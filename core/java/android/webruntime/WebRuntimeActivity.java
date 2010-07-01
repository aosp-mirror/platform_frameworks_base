/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webruntime;

import android.app.Activity;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.view.Window;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.internal.R;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * The runtime used to display installed web applications.
 * @hide
 */
public class WebRuntimeActivity extends Activity
{
    private final static String LOGTAG = "WebRuntimeActivity";

    private WebView mWebView;
    private URL mBaseUrl;
    private ImageView mSplashScreen;

    public static class SensitiveFeatures {
        // All of the sensitive features
        private boolean mGeolocation;
        // On Android, the Browser doesn't prompt for database access, so we don't require an
        // explicit permission here in the WebRuntimeActivity, and there's no Android system
        // permission required for it either.
        //private boolean mDatabase;

        public boolean getGeolocation() {
            return mGeolocation;
        }
        public void setGeolocation(boolean geolocation) {
            mGeolocation = geolocation;
        }
    }

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Can't get meta data using getApplicationInfo() as it doesn't pass GET_META_DATA
        PackageManager packageManager = getPackageManager();
        ComponentName componentName = new ComponentName(this, getClass());
        ActivityInfo activityInfo = null;
        try {
            activityInfo = packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(LOGTAG, "Failed to find component");
            return;
        }
        if (activityInfo == null) {
            Log.d(LOGTAG, "Failed to get activity info");
            return;
        }

        Bundle metaData = activityInfo.metaData;
        if (metaData == null) {
            Log.d(LOGTAG, "No meta data");
            return;
        }

        String url = metaData.getString("android.webruntime.url");
        if (url == null) {
            Log.d(LOGTAG, "No URL");
            return;
        }

        try {
            mBaseUrl = new URL(url);
        } catch (MalformedURLException e) {
            Log.d(LOGTAG, "Invalid URL");
        }

        // All false by default, and reading non-existent bundle properties gives false too.
        final SensitiveFeatures sensitiveFeatures = new SensitiveFeatures();
        sensitiveFeatures.setGeolocation(metaData.getBoolean("android.webruntime.SensitiveFeaturesGeolocation"));

        getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.web_runtime);
        mWebView = (WebView) findViewById(R.id.webview);
        mSplashScreen = (ImageView) findViewById(R.id.splashscreen);
        mSplashScreen.setImageResource(
                getResources().getIdentifier("splash_screen", "drawable", getPackageName()));
        mWebView.getSettings().setJavaScriptEnabled(true);
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                try {
                    URL newOrigin = new URL(url);
                    if (areSameOrigin(mBaseUrl, newOrigin)) {
                        // If simple same origin test passes, load in the webview.
                        return false;
                    }
                } catch(MalformedURLException e) {
                    // Don't load anything if this wasn't a proper URL.
                    return true;
                }

                // Otherwise this is a URL that is not same origin so pass it to the
                // Browser to load.
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (mSplashScreen.getVisibility() == View.VISIBLE) {
                    mSplashScreen.setVisibility(View.GONE);
                    mSplashScreen = null;
                }
            }
        });

        // Use a custom WebChromeClient with geolocation permissions handling.
        mWebView.setWebChromeClient(new WebChromeClient() {
            public void onGeolocationPermissionsShowPrompt(
                        String origin, GeolocationPermissions.Callback callback) {
                // Allow this origin if it has Geolocation permissions, otherwise deny.
                boolean allowed = false;
                if (sensitiveFeatures.getGeolocation()) {
                    try {
                        URL originUrl = new URL(origin);
                        allowed = areSameOrigin(mBaseUrl, originUrl);
                    } catch(MalformedURLException e) {
                    }
                }
                callback.invoke(origin, allowed, false);
            }
        });

        // Set the DB location. Optional. Geolocation works without DBs.
        mWebView.getSettings().setGeolocationDatabasePath(
                getDir("geolocation", MODE_PRIVATE).getPath());

        String title = metaData.getString("android.webruntime.title");
        // We turned off the title bar to go full screen so display the
        // webapp's title as a toast.
        if (title != null) {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
        }

        // Load the webapp's base URL.
        mWebView.loadUrl(url);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && mWebView.canGoBack()) {
            mWebView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 0, 0, "Menu item 1");
        menu.add(0, 1, 0, "Menu item 2");
        return true;
    }

    private static boolean areSameOrigin(URL a, URL b) {
        int aPort = a.getPort() == -1 ? a.getDefaultPort() : a.getPort();
        int bPort = b.getPort() == -1 ? b.getDefaultPort() : b.getPort();
        return a.getProtocol().equals(b.getProtocol()) && aPort == bPort && a.getHost().equals(b.getHost());
    }
}
