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

#include "process/IResourceTableConsumer.h"
#include "util/Maybe.h"
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"

#include <string>

namespace aapt {

struct ManifestFixerOptions {
    Maybe<std::string> minSdkVersionDefault;
    Maybe<std::string> targetSdkVersionDefault;
    Maybe<std::string> renameManifestPackage;
    Maybe<std::string> renameInstrumentationTargetPackage;
    Maybe<std::string> versionNameDefault;
    Maybe<std::string> versionCodeDefault;
};

/**
 * Verifies that the manifest is correctly formed and inserts defaults
 * where specified with ManifestFixerOptions.
 */
class ManifestFixer : public IXmlResourceConsumer {
public:
    explicit ManifestFixer(const ManifestFixerOptions& options) : mOptions(options) {
    }

    bool consume(IAaptContext* context, xml::XmlResource* doc) override;

private:
    bool buildRules(xml::XmlActionExecutor* executor, IDiagnostics* diag);

    ManifestFixerOptions mOptions;
};

} // namespace aapt

#endif /* AAPT_LINK_MANIFESTFIXER_H */
