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

import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FACE;
import static android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT;
import static android.hardware.biometrics.BiometricManager.Authenticators;

import static com.android.systemui.biometrics.AuthBiometricView.Callback.ACTION_AUTHENTICATED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.biometrics.PromptInfo;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.LayoutInflater;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthBiometricViewTest extends SysuiTestCase {

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock
    private AuthBiometricView.Callback mCallback;
    @Mock
    private AuthPanelController mPanelController;

    private AuthBiometricView mBiometricView;

    @After
    public void tearDown() {
        destroyDialog();
    }

    @Test
    public void testOnAuthenticationSucceeded_noConfirmationRequired_sendsActionAuthenticated() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        // The onAuthenticated runnable is posted when authentication succeeds.
        mBiometricView.onAuthenticationSucceeded(TYPE_FINGERPRINT);
        waitForIdleSync();
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
        verify(mCallback).onAction(ACTION_AUTHENTICATED);
    }

    @Test
    public void testOnAuthenticationSucceeded_confirmationRequired_updatesDialogContents() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded(TYPE_FINGERPRINT);
        waitForIdleSync();

        // TODO: this should be tested in the subclasses
        if (mBiometricView.supportsRequireConfirmation()) {
            assertEquals(AuthBiometricView.STATE_PENDING_CONFIRMATION, mBiometricView.mState);

            verify(mCallback, never()).onAction(anyInt());

            assertEquals(View.GONE, mBiometricView.mNegativeButton.getVisibility());
            assertEquals(View.VISIBLE, mBiometricView.mCancelButton.getVisibility());
            assertTrue(mBiometricView.mCancelButton.isEnabled());

            assertTrue(mBiometricView.mConfirmButton.isEnabled());
            assertEquals(mContext.getText(R.string.biometric_dialog_tap_confirm),
                    mBiometricView.mIndicatorView.getText());
            assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());
        } else {
            assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
            verify(mCallback).onAction(eq(ACTION_AUTHENTICATED));
        }

    }

    @Test
    public void testPositiveButton_sendsActionAuthenticated() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.mConfirmButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(ACTION_AUTHENTICATED);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
    }

    @Test
    public void testNegativeButton_beforeAuthentication_sendsActionButtonNegative() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.onDialogAnimatedIn();
        mBiometricView.mNegativeButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE);
    }

    @Test
    public void testCancelButton_whenPendingConfirmation_sendsActionUserCanceled() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded(TYPE_FINGERPRINT);

        assertEquals(View.GONE, mBiometricView.mNegativeButton.getVisibility());

        mBiometricView.mCancelButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_USER_CANCELED);
    }

    @Test
    public void testTryAgainButton_sendsActionTryAgain() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.mTryAgainButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATING, mBiometricView.mState);
    }

    @Test
    @Ignore("flaky, b/189031816")
    public void testError_sendsActionError() {
        initDialog(false /* allowDeviceCredential */, mCallback);
        final String testError = "testError";
        mBiometricView.onError(TYPE_FACE, testError);
        waitForIdleSync();

        verify(mCallback).onAction(eq(AuthBiometricView.Callback.ACTION_ERROR));
        assertEquals(AuthBiometricView.STATE_IDLE, mBiometricView.mState);
    }

    @Test
    public void testBackgroundClicked_sendsActionUserCanceled() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_afterAuthenticated_neverSendsUserCanceled() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        mBiometricView.onAuthenticationSucceeded(TYPE_FINGERPRINT);
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_whenSmallDialog_neverSendsUserCanceled() {
        initDialog(false /* allowDeviceCredential */, mCallback);
        mBiometricView.mLayoutParams = new AuthDialog.LayoutParams(0, 0);
        mBiometricView.updateSize(AuthDialog.SIZE_SMALL);

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testIgnoresUselessHelp() {
        initDialog(false /* allowDeviceCredential */, mCallback);

        mBiometricView.onDialogAnimatedIn();
        waitForIdleSync();

        assertEquals(AuthBiometricView.STATE_AUTHENTICATING, mBiometricView.mState);

        mBiometricView.onHelp(TYPE_FINGERPRINT, "");
        waitForIdleSync();

        assertEquals("", mBiometricView.mIndicatorView.getText());
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_ERROR));
        assertEquals(AuthBiometricView.STATE_AUTHENTICATING, mBiometricView.mState);
    }

    @Test
    public void testRestoresState() {
        final boolean requireConfirmation = true;

        initDialog(false /* allowDeviceCredential */, mCallback, null, 10000);

        final String failureMessage = "testFailureMessage";
        mBiometricView.setRequireConfirmation(requireConfirmation);
        mBiometricView.onAuthenticationFailed(TYPE_FACE, failureMessage);
        waitForIdleSync();

        Bundle state = new Bundle();
        mBiometricView.onSaveState(state);

        assertEquals(View.GONE, mBiometricView.mTryAgainButton.getVisibility());
        assertEquals(View.GONE, state.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY));

        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(AuthBiometricView.STATE_ERROR, state.getInt(AuthDialog.KEY_BIOMETRIC_STATE));

        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());
        assertTrue(state.getBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING));

        assertEquals(failureMessage, mBiometricView.mIndicatorView.getText());
        assertEquals(failureMessage, state.getString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING));

        // TODO: Test dialog size. Should move requireConfirmation to buildBiometricPromptBundle

        // Create new dialog and restore the previous state into it
        destroyDialog();
        initDialog(false /* allowDeviceCredential */, mCallback, state, 10000);
        mBiometricView.mAnimationDurationHideDialog = 10000;
        mBiometricView.setRequireConfirmation(requireConfirmation);
        waitForIdleSync();

        assertEquals(View.GONE, mBiometricView.mTryAgainButton.getVisibility());
        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());

        // TODO: Test restored text. Currently cannot test this, since it gets restored only after
        // dialog size is known.
    }

    @Test
    public void testCredentialButton_whenDeviceCredentialAllowed() throws InterruptedException {
        initDialog(true /* allowDeviceCredential */, mCallback);

        assertEquals(View.VISIBLE, mBiometricView.mUseCredentialButton.getVisibility());
        assertEquals(View.GONE, mBiometricView.mNegativeButton.getVisibility());
        mBiometricView.mUseCredentialButton.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_USE_DEVICE_CREDENTIAL);
    }

    private PromptInfo buildPromptInfo(boolean allowDeviceCredential) {
        PromptInfo promptInfo = new PromptInfo();
        promptInfo.setTitle("Title");
        int authenticators = Authenticators.BIOMETRIC_WEAK;
        if (allowDeviceCredential) {
            authenticators |= Authenticators.DEVICE_CREDENTIAL;
        } else {
            promptInfo.setNegativeButtonText("Negative");
        }
        promptInfo.setAuthenticators(authenticators);
        return promptInfo;
    }

    private void initDialog(boolean allowDeviceCredential, AuthBiometricView.Callback callback) {
        initDialog(allowDeviceCredential, callback,
                null /* savedState */, 0 /* hideDelay */);
    }

    private void initDialog(boolean allowDeviceCredential,
            AuthBiometricView.Callback callback, Bundle savedState, int hideDelay) {
        final LayoutInflater inflater = LayoutInflater.from(mContext);
        mBiometricView = (AuthBiometricView) inflater.inflate(
                R.layout.auth_biometric_view, null, false);
        mBiometricView.mAnimationDurationLong = 0;
        mBiometricView.mAnimationDurationShort = 0;
        mBiometricView.mAnimationDurationHideDialog = hideDelay;
        mBiometricView.setPromptInfo(buildPromptInfo(allowDeviceCredential));
        mBiometricView.setCallback(callback);
        mBiometricView.restoreState(savedState);
        ViewUtils.attachView(mBiometricView);
        mBiometricView.setPanelController(mPanelController);
        waitForIdleSync();
    }

    private void destroyDialog() {
        if (mBiometricView != null && mBiometricView.isAttachedToWindow()) {
            ViewUtils.detachView(mBiometricView);
        }
    }

    @Override
    protected void waitForIdleSync() {
        TestableLooper.get(this).processAllMessages();
        super.waitForIdleSync();
    }
}
