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
#include "xml/XmlActionExecutor.h"
#include "xml/XmlDom.h"

#include <unordered_set>

namespace aapt {

/**
 * This is how PackageManager builds class names from AndroidManifest.xml entries.
 */
static bool nameIsJavaClassName(xml::Element* el, xml::Attribute* attr,
                                SourcePathDiagnostics* diag) {
    // We allow unqualified class names (ie: .HelloActivity)
    // Since we don't know the package name, we can just make a fake one here and
    // the test will be identical as long as the real package name is valid too.
    Maybe<std::string> fullyQualifiedClassName =
            util::getFullyQualifiedClassName("a", attr->value);

    StringPiece qualifiedClassName = fullyQualifiedClassName
            ? fullyQualifiedClassName.value() : attr->value;

    if (!util::isJavaClassName(qualifiedClassName)) {
        diag->error(DiagMessage(el->lineNumber)
                    << "attribute 'android:name' in <"
                    << el->name << "> tag must be a valid Java class name");
        return false;
    }
    return true;
}

static bool optionalNameIsJavaClassName(xml::Element* el, SourcePathDiagnostics* diag) {
    if (xml::Attribute* attr = el->findAttribute(xml::kSchemaAndroid, "name")) {
        return nameIsJavaClassName(el, attr, diag);
    }
    return true;
}

static bool requiredNameIsJavaClassName(xml::Element* el, SourcePathDiagnostics* diag) {
    if (xml::Attribute* attr = el->findAttribute(xml::kSchemaAndroid, "name")) {
        return nameIsJavaClassName(el, attr, diag);
    }
    diag->error(DiagMessage(el->lineNumber)
                << "<" << el->name << "> is missing attribute 'android:name'");
    return false;
}

static bool verifyManifest(xml::Element* el, SourcePathDiagnostics* diag) {
    xml::Attribute* attr = el->findAttribute({}, "package");
    if (!attr) {
        diag->error(DiagMessage(el->lineNumber) << "<manifest> tag is missing 'package' attribute");
        return false;
    } else if (ResourceUtils::isReference(attr->value)) {
        diag->error(DiagMessage(el->lineNumber)
                    << "attribute 'package' in <manifest> tag must not be a reference");
        return false;
    } else if (!util::isJavaPackageName(attr->value)) {
        diag->error(DiagMessage(el->lineNumber)
                    << "attribute 'package' in <manifest> tag is not a valid Java package name: '"
                    << attr->value << "'");
        return false;
    }
    return true;
}

bool ManifestFixer::buildRules(xml::XmlActionExecutor* executor, IDiagnostics* diag) {
    // First verify some options.
    if (mOptions.renameManifestPackage) {
        if (!util::isJavaPackageName(mOptions.renameManifestPackage.value())) {
            diag->error(DiagMessage() << "invalid manifest package override '"
                        << mOptions.renameManifestPackage.value() << "'");
            return false;
        }
    }

    if (mOptions.renameInstrumentationTargetPackage) {
        if (!util::isJavaPackageName(mOptions.renameInstrumentationTargetPackage.value())) {
            diag->error(DiagMessage() << "invalid instrumentation target package override '"
                        << mOptions.renameInstrumentationTargetPackage.value() << "'");
            return false;
        }
    }

    // Common intent-filter actions.
    xml::XmlNodeAction intentFilterAction;
    intentFilterAction["action"];
    intentFilterAction["category"];
    intentFilterAction["data"];

    // Common meta-data actions.
    xml::XmlNodeAction metaDataAction;

    // Manifest actions.
    xml::XmlNodeAction& manifestAction = (*executor)["manifest"];
    manifestAction.action(verifyManifest);
    manifestAction.action([&](xml::Element* el) -> bool {
        if (mOptions.versionNameDefault) {
            if (el->findAttribute(xml::kSchemaAndroid, "versionName") == nullptr) {
                el->attributes.push_back(xml::Attribute{
                        xml::kSchemaAndroid,
                        "versionName",
                        mOptions.versionNameDefault.value() });
            }
        }

        if (mOptions.versionCodeDefault) {
            if (el->findAttribute(xml::kSchemaAndroid, "versionCode") == nullptr) {
                el->attributes.push_back(xml::Attribute{
                        xml::kSchemaAndroid,
                        "versionCode",
                        mOptions.versionCodeDefault.value() });
            }
        }
        return true;
    });

    // Meta tags.
    manifestAction["eat-comment"];

    // Uses-sdk actions.
    manifestAction["uses-sdk"].action([&](xml::Element* el) -> bool {
        if (mOptions.minSdkVersionDefault &&
                el->findAttribute(xml::kSchemaAndroid, "minSdkVersion") == nullptr) {
            // There was no minSdkVersion defined and we have a default to assign.
            el->attributes.push_back(xml::Attribute{
                    xml::kSchemaAndroid, "minSdkVersion",
                    mOptions.minSdkVersionDefault.value() });
        }

        if (mOptions.targetSdkVersionDefault &&
                el->findAttribute(xml::kSchemaAndroid, "targetSdkVersion") == nullptr) {
            // There was no targetSdkVersion defined and we have a default to assign.
            el->attributes.push_back(xml::Attribute{
                    xml::kSchemaAndroid, "targetSdkVersion",
                    mOptions.targetSdkVersionDefault.value() });
        }
        return true;
    });

    // Instrumentation actions.
    manifestAction["instrumentation"].action([&](xml::Element* el) -> bool {
        if (!mOptions.renameInstrumentationTargetPackage) {
            return true;
        }

        if (xml::Attribute* attr = el->findAttribute(xml::kSchemaAndroid, "targetPackage")) {
            attr->value = mOptions.renameInstrumentationTargetPackage.value();
        }
        return true;
    });

    manifestAction["original-package"];
    manifestAction["protected-broadcast"];
    manifestAction["uses-permission"];
    manifestAction["permission"];
    manifestAction["permission-tree"];
    manifestAction["permission-group"];

    manifestAction["uses-configuration"];
    manifestAction["uses-feature"];
    manifestAction["supports-screens"];
    manifestAction["compatible-screens"];
    manifestAction["supports-gl-texture"];

    // Application actions.
    xml::XmlNodeAction& applicationAction = manifestAction["application"];
    applicationAction.action(optionalNameIsJavaClassName);

    // Uses library actions.
    applicationAction["uses-library"];

    // Meta-data.
    applicationAction["meta-data"] = metaDataAction;

    // Activity actions.
    applicationAction["activity"].action(requiredNameIsJavaClassName);
    applicationAction["activity"]["intent-filter"] = intentFilterAction;
    applicationAction["activity"]["meta-data"] = metaDataAction;

    // Activity alias actions.
    applicationAction["activity-alias"]["intent-filter"] = intentFilterAction;
    applicationAction["activity-alias"]["meta-data"] = metaDataAction;

    // Service actions.
    applicationAction["service"].action(requiredNameIsJavaClassName);
    applicationAction["service"]["intent-filter"] = intentFilterAction;
    applicationAction["service"]["meta-data"] = metaDataAction;

    // Receiver actions.
    applicationAction["receiver"].action(requiredNameIsJavaClassName);
    applicationAction["receiver"]["intent-filter"] = intentFilterAction;
    applicationAction["receiver"]["meta-data"] = metaDataAction;

    // Provider actions.
    applicationAction["provider"].action(requiredNameIsJavaClassName);
    applicationAction["provider"]["intent-filter"] = intentFilterAction;
    applicationAction["provider"]["meta-data"] = metaDataAction;
    applicationAction["provider"]["grant-uri-permissions"];
    applicationAction["provider"]["path-permissions"];

    return true;
}

class FullyQualifiedClassNameVisitor : public xml::Visitor {
public:
    using xml::Visitor::visit;

    explicit FullyQualifiedClassNameVisitor(const StringPiece& package) : mPackage(package) {
    }

    void visit(xml::Element* el) override {
        for (xml::Attribute& attr : el->attributes) {
            if (attr.namespaceUri == xml::kSchemaAndroid
                    && mClassAttributes.find(attr.name) != mClassAttributes.end()) {
                if (Maybe<std::string> newValue =
                        util::getFullyQualifiedClassName(mPackage, attr.value)) {
                    attr.value = std::move(newValue.value());
                }
            }
        }

        // Super implementation to iterate over the children.
        xml::Visitor::visit(el);
    }

private:
    StringPiece mPackage;
    std::unordered_set<StringPiece> mClassAttributes = { "name" };
};

static bool renameManifestPackage(const StringPiece& packageOverride, xml::Element* manifestEl) {
    xml::Attribute* attr = manifestEl->findAttribute({}, "package");

    // We've already verified that the manifest element is present, with a package name specified.
    assert(attr);

    std::string originalPackage = std::move(attr->value);
    attr->value = packageOverride.toString();

    FullyQualifiedClassNameVisitor visitor(originalPackage);
    manifestEl->accept(&visitor);
    return true;
}

bool ManifestFixer::consume(IAaptContext* context, xml::XmlResource* doc) {
    xml::Element* root = xml::findRootElement(doc->root.get());
    if (!root || !root->namespaceUri.empty() || root->name != "manifest") {
        context->getDiagnostics()->error(DiagMessage(doc->file.source)
                                         << "root tag must be <manifest>");
        return false;
    }

    if ((mOptions.minSdkVersionDefault || mOptions.targetSdkVersionDefault)
            && root->findChild({}, "uses-sdk") == nullptr) {
        // Auto insert a <uses-sdk> element.
        std::unique_ptr<xml::Element> usesSdk = util::make_unique<xml::Element>();
        usesSdk->name = "uses-sdk";
        root->addChild(std::move(usesSdk));
    }

    xml::XmlActionExecutor executor;
    if (!buildRules(&executor, context->getDiagnostics())) {
        return false;
    }

    if (!executor.execute(xml::XmlActionExecutorPolicy::Whitelist, context->getDiagnostics(),
                          doc)) {
        return false;
    }

    if (mOptions.renameManifestPackage) {
        // Rename manifest package outside of the XmlActionExecutor.
        // We need to extract the old package name and FullyQualify all class names.
        if (!renameManifestPackage(mOptions.renameManifestPackage.value(), root)) {
            return false;
        }
    }
    return true;
}

} // namespace aapt
