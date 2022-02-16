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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AbstractRemoteService;
import com.android.internal.os.BackgroundThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Base class for {@link SystemService SystemServices} that support multi user.
 *
 * <p>Subclasses of this service are just a facade for the service binder calls - the "real" work
 * is done by the {@link AbstractPerUserSystemService} subclasses, which are automatically managed
 * through an user -> service cache.
 *
 * <p>It also takes care of other plumbing tasks such as:
 *
 * <ul>
 *   <li>Disabling the service when {@link UserManager} restrictions change.
 *   <li>Refreshing the service when its underlying
 *   {@link #getServiceSettingsProperty() Settings property} changed.
 *   <li>Calling the service when other Settings properties changed.
 * </ul>
 *
 * <p>See {@code com.android.server.autofill.AutofillManagerService} for a concrete
 * (no pun intended) example of how to use it.
 *
 * @param <M> "main" service class.
 * @param <S> "real" service class.
 *
 * @hide
 */
// TODO(b/117779333): improve javadoc above instead of using Autofill as an example
public abstract class AbstractMasterSystemService<M extends AbstractMasterSystemService<M, S>,
        S extends AbstractPerUserSystemService<S, M>> extends SystemService {

    /** On a package update, does not refresh the per-user service in the cache. */
    public static final int PACKAGE_UPDATE_POLICY_NO_REFRESH = 0x00000001;

    /**
     * On a package update, removes any existing per-user services in the cache.
     *
     * <p>This does not immediately recreate these services. It is assumed they will be recreated
     * for the next user request.
     */
    public static final int PACKAGE_UPDATE_POLICY_REFRESH_LAZY = 0x00000002;

    /**
     * On a package update, removes and recreates any existing per-user services in the cache.
     */
    public static final int PACKAGE_UPDATE_POLICY_REFRESH_EAGER = 0x00000004;

    /** On a package restart, does not refresh the per-user service in the cache. */
    public static final int PACKAGE_RESTART_POLICY_NO_REFRESH = 0x00000010;

    /**
     * On a package restart, removes any existing per-user services in the cache.
     *
     * <p>This does not immediately recreate these services. It is assumed they will be recreated
     * for the next user request.
     */
    public static final int PACKAGE_RESTART_POLICY_REFRESH_LAZY = 0x00000020;

    /**
     * On a package restart, removes and recreates any existing per-user services in the cache.
     */
    public static final int PACKAGE_RESTART_POLICY_REFRESH_EAGER = 0x00000040;

    @IntDef(flag = true, prefix = { "PACKAGE_" }, value = {
            PACKAGE_UPDATE_POLICY_NO_REFRESH,
            PACKAGE_UPDATE_POLICY_REFRESH_LAZY,
            PACKAGE_UPDATE_POLICY_REFRESH_EAGER,
            PACKAGE_RESTART_POLICY_NO_REFRESH,
            PACKAGE_RESTART_POLICY_REFRESH_LAZY,
            PACKAGE_RESTART_POLICY_REFRESH_EAGER
    })

    @Retention(RetentionPolicy.SOURCE)
    public @interface ServicePackagePolicyFlags {}

    /**
     * Log tag
     */
    protected final String mTag = getClass().getSimpleName();

    /**
     * Lock used to synchronize access to internal state; should be acquired before calling a
     * method whose name ends with {@code locked}.
     */
    protected final Object mLock = new Object();

    /**
     * Object used to define the name of the service component used to create
     * {@link com.android.internal.infra.AbstractRemoteService} instances.
     */
    @Nullable
    protected final ServiceNameResolver mServiceNameResolver;

    /**
     * Whether the service should log debug statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public boolean verbose = false;

    /**
     * Whether the service should log verbose statements.
     */
    //TODO(b/117779333): consider using constants for these guards
    public boolean debug = false;

    /**
     * Whether the service is allowed to bind to an instant-app.
     */
    @GuardedBy("mLock")
    protected boolean mAllowInstantService;

    /**
     * Users disabled due to {@link UserManager} restrictions, or {@code null} if the service cannot
     * be disabled through {@link UserManager}.
     */
    @GuardedBy("mLock")
    @Nullable
    private final SparseBooleanArray mDisabledByUserRestriction;

    /**
     * Cache of services per user id.
     */
    @GuardedBy("mLock")
    private final SparseArray<S> mServicesCache = new SparseArray<>();

    /**
     * Value that determines whether the per-user service should be removed from the cache when its
     * apk is updated or restarted.
     */
    private final @ServicePackagePolicyFlags int mServicePackagePolicyFlags;

    /**
     * Name of the service packages whose APK are being updated, keyed by user id.
     */
    @GuardedBy("mLock")
    private SparseArray<String> mUpdatingPackageNames;

    /**
     * Lazy-loadable reference to {@link UserManagerInternal}.
     */
    @Nullable
    private UserManagerInternal mUm;

    /**
     * Default constructor.
     *
     * <p>When using this constructor, the {@link AbstractPerUserSystemService} is removed from
     * the cache (and re-added) when the service package is updated.
     *
     * @param context system context.
     * @param serviceNameResolver resolver for
     * {@link com.android.internal.infra.AbstractRemoteService} instances, or
     * {@code null} when the service doesn't bind to remote services.
     * @param disallowProperty when not {@code null}, defines a {@link UserManager} restriction that
     *        disables the service. <b>NOTE: </b> you'll also need to add it to
     *        {@code UserRestrictionsUtils.USER_RESTRICTIONS}.
     */
    protected AbstractMasterSystemService(@NonNull Context context,
            @Nullable ServiceNameResolver serviceNameResolver,
            @Nullable String disallowProperty) {
        this(context, serviceNameResolver, disallowProperty,
                PACKAGE_UPDATE_POLICY_REFRESH_LAZY | PACKAGE_RESTART_POLICY_REFRESH_LAZY);
    }

    /**
     * Full Constructor.
     *
     * @param context system context.
     * @param serviceNameResolver resolver for
     * {@link com.android.internal.infra.AbstractRemoteService} instances, or
     * {@code null} when the service doesn't bind to remote services.
     * @param disallowProperty when not {@code null}, defines a {@link UserManager} restriction that
     *        disables the service. <b>NOTE: </b> you'll also need to add it to
     *        {@code UserRestrictionsUtils.USER_RESTRICTIONS}.
     * @param servicePackagePolicyFlags a combination of
     *        {@link #PACKAGE_UPDATE_POLICY_NO_REFRESH},
     *        {@link #PACKAGE_UPDATE_POLICY_REFRESH_LAZY},
     *        {@link #PACKAGE_UPDATE_POLICY_REFRESH_EAGER},
     *        {@link #PACKAGE_RESTART_POLICY_NO_REFRESH},
     *        {@link #PACKAGE_RESTART_POLICY_REFRESH_LAZY} or
     *        {@link #PACKAGE_RESTART_POLICY_REFRESH_EAGER}
     */
    protected AbstractMasterSystemService(@NonNull Context context,
            @Nullable ServiceNameResolver serviceNameResolver, @Nullable String disallowProperty,
            @ServicePackagePolicyFlags int servicePackagePolicyFlags) {
        super(context);

        final int updatePolicyMask = PACKAGE_UPDATE_POLICY_NO_REFRESH
                | PACKAGE_UPDATE_POLICY_REFRESH_LAZY | PACKAGE_UPDATE_POLICY_REFRESH_EAGER;
        if ((servicePackagePolicyFlags & updatePolicyMask) == 0) {
            // If the package update policy is not set, add the default flag
            servicePackagePolicyFlags |= PACKAGE_UPDATE_POLICY_REFRESH_LAZY;
        }
        final int restartPolicyMask = PACKAGE_RESTART_POLICY_NO_REFRESH
                | PACKAGE_RESTART_POLICY_REFRESH_LAZY | PACKAGE_RESTART_POLICY_REFRESH_EAGER;
        if ((servicePackagePolicyFlags & restartPolicyMask) == 0) {
            // If the package restart policy is not set, add the default flag
            servicePackagePolicyFlags |= PACKAGE_RESTART_POLICY_REFRESH_LAZY;
        }
        mServicePackagePolicyFlags = servicePackagePolicyFlags;

        mServiceNameResolver = serviceNameResolver;
        if (mServiceNameResolver != null) {
            mServiceNameResolver.setOnTemporaryServiceNameChangedCallback(
                    (u, s, t) -> onServiceNameChanged(u, s, t));

        }
        if (disallowProperty == null) {
            mDisabledByUserRestriction = null;
        } else {
            mDisabledByUserRestriction = new SparseBooleanArray();
            // Hookup with UserManager to disable service when necessary.
            final UserManagerInternal umi = getUserManagerInternal();
            final List<UserInfo> users = getSupportedUsers();
            for (int i = 0; i < users.size(); i++) {
                final int userId = users.get(i).id;
                final boolean disabled = umi.getUserRestriction(userId, disallowProperty);
                if (disabled) {
                    Slog.i(mTag, "Disabling by restrictions user " + userId);
                    mDisabledByUserRestriction.put(userId, disabled);
                }
            }
            umi.addUserRestrictionsListener((userId, newRestrictions, prevRestrictions) -> {
                final boolean disabledNow =
                        newRestrictions.getBoolean(disallowProperty, false);
                synchronized (mLock) {
                    final boolean disabledBefore = mDisabledByUserRestriction.get(userId);
                    if (disabledBefore == disabledNow) {
                        // Nothing changed, do nothing.
                        if (debug) {
                            Slog.d(mTag, "Restriction did not change for user " + userId);
                            return;
                        }
                    }
                    Slog.i(mTag, "Updating for user " + userId + ": disabled=" + disabledNow);
                    mDisabledByUserRestriction.put(userId, disabledNow);
                    updateCachedServiceLocked(userId, disabledNow);
                }
            });
        }
        startTrackingPackageChanges();
    }

    @Override // from SystemService
    public void onBootPhase(int phase) {
        if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
            new SettingsObserver(BackgroundThread.getHandler());
        }
    }

    @Override // from SystemService
    public void onUserUnlocking(@NonNull TargetUser user) {
        synchronized (mLock) {
            updateCachedServiceLocked(user.getUserIdentifier());
        }
    }

    @Override // from SystemService
    public void onUserStopped(@NonNull TargetUser user) {
        synchronized (mLock) {
            removeCachedServiceLocked(user.getUserIdentifier());
        }
    }

    /**
     * Gets whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by {@code ShellCommand} during CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final boolean getAllowInstantService() {
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            return mAllowInstantService;
        }
    }

    /**
     * Checks whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by subclasses when creating {@link AbstractRemoteService} instances.
     *
     * <p><b>NOTE: </b>must not be called by {@code ShellCommand} as it does not check for
     * permission.
     */
    public final boolean isBindInstantServiceAllowed() {
        synchronized (mLock) {
            return mAllowInstantService;
        }
    }

    /**
     * Sets whether the service is allowed to bind to an instant-app.
     *
     * <p>Typically called by {@code ShellCommand} during CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final void setAllowInstantService(boolean mode) {
        Slog.i(mTag, "setAllowInstantService(): " + mode);
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            mAllowInstantService = mode;
        }
    }

    /**
     * Temporarily sets the service implementation.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     *
     * @param componentName name of the new component
     * @param durationMs how long the change will be valid (the service will be automatically reset
     *            to the default component after this timeout expires).
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     * @throws IllegalArgumentException if value of {@code durationMs} is higher than
     *             {@link #getMaximumTemporaryServiceDurationMs()}.
     */
    public final void setTemporaryService(@UserIdInt int userId, @NonNull String componentName,
            int durationMs) {
        Slog.i(mTag, "setTemporaryService(" + userId + ") to " + componentName + " for "
                + durationMs + "ms");
        if (mServiceNameResolver == null) {
            return;
        }
        enforceCallingPermissionForManagement();

        Objects.requireNonNull(componentName);
        final int maxDurationMs = getMaximumTemporaryServiceDurationMs();
        if (durationMs > maxDurationMs) {
            throw new IllegalArgumentException(
                    "Max duration is " + maxDurationMs + " (called with " + durationMs + ")");
        }

        synchronized (mLock) {
            final S oldService = peekServiceForUserLocked(userId);
            if (oldService != null) {
                oldService.removeSelfFromCacheLocked();
            }
            mServiceNameResolver.setTemporaryService(userId, componentName, durationMs);
        }
    }

    /**
     * Sets whether the default service should be used.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     *
     * @return whether the enabled state changed.
     */
    public final boolean setDefaultServiceEnabled(@UserIdInt int userId, boolean enabled) {
        Slog.i(mTag, "setDefaultServiceEnabled() for userId " + userId + ": " + enabled);
        enforceCallingPermissionForManagement();

        synchronized (mLock) {
            if (mServiceNameResolver == null) {
                return false;
            }
            final boolean changed = mServiceNameResolver.setDefaultServiceEnabled(userId, enabled);
            if (!changed) {
                if (verbose) {
                    Slog.v(mTag, "setDefaultServiceEnabled(" + userId + "): already " + enabled);
                }
                return false;
            }

            final S oldService = peekServiceForUserLocked(userId);
            if (oldService != null) {
                oldService.removeSelfFromCacheLocked();
            }

            // Must update the service on cache so its initialization code is triggered
            updateCachedServiceLocked(userId);
        }
        return true;
    }

    /**
     * Checks whether the default service should be used.
     *
     * <p>Typically used during CTS tests to make sure only the default service doesn't interfere
     * with the test results.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final boolean isDefaultServiceEnabled(@UserIdInt int userId) {
        enforceCallingPermissionForManagement();

        if (mServiceNameResolver == null) {
            return false;
        }

        synchronized (mLock) {
            return mServiceNameResolver.isDefaultServiceEnabled(userId);
        }
    }

    /**
     * Gets the maximum time the service implementation can be changed.
     *
     * @throws UnsupportedOperationException if subclass doesn't override it.
     */
    protected int getMaximumTemporaryServiceDurationMs() {
        throw new UnsupportedOperationException("Not implemented by " + getClass());
    }

    /**
     * Resets the temporary service implementation to the default component.
     *
     * <p>Typically used by Shell command and/or CTS tests.
     *
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    public final void resetTemporaryService(@UserIdInt int userId) {
        Slog.i(mTag, "resetTemporaryService(): " + userId);
        enforceCallingPermissionForManagement();
        synchronized (mLock) {
            final S service = getServiceForUserLocked(userId);
            if (service != null) {
                service.resetTemporaryServiceLocked();
            }
        }
    }

    /**
     * Asserts that the caller has permissions to manage this service.
     *
     * <p>Typically called by {@code ShellCommand} implementations.
     *
     * @throws UnsupportedOperationException if subclass doesn't override it.
     * @throws SecurityException if caller is not allowed to manage this service's settings.
     */
    protected void enforceCallingPermissionForManagement() {
        throw new UnsupportedOperationException("Not implemented by " + getClass());
    }

    /**
     * Creates a new service that will be added to the cache.
     *
     * @param resolvedUserId the resolved user id for the service.
     * @param disabled whether the service is currently disabled (due to {@link UserManager}
     * restrictions).
     *
     * @return a new instance.
     */
    @Nullable
    protected abstract S newServiceLocked(@UserIdInt int resolvedUserId, boolean disabled);

    /**
     * Register the service for extra Settings changes (i.e., other than
     * {@link android.provider.Settings.Secure#USER_SETUP_COMPLETE} or
     * {@link #getServiceSettingsProperty()}, which are automatically handled).
     *
     * <p> Example:
     *
     * <pre><code>
     * resolver.registerContentObserver(Settings.Global.getUriFor(
     *     Settings.Global.AUTOFILL_COMPAT_MODE_ALLOWED_PACKAGES), false, observer,
     *     UserHandle.USER_ALL);
     * </code></pre>
     *
     * <p><b>NOTE: </p>it doesn't need to register for
     * {@link android.provider.Settings.Secure#USER_SETUP_COMPLETE} or
     * {@link #getServiceSettingsProperty()}.
     *
     */
    @SuppressWarnings("unused")
    protected void registerForExtraSettingsChanges(@NonNull ContentResolver resolver,
            @NonNull ContentObserver observer) {
    }

    /**
     * Callback for Settings changes that were registered though
     * {@link #registerForExtraSettingsChanges(ContentResolver, ContentObserver)}.
     *
     * @param userId user associated with the change
     * @param property Settings property changed.
     */
    protected void onSettingsChanged(@UserIdInt int userId, @NonNull String property) {
    }

    /**
     * Gets the service instance for an user, creating an instance if not present in the cache.
     */
    @GuardedBy("mLock")
    @NonNull
    protected S getServiceForUserLocked(@UserIdInt int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        S service = mServicesCache.get(resolvedUserId);
        if (service == null) {
            final boolean disabled = isDisabledLocked(userId);
            service = newServiceLocked(resolvedUserId, disabled);
            if (!disabled) {
                onServiceEnabledLocked(service, resolvedUserId);
            }
            mServicesCache.put(userId, service);
        }
        return service;
    }

    /**
     * Gets the <b>existing</b> service instance for a user, returning {@code null} if not already
     * present in the cache.
     */
    @GuardedBy("mLock")
    @Nullable
    protected S peekServiceForUserLocked(@UserIdInt int userId) {
        final int resolvedUserId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                Binder.getCallingUid(), userId, false, false, null, null);
        return mServicesCache.get(resolvedUserId);
    }

    /**
     * Updates a cached service for a given user.
     */
    @GuardedBy("mLock")
    protected void updateCachedServiceLocked(@UserIdInt int userId) {
        updateCachedServiceLocked(userId, isDisabledLocked(userId));
    }

    /**
     * Checks whether the service is disabled (through {@link UserManager} restrictions) for the
     * given user.
     */
    protected boolean isDisabledLocked(@UserIdInt int userId) {
        return mDisabledByUserRestriction == null ? false : mDisabledByUserRestriction.get(userId);
    }

    /**
     * Updates a cached service for a given user.
     *
     * @param userId user handle.
     * @param disabled whether the user is disabled.
     * @return service for the user.
     */
    @GuardedBy("mLock")
    protected S updateCachedServiceLocked(@UserIdInt int userId, boolean disabled) {
        final S service = getServiceForUserLocked(userId);
        if (service != null) {
            service.updateLocked(disabled);
            if (!service.isEnabledLocked()) {
                removeCachedServiceLocked(userId);
            } else {
                onServiceEnabledLocked(service, userId);
            }
        }
        return service;
    }

    /**
     * Gets the Settings property that defines the name of the component name used to bind this
     * service to an external service, or {@code null} when the service is not defined by such
     * property (for example, if it's a system service defined by framework resources).
     */
    @Nullable
    protected String getServiceSettingsProperty() {
        return null;
    }

    /**
     * Callback called after a new service was added to the cache, or an existing service that was
     * previously disabled gets enabled.
     *
     * <p>By default doesn't do anything, but can be overridden by subclasses.
     */
    @SuppressWarnings("unused")
    protected void onServiceEnabledLocked(@NonNull S service, @UserIdInt int userId) {
    }

    /**
     * Removes a cached service for a given user.
     *
     * @return the removed service.
     */
    @GuardedBy("mLock")
    @NonNull
    protected final S removeCachedServiceLocked(@UserIdInt int userId) {
        final S service = peekServiceForUserLocked(userId);
        if (service != null) {
            mServicesCache.delete(userId);
            onServiceRemoved(service, userId);
        }
        return service;
    }

    /**
     * Called before the package that provides the service for the given user is being updated.
     */
    protected void onServicePackageUpdatingLocked(@UserIdInt int userId) {
        if (verbose) Slog.v(mTag, "onServicePackageUpdatingLocked(" + userId + ")");
    }

    /**
     * Called after the package that provides the service for the given user is being updated.
     */
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        if (verbose) Slog.v(mTag, "onServicePackageUpdated(" + userId + ")");
    }

    /**
     * Called after the package data that provides the service for the given user is cleared.
     */
    protected void onServicePackageDataClearedLocked(@UserIdInt int userId) {
        if (verbose) Slog.v(mTag, "onServicePackageDataCleared(" + userId + ")");
    }

    /**
     * Called after the package that provides the service for the given user is restarted.
     */
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        if (verbose) Slog.v(mTag, "onServicePackageRestarted(" + userId + ")");
    }

    /**
     * Called after the service is removed from the cache.
     */
    @SuppressWarnings("unused")
    protected void onServiceRemoved(@NonNull S service, @UserIdInt int userId) {
    }

    /**
     * Called when the service name changed (typically when using temporary services).
     *
     * <p>By default, it calls {@link #updateCachedServiceLocked(int)}; subclasses must either call
     * that same method, or {@code super.onServiceNameChanged()}.
     *
     * @param userId user handle.
     * @param serviceName the new service name.
     * @param isTemporary whether the new service is temporary.
     */
    protected void onServiceNameChanged(@UserIdInt int userId, @Nullable String serviceName,
            boolean isTemporary) {
        synchronized (mLock) {
            updateCachedServiceLocked(userId);
        }
    }

    /**
     * Visits all services in the cache.
     */
    @GuardedBy("mLock")
    protected void visitServicesLocked(@NonNull Visitor<S> visitor) {
        final int size = mServicesCache.size();
        for (int i = 0; i < size; i++) {
            visitor.visit(mServicesCache.valueAt(i));
        }
    }

    /**
     * Clear the cache by removing all services.
     */
    @GuardedBy("mLock")
    protected void clearCacheLocked() {
        mServicesCache.clear();
    }

    /**
     * Gets a cached reference to {@link UserManagerInternal}.
     */
    @NonNull
    protected UserManagerInternal getUserManagerInternal() {
        if (mUm == null) {
            if (verbose) Slog.v(mTag, "lazy-loading UserManagerInternal");
            mUm = LocalServices.getService(UserManagerInternal.class);
        }
        return mUm;
    }

    /**
     * Gets a list of all supported users (i.e., those that pass the
     * {@link #isUserSupported(TargetUser)}check).
     */
    @NonNull
    protected List<UserInfo> getSupportedUsers() {
        final UserInfo[] allUsers = getUserManagerInternal().getUserInfos();
        final int size = allUsers.length;
        final List<UserInfo> supportedUsers = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            final UserInfo userInfo = allUsers[i];
            if (isUserSupported(new TargetUser(userInfo))) {
                supportedUsers.add(userInfo);
            }
        }
        return supportedUsers;
    }

    /**
     * Asserts that the given package name is owned by the UID making this call.
     *
     * @throws SecurityException when it's not...
     */
    protected void assertCalledByPackageOwner(@NonNull String packageName) {
        Objects.requireNonNull(packageName);
        final int uid = Binder.getCallingUid();
        final String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) {
            for (String candidate : packages) {
                if (packageName.equals(candidate)) return; // Found it
            }
        }
        throw new SecurityException("UID " + uid + " does not own " + packageName);
    }

    // TODO(b/117779333): support proto
    protected void dumpLocked(@NonNull String prefix, @NonNull PrintWriter pw) {
        boolean realDebug = debug;
        boolean realVerbose = verbose;
        final String prefix2 = "    ";

        try {
            // Temporarily turn on full logging;
            debug = verbose = true;
            final int size = mServicesCache.size();
            pw.print(prefix); pw.print("Debug: "); pw.print(realDebug);
            pw.print(" Verbose: "); pw.println(realVerbose);
            pw.print("Package policy flags: "); pw.println(mServicePackagePolicyFlags);
            if (mUpdatingPackageNames != null) {
                pw.print("Packages being updated: "); pw.println(mUpdatingPackageNames);
            }
            dumpSupportedUsers(pw, prefix);
            if (mServiceNameResolver != null) {
                pw.print(prefix); pw.print("Name resolver: ");
                mServiceNameResolver.dumpShort(pw); pw.println();
                final List<UserInfo> users = getSupportedUsers();
                for (int i = 0; i < users.size(); i++) {
                    final int userId = users.get(i).id;
                    pw.print(prefix2); pw.print(userId); pw.print(": ");
                    mServiceNameResolver.dumpShort(pw, userId); pw.println();
                }
            }
            pw.print(prefix); pw.print("Users disabled by restriction: ");
            pw.println(mDisabledByUserRestriction);
            pw.print(prefix); pw.print("Allow instant service: "); pw.println(mAllowInstantService);
            final String settingsProperty = getServiceSettingsProperty();
            if (settingsProperty != null) {
                pw.print(prefix); pw.print("Settings property: "); pw.println(settingsProperty);
            }
            pw.print(prefix); pw.print("Cached services: ");
            if (size == 0) {
                pw.println("none");
            } else {
                pw.println(size);
                for (int i = 0; i < size; i++) {
                    pw.print(prefix); pw.print("Service at "); pw.print(i); pw.println(": ");
                    final S service = mServicesCache.valueAt(i);
                    service.dumpLocked(prefix2, pw);
                    pw.println();
                }
            }
        } finally {
            debug = realDebug;
            verbose = realVerbose;
        }
    }

    private void startTrackingPackageChanges() {
        final PackageMonitor monitor = new PackageMonitor() {

            @Override
            public void onPackageUpdateStarted(@NonNull String packageName, int uid) {
                if (verbose) Slog.v(mTag, "onPackageUpdateStarted(): " + packageName);
                final String activePackageName = getActiveServicePackageNameLocked();
                if (!packageName.equals(activePackageName)) return;

                final int userId = getChangingUserId();
                synchronized (mLock) {
                    if (mUpdatingPackageNames == null) {
                        mUpdatingPackageNames = new SparseArray<String>(mServicesCache.size());
                    }
                    mUpdatingPackageNames.put(userId, packageName);
                    onServicePackageUpdatingLocked(userId);
                    if ((mServicePackagePolicyFlags & PACKAGE_UPDATE_POLICY_NO_REFRESH) != 0) {
                        if (debug) {
                            Slog.d(mTag, "Holding service for user " + userId + " while package "
                                    + activePackageName + " is being updated");
                        }
                    } else {
                        if (debug) {
                            Slog.d(mTag, "Removing service for user " + userId
                                    + " because package " + activePackageName
                                    + " is being updated");
                        }
                        removeCachedServiceLocked(userId);

                        if ((mServicePackagePolicyFlags & PACKAGE_UPDATE_POLICY_REFRESH_EAGER)
                                != 0) {
                            if (debug) {
                                Slog.d(mTag, "Eagerly recreating service for user "
                                        + userId);
                            }
                            getServiceForUserLocked(userId);
                        }
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(@NonNull String packageName, int uid) {
                if (verbose) Slog.v(mTag, "onPackageUpdateFinished(): " + packageName);
                final int userId = getChangingUserId();
                synchronized (mLock) {
                    final String activePackageName = mUpdatingPackageNames == null ? null
                            : mUpdatingPackageNames.get(userId);
                    if (packageName.equals(activePackageName)) {
                        if (mUpdatingPackageNames != null) {
                            mUpdatingPackageNames.remove(userId);
                            if (mUpdatingPackageNames.size() == 0) {
                                mUpdatingPackageNames = null;
                            }
                        }
                        onServicePackageUpdatedLocked(userId);
                    } else {
                        handlePackageUpdateLocked(packageName);
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    final int userId = getChangingUserId();
                    final S service = peekServiceForUserLocked(userId);
                    if (service != null) {
                        final ComponentName componentName = service.getServiceComponentName();
                        if (componentName != null) {
                            if (packageName.equals(componentName.getPackageName())) {
                                handleActiveServiceRemoved(userId);
                            }
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] packages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    final String activePackageName = getActiveServicePackageNameLocked();
                    for (String pkg : packages) {
                        if (pkg.equals(activePackageName)) {
                            if (!doit) {
                                return true;
                            }
                            final String action = intent.getAction();
                            final int userId = getChangingUserId();
                            if (Intent.ACTION_PACKAGE_RESTARTED.equals(action)) {
                                handleActiveServiceRestartedLocked(activePackageName, userId);
                            } else {
                                removeCachedServiceLocked(userId);
                            }
                        } else {
                            handlePackageUpdateLocked(pkg);
                        }
                    }
                }
                return false;
            }

            @Override
            public void onPackageDataCleared(String packageName, int uid) {
                if (verbose) Slog.v(mTag, "onPackageDataCleared(): " + packageName);
                final int userId = getChangingUserId();
                synchronized (mLock) {
                    final S service = peekServiceForUserLocked(userId);
                    if (service != null) {
                        final ComponentName componentName = service.getServiceComponentName();
                        if (componentName != null) {
                            if (packageName.equals(componentName.getPackageName())) {
                                onServicePackageDataClearedLocked(userId);
                            }
                        }
                    }
                }
            }

            private void handleActiveServiceRemoved(@UserIdInt int userId) {
                synchronized (mLock) {
                    removeCachedServiceLocked(userId);
                }
                final String serviceSettingsProperty = getServiceSettingsProperty();
                if (serviceSettingsProperty != null) {
                    Settings.Secure.putStringForUser(getContext().getContentResolver(),
                            serviceSettingsProperty, null, userId);
                }
            }

            private void handleActiveServiceRestartedLocked(String activePackageName,
                    @UserIdInt int userId) {
                if ((mServicePackagePolicyFlags & PACKAGE_RESTART_POLICY_NO_REFRESH) != 0) {
                    if (debug) {
                        Slog.d(mTag, "Holding service for user " + userId + " while package "
                                + activePackageName + " is being restarted");
                    }
                } else {
                    if (debug) {
                        Slog.d(mTag, "Removing service for user " + userId
                                + " because package " + activePackageName
                                + " is being restarted");
                    }
                    removeCachedServiceLocked(userId);

                    if ((mServicePackagePolicyFlags & PACKAGE_RESTART_POLICY_REFRESH_EAGER) != 0) {
                        if (debug) {
                            Slog.d(mTag, "Eagerly recreating service for user " + userId);
                        }
                        updateCachedServiceLocked(userId);
                    }
                }
                onServicePackageRestartedLocked(userId);
            }

            @Override
            public void onPackageModified(String packageName) {
                if (verbose) Slog.v(mTag, "onPackageModified(): " + packageName);

                if (mServiceNameResolver == null) {
                    return;
                }

                final int userId = getChangingUserId();
                final String serviceName = mServiceNameResolver.getDefaultServiceName(userId);
                if (serviceName == null) {
                    return;
                }

                final ComponentName serviceComponentName =
                        ComponentName.unflattenFromString(serviceName);
                if (serviceComponentName == null
                        || !serviceComponentName.getPackageName().equals(packageName)) {
                    return;
                }

                // The default service package has changed, update the cached if the service
                // exists but no active component.
                final S service = peekServiceForUserLocked(userId);
                if (service != null) {
                    final ComponentName componentName = service.getServiceComponentName();
                    if (componentName == null) {
                        if (verbose) Slog.v(mTag, "update cached");
                        updateCachedServiceLocked(userId);
                    }
                }
            }

            private String getActiveServicePackageNameLocked() {
                final int userId = getChangingUserId();
                final S service = peekServiceForUserLocked(userId);
                if (service == null) {
                    return null;
                }
                final ComponentName serviceComponent = service.getServiceComponentName();
                if (serviceComponent == null) {
                    return null;
                }
                return serviceComponent.getPackageName();
            }

            @GuardedBy("mLock")
            private void handlePackageUpdateLocked(String packageName) {
                visitServicesLocked((s) -> s.handlePackageUpdateLocked(packageName));
            }
        };

        // package changes
        monitor.register(getContext(), null,  UserHandle.ALL, true);
    }

    /**
     * Visitor pattern.
     *
     * @param <S> visited class.
     */
    public interface Visitor<S> {
        /**
         * Visits a service.
         *
         * @param service the service to be visited.
         */
        void visit(@NonNull S service);
    }

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = getContext().getContentResolver();
            final String serviceProperty = getServiceSettingsProperty();
            if (serviceProperty != null) {
                resolver.registerContentObserver(Settings.Secure.getUriFor(
                        serviceProperty), false, this, UserHandle.USER_ALL);
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.USER_SETUP_COMPLETE), false, this, UserHandle.USER_ALL);
            registerForExtraSettingsChanges(resolver, this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, @UserIdInt int userId) {
            if (verbose) Slog.v(mTag, "onChange(): uri=" + uri + ", userId=" + userId);
            final String property = uri.getLastPathSegment();
            if (property == null) {
                return;
            }
            if (property.equals(getServiceSettingsProperty())
                    || property.equals(Settings.Secure.USER_SETUP_COMPLETE)) {
                synchronized (mLock) {
                    updateCachedServiceLocked(userId);
                }
            } else {
                onSettingsChanged(userId, property);
            }
        }
    }
}
