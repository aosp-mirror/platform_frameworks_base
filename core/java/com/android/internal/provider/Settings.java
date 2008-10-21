/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.provider;

import android.provider.BaseColumns;
import android.net.Uri;

/**
 * Settings related utilities.
 */
public class Settings {
    /**
     * Favorite intents
     */
    public static final class Favorites implements BaseColumns {
        /**
         * The content:// style URL for this table
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" +
                android.provider.Settings.AUTHORITY + "/favorites?notify=true");

        /**
         * The content:// style URL for this table. When this Uri is used, no notification is
         * sent if the content changes.
         */
        public static final Uri CONTENT_URI_NO_NOTIFICATION =
                Uri.parse("content://" + android.provider.Settings.AUTHORITY +
                        "/favorites?notify=false");

        /**
         * The content:// style URL for a given row, identified by its id.
         *
         * @param id The row id.
         * @param notify True to send a notification is the content changes.
         *
         * @return The unique content URL for the specified row.
         */
        public static Uri getContentUri(long id, boolean notify) {
            return Uri.parse("content://" + android.provider.Settings.AUTHORITY +
                    "/favorites/" + id + "?notify=" + notify);
        }

        /**
         * The row ID.
         * <p>Type: INTEGER</p>
         */
        public static final String ID = "_id";

        /**
         * Descriptive name of the favorite that can be displayed to the user.
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The Intent URL of the favorite, describing what it points to.  This
         * value is given to {@link android.content.Intent#getIntent} to create
         * an Intent that can be launched.
         * <P>Type: TEXT</P>
         */
        public static final String INTENT = "intent";

        /**
         * The container holding the favorite
         * <P>Type: INTEGER</P>
         */
        public static final String CONTAINER = "container";

        /**
         * The icon is a resource identified by a package name and an integer id.
         */
        public static final int CONTAINER_DESKTOP = -100;

        /**
         * The screen holding the favorite (if container is CONTAINER_DESKTOP)
         * <P>Type: INTEGER</P>
         */
        public static final String SCREEN = "screen";

        /**
         * The X coordinate of the cell holding the favorite
         * (if container is CONTAINER_DESKTOP or CONTAINER_DOCK)
         * <P>Type: INTEGER</P>
         */
        public static final String CELLX = "cellX";

        /**
         * The Y coordinate of the cell holding the favorite
         * (if container is CONTAINER_DESKTOP)
         * <P>Type: INTEGER</P>
         */
        public static final String CELLY = "cellY";

        /**
         * The X span of the cell holding the favorite
         * <P>Type: INTEGER</P>
         */
        public static final String SPANX = "spanX";

        /**
         * The Y span of the cell holding the favorite
         * <P>Type: INTEGER</P>
         */
        public static final String SPANY = "spanY";

        /**
         * The type of the favorite
         *
         * <P>Type: INTEGER</P>
         */
        public static final String ITEM_TYPE = "itemType";

        /**
         * The favorite is an application
         */
        public static final int ITEM_TYPE_APPLICATION = 0;

        /**
         * The favorite is an application created shortcut
         */
        public static final int ITEM_TYPE_SHORTCUT = 1;

        /**
         * The favorite is a user created folder
         */
        public static final int ITEM_TYPE_USER_FOLDER = 2;

        /**
         * The favorite is a clock
         */
        public static final int ITEM_TYPE_WIDGET_CLOCK = 1000;

        /**
         * The favorite is a search widget
         */
        public static final int ITEM_TYPE_WIDGET_SEARCH = 1001;

        /**
         * The favorite is a photo frame
         */
        public static final int ITEM_TYPE_WIDGET_PHOTO_FRAME = 1002;

        /**
         * Indicates whether this favorite is an application-created shortcut or not.
         * If the value is 0, the favorite is not an application-created shortcut, if the
         * value is 1, it is an application-created shortcut.
         * <P>Type: INTEGER</P>
         */
        public static final String IS_SHORTCUT = "isShortcut";

        /**
         * The icon type.
         * <P>Type: INTEGER</P>
         */
        public static final String ICON_TYPE = "iconType";

        /**
         * The icon is a resource identified by a package name and an integer id.
         */
        public static final int ICON_TYPE_RESOURCE = 0;

        /**
         * The icon is a bitmap.
         */
        public static final int ICON_TYPE_BITMAP = 1;

        /**
         * The icon package name, if icon type is ICON_TYPE_RESOURCE.
         * <P>Type: TEXT</P>
         */
        public static final String ICON_PACKAGE = "iconPackage";

        /**
         * The icon resource id, if icon type is ICON_TYPE_RESOURCE.
         * <P>Type: TEXT</P>
         */
        public static final String ICON_RESOURCE = "iconResource";

        /**
         * The custom icon bitmap, if icon type is ICON_TYPE_BITMAP.
         * <P>Type: BLOB</P>
         */
        public static final String ICON = "icon";
    }
}
