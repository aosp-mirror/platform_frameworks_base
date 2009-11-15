/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import java.util.HashMap;
import java.util.Map;

/**
 * The class representing VCard related configurations. Useful static methods are not in this class
 * but in VCardUtils.
 */
public class VCardConfig {
    // TODO: may be better to make the instance of this available and stop using static methods and
    //        one integer. 

    /* package */ static final int LOG_LEVEL_NONE = 0;
    /* package */ static final int LOG_LEVEL_PERFORMANCE_MEASUREMENT = 0x1;
    /* package */ static final int LOG_LEVEL_SHOW_WARNING = 0x2;
    /* package */ static final int LOG_LEVEL_VERBOSE =
        LOG_LEVEL_PERFORMANCE_MEASUREMENT | LOG_LEVEL_SHOW_WARNING;

    /* package */ static final int LOG_LEVEL = LOG_LEVEL_NONE;

    // Assumes that "iso-8859-1" is able to map "all" 8bit characters to some unicode and
    // decode the unicode to the original charset. If not, this setting will cause some bug. 
    public static final String DEFAULT_CHARSET = "iso-8859-1";
    
    // TODO: make the other codes use this flag
    public static final boolean IGNORE_CASE_EXCEPT_VALUE = true;
    
    private static final int FLAG_V21 = 0;
    private static final int FLAG_V30 = 1;

    // 0x2 is reserved for the future use ...

    public static final int NAME_ORDER_DEFAULT = 0;
    public static final int NAME_ORDER_EUROPE = 0x4;
    public static final int NAME_ORDER_JAPANESE = 0x8;
    private static final int NAME_ORDER_MASK = 0xC;

    // 0x10 is reserved for safety
    
    private static final int FLAG_CHARSET_UTF8 = 0;
    private static final int FLAG_CHARSET_SHIFT_JIS = 0x20;

    /**
     * The flag indicating the vCard composer will add some "X-" properties used only in Android
     * when the formal vCard specification does not have appropriate fields for that data.
     * 
     * For example, Android accepts nickname information while vCard 2.1 does not.
     * When this flag is on, vCard composer emits alternative "X-" property (like "X-NICKNAME")
     * instead of just dropping it.
     * 
     * vCard parser code automatically parses the field emitted even when this flag is off.
     * 
     * Note that this flag does not assure all the information must be hold in the emitted vCard.
     */
    private static final int FLAG_USE_ANDROID_PROPERTY = 0x80000000;
    
    /**
     * The flag indicating the vCard composer will add some "X-" properties seen in the
     * vCard data emitted by the other softwares/devices when the formal vCard specification
     * does not have appropriate field(s) for that data. 
     * 
     * One example is X-PHONETIC-FIRST-NAME/X-PHONETIC-MIDDLE-NAME/X-PHONETIC-LAST-NAME, which are
     * for phonetic name (how the name is pronounced), seen in the vCard emitted by some other
     * non-Android devices/softwares. We chose to enable the vCard composer to use those
     * defact properties since they are also useful for Android devices.
     * 
     * Note for developers: only "X-" properties should be added with this flag. vCard 2.1/3.0
     * allows any kind of "X-" properties but does not allow non-"X-" properties (except IANA tokens
     * in vCard 3.0). Some external parsers may get confused with non-valid, non-"X-" properties.
     */
    private static final int FLAG_USE_DEFACT_PROPERTY = 0x40000000;

    /**
     * The flag indicating some specific dialect seen in vcard of DoCoMo (one of Japanese
     * mobile careers) should be used. This flag does not include any other information like
     * that "the vCard is for Japanese". So it is "possible" that "the vCard should have DoCoMo's
     * dialect but the name order should be European", but it is not recommended.
     */
    private static final int FLAG_DOCOMO = 0x20000000;

    /**
     * The flag indicating the vCard composer use Quoted-Printable toward even "primary" types.
     * In this context, "primary" types means "N", "FN", etc. which are usually "not" encoded
     * into Quoted-Printable format in external exporters.
     * This flag is useful when some target importer does not accept "primary" property values
     * without Quoted-Printable encoding.
     *
     * @hide Temporaly made public. We don't strictly define "primary", so we may change the
     * behavior around this flag in the future. Do not use this flag without any reason.
     */
    public static final int FLAG_USE_QP_TO_PRIMARY_PROPERTIES = 0x10000000;
    
    // VCard types

    /**
     * General vCard format with the version 2.1. Uses UTF-8 for the charset.
     * When composing a vCard entry, the US convension will be used.
     * 
     * e.g. The order of the display name would be "Prefix Given Middle Family Suffix",
     * while in Japan, it should be "Prefix Family Middle Given Suffix".
     */
    public static final int VCARD_TYPE_V21_GENERIC =
        (FLAG_V21 | NAME_ORDER_DEFAULT | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static String VCARD_TYPE_V21_GENERIC_STR = "v21_generic";
    
    /**
     * General vCard format with the version 3.0. Uses UTF-8 for the charset.
     * 
     * Note that this type is not fully implemented, so probably some bugs remain both in
     * parsing and composing.
     *
     * TODO: implement this type correctly.
     */
    public static final int VCARD_TYPE_V30_GENERIC =
        (FLAG_V30 | NAME_ORDER_DEFAULT | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_GENERIC_STR = "v30_generic";
    
    /**
     * General vCard format with the version 2.1 with some Europe convension. Uses Utf-8.
     * Currently, only name order is considered ("Prefix Middle Given Family Suffix")
     */
    public static final int VCARD_TYPE_V21_EUROPE =
        (FLAG_V21 | NAME_ORDER_EUROPE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
    
    /* package */ static final String VCARD_TYPE_V21_EUROPE_STR = "v21_europe";
    
    /**
     * General vCard format with the version 3.0 with some Europe convension. Uses UTF-8
     */
    public static final int VCARD_TYPE_V30_EUROPE =
        (FLAG_V30 | NAME_ORDER_EUROPE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
    
    /* package */ static final String VCARD_TYPE_V30_EUROPE_STR = "v30_europe";
    
    /**
     * vCard 2.1 format for miscellaneous Japanese devices. Shift_Jis is used for
     * parsing/composing the vCard data.
     */
    public static final int VCARD_TYPE_V21_JAPANESE =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS | 
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_STR = "v21_japanese";
    
    /**
     * vCard 2.1 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     */
    public static final int VCARD_TYPE_V21_JAPANESE_UTF8 =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_UTF8_STR = "v21_japanese_utf8";
    
    /**
     * vCard format for miscellaneous Japanese devices, using Shift_Jis for
     * parsing/composing the vCard data.
     */
    public static final int VCARD_TYPE_V30_JAPANESE =
        (FLAG_V30 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
        
    /* package */ static final String VCARD_TYPE_V30_JAPANESE_STR = "v30_japanese";
    
    /**
     * vCard 3.0 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     */
    public static final int VCARD_TYPE_V30_JAPANESE_UTF8 =
        (FLAG_V30 | NAME_ORDER_JAPANESE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_JAPANESE_UTF8_STR = "v30_japanese_utf8";

    /**
     *  VCard format used in DoCoMo, which is one of Japanese mobile phone careers.
     *  Base version is vCard 2.1, but the data has several DoCoMo-specific convensions.
     *  No Android-specific property nor defact property is included.
     */
    public static final int VCARD_TYPE_DOCOMO =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS | FLAG_DOCOMO);

    private static final String VCARD_TYPE_DOCOMO_STR = "docomo";
    
    public static int VCARD_TYPE_DEFAULT = VCARD_TYPE_V21_GENERIC;

    private static final Map<String, Integer> VCARD_TYPES_MAP;
    
    static {
        VCARD_TYPES_MAP = new HashMap<String, Integer>();
        VCARD_TYPES_MAP.put(VCARD_TYPE_V21_GENERIC_STR, VCARD_TYPE_V21_GENERIC);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V30_GENERIC_STR, VCARD_TYPE_V30_GENERIC);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V21_EUROPE_STR, VCARD_TYPE_V21_EUROPE);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V30_EUROPE_STR, VCARD_TYPE_V30_EUROPE);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V21_JAPANESE_STR, VCARD_TYPE_V21_JAPANESE);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V21_JAPANESE_UTF8_STR, VCARD_TYPE_V21_JAPANESE_UTF8);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V30_JAPANESE_STR, VCARD_TYPE_V30_JAPANESE);
        VCARD_TYPES_MAP.put(VCARD_TYPE_V30_JAPANESE_UTF8_STR, VCARD_TYPE_V30_JAPANESE_UTF8);
        VCARD_TYPES_MAP.put(VCARD_TYPE_DOCOMO_STR, VCARD_TYPE_DOCOMO);
    }

    public static int getVCardTypeFromString(String vcardTypeString) {
        String loweredKey = vcardTypeString.toLowerCase();
        if (VCARD_TYPES_MAP.containsKey(loweredKey)) {
            return VCARD_TYPES_MAP.get(loweredKey);
        } else {
            // XXX: should return the value indicating the input is invalid?
            return VCARD_TYPE_DEFAULT;
        }
    }

    public static boolean isV30(int vcardType) {
        return ((vcardType & FLAG_V30) != 0);  
    }

    public static boolean usesQuotedPrintable(int vcardType) {
        return !isV30(vcardType);
    }

    public static boolean isDoCoMo(int vcardType) {
        return ((vcardType & FLAG_DOCOMO) != 0);
    }
    
    /**
     * @return true if the device is Japanese and some Japanese convension is
     * applied to creating "formatted" something like FORMATTED_ADDRESS.
     */
    public static boolean isJapaneseDevice(int vcardType) {
        return ((vcardType == VCARD_TYPE_V21_JAPANESE) ||
                (vcardType == VCARD_TYPE_V21_JAPANESE_UTF8) ||
                (vcardType == VCARD_TYPE_V30_JAPANESE) ||
                (vcardType == VCARD_TYPE_V30_JAPANESE_UTF8) ||
                (vcardType == VCARD_TYPE_DOCOMO));
    }

    public static boolean usesUtf8(int vcardType) {
        return ((vcardType & FLAG_CHARSET_UTF8) != 0);
    }

    public static boolean usesShiftJis(int vcardType) {
        return ((vcardType & FLAG_CHARSET_SHIFT_JIS) != 0);
    }
    
    /**
     * @return true when Japanese phonetic string must be converted to a string
     * containing only half-width katakana. This method exists since Japanese mobile
     * phones usually use only half-width katakana for expressing phonetic names and
     * some devices are not ready for parsing other phonetic strings like hiragana and
     * full-width katakana.
     */
    public static boolean needsToConvertPhoneticString(int vcardType) {
        return (vcardType == VCARD_TYPE_DOCOMO);
    }

    public static int getNameOrderType(int vcardType) {
        return vcardType & NAME_ORDER_MASK;
    }

    public static boolean usesAndroidSpecificProperty(int vcardType) {
        return ((vcardType & FLAG_USE_ANDROID_PROPERTY) != 0);
    }

    public static boolean usesDefactProperty(int vcardType) {
        return ((vcardType & FLAG_USE_DEFACT_PROPERTY) != 0);
    }

    public static boolean onlyOneNoteFieldIsAvailable(int vcardType) {
        return vcardType == VCARD_TYPE_DOCOMO;
    }

    public static boolean showPerformanceLog() {
        return (VCardConfig.LOG_LEVEL & VCardConfig.LOG_LEVEL_PERFORMANCE_MEASUREMENT) != 0;
    }

    /**
     * @hide
     */
    public static boolean usesQPToPrimaryProperties(int vcardType) {
       return (usesQuotedPrintable(vcardType) &&
               ((vcardType & FLAG_USE_QP_TO_PRIMARY_PROPERTIES) != 0));
    }

    private VCardConfig() {
    }
}