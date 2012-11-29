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

import com.android.internal.content.PackageHelper;
import com.android.internal.os.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;

import android.os.Debug;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.IMountService;
import android.test.AndroidTestCase;
import android.util.Log;

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
                + "<last-platform-version internal=\"15\" external=\"0\" />"
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
                + "<cert index=\"0\" key=\"308886\" />"
                + "</sigs>"
                + "</package>"
                + "<package name=\"com.google.app2\" codePath=\"/system/app/app2.apk\" nativeLibraryPath=\"/data/data/com.google.app2/lib\" flags=\"1\" ft=\"1360e578718\" it=\"135f2f80d08\" ut=\"1360e578718\" version=\"15\" enabled=\"3\" userId=\"11001\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"0\" />"
                + "</sigs>"
                + "</package>"
                + "<package name=\"com.android.app3\" codePath=\"/system/app/app3.apk\" nativeLibraryPath=\"/data/data/com.android.app3/lib\" flags=\"1\" ft=\"1360e577b60\" it=\"135f2f80d08\" ut=\"1360e577b60\" version=\"15\" userId=\"11030\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" key=\"308366\" />"
                + "</sigs>"
                + "</package>"
                + "<shared-user name=\"com.android.shared1\" userId=\"11000\">"
                + "<sigs count=\"1\">"
                + "<cert index=\"1\" />"
                + "</sigs>"
                + "<perms>"
                + "<item name=\"android.permission.REBOOT\" />"
                + "</perms>"
                + "</shared-user>"
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
                ( "com.google.app1 11000 0 /data/data/com.google.app1"
                + "com.google.app2 11001 0 /data/data/com.google.app2"
                + "com.android.app3 11030 0 /data/data/com.android.app3")
                .getBytes());
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    private void writeOldFiles() {
        writePackagesXml();
        writeStoppedPackagesXml();
        writePackagesList();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    public void testSettingsReadOld() {
        // Debug.waitForDebugger();

        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = new Settings(getContext(), getContext().getFilesDir());
        assertEquals(true, settings.readLPw(null, 0, false));
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

    public void testNewPackageRestrictionsFile() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = new Settings(getContext(), getContext().getFilesDir());
        assertEquals(true, settings.readLPw(null, 0, false));

        // Create Settings again to make it read from the new files
        settings = new Settings(getContext(), getContext().getFilesDir());
        assertEquals(true, settings.readLPw(null, 0, false));

        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_2);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED_USER, ps.getEnabled(0));
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT, ps.getEnabled(1));
    }

    public void testEnableDisable() {
        // Write the package files and make sure they're parsed properly the first time
        writeOldFiles();
        Settings settings = new Settings(getContext(), getContext().getFilesDir());
        assertEquals(true, settings.readLPw(null, 0, false));

        // Enable/Disable a package
        PackageSetting ps = settings.peekPackageLPr(PACKAGE_NAME_1);
        ps.setEnabled(COMPONENT_ENABLED_STATE_DISABLED, 0);
        ps.setEnabled(COMPONENT_ENABLED_STATE_ENABLED, 1);
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED, ps.getEnabled(0));
        assertEquals(COMPONENT_ENABLED_STATE_ENABLED, ps.getEnabled(1));

        // Enable/Disable a component
        HashSet<String> components = new HashSet<String>();
        String component1 = PACKAGE_NAME_1 + "/.Component1";
        components.add(component1);
        ps.setDisabledComponents(components, 0);
        HashSet<String> componentsDisabled = ps.getDisabledComponents(0);
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
