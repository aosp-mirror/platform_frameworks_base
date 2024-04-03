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

package com.android.server.am;

import android.annotation.Nullable;
import android.os.Process;
import android.os.UserHandle;

import com.android.internal.annotations.GuardedBy;
import com.android.server.appop.AppOpsService;
import com.android.server.pm.permission.AccessCheckDelegate;
import com.android.server.pm.permission.PermissionManagerServiceInternal;

import java.util.Collections;
import java.util.List;

class AccessCheckDelegateHelper {
    private final ActivityManagerGlobalLock mProcLock;

    @GuardedBy("mProcLock")
    private final List<ActiveInstrumentation> mActiveInstrumentation;

    private final AppOpsService mAppOpsService;

    private final PermissionManagerServiceInternal mPermissionManagerInternal;

    @GuardedBy("mProcLock")
    private AccessCheckDelegate mAccessCheckDelegate;

    AccessCheckDelegateHelper(ActivityManagerGlobalLock procLock,
            List<ActiveInstrumentation> activeInstrumentation, AppOpsService appOpsService,
            PermissionManagerServiceInternal permissionManagerInternal) {
        mProcLock = procLock;
        mActiveInstrumentation = activeInstrumentation;
        mAppOpsService = appOpsService;
        mPermissionManagerInternal = permissionManagerInternal;
    }

    @GuardedBy("mProcLock")
    private AccessCheckDelegate getAccessCheckDelegateLPr(boolean create) {
        if (create && mAccessCheckDelegate == null) {
            mAccessCheckDelegate = new AccessCheckDelegate.AccessCheckDelegateImpl();
            mAppOpsService.setCheckOpsDelegate(mAccessCheckDelegate);
            mPermissionManagerInternal.setCheckPermissionDelegate(mAccessCheckDelegate);
        }

        return mAccessCheckDelegate;
    }

    @GuardedBy("mProcLock")
    private void removeAccessCheckDelegateLPr() {
        mAccessCheckDelegate = null;
        mAppOpsService.setCheckOpsDelegate(null);
        mPermissionManagerInternal.setCheckPermissionDelegate(null);
    }

    void startDelegateShellPermissionIdentity(int delegateUid,
            @Nullable String[] permissions) {
        if (UserHandle.getCallingAppId() != Process.SHELL_UID
                && UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }

        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate != null && !delegate.isDelegateAndOwnerUid(delegateUid)) {
                throw new SecurityException("Shell can delegate permissions only "
                        + "to one instrumentation at a time");
            }
            final int instrCount = mActiveInstrumentation.size();
            for (int i = 0; i < instrCount; i++) {
                final ActiveInstrumentation instr =
                        mActiveInstrumentation.get(i);
                if (instr.mTargetInfo.uid != delegateUid) {
                    continue;
                }

                // If instrumentation started from the shell the connection is not null
                if (instr.mUiAutomationConnection == null) {
                    throw new SecurityException("Shell can delegate its permissions"
                            + " only to an instrumentation started from the shell");
                }

                final String packageName = instr.mTargetInfo.packageName;
                delegate = getAccessCheckDelegateLPr(true);
                delegate.setShellPermissionDelegate(delegateUid, packageName, permissions);
                return;
            }
        }
    }

    void stopDelegateShellPermissionIdentity() {
        if (UserHandle.getCallingAppId() != Process.SHELL_UID
                && UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only the shell can delegate its permissions");
        }
        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate == null) {
                return;
            }

            if (!delegate.hasShellPermissionDelegate()) {
                return;
            }

            delegate.removeShellPermissionDelegate();

            if (!delegate.hasDelegateOrOverrides()) {
                removeAccessCheckDelegateLPr();
            }
        }
    }

    List<String> getDelegatedShellPermissions() {
        if (UserHandle.getCallingAppId() != Process.SHELL_UID
                && UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only the shell can get delegated permissions");
        }
        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate == null) {
                return Collections.EMPTY_LIST;
            }

            return delegate.getDelegatedPermissionNames();
        }
    }

    void addOverridePermissionState(int originatingUid, int uid, String permission, int result) {
        if (UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only root can override permissions");
        }

        synchronized (mProcLock) {
            final int instrCount = mActiveInstrumentation.size();
            for (int i = 0; i < instrCount; i++) {
                final ActiveInstrumentation instr =
                        mActiveInstrumentation.get(i);
                if (instr.mTargetInfo.uid != originatingUid) {
                    continue;
                }
                // If instrumentation started from the shell the connection is not null
                if (instr.mSourceUid != Process.ROOT_UID || instr.mUiAutomationConnection == null) {
                    throw new SecurityException("Root can only override permissions only if the "
                            + "owning app was instrumented from root.");
                }

                AccessCheckDelegate delegate =
                        getAccessCheckDelegateLPr(true);
                if (delegate.hasOverriddenPermissions()
                        && !delegate.isDelegateAndOwnerUid(originatingUid)) {
                    throw new SecurityException("Only one instrumentation to grant"
                            + " overrides is allowed at a time.");
                }

                delegate.addOverridePermissionState(originatingUid, uid, permission, result);
                return;
            }
        }
    }

    void removeOverridePermissionState(int originatingUid, int uid, String permission) {
        if (UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only root can override permissions.");
        }

        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate == null) {
                return;
            }

            if (!delegate.isDelegateAndOwnerUid(originatingUid)) {
                if (delegate.hasOverriddenPermissions()) {
                    throw new SecurityException("Only the granter of current overrides can remove "
                            + "them.");
                }
                return;
            }

            delegate.removeOverridePermissionState(uid, permission);

            if (!delegate.hasDelegateOrOverrides()) {
                removeAccessCheckDelegateLPr();
            }
        }
    }

    void clearOverridePermissionStates(int originatingUid, int uid) {
        if (UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only root can override permissions.");
        }
        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate == null) {
                return;
            }

            if (!delegate.isDelegateAndOwnerUid(originatingUid)) {
                if (delegate.hasOverriddenPermissions()) {
                    throw new SecurityException(
                            "Only the granter of current overrides can remove them.");
                }
                return;
            }

            delegate.clearOverridePermissionStates(uid);

            if (!delegate.hasDelegateOrOverrides()) {
                removeAccessCheckDelegateLPr();
            }
        }
    }

    void clearAllOverridePermissionStates(int originatingUid) {
        if (UserHandle.getCallingAppId() != Process.ROOT_UID) {
            throw new SecurityException("Only root can override permissions.");
        }
        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate == null) {
                return;
            }

            if (!delegate.isDelegateAndOwnerUid(originatingUid)) {
                if (delegate.hasOverriddenPermissions()) {
                    throw new SecurityException(
                            "Only the granter of current overrides can remove them.");
                }
                return;
            }

            delegate.clearAllOverridePermissionStates();

            if (!delegate.hasDelegateOrOverrides()) {
                removeAccessCheckDelegateLPr();
            }
        }
    }

    void onInstrumentationFinished(int uid, String packageName) {
        synchronized (mProcLock) {
            AccessCheckDelegate delegate = getAccessCheckDelegateLPr(false);
            if (delegate != null) {
                if (delegate.isDelegatePackage(uid, packageName)) {
                    delegate.removeShellPermissionDelegate();
                }
                if (delegate.isDelegateAndOwnerUid(uid)) {
                    delegate.clearAllOverridePermissionStates();
                }
                if (!delegate.hasDelegateOrOverrides()) {
                    removeAccessCheckDelegateLPr();
                }
            }
        }
    }
}
