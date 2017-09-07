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

import static android.app.NotificationManager.IMPORTANCE_NONE;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.R;

import java.lang.IllegalArgumentException;
import java.util.List;
import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationInfo extends LinearLayout implements NotificationGuts.GutsContent {
    private static final String TAG = "InfoGuts";

    private INotificationManager mINotificationManager;
    private String mPkg;
    private String mAppName;
    private int mAppUid;
    private List<NotificationChannel> mNotificationChannels;
    private NotificationChannel mSingleNotificationChannel;
    private boolean mIsSingleDefaultChannel;
    private StatusBarNotification mSbn;
    private int mStartingUserImportance;

    private TextView mNumChannelsView;
    private View mChannelDisabledView;
    private TextView mSettingsLinkView;
    private Switch mChannelEnabledSwitch;
    private CheckSaveListener mCheckSaveListener;
    private OnAppSettingsClickListener mAppSettingsClickListener;
    private PackageManager mPm;

    private NotificationGuts mGutsContainer;

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Specify a CheckSaveListener to override when/if the user's changes are committed.
    public interface CheckSaveListener {
        // Invoked when importance has changed and the NotificationInfo wants to try to save it.
        // Listener should run saveImportance unless the change should be canceled.
        void checkSave(Runnable saveImportance);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public interface OnAppSettingsClickListener {
        void onClick(View v, Intent intent);
    }

    public void bindNotification(final PackageManager pm,
            final INotificationManager iNotificationManager,
            final String pkg,
            final List<NotificationChannel> notificationChannels,
            int startingUserImportance,
            final StatusBarNotification sbn,
            OnSettingsClickListener onSettingsClick,
            OnAppSettingsClickListener onAppSettingsClick,
            OnClickListener onDoneClick,
            CheckSaveListener checkSaveListener,
            final Set<String> nonBlockablePkgs)
            throws RemoteException {
        mINotificationManager = iNotificationManager;
        mPkg = pkg;
        mNotificationChannels = notificationChannels;
        mCheckSaveListener = checkSaveListener;
        mSbn = sbn;
        mPm = pm;
        mAppSettingsClickListener = onAppSettingsClick;
        mStartingUserImportance = startingUserImportance;
        mAppName = mPkg;
        Drawable pkgicon = null;
        CharSequence channelNameText = "";
        ApplicationInfo info = null;
        try {
            info = pm.getApplicationInfo(mPkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppUid = sbn.getUid();
                mAppName = String.valueOf(pm.getApplicationLabel(info));
                pkgicon = pm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkgicon)).setImageDrawable(pkgicon);

        int numTotalChannels = iNotificationManager.getNumNotificationChannelsForPackage(
                pkg, mAppUid, false /* includeDeleted */);
        if (mNotificationChannels.isEmpty()) {
            throw new IllegalArgumentException("bindNotification requires at least one channel");
        } else  {
            if (mNotificationChannels.size() == 1) {
                mSingleNotificationChannel = mNotificationChannels.get(0);
                // Special behavior for the Default channel if no other channels have been defined.
                mIsSingleDefaultChannel =
                        (mSingleNotificationChannel.getId()
                                .equals(NotificationChannel.DEFAULT_CHANNEL_ID) &&
                        numTotalChannels <= 1);
            } else {
                mSingleNotificationChannel = null;
                mIsSingleDefaultChannel = false;
            }
        }

        boolean nonBlockable = false;
        try {
            final PackageInfo pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (Utils.isSystemPackage(getResources(), pm, pkgInfo)) {
                final int numChannels = mNotificationChannels.size();
                for (int i = 0; i < numChannels; i++) {
                    final NotificationChannel notificationChannel = mNotificationChannels.get(i);
                    // If any of the system channels is not blockable, the bundle is nonblockable
                    if (!notificationChannel.isBlockableSystem()) {
                        nonBlockable = true;
                        break;
                    }
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            // unlikely.
        }
        if (nonBlockablePkgs != null) {
            nonBlockable |= nonBlockablePkgs.contains(pkg);
        }

        String channelsDescText;
        mNumChannelsView = findViewById(R.id.num_channels_desc);
        if (nonBlockable) {
            channelsDescText = mContext.getString(R.string.notification_unblockable_desc);
        } else if (mIsSingleDefaultChannel) {
            channelsDescText = mContext.getString(R.string.notification_default_channel_desc);
        } else {
            switch (mNotificationChannels.size()) {
                case 1:
                    channelsDescText = String.format(mContext.getResources().getQuantityString(
                            R.plurals.notification_num_channels_desc, numTotalChannels),
                            numTotalChannels);
                    break;
                case 2:
                    channelsDescText = mContext.getString(
                            R.string.notification_channels_list_desc_2,
                            mNotificationChannels.get(0).getName(),
                            mNotificationChannels.get(1).getName());
                    break;
                default:
                    final int numOthers = mNotificationChannels.size() - 2;
                    channelsDescText = String.format(
                            mContext.getResources().getQuantityString(
                                    R.plurals.notification_channels_list_desc_2_and_others,
                                    numOthers),
                            mNotificationChannels.get(0).getName(),
                            mNotificationChannels.get(1).getName(),
                            numOthers);
            }
        }
        mNumChannelsView.setText(channelsDescText);

        if (mSingleNotificationChannel == null) {
            // Multiple channels don't use a channel name for the title.
            channelNameText = mContext.getString(R.string.notification_num_channels,
                    mNotificationChannels.size());
        } else if (mIsSingleDefaultChannel || nonBlockable) {
            // If this is the default channel or the app is unblockable,
            // don't use our channel-specific text.
            channelNameText = mContext.getString(R.string.notification_header_default_channel);
        } else {
            channelNameText = mSingleNotificationChannel.getName();
        }
        ((TextView) findViewById(R.id.pkgname)).setText(mAppName);
        ((TextView) findViewById(R.id.channel_name)).setText(channelNameText);

        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (mSingleNotificationChannel != null && mSingleNotificationChannel.getGroup() != null) {
            final NotificationChannelGroup notificationChannelGroup =
                    iNotificationManager.getNotificationChannelGroupForPackage(
                            mSingleNotificationChannel.getGroup(), pkg, mAppUid);
            if (notificationChannelGroup != null) {
                groupName = notificationChannelGroup.getName();
            }
        }
        TextView groupNameView = ((TextView) findViewById(R.id.group_name));
        TextView groupDividerView = ((TextView) findViewById(R.id.pkg_group_divider));
        if (groupName != null) {
            groupNameView.setText(groupName);
            groupNameView.setVisibility(View.VISIBLE);
            groupDividerView.setVisibility(View.VISIBLE);
        } else {
            groupNameView.setVisibility(View.GONE);
            groupDividerView.setVisibility(View.GONE);
        }

        bindButtons(nonBlockable);

        // Top-level importance group
        mChannelDisabledView = findViewById(R.id.channel_disabled);
        updateSecondaryText();

        // Settings button.
        final TextView settingsButton = (TextView) findViewById(R.id.more_settings);
        if (mAppUid >= 0 && onSettingsClick != null) {
            settingsButton.setVisibility(View.VISIBLE);
            final int appUidF = mAppUid;
            settingsButton.setOnClickListener(
                    (View view) -> {
                        onSettingsClick.onClick(view, mSingleNotificationChannel, appUidF);
                    });
            if (numTotalChannels <= 1 || nonBlockable) {
                settingsButton.setText(R.string.notification_more_settings);
            } else {
                settingsButton.setText(R.string.notification_all_categories);
            }
        } else {
            settingsButton.setVisibility(View.GONE);
        }

        // Done button.
        final TextView doneButton = (TextView) findViewById(R.id.done);
        doneButton.setText(R.string.notification_done);
        doneButton.setOnClickListener(onDoneClick);

        // Optional settings link
        updateAppSettingsLink();
    }

    private boolean hasImportanceChanged() {
        return mSingleNotificationChannel != null &&
                mChannelEnabledSwitch != null &&
                mStartingUserImportance != getSelectedImportance();
    }

    private void saveImportance() {
        if (!hasImportanceChanged()) {
            return;
        }
        final int selectedImportance = getSelectedImportance();
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                selectedImportance - mStartingUserImportance);
        mSingleNotificationChannel.setImportance(selectedImportance);
        mSingleNotificationChannel.lockFields(NotificationChannel.USER_LOCKED_IMPORTANCE);
        try {
            mINotificationManager.updateNotificationChannelForPackage(
                    mPkg, mAppUid, mSingleNotificationChannel);
        } catch (RemoteException e) {
            // :(
        }
    }

    private int getSelectedImportance() {
        if (!mChannelEnabledSwitch.isChecked()) {
            return IMPORTANCE_NONE;
        } else {
            return mStartingUserImportance;
        }
    }

    private void bindButtons(final boolean nonBlockable) {
        // Enabled Switch
        mChannelEnabledSwitch = (Switch) findViewById(R.id.channel_enabled_switch);
        mChannelEnabledSwitch.setChecked(
                mStartingUserImportance != IMPORTANCE_NONE);
        final boolean visible = !nonBlockable && mSingleNotificationChannel != null;
        mChannelEnabledSwitch.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

        // Callback when checked.
        mChannelEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mGutsContainer != null) {
                mGutsContainer.resetFalsingCheck();
            }
            updateSecondaryText();
            updateAppSettingsLink();
        });
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

    private void updateSecondaryText() {
        final boolean disabled = mSingleNotificationChannel != null &&
                getSelectedImportance() == IMPORTANCE_NONE;
        if (disabled) {
            mChannelDisabledView.setVisibility(View.VISIBLE);
            mNumChannelsView.setVisibility(View.GONE);
        } else {
            mChannelDisabledView.setVisibility(View.GONE);
            mNumChannelsView.setVisibility(mIsSingleDefaultChannel ? View.INVISIBLE : View.VISIBLE);
        }
    }

    private void updateAppSettingsLink() {
        mSettingsLinkView = findViewById(R.id.app_settings);
        Intent settingsIntent = getAppSettingsIntent(mPm, mPkg, mSingleNotificationChannel,
                mSbn.getId(), mSbn.getTag());
        if (settingsIntent != null && getSelectedImportance() != IMPORTANCE_NONE
                && !TextUtils.isEmpty(mSbn.getNotification().getSettingsText())) {
            mSettingsLinkView.setVisibility(View.VISIBLE);
            mSettingsLinkView.setText(mContext.getString(R.string.notification_app_settings,
                    mSbn.getNotification().getSettingsText()));
            mSettingsLinkView.setOnClickListener((View view) -> {
                mAppSettingsClickListener.onClick(view, settingsIntent);
            });
        } else {
            mSettingsLinkView.setVisibility(View.GONE);
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
        return mChannelEnabledSwitch != null && !mChannelEnabledSwitch.isChecked();
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (save && hasImportanceChanged()) {
            if (mCheckSaveListener != null) {
                mCheckSaveListener.checkSave(() -> { saveImportance(); });
            } else {
                saveImportance();
            }
        }
        return false;
    }

    @Override
    public int getActualHeight() {
        return getHeight();
    }
}
