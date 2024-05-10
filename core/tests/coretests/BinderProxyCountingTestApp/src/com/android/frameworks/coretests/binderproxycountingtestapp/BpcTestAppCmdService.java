/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.frameworks.coretests.binderproxycountingtestapp;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;

import com.android.frameworks.coretests.aidl.IBinderProxyCountingService;
import com.android.frameworks.coretests.aidl.IBpcTestAppCmdService;
import com.android.frameworks.coretests.aidl.ITestRemoteCallback;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class BpcTestAppCmdService extends Service {
    private static final String TAG = BpcTestAppCmdService.class.getSimpleName();

    private static final String TEST_SERVICE_PKG =
            "com.android.frameworks.coretests.binderproxycountingtestservice";
    private static final String TEST_SERVICE_CLASS =
            TEST_SERVICE_PKG + ".BinderProxyCountingService";
    private static final int BIND_SERVICE_TIMEOUT_SEC = 5;

    private static ServiceConnection mServiceConnection;
    private static IBinderProxyCountingService mBpcService;

    private IBpcTestAppCmdService.Stub mBinder = new IBpcTestAppCmdService.Stub() {

        private ArrayList<ContentObserver> mCoList = new ArrayList();
        private ArrayList<ITestRemoteCallback> mTrcList = new ArrayList();
        private Handler mHandler = new Handler();

        @Override
        public void createSystemBinders(int count) {
            int i = 0;
            while (i++ < count) {
                final ContentObserver co = new ContentObserver(mHandler) {};
                synchronized (mCoList) {
                    mCoList.add(co);
                }
                getContentResolver().registerContentObserver(
                        Settings.System.CONTENT_URI, false, co);
            }
        }

        @Override
        public void releaseSystemBinders(int count) {
            int i = 0;
            while (i++ < count) {
                ContentObserver co;
                synchronized (mCoList) {
                    co = mCoList.remove(0);
                }
                getContentResolver().unregisterContentObserver(co);
            }
        }

        @Override
        public void createTestBinders(int count) {
            int i = 0;
            while (i++ < count) {
                ITestRemoteCallback cb = new ITestRemoteCallback.Stub() {};
                synchronized (mTrcList) {
                    mTrcList.add(cb);
                }
                try {
                    mBpcService.registerCallback(cb);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException caught! " + e);
                }
            }
        }

        @Override
        public void releaseTestBinders(int count) {
            int i = 0;
            while (i++ < count) {

                ITestRemoteCallback cb;
                synchronized (mTrcList) {
                    cb = mTrcList.remove(0);
                }
                try {
                    mBpcService.unregisterCallback(cb);
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException caught! " + e);
                }
            }
        }

        @Override
        public void releaseAllBinders() {
            synchronized (mCoList) {
                while (mCoList.size() > 0) {
                    getContentResolver().unregisterContentObserver(mCoList.remove(0));
                }
            }
            synchronized (mTrcList) {
                while (mTrcList.size() > 0) {
                    try {
                        mBpcService.unregisterCallback(mTrcList.remove(0));
                    } catch (RemoteException e) {
                        Log.e(TAG, "RemoteException caught! " + e);
                    }
                }
            }
        }

        @Override
        public String bindToTestService() {
            try {
                final CountDownLatch bindLatch = new CountDownLatch(1);
                mServiceConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Log.i(TAG, "Service connected");
                        mBpcService = IBinderProxyCountingService.Stub.asInterface(service);
                        bindLatch.countDown();
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        Log.i(TAG, "Service disconnected");
                    }
                };
                final Intent intent = new Intent()
                        .setComponent(new ComponentName(TEST_SERVICE_PKG, TEST_SERVICE_CLASS));
                bindService(intent, mServiceConnection,
                        Context.BIND_AUTO_CREATE
                                | Context.BIND_ALLOW_OOM_MANAGEMENT
                                | Context.BIND_NOT_FOREGROUND);
                if (!bindLatch.await(BIND_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    throw new RuntimeException("Failed to bind to " + TEST_SERVICE_CLASS);
                }
            } catch (Exception e) {
                unbindFromTestService();
                Log.e(TAG, e.toString());
                return e.toString();
            }
            return null;
        }

        @Override
        public void unbindFromTestService() {
            if (mBpcService != null) {
                unbindService(mServiceConnection);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
