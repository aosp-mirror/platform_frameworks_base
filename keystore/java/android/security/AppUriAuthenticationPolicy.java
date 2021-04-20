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
import android.app.Activity;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.security.Principal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The app-URI authentication policy is set by the credential management app. This policy determines
 * which alias for a private key and certificate pair should be used for authentication.
 * <p>
 * The authentication policy should be added as a parameter when calling
 * {@link KeyChain#createManageCredentialsIntent}.
 * <p>
 * Example:
 * <pre>{@code
 *     AppUriAuthenticationPolicy authenticationPolicy = new AppUriAuthenticationPolicy.Builder()
 *              .addAppAndUriMapping("com.test.pkg", testUri, "testAlias")
 *              .addAppAndUriMapping("com.test2.pkg", testUri1, "testAlias2")
 *              .addAppAndUriMapping("com.test2.pkg", testUri2, "testAlias2")
 *              .build();
 *     Intent requestIntent = KeyChain.createManageCredentialsIntent(authenticationPolicy);
 * }</pre>
 * <p>
 */
public final class AppUriAuthenticationPolicy implements Parcelable {

    private static final String KEY_AUTHENTICATION_POLICY_APP_TO_URIS =
            "authentication_policy_app_to_uris";
    private static final String KEY_AUTHENTICATION_POLICY_APP = "policy_app";

    /**
     * The mappings from an app and list of URIs to a list of aliases, which will be used for
     * authentication.
     * <p>
     * appPackageName -> uri -> alias
     */
    @NonNull
    private final Map<String, UrisToAliases> mAppToUris;

    private AppUriAuthenticationPolicy(@NonNull Map<String, UrisToAliases> appToUris) {
        Objects.requireNonNull(appToUris);
        this.mAppToUris = appToUris;
    }

    /**
     * Builder class for {@link AppUriAuthenticationPolicy} objects.
     */
    public static final class Builder {
        private Map<String, UrisToAliases> mPackageNameToUris;

        /**
         * Initialize a new Builder to construct an {@link AppUriAuthenticationPolicy}.
         */
        public Builder() {
            mPackageNameToUris = new HashMap<>();
        }

        /**
         * Adds mappings from an app and URI to an alias, which will be used for authentication.
         * <p>
         * If this method is called with a package name and URI that was previously added, the
         * previous alias will be overwritten.
         * <p>
         * When the system tries to determine which alias to return to a requesting app calling
         * {@code KeyChain.choosePrivateKeyAlias}, it will choose the alias whose associated URI
         * exactly matches the URI provided in {@link KeyChain#choosePrivateKeyAlias(
         * Activity, KeyChainAliasCallback, String[], Principal[], Uri, String)} or the URI
         * built from the host and port provided in {@link KeyChain#choosePrivateKeyAlias(
         * Activity, KeyChainAliasCallback, String[], Principal[], String, int, String)}.
         *
         * @param appPackageName The app's package name to authenticate the user to.
         * @param uri            The URI to authenticate the user to.
         * @param alias          The alias which will be used for authentication.
         *
         * @return the same Builder instance.
         */
        @NonNull
        public Builder addAppAndUriMapping(@NonNull String appPackageName, @NonNull Uri uri,
                @NonNull String alias) {
            Objects.requireNonNull(appPackageName);
            Objects.requireNonNull(uri);
            Objects.requireNonNull(alias);
            UrisToAliases urisToAliases =
                    mPackageNameToUris.getOrDefault(appPackageName, new UrisToAliases());
            urisToAliases.addUriToAlias(uri, alias);
            mPackageNameToUris.put(appPackageName, urisToAliases);
            return this;
        }

        /**
         * Adds mappings from an app and list of URIs to a list of aliases, which will be used for
         * authentication.
         * <p>
         * appPackageName -> uri -> alias
         *
         * @hide
         */
        @NonNull
        public Builder addAppAndUriMapping(@NonNull String appPackageName,
                @NonNull UrisToAliases urisToAliases) {
            Objects.requireNonNull(appPackageName);
            Objects.requireNonNull(urisToAliases);
            mPackageNameToUris.put(appPackageName, urisToAliases);
            return this;
        }

        /**
         * Combines all of the attributes that have been set on the {@link Builder}
         *
         * @return a new {@link AppUriAuthenticationPolicy} object.
         */
        @NonNull
        public AppUriAuthenticationPolicy build() {
            return new AppUriAuthenticationPolicy(mPackageNameToUris);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mAppToUris);
    }

    @NonNull
    public static final Parcelable.Creator<AppUriAuthenticationPolicy> CREATOR =
            new Parcelable.Creator<AppUriAuthenticationPolicy>() {
                @Override
                public AppUriAuthenticationPolicy createFromParcel(Parcel in) {
                    Map<String, UrisToAliases> appToUris = new HashMap<>();
                    in.readMap(appToUris, UrisToAliases.class.getClassLoader());
                    return new AppUriAuthenticationPolicy(appToUris);
                }

                @Override
                public AppUriAuthenticationPolicy[] newArray(int size) {
                    return new AppUriAuthenticationPolicy[size];
                }
            };

    @Override
    public String toString() {
        return "AppUriAuthenticationPolicy{"
                + "mPackageNameToUris=" + mAppToUris
                + '}';
    }

    /**
     * Return the authentication policy mapping, which determines which alias for a private key
     * and certificate pair should be used for authentication.
     * <p>
     * appPackageName -> uri -> alias
     */
    @NonNull
    public Map<String, Map<Uri, String>> getAppAndUriMappings() {
        Map<String, Map<Uri, String>> appAndUris = new HashMap<>();
        for (Map.Entry<String, UrisToAliases> entry : mAppToUris.entrySet()) {
            appAndUris.put(entry.getKey(), entry.getValue().getUrisToAliases());
        }
        return appAndUris;
    }

    /**
     * Restore a previously saved {@link AppUriAuthenticationPolicy} from XML.
     *
     * @hide
     */
    @Nullable
    public static AppUriAuthenticationPolicy readFromXml(@NonNull XmlPullParser parser)
            throws IOException, XmlPullParserException {
        AppUriAuthenticationPolicy.Builder builder = new AppUriAuthenticationPolicy.Builder();
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (!parser.getName().equals(KEY_AUTHENTICATION_POLICY_APP_TO_URIS)) {
                continue;
            }
            String app = parser.getAttributeValue(null, KEY_AUTHENTICATION_POLICY_APP);
            UrisToAliases urisToAliases = UrisToAliases.readFromXml(parser);
            builder.addAppAndUriMapping(app, urisToAliases);
        }
        return builder.build();
    }

    /**
     * Save the {@link AppUriAuthenticationPolicy} to XML.
     *
     * @hide
     */
    public void writeToXml(@NonNull XmlSerializer out) throws IOException {
        for (Map.Entry<String, UrisToAliases> appsToUris : mAppToUris.entrySet()) {
            out.startTag(null, KEY_AUTHENTICATION_POLICY_APP_TO_URIS);
            out.attribute(null, KEY_AUTHENTICATION_POLICY_APP, appsToUris.getKey());
            appsToUris.getValue().writeToXml(out);
            out.endTag(null, KEY_AUTHENTICATION_POLICY_APP_TO_URIS);
        }
    }

    /**
     * Get the set of aliases found in the policy.
     *
     * @hide
     */
    public Set<String> getAliases() {
        Set<String> aliases = new HashSet<>();
        for (UrisToAliases appsToUris : mAppToUris.values()) {
            aliases.addAll(appsToUris.getUrisToAliases().values());
        }
        return aliases;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof AppUriAuthenticationPolicy)) {
            return false;
        }
        AppUriAuthenticationPolicy other = (AppUriAuthenticationPolicy) obj;
        return Objects.equals(mAppToUris, other.mAppToUris);
    }

    @Override
    public int hashCode() {
        return mAppToUris.hashCode();
    }

}
