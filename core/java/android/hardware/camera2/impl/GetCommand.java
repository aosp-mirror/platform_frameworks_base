/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.hardware.camera2.impl;

/**
 * Getter interface for use with Command pattern metadata value getters.
 */
public interface GetCommand {

    /**
     * Get the value from the given {@link CameraMetadataNative} object.
     *
     * @param metadata the {@link CameraMetadataNative} object to get the value from.
     * @param key the {@link CameraMetadataNative.Key} to look up.
     * @param <T> the type of the value.
     * @return the value for a given {@link CameraMetadataNative.Key}.
     */
    public <T> T getValue(CameraMetadataNative metadata, CameraMetadataNative.Key<T> key);
}
