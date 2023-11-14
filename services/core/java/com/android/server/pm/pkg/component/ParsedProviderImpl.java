/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm.pkg.component;

import static com.android.server.pm.parsing.pkg.PackageImpl.sForInternedString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PathPermission;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.pkg.component.ParsedProvider;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @hide
 **/
@DataClass(genSetters = true, genGetters = true, genParcelable = false, genBuilder = false)
@DataClass.Suppress({"setUriPermissionPatterns", "setPathPermissions"})
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class ParsedProviderImpl extends ParsedMainComponentImpl implements ParsedProvider,
        Parcelable {

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String authority;
    private boolean syncable;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String readPermission;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String writePermission;
    private boolean grantUriPermissions;
    private boolean forceUriPermissions;
    private boolean multiProcess;
    private int initOrder;
    @NonNull
    private List<PatternMatcher> uriPermissionPatterns = Collections.emptyList();
    @NonNull
    private List<PathPermission> pathPermissions = Collections.emptyList();

    public ParsedProviderImpl(ParsedProvider other) {
        super(other);

        this.authority = other.getAuthority();
        this.syncable = other.isSyncable();
        this.readPermission = other.getReadPermission();
        this.writePermission = other.getWritePermission();
        this.grantUriPermissions = other.isGrantUriPermissions();
        this.forceUriPermissions = other.isForceUriPermissions();
        this.multiProcess = other.isMultiProcess();
        this.initOrder = other.getInitOrder();
        this.uriPermissionPatterns = new ArrayList<>(other.getUriPermissionPatterns());
        this.pathPermissions = new ArrayList<>(other.getPathPermissions());
    }

    public ParsedProviderImpl setReadPermission(String readPermission) {
        // Empty string must be converted to null
        this.readPermission = TextUtils.isEmpty(readPermission)
                ? null : readPermission.intern();
        return this;
    }

    public ParsedProviderImpl setWritePermission(String writePermission) {
        // Empty string must be converted to null
        this.writePermission = TextUtils.isEmpty(writePermission)
                ? null : writePermission.intern();
        return this;
    }

    @NonNull
    public ParsedProviderImpl addUriPermissionPattern(@NonNull PatternMatcher value) {
        uriPermissionPatterns = CollectionUtils.add(uriPermissionPatterns, value);
        return this;
    }

    @NonNull
    public ParsedProviderImpl addPathPermission(@NonNull PathPermission value) {
        pathPermissions = CollectionUtils.add(pathPermissions, value);
        return this;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Provider{");
        sb.append(Integer.toHexString(System.identityHashCode(this)));
        sb.append(' ');
        ComponentName.appendShortString(sb, getPackageName(), getName());
        sb.append('}');
        return sb.toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(this.authority);
        dest.writeBoolean(this.syncable);
        sForInternedString.parcel(this.readPermission, dest, flags);
        sForInternedString.parcel(this.writePermission, dest, flags);
        dest.writeBoolean(this.grantUriPermissions);
        dest.writeBoolean(this.forceUriPermissions);
        dest.writeBoolean(this.multiProcess);
        dest.writeInt(this.initOrder);
        dest.writeTypedList(this.uriPermissionPatterns, flags);
        dest.writeTypedList(this.pathPermissions, flags);
    }

    public ParsedProviderImpl() {
    }

    protected ParsedProviderImpl(Parcel in) {
        super(in);
        this.authority = in.readString();
        this.syncable = in.readBoolean();
        this.readPermission = sForInternedString.unparcel(in);
        this.writePermission = sForInternedString.unparcel(in);
        this.grantUriPermissions = in.readBoolean();
        this.forceUriPermissions = in.readBoolean();
        this.multiProcess = in.readBoolean();
        this.initOrder = in.readInt();
        this.uriPermissionPatterns = in.createTypedArrayList(PatternMatcher.CREATOR);
        this.pathPermissions = in.createTypedArrayList(PathPermission.CREATOR);
    }

    @NonNull
    public static final Parcelable.Creator<ParsedProviderImpl> CREATOR =
            new Parcelable.Creator<ParsedProviderImpl>() {
                @Override
                public ParsedProviderImpl createFromParcel(Parcel source) {
                    return new ParsedProviderImpl(source);
                }

                @Override
                public ParsedProviderImpl[] newArray(int size) {
                    return new ParsedProviderImpl[size];
                }
            };



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/component/ParsedProviderImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public ParsedProviderImpl(
            @Nullable String authority,
            boolean syncable,
            @Nullable String readPermission,
            @Nullable String writePermission,
            boolean grantUriPermissions,
            boolean forceUriPermissions,
            boolean multiProcess,
            int initOrder,
            @NonNull List<PatternMatcher> uriPermissionPatterns,
            @NonNull List<PathPermission> pathPermissions) {
        this.authority = authority;
        this.syncable = syncable;
        this.readPermission = readPermission;
        this.writePermission = writePermission;
        this.grantUriPermissions = grantUriPermissions;
        this.forceUriPermissions = forceUriPermissions;
        this.multiProcess = multiProcess;
        this.initOrder = initOrder;
        this.uriPermissionPatterns = uriPermissionPatterns;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, uriPermissionPatterns);
        this.pathPermissions = pathPermissions;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, pathPermissions);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public @Nullable String getAuthority() {
        return authority;
    }

    @DataClass.Generated.Member
    public boolean isSyncable() {
        return syncable;
    }

    @DataClass.Generated.Member
    public @Nullable String getReadPermission() {
        return readPermission;
    }

    @DataClass.Generated.Member
    public @Nullable String getWritePermission() {
        return writePermission;
    }

    @DataClass.Generated.Member
    public boolean isGrantUriPermissions() {
        return grantUriPermissions;
    }

    @DataClass.Generated.Member
    public boolean isForceUriPermissions() {
        return forceUriPermissions;
    }

    @DataClass.Generated.Member
    public boolean isMultiProcess() {
        return multiProcess;
    }

    @DataClass.Generated.Member
    public int getInitOrder() {
        return initOrder;
    }

    @DataClass.Generated.Member
    public @NonNull List<PatternMatcher> getUriPermissionPatterns() {
        return uriPermissionPatterns;
    }

    @DataClass.Generated.Member
    public @NonNull List<PathPermission> getPathPermissions() {
        return pathPermissions;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setAuthority(@NonNull String value) {
        authority = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setSyncable( boolean value) {
        syncable = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setGrantUriPermissions( boolean value) {
        grantUriPermissions = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setForceUriPermissions( boolean value) {
        forceUriPermissions = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setMultiProcess( boolean value) {
        multiProcess = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedProviderImpl setInitOrder( int value) {
        initOrder = value;
        return this;
    }

    @DataClass.Generated(
            time = 1642560323360L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/component/ParsedProviderImpl.java",
            inputSignatures = "private @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String authority\nprivate  boolean syncable\nprivate @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String readPermission\nprivate @android.annotation.Nullable @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String writePermission\nprivate  boolean grantUriPermissions\nprivate  boolean forceUriPermissions\nprivate  boolean multiProcess\nprivate  int initOrder\nprivate @android.annotation.NonNull java.util.List<android.os.PatternMatcher> uriPermissionPatterns\nprivate @android.annotation.NonNull java.util.List<android.content.pm.PathPermission> pathPermissions\npublic static final @android.annotation.NonNull android.os.Parcelable.Creator<com.android.server.pm.pkg.component.ParsedProviderImpl> CREATOR\npublic  com.android.server.pm.pkg.component.ParsedProviderImpl setReadPermission(java.lang.String)\npublic  com.android.server.pm.pkg.component.ParsedProviderImpl setWritePermission(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.component.ParsedProviderImpl addUriPermissionPattern(android.os.PatternMatcher)\npublic @android.annotation.NonNull com.android.server.pm.pkg.component.ParsedProviderImpl addPathPermission(android.content.pm.PathPermission)\npublic  java.lang.String toString()\npublic @java.lang.Override int describeContents()\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\nclass ParsedProviderImpl extends com.android.server.pm.pkg.component.ParsedMainComponentImpl implements [com.android.internal.pm.pkg.component.ParsedProvider, android.os.Parcelable]\n@com.android.internal.util.DataClass(genSetters=true, genGetters=true, genParcelable=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
