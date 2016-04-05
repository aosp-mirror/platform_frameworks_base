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

#ifndef AAPT_TEST_COMMON_H
#define AAPT_TEST_COMMON_H

#include "ConfigDescription.h"
#include "Debug.h"
#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "io/File.h"
#include "process/IResourceTableConsumer.h"
#include "util/StringPiece.h"

#include <gtest/gtest.h>
#include <iostream>

//
// GTEST 1.7 doesn't explicitly cast to bool, which causes explicit operators to fail to compile.
//
#define AAPT_ASSERT_TRUE(v) ASSERT_TRUE(bool(v))
#define AAPT_ASSERT_FALSE(v) ASSERT_FALSE(bool(v))
#define AAPT_EXPECT_TRUE(v) EXPECT_TRUE(bool(v))
#define AAPT_EXPECT_FALSE(v) EXPECT_FALSE(bool(v))

namespace aapt {
namespace test {

struct DummyDiagnosticsImpl : public IDiagnostics {
    void log(Level level, DiagMessageActual& actualMsg) override {
        switch (level) {
        case Level::Note:
            return;

        case Level::Warn:
            std::cerr << actualMsg.source << ": warn: " << actualMsg.message << "." << std::endl;
            break;

        case Level::Error:
            std::cerr << actualMsg.source << ": error: " << actualMsg.message << "." << std::endl;
            break;
        }
    }
};

inline IDiagnostics* getDiagnostics() {
    static DummyDiagnosticsImpl diag;
    return &diag;
}

inline ResourceName parseNameOrDie(const StringPiece16& str) {
    ResourceNameRef ref;
    bool result = ResourceUtils::tryParseReference(str, &ref);
    assert(result && "invalid resource name");
    return ref.toResourceName();
}

inline ConfigDescription parseConfigOrDie(const StringPiece& str) {
    ConfigDescription config;
    bool result = ConfigDescription::parse(str, &config);
    assert(result && "invalid configuration");
    return config;
}

template <typename T> T* getValueForConfigAndProduct(ResourceTable* table,
                                                     const StringPiece16& resName,
                                                     const ConfigDescription& config,
                                                     const StringPiece& product) {
    Maybe<ResourceTable::SearchResult> result = table->findResource(parseNameOrDie(resName));
    if (result) {
        ResourceConfigValue* configValue = result.value().entry->findValue(config, product);
        if (configValue) {
            return valueCast<T>(configValue->value.get());
        }
    }
    return nullptr;
}

template <typename T> T* getValueForConfig(ResourceTable* table, const StringPiece16& resName,
                                           const ConfigDescription& config) {
    return getValueForConfigAndProduct<T>(table, resName, config, {});
}

template <typename T> T* getValue(ResourceTable* table, const StringPiece16& resName) {
    return getValueForConfig<T>(table, resName, {});
}

class TestFile : public io::IFile {
private:
    Source mSource;

public:
    TestFile(const StringPiece& path) : mSource(path) {}

    std::unique_ptr<io::IData> openAsData() override {
        return {};
    }

    const Source& getSource() const override {
        return mSource;
    }
};

} // namespace test
} // namespace aapt

#endif /* AAPT_TEST_COMMON_H */
