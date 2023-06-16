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

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
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
import android.window.WindowContainerTransaction;

import com.android.internal.policy.ScreenDecorationsUtils;
import com.android.launcher3.icons.IconProvider;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopTasksController;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeAppControlsWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeFocusedWindowDecorationViewHolder;
import com.android.wm.shell.windowdecor.viewholder.DesktopModeWindowDecorationViewHolder;

import java.util.HashSet;
import java.util.Set;

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
    private DragPositioningCallback mDragPositioningCallback;
    private DragResizeInputListener mDragResizeListener;
    private DragDetector mDragDetector;

    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final WindowDecoration.RelayoutResult<WindowDecorLinearLayout> mResult =
            new WindowDecoration.RelayoutResult<>();

    private final Point mPositionInParent = new Point();
    private HandleMenu mHandleMenu;

    private ResizeVeil mResizeVeil;

    private Drawable mAppIcon;
    private CharSequence mAppName;

    private TaskCornersListener mCornersListener;

    private final Set<IBinder> mTransitionsPausingRelayout = new HashSet<>();
    private int mRelayoutBlock;

    DesktopModeWindowDecoration(
            Context context,
            DisplayController displayController,
            ShellTaskOrganizer taskOrganizer,
            ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl taskSurface,
            Handler handler,
            Choreographer choreographer,
            SyncTransactionQueue syncQueue) {
        super(context, displayController, taskOrganizer, taskInfo, taskSurface);

        mHandler = handler;
        mChoreographer = choreographer;
        mSyncQueue = syncQueue;

        loadAppInfo();
    }

    @Override
    protected Configuration getConfigurationWithOverrides(
            ActivityManager.RunningTaskInfo taskInfo) {
        Configuration configuration = taskInfo.getConfiguration();
        if (DesktopTasksController.isDesktopDensityOverrideSet()) {
            // Density is overridden for desktop tasks. Keep system density for window decoration.
            configuration.densityDpi = mContext.getResources().getConfiguration().densityDpi;
        }
        return configuration;
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setCornersListener(TaskCornersListener cornersListener) {
        mCornersListener = cornersListener;
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

        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
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
        mRelayoutParams.mCaptionHeightId = getCaptionHeightId();
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;
        mRelayoutParams.mApplyStartTransactionOnDraw = applyStartTransactionOnDraw;

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
                        mAppName,
                        mAppIcon
                );
            } else {
                throw new IllegalArgumentException("Unexpected layout resource id");
            }
        }
        mWindowDecorViewHolder.bindData(mTaskInfo);

        if (!mTaskInfo.isFocused) {
            closeHandleMenu();
        }

        if (!isDragResizeable) {
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
                    mDragPositioningCallback);
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        mDragDetector.setTouchSlop(touchSlop);

        final int resize_handle = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_handle);
        final int resize_corner = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_corner);

        // If either task geometry or position have changed, update this task's cornersListener
        if (mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop)
                || !mTaskInfo.positionInParent.equals(mPositionInParent)) {
            mCornersListener.onTaskCornersChanged(mTaskInfo.taskId, getGlobalCornersRegion());
        }
        mPositionInParent.set(mTaskInfo.positionInParent);
    }

    boolean isHandleMenuActive() {
        return mHandleMenu != null;
    }

    private void loadAppInfo() {
        String packageName = mTaskInfo.realActivity.getPackageName();
        PackageManager pm = mContext.getApplicationContext().getPackageManager();
        try {
            IconProvider provider = new IconProvider(mContext);
            mAppIcon = provider.getIcon(pm.getActivityInfo(mTaskInfo.baseActivity,
                    PackageManager.ComponentInfoFlags.of(0)));
            ApplicationInfo applicationInfo = pm.getApplicationInfo(packageName,
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
        mResizeVeil = new ResizeVeil(mContext, mAppIcon, mTaskInfo,
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
     * Create and display handle menu window
     */
    void createHandleMenu() {
        mHandleMenu = new HandleMenu.Builder(this)
                .setAppIcon(mAppIcon)
                .setAppName(mAppName)
                .setOnClickListener(mOnCaptionButtonClickListener)
                .setOnTouchListener(mOnCaptionTouchListener)
                .setLayoutId(mRelayoutParams.mLayoutResId)
                .setCaptionPosition(mRelayoutParams.mCaptionX, mRelayoutParams.mCaptionY)
                .setWindowingButtonsVisible(DesktopModeStatus.isProto2Enabled())
                .build();
        mHandleMenu.show();
    }

    /**
     * Close the handle menu window
     */
    void closeHandleMenu() {
        if (!isHandleMenuActive()) return;
        mHandleMenu.close();
        mHandleMenu = null;
    }

    @Override
    void releaseViews() {
        closeHandleMenu();
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
        final Point positionInParent = mTaskOrganizer.getRunningTaskInfo(mTaskInfo.taskId)
                .positionInParent;
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
        mCornersListener.onTaskCornersRemoved(mTaskInfo.taskId);
        disposeResizeVeil();
        super.close();
    }

    private int getDesktopModeWindowDecorLayoutId(int windowingMode) {
        if (DesktopModeStatus.isProto1Enabled()) {
            return R.layout.desktop_mode_app_controls_window_decor;
        }
        return windowingMode == WINDOWING_MODE_FREEFORM
                ? R.layout.desktop_mode_app_controls_window_decor
                : R.layout.desktop_mode_focused_window_decor;
    }

    /**
     * Create a new region out of the corner rects of this task.
     */
    Region getGlobalCornersRegion() {
        Region cornersRegion = mDragResizeListener.getCornersRegion();
        cornersRegion.translate(mPositionInParent.x, mPositionInParent.y);
        return cornersRegion;
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
    int getCaptionHeightId() {
        return R.dimen.freeform_decor_caption_height;
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

    static class Factory {

        DesktopModeWindowDecoration create(
                Context context,
                DisplayController displayController,
                ShellTaskOrganizer taskOrganizer,
                ActivityManager.RunningTaskInfo taskInfo,
                SurfaceControl taskSurface,
                Handler handler,
                Choreographer choreographer,
                SyncTransactionQueue syncQueue) {
            return new DesktopModeWindowDecoration(
                    context,
                    displayController,
                    taskOrganizer,
                    taskInfo,
                    taskSurface,
                    handler,
                    choreographer,
                    syncQueue);
        }
    }

    interface TaskCornersListener {
        /** Inform the implementing class of this task's change in corner resize handles */
        void onTaskCornersChanged(int taskId, Region corner);

        /** Inform the implementing class that this task no longer needs its corners tracked,
         * likely due to it closing. */
        void onTaskCornersRemoved(int taskId);
    }
}
