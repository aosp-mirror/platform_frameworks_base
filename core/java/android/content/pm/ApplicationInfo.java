package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Printer;

import java.text.Collator;
import java.util.Comparator;

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
     * 
     * {@hide}
     */
    public String backupAgentName;
    
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
     * Value for {@link #flags}: this is set of the application has set
     * its android:targetSdkVersion to something >= the current SDK version.
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
     * android:smallScreens}.
     */
    public static final int FLAG_SUPPORTS_LARGE_SCREENS = 1<<11;
    
    /**
     * Value for {@link #flags}: this is false if the application has set
     * its android:allowBackup to false, true otherwise.
     * 
     * {@hide}
     */
    public static final int FLAG_ALLOW_BACKUP = 1<<12;
    
    /**
     * Indicates that the application supports any densities;
     * {@hide}
     */
    public static final int ANY_DENSITY = -1;
    private static final int[] ANY_DENSITIES_ARRAY = { ANY_DENSITY };

    /**
     * Flags associated with the application.  Any combination of
     * {@link #FLAG_SYSTEM}, {@link #FLAG_DEBUGGABLE}, {@link #FLAG_HAS_CODE},
     * {@link #FLAG_PERSISTENT}, {@link #FLAG_FACTORY_TEST}, and
     * {@link #FLAG_ALLOW_TASK_REPARENTING}
     * {@link #FLAG_ALLOW_CLEAR_USER_DATA}, {@link #FLAG_UPDATED_SYSTEM_APP},
     * {@link #FLAG_TEST_ONLY}, {@link #FLAG_SUPPORTS_SMALL_SCREENS},
     * {@link #FLAG_SUPPORTS_NORMAL_SCREENS},
     * {@link #FLAG_SUPPORTS_LARGE_SCREENS}.
     */
    public int flags = 0;
    
    /**
     * Full path to the location of this package.
     */
    public String sourceDir;

    /**
     * Full path to the location of the publicly available parts of this package (i.e. the resources
     * and manifest).  For non-forward-locked apps this will be the same as {@link #sourceDir).
     */
    public String publicSourceDir;
    
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
     * The kernel user-ID that has been assigned to this application;
     * currently this is not a unique ID (multiple applications can have
     * the same uid).
     */
    public int uid;
    
    /**
     * The list of densities in DPI that application supprots. This
     * field is only set if the {@link PackageManager#GET_SUPPORTS_DENSITIES} flag was
     * used when retrieving the structure.
     */
    public int[] supportsDensities;

    /**
     * The minimum SDK version this application targets.  It may run on earilier
     * versions, but it knows how to work with any new behavior added at this
     * version.  Will be {@link android.os.Build.VERSION_CODES#CUR_DEVELOPMENT}
     * if this is a development build and the app is targeting that.  You should
     * compare that this number is >= the SDK version number at which your
     * behavior was introduced.
     */
    public int targetSdkVersion;
    
    /**
     * When false, indicates that all components within this application are
     * considered disabled, regardless of their individually set enabled status.
     */
    public boolean enabled = true;

    public void dump(Printer pw, String prefix) {
        super.dumpFront(pw, prefix);
        pw.println(prefix + "className=" + className);
        pw.println(prefix + "permission=" + permission
                + " uid=" + uid);
        pw.println(prefix + "taskAffinity=" + taskAffinity);
        pw.println(prefix + "theme=0x" + Integer.toHexString(theme));
        pw.println(prefix + "flags=0x" + Integer.toHexString(flags)
                + " processName=" + processName);
        pw.println(prefix + "sourceDir=" + sourceDir);
        pw.println(prefix + "publicSourceDir=" + publicSourceDir);
        pw.println(prefix + "sharedLibraryFiles=" + sharedLibraryFiles);
        pw.println(prefix + "dataDir=" + dataDir);
        pw.println(prefix + "targetSdkVersion=" + targetSdkVersion);
        pw.println(prefix + "enabled=" + enabled);
        pw.println(prefix + "manageSpaceActivityName="+manageSpaceActivityName);
        pw.println(prefix + "description=0x"+Integer.toHexString(descriptionRes));
        pw.println(prefix + "supportsDensities=" + supportsDensities);
        super.dumpBack(pw, prefix);
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
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        sharedLibraryFiles = orig.sharedLibraryFiles;
        dataDir = orig.dataDir;
        uid = orig.uid;
        targetSdkVersion = orig.targetSdkVersion;
        enabled = orig.enabled;
        manageSpaceActivityName = orig.manageSpaceActivityName;
        descriptionRes = orig.descriptionRes;
        supportsDensities = orig.supportsDensities;
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
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(sharedLibraryFiles);
        dest.writeString(dataDir);
        dest.writeInt(uid);
        dest.writeInt(targetSdkVersion);
        dest.writeInt(enabled ? 1 : 0);
        dest.writeString(manageSpaceActivityName);
        dest.writeString(backupAgentName);
        dest.writeInt(descriptionRes);
        dest.writeIntArray(supportsDensities);
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
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        sharedLibraryFiles = source.readStringArray();
        dataDir = source.readString();
        uid = source.readInt();
        targetSdkVersion = source.readInt();
        enabled = source.readInt() != 0;
        manageSpaceActivityName = source.readString();
        backupAgentName = source.readString();
        descriptionRes = source.readInt();
        supportsDensities = source.createIntArray();
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
            CharSequence label = pm.getText(packageName, descriptionRes, null);
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
        flags |= FLAG_SUPPORTS_LARGE_SCREENS;
        supportsDensities = ANY_DENSITIES_ARRAY;
    }
}
