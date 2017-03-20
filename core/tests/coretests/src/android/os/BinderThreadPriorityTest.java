/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.os;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import java.io.File;
import java.io.IOException;

/**
 * Test whether Binder calls inherit thread priorities correctly.
 */
public class BinderThreadPriorityTest extends AndroidTestCase {
    private static final String TAG = "BinderThreadPriorityTest";
    private IBinderThreadPriorityService mService;
    private int mSavedPriority;

    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (BinderThreadPriorityTest.this) {
                mService = IBinderThreadPriorityService.Stub.asInterface(service);
                BinderThreadPriorityTest.this.notifyAll();
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            mService = null;
        }
    };

    private static class ServiceStub extends IBinderThreadPriorityService.Stub {
        public int getThreadPriority() { fail(); return -999; }
        public String getThreadSchedulerGroup() { fail(); return null; }
        public void setPriorityAndCallBack(int p, IBinderThreadPriorityService cb) { fail(); }
        public void callBack(IBinderThreadPriorityService cb) { fail(); }
        private static void fail() { throw new RuntimeException("unimplemented"); }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        getContext().bindService(
                new Intent(getContext(), BinderThreadPriorityService.class),
                mConnection, Context.BIND_AUTO_CREATE);

        synchronized (this) {
            if (mService == null) {
                try {
                    wait(30000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                assertNotNull("Gave up waiting for BinderThreadPriorityService", mService);
            }
        }

        mSavedPriority = Process.getThreadPriority(Process.myTid());
        Process.setThreadPriority(mSavedPriority);  // To realign priority & cgroup, if needed
        assertEquals(expectedSchedulerGroup(mSavedPriority), getSchedulerGroup());
        Log.i(TAG, "Saved priority: " + mSavedPriority);
    }

    @Override
    protected void tearDown() throws Exception {
        // HACK -- see bug 2665914 -- setThreadPriority() doesn't always set the
        // scheduler group reliably unless we start out with background priority.
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        Process.setThreadPriority(mSavedPriority);
        assertEquals(mSavedPriority, Process.getThreadPriority(Process.myTid()));
        assertEquals(expectedSchedulerGroup(mSavedPriority), getSchedulerGroup());

        getContext().unbindService(mConnection);
        super.tearDown();
    }

    public static String getSchedulerGroup() {
        String fn = "/proc/" + Process.myPid() + "/task/" + Process.myTid() + "/cgroup";
        try {
            String cgroup = FileUtils.readTextFile(new File(fn), 1024, null);
            for (String line : cgroup.split("\n")) {
                String fields[] = line.trim().split(":");
                    if (fields.length == 3 && fields[1].equals("cpu")) return fields[2];
            }
        } catch (IOException e) {
            Log.e(TAG, "Can't read: " + fn, e);
        }
        return null;  // Unknown
    }

    public static String expectedSchedulerGroup(int prio) {
        return "/";
    }

    public void testPassPriorityToService() throws Exception {
        for (int prio = 19; prio >= -20; prio--) {
            Process.setThreadPriority(prio);

            // Local
            assertEquals(prio, Process.getThreadPriority(Process.myTid()));
            assertEquals(expectedSchedulerGroup(prio), getSchedulerGroup());

            // Remote
            assertEquals(prio, mService.getThreadPriority());
            assertEquals(expectedSchedulerGroup(prio), mService.getThreadSchedulerGroup());
        }
    }

    public void testCallBackFromServiceWithPriority() throws Exception {
        for (int prio = -20; prio <= 19; prio++) {
            final int expected = prio;
            mService.setPriorityAndCallBack(prio, new ServiceStub() {
                public void callBack(IBinderThreadPriorityService cb) {
                    assertEquals(expected, Process.getThreadPriority(Process.myTid()));
                    assertEquals(expectedSchedulerGroup(expected), getSchedulerGroup());
                }
            });

            assertEquals(mSavedPriority, Process.getThreadPriority(Process.myTid()));

            // BROKEN -- see bug 2665954 -- scheduler group doesn't get reset
            // properly after a back-call with a different priority.
            // assertEquals(expectedSchedulerGroup(mSavedPriority), getSchedulerGroup());
        }
    }
}
