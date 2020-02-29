/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.app.Notification.EXTRA_IS_GROUP_CONVERSATION;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.IMPORTANCE_UNSPECIFIED;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_CACHED;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC;
import static android.content.pm.LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED;
import static android.provider.Settings.Secure.BUBBLE_IMPORTANT_CONVERSATIONS;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.settingslib.utils.ThreadUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.phone.ShadeController;

import java.lang.annotation.Retention;
import java.util.Arrays;
import java.util.List;

/**
 * The guts of a conversation notification revealed when performing a long press.
 */
public class NotificationConversationInfo extends LinearLayout implements
        NotificationGuts.GutsContent {
    private static final String TAG = "ConversationGuts";


    private INotificationManager mINotificationManager;
    private LauncherApps mLauncherApps;
    ShortcutManager mShortcutManager;
    private PackageManager mPm;
    private VisualStabilityManager mVisualStabilityManager;
    private ShadeController mShadeController;
    private ConversationIconFactory mIconFactory;

    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private NotificationChannel mNotificationChannel;
    private ShortcutInfo mShortcutInfo;
    private String mConversationId;
    private NotificationEntry mEntry;
    private StatusBarNotification mSbn;
    private boolean mIsDeviceProvisioned;
    private boolean mStartedAsBubble;
    private boolean mIsBubbleable;

    private @Action int mSelectedAction = -1;

    private OnSnoozeClickListener mOnSnoozeClickListener;
    private OnSettingsClickListener mOnSettingsClickListener;
    private NotificationGuts mGutsContainer;
    private BubbleController mBubbleController;

    @VisibleForTesting
    boolean mSkipPost = false;

    @Retention(SOURCE)
    @IntDef({ACTION_BUBBLE, ACTION_HOME, ACTION_FAVORITE, ACTION_SNOOZE, ACTION_MUTE,
            ACTION_UNBUBBLE, ACTION_SETTINGS})
    private @interface Action {}
    static final int ACTION_BUBBLE = 0;
    static final int ACTION_HOME = 1;
    static final int ACTION_FAVORITE = 2;
    static final int ACTION_SNOOZE = 3;
    static final int ACTION_MUTE = 4;
    static final int ACTION_SETTINGS = 5;
    static final int ACTION_UNBUBBLE = 6;

    private OnClickListener mOnBubbleClick = v -> {
        mSelectedAction = mStartedAsBubble ? ACTION_UNBUBBLE : ACTION_BUBBLE;
        if (mStartedAsBubble) {
            mBubbleController.onUserDemotedBubbleFromNotification(mEntry);
        } else {
            mBubbleController.onUserCreatedBubbleFromNotification(mEntry);
        }
        closeControls(v, true);
    };

    private OnClickListener mOnHomeClick = v -> {
        mSelectedAction = ACTION_HOME;
        mShortcutManager.requestPinShortcut(mShortcutInfo, null);
        mShadeController.animateCollapsePanels();
        closeControls(v, true);
    };

    private OnClickListener mOnFavoriteClick = v -> {
        mSelectedAction = ACTION_FAVORITE;
        updateChannel();

    };

    private OnClickListener mOnSnoozeClick = v -> {
        mSelectedAction = ACTION_SNOOZE;
        mOnSnoozeClickListener.onClick(v, 1);
        closeControls(v, true);
    };

    private OnClickListener mOnMuteClick = v -> {
        mSelectedAction = ACTION_MUTE;
        updateChannel();
    };

    public NotificationConversationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    public interface OnSnoozeClickListener {
        void onClick(View v, int hoursToSnooze);
    }

    public void bindNotification(
            ShortcutManager shortcutManager,
            LauncherApps launcherApps,
            PackageManager pm,
            INotificationManager iNotificationManager,
            VisualStabilityManager visualStabilityManager,
            String pkg,
            NotificationChannel notificationChannel,
            NotificationEntry entry,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            OnSnoozeClickListener onSnoozeClickListener,
            ConversationIconFactory conversationIconFactory,
            boolean isDeviceProvisioned) {
        mSelectedAction = -1;
        mINotificationManager = iNotificationManager;
        mVisualStabilityManager = visualStabilityManager;
        mBubbleController = Dependency.get(BubbleController.class);
        mPackageName = pkg;
        mEntry = entry;
        mSbn = entry.getSbn();
        mPm = pm;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mNotificationChannel = notificationChannel;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mOnSnoozeClickListener = onSnoozeClickListener;
        mShadeController = Dependency.get(ShadeController.class);
        mIconFactory = conversationIconFactory;

        mShortcutManager = shortcutManager;
        mLauncherApps = launcherApps;
        mConversationId = mNotificationChannel.getConversationId();
        if (TextUtils.isEmpty(mNotificationChannel.getConversationId())) {
            mConversationId = mSbn.getShortcutId(mContext);
        }
        if (TextUtils.isEmpty(mConversationId)) {
            throw new IllegalArgumentException("Does not have required information");
        }
        mShortcutInfo = entry.getRanking().getShortcutInfo();

        mIsBubbleable = mEntry.getBubbleMetadata() != null
            && Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.NOTIFICATION_BUBBLES, 0) == 1;
        mStartedAsBubble = mEntry.isBubble();

        createConversationChannelIfNeeded();

        bindHeader();
        bindActions();

    }

    void createConversationChannelIfNeeded() {
        // If this channel is not already a customized conversation channel, create
        // a custom channel
        if (TextUtils.isEmpty(mNotificationChannel.getConversationId())) {
            try {
                // TODO: remove
                mNotificationChannel.setName(mContext.getString(
                        R.string.notification_summary_message_format,
                        getName(), mNotificationChannel.getName()));
                mINotificationManager.createConversationNotificationChannelForPackage(
                        mPackageName, mAppUid, mSbn.getKey(), mNotificationChannel,
                        mConversationId);
                mNotificationChannel = mINotificationManager.getConversationNotificationChannel(
                        mContext.getOpPackageName(), UserHandle.getUserId(mAppUid), mPackageName,
                        mNotificationChannel.getId(), false, mConversationId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not create conversation channel", e);
            }
        }
    }

    private void bindActions() {
        // TODO: figure out what should happen for non-configurable channels

        Button bubble = findViewById(R.id.bubble);
        bubble.setVisibility(mIsBubbleable ? VISIBLE : GONE);
        bubble.setOnClickListener(mOnBubbleClick);
        if (mStartedAsBubble) {
            bubble.setText(R.string.notification_conversation_unbubble);
        } else {
            bubble.setText(R.string.notification_conversation_bubble);
        }

        Button home = findViewById(R.id.home);
        home.setOnClickListener(mOnHomeClick);
        home.setVisibility(mShortcutInfo != null
                && mShortcutManager.isRequestPinShortcutSupported()
                ? VISIBLE : GONE);

        View favorite = findViewById(R.id.fave);
        favorite.setOnClickListener(mOnFavoriteClick);

        Button snooze = findViewById(R.id.snooze);
        snooze.setOnClickListener(mOnSnoozeClick);

        View mute = findViewById(R.id.mute);
        mute.setOnClickListener(mOnMuteClick);

        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(getSettingsOnClickListener());
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);

        updateToggleActions();
    }

    private void bindHeader() {
        bindConversationDetails();

        // Delegate
        bindDelegate();
    }

    private OnClickListener getSettingsOnClickListener() {
        if (mAppUid >= 0 && mOnSettingsClickListener != null && mIsDeviceProvisioned) {
            final int appUidF = mAppUid;
            return ((View view) -> {
                mOnSettingsClickListener.onClick(view, mNotificationChannel, appUidF);
            });
        }
        return null;
    }

    private void bindConversationDetails() {
        final TextView channelName = findViewById(R.id.parent_channel_name);
        channelName.setText(mNotificationChannel.getName());

        bindGroup();
        // TODO: bring back when channel name does not include name
        // bindName();
        bindPackage();
        bindIcon();

    }

    private void bindIcon() {
        ImageView image = findViewById(R.id.conversation_icon);
        if (mShortcutInfo != null) {
            image.setImageDrawable(mIconFactory.getConversationDrawable(
                    mShortcutInfo, mPackageName, mAppUid,
                    mNotificationChannel.isImportantConversation()));
        } else {
            if (mSbn.getNotification().extras.getBoolean(EXTRA_IS_GROUP_CONVERSATION, false)) {
                // TODO: maybe use a generic group icon, or a composite of recent senders
                image.setImageDrawable(mPm.getDefaultActivityIcon());
            } else {
                final List<Notification.MessagingStyle.Message> messages =
                        Notification.MessagingStyle.Message.getMessagesFromBundleArray(
                                (Parcelable[]) mSbn.getNotification().extras.get(
                                        Notification.EXTRA_MESSAGES));

                final Notification.MessagingStyle.Message latestMessage =
                        Notification.MessagingStyle.findLatestIncomingMessage(messages);
                Icon personIcon = latestMessage.getSenderPerson().getIcon();
                if (personIcon != null) {
                    image.setImageIcon(latestMessage.getSenderPerson().getIcon());
                } else {
                    // TODO: choose something better
                    image.setImageDrawable(mPm.getDefaultActivityIcon());
                }
            }
        }
    }

    private void bindName() {
        TextView name = findViewById(R.id.name);
        name.setText(getName());
    }

    private String getName() {
        if (mShortcutInfo != null) {
            return mShortcutInfo.getShortLabel().toString();
        } else {
            Bundle extras = mSbn.getNotification().extras;
            String nameString = extras.getString(Notification.EXTRA_CONVERSATION_TITLE);
            if (TextUtils.isEmpty(nameString)) {
                nameString = extras.getString(Notification.EXTRA_TITLE);
            }
            return nameString;
        }
    }

    private void bindPackage() {
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
            }
        } catch (PackageManager.NameNotFoundException e) {
        }
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);
    }

    private boolean bubbleImportantConversations() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                BUBBLE_IMPORTANT_CONVERSATIONS, 1) == 1;
    }

    private void bindDelegate() {
        TextView delegateView = findViewById(R.id.delegate_name);

        if (!TextUtils.equals(mPackageName, mDelegatePkg)) {
            // this notification was posted by a delegate!
            delegateView.setVisibility(View.VISIBLE);
        } else {
            delegateView.setVisibility(View.GONE);
        }
    }

    private void bindGroup() {
        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mNotificationChannel != null && mNotificationChannel.getGroup() != null) {
            try {
                final NotificationChannelGroup notificationChannelGroup =
                        mINotificationManager.getNotificationChannelGroupForPackage(
                                mNotificationChannel.getGroup(), mPackageName, mAppUid);
                if (notificationChannelGroup != null) {
                    groupName = notificationChannelGroup.getName();
                }
            } catch (RemoteException e) {
            }
        }
        TextView groupNameView = findViewById(R.id.group_name);
        View groupDivider = findViewById(R.id.group_divider);
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
            groupDivider.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
            groupDivider.setVisibility(GONE);
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

    @Override
    public void onFinishedClosing() {
        // TODO: do we need to do anything here?
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

    private void updateToggleActions() {
        ImageButton favorite = findViewById(R.id.fave);
        if (mNotificationChannel.isImportantConversation()) {
            favorite.setContentDescription(
                    mContext.getString(R.string.notification_conversation_favorite));
            favorite.setImageResource(R.drawable.ic_important);
        } else {
            favorite.setContentDescription(
                    mContext.getString(R.string.notification_conversation_unfavorite));
            favorite.setImageResource(R.drawable.ic_important_outline);
        }

        ImageButton mute = findViewById(R.id.mute);
        if (mNotificationChannel.getImportance() >= IMPORTANCE_DEFAULT
                || mNotificationChannel.getImportance() == IMPORTANCE_UNSPECIFIED) {
            mute.setContentDescription(
                    mContext.getString(R.string.notification_conversation_unmute));
            mute.setImageResource(R.drawable.ic_notifications_alert);
        } else {
            mute.setContentDescription(
                    mContext.getString(R.string.notification_conversation_mute));
            mute.setImageResource(R.drawable.ic_notifications_silence);
        }

        // update icon in case importance has changed
        bindIcon();
    }

    private void updateChannel() {
        Handler bgHandler = new Handler(Dependency.get(Dependency.BG_LOOPER));
        bgHandler.post(
                new UpdateChannelRunnable(mINotificationManager, mPackageName,
                        mAppUid, mSelectedAction, mNotificationChannel));
        mVisualStabilityManager.temporarilyAllowReordering();
    }

    /**
     * Closes the controls and commits the updated importance values (indirectly).
     *
     * <p><b>Note,</b> this will only get called once the view is dismissing. This means that the
     * user does not have the ability to undo the action anymore.
     */
    @VisibleForTesting
    void closeControls(View v, boolean save) {
        int[] parentLoc = new int[2];
        int[] targetLoc = new int[2];
        mGutsContainer.getLocationOnScreen(parentLoc);
        v.getLocationOnScreen(targetLoc);
        final int centerX = v.getWidth() / 2;
        final int centerY = v.getHeight() / 2;
        final int x = targetLoc[0] - parentLoc[0] + centerX;
        final int y = targetLoc[1] - parentLoc[1] + centerY;
        mGutsContainer.closeControls(x, y, save, false /* force */);
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
    public boolean shouldBeSaved() {
        // Toggle actions are already saved by the time the guts are closed; save for any other
        // taps
        return mSelectedAction > -1
                && mSelectedAction != ACTION_FAVORITE
                && mSelectedAction != ACTION_MUTE;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (save && mSelectedAction > -1) {
            updateChannel();
        }
        return false;
    }

    @Override
    public int getActualHeight() {
        return getHeight();
    }

    @VisibleForTesting
    public boolean isAnimating() {
        return false;
    }

    class UpdateChannelRunnable implements Runnable {

        private final INotificationManager mINotificationManager;
        private final String mAppPkg;
        private final int mAppUid;
        private  NotificationChannel mChannelToUpdate;
        private final @Action int mAction;

        public UpdateChannelRunnable(INotificationManager notificationManager,
                String packageName, int appUid, @Action int action,
                @NonNull NotificationChannel channelToUpdate) {
            mINotificationManager = notificationManager;
            mAppPkg = packageName;
            mAppUid = appUid;
            mChannelToUpdate = channelToUpdate;
            mAction = action;
        }

        @Override
        public void run() {
            try {
                boolean channelSettingChanged = mAction != ACTION_HOME && mAction != ACTION_SNOOZE;
                switch (mAction) {
                    case ACTION_BUBBLE:
                    case ACTION_UNBUBBLE:
                        boolean canBubble = mAction == ACTION_BUBBLE;
                        if (mChannelToUpdate.canBubble() != canBubble) {
                            channelSettingChanged = true;
                            mChannelToUpdate.setAllowBubbles(canBubble);
                        } else {
                            channelSettingChanged = false;
                        }
                        break;
                    case ACTION_FAVORITE:
                        mChannelToUpdate.setImportantConversation(
                                !mChannelToUpdate.isImportantConversation());
                        if (mChannelToUpdate.isImportantConversation()
                                && bubbleImportantConversations()) {
                            mChannelToUpdate.setAllowBubbles(true);
                        }
                        break;
                    case ACTION_MUTE:
                        if (mChannelToUpdate.getImportance() == IMPORTANCE_UNSPECIFIED
                                || mChannelToUpdate.getImportance() >= IMPORTANCE_DEFAULT) {
                            mChannelToUpdate.setImportance(IMPORTANCE_LOW);
                        } else {
                            mChannelToUpdate.setImportance(Math.max(
                                    mChannelToUpdate.getOriginalImportance(), IMPORTANCE_DEFAULT));
                        }
                        break;
                }

                if (channelSettingChanged) {
                    mINotificationManager.updateNotificationChannelForPackage(
                            mAppPkg, mAppUid, mChannelToUpdate);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to update notification channel", e);
            }
            ThreadUtils.postOnMainThread(() -> updateToggleActions());
        }
    }
}
