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

#ifndef AAPT_TEST_CONTEXT_H
#define AAPT_TEST_CONTEXT_H

#include "NameMangler.h"
#include "util/Util.h"

#include "process/IResourceTableConsumer.h"
#include "process/SymbolTable.h"
#include "test/Common.h"

#include <cassert>
#include <list>

namespace aapt {
namespace test {

class Context : public IAaptContext {
private:
    friend class ContextBuilder;

    Context() = default;

    Maybe<std::u16string> mCompilationPackage;
    Maybe<uint8_t> mPackageId;
    std::unique_ptr<IDiagnostics> mDiagnostics = util::make_unique<StdErrDiagnostics>();
    std::unique_ptr<ISymbolTable> mSymbols;
    std::unique_ptr<NameMangler> mNameMangler;

public:
    ISymbolTable* getExternalSymbols() override {
        assert(mSymbols && "test symbols not set");
        return mSymbols.get();
    }

    void setSymbolTable(std::unique_ptr<ISymbolTable> symbols) {
        mSymbols = std::move(symbols);
    }

    IDiagnostics* getDiagnostics() override {
        assert(mDiagnostics && "test diagnostics not set");
        return mDiagnostics.get();
    }

    StringPiece16 getCompilationPackage() override {
        assert(mCompilationPackage && "package name not set");
        return mCompilationPackage.value();
    }

    uint8_t getPackageId() override {
        assert(mPackageId && "package ID not set");
        return mPackageId.value();
    }

    NameMangler* getNameMangler() override {
        assert(mNameMangler && "test name mangler not set");
        return mNameMangler.get();
    }
};

class ContextBuilder {
private:
    std::unique_ptr<Context> mContext = std::unique_ptr<Context>(new Context());

public:
    ContextBuilder& setCompilationPackage(const StringPiece16& package) {
        mContext->mCompilationPackage = package.toString();
        return *this;
    }

    ContextBuilder& setPackageId(uint8_t id) {
        mContext->mPackageId = id;
        return *this;
    }

    ContextBuilder& setSymbolTable(std::unique_ptr<ISymbolTable> symbols) {
        mContext->mSymbols = std::move(symbols);
        return *this;
    }

    ContextBuilder& setDiagnostics(std::unique_ptr<IDiagnostics> diag) {
        mContext->mDiagnostics = std::move(diag);
        return *this;
    }

    ContextBuilder& setNameManglerPolicy(NameManglerPolicy policy) {
        mContext->mNameMangler = util::make_unique<NameMangler>(policy);
        return *this;
    }

    std::unique_ptr<Context> build() {
        return std::move(mContext);
    }
};

class StaticSymbolTableBuilder {
private:
    struct SymbolTable : public ISymbolTable {
        std::list<std::unique_ptr<Symbol>> mSymbols;
        std::map<ResourceName, Symbol*> mNameMap;
        std::map<ResourceId, Symbol*> mIdMap;

        const Symbol* findByName(const ResourceName& name) override {
            auto iter = mNameMap.find(name);
            if (iter != mNameMap.end()) {
                return iter->second;
            }
            return nullptr;
        }

        const Symbol* findById(ResourceId id) override {
            auto iter = mIdMap.find(id);
            if (iter != mIdMap.end()) {
                return iter->second;
            }
            return nullptr;
        }
    };

    std::unique_ptr<SymbolTable> mSymbolTable = util::make_unique<SymbolTable>();

public:
    StaticSymbolTableBuilder& addSymbol(const StringPiece16& name, ResourceId id,
                                  std::unique_ptr<Attribute> attr = {}) {
        std::unique_ptr<ISymbolTable::Symbol> symbol = util::make_unique<ISymbolTable::Symbol>(
                id, std::move(attr));
        mSymbolTable->mNameMap[parseNameOrDie(name)] = symbol.get();
        mSymbolTable->mIdMap[id] = symbol.get();
        mSymbolTable->mSymbols.push_back(std::move(symbol));
        return *this;
    }

    std::unique_ptr<ISymbolTable> build() {
        return std::move(mSymbolTable);
    }
};

} // namespace test
} // namespace aapt

#endif /* AAPT_TEST_CONTEXT_H */
