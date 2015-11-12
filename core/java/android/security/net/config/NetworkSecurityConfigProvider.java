/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import java.security.Security;
import java.security.Provider;

/** @hide */
public final class NetworkSecurityConfigProvider extends Provider {
    private static final String LOG_TAG = "NetworkSecurityConfig";
    private static final String PREFIX =
            NetworkSecurityConfigProvider.class.getPackage().getName() + ".";
    public static final String META_DATA_NETWORK_SECURITY_CONFIG =
            "android.security.net.config";
    private static final boolean DBG = true;

    public NetworkSecurityConfigProvider() {
        // TODO: More clever name than this
        super("AndroidNSSP", 1.0, "Android Network Security Policy Provider");
        put("TrustManagerFactory.PKIX", PREFIX + "RootTrustManagerFactorySpi");
        put("Alg.Alias.TrustManagerFactory.X509", "PKIX");
    }

    public static void install(Context context) {
        ApplicationInfo info = null;
        // TODO: This lookup shouldn't be done in the app startup path, it should be done lazily.
        try {
            info = context.getPackageManager().getApplicationInfo(context.getPackageName(),
                    PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException("Failed to look up ApplicationInfo", e);
        }
        int configResourceId = 0;
        if (info != null && info.metaData != null) {
            configResourceId = info.metaData.getInt(META_DATA_NETWORK_SECURITY_CONFIG);
        }

        ApplicationConfig config;
        if (configResourceId != 0) {
            boolean debugBuild = (info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            if (DBG) {
                Log.d(LOG_TAG, "Using Network Security Config from resource "
                        + context.getResources().getResourceEntryName(configResourceId)
                        + " debugBuild: " + debugBuild);
            }
            ConfigSource source = new XmlConfigSource(context, configResourceId, debugBuild);
            config = new ApplicationConfig(source);
        } else {
            if (DBG) {
                Log.d(LOG_TAG, "No Network Security Config specified, using platform default");
            }
            config = ApplicationConfig.getPlatformDefault();
        }

        ApplicationConfig.setDefaultInstance(config);
        int pos = Security.insertProviderAt(new NetworkSecurityConfigProvider(), 1);
        if (pos != 1) {
            throw new RuntimeException("Failed to install provider as highest priority provider."
                    + " Provider was installed at position " + pos);
        }
    }
}
