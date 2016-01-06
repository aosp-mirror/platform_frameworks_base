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

#ifndef AAPT_COMPILE_PSEUDOLOCALIZE_H
#define AAPT_COMPILE_PSEUDOLOCALIZE_H

#include "ResourceValues.h"
#include "StringPool.h"
#include "util/StringPiece.h"

#include <android-base/macros.h>
#include <memory>

namespace aapt {

class PseudoMethodImpl {
public:
    virtual ~PseudoMethodImpl() {}
    virtual std::u16string start() { return {}; }
    virtual std::u16string end() { return {}; }
    virtual std::u16string text(const StringPiece16& text) = 0;
    virtual std::u16string placeholder(const StringPiece16& text) = 0;
};

class Pseudolocalizer {
public:
    enum class Method {
        kNone,
        kAccent,
        kBidi,
    };

    Pseudolocalizer(Method method);
    void setMethod(Method method);
    std::u16string start() { return mImpl->start(); }
    std::u16string end() { return mImpl->end(); }
    std::u16string text(const StringPiece16& text);
private:
    std::unique_ptr<PseudoMethodImpl> mImpl;
    size_t mLastDepth;
};

} // namespace aapt

#endif /* AAPT_COMPILE_PSEUDOLOCALIZE_H */
