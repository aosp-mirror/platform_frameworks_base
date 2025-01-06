/*
 * Copyright 2024 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.media.quality.PictureProfileHandle;

/**
 * A record of a visible layer that is using picture processing.
 * @hide
 */
public class SurfaceControlActivePicture {
    private final int mLayerId;
    private final int mOwnerUid;
    private final @NonNull PictureProfileHandle mPictureProfileHandle;

    /**
     * Create a record of a visible layer that is using picture processing.
     *
     * @param layerId the layer that is using picture processing
     * @param ownerUid the UID of the package that owns the layer
     * @param handle the handle for the picture profile that configured the processing
     */
    private SurfaceControlActivePicture(int layerId, int ownerUid, PictureProfileHandle handle) {
        mLayerId = layerId;
        mOwnerUid = ownerUid;
        mPictureProfileHandle = handle;
    }

    /** The layer that is using picture processing.  */
    public int getLayerId() {
        return mLayerId;
    }

    /** The UID of the package that owns the layer using picture processing. */
    public int getOwnerUid() {
        return mOwnerUid;
    }

    /** A handle that indicates which picture profile has configured the picture processing. */
    public @NonNull PictureProfileHandle getPictureProfileHandle() {
        return mPictureProfileHandle;
    }
}
