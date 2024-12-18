/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <memory>
#include <cstdint>
#include <cstddef>
#include <fuzzer/FuzzedDataProvider.h>
#include "androidfw/ResourceTypes.h"

static void populateDynamicRefTableWithFuzzedData(
    android::DynamicRefTable& table,
    FuzzedDataProvider& fuzzedDataProvider) {

    const size_t numMappings = fuzzedDataProvider.ConsumeIntegralInRange<size_t>(1, 5);
    for (size_t i = 0; i < numMappings; ++i) {
        const uint8_t packageId = fuzzedDataProvider.ConsumeIntegralInRange<uint8_t>(0x02, 0x7F);

        // Generate a package name
        std::string packageName;
        size_t packageNameLength = fuzzedDataProvider.ConsumeIntegralInRange<size_t>(1, 128);
        for (size_t j = 0; j < packageNameLength; ++j) {
            // Consume characters only in the ASCII range (0x20 to 0x7E) to ensure valid UTF-8
            char ch = fuzzedDataProvider.ConsumeIntegralInRange<char>(0x20, 0x7E);
            packageName.push_back(ch);
        }

        // Convert std::string to String16 for compatibility
        android::String16 androidPackageName(packageName.c_str(), packageName.length());

        // Add the mapping to the table
        table.addMapping(androidPackageName, packageId);
    }
}

extern "C" int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
    FuzzedDataProvider fuzzedDataProvider(data, size);

    auto dynamic_ref_table = std::make_shared<android::DynamicRefTable>();

    // Populate the DynamicRefTable with fuzzed data
    populateDynamicRefTableWithFuzzedData(*dynamic_ref_table, fuzzedDataProvider);
    std::vector<uint8_t> xmlData = fuzzedDataProvider.ConsumeRemainingBytes<uint8_t>();

    // Make sure the object here outlives the vector it's set to, otherwise it will try
    // accessing an already freed buffer and crash.
    auto tree = android::ResXMLTree(std::move(dynamic_ref_table));
    if (tree.setTo(xmlData.data(), xmlData.size()) != android::NO_ERROR) {
        return 0; // Exit early if unable to parse XML data
    }

    tree.restart();

    size_t len = 0;
    auto code = tree.next();
    if (code == android::ResXMLParser::START_TAG) {
        // Access element name
        auto name = tree.getElementName(&len);

        // Access attributes of the current element
        for (size_t i = 0; i < tree.getAttributeCount(); i++) {
            // Access attribute name
            auto attrName = tree.getAttributeName(i, &len);
        }
    } else if (code == android::ResXMLParser::TEXT) {
        const auto text = tree.getText(&len);
    }
    return 0; // Non-zero return values are reserved for future use.
}
