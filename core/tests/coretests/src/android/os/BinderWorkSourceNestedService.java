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

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
* Service used by {@link BinderWorkSourceTest}.
*/
public class BinderWorkSourceNestedService extends Service {
    private final IBinderWorkSourceNestedService.Stub mBinder =
            new IBinderWorkSourceNestedService.Stub() {

        public int[] nestedCallWithWorkSourceToSet(int uidToBlame) {
            final int uid =  Binder.getCallingWorkSourceUid();
            if (uidToBlame != ThreadLocalWorkSource.UID_NONE) {
                Binder.setCallingWorkSourceUid(uidToBlame);
            }
            final int nestedUid = callGetIncomingWorkSourceUid();
            return new int[] {uid, nestedUid};
        }

        public int[] nestedCall() {
            final int uid =  Binder.getCallingWorkSourceUid();
            final int nestedUid = callGetIncomingWorkSourceUid();
            return new int[] {uid, nestedUid};
        }

        private int callGetIncomingWorkSourceUid() {
            BlockingQueue<IBinderWorkSourceService> blockingQueue =
                    new LinkedBlockingQueue<>();
            ServiceConnection mConnection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder service) {
                    blockingQueue.add(IBinderWorkSourceService.Stub.asInterface(service));
                }

                public void onServiceDisconnected(ComponentName name) {
                }
            };

            Context context = getApplicationContext();
            context.bindService(
                    new Intent(context, BinderWorkSourceService.class),
                    mConnection, Context.BIND_AUTO_CREATE);

            final IBinderWorkSourceService service;
            try {
                service = blockingQueue.poll(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (service == null) {
                throw new RuntimeException("Gave up waiting for BinderWorkSourceService");
            }

            try {
                return service.getIncomingWorkSourceUid();
            } catch (RemoteException e) {
                throw new RuntimeException(e);
            } finally {
                context.unbindService(mConnection);
            }
        }
    };

    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
