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

#include "text/Utf8Iterator.h"

#include "test/Test.h"

using ::android::StringPiece;
using ::testing::Eq;

namespace aapt {
namespace text {

TEST(Utf8IteratorTest, IteratesOverAscii) {
  Utf8Iterator iter("hello");

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'h'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'e'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'l'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'l'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'o'));

  EXPECT_FALSE(iter.HasNext());
}

TEST(Utf8IteratorTest, IteratesOverUnicode) {
  Utf8Iterator iter("Hi there 華勵蓮🍩");
  iter.Skip(9);

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'華'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'勵'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'蓮'));

  ASSERT_TRUE(iter.HasNext());
  EXPECT_THAT(iter.Next(), Eq(U'🍩'));

  EXPECT_FALSE(iter.HasNext());
}

TEST(Utf8IteratorTest, PositionPointsToTheCorrectPlace) {
  const StringPiece expected("Mm🍩");
  Utf8Iterator iter(expected);

  // Before any character, the position should be 0.
  EXPECT_THAT(iter.Position(), Eq(0u));

  // The 'M' character, one byte.
  ASSERT_TRUE(iter.HasNext());
  iter.Next();
  EXPECT_THAT(iter.Position(), Eq(1u));

  // The 'm' character, one byte.
  ASSERT_TRUE(iter.HasNext());
  iter.Next();
  EXPECT_THAT(iter.Position(), Eq(2u));

  // The doughnut character, 4 bytes.
  ASSERT_TRUE(iter.HasNext());
  iter.Next();
  EXPECT_THAT(iter.Position(), Eq(6u));

  // There should be nothing left.
  EXPECT_FALSE(iter.HasNext());
  EXPECT_THAT(iter.Position(), Eq(expected.size()));
}

}  // namespace text
}  // namespace aapt
