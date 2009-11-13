/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.os.Parcelable;
import android.os.Parcel;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;

/**
 * Objects that pass through the ContentProvider and ContentResolver's methods that deal with
 * Entities must implement this abstract base class and thus themselves be Parcelable.
 * @hide
 */
public final class Entity implements Parcelable {
    final private ContentValues mValues;
    final private ArrayList<NamedContentValues> mSubValues;

    public Entity(ContentValues values) {
        mValues = values;
        mSubValues = new ArrayList<NamedContentValues>();
    }

    public ContentValues getEntityValues() {
        return mValues;
    }

    public ArrayList<NamedContentValues> getSubValues() {
        return mSubValues;
    }

    public void addSubValue(Uri uri, ContentValues values) {
        mSubValues.add(new Entity.NamedContentValues(uri, values));
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        mValues.writeToParcel(dest, 0);
        dest.writeInt(mSubValues.size());
        for (NamedContentValues value : mSubValues) {
            value.uri.writeToParcel(dest, 0);
            value.values.writeToParcel(dest, 0);
        }
    }

    private Entity(Parcel source) {
        mValues = ContentValues.CREATOR.createFromParcel(source);
        final int numValues = source.readInt();
        mSubValues = new ArrayList<NamedContentValues>(numValues);
        for (int i = 0; i < numValues; i++) {
            final Uri uri = Uri.CREATOR.createFromParcel(source);
            final ContentValues values = ContentValues.CREATOR.createFromParcel(source);
            mSubValues.add(new NamedContentValues(uri, values));
        }
    }

    public static final Creator<Entity> CREATOR = new Creator<Entity>() {
        public Entity createFromParcel(Parcel source) {
            return new Entity(source);
        }

        public Entity[] newArray(int size) {
            return new Entity[size];
        }
    };

    public static class NamedContentValues {
        public final Uri uri;
        public final ContentValues values;

        public NamedContentValues(Uri uri, ContentValues values) {
            this.uri = uri;
            this.values = values;
        }
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Entity: ").append(getEntityValues());
        for (Entity.NamedContentValues namedValue : getSubValues()) {
            sb.append("\n  ").append(namedValue.uri);
            sb.append("\n  -> ").append(namedValue.values);
        }
        return sb.toString();
    }
}
