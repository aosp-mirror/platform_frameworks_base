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

#include "compile/InlineXmlFormatParser.h"

#include <string>

#include "ResourceUtils.h"
#include "util/Util.h"
#include "xml/XmlDom.h"
#include "xml/XmlUtil.h"

namespace aapt {

namespace {

struct InlineDeclaration {
  xml::Element* el;
  std::string attr_namespace_uri;
  std::string attr_name;
};

// XML Visitor that will find all <aapt:attr> elements for extraction.
class Visitor : public xml::PackageAwareVisitor {
 public:
  using xml::PackageAwareVisitor::Visit;

  explicit Visitor(IAaptContext* context, xml::XmlResource* xml_resource)
      : context_(context), xml_resource_(xml_resource) {}

  void Visit(xml::Element* el) override {
    if (el->namespace_uri != xml::kSchemaAapt || el->name != "attr") {
      xml::PackageAwareVisitor::Visit(el);
      return;
    }

    const Source src = xml_resource_->file.source.WithLine(el->line_number);

    xml::Attribute* attr = el->FindAttribute({}, "name");
    if (!attr) {
      context_->GetDiagnostics()->Error(DiagMessage(src) << "missing 'name' attribute");
      error_ = true;
      return;
    }

    Maybe<Reference> ref = ResourceUtils::ParseXmlAttributeName(attr->value);
    if (!ref) {
      context_->GetDiagnostics()->Error(DiagMessage(src) << "invalid XML attribute '" << attr->value
                                                         << "'");
      error_ = true;
      return;
    }

    const ResourceName& name = ref.value().name.value();
    Maybe<xml::ExtractedPackage> maybe_pkg = TransformPackageAlias(name.package);
    if (!maybe_pkg) {
      context_->GetDiagnostics()->Error(DiagMessage(src)
                                        << "invalid namespace prefix '" << name.package << "'");
      error_ = true;
      return;
    }

    const xml::ExtractedPackage& pkg = maybe_pkg.value();
    const bool private_namespace = pkg.private_namespace || ref.value().private_reference;

    InlineDeclaration decl;
    decl.el = el;
    decl.attr_name = name.entry;

    // We need to differentiate between no-namespace defined, or the alias resolves to an empty
    // package, which means we must use the res-auto schema.
    if (!name.package.empty()) {
      if (pkg.package.empty()) {
        decl.attr_namespace_uri = xml::kSchemaAuto;
      } else {
        decl.attr_namespace_uri = xml::BuildPackageNamespace(pkg.package, private_namespace);
      }
    }

    inline_declarations_.push_back(std::move(decl));
  }

  const std::vector<InlineDeclaration>& GetInlineDeclarations() const {
    return inline_declarations_;
  }

  bool HasError() const {
    return error_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(Visitor);

  IAaptContext* context_;
  xml::XmlResource* xml_resource_;
  std::vector<InlineDeclaration> inline_declarations_;
  bool error_ = false;
};

}  // namespace

bool InlineXmlFormatParser::Consume(IAaptContext* context, xml::XmlResource* doc) {
  Visitor visitor(context, doc);
  doc->root->Accept(&visitor);
  if (visitor.HasError()) {
    return false;
  }

  size_t name_suffix_counter = 0;
  for (const InlineDeclaration& decl : visitor.GetInlineDeclarations()) {
    // Create a new XmlResource with the same ResourceFile as the base XmlResource.
    auto new_doc = util::make_unique<xml::XmlResource>(doc->file);

    // Attach the line number.
    new_doc->file.source.line = decl.el->line_number;

    // Modify the new entry name. We need to suffix the entry with a number to
    // avoid local collisions, then mangle it with the empty package, such that it won't show up
    // in R.java.
    new_doc->file.name.entry = NameMangler::MangleEntry(
        {}, new_doc->file.name.entry + "__" + std::to_string(name_suffix_counter));

    // Extracted elements must be the only child of <aapt:attr>.
    // Make sure there is one root node in the children (ignore empty text).
    for (std::unique_ptr<xml::Node>& child : decl.el->children) {
      const Source child_source = doc->file.source.WithLine(child->line_number);
      if (xml::Text* t = xml::NodeCast<xml::Text>(child.get())) {
        if (!util::TrimWhitespace(t->text).empty()) {
          context->GetDiagnostics()->Error(DiagMessage(child_source)
                                           << "can't extract text into its own resource");
          return false;
        }
      } else if (new_doc->root) {
        context->GetDiagnostics()->Error(DiagMessage(child_source)
                                         << "inline XML resources must have a single root");
        return false;
      } else {
        new_doc->root.reset(static_cast<xml::Element*>(child.release()));
        new_doc->root->parent = nullptr;
        // Copy down the namespace declarations
        new_doc->root->namespace_decls = doc->root->namespace_decls;
        // Recurse for nested inlines
        Consume(context, new_doc.get());
      }
    }

    // Get the parent element of <aapt:attr>
    xml::Element* parent_el = decl.el->parent;
    if (!parent_el) {
      context->GetDiagnostics()->Error(DiagMessage(new_doc->file.source)
                                       << "no suitable parent for inheriting attribute");
      return false;
    }

    // Add the inline attribute to the parent.
    parent_el->attributes.push_back(xml::Attribute{decl.attr_namespace_uri, decl.attr_name,
                                                   "@" + new_doc->file.name.to_string()});

    // Delete the subtree.
    for (auto iter = parent_el->children.begin(); iter != parent_el->children.end(); ++iter) {
      if (iter->get() == decl.el) {
        parent_el->children.erase(iter);
        break;
      }
    }

    queue_.push_back(std::move(new_doc));

    name_suffix_counter++;
  }
  return true;
}

}  // namespace aapt
