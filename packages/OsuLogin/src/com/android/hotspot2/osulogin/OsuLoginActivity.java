/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.hotspot2.osulogin;

import static android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * Online Sign Up Login Web View launched during Provision Process of Hotspot 2.0 rel2.
 */
public class OsuLoginActivity extends Activity {
    private static final String TAG = "OsuLogin";
    private static final boolean DBG = true;

    private String mUrl;
    private String mHostName;
    private Network mNetwork;
    private ConnectivityManager mCm;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private WifiManager mWifiManager;
    private WebView mWebView;
    private SwipeRefreshLayout mSwipeRefreshLayout;
    private ProgressBar mProgressBar;
    private boolean mForceDisconnect = true;
    boolean mRedirectResponseReceived = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DBG) {
            Log.d(TAG, "onCreate: Opening OSU Web View");
        }

        mWifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (mWifiManager == null) {
            Log.e(TAG, "Cannot get wifi service");
            finishAndRemoveTask();
            return;
        }

        if (getIntent() == null) {
            Log.e(TAG, "Intent is null");
            finishAndRemoveTask();
            return;
        }

        mNetwork = getIntent().getParcelableExtra(WifiManager.EXTRA_OSU_NETWORK);
        if (mNetwork == null) {
            Log.e(TAG, "Cannot get the network instance for OSU from intent");
            finishAndRemoveTask();
            return;
        }

        mUrl = getIntent().getStringExtra(WifiManager.EXTRA_URL);
        if (mUrl == null) {
            Log.e(TAG, "Cannot get OSU server url from intent");
            finishAndRemoveTask();
            return;
        }

        mHostName = getHost(mUrl);
        if (mHostName == null) {
            Log.e(TAG, "Cannot get host from the url");
            finishAndRemoveTask();
            return;
        }

        mCm = (ConnectivityManager) getApplicationContext().getSystemService(
                Context.CONNECTIVITY_SERVICE);
        if (mCm == null) {
            Log.e(TAG, "Cannot get connectivity service");
            finishAndRemoveTask();
            return;
        }

        if (!mCm.bindProcessToNetwork(mNetwork)) {
            Log.e(TAG, "Network is no longer valid");
            finishAndRemoveTask();
            return;
        }

        final NetworkCapabilities networkCapabilities = mCm.getNetworkCapabilities(mNetwork);
        if (networkCapabilities == null || !networkCapabilities.hasTransport(
                NetworkCapabilities.TRANSPORT_WIFI)) {
            Log.e(TAG, "WiFi is not supported for the Network");
            finishAndRemoveTask();
            return;
        }

        getActionBar().setDisplayShowHomeEnabled(false);
        getActionBar().setElevation(0); // remove shadow
        getActionBar().setTitle(getString(R.string.action_bar_label));
        getActionBar().setSubtitle("");
        setContentView(R.layout.osu_web_view);

        // Exit this app if network disappeared.
        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onLost(Network network) {
                if (DBG) {
                    Log.d(TAG, "Lost for the current Network, close the browser");
                }
                mForceDisconnect = false; // It is already disconnected.
                if (!mRedirectResponseReceived) {
                    showSignUpFailedToast();
                }
                if (mNetwork.equals(network)) {
                    finishAndRemoveTask();
                }
            }
        };

        mCm.registerNetworkCallback(
                new NetworkRequest.Builder().addTransportType(
                        NetworkCapabilities.TRANSPORT_WIFI).removeCapability(
                        NET_CAPABILITY_TRUSTED).build(),
                mNetworkCallback);

        mWebView = findViewById(R.id.webview);
        mWebView.clearCache(true);
        WebSettings webSettings = mWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        webSettings.setUseWideViewPort(true);
        webSettings.setLoadWithOverviewMode(true);
        webSettings.setSupportZoom(true);
        webSettings.setBuiltInZoomControls(true);
        webSettings.setDisplayZoomControls(false);
        mProgressBar = findViewById(R.id.progress_bar);
        mWebView.setWebViewClient(new OsuWebViewClient());
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
            }
        });

        if (DBG) {
            Log.d(TAG, "OSU Web View to " + mUrl);
        }

        mWebView.loadUrl(mUrl);
        mSwipeRefreshLayout = findViewById(R.id.swipe_refresh);
        mSwipeRefreshLayout.setOnRefreshListener(() -> {
            mWebView.reload();
            mSwipeRefreshLayout.setRefreshing(true);
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if the key event was the Back button.
        if ((keyCode == KeyEvent.KEYCODE_BACK)) {
            // If there is a history to move back
            if (mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (mNetworkCallback != null) {
            mCm.unregisterNetworkCallback(mNetworkCallback);
            mNetworkCallback = null;
        }
        if (mWifiManager != null && mForceDisconnect) {
            mWifiManager.disconnect();
            mWifiManager = null;
        }
        super.onDestroy();
    }

    private String getHost(String url) {
        try {
            return new URL(url).getHost();
        } catch (MalformedURLException e) {
            Log.e(TAG, "Invalid URL " + url);
        }
        return null;
    }

    private String getHeaderSubtitle(String urlString) {
        try {
            URL url = new URL(urlString);
            return url.getProtocol() + "://" +  url.getHost();
        } catch (MalformedURLException e) {
            Log.e(TAG, "Invalid URL " + urlString);
        }
        return "";
    }

    private void showSignUpFailedToast() {
        Toast.makeText(getApplicationContext(), R.string.sign_up_failed,
                Toast.LENGTH_SHORT).show();
    }

    private class OsuWebViewClient extends WebViewClient {
        boolean mPageError = false;

        @Override
        public void onPageStarted(WebView view, String urlString, Bitmap favicon) {
            String subtitle = getHeaderSubtitle(urlString);
            getActionBar().setSubtitle(subtitle);
            mProgressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            mProgressBar.setVisibility(View.INVISIBLE);
            mSwipeRefreshLayout.setRefreshing(false);

            // Do not show the page error on UI.
            if (mPageError) {
                if (mRedirectResponseReceived) {
                    // Do not disconnect current connection while provisioning is in progress.
                    mForceDisconnect = false;
                }
                finishAndRemoveTask();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request,
                WebResourceError error) {
            if (request.getUrl().toString().startsWith("http://127.0.0.1")) {
                mRedirectResponseReceived = true;
                view.stopLoading();
            }

            if (request.isForMainFrame()) {
                // This happens right after getting HTTP redirect response from an OSU server
                // since no more Http request is allowed to send to the OSU server.
                mPageError = true;
                Log.e(TAG, "onReceived Error for MainFrame: " + error.getErrorCode());
            }
        }
    }
}
