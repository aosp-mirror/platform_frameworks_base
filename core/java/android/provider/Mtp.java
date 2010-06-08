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

package android.provider;

import android.content.ContentUris;
import android.net.Uri;
import android.util.Log;


/**
 * The MTP provider supports accessing content on MTP and PTP devices.
 * @hide
 */
public final class Mtp
{
    private final static String TAG = "Mtp";

    public static final String AUTHORITY = "mtp";

    private static final String CONTENT_AUTHORITY_SLASH = "content://" + AUTHORITY + "/";
    private static final String CONTENT_AUTHORITY_DEVICE_SLASH = "content://" + AUTHORITY + "/device/";

    /**
     * Contains list of all MTP/PTP devices
     */
    public static final class Device implements BaseColumns {

        public static final Uri CONTENT_URI = Uri.parse(CONTENT_AUTHORITY_SLASH + "device");

        /**
         * The manufacturer of the device
         * <P>Type: TEXT</P>
         */
        public static final String MANUFACTURER = "manufacturer";

        /**
         * The model name of the device
         * <P>Type: TEXT</P>
         */
        public static final String MODEL = "model";
    }

    /**
     * Contains list of storage units for an MTP/PTP device
     */
    public static final class Storage implements BaseColumns {

        public static Uri getContentUri(int deviceID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID + "/storage");
        }

        /**
         * Storage unit identifier
         * <P>Type: TEXT</P>
         */
        public static final String IDENTIFIER = "identifier";

        /**
         * Storage unit description
         * <P>Type: TEXT</P>
         */
        public static final String DESCRIPTION = "description";
    }

    /**
     * Contains list of objects on an MTP/PTP device
     */
    public static final class Object implements BaseColumns {

        public static Uri getContentUri(int deviceID, int objectID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/object/" + objectID);
        }

        public static Uri getContentUriForObjectChildren(int deviceID, int objectID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/object/" + objectID + "/child");
        }

        public static Uri getContentUriForStorageChildren(int deviceID, int storageID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/storage/" + storageID + "/child");
        }

        /**
         * The following columns correspond to the fields in the ObjectInfo dataset
         * as described in the MTP specification.
         */

        /**
         * The ID of the storage unit containing the object.
         * <P>Type: INTEGER</P>
         */
        public static final String STORAGE_ID = "storage_id";

        /**
         * The object's format.  Can be one of the FORMAT_* symbols below,
         * or any of the valid MTP object formats as defined in the MTP specification.
         * <P>Type: INTEGER</P>
         */
        public static final String FORMAT = "format";

        /**
         * The protection status of the object.  See the PROTECTION_STATUS_*symbols below.
         * <P>Type: INTEGER</P>
         */
        public static final String PROTECTION_STATUS = "protection_status";

        /**
         * The size of the object in bytes.
         * <P>Type: INTEGER</P>
         */
        public static final String SIZE = "size";

        /**
         * The object's thumbnail format.  Can be one of the FORMAT_* symbols below,
         * or any of the valid MTP object formats as defined in the MTP specification.
         * <P>Type: INTEGER</P>
         */
        public static final String THUMB_FORMAT = "format";

        /**
         * The size of the object's thumbnail in bytes.
         * <P>Type: INTEGER</P>
         */
        public static final String THUMB_SIZE = "thumb_size";

        /**
         * The width of the object's thumbnail in pixels.
         * <P>Type: INTEGER</P>
         */
        public static final String THUMB_WIDTH = "thumb_width";

        /**
         * The height of the object's thumbnail in pixels.
         * <P>Type: INTEGER</P>
         */
        public static final String THUMB_HEIGHT = "thumb_height";

        /**
         * The width of the object in pixels.
         * <P>Type: INTEGER</P>
         */
        public static final String IMAGE_WIDTH = "image_width";

        /**
         * The height of the object in pixels.
         * <P>Type: INTEGER</P>
         */
        public static final String IMAGE_HEIGHT = "image_height";

        /**
         * The depth of the object in bits per pixel.
         * <P>Type: INTEGER</P>
         */
        public static final String IMAGE_DEPTH = "image_depth";

        /**
         * The ID of the object's parent, or zero if the object
         * is in the root of its storage unit.
         * <P>Type: INTEGER</P>
         */
        public static final String PARENT = "parent";

        /**
         * The association type for a container object.
         * For folders this is typically {@link #ASSOCIATION_TYPE_GENERIC_FOLDER}
         * <P>Type: INTEGER</P>
         */
        public static final String ASSOCIATION_TYPE = "association_type";

        /**
         * Contains additional information about container objects.
         * <P>Type: INTEGER</P>
         */
        public static final String ASSOCIATION_DESC = "association_desc";

        /**
         * The sequence number of the object, typically used for an association
         * containing images taken in sequence.
         * <P>Type: INTEGER</P>
         */
        public static final String SEQUENCE_NUMBER = "sequence_number";

        /**
         * The name of the object.
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";

        /**
         * The date the object was created, in seconds since January 1, 1970.
         * <P>Type: INTEGER</P>
         */
        public static final String DATE_CREATED = "date_created";

        /**
         * The date the object was last modified, in seconds since January 1, 1970.
         * <P>Type: INTEGER</P>
         */
        public static final String DATE_MODIFIED = "date_modified";

        /**
         * A list of keywords associated with an object, separated by spaces.
         * <P>Type: TEXT</P>
         */
        public static final String KEYWORDS = "keywords";

        /**
         * Contants for {@link #FORMAT} and {@link #THUMB_FORMAT}
         */
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
}
