/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <fcntl.h>
#include <flag_macros.h>
#include <gtest/gtest.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <utils/Log.h>

#include "SkAlphaType.h"
#include "SkBitmap.h"
#include "SkData.h"
#include "SkFontMgr.h"
#include "SkImageInfo.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypeface.h"
#include "SkiaCanvas.h"
#include "hwui/Bitmap.h"
#include "hwui/DrawTextFunctor.h"
#include "hwui/MinikinSkia.h"
#include "hwui/MinikinUtils.h"
#include "hwui/Paint.h"
#include "hwui/Typeface.h"
#include "utils/TypefaceUtils.h"

using namespace android;

namespace {

constexpr char kRobotoVariable[] = "/system/fonts/Roboto-Regular.ttf";
constexpr char kJPFont[] = "/system/fonts/NotoSansCJK-Regular.ttc";

// The underline position and thickness are cames from post table.
constexpr float ROBOTO_POSITION_EM = 150.0 / 2048.0;
constexpr float ROBOTO_THICKNESS_EM = 100.0 / 2048.0;
constexpr float NOTO_CJK_POSITION_EM = 125.0 / 1000.0;
constexpr float NOTO_CJK_THICKNESS_EM = 50.0 / 1000.0;

void unmap(const void* ptr, void* context) {
    void* p = const_cast<void*>(ptr);
    size_t len = reinterpret_cast<size_t>(context);
    munmap(p, len);
}

// Create a font family from a single font file.
std::shared_ptr<minikin::FontFamily> buildFamily(const char* fileName) {
    int fd = open(fileName, O_RDONLY);
    LOG_ALWAYS_FATAL_IF(fd == -1, "Failed to open file %s", fileName);
    struct stat st = {};
    LOG_ALWAYS_FATAL_IF(fstat(fd, &st) == -1, "Failed to stat file %s", fileName);
    void* data = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    sk_sp<SkData> skData =
            SkData::MakeWithProc(data, st.st_size, unmap, reinterpret_cast<void*>(st.st_size));
    std::unique_ptr<SkStreamAsset> fontData(new SkMemoryStream(skData));
    sk_sp<SkFontMgr> fm = android::FreeTypeFontMgr();
    sk_sp<SkTypeface> typeface(fm->makeFromStream(std::move(fontData)));
    LOG_ALWAYS_FATAL_IF(typeface == nullptr, "Failed to make typeface from %s", fileName);
    std::shared_ptr<minikin::MinikinFont> font =
            std::make_shared<MinikinFontSkia>(std::move(typeface), 0, data, st.st_size, fileName, 0,
                                              std::vector<minikin::FontVariation>());
    std::vector<std::shared_ptr<minikin::Font>> fonts;
    fonts.push_back(minikin::Font::Builder(font).build());
    return minikin::FontFamily::create(std::move(fonts));
}

// Create a typeface from roboto and NotoCJK.
Typeface* makeTypeface() {
    return Typeface::createFromFamilies(
            std::vector<std::shared_ptr<minikin::FontFamily>>(
                    {buildFamily(kRobotoVariable), buildFamily(kJPFont)}),
            RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE, nullptr /* fallback */);
}

// Execute a text layout.
minikin::Layout doLayout(const std::vector<uint16_t> text, Paint paint, Typeface* typeface) {
    return MinikinUtils::doLayout(&paint, minikin::Bidi::LTR, typeface, text.data(), text.size(),
                                  0 /* start */, text.size(), 0, text.size(), nullptr);
}

DrawTextFunctor processFunctor(const std::vector<uint16_t>& text, Paint* paint) {
    // Create canvas
    SkImageInfo info = SkImageInfo::Make(1, 1, kN32_SkColorType, kOpaque_SkAlphaType);
    sk_sp<Bitmap> bitmap = Bitmap::allocateHeapBitmap(info);
    SkBitmap skBitmap;
    bitmap->getSkBitmap(&skBitmap);
    SkiaCanvas canvas(skBitmap);

    // Create minikin::Layout
    std::unique_ptr<Typeface> typeface(makeTypeface());
    minikin::Layout layout = doLayout(text, *paint, typeface.get());

    DrawTextFunctor f(layout, &canvas, *paint, 0, 0, layout.getAdvance());
    MinikinUtils::forFontRun(layout, paint, f);
    return f;
}

TEST(UnderlineTest, Roboto) {
    float textSize = 100;
    Paint paint;
    paint.getSkFont().setSize(textSize);
    paint.setUnderline(true);
    // the text is "abc"
    DrawTextFunctor functor = processFunctor({0x0061, 0x0062, 0x0063}, &paint);

    EXPECT_EQ(ROBOTO_POSITION_EM * textSize, functor.getUnderlinePosition());
    EXPECT_EQ(ROBOTO_THICKNESS_EM * textSize, functor.getUnderlineThickness());
}

TEST(UnderlineTest, NotoCJK) {
    float textSize = 100;
    Paint paint;
    paint.getSkFont().setSize(textSize);
    paint.setUnderline(true);
    // The text is あいう in Japanese
    DrawTextFunctor functor = processFunctor({0x3042, 0x3044, 0x3046}, &paint);

    EXPECT_EQ(NOTO_CJK_POSITION_EM * textSize, functor.getUnderlinePosition());
    EXPECT_EQ(NOTO_CJK_THICKNESS_EM * textSize, functor.getUnderlineThickness());
}

TEST(UnderlineTest, Mixture) {
    float textSize = 100;
    Paint paint;
    paint.getSkFont().setSize(textSize);
    paint.setUnderline(true);
    // The text is aいc. The only middle of the character is Japanese.
    DrawTextFunctor functor = processFunctor({0x0061, 0x3044, 0x0063}, &paint);

    // We use the bottom, thicker line as underline. Here, use Noto's one.
    EXPECT_EQ(NOTO_CJK_POSITION_EM * textSize, functor.getUnderlinePosition());
    EXPECT_EQ(NOTO_CJK_THICKNESS_EM * textSize, functor.getUnderlineThickness());
}
}  // namespace
