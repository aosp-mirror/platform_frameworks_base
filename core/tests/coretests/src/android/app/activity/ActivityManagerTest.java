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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.content.res.Configuration;
import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;
import androidx.test.filters.Suppress;

import java.util.Iterator;
import java.util.List;

public class ActivityManagerTest extends AndroidTestCase {

    protected Context mContext;
    protected ActivityManager mActivityManager;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getContext();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    // TODO should write a test for getRecentTasks()
    // TODO should write a test for getRunningTasks()
    // TODO should write a test for getMemoryInfo()
    
    // TODO: Find a way to re-enable this.  It fails if any other app has failed during startup.
    // This is probably an OK assumption given the desired system status when we run unit tests,
    // but it's not necessarily the right assumption for a unit test.
    @Suppress
    public void disabledTestErrorTasksEmpty() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        errList = mActivityManager.getProcessesInErrorState();
        
        // test: confirm list is empty
        assertNull(errList);
    }
    
    // TODO: Force an activity into an error state - then see if we can catch it here?
    @SmallTest
    public void testErrorTasksWithError() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        // TODO force another process into an error condition.  How?
        
        // test: confirm error list length is at least 1 under varying query lengths
//      checkErrorListMax(1,-1);

        errList = mActivityManager.getProcessesInErrorState();

        // test: the list itself is healthy
        checkErrorListSanity(errList);

        // test: confirm our application shows up in the list
    }
    
    // TODO: Force an activity into an ANR state - then see if we can catch it here?
    @SmallTest
    public void testErrorTasksWithANR() throws Exception {
        
        List<ActivityManager.ProcessErrorStateInfo> errList;
        
        // TODO: force an application into an ANR state
        
        errList = mActivityManager.getProcessesInErrorState();

        // test: the list itself is healthy
        checkErrorListSanity(errList);

        // test: confirm our ANR'ing application shows up in the list
    }
    
    @SmallTest
    public void testGetDeviceConfigurationInfo() throws Exception {
        ConfigurationInfo config = mActivityManager.getDeviceConfigurationInfo();
        assertNotNull(config);
        // Validate values against configuration retrieved from resources
        Configuration vconfig = mContext.getResources().getConfiguration();
        assertNotNull(vconfig);
        assertEquals(config.reqKeyboardType, vconfig.keyboard);
        assertEquals(config.reqTouchScreen, vconfig.touchscreen);
        assertEquals(config.reqNavigation, vconfig.navigation);
        if (vconfig.navigation == Configuration.NAVIGATION_NONAV) {
            assertNotNull(config.reqInputFeatures & ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV);
        }
        if (vconfig.keyboard != Configuration.KEYBOARD_UNDEFINED) {
            assertNotNull(config.reqInputFeatures & ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD);
        }    
    }
    
    // If any entries in appear in the list, sanity check them against all running applications
    private void checkErrorListSanity(List<ActivityManager.ProcessErrorStateInfo> errList) {
        if (errList == null) return;
        
        Iterator<ActivityManager.ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ActivityManager.ProcessErrorStateInfo info = iter.next();
            assertNotNull(info);
            // sanity checks
            assertTrue((info.condition == ActivityManager.ProcessErrorStateInfo.CRASHED) ||
                       (info.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING));
            // TODO look at each of these and consider a stronger test
            // TODO can we cross-check at the process name via some other API?
            // TODO is there a better test for strings, e.g. "assertIsLegalString")
            assertNotNull(info.processName);
            // reasonableness test for info.pid ?
            assertNotNull(info.longMsg);
            assertNotNull(info.shortMsg);
            // is there any reasonable test for the crashData?  Probably not. 
        }
    }
}

