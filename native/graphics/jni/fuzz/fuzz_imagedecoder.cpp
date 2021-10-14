/*
 * Copyright 2020 The Android Open Source Project
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

#include <android/imagedecoder.h>

#include <binder/IPCThreadState.h>
#include <stddef.h>
#include <stdint.h>
#include <cstdlib>
#include <memory>

#ifdef PNG_MUTATOR_DEFINE_LIBFUZZER_CUSTOM_MUTATOR
#include <fuzz/png_mutator.h>
#endif

struct DecoderDeleter {
    void operator()(AImageDecoder* decoder) const { AImageDecoder_delete(decoder); }
};

using DecoderPointer = std::unique_ptr<AImageDecoder, DecoderDeleter>;

static DecoderPointer makeDecoder(const uint8_t* data, size_t size) {
    AImageDecoder* decoder = nullptr;
    int result = AImageDecoder_createFromBuffer(data, size, &decoder);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        // This was not a valid image.
        return nullptr;
    }
    return DecoderPointer(decoder);
}

struct PixelFreer {
    void operator()(void* pixels) const { std::free(pixels); }
};

using PixelPointer = std::unique_ptr<void, PixelFreer>;

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    // Without this call, decoding HEIF may time out on binder IPC calls.
    android::ProcessState::self()->startThreadPool();

    DecoderPointer decoder = makeDecoder(data, size);
    if (!decoder) {
        return 0;
    }

    const AImageDecoderHeaderInfo* info = AImageDecoder_getHeaderInfo(decoder.get());
    int32_t width = AImageDecoderHeaderInfo_getWidth(info);
    int32_t height = AImageDecoderHeaderInfo_getHeight(info);

    // Set an arbitrary limit on the size of an image. The fuzzer runs with a
    // limited amount of memory, and keeping this allocation small allows the
    // fuzzer to continue running to try to find more serious problems. This
    // size is large enough to hold a photo taken by a current gen phone.
    constexpr int32_t kMaxDimension = 5000;
    if (width > kMaxDimension || height > kMaxDimension) {
        return 0;
    }

    size_t stride = AImageDecoder_getMinimumStride(decoder.get());
    size_t pixelSize = height * stride;
    auto pixels = PixelPointer(std::malloc(pixelSize));
    if (!pixels.get()) {
        return 0;
    }

    while (true) {
        int result = AImageDecoder_decodeImage(decoder.get(), pixels.get(), stride, pixelSize);
        if (result != ANDROID_IMAGE_DECODER_SUCCESS) break;

        result = AImageDecoder_advanceFrame(decoder.get());
        if (result != ANDROID_IMAGE_DECODER_SUCCESS) break;
    }
    return 0;
}
