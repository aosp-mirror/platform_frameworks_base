/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.role.RoleManager;
import android.os.Binder;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.infra.AndroidFuture;
import com.android.internal.util.CollectionUtils;
import com.android.server.FgThread;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Interacts with {@link RoleManager} to provide and manage default apps.
 */
public class DefaultAppProvider {
    @NonNull
    private final Supplier<RoleManager> mRoleManagerSupplier;
    @NonNull
    private final Supplier<UserManagerInternal> mUserManagerInternalSupplier;

    /**
     * Create a new instance of this class
     *
     * @param roleManagerSupplier the supplier for {@link RoleManager}
     */
    public DefaultAppProvider(@NonNull Supplier<RoleManager> roleManagerSupplier,
            @NonNull Supplier<UserManagerInternal> userManagerInternalSupplier) {
        mRoleManagerSupplier = roleManagerSupplier;
        mUserManagerInternalSupplier = userManagerInternalSupplier;
    }

    /**
     * Get the package name of the default browser.
     *
     * @param userId the user ID
     * @return the package name of the default browser, or {@code null} if none
     */
    @Nullable
    public String getDefaultBrowser(@UserIdInt int userId) {
        return getRoleHolder(RoleManager.ROLE_BROWSER, userId);
    }

    /**
     * Set the package name of the default browser.
     *
     * @param packageName package name of the default browser, or {@code null} to unset
     * @param async whether the operation should be asynchronous
     * @param userId the user ID
     * @return whether the default browser was successfully set.
     */
    public boolean setDefaultBrowser(@Nullable String packageName, boolean async,
            @UserIdInt int userId) {
        if (userId == UserHandle.USER_ALL) {
            return false;
        }
        final RoleManager roleManager = mRoleManagerSupplier.get();
        if (roleManager == null) {
            return false;
        }
        final UserHandle user = UserHandle.of(userId);
        final Executor executor = FgThread.getExecutor();
        final AndroidFuture<Void> future = new AndroidFuture<>();
        final Consumer<Boolean> callback = successful -> {
            if (successful) {
                future.complete(null);
            } else {
                future.completeExceptionally(new RuntimeException());
            }
        };
        final long identity = Binder.clearCallingIdentity();
        try {
            if (packageName != null) {
                roleManager.addRoleHolderAsUser(RoleManager.ROLE_BROWSER, packageName, 0, user,
                        executor, callback);
            } else {
                roleManager.clearRoleHoldersAsUser(RoleManager.ROLE_BROWSER, 0, user, executor,
                        callback);
            }
            if (!async) {
                try {
                    future.get(5, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    Slog.e(PackageManagerService.TAG, "Exception while setting default browser: "
                            + packageName, e);
                    return false;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    /**
     * Get the package name of the default dialer.
     *
     * @param userId the user ID
     * @return the package name of the default dialer, or {@code null} if none
     */
    @Nullable
    public String getDefaultDialer(@NonNull int userId) {
        return getRoleHolder(RoleManager.ROLE_DIALER, userId);
    }

    /**
     * Get the package name of the default home.
     *
     * @param userId the user ID
     * @return the package name of the default home, or {@code null} if none
     */
    @Nullable
    public String getDefaultHome(@NonNull int userId) {
        return getRoleHolder(RoleManager.ROLE_HOME,
                mUserManagerInternalSupplier.get().getProfileParentId(userId));
    }

    /**
     * Set the package name of the default home.
     *
     * @param packageName package name of the default home
     * @param userId the user ID
     * @param executor the {@link Executor} to execute callback on
     * @param callback the callback made after the default home as been updated
     * @return whether the default home was set
     */
    public boolean setDefaultHome(@NonNull String packageName, @UserIdInt int userId,
            @NonNull Executor executor, @NonNull Consumer<Boolean> callback) {
        final RoleManager roleManager = mRoleManagerSupplier.get();
        if (roleManager == null) {
            return false;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            roleManager.addRoleHolderAsUser(RoleManager.ROLE_HOME, packageName, 0,
                    UserHandle.of(userId), executor, callback);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return true;
    }

    @Nullable
    private String getRoleHolder(@NonNull String roleName, @NonNull int userId) {
        final RoleManager roleManager = mRoleManagerSupplier.get();
        if (roleManager == null) {
            return null;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            return CollectionUtils.firstOrNull(roleManager.getRoleHoldersAsUser(roleName,
                    UserHandle.of(userId)));
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
