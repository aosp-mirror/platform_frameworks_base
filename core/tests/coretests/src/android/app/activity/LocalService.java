/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

public class LocalService extends Service {
    private final IBinder mBinder = new Binder() {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply,
                int flags) throws RemoteException {
            if (code == ServiceTest.SET_REPORTER_CODE) {
                data.enforceInterface(ServiceTest.SERVICE_LOCAL);
                mReportObject = data.readStrongBinder();
                return true;
            } else {
                return super.onTransact(code, data, reply, flags);
            }
        }
        
    };

    private IBinder mReportObject;
    private int mStartCount = 1;

    public LocalService() {
    }

    @Override
    public void onStart(Intent intent, int startId) {
        //Log.i("LocalService", "onStart: " + intent);
        if (intent.getExtras() != null) {
            mReportObject = intent.getExtras().getIBinder(ServiceTest.REPORT_OBJ_NAME);
            if (mReportObject != null) {
                try {
                    Parcel data = Parcel.obtain();
                    data.writeInterfaceToken(ServiceTest.SERVICE_LOCAL);
                    data.writeInt(mStartCount);
                    mStartCount++;
                    mReportObject.transact(
                            ServiceTest.STARTED_CODE, data, null, 0);
                    data.recycle();
                } catch (RemoteException e) {
                }
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.i("LocalService", "onDestroy: mReportObject=" + mReportObject);
        if (mReportObject != null) {
            try {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(ServiceTest.SERVICE_LOCAL);
                mReportObject.transact(
                        ServiceTest.DESTROYED_CODE, data, null, 0);
                data.recycle();
            } catch (RemoteException e) {
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.i("LocalService", "onBind: " + intent);
        return mBinder;
    }
    
    @Override
    public boolean onUnbind(Intent intent) {
        Log.i("LocalService", "onUnbind: " + intent);
        if (mReportObject != null) {
            try {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(ServiceTest.SERVICE_LOCAL);
                mReportObject.transact(
                        ServiceTest.UNBIND_CODE, data, null, 0);
                data.recycle();
            } catch (RemoteException e) {
            }
        }
        return true;
    }
    
    @Override
    public void onRebind(Intent intent) {
        Log.i("LocalService", "onUnbind: " + intent);
        if (mReportObject != null) {
            try {
                Parcel data = Parcel.obtain();
                data.writeInterfaceToken(ServiceTest.SERVICE_LOCAL);
                mReportObject.transact(
                        ServiceTest.REBIND_CODE, data, null, 0);
                data.recycle();
            } catch (RemoteException e) {
            }
        }
    }
}
