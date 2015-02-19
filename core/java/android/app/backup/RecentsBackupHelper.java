package android.app.backup;

import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import java.io.File;

/**
 * Helper for saving/restoring 'recent tasks' infrastructure.
 * @hide
 */
public class RecentsBackupHelper implements BackupHelper {
    private static final String TAG = "RecentsBackup";
    private static final boolean DEBUG = false;

    // This must match TaskPersister.TASKS_DIRNAME, but that class is not accessible from here
    private static final String RECENTS_TASK_DIR = "recent_tasks";

    // Must match TaskPersister.IMAGES_DIRNAME, as above
    private static final String RECENTS_IMAGE_DIR = "recent_images";

    // At restore time, tasks/thumbnails are placed in these directories alongside
    // the "live" recents dirs named above.
    private static final String RECENTS_TASK_RESTORE_DIR = "restored_" + RECENTS_TASK_DIR;
    private static final String RECENTS_IMAGE_RESTORE_DIR = "restored_" + RECENTS_IMAGE_DIR;

    // Prefixes for tagging the two kinds of recents backup records that we might generate
    private static final String RECENTS_TASK_KEY = "task:";
    private static final String RECENTS_IMAGE_KEY = "image:";

    FileBackupHelperBase mTaskFileHelper;

    final File mSystemDir;
    final File mTasksDir;
    final File mRestoredTasksDir;
    final File mRestoredImagesDir;
    final String[] mRecentFiles;
    final String[] mRecentKeys;

    /**
     * @param context The agent context in which this helper instance will run
     */
    public RecentsBackupHelper(Context context) {
        mTaskFileHelper = new FileBackupHelperBase(context);

        mSystemDir = new File(Environment.getDataDirectory(), "system");
        mTasksDir = new File(mSystemDir, RECENTS_TASK_DIR);
        mRestoredTasksDir = new File(mSystemDir, RECENTS_TASK_RESTORE_DIR);
        mRestoredImagesDir = new File(mSystemDir, RECENTS_IMAGE_RESTORE_DIR);

        // Currently we back up only the recent-task descriptions, not the thumbnails
        File[] recentFiles = mTasksDir.listFiles();
        if (recentFiles != null) {
            // We explicitly proceed even if this is a zero-size array
            final int N = recentFiles.length;
            mRecentKeys = new String[N];
            mRecentFiles = new String[N];
            if (DEBUG) {
                Slog.i(TAG, "Identifying recents for backup: " + N);
            }
            for (int i = 0; i < N; i++) {
                mRecentKeys[i] = new String(RECENTS_TASK_KEY + recentFiles[i].getName());
                mRecentFiles[i] = recentFiles[i].getAbsolutePath();
                if (DEBUG) {
                    Slog.i(TAG, "   " + mRecentKeys[i]);
                }
            }
        } else {
            mRecentFiles = mRecentKeys = new String[0];
        }
    }

    /**
     * Task-file key:  RECENTS_TASK_KEY + leaf filename
     * Thumbnail-file key: RECENTS_IMAGE_KEY + leaf filename
     */
    @Override
    public void performBackup(ParcelFileDescriptor oldState, BackupDataOutput data,
            ParcelFileDescriptor newState) {
        FileBackupHelperBase.performBackup_checked(oldState, data, newState,
                mRecentFiles, mRecentKeys);
    }

    @Override
    public void restoreEntity(BackupDataInputStream data) {
        final String key = data.getKey();
        File output = null;
        if (key.startsWith(RECENTS_TASK_KEY)) {
            String name = key.substring(RECENTS_TASK_KEY.length());
            output = new File(mRestoredTasksDir, name);
            mRestoredTasksDir.mkdirs();
        } else if (key.startsWith(RECENTS_IMAGE_KEY)) {
            String name = key.substring(RECENTS_IMAGE_KEY.length());
            output = new File(mRestoredImagesDir, name);
            mRestoredImagesDir.mkdirs();
        }

        if (output != null) {
            if (DEBUG) {
                Slog.i(TAG, "Restoring key='"
                        + key + "' to " + output.getAbsolutePath());
            }
            mTaskFileHelper.writeFile(output, data);
        }
    }

    @Override
    public void writeNewStateDescription(ParcelFileDescriptor newState) {
        mTaskFileHelper.writeNewStateDescription(newState);
    }

}
