/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.util.NotificationColorUtil;
import com.android.systemui.R;
import com.android.systemui.statusbar.BaseStatusBar;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.NotificationGroupManager;

import java.util.List;

/**
 * A header for a notification view
 */
public class NotificationHeaderView extends FrameLayout {

    private static final int DEFAULT_ICON_TINT_COLOR = 0xff616161;
    private final NotificationColorUtil mNotificationColorUtil;
    private NotificationData.Entry mNotificationEntry;
    private ImageView mIconView;
    private TextView mAppName;
    private TextView mPostTime;
    private TextView mChildCount;
    private TextView mSubTextDivider;
    private TextView mSubText;
    private NotificationGroupManager mGroupManager;
    private ImageButton mExpandButton;

    public NotificationHeaderView(Context context) {
        this(context, null);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationHeaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mNotificationColorUtil = NotificationColorUtil.getInstance(context);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mIconView = (ImageView) findViewById(R.id.header_notification_icon);
        mAppName = (TextView) findViewById(R.id.app_name_text);
        mSubTextDivider = (TextView) findViewById(R.id.app_title_sub_text_divider);
        mSubText = (TextView) findViewById(R.id.title_sub_text);
        mPostTime = (TextView) findViewById(R.id.post_time);
        mChildCount = (TextView) findViewById(R.id.number_of_children);
        mExpandButton = (ImageButton) findViewById(R.id.notification_expand_button);
        mExpandButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                mGroupManager.toggleGroupExpansion(mNotificationEntry.notification);
            }
        });
    }

    public void bind(NotificationData.Entry notificationEntry) {
        mNotificationEntry = notificationEntry;
        StatusBarNotification sbn = notificationEntry.notification;
        int notificationColor = getNotificationColor(sbn);
        bindIcon(notificationColor);
        bindNumber(notificationColor);
        bindAppName(sbn);
        bindSubText();
        bindTime(sbn);
        bindExpandButton(sbn);
    }

    private void bindExpandButton(StatusBarNotification sbn) {
        boolean summaryOfGroup = mGroupManager.isSummaryOfGroup(sbn);
        mExpandButton.setVisibility(summaryOfGroup ? VISIBLE : GONE);
    }

    private void bindSubText() {
        List<ExpandableNotificationRow> notificationChildren =
                mNotificationEntry.row.getNotificationChildren();
        CharSequence subText = null;
        if (notificationChildren != null) {
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow row = notificationChildren.get(i);
                CharSequence rowSubText = row.getSubText();
                if (TextUtils.isEmpty(rowSubText)
                        || (subText != null && !subText.equals(rowSubText))) {
                    // The children don't have a common subText
                    subText = null;
                    break;
                } else if (subText == null) {
                    subText = rowSubText;
                }
            }
        };
        setSubText(subText);
    }

    private void setSubText(CharSequence subText) {
        boolean goneInHeader = TextUtils.isEmpty(subText);
        if (goneInHeader) {
            mSubText.setVisibility(GONE);
            mSubTextDivider.setVisibility(GONE);
        } else {
            mSubText.setVisibility(VISIBLE);
            mSubText.setText(subText);
            mSubTextDivider.setVisibility(VISIBLE);
        }
        List<ExpandableNotificationRow> notificationChildren =
                mNotificationEntry.row.getNotificationChildren();
        if (notificationChildren != null) {
            for (int i = 0; i < notificationChildren.size(); i++) {
                ExpandableNotificationRow row = notificationChildren.get(i);
                row.setContentSubTextVisible(goneInHeader);
            }
        }
    }

    private int getNotificationColor(StatusBarNotification sbn) {
        int color = sbn.getNotification().color;
        if (color == Notification.COLOR_DEFAULT) {
            return DEFAULT_ICON_TINT_COLOR;
        }
        return color;
    }

    private void bindNumber(int notificationColor) {
        int numberOfNotificationChildren = mNotificationEntry.row.getNumberOfNotificationChildren();
        boolean visible = numberOfNotificationChildren > 0;
        if (visible) {
            mChildCount.setText("(" + numberOfNotificationChildren + ")");
            mChildCount.setTextColor(notificationColor);
            mChildCount.setVisibility(VISIBLE);
        } else {
            mChildCount.setVisibility(GONE);
        }
    }

    private void bindTime(StatusBarNotification sbn) {

    }

    private void bindIcon(int notificationColor) {
        Drawable icon = mNotificationEntry.icon.getDrawable().getConstantState()
                .newDrawable(getResources()).mutate();
        mIconView.setImageDrawable(icon);
        if (NotificationUtils.isGrayscale(mIconView, mNotificationColorUtil)) {
            icon.setTint(notificationColor);
        }
    }

    private void bindAppName(StatusBarNotification sbn) {
        PackageManager pmUser = BaseStatusBar.getPackageManagerForUser(getContext(),
                sbn.getUser().getIdentifier());
        final String pkg = sbn.getPackageName();
        String appname = pkg;
        try {
            final ApplicationInfo info = pmUser.getApplicationInfo(pkg,
                    PackageManager.GET_UNINSTALLED_PACKAGES
                            | PackageManager.GET_DISABLED_COMPONENTS);
            if (info != null) {
                appname = String.valueOf(pmUser.getApplicationLabel(info));

            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name
        }
        mAppName.setText(appname);
    }

    public void setGroupManager(NotificationGroupManager groupManager) {
        mGroupManager = groupManager;
    }
}
