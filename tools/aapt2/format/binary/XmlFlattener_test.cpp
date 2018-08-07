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

#include "format/binary/XmlFlattener.h"

#include "androidfw/ResourceTypes.h"

#include "link/Linkers.h"
#include "test/Test.h"
#include "util/BigBuffer.h"
#include "util/Util.h"

using ::aapt::test::StrEq;
using ::android::StringPiece16;
using ::testing::Eq;
using ::testing::Ge;
using ::testing::IsNull;
using ::testing::Ne;
using ::testing::NotNull;

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

  ::testing::AssertionResult Flatten(xml::XmlResource* doc, android::ResXMLTree* out_tree,
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
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View xmlns:test="http://com.test" attr="hey">
          <Layout test:hello="hi" />
          <Layout>Some text\\</Layout>
      </View>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_NAMESPACE));

  size_t len;
  EXPECT_THAT(tree.getNamespacePrefix(&len), StrEq(u"test"));
  EXPECT_THAT(tree.getNamespaceUri(&len), StrEq(u"http://com.test"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"View"));

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeNamespace(0, &len), IsNull());
  EXPECT_THAT(tree.getAttributeName(0, &len), StrEq(u"attr"));

  const StringPiece16 kAttr(u"attr");
  EXPECT_THAT(tree.indexOfAttribute(nullptr, 0, kAttr.data(), kAttr.size()), Eq(0));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"Layout"));

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeNamespace(0, &len), StrEq(u"http://com.test"));
  EXPECT_THAT(tree.getAttributeName(0, &len), StrEq(u"hello"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));

  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"Layout"));
  ASSERT_THAT(tree.getAttributeCount(), Eq(0u));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"Some text\\"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));
  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"Layout"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));
  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"View"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_NAMESPACE));
  EXPECT_THAT(tree.getNamespacePrefix(&len), StrEq(u"test"));
  EXPECT_THAT(tree.getNamespaceUri(&len), StrEq(u"http://com.test"));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_DOCUMENT));
}

TEST_F(XmlFlattenerTest, FlattenCompiledXmlAndStripOnlyTools) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View xmlns:tools="http://schemas.android.com/tools"
          xmlns:foo="http://schemas.android.com/foo"
          foo:bar="Foo"
          tools:ignore="MissingTranslation"/>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_NAMESPACE));

  size_t len;
  EXPECT_THAT(tree.getNamespacePrefix(&len), StrEq(u"foo"));
  EXPECT_THAT(tree.getNamespaceUri(&len), StrEq(u"http://schemas.android.com/foo"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));

  EXPECT_THAT(tree.indexOfAttribute("http://schemas.android.com/tools", "ignore"),
              Eq(android::NAME_NOT_FOUND));
  EXPECT_THAT(tree.indexOfAttribute("http://schemas.android.com/foo", "bar"), Ge(0));
}

TEST_F(XmlFlattenerTest, AssignSpecialAttributeIndices) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:id="@id/id"
          class="str"
          style="@id/id"/>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  EXPECT_THAT(tree.indexOfClass(), Eq(0));
  EXPECT_THAT(tree.indexOfStyle(), Eq(1));
}

// The device ResXMLParser in libandroidfw differentiates between empty namespace and null
// namespace.
TEST_F(XmlFlattenerTest, NoNamespaceIsNotTheSameAsEmptyNamespace) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<View package="android"/>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  const StringPiece16 kPackage = u"package";
  EXPECT_THAT(tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size()), Ge(0));
}

TEST_F(XmlFlattenerTest, EmptyStringValueInAttributeIsNotNull) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<View package=""/>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  const StringPiece16 kPackage = u"package";
  ssize_t idx = tree.indexOfAttribute(nullptr, 0, kPackage.data(), kPackage.size());
  ASSERT_THAT(idx, Ge(0));

  size_t len;
  EXPECT_THAT(tree.getAttributeStringValue(idx, &len), NotNull());
}

TEST_F(XmlFlattenerTest, FlattenNonStandardPackageId) {
  context_->SetCompilationPackage("com.app.test.feature");
  context_->SetPackageId(0x80);
  context_->SetNameManglerPolicy({"com.app.test.feature"});

  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDomForPackageName(context_.get(), R"(
      <View xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:app="http://schemas.android.com/apk/res-auto"
            android:id="@id/foo"
            app:foo="@id/foo" />)");

  XmlReferenceLinker linker;
  ASSERT_TRUE(linker.Consume(context_.get(), doc.get()));

  // The tree needs a custom DynamicRefTable since it is not using a standard app ID (0x7f).
  android::DynamicRefTable dynamic_ref_table;
  dynamic_ref_table.addMapping(0x80, 0x80);

  android::ResXMLTree tree(&dynamic_ref_table);
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  ssize_t idx;

  idx = tree.indexOfAttribute(xml::kSchemaAndroid, "id");
  ASSERT_THAT(idx, Ge(0));
  EXPECT_THAT(tree.indexOfID(), Eq(idx));
  EXPECT_THAT(tree.getAttributeNameResID(idx), Eq(0x010100d0u));

  idx = tree.indexOfAttribute(xml::kSchemaAuto, "foo");
  ASSERT_THAT(idx, Ge(0));
  EXPECT_THAT(tree.getAttributeNameResID(idx), Eq(0x80010000u));
  EXPECT_THAT(tree.getAttributeDataType(idx), Eq(android::Res_value::TYPE_REFERENCE));
  EXPECT_THAT(tree.getAttributeData(idx), Eq(int32_t(0x80020000)));
}

TEST_F(XmlFlattenerTest, ProcessEscapedStrings) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(
      R"(<element value="\?hello" pattern="\\d{5}" other="&quot;">\\d{5}</element>)");

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  const StringPiece16 kValue = u"value";
  const StringPiece16 kPattern = u"pattern";
  const StringPiece16 kOther = u"other";

  size_t len;
  ssize_t idx;

  idx = tree.indexOfAttribute(nullptr, 0, kValue.data(), kValue.size());
  ASSERT_THAT(idx, Ge(0));
  EXPECT_THAT(tree.getAttributeStringValue(idx, &len), StrEq(u"?hello"));

  idx = tree.indexOfAttribute(nullptr, 0, kPattern.data(), kPattern.size());
  ASSERT_THAT(idx, Ge(0));
  EXPECT_THAT(tree.getAttributeStringValue(idx, &len), StrEq(u"\\d{5}"));

  idx = tree.indexOfAttribute(nullptr, 0, kOther.data(), kOther.size());
  ASSERT_THAT(idx, Ge(0));
  EXPECT_THAT(tree.getAttributeStringValue(idx, &len), StrEq(u"\""));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"\\d{5}"));
}

TEST_F(XmlFlattenerTest, ProcessQuotes) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(
      R"(<root>
          <item>Regular text</item>
          <item>"Text in double quotes"</item>
          <item>'Text in single quotes'</item>
          <item>Text containing "double quotes"</item>
          <item>Text containing 'single quotes'</item>
      </root>)");

  size_t len;
  android::ResXMLTree tree;

  XmlFlattenerOptions options;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"Regular text"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"\"Text in double quotes\""));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementNamespace(&len), IsNull());
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"'Text in single quotes'"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"Text containing \"double quotes\""));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"Text containing 'single quotes'"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_DOCUMENT));
}

TEST_F(XmlFlattenerTest, ProcessWhitepspace) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(
      R"(<root>
          <item>   Compact   Spaces   </item>
          <item>
                 A
          </item>
          <item>B   </item>
          <item>C </item>
          <item> D  </item>
          <item>   E</item>
          <item> F</item>
          <item>  G </item>
          <item> H </item>
<item>
I
</item>
<item>

   J

</item>
          <item>
          </item>
      </root>)");

  size_t len;
  android::ResXMLTree tree;

  XmlFlattenerOptions options;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" Compact   Spaces "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" A "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"B "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u"C "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" D "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" E"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" F"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" G "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" H "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" I "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::TEXT));
  EXPECT_THAT(tree.getText(&len), StrEq(u" J "));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::START_TAG));
  EXPECT_THAT(tree.getElementName(&len), StrEq(u"item"));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));
  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_TAG));

  ASSERT_THAT(tree.next(), Eq(android::ResXMLTree::END_DOCUMENT));
}

TEST_F(XmlFlattenerTest, FlattenRawValueOnlyMakesCompiledValueToo) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<element foo="bar" />)");

  // Raw values are kept when encoding an attribute with no compiled value, regardless of option.
  XmlFlattenerOptions options;
  options.keep_raw_values = false;

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Ge(0));
  EXPECT_THAT(tree.getAttributeDataType(0), Eq(android::Res_value::TYPE_STRING));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(tree.getAttributeData(0)));
}

TEST_F(XmlFlattenerTest, FlattenCompiledStringValuePreservesRawValue) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<element foo="bar" />)");
  doc->root->attributes[0].compiled_value =
      util::make_unique<String>(doc->string_pool.MakeRef("bar"));

  // Raw values are kept when encoding a string anyways.
  XmlFlattenerOptions options;
  options.keep_raw_values = false;

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Ge(0));
  EXPECT_THAT(tree.getAttributeDataType(0), Eq(android::Res_value::TYPE_STRING));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(tree.getAttributeData(0)));
}

TEST_F(XmlFlattenerTest, FlattenCompiledValueExcludesRawValueWithKeepRawOptionFalse) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<element foo="true" />)");
  doc->root->attributes[0].compiled_value = ResourceUtils::MakeBool(true);

  XmlFlattenerOptions options;
  options.keep_raw_values = false;

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(-1));
  EXPECT_THAT(tree.getAttributeDataType(0), Eq(android::Res_value::TYPE_INT_BOOLEAN));
}

TEST_F(XmlFlattenerTest, FlattenCompiledValueExcludesRawValueWithKeepRawOptionTrue) {
  std::unique_ptr<xml::XmlResource> doc = test::BuildXmlDom(R"(<element foo="true" />)");
  doc->root->attributes[0].compiled_value = ResourceUtils::MakeBool(true);

  XmlFlattenerOptions options;
  options.keep_raw_values = true;

  android::ResXMLTree tree;
  ASSERT_TRUE(Flatten(doc.get(), &tree, options));

  while (tree.next() != android::ResXMLTree::START_TAG) {
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::BAD_DOCUMENT));
    ASSERT_THAT(tree.getEventType(), Ne(android::ResXMLTree::END_DOCUMENT));
  }

  ASSERT_THAT(tree.getAttributeCount(), Eq(1u));
  EXPECT_THAT(tree.getAttributeValueStringID(0), Ge(0));

  size_t len;
  EXPECT_THAT(tree.getAttributeStringValue(0, &len), StrEq(u"true"));

  EXPECT_THAT(tree.getAttributeDataType(0), Eq(android::Res_value::TYPE_INT_BOOLEAN));
}

}  // namespace aapt
