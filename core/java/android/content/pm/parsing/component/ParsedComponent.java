/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.parsing.component;

import static android.content.pm.parsing.ParsingPackageImpl.sForInternedString;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** @hide */
public abstract class ParsedComponent implements Parcelable {

    private static ParsedIntentInfo.ListParceler sForIntentInfos = Parcelling.Cache.getOrCreate(
            ParsedIntentInfo.ListParceler.class);

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private String name;
    int icon;
    int labelRes;
    @Nullable
    CharSequence nonLocalizedLabel;
    int logo;
    int banner;
    int descriptionRes;

    // TODO(b/135203078): Replace flags with individual booleans, scoped by subclass
    int flags;

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private String packageName;

    @Nullable
    @DataClass.PluralOf("intent")
    @DataClass.ParcelWith(ParsedIntentInfo.ListParceler.class)
    private List<ParsedIntentInfo> intents;

    private ComponentName componentName;

    @Nullable
    protected Bundle metaData;

    ParsedComponent() {

    }

    @SuppressWarnings("IncompleteCopyConstructor")
    public ParsedComponent(ParsedComponent other) {
        this.metaData = other.metaData;
        this.name = other.name;
        this.icon = other.getIcon();
        this.labelRes = other.getLabelRes();
        this.nonLocalizedLabel = other.getNonLocalizedLabel();
        this.logo = other.getLogo();
        this.banner = other.getBanner();

        this.descriptionRes = other.getDescriptionRes();

        this.flags = other.getFlags();

        this.setPackageName(other.packageName);
        this.intents = new ArrayList<>(other.getIntents());
    }

    public void addIntent(ParsedIntentInfo intent) {
        this.intents = CollectionUtils.add(this.intents, intent);
    }

    @NonNull
    public List<ParsedIntentInfo> getIntents() {
        return intents != null ? intents : Collections.emptyList();
    }

    public ParsedComponent setName(String name) {
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

    @NonNull
    public ComponentName getComponentName() {
        if (componentName == null) {
            componentName = new ComponentName(getPackageName(), getName());
        }
        return componentName;
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
        sForIntentInfos.parcel(this.getIntents(), dest, flags);
        dest.writeBundle(this.metaData);
    }

    protected ParsedComponent(Parcel in) {
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
        this.intents = sForIntentInfos.unparcel(in);
        this.metaData = in.readBundle(boot);
    }

    @NonNull
    public String getName() {
        return name;
    }

    public int getIcon() {
        return icon;
    }

    public int getLabelRes() {
        return labelRes;
    }

    @Nullable
    public CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    public int getLogo() {
        return logo;
    }

    public int getBanner() {
        return banner;
    }

    public int getDescriptionRes() {
        return descriptionRes;
    }

    public int getFlags() {
        return flags;
    }

    @NonNull
    public String getPackageName() {
        return packageName;
    }

    @Nullable
    public Bundle getMetaData() {
        return metaData;
    }
}
