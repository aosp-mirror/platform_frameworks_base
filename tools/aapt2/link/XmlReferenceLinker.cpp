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

  ReferenceVisitor(IAaptContext* context, SymbolTable* symbols,
                   xml::IPackageDeclStack* decls, CallSite* callsite)
      : context_(context),
        symbols_(symbols),
        decls_(decls),
        callsite_(callsite),
        error_(false) {}

  void Visit(Reference* ref) override {
    if (!ReferenceLinker::LinkReference(ref, context_, symbols_, decls_,
                                        callsite_)) {
      error_ = true;
    }
  }

  bool HasError() const { return error_; }

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceVisitor);

  IAaptContext* context_;
  SymbolTable* symbols_;
  xml::IPackageDeclStack* decls_;
  CallSite* callsite_;
  bool error_;
};

/**
 * Visits each xml Element and compiles the attributes within.
 */
class XmlVisitor : public xml::PackageAwareVisitor {
 public:
  using xml::PackageAwareVisitor::Visit;

  XmlVisitor(IAaptContext* context, SymbolTable* symbols, const Source& source,
             std::set<int>* sdk_levels_found, CallSite* callsite)
      : context_(context),
        symbols_(symbols),
        source_(source),
        sdk_levels_found_(sdk_levels_found),
        callsite_(callsite),
        reference_visitor_(context, symbols, this, callsite) {}

  void Visit(xml::Element* el) override {
    const Source source = source_.WithLine(el->line_number);
    for (xml::Attribute& attr : el->attributes) {
      Maybe<xml::ExtractedPackage> maybe_package =
          xml::ExtractPackageFromNamespace(attr.namespace_uri);
      if (maybe_package) {
        // There is a valid package name for this attribute. We will look this
        // up.
        StringPiece package = maybe_package.value().package;
        if (package.empty()) {
          // Empty package means the 'current' or 'local' package.
          package = context_->GetCompilationPackage();
        }

        Reference attr_ref(
            ResourceNameRef(package, ResourceType::kAttr, attr.name));
        attr_ref.private_reference = maybe_package.value().private_namespace;

        std::string err_str;
        attr.compiled_attribute = ReferenceLinker::CompileXmlAttribute(
            attr_ref, context_->GetNameMangler(), symbols_, callsite_,
            &err_str);

        // Convert the string value into a compiled Value if this is a valid
        // attribute.
        if (attr.compiled_attribute) {
          if (attr.compiled_attribute.value().id) {
            // Record all SDK levels from which the attributes were defined.
            const size_t sdk_level = FindAttributeSdkLevel(
                attr.compiled_attribute.value().id.value());
            if (sdk_level > 1) {
              sdk_levels_found_->insert(sdk_level);
            }
          }

          const Attribute* attribute =
              &attr.compiled_attribute.value().attribute;
          attr.compiled_value =
              ResourceUtils::TryParseItemForAttribute(attr.value, attribute);
          if (!attr.compiled_value &&
              !(attribute->type_mask & android::ResTable_map::TYPE_STRING)) {
            // We won't be able to encode this as a string.
            context_->GetDiagnostics()->Error(
                DiagMessage(source) << "'" << attr.value << "' "
                                    << "is incompatible with attribute "
                                    << package << ":" << attr.name << " "
                                    << *attribute);
            error_ = true;
          }

        } else {
          context_->GetDiagnostics()->Error(DiagMessage(source)
                                            << "attribute '" << package << ":"
                                            << attr.name << "' " << err_str);
          error_ = true;
        }
      } else if (!attr.compiled_value) {
        // We still encode references, but only if we haven't manually set this
        // to
        // another compiled value.
        attr.compiled_value = ResourceUtils::TryParseReference(attr.value);
      }

      if (attr.compiled_value) {
        // With a compiledValue, we must resolve the reference and assign it an
        // ID.
        attr.compiled_value->SetSource(source);
        attr.compiled_value->Accept(&reference_visitor_);
      }
    }

    // Call the super implementation.
    xml::PackageAwareVisitor::Visit(el);
  }

  bool HasError() { return error_ || reference_visitor_.HasError(); }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlVisitor);

  IAaptContext* context_;
  SymbolTable* symbols_;
  Source source_;
  std::set<int>* sdk_levels_found_;
  CallSite* callsite_;
  ReferenceVisitor reference_visitor_;
  bool error_ = false;
};

}  // namespace

bool XmlReferenceLinker::Consume(IAaptContext* context,
                                 xml::XmlResource* resource) {
  sdk_levels_found_.clear();
  CallSite callsite = {resource->file.name};
  XmlVisitor visitor(context, context->GetExternalSymbols(),
                     resource->file.source, &sdk_levels_found_, &callsite);
  if (resource->root) {
    resource->root->Accept(&visitor);
    return !visitor.HasError();
  }
  return false;
}

}  // namespace aapt
