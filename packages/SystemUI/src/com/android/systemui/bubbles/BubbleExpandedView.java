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

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.graphics.PixelFormat.TRANSPARENT;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.ViewRootImpl.sNewInsetsMode;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL;

import static com.android.systemui.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_EXPANDED_VIEW;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.systemui.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.ActivityView;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.hardware.display.VirtualDisplay;
import android.os.Binder;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.recents.TriangleShape;
import com.android.systemui.statusbar.AlphaOptimizedButton;

/**
 * Container for the expanded bubble view, handles rendering the caret and settings icon.
 */
public class BubbleExpandedView extends LinearLayout {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleExpandedView" : TAG_BUBBLES;
    private static final String WINDOW_TITLE = "ImeInsetsWindowWithoutContent";

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
    @Nullable private int[] mExpandedViewContainerLocation;

    private AlphaOptimizedButton mSettingsIcon;

    // Views for expanded state
    private ActivityView mActivityView;

    private ActivityViewStatus mActivityViewStatus = ActivityViewStatus.INITIALIZING;
    private int mTaskId = -1;

    private PendingIntent mPendingIntent;

    private boolean mKeyboardVisible;
    private boolean mNeedsNewHeight;

    private Point mDisplaySize;
    private int mMinHeight;
    private int mOverflowHeight;
    private int mSettingsIconHeight;
    private int mPointerWidth;
    private int mPointerHeight;
    private ShapeDrawable mPointerDrawable;
    private int mExpandedViewPadding;


    @Nullable private Bubble mBubble;

    private boolean mIsOverflow;

    private BubbleController mBubbleController = Dependency.get(BubbleController.class);
    private WindowManager mWindowManager;
    private ActivityManager mActivityManager;

    private BubbleStackView mStackView;
    private View mVirtualImeView;
    private WindowManager mVirtualDisplayWindowManager;
    private boolean mImeShowing = false;
    private float mCornerRadius = 0f;

    /**
     * Container for the ActivityView that has a solid, round-rect background that shows if the
     * ActivityView hasn't loaded.
     */
    private FrameLayout mActivityViewContainer = new FrameLayout(getContext());

    /** The SurfaceView that the ActivityView draws to. */
    @Nullable private SurfaceView mActivitySurface;

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
                    options.setTaskAlwaysOnTop(true);
                    options.setLaunchWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
                    // Post to keep the lifecycle normal
                    post(() -> {
                        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                            Log.d(TAG, "onActivityViewReady: calling startActivity, "
                                    + "bubble=" + getBubbleKey());
                        }
                        if (mActivityView == null) {
                            mBubbleController.removeBubble(getBubbleKey(),
                                    BubbleController.DISMISS_INVALID_INTENT);
                            return;
                        }
                        try {
                            if (!mIsOverflow && mBubble.hasMetadataShortcutId()
                                    && mBubble.getShortcutInfo() != null) {
                                options.setApplyActivityFlagsForBubbles(true);
                                mActivityView.startShortcutActivity(mBubble.getShortcutInfo(),
                                        options, null /* sourceBounds */);
                            } else {
                                Intent fillInIntent = new Intent();
                                // Apply flags to make behaviour match documentLaunchMode=always.
                                fillInIntent.addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                                fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                                if (mBubble != null) {
                                    mBubble.setIntentActive();
                                }
                                mActivityView.startActivity(mPendingIntent, fillInIntent, options);
                            }
                        } catch (RuntimeException e) {
                            // If there's a runtime exception here then there's something
                            // wrong with the intent, we can't really recover / try to populate
                            // the bubble again so we'll just remove it.
                            Log.w(TAG, "Exception while displaying bubble: " + getBubbleKey()
                                    + ", " + e.getMessage() + "; removing bubble");
                            mBubbleController.removeBubble(getBubbleKey(),
                                    BubbleController.DISMISS_INVALID_INTENT);
                        }
                    });
                    mActivityViewStatus = ActivityViewStatus.ACTIVITY_STARTED;
                    break;
                case ACTIVITY_STARTED:
                    post(() -> mActivityManager.moveTaskToFront(mTaskId, 0));
                    break;
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
        updateDimensions();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
    }

    void updateDimensions() {
        mDisplaySize = new Point();
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        // Get the real size -- this includes screen decorations (notches, statusbar, navbar).
        mWindowManager.getDefaultDisplay().getRealSize(mDisplaySize);
        Resources res = getResources();
        mMinHeight = res.getDimensionPixelSize(R.dimen.bubble_expanded_default_height);
        mOverflowHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height);
        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
    }

    @SuppressLint("ClickableViewAccessibility")
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
        mPointerView.setVisibility(INVISIBLE);

        mSettingsIconHeight = getContext().getResources().getDimensionPixelSize(
                R.dimen.bubble_manage_button_height);
        mSettingsIcon = findViewById(R.id.settings_button);

        mActivityView = new ActivityView(mContext, null /* attrs */, 0 /* defStyle */,
                true /* singleTaskInstance */, false /* usePublicVirtualDisplay*/,
                true /* disableSurfaceViewBackgroundLayer */, true /* useTrustedDisplay */);

        // Set ActivityView's alpha value as zero, since there is no view content to be shown.
        setContentVisibility(false);

        mActivityViewContainer.setBackgroundColor(Color.WHITE);
        mActivityViewContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mActivityViewContainer.setClipToOutline(true);
        mActivityViewContainer.addView(mActivityView);
        mActivityViewContainer.setLayoutParams(
                new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(mActivityViewContainer);

        if (mActivityView != null
                && mActivityView.getChildCount() > 0
                && mActivityView.getChildAt(0) instanceof SurfaceView) {
            // Retrieve the surface from the ActivityView so we can screenshot it and change its
            // z-ordering. This should always be possible, since ActivityView's constructor adds the
            // SurfaceView as its first child.
            mActivitySurface = (SurfaceView) mActivityView.getChildAt(0);
        }

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

        mExpandedViewPadding = res.getDimensionPixelSize(R.dimen.bubble_expanded_view_padding);
        setPadding(mExpandedViewPadding, mExpandedViewPadding, mExpandedViewPadding,
                mExpandedViewPadding);
        setOnTouchListener((view, motionEvent) -> {
            if (!usingActivityView()) {
                return false;
            }

            final Rect avBounds = new Rect();
            mActivityView.getBoundsOnScreen(avBounds);

            // Consume and ignore events on the expanded view padding that are within the
            // ActivityView's vertical bounds. These events are part of a back gesture, and so they
            // should not collapse the stack (which all other touches on areas around the AV would
            // do).
            if (motionEvent.getRawY() >= avBounds.top
                            && motionEvent.getRawY() <= avBounds.bottom
                            && (motionEvent.getRawX() < avBounds.left
                                || motionEvent.getRawX() > avBounds.right)) {
                return true;
            }

            return false;
        });

        // BubbleStackView is forced LTR, but we want to respect the locale for expanded view layout
        // so the Manage button appears on the right.
        setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
    }

    private String getBubbleKey() {
        return mBubble != null ? mBubble.getKey() : "null";
    }

    /**
     * Asks the ActivityView's surface to draw on top of all other views in the window. This is
     * useful for ordering surfaces during animations, but should otherwise be set to false so that
     * bubbles and menus can draw over the ActivityView.
     */
    void setSurfaceZOrderedOnTop(boolean onTop) {
        if (mActivitySurface == null) {
            return;
        }

        mActivitySurface.setZOrderedOnTop(onTop, true);
    }

    /** Return a GraphicBuffer with the contents of the ActivityView's underlying surface. */
    @Nullable SurfaceControl.ScreenshotGraphicBuffer snapshotActivitySurface() {
        if (mActivitySurface == null) {
            return null;
        }

        return SurfaceControl.captureLayers(
                mActivitySurface.getSurfaceControl(),
                new Rect(0, 0, mActivityView.getWidth(), mActivityView.getHeight()),
                1 /* scale */);
    }

    int[] getActivityViewLocationOnScreen() {
        if (mActivityView != null) {
            return mActivityView.getLocationOnScreen();
        } else {
            return new int[]{0, 0};
        }
    }

    void setManageClickListener(OnClickListener manageClickListener) {
        findViewById(R.id.settings_button).setOnClickListener(manageClickListener);
    }

    /**
     * Updates the ActivityView's obscured touchable region. This calls onLocationChanged, which
     * results in a call to {@link BubbleStackView#subtractObscuredTouchableRegion}. This is useful
     * if a view has been added or removed from on top of the ActivityView, such as the manage menu.
     */
    void updateObscuredTouchableRegion() {
        if (mActivityView != null) {
            mActivityView.onLocationChanged();
        }
    }

    void applyThemeAttrs() {
        final TypedArray ta = mContext.obtainStyledAttributes(
                new int[] {android.R.attr.dialogCornerRadius});
        mCornerRadius = ta.getDimensionPixelSize(0, 0);
        ta.recycle();

        if (mActivityView != null && ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources())) {
            mActivityView.setCornerRadius(mCornerRadius);
        }

        final int mode =
                getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        switch (mode) {
            case Configuration.UI_MODE_NIGHT_NO:
                mPointerDrawable.setTint(getResources().getColor(R.color.bubbles_light));
                break;
            case Configuration.UI_MODE_NIGHT_YES:
                mPointerDrawable.setTint(getResources().getColor(R.color.bubbles_dark));
                break;
        }
        mPointerView.setBackground(mPointerDrawable);
    }

    /**
     * Hides the IME if it's showing. This is currently done by dispatching a back press to the AV.
     */
    void hideImeIfVisible() {
        if (mKeyboardVisible) {
            performBackPressIfNeeded();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyboardVisible = false;
        mNeedsNewHeight = false;
        if (mActivityView != null) {
            if (sNewInsetsMode == NEW_INSETS_MODE_FULL) {
                setImeWindowToDisplay(0, 0);
            } else {
                mActivityView.setForwardedInsets(Insets.of(0, 0, 0, 0));
            }
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

        if (mActivityView != null && alpha != mActivityView.getAlpha()) {
            mActivityView.setAlpha(alpha);
            mActivityView.bringToFront();
        }
    }

    @Nullable ActivityView getActivityView() {
        return mActivityView;
    }

    int getTaskId() {
        return mTaskId;
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

            if (sNewInsetsMode == NEW_INSETS_MODE_FULL) {
                setImeWindowToDisplay(getWidth(), insetsBottom);
            } else {
                mActivityView.setForwardedInsets(Insets.of(0, 0, 0, insetsBottom));
            }
        }
    }

    private void setImeWindowToDisplay(int w, int h) {
        if (getVirtualDisplayId() == INVALID_DISPLAY) {
            return;
        }
        if (h == 0 || w == 0) {
            if (mImeShowing) {
                mVirtualImeView.setVisibility(GONE);
                mImeShowing = false;
            }
            return;
        }
        final Context virtualDisplayContext = mContext.createDisplayContext(
                getVirtualDisplay().getDisplay());

        if (mVirtualDisplayWindowManager == null) {
            mVirtualDisplayWindowManager =
                    (WindowManager) virtualDisplayContext.getSystemService(Context.WINDOW_SERVICE);
        }
        if (mVirtualImeView == null) {
            mVirtualImeView = new View(virtualDisplayContext);
            mVirtualImeView.setVisibility(VISIBLE);
            mVirtualDisplayWindowManager.addView(mVirtualImeView,
                    getVirtualImeViewAttrs(w, h));
        } else {
            mVirtualDisplayWindowManager.updateViewLayout(mVirtualImeView,
                    getVirtualImeViewAttrs(w, h));
            mVirtualImeView.setVisibility(VISIBLE);
        }

        mImeShowing = true;
    }

    private WindowManager.LayoutParams getVirtualImeViewAttrs(int w, int h) {
        // To use TYPE_NAVIGATION_BAR_PANEL instead of TYPE_IME_BAR to bypass the IME window type
        // token check when adding the window.
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(w, h, TYPE_NAVIGATION_BAR_PANEL,
                        FLAG_LAYOUT_NO_LIMITS | FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE,
                        TRANSPARENT);
        attrs.gravity = Gravity.BOTTOM;
        attrs.setTitle(WINDOW_TITLE);
        attrs.token = new Binder();
        attrs.providesInsetsTypes = new int[]{ITYPE_IME};
        attrs.alpha = 0.0f;
        return attrs;
    }

    void setStackView(BubbleStackView stackView) {
        mStackView = stackView;
    }

    public void setOverflow(boolean overflow) {
        mIsOverflow = overflow;

        Intent target = new Intent(mContext, BubbleOverflowActivity.class);
        mPendingIntent = PendingIntent.getActivity(mContext, /* requestCode */ 0,
                target, PendingIntent.FLAG_UPDATE_CURRENT);
        mSettingsIcon.setVisibility(GONE);
    }

    /**
     * Sets the bubble used to populate this view.
     */
    void update(Bubble bubble) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "update: bubble=" + (bubble != null ? bubble.getKey() : "null"));
        }
        boolean isNew = mBubble == null || didBackingContentChange(bubble);
        if (isNew || bubble != null && bubble.getKey().equals(mBubble.getKey())) {
            mBubble = bubble;
            mSettingsIcon.setContentDescription(getResources().getString(
                    R.string.bubbles_settings_button_description, bubble.getAppName()));

            mSettingsIcon.setAccessibilityDelegate(
                    new AccessibilityDelegate() {
                        @Override
                        public void onInitializeAccessibilityNodeInfo(View host,
                                AccessibilityNodeInfo info) {
                            super.onInitializeAccessibilityNodeInfo(host, info);
                            // On focus, have TalkBack say
                            // "Actions available. Use swipe up then right to view."
                            // in addition to the default "double tap to activate".
                            mStackView.setupLocalMenu(info);
                        }
                    });

            if (isNew) {
                mPendingIntent = mBubble.getBubbleIntent();
                if (mPendingIntent != null || mBubble.hasMetadataShortcutId()) {
                    setContentVisibility(false);
                    mActivityView.setVisibility(VISIBLE);
                }
            }
            applyThemeAttrs();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new bubble: "
                    + bubble.getKey() + " old bubble: " + bubble.getKey());
        }
    }

    private boolean didBackingContentChange(Bubble newBubble) {
        boolean prevWasIntentBased = mBubble != null && mPendingIntent != null;
        boolean newIsIntentBased = newBubble.getBubbleIntent() != null;
        return prevWasIntentBased != newIsIntentBased;
    }

    /**
     * Lets activity view know it should be shown / populated with activity content.
     */
    void populateExpandedView() {
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

        if (mExpandedViewContainerLocation == null) {
            return;
        }

        if (usingActivityView()) {
            float desiredHeight = mOverflowHeight;
            if (!mIsOverflow) {
                desiredHeight = Math.max(mBubble.getDesiredHeight(mContext), mMinHeight);
            }
            float height = Math.min(desiredHeight, getMaxExpandedHeight());
            height = Math.max(height, mMinHeight);
            ViewGroup.LayoutParams lp = mActivityView.getLayoutParams();
            mNeedsNewHeight = lp.height != height;
            if (!mKeyboardVisible) {
                // If the keyboard is visible... don't adjust the height because that will cause
                // a configuration change and the keyboard will be lost.
                lp.height = (int) height;
                mActivityView.setLayoutParams(lp);
                mNeedsNewHeight = false;
            }
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "updateHeight: bubble=" + getBubbleKey()
                        + " height=" + height
                        + " mNeedsNewHeight=" + mNeedsNewHeight);
            }
        }
    }

    private int getMaxExpandedHeight() {
        mWindowManager.getDefaultDisplay().getRealSize(mDisplaySize);
        int bottomInset = getRootWindowInsets() != null
                ? getRootWindowInsets().getStableInsetBottom()
                : 0;

        return mDisplaySize.y
                - mExpandedViewContainerLocation[1]
                - getPaddingTop()
                - getPaddingBottom()
                - mSettingsIconHeight
                - mPointerHeight
                - mPointerMargin - bottomInset;
    }

    /**
     * Update appearance of the expanded view being displayed.
     *
     * @param containerLocationOnScreen The location on-screen of the container the expanded view is
     *                                  added to. This allows us to calculate max height without
     *                                  waiting for layout.
     */
    public void updateView(int[] containerLocationOnScreen) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "updateView: bubble="
                    + getBubbleKey());
        }

        mExpandedViewContainerLocation = containerLocationOnScreen;

        if (usingActivityView()
                && mActivityView.getVisibility() == VISIBLE
                && mActivityView.isAttachedToWindow()) {
            mActivityView.onLocationChanged();
            updateHeight();
        }
    }

    /**
     * Set the x position that the tip of the triangle should point to.
     */
    public void setPointerPosition(float x) {
        float halfPointerWidth = mPointerWidth / 2f;
        float pointerLeft = x - halfPointerWidth - mExpandedViewPadding;
        mPointerView.setTranslationX(pointerLeft);
        mPointerView.setVisibility(VISIBLE);
    }

    /**
     * Position of the manage button displayed in the expanded view. Used for placing user
     * education about the manage button.
     */
    public void getManageButtonBoundsOnScreen(Rect rect) {
        mSettingsIcon.getBoundsOnScreen(rect);
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
        mActivityView.release();
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
        return (mPendingIntent != null || mBubble.hasMetadataShortcutId())
                && mActivityView != null;
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

    private VirtualDisplay getVirtualDisplay() {
        if (usingActivityView()) {
            return mActivityView.getVirtualDisplay();
        }
        return null;
    }
}
