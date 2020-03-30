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

#include <algorithm>
#include <array>
#include <regex>

#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
#include "util/Util.h"

using ::aapt::text::Printer;
using ::aapt::text::Utf8Iterator;
using ::android::StringPiece;

namespace aapt {

StringPiece AnnotationProcessor::ExtractFirstSentence(const StringPiece& comment) {
  Utf8Iterator iter(comment);
  while (iter.HasNext()) {
    const char32_t codepoint = iter.Next();
    if (codepoint == U'.') {
      const size_t current_position = iter.Position();
      if (!iter.HasNext() || text::IsWhitespace(iter.Next())) {
        return comment.substr(0, current_position);
      }
    }
  }
  return comment;
}

struct AnnotationRule {
  enum : uint32_t {
    kDeprecated = 0x01,
    kSystemApi = 0x02,
    kTestApi = 0x04,
  };

  StringPiece doc_str;
  uint32_t bit_mask;
  StringPiece annotation;
};

static std::array<AnnotationRule, 2> sAnnotationRules = {{
    {"@SystemApi", AnnotationRule::kSystemApi, "@android.annotation.SystemApi"},
    {"@TestApi", AnnotationRule::kTestApi, "@android.annotation.TestApi"},
}};

void AnnotationProcessor::AppendCommentLine(std::string comment) {
  static const std::string sDeprecated = "@deprecated";

  // Treat deprecated specially, since we don't remove it from the source comment.
  if (comment.find(sDeprecated) != std::string::npos) {
    annotation_parameter_map_[AnnotationRule::kDeprecated] = "";
  }

  for (const AnnotationRule& rule : sAnnotationRules) {
    std::string::size_type idx = comment.find(rule.doc_str.data());
    if (idx != std::string::npos) {
      // Captures all parameters associated with the specified annotation rule
      // by matching the first pair of parantheses after the rule.
      std::regex re(rule.doc_str.to_string() + "\\s*\\((.+)\\)");
      std::smatch match_result;
      const bool is_match = std::regex_search(comment, match_result, re);
      // We currently only capture and preserve parameters for SystemApi.
      if (is_match && rule.bit_mask == AnnotationRule::kSystemApi) {
        annotation_parameter_map_[rule.bit_mask] = match_result[1].str();
        comment.erase(comment.begin() + match_result.position(),
                      comment.begin() + match_result.position() + match_result.length());
      } else {
        annotation_parameter_map_[rule.bit_mask] = "";
        comment.erase(comment.begin() + idx, comment.begin() + idx + rule.doc_str.size());
      }
    }
  }

  // Check if after removal of annotations the line is empty.
  const StringPiece trimmed = util::TrimWhitespace(comment);
  if (trimmed.empty()) {
    return;
  }

  // If there was trimming to do, copy the string.
  if (trimmed.size() != comment.size()) {
    comment = trimmed.to_string();
  }

  if (!has_comments_) {
    has_comments_ = true;
    comment_ << "/**";
  }
  comment_ << "\n * " << std::move(comment);
}

void AnnotationProcessor::AppendComment(const StringPiece& comment) {
  // We need to process line by line to clean-up whitespace and append prefixes.
  for (StringPiece line : util::Tokenize(comment, '\n')) {
    line = util::TrimWhitespace(line);
    if (!line.empty()) {
      AppendCommentLine(line.to_string());
    }
  }
}

void AnnotationProcessor::AppendNewLine() {
  if (has_comments_) {
    comment_ << "\n *";
  }
}

void AnnotationProcessor::Print(Printer* printer) const {
  if (has_comments_) {
    std::string result = comment_.str();
    for (const StringPiece& line : util::Tokenize(result, '\n')) {
      printer->Println(line);
    }
    printer->Println(" */");
  }

  if (annotation_parameter_map_.find(AnnotationRule::kDeprecated) !=
        annotation_parameter_map_.end()) {
    printer->Println("@Deprecated");
  }

  for (const AnnotationRule& rule : sAnnotationRules) {
    const auto& it = annotation_parameter_map_.find(rule.bit_mask);
    if (it != annotation_parameter_map_.end()) {
      printer->Print(rule.annotation);
      if (!it->second.empty()) {
        printer->Print("(").Print(it->second).Print(")");
      }
      printer->Print("\n");
    }
  }
}

}  // namespace aapt
