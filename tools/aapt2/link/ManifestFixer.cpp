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

#include "ResourceUtils.h"
#include "XmlDom.h"

#include "link/ManifestFixer.h"
#include "util/Util.h"

namespace aapt {

static bool verifyManifest(IAaptContext* context, const Source& source, xml::Element* manifestEl) {
    bool error = false;

    xml::Attribute* attr = manifestEl->findAttribute({}, u"package");
    if (!attr) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "missing 'package' attribute");
        error = true;
    } else if (ResourceUtils::isReference(attr->value)) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "value for attribute 'package' must not be a "
                                            "reference");
        error = true;
    } else if (!util::isJavaPackageName(attr->value)) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "invalid package name '" << attr->value << "'");
        error = true;
    }

    return !error;
}

static bool fixUsesSdk(IAaptContext* context, const Source& source, xml::Element* el,
                       const ManifestFixerOptions& options) {
    if (options.minSdkVersionDefault &&
            el->findAttribute(xml::kSchemaAndroid, u"minSdkVersion") == nullptr) {
        // There was no minSdkVersion defined and we have a default to assign.
        el->attributes.push_back(xml::Attribute{
                xml::kSchemaAndroid, u"minSdkVersion", options.minSdkVersionDefault.value() });
    }

    if (options.targetSdkVersionDefault &&
            el->findAttribute(xml::kSchemaAndroid, u"targetSdkVersion") == nullptr) {
        // There was no targetSdkVersion defined and we have a default to assign.
        el->attributes.push_back(xml::Attribute{
                xml::kSchemaAndroid, u"targetSdkVersion",
                options.targetSdkVersionDefault.value() });
    }
    return true;
}

bool ManifestFixer::consume(IAaptContext* context, XmlResource* doc) {
    xml::Element* root = xml::findRootElement(doc->root.get());
    if (!root || !root->namespaceUri.empty() || root->name != u"manifest") {
        context->getDiagnostics()->error(DiagMessage(doc->file.source)
                                         << "root tag must be <manifest>");
        return false;
    }

    if (!verifyManifest(context, doc->file.source, root)) {
        return false;
    }

    bool foundUsesSdk = false;
    for (xml::Element* el : root->getChildElements()) {
        if (!el->namespaceUri.empty()) {
            continue;
        }

        if (el->name == u"uses-sdk") {
            foundUsesSdk = true;
            fixUsesSdk(context, doc->file.source, el, mOptions);
        }
    }

    if (!foundUsesSdk && (mOptions.minSdkVersionDefault || mOptions.targetSdkVersionDefault)) {
        std::unique_ptr<xml::Element> usesSdk = util::make_unique<xml::Element>();
        usesSdk->name = u"uses-sdk";
        fixUsesSdk(context, doc->file.source, usesSdk.get(), mOptions);
        root->addChild(std::move(usesSdk));
    }

    return true;
}

} // namespace aapt
