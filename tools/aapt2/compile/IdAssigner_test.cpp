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
#include "test/Test.h"

namespace aapt {

::testing::AssertionResult verifyIds(ResourceTable* table);

TEST(IdAssignerTest, AssignIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple("@android:attr/foo")
            .addSimple("@android:attr/bar")
            .addSimple("@android:id/foo")
            .setPackageId("android", 0x01)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_TRUE(assigner.consume(context.get(), table.get()));
    ASSERT_TRUE(verifyIds(table.get()));
}

TEST(IdAssignerTest, AssignIdsWithReservedIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple("@android:id/foo", ResourceId(0x01010000))
            .addSimple("@android:dimen/two")
            .addSimple("@android:integer/three")
            .addSimple("@android:string/five")
            .addSimple("@android:attr/fun", ResourceId(0x01040000))
            .addSimple("@android:attr/foo", ResourceId(0x01040006))
            .addSimple("@android:attr/bar")
            .addSimple("@android:attr/baz")
            .addSimple("@app:id/biz")
            .setPackageId("android", 0x01)
            .setPackageId("app", 0x7f)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_TRUE(assigner.consume(context.get(), table.get()));
    ASSERT_TRUE(verifyIds(table.get()));

    Maybe<ResourceTable::SearchResult> maybeResult;

    // Expect to fill in the gaps between 0x0101XXXX and 0x0104XXXX.

    maybeResult = table->findResource(test::parseNameOrDie("@android:dimen/two"));
    AAPT_ASSERT_TRUE(maybeResult);
    EXPECT_EQ(make_value<uint8_t>(2), maybeResult.value().type->id);

    maybeResult = table->findResource(test::parseNameOrDie("@android:integer/three"));
    AAPT_ASSERT_TRUE(maybeResult);
    EXPECT_EQ(make_value<uint8_t>(3), maybeResult.value().type->id);

    // Expect to bypass the reserved 0x0104XXXX IDs and use the next 0x0105XXXX IDs.

    maybeResult = table->findResource(test::parseNameOrDie("@android:string/five"));
    AAPT_ASSERT_TRUE(maybeResult);
    EXPECT_EQ(make_value<uint8_t>(5), maybeResult.value().type->id);

    // Expect to fill in the gaps between 0x01040000 and 0x01040006.

    maybeResult = table->findResource(test::parseNameOrDie("@android:attr/bar"));
    AAPT_ASSERT_TRUE(maybeResult);
    EXPECT_EQ(make_value<uint16_t>(1), maybeResult.value().entry->id);

    maybeResult = table->findResource(test::parseNameOrDie("@android:attr/baz"));
    AAPT_ASSERT_TRUE(maybeResult);
    EXPECT_EQ(make_value<uint16_t>(2), maybeResult.value().entry->id);
}

TEST(IdAssignerTest, FailWhenNonUniqueIdsAssigned) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple("@android:attr/foo", ResourceId(0x01040006))
            .addSimple("@android:attr/bar", ResourceId(0x01040006))
            .setPackageId("android", 0x01)
            .setPackageId("app", 0x7f)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    IdAssigner assigner;

    ASSERT_FALSE(assigner.consume(context.get(), table.get()));
}

TEST(IdAssignerTest, AssignIdsWithIdMap) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple("@android:attr/foo")
            .addSimple("@android:attr/bar")
            .setPackageId("android", 0x01)
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unordered_map<ResourceName, ResourceId> idMap = {
            { test::parseNameOrDie("@android:attr/foo"), ResourceId(0x01010002) } };
    IdAssigner assigner(&idMap);
    ASSERT_TRUE(assigner.consume(context.get(), table.get()));
    ASSERT_TRUE(verifyIds(table.get()));
    Maybe<ResourceTable::SearchResult> result = table->findResource(
            test::parseNameOrDie("@android:attr/foo"));
    AAPT_ASSERT_TRUE(result);

    const ResourceTable::SearchResult& searchResult = result.value();
    EXPECT_EQ(make_value<uint8_t>(0x01), searchResult.package->id);
    EXPECT_EQ(make_value<uint8_t>(0x01), searchResult.type->id);
    EXPECT_EQ(make_value<uint16_t>(0x0002), searchResult.entry->id);
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
