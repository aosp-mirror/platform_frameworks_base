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

#include "java/ManifestClassGenerator.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

static ::testing::AssertionResult getManifestClassText(IAaptContext* context, xml::XmlResource* res,
                                                       std::string* outStr) {
    std::unique_ptr<ClassDefinition> manifestClass = generateManifestClass(
            context->getDiagnostics(), res);
    if (!manifestClass) {
        return ::testing::AssertionFailure() << "manifestClass == nullptr";
    }

    std::stringstream out;
    if (!manifestClass->writeJavaFile(manifestClass.get(), "android", true, &out)) {
        return ::testing::AssertionFailure() << "failed to write java file";
    }

    *outStr = out.str();
    return ::testing::AssertionSuccess();
}

TEST(ManifestClassGeneratorTest, NameIsProperlyGeneratedFromSymbol) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<xml::XmlResource> manifest = test::buildXmlDom(R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <permission android:name="android.permission.ACCESS_INTERNET" />
          <permission android:name="android.DO_DANGEROUS_THINGS" />
          <permission android:name="com.test.sample.permission.HUH" />
          <permission-group android:name="foo.bar.PERMISSION" />
        </manifest>)EOF");

    std::string actual;
    ASSERT_TRUE(getManifestClassText(context.get(), manifest.get(), &actual));

    const size_t permissionClassPos = actual.find("public static final class permission {");
    const size_t permissionGroupClassPos =
            actual.find("public static final class permission_group {");
    ASSERT_NE(std::string::npos, permissionClassPos);
    ASSERT_NE(std::string::npos, permissionGroupClassPos);

    //
    // Make sure these permissions are in the permission class.
    //

    size_t pos = actual.find("public static final String ACCESS_INTERNET="
                             "\"android.permission.ACCESS_INTERNET\";");
    EXPECT_GT(pos, permissionClassPos);
    EXPECT_LT(pos, permissionGroupClassPos);

    pos = actual.find("public static final String DO_DANGEROUS_THINGS="
                      "\"android.DO_DANGEROUS_THINGS\";");
    EXPECT_GT(pos, permissionClassPos);
    EXPECT_LT(pos, permissionGroupClassPos);

    pos = actual.find("public static final String HUH=\"com.test.sample.permission.HUH\";");
    EXPECT_GT(pos, permissionClassPos);
    EXPECT_LT(pos, permissionGroupClassPos);

    //
    // Make sure these permissions are in the permission_group class
    //

    pos = actual.find("public static final String PERMISSION="
                      "\"foo.bar.PERMISSION\";");
    EXPECT_GT(pos, permissionGroupClassPos);
    EXPECT_LT(pos, std::string::npos);
}

TEST(ManifestClassGeneratorTest, CommentsAndAnnotationsArePresent) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::unique_ptr<xml::XmlResource> manifest = test::buildXmlDom(R"EOF(
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <!-- Required to access the internet.
               Added in API 1. -->
          <permission android:name="android.permission.ACCESS_INTERNET" />
          <!-- @deprecated This permission is for playing outside. -->
          <permission android:name="android.permission.PLAY_OUTSIDE" />
          <!-- This is a private permission for system only!
               @hide
               @SystemApi -->
          <permission android:name="android.permission.SECRET" />
        </manifest>)EOF");

    std::string actual;
    ASSERT_TRUE(getManifestClassText(context.get(), manifest.get(), &actual));

    const char* expectedAccessInternet =
R"EOF(    /**
     * Required to access the internet.
     * Added in API 1.
     */
    public static final String ACCESS_INTERNET="android.permission.ACCESS_INTERNET";)EOF";

    EXPECT_NE(std::string::npos, actual.find(expectedAccessInternet));

    const char* expectedPlayOutside =
R"EOF(    /**
     * @deprecated This permission is for playing outside.
     */
    @Deprecated
    public static final String PLAY_OUTSIDE="android.permission.PLAY_OUTSIDE";)EOF";

    EXPECT_NE(std::string::npos, actual.find(expectedPlayOutside));

    const char* expectedSecret =
R"EOF(    /**
     * This is a private permission for system only!
     * @hide
     */
    @android.annotation.SystemApi
    public static final String SECRET="android.permission.SECRET";)EOF";

    EXPECT_NE(std::string::npos, actual.find(expectedSecret));
}

} // namespace aapt
