/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist.ui;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Log;
import android.util.PathParser;

import com.android.systemui.R;

/**
 * Parses a path describing rounded corners from a string.
 */
public final class PathSpecCornerPathRenderer extends CornerPathRenderer {
    private static final String TAG = "PathSpecCornerPathRenderer";

    private final int mHeight;
    private final int mWidth;
    private final float mPathScale;
    private final int mBottomCornerRadius;
    private final int mTopCornerRadius;

    private final Path mPath = new Path();
    private final Path mRoundedPath;
    private final Matrix mMatrix = new Matrix();

    public PathSpecCornerPathRenderer(Context context) {
        mWidth = DisplayUtils.getWidth(context);
        mHeight = DisplayUtils.getHeight(context);

        mBottomCornerRadius =  DisplayUtils.getCornerRadiusBottom(context);
        mTopCornerRadius =  DisplayUtils.getCornerRadiusTop(context);

        String pathData = context.getResources().getString(R.string.config_rounded_mask);
        Path path = PathParser.createPathFromPathData(pathData);
        if (path == null) {
            Log.e(TAG, "No rounded corner path found!");
            mRoundedPath = new Path();
        } else {
            mRoundedPath = path;

        }

        RectF bounds = new RectF();
        mRoundedPath.computeBounds(bounds, true);

        // we use this to scale the path such that the larger of its [width, height] is scaled to
        // the corner radius (to account for asymmetric paths)
        mPathScale = Math.min(
                Math.abs(bounds.right - bounds.left),
                Math.abs(bounds.top - bounds.bottom));
    }

    /**
     * Scales and rotates each corner from the path specification to its correct position.
     *
     * Note: the rounded corners are passed in as the full shape (a curved triangle), but we only
     * want the actual corner curve. Therefore we call getSegment to jump past the horizontal and
     * vertical lines.
     */
    @Override
    public Path getCornerPath(Corner corner) {
        if (mRoundedPath.isEmpty()) {
            return mRoundedPath;
        }
        int cornerRadius;
        int rotateDegrees;
        int translateX;
        int translateY;
        switch (corner) {
            case TOP_LEFT:
                cornerRadius = mTopCornerRadius;
                rotateDegrees = 0;
                translateX = 0;
                translateY = 0;
                break;
            case TOP_RIGHT:
                cornerRadius = mTopCornerRadius;
                rotateDegrees = 90;
                translateX = mWidth;
                translateY = 0;
                break;
            case BOTTOM_RIGHT:
                cornerRadius = mBottomCornerRadius;
                rotateDegrees = 180;
                translateX = mWidth;
                translateY = mHeight;
                break;
            case BOTTOM_LEFT:
            default:
                cornerRadius = mBottomCornerRadius;
                rotateDegrees = 270;
                translateX = 0;
                translateY = mHeight;
                break;
        }
        mPath.reset();
        mMatrix.reset();
        mPath.addPath(mRoundedPath);

        mMatrix.preScale(cornerRadius / mPathScale, cornerRadius / mPathScale);
        mMatrix.postRotate(rotateDegrees);
        mMatrix.postTranslate(translateX, translateY);
        mPath.transform(mMatrix);
        return mPath;
    }
}
