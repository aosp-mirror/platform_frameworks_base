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
import android.service.notification.StatusBarNotification;
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

import java.util.Set;

/**
 * The guts of a notification revealed when performing a long press.
 */
public class NotificationInfo extends LinearLayout implements GutsContent {
    private static final String TAG = "InfoGuts";

    private INotificationManager mINotificationManager;
    private int mStartingUserImportance;
    private StatusBarNotification mStatusBarNotification;
    private NotificationChannel mNotificationChannel;

    private ImageView mAutoButton;
    private TextView mImportanceSummary;
    private TextView mImportanceTitle;
    private boolean mAuto;

    private TextView mNumChannelsView;
    private View mChannelDisabledView;
    private Switch mChannelEnabledSwitch;

    private GutsInteractionListener mGutsInteractionListener;

    public NotificationInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public interface OnSettingsClickListener {
        void onClick(View v, int appUid);
    }

    public void bindNotification(final PackageManager pm,
            final INotificationManager iNotificationManager,
            final StatusBarNotification sbn, final NotificationChannel channel,
            OnSettingsClickListener onSettingsClick,
            OnClickListener onDoneClick, final Set<String> nonBlockablePkgs) {
        mINotificationManager = iNotificationManager;
        mNotificationChannel = channel;
        mStatusBarNotification = sbn;
        mStartingUserImportance = channel.getImportance();

        final String pkg = sbn.getPackageName();
        int appUid = -1;
        String appName = pkg;
        Drawable pkgicon = null;
        try {
            final ApplicationInfo info = pm.getApplicationInfo(pkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                appUid = info.uid;
                appName = String.valueOf(pm.getApplicationLabel(info));
                pkgicon = pm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = pm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkgicon)).setImageDrawable(pkgicon);

        int numChannels = 1;
        try {
            numChannels = iNotificationManager.getNumNotificationChannelsForPackage(
                    pkg, appUid, false /* includeDeleted */);
        } catch (RemoteException e) {
            Log.e(TAG, e.toString());
        }

        mNumChannelsView = (TextView) (findViewById(R.id.num_channels_desc));
        mNumChannelsView.setText(String.format(mContext.getResources().getQuantityString(
                R.plurals.notification_num_channels_desc, numChannels), numChannels));

        // If this is the placeholder channel, don't use our channel-specific text.
        CharSequence channelNameText;
        if (channel.getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            channelNameText = mContext.getString(R.string.notification_header_default_channel);
        } else {
            channelNameText = channel.getName();
        }
        ((TextView) findViewById(R.id.pkgname)).setText(appName);
        ((TextView) findViewById(R.id.channel_name)).setText(channelNameText);

        // Set group information if this channel has an associated group.
        CharSequence groupName = null;
        if (channel.getGroup() != null) {
            try {
                final NotificationChannelGroup notificationChannelGroup =
                        iNotificationManager.getNotificationChannelGroupForPackage(
                                channel.getGroup(), pkg, appUid);
                if (notificationChannelGroup != null) {
                    groupName = notificationChannelGroup.getName();
                }
            } catch (RemoteException e) {
                Log.e(TAG, e.toString());
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
            final PackageInfo info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            nonBlockable = Utils.isSystemPackage(getResources(), pm, info);
        } catch (PackageManager.NameNotFoundException e) {
            // unlikely.
        }
        if (nonBlockablePkgs != null) {
            nonBlockable |= nonBlockablePkgs.contains(pkg);
        }

        bindButtons(nonBlockable);

        // Top-level importance group
        mChannelDisabledView = findViewById(R.id.channel_disabled);
        updateImportanceDisplay();

        // Settings button.
        final TextView settingsButton = (TextView) findViewById(R.id.more_settings);
        if (appUid >= 0 && onSettingsClick != null) {
            final int appUidF = appUid;
            settingsButton.setOnClickListener(
                    (View view) -> {
                        onSettingsClick.onClick(view, appUidF);
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
        return mStartingUserImportance != getSelectedImportance();
    }

    private void saveImportance() {
        int selectedImportance = getSelectedImportance();
        if (selectedImportance == mStartingUserImportance) {
            return;
        }
        MetricsLogger.action(mContext, MetricsEvent.ACTION_SAVE_IMPORTANCE,
                selectedImportance - mStartingUserImportance);
        mNotificationChannel.setImportance(selectedImportance);
        try {
            mINotificationManager.updateNotificationChannelForPackage(
                    mStatusBarNotification.getPackageName(), mStatusBarNotification.getUid(),
                    mNotificationChannel);
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
        mChannelEnabledSwitch.setVisibility(nonBlockable ? View.INVISIBLE : View.VISIBLE);

        // Callback when checked.
        mChannelEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (mGutsInteractionListener != null) {
                mGutsInteractionListener.onInteraction(NotificationInfo.this);
            }
            updateImportanceDisplay();
        });
    }

    private void updateImportanceDisplay() {
        final boolean disabled = getSelectedImportance() == NotificationManager.IMPORTANCE_NONE;
        mChannelDisabledView.setVisibility(disabled ? View.VISIBLE : View.GONE);
        if (disabled) {
            // To be replaced by disabled text.
            mNumChannelsView.setVisibility(View.GONE);
        } else if (mNotificationChannel.getId().equals(NotificationChannel.DEFAULT_CHANNEL_ID)) {
            mNumChannelsView.setVisibility(View.INVISIBLE);
        } else {
            mNumChannelsView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void setInteractionListener(GutsInteractionListener listener) {
        mGutsInteractionListener = listener;
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
