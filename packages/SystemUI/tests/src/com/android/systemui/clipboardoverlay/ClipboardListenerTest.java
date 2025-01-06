/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.clipboardoverlay;

import static com.google.android.setupcompat.util.WizardManagerHelper.SETTINGS_SECURE_USER_SETUP_COMPLETE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.provider.Settings;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.settings.FakeUserTracker;
import com.android.systemui.user.utils.FakeUserScopedService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Provider;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardListenerTest extends SysuiTestCase {

    @Mock
    private ClipboardManager mClipboardManager;
    @Mock
    private KeyguardManager mKeyguardManager;
    @Mock
    private ClipboardManager mClipboardManagerSecondaryUser;
    @Mock
    private KeyguardManager mKeyguardManagerSecondaryUser;
    @Mock
    private ClipboardOverlayController mOverlayController;
    @Mock
    private ClipboardToast mClipboardToast;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private ClipboardOverlaySuppressionController mClipboardOverlaySuppressionController;

    @Captor
    private ArgumentCaptor<Runnable> mRunnableCaptor;
    @Captor
    private ArgumentCaptor<ClipData> mClipDataCaptor;
    @Captor
    private ArgumentCaptor<String> mStringCaptor;

    @Spy
    private Provider<ClipboardOverlayController> mOverlayControllerProvider;

    private final FakeUserScopedService<ClipboardManager> mUserScopedClipboardManager =
            new FakeUserScopedService<>(mClipboardManager);
    private final FakeUserScopedService<KeyguardManager> mUserScopedKeyguardManager =
            new FakeUserScopedService<>(mKeyguardManager);
    private final FakeUserTracker mUserTracker = new FakeUserTracker();

    private final List<UserInfo> mUserInfos = List.of(
            new UserInfo(0, "system", 0), new UserInfo(50, "secondary", 0));
    private final ClipData mSampleClipData = new ClipData("Test", new String[]{"text/plain"},
            new ClipData.Item("Test Item"));
    private final ClipData mSecondaryClipData = new ClipData(
            "Test secondary", new String[]{"text/plain"}, new ClipData.Item("Secondary Item"));
    private final String mSampleSource = "Example source";

    private ClipboardListener mClipboardListener;


    @Before
    public void setup() {
        mOverlayControllerProvider = () -> mOverlayController;

        MockitoAnnotations.initMocks(this);

        Settings.Secure.putInt(
                mContext.getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 1);

        mUserTracker.set(mUserInfos, 0);
        UserHandle user0 = mUserInfos.get(0).getUserHandle();
        UserHandle user1 = mUserInfos.get(1).getUserHandle();
        mUserScopedKeyguardManager.addImplementation(user0, mKeyguardManager);
        mUserScopedKeyguardManager.addImplementation(user1, mKeyguardManagerSecondaryUser);
        setupClipboardManager(mClipboardManager, user0, mSampleClipData);
        setupClipboardManager(mClipboardManagerSecondaryUser, user1, mSecondaryClipData);

        mClipboardListener = new ClipboardListener(
                getContext(),
                mOverlayControllerProvider,
                mClipboardToast,
                mUserTracker,
                mUserScopedClipboardManager,
                mUserScopedKeyguardManager,
                mUiEventLogger,
                getContext().getMainExecutor(),
                mClipboardOverlaySuppressionController);
    }

    private void setupClipboardManager(
            ClipboardManager clipboardManager, UserHandle user, ClipData clipData) {
        when(clipboardManager.hasPrimaryClip()).thenReturn(true);
        when(clipboardManager.getPrimaryClip()).thenReturn(clipData);
        when(clipboardManager.getPrimaryClipSource()).thenReturn(mSampleSource);
        mUserScopedClipboardManager.addImplementation(user, clipboardManager);
    }


    @Test
    public void test_initialization() {
        mClipboardListener.start();
        verify(mClipboardManager).addPrimaryClipChangedListener(any());
        verifyNoMoreInteractions(mUiEventLogger);
    }

    @Test
    public void test_consecutiveCopies() {
        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mOverlayControllerProvider).get();

        verify(mOverlayController).setClipData(
                mClipDataCaptor.capture(), mStringCaptor.capture());

        assertEquals(mSampleClipData, mClipDataCaptor.getValue());
        assertEquals(mSampleSource, mStringCaptor.getValue());

        verify(mOverlayController).setOnSessionCompleteListener(mRunnableCaptor.capture());

        // Should clear the overlay controller
        mRunnableCaptor.getValue().run();

        mClipboardListener.onPrimaryClipChanged();

        verify(mOverlayControllerProvider, times(2)).get();

        // Not calling the runnable here, just change the clip again and verify that the overlay is
        // NOT recreated.

        mClipboardListener.onPrimaryClipChanged();

        verify(mOverlayControllerProvider, times(2)).get();
    }

    @Test
    @DisableFlags(Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    public void test_noSwitchUserWithFlagOff() {
        mClipboardListener.start();

        mClipboardListener.onPrimaryClipChanged();
        mUserTracker.set(mUserInfos, 1);
        mUserTracker.onUserChanged(mUserInfos.get(1).id);
        mClipboardListener.onPrimaryClipChanged();

        verify(mKeyguardManager, times(2)).isDeviceLocked();
        verify(mClipboardManager, times(2)).hasPrimaryClip();
        verify(mOverlayController, times(2)).setClipData(mSampleClipData, mSampleSource);
        verifyNoMoreInteractions(mClipboardManagerSecondaryUser);
        verifyNoMoreInteractions(mKeyguardManagerSecondaryUser);
    }

    @Test
    @EnableFlags(Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    public void test_switchUserSwitchesClipboard() {
        mClipboardListener.start();

        mClipboardListener.onPrimaryClipChanged();
        verify(mClipboardManager).hasPrimaryClip();
        verify(mOverlayController).setClipData(mSampleClipData, mSampleSource);

        mUserTracker.set(mUserInfos, 1);
        mUserTracker.onUserChanged(mUserInfos.get(1).id);
        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardManagerSecondaryUser).hasPrimaryClip();
        verify(mOverlayController).setClipData(mSecondaryClipData, mSampleSource);
    }

    @Test
    @DisableFlags(Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER)
    @EnableFlags(Flags.FLAG_CLIPBOARD_NONINTERACTIVE_ON_LOCKSCREEN)
    public void test_deviceLockedForSecondaryUser_withoutMultiuser_showsOverlay() {
        when(mKeyguardManager.isDeviceLocked()).thenReturn(false);
        when(mKeyguardManagerSecondaryUser.isDeviceLocked()).thenReturn(true);

        mClipboardListener.start();
        mUserTracker.set(mUserInfos, 1);
        mUserTracker.onUserChanged(mUserInfos.get(1).id);
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mOverlayController).setClipData(mSampleClipData, mSampleSource);
        verifyNoMoreInteractions(mClipboardToast);
    }

    @Test
    @EnableFlags({Flags.FLAG_CLIPBOARD_OVERLAY_MULTIUSER,
            Flags.FLAG_CLIPBOARD_NONINTERACTIVE_ON_LOCKSCREEN})
    public void test_deviceLockedForSecondaryUser_showsToast() {
        when(mKeyguardManager.isDeviceLocked()).thenReturn(false);
        when(mKeyguardManagerSecondaryUser.isDeviceLocked()).thenReturn(true);

        mClipboardListener.start();
        mUserTracker.set(mUserInfos, 1);
        mUserTracker.onUserChanged(mUserInfos.get(1).id);
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN, 0, mSampleSource);
        verify(mClipboardToast, times(1)).showCopiedToast();
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    @DisableFlags(Flags.FLAG_OVERRIDE_SUPPRESS_OVERLAY_CONDITION)
    public void test_shouldSuppressOverlay() {
        // Regardless of the package or emulator, nothing should be suppressed without the flag
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData, mSampleSource,
                false));
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData,
                ClipboardListener.SHELL_PACKAGE, false));
        assertFalse(ClipboardListener.shouldSuppressOverlay(mSampleClipData, mSampleSource,
                true));

        ClipDescription desc = new ClipDescription("Test", new String[]{"text/plain"});
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(ClipboardListener.EXTRA_SUPPRESS_OVERLAY, true);
        desc.setExtras(bundle);
        ClipData suppressableClipData = new ClipData(desc, new ClipData.Item("Test Item"));

        // Clip data with the suppression extra is only honored in the emulator or with the shell
        // package.
        assertFalse(ClipboardListener.shouldSuppressOverlay(suppressableClipData, mSampleSource,
                false));
        assertTrue(ClipboardListener.shouldSuppressOverlay(suppressableClipData, mSampleSource,
                true));
        assertTrue(ClipboardListener.shouldSuppressOverlay(suppressableClipData,
                ClipboardListener.SHELL_PACKAGE, false));
    }

    @Test
    public void test_logging_enterAndReenter() {
        mClipboardListener.start();

        mClipboardListener.onPrimaryClipChanged();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED, 0, mSampleSource);
    }

    @Test
    public void test_userSetupIncomplete_showsToast() {
        Settings.Secure.putInt(
                mContext.getContentResolver(), SETTINGS_SECURE_USER_SETUP_COMPLETE, 0);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN, 0, mSampleSource);
        verify(mClipboardToast, times(1)).showCopiedToast();
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    @EnableFlags(Flags.FLAG_CLIPBOARD_NONINTERACTIVE_ON_LOCKSCREEN)
    public void test_deviceLocked_showsToast() {
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN, 0, mSampleSource);
        verify(mClipboardToast, times(1)).showCopiedToast();
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    @DisableFlags(Flags.FLAG_CLIPBOARD_NONINTERACTIVE_ON_LOCKSCREEN)
    public void test_deviceLocked_legacyBehavior_showsInteractiveUI() {
        when(mKeyguardManager.isDeviceLocked()).thenReturn(true);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mOverlayController).setClipData(mSampleClipData, mSampleSource);
        verifyNoMoreInteractions(mClipboardToast);
    }

    @Test
    @EnableFlags(Flags.FLAG_OVERRIDE_SUPPRESS_OVERLAY_CONDITION)
    public void test_onPrimaryClipChanged_notSuppressOverlay() {
        when(mClipboardOverlaySuppressionController.shouldSuppressOverlay(any(), any(),
                anyBoolean())).thenReturn(false);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardOverlaySuppressionController).shouldSuppressOverlay(mSampleClipData,
                mSampleSource, Build.IS_EMULATOR);
        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mOverlayController).setClipData(mSampleClipData, mSampleSource);
    }

    @Test
    @EnableFlags(Flags.FLAG_OVERRIDE_SUPPRESS_OVERLAY_CONDITION)
    public void test_onPrimaryClipChanged_suppressOverlay() {
        when(mClipboardOverlaySuppressionController.shouldSuppressOverlay(any(), any(),
                anyBoolean())).thenReturn(true);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mClipboardOverlaySuppressionController).shouldSuppressOverlay(mSampleClipData,
                mSampleSource, Build.IS_EMULATOR);
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    @DisableFlags(Flags.FLAG_OVERRIDE_SUPPRESS_OVERLAY_CONDITION)
    public void test_onPrimaryClipChanged_legacyBehavior_notSuppressOverlay() {
        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED, 0, mSampleSource);
        verify(mOverlayController).setClipData(mSampleClipData, mSampleSource);
    }

    @Test
    @DisableFlags(Flags.FLAG_OVERRIDE_SUPPRESS_OVERLAY_CONDITION)
    public void test_onPrimaryClipChanged_legacyBehavior_suppressOverlay() {
        ClipDescription desc = new ClipDescription("Test", new String[]{"text/plain"});
        PersistableBundle bundle = new PersistableBundle();
        bundle.putBoolean(ClipboardListener.EXTRA_SUPPRESS_OVERLAY, true);
        desc.setExtras(bundle);
        ClipData suppressableClipData = new ClipData(desc, new ClipData.Item("Test Item"));
        when(mClipboardManager.getPrimaryClip()).thenReturn(suppressableClipData);
        when(mClipboardManager.getPrimaryClipSource()).thenReturn(ClipboardListener.SHELL_PACKAGE);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    public void test_nullClipData_showsNothing() {
        when(mClipboardManager.getPrimaryClip()).thenReturn(null);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verifyNoMoreInteractions(mUiEventLogger);
        verifyNoMoreInteractions(mClipboardToast);
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }

    @Test
    public void test_emptyClipData_showsToast() {
        ClipDescription description = new ClipDescription("Test", new String[0]);
        ClipData noItems = new ClipData(description, new ArrayList<>());
        when(mClipboardManager.getPrimaryClip()).thenReturn(noItems);

        mClipboardListener.start();
        mClipboardListener.onPrimaryClipChanged();

        verify(mUiEventLogger, times(1)).log(
                ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN, 0, mSampleSource);
        verify(mClipboardToast, times(1)).showCopiedToast();
        verifyNoMoreInteractions(mOverlayControllerProvider);
    }
}
