/*
 * Copyright (C) 2015 The Android Open Source Project
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
#include "TestWindowContext.h"

#include "AnimationContext.h"
#include "IContextFactory.h"
#include "RenderNode.h"
#include "SkTypes.h"
#include "gui/BufferQueue.h"
#include "gui/CpuConsumer.h"
#include "gui/IGraphicBufferConsumer.h"
#include "gui/IGraphicBufferProducer.h"
#include "gui/Surface.h"
#include "hwui/Canvas.h"
#include "renderthread/RenderProxy.h"

#include <cutils/memory.h>

namespace {

/**
 * Helper class for setting up android::uirenderer::renderthread::RenderProxy.
 */
class ContextFactory : public android::uirenderer::IContextFactory {
public:
    android::uirenderer::AnimationContext* createAnimationContext(
            android::uirenderer::renderthread::TimeLord& clock) override {
        return new android::uirenderer::AnimationContext(clock);
    }
};

}  // anonymous namespace

namespace android {
namespace uirenderer {

/**
  Android strong pointers (android::sp) can't hold forward-declared classes,
  so we have to use pointer-to-implementation here if we want to hide the
  details from our non-framework users.
*/

class TestWindowContext::TestWindowData {
public:
    explicit TestWindowData(SkISize size) : mSize(size) {
        android::BufferQueue::createBufferQueue(&mProducer, &mConsumer);
        mCpuConsumer = new android::CpuConsumer(mConsumer, 1);
        mCpuConsumer->setName(android::String8("TestWindowContext"));
        mCpuConsumer->setDefaultBufferSize(mSize.width(), mSize.height());
        mAndroidSurface = new android::Surface(mProducer);
        native_window_set_buffers_dimensions(mAndroidSurface.get(), mSize.width(), mSize.height());
        native_window_set_buffers_format(mAndroidSurface.get(), android::PIXEL_FORMAT_RGBA_8888);
        native_window_set_usage(mAndroidSurface.get(), GRALLOC_USAGE_SW_READ_OFTEN |
                                                               GRALLOC_USAGE_SW_WRITE_NEVER |
                                                               GRALLOC_USAGE_HW_RENDER);
        mRootNode.reset(new android::uirenderer::RenderNode());
        mRootNode->incStrong(nullptr);
        mRootNode->mutateStagingProperties().setLeftTopRightBottom(0, 0, mSize.width(),
                                                                   mSize.height());
        mRootNode->mutateStagingProperties().setClipToBounds(false);
        mRootNode->setPropertyFieldsDirty(android::uirenderer::RenderNode::GENERIC);
        ContextFactory factory;
        mProxy.reset(new android::uirenderer::renderthread::RenderProxy(false, mRootNode.get(),
                                                                        &factory));
        mProxy->loadSystemProperties();
        mProxy->initialize(mAndroidSurface.get());
        float lightX = mSize.width() / 2.0f;
        android::uirenderer::Vector3 lightVector{lightX, -200.0f, 800.0f};
        mProxy->setup(800.0f, 255 * 0.075f, 255 * 0.15f);
        mProxy->setLightCenter(lightVector);
        mCanvas.reset(Canvas::create_recording_canvas(mSize.width(), mSize.height(), mRootNode.get()));
    }

    SkCanvas* prepareToDraw() {
        // mCanvas->reset(mSize.width(), mSize.height());
        mCanvas->clipRect(0, 0, mSize.width(), mSize.height(), SkClipOp::kReplace_deprecated);
        return mCanvas->asSkCanvas();
    }

    void finishDrawing() {
        mRootNode->setStagingDisplayList(mCanvas->finishRecording());
        mProxy->syncAndDrawFrame();
        // Surprisingly, calling mProxy->fence() here appears to make no difference to
        // the timings we record.
    }

    void fence() { mProxy->fence(); }

    bool capturePixels(SkBitmap* bmp) {
        sk_sp<SkColorSpace> colorSpace = SkColorSpace::MakeSRGB();
        SkImageInfo destinationConfig =
                SkImageInfo::Make(mSize.width(), mSize.height(), kRGBA_8888_SkColorType,
                                  kPremul_SkAlphaType, colorSpace);
        bmp->allocPixels(destinationConfig);
        android_memset32((uint32_t*)bmp->getPixels(), SK_ColorRED,
                         mSize.width() * mSize.height() * 4);

        android::CpuConsumer::LockedBuffer nativeBuffer;
        android::status_t retval = mCpuConsumer->lockNextBuffer(&nativeBuffer);
        if (retval == android::BAD_VALUE) {
            SkDebugf("write_canvas_png() got no buffer; returning transparent");
            // No buffer ready to read - commonly triggered by dm sending us
            // a no-op source, or calling code that doesn't do anything on this
            // backend.
            bmp->eraseColor(SK_ColorTRANSPARENT);
            return false;
        } else if (retval) {
            SkDebugf("Failed to lock buffer to read pixels: %d.", retval);
            return false;
        }

        // Move the pixels into the destination SkBitmap

        LOG_ALWAYS_FATAL_IF(nativeBuffer.format != android::PIXEL_FORMAT_RGBA_8888,
                            "Native buffer not RGBA!");
        SkImageInfo nativeConfig = SkImageInfo::Make(nativeBuffer.width, nativeBuffer.height,
                                                     kRGBA_8888_SkColorType, kPremul_SkAlphaType);

        // Android stride is in pixels, Skia stride is in bytes
        SkBitmap nativeWrapper;
        bool success = nativeWrapper.installPixels(nativeConfig, nativeBuffer.data,
                                                   nativeBuffer.stride * 4);
        if (!success) {
            SkDebugf("Failed to wrap HWUI buffer in a SkBitmap");
            return false;
        }

        LOG_ALWAYS_FATAL_IF(bmp->colorType() != kRGBA_8888_SkColorType,
                            "Destination buffer not RGBA!");
        success = nativeWrapper.readPixels(destinationConfig, bmp->getPixels(), bmp->rowBytes(), 0,
                                           0);
        if (!success) {
            SkDebugf("Failed to extract pixels from HWUI buffer");
            return false;
        }

        mCpuConsumer->unlockBuffer(nativeBuffer);

        return true;
    }

private:
    std::unique_ptr<android::uirenderer::RenderNode> mRootNode;
    std::unique_ptr<android::uirenderer::renderthread::RenderProxy> mProxy;
    std::unique_ptr<android::Canvas> mCanvas;
    android::sp<android::IGraphicBufferProducer> mProducer;
    android::sp<android::IGraphicBufferConsumer> mConsumer;
    android::sp<android::CpuConsumer> mCpuConsumer;
    android::sp<android::Surface> mAndroidSurface;
    SkISize mSize;
};

TestWindowContext::TestWindowContext() : mData(nullptr) {}

TestWindowContext::~TestWindowContext() {
    delete mData;
}

void TestWindowContext::initialize(int width, int height) {
    mData = new TestWindowData(SkISize::Make(width, height));
}

SkCanvas* TestWindowContext::prepareToDraw() {
    return mData ? mData->prepareToDraw() : nullptr;
}

void TestWindowContext::finishDrawing() {
    if (mData) {
        mData->finishDrawing();
    }
}

void TestWindowContext::fence() {
    if (mData) {
        mData->fence();
    }
}

bool TestWindowContext::capturePixels(SkBitmap* bmp) {
    return mData ? mData->capturePixels(bmp) : false;
}

}  // namespace uirenderer
}  // namespace android
