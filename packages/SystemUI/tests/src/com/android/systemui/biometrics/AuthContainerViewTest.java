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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

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

        AuthContainerView.Config config = new AuthContainerView.Config();
        config.mContext = mContext;
        config.mCallback = mCallback;
        mAuthContainer = new TestableAuthContainer(config);
    }

    @Test
    public void testActionAuthenticated_sendsDismissedAuthenticated() {
        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_AUTHENTICATED);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_AUTHENTICATED));
    }

    @Test
    public void testActionUserCanceled_sendsDismissedUserCanceled() {
        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_USER_CANCELED);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_USER_CANCELED));
    }

    @Test
    public void testActionButtonNegative_sendsDismissedButtonNegative() {
        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE);
        verify(mCallback).onDismissed(eq(AuthDialogCallback.DISMISSED_BUTTON_NEGATIVE));
    }

    @Test
    public void testActionTryAgain_sendsTryAgain() {
        mAuthContainer.mBiometricCallback.onAction(
                AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        verify(mCallback).onTryAgainPressed();
    }

    private class TestableAuthContainer extends AuthContainerView {
        TestableAuthContainer(AuthContainerView.Config config) {
            super(config);
        }

        @Override
        public void animateAway(int reason) {
            mConfig.mCallback.onDismissed(reason);
        }
    }
}
