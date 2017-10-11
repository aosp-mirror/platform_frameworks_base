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

#include "XmlDom.h"

#include <expat.h>

#include <memory>
#include <stack>
#include <string>
#include <tuple>

#include "android-base/logging.h"

#include "ResourceUtils.h"
#include "XmlPullParser.h"
#include "util/Util.h"

using android::StringPiece;
using android::StringPiece16;

namespace aapt {
namespace xml {

constexpr char kXmlNamespaceSep = 1;

struct Stack {
  std::unique_ptr<xml::Node> root;
  std::stack<xml::Node*> node_stack;
  std::string pending_comment;
  std::unique_ptr<xml::Text> last_text_node;
};

/**
 * Extracts the namespace and name of an expanded element or attribute name.
 */
static void SplitName(const char* name, std::string* out_ns,
                      std::string* out_name) {
  const char* p = name;
  while (*p != 0 && *p != kXmlNamespaceSep) {
    p++;
  }

  if (*p == 0) {
    out_ns->clear();
    out_name->assign(name);
  } else {
    out_ns->assign(name, (p - name));
    out_name->assign(p + 1);
  }
}

static void FinishPendingText(Stack* stack) {
  if (stack->last_text_node != nullptr) {
    if (!stack->last_text_node->text.empty()) {
      stack->node_stack.top()->AppendChild(std::move(stack->last_text_node));
    } else {
      // Drop an empty text node.
    }
    stack->last_text_node = nullptr;
  }
}

static void AddToStack(Stack* stack, XML_Parser parser,
                       std::unique_ptr<Node> node) {
  node->line_number = XML_GetCurrentLineNumber(parser);
  node->column_number = XML_GetCurrentColumnNumber(parser);

  Node* this_node = node.get();
  if (!stack->node_stack.empty()) {
    stack->node_stack.top()->AppendChild(std::move(node));
  } else {
    stack->root = std::move(node);
  }

  if (!NodeCast<Text>(this_node)) {
    stack->node_stack.push(this_node);
  }
}

static void XMLCALL StartNamespaceHandler(void* user_data, const char* prefix,
                                          const char* uri) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  std::unique_ptr<Namespace> ns = util::make_unique<Namespace>();
  if (prefix) {
    ns->namespace_prefix = prefix;
  }

  if (uri) {
    ns->namespace_uri = uri;
  }

  AddToStack(stack, parser, std::move(ns));
}

static void XMLCALL EndNamespaceHandler(void* user_data, const char* prefix) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  CHECK(!stack->node_stack.empty());
  stack->node_stack.pop();
}

static bool less_attribute(const Attribute& lhs, const Attribute& rhs) {
  return std::tie(lhs.namespace_uri, lhs.name, lhs.value) <
         std::tie(rhs.namespace_uri, rhs.name, rhs.value);
}

static void XMLCALL StartElementHandler(void* user_data, const char* name,
                                        const char** attrs) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  std::unique_ptr<Element> el = util::make_unique<Element>();
  SplitName(name, &el->namespace_uri, &el->name);

  while (*attrs) {
    Attribute attribute;
    SplitName(*attrs++, &attribute.namespace_uri, &attribute.name);
    attribute.value = *attrs++;

    // Insert in sorted order.
    auto iter = std::lower_bound(el->attributes.begin(), el->attributes.end(), attribute,
                                 less_attribute);
    el->attributes.insert(iter, std::move(attribute));
  }

  el->comment = std::move(stack->pending_comment);
  AddToStack(stack, parser, std::move(el));
}

static void XMLCALL EndElementHandler(void* user_data, const char* name) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  CHECK(!stack->node_stack.empty());
  // stack->nodeStack.top()->comment = std::move(stack->pendingComment);
  stack->node_stack.pop();
}

static void XMLCALL CharacterDataHandler(void* user_data, const char* s, int len) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));

  const StringPiece str(s, len);
  if (str.empty()) {
    return;
  }

  // See if we can just append the text to a previous text node.
  if (stack->last_text_node != nullptr) {
    stack->last_text_node->text.append(str.data(), str.size());
    return;
  }

  stack->last_text_node = util::make_unique<Text>();
  stack->last_text_node->line_number = XML_GetCurrentLineNumber(parser);
  stack->last_text_node->column_number = XML_GetCurrentColumnNumber(parser);
  stack->last_text_node->text = str.to_string();
}

static void XMLCALL CommentDataHandler(void* user_data, const char* comment) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  if (!stack->pending_comment.empty()) {
    stack->pending_comment += '\n';
  }
  stack->pending_comment += comment;
}

std::unique_ptr<XmlResource> Inflate(std::istream* in, IDiagnostics* diag, const Source& source) {
  Stack stack;

  XML_Parser parser = XML_ParserCreateNS(nullptr, kXmlNamespaceSep);
  XML_SetUserData(parser, &stack);
  XML_UseParserAsHandlerArg(parser);
  XML_SetElementHandler(parser, StartElementHandler, EndElementHandler);
  XML_SetNamespaceDeclHandler(parser, StartNamespaceHandler, EndNamespaceHandler);
  XML_SetCharacterDataHandler(parser, CharacterDataHandler);
  XML_SetCommentHandler(parser, CommentDataHandler);

  char buffer[1024];
  while (!in->eof()) {
    in->read(buffer, sizeof(buffer) / sizeof(buffer[0]));
    if (in->bad() && !in->eof()) {
      stack.root = {};
      diag->Error(DiagMessage(source) << strerror(errno));
      break;
    }

    if (XML_Parse(parser, buffer, in->gcount(), in->eof()) == XML_STATUS_ERROR) {
      stack.root = {};
      diag->Error(DiagMessage(source.WithLine(XML_GetCurrentLineNumber(parser)))
                  << XML_ErrorString(XML_GetErrorCode(parser)));
      break;
    }
  }

  XML_ParserFree(parser);
  if (stack.root) {
    return util::make_unique<XmlResource>(ResourceFile{{}, {}, source}, StringPool{},
                                          std::move(stack.root));
  }
  return {};
}

static void CopyAttributes(Element* el, android::ResXMLParser* parser, StringPool* out_pool) {
  const size_t attr_count = parser->getAttributeCount();
  if (attr_count > 0) {
    el->attributes.reserve(attr_count);
    for (size_t i = 0; i < attr_count; i++) {
      Attribute attr;
      size_t len;
      const char16_t* str16 = parser->getAttributeNamespace(i, &len);
      if (str16) {
        attr.namespace_uri = util::Utf16ToUtf8(StringPiece16(str16, len));
      }

      str16 = parser->getAttributeName(i, &len);
      if (str16) {
        attr.name = util::Utf16ToUtf8(StringPiece16(str16, len));
      }

      str16 = parser->getAttributeStringValue(i, &len);
      if (str16) {
        attr.value = util::Utf16ToUtf8(StringPiece16(str16, len));
      }

      android::Res_value res_value;
      if (parser->getAttributeValue(i, &res_value) > 0) {
        attr.compiled_value = ResourceUtils::ParseBinaryResValue(
            ResourceType::kAnim, {}, parser->getStrings(), res_value, out_pool);
      }

      el->attributes.push_back(std::move(attr));
    }
  }
}

std::unique_ptr<XmlResource> Inflate(const void* data, size_t data_len, IDiagnostics* diag,
                                     const Source& source) {
  // We import the android namespace because on Windows NO_ERROR is a macro, not
  // an enum, which
  // causes errors when qualifying it with android::
  using namespace android;

  StringPool string_pool;
  std::unique_ptr<Node> root;
  std::stack<Node*> node_stack;

  ResXMLTree tree;
  if (tree.setTo(data, data_len) != NO_ERROR) {
    return {};
  }

  ResXMLParser::event_code_t code;
  while ((code = tree.next()) != ResXMLParser::BAD_DOCUMENT &&
         code != ResXMLParser::END_DOCUMENT) {
    std::unique_ptr<Node> new_node;
    switch (code) {
      case ResXMLParser::START_NAMESPACE: {
        std::unique_ptr<Namespace> node = util::make_unique<Namespace>();
        size_t len;
        const char16_t* str16 = tree.getNamespacePrefix(&len);
        if (str16) {
          node->namespace_prefix = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        str16 = tree.getNamespaceUri(&len);
        if (str16) {
          node->namespace_uri = util::Utf16ToUtf8(StringPiece16(str16, len));
        }
        new_node = std::move(node);
        break;
      }

      case ResXMLParser::START_TAG: {
        std::unique_ptr<Element> node = util::make_unique<Element>();
        size_t len;
        const char16_t* str16 = tree.getElementNamespace(&len);
        if (str16) {
          node->namespace_uri = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        str16 = tree.getElementName(&len);
        if (str16) {
          node->name = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        CopyAttributes(node.get(), &tree, &string_pool);

        new_node = std::move(node);
        break;
      }

      case ResXMLParser::TEXT: {
        std::unique_ptr<Text> node = util::make_unique<Text>();
        size_t len;
        const char16_t* str16 = tree.getText(&len);
        if (str16) {
          node->text = util::Utf16ToUtf8(StringPiece16(str16, len));
        }
        new_node = std::move(node);
        break;
      }

      case ResXMLParser::END_NAMESPACE:
      case ResXMLParser::END_TAG:
        CHECK(!node_stack.empty());
        node_stack.pop();
        break;

      default:
        LOG(FATAL) << "unhandled XML chunk type";
        break;
    }

    if (new_node) {
      new_node->line_number = tree.getLineNumber();

      Node* this_node = new_node.get();
      if (!root) {
        CHECK(node_stack.empty()) << "node stack should be empty";
        root = std::move(new_node);
      } else {
        CHECK(!node_stack.empty()) << "node stack should not be empty";
        node_stack.top()->AppendChild(std::move(new_node));
      }

      if (!NodeCast<Text>(this_node)) {
        node_stack.push(this_node);
      }
    }
  }
  return util::make_unique<XmlResource>(ResourceFile{}, std::move(string_pool), std::move(root));
}

std::unique_ptr<Node> Namespace::Clone(const ElementCloneFunc& el_cloner) {
  auto ns = util::make_unique<Namespace>();
  ns->comment = comment;
  ns->line_number = line_number;
  ns->column_number = column_number;
  ns->namespace_prefix = namespace_prefix;
  ns->namespace_uri = namespace_uri;
  ns->children.reserve(children.size());
  for (const std::unique_ptr<xml::Node>& child : children) {
    ns->AppendChild(child->Clone(el_cloner));
  }
  return std::move(ns);
}

Element* FindRootElement(XmlResource* doc) {
  return FindRootElement(doc->root.get());
}

Element* FindRootElement(Node* node) {
  if (!node) {
    return nullptr;
  }

  Element* el = nullptr;
  while ((el = NodeCast<Element>(node)) == nullptr) {
    if (node->children.empty()) {
      return nullptr;
    }
    // We are looking for the first element, and namespaces can only have one
    // child.
    node = node->children.front().get();
  }
  return el;
}

void Node::AppendChild(std::unique_ptr<Node> child) {
  child->parent = this;
  children.push_back(std::move(child));
}

void Node::InsertChild(size_t index, std::unique_ptr<Node> child) {
  child->parent = this;
  children.insert(children.begin() + index, std::move(child));
}

Attribute* Element::FindAttribute(const StringPiece& ns,
                                  const StringPiece& name) {
  for (auto& attr : attributes) {
    if (ns == attr.namespace_uri && name == attr.name) {
      return &attr;
    }
  }
  return nullptr;
}

const Attribute* Element::FindAttribute(const StringPiece& ns, const StringPiece& name) const {
  for (const auto& attr : attributes) {
    if (ns == attr.namespace_uri && name == attr.name) {
      return &attr;
    }
  }
  return nullptr;
}

Element* Element::FindChild(const StringPiece& ns, const StringPiece& name) {
  return FindChildWithAttribute(ns, name, {}, {}, {});
}

Element* Element::FindChildWithAttribute(const StringPiece& ns,
                                         const StringPiece& name,
                                         const StringPiece& attr_ns,
                                         const StringPiece& attr_name,
                                         const StringPiece& attr_value) {
  for (auto& child_node : children) {
    Node* child = child_node.get();
    while (NodeCast<Namespace>(child)) {
      if (child->children.empty()) {
        break;
      }
      child = child->children[0].get();
    }

    if (Element* el = NodeCast<Element>(child)) {
      if (ns == el->namespace_uri && name == el->name) {
        if (attr_ns.empty() && attr_name.empty()) {
          return el;
        }

        Attribute* attr = el->FindAttribute(attr_ns, attr_name);
        if (attr && attr_value == attr->value) {
          return el;
        }
      }
    }
  }
  return nullptr;
}

std::vector<Element*> Element::GetChildElements() {
  std::vector<Element*> elements;
  for (auto& child_node : children) {
    Node* child = child_node.get();
    while (NodeCast<Namespace>(child)) {
      if (child->children.empty()) {
        break;
      }
      child = child->children[0].get();
    }

    if (Element* el = NodeCast<Element>(child)) {
      elements.push_back(el);
    }
  }
  return elements;
}

std::unique_ptr<Node> Element::Clone(const ElementCloneFunc& el_cloner) {
  auto el = util::make_unique<Element>();
  el->comment = comment;
  el->line_number = line_number;
  el->column_number = column_number;
  el->name = name;
  el->namespace_uri = namespace_uri;
  el->attributes.reserve(attributes.size());
  el_cloner(*this, el.get());
  el->children.reserve(children.size());
  for (const std::unique_ptr<xml::Node>& child : children) {
    el->AppendChild(child->Clone(el_cloner));
  }
  return std::move(el);
}

std::unique_ptr<Node> Text::Clone(const ElementCloneFunc&) {
  auto t = util::make_unique<Text>();
  t->comment = comment;
  t->line_number = line_number;
  t->column_number = column_number;
  t->text = text;
  return std::move(t);
}

void PackageAwareVisitor::Visit(Namespace* ns) {
  bool added = false;
  if (Maybe<ExtractedPackage> maybe_package =
          ExtractPackageFromNamespace(ns->namespace_uri)) {
    ExtractedPackage& package = maybe_package.value();
    package_decls_.push_back(
        PackageDecl{ns->namespace_prefix, std::move(package)});
    added = true;
  }

  Visitor::Visit(ns);

  if (added) {
    package_decls_.pop_back();
  }
}

Maybe<ExtractedPackage> PackageAwareVisitor::TransformPackageAlias(
    const StringPiece& alias, const StringPiece& local_package) const {
  if (alias.empty()) {
    return ExtractedPackage{local_package.to_string(), false /* private */};
  }

  const auto rend = package_decls_.rend();
  for (auto iter = package_decls_.rbegin(); iter != rend; ++iter) {
    if (alias == iter->prefix) {
      if (iter->package.package.empty()) {
        return ExtractedPackage{local_package.to_string(), iter->package.private_namespace};
      }
      return iter->package;
    }
  }
  return {};
}

}  // namespace xml
}  // namespace aapt
