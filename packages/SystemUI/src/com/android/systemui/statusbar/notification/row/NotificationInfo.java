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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_MIN;
import static android.app.NotificationManager.IMPORTANCE_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.IntDef;
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
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
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
import com.android.systemui.statusbar.notification.NotificationUtils;
import com.android.systemui.statusbar.notification.logging.NotificationCounters;

import java.util.List;

/**
 * The guts of a notification revealed when performing a long press. This also houses the blocking
 * helper affordance that allows a user to keep/stop notifications after swiping one away.
 */
public class NotificationInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "InfoGuts";

    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_NONE,
            ACTION_UNDO,
            ACTION_TOGGLE_SILENT,
            ACTION_BLOCK,
    })
    public @interface NotificationInfoAction {
    }

    public static final int ACTION_NONE = 0;
    static final int ACTION_UNDO = 1;
    static final int ACTION_TOGGLE_SILENT = 2;
    static final int ACTION_BLOCK = 3;
    static final int ACTION_DELIVER_SILENTLY = 4;
    private static final int ACTION_ALERT = 5;

    private INotificationManager mINotificationManager;
    private PackageManager mPm;
    private MetricsLogger mMetricsLogger;

    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private int mNumUniqueChannelsInRow;
    private NotificationChannel mSingleNotificationChannel;
    private int mStartingChannelImportance;
    private boolean mWasShownHighPriority;
    private int mNotificationBlockState = ACTION_NONE;
    /**
     * The last importance level chosen by the user.  Null if the user has not chosen an importance
     * level; non-null once the user takes an action which indicates an explicit preference.
     */
    @Nullable private Integer mChosenImportance;
    private boolean mIsSingleDefaultChannel;
    private boolean mIsNonblockable;
    private StatusBarNotification mSbn;
    private AnimatorSet mExpandAnimation;
    private boolean mIsForeground;
    private boolean mIsDeviceProvisioned;

    private CheckSaveListener mCheckSaveListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    private OnAppSettingsClickListener mAppSettingsClickListener;
    private NotificationGuts mGutsContainer;

    /** Whether this view is being shown as part of the blocking helper. */
    private boolean mIsForBlockingHelper;

    /**
     * String that describes how the user exit or quit out of this view, also used as a counter tag.
     */
    private String mExitReason = NotificationCounters.BLOCKING_HELPER_DISMISSED;

    private OnClickListener mOnKeepShowing = v -> {
        mExitReason = NotificationCounters.BLOCKING_HELPER_KEEP_SHOWING;
        if (mIsForBlockingHelper) {
            closeControls(v);
            mMetricsLogger.write(getLogMaker().setCategory(
                    MetricsEvent.NOTIFICATION_BLOCKING_HELPER)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .setSubtype(MetricsEvent.BLOCKING_HELPER_CLICK_STAY_SILENT));
        }
    };

    private OnClickListener mOnAlert = v -> {
        mExitReason = NotificationCounters.BLOCKING_HELPER_KEEP_SHOWING;
        mChosenImportance = IMPORTANCE_DEFAULT;
        updateButtonsAndHelpText(ACTION_ALERT);
    };

    private OnClickListener mOnDismissSettings = v -> {
        closeControls(v);
    };

    private OnClickListener mOnDeliverSilently = v -> {
        handleSaveImportance(
                ACTION_DELIVER_SILENTLY, MetricsEvent.BLOCKING_HELPER_CLICK_STAY_SILENT);
        if (!mIsForBlockingHelper) {
            updateButtonsAndHelpText(ACTION_DELIVER_SILENTLY);
        }
    };

    private OnClickListener mOnStopOrMinimizeNotifications = v -> {
        handleSaveImportance(ACTION_BLOCK, MetricsEvent.BLOCKING_HELPER_CLICK_BLOCKED);
        if (!mIsForBlockingHelper) {
            updateButtonsAndHelpText(ACTION_BLOCK);
        }
    };

    private void handleSaveImportance(int action, int metricsSubtype) {
        Runnable saveImportance = () -> {
            saveImportanceAndExitReason(action);
            if (mIsForBlockingHelper) {
                swapContent(action, true /* animate */);
                mMetricsLogger.write(getLogMaker()
                        .setCategory(MetricsEvent.NOTIFICATION_BLOCKING_HELPER)
                        .setType(MetricsEvent.TYPE_ACTION)
                        .setSubtype(metricsSubtype));
            }
        };
        if (mCheckSaveListener != null) {
            mCheckSaveListener.checkSave(saveImportance, mSbn);
        } else {
            saveImportance.run();
        }
    }

    private OnClickListener mOnUndo = v -> {
        // Reset exit counter that we'll log and record an undo event separately (not an exit event)
        mExitReason = NotificationCounters.BLOCKING_HELPER_DISMISSED;
        if (mIsForBlockingHelper) {
            logBlockingHelperCounter(NotificationCounters.BLOCKING_HELPER_UNDO);
            mMetricsLogger.write(getLogMaker().setCategory(
                    MetricsEvent.NOTIFICATION_BLOCKING_HELPER)
                    .setType(MetricsEvent.TYPE_DISMISS)
                    .setSubtype(MetricsEvent.BLOCKING_HELPER_CLICK_UNDO));
        } else {
            mMetricsLogger.write(importanceChangeLogMaker().setType(MetricsEvent.TYPE_DISMISS));
        }
        saveImportanceAndExitReason(ACTION_UNDO);
        swapContent(ACTION_UNDO, true /* animate */);
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
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            int importance,
            boolean wasShownHighPriority)
            throws RemoteException {
        bindNotification(pm, iNotificationManager, pkg, notificationChannel,
                numUniqueChannelsInRow, sbn, checkSaveListener, onSettingsClick,
                onAppSettingsClick, isDeviceProvisioned, isNonblockable,
                false /* isBlockingHelper */, false /* isUserSentimentNegative */,
                importance, wasShownHighPriority);
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
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            boolean isForBlockingHelper,
            boolean isUserSentimentNegative,
            int importance,
            boolean wasShownHighPriority)
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
        mStartingChannelImportance = mSingleNotificationChannel.getImportance();
        mWasShownHighPriority = wasShownHighPriority;
        mIsNonblockable = isNonblockable;
        mIsForeground =
                (mSbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        mIsForBlockingHelper = isForBlockingHelper;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;

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

        mMetricsLogger.write(notificationControlsLogMaker());
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

        // Delegate
        bindDelegate();

        // Settings button.
        final View settingsButton = findViewById(R.id.info);
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
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

    private void bindPrompt() throws RemoteException {
        final TextView blockPrompt = findViewById(R.id.block_prompt);
        bindName();
        bindGroup();
        if (mIsNonblockable) {
            blockPrompt.setText(R.string.notification_unblockable_desc);
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

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);
        TextView dividerView = findViewById(R.id.pkg_divider);

        CharSequence delegatePkg = null;
        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            ApplicationInfo info;
            try {
                info = mPm.getApplicationInfo(
                        mDelegatePkg,
                        PackageManager.MATCH_UNINSTALLED_PACKAGES
                                | PackageManager.MATCH_DISABLED_COMPONENTS
                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                                | PackageManager.MATCH_DIRECT_BOOT_AWARE);
                if (info != null) {
                    delegatePkg = String.valueOf(mPm.getApplicationLabel(info));
                }
            } catch (PackageManager.NameNotFoundException e) { }
        }
        if (delegatePkg != null) {
            delegateView.setText(mContext.getResources().getString(
                    R.string.notification_delegate_header, delegatePkg));
            delegateView.setVisibility(View.VISIBLE);
            dividerView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
            dividerView.setVisibility(View.GONE);
        }
    }

    private void bindGroup() throws RemoteException {
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
    }


    @VisibleForTesting
    void logBlockingHelperCounter(String counterTag) {
        if (mIsForBlockingHelper) {
            mMetricsLogger.count(counterTag, 1);
        }
    }

    private boolean hasImportanceChanged() {
        return mSingleNotificationChannel != null
                && mChosenImportance != null
                && (mStartingChannelImportance != mChosenImportance
                || (mWasShownHighPriority && mChosenImportance < IMPORTANCE_DEFAULT)
                || (!mWasShownHighPriority && mChosenImportance >= IMPORTANCE_DEFAULT));
    }

    private void saveImportance() {
        if (!mIsNonblockable
                || mExitReason != NotificationCounters.BLOCKING_HELPER_STOP_NOTIFICATIONS) {
            if (mChosenImportance == null) {
                mChosenImportance = mStartingChannelImportance;
            }
            updateImportance();
        }
    }

    /**
     * Commits the updated importance values on the background thread.
     */
    private void updateImportance() {
        if (mChosenImportance != null) {
            mMetricsLogger.write(importanceChangeLogMaker());

            Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
            bgHandler.post(
                    new UpdateImportanceRunnable(mINotificationManager, mPackageName, mAppUid,
                            mNumUniqueChannelsInRow == 1 ? mSingleNotificationChannel : null,
                            mStartingChannelImportance, mChosenImportance));
        }
    }

    private void bindButtons() {
        findViewById(R.id.undo).setOnClickListener(mOnUndo);

        boolean showInterruptivenessSettings =
                !mIsNonblockable
                        && !mIsForeground
                        && !mIsForBlockingHelper
                        && NotificationUtils.useNewInterruptionModel(mContext);
        if (showInterruptivenessSettings) {
            findViewById(R.id.block_or_minimize).setVisibility(GONE);
            findViewById(R.id.interruptiveness_settings).setVisibility(VISIBLE);
            View done = findViewById(R.id.done_button);
            done.setOnClickListener(mOnDismissSettings);
            View block = findViewById(R.id.int_block_wrapper);
            View silent = findViewById(R.id.int_silent_wrapper);
            View alert = findViewById(R.id.int_alert_wrapper);
            block.setOnClickListener(mOnStopOrMinimizeNotifications);
            silent.setOnClickListener(mOnDeliverSilently);
            alert.setOnClickListener(mOnAlert);
            if (mNotificationBlockState != ACTION_NONE) {
                updateButtonsAndHelpText(mNotificationBlockState);
            } else if (mWasShownHighPriority) {
                updateButtonsAndHelpText(ACTION_ALERT);
            } else {
                updateButtonsAndHelpText(ACTION_DELIVER_SILENTLY);
            }
        } else {
            findViewById(R.id.block_or_minimize).setVisibility(VISIBLE);
            findViewById(R.id.interruptiveness_settings).setVisibility(GONE);
            View block = findViewById(R.id.block);
            TextView done = findViewById(R.id.done);
            View minimize = findViewById(R.id.minimize);
            View deliverSilently = findViewById(R.id.deliver_silently);


            block.setOnClickListener(mOnStopOrMinimizeNotifications);
            done.setOnClickListener(mOnKeepShowing);
            minimize.setOnClickListener(mOnStopOrMinimizeNotifications);
            deliverSilently.setOnClickListener(mOnDeliverSilently);

            if (mIsNonblockable) {
                done.setText(android.R.string.ok);
                block.setVisibility(GONE);
                minimize.setVisibility(GONE);
                deliverSilently.setVisibility(GONE);
            } else if (mIsForeground) {
                block.setVisibility(GONE);
                minimize.setVisibility(VISIBLE);
            } else {
                block.setVisibility(VISIBLE);
                minimize.setVisibility(GONE);
            }

            // Set up app settings link (i.e. Customize)
            View settingsLinkView = findViewById(R.id.app_settings);
            Intent settingsIntent = getAppSettingsIntent(mPm, mPackageName,
                    mSingleNotificationChannel,
                    mSbn.getId(), mSbn.getTag());
            if (!mIsForBlockingHelper
                    && settingsIntent != null
                    && !TextUtils.isEmpty(mSbn.getNotification().getSettingsText())) {
                settingsLinkView.setVisibility(VISIBLE);
                settingsLinkView.setOnClickListener((View view) -> {
                    mAppSettingsClickListener.onClick(view, settingsIntent);
                });
            } else {
                settingsLinkView.setVisibility(View.GONE);
            }
        }
    }

    private void updateButtonsAndHelpText(int blockState) {
        mNotificationBlockState = blockState;
        ImageView block = findViewById(R.id.int_block);
        ImageView silent = findViewById(R.id.int_silent);
        ImageView alert = findViewById(R.id.int_alert);
        TextView hintText = findViewById(R.id.hint_text);
        switch (blockState) {
            case ACTION_BLOCK:
                block.setBackgroundResource(R.drawable.circle_blue_40dp);
                block.setColorFilter(Color.WHITE);
                silent.setBackgroundResource(R.drawable.circle_white_40dp);
                silent.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                alert.setBackgroundResource(R.drawable.circle_white_40dp);
                alert.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                hintText.setText(R.string.hint_text_block);
                break;
            case ACTION_DELIVER_SILENTLY:
                silent.setBackgroundResource(R.drawable.circle_blue_40dp);
                silent.setColorFilter(Color.WHITE);
                block.setBackgroundResource(R.drawable.circle_white_40dp);
                block.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                alert.setBackgroundResource(R.drawable.circle_white_40dp);
                alert.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                hintText.setText(R.string.hint_text_silent);
                break;
            case ACTION_ALERT:
                alert.setBackgroundResource(R.drawable.circle_blue_40dp);
                alert.setColorFilter(Color.WHITE);
                block.setBackgroundResource(R.drawable.circle_white_40dp);
                block.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                silent.setBackgroundResource(R.drawable.circle_white_40dp);
                silent.setColorFilter(getResources().getColor(R.color.GM2_grey_400));
                hintText.setText(R.string.hint_text_alert);
                break;
        }
    }

    private void saveImportanceAndExitReason(@NotificationInfoAction int action) {
        switch (action) {
            case ACTION_UNDO:
                mChosenImportance = mStartingChannelImportance;
                break;
            case ACTION_DELIVER_SILENTLY:
                mExitReason = NotificationCounters.BLOCKING_HELPER_DELIVER_SILENTLY;
                mChosenImportance = IMPORTANCE_LOW;
                break;
            case ACTION_TOGGLE_SILENT:
                mExitReason = NotificationCounters.BLOCKING_HELPER_TOGGLE_SILENT;
                if (mWasShownHighPriority) {
                    mChosenImportance = IMPORTANCE_LOW;
                } else {
                    mChosenImportance = IMPORTANCE_DEFAULT;
                }
                break;
            case ACTION_BLOCK:
                mExitReason = NotificationCounters.BLOCKING_HELPER_STOP_NOTIFICATIONS;
                if (mIsForeground) {
                    mChosenImportance = IMPORTANCE_MIN;
                } else {
                    mChosenImportance = IMPORTANCE_NONE;
                }
                break;
            default:
                throw new IllegalArgumentException();
        }
    }

    private void swapContent(@NotificationInfoAction int action, boolean animate) {
        if (mExpandAnimation != null) {
            mExpandAnimation.cancel();
        }

        View prompt = findViewById(R.id.prompt);
        ViewGroup confirmation = findViewById(R.id.confirmation);
        TextView confirmationText = findViewById(R.id.confirmation_text);
        View header = findViewById(R.id.header);

        saveImportanceAndExitReason(action);

        switch (action) {
            case ACTION_UNDO:
                break;
            case ACTION_DELIVER_SILENTLY:
                confirmationText.setText(R.string.notification_channel_silenced);
                break;
            case ACTION_TOGGLE_SILENT:
                if (mWasShownHighPriority) {
                    confirmationText.setText(R.string.notification_channel_silenced);
                } else {
                    confirmationText.setText(R.string.notification_channel_unsilenced);
                }
                break;
            case ACTION_BLOCK:
                if (mIsForeground) {
                    confirmationText.setText(R.string.notification_channel_minimized);
                } else {
                    confirmationText.setText(R.string.notification_channel_disabled);
                }
                break;
            default:
                throw new IllegalArgumentException();
        }

        boolean isUndo = action == ACTION_UNDO;

        prompt.setVisibility(isUndo ? VISIBLE : GONE);
        confirmation.setVisibility(isUndo ? GONE : VISIBLE);
        header.setVisibility(isUndo ? VISIBLE : GONE);

        if (animate) {
            ObjectAnimator promptAnim = ObjectAnimator.ofFloat(prompt, View.ALPHA,
                    prompt.getAlpha(), isUndo ? 1f : 0f);
            promptAnim.setInterpolator(isUndo ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT);
            ObjectAnimator confirmAnim = ObjectAnimator.ofFloat(confirmation, View.ALPHA,
                    confirmation.getAlpha(), isUndo ? 0f : 1f);
            confirmAnim.setInterpolator(isUndo ? Interpolators.ALPHA_OUT : Interpolators.ALPHA_IN);

            mExpandAnimation = new AnimatorSet();
            mExpandAnimation.playTogether(promptAnim, confirmAnim);
            mExpandAnimation.setDuration(150);
            mExpandAnimation.addListener(new AnimatorListenerAdapter() {
                boolean mCancelled = false;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        prompt.setVisibility(isUndo ? VISIBLE : GONE);
                        confirmation.setVisibility(isUndo ? GONE : VISIBLE);
                    }
                }
            });
            mExpandAnimation.start();
        }

        // Since we're swapping/update the content, reset the timeout so the UI can't close
        // immediately after the update.
        if (mGutsContainer != null) {
            mGutsContainer.resetFalsingCheck();
        }
    }

    @Override
    public void onFinishedClosing() {
        if (mChosenImportance != null) {
            mStartingChannelImportance = mChosenImportance;
        }
        mExitReason = NotificationCounters.BLOCKING_HELPER_DISMISSED;

        View prompt = findViewById(R.id.prompt);
        ViewGroup confirmation = findViewById(R.id.confirmation);
        View header = findViewById(R.id.header);
        prompt.setVisibility(VISIBLE);
        prompt.setAlpha(1f);
        confirmation.setVisibility(GONE);
        confirmation.setAlpha(1f);
        header.setVisibility(VISIBLE);
        header.setAlpha(1f);

        mMetricsLogger.write(notificationControlsLogMaker().setType(MetricsEvent.TYPE_CLOSE));
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

    /**
     * Returns a LogMaker with all available notification information.
     * Caller should set category, type, and maybe subtype, before passing it to mMetricsLogger.
     * @return LogMaker
     */
    private LogMaker getLogMaker() {
        // The constructor requires a category, so also do it in the other branch for consistency.
        return mSbn == null ? new LogMaker(MetricsEvent.NOTIFICATION_BLOCKING_HELPER)
                : mSbn.getLogMaker().setCategory(MetricsEvent.NOTIFICATION_BLOCKING_HELPER);
    }

    /**
     * Returns an initialized LogMaker for logging importance changes.
     * The caller may override the type before passing it to mMetricsLogger.
     * @return LogMaker
     */
    private LogMaker importanceChangeLogMaker() {
        Integer chosenImportance =
                mChosenImportance != null ? mChosenImportance : mStartingChannelImportance;
        return getLogMaker().setCategory(MetricsEvent.ACTION_SAVE_IMPORTANCE)
                .setType(MetricsEvent.TYPE_ACTION)
                .setSubtype(chosenImportance - mStartingChannelImportance);
    }

    /**
     * Returns an initialized LogMaker for logging open/close of the info display.
     * The caller may override the type before passing it to mMetricsLogger.
     * @return LogMaker
     */
    private LogMaker notificationControlsLogMaker() {
        return getLogMaker().setCategory(MetricsEvent.ACTION_NOTE_CONTROLS)
                .setType(MetricsEvent.TYPE_OPEN)
                .setSubtype(mIsForBlockingHelper ? MetricsEvent.BLOCKING_HELPER_DISPLAY
                        : MetricsEvent.BLOCKING_HELPER_UNKNOWN);
    }
}
