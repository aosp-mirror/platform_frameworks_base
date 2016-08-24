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

#include "java/AnnotationProcessor.h"
#include "test/Test.h"

namespace aapt {

TEST(AnnotationProcessorTest, EmitsDeprecated) {
    const char* comment = "Some comment, and it should contain a marker word, "
                          "something that marks this resource as nor needed. "
                          "{@deprecated That's the marker! }";

    AnnotationProcessor processor;
    processor.appendComment(comment);

    std::stringstream result;
    processor.writeToStream(&result, "");
    std::string annotations = result.str();

    EXPECT_NE(std::string::npos, annotations.find("@Deprecated"));
}

TEST(AnnotationProcessorTest, EmitsSystemApiAnnotationAndRemovesFromComment) {
    AnnotationProcessor processor;
    processor.appendComment("@SystemApi This is a system API");

    std::stringstream result;
    processor.writeToStream(&result, "");
    std::string annotations = result.str();

    EXPECT_NE(std::string::npos, annotations.find("@android.annotation.SystemApi"));
    EXPECT_EQ(std::string::npos, annotations.find("@SystemApi"));
    EXPECT_NE(std::string::npos, annotations.find("This is a system API"));
}

} // namespace aapt


