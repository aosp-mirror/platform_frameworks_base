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

#ifndef WVFILE_SOURCE_H_
#define WVFILE_SOURCE_H_

#include "AndroidConfig.h"
#include "WVStreamControlAPI.h"
#include <media/stagefright/DataSource.h>
#include <utils/RefBase.h>

//
// Supports reading data from local file descriptor instead of URI-based streaming
// as we normally do.
//

namespace android {

class WVMFileSource : public WVFileSource, public RefBase {
public:
    WVMFileSource(sp<DataSource> &dataSource);
    virtual ~WVMFileSource() {}

    virtual unsigned long long GetSize();
    virtual unsigned long long GetOffset();

    virtual void Seek(unsigned long long offset);
    virtual size_t Read(size_t amount, unsigned char *buffer);

private:
    sp<DataSource> mDataSource;
    unsigned long long mOffset;
};

};


#endif
