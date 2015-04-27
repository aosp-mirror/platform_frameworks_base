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

package com.android.server.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.content.Context;
import android.content.pm.PackageParser;
import android.test.AndroidTestCase;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;

import com.android.internal.os.AtomicFile;

import java.lang.reflect.Constructor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.PublicKey;

public class PackageManagerSettingsTests extends AndroidTestCase {
    private static final String PACKAGE_NAME_2 = "com.google.app2";
    private static final String PACKAGE_NAME_3 = "com.android.app3";
    private static final String PACKAGE_NAME_1 = "com.google.app1";
    private static final boolean localLOGV = true;
    public static final String TAG = "PackageManagerSettingsTests";
    protected final String PREFIX = "android.content.pm";

    private void writeFile(File file, byte[] data) {
        file.mkdirs();
        try {
            AtomicFile aFile = new AtomicFile(file);
            FileOutputStream fos = aFile.startWrite();
            fos.write(data);
            aFile.finishWrite(fos);
        } catch (IOException ioe) {
            Log.e(TAG, "Cannot write file " + file.getPath());
        }
    }

    private void writePackagesXml() {
        writeFile(new File(getContext().getFilesDir(), "system/packages.xml"),
                ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<packages>"
                + "<last-platform-version internal=\"15\" external=\"0\" fingerprint=\"foo\" />"
                + "<permission-trees>"
                + "<item name=\"com.google.android.permtree\" package=\"com.google.android.permpackage\" />"
                + "</permission-trees>"
                + "<permissions>"
                + "<item name=\"android.permission.WRITE_CALL_LOG\" package=\"android\" protection=\"1\" />"
                + "<item name=\"android.permission.ASEC_ACCESS\" package=\"android\" protection=\"2\" />"
                + "<item name=\"android.permission.ACCESS_WIMAX_STATE\" package=\"android\" />"
                + "<item name=\"android.permission.REBOOT\" package=\"android\" protection=\"18\" />"
                + "</permissions>"
                + "<package name=\"com.google.app1\" codePath=\"/system/app/app1.apk\" nativeLibraryPath=\"/data/data/com.google.app1/lib\" flags=\"1\" ft=\"1360e2caa70\" it=\"135f2f80d08\" ut=\"1360e2caa70\" version=\"1109\" sharedUserId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" key=\"" + KeySetStrings.ctsKeySetCertA + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "</package>"
                + "<package name=\"com.google.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.google.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"1\" />"
                + "<defined-keyset alias=\"AB\" identifier=\"4\" />"
                + "</package>"
                + "<package name=\"com.android.app3\" codePath=\"/system/app/app3.apk\" nativeLibraryPath=\"/data/data/com.android.app3/lib\" flags=\"1\" ft=\"1360e577b60\" it=\"135f2f80d08\" ut=\"1360e577b60\" version=\"15\" userId=\"11030\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" key=\"" + KeySetStrings.ctsKeySetCertB + "\" />"
                + "</sigs>"
                + "<proper-signing-keyset identifier=\"2\" />"
                + "<upgrade-keyset identifier=\"3\" />"
                + "<defined-keyset alias=\"C\" identifier=\"3\" />"
                + "</package>"
                + "<shared-user name=\"com.android.shared1\" userId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" />"
                + "</sigs>"
                + "<perms>"
                + "<item name=\"android.permission.REBOOT\" />"
                + "</perms>"
                + "</shared-user>"
                + "<keyset-settings version=\"1\">"
                + "<keys>"
                + "<public-key identifier=\"1\" value=\"" + KeySetStrings.ctsKeySetPublicKeyA + "\" />"
                + "<public-key identifier=\"2\" value=\"" + KeySetStrings.ctsKeySetPublicKeyB + "\" />"
                + "<public-key identifier=\"3\" value=\"" + KeySetStrings.ctsKeySetPublicKeyC + "\" />"
                + "</keys>"
                + "<keysets>"
                + "<keyset identifier=\"1\">"
                + "<key-id identifier=\"1\" />"
                + "</keyset>"
                + "<keyset identifier=\"2\">"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "<keyset identifier=\"3\">"
                + "<key-id identifier=\"3\" />"
                + "</keyset>"
                + "<keyset identifier=\"4\">"
                + "<key-id identifier=\"1\" />"
                + "<key-id identifier=\"2\" />"
                + "</keyset>"
                + "</keysets>"
                + "<lastIssuedKeyId value=\"3\" />"
                + "<lastIssuedKeySetId value=\"4\" />"
                + "</keyset-settings>"
                + "</packages>").getBytes());
    }

    private void writeStoppedPackagesXml() {
        writeFile(new File(getContext().getFilesDir(), "system/packages-stopped.xml"),
                ( "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>"
                + "<stopped-packages>"
                + "<pkg name=\"com.google.app1\" nl=\"1\" />"
                + "<pkg name=\"com.android.app3\" nl=\"1\" />"
                + "</stopped-packages>")
                .getBytes());
    }

    private void writePackagesList() {
        writeFile(new File(getContext().getFilesDir(), "system/packages.list"),
                ( "com.google.app1 11000 0 /data/data/com.google.app1 seinfo1"
                + "com.google.app2 11001 0 /data/data/com.google.app2 seinfo2"
                + "com.android.app3 11030 0 /data/data/com.android.app3 seinfo3")
                .getBytes());
    }

    private void deleteSystemFolder() {
        File systemFolder = new File(getContext().getFilesDir(), "system");
        deleteFolder(systemFolder);
    }

    private static void deleteFolder(File folder) {
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                deleteFolder(file);
            }
        }
        folder.delete();
    }

    private void writeOldFiles() {
        deleteSystemFolder();
        writePackagesXml();
        writeStoppedPackagesXml();
        writePackagesList();
    }

    private void createUserManagerServiceRef() throws ReflectiveOperationException {
        Constructor<UserManagerService> umsc =
                UserManagerService.class.getDeclaredConstructor(
                        Context.class,
                        PackageManagerService.class,
                        Object.class,
                        Object.class,
                        File.class,
                        File.class);
        umsc.setAccessible(true);
        UserManagerService ums = umsc.newInstance(getContext(), null,
                new Object(), new Object(), getContext().getFilesDir(),
                new File(getContext().getFilesDir(), "user"));
    }

    private void verifyKeySetMetaData(Settings settings)
            throws ReflectiveOperationException, IllegalAccessException {
        ArrayMap<String, PackageSetting> packages = settings.mPackages;
        KeySetManagerService ksms = settings.mKeySetManagerService;

        /* verify keyset and public key ref counts */
        assertEquals(2, KeySetUtils.getKeySetRefCount(ksms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(ksms, 2));
        assertEquals(1, KeySetUtils.getKeySetRefCount(ksms, 3));
        assertEquals(1, KeySetUtils.getKeySetRefCount(ksms, 4));
        assertEquals(2, KeySetUtils.getPubKeyRefCount(ksms, 1));
        assertEquals(2, KeySetUtils.getPubKeyRefCount(ksms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(ksms, 3));

        /* verify public keys properly read */
        PublicKey keyA = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = PackageParser.parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);
        assertEquals(keyA, KeySetUtils.getPubKey(ksms, 1));
        assertEquals(keyB, KeySetUtils.getPubKey(ksms, 2));
        assertEquals(keyC, KeySetUtils.getPubKey(ksms, 3));

        /* verify mapping is correct (ks -> pub keys) */
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(ksms);
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        mapping = ksMapping.get(3);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(3)));
        mapping = ksMapping.get(4);
        assertEquals(2, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertTrue(mapping.contains(new Long(2)));

        /* verify lastIssuedIds are consistent */
        assertEquals(new Long(3), KeySetUtils.getLastIssuedKeyId(ksms));
        assertEquals(new Long(4), KeySetUtils.getLastIssuedKeySetId(ksms));

        /* verify packages have been given the appropriat information */
        PackageSetting ps = packages.get("com.google.app1");
        assertEquals(1, ps.keySetData.getProperSigningKeySet());
        ps = packages.get("com.google.app2");
        assertEquals(1, ps.keySetData.getProperSigningKeySet());
        assertEquals(new Long(4), ps.keySetData.getAliases().get("AB"));
        ps = packages.get("com.android.app3");
        assertEquals(2, ps.keySetData.getProperSigningKeySet());
        assertEquals(new Long(3), ps.keySetData.getAliases().get("C"));
        assertEquals(1, ps.keySetData.getUpgradeKeySets().length);
        assertEquals(3, ps.keySetData.getUpgradeKeySets()[0]);
    }

    /* make sure our initialized keysetmanagerservice metadata matches packages.xml */
    public void testReadKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {

        /* write out files and read */
        writeOldFiles();
        createUserManagerServiceRef();
        Settings settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));
        verifyKeySetMetaData(settings);
    }

    /* read in data, write it out, and read it back in.  Verify same. */
    public void testWriteKeySetSettings()
            throws ReflectiveOperationException, IllegalAccessException {

        /* write out files and read */
        writeOldFiles();
        createUserManagerServiceRef();
        Settings settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));

        /* write out, read back in and verify the same */
        settings.writeLPr();
        assertEquals(true, settings.readLPw(null, null, 0, false));
        verifyKeySetMetaData(settings);
    }

    public void testSettingsReadOld() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));
        assertNotNull(settings.peekPackageLPr(PACKAGE_NAME_3));
        assertNotNull(settings.peekPackageLPr(PACKAGE_NAME_1));

        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_1);
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, ps.getEnabled(0));
        assertEquals(true, ps.getNotLaunched(0));

        ps = settings.peekPackageLPr(PACKAGE_NAME_2);
        assertEquals(false, ps.getStopped(0));
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED_USER, ps.getEnabled(0));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, ps.getEnabled(1));
    }

    public void testNewPackageRestrictionsFile() throws ReflectiveOperationException {

        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        createUserManagerServiceRef();
        Settings settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));
        settings.writeLPr();

        // Create Settings again to make it read from the new files
        settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));

        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_2);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED_USER, ps.getEnabled(0));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, ps.getEnabled(1));
    }

    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = new Settings(getContext().getFilesDir(), new Object());
        assertEquals(true, settings.readLPw(null, null, 0, false));

        // Enable/Disable a package
        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_1);
        ps.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0, null);
        ps.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 1, null);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, ps.getEnabled(0));
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, ps.getEnabled(1));

        // Enable/Disable a component
        ArraySet<String> components = new ArraySet<String>();
        String component1 = PACKAGE_NAME_1 + "/.Component1";
        components.add(component1);
        ps.setDisabledComponents(components, 0);
        ArraySet<String> componentsDisabled = ps.getDisabledComponents(0);
        assertEquals(1, componentsDisabled.size());
        assertEquals(component1, componentsDisabled.toArray()[0]);
        boolean hasEnabled =
                ps.getEnabledComponents(0) != null && ps.getEnabledComponents(1).size() > 0;
        assertEquals(false, hasEnabled);

        // User 1 should not have any disabled components
        boolean hasDisabled =
                ps.getDisabledComponents(1) != null && ps.getDisabledComponents(1).size() > 0;
        assertEquals(false, hasDisabled);
        ps.setEnabledComponents(components, 1);
        assertEquals(1, ps.getEnabledComponents(1).size());
        hasEnabled = ps.getEnabledComponents(0) != null && ps.getEnabledComponents(0).size() > 0;
        assertEquals(false, hasEnabled);
    }
}
