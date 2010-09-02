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

import android.telephony.PhoneNumberUtils;
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

    /**
     * <p>
     * The charset used during import.
     * </p>
     * <p>
     * We cannot determine which charset should be used to interpret lines in vCard,
     * while Java requires us to specify it when InputStream is used.
     * We need to rely on the mechanism due to some performance reason.
     * </p>
     * <p>
     * In order to avoid "misinterpretation" of charset and lose any data in vCard,
     * "ISO-8859-1" is first used for reading the stream.
     * When a charset is specified in a property (with "CHARSET=..." parameter),
     * the string is decoded to raw bytes and encoded into the specific charset,
     * </p>
     * <p>
     * Unicode specification there's a one to one mapping between each byte in ISO-8859-1
     * and a codepoint, and Java specification requires runtime must have the charset.
     * Thus, ISO-8859-1 is one effective mapping for intermediate mapping.
     * </p>
     */
    public static final String DEFAULT_INTERMEDIATE_CHARSET = "ISO-8859-1";

    /**
     * The charset used when there's no information affbout what charset should be used to
     * encode the binary given from vCard.
     */
    public static final String DEFAULT_IMPORT_CHARSET = "UTF-8";
    public static final String DEFAULT_EXPORT_CHARSET = "UTF-8";

    /**
     * Do not use statically like "version == VERSION_V21"
     */
    public static final int VERSION_21 = 0;
    public static final int VERSION_30 = 1;
    public static final int VERSION_40 = 2;
    public static final int VERSION_MASK = 3;

    public static final int NAME_ORDER_DEFAULT = 0;
    public static final int NAME_ORDER_EUROPE = 0x4;
    public static final int NAME_ORDER_JAPANESE = 0x8;
    private static final int NAME_ORDER_MASK = 0xC;

    // 0x10 is reserved for safety

    /**
     * <p>
     * The flag indicating the vCard composer will add some "X-" properties used only in Android
     * when the formal vCard specification does not have appropriate fields for that data.
     * </p>
     * <p>
     * For example, Android accepts nickname information while vCard 2.1 does not.
     * When this flag is on, vCard composer emits alternative "X-" property (like "X-NICKNAME")
     * instead of just dropping it.
     * </p>
     * <p>
     * vCard parser code automatically parses the field emitted even when this flag is off.
     * </p>
     */
    private static final int FLAG_USE_ANDROID_PROPERTY = 0x80000000;
    
    /**
     * <p>
     * The flag indicating the vCard composer will add some "X-" properties seen in the
     * vCard data emitted by the other softwares/devices when the formal vCard specification
     * does not have appropriate field(s) for that data.
     * </p> 
     * <p>
     * One example is X-PHONETIC-FIRST-NAME/X-PHONETIC-MIDDLE-NAME/X-PHONETIC-LAST-NAME, which are
     * for phonetic name (how the name is pronounced), seen in the vCard emitted by some other
     * non-Android devices/softwares. We chose to enable the vCard composer to use those
     * defact properties since they are also useful for Android devices.
     * </p>
     * <p>
     * Note for developers: only "X-" properties should be added with this flag. vCard 2.1/3.0
     * allows any kind of "X-" properties but does not allow non-"X-" properties (except IANA tokens
     * in vCard 3.0). Some external parsers may get confused with non-valid, non-"X-" properties.
     * </p>
     */
    private static final int FLAG_USE_DEFACT_PROPERTY = 0x40000000;

    /**
     * <p>
     * The flag indicating some specific dialect seen in vCard of DoCoMo (one of Japanese
     * mobile careers) should be used. This flag does not include any other information like
     * that "the vCard is for Japanese". So it is "possible" that "the vCard should have DoCoMo's
     * dialect but the name order should be European", but it is not recommended.
     * </p>
     */
    private static final int FLAG_DOCOMO = 0x20000000;

    /**
     * <p>
     * The flag indicating the vCard composer does "NOT" use Quoted-Printable toward "primary"
     * properties even though it is required by vCard 2.1 (QP is prohibited in vCard 3.0).
     * </p>
     * <p>
     * We actually cannot define what is the "primary" property. Note that this is NOT defined
     * in vCard specification either. Also be aware that it is NOT related to "primary" notion
     * used in {@link android.provider.ContactsContract}.
     * This notion is just for vCard composition in Android.
     * </p>
     * <p>
     * We added this Android-specific notion since some (incomplete) vCard exporters for vCard 2.1
     * do NOT use Quoted-Printable encoding toward some properties related names like "N", "FN", etc.
     * even when their values contain non-ascii or/and CR/LF, while they use the encoding in the
     * other properties like "ADR", "ORG", etc.
     * <p>
     * We are afraid of the case where some vCard importer also forget handling QP presuming QP is
     * not used in such fields.
     * </p>
     * <p>
     * This flag is useful when some target importer you are going to focus on does not accept
     * such properties with Quoted-Printable encoding.
     * </p>
     * <p>
     * Again, we should not use this flag at all for complying vCard 2.1 spec.
     * </p>
     * <p>
     * In vCard 3.0, Quoted-Printable is explicitly "prohibitted", so we don't need to care this
     * kind of problem (hopefully).
     * </p>
     * @hide
     */
    public static final int FLAG_REFRAIN_QP_TO_NAME_PROPERTIES = 0x10000000;

    /**
     * <p>
     * The flag indicating that phonetic name related fields must be converted to
     * appropriate form. Note that "appropriate" is not defined in any vCard specification.
     * This is Android-specific.
     * </p>
     * <p>
     * One typical (and currently sole) example where we need this flag is the time when
     * we need to emit Japanese phonetic names into vCard entries. The property values
     * should be encoded into half-width katakana when the target importer is Japanese mobile
     * phones', which are probably not able to parse full-width hiragana/katakana for
     * historical reasons, while the vCard importers embedded to softwares for PC should be
     * able to parse them as we expect.
     * </p>
     */
    public static final int FLAG_CONVERT_PHONETIC_NAME_STRINGS = 0x08000000;

    /**
     * <p>
     * The flag indicating the vCard composer "for 2.1" emits "TYPE=" string toward TYPE params
     * every time possible. The default behavior does not emit it and is valid in the spec.
     * In vCrad 3.0, this flag is unnecessary, since "TYPE=" is MUST in vCard 3.0 specification.
     * </p>
     * <p>
     * Detail:
     * How more than one TYPE fields are expressed is different between vCard 2.1 and vCard 3.0.
     * </p>
     * <p>
     * e.g.
     * </p>
     * <ol>
     * <li>Probably valid in both vCard 2.1 and vCard 3.0: "ADR;TYPE=DOM;TYPE=HOME:..."</li>
     * <li>Valid in vCard 2.1 but not in vCard 3.0: "ADR;DOM;HOME:..."</li>
     * <li>Valid in vCard 3.0 but not in vCard 2.1: "ADR;TYPE=DOM,HOME:..."</li>
     * </ol>
     * <p>
     * If you are targeting to the importer which cannot accept TYPE params without "TYPE="
     * strings (which should be rare though), please use this flag.
     * </p>
     * <p>
     * Example usage:
     * <pre class="prettyprint">int type = (VCARD_TYPE_V21_GENERIC | FLAG_APPEND_TYPE_PARAM);</pre>
     * </p>
     */
    public static final int FLAG_APPEND_TYPE_PARAM = 0x04000000;

    /**
     * <p>
     * The flag indicating the vCard composer does touch nothing toward phone number Strings
     * but leave it as is.
     * </p>
     * <p>
     * The vCard specifications mention nothing toward phone numbers, while some devices
     * do (wrongly, but with innevitable reasons).
     * For example, there's a possibility Japanese mobile phones are expected to have
     * just numbers, hypens, plus, etc. but not usual alphabets, while US mobile phones
     * should get such characters. To make exported vCard simple for external parsers,
     * we have used {@link PhoneNumberUtils#formatNumber(String)} during export, and
     * removed unnecessary characters inside the number (e.g. "111-222-3333 (Miami)"
     * becomes "111-222-3333").
     * Unfortunate side effect of that use was some control characters used in the other
     * areas may be badly affected by the formatting.
     * </p>
     * <p>
     * This flag disables that formatting, affecting both importer and exporter.
     * If the user is aware of some side effects due to the implicit formatting, use this flag.
     * </p>
     */
    public static final int FLAG_REFRAIN_PHONE_NUMBER_FORMATTING = 0x02000000;

    /**
     * <p>
     * For importer only. Ignored in exporter.
     * </p>
     * <p>
     * The flag indicating the parser should handle a nested vCard, in which vCard clause starts
     * in another vCard clause. Here's a typical example.
     * </p>
     * <pre class="prettyprint">BEGIN:VCARD
     * BEGIN:VCARD
     * VERSION:2.1
     * ...
     * END:VCARD
     * END:VCARD</pre>
     * <p>
     * The vCard 2.1 specification allows the nest, but also let parsers ignore nested entries,
     * while some mobile devices emit nested ones as primary data to be imported.
     * </p>
     * <p>
     * This flag forces a vCard parser to torelate such a nest and understand its content.
     * </p>
     */
    public static final int FLAG_TORELATE_NEST = 0x01000000;

    //// The followings are VCard types available from importer/exporter. ////

    public static final int FLAG_REFRAIN_IMAGE_EXPORT = 0x00800000;

    /**
     * <p>
     * The type indicating nothing. Used by {@link VCardSourceDetector} when it
     * was not able to guess the exact vCard type.
     * </p>
     */
    public static final int VCARD_TYPE_UNKNOWN = 0;

    /**
     * <p>
     * Generic vCard format with the vCard 2.1. When composing a vCard entry,
     * the US convension will be used toward formatting some values.
     * </p>
     * <p>
     * e.g. The order of the display name would be "Prefix Given Middle Family Suffix",
     * while it should be "Prefix Family Middle Given Suffix" in Japan for example.
     * </p>
     * <p>
     * Uses UTF-8 for the charset as a charset for exporting. Note that old vCard importer
     * outside Android cannot accept it since vCard 2.1 specifically does not allow
     * that charset, while we need to use it to support various languages around the world.
     * </p>
     * <p>
     * If you want to use alternative charset, you should notify the charset to the other
     * compontent to be used.
     * </p>
     */
    public static final int VCARD_TYPE_V21_GENERIC =
        (VERSION_21 | NAME_ORDER_DEFAULT | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static String VCARD_TYPE_V21_GENERIC_STR = "v21_generic";
    
    /**
     * <p>
     * General vCard format with the version 3.0. Uses UTF-8 for the charset.
     * </p>
     * <p>
     * Not fully ready yet. Use with caution when you use this.
     * </p>
     */
    public static final int VCARD_TYPE_V30_GENERIC =
        (VERSION_30 | NAME_ORDER_DEFAULT | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_GENERIC_STR = "v30_generic";

    /**
     * General vCard format with the version 4.0.
     * @hide vCard 4.0 is not published yet.
     */
    public static final int VCARD_TYPE_V40_GENERIC =
        (VERSION_40 | NAME_ORDER_DEFAULT | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V40_GENERIC_STR = "v40_generic";

    /**
     * <p>
     * General vCard format for the vCard 2.1 with some Europe convension. Uses Utf-8.
     * Currently, only name order is considered ("Prefix Middle Given Family Suffix")
     * </p>
     */
    public static final int VCARD_TYPE_V21_EUROPE =
        (VERSION_21 | NAME_ORDER_EUROPE | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_EUROPE_STR = "v21_europe";
    
    /**
     * <p>
     * General vCard format with the version 3.0 with some Europe convension. Uses UTF-8.
     * </p>
     * <p>
     * Not ready yet. Use with caution when you use this.
     * </p>
     */
    public static final int VCARD_TYPE_V30_EUROPE =
        (VERSION_30 | NAME_ORDER_EUROPE | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);
    
    /* package */ static final String VCARD_TYPE_V30_EUROPE_STR = "v30_europe";

    /**
     * <p>
     * The vCard 2.1 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     * </p>
     * <p>
     * Not ready yet. Use with caution when you use this.
     * </p>
     */
    public static final int VCARD_TYPE_V21_JAPANESE =
        (VERSION_21 | NAME_ORDER_JAPANESE | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_STR = "v21_japanese_utf8";

    /**
     * <p>
     * The vCard 3.0 format for miscellaneous Japanese devices, using UTF-8 as default charset.
     * </p>
     * <p>
     * Not ready yet. Use with caution when you use this.
     * </p>
     */
    public static final int VCARD_TYPE_V30_JAPANESE =
        (VERSION_30 | NAME_ORDER_JAPANESE | FLAG_USE_DEFACT_PROPERTY | FLAG_USE_ANDROID_PROPERTY);

    /* package */ static final String VCARD_TYPE_V30_JAPANESE_STR = "v30_japanese_utf8";

    /**
     * <p>
     * The vCard 2.1 based format which (partially) considers the convention in Japanese
     * mobile phones, where phonetic names are translated to half-width katakana if
     * possible, etc. It would be better to use Shift_JIS as a charset for maximum
     * compatibility.
     * </p>
     * @hide Should not be available world wide.
     */
    public static final int VCARD_TYPE_V21_JAPANESE_MOBILE =
        (VERSION_21 | NAME_ORDER_JAPANESE |
                FLAG_CONVERT_PHONETIC_NAME_STRINGS | FLAG_REFRAIN_QP_TO_NAME_PROPERTIES);

    /* package */ static final String VCARD_TYPE_V21_JAPANESE_MOBILE_STR = "v21_japanese_mobile";

    /**
     * <p>
     * The vCard format used in DoCoMo, which is one of Japanese mobile phone careers.
     * </p>
     * <p>
     * Base version is vCard 2.1, but the data has several DoCoMo-specific convensions.
     * No Android-specific property nor defact property is included. The "Primary" properties
     * are NOT encoded to Quoted-Printable.
     * </p>
     * @hide Should not be available world wide.
     */
    public static final int VCARD_TYPE_DOCOMO =
        (VCARD_TYPE_V21_JAPANESE_MOBILE | FLAG_DOCOMO);

    /* package */ static final String VCARD_TYPE_DOCOMO_STR = "docomo";

    public static int VCARD_TYPE_DEFAULT = VCARD_TYPE_V21_GENERIC;

    private static final Map<String, Integer> sVCardTypeMap;
    private static final Set<Integer> sJapaneseMobileTypeSet;
    
    static {
        sVCardTypeMap = new HashMap<String, Integer>();
        sVCardTypeMap.put(VCARD_TYPE_V21_GENERIC_STR, VCARD_TYPE_V21_GENERIC);
        sVCardTypeMap.put(VCARD_TYPE_V30_GENERIC_STR, VCARD_TYPE_V30_GENERIC);
        sVCardTypeMap.put(VCARD_TYPE_V21_EUROPE_STR, VCARD_TYPE_V21_EUROPE);
        sVCardTypeMap.put(VCARD_TYPE_V30_EUROPE_STR, VCARD_TYPE_V30_EUROPE);
        sVCardTypeMap.put(VCARD_TYPE_V21_JAPANESE_STR, VCARD_TYPE_V21_JAPANESE);
        sVCardTypeMap.put(VCARD_TYPE_V30_JAPANESE_STR, VCARD_TYPE_V30_JAPANESE);
        sVCardTypeMap.put(VCARD_TYPE_V21_JAPANESE_MOBILE_STR, VCARD_TYPE_V21_JAPANESE_MOBILE);
        sVCardTypeMap.put(VCARD_TYPE_DOCOMO_STR, VCARD_TYPE_DOCOMO);

        sJapaneseMobileTypeSet = new HashSet<Integer>();
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V21_JAPANESE);
        sJapaneseMobileTypeSet.add(VCARD_TYPE_V30_JAPANESE);
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

    public static boolean isVersion21(final int vcardType) {
        return (vcardType & VERSION_MASK) == VERSION_21;
    }

    public static boolean isVersion30(final int vcardType) {
        return (vcardType & VERSION_MASK) == VERSION_30;
    }

    public static boolean isVersion40(final int vcardType) {
        return (vcardType & VERSION_MASK) == VERSION_40;
    }

    public static boolean shouldUseQuotedPrintable(final int vcardType) {
        return !isVersion30(vcardType);
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
        return (isVersion30(vcardType) || ((vcardType & FLAG_APPEND_TYPE_PARAM) != 0));
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

    /* package */ static boolean refrainPhoneNumberFormatting(final int vcardType) {
        return ((vcardType & FLAG_REFRAIN_PHONE_NUMBER_FORMATTING) != 0);
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