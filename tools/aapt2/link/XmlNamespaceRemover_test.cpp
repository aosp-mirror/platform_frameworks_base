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

#include "link/Linkers.h"

#include "test/Test.h"

namespace aapt {

class XmlUriTestVisitor : public xml::Visitor {
 public:
  XmlUriTestVisitor() = default;

  void Visit(xml::Element* el) override {
    for (const auto& attr : el->attributes) {
      EXPECT_EQ(std::string(), attr.namespace_uri);
    }
    EXPECT_EQ(std::string(), el->namespace_uri);
    xml::Visitor::Visit(el);
  }

  void Visit(xml::Namespace* ns) override {
    EXPECT_EQ(std::string(), ns->namespace_uri);
    xml::Visitor::Visit(ns);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlUriTestVisitor);
};

class XmlNamespaceTestVisitor : public xml::Visitor {
 public:
  XmlNamespaceTestVisitor() = default;

  void Visit(xml::Namespace* ns) override {
    ADD_FAILURE() << "Detected namespace: " << ns->namespace_prefix << "=\""
                  << ns->namespace_uri << "\"";
    xml::Visitor::Visit(ns);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(XmlNamespaceTestVisitor);
};

class XmlNamespaceRemoverTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ =
        test::ContextBuilder().SetCompilationPackage("com.app.test").Build();
  }

 protected:
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(XmlNamespaceRemoverTest, RemoveUris) {
  std::unique_ptr<xml::XmlResource> doc =
      test::BuildXmlDomForPackageName(context_.get(), R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:text="hello" />)EOF");

  XmlNamespaceRemover remover;
  ASSERT_TRUE(remover.Consume(context_.get(), doc.get()));

  xml::Node* root = doc.get()->root.get();
  ASSERT_NE(root, nullptr);

  XmlUriTestVisitor visitor;
  root->Accept(&visitor);
}

TEST_F(XmlNamespaceRemoverTest, RemoveNamespaces) {
  std::unique_ptr<xml::XmlResource> doc =
      test::BuildXmlDomForPackageName(context_.get(), R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  xmlns:foo="http://schemas.android.com/apk/res/foo"
                  foo:bar="foobar"
                  android:text="hello" />)EOF");

  XmlNamespaceRemover remover;
  ASSERT_TRUE(remover.Consume(context_.get(), doc.get()));

  xml::Node* root = doc.get()->root.get();
  ASSERT_NE(root, nullptr);

  XmlNamespaceTestVisitor visitor;
  root->Accept(&visitor);
}

TEST_F(XmlNamespaceRemoverTest, RemoveNestedNamespaces) {
  std::unique_ptr<xml::XmlResource> doc =
      test::BuildXmlDomForPackageName(context_.get(), R"EOF(
            <View xmlns:android="http://schemas.android.com/apk/res/android"
                  android:text="hello">
              <View xmlns:foo="http://schemas.example.com/foo"
                    android:text="foo"/>
            </View>)EOF");

  XmlNamespaceRemover remover;
  ASSERT_TRUE(remover.Consume(context_.get(), doc.get()));

  xml::Node* root = doc.get()->root.get();
  ASSERT_NE(root, nullptr);

  XmlNamespaceTestVisitor visitor;
  root->Accept(&visitor);
}

}  // namespace aapt
