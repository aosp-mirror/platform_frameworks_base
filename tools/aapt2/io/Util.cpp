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

#include "io/Util.h"

#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

using ::android::StringPiece;
using ::google::protobuf::io::ZeroCopyOutputStream;

namespace aapt {
namespace io {

bool CopyInputStreamToArchive(IAaptContext* context, InputStream* in, const std::string& out_path,
                              uint32_t compression_flags, IArchiveWriter* writer) {
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage() << "writing " << out_path << " to archive");
  }

  if (!writer->WriteFile(out_path, compression_flags, in)) {
    context->GetDiagnostics()->Error(DiagMessage() << "failed to write " << out_path
                                                   << " to archive: " << writer->GetError());
    return false;
  }
  return true;
}

bool CopyFileToArchive(IAaptContext* context, io::IFile* file, const std::string& out_path,
                       uint32_t compression_flags, IArchiveWriter* writer) {
  std::unique_ptr<io::IData> data = file->OpenAsData();
  if (!data) {
    context->GetDiagnostics()->Error(DiagMessage(file->GetSource()) << "failed to open file");
    return false;
  }
  return CopyInputStreamToArchive(context, data.get(), out_path, compression_flags, writer);
}

bool CopyFileToArchivePreserveCompression(IAaptContext* context, io::IFile* file,
                                          const std::string& out_path, IArchiveWriter* writer) {
  uint32_t compression_flags = file->WasCompressed() ? ArchiveEntry::kCompress : 0u;
  return CopyFileToArchive(context, file, out_path, compression_flags, writer);
}

bool CopyProtoToArchive(IAaptContext* context, ::google::protobuf::MessageLite* proto_msg,
                        const std::string& out_path, uint32_t compression_flags,
                        IArchiveWriter* writer) {
  if (context->IsVerbose()) {
    context->GetDiagnostics()->Note(DiagMessage() << "writing " << out_path << " to archive");
  }

  if (writer->StartEntry(out_path, compression_flags)) {
    // Make sure CopyingOutputStreamAdaptor is deleted before we call writer->FinishEntry().
    {
      // Wrap our IArchiveWriter with an adaptor that implements the ZeroCopyOutputStream interface.
      ::google::protobuf::io::CopyingOutputStreamAdaptor adaptor(writer);
      if (!proto_msg->SerializeToZeroCopyStream(&adaptor)) {
        context->GetDiagnostics()->Error(DiagMessage() << "failed to write " << out_path
                                                       << " to archive");
        return false;
      }
    }

    if (writer->FinishEntry()) {
      return true;
    }
  }
  context->GetDiagnostics()->Error(DiagMessage() << "failed to write " << out_path
                                                 << " to archive: " << writer->GetError());
  return false;
}

bool Copy(OutputStream* out, InputStream* in) {
  const void* in_buffer;
  size_t in_len;
  while (in->Next(&in_buffer, &in_len)) {
    void* out_buffer;
    size_t out_len;
    if (!out->Next(&out_buffer, &out_len)) {
      return !out->HadError();
    }

    const size_t bytes_to_copy = in_len < out_len ? in_len : out_len;
    memcpy(out_buffer, in_buffer, bytes_to_copy);
    out->BackUp(out_len - bytes_to_copy);
    in->BackUp(in_len - bytes_to_copy);
  }
  return !in->HadError();
}

bool Copy(OutputStream* out, const StringPiece& in) {
  const char* in_buffer = in.data();
  size_t in_len = in.size();
  while (in_len != 0) {
    void* out_buffer;
    size_t out_len;
    if (!out->Next(&out_buffer, &out_len)) {
      return false;
    }

    const size_t bytes_to_copy = in_len < out_len ? in_len : out_len;
    memcpy(out_buffer, in_buffer, bytes_to_copy);
    out->BackUp(out_len - bytes_to_copy);
    in_buffer += bytes_to_copy;
    in_len -= bytes_to_copy;
  }
  return true;
}

bool Copy(ZeroCopyOutputStream* out, InputStream* in) {
  OutputStreamAdaptor adaptor(out);
  return Copy(&adaptor, in);
}

}  // namespace io
}  // namespace aapt
