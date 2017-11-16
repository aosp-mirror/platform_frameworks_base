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
}
