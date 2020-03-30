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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.parsing.component.ParsedMainComponent;
import android.os.BaseBundle;
import android.os.Debug;
import android.os.PersistableBundle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
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
    public ArrayMap<String, SuspendParams> suspendParams; // Suspending package to suspend params
    public boolean instantApp;
    public boolean virtualPreload;
    public int enabled;
    public String lastDisableAppCaller;
    public int domainVerificationStatus;
    public int appLinkGeneration;
    public int categoryHint = ApplicationInfo.CATEGORY_UNDEFINED;
    public int installReason;
    public @PackageManager.UninstallReason int uninstallReason;
    public String harmfulAppWarning;

    public ArraySet<String> disabledComponents;
    public ArraySet<String> enabledComponents;

    private String[] overlayPaths;
    private ArrayMap<String, String[]> sharedLibraryOverlayPaths; // Lib name to overlay paths
    private String[] cachedOverlayPaths;

    @UnsupportedAppUsage
    public PackageUserState() {
        installed = true;
        hidden = false;
        suspended = false;
        enabled = COMPONENT_ENABLED_STATE_DEFAULT;
        domainVerificationStatus =
                PackageManager.INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED;
        installReason = PackageManager.INSTALL_REASON_UNKNOWN;
        uninstallReason = PackageManager.UNINSTALL_REASON_UNKNOWN;
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
        suspendParams = new ArrayMap<>(o.suspendParams);
        instantApp = o.instantApp;
        virtualPreload = o.virtualPreload;
        enabled = o.enabled;
        lastDisableAppCaller = o.lastDisableAppCaller;
        domainVerificationStatus = o.domainVerificationStatus;
        appLinkGeneration = o.appLinkGeneration;
        categoryHint = o.categoryHint;
        installReason = o.installReason;
        uninstallReason = o.uninstallReason;
        disabledComponents = ArrayUtils.cloneOrNull(o.disabledComponents);
        enabledComponents = ArrayUtils.cloneOrNull(o.enabledComponents);
        overlayPaths =
            o.overlayPaths == null ? null : Arrays.copyOf(o.overlayPaths, o.overlayPaths.length);
        if (o.sharedLibraryOverlayPaths != null) {
            sharedLibraryOverlayPaths = new ArrayMap<>(o.sharedLibraryOverlayPaths);
        }
        harmfulAppWarning = o.harmfulAppWarning;
    }

    public String[] getOverlayPaths() {
        return overlayPaths;
    }

    public void setOverlayPaths(String[] paths) {
        overlayPaths = paths;
        cachedOverlayPaths = null;
    }

    public Map<String, String[]> getSharedLibraryOverlayPaths() {
        return sharedLibraryOverlayPaths;
    }

    public void setSharedLibraryOverlayPaths(String library, String[] paths) {
        if (sharedLibraryOverlayPaths == null) {
            sharedLibraryOverlayPaths = new ArrayMap<>();
        }
        sharedLibraryOverlayPaths.put(library, paths);
        cachedOverlayPaths = null;
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

    public boolean isMatch(ComponentInfo componentInfo, int flags) {
        return isMatch(componentInfo.applicationInfo.isSystemApp(),
                componentInfo.applicationInfo.enabled, componentInfo.enabled,
                componentInfo.directBootAware, componentInfo.name, flags);
    }

    public boolean isMatch(boolean isSystem, boolean isPackageEnabled,
            ParsedMainComponent component, int flags) {
        return isMatch(isSystem, isPackageEnabled, component.isEnabled(),
                component.isDirectBootAware(), component.getName(), flags);
    }

    /**
     * Test if the given component is considered installed, enabled and a match
     * for the given flags.
     *
     * <p>
     * Expects at least one of {@link PackageManager#MATCH_DIRECT_BOOT_AWARE} and
     * {@link PackageManager#MATCH_DIRECT_BOOT_UNAWARE} are specified in {@code flags}.
     * </p>
     *
     */
    public boolean isMatch(boolean isSystem, boolean isPackageEnabled, boolean isComponentEnabled,
               boolean isComponentDirectBootAware, String componentName, int flags) {
        final boolean matchUninstalled = (flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0;
        if (!isAvailable(flags) && !(isSystem && matchUninstalled)) {
            return reportIfDebug(false, flags);
        }

        if (!isEnabled(isPackageEnabled, isComponentEnabled, componentName, flags)) {
            return reportIfDebug(false, flags);
        }

        if ((flags & MATCH_SYSTEM_ONLY) != 0) {
            if (!isSystem) {
                return reportIfDebug(false, flags);
            }
        }

        final boolean matchesUnaware = ((flags & MATCH_DIRECT_BOOT_UNAWARE) != 0)
                && !isComponentDirectBootAware;
        final boolean matchesAware = ((flags & MATCH_DIRECT_BOOT_AWARE) != 0)
                && isComponentDirectBootAware;
        return reportIfDebug(matchesUnaware || matchesAware, flags);
    }

    public boolean reportIfDebug(boolean result, int flags) {
        if (DEBUG && !result) {
            Slog.i(LOG_TAG, "No match!; flags: "
                    + DebugUtils.flagsToString(PackageManager.class, "MATCH_", flags) + " "
                    + Debug.getCaller());
        }
        return result;
    }

    public boolean isEnabled(ComponentInfo componentInfo, int flags) {
        return isEnabled(componentInfo.applicationInfo.enabled, componentInfo.enabled,
                componentInfo.name, flags);
    }

    public boolean isEnabled(boolean isPackageEnabled,
            ParsedMainComponent parsedComponent, int flags) {
        return isEnabled(isPackageEnabled, parsedComponent.isEnabled(), parsedComponent.getName(),
                flags);
    }

    /**
     * Test if the given component is considered enabled.
     */
    public boolean isEnabled(boolean isPackageEnabled, boolean isComponentEnabled,
            String componentName, int flags) {
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
                // fallthrough
            case COMPONENT_ENABLED_STATE_DEFAULT:
                if (!isPackageEnabled) {
                    return false;
                }
                // fallthrough
            case COMPONENT_ENABLED_STATE_ENABLED:
                break;
        }

        // Check if component has explicit state before falling through to
        // the manifest default
        if (ArrayUtils.contains(this.enabledComponents, componentName)) {
            return true;
        }
        if (ArrayUtils.contains(this.disabledComponents, componentName)) {
            return false;
        }

        return isComponentEnabled;
    }

    public String[] getAllOverlayPaths() {
        if (overlayPaths == null && sharedLibraryOverlayPaths == null) {
            return null;
        }

        if (cachedOverlayPaths != null) {
            return cachedOverlayPaths;
        }

        final LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (overlayPaths != null) {
            final int N = overlayPaths.length;
            for (int i = 0; i < N; i++) {
                paths.add(overlayPaths[i]);
            }
        }

        if (sharedLibraryOverlayPaths != null) {
            for (String[] libOverlayPaths : sharedLibraryOverlayPaths.values()) {
                if (libOverlayPaths != null) {
                    final int N = libOverlayPaths.length;
                    for (int i = 0; i < N; i++) {
                        paths.add(libOverlayPaths[i]);
                    }
                }
            }
        }

        cachedOverlayPaths = paths.toArray(new String[0]);
        return cachedOverlayPaths;
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
            if (!Objects.equals(suspendParams, oldState.suspendParams)) {
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
        if (uninstallReason != oldState.uninstallReason) {
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

    @Override
    public int hashCode() {
        int hashCode = Long.hashCode(ceDataInode);
        hashCode = 31 * hashCode + Boolean.hashCode(installed);
        hashCode = 31 * hashCode + Boolean.hashCode(stopped);
        hashCode = 31 * hashCode + Boolean.hashCode(notLaunched);
        hashCode = 31 * hashCode + Boolean.hashCode(hidden);
        hashCode = 31 * hashCode + distractionFlags;
        hashCode = 31 * hashCode + Boolean.hashCode(suspended);
        hashCode = 31 * hashCode + Objects.hashCode(suspendParams);
        hashCode = 31 * hashCode + Boolean.hashCode(instantApp);
        hashCode = 31 * hashCode + Boolean.hashCode(virtualPreload);
        hashCode = 31 * hashCode + enabled;
        hashCode = 31 * hashCode + Objects.hashCode(lastDisableAppCaller);
        hashCode = 31 * hashCode + domainVerificationStatus;
        hashCode = 31 * hashCode + appLinkGeneration;
        hashCode = 31 * hashCode + categoryHint;
        hashCode = 31 * hashCode + installReason;
        hashCode = 31 * hashCode + uninstallReason;
        hashCode = 31 * hashCode + Objects.hashCode(disabledComponents);
        hashCode = 31 * hashCode + Objects.hashCode(enabledComponents);
        hashCode = 31 * hashCode + Objects.hashCode(harmfulAppWarning);
        return hashCode;
    }

    /**
     * Container to describe suspension parameters.
     */
    public static final class SuspendParams {
        private static final String TAG_DIALOG_INFO = "dialog-info";
        private static final String TAG_APP_EXTRAS = "app-extras";
        private static final String TAG_LAUNCHER_EXTRAS = "launcher-extras";

        public SuspendDialogInfo dialogInfo;
        public PersistableBundle appExtras;
        public PersistableBundle launcherExtras;

        private SuspendParams() {
        }

        /**
         * Returns a {@link SuspendParams} object with the given fields. Returns {@code null} if all
         * the fields are {@code null}.
         *
         * @param dialogInfo
         * @param appExtras
         * @param launcherExtras
         * @return A {@link SuspendParams} object or {@code null}.
         */
        public static SuspendParams getInstanceOrNull(SuspendDialogInfo dialogInfo,
                PersistableBundle appExtras, PersistableBundle launcherExtras) {
            if (dialogInfo == null && appExtras == null && launcherExtras == null) {
                return null;
            }
            final SuspendParams instance = new SuspendParams();
            instance.dialogInfo = dialogInfo;
            instance.appExtras = appExtras;
            instance.launcherExtras = launcherExtras;
            return instance;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof SuspendParams)) {
                return false;
            }
            final SuspendParams other = (SuspendParams) obj;
            if (!Objects.equals(dialogInfo, other.dialogInfo)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(appExtras, other.appExtras)) {
                return false;
            }
            if (!BaseBundle.kindofEquals(launcherExtras, other.launcherExtras)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int hashCode = Objects.hashCode(dialogInfo);
            hashCode = 31 * hashCode + ((appExtras != null) ? appExtras.size() : 0);
            hashCode = 31 * hashCode + ((launcherExtras != null) ? launcherExtras.size() : 0);
            return hashCode;
        }

        /**
         * Serializes this object into an xml format
         * @param out the {@link XmlSerializer} object
         * @throws IOException
         */
        public void saveToXml(XmlSerializer out) throws IOException {
            if (dialogInfo != null) {
                out.startTag(null, TAG_DIALOG_INFO);
                dialogInfo.saveToXml(out);
                out.endTag(null, TAG_DIALOG_INFO);
            }
            if (appExtras != null) {
                out.startTag(null, TAG_APP_EXTRAS);
                try {
                    appExtras.saveToXml(out);
                } catch (XmlPullParserException e) {
                    Slog.e(LOG_TAG, "Exception while trying to write appExtras."
                            + " Will be lost on reboot", e);
                }
                out.endTag(null, TAG_APP_EXTRAS);
            }
            if (launcherExtras != null) {
                out.startTag(null, TAG_LAUNCHER_EXTRAS);
                try {
                    launcherExtras.saveToXml(out);
                } catch (XmlPullParserException e) {
                    Slog.e(LOG_TAG, "Exception while trying to write launcherExtras."
                            + " Will be lost on reboot", e);
                }
                out.endTag(null, TAG_LAUNCHER_EXTRAS);
            }
        }

        /**
         * Parses this object from the xml format. Returns {@code null} if no object related
         * information could be read.
         * @param in the reader
         * @return
         */
        public static SuspendParams restoreFromXml(XmlPullParser in) throws IOException {
            SuspendDialogInfo readDialogInfo = null;
            PersistableBundle readAppExtras = null;
            PersistableBundle readLauncherExtras = null;

            final int currentDepth = in.getDepth();
            int type;
            try {
                while ((type = in.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG
                        || in.getDepth() > currentDepth)) {
                    if (type == XmlPullParser.END_TAG
                            || type == XmlPullParser.TEXT) {
                        continue;
                    }
                    switch (in.getName()) {
                        case TAG_DIALOG_INFO:
                            readDialogInfo = SuspendDialogInfo.restoreFromXml(in);
                            break;
                        case TAG_APP_EXTRAS:
                            readAppExtras = PersistableBundle.restoreFromXml(in);
                            break;
                        case TAG_LAUNCHER_EXTRAS:
                            readLauncherExtras = PersistableBundle.restoreFromXml(in);
                            break;
                        default:
                            Slog.w(LOG_TAG, "Unknown tag " + in.getName()
                                    + " in SuspendParams. Ignoring");
                            break;
                    }
                }
            } catch (XmlPullParserException e) {
                Slog.e(LOG_TAG, "Exception while trying to parse SuspendParams,"
                        + " some fields may default", e);
            }
            return getInstanceOrNull(readDialogInfo, readAppExtras, readLauncherExtras);
        }
    }
}
