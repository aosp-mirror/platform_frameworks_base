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

package android.net.sip;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.util.Log;

// TODO: throw away this class after moving SIP classes to framework
// This class helps to get IBinder instance of a service in a blocking call.
// The method cannot be called in app's main thread as the ServiceConnection
// callback will.
class BinderHelper<T extends IInterface> {
    private Context mContext;
    private IBinder mBinder;
    private Class<T> mClass;

    BinderHelper(Context context, Class<T> klass) {
        mContext = context;
        mClass = klass;
    }

    void startService() {
        mContext.startService(new Intent(mClass.getName()));
    }

    void stopService() {
        mContext.stopService(new Intent(mClass.getName()));
    }

    IBinder getBinder() {
        // cannot call this method in app's main thread
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) {
            throw new RuntimeException(
                    "This method cannot be called in app's main thread");
        }

        final ConditionVariable cv = new ConditionVariable();
        cv.close();
        ServiceConnection c = new ServiceConnection() {
            public synchronized void onServiceConnected(
                    ComponentName className, IBinder binder) {
                Log.v("BinderHelper", "service connected!");
                mBinder = binder;
                cv.open();
                mContext.unbindService(this);
            }

            public void onServiceDisconnected(ComponentName className) {
                cv.open();
                mContext.unbindService(this);
            }
        };
        if (mContext.bindService(new Intent(mClass.getName()), c, 0)) {
            cv.block(4500);
        }
        return mBinder;
    }
}
