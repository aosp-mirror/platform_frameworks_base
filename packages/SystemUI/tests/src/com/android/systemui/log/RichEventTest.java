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

package com.android.systemui.log;

import static junit.framework.Assert.assertEquals;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import junit.framework.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class RichEventTest extends SysuiTestCase {

    private static final int TOTAL_EVENT_TYPES = 1;

    @Test
    public void testCreateRichEvent_invalidType() {
        try {
            // indexing for events starts at 0, so TOTAL_EVENT_TYPES is an invalid type
            new TestableRichEvent(Event.DEBUG, TOTAL_EVENT_TYPES, "msg");
        } catch (IllegalArgumentException e) {
            // expected
            return;
        }

        Assert.fail("Expected an invalidArgumentException since the event type was invalid.");
    }

    @Test
    public void testCreateRichEvent() {
        final int eventType = 0;
        RichEvent e = new TestableRichEvent(Event.DEBUG, eventType, "msg");
        assertEquals(e.getType(), eventType);
    }

    class TestableRichEvent extends RichEvent {
        TestableRichEvent(int logLevel, int type, String reason) {
            super(logLevel, type, reason);
        }

        @Override
        public String[] getEventLabels() {
            return new String[]{"ACTION_NAME"};
        }
    }

}
