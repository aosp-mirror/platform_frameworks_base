/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.webkit;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.graphics.Bitmap;

/**
 * A convenience class for accessing fields in an entry in the back/forward list
 * of a WebView. Each WebHistoryItem is a snapshot of the requested history
 * item.
 * @see WebBackForwardList
 */
public abstract class WebHistoryItem implements Cloneable {
    /**
     * Return an identifier for this history item. If an item is a copy of
     * another item, the identifiers will be the same even if they are not the
     * same object.
     * @return The id for this item.
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract int getId();

    /**
     * Return the url of this history item. The url is the base url of this
     * history item. See getTargetUrl() for the url that is the actual target of
     * this history item.
     * @return The base url of this history item.
     */
    public abstract String getUrl();

    /**
     * Return the original url of this history item. This was the requested
     * url, the final url may be different as there might have been
     * redirects while loading the site.
     * @return The original url of this history item.
     */
    public abstract String getOriginalUrl();

    /**
     * Return the document title of this history item.
     * @return The document title of this history item.
     */
    public abstract String getTitle();

    /**
     * Return the favicon of this history item or {@code null} if no favicon was found.
     * @return A Bitmap containing the favicon for this history item or {@code null}.
     */
    @Nullable
    public abstract Bitmap getFavicon();

    /**
     * Clone the history item for use by clients of WebView. On Android 4.4 and later
     * there is no need to use this, as the object is already a read-only copy of the
     * internal state.
     */
    protected abstract WebHistoryItem clone();
}
