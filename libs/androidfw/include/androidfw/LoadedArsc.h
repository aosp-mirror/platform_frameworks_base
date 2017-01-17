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

#ifndef LOADEDARSC_H_
#define LOADEDARSC_H_

#include <memory>
#include <vector>

#include "android-base/macros.h"

#include "androidfw/ResourceTypes.h"

namespace android {

class Chunk;
class LoadedPackage;

// Read-only view into a resource table. This class validates all data
// when loading, including offsets and lengths.
class LoadedArsc {
 public:
  // Load the resource table from memory. The data's lifetime must out-live the
  // object returned from this method.
  static std::unique_ptr<LoadedArsc> Load(const void* data, size_t len);

  ~LoadedArsc();

  // Returns the string pool where all string resource values
  // (Res_value::dataType == Res_value::TYPE_STRING) are indexed.
  inline const ResStringPool* GetStringPool() const { return &global_string_pool_; }

  struct Entry {
    // A pointer to the resource table entry for this resource.
    // If the size of the entry is > sizeof(ResTable_entry), it can be cast to
    // a ResTable_map_entry and processed as a bag/map.
    const ResTable_entry* entry = nullptr;

    // The string pool reference to the type's name. This uses a different string pool than
    // the global string pool, but this is hidden from the caller.
    StringPoolRef type_string_ref;

    // The string pool reference to the entry's name. This uses a different string pool than
    // the global string pool, but this is hidden from the caller.
    StringPoolRef entry_string_ref;
  };

  // Finds the resource with ID `resid` with the best value for configuration `config`.
  // The parameter `out_entry` will be filled with the resulting resource entry.
  // The resource entry can be a simple entry (ResTable_entry) or a complex bag
  // (ResTable_entry_map).
  bool FindEntry(uint32_t resid, const ResTable_config& config, Entry* out_entry,
                 ResTable_config* selected_config, uint32_t* out_flags) const;

  // Gets a pointer to the name of the package in `resid`, or nullptr if the package doesn't exist.
  const std::string* GetPackageNameForId(uint32_t resid) const;

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedArsc);

  LoadedArsc() = default;
  bool LoadTable(const Chunk& chunk);

  ResStringPool global_string_pool_;
  std::vector<std::unique_ptr<LoadedPackage>> packages_;
};

}  // namespace android

#endif /* LOADEDARSC_H_ */
