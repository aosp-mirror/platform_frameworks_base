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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;

import com.android.internal.util.ArrayUtils;
import com.android.server.SystemConfig;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Information you can retrieve about a particular application.  This
 * corresponds to information collected from the AndroidManifest.xml's
 * &lt;application&gt; tag.
 */
public class ApplicationInfo extends PackageItemInfo implements Parcelable {
    
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
    @UnsupportedAppUsage
    public int fullBackupContent = 0;

    /**
     * The default extra UI options for activities in this application.
     * Set from the {@link android.R.attr#uiOptions} attribute in the
     * activity's manifest.
     */
    public int uiOptions = 0;

    /**
     * Value for {@link #flags}: if set, this application is installed in the
     * device's system image.
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
     */
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
     * (e.g., HTTP rather than HTTPS; WebSockets rather than WebSockets Secure; XMPP, IMAP, STMP
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
     * Value for {@link #privateFlags}: Set to true if the application has been
     * installed using the forward lock option.
     *
     * NOTE: DO NOT CHANGE THIS VALUE!  It is saved in packages.xml.
     *
     * {@hide}
     */
    public static final int PRIVATE_FLAG_FORWARD_LOCK = 1<<2;

    /**
     * Value for {@link #privateFlags}: set to {@code true} if the application
     * is permitted to hold privileged permissions.
     *
     * {@hide}
     */
    @UnsupportedAppUsage
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
     * Indicates whether this package requires access to non-SDK APIs.
     * Only system apps and tests are allowed to use this property.
     * @hide
     */
    public static final int PRIVATE_FLAG_USES_NON_SDK_API = 1 << 22;

    /** @hide */
    @IntDef(flag = true, prefix = { "PRIVATE_FLAG_" }, value = {
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE,
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION,
            PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE,
            PRIVATE_FLAG_BACKUP_IN_FOREGROUND,
            PRIVATE_FLAG_CANT_SAVE_STATE,
            PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE,
            PRIVATE_FLAG_DIRECT_BOOT_AWARE,
            PRIVATE_FLAG_FORWARD_LOCK,
            PRIVATE_FLAG_HAS_DOMAIN_URLS,
            PRIVATE_FLAG_HIDDEN,
            PRIVATE_FLAG_INSTANT,
            PRIVATE_FLAG_ISOLATED_SPLIT_LOADING,
            PRIVATE_FLAG_OEM,
            PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE,
            PRIVATE_FLAG_PRIVILEGED,
            PRIVATE_FLAG_PRODUCT,
            PRIVATE_FLAG_REQUIRED_FOR_SYSTEM_USER,
            PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY,
            PRIVATE_FLAG_STATIC_SHARED_LIBRARY,
            PRIVATE_FLAG_VENDOR,
            PRIVATE_FLAG_VIRTUAL_PRELOAD,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ApplicationInfoPrivateFlags {}

    /**
     * Private/hidden flags. See {@code PRIVATE_FLAG_...} constants.
     * @hide
     */
    @UnsupportedAppUsage
    public @ApplicationInfoPrivateFlags int privateFlags;

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

    /** @removed */
    @Deprecated
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
     */
    public String[] splitNames;

    /**
     * Full paths to zero or more split APKs, indexed by the same order as {@link #splitNames}.
     */
    public String[] splitSourceDirs;

    /**
     * Full path to the publicly available parts of {@link #splitSourceDirs},
     * including resources and manifest. This may be different from
     * {@link #splitSourceDirs} if an application is forward locked.
     *
     * @see #splitSourceDirs
     */
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
     * {@hide}
     */
    public List<SharedLibraryInfo> sharedLibraryInfos;

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
     * The user-visible SDK version (ex. 26) of the framework against which the application claims
     * to have been compiled, or {@code 0} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#CODENAME Build.VERSION.SDK_INT}.
     *
     * @hide For platform use only; we don't expect developers to need to read this value.
     */
    public int compileSdkVersion;

    /**
     * The development codename (ex. "O", "REL") of the framework against which the application
     * claims to have been compiled, or {@code null} if not specified.
     * <p>
     * This property is the compile-time equivalent of
     * {@link android.os.Build.VERSION#CODENAME Build.VERSION.CODENAME}.
     *
     * @hide For platform use only; we don't expect developers to need to read this value.
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
            CATEGORY_PRODUCTIVITY
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
     * Return a concise, localized title for the given
     * {@link ApplicationInfo#category} value, or {@code null} for unknown
     * values such as {@link #CATEGORY_UNDEFINED}.
     *
     * @see #category
     */
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

    /**
     * Represents the default policy. The actual policy used will depend on other properties of
     * the application, e.g. the target SDK version.
     * @hide
     */
    public static final int HIDDEN_API_ENFORCEMENT_DEFAULT = -1;
    /**
     * No API enforcement; the app can access the entire internal private API. Only for use by
     * system apps.
     * @hide
     */
    public static final int HIDDEN_API_ENFORCEMENT_DISABLED = 0;
    /**
     * No API enforcement, but enable the detection logic and warnings. Observed behaviour is the
     * same as {@link #HIDDEN_API_ENFORCEMENT_DISABLED} but you may see warnings in the log when
     * APIs are accessed.
     * @hide
     * */
    public static final int HIDDEN_API_ENFORCEMENT_JUST_WARN = 1;
    /**
     * Dark grey list enforcement. Enforces the dark grey and black lists
     * @hide
     */
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

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        if ((dumpFlags & DUMP_FLAG_DETAILS) != 0 && className != null) {
            pw.println(prefix + "className=" + className);
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
            if (networkSecurityConfigRes != 0) {
                pw.println(prefix + "networkSecurityConfigRes=0x"
                        + Integer.toHexString(networkSecurityConfigRes));
            }
            if (category != CATEGORY_UNDEFINED) {
                pw.println(prefix + "category=" + category);
            }
            pw.println(prefix + "HiddenApiEnforcementPolicy=" + getHiddenApiEnforcementPolicy());
            pw.println(prefix + "usesNonSdkApi=" + usesNonSdkApi());
        }
        super.dumpBack(pw, prefix);
    }

    /** {@hide} */
    public void writeToProto(ProtoOutputStream proto, long fieldId, int dumpFlags) {
        long token = proto.start(fieldId);
        super.writeToProto(proto, ApplicationInfoProto.PACKAGE);
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
            proto.end(detailToken);
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
        private PackageManager   mPM;
    }

    public ApplicationInfo() {
    }
    
    public ApplicationInfo(ApplicationInfo orig) {
        super(orig);
        taskAffinity = orig.taskAffinity;
        permission = orig.permission;
        processName = orig.processName;
        className = orig.className;
        theme = orig.theme;
        flags = orig.flags;
        privateFlags = orig.privateFlags;
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
        seInfo = orig.seInfo;
        seInfoUser = orig.seInfoUser;
        sharedLibraryFiles = orig.sharedLibraryFiles;
        sharedLibraryInfos = orig.sharedLibraryInfos;
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
        networkSecurityConfigRes = orig.networkSecurityConfigRes;
        category = orig.category;
        targetSandboxVersion = orig.targetSandboxVersion;
        classLoaderName = orig.classLoaderName;
        splitClassLoaderNames = orig.splitClassLoaderNames;
        appComponentFactory = orig.appComponentFactory;
        compileSdkVersion = orig.compileSdkVersion;
        compileSdkVersionCodename = orig.compileSdkVersionCodename;
        mHiddenApiPolicy = orig.mHiddenApiPolicy;
        hiddenUntilInstalled = orig.hiddenUntilInstalled;
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
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString(taskAffinity);
        dest.writeString(permission);
        dest.writeString(processName);
        dest.writeString(className);
        dest.writeInt(theme);
        dest.writeInt(flags);
        dest.writeInt(privateFlags);
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
        dest.writeString(scanSourceDir);
        dest.writeString(scanPublicSourceDir);
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(splitNames);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeSparseArray((SparseArray) splitDependencies);
        dest.writeString(nativeLibraryDir);
        dest.writeString(secondaryNativeLibraryDir);
        dest.writeString(nativeLibraryRootDir);
        dest.writeInt(nativeLibraryRootRequiresIsa ? 1 : 0);
        dest.writeString(primaryCpuAbi);
        dest.writeString(secondaryCpuAbi);
        dest.writeStringArray(resourceDirs);
        dest.writeString(seInfo);
        dest.writeString(seInfoUser);
        dest.writeStringArray(sharedLibraryFiles);
        dest.writeTypedList(sharedLibraryInfos);
        dest.writeString(dataDir);
        dest.writeString(deviceProtectedDataDir);
        dest.writeString(credentialProtectedDataDir);
        dest.writeInt(uid);
        dest.writeInt(minSdkVersion);
        dest.writeInt(targetSdkVersion);
        dest.writeLong(longVersionCode);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(enabledSetting);
        dest.writeInt(installLocation);
        dest.writeString(manageSpaceActivityName);
        dest.writeString(backupAgentName);
        dest.writeInt(descriptionRes);
        dest.writeInt(uiOptions);
        dest.writeInt(fullBackupContent);
        dest.writeInt(networkSecurityConfigRes);
        dest.writeInt(category);
        dest.writeInt(targetSandboxVersion);
        dest.writeString(classLoaderName);
        dest.writeStringArray(splitClassLoaderNames);
        dest.writeInt(compileSdkVersion);
        dest.writeString(compileSdkVersionCodename);
        dest.writeString(appComponentFactory);
        dest.writeInt(mHiddenApiPolicy);
        dest.writeInt(hiddenUntilInstalled ? 1 : 0);
    }

    public static final Parcelable.Creator<ApplicationInfo> CREATOR
            = new Parcelable.Creator<ApplicationInfo>() {
        public ApplicationInfo createFromParcel(Parcel source) {
            return new ApplicationInfo(source);
        }
        public ApplicationInfo[] newArray(int size) {
            return new ApplicationInfo[size];
        }
    };

    @SuppressWarnings("unchecked")
    private ApplicationInfo(Parcel source) {
        super(source);
        taskAffinity = source.readString();
        permission = source.readString();
        processName = source.readString();
        className = source.readString();
        theme = source.readInt();
        flags = source.readInt();
        privateFlags = source.readInt();
        requiresSmallestWidthDp = source.readInt();
        compatibleWidthLimitDp = source.readInt();
        largestWidthLimitDp = source.readInt();
        if (source.readInt() != 0) {
            storageUuid = new UUID(source.readLong(), source.readLong());
            volumeUuid = StorageManager.convert(storageUuid);
        }
        scanSourceDir = source.readString();
        scanPublicSourceDir = source.readString();
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        splitNames = source.readStringArray();
        splitSourceDirs = source.readStringArray();
        splitPublicSourceDirs = source.readStringArray();
        splitDependencies = source.readSparseArray(null);
        nativeLibraryDir = source.readString();
        secondaryNativeLibraryDir = source.readString();
        nativeLibraryRootDir = source.readString();
        nativeLibraryRootRequiresIsa = source.readInt() != 0;
        primaryCpuAbi = source.readString();
        secondaryCpuAbi = source.readString();
        resourceDirs = source.readStringArray();
        seInfo = source.readString();
        seInfoUser = source.readString();
        sharedLibraryFiles = source.readStringArray();
        sharedLibraryInfos = source.createTypedArrayList(SharedLibraryInfo.CREATOR);
        dataDir = source.readString();
        deviceProtectedDataDir = source.readString();
        credentialProtectedDataDir = source.readString();
        uid = source.readInt();
        minSdkVersion = source.readInt();
        targetSdkVersion = source.readInt();
        setVersionCode(source.readLong());
        enabled = source.readInt() != 0;
        enabledSetting = source.readInt();
        installLocation = source.readInt();
        manageSpaceActivityName = source.readString();
        backupAgentName = source.readString();
        descriptionRes = source.readInt();
        uiOptions = source.readInt();
        fullBackupContent = source.readInt();
        networkSecurityConfigRes = source.readInt();
        category = source.readInt();
        targetSandboxVersion = source.readInt();
        classLoaderName = source.readString();
        splitClassLoaderNames = source.readStringArray();
        compileSdkVersion = source.readInt();
        compileSdkVersionCodename = source.readString();
        appComponentFactory = source.readString();
        mHiddenApiPolicy = source.readInt();
        hiddenUntilInstalled = source.readInt() != 0;
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
        return SystemConfig.getInstance().getHiddenApiWhitelistedApps().contains(packageName);
    }

    /**
     * @hide
     */
    public boolean usesNonSdkApi() {
        return (privateFlags & PRIVATE_FLAG_USES_NON_SDK_API) != 0;
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
     * is on the package whitelist.
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
    public Drawable loadDefaultIcon(PackageManager pm) {
        if ((flags & FLAG_EXTERNAL_STORAGE) != 0
                && isPackageUnavailable(pm)) {
            return Resources.getSystem().getDrawable(
                    com.android.internal.R.drawable.sym_app_on_sd_unavailable_icon);
        }
        return pm.getDefaultActivityIcon();
    }
    
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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

    /** @hide */
    public boolean isEncryptionAware() {
        return isDirectBootAware() || isPartiallyDirectBootAware();
    }

    /** @hide */
    public boolean isExternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
    }

    /** @hide */
    public boolean isExternalAsec() {
        return TextUtils.isEmpty(volumeUuid) && isExternal();
    }

    /** @hide */
    @UnsupportedAppUsage
    public boolean isForwardLocked() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK) != 0;
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

    /** @hide */
    public boolean isOem() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_OEM) != 0;
    }

    /** @hide */
    public boolean isPartiallyDirectBootAware() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE) != 0;
    }

    /** @hide */
    public boolean isSignedWithPlatformKey() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_SIGNED_WITH_PLATFORM_KEY) != 0;
    }

    /** @hide */
    @TestApi
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

    /** @hide */
    public boolean isVendor() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_VENDOR) != 0;
    }

    /** @hide */
    public boolean isProduct() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRODUCT) != 0;
    }

    /**
     * Returns whether or not this application was installed as a virtual preload.
     */
    public boolean isVirtualPreload() {
        return (privateFlags & PRIVATE_FLAG_VIRTUAL_PRELOAD) != 0;
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
     * @hide
     */
    @Override protected ApplicationInfo getApplicationInfo() {
        return this;
    }

    /** {@hide} */ public void setCodePath(String codePath) { scanSourceDir = codePath; }
    /** {@hide} */ public void setBaseCodePath(String baseCodePath) { sourceDir = baseCodePath; }
    /** {@hide} */ public void setSplitCodePaths(String[] splitCodePaths) { splitSourceDirs = splitCodePaths; }
    /** {@hide} */ public void setResourcePath(String resourcePath) { scanPublicSourceDir = resourcePath; }
    /** {@hide} */ public void setBaseResourcePath(String baseResourcePath) { publicSourceDir = baseResourcePath; }
    /** {@hide} */ public void setSplitResourcePaths(String[] splitResourcePaths) { splitPublicSourceDirs = splitResourcePaths; }

    /** {@hide} */
    @UnsupportedAppUsage
    public String getCodePath() { return scanSourceDir; }
    /** {@hide} */ public String getBaseCodePath() { return sourceDir; }
    /** {@hide} */ public String[] getSplitCodePaths() { return splitSourceDirs; }
    /** {@hide} */ public String getResourcePath() { return scanPublicSourceDir; }
    /** {@hide} */
    @UnsupportedAppUsage
    public String getBaseResourcePath() { return publicSourceDir; }
    /** {@hide} */ public String[] getSplitResourcePaths() { return splitPublicSourceDirs; }
}
