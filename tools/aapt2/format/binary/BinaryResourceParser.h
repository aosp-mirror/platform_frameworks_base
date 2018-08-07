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

#ifndef AAPT_FORMAT_BINARY_RESOURCEPARSER_H
#define AAPT_FORMAT_BINARY_RESOURCEPARSER_H

#include <string>

#include "android-base/macros.h"
#include "androidfw/ResourceTypes.h"

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Source.h"
#include "process/IResourceTableConsumer.h"
#include "util/Util.h"

namespace aapt {

struct SymbolTable_entry;

// Parses a binary resource table (resources.arsc) and adds the entries to a ResourceTable.
// This is different than the libandroidfw ResTable in that it scans the table from top to bottom
// and doesn't require support for random access.
class BinaryResourceParser {
 public:
  // Creates a parser, which will read `len` bytes from `data`, and add any resources parsed to
  // `table`. `source` is for logging purposes.
  BinaryResourceParser(IDiagnostics* diag, ResourceTable* table, const Source& source,
                       const void* data, size_t data_len, io::IFileCollection* files = nullptr);

  // Parses the binary resource table and returns true if successful.
  bool Parse();

 private:
  DISALLOW_COPY_AND_ASSIGN(BinaryResourceParser);

  bool ParseTable(const android::ResChunk_header* chunk);
  bool ParsePackage(const android::ResChunk_header* chunk);
  bool ParseTypeSpec(const ResourceTablePackage* package, const android::ResChunk_header* chunk);
  bool ParseType(const ResourceTablePackage* package, const android::ResChunk_header* chunk);
  bool ParseLibrary(const android::ResChunk_header* chunk);

  std::unique_ptr<Item> ParseValue(const ResourceNameRef& name, const ConfigDescription& config,
                                   const android::Res_value& value);

  std::unique_ptr<Value> ParseMapEntry(const ResourceNameRef& name, const ConfigDescription& config,
                                       const android::ResTable_map_entry* map);

  std::unique_ptr<Style> ParseStyle(const ResourceNameRef& name, const ConfigDescription& config,
                                    const android::ResTable_map_entry* map);

  std::unique_ptr<Attribute> ParseAttr(const ResourceNameRef& name, const ConfigDescription& config,
                                       const android::ResTable_map_entry* map);

  std::unique_ptr<Array> ParseArray(const ResourceNameRef& name, const ConfigDescription& config,
                                    const android::ResTable_map_entry* map);

  std::unique_ptr<Plural> ParsePlural(const ResourceNameRef& name, const ConfigDescription& config,
                                      const android::ResTable_map_entry* map);

  /**
   * If the mapEntry is a special type that denotes meta data (source, comment),
   * then it is
   * read and added to the Value.
   * Returns true if the mapEntry was meta data.
   */
  bool CollectMetaData(const android::ResTable_map& map_entry, Value* value);

  IDiagnostics* diag_;
  ResourceTable* table_;

  const Source source_;

  const void* data_;
  const size_t data_len_;

  // Optional file collection from which to create io::IFile objects.
  io::IFileCollection* files_;

  // The standard value string pool for resource values.
  android::ResStringPool value_pool_;

  // The string pool that holds the names of the types defined
  // in this table.
  android::ResStringPool type_pool_;

  // The string pool that holds the names of the entries defined
  // in this table.
  android::ResStringPool key_pool_;

  // A mapping of resource ID to resource name. When we finish parsing
  // we use this to convert all resource IDs to symbolic references.
  std::map<ResourceId, ResourceName> id_index_;

  // A mapping of resource ID to type spec flags.
  std::unordered_map<ResourceId, uint32_t> entry_type_spec_flags_;
};

}  // namespace aapt

namespace android {

// Iterator functionality for ResTable_map_entry.

inline const ResTable_map* begin(const ResTable_map_entry* map) {
  return (const ResTable_map*)((const uint8_t*)map + ::aapt::util::DeviceToHost32(map->size));
}

inline const ResTable_map* end(const ResTable_map_entry* map) {
  return begin(map) + aapt::util::DeviceToHost32(map->count);
}

}  // namespace android

#endif  // AAPT_FORMAT_BINARY_RESOURCEPARSER_H
