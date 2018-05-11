/**
 * Copyright (C) 2017 The Android Open Source Project
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

package android.ext.services.notification;

import static android.ext.services.notification.ChannelImpressions.DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT;
import static android.ext.services.notification.ChannelImpressions.DEFAULT_STREAK_LIMIT;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChannelImpressionsTest {

    @Test
    public void testNoResultNoBlock() {
        ChannelImpressions ci = new ChannelImpressions();
        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testNoStreakNoBlock() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i < DEFAULT_STREAK_LIMIT - 1; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
        }

        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testNoStreakNoBlock_breakStreak() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i < DEFAULT_STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
            if (i == DEFAULT_STREAK_LIMIT - 1) {
                ci.resetStreak();
            }
        }

        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testStreakBlock() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i <= DEFAULT_STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
        }

        assertTrue(ci.shouldTriggerBlock());
    }

    @Test
    public void testRatio_NoBlockEvenWithStreak() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i < DEFAULT_STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
            ci.incrementViews();
        }

        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testAppend() {
        ChannelImpressions ci = new ChannelImpressions();
        ci.incrementViews();
        ci.incrementDismissals();

        ChannelImpressions ci2 = new ChannelImpressions();
        ci2.incrementViews();
        ci2.incrementDismissals();
        ci2.incrementViews();

        ci.append(ci2);
        assertEquals(3, ci.getViews());
        assertEquals(2, ci.getDismissals());
        assertEquals(2, ci.getStreak());

        assertEquals(2, ci2.getViews());
        assertEquals(1, ci2.getDismissals());
        assertEquals(1, ci2.getStreak());

        // no crash
        ci.append(null);
    }

    @Test
    public void testUpdateThresholds_streakLimitsCorrectlyApplied() {
        int updatedStreakLimit = DEFAULT_STREAK_LIMIT + 3;
        ChannelImpressions ci = new ChannelImpressions();
        ci.updateThresholds(DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT, updatedStreakLimit);

        for (int i = 0; i <= updatedStreakLimit; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
        }

        ChannelImpressions ci2 = new ChannelImpressions();
        ci2.updateThresholds(DEFAULT_DISMISS_TO_VIEW_RATIO_LIMIT, updatedStreakLimit);

        for (int i = 0; i < updatedStreakLimit; i++) {
            ci2.incrementViews();
            ci2.incrementDismissals();
        }

        assertTrue(ci.shouldTriggerBlock());
        assertFalse(ci2.shouldTriggerBlock());
    }

    @Test
    public void testUpdateThresholds_ratioLimitsCorrectlyApplied() {
        float updatedDismissRatio = .99f;
        ChannelImpressions ci = new ChannelImpressions();
        ci.updateThresholds(updatedDismissRatio, DEFAULT_STREAK_LIMIT);

        // N views, N-1 dismissals, which doesn't satisfy the ratio = 1 criteria.
        for (int i = 0; i <= DEFAULT_STREAK_LIMIT; i++) {
            ci.incrementViews();
            if (i != DEFAULT_STREAK_LIMIT) {
                ci.incrementDismissals();
            }
        }

        ChannelImpressions ci2 = new ChannelImpressions();
        ci2.updateThresholds(updatedDismissRatio, DEFAULT_STREAK_LIMIT);

        for (int i = 0; i <= DEFAULT_STREAK_LIMIT; i++) {
            ci2.incrementViews();
            ci2.incrementDismissals();
        }

        assertFalse(ci.shouldTriggerBlock());
        assertTrue(ci2.shouldTriggerBlock());
    }
}
