/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.timezonedetector;

import static org.junit.Assert.assertEquals;

public class TestStateChangeListener implements StateChangeListener {

    private int mNotificationsReceived;

    @Override
    public void onChange() {
        mNotificationsReceived++;
    }

    /** Asserts the expected number of notifications have been received, then resets the count. */
    public void assertNotificationsReceivedAndReset(int expectedCount) {
        assertNotificationsReceived(expectedCount);
        resetNotificationsReceivedCount();
    }

    private void resetNotificationsReceivedCount() {
        mNotificationsReceived = 0;
    }

    private void assertNotificationsReceived(int expectedCount) {
        assertEquals(expectedCount, mNotificationsReceived);
    }
}
