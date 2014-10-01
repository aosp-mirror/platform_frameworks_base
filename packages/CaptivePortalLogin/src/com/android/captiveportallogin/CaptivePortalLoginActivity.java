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
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.lang.InterruptedException;

public class CaptivePortalLoginActivity extends Activity {
    private static final String DEFAULT_SERVER = "clients3.google.com";
    private static final int SOCKET_TIMEOUT_MS = 10000;

    // Keep this in sync with NetworkMonitor.
    // Intent broadcast to ConnectivityService indicating sign-in is complete.
    // Extras:
    //     EXTRA_TEXT       = netId
    //     LOGGED_IN_RESULT = "1" if we should use network, "0" if not.
    private static final String ACTION_CAPTIVE_PORTAL_LOGGED_IN =
            "android.net.netmon.captive_portal_logged_in";
    private static final String LOGGED_IN_RESULT = "result";

    private URL mURL;
    private int mNetId;
    private NetworkCallback mNetworkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String server = Settings.Global.getString(getContentResolver(), "captive_portal_server");
        if (server == null) server = DEFAULT_SERVER;
        try {
            mURL = new URL("http://" + server + "/generate_204");
        } catch (MalformedURLException e) {
            done(true);
        }

        setContentView(R.layout.activity_captive_portal_login);

        getActionBar().setDisplayShowHomeEnabled(false);

        mNetId = Integer.parseInt(getIntent().getStringExtra(Intent.EXTRA_TEXT));
        final Network network = new Network(mNetId);
        ConnectivityManager.setProcessDefaultNetwork(network);

        // Exit app if Network disappears.
        final NetworkCapabilities networkCapabilities =
                ConnectivityManager.from(this).getNetworkCapabilities(network);
        if (networkCapabilities == null) {
            finish();
            return;
        }
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network lostNetwork) {
                if (network.equals(lostNetwork)) done(false);
            }
        };
        final NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (int transportType : networkCapabilities.getTransportTypes()) {
            builder.addTransportType(transportType);
        }
        ConnectivityManager.from(this).registerNetworkCallback(builder.build(), mNetworkCallback);

        WebView myWebView = (WebView) findViewById(R.id.webview);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        myWebView.setWebViewClient(new MyWebViewClient());
        myWebView.setWebChromeClient(new MyWebChromeClient());
        myWebView.loadUrl(mURL.toString());
    }

    private void done(boolean use_network) {
        ConnectivityManager.from(this).unregisterNetworkCallback(mNetworkCallback);
        Intent intent = new Intent(ACTION_CAPTIVE_PORTAL_LOGGED_IN);
        intent.putExtra(Intent.EXTRA_TEXT, String.valueOf(mNetId));
        intent.putExtra(LOGGED_IN_RESULT, use_network ? "1" : "0");
        sendBroadcast(intent);
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.captive_portal_login, menu);
        return true;
    }

    @Override
    public void onBackPressed() {
        WebView myWebView = (WebView) findViewById(R.id.webview);
        if (myWebView.canGoBack()) {
            myWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_use_network) {
            done(true);
            return true;
        }
        if (id == R.id.action_do_not_use_network) {
            done(false);
            return true;
        }
        return super.onOptionsItemSelected(item);
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
                    done(true);
                }
            }
        }).start();
    }

    private class MyWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            testForCaptivePortal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            testForCaptivePortal();
        }
    }

    private class MyWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            ProgressBar myProgressBar = (ProgressBar) findViewById(R.id.progress_bar);
            myProgressBar.setProgress(newProgress);
            myProgressBar.setVisibility(newProgress == 100 ? View.GONE : View.VISIBLE);
        }
    }
}
