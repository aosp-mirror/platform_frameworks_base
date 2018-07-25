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

#include "xml/XmlActionExecutor.h"

#include "test/Test.h"

using ::testing::NotNull;

namespace aapt {
namespace xml {

TEST(XmlActionExecutorTest, BuildsAccessibleNestedPattern) {
  XmlActionExecutor executor;
  XmlNodeAction& manifest_action = executor["manifest"];
  XmlNodeAction& application_action = manifest_action["application"];

  Element* manifest_el = nullptr;
  manifest_action.Action([&](Element* manifest) -> bool {
    manifest_el = manifest;
    return true;
  });

  Element* application_el = nullptr;
  application_action.Action([&](Element* application) -> bool {
    application_el = application;
    return true;
  });

  std::unique_ptr<XmlResource> doc =
      test::BuildXmlDom("<manifest><application /></manifest>");

  StdErrDiagnostics diag;
  ASSERT_TRUE(executor.Execute(XmlActionExecutorPolicy::kNone, &diag, doc.get()));
  ASSERT_THAT(manifest_el, NotNull());
  EXPECT_EQ(std::string("manifest"), manifest_el->name);

  ASSERT_THAT(application_el, NotNull());
  EXPECT_EQ(std::string("application"), application_el->name);
}

TEST(XmlActionExecutorTest, FailsWhenUndefinedHierarchyExists) {
  XmlActionExecutor executor;
  executor["manifest"]["application"];

  std::unique_ptr<XmlResource> doc;
  StdErrDiagnostics diag;

  doc = test::BuildXmlDom("<manifest><application /><activity /></manifest>");
  ASSERT_FALSE(executor.Execute(XmlActionExecutorPolicy::kWhitelist, &diag, doc.get()));

  doc = test::BuildXmlDom("<manifest><application><activity /></application></manifest>");
  ASSERT_FALSE(executor.Execute(XmlActionExecutorPolicy::kWhitelist, &diag, doc.get()));
}

}  // namespace xml
}  // namespace aapt
