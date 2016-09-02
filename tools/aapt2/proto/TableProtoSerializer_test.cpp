/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "ResourceTable.h"
#include "proto/ProtoSerialize.h"
#include "test/Test.h"

using namespace google::protobuf::io;

namespace aapt {

TEST(TableProtoSerializer, SerializeSinglePackage) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId("com.app.a", 0x7f)
            .addFileReference("com.app.a:layout/main", ResourceId(0x7f020000),
                              "res/layout/main.xml")
            .addReference("com.app.a:layout/other", ResourceId(0x7f020001),
                          "com.app.a:layout/main")
            .addString("com.app.a:string/text", {}, "hi")
            .addValue("com.app.a:id/foo", {}, util::make_unique<Id>())
            .build();

    Symbol publicSymbol;
    publicSymbol.state = SymbolState::kPublic;
    ASSERT_TRUE(table->setSymbolState(test::parseNameOrDie("com.app.a:layout/main"),
                                      ResourceId(0x7f020000),
                                      publicSymbol, context->getDiagnostics()));

    Id* id = test::getValue<Id>(table.get(), "com.app.a:id/foo");
    ASSERT_NE(nullptr, id);

    // Make a plural.
    std::unique_ptr<Plural> plural = util::make_unique<Plural>();
    plural->values[Plural::One] = util::make_unique<String>(table->stringPool.makeRef("one"));
    ASSERT_TRUE(table->addResource(test::parseNameOrDie("com.app.a:plurals/hey"),
                                   ConfigDescription{}, {}, std::move(plural),
                                   context->getDiagnostics()));

    // Make a resource with different products.
    ASSERT_TRUE(table->addResource(test::parseNameOrDie("com.app.a:integer/one"),
                                   test::parseConfigOrDie("land"), {},
                                   test::buildPrimitive(android::Res_value::TYPE_INT_DEC, 123u),
                                   context->getDiagnostics()));
    ASSERT_TRUE(table->addResource(test::parseNameOrDie("com.app.a:integer/one"),
                                       test::parseConfigOrDie("land"), "tablet",
                                       test::buildPrimitive(android::Res_value::TYPE_INT_DEC, 321u),
                                       context->getDiagnostics()));

    // Make a reference with both resource name and resource ID.
    // The reference should point to a resource outside of this table to test that both
    // name and id get serialized.
    Reference expectedRef;
    expectedRef.name = test::parseNameOrDie("android:layout/main");
    expectedRef.id = ResourceId(0x01020000);
    ASSERT_TRUE(table->addResource(test::parseNameOrDie("com.app.a:layout/abc"),
                                   ConfigDescription::defaultConfig(), {},
                                   util::make_unique<Reference>(expectedRef),
                                   context->getDiagnostics()));

    std::unique_ptr<pb::ResourceTable> pbTable = serializeTableToPb(table.get());
    ASSERT_NE(nullptr, pbTable);

    std::unique_ptr<ResourceTable> newTable = deserializeTableFromPb(*pbTable,
                                                                     Source{ "test" },
                                                                     context->getDiagnostics());
    ASSERT_NE(nullptr, newTable);

    Id* newId = test::getValue<Id>(newTable.get(), "com.app.a:id/foo");
    ASSERT_NE(nullptr, newId);
    EXPECT_EQ(id->isWeak(), newId->isWeak());

    Maybe<ResourceTable::SearchResult> result = newTable->findResource(
            test::parseNameOrDie("com.app.a:layout/main"));
    AAPT_ASSERT_TRUE(result);
    EXPECT_EQ(SymbolState::kPublic, result.value().type->symbolStatus.state);
    EXPECT_EQ(SymbolState::kPublic, result.value().entry->symbolStatus.state);

    // Find the product-dependent values
    BinaryPrimitive* prim = test::getValueForConfigAndProduct<BinaryPrimitive>(
            newTable.get(), "com.app.a:integer/one", test::parseConfigOrDie("land"), "");
    ASSERT_NE(nullptr, prim);
    EXPECT_EQ(123u, prim->value.data);

    prim = test::getValueForConfigAndProduct<BinaryPrimitive>(
            newTable.get(), "com.app.a:integer/one", test::parseConfigOrDie("land"), "tablet");
    ASSERT_NE(nullptr, prim);
    EXPECT_EQ(321u, prim->value.data);

    Reference* actualRef = test::getValue<Reference>(newTable.get(), "com.app.a:layout/abc");
    ASSERT_NE(nullptr, actualRef);
    AAPT_ASSERT_TRUE(actualRef->name);
    AAPT_ASSERT_TRUE(actualRef->id);
    EXPECT_EQ(expectedRef.name.value(), actualRef->name.value());
    EXPECT_EQ(expectedRef.id.value(), actualRef->id.value());
}

TEST(TableProtoSerializer, SerializeFileHeader) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    ResourceFile f;
    f.config = test::parseConfigOrDie("hdpi-v9");
    f.name = test::parseNameOrDie("com.app.a:layout/main");
    f.source.path = "res/layout-hdpi-v9/main.xml";
    f.exportedSymbols.push_back(SourcedResourceName{ test::parseNameOrDie("id/unchecked"), 23u });

    const std::string expectedData1 = "123";
    const std::string expectedData2 = "1234";

    std::string outputStr;
    {
        std::unique_ptr<pb::CompiledFile> pbFile1 = serializeCompiledFileToPb(f);

        f.name.entry = "__" + f.name.entry + "$0";
        std::unique_ptr<pb::CompiledFile> pbFile2 = serializeCompiledFileToPb(f);

        StringOutputStream outStream(&outputStr);
        CompiledFileOutputStream outFileStream(&outStream);
        outFileStream.WriteLittleEndian32(2);
        outFileStream.WriteCompiledFile(pbFile1.get());
        outFileStream.WriteData(expectedData1.data(), expectedData1.size());
        outFileStream.WriteCompiledFile(pbFile2.get());
        outFileStream.WriteData(expectedData2.data(), expectedData2.size());
        ASSERT_FALSE(outFileStream.HadError());
    }

    CompiledFileInputStream inFileStream(outputStr.data(), outputStr.size());
    uint32_t numFiles = 0;
    ASSERT_TRUE(inFileStream.ReadLittleEndian32(&numFiles));
    ASSERT_EQ(2u, numFiles);

    // Read the first compiled file.

    pb::CompiledFile newPbFile;
    ASSERT_TRUE(inFileStream.ReadCompiledFile(&newPbFile));

    std::unique_ptr<ResourceFile> file = deserializeCompiledFileFromPb(newPbFile, Source("test"),
                                                                       context->getDiagnostics());
    ASSERT_NE(nullptr, file);

    uint64_t offset, len;
    ASSERT_TRUE(inFileStream.ReadDataMetaData(&offset, &len));

    std::string actualData(outputStr.data() + offset, len);
    EXPECT_EQ(expectedData1, actualData);

    // Expect the data to be aligned.
    EXPECT_EQ(0u, offset & 0x03);

    ASSERT_EQ(1u, file->exportedSymbols.size());
    EXPECT_EQ(test::parseNameOrDie("id/unchecked"), file->exportedSymbols[0].name);

    // Read the second compiled file.

    ASSERT_TRUE(inFileStream.ReadCompiledFile(&newPbFile));

    file = deserializeCompiledFileFromPb(newPbFile, Source("test"), context->getDiagnostics());
    ASSERT_NE(nullptr, file);

    ASSERT_TRUE(inFileStream.ReadDataMetaData(&offset, &len));

    actualData = std::string(outputStr.data() + offset, len);
    EXPECT_EQ(expectedData2, actualData);

    // Expect the data to be aligned.
    EXPECT_EQ(0u, offset & 0x03);
}

TEST(TableProtoSerializer, DeserializeCorruptHeaderSafely) {
    ResourceFile f;
    std::unique_ptr<pb::CompiledFile> pbFile = serializeCompiledFileToPb(f);

    const std::string expectedData = "1234";

    std::string outputStr;
    {
        StringOutputStream outStream(&outputStr);
        CompiledFileOutputStream outFileStream(&outStream);
        outFileStream.WriteLittleEndian32(1);
        outFileStream.WriteCompiledFile(pbFile.get());
        outFileStream.WriteData(expectedData.data(), expectedData.size());
        ASSERT_FALSE(outFileStream.HadError());
    }

    outputStr[4] = 0xff;

    CompiledFileInputStream inFileStream(outputStr.data(), outputStr.size());

    uint32_t numFiles = 0;
    EXPECT_TRUE(inFileStream.ReadLittleEndian32(&numFiles));
    EXPECT_EQ(1u, numFiles);

    pb::CompiledFile newPbFile;
    EXPECT_FALSE(inFileStream.ReadCompiledFile(&newPbFile));

    uint64_t offset, len;
    EXPECT_FALSE(inFileStream.ReadDataMetaData(&offset, &len));
}

} // namespace aapt
