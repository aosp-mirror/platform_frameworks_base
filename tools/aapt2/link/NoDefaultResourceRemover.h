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

#ifndef AAPT_LINK_NODEFAULTRESOURCEREMOVER_H
#define AAPT_LINK_NODEFAULTRESOURCEREMOVER_H

#include "android-base/macros.h"

#include "process/IResourceTableConsumer.h"

namespace aapt {

// Removes any resource for which there exists no definition for the default configuration, where
// for that resource type, a definition is required.
//
// The obvious example is when defining localized strings. If a string in the default configuration
// has its name changed, the translations for that string won't be changed but will still cause
// the generated R class to contain the old string name. This will cause breakages in apps that
// still rely on the old name when the translations are updated.
class NoDefaultResourceRemover : public IResourceTableConsumer {
 public:
  NoDefaultResourceRemover() = default;

  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(NoDefaultResourceRemover);
};

}  // namespace aapt

#endif  // AAPT_LINK_NODEFAULTRESOURCEREMOVER_H
