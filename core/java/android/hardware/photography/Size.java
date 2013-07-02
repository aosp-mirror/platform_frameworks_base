/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.hardware.photography;

/**
 * A simple immutable class for describing the dimensions of camera image
 * buffers.
 */
public final class Size {
    /**
     * Create a new immutable Size instance
     *
     * @param width The width to store in the Size instance
     * @param height The height to store in the Size instance
     */
    Size(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    public final int getWidth() {
        return mWidth;
    }

    public final int getHeight() {
        return mHeight;
    }

    private final int mWidth;
    private final int mHeight;
};
