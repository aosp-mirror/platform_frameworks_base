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

#include "Resolver.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SourceXmlPullParser.h"
#include "Util.h"
#include "XmlFlattener.h"

#include <androidfw/AssetManager.h>
#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>
#include <sstream>
#include <string>

using namespace android;

namespace aapt {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

struct MockResolver : public IResolver {
    MockResolver(const StringPiece16& defaultPackage,
                 const std::map<ResourceName, ResourceId>& items) :
            mPackage(defaultPackage.toString()), mAttr(false, ResTable_map::TYPE_ANY),
            mItems(items) {
    }

    virtual const std::u16string& getDefaultPackage() const override {
        return mPackage;
    }

    virtual Maybe<ResourceId> findId(const ResourceName& name) override {
        const auto iter = mItems.find(name);
        if (iter != mItems.end()) {
            return iter->second;
        }
        return {};
    }

    virtual Maybe<Entry> findAttribute(const ResourceName& name) override {
        Maybe<ResourceId> result = findId(name);
        if (result) {
            if (name.type == ResourceType::kAttr) {
                return Entry{ result.value(), &mAttr };
            } else {
                return Entry{ result.value() };
            }
        }
        return {};
    }

    virtual Maybe<ResourceName> findName(ResourceId resId) override {
        for (auto& p : mItems) {
            if (p.second == resId) {
                return p.first;
            }
        }
        return {};
    }

    std::u16string mPackage;
    Attribute mAttr;
    std::map<ResourceName, ResourceId> mItems;
};

class XmlFlattenerTest : public ::testing::Test {
public:
    virtual void SetUp() override {
        std::shared_ptr<IResolver> resolver = std::make_shared<MockResolver>(u"android",
                std::map<ResourceName, ResourceId>({
                        { ResourceName{ u"android", ResourceType::kAttr, u"attr" },
                          ResourceId{ 0x01010000u } },
                        { ResourceName{ u"android", ResourceType::kId, u"id" },
                          ResourceId{ 0x01020000u } },
                        { ResourceName{ u"com.lib", ResourceType::kAttr, u"attr" },
                          ResourceId{ 0x01010001u } },
                        { ResourceName{ u"com.lib", ResourceType::kId, u"id" },
                          ResourceId{ 0x01020001u } }}));

        mFlattener = std::make_shared<XmlFlattener>(nullptr, resolver);
    }

    ::testing::AssertionResult testFlatten(const std::string& in, ResXMLTree* outTree) {
        std::stringstream input(kXmlPreamble);
        input << in << std::endl;
        std::shared_ptr<XmlPullParser> xmlParser = std::make_shared<SourceXmlPullParser>(input);
        BigBuffer outBuffer(1024);
        XmlFlattener::Options xmlOptions;
        xmlOptions.defaultPackage = u"android";
        if (!mFlattener->flatten(Source{ "test" }, xmlParser, &outBuffer, xmlOptions)) {
            return ::testing::AssertionFailure();
        }

        std::unique_ptr<uint8_t[]> data = util::copy(outBuffer);
        if (outTree->setTo(data.get(), outBuffer.size(), true) != NO_ERROR) {
            return ::testing::AssertionFailure();
        }
        return ::testing::AssertionSuccess();
    }

    std::shared_ptr<XmlFlattener> mFlattener;
};

TEST_F(XmlFlattenerTest, ParseSimpleView) {
    std::string input = "<View xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        "      android:attr=\"@id/id\">\n"
                        "</View>";
    ResXMLTree tree;
    ASSERT_TRUE(testFlatten(input, &tree));

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

} // namespace aapt
