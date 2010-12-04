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

#include <binder/IMemory.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <SkImageEncoder.h>
#include <SkBitmap.h>
#include <SkStream.h>

using namespace android;

static void usage()
{
    fprintf(stderr,
            "usage: screenshot [-hp] [FILENAME]\n"
            "   -h: this message\n"
            "   -p: save the file as a png.\n"
            "If FILENAME ends with .png it will be saved as a png.\n"
            "If FILENAME is not given, the results will be printed to stdout.\n"
    );
}

static SkBitmap::Config flinger2skia(PixelFormat f)
{
    switch (f) {
        case PIXEL_FORMAT_A_8:
        case PIXEL_FORMAT_L_8:
            return SkBitmap::kA8_Config;
        case PIXEL_FORMAT_RGB_565:
            return SkBitmap::kRGB_565_Config;
        case PIXEL_FORMAT_RGBA_4444:
            return SkBitmap::kARGB_4444_Config;
        default:
            return SkBitmap::kARGB_8888_Config;
    }
}

int main(int argc, char** argv)
{
    bool png = false;
    int c;
    while ((c = getopt(argc, argv, "ph")) != -1) {
        switch (c) {
            case 'p':
                png = true;
                break;
            case '?':
            case 'h':
                usage();
                return 1;
        }
    }
    argc -= optind;
    argv += optind;

    int fd = -1;
    if (argc == 0) {
        fd = dup(STDOUT_FILENO);
    } else if (argc == 1) {
        const char* fn = argv[0];
        fd = open(fn, O_WRONLY | O_CREAT | O_TRUNC, 0664);
        if (fd == -1) {
            fprintf(stderr, "Error opening file: (%d) %s\n", errno, strerror(errno));
            return 1;
        }
        const int len = strlen(fn);
        if (len >= 4 && 0 == strcmp(fn+len-4, ".png")) {
            png = true;
        }
    }
    
    if (fd == -1) {
        usage();
        return 1;
    }

    ScreenshotClient screenshot;
    if (screenshot.update() != NO_ERROR) {
        return 0;
    }

    void const* base = screenshot.getPixels();
    uint32_t w = screenshot.getWidth();
    uint32_t h = screenshot.getHeight();
    uint32_t f = screenshot.getFormat();

    if (png) {
        SkBitmap b;
        b.setConfig(flinger2skia(f), w, h);
        b.setPixels((void*)base);
        SkDynamicMemoryWStream stream;
        SkImageEncoder::EncodeStream(&stream, b,
                SkImageEncoder::kPNG_Type, SkImageEncoder::kDefaultQuality);
        write(fd, stream.getStream(), stream.getOffset());
    } else {
        write(fd, &w, 4);
        write(fd, &h, 4);
        write(fd, &f, 4);
        write(fd, base, w*h*4);
    }
    close(fd);
    return 0;
}
