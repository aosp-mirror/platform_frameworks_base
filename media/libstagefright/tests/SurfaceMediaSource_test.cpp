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

#define LOG_TAG "SurfaceMediaSource_test"
// #define LOG_NDEBUG 0

#include <gtest/gtest.h>
#include <utils/String8.h>
#include <utils/Errors.h>

#include <media/stagefright/SurfaceMediaSource.h>

#include <gui/SurfaceTextureClient.h>
#include <ui/GraphicBuffer.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/Surface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <binder/ProcessState.h>
#include <ui/FramebufferNativeWindow.h>

#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <OMX_Component.h>

#include "DummyRecorder.h"

namespace android {


class SurfaceMediaSourceTest : public ::testing::Test {
public:

    SurfaceMediaSourceTest( ): mYuvTexWidth(64), mYuvTexHeight(66) { }
    sp<MPEG4Writer>  setUpWriter(OMXClient &client );
    void oneBufferPass(int width, int height );
    static void fillYV12Buffer(uint8_t* buf, int w, int h, int stride) ;
    static void fillYV12BufferRect(uint8_t* buf, int w, int h,
                        int stride, const android_native_rect_t& rect) ;
protected:

    virtual void SetUp() {
        mSMS = new SurfaceMediaSource(mYuvTexWidth, mYuvTexHeight);
        mSMS->setSynchronousMode(true);
        mSTC = new SurfaceTextureClient(mSMS);
        mANW = mSTC;

    }


    virtual void TearDown() {
        mSMS.clear();
        mSTC.clear();
        mANW.clear();
    }

    const int mYuvTexWidth;//  = 64;
    const int mYuvTexHeight;// = 66;

    sp<SurfaceMediaSource> mSMS;
    sp<SurfaceTextureClient> mSTC;
    sp<ANativeWindow> mANW;

};

void SurfaceMediaSourceTest::oneBufferPass(int width, int height ) {
    LOGV("One Buffer Pass");
    ANativeWindowBuffer* anb;
    ASSERT_EQ(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
    ASSERT_TRUE(anb != NULL);

    sp<GraphicBuffer> buf(new GraphicBuffer(anb, false));
    ASSERT_EQ(NO_ERROR, mANW->lockBuffer(mANW.get(), buf->getNativeBuffer()));

    // Fill the buffer with the a checkerboard pattern
    uint8_t* img = NULL;
    buf->lock(GRALLOC_USAGE_SW_WRITE_OFTEN, (void**)(&img));
    SurfaceMediaSourceTest::fillYV12Buffer(img, width, height, buf->getStride());
    buf->unlock();

    ASSERT_EQ(NO_ERROR, mANW->queueBuffer(mANW.get(), buf->getNativeBuffer()));
}

sp<MPEG4Writer> SurfaceMediaSourceTest::setUpWriter(OMXClient &client ) {
    // Writing to a file
    const char *fileName = "/sdcard/outputSurfEnc.mp4";
    sp<MetaData> enc_meta = new MetaData;
    enc_meta->setInt32(kKeyBitRate, 300000);
    enc_meta->setInt32(kKeyFrameRate, 30);

    enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);

    sp<MetaData> meta = mSMS->getFormat();

    int32_t width, height, stride, sliceHeight, colorFormat;
    CHECK(meta->findInt32(kKeyWidth, &width));
    CHECK(meta->findInt32(kKeyHeight, &height));
    CHECK(meta->findInt32(kKeyStride, &stride));
    CHECK(meta->findInt32(kKeySliceHeight, &sliceHeight));
    CHECK(meta->findInt32(kKeyColorFormat, &colorFormat));

    enc_meta->setInt32(kKeyWidth, width);
    enc_meta->setInt32(kKeyHeight, height);
    enc_meta->setInt32(kKeyIFramesInterval, 1);
    enc_meta->setInt32(kKeyStride, stride);
    enc_meta->setInt32(kKeySliceHeight, sliceHeight);
    // TODO: overwriting the colorformat since the format set by GRAlloc
    // could be wrong or not be read by OMX
    enc_meta->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
    // colorFormat);


    sp<MediaSource> encoder =
        OMXCodec::Create(
                client.interface(), enc_meta, true /* createEncoder */, mSMS);

    sp<MPEG4Writer> writer = new MPEG4Writer(fileName);
    writer->addSource(encoder);

    return writer;
}

// Fill a YV12 buffer with a multi-colored checkerboard pattern
void SurfaceMediaSourceTest::fillYV12Buffer(uint8_t* buf, int w, int h, int stride) {
    const int blockWidth = w > 16 ? w / 16 : 1;
    const int blockHeight = h > 16 ? h / 16 : 1;
    const int yuvTexOffsetY = 0;
    int yuvTexStrideY = stride;
    int yuvTexOffsetV = yuvTexStrideY * h;
    int yuvTexStrideV = (yuvTexStrideY/2 + 0xf) & ~0xf;
    int yuvTexOffsetU = yuvTexOffsetV + yuvTexStrideV * h/2;
    int yuvTexStrideU = yuvTexStrideV;
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            int parityX = (x / blockWidth) & 1;
            int parityY = (y / blockHeight) & 1;
            unsigned char intensity = (parityX ^ parityY) ? 63 : 191;
            buf[yuvTexOffsetY + (y * yuvTexStrideY) + x] = intensity;
            if (x < w / 2 && y < h / 2) {
                buf[yuvTexOffsetU + (y * yuvTexStrideU) + x] = intensity;
                if (x * 2 < w / 2 && y * 2 < h / 2) {
                    buf[yuvTexOffsetV + (y*2 * yuvTexStrideV) + x*2 + 0] =
                    buf[yuvTexOffsetV + (y*2 * yuvTexStrideV) + x*2 + 1] =
                    buf[yuvTexOffsetV + ((y*2+1) * yuvTexStrideV) + x*2 + 0] =
                    buf[yuvTexOffsetV + ((y*2+1) * yuvTexStrideV) + x*2 + 1] =
                        intensity;
                }
            }
        }
    }
}

// Fill a YV12 buffer with red outside a given rectangle and green inside it.
void SurfaceMediaSourceTest::fillYV12BufferRect(uint8_t* buf, int w,
                  int h, int stride, const android_native_rect_t& rect) {
    const int yuvTexOffsetY = 0;
    int yuvTexStrideY = stride;
    int yuvTexOffsetV = yuvTexStrideY * h;
    int yuvTexStrideV = (yuvTexStrideY/2 + 0xf) & ~0xf;
    int yuvTexOffsetU = yuvTexOffsetV + yuvTexStrideV * h/2;
    int yuvTexStrideU = yuvTexStrideV;
    for (int x = 0; x < w; x++) {
        for (int y = 0; y < h; y++) {
            bool inside = rect.left <= x && x < rect.right &&
                    rect.top <= y && y < rect.bottom;
            buf[yuvTexOffsetY + (y * yuvTexStrideY) + x] = inside ? 240 : 64;
            if (x < w / 2 && y < h / 2) {
                bool inside = rect.left <= 2*x && 2*x < rect.right &&
                        rect.top <= 2*y && 2*y < rect.bottom;
                buf[yuvTexOffsetU + (y * yuvTexStrideU) + x] = 16;
                buf[yuvTexOffsetV + (y * yuvTexStrideV) + x] =
                                                inside ? 16 : 255;
            }
        }
    }
}  ///////// End of class SurfaceMediaSourceTest

///////////////////////////////////////////////////////////////////
// Class to imitate the recording     /////////////////////////////
// ////////////////////////////////////////////////////////////////
struct SimpleDummyRecorder {
        sp<MediaSource> mSource;

        SimpleDummyRecorder
                (const sp<MediaSource> &source): mSource(source) {}

        status_t start() { return mSource->start();}
        status_t stop()  { return mSource->stop();}

        // fakes reading from a media source
        status_t readFromSource() {
            MediaBuffer *buffer;
            status_t err = mSource->read(&buffer);
            if (err != OK) {
                return err;
            }
            buffer->release();
            buffer = NULL;
            return OK;
        }
};

///////////////////////////////////////////////////////////////////
//           TESTS
// Just pass one buffer from the native_window to the SurfaceMediaSource
TEST_F(SurfaceMediaSourceTest, EncodingFromCpuFilledYV12BufferNpotOneBufferPass) {
    LOGV("Testing OneBufferPass ******************************");

    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            0, 0, HAL_PIXEL_FORMAT_YV12));
                                // OMX_COLOR_FormatYUV420Planar)); // ));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    oneBufferPass(mYuvTexWidth, mYuvTexHeight);
}

// Pass the buffer with the wrong height and weight and should not be accepted
TEST_F(SurfaceMediaSourceTest, EncodingFromCpuFilledYV12BufferNpotWrongSizeBufferPass) {
    LOGV("Testing Wrong size BufferPass ******************************");

    // setting the client side buffer size different than the server size
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
             10, 10, HAL_PIXEL_FORMAT_YV12));
                                // OMX_COLOR_FormatYUV420Planar)); // ));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    ANativeWindowBuffer* anb;

    // make sure we get an error back when dequeuing!
    ASSERT_NE(NO_ERROR, mANW->dequeueBuffer(mANW.get(), &anb));
}


// pass multiple buffers from the native_window the SurfaceMediaSource
// A dummy writer is used to simulate actual MPEG4Writer
TEST_F(SurfaceMediaSourceTest,  EncodingFromCpuFilledYV12BufferNpotMultiBufferPass) {
    LOGV("Testing MultiBufferPass, Dummy Recorder *********************");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            0, 0, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));
    SimpleDummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 0;
    while (nFramesCount < 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);

        ASSERT_EQ(NO_ERROR, writer.readFromSource());

        nFramesCount++;
    }
    writer.stop();
}

// Delayed pass of multiple buffers from the native_window the SurfaceMediaSource
// A dummy writer is used to simulate actual MPEG4Writer
TEST_F(SurfaceMediaSourceTest,  EncodingFromCpuFilledYV12BufferNpotMultiBufferPassLag) {
    LOGV("Testing MultiBufferPass, Dummy Recorder Lagging **************");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            0, 0, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));
    SimpleDummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 1;
    const int FRAMES_LAG = mSMS->getBufferCount() - 1;
    while (nFramesCount <= 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);
        // Forcing the writer to lag behind a few frames
        if (nFramesCount > FRAMES_LAG) {
            ASSERT_EQ(NO_ERROR, writer.readFromSource());
        }
        nFramesCount++;
    }
    writer.stop();
}

// pass multiple buffers from the native_window the SurfaceMediaSource
// A dummy writer (MULTITHREADED) is used to simulate actual MPEG4Writer
TEST_F(SurfaceMediaSourceTest, EncodingFromCpuFilledYV12BufferNpotMultiBufferPassThreaded) {
    LOGV("Testing MultiBufferPass, Dummy Recorder Multi-Threaded **********");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            0, 0, HAL_PIXEL_FORMAT_YV12));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    DummyRecorder writer(mSMS);
    writer.start();

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);

        nFramesCount++;
    }
    writer.stop();
}

// Test to examine the actual encoding. Temporarily disabled till the
// colorformat and encoding from GRAlloc data is resolved
TEST_F(SurfaceMediaSourceTest, DISABLED_EncodingFromCpuFilledYV12BufferNpotWrite) {
    LOGV("Testing the whole pipeline with actual Recorder");
    ASSERT_EQ(NO_ERROR, native_window_set_buffers_geometry(mANW.get(),
            0, 0, HAL_PIXEL_FORMAT_YV12)); // OMX_COLOR_FormatYUV420Planar)); // ));
    ASSERT_EQ(NO_ERROR, native_window_set_usage(mANW.get(),
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN));

    OMXClient client;
    CHECK_EQ(OK, client.connect());

    sp<MPEG4Writer> writer = setUpWriter(client);
    int64_t start = systemTime();
    CHECK_EQ(OK, writer->start());

    int32_t nFramesCount = 0;
    while (nFramesCount <= 300) {
        oneBufferPass(mYuvTexWidth, mYuvTexHeight);
        nFramesCount++;
    }

    CHECK_EQ(OK, writer->stop());
    writer.clear();
    int64_t end = systemTime();
    client.disconnect();
}


} // namespace android
