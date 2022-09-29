/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "format/binary/ResEntryWriter.h"

#include "ValueVisitor.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Util.h"
#include "format/binary/ResourceTypeExtensions.h"

namespace aapt {

using android::BigBuffer;
using android::Res_value;
using android::ResTable_entry;
using android::ResTable_map;

struct less_style_entries {
  bool operator()(const Style::Entry* a, const Style::Entry* b) const {
    if (a->key.id) {
      if (b->key.id) {
        return cmp_ids_dynamic_after_framework(a->key.id.value(), b->key.id.value());
      }
      return true;
    }
    if (!b->key.id) {
      return a->key.name.value() < b->key.name.value();
    }
    return false;
  }
};

class MapFlattenVisitor : public ConstValueVisitor {
 public:
  using ConstValueVisitor::Visit;

  MapFlattenVisitor(ResTable_entry_ext* out_entry, BigBuffer* buffer)
      : out_entry_(out_entry), buffer_(buffer) {
  }

  void Visit(const Attribute* attr) override {
    {
      Reference key = Reference(ResourceId(ResTable_map::ATTR_TYPE));
      BinaryPrimitive val(Res_value::TYPE_INT_DEC, attr->type_mask);
      FlattenEntry(&key, &val);
    }

    if (attr->min_int != std::numeric_limits<int32_t>::min()) {
      Reference key = Reference(ResourceId(ResTable_map::ATTR_MIN));
      BinaryPrimitive val(Res_value::TYPE_INT_DEC, static_cast<uint32_t>(attr->min_int));
      FlattenEntry(&key, &val);
    }

    if (attr->max_int != std::numeric_limits<int32_t>::max()) {
      Reference key = Reference(ResourceId(ResTable_map::ATTR_MAX));
      BinaryPrimitive val(Res_value::TYPE_INT_DEC, static_cast<uint32_t>(attr->max_int));
      FlattenEntry(&key, &val);
    }

    for (const Attribute::Symbol& s : attr->symbols) {
      BinaryPrimitive val(s.type, s.value);
      FlattenEntry(&s.symbol, &val);
    }
  }

  void Visit(const Style* style) override {
    if (style->parent) {
      const Reference& parent_ref = style->parent.value();
      CHECK(bool(parent_ref.id)) << "parent has no ID";
      out_entry_->parent.ident = android::util::HostToDevice32(parent_ref.id.value().id);
    }

    // Sort the style.
    std::vector<const Style::Entry*> sorted_entries;
    for (const auto& entry : style->entries) {
      sorted_entries.emplace_back(&entry);
    }

    std::sort(sorted_entries.begin(), sorted_entries.end(), less_style_entries());

    for (const Style::Entry* entry : sorted_entries) {
      FlattenEntry(&entry->key, entry->value.get());
    }
  }

  void Visit(const Styleable* styleable) override {
    for (auto& attr_ref : styleable->entries) {
      BinaryPrimitive val(Res_value{});
      FlattenEntry(&attr_ref, &val);
    }
  }

  void Visit(const Array* array) override {
    const size_t count = array->elements.size();
    for (size_t i = 0; i < count; i++) {
      Reference key(android::ResTable_map::ATTR_MIN + i);
      FlattenEntry(&key, array->elements[i].get());
    }
  }

  void Visit(const Plural* plural) override {
    const size_t count = plural->values.size();
    for (size_t i = 0; i < count; i++) {
      if (!plural->values[i]) {
        continue;
      }

      ResourceId q;
      switch (i) {
        case Plural::Zero:
          q.id = android::ResTable_map::ATTR_ZERO;
          break;

        case Plural::One:
          q.id = android::ResTable_map::ATTR_ONE;
          break;

        case Plural::Two:
          q.id = android::ResTable_map::ATTR_TWO;
          break;

        case Plural::Few:
          q.id = android::ResTable_map::ATTR_FEW;
          break;

        case Plural::Many:
          q.id = android::ResTable_map::ATTR_MANY;
          break;

        case Plural::Other:
          q.id = android::ResTable_map::ATTR_OTHER;
          break;

        default:
          LOG(FATAL) << "unhandled plural type";
          break;
      }

      Reference key(q);
      FlattenEntry(&key, plural->values[i].get());
    }
  }

  /**
   * Call this after visiting a Value. This will finish any work that
   * needs to be done to prepare the entry.
   */
  void Finish() {
    out_entry_->count = android::util::HostToDevice32(entry_count_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(MapFlattenVisitor);

  void FlattenKey(const Reference* key, ResTable_map* out_entry) {
    CHECK(bool(key->id)) << "key has no ID";
    out_entry->name.ident = android::util::HostToDevice32(key->id.value().id);
  }

  void FlattenValue(const Item* value, ResTable_map* out_entry) {
    CHECK(value->Flatten(&out_entry->value)) << "flatten failed";
  }

  void FlattenEntry(const Reference* key, Item* value) {
    ResTable_map* out_entry = buffer_->NextBlock<ResTable_map>();
    FlattenKey(key, out_entry);
    FlattenValue(value, out_entry);
    out_entry->value.size = android::util::HostToDevice16(sizeof(out_entry->value));
    entry_count_++;
  }

  ResTable_entry_ext* out_entry_;
  BigBuffer* buffer_;
  size_t entry_count_ = 0;
};

template <typename T>
void WriteEntry(const FlatEntry* entry, T* out_result) {
  static_assert(std::is_same_v<ResTable_entry, T> || std::is_same_v<ResTable_entry_ext, T>,
                "T must be ResTable_entry or ResTable_entry_ext");

  ResTable_entry* out_entry = (ResTable_entry*)out_result;
  if (entry->entry->visibility.level == Visibility::Level::kPublic) {
    out_entry->flags |= ResTable_entry::FLAG_PUBLIC;
  }

  if (entry->value->IsWeak()) {
    out_entry->flags |= ResTable_entry::FLAG_WEAK;
  }

  if constexpr (std::is_same_v<ResTable_entry_ext, T>) {
    out_entry->flags |= ResTable_entry::FLAG_COMPLEX;
  }

  out_entry->flags = android::util::HostToDevice16(out_entry->flags);
  out_entry->key.index = android::util::HostToDevice32(entry->entry_key);
  out_entry->size = android::util::HostToDevice16(sizeof(T));
}

int32_t WriteMapToBuffer(const FlatEntry* map_entry, BigBuffer* buffer) {
  int32_t offset = buffer->size();
  ResTable_entry_ext* out_entry = buffer->NextBlock<ResTable_entry_ext>();
  WriteEntry<ResTable_entry_ext>(map_entry, out_entry);

  MapFlattenVisitor visitor(out_entry, buffer);
  map_entry->value->Accept(&visitor);
  visitor.Finish();
  return offset;
}

int32_t SequentialResEntryWriter::WriteMap(const FlatEntry* entry) {
  return WriteMapToBuffer(entry, entries_buffer_);
}

int32_t SequentialResEntryWriter::WriteItem(const FlatEntry* entry) {
  int32_t offset = entries_buffer_->size();
  ResTable_entry* out_entry = entries_buffer_->NextBlock<ResTable_entry>();
  Res_value* out_value = entries_buffer_->NextBlock<Res_value>();
  out_value->size = android::util::HostToDevice16(sizeof(*out_value));

  WriteEntry<ResTable_entry>(entry, out_entry);
  CHECK(ValueCast<Item>(entry->value)->Flatten(out_value)) << "flatten failed";
  return offset;
}

}  // namespace aapt