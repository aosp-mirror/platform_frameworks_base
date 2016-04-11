/*
 * Copyright (C) 2014 The Android Open Source Project
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
#ifndef MACROS_H
#define MACROS_H

#include <type_traits>

#define PREVENT_COPY_AND_ASSIGN(Type) \
    private: \
        Type(const Type&) = delete; \
        void operator=(const Type&) = delete

#define HASHABLE_TYPE(Type) \
        bool operator==(const Type& other) const; \
        hash_t hash() const; \
        bool operator!=(const Type& other) const { return !(*this == other); } \
        friend inline hash_t hash_type(const Type& entry) { return entry.hash(); }

#define REQUIRE_COMPATIBLE_LAYOUT(Type) \
        static_assert(std::is_standard_layout<Type>::value, \
        #Type " must have standard layout")

#define WARN_UNUSED_RESULT \
    __attribute__((warn_unused_result))

#endif /* MACROS_H */
