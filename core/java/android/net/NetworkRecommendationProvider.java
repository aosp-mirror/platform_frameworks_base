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
 * limitations under the License
 */

package android.net;

import android.Manifest.permission;
import android.annotation.SystemApi;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.concurrent.Executor;

/**
 * The base class for implementing a network recommendation provider.
 * <p>
 * A network recommendation provider is any application which:
 * <ul>
 * <li>Is granted the {@link permission#SCORE_NETWORKS} permission.
 * <li>Is granted the {@link permission#ACCESS_COARSE_LOCATION} permission.
 * <li>Includes a Service for the {@link NetworkScoreManager#ACTION_RECOMMEND_NETWORKS} intent
 *     which is protected by the {@link permission#BIND_NETWORK_RECOMMENDATION_SERVICE} permission.
 * </ul>
 * <p>
 * Implementations are required to implement the abstract methods in this class and return the
 * result of {@link #getBinder()} from the <code>onBind()</code> method in their Service.
 * <p>
 * The default network recommendation provider is controlled via the
 * <code>config_defaultNetworkRecommendationProviderPackage</code> config key.
 * @hide
 */
@SystemApi
public abstract class NetworkRecommendationProvider {
    private static final String TAG = "NetworkRecProvider";
    private static final boolean VERBOSE = Build.IS_DEBUGGABLE && Log.isLoggable(TAG, Log.VERBOSE);
    private final IBinder mService;

    /**
     * Constructs a new instance.
     * @param context the current context instance. Cannot be {@code null}.
     * @param executor used to execute the incoming requests. Cannot be {@code null}.
     */
    public NetworkRecommendationProvider(Context context, Executor executor) {
        Preconditions.checkNotNull(context);
        Preconditions.checkNotNull(executor);
        mService = new ServiceWrapper(context, executor);
    }

    /**
     * Invoked when network scores have been requested.
     * <p>
     * Use {@link NetworkScoreManager#updateScores(ScoredNetwork[])} to respond to score requests.
     *
     * @param networks a non-empty array of {@link NetworkKey}s to score.
     */
    public abstract void onRequestScores(NetworkKey[] networks);

    /**
     * Services that can handle {@link NetworkScoreManager#ACTION_RECOMMEND_NETWORKS} should
     * return this Binder from their <code>onBind()</code> method.
     */
    public final IBinder getBinder() {
        return mService;
    }

    /**
     * A wrapper around INetworkRecommendationProvider that dispatches to the provided Handler.
     */
    private final class ServiceWrapper extends INetworkRecommendationProvider.Stub {
        private final Context mContext;
        private final Executor mExecutor;
        private final Handler mHandler;

        ServiceWrapper(Context context, Executor executor) {
            mContext = context;
            mExecutor = executor;
            mHandler = null;
        }

        @Override
        public void requestScores(final NetworkKey[] networks) throws RemoteException {
            enforceCallingPermission();
            if (networks != null && networks.length > 0) {
                execute(new Runnable() {
                    @Override
                    public void run() {
                        onRequestScores(networks);
                    }
                });
            }
        }

        private void execute(Runnable command) {
            if (mExecutor != null) {
                mExecutor.execute(command);
            } else {
                mHandler.post(command);
            }
        }

        private void enforceCallingPermission() {
            if (mContext != null) {
                mContext.enforceCallingOrSelfPermission(permission.REQUEST_NETWORK_SCORES,
                        "Permission denied.");
            }
        }
    }
}
