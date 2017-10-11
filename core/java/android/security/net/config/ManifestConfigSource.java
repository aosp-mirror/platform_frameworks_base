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
import android.util.Log;
import android.util.Pair;
import java.util.Set;

/** @hide */
public class ManifestConfigSource implements ConfigSource {
    private static final boolean DBG = true;
    private static final String LOG_TAG = "NetworkSecurityConfig";

    private final Object mLock = new Object();
    private final Context mContext;
    private final int mApplicationInfoFlags;
    private final int mTargetSdkVersion;
    private final int mConfigResourceId;
    private final int mTargetSandboxVesrsion;

    private ConfigSource mConfigSource;

    public ManifestConfigSource(Context context) {
        mContext = context;
        // Cache values because ApplicationInfo is mutable and apps do modify it :(
        ApplicationInfo info = context.getApplicationInfo();
        mApplicationInfoFlags = info.flags;
        mTargetSdkVersion = info.targetSdkVersion;
        mConfigResourceId = info.networkSecurityConfigRes;
        mTargetSandboxVesrsion = info.targetSandboxVersion;
    }

    @Override
    public Set<Pair<Domain, NetworkSecurityConfig>> getPerDomainConfigs() {
        return getConfigSource().getPerDomainConfigs();
    }

    @Override
    public NetworkSecurityConfig getDefaultConfig() {
        return getConfigSource().getDefaultConfig();
    }

    private ConfigSource getConfigSource() {
        synchronized (mLock) {
            if (mConfigSource != null) {
                return mConfigSource;
            }

            ConfigSource source;
            if (mConfigResourceId != 0) {
                boolean debugBuild = (mApplicationInfoFlags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                if (DBG) {
                    Log.d(LOG_TAG, "Using Network Security Config from resource "
                            + mContext.getResources().getResourceEntryName(mConfigResourceId)
                            + " debugBuild: " + debugBuild);
                }
                source = new XmlConfigSource(mContext, mConfigResourceId, debugBuild,
                        mTargetSdkVersion, mTargetSandboxVesrsion);
            } else {
                if (DBG) {
                    Log.d(LOG_TAG, "No Network Security Config specified, using platform default");
                }
                // the legacy FLAG_USES_CLEARTEXT_TRAFFIC is not supported for Ephemeral apps, they
                // should use the network security config.
                boolean usesCleartextTraffic =
                        (mApplicationInfoFlags & ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0
                        && mTargetSandboxVesrsion < 2;
                source = new DefaultConfigSource(usesCleartextTraffic, mTargetSdkVersion,
                        mTargetSandboxVesrsion);
            }
            mConfigSource = source;
            return mConfigSource;
        }
    }

    private static final class DefaultConfigSource implements ConfigSource {

        private final NetworkSecurityConfig mDefaultConfig;

        public DefaultConfigSource(boolean usesCleartextTraffic, int targetSdkVersion,
                int targetSandboxVesrsion) {
            mDefaultConfig = NetworkSecurityConfig.getDefaultBuilder(targetSdkVersion,
                    targetSandboxVesrsion)
                    .setCleartextTrafficPermitted(usesCleartextTraffic)
                    .build();
        }

        @Override
        public NetworkSecurityConfig getDefaultConfig() {
            return mDefaultConfig;
        }

        @Override
        public Set<Pair<Domain, NetworkSecurityConfig>> getPerDomainConfigs() {
            return null;
        }
    }
}
