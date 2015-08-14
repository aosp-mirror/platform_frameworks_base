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

#ifndef AAPT_XML_DOM_H
#define AAPT_XML_DOM_H

#include "Diagnostics.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include "process/IResourceTableConsumer.h"

#include <istream>
#include <expat.h>
#include <memory>
#include <string>
#include <vector>

namespace aapt {
namespace xml {

struct RawVisitor;

/**
 * The type of node. Can be used to downcast to the concrete XML node
 * class.
 */
enum class NodeType {
    kNamespace,
    kElement,
    kText,
};

/**
 * Base class for all XML nodes.
 */
struct Node {
    Node* parent = nullptr;
    size_t lineNumber = 0;
    size_t columnNumber = 0;
    std::u16string comment;
    std::vector<std::unique_ptr<Node>> children;

    void addChild(std::unique_ptr<Node> child);
    virtual void accept(RawVisitor* visitor) = 0;
    virtual ~Node() {}
};

/**
 * Base class that implements the visitor methods for a
 * subclass of Node.
 */
template <typename Derived>
struct BaseNode : public Node {
    virtual void accept(RawVisitor* visitor) override;
};

/**
 * A Namespace XML node. Can only have one child.
 */
struct Namespace : public BaseNode<Namespace> {
    std::u16string namespacePrefix;
    std::u16string namespaceUri;
};

struct AaptAttribute {
    ResourceId id;
    aapt::Attribute attribute;
};

/**
 * An XML attribute.
 */
struct Attribute {
    std::u16string namespaceUri;
    std::u16string name;
    std::u16string value;

    Maybe<AaptAttribute> compiledAttribute;
    std::unique_ptr<Item> compiledValue;
};

/**
 * An Element XML node.
 */
struct Element : public BaseNode<Element> {
    std::u16string namespaceUri;
    std::u16string name;
    std::vector<Attribute> attributes;

    Attribute* findAttribute(const StringPiece16& ns, const StringPiece16& name);
    xml::Element* findChild(const StringPiece16& ns, const StringPiece16& name);
    xml::Element* findChildWithAttribute(const StringPiece16& ns, const StringPiece16& name,
                                         const StringPiece16& attrNs,
                                         const StringPiece16& attrName,
                                         const StringPiece16& attrValue);
    std::vector<xml::Element*> getChildElements();
};

/**
 * A Text (CDATA) XML node. Can not have any children.
 */
struct Text : public BaseNode<Text> {
    std::u16string text;
};

/**
 * Inflates an XML DOM from a text stream, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<XmlResource> inflate(std::istream* in, IDiagnostics* diag, const Source& source);

/**
 * Inflates an XML DOM from a binary ResXMLTree, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<XmlResource> inflate(const void* data, size_t dataLen, IDiagnostics* diag,
                                     const Source& source);

/**
 * A visitor interface for the different XML Node subtypes. This will not traverse into
 * children. Use Visitor for that.
 */
struct RawVisitor {
    virtual ~RawVisitor() = default;

    virtual void visit(Namespace* node) {}
    virtual void visit(Element* node) {}
    virtual void visit(Text* text) {}
};

/**
 * Visitor whose default implementation visits the children nodes of any node.
 */
struct Visitor : public RawVisitor {
    using RawVisitor::visit;

    void visit(Namespace* node) override {
        visitChildren(node);
    }

    void visit(Element* node) override {
        visitChildren(node);
    }

    void visit(Text* text) override {
        visitChildren(text);
    }

    void visitChildren(Node* node) {
        for (auto& child : node->children) {
            child->accept(this);
        }
    }
};

/**
 * An XML DOM visitor that will record the package name for a namespace prefix.
 */
class PackageAwareVisitor : public Visitor, public IPackageDeclStack {
private:
    struct PackageDecl {
        std::u16string prefix;
        std::u16string package;
    };

    std::vector<PackageDecl> mPackageDecls;

public:
    using Visitor::visit;

    void visit(Namespace* ns) override {
        bool added = false;
        {
            Maybe<std::u16string> package = util::extractPackageFromNamespace(ns->namespaceUri);
            if (package) {
                mPackageDecls.push_back(PackageDecl{ ns->namespacePrefix, package.value() });
                added = true;
            }
        }

        Visitor::visit(ns);

        if (added) {
            mPackageDecls.pop_back();
        }
    }

    Maybe<ResourceName> transformPackage(const ResourceName& name,
                                         const StringPiece16& localPackage) const override {
        if (name.package.empty()) {
            return ResourceName{ localPackage.toString(), name.type, name.entry };
        }

        const auto rend = mPackageDecls.rend();
        for (auto iter = mPackageDecls.rbegin(); iter != rend; ++iter) {
            if (name.package == iter->prefix) {
                if (iter->package.empty()) {
                    return ResourceName{ localPackage.toString(), name.type, name.entry };
                } else {
                    return ResourceName{ iter->package, name.type, name.entry };
                }
            }
        }
        return {};
    }
};

// Implementations

template <typename Derived>
void BaseNode<Derived>::accept(RawVisitor* visitor) {
    visitor->visit(static_cast<Derived*>(this));
}

template <typename T>
struct NodeCastImpl : public RawVisitor {
    using RawVisitor::visit;

    T* value = nullptr;

    void visit(T* v) override {
        value = v;
    }
};

template <typename T>
T* nodeCast(Node* node) {
    NodeCastImpl<T> visitor;
    node->accept(&visitor);
    return visitor.value;
}

} // namespace xml
} // namespace aapt

#endif // AAPT_XML_DOM_H
