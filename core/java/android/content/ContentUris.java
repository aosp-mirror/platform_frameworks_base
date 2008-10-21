/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content;

import android.net.Uri;

/**
 * Utility methods useful for working with content {@link android.net.Uri}s,
 * those with a "content" scheme.
 */
public class ContentUris {

    /**
     * Converts the last path segment to a long.
     *
     * <p>This supports a common convention for content URIs where an ID is
     * stored in the last segment.
     *
     * @throws UnsupportedOperationException if this isn't a hierarchical URI
     * @throws NumberFormatException if the last segment isn't a number
     *
     * @return the long conversion of the last segment or -1 if the path is
     *  empty
     */
    public static long parseId(Uri contentUri) {
        String last = contentUri.getLastPathSegment();
        return last == null ? -1 : Long.parseLong(last);
    }

    /**
     * Appends the given ID to the end of the path.
     *
     * @param builder to append the ID to
     * @param id to append
     *
     * @return the given builder
     */
    public static Uri.Builder appendId(Uri.Builder builder, long id) {
        return builder.appendEncodedPath(String.valueOf(id));
    }

    /**
     * Appends the given ID to the end of the path.
     *
     * @param contentUri to start with
     * @param id to append
     *
     * @return a new URI with the given ID appended to the end of the path
     */
    public static Uri withAppendedId(Uri contentUri, long id) {
        return appendId(contentUri.buildUpon(), id).build();
    }
}
