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

import static android.view.Display.INVALID_DISPLAY;

import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityView;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.util.StatsLog;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.AlphaOptimizedButton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Container for the expanded bubble view, handles rendering the caret and settings icon.
 */
public class BubbleExpandedView extends LinearLayout implements View.OnClickListener {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleExpandedView" : TAG_BUBBLES;

    private enum ActivityViewStatus {
        // ActivityView is being initialized, cannot start an activity yet.
        INITIALIZING,
        // ActivityView is initialized, and ready to start an activity.
        INITIALIZED,
        // Activity runs in the ActivityView.
        ACTIVITY_STARTED,
        // ActivityView is released, so activity launching will no longer be permitted.
        RELEASED,
    }

    // The triangle pointing to the expanded view
    private View mPointerView;
    private int mPointerMargin;

    private AlphaOptimizedButton mSettingsIcon;

    // Views for expanded state
    private ActivityView mActivityView;

    private ActivityViewStatus mActivityViewStatus = ActivityViewStatus.INITIALIZING;
    private int mTaskId = -1;

    private PendingIntent mBubbleIntent;

    private boolean mKeyboardVisible;
    private boolean mNeedsNewHeight;

    private Point mDisplaySize;
    private int mMinHeight;
    private int mSettingsIconHeight;
    private int mPointerWidth;
    private int mPointerHeight;
    private ShapeDrawable mPointerDrawable;

    private NotificationEntry mEntry;
    private PackageManager mPm;
    private String mAppName;
    private Drawable mAppIcon;

    private BubbleController mBubbleController = Dependency.get(BubbleController.class);

    private BubbleStackView mStackView;

    private ActivityView.StateCallback mStateCallback = new ActivityView.StateCallback() {
        @Override
        public void onActivityViewReady(ActivityView view) {
            switch (mActivityViewStatus) {
                case INITIALIZING:
                case INITIALIZED:
                    // Custom options so there is no activity transition animation
                    ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                            0 /* enterResId */, 0 /* exitResId */);
                    // Post to keep the lifecycle normal
                    post(() -> mActivityView.startActivity(mBubbleIntent, options));
                    mActivityViewStatus = ActivityViewStatus.ACTIVITY_STARTED;
            }
        }

        @Override
        public void onActivityViewDestroyed(ActivityView view) {
            mActivityViewStatus = ActivityViewStatus.RELEASED;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            // Since Bubble ActivityView applies singleTaskDisplay this is
            // guaranteed to only be called once per ActivityView. The taskId is
            // saved to use for removeTask, preventing appearance in recent tasks.
            mTaskId = taskId;
        }

        /**
         * This is only called for tasks on this ActivityView, which is also set to
         * single-task mode -- meaning never more than one task on this display. If a task
         * is being removed, it's the top Activity finishing and this bubble should
         * be removed or collapsed.
         */
        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (mEntry != null) {
                // Must post because this is called from a binder thread.
                post(() -> mBubbleController.removeBubble(mEntry.key,
                        BubbleController.DISMISS_TASK_FINISHED));
            }
        }
    };

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
        mDisplaySize = new Point();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getSize(mDisplaySize);
        mMinHeight = getResources().getDimensionPixelSize(
                R.dimen.bubble_expanded_default_height);
        mPointerMargin = getResources().getDimensionPixelSize(R.dimen.bubble_pointer_margin);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);


        mPointerDrawable = new ShapeDrawable(TriangleShape.create(
                mPointerWidth, mPointerHeight, true /* pointUp */));
        mPointerView.setBackground(mPointerDrawable);
        mPointerView.setVisibility(INVISIBLE);

        mSettingsIconHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.bubble_expanded_header_height);
        mSettingsIcon = findViewById(R.id.settings_button);
        mSettingsIcon.setOnClickListener(this);

        mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                true /* singleTaskInstance */);

        setContentVisibility(false);
        addView(mActivityView);

        // Expanded stack layout, top to bottom:
        // Expanded view container
        // ==> bubble row
        // ==> expanded view
        //   ==> activity view
        //   ==> manage button
        bringChildToFront(mActivityView);
        bringChildToFront(mSettingsIcon);

        applyThemeAttrs();

        setOnApplyWindowInsetsListener((View view, WindowInsets insets) -> {
            // Keep track of IME displaying because we should not make any adjustments that might
            // cause a config change while the IME is displayed otherwise it'll loose focus.
            final int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            mKeyboardVisible = keyboardHeight != 0;
            if (!mKeyboardVisible && mNeedsNewHeight) {
                updateHeight();
            }
            return view.onApplyWindowInsets(insets);
        });
    }

    void applyThemeAttrs() {
        TypedArray ta = getContext().obtainStyledAttributes(R.styleable.BubbleExpandedView);
        int bgColor = ta.getColor(
                R.styleable.BubbleExpandedView_android_colorBackgroundFloating, Color.WHITE);
        float cornerRadius = ta.getDimension(
                R.styleable.BubbleExpandedView_android_dialogCornerRadius, 0);
        ta.recycle();

        // Update triangle color.
        mPointerDrawable.setTint(bgColor);

        // Update ActivityView cornerRadius
        if (ScreenDecorationsUtils.supportsRoundedCornersOnWindows(mContext.getResources())) {
            mActivityView.setCornerRadius(cornerRadius);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyboardVisible = false;
        mNeedsNewHeight = false;
        if (mActivityView != null) {
            mActivityView.setForwardedInsets(Insets.of(0, 0, 0, 0));
        }
    }

    /**
     * Set visibility of contents in the expanded state.
     *
     * @param visibility {@code true} if the contents should be visible on the screen.
     *
     * Note that this contents visibility doesn't affect visibility at {@link android.view.View},
     * and setting {@code false} actually means rendering the contents in transparent.
     */
    void setContentVisibility(boolean visibility) {
        final float alpha = visibility ? 1f : 0f;
        mPointerView.setAlpha(alpha);
        if (mActivityView != null) {
            mActivityView.setAlpha(alpha);
        }
    }

    /**
     * Called by {@link BubbleStackView} when the insets for the expanded state should be updated.
     * This should be done post-move and post-animation.
     */
    void updateInsets(WindowInsets insets) {
        if (usingActivityView()) {
            int[] windowLocation = mActivityView.getLocationOnScreen();
            final int windowBottom = windowLocation[1] + mActivityView.getHeight();
            final int keyboardHeight = insets.getSystemWindowInsetBottom()
                    - insets.getStableInsetBottom();
            final int insetsBottom = Math.max(0,
                    windowBottom + keyboardHeight - mDisplaySize.y);
            mActivityView.setForwardedInsets(Insets.of(0, 0, 0, insetsBottom));
        }
    }

    /**
     * Sets the notification entry used to populate this view.
     */
    public void setEntry(NotificationEntry entry, BubbleStackView stackView, String appName) {
        mStackView = stackView;
        mEntry = entry;
        mAppName = appName;

        try {
            ApplicationInfo info = mPm.getApplicationInfo(
                    entry.notification.getPackageName(),
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            mAppIcon = mPm.getApplicationIcon(info);
        } catch (PackageManager.NameNotFoundException e) {
            // Do nothing.
        }
        if (mAppIcon == null) {
            mAppIcon = mPm.getDefaultActivityIcon();
        }
        applyThemeAttrs();
        showSettingsIcon();
        updateExpandedView();
    }

    /**
     * Lets activity view know it should be shown / populated.
     */
    public void populateExpandedView() {
        if (usingActivityView()) {
            mActivityView.setCallback(mStateCallback);
        } else {
            Log.e(TAG, "Cannot populate expanded view.");
        }
    }

    /**
     * Updates the entry backing this view. This will not re-populate ActivityView, it will
     * only update the deep-links in the title, and the height of the view.
     */
    public void update(NotificationEntry entry) {
        if (entry.key.equals(mEntry.key)) {
            mEntry = entry;
            updateSettingsContentDescription();
            updateHeight();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new entry: "
                    + entry.key + " old entry: " + mEntry.key);
        }
    }

    private void updateExpandedView() {
        mBubbleIntent = getBubbleIntent(mEntry);
        if (mBubbleIntent != null) {
            setContentVisibility(false);
            mActivityView.setVisibility(VISIBLE);
        }
        updateView();
    }

    boolean performBackPressIfNeeded() {
        if (!usingActivityView()) {
            return false;
        }
        mActivityView.performBackPress();
        return true;
    }

    void updateHeight() {
        if (usingActivityView()) {
            Notification.BubbleMetadata data = mEntry.getBubbleMetadata();
            float desiredHeight;
            if (data == null) {
                // This is a contentIntent based bubble, lets allow it to be the max height
                // as it was forced into this mode and not prepared to be small
                desiredHeight = getMaxExpandedHeight();
            } else {
                boolean useRes = data.getDesiredHeightResId() != 0;
                float desiredPx;
                if (useRes) {
                    desiredPx = getDimenForPackageUser(data.getDesiredHeightResId(),
                            mEntry.notification.getPackageName(),
                            mEntry.notification.getUser().getIdentifier());
                } else {
                    desiredPx = data.getDesiredHeight()
                            * getContext().getResources().getDisplayMetrics().density;
                }
                desiredHeight = desiredPx > 0 ? desiredPx : mMinHeight;
            }
            float height = Math.min(desiredHeight, getMaxExpandedHeight());
            height = Math.max(height, mMinHeight);
            LayoutParams lp = (LayoutParams) mActivityView.getLayoutParams();
            mNeedsNewHeight =  lp.height != height;
            if (!mKeyboardVisible) {
                // If the keyboard is visible... don't adjust the height because that will cause
                // a configuration change and the keyboard will be lost.
                lp.height = (int) height;
                mActivityView.setLayoutParams(lp);
                mNeedsNewHeight = false;
            }
        }
    }

    private int getMaxExpandedHeight() {
        int[] windowLocation = mActivityView.getLocationOnScreen();
        return mDisplaySize.y - windowLocation[1] - mSettingsIconHeight;
    }

    @Override
    public void onClick(View view) {
        if (mEntry == null) {
            return;
        }
        Notification n = mEntry.notification.getNotification();
        int id = view.getId();
        if (id == R.id.settings_button) {
            Intent intent = getSettingsIntent(mEntry.notification.getPackageName(),
                    mEntry.notification.getUid());
            mStackView.collapseStack(() -> {
                mContext.startActivityAsUser(intent, mEntry.notification.getUser());
                logBubbleClickEvent(mEntry,
                        StatsLog.BUBBLE_UICHANGED__ACTION__HEADER_GO_TO_SETTINGS);
            });
        }
    }

    private void updateSettingsContentDescription() {
        mSettingsIcon.setContentDescription(getResources().getString(
                R.string.bubbles_settings_button_description, mAppName));
    }

    void showSettingsIcon() {
        updateSettingsContentDescription();
        mSettingsIcon.setVisibility(VISIBLE);
    }

    /**
     * Update appearance of the expanded view being displayed.
     */
    public void updateView() {
        if (usingActivityView()
                && mActivityView.getVisibility() == VISIBLE
                && mActivityView.isAttachedToWindow()) {
            mActivityView.onLocationChanged();
        }
        updateHeight();
    }

    /**
     * Set the x position that the tip of the triangle should point to.
     */
    public void setPointerPosition(float x) {
        float halfPointerWidth = mPointerWidth / 2f;
        float pointerLeft = x - halfPointerWidth;
        mPointerView.setTranslationX(pointerLeft);
        mPointerView.setVisibility(VISIBLE);
    }

    /**
     * Removes and releases an ActivityView if one was previously created for this bubble.
     */
    public void cleanUpExpandedState() {
        if (mActivityView == null) {
            return;
        }
        switch (mActivityViewStatus) {
            case INITIALIZED:
            case ACTIVITY_STARTED:
                mActivityView.release();
        }
        if (mTaskId != -1) {
            try {
                ActivityTaskManager.getService().removeTask(mTaskId);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove taskId " + mTaskId);
            }
            mTaskId = -1;
        }
        removeView(mActivityView);

        mActivityView = null;
    }

    /**
     * Called when the last task is removed from a {@link android.hardware.display.VirtualDisplay}
     * which {@link ActivityView} uses.
     */
    void notifyDisplayEmpty() {
        if (mActivityViewStatus == ActivityViewStatus.ACTIVITY_STARTED) {
            mActivityViewStatus = ActivityViewStatus.INITIALIZED;
        }
    }

    private boolean usingActivityView() {
        return mBubbleIntent != null && mActivityView != null;
    }

    /**
     * @return the display id of the virtual display.
     */
    public int getVirtualDisplayId() {
        if (usingActivityView()) {
            return mActivityView.getVirtualDisplayId();
        }
        return INVALID_DISPLAY;
    }

    private Intent getSettingsIntent(String packageName, final int appUid) {
        final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
        intent.putExtra(Settings.EXTRA_APP_UID, appUid);
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return intent;
    }

    @Nullable
    private PendingIntent getBubbleIntent(NotificationEntry entry) {
        Notification notif = entry.notification.getNotification();
        Notification.BubbleMetadata data = notif.getBubbleMetadata();
        if (BubbleController.canLaunchInActivityView(mContext, entry) && data != null) {
            return data.getIntent();
        }
        return null;
    }

    /**
     * Logs bubble UI click event.
     *
     * @param entry the bubble notification entry that user is interacting with.
     * @param action the user interaction enum.
     */
    private void logBubbleClickEvent(NotificationEntry entry, int action) {
        StatusBarNotification notification = entry.notification;
        StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                notification.getPackageName(),
                notification.getNotification().getChannelId(),
                notification.getId(),
                mStackView.getBubbleIndex(mStackView.getExpandedBubble()),
                mStackView.getBubbleCount(),
                action,
                mStackView.getNormalizedXPosition(),
                mStackView.getNormalizedYPosition(),
                entry.showInShadeWhenBubble(),
                entry.isForegroundService(),
                false /* isAppForeground (unused) */);
    }

    private int getDimenForPackageUser(int resId, String pkg, int userId) {
        Resources r;
        if (pkg != null) {
            try {
                if (userId == UserHandle.USER_ALL) {
                    userId = UserHandle.USER_SYSTEM;
                }
                r = mPm.getResourcesForApplicationAsUser(pkg, userId);
                return r.getDimensionPixelSize(resId);
            } catch (PackageManager.NameNotFoundException ex) {
                // Uninstalled, don't care
            } catch (Resources.NotFoundException e) {
                // Invalid res id, return 0 and user our default
                Log.e(TAG, "Couldn't find desired height res id", e);
            }
        }
        return 0;
    }
}
