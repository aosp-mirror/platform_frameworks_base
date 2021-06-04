/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.bubbles;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import static com.android.wm.shell.bubbles.BubbleDebugConfig.DEBUG_BUBBLE_EXPANDED_VIEW;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_BUBBLES;
import static com.android.wm.shell.bubbles.BubbleDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.app.ActivityOptions;
import android.app.ActivityTaskManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.launcher3.icons.IconNormalizer;
import com.android.wm.shell.R;
import com.android.wm.shell.TaskView;
import com.android.wm.shell.common.AlphaOptimizedButton;
import com.android.wm.shell.common.TriangleShape;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Container for the expanded bubble view, handles rendering the caret and settings icon.
 */
public class BubbleExpandedView extends LinearLayout {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "BubbleExpandedView" : TAG_BUBBLES;

    // The triangle pointing to the expanded view
    private View mPointerView;
    private int mPointerMargin;
    @Nullable private int[] mExpandedViewContainerLocation;

    private AlphaOptimizedButton mManageButton;
    private TaskView mTaskView;
    private BubbleOverflowContainerView mOverflowView;

    private int mTaskId = INVALID_TASK_ID;

    private boolean mImeVisible;
    private boolean mNeedsNewHeight;

    /**
     * Whether we want the TaskView's content to be visible (alpha = 1f). If
     * {@link #mIsAlphaAnimating} is true, this may not reflect the TaskView's actual alpha value
     * until the animation ends.
     */
    private boolean mIsContentVisible = false;

    /**
     * Whether we're animating the TaskView's alpha value. If so, we will hold off on applying alpha
     * changes from {@link #setContentVisibility} until the animation ends.
     */
    private boolean mIsAlphaAnimating = false;

    private int mMinHeight;
    private int mOverflowHeight;
    private int mManageButtonHeight;
    private int mPointerWidth;
    private int mPointerHeight;
    private float mPointerRadius;
    private float mPointerOverlap;
    private CornerPathEffect mPointerEffect;
    private ShapeDrawable mCurrentPointer;
    private ShapeDrawable mTopPointer;
    private ShapeDrawable mLeftPointer;
    private ShapeDrawable mRightPointer;
    private float mCornerRadius = 0f;
    private int mBackgroundColorFloating;

    @Nullable private Bubble mBubble;
    private PendingIntent mPendingIntent;
    // TODO(b/170891664): Don't use a flag, set the BubbleOverflow object instead
    private boolean mIsOverflow;

    private BubbleController mController;
    private BubbleStackView mStackView;
    private BubblePositioner mPositioner;

    /**
     * Container for the ActivityView that has a solid, round-rect background that shows if the
     * ActivityView hasn't loaded.
     */
    private final FrameLayout mExpandedViewContainer = new FrameLayout(getContext());

    private final TaskView.Listener mTaskViewListener = new TaskView.Listener() {
        private boolean mInitialized = false;
        private boolean mDestroyed = false;

        @Override
        public void onInitialized() {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onActivityViewReady: destroyed=" + mDestroyed
                        + " initialized=" + mInitialized
                        + " bubble=" + getBubbleKey());
            }

            if (mDestroyed || mInitialized) {
                return;
            }

            // Custom options so there is no activity transition animation
            ActivityOptions options = ActivityOptions.makeCustomAnimation(getContext(),
                    0 /* enterResId */, 0 /* exitResId */);

            Rect launchBounds = new Rect();
            mTaskView.getBoundsOnScreen(launchBounds);

            // TODO: I notice inconsistencies in lifecycle
            // Post to keep the lifecycle normal
            post(() -> {
                if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                    Log.d(TAG, "onActivityViewReady: calling startActivity, bubble="
                            + getBubbleKey());
                }
                try {
                    options.setTaskAlwaysOnTop(true);
                    options.setLaunchedFromBubble(true);
                    if (!mIsOverflow && mBubble.hasMetadataShortcutId()) {
                        options.setApplyActivityFlagsForBubbles(true);
                        mTaskView.startShortcutActivity(mBubble.getShortcutInfo(),
                                options, launchBounds);
                    } else {
                        Intent fillInIntent = new Intent();
                        // Apply flags to make behaviour match documentLaunchMode=always.
                        fillInIntent.addFlags(FLAG_ACTIVITY_NEW_DOCUMENT);
                        fillInIntent.addFlags(FLAG_ACTIVITY_MULTIPLE_TASK);
                        if (mBubble != null) {
                            mBubble.setIntentActive();
                        }
                        mTaskView.startActivity(mPendingIntent, fillInIntent, options,
                                launchBounds);
                    }
                } catch (RuntimeException e) {
                    // If there's a runtime exception here then there's something
                    // wrong with the intent, we can't really recover / try to populate
                    // the bubble again so we'll just remove it.
                    Log.w(TAG, "Exception while displaying bubble: " + getBubbleKey()
                            + ", " + e.getMessage() + "; removing bubble");
                    mController.removeBubble(getBubbleKey(), Bubbles.DISMISS_INVALID_INTENT);
                }
            });
            mInitialized = true;
        }

        @Override
        public void onReleased() {
            mDestroyed = true;
        }

        @Override
        public void onTaskCreated(int taskId, ComponentName name) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskCreated: taskId=" + taskId
                        + " bubble=" + getBubbleKey());
            }
            // The taskId is saved to use for removeTask, preventing appearance in recent tasks.
            mTaskId = taskId;

            // With the task org, the taskAppeared callback will only happen once the task has
            // already drawn
            setContentVisibility(true);
        }

        @Override
        public void onTaskVisibilityChanged(int taskId, boolean visible) {
            setContentVisibility(visible);
        }

        @Override
        public void onTaskRemovalStarted(int taskId) {
            if (DEBUG_BUBBLE_EXPANDED_VIEW) {
                Log.d(TAG, "onTaskRemovalStarted: taskId=" + taskId
                        + " bubble=" + getBubbleKey());
            }
            if (mBubble != null) {
                // Must post because this is called from a binder thread.
                post(() -> mController.removeBubble(
                        mBubble.getKey(), Bubbles.DISMISS_TASK_FINISHED));
            }
        }

        @Override
        public void onBackPressedOnTaskRoot(int taskId) {
            if (mTaskId == taskId && mStackView.isExpanded()) {
                mController.collapseStack();
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
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mManageButton = (AlphaOptimizedButton) LayoutInflater.from(getContext()).inflate(
                R.layout.bubble_manage_button, this /* parent */, false /* attach */);
        updateDimensions();
        mPointerView = findViewById(R.id.pointer_view);
        mCurrentPointer = mTopPointer;
        mPointerView.setVisibility(INVISIBLE);

        // Set TaskView's alpha value as zero, since there is no view content to be shown.
        setContentVisibility(false);

        mExpandedViewContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), mCornerRadius);
            }
        });
        mExpandedViewContainer.setClipToOutline(true);
        mExpandedViewContainer.setLayoutParams(
                new ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        addView(mExpandedViewContainer);

        // Expanded stack layout, top to bottom:
        // Expanded view container
        // ==> bubble row
        // ==> expanded view
        //   ==> activity view
        //   ==> manage button
        bringChildToFront(mManageButton);

        applyThemeAttrs();

        setClipToPadding(false);

        // BubbleStackView is forced LTR, but we want to respect the locale for expanded view layout
        // so the Manage button appears on the right.
        setLayoutDirection(LAYOUT_DIRECTION_LOCALE);
    }

    /**
     * Initialize {@link BubbleController} and {@link BubbleStackView} here, this method must need
     * to be called after view inflate.
     */
    void initialize(BubbleController controller, BubbleStackView stackView, boolean isOverflow) {
        mController = controller;
        mStackView = stackView;
        mIsOverflow = isOverflow;
        mPositioner = mController.getPositioner();

        if (mIsOverflow) {
            mOverflowView = (BubbleOverflowContainerView) LayoutInflater.from(getContext()).inflate(
                    R.layout.bubble_overflow_container, null /* root */);
            mOverflowView.setBubbleController(mController);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT);
            mExpandedViewContainer.addView(mOverflowView, lp);
            mExpandedViewContainer.setLayoutParams(
                    new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            bringChildToFront(mOverflowView);
            mManageButton.setVisibility(GONE);
        } else {
            mTaskView = new TaskView(mContext, mController.getTaskOrganizer());
            mTaskView.setListener(mController.getMainExecutor(), mTaskViewListener);
            mExpandedViewContainer.addView(mTaskView);
            bringChildToFront(mTaskView);
        }
    }

    void updateDimensions() {
        Resources res = getResources();
        mMinHeight = res.getDimensionPixelSize(R.dimen.bubble_expanded_default_height);
        mOverflowHeight = res.getDimensionPixelSize(R.dimen.bubble_overflow_height);

        updateFontSize();

        mPointerMargin = res.getDimensionPixelSize(R.dimen.bubble_pointer_margin);
        mPointerWidth = res.getDimensionPixelSize(R.dimen.bubble_pointer_width);
        mPointerHeight = res.getDimensionPixelSize(R.dimen.bubble_pointer_height);
        mPointerRadius = getResources().getDimensionPixelSize(R.dimen.bubble_pointer_radius);
        mPointerEffect = new CornerPathEffect(mPointerRadius);
        mPointerOverlap = getResources().getDimensionPixelSize(R.dimen.bubble_pointer_overlap);
        mTopPointer = new ShapeDrawable(TriangleShape.create(
                mPointerWidth, mPointerHeight, true /* pointUp */));
        mLeftPointer = new ShapeDrawable(TriangleShape.createHorizontal(
                mPointerWidth, mPointerHeight, true /* pointLeft */));
        mRightPointer = new ShapeDrawable(TriangleShape.createHorizontal(
                mPointerWidth, mPointerHeight, false /* pointLeft */));
        if (mPointerView != null) {
            updatePointerView();
        }

        mManageButtonHeight = res.getDimensionPixelSize(R.dimen.bubble_manage_button_height);
        if (mManageButton != null) {
            int visibility = mManageButton.getVisibility();
            removeView(mManageButton);
            mManageButton = (AlphaOptimizedButton) LayoutInflater.from(getContext()).inflate(
                    R.layout.bubble_manage_button, this /* parent */, false /* attach */);
            addView(mManageButton);
            mManageButton.setVisibility(visibility);
        }
    }

    void updateFontSize() {
        final float fontSize = mContext.getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.text_size_body_2_material);
        if (mManageButton != null) {
            mManageButton.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize);
        }
        if (mOverflowView != null) {
            mOverflowView.updateFontSize();
        }
    }

    void applyThemeAttrs() {
        final TypedArray ta = mContext.obtainStyledAttributes(new int[] {
                android.R.attr.dialogCornerRadius,
                android.R.attr.colorBackgroundFloating});
        mCornerRadius = ta.getDimensionPixelSize(0, 0);
        mBackgroundColorFloating = ta.getColor(1, Color.WHITE);
        mExpandedViewContainer.setBackgroundColor(mBackgroundColorFloating);
        ta.recycle();

        if (mTaskView != null && ScreenDecorationsUtils.supportsRoundedCornersOnWindows(
                mContext.getResources())) {
            mTaskView.setCornerRadius(mCornerRadius);
        }
        updatePointerView();
    }

    private void updatePointerView() {
        LayoutParams lp = (LayoutParams) mPointerView.getLayoutParams();
        if (mCurrentPointer == mLeftPointer || mCurrentPointer == mRightPointer) {
            lp.width = mPointerHeight;
            lp.height = mPointerWidth;
        } else {
            lp.width = mPointerWidth;
            lp.height = mPointerHeight;
        }
        mCurrentPointer.setTint(mBackgroundColorFloating);

        Paint arrowPaint = mCurrentPointer.getPaint();
        arrowPaint.setColor(mBackgroundColorFloating);
        arrowPaint.setPathEffect(mPointerEffect);
        mPointerView.setLayoutParams(lp);
        mPointerView.setBackground(mCurrentPointer);
    }

    private String getBubbleKey() {
        return mBubble != null ? mBubble.getKey() : "null";
    }

    /**
     * Sets whether the surface displaying app content should sit on top. This is useful for
     * ordering surfaces during animations. When content is drawn on top of the app (e.g. bubble
     * being dragged out, the manage menu) this is set to false, otherwise it should be true.
     */
    void setSurfaceZOrderedOnTop(boolean onTop) {
        if (mTaskView == null) {
            return;
        }
        mTaskView.setZOrderedOnTop(onTop, true /* allowDynamicChange */);
    }

    void setImeVisible(boolean visible) {
        mImeVisible = visible;
        if (!mImeVisible && mNeedsNewHeight) {
            updateHeight();
        }
    }

    /** Return a GraphicBuffer with the contents of the task view surface. */
    @Nullable
    SurfaceControl.ScreenshotHardwareBuffer snapshotActivitySurface() {
        if (mIsOverflow) {
            // For now, just snapshot the view and return it as a hw buffer so that the animation
            // code for both the tasks and overflow can be the same
            Picture p = new Picture();
            mOverflowView.draw(
                    p.beginRecording(mOverflowView.getWidth(), mOverflowView.getHeight()));
            p.endRecording();
            Bitmap snapshot = Bitmap.createBitmap(p);
            return new SurfaceControl.ScreenshotHardwareBuffer(snapshot.getHardwareBuffer(),
                    snapshot.getColorSpace(), false /* containsSecureLayers */);
        }
        if (mTaskView == null || mTaskView.getSurfaceControl() == null) {
            return null;
        }
        return SurfaceControl.captureLayers(
                mTaskView.getSurfaceControl(),
                new Rect(0, 0, mTaskView.getWidth(), mTaskView.getHeight()),
                1 /* scale */);
    }

    int[] getTaskViewLocationOnScreen() {
        if (mIsOverflow) {
            // This is only used for animating away the surface when switching bubbles, just use the
            // view location on screen for now to allow us to use the same animation code with tasks
            return mOverflowView.getLocationOnScreen();
        }
        if (mTaskView != null) {
            return mTaskView.getLocationOnScreen();
        } else {
            return new int[]{0, 0};
        }
    }

    // TODO: Could listener be passed when we pass StackView / can we avoid setting this like this
    void setManageClickListener(OnClickListener manageClickListener) {
        mManageButton.setOnClickListener(manageClickListener);
    }

    /**
     * Updates the obscured touchable region for the task surface. This calls onLocationChanged,
     * which results in a call to {@link BubbleStackView#subtractObscuredTouchableRegion}. This is
     * useful if a view has been added or removed from on top of the ActivityView, such as the
     * manage menu.
     */
    void updateObscuredTouchableRegion() {
        if (mTaskView != null) {
            mTaskView.onLocationChanged();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mImeVisible = false;
        mNeedsNewHeight = false;
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "onDetachedFromWindow: bubble=" + getBubbleKey());
        }
    }

    /**
     * Whether we are currently animating the TaskView's alpha value. If this is set to true, calls
     * to {@link #setContentVisibility} will not be applied until this is set to false again.
     */
    void setAlphaAnimating(boolean animating) {
        mIsAlphaAnimating = animating;

        // If we're done animating, apply the correct
        if (!animating) {
            setContentVisibility(mIsContentVisible);
        }
    }

    /**
     * Sets the alpha of the underlying TaskView, since changing the expanded view's alpha does not
     * affect the TaskView since it uses a Surface.
     */
    void setTaskViewAlpha(float alpha) {
        if (mTaskView != null) {
            mTaskView.setAlpha(alpha);
        }
        if (mManageButton != null && mManageButton.getVisibility() == View.VISIBLE) {
            mManageButton.setAlpha(alpha);
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
        mIsContentVisible = visibility;
        if (mTaskView != null && !mIsAlphaAnimating) {
            mTaskView.setAlpha(visibility ? 1f : 0f);
        }
    }

    @Nullable
    TaskView getTaskView() {
        return mTaskView;
    }

    int getTaskId() {
        return mTaskId;
    }

    /**
     * Sets the bubble used to populate this view.
     */
    void update(Bubble bubble) {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "update: bubble=" + bubble);
        }
        if (mStackView == null) {
            Log.w(TAG, "Stack is null for bubble: " + bubble);
            return;
        }
        boolean isNew = mBubble == null || didBackingContentChange(bubble);
        if (isNew || bubble != null && bubble.getKey().equals(mBubble.getKey())) {
            mBubble = bubble;
            mManageButton.setContentDescription(getResources().getString(
                    R.string.bubbles_settings_button_description, bubble.getAppName()));
            mManageButton.setAccessibilityDelegate(
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
                if ((mPendingIntent != null || mBubble.hasMetadataShortcutId())
                        && mTaskView != null) {
                    setContentVisibility(false);
                    mTaskView.setVisibility(VISIBLE);
                }
            }
            applyThemeAttrs();
        } else {
            Log.w(TAG, "Trying to update entry with different key, new bubble: "
                    + bubble.getKey() + " old bubble: " + bubble.getKey());
        }
    }

    /**
     * Bubbles are backed by a pending intent or a shortcut, once the activity is
     * started we never change it / restart it on notification updates -- unless the bubbles'
     * backing data switches.
     *
     * This indicates if the new bubble is backed by a different data source than what was
     * previously shown here (e.g. previously a pending intent & now a shortcut).
     *
     * @param newBubble the bubble this view is being updated with.
     * @return true if the backing content has changed.
     */
    private boolean didBackingContentChange(Bubble newBubble) {
        boolean prevWasIntentBased = mBubble != null && mPendingIntent != null;
        boolean newIsIntentBased = newBubble.getBubbleIntent() != null;
        return prevWasIntentBased != newIsIntentBased;
    }

    void updateHeight() {
        if (mExpandedViewContainerLocation == null) {
            return;
        }

        if ((mBubble != null && mTaskView != null) || mIsOverflow) {
            float desiredHeight = mIsOverflow
                    ? mPositioner.isLargeScreen() ? getMaxExpandedHeight() : mOverflowHeight
                    : mBubble.getDesiredHeight(mContext);
            desiredHeight = Math.max(desiredHeight, mMinHeight);
            float height = Math.min(desiredHeight, getMaxExpandedHeight());
            height = Math.max(height, mMinHeight);
            FrameLayout.LayoutParams lp = mIsOverflow
                    ? (FrameLayout.LayoutParams) mOverflowView.getLayoutParams()
                    : (FrameLayout.LayoutParams) mTaskView.getLayoutParams();
            mNeedsNewHeight = lp.height != height;
            if (!mImeVisible) {
                // If the ime is visible... don't adjust the height because that will cause
                // a configuration change and the ime will be lost.
                lp.height = (int) height;
                if (mIsOverflow) {
                    mOverflowView.setLayoutParams(lp);
                } else {
                    mTaskView.setLayoutParams(lp);
                }
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
        int expandedContainerY = mExpandedViewContainerLocation != null
                // Remove top insets back here because availableRect.height would account for that
                ? mExpandedViewContainerLocation[1] - mPositioner.getInsets().top
                : 0;
        int settingsHeight = mIsOverflow ? 0 : mManageButtonHeight;
        int pointerHeight = mPositioner.showBubblesVertically()
                ? mPointerWidth
                : (int) (mPointerHeight - mPointerOverlap + mPointerMargin);
        return mPositioner.getAvailableRect().height()
                - expandedContainerY
                - getPaddingTop()
                - getPaddingBottom()
                - settingsHeight
                - pointerHeight;
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
        updateHeight();
        if (mTaskView != null
                && mTaskView.getVisibility() == VISIBLE
                && mTaskView.isAttachedToWindow()) {
            mTaskView.onLocationChanged();
        }
        if (mIsOverflow) {
            mOverflowView.show();
        }
    }

    /**
     * Sets the position of the pointer.
     *
     * When bubbles are showing "vertically" they display along the left / right sides of the
     * screen with the expanded view beside them.
     *
     * If they aren't showing vertically they're positioned along the top of the screen with the
     * expanded view below them.
     *
     * @param bubblePosition the x position of the bubble if showing on top, the y position of
     *                       the bubble if showing vertically.
     * @param onLeft whether the stack was on the left side of the screen when expanded.
     */
    public void setPointerPosition(float bubblePosition, boolean onLeft) {
        // Pointer gets drawn in the padding
        final boolean showVertically = mPositioner.showBubblesVertically();
        final float paddingLeft = (showVertically && onLeft)
                ? mPointerHeight - mPointerOverlap
                : 0;
        final float paddingRight = (showVertically && !onLeft)
                ? mPointerHeight - mPointerOverlap : 0;
        final float paddingTop = showVertically ? 0
                : mPointerHeight - mPointerOverlap;
        setPadding((int) paddingLeft, (int) paddingTop, (int) paddingRight, 0);

        final float expandedViewY = mPositioner.getExpandedViewY();
        // TODO: I don't understand why it works but it does - why normalized in portrait
        //  & not in landscape? Am I missing ~2dp in the portrait expandedViewY calculation?
        final float normalizedSize = IconNormalizer.getNormalizedCircleSize(
                mPositioner.getBubbleSize());
        final float bubbleCenter = showVertically
                ? bubblePosition + (mPositioner.getBubbleSize() / 2f) - expandedViewY
                : bubblePosition + (normalizedSize / 2f) - mPointerWidth;
        // Post because we need the width of the view
        post(() -> {
            float pointerY;
            float pointerX;
            if (showVertically) {
                pointerY = bubbleCenter - (mPointerWidth / 2f);
                pointerX = onLeft
                        ? -mPointerHeight + mPointerOverlap
                        : getWidth() - mPaddingRight - mPointerOverlap;
            } else {
                pointerY = mPointerOverlap;
                pointerX = bubbleCenter - (mPointerWidth / 2f);
            }
            mPointerView.setTranslationY(pointerY);
            mPointerView.setTranslationX(pointerX);
            mCurrentPointer = showVertically ? onLeft ? mLeftPointer : mRightPointer : mTopPointer;
            updatePointerView();
            mPointerView.setVisibility(VISIBLE);
        });
    }

    /**
     * Position of the manage button displayed in the expanded view. Used for placing user
     * education about the manage button.
     */
    public void getManageButtonBoundsOnScreen(Rect rect) {
        mManageButton.getBoundsOnScreen(rect);
    }

    /**
     * Cleans up anything related to the task and TaskView. If this view should be reused after this
     * method is called, then {@link #initialize(BubbleController, BubbleStackView, boolean)} must
     * be invoked first.
     */
    public void cleanUpExpandedState() {
        if (DEBUG_BUBBLE_EXPANDED_VIEW) {
            Log.d(TAG, "cleanUpExpandedState: bubble=" + getBubbleKey() + " task=" + mTaskId);
        }
        if (getTaskId() != INVALID_TASK_ID) {
            try {
                ActivityTaskManager.getService().removeTask(getTaskId());
            } catch (RemoteException e) {
                Log.w(TAG, e.getMessage());
            }
        }
        if (mTaskView != null) {
            mTaskView.release();
            removeView(mTaskView);
            mTaskView = null;
        }
    }

    /**
     * Description of current expanded view state.
     */
    public void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("BubbleExpandedView");
        pw.print("  taskId:               "); pw.println(mTaskId);
        pw.print("  stackView:            "); pw.println(mStackView);
    }
}
