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
import android.net.NetworkRequest;
import android.net.Proxy;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;
import android.util.TypedValue;
import android.view.Menu;
import android.view.MenuItem;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.lang.InterruptedException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Random;

public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = "CaptivePortalLogin";
    private static final int SOCKET_TIMEOUT_MS = 10000;

    private enum Result { DISMISSED, UNWANTED, WANTED_AS_IS };

    private URL mURL;
    private Network mNetwork;
    private CaptivePortal mCaptivePortal;
    private NetworkCallback mNetworkCallback;
    private ConnectivityManager mCm;
    private boolean mLaunchBrowser = false;
    private MyWebViewClient mWebViewClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mCm = ConnectivityManager.from(this);
        String url = getIntent().getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL);
        if (url == null) url = mCm.getCaptivePortalServerUrl();
        try {
            mURL = new URL(url);
        } catch (MalformedURLException e) {
            // System misconfigured, bail out in a way that at least provides network access.
            Log.e(TAG, "Invalid captive portal URL, url=" + url);
            done(Result.WANTED_AS_IS);
        }
        mNetwork = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_NETWORK);
        mCaptivePortal = getIntent().getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL);

        // Also initializes proxy system properties.
        mCm.bindProcessToNetwork(mNetwork);

        // Proxy system properties must be initialized before setContentView is called because
        // setContentView initializes the WebView logic which in turn reads the system properties.
        setContentView(R.layout.activity_captive_portal_login);

        getActionBar().setDisplayShowHomeEnabled(false);

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

        final WebView myWebView = (WebView) findViewById(R.id.webview);
        myWebView.clearCache(true);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        mWebViewClient = new MyWebViewClient();
        myWebView.setWebViewClient(mWebViewClient);
        myWebView.setWebChromeClient(new MyWebChromeClient());
        // Start initial page load so WebView finishes loading proxy settings.
        // Actual load of mUrl is initiated by MyWebViewClient.
        myWebView.loadData("", "text/html", null);
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
        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
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
        WebView myWebView = (WebView) findViewById(R.id.webview);
        if (myWebView.canGoBack() && mWebViewClient.allowBack()) {
            myWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_use_network) {
            done(Result.WANTED_AS_IS);
            return true;
        }
        if (id == R.id.action_do_not_use_network) {
            done(Result.UNWANTED);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
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
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(mURL.toString())));
        }
    }

    private void testForCaptivePortal() {
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
                    urlConnection = (HttpURLConnection) mURL.openConnection();
                    urlConnection.setInstanceFollowRedirects(false);
                    urlConnection.setConnectTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setReadTimeout(SOCKET_TIMEOUT_MS);
                    urlConnection.setUseCaches(false);
                    urlConnection.getInputStream();
                    httpResponseCode = urlConnection.getResponseCode();
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

        // If we haven't finished cleaning up the history, don't allow going back.
        public boolean allowBack() {
            return mPagesLoaded > 1;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url.contains(mBrowserBailOutToken)) {
                mLaunchBrowser = true;
                done(Result.WANTED_AS_IS);
                return;
            }
            // The first page load is used only to cause the WebView to
            // fetch the proxy settings.  Don't update the URL bar, and
            // don't check if the captive portal is still there.
            if (mPagesLoaded == 0) return;
            // For internally generated pages, leave URL bar listing prior URL as this is the URL
            // the page refers to.
            if (!url.startsWith(INTERNAL_ASSETS)) {
                final TextView myUrlBar = (TextView) findViewById(R.id.url_bar);
                myUrlBar.setText(url);
            }
            testForCaptivePortal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mPagesLoaded++;
            if (mPagesLoaded == 1) {
                // Now that WebView has loaded at least one page we know it has read in the proxy
                // settings.  Now prompt the WebView read the Network-specific proxy settings.
                setWebViewProxy();
                // Load the real page.
                view.loadUrl(mURL.toString());
                return;
            } else if (mPagesLoaded == 2) {
                // Prevent going back to empty first page.
                view.clearHistory();
            }
            testForCaptivePortal();
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
            return dp((int)dp);
        }

        // A web page consisting of a large broken lock icon to indicate SSL failure.
        private final String SSL_ERROR_HTML = "<html><head><style>" +
                "body { margin-left:" + dp(48) + "; margin-right:" + dp(48) + "; " +
                        "margin-top:" + dp(96) + "; background-color:#fafafa; }" +
                "img { width:" + dp(48) + "; height:" + dp(48) + "; }" +
                "div.warn { font-size:" + sp(16) + "; margin-top:" + dp(16) + "; " +
                "           opacity:0.87; line-height:1.28; }" +
                "div.example { font-size:" + sp(14) + "; margin-top:" + dp(16) + "; " +
                "              opacity:0.54; line-height:1.21905; }" +
                "a { font-size:" + sp(14) + "; text-decoration:none; text-transform:uppercase; " +
                "    margin-top:" + dp(24) + "; display:inline-block; color:#4285F4; " +
                "    height:" + dp(48) + "; font-weight:bold; }" +
                "</style></head><body><p><img src=quantum_ic_warning_amber_96.png><br>" +
                "<div class=warn>%s</div>" +
                "<div class=example>%s</div>" +
                "<a href=%s>%s</a></body></html>";

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.w(TAG, "SSL error (error: " + error.getPrimaryError() + " host: " +
                    // Only show host to avoid leaking private info.
                    Uri.parse(error.getUrl()).getHost() + " certificate: " +
                    error.getCertificate() + "); displaying SSL warning.");
            final String html = String.format(SSL_ERROR_HTML, getString(R.string.ssl_error_warning),
                    getString(R.string.ssl_error_example), mBrowserBailOutToken,
                    getString(R.string.ssl_error_continue));
            view.loadDataWithBaseURL(INTERNAL_ASSETS, html, "text/HTML", "UTF-8", null);
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
            final ProgressBar myProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
            myProgressBar.setProgress(newProgress);
        }
    }
}
