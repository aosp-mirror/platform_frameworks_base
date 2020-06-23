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

#include "io/StringStream.h"
#include "test/Test.h"
#include "text/Printer.h"

using ::aapt::io::StringOutputStream;
using ::aapt::text::Printer;
using ::testing::Eq;
using ::testing::HasSubstr;
using ::testing::Not;

namespace aapt {

TEST(AnnotationProcessorTest, EmitsDeprecated) {
  const char* comment =
      "Some comment, and it should contain a marker word, "
      "something that marks this resource as nor needed. "
      "{@deprecated That's the marker! }";

  AnnotationProcessor processor;
  processor.AppendComment(comment);

  std::string annotations;
  StringOutputStream out(&annotations);
  Printer printer(&out);
  processor.Print(&printer);
  out.Flush();

  EXPECT_THAT(annotations, HasSubstr("@Deprecated"));
}

TEST(AnnotationProcessorTest, EmitsSystemApiAnnotationAndRemovesFromComment) {
  AnnotationProcessor processor;
  processor.AppendComment("@SystemApi This is a system API");

  std::string annotations;
  StringOutputStream out(&annotations);
  Printer printer(&out);
  processor.Print(&printer);
  out.Flush();

  EXPECT_THAT(annotations, HasSubstr("@android.annotation.SystemApi"));
  EXPECT_THAT(annotations, Not(HasSubstr("@SystemApi")));
  EXPECT_THAT(annotations, HasSubstr("This is a system API"));
}

TEST(AnnotationProcessorTest, EmitsSystemApiAnnotationParamsAndRemovesFromComment) {
  AnnotationProcessor processor;
  processor.AppendComment("@SystemApi (p1=k1,p2=k2) This is a system API");

  std::string annotations;
  StringOutputStream out(&annotations);
  Printer printer(&out);
  processor.Print(&printer);
  out.Flush();

  EXPECT_THAT(annotations, HasSubstr("@android.annotation.SystemApi(p1=k1,p2=k2)"));
  EXPECT_THAT(annotations, Not(HasSubstr("@SystemApi")));
  EXPECT_THAT(annotations, HasSubstr("This is a system API"));
}

TEST(AnnotationProcessorTest, EmitsTestApiAnnotationAndRemovesFromComment) {
  AnnotationProcessor processor;
  processor.AppendComment("@TestApi This is a test API");

  std::string annotations;
  StringOutputStream out(&annotations);
  Printer printer(&out);
  processor.Print(&printer);
  out.Flush();

  EXPECT_THAT(annotations, HasSubstr("@android.annotation.TestApi"));
  EXPECT_THAT(annotations, Not(HasSubstr("@TestApi")));
  EXPECT_THAT(annotations, HasSubstr("This is a test API"));
}

TEST(AnnotationProcessorTest, NotEmitSystemApiAnnotation) {
  AnnotationProcessor processor;
  processor.AppendComment("@SystemApi This is a system API");

  std::string annotations;
  StringOutputStream out(&annotations);
  Printer printer(&out);
  processor.Print(&printer, true /* strip_api_annotations */);
  out.Flush();

  EXPECT_THAT(annotations, Not(HasSubstr("@android.annotation.SystemApi")));
  EXPECT_THAT(annotations, Not(HasSubstr("@SystemApi")));
  EXPECT_THAT(annotations, HasSubstr("This is a system API"));
}

TEST(AnnotationProcessor, ExtractsFirstSentence) {
  EXPECT_THAT(AnnotationProcessor::ExtractFirstSentence("This is the only sentence"),
              Eq("This is the only sentence"));
  EXPECT_THAT(AnnotationProcessor::ExtractFirstSentence(
                  "This is the\n  first sentence.  This is the rest of the paragraph."),
              Eq("This is the\n  first sentence."));
  EXPECT_THAT(AnnotationProcessor::ExtractFirstSentence(
                  "This is the first sentence with a {@link android.R.styleable.Theme}."),
              Eq("This is the first sentence with a {@link android.R.styleable.Theme}."));
}

}  // namespace aapt
