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
import android.os.IBinder;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.window.WindowContainerTransaction;

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
import com.android.wm.shell.windowdecor.viewholder.DesktopModeAppControlsWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeFocusedWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeWindowDecorationViewHolder;

import java.util.HashSet;
import java.util.Set;
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

    private final Set<IBinder> mTransitionsPausingRelayout = new HashSet<>();
    private int mRelayoutBlock;
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
            View.OnLongClickListener onLongClickListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
        mOnCaptionLongClickListener = onLongClickListener;
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
        // TaskListener callbacks and shell transitions aren't synchronized, so starting a shell
        // transition can trigger an onTaskInfoChanged call that updates the task's SurfaceControl
        // and interferes with the transition animation that is playing at the same time.
        if (mRelayoutBlock > 0) {
            return;
        }

        final SurfaceControl.Transaction t = mSurfaceControlTransactionSupplier.get();
        // Use |applyStartTransactionOnDraw| so that the transaction (that applies task crop) is
        // synced with the buffer transaction (that draws the View). Both will be shown on screen
        // at the same, whereas applying them independently causes flickering. See b/270202228.
        relayout(taskInfo, t, t, true /* applyStartTransactionOnDraw */);
    }

    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT,
            boolean applyStartTransactionOnDraw) {
        final int shadowRadiusID = taskInfo.isFocused
                ? R.dimen.freeform_decor_shadow_focused_thickness
                : R.dimen.freeform_decor_shadow_unfocused_thickness;
        final boolean isFreeform =
                taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM;
        final boolean isDragResizeable = isFreeform && taskInfo.isResizeable;

        if (isHandleMenuActive()) {
            mHandleMenu.relayout(startT);
        }

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final int windowDecorLayoutId = getDesktopModeWindowDecorLayoutId(
                taskInfo.getWindowingMode());
        mRelayoutParams.reset();
        mRelayoutParams.mRunningTaskInfo = taskInfo;
        mRelayoutParams.mLayoutResId = windowDecorLayoutId;
        mRelayoutParams.mCaptionHeightId = getCaptionHeightId(taskInfo.getWindowingMode());
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;
        mRelayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;
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
                ? mContext.getResources().getConfiguration() // Use system context.
                : mTaskInfo.configuration); // Use task configuration.
        mRelayoutParams.mWindowDecorConfig = windowDecorConfig;

        mRelayoutParams.mCornerRadius =
                (int) ScreenDecorationsUtils.getWindowCornerRadius(mContext);
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
                        mAppName,
                        mAppIconBitmap
                );
            } else {
                throw new IllegalArgumentException("Unexpected layout resource id");
            }
        }
        mWindowDecorViewHolder.bindData(mTaskInfo);

        if (!mTaskInfo.isFocused) {
            closeHandleMenu();
            closeMaximizeMenu();
        }

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

    private void loadAppInfo() {
        String packageName = mTaskInfo.realActivity.getPackageName();
        PackageManager pm = mContext.getApplicationContext().getPackageManager();
        try {
            final IconProvider provider = new IconProvider(mContext);
            mAppIconDrawable = provider.getIcon(pm.getActivityInfo(mTaskInfo.baseActivity,
                    PackageManager.ComponentInfoFlags.of(0)));
            final Resources resources = mContext.getResources();
            final BaseIconFactory factory = new BaseIconFactory(mContext,
                    resources.getDisplayMetrics().densityDpi,
                    resources.getDimensionPixelSize(R.dimen.desktop_mode_caption_icon_radius));
            mAppIconBitmap = factory.createScaledBitmap(mAppIconDrawable, MODE_DEFAULT);
            final ApplicationInfo applicationInfo = pm.getApplicationInfo(packageName,
                    PackageManager.ApplicationInfoFlags.of(0));
            mAppName = pm.getApplicationLabel(applicationInfo);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found: " + packageName, e);
        }
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
     * Create and display maximize menu window
     */
    void createMaximizeMenu() {
        mMaximizeMenu = new MaximizeMenu(mSyncQueue, mRootTaskDisplayAreaOrganizer,
                mDisplayController, mTaskInfo, mOnCaptionButtonClickListener, mContext,
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
                .setCaptionPosition(mRelayoutParams.mCaptionX, mRelayoutParams.mCaptionY)
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

        final PointF inputPoint = offsetCaptionLocation(ev);
        if (!mMaximizeMenu.isValidMenuInput(inputPoint)) {
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
        result.offset(-mRelayoutParams.mCaptionX, -mRelayoutParams.mCaptionY);
        result.offset(-positionInParent.x, -positionInParent.y);
        return result;
    }

    /**
     * Determine if a passed MotionEvent is in a view in caption
     *
     * @param ev       the {@link MotionEvent} to check
     * @param layoutId the id of the view
     * @return {@code true} if event is inside the specified view, {@code false} if not
     */
    private boolean checkEventInCaptionView(MotionEvent ev, int layoutId) {
        if (mResult.mRootView == null) return false;
        final PointF inputPoint = offsetCaptionLocation(ev);
        final View view = mResult.mRootView.findViewById(layoutId);
        return view != null && pointInView(view, inputPoint.x, inputPoint.y);
    }

    boolean checkTouchEventInHandle(MotionEvent ev) {
        if (isHandleMenuActive()) return false;
        return checkEventInCaptionView(ev, R.id.caption_handle);
    }

    /**
     * Returns true if motion event is within the caption's root view's bounds.
     */
    boolean checkTouchEventInCaption(MotionEvent ev) {
        return checkEventInCaptionView(ev, getCaptionViewId());
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
            final View caption = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
            final View handle = caption.findViewById(R.id.caption_handle);
            clickIfPointInView(new PointF(ev.getX(), ev.getY()), handle);
        } else {
            mHandleMenu.checkClickEvent(ev);
            closeHandleMenuIfNeeded(ev);
        }
    }

    private boolean clickIfPointInView(PointF inputPoint, View v) {
        if (pointInView(v, inputPoint.x, inputPoint.y)) {
            mOnCaptionButtonClickListener.onClick(v);
            return true;
        }
        return false;
    }

    boolean pointInView(View v, float x, float y) {
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

    private int getDesktopModeWindowDecorLayoutId(@WindowingMode int windowingMode) {
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

    /**
     * If transition exists in mTransitionsPausingRelayout, remove the transition and decrement
     * mRelayoutBlock
     */
    void removeTransitionPausingRelayout(IBinder transition) {
        if (mTransitionsPausingRelayout.remove(transition)) {
            mRelayoutBlock--;
        }
    }

    @Override
    int getCaptionHeightId(@WindowingMode int windowingMode) {
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

    /**
     * Add transition to mTransitionsPausingRelayout
     */
    void addTransitionPausingRelayout(IBinder transition) {
        mTransitionsPausingRelayout.add(transition);
    }

    /**
     * If two transitions merge and the merged transition is in mTransitionsPausingRelayout,
     * remove the merged transition from the set and add the transition it was merged into.
     */
    public void mergeTransitionPausingRelayout(IBinder merged, IBinder playing) {
        if (mTransitionsPausingRelayout.remove(merged)) {
            mTransitionsPausingRelayout.add(playing);
        }
    }

    /**
     * Increase mRelayoutBlock, stopping relayout if mRelayoutBlock is now greater than 0.
     */
    public void incrementRelayoutBlock() {
        mRelayoutBlock++;
    }

    @Override
    public String toString() {
        return "{"
                + "mPositionInParent=" + mPositionInParent + ", "
                + "mRelayoutBlock=" + mRelayoutBlock + ", "
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
