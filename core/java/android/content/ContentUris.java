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
* Utility methods useful for working with {@link android.net.Uri} objects
* that use the &quot;content&quot; (content://) scheme.
*
*<p>
*   Content URIs have the syntax
*</p>
*<p>
*   <code>content://<em>authority</em>/<em>path</em>/<em>id</em></code>
*</p>
*<dl>
*   <dt>
*       <code>content:</code>
*   </dt>
*   <dd>
*       The scheme portion of the URI. This is always set to {@link
*       android.content.ContentResolver#SCHEME_CONTENT ContentResolver.SCHEME_CONTENT} (value
*       <code>content://</code>).
*   </dd>
*   <dt>
*       <em>authority</em>
*   </dt>
*   <dd>
*       A string that identifies the entire content provider. All the content URIs for the provider
*       start with this string. To guarantee a unique authority, providers should consider
*       using an authority that is the same as the provider class' package identifier.
*   </dd>
*   <dt>
*       <em>path</em>
*   </dt>
*   <dd>
*       Zero or more segments, separated by a forward slash (<code>/</code>), that identify
*       some subset of the provider's data. Most providers use the path part to identify
*       individual tables. Individual segments in the path are often called
*       &quot;directories&quot; although they do not refer to file directories. The right-most
*       segment in a path is often called a &quot;twig&quot;
*   </dd>
*   <dt>
*       <em>id</em>
*   </dt>
*   <dd>
*       A unique numeric identifier for a single row in the subset of data identified by the
*       preceding path part. Most providers recognize content URIs that contain an id part
*       and give them special handling. A table that contains a column named <code>_ID</code>
*       often expects the id part to be a particular value for that column.
*   </dd>
*</dl>
*
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
