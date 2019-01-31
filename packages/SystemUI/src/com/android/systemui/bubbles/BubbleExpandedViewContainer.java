/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.bubbles;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ShapeDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Container for the expanded bubble view, handles rendering the caret and header of the view.
 */
public class BubbleExpandedViewContainer extends LinearLayout implements View.OnClickListener {
    private static final String TAG = "BubbleExpandedView";

    // The triangle pointing to the expanded view
    private View mPointerView;
    // The view displayed between the pointer and the expanded view
    private TextView mHeaderView;
    // Tappable header icon deeplinking into the app
    private ImageButton mDeepLinkIcon;
    // Tappable header icon deeplinking into notification settings
    private ImageButton mSettingsIcon;
    // The view that is being displayed for the expanded state
    private View mExpandedView;

    private NotificationEntry mEntry;
    private PackageManager mPm;
    private String mAppName;

    // Need reference to let it know to collapse when new task is launched
    private BubbleStackView mStackView;

    public BubbleExpandedViewContainer(Context context) {
        this(context, null);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleExpandedViewContainer(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mPm = context.getPackageManager();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        int width = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        int height = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);

        TypedArray ta = getContext().obtainStyledAttributes(
                new int[] {android.R.attr.colorBackgroundFloating});
        int bgColor = ta.getColor(0, Color.WHITE);
        ta.recycle();

        ShapeDrawable triangleDrawable = new ShapeDrawable(
                TriangleShape.create(width, height, true /* pointUp */));
        triangleDrawable.setTint(bgColor);
        mPointerView.setBackground(triangleDrawable);

        mHeaderView = findViewById(R.id.header_text);
        mDeepLinkIcon = findViewById(R.id.deep_link_button);
        mSettingsIcon = findViewById(R.id.settings_button);
        mDeepLinkIcon.setOnClickListener(this);
        mSettingsIcon.setOnClickListener(this);
    }

    /**
     * Sets the notification entry used to populate this view.
     */
    public void setEntry(NotificationEntry entry, BubbleStackView stackView) {
        mStackView = stackView;
        mEntry = entry;

        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(
                    entry.notification.getPackageName(),
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Ahh... just use package name
            mAppName = entry.notification.getPackageName();
        }

        updateHeaderView();
    }

    private void updateHeaderView() {
        mSettingsIcon.setContentDescription(getResources().getString(
                R.string.bubbles_settings_button_description, mAppName));
        mDeepLinkIcon.setContentDescription(getResources().getString(
                R.string.bubbles_deep_link_button_description, mAppName));
        if (mEntry != null && mEntry.getBubbleMetadata() != null) {
            setHeaderText(mEntry.getBubbleMetadata().getTitle());
        } else {
            // This should only happen if we're auto-bubbling notification content that isn't
            // explicitly a bubble
            setHeaderText(mAppName);
        }
    }

    @Override
    public void onClick(View view) {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        int id = view.getId();
        if (id == R.id.deep_link_button) {
            mStackView.collapseStack(() -> {
                try {
                    n.contentIntent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send intent for bubble with key: "
                            + (mEntry != null ? mEntry.key : " null entry"));
                }
            });
        } else if (id == R.id.settings_button) {
            Intent intent = getSettingsIntent(mEntry.notification.getPackageName(),
                    mEntry.notification.getUid());
            mStackView.collapseStack(() -> mContext.startActivity(intent));
        }
    }

    /**
     * Set the x position that the tip of the triangle should point to.
     */
    public void setPointerPosition(int x) {
        // Adjust for the pointer size
        x -= (mPointerView.getWidth() / 2);
        mPointerView.setTranslationX(x);
    }

    /**
     * Set the text displayed within the header.
     */
    private void setHeaderText(CharSequence text) {
        mHeaderView.setText(text);
        mHeaderView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    /**
     * Set the view to display for the expanded state. Passing null will clear the view.
     */
    public void setExpandedView(View view) {
        if (mExpandedView == view) {
            return;
        }
        if (mExpandedView != null) {
            removeView(mExpandedView);
        }
        mExpandedView = view;
        if (mExpandedView != null) {
            addView(mExpandedView);
        }
    }

    /**
     * @return the view containing the expanded content, can be null.
     */
    @Nullable
    public View getExpandedView() {
        return mExpandedView;
    }

    private Intent getSettingsIntent(String packageName, final int appUid) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }
}
