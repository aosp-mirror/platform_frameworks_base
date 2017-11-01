/*
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
package android.os;

import android.app.ActivityManager;
import android.app.VrManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;
import android.provider.Settings;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

/**
 * Tests ActivityManager#setPersistentVrThread and ActivityManager#setVrThread's
 * interaction with persistent VR mode.
 */
public class SetPersistentVrThreadTest extends ActivityInstrumentationTestCase2<TestVrActivity> {
    private TestVrActivity mActivity;
    private ActivityManager mActivityManager;
    private VrManager mVrManager;
    private Context mContext;
    private String mOldVrListener;
    private ComponentName mRequestedComponent;
    private static final int SCHED_OTHER = 0;
    private static final int SCHED_FIFO = 1;
    private static final int SCHED_RESET_ON_FORK = 0x40000000;
    public static final String ENABLED_VR_LISTENERS = "enabled_vr_listeners";
    private static final String TAG = "VrSetPersistentFIFOThreadTest";

    public SetPersistentVrThreadTest() {
        super(TestVrActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mVrManager = (VrManager) mContext.getSystemService(Context.VR_SERVICE);

        mRequestedComponent = new ComponentName(mContext,
                TestVrActivity.TestVrListenerService.class);
        mOldVrListener = Settings.Secure.getString(mContext.getContentResolver(),
                ENABLED_VR_LISTENERS);
        Settings.Secure.putString(mContext.getContentResolver(), ENABLED_VR_LISTENERS,
                mRequestedComponent.flattenToString());
        mActivity = getActivity();
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            setPersistentVrModeEnabled(false);
        } catch (Throwable e) {
            // pass
        }
        Settings.Secure.putString(mContext.getContentResolver(), ENABLED_VR_LISTENERS,
                mOldVrListener);
        super.tearDown();
    }

    private void setPersistentVrModeEnabled(boolean enable) throws Throwable {
        mVrManager.setPersistentVrModeEnabled(enable);
        // Allow the system time to send out callbacks for persistent VR mode.
        Thread.sleep(200);
    }

    @SmallTest
    public void testSetPersistentVrThreadAPISuccess() throws Throwable {
        if (!mActivity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)) {
            return;
        }

        int vr_thread = 0, policy = 0;
        vr_thread = Process.myTid();

        setPersistentVrModeEnabled(true);
        mActivityManager.setPersistentVrThread(vr_thread);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals((SCHED_FIFO | SCHED_RESET_ON_FORK), policy);

        // Check that the thread loses priority when persistent mode is disabled.
        setPersistentVrModeEnabled(false);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals(SCHED_OTHER, policy);

        // Check that disabling VR mode when in persistent mode does not affect the persistent
        // thread.
        mActivity.setVrModeEnabled(true, mRequestedComponent);
        Thread.sleep(200);
        setPersistentVrModeEnabled(true);
        Thread.sleep(200);
        mActivityManager.setPersistentVrThread(vr_thread);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals((SCHED_FIFO | SCHED_RESET_ON_FORK), policy);
        mActivity.setVrModeEnabled(false, mRequestedComponent);
        Thread.sleep(200);
        assertEquals((SCHED_FIFO | SCHED_RESET_ON_FORK), policy);
        setPersistentVrModeEnabled(false);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals(SCHED_OTHER, policy);
    }

    @SmallTest
    public void testSetPersistentVrThreadAPIFailure() throws Throwable {
        if (!mActivity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)) {
            return;
        }
        int vr_thread = 0, policy = 0;
        vr_thread = Process.myTid();
        mActivityManager.setPersistentVrThread(vr_thread);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals(SCHED_OTHER, policy);
    }

    @SmallTest
    public void testSetVrThreadAPIFailsInPersistentMode() throws Throwable {
        if (!mActivity.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VR_MODE_HIGH_PERFORMANCE)) {
            return;
        }
        int vr_thread = 0, policy = 0;
        mActivity.setVrModeEnabled(true, mRequestedComponent);
        vr_thread = Process.myTid();

        setPersistentVrModeEnabled(true);
        mActivityManager.setVrThread(vr_thread);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals(SCHED_OTHER, policy);
        setPersistentVrModeEnabled(false);

        // When not in persistent mode the API works again.
        mActivity.setVrModeEnabled(true, mRequestedComponent);
        mActivityManager.setVrThread(vr_thread);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals((SCHED_FIFO | SCHED_RESET_ON_FORK), policy);

        // The thread loses priority when persistent mode is disabled.
        setPersistentVrModeEnabled(true);
        policy = (int) Process.getThreadScheduler(vr_thread);
        assertEquals(SCHED_OTHER, policy);
        setPersistentVrModeEnabled(false);
    }
}
