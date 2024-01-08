/*
 * Copyright (C) 2014 The Android Open Source Project
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

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.content.pm.Checksum.TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256;
import static android.content.pm.Checksum.TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512;
import static android.content.pm.Checksum.TYPE_WHOLE_MD5;
import static android.content.pm.Checksum.TYPE_WHOLE_MERKLE_ROOT_4K_SHA256;
import static android.content.pm.Checksum.TYPE_WHOLE_SHA1;
import static android.content.pm.Checksum.TYPE_WHOLE_SHA256;
import static android.content.pm.Checksum.TYPE_WHOLE_SHA512;
import static android.content.pm.PackageInfo.INSTALL_LOCATION_AUTO;
import static android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
import static android.content.pm.PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.CurrentTimeMillisLong;
import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager.DeleteFlags;
import android.content.pm.PackageManager.InstallReason;
import android.content.pm.PackageManager.InstallScenario;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.graphics.Bitmap;
import android.icu.util.ULocale;
import android.net.Uri;
import android.os.Build;
import android.os.FileBridge;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ParcelableException;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.ExceptionUtils;

import com.android.internal.content.InstallLocationUtils;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Offers the ability to install, upgrade, and remove applications on the
 * device. This includes support for apps packaged either as a single
 * "monolithic" APK, or apps packaged as multiple "split" APKs.
 * <p>
 * An app is delivered for installation through a
 * {@link PackageInstaller.Session}, which any app can create. Once the session
 * is created, the installer can stream one or more APKs into place until it
 * decides to either commit or destroy the session. Committing may require user
 * intervention to complete the installation, unless the caller falls into one of the
 * following categories, in which case the installation will complete automatically.
 * <ul>
 * <li>the device owner
 * <li>the affiliated profile owner
 * </ul>
 * <p>
 * Sessions can install brand new apps, upgrade existing apps, or add new splits
 * into an existing app.
 * <p>
 * Apps packaged as multiple split APKs always consist of a single "base" APK
 * (with a {@code null} split name) and zero or more "split" APKs (with unique
 * split names). Any subset of these APKs can be installed together, as long as
 * the following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All APKs must have unique split names.
 * <li>All installations must contain a single base APK.
 * </ul>
 * <p>
 * The ApiDemos project contains examples of using this API:
 * <code>ApiDemos/src/com/example/android/apis/content/InstallApk*.java</code>.
 */
public class PackageInstaller {
    private static final String TAG = "PackageInstaller";

    private static final String ACTION_WAIT_INSTALL_CONSTRAINTS =
            "android.content.pm.action.WAIT_INSTALL_CONSTRAINTS";

    /** {@hide} */
    public static final boolean ENABLE_REVOCABLE_FD =
            SystemProperties.getBoolean("fw.revocable_fd", false);

    /**
     * Activity Action: Show details about a particular install session. This
     * may surface actions such as pause, resume, or cancel.
     * <p>
     * This should always be scoped to the installer package that owns the
     * session. Clients should use {@link SessionInfo#createDetailsIntent()} to
     * build this intent correctly.
     * <p>
     * In some cases, a matching Activity may not exist, so ensure you safeguard
     * against this.
     * <p>
     * The session to show details for is defined in {@link #EXTRA_SESSION_ID}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SESSION_DETAILS = "android.content.pm.action.SESSION_DETAILS";

    /**
     * Broadcast Action: Explicit broadcast sent to the last known default launcher when a session
     * for a new install is committed. For managed profile, this is sent to the default launcher
     * of the primary profile.
     * <p>
     * The associated session is defined in {@link #EXTRA_SESSION} and the user for which this
     * session was created in {@link Intent#EXTRA_USER}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_COMMITTED =
            "android.content.pm.action.SESSION_COMMITTED";

    /**
     * Broadcast Action: Send information about a staged install session when its state is updated.
     * <p>
     * The associated session information is defined in {@link #EXTRA_SESSION}.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_SESSION_UPDATED =
            "android.content.pm.action.SESSION_UPDATED";

    /**
     * Intent action to indicate that user action is required for current install. This action can
     * be used only by system apps.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_CONFIRM_INSTALL = "android.content.pm.action.CONFIRM_INSTALL";

    /**
     * Activity Action: Intent sent to the installer when a session for requesting
     * user pre-approval, and user needs to confirm the installation.
     *
     * @hide
     */
    @SystemApi
    public static final String ACTION_CONFIRM_PRE_APPROVAL =
            "android.content.pm.action.CONFIRM_PRE_APPROVAL";

    /**
     * An integer session ID that an operation is working with.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_SESSION_ID = "android.content.pm.extra.SESSION_ID";

    /**
     * {@link SessionInfo} that an operation is working with.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final String EXTRA_SESSION = "android.content.pm.extra.SESSION";

    /**
     * Package name that an operation is working with.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_PACKAGE_NAME = "android.content.pm.extra.PACKAGE_NAME";

    /**
     * Current status of an operation. Will be one of
     * {@link #STATUS_PENDING_USER_ACTION}, {@link #STATUS_SUCCESS},
     * {@link #STATUS_FAILURE}, {@link #STATUS_FAILURE_ABORTED},
     * {@link #STATUS_FAILURE_BLOCKED}, {@link #STATUS_FAILURE_CONFLICT},
     * {@link #STATUS_FAILURE_INCOMPATIBLE}, {@link #STATUS_FAILURE_INVALID},
     * {@link #STATUS_FAILURE_STORAGE}, or {@link #STATUS_FAILURE_TIMEOUT}.
     * <p>
     * More information about a status may be available through additional
     * extras; see the individual status documentation for details.
     *
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_STATUS = "android.content.pm.extra.STATUS";

    /**
     * Indicate if the status is for a pre-approval request.
     *
     * If callers use the same {@link IntentSender} for both
     * {@link Session#requestUserPreapproval(PreapprovalDetails, IntentSender)} and
     * {@link Session#commit(IntentSender)}, they can use this to differentiate between them.
     *
     * @see Intent#getBooleanExtra(String, boolean)
     */
    public static final String EXTRA_PRE_APPROVAL = "android.content.pm.extra.PRE_APPROVAL";

    /**
     * Detailed string representation of the status, including raw details that
     * are useful for debugging.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STATUS_MESSAGE = "android.content.pm.extra.STATUS_MESSAGE";

    /**
     * Another package name relevant to a status. This is typically the package
     * responsible for causing an operation failure.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String
            EXTRA_OTHER_PACKAGE_NAME = "android.content.pm.extra.OTHER_PACKAGE_NAME";

    /**
     * Storage path relevant to a status.
     *
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_STORAGE_PATH = "android.content.pm.extra.STORAGE_PATH";

    /**
     * The {@link InstallConstraints} object.
     *
     * @see Intent#getParcelableExtra(String, Class)
     * @see #waitForInstallConstraints(List, InstallConstraints, IntentSender, long)
     */
    public static final String EXTRA_INSTALL_CONSTRAINTS =
            "android.content.pm.extra.INSTALL_CONSTRAINTS";

    /**
     * The {@link InstallConstraintsResult} object.
     *
     * @see Intent#getParcelableExtra(String, Class)
     * @see #waitForInstallConstraints(List, InstallConstraints, IntentSender, long)
     */
    public static final String EXTRA_INSTALL_CONSTRAINTS_RESULT =
            "android.content.pm.extra.INSTALL_CONSTRAINTS_RESULT";

    /** {@hide} */
    @Deprecated
    public static final String EXTRA_PACKAGE_NAMES = "android.content.pm.extra.PACKAGE_NAMES";

    /**
     * The status as used internally in the package manager. Refer to {@link PackageManager} for
     * a list of all valid legacy statuses.
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_LEGACY_STATUS = "android.content.pm.extra.LEGACY_STATUS";
    /** {@hide} */
    public static final String EXTRA_LEGACY_BUNDLE = "android.content.pm.extra.LEGACY_BUNDLE";
    /**
     * The callback to execute once an uninstall is completed (used for both successful and
     * unsuccessful uninstalls).
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_CALLBACK = "android.content.pm.extra.CALLBACK";
    /**
     * Key for passing extra delete flags during archiving.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(android.content.pm.Flags.FLAG_ARCHIVING)
    public static final String EXTRA_DELETE_FLAGS = "android.content.pm.extra.DELETE_FLAGS";

    /**
     * Type of DataLoader for this session. Will be one of
     * {@link #DATA_LOADER_TYPE_NONE}, {@link #DATA_LOADER_TYPE_STREAMING},
     * {@link #DATA_LOADER_TYPE_INCREMENTAL}.
     * <p>
     * See the individual types documentation for details.
     *
     * @see Intent#getIntExtra(String, int)
     * {@hide}
     */
    @SystemApi
    public static final String EXTRA_DATA_LOADER_TYPE = "android.content.pm.extra.DATA_LOADER_TYPE";

    /**
     * Path to the validated base APK for this session, which may point at an
     * APK inside the session (when the session defines the base), or it may
     * point at the existing base APK (when adding splits to an existing app).
     *
     * @hide
     * @deprecated Resolved base path of an install session should not be available to unauthorized
     * callers. Use {@link SessionInfo#getResolvedBaseApkPath()} instead.
     */
    @Deprecated
    @SystemApi
    public static final String EXTRA_RESOLVED_BASE_PATH =
            "android.content.pm.extra.RESOLVED_BASE_PATH";

    /**
     * Extra field for the package name of a package that is requested to be unarchived. Sent as
     * part of the {@link android.content.Intent#ACTION_UNARCHIVE_PACKAGE} intent.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final String EXTRA_UNARCHIVE_PACKAGE_NAME =
            "android.content.pm.extra.UNARCHIVE_PACKAGE_NAME";

    /**
     * Extra field for the unarchive ID. Sent as
     * part of the {@link android.content.Intent#ACTION_UNARCHIVE_PACKAGE} intent.
     *
     * @see SessionParams#setUnarchiveId
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final String EXTRA_UNARCHIVE_ID =
            "android.content.pm.extra.UNARCHIVE_ID";

    /**
     * If true, the requestor of the unarchival has specified that the app should be unarchived
     * for all users. Sent as part of the {@link android.content.Intent#ACTION_UNARCHIVE_PACKAGE}
     * intent.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final String EXTRA_UNARCHIVE_ALL_USERS =
            "android.content.pm.extra.UNARCHIVE_ALL_USERS";

    /**
     * Current status of an unarchive operation. Will be one of
     * {@link #UNARCHIVAL_OK}, {@link #UNARCHIVAL_ERROR_USER_ACTION_NEEDED},
     * {@link #UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE}, {@link #UNARCHIVAL_ERROR_NO_CONNECTIVITY},
     * {@link #UNARCHIVAL_GENERIC_ERROR}, {@link #UNARCHIVAL_ERROR_INSTALLER_DISABLED} or
     * {@link #UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED}.
     *
     * <p> If the status is not {@link #UNARCHIVAL_OK}, then {@link Intent#EXTRA_INTENT} will be set
     * with an intent for a corresponding follow-up action (e.g. storage clearing dialog) or a
     * failure dialog.
     *
     * <p> Used as part of {@link #requestUnarchive} to return the status of the unarchival through
     * the {@link IntentSender}.
     *
     * @see #requestUnarchive
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final String EXTRA_UNARCHIVE_STATUS = "android.content.pm.extra.UNARCHIVE_STATUS";

    /**
     * A list of warnings that occurred during installation.
     *
     * @hide
     */
    public static final String EXTRA_WARNINGS = "android.content.pm.extra.WARNINGS";

    /**
     * Streaming installation pending.
     * Caller should make sure DataLoader is able to prepare image and reinitiate the operation.
     *
     * @see #EXTRA_SESSION_ID
     * {@hide}
     */
    public static final int STATUS_PENDING_STREAMING = -2;

    /**
     * User action is currently required to proceed. You can launch the intent
     * activity described by {@link Intent#EXTRA_INTENT} to involve the user and
     * continue.
     * <p>
     * You may choose to immediately launch the intent if the user is actively
     * using your app. Otherwise, you should use a notification to guide the
     * user back into your app before launching.
     *
     * @see Intent#getParcelableExtra(String)
     */
    public static final int STATUS_PENDING_USER_ACTION = -1;

    /**
     * The operation succeeded.
     */
    public static final int STATUS_SUCCESS = 0;

    /**
     * The operation failed in a generic way. The system will always try to
     * provide a more specific failure reason, but in some rare cases this may
     * be delivered.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE = 1;

    /**
     * The operation failed because it was blocked. For example, a device policy
     * may be blocking the operation, a package verifier may have blocked the
     * operation, or the app may be required for core system operation.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package blocking the install.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_BLOCKED = 2;

    /**
     * The operation failed because it was actively aborted. For example, the
     * user actively declined requested permissions, or the session was
     * abandoned.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_ABORTED = 3;

    /**
     * The operation failed because one or more of the APKs was invalid. For
     * example, they might be malformed, corrupt, incorrectly signed,
     * mismatched, etc.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INVALID = 4;

    /**
     * The operation failed because it conflicts (or is inconsistent with) with
     * another package already installed on the device. For example, an existing
     * permission, incompatible certificates, etc. The user may be able to
     * uninstall another app to fix the issue.
     * <p>
     * The result may also contain {@link #EXTRA_OTHER_PACKAGE_NAME} with the
     * specific package identified as the cause of the conflict.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_OTHER_PACKAGE_NAME
     */
    public static final int STATUS_FAILURE_CONFLICT = 5;

    /**
     * The operation failed because of storage issues. For example, the device
     * may be running low on space, or external media may be unavailable. The
     * user may be able to help free space or insert different external media.
     * <p>
     * The result may also contain {@link #EXTRA_STORAGE_PATH} with the path to
     * the storage device that caused the failure.
     *
     * @see #EXTRA_STATUS_MESSAGE
     * @see #EXTRA_STORAGE_PATH
     */
    public static final int STATUS_FAILURE_STORAGE = 6;

    /**
     * The operation failed because it is fundamentally incompatible with this
     * device. For example, the app may require a hardware feature that doesn't
     * exist, it may be missing native code for the ABIs supported by the
     * device, or it requires a newer SDK version, etc.
     *
     * Starting in {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}, an app with only 32-bit native
     * code can still be installed on a device that supports both 64-bit and 32-bit ABIs.
     * However, a warning dialog will be displayed when the app is launched.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_INCOMPATIBLE = 7;

    /**
     * The operation failed because it didn't complete within the specified timeout.
     *
     * @see #EXTRA_STATUS_MESSAGE
     */
    public static final int STATUS_FAILURE_TIMEOUT = 8;

    /**
     * Default value, non-streaming installation session.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_NONE = DataLoaderType.NONE;

    /**
     * Streaming installation using data loader.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_STREAMING = DataLoaderType.STREAMING;

    /**
     * Streaming installation using Incremental FileSystem.
     *
     * @see #EXTRA_DATA_LOADER_TYPE
     * {@hide}
     */
    @SystemApi
    public static final int DATA_LOADER_TYPE_INCREMENTAL = DataLoaderType.INCREMENTAL;

    /**
     * Target location for the file in installation session is /data/app/<packageName>-<id>.
     * This is the intended location for APKs.
     * Requires permission to install packages.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_DATA_APP = InstallationFileLocation.DATA_APP;

    /**
     * Target location for the file in installation session is
     * /data/media/<userid>/Android/obb/<packageName>. This is the intended location for OBBs.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_MEDIA_OBB = InstallationFileLocation.MEDIA_OBB;

    /**
     * Target location for the file in installation session is
     * /data/media/<userid>/Android/data/<packageName>.
     * This is the intended location for application data.
     * Can only be used by an app itself running under specific user.
     * {@hide}
     */
    @SystemApi
    public static final int LOCATION_MEDIA_DATA = InstallationFileLocation.MEDIA_DATA;

    /** @hide */
    @IntDef(prefix = { "LOCATION_" }, value = {
            LOCATION_DATA_APP,
            LOCATION_MEDIA_OBB,
            LOCATION_MEDIA_DATA})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileLocation{}

    /**
     * The installer did not call {@link PackageInstaller.SessionParams#setPackageSource(int)} to
     * specify the package source.
     */
    public static final int PACKAGE_SOURCE_UNSPECIFIED = 0;

    /**
     * Code indicating that the package being installed is from a source not reflected by any
     * other package source constant.
     */
    public static final int PACKAGE_SOURCE_OTHER = 1;

    /**
     * Code indicating that the package being installed is from a store. An app store that
     * installs an app for the user would use this.
     */
    public static final int PACKAGE_SOURCE_STORE = 2;

    /**
     * Code indicating that the package being installed comes from a local file on the device. A
     * file manager that is facilitating the installation of an APK file would use this.
     */
    public static final int PACKAGE_SOURCE_LOCAL_FILE = 3;

    /**
     * Code indicating that the package being installed comes from a file that was downloaded to
     * the device by the user. For use in place of {@link #PACKAGE_SOURCE_LOCAL_FILE} when the
     * installer knows the package was downloaded.
     */
    public static final int PACKAGE_SOURCE_DOWNLOADED_FILE = 4;

    /** @hide */
    @IntDef(prefix = { "PACKAGE_SOURCE_" }, value = {
            PACKAGE_SOURCE_UNSPECIFIED,
            PACKAGE_SOURCE_STORE,
            PACKAGE_SOURCE_LOCAL_FILE,
            PACKAGE_SOURCE_DOWNLOADED_FILE,
            PACKAGE_SOURCE_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PackageSourceType{}

    /**
     * Indicate the user intervention is required when the installer attempts to commit the session.
     * This is the default case.
     *
     * @hide
     */
    @SystemApi
    public static final int REASON_CONFIRM_PACKAGE_CHANGE = 0;

    /**
     * Indicate the user intervention is required because the update ownership enforcement is
     * enabled, and the update owner will change.
     *
     * @see PackageInstaller.SessionParams#setRequestUpdateOwnership
     * @see InstallSourceInfo#getUpdateOwnerPackageName
     * @hide
     */
    @SystemApi
    public static final int REASON_OWNERSHIP_CHANGED = 1;

    /**
     * Indicate the user intervention is required because the update ownership enforcement is
     * enabled, and remind the update owner is a different package.
     *
     * @see PackageInstaller.SessionParams#setRequestUpdateOwnership
     * @see InstallSourceInfo#getUpdateOwnerPackageName
     * @hide
     */
    @SystemApi
    public static final int REASON_REMIND_OWNERSHIP = 2;

    /** @hide */
    @IntDef(prefix = { "REASON_" }, value = {
            REASON_CONFIRM_PACKAGE_CHANGE,
            REASON_OWNERSHIP_CHANGED,
            REASON_REMIND_OWNERSHIP,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserActionReason {}

    /**
     * The unarchival status is not set.
     *
     * @hide
     */
    public static final int UNARCHIVAL_STATUS_UNSET = -1;

    /**
     * The unarchival is possible and will commence.
     *
     * <p> Note that this does not mean that the unarchival has completed. This status should be
     * sent before any longer asynchronous action (e.g. app download) is started.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_OK = 0;

    /**
     * The user needs to interact with the installer to enable the installation.
     *
     * <p> An example use case for this could be that the user needs to login to allow the
     * download for a paid app.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_ERROR_USER_ACTION_NEEDED = 1;

    /**
     * Not enough storage to unarchive the application.
     *
     * <p> The installer can optionally provide a {@code userActionIntent} for a space-clearing
     * dialog. If no action is provided, then a generic intent
     * {@link android.os.storage.StorageManager#ACTION_MANAGE_STORAGE} is started instead.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE = 2;

    /**
     * The device is not connected to the internet
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_ERROR_NO_CONNECTIVITY = 3;

    /**
     * The installer responsible for the unarchival is disabled.
     *
     * <p> The system will return this status if appropriate. Installers do not need to verify for
     * this error.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_ERROR_INSTALLER_DISABLED = 4;

    /**
     * The installer responsible for the unarchival has been uninstalled
     *
     * <p> The system will return this status if appropriate. Installers do not need to verify for
     * this error.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED = 5;

    /**
     * Generic error: The app cannot be unarchived.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final int UNARCHIVAL_GENERIC_ERROR = 100;

    /**
     * The set of error types that can be set for
     * {@link #reportUnarchivalState}.
     *
     * @hide
     */
    @IntDef(value = {
            UNARCHIVAL_STATUS_UNSET,
            UNARCHIVAL_OK,
            UNARCHIVAL_ERROR_USER_ACTION_NEEDED,
            UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE,
            UNARCHIVAL_ERROR_NO_CONNECTIVITY,
            UNARCHIVAL_ERROR_INSTALLER_DISABLED,
            UNARCHIVAL_ERROR_INSTALLER_UNINSTALLED,
            UNARCHIVAL_GENERIC_ERROR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UnarchivalStatus {}


    /** Default set of checksums - includes all available checksums.
     * @see Session#requestChecksums  */
    private static final int DEFAULT_CHECKSUMS =
            TYPE_WHOLE_MERKLE_ROOT_4K_SHA256 | TYPE_WHOLE_MD5 | TYPE_WHOLE_SHA1 | TYPE_WHOLE_SHA256
                    | TYPE_WHOLE_SHA512 | TYPE_PARTIAL_MERKLE_ROOT_1M_SHA256
                    | TYPE_PARTIAL_MERKLE_ROOT_1M_SHA512;

    private final IPackageInstaller mInstaller;
    private final int mUserId;
    private final String mInstallerPackageName;
    private final String mAttributionTag;

    private final ArrayList<SessionCallbackDelegate> mDelegates = new ArrayList<>();

    /** {@hide} */
    public PackageInstaller(IPackageInstaller installer,
            String installerPackageName, String installerAttributionTag, int userId) {
        Objects.requireNonNull(installer, "installer cannot be null");
        mInstaller = installer;
        mInstallerPackageName = installerPackageName;
        mAttributionTag = installerAttributionTag;
        mUserId = userId;
    }

    /**
     * Create a new session using the given parameters, returning a unique ID
     * that represents the session. Once created, the session can be opened
     * multiple times across multiple device boots.
     * <p>
     * The system may automatically destroy sessions that have not been
     * finalized (either committed or abandoned) within a reasonable period of
     * time, typically on the order of a day.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when installation services are unavailable,
     *             such as when called from a restricted user.
     * @throws IllegalArgumentException when {@link SessionParams} is invalid.
     * @return positive, non-zero unique ID that represents the created session.
     *         This ID remains consistent across device reboots until the
     *         session is finalized. IDs are not reused during a given boot.
     */
    public int createSession(@NonNull SessionParams params) throws IOException {
        try {
            return mInstaller.createSession(params, mInstallerPackageName, mAttributionTag,
                    mUserId);
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Open an existing session to actively perform work. To succeed, the caller
     * must be the owner of the install session.
     *
     * @throws IOException if parameters were unsatisfiable, such as lack of
     *             disk space or unavailable media.
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public @NonNull Session openSession(int sessionId) throws IOException {
        try {
            try {
                return new Session(mInstaller.openSession(sessionId));
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        } catch (RuntimeException e) {
            ExceptionUtils.maybeUnwrapIOException(e);
            throw e;
        }
    }

    /**
     * Update the icon representing the app being installed in a specific
     * session. This should be roughly
     * {@link ActivityManager#getLauncherLargeIconSize()} in both dimensions.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppIcon(int sessionId, @Nullable Bitmap appIcon) {
        try {
            mInstaller.updateSessionAppIcon(sessionId, appIcon);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Update the label representing the app being installed in a specific
     * session.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void updateSessionAppLabel(int sessionId, @Nullable CharSequence appLabel) {
        try {
            final String val = (appLabel != null) ? appLabel.toString() : null;
            mInstaller.updateSessionAppLabel(sessionId, val);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Completely abandon the given session, destroying all staged data and
     * rendering it invalid. Abandoned sessions will be reported to
     * {@link SessionCallback} listeners as failures. This is equivalent to
     * opening the session and calling {@link Session#abandon()}.
     *
     * @throws SecurityException when the caller does not own the session, or
     *             the session is invalid.
     */
    public void abandonSession(int sessionId) {
        try {
            mInstaller.abandonSession(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return details for a specific session. Callers need to either declare &lt;queries&gt;
     * element with the specific package name in the app's manifest, have the
     * android.permission.QUERY_ALL_PACKAGES, or be the session owner to retrieve these details.
     *
     * @return details for the requested session, or {@code null} if the session
     *         does not exist.
     */
    public @Nullable SessionInfo getSessionInfo(int sessionId) {
        try {
            return mInstaller.getSessionInfo(sessionId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions, regardless of the installer. Callers need to
     * either declare &lt;queries&gt; element with the specific  package name in the app's manifest,
     * have the android.permission.QUERY_ALL_PACKAGES, or be the session owner to retrieve these
     * details.
     */
    public @NonNull List<SessionInfo> getAllSessions() {
        try {
            return mInstaller.getAllSessions(mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all known install sessions owned by the calling app.
     */
    public @NonNull List<SessionInfo> getMySessions() {
        try {
            return mInstaller.getMySessions(mInstallerPackageName, mUserId).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return list of all staged install sessions. Callers need to either declare &lt;queries&gt;
     * element with the specific package name in the app's manifest, have the
     * android.permission.QUERY_ALL_PACKAGES, or be the session owner to retrieve these details.
     */
    public @NonNull List<SessionInfo> getStagedSessions() {
        try {
            // TODO: limit this to the mUserId?
            return mInstaller.getStagedSessions().getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns first active staged session, or {@code null} if there is none.
     *
     * <p>For more information on what sessions are considered active see
     * {@link SessionInfo#isStagedSessionActive()}.
     *
     * @deprecated Use {@link #getActiveStagedSessions} as there can be more than one active staged
     * session
     */
    @Deprecated
    public @Nullable SessionInfo getActiveStagedSession() {
        List<SessionInfo> activeSessions = getActiveStagedSessions();
        return activeSessions.isEmpty() ? null : activeSessions.get(0);
    }

    /**
     * Returns list of active staged sessions. Returns empty list if there is none.
     *
     * <p>For more information on what sessions are considered active see
     *      * {@link SessionInfo#isStagedSessionActive()}.
     */
    public @NonNull List<SessionInfo> getActiveStagedSessions() {
        final List<SessionInfo> activeStagedSessions = new ArrayList<>();
        final List<SessionInfo> stagedSessions = getStagedSessions();
        for (int i = 0; i < stagedSessions.size(); i++) {
            final SessionInfo sessionInfo = stagedSessions.get(i);
            if (sessionInfo.isStagedSessionActive()) {
                activeStagedSessions.add(sessionInfo);
            }
        }
        return activeStagedSessions;
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * </ul>
     *
     * @param packageName The package to uninstall.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull String packageName, @NonNull IntentSender statusReceiver) {
        uninstall(packageName, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package, removing it completely from the device. This
     * method is only available to the current "installer of record" for the
     * package.
     *
     * @param packageName The package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     *
     * @hide
     */
    public void uninstall(@NonNull String packageName, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        uninstall(new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                flags, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     * <p>
     * This method is available to:
     * <ul>
     * <li>the current "installer of record" for the package
     * <li>the device owner
     * <li>the affiliated profile owner
     * </ul>
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     *
     * @see android.app.admin.DevicePolicyManager
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage,
            @NonNull IntentSender statusReceiver) {
        uninstall(versionedPackage, 0 /*flags*/, statusReceiver);
    }

    /**
     * Uninstall the given package with a specific version code, removing it
     * completely from the device. This method is only available to the current
     * "installer of record" for the package. If the version code of the package
     * does not match the one passed in the versioned package argument this
     * method is a no-op. Use {@link PackageManager#VERSION_CODE_HIGHEST} to
     * uninstall the latest version of the package.
     *
     * @param versionedPackage The versioned package to uninstall.
     * @param flags Flags for uninstall.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    public void uninstall(@NonNull VersionedPackage versionedPackage, @DeleteFlags int flags,
            @NonNull IntentSender statusReceiver) {
        Objects.requireNonNull(versionedPackage, "versionedPackage cannot be null");
        try {
            mInstaller.uninstall(versionedPackage, mInstallerPackageName,
                    flags, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Install the given package, which already exists on the device, for the user for which this
     * installer was created.
     *
     * <p>This will
     * {@link PackageInstaller.SessionParams#setWhitelistedRestrictedPermissions(Set) allowlist
     * all restricted permissions}.
     *
     * @param packageName The package to install.
     * @param installReason Reason for install.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     */
    @RequiresPermission(allOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.INSTALL_EXISTING_PACKAGES})
    public void installExistingPackage(@NonNull String packageName,
            @InstallReason int installReason,
            @Nullable IntentSender statusReceiver) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        try {
            mInstaller.installExistingPackage(packageName,
                    PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS, installReason,
                    statusReceiver, mUserId, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Uninstall the given package for the user for which this installer was created if the package
     * will still exist for other users on the device.
     *
     * @param packageName The package to uninstall.
     * @param statusReceiver Where to deliver the result of the operation indicated by the extra
     *                       {@link #EXTRA_STATUS}. Refer to the individual status codes
     *                       on how to handle them.
     */
    @RequiresPermission(Manifest.permission.DELETE_PACKAGES)
    public void uninstallExistingPackage(@NonNull String packageName,
            @Nullable IntentSender statusReceiver) {
        Objects.requireNonNull(packageName, "packageName cannot be null");
        try {
            mInstaller.uninstallExistingPackage(
                    new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST),
                    mInstallerPackageName, statusReceiver, mUserId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Install package in an archived state.
     *
     * @param archivedPackageInfo archived package data such as package name, signature etc.
     * @param sessionParams used to create an underlying installation session
     * @param statusReceiver Called when the state of the session changes. Intents
     *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
     *                       individual status codes on how to handle them.
     * @see #createSession
     * @see PackageInstaller.Session#commit
     */
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public void installPackageArchived(@NonNull ArchivedPackageInfo archivedPackageInfo,
            @NonNull SessionParams sessionParams,
            @NonNull IntentSender statusReceiver) {
        Objects.requireNonNull(archivedPackageInfo, "archivedPackageInfo cannot be null");
        Objects.requireNonNull(sessionParams, "sessionParams cannot be null");
        Objects.requireNonNull(statusReceiver, "statusReceiver cannot be null");
        try {
            mInstaller.installPackageArchived(
                    archivedPackageInfo.getParcel(),
                    sessionParams,
                    statusReceiver,
                    mInstallerPackageName,
                    new UserHandle(mUserId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
    public void setPermissionsResult(int sessionId, boolean accepted) {
        try {
            mInstaller.setPermissionsResult(sessionId, accepted);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if install constraints are satisfied for the given packages.
     *
     * Note this query result is just a hint and subject to race because system states could
     * change anytime in-between this query and committing the session.
     *
     * The result is returned by a callback because some constraints might take a long time
     * to evaluate.
     *
     * @param packageNames a list of package names to check the constraints for installation
     * @param constraints the constraints for installation.
     * @param executor the {@link Executor} on which to invoke the callback
     * @param callback called when the {@link InstallConstraintsResult} is ready
     *
     * @throws SecurityException if the given packages' installer of record doesn't match the
     *             caller's own package name or the installerPackageName set by the caller doesn't
     *             match the caller's own package name.
     */
    public void checkInstallConstraints(@NonNull List<String> packageNames,
            @NonNull InstallConstraints constraints,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<InstallConstraintsResult> callback) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        try {
            var remoteCallback = new RemoteCallback(b -> {
                executor.execute(() -> {
                    callback.accept(b.getParcelable("result", InstallConstraintsResult.class));
                });
            });
            mInstaller.checkInstallConstraints(
                    mInstallerPackageName, packageNames, constraints, remoteCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Similar to {@link #checkInstallConstraints(List, InstallConstraints, Executor, Consumer)},
     * but the callback is invoked only when the constraints are satisfied or after timeout.
     * <p>
     * Note: the device idle constraint might take a long time to evaluate. The system will
     * ensure the constraint is evaluated completely before handling timeout.
     *
     * @param packageNames a list of package names to check the constraints for installation
     * @param constraints the constraints for installation.
     * @param callback Called when the constraints are satisfied or after timeout.
     *                 Intents sent to this callback contain:
     *                 {@link Intent#EXTRA_PACKAGES} for the input package names,
     *                 {@link #EXTRA_INSTALL_CONSTRAINTS} for the input constraints,
     *                 {@link #EXTRA_INSTALL_CONSTRAINTS_RESULT} for the result.
     * @param timeoutMillis The maximum time to wait, in milliseconds until the constraints are
     *                      satisfied. Valid range is from 0 to one week. {@code 0} means the
     *                      callback will be invoked immediately no matter constraints are
     *                      satisfied or not.
     * @throws SecurityException if the given packages' installer of record doesn't match the
     *             caller's own package name or the installerPackageName set by the caller doesn't
     *             match the caller's own package name.
     */
    public void waitForInstallConstraints(@NonNull List<String> packageNames,
            @NonNull InstallConstraints constraints,
            @NonNull IntentSender callback,
            @DurationMillisLong long timeoutMillis) {
        try {
            mInstaller.waitForInstallConstraints(
                    mInstallerPackageName, packageNames, constraints, callback, timeoutMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Commit the session when all constraints are satisfied. This is a convenient method to
     * combine {@link #waitForInstallConstraints(List, InstallConstraints, IntentSender, long)}
     * and {@link Session#commit(IntentSender)}.
     * <p>
     * Once this method is called, the session is sealed and no additional mutations
     * may be performed on the session. In the case of timeout, you may commit the
     * session again using this method or {@link Session#commit(IntentSender)} for retries.
     *
     * @param sessionId the session ID to commit when all constraints are satisfied.
     * @param statusReceiver Called when the state of the session changes. Intents
     *                       sent to this receiver contain {@link #EXTRA_STATUS}.
     *                       Refer to the individual status codes on how to handle them.
     * @param constraints The requirements to satisfy before committing the session.
     * @param timeoutMillis The maximum time to wait, in milliseconds until the
     *                      constraints are satisfied. The caller will be notified via
     *                      {@code statusReceiver} if timeout happens before commit.
     * @throws IllegalArgumentException if the {@code statusReceiver} from an immutable
     *             {@link android.app.PendingIntent} when caller has a target SDK of API
     *             35 or above.
     */
    public void commitSessionAfterInstallConstraintsAreMet(int sessionId,
            @NonNull IntentSender statusReceiver, @NonNull InstallConstraints constraints,
            @DurationMillisLong long timeoutMillis) {
        try {
            var session = mInstaller.openSession(sessionId);
            session.seal();
            var packageNames = session.fetchPackageNames();
            var context = ActivityThread.currentApplication();
            var localIntentSender = new LocalIntentSender(context, sessionId, session,
                    statusReceiver);
            waitForInstallConstraints(packageNames, constraints,
                    localIntentSender.getIntentSender(), timeoutMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static final class LocalIntentSender extends BroadcastReceiver {

        private final Context mContext;
        private final IntentSender mStatusReceiver;
        private final int mSessionId;
        private final IPackageInstallerSession mSession;

        LocalIntentSender(Context context, int sessionId, IPackageInstallerSession session,
                IntentSender statusReceiver) {
            mContext = context;
            mSessionId = sessionId;
            mSession = session;
            mStatusReceiver = statusReceiver;
        }

        private IntentSender getIntentSender() {
            Intent intent = new Intent(ACTION_WAIT_INSTALL_CONSTRAINTS).setPackage(
                    mContext.getPackageName());
            mContext.registerReceiver(this, new IntentFilter(ACTION_WAIT_INSTALL_CONSTRAINTS),
                    Context.RECEIVER_EXPORTED);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                    PendingIntent.FLAG_MUTABLE);
            return pendingIntent.getIntentSender();
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            InstallConstraintsResult result = intent.getParcelableExtra(
                    PackageInstaller.EXTRA_INSTALL_CONSTRAINTS_RESULT,
                    InstallConstraintsResult.class);
            try {
                if (result.areAllConstraintsSatisfied()) {
                    mSession.commit(mStatusReceiver, false);
                } else {
                    // timeout
                    final Intent fillIn = new Intent();
                    fillIn.putExtra(PackageInstaller.EXTRA_SESSION_ID, mSessionId);
                    fillIn.putExtra(PackageInstaller.EXTRA_STATUS, STATUS_FAILURE_TIMEOUT);
                    fillIn.putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE,
                            "Install constraints not satisfied within timeout");
                    mStatusReceiver.sendIntent(ActivityThread.currentApplication(), 0, fillIn, null,
                            null);
                }
            } catch (Exception ignore) {
                // no-op
            } finally {
                unregisterReceiver();
            }
        }

        private void unregisterReceiver() {
            mContext.unregisterReceiver(this);
        }
    }

    /**
     * Events for observing session lifecycle.
     * <p>
     * A typical session lifecycle looks like this:
     * <ul>
     * <li>An installer creates a session to indicate pending app delivery. All
     * install details are available at this point.
     * <li>The installer opens the session to deliver APK data. Note that a
     * session may be opened and closed multiple times as network connectivity
     * changes. The installer may deliver periodic progress updates.
     * <li>The installer commits or abandons the session, resulting in the
     * session being finished.
     * </ul>
     */
    public static abstract class SessionCallback {
        /**
         * New session has been created. Details about the session can be
         * obtained from {@link PackageInstaller#getSessionInfo(int)}.
         */
        public abstract void onCreated(int sessionId);

        /**
         * Badging details for an existing session has changed. For example, the
         * app icon or label has been updated.
         */
        public abstract void onBadgingChanged(int sessionId);

        /**
         * Active state for session has been changed.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public abstract void onActiveChanged(int sessionId, boolean active);

        /**
         * Progress for given session has been updated.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public abstract void onProgressChanged(int sessionId, float progress);

        /**
         * Session has completely finished, either with success or failure.
         */
        public abstract void onFinished(int sessionId, boolean success);
    }

    /** {@hide} */
    static class SessionCallbackDelegate extends IPackageInstallerCallback.Stub {
        private static final int MSG_SESSION_CREATED = 1;
        private static final int MSG_SESSION_BADGING_CHANGED = 2;
        private static final int MSG_SESSION_ACTIVE_CHANGED = 3;
        private static final int MSG_SESSION_PROGRESS_CHANGED = 4;
        private static final int MSG_SESSION_FINISHED = 5;

        final SessionCallback mCallback;
        final Executor mExecutor;

        SessionCallbackDelegate(SessionCallback callback, Executor executor) {
            mCallback = callback;
            mExecutor = executor;
        }

        @Override
        public void onSessionCreated(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onCreated, mCallback,
                    sessionId).recycleOnUse());
        }

        @Override
        public void onSessionBadgingChanged(int sessionId) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onBadgingChanged,
                    mCallback, sessionId).recycleOnUse());
        }

        @Override
        public void onSessionActiveChanged(int sessionId, boolean active) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onActiveChanged,
                    mCallback, sessionId, active).recycleOnUse());
        }

        @Override
        public void onSessionProgressChanged(int sessionId, float progress) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onProgressChanged,
                    mCallback, sessionId, progress).recycleOnUse());
        }

        @Override
        public void onSessionFinished(int sessionId, boolean success) {
            mExecutor.execute(PooledLambda.obtainRunnable(SessionCallback::onFinished,
                    mCallback, sessionId, success).recycleOnUse());
        }
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback);
    }

    /**
     * Register to watch for session lifecycle events. The callers need to be the session
     * owner or have the android.permission.QUERY_ALL_PACKAGES to watch for these events.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback) {
        registerSessionCallback(callback, new Handler());
    }

    /** {@hide} */
    @Deprecated
    public void addSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        registerSessionCallback(callback, handler);
    }

    /**
     * Register to watch for session lifecycle events. No special permissions
     * are required to watch for these events.
     *
     * @param handler to dispatch callback events through, otherwise uses
     *            calling thread.
     */
    public void registerSessionCallback(@NonNull SessionCallback callback, @NonNull Handler handler) {
        synchronized (mDelegates) {
            final SessionCallbackDelegate delegate = new SessionCallbackDelegate(callback,
                    new HandlerExecutor(handler));
            try {
                mInstaller.registerCallback(delegate, mUserId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mDelegates.add(delegate);
        }
    }

    /** {@hide} */
    @Deprecated
    public void removeSessionCallback(@NonNull SessionCallback callback) {
        unregisterSessionCallback(callback);
    }

    /**
     * Unregister a previously registered callback.
     */
    public void unregisterSessionCallback(@NonNull SessionCallback callback) {
        synchronized (mDelegates) {
            for (Iterator<SessionCallbackDelegate> i = mDelegates.iterator(); i.hasNext();) {
                final SessionCallbackDelegate delegate = i.next();
                if (delegate.mCallback == callback) {
                    try {
                        mInstaller.unregisterCallback(delegate);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    i.remove();
                }
            }
        }
    }

    /**
     * An installation that is being actively staged. For an install to succeed,
     * all existing and new packages must have identical package names, version
     * codes, and signing certificates.
     * <p>
     * A session may contain any number of split packages. If the application
     * does not yet exist, this session must include a base package.
     * <p>
     * If an APK included in this session is already defined by the existing
     * installation (for example, the same split name), the APK in this session
     * will replace the existing APK.
     * <p>
     * In such a case that multiple packages need to be committed simultaneously,
     * multiple sessions can be referenced by a single multi-package session.
     * This session is created with no package name and calling
     * {@link SessionParams#setMultiPackage()}. The individual session IDs can be
     * added with {@link #addChildSessionId(int)} and commit of the multi-package
     * session will result in all child sessions being committed atomically.
     */
    public static class Session implements Closeable {
        /** {@hide} */
        protected final IPackageInstallerSession mSession;

        /** {@hide} */
        public Session(IPackageInstallerSession session) {
            mSession = session;
        }

        /** {@hide} */
        @Deprecated
        public void setProgress(float progress) {
            setStagingProgress(progress);
        }

        /**
         * Set current progress of staging this session. Valid values are
         * anywhere between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by {@link SessionCallback#onProgressChanged(int, float)}, as
         * the system may carve out a portion of the overall progress to
         * represent its own internal installation work.
         */
        public void setStagingProgress(float progress) {
            try {
                mSession.setClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage
        public void addProgress(float progress) {
            try {
                mSession.addClientProgress(progress);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to write an APK file into the session.
         * <p>
         * The returned stream will start writing data at the requested offset
         * in the underlying file, which can be used to resume a partially
         * written file. If a valid file length is specified, the system will
         * preallocate the underlying disk space to optimize placement on disk.
         * It's strongly recommended to provide a valid file length when known.
         * <p>
         * You can write data into the returned stream, optionally call
         * {@link #fsync(OutputStream)} as needed to ensure bytes have been
         * persisted to disk, and then close when finished. All streams must be
         * closed before calling {@link #commit(IntentSender)}.
         *
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param offsetBytes offset into the file to begin writing at, or 0 to
         *            start at the beginning of the file.
         * @param lengthBytes total size of the file being written, used to
         *            preallocate the underlying disk space, or -1 if unknown.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @throws IOException if trouble opening the file for writing, such as
         *             lack of disk space or unavailable media.
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         */
        public @NonNull OutputStream openWrite(@NonNull String name, long offsetBytes,
                long lengthBytes) throws IOException {
            try {
                if (ENABLE_REVOCABLE_FD) {
                    return new ParcelFileDescriptor.AutoCloseOutputStream(
                            mSession.openWrite(name, offsetBytes, lengthBytes));
                } else {
                    final ParcelFileDescriptor clientSocket = mSession.openWrite(name,
                            offsetBytes, lengthBytes);
                    return new FileBridge.FileBridgeOutputStream(clientSocket);
                }
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /** {@hide} */
        public void write(@NonNull String name, long offsetBytes, long lengthBytes,
                @NonNull ParcelFileDescriptor fd) throws IOException {
            try {
                mSession.write(name, offsetBytes, lengthBytes, fd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Populate an APK file by creating a hard link to avoid the need to copy.
         * <p>
         * Note this API is used by RollbackManager only and can only be called from system_server.
         * {@code target} will be relabeled if link is created successfully. RollbackManager has
         * to delete {@code target} when the session is committed successfully to avoid SELinux
         * label conflicts.
         * <p>
         * Note No more bytes should be written to the file once the link is created successfully.
         *
         * @param target the path of the link target
         *
         * @hide
         */
        public void stageViaHardLink(String target) throws IOException {
            try {
                mSession.stageViaHardLink(target);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Ensure that any outstanding data for given stream has been committed
         * to disk. This is only valid for streams returned from
         * {@link #openWrite(String, long, long)}.
         */
        public void fsync(@NonNull OutputStream out) throws IOException {
            if (ENABLE_REVOCABLE_FD) {
                if (out instanceof ParcelFileDescriptor.AutoCloseOutputStream) {
                    try {
                        Os.fsync(((ParcelFileDescriptor.AutoCloseOutputStream) out).getFD());
                    } catch (ErrnoException e) {
                        throw e.rethrowAsIOException();
                    }
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            } else {
                if (out instanceof FileBridge.FileBridgeOutputStream) {
                    ((FileBridge.FileBridgeOutputStream) out).fsync();
                } else {
                    throw new IllegalArgumentException("Unrecognized stream");
                }
            }
        }

        /**
         * Return all APK names contained in this session.
         * <p>
         * This returns all names which have been previously written through
         * {@link #openWrite(String, long, long)} as part of this session.
         *
         * @throws SecurityException if called after the session has been abandoned.
         */
        public @NonNull String[] getNames() throws IOException {
            try {
                return mSession.getNames();
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Open a stream to read an APK file from the session.
         * <p>
         * This is only valid for names which have been previously written
         * through {@link #openWrite(String, long, long)} as part of this
         * session. For example, this stream may be used to calculate a
         * {@link MessageDigest} of a written APK before committing.
         *
         * @throws SecurityException if called after the session has been
         *             committed or abandoned.
         */
        public @NonNull InputStream openRead(@NonNull String name) throws IOException {
            try {
                final ParcelFileDescriptor pfd = mSession.openRead(name);
                return new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a split.
         * <p>
         * Split removals occur prior to adding new APKs. If upgrading a feature
         * split, it is not expected nor desirable to remove the split prior to
         * upgrading.
         * <p>
         * When split removal is bundled with new APKs, the packageName must be
         * identical.
         */
        public void removeSplit(@NonNull String splitName) throws IOException {
            try {
                mSession.removeSplit(splitName);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return data loader params or null if the session is not using one.
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public @Nullable DataLoaderParams getDataLoaderParams() {
            try {
                DataLoaderParamsParcel data = mSession.getDataLoaderParams();
                if (data == null) {
                    return null;
                }
                return new DataLoaderParams(data);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Adds a file to session. On commit this file will be pulled from DataLoader {@code
         * android.service.dataloader.DataLoaderService.DataLoader}.
         *
         * @param location target location for the file. Possible values:
         *            {@link #LOCATION_DATA_APP},
         *            {@link #LOCATION_MEDIA_OBB},
         *            {@link #LOCATION_MEDIA_DATA}.
         * @param name arbitrary, unique name of your choosing to identify the
         *            APK being written. You can open a file again for
         *            additional writes (such as after a reboot) by using the
         *            same name. This name is only meaningful within the context
         *            of a single install session.
         * @param lengthBytes total size of the file being written.
         *            The system may clear various caches as needed to allocate
         *            this space.
         * @param metadata additional info use by DataLoader to pull data for the file.
         * @param signature additional file signature, e.g.
         *                  <a href="https://source.android.com/security/apksigning/v4.html">APK Signature Scheme v4</a>
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         * @throws IllegalStateException if called for non-streaming session
         *
         * @see android.content.pm.InstallationFile
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public void addFile(@FileLocation int location, @NonNull String name, long lengthBytes,
                @NonNull byte[] metadata, @Nullable byte[] signature) {
            try {
                mSession.addFile(location, name, lengthBytes, metadata, signature);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a file.
         *
         * @param location target location for the file. Possible values:
         *            {@link #LOCATION_DATA_APP},
         *            {@link #LOCATION_MEDIA_OBB},
         *            {@link #LOCATION_MEDIA_DATA}.
         * @param name name of a file, e.g. split.
         * @throws SecurityException if called after the session has been
         *             sealed or abandoned
         * @throws IllegalStateException if called for non-DataLoader session
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.USE_INSTALLER_V2)
        public void removeFile(@FileLocation int location, @NonNull String name) {
            try {
                mSession.removeFile(location, name);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Sets installer-provided checksums for the APK file in session.
         *
         * @param name      previously written as part of this session.
         *                  {@link #openWrite}
         * @param checksums installer intends to make available via
         *                  {@link PackageManager#requestChecksums} or {@link #requestChecksums}.
         * @param signature DER PKCS#7 detached signature bytes over binary serialized checksums
         *                  to enable integrity checking for the checksums or null for no integrity
         *                  checking. {@link PackageManager#requestChecksums} will return
         *                  the certificate used to create signature.
         *                  Binary format for checksums:
         *                  <pre>{@code DataOutputStream dos;
         *                  dos.writeInt(checksum.getType());
         *                  dos.writeInt(checksum.getValue().length);
         *                  dos.write(checksum.getValue());}</pre>
         *                  If using <b>openssl cms</b>, make sure to specify -binary -nosmimecap.
         *                  @see <a href="https://www.openssl.org/docs/man1.0.2/man1/cms.html">openssl cms</a>
         * @throws SecurityException if called after the session has been
         *                           committed or abandoned.
         * @throws IllegalStateException if checksums for this file have already been added.
         * @deprecated  do not use installer-provided checksums,
         *              use platform-enforced checksums
         *              e.g. {@link Checksum#TYPE_WHOLE_MERKLE_ROOT_4K_SHA256}
         *              in {@link PackageManager#requestChecksums}.
         */
        @Deprecated
        public void setChecksums(@NonNull String name, @NonNull List<Checksum> checksums,
                @Nullable byte[] signature) throws IOException {
            Objects.requireNonNull(name);
            Objects.requireNonNull(checksums);

            try {
                mSession.setChecksums(name, checksums.toArray(new Checksum[checksums.size()]),
                        signature);
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        private static List<byte[]> encodeCertificates(List<Certificate> certs) throws
                CertificateEncodingException {
            if (certs == null) {
                return null;
            }
            List<byte[]> result = new ArrayList<>(certs.size());
            for (Certificate cert : certs) {
                if (!(cert instanceof X509Certificate)) {
                    throw new CertificateEncodingException("Only X509 certificates supported.");
                }
                result.add(cert.getEncoded());
            }
            return result;
        }

        /**
         * Requests checksums for the APK file in session.
         * <p>
         * A possible use case is replying to {@link Intent#ACTION_PACKAGE_NEEDS_VERIFICATION}
         * broadcast.
         * The checksums will be returned asynchronously via onChecksumsReadyListener.
         * <p>
         * By default returns all readily available checksums:
         * <ul>
         * <li>enforced by platform,
         * <li>enforced by the installer.
         * </ul>
         * If the caller needs a specific checksum type, they can specify it as required.
         * <p>
         * <b>Caution: Android can not verify installer-provided checksums. Make sure you specify
         * trusted installers.</b>
         * <p>
         * @param name      previously written as part of this session.
         *                  {@link #openWrite}
         * @param required to explicitly request the checksum types. Will incur significant
         *                 CPU/memory/disk usage.
         * @param trustedInstallers for checksums enforced by installer, which installers are to be
         *                          trusted.
         *                          {@link PackageManager#TRUST_ALL} will return checksums from any
         *                          installer,
         *                          {@link PackageManager#TRUST_NONE} disables optimized
         *                          installer-enforced checksums, otherwise the list has to be
         *                          a non-empty list of certificates.
         * @param executor the {@link Executor} on which to invoke the callback
         * @param onChecksumsReadyListener called once when the results are available.
         * @throws CertificateEncodingException if an encoding error occurs for trustedInstallers.
         * @throws FileNotFoundException if the file does not exist.
         * @throws IllegalArgumentException if the list of trusted installer certificates is empty.
         */
        public void requestChecksums(@NonNull String name, @Checksum.TypeMask int required,
                @NonNull List<Certificate> trustedInstallers,
                @NonNull @CallbackExecutor Executor executor,
                @NonNull PackageManager.OnChecksumsReadyListener onChecksumsReadyListener)
                throws CertificateEncodingException, FileNotFoundException {
            Objects.requireNonNull(name);
            Objects.requireNonNull(trustedInstallers);
            Objects.requireNonNull(executor);
            Objects.requireNonNull(onChecksumsReadyListener);
            if (trustedInstallers == PackageManager.TRUST_ALL) {
                trustedInstallers = null;
            } else if (trustedInstallers == PackageManager.TRUST_NONE) {
                trustedInstallers = Collections.emptyList();
            } else if (trustedInstallers.isEmpty()) {
                throw new IllegalArgumentException(
                        "trustedInstallers has to be one of TRUST_ALL/TRUST_NONE or a non-empty "
                                + "list of certificates.");
            }
            try {
                IOnChecksumsReadyListener onChecksumsReadyListenerDelegate =
                        new IOnChecksumsReadyListener.Stub() {
                            @Override
                            public void onChecksumsReady(List<ApkChecksum> checksums)
                                    throws RemoteException {
                                executor.execute(
                                        () -> onChecksumsReadyListener.onChecksumsReady(checksums));
                            }
                        };
                mSession.requestChecksums(name, DEFAULT_CHECKSUMS, required,
                        encodeCertificates(trustedInstallers), onChecksumsReadyListenerDelegate);
            } catch (ParcelableException e) {
                e.maybeRethrow(FileNotFoundException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit everything staged in this session. This may require
         * user intervention, and so it may not happen immediately. The final
         * result of the commit will be reported through the given callback.
         * <p>
         * Once this method is called, the session is sealed and no additional mutations may be
         * performed on the session. In case of device reboot or data loader transient failure
         * before the session has been finalized, you may commit the session again.
         * <p>
         * If the installer is the device owner, the affiliated profile owner, or has received
         * user pre-approval of this session, there will be no user intervention.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         *
         * @throws SecurityException if streams opened through
         *             {@link #openWrite(String, long, long)} are still open.
         * @throws IllegalArgumentException if the {@code statusReceiver} from an immutable
         *             {@link android.app.PendingIntent} when caller has a target SDK of API
         *             version 35 or above.
         *
         * @see android.app.admin.DevicePolicyManager
         * @see #requestUserPreapproval
         */
        public void commit(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Attempt to commit a session that has been {@link #transfer(String) transferred}.
         *
         * <p>If the device reboots before the session has been finalized, you may commit the
         * session again.
         *
         * <p>The caller of this method is responsible to ensure the safety of the session. As the
         * session was created by another - usually less trusted - app, it is paramount that before
         * committing <u>all</u> public and system {@link SessionInfo properties of the session}
         * and <u>all</u> {@link #openRead(String) APKs} are verified by the caller. It might happen
         * that new properties are added to the session with a new API revision. In this case the
         * callers need to be updated.
         *
         * @param statusReceiver Called when the state of the session changes. Intents
         *                       sent to this receiver contain {@link #EXTRA_STATUS}. Refer to the
         *                       individual status codes on how to handle them.
         * @throws IllegalArgumentException if the {@code statusReceiver} from an immutable
         *             {@link android.app.PendingIntent} when caller has a target SDK of API
         *             35 or above.
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_PACKAGES)
        public void commitTransferred(@NonNull IntentSender statusReceiver) {
            try {
                mSession.commit(statusReceiver, true);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Transfer the session to a new owner.
         * <p>
         * Only sessions that update the installing app can be transferred.
         * <p>
         * After the transfer to a package with a different uid all method calls on the session
         * will cause {@link SecurityException}s.
         * <p>
         * Once this method is called, the session is sealed and no additional mutations beside
         * committing it may be performed on the session.
         *
         * @param packageName The package of the new owner. Needs to hold the INSTALL_PACKAGES
         *                    permission.
         *
         * @throws PackageManager.NameNotFoundException if the new owner could not be found.
         * @throws SecurityException if called after the session has been committed or abandoned.
         * @throws IllegalStateException if streams opened through
         *                                  {@link #openWrite(String, long, long) are still open.
         * @throws IllegalArgumentException if {@code packageName} is invalid.
         */
        public void transfer(@NonNull String packageName)
                throws PackageManager.NameNotFoundException {
            Preconditions.checkArgument(!TextUtils.isEmpty(packageName));

            try {
                mSession.transfer(packageName);
            } catch (ParcelableException e) {
                e.maybeRethrow(PackageManager.NameNotFoundException.class);
                throw new RuntimeException(e);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Release this session object. You can open the session again if it
         * hasn't been finalized.
         */
        @Override
        public void close() {
            try {
                mSession.close();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Completely abandon this session, destroying all staged data and
         * rendering it invalid. Abandoned sessions will be reported to
         * {@link SessionCallback} listeners as failures. This is equivalent to
         * {@link #abandonSession(int)}.
         * <p>If the parent is abandoned, all children will also be abandoned. Any written data
         * would be destroyed and the created {@link Session} information will be discarded.</p>
         */
        public void abandon() {
            try {
                mSession.abandon();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will commit more than one package when it is
         * committed.
         */
        public boolean isMultiPackage() {
            try {
                return mSession.isMultiPackage();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will be staged and applied at next reboot.
         */
        public boolean isStaged() {
            try {
                return mSession.isStaged();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return Session's {@link SessionParams#installFlags}.
         * @hide
         */
        public int getInstallFlags() {
            try {
                return mSession.getInstallFlags();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the session ID of the multi-package session that this belongs to or
         * {@link SessionInfo#INVALID_ID} if it does not belong to a multi-package session.
         */
        public int getParentSessionId() {
            try {
                return mSession.getParentSessionId();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return the set of session IDs that will be committed atomically when this session is
         * committed if this is a multi-package session or null if none exist.
         */
        @NonNull
        public int[] getChildSessionIds() {
            try {
                return mSession.getChildSessionIds();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Adds a session ID to the set of sessions that will be committed atomically
         * when this session is committed.
         *
         * <p>If the parent is staged or has rollback enabled, all children must have
         * the same properties.</p>
         * <p>If the parent is abandoned, all children will also be abandoned.</p>
         *
         * @param sessionId the session ID to add to this multi-package session.
         */
        public void addChildSessionId(int sessionId) {
            try {
                mSession.addChildSessionId(sessionId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /**
         * Removes a session ID from the set of sessions that will be committed
         * atomically when this session is committed.
         *
         * @param sessionId the session ID to remove from this multi-package session.
         */
        public void removeChildSessionId(int sessionId) {
            try {
                mSession.removeChildSessionId(sessionId);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /**
         * @return A PersistableBundle containing the app metadata set with
         * {@link Session#setAppMetadata(PersistableBundle)}. In the case where this data does not
         * exist, an empty PersistableBundle is returned.
         */
        @NonNull
        public PersistableBundle getAppMetadata() {
            PersistableBundle data = null;
            try {
                ParcelFileDescriptor pfd = mSession.getAppMetadataFd();
                if (pfd != null) {
                    try (InputStream inputStream =
                            new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
                        data = PersistableBundle.readFromStream(inputStream);
                    }
                }
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return data != null ? data : new PersistableBundle();
        }

        private OutputStream openWriteAppMetadata() throws IOException {
            try {
                if (ENABLE_REVOCABLE_FD) {
                    return new ParcelFileDescriptor.AutoCloseOutputStream(
                            mSession.openWriteAppMetadata());
                } else {
                    final ParcelFileDescriptor clientSocket = mSession.openWriteAppMetadata();
                    return new FileBridge.FileBridgeOutputStream(clientSocket);
                }
            } catch (RuntimeException e) {
                ExceptionUtils.maybeUnwrapIOException(e);
                throw e;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Optionally set the app metadata. The size of this data cannot exceed the maximum allowed.
         * Any existing data from the previous install will not be retained even if no data is set
         * for the current install session. Setting data to null or an empty PersistableBundle will
         * remove any metadata that has previously been set in the same session.
         *
         * @param data a PersistableBundle containing the app metadata.
         * @throws IOException if writing the data fails.
         */
        public void setAppMetadata(@Nullable PersistableBundle data) throws IOException {
            if (data == null || data.isEmpty()) {
                try {
                    mSession.removeAppMetadata();
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                return;
            }
            Objects.requireNonNull(data);
            try (OutputStream outputStream = openWriteAppMetadata()) {
                data.writeToStream(outputStream);
            }
        }

        /**
         * Attempt to request the approval before committing this session.
         *
         * For installers that have been granted the
         * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES REQUEST_INSTALL_PACKAGES}
         * permission, they can request the approval from users before
         * {@link Session#commit(IntentSender)} is called. This may require user intervention as
         * well. When user intervention is required, installers will receive a
         * {@link #STATUS_PENDING_USER_ACTION} callback, and {@link #STATUS_SUCCESS} otherwise.
         * In case that requesting user pre-approval is not available, installers will receive
         * {@link #STATUS_FAILURE_BLOCKED} instead. Note that if the users decline the request,
         * this session will be abandoned.
         *
         * If user intervention is required but never resolved, or requesting user
         * pre-approval is not available, you may still call {@link Session#commit(IntentSender)}
         * as the typical installation.
         *
         * @param details the adequate context to this session for requesting the approval from
         *                users prior to commit.
         * @param statusReceiver called when the state of the session changes.
         *                       Intents sent to this receiver contain {@link #EXTRA_STATUS}
         *                       and the {@link #EXTRA_PRE_APPROVAL} would be {@code true}.
         *                       Refer to the individual status codes on how to handle them.
         *
         * @throws IllegalArgumentException when {@link PreapprovalDetails} is {@code null}.
         * @throws IllegalArgumentException if {@link IntentSender} is {@code null}.
         * @throws IllegalStateException if called on a multi-package session (no matter
         *                               the parent session or any of the children sessions).
         * @throws IllegalStateException if called again after this method has been called on
         *                               this session.
         * @throws SecurityException when the caller does not own this session.
         * @throws SecurityException if called after the session has been committed or abandoned.
         */
        public void requestUserPreapproval(@NonNull PreapprovalDetails details,
                @NonNull IntentSender statusReceiver) {
            Preconditions.checkArgument(details != null, "preapprovalDetails cannot be null.");
            Preconditions.checkArgument(statusReceiver != null, "statusReceiver cannot be null.");
            try {
                mSession.requestUserPreapproval(details, statusReceiver);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if this session will keep the existing application enabled setting
         * after installation.
         */
        public boolean isApplicationEnabledSettingPersistent() {
            try {
                return mSession.isApplicationEnabledSettingPersistent();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * @return {@code true} if the installer requested the update ownership enforcement
         * for the packages in this session.
         *
         * @see PackageInstaller.SessionParams#setRequestUpdateOwnership
         */
        public boolean isRequestUpdateOwnership() {
            try {
                return mSession.isRequestUpdateOwnership();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Parse a single APK or a directory of APKs to get install relevant information about
     * the package wrapped in {@link InstallInfo}.
     * @throws PackageParsingException if the package source file(s) provided is(are) not valid,
     * or the parser isn't able to parse the supplied source(s).
     * @hide
     */
    @SystemApi
    @NonNull
    public InstallInfo readInstallInfo(@NonNull File file, int flags)
            throws PackageParsingException {
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<PackageLite> result = ApkLiteParseUtils.parsePackageLite(
                input.reset(), file, flags);
        if (result.isError()) {
            throw new PackageParsingException(result.getErrorCode(), result.getErrorMessage());
        }
        return new InstallInfo(result);
    }

    /**
     * Parse a single APK file passed as an FD to get install relevant information about
     * the package wrapped in {@link InstallInfo}.
     * @throws PackageParsingException if the package source file(s) provided is(are) not valid,
     * or the parser isn't able to parse the supplied source(s).
     * @hide
     */
    @SystemApi
    @NonNull
    @FlaggedApi(Flags.FLAG_READ_INSTALL_INFO)
    public InstallInfo readInstallInfo(@NonNull ParcelFileDescriptor pfd,
            @Nullable String debugPathName, int flags) throws PackageParsingException {
        final ParseTypeImpl input = ParseTypeImpl.forDefaultParsing();
        final ParseResult<PackageLite> result = ApkLiteParseUtils.parseMonolithicPackageLite(input,
                pfd.getFileDescriptor(), debugPathName, flags);
        if (result.isError()) {
            throw new PackageParsingException(result.getErrorCode(), result.getErrorMessage());
        }
        return new InstallInfo(result);
    }

    /**
     * Requests to archive a package which is currently installed.
     *
     * <p> During the archival process, the apps APKs and cache are removed from the device while
     * the user data is kept. Through the {@link #requestUnarchive} call, apps
     * can be restored again through their responsible installer.
     *
     * <p> Archived apps are returned as displayable apps through the {@link LauncherApps} APIs and
     * will be displayed to users with UI treatment to highlight that said apps are archived. If
     * a user taps on an archived app, the app will be unarchived and the restoration process is
     * communicated.
     *
     * @param statusReceiver Callback used to notify when the operation is completed.
     * @throws PackageManager.NameNotFoundException If {@code packageName} isn't found or not
     *                                              available to the caller or isn't archived.
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.DELETE_PACKAGES,
            Manifest.permission.REQUEST_DELETE_PACKAGES})
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public void requestArchive(@NonNull String packageName, @NonNull IntentSender statusReceiver)
            throws PackageManager.NameNotFoundException {
        try {
            mInstaller.requestArchive(packageName, mInstallerPackageName, statusReceiver,
                    new UserHandle(mUserId));
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests to unarchive a currently archived package.
     *
     * <p> Sends a request to unarchive an app to the responsible installer. The installer is
     * determined by {@link InstallSourceInfo#getUpdateOwnerPackageName()}, or
     * {@link InstallSourceInfo#getInstallingPackageName()} if the former value is null.
     *
     * <p> The installation will happen asynchronously and can be observed through
     * {@link android.content.Intent#ACTION_PACKAGE_ADDED}.
     *
     * @param statusReceiver Callback used to notify whether the installer has accepted the
     *                       unarchival request or an error has occurred. The status update will be
     *                       sent though {@link #EXTRA_UNARCHIVE_STATUS}. Only one status will be
     *                       sent.
     * @throws PackageManager.NameNotFoundException If {@code packageName} isn't found or not
     *                                              visible to the caller or if the package has no
     *                                              installer on the device anymore to unarchive it.
     * @throws IOException If parameters were unsatisfiable, such as lack of disk space.
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.REQUEST_INSTALL_PACKAGES})
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public void requestUnarchive(@NonNull String packageName, @NonNull IntentSender statusReceiver)
            throws IOException, PackageManager.NameNotFoundException {
        try {
            mInstaller.requestUnarchive(packageName, mInstallerPackageName, statusReceiver,
                    new UserHandle(mUserId));
        } catch (ParcelableException e) {
            e.maybeRethrow(IOException.class);
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports the status of an unarchival to the system.
     *
     * @param unarchiveId          the ID provided by the system as part of the
     *                             intent.action.UNARCHIVE broadcast with EXTRA_UNARCHIVE_ID.
     * @param status               is used for the system to provide the user with necessary
     *                             follow-up steps or errors.
     * @param requiredStorageBytes If the error is UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE this field
     *                             should be set to specify how many additional bytes of storage
     *                             are required to unarchive the app.
     * @param userActionIntent     Optional intent to start a follow up action required to
     *                             facilitate the unarchival flow (e.g. user needs to log in).
     * @throws PackageManager.NameNotFoundException if no unarchival with {@code unarchiveId} exists
     */
    // TODO(b/314960798) Remove old API once it's unused
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.REQUEST_INSTALL_PACKAGES})
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public void reportUnarchivalStatus(int unarchiveId, @UnarchivalStatus int status,
            long requiredStorageBytes, @Nullable PendingIntent userActionIntent)
            throws PackageManager.NameNotFoundException {
        try {
            mInstaller.reportUnarchivalStatus(unarchiveId, status, requiredStorageBytes,
                    userActionIntent, new UserHandle(mUserId));
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports the state of an unarchival to the system.
     *
     * @see UnarchivalState for the different state options.
     * @throws PackageManager.NameNotFoundException if no unarchival with {@code unarchiveId} exists
     */
    @RequiresPermission(anyOf = {
            Manifest.permission.INSTALL_PACKAGES,
            Manifest.permission.REQUEST_INSTALL_PACKAGES})
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public void reportUnarchivalState(@NonNull UnarchivalState unarchivalState)
            throws PackageManager.NameNotFoundException {
        Objects.requireNonNull(unarchivalState);
        try {
            mInstaller.reportUnarchivalStatus(unarchivalState.getUnarchiveId(),
                    unarchivalState.getStatus(), unarchivalState.getRequiredStorageBytes(),
                    unarchivalState.getUserActionIntent(), new UserHandle(mUserId));
        } catch (ParcelableException e) {
            e.maybeRethrow(PackageManager.NameNotFoundException.class);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // (b/239722738) This class serves as a bridge between the PackageLite class, which
    // is a hidden class, and the consumers of this class. (e.g. InstallInstalling.java)
    // This is a part of an effort to remove dependency on hidden APIs and use SystemAPIs or
    // public APIs.
    /**
     * Install related details from an APK or a folder of APK(s).
     *
     * @hide
     */
    @SystemApi
    public static class InstallInfo {

        /** @hide */
        @IntDef(prefix = { "INSTALL_LOCATION_" }, value = {
                INSTALL_LOCATION_AUTO,
                INSTALL_LOCATION_INTERNAL_ONLY,
                INSTALL_LOCATION_PREFER_EXTERNAL
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface InstallLocation{}

        private PackageLite mPkg;

        InstallInfo(ParseResult<PackageLite> result) {
            mPkg = result.getResult();
        }

        /**
         * See {@link PackageLite#getPackageName()}
         */
        @NonNull
        public String getPackageName() {
            return mPkg.getPackageName();
        }

        /**
         * @return The default install location defined by an application in
         * {@link android.R.attr#installLocation} attribute.
         */
        public @InstallLocation int getInstallLocation() {
            return mPkg.getInstallLocation();
        }

        /**
         * @param params {@link SessionParams} of the installation
         * @return Total disk space occupied by an application after installation.
         * Includes the size of the raw APKs, possibly unpacked resources, raw dex metadata files,
         * and all relevant native code.
         * @throws IOException when size of native binaries cannot be calculated.
         */
        public long calculateInstalledSize(@NonNull SessionParams params) throws IOException {
            return InstallLocationUtils.calculateInstalledSize(mPkg, params.abiOverride);
        }

        /**
         * @param params {@link SessionParams} of the installation
         * @param pfd of an APK opened for read
         * @return Total disk space occupied by an application after installation.
         * Includes the size of the raw APKs, possibly unpacked resources, raw dex metadata files,
         * and all relevant native code.
         * @throws IOException when size of native binaries cannot be calculated.
         */
        @FlaggedApi(Flags.FLAG_READ_INSTALL_INFO)
        public long calculateInstalledSize(@NonNull SessionParams params,
                @NonNull ParcelFileDescriptor pfd) throws IOException {
            return InstallLocationUtils.calculateInstalledSize(mPkg, params.abiOverride,
                    pfd.getFileDescriptor());
        }
    }

    /**
     * Generic exception class for using with parsing operations.
     *
     * @hide
     */
    @SystemApi
    public static class PackageParsingException extends Exception {
        private final int mErrorCode;

        /** {@hide} */
        public PackageParsingException(int errorCode, @Nullable String detailedMessage) {
            super(detailedMessage);
            mErrorCode = errorCode;
        }

        public int getErrorCode() {
            return mErrorCode;
        }
    }

    /**
     * Parameters for creating a new {@link PackageInstaller.Session}.
     */
    public static class SessionParams implements Parcelable {

        /** {@hide} */
        public static final int MODE_INVALID = -1;

        /**
         * Mode for an install session whose staged APKs should fully replace any
         * existing APKs for the target app.
         */
        public static final int MODE_FULL_INSTALL = 1;

        /**
         * Mode for an install session that should inherit any existing APKs for the
         * target app, unless they have been explicitly overridden (based on split
         * name) by the session. For example, this can be used to add one or more
         * split APKs to an existing installation.
         * <p>
         * If there are no existing APKs for the target app, this behaves like
         * {@link #MODE_FULL_INSTALL}.
         */
        public static final int MODE_INHERIT_EXISTING = 2;

        /**
         * Special constant to refer to all restricted permissions.
         */
        public static final @NonNull Set<String> RESTRICTED_PERMISSIONS_ALL = new ArraySet<>();

        /** {@hide} */
        public static final int UID_UNKNOWN = -1;

        /**
         * This value is derived from the maximum file name length. No package above this limit
         * can ever be successfully installed on the device.
         * @hide
         */
        public static final int MAX_PACKAGE_NAME_LENGTH = 255;

        /** @hide */
        @IntDef(prefix = {"USER_ACTION_"}, value = {
                USER_ACTION_UNSPECIFIED,
                USER_ACTION_REQUIRED,
                USER_ACTION_NOT_REQUIRED
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface UserActionRequirement {}

        /**
         * This value is passed by the installer to {@link SessionParams#setRequireUserAction(int)}
         * to indicate that user action is unspecified for this install.
         * {@code requireUserAction} also defaults to this value unless modified by
         * {@link SessionParams#setRequireUserAction(int)}
         */
        public static final int USER_ACTION_UNSPECIFIED = 0;

        /**
         * This value is passed by the installer to {@link SessionParams#setRequireUserAction(int)}
         * to indicate that user action is required for this install.
         */
        public static final int USER_ACTION_REQUIRED = 1;

        /**
         * This value is passed by the installer to {@link SessionParams#setRequireUserAction(int)}
         * to indicate that user action is not required for this install.
         */
        public static final int USER_ACTION_NOT_REQUIRED = 2;

        /** @hide */
        @IntDef(prefix = {"PERMISSION_STATE_"}, value = {
                PERMISSION_STATE_DEFAULT,
                PERMISSION_STATE_GRANTED,
                PERMISSION_STATE_DENIED,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface PermissionState {}

        /**
         * Value is passed by the installer to {@link #setPermissionState(String, int)} to set
         * the state of a permission. This indicates no preference by the installer, relying on
         * the device's default policy to set the grant state of the permission.
         */
        public static final int PERMISSION_STATE_DEFAULT = 0;

        /**
         * Value is passed by the installer to {@link #setPermissionState(String, int)} to set
         * the state of a permission. This indicates the installers wants to automatically grant
         * the permission to the package being installed. The user and other actors in the system
         * may still be able to deny the permission after installation.
         */
        public static final int PERMISSION_STATE_GRANTED = 1;

        /**
         * Value is passed by the installer to {@link #setPermissionState(String, int)} to set
         * the state of a permission. This indicates the installers wants to deny the permission
         * by default to the package being installed. The user and other actors in the system may
         * still be able to grant the permission after installation.
         */
        public static final int PERMISSION_STATE_DENIED = 2;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode = MODE_INVALID;
        /** {@hide} */
        @UnsupportedAppUsage
        public int installFlags = PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
        /** {@hide} */
        public int installLocation = PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY;
        /** {@hide} */
        public @InstallReason int installReason = PackageManager.INSTALL_REASON_UNKNOWN;
        /**
         * {@hide}
         *
         * This flag indicates which installation scenario best describes this session.  The system
         * may use this value when making decisions about how to handle the installation, such as
         * prioritizing system health or user experience.
         */
        public @InstallScenario int installScenario = PackageManager.INSTALL_SCENARIO_DEFAULT;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long sizeBytes = -1;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appLabel;
        /** {@hide} */
        public long appIconLastModified = -1;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int originatingUid = UID_UNKNOWN;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String abiOverride;
        /** {@hide} */
        public String volumeUuid;
        /** {@hide} */
        public List<String> whitelistedRestrictedPermissions;
        /** {@hide} */
        public int autoRevokePermissionsMode = MODE_DEFAULT;
        /** {@hide} */
        public String installerPackageName;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public int packageSource = PACKAGE_SOURCE_UNSPECIFIED;
        /** {@hide} */
        public boolean isStaged;
        /** {@hide} */
        public long requiredInstalledVersionCode = PackageManager.VERSION_CODE_HIGHEST;
        /** {@hide} */
        public DataLoaderParams dataLoaderParams;
        /** {@hide} */
        public int rollbackDataPolicy = PackageManager.ROLLBACK_DATA_POLICY_RESTORE;
        /** @hide */
        public long rollbackLifetimeMillis = 0;
        /** {@hide} */
        public boolean forceQueryableOverride;
        /** {@hide} */
        public int requireUserAction = USER_ACTION_UNSPECIFIED;
        /** {@hide} */
        public boolean applicationEnabledSettingPersistent = false;
        /** {@hide} */
        public int developmentInstallFlags = 0;
        /** {@hide} */
        public int unarchiveId = -1;

        private final ArrayMap<String, Integer> mPermissionStates;

        /**
         * Construct parameters for a new package install session.
         *
         * @param mode one of {@link #MODE_FULL_INSTALL} or
         *            {@link #MODE_INHERIT_EXISTING} describing how the session
         *            should interact with an existing app.
         */
        public SessionParams(int mode) {
            this.mode = mode;
            mPermissionStates = new ArrayMap<>();
        }

        /** {@hide} */
        public SessionParams(Parcel source) {
            mode = source.readInt();
            installFlags = source.readInt();
            installLocation = source.readInt();
            installReason = source.readInt();
            installScenario = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null, android.graphics.Bitmap.class);
            appLabel = source.readString();
            originatingUri = source.readParcelable(null, android.net.Uri.class);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null, android.net.Uri.class);
            abiOverride = source.readString();
            volumeUuid = source.readString();
            mPermissionStates = new ArrayMap<>();
            source.readMap(mPermissionStates, null, String.class, Integer.class);
            whitelistedRestrictedPermissions = source.createStringArrayList();
            autoRevokePermissionsMode = source.readInt();
            installerPackageName = source.readString();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
            forceQueryableOverride = source.readBoolean();
            requiredInstalledVersionCode = source.readLong();
            DataLoaderParamsParcel dataLoaderParamsParcel = source.readParcelable(
                    DataLoaderParamsParcel.class.getClassLoader(), android.content.pm.DataLoaderParamsParcel.class);
            if (dataLoaderParamsParcel != null) {
                dataLoaderParams = new DataLoaderParams(dataLoaderParamsParcel);
            }
            rollbackDataPolicy = source.readInt();
            rollbackLifetimeMillis = source.readLong();
            requireUserAction = source.readInt();
            packageSource = source.readInt();
            applicationEnabledSettingPersistent = source.readBoolean();
            developmentInstallFlags = source.readInt();
            unarchiveId = source.readInt();
        }

        /** {@hide} */
        public SessionParams copy() {
            SessionParams ret = new SessionParams(mode);
            ret.installFlags = installFlags;
            ret.installLocation = installLocation;
            ret.installReason = installReason;
            ret.installScenario = installScenario;
            ret.sizeBytes = sizeBytes;
            ret.appPackageName = appPackageName;
            ret.appIcon = appIcon;  // not a copy.
            ret.appLabel = appLabel;
            ret.originatingUri = originatingUri;  // not a copy, but immutable.
            ret.originatingUid = originatingUid;
            ret.referrerUri = referrerUri;  // not a copy, but immutable.
            ret.abiOverride = abiOverride;
            ret.volumeUuid = volumeUuid;
            ret.mPermissionStates.putAll(mPermissionStates);
            ret.whitelistedRestrictedPermissions = whitelistedRestrictedPermissions;
            ret.autoRevokePermissionsMode = autoRevokePermissionsMode;
            ret.installerPackageName = installerPackageName;
            ret.isMultiPackage = isMultiPackage;
            ret.isStaged = isStaged;
            ret.forceQueryableOverride = forceQueryableOverride;
            ret.requiredInstalledVersionCode = requiredInstalledVersionCode;
            ret.dataLoaderParams = dataLoaderParams;
            ret.rollbackDataPolicy = rollbackDataPolicy;
            ret.rollbackLifetimeMillis = rollbackLifetimeMillis;
            ret.requireUserAction = requireUserAction;
            ret.packageSource = packageSource;
            ret.applicationEnabledSettingPersistent = applicationEnabledSettingPersistent;
            ret.developmentInstallFlags = developmentInstallFlags;
            ret.unarchiveId = unarchiveId;
            return ret;
        }

        /**
         * Check if there are hidden options set.
         *
         * <p>Hidden options are those options that cannot be verified via public or system-api
         * methods on {@link SessionInfo}.
         *
         * @return {@code true} if any hidden option is set.
         *
         * @hide
         */
        public boolean areHiddenOptionsSet() {
            return (installFlags & (PackageManager.INSTALL_REQUEST_DOWNGRADE
                    | PackageManager.INSTALL_ALLOW_DOWNGRADE
                    | PackageManager.INSTALL_DONT_KILL_APP
                    | PackageManager.INSTALL_INSTANT_APP
                    | PackageManager.INSTALL_FULL_APP
                    | PackageManager.INSTALL_VIRTUAL_PRELOAD
                    | PackageManager.INSTALL_ALLOCATE_AGGRESSIVE)) != installFlags
                    || abiOverride != null || volumeUuid != null;
        }

        /**
         * Provide value of {@link PackageInfo#installLocation}, which may be used
         * to determine where the app will be staged. Defaults to
         * {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}.
         */
        public void setInstallLocation(int installLocation) {
            this.installLocation = installLocation;
        }

        /**
         * Optionally indicate the total size (in bytes) of all APKs that will be
         * delivered in this session. The system may use this to ensure enough disk
         * space exists before proceeding, or to estimate container size for
         * installations living on external storage.
         *
         * @see PackageInfo#INSTALL_LOCATION_AUTO
         * @see PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL
         */
        public void setSize(long sizeBytes) {
            this.sizeBytes = sizeBytes;
        }

        /**
         * Optionally set the package name of the app being installed. It's strongly
         * recommended that you provide this value when known, so that observers can
         * communicate installing apps to users.
         * <p>
         * If the APKs staged in the session aren't consistent with this package
         * name, the install will fail. Regardless of this value, all APKs in the
         * app must have the same package name.
         */
        public void setAppPackageName(@Nullable String appPackageName) {
            this.appPackageName = appPackageName;
        }

        /**
         * Optionally set an icon representing the app being installed. This should
         * be roughly {@link ActivityManager#getLauncherLargeIconSize()} in both
         * dimensions.
         */
        public void setAppIcon(@Nullable Bitmap appIcon) {
            this.appIcon = appIcon;
        }

        /**
         * Optionally set a label representing the app being installed.
         *
         * This value will be trimmed to the first 1000 characters.
         */
        public void setAppLabel(@Nullable CharSequence appLabel) {
            this.appLabel = (appLabel != null) ? appLabel.toString() : null;
        }

        /**
         * Optionally set the URI where this package was downloaded from. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_ORIGINATING_URI
         */
        public void setOriginatingUri(@Nullable Uri originatingUri) {
            this.originatingUri = originatingUri;
        }

        /**
         * Sets the UID that initiated the package installation. This is informational
         * and may be used as a signal for anti-malware purposes.
         */
        public void setOriginatingUid(int originatingUid) {
            this.originatingUid = originatingUid;
        }

        /**
         * Optionally set the URI that referred you to install this package. This is
         * informational and may be used as a signal for anti-malware purposes.
         *
         * @see Intent#EXTRA_REFERRER
         */
        public void setReferrerUri(@Nullable Uri referrerUri) {
            this.referrerUri = referrerUri;
        }

        /**
         * Sets which runtime permissions to be granted to the package at installation.
         *
         * @param permissions The permissions to grant or null to grant all runtime
         *     permissions.
         *
         * @deprecated Prefer {@link #setPermissionState(String, int)} instead starting in
         * {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE}.
         * @hide
         */
        @Deprecated
        @SystemApi
        @RequiresPermission(android.Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS)
        public void setGrantedRuntimePermissions(String[] permissions) {
            if (permissions == null) {
                // The new API has no mechanism to grant all requested permissions
                installFlags |= PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS;
                mPermissionStates.clear();
            } else {
                installFlags &= ~PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS;
                // Otherwise call the new API to grant the permissions specified
                for (String permission : permissions) {
                    setPermissionState(permission, PERMISSION_STATE_GRANTED);
                }
            }
        }

        /**
         * Sets the state of permissions for the package at installation.
         * <p/>
         * Granting any runtime permissions require the
         * {@code android.Manifest.permission#INSTALL_GRANT_RUNTIME_PERMISSIONS}
         * permission to be held by the caller. Revoking runtime
         * permissions is not allowed, even during app update sessions.
         * <p/>
         * Holders without the permission are allowed to change the following special permissions:
         * <p/>
         * On platform {@link Build.VERSION_CODES#UPSIDE_DOWN_CAKE UPSIDE_DOWN_CAKE}:
         * <ul>
         *     <li>{@link Manifest.permission#USE_FULL_SCREEN_INTENT}</li>
         * </ul>
         * Install time permissions, which cannot be revoked by the user, cannot be changed by the
         * installer.
         * <p/>
         * See <a href="https://developer.android.com/guide/topics/permissions/overview">
         * Permissions on Android</a> for more information.
         *
         * @param permissionName The permission to change state for.
         * @param state          Either {@link #PERMISSION_STATE_DEFAULT},
         *                       {@link #PERMISSION_STATE_GRANTED},
         *                       or {@link #PERMISSION_STATE_DENIED} to set the permission to.
         *
         * @return This object for easier chaining.
         */
        @RequiresPermission(value = android.Manifest.permission.INSTALL_GRANT_RUNTIME_PERMISSIONS,
                conditional = true)
        @NonNull
        public SessionParams setPermissionState(@NonNull String permissionName,
                @PermissionState int state) {
            if (TextUtils.isEmpty(permissionName)) {
                throw new IllegalArgumentException("Provided permissionName cannot be "
                        + (permissionName == null ? "null" : "empty"));
            }

            switch (state) {
                case PERMISSION_STATE_DEFAULT:
                    mPermissionStates.remove(permissionName);
                    break;
                case PERMISSION_STATE_GRANTED:
                case PERMISSION_STATE_DENIED:
                    mPermissionStates.put(permissionName, state);
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected permission state int: " + state);
            }

            return this;
        }

        /** @hide */
        public void setPermissionStates(Collection<String> grantPermissions,
                Collection<String> denyPermissions) {
            for (String grantPermission : grantPermissions) {
                mPermissionStates.put(grantPermission, PERMISSION_STATE_GRANTED);
            }
            for (String denyPermission : denyPermissions) {
                mPermissionStates.put(denyPermission, PERMISSION_STATE_DENIED);
            }
        }

        /**
         * Optionally indicate the package source of the app being installed. This is
         * informational and may be used as a signal by the system.
         *
         * An installer should specify {@link #PACKAGE_SOURCE_OTHER} if no other package source
         * constant adequately reflects the source for this session.
         *
         * The default value is {@link #PACKAGE_SOURCE_UNSPECIFIED}.
         */
        public void setPackageSource(@PackageSourceType int packageSource) {
            this.packageSource = packageSource;
        }

        /**
         * Sets which restricted permissions to be allowlisted for the app. Allowlisting
         * is not granting the permissions, rather it allows the app to hold permissions
         * which are otherwise restricted. Allowlisting a non restricted permission has
         * no effect.
         *
         * <p> Permissions can be hard restricted which means that the app cannot hold
         * them or soft restricted where the app can hold the permission but in a weaker
         * form. Whether a permission is {@link PermissionInfo#FLAG_HARD_RESTRICTED hard
         * restricted} or {@link PermissionInfo#FLAG_SOFT_RESTRICTED soft restricted}
         * depends on the permission declaration. Allowlisting a hard restricted permission
         * allows the app to hold that permission and allowlisting a soft restricted
         * permission allows the app to hold the permission in its full, unrestricted form.
         *
         * <p> Permissions can also be immutably restricted which means that the allowlist
         * state of the permission can be determined only at install time and cannot be
         * changed on updated or at a later point via the package manager APIs.
         *
         * <p>Initially, all restricted permissions are allowlisted but you can change
         * which ones are allowlisted by calling this method or the corresponding ones
         * on the {@link PackageManager}. Only soft or hard restricted permissions on the current
         * Android version are supported and any invalid entries will be removed.
         *
         * @see PackageManager#addWhitelistedRestrictedPermission(String, String, int)
         * @see PackageManager#removeWhitelistedRestrictedPermission(String, String, int)
         */
        public void setWhitelistedRestrictedPermissions(@Nullable Set<String> permissions) {
            if (permissions == RESTRICTED_PERMISSIONS_ALL) {
                installFlags |= PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                whitelistedRestrictedPermissions = null;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS;
                whitelistedRestrictedPermissions = (permissions != null)
                        ? new ArrayList<>(permissions) : null;
            }
        }

        /**
         * Sets whether permissions should be auto-revoked if this package is unused for an
         * extended periodd of time.
         *
         * It's disabled by default but generally the installer should enable it for most packages,
         * excluding only those where doing so might cause breakage that cannot be easily addressed
         * by simply re-requesting the permission(s).
         *
         * If user explicitly enabled or disabled it via settings, this call is ignored.
         *
         * @param shouldAutoRevoke whether permissions should be auto-revoked.
         *
         * @deprecated No longer used
         */
        @Deprecated
        public void setAutoRevokePermissionsMode(boolean shouldAutoRevoke) {
            autoRevokePermissionsMode = shouldAutoRevoke ? MODE_ALLOWED : MODE_IGNORED;
        }

        /**
         * Request that rollbacks be enabled or disabled for the given upgrade with rollback data
         * policy set to RESTORE.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * @param enable set to {@code true} to enable, {@code false} to disable
         * @see SessionParams#setEnableRollback(boolean, int)
         * @hide
         */
        @SystemApi
        public void setEnableRollback(boolean enable) {
            setEnableRollback(enable, PackageManager.ROLLBACK_DATA_POLICY_RESTORE);
        }

        /**
         * Request that rollbacks be enabled or disabled for the given upgrade.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * <p> For a multi-package install, this method must be called on each child session to
         * specify rollback data policies explicitly. Note each child session is allowed to have
         * different policies.
         *
         * @param enable set to {@code true} to enable, {@code false} to disable
         * @param dataPolicy the rollback data policy for this session
         * @hide
         */
        @SystemApi
        public void setEnableRollback(boolean enable,
                @PackageManager.RollbackDataPolicy int dataPolicy) {
            if (enable) {
                installFlags |= PackageManager.INSTALL_ENABLE_ROLLBACK;
            } else {
                installFlags &= ~PackageManager.INSTALL_ENABLE_ROLLBACK;
                rollbackLifetimeMillis = 0;
            }
            rollbackDataPolicy = dataPolicy;
        }

        /**
         * If rollback enabled for this session (via {@link #setEnableRollback}, set time
         * after which rollback will no longer be possible
         *
         * <p>For multi-package installs, this value must be set on the parent session.
         * Child session rollback lifetime will be ignored.
         *
         * @param lifetimeMillis time after which rollback expires
         * @throws IllegalArgumentException if lifetimeMillis is negative or rollback is not
         * enabled via setEnableRollback.
         * @hide
         */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.MANAGE_ROLLBACKS)
        @FlaggedApi(Flags.FLAG_ROLLBACK_LIFETIME)
        public void setRollbackLifetimeMillis(@DurationMillisLong long lifetimeMillis) {
            if (lifetimeMillis < 0) {
                throw new IllegalArgumentException("rollbackLifetimeMillis can't be negative.");
            }
            if ((installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) == 0) {
                throw new IllegalArgumentException(
                        "Can't set rollbackLifetimeMillis when rollback is not enabled");
            }
            rollbackLifetimeMillis = lifetimeMillis;
        }

        /**
         * @deprecated use {@link #setRequestDowngrade(boolean)}.
         * {@hide}
         */
        @SystemApi
        @Deprecated
        public void setAllowDowngrade(boolean allowDowngrade) {
            setRequestDowngrade(allowDowngrade);
        }

        /** {@hide} */
        @SystemApi
        public void setRequestDowngrade(boolean requestDowngrade) {
            if (requestDowngrade) {
                installFlags |= PackageManager.INSTALL_REQUEST_DOWNGRADE;
            } else {
                installFlags &= ~PackageManager.INSTALL_REQUEST_DOWNGRADE;
            }
        }

        /**
         * Require the given version of the package be installed.
         * The install will only be allowed if the existing version code of
         * the package installed on the device matches the given version code.
         * Use {@link * PackageManager#VERSION_CODE_HIGHEST} to allow
         * installation regardless of the currently installed package version.
         *
         * @hide
         */
        public void setRequiredInstalledVersionCode(long versionCode) {
            requiredInstalledVersionCode = versionCode;
        }

        /** {@hide} */
        public void setInstallFlagsForcePermissionPrompt() {
            installFlags |= PackageManager.INSTALL_FORCE_PERMISSION_PROMPT;
        }

        /**
         * Requests that the system not kill any of the package's running
         * processes as part of a {@link SessionParams#MODE_INHERIT_EXISTING}
         * session in which splits being added. By default, all installs will
         * result in the package's running processes being killed before the
         * install completes.
         *
         * @param dontKillApp set to {@code true} to request that the processes
         *                    belonging to the package not be killed as part of
         *                    this install.
         */
        public void setDontKillApp(boolean dontKillApp) {
            if (dontKillApp) {
                installFlags |= PackageManager.INSTALL_DONT_KILL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_DONT_KILL_APP;
            }
        }

        /** {@hide} */
        @SystemApi
        public void setInstallAsInstantApp(boolean isInstantApp) {
            if (isInstantApp) {
                installFlags |= PackageManager.INSTALL_INSTANT_APP;
                installFlags &= ~PackageManager.INSTALL_FULL_APP;
            } else {
                installFlags &= ~PackageManager.INSTALL_INSTANT_APP;
                installFlags |= PackageManager.INSTALL_FULL_APP;
            }
        }

        /**
         * Sets the install as a virtual preload. Will only have effect when called
         * by the verifier.
         * {@hide}
         */
        @SystemApi
        public void setInstallAsVirtualPreload() {
            installFlags |= PackageManager.INSTALL_VIRTUAL_PRELOAD;
        }

        /**
         * Set the reason for installing this package.
         * <p>
         * The install reason should be a pre-defined integer. The behavior is
         * undefined if other values are used.
         *
         * @see PackageManager#INSTALL_REASON_UNKNOWN
         * @see PackageManager#INSTALL_REASON_POLICY
         * @see PackageManager#INSTALL_REASON_DEVICE_RESTORE
         * @see PackageManager#INSTALL_REASON_DEVICE_SETUP
         * @see PackageManager#INSTALL_REASON_USER
         */
        public void setInstallReason(@InstallReason int installReason) {
            this.installReason = installReason;
        }

        /** {@hide} */
        @SystemApi
        @RequiresPermission(android.Manifest.permission.ALLOCATE_AGGRESSIVE)
        public void setAllocateAggressive(boolean allocateAggressive) {
            if (allocateAggressive) {
                installFlags |= PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            } else {
                installFlags &= ~PackageManager.INSTALL_ALLOCATE_AGGRESSIVE;
            }
        }

        /**
         * @hide
         */
        @TestApi
        public void setInstallFlagAllowTest() {
            installFlags |= PackageManager.INSTALL_ALLOW_TEST;
        }

        /**
         * Set the installer package for the app.
         *
         * By default this is the app that created the {@link PackageInstaller} object.
         *
         * Note: Only applications with {@link android.Manifest.permission#INSTALL_PACKAGES}
         * permission are allowed to set an installer that is not the caller's own installer
         * package name, otherwise it will cause a {@link SecurityException} when creating the
         * install session.
         *
         * @param installerPackageName The name of the installer package, its length must be less
         *                            than {@code 255}, otherwise it will be invalid.
         */
        public void setInstallerPackageName(@Nullable String installerPackageName) {
            this.installerPackageName = installerPackageName;
        }

        /**
         * Set this session to be the parent of a multi-package install.
         *
         * A multi-package install session contains no APKs and only references other install
         * sessions via ID. When a multi-package session is committed, all of its children
         * are committed to the system in an atomic manner. If any children fail to install,
         * all of them do, including the multi-package session.
         */
        public void setMultiPackage() {
            this.isMultiPackage = true;
        }

        /**
         * Set this session to be staged to be installed at reboot.
         *
         * Staged sessions are scheduled to be installed at next reboot. Staged sessions can also be
         * multi-package. In that case, if any of the children sessions fail to install at reboot,
         * all the other children sessions are aborted as well.
         *
         * <p>If the parent session is staged or has rollback enabled, all children sessions
         * must have the same properties.
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
        public void setStaged() {
            this.isStaged = true;
        }

        /**
         * Set this session to be installing an APEX package.
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
        public void setInstallAsApex() {
            installFlags |= PackageManager.INSTALL_APEX;
        }

        /** @hide */
        public boolean getEnableRollback() {
            return (installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0;
        }

        /**
         * Set the data loader params for the session.
         * This also switches installation into data loading mode and disallow direct writes into
         * staging folder.
         *
         * @see android.service.dataloader.DataLoaderService.DataLoader
         *
         * {@hide}
         */
        @SystemApi
        @RequiresPermission(allOf = {
                Manifest.permission.INSTALL_PACKAGES,
                Manifest.permission.USE_INSTALLER_V2})
        public void setDataLoaderParams(@NonNull DataLoaderParams dataLoaderParams) {
            this.dataLoaderParams = dataLoaderParams;
        }

        /**
         *
         * {@hide}
         */
        public void setForceQueryable() {
            this.forceQueryableOverride = true;
        }

        /**
         * Optionally indicate whether user action should be required when the session is
         * committed.
         * <p>
         * Defaults to {@link #USER_ACTION_UNSPECIFIED} unless otherwise set. When unspecified for
         * installers using the
         * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES REQUEST_INSTALL_PACKAGES}
         * permission will behave as if set to {@link #USER_ACTION_REQUIRED}, and
         * {@link #USER_ACTION_NOT_REQUIRED} otherwise. When {@code requireUserAction} is set to
         * {@link #USER_ACTION_REQUIRED}, installers will receive a
         * {@link #STATUS_PENDING_USER_ACTION} callback once the session is committed, indicating
         * that user action is required for the install to proceed.
         * <p>
         * For installers that have been granted the
         * {@link android.Manifest.permission#REQUEST_INSTALL_PACKAGES REQUEST_INSTALL_PACKAGES}
         * permission, user action will not be required when all of the following conditions are
         * met:
         *
         * <ul>
         *     <li>{@code requireUserAction} is set to {@link #USER_ACTION_NOT_REQUIRED}.</li>
         *     <li>The app being installed targets:
         *          <ul>
         *              <li>{@link android.os.Build.VERSION_CODES#Q API 29} or higher on
         *              Android S  ({@link android.os.Build.VERSION_CODES#S API 31})</li>
         *              <li>{@link android.os.Build.VERSION_CODES#R API 30} or higher on
         *              Android T ({@link android.os.Build.VERSION_CODES#TIRAMISU API 33})</li>
         *              <li>{@link android.os.Build.VERSION_CODES#S API 31} or higher <b>after</b>
         *              Android T ({@link android.os.Build.VERSION_CODES#TIRAMISU API 33})</li>
         *          </ul>
         *     </li>
         *     <li>The installer is:
         *         <ul>
         *             <li>The {@link InstallSourceInfo#getUpdateOwnerPackageName() update owner}
         *             of an existing version of the app (in other words, this install session is
         *             an app update) if the update ownership enforcement is enabled.</li>
         *             <li>The
         *             {@link InstallSourceInfo#getInstallingPackageName() installer of record}
         *             of an existing version of the app (in other words, this install
         *             session is an app update) if the update ownership enforcement isn't
         *             enabled.</li>
         *             <li>Updating itself.</li>
         *         </ul>
         *     </li>
         *     <li>The installer declares the
         *     {@link android.Manifest.permission#UPDATE_PACKAGES_WITHOUT_USER_ACTION
         *     UPDATE_PACKAGES_WITHOUT_USER_ACTION} permission.</li>
         * </ul>
         * <p>
         * Note: The target API level requirement will advance in future Android versions.
         * Session owners should always be prepared to handle {@link #STATUS_PENDING_USER_ACTION}.
         *
         * @param requireUserAction whether user action should be required.
         */
        public void setRequireUserAction(
                @SessionParams.UserActionRequirement int requireUserAction) {
            if (requireUserAction != USER_ACTION_UNSPECIFIED
                    && requireUserAction != USER_ACTION_REQUIRED
                    && requireUserAction != USER_ACTION_NOT_REQUIRED) {
                throw new IllegalArgumentException("requireUserAction set as invalid value of "
                        + requireUserAction + ", but must be one of ["
                        + "USER_ACTION_UNSPECIFIED, USER_ACTION_REQUIRED, USER_ACTION_NOT_REQUIRED"
                        + "]");
            }
            this.requireUserAction = requireUserAction;
        }

        /**
         * Sets the install scenario for this session, which describes the expected user journey.
         */
        public void setInstallScenario(@InstallScenario int installScenario) {
            this.installScenario = installScenario;
        }

        /**
         * Request to keep the original application enabled setting. This will prevent the
         * application from being enabled if it was previously in a disabled state.
         */
        public void setApplicationEnabledSettingPersistent() {
            this.applicationEnabledSettingPersistent = true;
        }

        /**
         * Optionally indicate whether the package being installed needs the update ownership
         * enforcement. Once the update ownership enforcement is enabled, the other installers
         * will need the user action to update the package even if the installers have been
         * granted the {@link android.Manifest.permission#INSTALL_PACKAGES INSTALL_PACKAGES}
         * permission. Default to {@code false}.
         *
         * The update ownership enforcement can only be enabled on initial installation. Set
         * this to {@code true} on package update is a no-op.
         *
         * Note: To enable the update ownership enforcement, the installer must have the
         * {@link android.Manifest.permission#ENFORCE_UPDATE_OWNERSHIP ENFORCE_UPDATE_OWNERSHIP}
         * permission.
         */
        @RequiresPermission(Manifest.permission.ENFORCE_UPDATE_OWNERSHIP)
        public void setRequestUpdateOwnership(boolean enable) {
            if (enable) {
                this.installFlags |= PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP;
            } else {
                this.installFlags &= ~PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP;
            }
        }

        /**
         * Used to set the unarchive ID received as part of an
         * {@link Intent#ACTION_UNARCHIVE_PACKAGE}.
         *
         * <p> The ID should be retrieved from the unarchive intent and passed into the
         * session that's being created to unarchive the app in question. Used to link the unarchive
         * intent and the install session to disambiguate.
         */
        @FlaggedApi(Flags.FLAG_ARCHIVING)
        public void setUnarchiveId(int unarchiveId) {
            this.unarchiveId = unarchiveId;
        }

        /** @hide */
        @NonNull
        public ArrayMap<String, Integer> getPermissionStates() {
            return mPermissionStates;
        }

        /** @hide */
        @Nullable
        public String[] getLegacyGrantedRuntimePermissions() {
            if ((installFlags & PackageManager.INSTALL_GRANT_ALL_REQUESTED_PERMISSIONS) != 0) {
                return null;
            }

            var grantedPermissions = new ArrayList<String>();
            for (int index = 0; index < mPermissionStates.size(); index++) {
                var permissionName = mPermissionStates.keyAt(index);
                var state = mPermissionStates.valueAt(index);
                if (state == PERMISSION_STATE_GRANTED) {
                    grantedPermissions.add(permissionName);
                }
            }

            return grantedPermissions.toArray(ArrayUtils.emptyArray(String.class));
        }

        /** {@hide} */
        public void dump(IndentingPrintWriter pw) {
            pw.printPair("mode", mode);
            pw.printHexPair("installFlags", installFlags);
            pw.printPair("installLocation", installLocation);
            pw.printPair("installReason", installReason);
            pw.printPair("installScenario", installScenario);
            pw.printPair("sizeBytes", sizeBytes);
            pw.printPair("appPackageName", appPackageName);
            pw.printPair("appIcon", (appIcon != null));
            pw.printPair("appLabel", appLabel);
            pw.printPair("originatingUri", originatingUri);
            pw.printPair("originatingUid", originatingUid);
            pw.printPair("referrerUri", referrerUri);
            pw.printPair("abiOverride", abiOverride);
            pw.printPair("volumeUuid", volumeUuid);
            pw.printPair("mPermissionStates", mPermissionStates);
            pw.printPair("packageSource", packageSource);
            pw.printPair("whitelistedRestrictedPermissions", whitelistedRestrictedPermissions);
            pw.printPair("autoRevokePermissions", autoRevokePermissionsMode);
            pw.printPair("installerPackageName", installerPackageName);
            pw.printPair("isMultiPackage", isMultiPackage);
            pw.printPair("isStaged", isStaged);
            pw.printPair("forceQueryable", forceQueryableOverride);
            pw.printPair("requireUserAction", SessionInfo.userActionToString(requireUserAction));
            pw.printPair("requiredInstalledVersionCode", requiredInstalledVersionCode);
            pw.printPair("dataLoaderParams", dataLoaderParams);
            pw.printPair("rollbackDataPolicy", rollbackDataPolicy);
            pw.printPair("rollbackLifetimeMillis", rollbackLifetimeMillis);
            pw.printPair("applicationEnabledSettingPersistent",
                    applicationEnabledSettingPersistent);
            pw.printHexPair("developmentInstallFlags", developmentInstallFlags);
            pw.printPair("unarchiveId", unarchiveId);
            pw.println();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mode);
            dest.writeInt(installFlags);
            dest.writeInt(installLocation);
            dest.writeInt(installReason);
            dest.writeInt(installScenario);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeString(abiOverride);
            dest.writeString(volumeUuid);
            dest.writeMap(mPermissionStates);
            dest.writeStringList(whitelistedRestrictedPermissions);
            dest.writeInt(autoRevokePermissionsMode);
            dest.writeString(installerPackageName);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
            dest.writeBoolean(forceQueryableOverride);
            dest.writeLong(requiredInstalledVersionCode);
            if (dataLoaderParams != null) {
                dest.writeParcelable(dataLoaderParams.getData(), flags);
            } else {
                dest.writeParcelable(null, flags);
            }
            dest.writeInt(rollbackDataPolicy);
            dest.writeLong(rollbackLifetimeMillis);
            dest.writeInt(requireUserAction);
            dest.writeInt(packageSource);
            dest.writeBoolean(applicationEnabledSettingPersistent);
            dest.writeInt(developmentInstallFlags);
            dest.writeInt(unarchiveId);
        }

        public static final Parcelable.Creator<SessionParams>
                CREATOR = new Parcelable.Creator<SessionParams>() {
                    @Override
                    public SessionParams createFromParcel(Parcel p) {
                        return new SessionParams(p);
                    }

                    @Override
                    public SessionParams[] newArray(int size) {
                        return new SessionParams[size];
                    }
                };
    }

    /**
     * Details for an active install session.
     */
    public static class SessionInfo implements Parcelable {

        /**
         * A session ID that does not exist or is invalid.
         */
        public static final int INVALID_ID = -1;
        /** {@hide} */
        private static final int[] NO_SESSIONS = {};

        /**
         * @deprecated use {@link #SESSION_NO_ERROR}.
         */
        @Deprecated
        public static final int STAGED_SESSION_NO_ERROR = 0;

        /**
         * @deprecated use {@link #SESSION_VERIFICATION_FAILED}.
         */
        @Deprecated
        public static final int STAGED_SESSION_VERIFICATION_FAILED = 1;

        /**
         * @deprecated use {@link #SESSION_ACTIVATION_FAILED}.
         */
        @Deprecated
        public static final int STAGED_SESSION_ACTIVATION_FAILED = 2;

        /**
         * @deprecated use {@link #SESSION_UNKNOWN_ERROR}.
         */
        @Deprecated
        public static final int STAGED_SESSION_UNKNOWN = 3;

        /**
         * @deprecated use {@link #SESSION_CONFLICT}.
         */
        @Deprecated
        public static final int STAGED_SESSION_CONFLICT = 4;

        /**
         * Constant indicating that no error occurred during the preparation or the activation of
         * this session.
         */
        public static final int SESSION_NO_ERROR = 0;

        /**
         * Constant indicating that an error occurred during the verification phase of
         * this session.
         */
        public static final int SESSION_VERIFICATION_FAILED = 1;

        /**
         * Constant indicating that an error occurred during the activation phase of
         * this session.
         */
        public static final int SESSION_ACTIVATION_FAILED = 2;

        /**
         * Constant indicating that an unknown error occurred while processing this session.
         */
        public static final int SESSION_UNKNOWN_ERROR = 3;

        /**
         * Constant indicating that the session was in conflict with another session and had
         * to be sacrificed for resolution.
         */
        public static final int SESSION_CONFLICT = 4;

        private static String userActionToString(int requireUserAction) {
            switch(requireUserAction) {
                case SessionParams.USER_ACTION_REQUIRED:
                    return "REQUIRED";
                case SessionParams.USER_ACTION_NOT_REQUIRED:
                    return "NOT_REQUIRED";
                default:
                    return "UNSPECIFIED";
            }
        }

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int sessionId;
        /** {@hide} */
        public int userId;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String installerPackageName;
        /** {@hide} */
        public String installerAttributionTag;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public String resolvedBaseCodePath;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public float progress;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public boolean sealed;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public boolean active;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int mode;
        /** {@hide} */
        public @InstallReason int installReason;
        /** {@hide} */
        public @InstallScenario int installScenario;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public long sizeBytes;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public String appPackageName;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public Bitmap appIcon;
        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public CharSequence appLabel;

        /** {@hide} */
        public int installLocation;
        /** {@hide} */
        public Uri originatingUri;
        /** {@hide} */
        public int originatingUid;
        /** {@hide} */
        public Uri referrerUri;
        /** {@hide} */
        public String[] grantedRuntimePermissions;
        /** {@hide}*/
        public List<String> whitelistedRestrictedPermissions;
        /** {@hide}*/
        public int autoRevokePermissionsMode = MODE_DEFAULT;
        /** {@hide} */
        public int installFlags;
        /** {@hide} */
        public boolean isMultiPackage;
        /** {@hide} */
        public boolean isStaged;
        /** {@hide} */
        public boolean forceQueryable;
        /** {@hide} */
        public int parentSessionId = INVALID_ID;
        /** {@hide} */
        public int[] childSessionIds = NO_SESSIONS;

        /** {@hide} */
        public boolean isSessionApplied;
        /** {@hide} */
        public boolean isSessionReady;
        /** {@hide} */
        public boolean isSessionFailed;
        private int mSessionErrorCode;
        private String mSessionErrorMessage;

        /** {@hide} */
        public boolean isCommitted;

        /** {@hide} */
        public long createdMillis;

        /** {@hide} */
        public long updatedMillis;

        /** {@hide} */
        public int rollbackDataPolicy;

        /** @hide */
        public long rollbackLifetimeMillis;

        /** {@hide} */
        public int requireUserAction;

        /** {@hide} */
        public int packageSource = PACKAGE_SOURCE_UNSPECIFIED;

        /** {@hide} */
        public int installerUid;

        /** @hide */
        public boolean isPreapprovalRequested;

        /** @hide */
        public boolean applicationEnabledSettingPersistent;

        /** @hide */
        public int pendingUserActionReason;

        /** {@hide} */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public SessionInfo() {
        }

        /** {@hide} */
        public SessionInfo(Parcel source) {
            sessionId = source.readInt();
            userId = source.readInt();
            installerPackageName = source.readString();
            installerAttributionTag = source.readString();
            resolvedBaseCodePath = source.readString();
            progress = source.readFloat();
            sealed = source.readInt() != 0;
            active = source.readInt() != 0;

            mode = source.readInt();
            installReason = source.readInt();
            installScenario = source.readInt();
            sizeBytes = source.readLong();
            appPackageName = source.readString();
            appIcon = source.readParcelable(null, android.graphics.Bitmap.class);
            appLabel = source.readString();

            installLocation = source.readInt();
            originatingUri = source.readParcelable(null, android.net.Uri.class);
            originatingUid = source.readInt();
            referrerUri = source.readParcelable(null, android.net.Uri.class);
            grantedRuntimePermissions = source.readStringArray();
            whitelistedRestrictedPermissions = source.createStringArrayList();
            autoRevokePermissionsMode = source.readInt();

            installFlags = source.readInt();
            isMultiPackage = source.readBoolean();
            isStaged = source.readBoolean();
            forceQueryable = source.readBoolean();
            parentSessionId = source.readInt();
            childSessionIds = source.createIntArray();
            if (childSessionIds == null) {
                childSessionIds = NO_SESSIONS;
            }
            isSessionApplied = source.readBoolean();
            isSessionReady = source.readBoolean();
            isSessionFailed = source.readBoolean();
            mSessionErrorCode = source.readInt();
            mSessionErrorMessage = source.readString();
            isCommitted = source.readBoolean();
            isPreapprovalRequested = source.readBoolean();
            rollbackDataPolicy = source.readInt();
            rollbackLifetimeMillis = source.readLong();
            createdMillis = source.readLong();
            requireUserAction = source.readInt();
            installerUid = source.readInt();
            packageSource = source.readInt();
            applicationEnabledSettingPersistent = source.readBoolean();
            pendingUserActionReason = source.readInt();
        }

        /**
         * Return the ID for this session.
         */
        public int getSessionId() {
            return sessionId;
        }

        /**
         * Return the user associated with this session.
         */
        public @NonNull UserHandle getUser() {
            return new UserHandle(userId);
        }

        /**
         * Return the package name of the app that owns this session.
         */
        public @Nullable String getInstallerPackageName() {
            return installerPackageName;
        }

        /**
         * @return {@link android.content.Context#getAttributionTag attribution tag} of the context
         * that created this session
         */
        public @Nullable String getInstallerAttributionTag() {
            return installerAttributionTag;
        }

        /**
         * Return current overall progress of this session, between 0 and 1.
         * <p>
         * Note that this progress may not directly correspond to the value
         * reported by
         * {@link PackageInstaller.Session#setStagingProgress(float)}, as the
         * system may carve out a portion of the overall progress to represent
         * its own internal installation work.
         */
        public float getProgress() {
            return progress;
        }

        /**
         * Return if this session is currently active.
         * <p>
         * A session is considered active whenever there is ongoing forward
         * progress being made, such as the installer holding an open
         * {@link Session} instance while streaming data into place, or the
         * system optimizing code as the result of
         * {@link Session#commit(IntentSender)}.
         * <p>
         * If the installer closes the {@link Session} without committing, the
         * session is considered inactive until the installer opens the session
         * again.
         */
        public boolean isActive() {
            return active;
        }

        /**
         * Return if this session is sealed.
         * <p>
         * Once sealed, no further changes may be made to the session. A session
         * is sealed the moment {@link Session#commit(IntentSender)} is called.
         */
        public boolean isSealed() {
            return sealed;
        }

        /**
         * Return the reason for installing this package.
         *
         * @return The install reason.
         */
        public @InstallReason int getInstallReason() {
            return installReason;
        }

        /** {@hide} */
        @Deprecated
        public boolean isOpen() {
            return isActive();
        }

        /**
         * Return the package name this session is working with. May be {@code null}
         * if unknown.
         */
        public @Nullable String getAppPackageName() {
            return appPackageName;
        }

        /**
         * Return an icon representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable Bitmap getAppIcon() {
            if (appIcon == null) {
                // Icon may have been omitted for calls that return bulk session
                // lists, so try fetching the specific icon.
                try {
                    final SessionInfo info = AppGlobals.getPackageManager().getPackageInstaller()
                            .getSessionInfo(sessionId);
                    appIcon = (info != null) ? info.appIcon : null;
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return appIcon;
        }

        /**
         * Return a label representing the app being installed. May be {@code null}
         * if unavailable.
         */
        public @Nullable CharSequence getAppLabel() {
            return appLabel;
        }

        /**
         * Return an Intent that can be started to view details about this install
         * session. This may surface actions such as pause, resume, or cancel.
         * <p>
         * In some cases, a matching Activity may not exist, so ensure you safeguard
         * against this.
         *
         * @see PackageInstaller#ACTION_SESSION_DETAILS
         */
        public @Nullable Intent createDetailsIntent() {
            final Intent intent = new Intent(PackageInstaller.ACTION_SESSION_DETAILS);
            intent.putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId);
            intent.setPackage(installerPackageName);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        }

        /**
         * Get the mode of the session as set in the constructor of the {@link SessionParams}.
         *
         * @return One of {@link SessionParams#MODE_FULL_INSTALL}
         *         or {@link SessionParams#MODE_INHERIT_EXISTING}
         */
        public int getMode() {
            return mode;
        }

        /**
         * Get the value set in {@link SessionParams#setInstallLocation(int)}.
         */
        public int getInstallLocation() {
            return installLocation;
        }

        /**
         * Get the value as set in {@link SessionParams#setSize(long)}.
         *
         * <p>The value is a hint and does not have to match the actual size.
         */
        public long getSize() {
            return sizeBytes;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUri(Uri)}.
         * Note: This value will only be non-null for the owner of the session.
         */
        public @Nullable Uri getOriginatingUri() {
            return originatingUri;
        }

        /**
         * Get the value set in {@link SessionParams#setOriginatingUid(int)}.
         */
        public int getOriginatingUid() {
            return originatingUid;
        }

        /**
         * Get the value set in {@link SessionParams#setReferrerUri(Uri)}
         * Note: This value will only be non-null for the owner of the session.
         */
        public @Nullable Uri getReferrerUri() {
            return referrerUri;
        }

        /**
         * @return the path to the validated base APK for this session, which may point at an
         * APK inside the session (when the session defines the base), or it may
         * point at the existing base APK (when adding splits to an existing app).
         *
         * @hide
         */
        @SystemApi
        @RequiresPermission(Manifest.permission.READ_INSTALLED_SESSION_PATHS)
        @FlaggedApi(Flags.FLAG_GET_RESOLVED_APK_PATH)
        public @Nullable String getResolvedBaseApkPath() {
            return resolvedBaseCodePath;
        }

        /**
         * Get the value set in {@link SessionParams#setGrantedRuntimePermissions(String[])}.
         *
         * @hide
         */
        @SystemApi
        public @Nullable String[] getGrantedRuntimePermissions() {
            return grantedRuntimePermissions;
        }

        /**
         * Get the value set in {@link SessionParams#setWhitelistedRestrictedPermissions(Set)}.
         * Note that if all permissions are allowlisted this method returns {@link
         * SessionParams#RESTRICTED_PERMISSIONS_ALL}.
         *
         * @hide
         */
        @SystemApi
        public @NonNull Set<String> getWhitelistedRestrictedPermissions() {
            if ((installFlags & PackageManager.INSTALL_ALL_WHITELIST_RESTRICTED_PERMISSIONS) != 0) {
                return SessionParams.RESTRICTED_PERMISSIONS_ALL;
            }
            if (whitelistedRestrictedPermissions != null) {
                return new ArraySet<>(whitelistedRestrictedPermissions);
            }
            return Collections.emptySet();
        }

        /**
         * Get the status of whether permission auto-revocation should be allowed, ignored, or
         * deferred to manifest data.
         *
         * @see android.app.AppOpsManager#MODE_ALLOWED
         * @see android.app.AppOpsManager#MODE_IGNORED
         * @see android.app.AppOpsManager#MODE_DEFAULT
         *
         * @return the status of auto-revoke for this package
         *
         * @hide
         */
        @SystemApi
        public int getAutoRevokePermissionsMode() {
            return autoRevokePermissionsMode;
        }

        /**
         * Get the value set in {@link SessionParams#setAllowDowngrade(boolean)}.
         *
         * @deprecated use {@link #getRequestDowngrade()}.
         * @hide
         */
        @SystemApi
        @Deprecated
        public boolean getAllowDowngrade() {
            return getRequestDowngrade();
        }

        /**
         * Get the value set in {@link SessionParams#setRequestDowngrade(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getRequestDowngrade() {
            return (installFlags & PackageManager.INSTALL_REQUEST_DOWNGRADE) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setDontKillApp(boolean)}.
         */
        public boolean getDontKillApp() {
            return (installFlags & PackageManager.INSTALL_DONT_KILL_APP) != 0;
        }

        /**
         * Get if this session is to be installed as Instant Apps.
         *
         * @param isInstantApp an unused parameter and is ignored.
         * @return {@code true} if {@link SessionParams#setInstallAsInstantApp(boolean)} was called
         * with {@code true}; {@code false} if it was called with {@code false} or if it was not
         * called.
         *
         * @see #getInstallAsFullApp
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsInstantApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_INSTANT_APP) != 0;
        }

        /**
         * Get if this session is to be installed as full apps.
         *
         * @param isInstantApp an unused parameter and is ignored.
         * @return {@code true} if {@link SessionParams#setInstallAsInstantApp(boolean)} was called
         * with {@code false}; {code false} if it was called with {@code true} or if it was not
         * called.
         *
         * @see #getInstallAsInstantApp
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsFullApp(boolean isInstantApp) {
            return (installFlags & PackageManager.INSTALL_FULL_APP) != 0;
        }

        /**
         * Get if {@link SessionParams#setInstallAsVirtualPreload()} was called.
         *
         * @hide
         */
        @SystemApi
        public boolean getInstallAsVirtualPreload() {
            return (installFlags & PackageManager.INSTALL_VIRTUAL_PRELOAD) != 0;
        }

        /**
         * Return whether rollback is enabled or disabled for the given upgrade.
         *
         * @hide
         */
        @SystemApi
        public boolean getEnableRollback() {
            return (installFlags & PackageManager.INSTALL_ENABLE_ROLLBACK) != 0;
        }

        /**
         * Get the value set in {@link SessionParams#setAllocateAggressive(boolean)}.
         *
         * @hide
         */
        @SystemApi
        public boolean getAllocateAggressive() {
            return (installFlags & PackageManager.INSTALL_ALLOCATE_AGGRESSIVE) != 0;
        }


        /** {@hide} */
        @Deprecated
        public @Nullable Intent getDetailsIntent() {
            return createDetailsIntent();
        }

        /**
         * Get the package source that was set in
         * {@link PackageInstaller.SessionParams#setPackageSource(int)}.
         */
        public @PackageSourceType int getPackageSource() {
            return packageSource;
        }

        /**
         * Returns true if this session is a multi-package session containing references to other
         * sessions.
         */
        public boolean isMultiPackage() {
            return isMultiPackage;
        }

        /**
         * Returns true if this session is a staged session.
         */
        public boolean isStaged() {
            return isStaged;
        }

        /**
         * Return the data policy associated with the rollback for the given upgrade.
         *
         * @hide
         */
        @SystemApi
        @PackageManager.RollbackDataPolicy
        public int getRollbackDataPolicy() {
            return rollbackDataPolicy;
        }

        /**
         * Returns true if this session is marked as forceQueryable
         * {@hide}
         */
        public boolean isForceQueryable() {
            return forceQueryable;
        }

        /**
         * Returns {@code true} if this session is an active staged session.
         *
         * We consider a session active if it has been committed and it is either pending
         * verification, or will be applied at next reboot.
         *
         * <p>Staged session is active iff:
         * <ul>
         *     <li>It is committed, i.e. {@link SessionInfo#isCommitted()} is {@code true}, and
         *     <li>it is not applied, i.e. {@link SessionInfo#isStagedSessionApplied()} is {@code
         *     false}, and
         *     <li>it is not failed, i.e. {@link SessionInfo#isStagedSessionFailed()} is
         *     {@code false}.
         * </ul>
         *
         * <p>In case of a multi-package session, reasoning above is applied to the parent session,
         * since that is the one that should have been {@link Session#commit committed}.
         */
        public boolean isStagedSessionActive() {
            return isStaged && isCommitted && !isSessionApplied && !isSessionFailed
                    && !hasParentSessionId();
        }

        /**
         * Returns the parent multi-package session ID if this session belongs to one,
         * {@link #INVALID_ID} otherwise.
         */
        public int getParentSessionId() {
            return parentSessionId;
        }

        /**
         * Returns true if session has a valid parent session, otherwise false.
         */
        public boolean hasParentSessionId() {
            return parentSessionId != INVALID_ID;
        }

        /**
         * Returns the set of session IDs that will be committed when this session is committed if
         * this session is a multi-package session.
         */
        @NonNull
        public int[] getChildSessionIds() {
            return childSessionIds;
        }

        private void checkSessionIsStaged() {
            if (!isStaged) {
                throw new IllegalStateException("Session is not marked as staged.");
            }
        }

        /**
         * Whether the staged session has been applied successfully, meaning that all of its
         * packages have been activated and no further action is required.
         * Only meaningful if {@code isStaged} is true.
         */
        public boolean isStagedSessionApplied() {
            checkSessionIsStaged();
            return isSessionApplied;
        }

        /**
         * Whether the staged session is ready to be applied at next reboot. Only meaningful if
         * {@code isStaged} is true.
         */
        public boolean isStagedSessionReady() {
            checkSessionIsStaged();
            return isSessionReady;
        }

        /**
         * Whether something went wrong and the staged session is declared as failed, meaning that
         * it will be ignored at next reboot. Only meaningful if {@code isStaged} is true.
         */
        public boolean isStagedSessionFailed() {
            checkSessionIsStaged();
            return isSessionFailed;
        }

        /**
         * If something went wrong with a staged session, clients can check this error code to
         * understand which kind of failure happened. Only meaningful if {@code isStaged} is true.
         */
        public int getStagedSessionErrorCode() {
            checkSessionIsStaged();
            return mSessionErrorCode;
        }

        /**
         * Text description of the error code returned by {@code getStagedSessionErrorCode}, or
         * empty string if no error was encountered.
         */
        public @NonNull String getStagedSessionErrorMessage() {
            checkSessionIsStaged();
            return mSessionErrorMessage;
        }

        /** {@hide} */
        public void setSessionErrorCode(int errorCode, String errorMessage) {
            mSessionErrorCode = errorCode;
            mSessionErrorMessage = errorMessage;
        }

        /**
         * Returns {@code true} if {@link Session#commit(IntentSender)}} was called for this
         * session.
         */
        public boolean isCommitted() {
            return isCommitted;
        }

        /**
         * The timestamp of the initial creation of the session.
         */
        public long getCreatedMillis() {
            return createdMillis;
        }

        /**
         * The timestamp of the last update that occurred to the session, including changing of
         * states in case of staged sessions.
         */
        @CurrentTimeMillisLong
        public long getUpdatedMillis() {
            return updatedMillis;
        }

        /**
         * Whether user action was required by the installer.
         *
         * <p>
         * Note: a return value of {@code USER_ACTION_NOT_REQUIRED} does not guarantee that the
         * install will not result in user action.
         *
         * @return {@link SessionParams#USER_ACTION_NOT_REQUIRED},
         *         {@link SessionParams#USER_ACTION_REQUIRED} or
         *         {@link SessionParams#USER_ACTION_UNSPECIFIED}
         */
        @SessionParams.UserActionRequirement
        public int getRequireUserAction() {
            return requireUserAction;
        }

        /**
         * Returns the Uid of the owner of the session.
         */
        public int getInstallerUid() {
            return installerUid;
        }

        /**
         * Returns {@code true} if this session will keep the existing application enabled setting
         * after installation.
         */
        public boolean isApplicationEnabledSettingPersistent() {
            return applicationEnabledSettingPersistent;
        }

        /**
         * Returns whether this session has requested user pre-approval.
         */
        public boolean isPreApprovalRequested() {
            return isPreapprovalRequested;
        }

        /**
         * @return {@code true} if the installer requested the update ownership enforcement
         * for the packages in this session.
         *
         * @see PackageInstaller.SessionParams#setRequestUpdateOwnership
         */
        public boolean isRequestUpdateOwnership() {
            return (installFlags & PackageManager.INSTALL_REQUEST_UPDATE_OWNERSHIP) != 0;
        }

        /**
         * Return the reason for requiring the user action.
         * @hide
         */
        @SystemApi
        public @UserActionReason int getPendingUserActionReason() {
            return pendingUserActionReason;
        }

        /**
         * Returns true if the session is an unarchival.
         *
         * @see PackageInstaller#requestUnarchive
         */
        @FlaggedApi(Flags.FLAG_ARCHIVING)
        public boolean isUnarchival() {
            return (installFlags & PackageManager.INSTALL_UNARCHIVE) != 0;
        }


        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(sessionId);
            dest.writeInt(userId);
            dest.writeString(installerPackageName);
            dest.writeString(installerAttributionTag);
            dest.writeString(resolvedBaseCodePath);
            dest.writeFloat(progress);
            dest.writeInt(sealed ? 1 : 0);
            dest.writeInt(active ? 1 : 0);

            dest.writeInt(mode);
            dest.writeInt(installReason);
            dest.writeInt(installScenario);
            dest.writeLong(sizeBytes);
            dest.writeString(appPackageName);
            dest.writeParcelable(appIcon, flags);
            dest.writeString(appLabel != null ? appLabel.toString() : null);

            dest.writeInt(installLocation);
            dest.writeParcelable(originatingUri, flags);
            dest.writeInt(originatingUid);
            dest.writeParcelable(referrerUri, flags);
            dest.writeStringArray(grantedRuntimePermissions);
            dest.writeStringList(whitelistedRestrictedPermissions);
            dest.writeInt(autoRevokePermissionsMode);
            dest.writeInt(installFlags);
            dest.writeBoolean(isMultiPackage);
            dest.writeBoolean(isStaged);
            dest.writeBoolean(forceQueryable);
            dest.writeInt(parentSessionId);
            dest.writeIntArray(childSessionIds);
            dest.writeBoolean(isSessionApplied);
            dest.writeBoolean(isSessionReady);
            dest.writeBoolean(isSessionFailed);
            dest.writeInt(mSessionErrorCode);
            dest.writeString(mSessionErrorMessage);
            dest.writeBoolean(isCommitted);
            dest.writeBoolean(isPreapprovalRequested);
            dest.writeInt(rollbackDataPolicy);
            dest.writeLong(rollbackLifetimeMillis);
            dest.writeLong(createdMillis);
            dest.writeInt(requireUserAction);
            dest.writeInt(installerUid);
            dest.writeInt(packageSource);
            dest.writeBoolean(applicationEnabledSettingPersistent);
            dest.writeInt(pendingUserActionReason);
        }

        public static final Parcelable.Creator<SessionInfo>
                CREATOR = new Parcelable.Creator<SessionInfo>() {
                    @Override
                    public SessionInfo createFromParcel(Parcel p) {
                        return new SessionInfo(p);
                    }

                    @Override
                    public SessionInfo[] newArray(int size) {
                        return new SessionInfo[size];
                    }
                };
    }

    /**
     * Details for requesting the pre-commit install approval.
     */
    @DataClass(genConstructor = false, genToString = true)
    public static final class PreapprovalDetails implements Parcelable {
        /**
         * The icon representing the app to be installed.
         */
        private final @Nullable Bitmap mIcon;
        /**
         * The label representing the app to be installed.
         */
        private final @NonNull CharSequence mLabel;
        /**
         * The locale of the app label being used.
         */
        private final @NonNull ULocale mLocale;
        /**
         * The package name of the app to be installed.
         */
        private final @NonNull String mPackageName;

        /**
         * Creates a new PreapprovalDetails.
         *
         * @param icon
         *   The icon representing the app to be installed.
         * @param label
         *   The label representing the app to be installed.
         * @param locale
         *   The locale is used to get the app label from the APKs (includes the base APK and
         *   split APKs) related to the package to be installed.
         * @param packageName
         *   The package name of the app to be installed.
         * @hide
         */
        public PreapprovalDetails(
                @Nullable Bitmap icon,
                @NonNull CharSequence label,
                @NonNull ULocale locale,
                @NonNull String packageName) {
            mIcon = icon;
            mLabel = label;
            Preconditions.checkArgument(!TextUtils.isEmpty(mLabel),
                    "App label cannot be empty.");
            mLocale = locale;
            Preconditions.checkArgument(!Objects.isNull(mLocale),
                    "Locale cannot be null.");
            mPackageName = packageName;
            Preconditions.checkArgument(!TextUtils.isEmpty(mPackageName),
                    "Package name cannot be empty.");
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            byte flg = 0;
            if (mIcon != null) flg |= 0x1;
            dest.writeByte(flg);
            if (mIcon != null) mIcon.writeToParcel(dest, flags);
            dest.writeCharSequence(mLabel);
            dest.writeString8(mLocale.toString());
            dest.writeString8(mPackageName);
        }

        @Override
        public int describeContents() { return 0; }

        /** @hide */
        /* package-private */ PreapprovalDetails(@NonNull Parcel in) {
            byte flg = in.readByte();
            final Bitmap icon = (flg & 0x1) == 0 ? null : Bitmap.CREATOR.createFromParcel(in);
            final CharSequence label = in.readCharSequence();
            final ULocale locale = new ULocale(in.readString8());
            final String packageName = in.readString8();

            mIcon = icon;
            mLabel = label;
            Preconditions.checkArgument(!TextUtils.isEmpty(mLabel),
                    "App label cannot be empty.");
            mLocale = locale;
            Preconditions.checkArgument(!Objects.isNull(mLocale),
                    "Locale cannot be null.");
            mPackageName = packageName;
            Preconditions.checkArgument(!TextUtils.isEmpty(mPackageName),
                    "Package name cannot be empty.");
        }

        public static final @NonNull Parcelable.Creator<PreapprovalDetails> CREATOR
                = new Parcelable.Creator<PreapprovalDetails>() {
            @Override
            public PreapprovalDetails[] newArray(int size) {
                return new PreapprovalDetails[size];
            }

            @Override
            public PreapprovalDetails createFromParcel(@NonNull Parcel in) {
                return new PreapprovalDetails(in);
            }
        };

        /**
         * A builder for {@link PreapprovalDetails}
         */
        public static final class Builder {

            private @Nullable Bitmap mIcon;
            private @NonNull CharSequence mLabel;
            private @NonNull ULocale mLocale;
            private @NonNull String mPackageName;

            private long mBuilderFieldsSet = 0L;

            /**
             * Creates a new Builder.
             */
            public Builder() {}

            /**
             * The icon representing the app to be installed.
             */
            public @NonNull Builder setIcon(@NonNull Bitmap value) {
                checkNotUsed();
                mBuilderFieldsSet |= 0x1;
                mIcon = value;
                return this;
            }

            /**
             * The label representing the app to be installed.
             */
            public @NonNull Builder setLabel(@NonNull CharSequence value) {
                checkNotUsed();
                mBuilderFieldsSet |= 0x2;
                mLabel = value;
                return this;
            }

            /**
             * The locale is used to get the app label from the APKs (includes the base APK and
             * split APKs) related to the package to be installed. The caller needs to make sure
             * the app label is consistent with the app label of {@link PreapprovalDetails} when
             * validating the installation. Otherwise, the pre-approval install session will fail.
             */
            public @NonNull Builder setLocale(@NonNull ULocale value) {
                checkNotUsed();
                mBuilderFieldsSet |= 0x4;
                mLocale = value;
                return this;
            }

            /**
             * The package name of the app to be installed.
             */
            public @NonNull Builder setPackageName(@NonNull String value) {
                checkNotUsed();
                mBuilderFieldsSet |= 0x8;
                mPackageName = value;
                return this;
            }

            /** Builds the instance. This builder should not be touched after calling this! */
            public @NonNull PreapprovalDetails build() {
                checkNotUsed();
                mBuilderFieldsSet |= 0x10; // Mark builder used

                PreapprovalDetails o = new PreapprovalDetails(
                        mIcon,
                        mLabel,
                        mLocale,
                        mPackageName);
                return o;
            }

            private void checkNotUsed() {
                if ((mBuilderFieldsSet & 0x10) != 0) {
                    throw new IllegalStateException("This Builder should not be reused. "
                            + "Use a new Builder instance instead");
                }
            }
        }




        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/PackageInstaller.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * The icon representing the app to be installed.
         */
        @DataClass.Generated.Member
        public @Nullable Bitmap getIcon() {
            return mIcon;
        }

        /**
         * The label representing the app to be installed.
         */
        @DataClass.Generated.Member
        public @NonNull CharSequence getLabel() {
            return mLabel;
        }

        /**
         * The locale of the app label being used.
         */
        @DataClass.Generated.Member
        public @NonNull ULocale getLocale() {
            return mLocale;
        }

        /**
         * The package name of the app to be installed.
         */
        @DataClass.Generated.Member
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        @Override
        @DataClass.Generated.Member
        public String toString() {
            // You can override field toString logic by defining methods like:
            // String fieldNameToString() { ... }

            return "PreapprovalDetails { " +
                    "icon = " + mIcon + ", " +
                    "label = " + mLabel + ", " +
                    "locale = " + mLocale + ", " +
                    "packageName = " + mPackageName +
            " }";
        }

        @DataClass.Generated(
                time = 1676970504308L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/content/pm/PackageInstaller.java",
                inputSignatures = "private final @android.annotation.Nullable android.graphics.Bitmap mIcon\nprivate final @android.annotation.NonNull java.lang.CharSequence mLabel\nprivate final @android.annotation.NonNull android.icu.util.ULocale mLocale\nprivate final @android.annotation.NonNull java.lang.String mPackageName\npublic static final @android.annotation.NonNull android.os.Parcelable.Creator<android.content.pm.PackageInstaller.PreapprovalDetails> CREATOR\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\npublic @java.lang.Override int describeContents()\nclass PreapprovalDetails extends java.lang.Object implements [android.os.Parcelable]\nprivate @android.annotation.Nullable android.graphics.Bitmap mIcon\nprivate @android.annotation.NonNull java.lang.CharSequence mLabel\nprivate @android.annotation.NonNull android.icu.util.ULocale mLocale\nprivate @android.annotation.NonNull java.lang.String mPackageName\nprivate  long mBuilderFieldsSet\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.PreapprovalDetails.Builder setIcon(android.graphics.Bitmap)\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.PreapprovalDetails.Builder setLabel(java.lang.CharSequence)\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.PreapprovalDetails.Builder setLocale(android.icu.util.ULocale)\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.PreapprovalDetails.Builder setPackageName(java.lang.String)\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.PreapprovalDetails build()\nprivate  void checkNotUsed()\nclass Builder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genToString=true)")

        @Deprecated
        private void __metadata() {}

        //@formatter:on
        // End of generated code

    }

    /**
     * The callback result of {@link #checkInstallConstraints(List, InstallConstraints, Executor, Consumer)}.
     */
    @DataClass(genParcelable = true, genHiddenConstructor = true)
    @DataClass.Suppress("isAllConstraintsSatisfied")
    public static final class InstallConstraintsResult implements Parcelable {
        /**
         * True if all constraints are satisfied.
         */
        private boolean mAllConstraintsSatisfied;

        /**
         * True if all constraints are satisfied.
         */
        public boolean areAllConstraintsSatisfied() {
            return mAllConstraintsSatisfied;
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/PackageInstaller.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new InstallConstraintsResult.
         *
         * @param allConstraintsSatisfied
         *   True if all constraints are satisfied.
         * @hide
         */
        @DataClass.Generated.Member
        public InstallConstraintsResult(
                boolean allConstraintsSatisfied) {
            this.mAllConstraintsSatisfied = allConstraintsSatisfied;

            // onConstructed(); // You can define this method to get a callback
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mAllConstraintsSatisfied) flg |= 0x1;
            dest.writeByte(flg);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ InstallConstraintsResult(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            boolean allConstraintsSatisfied = (flg & 0x1) != 0;

            this.mAllConstraintsSatisfied = allConstraintsSatisfied;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<InstallConstraintsResult> CREATOR
                = new Parcelable.Creator<InstallConstraintsResult>() {
            @Override
            public InstallConstraintsResult[] newArray(int size) {
                return new InstallConstraintsResult[size];
            }

            @Override
            public InstallConstraintsResult createFromParcel(@NonNull Parcel in) {
                return new InstallConstraintsResult(in);
            }
        };

        @DataClass.Generated(
                time = 1676970504336L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/content/pm/PackageInstaller.java",
                inputSignatures = "private  boolean mAllConstraintsSatisfied\npublic  boolean areAllConstraintsSatisfied()\nclass InstallConstraintsResult extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genParcelable=true, genHiddenConstructor=true)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }

    /**
     * A class to encapsulate constraints for installation.
     *
     * When used with {@link #checkInstallConstraints(List, InstallConstraints, Executor, Consumer)}, it
     * specifies the conditions to check against for the packages in question. This can be used
     * by app stores to deliver auto updates without disrupting the user experience (referred as
     * gentle update) - for example, an app store might hold off updates when it find out the
     * app to update is interacting with the user.
     *
     * Use {@link Builder} to create a new instance and call mutator methods to add constraints.
     * If no mutators were called, default constraints will be generated which implies no
     * constraints. It is recommended to use preset constraints which are useful in most
     * cases.
     *
     * For the purpose of gentle update, it is recommended to always use {@link #GENTLE_UPDATE}
     * for the system knows best how to do it. It will also benefits the installer as the
     * platform evolves and add more constraints to improve the accuracy and efficiency of
     * gentle update.
     *
     * Note the constraints are applied transitively. If app Foo is used by app Bar (via shared
     * library or bounded service), the constraints will also be applied to Bar.
     */
    @DataClass(genParcelable = true, genHiddenConstructor = true, genEqualsHashCode=true)
    public static final class InstallConstraints implements Parcelable {
        /**
         * Preset constraints suitable for gentle update.
         */
        @NonNull
        public static final InstallConstraints GENTLE_UPDATE =
                new Builder().setAppNotInteractingRequired().build();

        private final boolean mDeviceIdleRequired;
        private final boolean mAppNotForegroundRequired;
        private final boolean mAppNotInteractingRequired;
        private final boolean mAppNotTopVisibleRequired;
        private final boolean mNotInCallRequired;

        /**
         * Builder class for constructing {@link InstallConstraints}.
         */
        public static final class Builder {
            private boolean mDeviceIdleRequired;
            private boolean mAppNotForegroundRequired;
            private boolean mAppNotInteractingRequired;
            private boolean mAppNotTopVisibleRequired;
            private boolean mNotInCallRequired;

            /**
             * This constraint requires the device is idle.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setDeviceIdleRequired() {
                mDeviceIdleRequired = true;
                return this;
            }

            /**
             * This constraint requires the app in question is not in the foreground.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setAppNotForegroundRequired() {
                mAppNotForegroundRequired = true;
                return this;
            }

            /**
             * This constraint requires the app in question is not interacting with the user.
             * User interaction includes:
             * <ul>
             *     <li>playing or recording audio/video</li>
             *     <li>sending or receiving network data</li>
             *     <li>being visible to the user</li>
             * </ul>
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setAppNotInteractingRequired() {
                mAppNotInteractingRequired = true;
                return this;
            }

            /**
             * This constraint requires the app in question is not top-visible to the user.
             * A top-visible app is showing UI at the top of the screen that the user is
             * interacting with.
             *
             * Note this constraint is a subset of {@link #setAppNotForegroundRequired()}
             * because a top-visible app is also a foreground app. This is also a subset
             * of {@link #setAppNotInteractingRequired()} because a top-visible app is interacting
             * with the user.
             *
             * @see ActivityManager.RunningAppProcessInfo#IMPORTANCE_FOREGROUND
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setAppNotTopVisibleRequired() {
                mAppNotTopVisibleRequired = true;
                return this;
            }

            /**
             * This constraint requires there is no ongoing call in the device.
             */
            @SuppressLint("MissingGetterMatchingBuilder")
            @NonNull
            public Builder setNotInCallRequired() {
                mNotInCallRequired = true;
                return this;
            }

            /**
             * Builds a new {@link InstallConstraints} instance.
             */
            @NonNull
            public InstallConstraints build() {
                return new InstallConstraints(mDeviceIdleRequired, mAppNotForegroundRequired,
                        mAppNotInteractingRequired, mAppNotTopVisibleRequired, mNotInCallRequired);
            }
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/PackageInstaller.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new InstallConstraints.
         *
         * @hide
         */
        @DataClass.Generated.Member
        public InstallConstraints(
                boolean deviceIdleRequired,
                boolean appNotForegroundRequired,
                boolean appNotInteractingRequired,
                boolean appNotTopVisibleRequired,
                boolean notInCallRequired) {
            this.mDeviceIdleRequired = deviceIdleRequired;
            this.mAppNotForegroundRequired = appNotForegroundRequired;
            this.mAppNotInteractingRequired = appNotInteractingRequired;
            this.mAppNotTopVisibleRequired = appNotTopVisibleRequired;
            this.mNotInCallRequired = notInCallRequired;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public boolean isDeviceIdleRequired() {
            return mDeviceIdleRequired;
        }

        @DataClass.Generated.Member
        public boolean isAppNotForegroundRequired() {
            return mAppNotForegroundRequired;
        }

        @DataClass.Generated.Member
        public boolean isAppNotInteractingRequired() {
            return mAppNotInteractingRequired;
        }

        @DataClass.Generated.Member
        public boolean isAppNotTopVisibleRequired() {
            return mAppNotTopVisibleRequired;
        }

        @DataClass.Generated.Member
        public boolean isNotInCallRequired() {
            return mNotInCallRequired;
        }

        @Override
        @DataClass.Generated.Member
        public boolean equals(@Nullable Object o) {
            // You can override field equality logic by defining either of the methods like:
            // boolean fieldNameEquals(InstallConstraints other) { ... }
            // boolean fieldNameEquals(FieldType otherValue) { ... }

            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            @SuppressWarnings("unchecked")
            InstallConstraints that = (InstallConstraints) o;
            //noinspection PointlessBooleanExpression
            return true
                    && mDeviceIdleRequired == that.mDeviceIdleRequired
                    && mAppNotForegroundRequired == that.mAppNotForegroundRequired
                    && mAppNotInteractingRequired == that.mAppNotInteractingRequired
                    && mAppNotTopVisibleRequired == that.mAppNotTopVisibleRequired
                    && mNotInCallRequired == that.mNotInCallRequired;
        }

        @Override
        @DataClass.Generated.Member
        public int hashCode() {
            // You can override field hashCode logic by defining methods like:
            // int fieldNameHashCode() { ... }

            int _hash = 1;
            _hash = 31 * _hash + Boolean.hashCode(mDeviceIdleRequired);
            _hash = 31 * _hash + Boolean.hashCode(mAppNotForegroundRequired);
            _hash = 31 * _hash + Boolean.hashCode(mAppNotInteractingRequired);
            _hash = 31 * _hash + Boolean.hashCode(mAppNotTopVisibleRequired);
            _hash = 31 * _hash + Boolean.hashCode(mNotInCallRequired);
            return _hash;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mDeviceIdleRequired) flg |= 0x1;
            if (mAppNotForegroundRequired) flg |= 0x2;
            if (mAppNotInteractingRequired) flg |= 0x4;
            if (mAppNotTopVisibleRequired) flg |= 0x8;
            if (mNotInCallRequired) flg |= 0x10;
            dest.writeByte(flg);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ InstallConstraints(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            boolean deviceIdleRequired = (flg & 0x1) != 0;
            boolean appNotForegroundRequired = (flg & 0x2) != 0;
            boolean appNotInteractingRequired = (flg & 0x4) != 0;
            boolean appNotTopVisibleRequired = (flg & 0x8) != 0;
            boolean notInCallRequired = (flg & 0x10) != 0;

            this.mDeviceIdleRequired = deviceIdleRequired;
            this.mAppNotForegroundRequired = appNotForegroundRequired;
            this.mAppNotInteractingRequired = appNotInteractingRequired;
            this.mAppNotTopVisibleRequired = appNotTopVisibleRequired;
            this.mNotInCallRequired = notInCallRequired;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<InstallConstraints> CREATOR
                = new Parcelable.Creator<InstallConstraints>() {
            @Override
            public InstallConstraints[] newArray(int size) {
                return new InstallConstraints[size];
            }

            @Override
            public InstallConstraints createFromParcel(@NonNull Parcel in) {
                return new InstallConstraints(in);
            }
        };

        @DataClass.Generated(
                time = 1676970504352L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/core/java/android/content/pm/PackageInstaller.java",
                inputSignatures = "public static final @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints GENTLE_UPDATE\nprivate final  boolean mDeviceIdleRequired\nprivate final  boolean mAppNotForegroundRequired\nprivate final  boolean mAppNotInteractingRequired\nprivate final  boolean mAppNotTopVisibleRequired\nprivate final  boolean mNotInCallRequired\nclass InstallConstraints extends java.lang.Object implements [android.os.Parcelable]\nprivate  boolean mDeviceIdleRequired\nprivate  boolean mAppNotForegroundRequired\nprivate  boolean mAppNotInteractingRequired\nprivate  boolean mAppNotTopVisibleRequired\nprivate  boolean mNotInCallRequired\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints.Builder setDeviceIdleRequired()\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints.Builder setAppNotForegroundRequired()\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints.Builder setAppNotInteractingRequired()\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints.Builder setAppNotTopVisibleRequired()\npublic @android.annotation.SuppressLint @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints.Builder setNotInCallRequired()\npublic @android.annotation.NonNull android.content.pm.PackageInstaller.InstallConstraints build()\nclass Builder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genParcelable=true, genHiddenConstructor=true, genEqualsHashCode=true)")

        @Deprecated
        private void __metadata() {}

        //@formatter:on
        // End of generated code

    }

    /**
     * Used to communicate the unarchival state in {@link #reportUnarchivalState}.
     */
    @FlaggedApi(Flags.FLAG_ARCHIVING)
    public static final class UnarchivalState {

        /**
         * The caller is able to facilitate the unarchival for the given {@code unarchiveId}.
         *
         * @param unarchiveId the ID provided by the system as part of the intent.action.UNARCHIVE
         *                    broadcast with EXTRA_UNARCHIVE_ID.
         */
        @NonNull
        public static UnarchivalState createOkState(int unarchiveId) {
            return new UnarchivalState(unarchiveId, UNARCHIVAL_OK, /* requiredStorageBytes= */ -1,
                    /* userActionIntent= */ null);
        }

        /**
         * User action is required before commencing with the unarchival for the given
         * {@code unarchiveId}. E.g., this could be used if it's necessary for the user to sign-in
         * first.
         *
         * @param unarchiveId      the ID provided by the system as part of the
         *                         intent.action.UNARCHIVE
         *                         broadcast with EXTRA_UNARCHIVE_ID.
         * @param userActionIntent optional intent to start a follow up action required to
         *                         facilitate the unarchival flow (e.g. user needs to log in).
         */
        @NonNull
        public static UnarchivalState createUserActionRequiredState(int unarchiveId,
                @NonNull PendingIntent userActionIntent) {
            Objects.requireNonNull(userActionIntent);
            return new UnarchivalState(unarchiveId, UNARCHIVAL_ERROR_USER_ACTION_NEEDED,
                    /* requiredStorageBytes= */ -1, userActionIntent);
        }

        /**
         * There is not enough storage to start the unarchival for the given {@code unarchiveId}.
         *
         * @param unarchiveId          the ID provided by the system as part of the
         *                             intent.action.UNARCHIVE
         *                             broadcast with EXTRA_UNARCHIVE_ID.
         * @param requiredStorageBytes ff the error is UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE this
         *                             field should be set to specify how many additional bytes of
         *                             storage are required to unarchive the app.
         * @param userActionIntent     can optionally be set to provide a custom storage-clearing
         *                             action.
         */
        @NonNull
        public static UnarchivalState createInsufficientStorageState(int unarchiveId,
                long requiredStorageBytes, @Nullable PendingIntent userActionIntent) {
            return new UnarchivalState(unarchiveId, UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE,
                    requiredStorageBytes, userActionIntent);
        }

        /**
         * The device has no data connectivity and unarchival cannot be started for the given
         * {@code unarchiveId}.
         *
         * @param unarchiveId the ID provided by the system as part of the intent.action.UNARCHIVE
         *                    broadcast with EXTRA_UNARCHIVE_ID.
         */
        @NonNull
        public static UnarchivalState createNoConnectivityState(int unarchiveId) {
            return new UnarchivalState(unarchiveId, UNARCHIVAL_ERROR_NO_CONNECTIVITY,
                    /* requiredStorageBytes= */ -1,/* userActionIntent= */ null);
        }

        /**
         * Generic error state for all cases that are not covered by other methods in this class.
         *
         * @param unarchiveId the ID provided by the system as part of the intent.action.UNARCHIVE
         *                    broadcast with EXTRA_UNARCHIVE_ID.
         */
        @NonNull
        public static UnarchivalState createGenericErrorState(int unarchiveId) {
            return new UnarchivalState(unarchiveId, UNARCHIVAL_GENERIC_ERROR,
                    /* requiredStorageBytes= */ -1,/* userActionIntent= */ null);
        }


        /**
         * The ID provided by the system as part of the intent.action.UNARCHIVE broadcast with
         * EXTRA_UNARCHIVE_ID.
         */
        private final int mUnarchiveId;

        /** Used for the system to provide the user with necessary follow-up steps or errors. */
        @UnarchivalStatus
        private final int mStatus;

        /**
         * If the error is UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE this field should be set to specify
         * how many additional bytes of storage are required to unarchive the app.
         */
        private final long mRequiredStorageBytes;

        /**
         * Optional intent to start a follow up action required to facilitate the unarchival flow
         * (e.g., user needs to log in).
         */
        @Nullable
        private final PendingIntent mUserActionIntent;

        /**
         * Creates a new UnarchivalState.
         *
         * @param unarchiveId          The ID provided by the system as part of the
         *                             intent.action.UNARCHIVE broadcast with
         *                             EXTRA_UNARCHIVE_ID.
         * @param status               Used for the system to provide the user with necessary
         *                             follow-up steps or errors.
         * @param requiredStorageBytes If the error is UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE this
         *                             field should be set to specify
         *                             how many additional bytes of storage are required to
         *                             unarchive the app.
         * @param userActionIntent     Optional intent to start a follow up action required to
         *                             facilitate the unarchival flow
         *                             (e.g,. user needs to log in).
         * @hide
         */
        private UnarchivalState(
                int unarchiveId,
                @UnarchivalStatus int status,
                long requiredStorageBytes,
                @Nullable PendingIntent userActionIntent) {
            this.mUnarchiveId = unarchiveId;
            this.mStatus = status;
            com.android.internal.util.AnnotationValidations.validate(
                    UnarchivalStatus.class, null, mStatus);
            this.mRequiredStorageBytes = requiredStorageBytes;
            this.mUserActionIntent = userActionIntent;
        }

        /**
         * The ID provided by the system as part of the intent.action.UNARCHIVE broadcast with
         * EXTRA_UNARCHIVE_ID.
         *
         * @hide
         */
        int getUnarchiveId() {
            return mUnarchiveId;
        }

        /**
         * Used for the system to provide the user with necessary follow-up steps or errors.
         *
         * @hide
         */
        @UnarchivalStatus int getStatus() {
            return mStatus;
        }

        /**
         * If the error is UNARCHIVAL_ERROR_INSUFFICIENT_STORAGE this field should be set to specify
         * how many additional bytes of storage are required to unarchive the app.
         *
         * @hide
         */
        long getRequiredStorageBytes() {
            return mRequiredStorageBytes;
        }

        /**
         * Optional intent to start a follow up action required to facilitate the unarchival flow
         * (e.g. user needs to log in).
         *
         * @hide
         */
        @Nullable PendingIntent getUserActionIntent() {
            return mUserActionIntent;
        }
    }

}
