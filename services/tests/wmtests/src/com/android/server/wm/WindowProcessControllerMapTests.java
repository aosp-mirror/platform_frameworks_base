/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link WindowProcessControllerMap} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowProcessControllerMapTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowProcessControllerMapTests extends WindowTestsBase {

    private static final int FAKE_UID1 = 666;
    private static final int FAKE_UID2 = 667;
    private static final int FAKE_PID1 = 668;
    private static final int FAKE_PID2 = 669;
    private static final int FAKE_PID3 = 670;
    private static final int FAKE_PID4 = 671;

    private WindowProcessControllerMap mProcessMap;
    private WindowProcessController pid1uid1;
    private WindowProcessController pid1uid2;
    private WindowProcessController pid2uid1;
    private WindowProcessController pid3uid1;
    private WindowProcessController pid4uid2;

    @Before
    public void setUp() throws Exception {
        mProcessMap = new WindowProcessControllerMap();
        pid1uid1 = new WindowProcessController(
                mAtm, mAtm.mContext.getApplicationInfo(), "fakepid1fakeuid1", FAKE_UID1,
                UserHandle.getUserId(12345), mock(Object.class), mock(WindowProcessListener.class));
        pid1uid1.setPid(FAKE_PID1);
        pid1uid2 = new WindowProcessController(
                mAtm, mAtm.mContext.getApplicationInfo(), "fakepid1fakeuid2", FAKE_UID2,
                UserHandle.getUserId(12345), mock(Object.class), mock(WindowProcessListener.class));
        pid1uid2.setPid(FAKE_PID1);
        pid2uid1 = new WindowProcessController(
                mAtm, mAtm.mContext.getApplicationInfo(), "fakepid2fakeuid1", FAKE_UID1,
                UserHandle.getUserId(12345), mock(Object.class), mock(WindowProcessListener.class));
        pid2uid1.setPid(FAKE_PID2);
        pid3uid1 = new WindowProcessController(
                mAtm, mAtm.mContext.getApplicationInfo(), "fakepid3fakeuid1", FAKE_UID1,
                UserHandle.getUserId(12345), mock(Object.class), mock(WindowProcessListener.class));
        pid3uid1.setPid(FAKE_PID3);
        pid4uid2 = new WindowProcessController(
                mAtm, mAtm.mContext.getApplicationInfo(), "fakepid4fakeuid2", FAKE_UID2,
                UserHandle.getUserId(12345), mock(Object.class), mock(WindowProcessListener.class));
        pid4uid2.setPid(FAKE_PID4);
    }

    @Test
    public void testAdditionsAndRemovals() {
        // test various additions and removals
        mProcessMap.put(FAKE_PID1, pid1uid1);
        mProcessMap.put(FAKE_PID2, pid2uid1);
        assertEquals(pid1uid1, mProcessMap.getProcess(FAKE_PID1));
        assertEquals(pid2uid1, mProcessMap.getProcess(FAKE_PID2));
        ArraySet<WindowProcessController> uid1processes = mProcessMap.getProcesses(FAKE_UID1);
        assertTrue(uid1processes.contains(pid1uid1));
        assertTrue(uid1processes.contains(pid2uid1));
        assertEquals(uid1processes.size(), 2);

        mProcessMap.remove(FAKE_PID2);
        mProcessMap.put(FAKE_PID3, pid3uid1);
        uid1processes = mProcessMap.getProcesses(FAKE_UID1);
        assertTrue(uid1processes.contains(pid1uid1));
        assertFalse(uid1processes.contains(pid2uid1));
        assertTrue(uid1processes.contains(pid3uid1));
        assertEquals(uid1processes.size(), 2);

        mProcessMap.put(FAKE_PID4, pid4uid2);
        ArraySet<WindowProcessController> uid2processes = mProcessMap.getProcesses(FAKE_UID2);
        assertTrue(uid2processes.contains(pid4uid2));
        assertEquals(uid2processes.size(), 1);

        mProcessMap.remove(FAKE_PID1);
        mProcessMap.remove(FAKE_PID3);
        assertNull(mProcessMap.getProcesses(FAKE_UID1));
        assertEquals(mProcessMap.getProcess(FAKE_PID4), pid4uid2);
    }

    @Test
    public void testReplacement() {
        // test that replacing a process is handled correctly
        mProcessMap.put(FAKE_PID1, pid1uid1);
        ArraySet<WindowProcessController> uid1processes = mProcessMap.getProcesses(FAKE_UID1);
        assertTrue(uid1processes.contains(pid1uid1));
        assertEquals(uid1processes.size(), 1);

        mProcessMap.put(FAKE_PID1, pid1uid2);
        assertNull(mProcessMap.getProcesses(FAKE_UID1));
        ArraySet<WindowProcessController> uid2processes = mProcessMap.getProcesses(FAKE_UID2);
        assertTrue(uid2processes.contains(pid1uid2));
        assertEquals(uid2processes.size(), 1);
        assertEquals(mProcessMap.getProcess(FAKE_PID1), pid1uid2);
    }

    @Test
    public void testRemove_callsDestroy() {
        var proc = spy(pid1uid1);
        mProcessMap.put(FAKE_PID1, proc);

        mProcessMap.remove(FAKE_PID1);

        verify(proc).destroy();
    }
}
