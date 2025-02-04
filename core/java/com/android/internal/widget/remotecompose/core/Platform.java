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
package com.android.internal.widget.remotecompose.core;

import android.annotation.NonNull;
import android.annotation.Nullable;

/** Services that are needed to be provided by the platform during encoding. */
public interface Platform {

    /**
     * Converts a platform-specific image object into a platform-independent byte buffer
     *
     * @param image
     * @return
     */
    @Nullable
    byte[] imageToByteArray(@NonNull Object image);

    /**
     * Returns the width of a platform-specific image object
     *
     * @param image platform-specific image object
     * @return the width of the image in pixels
     */
    int getImageWidth(@NonNull Object image);

    /**
     * Returns the height of a platform-specific image object
     *
     * @param image platform-specific image object
     * @return the height of the image in pixels
     */
    int getImageHeight(@NonNull Object image);

    /**
     * Converts a platform-specific path object into a platform-independent float buffer
     *
     * @param path
     * @return
     */
    @Nullable
    float[] pathToFloatArray(@NonNull Object path);

    enum LogCategory {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        TODO,
    }

    /**
     * Log a message
     *
     * @param category
     * @param message
     */
    void log(LogCategory category, String message);

    /**
     * Represents a precomputed text layout, for complex text painting / measuring / layout. Allows
     * the implementation to return a cached / engine after a text measure to be used int the paint
     * pass.
     */
    interface ComputedTextLayout {
        /**
         * Horizontal dimension of this text layout
         *
         * @return
         */
        float getWidth();

        /**
         * Vertical dimension of this text layout
         *
         * @return
         */
        float getHeight();
    }

    Platform None =
            new Platform() {
                @Override
                public byte[] imageToByteArray(@NonNull Object image) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int getImageWidth(@NonNull Object image) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int getImageHeight(@NonNull Object image) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public float[] pathToFloatArray(@NonNull Object path) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void log(LogCategory category, String message) {}
            };
}
