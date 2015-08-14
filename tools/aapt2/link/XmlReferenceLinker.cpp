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
#include "ResourceUtils.h"
#include "SdkConstants.h"
#include "XmlDom.h"

#include "link/Linkers.h"
#include "link/ReferenceLinkerVisitor.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "util/Util.h"

namespace aapt {

namespace {

class XmlReferenceLinkerVisitor : public xml::PackageAwareVisitor {
private:
    IAaptContext* mContext;
    ISymbolTable* mSymbols;
    std::set<int>* mSdkLevelsFound;
    ReferenceLinkerVisitor mReferenceLinkerVisitor;
    bool mError = false;

public:
    using xml::PackageAwareVisitor::visit;

    XmlReferenceLinkerVisitor(IAaptContext* context, ISymbolTable* symbols,
                              std::set<int>* sdkLevelsFound) :
            mContext(context), mSymbols(symbols), mSdkLevelsFound(sdkLevelsFound),
            mReferenceLinkerVisitor(context, symbols, this) {
    }

    void visit(xml::Element* el) override {
        for (xml::Attribute& attr : el->attributes) {
            Maybe<std::u16string> maybePackage =
                    util::extractPackageFromNamespace(attr.namespaceUri);
            if (maybePackage) {
                // There is a valid package name for this attribute. We will look this up.
                StringPiece16 package = maybePackage.value();
                if (package.empty()) {
                    // Empty package means the 'current' or 'local' package.
                    package = mContext->getCompilationPackage();
                }

                attr.compiledAttribute = compileAttribute(
                        ResourceName{ package.toString(), ResourceType::kAttr, attr.name });

                // Convert the string value into a compiled Value if this is a valid attribute.
                if (attr.compiledAttribute) {
                    // Record all SDK levels from which the attributes were defined.
                    const int sdkLevel = findAttributeSdkLevel(attr.compiledAttribute.value().id);
                    if (sdkLevel > 1) {
                        mSdkLevelsFound->insert(sdkLevel);
                    }

                    const Attribute* attribute = &attr.compiledAttribute.value().attribute;
                    attr.compiledValue = ResourceUtils::parseItemForAttribute(attr.value,
                                                                              attribute);
                    if (!attr.compiledValue &&
                            !(attribute->typeMask & android::ResTable_map::TYPE_STRING)) {
                        // We won't be able to encode this as a string.
                        mContext->getDiagnostics()->error(
                                DiagMessage() << "'" << attr.value << "' "
                                              << "is incompatible with attribute "
                                              << package << ":" << attr.name << " " << *attribute);
                        mError = true;
                    }
                } else {
                    mContext->getDiagnostics()->error(
                            DiagMessage() << "attribute '" << package << ":" << attr.name
                                          << "' was not found");
                    mError = true;

                }
            } else {
                // We still encode references.
                attr.compiledValue = ResourceUtils::tryParseReference(attr.value);
            }

            if (attr.compiledValue) {
                // With a compiledValue, we must resolve the reference and assign it an ID.
                attr.compiledValue->accept(&mReferenceLinkerVisitor);
            }
        }

        // Call the super implementation.
        xml::PackageAwareVisitor::visit(el);
    }

    Maybe<xml::AaptAttribute> compileAttribute(const ResourceName& name) {
        Maybe<ResourceName> mangledName = mContext->getNameMangler()->mangleName(name);
        if (const ISymbolTable::Symbol* symbol = mSymbols->findByName(
                mangledName ? mangledName.value() : name)) {
            if (symbol->attribute) {
                return xml::AaptAttribute{ symbol->id, *symbol->attribute };
            }
        }
        return {};
    }

    inline bool hasError() {
        return mError || mReferenceLinkerVisitor.hasError();
    }
};

} // namespace

bool XmlReferenceLinker::consume(IAaptContext* context, XmlResource* resource) {
    mSdkLevelsFound.clear();
    XmlReferenceLinkerVisitor visitor(context, context->getExternalSymbols(), &mSdkLevelsFound);
    if (resource->root) {
        resource->root->accept(&visitor);
        return !visitor.hasError();
    }
    return false;
}

} // namespace aapt
