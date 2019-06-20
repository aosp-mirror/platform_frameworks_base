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

import android.graphics.Path;

/**
 * Describes paths for circular rounded device corners.
 */
public final class CircularCornerPathRenderer extends CornerPathRenderer {

    private final int mCornerRadiusBottom;
    private final int mCornerRadiusTop;
    private final int mHeight;
    private final int mWidth;
    private final Path mPath = new Path();

    public CircularCornerPathRenderer(int cornerRadiusBottom, int cornerRadiusTop,
            int width, int height) {
        mCornerRadiusBottom = cornerRadiusBottom;
        mCornerRadiusTop = cornerRadiusTop;
        mHeight = height;
        mWidth = width;
    }

    @Override // CornerPathRenderer
    public Path getCornerPath(Corner corner) {
        mPath.reset();
        switch (corner) {
            case BOTTOM_LEFT:
                mPath.moveTo(0, mHeight - mCornerRadiusBottom);
                mPath.arcTo(0, mHeight - mCornerRadiusBottom * 2, mCornerRadiusBottom * 2, mHeight,
                        180, -90, true);
                break;
            case BOTTOM_RIGHT:
                mPath.moveTo(mWidth - mCornerRadiusBottom, mHeight);
                mPath.arcTo(mWidth - mCornerRadiusBottom * 2, mHeight - mCornerRadiusBottom * 2,
                        mWidth, mHeight, 90, -90, true);
                break;
            case TOP_RIGHT:
                mPath.moveTo(mWidth, mCornerRadiusTop);
                mPath.arcTo(mWidth - mCornerRadiusTop * 2, 0, mWidth, mCornerRadiusTop * 2, 0, -90,
                        true);
                break;
            case TOP_LEFT:
                mPath.moveTo(mCornerRadiusTop, 0);
                mPath.arcTo(0, 0, mCornerRadiusTop * 2, mCornerRadiusTop * 2, 270, -90, true);
                break;
        }
        return mPath;
    }
}
