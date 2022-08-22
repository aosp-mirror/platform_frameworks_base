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
import android.annotation.Nullable;
import android.app.AppOpsManager.Mode;
import android.util.ArraySet;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.io.PrintWriter;

/**
 * Interface for accessing and modifying modes for app-ops i.e. package and uid modes.
 * This interface also includes functions for added and removing op mode watchers.
 * In the future this interface will also include op restrictions.
 */
public interface AppOpsServiceInterface {
    /**
     * Returns a copy of non-default app-ops with op as keys and their modes as values for a uid.
     * Returns an empty SparseIntArray if nothing is set.
     * @param uid for which we need the app-ops and their modes.
     */
    SparseIntArray getNonDefaultUidModes(int uid);

    /**
     * Returns the app-op mode for a particular app-op of a uid.
     * Returns default op mode if the op mode for particular uid and op is not set.
     * @param uid user id for which we need the mode.
     * @param op app-op for which we need the mode.
     * @return mode of the app-op.
     */
    int getUidMode(int uid, int op);

    /**
     * Set the app-op mode for a particular uid and op.
     * The mode is not set if the mode is the same as the default mode for the op.
     * @param uid user id for which we want to set the mode.
     * @param op app-op for which we want to set the mode.
     * @param mode mode for the app-op.
     * @return true if op mode is changed.
     */
    boolean setUidMode(int uid, int op, @Mode int mode);

    /**
     * Gets the app-op mode for a particular package.
     * Returns default op mode if the op mode for the particular package is not set.
     * @param packageName package name for which we need the op mode.
     * @param op app-op for which we need the mode.
     * @return the mode of the app-op.
     */
    int getPackageMode(@NonNull String packageName, int op);

    /**
     * Sets the app-op mode for a particular package.
     * @param packageName package name for which we need to set the op mode.
     * @param op app-op for which we need to set the mode.
     * @param mode the mode of the app-op.
     */
    void setPackageMode(@NonNull String packageName, int op, @Mode int mode);

    /**
     * Stop tracking any app-op modes for a package.
     * @param packageName Name of the package for which we want to remove all mode tracking.
     */
    boolean removePackage(@NonNull String packageName);

    /**
     * Stop tracking any app-op modes for this uid.
     * @param uid user id for which we want to remove all tracking.
     */
    void removeUid(int uid);

    /**
     * Returns true if all uid modes for this uid are
     * in default state.
     * @param uid user id
     */
    boolean areUidModesDefault(int uid);

    /**
     * Returns true if all package modes for this package name are
     * in default state.
     * @param packageName package name.
     */
    boolean arePackageModesDefault(String packageName);

    /**
     * Stop tracking app-op modes for all uid and packages.
     */
    void clearAllModes();

    /**
     * Registers changedListener to listen to op's mode change.
     * @param changedListener the listener that must be trigger on the op's mode change.
     * @param op op representing the app-op whose mode change needs to be listened to.
     */
    void startWatchingOpModeChanged(@NonNull OnOpModeChangedListener changedListener, int op);

    /**
     * Registers changedListener to listen to package's app-op's mode change.
     * @param changedListener the listener that must be trigger on the mode change.
     * @param packageName of the package whose app-op's mode change needs to be listened to.
     */
    void startWatchingPackageModeChanged(@NonNull OnOpModeChangedListener changedListener,
            @NonNull String packageName);

    /**
     * Stop the changedListener from triggering on any mode change.
     * @param changedListener the listener that needs to be removed.
     */
    void removeListener(@NonNull OnOpModeChangedListener changedListener);

    /**
     * Temporary API which will be removed once we can safely untangle the methods that use this.
     * Returns a set of OnOpModeChangedListener that are listening for op's mode changes.
     * @param op app-op whose mode change is being listened to.
     */
    ArraySet<OnOpModeChangedListener> getOpModeChangedListeners(int op);

    /**
     * Temporary API which will be removed once we can safely untangle the methods that use this.
     * Returns a set of OnOpModeChangedListener that are listening for package's op's mode changes.
     * @param packageName of package whose app-op's mode change is being listened to.
     */
    ArraySet<OnOpModeChangedListener> getPackageModeChangedListeners(@NonNull String packageName);

    /**
     * Temporary API which will be removed once we can safely untangle the methods that use this.
     * Notify that the app-op's mode is changed by triggering the change listener.
     * @param changedListener the change listener.
     * @param op App-op whose mode has changed
     * @param uid user id associated with the app-op
     * @param packageName package name that is associated with the app-op
     */
    void notifyOpChanged(@NonNull OnOpModeChangedListener changedListener, int op, int uid,
            @Nullable String packageName);

    /**
     * Temporary API which will be removed once we can safely untangle the methods that use this.
     * Notify that the app-op's mode is changed to all packages associated with the uid by
     * triggering the appropriate change listener.
     * @param op App-op whose mode has changed
     * @param uid user id associated with the app-op
     * @param onlyForeground true if only watchers that
     * @param callbackToIgnore callback that should be ignored.
     */
    void notifyOpChangedForAllPkgsInUid(int op, int uid, boolean onlyForeground,
            @Nullable OnOpModeChangedListener callbackToIgnore);

    /**
     * TODO: Move hasForegroundWatchers and foregroundOps into this.
     * Go over the list of app-ops for the uid and mark app-ops with MODE_FOREGROUND in
     * foregroundOps.
     * @param uid for which the app-op's mode needs to be marked.
     * @param foregroundOps boolean array where app-ops that have MODE_FOREGROUND are marked true.
     * @return  foregroundOps.
     */
    SparseBooleanArray evalForegroundUidOps(int uid, SparseBooleanArray foregroundOps);

    /**
     * Go over the list of app-ops for the package name and mark app-ops with MODE_FOREGROUND in
     * foregroundOps.
     * @param packageName for which the app-op's mode needs to be marked.
     * @param foregroundOps boolean array where app-ops that have MODE_FOREGROUND are marked true.
     * @return foregroundOps.
     */
    SparseBooleanArray evalForegroundPackageOps(String packageName,
            SparseBooleanArray foregroundOps);

    /**
     * Dump op mode and package mode listeners and their details.
     * @param dumpOp if -1 then op mode listeners for all app-ops are dumped. If it's set to an
     *               app-op, only the watchers for that app-op are dumped.
     * @param dumpUid uid for which we want to dump op mode watchers.
     * @param dumpPackage if not null and if dumpOp is -1, dumps watchers for the package name.
     * @param printWriter writer to dump to.
     */
    boolean dumpListeners(int dumpOp, int dumpUid, String dumpPackage, PrintWriter printWriter);

}
