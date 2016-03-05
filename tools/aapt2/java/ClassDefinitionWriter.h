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

#ifndef AAPT_JAVA_CLASSDEFINITION_H
#define AAPT_JAVA_CLASSDEFINITION_H

#include "Resource.h"
#include "java/AnnotationProcessor.h"
#include "util/StringPiece.h"
#include "util/Util.h"

#include <sstream>
#include <string>

namespace aapt {

struct ClassDefinitionWriterOptions {
    bool useFinalQualifier = false;
    bool forceCreationIfEmpty = false;
};

/**
 * Writes a class for use in R.java or Manifest.java.
 */
class ClassDefinitionWriter {
public:
    ClassDefinitionWriter(const StringPiece& name, const ClassDefinitionWriterOptions& options) :
            mName(name.toString()), mOptions(options), mStarted(false) {
    }

    ClassDefinitionWriter(const StringPiece16& name, const ClassDefinitionWriterOptions& options) :
            mName(util::utf16ToUtf8(name)), mOptions(options), mStarted(false) {
    }

    void addIntMember(const StringPiece& name, AnnotationProcessor* processor,
                      const uint32_t val) {
        ensureClassDeclaration();
        if (processor) {
            processor->writeToStream(&mOut, kIndent);
        }
        mOut << kIndent << "public static " << (mOptions.useFinalQualifier ? "final " : "")
             << "int " << name << "=" << val << ";\n";
    }

    void addStringMember(const StringPiece16& name, AnnotationProcessor* processor,
                         const StringPiece16& val) {
        ensureClassDeclaration();
        if (processor) {
            processor->writeToStream(&mOut, kIndent);
        }
        mOut << kIndent << "public static " << (mOptions.useFinalQualifier ? "final " : "")
             << "String " << name << "=\"" << val << "\";\n";
    }

    void addResourceMember(const StringPiece& name, AnnotationProcessor* processor,
                           const ResourceId id) {
        ensureClassDeclaration();
        if (processor) {
            processor->writeToStream(&mOut, kIndent);
        }
        mOut << kIndent << "public static " << (mOptions.useFinalQualifier ? "final " : "")
             << "int " << name << "=" << id <<";\n";
    }

    template <typename Iterator, typename FieldAccessorFunc>
    void addArrayMember(const StringPiece& name, AnnotationProcessor* processor,
                        const Iterator begin, const Iterator end, FieldAccessorFunc f) {
        ensureClassDeclaration();
        if (processor) {
            processor->writeToStream(&mOut, kIndent);
        }
        mOut << kIndent << "public static final int[] " << name << "={";

        for (Iterator current = begin; current != end; ++current) {
            if (std::distance(begin, current) % kAttribsPerLine == 0) {
                mOut << "\n" << kIndent << kIndent;
            }

            mOut << f(*current);
            if (std::distance(current, end) > 1) {
                mOut << ", ";
            }
        }
        mOut << "\n" << kIndent <<"};\n";
    }

    void writeToStream(std::ostream* out, const StringPiece& prefix,
                       AnnotationProcessor* processor=nullptr) {
        if (mOptions.forceCreationIfEmpty) {
            ensureClassDeclaration();
        }

        if (!mStarted) {
            return;
        }

        if (processor) {
            processor->writeToStream(out, prefix);
        }

        std::string result = mOut.str();
        for (StringPiece line : util::tokenize<char>(result, '\n')) {
            *out << prefix << line << "\n";
        }
        *out << prefix << "}\n";
    }

private:
    constexpr static const char* kIndent = "  ";

    // The number of attributes to emit per line in a Styleable array.
    constexpr static size_t kAttribsPerLine = 4;

    void ensureClassDeclaration() {
        if (!mStarted) {
            mStarted = true;
            mOut << "public static final class " << mName << " {\n";
        }
    }

    std::stringstream mOut;
    std::string mName;
    ClassDefinitionWriterOptions mOptions;
    bool mStarted;
};

} // namespace aapt

#endif /* AAPT_JAVA_CLASSDEFINITION_H */
