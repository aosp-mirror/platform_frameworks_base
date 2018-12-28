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

#include "src/piex_types.h"
#include "src/piex.h"

#include <android_runtime/AndroidRuntime.h>
#include <gui/CpuConsumer.h>
#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <utils/KeyedVector.h>
#include <utils/String8.h>
#include <SkStream.h>

namespace android {

class AssetStream : public piex::StreamInterface {
private:
    SkStream *mStream;
    size_t mPosition;

public:
    explicit AssetStream(SkStream* stream);
    ~AssetStream();

    // Reads 'length' amount of bytes from 'offset' to 'data'. The 'data' buffer
    // provided by the caller, guaranteed to be at least "length" bytes long.
    // On 'kOk' the 'data' pointer contains 'length' valid bytes beginning at
    // 'offset' bytes from the start of the stream.
    // Returns 'kFail' if 'offset' + 'length' exceeds the stream and does not
    // change the contents of 'data'.
    piex::Error GetData(
            const size_t offset, const size_t length, std::uint8_t* data) override;
};

class BufferedStream : public piex::StreamInterface {
private:
    SkStream *mStream;
    // Growable memory stream
    SkDynamicMemoryWStream mStreamBuffer;

    // Minimum size to read on filling the buffer.
    const size_t kMinSizeToRead = 8192;

public:
    explicit BufferedStream(SkStream* stream);
    ~BufferedStream();

    // Reads 'length' amount of bytes from 'offset' to 'data'. The 'data' buffer
    // provided by the caller, guaranteed to be at least "length" bytes long.
    // On 'kOk' the 'data' pointer contains 'length' valid bytes beginning at
    // 'offset' bytes from the start of the stream.
    // Returns 'kFail' if 'offset' + 'length' exceeds the stream and does not
    // change the contents of 'data'.
    piex::Error GetData(
            const size_t offset, const size_t length, std::uint8_t* data) override;
};

class FileStream : public piex::StreamInterface {
private:
    FILE *mFile;
    size_t mPosition;

public:
    explicit FileStream(const int fd);
    explicit FileStream(const String8 filename);
    ~FileStream();

    // Reads 'length' amount of bytes from 'offset' to 'data'. The 'data' buffer
    // provided by the caller, guaranteed to be at least "length" bytes long.
    // On 'kOk' the 'data' pointer contains 'length' valid bytes beginning at
    // 'offset' bytes from the start of the stream.
    // Returns 'kFail' if 'offset' + 'length' exceeds the stream and does not
    // change the contents of 'data'.
    piex::Error GetData(
            const size_t offset, const size_t length, std::uint8_t* data) override;
    bool exists() const;
};

// Reads EXIF metadata from a given raw image via piex.
// And returns true if the operation is successful; otherwise, false.
bool GetExifFromRawImage(
        piex::StreamInterface* stream, const String8& filename, piex::PreviewImageData& image_data);

// Returns true if the conversion is successful; otherwise, false.
bool ConvertKeyValueArraysToKeyedVector(
        JNIEnv *env, jobjectArray keys, jobjectArray values,
        KeyedVector<String8, String8>* vector);

struct AMessage;
status_t ConvertMessageToMap(
        JNIEnv *env, const sp<AMessage> &msg, jobject *map);

status_t ConvertKeyValueArraysToMessage(
        JNIEnv *env, jobjectArray keys, jobjectArray values,
        sp<AMessage> *msg);

// -----------Utility functions used by ImageReader/Writer JNI-----------------

typedef CpuConsumer::LockedBuffer LockedImage;

bool usingRGBAToJpegOverride(int32_t imageFormat, int32_t containerFormat);

int32_t applyFormatOverrides(int32_t imageFormat, int32_t containerFormat);

uint32_t Image_getBlobSize(LockedImage* buffer, bool usingRGBAOverride);

bool isFormatOpaque(int format);

bool isPossiblyYUV(PixelFormat format);

status_t getLockedImageInfo(LockedImage* buffer, int idx, int32_t containerFormat,
        uint8_t **base, uint32_t *size, int *pixelStride, int *rowStride);

status_t lockImageFromBuffer(sp<GraphicBuffer> buffer, uint32_t inUsage,
        const Rect& rect, int fenceFd, LockedImage* outputImage);

status_t lockImageFromBuffer(BufferItem* bufferItem, uint32_t inUsage,
        int fenceFd, LockedImage* outputImage);

int getBufferWidth(BufferItem *buffer);

int getBufferHeight(BufferItem *buffer);

};  // namespace android

#endif //  _ANDROID_MEDIA_UTILS_H_
