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

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_EXPANDED_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityView;
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
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.RemoteException;
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
    private Rect mTempRect = new Rect();
    private int[] mTempLoc = new int[2];
    private int mExpandedViewTouchSlop;

    private Bubble mBubble;
    private PackageManager mPm;
    private String mAppName;
    private Drawable mAppIcon;

    private BubbleController mBubbleController = Dependency.get(BubbleController.class);
    private WindowManager mWindowManager;

    private BubbleStackView mStackView;

    private ActivityView.StateCallback mStateCallback = new ActivityView.StateCallback() {
        @Override
        public void onActivityViewReady(ActivityView view) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onActivityViewReady: mActivityViewStatus=" + mActivityViewStatus
                        + " bubble=" + getBubbleKey());
            }
            switch (mActivityViewStatus) {
                case INITIALIZING:
                case INITIALIZED:
                    // Custom options so there is no activity transition animation
                    ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                            0 /* enterResId */, 0 /* exitResId */);
                    // Post to keep the lifecycle normal
                    post(() -> {
                        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                            Log.d(TAG, "onActivityViewReady: calling startActivity, "
                                    + "bubble=" + getBubbleKey());
                        }
                        try {
                            mActivityView.startActivity(mBubbleIntent, options);
                        } catch (RuntimeException e) {
                            // If there's a runtime exception here then there's something
                            // wrong with the intent, we can't really recover / try to populate
                            // the bubble again so we'll just remove it.
                            Log.w(TAG, "Exception while displaying bubble: " + getBubbleKey()
                                    + ", " + e.getMessage() + "; removing bubble");
                            mBubbleController.removeBubble(mBubble.getKey(),
                                    BubbleController.DISMISS_INVALID_INTENT);
                        }
                    });
                    mActivityViewStatus = ActivityViewStatus.ACTIVITY_STARTED;
            }
        }

        @Override
        public void onActivityViewDestroyed(ActivityView view) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onActivityViewDestroyed: mActivityViewStatus=" + mActivityViewStatus
                        + " bubble=" + getBubbleKey());
            }
            mActivityViewStatus = ActivityViewStatus.RELEASED;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName componentName) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskCreated: taskId=" + taskId
                        + " bubble=" + getBubbleKey());
            }
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
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId
                        + " mActivityViewStatus=" + mActivityViewStatus
                        + " bubble=" + getBubbleKey());
            }
            if (mBubble != null) {
                // Must post because this is called from a binder thread.
                post(() -> mBubbleController.removeBubble(mBubble.getKey(),
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
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        // Get the real size -- this includes screen decorations (notches, statusbar, navbar).
        mWindowManager.getDefaultDisplay().getRealSize(mDisplaySize);
        Resources res = getResources();
        mMinHeight = res.getDimensionPixelSize(R.dimen.bubble_expanded_default_height);
        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
        mExpandedViewTouchSlop = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_slop);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "onFinishInflate: bubble=" + getBubbleKey());
        }

        Resources res = getResources();
        mPointerView = findViewById(R.id.pointer_view);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);


        mPointerDrawable = new ShapeDrawable(TriangleShape.create(
                mPointerWidth, mPointerHeight, true /* pointUp */));
        mPointerView.setBackground(mPointerDrawable);
        mPointerView.setVisibility(INVISIBLE);

        mSettingsIconHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.bubble_settings_size);
        mSettingsIcon = findViewById(R.id.settings_button);
        mSettingsIcon.setOnClickListener(this);

        mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                true /* singleTaskInstance */);
        // Set ActivityView's alpha value as zero, since there is no view content to be shown.
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

    private String getBubbleKey() {
        return mBubble != null ? mBubble.getKey() : "null";
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "onDetachedFromWindow: bubble=" + getBubbleKey());
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "setContentVisibility: visibility=" + visibility
                    + " bubble=" + getBubbleKey());
        }
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
            int[] screenLoc = mActivityView.getLocationOnScreen();
            final int activityViewBottom = screenLoc[1] + mActivityView.getHeight();
            final int keyboardTop = mDisplaySize.y - Math.max(insets.getSystemWindowInsetBottom(),
                    insets.getDisplayCutout() != null
                            ? insets.getDisplayCutout().getSafeInsetBottom()
                            : 0);
            final int insetsBottom = Math.max(activityViewBottom - keyboardTop, 0);
            mActivityView.setForwardedInsets(Insets.of(0, 0, 0, insetsBottom));
        }
    }

    /**
     * Sets the bubble used to populate this view.
     */
    public void setBubble(Bubble bubble, BubbleStackView stackView, String appName) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "setBubble: bubble=" + (bubble != null ? bubble.getKey() : "null"));
        }

        mStackView = stackView;
        mBubble = bubble;
        mAppName = appName;

        try {
            ApplicationInfo info = mPm.getApplicationInfo(
                    bubble.getPackageName(),
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "populateExpandedView: "
                    + "bubble=" + getBubbleKey());
        }

        if (usingActivityView()) {
            mActivityView.setCallback(mStateCallback);
        } else {
            Log.e(TAG, "Cannot populate expanded view.");
        }
    }

    /**
     * Updates the bubble backing this view. This will not re-populate ActivityView, it will
     * only update the deep-links in the title, and the height of the view.
     */
    public void update(Bubble bubble) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "update: bubble=" + (bubble != null ? bubble.getKey() : "null"));
        }
        if (bubble.getKey().equals(mBubble.getKey())) {
            mBubble = bubble;
            updateSettingsContentDescription();
            updateHeight();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new bubble: "
                    + bubble.getKey() + " old bubble: " + bubble.getKey());
        }
    }

    private void updateExpandedView() {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "updateExpandedView: bubble="
                    + getBubbleKey());
        }

        mBubbleIntent = mBubble.getBubbleIntent(mContext);
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "updateHeight: bubble=" + getBubbleKey());
        }
        if (usingActivityView()) {
            float desiredHeight = Math.max(mBubble.getDesiredHeight(mContext), mMinHeight);
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
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "updateHeight: bubble=" + getBubbleKey() + " height=" + height
                        + " mNeedsNewHeight=" + mNeedsNewHeight);
            }
        }
    }

    private int getMaxExpandedHeight() {
        mWindowManager.getDefaultDisplay().getRealSize(mDisplaySize);
        int[] windowLocation = mActivityView.getLocationOnScreen();
        int bottomInset = getRootWindowInsets() != null
                ? getRootWindowInsets().getStableInsetBottom()
                : 0;
        return mDisplaySize.y - windowLocation[1] - mSettingsIconHeight - mPointerHeight
                - mPointerMargin - bottomInset;
    }

    /**
     * Whether the provided x, y values (in raw coordinates) are in a touchable area of the
     * expanded view.
     *
     * The touchable areas are the ActivityView (plus some slop around it) and the manage button.
     */
    boolean intersectingTouchableContent(int rawX, int rawY) {
        mTempRect.setEmpty();
        if (mActivityView != null) {
            mTempLoc = mActivityView.getLocationOnScreen();
            mTempRect.set(mTempLoc[0] - mExpandedViewTouchSlop,
                    mTempLoc[1] - mExpandedViewTouchSlop,
                    mTempLoc[0] + mActivityView.getWidth() + mExpandedViewTouchSlop,
                    mTempLoc[1] + mActivityView.getHeight() + mExpandedViewTouchSlop);
        }
        if (mTempRect.contains(rawX, rawY)) {
            return true;
        }
        mTempLoc = mSettingsIcon.getLocationOnScreen();
        mTempRect.set(mTempLoc[0],
                mTempLoc[1],
                mTempLoc[0] + mSettingsIcon.getWidth(),
                mTempLoc[1] + mSettingsIcon.getHeight());
        if (mTempRect.contains(rawX, rawY)) {
            return true;
        }
        return false;
    }

    @Override
    public void onClick(View view) {
        if (mBubble == null) {
            return;
        }
        int id = view.getId();
        if (id == R.id.settings_button) {
            Intent intent = mBubble.getSettingsIntent();
            mStackView.collapseStack(() -> {
                mContext.startActivityAsUser(intent, mBubble.getEntry().notification.getUser());
                logBubbleClickEvent(mBubble,
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "updateView: bubble="
                    + getBubbleKey());
        }
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "cleanUpExpandedState: mActivityViewStatus=" + mActivityViewStatus
                    + ", bubble=" + getBubbleKey());
        }
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
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "notifyDisplayEmpty: bubble="
                    + getBubbleKey()
                    + " mActivityViewStatus=" + mActivityViewStatus);
        }
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

    /**
     * Logs bubble UI click event.
     *
     * @param bubble the bubble notification entry that user is interacting with.
     * @param action the user interaction enum.
     */
    private void logBubbleClickEvent(Bubble bubble, int action) {
        StatusBarNotification notification = bubble.getEntry().notification;
        StatsLog.write(StatsLog.BUBBLE_UI_CHANGED,
                notification.getPackageName(),
                notification.getNotification().getChannelId(),
                notification.getId(),
                mStackView.getBubbleIndex(mStackView.getExpandedBubble()),
                mStackView.getBubbleCount(),
                action,
                mStackView.getNormalizedXPosition(),
                mStackView.getNormalizedYPosition(),
                bubble.showInShadeWhenBubble(),
                bubble.isOngoing(),
                false /* isAppForeground (unused) */);
    }
}
