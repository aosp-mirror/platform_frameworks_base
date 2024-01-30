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

package com.android.internal.pm.pkg.component;

import static com.android.internal.pm.parsing.pkg.PackageImpl.sForInternedString;

import static java.util.Collections.emptyMap;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.PackageManager.Property;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @hide
 */
@DataClass(genGetters = true, genSetters = true, genConstructor = false, genBuilder = false,
        genParcelable = false)
@DataClass.Suppress({"setComponentName", "setProperties", "setIntents"})
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public abstract class ParsedComponentImpl implements ParsedComponent, Parcelable {

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private String name;
    private int icon;
    private int labelRes;
    @Nullable
    private CharSequence nonLocalizedLabel;
    private int logo;
    private int banner;
    private int descriptionRes;

    // TODO(b/135203078): Replace flags with individual booleans, scoped by subclass
    private int flags;

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private String packageName;

    @NonNull
    @DataClass.PluralOf("intent")
    private List<ParsedIntentInfoImpl> intents = Collections.emptyList();

    @Nullable
    private ComponentName componentName;

    @Nullable
    private Bundle metaData;

    @NonNull
    private Map<String, Property> mProperties = emptyMap();

    public ParsedComponentImpl() {

    }

    protected ParsedComponentImpl(ParsedComponent other) {
        this.metaData = other.getMetaData();
        this.name = other.getName();
        this.icon = other.getIcon();
        this.labelRes = other.getLabelRes();
        this.nonLocalizedLabel = other.getNonLocalizedLabel();
        this.logo = other.getLogo();
        this.banner = other.getBanner();
        this.descriptionRes = other.getDescriptionRes();
        this.flags = other.getFlags();
        this.packageName = other.getPackageName();
        this.componentName = other.getComponentName();
        this.intents = new ArrayList<>(((ParsedComponentImpl) other).intents);
        this.mProperties = new ArrayMap<>();
        this.mProperties.putAll(other.getProperties());
    }

    public void addIntent(ParsedIntentInfoImpl intent) {
        this.intents = CollectionUtils.add(this.intents, intent);
    }

    /**
     * Add a property to the component
     */
    public void addProperty(@NonNull Property property) {
        this.mProperties = CollectionUtils.add(this.mProperties, property.getName(), property);
    }

    public ParsedComponentImpl setName(String name) {
        this.name = TextUtils.safeIntern(name);
        return this;
    }

    @CallSuper
    public void setPackageName(@NonNull String packageName) {
        this.packageName = TextUtils.safeIntern(packageName);
        //noinspection ConstantConditions
        this.componentName = null;

        // Note: this method does not edit name (which can point to a class), because this package
        // name change is not changing the package in code, but the identifier used by the system.
    }

    @Override
    @NonNull
    public ComponentName getComponentName() {
        if (componentName == null) {
            componentName = new ComponentName(getPackageName(), getName());
        }
        return componentName;
    }

    @NonNull
    @Override
    public Bundle getMetaData() {
        return metaData == null ? Bundle.EMPTY : metaData;
    }

    @NonNull
    @Override
    public List<ParsedIntentInfo> getIntents() {
        return new ArrayList<>(intents);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.name);
        dest.writeInt(this.getIcon());
        dest.writeInt(this.getLabelRes());
        dest.writeCharSequence(this.getNonLocalizedLabel());
        dest.writeInt(this.getLogo());
        dest.writeInt(this.getBanner());
        dest.writeInt(this.getDescriptionRes());
        dest.writeInt(this.getFlags());
        sForInternedString.parcel(this.packageName, dest, flags);
        dest.writeTypedList(this.intents);
        dest.writeBundle(this.metaData);
        dest.writeMap(this.mProperties);
    }

    protected ParsedComponentImpl(Parcel in) {
        // We use the boot classloader for all classes that we load.
        final ClassLoader boot = Object.class.getClassLoader();
        //noinspection ConstantConditions
        this.name = in.readString();
        this.icon = in.readInt();
        this.labelRes = in.readInt();
        this.nonLocalizedLabel = in.readCharSequence();
        this.logo = in.readInt();
        this.banner = in.readInt();
        this.descriptionRes = in.readInt();
        this.flags = in.readInt();
        //noinspection ConstantConditions
        this.packageName = sForInternedString.unparcel(in);
        this.intents = ParsingUtils.createTypedInterfaceList(in, ParsedIntentInfoImpl.CREATOR);
        this.metaData = in.readBundle(boot);
        this.mProperties = in.readHashMap(boot);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedComponentImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public @NonNull String getName() {
        return name;
    }

    @DataClass.Generated.Member
    public int getIcon() {
        return icon;
    }

    @DataClass.Generated.Member
    public int getLabelRes() {
        return labelRes;
    }

    @DataClass.Generated.Member
    public @Nullable CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    @DataClass.Generated.Member
    public int getLogo() {
        return logo;
    }

    @DataClass.Generated.Member
    public int getBanner() {
        return banner;
    }

    @DataClass.Generated.Member
    public int getDescriptionRes() {
        return descriptionRes;
    }

    @DataClass.Generated.Member
    public int getFlags() {
        return flags;
    }

    @DataClass.Generated.Member
    public @NonNull String getPackageName() {
        return packageName;
    }

    @DataClass.Generated.Member
    public @NonNull Map<String,Property> getProperties() {
        return mProperties;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setIcon( int value) {
        icon = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setLabelRes( int value) {
        labelRes = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setNonLocalizedLabel(@NonNull CharSequence value) {
        nonLocalizedLabel = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setLogo( int value) {
        logo = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setBanner( int value) {
        banner = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setDescriptionRes( int value) {
        descriptionRes = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setFlags( int value) {
        flags = value;
        return this;
    }

    @DataClass.Generated.Member
    public @NonNull ParsedComponentImpl setMetaData(@NonNull Bundle value) {
        metaData = value;
        return this;
    }

    @DataClass.Generated(
            time = 1701445673589L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/com/android/internal/pm/pkg/component/ParsedComponentImpl.java",
            inputSignatures = "private @android.annotation.NonNull @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String name\nprivate  int icon\nprivate  int labelRes\nprivate @android.annotation.Nullable java.lang.CharSequence nonLocalizedLabel\nprivate  int logo\nprivate  int banner\nprivate  int descriptionRes\nprivate  int flags\nprivate @android.annotation.NonNull @com.android.internal.util.DataClass.ParcelWith(com.android.internal.util.Parcelling.BuiltIn.ForInternedString.class) java.lang.String packageName\nprivate @android.annotation.NonNull @com.android.internal.util.DataClass.PluralOf(\"intent\") java.util.List<com.android.internal.pm.pkg.component.ParsedIntentInfoImpl> intents\nprivate @android.annotation.Nullable android.content.ComponentName componentName\nprivate @android.annotation.Nullable android.os.Bundle metaData\nprivate @android.annotation.NonNull java.util.Map<java.lang.String,android.content.pm.PackageManager.Property> mProperties\npublic  void addIntent(com.android.internal.pm.pkg.component.ParsedIntentInfoImpl)\npublic  void addProperty(android.content.pm.PackageManager.Property)\npublic  com.android.internal.pm.pkg.component.ParsedComponentImpl setName(java.lang.String)\npublic @android.annotation.CallSuper void setPackageName(java.lang.String)\npublic @java.lang.Override @android.annotation.NonNull android.content.ComponentName getComponentName()\npublic @android.annotation.NonNull @java.lang.Override android.os.Bundle getMetaData()\npublic @android.annotation.NonNull @java.lang.Override java.util.List<com.android.internal.pm.pkg.component.ParsedIntentInfo> getIntents()\npublic @java.lang.Override int describeContents()\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\nclass ParsedComponentImpl extends java.lang.Object implements [com.android.internal.pm.pkg.component.ParsedComponent, android.os.Parcelable]\n@com.android.internal.util.DataClass(genGetters=true, genSetters=true, genConstructor=false, genBuilder=false, genParcelable=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
