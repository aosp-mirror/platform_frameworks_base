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

#ifndef AAPT_PNG_H
#define AAPT_PNG_H

#include "util/BigBuffer.h"
#include "Diagnostics.h"
#include "Source.h"

#include <iostream>
#include <string>

namespace aapt {

struct PngOptions {
    int grayScaleTolerance = 0;
};

class Png {
public:
    Png(IDiagnostics* diag) : mDiag(diag) {
    }

    bool process(const Source& source, std::istream* input, BigBuffer* outBuffer,
                 const PngOptions& options);

private:
    IDiagnostics* mDiag;
};

} // namespace aapt

#endif // AAPT_PNG_H
