/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <utils/Log.h>

#include "SkData.h"
#include "SkFontMgr.h"
#include "SkRefCnt.h"
#include "SkStream.h"
#include "SkTypeface.h"

#include "hwui/MinikinSkia.h"
#include "hwui/Typeface.h"
#include "utils/TypefaceUtils.h"

using namespace android;

namespace {

constexpr char kRobotoVariable[] = "/system/fonts/Roboto-Regular.ttf";

constexpr char kRegularFont[] = "/system/fonts/NotoSerif-Regular.ttf";
constexpr char kBoldFont[] = "/system/fonts/NotoSerif-Bold.ttf";
constexpr char kItalicFont[] = "/system/fonts/NotoSerif-Italic.ttf";
constexpr char kBoldItalicFont[] = "/system/fonts/NotoSerif-BoldItalic.ttf";

void unmap(const void* ptr, void* context) {
    void* p = const_cast<void*>(ptr);
    size_t len = reinterpret_cast<size_t>(context);
    munmap(p, len);
}

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

std::vector<std::shared_ptr<minikin::FontFamily>> makeSingleFamlyVector(const char* fileName) {
    return std::vector<std::shared_ptr<minikin::FontFamily>>({buildFamily(fileName)});
}

TEST(TypefaceTest, resolveDefault_and_setDefaultTest) {
    std::unique_ptr<Typeface> regular(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE,
            nullptr /* fallback */));
    EXPECT_EQ(regular.get(), Typeface::resolveDefault(regular.get()));

    // Keep the original to restore it later.
    const Typeface* old = Typeface::resolveDefault(nullptr);
    ASSERT_NE(nullptr, old);

    Typeface::setDefault(regular.get());
    EXPECT_EQ(regular.get(), Typeface::resolveDefault(nullptr));

    Typeface::setDefault(old);  // Restore to the original.
}

TEST(TypefaceTest, createWithDifferentBaseWeight) {
    std::unique_ptr<Typeface> bold(Typeface::createWithDifferentBaseWeight(nullptr, 700));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, bold->fAPIStyle);

    std::unique_ptr<Typeface> light(Typeface::createWithDifferentBaseWeight(nullptr, 300));
    EXPECT_EQ(300, light->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, light->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, light->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_fromRegular) {
    // In Java, Typeface.create(Typeface.DEFAULT, Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(nullptr, Typeface::kNormal));
    EXPECT_EQ(400, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java, Typeface.create(Typeface.DEFAULT, Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(nullptr, Typeface::kBold));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(nullptr, Typeface::kItalic));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java, Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(Typeface::createRelative(nullptr, Typeface::kBoldItalic));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_BoldBase) {
    std::unique_ptr<Typeface> base(Typeface::createWithDifferentBaseWeight(nullptr, 700));

    // In Java, Typeface.create(Typeface.create("sans-serif-bold"),
    // Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(base.get(), Typeface::kNormal));
    EXPECT_EQ(700, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-bold"),
    // Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(base.get(), Typeface::kBold));
    EXPECT_EQ(1000, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-bold"),
    // Typeface.ITALIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(base.get(), Typeface::kItalic));
    EXPECT_EQ(700, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-bold"),
    // Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(
            Typeface::createRelative(base.get(), Typeface::kBoldItalic));
    EXPECT_EQ(1000, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_LightBase) {
    std::unique_ptr<Typeface> base(Typeface::createWithDifferentBaseWeight(nullptr, 300));

    // In Java, Typeface.create(Typeface.create("sans-serif-light"),
    // Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(base.get(), Typeface::kNormal));
    EXPECT_EQ(300, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-light"),
    // Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(base.get(), Typeface::kBold));
    EXPECT_EQ(600, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-light"),
    // Typeface.ITLIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(base.get(), Typeface::kItalic));
    EXPECT_EQ(300, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java, Typeface.create(Typeface.create("sans-serif-light"),
    // Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(
            Typeface::createRelative(base.get(), Typeface::kBoldItalic));
    EXPECT_EQ(600, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_fromBoldStyled) {
    std::unique_ptr<Typeface> base(Typeface::createRelative(nullptr, Typeface::kBold));

    // In Java, Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
    // Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(base.get(), Typeface::kNormal));
    EXPECT_EQ(400, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
    // Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(base.get(), Typeface::kBold));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
    // Typeface.ITALIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(base.get(), Typeface::kItalic));
    EXPECT_EQ(400, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
    // Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(
            Typeface::createRelative(base.get(), Typeface::kBoldItalic));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_fromItalicStyled) {
    std::unique_ptr<Typeface> base(Typeface::createRelative(nullptr, Typeface::kItalic));

    // In Java,
    // Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
    // Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(base.get(), Typeface::kNormal));
    EXPECT_EQ(400, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java, Typeface.create(Typeface.create(Typeface.DEFAULT,
    // Typeface.ITALIC), Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(base.get(), Typeface::kBold));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java,
    // Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
    // Typeface.ITALIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(base.get(), Typeface::kItalic));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // Typeface.create(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC),
    // Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(
            Typeface::createRelative(base.get(), Typeface::kBoldItalic));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createRelativeTest_fromSpecifiedStyled) {
    std::unique_ptr<Typeface> base(Typeface::createAbsolute(nullptr, 400, false));

    // In Java,
    // Typeface typeface = new Typeface.Builder(invalid).setFallback("sans-serif")
    //     .setWeight(700).setItalic(false).build();
    // Typeface.create(typeface, Typeface.NORMAL);
    std::unique_ptr<Typeface> normal(Typeface::createRelative(base.get(), Typeface::kNormal));
    EXPECT_EQ(400, normal->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, normal->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, normal->fAPIStyle);

    // In Java,
    // Typeface typeface = new Typeface.Builder(invalid).setFallback("sans-serif")
    //     .setWeight(700).setItalic(false).build();
    // Typeface.create(typeface, Typeface.BOLD);
    std::unique_ptr<Typeface> bold(Typeface::createRelative(base.get(), Typeface::kBold));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java,
    // Typeface typeface = new Typeface.Builder(invalid).setFallback("sans-serif")
    //     .setWeight(700).setItalic(false).build();
    // Typeface.create(typeface, Typeface.ITALIC);
    std::unique_ptr<Typeface> italic(Typeface::createRelative(base.get(), Typeface::kItalic));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // Typeface typeface = new Typeface.Builder(invalid).setFallback("sans-serif")
    //     .setWeight(700).setItalic(false).build();
    // Typeface.create(typeface, Typeface.BOLD_ITALIC);
    std::unique_ptr<Typeface> boldItalic(
            Typeface::createRelative(base.get(), Typeface::kBoldItalic));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);
}

TEST(TypefaceTest, createAbsolute) {
    // In Java,
    // new
    // Typeface.Builder(invalid).setFallback("sans-serif").setWeight(400).setItalic(false)
    //     .build();
    std::unique_ptr<Typeface> regular(Typeface::createAbsolute(nullptr, 400, false));
    EXPECT_EQ(400, regular->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, regular->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, regular->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder(invalid).setFallback("sans-serif").setWeight(700).setItalic(false)
    //     .build();
    std::unique_ptr<Typeface> bold(Typeface::createAbsolute(nullptr, 700, false));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder(invalid).setFallback("sans-serif").setWeight(400).setItalic(true)
    //     .build();
    std::unique_ptr<Typeface> italic(Typeface::createAbsolute(nullptr, 400, true));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder(invalid).setFallback("sans-serif").setWeight(700).setItalic(true)
    //     .build();
    std::unique_ptr<Typeface> boldItalic(Typeface::createAbsolute(nullptr, 700, true));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kBoldItalic, boldItalic->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder(invalid).setFallback("sans-serif").setWeight(1100).setItalic(true)
    //     .build();
    std::unique_ptr<Typeface> over1000(Typeface::createAbsolute(nullptr, 1100, false));
    EXPECT_EQ(1000, over1000->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, over1000->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, over1000->fAPIStyle);
}

TEST(TypefaceTest, createFromFamilies_Single) {
    // In Java, new
    // Typeface.Builder("Roboto-Regular.ttf").setWeight(400).setItalic(false).build();
    std::unique_ptr<Typeface> regular(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), 400, false, nullptr /* fallback */));
    EXPECT_EQ(400, regular->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, regular->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, regular->fAPIStyle);

    // In Java, new
    // Typeface.Builder("Roboto-Regular.ttf").setWeight(700).setItalic(false).build();
    std::unique_ptr<Typeface> bold(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), 700, false, nullptr /* fallback */));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, new
    // Typeface.Builder("Roboto-Regular.ttf").setWeight(400).setItalic(true).build();
    std::unique_ptr<Typeface> italic(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), 400, true, nullptr /* fallback */));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder("Roboto-Regular.ttf").setWeight(700).setItalic(true).build();
    std::unique_ptr<Typeface> boldItalic(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), 700, true, nullptr /* fallback */));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java,
    // new
    // Typeface.Builder("Roboto-Regular.ttf").setWeight(1100).setItalic(false).build();
    std::unique_ptr<Typeface> over1000(Typeface::createFromFamilies(
            makeSingleFamlyVector(kRobotoVariable), 1100, false, nullptr /* fallback */));
    EXPECT_EQ(1000, over1000->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, over1000->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, over1000->fAPIStyle);
}

TEST(TypefaceTest, createFromFamilies_Single_resolveByTable) {
    // In Java, new Typeface.Builder("Family-Regular.ttf").build();
    std::unique_ptr<Typeface> regular(
            Typeface::createFromFamilies(makeSingleFamlyVector(kRegularFont), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    EXPECT_EQ(400, regular->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, regular->fStyle.slant());
    EXPECT_EQ(Typeface::kNormal, regular->fAPIStyle);

    // In Java, new Typeface.Builder("Family-Bold.ttf").build();
    std::unique_ptr<Typeface> bold(
            Typeface::createFromFamilies(makeSingleFamlyVector(kBoldFont), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    EXPECT_EQ(700, bold->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, bold->fStyle.slant());
    EXPECT_EQ(Typeface::kBold, bold->fAPIStyle);

    // In Java, new Typeface.Builder("Family-Italic.ttf").build();
    std::unique_ptr<Typeface> italic(
            Typeface::createFromFamilies(makeSingleFamlyVector(kItalicFont), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    EXPECT_EQ(400, italic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, italic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);

    // In Java, new Typeface.Builder("Family-BoldItalic.ttf").build();
    std::unique_ptr<Typeface> boldItalic(Typeface::createFromFamilies(
            makeSingleFamlyVector(kBoldItalicFont), RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE,
            nullptr /* fallback */));
    EXPECT_EQ(700, boldItalic->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::ITALIC, boldItalic->fStyle.slant());
    EXPECT_EQ(Typeface::kItalic, italic->fAPIStyle);
}

TEST(TypefaceTest, createFromFamilies_Family) {
    std::vector<std::shared_ptr<minikin::FontFamily>> families = {
            buildFamily(kRegularFont), buildFamily(kBoldFont), buildFamily(kItalicFont),
            buildFamily(kBoldItalicFont)};
    std::unique_ptr<Typeface> typeface(
            Typeface::createFromFamilies(std::move(families), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    EXPECT_EQ(400, typeface->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, typeface->fStyle.slant());
}

TEST(TypefaceTest, createFromFamilies_Family_withoutRegular) {
    std::vector<std::shared_ptr<minikin::FontFamily>> families = {
            buildFamily(kBoldFont), buildFamily(kItalicFont), buildFamily(kBoldItalicFont)};
    std::unique_ptr<Typeface> typeface(
            Typeface::createFromFamilies(std::move(families), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    EXPECT_EQ(700, typeface->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, typeface->fStyle.slant());
}

TEST(TypefaceTest, createFromFamilies_Family_withFallback) {
    std::vector<std::shared_ptr<minikin::FontFamily>> fallbackFamilies = {
            buildFamily(kBoldFont), buildFamily(kItalicFont), buildFamily(kBoldItalicFont)};
    std::unique_ptr<Typeface> fallback(
            Typeface::createFromFamilies(std::move(fallbackFamilies), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, nullptr /* fallback */));
    std::unique_ptr<Typeface> regular(
            Typeface::createFromFamilies(makeSingleFamlyVector(kRegularFont), RESOLVE_BY_FONT_TABLE,
                                         RESOLVE_BY_FONT_TABLE, fallback.get()));
    EXPECT_EQ(400, regular->fStyle.weight());
    EXPECT_EQ(minikin::FontStyle::Slant::UPRIGHT, regular->fStyle.slant());
}

}  // namespace
