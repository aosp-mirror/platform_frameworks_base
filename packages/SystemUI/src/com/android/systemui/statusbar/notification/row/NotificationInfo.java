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

import static android.app.Notification.EXTRA_BUILDER_APPLICATION_INFO;
import static android.app.NotificationChannel.SYSTEM_RESERVED_IDS;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.service.notification.Adjustment.KEY_SUMMARIZATION;
import static android.service.notification.Adjustment.KEY_TYPE;

import static com.android.app.animation.Interpolators.FAST_OUT_SLOW_IN;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.Flags;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.RemoteException;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;
import android.text.Html;
import android.text.TextUtils;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.systemui.Dependency;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NmSummarizationUiFlag;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.lang.annotation.Retention;
import java.util.List;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "InfoGuts";
    private int mActualHeight;

    @IntDef(prefix = { "ACTION_" }, value = {
            ACTION_NONE,
            ACTION_TOGGLE_ALERT,
            ACTION_TOGGLE_SILENT,
    })
    public @interface NotificationInfoAction {
    }

    public static final int ACTION_NONE = 0;
    // standard controls
    static final int ACTION_TOGGLE_SILENT = 2;
    // standard controls
    private static final int ACTION_TOGGLE_ALERT = 5;

    private TextView mPriorityDescriptionView;
    private TextView mSilentDescriptionView;
    private TextView mAutomaticDescriptionView;

    private INotificationManager mINotificationManager;
    private OnUserInteractionCallback mOnUserInteractionCallback;
    private PackageManager mPm;
    private MetricsLogger mMetricsLogger;
    private ChannelEditorDialogController mChannelEditorDialogController;
    private AssistantFeedbackController mAssistantFeedbackController;

    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private NotificationChannel mSingleNotificationChannel;
    private int mStartingChannelImportance;
    private boolean mWasShownHighPriority;
    private boolean mPressedApply;
    private boolean mPresentingChannelEditorDialog = false;
    private boolean mShowAutomaticSetting;

    /**
     * The last importance level chosen by the user.  Null if the user has not chosen an importance
     * level; non-null once the user takes an action which indicates an explicit preference.
     */
    @Nullable private Integer mChosenImportance;
    private boolean mIsAutomaticChosen;
    private boolean mIsSingleDefaultChannel;
    private boolean mIsNonblockable;
    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    private boolean mIsDeviceProvisioned;
    private boolean mIsSystemRegisteredCall;

    private OnSettingsClickListener mOnSettingsClickListener;
    private OnAppSettingsClickListener mAppSettingsClickListener;
    private OnFeedbackClickListener mFeedbackClickListener;
    private NotificationGuts mGutsContainer;
    private Drawable mPkgIcon;
    private UiEventLogger mUiEventLogger;

    @VisibleForTesting
    boolean mSkipPost = false;

    // used by standard ui
    private OnClickListener mOnAutomatic = v -> {
        mIsAutomaticChosen = true;
        applyAlertingBehavior(BEHAVIOR_AUTOMATIC, true /* userTriggered */);
    };

    // used by standard ui
    private OnClickListener mOnAlert = v -> {
        mChosenImportance = IMPORTANCE_DEFAULT;
        mIsAutomaticChosen = false;
        applyAlertingBehavior(BEHAVIOR_ALERTING, true /* userTriggered */);
    };

    // used by standard ui
    private OnClickListener mOnSilent = v -> {
        mChosenImportance = IMPORTANCE_LOW;
        mIsAutomaticChosen = false;
        applyAlertingBehavior(BEHAVIOR_SILENT, true /* userTriggered */);
    };

    // used by standard ui
    private OnClickListener mOnDismissSettings = v -> {
        mPressedApply = true;
        mGutsContainer.closeControls(v, /* save= */ true);
    };

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mPriorityDescriptionView = findViewById(R.id.alert_summary);
        mSilentDescriptionView = findViewById(R.id.silence_summary);
        mAutomaticDescriptionView = findViewById(R.id.automatic_summary);
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

    public interface OnFeedbackClickListener {
        void onClick(View v, Intent intent);
    }

    public void bindNotification(
            PackageManager pm,
            INotificationManager iNotificationManager,
            OnUserInteractionCallback onUserInteractionCallback,
            ChannelEditorDialogController channelEditorDialogController,
            String pkg,
            NotificationChannel notificationChannel,
            NotificationEntry entry,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            OnFeedbackClickListener onFeedbackClickListener,
            UiEventLogger uiEventLogger,
            boolean isDeviceProvisioned,
            boolean isNonblockable,
            boolean wasShownHighPriority,
            AssistantFeedbackController assistantFeedbackController,
            MetricsLogger metricsLogger, OnClickListener onCloseClick)
            throws RemoteException {
        mINotificationManager = iNotificationManager;
        mMetricsLogger = metricsLogger;
        mOnUserInteractionCallback = onUserInteractionCallback;
        mChannelEditorDialogController = channelEditorDialogController;
        mAssistantFeedbackController = assistantFeedbackController;
        mPackageName = pkg;
        mEntry = entry;
        mSbn = entry.getSbn();
        mPm = pm;
        mAppSettingsClickListener = onAppSettingsClick;
        mFeedbackClickListener = onFeedbackClickListener;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mSingleNotificationChannel = notificationChannel;
        mStartingChannelImportance = mSingleNotificationChannel.getImportance();
        mWasShownHighPriority = wasShownHighPriority;
        mIsNonblockable = isNonblockable;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mShowAutomaticSetting = mAssistantFeedbackController.isFeedbackEnabled();
        mUiEventLogger = uiEventLogger;

        mIsSystemRegisteredCall = mSbn.getNotification().isStyle(Notification.CallStyle.class)
                && mINotificationManager.isInCall(mSbn.getPackageName(), mSbn.getUid());

        int numTotalChannels = mINotificationManager.getNumNotificationChannelsForPackage(
                pkg, mAppUid, false /* includeDeleted */);
        mIsSingleDefaultChannel = mSingleNotificationChannel.getId().equals(
                NotificationChannel.DEFAULT_CHANNEL_ID) && numTotalChannels == 1;
        mIsAutomaticChosen = getAlertingBehavior() == BEHAVIOR_AUTOMATIC;

        bindHeader();
        bindChannelDetails();

        bindInlineControls();

        logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_OPEN);
        mMetricsLogger.write(notificationControlsLogMaker());
    }

    private void bindInlineControls() {
        if (mIsSystemRegisteredCall) {
            findViewById(R.id.non_configurable_call_text).setVisibility(VISIBLE);
            findViewById(R.id.non_configurable_text).setVisibility(GONE);
            findViewById(R.id.non_configurable_multichannel_text).setVisibility(GONE);
            findViewById(R.id.interruptiveness_settings).setVisibility(GONE);
            ((TextView) findViewById(R.id.done)).setText(R.string.inline_done_button);
            findViewById(R.id.turn_off_notifications).setVisibility(GONE);
        } else if (mIsNonblockable) {
            findViewById(R.id.non_configurable_text).setVisibility(VISIBLE);
            findViewById(R.id.non_configurable_call_text).setVisibility(GONE);
            findViewById(R.id.non_configurable_multichannel_text).setVisibility(GONE);
            findViewById(R.id.interruptiveness_settings).setVisibility(GONE);
            ((TextView) findViewById(R.id.done)).setText(R.string.inline_done_button);
            findViewById(R.id.turn_off_notifications).setVisibility(GONE);
        } else {
            findViewById(R.id.non_configurable_call_text).setVisibility(GONE);
            findViewById(R.id.non_configurable_text).setVisibility(GONE);
            findViewById(R.id.non_configurable_multichannel_text).setVisibility(GONE);
            findViewById(R.id.interruptiveness_settings).setVisibility(VISIBLE);
        }

        View turnOffButton = findViewById(R.id.turn_off_notifications);
        turnOffButton.setOnClickListener(getTurnOffNotificationsClickListener());
        turnOffButton.setVisibility(turnOffButton.hasOnClickListeners() && !mIsNonblockable
                ? VISIBLE : GONE);

        View done = findViewById(R.id.done);
        done.setOnClickListener(mOnDismissSettings);
        done.setAccessibilityDelegate(mGutsContainer.getAccessibilityDelegate());

        View silent = findViewById(R.id.silence);
        View alert = findViewById(R.id.alert);
        silent.setOnClickListener(mOnSilent);
        alert.setOnClickListener(mOnAlert);

        View automatic = findViewById(R.id.automatic);
        if (mShowAutomaticSetting) {
            mAutomaticDescriptionView.setText(Html.fromHtml(mContext.getText(
                    mAssistantFeedbackController.getInlineDescriptionResource(mEntry)).toString()));
            automatic.setVisibility(VISIBLE);
            automatic.setOnClickListener(mOnAutomatic);
        } else {
            automatic.setVisibility(GONE);
        }

        int behavior = getAlertingBehavior();
        applyAlertingBehavior(behavior, false /* userTriggered */);
    }

    private void bindHeader() {
        // Package name
        mPkgIcon = null;
        // filled in if missing during notification inflation, which must have happened if
        // we have a notification to long press on
        ApplicationInfo info =
                mSbn.getNotification().extras.getParcelable(EXTRA_BUILDER_APPLICATION_INFO,
                        ApplicationInfo.class);
        if (info != null) {
            try {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                mPkgIcon = mPm.getApplicationIcon(info);
            } catch (Exception ignored) {}
        }
        if (mPkgIcon == null) {
            // app is gone, just show package name and generic icon
            mPkgIcon = mPm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkg_icon)).setImageDrawable(mPkgIcon);
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);

        // Delegate
        bindDelegate();


        if (Flags.notificationClassificationUi() &&
                SYSTEM_RESERVED_IDS.contains(mSingleNotificationChannel.getId())) {
            bindFeedback();
        } else {
            // Set up app settings link (i.e. Customize)
            View settingsLinkView = findViewById(R.id.app_settings);
            Intent settingsIntent = getAppSettingsIntent(mPm, mPackageName,
                    mSingleNotificationChannel,
                    mSbn.getId(), mSbn.getTag());
            if (settingsIntent != null
                    && !TextUtils.isEmpty(mSbn.getNotification().getSettingsText())) {
                settingsLinkView.setVisibility(VISIBLE);
                settingsLinkView.setOnClickListener((View view) -> {
                    mAppSettingsClickListener.onClick(view, settingsIntent);
                });
            } else {
                settingsLinkView.setVisibility(View.GONE);
            }
        }

        // System Settings button.
        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(getSettingsOnClickListener());
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);
    }

    private void bindFeedback() {
        View feedbackButton = findViewById(R.id.feedback);
        Intent intent = getAssistantFeedbackIntent(mINotificationManager, mPm, mEntry);
        if (!android.app.Flags.notificationClassificationUi() || intent == null) {
            feedbackButton.setVisibility(GONE);
        } else {
            feedbackButton.setVisibility(VISIBLE);
            feedbackButton.setOnClickListener((View v) -> {
                if (mFeedbackClickListener != null) {
                    mFeedbackClickListener.onClick(v, intent);
                }
            });
        }
    }

    public static Intent getAssistantFeedbackIntent(INotificationManager inm, PackageManager pm,
            NotificationEntry entry) {
        try {
            ComponentName assistant = inm.getAllowedNotificationAssistant();
            if (assistant == null) {
                return null;
            }
            Intent intent = new Intent(
                    NotificationAssistantService.ACTION_NOTIFICATION_ASSISTANT_FEEDBACK_SETTINGS)
                    .setPackage(assistant.getPackageName());
            final List<ResolveInfo> resolveInfos = pm.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
            );
            if (resolveInfos == null || resolveInfos.size() == 0 || resolveInfos.get(0) == null) {
                return null;
            }
            final ActivityInfo activityInfo = resolveInfos.get(0).activityInfo;
            intent.setClassName(activityInfo.packageName, activityInfo.name);

            intent.putExtra(NotificationAssistantService.EXTRA_NOTIFICATION_KEY, entry.getKey());
            intent.putExtra(NotificationAssistantService.EXTRA_NOTIFICATION_ADJUSTMENT,
                    entry.getRanking().getSummarization() != null ? KEY_SUMMARIZATION : KEY_TYPE);
            return intent;
        } catch (Exception e) {
            Slog.d(TAG, "no assistant?", e);
            return null;
        }
    }

    private OnClickListener getSettingsOnClickListener() {
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
            final int appUidF = mAppUid;
            return ((View view) -> {
                mOnSettingsClickListener.onClick(view, mSingleNotificationChannel, appUidF);
            });
        }
        return null;
    }

    private OnClickListener getTurnOffNotificationsClickListener() {
        return ((View view) -> {
            if (!mPresentingChannelEditorDialog && mChannelEditorDialogController != null) {
                mPresentingChannelEditorDialog = true;

                mChannelEditorDialogController.prepareDialogForApp(mAppName, mPackageName, mAppUid,
                        mSingleNotificationChannel, mPkgIcon, mOnSettingsClickListener);
                mChannelEditorDialogController.setOnFinishListener(() -> {
                    mPresentingChannelEditorDialog = false;
                    mGutsContainer.closeControls(this, false);
                });
                mChannelEditorDialogController.show();
            }
        });
    }

    private void bindChannelDetails() throws RemoteException {
        bindName();
        bindGroup();
    }

    private void bindName() {
        final TextView channelName = findViewById(R.id.channel_name);
        if (mIsSingleDefaultChannel) {
            channelName.setVisibility(View.GONE);
        } else {
            channelName.setText(mSingleNotificationChannel.getName());
        }
    }

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);

        CharSequence delegatePkg = null;
        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            delegateView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
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
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
        }
    }

    private void saveImportance() {
        if (!mIsNonblockable) {
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
            logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_SAVE_IMPORTANCE);
            mMetricsLogger.write(importanceChangeLogMaker());

            int newImportance = mChosenImportance;
            if (mStartingChannelImportance != IMPORTANCE_UNSPECIFIED) {
                if ((mWasShownHighPriority && mChosenImportance >= IMPORTANCE_DEFAULT)
                        || (!mWasShownHighPriority && mChosenImportance < IMPORTANCE_DEFAULT)) {
                    newImportance = mStartingChannelImportance;
                }
            }

            Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
            bgHandler.post(
                    new UpdateImportanceRunnable(mINotificationManager, mPackageName, mAppUid,
                            mSingleNotificationChannel,
                            mStartingChannelImportance, newImportance, mIsAutomaticChosen));
            mOnUserInteractionCallback.onImportanceChanged(mEntry);
        }
    }

    @Override
    public boolean post(Runnable action) {
        if (mSkipPost) {
            action.run();
            return true;
        } else {
            return super.post(action);
        }
    }

    private void applyAlertingBehavior(@AlertingBehavior int behavior, boolean userTriggered) {
        if (userTriggered) {
            TransitionSet transition = new TransitionSet();
            transition.setOrdering(TransitionSet.ORDERING_TOGETHER);
            transition.addTransition(new Fade(Fade.OUT))
                    .addTransition(new ChangeBounds())
                    .addTransition(
                            new Fade(Fade.IN)
                                    .setStartDelay(150)
                                    .setDuration(200)
                                    .setInterpolator(FAST_OUT_SLOW_IN));
            transition.setDuration(350);
            transition.setInterpolator(FAST_OUT_SLOW_IN);
            TransitionManager.beginDelayedTransition(this, transition);
        }

        View alert = findViewById(R.id.alert);
        View silence = findViewById(R.id.silence);
        View automatic = findViewById(R.id.automatic);

        switch (behavior) {
            case BEHAVIOR_ALERTING:
                mPriorityDescriptionView.setVisibility(VISIBLE);
                mSilentDescriptionView.setVisibility(GONE);
                mAutomaticDescriptionView.setVisibility(GONE);
                post(() -> {
                    alert.setSelected(true);
                    silence.setSelected(false);
                    automatic.setSelected(false);
                });
                break;

            case BEHAVIOR_SILENT:
                mSilentDescriptionView.setVisibility(VISIBLE);
                mPriorityDescriptionView.setVisibility(GONE);
                mAutomaticDescriptionView.setVisibility(GONE);
                post(() -> {
                    alert.setSelected(false);
                    silence.setSelected(true);
                    automatic.setSelected(false);
                });
                break;

            case BEHAVIOR_AUTOMATIC:
                mAutomaticDescriptionView.setVisibility(VISIBLE);
                mPriorityDescriptionView.setVisibility(GONE);
                mSilentDescriptionView.setVisibility(GONE);
                post(() -> {
                    automatic.setSelected(true);
                    alert.setSelected(false);
                    silence.setSelected(false);
                });
                break;

            default:
                throw new IllegalArgumentException("Unrecognized alerting behavior: " + behavior);
        }

        boolean isAChange = getAlertingBehavior() != behavior;
        TextView done = findViewById(R.id.done);
        done.setText(isAChange
                ? R.string.inline_ok_button
                : R.string.inline_done_button);
    }

    @Override
    public void onFinishedClosing() {
        bindInlineControls();

        logUiEvent(NotificationControlsEvent.NOTIFICATION_CONTROLS_CLOSE);
        mMetricsLogger.write(notificationControlsLogMaker().setType(MetricsEvent.TYPE_CLOSE));
    }

    @Override
    public boolean needsFalsingProtection() {
        return true;
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

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public boolean shouldBeSavedOnClose() {
        return mPressedApply;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (mPresentingChannelEditorDialog && mChannelEditorDialogController != null) {
            mPresentingChannelEditorDialog = false;
            // No need for the finish listener because we're closing
            mChannelEditorDialogController.setOnFinishListener(null);
            mChannelEditorDialogController.close();
        }

        // Save regardless of the importance so we can lock the importance field if the user wants
        // to keep getting notifications
        if (save) {
            saveImportance();
        }

        // Clear the selected importance when closing, so when when we open again,
        // we starts from a clean state.
        mChosenImportance = null;
        mPressedApply = false;

        return false;
    }

    @Override
    public int getActualHeight() {
        // Because we're animating the bounds, getHeight will return the small height at the
        // beginning of the animation. Instead we'd want it to already return the end value
        return mActualHeight;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        mActualHeight = getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return false;
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
        private final boolean mUnlockImportance;


        public UpdateImportanceRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Nullable NotificationChannel channelToUpdate,
                int currentImportance, int newImportance, boolean unlockImportance) {
            mINotificationManager = notificationManager;
            mPackageName = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mCurrentImportance = currentImportance;
            mNewImportance = newImportance;
            mUnlockImportance = unlockImportance;
        }

        @Override
        public void run() {
            try {
                if (mChannelToUpdate != null) {
                    if (mUnlockImportance) {
                        mINotificationManager.unlockNotificationChannel(
                                mPackageName, mAppUid, mChannelToUpdate.getId());
                    } else {
                        mChannelToUpdate.setImportance(mNewImportance);
                        mChannelToUpdate.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
                        mINotificationManager.updateNotificationChannelForPackage(
                                mPackageName, mAppUid, mChannelToUpdate);
                    }
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

    private void logUiEvent(NotificationControlsEvent event) {
        if (mSbn != null) {
            mUiEventLogger.logWithInstanceId(event,
                    mSbn.getUid(), mSbn.getPackageName(), mSbn.getInstanceId());
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
                .setSubtype(MetricsEvent.BLOCKING_HELPER_UNKNOWN);
    }

    private @AlertingBehavior int getAlertingBehavior() {
        if (mShowAutomaticSetting && !mSingleNotificationChannel.hasUserSetImportance()) {
            return BEHAVIOR_AUTOMATIC;
        }
        return mWasShownHighPriority ? BEHAVIOR_ALERTING : BEHAVIOR_SILENT;
    }

    @Retention(SOURCE)
    @IntDef({BEHAVIOR_ALERTING, BEHAVIOR_SILENT, BEHAVIOR_AUTOMATIC})
    private @interface AlertingBehavior {}
    private static final int BEHAVIOR_ALERTING = 0;
    private static final int BEHAVIOR_SILENT = 1;
    private static final int BEHAVIOR_AUTOMATIC = 2;
}
