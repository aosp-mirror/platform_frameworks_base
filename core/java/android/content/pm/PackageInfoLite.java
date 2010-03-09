package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Basic information about a package as specified in its manifest.
 * Utility class used in PackageManager methods
 * @hide
 */
public class PackageInfoLite implements Parcelable {
    /**
     * The name of this package.  From the &lt;manifest&gt; tag's "name"
     * attribute.
     */
    public String packageName;

    /**
     * Specifies the recommended install location. Can be one of
     * {@link #PackageHelper.RECOMMEND_INSTALL_INTERNAL} to install on internal storage
     * {@link #PackageHelper.RECOMMEND_INSTALL_EXTERNAL} to install on external media
     * {@link PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE} for storage errors
     * {@link PackageHelper.RECOMMEND_FAILED_INVALID_APK} for parse errors.
     */
    public int recommendedInstallLocation;
    public int installLocation;

    public PackageInfoLite() {
    }

    public String toString() {
        return "PackageInfoLite{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(packageName);
        dest.writeInt(recommendedInstallLocation);
        dest.writeInt(installLocation);
    }

    public static final Parcelable.Creator<PackageInfoLite> CREATOR
            = new Parcelable.Creator<PackageInfoLite>() {
        public PackageInfoLite createFromParcel(Parcel source) {
            return new PackageInfoLite(source);
        }

        public PackageInfoLite[] newArray(int size) {
            return new PackageInfoLite[size];
        }
    };

    private PackageInfoLite(Parcel source) {
        packageName = source.readString();
        recommendedInstallLocation = source.readInt();
        installLocation = source.readInt();
    }
}