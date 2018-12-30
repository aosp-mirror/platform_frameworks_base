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

import android.annotation.UnsupportedAppUsage;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;

/**
 * A representation of a item using ContentValues. It contains one top level ContentValue
 * plus a collection of Uri, ContentValues tuples as subvalues. One example of its use
 * is in Contacts, where the top level ContentValue contains the columns from the RawContacts
 * table and the subvalues contain a ContentValues object for each row from the Data table that
 * corresponds to that RawContact. The uri refers to the Data table uri for each row.
 */
public final class Entity {
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    final private ContentValues mValues;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
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
