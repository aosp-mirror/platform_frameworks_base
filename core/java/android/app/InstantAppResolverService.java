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

package android.app;

import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.InstantAppResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.SomeArgs;

import java.util.Arrays;
import java.util.List;

/**
 * Base class for implementing the resolver service.
 * @hide
 */
@SystemApi
public abstract class InstantAppResolverService extends Service {
    private static final boolean DEBUG_EPHEMERAL = Build.IS_DEBUGGABLE;
    private static final String TAG = "PackageManager";

    /** @hide */
    public static final String EXTRA_RESOLVE_INFO = "android.app.extra.RESOLVE_INFO";
    /** @hide */
    public static final String EXTRA_SEQUENCE = "android.app.extra.SEQUENCE";
    Handler mHandler;

    /**
     * Called to retrieve resolve info for instant applications.
     *
     * @param digestPrefix The hash prefix of the instant app's domain.
     */
    public void onGetInstantAppResolveInfo(
            int digestPrefix[], String token, InstantAppResolutionCallback callback) {
        throw new IllegalStateException("Must define");
    }

    /**
     * Called to retrieve intent filters for instant applications.
     *
     * @param digestPrefix The hash prefix of the instant app's domain.
     */
    public void onGetInstantAppIntentFilter(
            int digestPrefix[], String token, InstantAppResolutionCallback callback) {
        throw new IllegalStateException("Must define");
    }

    /**
     * Returns a {@link Looper} to perform service operations on.
     */
    Looper getLooper() {
        return getBaseContext().getMainLooper();
    }

    @Override
    public final void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mHandler = new ServiceHandler(getLooper());
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new IInstantAppResolver.Stub() {
            @Override
            public void getInstantAppResolveInfoList(
                    int digestPrefix[], String token, int sequence, IRemoteCallback callback) {
                if (DEBUG_EPHEMERAL) {
                    Slog.v(TAG, "[" + token + "] Phase1 called; posting");
                }
                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = callback;
                args.arg2 = digestPrefix;
                args.arg3 = token;
                mHandler.obtainMessage(
                                ServiceHandler.MSG_GET_INSTANT_APP_RESOLVE_INFO, sequence, 0, args)
                        .sendToTarget();
            }

            @Override
            public void getInstantAppIntentFilterList(
                    int digestPrefix[], String token, String hostName, IRemoteCallback callback) {
                if (DEBUG_EPHEMERAL) {
                    Slog.v(TAG, "[" + token + "] Phase2 called; posting");
                }
                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = callback;
                args.arg2 = digestPrefix;
                args.arg3 = token;
                args.arg4 = hostName;
                mHandler.obtainMessage(
                        ServiceHandler.MSG_GET_INSTANT_APP_INTENT_FILTER, callback).sendToTarget();
            }
        };
    }

    /**
     * Callback to post results from instant app resolution.
     */
    public static final class InstantAppResolutionCallback {
        private final IRemoteCallback mCallback;
        private final int mSequence;
        InstantAppResolutionCallback(int sequence, IRemoteCallback callback) {
            mCallback = callback;
            mSequence = sequence;
        }

        public void onInstantAppResolveInfo(List<InstantAppResolveInfo> resolveInfo) {
            final Bundle data = new Bundle();
            data.putParcelableList(EXTRA_RESOLVE_INFO, resolveInfo);
            data.putInt(EXTRA_SEQUENCE, mSequence);
            try {
                mCallback.sendResult(data);
            } catch (RemoteException e) {
            }
        }
    }

    @Deprecated
    void _onGetInstantAppResolveInfo(int[] digestPrefix, String token,
            InstantAppResolutionCallback callback) {
        if (DEBUG_EPHEMERAL) {
            Slog.d(TAG, "[" + token + "] Phase1 request;"
                    + " prefix: " + Arrays.toString(digestPrefix));
        }
        onGetInstantAppResolveInfo(digestPrefix, token, callback);
    }
    @Deprecated
    void _onGetInstantAppIntentFilter(int digestPrefix[], String token, String hostName,
            InstantAppResolutionCallback callback) {
        if (DEBUG_EPHEMERAL) {
            Slog.d(TAG, "[" + token + "] Phase2 request;"
                    + " prefix: " + Arrays.toString(digestPrefix));
        }
        onGetInstantAppIntentFilter(digestPrefix, token, callback);
    }

    private final class ServiceHandler extends Handler {
        public static final int MSG_GET_INSTANT_APP_RESOLVE_INFO = 1;
        public static final int MSG_GET_INSTANT_APP_INTENT_FILTER = 2;

        public ServiceHandler(Looper looper) {
            super(looper, null /*callback*/, true /*async*/);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            final int action = message.what;
            switch (action) {
                case MSG_GET_INSTANT_APP_RESOLVE_INFO: {
                    final SomeArgs args = (SomeArgs) message.obj;
                    final IRemoteCallback callback = (IRemoteCallback) args.arg1;
                    final int[] digestPrefix = (int[]) args.arg2;
                    final String token = (String) args.arg3;
                    final int sequence = message.arg1;
                    _onGetInstantAppResolveInfo(
                            digestPrefix, token,
                            new InstantAppResolutionCallback(sequence, callback));
                } break;

                case MSG_GET_INSTANT_APP_INTENT_FILTER: {
                    final SomeArgs args = (SomeArgs) message.obj;
                    final IRemoteCallback callback = (IRemoteCallback) args.arg1;
                    final int[] digestPrefix = (int[]) args.arg2;
                    final String token = (String) args.arg3;
                    final String hostName = (String) args.arg4;
                    _onGetInstantAppIntentFilter(
                            digestPrefix, token, hostName,
                            new InstantAppResolutionCallback(-1 /*sequence*/, callback));
                } break;

                default: {
                    throw new IllegalArgumentException("Unknown message: " + action);
                }
            }
        }
    }
}
