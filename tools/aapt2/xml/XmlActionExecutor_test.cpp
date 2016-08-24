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

#include "test/Test.h"
#include "xml/XmlActionExecutor.h"

namespace aapt {
namespace xml {

TEST(XmlActionExecutorTest, BuildsAccessibleNestedPattern) {
    XmlActionExecutor executor;
    XmlNodeAction& manifestAction = executor[u"manifest"];
    XmlNodeAction& applicationAction = manifestAction[u"application"];

    Element* manifestEl = nullptr;
    manifestAction.action([&](Element* manifest) -> bool {
        manifestEl = manifest;
        return true;
    });

    Element* applicationEl = nullptr;
    applicationAction.action([&](Element* application) -> bool {
        applicationEl = application;
        return true;
    });

    std::unique_ptr<XmlResource> doc = test::buildXmlDom("<manifest><application /></manifest>");

    StdErrDiagnostics diag;
    ASSERT_TRUE(executor.execute(XmlActionExecutorPolicy::None, &diag, doc.get()));
    ASSERT_NE(nullptr, manifestEl);
    EXPECT_EQ(std::u16string(u"manifest"), manifestEl->name);

    ASSERT_NE(nullptr, applicationEl);
    EXPECT_EQ(std::u16string(u"application"), applicationEl->name);
}

TEST(XmlActionExecutorTest, FailsWhenUndefinedHierarchyExists) {
    XmlActionExecutor executor;
    executor[u"manifest"][u"application"];

    std::unique_ptr<XmlResource> doc = test::buildXmlDom(
            "<manifest><application /><activity /></manifest>");
    StdErrDiagnostics diag;
    ASSERT_FALSE(executor.execute(XmlActionExecutorPolicy::Whitelist, &diag, doc.get()));
}

} // namespace xml
} // namespace aapt
