/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.test.binder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ServiceTestRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class BinderTest {
    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();

    @Test
    public void testDeathRecipientLeaksOrNot()
            throws RemoteException, TimeoutException, InterruptedException {
        Intent intent = new Intent(ApplicationProvider.getApplicationContext(), MyService.class);
        IFooProvider provider = IFooProvider.Stub.asInterface(serviceRule.bindService(intent));
        FooHolder holder = new FooHolder(provider.createFoo());

        // ref will get enqueued right after holder is finalized for gc.
        ReferenceQueue<FooHolder> refQueue = new ReferenceQueue<>();
        PhantomReference<FooHolder> ref = new PhantomReference<>(holder, refQueue);

        DeathRecorder deathRecorder = new DeathRecorder();
        holder.registerDeathRecorder(deathRecorder);

        if (getSdkVersion() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            /////////////////////////////////////////////
            // New behavior
            //
            // Reference chain at this moment:
            // holder --(java strong ref)--> FooHolder
            // FooHolder.mProxy --(java strong ref)--> IFoo.Proxy
            // IFoo.Proxy.mRemote --(java strong ref)--> BinderProxy
            // BinderProxy --(binder ref)--> Foo.Stub
            // In other words, the variable "holder" is the root of the reference chain.

            // By setting the variable to null, we make FooHolder, IFoo.Proxy, BinderProxy, and even
            // Foo.Stub unreachable.
            holder = null;

            // Ensure that the objects are garbage collected
            forceGc();
            assertEquals(ref, refQueue.poll());
            assertTrue(provider.isFooGarbageCollected());

            // The binder has died, but we don't get notified since the death recipient is GC'ed.
            provider.killProcess();
            Thread.sleep(1000); // give some time for the service process to die and reaped
            assertFalse(deathRecorder.deathRecorded);
        } else {
            /////////////////////////////////////////////
            // Legacy behavior
            //
            // Reference chain at this moment:
            // JavaDeathRecipient --(JNI strong ref)--> FooHolder
            // holder --(java strong ref)--> FooHolder
            // FooHolder.mProxy --(java strong ref)--> IFoo.Proxy
            // IFoo.Proxy.mRemote --(java strong ref)--> BinderProxy
            // BinderProxy --(binder ref)--> Foo.Stub
            // So, BOTH JavaDeathRecipient and holder are roots of the reference chain.

            // Even if we set holder to null, it doesn't make other objects unreachable; they are
            // still reachable via the JNI strong ref.
            holder = null;

            // Check that objects are not garbage collected
            forceGc();
            assertNotEquals(ref, refQueue.poll());
            assertFalse(provider.isFooGarbageCollected());

            // The legacy behavior is getting notified even when there's no reference
            provider.killProcess();
            Thread.sleep(1000); // give some time for the service process to die and reaped
            assertTrue(deathRecorder.deathRecorded);
        }
    }

    static class FooHolder implements IBinder.DeathRecipient {
        private IFoo mProxy;
        private DeathRecorder mDeathRecorder;

        FooHolder(IFoo proxy) throws RemoteException {
            proxy.asBinder().linkToDeath(this, 0);

            // A strong reference from DeathRecipient(this) to the binder proxy is created here
            mProxy = proxy;
        }

        public void registerDeathRecorder(DeathRecorder dr) {
            mDeathRecorder = dr;
        }

        @Override
        public void binderDied() {
            if (mDeathRecorder != null) {
                mDeathRecorder.deathRecorded = true;
            }
        }
    }

    static class DeathRecorder {
        public boolean deathRecorded = false;
    }

    // Try calling System.gc() until an orphaned object is confirmed to be finalized
    private static void forceGc() {
        Object obj = new Object();
        ReferenceQueue<Object> refQueue = new ReferenceQueue<>();
        PhantomReference<Object> ref = new PhantomReference<>(obj, refQueue);
        obj = null; // make it an orphan
        while (refQueue.poll() != ref) {
            System.gc();
        }
    }

    private static int getSdkVersion() {
        return ApplicationProvider.getApplicationContext().getApplicationInfo().targetSdkVersion;
    }
}
