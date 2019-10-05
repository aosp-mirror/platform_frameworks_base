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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.biometrics.Authenticator;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthContainerViewTest extends SysuiTestCase {

    private TestableAuthContainer mAuthContainer;

    private @Mock AuthDialogCallback mCallback;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testActionAuthenticated_sendsDismissedAuthenticated() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_AUTHENTICATED);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_BIOMETRIC_AUTHENTICATED));
    }

    @Test
    public void testActionUserCanceled_sendsDismissedUserCanceled() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_USER_CANCELED);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_USER_CANCELED));
    }

    @Test
    public void testActionButtonNegative_sendsDismissedButtonNegative() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE));
    }

    @Test
    public void testActionTryAgain_sendsTryAgain() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        verify(mCallback).onTryAgainPressed();
    }

    @Test
    public void testActionError_sendsDismissedError() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_ERROR);
        verify(mCallback).onDismissed(AuthDialogCallback.DISMISSED_ERROR);
    }

    @Test
    public void testActionUseDeviceCredential_sendsOnDeviceCredentialPressed() {
        initializeContainer(
                Authenticator.TYPE_BIOMETRIC | Authenticator.TYPE_CREDENTIAL);

        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL);
        verify(mCallback).onDeviceCredentialPressed();

        // Credential view is attached to the frame layout
        waitForIdleSync();
        assertNotNull(mAuthContainer.mCredentialView);
        verify(mAuthContainer.mFrameLayout).addView(eq(mAuthContainer.mCredentialView));
    }

    @Test
    public void testAnimateToCredentialUI_invokesStartTransitionToCredentialUI() {
        initializeContainer(
                Authenticator.TYPE_BIOMETRIC | Authenticator.TYPE_CREDENTIAL);

        mAuthContainer.mBiometricView = mock(AuthBiometricView.class);
        mAuthContainer.animateToCredentialUI();
        verify(mAuthContainer.mBiometricView).startTransitionToCredentialUI();
    }

    @Test
    public void testShowBiometricUI() {
        initializeContainer(Authenticator.TYPE_BIOMETRIC);

        assertNotEquals(null, mAuthContainer.mBiometricView);

        mAuthContainer.onAttachedToWindowInternal();
        verify(mAuthContainer.mBiometricScrollView).addView(mAuthContainer.mBiometricView);
        // Credential view is not added
        verify(mAuthContainer.mFrameLayout, never()).addView(any());
    }

    @Test
    public void testShowCredentialUI_doesNotInflateBiometricUI() {
        initializeContainer(Authenticator.TYPE_CREDENTIAL);

        mAuthContainer.onAttachedToWindowInternal();

        assertNull(null, mAuthContainer.mBiometricView);
        assertNotNull(mAuthContainer.mCredentialView);
        verify(mAuthContainer.mFrameLayout).addView(mAuthContainer.mCredentialView);
    }

    private void initializeContainer(int authenticators) {
        AuthContainerView.Config config = new AuthContainerView.Config();
        config.mContext = mContext;
        config.mCallback = mCallback;
        config.mModalityMask |= BiometricAuthenticator.TYPE_FINGERPRINT;

        Bundle bundle = new Bundle();
        bundle.putInt(BiometricPrompt.KEY_AUTHENTICATORS_ALLOWED, authenticators);
        config.mBiometricPromptBundle = bundle;

        mAuthContainer = new TestableAuthContainer(config);
    }

    private class TestableAuthContainer extends AuthContainerView {
        TestableAuthContainer(AuthContainerView.Config config) {
            super(config, new MockInjector());
        }

        @Override
        public void animateAway(int reason) {
            mConfig.mCallback.onDismissed(reason);
        }
    }

    private final class MockInjector extends AuthContainerView.Injector {
        @Override
        public ScrollView getBiometricScrollView(FrameLayout parent) {
            return mock(ScrollView.class);
        }

        @Override
        public FrameLayout inflateContainerView(LayoutInflater factory, ViewGroup root) {
            return mock(FrameLayout.class);
        }

        @Override
        public AuthPanelController getPanelController(Context context, View view,
                boolean isManagedProfile) {
            return mock(AuthPanelController.class);
        }

        @Override
        public ImageView getBackgroundView(FrameLayout parent) {
            return mock(ImageView.class);
        }

        @Override
        public View getPanelView(FrameLayout parent) {
            return mock(View.class);
        }
    }
}
