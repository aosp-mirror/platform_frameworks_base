/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.content.pm.ActivityInfo.CONFIG_DENSITY;
import static android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_DECOR_SURFACE_BOOSTED;

import static androidx.window.extensions.embedding.DividerAttributes.RATIO_SYSTEM_DEFAULT;
import static androidx.window.extensions.embedding.DividerAttributes.WIDTH_SYSTEM_DEFAULT;
import static androidx.window.extensions.embedding.SplitAttributesHelper.isReversedLayout;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.window.InputTransferToken;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.window.extensions.core.util.function.Consumer;
import androidx.window.extensions.embedding.SplitAttributes.SplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.ExpandContainersSplitType;
import androidx.window.extensions.embedding.SplitAttributes.SplitType.RatioSplitType;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages the rendering and interaction of the divider.
 */
class DividerPresenter implements View.OnTouchListener {
    static final float RATIO_EXPANDED_PRIMARY = 1.0f;
    static final float RATIO_EXPANDED_SECONDARY = 0.0f;
    private static final String WINDOW_NAME = "AE Divider";
    private static final int VEIL_LAYER = 0;
    private static final int DIVIDER_LAYER = 1;

    // TODO(b/327067596) Update based on UX guidance.
    private static final Color DEFAULT_PRIMARY_VEIL_COLOR = Color.valueOf(Color.BLACK);
    private static final Color DEFAULT_SECONDARY_VEIL_COLOR = Color.valueOf(Color.GRAY);
    @VisibleForTesting
    static final float DEFAULT_MIN_RATIO = 0.35f;
    @VisibleForTesting
    static final float DEFAULT_MAX_RATIO = 0.65f;
    @VisibleForTesting
    static final int DEFAULT_DIVIDER_WIDTH_DP = 24;

    @VisibleForTesting
    static final PathInterpolator FLING_ANIMATION_INTERPOLATOR =
            new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    @VisibleForTesting
    static final int FLING_ANIMATION_DURATION = 250;
    @VisibleForTesting
    static final int MIN_DISMISS_VELOCITY_DP_PER_SECOND = 600;
    @VisibleForTesting
    static final int MIN_FLING_VELOCITY_DP_PER_SECOND = 400;

    private final int mTaskId;

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final DragEventCallback mDragEventCallback;

    @NonNull
    private final Executor mCallbackExecutor;

    /**
     * The VelocityTracker of the divider, used to track the dragging velocity. This field is
     * {@code null} until dragging starts.
     */
    @GuardedBy("mLock")
    @Nullable
    VelocityTracker mVelocityTracker;

    /**
     * The {@link Properties} of the divider. This field is {@code null} when no divider should be
     * drawn, e.g. when the split doesn't have {@link DividerAttributes} or when the decor surface
     * is not available.
     */
    @GuardedBy("mLock")
    @Nullable
    @VisibleForTesting
    Properties mProperties;

    /**
     * The {@link Renderer} of the divider. This field is {@code null} when no divider should be
     * drawn, i.e. when {@link #mProperties} is {@code null}. The {@link Renderer} is recreated or
     * updated when {@link #mProperties} is changed.
     */
    @GuardedBy("mLock")
    @Nullable
    @VisibleForTesting
    Renderer mRenderer;

    /**
     * The owner TaskFragment token of the decor surface. The decor surface is placed right above
     * the owner TaskFragment surface and is removed if the owner TaskFragment is destroyed.
     */
    @GuardedBy("mLock")
    @Nullable
    @VisibleForTesting
    IBinder mDecorSurfaceOwner;

    /**
     * The current divider position relative to the Task bounds. For vertical split (left-to-right
     * or right-to-left), it is the x coordinate in the task window, and for horizontal split
     * (top-to-bottom or bottom-to-top), it is the y coordinate in the task window.
     */
    @GuardedBy("mLock")
    private int mDividerPosition;

    DividerPresenter(int taskId, @NonNull DragEventCallback dragEventCallback,
            @NonNull Executor callbackExecutor) {
        mTaskId = taskId;
        mDragEventCallback = dragEventCallback;
        mCallbackExecutor = callbackExecutor;
    }

    /** Updates the divider when external conditions are changed. */
    void updateDivider(
            @NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentParentInfo parentInfo,
            @Nullable SplitContainer topSplitContainer) {
        if (!Flags.activityEmbeddingInteractiveDividerFlag()) {
            return;
        }

        synchronized (mLock) {
            // Clean up the decor surface if top SplitContainer is null.
            if (topSplitContainer == null) {
                removeDecorSurfaceAndDivider(wct);
                return;
            }

            final SplitAttributes splitAttributes = topSplitContainer.getCurrentSplitAttributes();
            final DividerAttributes dividerAttributes = splitAttributes.getDividerAttributes();

            // Clean up the decor surface if DividerAttributes is null.
            if (dividerAttributes == null) {
                removeDecorSurfaceAndDivider(wct);
                return;
            }

            // At this point, a divider is required.
            final TaskFragmentContainer primaryContainer =
                    topSplitContainer.getPrimaryContainer();
            final TaskFragmentContainer secondaryContainer =
                    topSplitContainer.getSecondaryContainer();

            // Create the decor surface if one is not available yet.
            final SurfaceControl decorSurface = parentInfo.getDecorSurface();
            if (decorSurface == null) {
                // Clean up when the decor surface is currently unavailable.
                removeDivider();
                // Request to create the decor surface
                createOrMoveDecorSurfaceLocked(wct, primaryContainer);
                return;
            }

            // Update the decor surface owner if needed.
            boolean isDraggableExpandType =
                    SplitAttributesHelper.isDraggableExpandType(splitAttributes);
            final TaskFragmentContainer decorSurfaceOwnerContainer =
                    isDraggableExpandType ? secondaryContainer : primaryContainer;

            if (!Objects.equals(
                    mDecorSurfaceOwner, decorSurfaceOwnerContainer.getTaskFragmentToken())) {
                createOrMoveDecorSurfaceLocked(wct, decorSurfaceOwnerContainer);
            }

            final Configuration parentConfiguration = parentInfo.getConfiguration();
            final Rect taskBounds = parentConfiguration.windowConfiguration.getBounds();
            final boolean isVerticalSplit = isVerticalSplit(splitAttributes);
            final boolean isReversedLayout = isReversedLayout(splitAttributes, parentConfiguration);
            final int dividerWidthPx = getDividerWidthPx(dividerAttributes);

            updateProperties(
                    new Properties(
                            parentConfiguration,
                            dividerAttributes,
                            decorSurface,
                            getInitialDividerPosition(
                                    primaryContainer, secondaryContainer, taskBounds,
                                    dividerWidthPx, isDraggableExpandType, isVerticalSplit,
                                    isReversedLayout),
                            isVerticalSplit,
                            isReversedLayout,
                            parentInfo.getDisplayId(),
                            isDraggableExpandType,
                            primaryContainer,
                            secondaryContainer)
            );
        }
    }

    @GuardedBy("mLock")
    private void updateProperties(@NonNull Properties properties) {
        if (Properties.equalsForDivider(mProperties, properties)) {
            return;
        }
        final Properties previousProperties = mProperties;
        mProperties = properties;

        if (mRenderer == null) {
            // Create a new renderer when a renderer doesn't exist yet.
            mRenderer = new Renderer(mProperties, this);
        } else if (!Properties.areSameSurfaces(
                previousProperties.mDecorSurface, mProperties.mDecorSurface)
                || previousProperties.mDisplayId != mProperties.mDisplayId) {
            // Release and recreate the renderer if the decor surface or the display has changed.
            mRenderer.release();
            mRenderer = new Renderer(mProperties, this);
        } else {
            // Otherwise, update the renderer for the new properties.
            mRenderer.update(mProperties);
        }
    }

    /**
     * Returns the window background color of the top activity in the container if set, or the
     * default color if the background color of the top activity is unavailable.
     */
    @VisibleForTesting
    @NonNull
    static Color getContainerBackgroundColor(
            @NonNull TaskFragmentContainer container, @NonNull Color defaultColor) {
        final Activity activity = container.getTopNonFinishingActivity();
        if (activity == null) {
            // This can happen when the activities in the container are from a different process.
            // TODO(b/340984203) Report whether the top activity is in the same process. Use default
            // color if not.
            return defaultColor;
        }

        final Drawable drawable = activity.getWindow().getDecorView().getBackground();
        if (drawable instanceof ColorDrawable colorDrawable) {
            return Color.valueOf(colorDrawable.getColor());
        }
        return defaultColor;
    }

    /**
     * Creates a decor surface for the TaskFragment if no decor surface exists, or changes the owner
     * of the existing decor surface to be the specified TaskFragment.
     *
     * See {@link TaskFragmentOperation#OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE}.
     */
    void createOrMoveDecorSurface(
            @NonNull WindowContainerTransaction wct, @NonNull TaskFragmentContainer container) {
        synchronized (mLock) {
            createOrMoveDecorSurfaceLocked(wct, container);
        }
    }

    @GuardedBy("mLock")
    private void createOrMoveDecorSurfaceLocked(
            @NonNull WindowContainerTransaction wct, @NonNull TaskFragmentContainer container) {
        mDecorSurfaceOwner = container.getTaskFragmentToken();
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();
        wct.addTaskFragmentOperation(mDecorSurfaceOwner, operation);
    }

    @GuardedBy("mLock")
    private void removeDecorSurfaceAndDivider(@NonNull WindowContainerTransaction wct) {
        if (mDecorSurfaceOwner != null) {
            final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                    OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE)
                    .build();
            wct.addTaskFragmentOperation(mDecorSurfaceOwner, operation);
            mDecorSurfaceOwner = null;
        }
        removeDivider();
    }

    @GuardedBy("mLock")
    private void removeDivider() {
        if (mRenderer != null) {
            mRenderer.release();
        }
        mProperties = null;
        mRenderer = null;
    }

    @VisibleForTesting
    static int getInitialDividerPosition(
            @NonNull TaskFragmentContainer primaryContainer,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull Rect taskBounds,
            int dividerWidthPx,
            boolean isDraggableExpandType,
            boolean isVerticalSplit,
            boolean isReversedLayout) {
        if (isDraggableExpandType) {
            // If the secondary container is fully expanded by dragging the divider, we display the
            // divider on the edge.
            final int fullyExpandedPosition = isVerticalSplit
                    ? taskBounds.width() - dividerWidthPx
                    : taskBounds.height() - dividerWidthPx;
            return isReversedLayout ? fullyExpandedPosition : 0;
        } else {
            final Rect primaryBounds = primaryContainer.getLastRequestedBounds();
            final Rect secondaryBounds = secondaryContainer.getLastRequestedBounds();
            return isVerticalSplit
                    ? Math.min(primaryBounds.right, secondaryBounds.right)
                    : Math.min(primaryBounds.bottom, secondaryBounds.bottom);
        }
    }

    private static boolean isVerticalSplit(@NonNull SplitAttributes splitAttributes) {
        final int layoutDirection = splitAttributes.getLayoutDirection();
        switch (layoutDirection) {
            case SplitAttributes.LayoutDirection.LEFT_TO_RIGHT:
            case SplitAttributes.LayoutDirection.RIGHT_TO_LEFT:
            case SplitAttributes.LayoutDirection.LOCALE:
                return true;
            case SplitAttributes.LayoutDirection.TOP_TO_BOTTOM:
            case SplitAttributes.LayoutDirection.BOTTOM_TO_TOP:
                return false;
            default:
                throw new IllegalArgumentException("Invalid layout direction:" + layoutDirection);
        }
    }

    private static int getDividerWidthPx(@NonNull DividerAttributes dividerAttributes) {
        int dividerWidthDp = dividerAttributes.getWidthDp();
        return convertDpToPixel(dividerWidthDp);
    }

    private static int convertDpToPixel(int dp) {
        // TODO(b/329193115) support divider on secondary display
        final Context applicationContext = ActivityThread.currentActivityThread().getApplication();

        return (int) TypedValue.applyDimension(
                COMPLEX_UNIT_DIP,
                dp,
                applicationContext.getResources().getDisplayMetrics());
    }

    private static float getDisplayDensity() {
        // TODO(b/329193115) support divider on secondary display
        final Context applicationContext =
                ActivityThread.currentActivityThread().getApplication();
        return applicationContext.getResources().getDisplayMetrics().density;
    }

    /**
     * Returns the container bound offset that is a result of the presence of a divider.
     *
     * The offset is the relative position change for the container edge that is next to the divider
     * due to the presence of the divider. The value could be negative or positive depending on the
     * container position. Positive values indicate that the edge is shifting towards the right
     * (or bottom) and negative values indicate that the edge is shifting towards the left (or top).
     *
     * @param splitAttributes the {@link SplitAttributes} of the split container that we want to
     *                        compute bounds offset.
     * @param position        the position of the container in the split that we want to compute
     *                        bounds offset for.
     * @return the bounds offset in pixels.
     */
    static int getBoundsOffsetForDivider(
            @NonNull SplitAttributes splitAttributes,
            @SplitPresenter.ContainerPosition int position) {
        if (!Flags.activityEmbeddingInteractiveDividerFlag()) {
            return 0;
        }
        final DividerAttributes dividerAttributes = splitAttributes.getDividerAttributes();
        if (dividerAttributes == null) {
            return 0;
        }
        final int dividerWidthPx = getDividerWidthPx(dividerAttributes);
        return getBoundsOffsetForDivider(
                dividerWidthPx,
                splitAttributes.getSplitType(),
                position);
    }

    @VisibleForTesting
    static int getBoundsOffsetForDivider(
            int dividerWidthPx,
            @NonNull SplitType splitType,
            @SplitPresenter.ContainerPosition int position) {
        if (splitType instanceof ExpandContainersSplitType) {
            // No divider offset is needed for the ExpandContainersSplitType.
            return 0;
        }
        int primaryOffset;
        if (splitType instanceof final RatioSplitType splitRatio) {
            // When a divider is present, both containers shrink by an amount proportional to their
            // split ratio and sum to the width of the divider, so that the ending sizing of the
            // containers still maintain the same ratio.
            primaryOffset = (int) (dividerWidthPx * splitRatio.getRatio());
        } else {
            // Hinge split type (and other future split types) will have the divider width equally
            // distributed to both containers.
            primaryOffset = dividerWidthPx / 2;
        }
        final int secondaryOffset = dividerWidthPx - primaryOffset;
        switch (position) {
            case CONTAINER_POSITION_LEFT:
            case CONTAINER_POSITION_TOP:
                return -primaryOffset;
            case CONTAINER_POSITION_RIGHT:
            case CONTAINER_POSITION_BOTTOM:
                return secondaryOffset;
            default:
                throw new IllegalArgumentException("Unknown position:" + position);
        }
    }

    /**
     * Sanitizes and sets default values in the {@link DividerAttributes}.
     *
     * Unset values will be set with system default values. See
     * {@link DividerAttributes#WIDTH_SYSTEM_DEFAULT} and
     * {@link DividerAttributes#RATIO_SYSTEM_DEFAULT}.
     *
     * @param dividerAttributes input {@link DividerAttributes}
     * @return a {@link DividerAttributes} that has all values properly set.
     */
    @Nullable
    static DividerAttributes sanitizeDividerAttributes(
            @Nullable DividerAttributes dividerAttributes) {
        if (dividerAttributes == null) {
            return null;
        }
        int widthDp = dividerAttributes.getWidthDp();
        float minRatio = dividerAttributes.getPrimaryMinRatio();
        float maxRatio = dividerAttributes.getPrimaryMaxRatio();

        if (widthDp == WIDTH_SYSTEM_DEFAULT) {
            widthDp = DEFAULT_DIVIDER_WIDTH_DP;
        }

        if (dividerAttributes.getDividerType() == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
            // Update minRatio and maxRatio only when it is a draggable divider.
            if (minRatio == RATIO_SYSTEM_DEFAULT) {
                minRatio = DEFAULT_MIN_RATIO;
            }
            if (maxRatio == RATIO_SYSTEM_DEFAULT) {
                maxRatio = DEFAULT_MAX_RATIO;
            }
        }

        return new DividerAttributes.Builder(dividerAttributes)
                .setWidthDp(widthDp)
                .setPrimaryMinRatio(minRatio)
                .setPrimaryMaxRatio(maxRatio)
                .build();
    }

    @Override
    public boolean onTouch(@NonNull View view, @NonNull MotionEvent event) {
        synchronized (mLock) {
            if (mProperties != null && mRenderer != null) {
                final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
                mDividerPosition = calculateDividerPosition(
                        event, taskBounds, mProperties.mDividerWidthPx,
                        mProperties.mDividerAttributes, mProperties.mIsVerticalSplit,
                        calculateMinPosition(), calculateMaxPosition());
                mRenderer.setDividerPosition(mDividerPosition);

                // Convert to use screen-based coordinates to prevent lost track of motion events
                // while moving divider bar and calculating dragging velocity.
                event.setLocation(event.getRawX(), event.getRawY());
                final int action = event.getAction() & MotionEvent.ACTION_MASK;
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        onStartDragging(event);
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        onFinishDragging(event);
                        break;
                    case MotionEvent.ACTION_MOVE:
                        onDrag(event);
                        break;
                    default:
                        break;
                }
            }
        }

        // Returns true to prevent the default button click callback. The button pressed state is
        // set/unset when starting/finishing dragging.
        return true;
    }

    @GuardedBy("mLock")
    private void onStartDragging(@NonNull MotionEvent event) {
        mVelocityTracker = VelocityTracker.obtain();
        mVelocityTracker.addMovement(event);

        mRenderer.mIsDragging = true;
        mRenderer.mDragHandle.setPressed(mRenderer.mIsDragging);
        mRenderer.updateSurface();

        // Veil visibility change should be applied together with the surface boost transaction in
        // the wct.
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mRenderer.showVeils(t);

        // Callbacks must be executed on the executor to release mLock and prevent deadlocks.
        mCallbackExecutor.execute(() -> {
            mDragEventCallback.onStartDragging(
                    wct -> {
                        synchronized (mLock) {
                            setDecorSurfaceBoosted(wct, mDecorSurfaceOwner, true /* boosted */, t);
                        }
                    });
        });
    }

    @GuardedBy("mLock")
    private void onDrag(@NonNull MotionEvent event) {
        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(event);
        }
        mRenderer.updateSurface();
    }

    @GuardedBy("mLock")
    private void onFinishDragging(@NonNull MotionEvent event) {
        float velocity = 0.0f;
        if (mVelocityTracker != null) {
            mVelocityTracker.addMovement(event);
            mVelocityTracker.computeCurrentVelocity(1000 /* units */);
            velocity = mProperties.mIsVerticalSplit
                    ? mVelocityTracker.getXVelocity()
                    : mVelocityTracker.getYVelocity();
            mVelocityTracker.recycle();
        }

        final int prevDividerPosition = mDividerPosition;
        mDividerPosition = dividerPositionForSnapPoints(mDividerPosition, velocity);
        if (mDividerPosition != prevDividerPosition) {
            ValueAnimator animator = getFlingAnimator(prevDividerPosition, mDividerPosition);
            animator.start();
        } else {
            onDraggingEnd();
        }
    }

    @GuardedBy("mLock")
    @NonNull
    @VisibleForTesting
    ValueAnimator getFlingAnimator(int prevDividerPosition, int snappedDividerPosition) {
        final ValueAnimator animator =
                getValueAnimator(prevDividerPosition, snappedDividerPosition);
        animator.addUpdateListener(animation -> {
            synchronized (mLock) {
                updateDividerPosition((int) animation.getAnimatedValue());
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                synchronized (mLock) {
                    onDraggingEnd();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                synchronized (mLock) {
                    onDraggingEnd();
                }
            }
        });
        return animator;
    }

    @VisibleForTesting
    static ValueAnimator getValueAnimator(int prevDividerPosition, int snappedDividerPosition) {
        ValueAnimator animator = ValueAnimator
                .ofInt(prevDividerPosition, snappedDividerPosition)
                .setDuration(FLING_ANIMATION_DURATION);
        animator.setInterpolator(FLING_ANIMATION_INTERPOLATOR);
        return animator;
    }

    @GuardedBy("mLock")
    private void updateDividerPosition(int position) {
        mRenderer.setDividerPosition(position);
        mRenderer.updateSurface();
    }

    @GuardedBy("mLock")
    private void onDraggingEnd() {
        // Veil visibility change should be applied together with the surface boost transaction in
        // the wct.
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mRenderer.hideVeils(t);

        // Callbacks must be executed on the executor to release mLock and prevent deadlocks.
        // mDecorSurfaceOwner may change between here and when the callback is executed,
        // e.g. when the decor surface owner becomes the secondary container when it is expanded to
        // fullscreen.
        mCallbackExecutor.execute(() -> {
            mDragEventCallback.onFinishDragging(
                    mTaskId,
                    wct -> {
                        synchronized (mLock) {
                            setDecorSurfaceBoosted(wct, mDecorSurfaceOwner, false /* boosted */, t);
                        }
                    });
        });
        mRenderer.mIsDragging = false;
        mRenderer.mDragHandle.setPressed(mRenderer.mIsDragging);
    }

    /**
     * Returns the divider position adjusted for the min max ratio and fullscreen expansion.
     * The adjusted divider position is in the range of [minPosition, maxPosition] for a split, 0
     * for expanded right (bottom) container, or task width (height) minus the divider width for
     * expanded left (top) container.
     */
    @GuardedBy("mLock")
    private int dividerPositionForSnapPoints(int dividerPosition, float velocity) {
        final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
        final int minPosition = calculateMinPosition();
        final int maxPosition = calculateMaxPosition();
        final int fullyExpandedPosition = mProperties.mIsVerticalSplit
                ? taskBounds.width() - mProperties.mDividerWidthPx
                : taskBounds.height() - mProperties.mDividerWidthPx;

        final float displayDensity = getDisplayDensity();
        final boolean isDraggingToFullscreenAllowed =
                isDraggingToFullscreenAllowed(mProperties.mDividerAttributes);
        return dividerPositionWithPositionOptions(
                dividerPosition,
                minPosition,
                maxPosition,
                fullyExpandedPosition,
                velocity,
                displayDensity,
                isDraggingToFullscreenAllowed);
    }

    /**
     * Returns the divider position given a set of position options. A snap algorithm can adjust
     * the ending position to either fully expand one container or move the divider back to
     * the specified min/max ratio depending on the dragging velocity and if dragging to fullscreen
     * is allowed.
     */
    @VisibleForTesting
    static int dividerPositionWithPositionOptions(int dividerPosition, int minPosition,
            int maxPosition, int fullyExpandedPosition, float velocity, float displayDensity,
            boolean isDraggingToFullscreenAllowed) {
        if (isDraggingToFullscreenAllowed) {
            final float minDismissVelocityPxPerSecond =
                    MIN_DISMISS_VELOCITY_DP_PER_SECOND * displayDensity;
            if (dividerPosition < minPosition && velocity < -minDismissVelocityPxPerSecond) {
                return 0;
            }
            if (dividerPosition > maxPosition && velocity > minDismissVelocityPxPerSecond) {
                return fullyExpandedPosition;
            }
        }
        final float minFlingVelocityPxPerSecond =
                MIN_FLING_VELOCITY_DP_PER_SECOND * displayDensity;
        if (Math.abs(velocity) >= minFlingVelocityPxPerSecond) {
            return dividerPositionForFling(
                    dividerPosition, minPosition, maxPosition, velocity);
        }
        if (dividerPosition >= minPosition && dividerPosition <= maxPosition) {
            return dividerPosition;
        }
        return snap(
                dividerPosition,
                isDraggingToFullscreenAllowed
                        ? new int[] {0, minPosition, maxPosition, fullyExpandedPosition}
                        : new int[] {minPosition, maxPosition});
    }

    /**
     * Returns the closest position that is in the fling direction.
     */
    private static int dividerPositionForFling(int dividerPosition, int minPosition,
            int maxPosition, float velocity) {
        final boolean isBackwardDirection = velocity < 0;
        if (isBackwardDirection) {
            return dividerPosition < maxPosition ? minPosition : maxPosition;
        } else {
            return dividerPosition > minPosition ? maxPosition : minPosition;
        }
    }

    /**
     * Returns the snapped position from a list of possible positions. Currently, this method
     * snaps to the closest position by distance from the divider position.
     */
    private static int snap(int dividerPosition, int[] possiblePositions) {
        int snappedPosition = dividerPosition;
        float minDistance = Float.MAX_VALUE;
        for (int position : possiblePositions) {
            float distance = Math.abs(dividerPosition - position);
            if (distance < minDistance) {
                snappedPosition = position;
                minDistance = distance;
            }
        }
        return snappedPosition;
    }

    private static void setDecorSurfaceBoosted(
            @NonNull WindowContainerTransaction wct,
            @Nullable IBinder decorSurfaceOwner,
            boolean boosted,
            @NonNull SurfaceControl.Transaction clientTransaction) {
        if (decorSurfaceOwner == null) {
            return;
        }
        wct.addTaskFragmentOperation(
                decorSurfaceOwner,
                new TaskFragmentOperation.Builder(OP_TYPE_SET_DECOR_SURFACE_BOOSTED)
                        .setBooleanValue(boosted)
                        .setSurfaceTransaction(clientTransaction)
                        .build()
        );
    }

    /** Calculates the new divider position based on the touch event and divider attributes. */
    @VisibleForTesting
    static int calculateDividerPosition(@NonNull MotionEvent event, @NonNull Rect taskBounds,
            int dividerWidthPx, @NonNull DividerAttributes dividerAttributes,
            boolean isVerticalSplit, int minPosition, int maxPosition) {
        // The touch event is in display space. Converting it into the task window space.
        final int touchPositionInTaskSpace = isVerticalSplit
                ? (int) (event.getRawX()) - taskBounds.left
                : (int) (event.getRawY()) - taskBounds.top;

        // Assuming that the touch position is at the center of the divider bar, so the divider
        // position is offset by half of the divider width.
        int dividerPosition = touchPositionInTaskSpace - dividerWidthPx / 2;

        // If dragging to fullscreen is not allowed, limit the divider position to the min and max
        // ratios set in DividerAttributes. Otherwise, dragging beyond the min and max ratios is
        // temporarily allowed and the final ratio will be adjusted in onFinishDragging.
        if (!isDraggingToFullscreenAllowed(dividerAttributes)) {
            dividerPosition = Math.clamp(dividerPosition, minPosition, maxPosition);
        }
        return dividerPosition;
    }

    @GuardedBy("mLock")
    private int calculateMinPosition() {
        return calculateMinPosition(
                mProperties.mConfiguration.windowConfiguration.getBounds(),
                mProperties.mDividerWidthPx, mProperties.mDividerAttributes,
                mProperties.mIsVerticalSplit, mProperties.mIsReversedLayout);
    }

    @GuardedBy("mLock")
    private int calculateMaxPosition() {
        return calculateMaxPosition(
                mProperties.mConfiguration.windowConfiguration.getBounds(),
                mProperties.mDividerWidthPx, mProperties.mDividerAttributes,
                mProperties.mIsVerticalSplit, mProperties.mIsReversedLayout);
    }

    /** Calculates the min position of the divider that the user is allowed to drag to. */
    @VisibleForTesting
    static int calculateMinPosition(@NonNull Rect taskBounds, int dividerWidthPx,
            @NonNull DividerAttributes dividerAttributes, boolean isVerticalSplit,
            boolean isReversedLayout) {
        // The usable size is the task window size minus the divider bar width. This is shared
        // between the primary and secondary containers based on the split ratio.
        final int usableSize = isVerticalSplit
                ? taskBounds.width() - dividerWidthPx
                : taskBounds.height() - dividerWidthPx;
        return (int) (isReversedLayout
                ? usableSize - usableSize * dividerAttributes.getPrimaryMaxRatio()
                : usableSize * dividerAttributes.getPrimaryMinRatio());
    }

    /** Calculates the max position of the divider that the user is allowed to drag to. */
    @VisibleForTesting
    static int calculateMaxPosition(@NonNull Rect taskBounds, int dividerWidthPx,
            @NonNull DividerAttributes dividerAttributes, boolean isVerticalSplit,
            boolean isReversedLayout) {
        // The usable size is the task window size minus the divider bar width. This is shared
        // between the primary and secondary containers based on the split ratio.
        final int usableSize = isVerticalSplit
                ? taskBounds.width() - dividerWidthPx
                : taskBounds.height() - dividerWidthPx;
        return (int) (isReversedLayout
                ? usableSize - usableSize * dividerAttributes.getPrimaryMinRatio()
                : usableSize * dividerAttributes.getPrimaryMaxRatio());
    }

    /**
     * Returns the new split ratio of the {@link SplitContainer} based on the current divider
     * position.
     */
    float calculateNewSplitRatio() {
        synchronized (mLock) {
            return calculateNewSplitRatio(
                    mDividerPosition,
                    mProperties.mConfiguration.windowConfiguration.getBounds(),
                    mProperties.mDividerWidthPx,
                    mProperties.mIsVerticalSplit,
                    mProperties.mIsReversedLayout,
                    calculateMinPosition(),
                    calculateMaxPosition(),
                    isDraggingToFullscreenAllowed(mProperties.mDividerAttributes));
        }
    }

    private static boolean isDraggingToFullscreenAllowed(
            @NonNull DividerAttributes dividerAttributes) {
        // TODO(b/293654166) Use DividerAttributes.isDraggingToFullscreenAllowed when extension is
        // updated to v7.
        return false;
    }

    /**
     * Returns the new split ratio of the {@link SplitContainer} based on the current divider
     * position.
     *
     * @param dividerPosition the divider position. See {@link #mDividerPosition}.
     * @param taskBounds the task bounds
     * @param dividerWidthPx the width of the divider in pixels.
     * @param isVerticalSplit if {@code true}, the split is a vertical split. If {@code false}, the
     *                        split is a horizontal split. See
     *                        {@link #isVerticalSplit(SplitAttributes)}.
     * @param isReversedLayout if {@code true}, the split layout is reversed, i.e. right-to-left or
     *                         bottom-to-top. If {@code false}, the split is not reversed, i.e.
     *                         left-to-right or top-to-bottom. See
     *                         {@link SplitAttributesHelper#isReversedLayout}
     * @return the computed split ratio of the primary container. If the primary container is fully
     * expanded, {@link #RATIO_EXPANDED_PRIMARY} is returned. If the secondary container is fully
     * expanded, {@link #RATIO_EXPANDED_SECONDARY} is returned.
     */
    @VisibleForTesting
    static float calculateNewSplitRatio(
            int dividerPosition,
            @NonNull Rect taskBounds,
            int dividerWidthPx,
            boolean isVerticalSplit,
            boolean isReversedLayout,
            int minPosition,
            int maxPosition,
            boolean isDraggingToFullscreenAllowed) {

        // Handle the fully expanded cases.
        if (isDraggingToFullscreenAllowed) {
            // The divider position is already adjusted by the snap algorithm in onFinishDragging.
            // If the divider position is not in the range [minPosition, maxPosition], then one of
            // the containers is fully expanded.
            if (dividerPosition < minPosition) {
                return isReversedLayout ? RATIO_EXPANDED_PRIMARY : RATIO_EXPANDED_SECONDARY;
            }
            if (dividerPosition > maxPosition) {
                return isReversedLayout ? RATIO_EXPANDED_SECONDARY : RATIO_EXPANDED_PRIMARY;
            }
        } else {
            dividerPosition = Math.clamp(dividerPosition, minPosition, maxPosition);
        }

        final int usableSize = isVerticalSplit
                ? taskBounds.width() - dividerWidthPx
                : taskBounds.height() - dividerWidthPx;

        final float newRatio;
        if (isVerticalSplit) {
            final int newPrimaryWidth = isReversedLayout
                    ? taskBounds.width() - (dividerPosition + dividerWidthPx)
                    : dividerPosition;
            newRatio = 1.0f * newPrimaryWidth / usableSize;
        } else {
            final int newPrimaryHeight = isReversedLayout
                    ? taskBounds.height() - (dividerPosition + dividerWidthPx)
                    : dividerPosition;
            newRatio = 1.0f * newPrimaryHeight / usableSize;
        }
        return newRatio;
    }

    /** Callbacks for drag events */
    interface DragEventCallback {
        /**
         * Called when the user starts dragging the divider. Callbacks are executed on
         * {@link #mCallbackExecutor}.
         *
         * @param action additional action that should be applied to the
         *               {@link WindowContainerTransaction}
         */
        void onStartDragging(@NonNull Consumer<WindowContainerTransaction> action);

        /**
         * Called when the user finishes dragging the divider. Callbacks are executed on
         * {@link #mCallbackExecutor}.
         *
         * @param taskId the Task id of the {@link TaskContainer} that this divider belongs to.
         * @param action additional action that should be applied to the
         *               {@link WindowContainerTransaction}
         */
        void onFinishDragging(int taskId, @NonNull Consumer<WindowContainerTransaction> action);
    }

    /**
     * Properties for the {@link DividerPresenter}. The rendering of the divider solely depends on
     * these properties. When any value is updated, the divider is re-rendered. The Properties
     * instance is created only when all the pre-conditions of drawing a divider are met.
     */
    @VisibleForTesting
    static class Properties {
        private static final int CONFIGURATION_MASK_FOR_DIVIDER =
                CONFIG_DENSITY | CONFIG_WINDOW_CONFIGURATION | CONFIG_LAYOUT_DIRECTION;
        @NonNull
        private final Configuration mConfiguration;
        @NonNull
        private final DividerAttributes mDividerAttributes;
        @NonNull
        private final SurfaceControl mDecorSurface;

        /** The initial position of the divider calculated based on container bounds. */
        private final int mInitialDividerPosition;

        /** Whether the split is vertical, such as left-to-right or right-to-left split. */
        private final boolean mIsVerticalSplit;

        private final int mDisplayId;
        private final boolean mIsReversedLayout;
        private final boolean mIsDraggableExpandType;
        @NonNull
        private final TaskFragmentContainer mPrimaryContainer;
        @NonNull
        private final TaskFragmentContainer mSecondaryContainer;
        private final int mDividerWidthPx;

        @VisibleForTesting
        Properties(
                @NonNull Configuration configuration,
                @NonNull DividerAttributes dividerAttributes,
                @NonNull SurfaceControl decorSurface,
                int initialDividerPosition,
                boolean isVerticalSplit,
                boolean isReversedLayout,
                int displayId,
                boolean isDraggableExpandType,
                @NonNull TaskFragmentContainer primaryContainer,
                @NonNull TaskFragmentContainer secondaryContainer) {
            mConfiguration = configuration;
            mDividerAttributes = dividerAttributes;
            mDecorSurface = decorSurface;
            mInitialDividerPosition = initialDividerPosition;
            mIsVerticalSplit = isVerticalSplit;
            mIsReversedLayout = isReversedLayout;
            mDisplayId = displayId;
            mIsDraggableExpandType = isDraggableExpandType;
            mPrimaryContainer = primaryContainer;
            mSecondaryContainer = secondaryContainer;
            mDividerWidthPx = getDividerWidthPx(dividerAttributes);
        }

        /**
         * Compares whether two Properties objects are equal for rendering the divider. The
         * Configuration is checked for rendering related fields, and other fields are checked for
         * regular equality.
         */
        private static boolean equalsForDivider(@Nullable Properties a, @Nullable Properties b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return areSameSurfaces(a.mDecorSurface, b.mDecorSurface)
                    && Objects.equals(a.mDividerAttributes, b.mDividerAttributes)
                    && areConfigurationsEqualForDivider(a.mConfiguration, b.mConfiguration)
                    && a.mInitialDividerPosition == b.mInitialDividerPosition
                    && a.mIsVerticalSplit == b.mIsVerticalSplit
                    && a.mDisplayId == b.mDisplayId
                    && a.mIsReversedLayout == b.mIsReversedLayout
                    && a.mIsDraggableExpandType == b.mIsDraggableExpandType
                    && a.mPrimaryContainer == b.mPrimaryContainer
                    && a.mSecondaryContainer == b.mSecondaryContainer;
        }

        private static boolean areSameSurfaces(
                @Nullable SurfaceControl sc1, @Nullable SurfaceControl sc2) {
            if (sc1 == sc2) {
                // If both are null or both refer to the same object.
                return true;
            }
            if (sc1 == null || sc2 == null) {
                return false;
            }
            return sc1.isSameSurface(sc2);
        }

        private static boolean areConfigurationsEqualForDivider(
                @NonNull Configuration a, @NonNull Configuration b) {
            final int diff = a.diff(b);
            return (diff & CONFIGURATION_MASK_FOR_DIVIDER) == 0;
        }
    }

    /**
     * Handles the rendering of the divider. When the decor surface is updated, the renderer is
     * recreated. When other fields in the Properties are changed, the renderer is updated.
     */
    @VisibleForTesting
    static class Renderer {
        @NonNull
        private final SurfaceControl mDividerSurface;
        @NonNull
        private final WindowlessWindowManager mWindowlessWindowManager;
        @NonNull
        private final SurfaceControlViewHost mViewHost;
        @NonNull
        private final FrameLayout mDividerLayout;
        @NonNull
        private final View mDividerLine;
        private View mDragHandle;
        @NonNull
        private final View.OnTouchListener mListener;
        @NonNull
        private Properties mProperties;
        private int mHandleWidthPx;
        @Nullable
        private SurfaceControl mPrimaryVeil;
        @Nullable
        private SurfaceControl mSecondaryVeil;
        private boolean mIsDragging;
        private int mDividerPosition;
        private int mDividerSurfaceWidthPx;

        private Renderer(@NonNull Properties properties, @NonNull View.OnTouchListener listener) {
            mProperties = properties;
            mListener = listener;

            mDividerSurface = createChildSurface("DividerSurface", true /* visible */);
            mWindowlessWindowManager = new WindowlessWindowManager(
                    mProperties.mConfiguration,
                    mDividerSurface,
                    new InputTransferToken());

            final Context context = ActivityThread.currentActivityThread().getApplication();
            final DisplayManager displayManager = context.getSystemService(DisplayManager.class);
            mViewHost = new SurfaceControlViewHost(
                    context, displayManager.getDisplay(mProperties.mDisplayId),
                    mWindowlessWindowManager, "DividerContainer");
            mDividerLayout = new FrameLayout(context);
            mDividerLine = new View(context);

            update();
        }

        /** Updates the divider when properties are changed */
        private void update(@NonNull Properties newProperties) {
            mProperties = newProperties;
            update();
        }

        /** Updates the divider when initializing or when properties are changed */
        @VisibleForTesting
        void update() {
            mDividerPosition = mProperties.mInitialDividerPosition;
            mWindowlessWindowManager.setConfiguration(mProperties.mConfiguration);

            if (mProperties.mDividerAttributes.getDividerType()
                    == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
                // TODO(b/329193115) support divider on secondary display
                final Context context = ActivityThread.currentActivityThread().getApplication();
                mHandleWidthPx = context.getResources().getDimensionPixelSize(
                        R.dimen.activity_embedding_divider_touch_target_width);
            } else {
                mHandleWidthPx = 0;
            }

            // TODO handle synchronization between surface transactions and WCT.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            updateSurface(t);
            updateLayout();
            updateDivider(t);
            t.apply();
        }

        @VisibleForTesting
        void release() {
            mViewHost.release();
            // TODO handle synchronization between surface transactions and WCT.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.remove(mDividerSurface);
            removeVeils(t);
            t.apply();
        }

        private void setDividerPosition(int dividerPosition) {
            mDividerPosition = dividerPosition;
        }

        /**
         * Updates the positions and crops of the divider surface and veil surfaces. This method
         * should be called when {@link #mProperties} is changed or while dragging to update the
         * position of the divider surface and the veil surfaces.
         *
         * This method applies the changes in a stand-alone surface transaction immediately.
         */
        private void updateSurface() {
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            updateSurface(t);
            t.apply();
        }

        /**
         * Updates the positions and crops of the divider surface and veil surfaces. This method
         * should be called when {@link #mProperties} is changed or while dragging to update the
         * position of the divider surface and the veil surfaces.
         *
         * This method applies the changes in the provided surface transaction and can be synced
         * with other changes.
         */
        private void updateSurface(@NonNull SurfaceControl.Transaction t) {
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();

            int dividerSurfacePosition;
            if (mProperties.mDividerAttributes.getDividerType()
                    == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
                // When the divider drag handle width is larger than the divider width, the position
                // of the divider surface is adjusted so that it is large enough to host both the
                // divider line and the divider drag handle.
                mDividerSurfaceWidthPx = Math.max(mProperties.mDividerWidthPx, mHandleWidthPx);
                dividerSurfacePosition = mProperties.mIsReversedLayout
                        ? mDividerPosition
                        : mDividerPosition + mProperties.mDividerWidthPx - mDividerSurfaceWidthPx;
                dividerSurfacePosition =
                        Math.clamp(dividerSurfacePosition, 0,
                                mProperties.mIsVerticalSplit
                                        ? taskBounds.width() - mDividerSurfaceWidthPx
                                        : taskBounds.height() - mDividerSurfaceWidthPx);
            } else {
                mDividerSurfaceWidthPx = mProperties.mDividerWidthPx;
                dividerSurfacePosition = mDividerPosition;
            }

            if (mProperties.mIsVerticalSplit) {
                t.setPosition(mDividerSurface, dividerSurfacePosition, 0.0f);
                t.setWindowCrop(mDividerSurface, mDividerSurfaceWidthPx, taskBounds.height());
            } else {
                t.setPosition(mDividerSurface, 0.0f, dividerSurfacePosition);
                t.setWindowCrop(mDividerSurface, taskBounds.width(), mDividerSurfaceWidthPx);
            }

            // Update divider line position in the surface
            final int offset = mDividerPosition - dividerSurfacePosition;
            mDividerLine.setX(mProperties.mIsVerticalSplit ? offset : 0);
            mDividerLine.setY(mProperties.mIsVerticalSplit ? 0 : offset);

            if (mIsDragging) {
                updateVeils(t);
            }
        }

        /**
         * Updates the layout parameters of the layout used to host the divider. This method should
         * be called only when {@link #mProperties} is changed. This should not be called while
         * dragging, because the layout parameters are not changed during dragging.
         */
        private void updateLayout() {
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            final WindowManager.LayoutParams lp = mProperties.mIsVerticalSplit
                    ? new WindowManager.LayoutParams(
                            mDividerSurfaceWidthPx,
                            taskBounds.height(),
                            TYPE_APPLICATION_PANEL,
                            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SLIPPERY,
                            PixelFormat.TRANSLUCENT)
                    : new WindowManager.LayoutParams(
                            taskBounds.width(),
                            mDividerSurfaceWidthPx,
                            TYPE_APPLICATION_PANEL,
                            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SLIPPERY,
                            PixelFormat.TRANSLUCENT);
            lp.setTitle(WINDOW_NAME);

            // Ensure that the divider layout is always LTR regardless of the locale, because we
            // already considered the locale when determining the split layout direction and the
            // computed divider line position always starts from the left. This only affects the
            // horizontal layout and does not have any effect on the top-to-bottom layout.
            mDividerLayout.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            mViewHost.setView(mDividerLayout, lp);
            mViewHost.relayout(lp);
        }

        /**
         * Updates the UI component of the divider, including the drag handle and the veils. This
         * method should be called only when {@link #mProperties} is changed. This should not be
         * called while dragging, because the UI components are not changed during dragging and
         * only their surface positions are changed.
         */
        private void updateDivider(@NonNull SurfaceControl.Transaction t) {
            mDividerLayout.removeAllViews();
            mDividerLayout.addView(mDividerLine);
            if (mProperties.mIsDraggableExpandType && !mIsDragging) {
                // If a container is fully expanded, the divider overlays on the expanded container.
                mDividerLine.setBackgroundColor(Color.TRANSPARENT);
            } else {
                mDividerLine.setBackgroundColor(mProperties.mDividerAttributes.getDividerColor());
            }
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            mDividerLine.setLayoutParams(
                    mProperties.mIsVerticalSplit
                            ? new FrameLayout.LayoutParams(
                                    mProperties.mDividerWidthPx, taskBounds.height())
                            : new FrameLayout.LayoutParams(
                                    taskBounds.width(), mProperties.mDividerWidthPx)
            );
            if (mProperties.mDividerAttributes.getDividerType()
                    == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
                createVeils();
                drawDragHandle();
            } else {
                removeVeils(t);
            }
            mViewHost.getView().invalidate();
        }

        private void drawDragHandle() {
            final Context context = mDividerLayout.getContext();
            final ImageButton button = new ImageButton(context);
            final FrameLayout.LayoutParams params = mProperties.mIsVerticalSplit
                    ? new FrameLayout.LayoutParams(
                            context.getResources().getDimensionPixelSize(
                                    R.dimen.activity_embedding_divider_touch_target_width),
                            context.getResources().getDimensionPixelSize(
                                    R.dimen.activity_embedding_divider_touch_target_height))
                    : new FrameLayout.LayoutParams(
                            context.getResources().getDimensionPixelSize(
                                    R.dimen.activity_embedding_divider_touch_target_height),
                            context.getResources().getDimensionPixelSize(
                                    R.dimen.activity_embedding_divider_touch_target_width));
            params.gravity = Gravity.CENTER;
            button.setLayoutParams(params);
            button.setBackgroundColor(Color.TRANSPARENT);

            final Drawable handle = context.getResources().getDrawable(
                    R.drawable.activity_embedding_divider_handle, context.getTheme());
            if (mProperties.mIsVerticalSplit) {
                button.setImageDrawable(handle);
            } else {
                // Rotate the handle drawable
                RotateDrawable rotatedHandle = new RotateDrawable();
                rotatedHandle.setFromDegrees(90f);
                rotatedHandle.setToDegrees(90f);
                rotatedHandle.setPivotXRelative(true);
                rotatedHandle.setPivotYRelative(true);
                rotatedHandle.setPivotX(0.5f);
                rotatedHandle.setPivotY(0.5f);
                rotatedHandle.setLevel(1);
                rotatedHandle.setDrawable(handle);

                button.setImageDrawable(rotatedHandle);
            }

            button.setOnTouchListener(mListener);
            mDragHandle = button;
            mDividerLayout.addView(button);
        }

        @NonNull
        private SurfaceControl createChildSurface(@NonNull String name, boolean visible) {
            final Rect bounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            return new SurfaceControl.Builder()
                    .setParent(mProperties.mDecorSurface)
                    .setName(name)
                    .setHidden(!visible)
                    .setCallsite("DividerManager.createChildSurface")
                    .setBufferSize(bounds.width(), bounds.height())
                    .setEffectLayer()
                    .build();
        }

        private void createVeils() {
            if (mPrimaryVeil == null) {
                mPrimaryVeil = createChildSurface("DividerPrimaryVeil", false /* visible */);
            }
            if (mSecondaryVeil == null) {
                mSecondaryVeil = createChildSurface("DividerSecondaryVeil", false /* visible */);
            }
        }

        private void removeVeils(@NonNull SurfaceControl.Transaction t) {
            if (mPrimaryVeil != null) {
                t.remove(mPrimaryVeil);
            }
            if (mSecondaryVeil != null) {
                t.remove(mSecondaryVeil);
            }
            mPrimaryVeil = null;
            mSecondaryVeil = null;
        }

        private void showVeils(@NonNull SurfaceControl.Transaction t) {
            final Color primaryVeilColor = getContainerBackgroundColor(
                    mProperties.mPrimaryContainer, DEFAULT_PRIMARY_VEIL_COLOR);
            final Color secondaryVeilColor = getContainerBackgroundColor(
                    mProperties.mSecondaryContainer, DEFAULT_SECONDARY_VEIL_COLOR);
            t.setColor(mPrimaryVeil, colorToFloatArray(primaryVeilColor))
                    .setColor(mSecondaryVeil, colorToFloatArray(secondaryVeilColor))
                    .setLayer(mDividerSurface, DIVIDER_LAYER)
                    .setLayer(mPrimaryVeil, VEIL_LAYER)
                    .setLayer(mSecondaryVeil, VEIL_LAYER)
                    .setVisibility(mPrimaryVeil, true)
                    .setVisibility(mSecondaryVeil, true);
            updateVeils(t);
        }

        private void hideVeils(@NonNull SurfaceControl.Transaction t) {
            t.setVisibility(mPrimaryVeil, false).setVisibility(mSecondaryVeil, false);
        }

        private void updateVeils(@NonNull SurfaceControl.Transaction t) {
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();

            // Relative bounds of the primary and secondary containers in the Task.
            Rect primaryBounds;
            Rect secondaryBounds;
            if (mProperties.mIsVerticalSplit) {
                final Rect boundsLeft = new Rect(0, 0, mDividerPosition, taskBounds.height());
                final Rect boundsRight = new Rect(mDividerPosition + mProperties.mDividerWidthPx, 0,
                        taskBounds.width(), taskBounds.height());
                primaryBounds = mProperties.mIsReversedLayout ? boundsRight : boundsLeft;
                secondaryBounds = mProperties.mIsReversedLayout ? boundsLeft : boundsRight;
            } else {
                final Rect boundsTop = new Rect(0, 0, taskBounds.width(), mDividerPosition);
                final Rect boundsBottom = new Rect(
                        0, mDividerPosition + mProperties.mDividerWidthPx,
                        taskBounds.width(), taskBounds.height());
                primaryBounds = mProperties.mIsReversedLayout ? boundsBottom : boundsTop;
                secondaryBounds = mProperties.mIsReversedLayout ? boundsTop : boundsBottom;
            }
            if (mPrimaryVeil != null) {
                t.setWindowCrop(mPrimaryVeil, primaryBounds.width(), primaryBounds.height());
                t.setPosition(mPrimaryVeil, primaryBounds.left, primaryBounds.top);
                t.setVisibility(mPrimaryVeil, !primaryBounds.isEmpty());
            }
            if (mSecondaryVeil != null) {
                t.setWindowCrop(mSecondaryVeil, secondaryBounds.width(), secondaryBounds.height());
                t.setPosition(mSecondaryVeil, secondaryBounds.left, secondaryBounds.top);
                t.setVisibility(mSecondaryVeil, !secondaryBounds.isEmpty());
            }
        }

        private static float[] colorToFloatArray(@NonNull Color color) {
            return new float[]{color.red(), color.green(), color.blue()};
        }
    }
}
