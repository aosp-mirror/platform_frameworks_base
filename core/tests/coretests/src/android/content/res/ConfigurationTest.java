/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.content.res;

import java.util.Locale;

import android.test.AndroidTestCase;
import dalvik.annotation.TestLevel;
import dalvik.annotation.TestTargetNew;

public class ConfigurationTest extends AndroidTestCase {

    @TestTargetNew(
        level = TestLevel.COMPLETE,
        method = "getLayoutDirectionFromLocale",
        args = {Locale.class}
    )
    public void testGetLayoutDirectionFromLocale() {
        assertEquals(Configuration.LAYOUT_DIRECTION_UNDEFINED,
            Configuration.getLayoutDirectionFromLocale(null));

        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.ENGLISH));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.CANADA));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.CANADA_FRENCH));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.FRANCE));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.FRENCH));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.GERMAN));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.GERMANY));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.ITALIAN));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.ITALY));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.UK));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.US));

        assertEquals(Configuration.LAYOUT_DIRECTION_UNDEFINED,
            Configuration.getLayoutDirectionFromLocale(Locale.ROOT));

        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.CHINA));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.CHINESE));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.JAPAN));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.JAPANESE));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.KOREA));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.KOREAN));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.PRC));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.TAIWAN));
        assertEquals(Configuration.LAYOUT_DIRECTION_LTR,
            Configuration.getLayoutDirectionFromLocale(Locale.TRADITIONAL_CHINESE));

        Locale locale = new Locale("ar");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "AE");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "BH");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "DZ");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "EG");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "IQ");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "JO");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "KW");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LB");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LY");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "MA");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "OM");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "QA");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SA");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SD");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SY");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "TN");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "YE");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("fa");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "AF");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "IR");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("iw");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("iw", "IL");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he", "IL");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not pass until we are able to take care about the scrip subtag
        // thru having the "likelySubTags" file into ICU4C
//        locale = new Locale("pa_Arab");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("pa_Arab", "PK");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ps");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ps", "AF");
        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not work as the localized display name would be "Urdu" with ICU 4.4
        // We will need ICU 4.6 to get the correct localized display name
//        locale = new Locale("ur");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("ur", "IN");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("ur", "PK");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not pass until we are able to take care about the scrip subtag
        // thru having the "likelySubTags" file into ICU4C
//        locale = new Locale("uz_Arab");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("uz_Arab", "AF");
//        assertEquals(Configuration.LAYOUT_DIRECTION_RTL,
//            Configuration.getLayoutDirectionFromLocale(locale));
    }
}
