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

import android.annotation.Nullable;
import android.content.IntentFilter;
import android.os.Parcel;
import android.util.Pair;

import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;

import java.util.ArrayList;
import java.util.List;

/** @hide **/
public final class ParsedIntentInfo extends IntentFilter {

    public static final Parceler PARCELER = new Parceler();

    public static class Parceler implements Parcelling<ParsedIntentInfo> {

        @Override
        public void parcel(ParsedIntentInfo item, Parcel dest, int parcelFlags) {
            item.writeIntentInfoToParcel(dest, parcelFlags);
        }

        @Override
        public ParsedIntentInfo unparcel(Parcel source) {
            return new ParsedIntentInfo(source);
        }
    }

    public static class ListParceler implements Parcelling<List<ParsedIntentInfo>> {

        /**
         * <p>
         * Implementation note: The serialized form for the intent list also contains the name
         * of the concrete class that's stored in the list, and assumes that every element of the
         * list is of the same type. This is very similar to the original parcelable mechanism.
         * We cannot use that directly because IntentInfo extends IntentFilter, which is parcelable
         * and is public API. It also declares Parcelable related methods as final which means
         * we can't extend them. The approach of using composition instead of inheritance leads to
         * a large set of cascading changes in the PackageManagerService, which seem undesirable.
         *
         * <p>
         * <b>WARNING: </b> The list of objects returned by this function might need to be fixed up
         * to make sure their owner fields are consistent. See {@code fixupOwner}.
         */
        @Override
        public void parcel(List<ParsedIntentInfo> item, Parcel dest, int parcelFlags) {
            if (item == null) {
                dest.writeInt(-1);
                return;
            }

            final int size = item.size();
            dest.writeInt(size);

            for (int index = 0; index < size; index++) {
                PARCELER.parcel(item.get(index), dest, parcelFlags);
            }
        }

        @Override
        public List<ParsedIntentInfo> unparcel(Parcel source) {
            int size = source.readInt();
            if (size == -1) {
                return null;
            }

            if (size == 0) {
                return new ArrayList<>(0);
            }

            final ArrayList<ParsedIntentInfo> intentsList = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                intentsList.add(PARCELER.unparcel(source));
            }

            return intentsList;
        }
    }

    public static class StringPairListParceler implements Parcelling<List<Pair<String, ParsedIntentInfo>>> {

        @Override
        public void parcel(List<Pair<String, ParsedIntentInfo>> item, Parcel dest,
                int parcelFlags) {
            if (item == null) {
                dest.writeInt(-1);
                return;
            }

            final int size = item.size();
            dest.writeInt(size);

            for (int index = 0; index < size; index++) {
                Pair<String, ParsedIntentInfo> pair = item.get(index);
                dest.writeString(pair.first);
                PARCELER.parcel(pair.second, dest, parcelFlags);
            }
        }

        @Override
        public List<Pair<String, ParsedIntentInfo>> unparcel(Parcel source) {
            int size = source.readInt();
            if (size == -1) {
                return null;
            }

            if (size == 0) {
                return new ArrayList<>(0);
            }

            final List<Pair<String, ParsedIntentInfo>> list = new ArrayList<>(size);
            for (int i = 0; i < size; ++i) {
                list.add(Pair.create(source.readString(), PARCELER.unparcel(source)));
            }

            return list;
        }
    }

    boolean hasDefault;
    int labelRes;
    @Nullable
    CharSequence nonLocalizedLabel;
    int icon;

    public ParsedIntentInfo() {
    }

    public ParsedIntentInfo(Parcel in) {
        super(in);
        hasDefault = in.readBoolean();
        labelRes = in.readInt();
        nonLocalizedLabel = in.readCharSequence();
        icon = in.readInt();
    }

    public void writeIntentInfoToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(hasDefault);
        dest.writeInt(labelRes);
        dest.writeCharSequence(nonLocalizedLabel);
        dest.writeInt(icon);
    }

    public String toString() {
        return "ProviderIntentInfo{"
                + Integer.toHexString(System.identityHashCode(this))
                + '}';
    }

    public boolean isHasDefault() {
        return hasDefault;
    }

    public int getLabelRes() {
        return labelRes;
    }

    @Nullable
    public CharSequence getNonLocalizedLabel() {
        return nonLocalizedLabel;
    }

    public int getIcon() {
        return icon;
    }
}
