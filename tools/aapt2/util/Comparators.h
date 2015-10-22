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

#ifndef AAPT_UTIL_COMPARATORS_H
#define AAPT_UTIL_COMPARATORS_H

namespace aapt {
namespace cmp {

inline bool lessThan(const ResourceConfigValue& a, const ConfigDescription& b) {
    return a.config < b;
}

} // namespace cmp
} // namespace aapt

#endif /* AAPT_UTIL_COMPARATORS_H */
