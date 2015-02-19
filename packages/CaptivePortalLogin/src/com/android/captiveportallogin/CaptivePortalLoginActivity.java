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
import android.provider.Settings.Global;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.webkit.SslErrorHandler;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class CaptivePortalLoginActivity extends Activity {
    private static final String TAG = "CaptivePortalLogin";
    private static final String DEFAULT_SERVER = "connectivitycheck.android.com";
    private static final int SOCKET_TIMEOUT_MS = 10000;

    // Keep this in sync with NetworkMonitor.
    // Intent broadcast to ConnectivityService indicating sign-in is complete.
    // Extras:
    //     EXTRA_TEXT       = netId
    //     LOGGED_IN_RESULT = one of the CAPTIVE_PORTAL_APP_RETURN_* values below.
    //     RESPONSE_TOKEN   = data fragment from launching Intent
    private static final String ACTION_CAPTIVE_PORTAL_LOGGED_IN =
            "android.net.netmon.captive_portal_logged_in";
    private static final String LOGGED_IN_RESULT = "result";
    private static final int CAPTIVE_PORTAL_APP_RETURN_APPEASED = 0;
    private static final int CAPTIVE_PORTAL_APP_RETURN_UNWANTED = 1;
    private static final int CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS = 2;
    private static final String RESPONSE_TOKEN = "response_token";

    private URL mURL;
    private int mNetId;
    private String mResponseToken;
    private NetworkCallback mNetworkCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String server = Settings.Global.getString(getContentResolver(), "captive_portal_server");
        if (server == null) server = DEFAULT_SERVER;
        try {
            mURL = new URL("http", server, "/generate_204");
            final Uri dataUri = getIntent().getData();
            if (!dataUri.getScheme().equals("netid")) {
                throw new MalformedURLException();
            }
            mNetId = Integer.parseInt(dataUri.getSchemeSpecificPart());
            mResponseToken = dataUri.getFragment();
        } catch (MalformedURLException|NumberFormatException e) {
            // System misconfigured, bail out in a way that at least provides network access.
            done(CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS);
        }

        final ConnectivityManager cm = ConnectivityManager.from(this);
        final Network network = new Network(mNetId);
        // Also initializes proxy system properties.
        cm.setProcessDefaultNetwork(network);

        // Proxy system properties must be initialized before setContentView is called because
        // setContentView initializes the WebView logic which in turn reads the system properties.
        setContentView(R.layout.activity_captive_portal_login);

        getActionBar().setDisplayShowHomeEnabled(false);

        // Exit app if Network disappears.
        final NetworkCapabilities networkCapabilities = cm.getNetworkCapabilities(network);
        if (networkCapabilities == null) {
            finish();
            return;
        }
        mNetworkCallback = new NetworkCallback() {
            @Override
            public void onLost(Network lostNetwork) {
                if (network.equals(lostNetwork)) done(CAPTIVE_PORTAL_APP_RETURN_UNWANTED);
            }
        };
        final NetworkRequest.Builder builder = new NetworkRequest.Builder();
        for (int transportType : networkCapabilities.getTransportTypes()) {
            builder.addTransportType(transportType);
        }
        cm.registerNetworkCallback(builder.build(), mNetworkCallback);

        final WebView myWebView = (WebView) findViewById(R.id.webview);
        myWebView.clearCache(true);
        WebSettings webSettings = myWebView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        myWebView.setWebViewClient(new MyWebViewClient());
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

    private void done(int result) {
        if (mNetworkCallback != null) {
            ConnectivityManager.from(this).unregisterNetworkCallback(mNetworkCallback);
        }
        Intent intent = new Intent(ACTION_CAPTIVE_PORTAL_LOGGED_IN);
        intent.putExtra(Intent.EXTRA_TEXT, String.valueOf(mNetId));
        intent.putExtra(LOGGED_IN_RESULT, String.valueOf(result));
        intent.putExtra(RESPONSE_TOKEN, mResponseToken);
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
            done(CAPTIVE_PORTAL_APP_RETURN_WANTED_AS_IS);
            return true;
        }
        if (id == R.id.action_do_not_use_network) {
            done(CAPTIVE_PORTAL_APP_RETURN_UNWANTED);
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
                    done(CAPTIVE_PORTAL_APP_RETURN_APPEASED);
                }
            }
        }).start();
    }

    private class MyWebViewClient extends WebViewClient {
        private boolean firstPageLoad = true;

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (firstPageLoad) return;
            testForCaptivePortal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (firstPageLoad) {
                firstPageLoad = false;
                // Now that WebView has loaded at least one page we know it has read in the proxy
                // settings.  Now prompt the WebView read the Network-specific proxy settings.
                setWebViewProxy();
                // Load the real page.
                view.loadUrl(mURL.toString());
                return;
            }
            testForCaptivePortal();
        }

        // A web page consisting of a large broken lock icon to indicate SSL failure.
        final static String SSL_ERROR_HTML = "<!DOCTYPE html><html><head><style>" +
                "html { width:100%; height:100%; " +
                "       background:url(locked_page.png) center center no-repeat; }" +
                "</style></head><body></body></html>";

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            Log.w(TAG, "SSL error; displaying broken lock icon.");
            view.loadDataWithBaseURL("file:///android_asset/", SSL_ERROR_HTML, "text/HTML",
                    "UTF-8", null);
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
