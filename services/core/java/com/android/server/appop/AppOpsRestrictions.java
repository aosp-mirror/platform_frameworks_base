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

import android.os.PackageTagsList;

import java.io.PrintWriter;

/**
 * Legacy implementation for AppOpsService's app-op restrictions (global and user)
 * storage and access.
 */
public interface AppOpsRestrictions {
    /**
     * Set or clear a global app-op restriction for the given {@code clientToken}.
     *
     * @param clientToken A token identifying the client this restriction applies to.
     * @param code        The app-op opCode to set (or clear) a restriction for.
     * @param restricted  {@code true} to restrict this app-op code, or {@code false} to clear an
     *                    existing restriction.
     * @return {@code true} if any restriction state was modified as a result of this operation
     */
    boolean setGlobalRestriction(Object clientToken, int code, boolean restricted);

    /**
     * Get the state of a global app-op restriction for the given {@code clientToken}.
     *
     * @param clientToken A token identifying the client to get the restriction state of.
     * @param code        The app-op code to get the restriction state of.
     * @return the restriction state
     */
    boolean getGlobalRestriction(Object clientToken, int code);

    /**
     * Returns {@code true} if *any* global app-op restrictions are currently set for the given
     * {@code clientToken}.
     *
     * @param clientToken A token identifying the client to check restrictions for.
     * @return {@code true} if any restrictions are set
     */
    boolean hasGlobalRestrictions(Object clientToken);

    /**
     * Clear *all* global app-op restrictions for the given {@code clientToken}.
     *
     * @param clientToken A token identifying the client to clear restrictions from.
     * @return {@code true} if any restriction state was modified as a result of this operation
     */
    boolean clearGlobalRestrictions(Object clientToken);

    /**
     * Set or clear a user app-op restriction for the given {@code clientToken} and {@code userId}.
     *
     * @param clientToken         A token identifying the client this restriction applies to.
     * @param code                The app-op code to set (or clear) a restriction for.
     * @param restricted          {@code true} to restrict this app-op code, or {@code false} to
     *                            remove any existing restriction.
     * @param excludedPackageTags A list of packages and associated attribution tags to exclude
     *                            from this restriction. Or, if {@code null}, removes any
     *                            exclusions from this restriction.
     * @return {@code true} if any restriction state was modified as a result of this operation
     */
    boolean setUserRestriction(Object clientToken, int userId, int code, boolean restricted,
            PackageTagsList excludedPackageTags);

    /**
     * Get the state of a user app-op restriction for the given {@code clientToken} and {@code
     * userId}. Or, if the combination of ({{@code clientToken}, {@code userId}, @code
     * packageName}, {@code attributionTag}) has been excluded via
     * {@link AppOpsRestrictions#setUserRestriction}, always returns {@code false}.
     *
     * @param clientToken    A token identifying the client this restriction applies to.
     * @param userId         Which userId this restriction applies to.
     * @param code           The app-op code to get the restriction state of.
     * @param packageName    A package name used to check for exclusions.
     * @param attributionTag An attribution tag used to check for exclusions.
     * @param isCheckOp      a flag that, when {@code true}, denotes that exclusions should be
     *                       checked by (packageName) rather than (packageName, attributionTag)
     * @return the restriction state
     */
    boolean getUserRestriction(Object clientToken, int userId, int code, String packageName,
            String attributionTag, boolean isCheckOp);

    /**
     * Returns {@code true} if *any* user app-op restrictions are currently set for the given
     * {@code clientToken}.
     *
     * @param clientToken A token identifying the client to check restrictions for.
     * @return {@code true} if any restrictions are set
     */
    boolean hasUserRestrictions(Object clientToken);

    /**
     * Clear *all* user app-op restrictions for the given {@code clientToken}.
     *
     * @param clientToken A token identifying the client to clear restrictions for.
     * @return {@code true} if any restriction state was modified as a result of this operation
     */
    boolean clearUserRestrictions(Object clientToken);

    /**
     * Clear *all* user app-op restrictions for the given {@code clientToken} and {@code userId}.
     *
     * @param clientToken A token identifying the client to clear restrictions for.
     * @param userId      Which userId to clear restrictions for.
     * @return {@code true} if any restriction state was modified as a result of this operation
     */
    boolean clearUserRestrictions(Object clientToken, Integer userId);

    /**
     * Returns the set of exclusions previously set by
     * {@link AppOpsRestrictions#setUserRestriction} for the given {@code clientToken}
     * and {@code userId}.
     *
     * @param clientToken A token identifying the client to get restriction exclusions for.
     * @param userId      Which userId to get restriction exclusions for
     * @return a set of user restriction exclusions
     */
    PackageTagsList getUserRestrictionExclusions(Object clientToken, int userId);

    /**
     * Dump the state of appop restrictions.
     *
     * @param printWriter          writer to dump to.
     * @param dumpOp               if -1 then op mode listeners for all app-ops are dumped. If it's
     *                             set to an app-op, only the watchers for that app-op are dumped.
     * @param dumpPackage          if not null and if dumpOp is -1, dumps watchers for the package
     *                             name.
     * @param showUserRestrictions include user restriction state in the output
     */
    void dumpRestrictions(PrintWriter printWriter, int dumpOp, String dumpPackage,
            boolean showUserRestrictions);

    /**
     * Listener for when an appop restriction is removed.
     */
    interface AppOpsRestrictionRemovedListener {
        void onAppOpsRestrictionRemoved(int code);
    }
}
