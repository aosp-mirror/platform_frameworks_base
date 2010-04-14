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

import android.net.http.EventHandler;
import android.net.http.RequestHandle;
import android.util.Log;
import android.webkit.CacheManager.CacheResult;

import java.util.HashMap;
import java.util.Map;

class FrameLoader {

    private final LoadListener mListener;
    private final String mMethod;
    private final WebSettings mSettings;
    private Map<String, String> mHeaders;
    private byte[] mPostData;
    private Network mNetwork;
    private int mCacheMode;
    private String mReferrer;
    private String mContentType;

    private static final int URI_PROTOCOL = 0x100;

    private static final String CONTENT_TYPE = "content-type";

    // Contents of an about:blank page
    private static final String mAboutBlank =
            "<!DOCTYPE html PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EB\">" +
            "<html><head><title>about:blank</title></head><body></body></html>";

    static final String HEADER_STR = "text/xml, text/html, " +
            "application/xhtml+xml, image/png, text/plain, */*;q=0.8";

    private static final String LOGTAG = "webkit";
    
    FrameLoader(LoadListener listener, WebSettings settings,
            String method) {
        mListener = listener;
        mHeaders = null;
        mMethod = method;
        mCacheMode = WebSettings.LOAD_NORMAL;
        mSettings = settings;
    }

    public void setReferrer(String ref) {
        // only set referrer for http or https
        if (URLUtil.isNetworkUrl(ref)) mReferrer = ref;
    }

    public void setPostData(byte[] postData) {
        mPostData = postData;
    }

    public void setContentTypeForPost(String postContentType) {
        mContentType = postContentType;
    }

    public void setCacheMode(int cacheMode) {
        mCacheMode = cacheMode;
    }

    public void setHeaders(HashMap headers) {
        mHeaders = headers;
    }

    public LoadListener getLoadListener() {
        return mListener;
    }

    /**
     * Issues the load request.
     *
     * Return value does not indicate if the load was successful or not. It
     * simply indicates that the load request is reasonable.
     *
     * @return true if the load is reasonable.
     */
    public boolean executeLoad() {
        String url = mListener.url();

        if (URLUtil.isNetworkUrl(url)){
            if (mSettings.getBlockNetworkLoads()) {
                mListener.error(EventHandler.ERROR_BAD_URL,
                        mListener.getContext().getString(
                                com.android.internal.R.string.httpErrorBadUrl));
                return false;
            }
            // Make sure the host part of the url is correctly
            // encoded before sending the request
            if (!URLUtil.verifyURLEncoding(mListener.host())) {
                mListener.error(EventHandler.ERROR_BAD_URL,
                        mListener.getContext().getString(
                        com.android.internal.R.string.httpErrorBadUrl));
                return false;
            }
            mNetwork = Network.getInstance(mListener.getContext());
            if (mListener.isSynchronous()) {
                return handleHTTPLoad();
            }
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_ADD_HTTPLOADER, this).sendToTarget();
            return true;
        } else if (handleLocalFile(url, mListener, mSettings)) {
            return true;
        }
        if (DebugFlags.FRAME_LOADER) {
            Log.v(LOGTAG, "FrameLoader.executeLoad: url protocol not supported:"
                    + mListener.url());
        }
        mListener.error(EventHandler.ERROR_UNSUPPORTED_SCHEME,
                mListener.getContext().getText(
                        com.android.internal.R.string.httpErrorUnsupportedScheme).toString());
        return false;

    }

    /* package */
    static boolean handleLocalFile(String url, LoadListener loadListener,
            WebSettings settings) {
        // Attempt to decode the percent-encoded url before passing to the
        // local loaders.
        try {
            url = new String(URLUtil.decode(url.getBytes()));
        } catch (IllegalArgumentException e) {
            loadListener.error(EventHandler.ERROR_BAD_URL,
                    loadListener.getContext().getString(
                            com.android.internal.R.string.httpErrorBadUrl));
            // Return true here so we do not trigger an unsupported scheme
            // error.
            return true;
        }
        if (URLUtil.isAssetUrl(url)) {
            if (loadListener.isSynchronous()) {
                new FileLoader(url, loadListener, FileLoader.TYPE_ASSET,
                        true).load();
            } else {
                // load asset in a separate thread as it involves IO
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_ADD_STREAMLOADER,
                        new FileLoader(url, loadListener, FileLoader.TYPE_ASSET,
                                true)).sendToTarget();
            }
            return true;
        } else if (URLUtil.isResourceUrl(url)) {
            if (loadListener.isSynchronous()) {
                new FileLoader(url, loadListener, FileLoader.TYPE_RES,
                        true).load();
            } else {
                // load resource in a separate thread as it involves IO
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_ADD_STREAMLOADER,
                        new FileLoader(url, loadListener, FileLoader.TYPE_RES,
                                true)).sendToTarget();
            }
            return true;
        } else if (URLUtil.isFileUrl(url)) {
            if (loadListener.isSynchronous()) {
                new FileLoader(url, loadListener, FileLoader.TYPE_FILE,
                        settings.getAllowFileAccess()).load();
            } else {
                // load file in a separate thread as it involves IO
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_ADD_STREAMLOADER,
                        new FileLoader(url, loadListener, FileLoader.TYPE_FILE,
                                settings.getAllowFileAccess())).sendToTarget();
            }
            return true;
        } else if (URLUtil.isContentUrl(url)) {
            // Send the raw url to the ContentLoader because it will do a
            // permission check and the url has to match.
            if (loadListener.isSynchronous()) {
                new ContentLoader(loadListener.url(), loadListener).load();
            } else {
                // load content in a separate thread as it involves IO
                WebViewWorker.getHandler().obtainMessage(
                        WebViewWorker.MSG_ADD_STREAMLOADER,
                        new ContentLoader(loadListener.url(), loadListener))
                        .sendToTarget();
            }
            return true;
        } else if (URLUtil.isDataUrl(url)) {
            // load data in the current thread to reduce the latency
            new DataLoader(url, loadListener).load();
            return true;
        } else if (URLUtil.isAboutUrl(url)) {
            loadListener.data(mAboutBlank.getBytes(), mAboutBlank.length());
            loadListener.endData();
            return true;
        }
        return false;
    }

    boolean handleHTTPLoad() {
        if (mHeaders == null) {
            mHeaders = new HashMap<String, String>();
        }
        populateStaticHeaders();
        populateHeaders();

        // response was handled by Cache, don't issue HTTP request
        if (handleCache()) {
            // push the request data down to the LoadListener
            // as response from the cache could be a redirect
            // and we may need to initiate a network request if the cache
            // can't satisfy redirect URL
            mListener.setRequestData(mMethod, mHeaders, mPostData);
            return true;
        }

        if (DebugFlags.FRAME_LOADER) {
            Log.v(LOGTAG, "FrameLoader: http " + mMethod + " load for: "
                    + mListener.url());
        }

        boolean ret = false;
        int error = EventHandler.ERROR_UNSUPPORTED_SCHEME;
        
        try {
            ret = mNetwork.requestURL(mMethod, mHeaders,
                    mPostData, mListener);
        } catch (android.net.ParseException ex) {
            error = EventHandler.ERROR_BAD_URL;
        } catch (java.lang.RuntimeException ex) {
            /* probably an empty header set by javascript.  We want
               the same result as bad URL  */
            error = EventHandler.ERROR_BAD_URL;
        }
        if (!ret) {
            mListener.error(error, mListener.getContext().getText(
                    EventHandler.errorStringResources[Math.abs(error)]).toString());
            return false;
        }
        return true;
    }

    /*
     * This function is used by handleCache to
     * setup a load from the byte stream in a CacheResult.
     */
    private void startCacheLoad(CacheResult result) {
        if (DebugFlags.FRAME_LOADER) {
            Log.v(LOGTAG, "FrameLoader: loading from cache: "
                  + mListener.url());
        }
        // Tell the Listener respond with the cache file
        CacheLoader cacheLoader =
                new CacheLoader(mListener, result);
        mListener.setCacheLoader(cacheLoader);
        if (mListener.isSynchronous()) {
            cacheLoader.load();
        } else {
            // Load the cached file in a separate thread
            WebViewWorker.getHandler().obtainMessage(
                    WebViewWorker.MSG_ADD_STREAMLOADER, cacheLoader).sendToTarget();
        }
    }

    /*
     * This function is used by the handleHTTPLoad to setup the cache headers
     * correctly.
     * Returns true if the response was handled from the cache
     */
    private boolean handleCache() {
        switch (mCacheMode) {
            // This mode is normally used for a reload, it instructs the http
            // loader to not use the cached content.
            case WebSettings.LOAD_NO_CACHE:
                break;
                
                
            // This mode is used when the content should only be loaded from
            // the cache. If it is not there, then fail the load. This is used
            // to load POST content in a history navigation.
            case WebSettings.LOAD_CACHE_ONLY: {
                CacheResult result = CacheManager.getCacheFile(mListener.url(),
                        mListener.postIdentifier(), null);
                if (result != null) {
                    startCacheLoad(result);
                } else {
                    // This happens if WebCore was first told that the POST
                    // response was in the cache, then when we try to use it
                    // it has gone.
                    // Generate a file not found error
                    int err = EventHandler.FILE_NOT_FOUND_ERROR;
                    mListener.error(err, mListener.getContext().getText(
                            EventHandler.errorStringResources[Math.abs(err)])
                            .toString());
                }
                return true;
            }

            // This mode is for when the user is doing a history navigation
            // in the browser and should returned cached content regardless
            // of it's state. If it is not in the cache, then go to the 
            // network.
            case WebSettings.LOAD_CACHE_ELSE_NETWORK: {
                if (DebugFlags.FRAME_LOADER) {
                    Log.v(LOGTAG, "FrameLoader: checking cache: "
                            + mListener.url());
                }
                // Get the cache file name for the current URL, passing null for
                // the validation headers causes no validation to occur
                CacheResult result = CacheManager.getCacheFile(mListener.url(),
                        mListener.postIdentifier(), null);
                if (result != null) {
                    startCacheLoad(result);
                    return true;
                }
                break;
            }

            // This is the default case, which is to check to see if the
            // content in the cache can be used. If it can be used, then
            // use it. If it needs revalidation then the relevant headers
            // are added to the request.
            default:
            case WebSettings.LOAD_NORMAL:
                return mListener.checkCache(mHeaders);
        }// end of switch

        return false;
    }
    
    /**
     * Add the static headers that don't change with each request.
     */
    private void populateStaticHeaders() {
        // Accept header should already be there as they are built by WebCore,
        // but in the case they are missing, add some.
        String accept = mHeaders.get("Accept");
        if (accept == null || accept.length() == 0) {
            mHeaders.put("Accept", HEADER_STR);
        }
        mHeaders.put("Accept-Charset", "utf-8, iso-8859-1, utf-16, *;q=0.7");

        String acceptLanguage = mSettings.getAcceptLanguage();
        if (acceptLanguage.length() > 0) {
            mHeaders.put("Accept-Language", acceptLanguage);
        }
        
        mHeaders.put("User-Agent", mSettings.getUserAgentString());
    }

    /**
     * Add the content related headers. These headers contain user private data
     * and is not used when we are proxying an untrusted request.
     */
    private void populateHeaders() {
        
        if (mReferrer != null) mHeaders.put("Referer", mReferrer);
        if (mContentType != null) mHeaders.put(CONTENT_TYPE, mContentType);

        // if we have an active proxy and have proxy credentials, do pre-emptive
        // authentication to avoid an extra round-trip:
        if (mNetwork.isValidProxySet()) {
            String username;
            String password;
            /* The proxy credentials can be set in the Network thread */
            synchronized (mNetwork) {
                username = mNetwork.getProxyUsername();
                password = mNetwork.getProxyPassword();
            }
            if (username != null && password != null) {
                // we collect credentials ONLY if the proxy scheme is BASIC!!!
                String proxyHeader = RequestHandle.authorizationHeader(true);
                mHeaders.put(proxyHeader,
                        "Basic " + RequestHandle.computeBasicAuthResponse(
                                username, password));
            }
        }

        // Set cookie header
        String cookie = CookieManager.getInstance().getCookie(
                mListener.getWebAddress());
        if (cookie != null && cookie.length() > 0) {
            mHeaders.put("Cookie", cookie);
        }
    }
}
