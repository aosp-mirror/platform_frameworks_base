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

#include "link/Linkers.h"

#include "androidfw/ResourceTypes.h"

#include "Diagnostics.h"
#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "link/ReferenceLinker.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {

namespace {

/**
 * Visits all references (including parents of styles, references in styles,
 * arrays, etc) and
 * links their symbolic name to their Resource ID, performing mangling and
 * package aliasing
 * as needed.
 */
class ReferenceVisitor : public ValueVisitor {
 public:
  using ValueVisitor::Visit;

  ReferenceVisitor(const CallSite& callsite, IAaptContext* context, SymbolTable* symbols,
                   xml::IPackageDeclStack* decls)
      : callsite_(callsite), context_(context), symbols_(symbols), decls_(decls), error_(false) {}

  void Visit(Reference* ref) override {
    if (!ReferenceLinker::LinkReference(callsite_, ref, context_, symbols_, decls_)) {
      error_ = true;
    }
  }

  bool HasError() const { return error_; }

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceVisitor);

  const CallSite& callsite_;
  IAaptContext* context_;
  SymbolTable* symbols_;
  xml::IPackageDeclStack* decls_;
  bool error_;
};

/**
 * Visits each xml Element and compiles the attributes within.
 */
class XmlVisitor : public xml::PackageAwareVisitor {
 public:
  using xml::PackageAwareVisitor::Visit;

  XmlVisitor(const Source& source, const CallSite& callsite, IAaptContext* context,
             SymbolTable* symbols)
      : source_(source),
        callsite_(callsite),
        context_(context),
        symbols_(symbols),
        reference_visitor_(callsite, context, symbols, this) {
  }

  void Visit(xml::Element* el) override {
    // The default Attribute allows everything except enums or flags.
    constexpr const static uint32_t kDefaultTypeMask =
        0xffffffffu & ~(android::ResTable_map::TYPE_ENUM | android::ResTable_map::TYPE_FLAGS);
    const static Attribute kDefaultAttribute(true /* weak */, kDefaultTypeMask);

    const Source source = source_.WithLine(el->line_number);
    for (xml::Attribute& attr : el->attributes) {
      // If the attribute has no namespace, interpret values as if
      // they were assigned to the default Attribute.

      const Attribute* attribute = &kDefaultAttribute;
      std::string attribute_package;

      if (Maybe<xml::ExtractedPackage> maybe_package =
              xml::ExtractPackageFromNamespace(attr.namespace_uri)) {
        // There is a valid package name for this attribute. We will look this up.
        attribute_package = maybe_package.value().package;
        if (attribute_package.empty()) {
          // Empty package means the 'current' or 'local' package.
          attribute_package = context_->GetCompilationPackage();
        }

        Reference attr_ref(ResourceNameRef(attribute_package, ResourceType::kAttr, attr.name));
        attr_ref.private_reference = maybe_package.value().private_namespace;

        std::string err_str;
        attr.compiled_attribute =
            ReferenceLinker::CompileXmlAttribute(attr_ref, callsite_, symbols_, &err_str);

        if (!attr.compiled_attribute) {
          context_->GetDiagnostics()->Error(DiagMessage(source) << "attribute '"
                                                                << attribute_package << ":"
                                                                << attr.name << "' " << err_str);
          error_ = true;
          continue;
        }

        attribute = &attr.compiled_attribute.value().attribute;
      }

      attr.compiled_value = ResourceUtils::TryParseItemForAttribute(attr.value, attribute);
      if (attr.compiled_value) {
        // With a compiledValue, we must resolve the reference and assign it an ID.
        attr.compiled_value->SetSource(source);
        attr.compiled_value->Accept(&reference_visitor_);
      } else if ((attribute->type_mask & android::ResTable_map::TYPE_STRING) == 0) {
        // We won't be able to encode this as a string.
        DiagMessage msg(source);
        msg << "'" << attr.value << "' "
            << "is incompatible with attribute ";
        if (!attribute_package.empty()) {
          msg << attribute_package << ":";
        }
        msg << attr.name << " " << *attribute;
        context_->GetDiagnostics()->Error(msg);
        error_ = true;
      }
    }

    // Call the super implementation.
    xml::PackageAwareVisitor::Visit(el);
  }

  bool HasError() { return error_ || reference_visitor_.HasError(); }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlVisitor);

  Source source_;
  const CallSite& callsite_;
  IAaptContext* context_;
  SymbolTable* symbols_;

  ReferenceVisitor reference_visitor_;
  bool error_ = false;
};

}  // namespace

bool XmlReferenceLinker::Consume(IAaptContext* context, xml::XmlResource* resource) {
  const CallSite callsite = {resource->file.name};
  XmlVisitor visitor(resource->file.source, callsite, context, context->GetExternalSymbols());
  if (resource->root) {
    resource->root->Accept(&visitor);
    return !visitor.HasError();
  }
  return false;
}

}  // namespace aapt
