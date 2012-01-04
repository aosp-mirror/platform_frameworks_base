/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <binder/IMemory.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>
#include <utils/String8.h>

namespace android {

// Fill an RGBA_8888 formatted surface with a single color.
static void fillSurfaceRGBA8(const sp<SurfaceControl>& sc,
        uint8_t r, uint8_t g, uint8_t b) {
    Surface::SurfaceInfo info;
    sp<Surface> s = sc->getSurface();
    ASSERT_TRUE(s != NULL);
    ASSERT_EQ(NO_ERROR, s->lock(&info));
    uint8_t* img = reinterpret_cast<uint8_t*>(info.bits);
    for (uint32_t y = 0; y < info.h; y++) {
        for (uint32_t x = 0; x < info.w; x++) {
            uint8_t* pixel = img + (4 * (y*info.s + x));
            pixel[0] = r;
            pixel[1] = g;
            pixel[2] = b;
            pixel[3] = 255;
        }
    }
    ASSERT_EQ(NO_ERROR, s->unlockAndPost());
}

// A ScreenCapture is a screenshot from SurfaceFlinger that can be used to check
// individual pixel values for testing purposes.
class ScreenCapture : public RefBase {
public:
    static void captureScreen(sp<ScreenCapture>* sc) {
        sp<IMemoryHeap> heap;
        uint32_t w=0, h=0;
        PixelFormat fmt=0;
        sp<ISurfaceComposer> sf(ComposerService::getComposerService());
        ASSERT_EQ(NO_ERROR, sf->captureScreen(0, &heap, &w, &h, &fmt, 0, 0,
                0, INT_MAX));
        ASSERT_TRUE(heap != NULL);
        ASSERT_EQ(PIXEL_FORMAT_RGBA_8888, fmt);
        *sc = new ScreenCapture(w, h, heap);
    }

    void checkPixel(uint32_t x, uint32_t y, uint8_t r, uint8_t g, uint8_t b) {
        const uint8_t* img = reinterpret_cast<const uint8_t*>(mHeap->base());
        const uint8_t* pixel = img + (4 * (y*mWidth + x));
        if (r != pixel[0] || g != pixel[1] || b != pixel[2]) {
            String8 err(String8::format("pixel @ (%3d, %3d): "
                    "expected [%3d, %3d, %3d], got [%3d, %3d, %3d]",
                    x, y, r, g, b, pixel[0], pixel[1], pixel[2]));
            EXPECT_EQ(String8(), err);
        }
    }

private:
    ScreenCapture(uint32_t w, uint32_t h, const sp<IMemoryHeap>& heap) :
        mWidth(w),
        mHeight(h),
        mHeap(heap)
    {}

    const uint32_t mWidth;
    const uint32_t mHeight;
    sp<IMemoryHeap> mHeap;
};

class LayerUpdateTest : public ::testing::Test {
protected:
    virtual void SetUp() {
        mComposerClient = new SurfaceComposerClient;
        ASSERT_EQ(NO_ERROR, mComposerClient->initCheck());

        ssize_t displayWidth = mComposerClient->getDisplayWidth(0);
        ssize_t displayHeight = mComposerClient->getDisplayHeight(0);

        // Background surface
        mBGSurfaceControl = mComposerClient->createSurface(
                String8("BG Test Surface"), 0, displayWidth, displayHeight,
                PIXEL_FORMAT_RGBA_8888, 0);
        ASSERT_TRUE(mBGSurfaceControl != NULL);
        ASSERT_TRUE(mBGSurfaceControl->isValid());
        fillSurfaceRGBA8(mBGSurfaceControl, 63, 63, 195);

        // Foreground surface
        mFGSurfaceControl = mComposerClient->createSurface(
                String8("FG Test Surface"), 0, 64, 64, PIXEL_FORMAT_RGBA_8888, 0);
        ASSERT_TRUE(mFGSurfaceControl != NULL);
        ASSERT_TRUE(mFGSurfaceControl->isValid());

        fillSurfaceRGBA8(mFGSurfaceControl, 195, 63, 63);

        // Synchronization surface
        mSyncSurfaceControl = mComposerClient->createSurface(
                String8("Sync Test Surface"), 0, 1, 1, PIXEL_FORMAT_RGBA_8888, 0);
        ASSERT_TRUE(mSyncSurfaceControl != NULL);
        ASSERT_TRUE(mSyncSurfaceControl->isValid());

        fillSurfaceRGBA8(mSyncSurfaceControl, 31, 31, 31);

        SurfaceComposerClient::openGlobalTransaction();

        ASSERT_EQ(NO_ERROR, mBGSurfaceControl->setLayer(INT_MAX-2));
        ASSERT_EQ(NO_ERROR, mBGSurfaceControl->show());

        ASSERT_EQ(NO_ERROR, mFGSurfaceControl->setLayer(INT_MAX-1));
        ASSERT_EQ(NO_ERROR, mFGSurfaceControl->setPosition(64, 64));
        ASSERT_EQ(NO_ERROR, mFGSurfaceControl->show());

        ASSERT_EQ(NO_ERROR, mSyncSurfaceControl->setLayer(INT_MAX-1));
        ASSERT_EQ(NO_ERROR, mSyncSurfaceControl->setPosition(displayWidth-2,
                displayHeight-2));
        ASSERT_EQ(NO_ERROR, mSyncSurfaceControl->show());

        SurfaceComposerClient::closeGlobalTransaction(true);
    }

    virtual void TearDown() {
        mComposerClient->dispose();
        mBGSurfaceControl = 0;
        mFGSurfaceControl = 0;
        mSyncSurfaceControl = 0;
        mComposerClient = 0;
    }

    void waitForPostedBuffers() {
        // Since the sync surface is in synchronous mode (i.e. double buffered)
        // posting three buffers to it should ensure that at least two
        // SurfaceFlinger::handlePageFlip calls have been made, which should
        // guaranteed that a buffer posted to another Surface has been retired.
        fillSurfaceRGBA8(mSyncSurfaceControl, 31, 31, 31);
        fillSurfaceRGBA8(mSyncSurfaceControl, 31, 31, 31);
        fillSurfaceRGBA8(mSyncSurfaceControl, 31, 31, 31);
    }

    sp<SurfaceComposerClient> mComposerClient;
    sp<SurfaceControl> mBGSurfaceControl;
    sp<SurfaceControl> mFGSurfaceControl;

    // This surface is used to ensure that the buffers posted to
    // mFGSurfaceControl have been picked up by SurfaceFlinger.
    sp<SurfaceControl> mSyncSurfaceControl;
};

TEST_F(LayerUpdateTest, LayerMoveWorks) {
    sp<ScreenCapture> sc;
    {
        SCOPED_TRACE("before move");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel(  0,  12,  63,  63, 195);
        sc->checkPixel( 75,  75, 195,  63,  63);
        sc->checkPixel(145, 145,  63,  63, 195);
    }

    SurfaceComposerClient::openGlobalTransaction();
    ASSERT_EQ(NO_ERROR, mFGSurfaceControl->setPosition(128, 128));
    SurfaceComposerClient::closeGlobalTransaction(true);
    {
        // This should reflect the new position, but not the new color.
        SCOPED_TRACE("after move, before redraw");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel( 24,  24,  63,  63, 195);
        sc->checkPixel( 75,  75,  63,  63, 195);
        sc->checkPixel(145, 145, 195,  63,  63);
    }

    fillSurfaceRGBA8(mFGSurfaceControl, 63, 195, 63);
    waitForPostedBuffers();
    {
        // This should reflect the new position and the new color.
        SCOPED_TRACE("after redraw");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel( 24,  24,  63,  63, 195);
        sc->checkPixel( 75,  75,  63,  63, 195);
        sc->checkPixel(145, 145,  63, 195,  63);
    }
}

TEST_F(LayerUpdateTest, LayerResizeWorks) {
    sp<ScreenCapture> sc;
    {
        SCOPED_TRACE("before resize");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel(  0,  12,  63,  63, 195);
        sc->checkPixel( 75,  75, 195,  63,  63);
        sc->checkPixel(145, 145,  63,  63, 195);
    }

    ALOGD("resizing");
    SurfaceComposerClient::openGlobalTransaction();
    ASSERT_EQ(NO_ERROR, mFGSurfaceControl->setSize(128, 128));
    SurfaceComposerClient::closeGlobalTransaction(true);
    ALOGD("resized");
    {
        // This should not reflect the new size or color because SurfaceFlinger
        // has not yet received a buffer of the correct size.
        SCOPED_TRACE("after resize, before redraw");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel(  0,  12,  63,  63, 195);
        sc->checkPixel( 75,  75, 195,  63,  63);
        sc->checkPixel(145, 145,  63,  63, 195);
    }

    ALOGD("drawing");
    fillSurfaceRGBA8(mFGSurfaceControl, 63, 195, 63);
    waitForPostedBuffers();
    ALOGD("drawn");
    {
        // This should reflect the new size and the new color.
        SCOPED_TRACE("after redraw");
        ScreenCapture::captureScreen(&sc);
        sc->checkPixel( 24,  24,  63,  63, 195);
        sc->checkPixel( 75,  75,  63, 195,  63);
        sc->checkPixel(145, 145,  63, 195,  63);
    }
}

}
