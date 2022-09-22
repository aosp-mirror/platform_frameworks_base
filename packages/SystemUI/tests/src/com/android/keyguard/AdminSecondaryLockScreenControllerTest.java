/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.admin.IKeyguardCallback;
import android.app.admin.IKeyguardClient;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class AdminSecondaryLockScreenControllerTest extends SysuiTestCase {

    private static final int TARGET_USER_ID = KeyguardUpdateMonitor.getCurrentUser();

    private AdminSecondaryLockScreenController mTestController;
    private ComponentName mComponentName;
    private Intent mServiceIntent;
    private TestableLooper mTestableLooper;
    private KeyguardSecurityContainer mKeyguardSecurityContainer;

    @Mock
    private Handler mHandler;
    @Mock
    private IKeyguardClient.Stub mKeyguardClient;
    @Mock
    private KeyguardSecurityCallback mKeyguardCallback;
    @Mock
    private KeyguardUpdateMonitor mUpdateMonitor;
    @Mock
    private SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mKeyguardSecurityContainer = spy(new KeyguardSecurityContainer(mContext));
        mKeyguardSecurityContainer.setId(View.generateViewId());
        ViewUtils.attachView(mKeyguardSecurityContainer);

        mTestableLooper = TestableLooper.get(this);
        mComponentName = new ComponentName(mContext, "FakeKeyguardClient.class");
        mServiceIntent = new Intent().setComponent(mComponentName);

        mContext.addMockService(mComponentName, mKeyguardClient);
        // Have Stub.asInterface return the mocked interface.
        when(mKeyguardClient.queryLocalInterface(anyString())).thenReturn(mKeyguardClient);
        when(mKeyguardClient.asBinder()).thenReturn(mKeyguardClient);

        mTestController = new AdminSecondaryLockScreenController.Factory(
                mContext, mKeyguardSecurityContainer, mUpdateMonitor, mHandler)
                .create(mKeyguardCallback);
    }

    @After
    public void tearDown() {
        ViewUtils.detachView(mKeyguardSecurityContainer);
    }

    @Test
    public void testShow() throws Exception {
        doAnswer(invocation -> {
            IKeyguardCallback callback = (IKeyguardCallback) invocation.getArguments()[1];
            callback.onRemoteContentReady(mSurfacePackage);
            return null;
        }).when(mKeyguardClient).onCreateKeyguardSurface(any(), any(IKeyguardCallback.class));

        mTestController.show(mServiceIntent);

        verifySurfaceReady();
        assertThat(mContext.isBound(mComponentName)).isTrue();
    }

    @Test
    public void testShow_dismissedByCallback() throws Exception {
        doAnswer(answerVoid(Runnable::run)).when(mHandler).post(any(Runnable.class));
        doAnswer(invocation -> {
            IKeyguardCallback callback = (IKeyguardCallback) invocation.getArguments()[1];
            callback.onDismiss();
            return null;
        }).when(mKeyguardClient).onCreateKeyguardSurface(any(), any(IKeyguardCallback.class));

        mTestController.show(mServiceIntent);

        verifyViewDismissed(verifySurfaceReady());
    }

    @Test
    public void testHide() throws Exception {
        // Show the view first, then hide.
        doAnswer(invocation -> {
            IKeyguardCallback callback = (IKeyguardCallback) invocation.getArguments()[1];
            callback.onRemoteContentReady(mSurfacePackage);
            return null;
        }).when(mKeyguardClient).onCreateKeyguardSurface(any(), any(IKeyguardCallback.class));

        mTestController.show(mServiceIntent);
        SurfaceView v = verifySurfaceReady();

        mTestController.hide();
        verify(mKeyguardSecurityContainer).removeView(v);
        assertThat(mContext.isBound(mComponentName)).isFalse();
    }

    @Test
    public void testHide_notShown() throws Exception {
        mTestController.hide();
        // Nothing should happen if trying to hide when the view isn't attached yet.
        verify(mKeyguardSecurityContainer, never()).removeView(any(SurfaceView.class));
    }

    @Test
    public void testDismissed_onCreateKeyguardSurface_RemoteException() throws Exception {
        doThrow(new RemoteException()).when(mKeyguardClient)
                .onCreateKeyguardSurface(any(), any(IKeyguardCallback.class));

        mTestController.show(mServiceIntent);

        verifyViewDismissed(verifySurfaceReady());
    }

    @Test
    public void testDismissed_onCreateKeyguardSurface_timeout() throws Exception {
        // Mocked KeyguardClient never handles the onCreateKeyguardSurface, so the operation
        // times out, resulting in the view being dismissed.
        doAnswer(answerVoid(Runnable::run)).when(mHandler)
                .postDelayed(any(Runnable.class), anyLong());

        mTestController.show(mServiceIntent);

        verifyViewDismissed(verifySurfaceReady());
    }

    private SurfaceView verifySurfaceReady() throws Exception {
        mTestableLooper.processAllMessages();
        ArgumentCaptor<SurfaceView> captor = ArgumentCaptor.forClass(SurfaceView.class);
        verify(mKeyguardSecurityContainer).addView(captor.capture());

        mTestableLooper.processAllMessages();
        verify(mKeyguardClient).onCreateKeyguardSurface(any(), any(IKeyguardCallback.class));
        return captor.getValue();
    }

    private void verifyViewDismissed(SurfaceView v) throws Exception {
        verify(mKeyguardSecurityContainer).removeView(v);
        verify(mKeyguardCallback).dismiss(true, TARGET_USER_ID, true, SecurityMode.Invalid);
        assertThat(mContext.isBound(mComponentName)).isFalse();
    }
}
