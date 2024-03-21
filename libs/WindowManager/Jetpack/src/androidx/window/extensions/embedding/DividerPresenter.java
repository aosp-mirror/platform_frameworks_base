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

import static androidx.window.extensions.embedding.DividerAttributes.RATIO_UNSET;
import static androidx.window.extensions.embedding.DividerAttributes.WIDTH_UNSET;
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
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.window.InputTransferToken;
import android.window.TaskFragmentOperation;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.window.flags.Flags;

import java.util.Objects;

/**
 * Manages the rendering and interaction of the divider.
 */
class DividerPresenter {
    private static final String WINDOW_NAME = "AE Divider";

    // TODO(b/327067596) Update based on UX guidance.
    private static final Color DEFAULT_DIVIDER_COLOR = Color.valueOf(Color.BLACK);
    @VisibleForTesting
    static final float DEFAULT_MIN_RATIO = 0.35f;
    @VisibleForTesting
    static final float DEFAULT_MAX_RATIO = 0.65f;
    @VisibleForTesting
    static final int DEFAULT_DIVIDER_WIDTH_DP = 24;

    /**
     * The {@link Properties} of the divider. This field is {@code null} when no divider should be
     * drawn, e.g. when the split doesn't have {@link DividerAttributes} or when the decor surface
     * is not available.
     */
    @Nullable
    @VisibleForTesting
    Properties mProperties;

    /**
     * The {@link Renderer} of the divider. This field is {@code null} when no divider should be
     * drawn, i.e. when {@link #mProperties} is {@code null}. The {@link Renderer} is recreated or
     * updated when {@link #mProperties} is changed.
     */
    @Nullable
    @VisibleForTesting
    Renderer mRenderer;

    /**
     * The owner TaskFragment token of the decor surface. The decor surface is placed right above
     * the owner TaskFragment surface and is removed if the owner TaskFragment is destroyed.
     */
    @Nullable
    @VisibleForTesting
    IBinder mDecorSurfaceOwner;

    /** Updates the divider when external conditions are changed. */
    void updateDivider(
            @NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentParentInfo parentInfo,
            @Nullable SplitContainer topSplitContainer) {
        if (!Flags.activityEmbeddingInteractiveDividerFlag()) {
            return;
        }

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
                || topSplitContainer.getSecondaryContainer().getLastRequestedBounds().isEmpty()) {
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
                        parentInfo.getDisplayId()));
    }

    private void updateProperties(@NonNull Properties properties) {
        if (Properties.equalsForDivider(mProperties, properties)) {
            return;
        }
        final Properties previousProperties = mProperties;
        mProperties = properties;

        if (mRenderer == null) {
            // Create a new renderer when a renderer doesn't exist yet.
            mRenderer = new Renderer();
        } else if (!Properties.areSameSurfaces(
                previousProperties.mDecorSurface, mProperties.mDecorSurface)
                || previousProperties.mDisplayId != mProperties.mDisplayId) {
            // Release and recreate the renderer if the decor surface or the display has changed.
            mRenderer.release();
            mRenderer = new Renderer();
        } else {
            // Otherwise, update the renderer for the new properties.
            mRenderer.update();
        }
    }

    /**
     * Creates a decor surface for the TaskFragment if no decor surface exists, or changes the owner
     * of the existing decor surface to be the specified TaskFragment.
     *
     * See {@link TaskFragmentOperation#OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE}.
     */
    private void createOrMoveDecorSurface(
            @NonNull WindowContainerTransaction wct, @NonNull TaskFragmentContainer container) {
        final TaskFragmentOperation operation = new TaskFragmentOperation.Builder(
                OP_TYPE_CREATE_OR_MOVE_TASK_FRAGMENT_DECOR_SURFACE)
                .build();
        wct.addTaskFragmentOperation(container.getTaskFragmentToken(), operation);
        mDecorSurfaceOwner = container.getTaskFragmentToken();
    }

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
        switch(layoutDirection) {
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

    private static void safeReleaseSurfaceControl(@Nullable SurfaceControl sc) {
        if (sc != null) {
            sc.release();
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

        @VisibleForTesting
        Properties(
                @NonNull Configuration configuration,
                @NonNull DividerAttributes dividerAttributes,
                @NonNull SurfaceControl decorSurface,
                int initialDividerPosition,
                boolean isVerticalSplit,
                int displayId) {
            mConfiguration = configuration;
            mDividerAttributes = dividerAttributes;
            mDecorSurface = decorSurface;
            mInitialDividerPosition = initialDividerPosition;
            mIsVerticalSplit = isVerticalSplit;
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
                    && a.mDisplayId == b.mDisplayId;
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
    class Renderer {
        @NonNull
        private final SurfaceControl mDividerSurface;
        @NonNull
        private final WindowlessWindowManager mWindowlessWindowManager;
        @NonNull
        private final SurfaceControlViewHost mViewHost;
        @NonNull
        private final FrameLayout mDividerLayout;
        private final int mDividerWidthPx;

        private Renderer() {
            mDividerWidthPx = getDividerWidthPx(mProperties.mDividerAttributes);

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
        @VisibleForTesting
        void update() {
            mWindowlessWindowManager.setConfiguration(mProperties.mConfiguration);
            updateSurface();
            updateLayout();
            updateDivider();
        }

        @VisibleForTesting
        void release() {
            mViewHost.release();
            // TODO handle synchronization between surface transactions and WCT.
            new SurfaceControl.Transaction().remove(mDividerSurface).apply();
            safeReleaseSurfaceControl(mDividerSurface);
        }

        private void updateSurface() {
            final Rect taskBounds = mProperties.mConfiguration.windowConfiguration.getBounds();
            // TODO handle synchronization between surface transactions and WCT.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (mProperties.mIsVerticalSplit) {
                t.setPosition(mDividerSurface, mProperties.mInitialDividerPosition, 0.0f);
                t.setWindowCrop(mDividerSurface, mDividerWidthPx, taskBounds.height());
            } else {
                t.setPosition(mDividerSurface, 0.0f, mProperties.mInitialDividerPosition);
                t.setWindowCrop(mDividerSurface, taskBounds.width(), mDividerWidthPx);
            }
            t.apply();
        }

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

        private void updateDivider() {
            mDividerLayout.removeAllViews();
            mDividerLayout.setBackgroundColor(DEFAULT_DIVIDER_COLOR.toArgb());
            if (mProperties.mDividerAttributes.getDividerType()
                    == DividerAttributes.DIVIDER_TYPE_DRAGGABLE) {
                drawDragHandle();
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

            final Drawable handle =  context.getResources().getDrawable(
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
    }
}
