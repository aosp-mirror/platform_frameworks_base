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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import java.lang.annotation.Retention;
import java.util.List;
import java.util.Set;

/**
 * The guts of a conversation notification that doesn't use valid shortcuts that is revealed when
 * performing a long press.
 */
public class PartialConversationInfo extends LinearLayout implements
        NotificationGuts.GutsContent {
    private static final String TAG = "PartialConvoGuts";

    private INotificationManager mINotificationManager;
    private PackageManager mPm;
    private String mPackageName;
    private String mAppName;
    private int mAppUid;
    private String mDelegatePkg;
    private NotificationChannel mNotificationChannel;
    private StatusBarNotification mSbn;
    private boolean mIsDeviceProvisioned;
    private boolean mIsNonBlockable;
    private Set<NotificationChannel> mUniqueChannelsInRow;
    private Drawable mPkgIcon;

    private @Action int mSelectedAction = -1;
    private boolean mPressedApply;
    private boolean mPresentingChannelEditorDialog = false;

    private NotificationInfo.OnSettingsClickListener mOnSettingsClickListener;
    private NotificationGuts mGutsContainer;
    private ChannelEditorDialogController mChannelEditorDialogController;

    @VisibleForTesting
    boolean mSkipPost = false;

    @Retention(SOURCE)
    @IntDef({ACTION_SETTINGS})
    private @interface Action {}
    static final int ACTION_SETTINGS = 5;

    private OnClickListener mOnDone = v -> {
        mPressedApply = true;
        mGutsContainer.closeControls(v, true);
    };

    public PartialConversationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void bindNotification(
            PackageManager pm,
            INotificationManager iNotificationManager,
            ChannelEditorDialogController channelEditorDialogController,
            String pkg,
            NotificationChannel notificationChannel,
            Set<NotificationChannel> uniqueChannelsInRow,
            NotificationEntry entry,
            NotificationInfo.OnSettingsClickListener onSettingsClick,
            boolean isDeviceProvisioned,
            boolean isNonBlockable) {
        mSelectedAction = -1;
        mINotificationManager = iNotificationManager;
        mPackageName = pkg;
        mSbn = entry.getSbn();
        mPm = pm;
        mAppName = mPackageName;
        mOnSettingsClickListener = onSettingsClick;
        mNotificationChannel = notificationChannel;
        mAppUid = mSbn.getUid();
        mDelegatePkg = mSbn.getOpPkg();
        mIsDeviceProvisioned = isDeviceProvisioned;
        mIsNonBlockable = isNonBlockable;
        mChannelEditorDialogController = channelEditorDialogController;
        mUniqueChannelsInRow = uniqueChannelsInRow;

        bindHeader();
        bindActions();

        View turnOffButton = findViewById(R.id.turn_off_notifications);
        turnOffButton.setOnClickListener(getTurnOffNotificationsClickListener());
        turnOffButton.setVisibility(turnOffButton.hasOnClickListeners() && !mIsNonBlockable
                ? VISIBLE : GONE);

        View done = findViewById(R.id.done);
        done.setOnClickListener(mOnDone);
        done.setAccessibilityDelegate(mGutsContainer.getAccessibilityDelegate());
    }

    private void bindActions() {
        final OnClickListener settingsOnClickListener = getSettingsOnClickListener();
        final View settingsButton = findViewById(R.id.info);
        settingsButton.setOnClickListener(settingsOnClickListener);
        settingsButton.setVisibility(settingsButton.hasOnClickListeners() ? VISIBLE : GONE);

        findViewById(R.id.settings_link).setOnClickListener(settingsOnClickListener);

        TextView msg = findViewById(R.id.non_configurable_text);
        msg.setText(getResources().getString(R.string.no_shortcut, mAppName));
    }

    private void bindHeader() {
        bindPackage();
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

    private OnClickListener getTurnOffNotificationsClickListener() {
        return ((View view) -> {
            if (!mPresentingChannelEditorDialog && mChannelEditorDialogController != null) {
                mPresentingChannelEditorDialog = true;

                mChannelEditorDialogController.prepareDialogForApp(mAppName, mPackageName, mAppUid,
                        mUniqueChannelsInRow, mPkgIcon, mOnSettingsClickListener);
                mChannelEditorDialogController.setOnFinishListener(() -> {
                    mPresentingChannelEditorDialog = false;
                    mGutsContainer.closeControls(this, false);
                });
                mChannelEditorDialogController.show();
            }
        });
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
                mPkgIcon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            mPkgIcon = mPm.getDefaultActivityIcon();
        }
        TextView name = findViewById(R.id.name);
        name.setText(mAppName);
        ImageView image = findViewById(R.id.icon);
        image.setImageDrawable(mPkgIcon);
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
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(VISIBLE);
        } else {
            groupNameView.setVisibility(GONE);
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
    protected void onFinishInflate() {
        super.onFinishInflate();
    }

    @Override
    public void onFinishedClosing() {
        // TODO: do we need to do anything here?
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
        return mPressedApply;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
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
}
