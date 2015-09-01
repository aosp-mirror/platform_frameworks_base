/*
 * Copyright (C) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <string>
#include <vector>

#include <gtest/gtest.h>

#include "options.h"

using std::vector;
using std::string;

const char kPreprocessCommandOutputFile[] = "output_file_name";
const char kPreprocessCommandInput1[] = "input1";
const char kPreprocessCommandInput2[] = "input2";
const char kPreprocessCommandInput3[] = "input3";
const char* kPreprocessCommand[] = {
    "aidl", "--preprocess",
    kPreprocessCommandOutputFile,
    kPreprocessCommandInput1,
    kPreprocessCommandInput2,
    kPreprocessCommandInput3,
};

TEST(OptionsTests, ParsesPreprocess) {
  Options options;
  const int argc = sizeof(kPreprocessCommand) / sizeof(*kPreprocessCommand);
  EXPECT_EQ(parse_options(argc, kPreprocessCommand, &options), 0);
  EXPECT_EQ(options.task, PREPROCESS_AIDL);
  EXPECT_EQ(options.failOnParcelable, false);
  EXPECT_EQ(options.importPaths.size(), 0u);
  EXPECT_EQ(options.preprocessedFiles.size(), 0u);
  EXPECT_EQ(options.inputFileName, string{""});
  EXPECT_EQ(options.outputFileName, string{kPreprocessCommandOutputFile});
  EXPECT_EQ(options.autoDepFile, false);
  const vector<string> expected_input{kPreprocessCommandInput1,
                                      kPreprocessCommandInput2,
                                      kPreprocessCommandInput3};
  EXPECT_EQ(options.filesToPreprocess, expected_input);
}
