/*
 **
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#define LOG_TAG "StopWatch"

#include <stdlib.h>
#include <stdio.h>
#include <utils/StopWatch.h>
#include <utils/Log.h>

#include <ui/GraphicBuffer.h>
#include <ui/GraphicBufferMapper.h>

using namespace android;

void* lamecpy(void* d, void const* s, size_t size) {
    char* dst = (char*)d;
    char const* src = (char const*)s;
    while (size) {
        *dst++ = *src++;
        size--;
    }
    return d;
}

int main(int argc, char** argv)
{
    size_t size = 128*256*4;
    void* temp = malloc(size);
    void* temp2 = malloc(size);
    memset(temp, 0, size);
    memset(temp2, 0, size);


    sp<GraphicBuffer> buffer = new GraphicBuffer(128, 256, HAL_PIXEL_FORMAT_RGBA_8888,
            GRALLOC_USAGE_SW_READ_OFTEN |
            GRALLOC_USAGE_SW_WRITE_OFTEN);

    status_t err = buffer->initCheck();
    if (err != NO_ERROR) {
        printf("%s\n", strerror(-err));
        return 0;
    }

    void* vaddr;
    buffer->lock(
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
            &vaddr);

    {
        StopWatch watch("memset");
        for (int i=0 ; i<10 ; i++)
            memset(vaddr, 0, size);
    }

    {
        StopWatch watch("memcpy baseline");
        for (int i=0 ; i<10 ; i++)
            memcpy(temp, temp2, size);
    }

    {
        StopWatch watch("memcpy from gralloc");
        for (int i=0 ; i<10 ; i++)
            memcpy(temp, vaddr, size);
    }

    {
        StopWatch watch("memcpy into gralloc");
        for (int i=0 ; i<10 ; i++)
            memcpy(vaddr, temp, size);
    }


    {
        StopWatch watch("lamecpy baseline");
        for (int i=0 ; i<10 ; i++)
            lamecpy(temp, temp2, size);
    }

    {
        StopWatch watch("lamecpy from gralloc");
        for (int i=0 ; i<10 ; i++)
            lamecpy(temp, vaddr, size);
    }

    {
        StopWatch watch("lamecpy into gralloc");
        for (int i=0 ; i<10 ; i++)
            lamecpy(vaddr, temp, size);
    }

    buffer->unlock();

    return 0;
}
