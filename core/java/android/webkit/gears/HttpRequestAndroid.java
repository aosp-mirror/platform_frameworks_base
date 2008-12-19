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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.webkit.CacheManager;
import android.webkit.CacheManager.CacheResult;
import android.webkit.CookieManager;

import org.apache.http.conn.ssl.StrictHostnameVerifier;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.util.CharArrayBuffer;

import java.io.*;
import java.net.*;
import java.util.*;
import javax.net.ssl.*;

/**
 * Performs the underlying HTTP/HTTPS GET and POST requests.
 * <p> These are performed synchronously (blocking). The caller should
 * ensure that it is in a background thread if asynchronous behavior
 * is required. All data is pushed, so there is no need for JNI native
 * callbacks.
 * <p> This uses the java.net.HttpURLConnection class to perform most
 * of the underlying network activity. The Android brower's cache,
 * android.webkit.CacheManager, is also used when caching is enabled,
 * and updated with new data. The android.webkit.CookieManager is also
 * queried and updated as necessary.
 * <p> The public interface is designed to be called by native code
 * through JNI, and to simplify coding none of the public methods will
 * surface a checked exception. Unchecked exceptions may still be
 * raised but only if the system is in an ill state, such as out of
 * memory.
 * <p> TODO: This isn't plumbed into LocalServer yet. Mutually
 * dependent on LocalServer - will attach the two together once both
 * are submitted.
 */
public final class HttpRequestAndroid {
  /** Debug logging tag. */
  private static final String LOG_TAG = "Gears-J";
  /** HTTP response header line endings are CR-LF style. */
  private static final String HTTP_LINE_ENDING = "\r\n";
  /** Safe MIME type to use whenever it isn't specified. */
  private static final String DEFAULT_MIME_TYPE = "text/plain";
  /** Case-sensitive header keys */
  public static final String KEY_CONTENT_LENGTH = "Content-Length";
  public static final String KEY_EXPIRES = "Expires";
  public static final String KEY_LAST_MODIFIED = "Last-Modified";
  public static final String KEY_ETAG = "ETag";
  public static final String KEY_LOCATION = "Location";
  public static final String KEY_CONTENT_TYPE = "Content-Type";
  /** Number of bytes to send and receive on the HTTP connection in
   * one go. */
  private static final int BUFFER_SIZE = 4096;
  /** The first element of the String[] value in a headers map is the
   * unmodified (case-sensitive) key. */
  public static final int HEADERS_MAP_INDEX_KEY = 0;
  /** The second element of the String[] value in a headers map is the
   * associated value. */
  public static final int HEADERS_MAP_INDEX_VALUE = 1;

  /** Enable/disable all logging in this class. */
  private static boolean logEnabled = false;
  /** The underlying HTTP or HTTPS network connection. */
  private HttpURLConnection connection;
  /** HTTP body stream, setup after connection. */
  private InputStream inputStream;
  /** The complete response line e.g "HTTP/1.0 200 OK" */
  private String responseLine;
  /** Request headers, as a lowercase key -> [ unmodified key, value ] map. */
  private Map<String, String[]> requestHeaders =
      new HashMap<String, String[]>();
  /** Response headers, as a lowercase key -> [ unmodified key, value ] map. */
  private Map<String, String[]> responseHeaders;
  /** True if the child thread is in performing blocking IO. */
  private boolean inBlockingOperation = false;
  /** True when the thread acknowledges the abort. */
  private boolean abortReceived = false;
  /** The URL used for createCacheResult() */
  private String cacheResultUrl;
  /** CacheResult being saved into, if inserting a new cache entry. */
  private CacheResult cacheResult;
  /** Initialized by initChildThread(). Used to target abort(). */
  private Thread childThread;

  /**
   * Convenience debug function. Calls Android logging mechanism.
   * @param str String to log to the Android console.
   */
  private static void log(String str) {
    if (logEnabled) {
      Log.i(LOG_TAG, str);
    }
  }

  /**
   * Turn on/off logging in this class.
   * @param on Logging enable state.
   */
  public static void enableLogging(boolean on) {
    logEnabled = on;
  }

  /**
   * Initialize childThread using the TLS value of
   * Thread.currentThread(). Called on start up of the native child
   * thread.
   */
  public synchronized void initChildThread() {
    childThread = Thread.currentThread();
  }

  /**
   * Analagous to the native-side HttpRequest::open() function. This
   * initializes an underlying java.net.HttpURLConnection, but does
   * not go to the wire. On success, this enables a call to send() to
   * initiate the transaction.
   *
   * @param method    The HTTP method, e.g GET or POST.
   * @param url       The URL to open.
   * @return          True on success with a complete HTTP response.
   *                  False on failure.
   */
  public synchronized boolean open(String method, String url) {
    if (logEnabled)
      log("open " + method + " " + url);
    // Reset the response between calls to open().
    inputStream = null;
    responseLine = null;
    responseHeaders = null;
    if (!method.equals("GET") && !method.equals("POST")) {
      log("Method " + method + " not supported");
      return false;
    }
    // Setup the connection. This doesn't go to the wire yet - it
    // doesn't block.
    try {
      URL url_object = new URL(url);
      // Check that the protocol is indeed HTTP(S).
      String protocol = url_object.getProtocol();
      if (protocol == null) {
        log("null protocol for URL " + url);
        return false;
      }
      protocol = protocol.toLowerCase();
      if (!"http".equals(protocol) && !"https".equals(protocol)) {
        log("Url has wrong protocol: " + url);
        return false;
      }

      connection = (HttpURLConnection) url_object.openConnection();
      connection.setRequestMethod(method);
      // Manually follow redirects.
      connection.setInstanceFollowRedirects(false);
      // Manually cache.
      connection.setUseCaches(false);
      // Enable data output in POST method requests.
      connection.setDoOutput(method.equals("POST"));
      // Enable data input in non-HEAD method requests.
      // TODO: HEAD requests not tested.
      connection.setDoInput(!method.equals("HEAD"));
      if (connection instanceof javax.net.ssl.HttpsURLConnection) {
        // Verify the certificate matches the origin.
        ((HttpsURLConnection) connection).setHostnameVerifier(
            new StrictHostnameVerifier());
      }
      return true;
    } catch (IOException e) {
      log("Got IOException in open: " + e.toString());
      return false;
    }
  }

  /**
   * Interrupt a blocking IO operation. This will cause the child
   * thread to expediently return from an operation if it was stuck at
   * the time. Note that this inherently races, and unfortunately
   * requires the caller to loop.
   */
  public synchronized void interrupt() {
    if (childThread == null) {
      log("interrupt() called but no child thread");
      return;
    }
    synchronized (this) {
      if (inBlockingOperation) {
        log("Interrupting blocking operation");
        childThread.interrupt();
      } else {
        log("Nothing to interrupt");
      }
    }
  }

  /**
   * Set a header to send with the HTTP request. Will not take effect
   * on a transaction already in progress. The key is associated
   * case-insensitive, but stored case-sensitive.
   * @param name  The name of the header, e.g "Set-Cookie".
   * @param value The value for this header, e.g "text/html".
   */
  public synchronized void setRequestHeader(String name, String value) {
    String[] mapValue = { name, value };
    requestHeaders.put(name.toLowerCase(), mapValue);
  }

  /**
   * Returns the value associated with the given request header.
   * @param name The name of the request header, non-null, case-insensitive.
   * @return The value associated with the request header, or null if
   *         not set, or error.
   */
  public synchronized String getRequestHeader(String name) {
    String[] value = requestHeaders.get(name.toLowerCase());
    if (value != null) {
      return value[HEADERS_MAP_INDEX_VALUE];
    } else {
      return null;
    }
  }

  /**
   * Returns the value associated with the given response header.
   * @param name The name of the response header, non-null, case-insensitive.
   * @return The value associated with the response header, or null if
   *         not set or error.
   */
  public synchronized String getResponseHeader(String name) {
    if (responseHeaders != null) {
      String[] value = responseHeaders.get(name.toLowerCase());
      if (value != null) {
        return value[HEADERS_MAP_INDEX_VALUE];
      } else {
        return null;
      }
    } else {
      log("getResponseHeader() called but response not received");
      return null;
    } 
  }

  /**
   * Set a response header and associated value. The key is associated
   * case-insensitively, but stored case-sensitively.
   * @param name  Case sensitive request header key.
   * @param value The associated value.
   */
  private void setResponseHeader(String name, String value) {
    if (logEnabled)
      log("Set response header " + name + ": " + value);
    String mapValue[] = { name, value };
    responseHeaders.put(name.toLowerCase(), mapValue);
  }
  
  /**
   * Apply the contents of the Map requestHeaders to the connection
   * object. Calls to setRequestHeader() after this will not affect
   * the connection.
   */
  private synchronized void applyRequestHeadersToConnection() {
    Iterator<String[]> it = requestHeaders.values().iterator();
    while (it.hasNext()) {
      // Set the key case-sensitive.
      String[] entry = it.next();
      connection.setRequestProperty(
          entry[HEADERS_MAP_INDEX_KEY],
          entry[HEADERS_MAP_INDEX_VALUE]);
    }
  }

  /**
   * Return all response headers, separated by CR-LF line endings, and
   * ending with a trailing blank line. This mimics the format of the
   * raw response header up to but not including the body.
   * @return A string containing the entire response header.
   */
  public synchronized String getAllResponseHeaders() {
    if (responseHeaders == null) {
      log("getAllResponseHeaders() called but response not received");
      return null;
    }
    String result = new String();
    Iterator<String[]> it = responseHeaders.values().iterator();
    while (it.hasNext()) {
      String[] entry = it.next();
      // Output the "key: value" lines.
      result += entry[HEADERS_MAP_INDEX_KEY] + ": "
          + entry[HEADERS_MAP_INDEX_VALUE] + HTTP_LINE_ENDING;
    }
    result += HTTP_LINE_ENDING;
    return result;
  }

  /**
   * Get the complete response line of the HTTP request. Only valid on
   * completion of the transaction.
   * @return The complete HTTP response line, e.g "HTTP/1.0 200 OK".
   */
  public synchronized String getResponseLine() {
    return responseLine;
  }

  /**
   * Get the cookie for the given URL.
   * @param url The fully qualified URL.
   * @return A string containing the cookie for the URL if it exists,
   *         or null if not.
   */
  public static String getCookieForUrl(String url) {
    // Get the cookie for this URL, set as a header
    return CookieManager.getInstance().getCookie(url);
  }

  /**
   * Set the cookie for the given URL.
   * @param url    The fully qualified URL.
   * @param cookie The new cookie value.
   * @return A string containing the cookie for the URL if it exists,
   *         or null if not.
   */
  public static void setCookieForUrl(String url, String cookie) {
    // Get the cookie for this URL, set as a header
    CookieManager.getInstance().setCookie(url, cookie);
  }

  /**
   * Perform a request using LocalServer if possible. Initializes
   * class members so that receive() will obtain data from the stream
   * provided by the response.
   * @param url The fully qualified URL to try in LocalServer.
   * @return True if the url was found and is now setup to receive.
   *         False if not found, with no side-effect.
   */
  public synchronized boolean useLocalServerResult(String url) {
    UrlInterceptHandlerGears handler = UrlInterceptHandlerGears.getInstance();
    if (handler == null) {
      return false;
    }
    UrlInterceptHandlerGears.ServiceResponse serviceResponse =
        handler.getServiceResponse(url, requestHeaders);
    if (serviceResponse == null) {
      log("No response in LocalServer");
      return false;
    }
    // LocalServer will handle this URL. Initialize stream and
    // response.
    inputStream = serviceResponse.getInputStream();
    responseLine = serviceResponse.getStatusLine();
    responseHeaders = serviceResponse.getResponseHeaders();
    if (logEnabled)
      log("Got response from LocalServer: " + responseLine);
    return true;
  }

  /**
   * Perform a request using the cache result if present. Initializes
   * class members so that receive() will obtain data from the cache.
   * @param url The fully qualified URL to try in the cache.
   * @return True is the url was found and is now setup to receive
   *         from cache. False if not found, with no side-effect.
   */
  public synchronized boolean useCacheResult(String url) {
    // Try the browser's cache. CacheManager wants a Map<String, String>.
    Map<String, String> cacheRequestHeaders = new HashMap<String, String>();
    Iterator<Map.Entry<String, String[]>> it =
        requestHeaders.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, String[]> entry = it.next();
      cacheRequestHeaders.put(
          entry.getKey(),
          entry.getValue()[HEADERS_MAP_INDEX_VALUE]);
    }
    CacheResult cacheResult =
        CacheManager.getCacheFile(url, cacheRequestHeaders);
    if (cacheResult == null) {
      if (logEnabled)
        log("No CacheResult for " + url);
      return false;
    }
    if (logEnabled)
      log("Got CacheResult from browser cache");
    // Check for expiry. -1 is "never", otherwise milliseconds since 1970.
    // Can be compared to System.currentTimeMillis().
    long expires = cacheResult.getExpires();
    if (expires >= 0 && System.currentTimeMillis() >= expires) {
      log("CacheResult expired "
          + (System.currentTimeMillis() - expires)
          + " milliseconds ago");
      // Cache hit has expired. Do not return it.
      return false;
    }
    // Setup the inputStream to come from the cache.
    inputStream = cacheResult.getInputStream();
    if (inputStream == null) {
      // Cache result may have gone away.
      log("No inputStream for CacheResult " + url);
      return false;
    }
    // Cache hit. Parse headers.
    synthesizeHeadersFromCacheResult(cacheResult);
    return true;
  }

  /**
   * Take the limited set of headers in a CacheResult and synthesize
   * response headers.
   * @param cacheResult A CacheResult to populate responseHeaders with.
   */
  private void synthesizeHeadersFromCacheResult(CacheResult cacheResult) {
    int statusCode = cacheResult.getHttpStatusCode();
    // The status message is informal, so we can greatly simplify it.
    String statusMessage;
    if (statusCode >= 200 && statusCode < 300) {
      statusMessage = "OK";
    } else if (statusCode >= 300 && statusCode < 400) {
      statusMessage = "MOVED";
    } else {
      statusMessage = "UNAVAILABLE";
    }
    // Synthesize the response line.
    responseLine = "HTTP/1.1 " + statusCode + " " + statusMessage;
    if (logEnabled)
      log("Synthesized " + responseLine);
    // Synthesize the returned headers from cache.
    responseHeaders = new HashMap<String, String[]>();
    String contentLength = Long.toString(cacheResult.getContentLength());
    setResponseHeader(KEY_CONTENT_LENGTH, contentLength);
    long expires = cacheResult.getExpires();
    if (expires >= 0) {
      // "Expires" header is valid and finite. Milliseconds since 1970
      // epoch, formatted as RFC-1123.
      String expiresString = DateUtils.formatDate(new Date(expires));
      setResponseHeader(KEY_EXPIRES, expiresString);
    }
    String lastModified = cacheResult.getLastModified();
    if (lastModified != null) {
      // Last modification time of the page. Passed end-to-end, but
      // not used by us.
      setResponseHeader(KEY_LAST_MODIFIED, lastModified);
    }
    String eTag = cacheResult.getETag();
    if (eTag != null) {
      // Entity tag. A kind of GUID to identify identical resources.
      setResponseHeader(KEY_ETAG, eTag);
    }
    String location = cacheResult.getLocation();
    if (location != null) {
      // If valid, refers to the location of a redirect.
      setResponseHeader(KEY_LOCATION, location);
    }
    String mimeType = cacheResult.getMimeType();
    if (mimeType == null) {
      // Use a safe default MIME type when none is
      // specified. "text/plain" is safe to render in the browser
      // window (even if large) and won't be intepreted as anything
      // that would cause execution.
      mimeType = DEFAULT_MIME_TYPE;
    }
    String encoding = cacheResult.getEncoding();
    // Encoding may not be specified. No default.
    String contentType = mimeType;
    if (encoding != null && encoding.length() > 0) {
      contentType += "; charset=" + encoding;
    }
    setResponseHeader(KEY_CONTENT_TYPE, contentType);
  }

  /**
   * Create a CacheResult for this URL. This enables the repsonse body
   * to be sent in calls to appendCacheResult().
   * @param url          The fully qualified URL to add to the cache.
   * @param responseCode The response code returned for the request, e.g 200.
   * @param mimeType     The MIME type of the body, e.g "text/plain".
   * @param encoding     The encoding, e.g "utf-8". Use "" for unknown.
   */
  public synchronized boolean createCacheResult(
      String url, int responseCode, String mimeType, String encoding) {
    if (logEnabled)
      log("Making cache entry for " + url);
    // Take the headers and parse them into a format needed by
    // CacheManager.
    Headers cacheHeaders = new Headers();
    Iterator<Map.Entry<String, String[]>> it =
        responseHeaders.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<String, String[]> entry = it.next();
      // Headers.parseHeader() expects lowercase keys.
      String keyValue = entry.getKey() + ": "
          + entry.getValue()[HEADERS_MAP_INDEX_VALUE];
      CharArrayBuffer buffer = new CharArrayBuffer(keyValue.length());
      buffer.append(keyValue);
      // Parse it into the header container.
      cacheHeaders.parseHeader(buffer);
    }
    cacheResult = CacheManager.createCacheFile(
        url, responseCode, cacheHeaders, mimeType, true);
    if (cacheResult != null) {
      if (logEnabled)
        log("Saving into cache");
      cacheResult.setEncoding(encoding);
      cacheResultUrl = url;
      return true;
    } else {
      log("Couldn't create cacheResult");
      return false;
    }
  }

  /**
   * Add data from the response body to the CacheResult created with
   * createCacheResult().
   * @param data  A byte array of the next sequential bytes in the
   *              response body.
   * @param bytes The number of bytes to write from the start of
   *              the array.
   * @return True if all bytes successfully written, false on failure.
   */
  public synchronized boolean appendCacheResult(byte[] data, int bytes) {
    if (cacheResult == null) {
      log("appendCacheResult() called without a CacheResult initialized");
      return false;
    }
    try {
      cacheResult.getOutputStream().write(data, 0, bytes);
    } catch (IOException ex) {
      log("Got IOException writing cache data: " + ex);
      return false;
    }
    return true;
  }

  /**
   * Save the completed CacheResult into the CacheManager. This must
   * have been created first with createCacheResult().
   * @return Returns true if the entry has been successfully saved.
   */
  public synchronized boolean saveCacheResult() {
    if (cacheResult == null || cacheResultUrl == null) {
      log("Tried to save cache result but createCacheResult not called");
      return false;
    }
    if (logEnabled)
      log("Saving cache result");
    CacheManager.saveCacheFile(cacheResultUrl, cacheResult);
    cacheResult = null;
    cacheResultUrl = null;
    return true;
  }

  /**
   * Perform an HTTP request on the network. The underlying
   * HttpURLConnection is connected to the remote server and the
   * response headers are received.
   * @return True if the connection succeeded and headers have been
   *         received. False on connection failure.
   */
  public boolean connectToRemote() {
    synchronized (this) {
      // Transfer a snapshot of our internally maintained map of request
      // headers to the connection object.
      applyRequestHeadersToConnection();
      // Note blocking I/O so abort() can interrupt us.
      inBlockingOperation = true;
    }
    boolean success;
    try {
      if (logEnabled)
        log("Connecting to remote");
      connection.connect();
      if (logEnabled)
        log("Connected");
      success = true;
    } catch (IOException e) {
      log("Got IOException in connect(): " + e.toString());
      success = false;
    } finally {
      synchronized (this) {
        // No longer blocking.
        inBlockingOperation = false;
      }
    }
    return success;
  }

  /**
   * Receive all headers from the server and populate
   * responseHeaders. This converts from the slightly odd format
   * returned by java.net.HttpURLConnection to a simpler
   * java.util.Map.
   * @return True if headers are successfully received, False on
   *         connection error.
   */
  public synchronized boolean parseHeaders() {
    responseHeaders = new HashMap<String, String[]>();
    /* HttpURLConnection contains a null terminated list of
     * key->value response pairs. If the key is null, then the value
     * contains the complete status line. If both key and value are
     * null for an index, we've reached the end.
     */
    for (int i = 0; ; ++i) {
      String key = connection.getHeaderFieldKey(i);
      String value = connection.getHeaderField(i);
      if (logEnabled)
        log("header " + key + " -> " + value);
      if (key == null && value == null) {
        // End of list.
        break;
      } else if (key == null) {
        // The pair with null key has the complete status line in
        // the value, e.g "HTTP/1.0 200 OK".
        responseLine = value;
      } else if (value != null) {
        // If key and value are non-null, this is a response pair, e.g
        // "Content-Length" -> "5". Use setResponseHeader() to
        // correctly deal with case-insensitivity of the key.
        setResponseHeader(key, value);
      } else {
        // The key is non-null but value is null. Unexpected
        // condition.
        return false;
      }
    }
    return true;
  }

  /**
   * Receive the next sequential bytes of the response body after
   * successful connection. This will receive up to the size of the
   * provided byte array. If there is no body, this will return 0
   * bytes on the first call after connection.
   * @param  buf A pre-allocated byte array to receive data into.
   * @return The number of bytes from the start of the array which
   *         have been filled, 0 on EOF, or negative on error.
   */
  public int receive(byte[] buf) {
    if (inputStream == null) {
      // If this is the first call, setup the InputStream. This may
      // fail if there were headers, but no body returned by the
      // server.
      try {
        inputStream = connection.getInputStream();
      } catch (IOException inputException) {
        log("Failed to connect InputStream: " + inputException);
        // Not unexpected. For example, 404 response return headers,
        // and sometimes a body with a detailed error. Try the error
        // stream.
        inputStream = connection.getErrorStream();
        if (inputStream == null) {
          // No error stream either. Treat as a 0 byte response.
          log("No InputStream");
          return 0; // EOF.
        }
      }
    }
    synchronized (this) {
      // Note blocking I/O so abort() can interrupt us.
      inBlockingOperation = true;
    }
    int ret;
    try {
      int got = inputStream.read(buf);
      if (got > 0) {
        // Got some bytes, not EOF.
        ret = got;
      } else {
        // EOF.
        inputStream.close();
        ret = 0;
      }
    } catch (IOException e) {
      // An abort() interrupts us by calling close() on our stream.
      log("Got IOException in inputStream.read(): " + e.toString());
      ret = -1;
    } finally {
      synchronized (this) {
        // No longer blocking.
        inBlockingOperation = false;
      }
    }
    return ret;
  }

  /**
   * For POST method requests, send a stream of data provided by the
   * native side in repeated callbacks.
   * @param data  A byte array containing the data to sent, or null
   *              if indicating EOF.
   * @param bytes The number of bytes from the start of the array to
   *              send, or 0 if indicating EOF.
   * @return True if all bytes were successfully sent, false on error.
   */
  public boolean sendPostData(byte[] data, int bytes) {
    synchronized (this) {
      // Note blocking I/O so abort() can interrupt us.
      inBlockingOperation = true;
    }
    boolean success;
    try {
      OutputStream outputStream = connection.getOutputStream();
      if (data == null && bytes == 0) {
        outputStream.close();
      } else {
        outputStream.write(data, 0, bytes);
      }
      success = true;
    } catch (IOException e) {
      log("Got IOException in post: " + e.toString());
      success = false;
    } finally {
      synchronized (this) {
        // No longer blocking.
        inBlockingOperation = false;
      }
    }
    return success;
  }
}
