/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef TOOLS_AAPT2_OPTIMIZE_OBFUSCATOR_H_
#define TOOLS_AAPT2_OPTIMIZE_OBFUSCATOR_H_

#include <set>
#include <string>

#include "ResourceMetadata.pb.h"
#include "ResourceTable.h"
#include "android-base/function_ref.h"
#include "android-base/macros.h"
#include "cmd/Optimize.h"
#include "format/binary/TableFlattener.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

class ResourceTable;

// Maps resources in the apk to shortened paths.
class Obfuscator : public IResourceTableConsumer {
 public:
  explicit Obfuscator(OptimizeOptions& optimizeOptions);

  bool Consume(IAaptContext* context, ResourceTable* table) override;

  bool WriteObfuscationMap(const std::string& file_path) const;

  bool IsEnabled() const;

  enum class Result { Obfuscated, Keep_ExemptionList, Keep_Overlayable };

  // hardcoded string uses characters which make it an invalid resource name
  static constexpr char kObfuscatedResourceName[] = "0_resource_name_obfuscated";

  static void ObfuscateResourceName(
      const bool collapse_key_stringpool, const std::set<ResourceName>& name_collapse_exemptions,
      const ResourceNamedType& type_name, const ResourceTableEntryView& entry,
      const android::base::function_ref<void(Result, const ResourceName&)> onObfuscate);

 protected:
  virtual std::string ShortenFileName(android::StringPiece file_path, int output_length);

 private:
  bool HandleShortenFilePaths(ResourceTable* table,
                              std::map<std::string, std::string>& shortened_path_map,
                              const std::set<ResourceName>& path_shorten_exemptions);

  TableFlattenerOptions& options_;
  const bool shorten_resource_paths_;
  const bool collapse_key_stringpool_;
  DISALLOW_COPY_AND_ASSIGN(Obfuscator);
};

}  // namespace aapt

#endif  // TOOLS_AAPT2_OPTIMIZE_OBFUSCATOR_H_
