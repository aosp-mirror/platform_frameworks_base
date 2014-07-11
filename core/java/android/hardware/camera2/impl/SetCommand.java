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
 * Setter interface for use with Command pattern metadata value setters.
 */
public interface SetCommand {

    /**
     * Set the value in the given metadata.
     *
     * @param metadata {@link CameraMetadataNative} to set value in.
     * @param value value to set.
     * @param <T> type of the value to set.
     */
    public <T> void setValue(/*inout*/CameraMetadataNative metadata,
                             T value);
}
