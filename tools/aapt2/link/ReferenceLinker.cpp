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

#include "Diagnostics.h"
#include "ReferenceLinker.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "link/Linkers.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "util/Util.h"
#include "xml/XmlUtil.h"

#include <androidfw/ResourceTypes.h>
#include <cassert>

namespace aapt {

namespace {

/**
 * The ReferenceLinkerVisitor will follow all references and make sure they point
 * to resources that actually exist, either in the local resource table, or as external
 * symbols. Once the target resource has been found, the ID of the resource will be assigned
 * to the reference object.
 *
 * NOTE: All of the entries in the ResourceTable must be assigned IDs.
 */
class ReferenceLinkerVisitor : public ValueVisitor {
public:
    using ValueVisitor::visit;

    ReferenceLinkerVisitor(IAaptContext* context, SymbolTable* symbols, StringPool* stringPool,
                           xml::IPackageDeclStack* decl,CallSite* callSite) :
            mContext(context), mSymbols(symbols), mPackageDecls(decl), mStringPool(stringPool),
            mCallSite(callSite) {
    }

    void visit(Reference* ref) override {
        if (!ReferenceLinker::linkReference(ref, mContext, mSymbols, mPackageDecls, mCallSite)) {
            mError = true;
        }
    }

    /**
     * We visit the Style specially because during this phase, values of attributes are
     * all RawString values. Now that we are expected to resolve all symbols, we can
     * lookup the attributes to find out which types are allowed for the attributes' values.
     */
    void visit(Style* style) override {
        if (style->parent) {
            visit(&style->parent.value());
        }

        for (Style::Entry& entry : style->entries) {
            std::string errStr;

            // Transform the attribute reference so that it is using the fully qualified package
            // name. This will also mark the reference as being able to see private resources if
            // there was a '*' in the reference or if the package came from the private namespace.
            Reference transformedReference = entry.key;
            transformReferenceFromNamespace(mPackageDecls, mContext->getCompilationPackage(),
                                            &transformedReference);

            // Find the attribute in the symbol table and check if it is visible from this callsite.
            const SymbolTable::Symbol* symbol = ReferenceLinker::resolveAttributeCheckVisibility(
                    transformedReference, mContext->getNameMangler(), mSymbols, mCallSite, &errStr);
            if (symbol) {
                // Assign our style key the correct ID.
                // The ID may not exist.
                entry.key.id = symbol->id;

                // Try to convert the value to a more specific, typed value based on the
                // attribute it is set to.
                entry.value = parseValueWithAttribute(std::move(entry.value),
                                                      symbol->attribute.get());

                // Link/resolve the final value (mostly if it's a reference).
                entry.value->accept(this);

                // Now verify that the type of this item is compatible with the attribute it
                // is defined for. We pass `nullptr` as the DiagMessage so that this check is
                // fast and we avoid creating a DiagMessage when the match is successful.
                if (!symbol->attribute->matches(entry.value.get(), nullptr)) {
                    // The actual type of this item is incompatible with the attribute.
                    DiagMessage msg(entry.key.getSource());

                    // Call the matches method again, this time with a DiagMessage so we fill
                    // in the actual error message.
                    symbol->attribute->matches(entry.value.get(), &msg);
                    mContext->getDiagnostics()->error(msg);
                    mError = true;
                }

            } else {
                DiagMessage msg(entry.key.getSource());
                msg << "style attribute '";
                ReferenceLinker::writeResourceName(&msg, entry.key, transformedReference);
                msg << "' " << errStr;
                mContext->getDiagnostics()->error(msg);
                mError = true;
            }
        }
    }

    bool hasError() {
        return mError;
    }

private:
    IAaptContext* mContext;
    SymbolTable* mSymbols;
    xml::IPackageDeclStack* mPackageDecls;
    StringPool* mStringPool;
    CallSite* mCallSite;
    bool mError = false;

    /**
     * Transform a RawString value into a more specific, appropriate value, based on the
     * Attribute. If a non RawString value is passed in, this is an identity transform.
     */
    std::unique_ptr<Item> parseValueWithAttribute(std::unique_ptr<Item> value,
                                                  const Attribute* attr) {
        if (RawString* rawString = valueCast<RawString>(value.get())) {
            std::unique_ptr<Item> transformed =
                    ResourceUtils::parseItemForAttribute(*rawString->value, attr);

            // If we could not parse as any specific type, try a basic STRING.
            if (!transformed && (attr->typeMask & android::ResTable_map::TYPE_STRING)) {
                util::StringBuilder stringBuilder;
                stringBuilder.append(*rawString->value);
                if (stringBuilder) {
                    transformed = util::make_unique<String>(
                            mStringPool->makeRef(stringBuilder.str()));
                }
            }

            if (transformed) {
                return transformed;
            }
        };
        return value;
    }
};

} // namespace

/**
 * The symbol is visible if it is public, or if the reference to it is requesting private access
 * or if the callsite comes from the same package.
 */
bool ReferenceLinker::isSymbolVisible(const SymbolTable::Symbol& symbol, const Reference& ref,
                                      const CallSite& callSite) {
    if (!symbol.isPublic && !ref.privateReference) {
        if (ref.name) {
            return callSite.resource.package == ref.name.value().package;
        } else if (ref.id && symbol.id) {
            return ref.id.value().packageId() == symbol.id.value().packageId();
        } else {
            return false;
        }
    }
    return true;
}

const SymbolTable::Symbol* ReferenceLinker::resolveSymbol(const Reference& reference,
                                                          NameMangler* mangler,
                                                          SymbolTable* symbols) {
    if (reference.name) {
        Maybe<ResourceName> mangled = mangler->mangleName(reference.name.value());
        return symbols->findByName(mangled ? mangled.value() : reference.name.value());
    } else if (reference.id) {
        return symbols->findById(reference.id.value());
    } else {
        return nullptr;
    }
}

const SymbolTable::Symbol* ReferenceLinker::resolveSymbolCheckVisibility(
        const Reference& reference, NameMangler* nameMangler, SymbolTable* symbols,
        CallSite* callSite, std::string* outError) {
    const SymbolTable::Symbol* symbol = resolveSymbol(reference, nameMangler, symbols);
    if (!symbol) {
        if (outError) *outError = "not found";
        return nullptr;
    }

    if (!isSymbolVisible(*symbol, reference, *callSite)) {
        if (outError) *outError = "is private";
        return nullptr;
    }
    return symbol;
}

const SymbolTable::Symbol* ReferenceLinker::resolveAttributeCheckVisibility(
        const Reference& reference, NameMangler* nameMangler, SymbolTable* symbols,
        CallSite* callSite, std::string* outError) {
    const SymbolTable::Symbol* symbol = resolveSymbolCheckVisibility(reference, nameMangler,
                                                                     symbols, callSite,
                                                                     outError);
    if (!symbol) {
        return nullptr;
    }

    if (!symbol->attribute) {
        if (outError) *outError = "is not an attribute";
        return nullptr;
    }
    return symbol;
}

Maybe<xml::AaptAttribute> ReferenceLinker::compileXmlAttribute(const Reference& reference,
                                                               NameMangler* nameMangler,
                                                               SymbolTable* symbols,
                                                               CallSite* callSite,
                                                               std::string* outError) {
    const SymbolTable::Symbol* symbol = resolveSymbol(reference, nameMangler, symbols);
    if (!symbol) {
        return {};
    }

    if (!symbol->attribute) {
        if (outError) *outError = "is not an attribute";
        return {};
    }
    return xml::AaptAttribute{ symbol->id, *symbol->attribute };
}

void ReferenceLinker::writeResourceName(DiagMessage* outMsg, const Reference& orig,
                                        const Reference& transformed) {
    assert(outMsg);

    if (orig.name) {
        *outMsg << orig.name.value();
        if (transformed.name.value() != orig.name.value()) {
            *outMsg << " (aka " << transformed.name.value() << ")";
        }
    } else {
        *outMsg << orig.id.value();
    }
}

bool ReferenceLinker::linkReference(Reference* reference, IAaptContext* context,
                                    SymbolTable* symbols, xml::IPackageDeclStack* decls,
                                    CallSite* callSite) {
    assert(reference);
    assert(reference->name || reference->id);

    Reference transformedReference = *reference;
    transformReferenceFromNamespace(decls, context->getCompilationPackage(),
                                    &transformedReference);

    std::string errStr;
    const SymbolTable::Symbol* s = resolveSymbolCheckVisibility(
            transformedReference, context->getNameMangler(), symbols, callSite, &errStr);
    if (s) {
        // The ID may not exist. This is fine because of the possibility of building against
        // libraries without assigned IDs.
        // Ex: Linking against own resources when building a static library.
        reference->id = s->id;
        return true;
    }

    DiagMessage errorMsg(reference->getSource());
    errorMsg << "resource ";
    writeResourceName(&errorMsg, *reference, transformedReference);
    errorMsg << " " << errStr;
    context->getDiagnostics()->error(errorMsg);
    return false;
}

namespace {

struct EmptyDeclStack : public xml::IPackageDeclStack {
    Maybe<xml::ExtractedPackage> transformPackageAlias(
            const StringPiece16& alias, const StringPiece16& localPackage) const override {
        if (alias.empty()) {
            return xml::ExtractedPackage{ localPackage.toString(), true /* private */ };
        }
        return {};
    }
};

} // namespace

bool ReferenceLinker::consume(IAaptContext* context, ResourceTable* table) {
    EmptyDeclStack declStack;
    bool error = false;
    for (auto& package : table->packages) {
        for (auto& type : package->types) {
            for (auto& entry : type->entries) {
                // Symbol state information may be lost if there is no value for the resource.
                if (entry->symbolStatus.state != SymbolState::kUndefined && entry->values.empty()) {
                    context->getDiagnostics()->error(
                            DiagMessage(entry->symbolStatus.source)
                            << "no definition for declared symbol '"
                            << ResourceNameRef(package->name, type->type, entry->name)
                            << "'");
                    error = true;
                }

                CallSite callSite = { ResourceNameRef(package->name, type->type, entry->name) };
                ReferenceLinkerVisitor visitor(context, context->getExternalSymbols(),
                                               &table->stringPool, &declStack, &callSite);

                for (auto& configValue : entry->values) {
                    configValue->value->accept(&visitor);
                }

                if (visitor.hasError()) {
                    error = true;
                }
            }
        }
    }
    return !error;
}

} // namespace aapt
