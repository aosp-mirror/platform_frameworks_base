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

#include "Debug.h"
#include "ResourceUtils.h"
#include "compile/InlineXmlFormatParser.h"
#include "util/Util.h"
#include "xml/XmlDom.h"
#include "xml/XmlUtil.h"

#include <android-base/macros.h>
#include <sstream>
#include <string>

namespace aapt {

namespace {

/**
 * XML Visitor that will find all <aapt:attr> elements for extraction.
 */
class Visitor : public xml::PackageAwareVisitor {
public:
    using xml::PackageAwareVisitor::visit;

    struct InlineDeclaration {
        xml::Element* el;
        std::string attrNamespaceUri;
        std::string attrName;
    };

    explicit Visitor(IAaptContext* context, xml::XmlResource* xmlResource) :
            mContext(context), mXmlResource(xmlResource) {
    }

    void visit(xml::Element* el) override {
        if (el->namespaceUri != xml::kSchemaAapt || el->name != "attr") {
            xml::PackageAwareVisitor::visit(el);
            return;
        }

        const Source& src = mXmlResource->file.source.withLine(el->lineNumber);

        xml::Attribute* attr = el->findAttribute({}, "name");
        if (!attr) {
            mContext->getDiagnostics()->error(DiagMessage(src) << "missing 'name' attribute");
            mError = true;
            return;
        }

        Maybe<Reference> ref = ResourceUtils::parseXmlAttributeName(attr->value);
        if (!ref) {
            mContext->getDiagnostics()->error(DiagMessage(src) << "invalid XML attribute '"
                                              << attr->value << "'");
            mError = true;
            return;
        }

        const ResourceName& name = ref.value().name.value();

        // Use an empty string for the compilation package because we don't want to default to
        // the local package if the user specified name="style" or something. This should just
        // be the default namespace.
        Maybe<xml::ExtractedPackage> maybePkg = transformPackageAlias(name.package, {});
        if (!maybePkg) {
            mContext->getDiagnostics()->error(DiagMessage(src) << "invalid namespace prefix '"
                                              << name.package << "'");
            mError = true;
            return;
        }

        const xml::ExtractedPackage& pkg = maybePkg.value();
        const bool privateNamespace = pkg.privateNamespace || ref.value().privateReference;

        InlineDeclaration decl;
        decl.el = el;
        decl.attrName = name.entry;
        if (!pkg.package.empty()) {
            decl.attrNamespaceUri = xml::buildPackageNamespace(pkg.package, privateNamespace);
        }

        mInlineDeclarations.push_back(std::move(decl));
    }

    const std::vector<InlineDeclaration>& getInlineDeclarations() const {
        return mInlineDeclarations;
    }

    bool hasError() const {
        return mError;
    }

private:
    DISALLOW_COPY_AND_ASSIGN(Visitor);

    IAaptContext* mContext;
    xml::XmlResource* mXmlResource;
    std::vector<InlineDeclaration> mInlineDeclarations;
    bool mError = false;
};

} // namespace

bool InlineXmlFormatParser::consume(IAaptContext* context, xml::XmlResource* doc) {
    Visitor visitor(context, doc);
    doc->root->accept(&visitor);
    if (visitor.hasError()) {
        return false;
    }

    size_t nameSuffixCounter = 0;
    for (const Visitor::InlineDeclaration& decl : visitor.getInlineDeclarations()) {
        auto newDoc = util::make_unique<xml::XmlResource>();
        newDoc->file.config = doc->file.config;
        newDoc->file.source = doc->file.source.withLine(decl.el->lineNumber);
        newDoc->file.name = doc->file.name;

        // Modify the new entry name. We need to suffix the entry with a number to avoid
        // local collisions, then mangle it with the empty package, such that it won't show up
        // in R.java.

        newDoc->file.name.entry = NameMangler::mangleEntry(
                {}, newDoc->file.name.entry + "__" + std::to_string(nameSuffixCounter));

        // Extracted elements must be the only child of <aapt:attr>.
        // Make sure there is one root node in the children (ignore empty text).
        for (auto& child : decl.el->children) {
            const Source childSource = doc->file.source.withLine(child->lineNumber);
            if (xml::Text* t = xml::nodeCast<xml::Text>(child.get())) {
                if (!util::trimWhitespace(t->text).empty()) {
                    context->getDiagnostics()->error(DiagMessage(childSource)
                                                     << "can't extract text into its own resource");
                    return false;
                }
            } else if (newDoc->root) {
                context->getDiagnostics()->error(DiagMessage(childSource)
                                                 << "inline XML resources must have a single root");
                return false;
            } else {
                newDoc->root = std::move(child);
                newDoc->root->parent = nullptr;
            }
        }

        // Walk up and find the parent element.
        xml::Node* node = decl.el;
        xml::Element* parentEl = nullptr;
        while (node->parent && (parentEl = xml::nodeCast<xml::Element>(node->parent)) == nullptr) {
            node = node->parent;
        }

        if (!parentEl) {
            context->getDiagnostics()->error(DiagMessage(newDoc->file.source)
                                             << "no suitable parent for inheriting attribute");
            return false;
        }

        // Add the inline attribute to the parent.
        parentEl->attributes.push_back(xml::Attribute{
                decl.attrNamespaceUri, decl.attrName, "@" + newDoc->file.name.toString() });

        // Delete the subtree.
        for (auto iter = parentEl->children.begin(); iter != parentEl->children.end(); ++iter) {
            if (iter->get() == node) {
                parentEl->children.erase(iter);
                break;
            }
        }

        mQueue.push_back(std::move(newDoc));

        nameSuffixCounter++;
    }
    return true;
}

} // namespace aapt
