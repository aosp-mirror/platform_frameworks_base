/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.telephony;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.MbmsStreamingManagerCallback;
import android.telephony.mbms.MbmsUtils;
import android.telephony.mbms.StreamingService;
import android.telephony.mbms.StreamingServiceCallback;
import android.telephony.mbms.StreamingServiceInfo;
import android.telephony.mbms.vendor.IMbmsStreamingService;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/** @hide */
public class MbmsStreamingManager {
    private static final String LOG_TAG = "MbmsStreamingManager";
    public static final String MBMS_STREAMING_SERVICE_ACTION =
            "android.telephony.action.EmbmsStreaming";

    private AtomicReference<IMbmsStreamingService> mService = new AtomicReference<>(null);
    private MbmsStreamingManagerCallback mCallbackToApp;

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;

    /** @hide */
    private MbmsStreamingManager(Context context, MbmsStreamingManagerCallback listener,
                    int subscriptionId) {
        mContext = context;
        mCallbackToApp = listener;
        mSubscriptionId = subscriptionId;
    }

    /**
     * Create a new MbmsStreamingManager using the given subscription ID.
     *
     * Note that this call will bind a remote service. You may not call this method on your app's
     * main thread. This may throw an {@link MbmsException}, indicating errors that may happen
     * during the initialization or binding process.
     *
     * @param context The {@link Context} to use.
     * @param listener A callback object on which you wish to receive results of asynchronous
     *                 operations.
     * @param subscriptionId The subscription ID to use.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback listener, int subscriptionId)
            throws MbmsException {
        MbmsStreamingManager manager = new MbmsStreamingManager(context, listener, subscriptionId);
        manager.bindAndInitialize();
        return manager;
    }

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID.
     * See {@link #create(Context, MbmsStreamingManagerCallback, int)}.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback listener)
            throws MbmsException {
        return create(context, listener, SubscriptionManager.getDefaultSubscriptionId());
    }

    /**
     * Terminates this instance, ending calls to the registered listener.  Also terminates
     * any streaming services spawned from this instance.
     */
    public void dispose() {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            // Ignore and return, assume already disposed.
            return;
        }
        try {
            streamingService.dispose(mSubscriptionId);
        } catch (RemoteException e) {
            // Ignore for now
        }
        mService.set(null);
    }

    /**
     * An inspection API to retrieve the list of streaming media currently be advertised.
     * The results are returned asynchronously through the previously registered callback.
     * serviceClasses lets the app filter on types of programming and is opaque data between
     * the app and the carrier.
     *
     * Multiple calls replace the list of serviceClasses of interest.
     *
     * This may throw an {@link MbmsException} containing one of the following errors:
     * {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND}
     * {@link MbmsException#ERROR_CONCURRENT_SERVICE_LIMIT_REACHED}
     * {@link MbmsException#ERROR_SERVICE_LOST}
     *
     * Asynchronous error codes via the {@link MbmsStreamingManagerCallback#error(int, String)}
     * callback can include any of the errors except:
     * {@link MbmsException#ERROR_UNABLE_TO_START_SERVICE}
     * {@link MbmsException#ERROR_END_OF_SESSION}
     */
    public void getStreamingServices(List<String> classList) throws MbmsException {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }
        try {
            int returnCode = streamingService.getStreamingServices(mSubscriptionId, classList);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }
    }

    /**
     * Starts streaming a requested service, reporting status to the indicated listener.
     * Returns an object used to control that stream. The stream may not be ready for consumption
     * immediately upon return from this method -- wait until the streaming state has been
     * reported via {@link android.telephony.mbms.StreamingServiceCallback#streamStateUpdated(int)}
     *
     * May throw an {@link MbmsException} containing any of the following error codes:
     * {@link MbmsException#ERROR_MIDDLEWARE_NOT_BOUND}
     * {@link MbmsException#ERROR_CONCURRENT_SERVICE_LIMIT_REACHED}
     * {@link MbmsException#ERROR_SERVICE_LOST}
     *
     * May also throw an {@link IllegalArgumentException} or an {@link IllegalStateException}
     *
     * Asynchronous errors through the listener include any of the errors
     */
    public StreamingService startStreaming(StreamingServiceInfo serviceInfo,
            StreamingServiceCallback listener) throws MbmsException {
        IMbmsStreamingService streamingService = mService.get();
        if (streamingService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            int returnCode = streamingService.startStreaming(
                    mSubscriptionId, serviceInfo.getServiceId(), listener);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService.set(null);
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }

        return new StreamingService(mSubscriptionId, streamingService, serviceInfo, listener);
    }

    private void bindAndInitialize() throws MbmsException {
        MbmsUtils.startBinding(mContext, MBMS_STREAMING_SERVICE_ACTION,
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        IMbmsStreamingService streamingService =
                                IMbmsStreamingService.Stub.asInterface(service);
                        try {
                            streamingService.initialize(mCallbackToApp, mSubscriptionId);
                        } catch (RemoteException e) {
                            Log.e(LOG_TAG, "Service died before initialization");
                            return;
                        }
                        mService.set(streamingService);
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                        mService.set(null);
                    }
                });
    }
}
