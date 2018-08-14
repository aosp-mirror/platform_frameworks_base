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

#include <androidfw/LocaleData.h>
#include <androidfw/ResourceTypes.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <gtest/gtest.h>
namespace android {

TEST(ConfigLocaleTest, packAndUnpack2LetterLanguage) {
     ResTable_config config;
     config.packLanguage("en");

     EXPECT_EQ('e', config.language[0]);
     EXPECT_EQ('n', config.language[1]);

     char out[4] = {1, 1, 1, 1};
     config.unpackLanguage(out);
     EXPECT_EQ('e', out[0]);
     EXPECT_EQ('n', out[1]);
     EXPECT_EQ(0, out[2]);
     EXPECT_EQ(0, out[3]);

     memset(out, 1, sizeof(out));
     config.locale = 0;
     config.unpackLanguage(out);
     EXPECT_EQ(0, out[0]);
     EXPECT_EQ(0, out[1]);
     EXPECT_EQ(0, out[2]);
     EXPECT_EQ(0, out[3]);
}

TEST(ConfigLocaleTest, packAndUnpack2LetterRegion) {
     ResTable_config config;
     config.packRegion("US");

     EXPECT_EQ('U', config.country[0]);
     EXPECT_EQ('S', config.country[1]);

     char out[4] = {1, 1, 1, 1};
     config.unpackRegion(out);
     EXPECT_EQ('U', out[0]);
     EXPECT_EQ('S', out[1]);
     EXPECT_EQ(0, out[2]);
     EXPECT_EQ(0, out[3]);
}

TEST(ConfigLocaleTest, packAndUnpack3LetterLanguage) {
     ResTable_config config;
     config.packLanguage("eng");

     // 1-00110-01 101-00100
     EXPECT_EQ('\x99', config.language[0]);
     EXPECT_EQ('\xA4', config.language[1]);

     char out[4] = {1, 1, 1, 1};
     config.unpackLanguage(out);
     EXPECT_EQ('e', out[0]);
     EXPECT_EQ('n', out[1]);
     EXPECT_EQ('g', out[2]);
     EXPECT_EQ(0, out[3]);
}

TEST(ConfigLocaleTest, packAndUnpack3LetterLanguageAtOffset16) {
     ResTable_config config;
     config.packLanguage("tgp");

     // We had a bug where we would accidentally mask
     // the 5th bit of both bytes
     //
     // packed[0] = 1011 1100
     // packed[1] = 1101 0011
     //
     // which is equivalent to:
     // 1  [0]   [1]   [2]
     // 1-01111-00110-10011
     EXPECT_EQ(char(0xbc), config.language[0]);
     EXPECT_EQ(char(0xd3), config.language[1]);

     char out[4] = {1, 1, 1, 1};
     config.unpackLanguage(out);
     EXPECT_EQ('t', out[0]);
     EXPECT_EQ('g', out[1]);
     EXPECT_EQ('p', out[2]);
     EXPECT_EQ(0, out[3]);
}

TEST(ConfigLocaleTest, packAndUnpack3LetterRegion) {
     ResTable_config config;
     config.packRegion("419");

     char out[4] = {1, 1, 1, 1};
     config.unpackRegion(out);

     EXPECT_EQ('4', out[0]);
     EXPECT_EQ('1', out[1]);
     EXPECT_EQ('9', out[2]);
}

/* static */ void fillIn(const char* lang, const char* country,
        const char* script, const char* variant, ResTable_config* out) {
     memset(out, 0, sizeof(ResTable_config));
     if (lang != NULL) {
         out->packLanguage(lang);
     }

     if (country != NULL) {
         out->packRegion(country);
     }

     if (script != NULL) {
         memcpy(out->localeScript, script, 4);
         out->localeScriptWasComputed = false;
     } else {
         out->computeScript();
         out->localeScriptWasComputed = true;
     }

     if (variant != NULL) {
         memcpy(out->localeVariant, variant, strlen(variant));
     }
}

TEST(ConfigLocaleTest, IsMoreSpecificThan) {
    ResTable_config l;
    ResTable_config r;

    fillIn("en", NULL, NULL, NULL, &l);
    fillIn(NULL, NULL, NULL, NULL, &r);

    EXPECT_TRUE(l.isMoreSpecificThan(r));
    EXPECT_FALSE(r.isMoreSpecificThan(l));

    fillIn("eng", NULL, NULL, NULL, &l);
    EXPECT_TRUE(l.isMoreSpecificThan(r));
    EXPECT_FALSE(r.isMoreSpecificThan(l));

    fillIn("eng", "419", NULL, NULL, &r);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("en", NULL, NULL, NULL, &l);
    fillIn("en", "US", NULL, NULL, &r);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("en", "US", NULL, NULL, &l);
    fillIn("en", "US", "Latn", NULL, &r);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("en", "US", NULL, NULL, &l);
    fillIn("en", "US", NULL, "POSIX", &r);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("en", "US", "Latn", NULL, &l);
    fillIn("en", "US", NULL, "POSIX", &r);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("ar", "EG", NULL, NULL, &l);
    fillIn("ar", "EG", NULL, NULL, &r);
    memcpy(&r.localeNumberingSystem, "latn", 4);
    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_TRUE(r.isMoreSpecificThan(l));

    fillIn("en", "US", NULL, NULL, &l);
    fillIn("es", "ES", NULL, NULL, &r);

    EXPECT_FALSE(l.isMoreSpecificThan(r));
    EXPECT_FALSE(r.isMoreSpecificThan(l));
}

TEST(ConfigLocaleTest, setLocale) {
    ResTable_config test;
    test.setBcp47Locale("en-US");
    EXPECT_EQ('e', test.language[0]);
    EXPECT_EQ('n', test.language[1]);
    EXPECT_EQ('U', test.country[0]);
    EXPECT_EQ('S', test.country[1]);
    EXPECT_TRUE(test.localeScriptWasComputed);
    EXPECT_EQ(0, memcmp("Latn", test.localeScript, 4));
    EXPECT_EQ(0, test.localeVariant[0]);
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("eng-419");
    char out[4] = {1, 1, 1, 1};
    test.unpackLanguage(out);
    EXPECT_EQ('e', out[0]);
    EXPECT_EQ('n', out[1]);
    EXPECT_EQ('g', out[2]);
    EXPECT_EQ(0, out[3]);
    memset(out, 1, 4);
    test.unpackRegion(out);
    EXPECT_EQ('4', out[0]);
    EXPECT_EQ('1', out[1]);
    EXPECT_EQ('9', out[2]);
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("en-Latn-419");
    EXPECT_EQ('e', test.language[0]);
    EXPECT_EQ('n', test.language[1]);
    EXPECT_EQ(0, memcmp("Latn", test.localeScript, 4));
    EXPECT_FALSE(test.localeScriptWasComputed);
    memset(out, 1, 4);
    test.unpackRegion(out);
    EXPECT_EQ('4', out[0]);
    EXPECT_EQ('1', out[1]);
    EXPECT_EQ('9', out[2]);
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("de-1901");
    memset(out, 1, 4);
    test.unpackLanguage(out);
    EXPECT_EQ('d', out[0]);
    EXPECT_EQ('e', out[1]);
    EXPECT_EQ('\0', out[2]);
    EXPECT_TRUE(test.localeScriptWasComputed);
    EXPECT_EQ(0, memcmp("Latn", test.localeScript, 4));
    memset(out, 1, 4);
    test.unpackRegion(out);
    EXPECT_EQ('\0', out[0]);
    EXPECT_EQ(0, strcmp("1901", test.localeVariant));
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("de-Latn-1901");
    memset(out, 1, 4);
    test.unpackLanguage(out);
    EXPECT_EQ('d', out[0]);
    EXPECT_EQ('e', out[1]);
    EXPECT_EQ('\0', out[2]);
    EXPECT_FALSE(test.localeScriptWasComputed);
    EXPECT_EQ(0, memcmp("Latn", test.localeScript, 4));
    memset(out, 1, 4);
    test.unpackRegion(out);
    EXPECT_EQ('\0', out[0]);
    EXPECT_EQ(0, strcmp("1901", test.localeVariant));
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("ar-EG-u-nu-latn");
    EXPECT_EQ('a', test.language[0]);
    EXPECT_EQ('r', test.language[1]);
    EXPECT_EQ('E', test.country[0]);
    EXPECT_EQ('G', test.country[1]);
    EXPECT_TRUE(test.localeScriptWasComputed);
    EXPECT_EQ(0, memcmp("Arab", test.localeScript, 4));
    EXPECT_EQ(0, test.localeVariant[0]);
    EXPECT_EQ(0, memcmp("latn", test.localeNumberingSystem, 4));

    test.setBcp47Locale("ar-EG-u");
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("ar-EG-u-nu");
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("ar-EG-u-attr-nu-latn");
    EXPECT_EQ(0, memcmp("latn", test.localeNumberingSystem, 4));

    test.setBcp47Locale("ar-EG-u-ca-gregory-nu-latn");
    EXPECT_EQ(0, memcmp("latn", test.localeNumberingSystem, 4));

    test.setBcp47Locale("ar-EG-u-nu-latn-ca-gregory");
    EXPECT_EQ(0, memcmp("latn", test.localeNumberingSystem, 4));

    test.setBcp47Locale("ar-EG-u-nu-toolongnumsys");
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("ar-EG-u-nu-latn-nu-arab");
    EXPECT_EQ(0, memcmp("latn", test.localeNumberingSystem, 4));

    test.setBcp47Locale("ar-EG-u-co-nu-latn");
    EXPECT_EQ(0, test.localeNumberingSystem[0]);

    test.setBcp47Locale("ar-u-co-abcd-attr-nu-latn");
    EXPECT_EQ(0, test.localeNumberingSystem[0]);
}

TEST(ConfigLocaleTest, computeScript) {
    ResTable_config config;

    fillIn(NULL, NULL, NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("\0\0\0\0", config.localeScript, 4));

    fillIn("zh", "TW", NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Hant", config.localeScript, 4));

    fillIn("zh", "CN", NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Hans", config.localeScript, 4));

    fillIn("az", NULL, NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Latn", config.localeScript, 4));

    fillIn("az", "AZ", NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Latn", config.localeScript, 4));

    fillIn("az", "IR", NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Arab", config.localeScript, 4));

    fillIn("peo", NULL, NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("Xpeo", config.localeScript, 4));

    fillIn("qaa", NULL, NULL, NULL, &config);
    EXPECT_EQ(0, memcmp("\0\0\0\0", config.localeScript, 4));
}

TEST(ConfigLocaleTest, getBcp47Locale_script) {
    ResTable_config config;
    fillIn("en", NULL, "Latn", NULL, &config);

    char out[RESTABLE_MAX_LOCALE_LEN];
    config.localeScriptWasComputed = false;
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("en-Latn", out));

    config.localeScriptWasComputed = true;
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("en", out));
}

TEST(ConfigLocaleTest, getBcp47Locale_numberingSystem) {
    ResTable_config config;
    fillIn("en", NULL, NULL, NULL, &config);

    char out[RESTABLE_MAX_LOCALE_LEN];

    memcpy(&config.localeNumberingSystem, "latn", 4);
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("en-u-nu-latn", out));

    fillIn("sr", "SR", "Latn", NULL, &config);
    memcpy(&config.localeNumberingSystem, "latn", 4);
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("sr-Latn-SR-u-nu-latn", out));
}

TEST(ConfigLocaleTest, getBcp47Locale_canonicalize) {
    ResTable_config config;
    char out[RESTABLE_MAX_LOCALE_LEN];

    fillIn("tl", NULL, NULL, NULL, &config);
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("tl", out));
    config.getBcp47Locale(out, true /* canonicalize */);
    EXPECT_EQ(0, strcmp("fil", out));

    fillIn("tl", "PH", NULL, NULL, &config);
    config.getBcp47Locale(out);
    EXPECT_EQ(0, strcmp("tl-PH", out));
    config.getBcp47Locale(out, true /* canonicalize */);
    EXPECT_EQ(0, strcmp("fil-PH", out));
}

TEST(ConfigLocaleTest, match) {
    ResTable_config supported, requested;

    fillIn(NULL, NULL, NULL, NULL, &supported);
    fillIn("fr", "CA", NULL, NULL, &requested);
    // Empty locale matches everything (as a default).
    EXPECT_TRUE(supported.match(requested));

    fillIn("en", "CA", NULL, NULL, &supported);
    fillIn("fr", "CA", NULL, NULL, &requested);
    // Different languages don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("tl", "PH", NULL, NULL, &supported);
    fillIn("fil", "PH", NULL, NULL, &requested);
    // Equivalent languages match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("qaa", "FR", NULL, NULL, &supported);
    fillIn("qaa", "CA", NULL, NULL, &requested);
    // If we can't infer the scripts, different regions don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("qaa", "FR", "Latn", NULL, &supported);
    fillIn("qaa", "CA", NULL, NULL, &requested);
    // If we can't infer any of the scripts, different regions don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("qaa", "FR", NULL, NULL, &supported);
    fillIn("qaa", "CA", "Latn", NULL, &requested);
    // If we can't infer any of the scripts, different regions don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("qaa", NULL, NULL, NULL, &supported);
    fillIn("qaa", "CA", NULL, NULL, &requested);
    // language-only resources still support language+region requests, even if we can't infer the
    // script.
    EXPECT_TRUE(supported.match(requested));

    fillIn("qaa", "CA", NULL, NULL, &supported);
    fillIn("qaa", "CA", NULL, NULL, &requested);
    // Even if we can't infer the scripts, exactly equal locales match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("az", NULL, NULL, NULL, &supported);
    fillIn("az", NULL, "Latn", NULL, &requested);
    // If the resolved scripts are the same, it doesn't matter if they were explicitly provided
    // or not, and they match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("az", NULL, NULL, NULL, &supported);
    fillIn("az", NULL, "Cyrl", NULL, &requested);
    // If the resolved scripts are different, they don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("az", NULL, NULL, NULL, &supported);
    fillIn("az", "IR", NULL, NULL, &requested);
    // If the resolved scripts are different, they don't match.
    EXPECT_FALSE(supported.match(requested));

    fillIn("az", "IR", NULL, NULL, &supported);
    fillIn("az", NULL, "Arab", NULL, &requested);
    // If the resolved scripts are the same, it doesn't matter if they were explicitly provided
    // or not, and they match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("en", NULL, NULL, NULL, &supported);
    fillIn("en", "XA", NULL, NULL, &requested);
    // en-XA is a pseudo-locale, and English resources are not a match for it.
    EXPECT_FALSE(supported.match(requested));

    fillIn("en", "XA", NULL, NULL, &supported);
    fillIn("en", NULL, NULL, NULL, &requested);
    // en-XA is a pseudo-locale, and its resources don't support English locales.
    EXPECT_FALSE(supported.match(requested));

    fillIn("en", "XA", NULL, NULL, &supported);
    fillIn("en", "XA", NULL, NULL, &requested);
    // Even if they are pseudo-locales, exactly equal locales match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("ar", NULL, NULL, NULL, &supported);
    fillIn("ar", "XB", NULL, NULL, &requested);
    // ar-XB is a pseudo-locale, and Arabic resources are not a match for it.
    EXPECT_FALSE(supported.match(requested));

    fillIn("ar", "XB", NULL, NULL, &supported);
    fillIn("ar", NULL, NULL, NULL, &requested);
    // ar-XB is a pseudo-locale, and its resources don't support Arabic locales.
    EXPECT_FALSE(supported.match(requested));

    fillIn("ar", "XB", NULL, NULL, &supported);
    fillIn("ar", "XB", NULL, NULL, &requested);
    // Even if they are pseudo-locales, exactly equal locales match.
    EXPECT_TRUE(supported.match(requested));

    fillIn("ar", "EG", NULL, NULL, &supported);
    fillIn("ar", "TN", NULL, NULL, &requested);
    memcpy(&supported.localeNumberingSystem, "latn", 4);
    EXPECT_TRUE(supported.match(requested));
}

TEST(ConfigLocaleTest, match_emptyScript) {
    ResTable_config supported, requested;

    fillIn("fr", "FR", NULL, NULL, &supported);
    fillIn("fr", "CA", NULL, NULL, &requested);

    // emulate packages built with older AAPT
    memset(supported.localeScript, '\0', 4);
    supported.localeScriptWasComputed = false;

    EXPECT_TRUE(supported.match(requested));
}

TEST(ConfigLocaleTest, isLocaleBetterThan_basics) {
    ResTable_config config1, config2, request;

    fillIn(NULL, NULL, NULL, NULL, &request);
    fillIn("fr", "FR", NULL, NULL, &config1);
    fillIn("fr", "CA", NULL, NULL, &config2);
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("fr", "CA", NULL, NULL, &request);
    fillIn(NULL, NULL, NULL, NULL, &config1);
    fillIn(NULL, NULL, NULL, NULL, &config2);
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("fr", "CA", NULL, NULL, &request);
    fillIn("fr", "FR", NULL, NULL, &config1);
    fillIn(NULL, NULL, NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("de", "DE", NULL, NULL, &request);
    fillIn("de", "DE", NULL, NULL, &config1);
    fillIn("de", "DE", NULL, "1901", &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("de", "DE", NULL, NULL, &request);
    fillIn("de", "DE", NULL, "1901", &config1);
    fillIn("de", "DE", NULL, "1996", &config2);
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("de", "DE", NULL, "1901", &request);
    fillIn("de", "DE", NULL, "1901", &config1);
    fillIn("de", "DE", NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("de", "DE", NULL, "1901", &request);
    fillIn("de", "DE", NULL, "1996", &config1);
    fillIn("de", "DE", NULL, NULL, &config2);
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("fil", "PH", NULL, NULL, &request);
    fillIn("tl", "PH", NULL, NULL, &config1);
    fillIn("fil", "US", NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("fil", "PH", NULL, "fonipa", &request);
    fillIn("tl", "PH", NULL, "fonipa", &config1);
    fillIn("fil", "PH", NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("fil", "PH", NULL, NULL, &request);
    fillIn("fil", "PH", NULL, NULL, &config1);
    fillIn("tl", "PH", NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));
}

TEST(ConfigLocaleTest, isLocaleBetterThan_regionComparison) {
    ResTable_config config1, config2, request;

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", "419", NULL, NULL, &config2);
    // Both supported locales are the same, so none is better than the other.
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "AR", NULL, NULL, &config1);
    fillIn("es", "419", NULL, NULL, &config2);
    // An exact locale match is better than a parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", NULL, NULL, NULL, &config2);
    // A closer parent is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", "ES", NULL, NULL, &config2);
    // A parent is better than a non-parent representative locale.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", NULL, NULL, NULL, &config1);
    fillIn("es", "ES", NULL, NULL, &config2);
    // A parent is better than a non-parent representative locale.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "PE", NULL, NULL, &config1);
    fillIn("es", "ES", NULL, NULL, &config2);
    // A closer locale is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "US", NULL, NULL, &config1);
    fillIn("es", NULL, NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "MX", NULL, NULL, &request);
    fillIn("es", "US", NULL, NULL, &config1);
    fillIn("es", NULL, NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "MX", NULL, NULL, &config1);
    fillIn("es", NULL, NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "US", NULL, NULL, &request);
    fillIn("es", "MX", NULL, NULL, &config1);
    fillIn("es", NULL, NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", "MX", NULL, NULL, &config2);
    // Even though es-MX and es-US are pseudo-parents of all Latin Ameircan
    // Spanish locales, es-419 is a closer parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "US", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", "MX", NULL, NULL, &config2);
    // Even though es-MX and es-US are pseudo-parents of all Latin Ameircan
    // Spanish locales, es-419 is a closer parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "MX", NULL, NULL, &request);
    fillIn("es", "419", NULL, NULL, &config1);
    fillIn("es", "US", NULL, NULL, &config2);
    // Even though es-MX and es-US are pseudo-parents of all Latin Ameircan
    // Spanish locales, es-419 is a closer parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "MX", NULL, NULL, &config1);
    fillIn("es", "BO", NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "US", NULL, NULL, &config1);
    fillIn("es", "BO", NULL, NULL, &config2);
    // Special case for Latin American Spanish: es-MX and es-US are
    // pseudo-parents of all Latin Ameircan Spanish locales.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "IC", NULL, NULL, &request);
    fillIn("es", "ES", NULL, NULL, &config1);
    fillIn("es", "GQ", NULL, NULL, &config2);
    // A representative locale is better if they are equidistant.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "AR", NULL, NULL, &request);
    fillIn("es", "MX", NULL, NULL, &config1);
    fillIn("es", "US", NULL, NULL, &config2);
    // If all is equal, the locale earlier in the dictionary is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("es", "GQ", NULL, NULL, &request);
    fillIn("es", "IC", NULL, NULL, &config1);
    fillIn("es", "419", NULL, NULL, &config2);
    // If all is equal, the locale earlier in the dictionary is better and
    // letters are better than numbers.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "GB", NULL, NULL, &request);
    fillIn("en", "001", NULL, NULL, &config1);
    fillIn("en", NULL, NULL, NULL, &config2);
    // A closer parent is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "PR", NULL, NULL, &request);
    fillIn("en", NULL, NULL, NULL, &config1);
    fillIn("en", "001", NULL, NULL, &config2);
    // A parent is better than a non-parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "DE", NULL, NULL, &request);
    fillIn("en", "150", NULL, NULL, &config1);
    fillIn("en", "001", NULL, NULL, &config2);
    // A closer parent is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "IN", NULL, NULL, &request);
    fillIn("en", "AU", NULL, NULL, &config1);
    fillIn("en", "US", NULL, NULL, &config2);
    // A closer locale is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "PR", NULL, NULL, &request);
    fillIn("en", "001", NULL, NULL, &config1);
    fillIn("en", "GB", NULL, NULL, &config2);
    // A closer locale is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "IN", NULL, NULL, &request);
    fillIn("en", "GB", NULL, NULL, &config1);
    fillIn("en", "AU", NULL, NULL, &config2);
    // A representative locale is better if they are equidistant.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "IN", NULL, NULL, &request);
    fillIn("en", "AU", NULL, NULL, &config1);
    fillIn("en", "CA", NULL, NULL, &config2);
    // If all is equal, the locale earlier in the dictionary is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("pt", "MZ", NULL, NULL, &request);
    fillIn("pt", "PT", NULL, NULL, &config1);
    fillIn("pt", NULL, NULL, NULL, &config2);
    // A closer parent is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("pt", "MZ", NULL, NULL, &request);
    fillIn("pt", "PT", NULL, NULL, &config1);
    fillIn("pt", "BR", NULL, NULL, &config2);
    // A parent is better than a non-parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("zh", "MO", "Hant", NULL, &request);
    fillIn("zh", "HK", "Hant", NULL, &config1);
    fillIn("zh", "TW", "Hant", NULL, &config2);
    // A parent is better than a non-parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("zh", "US", "Hant", NULL, &request);
    fillIn("zh", "TW", "Hant", NULL, &config1);
    fillIn("zh", "HK", "Hant", NULL, &config2);
    // A representative locale is better if they are equidistant.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("ar", "DZ", NULL, NULL, &request);
    fillIn("ar", "015", NULL, NULL, &config1);
    fillIn("ar", NULL, NULL, NULL, &config2);
    // A closer parent is better.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("ar", "EG", NULL, NULL, &request);
    fillIn("ar", NULL, NULL, NULL, &config1);
    fillIn("ar", "015", NULL, NULL, &config2);
    // A parent is better than a non-parent.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("ar", "QA", NULL, NULL, &request);
    fillIn("ar", "EG", NULL, NULL, &config1);
    fillIn("ar", "BH", NULL, NULL, &config2);
    // A representative locale is better if they are equidistant.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("ar", "QA", NULL, NULL, &request);
    fillIn("ar", "SA", NULL, NULL, &config1);
    fillIn("ar", "015", NULL, NULL, &config2);
    // If all is equal, the locale earlier in the dictionary is better and
    // letters are better than numbers.
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));
}

TEST(ConfigLocaleTest, isLocaleBetterThan_numberingSystem) {
    ResTable_config config1, config2, request;

    fillIn("ar", "EG", NULL, NULL, &request);
    memcpy(&request.localeNumberingSystem, "latn", 4);
    fillIn("ar", NULL, NULL, NULL, &config1);
    memcpy(&config1.localeNumberingSystem, "latn", 4);
    fillIn("ar", NULL, NULL, NULL, &config2);
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("ar", "EG", NULL, NULL, &request);
    memcpy(&request.localeNumberingSystem, "latn", 4);
    fillIn("ar", "TN", NULL, NULL, &config1);
    memcpy(&config1.localeNumberingSystem, "latn", 4);
    fillIn("ar", NULL, NULL, NULL, &config2);
    EXPECT_TRUE(config2.isLocaleBetterThan(config1, &request));
    EXPECT_FALSE(config1.isLocaleBetterThan(config2, &request));
}

// Default resources are considered better matches for US English
// and US-like English locales than International English locales
TEST(ConfigLocaleTest, isLocaleBetterThan_UsEnglishIsSpecial) {
    ResTable_config config1, config2, request;

    fillIn("en", "US", NULL, NULL, &request);
    fillIn(NULL, NULL, NULL, NULL, &config1);
    fillIn("en", "001", NULL, NULL, &config2);
    // default is better than International English
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "US", NULL, NULL, &request);
    fillIn(NULL, NULL, NULL, NULL, &config1);
    fillIn("en", "GB", NULL, NULL, &config2);
    // default is better than British English
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "PR", NULL, NULL, &request);
    fillIn(NULL, NULL, NULL, NULL, &config1);
    fillIn("en", "001", NULL, NULL, &config2);
    // Even for Puerto Rico, default is better than International English
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "US", NULL, NULL, &request);
    fillIn("en", NULL, NULL, NULL, &config1);
    fillIn(NULL, NULL, NULL, NULL, &config2);
    // "English" is better than default, since it's a parent of US English
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "PR", NULL, NULL, &request);
    fillIn("en", NULL, NULL, NULL, &config1);
    fillIn(NULL, NULL, NULL, NULL, &config2);
    // "English" is better than default, since it's a parent of Puerto Rico English
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));

    fillIn("en", "US", NULL, NULL, &request);
    fillIn(NULL, NULL, NULL, NULL, &config1);
    fillIn("en", "PR", NULL, NULL, &config2);
    // For US English itself, we prefer default to its siblings in the parent tree
    EXPECT_TRUE(config1.isLocaleBetterThan(config2, &request));
    EXPECT_FALSE(config2.isLocaleBetterThan(config1, &request));
}

}  // namespace android
