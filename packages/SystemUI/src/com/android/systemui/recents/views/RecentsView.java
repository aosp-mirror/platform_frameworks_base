/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.views;

import static android.app.ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;

import static com.android.systemui.statusbar.phone.StatusBar.SYSTEM_DIALOG_REASON_RECENT_APPS;

import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Log;
import android.util.MathUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewPropertyAnimator;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.Utils;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.CancelEnterRecentsWindowAnimationEvent;
import com.android.systemui.recents.events.activity.DismissRecentsToHomeAnimationStarted;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.EnterRecentsWindowAnimationCompletedEvent;
import com.android.systemui.recents.events.activity.ExitRecentsWindowFirstAnimationFrameEvent;
import com.android.systemui.recents.events.activity.HideStackActionButtonEvent;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.activity.LaunchTaskFailedEvent;
import com.android.systemui.recents.events.activity.LaunchTaskStartedEvent;
import com.android.systemui.recents.events.activity.LaunchTaskSucceededEvent;
import com.android.systemui.recents.events.activity.MultiWindowStateChangedEvent;
import com.android.systemui.recents.events.activity.ShowEmptyViewEvent;
import com.android.systemui.recents.events.activity.ShowStackActionButtonEvent;
import com.android.systemui.recents.events.component.ExpandPipEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.component.SetWaitingForTransitionStartEvent;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.events.ui.DismissAllTaskViewsEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEndedEvent;
import com.android.systemui.recents.events.ui.DraggingInRecentsEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragDropTargetChangedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndCancelledEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.recents.model.Task;
import com.android.systemui.shared.recents.model.TaskStack;
import com.android.systemui.shared.recents.utilities.Utilities;
import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecCompat;
import com.android.systemui.shared.recents.view.AppTransitionAnimationSpecsFuture;
import com.android.systemui.shared.recents.view.RecentsTransition;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.ActivityOptionsCompat;
import com.android.systemui.shared.system.WindowManagerWrapper;
import com.android.systemui.stackdivider.WindowManagerProxy;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.statusbar.phone.ScrimController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * This view is the the top level layout that contains TaskStacks (which are laid out according
 * to their SpaceNode bounds.
 */
public class RecentsView extends FrameLayout {

    private static final String TAG = "RecentsView";

    private static final int DEFAULT_UPDATE_SCRIM_DURATION = 200;

    private static final int SHOW_STACK_ACTION_BUTTON_DURATION = 134;
    private static final int HIDE_STACK_ACTION_BUTTON_DURATION = 100;

    private static final int BUSY_RECENTS_TASK_COUNT = 3;

    private Handler mHandler;
    private TaskStackView mTaskStackView;
    private TextView mStackActionButton;
    private TextView mEmptyView;
    private final float mStackButtonShadowRadius;
    private final PointF mStackButtonShadowDistance;
    private final int mStackButtonShadowColor;

    private boolean mAwaitingFirstLayout = true;

    @ViewDebug.ExportedProperty(category="recents")
    Rect mSystemInsets = new Rect();
    private int mDividerSize;

    private float mBusynessFactor;
    private GradientDrawable mBackgroundScrim;
    private ColorDrawable mMultiWindowBackgroundScrim;
    private ValueAnimator mBackgroundScrimAnimator;
    private Point mTmpDisplaySize = new Point();

    private final AnimatorUpdateListener mUpdateBackgroundScrimAlpha = (animation) -> {
        int alpha = (Integer) animation.getAnimatedValue();
        mBackgroundScrim.setAlpha(alpha);
        mMultiWindowBackgroundScrim.setAlpha(alpha);
    };

    private RecentsTransitionComposer mTransitionHelper;
    @ViewDebug.ExportedProperty(deepExport=true, prefix="touch_")
    private RecentsViewTouchHandler mTouchHandler;
    private final FlingAnimationUtils mFlingAnimationUtils;

    public RecentsView(Context context) {
        this(context, null);
    }

    public RecentsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RecentsView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setWillNotDraw(false);

        SystemServicesProxy ssp = Recents.getSystemServices();
        mHandler = new Handler();
        mTransitionHelper = new RecentsTransitionComposer(getContext());
        mDividerSize = ssp.getDockedDividerSize(context);
        mTouchHandler = new RecentsViewTouchHandler(this);
        mFlingAnimationUtils = new FlingAnimationUtils(context, 0.3f);
        mBackgroundScrim = new GradientDrawable(context);
        mMultiWindowBackgroundScrim = new ColorDrawable();

        LayoutInflater inflater = LayoutInflater.from(context);
        mEmptyView = (TextView) inflater.inflate(R.layout.recents_empty, this, false);
        addView(mEmptyView);

        if (mStackActionButton != null) {
            removeView(mStackActionButton);
        }
        mStackActionButton = (TextView) inflater.inflate(Recents.getConfiguration()
                        .isLowRamDevice
                    ? R.layout.recents_low_ram_stack_action_button
                    : R.layout.recents_stack_action_button,
                this, false);

        mStackButtonShadowRadius = mStackActionButton.getShadowRadius();
        mStackButtonShadowDistance = new PointF(mStackActionButton.getShadowDx(),
                mStackActionButton.getShadowDy());
        mStackButtonShadowColor = mStackActionButton.getShadowColor();
        addView(mStackActionButton);

        reevaluateStyles();
    }

    public void reevaluateStyles() {
        int textColor = Utils.getColorAttr(mContext, R.attr.wallpaperTextColor);
        boolean usingDarkText = Color.luminance(textColor) < 0.5f;

        mEmptyView.setTextColor(textColor);
        mEmptyView.setCompoundDrawableTintList(new ColorStateList(new int[][]{
                {android.R.attr.state_enabled}}, new int[]{textColor}));

        if (mStackActionButton != null) {
            mStackActionButton.setTextColor(textColor);
            // Enable/disable shadow if text color is already dark.
            if (usingDarkText) {
                mStackActionButton.setShadowLayer(0, 0, 0, 0);
            } else {
                mStackActionButton.setShadowLayer(mStackButtonShadowRadius,
                        mStackButtonShadowDistance.x, mStackButtonShadowDistance.y,
                        mStackButtonShadowColor);
            }
        }

        // Let's also require dark status and nav bars if the text is dark
        int systemBarsStyle = usingDarkText ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR |
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;

        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                systemBarsStyle);
    }

    /**
     * Called from RecentsActivity when it is relaunched.
     */
    public void onReload(TaskStack stack, boolean isResumingFromVisible) {
        final RecentsConfiguration config = Recents.getConfiguration();
        final RecentsActivityLaunchState launchState = config.getLaunchState();
        final boolean isTaskStackEmpty = stack.getTaskCount() == 0;

        if (mTaskStackView == null) {
            isResumingFromVisible = false;
            mTaskStackView = new TaskStackView(getContext());
            mTaskStackView.setSystemInsets(mSystemInsets);
            addView(mTaskStackView);
        }

        // Reset the state
        mAwaitingFirstLayout = !isResumingFromVisible;

        // Update the stack
        mTaskStackView.onReload(isResumingFromVisible);
        updateStack(stack, true /* setStackViewTasks */);
        updateBusyness();

        if (isResumingFromVisible) {
            // If we are already visible, then restore the background scrim
            animateBackgroundScrim(getOpaqueScrimAlpha(), DEFAULT_UPDATE_SCRIM_DURATION);
        } else {
            // If we are already occluded by the app, then set the final background scrim alpha now.
            // Otherwise, defer until the enter animation completes to animate the scrim alpha with
            // the tasks for the home animation.
            if (launchState.launchedViaDockGesture || launchState.launchedFromApp
                    || isTaskStackEmpty) {
                mBackgroundScrim.setAlpha((int) (getOpaqueScrimAlpha() * 255));
            } else {
                mBackgroundScrim.setAlpha(0);
            }
            mMultiWindowBackgroundScrim.setAlpha(mBackgroundScrim.getAlpha());
        }
    }

    /**
     * Called from RecentsActivity when the task stack is updated.
     */
    public void updateStack(TaskStack stack, boolean setStackViewTasks) {
        if (setStackViewTasks) {
            mTaskStackView.setTasks(stack, true /* allowNotifyStackChanges */);
        }

        // Update the top level view's visibilities
        if (stack.getTaskCount() > 0) {
            hideEmptyView();
        } else {
            showEmptyView(R.string.recents_empty_message);
        }
    }

    /**
     * Animates the scrim opacity based on how many tasks are visible.
     * Called from {@link RecentsActivity} when tasks are dismissed.
     */
    public void updateScrimOpacity() {
        if (updateBusyness()) {
            animateBackgroundScrim(getOpaqueScrimAlpha(), DEFAULT_UPDATE_SCRIM_DURATION);
        }
    }

    /**
     * Updates the busyness factor.
     *
     * @return True if it changed.
     */
    private boolean updateBusyness() {
        final int taskCount = mTaskStackView.getStack().getTaskCount();
        final float busyness = Math.min(taskCount, BUSY_RECENTS_TASK_COUNT)
                / (float) BUSY_RECENTS_TASK_COUNT;
        if (mBusynessFactor == busyness) {
            return false;
        } else {
            mBusynessFactor = busyness;
            return true;
        }
    }

    /**
     * Returns the current TaskStack.
     */
    public TaskStack getStack() {
        return mTaskStackView.getStack();
    }

    /**
     * Returns the window background scrim.
     */
    public void updateBackgroundScrim(Window window, boolean isInMultiWindow) {
        if (isInMultiWindow) {
            mBackgroundScrim.setCallback(null);
            window.setBackgroundDrawable(mMultiWindowBackgroundScrim);
        } else {
            mMultiWindowBackgroundScrim.setCallback(null);
            window.setBackgroundDrawable(mBackgroundScrim);
        }
    }

    /** Launches the focused task from the first stack if possible */
    public boolean launchFocusedTask(int logEvent) {
        if (mTaskStackView != null) {
            Task task = mTaskStackView.getFocusedTask();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null, false));

                if (logEvent != 0) {
                    MetricsLogger.action(getContext(), logEvent,
                            task.key.getComponent().toString());
                }
                return true;
            }
        }
        return false;
    }

    /** Launches the task that recents was launched from if possible */
    public boolean launchPreviousTask() {
        if (Recents.getConfiguration().getLaunchState().launchedFromPipApp) {
            // If the app auto-entered PiP on the way to Recents, then just re-expand it
            EventBus.getDefault().send(new ExpandPipEvent());
            return true;
        }

        if (mTaskStackView != null) {
            Task task = getStack().getLaunchTarget();
            if (task != null) {
                TaskView taskView = mTaskStackView.getChildViewForTask(task);
                EventBus.getDefault().send(new LaunchTaskEvent(taskView, task, null, false));
                return true;
            }
        }
        return false;
    }

    /**
     * Hides the task stack and shows the empty view.
     */
    public void showEmptyView(int msgResId) {
        mTaskStackView.setVisibility(View.INVISIBLE);
        mEmptyView.setText(msgResId);
        mEmptyView.setVisibility(View.VISIBLE);
        mEmptyView.bringToFront();
        mStackActionButton.bringToFront();
    }

    /**
     * Shows the task stack and hides the empty view.
     */
    public void hideEmptyView() {
        mEmptyView.setVisibility(View.INVISIBLE);
        mTaskStackView.setVisibility(View.VISIBLE);
        mTaskStackView.bringToFront();
        mStackActionButton.bringToFront();
    }

    /**
     * Set the color of the scrim.
     *
     * @param scrimColors Colors to use.
     * @param animated Interpolate colors if true.
     */
    public void setScrimColors(ColorExtractor.GradientColors scrimColors, boolean animated) {
        mBackgroundScrim.setColors(scrimColors, animated);
        int alpha = mMultiWindowBackgroundScrim.getAlpha();
        mMultiWindowBackgroundScrim.setColor(scrimColors.getMainColor());
        mMultiWindowBackgroundScrim.setAlpha(alpha);
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        EventBus.getDefault().register(mTouchHandler, RecentsActivity.EVENT_BUS_PRIORITY + 2);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
        EventBus.getDefault().unregister(mTouchHandler);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.measure(widthMeasureSpec, heightMeasureSpec);
        }

        // Measure the empty view to the full size of the screen
        if (mEmptyView.getVisibility() != GONE) {
            measureChild(mEmptyView, MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
        }

        // Measure the stack action button within the constraints of the space above the stack
        Rect buttonBounds = mTaskStackView.mLayoutAlgorithm.getStackActionButtonRect();
        measureChild(mStackActionButton,
                MeasureSpec.makeMeasureSpec(buttonBounds.width(), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(buttonBounds.height(), MeasureSpec.AT_MOST));

        setMeasuredDimension(width, height);
    }

    /**
     * This is called with the full size of the window since we are handling our own insets.
     */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mTaskStackView.getVisibility() != GONE) {
            mTaskStackView.layout(left, top, left + getMeasuredWidth(), top + getMeasuredHeight());
        }

        // Layout the empty view
        if (mEmptyView.getVisibility() != GONE) {
            int leftRightInsets = mSystemInsets.left + mSystemInsets.right;
            int topBottomInsets = mSystemInsets.top + mSystemInsets.bottom;
            int childWidth = mEmptyView.getMeasuredWidth();
            int childHeight = mEmptyView.getMeasuredHeight();
            int childLeft = left + mSystemInsets.left +
                    Math.max(0, (right - left - leftRightInsets - childWidth)) / 2;
            int childTop = top + mSystemInsets.top +
                    Math.max(0, (bottom - top - topBottomInsets - childHeight)) / 2;
            mEmptyView.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
        }

        // Needs to know the screen size since the gradient never scales up or down
        // even when bounds change.
        mContext.getDisplay().getRealSize(mTmpDisplaySize);
        mBackgroundScrim.setScreenSize(mTmpDisplaySize.x, mTmpDisplaySize.y);
        mBackgroundScrim.setBounds(left, top, right, bottom);
        mMultiWindowBackgroundScrim.setBounds(0, 0, mTmpDisplaySize.x, mTmpDisplaySize.y);

        // Layout the stack action button such that its drawable is start-aligned with the
        // stack, vertically centered in the available space above the stack
        Rect buttonBounds = getStackActionButtonBoundsFromStackLayout();
        mStackActionButton.layout(buttonBounds.left, buttonBounds.top, buttonBounds.right,
                buttonBounds.bottom);

        if (mAwaitingFirstLayout) {
            mAwaitingFirstLayout = false;
            // If launched via dragging from the nav bar, then we should translate the whole view
            // down offscreen
            RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
            if (launchState.launchedViaDragGesture) {
                setTranslationY(getMeasuredHeight());
            } else {
                setTranslationY(0f);
            }

            if (Recents.getConfiguration().isLowRamDevice
                    && mEmptyView.getVisibility() == View.VISIBLE) {
                animateEmptyView(true /* show */, null /* postAnimationTrigger */);
            }
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        mSystemInsets.set(insets.getSystemWindowInsets());
        mTaskStackView.setSystemInsets(mSystemInsets);
        requestLayout();
        return insets;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return mTouchHandler.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return mTouchHandler.onTouchEvent(ev);
    }

    @Override
    public void onDrawForeground(Canvas canvas) {
        super.onDrawForeground(canvas);

        ArrayList<DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            visDockStates.get(i).viewState.draw(canvas);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        ArrayList<DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            Drawable d = visDockStates.get(i).viewState.dockAreaOverlay;
            if (d == who) {
                return true;
            }
        }
        return super.verifyDrawable(who);
    }

    /**** EventBus Events ****/

    public final void onBusEvent(LaunchTaskEvent event) {
        launchTaskFromRecents(getStack(), event.task, mTaskStackView, event.taskView,
                event.screenPinningRequested, event.targetWindowingMode, event.targetActivityType);
        if (Recents.getConfiguration().isLowRamDevice) {
            EventBus.getDefault().send(new HideStackActionButtonEvent(false /* translate */));
        }
    }

    public final void onBusEvent(DismissRecentsToHomeAnimationStarted event) {
        int taskViewExitToHomeDuration = TaskStackAnimationHelper.EXIT_TO_HOME_TRANSLATION_DURATION;
        // Hide the stack action button
        EventBus.getDefault().send(new HideStackActionButtonEvent());
        animateBackgroundScrim(0f, taskViewExitToHomeDuration);

        if (Recents.getConfiguration().isLowRamDevice) {
            animateEmptyView(false /* show */, event.getAnimationTrigger());
        }
    }

    public final void onBusEvent(DragStartEvent event) {
        updateVisibleDockRegions(Recents.getConfiguration().getDockStatesForCurrentOrientation(),
                true /* isDefaultDockState */, DockState.NONE.viewState.dockAreaAlpha,
                DockState.NONE.viewState.hintTextAlpha,
                true /* animateAlpha */, false /* animateBounds */);

        // Temporarily hide the stack action button without changing visibility
        if (mStackActionButton != null) {
            mStackActionButton.animate()
                    .alpha(0f)
                    .setDuration(HIDE_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_OUT)
                    .start();
        }
    }

    public final void onBusEvent(DragDropTargetChangedEvent event) {
        if (event.dropTarget == null || !(event.dropTarget instanceof DockState)) {
            updateVisibleDockRegions(
                    Recents.getConfiguration().getDockStatesForCurrentOrientation(),
                    true /* isDefaultDockState */, DockState.NONE.viewState.dockAreaAlpha,
                    DockState.NONE.viewState.hintTextAlpha,
                    true /* animateAlpha */, true /* animateBounds */);
        } else {
            final DockState dockState = (DockState) event.dropTarget;
            updateVisibleDockRegions(new DockState[] {dockState},
                    false /* isDefaultDockState */, -1, -1, true /* animateAlpha */,
                    true /* animateBounds */);
        }
        if (mStackActionButton != null) {
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    // Move the clear all button to its new position
                    Rect buttonBounds = getStackActionButtonBoundsFromStackLayout();
                    mStackActionButton.setLeftTopRightBottom(buttonBounds.left, buttonBounds.top,
                            buttonBounds.right, buttonBounds.bottom);
                }
            });
        }
    }

    public final void onBusEvent(final DragEndEvent event) {
        // Handle the case where we drop onto a dock region
        if (event.dropTarget instanceof DockState) {
            final DockState dockState = (DockState) event.dropTarget;

            // Hide the dock region
            updateVisibleDockRegions(null, false /* isDefaultDockState */, -1, -1,
                    false /* animateAlpha */, false /* animateBounds */);

            // We translated the view but we need to animate it back from the current layout-space
            // rect to its final layout-space rect
            Utilities.setViewFrameFromTranslation(event.taskView);

            final ActivityOptions options = ActivityOptionsCompat.makeSplitScreenOptions(
                    dockState.createMode == SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT);
            if (ActivityManagerWrapper.getInstance().startActivityFromRecents(event.task.key.id,
                    options)) {
                final Runnable animStartedListener = () -> {
                    EventBus.getDefault().send(new DockedFirstAnimationFrameEvent());
                    // Remove the task and don't bother relaying out, as all the tasks
                    // will be relaid out when the stack changes on the multiwindow
                    // change event
                    getStack().removeTask(event.task, null, true /* fromDockGesture */);
                };
                final Rect taskRect = getTaskRect(event.taskView);
                AppTransitionAnimationSpecsFuture future = new AppTransitionAnimationSpecsFuture(
                        getHandler()) {
                    @Override
                    public List<AppTransitionAnimationSpecCompat> composeSpecs() {
                        return mTransitionHelper.composeDockAnimationSpec(event.taskView, taskRect);
                    }
                };
                WindowManagerWrapper.getInstance().overridePendingAppTransitionMultiThumbFuture(
                        future, animStartedListener, getHandler(), true /* scaleUp */);
                MetricsLogger.action(mContext, MetricsEvent.ACTION_WINDOW_DOCK_DRAG_DROP,
                        event.task.getTopComponent().flattenToShortString());
            } else {
                EventBus.getDefault().send(new DragEndCancelledEvent(getStack(), event.task,
                        event.taskView));
            }
        } else {
            // Animate the overlay alpha back to 0
            updateVisibleDockRegions(null, true /* isDefaultDockState */, -1, -1,
                    true /* animateAlpha */, false /* animateBounds */);
        }

        // Show the stack action button again without changing visibility
        if (mStackActionButton != null) {
            mStackActionButton.animate()
                    .alpha(1f)
                    .setDuration(SHOW_STACK_ACTION_BUTTON_DURATION)
                    .setInterpolator(Interpolators.ALPHA_IN)
                    .start();
        }
    }

    public final void onBusEvent(final DragEndCancelledEvent event) {
        // Animate the overlay alpha back to 0
        updateVisibleDockRegions(null, true /* isDefaultDockState */, -1, -1,
                true /* animateAlpha */, false /* animateBounds */);
    }

    private Rect getTaskRect(TaskView taskView) {
        int[] location = taskView.getLocationOnScreen();
        int viewX = location[0];
        int viewY = location[1];
        return new Rect(viewX, viewY,
                (int) (viewX + taskView.getWidth() * taskView.getScaleX()),
                (int) (viewY + taskView.getHeight() * taskView.getScaleY()));
    }

    public final void onBusEvent(DraggingInRecentsEvent event) {
        if (mTaskStackView.getTaskViews().size() > 0) {
            setTranslationY(event.distanceFromTop - mTaskStackView.getTaskViews().get(0).getY());
        }
    }

    public final void onBusEvent(DraggingInRecentsEndedEvent event) {
        ViewPropertyAnimator animator = animate();
        if (event.velocity > mFlingAnimationUtils.getMinVelocityPxPerSecond()) {
            animator.translationY(getHeight());
            animator.withEndAction(new Runnable() {
                @Override
                public void run() {
                    WindowManagerProxy.getInstance().maximizeDockedStack();
                }
            });
            mFlingAnimationUtils.apply(animator, getTranslationY(), getHeight(), event.velocity);
        } else {
            animator.translationY(0f);
            animator.setListener(null);
            mFlingAnimationUtils.apply(animator, getTranslationY(), 0, event.velocity);
        }
        animator.start();
    }

    public final void onBusEvent(EnterRecentsWindowAnimationCompletedEvent event) {
        RecentsActivityLaunchState launchState = Recents.getConfiguration().getLaunchState();
        if (!launchState.launchedViaDockGesture && !launchState.launchedFromApp
                && getStack().getTaskCount() > 0) {
            animateBackgroundScrim(getOpaqueScrimAlpha(),
                    TaskStackAnimationHelper.ENTER_FROM_HOME_TRANSLATION_DURATION);
        }
    }

    public final void onBusEvent(AllTaskViewsDismissedEvent event) {
        EventBus.getDefault().send(new HideStackActionButtonEvent());
    }

    public final void onBusEvent(DismissAllTaskViewsEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (!ssp.hasDockedTask()) {
            // Animate the background away only if we are dismissing Recents to home
            animateBackgroundScrim(0f, DEFAULT_UPDATE_SCRIM_DURATION);
        }
    }

    public final void onBusEvent(ShowStackActionButtonEvent event) {
        showStackActionButton(SHOW_STACK_ACTION_BUTTON_DURATION, event.translate);
    }

    public final void onBusEvent(HideStackActionButtonEvent event) {
        hideStackActionButton(HIDE_STACK_ACTION_BUTTON_DURATION, true /* translate */);
    }

    public final void onBusEvent(MultiWindowStateChangedEvent event) {
        updateStack(event.stack, false /* setStackViewTasks */);
    }

    public final void onBusEvent(ShowEmptyViewEvent event) {
        showEmptyView(R.string.recents_empty_message);
    }

    /**
     * Shows the stack action button.
     */
    private void showStackActionButton(final int duration, final boolean translate) {
        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        if (mStackActionButton.getVisibility() == View.INVISIBLE) {
            mStackActionButton.setVisibility(View.VISIBLE);
            mStackActionButton.setAlpha(0f);
            if (translate) {
                mStackActionButton.setTranslationY(mStackActionButton.getMeasuredHeight() *
                        (Recents.getConfiguration().isLowRamDevice ? 1 : -0.25f));
            } else {
                mStackActionButton.setTranslationY(0f);
            }
            postAnimationTrigger.addLastDecrementRunnable(new Runnable() {
                @Override
                public void run() {
                    if (translate) {
                        mStackActionButton.animate()
                            .translationY(0f);
                    }
                    mStackActionButton.animate()
                            .alpha(1f)
                            .setDuration(duration)
                            .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                            .start();
                }
            });
        }
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate) {
        final ReferenceCountedTrigger postAnimationTrigger = new ReferenceCountedTrigger();
        hideStackActionButton(duration, translate, postAnimationTrigger);
        postAnimationTrigger.flushLastDecrementRunnables();
    }

    /**
     * Hides the stack action button.
     */
    private void hideStackActionButton(int duration, boolean translate,
                                       final ReferenceCountedTrigger postAnimationTrigger) {
        if (mStackActionButton.getVisibility() == View.VISIBLE) {
            if (translate) {
                mStackActionButton.animate().translationY(mStackActionButton.getMeasuredHeight()
                        * (Recents.getConfiguration().isLowRamDevice ? 1 : -0.25f));
            }
            mStackActionButton.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mStackActionButton.setVisibility(View.INVISIBLE);
                            postAnimationTrigger.decrement();
                        }
                    })
                    .start();
            postAnimationTrigger.increment();
        }
    }

    /**
     * Animates a translation in the Y direction and fades in/out for empty view to show or hide it.
     * @param show whether to translate up and fade in the empty view to the center of the screen
     * @param postAnimationTrigger to keep track of the animation
     */
    private void animateEmptyView(boolean show, ReferenceCountedTrigger postAnimationTrigger) {
        float start = mTaskStackView.getStackAlgorithm().getTaskRect().height() / 4;
        mEmptyView.setTranslationY(show ? start : 0);
        mEmptyView.setAlpha(show ? 0f : 1f);
        ViewPropertyAnimator animator = mEmptyView.animate()
                .setDuration(150)
                .setInterpolator(Interpolators.FAST_OUT_SLOW_IN)
                .translationY(show ? 0 : start)
                .alpha(show ? 1f : 0f);

        if (postAnimationTrigger != null) {
            animator.setListener(postAnimationTrigger.decrementOnAnimationEnd());
            postAnimationTrigger.increment();
        }
        animator.start();
    }

    /**
     * Updates the dock region to match the specified dock state.
     */
    private void updateVisibleDockRegions(DockState[] newDockStates,
            boolean isDefaultDockState, int overrideAreaAlpha, int overrideHintAlpha,
            boolean animateAlpha, boolean animateBounds) {
        ArraySet<DockState> newDockStatesSet = Utilities.arrayToSet(newDockStates,
                new ArraySet<DockState>());
        ArrayList<DockState> visDockStates = mTouchHandler.getVisibleDockStates();
        for (int i = visDockStates.size() - 1; i >= 0; i--) {
            DockState dockState = visDockStates.get(i);
            DockState.ViewState viewState = dockState.viewState;
            if (newDockStates == null || !newDockStatesSet.contains(dockState)) {
                // This is no longer visible, so hide it
                viewState.startAnimation(null, 0, 0, TaskStackView.SLOW_SYNC_STACK_DURATION,
                        Interpolators.FAST_OUT_SLOW_IN, animateAlpha, animateBounds);
            } else {
                // This state is now visible, update the bounds and show it
                int areaAlpha = overrideAreaAlpha != -1
                        ? overrideAreaAlpha
                        : viewState.dockAreaAlpha;
                int hintAlpha = overrideHintAlpha != -1
                        ? overrideHintAlpha
                        : viewState.hintTextAlpha;
                Rect bounds = isDefaultDockState
                        ? dockState.getPreDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                                mSystemInsets)
                        : dockState.getDockedBounds(getMeasuredWidth(), getMeasuredHeight(),
                                mDividerSize, mSystemInsets, getResources());
                if (viewState.dockAreaOverlay.getCallback() != this) {
                    viewState.dockAreaOverlay.setCallback(this);
                    viewState.dockAreaOverlay.setBounds(bounds);
                }
                viewState.startAnimation(bounds, areaAlpha, hintAlpha,
                        TaskStackView.SLOW_SYNC_STACK_DURATION, Interpolators.FAST_OUT_SLOW_IN,
                        animateAlpha, animateBounds);
            }
        }
    }

    /**
     * Scrim alpha based on how busy recents is:
     * Scrim will be {@link ScrimController#GRADIENT_SCRIM_ALPHA} when the stack is empty,
     * and {@link ScrimController#GRADIENT_SCRIM_ALPHA_BUSY} when it's full.
     *
     * @return Alpha from 0 to 1.
     */
    private float getOpaqueScrimAlpha() {
        return MathUtils.map(0, 1, ScrimController.GRADIENT_SCRIM_ALPHA,
                ScrimController.GRADIENT_SCRIM_ALPHA_BUSY, mBusynessFactor);
    }

    /**
     * Animates the background scrim to the given {@param alpha}.
     */
    private void animateBackgroundScrim(float alpha, int duration) {
        Utilities.cancelAnimationWithoutCallbacks(mBackgroundScrimAnimator);
        // Calculate the absolute alpha to animate from
        final int fromAlpha = mBackgroundScrim.getAlpha();
        final int toAlpha = (int) (alpha * 255);
        mBackgroundScrimAnimator = ValueAnimator.ofInt(fromAlpha, toAlpha);
        mBackgroundScrimAnimator.setDuration(duration);
        mBackgroundScrimAnimator.setInterpolator(toAlpha > fromAlpha
                ? Interpolators.ALPHA_IN
                : Interpolators.ALPHA_OUT);
        mBackgroundScrimAnimator.addUpdateListener(mUpdateBackgroundScrimAlpha);
        mBackgroundScrimAnimator.start();
    }

    /**
     * @return the bounds of the stack action button.
     */
    Rect getStackActionButtonBoundsFromStackLayout() {
        Rect actionButtonRect = new Rect(
                mTaskStackView.mLayoutAlgorithm.getStackActionButtonRect());
        int left, top;
        if (Recents.getConfiguration().isLowRamDevice) {
            Rect windowRect = Recents.getSystemServices().getWindowRect();
            int spaceLeft = windowRect.width() - mSystemInsets.left - mSystemInsets.right;
            left = (spaceLeft - mStackActionButton.getMeasuredWidth()) / 2 + mSystemInsets.left;
            top = windowRect.height() - (mStackActionButton.getMeasuredHeight()
                    + mSystemInsets.bottom + mStackActionButton.getPaddingBottom() / 2);
        } else {
            left = isLayoutRtl()
                ? actionButtonRect.left - mStackActionButton.getPaddingLeft()
                : actionButtonRect.right + mStackActionButton.getPaddingRight()
                        - mStackActionButton.getMeasuredWidth();
            top = actionButtonRect.top +
                (actionButtonRect.height() - mStackActionButton.getMeasuredHeight()) / 2;
        }
        actionButtonRect.set(left, top, left + mStackActionButton.getMeasuredWidth(),
                top + mStackActionButton.getMeasuredHeight());
        return actionButtonRect;
    }

    View getStackActionButton() {
        return mStackActionButton;
    }

    /**
     * Launches the specified {@link Task}.
     */
    public void launchTaskFromRecents(final TaskStack stack, @Nullable final Task task,
            final TaskStackView stackView, final TaskView taskView,
            final boolean screenPinningRequested, final int windowingMode, final int activityType) {

        final Runnable animStartedListener;
        final AppTransitionAnimationSpecsFuture transitionFuture;
        if (taskView != null) {

            // Fetch window rect here already in order not to be blocked on lock contention in WM
            // when the future calls it.
            final Rect windowRect = Recents.getSystemServices().getWindowRect();
            transitionFuture = new AppTransitionAnimationSpecsFuture(stackView.getHandler()) {
                @Override
                public List<AppTransitionAnimationSpecCompat> composeSpecs() {
                    return mTransitionHelper.composeAnimationSpecs(task, stackView, windowingMode,
                            activityType, windowRect);
                }
            };
            animStartedListener = new Runnable() {
                private boolean mHandled;

                @Override
                public void run() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    // If we are launching into another task, cancel the previous task's
                    // window transition
                    EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(task));
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                    stackView.cancelAllTaskViewAnimations();

                    if (screenPinningRequested) {
                        // Request screen pinning after the animation runs
                        mHandler.postDelayed(() -> {
                            EventBus.getDefault().send(new ScreenPinningRequestEvent(mContext,
                                    task.key.id));
                        }, 350);
                    }

                    if (!Recents.getConfiguration().isLowRamDevice) {
                        // Reset the state where we are waiting for the transition to start
                        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(false));
                    }
                }
            };
        } else {
            // This is only the case if the task is not on screen (scrolled offscreen for example)
            transitionFuture = null;
            animStartedListener = new Runnable() {
                private boolean mHandled;

                @Override
                public void run() {
                    if (mHandled) {
                        return;
                    }
                    mHandled = true;

                    // If we are launching into another task, cancel the previous task's
                    // window transition
                    EventBus.getDefault().send(new CancelEnterRecentsWindowAnimationEvent(task));
                    EventBus.getDefault().send(new ExitRecentsWindowFirstAnimationFrameEvent());
                    stackView.cancelAllTaskViewAnimations();

                    if (!Recents.getConfiguration().isLowRamDevice) {
                        // Reset the state where we are waiting for the transition to start
                        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(false));
                    }
                }
            };
        }

        EventBus.getDefault().send(new SetWaitingForTransitionStartEvent(true));
        final ActivityOptions opts = RecentsTransition.createAspectScaleAnimation(mContext,
                mHandler, true /* scaleUp */, transitionFuture != null ? transitionFuture : null,
                animStartedListener);
        if (taskView == null) {
            // If there is no task view, then we do not need to worry about animating out occluding
            // task views, and we can launch immediately
            startTaskActivity(stack, task, taskView, opts, transitionFuture,
                    windowingMode, activityType);
        } else {
            LaunchTaskStartedEvent launchStartedEvent = new LaunchTaskStartedEvent(taskView,
                    screenPinningRequested);
            EventBus.getDefault().send(launchStartedEvent);
            startTaskActivity(stack, task, taskView, opts, transitionFuture, windowingMode,
                    activityType);
        }
        ActivityManagerWrapper.getInstance().closeSystemWindows(SYSTEM_DIALOG_REASON_RECENT_APPS);
    }

    /**
     * Starts the activity for the launch task.
     *
     * @param taskView this is the {@link TaskView} that we are launching from. This can be null if
     *                 we are toggling recents and the launch-to task is now offscreen.
     */
    private void startTaskActivity(TaskStack stack, Task task, @Nullable TaskView taskView,
            ActivityOptions opts, AppTransitionAnimationSpecsFuture transitionFuture,
            int windowingMode, int activityType) {
        ActivityManagerWrapper.getInstance().startActivityFromRecentsAsync(task.key, opts,
                windowingMode, activityType, succeeded -> {
            if (succeeded) {
                // Keep track of the index of the task launch
                int taskIndexFromFront = 0;
                int taskIndex = stack.indexOfTask(task);
                if (taskIndex > -1) {
                    taskIndexFromFront = stack.getTaskCount() - taskIndex - 1;
                }
                EventBus.getDefault().send(new LaunchTaskSucceededEvent(taskIndexFromFront));
            } else {
                Log.e(TAG, mContext.getString(R.string.recents_launch_error_message, task.title));

                // Dismiss the task if we fail to launch it
                if (taskView != null) {
                    taskView.dismissTask();
                }

                // Keep track of failed launches
                EventBus.getDefault().send(new LaunchTaskFailedEvent());
            }
        }, getHandler());
        if (transitionFuture != null) {
            mHandler.post(transitionFuture::composeSpecsSynchronous);
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
        mTouchHandler.cancelStackActionButtonClick();
    }

    public void dump(String prefix, PrintWriter writer) {
        String innerPrefix = prefix + "  ";
        String id = Integer.toHexString(System.identityHashCode(this));

        writer.print(prefix); writer.print(TAG);
        writer.print(" awaitingFirstLayout="); writer.print(mAwaitingFirstLayout ? "Y" : "N");
        writer.print(" insets="); writer.print(Utilities.dumpRect(mSystemInsets));
        writer.print(" [0x"); writer.print(id); writer.print("]");
        writer.println();

        if (getStack() != null) {
            getStack().dump(innerPrefix, writer);
        }
        if (mTaskStackView != null) {
            mTaskStackView.dump(innerPrefix, writer);
        }
    }
}
