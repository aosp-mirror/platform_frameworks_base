/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "proto_util.h"

#include <google/protobuf/io/zero_copy_stream_impl.h>

#include <android/util/protobuf.h>
#include <android/util/ProtoOutputStream.h>
#include <android-base/file.h>

namespace android {
namespace os {
namespace incidentd {

using namespace android::base;
using namespace android::util;
using google::protobuf::io::FileOutputStream;

// special section ids
const int FIELD_ID_INCIDENT_HEADER = 1;

status_t write_header_section(int fd, const uint8_t* buf, size_t bufSize) {
    status_t err;

    if (bufSize == 0) {
        return NO_ERROR;
    }

    err = write_section_header(fd, FIELD_ID_INCIDENT_HEADER, bufSize);
    if (err != NO_ERROR) {
        return err;
    }

    err = WriteFully(fd, buf, bufSize);
    if (err != NO_ERROR) {
        return err;
    }

    return NO_ERROR;
}

status_t write_section_header(int fd, int sectionId, size_t size) {
    uint8_t buf[20];
    uint8_t* p = write_length_delimited_tag_header(buf, sectionId, size);
    return WriteFully(fd, buf, p - buf) ? NO_ERROR : -errno;
}

status_t write_section(int fd, int sectionId, const MessageLite& message) {
    status_t err;

    err = write_section_header(fd, sectionId, message.ByteSize());
    if (err != NO_ERROR) {
        return err;
    }

    FileOutputStream stream(fd);
    if (!message.SerializeToZeroCopyStream(&stream)) {
        return stream.GetErrno();
    } else {
        return NO_ERROR;
    }
}



}  // namespace incidentd
}  // namespace os
}  // namespace android

