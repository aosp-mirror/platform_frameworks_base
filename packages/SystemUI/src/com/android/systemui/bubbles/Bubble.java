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


import static android.os.AsyncTask.Status.FINISHED;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.shared.system.SysUiStatsLog;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * Encapsulates the data and UI elements of a bubble.
 */
class Bubble implements BubbleViewProvider {
    private static final String TAG = "Bubble";

    private NotificationEntry mEntry;
    private final String mKey;
    private final String mGroupId;

    private long mLastUpdated;
    private long mLastAccessed;

    private BubbleController.NotificationSuppressionChangedListener mSuppressionListener;

    /** Whether the bubble should show a dot for the notification indicating updated content. */
    private boolean mShowBubbleUpdateDot = true;

    /** Whether flyout text should be suppressed, regardless of any other flags or state. */
    private boolean mSuppressFlyout;

    // Items that are typically loaded later
    private String mAppName;
    private ShortcutInfo mShortcutInfo;
    private BadgedImageView mIconView;
    private BubbleExpandedView mExpandedView;

    private BubbleViewInfoTask mInflationTask;
    private boolean mInflateSynchronously;
    private boolean mPendingIntentCanceled;

    /**
     * Presentational info about the flyout.
     */
    public static class FlyoutMessage {
        @Nullable public Drawable senderAvatar;
        @Nullable public CharSequence senderName;
        @Nullable public CharSequence message;
        @Nullable public boolean isGroupChat;
    }

    private FlyoutMessage mFlyoutMessage;
    private Drawable mBadgedAppIcon;
    private Bitmap mBadgedImage;
    private int mDotColor;
    private Path mDotPath;


    public static String groupId(NotificationEntry entry) {
        UserHandle user = entry.getSbn().getUser();
        return user.getIdentifier() + "|" + entry.getSbn().getPackageName();
    }

    /** Used in tests when no UI is required. */
    @VisibleForTesting(visibility = PRIVATE)
    Bubble(NotificationEntry e,
            BubbleController.NotificationSuppressionChangedListener listener) {
        mEntry = e;
        mKey = e.getKey();
        mLastUpdated = e.getSbn().getPostTime();
        mGroupId = groupId(e);
        mSuppressionListener = listener;
    }

    @Override
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
        return mEntry.getSbn().getPackageName();
    }

    @Override
    public Bitmap getBadgedImage() {
        return mBadgedImage;
    }

    public Drawable getBadgedAppIcon() {
        return mBadgedAppIcon;
    }

    @Override
    public int getDotColor() {
        return mDotColor;
    }

    @Override
    public Path getDotPath() {
        return mDotPath;
    }

    @Nullable
    public String getAppName() {
        return mAppName;
    }

    @Nullable
    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    @Nullable
    @Override
    public BadgedImageView getIconView() {
        return mIconView;
    }

    @Override
    @Nullable
    public BubbleExpandedView getExpandedView() {
        return mExpandedView;
    }

    /**
     * Call when the views should be removed, ensure this is called to clean up ActivityView
     * content.
     */
    void cleanupViews() {
        if (mExpandedView != null) {
            mExpandedView.cleanUpExpandedState();
            mExpandedView = null;
        }
        mIconView = null;
    }

    void setPendingIntentCanceled() {
        mPendingIntentCanceled = true;
    }

    boolean getPendingIntentCanceled() {
        return mPendingIntentCanceled;
    }

    /**
     * Sets whether to perform inflation on the same thread as the caller. This method should only
     * be used in tests, not in production.
     */
    @VisibleForTesting
    void setInflateSynchronously(boolean inflateSynchronously) {
        mInflateSynchronously = inflateSynchronously;
    }

    /**
     * Starts a task to inflate & load any necessary information to display a bubble.
     *
     * @param callback the callback to notify one the bubble is ready to be displayed.
     * @param context the context for the bubble.
     * @param stackView the stackView the bubble is eventually added to.
     * @param iconFactory the iconfactory use to create badged images for the bubble.
     */
    void inflate(BubbleViewInfoTask.Callback callback,
            Context context,
            BubbleStackView stackView,
            BubbleIconFactory iconFactory) {
        if (isBubbleLoading()) {
            mInflationTask.cancel(true /* mayInterruptIfRunning */);
        }
        mInflationTask = new BubbleViewInfoTask(this,
                context,
                stackView,
                iconFactory,
                callback);
        if (mInflateSynchronously) {
            mInflationTask.onPostExecute(mInflationTask.doInBackground());
        } else {
            mInflationTask.execute();
        }
    }

    private boolean isBubbleLoading() {
        return mInflationTask != null && mInflationTask.getStatus() != FINISHED;
    }

    boolean isInflated() {
        return mIconView != null && mExpandedView != null;
    }

    void stopInflation() {
        if (mInflationTask == null) {
            return;
        }
        mInflationTask.cancel(true /* mayInterruptIfRunning */);
        cleanupViews();
    }

    void setViewInfo(BubbleViewInfoTask.BubbleViewInfo info) {
        if (!isInflated()) {
            mIconView = info.imageView;
            mExpandedView = info.expandedView;
        }

        mShortcutInfo = info.shortcutInfo;
        mAppName = info.appName;
        mFlyoutMessage = info.flyoutMessage;

        mBadgedAppIcon = info.badgedAppIcon;
        mBadgedImage = info.badgedBubbleImage;
        mDotColor = info.dotColor;
        mDotPath = info.dotPath;

        if (mExpandedView != null) {
            mExpandedView.update(/* bubble */ this);
        }
        if (mIconView != null) {
            mIconView.setRenderedBubble(/* bubble */ this);
        }
    }

    /**
     * Set visibility of bubble in the expanded state.
     *
     * @param visibility {@code true} if the expanded bubble should be visible on the screen.
     *
     * Note that this contents visibility doesn't affect visibility at {@link android.view.View},
     * and setting {@code false} actually means rendering the expanded view in transparent.
     */
    @Override
    public void setContentVisibility(boolean visibility) {
        if (mExpandedView != null) {
            mExpandedView.setContentVisibility(visibility);
        }
    }

    /**
     * Sets the entry associated with this bubble.
     */
    void setEntry(NotificationEntry entry) {
        mEntry = entry;
        mLastUpdated = entry.getSbn().getPostTime();
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
     * @return if the bubble was ever expanded
     */
    boolean getWasAccessed() {
        return mLastAccessed != 0L;
    }

    /**
     * @return the display id of the virtual display on which bubble contents is drawn.
     */
    @Override
    public int getDisplayId() {
        return mExpandedView != null ? mExpandedView.getVirtualDisplayId() : INVALID_DISPLAY;
    }

    /**
     * Should be invoked whenever a Bubble is accessed (selected while expanded).
     */
    void markAsAccessedAt(long lastAccessedMillis) {
        mLastAccessed = lastAccessedMillis;
        setSuppressNotification(true);
        setShowDot(false /* show */);
    }

    /**
     * Should be invoked whenever a Bubble is promoted from overflow.
     */
    void markUpdatedAt(long lastAccessedMillis) {
        mLastUpdated = lastAccessedMillis;
    }

    /**
     * Whether this notification should be shown in the shade.
     */
    boolean showInShade() {
        return !shouldSuppressNotification() || !mEntry.isClearable();
    }

    /**
     * Sets whether this notification should be suppressed in the shade.
     */
    void setSuppressNotification(boolean suppressNotification) {
        boolean prevShowInShade = showInShade();

        Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
        int flags = data.getFlags();
        if (suppressNotification) {
            flags |= Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
        } else {
            flags &= ~Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
        }
        data.setFlags(flags);

        if (showInShade() != prevShowInShade && mSuppressionListener != null) {
            mSuppressionListener.onBubbleNotificationSuppressionChange(this);
        }
    }

    /**
     * Sets whether the bubble for this notification should show a dot indicating updated content.
     */
    void setShowDot(boolean showDot) {
        mShowBubbleUpdateDot = showDot;

        if (mIconView != null) {
            mIconView.updateDotVisibility(true /* animate */);
        }
    }

    /**
     * Whether the bubble for this notification should show a dot indicating updated content.
     */
    @Override
    public boolean showDot() {
        return mShowBubbleUpdateDot
                && !mEntry.shouldSuppressNotificationDot()
                && !shouldSuppressNotification();
    }

    /**
     * Whether the flyout for the bubble should be shown.
     */
    boolean showFlyout() {
        return !mSuppressFlyout && !mEntry.shouldSuppressPeek()
                && !shouldSuppressNotification()
                && !mEntry.shouldSuppressNotificationList();
    }

    /**
     * Set whether the flyout text for the bubble should be shown when an update is received.
     *
     * @param suppressFlyout whether the flyout text is shown
     */
    void setSuppressFlyout(boolean suppressFlyout) {
        mSuppressFlyout = suppressFlyout;
    }

    FlyoutMessage getFlyoutMessage() {
        return mFlyoutMessage;
    }

    /**
     * Returns whether the notification for this bubble is a foreground service. It shows that this
     * is an ongoing bubble.
     */
    boolean isOngoing() {
        int flags = mEntry.getSbn().getNotification().flags;
        return (flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
    }

    float getDesiredHeight(Context context) {
        Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
        boolean useRes = data.getDesiredHeightResId() != 0;
        if (useRes) {
            return getDimenForPackageUser(context, data.getDesiredHeightResId(),
                    mEntry.getSbn().getPackageName(),
                    mEntry.getSbn().getUser().getIdentifier());
        } else {
            return data.getDesiredHeight()
                    * context.getResources().getDisplayMetrics().density;
        }
    }

    String getDesiredHeightString() {
        Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
        boolean useRes = data.getDesiredHeightResId() != 0;
        if (useRes) {
            return String.valueOf(data.getDesiredHeightResId());
        } else {
            return String.valueOf(data.getDesiredHeight());
        }
    }

    /**
     * Whether shortcut information should be used to populate the bubble.
     * <p>
     * To populate the activity use {@link LauncherApps#startShortcut(ShortcutInfo, Rect, Bundle)}.
     * To populate the icon use {@link LauncherApps#getShortcutIconDrawable(ShortcutInfo, int)}.
     */
    boolean usingShortcutInfo() {
        return mEntry.getBubbleMetadata().getShortcutId() != null;
    }

    @Nullable
    PendingIntent getBubbleIntent() {
        Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
        if (data != null) {
            return data.getIntent();
        }
        return null;
    }

    Intent getSettingsIntent() {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        intent.putExtra(Settings.EXTRA_APP_UID, mEntry.getSbn().getUid());
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
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

    boolean shouldAutoExpand() {
        Notification.BubbleMetadata metadata = mEntry.getBubbleMetadata();
        return metadata != null && metadata.getAutoExpandBubble();
    }

    @Override
    public String toString() {
        return "Bubble{" + mKey + '}';
    }

    /**
     * Description of current bubble state.
     */
    public void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("key: "); pw.println(mKey);
        pw.print("  showInShade:   "); pw.println(showInShade());
        pw.print("  showDot:       "); pw.println(showDot());
        pw.print("  showFlyout:    "); pw.println(showFlyout());
        pw.print("  desiredHeight: "); pw.println(getDesiredHeightString());
        pw.print("  suppressNotif: "); pw.println(shouldSuppressNotification());
        pw.print("  autoExpand:    "); pw.println(shouldAutoExpand());
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

    @Override
    public void logUIEvent(int bubbleCount, int action, float normalX, float normalY, int index) {
        if (this.getEntry() == null
                || this.getEntry().getSbn() == null) {
            SysUiStatsLog.write(SysUiStatsLog.BUBBLE_UI_CHANGED,
                    null /* package name */,
                    null /* notification channel */,
                    0 /* notification ID */,
                    0 /* bubble position */,
                    bubbleCount,
                    action,
                    normalX,
                    normalY,
                    false /* unread bubble */,
                    false /* on-going bubble */,
                    false /* isAppForeground (unused) */);
        } else {
            StatusBarNotification notification = this.getEntry().getSbn();
            SysUiStatsLog.write(SysUiStatsLog.BUBBLE_UI_CHANGED,
                    notification.getPackageName(),
                    notification.getNotification().getChannelId(),
                    notification.getId(),
                    index,
                    bubbleCount,
                    action,
                    normalX,
                    normalY,
                    this.showInShade(),
                    this.isOngoing(),
                    false /* isAppForeground (unused) */);
        }
    }
}
