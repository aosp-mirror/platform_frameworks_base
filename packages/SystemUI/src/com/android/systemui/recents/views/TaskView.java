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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.IntProperty;
import android.util.Property;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.LaunchTaskEvent;
import com.android.systemui.recents.events.ui.DismissTaskViewEvent;
import com.android.systemui.recents.events.ui.TaskViewDismissedEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragEndEvent;
import com.android.systemui.recents.events.ui.dragndrop.DragStartEvent;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.misc.Utilities;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import java.util.ArrayList;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

/* A task view */
public class TaskView extends FrameLayout implements Task.TaskCallbacks,
        TaskStackAnimationHelper.Callbacks, View.OnClickListener, View.OnLongClickListener {

    /** The TaskView callbacks */
    interface TaskViewCallbacks {
        void onTaskViewClipStateChanged(TaskView tv);
    }

    /**
     * The dim overlay is generally calculated from the task progress, but occasionally (like when
     * launching) needs to be animated independently of the task progress.
     */
    public static final Property<TaskView, Integer> DIM =
            new IntProperty<TaskView>("dim") {
                @Override
                public void setValue(TaskView tv, int dim) {
                    tv.setDim(dim);
                }

                @Override
                public Integer get(TaskView tv) {
                    return tv.getDim();
                }
            };

    public static final Property<TaskView, Float> TASK_PROGRESS =
            new FloatProperty<TaskView>("taskProgress") {
                @Override
                public void setValue(TaskView tv, float p) {
                    tv.setTaskProgress(p);
                }

                @Override
                public Float get(TaskView tv) {
                    return tv.getTaskProgress();
                }
            };

    float mTaskProgress;
    float mMaxDimScale;
    int mDimAlpha;
    AccelerateInterpolator mDimInterpolator = new AccelerateInterpolator(3f);
    PorterDuffColorFilter mDimColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_ATOP);
    Paint mDimLayerPaint = new Paint();
    float mActionButtonTranslationZ;

    Task mTask;
    boolean mTaskDataLoaded;
    boolean mClipViewInStack = true;
    AnimateableViewBounds mViewBounds;

    private AnimatorSet mTransformAnimation;
    private ArrayList<Animator> mTmpAnimators = new ArrayList<>();

    View mContent;
    TaskViewThumbnail mThumbnailView;
    TaskViewHeader mHeaderView;
    View mActionButtonView;
    TaskViewCallbacks mCb;

    Point mDownTouchPos = new Point();

    Interpolator mFastOutSlowInInterpolator;

    public TaskView(Context context) {
        this(context, null);
    }

    public TaskView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TaskView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        RecentsConfiguration config = Recents.getConfiguration();
        Resources res = context.getResources();
        mMaxDimScale = res.getInteger(R.integer.recents_max_task_stack_view_dim) / 255f;
        mViewBounds = new AnimateableViewBounds(this, res.getDimensionPixelSize(
                R.dimen.recents_task_view_rounded_corners_radius));
        mFastOutSlowInInterpolator = AnimationUtils.loadInterpolator(context,
                com.android.internal.R.interpolator.fast_out_slow_in);
        if (config.fakeShadows) {
            setBackground(new FakeShadowDrawable(res, config));
        }
        setOutlineProvider(mViewBounds);
        setOnLongClickListener(this);
    }

    /** Set callback */
    void setCallbacks(TaskViewCallbacks cb) {
        mCb = cb;
    }

    /** Resets this TaskView for reuse. */
    void reset() {
        resetViewProperties();
        resetNoUserInteractionState();
        setClipViewInStack(false);
        setCallbacks(null);
    }

    /** Gets the task */
    public Task getTask() {
        return mTask;
    }

    /** Returns the view bounds. */
    AnimateableViewBounds getViewBounds() {
        return mViewBounds;
    }

    @Override
    protected void onFinishInflate() {
        // Bind the views
        mContent = findViewById(R.id.task_view_content);
        mHeaderView = (TaskViewHeader) findViewById(R.id.task_view_bar);
        mThumbnailView = (TaskViewThumbnail) findViewById(R.id.task_view_thumbnail);
        mActionButtonView = findViewById(R.id.lock_to_app_fab);
        mActionButtonView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                // Set the outline to match the FAB background
                outline.setOval(0, 0, mActionButtonView.getWidth(), mActionButtonView.getHeight());
                outline.setAlpha(0.35f);
            }
        });
        mActionButtonView.setOnClickListener(this);
        mActionButtonTranslationZ = mActionButtonView.getTranslationZ();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mHeaderView.onTaskViewSizeChanged(w, h);
        mThumbnailView.onTaskViewSizeChanged(w, h);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mDownTouchPos.set((int) (ev.getX() * getScaleX()), (int) (ev.getY() * getScaleY()));
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        int widthWithoutPadding = width - mPaddingLeft - mPaddingRight;
        int heightWithoutPadding = height - mPaddingTop - mPaddingBottom;
        int taskBarHeight = getResources().getDimensionPixelSize(R.dimen.recents_task_bar_height);

        // Measure the content
        mContent.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.EXACTLY));

        // Measure the bar view, and action button
        mHeaderView.measure(MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(taskBarHeight, MeasureSpec.EXACTLY));
        mActionButtonView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.AT_MOST));
        // Measure the thumbnail to be square
        mThumbnailView.measure(
                MeasureSpec.makeMeasureSpec(widthWithoutPadding, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightWithoutPadding, MeasureSpec.EXACTLY));
        mThumbnailView.updateClipToTaskBar(mHeaderView);

        setMeasuredDimension(width, height);
        invalidateOutline();
    }

    void updateViewPropertiesToTaskTransform(TaskViewTransform toTransform,
            TaskViewAnimation toAnimation, ValueAnimator.AnimatorUpdateListener updateCallback) {
        RecentsConfiguration config = Recents.getConfiguration();
        Utilities.cancelAnimation(mTransformAnimation);

        // Compose the animations for the transform
        mTmpAnimators.clear();
        boolean requiresHwLayers = toTransform.applyToTaskView(this, mTmpAnimators, toAnimation,
                !config.fakeShadows);
        if (toAnimation.isImmediate()) {
            mThumbnailView.setBitmapScale(toTransform.thumbnailScale);
            setTaskProgress(toTransform.p);
            if (toAnimation.listener != null) {
                toAnimation.listener.onAnimationEnd(null);
            }
        } else {
            if (Float.compare(mThumbnailView.getBitmapScale(), toTransform.thumbnailScale) != 0) {
                mTmpAnimators.add(ObjectAnimator.ofFloat(mThumbnailView,
                        TaskViewThumbnail.BITMAP_SCALE, mThumbnailView.getBitmapScale(),
                        toTransform.thumbnailScale));
            }
            if (Float.compare(getTaskProgress(), toTransform.p) != 0) {
                mTmpAnimators.add(ObjectAnimator.ofFloat(this, TASK_PROGRESS, getTaskProgress(),
                        toTransform.p));
            }
            ValueAnimator updateCallbackAnim = ValueAnimator.ofInt(0, 1);
            updateCallbackAnim.addUpdateListener(updateCallback);
            mTmpAnimators.add(updateCallbackAnim);

            // Create the animator
            mTransformAnimation = toAnimation.createAnimator(mTmpAnimators);
            if (requiresHwLayers) {
                setLayerType(View.LAYER_TYPE_HARDWARE, null);
                mTransformAnimation.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        setLayerType(View.LAYER_TYPE_NONE, null);
                    }
                });
            }
            mTransformAnimation.start();
        }
    }

    /** Resets this view's properties */
    void resetViewProperties() {
        Utilities.cancelAnimation(mTransformAnimation);
        setDim(0);
        setVisibility(View.VISIBLE);
        getViewBounds().reset();
        TaskViewTransform.reset(this);

        mActionButtonView.setScaleX(1f);
        mActionButtonView.setScaleY(1f);
        mActionButtonView.setAlpha(1f);
        mActionButtonView.setTranslationZ(mActionButtonTranslationZ);
    }

    /**
     * Cancels any current transform animations.
     */
    public void cancelTransformAnimation() {
        Utilities.cancelAnimation(mTransformAnimation);
    }

    /** Enables/disables handling touch on this task view. */
    void setTouchEnabled(boolean enabled) {
        setOnClickListener(enabled ? this : null);
    }

    /** Animates this task view if the user does not interact with the stack after a certain time. */
    void startNoUserInteractionAnimation() {
        mHeaderView.startNoUserInteractionAnimation();
    }

    /** Mark this task view that the user does has not interacted with the stack after a certain time. */
    void setNoUserInteractionState() {
        mHeaderView.setNoUserInteractionState();
    }

    /** Resets the state tracking that the user has not interacted with the stack after a certain time. */
    void resetNoUserInteractionState() {
        mHeaderView.resetNoUserInteractionState();
    }

    /** Dismisses this task. */
    void dismissTask() {
        // Animate out the view and call the callback
        final TaskView tv = this;
        DismissTaskViewEvent dismissEvent = new DismissTaskViewEvent(tv, mTask);
        dismissEvent.addPostAnimationCallback(new Runnable() {
            @Override
            public void run() {
                EventBus.getDefault().send(new TaskViewDismissedEvent(mTask, tv));
            }
        });
        EventBus.getDefault().send(dismissEvent);
    }

    /**
     * Returns whether this view should be clipped, or any views below should clip against this
     * view.
     */
    boolean shouldClipViewInStack() {
        // Never clip for freeform tasks or if invisible
        if (mTask.isFreeformTask() || getVisibility() != View.VISIBLE) {
            return false;
        }
        return mClipViewInStack;
    }

    /** Sets whether this view should be clipped, or clipped against. */
    void setClipViewInStack(boolean clip) {
        if (clip != mClipViewInStack) {
            mClipViewInStack = clip;
            if (mCb != null) {
                mCb.onTaskViewClipStateChanged(this);
            }
        }
    }

    /** Sets the current task progress. */
    public void setTaskProgress(float p) {
        mTaskProgress = p;
        mViewBounds.setAlpha(p);
        updateDimFromTaskProgress();
    }

    /** Returns the current task progress. */
    public float getTaskProgress() {
        return mTaskProgress;
    }

    /** Returns the current dim. */
    public void setDim(int dim) {
        RecentsConfiguration config = Recents.getConfiguration();

        mDimAlpha = dim;
        if (config.useHardwareLayers) {
            // Defer setting hardware layers if we have not yet measured, or there is no dim to draw
            if (getMeasuredWidth() > 0 && getMeasuredHeight() > 0) {
                mDimColorFilter.setColor(Color.argb(mDimAlpha, 0, 0, 0));
                mDimLayerPaint.setColorFilter(mDimColorFilter);
                mContent.setLayerType(LAYER_TYPE_HARDWARE, mDimLayerPaint);
            }
        } else {
            float dimAlpha = mDimAlpha / 255.0f;
            mThumbnailView.setDimAlpha(dimAlpha);
            mHeaderView.setDimAlpha(dim);
        }
    }

    /** Returns the current dim. */
    public int getDim() {
        return mDimAlpha;
    }

    /** Animates the dim to the task progress. */
    void animateDimToProgress(int duration, Animator.AnimatorListener animListener) {
        // Animate the dim into view as well
        int toDim = getDimFromTaskProgress();
        if (toDim != getDim()) {
            ObjectAnimator anim = ObjectAnimator.ofInt(this, DIM, getDim(), toDim);
            anim.setDuration(duration);
            if (animListener != null) {
                anim.addListener(animListener);
            }
            anim.start();
        } else {
            animListener.onAnimationEnd(null);
        }
    }

    /** Compute the dim as a function of the scale of this view. */
    int getDimFromTaskProgress() {
        float x = mTaskProgress < 0
                ? 1f
                : mDimInterpolator.getInterpolation(1f - mTaskProgress);
        float dim = mMaxDimScale * x;
        return (int) (dim * 255);
    }

    /** Update the dim as a function of the scale of this view. */
    void updateDimFromTaskProgress() {
        setDim(getDimFromTaskProgress());
    }

    /**
     * Explicitly sets the focused state of this task.
     */
    public void setFocusedState(boolean isFocused, boolean requestViewFocus) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        if (isFocused) {
            if (requestViewFocus && !isFocused()) {
                requestFocus();
            }
            if (requestViewFocus && !isAccessibilityFocused() && ssp.isTouchExplorationEnabled()) {
                requestAccessibilityFocus();
            }
        } else {
            if (isAccessibilityFocused() && ssp.isTouchExplorationEnabled()) {
                clearAccessibilityFocus();
            }
        }
    }

    /**
     * Shows the action button.
     * @param fadeIn whether or not to animate the action button in.
     * @param fadeInDuration the duration of the action button animation, only used if
     *                       {@param fadeIn} is true.
     */
    public void showActionButton(boolean fadeIn, int fadeInDuration) {
        mActionButtonView.setVisibility(View.VISIBLE);

        if (fadeIn) {
            if (mActionButtonView.getAlpha() < 1f) {
                mActionButtonView.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(fadeInDuration)
                        .setInterpolator(PhoneStatusBar.ALPHA_IN)
                        .withLayer()
                        .start();
            }
        } else {
            mActionButtonView.setScaleX(1f);
            mActionButtonView.setScaleY(1f);
            mActionButtonView.setAlpha(1f);
            mActionButtonView.setTranslationZ(mActionButtonTranslationZ);
        }
    }

    /**
     * Immediately hides the action button.
     *
     * @param fadeOut whether or not to animate the action button out.
     */
    public void hideActionButton(boolean fadeOut, int fadeOutDuration, boolean scaleDown,
            final Animator.AnimatorListener animListener) {
        if (fadeOut) {
            if (mActionButtonView.getAlpha() > 0f) {
                if (scaleDown) {
                    float toScale = 0.9f;
                    mActionButtonView.animate()
                            .scaleX(toScale)
                            .scaleY(toScale);
                }
                mActionButtonView.animate()
                        .alpha(0f)
                        .setDuration(fadeOutDuration)
                        .setInterpolator(PhoneStatusBar.ALPHA_OUT)
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                if (animListener != null) {
                                    animListener.onAnimationEnd(null);
                                }
                                mActionButtonView.setVisibility(View.INVISIBLE);
                            }
                        })
                        .withLayer()
                        .start();
            }
        } else {
            mActionButtonView.setAlpha(0f);
            mActionButtonView.setVisibility(View.INVISIBLE);
            if (animListener != null) {
                animListener.onAnimationEnd(null);
            }
        }
    }

    /**** TaskStackAnimationHelper.Callbacks Implementation ****/

    @Override
    public void onPrepareLaunchTargetForEnterAnimation() {
        // These values will be animated in when onStartLaunchTargetEnterAnimation() is called
        setDim(0);
        mActionButtonView.setAlpha(0f);
    }

    @Override
    public void onStartLaunchTargetEnterAnimation(int duration, boolean screenPinningEnabled,
            ReferenceCountedTrigger postAnimationTrigger) {
        postAnimationTrigger.increment();
        animateDimToProgress(duration, postAnimationTrigger.decrementOnAnimationEnd());

        if (screenPinningEnabled) {
            showActionButton(true /* fadeIn */, duration /* fadeInDuration */);
        }
    }

    @Override
    public void onStartLaunchTargetLaunchAnimation(int duration, boolean screenPinningRequested,
            ReferenceCountedTrigger postAnimationTrigger) {
        if (mDimAlpha > 0) {
            ObjectAnimator anim = ObjectAnimator.ofInt(this, DIM, getDim(), 0);
            anim.setDuration(duration);
            anim.setInterpolator(PhoneStatusBar.ALPHA_OUT);
            anim.start();
        }

        postAnimationTrigger.increment();
        hideActionButton(true /* fadeOut */, duration,
                !screenPinningRequested /* scaleDown */,
                postAnimationTrigger.decrementOnAnimationEnd());
    }

    /**** TaskCallbacks Implementation ****/

    public void onTaskBound(Task t) {
        mTask = t;
        mTask.addCallback(this);
    }

    @Override
    public void onTaskDataLoaded(Task task) {
        // Bind each of the views to the new task data
        mThumbnailView.rebindToTask(mTask);
        mHeaderView.rebindToTask(mTask);
        mTaskDataLoaded = true;
    }

    @Override
    public void onTaskDataUnloaded() {
        // Unbind each of the views from the task data and remove the task callback
        mTask.removeCallback(this);
        mThumbnailView.unbindFromTask();
        mHeaderView.unbindFromTask();
        mTaskDataLoaded = false;
    }

    @Override
    public void onTaskStackIdChanged() {
        mHeaderView.rebindToTask(mTask);
    }

    /**** View.OnClickListener Implementation ****/

    @Override
     public void onClick(final View v) {
        boolean screenPinningRequested = false;
        if (v == mActionButtonView) {
            // Reset the translation of the action button before we animate it out
            mActionButtonView.setTranslationZ(0f);
            screenPinningRequested = true;
        }
        EventBus.getDefault().send(new LaunchTaskEvent(this, mTask, null, INVALID_STACK_ID,
                screenPinningRequested));
    }

    /**** View.OnLongClickListener Implementation ****/

    @Override
    public boolean onLongClick(View v) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        // Since we are clipping the view to the bounds, manually do the hit test
        Rect clipBounds = new Rect(mViewBounds.mClipBounds);
        clipBounds.scale(getScaleX());
        boolean inBounds = clipBounds.contains(mDownTouchPos.x, mDownTouchPos.y);
        if (v == this && inBounds && !ssp.hasDockedTask()) {
            // Start listening for drag events
            setClipViewInStack(false);

            mDownTouchPos.x += ((1f - getScaleX()) * getWidth()) / 2;
            mDownTouchPos.y += ((1f - getScaleY()) * getHeight()) / 2;

            EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
            EventBus.getDefault().send(new DragStartEvent(mTask, this, mDownTouchPos));
            return true;
        }
        return false;
    }

    /**** Events ****/

    public final void onBusEvent(DragEndEvent event) {
        if (!(event.dropTarget instanceof TaskStack.DockState)) {
            event.addPostAnimationCallback(new Runnable() {
                @Override
                public void run() {
                    // Animate the drag view back from where it is, to the view location, then after
                    // it returns, update the clip state
                    setClipViewInStack(true);
                }
            });
        }
        EventBus.getDefault().unregister(this);
    }
}
