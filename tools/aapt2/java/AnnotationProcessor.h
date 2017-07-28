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

#ifndef AAPT_JAVA_ANNOTATIONPROCESSOR_H
#define AAPT_JAVA_ANNOTATIONPROCESSOR_H

#include <sstream>
#include <string>

#include "androidfw/StringPiece.h"

namespace aapt {

/**
 * Builds a JavaDoc comment from a set of XML comments.
 * This will also look for instances of @SystemApi and convert them to
 * actual Java annotations.
 *
 * Example:
 *
 * Input XML:
 *
 * <!-- This is meant to be hidden because
 *      It is system api. Also it is @deprecated
 *      @SystemApi
 *      -->
 *
 * Output JavaDoc:
 *
 *  /\*
 *   * This is meant to be hidden because
 *   * It is system api. Also it is @deprecated
 *   *\/
 *
 * Output Annotations:
 *
 * @Deprecated
 * @android.annotation.SystemApi
 *
 */
class AnnotationProcessor {
 public:
  static android::StringPiece ExtractFirstSentence(const android::StringPiece& comment);

  /**
   * Adds more comments. Since resources can have various values with different
   * configurations,
   * we need to collect all the comments.
   */
  void AppendComment(const android::StringPiece& comment);

  void AppendNewLine();

  /**
   * Writes the comments and annotations to the stream, with the given prefix
   * before each line.
   */
  void WriteToStream(std::ostream* out, const android::StringPiece& prefix) const;

 private:
  enum : uint32_t {
    kDeprecated = 0x01,
    kSystemApi = 0x02,
  };

  std::stringstream comment_;
  std::stringstream mAnnotations;
  bool has_comments_ = false;
  uint32_t annotation_bit_mask_ = 0;

  void AppendCommentLine(std::string& line);
};

}  // namespace aapt

#endif /* AAPT_JAVA_ANNOTATIONPROCESSOR_H */
