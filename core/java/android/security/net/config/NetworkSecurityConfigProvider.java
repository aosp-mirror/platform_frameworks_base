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
import android.util.Log;

import java.security.Provider;
import java.security.Security;

/** @hide */
public final class NetworkSecurityConfigProvider extends Provider {
    private static final String LOG_TAG = "nsconfig";
    private static final String PREFIX =
            NetworkSecurityConfigProvider.class.getPackage().getName() + ".";

    public NetworkSecurityConfigProvider() {
        // TODO: More clever name than this
        super("AndroidNSSP", 1.0, "Android Network Security Policy Provider");
        put("TrustManagerFactory.PKIX", PREFIX + "RootTrustManagerFactorySpi");
        put("Alg.Alias.TrustManagerFactory.X509", "PKIX");
    }

    public static void install(Context context) {
        ApplicationConfig config = new ApplicationConfig(new ManifestConfigSource(context));
        ApplicationConfig.setDefaultInstance(config);
        int pos = Security.insertProviderAt(new NetworkSecurityConfigProvider(), 1);
        if (pos != 1) {
            throw new RuntimeException("Failed to install provider as highest priority provider."
                    + " Provider was installed at position " + pos);
        }
        libcore.net.NetworkSecurityPolicy.setInstance(new ConfigNetworkSecurityPolicy(config));
    }

    /**
     * For a shared process, resolves conflicting values of usesCleartextTraffic.
     * 1. Throws a RuntimeException if the shared process with conflicting
     * usesCleartextTraffic values have per domain rules.
     * 2. Sets the default instance to the least strict config.
     */
    public static void handleNewApplication(Context context) {
        ApplicationConfig config = new ApplicationConfig(new ManifestConfigSource(context));
        ApplicationConfig defaultConfig = ApplicationConfig.getDefaultInstance();
        String mProcessName = context.getApplicationInfo().processName;
        if (defaultConfig != null) {
            if (defaultConfig.isCleartextTrafficPermitted()
                    != config.isCleartextTrafficPermitted()) {
                Log.w(LOG_TAG, mProcessName
                        + ": New config does not match the previously set config.");

                if (defaultConfig.hasPerDomainConfigs()
                        || config.hasPerDomainConfigs()) {
                    throw new RuntimeException("Found multiple conflicting per-domain rules");
                }
                config = defaultConfig.isCleartextTrafficPermitted() ? defaultConfig : config;
            }
        }
        ApplicationConfig.setDefaultInstance(config);
    }
}
