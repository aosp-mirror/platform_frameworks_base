/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.content.pm;

import android.content.pm.VerificationParams;
import android.net.Uri;
import android.os.Parcel;
import android.test.AndroidTestCase;

/**
 * Tests the android.content.pm.VerificationParams class
 *
 * To test run:
 * ./development/testrunner/runtest.py frameworks-core -c android.content.pm.VerificationParamsTest
 */
public class VerificationParamsTest extends AndroidTestCase {

    private final static String VERIFICATION_URI_STRING = "http://verification.uri/path";
    private final static String ORIGINATING_URI_STRING = "http://originating.uri/path";
    private final static String REFERRER_STRING = "http://referrer.uri/path";
    private final static int INSTALLER_UID = 42;

    private final static Uri VERIFICATION_URI = Uri.parse(VERIFICATION_URI_STRING);
    private final static Uri ORIGINATING_URI = Uri.parse(ORIGINATING_URI_STRING);
    private final static Uri REFERRER = Uri.parse(REFERRER_STRING);

    private final static int ORIGINATING_UID = 10042;

    public void testParcel() throws Exception {
        VerificationParams expected = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        Parcel parcel = Parcel.obtain();
        expected.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        VerificationParams actual = VerificationParams.CREATOR.createFromParcel(parcel);

        assertEquals(VERIFICATION_URI, actual.getVerificationURI());

        assertEquals(ORIGINATING_URI, actual.getOriginatingURI());

        assertEquals(REFERRER, actual.getReferrer());

        assertEquals(ORIGINATING_UID, actual.getOriginatingUid());
    }

    public void testEquals_Success() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertEquals(params1, params2);
    }

    public void testEquals_VerificationUri_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse("http://a.different.uri/"), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_OriginatingUri_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse("http://a.different.uri/"),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_Referrer_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse("http://a.different.uri/"), ORIGINATING_UID);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_Originating_Uid_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), 12345);

        assertFalse(params1.equals(params2));
    }

    public void testEquals_InstallerUid_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);
        params2.setInstallerUid(INSTALLER_UID);

        assertFalse(params1.equals(params2));
    }

    public void testHashCode_Success() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertEquals(params1.hashCode(), params2.hashCode());
    }

    public void testHashCode_VerificationUri_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(null, Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_OriginatingUri_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse("http://a.different.uri/"),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_Referrer_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING), null,
                ORIGINATING_UID);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_Originating_Uid_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), 12345);

        assertFalse(params1.hashCode() == params2.hashCode());
    }

    public void testHashCode_InstallerUid_Failure() throws Exception {
        VerificationParams params1 = new VerificationParams(VERIFICATION_URI, ORIGINATING_URI,
                REFERRER, ORIGINATING_UID);

        VerificationParams params2 = new VerificationParams(
                Uri.parse(VERIFICATION_URI_STRING), Uri.parse(ORIGINATING_URI_STRING),
                Uri.parse(REFERRER_STRING), ORIGINATING_UID);
        params2.setInstallerUid(INSTALLER_UID);

        assertFalse(params1.hashCode() == params2.hashCode());
    }
}
