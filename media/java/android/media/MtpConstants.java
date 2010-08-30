/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.media;

/**
 * {@hide}
 */
public final class MtpConstants {

// MTP Response Codes
    public static final int RESPONSE_UNDEFINED = 0x2000;
    public static final int RESPONSE_OK = 0x2001;
    public static final int RESPONSE_GENERAL_ERROR = 0x2002;
    public static final int RESPONSE_SESSION_NOT_OPEN = 0x2003;
    public static final int RESPONSE_INVALID_TRANSACTION_ID = 0x2004;
    public static final int RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005;
    public static final int RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006;
    public static final int RESPONSE_INCOMPLETE_TRANSFER = 0x2007;
    public static final int RESPONSE_INVALID_STORAGE_ID = 0x2008;
    public static final int RESPONSE_INVALID_OBJECT_HANDLE = 0x2009;
    public static final int RESPONSE_DEVICE_PROP_NOT_SUPPORTED = 0x200A;
    public static final int RESPONSE_INVALID_OBJECT_FORMAT_CODE = 0x200B;
    public static final int RESPONSE_STORAGE_FULL = 0x200C;
    public static final int RESPONSE_OBJECT_WRITE_PROTECTED = 0x200D;
    public static final int RESPONSE_STORE_READ_ONLY = 0x200E;
    public static final int RESPONSE_ACCESS_DENIED = 0x200F;
    public static final int RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010;
    public static final int RESPONSE_SELF_TEST_FAILED = 0x2011;
    public static final int RESPONSE_PARTIAL_DELETION = 0x2012;
    public static final int RESPONSE_STORE_NOT_AVAILABLE = 0x2013;
    public static final int RESPONSE_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014;
    public static final int RESPONSE_NO_VALID_OBJECT_INFO = 0x2015;
    public static final int RESPONSE_INVALID_CODE_FORMAT = 0x2016;
    public static final int RESPONSE_UNKNOWN_VENDOR_CODE = 0x2017;
    public static final int RESPONSE_CAPTURE_ALREADY_TERMINATED = 0x2018;
    public static final int RESPONSE_DEVICE_BUSY = 0x2019;
    public static final int RESPONSE_INVALID_PARENT_OBJECT = 0x201A;
    public static final int RESPONSE_INVALID_DEVICE_PROP_FORMAT = 0x201B;
    public static final int RESPONSE_INVALID_DEVICE_PROP_VALUE = 0x201C;
    public static final int RESPONSE_INVALID_PARAMETER = 0x201D;
    public static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201E;
    public static final int RESPONSE_TRANSACTION_CANCELLED = 0x201F;
    public static final int RESPONSE_SPECIFICATION_OF_DESTINATION_UNSUPPORTED = 0x2020;
    public static final int RESPONSE_INVALID_OBJECT_PROP_CODE = 0xA801;
    public static final int RESPONSE_INVALID_OBJECT_PROP_FORMAT = 0xA802;
    public static final int RESPONSE_INVALID_OBJECT_PROP_VALUE = 0xA803;
    public static final int RESPONSE_INVALID_OBJECT_REFERENCE = 0xA804;
    public static final int RESPONSE_GROUP_NOT_SUPPORTED = 0xA805;
    public static final int RESPONSE_INVALID_DATASET = 0xA806;
    public static final int RESPONSE_SPECIFICATION_BY_GROUP_UNSUPPORTED = 0xA807;
    public static final int RESPONSE_SPECIFICATION_BY_DEPTH_UNSUPPORTED = 0xA808;
    public static final int RESPONSE_OBJECT_TOO_LARGE = 0xA809;
    public static final int RESPONSE_OBJECT_PROP_NOT_SUPPORTED = 0xA80A;

    // MTP format codes
    public static final int FORMAT_UNDEFINED = 0x3000;
    public static final int FORMAT_ASSOCIATION = 0x3001;
    public static final int FORMAT_SCRIPT = 0x3002;
    public static final int FORMAT_EXECUTABLE = 0x3003;
    public static final int FORMAT_TEXT = 0x3004;
    public static final int FORMAT_HTML = 0x3005;
    public static final int FORMAT_DPOF = 0x3006;
    public static final int FORMAT_AIFF = 0x3007;
    public static final int FORMAT_WAV = 0x3008;
    public static final int FORMAT_MP3 = 0x3009;
    public static final int FORMAT_AVI = 0x300A;
    public static final int FORMAT_MPEG = 0x300B;
    public static final int FORMAT_ASF = 0x300C;
    public static final int FORMAT_DEFINED = 0x3800;
    public static final int FORMAT_EXIF_JPEG = 0x3801;
    public static final int FORMAT_TIFF_EP = 0x3802;
    public static final int FORMAT_FLASHPIX = 0x3803;
    public static final int FORMAT_BMP = 0x3804;
    public static final int FORMAT_CIFF = 0x3805;
    public static final int FORMAT_GIF = 0x3807;
    public static final int FORMAT_JFIF = 0x3808;
    public static final int FORMAT_CD = 0x3809;
    public static final int FORMAT_PICT = 0x380A;
    public static final int FORMAT_PNG = 0x380B;
    public static final int FORMAT_TIFF = 0x380D;
    public static final int FORMAT_TIFF_IT = 0x380E;
    public static final int FORMAT_JP2 = 0x380F;
    public static final int FORMAT_JPX = 0x3810;
    public static final int FORMAT_UNDEFINED_FIRMWARE = 0xB802;
    public static final int FORMAT_WINDOWS_IMAGE_FORMAT = 0xB881;
    public static final int FORMAT_UNDEFINED_AUDIO = 0xB900;
    public static final int FORMAT_WMA = 0xB901;
    public static final int FORMAT_OGG = 0xB902;
    public static final int FORMAT_AAC = 0xB903;
    public static final int FORMAT_AUDIBLE = 0xB904;
    public static final int FORMAT_FLAC = 0xB906;
    public static final int FORMAT_UNDEFINED_VIDEO = 0xB980;
    public static final int FORMAT_WMV = 0xB981;
    public static final int FORMAT_MP4_CONTAINER = 0xB982;
    public static final int FORMAT_MP2 = 0xB983;
    public static final int FORMAT_3GP_CONTAINER = 0xB984;
    public static final int FORMAT_UNDEFINED_COLLECTION = 0xBA00;
    public static final int FORMAT_ABSTRACT_MULTIMEDIA_ALBUM = 0xBA01;
    public static final int FORMAT_ABSTRACT_IMAGE_ALBUM = 0xBA02;
    public static final int FORMAT_ABSTRACT_AUDIO_ALBUM = 0xBA03;
    public static final int FORMAT_ABSTRACT_VIDEO_ALBUM = 0xBA04;
    public static final int FORMAT_ABSTRACT_AV_PLAYLIST = 0xBA05;
    public static final int FORMAT_ABSTRACT_CONTACT_GROUP = 0xBA06;
    public static final int FORMAT_ABSTRACT_MESSAGE_FOLDER = 0xBA07;
    public static final int FORMAT_ABSTRACT_CHAPTERED_PRODUCTION = 0xBA08;
    public static final int FORMAT_ABSTRACT_AUDIO_PLAYLIST = 0xBA09;
    public static final int FORMAT_ABSTRACT_VIDEO_PLAYLIST = 0xBA0A;
    public static final int FORMAT_ABSTRACT_MEDIACAST = 0xBA0B;
    public static final int FORMAT_WPL_PLAYLIST = 0xBA10;
    public static final int FORMAT_M3U_PLAYLIST = 0xBA11;
    public static final int FORMAT_MPL_PLAYLIST = 0xBA12;
    public static final int FORMAT_ASX_PLAYLIST = 0xBA13;
    public static final int FORMAT_PLS_PLAYLIST = 0xBA14;
    public static final int FORMAT_UNDEFINED_DOCUMENT = 0xBA80;
    public static final int FORMAT_ABSTRACT_DOCUMENT = 0xBA81;
    public static final int FORMAT_XML_DOCUMENT = 0xBA82;
    public static final int FORMAT_MS_WORD_DOCUMENT = 0xBA83;
    public static final int FORMAT_MHT_COMPILED_HTML_DOCUMENT = 0xBA84;
    public static final int FORMAT_MS_EXCEL_SPREADSHEET = 0xBA85;
    public static final int FORMAT_MS_POWERPOINT_PRESENTATION = 0xBA86;
    public static final int FORMAT_UNDEFINED_MESSAGE = 0xBB00;
    public static final int FORMAT_ABSTRACT_MESSSAGE = 0xBB01;
    public static final int FORMAT_UNDEFINED_CONTACT = 0xBB80;
    public static final int FORMAT_ABSTRACT_CONTACT = 0xBB81;
    public static final int FORMAT_VCARD_2 = 0xBB82;

    // MTP object properties
    public static final int PROPERTY_STORAGE_ID = 0xDC01;
    public static final int PROPERTY_OBJECT_FORMAT = 0xDC02;
    public static final int PROPERTY_PROTECTION_STATUS = 0xDC03;
    public static final int PROPERTY_OBJECT_SIZE = 0xDC04;
    public static final int PROPERTY_ASSOCIATION_TYPE = 0xDC05;
    public static final int PROPERTY_ASSOCIATION_DESC = 0xDC06;
    public static final int PROPERTY_OBJECT_FILE_NAME = 0xDC07;
    public static final int PROPERTY_DATE_CREATED = 0xDC08;
    public static final int PROPERTY_DATE_MODIFIED = 0xDC09;
    public static final int PROPERTY_KEYWORDS = 0xDC0A;
    public static final int PROPERTY_PARENT_OBJECT = 0xDC0B;
    public static final int PROPERTY_ALLOWED_FOLDER_CONTENTS = 0xDC0C;
    public static final int PROPERTY_HIDDEN = 0xDC0D;
    public static final int PROPERTY_SYSTEM_OBJECT = 0xDC0E;
    public static final int PROPERTY_PERSISTENT_UID = 0xDC41;
    public static final int PROPERTY_SYNC_ID = 0xDC42;
    public static final int PROPERTY_PROPERTY_BAG = 0xDC43;
    public static final int PROPERTY_NAME = 0xDC44;
    public static final int PROPERTY_CREATED_BY = 0xDC45;
    public static final int PROPERTY_ARTIST = 0xDC46;
    public static final int PROPERTY_DATE_AUTHORED = 0xDC47;
    public static final int PROPERTY_DESCRIPTION = 0xDC48;
    public static final int PROPERTY_URL_REFERENCE = 0xDC49;
    public static final int PROPERTY_LANGUAGE_LOCALE = 0xDC4A;
    public static final int PROPERTY_COPYRIGHT_INFORMATION = 0xDC4B;
    public static final int PROPERTY_SOURCE = 0xDC4C;
    public static final int PROPERTY_ORIGIN_LOCATION = 0xDC4D;
    public static final int PROPERTY_DATE_ADDED = 0xDC4E;
    public static final int PROPERTY_NON_CONSUMABLE = 0xDC4F;
    public static final int PROPERTY_CORRUPT_UNPLAYABLE = 0xDC50;
    public static final int PROPERTY_PRODUCER_SERIAL_NUMBER = 0xDC51;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_FORMAT = 0xDC81;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_SIZE = 0xDC82;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_HEIGHT = 0xDC83;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_WIDTH = 0xDC84;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_DURATION = 0xDC85;
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_DATA = 0xDC86;
    public static final int PROPERTY_WIDTH = 0xDC87;
    public static final int PROPERTY_HEIGHT = 0xDC88;
    public static final int PROPERTY_DURATION = 0xDC89;
    public static final int PROPERTY_RATING = 0xDC8A;
    public static final int PROPERTY_TRACK = 0xDC8B;
    public static final int PROPERTY_GENRE = 0xDC8C;
    public static final int PROPERTY_CREDITS = 0xDC8D;
    public static final int PROPERTY_LYRICS = 0xDC8E;
    public static final int PROPERTY_SUBSCRIPTION_CONTENT_ID = 0xDC8F;
    public static final int PROPERTY_PRODUCED_BY = 0xDC90;
    public static final int PROPERTY_USE_COUNT = 0xDC91;
    public static final int PROPERTY_SKIP_COUNT = 0xDC92;
    public static final int PROPERTY_LAST_ACCESSED = 0xDC93;
    public static final int PROPERTY_PARENTAL_RATING = 0xDC94;
    public static final int PROPERTY_META_GENRE = 0xDC95;
    public static final int PROPERTY_COMPOSER = 0xDC96;
    public static final int PROPERTY_EFFECTIVE_RATING = 0xDC97;
    public static final int PROPERTY_SUBTITLE = 0xDC98;
    public static final int PROPERTY_ORIGINAL_RELEASE_DATE = 0xDC99;
    public static final int PROPERTY_ALBUM_NAME = 0xDC9A;
    public static final int PROPERTY_ALBUM_ARTIST = 0xDC9B;
    public static final int PROPERTY_MOOD = 0xDC9C;
    public static final int PROPERTY_DRM_STATUS = 0xDC9D;
    public static final int PROPERTY_SUB_DESCRIPTION = 0xDC9E;
    public static final int PROPERTY_IS_CROPPED = 0xDCD1;
    public static final int PROPERTY_IS_COLOUR_CORRECTED = 0xDCD2;
    public static final int PROPERTY_IMAGE_BIT_DEPTH = 0xDCD3;
    public static final int PROPERTY_F_NUMBER = 0xDCD4;
    public static final int PROPERTY_EXPOSURE_TIME = 0xDCD5;
    public static final int PROPERTY_EXPOSURE_INDEX = 0xDCD6;
    public static final int PROPERTY_TOTAL_BITRATE = 0xDE91;
    public static final int PROPERTY_BITRATE_TYPE = 0xDE92;
    public static final int PROPERTY_SAMPLE_RATE = 0xDE93;
    public static final int PROPERTY_NUMBER_OF_CHANNELS = 0xDE94;
    public static final int PROPERTY_AUDIO_BIT_DEPTH = 0xDE95;
    public static final int PROPERTY_SCAN_TYPE = 0xDE97;
    public static final int PROPERTY_AUDIO_WAVE_CODEC = 0xDE99;
    public static final int PROPERTY_AUDIO_BITRATE = 0xDE9A;
    public static final int PROPERTY_VIDEO_FOURCC_CODEC = 0xDE9B;
    public static final int PROPERTY_VIDEO_BITRATE = 0xDE9C;
    public static final int PROPERTY_FRAMES_PER_THOUSAND_SECONDS = 0xDE9D;
    public static final int PROPERTY_KEYFRAME_DISTANCE = 0xDE9E;
    public static final int PROPERTY_BUFFER_SIZE = 0xDE9F;
    public static final int PROPERTY_ENCODING_QUALITY = 0xDEA0;
    public static final int PROPERTY_ENCODING_PROFILE = 0xDEA1;
    public static final int PROPERTY_DISPLAY_NAME = 0xDCE0;
    public static final int PROPERTY_BODY_TEXT = 0xDCE1;
    public static final int PROPERTY_SUBJECT = 0xDCE2;
    public static final int PROPERTY_PRIORITY = 0xDCE3;
    public static final int PROPERTY_GIVEN_NAME = 0xDD00;
    public static final int PROPERTY_MIDDLE_NAMES = 0xDD01;
    public static final int PROPERTY_FAMILY_NAME = 0xDD02;
    public static final int PROPERTY_PREFIX = 0xDD03;
    public static final int PROPERTY_SUFFIX = 0xDD04;
    public static final int PROPERTY_PHONETIC_GIVEN_NAME = 0xDD05;
    public static final int PROPERTY_PHONETIC_FAMILY_NAME = 0xDD06;
    public static final int PROPERTY_EMAIL_PRIMARY = 0xDD07;
    public static final int PROPERTY_EMAIL_PERSONAL_1 = 0xDD08;
    public static final int PROPERTY_EMAIL_PERSONAL_2 = 0xDD09;
    public static final int PROPERTY_EMAIL_BUSINESS_1 = 0xDD0A;
    public static final int PROPERTY_EMAIL_BUSINESS_2 = 0xDD0B;
    public static final int PROPERTY_EMAIL_OTHERS = 0xDD0C;
    public static final int PROPERTY_PHONE_NUMBER_PRIMARY = 0xDD0D;
    public static final int PROPERTY_PHONE_NUMBER_PERSONAL = 0xDD0E;
    public static final int PROPERTY_PHONE_NUMBER_PERSONAL_2 = 0xDD0F;
    public static final int PROPERTY_PHONE_NUMBER_BUSINESS = 0xDD10;
    public static final int PROPERTY_PHONE_NUMBER_BUSINESS_2 = 0xDD11;
    public static final int PROPERTY_PHONE_NUMBER_MOBILE= 0xDD12;
    public static final int PROPERTY_PHONE_NUMBER_MOBILE_2 = 0xDD13;
    public static final int PROPERTY_FAX_NUMBER_PRIMARY = 0xDD14;
    public static final int PROPERTY_FAX_NUMBER_PERSONAL= 0xDD15;
    public static final int PROPERTY_FAX_NUMBER_BUSINESS= 0xDD16;
    public static final int PROPERTY_PAGER_NUMBER = 0xDD17;
    public static final int PROPERTY_PHONE_NUMBER_OTHERS= 0xDD18;
    public static final int PROPERTY_PRIMARY_WEB_ADDRESS= 0xDD19;
    public static final int PROPERTY_PERSONAL_WEB_ADDRESS = 0xDD1A;
    public static final int PROPERTY_BUSINESS_WEB_ADDRESS = 0xDD1B;
    public static final int PROPERTY_INSTANT_MESSANGER_ADDRESS = 0xDD1C;
    public static final int PROPERTY_INSTANT_MESSANGER_ADDRESS_2 = 0xDD1D;
    public static final int PROPERTY_INSTANT_MESSANGER_ADDRESS_3 = 0xDD1E;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_FULL = 0xDD1F;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_LINE_1 = 0xDD20;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_LINE_2 = 0xDD21;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_CITY = 0xDD22;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_REGION = 0xDD23;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_POSTAL_CODE = 0xDD24;
    public static final int PROPERTY_POSTAL_ADDRESS_PERSONAL_COUNTRY = 0xDD25;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_FULL = 0xDD26;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_LINE_1 = 0xDD27;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_LINE_2 = 0xDD28;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_CITY = 0xDD29;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_REGION = 0xDD2A;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_POSTAL_CODE = 0xDD2B;
    public static final int PROPERTY_POSTAL_ADDRESS_BUSINESS_COUNTRY = 0xDD2C;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_FULL = 0xDD2D;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_LINE_1 = 0xDD2E;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_LINE_2 = 0xDD2F;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_CITY = 0xDD30;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_REGION = 0xDD31;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_POSTAL_CODE = 0xDD32;
    public static final int PROPERTY_POSTAL_ADDRESS_OTHER_COUNTRY = 0xDD33;
    public static final int PROPERTY_ORGANIZATION_NAME = 0xDD34;
    public static final int PROPERTY_PHONETIC_ORGANIZATION_NAME = 0xDD35;
    public static final int PROPERTY_ROLE = 0xDD36;
    public static final int PROPERTY_BIRTHDATE = 0xDD37;
    public static final int PROPERTY_MESSAGE_TO = 0xDD40;
    public static final int PROPERTY_MESSAGE_CC = 0xDD41;
    public static final int PROPERTY_MESSAGE_BCC = 0xDD42;
    public static final int PROPERTY_MESSAGE_READ = 0xDD43;
    public static final int PROPERTY_MESSAGE_RECEIVED_TIME = 0xDD44;
    public static final int PROPERTY_MESSAGE_SENDER = 0xDD45;
    public static final int PROPERTY_ACTIVITY_BEGIN_TIME = 0xDD50;
    public static final int PROPERTY_ACTIVITY_END_TIME = 0xDD51;
    public static final int PROPERTY_ACTIVITY_LOCATION = 0xDD52;
    public static final int PROPERTY_ACTIVITY_REQUIRED_ATTENDEES = 0xDD54;
    public static final int PROPERTY_ACTIVITY_OPTIONAL_ATTENDEES = 0xDD55;
    public static final int PROPERTY_ACTIVITY_RESOURCES = 0xDD56;
    public static final int PROPERTY_ACTIVITY_ACCEPTED = 0xDD57;
    public static final int PROPERTY_ACTIVITY_TENTATIVE = 0xDD58;
    public static final int PROPERTY_ACTIVITY_DECLINED = 0xDD59;
    public static final int PROPERTY_ACTIVITY_REMAINDER_TIME = 0xDD5A;
    public static final int PROPERTY_ACTIVITY_OWNER = 0xDD5B;
    public static final int PROPERTY_ACTIVITY_STATUS = 0xDD5C;
    public static final int PROPERTY_OWNER = 0xDD5D;
    public static final int PROPERTY_EDITOR = 0xDD5E;
    public static final int PROPERTY_WEBMASTER = 0xDD5F;
    public static final int PROPERTY_URL_SOURCE = 0xDD60;
    public static final int PROPERTY_URL_DESTINATION = 0xDD61;
    public static final int PROPERTY_TIME_BOOKMARK = 0xDD62;
    public static final int PROPERTY_OBJECT_BOOKMARK = 0xDD63;
    public static final int PROPERTY_BYTE_BOOKMARK = 0xDD64;
    public static final int PROPERTY_LAST_BUILD_DATE = 0xDD70;
    public static final int PROPERTY_TIME_TO_LIVE = 0xDD71;
    public static final int PROPERTY_MEDIA_GUID = 0xDD72;

    // MTP device properties
    public static final int DEVICE_PROPERTY_UNDEFINED = 0x5000;
    public static final int DEVICE_PROPERTY_BATTERY_LEVEL = 0x5001;
    public static final int DEVICE_PROPERTY_FUNCTIONAL_MODE = 0x5002;
    public static final int DEVICE_PROPERTY_IMAGE_SIZE = 0x5003;
    public static final int DEVICE_PROPERTY_COMPRESSION_SETTING = 0x5004;
    public static final int DEVICE_PROPERTY_WHITE_BALANCE = 0x5005;
    public static final int DEVICE_PROPERTY_RGB_GAIN = 0x5006;
    public static final int DEVICE_PROPERTY_F_NUMBER = 0x5007;
    public static final int DEVICE_PROPERTY_FOCAL_LENGTH = 0x5008;
    public static final int DEVICE_PROPERTY_FOCUS_DISTANCE = 0x5009;
    public static final int DEVICE_PROPERTY_FOCUS_MODE = 0x500A;
    public static final int DEVICE_PROPERTY_EXPOSURE_METERING_MODE = 0x500B;
    public static final int DEVICE_PROPERTY_FLASH_MODE = 0x500C;
    public static final int DEVICE_PROPERTY_EXPOSURE_TIME = 0x500D;
    public static final int DEVICE_PROPERTY_EXPOSURE_PROGRAM_MODE = 0x500E;
    public static final int DEVICE_PROPERTY_EXPOSURE_INDEX = 0x500F;
    public static final int DEVICE_PROPERTY_EXPOSURE_BIAS_COMPENSATION = 0x5010;
    public static final int DEVICE_PROPERTY_DATETIME = 0x5011;
    public static final int DEVICE_PROPERTY_CAPTURE_DELAY = 0x5012;
    public static final int DEVICE_PROPERTY_STILL_CAPTURE_MODE = 0x5013;
    public static final int DEVICE_PROPERTY_CONTRAST = 0x5014;
    public static final int DEVICE_PROPERTY_SHARPNESS = 0x5015;
    public static final int DEVICE_PROPERTY_DIGITAL_ZOOM = 0x5016;
    public static final int DEVICE_PROPERTY_EFFECT_MODE = 0x5017;
    public static final int DEVICE_PROPERTY_BURST_NUMBER= 0x5018;
    public static final int DEVICE_PROPERTY_BURST_INTERVAL = 0x5019;
    public static final int DEVICE_PROPERTY_TIMELAPSE_NUMBER = 0x501A;
    public static final int DEVICE_PROPERTY_TIMELAPSE_INTERVAL = 0x501B;
    public static final int DEVICE_PROPERTY_FOCUS_METERING_MODE = 0x501C;
    public static final int DEVICE_PROPERTY_UPLOAD_URL = 0x501D;
    public static final int DEVICE_PROPERTY_ARTIST = 0x501E;
    public static final int DEVICE_PROPERTY_COPYRIGHT_INFO = 0x501F;
    public static final int DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER = 0xD401;
    public static final int DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME = 0xD402;
    public static final int DEVICE_PROPERTY_VOLUME = 0xD403;
    public static final int DEVICE_PROPERTY_SUPPORTED_FORMATS_ORDERED = 0xD404;
    public static final int DEVICE_PROPERTY_DEVICE_ICON = 0xD405;
    public static final int DEVICE_PROPERTY_PLAYBACK_RATE = 0xD410;
    public static final int DEVICE_PROPERTY_PLAYBACK_OBJECT = 0xD411;
    public static final int DEVICE_PROPERTY_PLAYBACK_CONTAINER_INDEX = 0xD412;
    public static final int DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO = 0xD406;
    public static final int DEVICE_PROPERTY_PERCEIVED_DEVICE_TYPE = 0xD407;


    /**
     * Object is not protected. It may be modified and deleted, and its properties
     * may be modified.
     */
    public static final int PROTECTION_STATUS_NONE = 0;

    /**
     * Object can not be modified or deleted and its properties can not be modified.
     */
    public static final int PROTECTION_STATUS_READ_ONLY = 0x8001;

    /**
     * Object can not be modified or deleted but its properties are modifiable.
     */
    public static final int PROTECTION_STATUS_READ_ONLY_DATA = 0x8002;

    /**
     * Object's contents can not be transfered from the device, but the object
     * may be moved or deleted and its properties may be modified.
     */
    public static final int PROTECTION_STATUS_NON_TRANSFERABLE_DATA = 0x8003;

    public static final int ASSOCIATION_TYPE_GENERIC_FOLDER = 0x0001;
}
