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

#include <set>
#include <string>

#include "Resource.h"
#include "util/Maybe.h"

namespace aapt {

struct NameManglerPolicy {
  /**
   * Represents the package we are trying to build. References pointing
   * to this package are not mangled, and mangled references inherit this
   * package name.
   */
  std::string target_package_name;

  /**
   * We must know which references to mangle, and which to keep (android vs.
   * com.android.support).
   */
  std::set<std::string> packages_to_mangle;
};

class NameMangler {
 public:
  explicit NameMangler(NameManglerPolicy policy) : policy_(policy) {}

  Maybe<ResourceName> MangleName(const ResourceName& name) {
    if (policy_.target_package_name == name.package ||
        policy_.packages_to_mangle.count(name.package) == 0) {
      return {};
    }

    std::string mangled_entry_name = MangleEntry(name.package, name.entry);
    return ResourceName(policy_.target_package_name, name.type,
                        mangled_entry_name);
  }

  bool ShouldMangle(const std::string& package) const {
    if (package.empty() || policy_.target_package_name == package) {
      return false;
    }
    return policy_.packages_to_mangle.count(package) != 0;
  }

  const std::string& GetTargetPackageName() const { return policy_.target_package_name; }

  /**
   * Returns a mangled name that is a combination of `name` and `package`.
   * The mangled name should contain symbols that are illegal to define in XML,
   * so that there will never be name mangling collisions.
   */
  static std::string MangleEntry(const std::string& package, const std::string& name) {
    return package + "$" + name;
  }

  /**
   * Unmangles the name in `outName`, storing the correct name back in `outName`
   * and the package in `outPackage`. Returns true if the name was unmangled or
   * false if the name was never mangled to begin with.
   */
  static bool Unmangle(std::string* out_name, std::string* out_package) {
    size_t pivot = out_name->find('$');
    if (pivot == std::string::npos) {
      return false;
    }

    out_package->assign(out_name->data(), pivot);
    std::string new_name = out_name->substr(pivot + 1);
    *out_name = std::move(new_name);
    return true;
  }

 private:
  NameManglerPolicy policy_;
};

}  // namespace aapt

#endif  // AAPT_NAME_MANGLER_H
