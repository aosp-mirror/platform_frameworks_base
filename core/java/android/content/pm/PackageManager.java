/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.Manifest;
import android.annotation.CheckResult;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.annotation.UserIdInt;
import android.annotation.XmlRes;
import android.app.ActivityManager;
import android.app.AppDetailsActivity;
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.app.admin.DevicePolicyManager;
import android.app.usage.StorageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.dex.ArtManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.util.AndroidException;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import dalvik.system.VMRuntime;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Locale;

/**
 * Class for retrieving various kinds of information related to the application
 * packages that are currently installed on the device.
 *
 * You can find this class through {@link Context#getPackageManager}.
 */
public abstract class PackageManager {
    private static final String TAG = "PackageManager";

    /** {@hide} */
    public static final boolean APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE = true;

    /**
     * This exception is thrown when a given package, application, or component
     * name cannot be found.
     */
    public static class NameNotFoundException extends AndroidException {
        public NameNotFoundException() {
        }

        public NameNotFoundException(String name) {
            super(name);
        }
    }

    /**
     * Listener for changes in permissions granted to a UID.
     *
     * @hide
     */
    @SystemApi
    public interface OnPermissionsChangedListener {

        /**
         * Called when the permissions for a UID change.
         * @param uid The UID with a change.
         */
        public void onPermissionsChanged(int uid);
    }

    /**
     * As a guiding principle:
     * <p>
     * {@code GET_} flags are used to request additional data that may have been
     * elided to save wire space.
     * <p>
     * {@code MATCH_} flags are used to include components or packages that
     * would have otherwise been omitted from a result set by current system
     * state.
     */

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_ACTIVITIES,
            GET_CONFIGURATIONS,
            GET_GIDS,
            GET_INSTRUMENTATION,
            GET_INTENT_FILTERS,
            GET_META_DATA,
            GET_PERMISSIONS,
            GET_PROVIDERS,
            GET_RECEIVERS,
            GET_SERVICES,
            GET_SHARED_LIBRARY_FILES,
            GET_SIGNATURES,
            GET_SIGNING_CERTIFICATES,
            GET_URI_PERMISSION_PATTERNS,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_SYSTEM_ONLY,
            MATCH_FACTORY_ONLY,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_INSTANT,
            MATCH_APEX,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
            MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PackageInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_SHARED_LIBRARY_FILES,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_SYSTEM_ONLY,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_INSTANT,
            MATCH_STATIC_SHARED_LIBRARIES,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
            MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_SHARED_LIBRARY_FILES,
            MATCH_ALL,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DEFAULT_ONLY,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_DIRECT_BOOT_AUTO,
            MATCH_DIRECT_BOOT_AWARE,
            MATCH_DIRECT_BOOT_UNAWARE,
            MATCH_SYSTEM_ONLY,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_INSTANT,
            MATCH_STATIC_SHARED_LIBRARIES,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ComponentInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
            GET_RESOLVED_FILTER,
            GET_SHARED_LIBRARY_FILES,
            MATCH_ALL,
            MATCH_DEBUG_TRIAGED_MISSING,
            MATCH_DISABLED_COMPONENTS,
            MATCH_DISABLED_UNTIL_USED_COMPONENTS,
            MATCH_DEFAULT_ONLY,
            MATCH_DIRECT_BOOT_AUTO,
            MATCH_DIRECT_BOOT_AWARE,
            MATCH_DIRECT_BOOT_UNAWARE,
            MATCH_SYSTEM_ONLY,
            MATCH_UNINSTALLED_PACKAGES,
            MATCH_INSTANT,
            GET_DISABLED_COMPONENTS,
            GET_DISABLED_UNTIL_USED_COMPONENTS,
            GET_UNINSTALLED_PACKAGES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResolveInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ModuleInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionGroupInfoFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "GET_", "MATCH_" }, value = {
            GET_META_DATA,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstrumentationInfoFlags {}

    /**
     * {@link PackageInfo} flag: return information about
     * activities in the package in {@link PackageInfo#activities}.
     */
    public static final int GET_ACTIVITIES              = 0x00000001;

    /**
     * {@link PackageInfo} flag: return information about
     * intent receivers in the package in
     * {@link PackageInfo#receivers}.
     */
    public static final int GET_RECEIVERS               = 0x00000002;

    /**
     * {@link PackageInfo} flag: return information about
     * services in the package in {@link PackageInfo#services}.
     */
    public static final int GET_SERVICES                = 0x00000004;

    /**
     * {@link PackageInfo} flag: return information about
     * content providers in the package in
     * {@link PackageInfo#providers}.
     */
    public static final int GET_PROVIDERS               = 0x00000008;

    /**
     * {@link PackageInfo} flag: return information about
     * instrumentation in the package in
     * {@link PackageInfo#instrumentation}.
     */
    public static final int GET_INSTRUMENTATION         = 0x00000010;

    /**
     * {@link PackageInfo} flag: return information about the
     * intent filters supported by the activity.
     */
    public static final int GET_INTENT_FILTERS          = 0x00000020;

    /**
     * {@link PackageInfo} flag: return information about the
     * signatures included in the package.
     *
     * @deprecated use {@code GET_SIGNING_CERTIFICATES} instead
     */
    @Deprecated
    public static final int GET_SIGNATURES          = 0x00000040;

    /**
     * {@link ResolveInfo} flag: return the IntentFilter that
     * was matched for a particular ResolveInfo in
     * {@link ResolveInfo#filter}.
     */
    public static final int GET_RESOLVED_FILTER         = 0x00000040;

    /**
     * {@link ComponentInfo} flag: return the {@link ComponentInfo#metaData}
     * data {@link android.os.Bundle}s that are associated with a component.
     * This applies for any API returning a ComponentInfo subclass.
     */
    public static final int GET_META_DATA               = 0x00000080;

    /**
     * {@link PackageInfo} flag: return the
     * {@link PackageInfo#gids group ids} that are associated with an
     * application.
     * This applies for any API returning a PackageInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_GIDS                    = 0x00000100;

    /**
     * @deprecated replaced with {@link #MATCH_DISABLED_COMPONENTS}
     */
    @Deprecated
    public static final int GET_DISABLED_COMPONENTS = 0x00000200;

    /**
     * {@link PackageInfo} flag: include disabled components in the returned info.
     */
    public static final int MATCH_DISABLED_COMPONENTS = 0x00000200;

    /**
     * {@link ApplicationInfo} flag: return the
     * {@link ApplicationInfo#sharedLibraryFiles paths to the shared libraries}
     * that are associated with an application.
     * This applies for any API returning an ApplicationInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_SHARED_LIBRARY_FILES    = 0x00000400;

    /**
     * {@link ProviderInfo} flag: return the
     * {@link ProviderInfo#uriPermissionPatterns URI permission patterns}
     * that are associated with a content provider.
     * This applies for any API returning a ProviderInfo class, either
     * directly or nested inside of another.
     */
    public static final int GET_URI_PERMISSION_PATTERNS  = 0x00000800;
    /**
     * {@link PackageInfo} flag: return information about
     * permissions in the package in
     * {@link PackageInfo#permissions}.
     */
    public static final int GET_PERMISSIONS               = 0x00001000;

    /**
     * @deprecated replaced with {@link #MATCH_UNINSTALLED_PACKAGES}
     */
    @Deprecated
    public static final int GET_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * Flag parameter to retrieve some information about all applications (even
     * uninstalled ones) which have data directories. This state could have
     * resulted if applications have been deleted with flag
     * {@code DONT_DELETE_DATA} with a possibility of being replaced or
     * reinstalled in future.
     * <p>
     * Note: this flag may cause less information about currently installed
     * applications to be returned.
     */
    public static final int MATCH_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * {@link PackageInfo} flag: return information about
     * hardware preferences in
     * {@link PackageInfo#configPreferences PackageInfo.configPreferences},
     * and requested features in {@link PackageInfo#reqFeatures} and
     * {@link PackageInfo#featureGroups}.
     */
    public static final int GET_CONFIGURATIONS = 0x00004000;

    /**
     * @deprecated replaced with {@link #MATCH_DISABLED_UNTIL_USED_COMPONENTS}.
     */
    @Deprecated
    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    /**
     * {@link PackageInfo} flag: include disabled components which are in
     * that state only because of {@link #COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED}
     * in the returned info.  Note that if you set this flag, applications
     * that are in this disabled state will be reported as enabled.
     */
    public static final int MATCH_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    /**
     * Resolution and querying flag: if set, only filters that support the
     * {@link android.content.Intent#CATEGORY_DEFAULT} will be considered for
     * matching.  This is a synonym for including the CATEGORY_DEFAULT in your
     * supplied Intent.
     */
    public static final int MATCH_DEFAULT_ONLY  = 0x00010000;

    /**
     * Querying flag: if set and if the platform is doing any filtering of the
     * results, then the filtering will not happen. This is a synonym for saying
     * that all results should be returned.
     * <p>
     * <em>This flag should be used with extreme care.</em>
     */
    public static final int MATCH_ALL = 0x00020000;

    /**
     * Querying flag: match components which are direct boot <em>unaware</em> in
     * the returned info, regardless of the current user state.
     * <p>
     * When neither {@link #MATCH_DIRECT_BOOT_AWARE} nor
     * {@link #MATCH_DIRECT_BOOT_UNAWARE} are specified, the default behavior is
     * to match only runnable components based on the user state. For example,
     * when a user is started but credentials have not been presented yet, the
     * user is running "locked" and only {@link #MATCH_DIRECT_BOOT_AWARE}
     * components are returned. Once the user credentials have been presented,
     * the user is running "unlocked" and both {@link #MATCH_DIRECT_BOOT_AWARE}
     * and {@link #MATCH_DIRECT_BOOT_UNAWARE} components are returned.
     *
     * @see UserManager#isUserUnlocked()
     */
    public static final int MATCH_DIRECT_BOOT_UNAWARE = 0x00040000;

    /**
     * Querying flag: match components which are direct boot <em>aware</em> in
     * the returned info, regardless of the current user state.
     * <p>
     * When neither {@link #MATCH_DIRECT_BOOT_AWARE} nor
     * {@link #MATCH_DIRECT_BOOT_UNAWARE} are specified, the default behavior is
     * to match only runnable components based on the user state. For example,
     * when a user is started but credentials have not been presented yet, the
     * user is running "locked" and only {@link #MATCH_DIRECT_BOOT_AWARE}
     * components are returned. Once the user credentials have been presented,
     * the user is running "unlocked" and both {@link #MATCH_DIRECT_BOOT_AWARE}
     * and {@link #MATCH_DIRECT_BOOT_UNAWARE} components are returned.
     *
     * @see UserManager#isUserUnlocked()
     */
    public static final int MATCH_DIRECT_BOOT_AWARE = 0x00080000;

    /**
     * Querying flag: include only components from applications that are marked
     * with {@link ApplicationInfo#FLAG_SYSTEM}.
     */
    public static final int MATCH_SYSTEM_ONLY = 0x00100000;

    /**
     * Internal {@link PackageInfo} flag: include only components on the system image.
     * This will not return information on any unbundled update to system components.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int MATCH_FACTORY_ONLY = 0x00200000;

    /**
     * Allows querying of packages installed for any user, not just the specific one. This flag
     * is only meant for use by apps that have INTERACT_ACROSS_USERS permission.
     * @hide
     */
    @SystemApi
    public static final int MATCH_ANY_USER = 0x00400000;

    /**
     * Combination of MATCH_ANY_USER and MATCH_UNINSTALLED_PACKAGES to mean any known
     * package.
     * @hide
     */
    @TestApi
    public static final int MATCH_KNOWN_PACKAGES = MATCH_UNINSTALLED_PACKAGES | MATCH_ANY_USER;

    /**
     * Internal {@link PackageInfo} flag: include components that are part of an
     * instant app. By default, instant app components are not matched.
     * @hide
     */
    @SystemApi
    public static final int MATCH_INSTANT = 0x00800000;

    /**
     * Internal {@link PackageInfo} flag: include only components that are exposed to
     * instant apps. Matched components may have been either explicitly or implicitly
     * exposed.
     * @hide
     */
    public static final int MATCH_VISIBLE_TO_INSTANT_APP_ONLY = 0x01000000;

    /**
     * Internal {@link PackageInfo} flag: include only components that have been
     * explicitly exposed to instant apps.
     * @hide
     */
    public static final int MATCH_EXPLICITLY_VISIBLE_ONLY = 0x02000000;

    /**
     * Internal {@link PackageInfo} flag: include static shared libraries.
     * Apps that depend on static shared libs can always access the version
     * of the lib they depend on. System/shell/root can access all shared
     * libs regardless of dependency but need to explicitly ask for them
     * via this flag.
     * @hide
     */
    public static final int MATCH_STATIC_SHARED_LIBRARIES = 0x04000000;

    /**
     * {@link PackageInfo} flag: return the signing certificates associated with
     * this package.  Each entry is a signing certificate that the package
     * has proven it is authorized to use, usually a past signing certificate from
     * which it has rotated.
     */
    public static final int GET_SIGNING_CERTIFICATES = 0x08000000;

    /**
     * Querying flag: automatically match components based on their Direct Boot
     * awareness and the current user state.
     * <p>
     * Since the default behavior is to automatically apply the current user
     * state, this is effectively a sentinel value that doesn't change the
     * output of any queries based on its presence or absence.
     * <p>
     * Instead, this value can be useful in conjunction with
     * {@link android.os.StrictMode.VmPolicy.Builder#detectImplicitDirectBoot()}
     * to detect when a caller is relying on implicit automatic matching,
     * instead of confirming the explicit behavior they want, using a
     * combination of these flags:
     * <ul>
     * <li>{@link #MATCH_DIRECT_BOOT_AWARE}
     * <li>{@link #MATCH_DIRECT_BOOT_UNAWARE}
     * <li>{@link #MATCH_DIRECT_BOOT_AUTO}
     * </ul>
     */
    public static final int MATCH_DIRECT_BOOT_AUTO = 0x10000000;

    /** @hide */
    @Deprecated
    public static final int MATCH_DEBUG_TRIAGED_MISSING = MATCH_DIRECT_BOOT_AUTO;

    /**
     * Internal flag used to indicate that a package is a hidden system app.
     * @hide
     */
    public static final int MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS =  0x20000000;

    /**
     * {@link PackageInfo} flag: include APEX packages that are currently
     * installed. In APEX terminology, this corresponds to packages that are
     * currently active, i.e. mounted and available to other processes of the OS.
     * In particular, this flag alone will not match APEX files that are staged
     * for activation at next reboot.
     */
    public static final int MATCH_APEX = 0x40000000;

    /**
     * Flag for {@link #addCrossProfileIntentFilter}: if this flag is set: when
     * resolving an intent that matches the {@code CrossProfileIntentFilter},
     * the current profile will be skipped. Only activities in the target user
     * can respond to the intent.
     *
     * @hide
     */
    public static final int SKIP_CURRENT_PROFILE = 0x00000002;

    /**
     * Flag for {@link #addCrossProfileIntentFilter}: if this flag is set:
     * activities in the other profiles can respond to the intent only if no activity with
     * non-negative priority in current profile can respond to the intent.
     * @hide
     */
    public static final int ONLY_IF_NO_MATCH_FOUND = 0x00000004;

    /** @hide */
    @IntDef(prefix = { "PERMISSION_" }, value = {
            PERMISSION_GRANTED,
            PERMISSION_DENIED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionResult {}

    /**
     * Permission check result: this is returned by {@link #checkPermission}
     * if the permission has been granted to the given package.
     */
    public static final int PERMISSION_GRANTED = 0;

    /**
     * Permission check result: this is returned by {@link #checkPermission}
     * if the permission has not been granted to the given package.
     */
    public static final int PERMISSION_DENIED = -1;

    /** @hide */
    @IntDef(prefix = { "SIGNATURE_" }, value = {
            SIGNATURE_MATCH,
            SIGNATURE_NEITHER_SIGNED,
            SIGNATURE_FIRST_NOT_SIGNED,
            SIGNATURE_SECOND_NOT_SIGNED,
            SIGNATURE_NO_MATCH,
            SIGNATURE_UNKNOWN_PACKAGE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SignatureResult {}

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if all signatures on the two packages match.
     */
    public static final int SIGNATURE_MATCH = 0;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if neither of the two packages is signed.
     */
    public static final int SIGNATURE_NEITHER_SIGNED = 1;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if the first package is not signed but the second is.
     */
    public static final int SIGNATURE_FIRST_NOT_SIGNED = -1;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if the second package is not signed but the first is.
     */
    public static final int SIGNATURE_SECOND_NOT_SIGNED = -2;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if not all signatures on both packages match.
     */
    public static final int SIGNATURE_NO_MATCH = -3;

    /**
     * Signature check result: this is returned by {@link #checkSignatures}
     * if either of the packages are not valid.
     */
    public static final int SIGNATURE_UNKNOWN_PACKAGE = -4;

    /** @hide */
    @IntDef(prefix = { "COMPONENT_ENABLED_STATE_" }, value = {
            COMPONENT_ENABLED_STATE_DEFAULT,
            COMPONENT_ENABLED_STATE_ENABLED,
            COMPONENT_ENABLED_STATE_DISABLED,
            COMPONENT_ENABLED_STATE_DISABLED_USER,
            COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledState {}

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} and
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application is in its default enabled state (as specified in
     * its manifest).
     * <p>
     * Explicitly setting the component state to this value restores it's
     * enabled state to whatever is set in the manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_DEFAULT = 0;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)}
     * and {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application has been explictily enabled, regardless of
     * what it has specified in its manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_ENABLED = 1;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)}
     * and {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application has been explicitly disabled, regardless of
     * what it has specified in its manifest.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED = 2;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} only: The
     * user has explicitly disabled the application, regardless of what it has
     * specified in its manifest.  Because this is due to the user's request,
     * they may re-enable it if desired through the appropriate system UI.  This
     * option currently <strong>cannot</strong> be used with
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED_USER = 3;

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)} only: This
     * application should be considered, until the point where the user actually
     * wants to use it.  This means that it will not normally show up to the user
     * (such as in the launcher), but various parts of the user interface can
     * use {@link #GET_DISABLED_UNTIL_USED_COMPONENTS} to still see it and allow
     * the user to select it (as for example an IME, device admin, etc).  Such code,
     * once the user has selected the app, should at that point also make it enabled.
     * This option currently <strong>can not</strong> be used with
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}.
     */
    public static final int COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED = 4;

    /** @hide */
    @IntDef(flag = true, prefix = { "INSTALL_" }, value = {
            INSTALL_REPLACE_EXISTING,
            INSTALL_ALLOW_TEST,
            INSTALL_INTERNAL,
            INSTALL_FROM_ADB,
            INSTALL_ALL_USERS,
            INSTALL_REQUEST_DOWNGRADE,
            INSTALL_GRANT_RUNTIME_PERMISSIONS,
            INSTALL_FORCE_VOLUME_UUID,
            INSTALL_FORCE_PERMISSION_PROMPT,
            INSTALL_INSTANT_APP,
            INSTALL_DONT_KILL_APP,
            INSTALL_FULL_APP,
            INSTALL_ALLOCATE_AGGRESSIVE,
            INSTALL_VIRTUAL_PRELOAD,
            INSTALL_APEX,
            INSTALL_ENABLE_ROLLBACK,
            INSTALL_ALLOW_DOWNGRADE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallFlags {}

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to
     * replace an already installed package, if one exists.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int INSTALL_REPLACE_EXISTING = 0x00000002;

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to
     * allow test packages (those that have set android:testOnly in their
     * manifest) to be installed.
     * @hide
     */
    public static final int INSTALL_ALLOW_TEST = 0x00000004;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * must be installed to internal storage.
     *
     * @hide
     */
    public static final int INSTALL_INTERNAL = 0x00000010;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this install
     * was initiated via ADB.
     *
     * @hide
     */
    public static final int INSTALL_FROM_ADB = 0x00000020;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this install
     * should immediately be visible to all users.
     *
     * @hide
     */
    public static final int INSTALL_ALL_USERS = 0x00000040;

    /**
     * Flag parameter for {@link #installPackage} to indicate that an upgrade to a lower version
     * of a package than currently installed has been requested.
     *
     * <p>Note that this flag doesn't guarantee that downgrade will be performed. That decision
     * depends
     * on whenever:
     * <ul>
     * <li>An app is debuggable.
     * <li>Or a build is debuggable.
     * <li>Or {@link #INSTALL_ALLOW_DOWNGRADE} is set.
     * </ul>
     *
     * @hide
     */
    public static final int INSTALL_REQUEST_DOWNGRADE = 0x00000080;

    /**
     * Flag parameter for {@link #installPackage} to indicate that all runtime
     * permissions should be granted to the package. If {@link #INSTALL_ALL_USERS}
     * is set the runtime permissions will be granted to all users, otherwise
     * only to the owner.
     *
     * @hide
     */
    public static final int INSTALL_GRANT_RUNTIME_PERMISSIONS = 0x00000100;

    /** {@hide} */
    public static final int INSTALL_FORCE_VOLUME_UUID = 0x00000200;

    /**
     * Flag parameter for {@link #installPackage} to indicate that we always want to force
     * the prompt for permission approval. This overrides any special behaviour for internal
     * components.
     *
     * @hide
     */
    public static final int INSTALL_FORCE_PERMISSION_PROMPT = 0x00000400;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package is
     * to be installed as a lightweight "ephemeral" app.
     *
     * @hide
     */
    public static final int INSTALL_INSTANT_APP = 0x00000800;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package contains
     * a feature split to an existing application and the existing application should not
     * be killed during the installation process.
     *
     * @hide
     */
    public static final int INSTALL_DONT_KILL_APP = 0x00001000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package is
     * to be installed as a heavy weight app. This is fundamentally the opposite of
     * {@link #INSTALL_INSTANT_APP}.
     *
     * @hide
     */
    public static final int INSTALL_FULL_APP = 0x00004000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is critical to system health or security, meaning the system should use
     * {@link StorageManager#FLAG_ALLOCATE_AGGRESSIVE} internally.
     *
     * @hide
     */
    public static final int INSTALL_ALLOCATE_AGGRESSIVE = 0x00008000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is a virtual preload.
     *
     * @hide
     */
    public static final int INSTALL_VIRTUAL_PRELOAD = 0x00010000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * is an APEX package
     *
     * @hide
     */
    public static final int INSTALL_APEX = 0x00020000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that rollback
     * should be enabled for this install.
     *
     * @hide
     */
    public static final int INSTALL_ENABLE_ROLLBACK = 0x00040000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that package verification should be
     * disabled for this package.
     *
     * @hide
     */
    public static final int INSTALL_DISABLE_VERIFICATION = 0x00080000;

    /**
     * Flag parameter for {@link #installPackage} to indicate that
     * {@link #INSTALL_REQUEST_DOWNGRADE} should be allowed.
     *
     * @hide
     */
    public static final int INSTALL_ALLOW_DOWNGRADE = 0x00100000;

    /** @hide */
    @IntDef(flag = true, prefix = { "DONT_KILL_APP" }, value = {
            DONT_KILL_APP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EnabledFlags {}

    /**
     * Flag parameter for
     * {@link #setComponentEnabledSetting(android.content.ComponentName, int, int)} to indicate
     * that you don't want to kill the app containing the component.  Be careful when you set this
     * since changing component states can make the containing application's behavior unpredictable.
     */
    public static final int DONT_KILL_APP = 0x00000001;

    /** @hide */
    @IntDef(prefix = { "INSTALL_REASON_" }, value = {
            INSTALL_REASON_UNKNOWN,
            INSTALL_REASON_POLICY,
            INSTALL_REASON_DEVICE_RESTORE,
            INSTALL_REASON_DEVICE_SETUP,
            INSTALL_REASON_USER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallReason {}

    /**
     * Code indicating that the reason for installing this package is unknown.
     */
    public static final int INSTALL_REASON_UNKNOWN = 0;

    /**
     * Code indicating that this package was installed due to enterprise policy.
     */
    public static final int INSTALL_REASON_POLICY = 1;

    /**
     * Code indicating that this package was installed as part of restoring from another device.
     */
    public static final int INSTALL_REASON_DEVICE_RESTORE = 2;

    /**
     * Code indicating that this package was installed as part of device setup.
     */
    public static final int INSTALL_REASON_DEVICE_SETUP = 3;

    /**
     * Code indicating that the package installation was initiated by the user.
     */
    public static final int INSTALL_REASON_USER = 4;

    /**
     * @hide
     */
    public static final int INSTALL_UNKNOWN = 0;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * on success.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_SUCCEEDED = 1;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package is already installed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_ALREADY_EXISTS = -1;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package archive file is invalid.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_APK = -2;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the URI passed in is invalid.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_URI = -3;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package manager service found that the device didn't have enough storage space to
     * install the app.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if a package is already installed with the same name.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DUPLICATE_PACKAGE = -5;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the requested shared user does not exist.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NO_SHARED_USER = -6;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if a previously installed package of the same name has a different signature than the new
     * package (and the old package's data was not removed).
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package is requested a shared user which is already installed on the device and
     * does not have matching signature.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_SHARED_USER_INCOMPATIBLE = -8;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package uses a shared library that is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_SHARED_LIBRARY = -9;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package uses a shared library that is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_REPLACE_COULDNT_DELETE = -10;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed while optimizing and validating its dex files, either because there
     * was not enough storage or the validation failed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DEXOPT = -11;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because the current SDK version is older than that required by the
     * package.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_OLDER_SDK = -12;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because it contains a content provider with the same authority as a
     * provider already installed in the system.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONFLICTING_PROVIDER = -13;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because the current SDK version is newer than that required by the
     * package.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NEWER_SDK = -14;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package failed because it has specified that it is a test-only package and the
     * caller has not supplied the {@link #INSTALL_ALLOW_TEST} flag.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_TEST_ONLY = -15;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package being installed contains native code, but none that is compatible with the
     * device's CPU_ABI.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CPU_ABI_INCOMPATIBLE = -16;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package uses a feature that is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_FEATURE = -17;

    // ------ Errors related to sdcard
    /**
     * Installation return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if a secure container mount point couldn't be
     * accessed on external media.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONTAINER_ERROR = -18;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed in the specified install location.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_INSTALL_LOCATION = -19;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed in the specified install location because the media
     * is not available.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MEDIA_UNAVAILABLE = -20;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed because the verification timed out.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_TIMEOUT = -21;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package couldn't be installed because the verification did not succeed.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_FAILURE = -22;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the package changed from what the calling program expected.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_PACKAGE_CHANGED = -23;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package is assigned a different UID than it previously held.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_UID_CHANGED = -24;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package has an older version code than the currently installed package.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_VERSION_DOWNGRADE = -25;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the old package has target SDK high enough to support runtime permission and the new
     * package has target SDK low enough to not support runtime permissions.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE = -26;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package attempts to downgrade the target sandbox version of the app.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_SANDBOX_VERSION_DOWNGRADE = -27;

    /**
     * Installation return code: this is passed in the {@link PackageInstaller#EXTRA_LEGACY_STATUS}
     * if the new package requires at least one split and it was not provided.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_MISSING_SPLIT = -28;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser was given a path that is not a
     * file, or does not end with the expected '.apk' extension.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NOT_APK = -100;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser was unable to retrieve the
     * AndroidManifest.xml file.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_MANIFEST = -101;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered an unexpected
     * exception.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION = -102;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser did not find any certificates in
     * the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser found inconsistent certificates on
     * the files in the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES = -104;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a
     * CertificateEncodingException in one of the files in the .apk.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING = -105;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a bad or missing
     * package name in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -106;

    /**
     * Installation parse return code: tthis is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered a bad shared user id
     * name in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID = -107;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser encountered some structural
     * problem in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the parser did not find any actionable tags
     * (instrumentation or application) in the manifest.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_EMPTY = -109;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because of system issues.
     *
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INTERNAL_ERROR = -110;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because the user is restricted from installing apps.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_USER_RESTRICTED = -111;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because it is attempting to define a permission that is already defined by some existing
     * package.
     * <p>
     * The package name of the app which has already defined the permission is passed to a
     * {@link PackageInstallObserver}, if any, as the {@link #EXTRA_FAILURE_EXISTING_PACKAGE} string
     * extra; and the name of the permission being redefined is passed in the
     * {@link #EXTRA_FAILURE_EXISTING_PERMISSION} string extra.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION = -112;

    /**
     * Installation failed return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the system failed to install the package
     * because its packaged native code did not match any of the ABIs supported by the system.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_NO_MATCHING_ABIS = -113;

    /**
     * Internal return code for NativeLibraryHelper methods to indicate that the package
     * being processed did not contain any native code. This is placed here only so that
     * it can belong to the same value space as the other install failure codes.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int NO_NATIVE_LIBRARIES = -114;

    /** {@hide} */
    public static final int INSTALL_FAILED_ABORTED = -115;

    /**
     * Installation failed return code: instant app installs are incompatible with some
     * other installation flags supplied for the operation; or other circumstances such
     * as trying to upgrade a system app via an instant app install.
     * @hide
     */
    public static final int INSTALL_FAILED_INSTANT_APP_INVALID = -116;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if the dex metadata file is invalid or
     * if there was no matching apk file for a dex metadata file.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_BAD_DEX_METADATA = -117;

    /**
     * Installation parse return code: this is passed in the
     * {@link PackageInstaller#EXTRA_LEGACY_STATUS} if there is any signature problem.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_BAD_SIGNATURE = -118;

    /**
     * Installation failed return code: a new staged session was attempted to be committed while
     * there is already one in-progress.
     *
     * @hide
     */
    public static final int INSTALL_FAILED_OTHER_STAGED_SESSION_IN_PROGRESS = -119;

    /** @hide */
    @IntDef(flag = true, prefix = { "DELETE_" }, value = {
            DELETE_KEEP_DATA,
            DELETE_ALL_USERS,
            DELETE_SYSTEM_APP,
            DELETE_DONT_KILL_APP,
            DELETE_CHATTY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DeleteFlags {}

    /**
     * Flag parameter for {@link #deletePackage} to indicate that you don't want to delete the
     * package's data directory.
     *
     * @hide
     */
    public static final int DELETE_KEEP_DATA = 0x00000001;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that you want the
     * package deleted for all users.
     *
     * @hide
     */
    public static final int DELETE_ALL_USERS = 0x00000002;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that, if you are calling
     * uninstall on a system that has been updated, then don't do the normal process
     * of uninstalling the update and rolling back to the older system version (which
     * needs to happen for all users); instead, just mark the app as uninstalled for
     * the current user.
     *
     * @hide
     */
    public static final int DELETE_SYSTEM_APP = 0x00000004;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that, if you are calling
     * uninstall on a package that is replaced to provide new feature splits, the
     * existing application should not be killed during the removal process.
     *
     * @hide
     */
    public static final int DELETE_DONT_KILL_APP = 0x00000008;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that any
     * contributed media should also be deleted during this uninstall. The
     * meaning of "contributed" means it won't automatically be deleted when the
     * app is uninstalled.
     *
     * @hide
     */
    public static final int DELETE_CONTRIBUTED_MEDIA = 0x00000010;

    /**
     * Flag parameter for {@link #deletePackage} to indicate that package deletion
     * should be chatty.
     *
     * @hide
     */
    public static final int DELETE_CHATTY = 0x80000000;

    /**
     * Return code for when package deletion succeeds. This is passed to the
     * {@link IPackageDeleteObserver} if the system succeeded in deleting the
     * package.
     *
     * @hide
     */
    public static final int DELETE_SUCCEEDED = 1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * for an unspecified reason.
     *
     * @hide
     */
    public static final int DELETE_FAILED_INTERNAL_ERROR = -1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because it is the active DevicePolicy manager.
     *
     * @hide
     */
    public static final int DELETE_FAILED_DEVICE_POLICY_MANAGER = -2;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * since the user is restricted.
     *
     * @hide
     */
    public static final int DELETE_FAILED_USER_RESTRICTED = -3;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because a profile or device owner has marked the package as
     * uninstallable.
     *
     * @hide
     */
    public static final int DELETE_FAILED_OWNER_BLOCKED = -4;

    /** {@hide} */
    public static final int DELETE_FAILED_ABORTED = -5;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} if the system failed to delete the package
     * because the packge is a shared library used by other installed packages.
     * {@hide} */
    public static final int DELETE_FAILED_USED_SHARED_LIBRARY = -6;

    /**
     * Return code that is passed to the {@link IPackageMoveObserver} when the
     * package has been successfully moved by the system.
     *
     * @hide
     */
    public static final int MOVE_SUCCEEDED = -100;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} when the
     * package hasn't been successfully moved by the system because of
     * insufficient memory on specified media.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INSUFFICIENT_STORAGE = -1;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package doesn't exist.
     *
     * @hide
     */
    public static final int MOVE_FAILED_DOESNT_EXIST = -2;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved since its a system package.
     *
     * @hide
     */
    public static final int MOVE_FAILED_SYSTEM_PACKAGE = -3;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved to the specified location.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INVALID_LOCATION = -5;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved to the specified location.
     *
     * @hide
     */
    public static final int MOVE_FAILED_INTERNAL_ERROR = -6;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package already has an operation pending in the queue.
     *
     * @hide
     */
    public static final int MOVE_FAILED_OPERATION_PENDING = -7;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if the
     * specified package cannot be moved since it contains a device admin.
     *
     * @hide
     */
    public static final int MOVE_FAILED_DEVICE_ADMIN = -8;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} if system does not allow
     * non-system apps to be moved to internal storage.
     *
     * @hide
     */
    public static final int MOVE_FAILED_3RD_PARTY_NOT_ALLOWED_ON_INTERNAL = -9;

    /** @hide */
    public static final int MOVE_FAILED_LOCKED_USER = -10;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to internal storage if its
     * been installed on external media.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static final int MOVE_INTERNAL = 0x00000001;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to external media.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static final int MOVE_EXTERNAL_MEDIA = 0x00000002;

    /** {@hide} */
    public static final String EXTRA_MOVE_ID = "android.content.pm.extra.MOVE_ID";

    /**
     * Usable by the required verifier as the {@code verificationCode} argument
     * for {@link PackageManager#verifyPendingInstall} to indicate that it will
     * allow the installation to proceed without any of the optional verifiers
     * needing to vote.
     *
     * @hide
     */
    public static final int VERIFICATION_ALLOW_WITHOUT_SUFFICIENT = 2;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyPendingInstall} to indicate that the calling
     * package verifier allows the installation to proceed.
     */
    public static final int VERIFICATION_ALLOW = 1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyPendingInstall} to indicate the calling
     * package verifier does not vote to allow the installation to proceed.
     */
    public static final int VERIFICATION_REJECT = -1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyIntentFilter} to indicate that the calling
     * IntentFilter Verifier confirms that the IntentFilter is verified.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_VERIFICATION_SUCCESS = 1;

    /**
     * Used as the {@code verificationCode} argument for
     * {@link PackageManager#verifyIntentFilter} to indicate that the calling
     * IntentFilter Verifier confirms that the IntentFilter is NOT verified.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_VERIFICATION_FAILURE = -1;

    /**
     * Internal status code to indicate that an IntentFilter verification result is not specified.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED = 0;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * will always be prompted the Intent Disambiguation Dialog if there are two
     * or more Intent resolved for the IntentFilter's domain(s).
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK = 1;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * will never be prompted the Intent Disambiguation Dialog if there are two
     * or more resolution of the Intent. The default App for the domain(s)
     * specified in the IntentFilter will also ALWAYS be used.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS = 2;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that the User
     * may be prompted the Intent Disambiguation Dialog if there are two or more
     * Intent resolved. The default App for the domain(s) specified in the
     * IntentFilter will also NEVER be presented to the User.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER = 3;

    /**
     * Used as the {@code status} argument for
     * {@link #updateIntentVerificationStatusAsUser} to indicate that this app
     * should always be considered as an ambiguous candidate for handling the
     * matching Intent even if there are other candidate apps in the "always"
     * state. Put another way: if there are any 'always ask' apps in a set of
     * more than one candidate app, then a disambiguation is *always* presented
     * even if there is another candidate app with the 'always' state.
     *
     * @hide
     */
    @SystemApi
    public static final int INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS_ASK = 4;

    /**
     * Can be used as the {@code millisecondsToDelay} argument for
     * {@link PackageManager#extendVerificationTimeout}. This is the
     * maximum time {@code PackageManager} waits for the verification
     * agent to return (in milliseconds).
     */
    public static final long MAXIMUM_VERIFICATION_TIMEOUT = 60*60*1000;

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device's
     * audio pipeline is low-latency, more suitable for audio applications sensitive to delays or
     * lag in sound input or output.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_LOW_LATENCY = "android.hardware.audio.low_latency";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes at least one form of audio
     * output, as defined in the Android Compatibility Definition Document (CDD)
     * <a href="https://source.android.com/compatibility/android-cdd#7_8_audio">section 7.8 Audio</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_OUTPUT = "android.hardware.audio.output";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has professional audio level of functionality and performance.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUDIO_PRO = "android.hardware.audio.pro";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * other devices via Bluetooth.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BLUETOOTH = "android.hardware.bluetooth";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * other devices via Bluetooth Low Energy radio.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BLUETOOTH_LE = "android.hardware.bluetooth_le";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a camera facing away
     * from the screen.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA = "android.hardware.camera";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's camera supports auto-focus.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_AUTOFOCUS = "android.hardware.camera.autofocus";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has at least one camera pointing in
     * some direction, or can support an external camera being connected to it.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_ANY = "android.hardware.camera.any";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can support having an external camera connected to it.
     * The external camera may not always be connected or available to applications to use.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_EXTERNAL = "android.hardware.camera.external";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's camera supports flash.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_FLASH = "android.hardware.camera.flash";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a front facing camera.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_FRONT = "android.hardware.camera.front";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraCharacteristics#INFO_SUPPORTED_HARDWARE_LEVEL full hardware}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_LEVEL_FULL = "android.hardware.camera.level.full";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR manual sensor}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_SENSOR =
            "android.hardware.camera.capability.manual_sensor";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING manual post-processing}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_MANUAL_POST_PROCESSING =
            "android.hardware.camera.capability.manual_post_processing";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_RAW RAW}
     * capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_CAPABILITY_RAW =
            "android.hardware.camera.capability.raw";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: At least one
     * of the cameras on the device supports the
     * {@link android.hardware.camera2.CameraMetadata#REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING
     * MOTION_TRACKING} capability level.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CAMERA_AR =
            "android.hardware.camera.ar";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * consumer IR devices.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONSUMER_IR = "android.hardware.consumerir";

    /** {@hide} */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CTS = "android.software.cts";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports one or more methods of
     * reporting current location.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION = "android.hardware.location";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a Global Positioning System
     * receiver and can report precise location.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION_GPS = "android.hardware.location.gps";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can report location with coarse
     * accuracy using a network-based geolocation system.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOCATION_NETWORK = "android.hardware.location.network";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's
     * {@link ActivityManager#isLowRamDevice() ActivityManager.isLowRamDevice()} method returns
     * true.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_RAM_LOW = "android.hardware.ram.low";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's
     * {@link ActivityManager#isLowRamDevice() ActivityManager.isLowRamDevice()} method returns
     * false.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_RAM_NORMAL = "android.hardware.ram.normal";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can record audio via a
     * microphone.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MICROPHONE = "android.hardware.microphone";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device can communicate using Near-Field
     * Communications (NFC).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC = "android.hardware.nfc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC card emulation.
     *
     * TODO remove when depending apps have moved to new constant.
     * @hide
     * @deprecated
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HCE = "android.hardware.nfc.hce";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HOST_CARD_EMULATION = "android.hardware.nfc.hce";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports host-
     * based NFC-F card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_HOST_CARD_EMULATION_NFCF = "android.hardware.nfc.hcef";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports uicc-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_UICC =
                                                                       "android.hardware.nfc.uicc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports eSE-
     * based NFC card emulation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_OFF_HOST_CARD_EMULATION_ESE = "android.hardware.nfc.ese";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The Beam API is enabled on the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_BEAM = "android.sofware.nfc.beam";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports any
     * one of the {@link #FEATURE_NFC}, {@link #FEATURE_NFC_HOST_CARD_EMULATION},
     * or {@link #FEATURE_NFC_HOST_CARD_EMULATION_NFCF} features.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_NFC_ANY = "android.hardware.nfc.any";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports the OpenGL ES
     * <a href="http://www.khronos.org/registry/gles/extensions/ANDROID/ANDROID_extension_pack_es31a.txt">
     * Android Extension Pack</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_OPENGLES_EXTENSION_PACK = "android.hardware.opengles.aep";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the Vulkan native API
     * will enumerate at least one {@code VkPhysicalDevice}, and the feature version will indicate
     * what level of optional hardware features limits it supports.
     * <p>
     * Level 0 includes the base Vulkan requirements as well as:
     * <ul><li>{@code VkPhysicalDeviceFeatures::textureCompressionETC2}</li></ul>
     * <p>
     * Level 1 additionally includes:
     * <ul>
     * <li>{@code VkPhysicalDeviceFeatures::fullDrawIndexUint32}</li>
     * <li>{@code VkPhysicalDeviceFeatures::imageCubeArray}</li>
     * <li>{@code VkPhysicalDeviceFeatures::independentBlend}</li>
     * <li>{@code VkPhysicalDeviceFeatures::geometryShader}</li>
     * <li>{@code VkPhysicalDeviceFeatures::tessellationShader}</li>
     * <li>{@code VkPhysicalDeviceFeatures::sampleRateShading}</li>
     * <li>{@code VkPhysicalDeviceFeatures::textureCompressionASTC_LDR}</li>
     * <li>{@code VkPhysicalDeviceFeatures::fragmentStoresAndAtomics}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderImageGatherExtended}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderUniformBufferArrayDynamicIndexing}</li>
     * <li>{@code VkPhysicalDeviceFeatures::shaderSampledImageArrayDynamicIndexing}</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_LEVEL = "android.hardware.vulkan.level";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: If this feature is supported, the Vulkan native API
     * will enumerate at least one {@code VkPhysicalDevice}, and the feature version will indicate
     * what level of optional compute features that device supports beyond the Vulkan 1.0
     * requirements.
     * <p>
     * Compute level 0 indicates:
     * <ul>
     * <li>The {@code VK_KHR_variable_pointers} extension and
     *     {@code VkPhysicalDeviceVariablePointerFeaturesKHR::variablePointers} feature are
           supported.</li>
     * <li>{@code VkPhysicalDeviceLimits::maxPerStageDescriptorStorageBuffers} is at least 16.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_COMPUTE = "android.hardware.vulkan.compute";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature(String, int)}: The version of this feature indicates the highest
     * {@code VkPhysicalDeviceProperties::apiVersion} supported by the physical devices that support
     * the hardware level indicated by {@link #FEATURE_VULKAN_HARDWARE_LEVEL}. The feature version
     * uses the same encoding as Vulkan version numbers:
     * <ul>
     * <li>Major version number in bits 31-22</li>
     * <li>Minor version number in bits 21-12</li>
     * <li>Patch version number in bits 11-0</li>
     * </ul>
     * A version of 1.1.0 or higher also indicates:
     * <ul>
     * <li>The {@code VK_ANDROID_external_memory_android_hardware_buffer} extension is
     *     supported.</li>
     * <li>{@code SYNC_FD} external semaphore and fence handles are supported.</li>
     * <li>{@code VkPhysicalDeviceSamplerYcbcrConversionFeatures::samplerYcbcrConversion} is
     *     supported.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VULKAN_HARDWARE_VERSION = "android.hardware.vulkan.version";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes broadcast radio tuner.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BROADCAST_RADIO = "android.hardware.broadcastradio";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a secure implementation of keyguard, meaning the
     * device supports PIN, pattern and password as defined in Android CDD
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SECURE_LOCK_SCREEN = "android.software.secure_lock_screen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an accelerometer.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_ACCELEROMETER = "android.hardware.sensor.accelerometer";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a barometer (air
     * pressure sensor.)
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_BAROMETER = "android.hardware.sensor.barometer";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a magnetometer (compass).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_COMPASS = "android.hardware.sensor.compass";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a gyroscope.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_GYROSCOPE = "android.hardware.sensor.gyroscope";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a light sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_LIGHT = "android.hardware.sensor.light";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a proximity sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_PROXIMITY = "android.hardware.sensor.proximity";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a hardware step counter.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_STEP_COUNTER = "android.hardware.sensor.stepcounter";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a hardware step detector.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_STEP_DETECTOR = "android.hardware.sensor.stepdetector";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a heart rate monitor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HEART_RATE = "android.hardware.sensor.heartrate";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The heart rate sensor on this device is an Electrocardiogram.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_HEART_RATE_ECG =
            "android.hardware.sensor.heartrate.ecg";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes a relative humidity sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_RELATIVE_HUMIDITY =
            "android.hardware.sensor.relative_humidity";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device includes an ambient temperature sensor.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SENSOR_AMBIENT_TEMPERATURE =
            "android.hardware.sensor.ambient_temperature";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports high fidelity sensor processing
     * capabilities.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HIFI_SENSORS =
            "android.hardware.sensor.hifi_sensors";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports a hardware mechanism for invoking an assist gesture.
     * @see android.provider.Settings.Secure#ASSIST_GESTURE_ENABLED
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ASSIST_GESTURE = "android.hardware.sensor.assist";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a telephony radio with data
     * communication support.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY = "android.hardware.telephony";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a CDMA telephony stack.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_CDMA = "android.hardware.telephony.cdma";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has a GSM telephony stack.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_GSM = "android.hardware.telephony.gsm";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports telephony carrier restriction mechanism.
     *
     * <p>Devices declaring this feature must have an implementation of the
     * {@link android.telephony.TelephonyManager#getAllowedCarriers} and
     * {@link android.telephony.TelephonyManager#setAllowedCarriers}.
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_CARRIERLOCK =
            "android.hardware.telephony.carrierlock";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports embedded subscriptions on eUICCs.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_EUICC = "android.hardware.telephony.euicc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports cell-broadcast reception using the MBMS APIs.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_MBMS = "android.hardware.telephony.mbms";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device
     * supports attaching to IMS implementations using the ImsService API in telephony.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEPHONY_IMS = "android.hardware.telephony.ims";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports connecting to USB devices
     * as the USB host.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_USB_HOST = "android.hardware.usb.host";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports connecting to USB accessories.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_USB_ACCESSORY = "android.hardware.usb.accessory";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The SIP API is enabled on the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SIP = "android.software.sip";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports SIP-based VOIP.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SIP_VOIP = "android.software.sip.voip";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The Connection Service API is enabled on the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONNECTION_SERVICE = "android.software.connectionservice";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's display has a touch screen.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN = "android.hardware.touchscreen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen supports
     * multitouch sufficient for basic two-finger gesture detection.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH = "android.hardware.touchscreen.multitouch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen is capable of
     * tracking two or more fingers fully independently.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT = "android.hardware.touchscreen.multitouch.distinct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device's touch screen is capable of
     * tracking a full hand of fingers fully independently -- that is, 5 or
     * more simultaneous independent pointers.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND = "android.hardware.touchscreen.multitouch.jazzhand";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events. For instance, the
     * device might use a mouse or remote control to drive a cursor, and
     * emulate basic touch pointer events like down, up, drag, etc. All
     * devices that support android.hardware.touchscreen or a sub-feature are
     * presumed to also support faketouch.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH = "android.hardware.faketouch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events that supports distinct
     * tracking of two or more fingers.  This is an extension of
     * {@link #FEATURE_FAKETOUCH} for input devices with this capability.  Note
     * that unlike a distinct multitouch screen as defined by
     * {@link #FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT}, these kinds of input
     * devices will not actually provide full two-finger gestures since the
     * input is being transformed to cursor movement on the screen.  That is,
     * single finger gestures will move a cursor; two-finger swipes will
     * result in single-finger touch events; other two-finger gestures will
     * result in the corresponding two-finger touch event.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT = "android.hardware.faketouch.multitouch.distinct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device does not have a touch screen, but
     * does support touch emulation for basic events that supports tracking
     * a hand of fingers (5 or more fingers) fully independently.
     * This is an extension of
     * {@link #FEATURE_FAKETOUCH} for input devices with this capability.  Note
     * that unlike a multitouch screen as defined by
     * {@link #FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND}, not all two finger
     * gestures can be detected due to the limitations described for
     * {@link #FEATURE_FAKETOUCH_MULTITOUCH_DISTINCT}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FAKETOUCH_MULTITOUCH_JAZZHAND = "android.hardware.faketouch.multitouch.jazzhand";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to detect a fingerprint.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FINGERPRINT = "android.hardware.fingerprint";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to perform face authentication.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FACE = "android.hardware.biometrics.face";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device has biometric hardware to perform iris authentication.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IRIS = "android.hardware.biometrics.iris";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports portrait orientation
     * screens.  For backwards compatibility, you can assume that if neither
     * this nor {@link #FEATURE_SCREEN_LANDSCAPE} is set then the device supports
     * both portrait and landscape.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SCREEN_PORTRAIT = "android.hardware.screen.portrait";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports landscape orientation
     * screens.  For backwards compatibility, you can assume that if neither
     * this nor {@link #FEATURE_SCREEN_PORTRAIT} is set then the device supports
     * both portrait and landscape.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SCREEN_LANDSCAPE = "android.hardware.screen.landscape";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports live wallpapers.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LIVE_WALLPAPER = "android.software.live_wallpaper";
    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports app widgets.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_APP_WIDGETS = "android.software.app_widgets";
    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports the
     * {@link android.R.attr#cantSaveState} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CANT_SAVE_STATE = "android.software.cant_save_state";

    /**
     * @hide
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports
     * {@link android.service.voice.VoiceInteractionService} and
     * {@link android.app.VoiceInteractor}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VOICE_RECOGNIZERS = "android.software.voice_recognizers";


    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports a home screen that is replaceable
     * by third party applications.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HOME_SCREEN = "android.software.home_screen";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports adding new input methods implemented
     * with the {@link android.inputmethodservice.InputMethodService} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_INPUT_METHODS = "android.software.input_methods";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports device policy enforcement via device admins.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DEVICE_ADMIN = "android.software.device_admin";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports leanback UI. This is
     * typically used in a living room television experience, but is a software
     * feature unlike {@link #FEATURE_TELEVISION}. Devices running with this
     * feature will use resources associated with the "television" UI mode.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LEANBACK = "android.software.leanback";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports only leanback UI. Only
     * applications designed for this experience should be run, though this is
     * not enforced by the system.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LEANBACK_ONLY = "android.software.leanback_only";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports live TV and can display
     * contents from TV inputs implemented with the
     * {@link android.media.tv.TvInputService} API.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LIVE_TV = "android.software.live_tv";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports WiFi (802.11) networking.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI = "android.hardware.wifi";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Direct networking.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_DIRECT = "android.hardware.wifi.direct";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Aware.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_AWARE = "android.hardware.wifi.aware";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi Passpoint and all
     * Passpoint related APIs in {@link WifiManager} are supported. Refer to
     * {@link WifiManager#addOrUpdatePasspointConfiguration} for more info.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_PASSPOINT = "android.hardware.wifi.passpoint";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports Wi-Fi RTT (IEEE 802.11mc).
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WIFI_RTT = "android.hardware.wifi.rtt";


    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports LoWPAN networking.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LOWPAN = "android.hardware.lowpan";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a vehicle headunit. A headunit here is defined to be inside a
     * vehicle that may or may not be moving. A headunit uses either a
     * primary display in the center console and/or additional displays in
     * the instrument cluster or elsewhere in the vehicle. Headunit display(s)
     * have limited size and resolution. The user will likely be focused on
     * driving so limiting driver distraction is a primary concern. User input
     * can be a variety of hard buttons, touch, rotary controllers and even mouse-
     * like interfaces.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a television.  Television here is defined to be a typical living
     * room television experience: displayed on a big screen, where the user
     * is sitting far away from it, and the dominant form of input will be
     * something like a DPAD, not through touch or mouse.
     * @deprecated use {@link #FEATURE_LEANBACK} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_TELEVISION = "android.hardware.type.television";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to showing UI
     * on a watch. A watch here is defined to be a device worn on the body, perhaps on
     * the wrist. The user is very close when interacting with the device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WATCH = "android.hardware.type.watch";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device for IoT and may not have an UI. An embedded
     * device is defined as a full stack Android device with or without a display and no
     * user-installable apps.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_EMBEDDED = "android.hardware.type.embedded";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a device dedicated to be primarily used
     * with keyboard, mouse or touchpad. This includes traditional desktop
     * computers, laptops and variants such as convertibles or detachables.
     * Due to the larger screen, the device will most likely use the
     * {@link #FEATURE_FREEFORM_WINDOW_MANAGEMENT} feature as well.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PC = "android.hardware.type.pc";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This is a foldable device. Properties such as
     * the display size may change in response to being folded.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FOLDABLE = "android.hardware.type.foldable";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports printing.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PRINTING = "android.software.print";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports {@link android.companion.CompanionDeviceManager#associate associating}
     * with devices via {@link android.companion.CompanionDeviceManager}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_COMPANION_DEVICE_SETUP
            = "android.software.companion_device_setup";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device can perform backup and restore operations on installed applications.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BACKUP = "android.software.backup";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device supports freeform window management.
     * Windows have title bars and can be moved and resized.
     */
    // If this feature is present, you also need to set
    // com.android.internal.R.config_freeformWindowManagement to true in your configuration overlay.
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FREEFORM_WINDOW_MANAGEMENT
            = "android.software.freeform_window_management";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports picture-in-picture multi-window mode.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PICTURE_IN_PICTURE = "android.software.picture_in_picture";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports running activities on secondary displays.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
            = "android.software.activities_on_secondary_displays";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports creating secondary users and managed profiles via
     * {@link DevicePolicyManager}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MANAGED_USERS = "android.software.managed_users";

    /**
     * @hide
     * TODO: Remove after dependencies updated b/17392243
     */
    public static final String FEATURE_MANAGED_PROFILES = "android.software.managed_users";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports verified boot.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VERIFIED_BOOT = "android.software.verified_boot";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports secure removal of users. When a user is deleted the data associated
     * with that user is securely deleted and no longer available.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_SECURELY_REMOVES_USERS
            = "android.software.securely_removes_users";

    /** {@hide} */
    @TestApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_FILE_BASED_ENCRYPTION
            = "android.software.file_based_encryption";

    /** {@hide} */
    @TestApi
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ADOPTABLE_STORAGE
            = "android.software.adoptable_storage";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a full implementation of the android.webkit.* APIs. Devices
     * lacking this feature will not have a functioning WebView implementation.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_WEBVIEW = "android.software.webview";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This device supports ethernet.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_ETHERNET = "android.hardware.ethernet";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: This device supports HDMI-CEC.
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_HDMI_CEC = "android.hardware.hdmi.cec";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has all of the inputs necessary to be considered a compatible game controller, or
     * includes a compatible game controller in the box.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_GAMEPAD = "android.hardware.gamepad";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a full implementation of the android.media.midi.* APIs.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MIDI = "android.software.midi";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements an optimized mode for virtual reality (VR) applications that handles
     * stereoscopic rendering of notifications, and disables most monocular system UI components
     * while a VR application has user focus.
     * Devices declaring this feature must include an application implementing a
     * {@link android.service.vr.VrListenerService} that can be targeted by VR applications via
     * {@link android.app.Activity#setVrModeEnabled}.
     * @deprecated use {@link #FEATURE_VR_MODE_HIGH_PERFORMANCE} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_MODE = "android.software.vr.mode";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements an optimized mode for virtual reality (VR) applications that handles
     * stereoscopic rendering of notifications, disables most monocular system UI components
     * while a VR application has user focus and meets extra CDD requirements to provide a
     * high-quality VR experience.
     * Devices declaring this feature must include an application implementing a
     * {@link android.service.vr.VrListenerService} that can be targeted by VR applications via
     * {@link android.app.Activity#setVrModeEnabled}.
     * and must meet CDD requirements to provide a high-quality VR experience.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_MODE_HIGH_PERFORMANCE
            = "android.hardware.vr.high_performance";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports autofill of user credentials, addresses, credit cards, etc
     * via integration with {@link android.service.autofill.AutofillService autofill
     * providers}.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_AUTOFILL = "android.software.autofill";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device implements headtracking suitable for a VR device.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_VR_HEADTRACKING = "android.hardware.vr.headtracking";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a StrongBox hardware-backed Keystore.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_STRONGBOX_KEYSTORE =
            "android.hardware.strongbox_keystore";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device has a Keymaster implementation that supports Device ID attestation.
     *
     * @see DevicePolicyManager#isDeviceIdAttestationSupported
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_DEVICE_ID_ATTESTATION =
            "android.software.device_id_attestation";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}: The device has
     * the requisite kernel support for multinetworking-capable IPsec tunnels.
     *
     * <p>This feature implies that the device supports XFRM Interfaces (CONFIG_XFRM_INTERFACE), or
     * VTIs with kernel patches allowing updates of output/set mark via UPDSA.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_IPSEC_TUNNELS = "android.software.ipsec_tunnels";

    /**
     * Extra field name for the URI to a verification file. Passed to a package
     * verifier.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_URI = "android.content.pm.extra.VERIFICATION_URI";

    /**
     * Extra field name for the ID of a package pending verification. Passed to
     * a package verifier and is used to call back to
     * {@link PackageManager#verifyPendingInstall(int, int)}
     */
    public static final String EXTRA_VERIFICATION_ID = "android.content.pm.extra.VERIFICATION_ID";

    /**
     * Extra field name for the package identifier which is trying to install
     * the package.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALLER_PACKAGE
            = "android.content.pm.extra.VERIFICATION_INSTALLER_PACKAGE";

    /**
     * Extra field name for the requested install flags for a package pending
     * verification. Passed to a package verifier.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALL_FLAGS
            = "android.content.pm.extra.VERIFICATION_INSTALL_FLAGS";

    /**
     * Extra field name for the uid of who is requesting to install
     * the package.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_INSTALLER_UID
            = "android.content.pm.extra.VERIFICATION_INSTALLER_UID";

    /**
     * Extra field name for the package name of a package pending verification.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_PACKAGE_NAME
            = "android.content.pm.extra.VERIFICATION_PACKAGE_NAME";
    /**
     * Extra field name for the result of a verification, either
     * {@link #VERIFICATION_ALLOW}, or {@link #VERIFICATION_REJECT}.
     * Passed to package verifiers after a package is verified.
     */
    public static final String EXTRA_VERIFICATION_RESULT
            = "android.content.pm.extra.VERIFICATION_RESULT";

    /**
     * Extra field name for the version code of a package pending verification.
     * @deprecated Use {@link #EXTRA_VERIFICATION_LONG_VERSION_CODE} instead.
     * @hide
     */
    @Deprecated
    public static final String EXTRA_VERIFICATION_VERSION_CODE
            = "android.content.pm.extra.VERIFICATION_VERSION_CODE";

    /**
     * Extra field name for the long version code of a package pending verification.
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_LONG_VERSION_CODE =
            "android.content.pm.extra.VERIFICATION_LONG_VERSION_CODE";

    /**
     * Extra field name for the ID of a intent filter pending verification.
     * Passed to an intent filter verifier and is used to call back to
     * {@link #verifyIntentFilter}
     *
     * @hide
     */
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_ID
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_ID";

    /**
     * Extra field name for the scheme used for an intent filter pending verification. Passed to
     * an intent filter verifier and is used to construct the URI to verify against.
     *
     * Usually this is "https"
     *
     * @hide
     */
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_URI_SCHEME";

    /**
     * Extra field name for the host names to be used for an intent filter pending verification.
     * Passed to an intent filter verifier and is used to construct the URI to verify the
     * intent filter.
     *
     * This is a space delimited list of hosts.
     *
     * @hide
     */
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_HOSTS
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_HOSTS";

    /**
     * Extra field name for the package name to be used for an intent filter pending verification.
     * Passed to an intent filter verifier and is used to check the verification responses coming
     * from the hosts. Each host response will need to include the package name of APK containing
     * the intent filter.
     *
     * @hide
     */
    public static final String EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME
            = "android.content.pm.extra.INTENT_FILTER_VERIFICATION_PACKAGE_NAME";

    /**
     * The action used to request that the user approve a permission request
     * from the application.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";

    /**
     * The names of the requested permissions.
     * <p>
     * <strong>Type:</strong> String[]
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_PERMISSIONS_NAMES =
            "android.content.pm.extra.REQUEST_PERMISSIONS_NAMES";

    /**
     * The results from the permissions request.
     * <p>
     * <strong>Type:</strong> int[] of #PermissionResult
     * </p>
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_REQUEST_PERMISSIONS_RESULTS
            = "android.content.pm.extra.REQUEST_PERMISSIONS_RESULTS";

    /**
     * String extra for {@link PackageInstallObserver} in the 'extras' Bundle in case of
     * {@link #INSTALL_FAILED_DUPLICATE_PERMISSION}.  This extra names the package which provides
     * the existing definition for the permission.
     * @hide
     */
    public static final String EXTRA_FAILURE_EXISTING_PACKAGE
            = "android.content.pm.extra.FAILURE_EXISTING_PACKAGE";

    /**
     * String extra for {@link PackageInstallObserver} in the 'extras' Bundle in case of
     * {@link #INSTALL_FAILED_DUPLICATE_PERMISSION}.  This extra names the permission that is
     * being redundantly defined by the package being installed.
     * @hide
     */
    public static final String EXTRA_FAILURE_EXISTING_PERMISSION
            = "android.content.pm.extra.FAILURE_EXISTING_PERMISSION";

   /**
    * Permission flag: The permission is set in its current state
    * by the user and apps can still request it at runtime.
    *
    * @hide
    */
    @SystemApi
    @TestApi
    public static final int FLAG_PERMISSION_USER_SET = 1 << 0;

    /**
     * Permission flag: The permission is set in its current state
     * by the user and it is fixed, i.e. apps can no longer request
     * this permission.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_PERMISSION_USER_FIXED =  1 << 1;

    /**
     * Permission flag: The permission is set in its current state
     * by device policy and neither apps nor the user can change
     * its state.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_POLICY_FIXED =  1 << 2;

    /**
     * Permission flag: The permission is set in a granted state but
     * access to resources it guards is restricted by other means to
     * enable revoking a permission on legacy apps that do not support
     * runtime permissions. If this permission is upgraded to runtime
     * because the app was updated to support runtime permissions, the
     * the permission will be revoked in the upgrade process.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_PERMISSION_REVOKE_ON_UPGRADE =  1 << 3;

    /**
     * Permission flag: The permission is set in its current state
     * because the app is a component that is a part of the system.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_SYSTEM_FIXED =  1 << 4;

    /**
     * Permission flag: The permission is granted by default because it
     * enables app functionality that is expected to work out-of-the-box
     * for providing a smooth user experience. For example, the phone app
     * is expected to have the phone permission.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_GRANTED_BY_DEFAULT =  1 << 5;

    /**
     * Permission flag: The permission has to be reviewed before any of
     * the app components can run.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_PERMISSION_REVIEW_REQUIRED =  1 << 6;

    /**
     * Permission flag: The permission has not been explicitly requested by
     * the app but has been added automatically by the system. Revoke once
     * the app does explicitly request it.
     *
     * @hide
     */
    @TestApi
    public static final int FLAG_PERMISSION_REVOKE_WHEN_REQUESTED =  1 << 7;

    /**
     * Permission flag: The permission's usage should be made highly visible to the user
     * when granted.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED =  1 << 8;

    /**
     * Permission flag: The permission's usage should be made highly visible to the user
     * when denied.
     *
     * @hide
     */
    @SystemApi
    public static final int FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED =  1 << 9;

    /**
     * Mask for all permission flags present in Android P
     *
     * @deprecated This constant does not contain useful information and should never have been
     * exposed. When checking permission flags always flag each flag explicitly and ignore all
     * flags that do not matter for this particular code.
     *
     * @hide
     */
    @Deprecated
    @SystemApi
    public static final int MASK_PERMISSION_FLAGS = 0xFF;

    /**
     * Mask for all permission flags.
     *
     * @hide
     */
    public static final int MASK_PERMISSION_FLAGS_ALL = 0x3FF;

    /**
     * Injected activity in app that forwards user to setting activity of that app.
     *
     * @hide
     */
    public static final String APP_DETAILS_ACTIVITY_CLASS_NAME = AppDetailsActivity.class.getName();

    /**
     * This is a library that contains components apps can invoke. For
     * example, a services for apps to bind to, or standard chooser UI,
     * etc. This library is versioned and backwards compatible. Clients
     * should check its version via {@link android.ext.services.Version
     * #getVersionCode()} and avoid calling APIs added in later versions.
     *
     * @hide
     */
    @TestApi
    public static final String SYSTEM_SHARED_LIBRARY_SERVICES = "android.ext.services";

    /**
     * This is a library that contains components apps can dynamically
     * load. For example, new widgets, helper classes, etc. This library
     * is versioned and backwards compatible. Clients should check its
     * version via {@link android.ext.shared.Version#getVersionCode()}
     * and avoid calling APIs added in later versions.
     *
     * @hide
     */
    @TestApi
    public static final String SYSTEM_SHARED_LIBRARY_SHARED = "android.ext.shared";

    /**
     * Used when starting a process for an Activity.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_ACTIVITY = 0;

    /**
     * Used when starting a process for a Service.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_SERVICE = 1;

    /**
     * Used when moving a Service to the foreground.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE = 2;

    /**
     * Used when starting a process for a BroadcastReceiver.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_BROADCAST_RECEIVER = 3;

    /**
     * Used when starting a process for a ContentProvider.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_CONTENT_PROVIDER = 4;

    /**
     * Used when starting a process for a BroadcastReceiver.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_BACKUP = 5;

    /**
     * Used with Context.getClassLoader() across Android packages.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_CROSS_PACKAGE = 6;

    /**
     * Used when starting a package within a process for Instrumentation.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_INSTRUMENTATION = 7;

    /**
     * Total number of usage reasons.
     *
     * @hide
     */
    public static final int NOTIFY_PACKAGE_USE_REASONS_COUNT = 8;

    /**
     * Constant for specifying the highest installed package version code.
     */
    public static final int VERSION_CODE_HIGHEST = -1;

    /** {@hide} */
    public int getUserId() {
        return UserHandle.myUserId();
    }

    /**
     * Retrieve overall information about an application package that is
     * installed on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract PackageInfo getPackageInfo(String packageName, @PackageInfoFlags int flags)
            throws NameNotFoundException;

    /**
     * Retrieve overall information about an application package that is
     * installed on the system. This method can be used for retrieving
     * information about packages for which multiple versions can be installed
     * at the time. Currently only packages hosting static shared libraries can
     * have multiple installed versions. The method can also be used to get info
     * for a package that has a single version installed by passing
     * {@link #VERSION_CODE_HIGHEST} in the {@link VersionedPackage}
     * constructor.
     *
     * @param versionedPackage The versioned package for which to query.
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract PackageInfo getPackageInfo(VersionedPackage versionedPackage,
            @PackageInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve overall information about an application package that is
     * installed on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return A PackageInfo object containing information about the package. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if the package
     *         is not found in the list of installed applications, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @hide
     */
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @UnsupportedAppUsage
    public abstract PackageInfo getPackageInfoAsUser(String packageName,
            @PackageInfoFlags int flags, @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Map from the current package names in use on the device to whatever
     * the current canonical name of that package is.
     * @param names Array of current names to be mapped.
     * @return Returns an array of the same size as the original, containing
     * the canonical name for each package.
     */
    public abstract String[] currentToCanonicalPackageNames(String[] names);

    /**
     * Map from a packages canonical name to the current name in use on the device.
     * @param names Array of new names to be mapped.
     * @return Returns an array of the same size as the original, containing
     * the current name for each package.
     */
    public abstract String[] canonicalToCurrentPackageNames(String[] names);

    /**
     * Returns a "good" intent to launch a front-door activity in a package.
     * This is used, for example, to implement an "open" button when browsing
     * through packages.  The current implementation looks first for a main
     * activity in the category {@link Intent#CATEGORY_INFO}, and next for a
     * main activity in the category {@link Intent#CATEGORY_LAUNCHER}. Returns
     * <code>null</code> if neither are found.
     *
     * @param packageName The name of the package to inspect.
     *
     * @return A fully-qualified {@link Intent} that can be used to launch the
     * main activity in the package. Returns <code>null</code> if the package
     * does not contain such an activity, or if <em>packageName</em> is not
     * recognized.
     */
    public abstract @Nullable Intent getLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Return a "good" intent to launch a front-door Leanback activity in a
     * package, for use for example to implement an "open" button when browsing
     * through packages. The current implementation will look for a main
     * activity in the category {@link Intent#CATEGORY_LEANBACK_LAUNCHER}, or
     * return null if no main leanback activities are found.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns either a fully-qualified Intent that can be used to launch
     *         the main Leanback activity in the package, or null if the package
     *         does not contain such an activity.
     */
    public abstract @Nullable Intent getLeanbackLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Return a "good" intent to launch a front-door Car activity in a
     * package, for use for example to implement an "open" button when browsing
     * through packages. The current implementation will look for a main
     * activity in the category {@link Intent#CATEGORY_CAR_LAUNCHER}, or
     * return null if no main car activities are found.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns either a fully-qualified Intent that can be used to launch
     *         the main Car activity in the package, or null if the package
     *         does not contain such an activity.
     * @hide
     */
    public abstract @Nullable Intent getCarLaunchIntentForPackage(@NonNull String packageName);

    /**
     * Return an array of all of the POSIX secondary group IDs that have been
     * assigned to the given package.
     * <p>
     * Note that the same package may have different GIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an int array of the assigned GIDs, or null if there are
     *         none.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract int[] getPackageGids(@NonNull String packageName)
            throws NameNotFoundException;

    /**
     * Return an array of all of the POSIX secondary group IDs that have been
     * assigned to the given package.
     * <p>
     * Note that the same package may have different GIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an int array of the assigned gids, or null if there are
     *         none.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract int[] getPackageGids(String packageName, @PackageInfoFlags int flags)
            throws NameNotFoundException;

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if a package with the given name can not be
     *             found on the system.
     */
    public abstract int getPackageUid(String packageName, @PackageInfoFlags int flags)
            throws NameNotFoundException;

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param userId The user handle identifier to look up the package under.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if a package with the given name can not be
     *             found on the system.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract int getPackageUidAsUser(String packageName, @UserIdInt int userId)
            throws NameNotFoundException;

    /**
     * Return the UID associated with the given package name.
     * <p>
     * Note that the same package will have different UIDs under different
     * {@link UserHandle} on the same device.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param userId The user handle identifier to look up the package under.
     * @return Returns an integer UID who owns the given package name.
     * @throws NameNotFoundException if a package with the given name can not be
     *             found on the system.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract int getPackageUidAsUser(String packageName, @PackageInfoFlags int flags,
            @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular permission.
     *
     * @param name The fully qualified name (i.e. com.google.permission.LOGIN)
     *            of the permission you are interested in.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a {@link PermissionInfo} containing information about the
     *         permission.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract PermissionInfo getPermissionInfo(String name, @PermissionInfoFlags int flags)
            throws NameNotFoundException;

    /**
     * Query for all of the permissions associated with a particular group.
     *
     * @param group The fully qualified name (i.e. com.google.permission.LOGIN)
     *            of the permission group you are interested in. Use null to
     *            find all of the permissions not associated with a group.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a list of {@link PermissionInfo} containing information
     *         about all of the permissions in the given group.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract List<PermissionInfo> queryPermissionsByGroup(String group,
            @PermissionInfoFlags int flags) throws NameNotFoundException;

    /**
     * Returns true if some permissions are individually controlled.
     *
     * <p>The user usually grants and revokes permission-groups. If this option is set some
     * dangerous system permissions can be revoked/granted by the user separately from their group.
     *
     * @hide
     */
    @TestApi @SystemApi
    public abstract boolean arePermissionsIndividuallyControlled();

    /**
     * Returns true if wireless consent mode is enabled
     *
     * @hide
     */
    public abstract boolean isWirelessConsentModeEnabled();

    /**
     * Retrieve all of the information we know about a particular group of
     * permissions.
     *
     * @param name The fully qualified name (i.e.
     *            com.google.permission_group.APPS) of the permission you are
     *            interested in.
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a {@link PermissionGroupInfo} containing information
     *         about the permission.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract PermissionGroupInfo getPermissionGroupInfo(String name,
            @PermissionGroupInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the known permission groups in the system.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a list of {@link PermissionGroupInfo} containing
     *         information about all of the known permission groups.
     */
    public abstract List<PermissionGroupInfo> getAllPermissionGroups(
            @PermissionGroupInfoFlags int flags);

    /**
     * Retrieve all of the information we know about a particular
     * package/application.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of an
     *            application.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ApplicationInfo} containing information about the
     *         package. If flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if
     *         the package is not found in the list of installed applications,
     *         the application information is retrieved from the list of
     *         uninstalled applications (which includes installed applications
     *         as well as applications with data directory i.e. applications
     *         which had been deleted with {@code DONT_DELETE_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ApplicationInfo getApplicationInfo(String packageName,
            @ApplicationInfoFlags int flags) throws NameNotFoundException;

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract ApplicationInfo getApplicationInfoAsUser(String packageName,
            @ApplicationInfoFlags int flags, @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular
     * package/application, for a specific user.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of an
     *            application.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ApplicationInfo} containing information about the
     *         package. If flag {@code MATCH_UNINSTALLED_PACKAGES} is set and if
     *         the package is not found in the list of installed applications,
     *         the application information is retrieved from the list of
     *         uninstalled applications (which includes installed applications
     *         as well as applications with data directory i.e. applications
     *         which had been deleted with {@code DONT_DELETE_DATA} flag set).
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public ApplicationInfo getApplicationInfoAsUser(@NonNull String packageName,
            @ApplicationInfoFlags int flags, @NonNull UserHandle user)
            throws NameNotFoundException {
        return getApplicationInfoAsUser(packageName, flags, user.getIdentifier());
    }

    /**
     * Retrieve all of the information we know about a particular activity
     * class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.contacts/com.google.apps.contacts.
     *            ContactsList) of an Activity class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ActivityInfo} containing information about the
     *         activity.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ActivityInfo getActivityInfo(ComponentName component,
            @ComponentInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular receiver
     * class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.calendar/com.google.apps.calendar.
     *            CalendarAlarm) of a Receiver class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link ActivityInfo} containing information about the
     *         receiver.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ActivityInfo getReceiverInfo(ComponentName component,
            @ComponentInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular service class.
     *
     * @param component The full component name (i.e.
     *            com.google.apps.media/com.google.apps.media.
     *            BackgroundPlayback) of a Service class.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ServiceInfo} object containing information about the
     *         service.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ServiceInfo getServiceInfo(ComponentName component,
            @ComponentInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular content
     * provider class.
     *
     * @param component The full component name (i.e.
     *            com.google.providers.media/com.google.providers.media.
     *            MediaProvider) of a ContentProvider class.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract ProviderInfo getProviderInfo(ComponentName component,
            @ComponentInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve information for a particular module.
     *
     * @param packageName The name of the module.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ModuleInfo} object containing information about the
     *         module.
     * @throws NameNotFoundException if a module with the given name cannot be
     *             found on the system.
     */
    public ModuleInfo getModuleInfo(String packageName, @ModuleInfoFlags int flags)
            throws NameNotFoundException {
        throw new UnsupportedOperationException(
                "getModuleInfo not implemented in subclass");
    }

    /**
     * Return a List of all modules that are installed.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link List} of {@link ModuleInfo} objects, one for each installed
     *         module, containing information about the module. In the unlikely case
     *         there are no installed modules, an empty list is returned.
     */
    public @NonNull List<ModuleInfo> getInstalledModules(@ModuleInfoFlags int flags) {
        throw new UnsupportedOperationException(
                "getInstalledModules not implemented in subclass");
    }

    /**
     * Return a List of all packages that are installed for the current user.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each installed package,
     *         containing information about the package. In the unlikely case
     *         there are no installed packages, an empty list is returned. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     */
    public abstract List<PackageInfo> getInstalledPackages(@PackageInfoFlags int flags);

    /**
     * Return a List of all installed packages that are currently holding any of
     * the given permissions.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each installed package
     *         that holds any of the permissions that were provided, containing
     *         information about the package. If no installed packages hold any
     *         of the permissions, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     */
    public abstract List<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, @PackageInfoFlags int flags);

    /**
     * Return a List of all packages that are installed on the device, for a
     * specific user.
     *
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user for whom the installed packages are to be listed
     * @return A List of PackageInfo objects, one for each installed package,
     *         containing information about the package. In the unlikely case
     *         there are no installed packages, an empty list is returned. If
     *         flag {@code MATCH_UNINSTALLED_PACKAGES} is set, the package
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract List<PackageInfo> getInstalledPackagesAsUser(@PackageInfoFlags int flags,
            @UserIdInt int userId);

    /**
     * Check whether a particular package has been granted a particular
     * permission.
     *
     * @param permName The name of the permission you are checking for.
     * @param pkgName The name of the package you are checking against.
     *
     * @return If the package has the permission, PERMISSION_GRANTED is
     * returned.  If it does not have the permission, PERMISSION_DENIED
     * is returned.
     *
     * @see #PERMISSION_GRANTED
     * @see #PERMISSION_DENIED
     */
    @CheckResult
    public abstract @PermissionResult int checkPermission(String permName, String pkgName);

    /**
     * Checks whether a particular permissions has been revoked for a
     * package by policy. Typically the device owner or the profile owner
     * may apply such a policy. The user cannot grant policy revoked
     * permissions, hence the only way for an app to get such a permission
     * is by a policy change.
     *
     * @param permName The name of the permission you are checking for.
     * @param pkgName The name of the package you are checking against.
     *
     * @return Whether the permission is restricted by policy.
     */
    @CheckResult
    public abstract boolean isPermissionRevokedByPolicy(@NonNull String permName,
            @NonNull String pkgName);

    /**
     * Gets the package name of the component controlling runtime permissions.
     *
     * @return The package name.
     *
     * @hide
     */
    @TestApi
    public abstract String getPermissionControllerPackageName();

    /**
     * Add a new dynamic permission to the system.  For this to work, your
     * package must have defined a permission tree through the
     * {@link android.R.styleable#AndroidManifestPermissionTree
     * &lt;permission-tree&gt;} tag in its manifest.  A package can only add
     * permissions to trees that were defined by either its own package or
     * another with the same user id; a permission is in a tree if it
     * matches the name of the permission tree + ".": for example,
     * "com.foo.bar" is a member of the permission tree "com.foo".
     *
     * <p>It is good to make your permission tree name descriptive, because you
     * are taking possession of that entire set of permission names.  Thus, it
     * must be under a domain you control, with a suffix that will not match
     * any normal permissions that may be declared in any applications that
     * are part of that domain.
     *
     * <p>New permissions must be added before
     * any .apks are installed that use those permissions.  Permissions you
     * add through this method are remembered across reboots of the device.
     * If the given permission already exists, the info you supply here
     * will be used to update it.
     *
     * @param info Description of the permission to be added.
     *
     * @return Returns true if a new permission was created, false if an
     * existing one was updated.
     *
     * @throws SecurityException if you are not allowed to add the
     * given permission name.
     *
     * @see #removePermission(String)
     */
    public abstract boolean addPermission(PermissionInfo info);

    /**
     * Like {@link #addPermission(PermissionInfo)} but asynchronously
     * persists the package manager state after returning from the call,
     * allowing it to return quicker and batch a series of adds at the
     * expense of no guarantee the added permission will be retained if
     * the device is rebooted before it is written.
     */
    public abstract boolean addPermissionAsync(PermissionInfo info);

    /**
     * Removes a permission that was previously added with
     * {@link #addPermission(PermissionInfo)}.  The same ownership rules apply
     * -- you are only allowed to remove permissions that you are allowed
     * to add.
     *
     * @param name The name of the permission to remove.
     *
     * @throws SecurityException if you are not allowed to remove the
     * given permission name.
     *
     * @see #addPermission(PermissionInfo)
     */
    public abstract void removePermission(String name);

    /**
     * Permission flags set when granting or revoking a permission.
     *
     * @hide
     */
    @SystemApi
    @IntDef(prefix = { "FLAG_PERMISSION_" }, value = {
            FLAG_PERMISSION_USER_SET,
            FLAG_PERMISSION_USER_FIXED,
            FLAG_PERMISSION_POLICY_FIXED,
            FLAG_PERMISSION_REVOKE_ON_UPGRADE,
            FLAG_PERMISSION_SYSTEM_FIXED,
            FLAG_PERMISSION_GRANTED_BY_DEFAULT,
            FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED,
            FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED,
            /*
            FLAG_PERMISSION_REVOKE_WHEN_REQUESED
            */
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionFlags {}

    /**
     * Grant a runtime permission to an application which the application does not
     * already have. The permission must have been requested by the application.
     * If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is
     * invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * android.permission.GRANT_RUNTIME_PERMISSIONS and if the user id is
     * not the current user android.permission.INTERACT_ACROSS_USERS_FULL.
     * </p>
     *
     * @param packageName The package to which to grant the permission.
     * @param permissionName The permission name to grant.
     * @param user The user for which to grant the permission.
     *
     * @see #revokeRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS)
    public abstract void grantRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName, @NonNull UserHandle user);

    /**
     * Revoke a runtime permission that was previously granted by {@link
     * #grantRuntimePermission(String, String, android.os.UserHandle)}. The
     * permission must have been requested by and granted to the application.
     * If the application is not allowed to hold the permission, a {@link
     * java.lang.SecurityException} is thrown. If the package or permission is
     * invalid, a {@link java.lang.IllegalArgumentException} is thrown.
     * <p>
     * <strong>Note: </strong>Using this API requires holding
     * android.permission.REVOKE_RUNTIME_PERMISSIONS and if the user id is
     * not the current user android.permission.INTERACT_ACROSS_USERS_FULL.
     * </p>
     *
     * @param packageName The package from which to revoke the permission.
     * @param permissionName The permission name to revoke.
     * @param user The user for which to revoke the permission.
     *
     * @see #grantRuntimePermission(String, String, android.os.UserHandle)
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS)
    public abstract void revokeRuntimePermission(@NonNull String packageName,
            @NonNull String permissionName, @NonNull UserHandle user);

    /**
     * Gets the state flags associated with a permission.
     *
     * @param permissionName The permission for which to get the flags.
     * @param packageName The package name for which to get the flags.
     * @param user The user for which to get permission flags.
     * @return The permission flags.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            android.Manifest.permission.GET_RUNTIME_PERMISSIONS
    })
    public abstract @PermissionFlags int getPermissionFlags(String permissionName,
            String packageName, @NonNull UserHandle user);

    /**
     * Updates the flags associated with a permission by replacing the flags in
     * the specified mask with the provided flag values.
     *
     * @param permissionName The permission for which to update the flags.
     * @param packageName The package name for which to update the flags.
     * @param flagMask The flags which to replace.
     * @param flagValues The flags with which to replace.
     * @param user The user for which to update the permission flags.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {
            android.Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            android.Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
    })
    public abstract void updatePermissionFlags(String permissionName,
            String packageName, @PermissionFlags int flagMask, @PermissionFlags int flagValues,
            @NonNull UserHandle user);

    /**
     * Gets whether you should show UI with rationale for requesting a permission.
     * You should do this only if you do not have the permission and the context in
     * which the permission is requested does not clearly communicate to the user
     * what would be the benefit from grating this permission.
     *
     * @param permission A permission your app wants to request.
     * @return Whether you can show permission rationale UI.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean shouldShowRequestPermissionRationale(String permission);

    /**
     * Returns an {@link android.content.Intent} suitable for passing to
     * {@link android.app.Activity#startActivityForResult(android.content.Intent, int)}
     * which prompts the user to grant permissions to this application.
     *
     * @throws NullPointerException if {@code permissions} is {@code null} or empty.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public Intent buildRequestPermissionsIntent(@NonNull String[] permissions) {
        if (ArrayUtils.isEmpty(permissions)) {
           throw new IllegalArgumentException("permission cannot be null or empty");
        }
        Intent intent = new Intent(ACTION_REQUEST_PERMISSIONS);
        intent.putExtra(EXTRA_REQUEST_PERMISSIONS_NAMES, permissions);
        intent.setPackage(getPermissionControllerPackageName());
        return intent;
    }

    /**
     * Compare the signatures of two packages to determine if the same
     * signature appears in both of them.  If they do contain the same
     * signature, then they are allowed special privileges when working
     * with each other: they can share the same user-id, run instrumentation
     * against each other, etc.
     *
     * @param pkg1 First package name whose signature will be compared.
     * @param pkg2 Second package name whose signature will be compared.
     *
     * @return Returns an integer indicating whether all signatures on the
     * two packages match. The value is >= 0 ({@link #SIGNATURE_MATCH}) if
     * all signatures match or < 0 if there is not a match ({@link
     * #SIGNATURE_NO_MATCH} or {@link #SIGNATURE_UNKNOWN_PACKAGE}).
     *
     * @see #checkSignatures(int, int)
     */
    @CheckResult
    public abstract @SignatureResult int checkSignatures(String pkg1, String pkg2);

    /**
     * Like {@link #checkSignatures(String, String)}, but takes UIDs of
     * the two packages to be checked.  This can be useful, for example,
     * when doing the check in an IPC, where the UID is the only identity
     * available.  It is functionally identical to determining the package
     * associated with the UIDs and checking their signatures.
     *
     * @param uid1 First UID whose signature will be compared.
     * @param uid2 Second UID whose signature will be compared.
     *
     * @return Returns an integer indicating whether all signatures on the
     * two packages match. The value is >= 0 ({@link #SIGNATURE_MATCH}) if
     * all signatures match or < 0 if there is not a match ({@link
     * #SIGNATURE_NO_MATCH} or {@link #SIGNATURE_UNKNOWN_PACKAGE}).
     *
     * @see #checkSignatures(String, String)
     */
    @CheckResult
    public abstract @SignatureResult int checkSignatures(int uid1, int uid2);

    /**
     * Retrieve the names of all packages that are associated with a particular
     * user id.  In most cases, this will be a single package name, the package
     * that has been assigned that user id.  Where there are multiple packages
     * sharing the same user id through the "sharedUserId" mechanism, all
     * packages with that id will be returned.
     *
     * @param uid The user id for which you would like to retrieve the
     * associated packages.
     *
     * @return Returns an array of one or more packages assigned to the user
     * id, or null if there are no known packages with the given id.
     */
    public abstract @Nullable String[] getPackagesForUid(int uid);

    /**
     * Retrieve the official name associated with a uid. This name is
     * guaranteed to never change, though it is possible for the underlying
     * uid to be changed.  That is, if you are storing information about
     * uids in persistent storage, you should use the string returned
     * by this function instead of the raw uid.
     *
     * @param uid The uid for which you would like to retrieve a name.
     * @return Returns a unique name for the given uid, or null if the
     * uid is not currently assigned.
     */
    public abstract @Nullable String getNameForUid(int uid);

    /**
     * Retrieves the official names associated with each given uid.
     * @see #getNameForUid(int)
     *
     * @hide
     */
    @TestApi
    public abstract @Nullable String[] getNamesForUids(int[] uids);

    /**
     * Return the user id associated with a shared user name. Multiple
     * applications can specify a shared user name in their manifest and thus
     * end up using a common uid. This might be used for new applications
     * that use an existing shared user name and need to know the uid of the
     * shared user.
     *
     * @param sharedUserName The shared user name whose uid is to be retrieved.
     * @return Returns the UID associated with the shared user.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract int getUidForSharedUser(String sharedUserName)
            throws NameNotFoundException;

    /**
     * Return a List of all application packages that are installed for the
     * current user. If flag GET_UNINSTALLED_PACKAGES has been set, a list of all
     * applications including those deleted with {@code DONT_DELETE_DATA}
     * (partially installed apps with data directory) will be returned.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of ApplicationInfo objects, one for each installed
     *         application. In the unlikely case there are no installed
     *         packages, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the application
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     */
    public abstract List<ApplicationInfo> getInstalledApplications(@ApplicationInfoFlags int flags);

    /**
     * Return a List of all application packages that are installed on the
     * device, for a specific user. If flag GET_UNINSTALLED_PACKAGES has been
     * set, a list of all applications including those deleted with
     * {@code DONT_DELETE_DATA} (partially installed apps with data directory)
     * will be returned.
     *
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user for whom the installed applications are to be
     *            listed
     * @return A List of ApplicationInfo objects, one for each installed
     *         application. In the unlikely case there are no installed
     *         packages, an empty list is returned. If flag
     *         {@code MATCH_UNINSTALLED_PACKAGES} is set, the application
     *         information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @hide
     */
    @TestApi
    public abstract List<ApplicationInfo> getInstalledApplicationsAsUser(
            @ApplicationInfoFlags int flags, @UserIdInt int userId);

    /**
     * Gets the instant applications the user recently used.
     *
     * @return The instant app list.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_INSTANT_APPS)
    public abstract @NonNull List<InstantAppInfo> getInstantApps();

    /**
     * Gets the icon for an instant application.
     *
     * @param packageName The app package name.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.ACCESS_INSTANT_APPS)
    public abstract @Nullable Drawable getInstantAppIcon(String packageName);

    /**
     * Gets whether this application is an instant app.
     *
     * @return Whether caller is an instant app.
     *
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #getInstantAppCookieMaxBytes()
     */
    public abstract boolean isInstantApp();

    /**
     * Gets whether the given package is an instant app.
     *
     * @param packageName The package to check
     * @return Whether the given package is an instant app.
     *
     * @see #isInstantApp()
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #getInstantAppCookieMaxBytes()
     * @see #clearInstantAppCookie()
     */
    public abstract boolean isInstantApp(String packageName);

    /**
     * Gets the maximum size in bytes of the cookie data an instant app
     * can store on the device.
     *
     * @return The max cookie size in bytes.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     */
    public abstract int getInstantAppCookieMaxBytes();

    /**
     * deprecated
     * @hide
     */
    public abstract int getInstantAppCookieMaxSize();

    /**
     * Gets the instant application cookie for this app. Non
     * instant apps and apps that were instant but were upgraded
     * to normal apps can still access this API. For instant apps
     * this cookie is cached for some time after uninstall while for
     * normal apps the cookie is deleted after the app is uninstalled.
     * The cookie is always present while the app is installed.
     *
     * @return The cookie.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #updateInstantAppCookie(byte[])
     * @see #getInstantAppCookieMaxBytes()
     * @see #clearInstantAppCookie()
     */
    public abstract @NonNull byte[] getInstantAppCookie();

    /**
     * Clears the instant application cookie for the calling app.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #getInstantAppCookieMaxBytes()
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     */
    public abstract void clearInstantAppCookie();

    /**
     * Updates the instant application cookie for the calling app. Non
     * instant apps and apps that were instant but were upgraded
     * to normal apps can still access this API. For instant apps
     * this cookie is cached for some time after uninstall while for
     * normal apps the cookie is deleted after the app is uninstalled.
     * The cookie is always present while the app is installed. The
     * cookie size is limited by {@link #getInstantAppCookieMaxBytes()}.
     * Passing <code>null</code> or an empty array clears the cookie.
     * </p>
     *
     * @param cookie The cookie data.
     *
     * @see #isInstantApp()
     * @see #isInstantApp(String)
     * @see #getInstantAppCookieMaxBytes()
     * @see #getInstantAppCookie()
     * @see #clearInstantAppCookie()
     *
     * @throws IllegalArgumentException if the array exceeds max cookie size.
     */
    public abstract void updateInstantAppCookie(@Nullable byte[] cookie);

    /**
     * @removed
     */
    public abstract boolean setInstantAppCookie(@Nullable byte[] cookie);

    /**
     * Get a list of shared libraries that are available on the
     * system.
     *
     * @return An array of shared library names that are
     * available on the system, or null if none are installed.
     *
     */
    public abstract String[] getSystemSharedLibraryNames();

    /**
     * Get a list of shared libraries on the device.
     *
     * @param flags To filter the libraries to return.
     * @return The shared library list.
     *
     * @see #MATCH_UNINSTALLED_PACKAGES
     */
    public abstract @NonNull List<SharedLibraryInfo> getSharedLibraries(
            @InstallFlags int flags);

    /**
     * Get a list of shared libraries on the device.
     *
     * @param flags To filter the libraries to return.
     * @param userId The user to query for.
     * @return The shared library list.
     *
     * @see #MATCH_FACTORY_ONLY
     * @see #MATCH_KNOWN_PACKAGES
     * @see #MATCH_ANY_USER
     * @see #MATCH_UNINSTALLED_PACKAGES
     *
     * @hide
     */
    public abstract @NonNull List<SharedLibraryInfo> getSharedLibrariesAsUser(
            @InstallFlags int flags, @UserIdInt int userId);

    /**
     * Get the name of the package hosting the services shared library.
     *
     * @return The library host package.
     *
     * @hide
     */
    @TestApi
    public abstract @NonNull String getServicesSystemSharedLibraryPackageName();

    /**
     * Get the name of the package hosting the shared components shared library.
     *
     * @return The library host package.
     *
     * @hide
     */
    @TestApi
    public abstract @NonNull String getSharedSystemSharedLibraryPackageName();

    /**
     * Returns the names of the packages that have been changed
     * [eg. added, removed or updated] since the given sequence
     * number.
     * <p>If no packages have been changed, returns <code>null</code>.
     * <p>The sequence number starts at <code>0</code> and is
     * reset every boot.
     * @param sequenceNumber The first sequence number for which to retrieve package changes.
     * @see android.provider.Settings.Global#BOOT_COUNT
     */
    public abstract @Nullable ChangedPackages getChangedPackages(
            @IntRange(from=0) int sequenceNumber);

    /**
     * Get a list of features that are available on the
     * system.
     *
     * @return An array of FeatureInfo classes describing the features
     * that are available on the system, or null if there are none(!!).
     */
    public abstract FeatureInfo[] getSystemAvailableFeatures();

    /**
     * Check whether the given feature name is one of the available features as
     * returned by {@link #getSystemAvailableFeatures()}. This tests for the
     * presence of <em>any</em> version of the given feature name; use
     * {@link #hasSystemFeature(String, int)} to check for a minimum version.
     *
     * @return Returns true if the devices supports the feature, else false.
     */
    public abstract boolean hasSystemFeature(String name);

    /**
     * Check whether the given feature name and version is one of the available
     * features as returned by {@link #getSystemAvailableFeatures()}. Since
     * features are defined to always be backwards compatible, this returns true
     * if the available feature version is greater than or equal to the
     * requested version.
     *
     * @return Returns true if the devices supports the feature, else false.
     */
    public abstract boolean hasSystemFeature(String name, int version);

    /**
     * Determine the best action to perform for a given Intent. This is how
     * {@link Intent#resolveActivity} finds an activity if a class has not been
     * explicitly specified.
     * <p>
     * <em>Note:</em> if using an implicit Intent (without an explicit
     * ComponentName specified), be sure to consider whether to set the
     * {@link #MATCH_DEFAULT_ONLY} only flag. You need to do so to resolve the
     * activity in the same way that
     * {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.
     * </p>
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @return Returns a ResolveInfo object containing the final activity intent
     *         that was determined to be the best action. Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo object
     *         containing something else, such as the activity resolver.
     */
    public abstract ResolveInfo resolveActivity(Intent intent, @ResolveInfoFlags int flags);

    /**
     * Determine the best action to perform for a given Intent for a given user.
     * This is how {@link Intent#resolveActivity} finds an activity if a class
     * has not been explicitly specified.
     * <p>
     * <em>Note:</em> if using an implicit Intent (without an explicit
     * ComponentName specified), be sure to consider whether to set the
     * {@link #MATCH_DEFAULT_ONLY} only flag. You need to do so to resolve the
     * activity in the same way that
     * {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.
     * </p>
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @param userId The user id.
     * @return Returns a ResolveInfo object containing the final activity intent
     *         that was determined to be the best action. Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo object
     *         containing something else, such as the activity resolver.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract ResolveInfo resolveActivityAsUser(Intent intent, @ResolveInfoFlags int flags,
            @UserIdInt int userId);

    /**
     * Retrieve all activities that can be performed for the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     */
    public abstract List<ResolveInfo> queryIntentActivities(Intent intent,
            @ResolveInfoFlags int flags);

    /**
     * Retrieve all activities that can be performed for the given intent, for a
     * specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
            @ResolveInfoFlags int flags, @UserIdInt int userId);

    /**
     * Retrieve all activities that can be performed for the given intent, for a
     * specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}. Or, set
     *            {@link #MATCH_ALL} to prevent any filtering of the results.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveActivity}. If there are no matching activities, an
     *         empty list is returned.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentActivitiesAsUser(@NonNull Intent intent,
            @ResolveInfoFlags int flags, @NonNull UserHandle user) {
        return queryIntentActivitiesAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * Retrieve a set of activities that should be presented to the user as
     * similar options. This is like {@link #queryIntentActivities}, except it
     * also allows you to supply a list of more explicit Intents that you would
     * like to resolve to particular options, and takes care of returning the
     * final ResolveInfo list in a reasonable order, with no duplicates, based
     * on those inputs.
     *
     * @param caller The class name of the activity that is making the request.
     *            This activity will never appear in the output list. Can be
     *            null.
     * @param specifics An array of Intents that should be resolved to the first
     *            specific results. Can be null.
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned. The
     *            most important is {@link #MATCH_DEFAULT_ONLY}, to limit the
     *            resolution to only those activities that support the
     *            {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching activity. The list is ordered first by all of the
     *         intents resolved in <var>specifics</var> and then any additional
     *         activities that can handle <var>intent</var> but did not get
     *         included by one of the <var>specifics</var> intents. If there are
     *         no matching activities, an empty list is returned.
     */
    public abstract List<ResolveInfo> queryIntentActivityOptions(@Nullable ComponentName caller,
            @Nullable Intent[] specifics, Intent intent, @ResolveInfoFlags int flags);

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching receiver, ordered from best to worst. If there are
     *         no matching receivers, an empty list or null is returned.
     */
    public abstract List<ResolveInfo> queryBroadcastReceivers(Intent intent,
            @ResolveInfoFlags int flags);

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent,
     * for a specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags to modify the data returned.
     * @param userHandle UserHandle of the user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching receiver, ordered from best to worst. If there are
     *         no matching receivers, an empty list or null is returned.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    public List<ResolveInfo> queryBroadcastReceiversAsUser(Intent intent,
            @ResolveInfoFlags int flags, UserHandle userHandle) {
        return queryBroadcastReceiversAsUser(intent, flags, userHandle.getIdentifier());
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryBroadcastReceiversAsUser(Intent intent,
            @ResolveInfoFlags int flags, @UserIdInt int userId);


    /** {@hide} */
    @Deprecated
    @UnsupportedAppUsage
    public List<ResolveInfo> queryBroadcastReceivers(Intent intent,
            @ResolveInfoFlags int flags, @UserIdInt int userId) {
        final String msg = "Shame on you for calling the hidden API "
                + "queryBroadcastReceivers(). Shame!";
        if (VMRuntime.getRuntime().getTargetSdkVersion() >= Build.VERSION_CODES.O) {
            throw new UnsupportedOperationException(msg);
        } else {
            Log.d(TAG, msg);
            return queryBroadcastReceiversAsUser(intent, flags, userId);
        }
    }

    /**
     * Determine the best service to handle for a given Intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a ResolveInfo object containing the final service intent
     *         that was determined to be the best action. Returns null if no
     *         matching service was found.
     */
    public abstract ResolveInfo resolveService(Intent intent, @ResolveInfoFlags int flags);

    /**
     * @hide
     */
    public abstract ResolveInfo resolveServiceAsUser(Intent intent, @ResolveInfoFlags int flags,
            @UserIdInt int userId);

    /**
     * Retrieve all services that can match the given intent.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     */
    public abstract List<ResolveInfo> queryIntentServices(Intent intent,
            @ResolveInfoFlags int flags);

    /**
     * Retrieve all services that can match the given intent for a given user.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentServicesAsUser(Intent intent,
            @ResolveInfoFlags int flags, @UserIdInt int userId);

    /**
     * Retrieve all services that can match the given intent for a given user.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags to modify the data returned.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching service, ordered from best to worst. In other
     *         words, the first item is what would be returned by
     *         {@link #resolveService}. If there are no matching services, an
     *         empty list or null is returned.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentServicesAsUser(@NonNull Intent intent,
            @ResolveInfoFlags int flags, @NonNull UserHandle user) {
        return queryIntentServicesAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract List<ResolveInfo> queryIntentContentProvidersAsUser(
            Intent intent, @ResolveInfoFlags int flags, @UserIdInt int userId);

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @param user The user being queried.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     * @hide
     */
    @NonNull
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS)
    @SystemApi
    public List<ResolveInfo> queryIntentContentProvidersAsUser(@NonNull Intent intent,
            @ResolveInfoFlags int flags, @NonNull UserHandle user) {
        return queryIntentContentProvidersAsUser(intent, flags, user.getIdentifier());
    }

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags to modify the data returned.
     * @return Returns a List of ResolveInfo objects containing one entry for
     *         each matching provider, ordered from best to worst. If there are
     *         no matching services, an empty list or null is returned.
     */
    public abstract List<ResolveInfo> queryIntentContentProviders(Intent intent,
            @ResolveInfoFlags int flags);

    /**
     * Find a single content provider by its authority.
     * <p>
     * Example:<p>
     * <pre>
     * Uri uri = Uri.parse("content://com.example.app.provider/table1");
     * ProviderInfo info = packageManager.resolveContentProvider(uri.getAuthority(), flags);
     * </pre>
     *
     * @param authority The authority of the provider to find.
     * @param flags Additional option flags to modify the data returned.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider. If a provider was not found, returns null.
     */
    public abstract ProviderInfo resolveContentProvider(String authority,
            @ComponentInfoFlags int flags);

    /**
     * Find a single content provider by its base path name.
     *
     * @param name The name of the provider to find.
     * @param flags Additional option flags to modify the data returned.
     * @param userId The user id.
     * @return A {@link ProviderInfo} object containing information about the
     *         provider. If a provider was not found, returns null.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract ProviderInfo resolveContentProviderAsUser(String name,
            @ComponentInfoFlags int flags, @UserIdInt int userId);

    /**
     * Retrieve content provider information.
     * <p>
     * <em>Note: unlike most other methods, an empty result set is indicated
     * by a null return instead of an empty list.</em>
     *
     * @param processName If non-null, limits the returned providers to only
     *            those that are hosted by the given process. If null, all
     *            content providers are returned.
     * @param uid If <var>processName</var> is non-null, this is the required
     *            uid owning the requested content providers.
     * @param flags Additional option flags to modify the data returned.
     * @return A list of {@link ProviderInfo} objects containing one entry for
     *         each provider either matching <var>processName</var> or, if
     *         <var>processName</var> is null, all known content providers.
     *         <em>If there are no matching providers, null is returned.</em>
     */
    public abstract List<ProviderInfo> queryContentProviders(
            String processName, int uid, @ComponentInfoFlags int flags);

    /**
     * Same as {@link #queryContentProviders}, except when {@code metaDataKey} is not null,
     * it only returns providers which have metadata with the {@code metaDataKey} key.
     *
     * <p>DO NOT USE the {@code metaDataKey} parameter, unless you're the contacts provider.
     * You really shouldn't need it.  Other apps should use {@link #queryIntentContentProviders}
     * instead.
     *
     * <p>The {@code metaDataKey} parameter was added to allow the contacts provider to quickly
     * scan the GAL providers on the device.  Unfortunately the discovery protocol used metadata
     * to mark GAL providers, rather than intent filters, so we can't use
     * {@link #queryIntentContentProviders} for that.
     *
     * @hide
     */
    public List<ProviderInfo> queryContentProviders(
            String processName, int uid, @ComponentInfoFlags int flags, String metaDataKey) {
        // Provide the default implementation for mocks.
        return queryContentProviders(processName, uid, flags);
    }

    /**
     * Retrieve all of the information we know about a particular
     * instrumentation class.
     *
     * @param className The full name (i.e.
     *            com.google.apps.contacts.InstrumentList) of an Instrumentation
     *            class.
     * @param flags Additional option flags to modify the data returned.
     * @return An {@link InstrumentationInfo} object containing information
     *         about the instrumentation.
     * @throws NameNotFoundException if a package with the given name cannot be
     *             found on the system.
     */
    public abstract InstrumentationInfo getInstrumentationInfo(ComponentName className,
            @InstrumentationInfoFlags int flags) throws NameNotFoundException;

    /**
     * Retrieve information about available instrumentation code. May be used to
     * retrieve either all instrumentation code, or only the code targeting a
     * particular package.
     *
     * @param targetPackage If null, all instrumentation is returned; only the
     *            instrumentation targeting this package name is returned.
     * @param flags Additional option flags to modify the data returned.
     * @return A list of {@link InstrumentationInfo} objects containing one
     *         entry for each matching instrumentation. If there are no
     *         instrumentation available, returns an empty list.
     */
    public abstract List<InstrumentationInfo> queryInstrumentation(String targetPackage,
            @InstrumentationInfoFlags int flags);

    /**
     * Retrieve an image from a package.  This is a low-level API used by
     * the various package manager info structures (such as
     * {@link ComponentInfo} to implement retrieval of their associated
     * icon.
     *
     * @param packageName The name of the package that this icon is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired image.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns a Drawable holding the requested image.  Returns null if
     * an image could not be found for any reason.
     */
    public abstract Drawable getDrawable(String packageName, @DrawableRes int resid,
            ApplicationInfo appInfo);

    /**
     * Retrieve the icon associated with an activity.  Given the full name of
     * an activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadIcon ComponentInfo.loadIcon()} to return its icon.
     * If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose icon is to be retrieved.
     *
     * @return Returns the image of the icon, or the default activity icon if
     * it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for the given
     * activity could not be loaded.
     *
     * @see #getActivityIcon(Intent)
     */
    public abstract Drawable getActivityIcon(ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the icon associated with an Intent.  If intent.getClassName() is
     * set, this simply returns the result of
     * getActivityIcon(intent.getClassName()).  Otherwise it resolves the intent's
     * component and returns the icon associated with the resolved component.
     * If intent.getClassName() cannot be found or the Intent cannot be resolved
     * to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve an icon.
     *
     * @return Returns the image of the icon, or the default activity icon if
     * it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for application
     * matching the given intent could not be loaded.
     *
     * @see #getActivityIcon(ComponentName)
     */
    public abstract Drawable getActivityIcon(Intent intent)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an activity. Given the full name of
     * an activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadIcon ComponentInfo.loadIcon()} to return its
     * banner. If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose banner is to be retrieved.
     * @return Returns the image of the banner, or null if the activity has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             activity could not be loaded.
     * @see #getActivityBanner(Intent)
     */
    public abstract Drawable getActivityBanner(ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an Intent. If intent.getClassName()
     * is set, this simply returns the result of
     * getActivityBanner(intent.getClassName()). Otherwise it resolves the
     * intent's component and returns the banner associated with the resolved
     * component. If intent.getClassName() cannot be found or the Intent cannot
     * be resolved to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve a banner.
     * @return Returns the image of the banner, or null if the activity has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for application
     *             matching the given intent could not be loaded.
     * @see #getActivityBanner(ComponentName)
     */
    public abstract Drawable getActivityBanner(Intent intent)
            throws NameNotFoundException;

    /**
     * Return the generic icon for an activity that is used when no specific
     * icon is defined.
     *
     * @return Drawable Image of the icon.
     */
    public abstract Drawable getDefaultActivityIcon();

    /**
     * Retrieve the icon associated with an application.  If it has not defined
     * an icon, the default app icon is returned.  Does not return null.
     *
     * @param info Information about application being queried.
     *
     * @return Returns the image of the icon, or the default application icon
     * if it could not be found.
     *
     * @see #getApplicationIcon(String)
     */
    public abstract Drawable getApplicationIcon(ApplicationInfo info);

    /**
     * Retrieve the icon associated with an application.  Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationIcon() to return its icon. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application icon is to be
     *                    retrieved.
     *
     * @return Returns the image of the icon, or the default application icon
     * if it could not be found.  Does not return null.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getApplicationIcon(ApplicationInfo)
     */
    public abstract Drawable getApplicationIcon(String packageName)
            throws NameNotFoundException;

    /**
     * Retrieve the banner associated with an application.
     *
     * @param info Information about application being queried.
     * @return Returns the image of the banner or null if the application has no
     *         banner specified.
     * @see #getApplicationBanner(String)
     */
    public abstract Drawable getApplicationBanner(ApplicationInfo info);

    /**
     * Retrieve the banner associated with an application. Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationIcon() to return its banner. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application banner is to be
     *            retrieved.
     * @return Returns the image of the banner or null if the application has no
     *         banner specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             application could not be loaded.
     * @see #getApplicationBanner(ApplicationInfo)
     */
    public abstract Drawable getApplicationBanner(String packageName)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an activity. Given the full name of an
     * activity, retrieves the information about it and calls
     * {@link ComponentInfo#loadLogo ComponentInfo.loadLogo()} to return its
     * logo. If the activity cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose logo is to be retrieved.
     * @return Returns the image of the logo or null if the activity has no logo
     *         specified.
     * @throws NameNotFoundException Thrown if the resources for the given
     *             activity could not be loaded.
     * @see #getActivityLogo(Intent)
     */
    public abstract Drawable getActivityLogo(ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an Intent.  If intent.getClassName() is
     * set, this simply returns the result of
     * getActivityLogo(intent.getClassName()).  Otherwise it resolves the intent's
     * component and returns the logo associated with the resolved component.
     * If intent.getClassName() cannot be found or the Intent cannot be resolved
     * to a component, NameNotFoundException is thrown.
     *
     * @param intent The intent for which you would like to retrieve a logo.
     *
     * @return Returns the image of the logo, or null if the activity has no
     * logo specified.
     *
     * @throws NameNotFoundException Thrown if the resources for application
     * matching the given intent could not be loaded.
     *
     * @see #getActivityLogo(ComponentName)
     */
    public abstract Drawable getActivityLogo(Intent intent)
            throws NameNotFoundException;

    /**
     * Retrieve the logo associated with an application.  If it has not specified
     * a logo, this method returns null.
     *
     * @param info Information about application being queried.
     *
     * @return Returns the image of the logo, or null if no logo is specified
     * by the application.
     *
     * @see #getApplicationLogo(String)
     */
    public abstract Drawable getApplicationLogo(ApplicationInfo info);

    /**
     * Retrieve the logo associated with an application.  Given the name of the
     * application's package, retrieves the information about it and calls
     * getApplicationLogo() to return its logo. If the application cannot be
     * found, NameNotFoundException is thrown.
     *
     * @param packageName Name of the package whose application logo is to be
     *                    retrieved.
     *
     * @return Returns the image of the logo, or null if no application logo
     * has been specified.
     *
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getApplicationLogo(ApplicationInfo)
     */
    public abstract Drawable getApplicationLogo(String packageName)
            throws NameNotFoundException;

    /**
     * If the target user is a managed profile, then this returns a badged copy of the given icon
     * to be able to distinguish it from the original icon. For badging an arbitrary drawable use
     * {@link #getUserBadgedDrawableForDensity(
     * android.graphics.drawable.Drawable, UserHandle, android.graphics.Rect, int)}.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param icon The icon to badge.
     * @param user The target user.
     * @return A drawable that combines the original icon and a badge as
     *         determined by the system.
     */
    public abstract Drawable getUserBadgedIcon(Drawable icon, UserHandle user);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a badged copy of the given
     * drawable allowing the user to distinguish it from the original drawable.
     * The caller can specify the location in the bounds of the drawable to be
     * badged where the badge should be applied as well as the density of the
     * badge to be used.
     * <p>
     * If the original drawable is a BitmapDrawable and the backing bitmap is
     * mutable as per {@link android.graphics.Bitmap#isMutable()}, the badging
     * is performed in place and the original drawable is returned.
     * </p>
     *
     * @param drawable The drawable to badge.
     * @param user The target user.
     * @param badgeLocation Where in the bounds of the badged drawable to place
     *         the badge. If it's {@code null}, the badge is applied on top of the entire
     *         drawable being badged.
     * @param badgeDensity The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If it's not positive,
     *         the density of the display is used.
     * @return A drawable that combines the original drawable and a badge as
     *         determined by the system.
     */
    public abstract Drawable getUserBadgedDrawableForDensity(Drawable drawable,
            UserHandle user, Rect badgeLocation, int badgeDensity);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a drawable to use as a small
     * icon to include in a view to distinguish it from the original icon.
     *
     * @param user The target user.
     * @param density The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided
     *         the density of the current display is used.
     * @return the drawable or null if no drawable is required.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract Drawable getUserBadgeForDensity(UserHandle user, int density);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a drawable to use as a small
     * icon to include in a view to distinguish it from the original icon. This version
     * doesn't have background protection and should be used over a light background instead of
     * a badge.
     *
     * @param user The target user.
     * @param density The optional desired density for the badge as per
     *         {@link android.util.DisplayMetrics#densityDpi}. If not provided
     *         the density of the current display is used.
     * @return the drawable or null if no drawable is required.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract Drawable getUserBadgeForDensityNoBackground(UserHandle user, int density);

    /**
     * If the target user is a managed profile of the calling user or the caller
     * is itself a managed profile, then this returns a copy of the label with
     * badging for accessibility services like talkback. E.g. passing in "Email"
     * and it might return "Work Email" for Email in the work profile.
     *
     * @param label The label to change.
     * @param user The target user.
     * @return A label that combines the original label and a badge as
     *         determined by the system.
     */
    public abstract CharSequence getUserBadgedLabel(CharSequence label, UserHandle user);

    /**
     * Retrieve text from a package.  This is a low-level API used by
     * the various package manager info structures (such as
     * {@link ComponentInfo} to implement retrieval of their associated
     * labels and other text.
     *
     * @param packageName The name of the package that this text is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired text.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns a CharSequence holding the requested text.  Returns null
     * if the text could not be found for any reason.
     */
    public abstract CharSequence getText(String packageName, @StringRes int resid,
            ApplicationInfo appInfo);

    /**
     * Retrieve an XML file from a package.  This is a low-level API used to
     * retrieve XML meta data.
     *
     * @param packageName The name of the package that this xml is coming from.
     * Cannot be null.
     * @param resid The resource identifier of the desired xml.  Cannot be 0.
     * @param appInfo Overall information about <var>packageName</var>.  This
     * may be null, in which case the application information will be retrieved
     * for you if needed; if you already have this information around, it can
     * be much more efficient to supply it here.
     *
     * @return Returns an XmlPullParser allowing you to parse out the XML
     * data.  Returns null if the xml resource could not be found for any
     * reason.
     */
    public abstract XmlResourceParser getXml(String packageName, @XmlRes int resid,
            ApplicationInfo appInfo);

    /**
     * Return the label to use for this application.
     *
     * @return Returns the label associated with this application, or null if
     * it could not be found for any reason.
     * @param info The application to get the label of.
     */
    public abstract CharSequence getApplicationLabel(ApplicationInfo info);

    /**
     * Retrieve the resources associated with an activity.  Given the full
     * name of an activity, retrieves the information about it and calls
     * getResources() to return its application's resources.  If the activity
     * cannot be found, NameNotFoundException is thrown.
     *
     * @param activityName Name of the activity whose resources are to be
     *                     retrieved.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getResourcesForApplication(ApplicationInfo)
     */
    public abstract Resources getResourcesForActivity(ComponentName activityName)
            throws NameNotFoundException;

    /**
     * Retrieve the resources for an application.  Throws NameNotFoundException
     * if the package is no longer installed.
     *
     * @param app Information about the desired application.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded (most likely because it was uninstalled).
     */
    public abstract Resources getResourcesForApplication(ApplicationInfo app)
            throws NameNotFoundException;

    /**
     * Retrieve the resources associated with an application.  Given the full
     * package name of an application, retrieves the information about it and
     * calls getResources() to return its application's resources.  If the
     * appPackageName cannot be found, NameNotFoundException is thrown.
     *
     * @param appPackageName Package name of the application whose resources
     *                       are to be retrieved.
     *
     * @return Returns the application's Resources.
     * @throws NameNotFoundException Thrown if the resources for the given
     * application could not be loaded.
     *
     * @see #getResourcesForApplication(ApplicationInfo)
     */
    public abstract Resources getResourcesForApplication(String appPackageName)
            throws NameNotFoundException;

    /** @hide */
    @UnsupportedAppUsage
    public abstract Resources getResourcesForApplicationAsUser(String appPackageName,
            @UserIdInt int userId) throws NameNotFoundException;

    /**
     * Retrieve overall information about an application package defined in a
     * package archive file
     *
     * @param archiveFilePath The path to the archive file
     * @param flags Additional option flags to modify the data returned.
     * @return A PackageInfo object containing information about the package
     *         archive. If the package could not be parsed, returns null.
     */
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, @PackageInfoFlags int flags) {
        final PackageParser parser = new PackageParser();
        parser.setCallback(new PackageParser.CallbackImpl(this));
        final File apkFile = new File(archiveFilePath);
        try {
            if ((flags & (MATCH_DIRECT_BOOT_UNAWARE | MATCH_DIRECT_BOOT_AWARE)) != 0) {
                // Caller expressed an explicit opinion about what encryption
                // aware/unaware components they want to see, so fall through and
                // give them what they want
            } else {
                // Caller expressed no opinion, so match everything
                flags |= MATCH_DIRECT_BOOT_AWARE | MATCH_DIRECT_BOOT_UNAWARE;
            }

            PackageParser.Package pkg = parser.parseMonolithicPackage(apkFile, 0);
            if ((flags & GET_SIGNATURES) != 0) {
                PackageParser.collectCertificates(pkg, false /* skipVerify */);
            }
            PackageUserState state = new PackageUserState();
            return PackageParser.generatePackageInfo(pkg, null, flags, 0, 0, null, state);
        } catch (PackageParserException e) {
            return null;
        }
    }

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the calling user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @Deprecated
    @SystemApi
    public abstract int installExistingPackage(String packageName) throws NameNotFoundException;

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the calling user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @Deprecated
    @SystemApi
    public abstract int installExistingPackage(String packageName, @InstallReason int installReason)
            throws NameNotFoundException;

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the specified user.
     * @hide
     *
     * @deprecated use {@link PackageInstaller#installExistingPackage()} instead.
     */
    @Deprecated
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_EXISTING_PACKAGES,
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @UnsupportedAppUsage
    public abstract int installExistingPackageAsUser(String packageName, @UserIdInt int userId)
            throws NameNotFoundException;

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_PACKAGE_NEEDS_VERIFICATION package verification
     * broadcast} to respond to the package manager. The response must include
     * the {@code verificationCode} which is one of
     * {@link PackageManager#VERIFICATION_ALLOW} or
     * {@link PackageManager#VERIFICATION_REJECT}.
     *
     * @param id pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCode either {@link PackageManager#VERIFICATION_ALLOW}
     *            or {@link PackageManager#VERIFICATION_REJECT}.
     * @throws SecurityException if the caller does not have the
     *            PACKAGE_VERIFICATION_AGENT permission.
     */
    public abstract void verifyPendingInstall(int id, int verificationCode);

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_PACKAGE_NEEDS_VERIFICATION package verification
     * broadcast} to extend the default timeout for a response and declare what
     * action to perform after the timeout occurs. The response must include
     * the {@code verificationCodeAtTimeout} which is one of
     * {@link PackageManager#VERIFICATION_ALLOW} or
     * {@link PackageManager#VERIFICATION_REJECT}.
     *
     * This method may only be called once per package id. Additional calls
     * will have no effect.
     *
     * @param id pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCodeAtTimeout either
     *            {@link PackageManager#VERIFICATION_ALLOW} or
     *            {@link PackageManager#VERIFICATION_REJECT}. If
     *            {@code verificationCodeAtTimeout} is neither
     *            {@link PackageManager#VERIFICATION_ALLOW} or
     *            {@link PackageManager#VERIFICATION_REJECT}, then
     *            {@code verificationCodeAtTimeout} will default to
     *            {@link PackageManager#VERIFICATION_REJECT}.
     * @param millisecondsToDelay the amount of time requested for the timeout.
     *            Must be positive and less than
     *            {@link PackageManager#MAXIMUM_VERIFICATION_TIMEOUT}. If
     *            {@code millisecondsToDelay} is out of bounds,
     *            {@code millisecondsToDelay} will be set to the closest in
     *            bounds value; namely, 0 or
     *            {@link PackageManager#MAXIMUM_VERIFICATION_TIMEOUT}.
     * @throws SecurityException if the caller does not have the
     *            PACKAGE_VERIFICATION_AGENT permission.
     */
    public abstract void extendVerificationTimeout(int id,
            int verificationCodeAtTimeout, long millisecondsToDelay);

    /**
     * Allows a package listening to the
     * {@link Intent#ACTION_INTENT_FILTER_NEEDS_VERIFICATION} intent filter verification
     * broadcast to respond to the package manager. The response must include
     * the {@code verificationCode} which is one of
     * {@link PackageManager#INTENT_FILTER_VERIFICATION_SUCCESS} or
     * {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}.
     *
     * @param verificationId pending package identifier as passed via the
     *            {@link PackageManager#EXTRA_VERIFICATION_ID} Intent extra.
     * @param verificationCode either {@link PackageManager#INTENT_FILTER_VERIFICATION_SUCCESS}
     *            or {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}.
     * @param failedDomains a list of failed domains if the verificationCode is
     *            {@link PackageManager#INTENT_FILTER_VERIFICATION_FAILURE}, otherwise null;
     * @throws SecurityException if the caller does not have the
     *            INTENT_FILTER_VERIFICATION_AGENT permission.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT)
    public abstract void verifyIntentFilter(int verificationId, int verificationCode,
            List<String> failedDomains);

    /**
     * Get the status of a Domain Verification Result for an IntentFilter. This is
     * related to the {@link android.content.IntentFilter#setAutoVerify(boolean)} and
     * {@link android.content.IntentFilter#getAutoVerify()}
     *
     * This is used by the ResolverActivity to change the status depending on what the User select
     * in the Disambiguation Dialog and also used by the Settings App for changing the default App
     * for a domain.
     *
     * @param packageName The package name of the Activity associated with the IntentFilter.
     * @param userId The user id.
     *
     * @return The status to set to. This can be
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_UNDEFINED}
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract int getIntentVerificationStatusAsUser(String packageName, @UserIdInt int userId);

    /**
     * Allow to change the status of a Intent Verification status for all IntentFilter of an App.
     * This is related to the {@link android.content.IntentFilter#setAutoVerify(boolean)} and
     * {@link android.content.IntentFilter#getAutoVerify()}
     *
     * This is used by the ResolverActivity to change the status depending on what the User select
     * in the Disambiguation Dialog and also used by the Settings App for changing the default App
     * for a domain.
     *
     * @param packageName The package name of the Activity associated with the IntentFilter.
     * @param status The status to set to. This can be
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ASK} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_ALWAYS} or
     *              {@link #INTENT_FILTER_DOMAIN_VERIFICATION_STATUS_NEVER}
     * @param userId The user id.
     *
     * @return true if the status has been set. False otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SET_PREFERRED_APPLICATIONS)
    public abstract boolean updateIntentVerificationStatusAsUser(String packageName, int status,
            @UserIdInt int userId);

    /**
     * Get the list of IntentFilterVerificationInfo for a specific package and User.
     *
     * @param packageName the package name. When this parameter is set to a non null value,
     *                    the results will be filtered by the package name provided.
     *                    Otherwise, there will be no filtering and it will return a list
     *                    corresponding for all packages
     *
     * @return a list of IntentFilterVerificationInfo for a specific package.
     *
     * @hide
     */
    @SystemApi
    public abstract List<IntentFilterVerificationInfo> getIntentFilterVerifications(
            String packageName);

    /**
     * Get the list of IntentFilter for a specific package.
     *
     * @param packageName the package name. This parameter is set to a non null value,
     *                    the list will contain all the IntentFilter for that package.
     *                    Otherwise, the list will be empty.
     *
     * @return a list of IntentFilter for a specific package.
     *
     * @hide
     */
    @SystemApi
    public abstract List<IntentFilter> getAllIntentFilters(String packageName);

    /**
     * Get the default Browser package name for a specific user.
     *
     * @param userId The user id.
     *
     * @return the package name of the default Browser for the specified user. If the user id passed
     *         is -1 (all users) it will return a null value.
     *
     * @hide
     */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public abstract String getDefaultBrowserPackageNameAsUser(@UserIdInt int userId);

    /**
     * Set the default Browser package name for a specific user.
     *
     * @param packageName The package name of the default Browser.
     * @param userId The user id.
     *
     * @return true if the default Browser for the specified user has been set,
     *         otherwise return false. If the user id passed is -1 (all users) this call will not
     *         do anything and just return false.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {
            Manifest.permission.SET_PREFERRED_APPLICATIONS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public abstract boolean setDefaultBrowserPackageNameAsUser(String packageName,
            @UserIdInt int userId);

    /**
     * Change the installer associated with a given package.  There are limitations
     * on how the installer package can be changed; in particular:
     * <ul>
     * <li> A SecurityException will be thrown if <var>installerPackageName</var>
     * is not signed with the same certificate as the calling application.
     * <li> A SecurityException will be thrown if <var>targetPackage</var> already
     * has an installer package, and that installer package is not signed with
     * the same certificate as the calling application.
     * </ul>
     *
     * @param targetPackage The installed package whose installer will be changed.
     * @param installerPackageName The package name of the new installer.  May be
     * null to clear the association.
     */
    public abstract void setInstallerPackageName(String targetPackage,
            String installerPackageName);

    /** @hide */
    @SystemApi
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    public abstract void setUpdateAvailable(String packageName, boolean updateAvaialble);

    /**
     * Attempts to delete a package. Since this may take a little while, the
     * result will be posted back to the given observer. A deletion will fail if
     * the calling context lacks the
     * {@link android.Manifest.permission#DELETE_PACKAGES} permission, if the
     * named package cannot be found, or if the named package is a system
     * package.
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package
     *            deletion is complete.
     *            {@link android.content.pm.IPackageDeleteObserver#packageDeleted}
     *            will be called when that happens. observer may be null to
     *            indicate that no callback is desired.
     * @hide
     */
    @RequiresPermission(Manifest.permission.DELETE_PACKAGES)
    @UnsupportedAppUsage
    public abstract void deletePackage(String packageName, IPackageDeleteObserver observer,
            @DeleteFlags int flags);

    /**
     * Attempts to delete a package. Since this may take a little while, the
     * result will be posted back to the given observer. A deletion will fail if
     * the named package cannot be found, or if the named package is a system
     * package.
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package
     *            deletion is complete.
     *            {@link android.content.pm.IPackageDeleteObserver#packageDeleted}
     *            will be called when that happens. observer may be null to
     *            indicate that no callback is desired.
     * @param userId The user Id
     * @hide
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    @UnsupportedAppUsage
    public abstract void deletePackageAsUser(@NonNull String packageName,
            IPackageDeleteObserver observer, @DeleteFlags int flags, @UserIdInt int userId);

    /**
     * Retrieve the package name of the application that installed a package. This identifies
     * which market the package came from.
     *
     * @param packageName The name of the package to query
     * @throws IllegalArgumentException if the given package name is not installed
     */
    @Nullable
    public abstract String getInstallerPackageName(String packageName);

    /**
     * Attempts to clear the user data directory of an application.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  A deletion will fail if the
     * named package cannot be found, or if the named package is a "system package".
     *
     * @param packageName The name of the package
     * @param observer An observer callback to get notified when the operation is finished
     * {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     * will be called when that happens.  observer may be null to indicate that
     * no callback is desired.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void clearApplicationUserData(String packageName,
            IPackageDataObserver observer);
    /**
     * Attempts to delete the cache files associated with an application.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  A deletion will fail if the calling context
     * lacks the {@link android.Manifest.permission#DELETE_CACHE_FILES} permission, if the
     * named package cannot be found, or if the named package is a "system package".
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the cache file deletion
     * is complete.
     * {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     * will be called when that happens.  observer may be null to indicate that
     * no callback is desired.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void deleteApplicationCacheFiles(String packageName,
            IPackageDataObserver observer);

    /**
     * Attempts to delete the cache files associated with an application for a given user. Since
     * this may take a little while, the result will be posted back to the given observer. A
     * deletion will fail if the calling context lacks the
     * {@link android.Manifest.permission#DELETE_CACHE_FILES} permission, if the named package
     * cannot be found, or if the named package is a "system package". If {@code userId} does not
     * belong to the calling user, the caller must have
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS} permission.
     *
     * @param packageName The name of the package to delete
     * @param userId the user for which the cache files needs to be deleted
     * @param observer An observer callback to get notified when the cache file deletion is
     *            complete.
     *            {@link android.content.pm.IPackageDataObserver#onRemoveCompleted(String, boolean)}
     *            will be called when that happens. observer may be null to indicate that no
     *            callback is desired.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void deleteApplicationCacheFilesAsUser(String packageName, int userId,
            IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param observer call back used to notify when
     * the operation is completed
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void freeStorageAndNotify(long freeStorageSize, IPackageDataObserver observer) {
        freeStorageAndNotify(null, freeStorageSize, observer);
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract void freeStorageAndNotify(String volumeUuid, long freeStorageSize,
            IPackageDataObserver observer);

    /**
     * Free storage by deleting LRU sorted list of cache files across
     * all applications. If the currently available free storage
     * on the device is greater than or equal to the requested
     * free storage, no cache files are cleared. If the currently
     * available storage on the device is less than the requested
     * free storage, some or all of the cache files across
     * all applications are deleted (based on last accessed time)
     * to increase the free storage space on the device to
     * the requested value. There is no guarantee that clearing all
     * the cache files from all applications will clear up
     * enough storage to achieve the desired value.
     * @param freeStorageSize The number of bytes of storage to be
     * freed by the system. Say if freeStorageSize is XX,
     * and the current free storage is YY,
     * if XX is less than YY, just return. if not free XX-YY number
     * of bytes if possible.
     * @param pi IntentSender call back used to
     * notify when the operation is completed.May be null
     * to indicate that no call back is desired.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void freeStorage(long freeStorageSize, IntentSender pi) {
        freeStorage(null, freeStorageSize, pi);
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract void freeStorage(String volumeUuid, long freeStorageSize, IntentSender pi);

    /**
     * Retrieve the size information for a package.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  The calling context
     * should have the {@link android.Manifest.permission#GET_PACKAGE_SIZE} permission.
     *
     * @param packageName The name of the package whose size information is to be retrieved
     * @param userId The user whose size information should be retrieved.
     * @param observer An observer callback to get notified when the operation
     * is complete.
     * {@link android.content.pm.IPackageStatsObserver#onGetStatsCompleted(PackageStats, boolean)}
     * The observer's callback is invoked with a PackageStats object(containing the
     * code, data and cache sizes of the package) and a boolean value representing
     * the status of the operation. observer may be null to indicate that
     * no callback is desired.
     *
     * @deprecated use {@link StorageStatsManager} instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public abstract void getPackageSizeInfoAsUser(String packageName, @UserIdInt int userId,
            IPackageStatsObserver observer);

    /**
     * Like {@link #getPackageSizeInfoAsUser(String, int, IPackageStatsObserver)}, but
     * returns the size for the calling user.
     *
     * @deprecated use {@link StorageStatsManager} instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void getPackageSizeInfo(String packageName, IPackageStatsObserver observer) {
        getPackageSizeInfoAsUser(packageName, getUserId(), observer);
    }

    /**
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract void addPackageToPreferred(String packageName);

    /**
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract void removePackageFromPreferred(String packageName);

    /**
     * Retrieve the list of all currently configured preferred packages. The
     * first package on the list is the most preferred, the last is the least
     * preferred.
     *
     * @param flags Additional option flags to modify the data returned.
     * @return A List of PackageInfo objects, one for each preferred
     *         application, in order of preference.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract List<PackageInfo> getPreferredPackages(@PackageInfoFlags int flags);

    /**
     * Add a new preferred activity mapping to the system.  This will be used
     * to automatically select the given activity component when
     * {@link Context#startActivity(Intent) Context.startActivity()} finds
     * multiple matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be
     * made preferred.
     * @param match The IntentFilter match category that this preference
     * applies to.
     * @param set The set of activities that the user was picking from when
     * this preference was made.
     * @param activity The component name of the activity that is to be
     * preferred.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity);

    /**
     * Same as {@link #addPreferredActivity(IntentFilter, int,
            ComponentName[], ComponentName)}, but with a specific userId to apply the preference
            to.
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void addPreferredActivityAsUser(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, @UserIdInt int userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Replaces an existing preferred activity mapping to the system, and if that were not present
     * adds a new preferred activity.  This will be used
     * to automatically select the given activity component when
     * {@link Context#startActivity(Intent) Context.startActivity()} finds
     * multiple matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be
     * made preferred.
     * @param match The IntentFilter match category that this preference
     * applies to.
     * @param set The set of activities that the user was picking from when
     * this preference was made.
     * @param activity The component name of the activity that is to be
     * preferred.
     *
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public abstract void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity);

    /**
     * Replaces an existing preferred activity mapping to the system, and if that were not present
     * adds a new preferred activity.  This will be used to automatically select the given activity
     * component when {@link Context#startActivity(Intent) Context.startActivity()} finds multiple
     * matching activities and also matches the given filter.
     *
     * @param filter The set of intents under which this activity will be made preferred.
     * @param match The IntentFilter match category that this preference applies to. Should be a
     *              combination of {@link IntentFilter#MATCH_CATEGORY_MASK} and
     *              {@link IntentFilter#MATCH_ADJUSTMENT_MASK}).
     * @param set The set of activities that the user was picking from when this preference was
     *            made.
     * @param activity The component name of the activity that is to be preferred.
     *
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    @SystemApi
    public void replacePreferredActivity(@NonNull IntentFilter filter, int match,
            @NonNull List<ComponentName> set, @NonNull ComponentName activity) {
        replacePreferredActivity(filter, match, set.toArray(new ComponentName[0]), activity);
    }

    /**
     * @hide
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    @UnsupportedAppUsage
    public void replacePreferredActivityAsUser(IntentFilter filter, int match,
           ComponentName[] set, ComponentName activity, @UserIdInt int userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * Remove all preferred activity mappings, previously added with
     * {@link #addPreferredActivity}, from the
     * system whose activities are implemented in the given package name.
     * An application can only clear its own package(s).
     *
     * @param packageName The name of the package whose preferred activity
     * mappings are to be removed.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract void clearPackagePreferredActivities(String packageName);

    /**
     * Retrieve all preferred activities, previously added with
     * {@link #addPreferredActivity}, that are
     * currently registered with the system.
     *
     * @param outFilters A required list in which to place the filters of all of the
     * preferred activities.
     * @param outActivities A required list in which to place the component names of
     * all of the preferred activities.
     * @param packageName An optional package in which you would like to limit
     * the list.  If null, all activities will be returned; if non-null, only
     * those activities in the given package are returned.
     *
     * @return Returns the total number of registered preferred activities
     * (the number of distinct IntentFilter records, not the number of unique
     * activity components) that were found.
     *
     * @deprecated This function no longer does anything. It is the platform's
     * responsibility to assign preferred activities and this cannot be modified
     * directly. To determine the activities resolved by the platform, use
     * {@link #resolveActivity} or {@link #queryIntentActivities}.
     */
    @Deprecated
    public abstract int getPreferredActivities(@NonNull List<IntentFilter> outFilters,
            @NonNull List<ComponentName> outActivities, String packageName);

    /**
     * Ask for the set of available 'home' activities and the current explicit
     * default, if any.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract ComponentName getHomeActivities(List<ResolveInfo> outActivities);

    /**
     * Set the enabled setting for a package component (activity, receiver, service, provider).
     * This setting will override any enabled state which may have been set by the component in its
     * manifest.
     *
     * @param componentName The component to enable
     * @param newState The new enabled state for the component.
     * @param flags Optional behavior flags.
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    public abstract void setComponentEnabledSetting(@NonNull ComponentName componentName,
            @EnabledState int newState, @EnabledFlags int flags);

    /**
     * Return the enabled setting for a package component (activity,
     * receiver, service, provider).  This returns the last value set by
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param componentName The component to retrieve.
     * @return Returns the current enabled state for the component.
     */
    public abstract @EnabledState int getComponentEnabledSetting(
            @NonNull ComponentName componentName);

    /**
     * Set whether a synthetic app details activity will be generated if the app has no enabled
     * launcher activity. Disabling this allows the app to have no launcher icon.
     *
     * @param packageName The package name of the app
     * @param enabled The new enabled state for the synthetic app details activity.
     *
     * @hide
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    @SystemApi
    public void setSyntheticAppDetailsActivityEnabled(@NonNull String packageName,
            boolean enabled) {
        throw new UnsupportedOperationException(
                "setSyntheticAppDetailsActivityEnabled not implemented");
    }


    /**
     * Return whether a synthetic app details activity will be generated if the app has no enabled
     * launcher activity.
     *
     * @param packageName The package name of the app
     * @return Returns the enabled state for the synthetic app details activity.
     *
     * @hide
     */
    @SystemApi
    public boolean getSyntheticAppDetailsActivityEnabled(@NonNull String packageName) {
        throw new UnsupportedOperationException(
                "getSyntheticAppDetailsActivityEnabled not implemented");
    }

    /**
     * Set the enabled setting for an application
     * This setting will override any enabled state which may have been set by the application in
     * its manifest.  It also overrides the enabled state set in the manifest for any of the
     * application's components.  It does not override any enabled state set by
     * {@link #setComponentEnabledSetting} for any of the application's components.
     *
     * @param packageName The package name of the application to enable
     * @param newState The new enabled state for the application.
     * @param flags Optional behavior flags.
     */
    @RequiresPermission(value = android.Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE,
            conditional = true)
    public abstract void setApplicationEnabledSetting(@NonNull String packageName,
            @EnabledState int newState, @EnabledFlags int flags);

    /**
     * Return the enabled setting for an application. This returns
     * the last value set by
     * {@link #setApplicationEnabledSetting(String, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param packageName The package name of the application to retrieve.
     * @return Returns the current enabled state for the application.
     * @throws IllegalArgumentException if the named package does not exist.
     */
    public abstract @EnabledState int getApplicationEnabledSetting(@NonNull String packageName);

    /**
     * Flush the package restrictions for a given user to disk. This forces the package restrictions
     * like component and package enabled settings to be written to disk and avoids the delay that
     * is otherwise present when changing those settings.
     *
     * @param userId Ther userId of the user whose restrictions are to be flushed.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void flushPackageRestrictionsAsUser(int userId);

    /**
     * Puts the package in a hidden state, which is almost like an uninstalled state,
     * making the package unavailable, but it doesn't remove the data or the actual
     * package file. Application can be unhidden by either resetting the hidden state
     * or by installing it, such as with {@link #installExistingPackage(String)}
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            UserHandle userHandle);

    /**
     * Returns the hidden state of a package.
     * @see #setApplicationHiddenSettingAsUser(String, boolean, UserHandle)
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean getApplicationHiddenSettingAsUser(String packageName,
            UserHandle userHandle);

    /**
     * Return whether the device has been booted into safe mode.
     */
    public abstract boolean isSafeMode();

    /**
     * Adds a listener for permission changes for installed packages.
     *
     * @param listener The listener to add.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public abstract void addOnPermissionsChangeListener(OnPermissionsChangedListener listener);

    /**
     * Remvoes a listener for permission changes for installed packages.
     *
     * @param listener The listener to remove.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    public abstract void removeOnPermissionsChangeListener(OnPermissionsChangedListener listener);

    /**
     * Return the {@link KeySet} associated with the String alias for this
     * application.
     *
     * @param alias The alias for a given {@link KeySet} as defined in the
     *        application's AndroidManifest.xml.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract KeySet getKeySetByAlias(String packageName, String alias);

    /** Return the signing {@link KeySet} for this application.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract KeySet getSigningKeySet(String packageName);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of the keys specified by the {@link KeySet} ks.  This will return true if
     * the package has been signed by additional keys (a superset) as well.
     * Compare to {@link #isSignedByExactly(String packageName, KeySet ks)}.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean isSignedBy(String packageName, KeySet ks);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of, and only, the keys specified by the {@link KeySet} ks. Compare to
     * {@link #isSignedBy(String packageName, KeySet ks)}.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean isSignedByExactly(String packageName, KeySet ks);

    /**
     * Flag to denote no restrictions. This should be used to clear any restrictions that may have
     * been previously set for the package.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_NONE = 0x0;

    /**
     * Flag to denote that a package should be hidden from any suggestions to the user.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_HIDE_FROM_SUGGESTIONS = 0x00000001;

    /**
     * Flag to denote that a package's notifications should be hidden.
     * @hide
     * @see #setDistractingPackageRestrictions(String[], int)
     */
    @SystemApi
    public static final int RESTRICTION_HIDE_NOTIFICATIONS = 0x00000002;

    /**
     * Restriction flags to set on a package that is considered as distracting to the user.
     * These should help the user to restrict their usage of these apps.
     *
     * @see #setDistractingPackageRestrictions(String[], int)
     * @hide
     */
    @IntDef(flag = true, prefix = {"RESTRICTION_"}, value = {
            RESTRICTION_NONE,
            RESTRICTION_HIDE_FROM_SUGGESTIONS,
            RESTRICTION_HIDE_NOTIFICATIONS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DistractionRestriction {}

    /**
     * Mark or unmark the given packages as distracting to the user.
     * These packages can have certain restrictions set that should discourage the user to launch
     * them often. For example, notifications from such an app can be hidden, or the app can be
     * removed from launcher suggestions, so the user is able to restrict their use of these apps.
     *
     * <p>The caller must hold {@link android.Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packages Packages to mark as distracting.
     * @param restrictionFlags Any combination of restrictions to impose on the given packages.
     *                         {@link #RESTRICTION_NONE} can be used to clear any existing
     *                         restrictions.
     * @return A list of packages that could not have the {@code restrictionFlags} set. The system
     * may prevent restricting critical packages to preserve normal device function.
     *
     * @hide
     * @see #RESTRICTION_NONE
     * @see #RESTRICTION_HIDE_FROM_SUGGESTIONS
     * @see #RESTRICTION_HIDE_NOTIFICATIONS
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.SUSPEND_APPS)
    @NonNull
    public String[] setDistractingPackageRestrictions(@NonNull String[] packages,
            @DistractionRestriction int restrictionFlags) {
        throw new UnsupportedOperationException(
                "setDistractingPackageRestrictions not implemented");
    }

    /**
     * Puts the package in a suspended state, where attempts at starting activities are denied.
     *
     * <p>It doesn't remove the data or the actual package file. The application's notifications
     * will be hidden, any of its started activities will be stopped and it will not be able to
     * show toasts or system alert windows or ring the device.
     *
     * <p>When the user tries to launch a suspended app, a system dialog with the given
     * {@code dialogMessage} will be shown instead. Since the message is supplied to the system as
     * a {@link String}, the caller needs to take care of localization as needed.
     * The dialog message can optionally contain a placeholder for the name of the suspended app.
     * The system uses {@link String#format(Locale, String, Object...) String.format} to insert the
     * app name into the message, so an example format string could be {@code "The app %1$s is
     * currently suspended"}. This makes it easier for callers to provide a single message which
     * works for all the packages being suspended in a single call.
     *
     * <p>The package must already be installed. If the package is uninstalled while suspended
     * the package will no longer be suspended. </p>
     *
     * <p>Optionally, the suspending app can provide extra information in the form of
     * {@link PersistableBundle} objects to be shared with the apps being suspended and the
     * launcher to support customization that they might need to handle the suspended state.
     *
     * <p>The caller must hold {@link Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended If set to {@code true}, the packages will be suspended, if set to
     * {@code false}, the packages will be unsuspended.
     * @param appExtras An optional {@link PersistableBundle} that the suspending app can provide
     *                  which will be shared with the apps being suspended. Ignored if
     *                  {@code suspended} is false.
     * @param launcherExtras An optional {@link PersistableBundle} that the suspending app can
     *                       provide which will be shared with the launcher. Ignored if
     *                       {@code suspended} is false.
     * @param dialogMessage The message to be displayed to the user, when they try to launch a
     *                      suspended app.
     *
     * @return an array of package names for which the suspended status could not be set as
     * requested in this method. Returns {@code null} if {@code packageNames} was {@code null}.
     *
     * @deprecated use {@link #setPackagesSuspended(String[], boolean, PersistableBundle,
     * PersistableBundle, android.content.pm.SuspendDialogInfo)} instead.
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @Nullable
    public String[] setPackagesSuspended(@Nullable String[] packageNames, boolean suspended,
            @Nullable PersistableBundle appExtras, @Nullable PersistableBundle launcherExtras,
            @Nullable String dialogMessage) {
        throw new UnsupportedOperationException("setPackagesSuspended not implemented");
    }

    /**
     * Puts the given packages in a suspended state, where attempts at starting activities are
     * denied.
     *
     * <p>The suspended application's notifications and all of its windows will be hidden, any
     * of its started activities will be stopped and it won't be able to ring the device.
     * It doesn't remove the data or the actual package file.
     *
     * <p>When the user tries to launch a suspended app, a system dialog alerting them that the app
     * is suspended will be shown instead.
     * The caller can optionally customize the dialog by passing a {@link SuspendDialogInfo} object
     * to this API. This dialog will have a button that starts the
     * {@link Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS} intent if the suspending app declares an
     * activity which handles this action.
     *
     * <p>The packages being suspended must already be installed. If a package is uninstalled, it
     * will no longer be suspended.
     *
     * <p>Optionally, the suspending app can provide extra information in the form of
     * {@link PersistableBundle} objects to be shared with the apps being suspended and the
     * launcher to support customization that they might need to handle the suspended state.
     *
     * <p>The caller must hold {@link Manifest.permission#SUSPEND_APPS} to use this API.
     *
     * @param packageNames The names of the packages to set the suspended status.
     * @param suspended If set to {@code true}, the packages will be suspended, if set to
     * {@code false}, the packages will be unsuspended.
     * @param appExtras An optional {@link PersistableBundle} that the suspending app can provide
     *                  which will be shared with the apps being suspended. Ignored if
     *                  {@code suspended} is false.
     * @param launcherExtras An optional {@link PersistableBundle} that the suspending app can
     *                       provide which will be shared with the launcher. Ignored if
     *                       {@code suspended} is false.
     * @param dialogInfo An optional {@link SuspendDialogInfo} object describing the dialog that
     *                   should be shown to the user when they try to launch a suspended app.
     *                   Ignored if {@code suspended} is false.
     *
     * @return an array of package names for which the suspended status could not be set as
     * requested in this method. Returns {@code null} if {@code packageNames} was {@code null}.
     *
     * @see #isPackageSuspended
     * @see SuspendDialogInfo
     * @see SuspendDialogInfo.Builder
     * @see Intent#ACTION_SHOW_SUSPENDED_APP_DETAILS
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @Nullable
    public String[] setPackagesSuspended(@Nullable String[] packageNames, boolean suspended,
            @Nullable PersistableBundle appExtras, @Nullable PersistableBundle launcherExtras,
            @Nullable SuspendDialogInfo dialogInfo) {
        throw new UnsupportedOperationException("setPackagesSuspended not implemented");
    }

    /**
     * Returns any packages in a given set of packages that cannot be suspended via a call to {@link
     * #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle,
     * SuspendDialogInfo) setPackagesSuspended}. The platform prevents suspending certain critical
     * packages to keep the device in a functioning state, e.g. the default dialer.
     * Apps need to hold {@link Manifest.permission#SUSPEND_APPS SUSPEND_APPS} to call this API.
     *
     * <p>
     * Note that this set of critical packages can change with time, so even though a package name
     * was not returned by this call, it does not guarantee that a subsequent call to
     * {@link #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle,
     * SuspendDialogInfo) setPackagesSuspended} for that package will succeed, especially if
     * significant time elapsed between the two calls.
     *
     * @param packageNames The packages to check.
     * @return A list of packages that can not be currently suspended by the system.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.SUSPEND_APPS)
    @NonNull
    public String[] getUnsuspendablePackages(@NonNull String[] packageNames) {
        throw new UnsupportedOperationException("canSuspendPackages not implemented");
    }

    /**
     * @see #setPackagesSuspended(String[], boolean, PersistableBundle, PersistableBundle, String)
     * @param packageName The name of the package to get the suspended status of.
     * @param userId The user id.
     * @return {@code true} if the package is suspended or {@code false} if the package is not
     * suspended.
     * @throws IllegalArgumentException if the package was not found.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean isPackageSuspendedForUser(String packageName, int userId);

    /**
     * Query if an app is currently suspended.
     *
     * @return {@code true} if the given package is suspended, {@code false} otherwise
     * @throws NameNotFoundException if the package could not be found.
     *
     * @see #isPackageSuspended()
     */
    public boolean isPackageSuspended(String packageName) throws NameNotFoundException {
        throw new UnsupportedOperationException("isPackageSuspended not implemented");
    }

    /**
     * Apps can query this to know if they have been suspended. A system app with the permission
     * {@code android.permission.SUSPEND_APPS} can put any app on the device into a suspended state.
     *
     * <p>While in this state, the application's notifications will be hidden, any of its started
     * activities will be stopped and it will not be able to show toasts or dialogs or play audio.
     * When the user tries to launch a suspended app, the system will, instead, show a
     * dialog to the user informing them that they cannot use this app while it is suspended.
     *
     * <p>When an app is put into this state, the broadcast action
     * {@link Intent#ACTION_MY_PACKAGE_SUSPENDED} will be delivered to any of its broadcast
     * receivers that included this action in their intent-filters, <em>including manifest
     * receivers.</em> Similarly, a broadcast action {@link Intent#ACTION_MY_PACKAGE_UNSUSPENDED}
     * is delivered when a previously suspended app is taken out of this state. Apps are expected to
     * use these to gracefully deal with transitions to and from this state.
     *
     * @return {@code true} if the calling package has been suspended, {@code false} otherwise.
     *
     * @see #getSuspendedPackageAppExtras()
     * @see Intent#ACTION_MY_PACKAGE_SUSPENDED
     * @see Intent#ACTION_MY_PACKAGE_UNSUSPENDED
     */
    public boolean isPackageSuspended() {
        throw new UnsupportedOperationException("isPackageSuspended not implemented");
    }

    /**
     * Returns a {@link Bundle} of extras that was meant to be sent to the calling app when it was
     * suspended. An app with the permission {@code android.permission.SUSPEND_APPS} can supply this
     * to the system at the time of suspending an app.
     *
     * <p>This is the same {@link Bundle} that is sent along with the broadcast
     * {@link Intent#ACTION_MY_PACKAGE_SUSPENDED}, whenever the app is suspended. The contents of
     * this {@link Bundle} are a contract between the suspended app and the suspending app.
     *
     * <p>Note: These extras are optional, so if no extras were supplied to the system, this method
     * will return {@code null}, even when the calling app has been suspended.
     *
     * @return A {@link Bundle} containing the extras for the app, or {@code null} if the
     * package is not currently suspended.
     *
     * @see #isPackageSuspended()
     * @see Intent#ACTION_MY_PACKAGE_UNSUSPENDED
     * @see Intent#ACTION_MY_PACKAGE_SUSPENDED
     * @see Intent#EXTRA_SUSPENDED_PACKAGE_EXTRAS
     */
    public @Nullable Bundle getSuspendedPackageAppExtras() {
        throw new UnsupportedOperationException("getSuspendedPackageAppExtras not implemented");
    }

    /**
     * Provide a hint of what the {@link ApplicationInfo#category} value should
     * be for the given package.
     * <p>
     * This hint can only be set by the app which installed this package, as
     * determined by {@link #getInstallerPackageName(String)}.
     *
     * @param packageName the package to change the category hint for.
     * @param categoryHint the category hint to set.
     */
    public abstract void setApplicationCategoryHint(@NonNull String packageName,
            @ApplicationInfo.Category int categoryHint);

    /** {@hide} */
    public static boolean isMoveStatusFinished(int status) {
        return (status < 0 || status > 100);
    }

    /** {@hide} */
    public static abstract class MoveCallback {
        public void onCreated(int moveId, Bundle extras) {}
        public abstract void onStatusChanged(int moveId, int status, long estMillis);
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract int getMoveStatus(int moveId);

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract void registerMoveCallback(MoveCallback callback, Handler handler);
    /** {@hide} */
    @UnsupportedAppUsage
    public abstract void unregisterMoveCallback(MoveCallback callback);

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract int movePackage(String packageName, VolumeInfo vol);
    /** {@hide} */
    @UnsupportedAppUsage
    public abstract @Nullable VolumeInfo getPackageCurrentVolume(ApplicationInfo app);
    /** {@hide} */
    @UnsupportedAppUsage
    public abstract @NonNull List<VolumeInfo> getPackageCandidateVolumes(ApplicationInfo app);

    /** {@hide} */
    public abstract int movePrimaryStorage(VolumeInfo vol);
    /** {@hide} */
    public abstract @Nullable VolumeInfo getPrimaryStorageCurrentVolume();
    /** {@hide} */
    public abstract @NonNull List<VolumeInfo> getPrimaryStorageCandidateVolumes();

    /**
     * Returns the device identity that verifiers can use to associate their scheme to a particular
     * device. This should not be used by anything other than a package verifier.
     *
     * @return identity that uniquely identifies current device
     * @hide
     */
    public abstract VerifierDeviceIdentity getVerifierDeviceIdentity();

    /**
     * Returns true if the device is upgrading, such as first boot after OTA.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public abstract boolean isUpgrade();

    /**
     * Return interface that offers the ability to install, upgrade, and remove
     * applications on the device.
     */
    public abstract @NonNull PackageInstaller getPackageInstaller();

    /**
     * Adds a {@code CrossProfileIntentFilter}. After calling this method all
     * intents sent from the user with id sourceUserId can also be be resolved
     * by activities in the user with id targetUserId if they match the
     * specified intent filter.
     *
     * @param filter The {@link IntentFilter} the intent has to match
     * @param sourceUserId The source user id.
     * @param targetUserId The target user id.
     * @param flags The possible values are {@link #SKIP_CURRENT_PROFILE} and
     *            {@link #ONLY_IF_NO_MATCH_FOUND}.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void addCrossProfileIntentFilter(IntentFilter filter, int sourceUserId,
            int targetUserId, int flags);

    /**
     * Clearing {@code CrossProfileIntentFilter}s which have the specified user
     * as their source, and have been set by the app calling this method.
     *
     * @param sourceUserId The source user id.
     * @hide
     */
    @UnsupportedAppUsage
    public abstract void clearCrossProfileIntentFilters(int sourceUserId);

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public abstract Drawable loadItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo);

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public abstract Drawable loadUnbadgedItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo);

    /** {@hide} */
    @UnsupportedAppUsage
    public abstract boolean isPackageAvailable(String packageName);

    /** {@hide} */
    @UnsupportedAppUsage
    public static String installStatusToString(int status, String msg) {
        final String str = installStatusToString(status);
        if (msg != null) {
            return str + ": " + msg;
        } else {
            return str;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public static String installStatusToString(int status) {
        switch (status) {
            case INSTALL_SUCCEEDED: return "INSTALL_SUCCEEDED";
            case INSTALL_FAILED_ALREADY_EXISTS: return "INSTALL_FAILED_ALREADY_EXISTS";
            case INSTALL_FAILED_INVALID_APK: return "INSTALL_FAILED_INVALID_APK";
            case INSTALL_FAILED_INVALID_URI: return "INSTALL_FAILED_INVALID_URI";
            case INSTALL_FAILED_INSUFFICIENT_STORAGE: return "INSTALL_FAILED_INSUFFICIENT_STORAGE";
            case INSTALL_FAILED_DUPLICATE_PACKAGE: return "INSTALL_FAILED_DUPLICATE_PACKAGE";
            case INSTALL_FAILED_NO_SHARED_USER: return "INSTALL_FAILED_NO_SHARED_USER";
            case INSTALL_FAILED_UPDATE_INCOMPATIBLE: return "INSTALL_FAILED_UPDATE_INCOMPATIBLE";
            case INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: return "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE";
            case INSTALL_FAILED_MISSING_SHARED_LIBRARY: return "INSTALL_FAILED_MISSING_SHARED_LIBRARY";
            case INSTALL_FAILED_REPLACE_COULDNT_DELETE: return "INSTALL_FAILED_REPLACE_COULDNT_DELETE";
            case INSTALL_FAILED_DEXOPT: return "INSTALL_FAILED_DEXOPT";
            case INSTALL_FAILED_OLDER_SDK: return "INSTALL_FAILED_OLDER_SDK";
            case INSTALL_FAILED_CONFLICTING_PROVIDER: return "INSTALL_FAILED_CONFLICTING_PROVIDER";
            case INSTALL_FAILED_NEWER_SDK: return "INSTALL_FAILED_NEWER_SDK";
            case INSTALL_FAILED_TEST_ONLY: return "INSTALL_FAILED_TEST_ONLY";
            case INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: return "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE";
            case INSTALL_FAILED_MISSING_FEATURE: return "INSTALL_FAILED_MISSING_FEATURE";
            case INSTALL_FAILED_CONTAINER_ERROR: return "INSTALL_FAILED_CONTAINER_ERROR";
            case INSTALL_FAILED_INVALID_INSTALL_LOCATION: return "INSTALL_FAILED_INVALID_INSTALL_LOCATION";
            case INSTALL_FAILED_MEDIA_UNAVAILABLE: return "INSTALL_FAILED_MEDIA_UNAVAILABLE";
            case INSTALL_FAILED_VERIFICATION_TIMEOUT: return "INSTALL_FAILED_VERIFICATION_TIMEOUT";
            case INSTALL_FAILED_VERIFICATION_FAILURE: return "INSTALL_FAILED_VERIFICATION_FAILURE";
            case INSTALL_FAILED_PACKAGE_CHANGED: return "INSTALL_FAILED_PACKAGE_CHANGED";
            case INSTALL_FAILED_UID_CHANGED: return "INSTALL_FAILED_UID_CHANGED";
            case INSTALL_FAILED_VERSION_DOWNGRADE: return "INSTALL_FAILED_VERSION_DOWNGRADE";
            case INSTALL_PARSE_FAILED_NOT_APK: return "INSTALL_PARSE_FAILED_NOT_APK";
            case INSTALL_PARSE_FAILED_BAD_MANIFEST: return "INSTALL_PARSE_FAILED_BAD_MANIFEST";
            case INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: return "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION";
            case INSTALL_PARSE_FAILED_NO_CERTIFICATES: return "INSTALL_PARSE_FAILED_NO_CERTIFICATES";
            case INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: return "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES";
            case INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: return "INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING";
            case INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: return "INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME";
            case INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: return "INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID";
            case INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: return "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED";
            case INSTALL_PARSE_FAILED_MANIFEST_EMPTY: return "INSTALL_PARSE_FAILED_MANIFEST_EMPTY";
            case INSTALL_FAILED_INTERNAL_ERROR: return "INSTALL_FAILED_INTERNAL_ERROR";
            case INSTALL_FAILED_USER_RESTRICTED: return "INSTALL_FAILED_USER_RESTRICTED";
            case INSTALL_FAILED_DUPLICATE_PERMISSION: return "INSTALL_FAILED_DUPLICATE_PERMISSION";
            case INSTALL_FAILED_NO_MATCHING_ABIS: return "INSTALL_FAILED_NO_MATCHING_ABIS";
            case INSTALL_FAILED_ABORTED: return "INSTALL_FAILED_ABORTED";
            case INSTALL_FAILED_BAD_DEX_METADATA: return "INSTALL_FAILED_BAD_DEX_METADATA";
            case INSTALL_FAILED_MISSING_SPLIT: return "INSTALL_FAILED_MISSING_SPLIT";
            case INSTALL_FAILED_BAD_SIGNATURE: return "INSTALL_FAILED_BAD_SIGNATURE";
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int installStatusToPublicStatus(int status) {
        switch (status) {
            case INSTALL_SUCCEEDED: return PackageInstaller.STATUS_SUCCESS;
            case INSTALL_FAILED_ALREADY_EXISTS: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_INVALID_APK: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INVALID_URI: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INSUFFICIENT_STORAGE: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_DUPLICATE_PACKAGE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_SHARED_USER: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_UPDATE_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_MISSING_SHARED_LIBRARY: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_REPLACE_COULDNT_DELETE: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_DEXOPT: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_OLDER_SDK: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONFLICTING_PROVIDER: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NEWER_SDK: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_TEST_ONLY: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_MISSING_FEATURE: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONTAINER_ERROR: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_INVALID_INSTALL_LOCATION: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_MEDIA_UNAVAILABLE: return PackageInstaller.STATUS_FAILURE_STORAGE;
            case INSTALL_FAILED_VERIFICATION_TIMEOUT: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_VERIFICATION_FAILURE: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_PACKAGE_CHANGED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_UID_CHANGED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_VERSION_DOWNGRADE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_PERMISSION_MODEL_DOWNGRADE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NOT_APK: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_MANIFEST: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NO_CERTIFICATES: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_EMPTY: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_BAD_DEX_METADATA: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_BAD_SIGNATURE: return PackageInstaller.STATUS_FAILURE_INVALID;
            case INSTALL_FAILED_INTERNAL_ERROR: return PackageInstaller.STATUS_FAILURE;
            case INSTALL_FAILED_USER_RESTRICTED: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_DUPLICATE_PERMISSION: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_MATCHING_ABIS: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_ABORTED: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case INSTALL_FAILED_MISSING_SPLIT: return PackageInstaller.STATUS_FAILURE_INCOMPATIBLE;
            default: return PackageInstaller.STATUS_FAILURE;
        }
    }

    /** {@hide} */
    public static String deleteStatusToString(int status, String msg) {
        final String str = deleteStatusToString(status);
        if (msg != null) {
            return str + ": " + msg;
        } else {
            return str;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage
    public static String deleteStatusToString(int status) {
        switch (status) {
            case DELETE_SUCCEEDED: return "DELETE_SUCCEEDED";
            case DELETE_FAILED_INTERNAL_ERROR: return "DELETE_FAILED_INTERNAL_ERROR";
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return "DELETE_FAILED_DEVICE_POLICY_MANAGER";
            case DELETE_FAILED_USER_RESTRICTED: return "DELETE_FAILED_USER_RESTRICTED";
            case DELETE_FAILED_OWNER_BLOCKED: return "DELETE_FAILED_OWNER_BLOCKED";
            case DELETE_FAILED_ABORTED: return "DELETE_FAILED_ABORTED";
            case DELETE_FAILED_USED_SHARED_LIBRARY: return "DELETE_FAILED_USED_SHARED_LIBRARY";
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int deleteStatusToPublicStatus(int status) {
        switch (status) {
            case DELETE_SUCCEEDED: return PackageInstaller.STATUS_SUCCESS;
            case DELETE_FAILED_INTERNAL_ERROR: return PackageInstaller.STATUS_FAILURE;
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_USER_RESTRICTED: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_OWNER_BLOCKED: return PackageInstaller.STATUS_FAILURE_BLOCKED;
            case DELETE_FAILED_ABORTED: return PackageInstaller.STATUS_FAILURE_ABORTED;
            case DELETE_FAILED_USED_SHARED_LIBRARY: return PackageInstaller.STATUS_FAILURE_CONFLICT;
            default: return PackageInstaller.STATUS_FAILURE;
        }
    }

    /** {@hide} */
    public static String permissionFlagToString(int flag) {
        switch (flag) {
            case FLAG_PERMISSION_GRANTED_BY_DEFAULT: return "GRANTED_BY_DEFAULT";
            case FLAG_PERMISSION_POLICY_FIXED: return "POLICY_FIXED";
            case FLAG_PERMISSION_SYSTEM_FIXED: return "SYSTEM_FIXED";
            case FLAG_PERMISSION_USER_SET: return "USER_SET";
            case FLAG_PERMISSION_REVOKE_ON_UPGRADE: return "REVOKE_ON_UPGRADE";
            case FLAG_PERMISSION_USER_FIXED: return "USER_FIXED";
            case FLAG_PERMISSION_REVIEW_REQUIRED: return "REVIEW_REQUIRED";
            case FLAG_PERMISSION_REVOKE_WHEN_REQUESTED: return "REVOKE_WHEN_REQUESTED";
            case FLAG_PERMISSION_USER_SENSITIVE_WHEN_GRANTED: return "USER_SENSITIVE_WHEN_GRANTED";
            case FLAG_PERMISSION_USER_SENSITIVE_WHEN_DENIED: return "USER_SENSITIVE_WHEN_DENIED";
            default: return Integer.toString(flag);
        }
    }

    /** {@hide} */
    public static class LegacyPackageDeleteObserver extends PackageDeleteObserver {
        private final IPackageDeleteObserver mLegacy;

        public LegacyPackageDeleteObserver(IPackageDeleteObserver legacy) {
            mLegacy = legacy;
        }

        @Override
        public void onPackageDeleted(String basePackageName, int returnCode, String msg) {
            if (mLegacy == null) return;
            try {
                mLegacy.packageDeleted(basePackageName, returnCode);
            } catch (RemoteException ignored) {
            }
        }
    }

    /**
     * Return the install reason that was recorded when a package was first
     * installed for a specific user. Requesting the install reason for another
     * user will require the permission INTERACT_ACROSS_USERS_FULL.
     *
     * @param packageName The package for which to retrieve the install reason
     * @param user The user for whom to retrieve the install reason
     * @return The install reason. If the package is not installed for the given
     *         user, {@code INSTALL_REASON_UNKNOWN} is returned.
     * @hide
     */
    @TestApi
    public abstract @InstallReason int getInstallReason(String packageName,
            @NonNull UserHandle user);

    /**
     * Checks whether the calling package is allowed to request package installs through package
     * installer. Apps are encouraged to call this API before launching the package installer via
     * intent {@link android.content.Intent#ACTION_INSTALL_PACKAGE}. Starting from Android O, the
     * user can explicitly choose what external sources they trust to install apps on the device.
     * If this API returns false, the install request will be blocked by the package installer and
     * a dialog will be shown to the user with an option to launch settings to change their
     * preference. An application must target Android O or higher and declare permission
     * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES} in order to use this API.
     *
     * @return true if the calling package is trusted by the user to request install packages on
     * the device, false otherwise.
     * @see android.content.Intent#ACTION_INSTALL_PACKAGE
     * @see android.provider.Settings#ACTION_MANAGE_UNKNOWN_APP_SOURCES
     */
    public abstract boolean canRequestPackageInstalls();

    /**
     * Return the {@link ComponentName} of the activity providing Settings for the Instant App
     * resolver.
     *
     * @see {@link android.content.Intent#ACTION_INSTANT_APP_RESOLVER_SETTINGS}
     * @hide
     */
    @SystemApi
    public abstract ComponentName getInstantAppResolverSettingsComponent();

    /**
     * Return the {@link ComponentName} of the activity responsible for installing instant
     * applications.
     *
     * @see {@link android.content.Intent#ACTION_INSTALL_INSTANT_APP_PACKAGE}
     * @hide
     */
    @SystemApi
    public abstract ComponentName getInstantAppInstallerComponent();

    /**
     * Return the Android Id for a given Instant App.
     *
     * @see {@link android.provider.Settings.Secure#ANDROID_ID}
     * @hide
     */
    public abstract String getInstantAppAndroidId(String packageName, @NonNull UserHandle user);

    /**
     * Callback use to notify the callers of module registration that the operation
     * has finished.
     *
     * @hide
     */
    @SystemApi
    public static abstract class DexModuleRegisterCallback {
        public abstract void onDexModuleRegistered(String dexModulePath, boolean success,
                String message);
    }

    /**
     * Register an application dex module with the package manager.
     * The package manager will keep track of the given module for future optimizations.
     *
     * Dex module optimizations will disable the classpath checking at runtime. The client bares
     * the responsibility to ensure that the static assumptions on classes in the optimized code
     * hold at runtime (e.g. there's no duplicate classes in the classpath).
     *
     * Note that the package manager already keeps track of dex modules loaded with
     * {@link dalvik.system.DexClassLoader} and {@link dalvik.system.PathClassLoader}.
     * This can be called for an eager registration.
     *
     * The call might take a while and the results will be posted on the main thread, using
     * the given callback.
     *
     * If the module is intended to be shared with other apps, make sure that the file
     * permissions allow for it.
     * If at registration time the permissions allow for others to read it, the module would
     * be marked as a shared module which might undergo a different optimization strategy.
     * (usually shared modules will generated larger optimizations artifacts,
     * taking more disk space).
     *
     * @param dexModulePath the absolute path of the dex module.
     * @param callback if not null, {@link DexModuleRegisterCallback#onDexModuleRegistered} will
     *                 be called once the registration finishes.
     *
     * @hide
     */
    @SystemApi
    public abstract void registerDexModule(String dexModulePath,
            @Nullable DexModuleRegisterCallback callback);

    /**
     * Returns the {@link ArtManager} associated with this package manager.
     *
     * @hide
     */
    @SystemApi
    public @NonNull ArtManager getArtManager() {
        throw new UnsupportedOperationException("getArtManager not implemented in subclass");
    }

    /**
     * Sets or clears the harmful app warning details for the given app.
     *
     * When set, any attempt to launch an activity in this package will be intercepted and a
     * warning dialog will be shown to the user instead, with the given warning. The user
     * will have the option to proceed with the activity launch, or to uninstall the application.
     *
     * @param packageName The full name of the package to warn on.
     * @param warning A warning string to display to the user describing the threat posed by the
     *                application, or null to clear the warning.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_HARMFUL_APP_WARNINGS)
    @SystemApi
    public void setHarmfulAppWarning(@NonNull String packageName, @Nullable CharSequence warning) {
        throw new UnsupportedOperationException("setHarmfulAppWarning not implemented in subclass");
    }

    /**
     * Returns the harmful app warning string for the given app, or null if there is none set.
     *
     * @param packageName The full name of the desired package.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_HARMFUL_APP_WARNINGS)
    @Nullable
    @SystemApi
    public CharSequence getHarmfulAppWarning(@NonNull String packageName) {
        throw new UnsupportedOperationException("getHarmfulAppWarning not implemented in subclass");
    }

    /** @hide */
    @IntDef(prefix = { "CERT_INPUT_" }, value = {
            CERT_INPUT_RAW_X509,
            CERT_INPUT_SHA256
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CertificateInputType {}

    /**
     * Certificate input bytes: the input bytes represent an encoded X.509 Certificate which could
     * be generated using an {@code CertificateFactory}
     */
    public static final int CERT_INPUT_RAW_X509 = 0;

    /**
     * Certificate input bytes: the input bytes represent the SHA256 output of an encoded X.509
     * Certificate.
     */
    public static final int CERT_INPUT_SHA256 = 1;

    /**
     * Searches the set of signing certificates by which the given package has proven to have been
     * signed.  This should be used instead of {@code getPackageInfo} with {@code GET_SIGNATURES}
     * since it takes into account the possibility of signing certificate rotation, except in the
     * case of packages that are signed by multiple certificates, for which signing certificate
     * rotation is not supported.  This method is analogous to using {@code getPackageInfo} with
     * {@code GET_SIGNING_CERTIFICATES} and then searching through the resulting {@code
     * signingInfo} field to see if the desired certificate is present.
     *
     * @param packageName package whose signing certificates to check
     * @param certificate signing certificate for which to search
     * @param type representation of the {@code certificate}
     * @return true if this package was or is signed by exactly the certificate {@code certificate}
     */
    public boolean hasSigningCertificate(
            String packageName, byte[] certificate, @CertificateInputType int type) {
        throw new UnsupportedOperationException(
                "hasSigningCertificate not implemented in subclass");
    }

    /**
     * Searches the set of signing certificates by which the package(s) for the given uid has proven
     * to have been signed.  For multiple packages sharing the same uid, this will return the
     * signing certificates found in the signing history of the "newest" package, where "newest"
     * indicates the package with the newest signing certificate in the shared uid group.  This
     * method should be used instead of {@code getPackageInfo} with {@code GET_SIGNATURES}
     * since it takes into account the possibility of signing certificate rotation, except in the
     * case of packages that are signed by multiple certificates, for which signing certificate
     * rotation is not supported. This method is analogous to using {@code getPackagesForUid}
     * followed by {@code getPackageInfo} with {@code GET_SIGNING_CERTIFICATES}, selecting the
     * {@code PackageInfo} of the newest-signed bpackage , and finally searching through the
     * resulting {@code signingInfo} field to see if the desired certificate is there.
     *
     * @param uid uid whose signing certificates to check
     * @param certificate signing certificate for which to search
     * @param type representation of the {@code certificate}
     * @return true if this package was or is signed by exactly the certificate {@code certificate}
     */
    public boolean hasSigningCertificate(
            int uid, byte[] certificate, @CertificateInputType int type) {
        throw new UnsupportedOperationException(
                "hasSigningCertificate not implemented in subclass");
    }

    /**
     * @return the system defined text classifier package name, or null if there's none.
     *
     * @hide
     */
    public String getSystemTextClassifierPackageName() {
        throw new UnsupportedOperationException(
                "getSystemTextClassifierPackageName not implemented in subclass");
    }

    /**
     * @return the wellbeing app package name, or null if it's not defined by the OEM.
     *
     * @hide
     */
    @TestApi
    public String getWellbeingPackageName() {
        throw new UnsupportedOperationException(
                "getWellbeingPackageName not implemented in subclass");
    }

    /**
     * @return the system defined app predictor package name, or null if there's none.
     *
     * @hide
     */
    public String getAppPredictionServicePackageName() {
        throw new UnsupportedOperationException(
            "getAppPredictionServicePackageName not implemented in subclass");
    }

    /**
     * @return the system defined content capture service package name, or null if there's none.
     *
     * @hide
     */
    public String getSystemCaptionsServicePackageName() {
        throw new UnsupportedOperationException(
                "getSystemCaptionsServicePackageName not implemented in subclass");
    }

    /**
     * @return the incident report approver app package name, or null if it's not defined
     * by the OEM.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public String getIncidentReportApproverPackageName() {
        throw new UnsupportedOperationException(
                "getIncidentReportApproverPackageName not implemented in subclass");
    }

    /**
     * @return whether a given package's state is protected, e.g. package cannot be disabled,
     *         suspended, hidden or force stopped.
     *
     * @hide
     */
    public boolean isPackageStateProtected(String packageName, int userId) {
        throw new UnsupportedOperationException(
            "isPackageStateProtected not implemented in subclass");
    }

    /**
     * Notify to the rest of the system that a new device configuration has
     * been prepared and that it is time to refresh caches.
     *
     * @see android.content.Intent#ACTION_DEVICE_CUSTOMIZATION_READY
     *
     * @hide
     */
    @SystemApi
    public void sendDeviceCustomizationReadyBroadcast() {
        throw new UnsupportedOperationException(
            "sendDeviceCustomizationReadyBroadcast not implemented in subclass");
    }

}
