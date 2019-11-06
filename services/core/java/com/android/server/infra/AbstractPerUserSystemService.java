/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.infra;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Companion for {@link AbstractMasterSystemService}, it's the base class for the "real" service
 * implementation.
 *
 * @param <M> "master" service class.
 * @param <S> "real" service class.
 *
 * @hide
 */
public abstract class AbstractPerUserSystemService<S extends AbstractPerUserSystemService<S, M>,
        M extends AbstractMasterSystemService<M, S>> {

    protected final @UserIdInt int mUserId;
    protected final Object mLock;
    protected final String mTag = getClass().getSimpleName();

    protected final M mMaster;

    /**
     * Whether service was disabled for user due to {@link UserManager} restrictions.
     */
    @GuardedBy("mLock")
    private boolean mDisabled;

    /**
     * Caches whether the setup completed for the current user.
     */
    @GuardedBy("mLock")
    private boolean mSetupComplete;

    @GuardedBy("mLock")
    private ServiceInfo mServiceInfo;

    protected AbstractPerUserSystemService(@NonNull M master, @NonNull Object lock,
            @UserIdInt int userId) {
        mMaster = master;
        mLock = lock;
        mUserId = userId;
        updateIsSetupComplete(userId);
    }

    /** Updates whether setup is complete for current user */
    private void updateIsSetupComplete(@UserIdInt int userId) {
        final String setupComplete = Settings.Secure.getStringForUser(
                getContext().getContentResolver(), Settings.Secure.USER_SETUP_COMPLETE, userId);
        mSetupComplete = "1".equals(setupComplete);
    }

    /**
     * Creates a new {@link ServiceInfo} for the given service name.
     *
     * <p><b>MUST</b> be overridden by subclasses that bind to an
     * {@link com.android.internal.infra.AbstractRemoteService}.
     *
     * @throws NameNotFoundException if the service does not exist.
     * @throws SecurityException if the service does not have the proper permissions to be bound to.
     * @throws UnsupportedOperationException if subclass binds to a remote service but does not
     * overrides it.
     *
     * @return new {@link ServiceInfo},
     */
    protected @NonNull ServiceInfo newServiceInfoLocked(
            @SuppressWarnings("unused") @NonNull ComponentName serviceComponent)
            throws NameNotFoundException {
        throw new UnsupportedOperationException("not overridden");
    }

    /**
     * Callback called when an app has been updated.
     *
     * @param packageName package of the app being updated.
     */
    protected void handlePackageUpdateLocked(@NonNull String packageName) {
    }

    /**
     * Gets whether the service is enabled and ready.
     */
    @GuardedBy("mLock")
    protected boolean isEnabledLocked() {
        return mSetupComplete && mServiceInfo != null && !mDisabled;
    }

    /**
     * Gets whether the service is disabled by {@link UserManager} restrictions.
     */
    protected final boolean isDisabledByUserRestrictionsLocked() {
        return mDisabled;
    }

    /**
     * Updates the state of this service.
     *
     * <p>Typically called when the service {@link Settings} property or {@link UserManager}
     * restriction changed, which includes the initial creation of the service.
     *
     * <p>Subclasses can extend this method to provide extra initialization, like clearing up
     * previous state.
     *
     * @param disabled whether the service is disabled (due to {@link UserManager} restrictions).
     *
     * @return whether the disabled state changed.
     */
    @GuardedBy("mLock")
    @CallSuper
    protected boolean updateLocked(boolean disabled) {

        final boolean wasEnabled = isEnabledLocked();
        if (mMaster.verbose) {
            Slog.v(mTag, "updateLocked(u=" + mUserId + "): wasEnabled=" + wasEnabled
                    + ", mSetupComplete=" + mSetupComplete
                    + ", disabled=" + disabled + ", mDisabled=" + mDisabled);
        }

        updateIsSetupComplete(mUserId);
        mDisabled = disabled;

        updateServiceInfoLocked();
        return wasEnabled != isEnabledLocked();
    }

    /**
     * Updates the internal reference to the service info, and returns the service's component.
     */
    protected final ComponentName updateServiceInfoLocked() {
        ComponentName serviceComponent = null;
        if (mMaster.mServiceNameResolver != null) {
            ServiceInfo serviceInfo = null;
            final String componentName = getComponentNameLocked();
            if (!TextUtils.isEmpty(componentName)) {
                try {
                    serviceComponent = ComponentName.unflattenFromString(componentName);
                    serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                            0, mUserId);
                    if (serviceInfo == null) {
                        Slog.e(mTag, "Bad service name: " + componentName);
                    }
                } catch (RuntimeException | RemoteException e) {
                    Slog.e(mTag, "Error getting service info for '" + componentName + "': " + e);
                    serviceInfo = null;
                }
            }
            try {
                if (serviceInfo != null) {
                    mServiceInfo = newServiceInfoLocked(serviceComponent);
                    if (mMaster.debug) {
                        Slog.d(mTag, "Set component for user " + mUserId + " as "
                                + serviceComponent + " and info as " + mServiceInfo);
                    }
                } else {
                    mServiceInfo = null;
                    if (mMaster.debug) {
                        Slog.d(mTag, "Reset component for user " + mUserId + ":" + componentName);
                    }
                }
            } catch (Exception e) {
                Slog.e(mTag, "Bad ServiceInfo for '" + componentName + "': " + e);
                mServiceInfo = null;
            }
        }
        return serviceComponent;
    }

    /**
     * Gets the user associated with this service.
     */
    public final @UserIdInt int getUserId() {
        return mUserId;
    }

    /**
     * Gets the master service.
     */
    public final M getMaster() {
        return mMaster;
    }

    /**
     * Gets this UID of the remote service this service binds to, or {@code -1} if the service is
     * disabled.
     */
    @GuardedBy("mLock")
    protected final int getServiceUidLocked() {
        if (mServiceInfo == null) {
            if (mMaster.verbose) Slog.v(mTag, "getServiceUidLocked(): no mServiceInfo");
            return Process.INVALID_UID;
        }
        return mServiceInfo.applicationInfo.uid;
    }

    /**
     * Gets the current name of the service, which is either the default service or the
     *  {@link AbstractMasterSystemService#setTemporaryService(int, String, int) temporary one}.
     */
    protected final @Nullable String getComponentNameLocked() {
        return mMaster.mServiceNameResolver.getServiceName(mUserId);
    }

    /**
     * Checks whether the current service for the user was temporarily set.
     */
    public final boolean isTemporaryServiceSetLocked() {
        return mMaster.mServiceNameResolver.isTemporary(mUserId);
    }

    /**
     * Resets the temporary service implementation to the default component.
     */
    protected final void resetTemporaryServiceLocked() {
        mMaster.mServiceNameResolver.resetTemporaryService(mUserId);
    }

    /**
     * Gets the {@link ServiceInfo} of the remote service this service binds to, or {@code null}
     * if the service is disabled.
     */
    @Nullable
    public final ServiceInfo getServiceInfo() {
        return mServiceInfo;
    }

    /**
     * Gets the {@link ComponentName} of the remote service this service binds to, or {@code null}
     * if the service is disabled.
     */
    @Nullable
    public final ComponentName getServiceComponentName() {
        synchronized (mLock) {
            return mServiceInfo == null ? null : mServiceInfo.getComponentName();
        }
    }
    /**
     * Gets the name of the of the app this service binds to, or {@code null} if the service is
     * disabled.
     */
    @Nullable
    public final String getServicePackageName() {
        final ComponentName serviceComponent = getServiceComponentName();
        return serviceComponent == null ? null : serviceComponent.getPackageName();
    }

    /**
     * Gets the user-visibile name of the service this service binds to, or {@code null} if the
     * service is disabled.
     */
    @Nullable
    @GuardedBy("mLock")
    public final CharSequence getServiceLabelLocked() {
        return mServiceInfo == null ? null : mServiceInfo.loadSafeLabel(
                getContext().getPackageManager(), 0 /* do not ellipsize */,
                PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);
    }

    /**
     * Gets the icon the service this service binds to, or {@code null} if the service is disabled.
     */
    @Nullable
    @GuardedBy("mLock")
    public final Drawable getServiceIconLocked() {
        return mServiceInfo == null ? null
                : mServiceInfo.loadIcon(getContext().getPackageManager());
    }

    /**
     * Removes the service from the master's cache.
     */
    protected final void removeSelfFromCacheLocked() {
        mMaster.removeCachedServiceLocked(mUserId);
    }

    /**
     * Whether the service should log debug statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public final boolean isDebug() {
        return mMaster.debug;
    }

    /**
     * Whether the service should log verbose statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public final boolean isVerbose() {
        return mMaster.verbose;
    }

    /**
     * Gets the target SDK level of the service this service binds to,
     * or {@code 0} if the service is disabled.
     */
    public final int getTargedSdkLocked() {
        return mServiceInfo == null ? 0 : mServiceInfo.applicationInfo.targetSdkVersion;
    }

    /**
     * Gets whether the device already finished setup.
     */
    protected final boolean isSetupCompletedLocked() {
        return mSetupComplete;
    }

    /**
     * Gets the context associated with this service.
     */
    protected final Context getContext() {
        return mMaster.getContext();
    }

    // TODO(b/117779333): support proto
    @GuardedBy("mLock")
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("User: "); pw.println(mUserId);
        if (mServiceInfo != null) {
            pw.print(prefix); pw.print("Service Label: "); pw.println(getServiceLabelLocked());
            pw.print(prefix); pw.print("Target SDK: "); pw.println(getTargedSdkLocked());
        }
        if (mMaster.mServiceNameResolver != null) {
            pw.print(prefix); pw.print("Name resolver: ");
            mMaster.mServiceNameResolver.dumpShort(pw, mUserId); pw.println();
        }
        pw.print(prefix); pw.print("Disabled by UserManager: "); pw.println(mDisabled);
        pw.print(prefix); pw.print("Setup complete: "); pw.println(mSetupComplete);
        if (mServiceInfo != null) {
            pw.print(prefix); pw.print("Service UID: ");
            pw.println(mServiceInfo.applicationInfo.uid);
        }
        pw.println();
    }
}
