/*
 * Copyright 2018 The Android Open Source Project
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

import static android.app.prediction.AppTargetEvent.ACTION_LAUNCH;

import android.annotation.Nullable;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.app.prediction.AppTargetEvent;
import android.app.prediction.AppTargetId;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Message;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Uses an {@link AppPredictor} to sort Resolver targets. If the AppPredictionService appears to be
 * disabled by returning an empty sorted target list, {@link AppPredictionServiceResolverComparator}
 * will fallback to using a {@link ResolverRankerServiceResolverComparator}.
 */
class AppPredictionServiceResolverComparator extends AbstractResolverComparator {

    private static final String TAG = "APSResolverComparator";

    private final AppPredictor mAppPredictor;
    private final Context mContext;
    private final Map<ComponentName, Integer> mTargetRanks = new HashMap<>();
    private final Map<ComponentName, Integer> mTargetScores = new HashMap<>();
    private final UserHandle mUser;
    private final Intent mIntent;
    private final String mReferrerPackage;
    // If this is non-null (and this is not destroyed), it means APS is disabled and we should fall
    // back to using the ResolverRankerService.
    // TODO: responsibility for this fallback behavior can live outside of the AppPrediction client.
    private ResolverRankerServiceResolverComparator mResolverRankerService;
    private AppPredictionServiceComparatorModel mComparatorModel;

    AppPredictionServiceResolverComparator(
            Context context,
            Intent intent,
            String referrerPackage,
            AppPredictor appPredictor,
            UserHandle user,
            ChooserActivityLogger chooserActivityLogger) {
        super(context, intent);
        mContext = context;
        mIntent = intent;
        mAppPredictor = appPredictor;
        mUser = user;
        mReferrerPackage = referrerPackage;
        setChooserActivityLogger(chooserActivityLogger);
        mComparatorModel = buildUpdatedModel();
    }

    @Override
    int compare(ResolveInfo lhs, ResolveInfo rhs) {
        return mComparatorModel.getComparator().compare(lhs, rhs);
    }

    @Override
    void doCompute(List<ResolvedComponentInfo> targets) {
        if (targets.isEmpty()) {
            mHandler.sendEmptyMessage(RANKER_SERVICE_RESULT);
            return;
        }
        List<AppTarget> appTargets = new ArrayList<>();
        for (ResolvedComponentInfo target : targets) {
            appTargets.add(
                    new AppTarget.Builder(
                        new AppTargetId(target.name.flattenToString()),
                            target.name.getPackageName(),
                            mUser)
                    .setClassName(target.name.getClassName())
                    .build());
        }
        mAppPredictor.sortTargets(appTargets, Executors.newSingleThreadExecutor(),
                sortedAppTargets -> {
                    if (sortedAppTargets.isEmpty()) {
                        Log.i(TAG, "AppPredictionService disabled. Using resolver.");
                        // APS for chooser is disabled. Fallback to resolver.
                        mResolverRankerService =
                                new ResolverRankerServiceResolverComparator(
                                        mContext, mIntent, mReferrerPackage,
                                        () -> mHandler.sendEmptyMessage(RANKER_SERVICE_RESULT),
                                        getChooserActivityLogger());
                        mComparatorModel = buildUpdatedModel();
                        mResolverRankerService.compute(targets);
                    } else {
                        Log.i(TAG, "AppPredictionService response received");
                        // Skip sending to Handler which takes extra time to dispatch messages.
                        handleResult(sortedAppTargets);
                    }
                }
        );
    }

    @Override
    void handleResultMessage(Message msg) {
        // Null value is okay if we have defaulted to the ResolverRankerService.
        if (msg.what == RANKER_SERVICE_RESULT && msg.obj != null) {
            final List<AppTarget> sortedAppTargets = (List<AppTarget>) msg.obj;
            handleSortedAppTargets(sortedAppTargets);
        } else if (msg.obj == null && mResolverRankerService == null) {
            Log.e(TAG, "Unexpected null result");
        }
    }

    private void handleResult(List<AppTarget> sortedAppTargets) {
        if (mHandler.hasMessages(RANKER_RESULT_TIMEOUT)) {
            handleSortedAppTargets(sortedAppTargets);
            mHandler.removeMessages(RANKER_RESULT_TIMEOUT);
            afterCompute();
        }
    }

    private void handleSortedAppTargets(List<AppTarget> sortedAppTargets) {
        if (checkAppTargetRankValid(sortedAppTargets)) {
            sortedAppTargets.forEach(target -> mTargetScores.put(
                    new ComponentName(target.getPackageName(), target.getClassName()),
                    target.getRank()));
        }
        for (int i = 0; i < sortedAppTargets.size(); i++) {
            ComponentName componentName = new ComponentName(
                    sortedAppTargets.get(i).getPackageName(),
                    sortedAppTargets.get(i).getClassName());
            mTargetRanks.put(componentName, i);
            Log.i(TAG, "handleSortedAppTargets, sortedAppTargets #" + i + ": " + componentName);
        }
        mComparatorModel = buildUpdatedModel();
    }

    private boolean checkAppTargetRankValid(List<AppTarget> sortedAppTargets) {
        for (AppTarget target : sortedAppTargets) {
            if (target.getRank() != 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    float getScore(ComponentName name) {
        return mComparatorModel.getScore(name);
    }

    @Override
    void updateModel(ComponentName componentName) {
        mComparatorModel.notifyOnTargetSelected(componentName);
    }

    @Override
    void destroy() {
        if (mResolverRankerService != null) {
            mResolverRankerService.destroy();
            mResolverRankerService = null;
            mComparatorModel = buildUpdatedModel();
        }
    }

    /**
     * Re-construct an {@code AppPredictionServiceComparatorModel} to replace the current model
     * instance (if any) using the up-to-date {@code AppPredictionServiceResolverComparator} ivar
     * values.
     *
     * TODO: each time we replace the model instance, we're either updating the model to use
     * adjusted data (which is appropriate), or we're providing a (late) value for one of our ivars
     * that wasn't available the last time the model was updated. For those latter cases, we should
     * just avoid creating the model altogether until we have all the prerequisites we'll need. Then
     * we can probably simplify the logic in {@code AppPredictionServiceComparatorModel} since we
     * won't need to handle edge cases when the model data isn't fully prepared.
     * (In some cases, these kinds of "updates" might interleave -- e.g., we might have finished
     * initializing the first time and now want to adjust some data, but still need to wait for
     * changes to propagate to the other ivars before rebuilding the model.)
     */
    private AppPredictionServiceComparatorModel buildUpdatedModel() {
        return new AppPredictionServiceComparatorModel(
                mAppPredictor, mResolverRankerService, mUser, mTargetRanks);
    }

    // TODO: Finish separating behaviors of AbstractResolverComparator, then (probably) make this a
    // standalone class once clients are written in terms of ResolverComparatorModel.
    static class AppPredictionServiceComparatorModel implements ResolverComparatorModel {
        private final AppPredictor mAppPredictor;
        private final ResolverRankerServiceResolverComparator mResolverRankerService;
        private final UserHandle mUser;
        private final Map<ComponentName, Integer> mTargetRanks;  // Treat as immutable.

        AppPredictionServiceComparatorModel(
                AppPredictor appPredictor,
                @Nullable ResolverRankerServiceResolverComparator resolverRankerService,
                UserHandle user,
                Map<ComponentName, Integer> targetRanks) {
            mAppPredictor = appPredictor;
            mResolverRankerService = resolverRankerService;
            mUser = user;
            mTargetRanks = targetRanks;
        }

        @Override
        public Comparator<ResolveInfo> getComparator() {
            return (lhs, rhs) -> {
                if (mResolverRankerService != null) {
                    return mResolverRankerService.compare(lhs, rhs);
                }
                Integer lhsRank = mTargetRanks.get(new ComponentName(lhs.activityInfo.packageName,
                        lhs.activityInfo.name));
                Integer rhsRank = mTargetRanks.get(new ComponentName(rhs.activityInfo.packageName,
                        rhs.activityInfo.name));
                if (lhsRank == null && rhsRank == null) {
                    return 0;
                } else if (lhsRank == null) {
                    return -1;
                } else if (rhsRank == null) {
                    return 1;
                }
                return lhsRank - rhsRank;
            };
        }

        @Override
        public float getScore(ComponentName name) {
            if (mResolverRankerService != null) {
                return mResolverRankerService.getScore(name);
            }
            Integer rank = mTargetRanks.get(name);
            if (rank == null) {
                Log.w(TAG, "Score requested for unknown component. Did you call compute yet?");
                return 0f;
            }
            int consecutiveSumOfRanks = (mTargetRanks.size() - 1) * (mTargetRanks.size()) / 2;
            return 1.0f - (((float) rank) / consecutiveSumOfRanks);
        }

        @Override
        public void notifyOnTargetSelected(ComponentName componentName) {
            if (mResolverRankerService != null) {
                mResolverRankerService.updateModel(componentName);
                return;
            }
            mAppPredictor.notifyAppTargetEvent(
                    new AppTargetEvent.Builder(
                        new AppTarget.Builder(
                            new AppTargetId(componentName.toString()),
                            componentName.getPackageName(), mUser)
                            .setClassName(componentName.getClassName()).build(),
                        ACTION_LAUNCH).build());
        }
    }
}
