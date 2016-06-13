/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import com.android.internal.widget.MessagingLinearLayout;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.TransformableView;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.view.View;

/**
 * Wraps a notification containing a messaging template
 */
public class NotificationMessagingTemplateViewWrapper extends NotificationTemplateViewWrapper {

    private View mContractedMessage;

    protected NotificationMessagingTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
    }

    private void resolveViews() {
        mContractedMessage = null;

        View container = mView.findViewById(com.android.internal.R.id.notification_messaging);
        if (container instanceof MessagingLinearLayout
                && ((MessagingLinearLayout) container).getChildCount() > 0) {
            MessagingLinearLayout messagingContainer = (MessagingLinearLayout) container;

            // Only consider the first child - transforming to a position other than the first
            // looks bad because we have to move across other messages that are fading in.
            View child = messagingContainer.getChildAt(0);
            if (child.getId() == messagingContainer.getContractedChildId()) {
                mContractedMessage = child;
            }
        }
    }

    @Override
    public void notifyContentUpdated(StatusBarNotification notification) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveViews();
        super.notifyContentUpdated(notification);
    }

    @Override
    protected void updateTransformedTypes() {
        // This also clears the existing types
        super.updateTransformedTypes();
        if (mContractedMessage != null) {
            mTransformationHelper.addTransformedView(TransformableView.TRANSFORMING_VIEW_TEXT,
                    mContractedMessage);
        }
    }
}
