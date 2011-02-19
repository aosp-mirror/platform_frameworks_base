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

#define LOG_TAG "WVMFileSource"
#include <utils/Log.h>

#include "WVMFileSource.h"
#include "media/stagefright/MediaErrors.h"
#include "media/stagefright/MediaDefs.h"
#include "media/stagefright/MediaDebug.h"

namespace android {


WVMFileSource::WVMFileSource(sp<DataSource> &dataSource)
    : mDataSource(dataSource),
      mOffset(0)
{
}

unsigned long long WVMFileSource::GetSize()
{
    off64_t size;
    mDataSource->getSize(&size);
    return size;
}

unsigned long long WVMFileSource::GetOffset()
{
    return mOffset;
}

void WVMFileSource::Seek(unsigned long long offset)
{
    mOffset = offset;
}

size_t WVMFileSource::Read(size_t amount, unsigned char *buffer)
{
    size_t result = mDataSource->readAt(mOffset, buffer, amount);

#if 0
    // debug code - log packets to files
    char filename[32];
    static int counter = 0;
    sprintf(filename, "/data/wv/buf%d", counter++);
    FILE *f = fopen(filename, "w");
    if (!f)
        LOGE("WVMFileSource: can't open %s", filename);
    else {
        fwrite(buffer, amount, 1, f);
        fclose(f);
    }
    LOGD("WVMFileSource::Read(%d bytes to buf=%p)", amount, buffer);
#endif

    mOffset += result;
    return result;
}

} // namespace android
