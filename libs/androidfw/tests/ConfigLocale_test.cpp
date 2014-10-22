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

     char out[4] = { 1, 1, 1, 1};
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

     char out[4] = { 1, 1, 1, 1};
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

     char out[4] = { 1, 1, 1, 1};
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

     char out[4] = { 1, 1, 1, 1};
     config.unpackLanguage(out);
     EXPECT_EQ('t', out[0]);
     EXPECT_EQ('g', out[1]);
     EXPECT_EQ('p', out[2]);
     EXPECT_EQ(0, out[3]);
}

TEST(ConfigLocaleTest, packAndUnpack3LetterRegion) {
     ResTable_config config;
     config.packRegion("419");

     char out[4] = { 1, 1, 1, 1};
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
}

TEST(ConfigLocaleTest, setLocale) {
    ResTable_config test;
    test.setBcp47Locale("en-US");
    EXPECT_EQ('e', test.language[0]);
    EXPECT_EQ('n', test.language[1]);
    EXPECT_EQ('U', test.country[0]);
    EXPECT_EQ('S', test.country[1]);
    EXPECT_EQ(0, test.localeScript[0]);
    EXPECT_EQ(0, test.localeVariant[0]);

    test.setBcp47Locale("eng-419");
    char out[4] = { 1, 1, 1, 1};
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


    test.setBcp47Locale("en-Latn-419");
    memset(out, 1, 4);
    EXPECT_EQ('e', test.language[0]);
    EXPECT_EQ('n', test.language[1]);

    EXPECT_EQ(0, memcmp("Latn", test.localeScript, 4));
    test.unpackRegion(out);
    EXPECT_EQ('4', out[0]);
    EXPECT_EQ('1', out[1]);
    EXPECT_EQ('9', out[2]);
}

}  // namespace android.
