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

#define PREVENT_COPY_AND_ASSIGN(Type) \
    private: \
        Type(const Type&); \
        void operator=(const Type&)

#define DESCRIPTION_TYPE(Type) \
        int compare(const Type& rhs) const { return memcmp(this, &rhs, sizeof(Type));} \
        bool operator==(const Type& other) const { return compare(other) == 0; } \
        bool operator!=(const Type& other) const { return compare(other) != 0; } \
        friend inline int strictly_order_type(const Type& lhs, const Type& rhs) { return lhs.compare(rhs) < 0; } \
        friend inline int compare_type(const Type& lhs, const Type& rhs) { return lhs.compare(rhs); } \
        friend inline hash_t hash_type(const Type& entry) { return entry.hash(); }

#endif /* MACROS_H */
