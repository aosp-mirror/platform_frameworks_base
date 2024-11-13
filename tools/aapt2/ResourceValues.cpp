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

#include "ResourceValues.h"

#include <algorithm>
#include <cinttypes>
#include <limits>
#include <set>
#include <sstream>

#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"

#include "Resource.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "util/Util.h"

using ::aapt::text::Printer;
using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

void Value::PrettyPrint(Printer* printer) const {
  std::ostringstream str_stream;
  Print(&str_stream);
  printer->Print(str_stream.str());
}

std::ostream& operator<<(std::ostream& out, const Value& value) {
  value.Print(&out);
  return out;
}

std::unique_ptr<Value> Value::Transform(ValueTransformer& transformer) const {
  return std::unique_ptr<Value>(this->TransformValueImpl(transformer));
}

std::unique_ptr<Item> Item::Transform(ValueTransformer& transformer) const {
  return std::unique_ptr<Item>(this->TransformItemImpl(transformer));
}

template <typename Derived>
void BaseValue<Derived>::Accept(ValueVisitor* visitor) {
  visitor->Visit(static_cast<Derived*>(this));
}

template <typename Derived>
void BaseValue<Derived>::Accept(ConstValueVisitor* visitor) const {
  visitor->Visit(static_cast<const Derived*>(this));
}

template <typename Derived>
void BaseItem<Derived>::Accept(ValueVisitor* visitor) {
  visitor->Visit(static_cast<Derived*>(this));
}

template <typename Derived>
void BaseItem<Derived>::Accept(ConstValueVisitor* visitor) const {
  visitor->Visit(static_cast<const Derived*>(this));
}

RawString::RawString(const android::StringPool::Ref& ref) : value(ref) {
}

bool RawString::Equals(const Value* value) const {
  const RawString* other = ValueCast<RawString>(value);
  if (!other) {
    return false;
  }
  return *this->value == *other->value;
}

bool RawString::Flatten(android::Res_value* out_value) const {
  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = android::util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

void RawString::Print(std::ostream* out) const {
  *out << "(raw string) " << *value;
}

Reference::Reference() : reference_type(Type::kResource) {}

Reference::Reference(const ResourceNameRef& n, Type t)
    : name(n.ToResourceName()), reference_type(t) {}

Reference::Reference(const ResourceId& i, Type type)
    : id(i), reference_type(type) {}

Reference::Reference(const ResourceNameRef& n, const ResourceId& i)
    : name(n.ToResourceName()), id(i), reference_type(Type::kResource) {}

bool Reference::Equals(const Value* value) const {
  const Reference* other = ValueCast<Reference>(value);
  if (!other) {
    return false;
  }
  return reference_type == other->reference_type && private_reference == other->private_reference &&
         id == other->id && name == other->name && type_flags == other->type_flags;
}

bool Reference::Flatten(android::Res_value* out_value) const {
  if (name && name.value().type.type == ResourceType::kMacro) {
    return false;
  }

  const ResourceId resid = id.value_or(ResourceId(0));
  const bool dynamic = resid.is_valid() && is_dynamic;

  if (reference_type == Reference::Type::kResource) {
    if (dynamic) {
      out_value->dataType = android::Res_value::TYPE_DYNAMIC_REFERENCE;
    } else {
      out_value->dataType = android::Res_value::TYPE_REFERENCE;
    }
  } else {
    if (dynamic) {
      out_value->dataType = android::Res_value::TYPE_DYNAMIC_ATTRIBUTE;
    } else {
      out_value->dataType = android::Res_value::TYPE_ATTRIBUTE;
    }
  }
  out_value->data = android::util::HostToDevice32(resid.id);
  return true;
}

void Reference::Print(std::ostream* out) const {
  if (reference_type == Type::kResource) {
    *out << "(reference) @";
    if (!name && !id) {
      *out << "null";
      return;
    }
  } else {
    *out << "(attr-reference) ?";
  }

  if (private_reference) {
    *out << "*";
  }

  if (name) {
    *out << name.value();
  }

  if (id && id.value().is_valid()) {
    if (name) {
      *out << " ";
    }
    *out << id.value();
  }
}

static void PrettyPrintReferenceImpl(const Reference& ref, bool print_package, Printer* printer) {
  switch (ref.reference_type) {
    case Reference::Type::kResource:
      printer->Print("@");
      break;

    case Reference::Type::kAttribute:
      printer->Print("?");
      break;
  }

  if (!ref.name && !ref.id) {
    printer->Print("null");
    return;
  }

  if (ref.private_reference) {
    printer->Print("*");
  }

  if (ref.name) {
    const ResourceName& name = ref.name.value();
    if (print_package) {
      printer->Print(name.to_string());
    } else {
      printer->Print(name.type.to_string());
      printer->Print("/");
      printer->Print(name.entry);
    }
  } else if (ref.id && ref.id.value().is_valid()) {
    printer->Print(ref.id.value().to_string());
  }
}

void Reference::PrettyPrint(Printer* printer) const {
  PrettyPrintReferenceImpl(*this, true /*print_package*/, printer);
}

void Reference::PrettyPrint(StringPiece package, Printer* printer) const {
  const bool print_package = name ? package != name.value().package : true;
  PrettyPrintReferenceImpl(*this, print_package, printer);
}

bool Id::Equals(const Value* value) const {
  return ValueCast<Id>(value) != nullptr;
}

bool Id::Flatten(android::Res_value* out) const {
  out->dataType = android::Res_value::TYPE_INT_BOOLEAN;
  out->data = android::util::HostToDevice32(0);
  return true;
}

void Id::Print(std::ostream* out) const {
  *out << "(id)";
}

String::String(const android::StringPool::Ref& ref) : value(ref) {
}

bool String::Equals(const Value* value) const {
  const String* other = ValueCast<String>(value);
  if (!other) {
    return false;
  }

  if (this->value != other->value) {
    return false;
  }

  if (untranslatable_sections.size() != other->untranslatable_sections.size()) {
    return false;
  }

  auto other_iter = other->untranslatable_sections.begin();
  for (const UntranslatableSection& this_section : untranslatable_sections) {
    if (this_section != *other_iter) {
      return false;
    }
    ++other_iter;
  }
  return true;
}

bool String::Flatten(android::Res_value* out_value) const {
  // Verify that our StringPool index is within encode-able limits.
  if (value.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = android::util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

void String::Print(std::ostream* out) const {
  *out << "(string) \"" << *value << "\"";
}

void String::PrettyPrint(Printer* printer) const {
  printer->Print("\"");
  printer->Print(*value);
  printer->Print("\"");
}

StyledString::StyledString(const android::StringPool::StyleRef& ref) : value(ref) {
}

bool StyledString::Equals(const Value* value) const {
  const StyledString* other = ValueCast<StyledString>(value);
  if (!other) {
    return false;
  }

  if (this->value != other->value) {
    return false;
  }

  if (untranslatable_sections.size() != other->untranslatable_sections.size()) {
    return false;
  }

  auto other_iter = other->untranslatable_sections.begin();
  for (const UntranslatableSection& this_section : untranslatable_sections) {
    if (this_section != *other_iter) {
      return false;
    }
    ++other_iter;
  }
  return true;
}

bool StyledString::Flatten(android::Res_value* out_value) const {
  if (value.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = android::util::HostToDevice32(static_cast<uint32_t>(value.index()));
  return true;
}

void StyledString::Print(std::ostream* out) const {
  *out << "(styled string) \"" << value->value << "\"";
  for (const android::StringPool::Span& span : value->spans) {
    *out << " " << *span.name << ":" << span.first_char << "," << span.last_char;
  }
}

FileReference::FileReference(const android::StringPool::Ref& _path) : path(_path) {
}

bool FileReference::Equals(const Value* value) const {
  const FileReference* other = ValueCast<FileReference>(value);
  if (!other) {
    return false;
  }
  return path == other->path;
}

bool FileReference::Flatten(android::Res_value* out_value) const {
  if (path.index() > std::numeric_limits<uint32_t>::max()) {
    return false;
  }

  out_value->dataType = android::Res_value::TYPE_STRING;
  out_value->data = android::util::HostToDevice32(static_cast<uint32_t>(path.index()));
  return true;
}

void FileReference::Print(std::ostream* out) const {
  *out << "(file) " << *path;
  switch (type) {
    case ResourceFile::Type::kBinaryXml:
      *out << " type=XML";
      break;
    case ResourceFile::Type::kProtoXml:
      *out << " type=protoXML";
      break;
    case ResourceFile::Type::kPng:
      *out << " type=PNG";
      break;
    default:
      break;
  }
}

BinaryPrimitive::BinaryPrimitive(const android::Res_value& val) : value(val) {
}

BinaryPrimitive::BinaryPrimitive(uint8_t dataType, uint32_t data) {
  value.dataType = dataType;
  value.data = data;
}

bool BinaryPrimitive::Equals(const Value* value) const {
  const BinaryPrimitive* other = ValueCast<BinaryPrimitive>(value);
  if (!other) {
    return false;
  }
  return this->value.dataType == other->value.dataType &&
         this->value.data == other->value.data;
}

bool BinaryPrimitive::Flatten(::android::Res_value* out_value) const {
  out_value->dataType = value.dataType;
  out_value->data = android::util::HostToDevice32(value.data);
  return true;
}

void BinaryPrimitive::Print(std::ostream* out) const {
  *out << StringPrintf("(primitive) type=0x%02x data=0x%08x", value.dataType, value.data);
}

static std::string ComplexToString(uint32_t complex_value, bool fraction) {
  using ::android::Res_value;

  constexpr std::array<int, 4> kRadixShifts = {{23, 16, 8, 0}};

  // Determine the radix that was used.
  const uint32_t radix =
      (complex_value >> Res_value::COMPLEX_RADIX_SHIFT) & Res_value::COMPLEX_RADIX_MASK;
  const uint64_t mantissa = uint64_t{(complex_value >> Res_value::COMPLEX_MANTISSA_SHIFT) &
                                     Res_value::COMPLEX_MANTISSA_MASK}
                            << kRadixShifts[radix];
  const float value = mantissa * (1.0f / (1 << 23));

  std::string str = StringPrintf("%f", value);

  const int unit_type =
      (complex_value >> Res_value::COMPLEX_UNIT_SHIFT) & Res_value::COMPLEX_UNIT_MASK;
  if (fraction) {
    switch (unit_type) {
      case Res_value::COMPLEX_UNIT_FRACTION:
        str += "%";
        break;
      case Res_value::COMPLEX_UNIT_FRACTION_PARENT:
        str += "%p";
        break;
      default:
        str += "???";
        break;
    }
  } else {
    switch (unit_type) {
      case Res_value::COMPLEX_UNIT_PX:
        str += "px";
        break;
      case Res_value::COMPLEX_UNIT_DIP:
        str += "dp";
        break;
      case Res_value::COMPLEX_UNIT_SP:
        str += "sp";
        break;
      case Res_value::COMPLEX_UNIT_PT:
        str += "pt";
        break;
      case Res_value::COMPLEX_UNIT_IN:
        str += "in";
        break;
      case Res_value::COMPLEX_UNIT_MM:
        str += "mm";
        break;
      default:
        str += "???";
        break;
    }
  }
  return str;
}

// This function is designed to using different specifier to print different floats,
// which can print more accurate format rather than using %g only.
const char* BinaryPrimitive::DecideFormat(float f) {
  // if the float is either too big or too tiny, print it in scientific notation.
  // eg: "10995116277760000000000" to 1.099512e+22, "0.00000000001" to 1.000000e-11
  if (fabs(f) > std::numeric_limits<int64_t>::max() || fabs(f) < 1e-10) {
    return "%e";
    // Else if the number is an integer exactly, print it without trailing zeros.
    // eg: "1099511627776" to 1099511627776
  } else if (int64_t(f) == f) {
    return "%.0f";
  }
  return "%g";
}

void BinaryPrimitive::PrettyPrint(Printer* printer) const {
  using ::android::Res_value;
  switch (value.dataType) {
    case Res_value::TYPE_NULL:
      if (value.data == Res_value::DATA_NULL_EMPTY) {
        printer->Print("@empty");
      } else {
        printer->Print("@null");
      }
      break;

    case Res_value::TYPE_INT_DEC:
      printer->Print(StringPrintf("%" PRIi32, static_cast<int32_t>(value.data)));
      break;

    case Res_value::TYPE_INT_HEX:
      printer->Print(StringPrintf("0x%08x", value.data));
      break;

    case Res_value::TYPE_INT_BOOLEAN:
      printer->Print(value.data != 0 ? "true" : "false");
      break;

    case Res_value::TYPE_INT_COLOR_ARGB8:
    case Res_value::TYPE_INT_COLOR_RGB8:
    case Res_value::TYPE_INT_COLOR_ARGB4:
    case Res_value::TYPE_INT_COLOR_RGB4:
      printer->Print(StringPrintf("#%08x", value.data));
      break;

    case Res_value::TYPE_FLOAT:
      float f;
      f = *reinterpret_cast<const float*>(&value.data);
      printer->Print(StringPrintf(DecideFormat(f), f));
      break;

    case Res_value::TYPE_DIMENSION:
      printer->Print(ComplexToString(value.data, false /*fraction*/));
      break;

    case Res_value::TYPE_FRACTION:
      printer->Print(ComplexToString(value.data, true /*fraction*/));
      break;

    default:
      printer->Print(StringPrintf("(unknown 0x%02x) 0x%08x", value.dataType, value.data));
      break;
  }
}

Attribute::Attribute(uint32_t t)
    : type_mask(t),
      min_int(std::numeric_limits<int32_t>::min()),
      max_int(std::numeric_limits<int32_t>::max()) {
}

std::ostream& operator<<(std::ostream& out, const Attribute::Symbol& s) {
  if (s.symbol.name) {
    out << s.symbol.name.value().entry;
  } else {
    out << "???";
  }
  return out << "=" << s.value;
}

template <typename T>
constexpr T* add_pointer(T& val) {
  return &val;
}

bool Attribute::Equals(const Value* value) const {
  const Attribute* other = ValueCast<Attribute>(value);
  if (!other) {
    return false;
  }

  if (symbols.size() != other->symbols.size()) {
    return false;
  }

  if (type_mask != other->type_mask || min_int != other->min_int || max_int != other->max_int) {
    return false;
  }

  std::vector<const Symbol*> sorted_a;
  std::transform(symbols.begin(), symbols.end(), std::back_inserter(sorted_a),
                 add_pointer<const Symbol>);
  std::sort(sorted_a.begin(), sorted_a.end(), [](const Symbol* a, const Symbol* b) -> bool {
    return a->symbol.name < b->symbol.name;
  });

  std::vector<const Symbol*> sorted_b;
  std::transform(other->symbols.begin(), other->symbols.end(), std::back_inserter(sorted_b),
                 add_pointer<const Symbol>);
  std::sort(sorted_b.begin(), sorted_b.end(), [](const Symbol* a, const Symbol* b) -> bool {
    return a->symbol.name < b->symbol.name;
  });

  return std::equal(sorted_a.begin(), sorted_a.end(), sorted_b.begin(),
                    [](const Symbol* a, const Symbol* b) -> bool {
                      return a->symbol.Equals(&b->symbol) && a->value == b->value;
                    });
}

bool Attribute::IsCompatibleWith(const Attribute& attr) const {
  // If the high bits are set on any of these attribute type masks, then they are incompatible.
  // We don't check that flags and enums are identical.
  if ((type_mask & ~android::ResTable_map::TYPE_ANY) != 0 ||
      (attr.type_mask & ~android::ResTable_map::TYPE_ANY) != 0) {
    return false;
  }

  // Every attribute accepts a reference.
  uint32_t this_type_mask = type_mask | android::ResTable_map::TYPE_REFERENCE;
  uint32_t that_type_mask = attr.type_mask | android::ResTable_map::TYPE_REFERENCE;
  return this_type_mask == that_type_mask;
}

std::string Attribute::MaskString(uint32_t type_mask) {
  if (type_mask == android::ResTable_map::TYPE_ANY) {
    return "any";
  }

  std::ostringstream out;
  bool set = false;
  if ((type_mask & android::ResTable_map::TYPE_REFERENCE) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "reference";
  }

  if ((type_mask & android::ResTable_map::TYPE_STRING) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "string";
  }

  if ((type_mask & android::ResTable_map::TYPE_INTEGER) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "integer";
  }

  if ((type_mask & android::ResTable_map::TYPE_BOOLEAN) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "boolean";
  }

  if ((type_mask & android::ResTable_map::TYPE_COLOR) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "color";
  }

  if ((type_mask & android::ResTable_map::TYPE_FLOAT) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "float";
  }

  if ((type_mask & android::ResTable_map::TYPE_DIMENSION) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "dimension";
  }

  if ((type_mask & android::ResTable_map::TYPE_FRACTION) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "fraction";
  }

  if ((type_mask & android::ResTable_map::TYPE_ENUM) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "enum";
  }

  if ((type_mask & android::ResTable_map::TYPE_FLAGS) != 0) {
    if (!set) {
      set = true;
    } else {
      out << "|";
    }
    out << "flags";
  }
  return out.str();
}

std::string Attribute::MaskString() const {
  return MaskString(type_mask);
}

void Attribute::Print(std::ostream* out) const {
  *out << "(attr) " << MaskString();

  if (!symbols.empty()) {
    *out << " [" << util::Joiner(symbols, ", ") << "]";
  }

  if (min_int != std::numeric_limits<int32_t>::min()) {
    *out << " min=" << min_int;
  }

  if (max_int != std::numeric_limits<int32_t>::max()) {
    *out << " max=" << max_int;
  }

  if (IsWeak()) {
    *out << " [weak]";
  }
}

static void BuildAttributeMismatchMessage(const Attribute& attr, const Item& value,
                                          android::DiagMessage* out_msg) {
  *out_msg << "expected";
  if (attr.type_mask & android::ResTable_map::TYPE_BOOLEAN) {
    *out_msg << " boolean";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_COLOR) {
    *out_msg << " color";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_DIMENSION) {
    *out_msg << " dimension";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_ENUM) {
    *out_msg << " enum";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_FLAGS) {
    *out_msg << " flags";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_FLOAT) {
    *out_msg << " float";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_FRACTION) {
    *out_msg << " fraction";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_INTEGER) {
    *out_msg << " integer";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_REFERENCE) {
    *out_msg << " reference";
  }

  if (attr.type_mask & android::ResTable_map::TYPE_STRING) {
    *out_msg << " string";
  }

  *out_msg << " but got " << value;
}

bool Attribute::Matches(const Item& item, android::DiagMessage* out_msg) const {
  constexpr const uint32_t TYPE_ENUM = android::ResTable_map::TYPE_ENUM;
  constexpr const uint32_t TYPE_FLAGS = android::ResTable_map::TYPE_FLAGS;
  constexpr const uint32_t TYPE_INTEGER = android::ResTable_map::TYPE_INTEGER;
  constexpr const uint32_t TYPE_REFERENCE = android::ResTable_map::TYPE_REFERENCE;

  android::Res_value val = {};
  item.Flatten(&val);

  const uint32_t flattened_data = android::util::DeviceToHost32(val.data);

  // Always allow references.
  const uint32_t actual_type = ResourceUtils::AndroidTypeToAttributeTypeMask(val.dataType);

  // Only one type must match between the actual and expected.
  if ((actual_type & (type_mask | TYPE_REFERENCE)) == 0) {
    if (out_msg) {
      BuildAttributeMismatchMessage(*this, item, out_msg);
    }
    return false;
  }

  // Enums and flags are encoded as integers, so check them first before doing any range checks.
  if ((type_mask & TYPE_ENUM) != 0 && (actual_type & TYPE_ENUM) != 0) {
    for (const Symbol& s : symbols) {
      if (flattened_data == s.value) {
        return true;
      }
    }

    // If the attribute accepts integers, we can't fail here.
    if ((type_mask & TYPE_INTEGER) == 0) {
      if (out_msg) {
        *out_msg << item << " is not a valid enum";
      }
      return false;
    }
  }

  if ((type_mask & TYPE_FLAGS) != 0 && (actual_type & TYPE_FLAGS) != 0) {
    uint32_t mask = 0u;
    for (const Symbol& s : symbols) {
      mask |= s.value;
    }

    // Check if the flattened data is covered by the flag bit mask.
    // If the attribute accepts integers, we can't fail here.
    if ((mask & flattened_data) == flattened_data) {
      return true;
    } else if ((type_mask & TYPE_INTEGER) == 0) {
      if (out_msg) {
        *out_msg << item << " is not a valid flag";
      }
      return false;
    }
  }

  // Finally check the integer range of the value.
  if ((type_mask & TYPE_INTEGER) != 0 && (actual_type & TYPE_INTEGER) != 0) {
    if (static_cast<int32_t>(flattened_data) < min_int) {
      if (out_msg) {
        *out_msg << item << " is less than minimum integer " << min_int;
      }
      return false;
    } else if (static_cast<int32_t>(flattened_data) > max_int) {
      if (out_msg) {
        *out_msg << item << " is greater than maximum integer " << max_int;
      }
      return false;
    }
  }
  return true;
}

std::ostream& operator<<(std::ostream& out, const Style::Entry& entry) {
  if (entry.key.name) {
    out << entry.key.name.value();
  } else if (entry.key.id) {
    out << entry.key.id.value();
  } else {
    out << "???";
  }
  out << " = " << entry.value;
  return out;
}

template <typename T>
std::vector<T*> ToPointerVec(std::vector<T>& src) {
  std::vector<T*> dst;
  dst.reserve(src.size());
  for (T& in : src) {
    dst.push_back(&in);
  }
  return dst;
}

template <typename T>
std::vector<const T*> ToPointerVec(const std::vector<T>& src) {
  std::vector<const T*> dst;
  dst.reserve(src.size());
  for (const T& in : src) {
    dst.push_back(&in);
  }
  return dst;
}

static bool KeyNameComparator(const Style::Entry* a, const Style::Entry* b) {
  return a->key.name < b->key.name;
}

bool Style::Equals(const Value* value) const {
  const Style* other = ValueCast<Style>(value);
  if (!other) {
    return false;
  }

  if (bool(parent) != bool(other->parent) ||
      (parent && other->parent && !parent.value().Equals(&other->parent.value()))) {
    return false;
  }

  if (entries.size() != other->entries.size()) {
    return false;
  }

  std::vector<const Entry*> sorted_a = ToPointerVec(entries);
  std::sort(sorted_a.begin(), sorted_a.end(), KeyNameComparator);

  std::vector<const Entry*> sorted_b = ToPointerVec(other->entries);
  std::sort(sorted_b.begin(), sorted_b.end(), KeyNameComparator);

  return std::equal(sorted_a.begin(), sorted_a.end(), sorted_b.begin(),
                    [](const Entry* a, const Entry* b) -> bool {
                      return a->key.Equals(&b->key) && a->value->Equals(b->value.get());
                    });
}

void Style::Print(std::ostream* out) const {
  *out << "(style) ";
  if (parent && parent.value().name) {
    const Reference& parent_ref = parent.value();
    if (parent_ref.private_reference) {
      *out << "*";
    }
    *out << parent_ref.name.value();
  }
  *out << " [" << util::Joiner(entries, ", ") << "]";
}

Style::Entry CloneEntry(const Style::Entry& entry, android::StringPool* pool) {
  Style::Entry cloned_entry{entry.key};
  if (entry.value != nullptr) {
    CloningValueTransformer cloner(pool);
    cloned_entry.value = entry.value->Transform(cloner);
  }
  return cloned_entry;
}

void Style::MergeWith(Style* other, android::StringPool* pool) {
  if (other->parent) {
    parent = other->parent;
  }

  // We can't assume that the entries are sorted alphabetically since they're supposed to be
  // sorted by Resource Id. Not all Resource Ids may be set though, so we can't sort and merge
  // them keying off that.
  //
  // Instead, sort the entries of each Style by their name in a separate structure. Then merge
  // those.

  std::vector<Entry*> this_sorted = ToPointerVec(entries);
  std::sort(this_sorted.begin(), this_sorted.end(), KeyNameComparator);

  std::vector<Entry*> other_sorted = ToPointerVec(other->entries);
  std::sort(other_sorted.begin(), other_sorted.end(), KeyNameComparator);

  auto this_iter = this_sorted.begin();
  const auto this_end = this_sorted.end();

  auto other_iter = other_sorted.begin();
  const auto other_end = other_sorted.end();

  std::vector<Entry> merged_entries;
  while (this_iter != this_end) {
    if (other_iter != other_end) {
      if ((*this_iter)->key.name < (*other_iter)->key.name) {
        merged_entries.push_back(std::move(**this_iter));
        ++this_iter;
      } else {
        // The other overrides.
        merged_entries.push_back(CloneEntry(**other_iter, pool));
        if ((*this_iter)->key.name == (*other_iter)->key.name) {
          ++this_iter;
        }
        ++other_iter;
      }
    } else {
      merged_entries.push_back(std::move(**this_iter));
      ++this_iter;
    }
  }

  while (other_iter != other_end) {
    merged_entries.push_back(CloneEntry(**other_iter, pool));
    ++other_iter;
  }

  entries = std::move(merged_entries);
}

bool Array::Equals(const Value* value) const {
  const Array* other = ValueCast<Array>(value);
  if (!other) {
    return false;
  }

  if (elements.size() != other->elements.size()) {
    return false;
  }

  return std::equal(elements.begin(), elements.end(), other->elements.begin(),
                    [](const std::unique_ptr<Item>& a, const std::unique_ptr<Item>& b) -> bool {
                      return a->Equals(b.get());
                    });
}

void Array::Print(std::ostream* out) const {
  *out << "(array) [" << util::Joiner(elements, ", ") << "]";
}

void Array::RemoveFlagDisabledElements() {
  const auto end_iter = elements.end();
  const auto remove_iter = std::stable_partition(
      elements.begin(), end_iter, [](const std::unique_ptr<Item>& item) -> bool {
        return item->GetFlagStatus() != FlagStatus::Disabled;
      });

  elements.erase(remove_iter, end_iter);
}

bool Plural::Equals(const Value* value) const {
  const Plural* other = ValueCast<Plural>(value);
  if (!other) {
    return false;
  }

  auto one_iter = values.begin();
  auto one_end_iter = values.end();
  auto two_iter = other->values.begin();
  for (; one_iter != one_end_iter; ++one_iter, ++two_iter) {
    const std::unique_ptr<Item>& a = *one_iter;
    const std::unique_ptr<Item>& b = *two_iter;
    if (a != nullptr && b != nullptr) {
      if (!a->Equals(b.get())) {
        return false;
      }
    } else if (a != b) {
      return false;
    }
  }
  return true;
}

void Plural::Print(std::ostream* out) const {
  *out << "(plural)";
  if (values[Zero]) {
    *out << " zero=" << *values[Zero];
  }

  if (values[One]) {
    *out << " one=" << *values[One];
  }

  if (values[Two]) {
    *out << " two=" << *values[Two];
  }

  if (values[Few]) {
    *out << " few=" << *values[Few];
  }

  if (values[Many]) {
    *out << " many=" << *values[Many];
  }

  if (values[Other]) {
    *out << " other=" << *values[Other];
  }
}

bool Styleable::Equals(const Value* value) const {
  const Styleable* other = ValueCast<Styleable>(value);
  if (!other) {
    return false;
  }

  if (entries.size() != other->entries.size()) {
    return false;
  }

  return std::equal(entries.begin(), entries.end(), other->entries.begin(),
                    [](const Reference& a, const Reference& b) -> bool {
                      return a.Equals(&b);
                    });
}

void Styleable::Print(std::ostream* out) const {
  *out << "(styleable) "
       << " [" << util::Joiner(entries, ", ") << "]";
}

bool Macro::Equals(const Value* value) const {
  const Macro* other = ValueCast<Macro>(value);
  if (!other) {
    return false;
  }
  return other->raw_value == raw_value && other->style_string.spans == style_string.spans &&
         other->style_string.str == style_string.str &&
         other->untranslatable_sections == untranslatable_sections &&
         other->alias_namespaces == alias_namespaces;
}

void Macro::Print(std::ostream* out) const {
  *out << "(macro) ";
}

bool operator<(const Reference& a, const Reference& b) {
  int cmp = a.name.value_or(ResourceName{}).compare(b.name.value_or(ResourceName{}));
  if (cmp != 0) return cmp < 0;
  return a.id < b.id;
}

bool operator==(const Reference& a, const Reference& b) {
  return a.name == b.name && a.id == b.id;
}

bool operator!=(const Reference& a, const Reference& b) {
  return a.name != b.name || a.id != b.id;
}

struct NameOnlyComparator {
  bool operator()(const Reference& a, const Reference& b) const {
    return a.name < b.name;
  }
};

void Styleable::MergeWith(Styleable* other) {
  // Compare only names, because some References may already have their IDs
  // assigned (framework IDs that don't change).
  std::set<Reference, NameOnlyComparator> references;
  references.insert(entries.begin(), entries.end());
  references.insert(other->entries.begin(), other->entries.end());
  entries.clear();
  entries.reserve(references.size());
  entries.insert(entries.end(), references.begin(), references.end());
}

template <typename T>
std::unique_ptr<T> CopyValueFields(std::unique_ptr<T> new_value, const T* value) {
  new_value->SetSource(value->GetSource());
  new_value->SetComment(value->GetComment());
  new_value->SetFlagStatus(value->GetFlagStatus());
  return new_value;
}

CloningValueTransformer::CloningValueTransformer(android::StringPool* new_pool)
    : ValueTransformer(new_pool) {
}

std::unique_ptr<Reference> CloningValueTransformer::TransformDerived(const Reference* value) {
  return std::make_unique<Reference>(*value);
}

std::unique_ptr<Id> CloningValueTransformer::TransformDerived(const Id* value) {
  return std::make_unique<Id>(*value);
}

std::unique_ptr<RawString> CloningValueTransformer::TransformDerived(const RawString* value) {
  auto new_value = std::make_unique<RawString>(pool_->MakeRef(value->value));
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<String> CloningValueTransformer::TransformDerived(const String* value) {
  auto new_value = std::make_unique<String>(pool_->MakeRef(value->value));
  new_value->untranslatable_sections = value->untranslatable_sections;
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<StyledString> CloningValueTransformer::TransformDerived(const StyledString* value) {
  auto new_value = std::make_unique<StyledString>(pool_->MakeRef(value->value));
  new_value->untranslatable_sections = value->untranslatable_sections;
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<FileReference> CloningValueTransformer::TransformDerived(
    const FileReference* value) {
  auto new_value = std::make_unique<FileReference>(pool_->MakeRef(value->path));
  new_value->file = value->file;
  new_value->type = value->type;
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<BinaryPrimitive> CloningValueTransformer::TransformDerived(
    const BinaryPrimitive* value) {
  return std::make_unique<BinaryPrimitive>(*value);
}

std::unique_ptr<Attribute> CloningValueTransformer::TransformDerived(const Attribute* value) {
  auto new_value = std::make_unique<Attribute>();
  new_value->type_mask = value->type_mask;
  new_value->min_int = value->min_int;
  new_value->max_int = value->max_int;
  for (const Attribute::Symbol& s : value->symbols) {
    new_value->symbols.emplace_back(Attribute::Symbol{
        .symbol = *s.symbol.Transform(*this),
        .value = s.value,
        .type = s.type,
    });
  }
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<Style> CloningValueTransformer::TransformDerived(const Style* value) {
  auto new_value = std::make_unique<Style>();
  new_value->parent = value->parent;
  new_value->parent_inferred = value->parent_inferred;
  for (auto& entry : value->entries) {
    new_value->entries.push_back(Style::Entry{entry.key, entry.value->Transform(*this)});
  }
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<Array> CloningValueTransformer::TransformDerived(const Array* value) {
  auto new_value = std::make_unique<Array>();
  for (auto& item : value->elements) {
    new_value->elements.emplace_back(item->Transform(*this));
  }
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<Plural> CloningValueTransformer::TransformDerived(const Plural* value) {
  auto new_value = std::make_unique<Plural>();
  const size_t count = value->values.size();
  for (size_t i = 0; i < count; i++) {
    if (value->values[i]) {
      new_value->values[i] = value->values[i]->Transform(*this);
    }
  }
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<Styleable> CloningValueTransformer::TransformDerived(const Styleable* value) {
  auto new_value = std::make_unique<Styleable>();
  for (const Reference& s : value->entries) {
    new_value->entries.emplace_back(*s.Transform(*this));
  }
  return CopyValueFields(std::move(new_value), value);
}

std::unique_ptr<Macro> CloningValueTransformer::TransformDerived(const Macro* value) {
  auto new_value = std::make_unique<Macro>(*value);
  return CopyValueFields(std::move(new_value), value);
}

}  // namespace aapt
