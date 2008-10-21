package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;

/**
 * implementation of PackageStats associated with a
 * application package.
 */
public class PackageStats implements Parcelable {
    public String packageName;
    public long codeSize;
    public long dataSize;
    public long cacheSize;
    
    public static final Parcelable.Creator<PackageStats> CREATOR
    = new Parcelable.Creator<PackageStats>() {
        public PackageStats createFromParcel(Parcel in) {
            return new PackageStats(in);
        }

        public PackageStats[] newArray(int size) {
            return new PackageStats[size];
        }
    };
    
    public String toString() {
        return "PackageStats{"
        + Integer.toHexString(System.identityHashCode(this))
        + " " + packageName + "}";
    }
    
    public PackageStats(String pkgName) {
        packageName = pkgName;
    }
    
    public PackageStats(Parcel source) {
        packageName = source.readString();
        codeSize = source.readLong();
        dataSize = source.readLong();
        cacheSize = source.readLong();
    }
    
    public PackageStats(PackageStats pStats) {
        packageName = pStats.packageName;
        codeSize = pStats.codeSize;
        dataSize = pStats.dataSize;
        cacheSize = pStats.cacheSize;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags){
        dest.writeString(packageName);
        dest.writeLong(codeSize);
        dest.writeLong(dataSize);
        dest.writeLong(cacheSize);
    }
}
