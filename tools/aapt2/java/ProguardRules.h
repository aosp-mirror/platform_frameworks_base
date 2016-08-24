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

#ifndef AAPT_PROGUARD_RULES_H
#define AAPT_PROGUARD_RULES_H

#include "Resource.h"
#include "Source.h"
#include "xml/XmlDom.h"

#include <map>
#include <ostream>
#include <set>
#include <string>

namespace aapt {
namespace proguard {

class KeepSet {
public:
    inline void addClass(const Source& source, const std::u16string& className) {
        mKeepSet[className].insert(source);
    }

    inline void addMethod(const Source& source, const std::u16string& methodName) {
        mKeepMethodSet[methodName].insert(source);
    }

private:
    friend bool writeKeepSet(std::ostream* out, const KeepSet& keepSet);

    std::map<std::u16string, std::set<Source>> mKeepSet;
    std::map<std::u16string, std::set<Source>> mKeepMethodSet;
};

bool collectProguardRulesForManifest(const Source& source, xml::XmlResource* res, KeepSet* keepSet);
bool collectProguardRules(const Source& source, xml::XmlResource* res, KeepSet* keepSet);

bool writeKeepSet(std::ostream* out, const KeepSet& keepSet);

} // namespace proguard
} // namespace aapt

#endif // AAPT_PROGUARD_RULES_H
