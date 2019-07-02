package com.android.server.backup.fullbackup;

import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_VERSION;
import static com.android.server.backup.UserBackupManagerService.BACKUP_METADATA_VERSION;
import static com.android.server.backup.UserBackupManagerService.BACKUP_WIDGET_METADATA_TOKEN;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.util.StringBuilderPrinter;

import com.android.internal.util.Preconditions;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Writes the backup of app-specific metadata to {@link FullBackupDataOutput}. This data is not
 * backed up by the app's backup agent and is written before the agent writes its own data. This
 * includes the app's:
 *
 * <ul>
 *   <li>manifest
 *   <li>widget data
 *   <li>apk
 *   <li>obb content
 * </ul>
 */
// TODO(b/113807190): Fix or remove apk and obb implementation (only used for adb).
public class AppMetadataBackupWriter {
    private final FullBackupDataOutput mOutput;
    private final PackageManager mPackageManager;

    /** The destination of the backup is specified by {@code output}. */
    public AppMetadataBackupWriter(FullBackupDataOutput output, PackageManager packageManager) {
        mOutput = output;
        mPackageManager = packageManager;
    }

    /**
     * Back up the app's manifest without specifying a pseudo-directory for the TAR stream.
     *
     * @see #backupManifest(PackageInfo, File, File, String, String, boolean)
     */
    public void backupManifest(
            PackageInfo packageInfo, File manifestFile, File filesDir, boolean withApk)
            throws IOException {
        backupManifest(
                packageInfo,
                manifestFile,
                filesDir,
                /* domain */ null,
                /* linkDomain */ null,
                withApk);
    }

    /**
     * Back up the app's manifest.
     *
     * <ol>
     *   <li>Write the app's manifest data to the specified temporary file {@code manifestFile}.
     *   <li>Backup the file in TAR format to the backup destination {@link #mOutput}.
     * </ol>
     *
     * <p>Note: {@code domain} and {@code linkDomain} are only used by adb to specify a
     * pseudo-directory for the TAR stream.
     */
    // TODO(b/113806991): Look into streaming the backup data directly.
    public void backupManifest(
            PackageInfo packageInfo,
            File manifestFile,
            File filesDir,
            @Nullable String domain,
            @Nullable String linkDomain,
            boolean withApk)
            throws IOException {
        byte[] manifestBytes = getManifestBytes(packageInfo, withApk);
        FileOutputStream outputStream = new FileOutputStream(manifestFile);
        outputStream.write(manifestBytes);
        outputStream.close();

        // We want the manifest block in the archive stream to be constant each time we generate
        // a backup stream for the app. However, the underlying TAR mechanism sees it as a file and
        // will propagate its last modified time. We pin the last modified time to zero to prevent
        // the TAR header from varying.
        manifestFile.setLastModified(0);

        FullBackup.backupToTar(
                packageInfo.packageName,
                domain,
                linkDomain,
                filesDir.getAbsolutePath(),
                manifestFile.getAbsolutePath(),
                mOutput);
    }

    /**
     * Gets the app's manifest as a byte array. All data are strings ending in LF.
     *
     * <p>The manifest format is:
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
    private byte[] getManifestBytes(PackageInfo packageInfo, boolean withApk) {
        String packageName = packageInfo.packageName;
        StringBuilder builder = new StringBuilder(4096);
        StringBuilderPrinter printer = new StringBuilderPrinter(builder);

        printer.println(Integer.toString(BACKUP_MANIFEST_VERSION));
        printer.println(packageName);
        printer.println(Long.toString(packageInfo.getLongVersionCode()));
        printer.println(Integer.toString(Build.VERSION.SDK_INT));

        String installerName = mPackageManager.getInstallerPackageName(packageName);
        printer.println((installerName != null) ? installerName : "");

        printer.println(withApk ? "1" : "0");

        // Write the signature block.
        SigningInfo signingInfo = packageInfo.signingInfo;
        if (signingInfo == null) {
            printer.println("0");
        } else {
            // Retrieve the newest signatures to write.
            // TODO (b/73988180) use entire signing history in case of rollbacks.
            Signature[] signatures = signingInfo.getApkContentsSigners();
            printer.println(Integer.toString(signatures.length));
            for (Signature sig : signatures) {
                printer.println(sig.toCharsString());
            }
        }
        return builder.toString().getBytes();
    }

    /**
     * Backup specified widget data. The widget data is prefaced by a metadata header.
     *
     * <ol>
     *   <li>Write a metadata header to the specified temporary file {@code metadataFile}.
     *   <li>Write widget data bytes to the same file.
     *   <li>Backup the file in TAR format to the backup destination {@link #mOutput}.
     * </ol>
     *
     * @throws IllegalArgumentException if the widget data provided is empty.
     */
    // TODO(b/113806991): Look into streaming the backup data directly.
    public void backupWidget(
            PackageInfo packageInfo, File metadataFile, File filesDir, byte[] widgetData)
            throws IOException {
        Preconditions.checkArgument(widgetData.length > 0, "Can't backup widget with no data.");

        String packageName = packageInfo.packageName;
        FileOutputStream fileOutputStream = new FileOutputStream(metadataFile);
        BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(fileOutputStream);
        DataOutputStream dataOutputStream = new DataOutputStream(bufferedOutputStream);

        byte[] metadata = getMetadataBytes(packageName);
        bufferedOutputStream.write(metadata); // bypassing DataOutputStream
        writeWidgetData(dataOutputStream, widgetData);
        bufferedOutputStream.flush();
        dataOutputStream.close();

        // As with the manifest file, guarantee consistency of the archive metadata for the widget
        // block by using a fixed last modified time on the metadata file.
        metadataFile.setLastModified(0);

        FullBackup.backupToTar(
                packageName,
                /* domain */ null,
                /* linkDomain */ null,
                filesDir.getAbsolutePath(),
                metadataFile.getAbsolutePath(),
                mOutput);
    }

    /**
     * Gets the app's metadata as a byte array. All entries are strings ending in LF.
     *
     * <p>The metadata format is:
     *
     * <pre>
     *     BACKUP_METADATA_VERSION
     *     package name
     * </pre>
     */
    private byte[] getMetadataBytes(String packageName) {
        StringBuilder builder = new StringBuilder(512);
        StringBuilderPrinter printer = new StringBuilderPrinter(builder);
        printer.println(Integer.toString(BACKUP_METADATA_VERSION));
        printer.println(packageName);
        return builder.toString().getBytes();
    }

    /**
     * Write a byte array of widget data to the specified output stream. All integers are binary in
     * network byte order.
     *
     * <p>The widget data format:
     *
     * <pre>
     *     4 : Integer token identifying the widget data blob.
     *     4 : Integer size of the widget data.
     *     N : Raw bytes of the widget data.
     * </pre>
     */
    private void writeWidgetData(DataOutputStream out, byte[] widgetData) throws IOException {
        out.writeInt(BACKUP_WIDGET_METADATA_TOKEN);
        out.writeInt(widgetData.length);
        out.write(widgetData);
    }

    /**
     * Backup the app's .apk to the backup destination {@link #mOutput}. Currently only used for
     * 'adb backup'.
     */
    // TODO(b/113807190): Investigate and potentially remove.
    public void backupApk(PackageInfo packageInfo) {
        // TODO: handle backing up split APKs
        String appSourceDir = packageInfo.applicationInfo.getBaseCodePath();
        String apkDir = new File(appSourceDir).getParent();
        FullBackup.backupToTar(
                packageInfo.packageName,
                FullBackup.APK_TREE_TOKEN,
                /* linkDomain */ null,
                apkDir,
                appSourceDir,
                mOutput);
    }

    /**
     * Backup the app's .obb files to the backup destination {@link #mOutput}. Currently only used
     * for 'adb backup'.
     */
    // TODO(b/113807190): Investigate and potentially remove.
    public void backupObb(@UserIdInt int userId, PackageInfo packageInfo) {
        // TODO: migrate this to SharedStorageBackup, since AID_SYSTEM doesn't have access to
        // external storage.
        Environment.UserEnvironment userEnv =
                new Environment.UserEnvironment(userId);
        File obbDir = userEnv.buildExternalStorageAppObbDirs(packageInfo.packageName)[0];
        if (obbDir != null) {
            if (MORE_DEBUG) {
                Log.i(TAG, "obb dir: " + obbDir.getAbsolutePath());
            }
            File[] obbFiles = obbDir.listFiles();
            if (obbFiles != null) {
                String obbDirName = obbDir.getAbsolutePath();
                for (File obb : obbFiles) {
                    FullBackup.backupToTar(
                            packageInfo.packageName,
                            FullBackup.OBB_TREE_TOKEN,
                            /* linkDomain */ null,
                            obbDirName,
                            obb.getAbsolutePath(),
                            mOutput);
                }
            }
        }
    }
}
