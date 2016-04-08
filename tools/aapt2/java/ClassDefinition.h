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

#include <android-base/macros.h>
#include <sstream>
#include <string>

namespace aapt {

// The number of attributes to emit per line in a Styleable array.
constexpr static size_t kAttribsPerLine = 4;
constexpr static const char* kIndent = "  ";

class ClassMember {
public:
    virtual ~ClassMember() = default;

    AnnotationProcessor* getCommentBuilder() {
        return &mProcessor;
    }

    virtual bool empty() const = 0;

    virtual void writeToStream(const StringPiece& prefix, bool final, std::ostream* out) const {
        mProcessor.writeToStream(out, prefix);
    }

private:
    AnnotationProcessor mProcessor;
};

template <typename T>
class PrimitiveMember : public ClassMember {
public:
    PrimitiveMember(const StringPiece& name, const T& val) :
            mName(name.toString()), mVal(val) {
    }

    bool empty() const override {
        return false;
    }

    void writeToStream(const StringPiece& prefix, bool final, std::ostream* out) const override {
        ClassMember::writeToStream(prefix, final, out);

        *out << prefix << "public static " << (final ? "final " : "")
             << "int " << mName << "=" << mVal << ";";
    }

private:
    std::string mName;
    T mVal;

    DISALLOW_COPY_AND_ASSIGN(PrimitiveMember);
};

/**
 * Specialization for strings so they get the right type and are quoted with "".
 */
template <>
class PrimitiveMember<std::string> : public ClassMember {
public:
    PrimitiveMember(const StringPiece& name, const std::string& val) :
            mName(name.toString()), mVal(val) {
    }

    bool empty() const override {
        return false;
    }

    void writeToStream(const StringPiece& prefix, bool final, std::ostream* out) const override {
        ClassMember::writeToStream(prefix, final, out);

        *out << prefix << "public static " << (final ? "final " : "")
             << "String " << mName << "=\"" << mVal << "\";";
    }

private:
    std::string mName;
    std::string mVal;

    DISALLOW_COPY_AND_ASSIGN(PrimitiveMember);
};

using IntMember = PrimitiveMember<uint32_t>;
using ResourceMember = PrimitiveMember<ResourceId>;
using StringMember = PrimitiveMember<std::string>;

template <typename T>
class PrimitiveArrayMember : public ClassMember {
public:
    PrimitiveArrayMember(const StringPiece& name) :
            mName(name.toString()) {
    }

    void addElement(const T& val) {
        mElements.push_back(val);
    }

    bool empty() const override {
        return false;
    }

    void writeToStream(const StringPiece& prefix, bool final, std::ostream* out) const override {
        ClassMember::writeToStream(prefix, final, out);

        *out << prefix << "public static final int[] " << mName << "={";

        const auto begin = mElements.begin();
        const auto end = mElements.end();
        for (auto current = begin; current != end; ++current) {
            if (std::distance(begin, current) % kAttribsPerLine == 0) {
                *out << "\n" << prefix << kIndent << kIndent;
            }

            *out << *current;
            if (std::distance(current, end) > 1) {
                *out << ", ";
            }
        }
        *out << "\n" << prefix << kIndent <<"};";
    }

private:
    std::string mName;
    std::vector<T> mElements;

    DISALLOW_COPY_AND_ASSIGN(PrimitiveArrayMember);
};

using ResourceArrayMember = PrimitiveArrayMember<ResourceId>;

enum class ClassQualifier {
    None,
    Static
};

class ClassDefinition : public ClassMember {
public:
    static bool writeJavaFile(const ClassDefinition* def,
                              const StringPiece& package,
                              bool final,
                              std::ostream* out);

    ClassDefinition(const StringPiece& name, ClassQualifier qualifier, bool createIfEmpty) :
            mName(name.toString()), mQualifier(qualifier), mCreateIfEmpty(createIfEmpty) {
    }

    void addMember(std::unique_ptr<ClassMember> member) {
        mMembers.push_back(std::move(member));
    }

    bool empty() const override;
    void writeToStream(const StringPiece& prefix, bool final, std::ostream* out) const override;

private:
    std::string mName;
    ClassQualifier mQualifier;
    bool mCreateIfEmpty;
    std::vector<std::unique_ptr<ClassMember>> mMembers;

    DISALLOW_COPY_AND_ASSIGN(ClassDefinition);
};

} // namespace aapt

#endif /* AAPT_JAVA_CLASSDEFINITION_H */
