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
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.DeadObjectException;
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

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

/** @hide */
public class MbmsStreamingManager {
    private interface ServiceListener {
        void onServiceConnected();
        void onServiceDisconnected();
    }

    private static final String LOG_TAG = "MbmsStreamingManager";
    public static final String MBMS_STREAMING_SERVICE_ACTION =
            "android.telephony.action.EmbmsStreaming";

    private static final boolean DEBUG = true;
    private static final int BIND_TIMEOUT_MS = 3000;

    private IMbmsStreamingService mService;
    private ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                Log.i(LOG_TAG, String.format("Connected to service %s", name));
                synchronized (MbmsStreamingManager.this) {
                    mService = IMbmsStreamingService.Stub.asInterface(service);
                    for (ServiceListener l : mServiceListeners) {
                        l.onServiceConnected();
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(LOG_TAG, String.format("Disconnected from service %s", name));
            synchronized (MbmsStreamingManager.this) {
                mService = null;
                for (ServiceListener l : mServiceListeners) {
                    l.onServiceDisconnected();
                }
            }
        }
    };

    private List<ServiceListener> mServiceListeners = new LinkedList<>();

    private MbmsStreamingManagerCallback mCallbackToApp;
    private final String mAppName;

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;

    /** @hide */
    private MbmsStreamingManager(Context context, MbmsStreamingManagerCallback listener,
                    String streamingAppName, int subscriptionId) {
        mContext = context;
        mAppName = streamingAppName;
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
     * @param streamingAppName The name of the streaming app, as specified by the carrier.
     * @param subscriptionId The subscription ID to use.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback listener, String streamingAppName, int subscriptionId)
            throws MbmsException {
        MbmsStreamingManager manager = new MbmsStreamingManager(context, listener,
                streamingAppName, subscriptionId);
        manager.bindAndInitialize();
        return manager;
    }

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID.
     * See {@link #create(Context, MbmsStreamingManagerCallback, String, int)}.
     */
    public static MbmsStreamingManager create(Context context,
            MbmsStreamingManagerCallback listener, String streamingAppName)
            throws MbmsException {
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        MbmsStreamingManager manager = new MbmsStreamingManager(context, listener,
                streamingAppName, subId);
        manager.bindAndInitialize();
        return manager;
    }

    /**
     * Terminates this instance, ending calls to the registered listener.  Also terminates
     * any streaming services spawned from this instance.
     */
    public synchronized void dispose() {
        if (mService == null) {
            // Ignore and return, assume already disposed.
            return;
        }
        try {
            mService.dispose(mAppName, mSubscriptionId);
        } catch (RemoteException e) {
            // Ignore for now
        }
        mService = null;
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
        if (mService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }
        try {
            int returnCode = mService.getStreamingServices(mAppName, mSubscriptionId, classList);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }
    }

    /**
     * Starts streaming a requested service, reporting status to the indicated listener.
     * Returns an object used to control that stream. The stream may not be ready for consumption
     * immediately upon return from this method -- wait until the streaming state has been
     * reported via {@link android.telephony.mbms.StreamingServiceCallback#streamStateChanged(int)}.
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
        if (mService == null) {
            throw new MbmsException(MbmsException.ERROR_MIDDLEWARE_NOT_BOUND);
        }

        try {
            int returnCode = mService.startStreaming(
                    mAppName, mSubscriptionId, serviceInfo.getServiceId(), listener);
            if (returnCode != MbmsException.SUCCESS) {
                throw new MbmsException(returnCode);
            }
        } catch (RemoteException e) {
            Log.w(LOG_TAG, "Remote process died");
            mService = null;
            throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
        }

        return new StreamingService(
                mAppName, mSubscriptionId, mService, serviceInfo, listener);
    }

    private void bindAndInitialize() throws MbmsException {
        // Kick off the binding, and synchronously wait until binding is complete
        final CountDownLatch latch = new CountDownLatch(1);
        ServiceListener bindListener = new ServiceListener() {
            @Override
            public void onServiceConnected() {
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected() {
            }
        };

        synchronized (this) {
            mServiceListeners.add(bindListener);
        }

        Intent bindIntent = new Intent();
        bindIntent.setComponent(MbmsUtils.toComponentName(
                MbmsUtils.getMiddlewareServiceInfo(mContext, MBMS_STREAMING_SERVICE_ACTION)));

        mContext.bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        MbmsUtils.waitOnLatchWithTimeout(latch, BIND_TIMEOUT_MS);

       // Remove the listener and call the initialization method through the interface.
        synchronized (this) {
            mServiceListeners.remove(bindListener);

            if (mService == null) {
                throw new MbmsException(MbmsException.ERROR_BIND_TIMEOUT_OR_FAILURE);
            }

            try {
                int returnCode = mService.initialize(mCallbackToApp, mAppName, mSubscriptionId);
                if (returnCode != MbmsException.SUCCESS) {
                    throw new MbmsException(returnCode);
                }
            } catch (RemoteException e) {
                mService = null;
                Log.e(LOG_TAG, "Service died before initialization");
                throw new MbmsException(MbmsException.ERROR_SERVICE_LOST);
            }
        }
    }

}
