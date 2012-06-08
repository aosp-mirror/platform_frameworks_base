/*
 * Copyright (C) 2012 The Android Open Source Project
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

/* package */ class WebHistoryItemClassic extends WebHistoryItem implements Cloneable {
    // Global identifier count.
    private static int sNextId = 0;
    // Unique identifier.
    private final int mId;
    // A point to a native WebHistoryItem instance which contains the actual data
    private int mNativeBridge;
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
    private WebHistoryItemClassic(int nativeBridge) {
        synchronized (WebHistoryItemClassic.class) {
            mId = sNextId++;
        }
        mNativeBridge = nativeBridge;
        nativeRef(mNativeBridge);
    }

    protected void finalize() throws Throwable {
        if (mNativeBridge != 0) {
            nativeUnref(mNativeBridge);
            mNativeBridge = 0;
        }
    }

    /**
     * Construct a new WebHistoryItem with initial flattened data.
     * @param data The pre-flattened data coming from restoreState.
     */
    /*package*/ WebHistoryItemClassic(byte[] data) {
        mFlattenedData = data;
        synchronized (WebHistoryItemClassic.class) {
            mId = sNextId++;
        }
    }

    /**
     * Construct a clone of a WebHistoryItem from the given item.
     * @param item The history item to clone.
     */
    private WebHistoryItemClassic(WebHistoryItemClassic item) {
        mFlattenedData = item.mFlattenedData;
        mId = item.mId;
        mFavicon = item.mFavicon;
        mNativeBridge = item.mNativeBridge;
        if (mNativeBridge != 0) {
            nativeRef(mNativeBridge);
        }
    }

    @Deprecated
    public int getId() {
        return mId;
    }

    public String getUrl() {
        if (mNativeBridge == 0) return null;
        return nativeGetUrl(mNativeBridge);
    }

    public String getOriginalUrl() {
        if (mNativeBridge == 0) return null;
        return nativeGetOriginalUrl(mNativeBridge);
    }

    public String getTitle() {
        if (mNativeBridge == 0) return null;
        return nativeGetTitle(mNativeBridge);
    }

    public Bitmap getFavicon() {
        if (mFavicon == null && mNativeBridge != 0) {
            mFavicon = nativeGetFavicon(mNativeBridge);
        }
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
            URL url = new URL(getOriginalUrl());
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
        if (mNativeBridge != 0) {
            return nativeGetFlattenedData(mNativeBridge);
        }
        return mFlattenedData;
    }

    /**
     * Inflate this item.
     * Note: The VM ensures 32-bit atomic read/write operations so we don't have
     * to synchronize this method.
     */
    /*package*/ void inflate(int nativeFrame) {
        mNativeBridge = inflate(nativeFrame, mFlattenedData);
        mFlattenedData = null;
    }

    public synchronized WebHistoryItemClassic clone() {
        return new WebHistoryItemClassic(this);
    }

    /* Natively inflate this item, this method is called in the WebCore thread.
     */
    private native int inflate(int nativeFrame, byte[] data);
    private native void nativeRef(int nptr);
    private native void nativeUnref(int nptr);
    private native String nativeGetTitle(int nptr);
    private native String nativeGetUrl(int nptr);
    private native String nativeGetOriginalUrl(int nptr);
    private native byte[] nativeGetFlattenedData(int nptr);
    private native Bitmap nativeGetFavicon(int nptr);

}
