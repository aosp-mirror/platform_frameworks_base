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

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <algorithm>

#include <core_jni_helpers.h>
#include <minikin/Hyphenator.h>

namespace android {

static std::string buildFileName(const std::string& locale) {
    constexpr char SYSTEM_HYPHENATOR_PREFIX[] = "/system/usr/hyphen-data/hyph-";
    constexpr char SYSTEM_HYPHENATOR_SUFFIX[] = ".hyb";
    std::string lowerLocale;
    lowerLocale.reserve(locale.size());
    std::transform(locale.begin(), locale.end(), std::back_inserter(lowerLocale), ::tolower);
    return SYSTEM_HYPHENATOR_PREFIX + lowerLocale + SYSTEM_HYPHENATOR_SUFFIX;
}

static std::pair<const uint8_t*, uint32_t> mmapPatternFile(const std::string& locale) {
    const std::string hyFilePath = buildFileName(locale);
    const int fd = open(hyFilePath.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd == -1) {
        return std::make_pair(nullptr, 0); // Open failed.
    }

    struct stat st = {};
    if (fstat(fd, &st) == -1) {  // Unlikely to happen.
        close(fd);
        return std::make_pair(nullptr, 0);
    }

    void* ptr = mmap(nullptr, st.st_size, PROT_READ, MAP_SHARED, fd, 0 /* offset */);
    close(fd);
    if (ptr == MAP_FAILED) {
        return std::make_pair(nullptr, 0);
    }
    return std::make_pair(reinterpret_cast<const uint8_t*>(ptr), st.st_size);
}

static void addHyphenatorWithoutPatternFile(const std::string& locale, int minPrefix,
        int minSuffix) {
    minikin::addHyphenator(locale,
                           minikin::Hyphenator::loadBinary(nullptr, 0, minPrefix, minSuffix,
                                                           locale));
}

static void addHyphenator(const std::string& locale, int minPrefix, int minSuffix) {
    auto [ptr, size] = mmapPatternFile(locale);
    if (ptr == nullptr) {
        ALOGE("Unable to find pattern file or unable to map it for %s", locale.c_str());
        return;
    }
    minikin::addHyphenator(locale,
                           minikin::Hyphenator::loadBinary(ptr, size, minPrefix, minSuffix,
                                                           locale));
}

static void addHyphenatorAlias(const std::string& from, const std::string& to) {
    minikin::addHyphenatorAlias(from, to);
}

static void init() {
    // TODO: Confirm that these are the best values. Various sources suggest (1, 1), but that
    // appears too small.
    constexpr int INDIC_MIN_PREFIX = 2;
    constexpr int INDIC_MIN_SUFFIX = 2;

    addHyphenator("af", 1, 1);  // Afrikaans
    addHyphenator("am", 1, 1);  // Amharic
    addHyphenator("as", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Assamese
    addHyphenator("be", 2, 2);  // Belarusian
    addHyphenator("bg", 2, 2);  // Bulgarian
    addHyphenator("bn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Bengali
    addHyphenator("cs", 2, 2);  // Czech
    addHyphenator("cu", 1, 2);  // Church Slavonic
    addHyphenator("cy", 2, 3);  // Welsh
    addHyphenator("da", 2, 2);  // Danish
    addHyphenator("de-1901", 2, 2);  // German 1901 orthography
    addHyphenator("de-1996", 2, 2);  // German 1996 orthography
    addHyphenator("de-CH-1901", 2, 2);  // Swiss High German 1901 orthography
    addHyphenator("el", 1, 1);  // Greek
    addHyphenator("en-GB", 2, 3);  // British English
    addHyphenator("en-US", 2, 3);  // American English
    addHyphenator("es", 2, 2);  // Spanish
    addHyphenator("et", 2, 3);  // Estonian
    addHyphenator("eu", 2, 2);  // Basque
    addHyphenator("fr", 2, 3);  // French
    addHyphenator("ga", 2, 3);  // Irish
    addHyphenator("gl", 2, 2);  // Galician
    addHyphenator("gu", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Gujarati
    addHyphenator("hi", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Hindi
    addHyphenator("hr", 2, 2);  // Croatian
    addHyphenator("hu", 2, 2);  // Hungarian
    // texhyphen sources say Armenian may be (1, 2); but that it needs confirmation.
    // Going with a more conservative value of (2, 2) for now.
    addHyphenator("hy", 2, 2);  // Armenian
    addHyphenator("it", 2, 2);  // Italian
    addHyphenator("ka", 1, 2);  // Georgian
    addHyphenator("kn", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Kannada
    addHyphenator("la", 2, 2);  // Latin
    addHyphenator("lt", 2, 2);  // Lithuanian
    addHyphenator("lv", 2, 2);  // Latvian
    addHyphenator("ml", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Malayalam
    addHyphenator("mn-Cyrl", 2, 2);  // Mongolian in Cyrillic script
    addHyphenator("mr", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Marathi
    addHyphenator("nb", 2, 2);  // Norwegian Bokmål
    addHyphenator("nl", 2, 2);  // Dutch
    addHyphenator("nn", 2, 2);  // Norwegian Nynorsk
    addHyphenator("or", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Oriya
    addHyphenator("pa", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Punjabi
    addHyphenator("pl", 2, 2);  // Polish
    addHyphenator("pt", 2, 3);  // Portuguese
    addHyphenator("ru", 2, 2);  // Russian
    addHyphenator("sk", 2, 2);  // Slovak
    addHyphenator("sl", 2, 2);  // Slovenian
    addHyphenator("sq", 2, 2);  // Albanian
    addHyphenator("sv", 1, 2);  // Swedish
    addHyphenator("ta", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Tamil
    addHyphenator("te", INDIC_MIN_PREFIX, INDIC_MIN_SUFFIX);  // Telugu
    addHyphenator("tk", 2, 2);  // Turkmen
    addHyphenator("uk", 2, 2);  // Ukrainian
    addHyphenator("und-Ethi", 1, 1);  // Any language in Ethiopic script

    // Following two hyphenators do not have pattern files but there is some special logic based on
    // language.
    addHyphenatorWithoutPatternFile("ca", 2, 2);  // Catalan

    // English locales that fall back to en-US. The data is from CLDR. It's all English locales,
    // minus the locales whose parent is en-001 (from supplementalData.xml, under <parentLocales>).
    // TODO: Figure out how to get this from ICU.
    addHyphenatorAlias("en-AS", "en-US");  // English (American Samoa)
    addHyphenatorAlias("en-GU", "en-US");  // English (Guam)
    addHyphenatorAlias("en-MH", "en-US");  // English (Marshall Islands)
    addHyphenatorAlias("en-MP", "en-US");  // English (Northern Mariana Islands)
    addHyphenatorAlias("en-PR", "en-US");  // English (Puerto Rico)
    addHyphenatorAlias("en-UM", "en-US");  // English (United States Minor Outlying Islands)
    addHyphenatorAlias("en-VI", "en-US");  // English (Virgin Islands)

    // All English locales other than those falling back to en-US are mapped to en-GB.
    addHyphenatorAlias("en", "en-GB");

    // For German, we're assuming the 1996 (and later) orthography by default.
    addHyphenatorAlias("de", "de-1996");
    // Liechtenstein uses the Swiss hyphenation rules for the 1901 orthography.
    addHyphenatorAlias("de-LI-1901", "de-CH-1901");

    // Norwegian is very probably Norwegian Bokmål.
    addHyphenatorAlias("no", "nb");

    // Use mn-Cyrl. According to CLDR's likelySubtags.xml, mn is most likely to be mn-Cyrl.
    addHyphenatorAlias("mn", "mn-Cyrl");  // Mongolian

    // Fall back to Ethiopic script for languages likely to be written in Ethiopic.
    // Data is from CLDR's likelySubtags.xml.
    // TODO: Convert this to a mechanism using ICU4J's ULocale#addLikelySubtags().
    addHyphenatorAlias("am", "und-Ethi");  // Amharic
    addHyphenatorAlias("byn", "und-Ethi");  // Blin
    addHyphenatorAlias("gez", "und-Ethi");  // Geʻez
    addHyphenatorAlias("ti", "und-Ethi");  // Tigrinya
    addHyphenatorAlias("wal", "und-Ethi");  // Wolaytta

    // Use Hindi as a fallback hyphenator for all languages written in Devanagari, etc. This makes
    // sense because our Indic patterns are not really linguistic, but script-based.
    addHyphenatorAlias("und-Beng", "bn");  // Bengali
    addHyphenatorAlias("und-Deva", "hi");  // Devanagari -> Hindi
    addHyphenatorAlias("und-Gujr", "gu");  // Gujarati
    addHyphenatorAlias("und-Guru", "pa");  // Gurmukhi -> Punjabi
    addHyphenatorAlias("und-Knda", "kn");  // Kannada
    addHyphenatorAlias("und-Mlym", "ml");  // Malayalam
    addHyphenatorAlias("und-Orya", "or");  // Oriya
    addHyphenatorAlias("und-Taml", "ta");  // Tamil
    addHyphenatorAlias("und-Telu", "te");  // Telugu
}

static const JNINativeMethod gMethods[] = {
    {"nInit", "()V", (void*) init},
};

int register_android_text_Hyphenator(JNIEnv* env) {
    return RegisterMethodsOrDie(env, "android/text/Hyphenator", gMethods, NELEM(gMethods));
}

}  // namespace android
