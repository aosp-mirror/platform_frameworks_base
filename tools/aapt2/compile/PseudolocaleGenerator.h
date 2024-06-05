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

#ifndef AAPT_COMPILE_PSEUDOLOCALEGENERATOR_H
#define AAPT_COMPILE_PSEUDOLOCALEGENERATOR_H

#include "androidfw/StringPool.h"
#include "compile/Pseudolocalizer.h"
#include "process/IResourceTableConsumer.h"

namespace aapt {

std::unique_ptr<StyledString> PseudolocalizeStyledString(StyledString* string,
                                                         Pseudolocalizer::Method method,
                                                         android::StringPool* pool);

class PseudolocaleGenerator : public IResourceTableConsumer {
 public:
  explicit PseudolocaleGenerator(std::string grammatical_gender_values,
                                 std::string grammatical_gender_ratio)
      : grammatical_gender_values_(std::move(grammatical_gender_values)),
        grammatical_gender_ratio_(std::move(grammatical_gender_ratio)) {
  }

  bool Consume(IAaptContext* context, ResourceTable* table);

 private:
  std::string grammatical_gender_values_;
  std::string grammatical_gender_ratio_;
};

}  // namespace aapt

#endif /* AAPT_COMPILE_PSEUDOLOCALEGENERATOR_H */
