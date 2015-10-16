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

#ifndef AAPT_LINKER_REFERENCELINKERVISITOR_H
#define AAPT_LINKER_REFERENCELINKERVISITOR_H

#include "Resource.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"

#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"

#include <cassert>

namespace aapt {

/**
 * The ReferenceLinkerVisitor will follow all references and make sure they point
 * to resources that actually exist in the given ISymbolTable.
 * Once the target resource has been found, the ID of the resource will be assigned
 * to the reference object.
 */
class ReferenceLinkerVisitor : public ValueVisitor {
    using ValueVisitor::visit;
private:
    IAaptContext* mContext;
    ISymbolTable* mSymbols;
    IPackageDeclStack* mPackageDecls;
    bool mError = false;

public:
    ReferenceLinkerVisitor(IAaptContext* context, ISymbolTable* symbols, IPackageDeclStack* decls) :
            mContext(context), mSymbols(symbols), mPackageDecls(decls) {
    }

    /**
     * Lookup a reference and ensure it exists, either in our local table, or as an external
     * symbol. Once found, assign the ID of the target resource to this reference object.
     */
    void visit(Reference* reference) override {
        assert(reference);
        assert(reference->name || reference->id);

        // We prefer to lookup by name if the name is set. Otherwise it could be
        // an out-of-date ID.
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

            if (s) {
                reference->id = s->id;
                return;
            }

            DiagMessage errorMsg;
            errorMsg << "reference to " << reference->name.value();
            if (realName) {
                errorMsg << " (aka " << realName.value() << ")";
            }
            errorMsg << " was not found";
            mContext->getDiagnostics()->error(errorMsg);
            mError = true;
            return;
        }

        if (!mSymbols->findById(reference->id.value())) {
            mContext->getDiagnostics()->error(DiagMessage()
                                              << "reference to " << reference->id.value()
                                              << " was not found");
            mError = true;
        }
    }

    inline bool hasError() {
        return mError;
    }
};

} // namespace aapt

#endif /* AAPT_LINKER_REFERENCELINKERVISITOR_H */
