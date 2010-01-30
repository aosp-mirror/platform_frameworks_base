/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.app.activity;

import android.app.AlarmManager;
import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import java.util.TimeZone;

public class SetTimeZonePermissionsTest extends AndroidTestCase {

    private String[] mZones;
    private String mCurrentZone;
    private AlarmManager mAlarm;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mZones = TimeZone.getAvailableIDs();
        mCurrentZone = TimeZone.getDefault().getID();
        mAlarm = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * Verify that non-system processes cannot set the time zone.
     */
    @LargeTest
    public void testSetTimeZonePermissions() {
        /**
         * Attempt to set several predefined time zones, verifying that the system
         * system default time zone has not actually changed from its prior state
         * after each attempt.
         */
        int max = (mZones.length > 10) ? mZones.length : 10;
        assertTrue("No system-defined time zones - test invalid", max > 0);

        for (int i = 0; i < max; i++) {
            String tz = mZones[i];
            try {
                mAlarm.setTimeZone(tz);
            } catch (SecurityException se) {
                // Expected failure; no need to handle specially since we're
                // about to assert that the test invariant holds: no change
                // to the system time zone.
            }

            String newZone = TimeZone.getDefault().getID();
            assertEquals("AlarmManager.setTimeZone() succeeded despite lack of permission",
                    mCurrentZone,
                    newZone);
        }
    }
}
