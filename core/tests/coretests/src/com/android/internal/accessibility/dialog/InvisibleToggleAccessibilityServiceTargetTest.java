/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.internal.accessibility.dialog;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ParceledListSlice;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.Flags;
import android.view.accessibility.IAccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.internal.accessibility.TestUtils;
import com.android.internal.accessibility.common.ShortcutConstants;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

/**
 * Unit Tests for
 * {@link com.android.internal.accessibility.dialog.InvisibleToggleAccessibilityServiceTarget}
 */
@RunWith(AndroidJUnit4.class)
public class InvisibleToggleAccessibilityServiceTargetTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();
    @Mock
    private IAccessibilityManager mAccessibilityManagerService;
    @Captor
    private ArgumentCaptor<List<String>> mListCaptor;

    private static final String ALWAYS_ON_SERVICE_PACKAGE_LABEL = "always on a11y service";
    private static final String ALWAYS_ON_SERVICE_COMPONENT_NAME =
            "fake.package/fake.alwayson.service.name";
    private static final String FAKE_A11Y_SERVICE_SUMMARY = "A11yService summary";

    private ContextWrapper mContextSpy;
    private InvisibleToggleAccessibilityServiceTarget mSut;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContextSpy = spy(
                new ContextWrapper(InstrumentationRegistry.getInstrumentation().getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);

        AccessibilityManager accessibilityManager =
                new AccessibilityManager(
                        mContextSpy, mock(Handler.class),
                        mAccessibilityManagerService, UserHandle.myUserId(),
                        /* serviceConnect= */ true);
        when(mContextSpy.getSystemService(Context.ACCESSIBILITY_SERVICE))
                .thenReturn(accessibilityManager);

        AccessibilityServiceInfo accessibilityServiceInfo = TestUtils.createFakeServiceInfo(
                ALWAYS_ON_SERVICE_PACKAGE_LABEL,
                ALWAYS_ON_SERVICE_COMPONENT_NAME,
                FAKE_A11Y_SERVICE_SUMMARY,
                /* isAlwaysOnService*/ true);
        when(mAccessibilityManagerService.getInstalledAccessibilityServiceList(anyInt()))
                .thenReturn(
                        new ParceledListSlice<>(
                                Collections.singletonList(accessibilityServiceInfo)));

        mSut = new InvisibleToggleAccessibilityServiceTarget(
                mContextSpy,
                ShortcutConstants.UserShortcutType.HARDWARE, accessibilityServiceInfo);
    }

    @Test
    @EnableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_true_callA11yManagerToUpdateShortcuts() throws Exception {
        mSut.onCheckedChanged(true);

        verify(mAccessibilityManagerService).enableShortcutsForTargets(
                eq(true),
                eq(ShortcutConstants.UserShortcutType.HARDWARE),
                mListCaptor.capture(),
                anyInt());
        assertThat(mListCaptor.getValue()).containsExactly(ALWAYS_ON_SERVICE_COMPONENT_NAME);
    }

    @Test
    @EnableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_false_callA11yManagerToUpdateShortcuts() throws Exception {
        mSut.onCheckedChanged(false);
        verify(mAccessibilityManagerService).enableShortcutsForTargets(
                eq(false),
                eq(ShortcutConstants.UserShortcutType.HARDWARE),
                mListCaptor.capture(),
                anyInt());
        assertThat(mListCaptor.getValue()).containsExactly(ALWAYS_ON_SERVICE_COMPONENT_NAME);
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_turnOnShortcut_hasOtherShortcut_serviceKeepsOn() {
        enableA11yService(/* enable= */ true);
        addShortcutForA11yService(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, /* add= */ false);
        addShortcutForA11yService(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* add= */ true);

        mSut.onCheckedChanged(/* isChecked= */ true);

        assertA11yServiceState(/* enabled= */ true);
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_turnOnShortcut_noOtherShortcut_shouldTurnOnService() {
        enableA11yService(/* enable= */ false);
        addShortcutForA11yService(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, /* add= */ false);
        addShortcutForA11yService(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* add= */ false);

        mSut.onCheckedChanged(/* isChecked= */ true);

        assertA11yServiceState(/* enabled= */ true);
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_turnOffShortcut_hasOtherShortcut_serviceKeepsOn() {
        enableA11yService(/* enable= */ true);
        addShortcutForA11yService(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, /* add= */ true);
        addShortcutForA11yService(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* add= */ true);

        mSut.onCheckedChanged(/* isChecked= */ false);

        assertA11yServiceState(/* enabled= */ true);
    }

    @Test
    @DisableFlags(Flags.FLAG_MIGRATE_ENABLE_SHORTCUTS)
    public void onCheckedChanged_turnOffShortcut_noOtherShortcut_shouldTurnOffService() {
        enableA11yService(/* enable= */ true);
        addShortcutForA11yService(
                Settings.Secure.ACCESSIBILITY_SHORTCUT_TARGET_SERVICE, /* add= */ true);
        addShortcutForA11yService(Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS, /* add= */ false);

        mSut.onCheckedChanged(/* isChecked= */ false);

        assertA11yServiceState(/* enabled= */ false);
    }

    private void enableA11yService(boolean enable) {
        Settings.Secure.putString(
                mContextSpy.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                enable ? ALWAYS_ON_SERVICE_COMPONENT_NAME : "");
    }

    private void addShortcutForA11yService(String shortcutKey, boolean add) {
        Settings.Secure.putString(
                mContextSpy.getContentResolver(),
                shortcutKey,
                add ? ALWAYS_ON_SERVICE_COMPONENT_NAME : "");
    }

    private void assertA11yServiceState(boolean enabled) {
        if (enabled) {
            assertThat(
                    Settings.Secure.getString(
                            mContextSpy.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ).contains(ALWAYS_ON_SERVICE_COMPONENT_NAME);
        } else {
            assertThat(
                    Settings.Secure.getString(
                            mContextSpy.getContentResolver(),
                            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ).doesNotContain(ALWAYS_ON_SERVICE_COMPONENT_NAME);
        }
    }
}
