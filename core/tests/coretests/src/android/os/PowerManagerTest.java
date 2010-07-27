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

package android.os;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class PowerManagerTest extends AndroidTestCase {
    
    private PowerManager mPm;
    
    /**
     * Setup any common data for the upcoming tests.
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        mPm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
    }
    
    /**
     * Confirm that the setup is good.
     * 
     * @throws Exception
     */
    @SmallTest
    public void testPreconditions() throws Exception {
        assertNotNull(mPm);
    }

    /**
     * Confirm that we can create functional wakelocks.
     * 
     * @throws Exception
     */
    @SmallTest
    public void testNewWakeLock() throws Exception {
        PowerManager.WakeLock wl = mPm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "FULL_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "SCREEN_BRIGHT_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "SCREEN_DIM_WAKE_LOCK");
        doTestWakeLock(wl);

        wl = mPm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PARTIAL_WAKE_LOCK");
        doTestWakeLock(wl);
        
        doTestSetBacklightBrightness();

        // TODO: Some sort of functional test (maybe not in the unit test here?) 
        // that confirms that things are really happening e.g. screen power, keyboard power.
}
    
    /**
     * Confirm that we can't create dysfunctional wakelocks.
     * 
     * @throws Exception
     */
    @SmallTest
    public void testBadNewWakeLock() throws Exception {
        
        final int badFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK 
                            | PowerManager.SCREEN_DIM_WAKE_LOCK;
        // wrap in try because we want the error here
        try {
            PowerManager.WakeLock wl = mPm.newWakeLock(badFlags, "foo");
        } catch (IllegalArgumentException e) {
            return;
        }
        fail("Bad WakeLock flag was not caught.");
    }
    
    /**
     * Apply a few tests to a wakelock to make sure it's healthy.
     * 
     * @param wl The wakelock to be tested.
     */
    private void doTestWakeLock(PowerManager.WakeLock wl) {
        // First try simple acquire/release
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());
        
        // Try ref-counted acquire/release
        wl.setReferenceCounted(true);
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());
        
        // Try non-ref-counted
        wl.setReferenceCounted(false);
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.acquire();
        assertTrue(wl.isHeld());
        wl.release();
        assertFalse(wl.isHeld());
        
        // TODO: Threaded test (needs handler) to make sure timed wakelocks work too
    }
    
 
    /**
     * Test that calling {@link android.os.IHardwareService#setBacklights(int)} requires
     * permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#DEVICE_POWER}
     */
    private void doTestSetBacklightBrightness() {
        try {
            mPm.setBacklightBrightness(0);
            fail("setBacklights did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

}
