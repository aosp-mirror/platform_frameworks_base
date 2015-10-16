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

#include "JavaClassGenerator.h"
#include "util/Util.h"

#include "test/Builders.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

TEST(JavaClassGeneratorTest, FailWhenEntryIsJavaKeyword) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/class", ResourceId(0x01020000))
            .build();

    JavaClassGenerator generator(table.get(), {});

    std::stringstream out;
    EXPECT_FALSE(generator.generate(u"android", &out));
}

TEST(JavaClassGeneratorTest, TransformInvalidJavaIdentifierCharacter) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"android", 0x01)
            .addSimple(u"@android:id/hey-man", ResourceId(0x01020000))
            .addSimple(u"@android:attr/cool.attr", ResourceId(0x01010000))
            .addValue(u"@android:styleable/hey.dude", ResourceId(0x01030000),
                      test::StyleableBuilder()
                              .addItem(u"@android:attr/cool.attr", ResourceId(0x01010000))
                              .build())
            .build();

    JavaClassGenerator generator(table.get(), {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(u"android", &out));

    std::string output = out.str();

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_man = 0x01020000;"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int[] hey_dude = {"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_dude_cool_attr = 0;"));
}

/*
 * TODO(adamlesinski): Re-enable this once we get merging working again.
 * TEST(JavaClassGeneratorTest, EmitPackageMangledSymbols) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"foo" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));
    ResourceTable table;
    table.setPackage(u"com.lib");
    ASSERT_TRUE(table.addResource(ResourceName{ {}, ResourceType::kId, u"test" }, {},
                                  Source{ "lib.xml", 33 }, util::make_unique<Id>()));
    ASSERT_TRUE(mTable->merge(std::move(table)));

    Linker linker(mTable,
                  std::make_shared<MockResolver>(mTable, std::map<ResourceName, ResourceId>()),
                  {});
    ASSERT_TRUE(linker.linkAndValidate());

    JavaClassGenerator generator(mTable, {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(mTable->getPackage(), out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("int foo ="));
    EXPECT_EQ(std::string::npos, output.find("int test ="));

    out.str("");
    EXPECT_TRUE(generator.generate(u"com.lib", out));
    output = out.str();
    EXPECT_NE(std::string::npos, output.find("int test ="));
    EXPECT_EQ(std::string::npos, output.find("int foo ="));
}*/

TEST(JavaClassGeneratorTest, EmitOtherPackagesAttributesInStyleable) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
                .setPackageId(u"android", 0x01)
                .setPackageId(u"com.lib", 0x02)
                .addSimple(u"@android:attr/bar", ResourceId(0x01010000))
                .addSimple(u"@com.lib:attr/bar", ResourceId(0x02010000))
                .addValue(u"@android:styleable/foo", ResourceId(0x01030000),
                          test::StyleableBuilder()
                                  .addItem(u"@android:attr/bar", ResourceId(0x01010000))
                                  .addItem(u"@com.lib:attr/bar", ResourceId(0x02010000))
                                  .build())
                .build();

    JavaClassGenerator generator(table.get(), {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(u"android", &out));

    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("int foo_bar ="));
    EXPECT_NE(std::string::npos, output.find("int foo_com_lib_bar ="));
}

} // namespace aapt
