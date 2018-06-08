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

#include "test/Test.h"

namespace aapt {

TEST(PrivateAttributeMoverTest, MovePrivateAttributes) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:attr/publicA")
          .AddSimple("android:attr/privateA")
          .AddSimple("android:attr/publicB")
          .AddSimple("android:attr/privateB")
          .SetSymbolState("android:attr/publicA", ResourceId(0x01010000),
                          Visibility::Level::kPublic)
          .SetSymbolState("android:attr/publicB", ResourceId(0x01010000),
                          Visibility::Level::kPublic)
          .Build();

  PrivateAttributeMover mover;
  ASSERT_TRUE(mover.Consume(context.get(), table.get()));

  ResourceTablePackage* package = table->FindPackage("android");
  ASSERT_NE(package, nullptr);

  ResourceTableType* type = package->FindType(ResourceType::kAttr);
  ASSERT_NE(type, nullptr);
  ASSERT_EQ(type->entries.size(), 2u);
  EXPECT_NE(type->FindEntry("publicA"), nullptr);
  EXPECT_NE(type->FindEntry("publicB"), nullptr);

  type = package->FindType(ResourceType::kAttrPrivate);
  ASSERT_NE(type, nullptr);
  ASSERT_EQ(type->entries.size(), 2u);
  EXPECT_NE(type->FindEntry("privateA"), nullptr);
  EXPECT_NE(type->FindEntry("privateB"), nullptr);
}

TEST(PrivateAttributeMoverTest, LeavePrivateAttributesWhenNoPublicAttributesDefined) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
                                             .AddSimple("android:attr/privateA")
                                             .AddSimple("android:attr/privateB")
                                             .Build();

  PrivateAttributeMover mover;
  ASSERT_TRUE(mover.Consume(context.get(), table.get()));

  ResourceTablePackage* package = table->FindPackage("android");
  ASSERT_NE(package, nullptr);

  ResourceTableType* type = package->FindType(ResourceType::kAttr);
  ASSERT_NE(type, nullptr);
  ASSERT_EQ(type->entries.size(), 2u);

  type = package->FindType(ResourceType::kAttrPrivate);
  ASSERT_EQ(type, nullptr);
}

TEST(PrivateAttributeMoverTest, DoNotCreatePrivateAttrsIfNoneExist) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("android:attr/pub")
          .SetSymbolState("android:attr/pub", ResourceId(0x01010000), Visibility::Level::kPublic)
          .Build();

  ResourceTablePackage* package = table->FindPackage("android");
  ASSERT_NE(nullptr, package);

  ASSERT_EQ(nullptr, package->FindType(ResourceType::kAttrPrivate));

  PrivateAttributeMover mover;
  ASSERT_TRUE(mover.Consume(context.get(), table.get()));

  ASSERT_EQ(nullptr, package->FindType(ResourceType::kAttrPrivate));
}

}  // namespace aapt
