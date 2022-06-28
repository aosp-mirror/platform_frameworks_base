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

#ifndef AAPT_LINKER_REFERENCELINKER_H
#define AAPT_LINKER_REFERENCELINKER_H

#include "android-base/macros.h"

#include "Resource.h"
#include "ResourceValues.h"
#include "link/Linkers.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "xml/XmlDom.h"

namespace aapt {

// A ValueTransformer that returns fully linked versions of resource and macro references.
class ReferenceLinkerTransformer : public CloningValueTransformer {
 public:
  ReferenceLinkerTransformer(const CallSite& callsite, IAaptContext* context, SymbolTable* symbols,
                             StringPool* string_pool, ResourceTable* table,
                             xml::IPackageDeclStack* decl)
      : CloningValueTransformer(string_pool),
        callsite_(callsite),
        context_(context),
        symbols_(symbols),
        table_(table),
        package_decls_(decl) {
  }

  std::unique_ptr<Reference> TransformDerived(const Reference* value) override;
  std::unique_ptr<Item> TransformItem(const Reference* value) override;
  std::unique_ptr<Style> TransformDerived(const Style* value) override;

  bool HasError() {
    return error_;
  }

 private:
  // Transform a RawString value into a more specific, appropriate value, based on the
  // Attribute. If a non RawString value is passed in, this is an identity transform.
  std::unique_ptr<Item> ParseValueWithAttribute(std::unique_ptr<Item> value, const Attribute* attr);

  const CallSite& callsite_;
  IAaptContext* context_;
  SymbolTable* symbols_;
  ResourceTable* table_;
  xml::IPackageDeclStack* package_decls_;
  bool error_ = false;
};

// Resolves all references to resources in the ResourceTable and assigns them IDs.
// The ResourceTable must already have IDs assigned to each resource.
// Once the ResourceTable is processed by this linker, it is ready to be flattened.
class ReferenceLinker : public IResourceTableConsumer {
 public:
  ReferenceLinker() = default;

  // Performs name mangling and looks up the resource in the symbol table. Uses the callsite's
  // package if the reference has no package name defined (implicit).
  // Returns nullptr if the symbol was not found.
  static const SymbolTable::Symbol* ResolveSymbol(const Reference& reference,
                                                  const CallSite& callsite,
                                                  IAaptContext* context,
                                                  SymbolTable* symbols);

  // Performs name mangling and looks up the resource in the symbol table. If the symbol is not
  // visible by the reference at the callsite, nullptr is returned.
  // `out_error` holds the error message.
  static const SymbolTable::Symbol* ResolveSymbolCheckVisibility(const Reference& reference,
                                                                 const CallSite& callsite,
                                                                 IAaptContext* context,
                                                                 SymbolTable* symbols,
                                                                 std::string* out_error);

  // Same as ResolveSymbolCheckVisibility(), but also makes sure the symbol is an attribute.
  // That is, the return value will have a non-null value for ISymbolTable::Symbol::attribute.
  static const SymbolTable::Symbol* ResolveAttributeCheckVisibility(const Reference& reference,
                                                                    const CallSite& callsite,
                                                                    IAaptContext* context,
                                                                    SymbolTable* symbols,
                                                                    std::string* out_error);

  // Resolves the attribute reference and returns an xml::AaptAttribute if successful.
  // If resolution fails, outError holds the error message.
  static std::optional<xml::AaptAttribute> CompileXmlAttribute(const Reference& reference,
                                                               const CallSite& callsite,
                                                               IAaptContext* context,
                                                               SymbolTable* symbols,
                                                               std::string* out_error);

  // Writes the resource name to the DiagMessage, using the
  // "orig_name (aka <transformed_name>)" syntax.
  /*static void WriteResourceName(const Reference& orig, const CallSite& callsite,
                                const xml::IPackageDeclStack* decls, DiagMessage* out_msg);*/

  // Same as WriteResourceName but omits the 'attr' part.
  static void WriteAttributeName(const Reference& ref, const CallSite& callsite,
                                 const xml::IPackageDeclStack* decls, DiagMessage* out_msg);

  // Returns a fully linked version a resource reference.
  //
  // If the reference points to a non-macro resource, the xml::IPackageDeclStack is used to
  // determine the fully qualified name of the referenced resource. If the symbol is visible
  // to the reference at the callsite, a copy of the reference with an updated updated ID is
  // returned.
  //
  // If the reference points to a macro, the ResourceTable is used to find the macro definition and
  // substitute its contents in place of the reference.
  //
  // Returns nullptr on failure, and an error message is logged to the IDiagnostics in the context.
  static std::unique_ptr<Item> LinkReference(const CallSite& callsite, const Reference& reference,
                                             IAaptContext* context, SymbolTable* symbols,
                                             ResourceTable* table,
                                             const xml::IPackageDeclStack* decls);

  // Links all references in the ResourceTable.
  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceLinker);
};

}  // namespace aapt

#endif /* AAPT_LINKER_REFERENCELINKER_H */
