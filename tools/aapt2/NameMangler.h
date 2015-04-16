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

#ifndef AAPT_NAME_MANGLER_H
#define AAPT_NAME_MANGLER_H

#include <string>

namespace aapt {

struct NameMangler {
    /**
     * Mangles the name in `outName` with the `package` and stores the mangled
     * result in `outName`. The mangled name should contain symbols that are
     * illegal to define in XML, so that there will never be name mangling
     * collisions.
     */
    static void mangle(const std::u16string& package, std::u16string* outName) {
        *outName = package + u"$" + *outName;
    }

    /**
     * Unmangles the name in `outName`, storing the correct name back in `outName`
     * and the package in `outPackage`. Returns true if the name was unmangled or
     * false if the name was never mangled to begin with.
     */
    static bool unmangle(std::u16string* outName, std::u16string* outPackage) {
        size_t pivot = outName->find(u'$');
        if (pivot == std::string::npos) {
            return false;
        }

        outPackage->assign(outName->data(), pivot);
        outName->assign(outName->data() + pivot + 1, outName->size() - (pivot + 1));
        return true;
    }
};

} // namespace aapt

#endif // AAPT_NAME_MANGLER_H
