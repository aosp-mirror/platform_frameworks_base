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
import android.annotation.UnsupportedAppUsage;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Manages the HTTP cache used by an application's {@link WebView} instances.
 * @deprecated Access to the HTTP cache will be removed in a future release.
 * @hide Since {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
 */
// The class CacheManager provides the persistent cache of content that is
// received over the network. The component handles parsing of HTTP headers and
// utilizes the relevant cache headers to determine if the content should be
// stored and if so, how long it is valid for. Network requests are provided to
// this component and if they can not be resolved by the cache, the HTTP headers
// are attached, as appropriate, to the request for revalidation of content. The
// class also manages the cache size.
//
// CacheManager may only be used if your activity contains a WebView.
@Deprecated
public final class CacheManager {
    /**
     * Represents a resource stored in the HTTP cache. Instances of this class
     * can be obtained by calling
     * {@link CacheManager#getCacheFile CacheManager.getCacheFile(String, Map<String, String>))}.
     *
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static class CacheResult {
        // these fields are saved to the database
        @UnsupportedAppUsage
        int httpStatusCode;
        @UnsupportedAppUsage
        long contentLength;
        @UnsupportedAppUsage
        long expires;
        @UnsupportedAppUsage
        String expiresString;
        @UnsupportedAppUsage
        String localPath;
        @UnsupportedAppUsage
        String lastModified;
        @UnsupportedAppUsage
        String etag;
        @UnsupportedAppUsage
        String mimeType;
        @UnsupportedAppUsage
        String location;
        @UnsupportedAppUsage
        String encoding;
        @UnsupportedAppUsage
        String contentdisposition;
        @UnsupportedAppUsage
        String crossDomain;

        // these fields are NOT saved to the database
        @UnsupportedAppUsage
        InputStream inStream;
        @UnsupportedAppUsage
        OutputStream outStream;
        @UnsupportedAppUsage
        File outFile;

        /**
         * Gets the status code of this cache entry.
         *
         * @return the status code of this cache entry
         */
        @UnsupportedAppUsage
        public int getHttpStatusCode() {
            return httpStatusCode;
        }

        /**
         * Gets the content length of this cache entry.
         *
         * @return the content length of this cache entry
         */
        @UnsupportedAppUsage
        public long getContentLength() {
            return contentLength;
        }

        /**
         * Gets the path of the file used to store the content of this cache
         * entry, relative to the base directory of the cache. See
         * {@link CacheManager#getCacheFileBaseDir CacheManager.getCacheFileBaseDir()}.
         *
         * @return the path of the file used to store this cache entry
         */
        @UnsupportedAppUsage
        public String getLocalPath() {
            return localPath;
        }

        /**
         * Gets the expiry date of this cache entry, expressed in milliseconds
         * since midnight, January 1, 1970 UTC.
         *
         * @return the expiry date of this cache entry
         */
        @UnsupportedAppUsage
        public long getExpires() {
            return expires;
        }

        /**
         * Gets the expiry date of this cache entry, expressed as a string.
         *
         * @return the expiry date of this cache entry
         *
         */
        @UnsupportedAppUsage
        public String getExpiresString() {
            return expiresString;
        }

        /**
         * Gets the date at which this cache entry was last modified, expressed
         * as a string.
         *
         * @return the date at which this cache entry was last modified
         */
        @UnsupportedAppUsage
        public String getLastModified() {
            return lastModified;
        }

        /**
         * Gets the entity tag of this cache entry.
         *
         * @return the entity tag of this cache entry
         */
        @UnsupportedAppUsage
        public String getETag() {
            return etag;
        }

        /**
         * Gets the MIME type of this cache entry.
         *
         * @return the MIME type of this cache entry
         */
        @UnsupportedAppUsage
        public String getMimeType() {
            return mimeType;
        }

        /**
         * Gets the value of the HTTP 'Location' header with which this cache
         * entry was received.
         *
         * @return the HTTP 'Location' header for this cache entry
         */
        @UnsupportedAppUsage
        public String getLocation() {
            return location;
        }

        /**
         * Gets the encoding of this cache entry.
         *
         * @return the encoding of this cache entry
         */
        @UnsupportedAppUsage
        public String getEncoding() {
            return encoding;
        }

        /**
         * Gets the value of the HTTP 'Content-Disposition' header with which
         * this cache entry was received.
         *
         * @return the HTTP 'Content-Disposition' header for this cache entry
         *
         */
        @UnsupportedAppUsage
        public String getContentDisposition() {
            return contentdisposition;
        }

        /**
         * Gets the input stream to the content of this cache entry, to allow
         * content to be read. See
         * {@link CacheManager#getCacheFile CacheManager.getCacheFile(String, Map<String, String>)}.
         *
         * @return an input stream to the content of this cache entry
         */
        @UnsupportedAppUsage
        public InputStream getInputStream() {
            return inStream;
        }

        /**
         * Gets an output stream to the content of this cache entry, to allow
         * content to be written. See
         * {@link CacheManager#saveCacheFile CacheManager.saveCacheFile(String, CacheResult)}.
         *
         * @return an output stream to the content of this cache entry
         */
        // Note that this is always null for objects returned by getCacheFile()!
        @UnsupportedAppUsage
        public OutputStream getOutputStream() {
            return outStream;
        }


        /**
         * Sets an input stream to the content of this cache entry.
         *
         * @param stream an input stream to the content of this cache entry
         */
        @UnsupportedAppUsage
        public void setInputStream(InputStream stream) {
            this.inStream = stream;
        }

        /**
         * Sets the encoding of this cache entry.
         *
         * @param encoding the encoding of this cache entry
         */
        @UnsupportedAppUsage
        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        /**
         * @hide
         */
        public void setContentLength(long contentLength) {
            this.contentLength = contentLength;
        }
    }

    /**
     * Gets the base directory in which the files used to store the contents of
     * cache entries are placed. See
     * {@link CacheManager.CacheResult#getLocalPath CacheManager.CacheResult.getLocalPath()}.
     *
     * @return the base directory of the cache
     * @deprecated This method no longer has any effect and always returns {@code null}.
     */
    @Deprecated
    @Nullable
    @UnsupportedAppUsage
    public static File getCacheFileBaseDir() {
        return null;
    }

    /**
     * Gets whether the HTTP cache is disabled.
     *
     * @return {@code true} if the HTTP cache is disabled
     * @deprecated This method no longer has any effect and always returns {@code false}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean cacheDisabled() {
        return false;
    }

    /**
     * Starts a cache transaction. Returns {@code true} if this is the only running
     * transaction. Otherwise, this transaction is nested inside currently
     * running transactions and {@code false} is returned.
     *
     * @return {@code true} if this is the only running transaction
     * @deprecated This method no longer has any effect and always returns {@code false}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean startCacheTransaction() {
        return false;
    }

    /**
     * Ends the innermost cache transaction and returns whether this was the
     * only running transaction.
     *
     * @return {@code true} if this was the only running transaction
     * @deprecated This method no longer has any effect and always returns {@code false}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean endCacheTransaction() {
        return false;
    }

    /**
     * Gets the cache entry for the specified URL, or {@code null} if none is found.
     * If a non-null value is provided for the HTTP headers map, and the cache
     * entry needs validation, appropriate headers will be added to the map.
     * The input stream of the CacheEntry object should be closed by the caller
     * when access to the underlying file is no longer required.
     *
     * @param url the URL for which a cache entry is requested
     * @param headers a map from HTTP header name to value, to be populated
     *                for the returned cache entry
     * @return the cache entry for the specified URL
     * @deprecated This method no longer has any effect and always returns {@code null}.
     */
    @Deprecated
    @Nullable
    @UnsupportedAppUsage
    public static CacheResult getCacheFile(String url,
            Map<String, String> headers) {
        return null;
    }

    /**
     * Adds a cache entry to the HTTP cache for the specicifed URL. Also closes
     * the cache entry's output stream.
     *
     * @param url the URL for which the cache entry should be added
     * @param cacheResult the cache entry to add
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    @UnsupportedAppUsage
    public static void saveCacheFile(String url, CacheResult cacheResult) {
        saveCacheFile(url, 0, cacheResult);
    }

    @UnsupportedAppUsage
    static void saveCacheFile(String url, long postIdentifier,
            CacheResult cacheRet) {
        try {
            cacheRet.outStream.close();
        } catch (IOException e) {
            return;
        }

        // This method is exposed in the public API but the API provides no
        // way to obtain a new CacheResult object with a non-null output
        // stream ...
        // - CacheResult objects returned by getCacheFile() have a null
        //   output stream.
        // - new CacheResult objects have a null output stream and no
        //   setter is provided.
        // Since this method throws a null pointer exception in this case,
        // it is effectively useless from the point of view of the public
        // API.
        //
        // With the Chromium HTTP stack we continue to throw the same
        // exception for 'backwards compatibility' with the Android HTTP
        // stack.
        //
        // This method is not used from within this package, and for public API
        // use, we should already have thrown an exception above.
        assert false;
    }
}
