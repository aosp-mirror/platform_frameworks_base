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
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "util/Util.h"
#include "ValueVisitor.h"

#include "link/Linkers.h"
#include "link/ReferenceLinkerVisitor.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"

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
class StyleAndReferenceLinkerVisitor : public ValueVisitor {
private:
    ReferenceLinkerVisitor mReferenceVisitor;
    IAaptContext* mContext;
    ISymbolTable* mSymbols;
    IPackageDeclStack* mPackageDecls;
    StringPool* mStringPool;
    bool mError = false;

    const ISymbolTable::Symbol* findAttributeSymbol(Reference* reference) {
        assert(reference);
        assert(reference->name || reference->id);

        if (reference->name) {
            // Transform the package name if it is an alias.
            Maybe<ResourceName> realName = mPackageDecls->transformPackage(
                    reference->name.value(), mContext->getCompilationPackage());

            // Mangle the reference name if it should be mangled.
            Maybe<ResourceName> mangledName = mContext->getNameMangler()->mangleName(
                    realName ? realName.value() : reference->name.value());

            const ISymbolTable::Symbol* s = nullptr;
            if (mangledName) {
                s = mSymbols->findByName(mangledName.value());
            } else if (realName) {
                s = mSymbols->findByName(realName.value());
            } else {
                s = mSymbols->findByName(reference->name.value());
            }

            if (s && s->attribute) {
                return s;
            }
        }

        if (reference->id) {
            if (const ISymbolTable::Symbol* s = mSymbols->findById(reference->id.value())) {
                if (s->attribute) {
                    return s;
                }
            }
        }
        return nullptr;
    }

    /**
     * Transform a RawString value into a more specific, appropriate value, based on the
     * Attribute. If a non RawString value is passed in, this is an identity transform.
     */
    std::unique_ptr<Item> parseValueWithAttribute(std::unique_ptr<Item> value,
                                                  const Attribute* attr) {
        if (RawString* rawString = valueCast<RawString>(value.get())) {
            std::unique_ptr<Item> transformed = ResourceUtils::parseItemForAttribute(
                    *rawString->value, attr);

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

    StyleAndReferenceLinkerVisitor(IAaptContext* context, ISymbolTable* symbols,
                                   StringPool* stringPool, IPackageDeclStack* decl) :
            mReferenceVisitor(context, symbols, decl), mContext(context), mSymbols(symbols),
            mPackageDecls(decl), mStringPool(stringPool) {
    }

    void visit(Reference* reference) override {
        mReferenceVisitor.visit(reference);
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
            if (const ISymbolTable::Symbol* s = findAttributeSymbol(&entry.key)) {
                // Assign our style key the correct ID.
                entry.key.id = s->id;

                // Try to convert the value to a more specific, typed value based on the
                // attribute it is set to.
                entry.value = parseValueWithAttribute(std::move(entry.value), s->attribute.get());

                // Link/resolve the final value (mostly if it's a reference).
                entry.value->accept(this);

                // Now verify that the type of this item is compatible with the attribute it
                // is defined for.
                android::Res_value val = {};
                entry.value->flatten(&val);

                // Always allow references.
                const uint32_t typeMask = s->attribute->typeMask |
                        android::ResTable_map::TYPE_REFERENCE;

                if (!(typeMask & ResourceUtils::androidTypeToAttributeTypeMask(val.dataType))) {
                    // The actual type of this item is incompatible with the attribute.
                    DiagMessage msg(style->getSource());
                    buildAttributeMismatchMessage(&msg, s->attribute.get(), entry.value.get());
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
                msg << "' not found";
                mContext->getDiagnostics()->error(msg);
                mError = true;
            }
        }
    }

    inline bool hasError() {
        return mError || mReferenceVisitor.hasError();
    }
};

struct EmptyDeclStack : public IPackageDeclStack {
    Maybe<ResourceName> transformPackage(const ResourceName& name,
                                         const StringPiece16& localPackage) const override {
        if (name.package.empty()) {
            return ResourceName{ localPackage.toString(), name.type, name.entry };
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

                for (auto& configValue : entry->values) {
                    StyleAndReferenceLinkerVisitor visitor(context,
                                                           context->getExternalSymbols(),
                                                           &table->stringPool, &declStack);
                    configValue.value->accept(&visitor);
                    if (visitor.hasError()) {
                        error = true;
                    }
                }
            }
        }
    }
    return !error;
}

} // namespace aapt
