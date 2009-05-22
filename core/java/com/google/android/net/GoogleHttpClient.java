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

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.http.AndroidHttpClient;
import android.os.Build;
import android.os.NetStat;
import android.os.SystemClock;
import android.provider.Checkin;
import android.util.Config;
import android.util.Log;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.LayeredSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.SocketFactory;
import org.apache.http.impl.client.EntityEnclosingRequestWrapper;
import org.apache.http.impl.client.RequestWrapper;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.harmony.xnet.provider.jsse.SSLClientSessionCache;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * {@link AndroidHttpClient} wrapper that uses {@link UrlRules} to rewrite URLs
 * and otherwise tweak HTTP requests.
 */
public class GoogleHttpClient implements HttpClient {

    private static final String TAG = "GoogleHttpClient";

    /** Exception thrown when a request is blocked by the URL rules. */
    public static class BlockedRequestException extends IOException {
        private final UrlRules.Rule mRule;
        BlockedRequestException(UrlRules.Rule rule) {
            super("Blocked by rule: " + rule.mName);
            mRule = rule;
        }
    }

    private final AndroidHttpClient mClient;
    private final ContentResolver mResolver;
    private final String mAppName, mUserAgent;
    private final ThreadLocal<Boolean> mConnectionAllocated = new ThreadLocal<Boolean>();

    /**
     * Create an HTTP client without SSL session persistence.
     * @deprecated Use {@link #GoogleHttpClient(android.content.Context, String, boolean)}
     */
    public GoogleHttpClient(ContentResolver resolver, String userAgent) {
        mClient = AndroidHttpClient.newInstance(userAgent);
        mResolver = resolver;
        mUserAgent = mAppName = userAgent;
    }

    /**
     * Create an HTTP client without SSL session persistence.
     * @deprecated Use {@link #GoogleHttpClient(android.content.Context, String, boolean)}
     */
    public GoogleHttpClient(ContentResolver resolver, String appAndVersion,
            boolean gzipCapable) {
        this(resolver, null /* cache */, appAndVersion, gzipCapable);
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
     * The context has settings for URL rewriting rules and is used to enable
     *  SSL session persistence.
     *
     * @param context application context.
     * @param appAndVersion Base app and version to use in the User-Agent.
     * e.g., "MyApp/1.0"
     * @param gzipCapable Whether or not this client is able to consume gzip'd
     * responses.  Only used to modify the User-Agent, not other request
     * headers.  Needed because Google servers require gzip in the User-Agent
     * in order to return gzip'd content.
     */
    public GoogleHttpClient(Context context, String appAndVersion, boolean gzipCapable) {
        this(context.getContentResolver(),
                SSLClientSessionCacheFactory.getCache(context),
                appAndVersion, gzipCapable);
    }

    private GoogleHttpClient(ContentResolver resolver,
            SSLClientSessionCache cache,
            String appAndVersion, boolean gzipCapable) {
        String userAgent = appAndVersion + " (" + Build.DEVICE + " " + Build.ID + ")";
        if (gzipCapable) {
            userAgent = userAgent + "; gzip";
        }

        mClient = AndroidHttpClient.newInstance(userAgent, cache);
        mResolver = resolver;
        mAppName = appAndVersion;
        mUserAgent = userAgent;

        // Wrap all the socket factories with the appropriate wrapper.  (Apache
        // HTTP, curse its black and stupid heart, inspects the SocketFactory to
        // see if it's a LayeredSocketFactory, so we need two wrapper classes.)
        SchemeRegistry registry = getConnectionManager().getSchemeRegistry();
        for (String name : registry.getSchemeNames()) {
            Scheme scheme = registry.unregister(name);
            SocketFactory sf = scheme.getSocketFactory();
            if (sf instanceof LayeredSocketFactory) {
                sf = new WrappedLayeredSocketFactory((LayeredSocketFactory) sf);
            } else {
                sf = new WrappedSocketFactory(sf);
            }
            registry.register(new Scheme(name, sf, scheme.getDefaultPort()));
        }
    }

    /**
     * Delegating wrapper for SocketFactory records when sockets are connected.
     * We use this to know whether a connection was created vs reused, to
     * gather per-app statistics about connection reuse rates.
     * (Note, we record only *connection*, not *creation* of sockets --
     * what we care about is the network overhead of an actual TCP connect.)
     */
    private class WrappedSocketFactory implements SocketFactory {
        private SocketFactory mDelegate;
        private WrappedSocketFactory(SocketFactory delegate) { mDelegate = delegate; }
        public final Socket createSocket() throws IOException { return mDelegate.createSocket(); }
        public final boolean isSecure(Socket s) { return mDelegate.isSecure(s); }

        public final Socket connectSocket(
                Socket s, String h, int p,
                InetAddress la, int lp, HttpParams params) throws IOException {
            mConnectionAllocated.set(Boolean.TRUE);
            return mDelegate.connectSocket(s, h, p, la, lp, params);
        }
    }

    /** Like WrappedSocketFactory, but for the LayeredSocketFactory subclass. */
    private class WrappedLayeredSocketFactory
            extends WrappedSocketFactory implements LayeredSocketFactory {
        private LayeredSocketFactory mDelegate;
        private WrappedLayeredSocketFactory(LayeredSocketFactory sf) { super(sf); mDelegate = sf; }

        public final Socket createSocket(Socket s, String host, int port, boolean autoClose)
                throws IOException {
            return mDelegate.createSocket(s, host, port, autoClose);
        }
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
        int code = -1;
        long start = SystemClock.elapsedRealtime();
        try {
            HttpResponse response;
            mConnectionAllocated.set(null);

            if (NetworkStatsEntity.shouldLogNetworkStats()) {
                // TODO: if we're logging network stats, and if the apache library is configured
                // to follow redirects, count each redirect as an additional round trip.

                int uid = android.os.Process.myUid();
                long startTx = NetStat.getUidTxBytes(uid);
                long startRx = NetStat.getUidRxBytes(uid);

                response = mClient.execute(request, context);
                HttpEntity origEntity = response == null ? null : response.getEntity();
                if (origEntity != null) {
                    // yeah, we compute the same thing below.  we do need to compute this here
                    // so we can wrap the HttpEntity in the response.
                    long now = SystemClock.elapsedRealtime();
                    long elapsed = now - start;
                    NetworkStatsEntity entity = new NetworkStatsEntity(origEntity,
                            mAppName, uid, startTx, startRx,
                            elapsed /* response latency */, now /* processing start time */);
                    response.setEntity(entity);
                }
            } else {
                response = mClient.execute(request, context);
            }

            code = response.getStatusLine().getStatusCode();
            return response;
        } finally {
            // Record some statistics to the checkin service about the outcome.
            // Note that this is only describing execute(), not body download.
            // We assume the database writes are much faster than network I/O,
            // and not worth running in a background thread or anything.
            try {
                long elapsed = SystemClock.elapsedRealtime() - start;
                ContentValues values = new ContentValues();
                values.put(Checkin.Stats.COUNT, 1);
                values.put(Checkin.Stats.SUM, elapsed / 1000.0);

                values.put(Checkin.Stats.TAG, Checkin.Stats.Tag.HTTP_REQUEST + ":" + mAppName);
                mResolver.insert(Checkin.Stats.CONTENT_URI, values);

                // No sockets and no exceptions means we successfully reused a connection
                if (mConnectionAllocated.get() == null && code >= 0) {
                    values.put(Checkin.Stats.TAG, Checkin.Stats.Tag.HTTP_REUSED + ":" + mAppName);
                    mResolver.insert(Checkin.Stats.CONTENT_URI, values);
                }

                String status = code < 0 ? "IOException" : Integer.toString(code);
                values.put(Checkin.Stats.TAG,
                         Checkin.Stats.Tag.HTTP_STATUS + ":" + mAppName + ":" + status);
                mResolver.insert(Checkin.Stats.CONTENT_URI, values);
            } catch (Exception e) {
                Log.e(TAG, "Error recording stats", e);
            }
        }
    }

    public String rewriteURI(String original) {
        UrlRules rules = UrlRules.getRules(mResolver);
        UrlRules.Rule rule = rules.matchRule(original);
        return rule.apply(original);
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
