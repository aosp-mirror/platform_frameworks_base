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
import android.util.SparseArray;

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
     * Names of the process(es) this instrumentation will run in.  If not specified, only
     * runs in the main process of the targetPackage.  Can either be a comma-separated list
     * of process names or '*' for any process that launches to run targetPackage code.
     */
    public String targetProcesses;

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
     * @hide
     */
    public SparseArray<int[]> splitDependencies;

    /**
     * Full path to a directory assigned to the package for its persistent data.
     */
    public String dataDir;

    /** {@hide} */
    public String deviceProtectedDataDir;
    /** {@hide} */
    public String credentialProtectedDataDir;

    /** {@hide} */
    public String primaryCpuAbi;

    /** {@hide} */
    public String secondaryCpuAbi;

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
        targetProcesses = orig.targetProcesses;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        splitNames = orig.splitNames;
        splitSourceDirs = orig.splitSourceDirs;
        splitPublicSourceDirs = orig.splitPublicSourceDirs;
        splitDependencies = orig.splitDependencies;
        dataDir = orig.dataDir;
        deviceProtectedDataDir = orig.deviceProtectedDataDir;
        credentialProtectedDataDir = orig.credentialProtectedDataDir;
        primaryCpuAbi = orig.primaryCpuAbi;
        secondaryCpuAbi = orig.secondaryCpuAbi;
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
        dest.writeString(targetProcesses);
        dest.writeString(sourceDir);
        dest.writeString(publicSourceDir);
        dest.writeStringArray(splitNames);
        dest.writeStringArray(splitSourceDirs);
        dest.writeStringArray(splitPublicSourceDirs);
        dest.writeSparseArray((SparseArray) splitDependencies);
        dest.writeString(dataDir);
        dest.writeString(deviceProtectedDataDir);
        dest.writeString(credentialProtectedDataDir);
        dest.writeString(primaryCpuAbi);
        dest.writeString(secondaryCpuAbi);
        dest.writeString(nativeLibraryDir);
        dest.writeString(secondaryNativeLibraryDir);
        dest.writeInt((handleProfiling == false) ? 0 : 1);
        dest.writeInt((functionalTest == false) ? 0 : 1);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<InstrumentationInfo> CREATOR
            = new Parcelable.Creator<InstrumentationInfo>() {
        public InstrumentationInfo createFromParcel(Parcel source) {
            return new InstrumentationInfo(source);
        }
        public InstrumentationInfo[] newArray(int size) {
            return new InstrumentationInfo[size];
        }
    };

    @SuppressWarnings("unchecked")
    private InstrumentationInfo(Parcel source) {
        super(source);
        targetPackage = source.readString();
        targetProcesses = source.readString();
        sourceDir = source.readString();
        publicSourceDir = source.readString();
        splitNames = source.readStringArray();
        splitSourceDirs = source.readStringArray();
        splitPublicSourceDirs = source.readStringArray();
        splitDependencies = source.readSparseArray(null);
        dataDir = source.readString();
        deviceProtectedDataDir = source.readString();
        credentialProtectedDataDir = source.readString();
        primaryCpuAbi = source.readString();
        secondaryCpuAbi = source.readString();
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
        ai.splitNames = splitNames;
        ai.splitSourceDirs = splitSourceDirs;
        ai.splitPublicSourceDirs = splitPublicSourceDirs;
        ai.splitDependencies = splitDependencies;
        ai.dataDir = dataDir;
        ai.deviceProtectedDataDir = deviceProtectedDataDir;
        ai.credentialProtectedDataDir = credentialProtectedDataDir;
        ai.primaryCpuAbi = primaryCpuAbi;
        ai.secondaryCpuAbi = secondaryCpuAbi;
        ai.nativeLibraryDir = nativeLibraryDir;
        ai.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
    }
}
