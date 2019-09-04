/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_AWARE;
import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import android.annotation.UnsupportedAppUsage;
import android.os.BaseBundle;
import android.os.Debug;
import android.os.PersistableBundle;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.util.Arrays;
import java.util.Objects;

/**
 * Per-user state information about a package.
 * @hide
 */
public class PackageUserState {
    private static final boolean DEBUG = false;
    private static final String LOG_TAG = "PackageUserState";

    public long ceDataInode;
    public boolean installed;
    public boolean stopped;
    public boolean notLaunched;
    public boolean hidden; // Is the app restricted by owner / admin
    public int distractionFlags;
    public boolean suspended;
    public String suspendingPackage;
    public SuspendDialogInfo dialogInfo;
    public PersistableBundle suspendedAppExtras;
    public PersistableBundle suspendedLauncherExtras;
    public boolean instantApp;
    public boolean virtualPreload;
    public int enabled;
    public String lastDisableAppCaller;
    public int domainVerificationStatus;
    public int appLinkGeneration;
    public int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
    public int installReason;
    public String harmfulAppWarning;

    public ArraySet<String> disabledComponents;
    public ArraySet<String> enabledComponents;

    public String[] overlayPaths;

    @UnsupportedAppUsage
    public PackageUserState() {
        installed = true;
        hidden = false;
        suspended = false;
        enabled = COMPONENT_ENABLED_STATE_DEFAULT;
        domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        installReason = PackageManager.INSTALL_REASON_UNKNOWN;
    }

    @VisibleForTesting
    public PackageUserState(PackageUserState o) {
        ceDataInode = o.ceDataInode;
        installed = o.installed;
        stopped = o.stopped;
        notLaunched = o.notLaunched;
        hidden = o.hidden;
        distractionFlags = o.distractionFlags;
        suspended = o.suspended;
        suspendingPackage = o.suspendingPackage;
        dialogInfo = o.dialogInfo;
        suspendedAppExtras = o.suspendedAppExtras;
        suspendedLauncherExtras = o.suspendedLauncherExtras;
        instantApp = o.instantApp;
        virtualPreload = o.virtualPreload;
        enabled = o.enabled;
        lastDisableAppCaller = o.lastDisableAppCaller;
        domainVerificationStatus = o.domainVerificationStatus;
        appLinkGeneration = o.appLinkGeneration;
        categoryHint = o.categoryHint;
        installReason = o.installReason;
        disabledComponents = ArrayUtils.cloneOrNull(o.disabledComponents);
        enabledComponents = ArrayUtils.cloneOrNull(o.enabledComponents);
        overlayPaths =
            o.overlayPaths == null ? null : Arrays.copyOf(o.overlayPaths, o.overlayPaths.length);
        harmfulAppWarning = o.harmfulAppWarning;
    }

    /**
     * Test if this package is installed.
     */
    public boolean isAvailable(int flags) {
        // True if it is installed for this user and it is not hidden. If it is hidden,
        // still return true if the caller requested MATCH_UNINSTALLED_PACKAGES
        final boolean matchAnyUser = (flags & PackageManager.MATCH_ANY_USER) != 0;
        final boolean matchUninstalled = (flags & PackageManager.MATCH_UNINSTALLED_PACKAGES) != 0;
        return matchAnyUser
                || (this.installed
                        && (!this.hidden || matchUninstalled));
    }

    /**
     * Test if the given component is considered installed, enabled and a match
     * for the given flags.
     *
     * <p>
     * Expects at least one of {@link PackageManager#MATCH_DIRECT_BOOT_AWARE} and
     * {@link PackageManager#MATCH_DIRECT_BOOT_UNAWARE} are specified in {@code flags}.
     * </p>
     */
    public boolean isMatch(ComponentInfo componentInfo, int flags) {
        final boolean isSystemApp = componentInfo.applicationInfo.isSystemApp();
        final boolean matchUninstalled = (flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0;
        if (!isAvailable(flags)
                && !(isSystemApp && matchUninstalled)) return reportIfDebug(false, flags);
        if (!isEnabled(componentInfo, flags)) return reportIfDebug(false, flags);

        if ((flags & MATCH_SYSTEM_ONLY) != 0) {
            if (!isSystemApp) {
                return reportIfDebug(false, flags);
            }
        }

        final boolean matchesUnaware = ((flags & MATCH_DIRECT_BOOT_UNAWARE) != 0)
                && !componentInfo.directBootAware;
        final boolean matchesAware = ((flags & MATCH_DIRECT_BOOT_AWARE) != 0)
                && componentInfo.directBootAware;
        return reportIfDebug(matchesUnaware || matchesAware, flags);
    }

    private boolean reportIfDebug(boolean result, int flags) {
        if (DEBUG && !result) {
            Slog.i(LOG_TAG, "No match!; flags: "
                    + DebugUtils.flagsToString(PackageManager.class, "MATCH_", flags) + " "
                    + Debug.getCaller());
        }
        return result;
    }

    /**
     * Test if the given component is considered enabled.
     */
    public boolean isEnabled(ComponentInfo componentInfo, int flags) {
        if ((flags & MATCH_DISABLED_COMPONENTS) != 0) {
            return true;
        }

        // First check if the overall package is disabled; if the package is
        // enabled then fall through to check specific component
        switch (this.enabled) {
            case COMPONENT_ENABLED_STATE_DISABLED:
            case COMPONENT_ENABLED_STATE_DISABLED_USER:
                return false;
            case COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED:
                if ((flags & MATCH_DISABLED_UNTIL_USED_COMPONENTS) == 0) {
                    return false;
                }
            case COMPONENT_ENABLED_STATE_DEFAULT:
                if (!componentInfo.applicationInfo.enabled) {
                    return false;
                }
            case COMPONENT_ENABLED_STATE_ENABLED:
                break;
        }

        // Check if component has explicit state before falling through to
        // the manifest default
        if (ArrayUtils.contains(this.enabledComponents, componentInfo.name)) {
            return true;
        }
        if (ArrayUtils.contains(this.disabledComponents, componentInfo.name)) {
            return false;
        }

        return componentInfo.enabled;
    }

    @Override
    final public boolean equals(Object obj) {
        if (!(obj instanceof PackageUserState)) {
            return false;
        }
        final PackageUserState oldState = (PackageUserState) obj;
        if (ceDataInode != oldState.ceDataInode) {
            return false;
        }
        if (installed != oldState.installed) {
            return false;
        }
        if (stopped != oldState.stopped) {
            return false;
        }
        if (notLaunched != oldState.notLaunched) {
            return false;
        }
        if (hidden != oldState.hidden) {
            return false;
        }
        if (distractionFlags != oldState.distractionFlags) {
            return false;
        }
        if (suspended != oldState.suspended) {
            return false;
        }
        if (suspended) {
            if (suspendingPackage == null
                    || !suspendingPackage.equals(oldState.suspendingPackage)) {
                return false;
            }
            if (!Objects.equals(dialogInfo, oldState.dialogInfo)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(suspendedAppExtras,
                    oldState.suspendedAppExtras)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(suspendedLauncherExtras,
                    oldState.suspendedLauncherExtras)) {
                return false;
            }
        }
        if (instantApp != oldState.instantApp) {
            return false;
        }
        if (virtualPreload != oldState.virtualPreload) {
            return false;
        }
        if (enabled != oldState.enabled) {
            return false;
        }
        if ((lastDisableAppCaller == null && oldState.lastDisableAppCaller != null)
                || (lastDisableAppCaller != null
                        && !lastDisableAppCaller.equals(oldState.lastDisableAppCaller))) {
            return false;
        }
        if (domainVerificationStatus != oldState.domainVerificationStatus) {
            return false;
        }
        if (appLinkGeneration != oldState.appLinkGeneration) {
            return false;
        }
        if (categoryHint != oldState.categoryHint) {
            return false;
        }
        if (installReason != oldState.installReason) {
            return false;
        }
        if ((disabledComponents == null && oldState.disabledComponents != null)
                || (disabledComponents != null && oldState.disabledComponents == null)) {
            return false;
        }
        if (disabledComponents != null) {
            if (disabledComponents.size() != oldState.disabledComponents.size()) {
                return false;
            }
            for (int i = disabledComponents.size() - 1; i >=0; --i) {
                if (!oldState.disabledComponents.contains(disabledComponents.valueAt(i))) {
                    return false;
                }
            }
        }
        if ((enabledComponents == null && oldState.enabledComponents != null)
                || (enabledComponents != null && oldState.enabledComponents == null)) {
            return false;
        }
        if (enabledComponents != null) {
            if (enabledComponents.size() != oldState.enabledComponents.size()) {
                return false;
            }
            for (int i = enabledComponents.size() - 1; i >=0; --i) {
                if (!oldState.enabledComponents.contains(enabledComponents.valueAt(i))) {
                    return false;
                }
            }
        }
        if (harmfulAppWarning == null && oldState.harmfulAppWarning != null
                || (harmfulAppWarning != null
                        && !harmfulAppWarning.equals(oldState.harmfulAppWarning))) {
            return false;
        }
        return true;
    }
}
