/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.statementservice.retriever;

import android.util.Log;

import com.android.volley.Cache;
import com.android.volley.NetworkResponse;
import com.android.volley.toolbox.HttpHeaderParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Helper class for fetching HTTP or HTTPS URL.
 *
 * Visible for testing.
 *
 * @hide
 */
public class URLFetcher {
    private static final String TAG = URLFetcher.class.getSimpleName();

    private static final long DO_NOT_CACHE_RESULT = 0L;
    private static final int INPUT_BUFFER_SIZE_IN_BYTES = 1024;

    /**
     * Fetches the specified url and returns the content and ttl.
     *
     * <p>
     * Retry {@code retry} times if the connection failed or timed out for any reason.
     * HTTP error code (e.g. 404/500) won't be retried.
     *
     * @throws IOException if it can't retrieve the content due to a network problem.
     * @throws AssociationServiceException if the URL scheme is not http or https or the content
     * length exceeds {code fileSizeLimit}.
     */
    public WebContent getWebContentFromUrlWithRetry(URL url, long fileSizeLimit,
            int connectionTimeoutMillis, int backoffMillis, int retry)
                    throws AssociationServiceException, IOException, InterruptedException {
        if (retry <= 0) {
            throw new IllegalArgumentException("retry should be a postive inetger.");
        }
        while (retry > 0) {
            try {
                return getWebContentFromUrl(url, fileSizeLimit, connectionTimeoutMillis);
            } catch (IOException e) {
                retry--;
                if (retry == 0) {
                    throw e;
                }
            }

            Thread.sleep(backoffMillis);
        }

        // Should never reach here.
        return null;
    }

    /**
     * Fetches the specified url and returns the content and ttl.
     *
     * @throws IOException if it can't retrieve the content due to a network problem.
     * @throws AssociationServiceException if the URL scheme is not http or https or the content
     * length exceeds {code fileSizeLimit}.
     */
    public WebContent getWebContentFromUrl(URL url, long fileSizeLimit, int connectionTimeoutMillis)
            throws AssociationServiceException, IOException {
        final String scheme = url.getProtocol().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("The url protocol should be on http or https.");
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(connectionTimeoutMillis);
            connection.setReadTimeout(connectionTimeoutMillis);
            connection.setUseCaches(true);
            connection.setInstanceFollowRedirects(false);
            connection.addRequestProperty("Cache-Control", "max-stale=60");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "The responses code is not 200 but "  + connection.getResponseCode());
                return new WebContent("", DO_NOT_CACHE_RESULT);
            }

            if (connection.getContentLength() > fileSizeLimit) {
                Log.e(TAG, "The content size of the url is larger than "  + fileSizeLimit);
                return new WebContent("", DO_NOT_CACHE_RESULT);
            }

            Long expireTimeMillis = getExpirationTimeMillisFromHTTPHeader(
                    connection.getHeaderFields());

            return new WebContent(inputStreamToString(
                    connection.getInputStream(), connection.getContentLength(), fileSizeLimit),
                expireTimeMillis);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Visible for testing.
     * @hide
     */
    public static String inputStreamToString(InputStream inputStream, int length, long sizeLimit)
            throws IOException, AssociationServiceException {
        if (length < 0) {
            length = 0;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(length);
        BufferedInputStream bis = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[INPUT_BUFFER_SIZE_IN_BYTES];
        int len = 0;
        while ((len = bis.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
            if (baos.size() > sizeLimit) {
                throw new AssociationServiceException("The content size of the url is larger than "
                        + sizeLimit);
            }
        }
        return baos.toString("UTF-8");
    }

    /**
     * Parses the HTTP headers to compute the ttl.
     *
     * @param headers a map that map the header key to the header values. Can be null.
     * @return the ttl in millisecond or null if the ttl is not specified in the header.
     */
    private Long getExpirationTimeMillisFromHTTPHeader(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> joinedHeaders = joinHttpHeaders(headers);

        NetworkResponse response = new NetworkResponse(null, joinedHeaders);
        Cache.Entry cachePolicy = HttpHeaderParser.parseCacheHeaders(response);

        if (cachePolicy == null) {
            // Cache is disabled, set the expire time to 0.
            return DO_NOT_CACHE_RESULT;
        } else if (cachePolicy.ttl == 0) {
            // Cache policy is not specified, set the expire time to 0.
            return DO_NOT_CACHE_RESULT;
        } else {
            // cachePolicy.ttl is actually the expire timestamp in millisecond.
            return cachePolicy.ttl;
        }
    }

    /**
     * Converts an HTTP header map of the format provided by {@linkHttpUrlConnection} to a map of
     * the format accepted by {@link HttpHeaderParser}. It does this by joining all the entries for
     * a given header key with ", ".
     */
    private Map<String, String> joinHttpHeaders(Map<String, List<String>> headers) {
        Map<String, String> joinedHeaders = new HashMap<String, String>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            List<String> values = entry.getValue();
            if (values.size() == 1) {
                joinedHeaders.put(entry.getKey(), values.get(0));
            } else {
                joinedHeaders.put(entry.getKey(), Utils.joinStrings(", ", values));
            }
        }
        return joinedHeaders;
    }
}
