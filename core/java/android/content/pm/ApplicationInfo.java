/*
 * Copyright (C) 2007 The Android Open Source Project
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

import static android.os.Build.VERSION_CODES.DONUT;

import android.Manifest;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.compat.CompatChanges;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.window.OnBackInvokedCallback;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForBoolean;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Information you can retrieve about a particular application.  This
 * corresponds to information collected from the AndroidManifest.xml's
 * &lt;application&gt; tag.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class ApplicationInfo extends PackageItemInfo implements Parcelable {
    private static final ForBoolean sForBoolean = Parcelling.Cache.getOrCreate(ForBoolean.class);
    private static final Parcelling.BuiltIn.ForStringSet sForStringSet =
            Parcelling.Cache.getOrCreate(Parcelling.BuiltIn.ForStringSet.class);

    /**
     * Default task affinity of all activities in this application. See
     * {@link ActivityInfo#taskAffinity} for more information.  This comes
     * from the "taskAffinity" attribute.
     */
    public String taskAffinity;

    /**
     * Optional name of a permission required to be able to access this
     * application's components.  From the "permission" attribute.
     */
    public String permission;

    /**
     * The name of the process this application should run in.  From the
     * "process" attribute or, if not set, the same as
     * <var>packageName</var>.
     */
    public String processName;

    /**
     * Class implementing the Application object.  From the "class"
     * attribute.
     */
    public String className;

    /**
     * A style resource identifier (in the package's resources) of the
     * description of an application.  From the "description" attribute
     * or, if not set, 0.
     */
    public int descriptionRes;

    /**
     * A style resource identifier (in the package's resources) of the
     * default visual theme of the application.  From the "theme" attribute
     * or, if not set, 0.
     */
    public int theme;

    /**
     * Class implementing the Application's manage space
     * functionality.  From the "manageSpaceActivity"
     * attribute. This is an optional attribute and will be null if
     * applications don't specify it in their manifest
     */
    public String manageSpaceActivityName;

    /**
     * Class implementing the Application's backup functionality.  From
     * the "backupAgent" attribute.  This is an optional attribute and
     * will be null if the application does not specify it in its manifest.
     *
     * <p>If android:allowBackup is set to false, this attribute is ignored.
     */
    public String backupAgentName;

    /**
     * An optional attribute that indicates the app supports automatic backup of app data.
     * <p>0 is the default and means the app's entire data folder + managed external storage will
     * be backed up;
     * Any negative value indicates the app does not support full-data backup, though it may still
     * want to participate via the traditional key/value backup API;
     * A positive number specifies an xml resource in which the application has defined its backup
     * include/exclude criteria.
     * <p>If android:allowBackup is set to false, this attribute is ignored.
     *
     * @see android.content.Context#getNoBackupFilesDir()
     * @see #FLAG_ALLOW_BACKUP
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int fullBackupContent = 0;

    /**
     * Applications can set this attribute to an xml resource within their app where they specified
     * the rules determining which files and directories can be copied from the device as part of
     * backup or transfer operations.
     *<p>
     * Set from the {@link android.R.styleable#AndroidManifestApplication_dataExtractionRules}
     * attribute in the manifest.
     *
     * @hide
     */
    public int dataExtractionRulesRes = 0;

    /**
     * <code>true</code> if the package is capable of presenting a unified interface representing
     * multiple profiles.
     * @hide
     */
    public boolean crossProfile;

    /**
     * The default extra UI options for activities in this application.
     * Set from the {@link android.R.attr#uiOptions} attribute in the
     * activity's manifest.
     */
    public int uiOptions = 0;

    /**
     * Value for {@link #flags}: if set, this application is installed in the device's system image.
     * This should not be used to make security decisions. Instead, rely on
     * {@linkplain android.content.pm.PackageManager#checkSignatures(java.lang.String,java.lang.String)
     * signature checks} or
     * <a href="https://developer.android.com/training/articles/security-tips#Permissions">permissions</a>.
     *
     * <p><b>Warning:</b> Note that this flag does not behave the same as
     * {@link android.R.attr#protectionLevel android:protectionLevel} {@code system} or
     * {@code signatureOrSystem}.
     */
    public static final int FLAG_SYSTEM = 1<<0;

    /**
     * Value for {@link #flags}: set to true if this application would like to
     * allow debugging of its
     * code, even when installed on a non-development system.  Comes
     * from {@link android.R.styleable#AndroidManifestApplication_debuggable
     * android:debuggable} of the &lt;application&gt; tag.
     */
    public static final int FLAG_DEBUGGABLE = 1<<1;

    /**
     * Value for {@link #flags}: set to true if this application has code
     * associated with it.  Comes
     * from {@link android.R.styleable#AndroidManifestApplication_hasCode
     * android:hasCode} of the &lt;application&gt; tag.
     */
    public static final int FLAG_HAS_CODE = 1<<2;

    /**
     * Value for {@link #flags}: set to true if this application is persistent.
     * Comes from {@link android.R.styleable#AndroidManifestApplication_persistent
     * android:persistent} of the &lt;application&gt; tag.
     */
    public static final int FLAG_PERSISTENT = 1<<3;

    /**
     * Value for {@link #flags}: set to true if this application holds the
     * {@link android.Manifest.permission#FACTORY_TEST} permission and the
     * device is running in factory test mode.
     */
    public static final int FLAG_FACTORY_TEST = 1<<4;

    /**
     * Value for {@link #flags}: default value for the corresponding ActivityInfo flag.
     * Comes from {@link android.R.styleable#AndroidManifestApplication_allowTaskReparenting
     * android:allowTaskReparenting} of the &lt;application&gt; tag.
     */
    public static final int FLAG_ALLOW_TASK_REPARENTING = 1<<5;

    /**
     * Value for {@link #flags}: default value for the corresponding ActivityInfo flag.
     * Comes from {@link android.R.styleable#AndroidManifestApplication_allowClearUserData
     * android:allowClearUserData} of the &lt;application&gt; tag.
     */
    public static final int FLAG_ALLOW_CLEAR_USER_DATA = 1<<6;

    /**
     * Value for {@link #flags}: this is set if this application has been
     * installed as an update to a built-in system application.
     */
    public static final int FLAG_UPDATED_SYSTEM_APP = 1<<7;

    /**
     * Value for {@link #flags}: this is set if the application has specified
     * {@link android.R.styleable#AndroidManifestApplication_testOnly
     * android:testOnly} to be true.
     */
    public static final int FLAG_TEST_ONLY = 1<<8;

    /**
     * Value for {@link #flags}: true when the application's window can be
     * reduced in size for smaller screens.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_smallScreens
     * android:smallScreens}.
     */
    public static final int FLAG_SUPPORTS_SMALL_SCREENS = 1<<9;

    /**
     * Value for {@link #flags}: true when the application's window can be
     * displayed on normal screens.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_normalScreens
     * android:normalScreens}.
     */
    public static final int FLAG_SUPPORTS_NORMAL_SCREENS = 1<<10;

    /**
     * Value for {@link #flags}: true when the application's window can be
     * increased in size for larger screens.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_largeScreens
     * android:largeScreens}.
     */
    public static final int FLAG_SUPPORTS_LARGE_SCREENS = 1<<11;

    /**
     * Value for {@link #flags}: true when the application knows how to adjust
     * its UI for different screen sizes.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_resizeable
     * android:resizeable}.
     */
    public static final int FLAG_RESIZEABLE_FOR_SCREENS = 1<<12;

    /**
     * Value for {@link #flags}: true when the application knows how to
     * accommodate different screen densities.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_anyDensity
     * android:anyDensity}.
     *
     * @deprecated Set by default when targeting API 4 or higher and apps
     *             should not set this to false.
     */
    @Deprecated
    public static final int FLAG_SUPPORTS_SCREEN_DENSITIES = 1<<13;

    /**
     * Value for {@link #flags}: set to true if this application would like to
     * request the VM to operate under the safe mode. Comes from
     * {@link android.R.styleable#AndroidManifestApplication_vmSafeMode
     * android:vmSafeMode} of the &lt;application&gt; tag.
     */
    public static final int FLAG_VM_SAFE_MODE = 1<<14;

    /**
     * Value for {@link #flags}: set to <code>false</code> if the application does not wish
     * to permit any OS-driven backups of its data; <code>true</code> otherwise.
     *
     * <p>Comes from the
     * {@link android.R.styleable#AndroidManifestApplication_allowBackup android:allowBackup}
     * attribute of the &lt;application&gt; tag.
     */
    public static final int FLAG_ALLOW_BACKUP = 1<<15;

    /**
     * Value for {@link #flags}: set to <code>false</code> if the application must be kept
     * in memory following a full-system restore operation; <code>true</code> otherwise.
     * Ordinarily, during a full system restore operation each application is shut down
     * following execution of its agent's onRestore() method.  Setting this attribute to
     * <code>false</code> prevents this.  Most applications will not need to set this attribute.
     *
     * <p>If
     * {@link android.R.styleable#AndroidManifestApplication_allowBackup android:allowBackup}
     * is set to <code>false</code> or no
     * {@link android.R.styleable#AndroidManifestApplication_backupAgent android:backupAgent}
     * is specified, this flag will be ignored.
     *
     * <p>Comes from the
     * {@link android.R.styleable#AndroidManifestApplication_killAfterRestore android:killAfterRestore}
     * attribute of the &lt;application&gt; tag.
     */
    public static final int FLAG_KILL_AFTER_RESTORE = 1<<16;

    /**
     * Value for {@link #flags}: Set to <code>true</code> if the application's backup
     * agent claims to be able to handle restore data even "from the future,"
     * i.e. from versions of the application with a versionCode greater than
     * the one currently installed on the device.  <i>Use with caution!</i>  By default
     * this attribute is <code>false</code> and the Backup Manager will ensure that data
     * from "future" versions of the application are never supplied during a restore operation.
     *
     * <p>If
     * {@link android.R.styleable#AndroidManifestApplication_allowBackup android:allowBackup}
     * is set to <code>false</code> or no
     * {@link android.R.styleable#AndroidManifestApplication_backupAgent android:backupAgent}
     * is specified, this flag will be ignored.
     *
     * <p>Comes from the
     * {@link android.R.styleable#AndroidManifestApplication_restoreAnyVersion android:restoreAnyVersion}
     * attribute of the &lt;application&gt; tag.
     */
    public static final int FLAG_RESTORE_ANY_VERSION = 1<<17;

    /**
     * Value for {@link #flags}: Set to true if the application is
     * currently installed on external/removable/unprotected storage.  Such
     * applications may not be available if their storage is not currently
     * mounted.  When the storage it is on is not available, it will look like
     * the application has been uninstalled (its .apk is no longer available)
     * but its persistent data is not removed.
     */
    public static final int FLAG_EXTERNAL_STORAGE = 1<<18;

    /**
     * Value for {@link #flags}: true when the application's window can be
     * increased in size for extra large screens.  Corresponds to
     * {@link android.R.styleable#AndroidManifestSupportsScreens_xlargeScreens
     * android:xlargeScreens}.
     */
    public static final int FLAG_SUPPORTS_XLARGE_SCREENS = 1<<19;

    /**
     * Value for {@link #flags}: true when the application has requested a
     * large heap for its processes.  Corresponds to
     * {@link android.R.styleable#AndroidManifestApplication_largeHeap
     * android:largeHeap}.
     */
    public static final int FLAG_LARGE_HEAP = 1<<20;

    /**
     * Value for {@link #flags}: true if this application's package is in
     * the stopped state.
     *
     * <p>Stopped is the initial state after an app is installed, before it is launched
     * or otherwise directly interacted with by the user. The system tries not to
     * start it unless initiated by a user interaction (typically launching its icon
     * from the launcher, could also include user actions like adding it as an app widget,
     * selecting it as a live wallpaper, selecting it as a keyboard, etc). Stopped
     * applications will not receive implicit broadcasts unless the sender specifies
     * {@link android.content.Intent#FLAG_INCLUDE_STOPPED_PACKAGES}.
     *
     * <p>Applications should avoid launching activities, binding to or starting services, or
     * otherwise causing a stopped application to run unless initiated by the user.
     *
     * <p>An app can also return to the stopped state by a "force stop".
     */
    public static final int FLAG_STOPPED = 1<<21;

    /**
     * Value for {@link #flags}: true  when the application is willing to support
     * RTL (right to left). All activities will inherit this value.
     *
     * Set from the {@link android.R.attr#supportsRtl} attribute in the
     * activity's manifest.
     *
     * Default value is false (no support for RTL).
     */
    public static final int FLAG_SUPPORTS_RTL = 1<<22;

    /**
     * Value for {@link #flags}: true if the application is currently
     * installed for the calling user.
     */
    public static final int FLAG_INSTALLED = 1<<23;

    /**
     * Value for {@link #flags}: true if the application only has its
     * data installed; the application package itself does not currently
     * exist on the device.
     */
    public static final int FLAG_IS_DATA_ONLY = 1<<24;

    /**
     * Value for {@link #flags}: true if the application was declared to be a
     * game, or false if it is a non-game application.
     *
     * @deprecated use {@link #CATEGORY_GAME} instead.
     */
    @Deprecated
    public static final int FLAG_IS_GAME = 1<<25;

    /**
     * Value for {@link #flags}: {@code true} if the application asks that only
     * full-data streaming backups of its data be performed even though it defines
     * a {@link android.app.backup.BackupAgent BackupAgent}, which normally
     * indicates that the app will manage its backed-up data via incremental
     * key/value updates.
     */
    public static final int FLAG_FULL_BACKUP_ONLY = 1<<26;

    /**
     * Value for {@link #flags}: {@code true} if the application may use cleartext network traffic
     * (e.g., HTTP rather than HTTPS; WebSockets rather than WebSockets Secure; XMPP, IMAP, SMTP
     * without STARTTLS or TLS). If {@code false}, the app declares that it does not intend to use
     * cleartext network traffic, in which case platform components (e.g., HTTP stacks,
     * {@code DownloadManager}, {@code MediaPlayer}) will refuse app's requests to use cleartext
     * traffic. Third-party libraries are encouraged to honor this flag as well.
     *
     * <p>NOTE: {@code WebView} honors this flag for applications targeting API level 26 and up.
     *
     * <p>This flag is ignored on Android N and above if an Android Network Security Config is
     * present.
     *
     * <p>This flag comes from
     * {@link android.R.styleable#AndroidManifestApplication_usesCleartextTraffic
     * android:usesCleartextTraffic} of the &lt;application&gt; tag.
     */
    public static final int FLAG_USES_CLEARTEXT_TRAFFIC = 1<<27;

    /**
     * When set installer extracts native libs from .apk files.
     */
    public static final int FLAG_EXTRACT_NATIVE_LIBS = 1<<28;

    /**
     * Value for {@link #flags}: {@code true} when the application's rendering
     * should be hardware accelerated.
     */
    public static final int FLAG_HARDWARE_ACCELERATED = 1<<29;

    /**
     * Value for {@link #flags}: true if this application's package is in
     * the suspended state.
     */
    public static final int FLAG_SUSPENDED = 1<<30;

    /**
     * Value for {@link #flags}: true if code from this application will need to be
     * loaded into other applications' processes. On devices that support multiple
     * instruction sets, this implies the code might be loaded into a process that's
     * using any of the devices supported instruction sets.
     *
     * <p> The system might treat such applications specially, for eg., by
     * extracting the application's native libraries for all supported instruction
     * sets or by compiling the application's dex code for all supported instruction
     * sets.
     */
    public static final int FLAG_MULTIARCH  = 1 << 31;

    /**
     * Flags associated with the application.  Any combination of
     * {@link #FLAG_SYSTEM}, {@link #FLAG_DEBUGGABLE}, {@link #FLAG_HAS_CODE},
     * {@link #FLAG_PERSISTENT}, {@link #FLAG_FACTORY_TEST}, and
     * {@link #FLAG_ALLOW_TASK_REPARENTING}
     * {@link #FLAG_ALLOW_CLEAR_USER_DATA}, {@link #FLAG_UPDATED_SYSTEM_APP},
     * {@link #FLAG_TEST_ONLY}, {@link #FLAG_SUPPORTS_SMALL_SCREENS},
     * {@link #FLAG_SUPPORTS_NORMAL_SCREENS},
     * {@link #FLAG_SUPPORTS_LARGE_SCREENS}, {@link #FLAG_SUPPORTS_XLARGE_SCREENS},
     * {@link #FLAG_RESIZEABLE_FOR_SCREENS},
     * {@link #FLAG_SUPPORTS_SCREEN_DENSITIES}, {@link #FLAG_VM_SAFE_MODE},
     * {@link #FLAG_ALLOW_BACKUP}, {@link #FLAG_KILL_AFTER_RESTORE},
     * {@link #FLAG_RESTORE_ANY_VERSION}, {@link #FLAG_EXTERNAL_STORAGE},
     * {@link #FLAG_LARGE_HEAP}, {@link #FLAG_STOPPED},
     * {@link #FLAG_SUPPORTS_RTL}, {@link #FLAG_INSTALLED},
     * {@link #FLAG_IS_DATA_ONLY}, {@link #FLAG_IS_GAME},
     * {@link #FLAG_FULL_BACKUP_ONLY}, {@link #FLAG_USES_CLEARTEXT_TRAFFIC},
     * {@link #FLAG_MULTIARCH}.
     */
    public int flags = 0;

    /**
     * Value for {@link #privateFlags}: true if the application is hidden via restrictions and for
     * most purposes is considered as not installed.
     * {@hide}
     */
    public static final int PRIVATE_FLAG_HIDDEN = 1<<0;

    /**
     * Value for {@link #privateFlags}: set to <code>true</code> if the application
     * has reported that it is heavy-weight, and thus can not participate in
     * the normal application lifecycle.
     *
     * <p>Comes from the
     * android.R.styleable#AndroidManifestApplication_cantSaveState
     * attribute of the &lt;application&gt; tag.
     *
     * {@hide}
     */
    public static final int PRIVATE_FLAG_CANT_SAVE_STATE = 1<<1;

    /**
     * Value for {@link #privateFlags}: set to {@code true} if the application
     * is permitted to hold privileged permissions.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    @TestApi
    public static final int PRIVATE_FLAG_PRIVILEGED = 1<<3;

    /**
     * Value for {@link #privateFlags}: {@code true} if the application has any IntentFiler
     * with some data URI using HTTP or HTTPS with an associated VIEW action.
     *
     * {@hide}
     */
    public static final int PRIVATE_FLAG_HAS_DOMAIN_URLS = 1<<4;

    /**
     * When set, the default data storage directory for this app is pointed at
     * the device-protected location.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE = 1 << 5;

    /**
     * When set, assume that all components under the given app are direct boot
     * aware, unless otherwise specified.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_DIRECT_BOOT_AWARE = 1 << 6;

    /**
     * Value for {@link #privateFlags}: {@code true} if the application is installed
     * as instant app.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_INSTANT = 1 << 7;

    /**
     * When set, at least one component inside this application is direct boot
     * aware.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE = 1 << 8;


    /**
     * When set, signals that the application is required for the system user and should not be
     * uninstalled.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER = 1 << 9;

    /**
     * When set, the application explicitly requested that its activities be resizeable by default.
     * @see android.R.styleable#AndroidManifestActivity_resizeableActivity
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE = 1 << 10;

    /**
     * When set, the application explicitly requested that its activities *not* be resizeable by
     * default.
     * @see android.R.styleable#AndroidManifestActivity_resizeableActivity
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE = 1 << 11;

    /**
     * The application isn't requesting explicitly requesting for its activities to be resizeable or
     * non-resizeable by default. So, we are making it activities resizeable by default based on the
     * target SDK version of the app.
     * @see android.R.styleable#AndroidManifestActivity_resizeableActivity
     *
     * NOTE: This only affects apps with target SDK >= N where the resizeableActivity attribute was
     * introduced. It shouldn't be confused with {@link ActivityInfo#RESIZE_MODE_FORCE_RESIZEABLE}
     * where certain pre-N apps are forced to the resizeable.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION =
            1 << 12;

    /**
     * Value for {@link #privateFlags}: {@code true} means the OS should go ahead and
     * run full-data backup operations for the app even when it is in a
     * foreground-equivalent run state.  Defaults to {@code false} if unspecified.
     * @hide
     */
    public static final int PRIVATE_FLAG_BACKUP_IN_FOREGROUND = 1 << 13;

    /**
     * Value for {@link #privateFlags}: {@code true} means this application
     * contains a static shared library. Defaults to {@code false} if unspecified.
     * @hide
     */
    public static final int PRIVATE_FLAG_STATIC_SHARED_LIBRARY = 1 << 14;

    /**
     * Value for {@link #privateFlags}: When set, the application will only have its splits loaded
     * if they are required to load a component. Splits can be loaded on demand using the
     * {@link Context#createContextForSplit(String)} API.
     * @hide
     */
    public static final int PRIVATE_FLAG_ISOLATED_SPLIT_LOADING = 1 << 15;

    /**
     * Value for {@link #privateFlags}: When set, the application was installed as
     * a virtual preload.
     * @hide
     */
    public static final int PRIVATE_FLAG_VIRTUAL_PRELOAD = 1 << 16;

    /**
     * Value for {@link #privateFlags}: whether this app is pre-installed on the
     * OEM partition of the system image.
     * @hide
     */
    public static final int PRIVATE_FLAG_OEM = 1 << 17;

    /**
     * Value for {@link #privateFlags}: whether this app is pre-installed on the
     * vendor partition of the system image.
     * @hide
     */
    public static final int PRIVATE_FLAG_VENDOR = 1 << 18;

    /**
     * Value for {@link #privateFlags}: whether this app is pre-installed on the
     * product partition of the system image.
     * @hide
     */
    public static final int PRIVATE_FLAG_PRODUCT = 1 << 19;

    /**
     * Value for {@link #privateFlags}: whether this app is signed with the
     * platform key.
     * @hide
     */
    public static final int PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY = 1 << 20;

    /**
     * Value for {@link #privateFlags}: whether this app is pre-installed on the
     * system_ext partition of the system image.
     * @hide
     */
    public static final int PRIVATE_FLAG_SYSTEM_EXT = 1 << 21;

    /**
     * Indicates whether this package requires access to non-SDK APIs.
     * Only system apps and tests are allowed to use this property.
     * @hide
     */
    public static final int PRIVATE_FLAG_USES_NON_SDK_API = 1 << 22;

    /**
     * Indicates whether this application can be profiled by the shell user,
     * even when running on a device that is running in user mode.
     * @hide
     */
    public static final int PRIVATE_FLAG_PROFILEABLE_BY_SHELL = 1 << 23;

    /**
     * Indicates whether this application has declared its user data as fragile,
     * causing the system to prompt the user on whether to keep the user data
     * on uninstall.
     * @hide
     */
    public static final int PRIVATE_FLAG_HAS_FRAGILE_USER_DATA = 1 << 24;

    /**
     * Indicates whether this application wants to use the embedded dex in the APK, rather than
     * extracted or locally compiled variants. This keeps the dex code protected by the APK
     * signature. Such apps will always run in JIT mode (same when they are first installed), and
     * the system will never generate ahead-of-time compiled code for them. Depending on the app's
     * workload, there may be some run time performance change, noteably the cold start time.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_USE_EMBEDDED_DEX = 1 << 25;

    /**
     * Value for {@link #privateFlags}: indicates whether this application's data will be cleared
     * on a failed restore.
     *
     * <p>Comes from the
     * android.R.styleable#AndroidManifestApplication_allowClearUserDataOnFailedRestore attribute
     * of the &lt;application&gt; tag.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE = 1 << 26;

    /**
     * Value for {@link #privateFlags}: true if the application allows its audio playback
     * to be captured by other apps.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE  = 1 << 27;

    /**
     * Indicates whether this package is in fact a runtime resource overlay.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_IS_RESOURCE_OVERLAY = 1 << 28;

    /**
     * Value for {@link #privateFlags}: If {@code true} this app requests
     * full external storage access. The request may not be honored due to
     * policy or other reasons.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE = 1 << 29;

    /**
     * Value for {@link #privateFlags}: whether this app is pre-installed on the
     * ODM partition of the system image.
     * @hide
     */
    public static final int PRIVATE_FLAG_ODM = 1 << 30;

    /**
     * Value for {@link #privateFlags}: If {@code true} this app allows heap tagging.
     * {@link com.android.server.am.ProcessList#NATIVE_HEAP_POINTER_TAGGING}
     * @hide
     */
    public static final int PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING = 1 << 31;

    /** @hide */
    @IntDef(flag = true, prefix = { "PRIVATE_FLAG_" }, value = {
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE,
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION,
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE,
            PRIVATE_FLAG_BACKUP_IN_FOREGROUND,
            PRIVATE_FLAG_CANT_SAVE_STATE,
            PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE,
            PRIVATE_FLAG_DIRECT_BOOT_AWARE,
            PRIVATE_FLAG_HAS_DOMAIN_URLS,
            PRIVATE_FLAG_HIDDEN,
            PRIVATE_FLAG_INSTANT,
            PRIVATE_FLAG_IS_RESOURCE_OVERLAY,
            PRIVATE_FLAG_ISOLATED_SPLIT_LOADING,
            PRIVATE_FLAG_OEM,
            PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE,
            PRIVATE_FLAG_USE_EMBEDDED_DEX,
            PRIVATE_FLAG_PRIVILEGED,
            PRIVATE_FLAG_PRODUCT,
            PRIVATE_FLAG_SYSTEM_EXT,
            PRIVATE_FLAG_PROFILEABLE_BY_SHELL,
            PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER,
            PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY,
            PRIVATE_FLAG_STATIC_SHARED_LIBRARY,
            PRIVATE_FLAG_VENDOR,
            PRIVATE_FLAG_VIRTUAL_PRELOAD,
            PRIVATE_FLAG_HAS_FRAGILE_USER_DATA,
            PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE,
            PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE,
            PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE,
            PRIVATE_FLAG_ODM,
            PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationInfoPrivateFlags {}

    /**
     * Value for {@link #privateFlagsExt}: whether this application can be profiled, either by the
     * shell user or the system.
     * @hide
     */
    public static final int PRIVATE_FLAG_EXT_PROFILEABLE = 1 << 0;

    /**
     * Value for {@link #privateFlagsExt}: whether this application has requested
     * exemption from the foreground service restriction introduced in S
     * (https://developer.android.com/about/versions/12/foreground-services).
     * @hide
     */
    public static final int PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION = 1 << 1;

    /**
     * Value for {@link #privateFlagsExt}: whether attributions provided by the application are
     * meant to be user-visible.
     * @hide
     */
    public static final int PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE = 1 << 2;

    /**
     * If false, {@link android.view.KeyEvent#KEYCODE_BACK} related events will be forwarded to
     * the Activities, Dialogs and Views and {@link android.app.Activity#onBackPressed()},
     * {@link android.app.Dialog#onBackPressed} will be called. Otherwise, those events will be
     * replaced by a call to {@link OnBackInvokedCallback#onBackInvoked()} on the focused window.
     *
     * @hide
     * @see android.R.styleable.AndroidManifestApplication_enableOnBackInvokedCallback
     */
    public static final int PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 << 3;

    /**
     * Whether or not this package is allowed to access hidden APIs. Replacement for legacy
     * implementation of {@link #isPackageWhitelistedForHiddenApis()}.
     *
     * This is an internal flag and should never be used outside of this class. The real API for
     * the hidden API enforcement policy is {@link #getHiddenApiEnforcementPolicy()}.
     *
     * @hide
     */
    public static final int PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS = 1 << 4;

    /**
     * Whether AbiOverride was used when installing this application.
     * @hide
     */
    public static final int PRIVATE_FLAG_EXT_CPU_OVERRIDE = 1 << 5;

    /** @hide */
    @IntDef(flag = true, prefix = { "PRIVATE_FLAG_EXT_" }, value = {
            PRIVATE_FLAG_EXT_PROFILEABLE,
            PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION,
            PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE,
            PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK,
            PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS,
            PRIVATE_FLAG_EXT_CPU_OVERRIDE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationInfoPrivateFlagsExt {}
    /**
     * Constant corresponding to <code>allowed</code> in the
     * {@link android.R.attr#autoRevokePermissions} attribute.
     *
     * @hide
     */
    public static final int AUTO_REVOKE_ALLOWED = 0;

    /**
     * Constant corresponding to <code>discouraged</code> in the
     * {@link android.R.attr#autoRevokePermissions} attribute.
     *
     * @hide
     */
    public static final int AUTO_REVOKE_DISCOURAGED = 1;

    /**
     * Constant corresponding to <code>disallowed</code> in the
     * {@link android.R.attr#autoRevokePermissions} attribute.
     *
     * @hide
     */
    public static final int AUTO_REVOKE_DISALLOWED = 2;

    /**
     * Private/hidden flags. See {@code PRIVATE_FLAG_...} constants.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public @ApplicationInfoPrivateFlags int privateFlags;

    /**
     * More private/hidden flags. See {@code PRIVATE_FLAG_EXT_...} constants.
     * @hide
     */
    public @ApplicationInfoPrivateFlagsExt int privateFlagsExt;

    /**
     * @hide
     */
    public static final String METADATA_PRELOADED_FONTS = "preloaded_fonts";

    /**
     * The required smallest screen width the application can run on.  If 0,
     * nothing has been specified.  Comes from
     * {@link android.R.styleable#AndroidManifestSupportsScreens_requiresSmallestWidthDp
     * android:requiresSmallestWidthDp} attribute of the &lt;supports-screens&gt; tag.
     */
    public int requiresSmallestWidthDp = 0;

    /**
     * The maximum smallest screen width the application is designed for.  If 0,
     * nothing has been specified.  Comes from
     * {@link android.R.styleable#AndroidManifestSupportsScreens_compatibleWidthLimitDp
     * android:compatibleWidthLimitDp} attribute of the &lt;supports-screens&gt; tag.
     */
    public int compatibleWidthLimitDp = 0;

    /**
     * The maximum smallest screen width the application will work on.  If 0,
     * nothing has been specified.  Comes from
     * {@link android.R.styleable#AndroidManifestSupportsScreens_largestWidthLimitDp
     * android:largestWidthLimitDp} attribute of the &lt;supports-screens&gt; tag.
     */
    public int largestWidthLimitDp = 0;

    /**
     * Value indicating the maximum aspect ratio the application supports.
     * <p>
     * 0 means unset.
     * @See {@link android.R.attr#maxAspectRatio}.
     * @hide
     */
    public float maxAspectRatio;

    /**
     * Value indicating the minimum aspect ratio the application supports.
     * <p>
     * 0 means unset.
     * @see {@link android.R.attr#minAspectRatio}.
     * @hide
     */
    public float minAspectRatio;

    /** @hide */
    public String volumeUuid;

    /**
     * UUID of the storage volume on which this application is being hosted. For
     * apps hosted on the default internal storage at
     * {@link Environment#getDataDirectory()}, the UUID value is
     * {@link StorageManager#UUID_DEFAULT}.
     */
    public UUID storageUuid;

    /** {@hide} */
    @UnsupportedAppUsage
    public String scanSourceDir;
    /** {@hide} */
    @UnsupportedAppUsage
    public String scanPublicSourceDir;

    /**
     * Full path to the base APK for this application.
     */
    public String sourceDir;

    /**
     * Full path to the publicly available parts of {@link #sourceDir},
     * including resources and manifest. This may be different from
     * {@link #sourceDir} if an application is forward locked.
     */
    public String publicSourceDir;

    /**
     * The names of all installed split APKs, ordered lexicographically.
     * May be null if no splits are installed.
     */
    @Nullable
    public String[] splitNames;

    /**
     * Full paths to split APKs, indexed by the same order as {@link #splitNames}.
     * May be null if no splits are installed.
     */
    @Nullable
    public String[] splitSourceDirs;

    /**
     * Full path to the publicly available parts of {@link #splitSourceDirs},
     * including resources and manifest. This may be different from
     * {@link #splitSourceDirs} if an application is forward locked.
     * May be null if no splits are installed.
     *
     * @see #splitSourceDirs
     */
    @Nullable
    public String[] splitPublicSourceDirs;

    /**
     * Maps the dependencies between split APKs. All splits implicitly depend on the base APK.
     *
     * Available since platform version O.
     *
     * Only populated if the application opts in to isolated split loading via the
     * {@link android.R.attr.isolatedSplits} attribute in the &lt;manifest&gt; tag of the app's
     * AndroidManifest.xml.
     *
     * The keys and values are all indices into the {@link #splitNames}, {@link #splitSourceDirs},
     * and {@link #splitPublicSourceDirs} arrays.
     * Each key represents a split and its value is an array of splits. The first element of this
     * array is the parent split, and the rest are configuration splits. These configuration splits
     * have no dependencies themselves.
     * Cycles do not exist because they are illegal and screened for during installation.
     *
     * May be null if no splits are installed, or if no dependencies exist between them.
     *
     * NOTE: Any change to the way split dependencies are stored must update the logic that
     *       creates the class loader context for dexopt (DexoptUtils#getClassLoaderContexts).
     *
     * @hide
     */
    public SparseArray<int[]> splitDependencies;

    /**
     * Full paths to the locations of extra resource packages (runtime overlays)
     * this application uses. This field is only used if there are extra resource
     * packages, otherwise it is null.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public String[] resourceDirs;

    /**
     * Contains the contents of {@link #resourceDirs} and along with paths for overlays that may or
     * may not be APK packages.
     *
     * {@hide}
     */
    public String[] overlayPaths;

    /**
     * String retrieved from the seinfo tag found in selinux policy. This value can be set through
     * the mac_permissions.xml policy construct. This value is used for setting an SELinux security
     * context on the process as well as its data directory.
     *
     * {@hide}
     */
    public String seInfo;

    /**
     * The seinfo tag generated per-user. This value may change based upon the
     * user's configuration. For example, when an instant app is installed for
     * a user. It is an error if this field is ever {@code null} when trying to
     * start a new process.
     * <p>NOTE: We need to separate this out because we modify per-user values
     * multiple times. This needs to be refactored since we're performing more
     * work than necessary and these values should only be set once. When that
     * happens, we can merge the per-user value with the seInfo state above.
     *
     * {@hide}
     */
    public String seInfoUser;

    /**
     * Paths to all shared libraries this application is linked against.  This
     * field is only set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     * PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving
     * the structure.
     */
    public String[] sharedLibraryFiles;

    /**
     * List of all shared libraries this application is linked against.  This
     * field is only set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     * PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving
     * the structure.
     *
     * NOTE: the list also contains the result of {@link #getOptionalSharedLibraryInfos}.
     *
     * {@hide}
     */
    @Nullable
    public List<SharedLibraryInfo> sharedLibraryInfos;

    /**
     * List of all shared libraries this application is optionally linked against.
     * This field is only set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     * PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving
     * the structure.
     *
     * @hide
     */
    @Nullable
    public List<SharedLibraryInfo> optionalSharedLibraryInfos;

    /**
     * Full path to the default directory assigned to the package for its
     * persistent data.
     */
    public String dataDir;

    /**
     * Full path to the device-protected directory assigned to the package for
     * its persistent data.
     *
     * @see Context#createDeviceProtectedStorageContext()
     */
    public String deviceProtectedDataDir;

    /**
     * Full path to the credential-protected directory assigned to the package
     * for its persistent data.
     *
     * @hide
     */
    @SystemApi
    public String credentialProtectedDataDir;

    /**
     * Full path to the directory where native JNI libraries are stored.
     */
    public String nativeLibraryDir;

    /**
     * Full path where unpacked native libraries for {@link #secondaryCpuAbi}
     * are stored, if present.
     *
     * The main reason this exists is for bundled multi-arch apps, where
     * it's not trivial to calculate the location of libs for the secondary abi
     * given the location of the primary.
     *
     * TODO: Change the layout of bundled installs so that we can use
     * nativeLibraryRootDir & nativeLibraryRootRequiresIsa there as well.
     * (e.g {@code [ "/system/app-lib/Foo/arm", "/system/app-lib/Foo/arm64" ]}
     * instead of {@code [ "/system/lib/Foo", "/system/lib64/Foo" ]}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String secondaryNativeLibraryDir;

    /**
     * The root path where unpacked native libraries are stored.
     * <p>
     * When {@link #nativeLibraryRootRequiresIsa} is set, the libraries are
     * placed in ISA-specific subdirectories under this path, otherwise the
     * libraries are placed directly at this path.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String nativeLibraryRootDir;

    /**
     * Flag indicating that ISA must be appended to
     * {@link #nativeLibraryRootDir} to be useful.
     *
     * @hide
     */
    public boolean nativeLibraryRootRequiresIsa;

    /**
     * The primary ABI that this application requires, This is inferred from the ABIs
     * of the native JNI libraries the application bundles. Will be {@code null}
     * if this application does not require any particular ABI.
     *
     * If non-null, the application will always be launched with this ABI.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public String primaryCpuAbi;

    /**
     * The secondary ABI for this application. Might be non-null for multi-arch
     * installs. The application itself never uses this ABI, but other applications that
     * use its code might.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
    public String secondaryCpuAbi;

    /**
     * The kernel user-ID that has been assigned to this application;
     * currently this is not a unique ID (multiple applications can have
     * the same uid).
     */
    public int uid;

    /**
     * The minimum SDK version this application can run on. It will not run
     * on earlier versions.
     */
    public int minSdkVersion;

    /**
     * The minimum SDK version this application targets.  It may run on earlier
     * versions, but it knows how to work with any new behavior added at this
     * version.  Will be {@link android.os.Build.VERSION_CODES#CUR_DEVELOPMENT}
     * if this is a development build and the app is targeting that.  You should
     * compare that this number is >= the SDK version number at which your
     * behavior was introduced.
     */
    public int targetSdkVersion;

    /**
     * The app's declared version code.
     * @hide
     */
    public long longVersionCode;

    /**
     * An integer representation of the app's declared version code. This is being left in place as
     * some apps were using reflection to access it before the move to long in
     * {@link android.os.Build.VERSION_CODES#P}
     * @deprecated Use {@link #longVersionCode} instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public int versionCode;

    /**
     * The timestamp of when this ApplicationInfo was created.
     * @hide
     */
    public long createTimestamp;

    /**
     * The user-visible SDK version (ex. 26) of the framework against which the application claims
     * to have been compiled, or {@code 0} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#CODENAME Build.VERSION.SDK_INT}.
     */
    public int compileSdkVersion;

    /**
     * The development codename (ex. "S", "REL") of the framework against which the application
     * claims to have been compiled, or {@code null} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#CODENAME Build.VERSION.CODENAME}.
     */
    @Nullable
    public String compileSdkVersionCodename;

    /**
     * When false, indicates that all components within this application are
     * considered disabled, regardless of their individually set enabled status.
     */
    public boolean enabled = true;

    /**
     * For convenient access to the current enabled setting of this app.
     * @hide
     */
    @UnsupportedAppUsage
    public int enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    /**
     * For convenient access to package's install location.
     * @hide
     */
    @UnsupportedAppUsage
    public int installLocation = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    /**
     * Resource file providing the application's Network Security Config.
     * @hide
     */
    public int networkSecurityConfigRes;

    /**
     * Version of the sandbox the application wants to run in.
     * @hide
     */
    @SystemApi
    public int targetSandboxVersion;

    /**
     * The factory of this package, as specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifestApplication_appComponentFactory}
     * attribute.
     */
    public String appComponentFactory;

    /**
     * Resource id of {@link com.android.internal.R.styleable.AndroidManifestProvider_icon}
     * @hide
     */
    public int iconRes;

    /**
     * Resource id of {@link com.android.internal.R.styleable.AndroidManifestProvider_roundIcon}
     * @hide
     */
    public int roundIconRes;

    /**
     * The category of this app. Categories are used to cluster multiple apps
     * together into meaningful groups, such as when summarizing battery,
     * network, or disk usage. Apps should only define this value when they fit
     * well into one of the specific categories.
     * <p>
     * Set from the {@link android.R.attr#appCategory} attribute in the
     * manifest. If the manifest doesn't define a category, this value may have
     * been provided by the installer via
     * {@link PackageManager#setApplicationCategoryHint(String, int)}.
     */
    public @Category int category = CATEGORY_UNDEFINED;

    /** {@hide} */
    @IntDef(prefix = { "CATEGORY_" }, value = {
            CATEGORY_UNDEFINED,
            CATEGORY_GAME,
            CATEGORY_AUDIO,
            CATEGORY_VIDEO,
            CATEGORY_IMAGE,
            CATEGORY_SOCIAL,
            CATEGORY_NEWS,
            CATEGORY_MAPS,
            CATEGORY_PRODUCTIVITY,
            CATEGORY_ACCESSIBILITY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Category {
    }

    /**
     * Value when category is undefined.
     *
     * @see #category
     */
    public static final int CATEGORY_UNDEFINED = -1;

    /**
     * Category for apps which are primarily games.
     *
     * @see #category
     */
    public static final int CATEGORY_GAME = 0;

    /**
     * Category for apps which primarily work with audio or music, such as music
     * players.
     *
     * @see #category
     */
    public static final int CATEGORY_AUDIO = 1;

    /**
     * Category for apps which primarily work with video or movies, such as
     * streaming video apps.
     *
     * @see #category
     */
    public static final int CATEGORY_VIDEO = 2;

    /**
     * Category for apps which primarily work with images or photos, such as
     * camera or gallery apps.
     *
     * @see #category
     */
    public static final int CATEGORY_IMAGE = 3;

    /**
     * Category for apps which are primarily social apps, such as messaging,
     * communication, email, or social network apps.
     *
     * @see #category
     */
    public static final int CATEGORY_SOCIAL = 4;

    /**
     * Category for apps which are primarily news apps, such as newspapers,
     * magazines, or sports apps.
     *
     * @see #category
     */
    public static final int CATEGORY_NEWS = 5;

    /**
     * Category for apps which are primarily maps apps, such as navigation apps.
     *
     * @see #category
     */
    public static final int CATEGORY_MAPS = 6;

    /**
     * Category for apps which are primarily productivity apps, such as cloud
     * storage or workplace apps.
     *
     * @see #category
     */
    public static final int CATEGORY_PRODUCTIVITY = 7;

    /**
     * Category for apps which are primarily accessibility apps, such as screen-readers.
     *
     * @see #category
     */
    public static final int CATEGORY_ACCESSIBILITY = 8;

    /**
     * Return a concise, localized title for the given
     * {@link ApplicationInfo#category} value, or {@code null} for unknown
     * values such as {@link #CATEGORY_UNDEFINED}.
     *
     * @see #category
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    public static CharSequence getCategoryTitle(Context context, @Category int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME:
                return context.getText(com.android.internal.R.string.app_category_game);
            case ApplicationInfo.CATEGORY_AUDIO:
                return context.getText(com.android.internal.R.string.app_category_audio);
            case ApplicationInfo.CATEGORY_VIDEO:
                return context.getText(com.android.internal.R.string.app_category_video);
            case ApplicationInfo.CATEGORY_IMAGE:
                return context.getText(com.android.internal.R.string.app_category_image);
            case ApplicationInfo.CATEGORY_SOCIAL:
                return context.getText(com.android.internal.R.string.app_category_social);
            case ApplicationInfo.CATEGORY_NEWS:
                return context.getText(com.android.internal.R.string.app_category_news);
            case ApplicationInfo.CATEGORY_MAPS:
                return context.getText(com.android.internal.R.string.app_category_maps);
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return context.getText(com.android.internal.R.string.app_category_productivity);
            case ApplicationInfo.CATEGORY_ACCESSIBILITY:
                return context.getText(com.android.internal.R.string.app_category_accessibility);
            default:
                return null;
        }
    }

    /** @hide */
    public String classLoaderName;

    /** @hide */
    public String[] splitClassLoaderNames;

    /** @hide */
    public boolean hiddenUntilInstalled;

    /** @hide */
    public String zygotePreloadName;

    /**
     * Default (unspecified) setting of GWP-ASan.
     */
    public static final int GWP_ASAN_DEFAULT = -1;

    /**
     * Never enable GWP-ASan in this application or process.
     */
    public static final int GWP_ASAN_NEVER = 0;

    /**
     * Always enable GWP-ASan in this application or process.
     */
    public static final int GWP_ASAN_ALWAYS = 1;

    /**
     * These constants need to match the values of gwpAsanMode in application manifest.
     * @hide
     */
    @IntDef(prefix = {"GWP_ASAN_"}, value = {
            GWP_ASAN_DEFAULT,
            GWP_ASAN_NEVER,
            GWP_ASAN_ALWAYS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GwpAsanMode {}

    /**
     * Indicates if the application has requested GWP-ASan to be enabled, disabled, or left
     * unspecified. Processes can override this setting.
     */
    private @GwpAsanMode int gwpAsanMode = GWP_ASAN_DEFAULT;

    /**
     * Default (unspecified) setting of Memtag.
     */
    public static final int MEMTAG_DEFAULT = -1;

    /**
     * Do not enable Memtag in this application or process.
     */
    public static final int MEMTAG_OFF = 0;

    /**
     * Enable Memtag in Async mode in this application or process.
     */
    public static final int MEMTAG_ASYNC = 1;

    /**
     * Enable Memtag in Sync mode in this application or process.
     */
    public static final int MEMTAG_SYNC = 2;

    /**
     * These constants need to match the values of memtagMode in application manifest.
     * @hide
     */
    @IntDef(prefix = {"MEMTAG_"}, value = {
            MEMTAG_DEFAULT,
            MEMTAG_OFF,
            MEMTAG_ASYNC,
            MEMTAG_SYNC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MemtagMode {}

    /**
     * Indicates if the application has requested Memtag to be enabled, disabled, or left
     * unspecified. Processes can override this setting.
     */
    private @MemtagMode int memtagMode = MEMTAG_DEFAULT;

    /**
     * Default (unspecified) setting of nativeHeapZeroInitialized.
     */
    public static final int ZEROINIT_DEFAULT = -1;

    /**
     * Disable zero-initialization of the native heap in this application or process.
     */
    public static final int ZEROINIT_DISABLED = 0;

    /**
     * Enable zero-initialization of the native heap in this application or process.
     */
    public static final int ZEROINIT_ENABLED = 1;

    /**
     * @hide
     */
    @IntDef(prefix = {"ZEROINIT_"}, value = {
            ZEROINIT_DEFAULT,
            ZEROINIT_DISABLED,
            ZEROINIT_ENABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface NativeHeapZeroInitialized {}

    /**
     * Enable automatic zero-initialization of native heap memory allocations.
     */
    private @NativeHeapZeroInitialized int nativeHeapZeroInitialized = ZEROINIT_DEFAULT;

    /**
     * If {@code true} this app requests raw external storage access.
     * The request may not be honored due to policy or other reasons.
     */
    @Nullable
    private Boolean requestRawExternalStorageAccess;

    /**
     * If {@code false}, this app does not allow its activities to be replaced by another app.
     * Is set from application manifest application tag's allowCrossUidActivitySwitchFromBelow
     * attribute.
     * @hide
     */
    public boolean allowCrossUidActivitySwitchFromBelow = true;

    /**
     * Represents the default policy. The actual policy used will depend on other properties of
     * the application, e.g. the target SDK version.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int HIDDEN_API_ENFORCEMENT_DEFAULT = -1;
    /**
     * No API enforcement; the app can access the entire internal private API. Only for use by
     * system apps.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int HIDDEN_API_ENFORCEMENT_DISABLED = 0;
    /**
     * No API enforcement, but enable the detection logic and warnings. Observed behaviour is the
     * same as {@link #HIDDEN_API_ENFORCEMENT_DISABLED} but you may see warnings in the log when
     * APIs are accessed.
     * @hide
     * */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int HIDDEN_API_ENFORCEMENT_JUST_WARN = 1;
    /**
     * Dark grey list enforcement. Enforces the dark grey and black lists
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final int HIDDEN_API_ENFORCEMENT_ENABLED = 2;

    private static final int HIDDEN_API_ENFORCEMENT_MIN = HIDDEN_API_ENFORCEMENT_DEFAULT;
    private static final int HIDDEN_API_ENFORCEMENT_MAX = HIDDEN_API_ENFORCEMENT_ENABLED;

    /**
     * Values in this IntDef MUST be kept in sync with enum hiddenapi::EnforcementPolicy in
     * art/runtime/hidden_api.h
     * @hide
     */
    @IntDef(prefix = { "HIDDEN_API_ENFORCEMENT_" }, value = {
            HIDDEN_API_ENFORCEMENT_DEFAULT,
            HIDDEN_API_ENFORCEMENT_DISABLED,
            HIDDEN_API_ENFORCEMENT_JUST_WARN,
            HIDDEN_API_ENFORCEMENT_ENABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HiddenApiEnforcementPolicy {}

    /** @hide */
    public static boolean isValidHiddenApiEnforcementPolicy(int policy) {
        return policy >= HIDDEN_API_ENFORCEMENT_MIN && policy <= HIDDEN_API_ENFORCEMENT_MAX;
    }

    private int mHiddenApiPolicy = HIDDEN_API_ENFORCEMENT_DEFAULT;

    /**
     * A map from a process name to an (custom) application class name in this package, derived
     * from the <processes> tag in the app's manifest. This map may not contain all the process
     * names. Processses not in this map will use the default app class name,
     * which is {@link #className}, or the default class {@link android.app.Application}.
     */
    @Nullable
    private ArrayMap<String, String> mAppClassNamesByProcess;

    /**
     * Resource file providing the application's locales configuration.
     */
    private int localeConfigRes;

    /**
     * Optional set of a certificates identifying apps that are allowed to embed activities of this
     * application. From the "knownActivityEmbeddingCerts" attribute.
     */
    @Nullable
    private Set<String> mKnownActivityEmbeddingCerts;

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            if (className != null) {
                pw.println(prefix + "className=" + className);
            }
            for (int i = 0; i < ArrayUtils.size(mAppClassNamesByProcess); i++) {
                pw.println(prefix + "  process=" + mAppClassNamesByProcess.keyAt(i)
                        + " className=" + mAppClassNamesByProcess.valueAt(i));
            }
        }
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        pw.println(prefix + "processName=" + processName);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "taskAffinity=" + taskAffinity);
        }
        pw.println(prefix + "uid=" + uid + " flags=0x" + Integer.toHexString(flags)
                + " privateFlags=0x" + Integer.toHexString(privateFlags)
                + " theme=0x" + Integer.toHexString(theme));
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "requiresSmallestWidthDp=" + requiresSmallestWidthDp
                    + " compatibleWidthLimitDp=" + compatibleWidthLimitDp
                    + " largestWidthLimitDp=" + largestWidthLimitDp);
        }
        pw.println(prefix + "sourceDir=" + sourceDir);
        if (!Objects.equals(sourceDir, publicSourceDir)) {
            pw.println(prefix + "publicSourceDir=" + publicSourceDir);
        }
        if (!ArrayUtils.isEmpty(splitSourceDirs)) {
            pw.println(prefix + "splitSourceDirs=" + Arrays.toString(splitSourceDirs));
        }
        if (!ArrayUtils.isEmpty(splitPublicSourceDirs)
                && !Arrays.equals(splitSourceDirs, splitPublicSourceDirs)) {
            pw.println(prefix + "splitPublicSourceDirs=" + Arrays.toString(splitPublicSourceDirs));
        }
        if (resourceDirs != null) {
            pw.println(prefix + "resourceDirs=" + Arrays.toString(resourceDirs));
        }
        if (overlayPaths != null) {
            pw.println(prefix + "overlayPaths=" + Arrays.toString(overlayPaths));
        }
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0 && seInfo != null) {
            pw.println(prefix + "seinfo=" + seInfo);
            pw.println(prefix + "seinfoUser=" + seInfoUser);
        }
        pw.println(prefix + "dataDir=" + dataDir);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            pw.println(prefix + "deviceProtectedDataDir=" + deviceProtectedDataDir);
            pw.println(prefix + "credentialProtectedDataDir=" + credentialProtectedDataDir);
            if (sharedLibraryFiles != null) {
                pw.println(prefix + "sharedLibraryFiles=" + Arrays.toString(sharedLibraryFiles));
            }
        }
        if (classLoaderName != null) {
            pw.println(prefix + "classLoaderName=" + classLoaderName);
        }
        if (!ArrayUtils.isEmpty(splitClassLoaderNames)) {
            pw.println(prefix + "splitClassLoaderNames=" + Arrays.toString(splitClassLoaderNames));
        }

        pw.println(prefix + "enabled=" + enabled
                + " minSdkVersion=" + minSdkVersion
                + " targetSdkVersion=" + targetSdkVersion
                + " versionCode=" + longVersionCode
                + " targetSandboxVersion=" + targetSandboxVersion);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            if (manageSpaceActivityName != null) {
                pw.println(prefix + "manageSpaceActivityName=" + manageSpaceActivityName);
            }
            if (descriptionRes != 0) {
                pw.println(prefix + "description=0x" + Integer.toHexString(descriptionRes));
            }
            if (uiOptions != 0) {
                pw.println(prefix + "uiOptions=0x" + Integer.toHexString(uiOptions));
            }
            pw.println(prefix + "supportsRtl=" + (hasRtlSupport() ? "true" : "false"));
            if (fullBackupContent > 0) {
                pw.println(prefix + "fullBackupContent=@xml/" + fullBackupContent);
            } else {
                pw.println(prefix + "fullBackupContent="
                        + (fullBackupContent < 0 ? "false" : "true"));
            }
            if (dataExtractionRulesRes != 0) {
                pw.println(prefix + "dataExtractionRules=@xml/" + dataExtractionRulesRes);
            }
            pw.println(prefix + "crossProfile=" + (crossProfile ? "true" : "false"));
            if (networkSecurityConfigRes != 0) {
                pw.println(prefix + "networkSecurityConfigRes=0x"
                        + Integer.toHexString(networkSecurityConfigRes));
            }
            if (category != CATEGORY_UNDEFINED) {
                pw.println(prefix + "category=" + category);
            }
            pw.println(prefix + "HiddenApiEnforcementPolicy=" + getHiddenApiEnforcementPolicy());
            pw.println(prefix + "usesNonSdkApi=" + usesNonSdkApi());
            pw.println(prefix + "allowsPlaybackCapture="
                        + (isAudioPlaybackCaptureAllowed() ? "true" : "false"));
            if (gwpAsanMode != GWP_ASAN_DEFAULT) {
                pw.println(prefix + "gwpAsanMode=" + gwpAsanMode);
            }
            if (memtagMode != MEMTAG_DEFAULT) {
                pw.println(prefix + "memtagMode=" + memtagMode);
            }
            if (nativeHeapZeroInitialized != ZEROINIT_DEFAULT) {
                pw.println(prefix + "nativeHeapZeroInitialized=" + nativeHeapZeroInitialized);
            }
            if (requestRawExternalStorageAccess != null) {
                pw.println(prefix + "requestRawExternalStorageAccess="
                        + requestRawExternalStorageAccess);
            }
            if (localeConfigRes != 0) {
                pw.println(prefix + "localeConfigRes=0x"
                        + Integer.toHexString(localeConfigRes));
            }
            pw.println(prefix + "enableOnBackInvokedCallback=" + isOnBackInvokedCallbackEnabled());
            pw.println(prefix + "allowCrossUidActivitySwitchFromBelow="
                    + allowCrossUidActivitySwitchFromBelow);

        }
        pw.println(prefix + "createTimestamp=" + createTimestamp);
        if (mKnownActivityEmbeddingCerts != null) {
            pw.println(prefix + "knownActivityEmbeddingCerts=" + mKnownActivityEmbeddingCerts);
        }
        super.dumpBack(pw, prefix);
    }

    /** {@hide} */
    public void dumpDebug(ProtoOutputStream proto, long fieldId, int dumpFlags) {
        long token = proto.start(fieldId);
        super.dumpDebug(proto, ApplicationInfoProto.PACKAGE, dumpFlags);
        proto.write(ApplicationInfoProto.PERMISSION, permission);
        proto.write(ApplicationInfoProto.PROCESS_NAME, processName);
        proto.write(ApplicationInfoProto.UID, uid);
        proto.write(ApplicationInfoProto.FLAGS, flags);
        proto.write(ApplicationInfoProto.PRIVATE_FLAGS, privateFlags);
        proto.write(ApplicationInfoProto.THEME, theme);
        proto.write(ApplicationInfoProto.SOURCE_DIR, sourceDir);
        if (!Objects.equals(sourceDir, publicSourceDir)) {
            proto.write(ApplicationInfoProto.PUBLIC_SOURCE_DIR, publicSourceDir);
        }
        if (!ArrayUtils.isEmpty(splitSourceDirs)) {
            for (String dir : splitSourceDirs) {
                proto.write(ApplicationInfoProto.SPLIT_SOURCE_DIRS, dir);
            }
        }
        if (!ArrayUtils.isEmpty(splitPublicSourceDirs)
                && !Arrays.equals(splitSourceDirs, splitPublicSourceDirs)) {
            for (String dir : splitPublicSourceDirs) {
                proto.write(ApplicationInfoProto.SPLIT_PUBLIC_SOURCE_DIRS, dir);
            }
        }
        if (resourceDirs != null) {
            for (String dir : resourceDirs) {
                proto.write(ApplicationInfoProto.RESOURCE_DIRS, dir);
            }
        }
        if (overlayPaths != null) {
            for (String dir : overlayPaths) {
                proto.write(ApplicationInfoProto.OVERLAY_PATHS, dir);
            }
        }
        proto.write(ApplicationInfoProto.DATA_DIR, dataDir);
        proto.write(ApplicationInfoProto.CLASS_LOADER_NAME, classLoaderName);
        if (!ArrayUtils.isEmpty(splitClassLoaderNames)) {
            for (String name : splitClassLoaderNames) {
                proto.write(ApplicationInfoProto.SPLIT_CLASS_LOADER_NAMES, name);
            }
        }

        long versionToken = proto.start(ApplicationInfoProto.VERSION);
        proto.write(ApplicationInfoProto.Version.ENABLED, enabled);
        proto.write(ApplicationInfoProto.Version.MIN_SDK_VERSION, minSdkVersion);
        proto.write(ApplicationInfoProto.Version.TARGET_SDK_VERSION, targetSdkVersion);
        proto.write(ApplicationInfoProto.Version.VERSION_CODE, longVersionCode);
        proto.write(ApplicationInfoProto.Version.TARGET_SANDBOX_VERSION, targetSandboxVersion);
        proto.end(versionToken);

        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0) {
            long detailToken = proto.start(ApplicationInfoProto.DETAIL);
            if (className != null) {
                proto.write(ApplicationInfoProto.Detail.CLASS_NAME, className);
            }
            proto.write(ApplicationInfoProto.Detail.TASK_AFFINITY, taskAffinity);
            proto.write(ApplicationInfoProto.Detail.REQUIRES_SMALLEST_WIDTH_DP,
                    requiresSmallestWidthDp);
            proto.write(ApplicationInfoProto.Detail.COMPATIBLE_WIDTH_LIMIT_DP,
                    compatibleWidthLimitDp);
            proto.write(ApplicationInfoProto.Detail.LARGEST_WIDTH_LIMIT_DP,
                    largestWidthLimitDp);
            if (seInfo != null) {
                proto.write(ApplicationInfoProto.Detail.SEINFO, seInfo);
                proto.write(ApplicationInfoProto.Detail.SEINFO_USER, seInfoUser);
            }
            proto.write(ApplicationInfoProto.Detail.DEVICE_PROTECTED_DATA_DIR,
                    deviceProtectedDataDir);
            proto.write(ApplicationInfoProto.Detail.CREDENTIAL_PROTECTED_DATA_DIR,
                    credentialProtectedDataDir);
            if (sharedLibraryFiles != null) {
                for (String f : sharedLibraryFiles) {
                    proto.write(ApplicationInfoProto.Detail.SHARED_LIBRARY_FILES, f);
                }
            }
            if (manageSpaceActivityName != null) {
                proto.write(ApplicationInfoProto.Detail.MANAGE_SPACE_ACTIVITY_NAME,
                        manageSpaceActivityName);
            }
            if (descriptionRes != 0) {
                proto.write(ApplicationInfoProto.Detail.DESCRIPTION_RES, descriptionRes);
            }
            if (uiOptions != 0) {
                proto.write(ApplicationInfoProto.Detail.UI_OPTIONS, uiOptions);
            }
            proto.write(ApplicationInfoProto.Detail.SUPPORTS_RTL, hasRtlSupport());
            if (fullBackupContent > 0) {
                proto.write(ApplicationInfoProto.Detail.CONTENT, "@xml/" + fullBackupContent);
            } else {
                proto.write(ApplicationInfoProto.Detail.IS_FULL_BACKUP, fullBackupContent == 0);
            }
            if (networkSecurityConfigRes != 0) {
                proto.write(ApplicationInfoProto.Detail.NETWORK_SECURITY_CONFIG_RES,
                        networkSecurityConfigRes);
            }
            if (category != CATEGORY_UNDEFINED) {
                proto.write(ApplicationInfoProto.Detail.CATEGORY, category);
            }
            if (gwpAsanMode != GWP_ASAN_DEFAULT) {
                proto.write(ApplicationInfoProto.Detail.ENABLE_GWP_ASAN, gwpAsanMode);
            }
            if (memtagMode != MEMTAG_DEFAULT) {
                proto.write(ApplicationInfoProto.Detail.ENABLE_MEMTAG, memtagMode);
            }
            if (nativeHeapZeroInitialized != ZEROINIT_DEFAULT) {
                proto.write(ApplicationInfoProto.Detail.NATIVE_HEAP_ZERO_INIT,
                        nativeHeapZeroInitialized);
            }
            proto.write(ApplicationInfoProto.Detail.ALLOW_CROSS_UID_ACTIVITY_SWITCH_FROM_BELOW,
                    allowCrossUidActivitySwitchFromBelow);
            proto.end(detailToken);
        }
        if (!ArrayUtils.isEmpty(mKnownActivityEmbeddingCerts)) {
            for (String knownCert : mKnownActivityEmbeddingCerts) {
                proto.write(ApplicationInfoProto.KNOWN_ACTIVITY_EMBEDDING_CERTS, knownCert);
            }
        }
        proto.end(token);
    }

    /**
     * @return true if "supportsRtl" has been set to true in the AndroidManifest
     * @hide
     */
    @UnsupportedAppUsage
    public boolean hasRtlSupport() {
        return (flags & FLAG_SUPPORTS_RTL) == FLAG_SUPPORTS_RTL;
    }

    /** {@hide} */
    public boolean hasCode() {
        return (flags & FLAG_HAS_CODE) != 0;
    }

    public static class DisplayNameComparator
            implements Comparator<ApplicationInfo> {
        public DisplayNameComparator(PackageManager pm) {
            mPM = pm;
        }

        public final int compare(ApplicationInfo aa, ApplicationInfo ab) {
            CharSequence  sa = mPM.getApplicationLabel(aa);
            if (sa == null) {
                sa = aa.packageName;
            }
            CharSequence  sb = mPM.getApplicationLabel(ab);
            if (sb == null) {
                sb = ab.packageName;
            }

            return sCollator.compare(sa.toString(), sb.toString());
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        private final Collator   sCollator = Collator.getInstance();
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        private final PackageManager mPM;
    }

    public ApplicationInfo() {
        createTimestamp = SystemClock.uptimeMillis();
    }

    public ApplicationInfo(ApplicationInfo orig) {
        super(orig);
        taskAffinity = orig.taskAffinity;
        permission = orig.permission;
        mKnownActivityEmbeddingCerts = orig.mKnownActivityEmbeddingCerts;
        processName = orig.processName;
        className = orig.className;
        theme = orig.theme;
        flags = orig.flags;
        privateFlags = orig.privateFlags;
        privateFlagsExt = orig.privateFlagsExt;
        requiresSmallestWidthDp = orig.requiresSmallestWidthDp;
        compatibleWidthLimitDp = orig.compatibleWidthLimitDp;
        largestWidthLimitDp = orig.largestWidthLimitDp;
        volumeUuid = orig.volumeUuid;
        storageUuid = orig.storageUuid;
        scanSourceDir = orig.scanSourceDir;
        scanPublicSourceDir = orig.scanPublicSourceDir;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        splitNames = orig.splitNames;
        splitSourceDirs = orig.splitSourceDirs;
        splitPublicSourceDirs = orig.splitPublicSourceDirs;
        splitDependencies = orig.splitDependencies;
        nativeLibraryDir = orig.nativeLibraryDir;
        secondaryNativeLibraryDir = orig.secondaryNativeLibraryDir;
        nativeLibraryRootDir = orig.nativeLibraryRootDir;
        nativeLibraryRootRequiresIsa = orig.nativeLibraryRootRequiresIsa;
        primaryCpuAbi = orig.primaryCpuAbi;
        secondaryCpuAbi = orig.secondaryCpuAbi;
        resourceDirs = orig.resourceDirs;
        overlayPaths = orig.overlayPaths;
        seInfo = orig.seInfo;
        seInfoUser = orig.seInfoUser;
        sharedLibraryFiles = orig.sharedLibraryFiles;
        sharedLibraryInfos = orig.sharedLibraryInfos;
        optionalSharedLibraryInfos = orig.optionalSharedLibraryInfos;
        dataDir = orig.dataDir;
        deviceProtectedDataDir = orig.deviceProtectedDataDir;
        credentialProtectedDataDir = orig.credentialProtectedDataDir;
        uid = orig.uid;
        minSdkVersion = orig.minSdkVersion;
        targetSdkVersion = orig.targetSdkVersion;
        setVersionCode(orig.longVersionCode);
        enabled = orig.enabled;
        enabledSetting = orig.enabledSetting;
        installLocation = orig.installLocation;
        manageSpaceActivityName = orig.manageSpaceActivityName;
        descriptionRes = orig.descriptionRes;
        uiOptions = orig.uiOptions;
        backupAgentName = orig.backupAgentName;
        fullBackupContent = orig.fullBackupContent;
        dataExtractionRulesRes = orig.dataExtractionRulesRes;
        crossProfile = orig.crossProfile;
        networkSecurityConfigRes = orig.networkSecurityConfigRes;
        category = orig.category;
        targetSandboxVersion = orig.targetSandboxVersion;
        classLoaderName = orig.classLoaderName;
        splitClassLoaderNames = orig.splitClassLoaderNames;
        appComponentFactory = orig.appComponentFactory;
        iconRes = orig.iconRes;
        roundIconRes = orig.roundIconRes;
        compileSdkVersion = orig.compileSdkVersion;
        compileSdkVersionCodename = orig.compileSdkVersionCodename;
        mHiddenApiPolicy = orig.mHiddenApiPolicy;
        hiddenUntilInstalled = orig.hiddenUntilInstalled;
        zygotePreloadName = orig.zygotePreloadName;
        gwpAsanMode = orig.gwpAsanMode;
        memtagMode = orig.memtagMode;
        nativeHeapZeroInitialized = orig.nativeHeapZeroInitialized;
        requestRawExternalStorageAccess = orig.requestRawExternalStorageAccess;
        localeConfigRes = orig.localeConfigRes;
        allowCrossUidActivitySwitchFromBelow = orig.allowCrossUidActivitySwitchFromBelow;
        createTimestamp = SystemClock.uptimeMillis();
    }

    public String toString() {
        return "ApplicationInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    public int describeContents() {
        return 0;
    }

    @SuppressWarnings("unchecked")
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        if (dest.maybeWriteSquashed(this)) {
            return;
        }
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString8(taskAffinity);
        dest.writeString8(permission);
        dest.writeString8(processName);
        dest.writeString8(className);
        dest.writeInt(theme);
        dest.writeInt(flags);
        dest.writeInt(privateFlags);
        dest.writeInt(privateFlagsExt);
        dest.writeInt(requiresSmallestWidthDp);
        dest.writeInt(compatibleWidthLimitDp);
        dest.writeInt(largestWidthLimitDp);
        if (storageUuid != null) {
            dest.writeInt(1);
            dest.writeLong(storageUuid.getMostSignificantBits());
            dest.writeLong(storageUuid.getLeastSignificantBits());
        } else {
            dest.writeInt(0);
        }
        dest.writeString8(scanSourceDir);
        dest.writeString8(scanPublicSourceDir);
        dest.writeString8(sourceDir);
        dest.writeString8(publicSourceDir);
        dest.writeString8Array(splitNames);
        dest.writeString8Array(splitSourceDirs);
        dest.writeString8Array(splitPublicSourceDirs);
        dest.writeSparseArray((SparseArray) splitDependencies);
        dest.writeString8(nativeLibraryDir);
        dest.writeString8(secondaryNativeLibraryDir);
        dest.writeString8(nativeLibraryRootDir);
        dest.writeInt(nativeLibraryRootRequiresIsa ? 1 : 0);
        dest.writeString8(primaryCpuAbi);
        dest.writeString8(secondaryCpuAbi);
        dest.writeString8Array(resourceDirs);
        dest.writeString8Array(overlayPaths);
        dest.writeString8(seInfo);
        dest.writeString8(seInfoUser);
        dest.writeString8Array(sharedLibraryFiles);
        dest.writeTypedList(sharedLibraryInfos);
        dest.writeTypedList(optionalSharedLibraryInfos);
        dest.writeString8(dataDir);
        dest.writeString8(deviceProtectedDataDir);
        dest.writeString8(credentialProtectedDataDir);
        dest.writeInt(uid);
        dest.writeInt(minSdkVersion);
        dest.writeInt(targetSdkVersion);
        dest.writeLong(longVersionCode);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(enabledSetting);
        dest.writeInt(installLocation);
        dest.writeString8(manageSpaceActivityName);
        dest.writeString8(backupAgentName);
        dest.writeInt(descriptionRes);
        dest.writeInt(uiOptions);
        dest.writeInt(fullBackupContent);
        dest.writeInt(dataExtractionRulesRes);
        dest.writeBoolean(crossProfile);
        dest.writeInt(networkSecurityConfigRes);
        dest.writeInt(category);
        dest.writeInt(targetSandboxVersion);
        dest.writeString8(classLoaderName);
        dest.writeString8Array(splitClassLoaderNames);
        dest.writeInt(compileSdkVersion);
        dest.writeString8(compileSdkVersionCodename);
        dest.writeString8(appComponentFactory);
        dest.writeInt(iconRes);
        dest.writeInt(roundIconRes);
        dest.writeInt(mHiddenApiPolicy);
        dest.writeInt(hiddenUntilInstalled ? 1 : 0);
        dest.writeString8(zygotePreloadName);
        dest.writeInt(gwpAsanMode);
        dest.writeInt(memtagMode);
        dest.writeInt(nativeHeapZeroInitialized);
        sForBoolean.parcel(requestRawExternalStorageAccess, dest, parcelableFlags);
        dest.writeLong(createTimestamp);
        if (mAppClassNamesByProcess == null) {
            dest.writeInt(0);
        } else {
            final int size = mAppClassNamesByProcess.size();
            dest.writeInt(size);
            for (int i = 0; i < size; i++) {
                dest.writeString(mAppClassNamesByProcess.keyAt(i));
                dest.writeString(mAppClassNamesByProcess.valueAt(i));
            }
        }
        dest.writeInt(localeConfigRes);
        dest.writeInt(allowCrossUidActivitySwitchFromBelow ? 1 : 0);

        sForStringSet.parcel(mKnownActivityEmbeddingCerts, dest, flags);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ApplicationInfo> CREATOR
            = new Parcelable.Creator<ApplicationInfo>() {
        @Override
        public ApplicationInfo createFromParcel(Parcel source) {
            return source.readSquashed(ApplicationInfo::new);
        }

        @Override
        public ApplicationInfo[] newArray(int size) {
            return new ApplicationInfo[size];
        }
    };

    @SuppressWarnings("unchecked")
    private ApplicationInfo(Parcel source) {
        super(source);
        taskAffinity = source.readString8();
        permission = source.readString8();
        processName = source.readString8();
        className = source.readString8();
        theme = source.readInt();
        flags = source.readInt();
        privateFlags = source.readInt();
        privateFlagsExt = source.readInt();
        requiresSmallestWidthDp = source.readInt();
        compatibleWidthLimitDp = source.readInt();
        largestWidthLimitDp = source.readInt();
        if (source.readInt() != 0) {
            storageUuid = new UUID(source.readLong(), source.readLong());
            volumeUuid = StorageManager.convert(storageUuid);
        }
        scanSourceDir = source.readString8();
        scanPublicSourceDir = source.readString8();
        sourceDir = source.readString8();
        publicSourceDir = source.readString8();
        splitNames = source.createString8Array();
        splitSourceDirs = source.createString8Array();
        splitPublicSourceDirs = source.createString8Array();
        splitDependencies = source.readSparseArray(null, int[].class);
        nativeLibraryDir = source.readString8();
        secondaryNativeLibraryDir = source.readString8();
        nativeLibraryRootDir = source.readString8();
        nativeLibraryRootRequiresIsa = source.readInt() != 0;
        primaryCpuAbi = source.readString8();
        secondaryCpuAbi = source.readString8();
        resourceDirs = source.createString8Array();
        overlayPaths = source.createString8Array();
        seInfo = source.readString8();
        seInfoUser = source.readString8();
        sharedLibraryFiles = source.createString8Array();
        sharedLibraryInfos = source.createTypedArrayList(SharedLibraryInfo.CREATOR);
        optionalSharedLibraryInfos = source.createTypedArrayList(SharedLibraryInfo.CREATOR);
        dataDir = source.readString8();
        deviceProtectedDataDir = source.readString8();
        credentialProtectedDataDir = source.readString8();
        uid = source.readInt();
        minSdkVersion = source.readInt();
        targetSdkVersion = source.readInt();
        setVersionCode(source.readLong());
        enabled = source.readInt() != 0;
        enabledSetting = source.readInt();
        installLocation = source.readInt();
        manageSpaceActivityName = source.readString8();
        backupAgentName = source.readString8();
        descriptionRes = source.readInt();
        uiOptions = source.readInt();
        fullBackupContent = source.readInt();
        dataExtractionRulesRes = source.readInt();
        crossProfile = source.readBoolean();
        networkSecurityConfigRes = source.readInt();
        category = source.readInt();
        targetSandboxVersion = source.readInt();
        classLoaderName = source.readString8();
        splitClassLoaderNames = source.createString8Array();
        compileSdkVersion = source.readInt();
        compileSdkVersionCodename = source.readString8();
        appComponentFactory = source.readString8();
        iconRes = source.readInt();
        roundIconRes = source.readInt();
        mHiddenApiPolicy = source.readInt();
        hiddenUntilInstalled = source.readInt() != 0;
        zygotePreloadName = source.readString8();
        gwpAsanMode = source.readInt();
        memtagMode = source.readInt();
        nativeHeapZeroInitialized = source.readInt();
        requestRawExternalStorageAccess = sForBoolean.unparcel(source);
        createTimestamp = source.readLong();
        final int allClassesSize = source.readInt();
        if (allClassesSize > 0) {
            mAppClassNamesByProcess = new ArrayMap<>(allClassesSize);
            for (int i = 0; i < allClassesSize; i++) {
                mAppClassNamesByProcess.put(source.readString(), source.readString());
            }
        }
        localeConfigRes = source.readInt();
        allowCrossUidActivitySwitchFromBelow = source.readInt() != 0;

        mKnownActivityEmbeddingCerts = sForStringSet.unparcel(source);
        if (mKnownActivityEmbeddingCerts.isEmpty()) {
            mKnownActivityEmbeddingCerts = null;
        }
    }

    /**
     * Retrieve the textual description of the application.  This
     * will call back on the given PackageManager to load the description from
     * the application.
     *
     * @param pm A PackageManager from which the label can be loaded; usually
     * the PackageManager from which you originally retrieved this item.
     *
     * @return Returns a CharSequence containing the application's description.
     * If there is no description, null is returned.
     */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    public CharSequence loadDescription(PackageManager pm) {
        if (descriptionRes != 0) {
            CharSequence label = pm.getText(packageName, descriptionRes, this);
            if (label != null) {
                return label;
            }
        }
        return null;
    }

    /**
     * Disable compatibility mode
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void disableCompatibilityMode() {
        flags |= (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS |
                FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS |
                FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
    }

    /**
     * Is using compatibility mode for non densty aware legacy applications.
     *
     * @hide
     */
    public boolean usesCompatibilityMode() {
        return targetSdkVersion < DONUT ||
                (flags & (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS |
                 FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS |
                 FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS)) == 0;
    }

    /** {@hide} */
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = Environment.class)
    public void initForUser(int userId) {
        uid = UserHandle.getUid(userId, UserHandle.getAppId(uid));

        if ("android".equals(packageName)) {
            dataDir = Environment.getDataSystemDirectory().getAbsolutePath();
            return;
        }

        deviceProtectedDataDir = Environment
                .getDataUserDePackageDirectory(volumeUuid, userId, packageName)
                .getAbsolutePath();
        credentialProtectedDataDir = Environment
                .getDataUserCePackageDirectory(volumeUuid, userId, packageName)
                .getAbsolutePath();

        if ((privateFlags & PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) != 0
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            dataDir = deviceProtectedDataDir;
        } else {
            dataDir = credentialProtectedDataDir;
        }
    }

    private boolean isPackageWhitelistedForHiddenApis() {
        return (privateFlagsExt & PRIVATE_FLAG_EXT_ALLOWLISTED_FOR_HIDDEN_APIS) != 0;
    }

    /**
     * @hide
     */
    public boolean usesNonSdkApi() {
        return (privateFlags & PRIVATE_FLAG_USES_NON_SDK_API) != 0;
    }

    /**
     * Whether an app needs to keep the app data on uninstall.
     *
     * @return {@code true} if the app indicates that it needs to keep the app data
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.DELETE_PACKAGES)
    public boolean hasFragileUserData() {
        return (privateFlags & PRIVATE_FLAG_HAS_FRAGILE_USER_DATA) != 0;
    }

    /**
     * Whether an app allows its playback audio to be captured by other apps.
     *
     * @return {@code true} if the app indicates that its audio can be captured by other apps.
     *
     * @hide
     */
    public boolean isAudioPlaybackCaptureAllowed() {
        return (privateFlags & PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE) != 0;
    }

    /**
     * If {@code true} this app requested to run in the legacy storage mode.
     *
     * @hide
     */
    public boolean hasRequestedLegacyExternalStorage() {
        return (privateFlags & PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE) != 0;
    }

    /**
     * Use default value for
     * {@link android.R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess}.
     */
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT = 0;

    /**
     * Raw external storage was requested by this app.
     */
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED = 1;

    /**
     * Raw external storage was not requested by this app.
     */
    public static final int RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED = 2;

    /**
     * These constants need to match the value of
     * {@link android.R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess}.
     * in application manifest.
     * @hide
     */
    @IntDef(prefix = {"RAW_EXTERNAL_STORAGE_"}, value = {
            RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT,
            RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED,
            RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RawExternalStorage {}

    /**
     * @return
     * <ul>
     * <li>{@link ApplicationInfo#RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT} if app didn't specify
     * {@link android.R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess}
     * attribute in the manifest.
     * <li>{@link ApplicationInfo#RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED} if this app requested raw
     * external storage access.
     * <li>{@link ApplicationInfo#RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED} if this app requests to
     * disable raw external storage access
     * </ul
     * <p>
     * Note that this doesn't give any hints on whether the app gets raw external storage access or
     * not. Also, apps may get raw external storage access by default in some cases, see
     * {@link android.R.styleable#AndroidManifestApplication_requestRawExternalStorageAccess}.
     */
    public @RawExternalStorage int getRequestRawExternalStorageAccess() {
        if (requestRawExternalStorageAccess == null) {
            return RAW_EXTERNAL_STORAGE_ACCESS_DEFAULT;
        }
        return requestRawExternalStorageAccess ? RAW_EXTERNAL_STORAGE_ACCESS_REQUESTED
                : RAW_EXTERNAL_STORAGE_ACCESS_NOT_REQUESTED;
    }

    /**
     * If {@code true} this app allows heap pointer tagging.
     *
     * @hide
     */
    public boolean allowsNativeHeapPointerTagging() {
        return (privateFlags & PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING) != 0;
    }

    private boolean isAllowedToUseHiddenApis() {
        if (isSignedWithPlatformKey()) {
            return true;
        } else if (isSystemApp() || isUpdatedSystemApp()) {
            return usesNonSdkApi() || isPackageWhitelistedForHiddenApis();
        } else {
            return false;
        }
    }

    /**
     * @hide
     */
    public @HiddenApiEnforcementPolicy int getHiddenApiEnforcementPolicy() {
        if (isAllowedToUseHiddenApis()) {
            return HIDDEN_API_ENFORCEMENT_DISABLED;
        }
        if (mHiddenApiPolicy != HIDDEN_API_ENFORCEMENT_DEFAULT) {
            return mHiddenApiPolicy;
        }
        return HIDDEN_API_ENFORCEMENT_ENABLED;
    }

    /**
     * @hide
     */
    public void setHiddenApiEnforcementPolicy(@HiddenApiEnforcementPolicy int policy) {
        if (!isValidHiddenApiEnforcementPolicy(policy)) {
            throw new IllegalArgumentException("Invalid API enforcement policy: " + policy);
        }
        mHiddenApiPolicy = policy;
    }

    /**
     * Updates the hidden API enforcement policy for this app from the given values, if appropriate.
     *
     * This will have no effect if this app is not subject to hidden API enforcement, i.e. if it
     * is on the package allowlist.
     *
     * @param policy configured policy for this app, or {@link #HIDDEN_API_ENFORCEMENT_DEFAULT}
     *        if nothing configured.
     * @hide
     */
    public void maybeUpdateHiddenApiEnforcementPolicy(@HiddenApiEnforcementPolicy int policy) {
        if (isPackageWhitelistedForHiddenApis()) {
            return;
        }
        setHiddenApiEnforcementPolicy(policy);
    }

    /**
     * @hide
     */
    public void setVersionCode(long newVersionCode) {
        longVersionCode = newVersionCode;
        versionCode = (int) newVersionCode;
    }

    /**
     * @hide
     */
    @Override
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = android.content.res.Resources.class)
    public Drawable loadDefaultIcon(PackageManager pm) {
        if ((flags & FLAG_EXTERNAL_STORAGE) != 0
                && isPackageUnavailable(pm)) {
            return Resources.getSystem().getDrawable(
                    com.android.internal.R.drawable.sym_app_on_sd_unavailable_icon);
        }
        return pm.getDefaultActivityIcon();
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    @android.ravenwood.annotation.RavenwoodThrow(blockedBy = PackageManager.class)
    private boolean isPackageUnavailable(PackageManager pm) {
        try {
            return pm.getPackageInfo(packageName, 0) == null;
        } catch (NameNotFoundException ex) {
            return true;
        }
    }

    /** @hide */
    public boolean isDefaultToDeviceProtectedStorage() {
        return (privateFlags
                & ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) != 0;
    }

    /** @hide */
    public boolean isDirectBootAware() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE) != 0;
    }

    /**
     * Check whether the application is encryption aware.
     *
     * @see #isDirectBootAware()
     * @see #isPartiallyDirectBootAware()
     *
     * @hide
     */
    @SystemApi
    public boolean isEncryptionAware() {
        return isDirectBootAware() || isPartiallyDirectBootAware();
    }

    /** @hide */
    public boolean isExternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    /**
     * True if the application is installed as an instant app.
     * @hide
     */
    @SystemApi
    public boolean isInstantApp() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_INSTANT) != 0;
    }

    /** @hide */
    public boolean isInternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0;
    }

    /**
     * True if the application is pre-installed on the OEM partition of the system image.
     * @hide
     */
    @SystemApi
    public boolean isOem() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    /** @hide */
    public boolean isOdm() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_ODM) != 0;
    }

    /** @hide */
    public boolean isPartiallyDirectBootAware() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE) != 0;
    }

    /** @hide */
    public boolean isSignedWithPlatformKey() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY) != 0;
    }

    /**
     * @return {@code true} if the application is permitted to hold privileged permissions.
     *
     * @hide */
    @TestApi
    @SystemApi
    @RequiresPermission(Manifest.permission.INSTALL_PACKAGES)
    public boolean isPrivilegedApp() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    /** @hide */
    public boolean isRequiredForSystemUser() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER) != 0;
    }

    /** @hide */
    public boolean isStaticSharedLibrary() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY) != 0;
    }

    /** @hide */
    @TestApi
    public boolean isSystemApp() {
        return (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /** @hide */
    public boolean isUpdatedSystemApp() {
        return (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /**
     * True if the application is pre-installed on the vendor partition of the system image.
     * @hide
     */
    @SystemApi
    public boolean isVendor() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    /**
     * True if the application is pre-installed on the product partition of the system image.
     * @hide
     */
    @SystemApi
    public boolean isProduct() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    /** @hide */
    public boolean isSystemExt() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_SYSTEM_EXT) != 0;
    }

    /** @hide */
    public boolean isEmbeddedDexUsed() {
        return (privateFlags & PRIVATE_FLAG_USE_EMBEDDED_DEX) != 0;
    }

    /**
     * Returns whether or not this application was installed as a virtual preload.
     */
    public boolean isVirtualPreload() {
        return (privateFlags & PRIVATE_FLAG_VIRTUAL_PRELOAD) != 0;
    }

    /**
     * Returns whether or not this application can be profiled by the shell user,
     * even when running on a device that is running in user mode.
     */
    public boolean isProfileableByShell() {
        return (privateFlags & PRIVATE_FLAG_PROFILEABLE_BY_SHELL) != 0;
    }

    /**
     * Returns whether this application can be profiled, either by the shell user or the system.
     */
    public boolean isProfileable() {
        return (privateFlagsExt & PRIVATE_FLAG_EXT_PROFILEABLE) != 0;
    }

    /**
     * Returns whether attributions provided by the application are meant to be user-visible.
     * Defaults to false if application info is retrieved without
     * {@link PackageManager#GET_ATTRIBUTIONS_LONG}.
     */
    public boolean areAttributionsUserVisible() {
        return (privateFlagsExt & PRIVATE_FLAG_EXT_ATTRIBUTIONS_ARE_USER_VISIBLE) != 0;
    }

    /**
     * Returns true if the app has declared in its manifest that it wants its split APKs to be
     * loaded into isolated Contexts, with their own ClassLoaders and Resources objects.
     * @hide
     */
    public boolean requestsIsolatedSplitLoading() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING) != 0;
    }

    /**
     * Returns true if the package has declared in its manifest that it is a
     * runtime resource overlay.
     */
    public boolean isResourceOverlay() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY) != 0;
    }

    /**
     * Checks if a changeId is enabled for the current user
     * @param changeId The changeId to verify
     * @return True of the changeId is enabled
     * @hide
     */
    public boolean isChangeEnabled(long changeId) {
        return CompatChanges.isChangeEnabled(changeId, packageName,
                UserHandle.getUserHandleForUid(uid));
    }

    /**
     * @return whether the app has requested exemption from the foreground service restrictions.
     * This does not take any affect for now.
     * @hide
     */
    @TestApi
    public boolean hasRequestForegroundServiceExemption() {
        return (privateFlagsExt
                & ApplicationInfo.PRIVATE_FLAG_EXT_REQUEST_FOREGROUND_SERVICE_EXEMPTION) != 0;
    }

    /**
     * Returns whether the application will use the {@link android.window.OnBackInvokedCallback}
     * navigation system instead of the {@link android.view.KeyEvent#KEYCODE_BACK} and related
     * callbacks.
     *
     * @hide
     */
    @TestApi
    public boolean isOnBackInvokedCallbackEnabled() {
        return ((privateFlagsExt & PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK)) != 0;
    }

    /**
     * @hide
     */
    @Override protected ApplicationInfo getApplicationInfo() {
        return this;
    }

    /**
     * Return all the APK paths that may be required to load this application, including all
     * splits, shared libraries, and resource overlays.
     * @hide
     */
    public String[] getAllApkPaths() {
        final String[][] inputLists = {
                splitSourceDirs, sharedLibraryFiles, resourceDirs, overlayPaths
        };
        final List<String> output = new ArrayList<>(10);
        if (sourceDir != null) {
            output.add(sourceDir);
        }
        for (String[] inputList : inputLists) {
            if (inputList != null) {
                for (String input : inputList) {
                    output.add(input);
                }
            }
        }
        return output.toArray(new String[output.size()]);
    }

    /** {@hide} */ public void setCodePath(String codePath) { scanSourceDir = codePath; }
    /** {@hide} */ public void setBaseCodePath(String baseCodePath) { sourceDir = baseCodePath; }
    /** {@hide} */ public void setSplitCodePaths(String[] splitCodePaths) { splitSourceDirs = splitCodePaths; }
    /** {@hide} */ public void setResourcePath(String resourcePath) { scanPublicSourceDir = resourcePath; }
    /** {@hide} */ public void setBaseResourcePath(String baseResourcePath) { publicSourceDir = baseResourcePath; }
    /** {@hide} */ public void setSplitResourcePaths(String[] splitResourcePaths) { splitPublicSourceDirs = splitResourcePaths; }
    /** {@hide} */ public void setGwpAsanMode(@GwpAsanMode int value) { gwpAsanMode = value; }
    /** {@hide} */ public void setMemtagMode(@MemtagMode int value) { memtagMode = value; }
    /** {@hide} */ public void setNativeHeapZeroInitialized(@NativeHeapZeroInitialized int value) {
        nativeHeapZeroInitialized = value;
    }
    /** {@hide} */
    public void setRequestRawExternalStorageAccess(@Nullable Boolean value) {
        requestRawExternalStorageAccess = value;
    }

    /**
     * Replaces {@link #mAppClassNamesByProcess}. This takes over the ownership of the passed map.
     * Do not modify the argument at the callsite.
     * {@hide}
     */
    public void setAppClassNamesByProcess(@Nullable ArrayMap<String, String> value) {
        if (ArrayUtils.size(value) == 0) {
            mAppClassNamesByProcess = null;
        } else {
            mAppClassNamesByProcess = value;
        }
    }

    /** {@hide} */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public String getCodePath() { return scanSourceDir; }
    /** {@hide} */ public String getBaseCodePath() { return sourceDir; }
    /** {@hide} */ public String[] getSplitCodePaths() { return splitSourceDirs; }
    /** {@hide} */ public String getResourcePath() { return scanPublicSourceDir; }
    /** {@hide} */
    @UnsupportedAppUsage
    public String getBaseResourcePath() { return publicSourceDir; }
    /** {@hide} */ public String[] getSplitResourcePaths() { return splitPublicSourceDirs; }
    @GwpAsanMode
    public int getGwpAsanMode() { return gwpAsanMode; }

    /**
     * Returns whether the application has requested Memtag to be enabled, disabled, or left
     * unspecified. Processes can override this setting.
     */
    @MemtagMode
    public int getMemtagMode() {
        return memtagMode;
    }

    /**
     * Returns whether the application has requested automatic zero-initialization of native heap
     * memory allocations to be enabled or disabled.
     */
    @NativeHeapZeroInitialized
    public int getNativeHeapZeroInitialized() {
        return nativeHeapZeroInitialized;
    }

    /**
     * Return the application class name defined in the manifest. The class name set in the
     * <processes> tag for this process, then return it. Otherwise it'll return the class
     * name set in the <application> tag. If neither is set, it'll return null.
     * @hide
     */
    @Nullable
    public String getCustomApplicationClassNameForProcess(String processName) {
        if (mAppClassNamesByProcess != null) {
            String byProcess = mAppClassNamesByProcess.get(processName);
            if (byProcess != null) {
                return byProcess;
            }
        }
        return className;
    }

    /** @hide */ public void setLocaleConfigRes(int value) { localeConfigRes = value; }

    /**
     * Return the resource id pointing to the resource file that provides the application's locales
     * configuration.
     *
     * @hide
     */
    public int getLocaleConfigRes() {
        return localeConfigRes;
    }

    /**
     *  List of all shared libraries this application is linked against. This
     *  list will only be set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     *  PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving the structure.
     *
     *  NOTE: the list also contains the result of {@link #getOptionalSharedLibraryInfos}.
     *
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public List<SharedLibraryInfo> getSharedLibraryInfos() {
        if (sharedLibraryInfos == null) {
            return Collections.EMPTY_LIST;
        }
        return sharedLibraryInfos;
    }

    /**
     *  List of all shared libraries this application is optionally linked against. This
     *  list will only be set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     *  PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving the structure.
     *
     * @hide
     */
    @NonNull
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @FlaggedApi(Flags.FLAG_SDK_LIB_INDEPENDENCE)
    public List<SharedLibraryInfo> getOptionalSharedLibraryInfos() {
        if (optionalSharedLibraryInfos == null) {
            return Collections.EMPTY_LIST;
        }
        return optionalSharedLibraryInfos;
    }

    /**
     * Gets the trusted host certificate digests of apps that are allowed to embed activities of
     * this application. The digests are computed using the SHA-256 digest algorithm.
     * @see android.R.attr#knownActivityEmbeddingCerts
     */
    @NonNull
    public Set<String> getKnownActivityEmbeddingCerts() {
        return mKnownActivityEmbeddingCerts == null ? Collections.emptySet()
                : mKnownActivityEmbeddingCerts;
    }

    /**
     * Sets the trusted host certificates of apps that are allowed to embed activities of this
     * application.
     * @see #getKnownActivityEmbeddingCerts()
     * @hide
     */
    public void setKnownActivityEmbeddingCerts(@NonNull Set<String> knownActivityEmbeddingCerts) {
        // Convert the provided digest to upper case for consistent Set membership
        // checks when verifying the signing certificate digests of requesting apps.
        mKnownActivityEmbeddingCerts = new ArraySet<>();
        for (String knownCert : knownActivityEmbeddingCerts) {
            mKnownActivityEmbeddingCerts.add(knownCert.toUpperCase(Locale.US));
        }
    }

    /**
     * Sets whether the application will use the {@link android.window.OnBackInvokedCallback}
     * navigation system instead of the {@link android.view.KeyEvent#KEYCODE_BACK} and related
     * callbacks. Intended to be used from tests only.
     *
     * @see #isOnBackInvokedCallbackEnabled()
     * @hide
     */
    @TestApi
    public void setEnableOnBackInvokedCallback(boolean isEnable) {
        if (isEnable) {
            privateFlagsExt |= PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        } else {
            privateFlagsExt &= ~PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        }
    }
}
