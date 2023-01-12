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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LabeledIntent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.service.chooser.ChooserTarget;
import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
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
import java.util.List;
import java.util.Map;

public class ChooserListAdapter extends ResolverListAdapter {
    private static final String TAG = "ChooserListAdapter";
    private static final boolean DEBUG = false;

    private boolean mEnableStackedApps = true;

    public static final int NO_POSITION = -1;
    public static final int TARGET_BAD = -1;
    public static final int TARGET_CALLER = 0;
    public static final int TARGET_SERVICE = 1;
    public static final int TARGET_STANDARD = 2;
    public static final int TARGET_STANDARD_AZ = 3;

    private static final int MAX_SUGGESTED_APP_TARGETS = 4;
    private static final int MAX_CHOOSER_TARGETS_PER_APP = 2;

    /** {@link #getBaseScore} */
    public static final float CALLER_TARGET_SCORE_BOOST = 900.f;
    /** {@link #getBaseScore} */
    public static final float SHORTCUT_TARGET_SCORE_BOOST = 90.f;
    private static final float PINNED_SHORTCUT_TARGET_SCORE_BOOST = 1000.f;

    private final int mMaxShortcutTargetsPerApp;
    private final ChooserListCommunicator mChooserListCommunicator;
    private final SelectableTargetInfo.SelectableTargetInfoCommunicator
            mSelectableTargetInfoCommunicator;
    private final ChooserActivityLogger mChooserActivityLogger;

    private int mNumShortcutResults = 0;
    private final Map<SelectableTargetInfo, LoadDirectShareIconTask> mIconLoaders = new HashMap<>();
    private boolean mApplySharingAppLimits;

    // Reserve spots for incoming direct share targets by adding placeholders
    private ChooserTargetInfo
            mPlaceHolderTargetInfo = new ChooserActivity.PlaceHolderTargetInfo();
    private final List<ChooserTargetInfo> mServiceTargets = new ArrayList<>();
    private final List<DisplayResolveInfo> mCallerTargets = new ArrayList<>();

    private final ChooserActivity.BaseChooserTargetComparator mBaseTargetComparator =
            new ChooserActivity.BaseChooserTargetComparator();
    private boolean mListViewDataChanged = false;

    // Sorted list of DisplayResolveInfos for the alphabetical app section.
    private List<DisplayResolveInfo> mSortedList = new ArrayList<>();
    private AppPredictor mAppPredictor;
    private AppPredictor.Callback mAppPredictorCallback;

    // For pinned direct share labels, if the text spans multiple lines, the TextView will consume
    // the full width, even if the characters actually take up less than that. Measure the actual
    // line widths and constrain the View's width based upon that so that the pin doesn't end up
    // very far from the text.
    private final View.OnLayoutChangeListener mPinTextSpacingListener =
            new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    TextView textView = (TextView) v;
                    Layout layout = textView.getLayout();
                    if (layout != null) {
                        int textWidth = 0;
                        for (int line = 0; line < layout.getLineCount(); line++) {
                            textWidth = Math.max((int) Math.ceil(layout.getLineMax(line)),
                                    textWidth);
                        }
                        int desiredWidth = textWidth + textView.getPaddingLeft()
                                + textView.getPaddingRight();
                        if (textView.getWidth() > desiredWidth) {
                            ViewGroup.LayoutParams params = textView.getLayoutParams();
                            params.width = desiredWidth;
                            textView.setLayoutParams(params);
                            // Need to wait until layout pass is over before requesting layout.
                            textView.post(() -> textView.requestLayout());
                        }
                        textView.removeOnLayoutChangeListener(this);
                    }
                }
            };

    public ChooserListAdapter(Context context, List<Intent> payloadIntents,
            Intent[] initialIntents, List<ResolveInfo> rList,
            boolean filterLastUsed, ResolverListController resolverListController,
            ChooserListCommunicator chooserListCommunicator,
            SelectableTargetInfo.SelectableTargetInfoCommunicator selectableTargetInfoCommunicator,
            PackageManager packageManager,
            ChooserActivityLogger chooserActivityLogger) {
        // Don't send the initial intents through the shared ResolverActivity path,
        // we want to separate them into a different section.
        super(context, payloadIntents, null, rList, filterLastUsed,
                resolverListController, chooserListCommunicator, false);

        mMaxShortcutTargetsPerApp =
                context.getResources().getInteger(R.integer.config_maxShortcutTargetsPerApp);
        mChooserListCommunicator = chooserListCommunicator;
        createPlaceHolders();
        mSelectableTargetInfoCommunicator = selectableTargetInfoCommunicator;
        mChooserActivityLogger = chooserActivityLogger;

        if (initialIntents != null) {
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
                        ai = packageManager.getActivityInfo(ii.getComponent(), 0);
                        ri = new ResolveInfo();
                        ri.activityInfo = ai;
                    } catch (PackageManager.NameNotFoundException ignored) {
                        // ai will == null below
                    }
                }
                if (ai == null) {
                    // Because of AIDL bug, resolveActivity can't accept subclasses of Intent.
                    final Intent rii = (ii.getClass() == Intent.class) ? ii : new Intent(ii);
                    ri = packageManager.resolveActivity(rii, PackageManager.MATCH_DEFAULT_ONLY);
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
                if (mCallerTargets.size() == MAX_SUGGESTED_APP_TARGETS) break;
            }
        }
        mApplySharingAppLimits = DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.APPLY_SHARING_APP_LIMITS_IN_SYSUI,
                true);
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
            super.notifyDataSetChanged();
        }
        mListViewDataChanged = false;
    }

    private void createPlaceHolders() {
        mNumShortcutResults = 0;
        mServiceTargets.clear();
        for (int i = 0; i < mChooserListCommunicator.getMaxRankedTargets(); i++) {
            mServiceTargets.add(mPlaceHolderTargetInfo);
        }
    }

    @Override
    View onCreateView(ViewGroup parent) {
        return mInflater.inflate(
                com.android.internal.R.layout.resolve_grid_item, parent, false);
    }

    @Override
    protected void onBindView(View view, TargetInfo info, int position) {
        final ViewHolder holder = (ViewHolder) view.getTag();

        if (info == null) {
            holder.icon.setImageDrawable(
                    mContext.getDrawable(R.drawable.resolver_icon_placeholder));
            return;
        }

        holder.bindLabel(info.getDisplayLabel(), info.getExtendedInfo(), alwaysShowSubLabel());
        holder.bindIcon(info);
        if (info instanceof SelectableTargetInfo) {
            // direct share targets should append the application name for a better readout
            SelectableTargetInfo sti = (SelectableTargetInfo) info;
            DisplayResolveInfo rInfo = sti.getDisplayResolveInfo();
            CharSequence appName = rInfo != null ? rInfo.getDisplayLabel() : "";
            CharSequence extendedInfo = info.getExtendedInfo();
            String contentDescription = String.join(" ", info.getDisplayLabel(),
                    extendedInfo != null ? extendedInfo : "", appName);
            holder.updateContentDescription(contentDescription);
            if (!sti.hasDisplayIcon()) {
                loadDirectShareIcon(sti);
            }
        } else if (info instanceof DisplayResolveInfo) {
            DisplayResolveInfo dri = (DisplayResolveInfo) info;
            if (!dri.hasDisplayIcon()) {
                loadIcon(dri);
            }
        }

        // If target is loading, show a special placeholder shape in the label, make unclickable
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

        // Always remove the spacing listener, attach as needed to direct share targets below.
        holder.text.removeOnLayoutChangeListener(mPinTextSpacingListener);

        if (info instanceof MultiDisplayResolveInfo) {
            // If the target is grouped show an indicator
            Drawable bkg = mContext.getDrawable(R.drawable.chooser_group_background);
            holder.text.setPaddingRelative(0, 0, bkg.getIntrinsicWidth() /* end */, 0);
            holder.text.setBackground(bkg);
        } else if (info.isPinned() && (getPositionTargetType(position) == TARGET_STANDARD
                || getPositionTargetType(position) == TARGET_SERVICE)) {
            // If the appShare or directShare target is pinned and in the suggested row show a
            // pinned indicator
            Drawable bkg = mContext.getDrawable(R.drawable.chooser_pinned_background);
            holder.text.setPaddingRelative(bkg.getIntrinsicWidth() /* start */, 0, 0, 0);
            holder.text.setBackground(bkg);
            holder.text.addOnLayoutChangeListener(mPinTextSpacingListener);
        } else {
            holder.text.setBackground(null);
            holder.text.setPaddingRelative(0, 0, 0, 0);
        }
    }

    private void loadDirectShareIcon(SelectableTargetInfo info) {
        LoadDirectShareIconTask task = (LoadDirectShareIconTask) mIconLoaders.get(info);
        if (task == null) {
            task = createLoadDirectShareIconTask(info);
            mIconLoaders.put(info, task);
            task.loadIcon();
        }
    }

    @VisibleForTesting
    protected LoadDirectShareIconTask createLoadDirectShareIconTask(SelectableTargetInfo info) {
        return new LoadDirectShareIconTask(info);
    }

    void updateAlphabeticalList() {
        new AsyncTask<Void, Void, List<DisplayResolveInfo>>() {
            @Override
            protected List<DisplayResolveInfo> doInBackground(Void... voids) {
                List<DisplayResolveInfo> allTargets = new ArrayList<>();
                allTargets.addAll(mDisplayList);
                allTargets.addAll(mCallerTargets);
                if (!mEnableStackedApps) {
                    return allTargets;
                }
                // Consolidate multiple targets from same app.
                Map<String, DisplayResolveInfo> consolidated = new HashMap<>();
                for (DisplayResolveInfo info : allTargets) {
                    String resolvedTarget = info.getResolvedComponentName().getPackageName()
                            + '#' + info.getDisplayLabel();
                    DisplayResolveInfo multiDri = consolidated.get(resolvedTarget);
                    if (multiDri == null) {
                        consolidated.put(resolvedTarget, info);
                    } else if (multiDri instanceof MultiDisplayResolveInfo) {
                        ((MultiDisplayResolveInfo) multiDri).addTarget(info);
                    } else {
                        // create consolidated target from the single DisplayResolveInfo
                        MultiDisplayResolveInfo multiDisplayResolveInfo =
                                new MultiDisplayResolveInfo(resolvedTarget, multiDri);
                        multiDisplayResolveInfo.addTarget(info);
                        consolidated.put(resolvedTarget, multiDisplayResolveInfo);
                    }
                }
                List<DisplayResolveInfo> groupedTargets = new ArrayList<>();
                groupedTargets.addAll(consolidated.values());
                Collections.sort(groupedTargets, new ChooserActivity.AzInfoComparator(mContext));
                return groupedTargets;
            }
            @Override
            protected void onPostExecute(List<DisplayResolveInfo> newList) {
                mSortedList = newList;
                notifyDataSetChanged();
            }
        }.execute();
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
        return mCallerTargets.size();
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
            return Math.min(mServiceTargets.size(), mChooserListCommunicator.getMaxRankedTargets());
        }

        return 0;
    }

    int getAlphaTargetCount() {
        int groupedCount = mSortedList.size();
        int ungroupedCount = mCallerTargets.size() + mDisplayList.size();
        return ungroupedCount > mChooserListCommunicator.getMaxRankedTargets() ? groupedCount : 0;
    }

    /**
     * Fetch ranked app target count
     */
    public int getRankedTargetCount() {
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

    // Check whether {@code dri} should be added into mDisplayList.
    @Override
    protected boolean shouldAddResolveInfo(DisplayResolveInfo dri) {
        // Checks if this info is already listed in callerTargets.
        for (TargetInfo existingInfo : mCallerTargets) {
            if (mResolverListCommunicator
                    .resolveInfoMatch(dri.getResolveInfo(), existingInfo.getResolveInfo())) {
                return false;
            }
        }
        return super.shouldAddResolveInfo(dri);
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
            Map<ChooserTarget, ShortcutInfo> directShareToShortcutInfos) {
        if (DEBUG) {
            Log.d(TAG, "addServiceResults " + origTarget.getResolvedComponentName() + ", "
                    + targets.size()
                    + " targets");
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
        final int targetsLimit = mApplySharingAppLimits ? Math.min(targets.size(), maxTargets)
                : targets.size();
        float lastScore = 0;
        boolean shouldNotify = false;
        for (int i = 0, count = targetsLimit; i < count; i++) {
            final ChooserTarget target = targets.get(i);
            float targetScore = target.getScore();
            if (mApplySharingAppLimits) {
                targetScore *= baseScore;
                if (i > 0 && targetScore >= lastScore) {
                    // Apply a decay so that the top app can't crowd out everything else.
                    // This incents ChooserTargetServices to define what's truly better.
                    targetScore = lastScore * 0.95f;
                }
            }
            ShortcutInfo shortcutInfo = isShortcutResult ? directShareToShortcutInfos.get(target)
                    : null;
            if ((shortcutInfo != null) && shortcutInfo.isPinned()) {
                targetScore += PINNED_SHORTCUT_TARGET_SCORE_BOOST;
            }
            UserHandle userHandle = getUserHandle();
            Context contextAsUser = mContext.createContextAsUser(userHandle, 0 /* flags */);
            boolean isInserted = insertServiceTarget(new SelectableTargetInfo(contextAsUser,
                    origTarget, target, targetScore, mSelectableTargetInfoCommunicator,
                    shortcutInfo));

            if (isInserted && isShortcutResult) {
                mNumShortcutResults++;
            }

            shouldNotify |= isInserted;

            if (DEBUG) {
                Log.d(TAG, " => " + target.toString() + " score=" + targetScore
                        + " base=" + target.getScore()
                        + " lastScore=" + lastScore
                        + " baseScore=" + baseScore
                        + " applyAppLimit=" + mApplySharingAppLimits);
            }

            lastScore = targetScore;
        }

        if (shouldNotify) {
            notifyDataSetChanged();
        }
    }

    /**
     * The return number have to exceed a minimum limit to make direct share area expandable. When
     * append direct share targets is enabled, return count of all available targets parking in the
     * memory; otherwise, it is shortcuts count which will help reduce the amount of visible
     * shuffling due to older-style direct share targets.
     */
    int getNumServiceTargetsForExpand() {
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
        float score = super.getScore(target);
        if (targetType == TARGET_TYPE_SHORTCUTS_FROM_SHORTCUT_MANAGER
                || targetType == TARGET_TYPE_SHORTCUTS_FROM_PREDICTION_SERVICE) {
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
        if (mServiceTargets.isEmpty()) {
            mServiceTargets.add(new ChooserActivity.EmptyTargetInfo());
            mChooserActivityLogger.logSharesheetEmptyDirectShareRow();
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
        for (int i = 0; i < Math.min(currentSize, mChooserListCommunicator.getMaxRankedTargets());
                i++) {
            final ChooserTargetInfo serviceTarget = mServiceTargets.get(i);
            if (serviceTarget == null) {
                mServiceTargets.set(i, chooserTargetInfo);
                return true;
            } else if (newScore > serviceTarget.getModifiedScore()) {
                mServiceTargets.add(i, chooserTargetInfo);
                return true;
            }
        }

        if (currentSize < mChooserListCommunicator.getMaxRankedTargets()) {
            mServiceTargets.add(chooserTargetInfo);
            return true;
        }

        return false;
    }

    public ChooserTarget getChooserTargetForValue(int value) {
        return mServiceTargets.get(value).getChooserTarget();
    }

    protected boolean alwaysShowSubLabel() {
        // Always show a subLabel for visual consistency across list items. Show an empty
        // subLabel if the subLabel is the same as the label
        return true;
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
                Trace.beginSection("ChooserListAdapter#SortingTask");
                mResolverListController.topK(params[0],
                        mChooserListCommunicator.getMaxRankedTargets());
                Trace.endSection();
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
            setAppPredictor(null);
        }
    }

    /**
     * Necessary methods to communicate between {@link ChooserListAdapter}
     * and {@link ChooserActivity}.
     */
    @VisibleForTesting
    public interface ChooserListCommunicator extends ResolverListCommunicator {

        int getMaxRankedTargets();

        void sendListViewUpdateMessage(UserHandle userHandle);

        boolean isSendAction(Intent targetIntent);
    }

    /**
     * Loads direct share targets icons.
     */
    @VisibleForTesting
    public class LoadDirectShareIconTask extends AsyncTask<Void, Void, Boolean> {
        private final SelectableTargetInfo mTargetInfo;

        private LoadDirectShareIconTask(SelectableTargetInfo targetInfo) {
            mTargetInfo = targetInfo;
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            return mTargetInfo.loadIcon();
        }

        @Override
        protected void onPostExecute(Boolean isLoaded) {
            if (isLoaded) {
                notifyDataSetChanged();
            }
        }

        /**
         * An alias for execute to use with unit tests.
         */
        public void loadIcon() {
            execute();
        }
    }
}
