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

#ifndef AAPT_VALUE_VISITOR_H
#define AAPT_VALUE_VISITOR_H

#include "ResourceTable.h"
#include "ResourceValues.h"

namespace aapt {

// Visits a value and invokes the appropriate method based on its type.
// Does not traverse into compound types. Use ValueVisitor for that.
class ValueVisitor {
 public:
  virtual ~ValueVisitor() = default;

  virtual void VisitAny(Value* value) {}
  virtual void VisitItem(Item* value) { VisitAny(value); }
  virtual void Visit(Reference* value) { VisitItem(value); }
  virtual void Visit(RawString* value) { VisitItem(value); }
  virtual void Visit(String* value) { VisitItem(value); }
  virtual void Visit(StyledString* value) { VisitItem(value); }
  virtual void Visit(FileReference* value) { VisitItem(value); }
  virtual void Visit(Id* value) { VisitItem(value); }
  virtual void Visit(BinaryPrimitive* value) { VisitItem(value); }

  virtual void Visit(Attribute* value) { VisitAny(value); }
  virtual void Visit(Style* value) { VisitAny(value); }
  virtual void Visit(Array* value) { VisitAny(value); }
  virtual void Visit(Plural* value) { VisitAny(value); }
  virtual void Visit(Styleable* value) { VisitAny(value); }
  virtual void Visit(Macro* value) {
    VisitAny(value);
  }
};

// Const version of ValueVisitor.
class ConstValueVisitor {
 public:
  virtual ~ConstValueVisitor() = default;

  virtual void VisitAny(const Value* value) {
  }
  virtual void VisitItem(const Item* value) {
    VisitAny(value);
  }
  virtual void Visit(const Reference* value) {
    VisitItem(value);
  }
  virtual void Visit(const RawString* value) {
    VisitItem(value);
  }
  virtual void Visit(const String* value) {
    VisitItem(value);
  }
  virtual void Visit(const StyledString* value) {
    VisitItem(value);
  }
  virtual void Visit(const FileReference* value) {
    VisitItem(value);
  }
  virtual void Visit(const Id* value) {
    VisitItem(value);
  }
  virtual void Visit(const BinaryPrimitive* value) {
    VisitItem(value);
  }

  virtual void Visit(const Attribute* value) {
    VisitAny(value);
  }
  virtual void Visit(const Style* value) {
    VisitAny(value);
  }
  virtual void Visit(const Array* value) {
    VisitAny(value);
  }
  virtual void Visit(const Plural* value) {
    VisitAny(value);
  }
  virtual void Visit(const Styleable* value) {
    VisitAny(value);
  }
  virtual void Visit(const Macro* value) {
    VisitAny(value);
  }
};

// NOLINT, do not add parentheses around T.
#define DECL_VISIT_COMPOUND_VALUE(T)                   \
  virtual void Visit(T* value) override { /* NOLINT */ \
    VisitSubValues(value);                             \
  }

// Visits values, and if they are compound values, descends into their components as well.
struct DescendingValueVisitor : public ValueVisitor {
  // The compiler will think we're hiding an overload, when we actually intend
  // to call into RawValueVisitor. This will expose the visit methods in the
  // super class so the compiler knows we are trying to call them.
  using ValueVisitor::Visit;

  void VisitSubValues(Attribute* attribute) {
    for (Attribute::Symbol& symbol : attribute->symbols) {
      Visit(&symbol.symbol);
    }
  }

  void VisitSubValues(Style* style) {
    if (style->parent) {
      Visit(&style->parent.value());
    }

    for (Style::Entry& entry : style->entries) {
      Visit(&entry.key);
      entry.value->Accept(this);
    }
  }

  void VisitSubValues(Array* array) {
    for (std::unique_ptr<Item>& item : array->elements) {
      item->Accept(this);
    }
  }

  void VisitSubValues(Plural* plural) {
    for (std::unique_ptr<Item>& item : plural->values) {
      if (item) {
        item->Accept(this);
      }
    }
  }

  void VisitSubValues(Styleable* styleable) {
    for (Reference& reference : styleable->entries) {
      Visit(&reference);
    }
  }

  DECL_VISIT_COMPOUND_VALUE(Attribute);
  DECL_VISIT_COMPOUND_VALUE(Style);
  DECL_VISIT_COMPOUND_VALUE(Array);
  DECL_VISIT_COMPOUND_VALUE(Plural);
  DECL_VISIT_COMPOUND_VALUE(Styleable);
};

// Do not use directly. Helper struct for dyn_cast.
template <typename T>
struct DynCastVisitor : public ConstValueVisitor {
  const T* value = nullptr;

  void Visit(const T* v) override {
    value = v;
  }
};

// Specialization that checks if the value is an Item.
template <>
struct DynCastVisitor<Item> : public ConstValueVisitor {
  const Item* value = nullptr;

  void VisitItem(const Item* item) override {
    value = item;
  }
};

// Returns a valid pointer to T if the value is an instance of T. Returns nullptr if value is
// nullptr of if value is not an instance of T.
template <typename T>
const T* ValueCast(const Value* value) {
  if (!value) {
    return nullptr;
  }
  DynCastVisitor<T> visitor;
  value->Accept(&visitor);
  return visitor.value;
}

// Non-const version of ValueCast.
template <typename T>
T* ValueCast(Value* value) {
  return const_cast<T*>(ValueCast<T>(static_cast<const Value*>(value)));
}

inline void VisitAllValuesInPackage(ResourceTablePackage* pkg, ValueVisitor* visitor) {
  for (auto& type : pkg->types) {
    for (auto& entry : type->entries) {
      for (auto& config_value : entry->values) {
        config_value->value->Accept(visitor);
      }
    }
  }
}

inline void VisitAllValuesInTable(ResourceTable* table, ValueVisitor* visitor) {
  for (auto& pkg : table->packages) {
    VisitAllValuesInPackage(pkg.get(), visitor);
  }
}

}  // namespace aapt

#endif  // AAPT_VALUE_VISITOR_H
