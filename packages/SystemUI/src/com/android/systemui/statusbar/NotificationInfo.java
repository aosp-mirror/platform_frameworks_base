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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.INotificationManager;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.GutsContent;
import com.android.systemui.plugins.statusbar.NotificationMenuRowProvider.GutsInteractionListener;
import com.android.systemui.statusbar.NotificationGuts.OnSettingsClickListener;
import com.android.systemui.statusbar.stack.StackStateAnimator;

import java.lang.IllegalArgumentException;
import java.util.List;
import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationInfo extends LinearLayout implements GutsContent {
    private static final String TAG = "InfoGuts";

    private INotificationManager mINotificationManager;
    private String mPkg;
    private int mAppUid;
    private List<NotificationChannel> mNotificationChannels;
    private NotificationChannel mSingleNotificationChannel;
    private int mStartingUserImportance;

    private TextView mNumChannelsView;
    private View mChannelDisabledView;
    private Switch mChannelEnabledSwitch;

    private GutsInteractionListener mGutsInteractionListener;

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, NotificationChannel channel, int appUid);
    }

    public void bindNotification(final PackageManager pm,
            final INotificationManager iNotificationManager,
            final String pkg,
            final List<NotificationChannel> notificationChannels,
            OnSettingsClickListener onSettingsClick,
            OnClickListener onDoneClick, final Set<String> nonBlockablePkgs)
            throws RemoteException {
        mINotificationManager = iNotificationManager;
        mPkg = pkg;
        mNotificationChannels = notificationChannels;
        boolean isSingleDefaultChannel = false;
        if (mNotificationChannels.isEmpty()) {
            throw new IllegalArgumentException("bindNotification requires at least one channel");
        } else if (mNotificationChannels.size() == 1) {
            mSingleNotificationChannel = mNotificationChannels.get(0);
            mStartingUserImportance = mSingleNotificationChannel.getImportance();
            isSingleDefaultChannel = mSingleNotificationChannel.getId()
                    .equals(NotificationChannel.DEFAULT_CHANNEL_ID);
        } else {
            mSingleNotificationChannel = null;
        }

        String appName = mPkg;
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
                mAppUid = info.uid;
                appName = String.valueOf(pm.getApplicationLabel(info));
                pkgicon = pm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkgicon)).setImageDrawable(pkgicon);

        int numChannels = 1;
        numChannels = iNotificationManager.getNumNotificationChannelsForPackage(
                pkg, mAppUid, false /* includeDeleted */);

        String channelsDescText;
        mNumChannelsView = (TextView) (findViewById(R.id.num_channels_desc));
        if (isSingleDefaultChannel) {
            channelsDescText = mContext.getString(R.string.notification_default_channel_desc);
        } else {
            switch (mNotificationChannels.size()) {
                case 1:
                    channelsDescText = String.format(mContext.getResources().getQuantityString(
                            R.plurals.notification_num_channels_desc, numChannels), numChannels);
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
        } else if (isSingleDefaultChannel) {
            // If this is the default channel, don't use our channel-specific text.
            channelNameText = mContext.getString(R.string.notification_header_default_channel);
        } else {
            channelNameText = mSingleNotificationChannel.getName();
        }
        ((TextView) findViewById(R.id.pkgname)).setText(appName);
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

        boolean nonBlockable = false;
        try {
            final PackageInfo pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            nonBlockable = Utils.isSystemPackage(getResources(), pm, pkgInfo);
        } catch (PackageManager.NameNotFoundException e) {
            // unlikely.
        }
        if (nonBlockablePkgs != null) {
            nonBlockable |= nonBlockablePkgs.contains(pkg);
        }

        bindButtons(nonBlockable);

        // Top-level importance group
        mChannelDisabledView = findViewById(R.id.channel_disabled);
        updateSecondaryText();

        // Settings button.
        final TextView settingsButton = (TextView) findViewById(R.id.more_settings);
        if (mAppUid >= 0 && onSettingsClick != null) {
            final int appUidF = mAppUid;
            settingsButton.setOnClickListener(
                    (View view) -> {
                        onSettingsClick.onClick(view, mSingleNotificationChannel, appUidF);
                    });
            if (numChannels > 1) {
                settingsButton.setText(R.string.notification_all_categories);
            } else {
                settingsButton.setText(R.string.notification_more_settings);
            }

        } else {
            settingsButton.setVisibility(View.GONE);
        }

        // Done button.
        final TextView doneButton = (TextView) findViewById(R.id.done);
        doneButton.setText(R.string.notification_done);
        doneButton.setOnClickListener(onDoneClick);
    }

    public boolean hasImportanceChanged() {
        return mSingleNotificationChannel != null &&
                mStartingUserImportance != getSelectedImportance();
    }

    private void saveImportance() {
        if (mSingleNotificationChannel == null) {
            return;
        }
        int selectedImportance = getSelectedImportance();
        if (selectedImportance == mStartingUserImportance) {
            return;
        }
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                selectedImportance - mStartingUserImportance);
        mSingleNotificationChannel.setImportance(selectedImportance);
        try {
            mINotificationManager.updateNotificationChannelForPackage(
                    mPkg, mAppUid, mSingleNotificationChannel);
        } catch (RemoteException e) {
            // :(
        }
    }

    private int getSelectedImportance() {
        if (!mChannelEnabledSwitch.isChecked()) {
            return NotificationManager.IMPORTANCE_NONE;
        } else {
            return mStartingUserImportance;
        }
    }

    private void bindButtons(final boolean nonBlockable) {
        // Enabled Switch
        mChannelEnabledSwitch = (Switch) findViewById(R.id.channel_enabled_switch);
        mChannelEnabledSwitch.setChecked(
                mStartingUserImportance != NotificationManager.IMPORTANCE_NONE);
        final boolean visible = !nonBlockable && mSingleNotificationChannel != null;
        mChannelEnabledSwitch.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

        // Callback when checked.
        mChannelEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mGutsInteractionListener != null) {
                mGutsInteractionListener.onInteraction(NotificationInfo.this);
            }
            updateSecondaryText();
        });
    }

    private void updateSecondaryText() {
        final boolean disabled = mSingleNotificationChannel != null &&
                getSelectedImportance() == NotificationManager.IMPORTANCE_NONE;
        if (disabled) {
            mChannelDisabledView.setVisibility(View.VISIBLE);
            mNumChannelsView.setVisibility(View.GONE);
        } else {
            mChannelDisabledView.setVisibility(View.GONE);
            mNumChannelsView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setInteractionListener(GutsInteractionListener listener) {
        mGutsInteractionListener = listener;
    }

    @Override
    public boolean willBeRemoved() {
        return !mChannelEnabledSwitch.isChecked();
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public boolean handleCloseControls(boolean save) {
        if (save) {
            saveImportance();
        }
        return false;
    }
}
