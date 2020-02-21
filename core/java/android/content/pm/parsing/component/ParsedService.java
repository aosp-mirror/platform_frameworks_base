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
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

/** @hide **/
public class ParsedService extends ParsedMainComponent {

    int foregroundServiceType;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String permission;

    public ParsedService(ParsedService other) {
        super(other);
        this.foregroundServiceType = other.foregroundServiceType;
        this.permission = other.permission;
    }

    public ParsedMainComponent setPermission(String permission) {
        // Empty string must be converted to null
        this.permission = TextUtils.isEmpty(permission) ? null : permission.intern();
        return this;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(128);
        sb.append("Service{");
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
        dest.writeInt(this.foregroundServiceType);
        sForInternedString.parcel(this.permission, dest, flags);
    }

    public ParsedService() {
    }

    protected ParsedService(Parcel in) {
        super(in);
        this.foregroundServiceType = in.readInt();
        this.permission = sForInternedString.unparcel(in);
    }

    public static final Parcelable.Creator<ParsedService> CREATOR = new Creator<ParsedService>() {
        @Override
        public ParsedService createFromParcel(Parcel source) {
            return new ParsedService(source);
        }

        @Override
        public ParsedService[] newArray(int size) {
            return new ParsedService[size];
        }
    };

    public int getForegroundServiceType() {
        return foregroundServiceType;
    }

    @Nullable
    public String getPermission() {
        return permission;
    }
}
