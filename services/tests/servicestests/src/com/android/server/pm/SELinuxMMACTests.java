/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.pm;

import android.content.pm.PackageParser;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.net.Uri;
import android.os.FileUtils;
import android.test.AndroidTestCase;
import android.util.DisplayMetrics;

import com.android.frameworks.servicestests.R;

import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.IOException;

/**
 * Test the {@link SELinuxMMAC} functionality. An emphasis is placed on testing the
 * seinfo assignments that result from various mac_permissions.xml files. To run these
 * tests individually use the following set of commands:
 *
 * <pre>
 * {@code
 * cd $ANDROID_BUILD_TOP
 * make -j8 FrameworksServicesTests
 * adb install -r out/target/product/mako/data/app/FrameworksServicesTests.apk
 * adb shell am instrument -w -e class com.android.server.pm.SELinuxMMACTests com.android.frameworks.servicestests/android.test.InstrumentationTestRunner
 * }
 *
 */
public class SELinuxMMACTests extends AndroidTestCase {

    private static final String TAG = "SELinuxMMACTests";

    private static File MAC_INSTALL_TMP;
    private static File APK_INSTALL_TMP;

    private static final String MAC_INSTALL_TMP_NAME = "macperms_test_policy";
    private static final String APK_INSTALL_TMP_NAME = "test_install.apk";

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        // Use the test apps data directory as scratch space
        File filesDir = mContext.getFilesDir();
        assertNotNull(filesDir);

        // Need a tmp file to hold mmac policy
        MAC_INSTALL_TMP = new File(filesDir, MAC_INSTALL_TMP_NAME);

        // Need a tmp file to hold the apk
        APK_INSTALL_TMP = new File(filesDir, APK_INSTALL_TMP_NAME);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        // Just in case tmp files still exist
        MAC_INSTALL_TMP.delete();
        APK_INSTALL_TMP.delete();
    }

    /**
     * Fake an app install. Simply call the PackageParser to parse and save the
     * contents of the app.
     */
    private PackageParser.Package parsePackage(Uri packageURI) {
        // Package archive parsing
        String archiveFilePath = packageURI.getPath();
        PackageParser packageParser = new PackageParser(archiveFilePath);
        File sourceFile = new File(archiveFilePath);
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        PackageParser.Package pkg = packageParser.parsePackage(sourceFile,
                archiveFilePath, metrics, 0);
        assertNotNull(pkg);
        assertNotNull(pkg.packageName);

        // Collect the certs for this package
        boolean savedCerts = packageParser.collectCertificates(pkg, 0);
        assertTrue(savedCerts);

        return pkg;
    }

    /**
     * Dump the contents of a resource to a file. This is just an ancillary function
     * used for copying the apk and mac_permissions.xml policy files.
     */
    private Uri getResourceURI(int fileResId, File outFile) {
        try (InputStream is = mContext.getResources().openRawResource(fileResId)) {
            boolean copied = FileUtils.copyToFile(is, outFile);
            assertTrue(copied);
        } catch (NotFoundException | IOException ex) {
            fail("Expecting to load resource with id: " + fileResId + ". " + ex);
        }

        return Uri.fromFile(outFile);
    }

    /**
     * Takes the policy xml file as a resource, the apk as a resource and the expected
     * seinfo string. Determines if the assigned seinfo string matches the passed string.
     */
    private void checkSeinfo(int policyRes, int apkRes, String expectedSeinfo) {
        // Grab policy file as a uri
        Uri policyURI = getResourceURI(policyRes, MAC_INSTALL_TMP);

        // Parse the policy file
        boolean parsed = SELinuxMMAC.readInstallPolicy(policyURI.getPath());
        assertTrue(parsed);

        // Grab the apk as a uri
        Uri apkURI = getResourceURI(apkRes, APK_INSTALL_TMP);

        // "install" the apk
        PackageParser.Package pkg = parsePackage(apkURI);

        // Assign the apk an seinfo value
        SELinuxMMAC.assignSeinfoValue(pkg);

        // Check for expected seinfo against assigned seinfo value
        String actualSeinfo = pkg.applicationInfo.seinfo;
        if (expectedSeinfo == null) {
            assertNull(actualSeinfo);
        } else {
            assertEquals(expectedSeinfo, actualSeinfo);
        }

        // delete policy and apk
        MAC_INSTALL_TMP.delete();
        APK_INSTALL_TMP.delete();
    }

    /*
     * Start of the SElinuxMMAC tests
     */

    // Requested policy file doesn't exist
    public void test_INSTALL_POLICY_BADPATH() {
        boolean ret = SELinuxMMAC.readInstallPolicy("/d/o/e/s/n/t/e/x/i/s/t");
        assertFalse(ret);
    }

    /*
     * Raw resource xml file names can be decoded with:
     *  c = signature stanza included
     *  s = seinfo tag attached
     *  p = package tag attached
     *  d = default stanza included
     *  n = means the next abbreviation is missing
     *
     * Example: R.raw.mmac_csps_ds.xml would translate to a signer stanza
     * with a seinfo tag attached followed by an inner child package tag which
     * has an seinfo tag. Also, there is a default stanza with an attached
     * seinfo tag.
     */

    // signer stanza (seinfo, no package), no default stanza : match signer
    public void test_CSNP_ND() {
        checkSeinfo(R.raw.mmac_csnp_nd, R.raw.signed_platform, "signer");
    }

    // signer stanza (seinfo, no package), no default stanza : match nothing
    public void test_CSNP_ND_2() {
        checkSeinfo(R.raw.mmac_csnp_nd, R.raw.signed_release, null);
    }

    // signer stanza (seinfo, package), no default stanza : match inner package
    public void test_CSPS_ND() {
        checkSeinfo(R.raw.mmac_csps_nd, R.raw.signed_platform, "package");
    }

    // signer stanza (seinfo, package), no default stanza : match nothing
    public void test_CSPS_ND_2() {
        checkSeinfo(R.raw.mmac_csps_nd, R.raw.signed_release, null);
    }

    // signer stanza (no seinfo, package), no default stanza : match inner package
    public void test_CNSPS_ND() {
        checkSeinfo(R.raw.mmac_cnsps_nd, R.raw.signed_platform, "package");
    }

    // signer stanza (no seinfo, package), no default stanza : match nothing
    public void test_CNSPS_ND_2() {
        checkSeinfo(R.raw.mmac_cnsps_nd, R.raw.signed_release, null);
    }

    // signer stanza (seinfo, no package), default stanza : match signer
    public void test_CSNP_DS() {
        checkSeinfo(R.raw.mmac_csnp_ds, R.raw.signed_platform, "signer");
    }

    // signer stanza (seinfo, no package), default stanza : match default
    public void test_CSNP_DS_2() {
        checkSeinfo(R.raw.mmac_csnp_ds, R.raw.signed_release, "default");
    }

    // signer stanza (seinfo, package), default stanza : match inner package
    public void test_CSPS_DS() {
        checkSeinfo(R.raw.mmac_csps_ds, R.raw.signed_platform, "package");
    }

    // signer stanza (seinfo, package), default stanza : match default
    public void test_CSPS_DS_2() {
        checkSeinfo(R.raw.mmac_csps_ds, R.raw.signed_release, "default");
    }

    // signer stanza (no seinfo, package), default stanza : match inner package
    public void test_CNSPS_DS() {
        checkSeinfo(R.raw.mmac_cnsps_ds, R.raw.signed_platform, "package");
    }

    // signer stanza (no seinfo, package), default stanza : match default
    public void test_CNSPS_DS_2() {
        checkSeinfo(R.raw.mmac_cnsps_ds, R.raw.signed_release, "default");
    }

    // no signer stanza, default stanza : match default
    public void test_NC_DS() {
        checkSeinfo(R.raw.mmac_nc_ds, R.raw.signed_platform, "default");
    }

    // Test for empty policy (i.e. no stanzas at all) : match nothing
    public void test_NC_ND() {
        checkSeinfo(R.raw.mmac_nc_nd, R.raw.signed_platform, null);
    }
}
