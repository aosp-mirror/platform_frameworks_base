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
#include <fuzzer/FuzzedDataProvider.h>

#ifdef PNG_MUTATOR_DEFINE_LIBFUZZER_CUSTOM_MUTATOR
#include <fuzz/png_mutator.h>
#endif

constexpr int32_t kMaxDimension = 5000;
constexpr int32_t kMinDimension = 0;

struct PixelFreer {
    void operator()(void* pixels) const { std::free(pixels); }
};

using PixelPointer = std::unique_ptr<void, PixelFreer>;

AImageDecoder* init(const uint8_t* data, size_t size, bool useFileDescriptor) {
    AImageDecoder* decoder = nullptr;
    if (useFileDescriptor) {
        constexpr char testFd[] = "tempFd";
        int32_t fileDesc = open(testFd, O_RDWR | O_CREAT | O_TRUNC);
        write(fileDesc, data, size);
        AImageDecoder_createFromFd(fileDesc, &decoder);
        close(fileDesc);
    } else {
        AImageDecoder_createFromBuffer(data, size, &decoder);
    }
    return decoder;
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t* data, size_t size) {
    FuzzedDataProvider dataProvider = FuzzedDataProvider(data, size);
    /**
     * Use maximum of 80% of buffer for creating decoder and save at least
     * 20% buffer for fuzzing other APIs
     */
    const int32_t dataSize = dataProvider.ConsumeIntegralInRange<int32_t>(0, (size * 80) / 100);
    std::vector<uint8_t> inputBuffer = dataProvider.ConsumeBytes<uint8_t>(dataSize);
    AImageDecoder* decoder =
            init(inputBuffer.data(), inputBuffer.size(), dataProvider.ConsumeBool());
    if (!decoder) {
        return 0;
    }
    const AImageDecoderHeaderInfo* headerInfo = AImageDecoder_getHeaderInfo(decoder);
    AImageDecoderFrameInfo* frameInfo = AImageDecoderFrameInfo_create();
    int32_t height = AImageDecoderHeaderInfo_getHeight(headerInfo);
    int32_t width = AImageDecoderHeaderInfo_getWidth(headerInfo);
    while (dataProvider.remaining_bytes()) {
        auto invokeImageApi = dataProvider.PickValueInArray<const std::function<void()>>({
                [&]() {
                    int32_t testHeight =
                            dataProvider.ConsumeIntegralInRange<int32_t>(kMinDimension,
                                                                         kMaxDimension);
                    int32_t testWidth = dataProvider.ConsumeIntegralInRange<int32_t>(kMinDimension,
                                                                                     kMaxDimension);
                    int32_t result = AImageDecoder_setTargetSize(decoder, testHeight, testWidth);
                    if (result == ANDROID_IMAGE_DECODER_SUCCESS) {
                        height = testHeight;
                        width = testWidth;
                    }
                },
                [&]() {
                    const bool required = dataProvider.ConsumeBool();
                    AImageDecoder_setUnpremultipliedRequired(decoder, required);
                },
                [&]() {
                    AImageDecoder_setAndroidBitmapFormat(
                            decoder,
                            dataProvider.ConsumeIntegralInRange<
                                    int32_t>(ANDROID_BITMAP_FORMAT_NONE,
                                             ANDROID_BITMAP_FORMAT_RGBA_1010102) /* format */);
                },
                [&]() {
                    AImageDecoder_setDataSpace(decoder,
                                               dataProvider
                                                       .ConsumeIntegral<int32_t>() /* dataspace */);
                },
                [&]() {
                    ARect rect{dataProvider.ConsumeIntegral<int32_t>() /* left */,
                               dataProvider.ConsumeIntegral<int32_t>() /* top */,
                               dataProvider.ConsumeIntegral<int32_t>() /* right */,
                               dataProvider.ConsumeIntegral<int32_t>() /* bottom */};
                    AImageDecoder_setCrop(decoder, rect);
                },
                [&]() { AImageDecoderHeaderInfo_getWidth(headerInfo); },
                [&]() { AImageDecoderHeaderInfo_getMimeType(headerInfo); },
                [&]() { AImageDecoderHeaderInfo_getAlphaFlags(headerInfo); },
                [&]() { AImageDecoderHeaderInfo_getAndroidBitmapFormat(headerInfo); },
                [&]() {
                    int32_t tempHeight;
                    int32_t tempWidth;
                    AImageDecoder_computeSampledSize(decoder,
                                                     dataProvider.ConsumeIntegral<
                                                             int>() /* sampleSize */,
                                                     &tempWidth, &tempHeight);
                },
                [&]() { AImageDecoderHeaderInfo_getDataSpace(headerInfo); },
                [&]() { AImageDecoder_getRepeatCount(decoder); },
                [&]() { AImageDecoder_getFrameInfo(decoder, frameInfo); },
                [&]() { AImageDecoderFrameInfo_getDuration(frameInfo); },
                [&]() { AImageDecoderFrameInfo_hasAlphaWithinBounds(frameInfo); },
                [&]() { AImageDecoderFrameInfo_getDisposeOp(frameInfo); },
                [&]() { AImageDecoderFrameInfo_getBlendOp(frameInfo); },
                [&]() {
                    AImageDecoder_setInternallyHandleDisposePrevious(
                            decoder, dataProvider.ConsumeBool() /* handle */);
                },
                [&]() { AImageDecoder_rewind(decoder); },
                [&]() { AImageDecoder_advanceFrame(decoder); },
                [&]() {
                    size_t stride = AImageDecoder_getMinimumStride(decoder);
                    size_t pixelSize = height * stride;
                    auto pixels = PixelPointer(std::malloc(pixelSize));
                    if (!pixels.get()) {
                        return;
                    }
                    AImageDecoder_decodeImage(decoder, pixels.get(), stride, pixelSize);
                },
        });
        invokeImageApi();
    }

    AImageDecoderFrameInfo_delete(frameInfo);
    AImageDecoder_delete(decoder);
    return 0;
}
