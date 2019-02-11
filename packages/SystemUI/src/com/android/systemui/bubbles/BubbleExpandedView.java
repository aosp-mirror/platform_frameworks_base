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

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Container for the expanded bubble view, handles rendering the caret and header of the view.
 */
public class BubbleExpandedView extends LinearLayout implements View.OnClickListener {
    private static final String TAG = "BubbleExpandedView";

    // The triangle pointing to the expanded view
    private View mPointerView;

    // Header
    private View mHeaderView;
    private TextView mHeaderTextView;
    private ImageButton mDeepLinkIcon;
    private ImageButton mSettingsIcon;

    // Permission view
    private View mPermissionView;

    // The view that is being displayed for the expanded state
    private View mExpandedView;

    private NotificationEntry mEntry;
    private PackageManager mPm;
    private String mAppName;
    private Drawable mAppIcon;

    private INotificationManager mNotificationManagerService;

    // Need reference to let it know to collapse when new task is launched
    private BubbleStackView mStackView;

    private OnBubbleBlockedListener mOnBubbleBlockedListener;

    public BubbleExpandedView(Context context) {
        this(context, null);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BubbleExpandedView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mPm = context.getPackageManager();
        try {
            mNotificationManagerService = INotificationManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.NOTIFICATION_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            Log.w(TAG, e);
        }
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

        FrameLayout viewWrapper = findViewById(R.id.header_permission_wrapper);
        LayoutTransition transition = new LayoutTransition();
        transition.setDuration(200);

        ObjectAnimator appearAnimator = ObjectAnimator.ofFloat(null, View.ALPHA, 0f, 1f);
        transition.setAnimator(LayoutTransition.APPEARING, appearAnimator);
        transition.setInterpolator(LayoutTransition.APPEARING, Interpolators.ALPHA_IN);

        ObjectAnimator disappearAnimator = ObjectAnimator.ofFloat(null, View.ALPHA, 1f, 0f);
        transition.setAnimator(LayoutTransition.DISAPPEARING, disappearAnimator);
        transition.setInterpolator(LayoutTransition.DISAPPEARING, Interpolators.ALPHA_OUT);

        transition.setAnimateParentHierarchy(false);
        viewWrapper.setLayoutTransition(transition);
        viewWrapper.getLayoutTransition().enableTransitionType(LayoutTransition.CHANGING);

        mHeaderView = findViewById(R.id.header_layout);
        mHeaderTextView = findViewById(R.id.header_text);
        mDeepLinkIcon = findViewById(R.id.deep_link_button);
        mSettingsIcon = findViewById(R.id.settings_button);
        mDeepLinkIcon.setOnClickListener(this);
        mSettingsIcon.setOnClickListener(this);

        mPermissionView = findViewById(R.id.permission_layout);
        findViewById(R.id.no_bubbles_button).setOnClickListener(this);
        findViewById(R.id.yes_bubbles_button).setOnClickListener(this);
    }

    /**
     * Sets the listener to notify when a bubble has been blocked.
     */
    public void setOnBlockedListener(OnBubbleBlockedListener listener) {
        mOnBubbleBlockedListener = listener;
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
                mAppIcon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Ahh... just use package name
            mAppName = entry.notification.getPackageName();
        }
        if (mAppIcon == null) {
            mAppIcon = mPm.getDefaultActivityIcon();
        }
        updateHeaderView();
        updatePermissionView();
    }

    private void updateHeaderView() {
        mSettingsIcon.setContentDescription(getResources().getString(
                R.string.bubbles_settings_button_description, mAppName));
        mDeepLinkIcon.setContentDescription(getResources().getString(
                R.string.bubbles_deep_link_button_description, mAppName));
        if (mEntry != null && mEntry.getBubbleMetadata() != null) {
            mHeaderTextView.setText(mEntry.getBubbleMetadata().getTitle());
        } else {
            // This should only happen if we're auto-bubbling notification content that isn't
            // explicitly a bubble
            mHeaderTextView.setText(mAppName);
        }
    }

    private void updatePermissionView() {
        boolean hasUserApprovedBubblesForPackage = false;
        try {
            hasUserApprovedBubblesForPackage =
                    mNotificationManagerService.hasUserApprovedBubblesForPackage(
                            mEntry.notification.getPackageName(), mEntry.notification.getUid());
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
        if (hasUserApprovedBubblesForPackage) {
            mHeaderView.setVisibility(VISIBLE);
            mPermissionView.setVisibility(GONE);
        } else {
            mHeaderView.setVisibility(GONE);
            mPermissionView.setVisibility(VISIBLE);
            ((ImageView) mPermissionView.findViewById(R.id.pkgicon)).setImageDrawable(mAppIcon);
            ((TextView) mPermissionView.findViewById(R.id.pkgname)).setText(mAppName);
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
                    logBubbleClickEvent(mEntry.notification,
                            StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_APP);
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send intent for bubble with key: "
                            + (mEntry != null ? mEntry.key : " null entry"));
                }
            });
        } else if (id == R.id.settings_button) {
            Intent intent = getSettingsIntent(mEntry.notification.getPackageName(),
                    mEntry.notification.getUid());
            mStackView.collapseStack(() -> {
                mContext.startActivity(intent);
                logBubbleClickEvent(mEntry.notification,
                        StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
            });
        } else if (id == R.id.no_bubbles_button) {
            setBubblesAllowed(false);
        } else if (id == R.id.yes_bubbles_button) {
            setBubblesAllowed(true);
        }
    }

    private void setBubblesAllowed(boolean allowed) {
        try {
            mNotificationManagerService.setBubblesAllowed(
                    mEntry.notification.getPackageName(),
                    mEntry.notification.getUid(),
                    allowed);
            if (allowed) {
                mPermissionView.setVisibility(GONE);
                mHeaderView.setVisibility(VISIBLE);
            } else if (mOnBubbleBlockedListener != null) {
                mOnBubbleBlockedListener.onBubbleBlocked(mEntry);
            }
            logBubbleClickEvent(mEntry.notification,
                    allowed ? StatsLog.BUBBLE_UICHANGED__ACTION__PERMISSION_OPT_IN :
                            StatsLog.BUBBLE_UICHANGED__ACTION__PERMISSION_OPT_OUT);
        } catch (RemoteException e) {
            Log.w(TAG, e);
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

    /**
     * Listener that is notified when a bubble is blocked.
     */
    public interface OnBubbleBlockedListener {
        /**
         * Called when a bubble is blocked for the provided entry.
         */
        void onBubbleBlocked(NotificationEntry entry);
    }

    /**
     * Logs bubble UI click event.
     *
     * @param notification the bubble notification that user is interacting with.
     * @param action the user interaction enum.
     */
    private void logBubbleClickEvent(StatusBarNotification notification, int action) {
        StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                notification.getPackageName(),
                notification.getNotification().getChannelId(),
                notification.getId(),
                mStackView.getBubbleIndex(mStackView.getExpandedBubble()),
                mStackView.getBubbleCount(),
                action,
                mStackView.getNormalizedXPosition(),
                mStackView.getNormalizedYPosition());
    }
}
