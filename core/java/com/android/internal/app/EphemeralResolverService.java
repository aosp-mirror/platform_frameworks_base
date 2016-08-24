/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.app;

import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.EphemeralResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import java.util.List;

/**
 * Base class for implementing the resolver service.
 * @hide
 */
@SystemApi
public abstract class EphemeralResolverService extends Service {
    public static final String EXTRA_RESOLVE_INFO = "com.android.internal.app.RESOLVE_INFO";
    public static final String EXTRA_SEQUENCE = "com.android.internal.app.SEQUENCE";
    private Handler mHandler;

    /**
     * Called to retrieve resolve info for ephemeral applications.
     *
     * @param digestPrefix The hash prefix of the ephemeral's domain.
     */
    protected abstract List<EphemeralResolveInfo> getEphemeralResolveInfoList(int digestPrefix);

    @Override
    protected final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new ServiceHandler(base.getMainLooper());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new IEphemeralResolver.Stub() {
            @Override
            public void getEphemeralResolveInfoList(
                    IRemoteCallback callback, int digestPrefix, int sequence) {
                mHandler.obtainMessage(ServiceHandler.MSG_GET_EPHEMERAL_RESOLVE_INFO,
                        digestPrefix, sequence, callback)
                    .sendToTarget();
            }
        };
    }

    private final class ServiceHandler extends Handler {
        public static final int MSG_GET_EPHEMERAL_RESOLVE_INFO = 1;

        public ServiceHandler(Looper looper) {
            super(looper, null /*callback*/, true /*async*/);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            final int action = message.what;
            switch (action) {
                case MSG_GET_EPHEMERAL_RESOLVE_INFO: {
                    final IRemoteCallback callback = (IRemoteCallback) message.obj;
                    final List<EphemeralResolveInfo> resolveInfo =
                            getEphemeralResolveInfoList(message.arg1);
                    final Bundle data = new Bundle();
                    data.putInt(EXTRA_SEQUENCE, message.arg2);
                    data.putParcelableList(EXTRA_RESOLVE_INFO, resolveInfo);
                    try {
                        callback.sendResult(data);
                    } catch (RemoteException e) {
                    }
                } break;

                default: {
                    throw new IllegalArgumentException("Unknown message: " + action);
                }
            }
        }
    }
}
