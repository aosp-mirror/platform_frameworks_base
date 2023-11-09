/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import android.os.Handler;
import android.os.Looper;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.utils.TestUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/** Tests for {@link RadiiAnimator}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
public class RadiiAnimatorTest extends SysuiTestCase {
    float[] mResultRadii = new float[RadiiAnimator.RADII_COUNT];
    final AtomicBoolean mAnimationStarted = new AtomicBoolean(false);
    final AtomicBoolean mAnimationStopped = new AtomicBoolean(false);
    final IRadiiAnimationListener mRadiiAnimationListener = new IRadiiAnimationListener() {
        @Override
        public void onRadiiAnimationUpdate(float[] radii) {
            mResultRadii = radii;
        }

        @Override
        public void onRadiiAnimationStart() {
            mAnimationStarted.set(true);
        }

        @Override
        public void onRadiiAnimationStop() {
            mAnimationStopped.set(true);
        }
    };

    @Test
    public void constructor() {
        final float[] radii = generateRadii(0.0f);
        final RadiiAnimator radiiAnimator = new RadiiAnimator(radii, mRadiiAnimationListener);
        assertThat(radiiAnimator.evaluate(0.0f)).isEqualTo(radii);
    }

    @Test
    public void skipAnimation_updatesToEnd() {
        final float[] startRadii = generateRadii(0.0f);
        final float[] endRadii = generateRadii(1.0f);

        final RadiiAnimator radiiAnimator = setupAnimator(startRadii);

        mAnimationStarted.set(false);
        mAnimationStopped.set(false);
        new Handler(Looper.getMainLooper()).post(() -> radiiAnimator.startAnimation(endRadii));
        TestUtils.waitForCondition(mAnimationStarted::get, "Animation did not start.");
        TestUtils.waitForCondition(() -> Arrays.equals(radiiAnimator.evaluate(0.0f), startRadii)
                        && Arrays.equals(radiiAnimator.evaluate(1.0f), endRadii),
                "Animator did not initialize to start and end values");
        new Handler(Looper.getMainLooper()).post(radiiAnimator::skipAnimationToEnd);
        TestUtils.waitForCondition(mAnimationStopped::get, "Animation did not stop.");
        assertThat(mResultRadii).usingTolerance(0.001).containsExactly(endRadii);
    }

    @Test
    public void finishedAnimation_canRepeat() {
        final float[] startRadii = generateRadii(0.0f);
        final float[] midRadii = generateRadii(1.0f);
        final float[] endRadii = generateRadii(2.0f);

        final RadiiAnimator radiiAnimator = setupAnimator(startRadii);

        playAndSkipAnimation(radiiAnimator, midRadii);
        assertThat(mResultRadii).usingTolerance(0.001).containsExactly(midRadii);

        playAndSkipAnimation(radiiAnimator, endRadii);
        assertThat(mResultRadii).usingTolerance(0.001).containsExactly(endRadii);
    }

    private float[] generateRadii(float value) {
        float[] radii = new float[8];
        Arrays.fill(radii, value);
        return radii;
    }

    private RadiiAnimator setupAnimator(float[] startRadii) {
        mResultRadii = new float[RadiiAnimator.RADII_COUNT];
        return new RadiiAnimator(startRadii, mRadiiAnimationListener);
    }

    private void playAndSkipAnimation(RadiiAnimator animator, float[] endRadii) {
        mAnimationStarted.set(false);
        mAnimationStopped.set(false);
        new Handler(Looper.getMainLooper()).post(() -> animator.startAnimation(endRadii));
        TestUtils.waitForCondition(mAnimationStarted::get, "Animation did not start.");
        new Handler(Looper.getMainLooper()).post(animator::skipAnimationToEnd);
        TestUtils.waitForCondition(mAnimationStopped::get, "Animation did not stop.");
    }
}
