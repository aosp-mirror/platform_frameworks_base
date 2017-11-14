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

#ifndef AAPT_TEXT_UNICODE_H
#define AAPT_TEXT_UNICODE_H

#include "androidfw/StringPiece.h"

namespace aapt {
namespace text {

// Returns true if the Unicode codepoint has the XID_Start property, meaning it can be used as the
// first character of a programming language identifier.
// http://unicode.org/reports/tr31/#Default_Identifier_Syntax
//
// XID_Start is a Unicode Derived Core Property. It is a variation of the ID_Start
// Derived Core Property, accounting for a few characters that, when normalized, yield valid
// characters in the ID_Start set.
bool IsXidStart(char32_t codepoint);

// Returns true if the Unicode codepoint has the XID_Continue property, meaning it can be used in
// any position of a programming language identifier, except the first.
// http://unicode.org/reports/tr31/#Default_Identifier_Syntax
//
// XID_Continue is a Unicode Derived Core Property. It is a variation of the ID_Continue
// Derived Core Property, accounting for a few characters that, when normalized, yield valid
// characters in the ID_Continue set.
bool IsXidContinue(char32_t codepoint);

// Returns true if the Unicode codepoint has the White_Space property.
// http://unicode.org/reports/tr44/#White_Space
bool IsWhitespace(char32_t codepoint);

// Returns true if the UTF8 string can be used as a Java identifier.
// NOTE: This does not check against the set of reserved Java keywords.
bool IsJavaIdentifier(const android::StringPiece& str);

// Returns true if the UTF8 string can be used as the entry name of a resource name.
// This is the `entry` part of package:type/entry.
bool IsValidResourceEntryName(const android::StringPiece& str);

}  // namespace text
}  // namespace aapt

#endif  // AAPT_TEXT_UNICODE_H
