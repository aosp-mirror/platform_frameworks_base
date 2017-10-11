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

#include <istream>
#include <memory>
#include <string>
#include <vector>

#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "Resource.h"
#include "ResourceValues.h"
#include "util/Util.h"
#include "xml/XmlUtil.h"

namespace aapt {
namespace xml {

class RawVisitor;

class Element;

/**
 * Base class for all XML nodes.
 */
class Node {
 public:
  Node* parent = nullptr;
  size_t line_number = 0;
  size_t column_number = 0;
  std::string comment;
  std::vector<std::unique_ptr<Node>> children;

  virtual ~Node() = default;

  void AppendChild(std::unique_ptr<Node> child);
  void InsertChild(size_t index, std::unique_ptr<Node> child);
  virtual void Accept(RawVisitor* visitor) = 0;

  using ElementCloneFunc = std::function<void(const Element&, Element*)>;

  // Clones the Node subtree, using the given function to decide how to clone an Element.
  virtual std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) = 0;
};

/**
 * Base class that implements the visitor methods for a
 * subclass of Node.
 */
template <typename Derived>
class BaseNode : public Node {
 public:
  virtual void Accept(RawVisitor* visitor) override;
};

/**
 * A Namespace XML node. Can only have one child.
 */
class Namespace : public BaseNode<Namespace> {
 public:
  std::string namespace_prefix;
  std::string namespace_uri;

  std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) override;
};

struct AaptAttribute {
  explicit AaptAttribute(const ::aapt::Attribute& attr, const Maybe<ResourceId>& resid = {})
      : attribute(attr), id(resid) {
  }

  aapt::Attribute attribute;
  Maybe<ResourceId> id;
};

/**
 * An XML attribute.
 */
struct Attribute {
  std::string namespace_uri;
  std::string name;
  std::string value;

  Maybe<AaptAttribute> compiled_attribute;
  std::unique_ptr<Item> compiled_value;
};

/**
 * An Element XML node.
 */
class Element : public BaseNode<Element> {
 public:
  std::string namespace_uri;
  std::string name;
  std::vector<Attribute> attributes;

  Attribute* FindAttribute(const android::StringPiece& ns, const android::StringPiece& name);
  const Attribute* FindAttribute(const android::StringPiece& ns,
                                 const android::StringPiece& name) const;
  xml::Element* FindChild(const android::StringPiece& ns, const android::StringPiece& name);
  xml::Element* FindChildWithAttribute(const android::StringPiece& ns,
                                       const android::StringPiece& name,
                                       const android::StringPiece& attr_ns,
                                       const android::StringPiece& attr_name,
                                       const android::StringPiece& attr_value);
  std::vector<xml::Element*> GetChildElements();
  std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) override;
};

/**
 * A Text (CDATA) XML node. Can not have any children.
 */
class Text : public BaseNode<Text> {
 public:
  std::string text;

  std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) override;
};

/**
 * An XML resource with a source, name, and XML tree.
 */
class XmlResource {
 public:
  ResourceFile file;

  // StringPool must come before the xml::Node. Destructors are called in reverse order, and
  // the xml::Node may have StringPool references that need to be destroyed before the StringPool
  // is destroyed.
  StringPool string_pool;

  std::unique_ptr<xml::Node> root;
};

/**
 * Inflates an XML DOM from a text stream, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<XmlResource> Inflate(std::istream* in, IDiagnostics* diag, const Source& source);

/**
 * Inflates an XML DOM from a binary ResXMLTree, logging errors to the logger.
 * Returns the root node on success, or nullptr on failure.
 */
std::unique_ptr<XmlResource> Inflate(const void* data, size_t data_len, IDiagnostics* diag,
                                     const Source& source);

Element* FindRootElement(XmlResource* doc);
Element* FindRootElement(Node* node);

/**
 * A visitor interface for the different XML Node subtypes. This will not
 * traverse into
 * children. Use Visitor for that.
 */
class RawVisitor {
 public:
  virtual ~RawVisitor() = default;

  virtual void Visit(Namespace* node) {}
  virtual void Visit(Element* node) {}
  virtual void Visit(Text* text) {}
};

/**
 * Visitor whose default implementation visits the children nodes of any node.
 */
class Visitor : public RawVisitor {
 public:
  using RawVisitor::Visit;

  void Visit(Namespace* node) override { VisitChildren(node); }

  void Visit(Element* node) override { VisitChildren(node); }

  void Visit(Text* text) override { VisitChildren(text); }

  void VisitChildren(Node* node) {
    for (auto& child : node->children) {
      child->Accept(this);
    }
  }
};

/**
 * An XML DOM visitor that will record the package name for a namespace prefix.
 */
class PackageAwareVisitor : public Visitor, public IPackageDeclStack {
 public:
  using Visitor::Visit;

  void Visit(Namespace* ns) override;
  Maybe<ExtractedPackage> TransformPackageAlias(
      const android::StringPiece& alias, const android::StringPiece& local_package) const override;

 private:
  struct PackageDecl {
    std::string prefix;
    ExtractedPackage package;
  };

  std::vector<PackageDecl> package_decls_;
};

// Implementations

template <typename Derived>
void BaseNode<Derived>::Accept(RawVisitor* visitor) {
  visitor->Visit(static_cast<Derived*>(this));
}

template <typename T>
class NodeCastImpl : public RawVisitor {
 public:
  using RawVisitor::Visit;

  T* value = nullptr;

  void Visit(T* v) override { value = v; }
};

template <typename T>
T* NodeCast(Node* node) {
  NodeCastImpl<T> visitor;
  node->Accept(&visitor);
  return visitor.value;
}

}  // namespace xml
}  // namespace aapt

#endif  // AAPT_XML_DOM_H
