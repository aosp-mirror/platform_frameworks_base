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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.testng.Assert.assertThrows;

import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.filters.SmallTest;

import com.android.internal.os.BinderInternal;


import org.junit.Rule;
import org.junit.Test;

@IgnoreUnderRavenwood(blockedBy = WorkSource.class)
public class BinderTest {
    private static final int UID = 100;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Test
    @SmallTest
    public void testSetWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        assertEquals(UID, Binder.getCallingWorkSourceUid());
    }

    @Test
    @SmallTest
    public void testClearWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        Binder.clearCallingWorkSource();
        assertEquals(-1, Binder.getCallingWorkSourceUid());
    }

    @Test
    @SmallTest
    public void testRestoreWorkSource() throws Exception {
        Binder.setCallingWorkSourceUid(UID);
        long token = Binder.clearCallingWorkSource();
        Binder.restoreCallingWorkSource(token);
        assertEquals(UID, Binder.getCallingWorkSourceUid());
    }

    @Test
    @SmallTest
    public void testGetCallingUidOrThrow_throws() throws Exception {
        assertThrows(IllegalStateException.class, () -> Binder.getCallingUidOrThrow());
    }

    @Test
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

    @SmallTest
    @Test(expected = java.lang.SecurityException.class)
    public void testServiceManagerNativeSecurityException() throws RemoteException {
        // Find the service manager
        IServiceManager sServiceManager = ServiceManagerNative
                .asInterface(Binder.allowBlocking(BinderInternal.getContextObject()));

        Binder binder = new Binder();
        sServiceManager.addService("ValidName",  binder,
                anyBoolean(), anyInt());
    }

    @SmallTest
    @Test(expected = java.lang.NullPointerException.class)
    public void testServiceManagerNativeNullptrException() throws RemoteException {
        // Find the service manager
        IServiceManager sServiceManager = ServiceManagerNative
                .asInterface(Binder.allowBlocking(BinderInternal.getContextObject()));

        sServiceManager.addService("ValidName",  null,
                anyBoolean(), anyInt());
    }
}
