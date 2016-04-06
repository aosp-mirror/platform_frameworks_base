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

#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "java/AnnotationProcessor.h"
#include "test/Builders.h"
#include "test/Context.h"
#include "xml/XmlPullParser.h"

#include <gtest/gtest.h>

namespace aapt {

struct AnnotationProcessorTest : public ::testing::Test {
    std::unique_ptr<IAaptContext> mContext;
    ResourceTable mTable;

    void SetUp() override {
        mContext = test::ContextBuilder().build();
    }

    ::testing::AssertionResult parse(const StringPiece& str) {
        ResourceParserOptions options;
        ResourceParser parser(mContext->getDiagnostics(), &mTable, Source{}, ConfigDescription{},
                              options);
        std::stringstream in;
        in << "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" << str;
        xml::XmlPullParser xmlParser(in);
        if (parser.parse(&xmlParser)) {
            return ::testing::AssertionSuccess();
        }
        return ::testing::AssertionFailure();
    }
};

TEST_F(AnnotationProcessorTest, EmitsDeprecated) {
    const char* xmlInput = R"EOF(
    <resources>
      <declare-styleable name="foo">
        <!-- Some comment, and it should contain
             a marker word, something that marks
             this resource as nor needed.
             {@deprecated That's the marker! } -->
        <attr name="autoText" format="boolean" />
      </declare-styleable>
    </resources>)EOF";

    ASSERT_TRUE(parse(xmlInput));

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/autoText");
    ASSERT_NE(nullptr, attr);

    AnnotationProcessor processor;
    processor.appendComment(attr->getComment());

    std::stringstream result;
    processor.writeToStream(&result, "");
    std::string annotations = result.str();

    EXPECT_NE(std::string::npos, annotations.find("@Deprecated"));
}

} // namespace aapt


