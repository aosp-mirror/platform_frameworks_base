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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

/** @hide */
public class ParsedMainComponent extends ParsedComponent {

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String processName;
    boolean directBootAware;
    boolean enabled = true;
    boolean exported;
    int order;

    @Nullable
    String splitName;

    public ParsedMainComponent() {
    }

    public ParsedMainComponent(ParsedMainComponent other) {
        super(other);
        this.processName = other.processName;
        this.directBootAware = other.directBootAware;
        this.enabled = other.enabled;
        this.exported = other.exported;
        this.order = other.order;
        this.splitName = other.splitName;
    }

    public ParsedMainComponent setProcessName(String processName) {
        this.processName = TextUtils.safeIntern(processName);
        return this;
    }

    public ParsedMainComponent setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /**
     * A main component's name is a class name. This makes code slightly more readable.
     */
    public String getClassName() {
        return getName();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        sForInternedString.parcel(this.processName, dest, flags);
        dest.writeBoolean(this.directBootAware);
        dest.writeBoolean(this.enabled);
        dest.writeBoolean(this.exported);
        dest.writeInt(this.order);
        dest.writeString(this.splitName);
    }

    protected ParsedMainComponent(Parcel in) {
        super(in);
        this.processName = sForInternedString.unparcel(in);
        this.directBootAware = in.readBoolean();
        this.enabled = in.readBoolean();
        this.exported = in.readBoolean();
        this.order = in.readInt();
        this.splitName = in.readString();
    }

    public static final Parcelable.Creator<ParsedMainComponent> CREATOR =
            new Parcelable.Creator<ParsedMainComponent>() {
                @Override
                public ParsedMainComponent createFromParcel(Parcel source) {
                    return new ParsedMainComponent(source);
                }

                @Override
                public ParsedMainComponent[] newArray(int size) {
                    return new ParsedMainComponent[size];
                }
            };

    @Nullable
    public String getProcessName() {
        return processName;
    }

    public boolean isDirectBootAware() {
        return directBootAware;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isExported() {
        return exported;
    }

    public int getOrder() {
        return order;
    }

    @Nullable
    public String getSplitName() {
        return splitName;
    }

    public ParsedMainComponent setDirectBootAware(boolean value) {
        directBootAware = value;
        return this;
    }

    public ParsedMainComponent setExported(boolean value) {
        exported = value;
        return this;
    }

    public ParsedMainComponent setSplitName(@Nullable String value) {
        splitName = value;
        return this;
    }
}
