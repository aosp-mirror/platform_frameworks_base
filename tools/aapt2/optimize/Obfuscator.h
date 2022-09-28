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

#include <map>
#include <string>

#include "android-base/macros.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

class ResourceTable;

// Maps resources in the apk to shortened paths.
class Obfuscator : public IResourceTableConsumer {
 public:
  explicit Obfuscator(std::map<std::string, std::string>& path_map_out);

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  std::map<std::string, std::string>& path_map_;
  DISALLOW_COPY_AND_ASSIGN(Obfuscator);
};

}  // namespace aapt

#endif  // TOOLS_AAPT2_OPTIMIZE_OBFUSCATOR_H_
