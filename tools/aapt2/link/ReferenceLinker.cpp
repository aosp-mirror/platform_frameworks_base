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

#include "ReferenceLinker.h"

#include "Diagnostics.h"
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
private:
    IAaptContext* mContext;
    ISymbolTable* mSymbols;
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

    void buildAttributeMismatchMessage(DiagMessage* msg, const Attribute* attr,
                                       const Item* value) {
        *msg << "expected";
        if (attr->typeMask & android::ResTable_map::TYPE_BOOLEAN) {
            *msg << " boolean";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_COLOR) {
            *msg << " color";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_DIMENSION) {
            *msg << " dimension";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_ENUM) {
            *msg << " enum";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_FLAGS) {
            *msg << " flags";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_FLOAT) {
            *msg << " float";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_FRACTION) {
            *msg << " fraction";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_INTEGER) {
            *msg << " integer";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_REFERENCE) {
            *msg << " reference";
        }

        if (attr->typeMask & android::ResTable_map::TYPE_STRING) {
            *msg << " string";
        }

        *msg << " but got " << *value;
    }

public:
    using ValueVisitor::visit;

    ReferenceLinkerVisitor(IAaptContext* context, ISymbolTable* symbols, StringPool* stringPool,
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
            const ISymbolTable::Symbol* symbol = ReferenceLinker::resolveAttributeCheckVisibility(
                    transformedReference, mContext->getNameMangler(), mSymbols, mCallSite, &errStr);
            if (symbol) {
                // Assign our style key the correct ID.
                entry.key.id = symbol->id;

                // Try to convert the value to a more specific, typed value based on the
                // attribute it is set to.
                entry.value = parseValueWithAttribute(std::move(entry.value),
                                                      symbol->attribute.get());

                // Link/resolve the final value (mostly if it's a reference).
                entry.value->accept(this);

                // Now verify that the type of this item is compatible with the attribute it
                // is defined for.
                android::Res_value val = {};
                entry.value->flatten(&val);

                // Always allow references.
                const uint32_t typeMask = symbol->attribute->typeMask |
                        android::ResTable_map::TYPE_REFERENCE;

                if (!(typeMask & ResourceUtils::androidTypeToAttributeTypeMask(val.dataType))) {
                    // The actual type of this item is incompatible with the attribute.
                    DiagMessage msg(style->getSource());
                    buildAttributeMismatchMessage(&msg, symbol->attribute.get(), entry.value.get());
                    mContext->getDiagnostics()->error(msg);
                    mError = true;
                }
            } else {
                DiagMessage msg(style->getSource());
                msg << "style attribute '";
                if (entry.key.name) {
                    msg << entry.key.name.value().package << ":" << entry.key.name.value().entry;
                } else {
                    msg << entry.key.id.value();
                }
                msg << "' " << errStr;
                mContext->getDiagnostics()->error(msg);
                mContext->getDiagnostics()->note(DiagMessage(style->getSource()) << entry.key);
                mError = true;
            }
        }
    }

    bool hasError() {
        return mError;
    }
};

} // namespace

/**
 * The symbol is visible if it is public, or if the reference to it is requesting private access
 * or if the callsite comes from the same package.
 */
bool ReferenceLinker::isSymbolVisible(const ISymbolTable::Symbol& symbol, const Reference& ref,
                                      const CallSite& callSite) {
    if (!symbol.isPublic && !ref.privateReference) {
        if (ref.name) {
            return callSite.resource.package == ref.name.value().package;
        } else if (ref.id) {
            return ref.id.value().packageId() == symbol.id.packageId();
        } else {
            return false;
        }
    }
    return true;
}

const ISymbolTable::Symbol* ReferenceLinker::resolveSymbol(const Reference& reference,
                                                           NameMangler* mangler,
                                                           ISymbolTable* symbols) {
    if (reference.name) {
        Maybe<ResourceName> mangled = mangler->mangleName(reference.name.value());
        return symbols->findByName(mangled ? mangled.value() : reference.name.value());
    } else if (reference.id) {
        return symbols->findById(reference.id.value());
    } else {
        return nullptr;
    }
}

const ISymbolTable::Symbol* ReferenceLinker::resolveSymbolCheckVisibility(
        const Reference& reference, NameMangler* nameMangler, ISymbolTable* symbols,
        CallSite* callSite, std::string* outError) {
    const ISymbolTable::Symbol* symbol = resolveSymbol(reference, nameMangler, symbols);
    if (!symbol) {
        std::stringstream errStr;
        errStr << "not found";
        if (outError) *outError = errStr.str();
        return nullptr;
    }

    if (!isSymbolVisible(*symbol, reference, *callSite)) {
        std::stringstream errStr;
        errStr << "is private";
        if (outError) *outError = errStr.str();
        return nullptr;
    }
    return symbol;
}

const ISymbolTable::Symbol* ReferenceLinker::resolveAttributeCheckVisibility(
        const Reference& reference, NameMangler* nameMangler, ISymbolTable* symbols,
        CallSite* callSite, std::string* outError) {
    const ISymbolTable::Symbol* symbol = resolveSymbolCheckVisibility(reference, nameMangler,
                                                                      symbols, callSite,
                                                                      outError);
    if (!symbol) {
        return nullptr;
    }

    if (!symbol->attribute) {
        std::stringstream errStr;
        errStr << "is not an attribute";
        if (outError) *outError = errStr.str();
        return nullptr;
    }
    return symbol;
}

Maybe<xml::AaptAttribute> ReferenceLinker::compileXmlAttribute(const Reference& reference,
                                                               NameMangler* nameMangler,
                                                               ISymbolTable* symbols,
                                                               CallSite* callSite,
                                                               std::string* outError) {
    const ISymbolTable::Symbol* symbol = resolveSymbol(reference, nameMangler, symbols);
    if (!symbol) {
        return {};
    }

    if (!symbol->attribute) {
        std::stringstream errStr;
        errStr << "is not an attribute";
        if (outError) *outError = errStr.str();
        return {};
    }
    return xml::AaptAttribute{ symbol->id, *symbol->attribute };
}

bool ReferenceLinker::linkReference(Reference* reference, IAaptContext* context,
                                    ISymbolTable* symbols, xml::IPackageDeclStack* decls,
                                    CallSite* callSite) {
    assert(reference);
    assert(reference->name || reference->id);

    Reference transformedReference = *reference;
    transformReferenceFromNamespace(decls, context->getCompilationPackage(),
                                    &transformedReference);

    std::string errStr;
    const ISymbolTable::Symbol* s = resolveSymbolCheckVisibility(
            transformedReference, context->getNameMangler(), symbols, callSite, &errStr);
    if (s) {
        reference->id = s->id;
        return true;
    }

    DiagMessage errorMsg(reference->getSource());
    errorMsg << "resource ";
    if (reference->name) {
        errorMsg << reference->name.value();
        if (transformedReference.name.value() != reference->name.value()) {
            errorMsg << " (aka " << transformedReference.name.value() << ")";
        }
    } else {
        errorMsg << reference->id.value();
    }

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
                    configValue.value->accept(&visitor);
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
