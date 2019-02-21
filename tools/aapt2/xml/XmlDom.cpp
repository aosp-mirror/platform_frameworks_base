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
#include "trace/TraceBuffer.h"
#include "XmlPullParser.h"
#include "util/Util.h"

using ::aapt::io::InputStream;
using ::android::StringPiece;
using ::android::StringPiece16;

namespace aapt {
namespace xml {

constexpr char kXmlNamespaceSep = 1;

struct Stack {
  std::unique_ptr<xml::Element> root;
  std::stack<xml::Element*> node_stack;
  std::unique_ptr<xml::Element> pending_element;
  std::string pending_comment;
  std::unique_ptr<xml::Text> last_text_node;
};

// Extracts the namespace and name of an expanded element or attribute name.
static void SplitName(const char* name, std::string* out_ns, std::string* out_name) {
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
      CHECK(!stack->node_stack.empty());
      stack->node_stack.top()->AppendChild(std::move(stack->last_text_node));
    } else {
      // Drop an empty text node.
    }
    stack->last_text_node = nullptr;
  }
}

static void XMLCALL StartNamespaceHandler(void* user_data, const char* prefix, const char* uri) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  NamespaceDecl decl;
  decl.line_number = XML_GetCurrentLineNumber(parser);
  decl.column_number = XML_GetCurrentColumnNumber(parser);
  decl.prefix = prefix ? prefix : "";
  decl.uri = uri ? uri : "";

  if (stack->pending_element == nullptr) {
    stack->pending_element = util::make_unique<Element>();
  }
  stack->pending_element->namespace_decls.push_back(std::move(decl));
}

static void XMLCALL EndNamespaceHandler(void* user_data, const char* /*prefix*/) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);
}

static bool less_attribute(const Attribute& lhs, const Attribute& rhs) {
  return std::tie(lhs.namespace_uri, lhs.name, lhs.value) <
         std::tie(rhs.namespace_uri, rhs.name, rhs.value);
}

static void XMLCALL StartElementHandler(void* user_data, const char* name, const char** attrs) {
  XML_Parser parser = reinterpret_cast<XML_Parser>(user_data);
  Stack* stack = reinterpret_cast<Stack*>(XML_GetUserData(parser));
  FinishPendingText(stack);

  std::unique_ptr<Element> el;
  if (stack->pending_element != nullptr) {
    el = std::move(stack->pending_element);
  } else {
    el = util::make_unique<Element>();
  }

  el->line_number = XML_GetCurrentLineNumber(parser);
  el->column_number = XML_GetCurrentColumnNumber(parser);
  el->comment = std::move(stack->pending_comment);

  SplitName(name, &el->namespace_uri, &el->name);

  while (*attrs) {
    Attribute attribute;
    SplitName(*attrs++, &attribute.namespace_uri, &attribute.name);
    attribute.value = *attrs++;
    el->attributes.push_back(std::move(attribute));
  }

  // Sort the attributes.
  std::sort(el->attributes.begin(), el->attributes.end(), less_attribute);

  // Add to the stack.
  Element* this_el = el.get();
  if (!stack->node_stack.empty()) {
    stack->node_stack.top()->AppendChild(std::move(el));
  } else {
    stack->root = std::move(el);
  }
  stack->node_stack.push(this_el);
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

std::unique_ptr<XmlResource> Inflate(InputStream* in, IDiagnostics* diag, const Source& source) {
  Stack stack;

  std::unique_ptr<std::remove_pointer<XML_Parser>::type, decltype(XML_ParserFree)*> parser = {
      XML_ParserCreateNS(nullptr, kXmlNamespaceSep), XML_ParserFree};
  XML_SetUserData(parser.get(), &stack);
  XML_UseParserAsHandlerArg(parser.get());
  XML_SetElementHandler(parser.get(), StartElementHandler, EndElementHandler);
  XML_SetNamespaceDeclHandler(parser.get(), StartNamespaceHandler, EndNamespaceHandler);
  XML_SetCharacterDataHandler(parser.get(), CharacterDataHandler);
  XML_SetCommentHandler(parser.get(), CommentDataHandler);

  const char* buffer = nullptr;
  size_t buffer_size = 0;
  while (in->Next(reinterpret_cast<const void**>(&buffer), &buffer_size)) {
    if (XML_Parse(parser.get(), buffer, buffer_size, false) == XML_STATUS_ERROR) {
      diag->Error(DiagMessage(source.WithLine(XML_GetCurrentLineNumber(parser.get())))
                  << XML_ErrorString(XML_GetErrorCode(parser.get())));
      return {};
    }
  }

  if (in->HadError()) {
    diag->Error(DiagMessage(source) << in->GetError());
    return {};
  } else {
    // Finish off the parsing.
    if (XML_Parse(parser.get(), nullptr, 0u, true) == XML_STATUS_ERROR) {
      diag->Error(DiagMessage(source.WithLine(XML_GetCurrentLineNumber(parser.get())))
                  << XML_ErrorString(XML_GetErrorCode(parser.get())));
      return {};
    }
  }
  return util::make_unique<XmlResource>(ResourceFile{{}, {}, ResourceFile::Type::kUnknown, source},
                                        StringPool{}, std::move(stack.root));
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

      uint32_t res_id = parser->getAttributeNameResID(i);
      if (res_id > 0) {
        attr.compiled_attribute = AaptAttribute(::aapt::Attribute(), {res_id});
      }

      str16 = parser->getAttributeStringValue(i, &len);
      if (str16) {
        attr.value = util::Utf16ToUtf8(StringPiece16(str16, len));
      }

      android::Res_value res_value;
      if (parser->getAttributeValue(i, &res_value) > 0) {
        // Only compile the value if it is not a string, or it is a string that differs from
        // the raw attribute value.
        int32_t raw_value_idx = parser->getAttributeValueStringID(i);
        if (res_value.dataType != android::Res_value::TYPE_STRING || raw_value_idx < 0 ||
            static_cast<uint32_t>(raw_value_idx) != res_value.data) {
          attr.compiled_value = ResourceUtils::ParseBinaryResValue(
              ResourceType::kAnim, {}, parser->getStrings(), res_value, out_pool);
        }
      }

      el->attributes.push_back(std::move(attr));
    }
  }
}

std::unique_ptr<XmlResource> Inflate(const void* data, size_t len, std::string* out_error) {
  TRACE_CALL();
  // We import the android namespace because on Windows NO_ERROR is a macro, not
  // an enum, which causes errors when qualifying it with android::
  using namespace android;

  std::unique_ptr<XmlResource> xml_resource = util::make_unique<XmlResource>();

  std::stack<Element*> node_stack;
  std::unique_ptr<Element> pending_element;

  ResXMLTree tree;
  if (tree.setTo(data, len) != NO_ERROR) {
    if (out_error != nullptr) {
      *out_error = "failed to initialize ResXMLTree";
    }
    return {};
  }

  ResXMLParser::event_code_t code;
  while ((code = tree.next()) != ResXMLParser::BAD_DOCUMENT && code != ResXMLParser::END_DOCUMENT) {
    std::unique_ptr<Node> new_node;
    switch (code) {
      case ResXMLParser::START_NAMESPACE: {
        NamespaceDecl decl;
        decl.line_number = tree.getLineNumber();

        size_t len;
        const char16_t* str16 = tree.getNamespacePrefix(&len);
        if (str16) {
          decl.prefix = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        str16 = tree.getNamespaceUri(&len);
        if (str16) {
          decl.uri = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        if (pending_element == nullptr) {
          pending_element = util::make_unique<Element>();
        }
        pending_element->namespace_decls.push_back(std::move(decl));
        break;
      }

      case ResXMLParser::START_TAG: {
        std::unique_ptr<Element> el;
        if (pending_element != nullptr) {
          el = std::move(pending_element);
        } else {
          el = util::make_unique<Element>();
        }
        el->line_number = tree.getLineNumber();

        size_t len;
        const char16_t* str16 = tree.getElementNamespace(&len);
        if (str16) {
          el->namespace_uri = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        str16 = tree.getElementName(&len);
        if (str16) {
          el->name = util::Utf16ToUtf8(StringPiece16(str16, len));
        }

        Element* this_el = el.get();
        CopyAttributes(el.get(), &tree, &xml_resource->string_pool);

        if (!node_stack.empty()) {
          node_stack.top()->AppendChild(std::move(el));
        } else {
          xml_resource->root = std::move(el);
        }
        node_stack.push(this_el);
        break;
      }

      case ResXMLParser::TEXT: {
        std::unique_ptr<Text> text = util::make_unique<Text>();
        text->line_number = tree.getLineNumber();
        size_t len;
        const char16_t* str16 = tree.getText(&len);
        if (str16) {
          text->text = util::Utf16ToUtf8(StringPiece16(str16, len));
        }
        CHECK(!node_stack.empty());
        node_stack.top()->AppendChild(std::move(text));
        break;
      }

      case ResXMLParser::END_NAMESPACE:
        break;

      case ResXMLParser::END_TAG:
        CHECK(!node_stack.empty());
        node_stack.pop();
        break;

      default:
        LOG(FATAL) << "unhandled XML chunk type";
        break;
    }
  }
  return xml_resource;
}

std::unique_ptr<XmlResource> XmlResource::Clone() const {
  std::unique_ptr<XmlResource> cloned = util::make_unique<XmlResource>(file);
  if (root != nullptr) {
    cloned->root = root->CloneElement([&](const xml::Element& src, xml::Element* dst) {
      dst->attributes.reserve(src.attributes.size());
      for (const xml::Attribute& attr : src.attributes) {
        xml::Attribute cloned_attr;
        cloned_attr.name = attr.name;
        cloned_attr.namespace_uri = attr.namespace_uri;
        cloned_attr.value = attr.value;
        cloned_attr.compiled_attribute = attr.compiled_attribute;
        if (attr.compiled_value != nullptr) {
          cloned_attr.compiled_value.reset(attr.compiled_value->Clone(&cloned->string_pool));
        }
        dst->attributes.push_back(std::move(cloned_attr));
      }
    });
  }
  return cloned;
}

Element* FindRootElement(Node* node) {
  if (node == nullptr) {
    return nullptr;
  }

  while (node->parent != nullptr) {
    node = node->parent;
  }
  return NodeCast<Element>(node);
}

void Element::AppendChild(std::unique_ptr<Node> child) {
  child->parent = this;
  children.push_back(std::move(child));
}

void Element::InsertChild(size_t index, std::unique_ptr<Node> child) {
  child->parent = this;
  children.insert(children.begin() + index, std::move(child));
}

Attribute* Element::FindAttribute(const StringPiece& ns, const StringPiece& name) {
  return const_cast<Attribute*>(static_cast<const Element*>(this)->FindAttribute(ns, name));
}

const Attribute* Element::FindAttribute(const StringPiece& ns, const StringPiece& name) const {
  for (const auto& attr : attributes) {
    if (ns == attr.namespace_uri && name == attr.name) {
      return &attr;
    }
  }
  return nullptr;
}

void Element::RemoveAttribute(const StringPiece& ns, const StringPiece& name) {
  auto new_attr_end = std::remove_if(attributes.begin(), attributes.end(),
    [&](const Attribute& attr) -> bool {
      return ns == attr.namespace_uri && name == attr.name;
    });

  attributes.erase(new_attr_end, attributes.end());
}

Attribute* Element::FindOrCreateAttribute(const StringPiece& ns, const StringPiece& name) {
  Attribute* attr = FindAttribute(ns, name);
  if (attr == nullptr) {
    attributes.push_back(Attribute{ns.to_string(), name.to_string()});
    attr = &attributes.back();
  }
  return attr;
}

Element* Element::FindChild(const StringPiece& ns, const StringPiece& name) {
  return FindChildWithAttribute(ns, name, {}, {}, {});
}

const Element* Element::FindChild(const StringPiece& ns, const StringPiece& name) const {
  return FindChildWithAttribute(ns, name, {}, {}, {});
}

Element* Element::FindChildWithAttribute(const StringPiece& ns, const StringPiece& name,
                                         const StringPiece& attr_ns, const StringPiece& attr_name,
                                         const StringPiece& attr_value) {
  return const_cast<Element*>(static_cast<const Element*>(this)->FindChildWithAttribute(
      ns, name, attr_ns, attr_name, attr_value));
}

const Element* Element::FindChildWithAttribute(const StringPiece& ns, const StringPiece& name,
                                               const StringPiece& attr_ns,
                                               const StringPiece& attr_name,
                                               const StringPiece& attr_value) const {
  for (const auto& child : children) {
    if (const Element* el = NodeCast<Element>(child.get())) {
      if (ns == el->namespace_uri && name == el->name) {
        if (attr_ns.empty() && attr_name.empty()) {
          return el;
        }

        const Attribute* attr = el->FindAttribute(attr_ns, attr_name);
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
    if (Element* child = NodeCast<Element>(child_node.get())) {
      elements.push_back(child);
    }
  }
  return elements;
}

std::unique_ptr<Node> Element::Clone(const ElementCloneFunc& el_cloner) const {
  auto el = util::make_unique<Element>();
  el->namespace_decls = namespace_decls;
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

std::unique_ptr<Element> Element::CloneElement(const ElementCloneFunc& el_cloner) const {
  return std::unique_ptr<Element>(static_cast<Element*>(Clone(el_cloner).release()));
}

void Element::Accept(Visitor* visitor) {
  visitor->BeforeVisitElement(this);
  visitor->Visit(this);
  visitor->AfterVisitElement(this);
}

void Element::Accept(ConstVisitor* visitor) const {
  visitor->BeforeVisitElement(this);
  visitor->Visit(this);
  visitor->AfterVisitElement(this);
}

std::unique_ptr<Node> Text::Clone(const ElementCloneFunc&) const {
  auto t = util::make_unique<Text>();
  t->comment = comment;
  t->line_number = line_number;
  t->column_number = column_number;
  t->text = text;
  return std::move(t);
}

void Text::Accept(Visitor* visitor) {
  visitor->Visit(this);
}

void Text::Accept(ConstVisitor* visitor) const {
  visitor->Visit(this);
}

void PackageAwareVisitor::BeforeVisitElement(Element* el) {
  std::vector<PackageDecl> decls;
  for (const NamespaceDecl& decl : el->namespace_decls) {
    if (Maybe<ExtractedPackage> maybe_package = ExtractPackageFromNamespace(decl.uri)) {
      decls.push_back(PackageDecl{decl.prefix, std::move(maybe_package.value())});
    }
  }
  package_decls_.push_back(std::move(decls));
}

void PackageAwareVisitor::AfterVisitElement(Element* el) {
  package_decls_.pop_back();
}

Maybe<ExtractedPackage> PackageAwareVisitor::TransformPackageAlias(const StringPiece& alias) const {
  if (alias.empty()) {
    return ExtractedPackage{{}, false /*private*/};
  }

  const auto rend = package_decls_.rend();
  for (auto iter = package_decls_.rbegin(); iter != rend; ++iter) {
    const std::vector<PackageDecl>& decls = *iter;
    const auto rend2 = decls.rend();
    for (auto iter2 = decls.rbegin(); iter2 != rend2; ++iter2) {
      const PackageDecl& decl = *iter2;
      if (alias == decl.prefix) {
        if (decl.package.package.empty()) {
          return ExtractedPackage{{}, decl.package.private_namespace};
        }
        return decl.package;
      }
    }
  }
  return {};
}

}  // namespace xml
}  // namespace aapt
