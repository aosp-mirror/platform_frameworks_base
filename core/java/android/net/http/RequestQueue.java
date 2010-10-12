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

/**
 * High level HTTP Interface
 * Queues requests as necessary
 */

package android.net.http;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Proxy;
import android.net.WebAddress;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Map;

import org.apache.http.HttpHost;

/**
 * {@hide}
 */
public class RequestQueue implements RequestFeeder {


    /**
     * Requests, indexed by HttpHost (scheme, host, port)
     */
    private final LinkedHashMap<HttpHost, LinkedList<Request>> mPending;
    private final Context mContext;
    private final ActivePool mActivePool;
    private final ConnectivityManager mConnectivityManager;

    private HttpHost mProxyHost = null;
    private BroadcastReceiver mProxyChangeReceiver;

    /* default simultaneous connection count */
    private static final int CONNECTION_COUNT = 4;

    /**
     * This class maintains active connection threads
     */
    class ActivePool implements ConnectionManager {
        /** Threads used to process requests */
        ConnectionThread[] mThreads;

        IdleCache mIdleCache;

        private int mTotalRequest;
        private int mTotalConnection;
        private int mConnectionCount;

        ActivePool(int connectionCount) {
            mIdleCache = new IdleCache();
            mConnectionCount = connectionCount;
            mThreads = new ConnectionThread[mConnectionCount];

            for (int i = 0; i < mConnectionCount; i++) {
                mThreads[i] = new ConnectionThread(
                        mContext, i, this, RequestQueue.this);
            }
        }

        void startup() {
            for (int i = 0; i < mConnectionCount; i++) {
                mThreads[i].start();
            }
        }

        void shutdown() {
            for (int i = 0; i < mConnectionCount; i++) {
                mThreads[i].requestStop();
            }
        }

        void startConnectionThread() {
            synchronized (RequestQueue.this) {
                RequestQueue.this.notify();
            }
        }

        public void startTiming() {
            for (int i = 0; i < mConnectionCount; i++) {
                ConnectionThread rt = mThreads[i];
                rt.mCurrentThreadTime = -1;
                rt.mTotalThreadTime = 0;
            }
            mTotalRequest = 0;
            mTotalConnection = 0;
        }

        public void stopTiming() {
            int totalTime = 0;
            for (int i = 0; i < mConnectionCount; i++) {
                ConnectionThread rt = mThreads[i];
                if (rt.mCurrentThreadTime != -1) {
                    totalTime += rt.mTotalThreadTime;
                }
                rt.mCurrentThreadTime = 0;
            }
            Log.d("Http", "Http thread used " + totalTime + " ms " + " for "
                    + mTotalRequest + " requests and " + mTotalConnection
                    + " new connections");
        }

        void logState() {
            StringBuilder dump = new StringBuilder();
            for (int i = 0; i < mConnectionCount; i++) {
                dump.append(mThreads[i] + "\n");
            }
            HttpLog.v(dump.toString());
        }


        public HttpHost getProxyHost() {
            return mProxyHost;
        }

        /**
         * Turns off persistence on all live connections
         */
        void disablePersistence() {
            for (int i = 0; i < mConnectionCount; i++) {
                Connection connection = mThreads[i].mConnection;
                if (connection != null) connection.setCanPersist(false);
            }
            mIdleCache.clear();
        }

        /* Linear lookup -- okay for small thread counts.  Might use
           private HashMap<HttpHost, LinkedList<ConnectionThread>> mActiveMap;
           if this turns out to be a hotspot */
        ConnectionThread getThread(HttpHost host) {
            synchronized(RequestQueue.this) {
                for (int i = 0; i < mThreads.length; i++) {
                    ConnectionThread ct = mThreads[i];
                    Connection connection = ct.mConnection;
                    if (connection != null && connection.mHost.equals(host)) {
                        return ct;
                    }
                }
            }
            return null;
        }

        public Connection getConnection(Context context, HttpHost host) {
            host = RequestQueue.this.determineHost(host);
            Connection con = mIdleCache.getConnection(host);
            if (con == null) {
                mTotalConnection++;
                con = Connection.getConnection(mContext, host, mProxyHost,
                        RequestQueue.this);
            }
            return con;
        }
        public boolean recycleConnection(Connection connection) {
            return mIdleCache.cacheConnection(connection.getHost(), connection);
        }

    }

    /**
     * A RequestQueue class instance maintains a set of queued
     * requests.  It orders them, makes the requests against HTTP
     * servers, and makes callbacks to supplied eventHandlers as data
     * is read.  It supports request prioritization, connection reuse
     * and pipelining.
     *
     * @param context application context
     */
    public RequestQueue(Context context) {
        this(context, CONNECTION_COUNT);
    }

    /**
     * A RequestQueue class instance maintains a set of queued
     * requests.  It orders them, makes the requests against HTTP
     * servers, and makes callbacks to supplied eventHandlers as data
     * is read.  It supports request prioritization, connection reuse
     * and pipelining.
     *
     * @param context application context
     * @param connectionCount The number of simultaneous connections 
     */
    public RequestQueue(Context context, int connectionCount) {
        mContext = context;

        mPending = new LinkedHashMap<HttpHost, LinkedList<Request>>(32);

        mActivePool = new ActivePool(connectionCount);
        mActivePool.startup();

        mConnectivityManager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    /**
     * Enables data state and proxy tracking
     */
    public synchronized void enablePlatformNotifications() {
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.enablePlatformNotifications() network");

        if (mProxyChangeReceiver == null) {
            mProxyChangeReceiver =
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context ctx, Intent intent) {
                            setProxyConfig();
                        }
                    };
            mContext.registerReceiver(mProxyChangeReceiver,
                                      new IntentFilter(Proxy.PROXY_CHANGE_ACTION));
        }
        // we need to resample the current proxy setup
        setProxyConfig();
    }

    /**
     * If platform notifications have been enabled, call this method
     * to disable before destroying RequestQueue
     */
    public synchronized void disablePlatformNotifications() {
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.disablePlatformNotifications() network");

        if (mProxyChangeReceiver != null) {
            mContext.unregisterReceiver(mProxyChangeReceiver);
            mProxyChangeReceiver = null;
        }
    }

    /**
     * Because our IntentReceiver can run within a different thread,
     * synchronize setting the proxy
     */
    private synchronized void setProxyConfig() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        if (info != null && info.getType() == ConnectivityManager.TYPE_WIFI) {
            mProxyHost = null;
        } else {
            String host = Proxy.getHost(mContext);
            if (HttpLog.LOGV) HttpLog.v("RequestQueue.setProxyConfig " + host);
            if (host == null) {
                mProxyHost = null;
            } else {
                mActivePool.disablePersistence();
                mProxyHost = new HttpHost(host, Proxy.getPort(mContext), "http");
            }
        }
    }

    /**
     * used by webkit
     * @return proxy host if set, null otherwise
     */
    public HttpHost getProxyHost() {
        return mProxyHost;
    }

    /**
     * Queues an HTTP request
     * @param url The url to load.
     * @param method "GET" or "POST."
     * @param headers A hashmap of http headers.
     * @param eventHandler The event handler for handling returned
     * data.  Callbacks will be made on the supplied instance.
     * @param bodyProvider InputStream providing HTTP body, null if none
     * @param bodyLength length of body, must be 0 if bodyProvider is null
     */
    public RequestHandle queueRequest(
            String url, String method,
            Map<String, String> headers, EventHandler eventHandler,
            InputStream bodyProvider, int bodyLength) {
        WebAddress uri = new WebAddress(url);
        return queueRequest(url, uri, method, headers, eventHandler,
                            bodyProvider, bodyLength);
    }

    /**
     * Queues an HTTP request
     * @param url The url to load.
     * @param uri The uri of the url to load.
     * @param method "GET" or "POST."
     * @param headers A hashmap of http headers.
     * @param eventHandler The event handler for handling returned
     * data.  Callbacks will be made on the supplied instance.
     * @param bodyProvider InputStream providing HTTP body, null if none
     * @param bodyLength length of body, must be 0 if bodyProvider is null
     */
    public RequestHandle queueRequest(
            String url, WebAddress uri, String method, Map<String, String> headers,
            EventHandler eventHandler,
            InputStream bodyProvider, int bodyLength) {

        if (HttpLog.LOGV) HttpLog.v("RequestQueue.queueRequest " + uri);

        // Ensure there is an eventHandler set
        if (eventHandler == null) {
            eventHandler = new LoggingEventHandler();
        }

        /* Create and queue request */
        Request req;
        HttpHost httpHost = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        // set up request
        req = new Request(method, httpHost, mProxyHost, uri.getPath(), bodyProvider,
                          bodyLength, eventHandler, headers);

        queueRequest(req, false);

        mActivePool.mTotalRequest++;

        // dump();
        mActivePool.startConnectionThread();

        return new RequestHandle(
                this, url, uri, method, headers, bodyProvider, bodyLength,
                req);
    }

    private static class SyncFeeder implements RequestFeeder {
        // This is used in the case where the request fails and needs to be
        // requeued into the RequestFeeder.
        private Request mRequest;
        SyncFeeder() {
        }
        public Request getRequest() {
            Request r = mRequest;
            mRequest = null;
            return r;
        }
        public Request getRequest(HttpHost host) {
            return getRequest();
        }
        public boolean haveRequest(HttpHost host) {
            return mRequest != null;
        }
        public void requeueRequest(Request r) {
            mRequest = r;
        }
    }

    public RequestHandle queueSynchronousRequest(String url, WebAddress uri,
            String method, Map<String, String> headers,
            EventHandler eventHandler, InputStream bodyProvider,
            int bodyLength) {
        if (HttpLog.LOGV) {
            HttpLog.v("RequestQueue.dispatchSynchronousRequest " + uri);
        }

        HttpHost host = new HttpHost(uri.getHost(), uri.getPort(), uri.getScheme());

        Request req = new Request(method, host, mProxyHost, uri.getPath(),
                bodyProvider, bodyLength, eventHandler, headers);

        // Open a new connection that uses our special RequestFeeder
        // implementation.
        host = determineHost(host);
        Connection conn = Connection.getConnection(mContext, host, mProxyHost,
                new SyncFeeder());

        // TODO: I would like to process the request here but LoadListener
        // needs a RequestHandle to process some messages.
        return new RequestHandle(this, url, uri, method, headers, bodyProvider,
                bodyLength, req, conn);

    }

    // Chooses between the proxy and the request's host.
    private HttpHost determineHost(HttpHost host) {
        // There used to be a comment in ConnectionThread about t-mob's proxy
        // being really bad about https. But, HttpsConnection actually looks
        // for a proxy and connects through it anyway. I think that this check
        // is still valid because if a site is https, we will use
        // HttpsConnection rather than HttpConnection if the proxy address is
        // not secure.
        return (mProxyHost == null || "https".equals(host.getSchemeName()))
                ? host : mProxyHost;
    }

    /**
     * @return true iff there are any non-active requests pending
     */
    synchronized boolean requestsPending() {
        return !mPending.isEmpty();
    }


    /**
     * debug tool: prints request queue to log
     */
    synchronized void dump() {
        HttpLog.v("dump()");
        StringBuilder dump = new StringBuilder();
        int count = 0;
        Iterator<Map.Entry<HttpHost, LinkedList<Request>>> iter;

        // mActivePool.log(dump);

        if (!mPending.isEmpty()) {
            iter = mPending.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<HttpHost, LinkedList<Request>> entry = iter.next();
                String hostName = entry.getKey().getHostName();
                StringBuilder line = new StringBuilder("p" + count++ + " " + hostName + " ");

                LinkedList<Request> reqList = entry.getValue();
                ListIterator reqIter = reqList.listIterator(0);
                while (iter.hasNext()) {
                    Request request = (Request)iter.next();
                    line.append(request + " ");
                }
                dump.append(line);
                dump.append("\n");
            }
        }
        HttpLog.v(dump.toString());
    }

    /*
     * RequestFeeder implementation
     */
    public synchronized Request getRequest() {
        Request ret = null;

        if (!mPending.isEmpty()) {
            ret = removeFirst(mPending);
        }
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.getRequest() => " + ret);
        return ret;
    }

    /**
     * @return a request for given host if possible
     */
    public synchronized Request getRequest(HttpHost host) {
        Request ret = null;

        if (mPending.containsKey(host)) {
            LinkedList<Request> reqList = mPending.get(host);
            ret = reqList.removeFirst();
            if (reqList.isEmpty()) {
                mPending.remove(host);
            }
        }
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.getRequest(" + host + ") => " + ret);
        return ret;
    }

    /**
     * @return true if a request for this host is available
     */
    public synchronized boolean haveRequest(HttpHost host) {
        return mPending.containsKey(host);
    }

    /**
     * Put request back on head of queue
     */
    public void requeueRequest(Request request) {
        queueRequest(request, true);
    }

    /**
     * This must be called to cleanly shutdown RequestQueue
     */
    public void shutdown() {
        mActivePool.shutdown();
    }

    protected synchronized void queueRequest(Request request, boolean head) {
        HttpHost host = request.mProxyHost == null ? request.mHost : request.mProxyHost;
        LinkedList<Request> reqList;
        if (mPending.containsKey(host)) {
            reqList = mPending.get(host);
        } else {
            reqList = new LinkedList<Request>();
            mPending.put(host, reqList);
        }
        if (head) {
            reqList.addFirst(request);
        } else {
            reqList.add(request);
        }
    }


    public void startTiming() {
        mActivePool.startTiming();
    }

    public void stopTiming() {
        mActivePool.stopTiming();
    }

    /* helper */
    private Request removeFirst(LinkedHashMap<HttpHost, LinkedList<Request>> requestQueue) {
        Request ret = null;
        Iterator<Map.Entry<HttpHost, LinkedList<Request>>> iter = requestQueue.entrySet().iterator();
        if (iter.hasNext()) {
            Map.Entry<HttpHost, LinkedList<Request>> entry = iter.next();
            LinkedList<Request> reqList = entry.getValue();
            ret = reqList.removeFirst();
            if (reqList.isEmpty()) {
                requestQueue.remove(entry.getKey());
            }
        }
        return ret;
    }

    /**
     * This interface is exposed to each connection
     */
    interface ConnectionManager {
        HttpHost getProxyHost();
        Connection getConnection(Context context, HttpHost host);
        boolean recycleConnection(Connection connection);
    }
}
