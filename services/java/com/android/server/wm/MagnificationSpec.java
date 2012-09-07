/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.wm;

public class MagnificationSpec {
    public float mScale = 1.0f;
    public float mOffsetX;
    public float mOffsetY;

    public void initialize(float scale, float offsetX, float offsetY) {
        mScale = scale;
        mOffsetX = offsetX;
        mOffsetY = offsetY;
    }

    public boolean isNop() {
        return mScale == 1.0f && mOffsetX == 0 && mOffsetY == 0;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("<scale:");
        builder.append(mScale);
        builder.append(",offsetX:");
        builder.append(mOffsetX);
        builder.append(",offsetY:");
        builder.append(mOffsetY);
        builder.append(">");
        return builder.toString();
    }
}
