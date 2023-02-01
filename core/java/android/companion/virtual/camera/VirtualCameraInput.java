/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.companion.virtual.camera;

import android.annotation.NonNull;
import android.hardware.camera2.params.InputConfiguration;

import java.io.InputStream;

/***
 *  Used for sending image data into virtual camera.
 *  <p>
 *  The system will call {@link  #openStream(InputConfiguration)} to signal when you
 *  should start sending Camera image data.
 *  When Camera is no longer needed, or there is change in configuration
 *  {@link #closeStream()} will be called. At that time finish sending current
 *  image data and then close the stream.
 *  <p>
 *  If Camera image data is needed again, {@link #openStream(InputConfiguration)} will be
 *  called by the system.
 *
 * @hide
 */
public interface VirtualCameraInput {

    /**
     * Opens a new image stream for the provided {@link InputConfiguration}.
     *
     * @param inputConfiguration image data configuration.
     * @return image data stream.
     */
    @NonNull
    InputStream openStream(@NonNull InputConfiguration inputConfiguration);

    /**
     * Stop sending image data and close {@link InputStream} provided in {@link
     * #openStream(InputConfiguration)}. Do nothing if there is currently no active stream.
     */
    void closeStream();
}
