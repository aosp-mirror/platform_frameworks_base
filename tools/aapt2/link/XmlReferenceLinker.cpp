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
#include "link/Linkers.h"
#include "link/ReferenceLinker.h"
#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "util/Util.h"
#include "xml/XmlDom.h"

namespace aapt {

namespace {

/**
 * Visits all references (including parents of styles, references in styles, arrays, etc) and
 * links their symbolic name to their Resource ID, performing mangling and package aliasing
 * as needed.
 */
class ReferenceVisitor : public ValueVisitor {
public:
    using ValueVisitor::visit;

    ReferenceVisitor(IAaptContext* context, SymbolTable* symbols, xml::IPackageDeclStack* decls,
                     CallSite* callSite) :
             mContext(context), mSymbols(symbols), mDecls(decls), mCallSite(callSite),
             mError(false) {
    }

    void visit(Reference* ref) override {
        if (!ReferenceLinker::linkReference(ref, mContext, mSymbols, mDecls, mCallSite)) {
            mError = true;
        }
    }

    bool hasError() const {
        return mError;
    }

private:
    IAaptContext* mContext;
    SymbolTable* mSymbols;
    xml::IPackageDeclStack* mDecls;
    CallSite* mCallSite;
    bool mError;
};

/**
 * Visits each xml Element and compiles the attributes within.
 */
class XmlVisitor : public xml::PackageAwareVisitor {
public:
    using xml::PackageAwareVisitor::visit;

    XmlVisitor(IAaptContext* context, SymbolTable* symbols, const Source& source,
               std::set<int>* sdkLevelsFound, CallSite* callSite) :
            mContext(context), mSymbols(symbols), mSource(source), mSdkLevelsFound(sdkLevelsFound),
            mCallSite(callSite), mReferenceVisitor(context, symbols, this, callSite) {
    }

    void visit(xml::Element* el) override {
        const Source source = mSource.withLine(el->lineNumber);
        for (xml::Attribute& attr : el->attributes) {
            Maybe<xml::ExtractedPackage> maybePackage =
                    xml::extractPackageFromNamespace(attr.namespaceUri);
            if (maybePackage) {
                // There is a valid package name for this attribute. We will look this up.
                StringPiece16 package = maybePackage.value().package;
                if (package.empty()) {
                    // Empty package means the 'current' or 'local' package.
                    package = mContext->getCompilationPackage();
                }

                Reference attrRef(ResourceNameRef(package, ResourceType::kAttr, attr.name));
                attrRef.privateReference = maybePackage.value().privateNamespace;

                std::string errStr;
                attr.compiledAttribute = ReferenceLinker::compileXmlAttribute(
                        attrRef, mContext->getNameMangler(), mSymbols, mCallSite, &errStr);

                // Convert the string value into a compiled Value if this is a valid attribute.
                if (attr.compiledAttribute) {
                    if (attr.compiledAttribute.value().id) {
                        // Record all SDK levels from which the attributes were defined.
                        const size_t sdkLevel = findAttributeSdkLevel(
                                attr.compiledAttribute.value().id.value());
                        if (sdkLevel > 1) {
                            mSdkLevelsFound->insert(sdkLevel);
                        }
                    }

                    const Attribute* attribute = &attr.compiledAttribute.value().attribute;
                    attr.compiledValue = ResourceUtils::parseItemForAttribute(attr.value,
                                                                              attribute);
                    if (!attr.compiledValue &&
                            !(attribute->typeMask & android::ResTable_map::TYPE_STRING)) {
                        // We won't be able to encode this as a string.
                        mContext->getDiagnostics()->error(
                                DiagMessage(source) << "'" << attr.value << "' "
                                                    << "is incompatible with attribute "
                                                    << package << ":" << attr.name << " "
                                                    << *attribute);
                        mError = true;
                    }

                } else {
                    mContext->getDiagnostics()->error(DiagMessage(source)
                                                      << "attribute '" << package << ":"
                                                      << attr.name << "' " << errStr);
                    mError = true;

                }
            } else {
                // We still encode references.
                attr.compiledValue = ResourceUtils::tryParseReference(attr.value);
            }

            if (attr.compiledValue) {
                // With a compiledValue, we must resolve the reference and assign it an ID.
                attr.compiledValue->setSource(source);
                attr.compiledValue->accept(&mReferenceVisitor);
            }
        }

        // Call the super implementation.
        xml::PackageAwareVisitor::visit(el);
    }

    bool hasError() {
        return mError || mReferenceVisitor.hasError();
    }

private:
    IAaptContext* mContext;
    SymbolTable* mSymbols;
    Source mSource;
    std::set<int>* mSdkLevelsFound;
    CallSite* mCallSite;
    ReferenceVisitor mReferenceVisitor;
    bool mError = false;
};

} // namespace

bool XmlReferenceLinker::consume(IAaptContext* context, xml::XmlResource* resource) {
    mSdkLevelsFound.clear();
    CallSite callSite = { resource->file.name };
    XmlVisitor visitor(context, context->getExternalSymbols(), resource->file.source,
                       &mSdkLevelsFound, &callSite);
    if (resource->root) {
        resource->root->accept(&visitor);
        return !visitor.hasError();
    }
    return false;
}

} // namespace aapt
