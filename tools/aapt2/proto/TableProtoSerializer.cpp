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

using google::protobuf::io::CodedOutputStream;
using google::protobuf::io::CodedInputStream;
using google::protobuf::io::ZeroCopyOutputStream;

namespace aapt {

namespace {

class PbSerializerVisitor : public RawValueVisitor {
 public:
  using RawValueVisitor::Visit;

  /**
   * Constructor to use when expecting to serialize any value.
   */
  PbSerializerVisitor(StringPool* source_pool, StringPool* symbol_pool,
                      pb::Value* out_pb_value)
      : source_pool_(source_pool),
        symbol_pool_(symbol_pool),
        out_pb_value_(out_pb_value),
        out_pb_item_(nullptr) {}

  /**
   * Constructor to use when expecting to serialize an Item.
   */
  PbSerializerVisitor(StringPool* sourcePool, StringPool* symbolPool,
                      pb::Item* outPbItem)
      : source_pool_(sourcePool),
        symbol_pool_(symbolPool),
        out_pb_value_(nullptr),
        out_pb_item_(outPbItem) {}

  void Visit(Reference* ref) override {
    SerializeReferenceToPb(*ref, pb_item()->mutable_ref());
  }

  void Visit(String* str) override {
    pb_item()->mutable_str()->set_idx(str->value.index());
  }

  void Visit(StyledString* str) override {
    pb_item()->mutable_str()->set_idx(str->value.index());
  }

  void Visit(FileReference* file) override {
    pb_item()->mutable_file()->set_path_idx(file->path.index());
  }

  void Visit(Id* id) override { pb_item()->mutable_id(); }

  void Visit(RawString* raw_str) override {
    pb_item()->mutable_raw_str()->set_idx(raw_str->value.index());
  }

  void Visit(BinaryPrimitive* prim) override {
    android::Res_value val = {};
    prim->Flatten(&val);

    pb::Primitive* pb_prim = pb_item()->mutable_prim();
    pb_prim->set_type(val.dataType);
    pb_prim->set_data(val.data);
  }

  void VisitItem(Item* item) override { LOG(FATAL) << "unimplemented item"; }

  void Visit(Attribute* attr) override {
    pb::Attribute* pb_attr = pb_compound_value()->mutable_attr();
    pb_attr->set_format_flags(attr->type_mask);
    pb_attr->set_min_int(attr->min_int);
    pb_attr->set_max_int(attr->max_int);

    for (auto& symbol : attr->symbols) {
      pb::Attribute_Symbol* pb_symbol = pb_attr->add_symbols();
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
      pb::Style_Entry* pb_entry = pb_style->add_entries();
      SerializeReferenceToPb(entry.key, pb_entry->mutable_key());

      pb::Item* pb_item = pb_entry->mutable_item();
      SerializeItemCommonToPb(entry.key, pb_entry);
      PbSerializerVisitor sub_visitor(source_pool_, symbol_pool_, pb_item);
      entry.value->Accept(&sub_visitor);
    }
  }

  void Visit(Styleable* styleable) override {
    pb::Styleable* pb_styleable = pb_compound_value()->mutable_styleable();
    for (Reference& entry : styleable->entries) {
      pb::Styleable_Entry* pb_entry = pb_styleable->add_entries();
      SerializeItemCommonToPb(entry, pb_entry);
      SerializeReferenceToPb(entry, pb_entry->mutable_attr());
    }
  }

  void Visit(Array* array) override {
    pb::Array* pb_array = pb_compound_value()->mutable_array();
    for (auto& value : array->items) {
      pb::Array_Entry* pb_entry = pb_array->add_entries();
      SerializeItemCommonToPb(*value, pb_entry);
      PbSerializerVisitor sub_visitor(source_pool_, symbol_pool_,
                                      pb_entry->mutable_item());
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

      pb::Plural_Entry* pb_entry = pb_plural->add_entries();
      pb_entry->set_arity(SerializePluralEnumToPb(i));
      pb::Item* pb_element = pb_entry->mutable_item();
      SerializeItemCommonToPb(*plural->values[i], pb_entry);
      PbSerializerVisitor sub_visitor(source_pool_, symbol_pool_, pb_element);
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
    SerializeSourceToPb(item.GetSource(), source_pool_,
                        pb_item->mutable_source());
    if (!item.GetComment().empty()) {
      pb_item->set_comment(item.GetComment());
    }
  }

  void SerializeReferenceToPb(const Reference& ref, pb::Reference* pb_ref) {
    if (ref.id) {
      pb_ref->set_id(ref.id.value().id);
    }

    if (ref.name) {
      StringPool::Ref symbol_ref =
          symbol_pool_->MakeRef(ref.name.value().ToString());
      pb_ref->set_symbol_idx(static_cast<uint32_t>(symbol_ref.index()));
    }

    pb_ref->set_private_(ref.private_reference);
    pb_ref->set_type(SerializeReferenceTypeToPb(ref.reference_type));
  }

  StringPool* source_pool_;
  StringPool* symbol_pool_;
  pb::Value* out_pb_value_;
  pb::Item* out_pb_item_;
};

}  // namespace

std::unique_ptr<pb::ResourceTable> SerializeTableToPb(ResourceTable* table) {
  // We must do this before writing the resources, since the string pool IDs may
  // change.
  table->string_pool.Sort(
      [](const StringPool::Entry& a, const StringPool::Entry& b) -> bool {
        int diff = a.context.priority - b.context.priority;
        if (diff < 0) return true;
        if (diff > 0) return false;
        diff = a.context.config.compare(b.context.config);
        if (diff < 0) return true;
        if (diff > 0) return false;
        return a.value < b.value;
      });
  table->string_pool.Prune();

  auto pb_table = util::make_unique<pb::ResourceTable>();
  SerializeStringPoolToPb(table->string_pool, pb_table->mutable_string_pool());

  StringPool source_pool, symbol_pool;

  for (auto& package : table->packages) {
    pb::Package* pb_package = pb_table->add_packages();
    if (package->id) {
      pb_package->set_package_id(package->id.value());
    }
    pb_package->set_package_name(package->name);

    for (auto& type : package->types) {
      pb::Type* pb_type = pb_package->add_types();
      if (type->id) {
        pb_type->set_id(type->id.value());
      }
      pb_type->set_name(ToString(type->type).to_string());

      for (auto& entry : type->entries) {
        pb::Entry* pb_entry = pb_type->add_entries();
        if (entry->id) {
          pb_entry->set_id(entry->id.value());
        }
        pb_entry->set_name(entry->name);

        // Write the SymbolStatus struct.
        pb::SymbolStatus* pb_status = pb_entry->mutable_symbol_status();
        pb_status->set_visibility(SerializeVisibilityToPb(entry->symbol_status.state));
        SerializeSourceToPb(entry->symbol_status.source, &source_pool, pb_status->mutable_source());
        pb_status->set_comment(entry->symbol_status.comment);
        pb_status->set_allow_new(entry->symbol_status.allow_new);

        for (auto& config_value : entry->values) {
          pb::ConfigValue* pb_config_value = pb_entry->add_config_values();
          SerializeConfig(config_value->config, pb_config_value->mutable_config());
          if (!config_value->product.empty()) {
            pb_config_value->mutable_config()->set_product(config_value->product);
          }

          pb::Value* pb_value = pb_config_value->mutable_value();
          SerializeSourceToPb(config_value->value->GetSource(), &source_pool,
                              pb_value->mutable_source());
          if (!config_value->value->GetComment().empty()) {
            pb_value->set_comment(config_value->value->GetComment());
          }

          if (config_value->value->IsWeak()) {
            pb_value->set_weak(true);
          }

          PbSerializerVisitor visitor(&source_pool, &symbol_pool, pb_value);
          config_value->value->Accept(&visitor);
        }
      }
    }
  }

  SerializeStringPoolToPb(source_pool, pb_table->mutable_source_pool());
  SerializeStringPoolToPb(symbol_pool, pb_table->mutable_symbol_pool());
  return pb_table;
}

std::unique_ptr<pb::CompiledFile> SerializeCompiledFileToPb(
    const ResourceFile& file) {
  auto pb_file = util::make_unique<pb::CompiledFile>();
  pb_file->set_resource_name(file.name.ToString());
  pb_file->set_source_path(file.source.path);
  SerializeConfig(file.config, pb_file->mutable_config());

  for (const SourcedResourceName& exported : file.exported_symbols) {
    pb::CompiledFile_Symbol* pb_symbol = pb_file->add_exported_symbols();
    pb_symbol->set_resource_name(exported.name.ToString());
    pb_symbol->set_line_no(exported.line);
  }
  return pb_file;
}

CompiledFileOutputStream::CompiledFileOutputStream(ZeroCopyOutputStream* out)
    : out_(out) {}

void CompiledFileOutputStream::EnsureAlignedWrite() {
  const int padding = out_.ByteCount() % 4;
  if (padding > 0) {
    uint32_t zero = 0u;
    out_.WriteRaw(&zero, padding);
  }
}

void CompiledFileOutputStream::WriteLittleEndian32(uint32_t val) {
  EnsureAlignedWrite();
  out_.WriteLittleEndian32(val);
}

void CompiledFileOutputStream::WriteCompiledFile(
    const pb::CompiledFile* compiled_file) {
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

bool CompiledFileOutputStream::HadError() { return out_.HadError(); }

CompiledFileInputStream::CompiledFileInputStream(const void* data, size_t size)
    : in_(static_cast<const uint8_t*>(data), size) {}

void CompiledFileInputStream::EnsureAlignedRead() {
  const int padding = in_.CurrentPosition() % 4;
  if (padding > 0) {
    // Reads are always 4 byte aligned.
    in_.Skip(padding);
  }
}

bool CompiledFileInputStream::ReadLittleEndian32(uint32_t* out_val) {
  EnsureAlignedRead();
  return in_.ReadLittleEndian32(out_val);
}

bool CompiledFileInputStream::ReadCompiledFile(pb::CompiledFile* out_val) {
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

bool CompiledFileInputStream::ReadDataMetaData(uint64_t* out_offset,
                                               uint64_t* out_len) {
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
