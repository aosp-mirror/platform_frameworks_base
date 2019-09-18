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
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.service.notification.StatusBarNotification;
import android.util.FeatureFlagUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;
import com.android.settingslib.media.MediaOutputSliceConstants;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

/**
 * Class for handling MediaTransfer state over a set of notifications.
 */
public class MediaTransferManager {
    private final Context mContext;
    private final ActivityStarter mActivityStarter;

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
                    getRowForParent(parent).getStatusBarNotification();
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                    .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                            statusBarNotification.getPackageName());
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return true;
        }
    };

    public MediaTransferManager(Context context) {
        mContext = context;
        mActivityStarter = Dependency.get(ActivityStarter.class);
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
        if (!FeatureFlagUtils.isEnabled(mContext, FeatureFlagUtils.SEAMLESS_TRANSFER)) {
            return;
        }

        View view = root.findViewById(com.android.internal.R.id.media_seamless);
        if (view == null) {
            return;
        }

        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(mOnClickHandler);

        ExpandableNotificationRow enr = getRowForParent(view.getParent());
        int color = enr.getNotificationHeader().getOriginalIconColor();
        ColorStateList tintList = ColorStateList.valueOf(color);

        // Update the outline color
        LinearLayout viewLayout = (LinearLayout) view;
        RippleDrawable bkgDrawable = (RippleDrawable) viewLayout.getBackground();
        GradientDrawable rect = (GradientDrawable) bkgDrawable.getDrawable(0);
        rect.setStroke(2, color);

        // Update the image color
        ImageView image = view.findViewById(R.id.media_seamless_image);
        image.setImageTintList(tintList);

        // Update the text color
        TextView text = view.findViewById(R.id.media_seamless_text);
        text.setTextColor(tintList);
    }
}
