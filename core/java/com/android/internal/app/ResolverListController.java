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


package com.android.internal.app;

import android.annotation.WorkerThread;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * A helper for the ResolverActivity that exposes methods to retrieve, filter and sort its list of
 * resolvers.
 */
public class ResolverListController {

    private final Context mContext;
    private final PackageManager mpm;
    private final int mLaunchedFromUid;

    // Needed for sorting resolvers.
    private final Intent mTargetIntent;
    private final String mReferrerPackage;

    private static final String TAG = "ResolverListController";
    private static final boolean DEBUG = false;

    private AbstractResolverComparator mResolverComparator;
    private boolean isComputed = false;

    public ResolverListController(
            Context context,
            PackageManager pm,
            Intent targetIntent,
            String referrerPackage,
            int launchedFromUid) {
        mContext = context;
        mpm = pm;
        mLaunchedFromUid = launchedFromUid;
        mTargetIntent = targetIntent;
        mReferrerPackage = referrerPackage;
        mResolverComparator =
                new ResolverRankerServiceResolverComparator(
                    mContext, mTargetIntent, mReferrerPackage, null);
    }

    @VisibleForTesting
    public ResolveInfo getLastChosen() throws RemoteException {
        return AppGlobals.getPackageManager().getLastChosenActivity(
                mTargetIntent, mTargetIntent.resolveTypeIfNeeded(mContext.getContentResolver()),
                PackageManager.MATCH_DEFAULT_ONLY);
    }

    @VisibleForTesting
    public void setLastChosen(Intent intent, IntentFilter filter, int match)
            throws RemoteException {
        AppGlobals.getPackageManager().setLastChosenActivity(intent,
                intent.resolveType(mContext.getContentResolver()),
                PackageManager.MATCH_DEFAULT_ONLY,
                filter, match, intent.getComponent());
    }

    @VisibleForTesting
    public List<ResolverActivity.ResolvedComponentInfo> getResolversForIntent(
            boolean shouldGetResolvedFilter,
            boolean shouldGetActivityMetadata,
            List<Intent> intents) {
        List<ResolverActivity.ResolvedComponentInfo> resolvedComponents = null;
        for (int i = 0, N = intents.size(); i < N; i++) {
            final Intent intent = intents.get(i);
            int flags = PackageManager.MATCH_DEFAULT_ONLY
                    | (shouldGetResolvedFilter ? PackageManager.GET_RESOLVED_FILTER : 0)
                    | (shouldGetActivityMetadata ? PackageManager.GET_META_DATA : 0);
            if (intent.isWebIntent()
                        || (intent.getFlags() & Intent.FLAG_ACTIVITY_MATCH_EXTERNAL) != 0) {
                flags |= PackageManager.MATCH_INSTANT;
            }
            final List<ResolveInfo> infos = mpm.queryIntentActivities(intent, flags);
            // Remove any activities that are not exported.
            int totalSize = infos.size();
            for (int j = totalSize - 1; j >= 0 ; j--) {
                ResolveInfo info = infos.get(j);
                if (info.activityInfo != null && !info.activityInfo.exported) {
                    infos.remove(j);
                }
            }
            if (infos != null) {
                if (resolvedComponents == null) {
                    resolvedComponents = new ArrayList<>();
                }
                addResolveListDedupe(resolvedComponents, intent, infos);
            }
        }
        return resolvedComponents;
    }

    @VisibleForTesting
    public void addResolveListDedupe(List<ResolverActivity.ResolvedComponentInfo> into,
            Intent intent,
            List<ResolveInfo> from) {
        final int fromCount = from.size();
        final int intoCount = into.size();
        for (int i = 0; i < fromCount; i++) {
            final ResolveInfo newInfo = from.get(i);
            boolean found = false;
            // Only loop to the end of into as it was before we started; no dupes in from.
            for (int j = 0; j < intoCount; j++) {
                final ResolverActivity.ResolvedComponentInfo rci = into.get(j);
                if (isSameResolvedComponent(newInfo, rci)) {
                    found = true;
                    rci.add(intent, newInfo);
                    break;
                }
            }
            if (!found) {
                final ComponentName name = new ComponentName(
                        newInfo.activityInfo.packageName, newInfo.activityInfo.name);
                final ResolverActivity.ResolvedComponentInfo rci =
                        new ResolverActivity.ResolvedComponentInfo(name, intent, newInfo);
                into.add(rci);
            }
        }
    }

    // Filter out any activities that the launched uid does not have permission for.
    //
    // Also filter out those that are suspended because they couldn't be started. We don't do this
    // when we have an explicit list of resolved activities, because that only happens when
    // we are being subclassed, so we can safely launch whatever they gave us.
    //
    // To preserve the inputList, optionally will return the original list if any modification has
    // been made.
    @VisibleForTesting
    public ArrayList<ResolverActivity.ResolvedComponentInfo> filterIneligibleActivities(
            List<ResolverActivity.ResolvedComponentInfo> inputList,
            boolean returnCopyOfOriginalListIfModified) {
        ArrayList<ResolverActivity.ResolvedComponentInfo> listToReturn = null;
        for (int i = inputList.size()-1; i >= 0; i--) {
            ActivityInfo ai = inputList.get(i)
                    .getResolveInfoAt(0).activityInfo;
            int granted = ActivityManager.checkComponentPermission(
                    ai.permission, mLaunchedFromUid,
                    ai.applicationInfo.uid, ai.exported);
            boolean suspended = (ai.applicationInfo.flags
                    & ApplicationInfo.FLAG_SUSPENDED) != 0;
            if (granted != PackageManager.PERMISSION_GRANTED || suspended
                    || isComponentFiltered(ai.getComponentName())) {
                // Access not allowed! We're about to filter an item,
                // so modify the unfiltered version if it hasn't already been modified.
                if (returnCopyOfOriginalListIfModified && listToReturn == null) {
                    listToReturn = new ArrayList<>(inputList);
                }
                inputList.remove(i);
            }
        }
        return listToReturn;
    }

    // Filter out any low priority items.
    //
    // To preserve the inputList, optionally will return the original list if any modification has
    // been made.
    @VisibleForTesting
    public ArrayList<ResolverActivity.ResolvedComponentInfo> filterLowPriority(
            List<ResolverActivity.ResolvedComponentInfo> inputList,
            boolean returnCopyOfOriginalListIfModified) {
        ArrayList<ResolverActivity.ResolvedComponentInfo> listToReturn = null;
        // Only display the first matches that are either of equal
        // priority or have asked to be default options.
        ResolverActivity.ResolvedComponentInfo rci0 = inputList.get(0);
        ResolveInfo r0 = rci0.getResolveInfoAt(0);
        int N = inputList.size();
        for (int i = 1; i < N; i++) {
            ResolveInfo ri = inputList.get(i).getResolveInfoAt(0);
            if (DEBUG) Log.v(
                    TAG,
                    r0.activityInfo.name + "=" +
                            r0.priority + "/" + r0.isDefault + " vs " +
                            ri.activityInfo.name + "=" +
                            ri.priority + "/" + ri.isDefault);
            if (r0.priority != ri.priority ||
                    r0.isDefault != ri.isDefault) {
                while (i < N) {
                    if (returnCopyOfOriginalListIfModified && listToReturn == null) {
                        listToReturn = new ArrayList<>(inputList);
                    }
                    inputList.remove(i);
                    N--;
                }
            }
        }
        return listToReturn;
    }

    private class ComputeCallback implements AbstractResolverComparator.AfterCompute {

        private CountDownLatch mFinishComputeSignal;

        public ComputeCallback(CountDownLatch finishComputeSignal) {
            mFinishComputeSignal = finishComputeSignal;
        }

        public void afterCompute () {
            mFinishComputeSignal.countDown();
        }
    }

    @VisibleForTesting
    @WorkerThread
    public void sort(List<ResolverActivity.ResolvedComponentInfo> inputList) {
        if (mResolverComparator == null) {
            Log.d(TAG, "Comparator has already been destroyed; skipped.");
            return;
        }
        try {
            long beforeRank = System.currentTimeMillis();
            if (!isComputed) {
                final CountDownLatch finishComputeSignal = new CountDownLatch(1);
                ComputeCallback callback = new ComputeCallback(finishComputeSignal);
                mResolverComparator.setCallBack(callback);
                mResolverComparator.compute(inputList);
                finishComputeSignal.await();
                isComputed = true;
            }
            Collections.sort(inputList, mResolverComparator);
            long afterRank = System.currentTimeMillis();
            if (DEBUG) {
                Log.d(TAG, "Time Cost: " + Long.toString(afterRank - beforeRank));
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Compute & Sort was interrupted: " + e);
        }
    }

    private static boolean isSameResolvedComponent(ResolveInfo a,
            ResolverActivity.ResolvedComponentInfo b) {
        final ActivityInfo ai = a.activityInfo;
        return ai.packageName.equals(b.name.getPackageName())
                && ai.name.equals(b.name.getClassName());
    }

    boolean isComponentFiltered(ComponentName componentName) {
        return false;
    }

    @VisibleForTesting
    public float getScore(ResolverActivity.DisplayResolveInfo target) {
        return mResolverComparator.getScore(target.getResolvedComponentName());
    }

    public void updateModel(ComponentName componentName) {
        mResolverComparator.updateModel(componentName);
    }

    public void updateChooserCounts(String packageName, int userId, String action) {
        mResolverComparator.updateChooserCounts(packageName, userId, action);
    }

    public void destroy() {
        mResolverComparator.destroy();
    }
}
