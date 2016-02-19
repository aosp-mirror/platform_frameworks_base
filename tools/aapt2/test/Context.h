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
public:
    SymbolTable* getExternalSymbols() override {
        return &mSymbols;
    }

    IDiagnostics* getDiagnostics() override {
        return &mDiagnostics;
    }

    const std::u16string& getCompilationPackage() override {
        assert(mCompilationPackage && "package name not set");
        return mCompilationPackage.value();
    }

    uint8_t getPackageId() override {
        assert(mPackageId && "package ID not set");
        return mPackageId.value();
    }

    NameMangler* getNameMangler() override {
        return &mNameMangler;
    }

    bool verbose() override {
        return false;
    }

private:
    friend class ContextBuilder;

    Context() : mNameMangler({}) {
    }

    Maybe<std::u16string> mCompilationPackage;
    Maybe<uint8_t> mPackageId;
    StdErrDiagnostics mDiagnostics;
    SymbolTable mSymbols;
    NameMangler mNameMangler;
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

    ContextBuilder& setNameManglerPolicy(NameManglerPolicy policy) {
        mContext->mNameMangler = NameMangler(policy);
        return *this;
    }

    ContextBuilder& addSymbolSource(std::unique_ptr<ISymbolSource> src) {
        mContext->getExternalSymbols()->appendSource(std::move(src));
        return *this;
    }

    std::unique_ptr<Context> build() {
        return std::move(mContext);
    }
};

class StaticSymbolSourceBuilder {
public:
    StaticSymbolSourceBuilder& addPublicSymbol(const StringPiece16& name, ResourceId id,
                                               std::unique_ptr<Attribute> attr = {}) {
        std::unique_ptr<SymbolTable::Symbol> symbol = util::make_unique<SymbolTable::Symbol>(
                id, std::move(attr), true);
        mSymbolSource->mNameMap[parseNameOrDie(name)] = symbol.get();
        mSymbolSource->mIdMap[id] = symbol.get();
        mSymbolSource->mSymbols.push_back(std::move(symbol));
        return *this;
    }

    StaticSymbolSourceBuilder& addSymbol(const StringPiece16& name, ResourceId id,
                                         std::unique_ptr<Attribute> attr = {}) {
        std::unique_ptr<SymbolTable::Symbol> symbol = util::make_unique<SymbolTable::Symbol>(
                id, std::move(attr), false);
        mSymbolSource->mNameMap[parseNameOrDie(name)] = symbol.get();
        mSymbolSource->mIdMap[id] = symbol.get();
        mSymbolSource->mSymbols.push_back(std::move(symbol));
        return *this;
    }

    std::unique_ptr<ISymbolSource> build() {
        return std::move(mSymbolSource);
    }

private:
    class StaticSymbolSource : public ISymbolSource {
    public:
        StaticSymbolSource() = default;

        std::unique_ptr<SymbolTable::Symbol> findByName(const ResourceName& name) override {
            auto iter = mNameMap.find(name);
            if (iter != mNameMap.end()) {
                return cloneSymbol(iter->second);
            }
            return nullptr;
        }

        std::unique_ptr<SymbolTable::Symbol> findById(ResourceId id) override {
            auto iter = mIdMap.find(id);
            if (iter != mIdMap.end()) {
                return cloneSymbol(iter->second);
            }
            return nullptr;
        }

        std::list<std::unique_ptr<SymbolTable::Symbol>> mSymbols;
        std::map<ResourceName, SymbolTable::Symbol*> mNameMap;
        std::map<ResourceId, SymbolTable::Symbol*> mIdMap;

    private:
        std::unique_ptr<SymbolTable::Symbol> cloneSymbol(SymbolTable::Symbol* sym) {
            std::unique_ptr<SymbolTable::Symbol> clone = util::make_unique<SymbolTable::Symbol>();
            clone->id = sym->id;
            if (sym->attribute) {
                clone->attribute = std::unique_ptr<Attribute>(sym->attribute->clone(nullptr));
            }
            clone->isPublic = sym->isPublic;
            return clone;
        }

        DISALLOW_COPY_AND_ASSIGN(StaticSymbolSource);
    };

    std::unique_ptr<StaticSymbolSource> mSymbolSource = util::make_unique<StaticSymbolSource>();
};

} // namespace test
} // namespace aapt

#endif /* AAPT_TEST_CONTEXT_H */
