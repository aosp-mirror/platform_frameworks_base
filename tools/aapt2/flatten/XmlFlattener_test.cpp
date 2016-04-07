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

#include "flatten/XmlFlattener.h"
#include "link/Linkers.h"
#include "test/Builders.h"
#include "test/Context.h"
#include "util/BigBuffer.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <gtest/gtest.h>

namespace aapt {

class XmlFlattenerTest : public ::testing::Test {
public:
    void SetUp() override {
        mContext = test::ContextBuilder()
                .setCompilationPackage(u"com.app.test")
                .setNameManglerPolicy(NameManglerPolicy{ u"com.app.test" })
                .addSymbolSource(test::StaticSymbolSourceBuilder()
                        .addSymbol(u"@android:attr/id", ResourceId(0x010100d0),
                                   test::AttributeBuilder().build())
                        .addSymbol(u"@com.app.test:id/id", ResourceId(0x7f020000))
                        .addSymbol(u"@android:attr/paddingStart", ResourceId(0x010103b3),
                                   test::AttributeBuilder().build())
                        .addSymbol(u"@android:attr/colorAccent", ResourceId(0x01010435),
                                   test::AttributeBuilder().build())
                        .build())
                .build();
    }

    ::testing::AssertionResult flatten(xml::XmlResource* doc, android::ResXMLTree* outTree,
                                       XmlFlattenerOptions options = {}) {
        using namespace android; // For NO_ERROR on windows because it is a macro.

        BigBuffer buffer(1024);
        XmlFlattener flattener(&buffer, options);
        if (!flattener.consume(mContext.get(), doc)) {
            return ::testing::AssertionFailure() << "failed to flatten XML Tree";
        }

        std::unique_ptr<uint8_t[]> data = util::copy(buffer);
        if (outTree->setTo(data.get(), buffer.size(), true) != NO_ERROR) {
            return ::testing::AssertionFailure() << "flattened XML is corrupt";
        }
        return ::testing::AssertionSuccess();
    }

protected:
    std::unique_ptr<IAaptContext> mContext;
};

TEST_F(XmlFlattenerTest, FlattenXmlWithNoCompiledAttributes) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
            <View xmlns:test="http://com.test"
                  attr="hey">
              <Layout test:hello="hi" />
              <Layout>Some text</Layout>
            </View>)EOF");


    android::ResXMLTree tree;
    ASSERT_TRUE(flatten(doc.get(), &tree));

    ASSERT_EQ(tree.next(), android::ResXMLTree::START_NAMESPACE);

    size_t len;
    const char16_t* namespacePrefix = tree.getNamespacePrefix(&len);
    EXPECT_EQ(StringPiece16(namespacePrefix, len), u"test");

    const char16_t* namespaceUri = tree.getNamespaceUri(&len);
    ASSERT_EQ(StringPiece16(namespaceUri, len), u"http://com.test");

    ASSERT_EQ(tree.next(), android::ResXMLTree::START_TAG);

    ASSERT_EQ(tree.getElementNamespace(&len), nullptr);
    const char16_t* tagName = tree.getElementName(&len);
    EXPECT_EQ(StringPiece16(tagName, len), u"View");

    ASSERT_EQ(1u, tree.getAttributeCount());
    ASSERT_EQ(tree.getAttributeNamespace(0, &len), nullptr);
    const char16_t* attrName = tree.getAttributeName(0, &len);
    EXPECT_EQ(StringPiece16(attrName, len), u"attr");

    EXPECT_EQ(0, tree.indexOfAttribute(nullptr, 0, u"attr", StringPiece16(u"attr").size()));

    ASSERT_EQ(tree.next(), android::ResXMLTree::START_TAG);

    ASSERT_EQ(tree.getElementNamespace(&len), nullptr);
    tagName = tree.getElementName(&len);
    EXPECT_EQ(StringPiece16(tagName, len), u"Layout");

    ASSERT_EQ(1u, tree.getAttributeCount());
    const char16_t* attrNamespace = tree.getAttributeNamespace(0, &len);
    EXPECT_EQ(StringPiece16(attrNamespace, len), u"http://com.test");

    attrName = tree.getAttributeName(0, &len);
    EXPECT_EQ(StringPiece16(attrName, len), u"hello");

    ASSERT_EQ(tree.next(), android::ResXMLTree::END_TAG);
    ASSERT_EQ(tree.next(), android::ResXMLTree::START_TAG);

    ASSERT_EQ(tree.getElementNamespace(&len), nullptr);
    tagName = tree.getElementName(&len);
    EXPECT_EQ(StringPiece16(tagName, len), u"Layout");
    ASSERT_EQ(0u, tree.getAttributeCount());

    ASSERT_EQ(tree.next(), android::ResXMLTree::TEXT);
    const char16_t* text = tree.getText(&len);
    EXPECT_EQ(StringPiece16(text, len), u"Some text");

    ASSERT_EQ(tree.next(), android::ResXMLTree::END_TAG);
    ASSERT_EQ(tree.getElementNamespace(&len), nullptr);
    tagName = tree.getElementName(&len);
    EXPECT_EQ(StringPiece16(tagName, len), u"Layout");

    ASSERT_EQ(tree.next(), android::ResXMLTree::END_TAG);
    ASSERT_EQ(tree.getElementNamespace(&len), nullptr);
    tagName = tree.getElementName(&len);
    EXPECT_EQ(StringPiece16(tagName, len), u"View");

    ASSERT_EQ(tree.next(), android::ResXMLTree::END_NAMESPACE);
    namespacePrefix = tree.getNamespacePrefix(&len);
    EXPECT_EQ(StringPiece16(namespacePrefix, len), u"test");

    namespaceUri = tree.getNamespaceUri(&len);
    ASSERT_EQ(StringPiece16(namespaceUri, len), u"http://com.test");

    ASSERT_EQ(tree.next(), android::ResXMLTree::END_DOCUMENT);
}

TEST_F(XmlFlattenerTest, FlattenCompiledXmlAndStripSdk21) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                android:paddingStart="1dp"
                android:colorAccent="#ffffff"/>)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));
    ASSERT_TRUE(linker.getSdkLevels().count(17) == 1);
    ASSERT_TRUE(linker.getSdkLevels().count(21) == 1);

    android::ResXMLTree tree;
    XmlFlattenerOptions options;
    options.maxSdkLevel = 17;
    ASSERT_TRUE(flatten(doc.get(), &tree, options));

    while (tree.next() != android::ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
    }

    ASSERT_EQ(1u, tree.getAttributeCount());
    EXPECT_EQ(uint32_t(0x010103b3), tree.getAttributeNameResID(0));
}

TEST_F(XmlFlattenerTest, AssignSpecialAttributeIndices) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom(R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:id="@id/id"
                  class="str"
                  style="@id/id"/>)EOF");

    android::ResXMLTree tree;
    ASSERT_TRUE(flatten(doc.get(), &tree));

    while (tree.next() != android::ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
    }

    EXPECT_EQ(tree.indexOfClass(), 0);
    EXPECT_EQ(tree.indexOfStyle(), 1);
}

/*
 * The device ResXMLParser in libandroidfw differentiates between empty namespace and null
 * namespace.
 */
TEST_F(XmlFlattenerTest, NoNamespaceIsNotTheSameAsEmptyNamespace) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDom("<View package=\"android\"/>");

    android::ResXMLTree tree;
    ASSERT_TRUE(flatten(doc.get(), &tree));

    while (tree.next() != android::ResXMLTree::START_TAG) {
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
        ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
    }

    const StringPiece16 kPackage = u"package";
    EXPECT_GE(tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size()), 0);
}

} // namespace aapt
