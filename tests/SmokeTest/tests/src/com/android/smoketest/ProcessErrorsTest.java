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

package com.android.smoketest;

import com.android.internal.os.RuntimeInit;

import android.app.ActivityManager;
import android.content.Context;
import android.test.AndroidTestCase;
import android.util.Log;

import java.util.Iterator;
import java.util.List;

/**
 * This smoke test is designed to quickly sniff for any error conditions
 * encountered after initial startup.
 */
public class ProcessErrorsTest extends AndroidTestCase {
    
    private final String TAG = "ProcessErrorsTest";
    
    protected ActivityManager mActivityManager;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mActivityManager = (ActivityManager) 
                getContext().getSystemService(Context.ACTIVITY_SERVICE);
    }

    public void testSetUpConditions() throws Exception {
        assertNotNull(mActivityManager);
    }

    public void testNoProcessErrors() throws Exception {
        List<ActivityManager.ProcessErrorStateInfo> errList;        
        errList = mActivityManager.getProcessesInErrorState();
        
        // note: this contains information about each process that is currently in an error
        // condition.  if the list is empty (null) then "we're good".  
        
        // if the list is non-empty, then it's useful to report the contents of the list
        // we'll put a copy in the log, and we'll report it back to the framework via the assert.
        final String reportMsg = reportListContents(errList);
        if (reportMsg != null) {
            Log.w(TAG, reportMsg);
        }
        
        // report a non-empty list back to the test framework
        assertNull(reportMsg, errList);
    }
    
    /**
     * This helper function will dump the actual error reports.
     * 
     * @param errList The error report containing one or more error records.
     * @return Returns a string containing all of the errors.
     */
    private String reportListContents(List<ActivityManager.ProcessErrorStateInfo> errList) {
        if (errList == null) return null;

        StringBuilder builder = new StringBuilder();

        Iterator<ActivityManager.ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ActivityManager.ProcessErrorStateInfo entry = iter.next();

            String condition;
            switch (entry.condition) {
            case ActivityManager.ProcessErrorStateInfo.CRASHED:
                condition = "CRASHED";
                break;
            case ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING:
                condition = "ANR";
                break;
            default:
                condition = "<unknown>";
                break;
            }

            builder.append("Process error ").append(condition).append(" ");
            builder.append(" ").append(entry.shortMsg);
            builder.append(" detected in ").append(entry.processName).append(" ").append(entry.tag);
        }
        return builder.toString();
    }
    
}
