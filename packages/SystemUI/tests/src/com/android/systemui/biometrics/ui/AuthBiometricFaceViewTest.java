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

package com.android.systemui.biometrics.ui;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.widget.ImageView;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.android.systemui.R;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class AuthBiometricFaceViewTest extends SysuiTestCase {

    @Mock
    AuthBiometricView.Callback mCallback;

    private TestableFaceView mFaceView;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mFaceView = new TestableFaceView(mContext);
        mFaceView.mIconController = mock(TestableFaceView.TestableIconController.class);
        mFaceView.setCallback(mCallback);
    }

    @Test
    public void testStateUpdated_whenDialogAnimatedIn() {
        mFaceView.onDialogAnimatedIn();
        verify(mFaceView.mIconController)
                .updateState(eq(AuthBiometricFaceView.STATE_AUTHENTICATING));
    }

    @Test
    public void testIconUpdatesState_whenDialogStateUpdated() {
        mFaceView.updateState(AuthBiometricFaceView.STATE_AUTHENTICATING);
        verify(mFaceView.mIconController)
                .updateState(eq(AuthBiometricFaceView.STATE_AUTHENTICATING));

        mFaceView.updateState(AuthBiometricFaceView.STATE_AUTHENTICATED);
        verify(mFaceView.mIconController)
                .updateState(eq(AuthBiometricFaceView.STATE_AUTHENTICATED));
    }

    public class TestableFaceView extends AuthBiometricFaceView {

        public class TestableIconController extends IconController {
            TestableIconController(Context context, ImageView iconView) {
                super(context, iconView);
            }

            public void startPulsing() {
                // Stub for testing
            }
        }

        @Override
        protected int getDelayAfterAuthenticatedDurationMs() {
            return 0; // Keep this at 0 for tests to invoke callback immediately.
        }

        public TestableFaceView(Context context) {
            super(context);
        }
    }

}
