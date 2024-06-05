package com.android.server.backup.fullbackup;

import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_FILENAME;
import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_VERSION;
import static com.android.server.backup.UserBackupManagerService.BACKUP_METADATA_FILENAME;
import static com.android.server.backup.UserBackupManagerService.BACKUP_METADATA_VERSION;
import static com.android.server.backup.UserBackupManagerService.BACKUP_WIDGET_METADATA_TOKEN;

import static com.google.common.truth.Truth.assertThat;

import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.Application;
import android.app.backup.BackupDataInput;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.UserHandle;

import com.android.server.testing.shadows.ShadowBackupDataInput;
import com.android.server.testing.shadows.ShadowBackupDataOutput;
import com.android.server.testing.shadows.ShadowFullBackup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplicationPackageManager;
import org.robolectric.shadows.ShadowEnvironment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;

@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
            ShadowBackupDataInput.class,
            ShadowBackupDataOutput.class,
            ShadowEnvironment.class,
            ShadowFullBackup.class,
            ShadowSigningInfo.class,
        })
public class AppMetadataBackupWriterTest {
    private static final String TEST_PACKAGE = "com.test.package";
    private static final String TEST_PACKAGE_INSTALLER = "com.test.package.installer";
    private static final Long TEST_PACKAGE_VERSION_CODE = 100L;

    private @UserIdInt int mUserId;
    private PackageManager mPackageManager;
    private ShadowApplicationPackageManager mShadowPackageManager;
    private File mFilesDir;
    private File mBackupDataOutputFile;
    private AppMetadataBackupWriter mBackupWriter;

    @Before
    public void setUp() throws Exception {
        Application application = RuntimeEnvironment.application;

        mUserId = UserHandle.USER_SYSTEM;
        mPackageManager = application.getPackageManager();
        mShadowPackageManager = (ShadowApplicationPackageManager) shadowOf(mPackageManager);

        mFilesDir = RuntimeEnvironment.application.getFilesDir();
        mBackupDataOutputFile = new File(mFilesDir, "output");
        mBackupDataOutputFile.createNewFile();
        ParcelFileDescriptor pfd =
                ParcelFileDescriptor.open(
                        mBackupDataOutputFile, ParcelFileDescriptor.MODE_READ_WRITE);
        FullBackupDataOutput output =
                new FullBackupDataOutput(pfd, /* quota */ -1, /* transportFlags */ 0);
        mBackupWriter = new AppMetadataBackupWriter(output, mPackageManager);
    }

    @After
    public void tearDown() throws Exception {
        mBackupDataOutputFile.delete();
    }

    /**
     * The manifest format is:
     *
     * <pre>
     *     BACKUP_MANIFEST_VERSION
     *     package name
     *     package version code
     *     platform version code
     *     installer package name (can be empty)
     *     boolean (1 if archive includes .apk, otherwise 0)
     *     # of signatures N
     *     N* (signature byte array in ascii format per Signature.toCharsString())
     * </pre>
     */
    @Test
    public void testBackupManifest_withoutApkOrSignatures_writesCorrectData() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File manifestFile = createFile(BACKUP_MANIFEST_FILENAME);

        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ false);

        byte[] manifestBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        String[] manifest = new String(manifestBytes, StandardCharsets.UTF_8).split("\n");
        assertThat(manifest.length).isEqualTo(7);
        assertThat(manifest[0]).isEqualTo(Integer.toString(BACKUP_MANIFEST_VERSION));
        assertThat(manifest[1]).isEqualTo(TEST_PACKAGE);
        assertThat(manifest[2]).isEqualTo(Long.toString(TEST_PACKAGE_VERSION_CODE));
        assertThat(manifest[3]).isEqualTo(Integer.toString(Build.VERSION.SDK_INT));
        assertThat(manifest[4]).isEqualTo(TEST_PACKAGE_INSTALLER);
        assertThat(manifest[5]).isEqualTo("0"); // withApk
        assertThat(manifest[6]).isEqualTo("0"); // signatures
        manifestFile.delete();
    }

    /**
     * The manifest format is:
     *
     * <pre>
     *     BACKUP_MANIFEST_VERSION
     *     package name
     *     package version code
     *     platform version code
     *     installer package name (can be empty)
     *     boolean (1 if archive includes .apk, otherwise 0)
     *     # of signatures N
     *     N* (signature byte array in ascii format per Signature.toCharsString())
     * </pre>
     */
    @Test
    public void testBackupManifest_withApk_writesApk() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File manifestFile = createFile(BACKUP_MANIFEST_FILENAME);

        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ true);

        byte[] manifestBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        String[] manifest = new String(manifestBytes, StandardCharsets.UTF_8).split("\n");
        assertThat(manifest.length).isEqualTo(7);
        assertThat(manifest[5]).isEqualTo("1"); // withApk
        manifestFile.delete();
    }

    /**
     * The manifest format is:
     *
     * <pre>
     *     BACKUP_MANIFEST_VERSION
     *     package name
     *     package version code
     *     platform version code
     *     installer package name (can be empty)
     *     boolean (1 if archive includes .apk, otherwise 0)
     *     # of signatures N
     *     N* (signature byte array in ascii format per Signature.toCharsString())
     * </pre>
     */
    @Test
    public void testBackupManifest_withSignatures_writesCorrectSignatures() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        packageInfo.signingInfo =
                new SigningInfo(
                        new SigningDetails(
                                new Signature[] {new Signature("1234"), new Signature("5678")},
                                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                                null,
                                null));
        File manifestFile = createFile(BACKUP_MANIFEST_FILENAME);

        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ false);

        byte[] manifestBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        String[] manifest = new String(manifestBytes, StandardCharsets.UTF_8).split("\n");
        assertThat(manifest.length).isEqualTo(9);
        assertThat(manifest[6]).isEqualTo("2"); // # of signatures
        assertThat(manifest[7]).isEqualTo("1234"); // first signature
        assertThat(manifest[8]).isEqualTo("5678"); // second signature
        manifestFile.delete();
    }

    /**
     * The manifest format is:
     *
     * <pre>
     *     BACKUP_MANIFEST_VERSION
     *     package name
     *     package version code
     *     platform version code
     *     installer package name (can be empty)
     *     boolean (1 if archive includes .apk, otherwise 0)
     *     # of signatures N
     *     N* (signature byte array in ascii format per Signature.toCharsString())
     * </pre>
     */
    @Test
    public void testBackupManifest_withoutInstallerPackage_writesEmptyInstaller() throws Exception {
        PackageInfo packageInfo = createPackageInfo(TEST_PACKAGE, null, TEST_PACKAGE_VERSION_CODE);
        File manifestFile = createFile(BACKUP_MANIFEST_FILENAME);

        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ false);

        byte[] manifestBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        String[] manifest = new String(manifestBytes, StandardCharsets.UTF_8).split("\n");
        assertThat(manifest.length).isEqualTo(7);
        assertThat(manifest[4]).isEqualTo(""); // installer package name
        manifestFile.delete();
    }

    @Test
    public void testBackupManifest_whenRunPreviouslyWithSameData_producesSameBytesOnSecondRun()
            throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File manifestFile = createFile(BACKUP_MANIFEST_FILENAME);
        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ false);
        byte[] firstRunBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ true);
        // Simulate modifying the manifest file to ensure that file metadata does not change the
        // backup bytes produced.
        modifyFileMetadata(manifestFile);

        mBackupWriter.backupManifest(packageInfo, manifestFile, mFilesDir, /* withApk */ false);

        byte[] secondRunBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ true);
        assertThat(firstRunBytes).isEqualTo(secondRunBytes);
        manifestFile.delete();
    }

    /**
     * The widget data format with metadata is:
     *
     * <pre>
     *     BACKUP_METADATA_VERSION
     *     package name
     *     4 : Integer token identifying the widget data blob.
     *     4 : Integer size of the widget data.
     *     N : Raw bytes of the widget data.
     * </pre>
     */
    @Test
    public void testBackupWidget_writesCorrectData() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File metadataFile = createFile(BACKUP_METADATA_FILENAME);
        byte[] widgetBytes = "widget".getBytes();

        mBackupWriter.backupWidget(packageInfo, metadataFile, mFilesDir, widgetBytes);

        byte[] writtenBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        String[] widgetData = new String(writtenBytes, StandardCharsets.UTF_8).split("\n");
        assertThat(widgetData.length).isEqualTo(3);
        // Metadata header
        assertThat(widgetData[0]).isEqualTo(Integer.toString(BACKUP_METADATA_VERSION));
        assertThat(widgetData[1]).isEqualTo(packageInfo.packageName);
        // Widget data
        ByteArrayOutputStream expectedBytes = new ByteArrayOutputStream();
        DataOutputStream stream = new DataOutputStream(expectedBytes);
        stream.writeInt(BACKUP_WIDGET_METADATA_TOKEN);
        stream.writeInt(widgetBytes.length);
        stream.write(widgetBytes);
        stream.flush();
        assertThat(widgetData[2]).isEqualTo(expectedBytes.toString());
        metadataFile.delete();
    }

    @Test
    public void testBackupWidget_withNullWidgetData_throwsNullPointerException() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File metadataFile = createFile(BACKUP_METADATA_FILENAME);

        expectThrows(
                NullPointerException.class,
                () ->
                        mBackupWriter.backupWidget(
                                packageInfo, metadataFile, mFilesDir, /* widgetData */ null));

        metadataFile.delete();
    }

    @Test
    public void testBackupWidget_withEmptyWidgetData_throwsIllegalArgumentException()
            throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File metadataFile = createFile(BACKUP_METADATA_FILENAME);

        expectThrows(
                IllegalArgumentException.class,
                () ->
                        mBackupWriter.backupWidget(
                                packageInfo, metadataFile, mFilesDir, new byte[0]));

        metadataFile.delete();
    }

    @Test
    public void testBackupWidget_whenRunPreviouslyWithSameData_producesSameBytesOnSecondRun()
            throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File metadataFile = createFile(BACKUP_METADATA_FILENAME);
        byte[] widgetBytes = "widget".getBytes();
        mBackupWriter.backupWidget(packageInfo, metadataFile, mFilesDir, widgetBytes);
        byte[] firstRunBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ true);
        // Simulate modifying the metadata file to ensure that file metadata does not change the
        // backup bytes produced.
        modifyFileMetadata(metadataFile);

        mBackupWriter.backupWidget(packageInfo, metadataFile, mFilesDir, widgetBytes);

        byte[] secondRunBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ true);
        assertThat(firstRunBytes).isEqualTo(secondRunBytes);
        metadataFile.delete();
    }

    @Test
    public void testBackupApk_writesCorrectBytesToOutput() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        byte[] apkBytes = "apk".getBytes();
        File apkFile = createApkFileAndWrite(apkBytes);
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.sourceDir = apkFile.getPath();

        mBackupWriter.backupApk(packageInfo);

        byte[] writtenBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        assertThat(writtenBytes).isEqualTo(apkBytes);
        apkFile.delete();
    }

    @Test
    public void testBackupObb_withObbData_writesCorrectBytesToOutput() throws Exception {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File obbDir = createObbDirForPackage(packageInfo.packageName);
        byte[] obbBytes = "obb".getBytes();
        File obbFile = createObbFileAndWrite(obbDir, obbBytes);

        mBackupWriter.backupObb(mUserId, packageInfo);

        byte[] writtenBytes = getWrittenBytes(mBackupDataOutputFile, /* includeTarHeader */ false);
        assertThat(writtenBytes).isEqualTo(obbBytes);
        obbFile.delete();
    }

    @Test
    public void testBackupObb_withNoObbData_doesNotWriteBytesToOutput() {
        PackageInfo packageInfo =
                createPackageInfo(TEST_PACKAGE, TEST_PACKAGE_INSTALLER, TEST_PACKAGE_VERSION_CODE);
        File obbDir = createObbDirForPackage(packageInfo.packageName);
        // No obb file created.

        mBackupWriter.backupObb(mUserId, packageInfo);

        assertThat(mBackupDataOutputFile.length()).isEqualTo(0);
    }

    /**
     * Creates a test package and registers it with the package manager. Also sets the installer
     * package name if not {@code null}.
     */
    private PackageInfo createPackageInfo(
            String packageName, @Nullable String installerPackageName, long versionCode) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.setLongVersionCode(versionCode);
        mShadowPackageManager.addPackage(packageInfo);
        if (installerPackageName != null) {
            mPackageManager.setInstallerPackageName(packageName, installerPackageName);
        }
        return packageInfo;
    }

    /**
     * Reads backup data written to the {@code file} by {@link ShadowBackupDataOutput}. Uses {@link
     * ShadowBackupDataInput} to parse the data. Follows the format used by {@link
     * ShadowFullBackup#backupToTar(String, String, String, String, String, FullBackupDataOutput)}.
     *
     * @param includeTarHeader If {@code true}, returns the TAR header and data bytes combined.
     *     Otherwise, only returns the data bytes.
     */
    private byte[] getWrittenBytes(File file, boolean includeTarHeader) throws IOException {
        BackupDataInput input = new BackupDataInput(new FileInputStream(file).getFD());
        input.readNextHeader();
        int dataSize = input.getDataSize();

        byte[] bytes;
        if (includeTarHeader) {
            bytes = new byte[dataSize + 512];
            input.readEntityData(bytes, 0, dataSize + 512);
        } else {
            input.readEntityData(new byte[512], 0, 512); // skip TAR header
            bytes = new byte[dataSize];
            input.readEntityData(bytes, 0, dataSize);
        }

        return bytes;
    }

    private File createFile(String fileName) throws IOException {
        File file = new File(mFilesDir, fileName);
        file.createNewFile();
        return file;
    }

    /**
     * Sets the last modified time of the {@code file} to the current time to edit the file's
     * metadata.
     */
    private void modifyFileMetadata(File file) throws IOException {
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis()));
    }

    private File createApkFileAndWrite(byte[] data) throws IOException {
        File apkFile = new File(mFilesDir, "apk");
        apkFile.createNewFile();
        Files.write(apkFile.toPath(), data);
        return apkFile;
    }

    /** Creates an .obb file in the input directory. */
    private File createObbFileAndWrite(File obbDir, byte[] data) throws IOException {
        File obbFile = new File(obbDir, "obb");
        obbFile.createNewFile();
        Files.write(obbFile.toPath(), data);
        return obbFile;
    }

    /**
     * Creates a package specific obb data directory since the backup method checks for obb data
     * there. See {@link Environment#buildExternalStorageAppObbDirs(String)}.
     */
    private File createObbDirForPackage(String packageName) {
        ShadowEnvironment.addExternalDir("test");
        Environment.UserEnvironment userEnv =
                new Environment.UserEnvironment(UserHandle.USER_SYSTEM);
        File obbDir =
                new File(
                        userEnv.getExternalDirs()[0],
                        Environment.DIR_ANDROID + "/obb/" + packageName);
        obbDir.mkdirs();
        return obbDir;
    }
}
