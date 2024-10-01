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
import static android.os.Process.SYSTEM_UID;
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
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents how to configure the ravenwood environment for a test class.
 *
 * If a ravenwood test class has a public static field with the {@link Config} annotation,
 * Ravenwood will extract the config from it and initializes the environment. The type of the
 * field must be of {@link RavenwoodConfig}.
 */
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
    int mUid = NOBODY_UID;
    int mPid = sNextPid.getAndIncrement();

    String mTestPackageName;
    String mTargetPackageName;

    int mMinSdkLevel;

    boolean mProvideMainThread = false;

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

    private void setDefaults() {
        if (mTargetPackageName == null) {
            mTargetPackageName = mTestPackageName;
        }
    }

    public static class Builder {
        private final RavenwoodConfig mConfig = new RavenwoodConfig();

        public Builder() {
        }

        /**
         * Configure the identity of this process to be the system UID for the duration of the
         * test. Has no effect on non-Ravenwood environments.
         */
        public Builder setProcessSystem() {
            mConfig.mUid = SYSTEM_UID;
            return this;
        }

        /**
         * Configure the identity of this process to be an app UID for the duration of the
         * test. Has no effect on non-Ravenwood environments.
         */
        public Builder setProcessApp() {
            mConfig.mUid = FIRST_APPLICATION_UID;
            return this;
        }

        /**
         * Configure the package name of the test, which corresponds to
         * {@link Instrumentation#getContext()}.
         */
        public Builder setPackageName(@NonNull String packageName) {
            mConfig.mTestPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Configure the package name of the target app, which corresponds to
         * {@link Instrumentation#getTargetContext()}. Defaults to {@link #setPackageName}.
         */
        public Builder setTargetPackageName(@NonNull String packageName) {
            mConfig.mTargetPackageName = Objects.requireNonNull(packageName);
            return this;
        }

        /**
         * Configure the min SDK level of the test.
         */
        public Builder setMinSdkLevel(int sdkLevel) {
            mConfig.mMinSdkLevel = sdkLevel;
            return this;
        }

        /**
         * Configure a "main" thread to be available for the duration of the test, as defined
         * by {@code Looper.getMainLooper()}. Has no effect on non-Ravenwood environments.
         */
        public Builder setProvideMainThread(boolean provideMainThread) {
            mConfig.mProvideMainThread = provideMainThread;
            return this;
        }

        /**
         * Configure the given system property as immutable for the duration of the test.
         * Read access to the key is allowed, and write access will fail. When {@code value} is
         * {@code null}, the value is left as undefined.
         *
         * All properties in the {@code debug.*} namespace are automatically mutable, with no
         * developer action required.
         *
         * Has no effect on non-Ravenwood environments.
         */
        public Builder setSystemPropertyImmutable(@NonNull String key,
                @Nullable Object value) {
            mConfig.mSystemProperties.setValue(key, value);
            mConfig.mSystemProperties.setAccessReadOnly(key);
            return this;
        }

        /**
         * Configure the given system property as mutable for the duration of the test.
         * Both read and write access to the key is allowed, and its value will be reset between
         * each test. When {@code value} is {@code null}, the value is left as undefined.
         *
         * All properties in the {@code debug.*} namespace are automatically mutable, with no
         * developer action required.
         *
         * Has no effect on non-Ravenwood environments.
         */
        public Builder setSystemPropertyMutable(@NonNull String key,
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
            mConfig.setDefaults();
            return mConfig;
        }
    }
}
