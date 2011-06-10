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
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_UNDEFINED_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(null));

        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.ENGLISH));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.CANADA));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.CANADA_FRENCH));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.FRANCE));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.FRENCH));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.GERMAN));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.GERMANY));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.ITALIAN));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.ITALY));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.UK));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.US));

        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_UNDEFINED_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.ROOT));

        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.CHINA));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.CHINESE));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.JAPAN));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.JAPANESE));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.KOREA));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.KOREAN));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.PRC));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.TAIWAN));
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(Locale.TRADITIONAL_CHINESE));

        Locale locale = new Locale("ar");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "AE");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "BH");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "DZ");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "EG");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "IQ");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "JO");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "KW");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LB");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LY");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "MA");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "OM");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "QA");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SA");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SD");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SY");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "TN");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "YE");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("fa");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "AF");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "IR");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("iw");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("iw", "IL");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he", "IL");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not pass until we are able to take care about the scrip subtag
        // thru having the "likelySubTags" file into ICU4C
//        locale = new Locale("pa_Arab");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("pa_Arab", "PK");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ps");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ps", "AF");
        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not work as the localized display name would be "Urdu" with ICU 4.4
        // We will need ICU 4.6 to get the correct localized display name
//        locale = new Locale("ur");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("ur", "IN");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("ur", "PK");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));

        // The following test will not pass until we are able to take care about the scrip subtag
        // thru having the "likelySubTags" file into ICU4C
//        locale = new Locale("uz_Arab");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));
//        locale = new Locale("uz_Arab", "AF");
//        assertEquals(Configuration.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
//            Configuration.getLayoutDirectionFromLocale(locale));
    }
}
