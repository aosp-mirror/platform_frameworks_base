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

package android.service.resolver;

import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.service.resolver.ResolverTarget;
import android.util.Log;

import java.util.List;
import java.util.Map;

/**
 * A service to rank apps according to usage stats of apps, when the system is resolving targets for
 * an Intent.
 *
 * <p>To extend this class, you must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_RESOLVER_RANKER_SERVICE} permission, and include an
 * intent filter with the {@link #SERVICE_INTERFACE} action. For example:</p>
 * <pre>
 *     &lt;service android:name=".MyResolverRankerService"
 *             android:exported="true"
 *             android:priority="100"
 *             android:permission="android.permission.BIND_RESOLVER_RANKER_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action android:name="android.service.resolver.ResolverRankerService" /&gt;
 *         &lt;/intent-filter&gt;
 *     &lt;/service&gt;
 * </pre>
 * @hide
 */
@SystemApi
public abstract class ResolverRankerService extends Service {

    private static final String TAG = "ResolverRankerService";

    private static final boolean DEBUG = false;

    /**
     * The Intent action that a service must respond to. Add it to the intent filter of the service
     * in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.resolver.ResolverRankerService";

    /**
     * The permission that a service must hold. If the service does not hold the permission, the
     * system will skip that service.
     */
    public static final String HOLD_PERMISSION = "android.permission.PROVIDE_RESOLVER_RANKER_SERVICE";

    /**
     * The permission that a service must require to ensure that only Android system can bind to it.
     * If this permission is not enforced in the AndroidManifest of the service, the system will
     * skip that service.
     */
    public static final String BIND_PERMISSION = "android.permission.BIND_RESOLVER_RANKER_SERVICE";

    private ResolverRankerServiceWrapper mWrapper = null;

    /**
     * Called by the system to retrieve a list of probabilities to rank apps/options. To implement
     * it, set selectProbability of each input {@link ResolverTarget}. The higher the
     * selectProbability is, the more likely the {@link ResolverTarget} will be selected by the
     * user. Override this function to provide prediction results.
     *
     * @param targets a list of {@link ResolverTarget}, for the list of apps to be ranked.
     *
     * @throws Exception when the prediction task fails.
     */
    public void onPredictSharingProbabilities(final List<ResolverTarget> targets) {}

    /**
     * Called by the system to train/update a ranking service, after the user makes a selection from
     * the ranked list of apps. Override this function to enable model updates.
     *
     * @param targets a list of {@link ResolverTarget}, for the list of apps to be ranked.
     * @param selectedPosition the position of the selected app in the list.
     *
     * @throws Exception when the training task fails.
     */
    public void onTrainRankingModel(
            final List<ResolverTarget> targets, final int selectedPosition) {}

    private static final String HANDLER_THREAD_NAME = "RESOLVER_RANKER_SERVICE";
    private volatile Handler mHandler;
    private HandlerThread mHandlerThread;

    @Override
    public IBinder onBind(Intent intent) {
        if (DEBUG) Log.d(TAG, "onBind " + intent);
        if (!SERVICE_INTERFACE.equals(intent.getAction())) {
            if (DEBUG) Log.d(TAG, "bad intent action " + intent.getAction() + "; returning null");
            return null;
        }
        if (mHandlerThread == null) {
            mHandlerThread = new HandlerThread(HANDLER_THREAD_NAME);
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper());
        }
        if (mWrapper == null) {
            mWrapper = new ResolverRankerServiceWrapper();
        }
        return mWrapper;
    }

    @Override
    public void onDestroy() {
        mHandler = null;
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
        super.onDestroy();
    }

    private static void sendResult(List<ResolverTarget> targets, IResolverRankerResult result) {
        try {
            result.sendResult(targets);
        } catch (Exception e) {
            Log.e(TAG, "failed to send results: " + e);
        }
    }

    private class ResolverRankerServiceWrapper extends IResolverRankerService.Stub {

        @Override
        public void predict(final List<ResolverTarget> targets, final IResolverRankerResult result)
                throws RemoteException {
            Runnable predictRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "predict calls onPredictSharingProbabilities.");
                        }
                        onPredictSharingProbabilities(targets);
                        sendResult(targets, result);
                    } catch (Exception e) {
                        Log.e(TAG, "onPredictSharingProbabilities failed; send null results: " + e);
                        sendResult(null, result);
                    }
                }
            };
            final Handler h = mHandler;
            if (h != null) {
                h.post(predictRunnable);
            }
        }

        @Override
        public void train(final List<ResolverTarget> targets, final int selectedPosition)
                throws RemoteException {
            Runnable trainRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (DEBUG) {
                            Log.d(TAG, "train calls onTranRankingModel");
                        }
                        onTrainRankingModel(targets, selectedPosition);
                    } catch (Exception e) {
                        Log.e(TAG, "onTrainRankingModel failed; skip train: " + e);
                    }
                }
            };
            final Handler h = mHandler;
            if (h != null) {
                h.post(trainRunnable);
            }
        }
    }
}
