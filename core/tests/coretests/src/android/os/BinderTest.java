/*
 * Copyright (C) 2018 The Android Open Source Project
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

import androidx.test.filters.SmallTest;

import junit.framework.TestCase;

public class BinderTest extends TestCase {
    private static final int UID = 100;

    @SmallTest
    public void testSetWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        assertEquals(UID, Binder.getCallingWorkSourceUid());
    }

    @SmallTest
    public void testClearWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        Binder.clearCallingWorkSource();
        assertEquals(-1, Binder.getCallingWorkSourceUid());
    }

    @SmallTest
    public void testRestoreWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        long token = Binder.clearCallingWorkSource();
        Binder.restoreCallingWorkSource(token);
        assertEquals(UID, Binder.getCallingWorkSourceUid());
    }

    @SmallTest
    public void testGetCallingUidOrThrow() throws Exception {
        try {
            Binder.getCallingUidOrThrow();
            throw new AssertionError("IllegalStateException expected");
        } catch (IllegalStateException expected) {
        }
    }

    @SmallTest
    public void testGetExtension() throws Exception {
        Binder binder = new Binder();
        assertNull(binder.getExtension());

        IBinder extension = new Binder();
        binder.setExtension(extension);
        assertNotNull(binder.getExtension());
        assertSame(binder.getExtension(), extension);

        binder.setExtension(null);
        assertNull(binder.getExtension());
    }
}
