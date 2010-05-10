/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

/**
 * Service used by {@link BinderThreadPriorityTest} to verify
 * the conveyance of thread priorities over Binder.
 */
public class BinderThreadPriorityService extends Service {
    private static final String TAG = "BinderThreadPriorityService";

    private final IBinderThreadPriorityService.Stub mBinder =
            new IBinderThreadPriorityService.Stub() {
        public int getThreadPriority() {
            return Process.getThreadPriority(Process.myTid());
        }

        public String getThreadSchedulerGroup() {
            return BinderThreadPriorityTest.getSchedulerGroup();
        }

        public void callBack(IBinderThreadPriorityService recurse) {
            try {
                recurse.callBack(this);
            } catch (RemoteException e) {
                Log.e(TAG, "Binder callback failed", e);
            }
        }

        public void setPriorityAndCallBack(int priority, IBinderThreadPriorityService recurse) {
            Process.setThreadPriority(priority);
            try {
                recurse.callBack(this);
            } catch (RemoteException e) {
                Log.e(TAG, "Binder callback failed", e);
            }
        }
    };

    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
