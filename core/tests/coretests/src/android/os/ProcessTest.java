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

import androidx.test.filters.MediumTest;

import junit.framework.TestCase;

public class ProcessTest extends TestCase {

    @MediumTest
    public void testProcessGetUidFromName() throws Exception {
        assertEquals(android.os.Process.SYSTEM_UID, Process.getUidForName("system"));
        assertEquals(Process.BLUETOOTH_UID, Process.getUidForName("bluetooth"));
        assertEquals(Process.FIRST_APPLICATION_UID, Process.getUidForName("u0_a0"));
        assertEquals(UserHandle.getUid(1, Process.SYSTEM_UID), Process.getUidForName("u1_system"));
        assertEquals(UserHandle.getUid(2, Process.FIRST_ISOLATED_UID),
                Process.getUidForName("u2_i0"));
        assertEquals(UserHandle.getUid(3, Process.FIRST_APPLICATION_UID + 100),
                Process.getUidForName("u3_a100"));
    }

    @MediumTest
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

}
