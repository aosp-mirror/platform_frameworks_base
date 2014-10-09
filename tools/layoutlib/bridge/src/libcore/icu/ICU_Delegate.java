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

package libcore.icu;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;
import com.ibm.icu.text.DateTimePatternGenerator;
import com.ibm.icu.util.Currency;
import com.ibm.icu.util.ULocale;

import java.util.Locale;

/**
 * Delegate implementing the native methods of libcore.icu.ICU
 *
 * Through the layoutlib_create tool, the original native methods of ICU have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 */
public class ICU_Delegate {

    // --- Java delegates

    @LayoutlibDelegate
    /*package*/ static String toLowerCase(String s, String localeName) {
        return s.toLowerCase();
    }

    @LayoutlibDelegate
    /*package*/ static String toUpperCase(String s, String localeName) {
        return s.toUpperCase();
    }

    // --- Native methods accessing ICU's database.

    @LayoutlibDelegate
    /*package*/ static String getBestDateTimePatternNative(String skeleton, String localeName) {
        return DateTimePatternGenerator.getInstance(new ULocale(localeName))
                .getBestPattern(skeleton);
    }

    @LayoutlibDelegate
    /*package*/ static String getCldrVersion() {
        return "22.1.1";      // TODO: check what the right value should be.
    }

    @LayoutlibDelegate
    /*package*/ static String getIcuVersion() {
        return "unknown_layoutlib";
    }

    @LayoutlibDelegate
    /*package*/ static String getUnicodeVersion() {
        return "5.2";
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableBreakIteratorLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableCalendarLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableCollatorLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableDateFormatLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableNumberFormatLocalesNative() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String[] getAvailableCurrencyCodes() {
        return new String[0];
    }

    @LayoutlibDelegate
    /*package*/ static String getCurrencyCode(String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getCurrencyDisplayName(String locale, String currencyCode) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static int getCurrencyFractionDigits(String currencyCode) {
        return 0;
    }

    @LayoutlibDelegate
    /*package*/ static int getCurrencyNumericCode(String currencyCode) {
        return Currency.getInstance(currencyCode).getNumericCode();
    }

    @LayoutlibDelegate
    /*package*/ static String getCurrencySymbol(String locale, String currencyCode) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getDisplayCountryNative(String countryCode, String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getDisplayLanguageNative(String languageCode, String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getDisplayVariantNative(String variantCode, String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getDisplayScriptNative(String variantCode, String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getISO3Country(String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getISO3Language(String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String addLikelySubtags(String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String getScript(String locale) {
        return "";
    }

    @LayoutlibDelegate
    /*package*/ static String[] getISOLanguagesNative() {
        return Locale.getISOLanguages();
    }

    @LayoutlibDelegate
    /*package*/ static String[] getISOCountriesNative() {
        return Locale.getISOCountries();
    }

    @LayoutlibDelegate
    /*package*/ static boolean initLocaleDataNative(String locale, LocaleData result) {

        // Used by Calendar.
        result.firstDayOfWeek = Integer.valueOf(1);
        result.minimalDaysInFirstWeek = Integer.valueOf(1);

        // Used by DateFormatSymbols.
        result.amPm = new String[] { "AM", "PM" };
        result.eras = new String[] { "BC", "AD" };

        result.longMonthNames = new String[] { "January", "February", "March", "April", "May",
                "June", "July", "August", "September", "October", "November", "December" };
        result.shortMonthNames = new String[] { "Jan", "Feb", "Mar", "Apr", "May",
                "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };
        result.longStandAloneMonthNames = result.longMonthNames;
        result.shortStandAloneMonthNames = result.shortMonthNames;

        // The platform code expects this to begin at index 1, rather than 0. It maps it directly to
        // the constants from java.util.Calendar.<weekday>
        result.longWeekdayNames = new String[] {
                "", "Sunday", "Monday" ,"Tuesday", "Wednesday", "Thursday", "Friday", "Saturday" };
        result.shortWeekdayNames = new String[] {
                "", "Sun", "Mon" ,"Tue", "Wed", "Thu", "Fri", "Sat" };
        result.tinyWeekdayNames = new String[] {
                "", "S", "M", "T", "W", "T", "F", "S" };

        result.longStandAloneWeekdayNames = result.longWeekdayNames;
        result.shortStandAloneWeekdayNames = result.shortWeekdayNames;
        result.tinyStandAloneWeekdayNames = result.tinyWeekdayNames;

        result.fullTimeFormat = "";
        result.longTimeFormat = "";
        result.mediumTimeFormat = "";
        result.shortTimeFormat = "";

        result.fullDateFormat = "";
        result.longDateFormat = "";
        result.mediumDateFormat = "";
        result.shortDateFormat = "";

        // Used by DecimalFormatSymbols.
        result.zeroDigit = '0';
        result.decimalSeparator = '.';
        result.groupingSeparator = ',';
        result.patternSeparator = ' ';
        result.percent = "%";
        result.perMill = '\u2030';
        result.monetarySeparator = ' ';
        result.minusSign = "-";
        result.exponentSeparator = "e";
        result.infinity = "\u221E";
        result.NaN = "NaN";
        // Also used by Currency.
        result.currencySymbol = "$";
        result.internationalCurrencySymbol = "USD";

        // Used by DecimalFormat and NumberFormat.
        result.numberPattern = "%f";
        result.integerPattern = "%d";
        result.currencyPattern = "%s";
        result.percentPattern = "%f";

        return true;
    }

    @LayoutlibDelegate
    /*package*/ static void setDefaultLocale(String locale) {
        ICU.setDefaultLocale(locale);
    }

    @LayoutlibDelegate
    /*package*/ static String getDefaultLocale() {
        return ICU.getDefaultLocale();
    }
}
