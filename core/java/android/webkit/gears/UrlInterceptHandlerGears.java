// Copyright 2008, The Android Open Source Project
//
// Redistribution and use in source and binary forms, with or without 
// modification, are permitted provided that the following conditions are met:
//
//  1. Redistributions of source code must retain the above copyright notice, 
//     this list of conditions and the following disclaimer.
//  2. Redistributions in binary form must reproduce the above copyright notice,
//     this list of conditions and the following disclaimer in the documentation
//     and/or other materials provided with the distribution.
//  3. Neither the name of Google Inc. nor the names of its contributors may be
//     used to endorse or promote products derived from this software without
//     specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
// WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF 
// MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, 
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
// PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
// OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
// WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR 
// OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package android.webkit.gears;

import android.net.http.Headers;
import android.util.Log;
import android.webkit.CacheManager;
import android.webkit.CacheManager.CacheResult;
import android.webkit.Plugin;
import android.webkit.UrlInterceptRegistry;
import android.webkit.UrlInterceptHandler;
import android.webkit.WebView;

import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.util.CharArrayBuffer;

import java.io.*;
import java.util.*;

/**
 * Services requests to handle URLs coming from the browser or
 * HttpRequestAndroid. This registers itself with the
 * UrlInterceptRegister in Android so we get a chance to service all
 * URLs passing through the browser before anything else.
 */
public class UrlInterceptHandlerGears implements UrlInterceptHandler {
  /** Singleton instance. */
  private static UrlInterceptHandlerGears instance;
  /** Debug logging tag. */
  private static final String LOG_TAG = "Gears-J";
  /** Buffer size for reading/writing streams. */
  private static final int BUFFER_SIZE = 4096;
  /**
   * Number of milliseconds to expire LocalServer temporary entries in
   * the browser's cache. Somewhat arbitrarily chosen as a compromise
   * between being a) long enough not to expire during page load and
   * b) short enough to evict entries during a session. */
  private static final int CACHE_EXPIRY_MS = 60000; // 1 minute.
  /** Enable/disable all logging in this class. */
  private static boolean logEnabled = false;
  /** The unmodified (case-sensitive) key in the headers map is the
   * same index as used by HttpRequestAndroid. */
  public static final int HEADERS_MAP_INDEX_KEY =
      HttpRequestAndroid.HEADERS_MAP_INDEX_KEY;
  /** The associated value in the headers map is the same index as
   * used by HttpRequestAndroid. */
  public static final int HEADERS_MAP_INDEX_VALUE =
      HttpRequestAndroid.HEADERS_MAP_INDEX_VALUE;

  /**
   * Object passed to the native side, containing information about
   * the URL to service.
   */
  public static class ServiceRequest {
    // The URL being requested.
    private String url;
    // Request headers. Map of lowercase key to [ unmodified key, value ].
    private Map<String, String[]> requestHeaders;

    /**
     * Initialize members on construction.
     * @param url The URL being requested.
     * @param requestHeaders Headers associated with the request,
     *                       or null if none.
     *                       Map of lowercase key to [ unmodified key, value ].
     */
    public ServiceRequest(String url, Map<String, String[]> requestHeaders) {
      this.url = url;
      this.requestHeaders = requestHeaders;
    }

    /**
     * Returns the URL being requested.
     * @return The URL being requested.
     */
    public String getUrl() {
      return url;
    }

    /**
     * Get the value associated with a request header key, if any.
     * @param header The key to find, case insensitive.
     * @return The value associated with this header, or null if not found.
     */
    public String getRequestHeader(String header) {
      if (requestHeaders != null) {
        String[] value = requestHeaders.get(header.toLowerCase());
        if (value != null) {
          return value[HEADERS_MAP_INDEX_VALUE];
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
  }

  /**
   * Object returned by the native side, containing information needed
   * to pass the entire response back to the browser or
   * HttpRequestAndroid. Works from either an in-memory array or a
   * file on disk.
   */
  public class ServiceResponse {
    // The response status code, e.g 200.
    private int statusCode;
    // The full status line, e.g "HTTP/1.1 200 OK".
    private String statusLine;
    // All headers associated with the response. Map of lowercase key
    // to [ unmodified key, value ].
    private Map<String, String[]> responseHeaders =
        new HashMap<String, String[]>();
    // The MIME type, e.g "text/html".
    private String mimeType;
    // The encoding, e.g "utf-8", or null if none.
    private String encoding;
    // The stream which contains the body when read().
    private InputStream inputStream;

    /**
     * Initialize members using an in-memory array to return the body.
     * @param statusCode The response status code, e.g 200.
     * @param statusLine The full status line, e.g "HTTP/1.1 200 OK".
     * @param mimeType The MIME type, e.g "text/html".
     * @param encoding Encoding, e.g "utf-8" or null if none.
     * @param body The response body as a byte array, non-empty.
     */
    void setResultArray(
        int statusCode,
        String statusLine,
        String mimeType,
        String encoding,
        byte[] body) {
      this.statusCode = statusCode;
      this.statusLine = statusLine;
      this.mimeType = mimeType;
      this.encoding = encoding;
      // Setup a stream to read out of the byte array.
      this.inputStream = new ByteArrayInputStream(body);
    }
    
    /**
     * Initialize members using a file on disk to return the body.
     * @param statusCode The response status code, e.g 200.
     * @param statusLine The full status line, e.g "HTTP/1.1 200 OK".
     * @param mimeType The MIME type, e.g "text/html".
     * @param encoding Encoding, e.g "utf-8" or null if none.
     * @param path Full path to the file containing the body.
     * @return True if the file is successfully setup to stream,
     *         false on error such as file not found.
     */
    boolean setResultFile(
        int statusCode,
        String statusLine,
        String mimeType,
        String encoding,
        String path) {
      this.statusCode = statusCode;
      this.statusLine = statusLine;
      this.mimeType = mimeType;
      this.encoding = encoding;
      try {
        // Setup a stream to read out of a file on disk.
        this.inputStream = new FileInputStream(new File(path));
        return true;
      } catch (java.io.FileNotFoundException ex) {
        log("File not found: " + path);
        return false;
      }
    }
    
    /**
     * Set a response header, adding its settings to the header members.
     * @param key   The case sensitive key for the response header,
     *              e.g "Set-Cookie".
     * @param value The value associated with this key, e.g "cookie1234".
     */
    public void setResponseHeader(String key, String value) {
      // The map value contains the unmodified key (not lowercase).
      String[] mapValue = { key, value };
      responseHeaders.put(key.toLowerCase(), mapValue);
    }

    /**
     * Return the "Content-Type" header possibly supplied by a
     * previous setResponseHeader().
     * @return The "Content-Type" value, or null if not present.
     */
    public String getContentType() {
      // The map keys are lowercase.
      String[] value = responseHeaders.get("content-type");
      if (value != null) {
        return value[HEADERS_MAP_INDEX_VALUE];
      } else {
        return null;
      }
    }

    /**
     * Returns the HTTP status code for the response, supplied in
     * setResultArray() or setResultFile().
     * @return The HTTP statue code, e.g 200.
     */
    public int getStatusCode() {
      return statusCode;
    }
    
    /**
     * Returns the full HTTP status line for the response, supplied in
     * setResultArray() or setResultFile().
     * @return The HTTP statue line, e.g "HTTP/1.1 200 OK".
     */
    public String getStatusLine() {
      return statusLine;
    }
    
    /**
     * Get all response headers supplied in calls in
     * setResponseHeader().
     * @return A Map<String, String[]> containing all headers.
     */
    public Map<String, String[]> getResponseHeaders() {
      return responseHeaders;
    }

    /**
     * Returns the MIME type for the response, supplied in
     * setResultArray() or setResultFile().
     * @return The MIME type, e.g "text/html".
     */
    public String getMimeType() {
      return mimeType;
    }
    
    /**
     * Returns the encoding for the response, supplied in
     * setResultArray() or setResultFile(), or null if none.
     * @return The encoding, e.g "utf-8", or null if none.
     */
    public String getEncoding() {
      return encoding;
    }

    /**
     * Returns the InputStream setup by setResultArray() or
     * setResultFile() to allow reading data either from memory or
     * disk.
     * @return The InputStream containing the response body.
     */
    public InputStream getInputStream() {
      return inputStream;
    }
  }

  /**
   * Construct and initialize the singleton instance.
   */
  public UrlInterceptHandlerGears() {
    if (instance != null) {
      Log.e(LOG_TAG, "UrlInterceptHandlerGears singleton already constructed");
      throw new RuntimeException();
    }
    instance = this;
  }

  /**
   * Turn on/off logging in this class.
   * @param on Logging enable state.
   */
  public static void enableLogging(boolean on) {
    logEnabled = on;
  }

  /**
   * Get the singleton instance.
   * @return The singleton instance.
   */
  public static UrlInterceptHandlerGears getInstance() {
    return instance;
  }

  /**
   * Register the singleton instance with the browser's interception
   * mechanism.
   */
  public synchronized void register() {
    UrlInterceptRegistry.registerHandler(this);
  }

  /**
   * Unregister the singleton instance from the browser's interception
   * mechanism.
   */
  public synchronized void unregister() {
    UrlInterceptRegistry.unregisterHandler(this);
  }

  /**
   * Copy the entire InputStream to OutputStream.
   * @param inputStream The stream to read from.
   * @param outputStream The stream to write to.
   * @return True if the entire stream copied successfully, false on error.
   */
  private boolean copyStream(InputStream inputStream,
      OutputStream outputStream) {
    try {
      // Temporary buffer to copy stream through.
      byte[] buf = new byte[BUFFER_SIZE];
      for (;;) {
        // Read up to BUFFER_SIZE bytes.
        int bytes = inputStream.read(buf);
        if (bytes < 0) {
          break;
        }
        // Write the number of bytes we just read.
        outputStream.write(buf, 0, bytes);
      }
    } catch (IOException ex) {
      log("Got IOException copying stream: " + ex);
      return false;
    }
    return true;
  }

  /**
   * Given an URL, returns a CacheResult which contains the response
   * for the request. This implements the UrlInterceptHandler interface.
   *
   * @param url            The fully qualified URL being requested.
   * @param requestHeaders The request headers for this URL.
   * @return If a response can be crafted, a CacheResult initialized
   *         to return the surrogate response. If this URL cannot
   *         be serviced, returns null.
   */
  public CacheResult service(String url, Map<String, String> requestHeaders) {
    // Thankfully the browser does call us with case-sensitive
    // headers. We just need to map it case-insensitive.
    Map<String, String[]> lowercaseRequestHeaders =
        new HashMap<String, String[]>();
    Iterator<Map.Entry<String, String>> requestHeadersIt =
        requestHeaders.entrySet().iterator();
    while (requestHeadersIt.hasNext()) {
      Map.Entry<String, String> entry = requestHeadersIt.next();
      String key = entry.getKey();
      String mapValue[] = { key, entry.getValue() };
      lowercaseRequestHeaders.put(key.toLowerCase(), mapValue);
    }
    ServiceResponse response = getServiceResponse(url, lowercaseRequestHeaders);
    if (response == null) {
      // No result for this URL.
      return null;
    }
    // Translate the ServiceResponse to a CacheResult.
    // Translate http -> gears, https -> gearss, so we don't overwrite
    // existing entries.
    String gearsUrl = "gears" + url.substring("http".length());
    // Set the result to expire, so that entries don't pollute the
    // browser's cache for too long.
    long now_ms = System.currentTimeMillis();
    String expires = DateUtils.formatDate(new Date(now_ms + CACHE_EXPIRY_MS));
    response.setResponseHeader(HttpRequestAndroid.KEY_EXPIRES, expires);
    // The browser is only interested in a small subset of headers,
    // contained in a Headers object. Iterate the map of all headers
    // and add them to Headers.
    Headers headers = new Headers();
    Iterator<Map.Entry<String, String[]>> responseHeadersIt =
        response.getResponseHeaders().entrySet().iterator();
    while (responseHeadersIt.hasNext()) {
      Map.Entry<String, String[]> entry = responseHeadersIt.next();
      // Headers.parseHeader() expects lowercase keys.
      String keyValue = entry.getKey() + ": "
          + entry.getValue()[HEADERS_MAP_INDEX_VALUE];
      CharArrayBuffer buffer = new CharArrayBuffer(keyValue.length());
      buffer.append(keyValue);
      // Parse it into the header container.
      headers.parseHeader(buffer);
    }
    CacheResult cacheResult = CacheManager.createCacheFile(
        gearsUrl,
        response.getStatusCode(),
        headers,
        response.getMimeType(),
        true); // forceCache

    if (cacheResult == null) {
      // With the no-cache policy we could end up
      // with a null result
      return null;
    }

    // Set encoding if specified.
    String encoding = response.getEncoding();
    if (encoding != null) {
      cacheResult.setEncoding(encoding);
    }
    // Copy the response body to the CacheResult. This handles all
    // combinations of memory vs on-disk on both sides.
    InputStream inputStream = response.getInputStream();
    OutputStream outputStream = cacheResult.getOutputStream();
    boolean copied = copyStream(inputStream, outputStream);
    // Close the input and output streams to relinquish their
    // resources earlier.
    try {
      inputStream.close();
    } catch (IOException ex) {
      log("IOException closing InputStream: " + ex);
      copied = false;
    }
    try {
      outputStream.close();
    } catch (IOException ex) {
      log("IOException closing OutputStream: " + ex);
      copied = false;
    }
    if (!copied) {
      log("copyStream of local result failed");
      return null;
    }
    // Save the entry into the browser's cache.
    CacheManager.saveCacheFile(gearsUrl, cacheResult);
    // Get it back from the cache, this time properly initialized to
    // be used for input.
    cacheResult = CacheManager.getCacheFile(gearsUrl, null);
    if (cacheResult != null) {
      log("Returning surrogate result");
      return cacheResult;
    } else {
      // Not an expected condition, but handle gracefully. Perhaps out
      // of memory or disk?
      Log.e(LOG_TAG, "Lost CacheResult between save and get. Can't serve.\n");
      return null;
    }
  }

  /**
   * Given an URL, returns a CacheResult and headers which contain the
   * response for the request.
   *
   * @param url             The fully qualified URL being requested.
   * @param requestHeaders  The request headers for this URL.
   * @return If a response can be crafted, a ServiceResponse is
   *         created which contains all response headers and an InputStream
   *         attached to the body. If there is no response, null is returned.
   */
  public ServiceResponse getServiceResponse(String url,
      Map<String, String[]> requestHeaders) {
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
      // Don't know how to service non-HTTP URLs
      return null;
    }
    // Call the native handler to craft a response for this URL.
    return nativeService(new ServiceRequest(url, requestHeaders));
  }

  /**
   * Convenience debug function. Calls the Android logging
   * mechanism. logEnabled is not a constant, so if the string
   * evaluation is potentially expensive, the caller also needs to
   * check it.
   * @param str String to log to the Android console.
   */
  private void log(String str) {
    if (logEnabled) {
      Log.i(LOG_TAG, str);
    }
  }

  /**
   * Native method which handles the bulk of the request in LocalServer.
   * @param request A ServiceRequest object containing information about
   *                the request.
   * @return If serviced, a ServiceResponse object containing all the
   *         information to provide a response for the URL, or null
   *         if no response available for this URL.
   */
  private native static ServiceResponse nativeService(ServiceRequest request);
}
