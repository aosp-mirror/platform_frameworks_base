/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE;
import static com.android.internal.app.ChooserActivity.TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER;

import android.app.ActivityManager;
import android.app.prediction.AppPredictor;
import android.app.prediction.AppTarget;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.os.AsyncTask;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.service.chooser.ChooserTarget;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;
import com.android.internal.app.ResolverActivity.ResolvedComponentInfo;
import com.android.internal.app.chooser.ChooserTargetInfo;
import com.android.internal.app.chooser.DisplayResolveInfo;
import com.android.internal.app.chooser.MultiDisplayResolveInfo;
import com.android.internal.app.chooser.SelectableTargetInfo;
import com.android.internal.app.chooser.TargetInfo;
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class ChooserListAdapter extends ResolverListAdapter {
    private static final String TAG = "ChooserListAdapter";
    private static final boolean DEBUG = false;

    private boolean mAppendDirectShareEnabled = DeviceConfig.getBoolean(
            DeviceConfig.NAMESPACE_SYSTEMUI,
            SystemUiDeviceConfigFlags.APPEND_DIRECT_SHARE_ENABLED,
            true);

    private boolean mEnableStackedApps = true;

    public static final int NO_POSITION = -1;
    public static final int TARGET_BAD = -1;
    public static final int TARGET_CALLER = 0;
    public static final int TARGET_SERVICE = 1;
    public static final int TARGET_STANDARD = 2;
    public static final int TARGET_STANDARD_AZ = 3;

    private static final int MAX_SUGGESTED_APP_TARGETS = 4;
    private static final int MAX_CHOOSER_TARGETS_PER_APP = 2;

    static final int MAX_SERVICE_TARGETS = 8;

    /** {@link #getBaseScore} */
    public static final float CALLER_TARGET_SCORE_BOOST = 900.f;
    /** {@link #getBaseScore} */
    public static final float SHORTCUT_TARGET_SCORE_BOOST = 90.f;

    private final int mMaxShortcutTargetsPerApp;
    private final ChooserListCommunicator mChooserListCommunicator;
    private final SelectableTargetInfo.SelectableTargetInfoCommunicator
            mSelectableTargetInfoComunicator;

    private int mNumShortcutResults = 0;

    // Reserve spots for incoming direct share targets by adding placeholders
    private ChooserTargetInfo
            mPlaceHolderTargetInfo = new ChooserActivity.PlaceHolderTargetInfo();
    private int mValidServiceTargetsNum = 0;
    private final Map<ComponentName, Pair<List<ChooserTargetInfo>, Integer>>
            mParkingDirectShareTargets = new HashMap<>();
    private final Map<ComponentName, Map<String, Integer>> mChooserTargetScores = new HashMap<>();
    private Set<ComponentName> mPendingChooserTargetService = new HashSet<>();
    private Set<ComponentName> mShortcutComponents = new HashSet<>();
    private final List<ChooserTargetInfo> mServiceTargets = new ArrayList<>();
    private final List<TargetInfo> mCallerTargets = new ArrayList<>();

    private final ChooserActivity.BaseChooserTargetComparator mBaseTargetComparator =
            new ChooserActivity.BaseChooserTargetComparator();
    private boolean mListViewDataChanged = false;

    // Sorted list of DisplayResolveInfos for the alphabetical app section.
    private List<DisplayResolveInfo> mSortedList = new ArrayList<>();
    private AppPredictor mAppPredictor;
    private AppPredictor.Callback mAppPredictorCallback;

    public ChooserListAdapter(Context context, List<Intent> payloadIntents,
            Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, ResolverListController resolverListController,
            boolean useLayoutForBrowsables,
            ChooserListCommunicator chooserListCommunicator,
            SelectableTargetInfo.SelectableTargetInfoCommunicator selectableTargetInfoComunicator) {
        // Don't send the initial intents through the shared ResolverActivity path,
        // we want to separate them into a different section.
        super(context, payloadIntents, null, rList, filterLastUsed,
                resolverListController, useLayoutForBrowsables,
                chooserListCommunicator, false);

        createPlaceHolders();
        mMaxShortcutTargetsPerApp =
                context.getResources().getInteger(R.integer.config_maxShortcutTargetsPerApp);
        mChooserListCommunicator = chooserListCommunicator;
        mSelectableTargetInfoComunicator = selectableTargetInfoComunicator;

        if (initialIntents != null) {
            final PackageManager pm = context.getPackageManager();
            for (int i = 0; i < initialIntents.length; i++) {
                final Intent ii = initialIntents[i];
                if (ii == null) {
                    continue;
                }

                // We reimplement Intent#resolveActivityInfo here because if we have an
                // implicit intent, we want the ResolveInfo returned by PackageManager
                // instead of one we reconstruct ourselves. The ResolveInfo returned might
                // have extra metadata and resolvePackageName set and we want to respect that.
                ResolveInfo ri = null;
                ActivityInfo ai = null;
                final ComponentName cn = ii.getComponent();
                if (cn != null) {
                    try {
                        ai = pm.getActivityInfo(ii.getComponent(), 0);
                        ri = new ResolveInfo();
                        ri.activityInfo = ai;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // ai will == null below
                    }
                }
                if (ai == null) {
                    ri = pm.resolveActivity(ii, PackageManager.MATCH_DEFAULT_ONLY);
                    ai = ri != null ? ri.activityInfo : null;
                }
                if (ai == null) {
                    Log.w(TAG, "No activity found for " + ii);
                    continue;
                }
                UserManager userManager =
                        (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (ii instanceof LabeledIntent) {
                    LabeledIntent li = (LabeledIntent) ii;
                    ri.resolvePackageName = li.getSourcePackage();
                    ri.labelRes = li.getLabelResource();
                    ri.nonLocalizedLabel = li.getNonLocalizedLabel();
                    ri.icon = li.getIconResource();
                    ri.iconResourceId = ri.icon;
                }
                if (userManager.isManagedProfile()) {
                    ri.noResourceId = true;
                    ri.icon = 0;
                }
                mCallerTargets.add(new DisplayResolveInfo(ii, ri, ii, makePresentationGetter(ri)));
            }
        }
    }

    AppPredictor getAppPredictor() {
        return mAppPredictor;
    }

    @Override
    public void handlePackagesChanged() {
        if (DEBUG) {
            Log.d(TAG, "clearing queryTargets on package change");
        }
        createPlaceHolders();
        mChooserListCommunicator.onHandlePackagesChanged(this);

    }

    @Override
    public void notifyDataSetChanged() {
        if (!mListViewDataChanged) {
            mChooserListCommunicator.sendListViewUpdateMessage(getUserHandle());
            mListViewDataChanged = true;
        }
    }

    void refreshListView() {
        if (mListViewDataChanged) {
            if (mAppendDirectShareEnabled) {
                appendServiceTargetsWithQuota();
            }
            super.notifyDataSetChanged();
        }
        mListViewDataChanged = false;
    }


    private void createPlaceHolders() {
        mNumShortcutResults = 0;
        mServiceTargets.clear();
        mValidServiceTargetsNum = 0;
        mParkingDirectShareTargets.clear();
        mPendingChooserTargetService.clear();
        mShortcutComponents.clear();
        for (int i = 0; i < MAX_SERVICE_TARGETS; i++) {
            mServiceTargets.add(mPlaceHolderTargetInfo);
        }
    }

    @Override
    View onCreateView(ViewGroup parent) {
        return mInflater.inflate(
                com.android.internal.R.layout.resolve_grid_item, parent, false);
    }

    @Override
    protected void onBindView(View view, TargetInfo info) {
        super.onBindView(view, info);

        // If target is loading, show a special placeholder shape in the label, make unclickable
        final ViewHolder holder = (ViewHolder) view.getTag();
        if (info instanceof ChooserActivity.PlaceHolderTargetInfo) {
            final int maxWidth = mContext.getResources().getDimensionPixelSize(
                    R.dimen.chooser_direct_share_label_placeholder_max_width);
            holder.text.setMaxWidth(maxWidth);
            holder.text.setBackground(mContext.getResources().getDrawable(
                    R.drawable.chooser_direct_share_label_placeholder, mContext.getTheme()));
            // Prevent rippling by removing background containing ripple
            holder.itemView.setBackground(null);
        } else {
            holder.text.setMaxWidth(Integer.MAX_VALUE);
            holder.text.setBackground(null);
            holder.itemView.setBackground(holder.defaultItemViewBackground);
        }
    }

    void updateAlphabeticalList() {
        mSortedList.clear();
        if (mEnableStackedApps) {
            // Consolidate multiple targets from same app.
            Map<String, DisplayResolveInfo> consolidated = new HashMap<>();
            for (DisplayResolveInfo info : mDisplayList) {
                String packageName = info.getResolvedComponentName().getPackageName();
                if (consolidated.get(packageName) != null) {
                    // create consolidated target
                    MultiDisplayResolveInfo multiDisplayResolveInfo =
                            new MultiDisplayResolveInfo(packageName, info);
                    multiDisplayResolveInfo.addTarget(consolidated.get(packageName));
                    consolidated.put(packageName, multiDisplayResolveInfo);
                } else {
                    consolidated.put(packageName, info);
                }
            }
            mSortedList.addAll(consolidated.values());
        } else {
            mSortedList.addAll(mDisplayList);
        }
        Collections.sort(mSortedList, new ChooserActivity.AzInfoComparator(mContext));
    }

    @Override
    public int getCount() {
        return getRankedTargetCount() + getAlphaTargetCount()
                + getSelectableServiceTargetCount() + getCallerTargetCount();
    }

    @Override
    public int getUnfilteredCount() {
        int appTargets = super.getUnfilteredCount();
        if (appTargets > mChooserListCommunicator.getMaxRankedTargets()) {
            appTargets = appTargets + mChooserListCommunicator.getMaxRankedTargets();
        }
        return appTargets + getSelectableServiceTargetCount() + getCallerTargetCount();
    }


    public int getCallerTargetCount() {
        return Math.min(mCallerTargets.size(), MAX_SUGGESTED_APP_TARGETS);
    }

    /**
     * Filter out placeholders and non-selectable service targets
     */
    public int getSelectableServiceTargetCount() {
        int count = 0;
        for (ChooserTargetInfo info : mServiceTargets) {
            if (info instanceof SelectableTargetInfo) {
                count++;
            }
        }
        return count;
    }

    public int getServiceTargetCount() {
        if (mChooserListCommunicator.isSendAction(mChooserListCommunicator.getTargetIntent())
                && !ActivityManager.isLowRamDeviceStatic()) {
            return Math.min(mServiceTargets.size(), MAX_SERVICE_TARGETS);
        }

        return 0;
    }

    int getAlphaTargetCount() {
        int standardCount = mSortedList.size();
        return standardCount > mChooserListCommunicator.getMaxRankedTargets() ? standardCount : 0;
    }

    int getRankedTargetCount() {
        int spacesAvailable =
                mChooserListCommunicator.getMaxRankedTargets() - getCallerTargetCount();
        return Math.min(spacesAvailable, super.getCount());
    }

    public int getPositionTargetType(int position) {
        int offset = 0;

        final int serviceTargetCount = getServiceTargetCount();
        if (position < serviceTargetCount) {
            return TARGET_SERVICE;
        }
        offset += serviceTargetCount;

        final int callerTargetCount = getCallerTargetCount();
        if (position - offset < callerTargetCount) {
            return TARGET_CALLER;
        }
        offset += callerTargetCount;

        final int rankedTargetCount = getRankedTargetCount();
        if (position - offset < rankedTargetCount) {
            return TARGET_STANDARD;
        }
        offset += rankedTargetCount;

        final int standardTargetCount = getAlphaTargetCount();
        if (position - offset < standardTargetCount) {
            return TARGET_STANDARD_AZ;
        }

        return TARGET_BAD;
    }

    @Override
    public TargetInfo getItem(int position) {
        return targetInfoForPosition(position, true);
    }


    /**
     * Find target info for a given position.
     * Since ChooserActivity displays several sections of content, determine which
     * section provides this item.
     */
    @Override
    public TargetInfo targetInfoForPosition(int position, boolean filtered) {
        if (position == NO_POSITION) {
            return null;
        }

        int offset = 0;

        // Direct share targets
        final int serviceTargetCount = filtered ? getServiceTargetCount() :
                getSelectableServiceTargetCount();
        if (position < serviceTargetCount) {
            return mServiceTargets.get(position);
        }
        offset += serviceTargetCount;

        // Targets provided by calling app
        final int callerTargetCount = getCallerTargetCount();
        if (position - offset < callerTargetCount) {
            return mCallerTargets.get(position - offset);
        }
        offset += callerTargetCount;

        // Ranked standard app targets
        final int rankedTargetCount = getRankedTargetCount();
        if (position - offset < rankedTargetCount) {
            return filtered ? super.getItem(position - offset)
                    : getDisplayResolveInfo(position - offset);
        }
        offset += rankedTargetCount;

        // Alphabetical complete app target list.
        if (position - offset < getAlphaTargetCount() && !mSortedList.isEmpty()) {
            return mSortedList.get(position - offset);
        }

        return null;
    }

    /**
     * Fetch surfaced direct share target info
     */
    public List<ChooserTargetInfo> getSurfacedTargetInfo() {
        int maxSurfacedTargets = mChooserListCommunicator.getMaxRankedTargets();
        return mServiceTargets.subList(0,
                Math.min(maxSurfacedTargets, getSelectableServiceTargetCount()));
    }


    /**
     * Evaluate targets for inclusion in the direct share area. May not be included
     * if score is too low.
     */
    public void addServiceResults(DisplayResolveInfo origTarget, List<ChooserTarget> targets,
            @ChooserActivity.ShareTargetType int targetType,
            Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos,
            List<ChooserActivity.ChooserTargetServiceConnection>
                    pendingChooserTargetServiceConnections) {
        if (DEBUG) {
            Log.d(TAG, "addServiceResults " + origTarget.getResolvedComponentName() + ", "
                    + targets.size()
                    + " targets");
        }
        if (mAppendDirectShareEnabled) {
            parkTargetIntoMemory(origTarget, targets, targetType, directShareToShortcutInfos,
                    pendingChooserTargetServiceConnections);
            return;
        }
        if (targets.size() == 0) {
            return;
        }

        final float baseScore = getBaseScore(origTarget, targetType);
        Collections.sort(targets, mBaseTargetComparator);

        final boolean isShortcutResult =
                (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER
                        || targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);
        final int maxTargets = isShortcutResult ? mMaxShortcutTargetsPerApp
                : MAX_CHOOSER_TARGETS_PER_APP;
        float lastScore = 0;
        boolean shouldNotify = false;
        for (int i = 0, count = Math.min(targets.size(), maxTargets); i < count; i++) {
            final ChooserTarget target = targets.get(i);
            float targetScore = target.getScore();
            targetScore *= baseScore;
            if (i > 0 && targetScore >= lastScore) {
                // Apply a decay so that the top app can't crowd out everything else.
                // This incents ChooserTargetServices to define what's truly better.
                targetScore = lastScore * 0.95f;
            }
            UserHandle userHandle = getUserHandle();
            Context contextAsUser = mContext.createContextAsUser(userHandle, 0 /* flags */);
            boolean isInserted = insertServiceTarget(new SelectableTargetInfo(contextAsUser,
                    origTarget, target, targetScore, mSelectableTargetInfoComunicator,
                    (isShortcutResult ? directShareToShortcutInfos.get(target) : null)));

            if (isInserted && isShortcutResult) {
                mNumShortcutResults++;
            }

            shouldNotify |= isInserted;

            if (DEBUG) {
                Log.d(TAG, " => " + target.toString() + " score=" + targetScore
                        + " base=" + target.getScore()
                        + " lastScore=" + lastScore
                        + " baseScore=" + baseScore);
            }

            lastScore = targetScore;
        }

        if (shouldNotify) {
            notifyDataSetChanged();
        }
    }

    /**
     * Store ChooserTarget ranking scores info wrapped in {@code targets}.
     */
    public void addChooserTargetRankingScore(List<AppTarget> targets) {
        Log.i(TAG, "addChooserTargetRankingScore " + targets.size() + " targets score.");
        for (AppTarget target : targets) {
            if (target.getShortcutInfo() == null) {
                continue;
            }
            ShortcutInfo shortcutInfo = target.getShortcutInfo();
            if (!shortcutInfo.getId().equals(ChooserActivity.CHOOSER_TARGET)
                    || shortcutInfo.getActivity() == null) {
                continue;
            }
            ComponentName componentName = shortcutInfo.getActivity();
            if (!mChooserTargetScores.containsKey(componentName)) {
                mChooserTargetScores.put(componentName, new HashMap<>());
            }
            mChooserTargetScores.get(componentName).put(shortcutInfo.getShortLabel().toString(),
                    shortcutInfo.getRank());
        }
        mChooserTargetScores.keySet().forEach(key -> rankTargetsWithinComponent(key));
    }

    /**
     * Rank chooserTargets of the given {@code componentName} in mParkingDirectShareTargets as per
     * available scores stored in mChooserTargetScores.
     */
    private void rankTargetsWithinComponent(ComponentName componentName) {
        if (!mParkingDirectShareTargets.containsKey(componentName)
                || !mChooserTargetScores.containsKey(componentName)) {
            return;
        }
        Map<String, Integer> scores = mChooserTargetScores.get(componentName);
        Collections.sort(mParkingDirectShareTargets.get(componentName).first, (o1, o2) -> {
            // The score has been normalized between 0 and 2, the default is 1.
            int score1 = scores.getOrDefault(
                    ChooserUtil.md5(o1.getChooserTarget().getTitle().toString()), 1);
            int score2 = scores.getOrDefault(
                    ChooserUtil.md5(o2.getChooserTarget().getTitle().toString()), 1);
            return score2 - score1;
        });
    }

    /**
     * Park {@code targets} into memory for the moment to surface them later when view is refreshed.
     * Components pending on ChooserTargetService query are also recorded.
     */
    private void parkTargetIntoMemory(DisplayResolveInfo origTarget, List<ChooserTarget> targets,
            @ChooserActivity.ShareTargetType int targetType,
            Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos,
            List<ChooserActivity.ChooserTargetServiceConnection>
                    pendingChooserTargetServiceConnections) {
        ComponentName origComponentName = origTarget != null ? origTarget.getResolvedComponentName()
                : !targets.isEmpty() ? targets.get(0).getComponentName() : null;
        Log.i(TAG,
                "parkTargetIntoMemory " + origComponentName + ", " + targets.size() + " targets");
        mPendingChooserTargetService = pendingChooserTargetServiceConnections.stream()
                .map(ChooserActivity.ChooserTargetServiceConnection::getComponentName)
                .filter(componentName -> !componentName.equals(origComponentName))
                .collect(Collectors.toSet());
        // Park targets in memory
        if (!targets.isEmpty()) {
            final boolean isShortcutResult =
                    (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER
                            || targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE);
            Context contextAsUser = mContext.createContextAsUser(getUserHandle(),
                    0 /* flags */);
            List<ChooserTargetInfo> parkingTargetInfos = targets.stream()
                    .map(target ->
                            new SelectableTargetInfo(
                                    contextAsUser, origTarget, target, target.getScore(),
                                    mSelectableTargetInfoComunicator,
                                    (isShortcutResult ? directShareToShortcutInfos.get(target)
                                            : null))
                    )
                    .collect(Collectors.toList());
            Pair<List<ChooserTargetInfo>, Integer> parkingTargetInfoPair =
                    mParkingDirectShareTargets.getOrDefault(origComponentName,
                            new Pair<>(new ArrayList<>(), 0));
            parkingTargetInfoPair.first.addAll(parkingTargetInfos);
            mParkingDirectShareTargets.put(origComponentName, parkingTargetInfoPair);
            rankTargetsWithinComponent(origComponentName);
            if (isShortcutResult) {
                mShortcutComponents.add(origComponentName);
            }
        }
        notifyDataSetChanged();
    }

    /**
     * Append targets of top ranked share app into direct share row with quota limit. Remove
     * appended ones from memory.
     */
    private void appendServiceTargetsWithQuota() {
        int maxRankedTargets = mChooserListCommunicator.getMaxRankedTargets();
        List<ComponentName> topComponentNames = getTopComponentNames(maxRankedTargets);
        float totalScore = 0f;
        for (ComponentName component : topComponentNames) {
            if (!mPendingChooserTargetService.contains(component)
                    && !mParkingDirectShareTargets.containsKey(component)) {
                continue;
            }
            totalScore += super.getScore(component);
        }
        boolean shouldWaitPendingService = false;
        for (ComponentName component : topComponentNames) {
            if (!mPendingChooserTargetService.contains(component)
                    && !mParkingDirectShareTargets.containsKey(component)) {
                continue;
            }
            float score = super.getScore(component);
            int quota = Math.round(maxRankedTargets * score / totalScore);
            if (mPendingChooserTargetService.contains(component) && quota >= 1) {
                shouldWaitPendingService = true;
            }
            if (!mParkingDirectShareTargets.containsKey(component)) {
                continue;
            }
            // Append targets into direct share row as per quota.
            Pair<List<ChooserTargetInfo>, Integer> parkingTargetsItem =
                    mParkingDirectShareTargets.get(component);
            List<ChooserTargetInfo> parkingTargets = parkingTargetsItem.first;
            int insertedNum = parkingTargetsItem.second;
            while (insertedNum < quota && !parkingTargets.isEmpty()) {
                if (!checkDuplicateTarget(parkingTargets.get(0))) {
                    mServiceTargets.add(mValidServiceTargetsNum, parkingTargets.get(0));
                    mValidServiceTargetsNum++;
                    insertedNum++;
                }
                parkingTargets.remove(0);
            }
            Log.i(TAG, " appendServiceTargetsWithQuota component=" + component
                    + " appendNum=" + (insertedNum - parkingTargetsItem.second));
            if (DEBUG) {
                Log.d(TAG, " appendServiceTargetsWithQuota component=" + component
                        + " score=" + score
                        + " totalScore=" + totalScore
                        + " quota=" + quota);
            }
            if (mShortcutComponents.contains(component)) {
                mNumShortcutResults += insertedNum - parkingTargetsItem.second;
            }
            mParkingDirectShareTargets.put(component, new Pair<>(parkingTargets, insertedNum));
        }
        if (!shouldWaitPendingService) {
            fillAllServiceTargets();
        }
    }

    /**
     * Append all remaining targets (parking in memory) into direct share row as per their ranking.
     */
    private void fillAllServiceTargets() {
        if (mParkingDirectShareTargets.isEmpty()) {
            return;
        }
        Log.i(TAG, " fillAllServiceTargets");
        int maxRankedTargets = mChooserListCommunicator.getMaxRankedTargets();
        List<ComponentName> topComponentNames = getTopComponentNames(maxRankedTargets);
        // Append all remaining targets of top recommended components into direct share row.
        for (ComponentName component : topComponentNames) {
            if (!mParkingDirectShareTargets.containsKey(component)) {
                continue;
            }
            mParkingDirectShareTargets.get(component).first.stream()
                    .filter(target -> !checkDuplicateTarget(target))
                    .forEach(target -> {
                        if (mShortcutComponents.contains(component)) {
                            mNumShortcutResults++;
                        }
                        mServiceTargets.add(mValidServiceTargetsNum, target);
                        mValidServiceTargetsNum++;
                    });
            mParkingDirectShareTargets.remove(component);
        }
        // Append all remaining shortcuts targets into direct share row.
        mParkingDirectShareTargets.entrySet().stream()
                .filter(entry -> mShortcutComponents.contains(entry.getKey()))
                .map(entry -> entry.getValue())
                .map(pair -> pair.first)
                .forEach(targets -> {
                    for (ChooserTargetInfo target : targets) {
                        if (!checkDuplicateTarget(target)) {
                            mServiceTargets.add(mValidServiceTargetsNum, target);
                            mValidServiceTargetsNum++;
                            mNumShortcutResults++;
                        }
                    }
                });
        mParkingDirectShareTargets.clear();
    }

    private boolean checkDuplicateTarget(ChooserTargetInfo chooserTargetInfo) {
        // Check for duplicates and abort if found
        for (ChooserTargetInfo otherTargetInfo : mServiceTargets) {
            if (chooserTargetInfo.isSimilar(otherTargetInfo)) {
                return true;
            }
        }
        return false;
    }

    int getNumShortcutResults() {
        return mNumShortcutResults;
    }

    /**
     * Use the scoring system along with artificial boosts to create up to 4 distinct buckets:
     * <ol>
     *   <li>App-supplied targets
     *   <li>Shortcuts ranked via App Prediction Manager
     *   <li>Shortcuts ranked via legacy heuristics
     *   <li>Legacy direct share targets
     * </ol>
     */
    public float getBaseScore(
            DisplayResolveInfo target,
            @ChooserActivity.ShareTargetType int targetType) {
        if (target == null) {
            return CALLER_TARGET_SCORE_BOOST;
        }

        if (targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE) {
            return SHORTCUT_TARGET_SCORE_BOOST;
        }

        float score = super.getScore(target);
        if (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER) {
            return score * SHORTCUT_TARGET_SCORE_BOOST;
        }

        return score;
    }

    /**
     * Calling this marks service target loading complete, and will attempt to no longer
     * update the direct share area.
     */
    public void completeServiceTargetLoading() {
        mServiceTargets.removeIf(o -> o instanceof ChooserActivity.PlaceHolderTargetInfo);
        if (mAppendDirectShareEnabled) {
            fillAllServiceTargets();
        }
        if (mServiceTargets.isEmpty()) {
            mServiceTargets.add(new ChooserActivity.EmptyTargetInfo());
        }
        notifyDataSetChanged();
    }

    private boolean insertServiceTarget(ChooserTargetInfo chooserTargetInfo) {
        // Avoid inserting any potentially late results
        if (mServiceTargets.size() == 1
                && mServiceTargets.get(0) instanceof ChooserActivity.EmptyTargetInfo) {
            return false;
        }

        // Check for duplicates and abort if found
        for (ChooserTargetInfo otherTargetInfo : mServiceTargets) {
            if (chooserTargetInfo.isSimilar(otherTargetInfo)) {
                return false;
            }
        }

        int currentSize = mServiceTargets.size();
        final float newScore = chooserTargetInfo.getModifiedScore();
        for (int i = 0; i < Math.min(currentSize, MAX_SERVICE_TARGETS); i++) {
            final ChooserTargetInfo serviceTarget = mServiceTargets.get(i);
            if (serviceTarget == null) {
                mServiceTargets.set(i, chooserTargetInfo);
                return true;
            } else if (newScore > serviceTarget.getModifiedScore()) {
                mServiceTargets.add(i, chooserTargetInfo);
                return true;
            }
        }

        if (currentSize < MAX_SERVICE_TARGETS) {
            mServiceTargets.add(chooserTargetInfo);
            return true;
        }

        return false;
    }

    public ChooserTarget getChooserTargetForValue(int value) {
        return mServiceTargets.get(value).getChooserTarget();
    }

    /**
     * Rather than fully sorting the input list, this sorting task will put the top k elements
     * in the head of input list and fill the tail with other elements in undetermined order.
     */
    @Override
    AsyncTask<List<ResolvedComponentInfo>,
                Void,
                List<ResolvedComponentInfo>> createSortingTask(boolean doPostProcessing) {
        return new AsyncTask<List<ResolvedComponentInfo>,
                Void,
                List<ResolvedComponentInfo>>() {
            @Override
            protected List<ResolvedComponentInfo> doInBackground(
                    List<ResolvedComponentInfo>... params) {
                mResolverListController.topK(params[0],
                        mChooserListCommunicator.getMaxRankedTargets());
                return params[0];
            }
            @Override
            protected void onPostExecute(List<ResolvedComponentInfo> sortedComponents) {
                processSortedList(sortedComponents, doPostProcessing);
                if (doPostProcessing) {
                    mChooserListCommunicator.updateProfileViewButton();
                    notifyDataSetChanged();
                }
            }
        };
    }

    public void setAppPredictor(AppPredictor appPredictor) {
        mAppPredictor = appPredictor;
    }

    public void setAppPredictorCallback(AppPredictor.Callback appPredictorCallback) {
        mAppPredictorCallback = appPredictorCallback;
    }

    public void destroyAppPredictor() {
        if (getAppPredictor() != null) {
            getAppPredictor().unregisterPredictionUpdates(mAppPredictorCallback);
            getAppPredictor().destroy();
        }
    }

    /**
     * Necessary methods to communicate between {@link ChooserListAdapter}
     * and {@link ChooserActivity}.
     */
    interface ChooserListCommunicator extends ResolverListCommunicator {

        int getMaxRankedTargets();

        void sendListViewUpdateMessage(UserHandle userHandle);

        boolean isSendAction(Intent targetIntent);
    }
}
