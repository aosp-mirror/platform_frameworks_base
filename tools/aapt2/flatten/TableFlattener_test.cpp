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

#include "flatten/TableFlattener.h"
#include "unflatten/BinaryResourceParser.h"
#include "util/Util.h"

#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

using namespace android;

namespace aapt {

class TableFlattenerTest : public ::testing::Test {
public:
    void SetUp() override {
        mContext = test::ContextBuilder()
                .setCompilationPackage(u"com.app.test")
                .setPackageId(0x7f)
                .build();
    }

    ::testing::AssertionResult flatten(ResourceTable* table, ResTable* outTable) {
        BigBuffer buffer(1024);
        TableFlattenerOptions options = {};
        options.useExtendedChunks = true;
        TableFlattener flattener(&buffer, options);
        if (!flattener.consume(mContext.get(), table)) {
            return ::testing::AssertionFailure() << "failed to flatten ResourceTable";
        }

        std::unique_ptr<uint8_t[]> data = util::copy(buffer);
        if (outTable->add(data.get(), buffer.size(), -1, true) != NO_ERROR) {
            return ::testing::AssertionFailure() << "flattened ResTable is corrupt";
        }
        return ::testing::AssertionSuccess();
    }

    ::testing::AssertionResult flatten(ResourceTable* table, ResourceTable* outTable) {
        BigBuffer buffer(1024);
        TableFlattenerOptions options = {};
        options.useExtendedChunks = true;
        TableFlattener flattener(&buffer, options);
        if (!flattener.consume(mContext.get(), table)) {
            return ::testing::AssertionFailure() << "failed to flatten ResourceTable";
        }

        std::unique_ptr<uint8_t[]> data = util::copy(buffer);
        BinaryResourceParser parser(mContext.get(), outTable, {}, data.get(), buffer.size());
        if (!parser.parse()) {
            return ::testing::AssertionFailure() << "flattened ResTable is corrupt";
        }
        return ::testing::AssertionSuccess();
    }

    ::testing::AssertionResult exists(ResTable* table,
                                      const StringPiece16& expectedName,
                                      const ResourceId expectedId,
                                      const ConfigDescription& expectedConfig,
                                      const uint8_t expectedDataType, const uint32_t expectedData,
                                      const uint32_t expectedSpecFlags) {
        const ResourceName expectedResName = test::parseNameOrDie(expectedName);

        table->setParameters(&expectedConfig);

        ResTable_config config;
        Res_value val;
        uint32_t specFlags;
        if (table->getResource(expectedId.id, &val, false, 0, &specFlags, &config) < 0) {
            return ::testing::AssertionFailure() << "could not find resource with";
        }

        if (expectedDataType != val.dataType) {
            return ::testing::AssertionFailure()
                    << "expected data type "
                    << std::hex << (int) expectedDataType << " but got data type "
                    << (int) val.dataType << std::dec << " instead";
        }

        if (expectedData != val.data) {
            return ::testing::AssertionFailure()
                    << "expected data "
                    << std::hex << expectedData << " but got data "
                    << val.data << std::dec << " instead";
        }

        if (expectedSpecFlags != specFlags) {
            return ::testing::AssertionFailure()
                    << "expected specFlags "
                    << std::hex << expectedSpecFlags << " but got specFlags "
                    << specFlags << std::dec << " instead";
        }

        ResTable::resource_name actualName;
        if (!table->getResourceName(expectedId.id, false, &actualName)) {
            return ::testing::AssertionFailure() << "failed to find resource name";
        }

        StringPiece16 package16(actualName.package, actualName.packageLen);
        if (package16 != expectedResName.package) {
            return ::testing::AssertionFailure()
                    << "expected package '" << expectedResName.package << "' but got '"
                    << package16 << "'";
        }

        StringPiece16 type16(actualName.type, actualName.typeLen);
        if (type16 != toString(expectedResName.type)) {
            return ::testing::AssertionFailure()
                    << "expected type '" << expectedResName.type
                    << "' but got '" << type16 << "'";
        }

        StringPiece16 name16(actualName.name, actualName.nameLen);
        if (name16 != expectedResName.entry) {
            return ::testing::AssertionFailure()
                    << "expected name '" << expectedResName.entry
                    << "' but got '" << name16 << "'";
        }

        if (expectedConfig != config) {
            return ::testing::AssertionFailure()
                    << "expected config '" << expectedConfig << "' but got '"
                    << ConfigDescription(config) << "'";
        }
        return ::testing::AssertionSuccess();
    }

private:
    std::unique_ptr<IAaptContext> mContext;
};

TEST_F(TableFlattenerTest, FlattenFullyLinkedTable) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"com.app.test", 0x7f)
            .addSimple(u"@com.app.test:id/one", ResourceId(0x7f020000))
            .addSimple(u"@com.app.test:id/two", ResourceId(0x7f020001))
            .addValue(u"@com.app.test:id/three", ResourceId(0x7f020002),
                      test::buildReference(u"@com.app.test:id/one", ResourceId(0x7f020000)))
            .addValue(u"@com.app.test:integer/one", ResourceId(0x7f030000),
                      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 1u))
            .addValue(u"@com.app.test:integer/one", ResourceId(0x7f030000),
                      test::parseConfigOrDie("v1"),
                      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 2u))
            .addString(u"@com.app.test:string/test", ResourceId(0x7f040000), u"foo")
            .addString(u"@com.app.test:layout/bar", ResourceId(0x7f050000), u"res/layout/bar.xml")
            .build();

    ResTable resTable;
    ASSERT_TRUE(flatten(table.get(), &resTable));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:id/one", ResourceId(0x7f020000), {},
                       Res_value::TYPE_INT_BOOLEAN, 0u, 0u));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:id/two", ResourceId(0x7f020001), {},
                       Res_value::TYPE_INT_BOOLEAN, 0u, 0u));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:id/three", ResourceId(0x7f020002), {},
                       Res_value::TYPE_REFERENCE, 0x7f020000u, 0u));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:integer/one", ResourceId(0x7f030000),
                       {}, Res_value::TYPE_INT_DEC, 1u,
                       ResTable_config::CONFIG_VERSION));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:integer/one", ResourceId(0x7f030000),
                       test::parseConfigOrDie("v1"), Res_value::TYPE_INT_DEC, 2u,
                       ResTable_config::CONFIG_VERSION));

    StringPiece16 fooStr = u"foo";
    ssize_t idx = resTable.getTableStringBlock(0)->indexOfString(fooStr.data(), fooStr.size());
    ASSERT_GE(idx, 0);
    EXPECT_TRUE(exists(&resTable, u"@com.app.test:string/test", ResourceId(0x7f040000),
                       {}, Res_value::TYPE_STRING, (uint32_t) idx, 0u));

    StringPiece16 barPath = u"res/layout/bar.xml";
    idx = resTable.getTableStringBlock(0)->indexOfString(barPath.data(), barPath.size());
    ASSERT_GE(idx, 0);
    EXPECT_TRUE(exists(&resTable, u"@com.app.test:layout/bar", ResourceId(0x7f050000), {},
                       Res_value::TYPE_STRING, (uint32_t) idx, 0u));
}

TEST_F(TableFlattenerTest, FlattenEntriesWithGapsInIds) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"com.app.test", 0x7f)
            .addSimple(u"@com.app.test:id/one", ResourceId(0x7f020001))
            .addSimple(u"@com.app.test:id/three", ResourceId(0x7f020003))
            .build();

    ResTable resTable;
    ASSERT_TRUE(flatten(table.get(), &resTable));

    EXPECT_TRUE(exists(&resTable, u"@com.app.test:id/one", ResourceId(0x7f020001), {},
                       Res_value::TYPE_INT_BOOLEAN, 0u, 0u));
    EXPECT_TRUE(exists(&resTable, u"@com.app.test:id/three", ResourceId(0x7f020003), {},
                           Res_value::TYPE_INT_BOOLEAN, 0u, 0u));
}

TEST_F(TableFlattenerTest, FlattenUnlinkedTable) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"com.app.test", 0x7f)
            .addValue(u"@com.app.test:integer/one", ResourceId(0x7f020000),
                      test::buildReference(u"@android:integer/foo"))
            .addValue(u"@com.app.test:style/Theme", ResourceId(0x7f030000), test::StyleBuilder()
                    .setParent(u"@android:style/Theme.Material")
                    .addItem(u"@android:attr/background", {})
                    .addItem(u"@android:attr/colorAccent",
                             test::buildReference(u"@com.app.test:color/green"))
                    .build())
            .build();

    {
        // Need access to stringPool to make RawString.
        Style* style = test::getValue<Style>(table.get(), u"@com.app.test:style/Theme");
        style->entries[0].value = util::make_unique<RawString>(table->stringPool.makeRef(u"foo"));
    }

    ResourceTable finalTable;
    ASSERT_TRUE(flatten(table.get(), &finalTable));

    Reference* ref = test::getValue<Reference>(&finalTable, u"@com.app.test:integer/one");
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->name);
    EXPECT_EQ(ref->name.value(), test::parseNameOrDie(u"@android:integer/foo"));

    Style* style = test::getValue<Style>(&finalTable, u"@com.app.test:style/Theme");
    ASSERT_NE(style, nullptr);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().name);
    EXPECT_EQ(style->parent.value().name.value(),
              test::parseNameOrDie(u"@android:style/Theme.Material"));

    ASSERT_EQ(2u, style->entries.size());

    AAPT_ASSERT_TRUE(style->entries[0].key.name);
    EXPECT_EQ(style->entries[0].key.name.value(),
              test::parseNameOrDie(u"@android:attr/background"));
    RawString* raw = valueCast<RawString>(style->entries[0].value.get());
    ASSERT_NE(raw, nullptr);
    EXPECT_EQ(*raw->value, u"foo");

    AAPT_ASSERT_TRUE(style->entries[1].key.name);
    EXPECT_EQ(style->entries[1].key.name.value(),
              test::parseNameOrDie(u"@android:attr/colorAccent"));
    ref = valueCast<Reference>(style->entries[1].value.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->name);
    EXPECT_EQ(ref->name.value(), test::parseNameOrDie(u"@com.app.test:color/green"));
}

} // namespace aapt
