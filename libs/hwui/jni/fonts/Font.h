/*
 * Copyright (C) 2020 The Android Open Source Project
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
#ifndef FONTS_FONT_H_
#define FONTS_FONT_H_

#include <minikin/FontVariation.h>
#include <minikin/MinikinFont.h>
#include <SkRefCnt.h>

#include <string_view>
#include <vector>

class SkData;

namespace android {

namespace fonts {

std::shared_ptr<minikin::MinikinFont> createMinikinFontSkia(
        sk_sp<SkData>&& data, std::string_view fontPath, const void *fontPtr, size_t fontSize,
        int ttcIndex, const std::vector<minikin::FontVariation>& axes);

int getNewSourceId();

} // namespace fonts

} // namespace android

#endif /* FONTS_FONT_H_ */
