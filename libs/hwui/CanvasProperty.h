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
#ifndef CANVASPROPERTY_H
#define CANVASPROPERTY_H

#include <utils/RefBase.h>

#include "utils/Macros.h"

#include <SkPaint.h>

namespace android {
namespace uirenderer {

class CanvasPropertyPrimitive : public VirtualLightRefBase {
    PREVENT_COPY_AND_ASSIGN(CanvasPropertyPrimitive);
public:
    CanvasPropertyPrimitive(float initialValue) : value(initialValue) {}

    float value;
};

class CanvasPropertyPaint : public VirtualLightRefBase {
    PREVENT_COPY_AND_ASSIGN(CanvasPropertyPaint);
public:
    CanvasPropertyPaint(const SkPaint& initialValue) : value(initialValue) {}

    SkPaint value;
};

} /* namespace uirenderer */
} /* namespace android */

#endif /* CANVASPROPERTY_H */
