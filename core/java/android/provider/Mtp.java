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

        public static Uri getContentUriForObjectChildren(int deviceID, int objectID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/object/" + objectID + "/child");
        }

        public static Uri getContentUriForStorageChildren(int deviceID, int storageID) {
            return Uri.parse(CONTENT_AUTHORITY_DEVICE_SLASH + deviceID
                    + "/storage/" + storageID + "/child");
        }

        /**
         * Name of the object
         * <P>Type: TEXT</P>
         */
        public static final String NAME = "name";
    }
}
