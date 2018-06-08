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

#include <string>
#include <unordered_map>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

#include "Resource.h"
#include "java/AnnotationProcessor.h"
#include "text/Printer.h"
#include "util/Util.h"

namespace aapt {

// The number of attributes to emit per line in a Styleable array.
constexpr static size_t kAttribsPerLine = 4;
constexpr static const char* kIndent = "  ";

class ClassMember {
 public:
  virtual ~ClassMember() = default;

  AnnotationProcessor* GetCommentBuilder() {
    return &processor_;
  }

  virtual bool empty() const = 0;

  virtual const std::string& GetName() const = 0;

  // Writes the class member to the Printer. Subclasses should derive this method
  // to write their own data. Call this base method from the subclass to write out
  // this member's comments/annotations.
  virtual void Print(bool final, text::Printer* printer) const;

 private:
  AnnotationProcessor processor_;
};

template <typename T>
class PrimitiveMember : public ClassMember {
 public:
  PrimitiveMember(const android::StringPiece& name, const T& val)
      : name_(name.to_string()), val_(val) {}

  bool empty() const override {
    return false;
  }

  const std::string& GetName() const override {
    return name_;
  }

  void Print(bool final, text::Printer* printer) const override {
    using std::to_string;

    ClassMember::Print(final, printer);

    printer->Print("public static ");
    if (final) {
      printer->Print("final ");
    }
    printer->Print("int ").Print(name_).Print("=").Print(to_string(val_)).Print(";");
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PrimitiveMember);

  std::string name_;
  T val_;
};

// Specialization for strings so they get the right type and are quoted with "".
template <>
class PrimitiveMember<std::string> : public ClassMember {
 public:
  PrimitiveMember(const android::StringPiece& name, const std::string& val)
      : name_(name.to_string()), val_(val) {}

  bool empty() const override {
    return false;
  }

  const std::string& GetName() const override {
    return name_;
  }

  void Print(bool final, text::Printer* printer) const override {
    ClassMember::Print(final, printer);

    printer->Print("public static ");
    if (final) {
      printer->Print("final ");
    }
    printer->Print("String ").Print(name_).Print("=\"").Print(val_).Print("\";");
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PrimitiveMember);

  std::string name_;
  std::string val_;
};

using IntMember = PrimitiveMember<uint32_t>;
using ResourceMember = PrimitiveMember<ResourceId>;
using StringMember = PrimitiveMember<std::string>;

template <typename T>
class PrimitiveArrayMember : public ClassMember {
 public:
  explicit PrimitiveArrayMember(const android::StringPiece& name) : name_(name.to_string()) {}

  void AddElement(const T& val) {
    elements_.push_back(val);
  }

  bool empty() const override {
    return false;
  }

  const std::string& GetName() const override {
    return name_;
  }

  void Print(bool final, text::Printer* printer) const override {
    ClassMember::Print(final, printer);

    printer->Print("public static final int[] ").Print(name_).Print("={");
    printer->Indent();

    const auto begin = elements_.begin();
    const auto end = elements_.end();
    for (auto current = begin; current != end; ++current) {
      if (std::distance(begin, current) % kAttribsPerLine == 0) {
        printer->Println();
      }

      printer->Print(to_string(*current));
      if (std::distance(current, end) > 1) {
        printer->Print(", ");
      }
    }
    printer->Println();
    printer->Undent();
    printer->Print("};");
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PrimitiveArrayMember);

  std::string name_;
  std::vector<T> elements_;
};

using ResourceArrayMember = PrimitiveArrayMember<ResourceId>;

// Represents a method in a class.
class MethodDefinition : public ClassMember {
 public:
  // Expected method signature example: 'public static void onResourcesLoaded(int p)'.
  explicit MethodDefinition(const android::StringPiece& signature)
      : signature_(signature.to_string()) {}

  // Appends a single statement to the method. It should include no newlines or else
  // formatting may be broken.
  void AppendStatement(const android::StringPiece& statement);

  // Not quite the same as a name, but good enough.
  const std::string& GetName() const override {
    return signature_;
  }

  // Even if the method is empty, we always want to write the method signature.
  bool empty() const override {
    return false;
  }

  void Print(bool final, text::Printer* printer) const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(MethodDefinition);

  std::string signature_;
  std::vector<std::string> statements_;
};

enum class ClassQualifier { kNone, kStatic };

class ClassDefinition : public ClassMember {
 public:
  static void WriteJavaFile(const ClassDefinition* def, const android::StringPiece& package,
                            bool final, io::OutputStream* out);

  ClassDefinition(const android::StringPiece& name, ClassQualifier qualifier, bool createIfEmpty)
      : name_(name.to_string()), qualifier_(qualifier), create_if_empty_(createIfEmpty) {}

  enum class Result {
    kAdded,
    kOverridden,
  };

  Result AddMember(std::unique_ptr<ClassMember> member);

  bool empty() const override;

  const std::string& GetName() const override {
    return name_;
  }

  void Print(bool final, text::Printer* printer) const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(ClassDefinition);

  std::string name_;
  ClassQualifier qualifier_;
  bool create_if_empty_;
  std::vector<std::unique_ptr<ClassMember>> ordered_members_;
  std::unordered_map<android::StringPiece, size_t> indexed_members_;
};

}  // namespace aapt

#endif /* AAPT_JAVA_CLASSDEFINITION_H */
