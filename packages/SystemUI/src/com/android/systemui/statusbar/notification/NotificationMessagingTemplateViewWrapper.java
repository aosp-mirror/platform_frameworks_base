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

import com.android.internal.widget.MessagingLayout;
import com.android.internal.widget.MessagingLinearLayout;
import com.android.systemui.R;
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

    private final int mMinHeightWithActions;
    private MessagingLayout mMessagingLayout;
    private MessagingLinearLayout mMessagingLinearLayout;

    protected NotificationMessagingTemplateViewWrapper(Context ctx, View view,
            ExpandableNotificationRow row) {
        super(ctx, view, row);
        mMessagingLayout = (MessagingLayout) view;
        mMinHeightWithActions = NotificationUtils.getFontScaledHeight(ctx,
                R.dimen.notification_messaging_actions_min_height);
    }

    private void resolveViews() {
        mMessagingLinearLayout = mMessagingLayout.getMessagingLinearLayout();
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
        if (mMessagingLinearLayout != null) {
            mTransformationHelper.addTransformedView(mMessagingLinearLayout.getId(),
                    mMessagingLinearLayout);
        }
    }

    @Override
    public void setRemoteInputVisible(boolean visible) {
        mMessagingLayout.showHistoricMessages(visible);
    }

    @Override
    public int getMinLayoutHeight() {
        if (mActionsContainer != null && mActionsContainer.getVisibility() != View.GONE) {
            return mMinHeightWithActions;
        }
        return super.getMinLayoutHeight();
    }
}
