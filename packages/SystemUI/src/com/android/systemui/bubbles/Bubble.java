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
package com.android.systemui.bubbles;


import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the data and UI elements of a bubble.
 */
class Bubble {
    private static final String TAG = "Bubble";

    private NotificationEntry mEntry;
    private final String mKey;
    private final String mGroupId;
    private String mAppName;

    private boolean mInflated;
    private BubbleView mIconView;
    private BubbleExpandedView mExpandedView;

    private long mLastUpdated;
    private long mLastAccessed;
    private boolean mIsRemoved;

    /**
     * Whether this notification should be shown in the shade when it is also displayed as a bubble.
     *
     * <p>When a notification is a bubble we don't show it in the shade once the bubble has been
     * expanded</p>
     */
    private boolean mShowInShadeWhenBubble = true;

    /**
     * Whether the bubble should show a dot for the notification indicating updated content.
     */
    private boolean mShowBubbleUpdateDot = true;

    public static String groupId(NotificationEntry entry) {
        UserHandle user = entry.notification.getUser();
        return user.getIdentifier() + "|" + entry.notification.getPackageName();
    }

    /** Used in tests when no UI is required. */
    @VisibleForTesting(visibility = PRIVATE)
    Bubble(Context context, NotificationEntry e) {
        mEntry = e;
        mKey = e.key;
        mLastUpdated = e.notification.getPostTime();
        mGroupId = groupId(e);

        PackageManager pm = context.getPackageManager();
        ApplicationInfo info;
        try {
            info = pm.getApplicationInfo(
                mEntry.notification.getPackageName(),
                PackageManager.MATCH_UNINSTALLED_PACKAGES
                    | PackageManager.MATCH_DISABLED_COMPONENTS
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                    | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(pm.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException unused) {
            mAppName = mEntry.notification.getPackageName();
        }
    }

    public String getKey() {
        return mKey;
    }

    public NotificationEntry getEntry() {
        return mEntry;
    }

    public String getGroupId() {
        return mGroupId;
    }

    public String getPackageName() {
        return mEntry.notification.getPackageName();
    }

    public String getAppName() {
        return mAppName;
    }

    boolean isInflated() {
        return mInflated;
    }

    void updateDotVisibility() {
        if (mIconView != null) {
            mIconView.updateDotVisibility(true /* animate */);
        }
    }

    BubbleView getIconView() {
        return mIconView;
    }

    BubbleExpandedView getExpandedView() {
        return mExpandedView;
    }

    void inflate(LayoutInflater inflater, BubbleStackView stackView) {
        if (mInflated) {
            return;
        }
        mIconView = (BubbleView) inflater.inflate(
                R.layout.bubble_view, stackView, false /* attachToRoot */);
        mIconView.setBubble(this);

        mExpandedView = (BubbleExpandedView) inflater.inflate(
                R.layout.bubble_expanded_view, stackView, false /* attachToRoot */);
        mExpandedView.setBubble(this, stackView, mAppName);

        mInflated = true;
    }

    /**
     * Set visibility of bubble in the expanded state.
     *
     * @param visibility {@code true} if the expanded bubble should be visible on the screen.
     *
     * Note that this contents visibility doesn't affect visibility at {@link android.view.View},
     * and setting {@code false} actually means rendering the expanded view in transparent.
     */
    void setContentVisibility(boolean visibility) {
        if (mExpandedView != null) {
            mExpandedView.setContentVisibility(visibility);
        }
    }

    void setRemoved() {
        mIsRemoved = true;
        // TODO: move this somewhere where it can be guaranteed not to run until safe from flicker
        if (mExpandedView != null) {
            mExpandedView.cleanUpExpandedState();
        }
    }

    void setRemoved(boolean removed) {
        mIsRemoved = removed;
    }

    boolean isRemoved() {
        return mIsRemoved;
    }

    void updateEntry(NotificationEntry entry) {
        mEntry = entry;
        mLastUpdated = entry.notification.getPostTime();
        if (mInflated) {
            mIconView.update(this);
            mExpandedView.update(this);
        }
    }

    /**
     * @return the newer of {@link #getLastUpdateTime()} and {@link #getLastAccessTime()}
     */
    long getLastActivity() {
        return Math.max(mLastUpdated, mLastAccessed);
    }

    /**
     * @return the timestamp in milliseconds of the most recent notification entry for this bubble
     */
    long getLastUpdateTime() {
        return mLastUpdated;
    }

    /**
     * @return the timestamp in milliseconds when this bubble was last displayed in expanded state
     */
    long getLastAccessTime() {
        return mLastAccessed;
    }

    /**
     * @return the display id of the virtual display on which bubble contents is drawn.
     */
    int getDisplayId() {
        return mExpandedView != null ? mExpandedView.getVirtualDisplayId() : INVALID_DISPLAY;
    }

    /**
     * Should be invoked whenever a Bubble is accessed (selected while expanded).
     */
    void markAsAccessedAt(long lastAccessedMillis) {
        mLastAccessed = lastAccessedMillis;
        setShowInShadeWhenBubble(false);
        setShowBubbleDot(false);
    }

    /**
     * Whether this notification should be shown in the shade when it is also displayed as a
     * bubble.
     */
    boolean showInShadeWhenBubble() {
        return !mEntry.isRowDismissed() && !shouldSuppressNotification()
                && (!mEntry.isClearable() || mShowInShadeWhenBubble);
    }

    /**
     * Sets whether this notification should be shown in the shade when it is also displayed as a
     * bubble.
     */
    void setShowInShadeWhenBubble(boolean showInShade) {
        mShowInShadeWhenBubble = showInShade;
    }

    /**
     * Sets whether the bubble for this notification should show a dot indicating updated content.
     */
    void setShowBubbleDot(boolean showDot) {
        mShowBubbleUpdateDot = showDot;
    }

    /**
     * Whether the bubble for this notification should show a dot indicating updated content.
     */
    boolean showBubbleDot() {
        return mShowBubbleUpdateDot && !mEntry.shouldSuppressNotificationDot();
    }

    /**
     * Whether the flyout for the bubble should be shown.
     */
    boolean showFlyoutForBubble() {
        return !mEntry.shouldSuppressPeek() && !mEntry.shouldSuppressNotificationList();
    }

    /**
     * Returns whether the notification for this bubble is a foreground service. It shows that this
     * is an ongoing bubble.
     */
    boolean isOngoing() {
        int flags = mEntry.notification.getNotification().flags;
        return (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }

    float getDesiredHeight(Context context) {
        Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
        boolean useRes = data.getDesiredHeightResId() != 0;
        if (useRes) {
            return getDimenForPackageUser(context, data.getDesiredHeightResId(),
                    mEntry.notification.getPackageName(),
                    mEntry.notification.getUser().getIdentifier());
        } else {
            return data.getDesiredHeight()
                    * context.getResources().getDisplayMetrics().density;
        }
    }

    @Nullable
    PendingIntent getBubbleIntent(Context context) {
        Notification notif = mEntry.notification.getNotification();
        Notification.BubbleMetadata data = notif.getBubbleMetadata();
        if (BubbleController.canLaunchInActivityView(context, mEntry) && data != null) {
            return data.getIntent();
        }
        return null;
    }

    Intent getSettingsIntent() {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        intent.putExtra(Settings.EXTRA_APP_UID, mEntry.notification.getUid());
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    /**
     * Returns our best guess for the most relevant text summary of the latest update to this
     * notification, based on its type. Returns null if there should not be an update message.
     */
    CharSequence getUpdateMessage(Context context) {
        final Notification underlyingNotif = mEntry.notification.getNotification();
        final Class<? extends Notification.Style> style = underlyingNotif.getNotificationStyle();

        try {
            if (Notification.BigTextStyle.class.equals(style)) {
                // Return the big text, it is big so probably important. If it's not there use the
                // normal text.
                CharSequence bigText =
                        underlyingNotif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
                return !TextUtils.isEmpty(bigText)
                        ? bigText
                        : underlyingNotif.extras.getCharSequence(Notification.EXTRA_TEXT);
            } else if (Notification.MessagingStyle.class.equals(style)) {
                final List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                (Parcelable[]) underlyingNotif.extras.get(
                                        Notification.EXTRA_MESSAGES));

                final Notification.MessagingStyle.Message latestMessage =
                        Notification.MessagingStyle.findLatestIncomingMessage(messages);

                if (latestMessage != null) {
                    final CharSequence personName = latestMessage.getSenderPerson() != null
                            ? latestMessage.getSenderPerson().getName()
                            : null;

                    // Prepend the sender name if available since group chats also use messaging
                    // style.
                    if (!TextUtils.isEmpty(personName)) {
                        return context.getResources().getString(
                                R.string.notification_summary_message_format,
                                personName,
                                latestMessage.getText());
                    } else {
                        return latestMessage.getText();
                    }
                }
            } else if (Notification.InboxStyle.class.equals(style)) {
                CharSequence[] lines =
                        underlyingNotif.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);

                // Return the last line since it should be the most recent.
                if (lines != null && lines.length > 0) {
                    return lines[lines.length - 1];
                }
            } else if (Notification.MediaStyle.class.equals(style)) {
                // Return nothing, media updates aren't typically useful as a text update.
                return null;
            } else {
                // Default to text extra.
                return underlyingNotif.extras.getCharSequence(Notification.EXTRA_TEXT);
            }
        } catch (ClassCastException | NullPointerException | ArrayIndexOutOfBoundsException e) {
            // No use crashing, we'll just return null and the caller will assume there's no update
            // message.
            e.printStackTrace();
        }

        return null;
    }

    private int getDimenForPackageUser(Context context, int resId, String pkg, int userId) {
        PackageManager pm = context.getPackageManager();
        Resources r;
        if (pkg != null) {
            try {
                if (userId == UserHandle.USER_ALL) {
                    userId = UserHandle.USER_SYSTEM;
                }
                r = pm.getResourcesForApplicationAsUser(pkg, userId);
                return r.getDimensionPixelSize(resId);
            } catch (PackageManager.NameNotFoundException ex) {
                // Uninstalled, don't care
            } catch (Resources.NotFoundException e) {
                // Invalid res id, return 0 and user our default
                Log.e(TAG, "Couldn't find desired height res id", e);
            }
        }
        return 0;
    }

    private boolean shouldSuppressNotification() {
        return mEntry.getBubbleMetadata() != null
                && mEntry.getBubbleMetadata().isNotificationSuppressed();
    }

    @Override
    public String toString() {
        return "Bubble{" + mKey + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Bubble)) return false;
        Bubble bubble = (Bubble) o;
        return Objects.equals(mKey, bubble.mKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mKey);
    }
}
