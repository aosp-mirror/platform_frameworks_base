/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.watchdog;

import static android.os.Parcelable.Creator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * A service to provide packages supporting explicit health checks and route checks to these
 * packages on behalf of the package watchdog.
 *
 * <p>To extend this class, you must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_EXPLICIT_HEALTH_CHECK_SERVICE} permission,
 * and include an intent filter with the {@link #SERVICE_INTERFACE} action. In adddition,
 * your implementation must live in {@link PackageManger#SYSTEM_SHARED_LIBRARY_SERVICES}.
 * For example:</p>
 * <pre>
 *     &lt;service android:name=".FooExplicitHealthCheckService"
 *             android:exported="true"
 *             android:priority="100"
 *             android:permission="android.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action android:name="android.service.watchdog.ExplicitHealthCheckService" /&gt;
 *         &lt;/intent-filter&gt;
 *     &lt;/service&gt;
 * </pre>
 * @hide
 */
@SystemApi
public abstract class ExplicitHealthCheckService extends Service {

    private static final String TAG = "ExplicitHealthCheckService";

    /**
     * {@link Bundle} key for a {@link List} of {@link PackageConfig} value.
     *
     * {@hide}
     */
    public static final String EXTRA_SUPPORTED_PACKAGES =
            "android.service.watchdog.extra.supported_packages";

    /**
     * {@link Bundle} key for a {@link List} of {@link String} value.
     *
     * {@hide}
     */
    public static final String EXTRA_REQUESTED_PACKAGES =
            "android.service.watchdog.extra.requested_packages";

    /**
     * {@link Bundle} key for a {@link String} value.
     *
     * {@hide}
     */
    public static final String EXTRA_HEALTH_CHECK_PASSED_PACKAGE =
            "android.service.watchdog.extra.health_check_passed_package";

    /**
     * The Intent action that a service must respond to. Add it to the intent filter of the service
     * in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.watchdog.ExplicitHealthCheckService";

    /**
     * The permission that a service must require to ensure that only Android system can bind to it.
     * If this permission is not enforced in the AndroidManifest of the service, the system will
     * skip that service.
     */
    public static final String BIND_PERMISSION =
            "android.permission.BIND_EXPLICIT_HEALTH_CHECK_SERVICE";

    private final ExplicitHealthCheckServiceWrapper mWrapper =
            new ExplicitHealthCheckServiceWrapper();

    /**
     * Called when the system requests an explicit health check for {@code packageName}.
     *
     * <p> When {@code packageName} passes the check, implementors should call
     * {@link #notifyHealthCheckPassed} to inform the system.
     *
     * <p> It could take many hours before a {@code packageName} passes a check and implementors
     * should never drop requests unless {@link onCancel} is called or the service dies.
     *
     * <p> Requests should not be queued and additional calls while expecting a result for
     * {@code packageName} should have no effect.
     */
    public abstract void onRequestHealthCheck(@NonNull String packageName);

    /**
     * Called when the system cancels the explicit health check request for {@code packageName}.
     * Should do nothing if there are is no active request for {@code packageName}.
     */
    public abstract void onCancelHealthCheck(@NonNull String packageName);

    /**
     * Called when the system requests for all the packages supporting explicit health checks. The
     * system may request an explicit health check for any of these packages with
     * {@link #onRequestHealthCheck}.
     *
     * @return all packages supporting explicit health checks
     */
    @NonNull public abstract List<PackageConfig> onGetSupportedPackages();

    /**
     * Called when the system requests for all the packages that it has currently requested
     * an explicit health check for.
     *
     * @return all packages expecting an explicit health check result
     */
    @NonNull public abstract List<String> onGetRequestedPackages();

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);
    @Nullable private RemoteCallback mCallback;

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    /**
     * Implementors should call this to notify the system when explicit health check passes
     * for {@code packageName};
     */
    public final void notifyHealthCheckPassed(@NonNull String packageName) {
        mHandler.post(() -> {
            if (mCallback != null) {
                Objects.requireNonNull(packageName,
                        "Package passing explicit health check must be non-null");
                Bundle bundle = new Bundle();
                bundle.putString(EXTRA_HEALTH_CHECK_PASSED_PACKAGE, packageName);
                mCallback.sendResult(bundle);
            } else {
                Log.wtf(TAG, "System missed explicit health check result for " + packageName);
            }
        });
    }

    /**
     * A PackageConfig contains a package supporting explicit health checks and the
     * timeout in {@link System#uptimeMillis} across reboots after which health
     * check requests from clients are failed.
     *
     * @hide
     */
    @SystemApi
    public static final class PackageConfig implements Parcelable {
        private static final long DEFAULT_HEALTH_CHECK_TIMEOUT_MILLIS = TimeUnit.HOURS.toMillis(1);

        private final String mPackageName;
        private final long mHealthCheckTimeoutMillis;

        /**
         * Creates a new instance.
         *
         * @param packageName the package name
         * @param durationMillis the duration in milliseconds, must be greater than or
         * equal to 0. If it is 0, it will use a system default value.
         */
        public PackageConfig(@NonNull String packageName, long healthCheckTimeoutMillis) {
            mPackageName = Preconditions.checkNotNull(packageName);
            if (healthCheckTimeoutMillis == 0) {
                mHealthCheckTimeoutMillis = DEFAULT_HEALTH_CHECK_TIMEOUT_MILLIS;
            } else {
                mHealthCheckTimeoutMillis = Preconditions.checkArgumentNonnegative(
                        healthCheckTimeoutMillis);
            }
        }

        private PackageConfig(Parcel parcel) {
            mPackageName = parcel.readString();
            mHealthCheckTimeoutMillis = parcel.readLong();
        }

        /**
         * Gets the package name.
         *
         * @return the package name
         */
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        /**
         * Gets the timeout in milliseconds to evaluate an explicit health check result after a
         * request.
         *
         * @return the duration in {@link System#uptimeMillis} across reboots
         */
        public long getHealthCheckTimeoutMillis() {
            return mHealthCheckTimeoutMillis;
        }

        @Override
        public String toString() {
            return "PackageConfig{" + mPackageName + ", " + mHealthCheckTimeoutMillis + "}";
        }

        @Override
        public boolean equals(Object other) {
            if (other == this) {
                return true;
            }
            if (!(other instanceof PackageConfig)) {
                return false;
            }

            PackageConfig otherInfo = (PackageConfig) other;
            return Objects.equals(otherInfo.getHealthCheckTimeoutMillis(),
                    mHealthCheckTimeoutMillis)
                    && Objects.equals(otherInfo.getPackageName(), mPackageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPackageName, mHealthCheckTimeoutMillis);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeString(mPackageName);
            parcel.writeLong(mHealthCheckTimeoutMillis);
        }

        public static final @NonNull Creator<PackageConfig> CREATOR = new Creator<PackageConfig>() {
                @Override
                public PackageConfig createFromParcel(Parcel source) {
                    return new PackageConfig(source);
                }

                @Override
                public PackageConfig[] newArray(int size) {
                    return new PackageConfig[size];
                }
            };
    }


    private class ExplicitHealthCheckServiceWrapper extends IExplicitHealthCheckService.Stub {
        @Override
        public void setCallback(RemoteCallback callback) throws RemoteException {
            mHandler.post(() -> {
                mCallback = callback;
            });
        }

        @Override
        public void request(String packageName) throws RemoteException {
            mHandler.post(() -> ExplicitHealthCheckService.this.onRequestHealthCheck(packageName));
        }

        @Override
        public void cancel(String packageName) throws RemoteException {
            mHandler.post(() -> ExplicitHealthCheckService.this.onCancelHealthCheck(packageName));
        }

        @Override
        public void getSupportedPackages(RemoteCallback callback) throws RemoteException {
            mHandler.post(() -> {
                List<PackageConfig> packages =
                        ExplicitHealthCheckService.this.onGetSupportedPackages();
                Objects.requireNonNull(packages, "Supported package list must be non-null");
                Bundle bundle = new Bundle();
                bundle.putParcelableArrayList(EXTRA_SUPPORTED_PACKAGES, new ArrayList<>(packages));
                callback.sendResult(bundle);
            });
        }

        @Override
        public void getRequestedPackages(RemoteCallback callback) throws RemoteException {
            mHandler.post(() -> {
                List<String> packages =
                        ExplicitHealthCheckService.this.onGetRequestedPackages();
                Objects.requireNonNull(packages, "Requested  package list must be non-null");
                Bundle bundle = new Bundle();
                bundle.putStringArrayList(EXTRA_REQUESTED_PACKAGES, new ArrayList<>(packages));
                callback.sendResult(bundle);
            });
        }
    }
}
