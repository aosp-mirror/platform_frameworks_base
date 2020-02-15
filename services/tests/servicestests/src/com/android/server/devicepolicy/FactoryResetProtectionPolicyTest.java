/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.admin.FactoryResetProtectionPolicy;
import android.os.Parcel;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FastXmlSerializer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link android.app.admin.FactoryResetProtectionPolicy}.
 *
 * atest com.android.server.devicepolicy.FactoryResetProtectionPolicyTest
 */
@RunWith(AndroidJUnit4.class)
public class FactoryResetProtectionPolicyTest {

    private static final String TAG_FACTORY_RESET_PROTECTION_POLICY =
            "factory_reset_protection_policy";

    @Test
    public void testNonDefaultFactoryResetProtectionPolicyObject() throws Exception {
        List<String> accounts = new ArrayList<>();
        accounts.add("Account 1");
        accounts.add("Account 2");

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .setFactoryResetProtectionEnabled(false)
                .build();

        testParcelAndUnparcel(policy);
        testSerializationAndDeserialization(policy);
    }

    @Test
    public void testInvalidXmlFactoryResetProtectionPolicyObject() throws Exception {
        List<String> accounts = new ArrayList<>();
        accounts.add("Account 1");
        accounts.add("Account 2");

        FactoryResetProtectionPolicy policy = new FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .setFactoryResetProtectionEnabled(false)
                .build();

        testParcelAndUnparcel(policy);
        testInvalidXmlSerializationAndDeserialization(policy);
    }

    private void testParcelAndUnparcel(FactoryResetProtectionPolicy policy) {
        Parcel parcel = Parcel.obtain();
        policy.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        FactoryResetProtectionPolicy actualPolicy =
                FactoryResetProtectionPolicy.CREATOR.createFromParcel(parcel);
        assertPoliciesAreEqual(policy, actualPolicy);
        parcel.recycle();
    }

    private void testSerializationAndDeserialization(FactoryResetProtectionPolicy policy)
            throws Exception {
        ByteArrayOutputStream outStream = serialize(policy);
        ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new InputStreamReader(inStream));
        assertEquals(XmlPullParser.START_TAG, parser.next());

        assertPoliciesAreEqual(policy, policy.readFromXml(parser));
    }

    private void testInvalidXmlSerializationAndDeserialization(FactoryResetProtectionPolicy policy)
            throws Exception {
        ByteArrayOutputStream outStream = serialize(policy);
        ByteArrayInputStream inStream = new ByteArrayInputStream(outStream.toByteArray());
        XmlPullParser parser = mock(XmlPullParser.class);
        when(parser.next()).thenThrow(XmlPullParserException.class);
        parser.setInput(new InputStreamReader(inStream));

        // If deserialization fails, then null is returned.
        assertNull(policy.readFromXml(parser));
    }

    private ByteArrayOutputStream serialize(FactoryResetProtectionPolicy policy)
            throws IOException {
        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        final XmlSerializer outXml = new FastXmlSerializer();
        outXml.setOutput(outStream, StandardCharsets.UTF_8.name());
        outXml.startDocument(null, true);
        outXml.startTag(null, TAG_FACTORY_RESET_PROTECTION_POLICY);
        policy.writeToXml(outXml);
        outXml.endTag(null, TAG_FACTORY_RESET_PROTECTION_POLICY);
        outXml.endDocument();
        outXml.flush();
        return outStream;
    }

    private void assertPoliciesAreEqual(FactoryResetProtectionPolicy expectedPolicy,
            FactoryResetProtectionPolicy actualPolicy) {
        assertEquals(expectedPolicy.isFactoryResetProtectionEnabled(),
                actualPolicy.isFactoryResetProtectionEnabled());
        assertAccountsAreEqual(expectedPolicy.getFactoryResetProtectionAccounts(),
                actualPolicy.getFactoryResetProtectionAccounts());
    }

    private void assertAccountsAreEqual(List<String> expectedAccounts,
            List<String> actualAccounts) {
        assertEquals(expectedAccounts.size(), actualAccounts.size());
        for (int i = 0; i < expectedAccounts.size(); i++) {
            assertEquals(expectedAccounts.get(i), actualAccounts.get(i));
        }
    }

}
