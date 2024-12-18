/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.Arrays;

@IgnoreUnderRavenwood(blockedBy = Process.class)
public class ProcessTest {
    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    private static final int BAD_PID = 0;

    @Test
    public void testProcessGetUidFromName() throws Exception {
        assertEquals(android.os.Process.SYSTEM_UID, Process.getUidForName("system"));
        assertEquals(Process.BLUETOOTH_UID, Process.getUidForName("bluetooth"));
        assertEquals(Process.FIRST_APPLICATION_UID, Process.getUidForName("u0_a0"));
        assertEquals(UserHandle.getUid(1, Process.SYSTEM_UID), Process.getUidForName("u1_system"));
        assertEquals(UserHandle.getUid(2, Process.FIRST_APP_ZYGOTE_ISOLATED_UID),
                Process.getUidForName("u2_i0"));
        assertEquals(UserHandle.getUid(2, Process.FIRST_ISOLATED_UID),
                Process.getUidForName("u2_i9000"));
        assertEquals(UserHandle.getUid(3, Process.FIRST_APPLICATION_UID + 100),
                Process.getUidForName("u3_a100"));
    }

    @Test
    public void testProcessGetUidFromNameFailure() throws Exception {
        // Failure cases
        assertEquals(-1, Process.getUidForName("u2a_foo"));
        assertEquals(-1, Process.getUidForName("u1_abcdef"));
        assertEquals(-1, Process.getUidForName("u23"));
        assertEquals(-1, Process.getUidForName("u2_i34a"));
        assertEquals(-1, Process.getUidForName("akjhwiuefhiuhsf"));
        assertEquals(-1, Process.getUidForName("u5_radio5"));
        assertEquals(-1, Process.getUidForName("u2jhsajhfkjhsafkhskafhkashfkjashfkjhaskjfdhakj3"));
    }

    /**
     * Tests getUidForPid() by ensuring that it returns the correct value when the process queried
     * doesn't exist.
     */
    @Test
    public void testGetUidForPidInvalidPid() {
        assertEquals(-1, Process.getUidForPid(BAD_PID));
    }

    /**
     * Tests getParentPid() by ensuring that it returns the correct value when the process queried
     * doesn't exist.
     */
    @Test
    public void testGetParentPidInvalidPid() {
        assertEquals(-1, Process.getParentPid(BAD_PID));
    }

    /**
     * Tests getThreadGroupLeader() by ensuring that it returns the correct value when the
     * thread queried doesn't exist.
     */
    @Test
    public void testGetThreadGroupLeaderInvalidTid() {
        // This function takes a TID instead of a PID but BAD_PID should also be a bad TID.
        assertEquals(-1, Process.getThreadGroupLeader(BAD_PID));
    }

    @Test
    public void testGetAdvertisedMem() {
        assertTrue(Process.getAdvertisedMem() > 0);
        assertTrue(Process.getTotalMemory() <= Process.getAdvertisedMem());
    }

    @Test
    public void testGetSchedAffinity() {
        long[] tidMasks = Process.getSchedAffinity(Process.myTid());
        long[] pidMasks = Process.getSchedAffinity(Process.myPid());
        checkAffinityMasks(tidMasks);
        checkAffinityMasks(pidMasks);
    }

    static void checkAffinityMasks(long[] masks) {
        assertNotNull(masks);
        assertTrue(masks.length > 0);
        assertTrue("At least one of the affinity mask should be non-zero but got "
                + Arrays.toString(masks), Arrays.stream(masks).anyMatch(value -> value > 0));
    }
}
