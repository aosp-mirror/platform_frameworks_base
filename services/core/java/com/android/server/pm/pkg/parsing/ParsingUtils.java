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

package com.android.server.pm.pkg.parsing;

import static com.android.server.pm.pkg.parsing.ParsingPackageUtils.RIGID_PARSER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.pm.pkg.component.ParsedIntentInfo;
import com.android.internal.util.Parcelling;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.pkg.component.ParsedIntentInfoImpl;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** @hide **/
public class ParsingUtils {

    public static final String TAG = "PackageParsing";

    public static final String ANDROID_RES_NAMESPACE = "http://schemas.android.com/apk/res/android";

    public static final int DEFAULT_MIN_SDK_VERSION = 1;
    public static final int DEFAULT_MAX_SDK_VERSION = Integer.MAX_VALUE;
    public static final int DEFAULT_TARGET_SDK_VERSION = 0;

    public static final int NOT_SET = -1;

    @Nullable
    public static String buildClassName(String pkg, CharSequence clsSeq) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return pkg + cls;
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString();
        }
        return cls;
    }

    @NonNull
    public static ParseResult unknownTag(String parentTag, ParsingPackage pkg,
            XmlResourceParser parser, ParseInput input) throws IOException, XmlPullParserException {
        if (RIGID_PARSER) {
            return input.error("Bad element under " + parentTag + ": " + parser.getName());
        }
        Slog.w(TAG, "Unknown element under " + parentTag + ": "
                + parser.getName() + " at " + pkg.getBaseApkPath() + " "
                + parser.getPositionDescription());
        XmlUtils.skipCurrentTag(parser);
        return input.success(null); // Type doesn't matter
    }

    /**
     * Use with {@link Parcel#writeTypedList(List)} or
     * {@link #writeInterfaceAsImplList(Parcel, List)}
     *
     * @see Parcel#createTypedArrayList(Parcelable.Creator)
     */
    @NonNull
    public static <Interface, Impl extends Interface> List<Interface> createTypedInterfaceList(
            @NonNull Parcel parcel, @NonNull Parcelable.Creator<Impl> creator) {
        int size = parcel.readInt();
        if (size < 0) {
            return new ArrayList<>();
        }
        ArrayList<Interface> list = new ArrayList<Interface>(size);
        while (size > 0) {
            list.add(parcel.readTypedObject(creator));
            size--;
        }
        return list;
    }

    /**
     * Use with {@link #createTypedInterfaceList(Parcel, Parcelable.Creator)}.
     *
     * Writes a list that can be cast as Parcelable types at runtime.
     * TODO: Remove with ImmutableList multi-casting support
     *
     * @see Parcel#writeTypedList(List)
     */
    @NonNull
    public static void writeParcelableList(@NonNull Parcel parcel, List<?> list) {
        if (list == null) {
            parcel.writeInt(-1);
            return;
        }
        int size = list.size();
        int index = 0;
        parcel.writeInt(size);
        while (index < size) {
            parcel.writeTypedObject((Parcelable) list.get(index), 0);
            index++;
        }
    }

    public static class StringPairListParceler implements
            Parcelling<List<Pair<String, ParsedIntentInfo>>> {

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
                dest.writeParcelable((Parcelable) pair.second, parcelFlags);
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
                list.add(Pair.create(source.readString(), source.readParcelable(
                        ParsedIntentInfoImpl.class.getClassLoader(), ParsedIntentInfo.class)));
            }

            return list;
        }
    }

    /**
     * Parse the {@link android.R.attr#knownActivityEmbeddingCerts} attribute, if available.
     */
    @NonNull
    public static ParseResult<Set<String>> parseKnownActivityEmbeddingCerts(@NonNull TypedArray sa,
            @NonNull Resources res, int resourceId, @NonNull ParseInput input) {
        if (!sa.hasValue(resourceId)) {
            return input.success(null);
        }

        final int knownActivityEmbeddingCertsResource = sa.getResourceId(resourceId, 0);
        if (knownActivityEmbeddingCertsResource != 0) {
            // The knownCerts attribute supports both a string array resource as well as a
            // string resource for the case where the permission should only be granted to a
            // single known signer.
            Set<String> knownEmbeddingCertificates = null;
            final String resourceType = res.getResourceTypeName(
                    knownActivityEmbeddingCertsResource);
            if (resourceType.equals("array")) {
                final String[] knownCerts = res.getStringArray(knownActivityEmbeddingCertsResource);
                if (knownCerts != null) {
                    knownEmbeddingCertificates = Set.of(knownCerts);
                }
            } else {
                final String knownCert = res.getString(knownActivityEmbeddingCertsResource);
                if (knownCert != null) {
                    knownEmbeddingCertificates = Set.of(knownCert);
                }
            }
            if (knownEmbeddingCertificates == null || knownEmbeddingCertificates.isEmpty()) {
                return input.error("Defined a knownActivityEmbeddingCerts attribute but the "
                        + "provided resource is null");
            }
            return input.success(knownEmbeddingCertificates);
        }

        // If the knownCerts resource ID is null - the app specified a string value for the
        // attribute representing a single trusted signer.
        final String knownCert = sa.getString(resourceId);
        if (knownCert == null || knownCert.isEmpty()) {
            return input.error("Defined a knownActivityEmbeddingCerts attribute but the provided "
                    + "string is empty");
        }
        return input.success(Set.of(knownCert));
    }
}
