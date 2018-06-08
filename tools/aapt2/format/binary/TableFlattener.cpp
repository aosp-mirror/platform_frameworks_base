/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "format/binary/TableFlattener.h"

#include <algorithm>
#include <numeric>
#include <sstream>
#include <type_traits>

#include "android-base/logging.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "ValueVisitor.h"
#include "format/binary/ChunkWriter.h"
#include "format/binary/ResourceTypeExtensions.h"
#include "util/BigBuffer.h"

using namespace android;

namespace aapt {

namespace {

template <typename T>
static bool cmp_ids(const T* a, const T* b) {
  return a->id.value() < b->id.value();
}

static void strcpy16_htod(uint16_t* dst, size_t len, const StringPiece16& src) {
  if (len == 0) {
    return;
  }

  size_t i;
  const char16_t* src_data = src.data();
  for (i = 0; i < len - 1 && i < src.size(); i++) {
    dst[i] = util::HostToDevice16((uint16_t)src_data[i]);
  }
  dst[i] = 0;
}

static bool cmp_style_entries(const Style::Entry& a, const Style::Entry& b) {
  if (a.key.id) {
    if (b.key.id) {
      return a.key.id.value() < b.key.id.value();
    }
    return true;
  } else if (!b.key.id) {
    return a.key.name.value() < b.key.name.value();
  }
  return false;
}

struct FlatEntry {
  ResourceEntry* entry;
  Value* value;

  // The entry string pool index to the entry's name.
  uint32_t entry_key;
};

class MapFlattenVisitor : public ValueVisitor {
 public:
  using ValueVisitor::Visit;

  MapFlattenVisitor(ResTable_entry_ext* out_entry, BigBuffer* buffer)
      : out_entry_(out_entry), buffer_(buffer) {
  }

  void Visit(Attribute* attr) override {
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

    for (Attribute::Symbol& s : attr->symbols) {
      BinaryPrimitive val(Res_value::TYPE_INT_DEC, s.value);
      FlattenEntry(&s.symbol, &val);
    }
  }

  void Visit(Style* style) override {
    if (style->parent) {
      const Reference& parent_ref = style->parent.value();
      CHECK(bool(parent_ref.id)) << "parent has no ID";
      out_entry_->parent.ident = util::HostToDevice32(parent_ref.id.value().id);
    }

    // Sort the style.
    std::sort(style->entries.begin(), style->entries.end(), cmp_style_entries);

    for (Style::Entry& entry : style->entries) {
      FlattenEntry(&entry.key, entry.value.get());
    }
  }

  void Visit(Styleable* styleable) override {
    for (auto& attr_ref : styleable->entries) {
      BinaryPrimitive val(Res_value{});
      FlattenEntry(&attr_ref, &val);
    }
  }

  void Visit(Array* array) override {
    for (auto& item : array->elements) {
      ResTable_map* out_entry = buffer_->NextBlock<ResTable_map>();
      FlattenValue(item.get(), out_entry);
      out_entry->value.size = util::HostToDevice16(sizeof(out_entry->value));
      entry_count_++;
    }
  }

  void Visit(Plural* plural) override {
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
    out_entry_->count = util::HostToDevice32(entry_count_);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(MapFlattenVisitor);

  void FlattenKey(Reference* key, ResTable_map* out_entry) {
    CHECK(bool(key->id)) << "key has no ID";
    out_entry->name.ident = util::HostToDevice32(key->id.value().id);
  }

  void FlattenValue(Item* value, ResTable_map* out_entry) {
    CHECK(value->Flatten(&out_entry->value)) << "flatten failed";
  }

  void FlattenEntry(Reference* key, Item* value) {
    ResTable_map* out_entry = buffer_->NextBlock<ResTable_map>();
    FlattenKey(key, out_entry);
    FlattenValue(value, out_entry);
    out_entry->value.size = util::HostToDevice16(sizeof(out_entry->value));
    entry_count_++;
  }

  ResTable_entry_ext* out_entry_;
  BigBuffer* buffer_;
  size_t entry_count_ = 0;
};

class PackageFlattener {
 public:
  PackageFlattener(IAaptContext* context, ResourceTablePackage* package,
                   const std::map<size_t, std::string>* shared_libs, bool use_sparse_entries,
                   bool collapse_key_stringpool, const std::set<std::string>& whitelisted_resources)
      : context_(context),
        diag_(context->GetDiagnostics()),
        package_(package),
        shared_libs_(shared_libs),
        use_sparse_entries_(use_sparse_entries),
        collapse_key_stringpool_(collapse_key_stringpool),
        whitelisted_resources_(whitelisted_resources) {
  }

  bool FlattenPackage(BigBuffer* buffer) {
    ChunkWriter pkg_writer(buffer);
    ResTable_package* pkg_header = pkg_writer.StartChunk<ResTable_package>(RES_TABLE_PACKAGE_TYPE);
    pkg_header->id = util::HostToDevice32(package_->id.value());

    // AAPT truncated the package name, so do the same.
    // Shared libraries require full package names, so don't truncate theirs.
    if (context_->GetPackageType() != PackageType::kApp &&
        package_->name.size() >= arraysize(pkg_header->name)) {
      diag_->Error(DiagMessage() << "package name '" << package_->name
                                 << "' is too long. "
                                    "Shared libraries cannot have truncated package names");
      return false;
    }

    // Copy the package name in device endianness.
    strcpy16_htod(pkg_header->name, arraysize(pkg_header->name), util::Utf8ToUtf16(package_->name));

    // Serialize the types. We do this now so that our type and key strings
    // are populated. We write those first.
    BigBuffer type_buffer(1024);
    FlattenTypes(&type_buffer);

    pkg_header->typeStrings = util::HostToDevice32(pkg_writer.size());
    StringPool::FlattenUtf16(pkg_writer.buffer(), type_pool_, diag_);

    pkg_header->keyStrings = util::HostToDevice32(pkg_writer.size());
    StringPool::FlattenUtf8(pkg_writer.buffer(), key_pool_, diag_);

    // Append the types.
    buffer->AppendBuffer(std::move(type_buffer));

    // If there are libraries (or if the package ID is 0x00), encode a library chunk.
    if (package_->id.value() == 0x00 || !shared_libs_->empty()) {
      FlattenLibrarySpec(buffer);
    }

    pkg_writer.Finish();
    return true;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PackageFlattener);

  template <typename T, bool IsItem>
  T* WriteEntry(FlatEntry* entry, BigBuffer* buffer) {
    static_assert(
        std::is_same<ResTable_entry, T>::value || std::is_same<ResTable_entry_ext, T>::value,
        "T must be ResTable_entry or ResTable_entry_ext");

    T* result = buffer->NextBlock<T>();
    ResTable_entry* out_entry = (ResTable_entry*)result;
    if (entry->entry->visibility.level == Visibility::Level::kPublic) {
      out_entry->flags |= ResTable_entry::FLAG_PUBLIC;
    }

    if (entry->value->IsWeak()) {
      out_entry->flags |= ResTable_entry::FLAG_WEAK;
    }

    if (!IsItem) {
      out_entry->flags |= ResTable_entry::FLAG_COMPLEX;
    }

    out_entry->flags = util::HostToDevice16(out_entry->flags);
    out_entry->key.index = util::HostToDevice32(entry->entry_key);
    out_entry->size = util::HostToDevice16(sizeof(T));
    return result;
  }

  bool FlattenValue(FlatEntry* entry, BigBuffer* buffer) {
    if (Item* item = ValueCast<Item>(entry->value)) {
      WriteEntry<ResTable_entry, true>(entry, buffer);
      Res_value* outValue = buffer->NextBlock<Res_value>();
      CHECK(item->Flatten(outValue)) << "flatten failed";
      outValue->size = util::HostToDevice16(sizeof(*outValue));
    } else {
      ResTable_entry_ext* out_entry = WriteEntry<ResTable_entry_ext, false>(entry, buffer);
      MapFlattenVisitor visitor(out_entry, buffer);
      entry->value->Accept(&visitor);
      visitor.Finish();
    }
    return true;
  }

  bool FlattenConfig(const ResourceTableType* type, const ConfigDescription& config,
                     const size_t num_total_entries, std::vector<FlatEntry>* entries,
                     BigBuffer* buffer) {
    CHECK(num_total_entries != 0);
    CHECK(num_total_entries <= std::numeric_limits<uint16_t>::max());

    ChunkWriter type_writer(buffer);
    ResTable_type* type_header = type_writer.StartChunk<ResTable_type>(RES_TABLE_TYPE_TYPE);
    type_header->id = type->id.value();
    type_header->config = config;
    type_header->config.swapHtoD();

    std::vector<uint32_t> offsets;
    offsets.resize(num_total_entries, 0xffffffffu);

    BigBuffer values_buffer(512);
    for (FlatEntry& flat_entry : *entries) {
      CHECK(static_cast<size_t>(flat_entry.entry->id.value()) < num_total_entries);
      offsets[flat_entry.entry->id.value()] = values_buffer.size();
      if (!FlattenValue(&flat_entry, &values_buffer)) {
        diag_->Error(DiagMessage()
                     << "failed to flatten resource '"
                     << ResourceNameRef(package_->name, type->type, flat_entry.entry->name)
                     << "' for configuration '" << config << "'");
        return false;
      }
    }

    bool sparse_encode = use_sparse_entries_;

    // Only sparse encode if the entries will be read on platforms O+.
    sparse_encode =
        sparse_encode && (context_->GetMinSdkVersion() >= SDK_O || config.sdkVersion >= SDK_O);

    // Only sparse encode if the offsets are representable in 2 bytes.
    sparse_encode =
        sparse_encode && (values_buffer.size() / 4u) <= std::numeric_limits<uint16_t>::max();

    // Only sparse encode if the ratio of populated entries to total entries is below some
    // threshold.
    sparse_encode =
        sparse_encode && ((100 * entries->size()) / num_total_entries) < kSparseEncodingThreshold;

    if (sparse_encode) {
      type_header->entryCount = util::HostToDevice32(entries->size());
      type_header->flags |= ResTable_type::FLAG_SPARSE;
      ResTable_sparseTypeEntry* indices =
          type_writer.NextBlock<ResTable_sparseTypeEntry>(entries->size());
      for (size_t i = 0; i < num_total_entries; i++) {
        if (offsets[i] != ResTable_type::NO_ENTRY) {
          CHECK((offsets[i] & 0x03) == 0);
          indices->idx = util::HostToDevice16(i);
          indices->offset = util::HostToDevice16(offsets[i] / 4u);
          indices++;
        }
      }
    } else {
      type_header->entryCount = util::HostToDevice32(num_total_entries);
      uint32_t* indices = type_writer.NextBlock<uint32_t>(num_total_entries);
      for (size_t i = 0; i < num_total_entries; i++) {
        indices[i] = util::HostToDevice32(offsets[i]);
      }
    }

    type_header->entriesStart = util::HostToDevice32(type_writer.size());
    type_writer.buffer()->AppendBuffer(std::move(values_buffer));
    type_writer.Finish();
    return true;
  }

  std::vector<ResourceTableType*> CollectAndSortTypes() {
    std::vector<ResourceTableType*> sorted_types;
    for (auto& type : package_->types) {
      if (type->type == ResourceType::kStyleable) {
        // Styleables aren't real Resource Types, they are represented in the
        // R.java file.
        continue;
      }

      CHECK(bool(type->id)) << "type must have an ID set";

      sorted_types.push_back(type.get());
    }
    std::sort(sorted_types.begin(), sorted_types.end(), cmp_ids<ResourceTableType>);
    return sorted_types;
  }

  std::vector<ResourceEntry*> CollectAndSortEntries(ResourceTableType* type) {
    // Sort the entries by entry ID.
    std::vector<ResourceEntry*> sorted_entries;
    for (auto& entry : type->entries) {
      CHECK(bool(entry->id)) << "entry must have an ID set";
      sorted_entries.push_back(entry.get());
    }
    std::sort(sorted_entries.begin(), sorted_entries.end(), cmp_ids<ResourceEntry>);
    return sorted_entries;
  }

  bool FlattenTypeSpec(ResourceTableType* type, std::vector<ResourceEntry*>* sorted_entries,
                       BigBuffer* buffer) {
    ChunkWriter type_spec_writer(buffer);
    ResTable_typeSpec* spec_header =
        type_spec_writer.StartChunk<ResTable_typeSpec>(RES_TABLE_TYPE_SPEC_TYPE);
    spec_header->id = type->id.value();

    if (sorted_entries->empty()) {
      type_spec_writer.Finish();
      return true;
    }

    // We can't just take the size of the vector. There may be holes in the
    // entry ID space.
    // Since the entries are sorted by ID, the last one will be the biggest.
    const size_t num_entries = sorted_entries->back()->id.value() + 1;

    spec_header->entryCount = util::HostToDevice32(num_entries);

    // Reserve space for the masks of each resource in this type. These
    // show for which configuration axis the resource changes.
    uint32_t* config_masks = type_spec_writer.NextBlock<uint32_t>(num_entries);

    const size_t actual_num_entries = sorted_entries->size();
    for (size_t entryIndex = 0; entryIndex < actual_num_entries; entryIndex++) {
      ResourceEntry* entry = sorted_entries->at(entryIndex);

      // Populate the config masks for this entry.

      if (entry->visibility.level == Visibility::Level::kPublic) {
        config_masks[entry->id.value()] |= util::HostToDevice32(ResTable_typeSpec::SPEC_PUBLIC);
      }

      if (entry->overlayable) {
        config_masks[entry->id.value()] |=
            util::HostToDevice32(ResTable_typeSpec::SPEC_OVERLAYABLE);
      }

      const size_t config_count = entry->values.size();
      for (size_t i = 0; i < config_count; i++) {
        const ConfigDescription& config = entry->values[i]->config;
        for (size_t j = i + 1; j < config_count; j++) {
          config_masks[entry->id.value()] |=
              util::HostToDevice32(config.diff(entry->values[j]->config));
        }
      }
    }
    type_spec_writer.Finish();
    return true;
  }

  bool FlattenTypes(BigBuffer* buffer) {
    // Sort the types by their IDs. They will be inserted into the StringPool in
    // this order.
    std::vector<ResourceTableType*> sorted_types = CollectAndSortTypes();

    size_t expected_type_id = 1;
    for (ResourceTableType* type : sorted_types) {
      // If there is a gap in the type IDs, fill in the StringPool
      // with empty values until we reach the ID we expect.
      while (type->id.value() > expected_type_id) {
        std::stringstream type_name;
        type_name << "?" << expected_type_id;
        type_pool_.MakeRef(type_name.str());
        expected_type_id++;
      }
      expected_type_id++;
      type_pool_.MakeRef(to_string(type->type));

      std::vector<ResourceEntry*> sorted_entries = CollectAndSortEntries(type);
      if (sorted_entries.empty()) {
        continue;
      }

      if (!FlattenTypeSpec(type, &sorted_entries, buffer)) {
        return false;
      }

      // Since the entries are sorted by ID, the last ID will be the largest.
      const size_t num_entries = sorted_entries.back()->id.value() + 1;

      // The binary resource table lists resource entries for each
      // configuration.
      // We store them inverted, where a resource entry lists the values for
      // each
      // configuration available. Here we reverse this to match the binary
      // table.
      std::map<ConfigDescription, std::vector<FlatEntry>> config_to_entry_list_map;

      // hardcoded string uses characters which make it an invalid resource name
      const std::string obfuscated_resource_name = "0_resource_name_obfuscated";

      for (ResourceEntry* entry : sorted_entries) {
        uint32_t local_key_index;
        if (!collapse_key_stringpool_ ||
            whitelisted_resources_.find(entry->name) != whitelisted_resources_.end()) {
          local_key_index = (uint32_t)key_pool_.MakeRef(entry->name).index();
        } else {
          // resource isn't whitelisted, add it as obfuscated value
          local_key_index = (uint32_t)key_pool_.MakeRef(obfuscated_resource_name).index();
        }
        // Group values by configuration.
        for (auto& config_value : entry->values) {
          config_to_entry_list_map[config_value->config].push_back(
              FlatEntry{entry, config_value->value.get(), local_key_index});
        }
      }

      // Flatten a configuration value.
      for (auto& entry : config_to_entry_list_map) {
        if (!FlattenConfig(type, entry.first, num_entries, &entry.second, buffer)) {
          return false;
        }
      }
    }
    return true;
  }

  void FlattenLibrarySpec(BigBuffer* buffer) {
    ChunkWriter lib_writer(buffer);
    ResTable_lib_header* lib_header =
        lib_writer.StartChunk<ResTable_lib_header>(RES_TABLE_LIBRARY_TYPE);

    const size_t num_entries = (package_->id.value() == 0x00 ? 1 : 0) + shared_libs_->size();
    CHECK(num_entries > 0);

    lib_header->count = util::HostToDevice32(num_entries);

    ResTable_lib_entry* lib_entry = buffer->NextBlock<ResTable_lib_entry>(num_entries);
    if (package_->id.value() == 0x00) {
      // Add this package
      lib_entry->packageId = util::HostToDevice32(0x00);
      strcpy16_htod(lib_entry->packageName, arraysize(lib_entry->packageName),
                    util::Utf8ToUtf16(package_->name));
      ++lib_entry;
    }

    for (auto& map_entry : *shared_libs_) {
      lib_entry->packageId = util::HostToDevice32(map_entry.first);
      strcpy16_htod(lib_entry->packageName, arraysize(lib_entry->packageName),
                    util::Utf8ToUtf16(map_entry.second));
      ++lib_entry;
    }
    lib_writer.Finish();
  }

  IAaptContext* context_;
  IDiagnostics* diag_;
  ResourceTablePackage* package_;
  const std::map<size_t, std::string>* shared_libs_;
  bool use_sparse_entries_;
  StringPool type_pool_;
  StringPool key_pool_;
  bool collapse_key_stringpool_;
  const std::set<std::string>& whitelisted_resources_;
};

}  // namespace

bool TableFlattener::Consume(IAaptContext* context, ResourceTable* table) {
  // We must do this before writing the resources, since the string pool IDs may change.
  table->string_pool.Prune();
  table->string_pool.Sort([](const StringPool::Context& a, const StringPool::Context& b) -> int {
    int diff = util::compare(a.priority, b.priority);
    if (diff == 0) {
      diff = a.config.compare(b.config);
    }
    return diff;
  });

  // Write the ResTable header.
  ChunkWriter table_writer(buffer_);
  ResTable_header* table_header = table_writer.StartChunk<ResTable_header>(RES_TABLE_TYPE);
  table_header->packageCount = util::HostToDevice32(table->packages.size());

  // Flatten the values string pool.
  StringPool::FlattenUtf8(table_writer.buffer(), table->string_pool,
      context->GetDiagnostics());

  BigBuffer package_buffer(1024);

  // Flatten each package.
  for (auto& package : table->packages) {
    if (context->GetPackageType() == PackageType::kApp) {
      // Write a self mapping entry for this package if the ID is non-standard (0x7f).
      const uint8_t package_id = package->id.value();
      if (package_id != kFrameworkPackageId && package_id != kAppPackageId) {
        auto result = table->included_packages_.insert({package_id, package->name});
        if (!result.second && result.first->second != package->name) {
          // A mapping for this package ID already exists, and is a different package. Error!
          context->GetDiagnostics()->Error(
              DiagMessage() << android::base::StringPrintf(
                  "can't map package ID %02x to '%s'. Already mapped to '%s'", package_id,
                  package->name.c_str(), result.first->second.c_str()));
          return false;
        }
      }
    }

    PackageFlattener flattener(context, package.get(), &table->included_packages_,
                               options_.use_sparse_entries, options_.collapse_key_stringpool,
                               options_.whitelisted_resources);
    if (!flattener.FlattenPackage(&package_buffer)) {
      return false;
    }
  }

  // Finally merge all the packages into the main buffer.
  table_writer.buffer()->AppendBuffer(std::move(package_buffer));
  table_writer.Finish();
  return true;
}

}  // namespace aapt
