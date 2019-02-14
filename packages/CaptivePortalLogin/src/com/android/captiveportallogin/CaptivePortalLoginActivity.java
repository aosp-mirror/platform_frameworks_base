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

import static android.net.ConnectivityManager.EXTRA_CAPTIVE_PORTAL_PROBE_SPEC;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.CaptivePortal;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Proxy;
import android.net.Uri;
import android.net.captiveportal.CaptivePortalProbeSpec;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.SystemProperties;
import android.support.v4.widget.SwipeRefreshLayout;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = CaptivePortalLoginActivity.class.getSimpleName();
    private static final boolean DBG = true;
    private static final boolean VDBG = false;

    private static final int SOCKET_TIMEOUT_MS = 10000;
    public static final String HTTP_LOCATION_HEADER_NAME = "Location";

    private enum Result {
        DISMISSED(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_DISMISSED),
        UNWANTED(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_UNWANTED),
        WANTED_AS_IS(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_RESULT_WANTED_AS_IS);

        final int metricsEvent;
        Result(int metricsEvent) { this.metricsEvent = metricsEvent; }
    };

    private URL mUrl;
    private CaptivePortalProbeSpec mProbeSpec;
    private String mUserAgent;
    private Network mNetwork;
    private CaptivePortal mCaptivePortal;
    private NetworkCallback mNetworkCallback;
    private ConnectivityManager mCm;
    private WifiManager mWifiManager;
    private boolean mLaunchBrowser = false;
    private MyWebViewClient mWebViewClient;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    // Ensures that done() happens once exactly, handling concurrent callers with atomic operations.
    private final AtomicBoolean isDone = new AtomicBoolean(false);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mCaptivePortal = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);
        logMetricsEvent(MetricsEvent.ACTION_CAPTIVE_PORTAL_LOGIN_ACTIVITY);

        mCm = getSystemService(ConnectivityManager.class);
        mWifiManager = getSystemService(WifiManager.class);
        mNetwork = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
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

        final String spec = getIntent().getStringExtra(EXTRA_CAPTIVE_PORTAL_PROBE_SPEC);
        try {
            mProbeSpec = CaptivePortalProbeSpec.parseSpecOrNull(spec);
        } catch (Exception e) {
            // Make extra sure that invalid configurations do not cause crashes
            mProbeSpec = null;
        }

        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network lostNetwork) {
                // If the network disappears while the app is up, exit.
                if (mNetwork.equals(lostNetwork)) done(Result.UNWANTED);
            }
        };
        mCm.registerNetworkCallback(new NetworkRequest.Builder().build(), mNetworkCallback);

        // If the network has disappeared, exit.
        final NetworkCapabilities networkCapabilities = mCm.getNetworkCapabilities(mNetwork);
        if (networkCapabilities == null) {
            finishAndRemoveTask();
            return;
        }

        // Also initializes proxy system properties.
        mNetwork = mNetwork.getPrivateDnsBypassingCopy();
        mCm.bindProcessToNetwork(mNetwork);

        // Proxy system properties must be initialized before setContentView is called because
        // setContentView initializes the WebView logic which in turn reads the system properties.
        setContentView(R.layout.activity_captive_portal_login);

        getActionBar().setDisplayShowHomeEnabled(false);
        getActionBar().setElevation(0); // remove shadow
        getActionBar().setTitle(getHeaderTitle());
        getActionBar().setSubtitle("");

        final WebView webview = getWebview();
        webview.clearCache(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true);
        WebSettings webSettings = webview.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        webSettings.setDomStorageEnabled(true);
        mWebViewClient = new MyWebViewClient();
        webview.setWebViewClient(mWebViewClient);
        webview.setWebChromeClient(new MyWebChromeClient());
        // Start initial page load so WebView finishes loading proxy settings.
        // Actual load of mUrl is initiated by MyWebViewClient.
        webview.loadData("", "text/html", null);

        mSwipeRefreshLayout = findViewById(R.id.swipe_refresh);
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
                webview.reload();
                mSwipeRefreshLayout.setRefreshing(true);
            });

    }

    // Find WebView's proxy BroadcastReceiver and prompt it to read proxy system properties.
    private void setWebViewProxy() {
        // TODO: migrate to androidx WebView proxy setting API as soon as it is finalized
        try {
            final Field loadedApkField = Application.class.getDeclaredField("mLoadedApk");
            final Class<?> loadedApkClass = loadedApkField.getType();
            final Object loadedApk = loadedApkField.get(getApplication());
            Field receiversField = loadedApkClass.getDeclaredField("mReceivers");
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
        final WebView webview = (WebView) findViewById(R.id.webview);
        if (webview != null) {
            webview.stopLoading();
            webview.setWebViewClient(null);
            webview.setWebChromeClient(null);
            webview.destroy();
        }
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
        return isDebuggable() ? Objects.toString(url) : host(url);
    }

    private static boolean isDebuggable() {
        return SystemProperties.getInt("ro.debuggable", 0) == 1;
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
                String locationHeader = null;
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
                    locationHeader = urlConnection.getHeaderField(HTTP_LOCATION_HEADER_NAME);
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
                if (isDismissed(httpResponseCode, locationHeader, mProbeSpec)) {
                    done(Result.DISMISSED);
                }
            }
        }).start();
    }

    private static boolean isDismissed(
            int httpResponseCode, String locationHeader, CaptivePortalProbeSpec probeSpec) {
        return (probeSpec != null)
                ? probeSpec.getResult(httpResponseCode, locationHeader).isSuccessful()
                : (httpResponseCode == 204);
    }

    private class MyWebViewClient extends WebViewClient {
        private static final String INTERNAL_ASSETS = "file:///android_asset/";

        private final String mBrowserBailOutToken = Long.toString(new Random().nextLong());
        private final String mCertificateOutToken = Long.toString(new Random().nextLong());
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

        private String mSslErrorTitle = null;
        private SslErrorHandler mSslErrorHandler = null;
        private SslError mSslError = null;

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
            Log.d(TAG, "onPageStarted: " + sanitizeURL(url));
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
            mSwipeRefreshLayout.setRefreshing(false);
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
                    sslErrorName(error), sanitizeURL(url), error.getCertificate()));
            if (url == null || !Objects.equals(host, mHostname)) {
                // Ignore ssl errors for resources coming from a different hostname than the page
                // that we are currently loading, and only cancel the request.
                handler.cancel();
                return;
            }
            logMetricsEvent(MetricsEvent.CAPTIVE_PORTAL_LOGIN_ACTIVITY_SSL_ERROR);
            final String sslErrorPage = makeSslErrorPage();
            view.loadDataWithBaseURL(INTERNAL_ASSETS, sslErrorPage, "text/HTML", "UTF-8", null);
            mSslErrorTitle = view.getTitle() == null ? "" : view.getTitle();
            mSslErrorHandler = handler;
            mSslError = error;
        }

        private String makeSslErrorPage() {
            final String warningMsg = getString(R.string.ssl_error_warning);
            final String exampleMsg = getString(R.string.ssl_error_example);
            final String continueMsg = getString(R.string.ssl_error_continue);
            final String certificateMsg = getString(R.string.ssl_error_view_certificate);
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
                    "    a.certificate {",
                    "      margin-top:0px;",
                    "    }",
                    "  </style>",
                    "</head>",
                    "<body>",
                    "  <p><img src=quantum_ic_warning_amber_96.png><br>",
                    "  <div class=warn>" + warningMsg + "</div>",
                    "  <div class=example>" + exampleMsg + "</div>",
                    "  <a href=" + mBrowserBailOutToken + ">" + continueMsg + "</a><br>",
                    "  <a class=certificate href=" + mCertificateOutToken + ">" + certificateMsg +
                            "</a>",
                    "</body>",
                    "</html>");
        }

        @Override
        public boolean shouldOverrideUrlLoading (WebView view, String url) {
            if (url.startsWith("tel:")) {
                startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                return true;
            }
            if (url.contains(mCertificateOutToken) && mSslError != null) {
                showSslAlertDialog(mSslErrorHandler, mSslError, mSslErrorTitle);
                return true;
            }
            return false;
        }
        private void showSslAlertDialog(SslErrorHandler handler, SslError error, String title) {
            final LayoutInflater factory = LayoutInflater.from(CaptivePortalLoginActivity.this);
            final View sslWarningView = factory.inflate(R.layout.ssl_warning, null);

            // Set Security certificate
            setViewSecurityCertificate(sslWarningView.findViewById(R.id.certificate_layout), error);
            ((TextView) sslWarningView.findViewById(R.id.ssl_error_type))
                    .setText(sslErrorName(error));
            ((TextView) sslWarningView.findViewById(R.id.title)).setText(mSslErrorTitle);
            ((TextView) sslWarningView.findViewById(R.id.address)).setText(error.getUrl());

            AlertDialog sslAlertDialog = new AlertDialog.Builder(CaptivePortalLoginActivity.this)
                    .setTitle(R.string.ssl_security_warning_title)
                    .setView(sslWarningView)
                    .setPositiveButton(R.string.ok, (DialogInterface dialog, int whichButton) -> {
                        // handler.cancel is called via OnCancelListener.
                        dialog.cancel();
                    })
                    .setOnCancelListener((DialogInterface dialogInterface) -> handler.cancel())
                    .create();
            sslAlertDialog.show();
        }

        private void setViewSecurityCertificate(LinearLayout certificateLayout, SslError error) {
            ((TextView) certificateLayout.findViewById(R.id.ssl_error_msg))
                    .setText(sslErrorMessage(error));
            SslCertificate cert = error.getCertificate();
            // TODO: call the method directly once inflateCertificateView is @SystemApi
            try {
                final View certificateView = (View) SslCertificate.class.getMethod(
                        "inflateCertificateView", Context.class)
                        .invoke(cert, CaptivePortalLoginActivity.this);
                certificateLayout.addView(certificateView);
            } catch (ReflectiveOperationException | SecurityException e) {
                Log.e(TAG, "Could not create certificate view", e);
            }
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
        NetworkCapabilities nc = mCm.getNetworkCapabilities(mNetwork);
        final String ssid = getSsid();
        if (TextUtils.isEmpty(ssid)
                || nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getString(R.string.action_bar_label);
        }
        return getString(R.string.action_bar_title, ssid);
    }

    // TODO: remove once SSID is obtained from NetworkCapabilities
    private String getSsid() {
        if (mWifiManager == null) {
            return null;
        }
        final WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        return removeDoubleQuotes(wifiInfo.getSSID());
    }

    private static String removeDoubleQuotes(String string) {
        if (string == null) return null;
        final int length = string.length();
        if ((length > 1) && (string.charAt(0) == '"') && (string.charAt(length - 1) == '"')) {
            return string.substring(1, length - 1);
        }
        return string;
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
        mCaptivePortal.logEvent(event, getPackageName());
    }

    private static final SparseArray<String> SSL_ERRORS = new SparseArray<>();
    static {
        SSL_ERRORS.put(SslError.SSL_NOTYETVALID,  "SSL_NOTYETVALID");
        SSL_ERRORS.put(SslError.SSL_EXPIRED,      "SSL_EXPIRED");
        SSL_ERRORS.put(SslError.SSL_IDMISMATCH,   "SSL_IDMISMATCH");
        SSL_ERRORS.put(SslError.SSL_UNTRUSTED,    "SSL_UNTRUSTED");
        SSL_ERRORS.put(SslError.SSL_DATE_INVALID, "SSL_DATE_INVALID");
        SSL_ERRORS.put(SslError.SSL_INVALID,      "SSL_INVALID");
    }

    private static String sslErrorName(SslError error) {
        return SSL_ERRORS.get(error.getPrimaryError(), "UNKNOWN");
    }

    private static final SparseArray<Integer> SSL_ERROR_MSGS = new SparseArray<>();
    static {
        SSL_ERROR_MSGS.put(SslError.SSL_NOTYETVALID,  R.string.ssl_error_not_yet_valid);
        SSL_ERROR_MSGS.put(SslError.SSL_EXPIRED,      R.string.ssl_error_expired);
        SSL_ERROR_MSGS.put(SslError.SSL_IDMISMATCH,   R.string.ssl_error_mismatch);
        SSL_ERROR_MSGS.put(SslError.SSL_UNTRUSTED,    R.string.ssl_error_untrusted);
        SSL_ERROR_MSGS.put(SslError.SSL_DATE_INVALID, R.string.ssl_error_date_invalid);
        SSL_ERROR_MSGS.put(SslError.SSL_INVALID,      R.string.ssl_error_invalid);
    }

    private static Integer sslErrorMessage(SslError error) {
        return SSL_ERROR_MSGS.get(error.getPrimaryError(), R.string.ssl_error_unknown);
    }
}
