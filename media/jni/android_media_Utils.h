/*
 * Copyright 2011, The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_UTILS_H_
#define _ANDROID_MEDIA_UTILS_H_

#include <gui/CpuConsumer.h>

namespace android {

// -----------Utility functions used by ImageReader/Writer JNI-----------------

typedef CpuConsumer::LockedBuffer LockedImage;

bool usingRGBAToJpegOverride(int32_t imageFormat, int32_t containerFormat);

int32_t applyFormatOverrides(int32_t imageFormat, int32_t containerFormat);

uint32_t Image_getBlobSize(LockedImage* buffer, bool usingRGBAOverride);

bool isFormatOpaque(int format);

bool isPossiblyYUV(PixelFormat format);

bool isPossibly10BitYUV(PixelFormat format);

status_t getLockedImageInfo(LockedImage* buffer, int idx, int32_t containerFormat,
        uint8_t **base, uint32_t *size, int *pixelStride, int *rowStride);

status_t lockImageFromBuffer(sp<GraphicBuffer> buffer, uint32_t inUsage,
        const Rect& rect, int fenceFd, LockedImage* outputImage);

status_t lockImageFromBuffer(BufferItem* bufferItem, uint32_t inUsage,
        int fenceFd, LockedImage* outputImage);

int getBufferWidth(BufferItem *buffer);

int getBufferHeight(BufferItem *buffer);

// Must be in sync with AIDL CameraBlob : android.hardware.camera.device.CameraBlob
// HALs must NOT copy this definition.
// for details: http://b/229688810
typedef struct camera3_jpeg_blob_v2 {
  uint32_t jpeg_blob_id;
  uint32_t jpeg_size;
} camera3_jpeg_blobv2_t;

// Must be in sync with AIDL CameraBlob : android.hardware.camera.device.CameraBlobId
enum {
      CAMERA3_JPEG_BLOB_ID = 0x00FF,
      CAMERA3_JPEG_APP_SEGMENTS_BLOB_ID = 0x0100,
};

};  // namespace android

#endif //  _ANDROID_MEDIA_UTILS_H_
