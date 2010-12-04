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
 * The PTP provider supports accessing content on PTP devices.
 * Currently the provider supports:
 * - enumerating the storage units, files and directories on PTP devices
 * - deleting files and directories on PTP devices
 * - importing a file from PTP device into the host device's storage
 *   and adding it to the media provider
 */
public final class Ptp
{
    private final static String TAG = "Ptp";

    public static final String AUTHORITY = "ptp";

    private static final String CONTENT_AUTHORITY_SLASH = "content://" + AUTHORITY + "/";
    private static final String CONTENT_AUTHORITY_DEVICE_SLASH = "content://" + AUTHORITY + "/device/";

    /**
     * Contains list of all PTP devices
     * The BaseColumns._ID column contains a hardware specific identifier for the attached
     * USB device, and is not guaranteed to be persistent across USB disconnects.
     */
    public static final class Device implements BaseColumns {

        public static final Uri CONTENT_URI = Uri.parse(CONTENT_AUTHORITY_SLASH + "device");

        public static Uri getContentUri(int deviceID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID);
        }

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
     * Contains list of storage units for an PTP device.
     * The BaseColumns._ID column contains the PTP StorageID for the storage unit.
     */
    public static final class Storage implements BaseColumns {

        public static Uri getContentUri(int deviceID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID + "/storage");
        }

        public static Uri getContentUri(int deviceID, long storageID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID + "/storage/" + storageID);
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
     * Contains list of objects on a PTP device.
     * The columns in this table correspond directly to the ObjectInfo dataset
     * described in the PTP specification (PIMA 15740:2000).
     * The BaseColumns._ID column contains the object's PTP ObjectHandle.
     */
    public static final class Object implements BaseColumns {

        public static Uri getContentUri(int deviceID, long objectID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/object/" + objectID);
        }

        public static Uri getContentUriForObjectChildren(int deviceID, long objectID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/object/" + objectID + "/child");
        }

        public static Uri getContentUriForStorageChildren(int deviceID, long storageID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/storage/" + storageID + "/child");
        }

        /**
         * Used for copying files from device to host.
         * Constructs a Uri based on the ID of the device and object for the source file,
         * and the path for the destination file.
         * When passed to the ContentProvider.insert() method, the file will be transferred
         * to the specified destination directory and insert() will return a content Uri
         * for the new file in the MediaProvider.
         * ContentProvider.insert() will throw IllegalArgumentException if the destination
         * path is not in the external storage or internal media directory.
         */
        public static Uri getContentUriForImport(int deviceID, long objectID, String destPath) {
            if (destPath.length() == 0 || destPath.charAt(0) != '/') {
                throw new IllegalArgumentException(
                        "destPath must be a full path in getContentUriForImport");
            }
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/import/" + objectID + "?" +  destPath);
        }

        /**
         * The following columns correspond to the fields in the ObjectInfo dataset
         * as described in the PTP specification.
         */

        /**
         * The ID of the storage unit containing the object.
         * <P>Type: INTEGER</P>
         */
        public static final String STORAGE_ID = "storage_id";

        /**
         * The object's format.  Can be any of the valid PTP object formats
         * as defined in the PTP specification.
         * <P>Type: INTEGER</P>
         */
        public static final String FORMAT = "format";

        /**
         * The protection status of the object.
         * <P>Type: INTEGER</P>
         */
        public static final String PROTECTION_STATUS = "protection_status";

        /**
         * The size of the object in bytes.
         * <P>Type: INTEGER</P>
         */
        public static final String SIZE = "size";

        /**
         * The object's thumbnail format.  Can be any of the valid PTP object formats
         * as defined in the PTP specification.
         * <P>Type: INTEGER</P>
         */
        public static final String THUMB_FORMAT = "thumb_format";

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
         * The object's thumbnail.
         * <P>Type: BLOB</P>
         */
        public static final String THUMB = "thumb";

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
    }
}
