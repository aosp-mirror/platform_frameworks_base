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

/**
 * Constants used in both exporter and importer code.
 */
/* package */ class Constants {

    public static final String VERSION_V21 = "2.1";
    public static final String VERSION_V30 = "3.0";

    // The property names valid both in vCard 2.1 and 3.0.
    public static final String PROPERTY_BEGIN = "BEGIN";
    public static final String PROPERTY_VERSION = "VERSION";
    public static final String PROPERTY_N = "N";
    public static final String PROPERTY_FN = "FN";
    public static final String PROPERTY_ADR = "ADR";
    public static final String PROPERTY_EMAIL = "EMAIL";
    public static final String PROPERTY_NOTE = "NOTE";
    public static final String PROPERTY_ORG = "ORG";
    public static final String PROPERTY_SOUND = "SOUND";  // Not fully supported.
    public static final String PROPERTY_TEL = "TEL";
    public static final String PROPERTY_TITLE = "TITLE";
    public static final String PROPERTY_ROLE = "ROLE";
    public static final String PROPERTY_PHOTO = "PHOTO";
    public static final String PROPERTY_LOGO = "LOGO";
    public static final String PROPERTY_URL = "URL";
    public static final String PROPERTY_BDAY = "BDAY";  // Birthday
    public static final String PROPERTY_END = "END";

    // Valid property names not supported (not appropriately handled) by our vCard importer now.
    public static final String PROPERTY_REV = "REV";
    public static final String PROPERTY_AGENT = "AGENT";

    // Available in vCard 3.0. Shoud not use when composing vCard 2.1 file.
    public static final String PROPERTY_NAME = "NAME";
    public static final String PROPERTY_NICKNAME = "NICKNAME";
    public static final String PROPERTY_SORT_STRING = "SORT-STRING";
    
    // De-fact property values expressing phonetic names.
    public static final String PROPERTY_X_PHONETIC_FIRST_NAME = "X-PHONETIC-FIRST-NAME";
    public static final String PROPERTY_X_PHONETIC_MIDDLE_NAME = "X-PHONETIC-MIDDLE-NAME";
    public static final String PROPERTY_X_PHONETIC_LAST_NAME = "X-PHONETIC-LAST-NAME";

    // Properties both ContactsStruct in Eclair and de-fact vCard extensions
    // shown in http://en.wikipedia.org/wiki/VCard support are defined here.
    public static final String PROPERTY_X_AIM = "X-AIM";
    public static final String PROPERTY_X_MSN = "X-MSN";
    public static final String PROPERTY_X_YAHOO = "X-YAHOO";
    public static final String PROPERTY_X_ICQ = "X-ICQ";
    public static final String PROPERTY_X_JABBER = "X-JABBER";
    public static final String PROPERTY_X_GOOGLE_TALK = "X-GOOGLE-TALK";
    public static final String PROPERTY_X_SKYPE_USERNAME = "X-SKYPE-USERNAME";
    // Properties only ContactsStruct has. We alse use this.
    public static final String PROPERTY_X_QQ = "X-QQ";
    public static final String PROPERTY_X_NETMEETING = "X-NETMEETING";

    // Phone number for Skype, available as usual phone.
    public static final String PROPERTY_X_SKYPE_PSTNNUMBER = "X-SKYPE-PSTNNUMBER";

    // Property for Android-specific fields.
    public static final String PROPERTY_X_ANDROID_CUSTOM = "X-ANDROID-CUSTOM";

    // Properties for DoCoMo vCard.
    public static final String PROPERTY_X_CLASS = "X-CLASS";
    public static final String PROPERTY_X_REDUCTION = "X-REDUCTION";
    public static final String PROPERTY_X_NO = "X-NO";
    public static final String PROPERTY_X_DCM_HMN_MODE = "X-DCM-HMN-MODE";

    // For some historical reason, we often use the term "ATTR"/"attribute" especially toward
    // what is called "param" in both vCard specs, while vCard, while vCard importer side uses
    // "param".
    //
    // TODO: Confusing. Fix it.
    public static final String ATTR_TYPE = "TYPE";

    // How more than one TYPE fields are expressed is different between vCard 2.1 and vCard 3.0
    //
    // e.g.
    // 1) Probably valid in both vCard 2.1 and vCard 3.0: "ADR;TYPE=DOM;TYPE=HOME:..." 
    // 2) Valid in vCard 2.1 but not in vCard 3.0: "ADR;DOM;HOME:..."
    // 3) Valid in vCard 3.0 but not in vCard 2.1: "ADR;TYPE=DOM,HOME:..."
    //
    // 2) has been the default of VCard exporter/importer in Android, but we can see the other
    // formats in vCard data emitted by the other softwares/devices.
    //
    // So we are currently not sure which type is the best; probably we will have to change which
    // type should be emitted depending on the device.
    public static final String ATTR_TYPE_HOME = "HOME";
    public static final String ATTR_TYPE_WORK = "WORK";
    public static final String ATTR_TYPE_FAX = "FAX";
    public static final String ATTR_TYPE_CELL = "CELL";
    public static final String ATTR_TYPE_VOICE = "VOICE";
    public static final String ATTR_TYPE_INTERNET = "INTERNET";

    // Abbreviation of "prefered" according to vCard 2.1 specification.
    // We interpret this value as "primary" property during import/export.
    //
    // Note: Both vCard specs does anything about the requirement about this attribute,
    //       but there may be some vCard importer which will get confused with more than
    //       one "PREF"s in one property name, while Android accepts them.
    public static final String ATTR_TYPE_PREF = "PREF";

    // Phone types valid in vCard and known to ContactsContract, but not so common.
    public static final String ATTR_TYPE_CAR = "CAR";
    public static final String ATTR_TYPE_ISDN = "ISDN";
    public static final String ATTR_TYPE_PAGER = "PAGER";
    public static final String ATTR_TYPE_TLX = "TLX";  // Telex

    // Phone types existing in vCard 2.1 but not known to ContactsContract.
    // TODO: should make parser make these TYPE_CUSTOM.
    public static final String ATTR_TYPE_MODEM = "MODEM";
    public static final String ATTR_TYPE_MSG = "MSG";
    public static final String ATTR_TYPE_BBS = "BBS";
    public static final String ATTR_TYPE_VIDEO = "VIDEO";

    // Attribute for Phones, which are not formally valid in vCard (at least 2.1).
    // These types are basically encoded to "X-" attributes when composing vCard.
    // Parser passes these when "X-" is added to the attribute or not.
    public static final String ATTR_PHONE_EXTRA_TYPE_CALLBACK = "CALLBACK";
    public static final String ATTR_PHONE_EXTRA_TYPE_RADIO = "RADIO";
    public static final String ATTR_PHONE_EXTRA_TYPE_TTY_TDD = "TTY-TDD";
    public static final String ATTR_PHONE_EXTRA_TYPE_ASSISTANT = "ASSISTANT";
    // vCard composer translates this type to "WORK" + "PREF". Just for parsing.
    public static final String ATTR_PHONE_EXTRA_TYPE_COMPANY_MAIN = "COMPANY-MAIN";
    // vCard composer translates this type to "VOICE" Just for parsing.
    public static final String ATTR_PHONE_EXTRA_TYPE_OTHER = "OTHER";

    // Attribute for addresses.
    public static final String ATTR_ADR_TYPE_PARCEL = "PARCEL";
    public static final String ATTR_ADR_TYPE_DOM = "DOM";
    public static final String ATTR_ADR_TYPE_INTL = "INTL";

    // Attribute types not officially valid but used in some vCard exporter.
    // Do not use in composer side.
    public static final String ATTR_EXTRA_TYPE_COMPANY = "COMPANY";

    // DoCoMo specific attribute. Used with "SOUND" property, which is alternate of SORT-STRING in
    // vCard 3.0.
    public static final String ATTR_TYPE_X_IRMC_N = "X-IRMC-N";

    public interface ImportOnly {
        public static final String PROPERTY_X_NICKNAME = "X-NICKNAME";
        // Some device emits this "X-" attribute for expressing Google Talk,
        // which is specifically invalid but should be always properly accepted, and emitted
        // in some special case (for that device/application).
        public static final String PROPERTY_X_GOOGLE_TALK_WITH_SPACE = "X-GOOGLE TALK";
    }

    // TODO: Should be in ContactsContract?
    /* package */ static final int MAX_DATA_COLUMN = 15;

    private Constants() {
    }
}