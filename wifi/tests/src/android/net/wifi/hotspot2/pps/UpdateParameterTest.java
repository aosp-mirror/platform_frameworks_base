/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package android.net.wifi.hotspot2.pps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.net.wifi.FakeKeys;
import android.os.Parcel;
import android.util.Base64;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Unit tests for {@link android.net.wifi.hotspot2.pps.UpdateParameter}.
 */
@SmallTest
public class UpdateParameterTest {
    private static final int MAX_URI_BYTES = 1023;
    private static final int MAX_URL_BYTES = 1023;
    private static final int MAX_USERNAME_BYTES = 63;
    private static final int MAX_PASSWORD_BYTES = 255;
    private static final int CERTIFICATE_SHA256_BYTES = 32;

    /**
     * Helper function for creating a {@link UpdateParameter} for testing.
     *
     * @return {@link UpdateParameter}
     */
    private static UpdateParameter createUpdateParameter() {
        UpdateParameter updateParam = new UpdateParameter();
        updateParam.setUpdateIntervalInMinutes(1712);
        updateParam.setUpdateMethod(UpdateParameter.UPDATE_METHOD_OMADM);
        updateParam.setRestriction(UpdateParameter.UPDATE_RESTRICTION_HOMESP);
        updateParam.setServerUri("server.pdate.com");
        updateParam.setUsername("username");
        updateParam.setBase64EncodedPassword(
                Base64.encodeToString("password".getBytes(), Base64.DEFAULT));
        updateParam.setTrustRootCertUrl("trust.cert.com");
        updateParam.setTrustRootCertSha256Fingerprint(new byte[32]);
        updateParam.setCaCertificate(FakeKeys.CA_CERT0);
        return updateParam;
    }

    /**
     * Helper function for verifying UpdateParameter after parcel write then read.
     * @param paramToWrite The UpdateParamter to verify
     * @throws Exception
     */
    private static void verifyParcel(UpdateParameter paramToWrite) throws Exception {
        Parcel parcel = Parcel.obtain();
        paramToWrite.writeToParcel(parcel, 0);

        parcel.setDataPosition(0);    // Rewind data position back to the beginning for read.
        UpdateParameter paramFromRead = UpdateParameter.CREATOR.createFromParcel(parcel);
        assertTrue(paramFromRead.equals(paramToWrite));
        assertEquals(paramToWrite.hashCode(), paramFromRead.hashCode());
    }

    /**
     * Verify parcel read/write for an empty UpdateParameter.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithEmptyUpdateParameter() throws Exception {
        verifyParcel(new UpdateParameter());
    }

    /**
     * Verify parcel read/write for a UpdateParameter with all fields set.
     *
     * @throws Exception
     */
    @Test
    public void verifyParcelWithFullUpdateParameter() throws Exception {
        verifyParcel(createUpdateParameter());
    }

    /**
     * Verify that UpdateParameter created using copy constructor with null source should be the
     * same as the UpdateParameter created using default constructor.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructionWithNullSource() throws Exception {
        UpdateParameter copyParam = new UpdateParameter(null);
        UpdateParameter defaultParam = new UpdateParameter();
        assertTrue(defaultParam.equals(copyParam));
    }

    /**
     * Verify that UpdateParameter created using copy constructor with a valid source should be the
     * same as the source.
     *
     * @throws Exception
     */
    @Test
    public void verifyCopyConstructionWithFullUpdateParameter() throws Exception {
        UpdateParameter origParam = createUpdateParameter();
        UpdateParameter copyParam = new UpdateParameter(origParam);
        assertTrue(origParam.equals(copyParam));
    }

    /**
     * Verify that a default UpdateParameter is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithDefault() throws Exception {
        UpdateParameter updateParam = new UpdateParameter();
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter created using {@link #createUpdateParameter} is valid,
     * since all fields are filled in with valid values.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithFullPolicy() throws Exception {
        assertTrue(createUpdateParameter().validate());
    }

    /**
     * Verify that an UpdateParameter with an unknown update method is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithUnknowMethod() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setUpdateMethod("adsfasd");
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an unknown restriction is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithUnknowRestriction() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setRestriction("adsfasd");
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an username exceeding maximum size is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithUsernameExceedingMaxSize() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        byte[] rawUsernameBytes = new byte[MAX_USERNAME_BYTES + 1];
        Arrays.fill(rawUsernameBytes, (byte) 'a');
        updateParam.setUsername(new String(rawUsernameBytes, StandardCharsets.UTF_8));
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an empty username is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithEmptyUsername() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setUsername(null);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with a password exceeding maximum size is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithPasswordExceedingMaxSize() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        byte[] rawPasswordBytes = new byte[MAX_PASSWORD_BYTES + 1];
        Arrays.fill(rawPasswordBytes, (byte) 'a');
        updateParam.setBase64EncodedPassword(new String(rawPasswordBytes, StandardCharsets.UTF_8));
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an empty password is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithEmptyPassword() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setBase64EncodedPassword(null);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with a Base64 encoded password that contained invalid padding
     * is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithPasswordContainedInvalidPadding() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setBase64EncodedPassword(updateParam.getBase64EncodedPassword() + "=");
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter without trust root certificate URL is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithoutTrustRootCertUrl() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setTrustRootCertUrl(null);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with invalid trust root certificate URL is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithInvalidTrustRootCertUrl() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        byte[] rawUrlBytes = new byte[MAX_URL_BYTES + 1];
        Arrays.fill(rawUrlBytes, (byte) 'a');
        updateParam.setTrustRootCertUrl(new String(rawUrlBytes, StandardCharsets.UTF_8));
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter without trust root certificate SHA-256 fingerprint is
     * invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithouttrustRootCertSha256Fingerprint() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setTrustRootCertSha256Fingerprint(null);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an incorrect size trust root certificate SHA-256
     * fingerprint is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithInvalidtrustRootCertSha256Fingerprint() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setTrustRootCertSha256Fingerprint(new byte[CERTIFICATE_SHA256_BYTES + 1]);
        assertFalse(updateParam.validate());

        updateParam.setTrustRootCertSha256Fingerprint(new byte[CERTIFICATE_SHA256_BYTES - 1]);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter without server URI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithoutServerUri() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setServerUri(null);
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with an invalid server URI is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validatePolicyWithInvalidServerUri() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        byte[] rawUriBytes = new byte[MAX_URI_BYTES + 1];
        Arrays.fill(rawUriBytes, (byte) 'a');
        updateParam.setServerUri(new String(rawUriBytes, StandardCharsets.UTF_8));
        assertFalse(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with update interval set to "never" will not perform
     * validation on other parameters, since update is not applicable in this case.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithNoServerCheck() throws Exception {
        UpdateParameter updateParam = new UpdateParameter();
        updateParam.setUpdateIntervalInMinutes(UpdateParameter.UPDATE_CHECK_INTERVAL_NEVER);
        updateParam.setUsername(null);
        updateParam.setBase64EncodedPassword(null);
        updateParam.setUpdateMethod(null);
        updateParam.setRestriction(null);
        updateParam.setServerUri(null);
        updateParam.setTrustRootCertUrl(null);
        updateParam.setTrustRootCertSha256Fingerprint(null);
        assertTrue(updateParam.validate());
    }

    /**
     * Verify that an UpdateParameter with unset update interval is invalid.
     *
     * @throws Exception
     */
    @Test
    public void validateUpdateParameterWithoutUpdateInterval() throws Exception {
        UpdateParameter updateParam = createUpdateParameter();
        updateParam.setUpdateIntervalInMinutes(Long.MIN_VALUE);
        assertFalse(updateParam.validate());
    }
}
