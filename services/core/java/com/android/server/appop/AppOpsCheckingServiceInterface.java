/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.appop;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.app.AppOpsManager.Mode;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Interface for accessing and modifying modes for app-ops i.e. package and uid modes.
 * This interface also includes functions for added and removing op mode watchers.
 * In the future this interface will also include op restrictions.
 */
public interface AppOpsCheckingServiceInterface {

    /**
     * Tells the checking service to write its state to persistence (unconditionally).
     * This is only made visible for testing.
     */
    @VisibleForTesting
    void writeState();

    /**
     * Tells the checking service to read its state from persistence. This is generally called
     * shortly after instantiation. If extra system services need to be guaranteed to be published
     * that work should be done in {@link #systemReady()}
     */
    void readState();

    /**
     * Tells the checking service that a shutdown is occurring. This gives it a chance to write its
     * state to persistence (if there are any pending changes).
     */
    void shutdown();

    /**
     * Do additional initialization work that is dependent on external system services.
     */
    void systemReady();

    /**
     * Returns a copy of non-default app-ops with op as keys and their modes as values for a uid.
     * Returns an empty SparseIntArray if nothing is set.
     * @param uid for which we need the app-ops and their modes.
     * @param persistentDeviceId device for which we need the app-ops and their modes
     */
    SparseIntArray getNonDefaultUidModes(int uid, String persistentDeviceId);

    /**
     * Returns a copy of non-default app-ops with op as keys and their modes as values for a package
     * and user.
     * Returns an empty SparseIntArray if nothing is set.
     * @param packageName for which we need the app-ops and their modes.
     * @param userId for which the package is installed in.
     */
    SparseIntArray getNonDefaultPackageModes(String packageName, int userId);

    /**
     * Returns the app-op mode for a particular app-op of a uid.
     * Returns default op mode if the op mode for particular uid and op is not set.
     * @param uid user id for which we need the mode.
     * @param persistentDeviceId device for which we need the mode
     * @param op app-op for which we need the mode.
     * @return mode of the app-op.
     */
    int getUidMode(int uid, String persistentDeviceId, int op);

    /**
     * Set the app-op mode for a particular uid and op.
     * The mode is not set if the mode is the same as the default mode for the op.
     * @param uid user id for which we want to set the mode.
     * @param persistentDeviceId device for which we want to set the mode.
     * @param op app-op for which we want to set the mode.
     * @param mode mode for the app-op.
     * @return true if op mode is changed.
     */
    boolean setUidMode(int uid, String persistentDeviceId, int op, @Mode int mode);

    /**
     * Gets the app-op mode for a particular package.
     * Returns default op mode if the op mode for the particular package is not set.
     * @param packageName package name for which we need the op mode.
     * @param op app-op for which we need the mode.
     * @param userId user id associated with the package.
     * @return the mode of the app-op.
     */
    int getPackageMode(@NonNull String packageName, int op, @UserIdInt int userId);

    /**
     * Sets the app-op mode for a particular package.
     * @param packageName package name for which we need to set the op mode.
     * @param op app-op for which we need to set the mode.
     * @param mode the mode of the app-op.
     * @param userId user id associated with the package.
     *
     */
    void setPackageMode(@NonNull String packageName, int op, @Mode int mode, @UserIdInt int userId);

    /**
     * Stop tracking any app-op modes for a package.
     * @param packageName Name of the package for which we want to remove all mode tracking.
     * @param userId user id associated with the package.
     */
    boolean removePackage(@NonNull String packageName,  @UserIdInt int userId);

    /**
     * Stop tracking any app-op modes for this uid.
     * @param uid user id for which we want to remove all tracking.
     */
    void removeUid(int uid);

    /**
     * Stop tracking app-op modes for all uid and packages.
     */
    void clearAllModes();

    /**
     * @param uid UID to query foreground ops for.
     * @param persistentDeviceId device to query foreground ops for
     * @return SparseBooleanArray where the keys are the op codes for which their modes are
     * MODE_FOREGROUND for the passed UID.
     */
    SparseBooleanArray getForegroundOps(int uid, String persistentDeviceId);

    /**
     *
     * @param packageName Package name to check for.
     * @param userId User ID to check for.
     * @return SparseBooleanArray where the keys are the op codes for which their modes are
     * MODE_FOREGROUND for the passed package name and user ID.
     */
    SparseBooleanArray getForegroundOps(String packageName, int userId);

    /**
     * Adds a listener for changes in appop modes. These callbacks should be dispatched
     * synchronously.
     *
     * @param listener The listener to be added.
     * @return true if the listener was added.
     */
    boolean addAppOpsModeChangedListener(@NonNull AppOpsModeChangedListener listener);

    /**
     * Removes a listener for changes in appop modes.
     *
     * @param listener The listener to be removed.
     * @return true if the listener was removed.
     */
    boolean removeAppOpsModeChangedListener(@NonNull AppOpsModeChangedListener listener);

    /**
     * A listener for changes to the AppOps mode.
     */
    interface AppOpsModeChangedListener {

        /**
         * Invoked when a UID's appop mode is changed.
         *
         * @param uid The UID whose appop mode was changed.
         * @param code The op code that was changed.
         * @param mode The new mode.
         */
        void onUidModeChanged(int uid, int code, int mode);

        /**
         * Invoked when a package's appop mode is changed.
         *
         * @param packageName The package name whose appop mode was changed.
         * @param userId The user ID for the package.
         * @param code The op code that was changed.
         * @param mode The new mode.
         */
        void onPackageModeChanged(@NonNull String packageName, int userId, int code, int mode);
    }
}
