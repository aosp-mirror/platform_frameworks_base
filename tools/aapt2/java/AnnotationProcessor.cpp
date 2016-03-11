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

void AnnotationProcessor::appendCommentLine(const std::string& comment) {
    static const std::string sDeprecated = "@deprecated";
    static const std::string sSystemApi = "@SystemApi";

    if (comment.find(sDeprecated) != std::string::npos) {
        mAnnotationBitMask |= kDeprecated;
    }

    if (comment.find(sSystemApi) != std::string::npos) {
        mAnnotationBitMask |= kSystemApi;
    }

    if (!mHasComments) {
        mHasComments = true;
        mComment << "/**";
    }

    mComment << "\n * " << std::move(comment);
}

void AnnotationProcessor::appendComment(const StringPiece16& comment) {
    // We need to process line by line to clean-up whitespace and append prefixes.
    for (StringPiece16 line : util::tokenize(comment, u'\n')) {
        line = util::trimWhitespace(line);
        if (!line.empty()) {
            appendCommentLine(util::utf16ToUtf8(line));
        }
    }
}

void AnnotationProcessor::appendComment(const StringPiece& comment) {
    for (StringPiece line : util::tokenize(comment, '\n')) {
        line = util::trimWhitespace(line);
        if (!line.empty()) {
            appendCommentLine(line.toString());
        }
    }
}

void AnnotationProcessor::appendNewLine() {
    mComment << "\n *";
}

void AnnotationProcessor::writeToStream(std::ostream* out, const StringPiece& prefix) {
    if (mHasComments) {
        std::string result = mComment.str();
        for (StringPiece line : util::tokenize<char>(result, '\n')) {
           *out << prefix << line << "\n";
        }
        *out << prefix << " */" << "\n";
    }

    if (mAnnotationBitMask & kDeprecated) {
        *out << prefix << "@Deprecated\n";
    }

    if (mAnnotationBitMask & kSystemApi) {
        *out << prefix << "@android.annotation.SystemApi\n";
    }
}

} // namespace aapt
