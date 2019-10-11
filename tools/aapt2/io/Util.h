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
#include "google/protobuf/io/coded_stream.h"

#include "format/Archive.h"
#include "io/File.h"
#include "io/Io.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {
namespace io {

bool CopyInputStreamToArchive(IAaptContext* context, InputStream* in, const std::string& out_path,
                              uint32_t compression_flags, IArchiveWriter* writer);

bool CopyFileToArchive(IAaptContext* context, IFile* file, const std::string& out_path,
                       uint32_t compression_flags, IArchiveWriter* writer);

bool CopyFileToArchivePreserveCompression(IAaptContext* context, IFile* file,
                                          const std::string& out_path, IArchiveWriter* writer);

bool CopyProtoToArchive(IAaptContext* context, ::google::protobuf::MessageLite* proto_msg,
                        const std::string& out_path, uint32_t compression_flags,
                        IArchiveWriter* writer);

// Copies the data from in to out. Returns false if there was an error.
// If there was an error, check the individual streams' HadError/GetError methods.
bool Copy(OutputStream* out, InputStream* in);
bool Copy(OutputStream* out, const ::android::StringPiece& in);
bool Copy(::google::protobuf::io::ZeroCopyOutputStream* out, InputStream* in);

class OutputStreamAdaptor : public io::OutputStream {
 public:
  explicit OutputStreamAdaptor(::google::protobuf::io::ZeroCopyOutputStream* out) : out_(out) {
  }

  bool Next(void** data, size_t* size) override {
    int out_size;
    bool result = out_->Next(data, &out_size);
    *size = static_cast<size_t>(out_size);
    if (!result) {
      error_ocurred_ = true;
    }
    return result;
  }

  void BackUp(size_t count) override {
    out_->BackUp(static_cast<int>(count));
  }

  size_t ByteCount() const override {
    return static_cast<size_t>(out_->ByteCount());
  }

  bool HadError() const override {
    return error_ocurred_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(OutputStreamAdaptor);

  ::google::protobuf::io::ZeroCopyOutputStream* out_;
  bool error_ocurred_ = false;
};

class ZeroCopyInputAdaptor : public ::google::protobuf::io::ZeroCopyInputStream {
 public:
  explicit ZeroCopyInputAdaptor(io::InputStream* in) : in_(in) {
  }

  bool Next(const void** data, int* size) override {
    size_t out_size;
    bool result = in_->Next(data, &out_size);
    *size = static_cast<int>(out_size);
    return result;
  }

  void BackUp(int count) override {
    in_->BackUp(static_cast<size_t>(count));
  }

  bool Skip(int count) override {
    const void* data;
    int size;
    while (Next(&data, &size)) {
      if (size > count) {
        BackUp(size - count);
        return true;
      } else {
        count -= size;
      }
    }
    return false;
  }

  ::google::protobuf::int64 ByteCount() const override {
    return static_cast<::google::protobuf::int64>(in_->ByteCount());
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ZeroCopyInputAdaptor);

  io::InputStream* in_;
};

class ProtoInputStreamReader {
 public:
  explicit ProtoInputStreamReader(io::InputStream* in) : in_(in) { }

  /** Deserializes a MessageLite proto from the current position in the input stream.*/
  template <typename T> bool ReadMessage(T *message_lite) {
    ZeroCopyInputAdaptor adapter(in_);
    google::protobuf::io::CodedInputStream coded_stream(&adapter);
    coded_stream.SetTotalBytesLimit(std::numeric_limits<int32_t>::max(),
                                    coded_stream.BytesUntilTotalBytesLimit());
    return message_lite->ParseFromCodedStream(&coded_stream);
  }

 private:
  io::InputStream* in_;
};

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_UTIL_H */
