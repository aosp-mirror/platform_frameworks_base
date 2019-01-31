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
import android.service.notification.StatusBarNotification;
import android.util.FeatureFlagUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

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
            StatusBarNotification statusBarNotification = getNotificationForParent(parent);
            final Intent intent = new Intent()
                    .setAction(MediaOutputSliceConstants.ACTION_MEDIA_OUTPUT)
                    .putExtra(MediaOutputSliceConstants.EXTRA_PACKAGE_NAME,
                            statusBarNotification.getPackageName());
            mActivityStarter.startActivity(intent, false, true /* dismissShade */,
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            return true;
        }

        private StatusBarNotification getNotificationForParent(ViewParent parent) {
            while (parent != null) {
                if (parent instanceof ExpandableNotificationRow) {
                    return ((ExpandableNotificationRow) parent).getStatusBarNotification();
                }
                parent = parent.getParent();
            }
            return null;
        }
    };

    public MediaTransferManager(Context context) {
        mContext = context;
        mActivityStarter = Dependency.get(ActivityStarter.class);
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
    }
}
