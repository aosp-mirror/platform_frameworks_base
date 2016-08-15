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

#include "Resource.h"
#include "util/Maybe.h"

#include <set>
#include <string>

namespace aapt {

struct NameManglerPolicy {
    /**
     * Represents the package we are trying to build. References pointing
     * to this package are not mangled, and mangled references inherit this package name.
     */
    std::string targetPackageName;

    /**
     * We must know which references to mangle, and which to keep (android vs. com.android.support).
     */
    std::set<std::string> packagesToMangle;
};

class NameMangler {
private:
    NameManglerPolicy mPolicy;

public:
    explicit NameMangler(NameManglerPolicy policy) : mPolicy(policy) {
    }

    Maybe<ResourceName> mangleName(const ResourceName& name) {
        if (mPolicy.targetPackageName == name.package ||
                mPolicy.packagesToMangle.count(name.package) == 0) {
            return {};
        }

        std::string mangledEntryName = mangleEntry(name.package, name.entry);
        return ResourceName(mPolicy.targetPackageName, name.type, mangledEntryName);
    }

    bool shouldMangle(const std::string& package) const {
        if (package.empty() || mPolicy.targetPackageName == package) {
            return false;
        }
        return mPolicy.packagesToMangle.count(package) != 0;
    }

    /**
     * Returns a mangled name that is a combination of `name` and `package`.
     * The mangled name should contain symbols that are illegal to define in XML,
     * so that there will never be name mangling collisions.
     */
    static std::string mangleEntry(const std::string& package, const std::string& name) {
        return package + "$" + name;
    }

    /**
     * Unmangles the name in `outName`, storing the correct name back in `outName`
     * and the package in `outPackage`. Returns true if the name was unmangled or
     * false if the name was never mangled to begin with.
     */
    static bool unmangle(std::string* outName, std::string* outPackage) {
        size_t pivot = outName->find('$');
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
