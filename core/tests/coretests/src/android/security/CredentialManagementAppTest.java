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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import android.net.Uri;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class CredentialManagementAppTest {

    private static final String TEST_PACKAGE_NAME_1 = "com.android.test";
    private static final String TEST_PACKAGE_NAME_2 = "com.android.test2";
    private static final Uri TEST_URI_1 = Uri.parse("test.com");
    private static final Uri TEST_URI_2 = Uri.parse("test2.com");
    private static final String TEST_ALIAS_1 = "testAlias";
    private static final String TEST_ALIAS_2 = "testAlias2";

    private static final String PACKAGE_NAME = "com.android.cred.mng.pkg";
    private static final AppUriAuthenticationPolicy AUTHENTICATION_POLICY =
            new AppUriAuthenticationPolicy.Builder()
                    .addAppAndUriMapping(TEST_PACKAGE_NAME_1, TEST_URI_1, TEST_ALIAS_1)
                    .build();
    private static final CredentialManagementApp CREDENTIAL_MANAGEMENT_APP =
            new CredentialManagementApp(PACKAGE_NAME, AUTHENTICATION_POLICY);

    private static final String TAG_CREDENTIAL_MANAGEMENT_APP = "credential-management-app";

    @Test
    public void credentialManagementApp_getters() {
        CredentialManagementApp credentialManagementApp =
                new CredentialManagementApp(PACKAGE_NAME, AUTHENTICATION_POLICY);

        assertThat(credentialManagementApp.getPackageName(), is(PACKAGE_NAME));
        assertThat(credentialManagementApp.getAuthenticationPolicy(), is(AUTHENTICATION_POLICY));
    }

    @Test
    public void setAuthenticationPolicy_updatesAuthenticationPolicy() {
        CredentialManagementApp credentialManagementApp =
                new CredentialManagementApp(PACKAGE_NAME, AUTHENTICATION_POLICY);
        AppUriAuthenticationPolicy updatedAuthenticationPolicy =
                new AppUriAuthenticationPolicy.Builder().addAppAndUriMapping(
                        TEST_PACKAGE_NAME_2, TEST_URI_2, TEST_ALIAS_2).build();

        credentialManagementApp.setAuthenticationPolicy(updatedAuthenticationPolicy);

        assertThat(credentialManagementApp.getAuthenticationPolicy(),
                is(updatedAuthenticationPolicy));
    }

    @Test
    public void constructor_nullPackageName_throwException() {
        try {
            new CredentialManagementApp(/* packageName= */ null, AUTHENTICATION_POLICY);
            fail("Shall not take null inputs");
        } catch (NullPointerException expected) {
            // Expected behavior, nothing to do.
        }
    }

    @Test
    public void constructor_nullAuthenticationPolicy_throwException() {
        try {
            new CredentialManagementApp(PACKAGE_NAME, /* authenticationPolicy= */ null);
            fail("Shall not take null inputs");
        } catch (NullPointerException expected) {
            // Expected behavior, nothing to do.
        }
    }

    @Test
    public void writeToXmlAndReadFromXml() throws IOException, XmlPullParserException {
        File xmlFile = writeToXml(CREDENTIAL_MANAGEMENT_APP);

        CredentialManagementApp loadedCredentialManagementApp = readFromXml(xmlFile);

        assertCredentialManagementAppsEqual(loadedCredentialManagementApp,
                CREDENTIAL_MANAGEMENT_APP);
    }

    private File writeToXml(CredentialManagementApp credentialManagementApp) throws IOException {
        File file = File.createTempFile("temp", "credmng");
        final FileOutputStream out = new FileOutputStream(file);
        XmlSerializer xml = Xml.newSerializer();
        xml.setOutput(out, StandardCharsets.UTF_8.name());
        xml.startDocument(null, true);
        xml.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        xml.startTag(null, TAG_CREDENTIAL_MANAGEMENT_APP);
        credentialManagementApp.writeToXml(xml);
        xml.endTag(null, TAG_CREDENTIAL_MANAGEMENT_APP);
        xml.endDocument();
        out.close();
        return file;
    }

    private CredentialManagementApp readFromXml(File file)
            throws IOException, XmlPullParserException {
        CredentialManagementApp credentialManagementApp = null;
        final XmlPullParser parser = Xml.newPullParser();
        final FileInputStream in = new FileInputStream(file);
        parser.setInput(in, StandardCharsets.UTF_8.name());
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }
        String tag = parser.getName();
        if (TAG_CREDENTIAL_MANAGEMENT_APP.equals(tag)) {
            credentialManagementApp = CredentialManagementApp.readFromXml(parser);
        }
        return credentialManagementApp;
    }

    private void assertCredentialManagementAppsEqual(CredentialManagementApp actual,
            CredentialManagementApp expected) {
        assertThat(actual.getPackageName(), is(expected.getPackageName()));
        assertThat(actual.getAuthenticationPolicy(), is(expected.getAuthenticationPolicy()));
    }
}
