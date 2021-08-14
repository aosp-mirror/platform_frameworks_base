/*
 * Copyright (C) 2021 The Android Open Source Project
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

#ifndef AAPT_VALUE_TRANSFORMER_H
#define AAPT_VALUE_TRANSFORMER_H

#include <memory>

#include "StringPool.h"

namespace aapt {

class Value;
struct Item;
struct Reference;
struct Id;
struct RawString;
struct String;
struct StyledString;
struct FileReference;
struct BinaryPrimitive;
struct Attribute;
struct Style;
struct Array;
struct Plural;
struct Styleable;
struct Macro;

#define AAPT_TRANSFORM_VALUE(T)                                    \
  virtual std::unique_ptr<T> TransformDerived(const T* value) = 0; \
  virtual std::unique_ptr<Value> TransformValue(const T* value);

#define AAPT_TRANSFORM_ITEM(T)                                 \
  virtual std::unique_ptr<Item> TransformItem(const T* value); \
  AAPT_TRANSFORM_VALUE(T)

/**
 * An interface for consuming a Value type and transforming it into another Value.
 *
 * The interface defines 2 methods for each type (T) that inherits from TransformableValue:
 *   std::unique_ptr<T> TransformDerived(const T*)
 *   std::unique_ptr<Value> TransformValue(const T*)
 *
 * The interface defines 3 method for each type (T) that inherits from TransformableItem:
 *   std::unique_ptr<T> TransformDerived(const T*)
 *   std::unique_ptr<Item> TransformItem(const T*)
 *   std::unique_ptr<Value> TransformValue(const T*)
 *
 * TransformDerived is invoked when Transform is invoked on the derived type T.
 * TransformItem is invoked when Transform is invoked on an Item type.
 * TransformValue is invoked when Transform is invoked on a Value type.
 *
 *  ValueTransformerImpl transformer(&string_pool);
 *  T* derived = ...;
 *  std::unique_ptr<T> new_type = derived->Transform(transformer); // Invokes TransformDerived
 *
 *  Item* item = derived;
 *  std::unique_ptr<Item> new_item = item->TransformItem(transformer); // Invokes TransformItem
 *
 *  Value* value = item;
 *  std::unique_ptr<Value> new_value = value->TransformValue(transformer); // Invokes TransformValue
 *
 * For types that inherit from AbstractTransformableItem, the default implementation of
 * TransformValue invokes TransformItem which invokes TransformDerived.
 *
 * For types that inherit from AbstractTransformableValue, the default implementation of
 * TransformValue invokes TransformDerived.
 */
struct ValueTransformer {
  // `new_pool` is the new StringPool that newly created Values should use for string storing string
  // values.
  explicit ValueTransformer(StringPool* new_pool);
  virtual ~ValueTransformer() = default;

  AAPT_TRANSFORM_ITEM(Id);
  AAPT_TRANSFORM_ITEM(Reference);
  AAPT_TRANSFORM_ITEM(RawString);
  AAPT_TRANSFORM_ITEM(String);
  AAPT_TRANSFORM_ITEM(StyledString);
  AAPT_TRANSFORM_ITEM(FileReference);
  AAPT_TRANSFORM_ITEM(BinaryPrimitive);

  AAPT_TRANSFORM_VALUE(Attribute);
  AAPT_TRANSFORM_VALUE(Style);
  AAPT_TRANSFORM_VALUE(Array);
  AAPT_TRANSFORM_VALUE(Plural);
  AAPT_TRANSFORM_VALUE(Styleable);
  AAPT_TRANSFORM_VALUE(Macro);

 protected:
  StringPool* const pool_;
};

#undef AAPT_TRANSFORM_VALUE
#undef AAPT_TRANSFORM_ITEM

template <typename Derived, typename Base>
struct TransformableValue : public Base {
  // Transform this Derived into another Derived using the transformer.
  std::unique_ptr<Derived> Transform(ValueTransformer& transformer) const;

 private:
  Value* TransformValueImpl(ValueTransformer& transformer) const override;
};

template <typename Derived, typename Base>
struct TransformableItem : public TransformableValue<Derived, Base> {
 private:
  Item* TransformItemImpl(ValueTransformer& transformer) const override;
};

}  // namespace aapt

// Implementation
#include "ValueTransformer_inline.h"

#endif  // AAPT_VALUE_TRANSFORMER_H