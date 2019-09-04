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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
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
    @Mock private TextView mErrorView;
    @Mock private ImageView mIconView;

    TestableBiometricView mBiometricView;

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
        verify(mErrorView).setText(eq(R.string.biometric_dialog_tap_confirm));
        verify(mErrorView).setVisibility(eq(View.VISIBLE));
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

    private void initDialog(Context context, AuthBiometricView.Callback callback,
            MockInjector injector) {
        mBiometricView = new TestableBiometricView(context, null, injector);
        mBiometricView.setCallback(callback);
        mBiometricView.initializeViews();
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
        public TextView getErrorView() {
            return mErrorView;
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
