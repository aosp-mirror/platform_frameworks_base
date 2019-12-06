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

package com.android.systemui.glwallpaper;

import static com.google.common.truth.Truth.assertThat;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ImageRevealHelperTest extends SysuiTestCase {

    static final int ANIMATION_DURATION = 500;
    ImageRevealHelper mImageRevealHelper;
    ImageRevealHelper.RevealStateListener mRevealStateListener;

    @Before
    public void setUp() throws Exception {
        mRevealStateListener = new ImageRevealHelper.RevealStateListener() {
            @Override
            public void onRevealStateChanged() {
                // no-op
            }

            @Override
            public void onRevealStart(boolean animate) {
                // no-op
            }

            @Override
            public void onRevealEnd() {
                // no-op
            }
        };
        mImageRevealHelper = new ImageRevealHelper(mRevealStateListener);
    }

    @Test
    public void testBiometricAuthUnlockAnimateImageRevealState_shouldNotBlackoutScreen() {
        assertThat(mImageRevealHelper.getReveal()).isEqualTo(0f);

        mImageRevealHelper.updateAwake(true /* awake */, ANIMATION_DURATION);
        assertThat(mImageRevealHelper.getReveal()).isEqualTo(0f);

        // When device unlock through Biometric, should not show reveal transition
        mImageRevealHelper.updateAwake(false /* awake */, 0);
        assertThat(mImageRevealHelper.getReveal()).isEqualTo(1f);
    }
}
