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
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Wraps a notification containing a messaging template
 */
public class NotificationMessagingTemplateViewWrapper extends NotificationTemplateViewWrapper {

    private View mContractedMessage;
    private ArrayList<View> mHistoricMessages = new ArrayList<View>();

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

            int childCount = messagingContainer.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = messagingContainer.getChildAt(i);

                if (child.getVisibility() == View.GONE
                        && child instanceof TextView
                        && !TextUtils.isEmpty(((TextView) child).getText())) {
                    mHistoricMessages.add(child);
                }

                // Only consider the first visible child - transforming to a position other than the
                // first looks bad because we have to move across other messages that are fading in.
                if (child.getId() == messagingContainer.getContractedChildId()) {
                    mContractedMessage = child;
                } else if (child.getVisibility() == View.VISIBLE) {
                    break;
                }
            }
        }
    }

    @Override
    public void onContentUpdated(ExpandableNotificationRow row) {
        // Reinspect the notification. Before the super call, because the super call also updates
        // the transformation types and we need to have our values set by then.
        resolveViews();
        super.onContentUpdated(row);
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

    @Override
    public void setRemoteInputVisible(boolean visible) {
        for (int i = 0; i < mHistoricMessages.size(); i++) {
            mHistoricMessages.get(i).setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
