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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.app.PackageDeleteObserver;
import android.app.PackageInstallObserver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInstaller.CommitCallback;
import android.content.pm.PackageInstaller.UninstallCallback;
import android.content.pm.PackageParser.PackageParserException;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.AndroidException;

import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Class for retrieving various kinds of information related to the application
 * packages that are currently installed on the device.
 *
 * You can find this class through {@link Context#getPackageManager}.
 */
public abstract class PackageManager {

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
     */
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
     * {@link PackageInfo} flag: include disabled components in the returned info.
     */
    public static final int GET_DISABLED_COMPONENTS     = 0x00000200;

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
     * Flag parameter to retrieve some information about all applications (even
     * uninstalled ones) which have data directories. This state could have
     * resulted if applications have been deleted with flag
     * {@code DONT_DELETE_DATA} with a possibility of being replaced or
     * reinstalled in future.
     * <p>
     * Note: this flag may cause less information about currently installed
     * applications to be returned.
     */
    public static final int GET_UNINSTALLED_PACKAGES = 0x00002000;

    /**
     * {@link PackageInfo} flag: return information about
     * hardware preferences in
     * {@link PackageInfo#configPreferences PackageInfo.configPreferences},
     * and requested features in {@link PackageInfo#reqFeatures} and
     * {@link PackageInfo#featureGroups}.
     */
    public static final int GET_CONFIGURATIONS = 0x00004000;

    /**
     * {@link PackageInfo} flag: include disabled components which are in
     * that state only because of {@link #COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED}
     * in the returned info.  Note that if you set this flag, applications
     * that are in this disabled state will be reported as enabled.
     */
    public static final int GET_DISABLED_UNTIL_USED_COMPONENTS = 0x00008000;

    /**
     * Resolution and querying flag: if set, only filters that support the
     * {@link android.content.Intent#CATEGORY_DEFAULT} will be considered for
     * matching.  This is a synonym for including the CATEGORY_DEFAULT in your
     * supplied Intent.
     */
    public static final int MATCH_DEFAULT_ONLY   = 0x00010000;

    /**
     * Resolution and querying flag: do not resolve intents cross-profile.
     * @hide
     */
    public static final int NO_CROSS_PROFILE = 0x00020000;

    /**
     * Flag for {@link addCrossProfileIntentFilter}: if this flag is set:
     * when resolving an intent that matches the {@link CrossProfileIntentFilter}, the current
     * profile will be skipped.
     * Only activities in the target user can respond to the intent.
     * @hide
     */
    public static final int SKIP_CURRENT_PROFILE = 0x00000002;

    /** @hide */
    @IntDef({PERMISSION_GRANTED, PERMISSION_DENIED})
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

    /**
     * Flag for {@link #setApplicationEnabledSetting(String, int, int)}
     * and {@link #setComponentEnabledSetting(ComponentName, int, int)}: This
     * component or application is in its default enabled state (as specified
     * in its manifest).
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

    /**
     * Flag parameter for {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} to
     * indicate that this package should be installed as forward locked, i.e. only the app itself
     * should have access to its code and non-resource assets.
     * @hide
     */
    public static final int INSTALL_FORWARD_LOCK = 0x00000001;

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to replace an already
     * installed package, if one exists.
     * @hide
     */
    public static final int INSTALL_REPLACE_EXISTING = 0x00000002;

    /**
     * Flag parameter for {@link #installPackage} to indicate that you want to
     * allow test packages (those that have set android:testOnly in their
     * manifest) to be installed.
     * @hide
     */
    public static final int INSTALL_ALLOW_TEST = 0x00000004;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this
     * package has to be installed on the sdcard.
     * @hide
     */
    public static final int INSTALL_EXTERNAL = 0x00000008;

    /**
     * Flag parameter for {@link #installPackage} to indicate that this package
     * has to be installed on the sdcard.
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
     * Flag parameter for {@link #installPackage} to indicate that it is okay
     * to install an update to an app where the newly installed app has a lower
     * version code than the currently installed app.
     *
     * @hide
     */
    public static final int INSTALL_ALLOW_DOWNGRADE = 0x00000080;

    /**
     * Flag parameter for
     * {@link #setComponentEnabledSetting(android.content.ComponentName, int, int)} to indicate
     * that you don't want to kill the app containing the component.  Be careful when you set this
     * since changing component states can make the containing application's behavior unpredictable.
     */
    public static final int DONT_KILL_APP = 0x00000001;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} on success.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_SUCCEEDED = 1;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if the package is
     * already installed.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_ALREADY_EXISTS = -1;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if the package archive
     * file is invalid.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_APK = -2;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if the URI passed in
     * is invalid.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_URI = -3;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if the package manager
     * service found that the device didn't have enough storage space to install the app.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INSUFFICIENT_STORAGE = -4;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if a
     * package is already installed with the same name.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DUPLICATE_PACKAGE = -5;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the requested shared user does not exist.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NO_SHARED_USER = -6;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * a previously installed package of the same name has a different signature
     * than the new package (and the old package's data was not removed).
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_UPDATE_INCOMPATIBLE = -7;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package is requested a shared user which is already installed on the
     * device and does not have matching signature.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_SHARED_USER_INCOMPATIBLE = -8;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package uses a shared library that is not available.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_SHARED_LIBRARY = -9;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package uses a shared library that is not available.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_REPLACE_COULDNT_DELETE = -10;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package failed while optimizing and validating its dex files,
     * either because there was not enough storage or the validation failed.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_DEXOPT = -11;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package failed because the current SDK version is older than
     * that required by the package.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_OLDER_SDK = -12;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package failed because it contains a content provider with the
     * same authority as a provider already installed in the system.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONFLICTING_PROVIDER = -13;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package failed because the current SDK version is newer than
     * that required by the package.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_NEWER_SDK = -14;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package failed because it has specified that it is a test-only
     * package and the caller has not supplied the {@link #INSTALL_ALLOW_TEST}
     * flag.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_TEST_ONLY = -15;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the package being installed contains native code, but none that is
     * compatible with the device's CPU_ABI.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CPU_ABI_INCOMPATIBLE = -16;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package uses a feature that is not available.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MISSING_FEATURE = -17;

    // ------ Errors related to sdcard
    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * a secure container mount point couldn't be accessed on external media.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_CONTAINER_ERROR = -18;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package couldn't be installed in the specified install
     * location.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INVALID_INSTALL_LOCATION = -19;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package couldn't be installed in the specified install
     * location because the media is not available.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_MEDIA_UNAVAILABLE = -20;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package couldn't be installed because the verification timed out.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_TIMEOUT = -21;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package couldn't be installed because the verification did not succeed.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_VERIFICATION_FAILURE = -22;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the package changed from what the calling program expected.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_PACKAGE_CHANGED = -23;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package is assigned a different UID than it previously held.
     * @hide
     */
    public static final int INSTALL_FAILED_UID_CHANGED = -24;

    /**
     * Installation return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)} if
     * the new package has an older version code than the currently installed package.
     * @hide
     */
    public static final int INSTALL_FAILED_VERSION_DOWNGRADE = -25;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser was given a path that is not a file, or does not end with the expected
     * '.apk' extension.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NOT_APK = -100;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser was unable to retrieve the AndroidManifest.xml file.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_MANIFEST = -101;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser encountered an unexpected exception.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION = -102;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser did not find any certificates in the .apk.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_NO_CERTIFICATES = -103;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser found inconsistent certificates on the files in the .apk.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES = -104;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser encountered a CertificateEncodingException in one of the
     * files in the .apk.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING = -105;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser encountered a bad or missing package name in the manifest.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME = -106;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser encountered a bad shared user id name in the manifest.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID = -107;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser encountered some structural problem in the manifest.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_MALFORMED = -108;

    /**
     * Installation parse return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the parser did not find any actionable tags (instrumentation or application)
     * in the manifest.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_PARSE_FAILED_MANIFEST_EMPTY = -109;

    /**
     * Installation failed return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the system failed to install the package because of system issues.
     * @hide
     */
    @SystemApi
    public static final int INSTALL_FAILED_INTERNAL_ERROR = -110;

    /**
     * Installation failed return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the system failed to install the package because the user is restricted from installing
     * apps.
     * @hide
     */
    public static final int INSTALL_FAILED_USER_RESTRICTED = -111;

    /**
     * Installation failed return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the system failed to install the package because it is attempting to define a
     * permission that is already defined by some existing package.
     *
     * <p>The package name of the app which has already defined the permission is passed to
     * a {@link PackageInstallObserver}, if any, as the {@link #EXTRA_EXISTING_PACKAGE}
     * string extra; and the name of the permission being redefined is passed in the
     * {@link #EXTRA_EXISTING_PERMISSION} string extra.
     * @hide
     */
    public static final int INSTALL_FAILED_DUPLICATE_PERMISSION = -112;

    /**
     * Installation failed return code: this is passed to the {@link IPackageInstallObserver} by
     * {@link #installPackage(android.net.Uri, IPackageInstallObserver, int)}
     * if the system failed to install the package because its packaged native code did not
     * match any of the ABIs supported by the system.
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
    public static final int NO_NATIVE_LIBRARIES = -114;

    /** {@hide} */
    public static final int INSTALL_FAILED_ABORTED = -115;

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
     * Return code for when package deletion succeeds. This is passed to the
     * {@link IPackageDeleteObserver} by {@link #deletePackage()} if the system
     * succeeded in deleting the package.
     *
     * @hide
     */
    public static final int DELETE_SUCCEEDED = 1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} by {@link #deletePackage()} if the system
     * failed to delete the package for an unspecified reason.
     *
     * @hide
     */
    public static final int DELETE_FAILED_INTERNAL_ERROR = -1;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} by {@link #deletePackage()} if the system
     * failed to delete the package because it is the active DevicePolicy
     * manager.
     *
     * @hide
     */
    public static final int DELETE_FAILED_DEVICE_POLICY_MANAGER = -2;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} by {@link #deletePackage()} if the system
     * failed to delete the package since the user is restricted.
     *
     * @hide
     */
    public static final int DELETE_FAILED_USER_RESTRICTED = -3;

    /**
     * Deletion failed return code: this is passed to the
     * {@link IPackageDeleteObserver} by {@link #deletePackage()} if the system
     * failed to delete the package because a profile
     * or device owner has marked the package as uninstallable.
     *
     * @hide
     */
    public static final int DELETE_FAILED_OWNER_BLOCKED = -4;

    /** {@hide} */
    public static final int DELETE_FAILED_ABORTED = -5;

    /**
     * Return code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)} when the
     * package has been successfully moved by the system.
     *
     * @hide
     */
    public static final int MOVE_SUCCEEDED = 1;
    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * when the package hasn't been successfully moved by the system
     * because of insufficient memory on specified media.
     * @hide
     */
    public static final int MOVE_FAILED_INSUFFICIENT_STORAGE = -1;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * if the specified package doesn't exist.
     * @hide
     */
    public static final int MOVE_FAILED_DOESNT_EXIST = -2;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * if the specified package cannot be moved since its a system package.
     * @hide
     */
    public static final int MOVE_FAILED_SYSTEM_PACKAGE = -3;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * if the specified package cannot be moved since its forward locked.
     * @hide
     */
    public static final int MOVE_FAILED_FORWARD_LOCKED = -4;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * if the specified package cannot be moved to the specified location.
     * @hide
     */
    public static final int MOVE_FAILED_INVALID_LOCATION = -5;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)}
     * if the specified package cannot be moved to the specified location.
     * @hide
     */
    public static final int MOVE_FAILED_INTERNAL_ERROR = -6;

    /**
     * Error code that is passed to the {@link IPackageMoveObserver} by
     * {@link #movePackage(android.net.Uri, IPackageMoveObserver)} if the
     * specified package already has an operation pending in the
     * {@link PackageHandler} queue.
     *
     * @hide
     */
    public static final int MOVE_FAILED_OPERATION_PENDING = -7;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to internal storage if its
     * been installed on external media.
     * @hide
     */
    public static final int MOVE_INTERNAL = 0x00000001;

    /**
     * Flag parameter for {@link #movePackage} to indicate that
     * the package should be moved to external media.
     * @hide
     */
    public static final int MOVE_EXTERNAL_MEDIA = 0x00000002;

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
     * Feature for {@link #getSystemAvailableFeatures} and
     * {@link #hasSystemFeature}: The device is capable of communicating with
     * consumer IR devices.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_CONSUMER_IR = "android.hardware.consumerir";

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
     * {@link #hasSystemFeature}: The device supports the OpenGL ES
     * <a href="http://www.khronos.org/registry/gles/extensions/ANDROID/ANDROID_extension_pack_es31a.txt">
     * Android Extension Pack</a>.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_OPENGLES_EXTENSION_PACK = "android.hardware.opengles.aep";

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
     * @hide
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_LEANBACK_ONLY = "android.software.leanback_only";

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
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports printing.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_PRINTING = "android.software.print";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device can perform backup and restore operations on installed applications.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_BACKUP = "android.software.backup";

    /**
     * Feature for {@link #getSystemAvailableFeatures} and {@link #hasSystemFeature}:
     * The device supports managed profiles for enterprise users.
     */
    @SdkConstant(SdkConstantType.FEATURE)
    public static final String FEATURE_MANAGED_PROFILES = "android.software.managed_profiles";

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
     * @hide
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
     * Action to external storage service to clean out removed apps.
     * @hide
     */
    public static final String ACTION_CLEAN_EXTERNAL_STORAGE
            = "android.content.pm.CLEAN_EXTERNAL_STORAGE";

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
     *
     * @hide
     */
    public static final String EXTRA_VERIFICATION_VERSION_CODE
            = "android.content.pm.extra.VERIFICATION_VERSION_CODE";

    /**
     * The action used to request that the user approve a permission request
     * from the application.
     *
     * @hide
     */
    public static final String ACTION_REQUEST_PERMISSION
            = "android.content.pm.action.REQUEST_PERMISSION";

    /**
     * Extra field name for the list of permissions, which the user must approve.
     *
     * @hide
     */
    public static final String EXTRA_REQUEST_PERMISSION_PERMISSION_LIST
            = "android.content.pm.extra.PERMISSION_LIST";

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
     * Retrieve overall information about an application package that is
     * installed on the system.
     * <p>
     * Throws {@link NameNotFoundException} if a package with the given name can
     * not be found on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @param flags Additional option flags. Use any combination of
     *            {@link #GET_ACTIVITIES}, {@link #GET_GIDS},
     *            {@link #GET_CONFIGURATIONS}, {@link #GET_INSTRUMENTATION},
     *            {@link #GET_PERMISSIONS}, {@link #GET_PROVIDERS},
     *            {@link #GET_RECEIVERS}, {@link #GET_SERVICES},
     *            {@link #GET_SIGNATURES}, {@link #GET_UNINSTALLED_PACKAGES} to
     *            modify the data returned.
     * @return Returns a PackageInfo object containing information about the
     *         package. If flag GET_UNINSTALLED_PACKAGES is set and if the
     *         package is not found in the list of installed applications, the
     *         package information is retrieved from the list of uninstalled
     *         applications (which includes installed applications as well as
     *         applications with data directory i.e. applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     * @see #GET_UNINSTALLED_PACKAGES
     */
    public abstract PackageInfo getPackageInfo(String packageName, int flags)
            throws NameNotFoundException;

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
    public abstract Intent getLaunchIntentForPackage(String packageName);

    /**
     * Return a "good" intent to launch a front-door Leanback activity in a
     * package, for use for example to implement an "open" button when browsing
     * through packages. The current implementation will look for a main
     * activity in the category {@link Intent#CATEGORY_LEANBACK_LAUNCHER}, or
     * return null if no main leanback activities are found.
     * <p>
     * Throws {@link NameNotFoundException} if a package with the given name
     * cannot be found on the system.
     *
     * @param packageName The name of the package to inspect.
     * @return Returns either a fully-qualified Intent that can be used to launch
     *         the main Leanback activity in the package, or null if the package
     *         does not contain such an activity.
     */
    public abstract Intent getLeanbackLaunchIntentForPackage(String packageName);

    /**
     * Return an array of all of the secondary group-ids that have been assigned
     * to a package.
     * <p>
     * Throws {@link NameNotFoundException} if a package with the given name
     * cannot be found on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *            desired package.
     * @return Returns an int array of the assigned gids, or null if there are
     *         none.
     */
    public abstract int[] getPackageGids(String packageName)
            throws NameNotFoundException;

    /**
     * @hide Return the uid associated with the given package name for the
     * given user.
     *
     * <p>Throws {@link NameNotFoundException} if a package with the given
     * name can not be found on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of the
     *                    desired package.
     * @param userHandle The user handle identifier to look up the package under.
     *
     * @return Returns an integer uid who owns the given package name.
     */
    public abstract int getPackageUid(String packageName, int userHandle)
            throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular permission.
     *
     * <p>Throws {@link NameNotFoundException} if a permission with the given
     * name cannot be found on the system.
     *
     * @param name The fully qualified name (i.e. com.google.permission.LOGIN)
     *             of the permission you are interested in.
     * @param flags Additional option flags.  Use {@link #GET_META_DATA} to
     * retrieve any meta-data associated with the permission.
     *
     * @return Returns a {@link PermissionInfo} containing information about the
     *         permission.
     */
    public abstract PermissionInfo getPermissionInfo(String name, int flags)
            throws NameNotFoundException;

    /**
     * Query for all of the permissions associated with a particular group.
     *
     * <p>Throws {@link NameNotFoundException} if the given group does not
     * exist.
     *
     * @param group The fully qualified name (i.e. com.google.permission.LOGIN)
     *             of the permission group you are interested in.  Use null to
     *             find all of the permissions not associated with a group.
     * @param flags Additional option flags.  Use {@link #GET_META_DATA} to
     * retrieve any meta-data associated with the permissions.
     *
     * @return Returns a list of {@link PermissionInfo} containing information
     * about all of the permissions in the given group.
     */
    public abstract List<PermissionInfo> queryPermissionsByGroup(String group,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular group of
     * permissions.
     *
     * <p>Throws {@link NameNotFoundException} if a permission group with the given
     * name cannot be found on the system.
     *
     * @param name The fully qualified name (i.e. com.google.permission_group.APPS)
     *             of the permission you are interested in.
     * @param flags Additional option flags.  Use {@link #GET_META_DATA} to
     * retrieve any meta-data associated with the permission group.
     *
     * @return Returns a {@link PermissionGroupInfo} containing information
     * about the permission.
     */
    public abstract PermissionGroupInfo getPermissionGroupInfo(String name,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the known permission groups in the system.
     *
     * @param flags Additional option flags.  Use {@link #GET_META_DATA} to
     * retrieve any meta-data associated with the permission group.
     *
     * @return Returns a list of {@link PermissionGroupInfo} containing
     * information about all of the known permission groups.
     */
    public abstract List<PermissionGroupInfo> getAllPermissionGroups(int flags);

    /**
     * Retrieve all of the information we know about a particular
     * package/application.
     *
     * <p>Throws {@link NameNotFoundException} if an application with the given
     * package name cannot be found on the system.
     *
     * @param packageName The full name (i.e. com.google.apps.contacts) of an
     *                    application.
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * {@link #GET_UNINSTALLED_PACKAGES} to modify the data returned.
     *
     * @return  {@link ApplicationInfo} Returns ApplicationInfo object containing
     *         information about the package.
     *         If flag GET_UNINSTALLED_PACKAGES is set and  if the package is not
     *         found in the list of installed applications,
     *         the application information is retrieved from the
     *         list of uninstalled applications(which includes
     *         installed applications as well as applications
     *         with data directory ie applications which had been
     *         deleted with {@code DONT_DELETE_DATA} flag set).
     *
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     * @see #GET_UNINSTALLED_PACKAGES
     */
    public abstract ApplicationInfo getApplicationInfo(String packageName,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular activity
     * class.
     *
     * <p>Throws {@link NameNotFoundException} if an activity with the given
     * class name cannot be found on the system.
     *
     * @param component The full component name (i.e.
     * com.google.apps.contacts/com.google.apps.contacts.ContactsList) of an Activity
     * class.
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * to modify the data (in ApplicationInfo) returned.
     *
     * @return {@link ActivityInfo} containing information about the activity.
     *
     * @see #GET_INTENT_FILTERS
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     */
    public abstract ActivityInfo getActivityInfo(ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular receiver
     * class.
     *
     * <p>Throws {@link NameNotFoundException} if a receiver with the given
     * class name cannot be found on the system.
     *
     * @param component The full component name (i.e.
     * com.google.apps.calendar/com.google.apps.calendar.CalendarAlarm) of a Receiver
     * class.
     * @param flags Additional option flags.  Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * to modify the data returned.
     *
     * @return {@link ActivityInfo} containing information about the receiver.
     *
     * @see #GET_INTENT_FILTERS
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     */
    public abstract ActivityInfo getReceiverInfo(ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular service
     * class.
     *
     * <p>Throws {@link NameNotFoundException} if a service with the given
     * class name cannot be found on the system.
     *
     * @param component The full component name (i.e.
     * com.google.apps.media/com.google.apps.media.BackgroundPlayback) of a Service
     * class.
     * @param flags Additional option flags.  Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * to modify the data returned.
     *
     * @return ServiceInfo containing information about the service.
     *
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     */
    public abstract ServiceInfo getServiceInfo(ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * Retrieve all of the information we know about a particular content
     * provider class.
     *
     * <p>Throws {@link NameNotFoundException} if a provider with the given
     * class name cannot be found on the system.
     *
     * @param component The full component name (i.e.
     * com.google.providers.media/com.google.providers.media.MediaProvider) of a
     * ContentProvider class.
     * @param flags Additional option flags.  Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * to modify the data returned.
     *
     * @return ProviderInfo containing information about the service.
     *
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     */
    public abstract ProviderInfo getProviderInfo(ComponentName component,
            int flags) throws NameNotFoundException;

    /**
     * Return a List of all packages that are installed
     * on the device.
     *
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_ACTIVITIES},
     * {@link #GET_GIDS},
     * {@link #GET_CONFIGURATIONS},
     * {@link #GET_INSTRUMENTATION},
     * {@link #GET_PERMISSIONS},
     * {@link #GET_PROVIDERS},
     * {@link #GET_RECEIVERS},
     * {@link #GET_SERVICES},
     * {@link #GET_SIGNATURES},
     * {@link #GET_UNINSTALLED_PACKAGES} to modify the data returned.
     *
     * @return A List of PackageInfo objects, one for each package that is
     *         installed on the device.  In the unlikely case of there being no
     *         installed packages, an empty list is returned.
     *         If flag GET_UNINSTALLED_PACKAGES is set, a list of all
     *         applications including those deleted with {@code DONT_DELETE_DATA}
     *         (partially installed apps with data directory) will be returned.
     *
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     * @see #GET_UNINSTALLED_PACKAGES
     */
    public abstract List<PackageInfo> getInstalledPackages(int flags);

    /**
     * Return a List of all installed packages that are currently
     * holding any of the given permissions.
     *
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_ACTIVITIES},
     * {@link #GET_GIDS},
     * {@link #GET_CONFIGURATIONS},
     * {@link #GET_INSTRUMENTATION},
     * {@link #GET_PERMISSIONS},
     * {@link #GET_PROVIDERS},
     * {@link #GET_RECEIVERS},
     * {@link #GET_SERVICES},
     * {@link #GET_SIGNATURES},
     * {@link #GET_UNINSTALLED_PACKAGES} to modify the data returned.
     *
     * @return Returns a List of PackageInfo objects, one for each installed
     * application that is holding any of the permissions that were provided.
     *
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     * @see #GET_UNINSTALLED_PACKAGES
     */
    public abstract List<PackageInfo> getPackagesHoldingPermissions(
            String[] permissions, int flags);

    /**
     * Return a List of all packages that are installed on the device, for a specific user.
     * Requesting a list of installed packages for another user
     * will require the permission INTERACT_ACROSS_USERS_FULL.
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_ACTIVITIES},
     * {@link #GET_GIDS},
     * {@link #GET_CONFIGURATIONS},
     * {@link #GET_INSTRUMENTATION},
     * {@link #GET_PERMISSIONS},
     * {@link #GET_PROVIDERS},
     * {@link #GET_RECEIVERS},
     * {@link #GET_SERVICES},
     * {@link #GET_SIGNATURES},
     * {@link #GET_UNINSTALLED_PACKAGES} to modify the data returned.
     * @param userId The user for whom the installed packages are to be listed
     *
     * @return A List of PackageInfo objects, one for each package that is
     *         installed on the device.  In the unlikely case of there being no
     *         installed packages, an empty list is returned.
     *         If flag GET_UNINSTALLED_PACKAGES is set, a list of all
     *         applications including those deleted with {@code DONT_DELETE_DATA}
     *         (partially installed apps with data directory) will be returned.
     *
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     * @see #GET_UNINSTALLED_PACKAGES
     *
     * @hide
     */
    public abstract List<PackageInfo> getInstalledPackages(int flags, int userId);

    /**
     * Check whether a particular package has been granted a particular
     * permission.
     *
     * @param permName The name of the permission you are checking for,
     * @param pkgName The name of the package you are checking against.
     *
     * @return If the package has the permission, PERMISSION_GRANTED is
     * returned.  If it does not have the permission, PERMISSION_DENIED
     * is returned.
     *
     * @see #PERMISSION_GRANTED
     * @see #PERMISSION_DENIED
     */
    public abstract int checkPermission(String permName, String pkgName);

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
     * Returns an {@link Intent} suitable for passing to {@code startActivityForResult}
     * which prompts the user to grant {@code permissions} to this application.
     * @hide
     *
     * @throws NullPointerException if {@code permissions} is {@code null}.
     * @throws IllegalArgumentException if {@code permissions} contains {@code null}.
     */
    public Intent buildPermissionRequestIntent(String... permissions) {
        if (permissions == null) {
            throw new NullPointerException("permissions cannot be null");
        }
        for (String permission : permissions) {
            if (permission == null) {
                throw new IllegalArgumentException("permissions cannot contain null");
            }
        }

        Intent i = new Intent(ACTION_REQUEST_PERMISSION);
        i.putExtra(EXTRA_REQUEST_PERMISSION_PERMISSION_LIST, permissions);
        i.setPackage("com.android.packageinstaller");
        return i;
    }

    /**
     * Grant a permission to an application which the application does not
     * already have.  The permission must have been requested by the application,
     * but as an optional permission.  If the application is not allowed to
     * hold the permission, a SecurityException is thrown.
     * @hide
     *
     * @param packageName The name of the package that the permission will be
     * granted to.
     * @param permissionName The name of the permission.
     */
    public abstract void grantPermission(String packageName, String permissionName);

    /**
     * Revoke a permission that was previously granted by {@link #grantPermission}.
     * @hide
     *
     * @param packageName The name of the package that the permission will be
     * granted to.
     * @param permissionName The name of the permission.
     */
    public abstract void revokePermission(String packageName, String permissionName);

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
     * @see #SIGNATURE_MATCH
     * @see #SIGNATURE_NO_MATCH
     * @see #SIGNATURE_UNKNOWN_PACKAGE
     */
    public abstract int checkSignatures(String pkg1, String pkg2);

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
     * @see #SIGNATURE_MATCH
     * @see #SIGNATURE_NO_MATCH
     * @see #SIGNATURE_UNKNOWN_PACKAGE
     */
    public abstract int checkSignatures(int uid1, int uid2);

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
    public abstract String[] getPackagesForUid(int uid);

    /**
     * Retrieve the official name associated with a user id.  This name is
     * guaranteed to never change, though it is possibly for the underlying
     * user id to be changed.  That is, if you are storing information about
     * user ids in persistent storage, you should use the string returned
     * by this function instead of the raw user-id.
     *
     * @param uid The user id for which you would like to retrieve a name.
     * @return Returns a unique name for the given user id, or null if the
     * user id is not currently assigned.
     */
    public abstract String getNameForUid(int uid);

    /**
     * Return the user id associated with a shared user name. Multiple
     * applications can specify a shared user name in their manifest and thus
     * end up using a common uid. This might be used for new applications
     * that use an existing shared user name and need to know the uid of the
     * shared user.
     *
     * @param sharedUserName The shared user name whose uid is to be retrieved.
     * @return Returns the uid associated with the shared user, or  NameNotFoundException
     * if the shared user name is not being used by any installed packages
     * @hide
     */
    public abstract int getUidForSharedUser(String sharedUserName)
            throws NameNotFoundException;

    /**
     * Return a List of all application packages that are installed on the
     * device. If flag GET_UNINSTALLED_PACKAGES has been set, a list of all
     * applications including those deleted with {@code DONT_DELETE_DATA} (partially
     * installed apps with data directory) will be returned.
     *
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_META_DATA}, {@link #GET_SHARED_LIBRARY_FILES},
     * {@link #GET_UNINSTALLED_PACKAGES} to modify the data returned.
     *
     * @return Returns a List of ApplicationInfo objects, one for each application that
     *         is installed on the device.  In the unlikely case of there being
     *         no installed applications, an empty list is returned.
     *         If flag GET_UNINSTALLED_PACKAGES is set, a list of all
     *         applications including those deleted with {@code DONT_DELETE_DATA}
     *         (partially installed apps with data directory) will be returned.
     *
     * @see #GET_META_DATA
     * @see #GET_SHARED_LIBRARY_FILES
     * @see #GET_UNINSTALLED_PACKAGES
     */
    public abstract List<ApplicationInfo> getInstalledApplications(int flags);

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
     * Get a list of features that are available on the
     * system.
     *
     * @return An array of FeatureInfo classes describing the features
     * that are available on the system, or null if there are none(!!).
     */
    public abstract FeatureInfo[] getSystemAvailableFeatures();

    /**
     * Check whether the given feature name is one of the available
     * features as returned by {@link #getSystemAvailableFeatures()}.
     *
     * @return Returns true if the devices supports the feature, else
     * false.
     */
    public abstract boolean hasSystemFeature(String name);

    /**
     * Determine the best action to perform for a given Intent.  This is how
     * {@link Intent#resolveActivity} finds an activity if a class has not
     * been explicitly specified.
     *
     * <p><em>Note:</em> if using an implicit Intent (without an explicit ComponentName
     * specified), be sure to consider whether to set the {@link #MATCH_DEFAULT_ONLY}
     * only flag.  You need to do so to resolve the activity in the same way
     * that {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.</p>
     *
     * @param intent An intent containing all of the desired specification
     *               (action, data, type, category, and/or component).
     * @param flags Additional option flags.  The most important is
     * {@link #MATCH_DEFAULT_ONLY}, to limit the resolution to only
     * those activities that support the {@link android.content.Intent#CATEGORY_DEFAULT}.
     *
     * @return Returns a ResolveInfo containing the final activity intent that
     *         was determined to be the best action.  Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo
     *         containing something else, such as the activity resolver.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract ResolveInfo resolveActivity(Intent intent, int flags);

    /**
     * Determine the best action to perform for a given Intent for a given user. This
     * is how {@link Intent#resolveActivity} finds an activity if a class has not
     * been explicitly specified.
     *
     * <p><em>Note:</em> if using an implicit Intent (without an explicit ComponentName
     * specified), be sure to consider whether to set the {@link #MATCH_DEFAULT_ONLY}
     * only flag.  You need to do so to resolve the activity in the same way
     * that {@link android.content.Context#startActivity(Intent)} and
     * {@link android.content.Intent#resolveActivity(PackageManager)
     * Intent.resolveActivity(PackageManager)} do.</p>
     *
     * @param intent An intent containing all of the desired specification
     *               (action, data, type, category, and/or component).
     * @param flags Additional option flags.  The most important is
     * {@link #MATCH_DEFAULT_ONLY}, to limit the resolution to only
     * those activities that support the {@link android.content.Intent#CATEGORY_DEFAULT}.
     * @param userId The user id.
     *
     * @return Returns a ResolveInfo containing the final activity intent that
     *         was determined to be the best action.  Returns null if no
     *         matching activity was found. If multiple matching activities are
     *         found and there is no default set, returns a ResolveInfo
     *         containing something else, such as the activity resolver.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     *
     * @hide
     */
    public abstract ResolveInfo resolveActivityAsUser(Intent intent, int flags, int userId);

    /**
     * Retrieve all activities that can be performed for the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags.  The most important is
     * {@link #MATCH_DEFAULT_ONLY}, to limit the resolution to only
     * those activities that support the {@link android.content.Intent#CATEGORY_DEFAULT}.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         Activity. These are ordered from best to worst match -- that
     *         is, the first item in the list is what is returned by
     *         {@link #resolveActivity}.  If there are no matching activities, an empty
     *         list is returned.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract List<ResolveInfo> queryIntentActivities(Intent intent,
            int flags);

    /**
     * Retrieve all activities that can be performed for the given intent, for a specific user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags.  The most important is
     * {@link #MATCH_DEFAULT_ONLY}, to limit the resolution to only
     * those activities that support the {@link android.content.Intent#CATEGORY_DEFAULT}.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         Activity. These are ordered from best to worst match -- that
     *         is, the first item in the list is what is returned by
     *         {@link #resolveActivity}.  If there are no matching activities, an empty
     *         list is returned.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     * @see #NO_CROSS_PROFILE
     * @hide
     */
    public abstract List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent,
            int flags, int userId);


    /**
     * Retrieve a set of activities that should be presented to the user as
     * similar options.  This is like {@link #queryIntentActivities}, except it
     * also allows you to supply a list of more explicit Intents that you would
     * like to resolve to particular options, and takes care of returning the
     * final ResolveInfo list in a reasonable order, with no duplicates, based
     * on those inputs.
     *
     * @param caller The class name of the activity that is making the
     *               request.  This activity will never appear in the output
     *               list.  Can be null.
     * @param specifics An array of Intents that should be resolved to the
     *                  first specific results.  Can be null.
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags.  The most important is
     * {@link #MATCH_DEFAULT_ONLY}, to limit the resolution to only
     * those activities that support the {@link android.content.Intent#CATEGORY_DEFAULT}.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         Activity. These are ordered first by all of the intents resolved
     *         in <var>specifics</var> and then any additional activities that
     *         can handle <var>intent</var> but did not get included by one of
     *         the <var>specifics</var> intents.  If there are no matching
     *         activities, an empty list is returned.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract List<ResolveInfo> queryIntentActivityOptions(
            ComponentName caller, Intent[] specifics, Intent intent, int flags);

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         Receiver. These are ordered from first to last in priority.  If
     *         there are no matching receivers, an empty list is returned.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract List<ResolveInfo> queryBroadcastReceivers(Intent intent,
            int flags);

    /**
     * Retrieve all receivers that can handle a broadcast of the given intent, for a specific
     * user.
     *
     * @param intent The desired intent as per resolveActivity().
     * @param flags Additional option flags.
     * @param userId The userId of the user being queried.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         Receiver. These are ordered from first to last in priority.  If
     *         there are no matching receivers, an empty list is returned.
     *
     * @see #MATCH_DEFAULT_ONLY
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     * @hide
     */
    public abstract List<ResolveInfo> queryBroadcastReceivers(Intent intent,
            int flags, int userId);

    /**
     * Determine the best service to handle for a given Intent.
     *
     * @param intent An intent containing all of the desired specification
     *               (action, data, type, category, and/or component).
     * @param flags Additional option flags.
     *
     * @return Returns a ResolveInfo containing the final service intent that
     *         was determined to be the best action.  Returns null if no
     *         matching service was found.
     *
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract ResolveInfo resolveService(Intent intent, int flags);

    /**
     * Retrieve all services that can match the given intent.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         ServiceInfo. These are ordered from best to worst match -- that
     *         is, the first item in the list is what is returned by
     *         resolveService().  If there are no matching services, an empty
     *         list is returned.
     *
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract List<ResolveInfo> queryIntentServices(Intent intent,
            int flags);

    /**
     * Retrieve all services that can match the given intent for a given user.
     *
     * @param intent The desired intent as per resolveService().
     * @param flags Additional option flags.
     * @param userId The user id.
     *
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         ServiceInfo. These are ordered from best to worst match -- that
     *         is, the first item in the list is what is returned by
     *         resolveService().  If there are no matching services, an empty
     *         list is returned.
     *
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     *
     * @hide
     */
    public abstract List<ResolveInfo> queryIntentServicesAsUser(Intent intent,
            int flags, int userId);

    /** {@hide} */
    public abstract List<ResolveInfo> queryIntentContentProvidersAsUser(
            Intent intent, int flags, int userId);

    /**
     * Retrieve all providers that can match the given intent.
     *
     * @param intent An intent containing all of the desired specification
     *            (action, data, type, category, and/or component).
     * @param flags Additional option flags.
     * @return A List&lt;ResolveInfo&gt; containing one entry for each matching
     *         ProviderInfo. These are ordered from best to worst match. If
     *         there are no matching providers, an empty list is returned.
     * @see #GET_INTENT_FILTERS
     * @see #GET_RESOLVED_FILTER
     */
    public abstract List<ResolveInfo> queryIntentContentProviders(Intent intent, int flags);

    /**
     * Find a single content provider by its base path name.
     *
     * @param name The name of the provider to find.
     * @param flags Additional option flags.  Currently should always be 0.
     *
     * @return ContentProviderInfo Information about the provider, if found,
     *         else null.
     */
    public abstract ProviderInfo resolveContentProvider(String name,
            int flags);

    /**
     * Find a single content provider by its base path name.
     *
     * @param name The name of the provider to find.
     * @param flags Additional option flags.  Currently should always be 0.
     * @param userId The user id.
     *
     * @return ContentProviderInfo Information about the provider, if found,
     *         else null.
     * @hide
     */
    public abstract ProviderInfo resolveContentProviderAsUser(String name, int flags, int userId);

    /**
     * Retrieve content provider information.
     *
     * <p><em>Note: unlike most other methods, an empty result set is indicated
     * by a null return instead of an empty list.</em>
     *
     * @param processName If non-null, limits the returned providers to only
     *                    those that are hosted by the given process.  If null,
     *                    all content providers are returned.
     * @param uid If <var>processName</var> is non-null, this is the required
     *        uid owning the requested content providers.
     * @param flags Additional option flags.  Currently should always be 0.
     *
     * @return A List&lt;ContentProviderInfo&gt; containing one entry for each
     *         content provider either patching <var>processName</var> or, if
     *         <var>processName</var> is null, all known content providers.
     *         <em>If there are no matching providers, null is returned.</em>
     */
    public abstract List<ProviderInfo> queryContentProviders(
            String processName, int uid, int flags);

    /**
     * Retrieve all of the information we know about a particular
     * instrumentation class.
     *
     * <p>Throws {@link NameNotFoundException} if instrumentation with the
     * given class name cannot be found on the system.
     *
     * @param className The full name (i.e.
     *                  com.google.apps.contacts.InstrumentList) of an
     *                  Instrumentation class.
     * @param flags Additional option flags.  Currently should always be 0.
     *
     * @return InstrumentationInfo containing information about the
     *         instrumentation.
     */
    public abstract InstrumentationInfo getInstrumentationInfo(
            ComponentName className, int flags) throws NameNotFoundException;

    /**
     * Retrieve information about available instrumentation code.  May be used
     * to retrieve either all instrumentation code, or only the code targeting
     * a particular package.
     *
     * @param targetPackage If null, all instrumentation is returned; only the
     *                      instrumentation targeting this package name is
     *                      returned.
     * @param flags Additional option flags.  Currently should always be 0.
     *
     * @return A List&lt;InstrumentationInfo&gt; containing one entry for each
     *         matching available Instrumentation.  Returns an empty list if
     *         there is no instrumentation available for the given package.
     */
    public abstract List<InstrumentationInfo> queryInstrumentation(
            String targetPackage, int flags);

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
    public abstract Drawable getDrawable(String packageName, int resid,
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
    public abstract CharSequence getText(String packageName, int resid,
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
    public abstract XmlResourceParser getXml(String packageName, int resid,
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
    public abstract Resources getResourcesForApplicationAsUser(String appPackageName, int userId)
            throws NameNotFoundException;

    /**
     * Retrieve overall information about an application package defined
     * in a package archive file
     *
     * @param archiveFilePath The path to the archive file
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_ACTIVITIES},
     * {@link #GET_GIDS},
     * {@link #GET_CONFIGURATIONS},
     * {@link #GET_INSTRUMENTATION},
     * {@link #GET_PERMISSIONS},
     * {@link #GET_PROVIDERS},
     * {@link #GET_RECEIVERS},
     * {@link #GET_SERVICES},
     * {@link #GET_SIGNATURES}, to modify the data returned.
     *
     * @return Returns the information about the package. Returns
     * null if the package could not be successfully parsed.
     *
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     *
     */
    public PackageInfo getPackageArchiveInfo(String archiveFilePath, int flags) {
        final PackageParser parser = new PackageParser();
        final File apkFile = new File(archiveFilePath);
        try {
            PackageParser.Package pkg = parser.parseMonolithicPackage(apkFile, 0);
            if ((flags & GET_SIGNATURES) != 0) {
                parser.collectCertificates(pkg, 0);
                parser.collectManifestDigest(pkg);
            }
            PackageUserState state = new PackageUserState();
            return PackageParser.generatePackageInfo(pkg, null, flags, 0, 0, null, state);
        } catch (PackageParserException e) {
            return null;
        }
    }

    /**
     * @hide Install a package. Since this may take a little while, the result
     *       will be posted back to the given observer. An installation will
     *       fail if the calling context lacks the
     *       {@link android.Manifest.permission#INSTALL_PACKAGES} permission, if
     *       the package named in the package file's manifest is already
     *       installed, or if there's no space available on the device.
     * @param packageURI The location of the package file to install. This can
     *            be a 'file:' or a 'content:' URI.
     * @param observer An observer callback to get notified when the package
     *            installation is complete.
     *            {@link IPackageInstallObserver#packageInstalled(String, int)}
     *            will be called when that happens. This parameter must not be
     *            null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     *            {@link #INSTALL_REPLACE_EXISTING},
     *            {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that
     *            is performing the installation. This identifies which market
     *            the package came from.
     * @deprecated Use {@link #installPackage(Uri, PackageInstallObserver, int,
     *             String)} instead. This method will continue to be supported
     *             but the older observer interface will not get additional
     *             failure details.
     */
    // @SystemApi
    public abstract void installPackage(
            Uri packageURI, IPackageInstallObserver observer, int flags,
            String installerPackageName);

    /**
     * Similar to
     * {@link #installPackage(Uri, IPackageInstallObserver, int, String)} but
     * with an extra verification file provided.
     *
     * @param packageURI The location of the package file to install. This can
     *            be a 'file:' or a 'content:' URI.
     * @param observer An observer callback to get notified when the package
     *            installation is complete.
     *            {@link IPackageInstallObserver#packageInstalled(String, int)}
     *            will be called when that happens. This parameter must not be
     *            null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     *            {@link #INSTALL_REPLACE_EXISTING},
     *            {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that
     *            is performing the installation. This identifies which market
     *            the package came from.
     * @param verificationURI The location of the supplementary verification
     *            file. This can be a 'file:' or a 'content:' URI. May be
     *            {@code null}.
     * @param manifestDigest an object that holds the digest of the package
     *            which can be used to verify ownership. May be {@code null}.
     * @param encryptionParams if the package to be installed is encrypted,
     *            these parameters describing the encryption and authentication
     *            used. May be {@code null}.
     * @hide
     * @deprecated Use {@link #installPackageWithVerification(Uri,
     *             PackageInstallObserver, int, String, Uri, ManifestDigest,
     *             ContainerEncryptionParams)} instead. This method will
     *             continue to be supported but the older observer interface
     *             will not get additional failure details.
     */
    // @SystemApi
    public abstract void installPackageWithVerification(Uri packageURI,
            IPackageInstallObserver observer, int flags, String installerPackageName,
            Uri verificationURI, ManifestDigest manifestDigest,
            ContainerEncryptionParams encryptionParams);

    /**
     * Similar to
     * {@link #installPackage(Uri, IPackageInstallObserver, int, String)} but
     * with an extra verification information provided.
     *
     * @param packageURI The location of the package file to install. This can
     *            be a 'file:' or a 'content:' URI.
     * @param observer An observer callback to get notified when the package
     *            installation is complete.
     *            {@link IPackageInstallObserver#packageInstalled(String, int)}
     *            will be called when that happens. This parameter must not be
     *            null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     *            {@link #INSTALL_REPLACE_EXISTING},
     *            {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that
     *            is performing the installation. This identifies which market
     *            the package came from.
     * @param verificationParams an object that holds signal information to
     *            assist verification. May be {@code null}.
     * @param encryptionParams if the package to be installed is encrypted,
     *            these parameters describing the encryption and authentication
     *            used. May be {@code null}.
     * @hide
     * @deprecated Use {@link #installPackageWithVerificationAndEncryption(Uri,
     *             PackageInstallObserver, int, String, VerificationParams,
     *             ContainerEncryptionParams)} instead. This method will
     *             continue to be supported but the older observer interface
     *             will not get additional failure details.
     */
    @Deprecated
    public abstract void installPackageWithVerificationAndEncryption(Uri packageURI,
            IPackageInstallObserver observer, int flags, String installerPackageName,
            VerificationParams verificationParams,
            ContainerEncryptionParams encryptionParams);

    // Package-install variants that take the new, expanded form of observer interface.
    // Note that these *also* take the original observer type and will redundantly
    // report the same information to that observer if supplied; but it is not required.

    /**
     * @hide
     *
     * Install a package. Since this may take a little while, the result will
     * be posted back to the given observer.  An installation will fail if the calling context
     * lacks the {@link android.Manifest.permission#INSTALL_PACKAGES} permission, if the
     * package named in the package file's manifest is already installed, or if there's no space
     * available on the device.
     *
     * @param packageURI The location of the package file to install.  This can be a 'file:' or a
     * 'content:' URI.
     * @param observer An observer callback to get notified when the package installation is
     * complete. {@link PackageInstallObserver#packageInstalled(String, Bundle, int)} will be
     * called when that happens. This parameter must not be null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     * {@link #INSTALL_REPLACE_EXISTING}, {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that is performing the
     * installation. This identifies which market the package came from.
     */
    public abstract void installPackage(
            Uri packageURI, PackageInstallObserver observer,
            int flags, String installerPackageName);

    /**
     * Similar to
     * {@link #installPackage(Uri, IPackageInstallObserver, int, String)} but
     * with an extra verification file provided.
     *
     * @param packageURI The location of the package file to install. This can
     *            be a 'file:' or a 'content:' URI.
     * @param observer An observer callback to get notified when the package installation is
     * complete. {@link PackageInstallObserver#packageInstalled(String, Bundle, int)} will be
     * called when that happens. This parameter must not be null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     *            {@link #INSTALL_REPLACE_EXISTING}, {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that
     *            is performing the installation. This identifies which market
     *            the package came from.
     * @param verificationURI The location of the supplementary verification
     *            file. This can be a 'file:' or a 'content:' URI. May be
     *            {@code null}.
     * @param manifestDigest an object that holds the digest of the package
     *            which can be used to verify ownership. May be {@code null}.
     * @param encryptionParams if the package to be installed is encrypted,
     *            these parameters describing the encryption and authentication
     *            used. May be {@code null}.
     * @hide
     */
    public abstract void installPackageWithVerification(Uri packageURI,
            PackageInstallObserver observer, int flags, String installerPackageName,
            Uri verificationURI, ManifestDigest manifestDigest,
            ContainerEncryptionParams encryptionParams);

    /**
     * Similar to
     * {@link #installPackage(Uri, IPackageInstallObserver, int, String)} but
     * with an extra verification information provided.
     *
     * @param packageURI The location of the package file to install. This can
     *            be a 'file:' or a 'content:' URI.
     * @param observer An observer callback to get notified when the package installation is
     * complete. {@link PackageInstallObserver#packageInstalled(String, Bundle, int)} will be
     * called when that happens. This parameter must not be null.
     * @param flags - possible values: {@link #INSTALL_FORWARD_LOCK},
     *            {@link #INSTALL_REPLACE_EXISTING}, {@link #INSTALL_ALLOW_TEST}.
     * @param installerPackageName Optional package name of the application that
     *            is performing the installation. This identifies which market
     *            the package came from.
     * @param verificationParams an object that holds signal information to
     *            assist verification. May be {@code null}.
     * @param encryptionParams if the package to be installed is encrypted,
     *            these parameters describing the encryption and authentication
     *            used. May be {@code null}.
     *
     * @hide
     */
    public abstract void installPackageWithVerificationAndEncryption(Uri packageURI,
            PackageInstallObserver observer, int flags, String installerPackageName,
            VerificationParams verificationParams, ContainerEncryptionParams encryptionParams);

    /**
     * If there is already an application with the given package name installed
     * on the system for other users, also install it for the calling user.
     * @hide
     */
    // @SystemApi
    public abstract int installExistingPackage(String packageName)
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

    /**
     * Attempts to delete a package.  Since this may take a little while, the result will
     * be posted back to the given observer.  A deletion will fail if the calling context
     * lacks the {@link android.Manifest.permission#DELETE_PACKAGES} permission, if the
     * named package cannot be found, or if the named package is a "system package".
     * (TODO: include pointer to documentation on "system packages")
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package deletion is
     * complete. {@link android.content.pm.IPackageDeleteObserver#packageDeleted(boolean)} will be
     * called when that happens.  observer may be null to indicate that no callback is desired.
     * @param flags - possible values: {@link #DELETE_KEEP_DATA},
     * {@link #DELETE_ALL_USERS}.
     *
     * @hide
     */
    // @SystemApi
    public abstract void deletePackage(
            String packageName, IPackageDeleteObserver observer, int flags);

    /**
     * Retrieve the package name of the application that installed a package. This identifies
     * which market the package came from.
     *
     * @param packageName The name of the package to query
     */
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
    public abstract void deleteApplicationCacheFiles(String packageName,
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
    // @SystemApi
    public abstract void freeStorageAndNotify(long freeStorageSize, IPackageDataObserver observer);

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
    public abstract void freeStorage(long freeStorageSize, IntentSender pi);

    /**
     * Retrieve the size information for a package.
     * Since this may take a little while, the result will
     * be posted back to the given observer.  The calling context
     * should have the {@link android.Manifest.permission#GET_PACKAGE_SIZE} permission.
     *
     * @param packageName The name of the package whose size information is to be retrieved
     * @param userHandle The user whose size information should be retrieved.
     * @param observer An observer callback to get notified when the operation
     * is complete.
     * {@link android.content.pm.IPackageStatsObserver#onGetStatsCompleted(PackageStats, boolean)}
     * The observer's callback is invoked with a PackageStats object(containing the
     * code, data and cache sizes of the package) and a boolean value representing
     * the status of the operation. observer may be null to indicate that
     * no callback is desired.
     *
     * @hide
     */
    public abstract void getPackageSizeInfo(String packageName, int userHandle,
            IPackageStatsObserver observer);

    /**
     * Like {@link #getPackageSizeInfo(String, int, IPackageStatsObserver)}, but
     * returns the size for the calling user.
     *
     * @hide
     */
    public void getPackageSizeInfo(String packageName, IPackageStatsObserver observer) {
        getPackageSizeInfo(packageName, UserHandle.myUserId(), observer);
    }

    /**
     * @deprecated This function no longer does anything; it was an old
     * approach to managing preferred activities, which has been superseded
     * by (and conflicts with) the modern activity-based preferences.
     */
    @Deprecated
    public abstract void addPackageToPreferred(String packageName);

    /**
     * @deprecated This function no longer does anything; it was an old
     * approach to managing preferred activities, which has been superseded
     * by (and conflicts with) the modern activity-based preferences.
     */
    @Deprecated
    public abstract void removePackageFromPreferred(String packageName);

    /**
     * Retrieve the list of all currently configured preferred packages.  The
     * first package on the list is the most preferred, the last is the
     * least preferred.
     *
     * @param flags Additional option flags. Use any combination of
     * {@link #GET_ACTIVITIES},
     * {@link #GET_GIDS},
     * {@link #GET_CONFIGURATIONS},
     * {@link #GET_INSTRUMENTATION},
     * {@link #GET_PERMISSIONS},
     * {@link #GET_PROVIDERS},
     * {@link #GET_RECEIVERS},
     * {@link #GET_SERVICES},
     * {@link #GET_SIGNATURES}, to modify the data returned.
     *
     * @return Returns a list of PackageInfo objects describing each
     * preferred application, in order of preference.
     *
     * @see #GET_ACTIVITIES
     * @see #GET_GIDS
     * @see #GET_CONFIGURATIONS
     * @see #GET_INSTRUMENTATION
     * @see #GET_PERMISSIONS
     * @see #GET_PROVIDERS
     * @see #GET_RECEIVERS
     * @see #GET_SERVICES
     * @see #GET_SIGNATURES
     */
    public abstract List<PackageInfo> getPreferredPackages(int flags);

    /**
     * @deprecated This is a protected API that should not have been available
     * to third party applications.  It is the platform's responsibility for
     * assigning preferred activities and this cannot be directly modified.
     *
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
     */
    @Deprecated
    public abstract void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity);

    /**
     * Same as {@link #addPreferredActivity(IntentFilter, int,
            ComponentName[], ComponentName)}, but with a specific userId to apply the preference
            to.
     * @hide
     */
    public void addPreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity, int userId) {
        throw new RuntimeException("Not implemented. Must override in a subclass.");
    }

    /**
     * @deprecated This is a protected API that should not have been available
     * to third party applications.  It is the platform's responsibility for
     * assigning preferred activities and this cannot be directly modified.
     *
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
     * @hide
     */
    @Deprecated
    public abstract void replacePreferredActivity(IntentFilter filter, int match,
            ComponentName[] set, ComponentName activity);

    /**
     * @hide
     */
    @Deprecated
    public void replacePreferredActivityAsUser(IntentFilter filter, int match,
           ComponentName[] set, ComponentName activity, int userId) {
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
     */
    public abstract void clearPackagePreferredActivities(String packageName);

    /**
     * Retrieve all preferred activities, previously added with
     * {@link #addPreferredActivity}, that are
     * currently registered with the system.
     *
     * @param outFilters A list in which to place the filters of all of the
     * preferred activities, or null for none.
     * @param outActivities A list in which to place the component names of
     * all of the preferred activities, or null for none.
     * @param packageName An option package in which you would like to limit
     * the list.  If null, all activities will be returned; if non-null, only
     * those activities in the given package are returned.
     *
     * @return Returns the total number of registered preferred activities
     * (the number of distinct IntentFilter records, not the number of unique
     * activity components) that were found.
     */
    public abstract int getPreferredActivities(List<IntentFilter> outFilters,
            List<ComponentName> outActivities, String packageName);

    /**
     * Ask for the set of available 'home' activities and the current explicit
     * default, if any.
     * @hide
     */
    public abstract ComponentName getHomeActivities(List<ResolveInfo> outActivities);

    /**
     * Set the enabled setting for a package component (activity, receiver, service, provider).
     * This setting will override any enabled state which may have been set by the component in its
     * manifest.
     *
     * @param componentName The component to enable
     * @param newState The new enabled state for the component.  The legal values for this state
     *                 are:
     *                   {@link #COMPONENT_ENABLED_STATE_ENABLED},
     *                   {@link #COMPONENT_ENABLED_STATE_DISABLED}
     *                   and
     *                   {@link #COMPONENT_ENABLED_STATE_DEFAULT}
     *                 The last one removes the setting, thereby restoring the component's state to
     *                 whatever was set in it's manifest (or enabled, by default).
     * @param flags Optional behavior flags: {@link #DONT_KILL_APP} or 0.
     */
    public abstract void setComponentEnabledSetting(ComponentName componentName,
            int newState, int flags);


    /**
     * Return the enabled setting for a package component (activity,
     * receiver, service, provider).  This returns the last value set by
     * {@link #setComponentEnabledSetting(ComponentName, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param componentName The component to retrieve.
     * @return Returns the current enabled state for the component.  May
     * be one of {@link #COMPONENT_ENABLED_STATE_ENABLED},
     * {@link #COMPONENT_ENABLED_STATE_DISABLED}, or
     * {@link #COMPONENT_ENABLED_STATE_DEFAULT}.  The last one means the
     * component's enabled state is based on the original information in
     * the manifest as found in {@link ComponentInfo}.
     */
    public abstract int getComponentEnabledSetting(ComponentName componentName);

    /**
     * Set the enabled setting for an application
     * This setting will override any enabled state which may have been set by the application in
     * its manifest.  It also overrides the enabled state set in the manifest for any of the
     * application's components.  It does not override any enabled state set by
     * {@link #setComponentEnabledSetting} for any of the application's components.
     *
     * @param packageName The package name of the application to enable
     * @param newState The new enabled state for the component.  The legal values for this state
     *                 are:
     *                   {@link #COMPONENT_ENABLED_STATE_ENABLED},
     *                   {@link #COMPONENT_ENABLED_STATE_DISABLED}
     *                   and
     *                   {@link #COMPONENT_ENABLED_STATE_DEFAULT}
     *                 The last one removes the setting, thereby restoring the applications's state to
     *                 whatever was set in its manifest (or enabled, by default).
     * @param flags Optional behavior flags: {@link #DONT_KILL_APP} or 0.
     */
    public abstract void setApplicationEnabledSetting(String packageName,
            int newState, int flags);

    /**
     * Return the enabled setting for an application. This returns
     * the last value set by
     * {@link #setApplicationEnabledSetting(String, int, int)}; in most
     * cases this value will be {@link #COMPONENT_ENABLED_STATE_DEFAULT} since
     * the value originally specified in the manifest has not been modified.
     *
     * @param packageName The package name of the application to retrieve.
     * @return Returns the current enabled state for the application.  May
     * be one of {@link #COMPONENT_ENABLED_STATE_ENABLED},
     * {@link #COMPONENT_ENABLED_STATE_DISABLED}, or
     * {@link #COMPONENT_ENABLED_STATE_DEFAULT}.  The last one means the
     * application's enabled state is based on the original information in
     * the manifest as found in {@link ComponentInfo}.
     * @throws IllegalArgumentException if the named package does not exist.
     */
    public abstract int getApplicationEnabledSetting(String packageName);

    /**
     * Puts the package in a hidden state, which is almost like an uninstalled state,
     * making the package unavailable, but it doesn't remove the data or the actual
     * package file. Application can be unhidden by either resetting the hidden state
     * or by installing it, such as with {@link #installExistingPackage(String)}
     * @hide
     */
    public abstract boolean setApplicationHiddenSettingAsUser(String packageName, boolean hidden,
            UserHandle userHandle);

    /**
     * Returns the hidden state of a package.
     * @see #setApplicationHiddenSettingAsUser(String, boolean, UserHandle)
     * @hide
     */
    public abstract boolean getApplicationHiddenSettingAsUser(String packageName,
            UserHandle userHandle);

    /**
     * Return whether the device has been booted into safe mode.
     */
    public abstract boolean isSafeMode();

    /**
     * Return the {@link KeySet} associated with the String alias for this
     * application.
     *
     * @param alias The alias for a given {@link KeySet} as defined in the
     *        application's AndroidManifest.xml.
     */
    public abstract KeySet getKeySetByAlias(String packageName, String alias);

    /** Return the signing {@link KeySet} for this application. */
    public abstract KeySet getSigningKeySet(String packageName);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of the keys specified by the {@link KeySet} ks.  This will return true if
     * the package has been signed by additional keys (a superset) as well.
     * Compare to {@link #isSignedByExactly(String packageName, KeySet ks)}.
     */
    public abstract boolean isSignedBy(String packageName, KeySet ks);

    /**
     * Return whether the package denoted by packageName has been signed by all
     * of, and only, the keys specified by the {@link KeySet} ks. Compare to
     * {@link #isSignedBy(String packageName, KeySet ks)}.
     */
    public abstract boolean isSignedByExactly(String packageName, KeySet ks);

    /**
     * Attempts to move package resources from internal to external media or vice versa.
     * Since this may take a little while, the result will
     * be posted back to the given observer.   This call may fail if the calling context
     * lacks the {@link android.Manifest.permission#MOVE_PACKAGE} permission, if the
     * named package cannot be found, or if the named package is a "system package".
     *
     * @param packageName The name of the package to delete
     * @param observer An observer callback to get notified when the package move is
     * complete. {@link android.content.pm.IPackageMoveObserver#packageMoved(boolean)} will be
     * called when that happens.  observer may be null to indicate that no callback is desired.
     * @param flags To indicate install location {@link #MOVE_INTERNAL} or
     * {@link #MOVE_EXTERNAL_MEDIA}
     *
     * @hide
     */
    public abstract void movePackage(
            String packageName, IPackageMoveObserver observer, int flags);

    /**
     * Returns the device identity that verifiers can use to associate their scheme to a particular
     * device. This should not be used by anything other than a package verifier.
     *
     * @return identity that uniquely identifies current device
     * @hide
     */
    public abstract VerifierDeviceIdentity getVerifierDeviceIdentity();

    /**
     * Return interface that offers the ability to install, upgrade, and remove
     * applications on the device.
     */
    public abstract @NonNull PackageInstaller getPackageInstaller();

    /**
     * Returns the data directory for a particular user and package, given the uid of the package.
     * @param uid uid of the package, including the userId and appId
     * @param packageName name of the package
     * @return the user-specific data directory for the package
     * @hide
     */
    public static String getDataDirForUser(int userId, String packageName) {
        // TODO: This should be shared with Installer's knowledge of user directory
        return Environment.getDataDirectory().toString() + "/user/" + userId
                + "/" + packageName;
    }

    /**
     * Adds a {@link CrossProfileIntentFilter}. After calling this method all intents sent from the
     * user with id sourceUserId can also be be resolved by activities in the user with id
     * targetUserId if they match the specified intent filter.
     * @param filter The {@link IntentFilter} the intent has to match
     * @param sourceUserId The source user id.
     * @param targetUserId The target user id.
     * @param flags The only possible value is {@link SKIP_CURRENT_PROFILE}
     * @hide
     */
    public abstract void addCrossProfileIntentFilter(IntentFilter filter, int sourceUserId,
            int targetUserId, int flags);

    /**
     * Clearing {@link CrossProfileIntentFilter}s which have the specified user as their
     * source, and have been set by the app calling this method.
     * @param sourceUserId The source user id.
     * @hide
     */
    public abstract void clearCrossProfileIntentFilters(int sourceUserId);

    /**
     * Forwards all intents for {@link packageName} for user {@link sourceUserId} to user
     * {@link targetUserId}.
     * @hide
     */
    public abstract void addCrossProfileIntentsForPackage(String packageName,
            int sourceUserId, int targetUserId);

    /**
     * Removes all intents for {@link packageName} for user {@link sourceUserId} to user
     * {@link targetUserId}.
     * @hide
     */
    public abstract void removeCrossProfileIntentsForPackage(String packageName,
            int sourceUserId, int targetUserId);

    /**
     * @hide
     */
    public abstract Drawable loadItemIcon(PackageItemInfo itemInfo, ApplicationInfo appInfo);

    /** {@hide} */
    public abstract boolean isPackageAvailable(String packageName);

    /** {@hide} */
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
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int installStatusToFailureReason(int status) {
        switch (status) {
            case INSTALL_FAILED_ALREADY_EXISTS: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_INVALID_APK: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_INVALID_URI: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_INSUFFICIENT_STORAGE: return CommitCallback.FAILURE_STORAGE;
            case INSTALL_FAILED_DUPLICATE_PACKAGE: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_SHARED_USER: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_UPDATE_INCOMPATIBLE: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_MISSING_SHARED_LIBRARY: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_REPLACE_COULDNT_DELETE: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_DEXOPT: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_OLDER_SDK: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONFLICTING_PROVIDER: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_NEWER_SDK: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_TEST_ONLY: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_CPU_ABI_INCOMPATIBLE: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_MISSING_FEATURE: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_CONTAINER_ERROR: return CommitCallback.FAILURE_STORAGE;
            case INSTALL_FAILED_INVALID_INSTALL_LOCATION: return CommitCallback.FAILURE_STORAGE;
            case INSTALL_FAILED_MEDIA_UNAVAILABLE: return CommitCallback.FAILURE_STORAGE;
            case INSTALL_FAILED_VERIFICATION_TIMEOUT: return CommitCallback.FAILURE_ABORTED;
            case INSTALL_FAILED_VERIFICATION_FAILURE: return CommitCallback.FAILURE_ABORTED;
            case INSTALL_FAILED_PACKAGE_CHANGED: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_UID_CHANGED: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_VERSION_DOWNGRADE: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NOT_APK: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_MANIFEST: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_NO_CERTIFICATES: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_CERTIFICATE_ENCODING: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_MALFORMED: return CommitCallback.FAILURE_INVALID;
            case INSTALL_PARSE_FAILED_MANIFEST_EMPTY: return CommitCallback.FAILURE_INVALID;
            case INSTALL_FAILED_INTERNAL_ERROR: return CommitCallback.FAILURE_UNKNOWN;
            case INSTALL_FAILED_USER_RESTRICTED: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_DUPLICATE_PERMISSION: return CommitCallback.FAILURE_CONFLICT;
            case INSTALL_FAILED_NO_MATCHING_ABIS: return CommitCallback.FAILURE_INCOMPATIBLE;
            case INSTALL_FAILED_ABORTED: return CommitCallback.FAILURE_ABORTED;
            default: return CommitCallback.FAILURE_UNKNOWN;
        }
    }

    /** {@hide} */
    public static String deleteStatusToString(int status) {
        switch (status) {
            case DELETE_SUCCEEDED: return "DELETE_SUCCEEDED";
            case DELETE_FAILED_INTERNAL_ERROR: return "DELETE_FAILED_INTERNAL_ERROR";
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return "DELETE_FAILED_DEVICE_POLICY_MANAGER";
            case DELETE_FAILED_USER_RESTRICTED: return "DELETE_FAILED_USER_RESTRICTED";
            case DELETE_FAILED_OWNER_BLOCKED: return "DELETE_FAILED_OWNER_BLOCKED";
            case DELETE_FAILED_ABORTED: return "DELETE_FAILED_ABORTED";
            default: return Integer.toString(status);
        }
    }

    /** {@hide} */
    public static int deleteStatusToFailureReason(int status) {
        switch (status) {
            case DELETE_FAILED_INTERNAL_ERROR: return UninstallCallback.FAILURE_UNKNOWN;
            case DELETE_FAILED_DEVICE_POLICY_MANAGER: return UninstallCallback.FAILURE_BLOCKED;
            case DELETE_FAILED_USER_RESTRICTED: return UninstallCallback.FAILURE_BLOCKED;
            case DELETE_FAILED_OWNER_BLOCKED: return UninstallCallback.FAILURE_BLOCKED;
            case DELETE_FAILED_ABORTED: return UninstallCallback.FAILURE_ABORTED;
            default: return UninstallCallback.FAILURE_UNKNOWN;
        }
    }

    /** {@hide} */
    public static class LegacyPackageInstallObserver extends PackageInstallObserver {
        private final IPackageInstallObserver mLegacy;

        public LegacyPackageInstallObserver(IPackageInstallObserver legacy) {
            mLegacy = legacy;
        }

        @Override
        public void onPackageInstalled(String basePackageName, int returnCode, String msg,
                Bundle extras) {
            if (mLegacy == null) return;
            try {
                mLegacy.packageInstalled(basePackageName, returnCode);
            } catch (RemoteException ignored) {
            }
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
}
