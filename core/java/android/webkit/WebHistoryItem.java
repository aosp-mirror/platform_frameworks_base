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

import android.graphics.Bitmap;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * A convenience class for accessing fields in an entry in the back/forward list
 * of a WebView. Each WebHistoryItem is a snapshot of the requested history
 * item. Each history item may be updated during the load of a page.
 * @see WebBackForwardList
 */
public class WebHistoryItem implements Cloneable {
    // Global identifier count.
    private static int sNextId = 0;
    // Unique identifier.
    private final int mId;
    // The title of this item's document.
    private String mTitle;
    // The base url of this item.
    private String mUrl;
    // The original requested url of this item.
    private String mOriginalUrl;
    // The favicon for this item.
    private Bitmap mFavicon;
    // The pre-flattened data used for saving the state.
    private byte[] mFlattenedData;
    // The apple-touch-icon url for use when adding the site to the home screen,
    // as obtained from a <link> element in the page.
    private String mTouchIconUrlFromLink;
    // If no <link> is specified, this holds the default location of the
    // apple-touch-icon.
    private String mTouchIconUrlServerDefault;
    // Custom client data that is not flattened or read by native code.
    private Object mCustomData;

    /**
     * Basic constructor that assigns a unique id to the item. Called by JNI
     * only.
     */
    private WebHistoryItem() {
        synchronized (WebHistoryItem.class) {
            mId = sNextId++;
        }
    }

    /**
     * Construct a new WebHistoryItem with initial flattened data.
     * @param data The pre-flattened data coming from restoreState.
     */
    /*package*/ WebHistoryItem(byte[] data) {
        mUrl = null; // This will be updated natively
        mFlattenedData = data;
        synchronized (WebHistoryItem.class) {
            mId = sNextId++;
        }
    }

    /**
     * Construct a clone of a WebHistoryItem from the given item.
     * @param item The history item to clone.
     */
    private WebHistoryItem(WebHistoryItem item) {
        mUrl = item.mUrl;
        mTitle = item.mTitle;
        mFlattenedData = item.mFlattenedData;
        mFavicon = item.mFavicon;
        mId = item.mId;
}

    /**
     * Return an identifier for this history item. If an item is a copy of
     * another item, the identifiers will be the same even if they are not the
     * same object.
     * @return The id for this item.
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public int getId() {
        return mId;
    }

    /**
     * Return the url of this history item. The url is the base url of this
     * history item. See getTargetUrl() for the url that is the actual target of
     * this history item.
     * @return The base url of this history item.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    public String getUrl() {
        return mUrl;
    }

    /**
     * Return the original url of this history item. This was the requested
     * url, the final url may be different as there might have been 
     * redirects while loading the site.
     * @return The original url of this history item.
     */
    public String getOriginalUrl() {
        return mOriginalUrl;
    }
    
    /**
     * Return the document title of this history item.
     * @return The document title of this history item.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    public String getTitle() {
        return mTitle;
    }

    /**
     * Return the favicon of this history item or null if no favicon was found.
     * @return A Bitmap containing the favicon for this history item or null.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    public Bitmap getFavicon() {
        return mFavicon;
    }

    /**
     * Return the touch icon url.
     * If no touch icon <link> tag was specified, returns
     * <host>/apple-touch-icon.png. The DownloadTouchIcon class that
     * attempts to retrieve the touch icon will handle the case where
     * that file does not exist. An icon set by a <link> tag is always
     * used in preference to an icon saved on the server.
     * @hide
     */
    public String getTouchIconUrl() {
        if (mTouchIconUrlFromLink != null) {
            return mTouchIconUrlFromLink;
        } else if (mTouchIconUrlServerDefault != null) {
            return mTouchIconUrlServerDefault;
        }

        try {
            URL url = new URL(mOriginalUrl);
            mTouchIconUrlServerDefault = new URL(url.getProtocol(), url.getHost(), url.getPort(),
                    "/apple-touch-icon.png").toString();
        } catch (MalformedURLException e) {
            return null;
        }
        return mTouchIconUrlServerDefault;
    }

    /**
     * Return the custom data provided by the client.
     * @hide
     */
    public Object getCustomData() {
        return mCustomData;
    }

    /**
     * Set the custom data field.
     * @param data An Object containing any data the client wishes to associate
     *             with the item.
     * @hide
     */
    public void setCustomData(Object data) {
        // NOTE: WebHistoryItems are used in multiple threads. However, the
        // public facing apis are all getters with the exception of this one
        // api. Since this api is exclusive to clients, we don't make any
        // promises about thread safety.
        mCustomData = data;
    }

    /**
     * Set the favicon.
     * @param icon A Bitmap containing the favicon for this history item.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    /*package*/ void setFavicon(Bitmap icon) {
        mFavicon = icon;
    }

    /**
     * Set the touch icon url. Will not overwrite an icon that has been
     * set already from a <link> tag, unless the new icon is precomposed.
     * @hide
     */
    /*package*/ void setTouchIconUrl(String url, boolean precomposed) {
        if (precomposed || mTouchIconUrlFromLink == null) {
            mTouchIconUrlFromLink = url;
        }
    }

    /**
     * Get the pre-flattened data.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    /*package*/ byte[] getFlattenedData() {
        return mFlattenedData;
    }

    /**
     * Inflate this item.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    /*package*/ void inflate(int nativeFrame) {
        inflate(nativeFrame, mFlattenedData);
    }

    /**
     * Clone the history item for use by clients of WebView.
     */
    protected synchronized WebHistoryItem clone() {
        return new WebHistoryItem(this);
    }

    /* Natively inflate this item, this method is called in the WebCore thread.
     */
    private native void inflate(int nativeFrame, byte[] data);

    /* Called by jni when the item is updated */
    private void update(String url, String originalUrl, String title, 
            Bitmap favicon, byte[] data) {
        mUrl = url;
        mOriginalUrl = originalUrl;
        mTitle = title;
        mFavicon = favicon;
        mFlattenedData = data;
    }
}
