/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app;

import static android.app.ActivityManager.PROCESS_STATE_SERVICE;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.UserHandle;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;

@RunWith(AndroidJUnit4.class)
public class ActivityManagerTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    public void testSimple() throws Exception {
        assertTrue(ActivityManager.isSystemReady());
        assertFalse(ActivityManager.isUserAMonkey());
        assertNotEquals(UserHandle.USER_NULL, ActivityManager.getCurrentUser());
    }

    @Test
    public void testCapabilities() throws Exception {
        // For the moment mostly want to confirm we don't crash
        assertNotNull(ActivityManager.getCapabilitiesSummary(~0));
        ActivityManager.printCapabilitiesFull(new PrintWriter(new ByteArrayOutputStream()), ~0);
        ActivityManager.printCapabilitiesSummary(new PrintWriter(new ByteArrayOutputStream()), ~0);
        ActivityManager.printCapabilitiesSummary(new StringBuilder(), ~0);
    }

    @Test
    public void testProcState() throws Exception {
        // For the moment mostly want to confirm we don't crash
        assertNotNull(ActivityManager.procStateToString(PROCESS_STATE_SERVICE));
        assertNotNull(ActivityManager.processStateAmToProto(PROCESS_STATE_SERVICE));
        assertTrue(ActivityManager.isProcStateBackground(PROCESS_STATE_SERVICE));
        assertFalse(ActivityManager.isProcStateCached(PROCESS_STATE_SERVICE));
        assertFalse(ActivityManager.isForegroundService(PROCESS_STATE_SERVICE));
        assertFalse(ActivityManager.isProcStateConsideredInteraction(PROCESS_STATE_SERVICE));
    }

    @Test
    public void testStartResult() throws Exception {
        // For the moment mostly want to confirm we don't crash
        assertTrue(ActivityManager.isStartResultSuccessful(50));
        assertTrue(ActivityManager.isStartResultFatalError(-50));
    }

    @Test
    public void testRestrictionLevel() throws Exception {
        // For the moment mostly want to confirm we don't crash
        assertNotNull(ActivityManager.restrictionLevelToName(
                ActivityManager.RESTRICTION_LEVEL_HIBERNATION));
    }
}
