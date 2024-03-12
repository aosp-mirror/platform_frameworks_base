/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.windowdecor;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.windowingModeToString;

import static com.android.launcher3.icons.BaseIconFactory.MODE_DEFAULT;

import android.app.ActivityManager;
import android.app.WindowConfiguration.WindowingMode;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.launcher3.icons.BaseIconFactory;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.windowdecor.extension.TaskInfoKt;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeAppControlsWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeFocusedWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeWindowDecorationViewHolder;

import kotlin.Unit;

import java.util.function.Supplier;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link DesktopModeWindowDecorViewModel}.
 *
 * The shadow's thickness is 20dp when the window is in focus and 5dp when the window isn't.
 */
public class DesktopModeWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private static final String TAG = "DesktopModeWindowDecoration";

    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;

    private DesktopModeWindowDecorationViewHolder mWindowDecorViewHolder;
    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private View.OnLongClickListener mOnCaptionLongClickListener;
    private View.OnGenericMotionListener mOnCaptionGenericMotionListener;
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;
    private DragDetector mDragDetector;

    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final WindowDecoration.RelayoutResult<WindowDecorLinearLayout> mResult =
            new WindowDecoration.RelayoutResult<>();

    private final Point mPositionInParent = new Point();
    private HandleMenu mHandleMenu;

    private MaximizeMenu mMaximizeMenu;

    private ResizeVeil mResizeVeil;

    private Drawable mAppIconDrawable;
    private Bitmap mAppIconBitmap;
    private CharSequence mAppName;

    private ExclusionRegionListener mExclusionRegionListener;

    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;

    DesktopModeWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Configuration windowDecorConfig,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        this (context, displayController, taskOrganizer, taskInfo, taskSurface, windowDecorConfig,
                handler, choreographer, syncQueue, rootTaskDisplayAreaOrganizer,
                SurfaceControl.Builder::new, SurfaceControl.Transaction::new,
                WindowContainerTransaction::new, SurfaceControl::new,
                new SurfaceControlViewHostFactory() {});
    }

    DesktopModeWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Configuration windowDecorConfig,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue,
            RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer,
            Supplier<SurfaceControl.Builder> surfaceControlBuilderSupplier,
            Supplier<SurfaceControl.Transaction> surfaceControlTransactionSupplier,
            Supplier<WindowContainerTransaction> windowContainerTransactionSupplier,
            Supplier<SurfaceControl> surfaceControlSupplier,
            SurfaceControlViewHostFactory surfaceControlViewHostFactory) {
        super(context, displayController, taskOrganizer, taskInfo, taskSurface, windowDecorConfig,
                surfaceControlBuilderSupplier, surfaceControlTransactionSupplier,
                windowContainerTransactionSupplier, surfaceControlSupplier,
                surfaceControlViewHostFactory);

        mHandler = handler;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;

        loadAppInfo();
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener,
            View.OnLongClickListener onLongClickListener,
            View.OnGenericMotionListener onGenericMotionListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
        mOnCaptionLongClickListener = onLongClickListener;
        mOnCaptionGenericMotionListener = onGenericMotionListener;
    }

    void setExclusionRegionListener(ExclusionRegionListener exclusionRegionListener) {
        mExclusionRegionListener = exclusionRegionListener;
    }

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
    }

    void setDragDetector(DragDetector dragDetector) {
        mDragDetector = dragDetector;
        mDragDetector.setTouchSlop(ViewConfiguration.get(mContext).getScaledTouchSlop());
    }

    @Override
    void relayout(ActivityManager.RunningTaskInfo taskInfo) {
        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        // The crop and position of the task should only be set when a task is fluid resizing. In
        // all other cases, it is expected that the transition handler positions and crops the task
        // in order to allow the handler time to animate before the task before the final
        // position and crop are set.
        final boolean shouldSetTaskPositionAndCrop = !DesktopModeStatus.isVeiledResizeEnabled()
                && mTaskDragResizer.isResizingOrAnimating();
        // Use |applyStartTransactionOnDraw| so that the transaction (that applies task crop) is
        // synced with the buffer transaction (that draws the View). Both will be shown on screen
        // at the same, whereas applying them independently causes flickering. See b/270202228.
        relayout(taskInfo, t, t, true /* applyStartTransactionOnDraw */,
                shouldSetTaskPositionAndCrop);
    }

    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw, boolean shouldSetTaskPositionAndCrop) {
        if (isHandleMenuActive()) {
            mHandleMenu.relayout(startT);
        }

        updateRelayoutParams(mRelayoutParams, mContext, taskInfo, applyStartTransactionOnDraw,
                shouldSetTaskPositionAndCrop);

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            if (mRelayoutParams.mLayoutResId == R.layout.desktop_mode_focused_window_decor) {
                mWindowDecorViewHolder = new DesktopModeFocusedWindowDecorationViewHolder(
                        mResult.mRootView,
                        mOnCaptionTouchListener,
                        mOnCaptionButtonClickListener
                );
            } else if (mRelayoutParams.mLayoutResId
                    == R.layout.desktop_mode_app_controls_window_decor) {
                mWindowDecorViewHolder = new DesktopModeAppControlsWindowDecorationViewHolder(
                        mResult.mRootView,
                        mOnCaptionTouchListener,
                        mOnCaptionButtonClickListener,
                        mOnCaptionLongClickListener,
                        mOnCaptionGenericMotionListener,
                        mAppName,
                        mAppIconBitmap,
                        () -> {
                            if (!isMaximizeMenuActive()) {
                                createMaximizeMenu();
                            }
                            return Unit.INSTANCE;
                        });
            } else {
                throw new IllegalArgumentException("Unexpected layout resource id");
            }
        }
        mWindowDecorViewHolder.bindData(mTaskInfo);

        if (!mTaskInfo.isFocused) {
            closeHandleMenu();
            closeMaximizeMenu();
        }

        final boolean isFreeform =
                taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM;
        final boolean isDragResizeable = isFreeform && taskInfo.isResizeable;
        if (!isDragResizeable) {
            if (!mTaskInfo.positionInParent.equals(mPositionInParent)) {
                // We still want to track caption bar's exclusion region on a non-resizeable task.
                updateExclusionRegion();
            }
            closeDragResizeListener();
            return;
        }

        if (oldDecorationSurface != mDecorationContainerSurface || mDragResizeListener == null) {
            closeDragResizeListener();
            mDragResizeListener = new DragResizeInputListener(
                    mContext,
                    mHandler,
                    mChoreographer,
                    mDisplay.getDisplayId(),
                    mRelayoutParams.mCornerRadius,
                    mDecorationContainerSurface,
                    mDragPositioningCallback,
                    mSurfaceControlBuilderSupplier,
                    mSurfaceControlTransactionSupplier,
                    mDisplayController);
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        mDragDetector.setTouchSlop(touchSlop);

        final int resize_handle = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_handle);
        final int resize_corner = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_corner);

        // If either task geometry or position have changed, update this task's
        // exclusion region listener
        if (mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop)
                || !mTaskInfo.positionInParent.equals(mPositionInParent)) {
            updateExclusionRegion();
        }

        if (isMaximizeMenuActive()) {
            if (!mTaskInfo.isVisible()) {
                closeMaximizeMenu();
            } else {
                mMaximizeMenu.positionMenu(calculateMaximizeMenuPosition(), startT);
            }
        }
    }

    @VisibleForTesting
    static void updateRelayoutParams(
            RelayoutParams relayoutParams,
            Context context,
            ActivityManager.RunningTaskInfo taskInfo,
            boolean applyStartTransactionOnDraw,
            boolean shouldSetTaskPositionAndCrop) {
        final int captionLayoutId = getDesktopModeWindowDecorLayoutId(taskInfo.getWindowingMode());
        relayoutParams.reset();
        relayoutParams.mRunningTaskInfo = taskInfo;
        relayoutParams.mLayoutResId = captionLayoutId;
        relayoutParams.mCaptionHeightId = getCaptionHeightIdStatic(taskInfo.getWindowingMode());
        relayoutParams.mCaptionWidthId = getCaptionWidthId(relayoutParams.mLayoutResId);

        if (captionLayoutId == R.layout.desktop_mode_app_controls_window_decor) {
            // If the app is requesting to customize the caption bar, allow input to fall through
            // to the windows below so that the app can respond to input events on their custom
            // content.
            relayoutParams.mAllowCaptionInputFallthrough =
                    TaskInfoKt.isTransparentCaptionBarAppearance(taskInfo);
            // Report occluding elements as bounding rects to the insets system so that apps can
            // draw in the empty space in the center:
            //   First, the "app chip" section of the caption bar (+ some extra margins).
            final RelayoutParams.OccludingCaptionElement appChipElement =
                    new RelayoutParams.OccludingCaptionElement();
            appChipElement.mWidthResId = R.dimen.desktop_mode_customizable_caption_margin_start;
            appChipElement.mAlignment = RelayoutParams.OccludingCaptionElement.Alignment.START;
            relayoutParams.mOccludingCaptionElements.add(appChipElement);
            //   Then, the right-aligned section (drag space, maximize and close buttons).
            final RelayoutParams.OccludingCaptionElement controlsElement =
                    new RelayoutParams.OccludingCaptionElement();
            controlsElement.mWidthResId = R.dimen.desktop_mode_customizable_caption_margin_end;
            controlsElement.mAlignment = RelayoutParams.OccludingCaptionElement.Alignment.END;
            relayoutParams.mOccludingCaptionElements.add(controlsElement);
        }
        if (DesktopModeStatus.useWindowShadow(/* isFocusedWindow= */ taskInfo.isFocused)) {
            relayoutParams.mShadowRadiusId = taskInfo.isFocused
                    ? R.dimen.freeform_decor_shadow_focused_thickness
                    : R.dimen.freeform_decor_shadow_unfocused_thickness;
        }
        relayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
        relayoutParams.mSetTaskPositionAndCrop = shouldSetTaskPositionAndCrop;
        // The configuration used to lay out the window decoration. The system context's config is
        // used when the task density has been overridden to a custom density so that the resources
        // and views of the decoration aren't affected and match the rest of the System UI, if not
        // then just use the task's configuration. A copy is made instead of using the original
        // reference so that the configuration isn't mutated on config changes and diff checks can
        // be made in WindowDecoration#relayout using the pre/post-relayout configuration.
        // See b/301119301.
        // TODO(b/301119301): consider moving the config data needed for diffs to relayout params
        // instead of using a whole Configuration as a parameter.
        final Configuration windowDecorConfig = new Configuration();
        windowDecorConfig.setTo(DesktopTasksController.isDesktopDensityOverrideSet()
                ? context.getResources().getConfiguration() // Use system context.
                : taskInfo.configuration); // Use task configuration.
        relayoutParams.mWindowDecorConfig = windowDecorConfig;

        if (DesktopModeStatus.useRoundedCorners()) {
            relayoutParams.mCornerRadius =
                    (int) ScreenDecorationsUtils.getWindowCornerRadius(context);
        }
    }

    /**
     * If task has focused window decor, return the caption id of the fullscreen caption size
     * resource. Otherwise, return ID_NULL and caption width be set to task width.
     */
    private static int getCaptionWidthId(int layoutResId) {
        if (layoutResId == R.layout.desktop_mode_focused_window_decor) {
            return R.dimen.desktop_mode_fullscreen_decor_caption_width;
        }
        return Resources.ID_NULL;
    }


    private PointF calculateMaximizeMenuPosition() {
        final PointF position = new PointF();
        final Resources resources = mContext.getResources();
        final DisplayLayout displayLayout =
                mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        if (displayLayout == null) return position;

        final int displayWidth = displayLayout.width();
        final int displayHeight = displayLayout.height();
        final int captionHeight = getCaptionHeight(mTaskInfo.getWindowingMode());

        final ImageButton maximizeWindowButton =
                mResult.mRootView.findViewById(R.id.maximize_window);
        final int[] maximizeButtonLocation = new int[2];
        maximizeWindowButton.getLocationInWindow(maximizeButtonLocation);

        final int menuWidth = loadDimensionPixelSize(
                resources, R.dimen.desktop_mode_maximize_menu_width);
        final int menuHeight = loadDimensionPixelSize(
                resources, R.dimen.desktop_mode_maximize_menu_height);

        float menuLeft = (mPositionInParent.x + maximizeButtonLocation[0]);
        float menuTop = (mPositionInParent.y + captionHeight);
        final float menuRight = menuLeft + menuWidth;
        final float menuBottom = menuTop + menuHeight;

        // If the menu is out of screen bounds, shift it up/left as needed
        if (menuRight > displayWidth) {
            menuLeft = (displayWidth - menuWidth);
        }
        if (menuBottom > displayHeight) {
            menuTop = (displayHeight - menuHeight);
        }

        return new PointF(menuLeft, menuTop);
    }

    boolean isHandleMenuActive() {
        return mHandleMenu != null;
    }

    boolean isHandlingDragResize() {
        return mDragResizeListener != null && mDragResizeListener.isHandlingDragResize();
    }

    private void loadAppInfo() {
        PackageManager pm = mContext.getApplicationContext().getPackageManager();
        final IconProvider provider = new IconProvider(mContext);
        mAppIconDrawable = provider.getIcon(mTaskInfo.topActivityInfo);
        final Resources resources = mContext.getResources();
        final BaseIconFactory factory = new BaseIconFactory(mContext,
                resources.getDisplayMetrics().densityDpi,
                resources.getDimensionPixelSize(R.dimen.desktop_mode_caption_icon_radius));
        mAppIconBitmap = factory.createScaledBitmap(mAppIconDrawable, MODE_DEFAULT);
        final ApplicationInfo applicationInfo = mTaskInfo.topActivityInfo.applicationInfo;
        mAppName = pm.getApplicationLabel(applicationInfo);
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    /**
     * Create the resize veil for this task. Note the veil's visibility is View.GONE by default
     * until a resize event calls showResizeVeil below.
     */
    void createResizeVeil() {
        mResizeVeil = new ResizeVeil(mContext, mAppIconDrawable, mTaskInfo,
                mSurfaceControlBuilderSupplier, mDisplay, mSurfaceControlTransactionSupplier);
    }

    /**
     * Show the resize veil.
     */
    public void showResizeVeil(Rect taskBounds) {
        mResizeVeil.showVeil(mTaskSurface, taskBounds);
    }

    /**
     * Show the resize veil.
     */
    public void showResizeVeil(SurfaceControl.Transaction tx, Rect taskBounds) {
        mResizeVeil.showVeil(tx, mTaskSurface, taskBounds, false /* fadeIn */);
    }

    /**
     * Set new bounds for the resize veil
     */
    public void updateResizeVeil(Rect newBounds) {
        mResizeVeil.updateResizeVeil(newBounds);
    }

    /**
     * Set new bounds for the resize veil
     */
    public void updateResizeVeil(SurfaceControl.Transaction tx, Rect newBounds) {
        mResizeVeil.updateResizeVeil(tx, newBounds);
    }

    /**
     * Fade the resize veil out.
     */
    public void hideResizeVeil() {
        mResizeVeil.hideVeil();
    }

    private void disposeResizeVeil() {
        if (mResizeVeil == null) return;
        mResizeVeil.dispose();
        mResizeVeil = null;
    }

    /**
     * Determine valid drag area for this task based on elements in the app chip.
     */
    @Override
    Rect calculateValidDragArea() {
        final int appTextWidth = ((DesktopModeAppControlsWindowDecorationViewHolder)
                mWindowDecorViewHolder).getAppNameTextWidth();
        final int leftButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.desktop_mode_app_details_width_minus_text) + appTextWidth;
        final int requiredEmptySpace = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.freeform_required_visible_empty_space_in_header);
        final int rightButtonsWidth = loadDimensionPixelSize(mContext.getResources(),
                R.dimen.desktop_mode_right_edge_buttons_width);
        final int taskWidth = mTaskInfo.configuration.windowConfiguration.getBounds().width();
        final DisplayLayout layout = mDisplayController.getDisplayLayout(mTaskInfo.displayId);
        final int displayWidth = layout.width();
        final Rect stableBounds = new Rect();
        layout.getStableBounds(stableBounds);
        return new Rect(
                determineMinX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth),
                stableBounds.top,
                determineMaxX(leftButtonsWidth, rightButtonsWidth, requiredEmptySpace,
                        taskWidth, displayWidth),
                determineMaxY(requiredEmptySpace, stableBounds));
    }


    /**
     * Determine the lowest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMinX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth) {
        // Do not let apps with < 48dp empty header space go off the left edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return 0;
        }
        return -taskWidth + requiredEmptySpace + rightButtonsWidth;
    }

    /**
     * Determine the highest x coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxX(int leftButtonsWidth, int rightButtonsWidth, int requiredEmptySpace,
            int taskWidth, int displayWidth) {
        // Do not let apps with < 48dp empty header space go off the right edge at all.
        if (leftButtonsWidth + rightButtonsWidth + requiredEmptySpace > taskWidth) {
            return displayWidth - taskWidth;
        }
        return displayWidth - requiredEmptySpace - leftButtonsWidth;
    }

    /**
     * Determine the highest y coordinate of a freeform task. Used for restricting drag inputs.
     */
    private int determineMaxY(int requiredEmptySpace, Rect stableBounds) {
        return stableBounds.bottom - requiredEmptySpace;
    }


    /**
     * Create and display maximize menu window
     */
    void createMaximizeMenu() {
        mMaximizeMenu = new MaximizeMenu(mSyncQueue, mRootTaskDisplayAreaOrganizer,
                mDisplayController, mTaskInfo, mOnCaptionButtonClickListener,
                mOnCaptionGenericMotionListener, mOnCaptionTouchListener, mContext,
                calculateMaximizeMenuPosition(), mSurfaceControlTransactionSupplier);
        mMaximizeMenu.show();
    }

    /**
     * Close the maximize menu window
     */
    void closeMaximizeMenu() {
        if (!isMaximizeMenuActive()) return;
        mMaximizeMenu.close();
        mMaximizeMenu = null;
    }

    boolean isMaximizeMenuActive() {
        return mMaximizeMenu != null;
    }

    /**
     * Create and display handle menu window.
     */
    void createHandleMenu() {
        mHandleMenu = new HandleMenu.Builder(this)
                .setAppIcon(mAppIconBitmap)
                .setAppName(mAppName)
                .setOnClickListener(mOnCaptionButtonClickListener)
                .setOnTouchListener(mOnCaptionTouchListener)
                .setLayoutId(mRelayoutParams.mLayoutResId)
                .setWindowingButtonsVisible(DesktopModeStatus.isEnabled())
                .setCaptionHeight(mResult.mCaptionHeight)
                .build();
        mWindowDecorViewHolder.onHandleMenuOpened();
        mHandleMenu.show();
    }

    /**
     * Close the handle menu window.
     */
    void closeHandleMenu() {
        if (!isHandleMenuActive()) return;
        mWindowDecorViewHolder.onHandleMenuClosed();
        mHandleMenu.close();
        mHandleMenu = null;
    }

    @Override
    void releaseViews() {
        closeHandleMenu();
        closeMaximizeMenu();
        super.releaseViews();
    }

    /**
     * Close an open handle menu if input is outside of menu coordinates
     *
     * @param ev the tapped point to compare against
     */
    void closeHandleMenuIfNeeded(MotionEvent ev) {
        if (!isHandleMenuActive()) return;

        PointF inputPoint = offsetCaptionLocation(ev);

        // If this is called before open_menu_button's onClick, we don't want to close
        // the menu since it will just reopen in onClick.
        final boolean pointInOpenMenuButton = pointInView(
                mResult.mRootView.findViewById(R.id.open_menu_button),
                inputPoint.x,
                inputPoint.y);

        if (!mHandleMenu.isValidMenuInput(inputPoint) && !pointInOpenMenuButton) {
            closeHandleMenu();
        }
    }

    /**
     * Close an open maximize menu if input is outside of menu coordinates
     *
     * @param ev the tapped point to compare against
     */
    void closeMaximizeMenuIfNeeded(MotionEvent ev) {
        if (!isMaximizeMenuActive()) return;

        if (!mMaximizeMenu.isValidMenuInput(ev)) {
            closeMaximizeMenu();
        }
    }

    boolean isFocused() {
        return mTaskInfo.isFocused;
    }

    /**
     * Offset the coordinates of a {@link MotionEvent} to be in the same coordinate space as caption
     *
     * @param ev the {@link MotionEvent} to offset
     * @return the point of the input in local space
     */
    private PointF offsetCaptionLocation(MotionEvent ev) {
        final PointF result = new PointF(ev.getX(), ev.getY());
        final ActivityManager.RunningTaskInfo taskInfo =
                mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId);
        if (taskInfo == null) return result;
        final Point positionInParent = taskInfo.positionInParent;
        result.offset(-positionInParent.x, -positionInParent.y);
        return result;
    }

    /**
     * Checks if motion event occurs in the caption handle area of a focused caption (the caption on
     * a task in fullscreen or in multi-windowing mode). This should be used in cases where
     * onTouchListener will not work (i.e. when caption is in status bar area).
     *
     * @param ev       the {@link MotionEvent} to check
     * @return {@code true} if event is inside caption handle view, {@code false} if not
     */
    boolean checkTouchEventInFocusedCaptionHandle(MotionEvent ev) {
        if (isHandleMenuActive() || !(mWindowDecorViewHolder
                instanceof DesktopModeFocusedWindowDecorationViewHolder)) {
            return false;
        }

        return checkTouchEventInCaption(ev);
    }

    /**
     * Checks if touch event occurs in caption.
     *
     * @param ev       the {@link MotionEvent} to check
     * @return {@code true} if event is inside caption view, {@code false} if not
     */
    boolean checkTouchEventInCaption(MotionEvent ev) {
        final PointF inputPoint = offsetCaptionLocation(ev);
        return inputPoint.x >= mResult.mCaptionX
                && inputPoint.x <= mResult.mCaptionX + mResult.mCaptionWidth
                && inputPoint.y >= 0
                && inputPoint.y <= mResult.mCaptionHeight;
    }

    /**
     * Checks whether the touch event falls inside the customizable caption region.
     */
    boolean checkTouchEventInCustomizableRegion(MotionEvent ev) {
        return mResult.mCustomizableCaptionRegion.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /**
     * Check a passed MotionEvent if a click has occurred on any button on this caption
     * Note this should only be called when a regular onClick is not possible
     * (i.e. the button was clicked through status bar layer)
     *
     * @param ev the MotionEvent to compare
     */
    void checkClickEvent(MotionEvent ev) {
        if (mResult.mRootView == null) return;
        if (!isHandleMenuActive()) {
            // Click if point in caption handle view
            final View caption = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
            final View handle = caption.findViewById(R.id.caption_handle);
            if (checkTouchEventInFocusedCaptionHandle(ev)) {
                mOnCaptionButtonClickListener.onClick(handle);
            }
        } else {
            mHandleMenu.checkClickEvent(ev);
            closeHandleMenuIfNeeded(ev);
        }
    }

    private boolean pointInView(View v, float x, float y) {
        return v != null && v.getLeft() <= x && v.getRight() >= x
                && v.getTop() <= y && v.getBottom() >= y;
    }

    @Override
    public void close() {
        closeDragResizeListener();
        closeHandleMenu();
        mExclusionRegionListener.onExclusionRegionDismissed(mTaskInfo.taskId);
        disposeResizeVeil();
        super.close();
    }

    private static int getDesktopModeWindowDecorLayoutId(@WindowingMode int windowingMode) {
        return windowingMode == WINDOWING_MODE_FREEFORM
                ? R.layout.desktop_mode_app_controls_window_decor
                : R.layout.desktop_mode_focused_window_decor;
    }

    private void updatePositionInParent() {
        mPositionInParent.set(mTaskInfo.positionInParent);
    }

    private void updateExclusionRegion() {
        // An outdated position in parent is one reason for this to be called; update it here.
        updatePositionInParent();
        mExclusionRegionListener
                .onExclusionRegionChanged(mTaskInfo.taskId, getGlobalExclusionRegion());
    }

    /**
     * Create a new exclusion region from the corner rects (if resizeable) and caption bounds
     * of this task.
     */
    private Region getGlobalExclusionRegion() {
        Region exclusionRegion;
        if (mTaskInfo.isResizeable) {
            exclusionRegion = mDragResizeListener.getCornersRegion();
        } else {
            exclusionRegion = new Region();
        }
        exclusionRegion.union(new Rect(0, 0, mResult.mWidth,
                getCaptionHeight(mTaskInfo.getWindowingMode())));
        exclusionRegion.translate(mPositionInParent.x, mPositionInParent.y);
        return exclusionRegion;
    }

    @Override
    int getCaptionHeightId(@WindowingMode int windowingMode) {
        return getCaptionHeightIdStatic(windowingMode);
    }

    private static int getCaptionHeightIdStatic(@WindowingMode int windowingMode) {
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                ? R.dimen.desktop_mode_fullscreen_decor_caption_height
                : R.dimen.desktop_mode_freeform_decor_caption_height;
    }

    private int getCaptionHeight(@WindowingMode int windowingMode) {
        return loadDimensionPixelSize(mContext.getResources(), getCaptionHeightId(windowingMode));
    }

    @Override
    int getCaptionViewId() {
        return R.id.desktop_mode_caption;
    }

    void setAnimatingTaskResize(boolean animatingTaskResize) {
        if (mRelayoutParams.mLayoutResId == R.layout.desktop_mode_focused_window_decor) return;
        ((DesktopModeAppControlsWindowDecorationViewHolder) mWindowDecorViewHolder)
                .setAnimatingTaskResize(animatingTaskResize);
    }

    void onMaximizeWindowHoverExit() {
        ((DesktopModeAppControlsWindowDecorationViewHolder) mWindowDecorViewHolder)
                .onMaximizeWindowHoverExit();
    }

    void onMaximizeWindowHoverEnter() {
        ((DesktopModeAppControlsWindowDecorationViewHolder) mWindowDecorViewHolder)
                .onMaximizeWindowHoverEnter();
    }

    @Override
    public String toString() {
        return "{"
                + "mPositionInParent=" + mPositionInParent + ", "
                + "taskId=" + mTaskInfo.taskId + ", "
                + "windowingMode=" + windowingModeToString(mTaskInfo.getWindowingMode()) + ", "
                + "isFocused=" + isFocused()
                + "}";
    }

    static class Factory {

        DesktopModeWindowDecoration create(
                Context context,
                DisplayController displayController,
                ShellTaskOrganizer taskOrganizer,
                ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Handler handler,
                Choreographer choreographer,
                SyncTransactionQueue syncQueue,
                RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
            final Configuration windowDecorConfig =
                    DesktopTasksController.isDesktopDensityOverrideSet()
                    ? context.getResources().getConfiguration() // Use system context
                    : taskInfo.configuration; // Use task configuration
            return new DesktopModeWindowDecoration(
                    context,
                    displayController,
                    taskOrganizer,
                    taskInfo,
                    taskSurface,
                    windowDecorConfig,
                    handler,
                    choreographer,
                    syncQueue,
                    rootTaskDisplayAreaOrganizer);
        }
    }

    interface ExclusionRegionListener {
        /** Inform the implementing class of this task's change in region resize handles */
        void onExclusionRegionChanged(int taskId, Region region);

        /**
         * Inform the implementing class that this task no longer needs an exclusion region,
         * likely due to it closing.
         */
        void onExclusionRegionDismissed(int taskId);
    }
}
