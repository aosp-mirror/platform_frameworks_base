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

import android.app.usage.UsageStats;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.metrics.LogMaker;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.resolver.IResolverRankerResult;
import android.service.resolver.IResolverRankerService;
import android.service.resolver.ResolverRankerService;
import android.service.resolver.ResolverTarget;
import android.util.Log;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Ranks and compares packages based on usage stats and uses the {@link ResolverRankerService}.
 */
class ResolverRankerServiceResolverComparator extends AbstractResolverComparator {
    private static final String TAG = "RRSResolverComparator";

    private static final boolean DEBUG = false;

    // One week
    private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 7;

    private static final long RECENCY_TIME_PERIOD = 1000 * 60 * 60 * 12;

    private static final float RECENCY_MULTIPLIER = 2.f;

    // timeout for establishing connections with a ResolverRankerService.
    private static final int CONNECTION_COST_TIMEOUT_MILLIS = 200;

    private final Collator mCollator;
    private final Map<String, UsageStats> mStats;
    private final long mCurrentTime;
    private final long mSinceTime;
    private final LinkedHashMap<ComponentName, ResolverTarget> mTargetsDict = new LinkedHashMap<>();
    private final String mReferrerPackage;
    private final Object mLock = new Object();
    private ArrayList<ResolverTarget> mTargets;
    private String mAction;
    private ComponentName mResolvedRankerName;
    private ComponentName mRankerServiceName;
    private IResolverRankerService mRanker;
    private ResolverRankerServiceConnection mConnection;
    private Context mContext;
    private CountDownLatch mConnectSignal;

    public ResolverRankerServiceResolverComparator(Context context, Intent intent,
                String referrerPackage, AfterCompute afterCompute) {
        super(context, intent);
        mCollator = Collator.getInstance(context.getResources().getConfiguration().locale);
        mReferrerPackage = referrerPackage;
        mContext = context;

        mCurrentTime = System.currentTimeMillis();
        mSinceTime = mCurrentTime - USAGE_STATS_PERIOD;
        mStats = mUsm.queryAndAggregateUsageStats(mSinceTime, mCurrentTime);
        mAction = intent.getAction();
        mRankerServiceName = new ComponentName(mContext, this.getClass());
        setCallBack(afterCompute);
    }

    @Override
    public void handleResultMessage(Message msg) {
        if (msg.what != RANKER_SERVICE_RESULT) {
            return;
        }
        if (msg.obj == null) {
            Log.e(TAG, "Receiving null prediction results.");
            return;
        }
        final List<ResolverTarget> receivedTargets = (List<ResolverTarget>) msg.obj;
        if (receivedTargets != null && mTargets != null
                    && receivedTargets.size() == mTargets.size()) {
            final int size = mTargets.size();
            boolean isUpdated = false;
            for (int i = 0; i < size; ++i) {
                final float predictedProb =
                        receivedTargets.get(i).getSelectProbability();
                if (predictedProb != mTargets.get(i).getSelectProbability()) {
                    mTargets.get(i).setSelectProbability(predictedProb);
                    isUpdated = true;
                }
            }
            if (isUpdated) {
                mRankerServiceName = mResolvedRankerName;
            }
        } else {
            Log.e(TAG, "Sizes of sent and received ResolverTargets diff.");
        }
    }

    // compute features for each target according to usage stats of targets.
    @Override
    public void doCompute(List<ResolvedComponentInfo> targets) {
        final long recentSinceTime = mCurrentTime - RECENCY_TIME_PERIOD;

        float mostRecencyScore = 1.0f;
        float mostTimeSpentScore = 1.0f;
        float mostLaunchScore = 1.0f;
        float mostChooserScore = 1.0f;

        for (ResolvedComponentInfo target : targets) {
            final ResolverTarget resolverTarget = new ResolverTarget();
            mTargetsDict.put(target.name, resolverTarget);
            final UsageStats pkStats = mStats.get(target.name.getPackageName());
            if (pkStats != null) {
                // Only count recency for apps that weren't the caller
                // since the caller is always the most recent.
                // Persistent processes muck this up, so omit them too.
                if (!target.name.getPackageName().equals(mReferrerPackage)
                        && !isPersistentProcess(target)) {
                    final float recencyScore =
                            (float) Math.max(pkStats.getLastTimeUsed() - recentSinceTime, 0);
                    resolverTarget.setRecencyScore(recencyScore);
                    if (recencyScore > mostRecencyScore) {
                        mostRecencyScore = recencyScore;
                    }
                }
                final float timeSpentScore = (float) pkStats.getTotalTimeInForeground();
                resolverTarget.setTimeSpentScore(timeSpentScore);
                if (timeSpentScore > mostTimeSpentScore) {
                    mostTimeSpentScore = timeSpentScore;
                }
                final float launchScore = (float) pkStats.mLaunchCount;
                resolverTarget.setLaunchScore(launchScore);
                if (launchScore > mostLaunchScore) {
                    mostLaunchScore = launchScore;
                }

                float chooserScore = 0.0f;
                if (pkStats.mChooserCounts != null && mAction != null
                        && pkStats.mChooserCounts.get(mAction) != null) {
                    chooserScore = (float) pkStats.mChooserCounts.get(mAction)
                            .getOrDefault(mContentType, 0);
                    if (mAnnotations != null) {
                        final int size = mAnnotations.length;
                        for (int i = 0; i < size; i++) {
                            chooserScore += (float) pkStats.mChooserCounts.get(mAction)
                                    .getOrDefault(mAnnotations[i], 0);
                        }
                    }
                }
                if (DEBUG) {
                    if (mAction == null) {
                        Log.d(TAG, "Action type is null");
                    } else {
                        Log.d(TAG, "Chooser Count of " + mAction + ":" +
                                target.name.getPackageName() + " is " +
                                Float.toString(chooserScore));
                    }
                }
                resolverTarget.setChooserScore(chooserScore);
                if (chooserScore > mostChooserScore) {
                    mostChooserScore = chooserScore;
                }
            }
        }

        if (DEBUG) {
            Log.d(TAG, "compute - mostRecencyScore: " + mostRecencyScore
                    + " mostTimeSpentScore: " + mostTimeSpentScore
                    + " mostLaunchScore: " + mostLaunchScore
                    + " mostChooserScore: " + mostChooserScore);
        }

        mTargets = new ArrayList<>(mTargetsDict.values());
        for (ResolverTarget target : mTargets) {
            final float recency = target.getRecencyScore() / mostRecencyScore;
            setFeatures(target, recency * recency * RECENCY_MULTIPLIER,
                    target.getLaunchScore() / mostLaunchScore,
                    target.getTimeSpentScore() / mostTimeSpentScore,
                    target.getChooserScore() / mostChooserScore);
            addDefaultSelectProbability(target);
            if (DEBUG) {
                Log.d(TAG, "Scores: " + target);
            }
        }
        predictSelectProbabilities(mTargets);
    }

    @Override
    public int compare(ResolveInfo lhs, ResolveInfo rhs) {
        if (mStats != null) {
            final ResolverTarget lhsTarget = mTargetsDict.get(new ComponentName(
                    lhs.activityInfo.packageName, lhs.activityInfo.name));
            final ResolverTarget rhsTarget = mTargetsDict.get(new ComponentName(
                    rhs.activityInfo.packageName, rhs.activityInfo.name));

            if (lhsTarget != null && rhsTarget != null) {
                final int selectProbabilityDiff = Float.compare(
                        rhsTarget.getSelectProbability(), lhsTarget.getSelectProbability());

                if (selectProbabilityDiff != 0) {
                    return selectProbabilityDiff > 0 ? 1 : -1;
                }
            }
        }

        CharSequence  sa = lhs.loadLabel(mPm);
        if (sa == null) sa = lhs.activityInfo.name;
        CharSequence  sb = rhs.loadLabel(mPm);
        if (sb == null) sb = rhs.activityInfo.name;

        return mCollator.compare(sa.toString().trim(), sb.toString().trim());
    }

    @Override
    public float getScore(ComponentName name) {
        final ResolverTarget target = mTargetsDict.get(name);
        if (target != null) {
            return target.getSelectProbability();
        }
        return 0;
    }

    @Override
    List<ComponentName> getTopComponentNames(int topK) {
        return mTargetsDict.entrySet().stream()
                .sorted((o1, o2) -> -Float.compare(getScore(o1.getKey()), getScore(o2.getKey())))
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    // update ranking model when the connection to it is valid.
    @Override
    public void updateModel(ComponentName componentName) {
        synchronized (mLock) {
            if (mRanker != null) {
                try {
                    int selectedPos = new ArrayList<ComponentName>(mTargetsDict.keySet())
                            .indexOf(componentName);
                    if (selectedPos >= 0 && mTargets != null) {
                        final float selectedProbability = getScore(componentName);
                        int order = 0;
                        for (ResolverTarget target : mTargets) {
                            if (target.getSelectProbability() > selectedProbability) {
                                order++;
                            }
                        }
                        logMetrics(order);
                        mRanker.train(mTargets, selectedPos);
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Selected a unknown component: " + componentName);
                        }
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Error in Train: " + e);
                }
            } else {
                if (DEBUG) {
                    Log.d(TAG, "Ranker is null; skip updateModel.");
                }
            }
        }
    }

    // unbind the service and clear unhandled messges.
    @Override
    public void destroy() {
        mHandler.removeMessages(RANKER_SERVICE_RESULT);
        mHandler.removeMessages(RANKER_RESULT_TIMEOUT);
        if (mConnection != null) {
            mContext.unbindService(mConnection);
            mConnection.destroy();
        }
        afterCompute();
        if (DEBUG) {
            Log.d(TAG, "Unbinded Resolver Ranker.");
        }
    }

    // records metrics for evaluation.
    private void logMetrics(int selectedPos) {
        if (mRankerServiceName != null) {
            MetricsLogger metricsLogger = new MetricsLogger();
            LogMaker log = new LogMaker(MetricsEvent.ACTION_TARGET_SELECTED);
            log.setComponentName(mRankerServiceName);
            int isCategoryUsed = (mAnnotations == null) ? 0 : 1;
            log.addTaggedData(MetricsEvent.FIELD_IS_CATEGORY_USED, isCategoryUsed);
            log.addTaggedData(MetricsEvent.FIELD_RANKED_POSITION, selectedPos);
            metricsLogger.write(log);
        }
    }

    // connect to a ranking service.
    private void initRanker(Context context) {
        synchronized (mLock) {
            if (mConnection != null && mRanker != null) {
                if (DEBUG) {
                    Log.d(TAG, "Ranker still exists; reusing the existing one.");
                }
                return;
            }
        }
        Intent intent = resolveRankerService();
        if (intent == null) {
            return;
        }
        mConnectSignal = new CountDownLatch(1);
        mConnection = new ResolverRankerServiceConnection(mConnectSignal);
        context.bindServiceAsUser(intent, mConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
    }

    // resolve the service for ranking.
    private Intent resolveRankerService() {
        Intent intent = new Intent(ResolverRankerService.SERVICE_INTERFACE);
        final List<ResolveInfo> resolveInfos = mPm.queryIntentServices(intent, 0);
        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo == null || resolveInfo.serviceInfo == null
                    || resolveInfo.serviceInfo.applicationInfo == null) {
                if (DEBUG) {
                    Log.d(TAG, "Failed to retrieve a ranker: " + resolveInfo);
                }
                continue;
            }
            ComponentName componentName = new ComponentName(
                    resolveInfo.serviceInfo.applicationInfo.packageName,
                    resolveInfo.serviceInfo.name);
            try {
                final String perm = mPm.getServiceInfo(componentName, 0).permission;
                if (!ResolverRankerService.BIND_PERMISSION.equals(perm)) {
                    Log.w(TAG, "ResolverRankerService " + componentName + " does not require"
                            + " permission " + ResolverRankerService.BIND_PERMISSION
                            + " - this service will not be queried for "
                            + "ResolverRankerServiceResolverComparator. add android:permission=\""
                            + ResolverRankerService.BIND_PERMISSION + "\""
                            + " to the <service> tag for " + componentName
                            + " in the manifest.");
                    continue;
                }
                if (PackageManager.PERMISSION_GRANTED != mPm.checkPermission(
                        ResolverRankerService.HOLD_PERMISSION,
                        resolveInfo.serviceInfo.packageName)) {
                    Log.w(TAG, "ResolverRankerService " + componentName + " does not hold"
                            + " permission " + ResolverRankerService.HOLD_PERMISSION
                            + " - this service will not be queried for "
                            + "ResolverRankerServiceResolverComparator.");
                    continue;
                }
            } catch (NameNotFoundException e) {
                Log.e(TAG, "Could not look up service " + componentName
                        + "; component name not found");
                continue;
            }
            if (DEBUG) {
                Log.d(TAG, "Succeeded to retrieve a ranker: " + componentName);
            }
            mResolvedRankerName = componentName;
            intent.setComponent(componentName);
            return intent;
        }
        return null;
    }

    private class ResolverRankerServiceConnection implements ServiceConnection {
        private final CountDownLatch mConnectSignal;

        public ResolverRankerServiceConnection(CountDownLatch connectSignal) {
            mConnectSignal = connectSignal;
        }

        public final IResolverRankerResult resolverRankerResult =
                new IResolverRankerResult.Stub() {
            @Override
            public void sendResult(List<ResolverTarget> targets) throws RemoteException {
                if (DEBUG) {
                    Log.d(TAG, "Sending Result back to Resolver: " + targets);
                }
                synchronized (mLock) {
                    final Message msg = Message.obtain();
                    msg.what = RANKER_SERVICE_RESULT;
                    msg.obj = targets;
                    mHandler.sendMessage(msg);
                }
            }
        };

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Log.d(TAG, "onServiceConnected: " + name);
            }
            synchronized (mLock) {
                mRanker = IResolverRankerService.Stub.asInterface(service);
                mConnectSignal.countDown();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Log.d(TAG, "onServiceDisconnected: " + name);
            }
            synchronized (mLock) {
                destroy();
            }
        }

        public void destroy() {
            synchronized (mLock) {
                mRanker = null;
            }
        }
    }

    @Override
    void beforeCompute() {
        super.beforeCompute();
        mTargetsDict.clear();
        mTargets = null;
        mRankerServiceName = new ComponentName(mContext, this.getClass());
        mResolvedRankerName = null;
        initRanker(mContext);
    }

    // predict select probabilities if ranking service is valid.
    private void predictSelectProbabilities(List<ResolverTarget> targets) {
        if (mConnection == null) {
            if (DEBUG) {
                Log.d(TAG, "Has not found valid ResolverRankerService; Skip Prediction");
            }
        } else {
            try {
                mConnectSignal.await(CONNECTION_COST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                synchronized (mLock) {
                    if (mRanker != null) {
                        mRanker.predict(targets, mConnection.resolverRankerResult);
                        return;
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Ranker has not been initialized; skip predict.");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Error in Wait for Service Connection.");
            } catch (RemoteException e) {
                Log.e(TAG, "Error in Predict: " + e);
            }
        }
        afterCompute();
    }

    // adds select prob as the default values, according to a pre-trained Logistic Regression model.
    private void addDefaultSelectProbability(ResolverTarget target) {
        float sum = 2.5543f * target.getLaunchScore() + 2.8412f * target.getTimeSpentScore() +
                0.269f * target.getRecencyScore() + 4.2222f * target.getChooserScore();
        target.setSelectProbability((float) (1.0 / (1.0 + Math.exp(1.6568f - sum))));
    }

    // sets features for each target
    private void setFeatures(ResolverTarget target, float recencyScore, float launchScore,
                             float timeSpentScore, float chooserScore) {
        target.setRecencyScore(recencyScore);
        target.setLaunchScore(launchScore);
        target.setTimeSpentScore(timeSpentScore);
        target.setChooserScore(chooserScore);
    }

    static boolean isPersistentProcess(ResolvedComponentInfo rci) {
        if (rci != null && rci.getCount() > 0) {
            return (rci.getResolveInfoAt(0).activityInfo.applicationInfo.flags &
                    ApplicationInfo.FLAG_PERSISTENT) != 0;
        }
        return false;
    }
}
