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

#include "util/StringPiece.h"

#include <string>

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
 *   * @SystemApi
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
    /**
     * Creates an AnnotationProcessor with a given prefix for each line generated.
     * This is usually a set of spaces for indentation.
     */
    AnnotationProcessor(const StringPiece& prefix) : mPrefix(prefix.toString()) {
    }

    /**
     * Adds more comments. Since resources can have various values with different configurations,
     * we need to collect all the comments.
     */
    void appendComment(const StringPiece16& comment);
    void appendComment(const StringPiece& comment);

    /**
     * Finishes the comment and moves it to the caller. Subsequent calls to buildComment() have
     * undefined results.
     */
    std::string buildComment();

    /**
     * Finishes the annotation and moves it to the caller. Subsequent calls to buildAnnotations()
     * have undefined results.
     */
    std::string buildAnnotations();

private:
    std::string mPrefix;
    std::string mComment;
    std::string mAnnotations;
    bool mDeprecated = false;
    bool mSystemApi = false;

    void appendCommentLine(const std::string& line);
};

} // namespace aapt

#endif /* AAPT_JAVA_ANNOTATIONPROCESSOR_H */
