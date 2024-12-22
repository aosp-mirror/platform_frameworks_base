/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Region;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Pools;

import androidx.collection.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingAllowedListener;
import com.android.systemui.statusbar.notification.collection.provider.OnReorderingBannedListener;
import com.android.systemui.statusbar.notification.collection.provider.VisualStabilityProvider;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository;
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun;
import com.android.systemui.statusbar.phone.ExpandHeadsUpOnInlineReply;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.AnimationStateHandler;
import com.android.systemui.statusbar.policy.AvalancheController;
import com.android.systemui.statusbar.policy.BaseHeadsUpManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;
import com.android.systemui.statusbar.policy.OnHeadsUpPhoneListenerChange;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.SystemClock;

import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;

import javax.inject.Inject;

/** A implementation of HeadsUpManager for phone. */
@SysUISingleton
public class HeadsUpManagerPhone extends BaseHeadsUpManager implements
        HeadsUpRepository, OnHeadsUpChangedListener {
    private static final String TAG = "HeadsUpManagerPhone";

    @VisibleForTesting
    public final int mExtensionTime;
    private final KeyguardBypassController mBypassController;
    private final GroupMembershipManager mGroupMembershipManager;
    private final List<OnHeadsUpPhoneListenerChange> mHeadsUpPhoneListeners = new ArrayList<>();
    private final VisualStabilityProvider mVisualStabilityProvider;

    private AvalancheController mAvalancheController;

    // TODO(b/328393698) move the topHeadsUpRow logic to an interactor
    private final MutableStateFlow<HeadsUpRowRepository> mTopHeadsUpRow =
            StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<Set<HeadsUpRowRepository>> mHeadsUpNotificationRows =
            StateFlowKt.MutableStateFlow(new HashSet<>());
    private final MutableStateFlow<Boolean> mHeadsUpAnimatingAway =
            StateFlowKt.MutableStateFlow(false);
    private boolean mReleaseOnExpandFinish;
    private boolean mTrackingHeadsUp;
    private final HashSet<String> mSwipedOutKeys = new HashSet<>();
    private final HashSet<NotificationEntry> mEntriesToRemoveAfterExpand = new HashSet<>();
    @VisibleForTesting
    public final ArraySet<NotificationEntry> mEntriesToRemoveWhenReorderingAllowed
            = new ArraySet<>();
    private boolean mIsShadeOrQsExpanded;
    private boolean mIsQsExpanded;
    private int mStatusBarState;
    private AnimationStateHandler mAnimationStateHandler;

    private int mHeadsUpInset;

    // Used for determining the region for touch interaction
    private final Region mTouchableRegion = new Region();

    private final Pools.Pool<HeadsUpEntryPhone> mEntryPool = new Pools.Pool<HeadsUpEntryPhone>() {
        private Stack<HeadsUpEntryPhone> mPoolObjects = new Stack<>();

        @Override
        public HeadsUpEntryPhone acquire() {
            NotificationThrottleHun.assertInLegacyMode();
            if (!mPoolObjects.isEmpty()) {
                return mPoolObjects.pop();
            }
            return new HeadsUpEntryPhone();
        }

        @Override
        public boolean release(@NonNull HeadsUpEntryPhone instance) {
            NotificationThrottleHun.assertInLegacyMode();
            mPoolObjects.push(instance);
            return true;
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Constructor:
    @Inject
    public HeadsUpManagerPhone(
            @NonNull final Context context,
            HeadsUpManagerLogger logger,
            StatusBarStateController statusBarStateController,
            KeyguardBypassController bypassController,
            GroupMembershipManager groupMembershipManager,
            VisualStabilityProvider visualStabilityProvider,
            ConfigurationController configurationController,
            @Main Handler handler,
            GlobalSettings globalSettings,
            SystemClock systemClock,
            @Main DelayableExecutor executor,
            AccessibilityManagerWrapper accessibilityManagerWrapper,
            UiEventLogger uiEventLogger,
            JavaAdapter javaAdapter,
            ShadeInteractor shadeInteractor,
            AvalancheController avalancheController) {
        super(context, logger, handler, globalSettings, systemClock, executor,
                accessibilityManagerWrapper, uiEventLogger, avalancheController);
        Resources resources = mContext.getResources();
        mExtensionTime = resources.getInteger(R.integer.ambient_notification_extension_time);
        statusBarStateController.addCallback(mStatusBarStateListener);
        mBypassController = bypassController;
        mGroupMembershipManager = groupMembershipManager;
        mVisualStabilityProvider = visualStabilityProvider;
        mAvalancheController = avalancheController;
        updateResources();
        configurationController.addCallback(new ConfigurationController.ConfigurationListener() {
            @Override
            public void onDensityOrFontScaleChanged() {
                updateResources();
            }

            @Override
            public void onThemeChanged() {
                updateResources();
            }
        });
        javaAdapter.alwaysCollectFlow(shadeInteractor.isAnyExpanded(),
                    this::onShadeOrQsExpanded);
        if (SceneContainerFlag.isEnabled()) {
            javaAdapter.alwaysCollectFlow(shadeInteractor.isQsExpanded(),
                    this::onQsExpanded);
        }
        if (NotificationThrottleHun.isEnabled()) {
            mVisualStabilityProvider.addPersistentReorderingBannedListener(
                    mOnReorderingBannedListener);
            mVisualStabilityProvider.addPersistentReorderingAllowedListener(
                    mOnReorderingAllowedListener);
        }
    }

    public void setAnimationStateHandler(AnimationStateHandler handler) {
        mAnimationStateHandler = handler;
    }

    private void updateResources() {
        Resources resources = mContext.getResources();
        mHeadsUpInset = SystemBarUtils.getStatusBarHeight(mContext)
                + resources.getDimensionPixelSize(R.dimen.heads_up_status_bar_padding);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Public methods:

    /**
     * Add a listener to receive callbacks {@link #setHeadsUpAnimatingAway(boolean)}
     */
    @Override
    public void addHeadsUpPhoneListener(OnHeadsUpPhoneListenerChange listener) {
        mHeadsUpPhoneListeners.add(listener);
    }

    /**
     * Gets the touchable region needed for heads up notifications. Returns null if no touchable
     * region is required (ie: no heads up notification currently exists).
     */
    // TODO(b/347007367): With scene container enabled this method may report outdated regions
    @Override
    public @Nullable Region getTouchableRegion() {
        NotificationEntry topEntry = getTopEntry();

        // This call could be made in an inconsistent state while the pinnedMode hasn't been
        // updated yet, but callbacks leading out of the headsUp manager, querying it. Let's
        // therefore also check if the topEntry is null.
        if (!hasPinnedHeadsUp() || topEntry == null) {
            return null;
        } else {
            if (topEntry.rowIsChildInGroup()) {
                final NotificationEntry groupSummary =
                        mGroupMembershipManager.getGroupSummary(topEntry);
                if (groupSummary != null) {
                    topEntry = groupSummary;
                }
            }
            ExpandableNotificationRow topRow = topEntry.getRow();
            int[] tmpArray = new int[2];
            topRow.getLocationOnScreen(tmpArray);
            int minX = tmpArray[0];
            int maxX = tmpArray[0] + topRow.getWidth();
            int height = topRow.getIntrinsicHeight();
            final boolean stretchToTop = tmpArray[1] <= mHeadsUpInset;
            mTouchableRegion.set(minX, stretchToTop ? 0 : tmpArray[1], maxX, tmpArray[1] + height);
            return mTouchableRegion;
        }
    }

    /**
     * Decides whether a click is invalid for a notification, i.e it has not been shown long enough
     * that a user might have consciously clicked on it.
     *
     * @param key the key of the touched notification
     * @return whether the touch is invalid and should be discarded
     */
    @Override
    public boolean shouldSwallowClick(@NonNull String key) {
        BaseHeadsUpManager.HeadsUpEntry entry = getHeadsUpEntry(key);
        return entry != null && mSystemClock.elapsedRealtime() < entry.mPostTime;
    }

    @Override
    public void releaseAfterExpansion() {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        onExpandingFinished();
    }

    public void onExpandingFinished() {
        if (mReleaseOnExpandFinish) {
            releaseAllImmediately();
            mReleaseOnExpandFinish = false;
        } else {
            for (NotificationEntry entry: getAllEntries().toList()) {
                entry.setSeenInShade(true);
            }
            for (NotificationEntry entry : mEntriesToRemoveAfterExpand) {
                if (isHeadsUpEntry(entry.getKey())) {
                    // Maybe the heads-up was removed already
                    removeEntry(entry.getKey(), "onExpandingFinished");
                }
            }
        }
        mEntriesToRemoveAfterExpand.clear();
    }

    /**
     * Sets the tracking-heads-up flag. If the flag is true, HeadsUpManager doesn't remove the entry
     * from the list even after a Heads Up Notification is gone.
     */
    public void setTrackingHeadsUp(boolean trackingHeadsUp) {
        mTrackingHeadsUp = trackingHeadsUp;
    }

    private void onShadeOrQsExpanded(Boolean isExpanded) {
        if (isExpanded != mIsShadeOrQsExpanded) {
            mIsShadeOrQsExpanded = isExpanded;
            if (!SceneContainerFlag.isEnabled() && isExpanded) {
                mHeadsUpAnimatingAway.setValue(false);
            }
        }
    }

    private void onQsExpanded(Boolean isQsExpanded) {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) return;
        if (isQsExpanded != mIsQsExpanded) mIsQsExpanded = isQsExpanded;
    }

    /**
     * Set that we are exiting the headsUp pinned mode, but some notifications might still be
     * animating out. This is used to keep the touchable regions in a reasonable state.
     */
    @Override
    public void setHeadsUpAnimatingAway(boolean headsUpAnimatingAway) {
        if (headsUpAnimatingAway != mHeadsUpAnimatingAway.getValue()) {
            for (OnHeadsUpPhoneListenerChange listener : mHeadsUpPhoneListeners) {
                listener.onHeadsUpAnimatingAwayStateChanged(headsUpAnimatingAway);
            }
            mHeadsUpAnimatingAway.setValue(headsUpAnimatingAway);
        }
    }

    @Override
    public void unpinAll(boolean userUnPinned) {
        super.unpinAll(userUnPinned);
    }

    /**
     * Notifies that a remote input textbox in notification gets active or inactive.
     *
     * @param entry             The entry of the target notification.
     * @param remoteInputActive True to notify active, False to notify inactive.
     */
    public void setRemoteInputActive(
            @NonNull NotificationEntry entry, boolean remoteInputActive) {
        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(entry.getKey());
        if (headsUpEntry != null && headsUpEntry.mRemoteInputActive != remoteInputActive) {
            headsUpEntry.mRemoteInputActive = remoteInputActive;
            if (ExpandHeadsUpOnInlineReply.isEnabled() && remoteInputActive) {
                headsUpEntry.mRemoteInputActivatedAtLeastOnce = true;
            }
            if (remoteInputActive) {
                headsUpEntry.cancelAutoRemovalCallbacks("setRemoteInputActive(true)");
            } else {
                headsUpEntry.updateEntry(false /* updatePostTime */, "setRemoteInputActive(false)");
            }
            onEntryUpdated(headsUpEntry);
        }
    }

    /**
     * Sets whether an entry's guts are exposed and therefore it should stick in the heads up
     * area if it's pinned until it's hidden again.
     */
    public void setGutsShown(@NonNull NotificationEntry entry, boolean gutsShown) {
        HeadsUpEntry headsUpEntry = getHeadsUpEntry(entry.getKey());
        if (!(headsUpEntry instanceof HeadsUpEntryPhone)) return;
        HeadsUpEntryPhone headsUpEntryPhone = (HeadsUpEntryPhone)headsUpEntry;
        if (entry.isRowPinned() || !gutsShown) {
            headsUpEntryPhone.setGutsShownPinned(gutsShown);
        }
    }

    /**
     * Extends the lifetime of the currently showing pulsing notification so that the pulse lasts
     * longer.
     */
    public void extendHeadsUp() {
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();
        if (topEntry == null) {
            return;
        }
        topEntry.extendPulse();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager public methods overrides and overloads:

    @Override
    public boolean isTrackingHeadsUp() {
        return mTrackingHeadsUp;
    }

    @Override
    public void snooze() {
        super.snooze();
        mReleaseOnExpandFinish = true;
    }

    public void addSwipedOutNotification(@NonNull String key) {
        mSwipedOutKeys.add(key);
    }

    @Override
    public boolean removeNotification(@NonNull String key, boolean releaseImmediately,
            boolean animate, @NonNull String reason) {
        if (animate) {
            return removeNotification(key, releaseImmediately,
                    "removeNotification(animate: true), reason: " + reason);
        } else {
            mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(false);
            final boolean removed = removeNotification(key, releaseImmediately,
                    "removeNotification(animate: false), reason: " + reason);
            mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(true);
            return removed;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Dumpable overrides:

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println("HeadsUpManagerPhone state:");
        dumpInternal(pw, args);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  OnReorderingAllowedListener:

    private final OnReorderingAllowedListener mOnReorderingAllowedListener = () -> {
        if (NotificationThrottleHun.isEnabled()) {
            mAvalancheController.setEnableAtRuntime(true);
            if (mEntriesToRemoveWhenReorderingAllowed.isEmpty()) {
                return;
            }
        }
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(false);
        for (NotificationEntry entry : mEntriesToRemoveWhenReorderingAllowed) {
            if (isHeadsUpEntry(entry.getKey())) {
                // Maybe the heads-up was removed already
                removeEntry(entry.getKey(), "allowReorder");
            }
        }
        mEntriesToRemoveWhenReorderingAllowed.clear();
        mAnimationStateHandler.setHeadsUpGoingAwayAnimationsAllowed(true);
    };

    private final OnReorderingBannedListener mOnReorderingBannedListener = () -> {
        if (mAvalancheController != null) {
            // In open shade the first HUN is pinned, and visual stability logic prevents us from
            // unpinning this first HUN as long as the shade remains open. AvalancheController only
            // shows the next HUN when the currently showing HUN is unpinned, so we must disable
            // throttling here so that the incoming HUN stream is not forever paused. This is reset
            // when reorder becomes allowed.
            mAvalancheController.setEnableAtRuntime(false);

            // Note that we cannot do the above when
            // 1) The remove runnable runs because its delay means it may not run before shade close
            // 2) Reordering is allowed again (when shade closes) because the HUN appear animation
            // will have started by then
        }
    };

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpManager utility (protected) methods overrides:

    @NonNull
    @Override
    protected HeadsUpEntry createHeadsUpEntry(NotificationEntry entry) {
        if (NotificationThrottleHun.isEnabled()) {
            return new HeadsUpEntryPhone(entry);
        } else {
            HeadsUpEntryPhone headsUpEntry = mEntryPool.acquire();
            headsUpEntry.setEntry(entry);
            return headsUpEntry;
        }
    }

    @Override
    protected void onEntryAdded(HeadsUpEntry headsUpEntry) {
        super.onEntryAdded(headsUpEntry);
        updateTopHeadsUpFlow();
        updateHeadsUpFlow();
    }

    @Override
    protected void onEntryUpdated(HeadsUpEntry headsUpEntry) {
        super.onEntryUpdated(headsUpEntry);
        // no need to update the list here
        updateTopHeadsUpFlow();
    }

    @Override
    protected void onEntryRemoved(HeadsUpEntry headsUpEntry) {
        super.onEntryRemoved(headsUpEntry);
        if (!NotificationThrottleHun.isEnabled()) {
            mEntryPool.release((HeadsUpEntryPhone) headsUpEntry);
        }
        updateTopHeadsUpFlow();
        updateHeadsUpFlow();
    }

    private void updateTopHeadsUpFlow() {
        mTopHeadsUpRow.setValue((HeadsUpRowRepository) getTopHeadsUpEntry());
    }

    private void updateHeadsUpFlow() {
        mHeadsUpNotificationRows.setValue(new HashSet<>(getHeadsUpEntryPhoneMap().values()));
    }

    @Override
    protected boolean shouldHeadsUpBecomePinned(NotificationEntry entry) {
        boolean pin = mStatusBarState == StatusBarState.SHADE && !mIsShadeOrQsExpanded;
        if (SceneContainerFlag.isEnabled()) {
            pin |= mIsQsExpanded;
        }
        if (mBypassController.getBypassEnabled()) {
            pin |= mStatusBarState == StatusBarState.KEYGUARD;
        }
        return pin || super.shouldHeadsUpBecomePinned(entry);
    }

    @Override
    protected void dumpInternal(PrintWriter pw, String[] args) {
        super.dumpInternal(pw, args);
        pw.print("  mBarState=");
        pw.println(mStatusBarState);
        pw.print("  mTouchableRegion=");
        pw.println(mTouchableRegion);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  Private utility methods:

    @NonNull
    private ArrayMap<String, HeadsUpEntryPhone> getHeadsUpEntryPhoneMap() {
        //noinspection unchecked
        return (ArrayMap<String, HeadsUpEntryPhone>) ((ArrayMap) mHeadsUpEntryMap);
    }

    @Nullable
    private HeadsUpEntryPhone getHeadsUpEntryPhone(@NonNull String key) {
        return (HeadsUpEntryPhone) mHeadsUpEntryMap.get(key);
    }

    @Nullable
    private HeadsUpEntryPhone getTopHeadsUpEntryPhone() {
        if (SceneContainerFlag.isEnabled()) {
            return (HeadsUpEntryPhone) mTopHeadsUpRow.getValue();
        } else {
            return (HeadsUpEntryPhone) getTopHeadsUpEntry();
        }
    }

    @Override
    public boolean canRemoveImmediately(@NonNull String key) {
        if (mSwipedOutKeys.contains(key)) {
            // We always instantly dismiss views being manually swiped out.
            mSwipedOutKeys.remove(key);
            return true;
        }

        HeadsUpEntryPhone headsUpEntry = getHeadsUpEntryPhone(key);
        HeadsUpEntryPhone topEntry = getTopHeadsUpEntryPhone();

        return headsUpEntry == null || headsUpEntry != topEntry || super.canRemoveImmediately(key);
    }

    @Override
    @NonNull
    public Flow<HeadsUpRowRepository> getTopHeadsUpRow() {
        return mTopHeadsUpRow;
    }

    @Override
    @NonNull
    public Flow<Set<HeadsUpRowRepository>> getActiveHeadsUpRows() {
        return mHeadsUpNotificationRows;
    }

    @Override
    @NonNull
    public StateFlow<Boolean> isHeadsUpAnimatingAway() {
        return mHeadsUpAnimatingAway;
    }

    @Override
    public boolean isHeadsUpAnimatingAwayValue() {
        return mHeadsUpAnimatingAway.getValue();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////
    //  HeadsUpEntryPhone:

    protected class HeadsUpEntryPhone extends BaseHeadsUpManager.HeadsUpEntry implements
            HeadsUpRowRepository {

        private boolean mGutsShownPinned;
        private final MutableStateFlow<Boolean> mIsPinned = StateFlowKt.MutableStateFlow(false);

        /**
         * If the time this entry has been on was extended
         */
        private boolean extended;

        @Override
        public boolean isSticky() {
            return super.isSticky() || mGutsShownPinned;
        }

        public HeadsUpEntryPhone() {
            super();
        }

        public HeadsUpEntryPhone(NotificationEntry entry) {
            super(entry);
        }

        @Override
        @NonNull
        public String getKey() {
            return requireEntry().getKey();
        }

        @Override
        @NonNull
        public StateFlow<Boolean> isPinned() {
            return mIsPinned;
        }

        @Override
        protected void setRowPinned(boolean pinned) {
            // TODO(b/327624082): replace this super call with a ViewBinder
            super.setRowPinned(pinned);
            mIsPinned.setValue(pinned);
        }

        @Override
        protected void setEntry(@androidx.annotation.NonNull NotificationEntry entry,
                @androidx.annotation.Nullable Runnable removeRunnable) {
            super.setEntry(entry, removeRunnable);

            if (NotificationThrottleHun.isEnabled()) {
                mEntriesToRemoveWhenReorderingAllowed.add(entry);
                if (!mVisualStabilityProvider.isReorderingAllowed()) {
                    entry.setSeenInShade(true);
                }
            }
        }

        @Override
        protected Runnable createRemoveRunnable(NotificationEntry entry) {
            return () -> {
                if (!NotificationThrottleHun.isEnabled()
                        && !mVisualStabilityProvider.isReorderingAllowed()
                        // We don't want to allow reordering while pulsing, but headsup need to
                        // time out anyway
                        && !entry.showingPulsing()) {
                    mEntriesToRemoveWhenReorderingAllowed.add(entry);
                    mVisualStabilityProvider.addTemporaryReorderingAllowedListener(
                            mOnReorderingAllowedListener);
                } else if (mTrackingHeadsUp) {
                    mEntriesToRemoveAfterExpand.add(entry);
                    mLogger.logRemoveEntryAfterExpand(entry);
                } else if (mVisualStabilityProvider.isReorderingAllowed()
                        || entry.showingPulsing()) {
                    removeEntry(entry.getKey(), "createRemoveRunnable");
                }
            };
        }

        @Override
        public void updateEntry(boolean updatePostTime, String reason) {
            super.updateEntry(updatePostTime, reason);

            if (mEntriesToRemoveAfterExpand.contains(mEntry)) {
                mEntriesToRemoveAfterExpand.remove(mEntry);
            }
            if (!NotificationThrottleHun.isEnabled()) {
                if (mEntriesToRemoveWhenReorderingAllowed.contains(mEntry)) {
                    mEntriesToRemoveWhenReorderingAllowed.remove(mEntry);
                }
            }
        }

        @Override
        public void setExpanded(boolean expanded) {
            if (this.mExpanded == expanded) {
                return;
            }

            this.mExpanded = expanded;
            if (expanded) {
                cancelAutoRemovalCallbacks("setExpanded(true)");
            } else {
                updateEntry(false /* updatePostTime */, "setExpanded(false)");
            }
        }

        public void setGutsShownPinned(boolean gutsShownPinned) {
            if (mGutsShownPinned == gutsShownPinned) {
                return;
            }

            mGutsShownPinned = gutsShownPinned;
            if (gutsShownPinned) {
                cancelAutoRemovalCallbacks("setGutsShownPinned(true)");
            } else {
                updateEntry(false /* updatePostTime */, "setGutsShownPinned(false)");
            }
        }

        @Override
        public void reset() {
            super.reset();
            mGutsShownPinned = false;
            extended = false;
        }

        private void extendPulse() {
            if (!extended) {
                extended = true;
                updateEntry(false, "extendPulse()");
            }
        }

        @Override
        protected long calculateFinishTime() {
            return super.calculateFinishTime() + (extended ? mExtensionTime : 0);
        }

        @Override
        @NonNull
        public Object getElementKey() {
            return requireEntry().getRow();
        }

        private NotificationEntry requireEntry() {
            /* check if */ SceneContainerFlag.isUnexpectedlyInLegacyMode();
            return Objects.requireNonNull(mEntry);
        }
    }

    private final StateListener mStatusBarStateListener = new StateListener() {
        @Override
        public void onStateChanged(int newState) {
            boolean wasKeyguard = mStatusBarState == StatusBarState.KEYGUARD;
            boolean isKeyguard = newState == StatusBarState.KEYGUARD;
            mStatusBarState = newState;

            if (wasKeyguard && !isKeyguard && mBypassController.getBypassEnabled()) {
                ArrayList<String> keysToRemove = new ArrayList<>();
                for (HeadsUpEntry entry : getHeadsUpEntryList()) {
                    if (entry.mEntry != null && entry.mEntry.isBubble() && !entry.isSticky()) {
                        keysToRemove.add(entry.mEntry.getKey());
                    }
                }
                for (String key : keysToRemove) {
                    removeEntry(key, "mStatusBarStateListener");
                }
            }
        }

        @Override
        public void onDozingChanged(boolean isDozing) {
            if (!isDozing) {
                // Let's make sure all huns we got while dozing time out within the normal timeout
                // duration. Otherwise they could get stuck for a very long time
                for (HeadsUpEntry entry : getHeadsUpEntryList()) {
                    entry.updateEntry(true /* updatePostTime */, "onDozingChanged(false)");
                }
            }
        }
    };
}
