/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "SineSource.h"

#include <binder/ProcessState.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MPEG4Writer.h>
#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#include <media/MediaPlayerInterface.h>

using namespace android;

// Print usage showing how to use this utility to record videos
static void usage(const char *me) {
    fprintf(stderr, "usage: %s\n", me);
    fprintf(stderr, "       -h(elp)\n");
    fprintf(stderr, "       -b bit rate in bits per second (default: 300000)\n");
    fprintf(stderr, "       -c YUV420 color format: [0] semi planar or [1] planar or other omx YUV420 color format (default: 1)\n");
    fprintf(stderr, "       -f frame rate in frames per second (default: 30)\n");
    fprintf(stderr, "       -i I frame interval in seconds (default: 1)\n");
    fprintf(stderr, "       -n number of frames to be recorded (default: 300)\n");
    fprintf(stderr, "       -w width in pixels (default: 176)\n");
    fprintf(stderr, "       -t height in pixels (default: 144)\n");
    fprintf(stderr, "       -l encoder level. see omx il header (default: encoder specific)\n");
    fprintf(stderr, "       -p encoder profile. see omx il header (default: encoder specific)\n");
    fprintf(stderr, "       -v video codec: [0] AVC [1] M4V [2] H263 (default: 0)\n");
    fprintf(stderr, "The output file is /sdcard/output.mp4\n");
    exit(1);
}

class DummySource : public MediaSource {

public:
    DummySource(int width, int height, int nFrames, int fps, int colorFormat)
        : mWidth(width),
          mHeight(height),
          mMaxNumFrames(nFrames),
          mFrameRate(fps),
          mColorFormat(colorFormat),
          mSize((width * height * 3) / 2) {

        mGroup.add_buffer(new MediaBuffer(mSize));
    }

    virtual sp<MetaData> getFormat() {
        sp<MetaData> meta = new MetaData;
        meta->setInt32(kKeyWidth, mWidth);
        meta->setInt32(kKeyHeight, mHeight);
        meta->setInt32(kKeyColorFormat, mColorFormat);
        meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);

        return meta;
    }

    virtual status_t start(MetaData *params) {
        mNumFramesOutput = 0;
        return OK;
    }

    virtual status_t stop() {
        return OK;
    }

    virtual status_t read(
            MediaBuffer **buffer, const MediaSource::ReadOptions *options) {

        if (mNumFramesOutput % 10 == 0) {
            fprintf(stderr, ".");
        }
        if (mNumFramesOutput == mMaxNumFrames) {
            return ERROR_END_OF_STREAM;
        }

        status_t err = mGroup.acquire_buffer(buffer);
        if (err != OK) {
            return err;
        }

        // We don't care about the contents. we just test video encoder
        // Also, by skipping the content generation, we can return from
        // read() much faster.
        //char x = (char)((double)rand() / RAND_MAX * 255);
        //memset((*buffer)->data(), x, mSize);
        (*buffer)->set_range(0, mSize);
        (*buffer)->meta_data()->clear();
        (*buffer)->meta_data()->setInt64(
                kKeyTime, (mNumFramesOutput * 1000000) / mFrameRate);
        ++mNumFramesOutput;

        return OK;
    }

protected:
    virtual ~DummySource() {}

private:
    MediaBufferGroup mGroup;
    int mWidth, mHeight;
    int mMaxNumFrames;
    int mFrameRate;
    int mColorFormat;
    size_t mSize;
    int64_t mNumFramesOutput;;

    DummySource(const DummySource &);
    DummySource &operator=(const DummySource &);
};

enum {
    kYUV420SP = 0,
    kYUV420P  = 1,
};

// returns -1 if mapping of the given color is unsuccessful
// returns an omx color enum value otherwise
static int translateColorToOmxEnumValue(int color) {
    switch (color) {
        case kYUV420SP:
            return OMX_COLOR_FormatYUV420SemiPlanar;
        case kYUV420P:
            return OMX_COLOR_FormatYUV420Planar;
        default:
            fprintf(stderr, "Custom OMX color format: %d\n", color);
            if (color == OMX_TI_COLOR_FormatYUV420PackedSemiPlanar ||
                color == OMX_QCOM_COLOR_FormatYVU420SemiPlanar) {
                return color;
            }
    }
    return -1;
}

int main(int argc, char **argv) {

    // Default values for the program if not overwritten
    int frameRateFps = 30;
    int width = 176;
    int height = 144;
    int bitRateBps = 300000;
    int iFramesIntervalSeconds = 1;
    int colorFormat = OMX_COLOR_FormatYUV420Planar;
    int nFrames = 300;
    int level = -1;        // Encoder specific default
    int profile = -1;      // Encoder specific default
    int codec = 0;
    const char *fileName = "/sdcard/output.mp4";

    android::ProcessState::self()->startThreadPool();
    int res;
    while ((res = getopt(argc, argv, "b:c:f:i:n:w:t:l:p:v:h")) >= 0) {
        switch (res) {
            case 'b':
            {
                bitRateBps = atoi(optarg);
                break;
            }

            case 'c':
            {
                colorFormat = translateColorToOmxEnumValue(atoi(optarg));
                if (colorFormat == -1) {
                    usage(argv[0]);
                }
                break;
            }

            case 'f':
            {
                frameRateFps = atoi(optarg);
                break;
            }

            case 'i':
            {
                iFramesIntervalSeconds = atoi(optarg);
                break;
            }

            case 'n':
            {
                nFrames = atoi(optarg);
                break;
            }

            case 'w':
            {
                width = atoi(optarg);
                break;
            }

            case 't':
            {
                height = atoi(optarg);
                break;
            }

            case 'l':
            {
                level = atoi(optarg);
                break;
            }

            case 'p':
            {
                profile = atoi(optarg);
                break;
            }

            case 'v':
            {
                codec = atoi(optarg);
                if (codec < 0 || codec > 2) {
                    usage(argv[0]);
                }
                break;
            }

            case 'h':
            default:
            {
                usage(argv[0]);
                break;
            }
        }
    }

    OMXClient client;
    CHECK_EQ(client.connect(), OK);

    status_t err = OK;
    sp<MediaSource> source =
        new DummySource(width, height, nFrames, frameRateFps, colorFormat);

    sp<MetaData> enc_meta = new MetaData;
    switch (codec) {
        case 1:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);
            break;
        case 2:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);
            break;
        default:
            enc_meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
            break;
    }
    enc_meta->setInt32(kKeyWidth, width);
    enc_meta->setInt32(kKeyHeight, height);
    enc_meta->setInt32(kKeyFrameRate, frameRateFps);
    enc_meta->setInt32(kKeyBitRate, bitRateBps);
    enc_meta->setInt32(kKeyStride, width);
    enc_meta->setInt32(kKeySliceHeight, height);
    enc_meta->setInt32(kKeyIFramesInterval, iFramesIntervalSeconds);
    enc_meta->setInt32(kKeyColorFormat, colorFormat);
    if (level != -1) {
        enc_meta->setInt32(kKeyVideoLevel, level);
    }
    if (profile != -1) {
        enc_meta->setInt32(kKeyVideoProfile, profile);
    }

    sp<MediaSource> encoder =
        OMXCodec::Create(
                client.interface(), enc_meta, true /* createEncoder */, source);

    sp<MPEG4Writer> writer = new MPEG4Writer(fileName);
    writer->addSource(encoder);
    int64_t start = systemTime();
    CHECK_EQ(OK, writer->start());
    while (!writer->reachedEOS()) {
    }
    err = writer->stop();
    int64_t end = systemTime();

    fprintf(stderr, "$\n");
    client.disconnect();

    if (err != OK && err != ERROR_END_OF_STREAM) {
        fprintf(stderr, "record failed: %d\n", err);
        return 1;
    }
    fprintf(stderr, "encoding %d frames in %lld us\n", nFrames, (end-start)/1000);
    fprintf(stderr, "encoding speed is: %.2f fps\n", (nFrames * 1E9) / (end-start));
    return 0;
}
