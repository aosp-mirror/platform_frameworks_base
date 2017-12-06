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

#include "text/Unicode.h"
#include "text/Utf8Iterator.h"
#include "util/Util.h"

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

void AnnotationProcessor::AppendCommentLine(std::string& comment) {
  static const std::string sDeprecated = "@deprecated";
  static const std::string sSystemApi = "@SystemApi";

  if (comment.find(sDeprecated) != std::string::npos) {
    annotation_bit_mask_ |= kDeprecated;
  }

  std::string::size_type idx = comment.find(sSystemApi);
  if (idx != std::string::npos) {
    annotation_bit_mask_ |= kSystemApi;
    comment.erase(comment.begin() + idx,
                  comment.begin() + idx + sSystemApi.size());
  }

  if (util::TrimWhitespace(comment).empty()) {
    return;
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
      std::string lineCopy = line.to_string();
      AppendCommentLine(lineCopy);
    }
  }
}

void AnnotationProcessor::AppendNewLine() { comment_ << "\n *"; }

void AnnotationProcessor::WriteToStream(std::ostream* out,
                                        const StringPiece& prefix) const {
  if (has_comments_) {
    std::string result = comment_.str();
    for (StringPiece line : util::Tokenize(result, '\n')) {
      *out << prefix << line << "\n";
    }
    *out << prefix << " */"
         << "\n";
  }

  if (annotation_bit_mask_ & kDeprecated) {
    *out << prefix << "@Deprecated\n";
  }

  if (annotation_bit_mask_ & kSystemApi) {
    *out << prefix << "@android.annotation.SystemApi\n";
  }
}

}  // namespace aapt
