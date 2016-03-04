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

#include "Resource.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "link/Linkers.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "xml/XmlDom.h"

#include <cassert>

namespace aapt {

/**
 * Resolves all references to resources in the ResourceTable and assigns them IDs.
 * The ResourceTable must already have IDs assigned to each resource.
 * Once the ResourceTable is processed by this linker, it is ready to be flattened.
 */
struct ReferenceLinker : public IResourceTableConsumer {
    /**
     * Returns true if the symbol is visible by the reference and from the callsite.
     */
    static bool isSymbolVisible(const SymbolTable::Symbol& symbol, const Reference& ref,
                                const CallSite& callSite);

    /**
     * Performs name mangling and looks up the resource in the symbol table. Returns nullptr
     * if the symbol was not found.
     */
    static const SymbolTable::Symbol* resolveSymbol(const Reference& reference,
                                                    NameMangler* mangler, SymbolTable* symbols);

    /**
     * Performs name mangling and looks up the resource in the symbol table. If the symbol is
     * not visible by the reference at the callsite, nullptr is returned. outError holds
     * the error message.
     */
    static const SymbolTable::Symbol* resolveSymbolCheckVisibility(const Reference& reference,
                                                                   NameMangler* nameMangler,
                                                                   SymbolTable* symbols,
                                                                   CallSite* callSite,
                                                                   std::string* outError);

    /**
     * Same as resolveSymbolCheckVisibility(), but also makes sure the symbol is an attribute.
     * That is, the return value will have a non-null value for ISymbolTable::Symbol::attribute.
     */
    static const SymbolTable::Symbol* resolveAttributeCheckVisibility(const Reference& reference,
                                                                      NameMangler* nameMangler,
                                                                      SymbolTable* symbols,
                                                                      CallSite* callSite,
                                                                      std::string* outError);

    /**
     * Resolves the attribute reference and returns an xml::AaptAttribute if successful.
     * If resolution fails, outError holds the error message.
     */
    static Maybe<xml::AaptAttribute> compileXmlAttribute(const Reference& reference,
                                                         NameMangler* nameMangler,
                                                         SymbolTable* symbols,
                                                         CallSite* callSite,
                                                         std::string* outError);

    /**
     * Writes the resource name to the DiagMessage, using the "orig_name (aka <transformed_name>)"
     * syntax.
     */
    static void writeResourceName(DiagMessage* outMsg, const Reference& orig,
                                  const Reference& transformed);

    /**
     * Transforms the package name of the reference to the fully qualified package name using
     * the xml::IPackageDeclStack, then mangles and looks up the symbol. If the symbol is visible
     * to the reference at the callsite, the reference is updated with an ID.
     * Returns false on failure, and an error message is logged to the IDiagnostics in the context.
     */
    static bool linkReference(Reference* reference, IAaptContext* context, SymbolTable* symbols,
                              xml::IPackageDeclStack* decls, CallSite* callSite);

    /**
     * Links all references in the ResourceTable.
     */
    bool consume(IAaptContext* context, ResourceTable* table) override;
};

} // namespace aapt

#endif /* AAPT_LINKER_REFERENCELINKER_H */
