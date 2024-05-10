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

#include <memory>
#include <string>
#include <vector>

#include "Resource.h"
#include "ResourceValues.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/Streams.h"
#include "androidfw/StringPiece.h"
#include "util/Util.h"
#include "xml/XmlUtil.h"

namespace aapt {
namespace xml {

class Element;
class Visitor;
class ConstVisitor;

// Base class for all XML nodes.
class Node {
 public:
  virtual ~Node() = default;

  Element* parent = nullptr;
  size_t line_number = 0u;
  size_t column_number = 0u;
  std::string comment;

  virtual void Accept(Visitor* visitor) = 0;
  virtual void Accept(ConstVisitor* visitor) const = 0;

  using ElementCloneFunc = std::function<void(const Element&, Element*)>;

  // Clones the Node subtree, using the given function to decide how to clone an Element.
  virtual std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) const = 0;
};

// A namespace declaration (xmlns:prefix="uri").
struct NamespaceDecl {
  std::string prefix;
  std::string uri;
  size_t line_number = 0u;
  size_t column_number = 0u;
};

struct AaptAttribute {
  explicit AaptAttribute(const ::aapt::Attribute& attr, const std::optional<ResourceId>& resid = {})
      : attribute(attr), id(resid) {
  }

  aapt::Attribute attribute;
  std::optional<ResourceId> id;
};

// An XML attribute.
struct Attribute {
  std::string namespace_uri;
  std::string name;
  std::string value;

  std::optional<AaptAttribute> compiled_attribute;
  std::unique_ptr<Item> compiled_value;
};

// An Element XML node.
class Element : public Node {
 public:
  // Ordered namespace prefix declarations.
  std::vector<NamespaceDecl> namespace_decls;

  std::string namespace_uri;
  std::string name;
  std::vector<Attribute> attributes;
  std::vector<std::unique_ptr<Node>> children;

  void AppendChild(std::unique_ptr<Node> child);
  void InsertChild(size_t index, std::unique_ptr<Node> child);

  Attribute* FindAttribute(android::StringPiece ns, android::StringPiece name);
  const Attribute* FindAttribute(android::StringPiece ns, android::StringPiece name) const;
  Attribute* FindOrCreateAttribute(android::StringPiece ns, android::StringPiece name);
  void RemoveAttribute(android::StringPiece ns, android::StringPiece name);

  Element* FindChild(android::StringPiece ns, android::StringPiece name);
  const Element* FindChild(android::StringPiece ns, android::StringPiece name) const;

  Element* FindChildWithAttribute(android::StringPiece ns, android::StringPiece name,
                                  android::StringPiece attr_ns, android::StringPiece attr_name,
                                  android::StringPiece attr_value);

  const Element* FindChildWithAttribute(android::StringPiece ns, android::StringPiece name,
                                        android::StringPiece attr_ns,
                                        android::StringPiece attr_name,
                                        android::StringPiece attr_value) const;

  std::vector<Element*> GetChildElements();

  // Due to overriding of subtypes not working with unique_ptr, define a convenience Clone method
  // that knows cloning an element returns an element.
  std::unique_ptr<Element> CloneElement(const ElementCloneFunc& el_cloner) const;

  std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) const override;

  void Accept(Visitor* visitor) override;
  void Accept(ConstVisitor* visitor) const override;
};

// A Text (CDATA) XML node. Can not have any children.
class Text : public Node {
 public:
  std::string text;

  std::unique_ptr<Node> Clone(const ElementCloneFunc& el_cloner) const override;

  void Accept(Visitor* visitor) override;
  void Accept(ConstVisitor* visitor) const override;
};

// An XML resource with a source, name, and XML tree.
class XmlResource {
 public:
  ResourceFile file;

  // StringPool must come before the xml::Node. Destructors are called in reverse order, and
  // the xml::Node may have StringPool references that need to be destroyed before the StringPool
  // is destroyed.
  android::StringPool string_pool;

  std::unique_ptr<xml::Element> root;

  std::unique_ptr<XmlResource> Clone() const;
};

// Inflates an XML DOM from an InputStream, logging errors to the logger.
std::unique_ptr<XmlResource> Inflate(android::InputStream* in, android::IDiagnostics* diag,
                                     const android::Source& source);

// Inflates an XML DOM from a binary ResXMLTree.
std::unique_ptr<XmlResource> Inflate(const void* data, size_t len,
                                     std::string* out_error = nullptr);

Element* FindRootElement(Node* node);

// Visitor whose default implementation visits the children nodes of any node.
class Visitor {
 public:
  virtual ~Visitor() = default;

  virtual void Visit(Element* el) {
    VisitChildren(el);
  }

  virtual void Visit(Text* text) {
  }

 protected:
  Visitor() = default;

  void VisitChildren(Element* el) {
    for (auto& child : el->children) {
      child->Accept(this);
    }
  }

  virtual void BeforeVisitElement(Element* el) {
  }
  virtual void AfterVisitElement(Element* el) {
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(Visitor);

  friend class Element;
};

class ConstVisitor {
 public:
  virtual ~ConstVisitor() = default;

  virtual void Visit(const Element* el) {
    VisitChildren(el);
  }

  virtual void Visit(const Text* text) {
  }

 protected:
  ConstVisitor() = default;

  void VisitChildren(const Element* el) {
    for (const auto& child : el->children) {
      child->Accept(this);
    }
  }

  virtual void BeforeVisitElement(const Element* el) {
  }

  virtual void AfterVisitElement(const Element* el) {
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ConstVisitor);

  friend class Element;
};

// An XML DOM visitor that will record the package name for a namespace prefix.
class PackageAwareVisitor : public Visitor, public IPackageDeclStack {
 public:
  using Visitor::Visit;

  std::optional<ExtractedPackage> TransformPackageAlias(android::StringPiece alias) const override;

 protected:
  PackageAwareVisitor() = default;

  void BeforeVisitElement(Element* el) override;
  void AfterVisitElement(Element* el) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(PackageAwareVisitor);

  struct PackageDecl {
    std::string prefix;
    ExtractedPackage package;
  };

  std::vector<std::vector<PackageDecl>> package_decls_;
};

namespace internal {

// Base class that overrides the default behaviour and does not descend into child nodes.
class NodeCastBase : public ConstVisitor {
 public:
  void Visit(const Element* el) override {
  }
  void Visit(const Text* el) override {
  }

 protected:
  NodeCastBase() = default;

  void BeforeVisitElement(const Element* el) override {
  }
  void AfterVisitElement(const Element* el) override {
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(NodeCastBase);
};

template <typename T>
class NodeCastImpl : public NodeCastBase {
 public:
  using NodeCastBase::Visit;

  NodeCastImpl() = default;

  const T* value = nullptr;

  void Visit(const T* v) override {
    value = v;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(NodeCastImpl);
};

}  // namespace internal

template <typename T>
const T* NodeCast(const Node* node) {
  internal::NodeCastImpl<T> visitor;
  node->Accept(&visitor);
  return visitor.value;
}

template <typename T>
T* NodeCast(Node* node) {
  return const_cast<T*>(NodeCast<T>(static_cast<const T*>(node)));
}

}  // namespace xml
}  // namespace aapt

#endif  // AAPT_XML_DOM_H
