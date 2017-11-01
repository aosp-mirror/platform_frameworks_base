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

#ifndef AAPT_LINK_MANIFESTFIXER_H
#define AAPT_LINK_MANIFESTFIXER_H

#include <string>

#include "android-base/macros.h"

#include "process/IResourceTableConsumer.h"
#include "util/Maybe.h"
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"

namespace aapt {

struct ManifestFixerOptions {
  Maybe<std::string> min_sdk_version_default;
  Maybe<std::string> target_sdk_version_default;
  Maybe<std::string> rename_manifest_package;
  Maybe<std::string> rename_instrumentation_target_package;
  Maybe<std::string> version_name_default;
  Maybe<std::string> version_code_default;
};

/**
 * Verifies that the manifest is correctly formed and inserts defaults
 * where specified with ManifestFixerOptions.
 */
class ManifestFixer : public IXmlResourceConsumer {
 public:
  explicit ManifestFixer(const ManifestFixerOptions& options)
      : options_(options) {}

  bool Consume(IAaptContext* context, xml::XmlResource* doc) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ManifestFixer);

  bool BuildRules(xml::XmlActionExecutor* executor, IDiagnostics* diag);

  ManifestFixerOptions options_;
};

}  // namespace aapt

#endif /* AAPT_LINK_MANIFESTFIXER_H */
