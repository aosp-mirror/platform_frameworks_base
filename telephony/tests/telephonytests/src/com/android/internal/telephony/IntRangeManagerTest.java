/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.internal.telephony;

import android.test.AndroidTestCase;

import com.android.internal.telephony.gsm.SmsBroadcastConfigInfo;

import java.util.ArrayList;

/**
 * Test cases for the IntRangeManager class.
 */
public class IntRangeManagerTest extends AndroidTestCase {

    private static final int SMS_CB_CODE_SCHEME_MIN = 0;
    private static final int SMS_CB_CODE_SCHEME_MAX = 255;

    private static final int FLAG_START_UPDATE_CALLED   = 0x01;
    private static final int FLAG_ADD_RANGE_CALLED      = 0x02;
    private static final int FLAG_FINISH_UPDATE_CALLED  = 0x04;

    private static final int ALL_FLAGS_SET = FLAG_START_UPDATE_CALLED | FLAG_ADD_RANGE_CALLED |
            FLAG_FINISH_UPDATE_CALLED;

    /** Dummy IntRangeManager for testing. */
    class TestIntRangeManager extends IntRangeManager {
        ArrayList<SmsBroadcastConfigInfo> mConfigList =
                new ArrayList<SmsBroadcastConfigInfo>();

        int flags;
        boolean finishUpdateReturnValue = true;

        /**
         * Called when the list of enabled ranges has changed. This will be
         * followed by zero or more calls to {@link #addRange} followed by
         * a call to {@link #finishUpdate}.
         */
        protected void startUpdate() {
            mConfigList.clear();
            flags |= FLAG_START_UPDATE_CALLED;
        }

        /**
         * Called after {@link #startUpdate} to indicate a range of enabled
         * values.
         * @param startId the first id included in the range
         * @param endId the last id included in the range
         */
        protected void addRange(int startId, int endId, boolean selected) {
            mConfigList.add(new SmsBroadcastConfigInfo(startId, endId,
                        SMS_CB_CODE_SCHEME_MIN, SMS_CB_CODE_SCHEME_MAX, selected));
            flags |= FLAG_ADD_RANGE_CALLED;
        }

        /**
         * Called to indicate the end of a range update started by the
         * previous call to {@link #startUpdate}.
         */
        protected boolean finishUpdate() {
            flags |= FLAG_FINISH_UPDATE_CALLED;
            return finishUpdateReturnValue;
        }

        /** Reset the object for the next test case. */
        void reset() {
            flags = 0;
            mConfigList.clear();
        }
    }

    public void testEmptyRangeManager() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("expecting empty configlist", 0, testManager.mConfigList.size());
    }

    private void checkConfigInfo(SmsBroadcastConfigInfo info, int fromServiceId,
            int toServiceId, int fromCodeScheme, int toCodeScheme, boolean selected) {
        assertEquals("fromServiceId", fromServiceId, info.getFromServiceId());
        assertEquals("toServiceId", toServiceId, info.getToServiceId());
        assertEquals("fromCodeScheme", fromCodeScheme, info.getFromCodeScheme());
        assertEquals("toCodeScheme", toCodeScheme, info.getToCodeScheme());
        assertEquals("selected", selected, info.isSelected());
    }

    public void testAddSingleChannel() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("flags before test", 0, testManager.flags);
        assertTrue("enabling range", testManager.enableRange(123, 123, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 123, 123, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 123, 123, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
    }

    public void testRemoveSingleChannel() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertTrue("enabling range", testManager.enableRange(123, 123, "client1"));
        assertEquals("flags after enable", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        testManager.reset();
        assertTrue("disabling range", testManager.disableRange(123, 123, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 123, 123, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", FLAG_START_UPDATE_CALLED | FLAG_FINISH_UPDATE_CALLED,
                testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
    }

    public void testRemoveBadChannel() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertFalse("disabling missing range", testManager.disableRange(123, 123, "client1"));
        assertEquals("flags after test", 0, testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
    }

    public void testAddTwoChannels() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("flags before test", 0, testManager.flags);
        assertTrue("enabling range 1", testManager.enableRange(100, 120, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 120, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 2", testManager.enableRange(200, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 200, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 2, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 120, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 200, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
    }

    public void testOverlappingChannels() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("flags before test", 0, testManager.flags);
        assertTrue("enabling range 1", testManager.enableRange(100, 200, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 200, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 2", testManager.enableRange(150, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 201, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 1", testManager.disableRange(100, 200, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 149, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("disabling range 2", testManager.disableRange(150, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 150, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", FLAG_START_UPDATE_CALLED | FLAG_FINISH_UPDATE_CALLED,
                testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
    }

    public void testOverlappingChannels2() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("flags before test", 0, testManager.flags);
        assertTrue("enabling range 1", testManager.enableRange(100, 200, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 200, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 2", testManager.enableRange(150, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 201, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 2", testManager.disableRange(150, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 201, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 200, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 1", testManager.disableRange(100, 200, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 100, 200, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
    }

    public void testMultipleOverlappingChannels() {
        TestIntRangeManager testManager = new TestIntRangeManager();
        assertEquals("flags before test", 0, testManager.flags);
        assertTrue("enabling range 1", testManager.enableRange(67, 9999, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 67, 9999, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 2", testManager.enableRange(150, 250, "client2"));
        assertEquals("flags after test", 0, testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
        testManager.reset();
        assertTrue("enabling range 3", testManager.enableRange(25, 75, "client3"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 66, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 4", testManager.enableRange(12, 500, "client4"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 12, 24, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("enabling range 5", testManager.enableRange(8000, 9998, "client5"));
        assertEquals("flags after test", 0, testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
        testManager.reset();
        assertTrue("enabling range 6", testManager.enableRange(50000, 65535, "client6"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 2, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 12, 9999, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 1", testManager.disableRange(67, 9999, "client1"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 2, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 501, 7999, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        checkConfigInfo(testManager.mConfigList.get(1), 9999, 9999, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 3, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 12, 500, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 8000, 9998, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(2), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 4", testManager.disableRange(12, 500, "client4"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 3, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 12, 24, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        checkConfigInfo(testManager.mConfigList.get(1), 76, 149, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        checkConfigInfo(testManager.mConfigList.get(2), 251, 500, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 4, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 75, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 150, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(2), 8000, 9998, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(3), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 5", testManager.disableRange(8000, 9998, "client5"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 8000, 9998, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 3, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 75, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 150, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(2), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 6", testManager.disableRange(50000, 65535, "client6"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 50000, 65535, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 2, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 75, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        checkConfigInfo(testManager.mConfigList.get(1), 150, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 2", testManager.disableRange(150, 250, "client2"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 150, 250, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 75, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, true);
        testManager.reset();
        assertTrue("disabling range 3", testManager.disableRange(25, 75, "client3"));
        assertEquals("flags after test", ALL_FLAGS_SET, testManager.flags);
        assertEquals("configlist size", 1, testManager.mConfigList.size());
        checkConfigInfo(testManager.mConfigList.get(0), 25, 75, SMS_CB_CODE_SCHEME_MIN,
                SMS_CB_CODE_SCHEME_MAX, false);
        testManager.reset();
        assertTrue("updating ranges", testManager.updateRanges());
        assertEquals("flags after test", FLAG_START_UPDATE_CALLED | FLAG_FINISH_UPDATE_CALLED,
                testManager.flags);
        assertEquals("configlist size", 0, testManager.mConfigList.size());
    }
}
