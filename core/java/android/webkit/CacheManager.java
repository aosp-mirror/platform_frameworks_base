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

import android.content.Context;
import android.net.http.AndroidHttpClient;
import android.net.http.Headers;
import android.os.FileUtils;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;


import com.android.org.bouncycastle.crypto.Digest;
import com.android.org.bouncycastle.crypto.digests.SHA1Digest;

/**
 * Manages the HTTP cache used by an application's {@link WebView} instances.
 * @deprecated Access to the HTTP cache will be removed in a future release.
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

    private static final String LOGTAG = "cache";

    static final String HEADER_KEY_IFMODIFIEDSINCE = "if-modified-since";
    static final String HEADER_KEY_IFNONEMATCH = "if-none-match";

    private static final String NO_STORE = "no-store";
    private static final String NO_CACHE = "no-cache";
    private static final String MAX_AGE = "max-age";
    private static final String MANIFEST_MIME = "text/cache-manifest";

    private static long CACHE_THRESHOLD = 6 * 1024 * 1024;
    private static long CACHE_TRIM_AMOUNT = 2 * 1024 * 1024;

    // Limit the maximum cache file size to half of the normal capacity
    static long CACHE_MAX_SIZE = (CACHE_THRESHOLD - CACHE_TRIM_AMOUNT) / 2;

    // Reference count the enable/disable transaction
    private static int mRefCount;

    // trimCacheIfNeeded() is called when a page is fully loaded. But JavaScript
    // can load the content, e.g. in a slideshow, continuously, so we need to
    // trim the cache on a timer base too. endCacheTransaction() is called on a 
    // timer base. We share the same timer with less frequent update.
    private static int mTrimCacheCount = 0;
    private static final int TRIM_CACHE_INTERVAL = 5;

    private static WebViewDatabase mDataBase;
    private static File mBaseDir;
    
    // Flag to clear the cache when the CacheManager is initialized
    private static boolean mClearCacheOnInit = false;

    /**
     * Represents a resource stored in the HTTP cache. Instances of this class
     * can be obtained by calling
     * {@link CacheManager#getCacheFile CacheManager.getCacheFile(String, Map<String, String>))}.
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static class CacheResult {
        // these fields are saved to the database
        int httpStatusCode;
        long contentLength;
        long expires;
        String expiresString;
        String localPath;
        String lastModified;
        String etag;
        String mimeType;
        String location;
        String encoding;
        String contentdisposition;
        String crossDomain;

        // these fields are NOT saved to the database
        InputStream inStream;
        OutputStream outStream;
        File outFile;

        /**
         * Gets the status code of this cache entry.
         * @return The status code of this cache entry
         */
        public int getHttpStatusCode() {
            return httpStatusCode;
        }

        /**
         * Gets the content length of this cache entry.
         * @return The content length of this cache entry
         */
        public long getContentLength() {
            return contentLength;
        }

        /**
         * Gets the path of the file used to store the content of this cache
         * entry, relative to the base directory of the cache. See
         * {@link CacheManager#getCacheFileBaseDir CacheManager.getCacheFileBaseDir()}.
         * @return The path of the file used to store this cache entry
         */
        public String getLocalPath() {
            return localPath;
        }

        /**
         * Gets the expiry date of this cache entry, expressed in milliseconds
         * since midnight, January 1, 1970 UTC.
         * @return The expiry date of this cache entry
         */
        public long getExpires() {
            return expires;
        }

        /**
         * Gets the expiry date of this cache entry, expressed as a string.
         * @return The expiry date of this cache entry
         *
         */
        public String getExpiresString() {
            return expiresString;
        }

        /**
         * Gets the date at which this cache entry was last modified, expressed
         * as a string.
         * @return The date at which this cache entry was last modified
         */
        public String getLastModified() {
            return lastModified;
        }

        /**
         * Gets the entity tag of this cache entry.
         * @return The entity tag of this cache entry
         */
        public String getETag() {
            return etag;
        }

        /**
         * Gets the MIME type of this cache entry.
         * @return The MIME type of this cache entry
         */
        public String getMimeType() {
            return mimeType;
        }

        /**
         * Gets the value of the HTTP 'Location' header with which this cache
         * entry was received.
         * @return The HTTP 'Location' header for this cache entry
         */
        public String getLocation() {
            return location;
        }

        /**
         * Gets the encoding of this cache entry.
         * @return The encoding of this cache entry
         */
        public String getEncoding() {
            return encoding;
        }

        /**
         * Gets the value of the HTTP 'Content-Disposition' header with which
         * this cache entry was received.
         * @return The HTTP 'Content-Disposition' header for this cache entry
         *
         */
        public String getContentDisposition() {
            return contentdisposition;
        }

        /**
         * Gets the input stream to the content of this cache entry, to allow
         * content to be read. See
         * {@link CacheManager#getCacheFile CacheManager.getCacheFile(String, Map<String, String>)}.
         * @return An input stream to the content of this cache entry
         */
        public InputStream getInputStream() {
            return inStream;
        }

        /**
         * Gets an output stream to the content of this cache entry, to allow
         * content to be written. See
         * {@link CacheManager#saveCacheFile CacheManager.saveCacheFile(String, CacheResult)}.
         * @return An output stream to the content of this cache entry
         */
        // Note that this is always null for objects returned by getCacheFile()!
        public OutputStream getOutputStream() {
            return outStream;
        }


        /**
         * Sets an input stream to the content of this cache entry.
         * @param stream An input stream to the content of this cache entry
         */
        public void setInputStream(InputStream stream) {
            this.inStream = stream;
        }

        /**
         * Sets the encoding of this cache entry.
         * @param encoding The encoding of this cache entry
         */
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
     * Initializes the HTTP cache. This method must be called before any
     * CacheManager methods are used. Note that this is called automatically
     * when a {@link WebView} is created.
     * @param context The application context
     */
    static void init(Context context) {
        if (JniUtil.useChromiumHttpStack()) {
            // This isn't actually where the real cache lives, but where we put files for the
            // purpose of getCacheFile().
            mBaseDir = new File(context.getCacheDir(), "webviewCacheChromiumStaging");
            if (!mBaseDir.exists()) {
                mBaseDir.mkdirs();
            }
            return;
        }

        mDataBase = WebViewDatabase.getInstance(context.getApplicationContext());
        mBaseDir = new File(context.getCacheDir(), "webviewCache");
        if (createCacheDirectory() && mClearCacheOnInit) {
            removeAllCacheFiles();
            mClearCacheOnInit = false;
        }
    }

    /**
     * Create the cache directory if it does not already exist.
     *
     * @return true if the cache directory didn't exist and was created.
     */
    static private boolean createCacheDirectory() {
        assert !JniUtil.useChromiumHttpStack();

        if (!mBaseDir.exists()) {
            if(!mBaseDir.mkdirs()) {
                Log.w(LOGTAG, "Unable to create webviewCache directory");
                return false;
            }
            FileUtils.setPermissions(
                    mBaseDir.toString(),
                    FileUtils.S_IRWXU | FileUtils.S_IRWXG,
                    -1, -1);
            // If we did create the directory, we need to flush
            // the cache database. The directory could be recreated
            // because the system flushed all the data/cache directories
            // to free up disk space.
            // delete rows in the cache database
            WebViewWorker.getHandler().sendEmptyMessage(
                    WebViewWorker.MSG_CLEAR_CACHE);
            return true;
        }
        return false;
    }

    /**
     * Gets the base directory in which the files used to store the contents of
     * cache entries are placed. See
     * {@link CacheManager.CacheResult#getLocalPath CacheManager.CacheResult.getLocalPath()}.
     * @return The base directory of the cache
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static File getCacheFileBaseDir() {
        return mBaseDir;
    }

    /**
     * Gets whether the HTTP cache is disabled.
     * @return True if the HTTP cache is disabled
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static boolean cacheDisabled() {
        return false;
    }

    // only called from WebViewWorkerThread
    // make sure to call enableTransaction/disableTransaction in pair
    static boolean enableTransaction() {
        assert !JniUtil.useChromiumHttpStack();

        if (++mRefCount == 1) {
            mDataBase.startCacheTransaction();
            return true;
        }
        return false;
    }

    // only called from WebViewWorkerThread
    // make sure to call enableTransaction/disableTransaction in pair
    static boolean disableTransaction() {
        assert !JniUtil.useChromiumHttpStack();

        if (--mRefCount == 0) {
            mDataBase.endCacheTransaction();
            return true;
        }
        return false;
    }

    // only called from WebViewWorkerThread
    // make sure to call startTransaction/endTransaction in pair
    static boolean startTransaction() {
        assert !JniUtil.useChromiumHttpStack();

        return mDataBase.startCacheTransaction();
    }

    // only called from WebViewWorkerThread
    // make sure to call startTransaction/endTransaction in pair
    static boolean endTransaction() {
        assert !JniUtil.useChromiumHttpStack();

        boolean ret = mDataBase.endCacheTransaction();
        if (++mTrimCacheCount >= TRIM_CACHE_INTERVAL) {
            mTrimCacheCount = 0;
            trimCacheIfNeeded();
        }
        return ret;
    }

    /**
     * Starts a cache transaction. Returns true if this is the only running
     * transaction. Otherwise, this transaction is nested inside currently
     * running transactions and false is returned.
     * @return True if this is the only running transaction
     * @deprecated This method no longer has any effect and always returns false
     */
    @Deprecated
    public static boolean startCacheTransaction() {
        return false;
    }

    /**
     * Ends the innermost cache transaction and returns whether this was the
     * only running transaction.
     * @return True if this was the only running transaction
     * @deprecated This method no longer has any effect and always returns false
     */
    @Deprecated
    public static boolean endCacheTransaction() {
        return false;
    }

    /**
     * Gets the cache entry for the specified URL, or null if none is found.
     * If a non-null value is provided for the HTTP headers map, and the cache
     * entry needs validation, appropriate headers will be added to the map.
     * The input stream of the CacheEntry object should be closed by the caller
     * when access to the underlying file is no longer required.
     * @param url The URL for which a cache entry is requested
     * @param headers A map from HTTP header name to value, to be populated
     *                for the returned cache entry
     * @return The cache entry for the specified URL
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static CacheResult getCacheFile(String url,
            Map<String, String> headers) {
        return getCacheFile(url, 0, headers);
    }

    private static CacheResult getCacheFileChromiumHttpStack(String url) {
        assert JniUtil.useChromiumHttpStack();

        CacheResult result = nativeGetCacheResult(url);
        if (result == null) {
            return null;
        }
        // A temporary local file will have been created native side and localPath set
        // appropriately.
        File src = new File(mBaseDir, result.localPath);
        try {
            // Open the file here so that even if it is deleted, the content
            // is still readable by the caller until close() is called.
            result.inStream = new FileInputStream(src);
        } catch (FileNotFoundException e) {
            Log.v(LOGTAG, "getCacheFile(): Failed to open file: " + e);
            // TODO: The files in the cache directory can be removed by the
            // system. If it is gone, what should we do?
            return null;
        }
        return result;
    }

    private static CacheResult getCacheFileAndroidHttpStack(String url,
            long postIdentifier) {
        assert !JniUtil.useChromiumHttpStack();

        String databaseKey = getDatabaseKey(url, postIdentifier);
        CacheResult result = mDataBase.getCache(databaseKey);
        if (result == null) {
            return null;
        }
        if (result.contentLength == 0) {
            if (!isCachableRedirect(result.httpStatusCode)) {
                // This should not happen. If it does, remove it.
                mDataBase.removeCache(databaseKey);
                return null;
            }
        } else {
            File src = new File(mBaseDir, result.localPath);
            try {
                // Open the file here so that even if it is deleted, the content
                // is still readable by the caller until close() is called.
                result.inStream = new FileInputStream(src);
            } catch (FileNotFoundException e) {
                // The files in the cache directory can be removed by the
                // system. If it is gone, clean up the database.
                mDataBase.removeCache(databaseKey);
                return null;
            }
        }
        return result;
    }

    static CacheResult getCacheFile(String url, long postIdentifier,
            Map<String, String> headers) {
        CacheResult result = JniUtil.useChromiumHttpStack() ?
                getCacheFileChromiumHttpStack(url) :
                getCacheFileAndroidHttpStack(url, postIdentifier);

        if (result == null) {
            return null;
        }

        // A null value for headers is used by CACHE_MODE_CACHE_ONLY to imply
        // that we should provide the cache result even if it is expired.
        // Note that a negative expires value means a time in the far future.
        if (headers != null && result.expires >= 0
                && result.expires <= System.currentTimeMillis()) {
            if (result.lastModified == null && result.etag == null) {
                return null;
            }
            // Return HEADER_KEY_IFNONEMATCH or HEADER_KEY_IFMODIFIEDSINCE
            // for requesting validation.
            if (result.etag != null) {
                headers.put(HEADER_KEY_IFNONEMATCH, result.etag);
            }
            if (result.lastModified != null) {
                headers.put(HEADER_KEY_IFMODIFIEDSINCE, result.lastModified);
            }
        }

        if (DebugFlags.CACHE_MANAGER) {
            Log.v(LOGTAG, "getCacheFile for url " + url);
        }

        return result;
    }

    /**
     * Given a url and its full headers, returns CacheResult if a local cache
     * can be stored. Otherwise returns null. The mimetype is passed in so that
     * the function can use the mimetype that will be passed to WebCore which
     * could be different from the mimetype defined in the headers.
     * forceCache is for out-of-package callers to force creation of a
     * CacheResult, and is used to supply surrogate responses for URL
     * interception.
     * @return CacheResult for a given url
     */
    static CacheResult createCacheFile(String url, int statusCode,
            Headers headers, String mimeType, boolean forceCache) {
        if (JniUtil.useChromiumHttpStack()) {
            // This method is public but hidden. We break functionality.
            return null;
        }

        return createCacheFile(url, statusCode, headers, mimeType, 0,
                forceCache);
    }

    static CacheResult createCacheFile(String url, int statusCode,
            Headers headers, String mimeType, long postIdentifier,
            boolean forceCache) {
        assert !JniUtil.useChromiumHttpStack();

        String databaseKey = getDatabaseKey(url, postIdentifier);

        // according to the rfc 2616, the 303 response MUST NOT be cached.
        if (statusCode == 303) {
            // remove the saved cache if there is any
            mDataBase.removeCache(databaseKey);
            return null;
        }

        // like the other browsers, do not cache redirects containing a cookie
        // header.
        if (isCachableRedirect(statusCode) && !headers.getSetCookie().isEmpty()) {
            // remove the saved cache if there is any
            mDataBase.removeCache(databaseKey);
            return null;
        }

        CacheResult ret = parseHeaders(statusCode, headers, mimeType);
        if (ret == null) {
            // this should only happen if the headers has "no-store" in the
            // cache-control. remove the saved cache if there is any
            mDataBase.removeCache(databaseKey);
        } else {
            setupFiles(databaseKey, ret);
            try {
                ret.outStream = new FileOutputStream(ret.outFile);
            } catch (FileNotFoundException e) {
                // This can happen with the system did a purge and our
                // subdirectory has gone, so lets try to create it again
                if (createCacheDirectory()) {
                    try {
                        ret.outStream = new FileOutputStream(ret.outFile);
                    } catch  (FileNotFoundException e2) {
                        // We failed to create the file again, so there
                        // is something else wrong. Return null.
                        return null;
                    }
                } else {
                    // Failed to create cache directory
                    return null;
                }
            }
            ret.mimeType = mimeType;
        }

        return ret;
    }

    /**
     * Adds a cache entry to the HTTP cache for the specicifed URL. Also closes
     * the cache entry's output stream.
     * @param url The URL for which the cache entry should be added
     * @param cacheResult The cache entry to add
     * @deprecated Access to the HTTP cache will be removed in a future release.
     */
    @Deprecated
    public static void saveCacheFile(String url, CacheResult cacheResult) {
        saveCacheFile(url, 0, cacheResult);
    }

    static void saveCacheFile(String url, long postIdentifier,
            CacheResult cacheRet) {
        try {
            cacheRet.outStream.close();
        } catch (IOException e) {
            return;
        }

        if (JniUtil.useChromiumHttpStack()) {
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
            // This method is not used from within this package with the
            // Chromium HTTP stack, and for public API use, we should already
            // have thrown an exception above.
            assert false;
            return;
        }

        if (!cacheRet.outFile.exists()) {
            // the file in the cache directory can be removed by the system
            return;
        }

        boolean redirect = isCachableRedirect(cacheRet.httpStatusCode);
        if (redirect) {
            // location is in database, no need to keep the file
            cacheRet.contentLength = 0;
            cacheRet.localPath = "";
        }
        if ((redirect || cacheRet.contentLength == 0)
                && !cacheRet.outFile.delete()) {
            Log.e(LOGTAG, cacheRet.outFile.getPath() + " delete failed.");
        }
        if (cacheRet.contentLength == 0) {
            return;
        }

        mDataBase.addCache(getDatabaseKey(url, postIdentifier), cacheRet);

        if (DebugFlags.CACHE_MANAGER) {
            Log.v(LOGTAG, "saveCacheFile for url " + url);
        }
    }

    static boolean cleanupCacheFile(CacheResult cacheRet) {
        assert !JniUtil.useChromiumHttpStack();

        try {
            cacheRet.outStream.close();
        } catch (IOException e) {
            return false;
        }
        return cacheRet.outFile.delete();
    }

    /**
     * Remove all cache files.
     *
     * @return Whether the removal succeeded.
     */
    static boolean removeAllCacheFiles() {
        // Note, this is called before init() when the database is
        // created or upgraded.
        if (mBaseDir == null) {
            // This method should not be called before init() when using the
            // chrome http stack
            assert !JniUtil.useChromiumHttpStack();
            // Init() has not been called yet, so just flag that
            // we need to clear the cache when init() is called.
            mClearCacheOnInit = true;
            return true;
        }
        // delete rows in the cache database
        if (!JniUtil.useChromiumHttpStack())
            WebViewWorker.getHandler().sendEmptyMessage(WebViewWorker.MSG_CLEAR_CACHE);

        // delete cache files in a separate thread to not block UI.
        final Runnable clearCache = new Runnable() {
            public void run() {
                // delete all cache files
                try {
                    String[] files = mBaseDir.list();
                    // if mBaseDir doesn't exist, files can be null.
                    if (files != null) {
                        for (int i = 0; i < files.length; i++) {
                            File f = new File(mBaseDir, files[i]);
                            if (!f.delete()) {
                                Log.e(LOGTAG, f.getPath() + " delete failed.");
                            }
                        }
                    }
                } catch (SecurityException e) {
                    // Ignore SecurityExceptions.
                }
            }
        };
        new Thread(clearCache).start();
        return true;
    }

    static void trimCacheIfNeeded() {
        assert !JniUtil.useChromiumHttpStack();

        if (mDataBase.getCacheTotalSize() > CACHE_THRESHOLD) {
            List<String> pathList = mDataBase.trimCache(CACHE_TRIM_AMOUNT);
            int size = pathList.size();
            for (int i = 0; i < size; i++) {
                File f = new File(mBaseDir, pathList.get(i));
                if (!f.delete()) {
                    Log.e(LOGTAG, f.getPath() + " delete failed.");
                }
            }
            // remove the unreferenced files in the cache directory
            final List<String> fileList = mDataBase.getAllCacheFileNames();
            if (fileList == null) return;
            String[] toDelete = mBaseDir.list(new FilenameFilter() {
                public boolean accept(File dir, String filename) {
                    if (fileList.contains(filename)) {
                        return false;
                    } else {
                        return true;
                    }
                }
            });
            if (toDelete == null) return;
            size = toDelete.length;
            for (int i = 0; i < size; i++) {
                File f = new File(mBaseDir, toDelete[i]);
                if (!f.delete()) {
                    Log.e(LOGTAG, f.getPath() + " delete failed.");
                }
            }
        }
    }

    static void clearCache() {
        assert !JniUtil.useChromiumHttpStack();

        // delete database
        mDataBase.clearCache();
    }

    private static boolean isCachableRedirect(int statusCode) {
        if (statusCode == 301 || statusCode == 302 || statusCode == 307) {
            // as 303 can't be cached, we do not return true
            return true;
        } else {
            return false;
        }
    }

    private static String getDatabaseKey(String url, long postIdentifier) {
        assert !JniUtil.useChromiumHttpStack();

        if (postIdentifier == 0) return url;
        return postIdentifier + url;
    }

    @SuppressWarnings("deprecation")
    private static void setupFiles(String url, CacheResult cacheRet) {
        assert !JniUtil.useChromiumHttpStack();

        if (true) {
            // Note: SHA1 is much stronger hash. But the cost of setupFiles() is
            // 3.2% cpu time for a fresh load of nytimes.com. While a simple
            // String.hashCode() is only 0.6%. If adding the collision resolving
            // to String.hashCode(), it makes the cpu time to be 1.6% for a 
            // fresh load, but 5.3% for the worst case where all the files 
            // already exist in the file system, but database is gone. So it
            // needs to resolve collision for every file at least once.
            int hashCode = url.hashCode();
            StringBuffer ret = new StringBuffer(8);
            appendAsHex(hashCode, ret);
            String path = ret.toString();
            File file = new File(mBaseDir, path);
            if (true) {
                boolean checkOldPath = true;
                // Check hash collision. If the hash file doesn't exist, just
                // continue. There is a chance that the old cache file is not
                // same as the hash file. As mDataBase.getCache() is more 
                // expansive than "leak" a file until clear cache, don't bother.
                // If the hash file exists, make sure that it is same as the 
                // cache file. If it is not, resolve the collision.
                while (file.exists()) {
                    if (checkOldPath) {
                        CacheResult oldResult = mDataBase.getCache(url);
                        if (oldResult != null && oldResult.contentLength > 0) {
                            if (path.equals(oldResult.localPath)) {
                                path = oldResult.localPath;
                            } else {
                                path = oldResult.localPath;
                                file = new File(mBaseDir, path);
                            }
                            break;
                        }
                        checkOldPath = false;
                    }
                    ret = new StringBuffer(8);
                    appendAsHex(++hashCode, ret);
                    path = ret.toString();
                    file = new File(mBaseDir, path);
                }
            }
            cacheRet.localPath = path;
            cacheRet.outFile = file;
        } else {
            // get hash in byte[]
            Digest digest = new SHA1Digest();
            int digestLen = digest.getDigestSize();
            byte[] hash = new byte[digestLen];
            int urlLen = url.length();
            byte[] data = new byte[urlLen];
            url.getBytes(0, urlLen, data, 0);
            digest.update(data, 0, urlLen);
            digest.doFinal(hash, 0);
            // convert byte[] to hex String
            StringBuffer result = new StringBuffer(2 * digestLen);
            for (int i = 0; i < digestLen; i = i + 4) {
                int h = (0x00ff & hash[i]) << 24 | (0x00ff & hash[i + 1]) << 16
                        | (0x00ff & hash[i + 2]) << 8 | (0x00ff & hash[i + 3]);
                appendAsHex(h, result);
            }
            cacheRet.localPath = result.toString();
            cacheRet.outFile = new File(mBaseDir, cacheRet.localPath);
        }
    }

    private static void appendAsHex(int i, StringBuffer ret) {
        assert !JniUtil.useChromiumHttpStack();

        String hex = Integer.toHexString(i);
        switch (hex.length()) {
            case 1:
                ret.append("0000000");
                break;
            case 2:
                ret.append("000000");
                break;
            case 3:
                ret.append("00000");
                break;
            case 4:
                ret.append("0000");
                break;
            case 5:
                ret.append("000");
                break;
            case 6:
                ret.append("00");
                break;
            case 7:
                ret.append("0");
                break;
        }
        ret.append(hex);
    }

    private static CacheResult parseHeaders(int statusCode, Headers headers,
            String mimeType) {
        assert !JniUtil.useChromiumHttpStack();

        // if the contentLength is already larger than CACHE_MAX_SIZE, skip it
        if (headers.getContentLength() > CACHE_MAX_SIZE) return null;

        // The HTML 5 spec, section 6.9.4, step 7.3 of the application cache
        // process states that HTTP caching rules are ignored for the
        // purposes of the application cache download process.
        // At this point we can't tell that if a file is part of this process,
        // except for the manifest, which has its own mimeType.
        // TODO: work out a way to distinguish all responses that are part of
        // the application download process and skip them.
        if (MANIFEST_MIME.equals(mimeType)) return null;

        // TODO: if authenticated or secure, return null
        CacheResult ret = new CacheResult();
        ret.httpStatusCode = statusCode;

        ret.location = headers.getLocation();

        ret.expires = -1;
        ret.expiresString = headers.getExpires();
        if (ret.expiresString != null) {
            try {
                ret.expires = AndroidHttpClient.parseDate(ret.expiresString);
            } catch (IllegalArgumentException ex) {
                // Take care of the special "-1" and "0" cases
                if ("-1".equals(ret.expiresString)
                        || "0".equals(ret.expiresString)) {
                    // make it expired, but can be used for history navigation
                    ret.expires = 0;
                } else {
                    Log.e(LOGTAG, "illegal expires: " + ret.expiresString);
                }
            }
        }

        ret.contentdisposition = headers.getContentDisposition();

        ret.crossDomain = headers.getXPermittedCrossDomainPolicies();

        // lastModified and etag may be set back to http header. So they can't
        // be empty string.
        String lastModified = headers.getLastModified();
        if (lastModified != null && lastModified.length() > 0) {
            ret.lastModified = lastModified;
        }

        String etag = headers.getEtag();
        if (etag != null && etag.length() > 0) {
            ret.etag = etag;
        }

        String cacheControl = headers.getCacheControl();
        if (cacheControl != null) {
            String[] controls = cacheControl.toLowerCase().split("[ ,;]");
            boolean noCache = false;
            for (int i = 0; i < controls.length; i++) {
                if (NO_STORE.equals(controls[i])) {
                    return null;
                }
                // According to the spec, 'no-cache' means that the content
                // must be re-validated on every load. It does not mean that
                // the content can not be cached. set to expire 0 means it
                // can only be used in CACHE_MODE_CACHE_ONLY case
                if (NO_CACHE.equals(controls[i])) {
                    ret.expires = 0;
                    noCache = true;
                // if cache control = no-cache has been received, ignore max-age
                // header, according to http spec:
                // If a request includes the no-cache directive, it SHOULD NOT
                // include min-fresh, max-stale, or max-age.
                } else if (controls[i].startsWith(MAX_AGE) && !noCache) {
                    int separator = controls[i].indexOf('=');
                    if (separator < 0) {
                        separator = controls[i].indexOf(':');
                    }
                    if (separator > 0) {
                        String s = controls[i].substring(separator + 1);
                        try {
                            long sec = Long.parseLong(s);
                            if (sec >= 0) {
                                ret.expires = System.currentTimeMillis() + 1000
                                        * sec;
                            }
                        } catch (NumberFormatException ex) {
                            if ("1d".equals(s)) {
                                // Take care of the special "1d" case
                                ret.expires = System.currentTimeMillis() + 86400000; // 24*60*60*1000
                            } else {
                                Log.e(LOGTAG, "exception in parseHeaders for "
                                        + "max-age:"
                                        + controls[i].substring(separator + 1));
                                ret.expires = 0;
                            }
                        }
                    }
                }
            }
        }

        // According to RFC 2616 section 14.32:
        // HTTP/1.1 caches SHOULD treat "Pragma: no-cache" as if the
        // client had sent "Cache-Control: no-cache"
        if (NO_CACHE.equals(headers.getPragma())) {
            ret.expires = 0;
        }

        // According to RFC 2616 section 13.2.4, if an expiration has not been
        // explicitly defined a heuristic to set an expiration may be used.
        if (ret.expires == -1) {
            if (ret.httpStatusCode == 301) {
                // If it is a permanent redirect, and it did not have an
                // explicit cache directive, then it never expires
                ret.expires = Long.MAX_VALUE;
            } else if (ret.httpStatusCode == 302 || ret.httpStatusCode == 307) {
                // If it is temporary redirect, expires
                ret.expires = 0;
            } else if (ret.lastModified == null) {
                // When we have no last-modified, then expire the content with
                // in 24hrs as, according to the RFC, longer time requires a
                // warning 113 to be added to the response.

                // Only add the default expiration for non-html markup. Some
                // sites like news.google.com have no cache directives.
                if (!mimeType.startsWith("text/html")) {
                    ret.expires = System.currentTimeMillis() + 86400000; // 24*60*60*1000
                } else {
                    // Setting a expires as zero will cache the result for
                    // forward/back nav.
                    ret.expires = 0;
                }
            } else {
                // If we have a last-modified value, we could use it to set the
                // expiration. Suggestion from RFC is 10% of time since
                // last-modified. As we are on mobile, loads are expensive,
                // increasing this to 20%.

                // 24 * 60 * 60 * 1000
                long lastmod = System.currentTimeMillis() + 86400000;
                try {
                    lastmod = AndroidHttpClient.parseDate(ret.lastModified);
                } catch (IllegalArgumentException ex) {
                    Log.e(LOGTAG, "illegal lastModified: " + ret.lastModified);
                }
                long difference = System.currentTimeMillis() - lastmod;
                if (difference > 0) {
                    ret.expires = System.currentTimeMillis() + difference / 5;
                } else {
                    // last modified is in the future, expire the content
                    // on the last modified
                    ret.expires = lastmod;
                }
            }
        }

        return ret;
    }

    private static native CacheResult nativeGetCacheResult(String url);
}
