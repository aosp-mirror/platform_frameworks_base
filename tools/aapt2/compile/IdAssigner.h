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

#ifndef AAPT_COMPILE_IDASSIGNER_H
#define AAPT_COMPILE_IDASSIGNER_H

#include <unordered_map>

#include "android-base/macros.h"

#include "Resource.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

// Assigns IDs to each resource in the table, respecting existing IDs and
// filling in gaps in between fixed ID assignments.
class IdAssigner : public IResourceTableConsumer {
 public:
  IdAssigner() = default;
  explicit IdAssigner(const std::unordered_map<ResourceName, ResourceId>* map)
      : assigned_id_map_(map) {}

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  const std::unordered_map<ResourceName, ResourceId>* assigned_id_map_ = nullptr;
};

}  // namespace aapt

#endif /* AAPT_COMPILE_IDASSIGNER_H */
