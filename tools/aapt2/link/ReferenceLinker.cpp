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

#include "link/ReferenceLinker.h"

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"

#include "Diagnostics.h"
#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "link/Linkers.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "trace/TraceBuffer.h"
#include "util/Util.h"
#include "xml/XmlUtil.h"

using ::aapt::ResourceUtils::StringBuilder;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {
namespace {
struct LoggingResourceName {
  LoggingResourceName(const Reference& ref, const CallSite& callsite,
                      const xml::IPackageDeclStack* decls)
      : ref_(ref), callsite_(callsite), decls_(decls) {
  }

  const Reference& ref_;
  const CallSite& callsite_;
  const xml::IPackageDeclStack* decls_;
};

inline ::std::ostream& operator<<(::std::ostream& out, const LoggingResourceName& name) {
  if (!name.ref_.name) {
    out << name.ref_.id.value();
    return out;
  }

  out << name.ref_.name.value();

  Reference fully_qualified = name.ref_;
  xml::ResolvePackage(name.decls_, &fully_qualified);

  ResourceName& full_name = fully_qualified.name.value();
  if (full_name.package.empty()) {
    full_name.package = name.callsite_.package;
  }

  if (full_name != name.ref_.name.value()) {
    out << " (aka " << full_name << ")";
  }
  return out;
}

}  // namespace

std::unique_ptr<Reference> ReferenceLinkerTransformer::TransformDerived(const Reference* value) {
  auto linked_item =
      ReferenceLinker::LinkReference(callsite_, *value, context_, symbols_, table_, package_decls_);
  if (linked_item) {
    auto linked_item_ptr = linked_item.release();
    if (auto ref = ValueCast<Reference>(linked_item_ptr)) {
      return std::unique_ptr<Reference>(ref);
    }
    context_->GetDiagnostics()->Error(DiagMessage(value->GetSource())
                                      << "value of '"
                                      << LoggingResourceName(*value, callsite_, package_decls_)
                                      << "' must be a resource reference");
    delete linked_item_ptr;
  }

  error_ = true;
  return CloningValueTransformer::TransformDerived(value);
}

std::unique_ptr<Style> ReferenceLinkerTransformer::TransformDerived(const Style* style) {
  // We visit the Style specially because during this phase, values of attributes are either
  // RawString or Reference values. Now that we are expected to resolve all symbols, we can lookup
  // the attributes to find out which types are allowed for the attributes' values.
  auto new_style = CloningValueTransformer::TransformDerived(style);
  if (new_style->parent) {
    new_style->parent = *TransformDerived(&style->parent.value());
  }

  for (Style::Entry& entry : new_style->entries) {
    std::string err_str;

    // Transform the attribute reference so that it is using the fully qualified package
    // name. This will also mark the reference as being able to see private resources if
    // there was a '*' in the reference or if the package came from the private namespace.
    Reference transformed_reference = entry.key;
    ResolvePackage(package_decls_, &transformed_reference);

    // Find the attribute in the symbol table and check if it is visible from this callsite.
    const SymbolTable::Symbol* symbol = ReferenceLinker::ResolveAttributeCheckVisibility(
        transformed_reference, callsite_, context_, symbols_, &err_str);
    if (symbol) {
      // Assign our style key the correct ID. The ID may not exist.
      entry.key.id = symbol->id;

      // Link/resolve the final value if it's a reference.
      entry.value = entry.value->Transform(*this);

      // Try to convert the value to a more specific, typed value based on the attribute it is
      // set to.
      entry.value = ParseValueWithAttribute(std::move(entry.value), symbol->attribute.get());

      // Now verify that the type of this item is compatible with the
      // attribute it is defined for. We pass `nullptr` as the DiagMessage so that this
      // check is fast and we avoid creating a DiagMessage when the match is successful.
      if (!symbol->attribute->Matches(*entry.value, nullptr)) {
        // The actual type of this item is incompatible with the attribute.
        DiagMessage msg(entry.key.GetSource());

        // Call the matches method again, this time with a DiagMessage so we fill in the actual
        // error message.
        symbol->attribute->Matches(*entry.value, &msg);
        context_->GetDiagnostics()->Error(msg);
        error_ = true;
      }
    } else {
      context_->GetDiagnostics()->Error(DiagMessage(entry.key.GetSource())
                                        << "style attribute '"
                                        << LoggingResourceName(entry.key, callsite_, package_decls_)
                                        << "' " << err_str);

      error_ = true;
    }
  }
  return new_style;
}

std::unique_ptr<Item> ReferenceLinkerTransformer::TransformItem(const Reference* value) {
  auto linked_value =
      ReferenceLinker::LinkReference(callsite_, *value, context_, symbols_, table_, package_decls_);
  if (linked_value) {
    return linked_value;
  }
  error_ = true;
  return CloningValueTransformer::TransformDerived(value);
}

// Transform a RawString value into a more specific, appropriate value, based on the
// Attribute. If a non RawString value is passed in, this is an identity transform.
std::unique_ptr<Item> ReferenceLinkerTransformer::ParseValueWithAttribute(
    std::unique_ptr<Item> value, const Attribute* attr) {
  if (RawString* raw_string = ValueCast<RawString>(value.get())) {
    std::unique_ptr<Item> transformed =
        ResourceUtils::TryParseItemForAttribute(*raw_string->value, attr);

    // If we could not parse as any specific type, try a basic STRING.
    if (!transformed && (attr->type_mask & android::ResTable_map::TYPE_STRING)) {
      StringBuilder string_builder;
      string_builder.AppendText(*raw_string->value);
      if (string_builder) {
        transformed = util::make_unique<String>(pool_->MakeRef(string_builder.to_string()));
      }
    }

    if (transformed) {
      return transformed;
    }
  }
  return value;
}

namespace {

class EmptyDeclStack : public xml::IPackageDeclStack {
 public:
  EmptyDeclStack() = default;

  std::optional<xml::ExtractedPackage> TransformPackageAlias(
      const StringPiece& alias) const override {
    if (alias.empty()) {
      return xml::ExtractedPackage{{}, true /*private*/};
    }
    return {};
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(EmptyDeclStack);
};

struct MacroDeclStack : public xml::IPackageDeclStack {
  explicit MacroDeclStack(std::vector<Macro::Namespace> namespaces)
      : alias_namespaces_(std::move(namespaces)) {
  }

  std::optional<xml::ExtractedPackage> TransformPackageAlias(
      const StringPiece& alias) const override {
    if (alias.empty()) {
      return xml::ExtractedPackage{{}, true /*private*/};
    }
    for (auto it = alias_namespaces_.rbegin(); it != alias_namespaces_.rend(); ++it) {
      if (alias == StringPiece(it->alias)) {
        return xml::ExtractedPackage{it->package_name, it->is_private};
      }
    }
    return {};
  }

 private:
  std::vector<Macro::Namespace> alias_namespaces_;
};

// The symbol is visible if it is public, or if the reference to it is requesting private access
// or if the callsite comes from the same package.
bool IsSymbolVisible(const SymbolTable::Symbol& symbol, const Reference& ref,
                     const CallSite& callsite) {
  if (symbol.is_public || ref.private_reference) {
    return true;
  }

  if (ref.name) {
    const ResourceName& name = ref.name.value();
    if (name.package.empty()) {
      // If the symbol was found, and the package is empty, that means it was found in the local
      // scope, which is always visible (private local).
      return true;
    }

    // The symbol is visible if the reference is local to the same package it is defined in.
    return callsite.package == name.package;
  }

  if (ref.id && symbol.id) {
    return ref.id.value().package_id() == symbol.id.value().package_id();
  }
  return false;
}

}  // namespace

const SymbolTable::Symbol* ReferenceLinker::ResolveSymbol(const Reference& reference,
                                                          const CallSite& callsite,
                                                          IAaptContext* context,
                                                          SymbolTable* symbols) {
  if (reference.name) {
    const ResourceName& name = reference.name.value();
    if (name.package.empty()) {
      // Use the callsite's package name if no package name was defined.
      const SymbolTable::Symbol* symbol = symbols->FindByName(
          ResourceName(callsite.package, name.type, name.entry));
      if (symbol) {
        return symbol;
      }

      // If the callsite package is the same as the current compilation package,
      // check the feature split dependencies as well. Feature split resources
      // can be referenced without a namespace, just like the base package.
      if (callsite.package == context->GetCompilationPackage()) {
        const auto& split_name_dependencies = context->GetSplitNameDependencies();
        for (const std::string& split_name : split_name_dependencies) {
          std::string split_package =
              StringPrintf("%s.%s", callsite.package.c_str(), split_name.c_str());
          symbol = symbols->FindByName(ResourceName(split_package, name.type, name.entry));
          if (symbol) {
            return symbol;
          }
        }
      }
      return nullptr;
    }
    return symbols->FindByName(name);
  } else if (reference.id) {
    return symbols->FindById(reference.id.value());
  } else {
    return nullptr;
  }
}

const SymbolTable::Symbol* ReferenceLinker::ResolveSymbolCheckVisibility(const Reference& reference,
                                                                         const CallSite& callsite,
                                                                         IAaptContext* context,
                                                                         SymbolTable* symbols,
                                                                         std::string* out_error) {
  const SymbolTable::Symbol* symbol = ResolveSymbol(reference, callsite, context, symbols);
  if (!symbol) {
    if (out_error) *out_error = "not found";
    return nullptr;
  }

  if (!IsSymbolVisible(*symbol, reference, callsite)) {
    if (out_error) *out_error = "is private";
    return nullptr;
  }
  return symbol;
}

const SymbolTable::Symbol* ReferenceLinker::ResolveAttributeCheckVisibility(
    const Reference& reference, const CallSite& callsite, IAaptContext* context,
    SymbolTable* symbols, std::string* out_error) {
  const SymbolTable::Symbol* symbol =
      ResolveSymbolCheckVisibility(reference, callsite, context, symbols, out_error);
  if (!symbol) {
    return nullptr;
  }

  if (!symbol->attribute) {
    if (out_error) *out_error = "is not an attribute";
    return nullptr;
  }
  return symbol;
}

std::optional<xml::AaptAttribute> ReferenceLinker::CompileXmlAttribute(const Reference& reference,
                                                                       const CallSite& callsite,
                                                                       IAaptContext* context,
                                                                       SymbolTable* symbols,
                                                                       std::string* out_error) {
  const SymbolTable::Symbol* symbol =
      ResolveAttributeCheckVisibility(reference, callsite, context, symbols, out_error);
  if (!symbol) {
    return {};
  }

  if (!symbol->attribute) {
    if (out_error) *out_error = "is not an attribute";
    return {};
  }
  return xml::AaptAttribute(*symbol->attribute, symbol->id);
}

void ReferenceLinker::WriteAttributeName(const Reference& ref, const CallSite& callsite,
                                         const xml::IPackageDeclStack* decls,
                                         DiagMessage* out_msg) {
  CHECK(out_msg != nullptr);
  if (!ref.name) {
    *out_msg << ref.id.value();
    return;
  }

  const ResourceName& ref_name = ref.name.value();
  CHECK_EQ(ref_name.type.type, ResourceType::kAttr);

  if (!ref_name.package.empty()) {
    *out_msg << ref_name.package << ":";
  }
  *out_msg << ref_name.entry;

  Reference fully_qualified = ref;
  xml::ResolvePackage(decls, &fully_qualified);

  ResourceName& full_name = fully_qualified.name.value();
  if (full_name.package.empty()) {
    full_name.package = callsite.package;
  }

  if (full_name != ref.name.value()) {
    *out_msg << " (aka " << full_name.package << ":" << full_name.entry << ")";
  }
}

std::unique_ptr<Item> ReferenceLinker::LinkReference(const CallSite& callsite,
                                                     const Reference& reference,
                                                     IAaptContext* context, SymbolTable* symbols,
                                                     ResourceTable* table,
                                                     const xml::IPackageDeclStack* decls) {
  if (!reference.name && !reference.id) {
    // This is @null.
    return std::make_unique<Reference>(reference);
  }

  Reference transformed_reference = reference;
  xml::ResolvePackage(decls, &transformed_reference);

  if (transformed_reference.name.value().type.type == ResourceType::kMacro) {
    if (transformed_reference.name.value().package.empty()) {
      transformed_reference.name.value().package = callsite.package;
    }

    auto result = table->FindResource(transformed_reference.name.value());
    if (!result || result.value().entry->values.empty()) {
      context->GetDiagnostics()->Error(
          DiagMessage(reference.GetSource())
          << "failed to find definition for "
          << LoggingResourceName(transformed_reference, callsite, decls));
      return {};
    }

    auto& macro_values = result.value().entry->values;
    CHECK(macro_values.size() == 1) << "Macros can only be defined in the default configuration.";

    auto macro = ValueCast<Macro>(macro_values[0]->value.get());
    CHECK(macro != nullptr) << "Value of macro resource is not a Macro (actual "
                            << *macro_values[0]->value << ")";

    // Re-create the state used to parse the macro tag to compile the macro contents as if it was
    // defined inline
    uint32_t type_flags = 0;
    if (reference.type_flags.has_value()) {
      type_flags = reference.type_flags.value();
    }

    MacroDeclStack namespace_stack(macro->alias_namespaces);
    FlattenedXmlSubTree sub_tree{.raw_value = macro->raw_value,
                                 .style_string = macro->style_string,
                                 .untranslatable_sections = macro->untranslatable_sections,
                                 .namespace_resolver = &namespace_stack,
                                 .source = macro->GetSource()};

    auto new_value = ResourceParser::ParseXml(sub_tree, type_flags, reference.allow_raw, *table,
                                              macro_values[0]->config, *context->GetDiagnostics());
    if (new_value == nullptr) {
      context->GetDiagnostics()->Error(
          DiagMessage(reference.GetSource())
          << "failed to substitute macro "
          << LoggingResourceName(transformed_reference, callsite, decls)
          << ": failed to parse contents as one of type(s) " << Attribute::MaskString(type_flags));
      return {};
    }

    if (auto ref = ValueCast<Reference>(new_value.get())) {
      return LinkReference(callsite, *ref, context, symbols, table, decls);
    }
    return new_value;
  }

  std::string err_str;
  const SymbolTable::Symbol* s =
      ResolveSymbolCheckVisibility(transformed_reference, callsite, context, symbols, &err_str);
  if (s) {
    // The ID may not exist. This is fine because of the possibility of building
    // against libraries without assigned IDs.
    // Ex: Linking against own resources when building a static library.
    auto new_ref = std::make_unique<Reference>(reference);
    new_ref->id = s->id;
    new_ref->is_dynamic = s->is_dynamic;
    return std::move(new_ref);
  }

  context->GetDiagnostics()->Error(DiagMessage(reference.GetSource())
                                   << "resource "
                                   << LoggingResourceName(transformed_reference, callsite, decls)
                                   << " " << err_str);
  return {};
}

bool ReferenceLinker::Consume(IAaptContext* context, ResourceTable* table) {
  TRACE_NAME("ReferenceLinker::Consume");
  EmptyDeclStack decl_stack;
  bool error = false;
  for (auto& package : table->packages) {
    // Since we're linking, each package must have a name.
    CHECK(!package->name.empty()) << "all packages being linked must have a name";

    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        // First, unmangle the name if necessary.
        ResourceName name(package->name, type->type, entry->name);
        NameMangler::Unmangle(&name.entry, &name.package);

        // Symbol state information may be lost if there is no value for the resource.
        if (entry->visibility.level != Visibility::Level::kUndefined && entry->values.empty()) {
          context->GetDiagnostics()->Error(DiagMessage(entry->visibility.source)
                                               << "no definition for declared symbol '" << name
                                               << "'");
          error = true;
        }

        // Ensure that definitions for values declared as overlayable exist
        if (entry->overlayable_item && entry->values.empty()) {
          context->GetDiagnostics()->Error(DiagMessage(entry->overlayable_item.value().source)
                                           << "no definition for overlayable symbol '"
                                           << name << "'");
          error = true;
        }

        // The context of this resource is the package in which it is defined.
        const CallSite callsite{name.package};
        ReferenceLinkerTransformer reference_transformer(callsite, context,
                                                         context->GetExternalSymbols(),
                                                         &table->string_pool, table, &decl_stack);

        for (auto& config_value : entry->values) {
          config_value->value = config_value->value->Transform(reference_transformer);
        }

        if (reference_transformer.HasError()) {
          error = true;
        }
      }
    }
  }
  return !error;
}

}  // namespace aapt
