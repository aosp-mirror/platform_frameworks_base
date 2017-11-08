/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "text/Printer.h"

#include "io/StringStream.h"
#include "test/Test.h"

using ::aapt::io::StringOutputStream;
using ::android::StringPiece;
using ::testing::StrEq;

namespace aapt {
namespace text {

TEST(PrinterTest, PrintsToStreamWithIndents) {
  std::string result;
  StringOutputStream out(&result);
  Printer printer(&out);

  printer.Print("Hello");
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello"));

  printer.Println();
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n"));

  // This shouldn't print anything yet.
  printer.Indent();
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n"));

  // Now we should see the indent.
  printer.Print("world!");
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n  world!"));

  printer.Println(" What a\nlovely day.");
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n  world! What a\n  lovely day.\n"));

  // This shouldn't print anything yet.
  printer.Undent();
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n  world! What a\n  lovely day.\n"));

  printer.Println("Isn't it?");
  out.Flush();
  EXPECT_THAT(result, StrEq("Hello\n  world! What a\n  lovely day.\nIsn't it?\n"));
}

}  // namespace text
}  // namespace aapt
