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

package com.android.systemui.statusbar.notification.collection;

import static android.app.Notification.CATEGORY_ALARM;
import static android.app.Notification.CATEGORY_CALL;
import static android.app.Notification.CATEGORY_EVENT;
import static android.app.Notification.CATEGORY_MESSAGE;
import static android.app.Notification.CATEGORY_REMINDER;
import static android.app.Notification.FLAG_BUBBLE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;

import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_NOT_CANCELED;
import static com.android.systemui.statusbar.notification.stack.NotificationPriorityBucketKt.BUCKET_ALERTING;

import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.Notification.MessagingStyle.Message;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.app.Person;
import android.app.RemoteInput;
import android.app.RemoteInputHistoryItem;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.view.ContentInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.icon.IconPack;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.row.shared.HeadsUpStatusBarModel;
import com.android.systemui.statusbar.notification.row.shared.NotificationContentModel;
import com.android.systemui.statusbar.notification.row.shared.NotificationRowContentBinderRefactor;
import com.android.systemui.statusbar.notification.stack.PriorityBucket;
import com.android.systemui.util.ListenerSet;

import kotlinx.coroutines.flow.MutableStateFlow;
import kotlinx.coroutines.flow.StateFlow;
import kotlinx.coroutines.flow.StateFlowKt;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a notification that the system UI knows about
 *
 * Whenever the NotificationManager tells us about the existence of a new notification, we wrap it
 * in a NotificationEntry. Thus, every notification has an associated NotificationEntry, even if
 * that notification is never displayed to the user (for example, if it's filtered out for some
 * reason).
 *
 * Entries store information about the current state of the notification. Essentially:
 * anything that needs to persist or be modifiable even when the notification's views don't
 * exist. Any other state should be stored on the views/view controllers themselves.
 *
 * At the moment, there are many things here that shouldn't be and vice-versa. Hopefully we can
 * clean this up in the future.
 */
public final class NotificationEntry extends ListEntry {

    private final String mKey;
    private StatusBarNotification mSbn;
    private Ranking mRanking;

    /*
     * Bookkeeping members
     */

    /** List of lifetime extenders that are extending the lifetime of this notification. */
    final List<NotifLifetimeExtender> mLifetimeExtenders = new ArrayList<>();

    /** List of dismiss interceptors that are intercepting the dismissal of this notification. */
    final List<NotifDismissInterceptor> mDismissInterceptors = new ArrayList<>();

    /**
     * If this notification was cancelled by system server, then the reason that was supplied.
     * Uncancelled notifications always have REASON_NOT_CANCELED. Note that lifetime-extended
     * notifications will have this set even though they are still in the active notification set.
     */
    @CancellationReason int mCancellationReason = REASON_NOT_CANCELED;

    /** @see #getDismissState() */
    @NonNull private DismissState mDismissState = DismissState.NOT_DISMISSED;

    /*
    * Old members
    * TODO: Remove every member beneath this line if possible
    */

    private IconPack mIcons = IconPack.buildEmptyPack(null);
    private boolean interruption;
    public int targetSdk;
    private long lastFullScreenIntentLaunchTime = NOT_LAUNCHED_YET;
    public CharSequence remoteInputText;
    public List<RemoteInputHistoryItem> remoteInputs = null;
    public String remoteInputMimeType;
    public Uri remoteInputUri;
    public ContentInfo remoteInputAttachment;
    private Notification.BubbleMetadata mBubbleMetadata;

    /**
     * If {@link RemoteInput#getEditChoicesBeforeSending} is enabled, and the user is
     * currently editing a choice (smart reply), then this field contains the information about the
     * suggestion being edited. Otherwise <code>null</code>.
     */
    public EditedSuggestionInfo editedSuggestionInfo;

    private ExpandableNotificationRow row; // the outer expanded view
    private ExpandableNotificationRowController mRowController;

    private int mCachedContrastColor = COLOR_INVALID;
    private int mCachedContrastColorIsFor = COLOR_INVALID;
    private InflationTask mRunningTask = null;
    public CharSequence remoteInputTextWhenReset;
    public long lastRemoteInputSent = NOT_LAUNCHED_YET;

    private final MutableStateFlow<CharSequence> mHeadsUpStatusBarText =
            StateFlowKt.MutableStateFlow(null);
    private final MutableStateFlow<CharSequence> mHeadsUpStatusBarTextPublic =
            StateFlowKt.MutableStateFlow(null);

    // indicates when this entry's view was first attached to a window
    // this value will reset when the view is completely removed from the shade (ie: filtered out)
    private long initializationTime = -1;

    /**
     * Has the user sent a reply through this Notification.
     */
    private boolean hasSentReply;

    private final MutableStateFlow<Boolean> mSensitive = StateFlowKt.MutableStateFlow(true);
    private final ListenerSet<OnSensitivityChangedListener> mOnSensitivityChangedListeners =
            new ListenerSet<>();

    private boolean mPulseSupressed;
    private int mBucket = BUCKET_ALERTING;
    private boolean mIsMarkedForUserTriggeredMovement;
    private boolean mIsHeadsUpEntry;

    private boolean mHasEverBeenGroupSummary;
    private boolean mHasEverBeenGroupChild;

    public boolean mRemoteEditImeAnimatingAway;
    public boolean mRemoteEditImeVisible;
    private boolean mExpandAnimationRunning;
    /**
     * Flag to determine if the entry is blockable by DnD filters
     */
    private boolean mBlockable;

    /**
     * Whether this notification has ever been a non-sticky HUN.
     */
    private boolean mIsDemoted = false;

    /**
     * True if both
     *  1) app provided full screen intent but does not have the permission to send it
     *  2) this notification has never been demoted before
     */
    public boolean isStickyAndNotDemoted() {

        final boolean fsiRequestedButDenied =  (getSbn().getNotification().flags
                & Notification.FLAG_FSI_REQUESTED_BUT_DENIED) != 0;

        if (!fsiRequestedButDenied && !mIsDemoted) {
            demoteStickyHun();
        }
        return fsiRequestedButDenied && !mIsDemoted;
    }

    @VisibleForTesting
    public boolean isDemoted() {
        return mIsDemoted;
    }

    /**
     * Make sticky HUN not sticky.
     */
    public void demoteStickyHun() {
        mIsDemoted = true;
    }

    /** called when entry is currently a summary of a group */
    public void markAsGroupSummary() {
        mHasEverBeenGroupSummary = true;
    }

    /** whether this entry has ever been marked as a summary */
    public boolean hasEverBeenGroupSummary() {
        return mHasEverBeenGroupSummary;
    }

    /** called when entry is currently a child in a group */
    public void markAsGroupChild() {
        mHasEverBeenGroupChild = true;
    }

    /** whether this entry has ever been marked as a child */
    public boolean hasEverBeenGroupChild() {
        return mHasEverBeenGroupChild;
    }

    /**
     * @param sbn the StatusBarNotification from system server
     * @param ranking also from system server
     * @param creationTime SystemClock.uptimeMillis of when we were created
     */
    public NotificationEntry(
            @NonNull StatusBarNotification sbn,
            @NonNull Ranking ranking,
            long creationTime
    ) {
        super(requireNonNull(requireNonNull(sbn).getKey()), creationTime);

        requireNonNull(ranking);

        mKey = sbn.getKey();
        setSbn(sbn);
        setRanking(ranking);
    }

    @Override
    public NotificationEntry getRepresentativeEntry() {
        return this;
    }

    /** The key for this notification. Guaranteed to be immutable and unique */
    @NonNull public String getKey() {
        return mKey;
    }

    /**
     * The StatusBarNotification that represents one half of a NotificationEntry (the other half
     * being the Ranking). This object is swapped out whenever a notification is updated.
     */
    @NonNull public StatusBarNotification getSbn() {
        return mSbn;
    }

    /**
     * Should only be called by NotificationEntryManager and friends.
     * TODO: Make this package-private
     */
    public void setSbn(@NonNull StatusBarNotification sbn) {
        requireNonNull(sbn);
        requireNonNull(sbn.getKey());

        if (!sbn.getKey().equals(mKey)) {
            throw new IllegalArgumentException("New key " + sbn.getKey()
                    + " doesn't match existing key " + mKey);
        }

        mSbn = sbn;
        mBubbleMetadata = mSbn.getNotification().getBubbleMetadata();
    }

    /**
     * The Ranking that represents one half of a NotificationEntry (the other half being the
     * StatusBarNotification). This object is swapped out whenever a the ranking is updated (which
     * generally occurs whenever anything changes in the notification list).
     */
    public Ranking getRanking() {
        return mRanking;
    }

    /**
     * Should only be called by NotificationEntryManager and friends.
     * TODO: Make this package-private
     */
    public void setRanking(@NonNull Ranking ranking) {
        requireNonNull(ranking);
        requireNonNull(ranking.getKey());

        if (!ranking.getKey().equals(mKey)) {
            throw new IllegalArgumentException("New key " + ranking.getKey()
                    + " doesn't match existing key " + mKey);
        }

        mRanking = ranking.withAudiblyAlertedInfo(mRanking);
        updateIsBlockable();
    }

    /*
     * Bookkeeping getters and setters
     */

    /**
     * Set if the user has dismissed this notif but we haven't yet heard back from system server to
     * confirm the dismissal.
     */
    @NonNull public DismissState getDismissState() {
        return mDismissState;
    }

    void setDismissState(@NonNull DismissState dismissState) {
        mDismissState = requireNonNull(dismissState);
    }

    /**
     * True if the notification has been canceled by system server. Usually, such notifications are
     * immediately removed from the collection, but can sometimes stick around due to lifetime
     * extenders.
     */
    public boolean isCanceled() {
        return mCancellationReason != REASON_NOT_CANCELED;
    }

    @Nullable public NotifFilter getExcludingFilter() {
        return getAttachState().getExcludingFilter();
    }

    @Nullable public NotifPromoter getNotifPromoter() {
        return getAttachState().getPromoter();
    }

    /*
     * Convenience getters for SBN and Ranking members
     */

    public NotificationChannel getChannel() {
        return mRanking.getChannel();
    }

    public long getLastAudiblyAlertedMs() {
        return mRanking.getLastAudiblyAlertedMillis();
    }

    public boolean isAmbient() {
        return mRanking.isAmbient();
    }

    public int getImportance() {
        return mRanking.getImportance();
    }

    public List<SnoozeCriterion> getSnoozeCriteria() {
        return mRanking.getSnoozeCriteria();
    }

    public int getUserSentiment() {
        return mRanking.getUserSentiment();
    }

    public int getSuppressedVisualEffects() {
        return mRanking.getSuppressedVisualEffects();
    }

    /** @see Ranking#canBubble() */
    public boolean canBubble() {
        return mRanking.canBubble();
    }

    public @NonNull List<Notification.Action> getSmartActions() {
        return mRanking.getSmartActions();
    }

    public @NonNull List<CharSequence> getSmartReplies() {
        return mRanking.getSmartReplies();
    }


    /*
     * Old methods
     *
     * TODO: Remove as many of these as possible
     */

    @NonNull
    public IconPack getIcons() {
        return mIcons;
    }

    public void setIcons(@NonNull IconPack icons) {
        mIcons = icons;
    }

    public void setInterruption() {
        interruption = true;
    }

    public boolean hasInterrupted() {
        return interruption;
    }

    public boolean isBubble() {
        return (mSbn.getNotification().flags & FLAG_BUBBLE) != 0;
    }

    /**
     * Returns the data needed for a bubble for this notification, if it exists.
     */
    @Nullable
    public Notification.BubbleMetadata getBubbleMetadata() {
        return mBubbleMetadata;
    }

    /**
     * Sets bubble metadata for this notification.
     */
    public void setBubbleMetadata(@Nullable Notification.BubbleMetadata metadata) {
        mBubbleMetadata = metadata;
    }

    /**
     * Updates the {@link Notification#FLAG_BUBBLE} flag on this notification to indicate
     * whether it is a bubble or not. If this entry is set to not bubble, or does not have
     * the required info to bubble, the flag cannot be set to true.
     *
     * @param shouldBubble whether this notification should be flagged as a bubble.
     * @return true if the value changed.
     */
    public boolean setFlagBubble(boolean shouldBubble) {
        boolean wasBubble = isBubble();
        if (!shouldBubble) {
            mSbn.getNotification().flags &= ~FLAG_BUBBLE;
        } else if (mBubbleMetadata != null && canBubble()) {
            // wants to be bubble & can bubble, set flag
            mSbn.getNotification().flags |= FLAG_BUBBLE;
        }
        return wasBubble != isBubble();
    }

    @PriorityBucket
    public int getBucket() {
        return mBucket;
    }

    public void setBucket(@PriorityBucket int bucket) {
        mBucket = bucket;
    }

    public ExpandableNotificationRow getRow() {
        return row;
    }

    //TODO: This will go away when we have a way to bind an entry to a row
    public void setRow(ExpandableNotificationRow row) {
        this.row = row;
    }

    public ExpandableNotificationRowController getRowController() {
        return mRowController;
    }

    public void setRowController(ExpandableNotificationRowController controller) {
        mRowController = controller;
    }

    /**
     * Get the children that are actually attached to this notification's row.
     *
     * TODO: Seems like most callers here should probably be using
     * {@link GroupMembershipManager#getChildren(ListEntry)}
     */
    public @Nullable List<NotificationEntry> getAttachedNotifChildren() {
        if (row == null) {
            return null;
        }

        List<ExpandableNotificationRow> rowChildren = row.getAttachedChildren();
        if (rowChildren == null) {
            return null;
        }

        ArrayList<NotificationEntry> children = new ArrayList<>();
        for (ExpandableNotificationRow child : rowChildren) {
            children.add(child.getEntry());
        }

        return children;
    }

    public void notifyFullScreenIntentLaunched() {
        setInterruption();
        lastFullScreenIntentLaunchTime = SystemClock.elapsedRealtime();
    }

    public boolean hasJustLaunchedFullScreenIntent() {
        return SystemClock.elapsedRealtime() < lastFullScreenIntentLaunchTime + LAUNCH_COOLDOWN;
    }

    public boolean hasJustSentRemoteInput() {
        return SystemClock.elapsedRealtime() < lastRemoteInputSent + REMOTE_INPUT_COOLDOWN;
    }

    public boolean hasFinishedInitialization() {
        return initializationTime != -1
                && SystemClock.elapsedRealtime() > initializationTime + INITIALIZATION_DELAY;
    }

    public int getContrastedColor(Context context, boolean isLowPriority,
            int backgroundColor) {
        int rawColor = isLowPriority ? Notification.COLOR_DEFAULT :
                mSbn.getNotification().color;
        if (mCachedContrastColorIsFor == rawColor && mCachedContrastColor != COLOR_INVALID) {
            return mCachedContrastColor;
        }
        final int contrasted = ContrastColorUtil.resolveContrastColor(context, rawColor,
                backgroundColor);
        mCachedContrastColorIsFor = rawColor;
        mCachedContrastColor = contrasted;
        return mCachedContrastColor;
    }

    /**
     * Abort all existing inflation tasks
     */
    public boolean abortTask() {
        if (mRunningTask != null) {
            mRunningTask.abort();
            mRunningTask = null;
            return true;
        }
        return false;
    }

    public void setInflationTask(InflationTask abortableTask) {
        // abort any existing inflation
        abortTask();
        mRunningTask = abortableTask;
    }

    public void onInflationTaskFinished() {
        mRunningTask = null;
    }

    @VisibleForTesting
    public InflationTask getRunningTask() {
        return mRunningTask;
    }

    public void onRemoteInputInserted() {
        lastRemoteInputSent = NOT_LAUNCHED_YET;
        remoteInputTextWhenReset = null;
    }

    public void setHasSentReply() {
        hasSentReply = true;
    }

    public boolean isLastMessageFromReply() {
        if (!hasSentReply) {
            return false;
        }
        Bundle extras = mSbn.getNotification().extras;
        Parcelable[] replyTexts =
                extras.getParcelableArray(Notification.EXTRA_REMOTE_INPUT_HISTORY_ITEMS);
        if (!ArrayUtils.isEmpty(replyTexts)) {
            return true;
        }
        List<Message> messages = Message.getMessagesFromBundleArray(
                extras.getParcelableArray(Notification.EXTRA_MESSAGES));
        if (messages != null && !messages.isEmpty()) {
            Message lastMessage = messages.get(messages.size() -1);

            if (lastMessage != null) {
                Person senderPerson = lastMessage.getSenderPerson();
                if (senderPerson == null) {
                    return true;
                }
                Person user = extras.getParcelable(
                        Notification.EXTRA_MESSAGING_PERSON, Person.class);
                return Objects.equals(user, senderPerson);
            }
        }
        return false;
    }

    public void resetInitializationTime() {
        initializationTime = -1;
    }

    public void setInitializationTime(long time) {
        if (initializationTime == -1) {
            initializationTime = time;
        }
    }

    public void sendAccessibilityEvent(int eventType) {
        if (row != null) {
            row.sendAccessibilityEvent(eventType);
        }
    }

    /**
     * Used by NotificationMediaManager to determine... things
     * @return {@code true} if we are a media notification
     */
    public boolean isMediaNotification() {
        if (row == null) return false;

        return row.isMediaRow();
    }

    public void resetUserExpansion() {
        if (row != null) row.resetUserExpansion();
    }

    public boolean rowExists() {
        return row != null;
    }

    public boolean isRowDismissed() {
        return row != null && row.isDismissed();
    }

    public boolean isRowRemoved() {
        return row != null && row.isRemoved();
    }

    /**
     * @return {@code true} if the row is null or removed
     */
    public boolean isRemoved() {
        //TODO: recycling invalidates this
        return row == null || row.isRemoved();
    }

    public boolean isRowPinned() {
        return row != null && row.isPinned();
    }

    /**
     * Is this entry pinned and was expanded while doing so
     */
    public boolean isPinnedAndExpanded() {
        return row != null && row.isPinnedAndExpanded();
    }

    public void setRowPinned(boolean pinned) {
        if (row != null) row.setPinned(pinned);
    }

    public boolean isRowHeadsUp() {
        return row != null && row.isHeadsUp();
    }

    public boolean showingPulsing() {
        return row != null && row.showingPulsing();
    }

    public void setHeadsUp(boolean shouldHeadsUp) {
        if (row != null) row.setHeadsUp(shouldHeadsUp);
    }

    public void setHeadsUpAnimatingAway(boolean animatingAway) {
        if (row != null) row.setHeadsUpAnimatingAway(animatingAway);
    }

    public boolean mustStayOnScreen() {
        return row != null && row.mustStayOnScreen();
    }

    public void setHeadsUpIsVisible() {
        if (row != null) row.setHeadsUpIsVisible();
    }

    //TODO: i'm imagining a world where this isn't just the row, but I could be rwong
    public ExpandableNotificationRow getHeadsUpAnimationView() {
        return row;
    }

    public void setUserLocked(boolean userLocked) {
        if (row != null) row.setUserLocked(userLocked);
    }

    public void setUserExpanded(boolean userExpanded, boolean allowChildExpansion) {
        if (row != null) row.setUserExpanded(userExpanded, allowChildExpansion);
    }

    public void setGroupExpansionChanging(boolean changing) {
        if (row != null) row.setGroupExpansionChanging(changing);
    }

    public void notifyHeightChanged(boolean needsAnimation) {
        if (row != null) row.notifyHeightChanged(needsAnimation);
    }

    public void closeRemoteInput() {
        if (row != null) row.closeRemoteInput();
    }

    public boolean areChildrenExpanded() {
        return row != null && row.areChildrenExpanded();
    }

    public NotificationGuts getGuts() {
        if (row != null) return row.getGuts();
        return null;
    }

    public void removeRow() {
        if (row != null) row.setRemoved();
    }

    public boolean isSummaryWithChildren() {
        return row != null && row.isSummaryWithChildren();
    }

    public void onDensityOrFontScaleChanged() {
        if (row != null) row.onDensityOrFontScaleChanged();
    }

    public boolean areGutsExposed() {
        return row != null && row.getGuts() != null && row.getGuts().isExposed();
    }

    /**
     * @return Whether the notification row is a child of a group notification view; false if the
     * row is null
     */
    public boolean rowIsChildInGroup() {
        return row != null && row.isChildInGroup();
    }

    /**
     * @return Can the underlying notification be cleared? This can be different from whether the
     *         notification can be dismissed in case notifications are sensitive on the lockscreen.
     */
    // TODO: This logic doesn't belong on NotificationEntry. It should be moved to a controller
    // that can be added as a dependency to any class that needs to answer this question.
    public boolean isClearable() {
        if (!mSbn.isClearable()) {
            return false;
        }

        List<NotificationEntry> children = getAttachedNotifChildren();
        if (children != null && children.size() > 0) {
            for (int i = 0; i < children.size(); i++) {
                NotificationEntry child =  children.get(i);
                if (!child.getSbn().isClearable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Determines whether the NotificationEntry is dismissable based on the Notification flags and
     * the given state. It doesn't recurse children or depend on the view attach state.
     *
     * @param isLocked if the device is locked or unlocked
     * @return true if this NotificationEntry is dismissable.
     */
    public boolean isDismissableForState(boolean isLocked) {
        if (mSbn.isNonDismissable()) {
            // don't dismiss exempted Notifications
            return false;
        }
        // don't dismiss ongoing Notifications when the device is locked
        return !mSbn.isOngoing() || !isLocked;
    }

    public boolean canViewBeDismissed() {
        if (row == null) return true;
        return row.canViewBeDismissed();
    }

    @VisibleForTesting
    boolean isExemptFromDndVisualSuppression() {
        if (isNotificationBlockedByPolicy(mSbn.getNotification())) {
            return false;
        }

        if (mSbn.getNotification().isFgsOrUij()) {
            return true;
        }
        if (mSbn.getNotification().isMediaNotification()) {
            return true;
        }
        if (!isBlockable()) {
            return true;
        }
        return false;
    }

    /**
     * Returns whether this row is considered blockable (i.e. it's not a system notif
     * or is not in an allowList).
     */
    public boolean isBlockable() {
        return mBlockable;
    }

    private void updateIsBlockable() {
        if (getChannel() == null) {
            mBlockable = false;
            return;
        }
        if (getChannel().isImportanceLockedByCriticalDeviceFunction()
                && !getChannel().isBlockable()) {
            mBlockable = false;
            return;
        }
        mBlockable = true;
    }

    private boolean shouldSuppressVisualEffect(int effect) {
        if (isExemptFromDndVisualSuppression()) {
            return false;
        }
        return (getSuppressedVisualEffects() & effect) != 0;
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_FULL_SCREEN_INTENT}
     * is set for this entry.
     */
    public boolean shouldSuppressFullScreenIntent() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_PEEK}
     * is set for this entry.
     */
    public boolean shouldSuppressPeek() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_PEEK);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_STATUS_BAR}
     * is set for this entry.
     */
    public boolean shouldSuppressStatusBar() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_STATUS_BAR);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_AMBIENT}
     * is set for this entry.
     */
    public boolean shouldSuppressAmbient() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_AMBIENT);
    }

    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_NOTIFICATION_LIST}
     * is set for this entry.
     */
    public boolean shouldSuppressNotificationList() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_NOTIFICATION_LIST);
    }


    /**
     * Returns whether {@link Policy#SUPPRESSED_EFFECT_BADGE}
     * is set for this entry. This badge is not an app badge, but rather an indicator of "unseen"
     * content. Typically this is referred to as a "dot" internally in Launcher & SysUI code.
     */
    public boolean shouldSuppressNotificationDot() {
        return shouldSuppressVisualEffect(SUPPRESSED_EFFECT_BADGE);
    }

    /**
     * Categories that are explicitly called out on DND settings screens are always blocked, if
     * DND has flagged them, even if they are foreground or system notifications that might
     * otherwise visually bypass DND.
     */
    private static boolean isNotificationBlockedByPolicy(Notification n) {
        return isCategory(CATEGORY_CALL, n)
                || isCategory(CATEGORY_MESSAGE, n)
                || isCategory(CATEGORY_ALARM, n)
                || isCategory(CATEGORY_EVENT, n)
                || isCategory(CATEGORY_REMINDER, n);
    }

    private static boolean isCategory(String category, Notification n) {
        return Objects.equals(n.category, category);
    }

    /** @see #setSensitive(boolean, boolean)  */
    public StateFlow<Boolean> isSensitive() {
        return mSensitive;
    }

    /**
     * Set this notification to be sensitive.
     *
     * @param sensitive true if the content of this notification is sensitive right now
     * @param deviceSensitive true if the device in general is sensitive right now
     */
    public void setSensitive(boolean sensitive, boolean deviceSensitive) {
        getRow().setSensitive(sensitive, deviceSensitive);
        if (sensitive != mSensitive.getValue()) {
            mSensitive.setValue(sensitive);
            for (NotificationEntry.OnSensitivityChangedListener listener :
                    mOnSensitivityChangedListeners) {
                listener.onSensitivityChanged(this);
            }
        }
    }

    /** Add a listener to be notified when the entry's sensitivity changes. */
    public void addOnSensitivityChangedListener(OnSensitivityChangedListener listener) {
        mOnSensitivityChangedListeners.addIfAbsent(listener);
    }

    /** Remove a listener that was registered above. */
    public void removeOnSensitivityChangedListener(OnSensitivityChangedListener listener) {
        mOnSensitivityChangedListeners.remove(listener);
    }

    /** @see #setHeadsUpStatusBarText(CharSequence) */
    public StateFlow<CharSequence> getHeadsUpStatusBarText() {
        return mHeadsUpStatusBarText;
    }

    /**
     * Sets the text to be displayed on the StatusBar, when this notification is the top pinned
     * heads up.
     */
    public void setHeadsUpStatusBarText(CharSequence headsUpStatusBarText) {
        NotificationRowContentBinderRefactor.assertInLegacyMode();
        this.mHeadsUpStatusBarText.setValue(headsUpStatusBarText);
    }

    /** @see #setHeadsUpStatusBarTextPublic(CharSequence) */
    public StateFlow<CharSequence> getHeadsUpStatusBarTextPublic() {
        return mHeadsUpStatusBarTextPublic;
    }

    /**
     * Sets the text to be displayed on the StatusBar, when this notification is the top pinned
     * heads up, and its content is sensitive right now.
     */
    public void setHeadsUpStatusBarTextPublic(CharSequence headsUpStatusBarTextPublic) {
        NotificationRowContentBinderRefactor.assertInLegacyMode();
        this.mHeadsUpStatusBarTextPublic.setValue(headsUpStatusBarTextPublic);
    }

    public boolean isPulseSuppressed() {
        return mPulseSupressed;
    }

    public void setPulseSuppressed(boolean suppressed) {
        mPulseSupressed = suppressed;
    }

    /** Whether or not this entry has been marked for a user-triggered movement. */
    public boolean isMarkedForUserTriggeredMovement() {
        return mIsMarkedForUserTriggeredMovement;
    }

    /**
     * Mark this entry for movement triggered by a user action (ex: changing the priorirty of a
     * conversation). This can then be used for custom animations.
     */
    public void markForUserTriggeredMovement(boolean marked) {
        mIsMarkedForUserTriggeredMovement = marked;
    }

    public void setIsHeadsUpEntry(boolean isHeadsUpEntry) {
        mIsHeadsUpEntry = isHeadsUpEntry;
    }

    public boolean isHeadsUpEntry() {
        return mIsHeadsUpEntry;
    }

    /** Set whether this notification is currently used to animate a launch. */
    public void setExpandAnimationRunning(boolean expandAnimationRunning) {
        mExpandAnimationRunning = expandAnimationRunning;
    }

    /** Whether this notification is currently used to animate a launch. */
    public boolean isExpandAnimationRunning() {
        return mExpandAnimationRunning;
    }

    /**
     * @return NotificationStyle
     */
    public String getNotificationStyle() {
        if (isSummaryWithChildren()) {
            return "summary";
        }

        final Class<? extends Notification.Style> style =
                getSbn().getNotification().getNotificationStyle();
        return style == null ? "nostyle" : style.getSimpleName();
    }

    /**
     * Return {@code true} if notification's visibility is {@link Notification.VISIBILITY_PRIVATE}
     */
    public boolean isNotificationVisibilityPrivate() {
        return getSbn().getNotification().visibility == Notification.VISIBILITY_PRIVATE;
    }

    /**
     * Return {@code true} if notification's channel lockscreen visibility is
     * {@link Notification.VISIBILITY_PRIVATE}
     */
    public boolean isChannelVisibilityPrivate() {
        return getRanking().getChannel() != null
                && getRanking().getChannel().getLockscreenVisibility()
                == Notification.VISIBILITY_PRIVATE;
    }

    /** Set the content generated by the notification inflater. */
    public void setContentModel(NotificationContentModel contentModel) {
        if (NotificationRowContentBinderRefactor.isUnexpectedlyInLegacyMode()) return;
        HeadsUpStatusBarModel headsUpStatusBarModel = contentModel.getHeadsUpStatusBarModel();
        this.mHeadsUpStatusBarText.setValue(headsUpStatusBarModel.getPrivateText());
        this.mHeadsUpStatusBarTextPublic.setValue(headsUpStatusBarModel.getPublicText());
    }

    /** Information about a suggestion that is being edited. */
    public static class EditedSuggestionInfo {

        /**
         * The value of the suggestion (before any user edits).
         */
        public final CharSequence originalText;

        /**
         * The index of the suggestion that is being edited.
         */
        public final int index;

        public EditedSuggestionInfo(CharSequence originalText, int index) {
            this.originalText = originalText;
            this.index = index;
        }
    }

    /** Listener interface for {@link #addOnSensitivityChangedListener} */
    public interface OnSensitivityChangedListener {
        /** Called when the sensitivity changes */
        void onSensitivityChanged(@NonNull NotificationEntry entry);
    }

    /** @see #getDismissState() */
    public enum DismissState {
        /** User has not dismissed this notif or its parent */
        NOT_DISMISSED,
        /** User has dismissed this notif specifically */
        DISMISSED,
        /** User has dismissed this notif's parent (which implicitly dismisses this one as well) */
        PARENT_DISMISSED,
    }

    private static final long LAUNCH_COOLDOWN = 2000;
    private static final long REMOTE_INPUT_COOLDOWN = 500;
    private static final long INITIALIZATION_DELAY = 400;
    private static final long NOT_LAUNCHED_YET = -LAUNCH_COOLDOWN;
    private static final int COLOR_INVALID = 1;
}
