/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.framework.permission.tests;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.res.Configuration;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * TODO: Remove this. This is only a placeholder, need to implement this.
 */
public class ActivityManagerPermissionTests extends TestCase {
    IActivityManager mAm;
    
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mAm = ActivityManagerNative.getDefault();
    }

    @SmallTest
    public void testREORDER_TASKS() {
        try {
            mAm.moveTaskToFront(0, 0, null);
            fail("IActivityManager.moveTaskToFront did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }

        try {
            mAm.moveTaskBackwards(-1);
            fail("IActivityManager.moveTaskToFront did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testCHANGE_CONFIGURATION() {
        try {
            mAm.updateConfiguration(new Configuration());
            fail("IActivityManager.updateConfiguration did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSET_DEBUG_APP() {
        try {
            mAm.setDebugApp(null, false, false);
            fail("IActivityManager.setDebugApp did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSET_PROCESS_LIMIT() {
        try {
            mAm.setProcessLimit(10);
            fail("IActivityManager.setProcessLimit did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testALWAYS_FINISH() {
        try {
            mAm.setAlwaysFinish(false);
            fail("IActivityManager.setAlwaysFinish did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSIGNAL_PERSISTENT_PROCESSES() {
        try {
            mAm.signalPersistentProcesses(-1);
            fail("IActivityManager.signalPersistentProcesses did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testFORCE_BACK() {
        try {
            mAm.unhandledBack();
            fail("IActivityManager.unhandledBack did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSET_ACTIVITY_WATCHER() {
        try {
            mAm.setActivityController(null, false);
            fail("IActivityManager.setActivityController did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSHUTDOWN() {
        try {
            mAm.shutdown(0);
            fail("IActivityManager.shutdown did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }

    @SmallTest
    public void testSTOP_APP_SWITCHES() {
        try {
            mAm.stopAppSwitches();
            fail("IActivityManager.stopAppSwitches did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
        
        try {
            mAm.resumeAppSwitches();
            fail("IActivityManager.resumeAppSwitches did not throw SecurityException as"
                    + " expected");
        } catch (SecurityException e) {
            // expected
        } catch (RemoteException e) {
            fail("Unexpected remote exception");
        }
    }
}
