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

package com.android.server.rollback;

import static com.google.common.truth.Truth.assertThat;

import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.util.SparseIntArray;

import com.google.common.truth.Correspondence;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@RunWith(JUnit4.class)
public class RollbackStoreTest {

    private static final int ID = 123;
    private static final int USER = 0;
    private static final String INSTALLER = "some.installer";

    private static final Correspondence<VersionedPackage, VersionedPackage> VER_PKG_CORR =
            Correspondence.from((VersionedPackage a, VersionedPackage b) -> {
                if (a == null || b == null) {
                    return a == b;
                }
                return a.equals(b);
            }, "is the same as");

    private static final Correspondence<PackageRollbackInfo.RestoreInfo,
            PackageRollbackInfo.RestoreInfo>
            RESTORE_INFO_CORR =
            Correspondence.from((PackageRollbackInfo.RestoreInfo a,
                    PackageRollbackInfo.RestoreInfo b) -> {
                if (a == null || b == null) {
                    return a == b;
                }
                return a.userId == b.userId
                        && a.appId == b.appId
                        && Objects.equals(a.seInfo, b.seInfo);
            }, "is the same as");

    private static final String JSON_ROLLBACK_NO_EXT = "{'info':{'rollbackId':123,'packages':"
            + "[{'versionRolledBackFrom':{'packageName':'blah','longVersionCode':55},"
            + "'versionRolledBackTo':{'packageName':'blah1','longVersionCode':50},'pendingBackups':"
            + "[59,1245,124544],'pendingRestores':[{'userId':498,'appId':32322,'seInfo':'wombles'},"
            + "{'userId':-895,'appId':1,'seInfo':'pingu'}],'isApex':false,'isApkInApex':false,"
            + "'installedUsers':"
            + "[498468432,1111,98464],'ceSnapshotInodes':[{'userId':1,'ceSnapshotInode':-6},"
            + "{'userId':2222,'ceSnapshotInode':81641654445},{'userId':546546,"
            + "'ceSnapshotInode':345689375}]},{'versionRolledBackFrom':{'packageName':'chips',"
            + "'longVersionCode':28},'versionRolledBackTo':{'packageName':'com.chips.test',"
            + "'longVersionCode':48},'pendingBackups':[5],'pendingRestores':[{'userId':18,"
            + "'appId':-12,'seInfo':''}],'isApex':false,'isApkInApex':false,"
            + "'installedUsers':[55,79],"
            + "'ceSnapshotInodes':[]}],'isStaged':false,'causePackages':[{'packageName':'hello',"
            + "'longVersionCode':23},{'packageName':'something','longVersionCode':999}],"
            + "'committedSessionId':45654465},'timestamp':'2019-10-01T12:29:08.855Z',"
            + "'originalSessionId':567,'state':'enabling','apkSessionId':-1,"
            + "'restoreUserDataInProgress':true, 'userId':0,"
            + "'installerPackageName':'some.installer'}";

    private static final String JSON_ROLLBACK = "{'info':{'rollbackId':123,'packages':"
            + "[{'versionRolledBackFrom':{'packageName':'blah','longVersionCode':55},"
            + "'versionRolledBackTo':{'packageName':'blah1','longVersionCode':50},'pendingBackups':"
            + "[59,1245,124544],'pendingRestores':[{'userId':498,'appId':32322,'seInfo':'wombles'},"
            + "{'userId':-895,'appId':1,'seInfo':'pingu'}],'isApex':false,'isApkInApex':false,"
            + "'installedUsers':"
            + "[498468432,1111,98464],'ceSnapshotInodes':[{'userId':1,'ceSnapshotInode':-6},"
            + "{'userId':2222,'ceSnapshotInode':81641654445},{'userId':546546,"
            + "'ceSnapshotInode':345689375}]},{'versionRolledBackFrom':{'packageName':'chips',"
            + "'longVersionCode':28},'versionRolledBackTo':{'packageName':'com.chips.test',"
            + "'longVersionCode':48},'pendingBackups':[5],'pendingRestores':[{'userId':18,"
            + "'appId':-12,'seInfo':''}],'isApex':false,'isApkInApex':false,"
            + "'installedUsers':[55,79],"
            + "'ceSnapshotInodes':[]}],'isStaged':false,'causePackages':[{'packageName':'hello',"
            + "'longVersionCode':23},{'packageName':'something','longVersionCode':999}],"
            + "'committedSessionId':45654465},'timestamp':'2019-10-01T12:29:08.855Z',"
            + "'originalSessionId':567,'state':'enabling','apkSessionId':-1,"
            + "'restoreUserDataInProgress':true, 'userId':0,"
            + "'installerPackageName':'some.installer',"
            + "'extensionVersions':[{'sdkVersion':5,'extensionVersion':25},"
            + "{'sdkVersion':30,'extensionVersion':71}]}";

    @Rule
    public TemporaryFolder mFolder = new TemporaryFolder();
    @Rule
    public TemporaryFolder mHistoryDir = new TemporaryFolder();

    private File mRollbackDir;

    private RollbackStore mRollbackStore;

    @Before
    public void setUp() throws Exception {
        mRollbackStore = new RollbackStore(mFolder.getRoot(), mHistoryDir.getRoot());
        mRollbackDir = mFolder.newFolder(ID + "");
        mFolder.newFile("rollback.json");
    }

    @Test
    public void createNonStaged() {
        SparseIntArray extensionVersions = new SparseIntArray();
        extensionVersions.put(30, 71);
        Rollback rollback = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, extensionVersions);

        assertThat(rollback.getBackupDir().getAbsolutePath())
                .isEqualTo(mFolder.getRoot().getAbsolutePath() + "/" + ID);

        assertThat(rollback.isStaged()).isFalse();
        assertThat(rollback.getOriginalSessionId()).isEqualTo(567);
        assertThat(rollback.info.getRollbackId()).isEqualTo(ID);
        assertThat(rollback.info.getPackages()).isEmpty();
        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getExtensionVersions().toString())
                .isEqualTo(extensionVersions.toString());
    }

    @Test
    public void createStaged() {
        SparseIntArray extensionVersions = new SparseIntArray();
        extensionVersions.put(30, 71);
        Rollback rollback = mRollbackStore.createStagedRollback(
                ID, 897, USER, INSTALLER, null, extensionVersions);

        assertThat(rollback.getBackupDir().getAbsolutePath())
                .isEqualTo(mFolder.getRoot().getAbsolutePath() + "/" + ID);

        assertThat(rollback.isStaged()).isTrue();
        assertThat(rollback.getOriginalSessionId()).isEqualTo(897);

        assertThat(rollback.info.getRollbackId()).isEqualTo(ID);
        assertThat(rollback.info.getPackages()).isEmpty();
        assertThat(rollback.isEnabling()).isTrue();
        assertThat(rollback.getExtensionVersions().toString())
                .isEqualTo(extensionVersions.toString());
    }

    @Test
    public void saveAndLoadRollback() {
        SparseIntArray extensionVersions = new SparseIntArray();
        extensionVersions.put(5, 25);
        extensionVersions.put(30, 71);
        Rollback origRb = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, extensionVersions);

        origRb.setRestoreUserDataInProgress(true);
        origRb.info.getCausePackages().add(new VersionedPackage("com.made.up", 2));
        origRb.info.getCausePackages().add(new VersionedPackage("com.pack.age", 99));
        origRb.info.setCommittedSessionId(123456);

        PackageRollbackInfo pkgInfo1 =
                new PackageRollbackInfo(new VersionedPackage("com.made.up", 18),
                        new VersionedPackage("com.something.else", 5), new ArrayList<>(),
                        new ArrayList<>(), false, false, new ArrayList<>());
        pkgInfo1.getPendingBackups().add(8);
        pkgInfo1.getPendingBackups().add(888);
        pkgInfo1.getPendingBackups().add(88885);

        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(4980, 3442322, "seInfo"));
        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(-89, 15, "otherSeInfo"));

        pkgInfo1.getSnapshottedUsers().add(11);
        pkgInfo1.getSnapshottedUsers().add(1);
        pkgInfo1.getSnapshottedUsers().add(0);

        PackageRollbackInfo pkgInfo2 = new PackageRollbackInfo(
                new VersionedPackage("another.package", 2),
                new VersionedPackage("com.test.ing", 48888), new ArrayList<>(), new ArrayList<>(),
                false, false, new ArrayList<>());
        pkgInfo2.getPendingBackups().add(57);

        pkgInfo2.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(180, -120, ""));

        origRb.info.getPackages().add(pkgInfo1);
        origRb.info.getPackages().add(pkgInfo2);

        origRb.setState(Rollback.ROLLBACK_STATE_AVAILABLE, "hello world");

        RollbackStore.saveRollback(origRb);

        List<Rollback> loadedRollbacks = mRollbackStore.loadRollbacks();
        assertThat(loadedRollbacks).hasSize(1);
        Rollback loadedRb = loadedRollbacks.get(0);

        assertRollbacksAreEquivalent(loadedRb, origRb);
    }

    @Test
    public void loadFromJsonNoExtensionVersions() throws Exception {
        Rollback expectedRb = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, new SparseIntArray(0));

        expectedRb.setTimestamp(Instant.parse("2019-10-01T12:29:08.855Z"));
        expectedRb.setRestoreUserDataInProgress(true);
        expectedRb.info.getCausePackages().add(new VersionedPackage("hello", 23));
        expectedRb.info.getCausePackages().add(new VersionedPackage("something", 999));
        expectedRb.info.setCommittedSessionId(45654465);

        PackageRollbackInfo pkgInfo1 = new PackageRollbackInfo(new VersionedPackage("blah", 55),
                new VersionedPackage("blah1", 50), new ArrayList<>(), new ArrayList<>(),
                false, false, new ArrayList<>());
        pkgInfo1.getPendingBackups().add(59);
        pkgInfo1.getPendingBackups().add(1245);
        pkgInfo1.getPendingBackups().add(124544);

        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(498, 32322, "wombles"));
        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(-895, 1, "pingu"));

        pkgInfo1.getSnapshottedUsers().add(498468432);
        pkgInfo1.getSnapshottedUsers().add(1111);
        pkgInfo1.getSnapshottedUsers().add(98464);

        PackageRollbackInfo pkgInfo2 = new PackageRollbackInfo(new VersionedPackage("chips", 28),
                new VersionedPackage("com.chips.test", 48), new ArrayList<>(), new ArrayList<>(),
                false, false, new ArrayList<>());
        pkgInfo2.getPendingBackups().add(5);

        pkgInfo2.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(18, -12, ""));

        pkgInfo2.getSnapshottedUsers().add(55);
        pkgInfo2.getSnapshottedUsers().add(79);

        expectedRb.info.getPackages().add(pkgInfo1);
        expectedRb.info.getPackages().add(pkgInfo2);

        Rollback parsedRb = RollbackStore.rollbackFromJson(
                new JSONObject(JSON_ROLLBACK_NO_EXT), expectedRb.getBackupDir());

        assertRollbacksAreEquivalent(parsedRb, expectedRb);
    }

    @Test
    public void loadFromJson() throws Exception {
        SparseIntArray extensionVersions = new SparseIntArray();
        extensionVersions.put(5, 25);
        extensionVersions.put(30, 71);
        Rollback expectedRb = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, extensionVersions);

        expectedRb.setTimestamp(Instant.parse("2019-10-01T12:29:08.855Z"));
        expectedRb.setRestoreUserDataInProgress(true);
        expectedRb.info.getCausePackages().add(new VersionedPackage("hello", 23));
        expectedRb.info.getCausePackages().add(new VersionedPackage("something", 999));
        expectedRb.info.setCommittedSessionId(45654465);

        PackageRollbackInfo pkgInfo1 = new PackageRollbackInfo(new VersionedPackage("blah", 55),
                new VersionedPackage("blah1", 50), new ArrayList<>(), new ArrayList<>(),
                false, false, new ArrayList<>());
        pkgInfo1.getPendingBackups().add(59);
        pkgInfo1.getPendingBackups().add(1245);
        pkgInfo1.getPendingBackups().add(124544);

        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(498, 32322, "wombles"));
        pkgInfo1.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(-895, 1, "pingu"));

        pkgInfo1.getSnapshottedUsers().add(498468432);
        pkgInfo1.getSnapshottedUsers().add(1111);
        pkgInfo1.getSnapshottedUsers().add(98464);

        PackageRollbackInfo pkgInfo2 = new PackageRollbackInfo(new VersionedPackage("chips", 28),
                new VersionedPackage("com.chips.test", 48), new ArrayList<>(), new ArrayList<>(),
                false, false, new ArrayList<>());
        pkgInfo2.getPendingBackups().add(5);

        pkgInfo2.getPendingRestores().add(
                new PackageRollbackInfo.RestoreInfo(18, -12, ""));

        pkgInfo2.getSnapshottedUsers().add(55);
        pkgInfo2.getSnapshottedUsers().add(79);

        expectedRb.info.getPackages().add(pkgInfo1);
        expectedRb.info.getPackages().add(pkgInfo2);

        Rollback parsedRb = RollbackStore.rollbackFromJson(
                new JSONObject(JSON_ROLLBACK), expectedRb.getBackupDir());

        assertRollbacksAreEquivalent(parsedRb, expectedRb);
    }

    @Test
    public void saveAndDelete() {
        Rollback rollback = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, new SparseIntArray(0));

        RollbackStore.saveRollback(rollback);

        File expectedFile = new File(mRollbackDir.getAbsolutePath() + "/rollback.json");

        assertThat(expectedFile.exists()).isTrue();

        RollbackStore.deleteRollback(rollback);

        assertThat(expectedFile.exists()).isFalse();
    }

    @Test
    public void saveToHistoryAndLoad() {
        Rollback origRb = mRollbackStore.createNonStagedRollback(
                ID, 567, USER, INSTALLER, null, new SparseIntArray(0));
        mRollbackStore.saveRollbackToHistory(origRb);

        List<Rollback> loadedRollbacks = mRollbackStore.loadHistorialRollbacks();
        assertThat(loadedRollbacks).hasSize(1);
        Rollback loadedRb = loadedRollbacks.get(0);

        assertRollbacksAreEquivalentExcludingBackupDir(loadedRb, origRb);
    }

    private void assertRollbacksAreEquivalent(Rollback b, Rollback a) {
        assertThat(b.getBackupDir()).isEqualTo(a.getBackupDir());
        assertRollbacksAreEquivalentExcludingBackupDir(b, a);
    }

    private void assertRollbacksAreEquivalentExcludingBackupDir(Rollback b, Rollback a) {
        assertThat(b.info.getRollbackId()).isEqualTo(ID);

        assertThat(b.isRestoreUserDataInProgress())
                .isEqualTo(a.isRestoreUserDataInProgress());

        assertThat(b.getTimestamp()).isEqualTo(a.getTimestamp());

        assertThat(b.isEnabling()).isEqualTo(a.isEnabling());
        assertThat(b.isAvailable()).isEqualTo(a.isAvailable());
        assertThat(b.isCommitted()).isEqualTo(a.isCommitted());
        assertThat(b.getStateDescription()).isEqualTo(a.getStateDescription());

        assertThat(b.isStaged()).isEqualTo(a.isStaged());

        assertThat(b.getApexPackageNames())
                .containsExactlyElementsIn(a.getApexPackageNames());

        assertThat(b.getOriginalSessionId()).isEqualTo(a.getOriginalSessionId());

        assertThat(b.info.getCommittedSessionId()).isEqualTo(a.info.getCommittedSessionId());

        assertThat(b.info.getCausePackages()).comparingElementsUsing(VER_PKG_CORR)
                .containsExactlyElementsIn(a.info.getCausePackages());

        assertThat(b.info.getPackages()).hasSize(a.info.getPackages().size());

        for (int i = 0; i < b.info.getPackages().size(); i++) {
            assertPackageRollbacksAreEquivalent(
                    b.info.getPackages().get(i), a.info.getPackages().get(i));
        }

        assertThat(a.getUserId()).isEqualTo(b.getUserId());
        assertThat(a.getInstallerPackageName()).isEqualTo(b.getInstallerPackageName());

        if (a.getExtensionVersions() == null) {
            assertThat(b.getExtensionVersions()).isNull();
        } else {
            assertThat(b.getExtensionVersions().toString())
                    .isEqualTo(a.getExtensionVersions().toString());
        }
    }

    private void assertPackageRollbacksAreEquivalent(PackageRollbackInfo b, PackageRollbackInfo a) {
        assertThat(b.getPackageName()).isEqualTo(a.getPackageName());

        assertThat(b.getVersionRolledBackFrom()).isEqualTo(a.getVersionRolledBackFrom());
        assertThat(b.getVersionRolledBackTo()).isEqualTo(a.getVersionRolledBackTo());

        assertThat(b.getPendingBackups().toArray()).isEqualTo(a.getPendingBackups().toArray());

        assertThat(b.getPendingRestores()).comparingElementsUsing(RESTORE_INFO_CORR)
                .containsExactlyElementsIn(a.getPendingRestores());

        assertThat(b.isApex()).isEqualTo(a.isApex());

        assertThat(b.getSnapshottedUsers().toArray()).isEqualTo(a.getSnapshottedUsers().toArray());
    }

}
