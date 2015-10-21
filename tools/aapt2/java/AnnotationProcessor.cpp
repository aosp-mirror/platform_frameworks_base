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
#include "util/Util.h"

#include <algorithm>

namespace aapt {

void AnnotationProcessor::appendCommentLine(const StringPiece16& line) {
    static const std::string sDeprecated = "@deprecated";
    static const std::string sSystemApi = "@SystemApi";

    if (line.empty()) {
        return;
    }

    std::string comment = util::utf16ToUtf8(line);

    if (comment.find(sDeprecated) != std::string::npos && !mDeprecated) {
        mDeprecated = true;
        if (!mAnnotations.empty()) {
            mAnnotations += "\n";
        }
        mAnnotations += mPrefix;
        mAnnotations += "@Deprecated";
    }

    if (comment.find(sSystemApi) != std::string::npos && !mSystemApi) {
        mSystemApi = true;
        if (!mAnnotations.empty()) {
            mAnnotations += "\n";
        }
        mAnnotations += mPrefix;
        mAnnotations += "@android.annotations.SystemApi";
    }

    if (mComment.empty()) {
        mComment += mPrefix;
        mComment += "/**";
    }

    mComment += "\n";
    mComment += mPrefix;
    mComment += " * ";
    mComment += std::move(comment);
}

void AnnotationProcessor::appendComment(const StringPiece16& comment) {
    // We need to process line by line to clean-up whitespace and append prefixes.
    for (StringPiece16 line : util::tokenize(comment, u'\n')) {
        appendCommentLine(util::trimWhitespace(line));
    }
}

std::string AnnotationProcessor::buildComment() {
    mComment += "\n";
    mComment += mPrefix;
    mComment += " */";
    return std::move(mComment);
}

std::string AnnotationProcessor::buildAnnotations() {
    return std::move(mAnnotations);
}

} // namespace aapt
