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

package android.mtp;

/**
 * A class containing constants in the MTP and PTP specifications.
 */
public final class MtpConstants {

    // MTP Data Types
    /** @hide */
    public static final int TYPE_UNDEFINED = 0x0000;
    /** @hide */
    public static final int TYPE_INT8 = 0x0001;
    /** @hide */
    public static final int TYPE_UINT8 = 0x0002;
    /** @hide */
    public static final int TYPE_INT16 = 0x0003;
    /** @hide */
    public static final int TYPE_UINT16 = 0x0004;
    /** @hide */
    public static final int TYPE_INT32 = 0x0005;
    /** @hide */
    public static final int TYPE_UINT32 = 0x0006;
    /** @hide */
    public static final int TYPE_INT64 = 0x0007;
    /** @hide */
    public static final int TYPE_UINT64 = 0x0008;
    /** @hide */
    public static final int TYPE_INT128 = 0x0009;
    /** @hide */
    public static final int TYPE_UINT128 = 0x000A;
    /** @hide */
    public static final int TYPE_AINT8 = 0x4001;
    /** @hide */
    public static final int TYPE_AUINT8 = 0x4002;
    /** @hide */
    public static final int TYPE_AINT16 = 0x4003;
    /** @hide */
    public static final int TYPE_AUINT16 = 0x4004;
    /** @hide */
    public static final int TYPE_AINT32 = 0x4005;
    /** @hide */
    public static final int TYPE_AUINT32 = 0x4006;
    /** @hide */
    public static final int TYPE_AINT64 = 0x4007;
    /** @hide */
    public static final int TYPE_AUINT64 = 0x4008;
    /** @hide */
    public static final int TYPE_AINT128 = 0x4009;
    /** @hide */
    public static final int TYPE_AUINT128 = 0x400A;
    /** @hide */
    public static final int TYPE_STR = 0xFFFF;

    // MTP Response Codes
    /** @hide */
    public static final int RESPONSE_UNDEFINED = 0x2000;
    /** @hide */
    public static final int RESPONSE_OK = 0x2001;
    /** @hide */
    public static final int RESPONSE_GENERAL_ERROR = 0x2002;
    /** @hide */
    public static final int RESPONSE_SESSION_NOT_OPEN = 0x2003;
    /** @hide */
    public static final int RESPONSE_INVALID_TRANSACTION_ID = 0x2004;
    /** @hide */
    public static final int RESPONSE_OPERATION_NOT_SUPPORTED = 0x2005;
    /** @hide */
    public static final int RESPONSE_PARAMETER_NOT_SUPPORTED = 0x2006;
    /** @hide */
    public static final int RESPONSE_INCOMPLETE_TRANSFER = 0x2007;
    /** @hide */
    public static final int RESPONSE_INVALID_STORAGE_ID = 0x2008;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_HANDLE = 0x2009;
    /** @hide */
    public static final int RESPONSE_DEVICE_PROP_NOT_SUPPORTED = 0x200A;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_FORMAT_CODE = 0x200B;
    /** @hide */
    public static final int RESPONSE_STORAGE_FULL = 0x200C;
    /** @hide */
    public static final int RESPONSE_OBJECT_WRITE_PROTECTED = 0x200D;
    /** @hide */
    public static final int RESPONSE_STORE_READ_ONLY = 0x200E;
    /** @hide */
    public static final int RESPONSE_ACCESS_DENIED = 0x200F;
    /** @hide */
    public static final int RESPONSE_NO_THUMBNAIL_PRESENT = 0x2010;
    /** @hide */
    public static final int RESPONSE_SELF_TEST_FAILED = 0x2011;
    /** @hide */
    public static final int RESPONSE_PARTIAL_DELETION = 0x2012;
    /** @hide */
    public static final int RESPONSE_STORE_NOT_AVAILABLE = 0x2013;
    /** @hide */
    public static final int RESPONSE_SPECIFICATION_BY_FORMAT_UNSUPPORTED = 0x2014;
    /** @hide */
    public static final int RESPONSE_NO_VALID_OBJECT_INFO = 0x2015;
    /** @hide */
    public static final int RESPONSE_INVALID_CODE_FORMAT = 0x2016;
    /** @hide */
    public static final int RESPONSE_UNKNOWN_VENDOR_CODE = 0x2017;
    /** @hide */
    public static final int RESPONSE_CAPTURE_ALREADY_TERMINATED = 0x2018;
    /** @hide */
    public static final int RESPONSE_DEVICE_BUSY = 0x2019;
    /** @hide */
    public static final int RESPONSE_INVALID_PARENT_OBJECT = 0x201A;
    /** @hide */
    public static final int RESPONSE_INVALID_DEVICE_PROP_FORMAT = 0x201B;
    /** @hide */
    public static final int RESPONSE_INVALID_DEVICE_PROP_VALUE = 0x201C;
    /** @hide */
    public static final int RESPONSE_INVALID_PARAMETER = 0x201D;
    /** @hide */
    public static final int RESPONSE_SESSION_ALREADY_OPEN = 0x201E;
    /** @hide */
    public static final int RESPONSE_TRANSACTION_CANCELLED = 0x201F;
    /** @hide */
    public static final int RESPONSE_SPECIFICATION_OF_DESTINATION_UNSUPPORTED = 0x2020;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_PROP_CODE = 0xA801;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_PROP_FORMAT = 0xA802;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_PROP_VALUE = 0xA803;
    /** @hide */
    public static final int RESPONSE_INVALID_OBJECT_REFERENCE = 0xA804;
    /** @hide */
    public static final int RESPONSE_GROUP_NOT_SUPPORTED = 0xA805;
    /** @hide */
    public static final int RESPONSE_INVALID_DATASET = 0xA806;
    /** @hide */
    public static final int RESPONSE_SPECIFICATION_BY_GROUP_UNSUPPORTED = 0xA807;
    /** @hide */
    public static final int RESPONSE_SPECIFICATION_BY_DEPTH_UNSUPPORTED = 0xA808;
    /** @hide */
    public static final int RESPONSE_OBJECT_TOO_LARGE = 0xA809;
    /** @hide */
    public static final int RESPONSE_OBJECT_PROP_NOT_SUPPORTED = 0xA80A;

    // MTP format codes
    /** Undefined format code */
    public static final int FORMAT_UNDEFINED = 0x3000;
    /** Format code for associations (folders and directories) */
    public static final int FORMAT_ASSOCIATION = 0x3001;
    /** Format code for script files */
    public static final int FORMAT_SCRIPT = 0x3002;
    /** Format code for executable files */
    public static final int FORMAT_EXECUTABLE = 0x3003;
    /** Format code for text files */
    public static final int FORMAT_TEXT = 0x3004;
    /** Format code for HTML files */
    public static final int FORMAT_HTML = 0x3005;
    /** Format code for DPOF files */
    public static final int FORMAT_DPOF = 0x3006;
    /** Format code for AIFF audio files */
    public static final int FORMAT_AIFF = 0x3007;
    /** Format code for WAV audio files */
    public static final int FORMAT_WAV = 0x3008;
    /** Format code for MP3 audio files */
    public static final int FORMAT_MP3 = 0x3009;
    /** Format code for AVI video files */
    public static final int FORMAT_AVI = 0x300A;
    /** Format code for MPEG video files */
    public static final int FORMAT_MPEG = 0x300B;
    /** Format code for ASF files */
    public static final int FORMAT_ASF = 0x300C;
    /**
     * Format code for unknown image files.
     * <p>
     * Will be used for the formats which are not specified in PTP specification.
     * For instance, WEBP and WBMP.
     */
    public static final int FORMAT_DEFINED = 0x3800;
    /** Format code for JPEG image files */
    public static final int FORMAT_EXIF_JPEG = 0x3801;
    /** Format code for TIFF EP image files */
    public static final int FORMAT_TIFF_EP = 0x3802;
    /** Format code for BMP image files */
    public static final int FORMAT_BMP = 0x3804;
    /** Format code for GIF image files */
    public static final int FORMAT_GIF = 0x3807;
    /** Format code for JFIF image files */
    public static final int FORMAT_JFIF = 0x3808;
    /** Format code for PICT image files */
    public static final int FORMAT_PICT = 0x380A;
    /** Format code for PNG image files */
    public static final int FORMAT_PNG = 0x380B;
    /** Format code for TIFF image files */
    public static final int FORMAT_TIFF = 0x380D;
    /** Format code for JP2 files */
    public static final int FORMAT_JP2 = 0x380F;
    /** Format code for JPX files */
    public static final int FORMAT_JPX = 0x3810;
    /** Format code for DNG files */
    public static final int FORMAT_DNG = 0x3811;
    /** Format code for firmware files */
    public static final int FORMAT_UNDEFINED_FIRMWARE = 0xB802;
    /** Format code for Windows image files */
    public static final int FORMAT_WINDOWS_IMAGE_FORMAT = 0xB881;
    /** Format code for undefined audio files files */
    public static final int FORMAT_UNDEFINED_AUDIO = 0xB900;
    /** Format code for WMA audio files */
    public static final int FORMAT_WMA = 0xB901;
    /** Format code for OGG audio files */
    public static final int FORMAT_OGG = 0xB902;
    /** Format code for AAC audio files */
    public static final int FORMAT_AAC = 0xB903;
    /** Format code for Audible audio files */
    public static final int FORMAT_AUDIBLE = 0xB904;
    /** Format code for FLAC audio files */
    public static final int FORMAT_FLAC = 0xB906;
    /** Format code for undefined video files */
    public static final int FORMAT_UNDEFINED_VIDEO = 0xB980;
    /** Format code for WMV video files */
    public static final int FORMAT_WMV = 0xB981;
    /** Format code for MP4 files */
    public static final int FORMAT_MP4_CONTAINER = 0xB982;
    /** Format code for MP2 files */
    public static final int FORMAT_MP2 = 0xB983;
    /** Format code for 3GP files */
    public static final int FORMAT_3GP_CONTAINER = 0xB984;
    /** Format code for undefined collections */
    public static final int FORMAT_UNDEFINED_COLLECTION = 0xBA00;
    /** Format code for multimedia albums */
    public static final int FORMAT_ABSTRACT_MULTIMEDIA_ALBUM = 0xBA01;
    /** Format code for image albums */
    public static final int FORMAT_ABSTRACT_IMAGE_ALBUM = 0xBA02;
    /** Format code for audio albums */
    public static final int FORMAT_ABSTRACT_AUDIO_ALBUM = 0xBA03;
    /** Format code for video albums */
    public static final int FORMAT_ABSTRACT_VIDEO_ALBUM = 0xBA04;
    /** Format code for abstract AV playlists */
    public static final int FORMAT_ABSTRACT_AV_PLAYLIST = 0xBA05;
    /** Format code for abstract audio playlists */
    public static final int FORMAT_ABSTRACT_AUDIO_PLAYLIST = 0xBA09;
    /** Format code for abstract video playlists */
    public static final int FORMAT_ABSTRACT_VIDEO_PLAYLIST = 0xBA0A;
    /** Format code for abstract mediacasts */
    public static final int FORMAT_ABSTRACT_MEDIACAST = 0xBA0B;
    /** Format code for WPL playlist files */
    public static final int FORMAT_WPL_PLAYLIST = 0xBA10;
    /** Format code for M3u playlist files */
    public static final int FORMAT_M3U_PLAYLIST = 0xBA11;
    /** Format code for MPL playlist files */
    public static final int FORMAT_MPL_PLAYLIST = 0xBA12;
    /** Format code for ASX playlist files */
    public static final int FORMAT_ASX_PLAYLIST = 0xBA13;
    /** Format code for PLS playlist files */
    public static final int FORMAT_PLS_PLAYLIST = 0xBA14;
    /** Format code for undefined document files */
    public static final int FORMAT_UNDEFINED_DOCUMENT = 0xBA80;
    /** Format code for abstract documents */
    public static final int FORMAT_ABSTRACT_DOCUMENT = 0xBA81;
    /** Format code for XML documents */
    public static final int FORMAT_XML_DOCUMENT = 0xBA82;
    /** Format code for MS Word documents */
    public static final int FORMAT_MS_WORD_DOCUMENT = 0xBA83;
    /** Format code for MS Excel spreadsheets */
    public static final int FORMAT_MS_EXCEL_SPREADSHEET = 0xBA85;
    /** Format code for MS PowerPoint presentatiosn */
    public static final int FORMAT_MS_POWERPOINT_PRESENTATION = 0xBA86;

    /**
     * Returns true if the object is abstract (that is, it has no representation
     * in the underlying file system).
     *
     * @param format the format of the object
     * @return true if the object is abstract
     */
    public static boolean isAbstractObject(int format) {
        switch (format) {
            case FORMAT_ABSTRACT_MULTIMEDIA_ALBUM:
            case FORMAT_ABSTRACT_IMAGE_ALBUM:
            case FORMAT_ABSTRACT_AUDIO_ALBUM:
            case FORMAT_ABSTRACT_VIDEO_ALBUM:
            case FORMAT_ABSTRACT_AV_PLAYLIST:
            case FORMAT_ABSTRACT_AUDIO_PLAYLIST:
            case FORMAT_ABSTRACT_VIDEO_PLAYLIST:
            case FORMAT_ABSTRACT_MEDIACAST:
            case FORMAT_ABSTRACT_DOCUMENT:
                return true;
            default:
                return false;
        }
    }

    // MTP object properties
    /** @hide */
    public static final int PROPERTY_STORAGE_ID = 0xDC01;
    /** @hide */
    public static final int PROPERTY_OBJECT_FORMAT = 0xDC02;
    /** @hide */
    public static final int PROPERTY_PROTECTION_STATUS = 0xDC03;
    /** @hide */
    public static final int PROPERTY_OBJECT_SIZE = 0xDC04;
    /** @hide */
    public static final int PROPERTY_ASSOCIATION_TYPE = 0xDC05;
    /** @hide */
    public static final int PROPERTY_ASSOCIATION_DESC = 0xDC06;
    /** @hide */
    public static final int PROPERTY_OBJECT_FILE_NAME = 0xDC07;
    /** @hide */
    public static final int PROPERTY_DATE_CREATED = 0xDC08;
    /** @hide */
    public static final int PROPERTY_DATE_MODIFIED = 0xDC09;
    /** @hide */
    public static final int PROPERTY_KEYWORDS = 0xDC0A;
    /** @hide */
    public static final int PROPERTY_PARENT_OBJECT = 0xDC0B;
    /** @hide */
    public static final int PROPERTY_ALLOWED_FOLDER_CONTENTS = 0xDC0C;
    /** @hide */
    public static final int PROPERTY_HIDDEN = 0xDC0D;
    /** @hide */
    public static final int PROPERTY_SYSTEM_OBJECT = 0xDC0E;
    /** @hide */
    public static final int PROPERTY_PERSISTENT_UID = 0xDC41;
    /** @hide */
    public static final int PROPERTY_SYNC_ID = 0xDC42;
    /** @hide */
    public static final int PROPERTY_PROPERTY_BAG = 0xDC43;
    /** @hide */
    public static final int PROPERTY_NAME = 0xDC44;
    /** @hide */
    public static final int PROPERTY_CREATED_BY = 0xDC45;
    /** @hide */
    public static final int PROPERTY_ARTIST = 0xDC46;
    /** @hide */
    public static final int PROPERTY_DATE_AUTHORED = 0xDC47;
    /** @hide */
    public static final int PROPERTY_DESCRIPTION = 0xDC48;
    /** @hide */
    public static final int PROPERTY_URL_REFERENCE = 0xDC49;
    /** @hide */
    public static final int PROPERTY_LANGUAGE_LOCALE = 0xDC4A;
    /** @hide */
    public static final int PROPERTY_COPYRIGHT_INFORMATION = 0xDC4B;
    /** @hide */
    public static final int PROPERTY_SOURCE = 0xDC4C;
    /** @hide */
    public static final int PROPERTY_ORIGIN_LOCATION = 0xDC4D;
    /** @hide */
    public static final int PROPERTY_DATE_ADDED = 0xDC4E;
    /** @hide */
    public static final int PROPERTY_NON_CONSUMABLE = 0xDC4F;
    /** @hide */
    public static final int PROPERTY_CORRUPT_UNPLAYABLE = 0xDC50;
    /** @hide */
    public static final int PROPERTY_PRODUCER_SERIAL_NUMBER = 0xDC51;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_FORMAT = 0xDC81;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_SIZE = 0xDC82;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_HEIGHT = 0xDC83;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_WIDTH = 0xDC84;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_DURATION = 0xDC85;
    /** @hide */
    public static final int PROPERTY_REPRESENTATIVE_SAMPLE_DATA = 0xDC86;
    /** @hide */
    public static final int PROPERTY_WIDTH = 0xDC87;
    /** @hide */
    public static final int PROPERTY_HEIGHT = 0xDC88;
    /** @hide */
    public static final int PROPERTY_DURATION = 0xDC89;
    /** @hide */
    public static final int PROPERTY_RATING = 0xDC8A;
    /** @hide */
    public static final int PROPERTY_TRACK = 0xDC8B;
    /** @hide */
    public static final int PROPERTY_GENRE = 0xDC8C;
    /** @hide */
    public static final int PROPERTY_CREDITS = 0xDC8D;
    /** @hide */
    public static final int PROPERTY_LYRICS = 0xDC8E;
    /** @hide */
    public static final int PROPERTY_SUBSCRIPTION_CONTENT_ID = 0xDC8F;
    /** @hide */
    public static final int PROPERTY_PRODUCED_BY = 0xDC90;
    /** @hide */
    public static final int PROPERTY_USE_COUNT = 0xDC91;
    /** @hide */
    public static final int PROPERTY_SKIP_COUNT = 0xDC92;
    /** @hide */
    public static final int PROPERTY_LAST_ACCESSED = 0xDC93;
    /** @hide */
    public static final int PROPERTY_PARENTAL_RATING = 0xDC94;
    /** @hide */
    public static final int PROPERTY_META_GENRE = 0xDC95;
    /** @hide */
    public static final int PROPERTY_COMPOSER = 0xDC96;
    /** @hide */
    public static final int PROPERTY_EFFECTIVE_RATING = 0xDC97;
    /** @hide */
    public static final int PROPERTY_SUBTITLE = 0xDC98;
    /** @hide */
    public static final int PROPERTY_ORIGINAL_RELEASE_DATE = 0xDC99;
    /** @hide */
    public static final int PROPERTY_ALBUM_NAME = 0xDC9A;
    /** @hide */
    public static final int PROPERTY_ALBUM_ARTIST = 0xDC9B;
    /** @hide */
    public static final int PROPERTY_MOOD = 0xDC9C;
    /** @hide */
    public static final int PROPERTY_DRM_STATUS = 0xDC9D;
    /** @hide */
    public static final int PROPERTY_SUB_DESCRIPTION = 0xDC9E;
    /** @hide */
    public static final int PROPERTY_IS_CROPPED = 0xDCD1;
    /** @hide */
    public static final int PROPERTY_IS_COLOUR_CORRECTED = 0xDCD2;
    /** @hide */
    public static final int PROPERTY_IMAGE_BIT_DEPTH = 0xDCD3;
    /** @hide */
    public static final int PROPERTY_F_NUMBER = 0xDCD4;
    /** @hide */
    public static final int PROPERTY_EXPOSURE_TIME = 0xDCD5;
    /** @hide */
    public static final int PROPERTY_EXPOSURE_INDEX = 0xDCD6;
    /** @hide */
    public static final int PROPERTY_TOTAL_BITRATE = 0xDE91;
    /** @hide */
    public static final int PROPERTY_BITRATE_TYPE = 0xDE92;
    /** @hide */
    public static final int PROPERTY_SAMPLE_RATE = 0xDE93;
    /** @hide */
    public static final int PROPERTY_NUMBER_OF_CHANNELS = 0xDE94;
    /** @hide */
    public static final int PROPERTY_AUDIO_BIT_DEPTH = 0xDE95;
    /** @hide */
    public static final int PROPERTY_SCAN_TYPE = 0xDE97;
    /** @hide */
    public static final int PROPERTY_AUDIO_WAVE_CODEC = 0xDE99;
    /** @hide */
    public static final int PROPERTY_AUDIO_BITRATE = 0xDE9A;
    /** @hide */
    public static final int PROPERTY_VIDEO_FOURCC_CODEC = 0xDE9B;
    /** @hide */
    public static final int PROPERTY_VIDEO_BITRATE = 0xDE9C;
    /** @hide */
    public static final int PROPERTY_FRAMES_PER_THOUSAND_SECONDS = 0xDE9D;
    /** @hide */
    public static final int PROPERTY_KEYFRAME_DISTANCE = 0xDE9E;
    /** @hide */
    public static final int PROPERTY_BUFFER_SIZE = 0xDE9F;
    /** @hide */
    public static final int PROPERTY_ENCODING_QUALITY = 0xDEA0;
    /** @hide */
    public static final int PROPERTY_ENCODING_PROFILE = 0xDEA1;
    /** @hide */
    public static final int PROPERTY_DISPLAY_NAME = 0xDCE0;

    // MTP device properties
    /** @hide */
    public static final int DEVICE_PROPERTY_UNDEFINED = 0x5000;
    /** @hide */
    public static final int DEVICE_PROPERTY_BATTERY_LEVEL = 0x5001;
    /** @hide */
    public static final int DEVICE_PROPERTY_FUNCTIONAL_MODE = 0x5002;
    /** @hide */
    public static final int DEVICE_PROPERTY_IMAGE_SIZE = 0x5003;
    /** @hide */
    public static final int DEVICE_PROPERTY_COMPRESSION_SETTING = 0x5004;
    /** @hide */
    public static final int DEVICE_PROPERTY_WHITE_BALANCE = 0x5005;
    /** @hide */
    public static final int DEVICE_PROPERTY_RGB_GAIN = 0x5006;
    /** @hide */
    public static final int DEVICE_PROPERTY_F_NUMBER = 0x5007;
    /** @hide */
    public static final int DEVICE_PROPERTY_FOCAL_LENGTH = 0x5008;
    /** @hide */
    public static final int DEVICE_PROPERTY_FOCUS_DISTANCE = 0x5009;
    /** @hide */
    public static final int DEVICE_PROPERTY_FOCUS_MODE = 0x500A;
    /** @hide */
    public static final int DEVICE_PROPERTY_EXPOSURE_METERING_MODE = 0x500B;
    /** @hide */
    public static final int DEVICE_PROPERTY_FLASH_MODE = 0x500C;
    /** @hide */
    public static final int DEVICE_PROPERTY_EXPOSURE_TIME = 0x500D;
    /** @hide */
    public static final int DEVICE_PROPERTY_EXPOSURE_PROGRAM_MODE = 0x500E;
    /** @hide */
    public static final int DEVICE_PROPERTY_EXPOSURE_INDEX = 0x500F;
    /** @hide */
    public static final int DEVICE_PROPERTY_EXPOSURE_BIAS_COMPENSATION = 0x5010;
    /** @hide */
    public static final int DEVICE_PROPERTY_DATETIME = 0x5011;
    /** @hide */
    public static final int DEVICE_PROPERTY_CAPTURE_DELAY = 0x5012;
    /** @hide */
    public static final int DEVICE_PROPERTY_STILL_CAPTURE_MODE = 0x5013;
    /** @hide */
    public static final int DEVICE_PROPERTY_CONTRAST = 0x5014;
    /** @hide */
    public static final int DEVICE_PROPERTY_SHARPNESS = 0x5015;
    /** @hide */
    public static final int DEVICE_PROPERTY_DIGITAL_ZOOM = 0x5016;
    /** @hide */
    public static final int DEVICE_PROPERTY_EFFECT_MODE = 0x5017;
    /** @hide */
    public static final int DEVICE_PROPERTY_BURST_NUMBER= 0x5018;
    /** @hide */
    public static final int DEVICE_PROPERTY_BURST_INTERVAL = 0x5019;
    /** @hide */
    public static final int DEVICE_PROPERTY_TIMELAPSE_NUMBER = 0x501A;
    /** @hide */
    public static final int DEVICE_PROPERTY_TIMELAPSE_INTERVAL = 0x501B;
    /** @hide */
    public static final int DEVICE_PROPERTY_FOCUS_METERING_MODE = 0x501C;
    /** @hide */
    public static final int DEVICE_PROPERTY_UPLOAD_URL = 0x501D;
    /** @hide */
    public static final int DEVICE_PROPERTY_ARTIST = 0x501E;
    /** @hide */
    public static final int DEVICE_PROPERTY_COPYRIGHT_INFO = 0x501F;
    /** @hide */
    public static final int DEVICE_PROPERTY_SYNCHRONIZATION_PARTNER = 0xD401;
    /** @hide */
    public static final int DEVICE_PROPERTY_DEVICE_FRIENDLY_NAME = 0xD402;
    /** @hide */
    public static final int DEVICE_PROPERTY_VOLUME = 0xD403;
    /** @hide */
    public static final int DEVICE_PROPERTY_SUPPORTED_FORMATS_ORDERED = 0xD404;
    /** @hide */
    public static final int DEVICE_PROPERTY_DEVICE_ICON = 0xD405;
    /** @hide */
    public static final int DEVICE_PROPERTY_PLAYBACK_RATE = 0xD410;
    /** @hide */
    public static final int DEVICE_PROPERTY_PLAYBACK_OBJECT = 0xD411;
    /** @hide */
    public static final int DEVICE_PROPERTY_PLAYBACK_CONTAINER_INDEX = 0xD412;
    /** @hide */
    public static final int DEVICE_PROPERTY_SESSION_INITIATOR_VERSION_INFO = 0xD406;
    /** @hide */
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

    /**
     * Association type for objects representing file system directories.
     */
    public static final int ASSOCIATION_TYPE_GENERIC_FOLDER = 0x0001;

    /** @removed */
    public static final int EVENT_UNDEFINED = 0x4000;
    /** @removed */
    public static final int EVENT_CANCEL_TRANSACTION = 0x4001;
    /** @removed */
    public static final int EVENT_OBJECT_ADDED = 0x4002;
    /** @removed */
    public static final int EVENT_OBJECT_REMOVED = 0x4003;
    /** @removed */
    public static final int EVENT_STORE_ADDED = 0x4004;
    /** @removed */
    public static final int EVENT_STORE_REMOVED = 0x4005;
    /** @removed */
    public static final int EVENT_DEVICE_PROP_CHANGED = 0x4006;
    /** @removed */
    public static final int EVENT_OBJECT_INFO_CHANGED = 0x4007;
    /** @removed */
    public static final int EVENT_DEVICE_INFO_CHANGED = 0x4008;
    /** @removed */
    public static final int EVENT_REQUEST_OBJECT_TRANSFER = 0x4009;
    /** @removed */
    public static final int EVENT_STORE_FULL = 0x400A;
    /** @removed */
    public static final int EVENT_DEVICE_RESET = 0x400B;
    /** @removed */
    public static final int EVENT_STORAGE_INFO_CHANGED = 0x400C;
    /** @removed */
    public static final int EVENT_CAPTURE_COMPLETE = 0x400D;
    /** @removed */
    public static final int EVENT_UNREPORTED_STATUS = 0x400E;
    /** @removed */
    public static final int EVENT_OBJECT_PROP_CHANGED = 0xC801;
    /** @removed */
    public static final int EVENT_OBJECT_PROP_DESC_CHANGED = 0xC802;
    /** @removed */
    public static final int EVENT_OBJECT_REFERENCES_CHANGED = 0xC803;

    /** Operation code for GetDeviceInfo */
    public static final int OPERATION_GET_DEVICE_INFO = 0x1001;
    /** Operation code for OpenSession */
    public static final int OPERATION_OPEN_SESSION = 0x1002;
    /** Operation code for CloseSession */
    public static final int OPERATION_CLOSE_SESSION = 0x1003;
    /** Operation code for GetStorageIDs */
    public static final int OPERATION_GET_STORAGE_I_DS = 0x1004;
    /** Operation code for GetStorageInfo */
    public static final int OPERATION_GET_STORAGE_INFO = 0x1005;
    /** Operation code for GetNumObjects */
    public static final int OPERATION_GET_NUM_OBJECTS = 0x1006;
    /** Operation code for GetObjectHandles */
    public static final int OPERATION_GET_OBJECT_HANDLES = 0x1007;
    /** Operation code for GetObjectInfo */
    public static final int OPERATION_GET_OBJECT_INFO = 0x1008;
    /** Operation code for GetObject */
    public static final int OPERATION_GET_OBJECT = 0x1009;
    /** Operation code for GetThumb */
    public static final int OPERATION_GET_THUMB = 0x100A;
    /** Operation code for DeleteObject */
    public static final int OPERATION_DELETE_OBJECT = 0x100B;
    /** Operation code for SendObjectInfo */
    public static final int OPERATION_SEND_OBJECT_INFO = 0x100C;
    /** Operation code for SendObject */
    public static final int OPERATION_SEND_OBJECT = 0x100D;
    /** Operation code for InitiateCapture */
    public static final int OPERATION_INITIATE_CAPTURE = 0x100E;
    /** Operation code for FormatStore */
    public static final int OPERATION_FORMAT_STORE = 0x100F;
    /** Operation code for ResetDevice */
    public static final int OPERATION_RESET_DEVICE = 0x1010;
    /** Operation code for SelfTest */
    public static final int OPERATION_SELF_TEST = 0x1011;
    /** Operation code for SetObjectProtection */
    public static final int OPERATION_SET_OBJECT_PROTECTION = 0x1012;
    /** Operation code for PowerDown */
    public static final int OPERATION_POWER_DOWN = 0x1013;
    /** Operation code for GetDevicePropDesc */
    public static final int OPERATION_GET_DEVICE_PROP_DESC = 0x1014;
    /** Operation code for GetDevicePropValue */
    public static final int OPERATION_GET_DEVICE_PROP_VALUE = 0x1015;
    /** Operation code for SetDevicePropValue */
    public static final int OPERATION_SET_DEVICE_PROP_VALUE = 0x1016;
    /** Operation code for ResetDevicePropValue */
    public static final int OPERATION_RESET_DEVICE_PROP_VALUE = 0x1017;
    /** Operation code for TerminateOpenCapture */
    public static final int OPERATION_TERMINATE_OPEN_CAPTURE = 0x1018;
    /** Operation code for MoveObject */
    public static final int OPERATION_MOVE_OBJECT = 0x1019;
    /** Operation code for CopyObject */
    public static final int OPERATION_COPY_OBJECT = 0x101A;
    /** Operation code for GetPartialObject */
    public static final int OPERATION_GET_PARTIAL_OBJECT = 0x101B;
    /** Operation code for InitiateOpenCapture */
    public static final int OPERATION_INITIATE_OPEN_CAPTURE = 0x101C;
    /** Operation code for GetObjectPropsSupported */
    public static final int OPERATION_GET_OBJECT_PROPS_SUPPORTED = 0x9801;
    /** Operation code for GetObjectPropDesc */
    public static final int OPERATION_GET_OBJECT_PROP_DESC = 0x9802;
    /** Operation code for GetObjectPropValue */
    public static final int OPERATION_GET_OBJECT_PROP_VALUE = 0x9803;
    /** Operation code for SetObjectPropValue */
    public static final int OPERATION_SET_OBJECT_PROP_VALUE = 0x9804;
    /** Operation code for GetObjectReferences */
    public static final int OPERATION_GET_OBJECT_REFERENCES = 0x9810;
    /** Operation code for SetObjectReferences */
    public static final int OPERATION_SET_OBJECT_REFERENCES = 0x9811;
    /** Operation code for Skip */
    public static final int OPERATION_SKIP = 0x9820;
    /** Operation code for GetPartialObject64 */
    public static final int OPERATION_GET_PARTIAL_OBJECT_64 = 0x95C1;
}
