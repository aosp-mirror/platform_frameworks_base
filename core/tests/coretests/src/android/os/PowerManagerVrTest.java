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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.service.dreams.DreamService;
import android.support.test.filters.MediumTest;
import android.support.test.filters.SmallTest;
import android.test.ActivityInstrumentationTestCase2;

/**
 * Tests dream aspects of PowerManager.
 */
@MediumTest
public class PowerManagerVrTest extends ActivityInstrumentationTestCase2<TestVrActivity> {
    private PowerManager mPm;
    private IDreamManager mDm;
    private String mOldVrListener;

    public PowerManagerVrTest() {
        super(TestVrActivity.class);
    }

    /**
     * Setup any common data for the upcoming tests.
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        mPm = (PowerManager) getInstrumentation().getTargetContext().getSystemService(
                Context.POWER_SERVICE);
        mDm = IDreamManager.Stub.asInterface(
                ServiceManager.getService(DreamService.DREAM_SERVICE));

        mOldVrListener = setTestVrListener(new ComponentName(
                context, TestVrActivity.TestVrListenerService.class).flattenToString());
    }

    @Override
    public void tearDown() throws Exception {
        if (mDm != null) {
            mDm.awaken();  // Don't leave the device in the dream state.
        }

        setTestVrListener(mOldVrListener);
    }

    /**
     * Confirm that the setup is good.
     *
     * @throws Exception
     */
    @SmallTest
    public void testPreconditions() throws Exception {
        assertNotNull(mPm);
        assertNotNull(mDm);
    }

    /**
     * Confirm that the system prevents napping while in VR.
     * Dreaming is controlled by PowerManager, but we use dreamManager to access those features
     * in order to not require DEVICE_POWER permissions which other tests expect not to have.
     *
     * @throws Exception
     */
    @SmallTest
    public void testNap() throws Exception {
        // For dream to work, we need to wake up the system
        wakeUpDevice();

        mDm.dream();
        waitForDreamState(true);
        assertTrue(mDm.isDreaming());
        mDm.awaken();

        // awaken() is not immediate so we have to wait for dreaming to stop
        // before continuing with the test.
        waitForDreamState(false);

        // set VR Mode to true by starting our VR Activity, then retest the dream.
        TestVrActivity activity = getActivity();
        assertTrue(activity.waitForActivityStart());

        try {
            mDm.dream();
            waitForDreamState(true);  // wait for dream to turn true with a timeout
            assertFalse(mDm.isDreaming()); // ensure dream is still false after waiting.
            mDm.awaken();
        } finally {
            activity.finish();
        }
    }

    /**
     * Waits synchronously for the system to be set to the specified dream state.
     */
    private void waitForDreamState(boolean isDreaming) throws Exception {
        final int MAX_ATTEMPTS = 10;
        final int WAIT_TIME_PER_ATTEMPT_MILLIS = 100;
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
          if (mDm.isDreaming() == isDreaming) {
            break;
          }
          Thread.sleep(WAIT_TIME_PER_ATTEMPT_MILLIS);
        }
    }

    private void wakeUpDevice() {
        PowerManager.WakeLock wl = mPm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                | PowerManager.ON_AFTER_RELEASE, "FULL_WAKE_LOCK");
        wl.acquire();
        wl.release();
    }

    /**
     * Sets a new value for the enabled VrListenerService and returns the previous value.
     */
    private String setTestVrListener(String newValue) {
        final String ENABLED_VR_LISTENERS = "enabled_vr_listeners";
        Context context = getInstrumentation().getTargetContext();
        ContentResolver cr = context.getContentResolver();
        String oldVrListeners = Settings.Secure.getString(cr, ENABLED_VR_LISTENERS);
        Settings.Secure.putString(cr, ENABLED_VR_LISTENERS, newValue);
        return oldVrListeners;
    }
}
