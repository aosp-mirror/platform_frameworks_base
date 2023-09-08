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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

public class MyService extends Service {
    @Override
    public IBinder onBind(Intent intent) {
        return new IFooProvider.Stub() {
            ReferenceQueue<IFoo> mRefQueue = new ReferenceQueue<>();
            PhantomReference<IFoo> mRef;

            @Override
            public IFoo createFoo() throws RemoteException {
                IFoo binder = new IFoo.Stub() {};
                mRef = new PhantomReference<>(binder, mRefQueue);
                return binder;
            }

            @Override
            public boolean isFooGarbageCollected() throws RemoteException {
                forceGc();
                return mRefQueue.poll() == mRef;
            }

            @Override
            public void killProcess() throws RemoteException {
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        };
    }

    private static void forceGc() {
        Object obj = new Object();
        ReferenceQueue<Object> refQueue = new ReferenceQueue<>();
        PhantomReference<Object> ref = new PhantomReference<>(obj, refQueue);
        obj = null; // make it an orphan
        while (refQueue.poll() != ref) {
            System.gc();
        }
    }
}
