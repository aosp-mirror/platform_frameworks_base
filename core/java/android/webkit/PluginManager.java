/*
 * Copyright (C) 2009 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.List;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.content.pm.PackageManager.NameNotFoundException;
import android.util.Log;

/**
 * Class for managing the relationship between the {@link WebView} and installed
 * plugins in the system. You can find this class through
 * {@link PluginManager#getInstance}.
 * 
 * @hide pending API solidification
 */
public class PluginManager {

    /**
     * Service Action: A plugin wishes to be loaded in the WebView must provide
     * {@link android.content.IntentFilter IntentFilter} that accepts this
     * action in their AndroidManifest.xml.
     * <p>
     * TODO: we may change this to a new PLUGIN_ACTION if this is going to be
     * public.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String PLUGIN_ACTION = "android.webkit.PLUGIN";

    /**
     * A plugin wishes to be loaded in the WebView must provide this permission
     * in their AndroidManifest.xml.
     */
    public static final String PLUGIN_PERMISSION = "android.webkit.permission.PLUGIN";

    private static final String LOGTAG = "webkit";

    private static PluginManager mInstance = null;

    private final Context mContext;

    private PluginManager(Context context) {
        mContext = context;
    }

    public static synchronized PluginManager getInstance(Context context) {
        if (mInstance == null) {
            if (context == null) {
                throw new IllegalStateException(
                        "First call to PluginManager need a valid context.");
            }
            mInstance = new PluginManager(context);
        }
        return mInstance;
    }

    /**
     * Signal the WebCore thread to refresh its list of plugins. Use this if the
     * directory contents of one of the plugin directories has been modified and
     * needs its changes reflecting. May cause plugin load and/or unload.
     * 
     * @param reloadOpenPages Set to true to reload all open pages.
     */
    public void refreshPlugins(boolean reloadOpenPages) {
        BrowserFrame.sJavaBridge.obtainMessage(
                JWebCoreJavaBridge.REFRESH_PLUGINS, reloadOpenPages)
                .sendToTarget();
    }

    String[] getPluginDirecoties() {
        ArrayList<String> directories = new ArrayList<String>();
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> plugins = pm.queryIntentServices(new Intent(
                PLUGIN_ACTION), PackageManager.GET_SERVICES);
        for (ResolveInfo info : plugins) {
            ServiceInfo serviceInfo = info.serviceInfo;
            if (serviceInfo == null) {
                Log.w(LOGTAG, "Ignore bad plugin");
                continue;
            }
            PackageInfo pkgInfo;
            try {
                pkgInfo = pm.getPackageInfo(serviceInfo.packageName,
                        PackageManager.GET_PERMISSIONS
                                | PackageManager.GET_SIGNATURES);
            } catch (NameNotFoundException e) {
                Log.w(LOGTAG, "Cant find plugin: " + serviceInfo.packageName);
                continue;
            }
            if (pkgInfo == null) {
                continue;
            }
            String directory = pkgInfo.applicationInfo.dataDir + "/lib";
            if (directories.contains(directory)) {
                continue;
            }
            String permissions[] = pkgInfo.requestedPermissions;
            if (permissions == null) {
                continue;
            }
            boolean permissionOk = false;
            for (String permit : permissions) {
                if (PLUGIN_PERMISSION.equals(permit)) {
                    permissionOk = true;
                    break;
                }
            }
            if (!permissionOk) {
                continue;
            }
            Signature signatures[] = pkgInfo.signatures;
            if (signatures == null) {
                continue;
            }
            boolean signatureMatch = false;
            for (Signature signature : signatures) {
                // TODO: check signature against Google provided one
                signatureMatch = true;
                break;
            }
            if (!signatureMatch) {
                continue;
            }
            directories.add(directory);
        }
        // hack for gears for now
        String gears = mContext.getDir("plugins", 0).getPath();
        if (!directories.contains(gears)) {
            directories.add(gears);
        }
        return directories.toArray(new String[directories.size()]);
    }

    String getPluginSharedDataDirectory() {
        return mContext.getDir("plugins", 0).getPath();
    }
}
