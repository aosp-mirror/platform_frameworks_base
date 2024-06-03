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

package com.android.frameworks.coretests.binderproxycountingtestservice;

import android.app.Service;
import android.content.Intent;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import com.android.frameworks.coretests.aidl.IBpcCallbackObserver;
import com.android.frameworks.coretests.aidl.IBpcTestServiceCmdService;
import com.android.internal.os.BinderInternal;

public class BpcTestServiceCmdService extends Service {
    private static final String TAG = BpcTestServiceCmdService.class.getSimpleName();

    //ServiceThread mHandlerThread;
    Handler mHandler;
    HandlerThread mHandlerThread;

    private IBpcTestServiceCmdService.Stub mBinder = new IBpcTestServiceCmdService.Stub() {
        IBpcCallbackObserver mCallbackObserver;

        @Override
        public void forceGc() {
            int gcCount = Integer.parseInt(Debug.getRuntimeStat("art.gc.gc-count"));
            int i = 20;
            while (gcCount == Integer.parseInt(Debug.getRuntimeStat("art.gc.gc-count")) && i > 0) {
                System.gc();
                System.runFinalization();
                i--;
            }
        }

        @Override
        public int getBinderProxyCount(int uid) {
            return BinderInternal.nGetBinderProxyCount(uid);
        }

        @Override
        public void setBinderProxyWatermarks(int high, int low, int warning) {
            BinderInternal.nSetBinderProxyCountWatermarks(high, low, warning);
        }

        @Override
        public void enableBinderProxyLimit(boolean enable) {
            BinderInternal.nSetBinderProxyCountEnabled(enable);
        }

        @Override
        public void setBinderProxyCountCallback(IBpcCallbackObserver observer) {
            if (observer != null) {
                BinderInternal.setBinderProxyCountCallback(
                        new BinderInternal.BinderProxyCountEventListener() {
                            @Override
                            public void onLimitReached(int uid) {
                                try {
                                    synchronized (observer) {
                                        observer.onLimitReached(uid);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, e.toString());
                                }
                            }

                            @Override
                            public void onWarningThresholdReached(int uid) {
                                try {
                                    synchronized (observer) {
                                        observer.onWarningThresholdReached(uid);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, e.toString());
                                }
                            }
                        }, mHandler);
            } else {
                BinderInternal.clearBinderProxyCountCallback();
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate()
    {
        mHandlerThread = new HandlerThread("BinderProxyCountingServiceThread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
    }
}
