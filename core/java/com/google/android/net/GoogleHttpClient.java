/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.google.android.net;

import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.os.SystemClock;
import android.os.Build;
import android.net.http.AndroidHttpClient;
import android.provider.Checkin;
import android.util.Config;
import android.util.Log;

/**
 * {@link AndroidHttpClient} wrapper that uses {@link UrlRules} to rewrite URLs
 * and otherwise tweak HTTP requests.
 */
public class GoogleHttpClient implements HttpClient {
    private static final String TAG = "GoogleHttpClient";

    private final AndroidHttpClient mClient;
    private final ContentResolver mResolver;
    private final String mUserAgent;

    /** Exception thrown when a request is blocked by the URL rules. */
    public static class BlockedRequestException extends IOException {
        private final UrlRules.Rule mRule;
        BlockedRequestException(UrlRules.Rule rule) {
            super("Blocked by rule: " + rule.mName);
            mRule = rule;
        }
    }

    /**
     * Create an HTTP client.  Normally one client is shared throughout an app.
     * @param resolver to use for accessing URL rewriting rules.
     * @param userAgent to report in your HTTP requests.
     * @deprecated Use {@link #GoogleHttpClient(android.content.ContentResolver, String, boolean)} 
     */
    public GoogleHttpClient(ContentResolver resolver, String userAgent) {
        mClient = AndroidHttpClient.newInstance(userAgent);
        mResolver = resolver;
        mUserAgent = userAgent;
    }

    /**
     * Create an HTTP client.  Normaly this client is shared throughout an app.
     * The HTTP client will construct its User-Agent as follows:
     *
     * <appAndVersion> (<build device> <build id>)
     * or
     * <appAndVersion> (<build device> <build id>); gzip
     * (if gzip capable)
     *
     * @param resolver to use for acccessing URL rewriting rules.
     * @param appAndVersion Base app and version to use in the User-Agent.
     * e.g., "MyApp/1.0"
     * @param gzipCapable Whether or not this client is able to consume gzip'd
     * responses.  Only used to modify the User-Agent, not other request
     * headers.  Needed because Google servers require gzip in the User-Agent
     * in order to return gzip'd content.
     */
    public GoogleHttpClient(ContentResolver resolver, String appAndVersion,
            boolean gzipCapable) {
        String userAgent = appAndVersion
                + " (" + Build.DEVICE + " " + Build.ID + ")";
        if (gzipCapable) {
            userAgent = userAgent + "; gzip";
        }
        mClient = AndroidHttpClient.newInstance(userAgent);
        mResolver = resolver;
        mUserAgent = userAgent;
    }

    /**
     * Release resources associated with this client.  You must call this,
     * or significant resources (sockets and memory) may be leaked.
     */
    public void close() {
        mClient.close();
    }

    /** Execute a request without applying and rewrite rules. */
    public HttpResponse executeWithoutRewriting(
            HttpUriRequest request, HttpContext context)
            throws IOException {
        String code = "Error";
        long start = SystemClock.elapsedRealtime();
        try {
            HttpResponse response = mClient.execute(request, context);
            code = Integer.toString(response.getStatusLine().getStatusCode());
            return response;
        } catch (IOException e) {
            code = "IOException";
            throw e;
        } finally {
            // Record some statistics to the checkin service about the outcome.
            // Note that this is only describing execute(), not body download.
            try {
                long elapsed = SystemClock.elapsedRealtime() - start;
                ContentValues values = new ContentValues();
                values.put(Checkin.Stats.TAG,
                         Checkin.Stats.Tag.HTTP_STATUS + ":" +
                         mUserAgent + ":" + code);
                values.put(Checkin.Stats.COUNT, 1);
                values.put(Checkin.Stats.SUM, elapsed / 1000.0);
                mResolver.insert(Checkin.Stats.CONTENT_URI, values);
            } catch (Exception e) {
                Log.e(TAG, "Error recording stats", e);
            }
        }
    }

    public HttpResponse execute(HttpUriRequest request, HttpContext context)
            throws IOException {
        // Rewrite the supplied URL...
        URI uri = request.getURI();
        String original = uri.toString();
        UrlRules rules = UrlRules.getRules(mResolver);
        UrlRules.Rule rule = rules.matchRule(original);
        String rewritten = rule.apply(original);

        if (rewritten == null) {
            Log.w(TAG, "Blocked by " + rule.mName + ": " + original);
            throw new BlockedRequestException(rule);
        } else if (rewritten == original) {
            return executeWithoutRewriting(request, context);  // Pass through
        }

        try {
            uri = new URI(rewritten);
        } catch (URISyntaxException e) {
            throw new RuntimeException("Bad URL from rule: " + rule.mName, e);
        }

        // Wrap request so we can replace the URI.
        RequestWrapper wrapper = wrapRequest(request);
        wrapper.setURI(uri);
        request = wrapper;

        if (Config.LOGV) {
            Log.v(TAG, "Rule " + rule.mName + ": " + original + " -> " + rewritten);
        }
        return executeWithoutRewriting(request, context);
    }

    /**
     * Wraps the request making it mutable.
     */
    private static RequestWrapper wrapRequest(HttpUriRequest request)
            throws IOException {
        try {
            // We have to wrap it with the right type. Some code performs
            // instanceof checks.
            RequestWrapper wrapped;
            if (request instanceof HttpEntityEnclosingRequest) {
                wrapped = new EntityEnclosingRequestWrapper(
                        (HttpEntityEnclosingRequest) request);
            } else {
                wrapped = new RequestWrapper(request);
            }

            // Copy the headers from the original request into the wrapper.
            wrapped.resetHeaders();

            return wrapped;
        } catch (ProtocolException e) {
            throw new ClientProtocolException(e);
        }
    }

    /**
     * Mark a user agent as one Google will trust to handle gzipped content.
     * {@link AndroidHttpClient#modifyRequestToAcceptGzipResponse} is (also)
     * necessary but not sufficient -- many browsers claim to accept gzip but
     * have broken handling, so Google checks the user agent as well.
     *
     * @param originalUserAgent to modify (however you identify yourself)
     * @return user agent with a "yes, I really can handle gzip" token added.
     * @deprecated Use {@link #GoogleHttpClient(android.content.ContentResolver, String, boolean)} 
     */
    public static String getGzipCapableUserAgent(String originalUserAgent) {
        return originalUserAgent + "; gzip";
    }

    // HttpClient wrapper methods.

    public HttpParams getParams() {
        return mClient.getParams();
    }

    public ClientConnectionManager getConnectionManager() {
        return mClient.getConnectionManager();
    }

    public HttpResponse execute(HttpUriRequest request) throws IOException {
        return execute(request, (HttpContext) null);
    }

    public HttpResponse execute(HttpHost target, HttpRequest request)
            throws IOException {
        return mClient.execute(target, request);
    }

    public HttpResponse execute(HttpHost target, HttpRequest request,
            HttpContext context) throws IOException {
        return mClient.execute(target, request, context);
    }

    public <T> T execute(HttpUriRequest request,
            ResponseHandler<? extends T> responseHandler)
            throws IOException, ClientProtocolException {
        return mClient.execute(request, responseHandler);
    }

    public <T> T execute(HttpUriRequest request,
            ResponseHandler<? extends T> responseHandler, HttpContext context)
            throws IOException, ClientProtocolException {
        return mClient.execute(request, responseHandler, context);
    }

    public <T> T execute(HttpHost target, HttpRequest request,
            ResponseHandler<? extends T> responseHandler) throws IOException,
            ClientProtocolException {
        return mClient.execute(target, request, responseHandler);
    }

    public <T> T execute(HttpHost target, HttpRequest request,
            ResponseHandler<? extends T> responseHandler, HttpContext context)
            throws IOException, ClientProtocolException {
        return mClient.execute(target, request, responseHandler, context);
    }

    /**
     * Enables cURL request logging for this client.
     *
     * @param name to log messages with
     * @param level at which to log messages (see {@link android.util.Log})
     */
    public void enableCurlLogging(String name, int level) {
        mClient.enableCurlLogging(name, level);
    }

    /**
     * Disables cURL logging for this client.
     */
    public void disableCurlLogging() {
        mClient.disableCurlLogging();
    }
}
