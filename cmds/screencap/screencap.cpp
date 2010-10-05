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

#include <unistd.h>
#include <fcntl.h>

#include <binder/IMemory.h>
#include <surfaceflinger/SurfaceComposerClient.h>

using namespace android;

int main(int argc, char** argv)
{
    ScreenshotClient screenshot;
    if (screenshot.update() != NO_ERROR)
        return 0;

    void const* base = screenshot.getPixels();
    uint32_t w = screenshot.getWidth();
    uint32_t h = screenshot.getHeight();
    uint32_t f = screenshot.getFormat();
    int fd = dup(STDOUT_FILENO);
    write(fd, &w, 4);
    write(fd, &h, 4);
    write(fd, &f, 4);
    write(fd, base, w*h*4);
    close(fd);
    return 0;
}
