/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.carrierdefaultapp;

import android.app.Activity;
import android.app.LoadedApk;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Proxy;
import android.net.TrafficStats;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TypedValue;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.util.ArrayUtils;
import com.android.net.module.util.NetworkStackConstants;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Random;

/**
 * Activity that launches in response to the captive portal notification
 * @see com.android.carrierdefaultapp.CarrierActionUtils#CARRIER_ACTION_SHOW_PORTAL_NOTIFICATION
 * This activity requests network connection if there is no available one before loading the real
 * portal page and apply carrier actions on the portal activation result.
 */
public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = CaptivePortalLoginActivity.class.getSimpleName();
    private static final boolean DBG = true;

    private static final int SOCKET_TIMEOUT_MS = 10 * 1000;
    private static final int NETWORK_REQUEST_TIMEOUT_MS = 5 * 1000;

    private URL mUrl;
    private Network mNetwork;
    private NetworkCallback mNetworkCallback;
    private ConnectivityManager mCm;
    private WebView mWebView;
    private MyWebViewClient mWebViewClient;
    private boolean mLaunchBrowser = false;
    private Thread mTestingThread = null;
    private boolean mReload = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCm = ConnectivityManager.from(this);
        mUrl = getUrlForCaptivePortal();
        if (mUrl == null) {
            done(false);
            return;
        }
        if (DBG) logd(String.format("onCreate for %s", mUrl.toString()));
        setContentView(R.layout.activity_captive_portal_login);
        getActionBar().setDisplayShowHomeEnabled(false);

        mWebView = findViewById(R.id.webview);
        mWebView.clearCache(true);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(false);
        mWebViewClient = new MyWebViewClient();
        mWebView.setWebViewClient(mWebViewClient);
        mWebView.setWebChromeClient(new MyWebChromeClient());

        final Network network = getNetworkForCaptivePortal();
        if (network == null) {
            requestNetworkForCaptivePortal();
        } else {
            setNetwork(network);
            // Start initial page load so WebView finishes loading proxy settings.
            // Actual load of mUrl is initiated by MyWebViewClient.
            mWebView.loadData("", "text/html", null);
        }
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
    public void onDestroy() {
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
            if (DBG) logd("starting activity with intent ACTION_VIEW for " + url);
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }

        if (mTestingThread != null) {
            mTestingThread.interrupt();
        }
        mWebView.destroy();
        releaseNetworkRequest();
        super.onDestroy();
    }

    private void setNetwork(Network network) {
        if (network != null) {
            network = network.getPrivateDnsBypassingCopy();
            mCm.bindProcessToNetwork(network);
            mCm.setProcessDefaultNetworkForHostResolution(network);
        }
        mNetwork = network;
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
            loge("Exception while setting WebView proxy: " + e);
        }
    }

    private void done(boolean success) {
        if (DBG) logd(String.format("Result success %b for %s", success,
                mUrl != null ? mUrl.toString() : "null"));
        if (success) {
            // Trigger re-evaluation upon success http response code
            CarrierActionUtils.applyCarrierAction(
                    CarrierActionUtils.CARRIER_ACTION_RESET_ALL, getIntent(),
                    getApplicationContext());
        }
        finishAndRemoveTask();
    }

    private URL getUrlForCaptivePortal() {
        String url = getIntent().getStringExtra(TelephonyManager.EXTRA_REDIRECTION_URL);
        if (TextUtils.isEmpty(url)) url = mCm.getCaptivePortalServerUrl();
        final CarrierConfigManager configManager = getApplicationContext()
                .getSystemService(CarrierConfigManager.class);
        final int subId = getIntent().getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                SubscriptionManager.getDefaultVoiceSubscriptionId());
        final String[] portalURLs = configManager.getConfigForSubId(subId).getStringArray(
                CarrierConfigManager.KEY_CARRIER_DEFAULT_REDIRECTION_URL_STRING_ARRAY);
        if (!ArrayUtils.isEmpty(portalURLs)) {
            for (String portalUrl : portalURLs) {
                if (url.startsWith(portalUrl)) {
                    break;
                }
            }
            url = null;
        }
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            loge("Invalid captive portal URL " + url);
        }
        return null;
    }

    private void testForCaptivePortal() {
        mTestingThread = new Thread(new Runnable() {
            public void run() {
                // Give time for captive portal to open.
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                if (isFinishing() || isDestroyed()) return;
                HttpURLConnection urlConnection = null;
                int httpResponseCode = 500;
                int oldTag = TrafficStats.getAndSetThreadStatsTag(
                        NetworkStackConstants.TAG_SYSTEM_PROBE);
                try {
                    urlConnection = (HttpURLConnection) mNetwork.openConnection(
                            new URL(mCm.getCaptivePortalServerUrl()));
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setUseCaches(false);
                    urlConnection.getInputStream();
                    httpResponseCode = urlConnection.getResponseCode();
                } catch (IOException e) {
                    loge(e.getMessage());
                } finally {
                    if (urlConnection != null) urlConnection.disconnect();
                    TrafficStats.setThreadStatsTag(oldTag);
                }
                if (httpResponseCode == 204) {
                    done(true);
                }
            }
        });
        mTestingThread.start();
    }

    private Network getNetworkForCaptivePortal() {
        Network[] info = mCm.getAllNetworks();
        if (!ArrayUtils.isEmpty(info)) {
            for (Network nw : info) {
                final NetworkCapabilities nc = mCm.getNetworkCapabilities(nw);
                if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    return nw;
                }
            }
        }
        return null;
    }

    private void requestNetworkForCaptivePortal() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build();

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (DBG) logd("Network available: " + network);
                setNetwork(network);
                runOnUiThreadIfNotFinishing(() -> {
                    if (mReload) {
                        mWebView.reload();
                    } else {
                        // Start initial page load so WebView finishes loading proxy settings.
                        // Actual load of mUrl is initiated by MyWebViewClient.
                        mWebView.loadData("", "text/html", null);
                    }
                });
            }

            @Override
            public void onUnavailable() {
                if (DBG) logd("Network unavailable");
                runOnUiThreadIfNotFinishing(() -> {
                    // Instead of not loading anything in webview, simply load the page and return
                    // HTTP error page in the absence of network connection.
                    mWebView.loadUrl(mUrl.toString());
                });
            }

            @Override
            public void onLost(Network lostNetwork) {
                if (DBG) logd("Network lost");
                mReload = true;
            }
        };
        logd("request Network for captive portal");
        mCm.requestNetwork(request, mNetworkCallback, NETWORK_REQUEST_TIMEOUT_MS);
    }

    private void releaseNetworkRequest() {
        logd("release Network for captive portal");
        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
            mNetwork = null;
        }
    }

    private class MyWebViewClient extends WebViewClient {
        private static final String INTERNAL_ASSETS = "file:///android_asset/";
        private final String mBrowserBailOutToken = Long.toString(new Random().nextLong());
        // How many Android device-independent-pixels per scaled-pixel
        // dp/sp = (px/sp) / (px/dp) = (1/sp) / (1/dp)
        private final float mDpPerSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1,
                    getResources().getDisplayMetrics())
                / TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
                    getResources().getDisplayMetrics());
        private int mPagesLoaded;

        // If we haven't finished cleaning up the history, don't allow going back.
        public boolean allowBack() {
            return mPagesLoaded > 1;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url.contains(mBrowserBailOutToken)) {
                mLaunchBrowser = true;
                done(false);
                return;
            }
            // The first page load is used only to cause the WebView to
            // fetch the proxy settings.  Don't update the URL bar, and
            // don't check if the captive portal is still there.
            if (mPagesLoaded == 0) return;
            // For internally generated pages, leave URL bar listing prior URL as this is the URL
            // the page refers to.
            if (!url.startsWith(INTERNAL_ASSETS)) {
                final TextView myUrlBar = findViewById(R.id.url_bar);
                myUrlBar.setText(url);
            }
            if (mNetwork != null) {
                testForCaptivePortal();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mPagesLoaded++;
            if (mPagesLoaded == 1) {
                // Now that WebView has loaded at least one page we know it has read in the proxy
                // settings.  Now prompt the WebView read the Network-specific proxy settings.
                setWebViewProxy();
                // Load the real page.
                view.loadUrl(mUrl.toString());
                return;
            } else if (mPagesLoaded == 2) {
                // Prevent going back to empty first page.
                view.clearHistory();
            }
            if (mNetwork != null) {
                testForCaptivePortal();
            }
        }

        // Convert Android device-independent-pixels (dp) to HTML size.
        private String dp(int dp) {
            // HTML px's are scaled just like dp's, so just add "px" suffix.
            return Integer.toString(dp) + "px";
        }

        // Convert Android scaled-pixels (sp) to HTML size.
        private String sp(int sp) {
            // Convert sp to dp's.
            float dp = sp * mDpPerSp;
            // Apply a scale factor to make things look right.
            dp *= 1.3;
            // Convert dp's to HTML size.
            return dp((int) dp);
        }

        // A web page consisting of a large broken lock icon to indicate SSL failure.
        private final String SSL_ERROR_HTML = "<html><head><style>"
                + "body { margin-left:" + dp(48) + "; margin-right:" + dp(48) + "; "
                + "margin-top:" + dp(96) + "; background-color:#fafafa; }"
                + "img { width:" + dp(48) + "; height:" + dp(48) + "; }"
                + "div.warn { font-size:" + sp(16) + "; margin-top:" + dp(16) + "; "
                + "           opacity:0.87; line-height:1.28; }"
                + "div.example { font-size:" + sp(14) + "; margin-top:" + dp(16) + "; "
                + "              opacity:0.54; line-height:1.21905; }"
                + "a { font-size:" + sp(14) + "; text-decoration:none; text-transform:uppercase; "
                + "    margin-top:" + dp(24) + "; display:inline-block; color:#4285F4; "
                + "    height:" + dp(48) + "; font-weight:bold; }"
                + "</style></head><body><p><img src=quantum_ic_warning_amber_96.png><br>"
                + "<div class=warn>%s</div>"
                + "<div class=example>%s</div>" + "<a href=%s>%s</a></body></html>";

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.w(TAG, "SSL error (error: " + error.getPrimaryError() + " host: "
                    // Only show host to avoid leaking private info.
                    + Uri.parse(error.getUrl()).getHost() + " certificate: "
                    + error.getCertificate() + "); displaying SSL warning.");
            final String html = String.format(SSL_ERROR_HTML, getString(R.string.ssl_error_warning),
                    getString(R.string.ssl_error_example), mBrowserBailOutToken,
                    getString(R.string.ssl_error_continue));
            view.loadDataWithBaseURL(INTERNAL_ASSETS, html, "text/HTML", "UTF-8", null);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
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
            final ProgressBar myProgressBar = findViewById(R.id.progress_bar);
            myProgressBar.setProgress(newProgress);
        }
    }

    private void runOnUiThreadIfNotFinishing(Runnable r) {
        if (!isFinishing()) {
            runOnUiThread(r);
        }
    }

    /**
     * This alias presents the target activity, CaptivePortalLoginActivity, as a independent
     * entity with its own intent filter to handle URL links. This alias will be enabled/disabled
     * dynamically to handle url links based on the network conditions.
     */
    public static String getAlias(Context context) {
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(context.getPackageName(),
                    PackageManager.GET_ACTIVITIES | PackageManager.MATCH_DISABLED_COMPONENTS);
            for (ActivityInfo activityInfo : p.activities) {
                String targetActivity = activityInfo.targetActivity;
                if (CaptivePortalLoginActivity.class.getName().equals(targetActivity)) {
                    return activityInfo.name;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void logd(String s) {
        Log.d(TAG, s);
    }

    private static void loge(String s) {
        Log.d(TAG, s);
    }

}
