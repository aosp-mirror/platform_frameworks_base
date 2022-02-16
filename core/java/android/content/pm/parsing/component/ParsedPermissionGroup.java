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

import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.DataClass;

/** @hide */
public class ParsedPermissionGroup extends ParsedComponent {

    int requestDetailResourceId;
    int backgroundRequestResourceId;
    int backgroundRequestDetailResourceId;
    int requestRes;
    int priority;

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public String toString() {
        return "PermissionGroup{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getName() + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(this.requestDetailResourceId);
        dest.writeInt(this.backgroundRequestResourceId);
        dest.writeInt(this.backgroundRequestDetailResourceId);
        dest.writeInt(this.requestRes);
        dest.writeInt(this.priority);
    }

    public ParsedPermissionGroup() {
    }

    protected ParsedPermissionGroup(Parcel in) {
        super(in);
        this.requestDetailResourceId = in.readInt();
        this.backgroundRequestResourceId = in.readInt();
        this.backgroundRequestDetailResourceId = in.readInt();
        this.requestRes = in.readInt();
        this.priority = in.readInt();
    }

    public static final Parcelable.Creator<ParsedPermissionGroup> CREATOR =
            new Parcelable.Creator<ParsedPermissionGroup>() {
                @Override
                public ParsedPermissionGroup createFromParcel(Parcel source) {
                    return new ParsedPermissionGroup(source);
                }

                @Override
                public ParsedPermissionGroup[] newArray(int size) {
                    return new ParsedPermissionGroup[size];
                }
            };

    public int getRequestDetailResourceId() {
        return requestDetailResourceId;
    }

    public int getBackgroundRequestResourceId() {
        return backgroundRequestResourceId;
    }

    public int getBackgroundRequestDetailResourceId() {
        return backgroundRequestDetailResourceId;
    }

    public int getRequestRes() {
        return requestRes;
    }

    public int getPriority() {
        return priority;
    }
}
