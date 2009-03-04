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
import android.net.NetworkConnectivityListener;
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

    private Context mContext;

    /**
     * Requests, indexed by HttpHost (scheme, host, port)
     */
    private LinkedHashMap<HttpHost, LinkedList<Request>> mPending;

    /* Support for notifying a client when queue is empty */
    private boolean mClientWaiting = false;

    /** true if connected */
    boolean mNetworkConnected = true;

    private HttpHost mProxyHost = null;
    private BroadcastReceiver mProxyChangeReceiver;

    private ActivePool mActivePool;

    /* default simultaneous connection count */
    private static final int CONNECTION_COUNT = 4;

    /**
     * This intent broadcast when http is paused or unpaused due to
     * net availability toggling
     */
    public final static String HTTP_NETWORK_STATE_CHANGED_INTENT =
            "android.net.http.NETWORK_STATE";
    public final static String HTTP_NETWORK_STATE_UP = "up";

    /**
     * Listen to platform network state.  On a change,
     * (1) kick stack on or off as appropriate
     * (2) send an intent to my host app telling
     *     it what I've done
     */
    private NetworkStateTracker mNetworkStateTracker;
    class NetworkStateTracker {

        final static int EVENT_DATA_STATE_CHANGED = 100;

        Context mContext;
        NetworkConnectivityListener mConnectivityListener;
        NetworkInfo.State mLastNetworkState = NetworkInfo.State.CONNECTED;
        int mCurrentNetworkType;

        NetworkStateTracker(Context context) {
            mContext = context;
        }

        /**
         * register for updates
         */
        protected void enable() {
            if (mConnectivityListener == null) {
                /*
                 * Initializing the network type is really unnecessary,
                 * since as soon as we register with the NCL, we'll
                 * get a CONNECTED event for the active network, and
                 * we'll configure the HTTP proxy accordingly. However,
                 * as a fallback in case that doesn't happen for some
                 * reason, initializing to type WIFI would mean that
                 * we'd start out without a proxy. This seems better
                 * than thinking we have a proxy (which is probably
                 * private to the carrier network and therefore
                 * unreachable outside of that network) when we really
                 * shouldn't.
                 */
                mCurrentNetworkType = ConnectivityManager.TYPE_WIFI;
                mConnectivityListener = new NetworkConnectivityListener();
                mConnectivityListener.registerHandler(mHandler, EVENT_DATA_STATE_CHANGED);
                mConnectivityListener.startListening(mContext);
            }
        }

        protected void disable() {
            if (mConnectivityListener != null) {
                mConnectivityListener.unregisterHandler(mHandler);
                mConnectivityListener.stopListening();
                mConnectivityListener = null;
            }
        }

        private Handler mHandler = new Handler() {
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_DATA_STATE_CHANGED:
                        networkStateChanged();
                        break;
                }
            }
        };

        int getCurrentNetworkType() {
            return mCurrentNetworkType;
        }

        void networkStateChanged() {
            if (mConnectivityListener == null)
                return;

            
            NetworkConnectivityListener.State connectivityState = mConnectivityListener.getState();
            NetworkInfo info = mConnectivityListener.getNetworkInfo();
            if (info == null) {
                /**
                 * We've been seeing occasional NPEs here. I believe recent changes
                 * have made this impossible, but in the interest of being totally
                 * paranoid, check and log this here.
                 */
                HttpLog.v("NetworkStateTracker: connectivity broadcast"
                    + " has null network info - ignoring");
                return;
            }
            NetworkInfo.State state = info.getState();

            if (HttpLog.LOGV) {
                HttpLog.v("NetworkStateTracker " + info.getTypeName() +
                " state= " + state + " last= " + mLastNetworkState +
                " connectivityState= " + connectivityState.toString());
            }

            boolean newConnection =
                state != mLastNetworkState && state == NetworkInfo.State.CONNECTED;

            if (state == NetworkInfo.State.CONNECTED) {
                mCurrentNetworkType = info.getType();
                setProxyConfig();
            }

            mLastNetworkState = state;
            if (connectivityState == NetworkConnectivityListener.State.NOT_CONNECTED) {
                setNetworkState(false);
                broadcastState(false);
            } else if (newConnection) {
                setNetworkState(true);
                broadcastState(true);
            }

        }

        void broadcastState(boolean connected) {
            Intent intent = new Intent(HTTP_NETWORK_STATE_CHANGED_INTENT);
            intent.putExtra(HTTP_NETWORK_STATE_UP, connected);
            mContext.sendBroadcast(intent);
        }
    }

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

        public boolean isNetworkConnected() {
            return mNetworkConnected;
        }

        void startConnectionThread() {
            synchronized (RequestQueue.this) {
                RequestQueue.this.notify();
            }
        }

        public void startTiming() {
            for (int i = 0; i < mConnectionCount; i++) {
                mThreads[i].mStartThreadTime = mThreads[i].mCurrentThreadTime;
            }
            mTotalRequest = 0;
            mTotalConnection = 0;
        }

        public void stopTiming() {
            int totalTime = 0;
            for (int i = 0; i < mConnectionCount; i++) {
                ConnectionThread rt = mThreads[i];
                totalTime += (rt.mCurrentThreadTime - rt.mStartThreadTime);
                rt.mStartThreadTime = -1;
            }
            Log.d("Http", "Http thread used " + totalTime + " ms " + " for "
                    + mTotalRequest + " requests and " + mTotalConnection
                    + " connections");
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
            Connection con = mIdleCache.getConnection(host);
            if (con == null) {
                mTotalConnection++;
                con = Connection.getConnection(
                        mContext, host, this, RequestQueue.this);
            }
            return con;
        }
        public boolean recycleConnection(HttpHost host, Connection connection) {
            return mIdleCache.cacheConnection(host, connection);
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

        /* Network state notification is broken on the simulator
           don't register for notifications on SIM */
        String device = SystemProperties.get("ro.product.device");
        boolean simulation = TextUtils.isEmpty(device);

        if (!simulation) {
            if (mNetworkStateTracker == null) {
                mNetworkStateTracker = new NetworkStateTracker(mContext);
            }
            mNetworkStateTracker.enable();
        }
    }

    /**
     * If platform notifications have been enabled, call this method
     * to disable before destroying RequestQueue
     */
    public synchronized void disablePlatformNotifications() {
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.disablePlatformNotifications() network");

        if (mNetworkStateTracker != null) {
            mNetworkStateTracker.disable();
        }

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
        if (mNetworkStateTracker.getCurrentNetworkType() == ConnectivityManager.TYPE_WIFI) {
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
     * @param highPriority If true, queues before low priority
     *     requests if possible
     */
    public RequestHandle queueRequest(
            String url, String method,
            Map<String, String> headers, EventHandler eventHandler,
            InputStream bodyProvider, int bodyLength, boolean highPriority) {
        WebAddress uri = new WebAddress(url);
        return queueRequest(url, uri, method, headers, eventHandler,
                            bodyProvider, bodyLength, highPriority);
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
     * @param highPriority If true, queues before low priority
     *     requests if possible
     */
    public RequestHandle queueRequest(
            String url, WebAddress uri, String method, Map<String, String> headers,
            EventHandler eventHandler,
            InputStream bodyProvider, int bodyLength,
            boolean highPriority) {

        if (HttpLog.LOGV) HttpLog.v("RequestQueue.queueRequest " + uri);

        // Ensure there is an eventHandler set
        if (eventHandler == null) {
            eventHandler = new LoggingEventHandler();
        }

        /* Create and queue request */
        Request req;
        HttpHost httpHost = new HttpHost(uri.mHost, uri.mPort, uri.mScheme);

        // set up request
        req = new Request(method, httpHost, mProxyHost, uri.mPath, bodyProvider,
                          bodyLength, eventHandler, headers, highPriority);

        queueRequest(req, highPriority);

        mActivePool.mTotalRequest++;

        // dump();
        mActivePool.startConnectionThread();

        return new RequestHandle(
                this, url, uri, method, headers, bodyProvider, bodyLength,
                req);
    }

    /**
     * Called by the NetworkStateTracker -- updates when network connectivity
     * is lost/restored.
     *
     * If isNetworkConnected is true, start processing requests
     */
    public void setNetworkState(boolean isNetworkConnected) {
        if (HttpLog.LOGV) HttpLog.v("RequestQueue.setNetworkState() " + isNetworkConnected);
        mNetworkConnected = isNetworkConnected;
        if (isNetworkConnected)
            mActivePool.startConnectionThread();
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

        if (mNetworkConnected && !mPending.isEmpty()) {
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

        if (mNetworkConnected && mPending.containsKey(host)) {
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
        boolean isNetworkConnected();
        HttpHost getProxyHost();
        Connection getConnection(Context context, HttpHost host);
        boolean recycleConnection(HttpHost host, Connection connection);
    }
}
