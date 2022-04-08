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

#ifndef AAPT_RESOURCE_VALUES_H
#define AAPT_RESOURCE_VALUES_H

#include <array>
#include <limits>
#include <ostream>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "Diagnostics.h"
#include "Resource.h"
#include "StringPool.h"
#include "io/File.h"
#include "text/Printer.h"
#include "util/Maybe.h"

namespace aapt {

class ValueVisitor;
class ConstValueVisitor;

// A resource value. This is an all-encompassing representation
// of Item and Map and their subclasses. The way to do
// type specific operations is to check the Value's type() and
// cast it to the appropriate subclass. This isn't super clean,
// but it is the simplest strategy.
class Value {
 public:
  virtual ~Value() = default;

  // Whether this value is weak and can be overridden without warning or error. Default is false.
  bool IsWeak() const {
    return weak_;
  }

  void SetWeak(bool val) {
    weak_ = val;
  }

  // Whether the value is marked as translatable. This does not persist when flattened to binary.
  // It is only used during compilation phase.
  void SetTranslatable(bool val) {
    translatable_ = val;
  }

  // Default true.
  bool IsTranslatable() const {
    return translatable_;
  }

  // Returns the source where this value was defined.
  const Source& GetSource() const {
    return source_;
  }

  void SetSource(const Source& source) {
    source_ = source;
  }

  void SetSource(Source&& source) {
    source_ = std::move(source);
  }

  // Returns the comment that was associated with this resource.
  const std::string& GetComment() const {
    return comment_;
  }

  void SetComment(const android::StringPiece& str) {
    comment_ = str.to_string();
  }

  void SetComment(std::string&& str) {
    comment_ = std::move(str);
  }

  virtual bool Equals(const Value* value) const = 0;

  // Calls the appropriate overload of ValueVisitor.
  virtual void Accept(ValueVisitor* visitor) = 0;

  // Calls the appropriate overload of ConstValueVisitor.
  virtual void Accept(ConstValueVisitor* visitor) const = 0;

  // Clone the value. `new_pool` is the new StringPool that
  // any resources with strings should use when copying their string.
  virtual Value* Clone(StringPool* new_pool) const = 0;

  // Human readable printout of this value.
  virtual void Print(std::ostream* out) const = 0;

  // Human readable printout of this value that may omit some information for the sake
  // of brevity and readability. Default implementation just calls Print().
  virtual void PrettyPrint(text::Printer* printer) const;

  friend std::ostream& operator<<(std::ostream& out, const Value& value);

 protected:
  Source source_;
  std::string comment_;
  bool weak_ = false;
  bool translatable_ = true;
};

// Inherit from this to get visitor accepting implementations for free.
template <typename Derived>
struct BaseValue : public Value {
  void Accept(ValueVisitor* visitor) override;
  void Accept(ConstValueVisitor* visitor) const override;
};

// A resource item with a single value. This maps to android::ResTable_entry.
struct Item : public Value {
  // Clone the Item.
  virtual Item* Clone(StringPool* new_pool) const override = 0;

  // Fills in an android::Res_value structure with this Item's binary representation.
  // Returns false if an error occurred.
  virtual bool Flatten(android::Res_value* out_value) const = 0;
};

// Inherit from this to get visitor accepting implementations for free.
template <typename Derived>
struct BaseItem : public Item {
  void Accept(ValueVisitor* visitor) override;
  void Accept(ConstValueVisitor* visitor) const override;
};

// A reference to another resource. This maps to android::Res_value::TYPE_REFERENCE.
// A reference can be symbolic (with the name set to a valid resource name) or be
// numeric (the id is set to a valid resource ID).
struct Reference : public BaseItem<Reference> {
  enum class Type {
    kResource,
    kAttribute,
  };

  Maybe<ResourceName> name;
  Maybe<ResourceId> id;
  Reference::Type reference_type;
  bool private_reference = false;
  bool is_dynamic = false;

  Reference();
  explicit Reference(const ResourceNameRef& n, Type type = Type::kResource);
  explicit Reference(const ResourceId& i, Type type = Type::kResource);
  Reference(const ResourceNameRef& n, const ResourceId& i);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  Reference* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
  void PrettyPrint(text::Printer* printer) const override;

  // Prints the reference without a package name if the package name matches the one given.
  void PrettyPrint(const android::StringPiece& package, text::Printer* printer) const;
};

bool operator<(const Reference&, const Reference&);
bool operator==(const Reference&, const Reference&);

// An ID resource. Has no real value, just a place holder.
struct Id : public BaseItem<Id> {
  Id() {
    weak_ = true;
  }

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out) const override;
  Id* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

// A raw, unprocessed string. This may contain quotations, escape sequences, and whitespace.
// This shall *NOT* end up in the final resource table.
struct RawString : public BaseItem<RawString> {
  StringPool::Ref value;

  explicit RawString(const StringPool::Ref& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  RawString* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

// Identifies a range of characters in a string that are untranslatable.
// These should not be pseudolocalized. The start and end indices are measured in bytes.
struct UntranslatableSection {
  // Start offset inclusive.
  size_t start;

  // End offset exclusive.
  size_t end;
};

inline bool operator==(const UntranslatableSection& a, const UntranslatableSection& b) {
  return a.start == b.start && a.end == b.end;
}

inline bool operator!=(const UntranslatableSection& a, const UntranslatableSection& b) {
  return a.start != b.start || a.end != b.end;
}

struct String : public BaseItem<String> {
  StringPool::Ref value;

  // Sections of the string to NOT translate. Mainly used
  // for pseudolocalization. This data is NOT persisted
  // in any format.
  std::vector<UntranslatableSection> untranslatable_sections;

  explicit String(const StringPool::Ref& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  String* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
  void PrettyPrint(text::Printer* printer) const override;
};

struct StyledString : public BaseItem<StyledString> {
  StringPool::StyleRef value;

  // Sections of the string to NOT translate. Mainly used
  // for pseudolocalization. This data is NOT persisted
  // in any format.
  std::vector<UntranslatableSection> untranslatable_sections;

  explicit StyledString(const StringPool::StyleRef& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  StyledString* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

struct FileReference : public BaseItem<FileReference> {
  StringPool::Ref path;

  // A handle to the file object from which this file can be read.
  // This field is NOT persisted in any format. It is transient.
  io::IFile* file = nullptr;

  // FileType of the file pointed to by `file`. This is used to know how to inflate the file,
  // or if to inflate at all (just copy).
  ResourceFile::Type type = ResourceFile::Type::kUnknown;

  FileReference() = default;
  explicit FileReference(const StringPool::Ref& path);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  FileReference* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

// Represents any other android::Res_value.
struct BinaryPrimitive : public BaseItem<BinaryPrimitive> {
  android::Res_value value;

  BinaryPrimitive() = default;
  explicit BinaryPrimitive(const android::Res_value& val);
  BinaryPrimitive(uint8_t dataType, uint32_t data);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  BinaryPrimitive* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
  void PrettyPrint(text::Printer* printer) const override;
};

struct Attribute : public BaseValue<Attribute> {
  struct Symbol {
    Reference symbol;
    uint32_t value;
    uint8_t type;

    friend std::ostream& operator<<(std::ostream& out, const Symbol& symbol);
  };

  uint32_t type_mask;
  int32_t min_int;
  int32_t max_int;
  std::vector<Symbol> symbols;

  explicit Attribute(uint32_t t = 0u);

  bool Equals(const Value* value) const override;

  // Returns true if this Attribute's format is compatible with the given Attribute. The basic
  // rule is that TYPE_REFERENCE can be ignored for both of the Attributes, and TYPE_FLAGS and
  // TYPE_ENUMS are never compatible.
  bool IsCompatibleWith(const Attribute& attr) const;

  Attribute* Clone(StringPool* new_pool) const override;
  std::string MaskString() const;
  void Print(std::ostream* out) const override;
  bool Matches(const Item& item, DiagMessage* out_msg = nullptr) const;
};

struct Style : public BaseValue<Style> {
  struct Entry {
    Reference key;
    std::unique_ptr<Item> value;

    friend std::ostream& operator<<(std::ostream& out, const Entry& entry);
  };

  Maybe<Reference> parent;

  // If set to true, the parent was auto inferred from the style's name.
  bool parent_inferred = false;

  std::vector<Entry> entries;

  bool Equals(const Value* value) const override;
  Style* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;

  // Merges `style` into this Style. All identical attributes of `style` take precedence, including
  // the parent, if there is one.
  void MergeWith(Style* style, StringPool* pool);
};

struct Array : public BaseValue<Array> {
  std::vector<std::unique_ptr<Item>> elements;

  bool Equals(const Value* value) const override;
  Array* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

struct Plural : public BaseValue<Plural> {
  enum { Zero = 0, One, Two, Few, Many, Other, Count };

  std::array<std::unique_ptr<Item>, Count> values;

  bool Equals(const Value* value) const override;
  Plural* Clone(StringPool* new_pool) const override;
  void Print(std::ostream* out) const override;
};

struct Styleable : public BaseValue<Styleable> {
  std::vector<Reference> entries;

  bool Equals(const Value* value) const override;
  Styleable* Clone(StringPool* newPool) const override;
  void Print(std::ostream* out) const override;
  void MergeWith(Styleable* styleable);
};

template <typename T>
typename std::enable_if<std::is_base_of<Value, T>::value, std::ostream&>::type operator<<(
    std::ostream& out, const std::unique_ptr<T>& value) {
  if (value == nullptr) {
    out << "NULL";
  } else {
    value->Print(&out);
  }
  return out;
}

}  // namespace aapt

#endif  // AAPT_RESOURCE_VALUES_H
