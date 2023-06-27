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

package android.companion.virtual.camera;

import android.graphics.ImageFormat;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Wraps the client side {@link VirtualCameraSession} into an {@link IVirtualCameraSession}.
 *
 * @hide
 */
final class VirtualCameraSessionInternal extends IVirtualCameraSession.Stub {

    @SuppressWarnings("FieldCanBeLocal")
    // TODO: b/289881985: Will be used once connected with the CameraService
    private final VirtualCameraSession mVirtualCameraSession;

    VirtualCameraSessionInternal(@NonNull VirtualCameraSession virtualCameraSession) {
        mVirtualCameraSession = Objects.requireNonNull(virtualCameraSession);
    }

    @Override
    public void configureStream(int width, int height, @ImageFormat.Format int format) {}

    public void close() {}
}
