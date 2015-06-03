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

#include "MockResolver.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Util.h"
#include "XmlFlattener.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>
#include <sstream>
#include <string>

using namespace android;

namespace aapt {
namespace xml {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

class XmlFlattenerTest : public ::testing::Test {
public:
    virtual void SetUp() override {
        mResolver = std::make_shared<MockResolver>(
                std::make_shared<ResourceTable>(),
                std::map<ResourceName, ResourceId>({
                        { ResourceName{ u"android", ResourceType::kAttr, u"attr" },
                          ResourceId{ 0x01010000u } },
                        { ResourceName{ u"android", ResourceType::kId, u"id" },
                          ResourceId{ 0x01020000u } },
                        { ResourceName{ u"com.lib", ResourceType::kAttr, u"attr" },
                          ResourceId{ 0x01010001u } },
                        { ResourceName{ u"com.lib", ResourceType::kId, u"id" },
                          ResourceId{ 0x01020001u } }}));
    }

    ::testing::AssertionResult testFlatten(const std::string& in, ResXMLTree* outTree) {
        std::stringstream input(kXmlPreamble);
        input << in << std::endl;

        SourceLogger logger(Source{ "test.xml" });
        std::unique_ptr<Node> root = inflate(&input, &logger);
        if (!root) {
            return ::testing::AssertionFailure();
        }

        BigBuffer outBuffer(1024);
        if (!flattenAndLink(Source{ "test.xml" }, root.get(), std::u16string(u"android"),
                    mResolver, {}, &outBuffer)) {
            return ::testing::AssertionFailure();
        }

        std::unique_ptr<uint8_t[]> data = util::copy(outBuffer);
        if (outTree->setTo(data.get(), outBuffer.size(), true) != NO_ERROR) {
            return ::testing::AssertionFailure();
        }
        return ::testing::AssertionSuccess();
    }

    std::shared_ptr<IResolver> mResolver;
};

TEST_F(XmlFlattenerTest, ParseSimpleView) {
    std::string input = R"EOF(
        <View xmlns:android="http://schemas.android.com/apk/res/android"
              android:attr="@id/id"
              class="str"
              style="@id/id">
        </View>
    )EOF";
    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::END_DOCUMENT);
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
    }

    const StringPiece16 androidNs = u"http://schemas.android.com/apk/res/android";
    const StringPiece16 attrName = u"attr";
    ssize_t idx = tree.indexOfAttribute(androidNs.data(), androidNs.size(), attrName.data(),
                                        attrName.size());
    ASSERT_GE(idx, 0);
    EXPECT_EQ(tree.getAttributeNameResID(idx), 0x01010000u);
    EXPECT_EQ(tree.getAttributeDataType(idx), android::Res_value::TYPE_REFERENCE);

    const StringPiece16 class16 = u"class";
    idx = tree.indexOfAttribute(nullptr, 0, class16.data(), class16.size());
    ASSERT_GE(idx, 0);
    EXPECT_EQ(tree.getAttributeNameResID(idx), 0u);
    EXPECT_EQ(tree.getAttributeDataType(idx), android::Res_value::TYPE_STRING);
    EXPECT_EQ(tree.getAttributeData(idx), tree.getAttributeValueStringID(idx));

    const StringPiece16 style16 = u"style";
    idx = tree.indexOfAttribute(nullptr, 0, style16.data(), style16.size());
    ASSERT_GE(idx, 0);
    EXPECT_EQ(tree.getAttributeNameResID(idx), 0u);
    EXPECT_EQ(tree.getAttributeDataType(idx), android::Res_value::TYPE_REFERENCE);
    EXPECT_EQ((uint32_t) tree.getAttributeData(idx), 0x01020000u);
    EXPECT_EQ(tree.getAttributeValueStringID(idx), -1);

    while (tree.next() != ResXMLTree::END_DOCUMENT) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
    }
}

TEST_F(XmlFlattenerTest, ParseViewWithPackageAlias) {
    std::string input = "<View xmlns:ns1=\"http://schemas.android.com/apk/res/android\"\n"
                        "      xmlns:ns2=\"http://schemas.android.com/apk/res/android\"\n"
                        "      ns1:attr=\"@ns2:id/id\">\n"
                        "</View>";
    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::END_DOCUMENT) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
    }
}

::testing::AssertionResult attributeNameAndValueEquals(ResXMLTree* tree, size_t index,
                                                       ResourceId nameId, ResourceId valueId) {
    if (index >= tree->getAttributeCount()) {
        return ::testing::AssertionFailure() << "index " << index << " is out of bounds ("
                                             << tree->getAttributeCount() << ")";
    }

    if (tree->getAttributeNameResID(index) != nameId.id) {
        return ::testing::AssertionFailure()
                << "attribute at index " << index << " has ID "
                << ResourceId{ (uint32_t) tree->getAttributeNameResID(index) }
                << ". Expected ID " << nameId;
    }

    if (tree->getAttributeDataType(index) != Res_value::TYPE_REFERENCE) {
        return ::testing::AssertionFailure() << "attribute at index " << index << " has value of "
                                             << "type " << std::hex
                                             << tree->getAttributeDataType(index) << std::dec
                                             << ". Expected reference (" << std::hex
                                             << Res_value::TYPE_REFERENCE << std::dec << ")";
    }

    if ((uint32_t) tree->getAttributeData(index) != valueId.id) {
        return ::testing::AssertionFailure()
                << "attribute at index " << index << " has value " << "with ID "
                << ResourceId{ (uint32_t) tree->getAttributeData(index) }
                << ". Expected ID " << valueId;
    }
    return ::testing::AssertionSuccess();
}

TEST_F(XmlFlattenerTest, ParseViewWithShadowedPackageAlias) {
    std::string input = "<View xmlns:app=\"http://schemas.android.com/apk/res/android\"\n"
                        "      app:attr=\"@app:id/id\">\n"
                        "  <View xmlns:app=\"http://schemas.android.com/apk/res/com.lib\"\n"
                        "        app:attr=\"@app:id/id\"/>\n"
                        "</View>";
    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), ResXMLTree::END_DOCUMENT);
    }

    ASSERT_TRUE(attributeNameAndValueEquals(&tree, 0u, ResourceId{ 0x01010000u },
                                            ResourceId{ 0x01020000u }));

    while (tree.next() != ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), ResXMLTree::END_DOCUMENT);
    }

    ASSERT_TRUE(attributeNameAndValueEquals(&tree, 0u, ResourceId{ 0x01010001u },
                                            ResourceId{ 0x01020001u }));
}

TEST_F(XmlFlattenerTest, ParseViewWithLocalPackageAndAliasOfTheSameName) {
    std::string input = "<View xmlns:android=\"http://schemas.android.com/apk/res/com.lib\"\n"
                        "      android:attr=\"@id/id\"/>";
    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), ResXMLTree::END_DOCUMENT);
    }

    // We expect the 'android:attr' to be converted to 'com.lib:attr' due to the namespace
    // assignment.
    // However, we didn't give '@id/id' a package, so it should use the default package
    // 'android', and not be converted from 'android' to 'com.lib'.
    ASSERT_TRUE(attributeNameAndValueEquals(&tree, 0u, ResourceId{ 0x01010001u },
                                            ResourceId{ 0x01020000u }));
}

/*
 * The device ResXMLParser in libandroidfw differentiates between empty namespace and null
 * namespace.
 */
TEST_F(XmlFlattenerTest, NoNamespaceIsNotTheSameAsEmptyNamespace) {
    std::string input = "<View xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        "      package=\"android\"/>";

    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

    while (tree.next() != ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), ResXMLTree::END_DOCUMENT);
    }

    const StringPiece16 kPackage = u"package";
    EXPECT_GE(tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size()), 0);
}

} // namespace xml
} // namespace aapt
