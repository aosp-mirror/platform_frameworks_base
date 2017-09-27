/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "Resource.h"
#include "ResourceTable.h"
#include "StringPool.h"
#include "ValueVisitor.h"
#include "proto/ProtoHelpers.h"
#include "proto/ProtoSerialize.h"
#include "util/BigBuffer.h"

#include "android-base/logging.h"

using ::google::protobuf::io::CodedInputStream;
using ::google::protobuf::io::CodedOutputStream;
using ::google::protobuf::io::ZeroCopyOutputStream;

namespace aapt {

namespace {

class PbSerializerVisitor : public RawValueVisitor {
 public:
  using RawValueVisitor::Visit;

  // Constructor to use when expecting to serialize any value.
  PbSerializerVisitor(StringPool* source_pool, pb::Value* out_pb_value)
      : source_pool_(source_pool), out_pb_value_(out_pb_value), out_pb_item_(nullptr) {
  }

  // Constructor to use when expecting to serialize an Item.
  PbSerializerVisitor(StringPool* sourcePool, pb::Item* outPbItem)
      : source_pool_(sourcePool), out_pb_value_(nullptr), out_pb_item_(outPbItem) {
  }

  void Visit(Reference* ref) override {
    SerializeReferenceToPb(*ref, pb_item()->mutable_ref());
  }

  void Visit(String* str) override {
    pb_item()->mutable_str()->set_value(*str->value);
  }

  void Visit(RawString* str) override {
    pb_item()->mutable_raw_str()->set_value(*str->value);
  }

  void Visit(StyledString* str) override {
    pb::StyledString* pb_str = pb_item()->mutable_styled_str();
    pb_str->set_value(str->value->value);

    for (const StringPool::Span& span : str->value->spans) {
      pb::StyledString::Span* pb_span = pb_str->add_span();
      pb_span->set_tag(*span.name);
      pb_span->set_first_char(span.first_char);
      pb_span->set_last_char(span.last_char);
    }
  }

  void Visit(FileReference* file) override {
    pb_item()->mutable_file()->set_path(*file->path);
  }

  void Visit(Id* /*id*/) override {
    pb_item()->mutable_id();
  }

  void Visit(BinaryPrimitive* prim) override {
    android::Res_value val = {};
    prim->Flatten(&val);

    pb::Primitive* pb_prim = pb_item()->mutable_prim();
    pb_prim->set_type(val.dataType);
    pb_prim->set_data(val.data);
  }

  void VisitItem(Item* item) override {
    LOG(FATAL) << "unimplemented item";
  }

  void Visit(Attribute* attr) override {
    pb::Attribute* pb_attr = pb_compound_value()->mutable_attr();
    pb_attr->set_format_flags(attr->type_mask);
    pb_attr->set_min_int(attr->min_int);
    pb_attr->set_max_int(attr->max_int);

    for (auto& symbol : attr->symbols) {
      pb::Attribute_Symbol* pb_symbol = pb_attr->add_symbol();
      SerializeItemCommonToPb(symbol.symbol, pb_symbol);
      SerializeReferenceToPb(symbol.symbol, pb_symbol->mutable_name());
      pb_symbol->set_value(symbol.value);
    }
  }

  void Visit(Style* style) override {
    pb::Style* pb_style = pb_compound_value()->mutable_style();
    if (style->parent) {
      SerializeReferenceToPb(style->parent.value(), pb_style->mutable_parent());
      SerializeSourceToPb(style->parent.value().GetSource(), source_pool_,
                          pb_style->mutable_parent_source());
    }

    for (Style::Entry& entry : style->entries) {
      pb::Style_Entry* pb_entry = pb_style->add_entry();
      SerializeReferenceToPb(entry.key, pb_entry->mutable_key());

      pb::Item* pb_item = pb_entry->mutable_item();
      SerializeItemCommonToPb(entry.key, pb_entry);
      PbSerializerVisitor sub_visitor(source_pool_, pb_item);
      entry.value->Accept(&sub_visitor);
    }
  }

  void Visit(Styleable* styleable) override {
    pb::Styleable* pb_styleable = pb_compound_value()->mutable_styleable();
    for (Reference& entry : styleable->entries) {
      pb::Styleable_Entry* pb_entry = pb_styleable->add_entry();
      SerializeItemCommonToPb(entry, pb_entry);
      SerializeReferenceToPb(entry, pb_entry->mutable_attr());
    }
  }

  void Visit(Array* array) override {
    pb::Array* pb_array = pb_compound_value()->mutable_array();
    for (auto& value : array->elements) {
      pb::Array_Element* pb_element = pb_array->add_element();
      SerializeItemCommonToPb(*value, pb_element);
      PbSerializerVisitor sub_visitor(source_pool_, pb_element->mutable_item());
      value->Accept(&sub_visitor);
    }
  }

  void Visit(Plural* plural) override {
    pb::Plural* pb_plural = pb_compound_value()->mutable_plural();
    const size_t count = plural->values.size();
    for (size_t i = 0; i < count; i++) {
      if (!plural->values[i]) {
        // No plural value set here.
        continue;
      }

      pb::Plural_Entry* pb_entry = pb_plural->add_entry();
      pb_entry->set_arity(SerializePluralEnumToPb(i));
      pb::Item* pb_element = pb_entry->mutable_item();
      SerializeItemCommonToPb(*plural->values[i], pb_entry);
      PbSerializerVisitor sub_visitor(source_pool_, pb_element);
      plural->values[i]->Accept(&sub_visitor);
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PbSerializerVisitor);

  pb::Item* pb_item() {
    if (out_pb_value_) {
      return out_pb_value_->mutable_item();
    }
    return out_pb_item_;
  }

  pb::CompoundValue* pb_compound_value() {
    CHECK(out_pb_value_ != nullptr);
    return out_pb_value_->mutable_compound_value();
  }

  template <typename T>
  void SerializeItemCommonToPb(const Item& item, T* pb_item) {
    SerializeSourceToPb(item.GetSource(), source_pool_, pb_item->mutable_source());
    pb_item->set_comment(item.GetComment());
  }

  void SerializeReferenceToPb(const Reference& ref, pb::Reference* pb_ref) {
    pb_ref->set_id(ref.id.value_or_default(ResourceId(0x0)).id);

    if (ref.name) {
      pb_ref->set_name(ref.name.value().ToString());
    }

    pb_ref->set_private_(ref.private_reference);
    pb_ref->set_type(SerializeReferenceTypeToPb(ref.reference_type));
  }

  StringPool* source_pool_;
  pb::Value* out_pb_value_;
  pb::Item* out_pb_item_;
};

}  // namespace

std::unique_ptr<pb::ResourceTable> SerializeTableToPb(ResourceTable* table) {
  // We must do this before writing the resources, since the string pool IDs may change.
  table->string_pool.Prune();
  table->string_pool.Sort([](const StringPool::Context& a, const StringPool::Context& b) -> int {
    int diff = util::compare(a.priority, b.priority);
    if (diff == 0) {
      diff = a.config.compare(b.config);
    }
    return diff;
  });

  auto pb_table = util::make_unique<pb::ResourceTable>();
  StringPool source_pool;

  for (auto& package : table->packages) {
    pb::Package* pb_package = pb_table->add_package();
    if (package->id) {
      pb_package->mutable_package_id()->set_id(package->id.value());
    }
    pb_package->set_package_name(package->name);

    for (auto& type : package->types) {
      pb::Type* pb_type = pb_package->add_type();
      if (type->id) {
        pb_type->mutable_type_id()->set_id(type->id.value());
      }
      pb_type->set_name(ToString(type->type).to_string());

      for (auto& entry : type->entries) {
        pb::Entry* pb_entry = pb_type->add_entry();
        if (entry->id) {
          pb_entry->mutable_entry_id()->set_id(entry->id.value());
        }
        pb_entry->set_name(entry->name);

        // Write the SymbolStatus struct.
        pb::SymbolStatus* pb_status = pb_entry->mutable_symbol_status();
        pb_status->set_visibility(SerializeVisibilityToPb(entry->symbol_status.state));
        SerializeSourceToPb(entry->symbol_status.source, &source_pool, pb_status->mutable_source());
        pb_status->set_comment(entry->symbol_status.comment);
        pb_status->set_allow_new(entry->symbol_status.allow_new);

        for (auto& config_value : entry->values) {
          pb::ConfigValue* pb_config_value = pb_entry->add_config_value();
          SerializeConfig(config_value->config, pb_config_value->mutable_config());
          pb_config_value->mutable_config()->set_product(config_value->product);

          pb::Value* pb_value = pb_config_value->mutable_value();
          SerializeSourceToPb(config_value->value->GetSource(), &source_pool,
                              pb_value->mutable_source());
          pb_value->set_comment(config_value->value->GetComment());
          pb_value->set_weak(config_value->value->IsWeak());

          PbSerializerVisitor visitor(&source_pool, pb_value);
          config_value->value->Accept(&visitor);
        }
      }
    }
  }

  SerializeStringPoolToPb(source_pool, pb_table->mutable_source_pool());
  return pb_table;
}

std::unique_ptr<pb::internal::CompiledFile> SerializeCompiledFileToPb(const ResourceFile& file) {
  auto pb_file = util::make_unique<pb::internal::CompiledFile>();
  pb_file->set_resource_name(file.name.ToString());
  pb_file->set_source_path(file.source.path);
  SerializeConfig(file.config, pb_file->mutable_config());

  for (const SourcedResourceName& exported : file.exported_symbols) {
    pb::internal::CompiledFile_Symbol* pb_symbol = pb_file->add_exported_symbol();
    pb_symbol->set_resource_name(exported.name.ToString());
    pb_symbol->mutable_source()->set_line_number(exported.line);
  }
  return pb_file;
}

CompiledFileOutputStream::CompiledFileOutputStream(ZeroCopyOutputStream* out) : out_(out) {
}

void CompiledFileOutputStream::EnsureAlignedWrite() {
  const int overflow = out_.ByteCount() % 4;
  if (overflow > 0) {
    uint32_t zero = 0u;
    out_.WriteRaw(&zero, 4 - overflow);
  }
}

void CompiledFileOutputStream::WriteLittleEndian32(uint32_t val) {
  EnsureAlignedWrite();
  out_.WriteLittleEndian32(val);
}

void CompiledFileOutputStream::WriteCompiledFile(const pb::internal::CompiledFile* compiled_file) {
  EnsureAlignedWrite();
  out_.WriteLittleEndian64(static_cast<uint64_t>(compiled_file->ByteSize()));
  compiled_file->SerializeWithCachedSizes(&out_);
}

void CompiledFileOutputStream::WriteData(const BigBuffer* buffer) {
  EnsureAlignedWrite();
  out_.WriteLittleEndian64(static_cast<uint64_t>(buffer->size()));
  for (const BigBuffer::Block& block : *buffer) {
    out_.WriteRaw(block.buffer.get(), block.size);
  }
}

void CompiledFileOutputStream::WriteData(const void* data, size_t len) {
  EnsureAlignedWrite();
  out_.WriteLittleEndian64(static_cast<uint64_t>(len));
  out_.WriteRaw(data, len);
}

bool CompiledFileOutputStream::HadError() {
  return out_.HadError();
}

CompiledFileInputStream::CompiledFileInputStream(const void* data, size_t size)
    : in_(static_cast<const uint8_t*>(data), size) {}

void CompiledFileInputStream::EnsureAlignedRead() {
  const int overflow = in_.CurrentPosition() % 4;
  if (overflow > 0) {
    // Reads are always 4 byte aligned.
    in_.Skip(4 - overflow);
  }
}

bool CompiledFileInputStream::ReadLittleEndian32(uint32_t* out_val) {
  EnsureAlignedRead();
  return in_.ReadLittleEndian32(out_val);
}

bool CompiledFileInputStream::ReadCompiledFile(pb::internal::CompiledFile* out_val) {
  EnsureAlignedRead();

  google::protobuf::uint64 pb_size = 0u;
  if (!in_.ReadLittleEndian64(&pb_size)) {
    return false;
  }

  CodedInputStream::Limit l = in_.PushLimit(static_cast<int>(pb_size));

  // Check that we haven't tried to read past the end.
  if (static_cast<uint64_t>(in_.BytesUntilLimit()) != pb_size) {
    in_.PopLimit(l);
    in_.PushLimit(0);
    return false;
  }

  if (!out_val->ParsePartialFromCodedStream(&in_)) {
    in_.PopLimit(l);
    in_.PushLimit(0);
    return false;
  }

  in_.PopLimit(l);
  return true;
}

bool CompiledFileInputStream::ReadDataMetaData(uint64_t* out_offset, uint64_t* out_len) {
  EnsureAlignedRead();

  google::protobuf::uint64 pb_size = 0u;
  if (!in_.ReadLittleEndian64(&pb_size)) {
    return false;
  }

  // Check that we aren't trying to read past the end.
  if (pb_size > static_cast<uint64_t>(in_.BytesUntilLimit())) {
    in_.PushLimit(0);
    return false;
  }

  uint64_t offset = static_cast<uint64_t>(in_.CurrentPosition());
  if (!in_.Skip(pb_size)) {
    return false;
  }

  *out_offset = offset;
  *out_len = pb_size;
  return true;
}

}  // namespace aapt
