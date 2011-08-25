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

import static android.util.LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE;

public class LocaleUtilTest extends AndroidTestCase {

    @TestTargetNew(
        level = TestLevel.COMPLETE,
        method = "getLayoutDirectionFromLocale",
        args = {Locale.class}
    )
    public void testGetLayoutDirectionFromLocale() {
        assertEquals(TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
                LocaleUtil.getLayoutDirectionFromLocale(null));

        assertEquals(TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ENGLISH));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CANADA));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CANADA_FRENCH));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.FRANCE));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.FRENCH));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.GERMAN));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.GERMANY));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ITALIAN));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ITALY));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.UK));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.US));

        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.ROOT));

        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CHINA));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.CHINESE));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.JAPAN));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.JAPANESE));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.KOREA));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.KOREAN));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.PRC));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.SIMPLIFIED_CHINESE));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.TAIWAN));
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(Locale.TRADITIONAL_CHINESE));

        Locale locale = new Locale("ar");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "AE");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "BH");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "DZ");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "EG");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "IQ");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "JO");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "KW");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LB");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "LY");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "MA");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "OM");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "QA");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SA");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SD");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "SY");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "TN");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ar", "YE");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("fa");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "AF");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("fa", "IR");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("iw");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("iw", "IL");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("he", "IL");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("pa_Arab");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("pa_Arab", "PK");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ps");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ps", "AF");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("ur");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ur", "IN");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("ur", "PK");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        locale = new Locale("uz_Arab");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
        locale = new Locale("uz_Arab", "AF");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_RTL_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));

        // Locale without a real language
        locale = new Locale("zz");
        assertEquals(LocaleUtil.TEXT_LAYOUT_DIRECTION_LTR_DO_NOT_USE,
            LocaleUtil.getLayoutDirectionFromLocale(locale));
    }
}
