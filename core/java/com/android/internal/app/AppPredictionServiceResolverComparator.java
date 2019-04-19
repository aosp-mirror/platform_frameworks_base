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
import android.os.UserHandle;
import android.view.textclassifier.Log;

import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Uses an {@link AppPredictor} to sort Resolver targets.
 */
class AppPredictionServiceResolverComparator extends AbstractResolverComparator {

    private static final String TAG = "APSResolverComparator";

    private final AppPredictor mAppPredictor;
    private final Context mContext;
    private final Map<ComponentName, Integer> mTargetRanks = new HashMap<>();
    private final UserHandle mUser;

    AppPredictionServiceResolverComparator(
                Context context, Intent intent, AppPredictor appPredictor, UserHandle user) {
        super(context, intent);
        mContext = context;
        mAppPredictor = appPredictor;
        mUser = user;
    }

    @Override
    int compare(ResolveInfo lhs, ResolveInfo rhs) {
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
    }

    @Override
    void compute(List<ResolvedComponentInfo> targets) {
        List<AppTarget> appTargets = new ArrayList<>();
        for (ResolvedComponentInfo target : targets) {
            appTargets.add(new AppTarget.Builder(new AppTargetId(target.name.flattenToString()))
                    .setTarget(target.name.getPackageName(), mUser)
                    .setClassName(target.name.getClassName()).build());
        }
        mAppPredictor.sortTargets(appTargets, mContext.getMainExecutor(),
                sortedAppTargets -> {
                    for (int i = 0; i < sortedAppTargets.size(); i++) {
                        mTargetRanks.put(new ComponentName(sortedAppTargets.get(i).getPackageName(),
                                sortedAppTargets.get(i).getClassName()), i);
                    }
                    afterCompute();
                });
    }

    @Override
    float getScore(ComponentName name) {
        Integer rank = mTargetRanks.get(name);
        if (rank == null) {
            Log.w(TAG, "Score requested for unknown component.");
            return 0f;
        }
        int consecutiveSumOfRanks = (mTargetRanks.size() - 1) * (mTargetRanks.size()) / 2;
        return 1.0f - (((float) rank) / consecutiveSumOfRanks);
    }

    @Override
    void updateModel(ComponentName componentName) {
        mAppPredictor.notifyAppTargetEvent(
                new AppTargetEvent.Builder(
                    new AppTarget.Builder(
                        new AppTargetId(componentName.toString()),
                        componentName.getPackageName(), mUser)
                        .setClassName(componentName.getClassName()).build(),
                    ACTION_LAUNCH).build());
    }

    @Override
    void destroy() {
        // Do nothing. App Predictor destruction is handled by caller.
    }
}
