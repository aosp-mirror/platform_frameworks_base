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

#include "ValueTransformer.h"

#include "ResourceValues.h"

namespace aapt {

#define VALUE_CREATE_VALUE_DECL(T)                                          \
  std::unique_ptr<Value> ValueTransformer::TransformValue(const T* value) { \
    return TransformDerived(value);                                         \
  }

#define VALUE_CREATE_ITEM_DECL(T)                                           \
  std::unique_ptr<Item> ValueTransformer::TransformItem(const T* value) {   \
    return TransformDerived(value);                                         \
  }                                                                         \
  std::unique_ptr<Value> ValueTransformer::TransformValue(const T* value) { \
    return TransformItem(value);                                            \
  }

VALUE_CREATE_ITEM_DECL(Id);
VALUE_CREATE_ITEM_DECL(Reference);
VALUE_CREATE_ITEM_DECL(RawString);
VALUE_CREATE_ITEM_DECL(String);
VALUE_CREATE_ITEM_DECL(StyledString);
VALUE_CREATE_ITEM_DECL(FileReference);
VALUE_CREATE_ITEM_DECL(BinaryPrimitive);

VALUE_CREATE_VALUE_DECL(Attribute);
VALUE_CREATE_VALUE_DECL(Style);
VALUE_CREATE_VALUE_DECL(Array);
VALUE_CREATE_VALUE_DECL(Plural);
VALUE_CREATE_VALUE_DECL(Styleable);
VALUE_CREATE_VALUE_DECL(Macro);

}  // namespace aapt