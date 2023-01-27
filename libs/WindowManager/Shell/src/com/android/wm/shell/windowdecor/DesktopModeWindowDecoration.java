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
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.VectorDrawable;
import android.os.Handler;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.desktopmode.DesktopModeStatus;

/**
 * Defines visuals and behaviors of a window decoration of a caption bar and shadows. It works with
 * {@link DesktopModeWindowDecorViewModel}. The caption bar contains a handle, back button, and
 * close button.
 *
 * The shadow's thickness is 20dp when the window is in focus and 5dp when the window isn't.
 */
public class DesktopModeWindowDecoration extends WindowDecoration<WindowDecorLinearLayout> {
    private final Handler mHandler;
    private final Choreographer mChoreographer;
    private final SyncTransactionQueue mSyncQueue;

    private View.OnClickListener mOnCaptionButtonClickListener;
    private View.OnTouchListener mOnCaptionTouchListener;
    private DragResizeCallback mDragResizeCallback;
    private DragResizeInputListener mDragResizeListener;
    private DragDetector mDragDetector;

    private RelayoutParams mRelayoutParams = new RelayoutParams();
    private final int mCaptionMenuWidthId = R.dimen.freeform_decor_caption_menu_width;
    private final WindowDecoration.RelayoutResult<WindowDecorLinearLayout> mResult =
            new WindowDecoration.RelayoutResult<>();

    private boolean mDesktopActive;
    private AdditionalWindow mHandleMenu;

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
        mDesktopActive = DesktopModeStatus.isActive(mContext);
    }

    void setCaptionListeners(
            View.OnClickListener onCaptionButtonClickListener,
            View.OnTouchListener onCaptionTouchListener) {
        mOnCaptionButtonClickListener = onCaptionButtonClickListener;
        mOnCaptionTouchListener = onCaptionTouchListener;
    }

    void setDragResizeCallback(DragResizeCallback dragResizeCallback) {
        mDragResizeCallback = dragResizeCallback;
    }

    void setDragDetector(DragDetector dragDetector) {
        mDragDetector = dragDetector;
        mDragDetector.setTouchSlop(ViewConfiguration.get(mContext).getScaledTouchSlop());
    }

    @Override
    void relayout(ActivityManager.RunningTaskInfo taskInfo) {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        relayout(taskInfo, t, t);
        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
    }

    void relayout(ActivityManager.RunningTaskInfo taskInfo,
            SurfaceControl.Transaction startT, SurfaceControl.Transaction finishT) {
        final int shadowRadiusID = taskInfo.isFocused
                ? R.dimen.freeform_decor_shadow_focused_thickness
                : R.dimen.freeform_decor_shadow_unfocused_thickness;
        final boolean isFreeform =
                taskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM;
        final boolean isDragResizeable = isFreeform && taskInfo.isResizeable;

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final int outsetLeftId = R.dimen.freeform_resize_handle;
        final int outsetTopId = R.dimen.freeform_resize_handle;
        final int outsetRightId = R.dimen.freeform_resize_handle;
        final int outsetBottomId = R.dimen.freeform_resize_handle;

        mRelayoutParams.reset();
        mRelayoutParams.mRunningTaskInfo = taskInfo;
        mRelayoutParams.mLayoutResId = R.layout.desktop_mode_window_decor;
        mRelayoutParams.mCaptionHeightId = R.dimen.freeform_decor_caption_height;
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;
        if (isDragResizeable) {
            mRelayoutParams.setOutsets(outsetLeftId, outsetTopId, outsetRightId, outsetBottomId);
        }

        relayout(mRelayoutParams, startT, finishT, wct, oldRootView, mResult);
        // After this line, mTaskInfo is up-to-date and should be used instead of taskInfo

        mTaskOrganizer.applyTransaction(wct);

        if (mResult.mRootView == null) {
            // This means something blocks the window decor from showing, e.g. the task is hidden.
            // Nothing is set up in this case including the decoration surface.
            return;
        }
        if (oldRootView != mResult.mRootView) {
            setupRootView();
        }

        // If this task is not focused, do not show caption.
        setCaptionVisibility(mTaskInfo.isFocused);

        if (mTaskInfo.isFocused) {
            if (DesktopModeStatus.isProto2Enabled()) {
                updateButtonVisibility();
            } else if (DesktopModeStatus.isProto1Enabled()) {
                // Only handle should show if Desktop Mode is inactive.
                boolean desktopCurrentStatus = DesktopModeStatus.isActive(mContext);
                if (mDesktopActive != desktopCurrentStatus) {
                    mDesktopActive = desktopCurrentStatus;
                    setButtonVisibility(mDesktopActive);
                }
            }
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
                    mDecorationContainerSurface,
                    mDragResizeCallback);
        }

        final int touchSlop = ViewConfiguration.get(mResult.mRootView.getContext())
                .getScaledTouchSlop();
        mDragDetector.setTouchSlop(touchSlop);

        final int resize_handle = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_handle);
        final int resize_corner = mResult.mRootView.getResources()
                .getDimensionPixelSize(R.dimen.freeform_resize_corner);
        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop);
    }

    /**
     * Sets up listeners when a new root view is created.
     */
    private void setupRootView() {
        final View caption = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
        caption.setOnTouchListener(mOnCaptionTouchListener);
        final View handle = caption.findViewById(R.id.caption_handle);
        handle.setOnTouchListener(mOnCaptionTouchListener);
        handle.setOnClickListener(mOnCaptionButtonClickListener);
        updateButtonVisibility();
    }

    private void setupHandleMenu() {
        final View menu = mHandleMenu.mWindowViewHost.getView();
        final View fullscreen = menu.findViewById(R.id.fullscreen_button);
        fullscreen.setOnClickListener(mOnCaptionButtonClickListener);
        final View desktop = menu.findViewById(R.id.desktop_button);
        desktop.setOnClickListener(mOnCaptionButtonClickListener);
        final View split = menu.findViewById(R.id.split_screen_button);
        split.setOnClickListener(mOnCaptionButtonClickListener);
    }

    /**
     * Sets caption visibility based on task focus.
     *
     * @param visible whether or not the caption should be visible
     */
    private void setCaptionVisibility(boolean visible) {
        final int v = visible ? View.VISIBLE : View.GONE;
        final View captionView = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
        captionView.setVisibility(v);
        if (!visible) closeHandleMenu();
    }

    /**
     * Sets the visibility of buttons and color of caption based on desktop mode status
     */
    void updateButtonVisibility() {
        if (DesktopModeStatus.isProto2Enabled()) {
            setButtonVisibility(mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM);
        } else if (DesktopModeStatus.isProto1Enabled()) {
            mDesktopActive = DesktopModeStatus.isActive(mContext);
            setButtonVisibility(mDesktopActive);
        }
    }

    /**
     * Show or hide buttons
     */
    void setButtonVisibility(boolean visible) {
        final int visibility = visible ? View.VISIBLE : View.GONE;
        final View caption = mResult.mRootView.findViewById(R.id.desktop_mode_caption);
        final int buttonTintColorRes =
                mDesktopActive ? R.color.decor_button_dark_color
                        : R.color.decor_button_light_color;
        final ColorStateList buttonTintColor =
                caption.getResources().getColorStateList(buttonTintColorRes, null /* theme */);
        final View handle = caption.findViewById(R.id.caption_handle);
        final VectorDrawable handleBackground = (VectorDrawable) handle.getBackground();
        handleBackground.setTintList(buttonTintColor);
        caption.getBackground().setTint(visible ? Color.WHITE : Color.TRANSPARENT);
    }

    boolean isHandleMenuActive() {
        return mHandleMenu != null;
    }

    private void closeDragResizeListener() {
        if (mDragResizeListener == null) {
            return;
        }
        mDragResizeListener.close();
        mDragResizeListener = null;
    }

    /**
     * Create and display handle menu window
     */
    void createHandleMenu() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        final Resources resources = mDecorWindowContext.getResources();
        final int captionWidth = mTaskInfo.getConfiguration()
                .windowConfiguration.getBounds().width();
        final int menuWidth = loadDimensionPixelSize(
                resources, mCaptionMenuWidthId);
        final int height = loadDimensionPixelSize(
                resources, mRelayoutParams.mCaptionHeightId);
        final int x = mRelayoutParams.mCaptionX + (captionWidth / 2) - (menuWidth / 2)
                - mResult.mDecorContainerOffsetX;
        final int y = mRelayoutParams.mCaptionY - mResult.mDecorContainerOffsetY;
        String namePrefix = "Caption Menu";
        mHandleMenu = addWindow(R.layout.desktop_mode_decor_handle_menu, namePrefix, t, x, y,
                menuWidth, height);
        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
        setupHandleMenu();
    }

    /**
     * Close the handle menu window
     */
    void closeHandleMenu() {
        if (!isHandleMenuActive()) return;
        mHandleMenu.releaseView();
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
        if (isHandleMenuActive()) {
            if (!checkEventInCaptionView(ev, R.id.desktop_mode_caption)) {
                closeHandleMenu();
            }
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
            final View menu = mHandleMenu.mWindowViewHost.getView();
            final int captionWidth = mTaskInfo.getConfiguration().windowConfiguration
                    .getBounds().width();
            final int menuX = mRelayoutParams.mCaptionX + (captionWidth / 2)
                    - (menu.getWidth() / 2);
            final PointF inputPoint = new PointF(ev.getX() - menuX, ev.getY());
            final View fullscreen = menu.findViewById(R.id.fullscreen_button);
            if (clickIfPointInView(inputPoint, fullscreen)) return;
            final View desktop = menu.findViewById(R.id.desktop_button);
            if (clickIfPointInView(inputPoint, desktop)) return;
            final View split = menu.findViewById(R.id.split_screen_button);
            if (clickIfPointInView(inputPoint, split)) return;
        }
    }

    private boolean clickIfPointInView(PointF inputPoint, View v) {
        if (pointInView(v, inputPoint.x, inputPoint.y)) {
            mOnCaptionButtonClickListener.onClick(v);
            return true;
        }
        return false;
    }

    private boolean pointInView(View v, float x, float y) {
        return v != null && v.getLeft() <= x && v.getRight() >= x
                && v.getTop() <= y && v.getBottom() >= y;
    }

    @Override
    public void close() {
        closeDragResizeListener();
        closeHandleMenu();
        super.close();
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
}
