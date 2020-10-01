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
  static Maybe<xml::AaptAttribute> CompileXmlAttribute(const Reference& reference,
                                                       const CallSite& callsite,
                                                       IAaptContext* context,
                                                       SymbolTable* symbols,
                                                       std::string* out_error);

  // Writes the resource name to the DiagMessage, using the
  // "orig_name (aka <transformed_name>)" syntax.
  static void WriteResourceName(const Reference& orig, const CallSite& callsite,
                                const xml::IPackageDeclStack* decls, DiagMessage* out_msg);

  // Same as WriteResourceName but omits the 'attr' part.
  static void WriteAttributeName(const Reference& ref, const CallSite& callsite,
                                 const xml::IPackageDeclStack* decls, DiagMessage* out_msg);

  // Transforms the package name of the reference to the fully qualified package name using
  // the xml::IPackageDeclStack, then mangles and looks up the symbol. If the symbol is visible
  // to the reference at the callsite, the reference is updated with an ID.
  // Returns false on failure, and an error message is logged to the IDiagnostics in the context.
  static bool LinkReference(const CallSite& callsite, Reference* reference, IAaptContext* context,
                            SymbolTable* symbols, const xml::IPackageDeclStack* decls);

  // Links all references in the ResourceTable.
  bool Consume(IAaptContext* context, ResourceTable* table) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceLinker);
};

}  // namespace aapt

#endif /* AAPT_LINKER_REFERENCELINKER_H */
