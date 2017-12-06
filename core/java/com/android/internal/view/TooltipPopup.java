/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.view;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.TextView;

public class TooltipPopup {
    private static final String TAG = "TooltipPopup";

    private final Context mContext;

    private final View mContentView;
    private final TextView mMessageView;

    private final WindowManager.LayoutParams mLayoutParams = new WindowManager.LayoutParams();
    private final Rect mTmpDisplayFrame = new Rect();
    private final int[] mTmpAnchorPos = new int[2];
    private final int[] mTmpAppPos = new int[2];

    public TooltipPopup(Context context) {
        mContext = context;

        mContentView = LayoutInflater.from(mContext).inflate(
                com.android.internal.R.layout.tooltip, null);
        mMessageView = (TextView) mContentView.findViewById(
                com.android.internal.R.id.message);

        mLayoutParams.setTitle(
                mContext.getString(com.android.internal.R.string.tooltip_popup_title));
        mLayoutParams.packageName = mContext.getOpPackageName();
        mLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
        mLayoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
        mLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT;
        mLayoutParams.format = PixelFormat.TRANSLUCENT;
        mLayoutParams.windowAnimations = com.android.internal.R.style.Animation_Tooltip;
        mLayoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
    }

    public void show(View anchorView, int anchorX, int anchorY, boolean fromTouch,
            CharSequence tooltipText) {
        if (isShowing()) {
            hide();
        }

        mMessageView.setText(tooltipText);

        computePosition(anchorView, anchorX, anchorY, fromTouch, mLayoutParams);

        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.addView(mContentView, mLayoutParams);
    }

    public void hide() {
        if (!isShowing()) {
            return;
        }

        WindowManager wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        wm.removeView(mContentView);
    }

    public View getContentView() {
        return mContentView;
    }

    public boolean isShowing() {
        return mContentView.getParent() != null;
    }

    private void computePosition(View anchorView, int anchorX, int anchorY, boolean fromTouch,
            WindowManager.LayoutParams outParams) {
        outParams.token = anchorView.getApplicationWindowToken();

        final int tooltipPreciseAnchorThreshold = mContext.getResources().getDimensionPixelOffset(
                com.android.internal.R.dimen.tooltip_precise_anchor_threshold);

        final int offsetX;
        if (anchorView.getWidth() >= tooltipPreciseAnchorThreshold) {
            // Wide view. Align the tooltip horizontally to the precise X position.
            offsetX = anchorX;
        } else {
            // Otherwise anchor the tooltip to the view center.
            offsetX = anchorView.getWidth() / 2;  // Center on the view horizontally.
        }

        final int offsetBelow;
        final int offsetAbove;
        if (anchorView.getHeight() >= tooltipPreciseAnchorThreshold) {
            // Tall view. Align the tooltip vertically to the precise Y position.
            final int offsetExtra = mContext.getResources().getDimensionPixelOffset(
                    com.android.internal.R.dimen.tooltip_precise_anchor_extra_offset);
            offsetBelow = anchorY + offsetExtra;
            offsetAbove = anchorY - offsetExtra;
        } else {
            // Otherwise anchor the tooltip to the view center.
            offsetBelow = anchorView.getHeight();  // Place below the view in most cases.
            offsetAbove = 0;  // Place above the view if the tooltip does not fit below.
        }

        outParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.TOP;

        final int tooltipOffset = mContext.getResources().getDimensionPixelOffset(
                fromTouch ? com.android.internal.R.dimen.tooltip_y_offset_touch
                        : com.android.internal.R.dimen.tooltip_y_offset_non_touch);

        // Find the main app window. The popup window will be positioned relative to it.
        final View appView = WindowManagerGlobal.getInstance().getWindowView(
                anchorView.getApplicationWindowToken());
        if (appView == null) {
            Slog.e(TAG, "Cannot find app view");
            return;
        }
        appView.getWindowVisibleDisplayFrame(mTmpDisplayFrame);
        appView.getLocationOnScreen(mTmpAppPos);

        anchorView.getLocationOnScreen(mTmpAnchorPos);
        mTmpAnchorPos[0] -= mTmpAppPos[0];
        mTmpAnchorPos[1] -= mTmpAppPos[1];
        // mTmpAnchorPos is now relative to the main app window.

        outParams.x = mTmpAnchorPos[0] + offsetX - mTmpDisplayFrame.width() / 2;

        final int spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        mContentView.measure(spec, spec);
        final int tooltipHeight = mContentView.getMeasuredHeight();

        final int yAbove = mTmpAnchorPos[1] + offsetAbove - tooltipOffset - tooltipHeight;
        final int yBelow = mTmpAnchorPos[1] + offsetBelow + tooltipOffset;
        if (fromTouch) {
            if (yAbove >= 0) {
                outParams.y = yAbove;
            } else {
                outParams.y = yBelow;
            }
        } else {
            if (yBelow + tooltipHeight <= mTmpDisplayFrame.height()) {
                outParams.y = yBelow;
            } else {
                outParams.y = yAbove;
            }
        }
    }
}
