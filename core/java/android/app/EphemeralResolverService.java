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

package android.app;

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
    public static final String EXTRA_RESOLVE_INFO = "android.app.extra.RESOLVE_INFO";
    public static final String EXTRA_SEQUENCE = "android.app.extra.SEQUENCE";
    private static final String EXTRA_PREFIX = "android.app.PREFIX";
    private static final String EXTRA_HOSTNAME = "android.app.HOSTNAME";
    private Handler mHandler;

    /**
     * Called to retrieve resolve info for ephemeral applications.
     *
     * @param digestPrefix The hash prefix of the ephemeral's domain.
     * @param prefixMask A mask that was applied to each digest prefix. This should
     *      be used when comparing against the digest prefixes as all bits might
     *      not be set.
     * @deprecated use {@link #onGetEphemeralResolveInfo(int[])} instead
     */
    @Deprecated
    public abstract List<EphemeralResolveInfo> onEphemeralResolveInfoList(
            int digestPrefix[], int prefix);

    /**
     * Called to retrieve resolve info for ephemeral applications.
     *
     * @param digestPrefix The hash prefix of the ephemeral's domain.
     */
    public List<EphemeralResolveInfo> onGetEphemeralResolveInfo(int digestPrefix[]) {
        return onEphemeralResolveInfoList(digestPrefix, 0xFFFFF000);
    }

    /**
     * Called to retrieve intent filters for ephemeral applications.
     *
     * @param hostName The name of the host to get intent filters for.
     */
    public EphemeralResolveInfo onGetEphemeralIntentFilter(String hostName) {
        throw new IllegalStateException("Must define");
    }

    @Override
    public final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new ServiceHandler(base.getMainLooper());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new IEphemeralResolver.Stub() {
            @Override
            public void getEphemeralResolveInfoList(
                    IRemoteCallback callback, int digestPrefix[], int sequence) {
                final Message msg = mHandler.obtainMessage(
                        ServiceHandler.MSG_GET_EPHEMERAL_RESOLVE_INFO, sequence, 0, callback);
                final Bundle data = new Bundle();
                data.putIntArray(EXTRA_PREFIX, digestPrefix);
                msg.setData(data);
                msg.sendToTarget();
            }

            @Override
            public void getEphemeralIntentFilterList(
                    IRemoteCallback callback, String hostName, int sequence) {
                final Message msg = mHandler.obtainMessage(
                        ServiceHandler.MSG_GET_EPHEMERAL_INTENT_FILTER, sequence, 0, callback);
                final Bundle data = new Bundle();
                data.putString(EXTRA_HOSTNAME, hostName);
                msg.setData(data);
                msg.sendToTarget();
            }
        };
    }

    private final class ServiceHandler extends Handler {
        public static final int MSG_GET_EPHEMERAL_RESOLVE_INFO = 1;
        public static final int MSG_GET_EPHEMERAL_INTENT_FILTER = 2;

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
                    final int[] digestPrefix = message.getData().getIntArray(EXTRA_PREFIX);
                    final List<EphemeralResolveInfo> resolveInfo =
                            onGetEphemeralResolveInfo(digestPrefix);
                    final Bundle data = new Bundle();
                    data.putInt(EXTRA_SEQUENCE, message.arg1);
                    data.putParcelableList(EXTRA_RESOLVE_INFO, resolveInfo);
                    try {
                        callback.sendResult(data);
                    } catch (RemoteException e) {
                    }
                } break;

                case MSG_GET_EPHEMERAL_INTENT_FILTER: {
                    final IRemoteCallback callback = (IRemoteCallback) message.obj;
                    final String hostName = message.getData().getString(EXTRA_HOSTNAME);
                    final EphemeralResolveInfo resolveInfo = onGetEphemeralIntentFilter(hostName);
                    final Bundle data = new Bundle();
                    data.putInt(EXTRA_SEQUENCE, message.arg1);
                    data.putParcelable(EXTRA_RESOLVE_INFO, resolveInfo);
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
