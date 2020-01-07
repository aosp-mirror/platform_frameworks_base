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

import static com.android.systemui.statusbar.notification.stack.NotificationSectionsManager.BUCKET_ALERTING;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.Notification.MessagingStyle.Message;
import android.app.NotificationChannel;
import android.app.NotificationManager.Policy;
import android.app.Person;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService.Ranking;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.statusbar.notification.InflationException;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifPromoter;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationGuts;
import com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.InflationFlag;
import com.android.systemui.statusbar.notification.stack.NotificationSectionsManager;

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

    /** If this notification was filtered out, then the filter that did the filtering. */
    @Nullable NotifFilter mExcludingFilter;

    /** If this was a group child that was promoted to the top level, then who did the promoting. */
    @Nullable NotifPromoter mNotifPromoter;

    /** If this notification had an issue with inflating. Only used with the NewNotifPipeline **/
    private boolean mHasInflationError;


    /*
    * Old members
    * TODO: Remove every member beneath this line if possible
    */

    public StatusBarIconView icon;
    public StatusBarIconView expandedIcon;
    public StatusBarIconView centeredIcon;
    public StatusBarIconView aodIcon;
    private boolean interruption;
    public int targetSdk;
    private long lastFullScreenIntentLaunchTime = NOT_LAUNCHED_YET;
    public CharSequence remoteInputText;
    private Notification.BubbleMetadata mBubbleMetadata;

    /**
     * If {@link android.app.RemoteInput#getEditChoicesBeforeSending} is enabled, and the user is
     * currently editing a choice (smart reply), then this field contains the information about the
     * suggestion being edited. Otherwise <code>null</code>.
     */
    public EditedSuggestionInfo editedSuggestionInfo;

    private NotificationEntry parent; // our parent (if we're in a group)
    private ExpandableNotificationRow row; // the outer expanded view

    private int mCachedContrastColor = COLOR_INVALID;
    private int mCachedContrastColorIsFor = COLOR_INVALID;
    private InflationTask mRunningTask = null;
    private Throwable mDebugThrowable;
    public CharSequence remoteInputTextWhenReset;
    public long lastRemoteInputSent = NOT_LAUNCHED_YET;
    public final ArraySet<Integer> mActiveAppOps = new ArraySet<>(3);
    public CharSequence headsUpStatusBarText;
    public CharSequence headsUpStatusBarTextPublic;

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
    private Runnable mOnSensitiveChangedListener;
    private boolean mAutoHeadsUp;
    private boolean mPulseSupressed;
    private int mBucket = BUCKET_ALERTING;

    public NotificationEntry(
            @NonNull StatusBarNotification sbn,
            @NonNull Ranking ranking) {
        super(Objects.requireNonNull(Objects.requireNonNull(sbn).getKey()));

        Objects.requireNonNull(ranking);

        mKey = sbn.getKey();
        setSbn(sbn);
        setRanking(ranking);
    }

    @Override
    public NotificationEntry getRepresentativeEntry() {
        return this;
    }

    /** The key for this notification. Guaranteed to be immutable and unique */
    public String getKey() {
        return mKey;
    }

    /**
     * The StatusBarNotification that represents one half of a NotificationEntry (the other half
     * being the Ranking). This object is swapped out whenever a notification is updated.
     */
    public StatusBarNotification getSbn() {
        return mSbn;
    }

    /**
     * Should only be called by NotificationEntryManager and friends.
     * TODO: Make this package-private
     */
    public void setSbn(@NonNull StatusBarNotification sbn) {
        Objects.requireNonNull(sbn);
        Objects.requireNonNull(sbn.getKey());

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
        Objects.requireNonNull(ranking);
        Objects.requireNonNull(ranking.getKey());

        if (!ranking.getKey().equals(mKey)) {
            throw new IllegalArgumentException("New key " + ranking.getKey()
                    + " doesn't match existing key " + mKey);
        }

        mRanking = ranking;
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
    public Notification.BubbleMetadata getBubbleMetadata() {
        return mBubbleMetadata;
    }

    /**
     * Sets bubble metadata for this notification.
     */
    public void setBubbleMetadata(Notification.BubbleMetadata metadata) {
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

    /**
     * Resets the notification entry to be re-used.
     */
    public void reset() {
        if (row != null) {
            row.reset();
        }
    }

    @NotificationSectionsManager.PriorityBucket
    public int getBucket() {
        return mBucket;
    }

    public void setBucket(@NotificationSectionsManager.PriorityBucket int bucket) {
        mBucket = bucket;
    }

    public ExpandableNotificationRow getRow() {
        return row;
    }

    //TODO: This will go away when we have a way to bind an entry to a row
    public void setRow(ExpandableNotificationRow row) {
        this.row = row;
    }

    @Nullable
    public List<NotificationEntry> getChildren() {
        if (row == null) {
            return null;
        }

        List<ExpandableNotificationRow> rowChildren = row.getNotificationChildren();
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
        return initializationTime == -1
                || SystemClock.elapsedRealtime() > initializationTime + INITIALIZATION_DELAY;
    }

    /**
     * Create the icons for a notification
     * @param context the context to create the icons with
     * @param sbn the notification
     * @throws InflationException Exception if required icons are not valid or specified
     */
    public void createIcons(Context context, StatusBarNotification sbn)
            throws InflationException {
        Notification n = sbn.getNotification();
        final Icon smallIcon = n.getSmallIcon();
        if (smallIcon == null) {
            throw new InflationException("No small icon in notification from "
                    + sbn.getPackageName());
        }

        // Construct the icon.
        icon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Construct the expanded icon.
        expandedIcon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        expandedIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        // Construct the expanded icon.
        aodIcon = new StatusBarIconView(context,
                sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
        aodIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        aodIcon.setIncreasedSize(true);

        final StatusBarIcon ic = new StatusBarIcon(
                sbn.getUser(),
                sbn.getPackageName(),
                smallIcon,
                n.iconLevel,
                n.number,
                StatusBarIconView.contentDescForNotification(context, n));

        if (!icon.set(ic) || !expandedIcon.set(ic) || !aodIcon.set(ic)) {
            icon = null;
            expandedIcon = null;
            centeredIcon = null;
            aodIcon = null;
            throw new InflationException("Couldn't create icon: " + ic);
        }
        expandedIcon.setVisibility(View.INVISIBLE);
        expandedIcon.setOnVisibilityChangedListener(
                newVisibility -> {
                    if (row != null) {
                        row.setIconsVisible(newVisibility != View.VISIBLE);
                    }
                });

        // Construct the centered icon
        if (mSbn.getNotification().isMediaNotification()) {
            centeredIcon = new StatusBarIconView(context,
                    sbn.getPackageName() + "/0x" + Integer.toHexString(sbn.getId()), sbn);
            centeredIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            if (!centeredIcon.set(ic)) {
                centeredIcon = null;
                throw new InflationException("Couldn't update centered icon: " + ic);
            }
        }
    }

    public void setIconTag(int key, Object tag) {
        if (icon != null) {
            icon.setTag(key, tag);
            expandedIcon.setTag(key, tag);
        }

        if (centeredIcon != null) {
            centeredIcon.setTag(key, tag);
        }

        if (aodIcon != null) {
            aodIcon.setTag(key, tag);
        }
    }

    /**
     * Update the notification icons.
     *
     * @param context the context to create the icons with.
     * @param sbn the notification to read the icon from.
     * @throws InflationException Exception if required icons are not valid or specified
     */
    public void updateIcons(Context context, StatusBarNotification sbn)
            throws InflationException {
        if (icon != null) {
            // Update the icon
            Notification n = sbn.getNotification();
            final StatusBarIcon ic = new StatusBarIcon(
                    mSbn.getUser(),
                    mSbn.getPackageName(),
                    n.getSmallIcon(),
                    n.iconLevel,
                    n.number,
                    StatusBarIconView.contentDescForNotification(context, n));
            icon.setNotification(sbn);
            expandedIcon.setNotification(sbn);
            aodIcon.setNotification(sbn);
            if (!icon.set(ic) || !expandedIcon.set(ic) || !aodIcon.set(ic)) {
                throw new InflationException("Couldn't update icon: " + ic);
            }

            if (centeredIcon != null) {
                centeredIcon.setNotification(sbn);
                if (!centeredIcon.set(ic)) {
                    throw new InflationException("Couldn't update centered icon: " + ic);
                }
            }
        }
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
        InflationTask existing = mRunningTask;
        abortTask();
        mRunningTask = abortableTask;
        if (existing != null && mRunningTask != null) {
            mRunningTask.supersedeTask(existing);
        }
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

    void setHasInflationError(boolean hasError) {
        mHasInflationError = hasError;
    }

    /**
     * Whether this notification had an error when attempting to inflate. This is only used in
     * the NewNotifPipeline
     */
    public boolean hasInflationError() {
        return mHasInflationError;
    }

    public void setHasSentReply() {
        hasSentReply = true;
    }

    public boolean isLastMessageFromReply() {
        if (!hasSentReply) {
            return false;
        }
        Bundle extras = mSbn.getNotification().extras;
        CharSequence[] replyTexts = extras.getCharSequenceArray(
                Notification.EXTRA_REMOTE_INPUT_HISTORY);
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

    public void freeContentViewWhenSafe(@InflationFlag int inflationFlag) {
        if (row != null) row.freeContentViewWhenSafe(inflationFlag);
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
        return parent == null;
    }

    /**
     * @return Can the underlying notification be cleared? This can be different from whether the
     *         notification can be dismissed in case notifications are sensitive on the lockscreen.
     * @see #canViewBeDismissed()
     */
    public boolean isClearable() {
        if (!mSbn.isClearable()) {
            return false;
        }

        List<NotificationEntry> children = getChildren();
        if (children != null && children.size() > 0) {
            for (int i = 0; i < children.size(); i++) {
                NotificationEntry child =  children.get(i);
                if (!child.isClearable()) {
                    return false;
                }
            }
        }
        return true;
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
                & Notification.FLAG_FOREGROUND_SERVICE) != 0) {
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
            if (mOnSensitiveChangedListener != null) {
                mOnSensitiveChangedListener.run();
            }
        }
    }

    public boolean isSensitive() {
        return mSensitive;
    }

    public void setOnSensitiveChangedListener(Runnable listener) {
        mOnSensitiveChangedListener = listener;
    }

    public boolean isPulseSuppressed() {
        return mPulseSupressed;
    }

    public void setPulseSuppressed(boolean suppressed) {
        mPulseSupressed = suppressed;
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

    private static final long LAUNCH_COOLDOWN = 2000;
    private static final long REMOTE_INPUT_COOLDOWN = 500;
    private static final long INITIALIZATION_DELAY = 400;
    private static final long NOT_LAUNCHED_YET = -LAUNCH_COOLDOWN;
    private static final int COLOR_INVALID = 1;
}
