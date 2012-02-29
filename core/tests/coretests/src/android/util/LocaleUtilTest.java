/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.util;

import java.util.Locale;

import android.test.AndroidTestCase;
import dalvik.annotation.TestLevel;
import dalvik.annotation.TestTargetNew;

import static android.view.View.LAYOUT_DIRECTION_LTR;
import static android.view.View.LAYOUT_DIRECTION_RTL;

public class LocaleUtilTest extends AndroidTestCase {

    @TestTargetNew(
        level = TestLevel.COMPLETE,
        method = "getLayoutDirectionFromLocale",
        args = {Locale.class}
    )
    public void testGetLayoutDirectionFromLocale() {
        assertEquals(LAYOUT_DIRECTION_LTR,
                LocaleUtil.getLayoutDirectionFromLocale(null));

        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ENGLISH));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CANADA));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CANADA_FRENCH));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.FRANCE));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.FRENCH));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.GERMAN));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.GERMANY));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ITALIAN));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ITALY));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.UK));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.US));

        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ROOT));

        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CHINA));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CHINESE));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.JAPAN));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.JAPANESE));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.KOREA));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.KOREAN));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.PRC));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.TAIWAN));
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.TRADITIONAL_CHINESE));

        Locale locale = new Locale("ar");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "AE");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "BH");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "DZ");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "EG");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "IQ");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "JO");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "KW");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LB");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LY");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "MA");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "OM");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "QA");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SA");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SD");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SY");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "TN");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "YE");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("fa");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "AF");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "IR");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("iw");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("iw", "IL");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he", "IL");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("pa_Arab");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("pa_Arab", "PK");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ps");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ps", "AF");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ur");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ur", "IN");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ur", "PK");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("uz_Arab");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("uz_Arab", "AF");
        assertEquals(LAYOUT_DIRECTION_RTL,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        // Locale without a real language
        locale = new Locale("zz");
        assertEquals(LAYOUT_DIRECTION_LTR,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
    }
}
