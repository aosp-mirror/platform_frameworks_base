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

#include "text/Unicode.h"

#include <algorithm>
#include <array>

#include "text/Utf8Iterator.h"

using ::android::StringPiece;

namespace aapt {
namespace text {

namespace {

struct CharacterProperties {
  enum : uint32_t {
    kXidStart = 1 << 0,
    kXidContinue = 1 << 1,
  };

  char32_t first_char;
  char32_t last_char;
  uint32_t properties;
};

// Incude the generated data table.
#include "text/Unicode_data.cpp"

bool CompareCharacterProperties(const CharacterProperties& a, char32_t codepoint) {
  return a.last_char < codepoint;
}

uint32_t FindCharacterProperties(char32_t codepoint) {
  const auto iter_end = sCharacterProperties.end();
  const auto iter = std::lower_bound(sCharacterProperties.begin(), iter_end, codepoint,
                                     CompareCharacterProperties);
  if (iter != iter_end && codepoint >= iter->first_char) {
    return iter->properties;
  }
  return 0u;
}

}  // namespace

bool IsXidStart(char32_t codepoint) {
  return FindCharacterProperties(codepoint) & CharacterProperties::kXidStart;
}

bool IsXidContinue(char32_t codepoint) {
  return FindCharacterProperties(codepoint) & CharacterProperties::kXidContinue;
}

// Hardcode the White_Space characters since they are few and the external/icu project doesn't
// list them as data files to parse.
// Sourced from http://www.unicode.org/Public/UCD/latest/ucd/PropList.txt
bool IsWhitespace(char32_t codepoint) {
  return (codepoint >= 0x0009 && codepoint <= 0x000d) || (codepoint == 0x0020) ||
         (codepoint == 0x0085) || (codepoint == 0x00a0) || (codepoint == 0x1680) ||
         (codepoint >= 0x2000 && codepoint <= 0x200a) || (codepoint == 0x2028) ||
         (codepoint == 0x2029) || (codepoint == 0x202f) || (codepoint == 0x205f) ||
         (codepoint == 0x3000);
}

bool IsJavaIdentifier(const StringPiece& str) {
  Utf8Iterator iter(str);

  // Check the first character.
  if (!iter.HasNext()) {
    return false;
  }

  const char32_t first_codepoint = iter.Next();
  if (!IsXidStart(first_codepoint) && first_codepoint != U'_' && first_codepoint != U'$') {
    return false;
  }

  while (iter.HasNext()) {
    const char32_t codepoint = iter.Next();
    if (!IsXidContinue(codepoint) && codepoint != U'$') {
      return false;
    }
  }
  return true;
}

bool IsValidResourceEntryName(const StringPiece& str) {
  Utf8Iterator iter(str);

  // Check the first character.
  if (!iter.HasNext()) {
    return false;
  }

  // Resources are allowed to start with '_'
  const char32_t first_codepoint = iter.Next();
  if (!IsXidStart(first_codepoint) && first_codepoint != U'_') {
    return false;
  }

  while (iter.HasNext()) {
    const char32_t codepoint = iter.Next();
    if (!IsXidContinue(codepoint) && codepoint != U'.' && codepoint != U'-') {
      return false;
    }
  }
  return true;
}

}  // namespace text
}  // namespace aapt
