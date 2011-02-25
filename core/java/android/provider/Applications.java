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

import android.content.ComponentName;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;

import java.util.List;

/**
 * The Applications provider gives information about installed applications.
 *
 * @hide Only used by ApplicationsProvider so far.
 */
public class Applications {

    /**
     * The content authority for this provider.
     */
    public static final String AUTHORITY = "applications";

    /**
     * The content:// style URL for this provider
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    /**
     * The content path for application component URIs.
     */
    public static final String APPLICATION_PATH = "applications";

    /**
     * The content path for application search.
     */
    public static final String SEARCH_PATH = "search";

    private static final String APPLICATION_SUB_TYPE = "vnd.android.application";

    /**
     * The MIME type for a single application item.
     */
    public static final String APPLICATION_ITEM_TYPE =
            ContentResolver.CURSOR_ITEM_BASE_TYPE + "/" + APPLICATION_SUB_TYPE;

    /**
     * The MIME type for a list of application items.
     */
    public static final String APPLICATION_DIR_TYPE =
            ContentResolver.CURSOR_DIR_BASE_TYPE + "/" + APPLICATION_SUB_TYPE;

    /**
     * no public constructor since this is a utility class
     */
    private Applications() {}

    /**
     * Gets a cursor with application search results.
     * See {@link ApplicationColumns} for the columns available in the returned cursor.
     */
    public static Cursor search(ContentResolver resolver, String query) {
        Uri searchUri = CONTENT_URI.buildUpon().appendPath(SEARCH_PATH).appendPath(query).build();
        return resolver.query(searchUri, null, null, null, null);
    }

    /**
     * Gets the application component name from an application URI.
     *
     * @param appUri A URI of the form
     * "content://applications/applications/&lt;packageName&gt;/&lt;className&gt;".
     * @return The component name for the application, or
     * <code>null</code> if the given URI was <code>null</code>
     * or malformed.
     */
    public static ComponentName uriToComponentName(Uri appUri) {
        if (appUri == null) return null;
        if (!ContentResolver.SCHEME_CONTENT.equals(appUri.getScheme())) return null;
        if (!AUTHORITY.equals(appUri.getAuthority())) return null;
        List<String> pathSegments = appUri.getPathSegments();
        if (pathSegments.size() != 3) return null;
        if (!APPLICATION_PATH.equals(pathSegments.get(0))) return null;
        String packageName = pathSegments.get(1);
        String name = pathSegments.get(2);
        return new ComponentName(packageName, name);
    }

    /**
     * Gets the URI for an application component.
     *
     * @param packageName The name of the application's package.
     * @param className The class name of the application.
     * @return A URI of the form
     * "content://applications/applications/&lt;packageName&gt;/&lt;className&gt;".
     */
    public static Uri componentNameToUri(String packageName, String className) {
        return Applications.CONTENT_URI.buildUpon()
                .appendEncodedPath(APPLICATION_PATH)
                .appendPath(packageName)
                .appendPath(className)
                .build();
    }

    /**
     * The columns in application cursors, like those returned by
     * {@link Applications#search(ContentResolver, String)}.
     */
    public interface ApplicationColumns extends BaseColumns {
        public static final String NAME = "name";
        public static final String ICON = "icon";
        public static final String URI = "uri";
    }
}
