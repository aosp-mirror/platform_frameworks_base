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
import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;

import static com.android.systemui.statusbar.notification.collection.NotifCollection.REASON_NOT_CANCELED;
import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManagerKt.BUCKET_ALERTING;

import static java.util.Objects.requireNonNull;

import android.app.Notification;
import android.app.Notification.MessagingStyle.Message;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.ContentInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifDismissInterceptor;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender;
import com.android.systemui.statusbar.notification.icon.IconPack;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.stack.PriorityBucket;

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
    // Mimetype and Uri used to display the image in the notification *after* it has been sent.
    public String remoteInputMimeType;
    public Uri remoteInputUri;
    // ContentInfo used to keep the attachment permission alive until RemoteInput is sent or
    // cancelled.
    public ContentInfo remoteInputAttachment;
    private Notification.BubbleMetadata mBubbleMetadata;
    private ShortcutInfo mShortcutInfo;

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
    private Throwable mDebugThrowable;
    public CharSequence remoteInputTextWhenReset;
    public long lastRemoteInputSent = NOT_LAUNCHED_YET;
    public final ArraySet<Integer> mActiveAppOps = new ArraySet<>(3);
    public CharSequence headsUpStatusBarText;
    public CharSequence headsUpStatusBarTextPublic;

    // indicates when this entry's view was first attached to a window
    // this value will reset when the view is completely removed from the shade (ie: filtered out)
    private long initializationTime = -1;

    /**
     * Whether or not this row represents a system notification. Note that if this is
     * {@code null}, that means we were either unable to retrieve the info or have yet to
     * retrieve the info.
     */
    public Boolean mIsSystemNotification;

    /**
     * Has the user sent a reply through this Notification.
     */
    private boolean hasSentReply;

    private boolean mSensitive = true;
    private List<OnSensitivityChangedListener> mOnSensitivityChangedListeners = new ArrayList<>();

    private boolean mAutoHeadsUp;
    private boolean mPulseSupressed;
    private boolean mAllowFgsDismissal;
    private int mBucket = BUCKET_ALERTING;
    @Nullable private Long mPendingAnimationDuration;
    private boolean mIsMarkedForUserTriggeredMovement;
    private boolean mIsAlerting;

    public boolean mRemoteEditImeAnimatingAway;
    public boolean mRemoteEditImeVisible;
    private boolean mExpandAnimationRunning;

    /**
     * @param sbn the StatusBarNotification from system server
     * @param ranking also from system server
     * @param creationTime SystemClock.uptimeMillis of when we were created
     */
    public NotificationEntry(
            @NonNull StatusBarNotification sbn,
            @NonNull Ranking ranking,
            long creationTime) {
        this(sbn, ranking, false, creationTime);
    }

    public NotificationEntry(
            @NonNull StatusBarNotification sbn,
            @NonNull Ranking ranking,
            boolean allowFgsDismissal,
            long creationTime
    ) {
        super(requireNonNull(requireNonNull(sbn).getKey()), creationTime);

        requireNonNull(ranking);

        mKey = sbn.getKey();
        setSbn(sbn);
        setRanking(ranking);

        mAllowFgsDismissal = allowFgsDismissal;
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
     * {@link NotificationGroupManagerLegacy#getChildren}
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
    public void abortTask() {
        if (mRunningTask != null) {
            mRunningTask.abort();
            mRunningTask = null;
        }
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

    /**
     * Set a throwable that is used for debugging
     *
     * @param debugThrowable the throwable to save
     */
    public void setDebugThrowable(Throwable debugThrowable) {
        mDebugThrowable = debugThrowable;
    }

    public Throwable getDebugThrowable() {
        return mDebugThrowable;
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
                Person user = extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
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

    /**
     * We are a top level child if our parent is the list of notifications duh
     * @return {@code true} if we're a top level notification
     */
    public boolean isTopLevelChild() {
        return row != null && row.isTopLevelChild();
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

    /**
     * Set that this notification was automatically heads upped. This happens for example when
     * the user bypasses the lockscreen and media is playing.
     */
    public void setAutoHeadsUp(boolean autoHeadsUp) {
        mAutoHeadsUp = autoHeadsUp;
    }

    /**
     * @return if this notification was automatically heads upped. This happens for example when
     *      * the user bypasses the lockscreen and media is playing.
     */
    public boolean isAutoHeadsUp() {
        return mAutoHeadsUp;
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

    public boolean keepInParent() {
        return row != null && row.keepInParent();
    }

    //TODO: probably less confusing to say "is group fully visible"
    public boolean isGroupNotFullyVisible() {
        return row == null || row.isGroupNotFullyVisible();
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

    public void setKeepInParent(boolean keep) {
        if (row != null) row.setKeepInParent(keep);
    }

    public void onDensityOrFontScaleChanged() {
        if (row != null) row.onDensityOrFontScaleChanged();
    }

    public boolean areGutsExposed() {
        return row != null && row.getGuts() != null && row.getGuts().isExposed();
    }

    public boolean isChildInGroup() {
        return row != null && row.isChildInGroup();
    }

    /**
     * @return Can the underlying notification be cleared? This can be different from whether the
     *         notification can be dismissed in case notifications are sensitive on the lockscreen.
     * @see #canViewBeDismissed()
     */
    // TOOD: This logic doesn't belong on NotificationEntry. It should be moved to the
    // ForegroundsServiceDismissalFeatureController or some other controller that can be added
    // as a dependency to any class that needs to answer this question.
    public boolean isClearable() {
        if (!isDismissable()) {
            return false;
        }

        List<NotificationEntry> children = getAttachedNotifChildren();
        if (children != null && children.size() > 0) {
            for (int i = 0; i < children.size(); i++) {
                NotificationEntry child =  children.get(i);
                if (!child.isDismissable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Notifications might have any combination of flags:
     * - FLAG_ONGOING_EVENT
     * - FLAG_NO_CLEAR
     * - FLAG_FOREGROUND_SERVICE
     *
     * We want to allow dismissal of notifications that represent foreground services, which may
     * have all 3 flags set. If we only find NO_CLEAR though, we don't want to allow dismissal
     */
    private boolean isDismissable() {
        boolean ongoing = ((mSbn.getNotification().flags & Notification.FLAG_ONGOING_EVENT) != 0);
        boolean noclear = ((mSbn.getNotification().flags & Notification.FLAG_NO_CLEAR) != 0);
        boolean fgs = ((mSbn.getNotification().flags & FLAG_FOREGROUND_SERVICE) != 0);

        if (mAllowFgsDismissal) {
            if (noclear && !ongoing && !fgs) {
                return false;
            }
            return true;
        } else {
            return mSbn.isClearable();
        }

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

        if ((mSbn.getNotification().flags
                & FLAG_FOREGROUND_SERVICE) != 0) {
            return true;
        }
        if (mSbn.getNotification().isMediaNotification()) {
            return true;
        }
        if (mIsSystemNotification != null && mIsSystemNotification) {
            return true;
        }
        return false;
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

    /**
     * Whether or not this row represents a system notification. Note that if this is
     * {@code null}, that means we were either unable to retrieve the info or have yet to
     * retrieve the info.
     */
    public Boolean isSystemNotification() {
        return mIsSystemNotification;
    }

    /**
     * Set this notification to be sensitive.
     *
     * @param sensitive true if the content of this notification is sensitive right now
     * @param deviceSensitive true if the device in general is sensitive right now
     */
    public void setSensitive(boolean sensitive, boolean deviceSensitive) {
        getRow().setSensitive(sensitive, deviceSensitive);
        if (sensitive != mSensitive) {
            mSensitive = sensitive;
            for (int i = 0; i < mOnSensitivityChangedListeners.size(); i++) {
                mOnSensitivityChangedListeners.get(i).onSensitivityChanged(this);
            }
        }
    }

    public boolean isSensitive() {
        return mSensitive;
    }

    /** Add a listener to be notified when the entry's sensitivity changes. */
    public void addOnSensitivityChangedListener(OnSensitivityChangedListener listener) {
        mOnSensitivityChangedListeners.add(listener);
    }

    /** Remove a listener that was registered above. */
    public void removeOnSensitivityChangedListener(OnSensitivityChangedListener listener) {
        mOnSensitivityChangedListeners.remove(listener);
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

    public void setIsAlerting(boolean isAlerting) {
        mIsAlerting = isAlerting;
    }

    public boolean isAlerting() {
        return mIsAlerting;
    }

    /** Set whether this notification is currently used to animate a launch. */
    public void setExpandAnimationRunning(boolean expandAnimationRunning) {
        mExpandAnimationRunning = expandAnimationRunning;
    }

    /** Whether this notification is currently used to animate a launch. */
    public boolean isExpandAnimationRunning() {
        return mExpandAnimationRunning;
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
