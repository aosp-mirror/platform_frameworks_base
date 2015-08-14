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

#include "compile/IdAssigner.h"

#include "test/Context.h"
#include "test/Builders.h"

#include <gtest/gtest.h>

namespace aapt {

::testing::AssertionResult verifyIds(ResourceTable* table);

TEST(IdAssignerTest, AssignIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:attr/foo")
            .addSimple(u"@android:attr/bar")
            .addSimple(u"@android:id/foo")
            .setPackageId(u"android", 0x01)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_TRUE(assigner.consume(context.get(), table.get()));
    ASSERT_TRUE(verifyIds(table.get()));
}

TEST(IdAssignerTest, AssignIdsWithReservedIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:attr/foo", ResourceId(0x01040006))
            .addSimple(u"@android:attr/bar")
            .addSimple(u"@android:id/foo")
            .addSimple(u"@app:id/biz")
            .setPackageId(u"android", 0x01)
            .setPackageId(u"app", 0x7f)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_TRUE(assigner.consume(context.get(), table.get()));
    ASSERT_TRUE(verifyIds(table.get()));
}

TEST(IdAssignerTest, FailWhenNonUniqueIdsAssigned) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:attr/foo", ResourceId(0x01040006))
            .addSimple(u"@android:attr/bar", ResourceId(0x01040006))
            .setPackageId(u"android", 0x01)
            .setPackageId(u"app", 0x7f)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_FALSE(assigner.consume(context.get(), table.get()));
}

::testing::AssertionResult verifyIds(ResourceTable* table) {
    std::set<uint8_t> packageIds;
    for (auto& package : table->packages) {
        if (!package->id) {
            return ::testing::AssertionFailure() << "package " << package->name << " has no ID";
        }

        if (!packageIds.insert(package->id.value()).second) {
            return ::testing::AssertionFailure() << "package " << package->name
                    << " has non-unique ID " << std::hex << (int) package->id.value() << std::dec;
        }
    }

    for (auto& package : table->packages) {
        std::set<uint8_t> typeIds;
        for (auto& type : package->types) {
            if (!type->id) {
                return ::testing::AssertionFailure() << "type " << type->type << " of package "
                        << package->name << " has no ID";
            }

            if (!typeIds.insert(type->id.value()).second) {
                return ::testing::AssertionFailure() << "type " << type->type
                        << " of package " << package->name << " has non-unique ID "
                        << std::hex << (int) type->id.value() << std::dec;
            }
        }


        for (auto& type : package->types) {
            std::set<uint16_t> entryIds;
            for (auto& entry : type->entries) {
                if (!entry->id) {
                    return ::testing::AssertionFailure() << "entry " << entry->name << " of type "
                            << type->type << " of package " << package->name << " has no ID";
                }

                if (!entryIds.insert(entry->id.value()).second) {
                    return ::testing::AssertionFailure() << "entry " << entry->name
                            << " of type " << type->type << " of package " << package->name
                            << " has non-unique ID "
                            << std::hex << (int) entry->id.value() << std::dec;
                }
            }
        }
    }
    return ::testing::AssertionSuccess() << "all IDs are unique and assigned";
}

} // namespace aapt
