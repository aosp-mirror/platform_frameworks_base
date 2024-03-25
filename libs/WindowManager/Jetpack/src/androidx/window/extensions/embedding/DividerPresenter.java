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

import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
import static android.window.TaskFragmentOperation.OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_REMOVE_TASK_FRAGMENT_DECOR_SURFACE;
import static android.window.TaskFragmentOperation.OP_TYPE_SET_DECOR_SURFACE_BOOSTED;

import static androidx.window.extensions.embedding.DividerAttributes.RATIO_UNSET;
import static androidx.window.extensions.embedding.DividerAttributes.WIDTH_UNSET;
import static androidx.window.extensions.embedding.SplitAttributesHelper.isReversedLayout;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_BOTTOM;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_LEFT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_RIGHT;
import static androidx.window.extensions.embedding.SplitPresenter.CONTAINER_POSITION_TOP;

import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RotateDrawable;
import android.hardware.display.DisplayManager;
import android.os.IBinder;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.window.InputTransferToken;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.window.extensions.core.util.function.Consumer;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Manages the rendering and interaction of the divider.
 */
class DividerPresenter implements View.OnTouchListener {
    private static final String WINDOW_NAME = "AE Divider";
    private static final int VEIL_LAYER = 0;
    private static final int DIVIDER_LAYER = 1;

    // TODO(b/327067596) Update based on UX guidance.
    private static final Color DEFAULT_DIVIDER_COLOR = Color.valueOf(Color.BLACK);
    private static final Color DEFAULT_PRIMARY_VEIL_COLOR = Color.valueOf(Color.BLACK);
    private static final Color DEFAULT_SECONDARY_VEIL_COLOR = Color.valueOf(Color.GRAY);
    @VisibleForTesting
    static final float DEFAULT_MIN_RATIO = 0.35f;
    @VisibleForTesting
    static final float DEFAULT_MAX_RATIO = 0.65f;
    @VisibleForTesting
    static final int DEFAULT_DIVIDER_WIDTH_DP = 24;

    private final int mTaskId;

    @NonNull
    private final Object mLock = new Object();

    @NonNull
    private final DragEventCallback mDragEventCallback;

    @NonNull
    private final Executor mCallbackExecutor;

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

            // Clean up the decor surface if DividerAttributes is null.
            final DividerAttributes dividerAttributes =
                    topSplitContainer.getCurrentSplitAttributes().getDividerAttributes();
            if (dividerAttributes == null) {
                removeDecorSurfaceAndDivider(wct);
                return;
            }

            if (topSplitContainer.getCurrentSplitAttributes().getSplitType()
                    instanceof SplitAttributes.SplitType.ExpandContainersSplitType) {
                // No divider is needed for ExpandContainersSplitType.
                removeDivider();
                return;
            }

            // Skip updating when the TFs have not been updated to match the SplitAttributes.
            if (topSplitContainer.getPrimaryContainer().getLastRequestedBounds().isEmpty()
                    || topSplitContainer.getSecondaryContainer().getLastRequestedBounds()
                    .isEmpty()) {
                return;
            }

            final SurfaceControl decorSurface = parentInfo.getDecorSurface();
            if (decorSurface == null) {
                // Clean up when the decor surface is currently unavailable.
                removeDivider();
                // Request to create the decor surface
                createOrMoveDecorSurface(wct, topSplitContainer.getPrimaryContainer());
                return;
            }

            // make the top primary container the owner of the decor surface.
            if (!Objects.equals(mDecorSurfaceOwner,
                    topSplitContainer.getPrimaryContainer().getTaskFragmentToken())) {
                createOrMoveDecorSurface(wct, topSplitContainer.getPrimaryContainer());
            }

            updateProperties(
                    new Properties(
                            parentInfo.getConfiguration(),
                            dividerAttributes,
                            decorSurface,
                            getInitialDividerPosition(topSplitContainer),
                            isVerticalSplit(topSplitContainer),
                            isReversedLayout(
                                    topSplitContainer.getCurrentSplitAttributes(),
                                    parentInfo.getConfiguration()),
                            parentInfo.getDisplayId()));
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
     * Creates a decor surface for the TaskFragment if no decor surface exists, or changes the owner
     * of the existing decor surface to be the specified TaskFragment.
     *
     * See {@link TaskFragmentOperation#OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE}.
     */
    @GuardedBy("mLock")
    private void createOrMoveDecorSurface(
            @NonNull WindowContainerTransaction wct, @NonNull TaskFragmentContainer container) {
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();
        wct.addTaskFragmentOperation(container.getTaskFragmentToken(), operation);
        mDecorSurfaceOwner = container.getTaskFragmentToken();
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
    static int getInitialDividerPosition(@NonNull SplitContainer splitContainer) {
        final Rect primaryBounds =
                splitContainer.getPrimaryContainer().getLastRequestedBounds();
        final Rect secondaryBounds =
                splitContainer.getSecondaryContainer().getLastRequestedBounds();
        if (isVerticalSplit(splitContainer)) {
            return Math.min(primaryBounds.right, secondaryBounds.right);
        } else {
            return Math.min(primaryBounds.bottom, secondaryBounds.bottom);
        }
    }

    private static boolean isVerticalSplit(@NonNull SplitContainer splitContainer) {
        final int layoutDirection = splitContainer.getCurrentSplitAttributes().getLayoutDirection();
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

    private static int getDimensionDp(@IdRes int resId) {
        final Context context = ActivityThread.currentActivityThread().getApplication();
        final int px = context.getResources().getDimensionPixelSize(resId);
        return (int) TypedValue.convertPixelsToDimension(
                COMPLEX_UNIT_DIP,
                px,
                context.getResources().getDisplayMetrics());
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
            @NonNull SplitAttributes.SplitType splitType,
            @SplitPresenter.ContainerPosition int position) {
        if (splitType instanceof SplitAttributes.SplitType.ExpandContainersSplitType) {
            // No divider is needed for the ExpandContainersSplitType.
            return 0;
        }
        int primaryOffset;
        if (splitType instanceof final SplitAttributes.SplitType.RatioSplitType splitRatio) {
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
     * {@link DividerAttributes#WIDTH_UNSET} and {@link DividerAttributes#RATIO_UNSET}.
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
        if (widthDp == WIDTH_UNSET) {
            widthDp = DEFAULT_DIVIDER_WIDTH_DP;
        }

        if (dividerAttributes.getDividerType() == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
            // Draggable divider width must be larger than the drag handle size.
            widthDp = Math.max(widthDp,
                    getDimensionDp(R.dimen.activity_embedding_divider_touch_target_width));
        }

        float minRatio = dividerAttributes.getPrimaryMinRatio();
        if (minRatio == RATIO_UNSET) {
            minRatio = DEFAULT_MIN_RATIO;
        }

        float maxRatio = dividerAttributes.getPrimaryMaxRatio();
        if (maxRatio == RATIO_UNSET) {
            maxRatio = DEFAULT_MAX_RATIO;
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
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            mDividerPosition = calculateDividerPosition(
                    event, taskBounds, mRenderer.mDividerWidthPx, mProperties.mDividerAttributes,
                    mProperties.mIsVerticalSplit, mProperties.mIsReversedLayout);
            mRenderer.setDividerPosition(mDividerPosition);
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    onStartDragging();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    onFinishDragging();
                    break;
                case MotionEvent.ACTION_MOVE:
                    onDrag();
                    break;
                default:
                    break;
            }
        }

        // Returns false so that the default button click callback is still triggered, i.e. the
        // button UI transitions into the "pressed" state.
        return false;
    }

    @GuardedBy("mLock")
    private void onStartDragging() {
        mRenderer.mIsDragging = true;
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mRenderer.updateSurface(t);
        mRenderer.showVeils(t);
        final IBinder decorSurfaceOwner = mDecorSurfaceOwner;

        // Callbacks must be executed on the executor to release mLock and prevent deadlocks.
        mCallbackExecutor.execute(() -> {
            mDragEventCallback.onStartDragging(
                    wct -> setDecorSurfaceBoosted(wct, decorSurfaceOwner, true /* boosted */, t));
        });
    }

    @GuardedBy("mLock")
    private void onDrag() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mRenderer.updateSurface(t);
        t.apply();
    }

    @GuardedBy("mLock")
    private void onFinishDragging() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        mRenderer.updateSurface(t);
        mRenderer.hideVeils(t);
        final IBinder decorSurfaceOwner = mDecorSurfaceOwner;

        // Callbacks must be executed on the executor to release mLock and prevent deadlocks.
        mCallbackExecutor.execute(() -> {
            mDragEventCallback.onFinishDragging(
                    mTaskId,
                    wct -> setDecorSurfaceBoosted(wct, decorSurfaceOwner, false /* boosted */, t));
        });
        mRenderer.mIsDragging = false;
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
            boolean isVerticalSplit, boolean isReversedLayout) {
        // The touch event is in display space. Converting it into the task window space.
        final int touchPositionInTaskSpace = isVerticalSplit
                ? (int) (event.getRawX()) - taskBounds.left
                : (int) (event.getRawY()) - taskBounds.top;

        // Assuming that the touch position is at the center of the divider bar, so the divider
        // position is offset by half of the divider width.
        int dividerPosition = touchPositionInTaskSpace - dividerWidthPx / 2;

        // Limit the divider position to the min and max ratios set in DividerAttributes.
        // TODO(b/327536303) Handle when the divider is dragged to the edge.
        dividerPosition = Math.max(dividerPosition, calculateMinPosition(
                taskBounds, dividerWidthPx, dividerAttributes, isVerticalSplit, isReversedLayout));
        dividerPosition = Math.min(dividerPosition, calculateMaxPosition(
                taskBounds, dividerWidthPx, dividerAttributes, isVerticalSplit, isReversedLayout));
        return dividerPosition;
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
    float calculateNewSplitRatio(@NonNull SplitContainer topSplitContainer) {
        synchronized (mLock) {
            return calculateNewSplitRatio(
                    topSplitContainer,
                    mDividerPosition,
                    mProperties.mConfiguration.windowConfiguration.getBounds(),
                    mRenderer.mDividerWidthPx,
                    mProperties.mIsVerticalSplit,
                    mProperties.mIsReversedLayout);
        }
    }

    /**
     * Returns the new split ratio of the {@link SplitContainer} based on the current divider
     * position.
     * @param topSplitContainer the {@link SplitContainer} for which to compute the split ratio.
     * @param dividerPosition the divider position. See {@link #mDividerPosition}.
     * @param taskBounds the task bounds
     * @param dividerWidthPx the width of the divider in pixels.
     * @param isVerticalSplit if {@code true}, the split is a vertical split. If {@code false}, the
     *                        split is a horizontal split. See
     *                        {@link #isVerticalSplit(SplitContainer)}.
     * @param isReversedLayout if {@code true}, the split layout is reversed, i.e. right-to-left or
     *                         bottom-to-top. If {@code false}, the split is not reversed, i.e.
     *                         left-to-right or top-to-bottom. See
     *                         {@link SplitAttributesHelper#isReversedLayout}
     * @return the computed split ratio of the primary container.
     */
    @VisibleForTesting
    static float calculateNewSplitRatio(
            @NonNull SplitContainer topSplitContainer,
            int dividerPosition,
            @NonNull Rect taskBounds,
            int dividerWidthPx,
            boolean isVerticalSplit,
            boolean isReversedLayout) {
        final int usableSize = isVerticalSplit
                ? taskBounds.width() - dividerWidthPx
                : taskBounds.height() - dividerWidthPx;

        final TaskFragmentContainer primaryContainer = topSplitContainer.getPrimaryContainer();
        final Rect origPrimaryBounds = primaryContainer.getLastRequestedBounds();

        float newRatio;
        if (isVerticalSplit) {
            final int newPrimaryWidth = isReversedLayout
                    ? (origPrimaryBounds.right - (dividerPosition + dividerWidthPx))
                    : (dividerPosition - origPrimaryBounds.left);
            newRatio = 1.0f * newPrimaryWidth / usableSize;
        } else {
            final int newPrimaryHeight = isReversedLayout
                    ? (origPrimaryBounds.bottom - (dividerPosition + dividerWidthPx))
                    : (dividerPosition - origPrimaryBounds.top);
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
                ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
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

        @VisibleForTesting
        Properties(
                @NonNull Configuration configuration,
                @NonNull DividerAttributes dividerAttributes,
                @NonNull SurfaceControl decorSurface,
                int initialDividerPosition,
                boolean isVerticalSplit,
                boolean isReversedLayout,
                int displayId) {
            mConfiguration = configuration;
            mDividerAttributes = dividerAttributes;
            mDecorSurface = decorSurface;
            mInitialDividerPosition = initialDividerPosition;
            mIsVerticalSplit = isVerticalSplit;
            mIsReversedLayout = isReversedLayout;
            mDisplayId = displayId;
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
                    && a.mIsReversedLayout == b.mIsReversedLayout;
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
        private final View.OnTouchListener mListener;
        @NonNull
        private Properties mProperties;
        private int mDividerWidthPx;
        @Nullable
        private SurfaceControl mPrimaryVeil;
        @Nullable
        private SurfaceControl mSecondaryVeil;
        private boolean mIsDragging;
        private int mDividerPosition;

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
            mDividerWidthPx = getDividerWidthPx(mProperties.mDividerAttributes);
            mDividerPosition = mProperties.mInitialDividerPosition;
            mWindowlessWindowManager.setConfiguration(mProperties.mConfiguration);
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
         */
        private void updateSurface(@NonNull SurfaceControl.Transaction t) {
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            if (mProperties.mIsVerticalSplit) {
                t.setPosition(mDividerSurface, mDividerPosition, 0.0f);
                t.setWindowCrop(mDividerSurface, mDividerWidthPx, taskBounds.height());
            } else {
                t.setPosition(mDividerSurface, 0.0f, mDividerPosition);
                t.setWindowCrop(mDividerSurface, taskBounds.width(), mDividerWidthPx);
            }
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
                            mDividerWidthPx,
                            taskBounds.height(),
                            TYPE_APPLICATION_PANEL,
                            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SLIPPERY,
                            PixelFormat.TRANSLUCENT)
                    : new WindowManager.LayoutParams(
                            taskBounds.width(),
                            mDividerWidthPx,
                            TYPE_APPLICATION_PANEL,
                            FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_SLIPPERY,
                            PixelFormat.TRANSLUCENT);
            lp.setTitle(WINDOW_NAME);
            mViewHost.setView(mDividerLayout, lp);
        }

        /**
         * Updates the UI component of the divider, including the drag handle and the veils. This
         * method should be called only when {@link #mProperties} is changed. This should not be
         * called while dragging, because the UI components are not changed during dragging and
         * only their surface positions are changed.
         */
        private void updateDivider(@NonNull SurfaceControl.Transaction t) {
            mDividerLayout.removeAllViews();
            mDividerLayout.setBackgroundColor(DEFAULT_DIVIDER_COLOR.toArgb());
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
            button.setBackgroundColor(R.color.transparent);

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
                    .setColorLayer()
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
            t.setColor(mPrimaryVeil, colorToFloatArray(DEFAULT_PRIMARY_VEIL_COLOR))
                    .setColor(mSecondaryVeil, colorToFloatArray(DEFAULT_SECONDARY_VEIL_COLOR))
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
                final Rect boundsRight = new Rect(mDividerPosition + mDividerWidthPx, 0,
                        taskBounds.width(), taskBounds.height());
                primaryBounds = mProperties.mIsReversedLayout ? boundsRight : boundsLeft;
                secondaryBounds = mProperties.mIsReversedLayout ? boundsLeft : boundsRight;
            } else {
                final Rect boundsTop = new Rect(0, 0, taskBounds.width(), mDividerPosition);
                final Rect boundsBottom = new Rect(0, mDividerPosition + mDividerWidthPx,
                        taskBounds.width(), taskBounds.height());
                primaryBounds = mProperties.mIsReversedLayout ? boundsBottom : boundsTop;
                secondaryBounds = mProperties.mIsReversedLayout ? boundsTop : boundsBottom;
            }
            t.setWindowCrop(mPrimaryVeil, primaryBounds.width(), primaryBounds.height());
            t.setWindowCrop(mSecondaryVeil, secondaryBounds.width(), secondaryBounds.height());
            t.setPosition(mPrimaryVeil, primaryBounds.left, primaryBounds.top);
            t.setPosition(mSecondaryVeil, secondaryBounds.left, secondaryBounds.top);
        }

        private static float[] colorToFloatArray(@NonNull Color color) {
            return new float[]{color.red(), color.green(), color.blue()};
        }
    }
}
