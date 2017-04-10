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

#ifndef AAPT_IO_UTIL_H
#define AAPT_IO_UTIL_H

#include <string>

#include "google/protobuf/message_lite.h"

#include "flatten/Archive.h"
#include "io/File.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {
namespace io {

bool CopyInputStreamToArchive(IAaptContext* context, InputStream* in, const std::string& out_path,
                              uint32_t compression_flags, IArchiveWriter* writer);

bool CopyFileToArchive(IAaptContext* context, IFile* file, const std::string& out_path,
                       uint32_t compression_flags, IArchiveWriter* writer);

bool CopyProtoToArchive(IAaptContext* context, ::google::protobuf::MessageLite* proto_msg,
                        const std::string& out_path, uint32_t compression_flags,
                        IArchiveWriter* writer);

// Copies the data from in to out. Returns false if there was an error.
// If there was an error, check the individual streams' HadError/GetError methods.
bool Copy(OutputStream* out, InputStream* in);

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_UTIL_H */
