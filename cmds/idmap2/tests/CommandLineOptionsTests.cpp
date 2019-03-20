/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "android-base/file.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"
#include "androidfw/LoadedArsc.h"

#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

namespace android::idmap2 {

TEST(CommandLineOptionsTests, Flag) {
  bool foo = true;
  bool bar = false;
  CommandLineOptions opts =
      CommandLineOptions("test").OptionalFlag("--foo", "", &foo).OptionalFlag("--bar", "", &bar);

  auto success = opts.Parse({"--foo", "--bar"});
  ASSERT_TRUE(success);
  ASSERT_TRUE(foo);
  ASSERT_TRUE(bar);

  foo = bar = false;
  success = opts.Parse({"--foo"});
  ASSERT_TRUE(success);
  ASSERT_TRUE(foo);
  ASSERT_FALSE(bar);
}

TEST(CommandLineOptionsTests, MandatoryOption) {
  std::string foo;
  std::string bar;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--foo", "", &foo)
                                .MandatoryOption("--bar", "", &bar);
  auto success = opts.Parse({"--foo", "FOO", "--bar", "BAR"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "FOO");
  ASSERT_EQ(bar, "BAR");

  success = opts.Parse({"--foo"});
  ASSERT_FALSE(success);
}

TEST(CommandLineOptionsTests, MandatoryOptionMultipleArgsButExpectedOnce) {
  std::string foo;
  CommandLineOptions opts = CommandLineOptions("test").MandatoryOption("--foo", "", &foo);
  auto success = opts.Parse({"--foo", "FIRST", "--foo", "SECOND"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "SECOND");
}

TEST(CommandLineOptionsTests, MandatoryOptionMultipleArgsAndExpectedOnceOrMore) {
  std::vector<std::string> args;
  CommandLineOptions opts = CommandLineOptions("test").MandatoryOption("--foo", "", &args);
  auto success = opts.Parse({"--foo", "FOO", "--foo", "BAR"});
  ASSERT_TRUE(success);
  ASSERT_EQ(args.size(), 2U);
  ASSERT_EQ(args[0], "FOO");
  ASSERT_EQ(args[1], "BAR");
}

TEST(CommandLineOptionsTests, OptionalOption) {
  std::string foo;
  std::string bar;
  CommandLineOptions opts = CommandLineOptions("test")
                                .OptionalOption("--foo", "", &foo)
                                .OptionalOption("--bar", "", &bar);
  auto success = opts.Parse({"--foo", "FOO", "--bar", "BAR"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "FOO");
  ASSERT_EQ(bar, "BAR");

  success = opts.Parse({"--foo", "BAZ"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo, "BAZ");

  success = opts.Parse({"--foo"});
  ASSERT_FALSE(success);

  success = opts.Parse({"--foo", "--bar", "BAR"});
  ASSERT_FALSE(success);

  success = opts.Parse({"--foo", "FOO", "--bar"});
  ASSERT_FALSE(success);
}

TEST(CommandLineOptionsTests, OptionalOptionList) {
  std::vector<std::string> foo;
  std::vector<std::string> bar;
  CommandLineOptions opts = CommandLineOptions("test")
                                .OptionalOption("--foo", "", &foo)
                                .OptionalOption("--bar", "", &bar);
  auto success = opts.Parse({"--foo", "FOO", "--bar", "BAR"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo.size(), 1U);
  ASSERT_EQ(foo[0], "FOO");
  ASSERT_EQ(bar.size(), 1U);
  ASSERT_EQ(bar[0], "BAR");

  foo.clear();
  bar.clear();
  success = opts.Parse({"--foo", "BAZ"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo.size(), 1U);
  ASSERT_EQ(foo[0], "BAZ");
  ASSERT_EQ(bar.size(), 0U);

  foo.clear();
  bar.clear();
  success = opts.Parse({"--foo", "BAZ", "--foo", "BIZ", "--bar", "FIZ", "--bar", "FUZZ"});
  ASSERT_TRUE(success);
  ASSERT_EQ(foo.size(), 2U);
  ASSERT_EQ(foo[0], "BAZ");
  ASSERT_EQ(foo[1], "BIZ");
  ASSERT_EQ(bar.size(), 2U);
  ASSERT_EQ(bar[0], "FIZ");
  ASSERT_EQ(bar[1], "FUZZ");

  foo.clear();
  bar.clear();
  success = opts.Parse({"--foo"});
  ASSERT_FALSE(success);

  foo.clear();
  bar.clear();
  success = opts.Parse({"--foo", "--bar", "BAR"});
  ASSERT_FALSE(success);

  foo.clear();
  bar.clear();
  success = opts.Parse({"--foo", "FOO", "--bar"});
  ASSERT_FALSE(success);
}

TEST(CommandLineOptionsTests, CornerCases) {
  std::string foo;
  std::string bar;
  bool baz = false;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--foo", "", &foo)
                                .OptionalFlag("--baz", "", &baz)
                                .OptionalOption("--bar", "", &bar);
  auto success = opts.Parse({"--unexpected"});
  ASSERT_FALSE(success);

  success = opts.Parse({"--bar", "BAR"});
  ASSERT_FALSE(success);

  success = opts.Parse({"--baz", "--foo", "FOO"});
  ASSERT_TRUE(success);
  ASSERT_TRUE(baz);
  ASSERT_EQ(foo, "FOO");
}

TEST(CommandLineOptionsTests, ConvertArgvToVector) {
  const char* argv[] = {
      "program-name",
      "--foo",
      "FOO",
      nullptr,
  };
  std::unique_ptr<std::vector<std::string>> v = CommandLineOptions::ConvertArgvToVector(3, argv);
  ASSERT_EQ(v->size(), 2UL);
  ASSERT_EQ((*v)[0], "--foo");
  ASSERT_EQ((*v)[1], "FOO");
}

TEST(CommandLineOptionsTests, ConvertArgvToVectorNoArgs) {
  const char* argv[] = {
      "program-name",
      nullptr,
  };
  std::unique_ptr<std::vector<std::string>> v = CommandLineOptions::ConvertArgvToVector(1, argv);
  ASSERT_EQ(v->size(), 0UL);
}

TEST(CommandLineOptionsTests, Usage) {
  std::string arg1;
  std::string arg2;
  std::string arg3;
  std::string arg4;
  bool arg5 = false;
  bool arg6 = false;
  std::vector<std::string> arg7;
  std::vector<std::string> arg8;
  CommandLineOptions opts = CommandLineOptions("test")
                                .MandatoryOption("--aa", "description-aa", &arg1)
                                .OptionalFlag("--bb", "description-bb", &arg5)
                                .OptionalOption("--cc", "description-cc", &arg2)
                                .OptionalOption("--dd", "description-dd", &arg3)
                                .MandatoryOption("--ee", "description-ee", &arg4)
                                .OptionalFlag("--ff", "description-ff", &arg6)
                                .MandatoryOption("--gg", "description-gg", &arg7)
                                .OptionalOption("--hh", "description-hh", &arg8);
  std::stringstream stream;
  opts.Usage(stream);
  const std::string s = stream.str();
  ASSERT_NE(s.find("usage: test --aa arg [--bb] [--cc arg] [--dd arg] --ee arg [--ff] --gg arg "
                   "[--gg arg [..]] [--hh arg [..]]"),
            std::string::npos);
  ASSERT_NE(s.find("--aa arg    description-aa"), std::string::npos);
  ASSERT_NE(s.find("--ff        description-ff"), std::string::npos);
  ASSERT_NE(s.find("--gg arg    description-gg (can be provided multiple times)"),
            std::string::npos);
}

}  // namespace android::idmap2
