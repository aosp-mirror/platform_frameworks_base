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
#ifndef PROTOHELPERS_H
#define PROTOHELPERS_H

#include "Rect.h"
#include "protos/hwui.pb.h"

namespace android {
namespace uirenderer {

void set(proto::RectF* dest, const Rect& src) {
    dest->set_left(src.left);
    dest->set_top(src.top);
    dest->set_right(src.right);
    dest->set_bottom(src.bottom);
}

void set(std::string* dest, const SkPath& src) {
    size_t size = src.writeToMemory(nullptr);
    dest->resize(size);
    src.writeToMemory(&*dest->begin());
}

} // namespace uirenderer
} // namespace android

#endif // PROTOHELPERS_H
