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

package android.security;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The mapping from URI to alias, which determines the alias to use when the user visits a URI.
 * This mapping is part of the {@link AppUriAuthenticationPolicy}, which specifies which app this
 * mapping should be used for.
 *
 * @hide
 * @see AppUriAuthenticationPolicy
 */
public final class UrisToAliases implements Parcelable {

    private static final String KEY_AUTHENTICATION_POLICY_URI_TO_ALIAS =
            "authentication_policy_uri_to_alias";
    private static final String KEY_AUTHENTICATION_POLICY_URI = "policy_uri";
    private static final String KEY_AUTHENTICATION_POLICY_ALIAS = "policy_alias";

    /**
     * The mappings from URIs to aliases, which will be used for authentication.
     */
    @NonNull
    private final Map<Uri, String> mUrisToAliases;

    public UrisToAliases() {
        this.mUrisToAliases = new HashMap<>();
    }

    private UrisToAliases(@NonNull Map<Uri, String> urisToAliases) {
        this.mUrisToAliases = urisToAliases;
    }

    @NonNull
    public static final Creator<UrisToAliases> CREATOR = new Creator<UrisToAliases>() {
        @Override
        public UrisToAliases createFromParcel(Parcel in) {
            Map<Uri, String> urisToAliases = new HashMap<>();
            in.readMap(urisToAliases, String.class.getClassLoader());
            return new UrisToAliases(urisToAliases);
        }

        @Override
        public UrisToAliases[] newArray(int size) {
            return new UrisToAliases[size];
        }
    };

    /**
     * Returns the mapping from URIs to aliases.
     */
    @NonNull
    public Map<Uri, String> getUrisToAliases() {
        return Collections.unmodifiableMap(mUrisToAliases);
    }

    /**
     * Adds mapping from an URI to an alias.
     */
    public void addUriToAlias(@NonNull Uri uri, @NonNull String alias) {
        mUrisToAliases.put(uri, alias);
    }

    /**
     * Restore a previously saved {@link UrisToAliases} from XML.
     */
    @Nullable
    public static UrisToAliases readFromXml(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        Map<Uri, String> urisToAliases = new HashMap<>();
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (!parser.getName().equals(KEY_AUTHENTICATION_POLICY_URI_TO_ALIAS)) {
                continue;
            }
            Uri uri = Uri.parse(parser.getAttributeValue(null, KEY_AUTHENTICATION_POLICY_URI));
            String alias = parser.getAttributeValue(null, KEY_AUTHENTICATION_POLICY_ALIAS);
            urisToAliases.put(uri, alias);
        }
        return new UrisToAliases(urisToAliases);
    }

    /**
     * Save the {@link UrisToAliases} to XML.
     */
    public void writeToXml(@NonNull XmlSerializer out) throws IOException {
        for (Map.Entry<Uri, String> urisToAliases : mUrisToAliases.entrySet()) {
            out.startTag(null, KEY_AUTHENTICATION_POLICY_URI_TO_ALIAS);
            out.attribute(null, KEY_AUTHENTICATION_POLICY_URI, urisToAliases.getKey().toString());
            out.attribute(null, KEY_AUTHENTICATION_POLICY_ALIAS, urisToAliases.getValue());
            out.endTag(null, KEY_AUTHENTICATION_POLICY_URI_TO_ALIAS);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mUrisToAliases);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof UrisToAliases)) {
            return false;
        }
        UrisToAliases other = (UrisToAliases) obj;
        return Objects.equals(mUrisToAliases, other.mUrisToAliases);
    }

    @Override
    public int hashCode() {
        return mUrisToAliases.hashCode();
    }
}
