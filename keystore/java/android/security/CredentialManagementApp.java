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
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * The credential management app has the ability to manage the user's KeyChain credentials on
 * unmanaged devices. {@link KeyChain#createManageCredentialsIntent} should be used by an app to
 * request to become the credential management app. The user must approve this request before the
 * app can manage the user's credentials.
 * <p>
 * Note: there can only be one credential management on the device. If another app requests to
 * become the credential management app and the user approves, then the existing credential
 * management app will no longer be able to manage credentials.
 * <p>
 * The requesting credential management app should include its authentication policy in the
 * requesting intent. The authentication policy declares which certificates should be used for a
 * given list of apps and URIs.
 *
 * @hide
 * @see AppUriAuthenticationPolicy
 */
public class CredentialManagementApp {

    private static final String TAG = "CredentialManagementApp";
    private static final String KEY_PACKAGE_NAME = "package_name";

    /**
     * The credential management app's package name
     */
    @NonNull
    private final String mPackageName;

    /**
     * The mappings from an app and list of URIs to a list of aliases, which will be used for
     * authentication.
     * <p>
     * appPackageName -> uri -> alias
     */
    @NonNull
    private AppUriAuthenticationPolicy mAuthenticationPolicy;

    public CredentialManagementApp(@NonNull String packageName,
            @NonNull AppUriAuthenticationPolicy authenticationPolicy) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(authenticationPolicy);
        mPackageName = packageName;
        mAuthenticationPolicy = authenticationPolicy;
    }

    /**
     * Returns the package name of the credential management app.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns the authentication policy of the credential management app.
     */
    @NonNull
    public AppUriAuthenticationPolicy getAuthenticationPolicy() {
        return mAuthenticationPolicy;
    }

    /**
     * Sets the authentication policy of the credential management app.
     */
    public void setAuthenticationPolicy(@Nullable AppUriAuthenticationPolicy authenticationPolicy) {
        Objects.requireNonNull(authenticationPolicy);
        mAuthenticationPolicy = authenticationPolicy;
    }

    /**
     * Restore a previously saved {@link CredentialManagementApp} from XML.
     */
    @Nullable
    public static CredentialManagementApp readFromXml(@NonNull XmlPullParser parser) {
        try {
            String packageName = parser.getAttributeValue(null, KEY_PACKAGE_NAME);
            AppUriAuthenticationPolicy policy = AppUriAuthenticationPolicy.readFromXml(parser);
            return new CredentialManagementApp(packageName, policy);
        } catch (XmlPullParserException | IOException e) {
            Log.w(TAG, "Reading from xml failed", e);
        }
        return null;
    }

    /**
     * Save the {@link CredentialManagementApp} to XML.
     */
    public void writeToXml(@NonNull XmlSerializer out) throws IOException {
        out.attribute(null, KEY_PACKAGE_NAME, mPackageName);
        if (mAuthenticationPolicy != null) {
            mAuthenticationPolicy.writeToXml(out);
        }
    }
}
