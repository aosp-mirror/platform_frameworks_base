/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.window.SurfaceSyncGroup;

import com.android.wm.shell.R;
import com.android.wm.shell.desktopmode.DesktopModeStatus;

/**
 * Handle menu opened when the appropriate button is clicked on.
 *
 * Displays up to 3 pills that show the following:
 * App Info: App name, app icon, and collapse button to close the menu.
 * Windowing Options(Proto 2 only): Buttons to change windowing modes.
 * Additional Options: Miscellaneous functions including screenshot and closing task.
 */
class HandleMenu {
    private static final String TAG = "HandleMenu";
    private final Context mContext;
    private final WindowDecoration mParentDecor;
    private WindowDecoration.AdditionalWindow mAppInfoPill;
    private WindowDecoration.AdditionalWindow mWindowingPill;
    private WindowDecoration.AdditionalWindow mMoreActionsPill;
    private final PointF mAppInfoPillPosition = new PointF();
    private final PointF mWindowingPillPosition = new PointF();
    private final PointF mMoreActionsPillPosition = new PointF();
    private final boolean mShouldShowWindowingPill;
    private final Drawable mAppIcon;
    private final CharSequence mAppName;
    private final View.OnClickListener mOnClickListener;
    private final View.OnTouchListener mOnTouchListener;
    private final RunningTaskInfo mTaskInfo;
    private final int mLayoutResId;
    private final int mCaptionX;
    private final int mCaptionY;
    private int mMarginMenuTop;
    private int mMarginMenuStart;
    private int mMarginMenuSpacing;
    private int mMenuWidth;
    private int mAppInfoPillHeight;
    private int mWindowingPillHeight;
    private int mMoreActionsPillHeight;
    private int mShadowRadius;
    private int mCornerRadius;


    HandleMenu(WindowDecoration parentDecor, int layoutResId, int captionX, int captionY,
            View.OnClickListener onClickListener, View.OnTouchListener onTouchListener,
            Drawable appIcon, CharSequence appName, boolean shouldShowWindowingPill) {
        mParentDecor = parentDecor;
        mContext = mParentDecor.mDecorWindowContext;
        mTaskInfo = mParentDecor.mTaskInfo;
        mLayoutResId = layoutResId;
        mCaptionX = captionX;
        mCaptionY = captionY;
        mOnClickListener = onClickListener;
        mOnTouchListener = onTouchListener;
        mAppIcon = appIcon;
        mAppName = appName;
        mShouldShowWindowingPill = shouldShowWindowingPill;
        loadHandleMenuDimensions();
        updateHandleMenuPillPositions();
    }

    void show() {
        final SurfaceSyncGroup ssg = new SurfaceSyncGroup(TAG);
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();

        createAppInfoPill(t, ssg);
        if (mShouldShowWindowingPill) {
            createWindowingPill(t, ssg);
        }
        createMoreActionsPill(t, ssg);
        ssg.addTransaction(t);
        ssg.markSyncReady();
        setupHandleMenu();
    }

    private void createAppInfoPill(SurfaceControl.Transaction t, SurfaceSyncGroup ssg) {
        final int x = (int) mAppInfoPillPosition.x;
        final int y = (int) mAppInfoPillPosition.y;
        mAppInfoPill = mParentDecor.addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_app_info_pill,
                "Menu's app info pill",
                t, ssg, x, y, mMenuWidth, mAppInfoPillHeight, mShadowRadius, mCornerRadius);
    }

    private void createWindowingPill(SurfaceControl.Transaction t, SurfaceSyncGroup ssg) {
        final int x = (int) mWindowingPillPosition.x;
        final int y = (int) mWindowingPillPosition.y;
        mWindowingPill = mParentDecor.addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_windowing_pill,
                "Menu's windowing pill",
                t, ssg, x, y, mMenuWidth, mWindowingPillHeight, mShadowRadius, mCornerRadius);
    }

    private void createMoreActionsPill(SurfaceControl.Transaction t, SurfaceSyncGroup ssg) {
        final int x = (int) mMoreActionsPillPosition.x;
        final int y = (int) mMoreActionsPillPosition.y;
        mMoreActionsPill = mParentDecor.addWindow(
                R.layout.desktop_mode_window_decor_handle_menu_more_actions_pill,
                "Menu's more actions pill",
                t, ssg, x, y, mMenuWidth, mMoreActionsPillHeight, mShadowRadius, mCornerRadius);
    }

    /**
     * Set up interactive elements and color of this handle menu
     */
    private void setupHandleMenu() {
        // App Info pill setup.
        final View appInfoPillView = mAppInfoPill.mWindowViewHost.getView();
        final ImageButton collapseBtn = appInfoPillView.findViewById(R.id.collapse_menu_button);
        final ImageView appIcon = appInfoPillView.findViewById(R.id.application_icon);
        final TextView appName = appInfoPillView.findViewById(R.id.application_name);
        collapseBtn.setOnClickListener(mOnClickListener);
        appInfoPillView.setOnTouchListener(mOnTouchListener);
        appIcon.setImageDrawable(mAppIcon);
        appName.setText(mAppName);

        // Windowing pill setup.
        if (mShouldShowWindowingPill) {
            final View windowingPillView = mWindowingPill.mWindowViewHost.getView();
            final ImageButton fullscreenBtn = windowingPillView.findViewById(
                    R.id.fullscreen_button);
            final ImageButton splitscreenBtn = windowingPillView.findViewById(
                    R.id.split_screen_button);
            final ImageButton floatingBtn = windowingPillView.findViewById(R.id.floating_button);
            final ImageButton desktopBtn = windowingPillView.findViewById(R.id.desktop_button);
            fullscreenBtn.setOnClickListener(mOnClickListener);
            splitscreenBtn.setOnClickListener(mOnClickListener);
            floatingBtn.setOnClickListener(mOnClickListener);
            desktopBtn.setOnClickListener(mOnClickListener);
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
        final View moreActionsPillView = mMoreActionsPill.mWindowViewHost.getView();
        final Button closeBtn = moreActionsPillView.findViewById(R.id.close_button);
        if (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM) {
            closeBtn.setVisibility(View.GONE);
        } else {
            closeBtn.setVisibility(View.VISIBLE);
            closeBtn.setOnClickListener(mOnClickListener);
        }
        final Button selectBtn = moreActionsPillView.findViewById(R.id.select_button);
        selectBtn.setOnClickListener(mOnClickListener);
    }

    /**
     * Updates the handle menu pills' position variables to reflect their next positions
     */
    private void updateHandleMenuPillPositions() {
        final int menuX, menuY;
        final int captionWidth = mTaskInfo.getConfiguration()
                .windowConfiguration.getBounds().width();
        if (mLayoutResId
                == R.layout.desktop_mode_app_controls_window_decor) {
            // Align the handle menu to the left of the caption.
            menuX = mCaptionX + mMarginMenuStart;
            menuY = mCaptionY + mMarginMenuTop;
        } else {
            // Position the handle menu at the center of the caption.
            menuX = mCaptionX + (captionWidth / 2) - (mMenuWidth / 2);
            menuY = mCaptionY + mMarginMenuStart;
        }

        // App Info pill setup.
        final int appInfoPillY = menuY;
        mAppInfoPillPosition.set(menuX, appInfoPillY);

        final int windowingPillY, moreActionsPillY;
        if (mShouldShowWindowingPill) {
            windowingPillY = appInfoPillY + mAppInfoPillHeight + mMarginMenuSpacing;
            mWindowingPillPosition.set(menuX, windowingPillY);
            moreActionsPillY = windowingPillY + mWindowingPillHeight + mMarginMenuSpacing;
            mMoreActionsPillPosition.set(menuX, moreActionsPillY);
        } else {
            // Just start after the end of the app info pill + margins.
            moreActionsPillY = appInfoPillY + mAppInfoPillHeight + mMarginMenuSpacing;
            mMoreActionsPillPosition.set(menuX, moreActionsPillY);
        }
    }

    /**
     * Update pill layout, in case task changes have caused positioning to change.
     */
    void relayout(SurfaceControl.Transaction t) {
        if (mAppInfoPill != null) {
            updateHandleMenuPillPositions();
            t.setPosition(mAppInfoPill.mWindowSurface,
                    mAppInfoPillPosition.x, mAppInfoPillPosition.y);
            // Only show windowing buttons in proto2. Proto1 uses a system-level mode only.
            final boolean shouldShowWindowingPill = DesktopModeStatus.isEnabled();
            if (shouldShowWindowingPill) {
                t.setPosition(mWindowingPill.mWindowSurface,
                        mWindowingPillPosition.x, mWindowingPillPosition.y);
            }
            t.setPosition(mMoreActionsPill.mWindowSurface,
                    mMoreActionsPillPosition.x, mMoreActionsPillPosition.y);
        }
    }

    /**
     * Check a passed MotionEvent if a click has occurred on any button on this caption
     * Note this should only be called when a regular onClick is not possible
     * (i.e. the button was clicked through status bar layer)
     *
     * @param ev the MotionEvent to compare against.
     */
    void checkClickEvent(MotionEvent ev) {
        final View appInfoPill = mAppInfoPill.mWindowViewHost.getView();
        final ImageButton collapse = appInfoPill.findViewById(R.id.collapse_menu_button);
        // Translate the input point from display coordinates to the same space as the collapse
        // button, meaning its parent (app info pill view).
        final PointF inputPoint = new PointF(ev.getX() - mAppInfoPillPosition.x,
                ev.getY() - mAppInfoPillPosition.y);
        if (pointInView(collapse, inputPoint.x, inputPoint.y)) {
            mOnClickListener.onClick(collapse);
        }
    }

    /**
     * A valid menu input is one of the following:
     * An input that happens in the menu views.
     * Any input before the views have been laid out.
     *
     * @param inputPoint the input to compare against.
     */
    boolean isValidMenuInput(PointF inputPoint) {
        if (!viewsLaidOut()) return true;
        final boolean pointInAppInfoPill = pointInView(
                mAppInfoPill.mWindowViewHost.getView(),
                inputPoint.x - mAppInfoPillPosition.x,
                inputPoint.y - mAppInfoPillPosition.y);
        boolean pointInWindowingPill = false;
        if (mWindowingPill != null) {
            pointInWindowingPill = pointInView(
                    mWindowingPill.mWindowViewHost.getView(),
                    inputPoint.x - mWindowingPillPosition.x,
                    inputPoint.y - mWindowingPillPosition.y);
        }
        final boolean pointInMoreActionsPill = pointInView(
                mMoreActionsPill.mWindowViewHost.getView(),
                inputPoint.x - mMoreActionsPillPosition.x,
                inputPoint.y - mMoreActionsPillPosition.y);

        return pointInAppInfoPill || pointInWindowingPill || pointInMoreActionsPill;
    }

    private boolean pointInView(View v, float x, float y) {
        return v != null && v.getLeft() <= x && v.getRight() >= x
                && v.getTop() <= y && v.getBottom() >= y;
    }

    /**
     * Check if the views for handle menu can be seen.
     */
    private boolean viewsLaidOut() {
        return mAppInfoPill.mWindowViewHost.getView().isLaidOut();
    }


    private void loadHandleMenuDimensions() {
        final Resources resources = mContext.getResources();
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
        mMoreActionsPillHeight = shouldShowCloseButton(resources);
        mShadowRadius = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_shadow_radius);
        mCornerRadius = loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_corner_radius);
    }

    private int loadDimensionPixelSize(Resources resources, int resourceId) {
        if (resourceId == Resources.ID_NULL) {
            return 0;
        }
        return resources.getDimensionPixelSize(resourceId);
    }

    private int shouldShowCloseButton(Resources resources) {
        return (mTaskInfo.getWindowingMode() == WINDOWING_MODE_FREEFORM)
                ? loadDimensionPixelSize(resources,
                R.dimen.desktop_mode_handle_menu_more_actions_pill_freeform_height)
                : loadDimensionPixelSize(resources,
                        R.dimen.desktop_mode_handle_menu_more_actions_pill_height);
    }

    void close() {
        mAppInfoPill.releaseView();
        mAppInfoPill = null;
        if (mWindowingPill != null) {
            mWindowingPill.releaseView();
            mWindowingPill = null;
        }
        mMoreActionsPill.releaseView();
        mMoreActionsPill = null;
    }

    static final class Builder {
        private final WindowDecoration mParent;
        private CharSequence mName;
        private Drawable mAppIcon;
        private View.OnClickListener mOnClickListener;
        private View.OnTouchListener mOnTouchListener;
        private int mLayoutId;
        private int mCaptionX;
        private int mCaptionY;
        private boolean mShowWindowingPill;


        Builder(@NonNull WindowDecoration parent) {
            mParent = parent;
        }

        Builder setAppName(@Nullable CharSequence name) {
            mName = name;
            return this;
        }

        Builder setAppIcon(@Nullable Drawable appIcon) {
            mAppIcon = appIcon;
            return this;
        }

        Builder setOnClickListener(@Nullable View.OnClickListener onClickListener) {
            mOnClickListener = onClickListener;
            return this;
        }

        Builder setOnTouchListener(@Nullable View.OnTouchListener onTouchListener) {
            mOnTouchListener = onTouchListener;
            return this;
        }

        Builder setLayoutId(int layoutId) {
            mLayoutId = layoutId;
            return this;
        }

        Builder setCaptionPosition(int captionX, int captionY) {
            mCaptionX = captionX;
            mCaptionY = captionY;
            return this;
        }

        Builder setWindowingButtonsVisible(boolean windowingButtonsVisible) {
            mShowWindowingPill = windowingButtonsVisible;
            return this;
        }

        HandleMenu build() {
            return new HandleMenu(mParent, mLayoutId, mCaptionX, mCaptionY, mOnClickListener,
                    mOnTouchListener, mAppIcon, mName, mShowWindowingPill);
        }
    }
}
