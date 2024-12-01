/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.platform.test.ravenwood;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @deprecated This class will be removed. Reach out to g/ravenwood if you need any features in it.
 */
@Deprecated
public final class RavenwoodConfig {
    /**
     * Use this to mark a field as the configuration.
     * @hide
     */
    @Target({ElementType.FIELD})
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Config {
    }

    /**
     * Stores internal states / methods associated with this config that's only needed in
     * junit-impl.
     */
    private RavenwoodConfig() {
    }

    /**
     * Return if the current process is running on a Ravenwood test environment.
     */
    public static boolean isOnRavenwood() {
        return RavenwoodRule.isOnRavenwood();
    }

    public static class Builder {
        private final RavenwoodConfig mConfig = new RavenwoodConfig();

        public Builder() {
        }

        /**
         * @deprecated no longer used. We always use an app UID.
         */
        @Deprecated
        public Builder setProcessSystem() {
            return this;
        }

        /**
         * @deprecated no longer used. We always use an app UID.
         */
        @Deprecated
        public Builder setProcessApp() {
            return this;
        }

        /**
         * @deprecated no longer used. Package name is set in the build file. (for now)
         */
        @Deprecated
        public Builder setPackageName(@NonNull String packageName) {
            return this;
        }

        /**
         * @deprecated no longer used. Package name is set in the build file. (for now)
         */
        @Deprecated
        public Builder setTargetPackageName(@NonNull String packageName) {
            return this;
        }


        /**
         * @deprecated no longer used. Target SDK level is set in the build file. (for now)
         */
        @Deprecated
        public Builder setTargetSdkLevel(int sdkLevel) {
            return this;
        }

        /**
         * @deprecated no longer used. Main thread is always available.
         */
        @Deprecated
        public Builder setProvideMainThread(boolean provideMainThread) {
            return this;
        }

        /**
         * @deprecated Use {@link RavenwoodRule.Builder#setSystemPropertyImmutable(String, Object)}
         */
        @Deprecated
        public Builder setSystemPropertyImmutable(@NonNull String key,
                @Nullable Object value) {
            return this;
        }

        /**
         * @deprecated Use {@link RavenwoodRule.Builder#setSystemPropertyMutable(String, Object)}
         */
        @Deprecated
        public Builder setSystemPropertyMutable(@NonNull String key,
                @Nullable Object value) {
            return this;
        }

        /**
         * @deprecated no longer used. All supported services are available.
         */
        @Deprecated
        public Builder setServicesRequired(@NonNull Class<?>... services) {
            return this;
        }

        public RavenwoodConfig build() {
            return mConfig;
        }
    }
}
