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
#include "Linker.h"
#include "MockResolver.h"
#include "ResourceTable.h"
#include "ResourceTableResolver.h"
#include "ResourceValues.h"
#include "Util.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

struct JavaClassGeneratorTest : public ::testing::Test {
    virtual void SetUp() override {
        mTable = std::make_shared<ResourceTable>();
        mTable->setPackage(u"android");
        mTable->setPackageId(0x01);
    }

    bool addResource(const ResourceNameRef& name, ResourceId id) {
        return mTable->addResource(name, id, {}, SourceLine{ "test.xml", 21 },
                                   util::make_unique<Id>());
    }

    std::shared_ptr<ResourceTable> mTable;
};

TEST_F(JavaClassGeneratorTest, FailWhenEntryIsJavaKeyword) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"class" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));

    JavaClassGenerator generator(mTable, {});

    std::stringstream out;
    EXPECT_FALSE(generator.generate(mTable->getPackage(), out));
}

TEST_F(JavaClassGeneratorTest, TransformInvalidJavaIdentifierCharacter) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"hey-man" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));

    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kAttr, u"cool.attr" },
                            ResourceId{ 0x01, 0x01, 0x0000 }));

    std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
    Reference ref(ResourceName{ u"android", ResourceType::kAttr, u"cool.attr"});
    ref.id = ResourceId{ 0x01, 0x01, 0x0000 };
    styleable->entries.emplace_back(ref);

    ASSERT_TRUE(mTable->addResource(ResourceName{ {}, ResourceType::kStyleable, u"hey.dude" },
                                    ResourceId{ 0x01, 0x03, 0x0000 }, {},
                                    SourceLine{ "test.xml", 21 }, std::move(styleable)));

    JavaClassGenerator generator(mTable, {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(mTable->getPackage(), out));
    std::string output = out.str();

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_man = 0x01020000;"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int[] hey_dude = {"));

    EXPECT_NE(std::string::npos,
              output.find("public static final int hey_dude_cool_attr = 0;"));
}


TEST_F(JavaClassGeneratorTest, EmitPackageMangledSymbols) {
    ASSERT_TRUE(addResource(ResourceName{ {}, ResourceType::kId, u"foo" },
                            ResourceId{ 0x01, 0x02, 0x0000 }));
    ResourceTable table;
    table.setPackage(u"com.lib");
    ASSERT_TRUE(table.addResource(ResourceName{ {}, ResourceType::kId, u"test" }, {},
                                  SourceLine{ "lib.xml", 33 }, util::make_unique<Id>()));
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
}

TEST_F(JavaClassGeneratorTest, EmitOtherPackagesAttributesInStyleable) {
    std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
    styleable->entries.emplace_back(ResourceNameRef{ mTable->getPackage(),
                                                     ResourceType::kAttr,
                                                     u"bar" });
    styleable->entries.emplace_back(ResourceNameRef{ u"com.lib", ResourceType::kAttr, u"bar" });
    ASSERT_TRUE(mTable->addResource(ResourceName{ {}, ResourceType::kStyleable, u"Foo" }, {}, {},
                                    std::move(styleable)));

    std::shared_ptr<IResolver> resolver = std::make_shared<MockResolver>(mTable,
            std::map<ResourceName, ResourceId>({
                    { ResourceName{ u"android", ResourceType::kAttr, u"bar" },
                      ResourceId{ 0x01, 0x01, 0x0000 } },
                    { ResourceName{ u"com.lib", ResourceType::kAttr, u"bar" },
                      ResourceId{ 0x02, 0x01, 0x0000 } }}));

    Linker linker(mTable, resolver, {});
    ASSERT_TRUE(linker.linkAndValidate());

    JavaClassGenerator generator(mTable, {});

    std::stringstream out;
    EXPECT_TRUE(generator.generate(mTable->getPackage(), out));
    std::string output = out.str();
    EXPECT_NE(std::string::npos, output.find("int Foo_bar ="));
    EXPECT_NE(std::string::npos, output.find("int Foo_com_lib_bar ="));
}

} // namespace aapt
