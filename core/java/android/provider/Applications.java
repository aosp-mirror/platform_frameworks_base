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

package android.provider;

import android.app.SearchManager;
import android.net.Uri;
import android.widget.SimpleCursorAdapter;

/**
 * <p>The Applications provider gives information about installed applications.</p>
 * 
 * <p>This provider provides the following columns:
 * 
 * <table border="2" width="85%" align="center" frame="hsides" rules="rows">
 *
 *     <thead>
 *     <tr><th>Column Name</th> <th>Description</th> </tr>
 *     </thead>
 *
 * <tbody>
 * <tr><th>{@link SearchManager#SUGGEST_COLUMN_TEXT_1}</th>
 *     <td>The application name.</td>
 * </tr>
 * 
 * <tr><th>{@link SearchManager#SUGGEST_COLUMN_INTENT_COMPONENT}</th>
 *     <td>The component to be used when forming the intent.</td>
 * </tr>
 * 
 * <tr><th>{@link SearchManager#SUGGEST_COLUMN_ICON_1}</th>
 *     <td>The application's icon resource id, prepended by its package name and
 *         separated by a colon, e.g., "com.android.alarmclock:2130837524". The
 *         package name is required for an activity interpreting this value to
 *         be able to correctly access the icon drawable, for example, in an override of
 *         {@link SimpleCursorAdapter#setViewImage(android.widget.ImageView, String)}.</td>
 * </tr>
 * 
 * <tr><th>{@link SearchManager#SUGGEST_COLUMN_ICON_2}</th>
 *     <td><i>Unused - column provided to conform to the {@link SearchManager} stipulation
 *            that all providers provide either both or neither of
 *            {@link SearchManager#SUGGEST_COLUMN_ICON_1} and
 *            {@link SearchManager#SUGGEST_COLUMN_ICON_2}.</td>
 * </tr>
 * 
 * @hide pending API council approval - should be unhidden at the same time as
 *       {@link SearchManager#SUGGEST_COLUMN_INTENT_COMPONENT}
 */
public class Applications {
    private static final String TAG = "Applications";

    /**
     * The content authority for this provider.
     *
     * @hide
     */
    public static final String AUTHORITY = "applications";

    /**
     * The content:// style URL for this provider
     *
     * @hide
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * no public constructor since this is a utility class
     */
    private Applications() {}
}
