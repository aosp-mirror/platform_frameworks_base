/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.os.PatternMatcher;
import android.util.Printer;

/**
 * Holds information about a specific
 * {@link android.content.ContentProvider content provider}. This is returned by
 * {@link android.content.pm.PackageManager#resolveContentProvider(java.lang.String, int)
 * PackageManager.resolveContentProvider()}.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class ProviderInfo extends ComponentInfo
        implements Parcelable {
    
    /** The name provider is published under content:// */
    public String authority = null;
    
    /** Optional permission required for read-only access this content
     * provider. */
    public String readPermission = null;
    
    /** Optional permission required for read/write access this content
     * provider. */
    public String writePermission = null;
    
    /** If true, additional permissions to specific Uris in this content
     * provider can be granted, as per the
     * {@link android.R.styleable#AndroidManifestProvider_grantUriPermissions
     * grantUriPermissions} attribute.
     */
    public boolean grantUriPermissions = false;

    /** If true, always apply URI permission grants, as per the
     * {@link android.R.styleable#AndroidManifestProvider_forceUriPermissions
     * forceUriPermissions} attribute.
     */
    public boolean forceUriPermissions = false;

    /**
     * If non-null, these are the patterns that are allowed for granting URI
     * permissions.  Any URI that does not match one of these patterns will not
     * allowed to be granted.  If null, all URIs are allowed.  The
     * {@link PackageManager#GET_URI_PERMISSION_PATTERNS
     * PackageManager.GET_URI_PERMISSION_PATTERNS} flag must be specified for
     * this field to be filled in.
     */
    public PatternMatcher[] uriPermissionPatterns = null;
    
    /**
     * If non-null, these are path-specific permissions that are allowed for
     * accessing the provider.  Any permissions listed here will allow a
     * holding client to access the provider, and the provider will check
     * the URI it provides when making calls against the patterns here.
     */
    public PathPermission[] pathPermissions = null;
    
    /** If true, this content provider allows multiple instances of itself
     *  to run in different process.  If false, a single instances is always
     *  run in {@link #processName}. */
    public boolean multiprocess = false;
    
    /** Used to control initialization order of single-process providers
     *  running in the same process.  Higher goes first. */
    public int initOrder = 0;

    /**
     * Bit in {@link #flags} indicating if the provider is visible to ephemeral applications.
     * @hide
     */
    public static final int FLAG_VISIBLE_TO_INSTANT_APP = 0x100000;

    /**
     * Bit in {@link #flags}: If set, a single instance of the provider will
     * run for all users on the device.  Set from the
     * {@link android.R.attr#singleUser} attribute.
     */
    public static final int FLAG_SINGLE_USER = 0x40000000;

    /**
     * Options that have been set in the provider declaration in the
     * manifest.
     * These include: {@link #FLAG_SINGLE_USER}.
     */
    public int flags = 0;

    /**
     * Whether or not this provider is syncable.
     * @deprecated This flag is now being ignored. The current way to make a provider
     * syncable is to provide a SyncAdapter service for a given provider/account type. 
     */
    @Deprecated
    public boolean isSyncable = false;

    public ProviderInfo() {
    }

    public ProviderInfo(ProviderInfo orig) {
        super(orig);
        authority = orig.authority;
        readPermission = orig.readPermission;
        writePermission = orig.writePermission;
        grantUriPermissions = orig.grantUriPermissions;
        forceUriPermissions = orig.forceUriPermissions;
        uriPermissionPatterns = orig.uriPermissionPatterns;
        pathPermissions = orig.pathPermissions;
        multiprocess = orig.multiprocess;
        initOrder = orig.initOrder;
        flags = orig.flags;
        isSyncable = orig.isSyncable;
    }

    public void dump(Printer pw, String prefix) {
        dump(pw, prefix, DUMP_FLAG_ALL);
    }

    /** @hide */
    public void dump(Printer pw, String prefix, int dumpFlags) {
        super.dumpFront(pw, prefix);
        pw.println(prefix + "authority=" + authority);
        pw.println(prefix + "flags=0x" + Integer.toHexString(flags));
        super.dumpBack(pw, prefix, dumpFlags);
    }

    public int describeContents() {
        return 0;
    }

    @Override public void writeToParcel(Parcel out, int parcelableFlags) {
        super.writeToParcel(out, parcelableFlags);
        out.writeString8(authority);
        out.writeString8(readPermission);
        out.writeString8(writePermission);
        out.writeInt(grantUriPermissions ? 1 : 0);
        out.writeInt(forceUriPermissions ? 1 : 0);
        out.writeTypedArray(uriPermissionPatterns, parcelableFlags);
        out.writeTypedArray(pathPermissions, parcelableFlags);
        out.writeInt(multiprocess ? 1 : 0);
        out.writeInt(initOrder);
        out.writeInt(flags);
        out.writeInt(isSyncable ? 1 : 0);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ProviderInfo> CREATOR
            = new Parcelable.Creator<ProviderInfo>() {
        public ProviderInfo createFromParcel(Parcel in) {
            return new ProviderInfo(in);
        }

        public ProviderInfo[] newArray(int size) {
            return new ProviderInfo[size];
        }
    };

    public String toString() {
        return "ContentProviderInfo{name=" + authority + " className=" + name + "}";
    }

    private ProviderInfo(Parcel in) {
        super(in);
        authority = in.readString8();
        readPermission = in.readString8();
        writePermission = in.readString8();
        grantUriPermissions = in.readInt() != 0;
        forceUriPermissions = in.readInt() != 0;
        uriPermissionPatterns = in.createTypedArray(PatternMatcher.CREATOR);
        pathPermissions = in.createTypedArray(PathPermission.CREATOR);
        multiprocess = in.readInt() != 0;
        initOrder = in.readInt();
        flags = in.readInt();
        isSyncable = in.readInt() != 0;
    }
}
