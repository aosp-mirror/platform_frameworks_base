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

#include "link/Linkers.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(PrivateAttributeMoverTest, MovePrivateAttributes) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:attr/publicA")
            .addSimple(u"@android:attr/privateA")
            .addSimple(u"@android:attr/publicB")
            .addSimple(u"@android:attr/privateB")
            .setSymbolState(u"@android:attr/publicA", ResourceId(0x01010000), SymbolState::kPublic)
            .setSymbolState(u"@android:attr/publicB", ResourceId(0x01010000), SymbolState::kPublic)
            .build();

    PrivateAttributeMover mover;
    ASSERT_TRUE(mover.consume(context.get(), table.get()));

    ResourceTablePackage* package = table->findPackage(u"android");
    ASSERT_NE(package, nullptr);

    ResourceTableType* type = package->findType(ResourceType::kAttr);
    ASSERT_NE(type, nullptr);
    ASSERT_EQ(type->entries.size(), 2u);
    EXPECT_NE(type->findEntry(u"publicA"), nullptr);
    EXPECT_NE(type->findEntry(u"publicB"), nullptr);

    type = package->findType(ResourceType::kAttrPrivate);
    ASSERT_NE(type, nullptr);
    ASSERT_EQ(type->entries.size(), 2u);
    EXPECT_NE(type->findEntry(u"privateA"), nullptr);
    EXPECT_NE(type->findEntry(u"privateB"), nullptr);
}

TEST(PrivateAttributeMoverTest, LeavePrivateAttributesWhenNoPublicAttributesDefined) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .addSimple(u"@android:attr/privateA")
            .addSimple(u"@android:attr/privateB")
            .build();

    PrivateAttributeMover mover;
    ASSERT_TRUE(mover.consume(context.get(), table.get()));

    ResourceTablePackage* package = table->findPackage(u"android");
    ASSERT_NE(package, nullptr);

    ResourceTableType* type = package->findType(ResourceType::kAttr);
    ASSERT_NE(type, nullptr);
    ASSERT_EQ(type->entries.size(), 2u);

    type = package->findType(ResourceType::kAttrPrivate);
    ASSERT_EQ(type, nullptr);
}

} // namespace aapt
