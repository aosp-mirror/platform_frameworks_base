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

#ifndef AAPT_VALUE_TRANSFORMER_IMPL_H
#define AAPT_VALUE_TRANSFORMER_IMPL_H

namespace aapt {

inline ValueTransformer::ValueTransformer(StringPool* new_pool) : pool_(new_pool) {
}

template <typename Derived, typename Base>
inline std::unique_ptr<Derived> TransformableValue<Derived, Base>::Transform(
    ValueTransformer& transformer) const {
  return transformer.TransformDerived(static_cast<const Derived*>(this));
}

template <typename Derived, typename Base>
Value* TransformableValue<Derived, Base>::TransformValueImpl(ValueTransformer& transformer) const {
  auto self = static_cast<const Derived*>(this);
  auto transformed = transformer.TransformValue(self);
  return transformed.release();
}

template <typename Derived, typename Base>
Item* TransformableItem<Derived, Base>::TransformItemImpl(ValueTransformer& transformer) const {
  auto self = static_cast<const Derived*>(this);
  auto transformed = transformer.TransformItem(self);
  return transformed.release();
}

}  // namespace aapt

#endif  // AAPT_VALUE_TRANSFORMER_IMPL_H