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
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.util.Log;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.WindowContainerTransaction;

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

    private final PointF mHandleMenuAppInfoPillPosition = new PointF();
    private final PointF mHandleMenuWindowingPillPosition = new PointF();
    private final PointF mHandleMenuMoreActionsPillPosition = new PointF();

    // Collection of additional windows that comprise the handle menu.
    private AdditionalWindow mHandleMenuAppInfoPill;
    private AdditionalWindow mHandleMenuWindowingPill;
    private AdditionalWindow mHandleMenuMoreActionsPill;

    private Drawable mAppIcon;
    private CharSequence mAppName;

    private int mMenuWidth;
    private int mMarginMenuTop;
    private int mMarginMenuStart;
    private int mMarginMenuSpacing;
    private int mAppInfoPillHeight;
    private int mWindowingPillHeight;
    private int mMoreActionsPillHeight;
    private int mShadowRadius;
    private int mCornerRadius;

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
        loadHandleMenuDimensions();
    }

    private void loadHandleMenuDimensions() {
        final Resources resources = mDecorWindowContext.getResources();
        mMenuWidth = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_width);
        mMarginMenuTop = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_margin_top);
        mMarginMenuStart = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_margin_start);
        mMarginMenuSpacing = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_pill_spacing_margin);
        mAppInfoPillHeight = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_app_info_pill_height);
        mWindowingPillHeight = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_windowing_pill_height);
        mShadowRadius = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_shadow_radius);
        mCornerRadius = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_corner_radius);
        mMoreActionsPillHeight = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_more_actions_pill_height);
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

    void setDragPositioningCallback(DragPositioningCallback dragPositioningCallback) {
        mDragPositioningCallback = dragPositioningCallback;
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

        if (mHandleMenuAppInfoPill != null) {
            updateHandleMenuPillPositions();
            startT.setPosition(mHandleMenuAppInfoPill.mWindowSurface,
                    mHandleMenuAppInfoPillPosition.x, mHandleMenuAppInfoPillPosition.y);

            // Only show windowing buttons in proto2. Proto1 uses a system-level mode only.
            final boolean shouldShowWindowingPill = DesktopModeStatus.isProto2Enabled();
            if (shouldShowWindowingPill) {
                startT.setPosition(mHandleMenuWindowingPill.mWindowSurface,
                        mHandleMenuWindowingPillPosition.x, mHandleMenuWindowingPillPosition.y);
            }

            startT.setPosition(mHandleMenuMoreActionsPill.mWindowSurface,
                    mHandleMenuMoreActionsPillPosition.x, mHandleMenuMoreActionsPillPosition.y);
        }

        final WindowDecorLinearLayout oldRootView = mResult.mRootView;
        final SurfaceControl oldDecorationSurface = mDecorationContainerSurface;
        final WindowContainerTransaction wct = new WindowContainerTransaction();

        final int windowDecorLayoutId = getDesktopModeWindowDecorLayoutId(
                taskInfo.getWindowingMode());
        mRelayoutParams.reset();
        mRelayoutParams.mRunningTaskInfo = taskInfo;
        mRelayoutParams.mLayoutResId = windowDecorLayoutId;
        mRelayoutParams.mCaptionHeightId = R.dimen.freeform_decor_caption_height;
        mRelayoutParams.mShadowRadiusId = shadowRadiusID;

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
        mDragResizeListener.setGeometry(
                mResult.mWidth, mResult.mHeight, resize_handle, resize_corner, touchSlop);
    }

    boolean isHandleMenuActive() {
        return mHandleMenuAppInfoPill != null;
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
     * Create and display handle menu window
     */
    void createHandleMenu() {
        final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        updateHandleMenuPillPositions();

        createAppInfoPill(t);

        // Only show windowing buttons in proto2. Proto1 uses a system-level mode only.
        final boolean shouldShowWindowingPill = DesktopModeStatus.isProto2Enabled();
        if (shouldShowWindowingPill) {
            createWindowingPill(t);
        }

        createMoreActionsPill(t);

        mSyncQueue.runInSync(transaction -> {
            transaction.merge(t);
            t.close();
        });
        setupHandleMenu(shouldShowWindowingPill);
    }

    private void createAppInfoPill(SurfaceControl.Transaction t) {
        final int x = (int) mHandleMenuAppInfoPillPosition.x;
        final int y = (int) mHandleMenuAppInfoPillPosition.y;
        mHandleMenuAppInfoPill = addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_app_info_pill,
                "Menu's app info pill",
                t, x, y, mMenuWidth, mAppInfoPillHeight, mShadowRadius, mCornerRadius);
    }

    private void createWindowingPill(SurfaceControl.Transaction t) {
        final int x = (int) mHandleMenuWindowingPillPosition.x;
        final int y = (int) mHandleMenuWindowingPillPosition.y;
        mHandleMenuWindowingPill = addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_windowing_pill,
                "Menu's windowing pill",
                t, x, y, mMenuWidth, mWindowingPillHeight, mShadowRadius, mCornerRadius);
    }

    private void createMoreActionsPill(SurfaceControl.Transaction t) {
        final int x = (int) mHandleMenuMoreActionsPillPosition.x;
        final int y = (int) mHandleMenuMoreActionsPillPosition.y;
        mHandleMenuMoreActionsPill = addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_more_actions_pill,
                "Menu's more actions pill",
                t, x, y, mMenuWidth, mMoreActionsPillHeight, mShadowRadius, mCornerRadius);
    }

    private void setupHandleMenu(boolean windowingPillShown) {
        // App Info pill setup.
        final View appInfoPillView = mHandleMenuAppInfoPill.mWindowViewHost.getView();
        final ImageButton collapseBtn = appInfoPillView.findViewById(R.id.collapse_menu_button);
        final ImageView appIcon = appInfoPillView.findViewById(R.id.application_icon);
        final TextView appName = appInfoPillView.findViewById(R.id.application_name);
        collapseBtn.setOnClickListener(mOnCaptionButtonClickListener);
        appInfoPillView.setOnTouchListener(mOnCaptionTouchListener);
        appIcon.setImageDrawable(mAppIcon);
        appName.setText(mAppName);

        // Windowing pill setup.
        if (windowingPillShown) {
            final View windowingPillView = mHandleMenuWindowingPill.mWindowViewHost.getView();
            final ImageButton fullscreenBtn = windowingPillView.findViewById(
                    R.id.fullscreen_button);
            final ImageButton splitscreenBtn = windowingPillView.findViewById(
                    R.id.split_screen_button);
            final ImageButton floatingBtn = windowingPillView.findViewById(R.id.floating_button);
            final ImageButton desktopBtn = windowingPillView.findViewById(R.id.desktop_button);
            fullscreenBtn.setOnClickListener(mOnCaptionButtonClickListener);
            splitscreenBtn.setOnClickListener(mOnCaptionButtonClickListener);
            floatingBtn.setOnClickListener(mOnCaptionButtonClickListener);
            desktopBtn.setOnClickListener(mOnCaptionButtonClickListener);
            // The button corresponding to the windowing mode that the task is currently in uses a
            // different color than the others.
            final ColorStateList activeColorStateList = ColorStateList.valueOf(
                    mContext.getColor(R.color.desktop_mode_caption_menu_buttons_color_active));
            final ColorStateList inActiveColorStateList = ColorStateList.valueOf(
                    mContext.getColor(R.color.desktop_mode_caption_menu_buttons_color_inactive));
            fullscreenBtn.setImageTintList(
                    mTaskInfo.getWindowingMode() == WINDOWING_MODE_FULLSCREEN
                            ? activeColorStateList : inActiveColorStateList);
            splitscreenBtn.setImageTintList(
                    mTaskInfo.getWindowingMode() == WINDOWING_MODE_MULTI_WINDOW
                            ? activeColorStateList : inActiveColorStateList);
            floatingBtn.setImageTintList(mTaskInfo.getWindowingMode() == WINDOWING_MODE_PINNED
                    ? activeColorStateList : inActiveColorStateList);
            desktopBtn.setImageTintList(mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM
                    ? activeColorStateList : inActiveColorStateList);
        }

        // More Actions pill setup.
        final View moreActionsPillView = mHandleMenuMoreActionsPill.mWindowViewHost.getView();
        final Button closeBtn = moreActionsPillView.findViewById(R.id.close_button);
        closeBtn.setOnClickListener(mOnCaptionButtonClickListener);
    }

    /**
     * Updates the handle menu pills' position variables to reflect their next positions
     */
    private void updateHandleMenuPillPositions() {
        final int menuX, menuY;
        final int captionWidth = mTaskInfo.getConfiguration()
                .windowConfiguration.getBounds().width();
        if (mRelayoutParams.mLayoutResId
                == R.layout.desktop_mode_app_controls_window_decor) {
            // Align the handle menu to the left of the caption.
            menuX = mRelayoutParams.mCaptionX + mMarginMenuStart;
            menuY = mRelayoutParams.mCaptionY + mMarginMenuTop;
        } else {
            // Position the handle menu at the center of the caption.
            menuX = mRelayoutParams.mCaptionX + (captionWidth / 2) - (mMenuWidth / 2);
            menuY = mRelayoutParams.mCaptionY + mMarginMenuStart;
        }

        // App Info pill setup.
        final int appInfoPillY = menuY;
        mHandleMenuAppInfoPillPosition.set(menuX, appInfoPillY);

        // Only show windowing buttons in proto2. Proto1 uses a system-level mode only.
        final boolean shouldShowWindowingPill = DesktopModeStatus.isProto2Enabled();

        final int windowingPillY, moreActionsPillY;
        if (shouldShowWindowingPill) {
            windowingPillY = appInfoPillY + mAppInfoPillHeight + mMarginMenuSpacing;
            mHandleMenuWindowingPillPosition.set(menuX, windowingPillY);
            moreActionsPillY = windowingPillY + mWindowingPillHeight + mMarginMenuSpacing;
            mHandleMenuMoreActionsPillPosition.set(menuX, moreActionsPillY);
        } else {
            // Just start after the end of the app info pill + margins.
            moreActionsPillY = appInfoPillY + mAppInfoPillHeight + mMarginMenuSpacing;
            mHandleMenuMoreActionsPillPosition.set(menuX, moreActionsPillY);
        }
    }

    /**
     * Close the handle menu window
     */
    void closeHandleMenu() {
        if (!isHandleMenuActive()) return;
        mHandleMenuAppInfoPill.releaseView();
        mHandleMenuAppInfoPill = null;
        if (mHandleMenuWindowingPill != null) {
            mHandleMenuWindowingPill.releaseView();
            mHandleMenuWindowingPill = null;
        }
        mHandleMenuMoreActionsPill.releaseView();
        mHandleMenuMoreActionsPill = null;
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

        // When this is called before the layout is fully inflated, width will be 0.
        // Menu is not visible in this scenario, so skip the check if that is the case.
        if (mHandleMenuAppInfoPill.mWindowViewHost.getView().getWidth() == 0) return;

        PointF inputPoint = offsetCaptionLocation(ev);

        // If this is called before open_menu_button's onClick, we don't want to close
        // the menu since it will just reopen in onClick.
        final boolean pointInOpenMenuButton = pointInView(
                mResult.mRootView.findViewById(R.id.open_menu_button),
                inputPoint.x,
                inputPoint.y);

        final boolean pointInAppInfoPill = pointInView(
                mHandleMenuAppInfoPill.mWindowViewHost.getView(),
                inputPoint.x - mHandleMenuAppInfoPillPosition.x,
                inputPoint.y - mHandleMenuAppInfoPillPosition.y);
        boolean pointInWindowingPill = false;
        if (mHandleMenuWindowingPill != null) {
            pointInWindowingPill = pointInView(mHandleMenuWindowingPill.mWindowViewHost.getView(),
                    inputPoint.x - mHandleMenuWindowingPillPosition.x,
                    inputPoint.y - mHandleMenuWindowingPillPosition.y);
        }
        final boolean pointInMoreActionsPill = pointInView(
                mHandleMenuMoreActionsPill.mWindowViewHost.getView(),
                inputPoint.x - mHandleMenuMoreActionsPillPosition.x,
                inputPoint.y - mHandleMenuMoreActionsPillPosition.y);
        if (!pointInAppInfoPill && !pointInWindowingPill
                && !pointInMoreActionsPill && !pointInOpenMenuButton) {
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
            final View appInfoPill = mHandleMenuAppInfoPill.mWindowViewHost.getView();
            final ImageButton collapse = appInfoPill.findViewById(R.id.collapse_menu_button);
            // Translate the input point from display coordinates to the same space as the collapse
            // button, meaning its parent (app info pill view).
            final PointF inputPoint = new PointF(ev.getX() - mHandleMenuAppInfoPillPosition.x,
                    ev.getY() - mHandleMenuAppInfoPillPosition.y);
            clickIfPointInView(inputPoint, collapse);
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

    private int getDesktopModeWindowDecorLayoutId(int windowingMode) {
        if (DesktopModeStatus.isProto1Enabled()) {
            return R.layout.desktop_mode_app_controls_window_decor;
        }
        return windowingMode == WINDOWING_MODE_FREEFORM
                ? R.layout.desktop_mode_app_controls_window_decor
                : R.layout.desktop_mode_focused_window_decor;
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
