/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.captiveportallogin;

import android.app.Activity;
import android.app.LoadedApk;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Proxy;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.lang.InterruptedException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = CaptivePortalLoginActivity.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int SOCKET_TIMEOUT_MS = 10000;

    private enum Result {
        DISMISSED(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_DISMISSED),
        UNWANTED(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_UNWANTED),
        WANTED_AS_IS(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_WANTED_AS_IS);

        final int metricsEvent;
        Result(int metricsEvent) { this.metricsEvent = metricsEvent; }
    };

    private URL mUrl;
    private String mUserAgent;
    private Network mNetwork;
    private CaptivePortal mCaptivePortal;
    private NetworkCallback mNetworkCallback;
    private ConnectivityManager mCm;
    private boolean mLaunchBrowser = false;
    private MyWebViewClient mWebViewClient;
    // Ensures that done() happens once exactly, handling concurrent callers with atomic operations.
    private final AtomicBoolean isDone = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logMetricsEvent(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_ACTIVITY);

        mCm = ConnectivityManager.from(this);
        mNetwork = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        mCaptivePortal = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
        mUserAgent =
                getIntent().getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_USER_AGENT);
        mUrl = getUrl();
        if (mUrl == null) {
            // getUrl() failed to parse the url provided in the intent: bail out in a way that
            // at least provides network access.
            done(Result.WANTED_AS_IS);
            return;
        }
        if (DBG) {
            Log.d(TAG, String.format("onCreate for %s", mUrl.toString()));
        }

        // Also initializes proxy system properties.
        mCm.bindProcessToNetwork(mNetwork);

        // Proxy system properties must be initialized before setContentView is called because
        // setContentView initializes the WebView logic which in turn reads the system properties.
        setContentView(R.layout.activity_captive_portal_login);

        // Exit app if Network disappears.
        final NetworkCapabilities networkCapabilities = mCm.getNetworkCapabilities(mNetwork);
        if (networkCapabilities == null) {
            finishAndRemoveTask();
            return;
        }
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network lostNetwork) {
                if (mNetwork.equals(lostNetwork)) done(Result.UNWANTED);
            }
        };
        final NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (int transportType : networkCapabilities.getTransportTypes()) {
            builder.addTransportType(transportType);
        }
        mCm.registerNetworkCallback(builder.build(), mNetworkCallback);

        getActionBar().setDisplayShowHomeEnabled(false);
        getActionBar().setElevation(0); // remove shadow
        getActionBar().setTitle(getHeaderTitle());
        getActionBar().setSubtitle("");

        final WebView webview = getWebview();
        webview.clearCache(true);
        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        mWebViewClient = new MyWebViewClient();
        webview.setWebViewClient(mWebViewClient);
        webview.setWebChromeClient(new MyWebChromeClient());
        // Start initial page load so WebView finishes loading proxy settings.
        // Actual load of mUrl is initiated by MyWebViewClient.
        webview.loadData("", "text/html", null);
    }

    // Find WebView's proxy BroadcastReceiver and prompt it to read proxy system properties.
    private void setWebViewProxy() {
        LoadedApk loadedApk = getApplication().mLoadedApk;
        try {
            Field receiversField = LoadedApk.class.getDeclaredField("mReceivers");
            receiversField.setAccessible(true);
            ArrayMap receivers = (ArrayMap) receiversField.get(loadedApk);
            for (Object receiverMap : receivers.values()) {
                for (Object rec : ((ArrayMap) receiverMap).keySet()) {
                    Class clazz = rec.getClass();
                    if (clazz.getName().contains("ProxyChangeListener")) {
                        Method onReceiveMethod = clazz.getDeclaredMethod("onReceive", Context.class,
                                Intent.class);
                        Intent intent = new Intent(Proxy.PROXY_CHANGE_ACTION);
                        onReceiveMethod.invoke(rec, getApplicationContext(), intent);
                        Log.v(TAG, "Prompting WebView proxy reload.");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception while setting WebView proxy: " + e);
        }
    }

    private void done(Result result) {
        if (isDone.getAndSet(true)) {
            // isDone was already true: done() already called
            return;
        }
        if (DBG) {
            Log.d(TAG, String.format("Result %s for %s", result.name(), mUrl.toString()));
        }
        logMetricsEvent(result.metricsEvent);
        switch (result) {
            case DISMISSED:
                mCaptivePortal.reportCaptivePortalDismissed();
                break;
            case UNWANTED:
                mCaptivePortal.ignoreNetwork();
                break;
            case WANTED_AS_IS:
                mCaptivePortal.useNetwork();
                break;
        }
        finishAndRemoveTask();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.captive_portal_login, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        WebView myWebView = findViewById(R.id.webview);
        if (myWebView.canGoBack() && mWebViewClient.allowBack()) {
            myWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final Result result;
        final String action;
        final int id = item.getItemId();
        switch (id) {
            case R.id.action_use_network:
                result = Result.WANTED_AS_IS;
                action = "USE_NETWORK";
                break;
            case R.id.action_do_not_use_network:
                result = Result.UNWANTED;
                action = "DO_NOT_USE_NETWORK";
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        if (DBG) {
            Log.d(TAG, String.format("onOptionsItemSelect %s for %s", action, mUrl.toString()));
        }
        done(result);
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mNetworkCallback != null) {
            // mNetworkCallback is not null if mUrl is not null.
            mCm.unregisterNetworkCallback(mNetworkCallback);
        }
        if (mLaunchBrowser) {
            // Give time for this network to become default. After 500ms just proceed.
            for (int i = 0; i < 5; i++) {
                // TODO: This misses when mNetwork underlies a VPN.
                if (mNetwork.equals(mCm.getActiveNetwork())) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                }
            }
            final String url = mUrl.toString();
            if (DBG) {
                Log.d(TAG, "starting activity with intent ACTION_VIEW for " + url);
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
    }

    private URL getUrl() {
        String url = getIntent().getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);
        if (url == null) {
            url = mCm.getCaptivePortalServerUrl();
        }
        return makeURL(url);
    }

    private static URL makeURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            Log.e(TAG, "Invalid URL " + url);
        }
        return null;
    }

    private static String host(URL url) {
        if (url == null) {
            return null;
        }
        return url.getHost();
    }

    private static String sanitizeURL(URL url) {
        // In non-Debug build, only show host to avoid leaking private info.
        return Build.IS_DEBUGGABLE ? Objects.toString(url) : host(url);
    }

    private void testForCaptivePortal() {
        // TODO: reuse NetworkMonitor facilities for consistent captive portal detection.
        new Thread(new Runnable() {
            public void run() {
                // Give time for captive portal to open.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                HttpURLConnection urlConnection = null;
                int httpResponseCode = 500;
                try {
                    urlConnection = (HttpURLConnection) mNetwork.openConnection(mUrl);
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setUseCaches(false);
                    if (mUserAgent != null) {
                       urlConnection.setRequestProperty("User-Agent", mUserAgent);
                    }
                    // cannot read request header after connection
                    String requestHeader = urlConnection.getRequestProperties().toString();

                    urlConnection.getInputStream();
                    httpResponseCode = urlConnection.getResponseCode();
                    if (DBG) {
                        Log.d(TAG, "probe at " + mUrl +
                                " ret=" + httpResponseCode +
                                " request=" + requestHeader +
                                " headers=" + urlConnection.getHeaderFields());
                    }
                } catch (IOException e) {
                } finally {
                    if (urlConnection != null) urlConnection.disconnect();
                }
                if (httpResponseCode == 204) {
                    done(Result.DISMISSED);
                }
            }
        }).start();
    }

    private class MyWebViewClient extends WebViewClient {
        private static final String INTERNAL_ASSETS = "file:///android_asset/";

        private final String mBrowserBailOutToken = Long.toString(new Random().nextLong());
        // How many Android device-independent-pixels per scaled-pixel
        // dp/sp = (px/sp) / (px/dp) = (1/sp) / (1/dp)
        private final float mDpPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                    getResources().getDisplayMetrics()) /
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                    getResources().getDisplayMetrics());
        private int mPagesLoaded;
        // the host of the page that this webview is currently loading. Can be null when undefined.
        private String mHostname;

        // If we haven't finished cleaning up the history, don't allow going back.
        public boolean allowBack() {
            return mPagesLoaded > 1;
        }

        @Override
        public void onPageStarted(WebView view, String urlString, Bitmap favicon) {
            if (urlString.contains(mBrowserBailOutToken)) {
                mLaunchBrowser = true;
                done(Result.WANTED_AS_IS);
                return;
            }
            // The first page load is used only to cause the WebView to
            // fetch the proxy settings.  Don't update the URL bar, and
            // don't check if the captive portal is still there.
            if (mPagesLoaded == 0) {
                return;
            }
            final URL url = makeURL(urlString);
            Log.d(TAG, "onPageSarted: " + sanitizeURL(url));
            mHostname = host(url);
            // For internally generated pages, leave URL bar listing prior URL as this is the URL
            // the page refers to.
            if (!urlString.startsWith(INTERNAL_ASSETS)) {
                String subtitle = (url != null) ? getHeaderSubtitle(url) : urlString;
                getActionBar().setSubtitle(subtitle);
            }
            getProgressBar().setVisibility(View.VISIBLE);
            testForCaptivePortal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mPagesLoaded++;
            getProgressBar().setVisibility(View.INVISIBLE);
            if (mPagesLoaded == 1) {
                // Now that WebView has loaded at least one page we know it has read in the proxy
                // settings.  Now prompt the WebView read the Network-specific proxy settings.
                setWebViewProxy();
                // Load the real page.
                view.loadUrl(mUrl.toString());
                return;
            } else if (mPagesLoaded == 2) {
                // Prevent going back to empty first page.
                // Fix for missing focus, see b/62449959 for details. Remove it once we get a
                // newer version of WebView (60.x.y).
                view.requestFocus();
                view.clearHistory();
            }
            testForCaptivePortal();
        }

        // Convert Android scaled-pixels (sp) to HTML size.
        private String sp(int sp) {
            // Convert sp to dp's.
            float dp = sp * mDpPerSp;
            // Apply a scale factor to make things look right.
            dp *= 1.3;
            // Convert dp's to HTML size.
            // HTML px's are scaled just like dp's, so just add "px" suffix.
            return Integer.toString((int)dp) + "px";
        }

        // A web page consisting of a large broken lock icon to indicate SSL failure.

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            final URL url = makeURL(error.getUrl());
            final String host = host(url);
            Log.d(TAG, String.format("SSL error: %s, url: %s, certificate: %s",
                    error.getPrimaryError(), sanitizeURL(url), error.getCertificate()));
            if (url == null || !Objects.equals(host, mHostname)) {
                // Ignore ssl errors for resources coming from a different hostname than the page
                // that we are currently loading, and only cancel the request.
                handler.cancel();
                return;
            }
            logMetricsEvent(MetricsEvent.CAPTIVE_PORTAL_LOGIN_ACTIVITY_SSL_ERROR);
            final String sslErrorPage = makeSslErrorPage();
            view.loadDataWithBaseURL(INTERNAL_ASSETS, sslErrorPage, "text/HTML", "UTF-8", null);
        }

        private String makeSslErrorPage() {
            final String warningMsg = getString(R.string.ssl_error_warning);
            final String exampleMsg = getString(R.string.ssl_error_example);
            final String continueMsg = getString(R.string.ssl_error_continue);
            return String.join("\n",
                    "<html>",
                    "<head>",
                    "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
                    "  <style>",
                    "    body {",
                    "      background-color:#fafafa;",
                    "      margin:auto;",
                    "      width:80%;",
                    "      margin-top: 96px",
                    "    }",
                    "    img {",
                    "      height:48px;",
                    "      width:48px;",
                    "    }",
                    "    div.warn {",
                    "      font-size:" + sp(16) + ";",
                    "      line-height:1.28;",
                    "      margin-top:16px;",
                    "      opacity:0.87;",
                    "    }",
                    "    div.example {",
                    "      font-size:" + sp(14) + ";",
                    "      line-height:1.21905;",
                    "      margin-top:16px;",
                    "      opacity:0.54;",
                    "    }",
                    "    a {",
                    "      color:#4285F4;",
                    "      display:inline-block;",
                    "      font-size:" + sp(14) + ";",
                    "      font-weight:bold;",
                    "      height:48px;",
                    "      margin-top:24px;",
                    "      text-decoration:none;",
                    "      text-transform:uppercase;",
                    "    }",
                    "  </style>",
                    "</head>",
                    "<body>",
                    "  <p><img src=quantum_ic_warning_amber_96.png><br>",
                    "  <div class=warn>" + warningMsg + "</div>",
                    "  <div class=example>" + exampleMsg + "</div>",
                    "  <a href=" + mBrowserBailOutToken + ">" + continueMsg + "</a>",
                    "</body>",
                    "</html>");
        }

        @Override
        public boolean shouldOverrideUrlLoading (WebView view, String url) {
            if (url.startsWith("tel:")) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                return true;
            }
            return false;
        }
    }

    private class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            getProgressBar().setProgress(newProgress);
        }
    }

    private ProgressBar getProgressBar() {
        return findViewById(R.id.progress_bar);
    }

    private WebView getWebview() {
        return findViewById(R.id.webview);
    }

    private String getHeaderTitle() {
        NetworkInfo info = mCm.getNetworkInfo(mNetwork);
        if (info == null) {
            return getString(R.string.action_bar_label);
        }
        NetworkCapabilities nc = mCm.getNetworkCapabilities(mNetwork);
        if (!nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getString(R.string.action_bar_label);
        }
        return getString(R.string.action_bar_title, info.getExtraInfo().replaceAll("^\"|\"$", ""));
    }

    private String getHeaderSubtitle(URL url) {
        String host = host(url);
        final String https = "https";
        if (https.equals(url.getProtocol())) {
            return https + "://" + host;
        }
        return host;
    }

    private void logMetricsEvent(int event) {
        MetricsLogger.action(this, event, getPackageName());
    }
}
