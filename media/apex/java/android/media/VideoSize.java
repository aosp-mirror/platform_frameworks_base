/*
 * Copyright 2018 The Android Open Source Project
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

package android.media;

/**
 * Immutable class for describing width and height dimensions.
 */
public final class VideoSize {
    /**
     * Create a new immutable VideoSize instance.
     *
     * @param width The width of the video size
     * @param height The height of the video size
     */
    VideoSize(int width, int height) {
        mWidth = width;
        mHeight = height;
    }

    /**
     * Get the width of the video size
     * @return width
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Get the height of the video size
     * @return height
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Check if this video size is equal to another video size.
     * <p>
     * Two video sizes are equal if and only if both their widths and heights are
     * equal.
     * </p>
     * <p>
     * A video size object is never equal to any other type of object.
     * </p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof VideoSize) {
            VideoSize other = (VideoSize) obj;
            return mWidth == other.mWidth && mHeight == other.mHeight;
        }
        return false;
    }

    /**
     * Return the video size represented as a string with the format {@code "WxH"}
     *
     * @return string representation of the video size
     */
    @Override
    public String toString() {
        return mWidth + "x" + mHeight;
    }

    private final int mWidth;
    private final int mHeight;
}
