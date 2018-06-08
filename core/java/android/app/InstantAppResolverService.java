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
import java.util.Collections;
import java.util.List;

/**
 * Base class for implementing the resolver service.
 * @hide
 */
@SystemApi
public abstract class InstantAppResolverService extends Service {
    private static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;
    private static final String TAG = "PackageManager";

    /** @hide */
    public static final String EXTRA_RESOLVE_INFO = "android.app.extra.RESOLVE_INFO";
    /** @hide */
    public static final String EXTRA_SEQUENCE = "android.app.extra.SEQUENCE";
    Handler mHandler;

    /**
     * Called to retrieve resolve info for instant applications immediately.
     *
     * @param digestPrefix The hash prefix of the instant app's domain.
     * @deprecated should implement {@link #onGetInstantAppResolveInfo(Intent, int[], String,
     *             InstantAppResolutionCallback)}
     */
    @Deprecated
    public void onGetInstantAppResolveInfo(
            int digestPrefix[], String token, InstantAppResolutionCallback callback) {
        throw new IllegalStateException("Must define onGetInstantAppResolveInfo");
    }

    /**
     * Called to retrieve intent filters for instant applications from potentially expensive
     * sources.
     *
     * @param digestPrefix The hash prefix of the instant app's domain.
     * @deprecated should implement {@link #onGetInstantAppIntentFilter(Intent, int[], String,
     *             InstantAppResolutionCallback)}
     */
    @Deprecated
    public void onGetInstantAppIntentFilter(
            int digestPrefix[], String token, InstantAppResolutionCallback callback) {
        throw new IllegalStateException("Must define onGetInstantAppIntentFilter");
    }

    /**
     * Called to retrieve resolve info for instant applications immediately. The response will be
     * ignored if not provided within a reasonable time. {@link InstantAppResolveInfo}s provided
     * in response to this method may be partial to request a second phase of resolution which will
     * result in a subsequent call to
     * {@link #onGetInstantAppIntentFilter(Intent, int[], String, InstantAppResolutionCallback)}
     *
     *
     * @param sanitizedIntent The sanitized {@link Intent} used for resolution. A sanitized Intent
     *                        is an intent with potential PII removed from the original intent.
     *                        Fields removed include extras and the host + path of the data, if
     *                        defined.
     * @param hostDigestPrefix The hash prefix of the instant app's domain.
     * @param token A unique identifier that will be provided in calls to
     *              {@link #onGetInstantAppIntentFilter(Intent, int[], String,
     *              InstantAppResolutionCallback)}
     *              and provided to the installer via {@link Intent#EXTRA_INSTANT_APP_TOKEN} to
     *              tie a single launch together.
     * @param callback The {@link InstantAppResolutionCallback} to provide results to.
     *
     * @see InstantAppResolveInfo
     */
    public void onGetInstantAppResolveInfo(Intent sanitizedIntent, int[] hostDigestPrefix,
            String token, InstantAppResolutionCallback callback) {
        // if not overridden, forward to old methods and filter out non-web intents
        if (sanitizedIntent.isWebIntent()) {
            onGetInstantAppResolveInfo(hostDigestPrefix, token, callback);
        } else {
            callback.onInstantAppResolveInfo(Collections.emptyList());
        }
    }

    /**
     * Called to retrieve intent filters for potentially matching instant applications. Unlike
     * {@link #onGetInstantAppResolveInfo(Intent, int[], String, InstantAppResolutionCallback)},
     * the response may take as long as necessary to respond. All {@link InstantAppResolveInfo}s
     * provided in response to this method must be completely populated.
     *
     * @param sanitizedIntent The sanitized {@link Intent} used for resolution.
     * @param hostDigestPrefix The hash prefix of the instant app's domain or null if no host is
     *                         defined.
     * @param token A unique identifier that was provided in
     *              {@link #onGetInstantAppResolveInfo(Intent, int[], String,
     *              InstantAppResolutionCallback)}
     *              and provided to the currently visible installer via
     *              {@link Intent#EXTRA_INSTANT_APP_TOKEN}.
     * @param callback The {@link InstantAppResolutionCallback} to provide results to
     */
    public void onGetInstantAppIntentFilter(Intent sanitizedIntent, int[] hostDigestPrefix,
            String token, InstantAppResolutionCallback callback) {
        Log.e(TAG, "New onGetInstantAppIntentFilter is not overridden");
        // if not overridden, forward to old methods and filter out non-web intents
        if (sanitizedIntent.isWebIntent()) {
            onGetInstantAppIntentFilter(hostDigestPrefix, token, callback);
        } else {
            callback.onInstantAppResolveInfo(Collections.emptyList());
        }
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
            public void getInstantAppResolveInfoList(Intent sanitizedIntent, int[] digestPrefix,
                    String token, int sequence, IRemoteCallback callback) {
                if (DEBUG_INSTANT) {
                    Slog.v(TAG, "[" + token + "] Phase1 called; posting");
                }
                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = callback;
                args.arg2 = digestPrefix;
                args.arg3 = token;
                args.arg4 = sanitizedIntent;
                mHandler.obtainMessage(ServiceHandler.MSG_GET_INSTANT_APP_RESOLVE_INFO,
                        sequence, 0, args).sendToTarget();
            }

            @Override
            public void getInstantAppIntentFilterList(Intent sanitizedIntent,
                    int[] digestPrefix, String token, IRemoteCallback callback) {
                if (DEBUG_INSTANT) {
                    Slog.v(TAG, "[" + token + "] Phase2 called; posting");
                }
                final SomeArgs args = SomeArgs.obtain();
                args.arg1 = callback;
                args.arg2 = digestPrefix;
                args.arg3 = token;
                args.arg4 = sanitizedIntent;
                mHandler.obtainMessage(ServiceHandler.MSG_GET_INSTANT_APP_INTENT_FILTER,
                        callback).sendToTarget();
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
                    final Intent intent = (Intent) args.arg4;
                    final int sequence = message.arg1;
                    if (DEBUG_INSTANT) {
                        Slog.d(TAG, "[" + token + "] Phase1 request;"
                                + " prefix: " + Arrays.toString(digestPrefix));
                    }
                    onGetInstantAppResolveInfo(intent, digestPrefix, token,
                            new InstantAppResolutionCallback(sequence, callback));
                } break;

                case MSG_GET_INSTANT_APP_INTENT_FILTER: {
                    final SomeArgs args = (SomeArgs) message.obj;
                    final IRemoteCallback callback = (IRemoteCallback) args.arg1;
                    final int[] digestPrefix = (int[]) args.arg2;
                    final String token = (String) args.arg3;
                    final Intent intent = (Intent) args.arg4;
                    if (DEBUG_INSTANT) {
                        Slog.d(TAG, "[" + token + "] Phase2 request;"
                                + " prefix: " + Arrays.toString(digestPrefix));
                    }
                    onGetInstantAppIntentFilter(intent, digestPrefix, token,
                            new InstantAppResolutionCallback(-1 /*sequence*/, callback));
                } break;

                default: {
                    throw new IllegalArgumentException("Unknown message: " + action);
                }
            }
        }
    }
}
