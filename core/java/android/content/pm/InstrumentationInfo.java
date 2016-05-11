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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Information you can retrieve about a particular piece of test
 * instrumentation.  This corresponds to information collected
 * from the AndroidManifest.xml's &lt;instrumentation&gt; tag.
 */
public class InstrumentationInfo extends PackageItemInfo implements Parcelable {
    /**
     * The name of the application package being instrumented.  From the
     * "package" attribute.
     */
    public String targetPackage;

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
     * Full path to a directory assigned to the package for its persistent data.
     */
    public String dataDir;

    /** {@hide} */
    public String deviceProtectedDataDir;
    /** {@hide} */
    public String credentialProtectedDataDir;

    /** {@hide} Full path to the directory containing primary ABI native libraries. */
    public String nativeLibraryDir;

    /** {@hide} Full path to the directory containing secondary ABI native libraries. */
    public String secondaryNativeLibraryDir;

    /**
     * Specifies whether or not this instrumentation will handle profiling.
     */
    public boolean handleProfiling;
    
    /** Specifies whether or not to run this instrumentation as a functional test */
    public boolean functionalTest;

    public InstrumentationInfo() {
    }

    public InstrumentationInfo(InstrumentationInfo orig) {
        super(orig);
        targetPackage = orig.targetPackage;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        splitSourceDirs = orig.splitSourceDirs;
        splitPublicSourceDirs = orig.splitPublicSourceDirs;
        dataDir = orig.dataDir;
        deviceProtectedDataDir = orig.deviceProtectedDataDir;
        credentialProtectedDataDir = orig.credentialProtectedDataDir;
        nativeLibraryDir = orig.nativeLibraryDir;
        secondaryNativeLibraryDir = orig.secondaryNativeLibraryDir;
        handleProfiling = orig.handleProfiling;
        functionalTest = orig.functionalTest;
    }

    public String toString() {
        return "InstrumentationInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + packageName + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeString(targetPackage);
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeString(dataDir);
        dest.writeString(deviceProtectedDataDir);
        dest.writeString(credentialProtectedDataDir);
        dest.writeString(nativeLibraryDir);
        dest.writeString(secondaryNativeLibraryDir);
        dest.writeInt((handleProfiling == false) ? 0 : 1);
        dest.writeInt((functionalTest == false) ? 0 : 1);
    }

    public static final Parcelable.Creator<InstrumentationInfo> CREATOR
            = new Parcelable.Creator<InstrumentationInfo>() {
        public InstrumentationInfo createFromParcel(Parcel source) {
            return new InstrumentationInfo(source);
        }
        public InstrumentationInfo[] newArray(int size) {
            return new InstrumentationInfo[size];
        }
    };

    private InstrumentationInfo(Parcel source) {
        super(source);
        targetPackage = source.readString();
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        splitSourceDirs = source.readStringArray();
        splitPublicSourceDirs = source.readStringArray();
        dataDir = source.readString();
        deviceProtectedDataDir = source.readString();
        credentialProtectedDataDir = source.readString();
        nativeLibraryDir = source.readString();
        secondaryNativeLibraryDir = source.readString();
        handleProfiling = source.readInt() != 0;
        functionalTest = source.readInt() != 0;
    }

    /** {@hide} */
    public void copyTo(ApplicationInfo ai) {
        ai.packageName = packageName;
        ai.sourceDir = sourceDir;
        ai.publicSourceDir = publicSourceDir;
        ai.splitSourceDirs = splitSourceDirs;
        ai.splitPublicSourceDirs = splitPublicSourceDirs;
        ai.dataDir = dataDir;
        ai.deviceProtectedDataDir = deviceProtectedDataDir;
        ai.credentialProtectedDataDir = credentialProtectedDataDir;
        ai.nativeLibraryDir = nativeLibraryDir;
        ai.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
    }
}
