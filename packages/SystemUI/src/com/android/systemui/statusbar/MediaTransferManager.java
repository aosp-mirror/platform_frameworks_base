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

package com.android.systemui.statusbar;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.service.notification.StatusBarNotification;
import android.util.FeatureFlagUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.InfoMediaManager;
import com.android.settingslib.media.LocalMediaManager;
import com.android.settingslib.media.MediaDevice;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for handling MediaTransfer state over a set of notifications.
 */
public class MediaTransferManager {
    private final Context mContext;
    private final ActivityStarter mActivityStarter;
    private MediaDevice mDevice;
    private List<View> mViews = new ArrayList<>();
    private LocalMediaManager mLocalMediaManager;

    private static final String TAG = "MediaTransferManager";

    private final View.OnClickListener mOnClickHandler = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            if (handleMediaTransfer(view)) {
                return;
            }
        }

        private boolean handleMediaTransfer(View view) {
            if (view.findViewById(com.android.internal.R.id.media_seamless) == null) {
                return false;
            }

            ViewParent parent = view.getParent();
            StatusBarNotification statusBarNotification =
                    getRowForParent(parent).getEntry().getSbn();
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                    .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                            statusBarNotification.getPackageName());
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return true;
        }
    };

    private final LocalMediaManager.DeviceCallback mMediaDeviceCallback =
            new LocalMediaManager.DeviceCallback() {
        @Override
        public void onDeviceListUpdate(List<MediaDevice> devices) {
            MediaDevice currentDevice = mLocalMediaManager.getCurrentConnectedDevice();
            // Check because this can be called several times while changing devices
            if (mDevice == null || !mDevice.equals(currentDevice)) {
                mDevice = currentDevice;
                updateAllChips();
            }
        }

        @Override
        public void onSelectedDeviceStateChanged(MediaDevice device, int state) {
            if (mDevice == null || !mDevice.equals(device)) {
                mDevice = device;
                updateAllChips();
            }
        }
    };

    public MediaTransferManager(Context context) {
        mContext = context;
        mActivityStarter = Dependency.get(ActivityStarter.class);
        LocalBluetoothManager lbm = Dependency.get(LocalBluetoothManager.class);
        InfoMediaManager imm = new InfoMediaManager(mContext, null, null, lbm);
        mLocalMediaManager = new LocalMediaManager(mContext, lbm, imm, null);
    }

    /**
     * Mark a view as removed. If no views remain the media device listener will be unregistered.
     * @param root
     */
    public void setRemoved(View root) {
        if (!FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SEAMLESS_TRANSFER)
                || mLocalMediaManager == null || root == null) {
            return;
        }
        View view = root.findViewById(com.android.internal.R.id.media_seamless);
        if (mViews.remove(view)) {
            if (mViews.size() == 0) {
                mLocalMediaManager.unregisterCallback(mMediaDeviceCallback);
            }
        } else {
            Log.e(TAG, "Tried to remove unknown view " + view);
        }
    }

    private ExpandableNotificationRow getRowForParent(ViewParent parent) {
        while (parent != null) {
            if (parent instanceof ExpandableNotificationRow) {
                return ((ExpandableNotificationRow) parent);
            }
            parent = parent.getParent();
        }
        return null;
    }

    /**
     * apply the action button for MediaTransfer
     *
     * @param root  The parent container of the view.
     * @param entry The entry of MediaTransfer action button.
     */
    public void applyMediaTransferView(ViewGroup root, NotificationEntry entry) {
        if (!FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SEAMLESS_TRANSFER)
                || mLocalMediaManager == null || root == null) {
            return;
        }

        View view = root.findViewById(com.android.internal.R.id.media_seamless);
        if (view == null) {
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(mOnClickHandler);
        if (!mViews.contains(view)) {
            mViews.add(view);
            if (mViews.size() == 1) {
                mLocalMediaManager.registerCallback(mMediaDeviceCallback);
            }
        }

        // Initial update
        mLocalMediaManager.startScan();
        mDevice = mLocalMediaManager.getCurrentConnectedDevice();
        updateChip(view);
    }

    private void updateAllChips() {
        for (View view : mViews) {
            updateChip(view);
        }
    }

    private void updateChip(View view) {
        ExpandableNotificationRow enr = getRowForParent(view.getParent());
        int fgColor = enr.getNotificationHeader().getOriginalIconColor();
        ColorStateList fgTintList = ColorStateList.valueOf(fgColor);
        int bgColor = enr.getCurrentBackgroundTint();

        // Update outline color
        LinearLayout viewLayout = (LinearLayout) view;
        RippleDrawable bkgDrawable = (RippleDrawable) viewLayout.getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, fgColor);
        rect.setColor(bgColor);

        ImageView iconView = view.findViewById(com.android.internal.R.id.media_seamless_image);
        TextView deviceName = view.findViewById(com.android.internal.R.id.media_seamless_text);
        deviceName.setTextColor(fgTintList);

        if (mDevice != null) {
            Drawable icon = mDevice.getIcon();
            iconView.setVisibility(View.VISIBLE);
            iconView.setImageTintList(fgTintList);

            if (icon instanceof AdaptiveIcon) {
                AdaptiveIcon aIcon = (AdaptiveIcon) icon;
                aIcon.setBackgroundColor(bgColor);
                iconView.setImageDrawable(aIcon);
            } else {
                iconView.setImageDrawable(icon);
            }
            deviceName.setText(mDevice.getName());
        } else {
            // Reset to default
            iconView.setVisibility(View.GONE);
            deviceName.setText(com.android.internal.R.string.ext_media_seamless_action);
        }
    }
}
