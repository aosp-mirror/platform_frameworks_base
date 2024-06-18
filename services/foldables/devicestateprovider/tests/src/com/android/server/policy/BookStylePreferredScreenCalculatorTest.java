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

package com.android.server.policy;


import static com.android.server.policy.BookStyleStateTransitions.DEFAULT_STATE_TRANSITIONS;

import static com.google.common.truth.Truth.assertWithMessage;

import android.testing.AndroidTestingRunner;

import com.android.server.policy.BookStylePreferredScreenCalculator.PreferredScreen;
import com.android.server.policy.BookStylePreferredScreenCalculator.HingeAngle;

import com.google.common.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for {@link BookStylePreferredScreenCalculator}.
 * <p/>
 * Run with <code>atest BookStyleClosedStateCalculatorTest</code>.
 */
@RunWith(AndroidTestingRunner.class)
public final class BookStylePreferredScreenCalculatorTest {

    private final BookStylePreferredScreenCalculator mCalculator =
            new BookStylePreferredScreenCalculator(DEFAULT_STATE_TRANSITIONS);

    private final List<HingeAngle> mHingeAngleValues = Arrays.asList(HingeAngle.values());
    private final List<Boolean> mLikelyTentModeValues = Arrays.asList(true, false);
    private final List<Boolean> mLikelyReverseWedgeModeValues = Arrays.asList(true, false);

    @Test
    public void transitionAllStates_noCrashes() {
        final List<List<Object>> arguments = Lists.cartesianProduct(Arrays.asList(
                mHingeAngleValues,
                mLikelyTentModeValues,
                mLikelyReverseWedgeModeValues
        ));

        arguments.forEach(objects -> {
            final HingeAngle hingeAngle = (HingeAngle) objects.get(0);
            final boolean likelyTent = (boolean) objects.get(1);
            final boolean likelyReverseWedge = (boolean) objects.get(2);

            final String description =
                    "Input: hinge angle = " + hingeAngle + ", likelyTent = " + likelyTent
                            + ", likelyReverseWedge = " + likelyReverseWedge;

            // Verify that there are no crashes because of infinite state transitions and
            // that it returns a valid active state
            try {
                PreferredScreen preferredScreen = mCalculator.calculatePreferredScreen(hingeAngle, likelyTent,
                        likelyReverseWedge);

                assertWithMessage(description).that(preferredScreen).isNotEqualTo(PreferredScreen.INVALID);
            } catch (Throwable exception) {
                throw new AssertionError(description, exception);
            }
        });
    }
}
