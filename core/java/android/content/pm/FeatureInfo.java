package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.os.Parcelable.Creator;

/**
 * A single feature that can be requested by an application. This corresponds
 * to information collected from the
 * AndroidManifest.xml's &lt;uses-feature&gt; tag.
 */
public class FeatureInfo implements Parcelable {
    /**
     * The name of this feature, for example "android.hardware.camera".  If
     * this is null, then this is an OpenGL ES version feature as described
     * in {@link #reqGlEsVersion}.
     */
    public String name;
    
    /**
     * Default value for {@link #reqGlEsVersion};
     */
    public static final int GL_ES_VERSION_UNDEFINED = 0;
    
    /**
     * The GLES version used by an application. The upper order 16 bits represent the
     * major version and the lower order 16 bits the minor version.  Only valid
     * if {@link #name} is null.
     */
    public int reqGlEsVersion;

    /**
     * Set on {@link #flags} if this feature has been required by the application.
     */
    public static final int FLAG_REQUIRED = 0x0001;
    
    /**
     * Additional flags.  May be zero or more of {@link #FLAG_REQUIRED}.
     */
    public int flags;
    
    public FeatureInfo() {
    }

    public FeatureInfo(FeatureInfo orig) {
        name = orig.name;
        reqGlEsVersion = orig.reqGlEsVersion;
        flags = orig.flags;
    }

    public String toString() {
        if (name != null) {
            return "FeatureInfo{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " " + name + " fl=0x" + Integer.toHexString(flags) + "}";
        } else {
            return "FeatureInfo{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " glEsVers=" + getGlEsVersion()
                    + " fl=0x" + Integer.toHexString(flags) + "}";
        }
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeString(name);
        dest.writeInt(reqGlEsVersion);
        dest.writeInt(flags);
    }

    public static final Creator<FeatureInfo> CREATOR =
        new Creator<FeatureInfo>() {
        public FeatureInfo createFromParcel(Parcel source) {
            return new FeatureInfo(source);
        }
        public FeatureInfo[] newArray(int size) {
            return new FeatureInfo[size];
        }
    };

    private FeatureInfo(Parcel source) {
        name = source.readString();
        reqGlEsVersion = source.readInt();
        flags = source.readInt();
    }

    /**
     * This method extracts the major and minor version of reqGLEsVersion attribute
     * and returns it as a string. Say reqGlEsVersion value of 0x00010002 is returned
     * as 1.2
     * @return String representation of the reqGlEsVersion attribute
     */
    public String getGlEsVersion() {
        int major = ((reqGlEsVersion & 0xffff0000) >> 16);
        int minor = reqGlEsVersion & 0x0000ffff;
        return String.valueOf(major)+"."+String.valueOf(minor);
    }
}
