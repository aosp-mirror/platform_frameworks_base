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
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebView;
import com.android.internal.R;

/**
 * The runtime used to display installed web applications.
 * @hide
 */
public class WebRuntimeActivity extends Activity
{
    private final static String LOGTAG = "WebRuntimeActivity";

    WebView webView;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        // Can't get meta data using getApplicationInfo() as it doesn't pass GET_META_DATA
        PackageManager packageManager = getPackageManager();
        ComponentName componentName = new ComponentName(this, "android.webruntime.WebRuntimeActivity");
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

        setContentView(R.layout.web_runtime);
        webView = (WebView) findViewById(R.id.webview);
        webView.loadUrl(url);

        String title = metaData.getString("android.webruntime.title");
        if (title != null) {
            setTitle(title);
        }
    }
}
