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

#include "Logger.h"
#include "StringPiece.h"

#include <istream>
#include <libexpat/expat.h>
#include <memory>
#include <string>
#include <vector>

namespace aapt {
namespace xml {

struct Visitor;

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
    NodeType type;
    Node* parent;
    size_t lineNumber;
    size_t columnNumber;
    std::u16string comment;
    std::vector<std::unique_ptr<Node>> children;

    Node(NodeType type);
    void addChild(std::unique_ptr<Node> child);
    virtual std::unique_ptr<Node> clone() const = 0;
    virtual void accept(Visitor* visitor) = 0;
    virtual ~Node() {}
};

/**
 * Base class that implements the visitor methods for a
 * subclass of Node.
 */
template <typename Derived>
struct BaseNode : public Node {
    BaseNode(NodeType t);
    virtual void accept(Visitor* visitor) override;
};

/**
 * A Namespace XML node. Can only have one child.
 */
struct Namespace : public BaseNode<Namespace> {
    std::u16string namespacePrefix;
    std::u16string namespaceUri;

    Namespace();
    virtual std::unique_ptr<Node> clone() const override;
};

/**
 * An XML attribute.
 */
struct Attribute {
    std::u16string namespaceUri;
    std::u16string name;
    std::u16string value;
};

/**
 * An Element XML node.
 */
struct Element : public BaseNode<Element> {
    std::u16string namespaceUri;
    std::u16string name;
    std::vector<Attribute> attributes;

    Element();
    virtual std::unique_ptr<Node> clone() const override;
    Attribute* findAttribute(const StringPiece16& ns, const StringPiece16& name);
    xml::Element* findChild(const StringPiece16& ns, const StringPiece16& name);
    xml::Element* findChildWithAttribute(const StringPiece16& ns, const StringPiece16& name,
                                         const xml::Attribute* reqAttr);
    std::vector<xml::Element*> getChildElements();
};

/**
 * A Text (CDATA) XML node. Can not have any children.
 */
struct Text : public BaseNode<Text> {
    std::u16string text;

    Text();
    virtual std::unique_ptr<Node> clone() const override;
};

/**
 * Inflates an XML DOM from a text stream, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<Node> inflate(std::istream* in, SourceLogger* logger);

/**
 * Inflates an XML DOM from a binary ResXMLTree, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<Node> inflate(const void* data, size_t dataLen, SourceLogger* logger);

/**
 * A visitor interface for the different XML Node subtypes.
 */
struct Visitor {
    virtual void visit(Namespace* node) = 0;
    virtual void visit(Element* node) = 0;
    virtual void visit(Text* text) = 0;
};

// Implementations

template <typename Derived>
BaseNode<Derived>::BaseNode(NodeType type) : Node(type) {
}

template <typename Derived>
void BaseNode<Derived>::accept(Visitor* visitor) {
    visitor->visit(static_cast<Derived*>(this));
}

} // namespace xml
} // namespace aapt

#endif // AAPT_XML_DOM_H
