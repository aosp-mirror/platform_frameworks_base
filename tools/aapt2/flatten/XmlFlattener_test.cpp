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

#include "androidfw/ResourceTypes.h"

#include "link/Linkers.h"
#include "test/Test.h"
#include "util/BigBuffer.h"
#include "util/Util.h"

using android::StringPiece16;

namespace aapt {

class XmlFlattenerTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ = test::ContextBuilder()
                   .SetCompilationPackage("com.app.test")
                   .SetNameManglerPolicy(NameManglerPolicy{"com.app.test"})
                   .AddSymbolSource(
                       test::StaticSymbolSourceBuilder()
                           .AddPublicSymbol("android:attr/id", ResourceId(0x010100d0),
                                            test::AttributeBuilder().Build())
                           .AddSymbol("com.app.test:id/id", ResourceId(0x7f020000))
                           .AddPublicSymbol("android:attr/paddingStart", ResourceId(0x010103b3),
                                            test::AttributeBuilder().Build())
                           .AddPublicSymbol("android:attr/colorAccent", ResourceId(0x01010435),
                                            test::AttributeBuilder().Build())
                           .AddSymbol("com.app.test.feature:id/foo", ResourceId(0x80020000))
                           .AddSymbol("com.app.test.feature:attr/foo", ResourceId(0x80010000),
                                      test::AttributeBuilder().Build())
                           .Build())
                   .Build();
  }

  ::testing::AssertionResult Flatten(xml::XmlResource* doc,
                                     android::ResXMLTree* out_tree,
                                     const XmlFlattenerOptions& options = {}) {
    using namespace android;  // For NO_ERROR on windows because it is a macro.

    BigBuffer buffer(1024);
    XmlFlattener flattener(&buffer, options);
    if (!flattener.Consume(context_.get(), doc)) {
      return ::testing::AssertionFailure() << "failed to flatten XML Tree";
    }

    std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
    if (out_tree->setTo(data.get(), buffer.size(), true) != NO_ERROR) {
      return ::testing::AssertionFailure() << "flattened XML is corrupt";
    }
    return ::testing::AssertionSuccess();
  }

 protected:
  std::unique_ptr<test::Context> context_;
};

TEST_F(XmlFlattenerTest, FlattenXmlWithNoCompiledAttributes) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
            <View xmlns:test="http://com.test"
                  attr="hey">
              <Layout test:hello="hi" />
              <Layout>Some text\\</Layout>
            </View>)EOF");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  ASSERT_EQ(android::ResXMLTree::START_NAMESPACE, tree.next());

  size_t len;
  const char16_t* namespace_prefix = tree.getNamespacePrefix(&len);
  EXPECT_EQ(StringPiece16(u"test"), StringPiece16(namespace_prefix, len));

  const char16_t* namespace_uri = tree.getNamespaceUri(&len);
  ASSERT_EQ(StringPiece16(u"http://com.test"), StringPiece16(namespace_uri, len));

  ASSERT_EQ(android::ResXMLTree::START_TAG, tree.next());

  ASSERT_EQ(nullptr, tree.getElementNamespace(&len));
  const char16_t* tag_name = tree.getElementName(&len);
  EXPECT_EQ(StringPiece16(u"View"), StringPiece16(tag_name, len));

  ASSERT_EQ(1u, tree.getAttributeCount());
  ASSERT_EQ(nullptr, tree.getAttributeNamespace(0, &len));
  const char16_t* attr_name = tree.getAttributeName(0, &len);
  EXPECT_EQ(StringPiece16(u"attr"), StringPiece16(attr_name, len));

  EXPECT_EQ(0, tree.indexOfAttribute(nullptr, 0, u"attr", StringPiece16(u"attr").size()));

  ASSERT_EQ(android::ResXMLTree::START_TAG, tree.next());

  ASSERT_EQ(nullptr, tree.getElementNamespace(&len));
  tag_name = tree.getElementName(&len);
  EXPECT_EQ(StringPiece16(u"Layout"), StringPiece16(tag_name, len));

  ASSERT_EQ(1u, tree.getAttributeCount());
  const char16_t* attr_namespace = tree.getAttributeNamespace(0, &len);
  EXPECT_EQ(StringPiece16(u"http://com.test"), StringPiece16(attr_namespace, len));

  attr_name = tree.getAttributeName(0, &len);
  EXPECT_EQ(StringPiece16(u"hello"), StringPiece16(attr_name, len));

  ASSERT_EQ(android::ResXMLTree::END_TAG, tree.next());
  ASSERT_EQ(android::ResXMLTree::START_TAG, tree.next());

  ASSERT_EQ(nullptr, tree.getElementNamespace(&len));
  tag_name = tree.getElementName(&len);
  EXPECT_EQ(StringPiece16(u"Layout"), StringPiece16(tag_name, len));
  ASSERT_EQ(0u, tree.getAttributeCount());

  ASSERT_EQ(android::ResXMLTree::TEXT, tree.next());
  const char16_t* text = tree.getText(&len);
  EXPECT_EQ(StringPiece16(u"Some text\\"), StringPiece16(text, len));

  ASSERT_EQ(android::ResXMLTree::END_TAG, tree.next());
  ASSERT_EQ(nullptr, tree.getElementNamespace(&len));
  tag_name = tree.getElementName(&len);
  EXPECT_EQ(StringPiece16(u"Layout"), StringPiece16(tag_name, len));

  ASSERT_EQ(android::ResXMLTree::END_TAG, tree.next());
  ASSERT_EQ(nullptr, tree.getElementNamespace(&len));
  tag_name = tree.getElementName(&len);
  EXPECT_EQ(StringPiece16(u"View"), StringPiece16(tag_name, len));

  ASSERT_EQ(android::ResXMLTree::END_NAMESPACE, tree.next());
  namespace_prefix = tree.getNamespacePrefix(&len);
  EXPECT_EQ(StringPiece16(u"test"), StringPiece16(namespace_prefix, len));

  namespace_uri = tree.getNamespaceUri(&len);
  ASSERT_EQ(StringPiece16(u"http://com.test"), StringPiece16(namespace_uri, len));

  ASSERT_EQ(android::ResXMLTree::END_DOCUMENT, tree.next());
}

TEST_F(XmlFlattenerTest, FlattenCompiledXmlAndStripOnlyTools) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
            <View xmlns:tools="http://schemas.android.com/tools"
                xmlns:foo="http://schemas.android.com/foo"
                foo:bar="Foo"
                tools:ignore="MissingTranslation"/>)EOF");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  ASSERT_EQ(tree.next(), android::ResXMLTree::START_NAMESPACE);

  size_t len;
  const char16_t* namespace_prefix = tree.getNamespacePrefix(&len);
  EXPECT_EQ(StringPiece16(namespace_prefix, len), u"foo");

  const char16_t* namespace_uri = tree.getNamespaceUri(&len);
  ASSERT_EQ(StringPiece16(namespace_uri, len),
            u"http://schemas.android.com/foo");

  ASSERT_EQ(tree.next(), android::ResXMLTree::START_TAG);

  EXPECT_EQ(tree.indexOfAttribute("http://schemas.android.com/tools", "ignore"),
            android::NAME_NOT_FOUND);
  EXPECT_GE(tree.indexOfAttribute("http://schemas.android.com/foo", "bar"), 0);
}

TEST_F(XmlFlattenerTest, AssignSpecialAttributeIndices) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:id="@id/id"
                  class="str"
                  style="@id/id"/>)EOF");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
  }

  EXPECT_EQ(tree.indexOfClass(), 0);
  EXPECT_EQ(tree.indexOfStyle(), 1);
}

// The device ResXMLParser in libandroidfw differentiates between empty namespace and null
// namespace.
TEST_F(XmlFlattenerTest, NoNamespaceIsNotTheSameAsEmptyNamespace) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom("<View package=\"android\"/>");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
  }

  const StringPiece16 kPackage = u"package";
  EXPECT_GE(tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size()), 0);
}

TEST_F(XmlFlattenerTest, EmptyStringValueInAttributeIsNotNull) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom("<View package=\"\"/>");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
  }

  const StringPiece16 kPackage = u"package";
  ssize_t idx = tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size());
  ASSERT_GE(idx, 0);

  size_t len;
  EXPECT_NE(nullptr, tree.getAttributeStringValue(idx, &len));
}

TEST_F(XmlFlattenerTest, FlattenNonStandardPackageId) {
  context_->SetCompilationPackage("com.app.test.feature");
  context_->SetPackageId(0x80);
  context_->SetNameManglerPolicy({"com.app.test.feature"});

  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"EOF(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@id/foo"
            app:foo="@id/foo" />)EOF");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  // The tree needs a custom DynamicRefTable since it is not using a standard app ID (0x7f).
  android::DynamicRefTable dynamic_ref_table;
  dynamic_ref_table.addMapping(0x80, 0x80);

  android::ResXMLTree tree(&dynamic_ref_table);
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_NE(android::ResXMLTree::BAD_DOCUMENT, tree.getEventType());
    ASSERT_NE(android::ResXMLTree::END_DOCUMENT, tree.getEventType());
  }

  ssize_t idx;

  idx = tree.indexOfAttribute(xml::kSchemaAndroid, "id");
  ASSERT_GE(idx, 0);
  EXPECT_EQ(idx, tree.indexOfID());
  EXPECT_EQ(ResourceId(0x010100d0), ResourceId(tree.getAttributeNameResID(idx)));

  idx = tree.indexOfAttribute(xml::kSchemaAuto, "foo");
  ASSERT_GE(idx, 0);
  EXPECT_EQ(ResourceId(0x80010000), ResourceId(tree.getAttributeNameResID(idx)));
  EXPECT_EQ(android::Res_value::TYPE_REFERENCE, tree.getAttributeDataType(idx));
  EXPECT_EQ(ResourceId(0x80020000), tree.getAttributeData(idx));
}

TEST_F(XmlFlattenerTest, ProcessEscapedStrings) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(
      R"EOF(<element value="\?hello" pattern="\\d{5}">\\d{5}</element>)EOF");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::BAD_DOCUMENT);
    ASSERT_NE(tree.getEventType(), android::ResXMLTree::END_DOCUMENT);
  }

  const StringPiece16 kValue = u"value";
  const StringPiece16 kPattern = u"pattern";

  size_t len;
  ssize_t idx;
  const char16_t* str16;

  idx = tree.indexOfAttribute(nullptr, 0, kValue.data(), kValue.size());
  ASSERT_GE(idx, 0);
  str16 = tree.getAttributeStringValue(idx, &len);
  ASSERT_NE(nullptr, str16);
  EXPECT_EQ(StringPiece16(u"?hello"), StringPiece16(str16, len));

  idx = tree.indexOfAttribute(nullptr, 0, kPattern.data(), kPattern.size());
  ASSERT_GE(idx, 0);
  str16 = tree.getAttributeStringValue(idx, &len);
  ASSERT_NE(nullptr, str16);
  EXPECT_EQ(StringPiece16(u"\\d{5}"), StringPiece16(str16, len));

  ASSERT_EQ(android::ResXMLTree::TEXT, tree.next());
  str16 = tree.getText(&len);
  ASSERT_NE(nullptr, str16);
  EXPECT_EQ(StringPiece16(u"\\d{5}"), StringPiece16(str16, len));
}

}  // namespace aapt
