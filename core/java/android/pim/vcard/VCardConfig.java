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

import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The class representing VCard related configurations. Useful static methods are not in this class
 * but in VCardUtils.
 */
public class VCardConfig {
    private static final String LOG_TAG = "VCardConfig";

    /* package */ static final int LOG_LEVEL_NONE = 0;
    /* package */ static final int LOG_LEVEL_PERFORMANCE_MEASUREMENT = 0x1;
    /* package */ static final int LOG_LEVEL_SHOW_WARNING = 0x2;
    /* package */ static final int LOG_LEVEL_VERBOSE =
        LOG_LEVEL_PERFORMANCE_MEASUREMENT | LOG_LEVEL_SHOW_WARNING;

    /* package */ static final int LOG_LEVEL = LOG_LEVEL_NONE;

    /* package */ static final int PARSE_TYPE_UNKNOWN = 0;
    /* package */ static final int PARSE_TYPE_APPLE = 1;
    /* package */ static final int PARSE_TYPE_MOBILE_PHONE_JP = 2;  // For Japanese mobile phones.
    /* package */ static final int PARSE_TYPE_FOMA = 3;  // For Japanese FOMA mobile phones.
    /* package */ static final int PARSE_TYPE_WINDOWS_MOBILE_JP = 4;

    // Assumes that "iso-8859-1" is able to map "all" 8bit characters to some unicode and
    // decode the unicode to the original charset. If not, this setting will cause some bug. 
    public static final String DEFAULT_CHARSET = "iso-8859-1";
    
    public static final int FLAG_V21 = 0;
    public static final int FLAG_V30 = 1;

    // 0x2 is reserved for the future use ...

    public static final int NAME_ORDER_DEFAULT = 0;
    public static final int NAME_ORDER_EUROPE = 0x4;
    public static final int NAME_ORDER_JAPANESE = 0x8;
    private static final int NAME_ORDER_MASK = 0xC;

    // 0x10 is reserved for safety
    
    private static final int FLAG_CHARSET_UTF8 = 0;
    private static final int FLAG_CHARSET_SHIFT_JIS = 0x100;
    private static final int FLAG_CHARSET_MASK = 0xF00;

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
     * <P>
     * The flag indicating the vCard composer does "NOT" use Quoted-Printable toward "primary"
     * properties even though it is required by vCard 2.1 (QP is prohibited in vCard 3.0).
     * </P>
     * <P>
     * We actually cannot define what is the "primary" property. Note that this is NOT defined
     * in vCard specification either. Also be aware that it is NOT related to "primary" notion
     * used in {@link android.provider.ContactsContract}.
     * This notion is just for vCard composition in Android.
     * </P>
     * <P>
     * We added this Android-specific notion since some (incomplete) vCard exporters for vCard 2.1
     * do NOT use Quoted-Printable encoding toward some properties related names like "N", "FN", etc.
     * even when their values contain non-ascii or/and CR/LF, while they use the encoding in the
     * other properties like "ADR", "ORG", etc.
     * <P>
     * We are afraid of the case where some vCard importer also forget handling QP presuming QP is
     * not used in such fields.
     * </P>
     * <P>
     * This flag is useful when some target importer you are going to focus on does not accept
     * such properties with Quoted-Printable encoding.
     * </P>
     * <P>
     * Again, we should not use this flag at all for complying vCard 2.1 spec.
     * </P>
     * <P>
     * In vCard 3.0, Quoted-Printable is explicitly "prohibitted", so we don't need to care this
     * kind of problem (hopefully).
     * </P>
     */
    public static final int FLAG_REFRAIN_QP_TO_NAME_PROPERTIES = 0x10000000;

    /**
     * <P>
     * The flag indicating that phonetic name related fields must be converted to
     * appropriate form. Note that "appropriate" is not defined in any vCard specification.
     * This is Android-specific.
     * </P>
     * <P>
     * One typical (and currently sole) example where we need this flag is the time when
     * we need to emit Japanese phonetic names into vCard entries. The property values
     * should be encoded into half-width katakana when the target importer is Japanese mobile
     * phones', which are probably not able to parse full-width hiragana/katakana for
     * historical reasons, while the vCard importers embedded to softwares for PC should be
     * able to parse them as we expect.
     * </P>
     */
    public static final int FLAG_CONVERT_PHONETIC_NAME_STRINGS = 0x0800000;

    /**
     * <P>
     * The flag indicating the vCard composer "for 2.1" emits "TYPE=" string toward TYPE params
     * every time possible. The default behavior does not emit it and is valid in the spec.
     * In vCrad 3.0, this flag is unnecessary, since "TYPE=" is MUST in vCard 3.0 specification.
     * </P>
     * <P>
     * Detail:
     * How more than one TYPE fields are expressed is different between vCard 2.1 and vCard 3.0.
     * </p>
     * <P>
     * e.g.<BR />
     * 1) Probably valid in both vCard 2.1 and vCard 3.0: "ADR;TYPE=DOM;TYPE=HOME:..."<BR />
     * 2) Valid in vCard 2.1 but not in vCard 3.0: "ADR;DOM;HOME:..."<BR />
     * 3) Valid in vCard 3.0 but not in vCard 2.1: "ADR;TYPE=DOM,HOME:..."<BR />
     * </P>
     * <P>
     * 2) had been the default of VCard exporter/importer in Android, but it is found that
     * some external exporter is not able to parse the type format like 2) but only 3).
     * </P>
     * <P>
     * If you are targeting to the importer which cannot accept TYPE params without "TYPE="
     * strings (which should be rare though), please use this flag.
     * </P>
     * <P>
     * Example usage: int vcardType = (VCARD_TYPE_V21_GENERIC | FLAG_APPEND_TYPE_PARAM);
     * </P>
     */
    public static final int FLAG_APPEND_TYPE_PARAM = 0x04000000;

    /**
     * <P>
     * The flag asking exporter to refrain image export.
     * </P>
     * @hide will be deleted in the near future.
     */
    public static final int FLAG_REFRAIN_IMAGE_EXPORT = 0x02000000;

    //// The followings are VCard types available from importer/exporter. ////

    /**
     * <P>
     * Generic vCard format with the vCard 2.1. Uses UTF-8 for the charset.
     * When composing a vCard entry, the US convension will be used toward formatting
     * some values.
     * </P>
     * <P>
     * e.g. The order of the display name would be "Prefix Given Middle Family Suffix",
     * while it should be "Prefix Family Middle Given Suffix" in Japan for example.
     * </P>
     */
    public static final int VCARD_TYPE_V21_GENERIC_UTF8 =
        (FLAG_V21 | NAME_ORDER_DEFAULT | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static String VCARD_TYPE_V21_GENERIC_UTF8_STR = "v21_generic";
    
    /**
     * <P>
     * General vCard format with the version 3.0. Uses UTF-8 for the charset.
     * </P>
     * <P>
     * Not fully ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V30_GENERIC_UTF8 =
        (FLAG_V30 | NAME_ORDER_DEFAULT | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_GENERIC_UTF8_STR = "v30_generic";
    
    /**
     * <P>
     * General vCard format for the vCard 2.1 with some Europe convension. Uses Utf-8.
     * Currently, only name order is considered ("Prefix Middle Given Family Suffix")
     * </P>
     */
    public static final int VCARD_TYPE_V21_EUROPE_UTF8 =
        (FLAG_V21 | NAME_ORDER_EUROPE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
    
    /* package */ static final String VCARD_TYPE_V21_EUROPE_UTF8_STR = "v21_europe";
    
    /**
     * <P>
     * General vCard format with the version 3.0 with some Europe convension. Uses UTF-8.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V30_EUROPE_UTF8 =
        (FLAG_V30 | NAME_ORDER_EUROPE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
    
    /* package */ static final String VCARD_TYPE_V30_EUROPE_STR = "v30_europe";

    /**
     * <P>
     * The vCard 2.1 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V21_JAPANESE_UTF8 =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_UTF8_STR = "v21_japanese_utf8";

    /**
     * <P>
     * vCard 2.1 format for miscellaneous Japanese devices. Shift_Jis is used for
     * parsing/composing the vCard data.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V21_JAPANESE_SJIS =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_SJIS_STR = "v21_japanese_sjis";
    
    /**
     * <P>
     * vCard format for miscellaneous Japanese devices, using Shift_Jis for
     * parsing/composing the vCard data.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V30_JAPANESE_SJIS =
        (FLAG_V30 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
        
    /* package */ static final String VCARD_TYPE_V30_JAPANESE_SJIS_STR = "v30_japanese_sjis";
    
    /**
     * <P>
     * The vCard 3.0 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V30_JAPANESE_UTF8 =
        (FLAG_V30 | NAME_ORDER_JAPANESE | FLAG_CHARSET_UTF8 |
                FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_JAPANESE_UTF8_STR = "v30_japanese_utf8";

    /**
     * <P>
     * The vCard 2.1 based format which (partially) considers the convention in Japanese
     * mobile phones, where phonetic names are translated to half-width katakana if
     * possible, etc.
     * </P>
     * <P>
     * Not ready yet. Use with caution when you use this.
     * </P>
     */
    public static final int VCARD_TYPE_V21_JAPANESE_MOBILE =
        (FLAG_V21 | NAME_ORDER_JAPANESE | FLAG_CHARSET_SHIFT_JIS |
                FLAG_CONVERT_PHONETIC_NAME_STRINGS |
                FLAG_REFRAIN_QP_TO_NAME_PROPERTIES);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_MOBILE_STR = "v21_japanese_mobile";

    /**
     * <P>
     * VCard format used in DoCoMo, which is one of Japanese mobile phone careers.
     * </p>
     * <P>
     * Base version is vCard 2.1, but the data has several DoCoMo-specific convensions.
     * No Android-specific property nor defact property is included. The "Primary" properties
     * are NOT encoded to Quoted-Printable.
     * </P>
     */
    public static final int VCARD_TYPE_DOCOMO =
        (VCARD_TYPE_V21_JAPANESE_MOBILE | FLAG_DOCOMO);

    /* package */ static final String VCARD_TYPE_DOCOMO_STR = "docomo";

    public static int VCARD_TYPE_DEFAULT = VCARD_TYPE_V21_GENERIC_UTF8;

    private static final Map<String, Integer> sVCardTypeMap;
    private static final Set<Integer> sJapaneseMobileTypeSet;
    
    static {
        sVCardTypeMap = new HashMap<String, Integer>();
        sVCardTypeMap.put(VCARD_TYPE_V21_GENERIC_UTF8_STR, VCARD_TYPE_V21_GENERIC_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V30_GENERIC_UTF8_STR, VCARD_TYPE_V30_GENERIC_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V21_EUROPE_UTF8_STR, VCARD_TYPE_V21_EUROPE_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V30_EUROPE_STR, VCARD_TYPE_V30_EUROPE_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V21_JAPANESE_SJIS_STR, VCARD_TYPE_V21_JAPANESE_SJIS);
        sVCardTypeMap.put(VCARD_TYPE_V21_JAPANESE_UTF8_STR, VCARD_TYPE_V21_JAPANESE_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V30_JAPANESE_SJIS_STR, VCARD_TYPE_V30_JAPANESE_SJIS);
        sVCardTypeMap.put(VCARD_TYPE_V30_JAPANESE_UTF8_STR, VCARD_TYPE_V30_JAPANESE_UTF8);
        sVCardTypeMap.put(VCARD_TYPE_V21_JAPANESE_MOBILE_STR, VCARD_TYPE_V21_JAPANESE_MOBILE);
        sVCardTypeMap.put(VCARD_TYPE_DOCOMO_STR, VCARD_TYPE_DOCOMO);

        sJapaneseMobileTypeSet = new HashSet<Integer>();
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V21_JAPANESE_SJIS);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V21_JAPANESE_UTF8);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V21_JAPANESE_SJIS);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V30_JAPANESE_SJIS);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V30_JAPANESE_UTF8);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V21_JAPANESE_MOBILE);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_DOCOMO);
    }

    public static int getVCardTypeFromString(final String vcardTypeString) {
        final String loweredKey = vcardTypeString.toLowerCase();
        if (sVCardTypeMap.containsKey(loweredKey)) {
            return sVCardTypeMap.get(loweredKey);
        } else if ("default".equalsIgnoreCase(vcardTypeString)) {
            return VCARD_TYPE_DEFAULT;
        } else {
            Log.e(LOG_TAG, "Unknown vCard type String: \"" + vcardTypeString + "\"");
            return VCARD_TYPE_DEFAULT;
        }
    }

    public static boolean isV30(final int vcardType) {
        return ((vcardType & FLAG_V30) != 0);  
    }

    public static boolean shouldUseQuotedPrintable(final int vcardType) {
        return !isV30(vcardType);
    }

    public static boolean usesUtf8(final int vcardType) {
        return ((vcardType & FLAG_CHARSET_MASK) == FLAG_CHARSET_UTF8);
    }

    public static boolean usesShiftJis(final int vcardType) {
        return ((vcardType & FLAG_CHARSET_MASK) == FLAG_CHARSET_SHIFT_JIS);
    }

    public static int getNameOrderType(final int vcardType) {
        return vcardType & NAME_ORDER_MASK;
    }

    public static boolean usesAndroidSpecificProperty(final int vcardType) {
        return ((vcardType & FLAG_USE_ANDROID_PROPERTY) != 0);
    }

    public static boolean usesDefactProperty(final int vcardType) {
        return ((vcardType & FLAG_USE_DEFACT_PROPERTY) != 0);
    }

    public static boolean showPerformanceLog() {
        return (VCardConfig.LOG_LEVEL & VCardConfig.LOG_LEVEL_PERFORMANCE_MEASUREMENT) != 0;
    }

    public static boolean shouldRefrainQPToNameProperties(final int vcardType) {
       return (!shouldUseQuotedPrintable(vcardType) ||
               ((vcardType & FLAG_REFRAIN_QP_TO_NAME_PROPERTIES) != 0));
    }

    public static boolean appendTypeParamName(final int vcardType) {
        return (isV30(vcardType) || ((vcardType & FLAG_APPEND_TYPE_PARAM) != 0));
    }

    /**
     * @return true if the device is Japanese and some Japanese convension is
     * applied to creating "formatted" something like FORMATTED_ADDRESS.
     */
    public static boolean isJapaneseDevice(final int vcardType) {
        // TODO: Some mask will be required so that this method wrongly interpret
        //        Japanese"-like" vCard type.
        //        e.g. VCARD_TYPE_V21_JAPANESE_SJIS | FLAG_APPEND_TYPE_PARAMS
        return sJapaneseMobileTypeSet.contains(vcardType);
    }

    public static boolean needsToConvertPhoneticString(final int vcardType) {
        return ((vcardType & FLAG_CONVERT_PHONETIC_NAME_STRINGS) != 0);
    }

    public static boolean onlyOneNoteFieldIsAvailable(final int vcardType) {
        return vcardType == VCARD_TYPE_DOCOMO;
    }

    public static boolean isDoCoMo(final int vcardType) {
        return ((vcardType & FLAG_DOCOMO) != 0);
    }

    private VCardConfig() {
    }
}