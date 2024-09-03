/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations.layout.measure;

/**
 * Basic data class representing a component size, used during layout computations.
 */
public class Size {
    float mWidth;
    float mHeight;
    public Size(float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    public void setWidth(float value) {
        mWidth = value;
    }

    public void setHeight(float value) {
        mHeight = value;
    }

    public float getWidth() {
        return mWidth;
    }

    public float getHeight() {
        return mHeight;
    }
}
