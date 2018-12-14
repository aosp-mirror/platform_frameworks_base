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
#ifndef TINYXML_LAYOUT_PARSER_H_
#define TINYXML_LAYOUT_PARSER_H_

#include "tinyxml2.h"

#include <codecvt>
#include <locale>
#include <string>

namespace startop {

template <typename Visitor>
class TinyXmlVisitorAdapter : public tinyxml2::XMLVisitor {
 public:
  explicit TinyXmlVisitorAdapter(Visitor* visitor) : visitor_{visitor} {}

  bool VisitEnter(const tinyxml2::XMLDocument& /*doc*/) override {
    visitor_->VisitStartDocument();
    return true;
  }

  bool VisitExit(const tinyxml2::XMLDocument& /*doc*/) override {
    visitor_->VisitEndDocument();
    return true;
  }

  bool VisitEnter(const tinyxml2::XMLElement& element,
                  const tinyxml2::XMLAttribute* /*firstAttribute*/) override {
    visitor_->VisitStartTag(
        std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t>{}.from_bytes(
            element.Name()));
    return true;
  }

  bool VisitExit(const tinyxml2::XMLElement& /*element*/) override {
    visitor_->VisitEndTag();
    return true;
  }

 private:
  Visitor* visitor_;
};

// Returns whether a layout resource represented by a TinyXML document is supported by the layout
// compiler.
bool CanCompileLayout(const tinyxml2::XMLDocument& xml, std::string* message = nullptr);

}  // namespace startop

#endif  // TINYXML_LAYOUT_PARSER_H_
