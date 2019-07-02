/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents.views.grid;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.util.AttributeSet;

import com.android.systemui.R;
import com.android.systemui.recents.views.TaskViewThumbnail;

public class GridTaskViewThumbnail extends TaskViewThumbnail {

    private final Path mThumbnailOutline = new Path();
    private final Path mRestBackgroundOutline = new Path();
    // True if either this view's size or thumbnail scale has changed and mThumbnailOutline should
    // be updated.
    private boolean mUpdateThumbnailOutline = true;

    public GridTaskViewThumbnail(Context context) {
        this(context, null);
    }

    public GridTaskViewThumbnail(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GridTaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public GridTaskViewThumbnail(Context context, AttributeSet attrs, int defStyleAttr,
        int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mCornerRadius = getResources().getDimensionPixelSize(
                R.dimen.recents_grid_task_view_rounded_corners_radius);
    }

    /**
     * Called when the task view frame changes, allowing us to move the contents of the header
     * to match the frame changes.
     */
    public void onTaskViewSizeChanged(int width, int height) {
        mUpdateThumbnailOutline = true;
        super.onTaskViewSizeChanged(width, height);
    }

    /**
     * Updates the scale of the bitmap relative to this view.
     */
    public void updateThumbnailMatrix() {
        mUpdateThumbnailOutline = true;
        super.updateThumbnailMatrix();
    }

    private void updateThumbnailOutline() {
        final int titleHeight = getResources().getDimensionPixelSize(
            R.dimen.recents_grid_task_view_header_height);
        final int viewWidth = mTaskViewRect.width();
        final int viewHeight = mTaskViewRect.height() - titleHeight;
        final int thumbnailWidth = Math.min(viewWidth,
            (int) (mThumbnailRect.width() * mThumbnailScale));
        final int thumbnailHeight = Math.min(viewHeight,
            (int) (mThumbnailRect.height() * mThumbnailScale));

        if (mBitmapShader != null && thumbnailWidth > 0 && thumbnailHeight > 0) {
            // Draw the thumbnail, we only round the bottom corners:
            //
            // outerLeft                outerRight
            //    <----------------------->            mRestBackgroundOutline
            //    _________________________            (thumbnailWidth < viewWidth)
            //    |_______________________| outerTop     A ____ B
            //    |                       |    ↑           |  |
            //    |                       |    |           |  |
            //    |                       |    |           |  |
            //    |                       |    |           |  | C
            //    \_______________________/    ↓           |__/
            //  mCornerRadius             outerBottom    E    D
            //
            //  mRestBackgroundOutline (thumbnailHeight < viewHeight)
            //  A _________________________ B
            //    |                       | C
            //  F \_______________________/
            //    E                       D
            final int outerLeft = 0;
            final int outerTop = 0;
            final int outerRight = outerLeft + thumbnailWidth;
            final int outerBottom = outerTop + thumbnailHeight;
            createThumbnailPath(outerLeft, outerTop, outerRight, outerBottom, mThumbnailOutline);

            if (thumbnailWidth < viewWidth) {
                final int l = Math.max(0, outerRight - mCornerRadius);
                final int r = outerRight;
                final int t = outerTop;
                final int b = outerBottom;
                mRestBackgroundOutline.reset();
                mRestBackgroundOutline.moveTo(l, t); // A
                mRestBackgroundOutline.lineTo(r, t); // B
                mRestBackgroundOutline.lineTo(r, b - mCornerRadius); // C
                mRestBackgroundOutline.arcTo(r -  2 * mCornerRadius, b - 2 * mCornerRadius, r, b,
                        0, 90, false); // D
                mRestBackgroundOutline.lineTo(l, b); // E
                mRestBackgroundOutline.lineTo(l, t); // A
                mRestBackgroundOutline.close();

            }
            if (thumbnailHeight < viewHeight) {
                final int l = outerLeft;
                final int r = outerRight;
                final int t = Math.max(0, thumbnailHeight - mCornerRadius);
                final int b = outerBottom;
                mRestBackgroundOutline.reset();
                mRestBackgroundOutline.moveTo(l, t); // A
                mRestBackgroundOutline.lineTo(r, t); // B
                mRestBackgroundOutline.lineTo(r, b - mCornerRadius); // C
                mRestBackgroundOutline.arcTo(r -  2 * mCornerRadius, b - 2 * mCornerRadius, r, b,
                        0, 90, false); // D
                mRestBackgroundOutline.lineTo(l + mCornerRadius, b); // E
                mRestBackgroundOutline.arcTo(l, b - 2 * mCornerRadius, l + 2 * mCornerRadius, b,
                        90, 90, false); // F
                mRestBackgroundOutline.lineTo(l, t); // A
                mRestBackgroundOutline.close();
            }
        } else {
            createThumbnailPath(0, 0, viewWidth, viewHeight, mThumbnailOutline);
        }
    }

    private void createThumbnailPath(int outerLeft, int outerTop, int outerRight, int outerBottom,
            Path outPath) {
        outPath.reset();
        outPath.moveTo(outerLeft, outerTop);
        outPath.lineTo(outerRight, outerTop);
        outPath.lineTo(outerRight, outerBottom - mCornerRadius);
        outPath.arcTo(outerRight -  2 * mCornerRadius, outerBottom - 2 * mCornerRadius, outerRight,
                outerBottom, 0, 90, false);
        outPath.lineTo(outerLeft + mCornerRadius, outerBottom);
        outPath.arcTo(outerLeft, outerBottom - 2 * mCornerRadius, outerLeft + 2 * mCornerRadius,
                outerBottom, 90, 90, false);
        outPath.lineTo(outerLeft, outerTop);
        outPath.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int titleHeight = getResources().getDimensionPixelSize(
            R.dimen.recents_grid_task_view_header_height);
        final int viewWidth = mTaskViewRect.width();
        final int viewHeight = mTaskViewRect.height() - titleHeight;
        final int thumbnailWidth = Math.min(viewWidth,
            (int) (mThumbnailRect.width() * mThumbnailScale));
        final int thumbnailHeight = Math.min(viewHeight,
            (int) (mThumbnailRect.height() * mThumbnailScale));

        if (mUpdateThumbnailOutline) {
            updateThumbnailOutline();
            mUpdateThumbnailOutline = false;
        }

        if (mUserLocked) {
            canvas.drawPath(mThumbnailOutline, mLockedPaint);
        } else if (mBitmapShader != null && thumbnailWidth > 0 && thumbnailHeight > 0) {
            // Draw the background, there will be some small overdraw with the thumbnail
            if (thumbnailWidth < viewWidth) {
                // Portrait thumbnail on a landscape task view
                canvas.drawPath(mRestBackgroundOutline, mBgFillPaint);
            }
            if (thumbnailHeight < viewHeight) {
                // Landscape thumbnail on a portrait task view
                canvas.drawPath(mRestBackgroundOutline, mBgFillPaint);
            }
            canvas.drawPath(mThumbnailOutline, getDrawPaint());
        } else {
            canvas.drawPath(mThumbnailOutline, mBgFillPaint);
        }
    }
}
