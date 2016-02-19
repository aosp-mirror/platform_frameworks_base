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

#include <test/Context.h>
#include "link/Linkers.h"
#include "test/Test.h"

namespace aapt {

class XmlReferenceLinkerTest : public ::testing::Test {
public:
    void SetUp() override {
        mContext = test::ContextBuilder()
                .setCompilationPackage(u"com.app.test")
                .setNameManglerPolicy(
                        NameManglerPolicy{ u"com.app.test", { u"com.android.support" } })
                .addSymbolSource(test::StaticSymbolSourceBuilder()
                        .addPublicSymbol(u"@android:attr/layout_width", ResourceId(0x01010000),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_ENUM |
                                                     android::ResTable_map::TYPE_DIMENSION)
                                        .addItem(u"match_parent", 0xffffffff)
                                        .build())
                        .addPublicSymbol(u"@android:attr/background", ResourceId(0x01010001),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_COLOR).build())
                        .addPublicSymbol(u"@android:attr/attr", ResourceId(0x01010002),
                                   test::AttributeBuilder().build())
                        .addPublicSymbol(u"@android:attr/text", ResourceId(0x01010003),
                                   test::AttributeBuilder()
                                        .setTypeMask(android::ResTable_map::TYPE_STRING)
                                        .build())

                         // Add one real symbol that was introduces in v21
                        .addPublicSymbol(u"@android:attr/colorAccent", ResourceId(0x01010435),
                                   test::AttributeBuilder().build())

                        // Private symbol.
                        .addSymbol(u"@android:color/hidden", ResourceId(0x01020001))

                        .addPublicSymbol(u"@android:id/id", ResourceId(0x01030000))
                        .addSymbol(u"@com.app.test:id/id", ResourceId(0x7f030000))
                        .addSymbol(u"@com.app.test:color/green", ResourceId(0x7f020000))
                        .addSymbol(u"@com.app.test:color/red", ResourceId(0x7f020001))
                        .addSymbol(u"@com.app.test:attr/colorAccent", ResourceId(0x7f010000),
                                   test::AttributeBuilder()
                                       .setTypeMask(android::ResTable_map::TYPE_COLOR).build())
                        .addPublicSymbol(u"@com.app.test:attr/com.android.support$colorAccent",
                                   ResourceId(0x7f010001), test::AttributeBuilder()
                                       .setTypeMask(android::ResTable_map::TYPE_COLOR).build())
                        .addPublicSymbol(u"@com.app.test:attr/attr", ResourceId(0x7f010002),
                                   test::AttributeBuilder().build())
                        .build())
                .build();
    }

protected:
    std::unique_ptr<IAaptContext> mContext;
};

TEST_F(XmlReferenceLinkerTest, LinkBasicAttributes) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
        <View xmlns:android="http://schemas.android.com/apk/res/android"
              android:layout_width="match_parent"
              android:background="@color/green"
              android:text="hello"
              class="hello" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));

    xml::Element* viewEl = xml::findRootElement(doc.get());
    ASSERT_NE(viewEl, nullptr);

    xml::Attribute* xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res/android",
                                                    u"layout_width");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x01010000));
    ASSERT_NE(xmlAttr->compiledValue, nullptr);
    ASSERT_NE(valueCast<BinaryPrimitive>(xmlAttr->compiledValue.get()), nullptr);

    xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res/android", u"background");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x01010001));
    ASSERT_NE(xmlAttr->compiledValue, nullptr);
    Reference* ref = valueCast<Reference>(xmlAttr->compiledValue.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->name);
    EXPECT_EQ(ref->name.value(), test::parseNameOrDie(u"@color/green")); // Make sure the name
                                                                         // didn't change.
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f020000));

    xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res/android", u"text");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    ASSERT_FALSE(xmlAttr->compiledValue);   // Strings don't get compiled for memory sake.

    xmlAttr = viewEl->findAttribute(u"", u"class");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_FALSE(xmlAttr->compiledAttribute);
    ASSERT_EQ(xmlAttr->compiledValue, nullptr);
}

TEST_F(XmlReferenceLinkerTest, PrivateSymbolsAreNotLinked) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
        <View xmlns:android="http://schemas.android.com/apk/res/android"
              android:colorAccent="@android:color/hidden" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_FALSE(linker.consume(mContext.get(), doc.get()));
}

TEST_F(XmlReferenceLinkerTest, PrivateSymbolsAreLinkedWhenReferenceHasStarPrefix) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
    <View xmlns:android="http://schemas.android.com/apk/res/android"
          android:colorAccent="@*android:color/hidden" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));
}

TEST_F(XmlReferenceLinkerTest, SdkLevelsAreRecorded) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
        <View xmlns:android="http://schemas.android.com/apk/res/android"
              android:colorAccent="#ffffff" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));
    EXPECT_TRUE(linker.getSdkLevels().count(21) == 1);
}

TEST_F(XmlReferenceLinkerTest, LinkMangledAttributes) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
            <View xmlns:support="http://schemas.android.com/apk/res/com.android.support"
                  support:colorAccent="#ff0000" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));

    xml::Element* viewEl = xml::findRootElement(doc.get());
    ASSERT_NE(viewEl, nullptr);

    xml::Attribute* xmlAttr = viewEl->findAttribute(
            u"http://schemas.android.com/apk/res/com.android.support", u"colorAccent");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x7f010001));
    ASSERT_NE(valueCast<BinaryPrimitive>(xmlAttr->compiledValue.get()), nullptr);
}

TEST_F(XmlReferenceLinkerTest, LinkAutoResReference) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
            <View xmlns:app="http://schemas.android.com/apk/res-auto"
                  app:colorAccent="@app:color/red" />)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));

    xml::Element* viewEl = xml::findRootElement(doc.get());
    ASSERT_NE(viewEl, nullptr);

    xml::Attribute* xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res-auto",
                                                    u"colorAccent");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x7f010000));
    Reference* ref = valueCast<Reference>(xmlAttr->compiledValue.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->name);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f020001));
}

TEST_F(XmlReferenceLinkerTest, LinkViewWithShadowedPackageAlias) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
            <View xmlns:app="http://schemas.android.com/apk/res/android"
                  app:attr="@app:id/id">
              <View xmlns:app="http://schemas.android.com/apk/res/com.app.test"
                    app:attr="@app:id/id"/>
            </View>)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));

    xml::Element* viewEl = xml::findRootElement(doc.get());
    ASSERT_NE(viewEl, nullptr);

    // All attributes and references in this element should be referring to "android" (0x01).
    xml::Attribute* xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res/android",
                                                    u"attr");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x01010002));
    Reference* ref = valueCast<Reference>(xmlAttr->compiledValue.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x01030000));

    ASSERT_FALSE(viewEl->getChildElements().empty());
    viewEl = viewEl->getChildElements().front();
    ASSERT_NE(viewEl, nullptr);

    // All attributes and references in this element should be referring to "com.app.test" (0x7f).
    xmlAttr = viewEl->findAttribute(u"http://schemas.android.com/apk/res/com.app.test", u"attr");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x7f010002));
    ref = valueCast<Reference>(xmlAttr->compiledValue.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f030000));
}

TEST_F(XmlReferenceLinkerTest, LinkViewWithLocalPackageAndAliasOfTheSameName) {
    std::unique_ptr<xml::XmlResource> doc = test::buildXmlDomForPackageName(mContext.get(), R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/com.app.test"
                  android:attr="@id/id"/>)EOF");

    XmlReferenceLinker linker;
    ASSERT_TRUE(linker.consume(mContext.get(), doc.get()));

    xml::Element* viewEl = xml::findRootElement(doc.get());
    ASSERT_NE(viewEl, nullptr);

    // All attributes and references in this element should be referring to "com.app.test" (0x7f).
    xml::Attribute* xmlAttr = viewEl->findAttribute(
            u"http://schemas.android.com/apk/res/com.app.test", u"attr");
    ASSERT_NE(xmlAttr, nullptr);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute);
    AAPT_ASSERT_TRUE(xmlAttr->compiledAttribute.value().id);
    EXPECT_EQ(xmlAttr->compiledAttribute.value().id.value(), ResourceId(0x7f010002));
    Reference* ref = valueCast<Reference>(xmlAttr->compiledValue.get());
    ASSERT_NE(ref, nullptr);
    AAPT_ASSERT_TRUE(ref->id);
    EXPECT_EQ(ref->id.value(), ResourceId(0x7f030000));
}

} // namespace aapt
