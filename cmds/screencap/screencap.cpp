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
#include <getopt.h>

#include <linux/fb.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/wait.h>

#include <android/bitmap.h>

#include <binder/ProcessState.h>

#include <ftl/concat.h>
#include <ftl/optional.h>
#include <gui/DisplayCaptureArgs.h>
#include <gui/ISurfaceComposer.h>
#include <gui/SurfaceComposerClient.h>
#include <gui/SyncScreenCaptureListener.h>

#include <ui/GraphicTypes.h>
#include <ui/PixelFormat.h>

#include <system/graphics.h>

using namespace android;

#define COLORSPACE_UNKNOWN    0
#define COLORSPACE_SRGB       1
#define COLORSPACE_DISPLAY_P3 2

void usage(const char* pname, ftl::Optional<DisplayId> displayIdOpt) {
    fprintf(stderr, R"(
usage: %s [-ahp] [-d display-id] [FILENAME]
   -h: this message
   -a: captures all the active displays. This appends an integer postfix to the FILENAME.
       e.g., FILENAME_0.png, FILENAME_1.png. If both -a and -d are given, it ignores -d.
   -d: specify the display ID to capture%s
       see "dumpsys SurfaceFlinger --display-id" for valid display IDs.
   -p: outputs in png format.
   --hint-for-seamless If set will use the hintForSeamless path in SF

If FILENAME ends with .png it will be saved as a png.
If FILENAME is not given, the results will be printed to stdout.
)",
            pname,
            displayIdOpt
                .transform([](DisplayId id) {
                    return std::string(ftl::Concat(
                    " (If the id is not given, it defaults to ", id.value,')'
                    ).str());
                })
                .value_or(std::string())
                .c_str());
}

// For options that only exist in long-form. Anything in the
// 0-255 range is reserved for short options (which just use their ASCII value)
namespace LongOpts {
enum {
    Reserved = 255,
    HintForSeamless,
};
}

static const struct option LONG_OPTIONS[] = {
        {"png", no_argument, nullptr, 'p'},
        {"help", no_argument, nullptr, 'h'},
        {"hint-for-seamless", no_argument, nullptr, LongOpts::HintForSeamless},
        {0, 0, 0, 0}};

static int32_t flinger2bitmapFormat(PixelFormat f)
{
    switch (f) {
        case PIXEL_FORMAT_RGB_565:
            return ANDROID_BITMAP_FORMAT_RGB_565;
        default:
            return ANDROID_BITMAP_FORMAT_RGBA_8888;
    }
}

static uint32_t dataSpaceToInt(ui::Dataspace d)
{
    switch (d) {
        case ui::Dataspace::V0_SRGB:
            return COLORSPACE_SRGB;
        case ui::Dataspace::DISPLAY_P3:
            return COLORSPACE_DISPLAY_P3;
        default:
            return COLORSPACE_UNKNOWN;
    }
}

static status_t notifyMediaScanner(const char* fileName) {
    std::string filePath("file://");
    filePath.append(fileName);
    char *cmd[] = {
        (char*) "am",
        (char*) "broadcast",
        (char*) "-a",
        (char*) "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        (char*) "-d",
        &filePath[0],
        nullptr
    };

    int status;
    int pid = fork();
    if (pid < 0){
        fprintf(stderr, "Unable to fork in order to send intent for media scanner.\n");
        return UNKNOWN_ERROR;
    }
    if (pid == 0){
        int fd = open("/dev/null", O_WRONLY);
        if (fd < 0){
            fprintf(stderr, "Unable to open /dev/null for media scanner stdout redirection.\n");
            exit(1);
        }
        dup2(fd, 1);
        int result = execvp(cmd[0], cmd);
        close(fd);
        exit(result);
    }
    wait(&status);

    if (status < 0) {
        fprintf(stderr, "Unable to broadcast intent for media scanner.\n");
        return UNKNOWN_ERROR;
    }
    return NO_ERROR;
}

status_t capture(const DisplayId displayId,
            const gui::CaptureArgs& captureArgs,
            ScreenCaptureResults& outResult) {
    sp<SyncScreenCaptureListener> captureListener = new SyncScreenCaptureListener();
    ScreenshotClient::captureDisplay(displayId, captureArgs, captureListener);

    ScreenCaptureResults captureResults = captureListener->waitForResults();
    if (!captureResults.fenceResult.ok()) {
        fprintf(stderr, "Failed to take screenshot. Status: %d\n",
                fenceStatus(captureResults.fenceResult));
        return 1;
    }

    outResult = captureResults;

    return 0;
}

status_t saveImage(const char* fn, bool png, const ScreenCaptureResults& captureResults) {
    void* base = nullptr;
    ui::Dataspace dataspace = captureResults.capturedDataspace;
    sp<GraphicBuffer> buffer = captureResults.buffer;

    status_t result = buffer->lock(GraphicBuffer::USAGE_SW_READ_OFTEN, &base);

    if (base == nullptr || result != NO_ERROR) {
        String8 reason;
        if (result != NO_ERROR) {
            reason.appendFormat(" Error Code: %d", result);
        } else {
            reason = "Failed to write to buffer";
        }
        fprintf(stderr, "Failed to take screenshot (%s)\n", reason.c_str());
        return 1;
    }

    int fd = -1;
    if (fn == nullptr) {
        fd = dup(STDOUT_FILENO);
        if (fd == -1) {
            fprintf(stderr, "Error writing to stdout. (%s)\n", strerror(errno));
            return 1;
        }
    } else {
        fd = open(fn, O_WRONLY | O_CREAT | O_TRUNC, 0664);
        if (fd == -1) {
            fprintf(stderr, "Error opening file: %s (%s)\n", fn, strerror(errno));
            return 1;
        }
    }

    if (png) {
        AndroidBitmapInfo info;
        info.format = flinger2bitmapFormat(buffer->getPixelFormat());
        info.flags = ANDROID_BITMAP_FLAGS_ALPHA_PREMUL;
        info.width = buffer->getWidth();
        info.height = buffer->getHeight();
        info.stride = buffer->getStride() * bytesPerPixel(buffer->getPixelFormat());

        int result = AndroidBitmap_compress(&info, static_cast<int32_t>(dataspace), base,
                                            ANDROID_BITMAP_COMPRESS_FORMAT_PNG, 100, &fd,
                                            [](void* fdPtr, const void* data, size_t size) -> bool {
                                                int bytesWritten = write(*static_cast<int*>(fdPtr),
                                                                         data, size);
                                                return bytesWritten == size;
                                            });

        if (result != ANDROID_BITMAP_RESULT_SUCCESS) {
            fprintf(stderr, "Failed to compress PNG (error code: %d)\n", result);
        }

        if (fn != NULL) {
            notifyMediaScanner(fn);
        }
    } else {
        uint32_t w = buffer->getWidth();
        uint32_t h = buffer->getHeight();
        uint32_t s = buffer->getStride();
        uint32_t f = buffer->getPixelFormat();
        uint32_t c = dataSpaceToInt(dataspace);

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

    return 0;
}

int main(int argc, char** argv)
{
    const std::vector<PhysicalDisplayId> physicalDisplays =
        SurfaceComposerClient::getPhysicalDisplayIds();

    if (physicalDisplays.empty()) {
        fprintf(stderr, "Failed to get ID for any displays.\n");
        return 1;
    }
    std::optional<DisplayId> displayIdOpt;
    std::vector<DisplayId> displaysToCapture;
    gui::CaptureArgs captureArgs;
    const char* pname = argv[0];
    bool png = false;
    bool all = false;
    int c;
    while ((c = getopt_long(argc, argv, "aphd:", LONG_OPTIONS, nullptr)) != -1) {
        switch (c) {
            case 'p':
                png = true;
                break;
            case 'd': {
                errno = 0;
                char* end = nullptr;
                const uint64_t id = strtoull(optarg, &end, 10);
                if (!end || *end != '\0' || errno == ERANGE) {
                    fprintf(stderr, "Invalid display ID: Out of range [0, 2^64).\n");
                    return 1;
                }

                displayIdOpt = DisplayId::fromValue(id);
                if (!displayIdOpt) {
                    fprintf(stderr, "Invalid display ID: Incorrect encoding.\n");
                    return 1;
                }
                displaysToCapture.push_back(displayIdOpt.value());
                break;
            }
            case 'a': {
                all = true;
                break;
            }
            case '?':
            case 'h':
                if (physicalDisplays.size() >= 1) {
                    displayIdOpt = physicalDisplays.front();
                }
                usage(pname, displayIdOpt);
                return 1;
            case LongOpts::HintForSeamless:
                captureArgs.hintForSeamlessTransition = true;
                break;
        }
    }

    argc -= optind;
    argv += optind;

    // We don't expect more than 2 arguments.
    if (argc >= 2) {
        if (physicalDisplays.size() >= 1) {
            usage(pname, physicalDisplays.front());
        } else {
            usage(pname, std::nullopt);
        }
        return 1;
    }

    std::string baseName;
    std::string suffix;

    if (argc == 1) {
        std::string_view filename = { argv[0] };
        if (filename.ends_with(".png")) {
            baseName = filename.substr(0, filename.size()-4);
            suffix = ".png";
            png = true;
        } else {
            baseName = filename;
        }
    }

    if (all) {
        // Ignores -d if -a is given.
        displaysToCapture.clear();
        for (int i = 0; i < physicalDisplays.size(); i++) {
            displaysToCapture.push_back(physicalDisplays[i]);
        }
    }

    if (displaysToCapture.empty()) {
        displaysToCapture.push_back(physicalDisplays.front());
        if (physicalDisplays.size() > 1) {
            fprintf(stderr,
                    "[Warning] Multiple displays were found, but no display id was specified! "
                    "Defaulting to the first display found, however this default is not guaranteed "
                    "to be consistent across captures. A display id should be specified.\n");
            fprintf(stderr, "A display ID can be specified with the [-d display-id] option.\n");
            fprintf(stderr, "See \"dumpsys SurfaceFlinger --display-id\" for valid display IDs.\n");
        }
    }

    // setThreadPoolMaxThreadCount(0) actually tells the kernel it's
    // not allowed to spawn any additional threads, but we still spawn
    // a binder thread from userspace when we call startThreadPool().
    // See b/36066697 for rationale
    ProcessState::self()->setThreadPoolMaxThreadCount(0);
    ProcessState::self()->startThreadPool();

    std::vector<ScreenCaptureResults> results;
    const size_t numDisplays = displaysToCapture.size();
    for (int i=0; i<numDisplays; i++) {
        ScreenCaptureResults result;

        // 1. Capture the screen
        if (const status_t captureStatus =
            capture(displaysToCapture[i], captureArgs, result) != 0) {

            fprintf(stderr, "Capturing failed.\n");
            return captureStatus;
        }

        // 2. Save the capture result as an image.
        // When there's more than one file to capture, add the index as postfix.
        std::string filename;
        if (!baseName.empty()) {
            filename = baseName;
            if (numDisplays > 1) {
                filename += "_";
                filename += std::to_string(i);
            }
            filename += suffix;
        }
        const char* fn = nullptr;
        if (!filename.empty()) {
            fn = filename.c_str();
        }
        if (const status_t saveImageStatus = saveImage(fn, png, result) != 0) {
            fprintf(stderr, "Saving image failed.\n");
            return saveImageStatus;
        }
    }

    return 0;
}
