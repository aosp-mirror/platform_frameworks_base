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

import static android.os.Process.FIRST_APPLICATION_UID;
import static android.os.UserHandle.SYSTEM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Instrumentation;
import android.content.Context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    private static final int NOBODY_UID = 9999;

    private static final AtomicInteger sNextPid = new AtomicInteger(100);

    int mCurrentUser = SYSTEM.getIdentifier();

    /**
     * Unless the test author requests differently, run as "nobody", and give each collection of
     * tests its own unique PID.
     */
    int mUid = FIRST_APPLICATION_UID;
    int mPid = sNextPid.getAndIncrement();

    String mTestPackageName;
    String mTargetPackageName;

    int mTargetSdkLevel;

    final RavenwoodSystemProperties mSystemProperties = new RavenwoodSystemProperties();

    final List<Class<?>> mServicesRequired = new ArrayList<>();

    volatile Context mInstContext;
    volatile Context mTargetContext;
    volatile Instrumentation mInstrumentation;

    /**
     * Stores internal states / methods associated with this config that's only needed in
     * junit-impl.
     */
    final RavenwoodConfigState mState = new RavenwoodConfigState(this);
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

        Builder setSystemPropertyImmutableReal(@NonNull String key,
                @Nullable Object value) {
            mConfig.mSystemProperties.setValue(key, value);
            mConfig.mSystemProperties.setAccessReadOnly(key);
            return this;
        }

        Builder setSystemPropertyMutableReal(@NonNull String key,
                @Nullable Object value) {
            mConfig.mSystemProperties.setValue(key, value);
            mConfig.mSystemProperties.setAccessReadWrite(key);
            return this;
        }

        /**
         * Configure the set of system services that are required for this test to operate.
         *
         * For example, passing {@code android.hardware.SerialManager.class} as an argument will
         * ensure that the underlying service is created, initialized, and ready to use for the
         * duration of the test. The {@code SerialManager} instance can be obtained via
         * {@code RavenwoodRule.getContext()} and {@code Context.getSystemService()}, and
         * {@code SerialManagerInternal} can be obtained via {@code LocalServices.getService()}.
         */
        public Builder setServicesRequired(@NonNull Class<?>... services) {
            mConfig.mServicesRequired.clear();
            for (Class<?> service : services) {
                mConfig.mServicesRequired.add(service);
            }
            return this;
        }

        public RavenwoodConfig build() {
            return mConfig;
        }
    }
}
