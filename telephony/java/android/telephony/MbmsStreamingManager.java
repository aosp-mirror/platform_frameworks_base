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
import android.telephony.mbms.IMbmsStreamingManagerCallback;
import android.telephony.mbms.IStreamingServiceCallback;
import android.telephony.mbms.MbmsException;
import android.telephony.mbms.StreamingService;
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
                    mServiceListeners.forEach(ServiceListener::onServiceConnected);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.i(LOG_TAG, String.format("Disconnected from service %s", name));
            synchronized (MbmsStreamingManager.this) {
                mService = null;
                mServiceListeners.forEach(ServiceListener::onServiceDisconnected);
            }
        }
    };
    private List<ServiceListener> mServiceListeners = new LinkedList<>();

    private IMbmsStreamingManagerCallback mCallbackToApp;
    private final String mAppName;

    private final Context mContext;
    private int mSubscriptionId = INVALID_SUBSCRIPTION_ID;

    /** @hide */
    private MbmsStreamingManager(Context context, IMbmsStreamingManagerCallback listener,
                    String streamingAppName, int subscriptionId) {
        mContext = context;
        mAppName = streamingAppName;
        mCallbackToApp = listener;
        mSubscriptionId = subscriptionId;
    }

    /**
     * Create a new MbmsStreamingManager using the given subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an {@link MbmsException}, indicating errors that may happen during
     * the initialization or binding process.
     *
     * @param context
     * @param listener
     * @param streamingAppName
     * @param subscriptionId
     * @return
     */
    public static MbmsStreamingManager create(Context context,
            IMbmsStreamingManagerCallback listener, String streamingAppName, int subscriptionId)
            throws MbmsException {
        MbmsStreamingManager manager = new MbmsStreamingManager(context, listener,
                streamingAppName, subscriptionId);
        manager.bindAndInitialize();
        return manager;
    }

    /**
     * Create a new MbmsStreamingManager using the system default data subscription ID.
     *
     * Note that this call will bind a remote service and that may take a bit.  This
     * may throw an IllegalArgumentException or RemoteException.
     */
    public static MbmsStreamingManager create(Context context,
            IMbmsStreamingManagerCallback listener, String streamingAppName)
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
     * May throw an IllegalArgumentException or RemoteException.
     *
     * Synchronous responses include
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_MSDC_UNABLE_TO_)START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     */
    public int getStreamingServices(List<String> classList) {
        return 0;
    }

    /**
     * Starts streaming a requested service, reporting status to the indicated listener.
     * Returns an object used to control that stream.
     *
     * May throw an IllegalArgumentException or RemoteException.
     *
     * Asynchronous errors through the listener include any of the errors
     */
    public StreamingService startStreaming(StreamingServiceInfo serviceInfo,
            IStreamingServiceCallback listener) {
        return null;
    }

    /**
     * Lists all the services currently being streamed to the device by this application
     * on this given subId.  Results are returned asynchronously through the previously
     * registered callback.
     *
     * May throw a RemoteException.
     *
     * The return value is a success/error-code with the following possible values:
     * <li>SUCCESS</li>
     * <li>ERROR_MSDC_CONCURRENT_SERVICE_LIMIT_REACHED</li>
     *
     * Asynchronous errors through the listener include any of the errors except
     * <li>ERROR_UNABLED_TO_START_SERVICE</li>
     * <li>ERROR_MSDC_INVALID_SERVICE_ID</li>
     * <li>ERROR_MSDC_END_OF_SESSION</li>
     *
     */
    public int getActiveStreamingServices() {
        return 0;
    }

    private void bindAndInitialize() throws MbmsException {
        // Query for the proper service
        PackageManager packageManager = mContext.getPackageManager();
        Intent queryIntent = new Intent();
        queryIntent.setAction(MBMS_STREAMING_SERVICE_ACTION);
        List<ResolveInfo> streamingServices = packageManager.queryIntentServices(queryIntent,
                PackageManager.MATCH_SYSTEM_ONLY);

        if (streamingServices == null || streamingServices.size() == 0) {
            throw new MbmsException(
                    MbmsException.ERROR_NO_SERVICE_INSTALLED);
        }
        if (streamingServices.size() > 1) {
            throw new MbmsException(
                    MbmsException.ERROR_MULTIPLE_SERVICES_INSTALLED);
        }

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
        bindIntent.setComponent(streamingServices.get(0).getComponentInfo().getComponentName());

        mContext.bindService(bindIntent, mServiceConnection, Context.BIND_AUTO_CREATE);

        waitOnLatchWithTimeout(latch, BIND_TIMEOUT_MS);

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
                throw new MbmsException(MbmsException.ERROR_INITIALIZATION_REMOTE_EXCEPTION);
            }
        }
    }

    private static void waitOnLatchWithTimeout(CountDownLatch l, long timeoutMs) {
        long endTime = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < endTime) {
            try {
                l.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // keep waiting
            }
            if (l.getCount() <= 0) {
                return;
            }
        }
    }
}
