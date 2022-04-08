/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <jni.h>

#define LOG_TAG "SystemFont"

#include <android/font.h>
#include <android/font_matcher.h>
#include <android/system_fonts.h>

#include <memory>
#include <string>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <libxml/tree.h>
#include <log/log.h>
#include <sys/stat.h>
#include <unistd.h>

#include <hwui/MinikinSkia.h>
#include <minikin/FontCollection.h>
#include <minikin/LocaleList.h>
#include <minikin/SystemFonts.h>

struct XmlCharDeleter {
    void operator()(xmlChar* b) { xmlFree(b); }
};

struct XmlDocDeleter {
    void operator()(xmlDoc* d) { xmlFreeDoc(d); }
};

using XmlCharUniquePtr = std::unique_ptr<xmlChar, XmlCharDeleter>;
using XmlDocUniquePtr = std::unique_ptr<xmlDoc, XmlDocDeleter>;

struct ParserState {
    xmlNode* mFontNode = nullptr;
    XmlCharUniquePtr mLocale;
};

struct ASystemFontIterator {
    XmlDocUniquePtr mXmlDoc;
    ParserState state;

    // The OEM customization XML.
    XmlDocUniquePtr mCustomizationXmlDoc;
};

struct AFont {
    std::string mFilePath;
    std::unique_ptr<std::string> mLocale;
    uint16_t mWeight;
    bool mItalic;
    uint32_t mCollectionIndex;
    std::vector<std::pair<uint32_t, float>> mAxes;
};

struct AFontMatcher {
    minikin::FontStyle mFontStyle;
    uint32_t mLocaleListId = 0;  // 0 is reserved for empty locale ID.
    bool mFamilyVariant = AFAMILY_VARIANT_DEFAULT;
};

static_assert(static_cast<uint32_t>(AFAMILY_VARIANT_DEFAULT) ==
              static_cast<uint32_t>(minikin::FamilyVariant::DEFAULT));
static_assert(static_cast<uint32_t>(AFAMILY_VARIANT_COMPACT) ==
              static_cast<uint32_t>(minikin::FamilyVariant::COMPACT));
static_assert(static_cast<uint32_t>(AFAMILY_VARIANT_ELEGANT) ==
              static_cast<uint32_t>(minikin::FamilyVariant::ELEGANT));

namespace {

std::string xmlTrim(const std::string& in) {
    if (in.empty()) {
        return in;
    }
    const char XML_SPACES[] = "\u0020\u000D\u000A\u0009";
    const size_t start = in.find_first_not_of(XML_SPACES);  // inclusive
    if (start == std::string::npos) {
        return "";
    }
    const size_t end = in.find_last_not_of(XML_SPACES);     // inclusive
    if (end == std::string::npos) {
        return "";
    }
    return in.substr(start, end - start + 1 /* +1 since end is inclusive */);
}

const xmlChar* FAMILY_TAG = BAD_CAST("family");
const xmlChar* FONT_TAG = BAD_CAST("font");
const xmlChar* LOCALE_ATTR_NAME = BAD_CAST("lang");

xmlNode* firstElement(xmlNode* node, const xmlChar* tag) {
    for (xmlNode* child = node->children; child; child = child->next) {
        if (xmlStrEqual(child->name, tag)) {
            return child;
        }
    }
    return nullptr;
}

xmlNode* nextSibling(xmlNode* node, const xmlChar* tag) {
    while ((node = node->next) != nullptr) {
        if (xmlStrEqual(node->name, tag)) {
            return node;
        }
    }
    return nullptr;
}

void copyFont(const XmlDocUniquePtr& xmlDoc, const ParserState& state, AFont* out,
              const std::string& pathPrefix) {
    xmlNode* fontNode = state.mFontNode;
    XmlCharUniquePtr filePathStr(
            xmlNodeListGetString(xmlDoc.get(), fontNode->xmlChildrenNode, 1));
    out->mFilePath = pathPrefix + xmlTrim(
            std::string(filePathStr.get(), filePathStr.get() + xmlStrlen(filePathStr.get())));

    const xmlChar* WEIGHT_ATTR_NAME = BAD_CAST("weight");
    XmlCharUniquePtr weightStr(xmlGetProp(fontNode, WEIGHT_ATTR_NAME));
    out->mWeight = weightStr ?
            strtol(reinterpret_cast<const char*>(weightStr.get()), nullptr, 10) : 400;

    const xmlChar* STYLE_ATTR_NAME = BAD_CAST("style");
    const xmlChar* ITALIC_ATTR_VALUE = BAD_CAST("italic");
    XmlCharUniquePtr styleStr(xmlGetProp(fontNode, STYLE_ATTR_NAME));
    out->mItalic = styleStr ? xmlStrEqual(styleStr.get(), ITALIC_ATTR_VALUE) : false;

    const xmlChar* INDEX_ATTR_NAME = BAD_CAST("index");
    XmlCharUniquePtr indexStr(xmlGetProp(fontNode, INDEX_ATTR_NAME));
    out->mCollectionIndex =  indexStr ?
            strtol(reinterpret_cast<const char*>(indexStr.get()), nullptr, 10) : 0;

    out->mLocale.reset(
            state.mLocale ?
            new std::string(reinterpret_cast<const char*>(state.mLocale.get()))
            : nullptr);

    const xmlChar* TAG_ATTR_NAME = BAD_CAST("tag");
    const xmlChar* STYLEVALUE_ATTR_NAME = BAD_CAST("stylevalue");
    const xmlChar* AXIS_TAG = BAD_CAST("axis");
    out->mAxes.clear();
    for (xmlNode* axis = firstElement(fontNode, AXIS_TAG); axis;
            axis = nextSibling(axis, AXIS_TAG)) {
        XmlCharUniquePtr tagStr(xmlGetProp(axis, TAG_ATTR_NAME));
        if (!tagStr || xmlStrlen(tagStr.get()) != 4) {
            continue;  // Tag value must be 4 char string
        }

        XmlCharUniquePtr styleValueStr(xmlGetProp(axis, STYLEVALUE_ATTR_NAME));
        if (!styleValueStr) {
            continue;
        }

        uint32_t tag =
            static_cast<uint32_t>(tagStr.get()[0] << 24) |
            static_cast<uint32_t>(tagStr.get()[1] << 16) |
            static_cast<uint32_t>(tagStr.get()[2] << 8) |
            static_cast<uint32_t>(tagStr.get()[3]);
        float styleValue = strtod(reinterpret_cast<const char*>(styleValueStr.get()), nullptr);
        out->mAxes.push_back(std::make_pair(tag, styleValue));
    }
}

bool isFontFileAvailable(const std::string& filePath) {
    std::string fullPath = filePath;
    struct stat st = {};
    if (stat(fullPath.c_str(), &st) != 0) {
        return false;
    }
    return S_ISREG(st.st_mode);
}

bool findFirstFontNode(const XmlDocUniquePtr& doc, ParserState* state) {
    xmlNode* familySet = xmlDocGetRootElement(doc.get());
    if (familySet == nullptr) {
        return false;
    }
    xmlNode* family = firstElement(familySet, FAMILY_TAG);
    if (family == nullptr) {
        return false;
    }
    state->mLocale.reset(xmlGetProp(family, LOCALE_ATTR_NAME));

    xmlNode* font = firstElement(family, FONT_TAG);
    while (font == nullptr) {
        family = nextSibling(family, FAMILY_TAG);
        if (family == nullptr) {
            return false;
        }
        font = firstElement(family, FONT_TAG);
    }
    state->mFontNode = font;
    return font != nullptr;
}

}  // namespace

ASystemFontIterator* ASystemFontIterator_open() {
    std::unique_ptr<ASystemFontIterator> ite(new ASystemFontIterator());
    ite->mXmlDoc.reset(xmlReadFile("/system/etc/fonts.xml", nullptr, 0));
    ite->mCustomizationXmlDoc.reset(xmlReadFile("/product/etc/fonts_customization.xml", nullptr, 0));
    return ite.release();
}

void ASystemFontIterator_close(ASystemFontIterator* ite) {
    delete ite;
}

AFontMatcher* _Nonnull AFontMatcher_create() {
    return new AFontMatcher();
}

void AFontMatcher_destroy(AFontMatcher* matcher) {
    delete matcher;
}

void AFontMatcher_setStyle(
        AFontMatcher* _Nonnull matcher,
        uint16_t weight,
        bool italic) {
    matcher->mFontStyle = minikin::FontStyle(
            weight, static_cast<minikin::FontStyle::Slant>(italic));
}

void AFontMatcher_setLocales(
        AFontMatcher* _Nonnull matcher,
        const char* _Nonnull languageTags) {
    matcher->mLocaleListId = minikin::registerLocaleList(languageTags);
}

void AFontMatcher_setFamilyVariant(AFontMatcher* _Nonnull matcher, uint32_t familyVariant) {
    matcher->mFamilyVariant = familyVariant;
}

AFont* _Nonnull AFontMatcher_match(
        const AFontMatcher* _Nonnull matcher,
        const char* _Nonnull familyName,
        const uint16_t* _Nonnull text,
        const uint32_t textLength,
        uint32_t* _Nullable runLength) {
    std::shared_ptr<minikin::FontCollection> fc =
            minikin::SystemFonts::findFontCollection(familyName);
    std::vector<minikin::FontCollection::Run> runs = fc->itemize(
                minikin::U16StringPiece(text, textLength),
                matcher->mFontStyle,
                matcher->mLocaleListId,
                static_cast<minikin::FamilyVariant>(matcher->mFamilyVariant),
                1  /* maxRun */);

    const minikin::Font* font = runs[0].fakedFont.font;
    std::unique_ptr<AFont> result = std::make_unique<AFont>();
    const android::MinikinFontSkia* minikinFontSkia =
            reinterpret_cast<android::MinikinFontSkia*>(font->typeface().get());
    result->mFilePath = minikinFontSkia->getFilePath();
    result->mWeight = font->style().weight();
    result->mItalic = font->style().slant() == minikin::FontStyle::Slant::ITALIC;
    result->mCollectionIndex = minikinFontSkia->GetFontIndex();
    const std::vector<minikin::FontVariation>& axes = minikinFontSkia->GetAxes();
    result->mAxes.reserve(axes.size());
    for (auto axis : axes) {
        result->mAxes.push_back(std::make_pair(axis.axisTag, axis.value));
    }
    if (runLength != nullptr) {
        *runLength = runs[0].end;
    }
    return result.release();
}

bool findNextFontNode(const XmlDocUniquePtr& xmlDoc, ParserState* state) {
    if (state->mFontNode == nullptr) {
        if (!xmlDoc) {
            return false;  // Already at the end.
        } else {
            // First time to query font.
            return findFirstFontNode(xmlDoc, state);
        }
    } else {
        xmlNode* nextNode = nextSibling(state->mFontNode, FONT_TAG);
        while (nextNode == nullptr) {
            xmlNode* family = nextSibling(state->mFontNode->parent, FAMILY_TAG);
            if (family == nullptr) {
                break;
            }
            state->mLocale.reset(xmlGetProp(family, LOCALE_ATTR_NAME));
            nextNode = firstElement(family, FONT_TAG);
        }
        state->mFontNode = nextNode;
        return nextNode != nullptr;
    }
}

AFont* ASystemFontIterator_next(ASystemFontIterator* ite) {
    LOG_ALWAYS_FATAL_IF(ite == nullptr, "nullptr has passed as iterator argument");
    if (ite->mXmlDoc) {
        if (!findNextFontNode(ite->mXmlDoc, &ite->state)) {
            // Reached end of the XML file. Continue OEM customization.
            ite->mXmlDoc.reset();
        } else {
            std::unique_ptr<AFont> font = std::make_unique<AFont>();
            copyFont(ite->mXmlDoc, ite->state, font.get(), "/system/fonts/");
            if (!isFontFileAvailable(font->mFilePath)) {
                return ASystemFontIterator_next(ite);
            }
            return font.release();
        }
    }
    if (ite->mCustomizationXmlDoc) {
        // TODO: Filter only customizationType="new-named-family"
        if (!findNextFontNode(ite->mCustomizationXmlDoc, &ite->state)) {
            // Reached end of the XML file. Finishing
            ite->mCustomizationXmlDoc.reset();
            return nullptr;
        } else {
            std::unique_ptr<AFont> font = std::make_unique<AFont>();
            copyFont(ite->mCustomizationXmlDoc, ite->state, font.get(), "/product/fonts/");
            if (!isFontFileAvailable(font->mFilePath)) {
                return ASystemFontIterator_next(ite);
            }
            return font.release();
        }
    }
    return nullptr;
}

void AFont_close(AFont* font) {
    delete font;
}

const char* AFont_getFontFilePath(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed as font argument");
    return font->mFilePath.c_str();
}

uint16_t AFont_getWeight(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed as font argument");
    return font->mWeight;
}

bool AFont_isItalic(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed as font argument");
    return font->mItalic;
}

const char* AFont_getLocale(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed to font argument");
    return font->mLocale ? font->mLocale->c_str() : nullptr;
}

size_t AFont_getCollectionIndex(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed to font argument");
    return font->mCollectionIndex;
}

size_t AFont_getAxisCount(const AFont* font) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed to font argument");
    return font->mAxes.size();
}

uint32_t AFont_getAxisTag(const AFont* font, uint32_t axisIndex) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed to font argument");
    LOG_ALWAYS_FATAL_IF(axisIndex >= font->mAxes.size(),
                        "given axis index is out of bounds. (< %zd", font->mAxes.size());
    return font->mAxes[axisIndex].first;
}

float AFont_getAxisValue(const AFont* font, uint32_t axisIndex) {
    LOG_ALWAYS_FATAL_IF(font == nullptr, "nullptr has passed to font argument");
    LOG_ALWAYS_FATAL_IF(axisIndex >= font->mAxes.size(),
                        "given axis index is out of bounds. (< %zd", font->mAxes.size());
    return font->mAxes[axisIndex].second;
}
