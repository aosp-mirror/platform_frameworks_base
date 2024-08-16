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

#include "Resource.h"
#include "ValueTransformer.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"
#include "androidfw/StringPool.h"
#include "io/File.h"
#include "text/Printer.h"

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

  void SetFlagStatus(FlagStatus val) {
    flag_status_ = val;
  }

  FlagStatus GetFlagStatus() const {
    return flag_status_;
  }

  // Returns the source where this value was defined.
  const android::Source& GetSource() const {
    return source_;
  }

  void SetSource(const android::Source& source) {
    source_ = source;
  }

  void SetSource(android::Source&& source) {
    source_ = std::move(source);
  }

  // Returns the comment that was associated with this resource.
  const std::string& GetComment() const {
    return comment_;
  }

  void SetComment(android::StringPiece str) {
    comment_.assign(str);
  }

  void SetComment(std::string&& str) {
    comment_ = std::move(str);
  }

  virtual bool Equals(const Value* value) const = 0;

  // Calls the appropriate overload of ValueVisitor.
  virtual void Accept(ValueVisitor* visitor) = 0;

  // Calls the appropriate overload of ConstValueVisitor.
  virtual void Accept(ConstValueVisitor* visitor) const = 0;

  // Transform this Value into another Value using the transformer.
  std::unique_ptr<Value> Transform(ValueTransformer& transformer) const;

  // Human readable printout of this value.
  virtual void Print(std::ostream* out) const = 0;

  // Human readable printout of this value that may omit some information for the sake
  // of brevity and readability. Default implementation just calls Print().
  virtual void PrettyPrint(text::Printer* printer) const;

  // Removes any part of the value that is beind a disabled flag.
  virtual void RemoveFlagDisabledElements() {
  }

  friend std::ostream& operator<<(std::ostream& out, const Value& value);

 protected:
  android::Source source_;
  std::string comment_;
  bool weak_ = false;
  bool translatable_ = true;
  FlagStatus flag_status_ = FlagStatus::NoFlag;

 private:
  virtual Value* TransformValueImpl(ValueTransformer& transformer) const = 0;
};

// Inherit from this to get visitor accepting implementations for free.
template <typename Derived>
struct BaseValue : public Value {
  void Accept(ValueVisitor* visitor) override;
  void Accept(ConstValueVisitor* visitor) const override;
};

// A resource item with a single value. This maps to android::ResTable_entry.
struct Item : public Value {
  // Fills in an android::Res_value structure with this Item's binary representation.
  // Returns false if an error occurred.
  virtual bool Flatten(android::Res_value* out_value) const = 0;

  // Transform this Item into another Item using the transformer.
  std::unique_ptr<Item> Transform(ValueTransformer& transformer) const;

 private:
  virtual Item* TransformItemImpl(ValueTransformer& transformer) const = 0;
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
struct Reference : public TransformableItem<Reference, BaseItem<Reference>> {
  enum class Type : uint8_t {
    kResource,
    kAttribute,
  };

  std::optional<ResourceName> name;
  std::optional<ResourceId> id;
  std::optional<uint32_t> type_flags;
  Reference::Type reference_type;
  bool private_reference = false;
  bool is_dynamic = false;
  bool allow_raw = false;

  Reference();
  explicit Reference(const ResourceNameRef& n, Type type = Type::kResource);
  explicit Reference(const ResourceId& i, Type type = Type::kResource);
  Reference(const ResourceNameRef& n, const ResourceId& i);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  void Print(std::ostream* out) const override;
  void PrettyPrint(text::Printer* printer) const override;

  // Prints the reference without a package name if the package name matches the one given.
  void PrettyPrint(android::StringPiece package, text::Printer* printer) const;
};

bool operator<(const Reference&, const Reference&);
bool operator==(const Reference&, const Reference&);

// An ID resource. Has no real value, just a place holder.
struct Id : public TransformableItem<Id, BaseItem<Id>> {
  Id() {
    weak_ = true;
  }

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out) const override;
  void Print(std::ostream* out) const override;
};

// A raw, unprocessed string. This may contain quotations, escape sequences, and whitespace.
// This shall *NOT* end up in the final resource table.
struct RawString : public TransformableItem<RawString, BaseItem<RawString>> {
  android::StringPool::Ref value;

  explicit RawString(const android::StringPool::Ref& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
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

struct String : public TransformableItem<String, BaseItem<String>> {
  android::StringPool::Ref value;

  // Sections of the string to NOT translate. Mainly used
  // for pseudolocalization. This data is NOT persisted
  // in any format.
  std::vector<UntranslatableSection> untranslatable_sections;

  explicit String(const android::StringPool::Ref& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  void Print(std::ostream* out) const override;
  void PrettyPrint(text::Printer* printer) const override;
};

struct StyledString : public TransformableItem<StyledString, BaseItem<StyledString>> {
  android::StringPool::StyleRef value;

  // Sections of the string to NOT translate. Mainly used
  // for pseudolocalization. This data is NOT persisted
  // in any format.
  std::vector<UntranslatableSection> untranslatable_sections;

  explicit StyledString(const android::StringPool::StyleRef& ref);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  void Print(std::ostream* out) const override;
};

struct FileReference : public TransformableItem<FileReference, BaseItem<FileReference>> {
  android::StringPool::Ref path;

  // A handle to the file object from which this file can be read.
  // This field is NOT persisted in any format. It is transient.
  io::IFile* file = nullptr;

  // FileType of the file pointed to by `file`. This is used to know how to inflate the file,
  // or if to inflate at all (just copy).
  ResourceFile::Type type = ResourceFile::Type::kUnknown;

  FileReference() = default;
  explicit FileReference(const android::StringPool::Ref& path);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  void Print(std::ostream* out) const override;
};

// Represents any other android::Res_value.
struct BinaryPrimitive : public TransformableItem<BinaryPrimitive, BaseItem<BinaryPrimitive>> {
  android::Res_value value;

  BinaryPrimitive() = default;
  explicit BinaryPrimitive(const android::Res_value& val);
  BinaryPrimitive(uint8_t dataType, uint32_t data);

  bool Equals(const Value* value) const override;
  bool Flatten(android::Res_value* out_value) const override;
  void Print(std::ostream* out) const override;
  static const char* DecideFormat(float f);
  void PrettyPrint(text::Printer* printer) const override;
};

struct Attribute : public TransformableValue<Attribute, BaseValue<Attribute>> {
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

  std::string MaskString() const;
  static std::string MaskString(uint32_t type_mask);

  void Print(std::ostream* out) const override;
  bool Matches(const Item& item, android::DiagMessage* out_msg = nullptr) const;
};

struct Style : public TransformableValue<Style, BaseValue<Style>> {
  struct Entry {
    Reference key;
    std::unique_ptr<Item> value;

    friend std::ostream& operator<<(std::ostream& out, const Entry& entry);
  };

  std::optional<Reference> parent;

  // If set to true, the parent was auto inferred from the style's name.
  bool parent_inferred = false;

  std::vector<Entry> entries;

  bool Equals(const Value* value) const override;
  void Print(std::ostream* out) const override;

  // Merges `style` into this Style. All identical attributes of `style` take precedence, including
  // the parent, if there is one.
  void MergeWith(Style* style, android::StringPool* pool);
};

struct Array : public TransformableValue<Array, BaseValue<Array>> {
  std::vector<std::unique_ptr<Item>> elements;

  bool Equals(const Value* value) const override;
  void Print(std::ostream* out) const override;
  void RemoveFlagDisabledElements() override;
};

struct Plural : public TransformableValue<Plural, BaseValue<Plural>> {
  enum { Zero = 0, One, Two, Few, Many, Other, Count };

  std::array<std::unique_ptr<Item>, Count> values;

  bool Equals(const Value* value) const override;
  void Print(std::ostream* out) const override;
};

struct Styleable : public TransformableValue<Styleable, BaseValue<Styleable>> {
  std::vector<Reference> entries;

  bool Equals(const Value* value) const override;
  void Print(std::ostream* out) const override;
  void MergeWith(Styleable* styleable);
};

struct Macro : public TransformableValue<Macro, BaseValue<Macro>> {
  std::string raw_value;
  android::StyleString style_string;
  std::vector<UntranslatableSection> untranslatable_sections;

  struct Namespace {
    std::string alias;
    std::string package_name;
    bool is_private;

    bool operator==(const Namespace& right) const {
      return alias == right.alias && package_name == right.package_name &&
             is_private == right.is_private;
    }
  };

  std::vector<Namespace> alias_namespaces;

  bool Equals(const Value* value) const override;
  void Print(std::ostream* out) const override;
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

struct CloningValueTransformer : public ValueTransformer {
  explicit CloningValueTransformer(android::StringPool* new_pool);

  std::unique_ptr<Reference> TransformDerived(const Reference* value) override;
  std::unique_ptr<Id> TransformDerived(const Id* value) override;
  std::unique_ptr<RawString> TransformDerived(const RawString* value) override;
  std::unique_ptr<String> TransformDerived(const String* value) override;
  std::unique_ptr<StyledString> TransformDerived(const StyledString* value) override;
  std::unique_ptr<FileReference> TransformDerived(const FileReference* value) override;
  std::unique_ptr<BinaryPrimitive> TransformDerived(const BinaryPrimitive* value) override;
  std::unique_ptr<Attribute> TransformDerived(const Attribute* value) override;
  std::unique_ptr<Style> TransformDerived(const Style* value) override;
  std::unique_ptr<Array> TransformDerived(const Array* value) override;
  std::unique_ptr<Plural> TransformDerived(const Plural* value) override;
  std::unique_ptr<Styleable> TransformDerived(const Styleable* value) override;
  std::unique_ptr<Macro> TransformDerived(const Macro* value) override;
};

}  // namespace aapt

#endif  // AAPT_RESOURCE_VALUES_H
