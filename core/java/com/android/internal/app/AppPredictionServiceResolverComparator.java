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
import com.android.internal.app.chooser.TargetInfo;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
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
    private final UserHandle mUser;
    private final Intent mIntent;
    private final String mReferrerPackage;

    private final ModelBuilder mModelBuilder;
    private ResolverComparatorModel mComparatorModel;

    private ResolverAppPredictorCallback mSortingCallback;

    // If this is non-null (and this is not destroyed), it means APS is disabled and we should fall
    // back to using the ResolverRankerService.
    // TODO: responsibility for this fallback behavior can live outside of the AppPrediction client.
    private ResolverRankerServiceResolverComparator mResolverRankerService;

    AppPredictionServiceResolverComparator(
            Context context,
            Intent intent,
            String referrerPackage,
            AppPredictor appPredictor,
            UserHandle user,
            ChooserActivityLogger chooserActivityLogger) {
        super(context, intent, Lists.newArrayList(user));
        mContext = context;
        mIntent = intent;
        mAppPredictor = appPredictor;
        mUser = user;
        mReferrerPackage = referrerPackage;
        setChooserActivityLogger(chooserActivityLogger);

        mModelBuilder = new ModelBuilder(appPredictor, user);
        mComparatorModel = mModelBuilder.buildFromRankedList(Collections.emptyList());
    }

    @Override
    void destroy() {
        if (mResolverRankerService != null) {
            mResolverRankerService.destroy();
            mResolverRankerService = null;

            // TODO: may not be necessary to build a new model, since we're destroying anyways.
            mComparatorModel = mModelBuilder.buildFallbackModel(mResolverRankerService);
        }
        if (mSortingCallback != null) {
            mSortingCallback.destroy();
        }
    }

    @Override
    int compare(ResolveInfo lhs, ResolveInfo rhs) {
        return mComparatorModel.getComparator().compare(lhs, rhs);
    }

    @Override
    float getScore(TargetInfo targetInfo) {
        return mComparatorModel.getScore(targetInfo);
    }

    @Override
    void updateModel(TargetInfo targetInfo) {
        mComparatorModel.notifyOnTargetSelected(targetInfo);
    }

    @Override
    void handleResultMessage(Message msg) {
        // Null value is okay if we have defaulted to the ResolverRankerService.
        if (msg.what == RANKER_SERVICE_RESULT && msg.obj != null) {
            // TODO: this probably never happens? The sorting callback circumvents the Handler
            // design to call handleResult() directly instead of sending the list through a Message.
            // (OK to leave as-is since the Handler design is going away soon.)
            mComparatorModel = mModelBuilder.buildFromRankedList((List<AppTarget>) msg.obj);
        } else if (msg.obj == null && mResolverRankerService == null) {
            Log.e(TAG, "Unexpected null result");
        }
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

        if (mSortingCallback != null) {
            mSortingCallback.destroy();
        }
        mSortingCallback = new ResolverAppPredictorCallback(sortedAppTargets -> {
            if (sortedAppTargets.isEmpty()) {
                Log.i(TAG, "AppPredictionService disabled. Using resolver.");
                setupFallbackModel(targets);
            } else {
                Log.i(TAG, "AppPredictionService response received");
                // Skip sending to Handler which takes extra time to dispatch messages.
                // TODO: the Handler guards some concurrency conditions, so this could
                // probably result in a race (we're not currently on the Handler thread?).
                // We'll leave this as-is since we intend to remove the Handler design
                // shortly, but this is still an unsound shortcut.
                handleResult(sortedAppTargets);
            }
        });

        mAppPredictor.sortTargets(appTargets, Executors.newSingleThreadExecutor(),
                mSortingCallback.asConsumer());
    }

    private void setupFallbackModel(List<ResolvedComponentInfo> targets) {
        mResolverRankerService =
                new ResolverRankerServiceResolverComparator(
                        mContext,
                        mIntent,
                        mReferrerPackage,
                        () -> mHandler.sendEmptyMessage(RANKER_SERVICE_RESULT),
                        getChooserActivityLogger(),
                        mUser);
        mComparatorModel = mModelBuilder.buildFallbackModel(mResolverRankerService);
        mResolverRankerService.compute(targets);
    }

    private void handleResult(List<AppTarget> sortedAppTargets) {
        if (mHandler.hasMessages(RANKER_RESULT_TIMEOUT)) {
            mComparatorModel = mModelBuilder.buildFromRankedList(sortedAppTargets);
            mHandler.removeMessages(RANKER_RESULT_TIMEOUT);
            afterCompute();
        }
    }

    static class ModelBuilder {
        private final AppPredictor mAppPredictor;
        private final UserHandle mUser;

        ModelBuilder(AppPredictor appPredictor, UserHandle user) {
            mAppPredictor = appPredictor;
            mUser = user;
        }

        ResolverComparatorModel buildFromRankedList(List<AppTarget> sortedAppTargets) {
            return new AppPredictionServiceComparatorModel(
                    mAppPredictor, mUser, buildTargetRanksMapFromSortedTargets(sortedAppTargets));
        }

        ResolverComparatorModel buildFallbackModel(
                ResolverRankerServiceResolverComparator fallback) {
            return adaptLegacyResolverComparatorToComparatorModel(fallback);
        }

        // The remaining methods would be static if this weren't an inner class (i.e., they don't
        // depend on any ivars, not even the ones in ModelBuilder).

        private Map<ComponentName, Integer> buildTargetRanksMapFromSortedTargets(
                List<AppTarget> sortedAppTargets) {
            Map<ComponentName, Integer> targetRanks = new HashMap<>();
            for (int i = 0; i < sortedAppTargets.size(); i++) {
                ComponentName componentName = new ComponentName(
                        sortedAppTargets.get(i).getPackageName(),
                        sortedAppTargets.get(i).getClassName());
                targetRanks.put(componentName, i);
                Log.i(TAG, "handleSortedAppTargets, sortedAppTargets #" + i + ": " + componentName);
            }
            return targetRanks;
        }

        // TODO: when the refactoring is further along we'll probably have access to the
        // comparator's new ResolverComparatorModel API, so we won't have to adapt from the legacy
        // interface here. On the other hand, AppPredictionServiceResolverComparatorModel (or its
        // replacement counterpart) shouldn't still be responsible for implementing the
        // ResolverRankerService fallback logic at that time.
        private ResolverComparatorModel adaptLegacyResolverComparatorToComparatorModel(
                AbstractResolverComparator comparator) {
            return new ResolverComparatorModel() {
                    @Override
                    public Comparator<ResolveInfo> getComparator() {
                        // Adapt the base type, which doesn't declare itself to be an implementation
                        // of {@code Comparator<ResolveInfo>} even though it has the one method.
                        return (lhs, rhs) -> comparator.compare(lhs, rhs);
                    }

                    @Override
                    public float getScore(TargetInfo targetInfo) {
                        return comparator.getScore(targetInfo);
                    }

                    @Override
                    public void notifyOnTargetSelected(TargetInfo targetInfo) {
                        comparator.updateModel(targetInfo);
                    }
                };
        }
    }

    // TODO: Finish separating behaviors of AbstractResolverComparator, then (probably) make this a
    // standalone class once clients are written in terms of ResolverComparatorModel.
    static class AppPredictionServiceComparatorModel implements ResolverComparatorModel {
        private final AppPredictor mAppPredictor;
        private final UserHandle mUser;
        private final Map<ComponentName, Integer> mTargetRanks;  // Treat as immutable.

        AppPredictionServiceComparatorModel(
                AppPredictor appPredictor,
                UserHandle user,
                Map<ComponentName, Integer> targetRanks) {
            mAppPredictor = appPredictor;
            mUser = user;
            mTargetRanks = targetRanks;
        }

        @Override
        public Comparator<ResolveInfo> getComparator() {
            return (lhs, rhs) -> {
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
        public float getScore(TargetInfo targetInfo) {
            Integer rank = mTargetRanks.get(targetInfo.getResolvedComponentName());
            if (rank == null) {
                Log.w(TAG, "Score requested for unknown component. Did you call compute yet?");
                return 0f;
            }
            int consecutiveSumOfRanks = (mTargetRanks.size() - 1) * (mTargetRanks.size()) / 2;
            return 1.0f - (((float) rank) / consecutiveSumOfRanks);
        }

        @Override
        public void notifyOnTargetSelected(TargetInfo targetInfo) {
            mAppPredictor.notifyAppTargetEvent(
                    new AppTargetEvent.Builder(
                        new AppTarget.Builder(
                            new AppTargetId(targetInfo.getResolvedComponentName().toString()),
                                targetInfo.getResolvedComponentName().getPackageName(), mUser)
                            .setClassName(targetInfo.getResolvedComponentName()
                                    .getClassName()).build(),
                        ACTION_LAUNCH).build());
        }
    }
}
