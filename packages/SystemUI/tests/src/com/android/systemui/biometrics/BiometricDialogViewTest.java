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
import static junit.framework.Assert.assertNotSame;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.spy;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WakefulnessLifecycle;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class BiometricDialogViewTest extends SysuiTestCase {

    FaceDialogView mFaceDialogView;

    private static final String TITLE = "Title";
    private static final String SUBTITLE = "Subtitle";
    private static final String DESCRIPTION = "Description";
    private static final String NEGATIVE_BUTTON = "Negative Button";

    private static final String TEST_HELP = "Help";

    TestableContext mTestableContext;
    @Mock
    private AuthDialogCallback mCallback;
    @Mock
    private UserManager mUserManager;
    @Mock
    private DevicePolicyManager mDpm;

    private static class Injector extends BiometricDialogView.Injector {
        @Override
        public WakefulnessLifecycle getWakefulnessLifecycle() {
            final WakefulnessLifecycle lifecycle = new WakefulnessLifecycle();
            lifecycle.dispatchFinishedWakingUp();
            return lifecycle;
        }
    }

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestableContext = spy(mContext);
        mTestableContext.addMockSystemService(UserManager.class, mUserManager);
        mTestableContext.addMockSystemService(DevicePolicyManager.class, mDpm);
    }

    @Test
    public void testContentStates_confirmationRequired_authenticated() {
        mFaceDialogView = buildFaceDialogView(mTestableContext, mCallback,
                true /* requireConfirmation */);
        mFaceDialogView.onAttachedToWindow();

        // When starting authentication
        assertEquals(View.VISIBLE, mFaceDialogView.mTitleText.getVisibility());
        assertEquals(View.VISIBLE, mFaceDialogView.mSubtitleText.getVisibility());
        assertEquals(View.VISIBLE, mFaceDialogView.mDescriptionText.getVisibility());
        assertEquals(View.INVISIBLE, mFaceDialogView.mErrorText.getVisibility());
        assertEquals(View.VISIBLE, mFaceDialogView.mPositiveButton.getVisibility());
        assertEquals(View.VISIBLE, mFaceDialogView.mNegativeButton.getVisibility());
        assertEquals(View.GONE, mFaceDialogView.mTryAgainButton.getVisibility());

        // Contents are as expected
        assertTrue(TITLE.contentEquals(mFaceDialogView.mTitleText.getText()));
        assertTrue(SUBTITLE.contentEquals(mFaceDialogView.mSubtitleText.getText()));
        assertTrue(DESCRIPTION.contentEquals(mFaceDialogView.mDescriptionText.getText()));
        assertTrue(mFaceDialogView.mPositiveButton.getText().toString()
                .contentEquals(mContext.getString(R.string.biometric_dialog_confirm)));
        assertTrue(NEGATIVE_BUTTON.contentEquals(mFaceDialogView.mNegativeButton.getText()));
        assertTrue(mFaceDialogView.mTryAgainButton.getText().toString()
                .contentEquals(mContext.getString(R.string.biometric_dialog_try_again)));

        // When help message is received
        mFaceDialogView.onHelp(TEST_HELP);
        assertEquals(mFaceDialogView.mErrorText.getVisibility(), View.VISIBLE);
        assertTrue(TEST_HELP.contentEquals(mFaceDialogView.mErrorText.getText()));

        // When authenticated, confirm button comes out
        mFaceDialogView.onAuthenticationSucceeded();
        assertEquals(View.VISIBLE, mFaceDialogView.mPositiveButton.getVisibility());
        assertEquals(true, mFaceDialogView.mPositiveButton.isEnabled());
    }

    @Test
    public void testContentStates_confirmationNotRequired_authenticated() {
        mFaceDialogView = buildFaceDialogView(mTestableContext, mCallback,
                false /* requireConfirmation */);
        mFaceDialogView.onAttachedToWindow();
        mFaceDialogView.updateSize(FaceDialogView.SIZE_SMALL);

        assertEquals(View.INVISIBLE, mFaceDialogView.mTitleText.getVisibility());
        assertNotSame(View.VISIBLE, mFaceDialogView.mSubtitleText.getVisibility());
        assertNotSame(View.VISIBLE, mFaceDialogView.mDescriptionText.getVisibility());
        assertEquals(View.INVISIBLE, mFaceDialogView.mErrorText.getVisibility());
        assertEquals(View.GONE, mFaceDialogView.mPositiveButton.getVisibility());
        assertEquals(View.GONE, mFaceDialogView.mTryAgainButton.getVisibility());
        assertEquals(View.GONE, mFaceDialogView.mTryAgainButton.getVisibility());
    }

    @Test
    public void testContentStates_confirmationNotRequired_help() {
        mFaceDialogView = buildFaceDialogView(mTestableContext, mCallback,
                false /* requireConfirmation */);
        mFaceDialogView.onAttachedToWindow();

        mFaceDialogView.onHelp(TEST_HELP);
        assertEquals(mFaceDialogView.mErrorText.getVisibility(), View.VISIBLE);
        assertTrue(TEST_HELP.contentEquals(mFaceDialogView.mErrorText.getText()));
    }

    @Test
    public void testBack_sendsUserCanceled() {
        // TODO: Need robolectric framework to wait for handler to complete
    }

    @Test
    public void testScreenOff_sendsUserCanceled() {
        // TODO: Need robolectric framework to wait for handler to complete
    }

    @Test
    public void testRestoreState_contentStatesCorrect() {
        mFaceDialogView = buildFaceDialogView(mTestableContext, mCallback,
                false /* requireConfirmation */);
        mFaceDialogView.onAttachedToWindow();
        mFaceDialogView.onAuthenticationFailed(TEST_HELP);

        final Bundle bundle = new Bundle();
        mFaceDialogView.onSaveState(bundle);

        mFaceDialogView = buildFaceDialogView(mTestableContext, mCallback,
                false /* requireConfirmation */);
        mFaceDialogView.restoreState(bundle);
        mFaceDialogView.onAttachedToWindow();

        assertEquals(View.VISIBLE, mFaceDialogView.mTryAgainButton.getVisibility());
    }

    private FaceDialogView buildFaceDialogView(Context context, AuthDialogCallback callback,
            boolean requireConfirmation) {
        return (FaceDialogView) new BiometricDialogView.Builder(context)
                .setCallback(callback)
                .setBiometricPromptBundle(createTestDialogBundle())
                .setRequireConfirmation(requireConfirmation)
                .setUserId(0)
                .setOpPackageName("test_package")
                .build(BiometricDialogView.Builder.TYPE_FACE, new Injector());
    }

    private Bundle createTestDialogBundle() {
        Bundle bundle = new Bundle();

        bundle.putCharSequence(BiometricPrompt.KEY_TITLE, TITLE);
        bundle.putCharSequence(BiometricPrompt.KEY_SUBTITLE, SUBTITLE);
        bundle.putCharSequence(BiometricPrompt.KEY_DESCRIPTION, DESCRIPTION);
        bundle.putCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT, NEGATIVE_BUTTON);

        // RequireConfirmation is a hint to BiometricService. This can be forced to be required
        // by user settings, and should be tested in BiometricService.
        bundle.putBoolean(BiometricPrompt.KEY_REQUIRE_CONFIRMATION, true);

        return bundle;
    }
}
