/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.http;

import android.content.Context;
import com.android.okhttp.OkResponseCache;
import com.android.okhttp.ResponseSource;
import com.android.okhttp.internal.DiskLruCache;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.HttpURLConnection;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import libcore.io.IoUtils;
import org.apache.http.impl.client.DefaultHttpClient;

/**
 * Caches HTTP and HTTPS responses to the filesystem so they may be reused,
 * saving time and bandwidth. This class supports {@link HttpURLConnection} and
 * {@link HttpsURLConnection}; there is no platform-provided cache for {@link
 * DefaultHttpClient} or {@link AndroidHttpClient}.
 *
 * <h3>Installing an HTTP response cache</h3>
 * Enable caching of all of your application's HTTP requests by installing the
 * cache at application startup. For example, this code installs a 10 MiB cache
 * in the {@link Context#getCacheDir() application-specific cache directory} of
 * the filesystem}: <pre>   {@code
 *   protected void onCreate(Bundle savedInstanceState) {
 *       ...
 *
 *       try {
 *           File httpCacheDir = new File(context.getCacheDir(), "http");
 *           long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
 *           HttpResponseCache.install(httpCacheDir, httpCacheSize);
 *       } catch (IOException e) {
 *           Log.i(TAG, "HTTP response cache installation failed:" + e);
 *       }
 *   }
 *
 *   protected void onStop() {
 *       ...
 *
 *       HttpResponseCache cache = HttpResponseCache.getInstalled();
 *       if (cache != null) {
 *           cache.flush();
 *       }
 *   }}</pre>
 * This cache will evict entries as necessary to keep its size from exceeding
 * 10 MiB. The best cache size is application specific and depends on the size
 * and frequency of the files being downloaded. Increasing the limit may improve
 * the hit rate, but it may also just waste filesystem space!
 *
 * <p>For some applications it may be preferable to create the cache in the
 * external storage directory. <strong>There are no access controls on the
 * external storage directory so it should not be used for caches that could
 * contain private data.</strong> Although it often has more free space,
 * external storage is optional and&#8212;even if available&#8212;can disappear
 * during use. Retrieve the external cache directory using {@link
 * Context#getExternalCacheDir()}. If this method returns null, your application
 * should fall back to either not caching or caching on non-external storage. If
 * the external storage is removed during use, the cache hit rate will drop to
 * zero and ongoing cache reads will fail.
 *
 * <p>Flushing the cache forces its data to the filesystem. This ensures that
 * all responses written to the cache will be readable the next time the
 * activity starts.
 *
 * <h3>Cache Optimization</h3>
 * To measure cache effectiveness, this class tracks three statistics:
 * <ul>
 *     <li><strong>{@link #getRequestCount() Request Count:}</strong> the number
 *         of HTTP requests issued since this cache was created.
 *     <li><strong>{@link #getNetworkCount() Network Count:}</strong> the
 *         number of those requests that required network use.
 *     <li><strong>{@link #getHitCount() Hit Count:}</strong> the number of
 *         those requests whose responses were served by the cache.
 * </ul>
 * Sometimes a request will result in a conditional cache hit. If the cache
 * contains a stale copy of the response, the client will issue a conditional
 * {@code GET}. The server will then send either the updated response if it has
 * changed, or a short 'not modified' response if the client's copy is still
 * valid. Such responses increment both the network count and hit count.
 *
 * <p>The best way to improve the cache hit rate is by configuring the web
 * server to return cacheable responses. Although this client honors all <a
 * href="http://www.ietf.org/rfc/rfc2616.txt">HTTP/1.1 (RFC 2068)</a> cache
 * headers, it doesn't cache partial responses.
 *
 * <h3>Force a Network Response</h3>
 * In some situations, such as after a user clicks a 'refresh' button, it may be
 * necessary to skip the cache, and fetch data directly from the server. To force
 * a full refresh, add the {@code no-cache} directive: <pre>   {@code
 *         connection.addRequestProperty("Cache-Control", "no-cache");
 * }</pre>
 * If it is only necessary to force a cached response to be validated by the
 * server, use the more efficient {@code max-age=0} instead: <pre>   {@code
 *         connection.addRequestProperty("Cache-Control", "max-age=0");
 * }</pre>
 *
 * <h3>Force a Cache Response</h3>
 * Sometimes you'll want to show resources if they are available immediately,
 * but not otherwise. This can be used so your application can show
 * <i>something</i> while waiting for the latest data to be downloaded. To
 * restrict a request to locally-cached resources, add the {@code
 * only-if-cached} directive: <pre>   {@code
 *     try {
 *         connection.addRequestProperty("Cache-Control", "only-if-cached");
 *         InputStream cached = connection.getInputStream();
 *         // the resource was cached! show it
 *     } catch (FileNotFoundException e) {
 *         // the resource was not cached
 *     }
 * }</pre>
 * This technique works even better in situations where a stale response is
 * better than no response. To permit stale cached responses, use the {@code
 * max-stale} directive with the maximum staleness in seconds: <pre>   {@code
 *         int maxStale = 60 * 60 * 24 * 28; // tolerate 4-weeks stale
 *         connection.addRequestProperty("Cache-Control", "max-stale=" + maxStale);
 * }</pre>
 *
 * <h3>Working With Earlier Releases</h3>
 * This class was added in Android 4.0 (Ice Cream Sandwich). Use reflection to
 * enable the response cache without impacting earlier releases: <pre>   {@code
 *       try {
 *           File httpCacheDir = new File(context.getCacheDir(), "http");
 *           long httpCacheSize = 10 * 1024 * 1024; // 10 MiB
 *           Class.forName("android.net.http.HttpResponseCache")
 *                   .getMethod("install", File.class, long.class)
 *                   .invoke(null, httpCacheDir, httpCacheSize);
 *       } catch (Exception httpResponseCacheNotAvailable) {
 *       }}</pre>
 */
public final class HttpResponseCache extends ResponseCache implements Closeable {

    private final com.android.okhttp.HttpResponseCache delegate;

    private HttpResponseCache(com.android.okhttp.HttpResponseCache delegate) {
        this.delegate = delegate;
    }

    /**
     * Returns the currently-installed {@code HttpResponseCache}, or null if
     * there is no cache installed or it is not a {@code HttpResponseCache}.
     */
    public static HttpResponseCache getInstalled() {
        ResponseCache installed = ResponseCache.getDefault();
        if (installed instanceof com.android.okhttp.HttpResponseCache) {
            return new HttpResponseCache(
                    (com.android.okhttp.HttpResponseCache) installed);
        }

        return null;
    }

    /**
     * Creates a new HTTP response cache and {@link ResponseCache#setDefault
     * sets it} as the system default cache.
     *
     * @param directory the directory to hold cache data.
     * @param maxSize the maximum size of the cache in bytes.
     * @return the newly-installed cache
     * @throws IOException if {@code directory} cannot be used for this cache.
     *     Most applications should respond to this exception by logging a
     *     warning.
     */
    public static HttpResponseCache install(File directory, long maxSize) throws IOException {
        ResponseCache installed = ResponseCache.getDefault();
        if (installed instanceof com.android.okhttp.HttpResponseCache) {
            com.android.okhttp.HttpResponseCache installedCache =
                    (com.android.okhttp.HttpResponseCache) installed;
            // don't close and reopen if an equivalent cache is already installed
            if (installedCache.getDirectory().equals(directory)
                    && installedCache.getMaxSize() == maxSize
                    && !installedCache.isClosed()) {
                return new HttpResponseCache(installedCache);
            } else {
                // The HttpResponseCache that owns this object is about to be replaced.
                installedCache.close();
            }
        }

        com.android.okhttp.HttpResponseCache responseCache =
                new com.android.okhttp.HttpResponseCache(directory, maxSize);
        ResponseCache.setDefault(responseCache);
        return new HttpResponseCache(responseCache);
    }

    @Override public CacheResponse get(URI uri, String requestMethod,
            Map<String, List<String>> requestHeaders) throws IOException {
        return delegate.get(uri, requestMethod, requestHeaders);
    }

    @Override public CacheRequest put(URI uri, URLConnection urlConnection) throws IOException {
        return delegate.put(uri, urlConnection);
    }

    /**
     * Returns the number of bytes currently being used to store the values in
     * this cache. This may be greater than the {@link #maxSize} if a background
     * deletion is pending.
     */
    public long size() {
        return delegate.getSize();
    }

    /**
     * Returns the maximum number of bytes that this cache should use to store
     * its data.
     */
    public long maxSize() {
        return delegate.getMaxSize();
    }

    /**
     * Force buffered operations to the filesystem. This ensures that responses
     * written to the cache will be available the next time the cache is opened,
     * even if this process is killed.
     */
    public void flush() {
        try {
            delegate.flush();
        } catch (IOException ignored) {
        }
    }

    /**
     * Returns the number of HTTP requests that required the network to either
     * supply a response or validate a locally cached response.
     */
    public int getNetworkCount() {
        return delegate.getNetworkCount();
    }

    /**
     * Returns the number of HTTP requests whose response was provided by the
     * cache. This may include conditional {@code GET} requests that were
     * validated over the network.
     */
    public int getHitCount() {
        return delegate.getHitCount();
    }

    /**
     * Returns the total number of HTTP requests that were made. This includes
     * both client requests and requests that were made on the client's behalf
     * to handle a redirects and retries.
     */
    public int getRequestCount() {
        return delegate.getRequestCount();
    }

    /**
     * Uninstalls the cache and releases any active resources. Stored contents
     * will remain on the filesystem.
     */
    @Override public void close() throws IOException {
        if (ResponseCache.getDefault() == this.delegate) {
            ResponseCache.setDefault(null);
        }
        delegate.close();
    }

    /**
     * Uninstalls the cache and deletes all of its stored contents.
     */
    public void delete() throws IOException {
        if (ResponseCache.getDefault() == this.delegate) {
            ResponseCache.setDefault(null);
        }
        delegate.delete();
    }
}
