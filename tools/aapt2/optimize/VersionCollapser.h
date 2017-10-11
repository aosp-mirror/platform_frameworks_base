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

#ifndef AAPT_OPTIMIZE_VERSIONCOLLAPSER_H
#define AAPT_OPTIMIZE_VERSIONCOLLAPSER_H

#include "android-base/macros.h"

#include "process/IResourceTableConsumer.h"

namespace aapt {

class ResourceTable;

class VersionCollapser : public IResourceTableConsumer {
 public:
  VersionCollapser() = default;

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(VersionCollapser);
};

} // namespace aapt

#endif  // AAPT_OPTIMIZE_VERSIONCOLLAPSER_H
