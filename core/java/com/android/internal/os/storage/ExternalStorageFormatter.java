package com.android.internal.os.storage;

import android.app.ProgressDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Slog;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.internal.R;

/**
 * Takes care of unmounting and formatting external storage.
 *
 * @deprecated Please use {@link Intent#ACTION_MASTER_CLEAR} broadcast with extra
 * {@link Intent#EXTRA_WIPE_EXTERNAL_STORAGE} to wipe and factory reset, or call
 * {@link StorageManager#wipeAdoptableDisks} directly to format external storages.
 */
public class ExternalStorageFormatter extends Service {
    static final String TAG = "ExternalStorageFormatter";

    public static final String FORMAT_ONLY = "com.android.internal.os.storage.FORMAT_ONLY";
    public static final String FORMAT_AND_FACTORY_RESET = "com.android.internal.os.storage.FORMAT_AND_FACTORY_RESET";

    public static final String EXTRA_ALWAYS_RESET = "always_reset";

    public static final ComponentName COMPONENT_NAME
            = new ComponentName("android", ExternalStorageFormatter.class.getName());

    private StorageManager mStorageManager;

    private PowerManager.WakeLock mWakeLock;

    private ProgressDialog mProgressDialog = null;

    private boolean mFactoryReset = false;
    private boolean mAlwaysReset = false;
    private String mReason = null;

    @Override
    public void onCreate() {
        super.onCreate();

        mStorageManager = getSystemService(StorageManager.class);

        mWakeLock = ((PowerManager)getSystemService(Context.POWER_SERVICE))
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ExternalStorageFormatter");
        mWakeLock.acquire();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (FORMAT_AND_FACTORY_RESET.equals(intent.getAction())) {
            mFactoryReset = true;
        }
        if (intent.getBooleanExtra(EXTRA_ALWAYS_RESET, false)) {
            mAlwaysReset = true;
        }

        mReason = intent.getStringExtra(Intent.EXTRA_REASON);
        StorageVolume userVol = intent.getParcelableExtra(StorageVolume.EXTRA_STORAGE_VOLUME);
        if (userVol == null) {
            Slog.w(TAG, "Missing explicit storage volume; assuming default");
            userVol = mStorageManager.getPrimaryVolume();
        }

        final String volumeId = userVol.getId();

        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setIndeterminate(true);
        mProgressDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        mProgressDialog.setMessage(getText(R.string.progress_unmounting));
        mProgressDialog.show();

        new FormatTask(volumeId).start();

        return Service.START_REDELIVER_INTENT;
    }

    private class FormatTask extends Thread {
        private final String mVolumeId;

        public FormatTask(String volumeId) {
            mVolumeId = volumeId;
        }

        @Override
        public void run() {
            boolean success = false;
            try {
                mStorageManager.format(mVolumeId);
                success = true;
            } catch (Exception e) {
                Slog.w(TAG, "Failed to format", e);
                Toast.makeText(ExternalStorageFormatter.this,
                        R.string.format_error, Toast.LENGTH_LONG).show();
            }
            if (success) {
                if (mFactoryReset) {
                    Intent intent = new Intent(Intent.ACTION_MASTER_CLEAR);
                    intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_REASON, mReason);
                    sendBroadcast(intent);
                    // Intent handling is asynchronous -- assume it will happen soon.
                    stopSelf();
                    return;
                }
            }
            // If we didn't succeed, or aren't doing a full factory
            // reset, then it is time to remount the storage.
            if (!success && mAlwaysReset) {
                Intent intent = new Intent(Intent.ACTION_MASTER_CLEAR);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_REASON, mReason);
                sendBroadcast(intent);
            } else {
                try {
                    mStorageManager.mount(mVolumeId);
                } catch (Exception e) {
                    Slog.w(TAG, "Failed to mount", e);
                }
            }
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        if (mProgressDialog != null) {
            mProgressDialog.dismiss();
        }
        mWakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
