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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Bundle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthBiometricViewTest extends SysuiTestCase {

    @Mock private AuthBiometricView.Callback mCallback;
    @Mock private AuthPanelController mPanelController;

    @Mock private Button mNegativeButton;
    @Mock private Button mPositiveButton;
    @Mock private Button mTryAgainButton;
    @Mock private TextView mTitleView;
    @Mock private TextView mSubtitleView;
    @Mock private TextView mDescriptionView;
    @Mock private TextView mIndicatorView;
    @Mock private ImageView mIconView;

    private TestableBiometricView mBiometricView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testOnAuthenticationSucceeded_noConfirmationRequired_sendsActionAuthenticated() {
        initDialog(mContext, mCallback, new MockInjector());

        // The onAuthenticated runnable is posted when authentication succeeds.
        mBiometricView.onAuthenticationSucceeded();
        waitForIdleSync();
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED);
    }

    @Test
    public void testOnAuthenticationSucceeded_confirmationRequired_updatesDialogContents() {
        initDialog(mContext, mCallback, new MockInjector());

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded();
        waitForIdleSync();
        assertEquals(AuthBiometricView.STATE_PENDING_CONFIRMATION, mBiometricView.mState);
        verify(mCallback, never()).onAction(anyInt());
        verify(mBiometricView.mNegativeButton).setText(eq(R.string.cancel));
        verify(mBiometricView.mPositiveButton).setEnabled(eq(true));
        verify(mIndicatorView).setText(eq(R.string.biometric_dialog_tap_confirm));
        verify(mIndicatorView).setVisibility(eq(View.VISIBLE));
    }

    @Test
    public void testPositiveButton_sendsActionAuthenticated() {
        Button button = new Button(mContext);
        initDialog(mContext, mCallback, new MockInjector() {
           @Override
            public Button getPositiveButton() {
               return button;
           }
        });

        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_AUTHENTICATED);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATED, mBiometricView.mState);
    }

    @Test
    public void testNegativeButton_beforeAuthentication_sendsActionButtonNegative() {
        Button button = new Button(mContext);
        initDialog(mContext, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return button;
            }
        });

        mBiometricView.onDialogAnimatedIn();
        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_NEGATIVE);
    }

    @Test
    public void testNegativeButton_whenPendingConfirmation_sendsActionUserCanceled() {
        Button button = new Button(mContext);
        initDialog(mContext, mCallback, new MockInjector() {
            @Override
            public Button getNegativeButton() {
                return button;
            }
        });

        mBiometricView.setRequireConfirmation(true);
        mBiometricView.onAuthenticationSucceeded();
        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_USER_CANCELED);
    }

    @Test
    public void testTryAgainButton_sendsActionTryAgain() {
        Button button = new Button(mContext);
        initDialog(mContext, mCallback, new MockInjector() {
            @Override
            public Button getTryAgainButton() {
                return button;
            }
        });

        button.performClick();
        waitForIdleSync();

        verify(mCallback).onAction(AuthBiometricView.Callback.ACTION_BUTTON_TRY_AGAIN);
        assertEquals(AuthBiometricView.STATE_AUTHENTICATING, mBiometricView.mState);
    }

    @Test
    public void testBackgroundClicked_sendsActionUserCanceled() {
        initDialog(mContext, mCallback, new MockInjector());

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_afterAuthenticated_neverSendsUserCanceled() {
        initDialog(mContext, mCallback, new MockInjector());

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        mBiometricView.onAuthenticationSucceeded();
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testBackgroundClicked_whenSmallDialog_neverSendsUserCanceled() {
        initDialog(mContext, mCallback, new MockInjector());
        mBiometricView.setPanelController(mPanelController);
        mBiometricView.updateSize(AuthDialog.SIZE_SMALL);

        View view = new View(mContext);
        mBiometricView.setBackgroundView(view);
        view.performClick();
        verify(mCallback, never()).onAction(eq(AuthBiometricView.Callback.ACTION_USER_CANCELED));
    }

    @Test
    public void testRestoresState() {
        final boolean requireConfirmation = true; // set/init from AuthController

        Button tryAgainButton = new Button(mContext);
        TextView indicatorView = new TextView(mContext);
        initDialog(mContext, mCallback, new MockInjector() {
            @Override
            public Button getTryAgainButton() {
                return tryAgainButton;
            }
            @Override
            public TextView getIndicatorView() {
                return indicatorView;
            }
        });

        final String failureMessage = "testFailureMessage";
        mBiometricView.setRequireConfirmation(requireConfirmation);
        mBiometricView.onAuthenticationFailed(failureMessage);
        waitForIdleSync();

        Bundle state = new Bundle();
        mBiometricView.onSaveState(state);

        assertEquals(View.VISIBLE, tryAgainButton.getVisibility());
        assertEquals(View.VISIBLE, state.getInt(AuthDialog.KEY_BIOMETRIC_TRY_AGAIN_VISIBILITY));

        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(AuthBiometricView.STATE_ERROR, state.getInt(AuthDialog.KEY_BIOMETRIC_STATE));

        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());
        assertTrue(state.getBoolean(AuthDialog.KEY_BIOMETRIC_INDICATOR_ERROR_SHOWING));

        assertEquals(failureMessage, mBiometricView.mIndicatorView.getText());
        assertEquals(failureMessage, state.getString(AuthDialog.KEY_BIOMETRIC_INDICATOR_STRING));

        // TODO: Test dialog size. Should move requireConfirmation to buildBiometricPromptBundle

        // Create new dialog and restore the previous state into it
        Button tryAgainButton2 = new Button(mContext);
        TextView indicatorView2 = new TextView(mContext);
        initDialog(mContext, mCallback, state, new MockInjector() {
            @Override
            public Button getTryAgainButton() {
                return tryAgainButton2;
            }
            @Override
            public TextView getIndicatorView() {
                return indicatorView2;
            }
        });
        mBiometricView.setRequireConfirmation(requireConfirmation);
        waitForIdleSync();

        // Test restored state
        assertEquals(View.VISIBLE, tryAgainButton.getVisibility());
        assertEquals(AuthBiometricView.STATE_ERROR, mBiometricView.mState);
        assertEquals(View.VISIBLE, mBiometricView.mIndicatorView.getVisibility());
        assertEquals(failureMessage, mBiometricView.mIndicatorView.getText());
    }

    private Bundle buildBiometricPromptBundle() {
        Bundle bundle = new Bundle();
        bundle.putCharSequence(BiometricPrompt.KEY_TITLE, "Title");
        bundle.putCharSequence(BiometricPrompt.KEY_NEGATIVE_TEXT, "Negative");
        return bundle;
    }

    private void initDialog(Context context, AuthBiometricView.Callback callback,
            Bundle savedState, MockInjector injector) {
        mBiometricView = new TestableBiometricView(context, null, injector);
        mBiometricView.setBiometricPromptBundle(buildBiometricPromptBundle());
        mBiometricView.setCallback(callback);
        mBiometricView.restoreState(savedState);
        mBiometricView.onFinishInflateInternal();
        mBiometricView.onAttachedToWindowInternal();
    }

    private void initDialog(Context context, AuthBiometricView.Callback callback,
            MockInjector injector) {
        initDialog(context, callback, null /* savedState */, injector);
    }

    private class MockInjector extends AuthBiometricView.Injector {
        @Override
        public Button getNegativeButton() {
            return mNegativeButton;
        }

        @Override
        public Button getPositiveButton() {
            return mPositiveButton;
        }

        @Override
        public Button getTryAgainButton() {
            return mTryAgainButton;
        }

        @Override
        public TextView getTitleView() {
            return mTitleView;
        }

        @Override
        public TextView getSubtitleView() {
            return mSubtitleView;
        }

        @Override
        public TextView getDescriptionView() {
            return mDescriptionView;
        }

        @Override
        public TextView getIndicatorView() {
            return mIndicatorView;
        }

        @Override
        public ImageView getIconView() {
            return mIconView;
        }
    }

    private class TestableBiometricView extends AuthBiometricView {
        TestableBiometricView(Context context, AttributeSet attrs,
                Injector injector) {
            super(context, attrs, injector);
        }

        @Override
        protected int getDelayAfterAuthenticatedDurationMs() {
            return 0; // Keep this at 0 for tests to invoke callback immediately.
        }

        @Override
        protected int getStateForAfterError() {
            return 0;
        }

        @Override
        protected void handleResetAfterError() {

        }

        @Override
        protected void handleResetAfterHelp() {

        }
    }
}
