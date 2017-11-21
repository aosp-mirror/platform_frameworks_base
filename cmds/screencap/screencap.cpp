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

#include <errno.h>
#include <unistd.h>
#include <stdio.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>

#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/mman.h>

#include <binder/ProcessState.h>

#include <gui/SurfaceComposerClient.h>
#include <gui/ISurfaceComposer.h>

#include <ui/DisplayInfo.h>
#include <ui/PixelFormat.h>

#include <system/graphics.h>

// TODO: Fix Skia.
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-parameter"
#include <SkImageEncoder.h>
#include <SkData.h>
#include <SkColorSpace.h>
#pragma GCC diagnostic pop

using namespace android;

static uint32_t DEFAULT_DISPLAY_ID = ISurfaceComposer::eDisplayIdMain;

#define COLORSPACE_UNKNOWN    0
#define COLORSPACE_SRGB       1
#define COLORSPACE_DISPLAY_P3 2

static void usage(const char* pname)
{
    fprintf(stderr,
            "usage: %s [-hp] [-d display-id] [FILENAME]\n"
            "   -h: this message\n"
            "   -p: save the file as a png.\n"
            "   -d: specify the display id to capture, default %d.\n"
            "If FILENAME ends with .png it will be saved as a png.\n"
            "If FILENAME is not given, the results will be printed to stdout.\n",
            pname, DEFAULT_DISPLAY_ID
    );
}

static SkColorType flinger2skia(PixelFormat f)
{
    switch (f) {
        case PIXEL_FORMAT_RGB_565:
            return kRGB_565_SkColorType;
        default:
            return kN32_SkColorType;
    }
}

static sk_sp<SkColorSpace> dataSpaceToColorSpace(android_dataspace d)
{
    switch (d) {
        case HAL_DATASPACE_V0_SRGB:
            return SkColorSpace::MakeSRGB();
        case HAL_DATASPACE_DISPLAY_P3:
            return SkColorSpace::MakeRGB(
                    SkColorSpace::kSRGB_RenderTargetGamma, SkColorSpace::kDCIP3_D65_Gamut);
        default:
            return nullptr;
    }
}

static uint32_t dataSpaceToInt(android_dataspace d)
{
    switch (d) {
        case HAL_DATASPACE_V0_SRGB:
            return COLORSPACE_SRGB;
        case HAL_DATASPACE_DISPLAY_P3:
            return COLORSPACE_DISPLAY_P3;
        default:
            return COLORSPACE_UNKNOWN;
    }
}

static status_t notifyMediaScanner(const char* fileName) {
    String8 cmd("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://");
    cmd.append(fileName);
    cmd.append(" > /dev/null");
    int result = system(cmd.string());
    if (result < 0) {
        fprintf(stderr, "Unable to broadcast intent for media scanner.\n");
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

int main(int argc, char** argv)
{
    const char* pname = argv[0];
    bool png = false;
    int32_t displayId = DEFAULT_DISPLAY_ID;
    int c;
    while ((c = getopt(argc, argv, "phd:")) != -1) {
        switch (c) {
            case 'p':
                png = true;
                break;
            case 'd':
                displayId = atoi(optarg);
                break;
            case '?':
            case 'h':
                usage(pname);
                return 1;
        }
    }
    argc -= optind;
    argv += optind;

    int fd = -1;
    const char* fn = NULL;
    if (argc == 0) {
        fd = dup(STDOUT_FILENO);
    } else if (argc == 1) {
        fn = argv[0];
        fd = open(fn, O_WRONLY | O_CREAT | O_TRUNC, 0664);
        if (fd == -1) {
            fprintf(stderr, "Error opening file: %s (%s)\n", fn, strerror(errno));
            return 1;
        }
        const int len = strlen(fn);
        if (len >= 4 && 0 == strcmp(fn+len-4, ".png")) {
            png = true;
        }
    }

    if (fd == -1) {
        usage(pname);
        return 1;
    }

    void const* mapbase = MAP_FAILED;
    ssize_t mapsize = -1;

    void* base = NULL;
    uint32_t w, s, h, f;
    android_dataspace d;
    size_t size = 0;

    // Maps orientations from DisplayInfo to ISurfaceComposer
    static const uint32_t ORIENTATION_MAP[] = {
        ISurfaceComposer::eRotateNone, // 0 == DISPLAY_ORIENTATION_0
        ISurfaceComposer::eRotate270, // 1 == DISPLAY_ORIENTATION_90
        ISurfaceComposer::eRotate180, // 2 == DISPLAY_ORIENTATION_180
        ISurfaceComposer::eRotate90, // 3 == DISPLAY_ORIENTATION_270
    };

    // setThreadPoolMaxThreadCount(0) actually tells the kernel it's
    // not allowed to spawn any additional threads, but we still spawn
    // a binder thread from userspace when we call startThreadPool().
    // See b/36066697 for rationale
    ProcessState::self()->setThreadPoolMaxThreadCount(0);
    ProcessState::self()->startThreadPool();

    sp<IBinder> display = SurfaceComposerClient::getBuiltInDisplay(displayId);
    if (display == NULL) {
        fprintf(stderr, "Unable to get handle for display %d\n", displayId);
        // b/36066697: Avoid running static destructors.
        _exit(1);
    }

    Vector<DisplayInfo> configs;
    SurfaceComposerClient::getDisplayConfigs(display, &configs);
    int activeConfig = SurfaceComposerClient::getActiveConfig(display);
    if (static_cast<size_t>(activeConfig) >= configs.size()) {
        fprintf(stderr, "Active config %d not inside configs (size %zu)\n",
                activeConfig, configs.size());
        // b/36066697: Avoid running static destructors.
        _exit(1);
    }
    uint8_t displayOrientation = configs[activeConfig].orientation;
    uint32_t captureOrientation = ORIENTATION_MAP[displayOrientation];

    sp<GraphicBuffer> outBuffer;
    status_t result = ScreenshotClient::capture(display, Rect(), 0 /* reqWidth */,
            0 /* reqHeight */, INT32_MIN, INT32_MAX, /* all layers */ false, captureOrientation,
            &outBuffer);
    if (result != NO_ERROR) {
        close(fd);
        _exit(1);
    }

    result = outBuffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &base);

    if (base == NULL) {
        close(fd);
        _exit(1);
    }

    w = outBuffer->getWidth();
    h = outBuffer->getHeight();
    s = outBuffer->getStride();
    f = outBuffer->getPixelFormat();
    d = HAL_DATASPACE_UNKNOWN;
    size = s * h * bytesPerPixel(f);

    if (png) {
        const SkImageInfo info =
            SkImageInfo::Make(w, h, flinger2skia(f), kPremul_SkAlphaType, dataSpaceToColorSpace(d));
        SkPixmap pixmap(info, base, s * bytesPerPixel(f));
        struct FDWStream final : public SkWStream {
          size_t fBytesWritten = 0;
          int fFd;
          FDWStream(int f) : fFd(f) {}
          size_t bytesWritten() const override { return fBytesWritten; }
          bool write(const void* buffer, size_t size) override {
            fBytesWritten += size;
            return size == 0 || ::write(fFd, buffer, size) > 0;
          }
        } fdStream(fd);
        (void)SkEncodeImage(&fdStream, pixmap, SkEncodedImageFormat::kPNG, 100);
        if (fn != NULL) {
            notifyMediaScanner(fn);
        }
    } else {
        uint32_t c = dataSpaceToInt(d);
        write(fd, &w, 4);
        write(fd, &h, 4);
        write(fd, &f, 4);
        write(fd, &c, 4);
        size_t Bpp = bytesPerPixel(f);
        for (size_t y=0 ; y<h ; y++) {
            write(fd, base, w*Bpp);
            base = (void *)((char *)base + s*Bpp);
        }
    }
    close(fd);
    if (mapbase != MAP_FAILED) {
        munmap((void *)mapbase, mapsize);
    }

    // b/36066697: Avoid running static destructors.
    _exit(0);
}