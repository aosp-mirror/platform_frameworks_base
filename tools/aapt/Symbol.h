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

#ifndef AAPT_SYMBOL_H
#define AAPT_SYMBOL_H

#include <utils/String8.h>
#include <utils/String16.h>

#include "ConfigDescription.h"
#include "SourcePos.h"

/**
 * A resource symbol, not attached to any configuration or context.
 */
struct Symbol {
    inline Symbol();
    inline Symbol(const android::String16& p, const android::String16& t, const android::String16& n, uint32_t i);
    inline android::String8 toString() const;
    inline bool operator<(const Symbol& rhs) const;

    android::String16 package;
    android::String16 type;
    android::String16 name;
    uint32_t id;

};

/**
 * A specific definition of a symbol, defined with a configuration and a definition site.
 */
struct SymbolDefinition {
    inline SymbolDefinition();
    inline SymbolDefinition(const Symbol& s, const ConfigDescription& c, const SourcePos& src);
    inline bool operator<(const SymbolDefinition& rhs) const;

    Symbol symbol;
    ConfigDescription config;
    SourcePos source;
};

//
// Implementations
//

Symbol::Symbol() {
}

Symbol::Symbol(const android::String16& p, const android::String16& t, const android::String16& n, uint32_t i)
    : package(p)
    , type(t)
    , name(n)
    , id(i) {
}

android::String8 Symbol::toString() const {
    return android::String8::format("%s:%s/%s (0x%08x)",
            android::String8(package).c_str(),
            android::String8(type).c_str(),
            android::String8(name).c_str(),
            (int) id);
}

bool Symbol::operator<(const Symbol& rhs) const {
    return (package < rhs.package) || (type < rhs.type) || (name < rhs.name) || (id < rhs.id);
}

SymbolDefinition::SymbolDefinition() {
}

SymbolDefinition::SymbolDefinition(const Symbol& s, const ConfigDescription& c, const SourcePos& src)
    : symbol(s)
    , config(c)
    , source(src) {
}

bool SymbolDefinition::operator<(const SymbolDefinition& rhs) const {
    return (symbol < rhs.symbol) || (config < rhs.config) || (source < rhs.source);
}

#endif // AAPT_SYMBOL_H
