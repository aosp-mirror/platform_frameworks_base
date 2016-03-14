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

package com.android.server.print;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.printservice.recommendation.IRecommendationService;
import android.printservice.recommendation.IRecommendationServiceCallbacks;
import android.printservice.recommendation.RecommendationInfo;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.List;

import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;

/**
 * Connection to a remote print service recommendation service.
 */
class RemotePrintServiceRecommendationService {
    private static final String LOG_TAG = "RemotePrintServiceRecS";

    /** Lock for this object */
    private final Object mLock = new Object();

    /** Context used for the connection */
    private @NonNull final Context mContext;

    /**  The connection to the service (if {@link #mIsBound bound}) */
    @GuardedBy("mLock")
    private @NonNull final Connection mConnection;

    /** If the service is currently bound. */
    @GuardedBy("mLock")
    private boolean mIsBound;

    /** The service once bound */
    @GuardedBy("mLock")
    private IRecommendationService mService;

    /**
     * Callbacks to be called when there are updates to the print service recommendations.
     */
    public interface RemotePrintServiceRecommendationServiceCallbacks {
        /**
         * Called when there is an update list of print service recommendations.
         *
         * @param recommendations The new recommendations.
         */
        void onPrintServiceRecommendationsUpdated(
                @Nullable List<RecommendationInfo> recommendations);
    }

    /**
     * @return The intent that is used to connect to the print service recommendation service.
     */
    private Intent getServiceIntent(@NonNull UserHandle userHandle) throws Exception {
        List<ResolveInfo> installedServices = mContext.getPackageManager()
                .queryIntentServicesAsUser(new Intent(
                        android.printservice.recommendation.RecommendationService.SERVICE_INTERFACE),
                        GET_SERVICES | GET_META_DATA | MATCH_DEBUG_TRIAGED_MISSING,
                        userHandle.getIdentifier());

        if (installedServices.size() != 1) {
            throw new Exception(installedServices.size() + " instead of exactly one service found");
        }

        ResolveInfo installedService = installedServices.get(0);

        ComponentName serviceName = new ComponentName(
                installedService.serviceInfo.packageName,
                installedService.serviceInfo.name);

        ApplicationInfo appInfo = mContext.getPackageManager()
                .getApplicationInfo(installedService.serviceInfo.packageName, 0);

        if (appInfo == null) {
            throw new Exception("Cannot read appInfo for service");
        }

        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
            throw new Exception("Service is not part of the system");
        }

        if (!android.Manifest.permission.BIND_PRINT_RECOMMENDATION_SERVICE.equals(
                installedService.serviceInfo.permission)) {
            throw new Exception("Service " + serviceName.flattenToShortString()
                    + " does not require permission "
                    + android.Manifest.permission.BIND_PRINT_RECOMMENDATION_SERVICE);
        }

        Intent serviceIntent = new Intent();
        serviceIntent.setComponent(serviceName);

        return serviceIntent;
    }

    /**
     * Open a new connection to a {@link IRecommendationService remote print service
     * recommendation service}.
     *
     * @param context    The context establishing the connection
     * @param userHandle The user the connection is for
     * @param callbacks  The callbacks to call by the service
     */
    RemotePrintServiceRecommendationService(@NonNull Context context,
            @NonNull UserHandle userHandle,
            @NonNull RemotePrintServiceRecommendationServiceCallbacks callbacks) {
        mContext = context;
        mConnection = new Connection(callbacks);

        try {
            Intent serviceIntent = getServiceIntent(userHandle);

            synchronized (mLock) {
                mIsBound = mContext.bindServiceAsUser(serviceIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, userHandle);

                if (!mIsBound) {
                    throw new Exception("Failed to bind to service " + serviceIntent);
                }
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "Could not connect to print service recommendation service", e);
        }
    }

    /**
     * Terminate the connection to the {@link IRecommendationService remote print
     * service recommendation service}.
     */
    void close() {
        synchronized (mLock) {
            if (mService != null) {
                try {
                    mService.registerCallbacks(null);
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Could not unregister callbacks", e);
                }

                mService = null;
            }

            if (mIsBound) {
                mContext.unbindService(mConnection);
                mIsBound = false;
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (mIsBound || mService != null) {
            Log.w(LOG_TAG, "Service still connected on finalize()");
            close();
        }

        super.finalize();
    }

    /**
     * Connection to the service.
     */
    private class Connection implements ServiceConnection {
        private final RemotePrintServiceRecommendationServiceCallbacks mCallbacks;

        public Connection(@NonNull RemotePrintServiceRecommendationServiceCallbacks callbacks) {
            mCallbacks = callbacks;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mService = (IRecommendationService)IRecommendationService.Stub.asInterface(service);

                try {
                    mService.registerCallbacks(new IRecommendationServiceCallbacks.Stub() {
                        @Override
                        public void onRecommendationsUpdated(
                                List<RecommendationInfo> recommendations) {
                            synchronized (mLock) {
                                if (mIsBound && mService != null) {
                                    if (recommendations != null) {
                                        Preconditions.checkCollectionElementsNotNull(
                                                recommendations, "recommendation");
                                    }

                                    mCallbacks.onPrintServiceRecommendationsUpdated(
                                            recommendations);
                                }
                            }
                        }
                    });
                } catch (RemoteException e) {
                    Log.e(LOG_TAG, "Could not register callbacks", e);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(LOG_TAG, "Unexpected termination of connection");

            synchronized (mLock) {
                mService = null;
            }
        }
    }
}
