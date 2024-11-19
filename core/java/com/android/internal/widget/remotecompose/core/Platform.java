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
    @Nullable
    byte[] imageToByteArray(@NonNull Object image);

    int getImageWidth(@NonNull Object image);

    int getImageHeight(@NonNull Object image);

    @Nullable
    float[] pathToFloatArray(@NonNull Object path);

    enum LogCategory {
        DEBUG,
        INFO,
        WARN,
        ERROR,
        TODO,
    }

    void log(LogCategory category, String message);

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
