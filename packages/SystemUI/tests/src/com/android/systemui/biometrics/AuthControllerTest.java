/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.biometrics;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNull;
import static junit.framework.TestCase.assertNotNull;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.biometrics.IBiometricServiceReceiverInternal;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBar;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthControllerTest extends SysuiTestCase {

    @Mock
    private PackageManager mPackageManager;
    @Mock
    private IBiometricServiceReceiverInternal mReceiver;
    @Mock
    private AuthDialog mDialog1;
    @Mock
    private AuthDialog mDialog2;

    private TestableBiometricDialogImpl mBiometricDialogImpl;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        TestableContext context = spy(mContext);

        mContext.putComponent(StatusBar.class, mock(StatusBar.class));
        mContext.putComponent(CommandQueue.class, mock(CommandQueue.class));

        when(context.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FACE))
            .thenReturn(true);
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT))
            .thenReturn(true);

        when(mDialog1.getOpPackageName()).thenReturn("Dialog1");
        when(mDialog2.getOpPackageName()).thenReturn("Dialog2");

        mBiometricDialogImpl = new TestableBiometricDialogImpl(new MockInjector());
        mBiometricDialogImpl.mContext = context;
        mBiometricDialogImpl.mComponents = mContext.getComponents();

        mBiometricDialogImpl.start();
    }

    // Callback tests

    @Test
    public void testSendsReasonUserCanceled_whenDismissedByUserCancel() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_USER_CANCELED);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_USER_CANCEL);
    }

    @Test
    public void testSendsReasonNegative_whenDismissedByButtonNegative() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_NEGATIVE);
    }

    @Test
    public void testSendsReasonConfirmed_whenDismissedByButtonPositive() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_BUTTON_POSITIVE);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_CONFIRMED);
    }

    @Test
    public void testSendsReasonConfirmNotRequired_whenDismissedByAuthenticated() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_AUTHENTICATED);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_CONFIRM_NOT_REQUIRED);
    }

    @Test
    public void testSendsReasonError_whenDismissedByError() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_ERROR);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_ERROR);
    }

    @Test
    public void testSendsReasonDismissedBySystemServer_whenDismissedByServer() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onDismissed(AuthDialogCallback.DISMISSED_BY_SYSTEM_SERVER);
        verify(mReceiver).onDialogDismissed(BiometricPrompt.DISMISSED_REASON_SERVER_REQUESTED);
    }

    // Statusbar tests

    @Test
    public void testShowInvoked_whenSystemRequested()
            throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());
    }

    @Test
    public void testOnAuthenticationSucceededInvoked_whenSystemRequested() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.onBiometricAuthenticated(true, null /* failureReason */);
        verify(mDialog1).onAuthenticationSucceeded();
    }

    @Test
    public void testOnAuthenticationFailedInvoked_whenSystemRequested() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        final String failureReason = "failure reason";
        mBiometricDialogImpl.onBiometricAuthenticated(false, failureReason);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onAuthenticationFailed(captor.capture());

        assertEquals(captor.getValue(), failureReason);
    }

    @Test
    public void testOnHelpInvoked_whenSystemRequested() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        final String helpMessage = "help";
        mBiometricDialogImpl.onBiometricHelp(helpMessage);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onHelp(captor.capture());

        assertEquals(captor.getValue(), helpMessage);
    }

    @Test
    public void testOnErrorInvoked_whenSystemRequested() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        final String errMessage = "error message";
        mBiometricDialogImpl.onBiometricError(errMessage);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mDialog1).onError(captor.capture());

        assertEquals(captor.getValue(), errMessage);
    }

    @Test
    public void testDismissWithoutCallbackInvoked_whenSystemRequested() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.hideBiometricDialog();
        verify(mDialog1).dismissFromSystemServer();
    }

    @Test
    public void testClientNotified_whenDismissedBySystemServer() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        mBiometricDialogImpl.hideBiometricDialog();
        verify(mDialog1).dismissFromSystemServer();

        assertNotNull(mBiometricDialogImpl.mCurrentDialog);
        assertNotNull(mBiometricDialogImpl.mReceiver);
    }

    // Corner case tests

    @Test
    public void testShowNewDialog_beforeOldDialogDismissed_SkipsAnimations() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());

        showDialog(BiometricPrompt.TYPE_FACE);

        // First dialog should be dismissed without animation
        verify(mDialog1).dismissWithoutCallback(eq(false) /* animate */);

        // Second dialog should be shown without animation
        verify(mDialog2).show(any(), any());
    }

    @Test
    public void testConfigurationPersists_whenOnConfigurationChanged() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);
        verify(mDialog1).show(any(), any());

        mBiometricDialogImpl.onConfigurationChanged(new Configuration());

        ArgumentCaptor<Bundle> captor = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog1).onSaveState(captor.capture());

        // Old dialog doesn't animate
        verify(mDialog1).dismissWithoutCallback(eq(false /* animate */));

        // Saved state is restored into new dialog
        ArgumentCaptor<Bundle> captor2 = ArgumentCaptor.forClass(Bundle.class);
        verify(mDialog2).show(any(), captor2.capture());

        // TODO: This should check all values we want to save/restore
        assertEquals(captor.getValue(), captor2.getValue());
    }

    @Test
    public void testClientNotified_whenTaskStackChangesDuringAuthentication() throws Exception {
        showDialog(BiometricPrompt.TYPE_FACE);

        List<ActivityManager.RunningTaskInfo> tasks = new ArrayList<>();
        ActivityManager.RunningTaskInfo taskInfo = mock(ActivityManager.RunningTaskInfo.class);
        taskInfo.topActivity = mock(ComponentName.class);
        when(taskInfo.topActivity.getPackageName()).thenReturn("other_package");
        tasks.add(taskInfo);
        when(mBiometricDialogImpl.mActivityTaskManager.getTasks(anyInt())).thenReturn(tasks);

        mBiometricDialogImpl.mTaskStackListener.onTaskStackChanged();
        waitForIdleSync();

        assertNull(mBiometricDialogImpl.mCurrentDialog);
        assertNull(mBiometricDialogImpl.mReceiver);
        verify(mDialog1).dismissWithoutCallback(true /* animate */);
        verify(mReceiver).onDialogDismissed(eq(BiometricPrompt.DISMISSED_REASON_USER_CANCEL));
    }

    // Helpers

    private void showDialog(int type) {
        mBiometricDialogImpl.showBiometricDialog(createTestDialogBundle(),
                mReceiver /* receiver */,
                type,
                true /* requireConfirmation */,
                0 /* userId */,
                "testPackage");
    }

    private Bundle createTestDialogBundle() {
        Bundle bundle = new Bundle();

        bundle.putCharSequence(BiometricPrompt.KEY_TITLE, "Title");
        bundle.putCharSequence(BiometricPrompt.KEY_SUBTITLE, "Subtitle");
        bundle.putCharSequence(BiometricPrompt.KEY_DESCRIPTION, "Description");
        bundle.putCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT, "Negative Button");

        // RequireConfirmation is a hint to BiometricService. This can be forced to be required
        // by user settings, and should be tested in BiometricService.
        bundle.putBoolean(BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true);

        return bundle;
    }

    private final class TestableBiometricDialogImpl extends AuthController {
        private int mBuildCount = 0;

        public TestableBiometricDialogImpl(Injector injector) {
            super(injector);
        }

        @Override
        protected AuthDialog buildDialog(Bundle biometricPromptBundle,
                boolean requireConfirmation, int userId, int type, String opPackageName,
                boolean skipIntro) {
            AuthDialog dialog;
            if (mBuildCount == 0) {
                dialog = mDialog1;
            } else if (mBuildCount == 1) {
                dialog = mDialog2;
            } else {
                dialog = null;
            }
            mBuildCount++;
            return dialog;
        }
    }

    private final class MockInjector extends AuthController.Injector {
        @Override
        IActivityTaskManager getActivityTaskManager() {
            return mock(IActivityTaskManager.class);
        }
    }
}

