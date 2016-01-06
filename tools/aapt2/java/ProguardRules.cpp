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

#include "java/ProguardRules.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

#include <memory>
#include <string>

namespace aapt {
namespace proguard {

class BaseVisitor : public xml::Visitor {
public:
    BaseVisitor(const Source& source, KeepSet* keepSet) : mSource(source), mKeepSet(keepSet) {
    }

    virtual void visit(xml::Text*) override {};

    virtual void visit(xml::Namespace* node) override {
        for (const auto& child : node->children) {
            child->accept(this);
        }
    }

    virtual void visit(xml::Element* node) override {
        if (!node->namespaceUri.empty()) {
            Maybe<xml::ExtractedPackage> maybePackage = xml::extractPackageFromNamespace(
                    node->namespaceUri);
            if (maybePackage) {
                // This is a custom view, let's figure out the class name from this.
                std::u16string package = maybePackage.value().package + u"." + node->name;
                if (util::isJavaClassName(package)) {
                    addClass(node->lineNumber, package);
                }
            }
        } else if (util::isJavaClassName(node->name)) {
            addClass(node->lineNumber, node->name);
        }

        for (const auto& child: node->children) {
            child->accept(this);
        }
    }

protected:
    void addClass(size_t lineNumber, const std::u16string& className) {
        mKeepSet->addClass(Source(mSource.path, lineNumber), className);
    }

    void addMethod(size_t lineNumber, const std::u16string& methodName) {
        mKeepSet->addMethod(Source(mSource.path, lineNumber), methodName);
    }

private:
    Source mSource;
    KeepSet* mKeepSet;
};

struct LayoutVisitor : public BaseVisitor {
    LayoutVisitor(const Source& source, KeepSet* keepSet) : BaseVisitor(source, keepSet) {
    }

    virtual void visit(xml::Element* node) override {
        bool checkClass = false;
        bool checkName = false;
        if (node->namespaceUri.empty()) {
            checkClass = node->name == u"view" || node->name == u"fragment";
        } else if (node->namespaceUri == xml::kSchemaAndroid) {
            checkName = node->name == u"fragment";
        }

        for (const auto& attr : node->attributes) {
            if (checkClass && attr.namespaceUri.empty() && attr.name == u"class" &&
                    util::isJavaClassName(attr.value)) {
                addClass(node->lineNumber, attr.value);
            } else if (checkName && attr.namespaceUri == xml::kSchemaAndroid &&
                    attr.name == u"name" && util::isJavaClassName(attr.value)) {
                addClass(node->lineNumber, attr.value);
            } else if (attr.namespaceUri == xml::kSchemaAndroid && attr.name == u"onClick") {
                addMethod(node->lineNumber, attr.value);
            }
        }

        BaseVisitor::visit(node);
    }
};

struct XmlResourceVisitor : public BaseVisitor {
    XmlResourceVisitor(const Source& source, KeepSet* keepSet) : BaseVisitor(source, keepSet) {
    }

    virtual void visit(xml::Element* node) override {
        bool checkFragment = false;
        if (node->namespaceUri.empty()) {
            checkFragment = node->name == u"PreferenceScreen" || node->name == u"header";
        }

        if (checkFragment) {
            xml::Attribute* attr = node->findAttribute(xml::kSchemaAndroid, u"fragment");
            if (attr && util::isJavaClassName(attr->value)) {
                addClass(node->lineNumber, attr->value);
            }
        }

        BaseVisitor::visit(node);
    }
};

struct TransitionVisitor : public BaseVisitor {
    TransitionVisitor(const Source& source, KeepSet* keepSet) : BaseVisitor(source, keepSet) {
    }

    virtual void visit(xml::Element* node) override {
        bool checkClass = node->namespaceUri.empty() &&
                (node->name == u"transition" || node->name == u"pathMotion");
        if (checkClass) {
            xml::Attribute* attr = node->findAttribute({}, u"class");
            if (attr && util::isJavaClassName(attr->value)) {
                addClass(node->lineNumber, attr->value);
            }
        }

        BaseVisitor::visit(node);
    }
};

struct ManifestVisitor : public BaseVisitor {
    ManifestVisitor(const Source& source, KeepSet* keepSet) : BaseVisitor(source, keepSet) {
    }

    virtual void visit(xml::Element* node) override {
        if (node->namespaceUri.empty()) {
            bool getName = false;
            if (node->name == u"manifest") {
                xml::Attribute* attr = node->findAttribute({}, u"package");
                if (attr) {
                    mPackage = attr->value;
                }
            } else if (node->name == u"application") {
                getName = true;
                xml::Attribute* attr = node->findAttribute(xml::kSchemaAndroid, u"backupAgent");
                if (attr) {
                    Maybe<std::u16string> result = util::getFullyQualifiedClassName(mPackage,
                                                                                    attr->value);
                    if (result) {
                        addClass(node->lineNumber, result.value());
                    }
                }
            } else if (node->name == u"activity" || node->name == u"service" ||
                    node->name == u"receiver" || node->name == u"provider" ||
                    node->name == u"instrumentation") {
                getName = true;
            }

            if (getName) {
                xml::Attribute* attr = node->findAttribute(xml::kSchemaAndroid, u"name");
                if (attr) {
                    Maybe<std::u16string> result = util::getFullyQualifiedClassName(mPackage,
                                                                                    attr->value);
                    if (result) {
                        addClass(node->lineNumber, result.value());
                    }
                }
            }
        }
        BaseVisitor::visit(node);
    }

    std::u16string mPackage;
};

bool collectProguardRulesForManifest(const Source& source, xml::XmlResource* res,
                                     KeepSet* keepSet) {
    ManifestVisitor visitor(source, keepSet);
    if (res->root) {
        res->root->accept(&visitor);
        return true;
    }
    return false;
}

bool collectProguardRules(const Source& source, xml::XmlResource* res, KeepSet* keepSet) {
    if (!res->root) {
        return false;
    }

    switch (res->file.name.type) {
        case ResourceType::kLayout: {
            LayoutVisitor visitor(source, keepSet);
            res->root->accept(&visitor);
            break;
        }

        case ResourceType::kXml: {
            XmlResourceVisitor visitor(source, keepSet);
            res->root->accept(&visitor);
            break;
        }

        case ResourceType::kTransition: {
            TransitionVisitor visitor(source, keepSet);
            res->root->accept(&visitor);
            break;
        }

        default:
            break;
    }
    return true;
}

bool writeKeepSet(std::ostream* out, const KeepSet& keepSet) {
    for (const auto& entry : keepSet.mKeepSet) {
        for (const Source& source : entry.second) {
            *out << "# Referenced at " << source << "\n";
        }
        *out << "-keep class " << entry.first << " { <init>(...); }\n" << std::endl;
    }

    for (const auto& entry : keepSet.mKeepMethodSet) {
        for (const Source& source : entry.second) {
            *out << "# Referenced at " << source << "\n";
        }
        *out << "-keepclassmembers class * { *** " << entry.first << "(...); }\n" << std::endl;
    }
    return true;
}

} // namespace proguard
} // namespace aapt
