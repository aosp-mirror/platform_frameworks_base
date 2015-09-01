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

import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Printer;

import com.android.internal.util.ArrayUtils;

import java.text.Collator;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

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
     * @see {@link android.content.Context#getNoBackupFilesDir}
     * @see {@link #FLAG_ALLOW_BACKUP}
     *
     * @hide
     */
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
     * install as an update to a built-in system application.
     */
    public static final int FLAG_UPDATED_SYSTEM_APP = 1<<7;
    
    /**
     * Value for {@link #flags}: this is set of the application has specified
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
     * accomodate different screen densities.  Corresponds to
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
     * Value for {@link #flags}: true if the application was declared to be a game, or
     * false if it is a non-game application.
     */
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
     * <p>NOTE: {@code WebView} does not honor this flag.
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
    public static final int PRIVATE_FLAG_PRIVILEGED = 1<<3;

    /**
     * Value for {@link #flags}: {@code true} if the application has any IntentFiler with some
     * data URI using HTTP or HTTPS with an associated VIEW action.
     *
     * {@hide}
     */
    public static final int PRIVATE_FLAG_HAS_DOMAIN_URLS = 1<<4;

    /**
     * Private/hidden flags. See {@code PRIVATE_FLAG_...} constants.
     * {@hide}
     */
    public int privateFlags;

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

    /** {@hide} */
    public String volumeUuid;
    /** {@hide} */
    public String scanSourceDir;
    /** {@hide} */
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
     * Full paths to zero or more split APKs that, when combined with the base
     * APK defined in {@link #sourceDir}, form a complete application.
     */
    public String[] splitSourceDirs;

    /**
     * Full path to the publicly available parts of {@link #splitSourceDirs},
     * including resources and manifest. This may be different from
     * {@link #splitSourceDirs} if an application is forward locked.
     */
    public String[] splitPublicSourceDirs;

    /**
     * Full paths to the locations of extra resource packages this application
     * uses. This field is only used if there are extra resource packages,
     * otherwise it is null.
     * 
     * {@hide}
     */
    public String[] resourceDirs;

    /**
     * String retrieved from the seinfo tag found in selinux policy. This value
     * can be overridden with a value set through the mac_permissions.xml policy
     * construct. This value is useful in setting an SELinux security context on
     * the process as well as its data directory. The String default is being used
     * here to represent a catchall label when no policy matches.
     *
     * {@hide}
     */
    public String seinfo = "default";

    /**
     * Paths to all shared libraries this application is linked against.  This
     * field is only set if the {@link PackageManager#GET_SHARED_LIBRARY_FILES
     * PackageManager.GET_SHARED_LIBRARY_FILES} flag was used when retrieving
     * the structure.
     */
    public String[] sharedLibraryFiles;
    
    /**
     * Full path to a directory assigned to the package for its persistent
     * data.
     */
    public String dataDir;

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
    public String primaryCpuAbi;

    /**
     * The secondary ABI for this application. Might be non-null for multi-arch
     * installs. The application itself never uses this ABI, but other applications that
     * use its code might.
     *
     * {@hide}
     */
    public String secondaryCpuAbi;

    /**
     * The kernel user-ID that has been assigned to this application;
     * currently this is not a unique ID (multiple applications can have
     * the same uid).
     */
    public int uid;
    
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
    public int versionCode;

    /**
     * When false, indicates that all components within this application are
     * considered disabled, regardless of their individually set enabled status.
     */
    public boolean enabled = true;

    /**
     * For convenient access to the current enabled setting of this app.
     * @hide
     */
    public int enabledSetting = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;

    /**
     * For convenient access to package's install location.
     * @hide
     */
    public int installLocation = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    public void dump(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        if (className != null) {
            pw.println(prefix + "className=" + className);
        }
        if (permission != null) {
            pw.println(prefix + "permission=" + permission);
        }
        pw.println(prefix + "processName=" + processName);
        pw.println(prefix + "taskAffinity=" + taskAffinity);
        pw.println(prefix + "uid=" + uid + " flags=0x" + Integer.toHexString(flags)
                + " privateFlags=0x" + Integer.toHexString(privateFlags)
                + " theme=0x" + Integer.toHexString(theme));
        pw.println(prefix + "requiresSmallestWidthDp=" + requiresSmallestWidthDp
                + " compatibleWidthLimitDp=" + compatibleWidthLimitDp
                + " largestWidthLimitDp=" + largestWidthLimitDp);
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
            pw.println(prefix + "resourceDirs=" + resourceDirs);
        }
        if (seinfo != null) {
            pw.println(prefix + "seinfo=" + seinfo);
        }
        pw.println(prefix + "dataDir=" + dataDir);
        if (sharedLibraryFiles != null) {
            pw.println(prefix + "sharedLibraryFiles=" + Arrays.toString(sharedLibraryFiles));
        }
        pw.println(prefix + "enabled=" + enabled + " targetSdkVersion=" + targetSdkVersion
                + " versionCode=" + versionCode);
        if (manageSpaceActivityName != null) {
            pw.println(prefix + "manageSpaceActivityName="+manageSpaceActivityName);
        }
        if (descriptionRes != 0) {
            pw.println(prefix + "description=0x"+Integer.toHexString(descriptionRes));
        }
        if (uiOptions != 0) {
            pw.println(prefix + "uiOptions=0x" + Integer.toHexString(uiOptions));
        }
        pw.println(prefix + "supportsRtl=" + (hasRtlSupport() ? "true" : "false"));
        if (fullBackupContent > 0) {
            pw.println(prefix + "fullBackupContent=@xml/" + fullBackupContent);
        } else {
            pw.println(prefix + "fullBackupContent=" + (fullBackupContent < 0 ? "false" : "true"));
        }
        super.dumpBack(pw, prefix);
    }

    /**
     * @return true if "supportsRtl" has been set to true in the AndroidManifest
     * @hide
     */
    public boolean hasRtlSupport() {
        return (flags & FLAG_SUPPORTS_RTL) == FLAG_SUPPORTS_RTL;
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

        private final Collator   sCollator = Collator.getInstance();
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
        scanSourceDir = orig.scanSourceDir;
        scanPublicSourceDir = orig.scanPublicSourceDir;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        splitSourceDirs = orig.splitSourceDirs;
        splitPublicSourceDirs = orig.splitPublicSourceDirs;
        nativeLibraryDir = orig.nativeLibraryDir;
        secondaryNativeLibraryDir = orig.secondaryNativeLibraryDir;
        nativeLibraryRootDir = orig.nativeLibraryRootDir;
        nativeLibraryRootRequiresIsa = orig.nativeLibraryRootRequiresIsa;
        primaryCpuAbi = orig.primaryCpuAbi;
        secondaryCpuAbi = orig.secondaryCpuAbi;
        resourceDirs = orig.resourceDirs;
        seinfo = orig.seinfo;
        sharedLibraryFiles = orig.sharedLibraryFiles;
        dataDir = orig.dataDir;
        uid = orig.uid;
        targetSdkVersion = orig.targetSdkVersion;
        versionCode = orig.versionCode;
        enabled = orig.enabled;
        enabledSetting = orig.enabledSetting;
        installLocation = orig.installLocation;
        manageSpaceActivityName = orig.manageSpaceActivityName;
        descriptionRes = orig.descriptionRes;
        uiOptions = orig.uiOptions;
        backupAgentName = orig.backupAgentName;
        fullBackupContent = orig.fullBackupContent;
    }


    public String toString() {
        return "ApplicationInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    public int describeContents() {
        return 0;
    }

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
        dest.writeString(volumeUuid);
        dest.writeString(scanSourceDir);
        dest.writeString(scanPublicSourceDir);
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeString(nativeLibraryDir);
        dest.writeString(secondaryNativeLibraryDir);
        dest.writeString(nativeLibraryRootDir);
        dest.writeInt(nativeLibraryRootRequiresIsa ? 1 : 0);
        dest.writeString(primaryCpuAbi);
        dest.writeString(secondaryCpuAbi);
        dest.writeStringArray(resourceDirs);
        dest.writeString(seinfo);
        dest.writeStringArray(sharedLibraryFiles);
        dest.writeString(dataDir);
        dest.writeInt(uid);
        dest.writeInt(targetSdkVersion);
        dest.writeInt(versionCode);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeInt(enabledSetting);
        dest.writeInt(installLocation);
        dest.writeString(manageSpaceActivityName);
        dest.writeString(backupAgentName);
        dest.writeInt(descriptionRes);
        dest.writeInt(uiOptions);
        dest.writeInt(fullBackupContent);
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
        volumeUuid = source.readString();
        scanSourceDir = source.readString();
        scanPublicSourceDir = source.readString();
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        splitSourceDirs = source.readStringArray();
        splitPublicSourceDirs = source.readStringArray();
        nativeLibraryDir = source.readString();
        secondaryNativeLibraryDir = source.readString();
        nativeLibraryRootDir = source.readString();
        nativeLibraryRootRequiresIsa = source.readInt() != 0;
        primaryCpuAbi = source.readString();
        secondaryCpuAbi = source.readString();
        resourceDirs = source.readStringArray();
        seinfo = source.readString();
        sharedLibraryFiles = source.readStringArray();
        dataDir = source.readString();
        uid = source.readInt();
        targetSdkVersion = source.readInt();
        versionCode = source.readInt();
        enabled = source.readInt() != 0;
        enabledSetting = source.readInt();
        installLocation = source.readInt();
        manageSpaceActivityName = source.readString();
        backupAgentName = source.readString();
        descriptionRes = source.readInt();
        uiOptions = source.readInt();
        fullBackupContent = source.readInt();
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
    public void disableCompatibilityMode() {
        flags |= (FLAG_SUPPORTS_LARGE_SCREENS | FLAG_SUPPORTS_NORMAL_SCREENS |
                FLAG_SUPPORTS_SMALL_SCREENS | FLAG_RESIZEABLE_FOR_SCREENS |
                FLAG_SUPPORTS_SCREEN_DENSITIES | FLAG_SUPPORTS_XLARGE_SCREENS);
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
    
    private boolean isPackageUnavailable(PackageManager pm) {
        try {
            return pm.getPackageInfo(packageName, 0) == null;
        } catch (NameNotFoundException ex) {
            return true;
        }
    }

    /**
     * @hide
     */
    public boolean isForwardLocked() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_FORWARD_LOCK) != 0;
    }

    /**
     * @hide
     */
    public boolean isSystemApp() {
        return (flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    /**
     * @hide
     */
    public boolean isPrivilegedApp() {
        return (privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
    }

    /**
     * @hide
     */
    public boolean isUpdatedSystemApp() {
        return (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    /** @hide */
    public boolean isInternal() {
        return (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) == 0;
    }

    /** @hide */
    public boolean isExternalAsec() {
        return TextUtils.isEmpty(volumeUuid)
                && (flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0;
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

    /** {@hide} */ public String getCodePath() { return scanSourceDir; }
    /** {@hide} */ public String getBaseCodePath() { return sourceDir; }
    /** {@hide} */ public String[] getSplitCodePaths() { return splitSourceDirs; }
    /** {@hide} */ public String getResourcePath() { return scanPublicSourceDir; }
    /** {@hide} */ public String getBaseResourcePath() { return publicSourceDir; }
    /** {@hide} */ public String[] getSplitResourcePaths() { return splitSourceDirs; }
}
