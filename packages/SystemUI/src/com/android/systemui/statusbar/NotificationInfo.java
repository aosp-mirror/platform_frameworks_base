/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.NotificationCounters;

import java.util.List;

/**
 * The guts of a notification revealed when performing a long press. This also houses the blocking
 * helper affordance that allows a user to keep/stop notifications after swiping one away.
 */
public class NotificationInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "InfoGuts";

    private INotificationManager mINotificationManager;
    private PackageManager mPm;
    private MetricsLogger mMetricsLogger;

    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private int mNumUniqueChannelsInRow;
    private NotificationChannel mSingleNotificationChannel;
    private int mStartingUserImportance;
    private int mChosenImportance;
    private boolean mIsSingleDefaultChannel;
    private boolean mIsNonblockable;
    private StatusBarNotification mSbn;
    private AnimatorSet mExpandAnimation;
    private boolean mIsForeground;

    private CheckSaveListener mCheckSaveListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    private OnAppSettingsClickListener mAppSettingsClickListener;
    private NotificationGuts mGutsContainer;

    /** Whether this view is being shown as part of the blocking helper. */
    private boolean mIsForBlockingHelper;
    private boolean mNegativeUserSentiment;

    /**
     * String that describes how the user exit or quit out of this view, also used as a counter tag.
     */
    private String mExitReason = NotificationCounters.BLOCKING_HELPER_DISMISSED;

    private OnClickListener mOnKeepShowing = v -> {
        mExitReason = NotificationCounters.BLOCKING_HELPER_KEEP_SHOWING;
        closeControls(v);
    };

    private OnClickListener mOnStopOrMinimizeNotifications = v -> {
        Runnable saveImportance = () -> {
            mExitReason = NotificationCounters.BLOCKING_HELPER_STOP_NOTIFICATIONS;
            swapContent(false);
        };
        if (mCheckSaveListener != null) {
            mCheckSaveListener.checkSave(saveImportance, mSbn);
        } else {
            saveImportance.run();
        }
    };

    private OnClickListener mOnUndo = v -> {
        // Reset exit counter that we'll log and record an undo event separately (not an exit event)
        mExitReason = NotificationCounters.BLOCKING_HELPER_DISMISSED;
        logBlockingHelperCounter(NotificationCounters.BLOCKING_HELPER_UNDO);
        swapContent(true);
    };

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Specify a CheckSaveListener to override when/if the user's changes are committed.
    public interface CheckSaveListener {
        // Invoked when importance has changed and the NotificationInfo wants to try to save it.
        // Listener should run saveImportance unless the change should be canceled.
        void checkSave(Runnable saveImportance, StatusBarNotification sbn);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    @VisibleForTesting
    void bindNotification(
            final PackageManager pm,
            final INotificationManager iNotificationManager,
            final String pkg,
            final NotificationChannel notificationChannel,
            final int numUniqueChannelsInRow,
            final StatusBarNotification sbn,
            final CheckSaveListener checkSaveListener,
            final OnSettingsClickListener onSettingsClick,
            final OnAppSettingsClickListener onAppSettingsClick,
            boolean isNonblockable)
            throws RemoteException {
        bindNotification(pm, iNotificationManager, pkg, notificationChannel,
                numUniqueChannelsInRow, sbn, checkSaveListener, onSettingsClick,
                onAppSettingsClick, isNonblockable, false /* isBlockingHelper */,
                false /* isUserSentimentNegative */);
    }

    public void bindNotification(
            PackageManager pm,
            INotificationManager iNotificationManager,
            String pkg,
            NotificationChannel notificationChannel,
            int numUniqueChannelsInRow,
            StatusBarNotification sbn,
            CheckSaveListener checkSaveListener,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            boolean isNonblockable,
            boolean isForBlockingHelper,
            boolean isUserSentimentNegative)
            throws RemoteException {
        mINotificationManager = iNotificationManager;
        mMetricsLogger = Dependency.get(MetricsLogger.class);
        mPackageName = pkg;
        mNumUniqueChannelsInRow = numUniqueChannelsInRow;
        mSbn = sbn;
        mPm = pm;
        mAppSettingsClickListener = onAppSettingsClick;
        mAppName = mPackageName;
        mCheckSaveListener = checkSaveListener;
        mOnSettingsClickListener = onSettingsClick;
        mSingleNotificationChannel = notificationChannel;
        mStartingUserImportance = mChosenImportance = mSingleNotificationChannel.getImportance();
        mNegativeUserSentiment = isUserSentimentNegative;
        mIsNonblockable = isNonblockable;
        mIsForeground =
                (mSbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        mIsForBlockingHelper = isForBlockingHelper;
        mAppUid = mSbn.getUid();

        int numTotalChannels = mINotificationManager.getNumNotificationChannelsForPackage(
                pkg, mAppUid, false /* includeDeleted */);
        if (mNumUniqueChannelsInRow == 0) {
            throw new IllegalArgumentException("bindNotification requires at least one channel");
        } else  {
            // Special behavior for the Default channel if no other channels have been defined.
            mIsSingleDefaultChannel = mNumUniqueChannelsInRow == 1
                    && mSingleNotificationChannel.getId().equals(
                            NotificationChannel.DEFAULT_CHANNEL_ID)
                    && numTotalChannels == 1;
        }

        bindHeader();
        bindPrompt();
        bindButtons();
    }

    private void bindHeader() throws RemoteException {
        // Package name
        Drawable pkgicon = null;
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(
                    mPackageName,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                pkgicon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = mPm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkgicon)).setImageDrawable(pkgicon);
        ((TextView) findViewById(R.id.pkgname)).setText(mAppName);

        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mSingleNotificationChannel != null && mSingleNotificationChannel.getGroup() != null) {
            final NotificationChannelGroup notificationChannelGroup =
                    mINotificationManager.getNotificationChannelGroupForPackage(
                            mSingleNotificationChannel.getGroup(), mPackageName, mAppUid);
            if (notificationChannelGroup != null) {
                groupName = notificationChannelGroup.getName();
            }
        }
        TextView groupNameView = findViewById(R.id.group_name);
        TextView groupDividerView = findViewById(R.id.pkg_group_divider);
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(View.VISIBLE);
            groupDividerView.setVisibility(View.VISIBLE);
        } else {
            groupNameView.setVisibility(View.GONE);
            groupDividerView.setVisibility(View.GONE);
        }

        // Settings button.
        final View settingsButton = findViewById(R.id.info);
        if (mAppUid >= 0 && mOnSettingsClickListener != null) {
            settingsButton.setVisibility(View.VISIBLE);
            final int appUidF = mAppUid;
            settingsButton.setOnClickListener(
                    (View view) -> {
                        logBlockingHelperCounter(
                                NotificationCounters.BLOCKING_HELPER_NOTIF_SETTINGS);
                        mOnSettingsClickListener.onClick(view,
                                mNumUniqueChannelsInRow > 1 ? null : mSingleNotificationChannel,
                                appUidF);
                    });
        } else {
            settingsButton.setVisibility(View.GONE);
        }
    }

    private void bindPrompt() {
        final TextView blockPrompt = findViewById(R.id.block_prompt);
        bindName();
        if (mIsNonblockable) {
            blockPrompt.setText(R.string.notification_unblockable_desc);
        } else {
            if (mNegativeUserSentiment) {
                blockPrompt.setText(R.string.inline_blocking_helper);
            }  else if (mIsSingleDefaultChannel || mNumUniqueChannelsInRow > 1) {
                blockPrompt.setText(R.string.inline_keep_showing_app);
            } else {
                blockPrompt.setText(R.string.inline_keep_showing);
            }
        }
    }

    private void bindName() {
        final TextView channelName = findViewById(R.id.channel_name);
        if (mIsSingleDefaultChannel || mNumUniqueChannelsInRow > 1) {
            channelName.setVisibility(View.GONE);
        } else {
            channelName.setText(mSingleNotificationChannel.getName());
        }
    }

    @VisibleForTesting
    void logBlockingHelperCounter(String counterTag) {
        if (mIsForBlockingHelper) {
            mMetricsLogger.count(counterTag, 1);
        }
    }

    private boolean hasImportanceChanged() {
        return mSingleNotificationChannel != null && mStartingUserImportance != mChosenImportance;
    }

    private void saveImportance() {
        if (!mIsNonblockable) {
            updateImportance();
        }
    }

    /**
     * Commits the updated importance values on the background thread.
     */
    private void updateImportance() {
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                mChosenImportance - mStartingUserImportance);

        Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
        bgHandler.post(new UpdateImportanceRunnable(mINotificationManager, mPackageName, mAppUid,
                mNumUniqueChannelsInRow == 1 ? mSingleNotificationChannel : null,
                mStartingUserImportance, mChosenImportance));
    }

    private void bindButtons() {
        // Set up stay-in-notification actions
        View block =  findViewById(R.id.block);
        TextView keep = findViewById(R.id.keep);
        View minimize = findViewById(R.id.minimize);

        findViewById(R.id.undo).setOnClickListener(mOnUndo);
        block.setOnClickListener(mOnStopOrMinimizeNotifications);
        keep.setOnClickListener(mOnKeepShowing);
        minimize.setOnClickListener(mOnStopOrMinimizeNotifications);

        if (mIsNonblockable) {
            keep.setText(android.R.string.ok);
            block.setVisibility(GONE);
            minimize.setVisibility(GONE);
        } else if (mIsForeground) {
            block.setVisibility(GONE);
            minimize.setVisibility(VISIBLE);
        } else if (!mIsForeground) {
            block.setVisibility(VISIBLE);
            minimize.setVisibility(GONE);
        }

        // Set up app settings link (i.e. Customize)
        TextView settingsLinkView = findViewById(R.id.app_settings);
        Intent settingsIntent = getAppSettingsIntent(mPm, mPackageName, mSingleNotificationChannel,
                mSbn.getId(), mSbn.getTag());
        if (!mIsForBlockingHelper
                && settingsIntent != null
                && !TextUtils.isEmpty(mSbn.getNotification().getSettingsText())) {
            settingsLinkView.setVisibility(VISIBLE);
            settingsLinkView.setText(mContext.getString(R.string.notification_app_settings));
            settingsLinkView.setOnClickListener((View view) -> {
                mAppSettingsClickListener.onClick(view, settingsIntent);
            });
        } else {
            settingsLinkView.setVisibility(View.GONE);
        }
    }

    private void swapContent(boolean showPrompt) {
        if (mExpandAnimation != null) {
            mExpandAnimation.cancel();
        }

        View prompt = findViewById(R.id.prompt);
        ViewGroup confirmation = findViewById(R.id.confirmation);
        TextView confirmationText = findViewById(R.id.confirmation_text);
        View header = findViewById(R.id.header);

        if (showPrompt) {
            mChosenImportance = mStartingUserImportance;
        } else if (mIsForeground) {
            mChosenImportance = IMPORTANCE_MIN;
            confirmationText.setText(R.string.notification_channel_minimized);
        } else {
            mChosenImportance = IMPORTANCE_NONE;
            confirmationText.setText(R.string.notification_channel_disabled);
        }

        ObjectAnimator promptAnim = ObjectAnimator.ofFloat(prompt, View.ALPHA,
                prompt.getAlpha(), showPrompt ? 1f : 0f);
        promptAnim.setInterpolator(showPrompt ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT);
        ObjectAnimator confirmAnim = ObjectAnimator.ofFloat(confirmation, View.ALPHA,
                confirmation.getAlpha(), showPrompt ? 0f : 1f);
        confirmAnim.setInterpolator(showPrompt ? Interpolators.ALPHA_OUT : Interpolators.ALPHA_IN);

        prompt.setVisibility(showPrompt ? VISIBLE : GONE);
        confirmation.setVisibility(showPrompt ? GONE : VISIBLE);
        header.setVisibility(showPrompt ? VISIBLE : GONE);

        mExpandAnimation = new AnimatorSet();
        mExpandAnimation.playTogether(promptAnim, confirmAnim);
        mExpandAnimation.setDuration(150);
        mExpandAnimation.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!cancelled) {
                    prompt.setVisibility(showPrompt ? VISIBLE : GONE);
                    confirmation.setVisibility(showPrompt ? GONE : VISIBLE);
                }
            }
        });
        mExpandAnimation.start();
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null &&
                event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private Intent getAppSettingsIntent(PackageManager pm, String packageName,
            NotificationChannel channel, int id, String tag) {
        Intent intent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES)
                .setPackage(packageName);
        final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        if (resolveInfos == null || resolveInfos.size() == 0 || resolveInfos.get(0) == null) {
            return null;
        }
        final ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
        intent.setClassName(activityInfo.packageName, activityInfo.name);
        if (channel != null) {
            intent.putExtra(Notification.EXTRA_CHANNEL_ID, channel.getId());
        }
        intent.putExtra(Notification.EXTRA_NOTIFICATION_ID, id);
        intent.putExtra(Notification.EXTRA_NOTIFICATION_TAG, tag);
        return intent;
    }

    /**
     * Closes the controls and commits the updated importance values (indirectly). If this view is
     * being used to show the blocking helper, this will immediately dismiss the blocking helper and
     * commit the updated importance.
     *
     * <p><b>Note,</b> this will only get called once the view is dismissing. This means that the
     * user does not have the ability to undo the action anymore. See {@link #swapContent(boolean)}
     * for where undo is handled.
     */
    @VisibleForTesting
    void closeControls(View v) {
        int[] parentLoc = new int[2];
        int[] targetLoc = new int[2];
        mGutsContainer.getLocationOnScreen(parentLoc);
        v.getLocationOnScreen(targetLoc);
        final int centerX = v.getWidth() / 2;
        final int centerY = v.getHeight() / 2;
        final int x = targetLoc[0] - parentLoc[0] + centerX;
        final int y = targetLoc[1] - parentLoc[1] + centerY;
        mGutsContainer.closeControls(x, y, true /* save */, false /* force */);
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return hasImportanceChanged();
    }

    @Override
    public boolean shouldBeSaved() {
        return hasImportanceChanged();
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        // Save regardless of the importance so we can lock the importance field if the user wants
        // to keep getting notifications
        if (save) {
            saveImportance();
        }
        logBlockingHelperCounter(mExitReason);
        return false;
    }

    @Override
    public int getActualHeight() {
        return getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return mExpandAnimation != null && mExpandAnimation.isRunning();
    }

    /**
     * Runnable to either update the given channel (with a new importance value) or, if no channel
     * is provided, update notifications enabled state for the package.
     */
    private static class UpdateImportanceRunnable implements Runnable {
        private final INotificationManager mINotificationManager;
        private final String mPackageName;
        private final int mAppUid;
        private final @Nullable NotificationChannel mChannelToUpdate;
        private final int mCurrentImportance;
        private final int mNewImportance;


        public UpdateImportanceRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Nullable NotificationChannel channelToUpdate,
                int currentImportance, int newImportance) {
            mINotificationManager = notificationManager;
            mPackageName = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mCurrentImportance = currentImportance;
            mNewImportance = newImportance;
        }

        @Override
        public void run() {
            try {
                if (mChannelToUpdate != null) {
                    mChannelToUpdate.setImportance(mNewImportance);
                    mChannelToUpdate.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
                    mINotificationManager.updateNotificationChannelForPackage(
                            mPackageName, mAppUid, mChannelToUpdate);
                } else {
                    // For notifications with more than one channel, update notification enabled
                    // state. If the importance was lowered, we disable notifications.
                    mINotificationManager.setNotificationsEnabledWithImportanceLockForPackage(
                            mPackageName, mAppUid, mNewImportance >= mCurrentImportance);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update notification importance", e);
            }
        }
    }
}
