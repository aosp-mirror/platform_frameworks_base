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

#include "format/Container.h"

#include "android-base/scopeguard.h"
#include "android-base/stringprintf.h"

#include "trace/TraceBuffer.h"

using ::android::base::StringPrintf;
using ::google::protobuf::io::CodedInputStream;
using ::google::protobuf::io::CodedOutputStream;
using ::google::protobuf::io::ZeroCopyOutputStream;

namespace aapt {

constexpr const static uint32_t kContainerFormatMagic = 0x54504141u;
constexpr const static uint32_t kContainerFormatVersion = 1u;
constexpr const static size_t kPaddingAlignment = 4u;

ContainerWriter::ContainerWriter(ZeroCopyOutputStream* out, size_t entry_count)
    : out_(out), total_entry_count_(entry_count), current_entry_count_(0u) {
  CodedOutputStream coded_out(out_);

  // Write the magic.
  coded_out.WriteLittleEndian32(kContainerFormatMagic);

  // Write the version.
  coded_out.WriteLittleEndian32(kContainerFormatVersion);

  // Write the total number of entries.
  coded_out.WriteLittleEndian32(static_cast<uint32_t>(total_entry_count_));

  if (coded_out.HadError()) {
    error_ = "failed writing container format header";
  }
}

inline static size_t CalculatePaddingForAlignment(size_t size) {
  size_t overage = size % kPaddingAlignment;
  return overage == 0 ? 0 : kPaddingAlignment - overage;
}

inline static void WritePadding(size_t padding, CodedOutputStream* out) {
  CHECK(padding < kPaddingAlignment);
  const uint32_t zero = 0u;
  static_assert(sizeof(zero) >= kPaddingAlignment, "Not enough source bytes for padding");

  out->WriteRaw(&zero, padding);
}

bool ContainerWriter::AddResTableEntry(const pb::ResourceTable& table) {
  if (current_entry_count_ >= total_entry_count_) {
    error_ = "too many entries being serialized";
    return false;
  }
  current_entry_count_++;

  CodedOutputStream coded_out(out_);

  // Write the type.
  coded_out.WriteLittleEndian32(kResTable);

  // Write the aligned size.
  const ::google::protobuf::uint64 size = table.ByteSize();
  const int padding = CalculatePaddingForAlignment(size);
  coded_out.WriteLittleEndian64(size);

  // Write the table.
  table.SerializeWithCachedSizes(&coded_out);

  // Write the padding.
  WritePadding(padding, &coded_out);

  if (coded_out.HadError()) {
    error_ = "failed writing to output";
    return false;
  }
  return true;
}

bool ContainerWriter::AddResFileEntry(const pb::internal::CompiledFile& file,
                                      io::KnownSizeInputStream* in) {
  if (current_entry_count_ >= total_entry_count_) {
    error_ = "too many entries being serialized";
    return false;
  }
  current_entry_count_++;

  constexpr const static int kResFileEntryHeaderSize = 12;

  CodedOutputStream coded_out(out_);

  // Write the type.
  coded_out.WriteLittleEndian32(kResFile);

  // Write the aligned size.
  const ::google::protobuf::uint32 header_size = file.ByteSize();
  const int header_padding = CalculatePaddingForAlignment(header_size);
  const ::google::protobuf::uint64 data_size = in->TotalSize();
  const int data_padding = CalculatePaddingForAlignment(data_size);
  coded_out.WriteLittleEndian64(kResFileEntryHeaderSize + header_size + header_padding + data_size +
                                data_padding);

  // Write the res file header size.
  coded_out.WriteLittleEndian32(header_size);

  // Write the data payload size.
  coded_out.WriteLittleEndian64(data_size);

  // Write the header.
  file.SerializeToCodedStream(&coded_out);

  WritePadding(header_padding, &coded_out);

  // Write the data payload. We need to call Trim() since we are going to write to the underlying
  // ZeroCopyOutputStream.
  coded_out.Trim();

  // Check at this point if there were any errors.
  if (coded_out.HadError()) {
    error_ = "failed writing to output";
    return false;
  }

  if (!io::Copy(out_, in)) {
    if (in->HadError()) {
      std::ostringstream error;
      error << "failed reading from input: " << in->GetError();
      error_ = error.str();
    } else {
      error_ = "failed writing to output";
    }
    return false;
  }
  WritePadding(data_padding, &coded_out);

  if (coded_out.HadError()) {
    error_ = "failed writing to output";
    return false;
  }
  return true;
}

bool ContainerWriter::HadError() const {
  return !error_.empty();
}

std::string ContainerWriter::GetError() const {
  return error_;
}

static bool AlignRead(CodedInputStream* in) {
  const int padding = 4 - (in->CurrentPosition() % 4);
  if (padding < 4) {
    return in->Skip(padding);
  }
  return true;
}

ContainerReaderEntry::ContainerReaderEntry(ContainerReader* reader) : reader_(reader) {
}

ContainerEntryType ContainerReaderEntry::Type() const {
  return type_;
}

bool ContainerReaderEntry::GetResTable(pb::ResourceTable* out_table) {
  TRACE_CALL();
  CHECK(type_ == ContainerEntryType::kResTable) << "reading a kResTable when the type is kResFile";
  if (length_ > std::numeric_limits<int>::max()) {
    reader_->error_ = StringPrintf("entry length %zu is too large", length_);
    return false;
  }

  CodedInputStream& coded_in = reader_->coded_in_;

  const CodedInputStream::Limit limit = coded_in.PushLimit(static_cast<int>(length_));
  auto guard = ::android::base::make_scope_guard([&]() { coded_in.PopLimit(limit); });

  if (!out_table->ParseFromCodedStream(&coded_in)) {
    reader_->error_ = "failed to parse ResourceTable";
    return false;
  }
  return true;
}

bool ContainerReaderEntry::GetResFileOffsets(pb::internal::CompiledFile* out_file,
                                             off64_t* out_offset, size_t* out_len) {
  CHECK(type_ == ContainerEntryType::kResFile) << "reading a kResFile when the type is kResTable";

  CodedInputStream& coded_in = reader_->coded_in_;

  // Read the ResFile header.
  ::google::protobuf::uint32 header_length;
  if (!coded_in.ReadLittleEndian32(&header_length)) {
    std::ostringstream error;
    error << "failed to read header length from input: " << reader_->in_->GetError();
    reader_->error_ = error.str();
    return false;
  }

  ::google::protobuf::uint64 data_length;
  if (!coded_in.ReadLittleEndian64(&data_length)) {
    std::ostringstream error;
    error << "failed to read data length from input: " << reader_->in_->GetError();
    reader_->error_ = error.str();
    return false;
  }

  if (header_length > std::numeric_limits<int>::max()) {
    std::ostringstream error;
    error << "header length " << header_length << " is too large";
    reader_->error_ = error.str();
    return false;
  }

  if (data_length > std::numeric_limits<size_t>::max()) {
    std::ostringstream error;
    error << "data length " << data_length << " is too large";
    reader_->error_ = error.str();
    return false;
  }

  {
    const CodedInputStream::Limit limit = coded_in.PushLimit(static_cast<int>(header_length));
    auto guard = ::android::base::make_scope_guard([&]() { coded_in.PopLimit(limit); });

    if (!out_file->ParseFromCodedStream(&coded_in)) {
      reader_->error_ = "failed to parse CompiledFile header";
      return false;
    }
  }

  AlignRead(&coded_in);

  *out_offset = coded_in.CurrentPosition();
  *out_len = data_length;

  coded_in.Skip(static_cast<int>(data_length));
  AlignRead(&coded_in);
  return true;
}

bool ContainerReaderEntry::HadError() const {
  return reader_->HadError();
}

std::string ContainerReaderEntry::GetError() const {
  return reader_->GetError();
}

ContainerReader::ContainerReader(io::InputStream* in)
    : in_(in),
      adaptor_(in),
      coded_in_(&adaptor_),
      total_entry_count_(0u),
      current_entry_count_(0u),
      entry_(this) {
  TRACE_CALL();
  ::google::protobuf::uint32 magic;
  if (!coded_in_.ReadLittleEndian32(&magic)) {
    std::ostringstream error;
    error << "failed to read magic from input: " << in_->GetError();
    error_ = error.str();
    return;
  }

  if (magic != kContainerFormatMagic) {
    error_ =
        StringPrintf("magic value is 0x%08x but AAPT expects 0x%08x", magic, kContainerFormatMagic);
    return;
  }

  ::google::protobuf::uint32 version;
  if (!coded_in_.ReadLittleEndian32(&version)) {
    std::ostringstream error;
    error << "failed to read version from input: " << in_->GetError();
    error_ = error.str();
    return;
  }

  if (version != kContainerFormatVersion) {
    error_ = StringPrintf("container version is 0x%08x but AAPT expects version 0x%08x", version,
                          kContainerFormatVersion);
    return;
  }

  ::google::protobuf::uint32 total_entry_count;
  if (!coded_in_.ReadLittleEndian32(&total_entry_count)) {
    std::ostringstream error;
    error << "failed to read entry count from input: " << in_->GetError();
    error_ = error.str();
    return;
  }

  total_entry_count_ = total_entry_count;
}

ContainerReaderEntry* ContainerReader::Next() {
  if (current_entry_count_ >= total_entry_count_) {
    return nullptr;
  }
  current_entry_count_++;

  // Ensure the next read is aligned.
  AlignRead(&coded_in_);

  ::google::protobuf::uint32 entry_type;
  if (!coded_in_.ReadLittleEndian32(&entry_type)) {
    std::ostringstream error;
    error << "failed reading entry type from input: " << in_->GetError();
    error_ = error.str();
    return nullptr;
  }

  ::google::protobuf::uint64 entry_length;
  if (!coded_in_.ReadLittleEndian64(&entry_length)) {
    std::ostringstream error;
    error << "failed reading entry length from input: " << in_->GetError();
    error_ = error.str();
    return nullptr;
  }

  if (entry_type == ContainerEntryType::kResFile || entry_type == ContainerEntryType::kResTable) {
    entry_.type_ = static_cast<ContainerEntryType>(entry_type);
  } else {
    error_ = StringPrintf("entry type 0x%08x is invalid", entry_type);
    return nullptr;
  }

  if (entry_length > std::numeric_limits<size_t>::max()) {
    std::ostringstream error;
    error << "entry length " << entry_length << " is too large";
    error_ = error.str();
    return nullptr;
  }

  entry_.length_ = entry_length;
  return &entry_;
}

bool ContainerReader::HadError() const {
  return !error_.empty();
}

std::string ContainerReader::GetError() const {
  return error_;
}

}  // namespace aapt
