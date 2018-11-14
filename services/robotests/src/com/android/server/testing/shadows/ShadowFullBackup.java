package com.android.server.testing.shadows;

import android.app.backup.BackupDataOutput;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Shadow for {@link FullBackup}. Used to emulate the native method {@link
 * FullBackup#backupToTar(String, String, String, String, String, FullBackupDataOutput)}. Relies on
 * the shadow {@link ShadowBackupDataOutput}, which must be included in tests that use this shadow.
 */
@Implements(FullBackup.class)
public class ShadowFullBackup {
    /**
     * Reads data from the specified file at {@code path} and writes it to the {@code output}. Does
     * not match the native implementation, and only partially simulates TAR format. Used solely for
     * passing backup data for testing purposes.
     *
     * <p>Note: Only handles the {@code path} denoting a file and not a directory like the real
     * implementation.
     */
    @Implementation
    protected static int backupToTar(
            String packageName,
            String domain,
            String linkdomain,
            String rootpath,
            String path,
            FullBackupDataOutput output) {
        BackupDataOutput backupDataOutput = output.getData();
        try {
            Path file = Paths.get(path);
            byte[] data = Files.readAllBytes(file);
            backupDataOutput.writeEntityHeader("key", data.length);

            // Partially simulate TAR header (not all fields included). We use a 512 byte block for
            // the header to follow the TAR convention and to have a consistent size block to help
            // with separating the header from the data.
            ByteBuffer tarBlock = ByteBuffer.wrap(new byte[512]);
            String tarPath = "apps/" + packageName + (domain == null ? "" : "/" + domain) + path;
            tarBlock.put(tarPath.getBytes()); // file path
            tarBlock.putInt(0x1ff); // file mode
            tarBlock.putLong(Files.size(file)); // file size
            tarBlock.putLong(Files.getLastModifiedTime(file).toMillis()); // last modified time
            tarBlock.putInt(0); // file type

            // Write TAR header directly to the BackupDataOutput's output stream.
            ShadowBackupDataOutput shadowBackupDataOutput = Shadow.extract(backupDataOutput);
            ObjectOutputStream outputStream = shadowBackupDataOutput.getOutputStream();
            outputStream.write(tarBlock.array());
            outputStream.flush();

            backupDataOutput.writeEntityData(data, data.length);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return 0;
    }
}
