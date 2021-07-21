/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.hardware.camera2.extension;

import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.ParcelFileDescriptor;

/** @hide */
parcelable ParcelImage
{
    int format;
    int width;
    int height;
    int transform;
    int scalingMode;
    long timestamp;
    int planeCount;
    Rect crop;
    HardwareBuffer buffer;
    ParcelFileDescriptor fence;
}
