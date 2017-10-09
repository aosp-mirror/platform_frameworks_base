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

import static android.ext.services.notification.ChannelImpressions.STREAK_LIMIT;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

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

        for (int i = 0; i < STREAK_LIMIT - 1; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
        }

        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testNoStreakNoBlock_breakStreak() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i < STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
            if (i == STREAK_LIMIT - 1) {
                ci.resetStreak();
            }
        }

        assertFalse(ci.shouldTriggerBlock());
    }

    @Test
    public void testStreakBlock() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i <= STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
        }

        assertTrue(ci.shouldTriggerBlock());
    }

    @Test
    public void testRatio_NoBlockEvenWithStreak() {
        ChannelImpressions ci = new ChannelImpressions();

        for (int i = 0; i < STREAK_LIMIT; i++) {
            ci.incrementViews();
            ci.incrementDismissals();
            ci.incrementViews();
        }

        assertFalse(ci.shouldTriggerBlock());
    }
}
