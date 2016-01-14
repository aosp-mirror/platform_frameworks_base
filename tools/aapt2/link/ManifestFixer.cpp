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
#include "link/ManifestFixer.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {

static bool verifyManifest(IAaptContext* context, const Source& source, xml::Element* manifestEl) {
    xml::Attribute* attr = manifestEl->findAttribute({}, u"package");
    if (!attr) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "missing 'package' attribute");
    } else if (ResourceUtils::isReference(attr->value)) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "value for attribute 'package' must not be a "
                                            "reference");
    } else if (!util::isJavaPackageName(attr->value)) {
        context->getDiagnostics()->error(DiagMessage(source.withLine(manifestEl->lineNumber))
                                         << "invalid package name '" << attr->value << "'");
    } else {
        return true;
    }
    return false;
}

static bool includeVersionName(IAaptContext* context, const Source& source,
                               const StringPiece16& versionName, xml::Element* manifestEl) {
    if (manifestEl->findAttribute(xml::kSchemaAndroid, u"versionName")) {
        return true;
    }

    manifestEl->attributes.push_back(xml::Attribute{
            xml::kSchemaAndroid, u"versionName", versionName.toString() });
    return true;
}

static bool includeVersionCode(IAaptContext* context, const Source& source,
                               const StringPiece16& versionCode, xml::Element* manifestEl) {
    if (manifestEl->findAttribute(xml::kSchemaAndroid, u"versionCode")) {
        return true;
    }

    manifestEl->attributes.push_back(xml::Attribute{
            xml::kSchemaAndroid, u"versionCode", versionCode.toString() });
    return true;
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

class FullyQualifiedClassNameVisitor : public xml::Visitor {
public:
    using xml::Visitor::visit;

    FullyQualifiedClassNameVisitor(const StringPiece16& package) : mPackage(package) {
    }

    void visit(xml::Element* el) override {
        for (xml::Attribute& attr : el->attributes) {
            if (Maybe<std::u16string> newValue =
                    util::getFullyQualifiedClassName(mPackage, attr.value)) {
                attr.value = std::move(newValue.value());
            }
        }

        // Super implementation to iterate over the children.
        xml::Visitor::visit(el);
    }

private:
    StringPiece16 mPackage;
};

static bool renameManifestPackage(IAaptContext* context, const Source& source,
                                  const StringPiece16& packageOverride, xml::Element* manifestEl) {
    if (!util::isJavaPackageName(packageOverride)) {
        context->getDiagnostics()->error(DiagMessage() << "invalid manifest package override '"
                                         << packageOverride << "'");
        return false;
    }

    xml::Attribute* attr = manifestEl->findAttribute({}, u"package");

    // We've already verified that the manifest element is present, with a package name specified.
    assert(attr);

    std::u16string originalPackage = std::move(attr->value);
    attr->value = packageOverride.toString();

    FullyQualifiedClassNameVisitor visitor(originalPackage);
    manifestEl->accept(&visitor);
    return true;
}

static bool renameInstrumentationTargetPackage(IAaptContext* context, const Source& source,
                                               const StringPiece16& packageOverride,
                                               xml::Element* manifestEl) {
    if (!util::isJavaPackageName(packageOverride)) {
        context->getDiagnostics()->error(DiagMessage()
                                         << "invalid instrumentation target package override '"
                                         << packageOverride << "'");
        return false;
    }

    xml::Element* instrumentationEl = manifestEl->findChild({}, u"instrumentation");
    if (!instrumentationEl) {
        // No error if there is no work to be done.
        return true;
    }

    xml::Attribute* attr = instrumentationEl->findAttribute(xml::kSchemaAndroid, u"targetPackage");
    if (!attr) {
        // No error if there is no work to be done.
        return true;
    }

    attr->value = packageOverride.toString();
    return true;
}

bool ManifestFixer::consume(IAaptContext* context, xml::XmlResource* doc) {
    xml::Element* root = xml::findRootElement(doc->root.get());
    if (!root || !root->namespaceUri.empty() || root->name != u"manifest") {
        context->getDiagnostics()->error(DiagMessage(doc->file.source)
                                         << "root tag must be <manifest>");
        return false;
    }

    if (!verifyManifest(context, doc->file.source, root)) {
        return false;
    }

    if (mOptions.versionCodeDefault) {
        if (!includeVersionCode(context, doc->file.source, mOptions.versionCodeDefault.value(),
                                root)) {
            return false;
        }
    }

    if (mOptions.versionNameDefault) {
        if (!includeVersionName(context, doc->file.source, mOptions.versionNameDefault.value(),
                                root)) {
            return false;
        }
    }

    if (mOptions.renameManifestPackage) {
        // Rename manifest package.
        if (!renameManifestPackage(context, doc->file.source,
                                   mOptions.renameManifestPackage.value(), root)) {
            return false;
        }
    }

    if (mOptions.renameInstrumentationTargetPackage) {
        if (!renameInstrumentationTargetPackage(context, doc->file.source,
                                                mOptions.renameInstrumentationTargetPackage.value(),
                                                root)) {
            return false;
        }
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
