/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.shell;

import static android.content.pm.PackageManager.FEATURE_LEANBACK;
import static android.content.pm.PackageManager.FEATURE_TELEVISION;
import static android.os.Process.THREAD_PRIORITY_BACKGROUND;

import static com.android.shell.BugreportPrefs.STATE_HIDE;
import static com.android.shell.BugreportPrefs.STATE_UNKNOWN;
import static com.android.shell.BugreportPrefs.getWarningState;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.MainThread;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.BugreportManager;
import android.os.BugreportManager.BugreportCallback;
import android.os.BugreportManager.BugreportCallback.BugreportErrorCode;
import android.os.BugreportParams;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.Vibrator;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Pair;
import android.util.Patterns;
import android.util.SparseArray;
import android.view.ContextThemeWrapper;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ChooserActivity;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

import com.google.android.collect.Lists;

import libcore.io.Streams;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Service used to trigger system bugreports.
 * <p>
 * The workflow uses Bugreport API({@code BugreportManager}) and is as follows:
 * <ol>
 * <li>System apps like Settings or SysUI broadcasts {@code BUGREPORT_REQUESTED}.
 * <li>{@link BugreportRequestedReceiver} receives the intent and delegates it to this service.
 * <li>This service calls startBugreport() and passes in local file descriptors to receive
 * bugreport artifacts.
 * </ol>
 */
public class BugreportProgressService extends Service {
    private static final String TAG = "BugreportProgressService";
    private static final boolean DEBUG = false;

    private Intent startSelfIntent;

    private static final String AUTHORITY = "com.android.shell";

    // External intent used to trigger bugreport API.
    static final String INTENT_BUGREPORT_REQUESTED =
            "com.android.internal.intent.action.BUGREPORT_REQUESTED";

    // Intent sent to notify external apps that bugreport finished
    static final String INTENT_BUGREPORT_FINISHED =
            "com.android.internal.intent.action.BUGREPORT_FINISHED";

    // Internal intents used on notification actions.
    static final String INTENT_BUGREPORT_CANCEL = "android.intent.action.BUGREPORT_CANCEL";
    static final String INTENT_BUGREPORT_SHARE = "android.intent.action.BUGREPORT_SHARE";
    static final String INTENT_BUGREPORT_INFO_LAUNCH =
            "android.intent.action.BUGREPORT_INFO_LAUNCH";
    static final String INTENT_BUGREPORT_SCREENSHOT =
            "android.intent.action.BUGREPORT_SCREENSHOT";

    static final String EXTRA_BUGREPORT = "android.intent.extra.BUGREPORT";
    static final String EXTRA_BUGREPORT_TYPE = "android.intent.extra.BUGREPORT_TYPE";
    static final String EXTRA_BUGREPORT_NONCE = "android.intent.extra.BUGREPORT_NONCE";
    static final String EXTRA_SCREENSHOT = "android.intent.extra.SCREENSHOT";
    static final String EXTRA_ID = "android.intent.extra.ID";
    static final String EXTRA_NAME = "android.intent.extra.NAME";
    static final String EXTRA_TITLE = "android.intent.extra.TITLE";
    static final String EXTRA_DESCRIPTION = "android.intent.extra.DESCRIPTION";
    static final String EXTRA_ORIGINAL_INTENT = "android.intent.extra.ORIGINAL_INTENT";
    static final String EXTRA_INFO = "android.intent.extra.INFO";

    private static final int MSG_SERVICE_COMMAND = 1;
    private static final int MSG_DELAYED_SCREENSHOT = 2;
    private static final int MSG_SCREENSHOT_REQUEST = 3;
    private static final int MSG_SCREENSHOT_RESPONSE = 4;

    // Passed to Message.obtain() when msg.arg2 is not used.
    private static final int UNUSED_ARG2 = -2;

    // Maximum progress displayed in %.
    private static final int CAPPED_PROGRESS = 99;

    /** Show the progress log every this percent. */
    private static final int LOG_PROGRESS_STEP = 10;

    /**
     * Delay before a screenshot is taken.
     * <p>
     * Should be at least 3 seconds, otherwise its toast might show up in the screenshot.
     */
    static final int SCREENSHOT_DELAY_SECONDS = 3;

    /** System property where dumpstate stores last triggered bugreport id */
    static final String PROPERTY_LAST_ID = "dumpstate.last_id";

    private static final String BUGREPORT_SERVICE = "bugreport";

    /**
     * Directory on Shell's data storage where screenshots will be stored.
     * <p>
     * Must be a path supported by its FileProvider.
     */
    private static final String BUGREPORT_DIR = "bugreports";

    private static final String NOTIFICATION_CHANNEL_ID = "bugreports";

    /**
     * Always keep the newest 8 bugreport files.
     */
    private static final int MIN_KEEP_COUNT = 8;

    /**
     * Always keep bugreports taken in the last week.
     */
    private static final long MIN_KEEP_AGE = DateUtils.WEEK_IN_MILLIS;

    private static final String BUGREPORT_MIMETYPE = "application/vnd.android.bugreport";

    /** Always keep just the last 3 remote bugreport's files around. */
    private static final int REMOTE_BUGREPORT_FILES_AMOUNT = 3;

    /** Always keep remote bugreport files created in the last day. */
    private static final long REMOTE_MIN_KEEP_AGE = DateUtils.DAY_IN_MILLIS;

    private final Object mLock = new Object();

    /** Managed bugreport info (keyed by id) */
    @GuardedBy("mLock")
    private final SparseArray<BugreportInfo> mBugreportInfos = new SparseArray<>();

    private Context mContext;

    private Handler mMainThreadHandler;
    private ServiceHandler mServiceHandler;
    private ScreenshotHandler mScreenshotHandler;

    private final BugreportInfoDialog mInfoDialog = new BugreportInfoDialog();

    private File mBugreportsDir;

    @VisibleForTesting BugreportManager mBugreportManager;

    /**
     * id of the notification used to set service on foreground.
     */
    private int mForegroundId = -1;

    /**
     * Flag indicating whether a screenshot is being taken.
     * <p>
     * This is the only state that is shared between the 2 handlers and hence must have synchronized
     * access.
     */
    private boolean mTakingScreenshot;

    /**
     * The delay timeout before taking a screenshot.
     */
    @VisibleForTesting int mScreenshotDelaySec = SCREENSHOT_DELAY_SECONDS;

    @GuardedBy("sNotificationBundle")
    private static final Bundle sNotificationBundle = new Bundle();

    private boolean mIsWatch;
    private boolean mIsTv;

    @Override
    public void onCreate() {
        mContext = getApplicationContext();
        mMainThreadHandler = new Handler(Looper.getMainLooper());
        mServiceHandler = new ServiceHandler("BugreportProgressServiceMainThread");
        mScreenshotHandler = new ScreenshotHandler("BugreportProgressServiceScreenshotThread");
        startSelfIntent = new Intent(this, this.getClass());

        mBugreportsDir = new File(getFilesDir(), BUGREPORT_DIR);
        if (!mBugreportsDir.exists()) {
            Log.i(TAG, "Creating directory " + mBugreportsDir
                    + " to store bugreports and screenshots");
            if (!mBugreportsDir.mkdir()) {
                Log.w(TAG, "Could not create directory " + mBugreportsDir);
            }
        }
        final Configuration conf = mContext.getResources().getConfiguration();
        mIsWatch = (conf.uiMode & Configuration.UI_MODE_TYPE_MASK) ==
                Configuration.UI_MODE_TYPE_WATCH;
        PackageManager packageManager = getPackageManager();
        mIsTv = packageManager.hasSystemFeature(FEATURE_LEANBACK)
                || packageManager.hasSystemFeature(FEATURE_TELEVISION);
        NotificationManager nm = NotificationManager.from(mContext);
        nm.createNotificationChannel(
                new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                        mContext.getString(R.string.bugreport_notification_channel),
                        isTv(this) ? NotificationManager.IMPORTANCE_DEFAULT
                                : NotificationManager.IMPORTANCE_LOW));
        mBugreportManager = mContext.getSystemService(BugreportManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "onStartCommand(): " + dumpIntent(intent));
        if (intent != null) {
            if (!intent.hasExtra(EXTRA_ORIGINAL_INTENT) && !intent.hasExtra(EXTRA_ID)) {
                return START_NOT_STICKY;
            }
            // Handle it in a separate thread.
            final Message msg = mServiceHandler.obtainMessage();
            msg.what = MSG_SERVICE_COMMAND;
            msg.obj = intent;
            mServiceHandler.sendMessage(msg);
        }

        // If service is killed it cannot be recreated because it would not know which
        // dumpstate IDs it would have to watch.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public void onDestroy() {
        mServiceHandler.getLooper().quit();
        mScreenshotHandler.getLooper().quit();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        synchronized (mLock) {
            final int size = mBugreportInfos.size();
            if (size == 0) {
                writer.println("No monitored processes");
                return;
            }
            writer.print("Foreground id: "); writer.println(mForegroundId);
            writer.println("\n");
            writer.println("Monitored dumpstate processes");
            writer.println("-----------------------------");
            for (int i = 0; i < size; i++) {
                writer.print("#");
                writer.println(i + 1);
                writer.println(getInfoLocked(mBugreportInfos.keyAt(i)));
            }
        }
    }

    private static String getFileName(BugreportInfo info, String suffix) {
        return String.format("%s-%s%s", info.baseName, info.getName(), suffix);
    }

    private final class BugreportCallbackImpl extends BugreportCallback {

        @GuardedBy("mLock")
        private final BugreportInfo mInfo;

        BugreportCallbackImpl(BugreportInfo info) {
            mInfo = info;
        }

        @Override
        public void onProgress(float progress) {
            synchronized (mLock) {
                checkProgressUpdatedLocked(mInfo, (int) progress);
            }
        }

        /**
         * Logs errors and stops the service on which this bugreport was running.
         * Also stops progress notification (if any).
         */
        @Override
        public void onError(@BugreportErrorCode int errorCode) {
            synchronized (mLock) {
                stopProgressLocked(mInfo.id);
                mInfo.deleteEmptyFiles();
            }
            Log.e(TAG, "Bugreport API callback onError() errorCode = " + errorCode);
            return;
        }

        @Override
        public void onFinished() {
            mInfo.renameBugreportFile();
            mInfo.renameScreenshots();
            if (mInfo.bugreportFile.length() == 0) {
                Log.e(TAG, "Bugreport file empty. File path = " + mInfo.bugreportFile);
                onError(BUGREPORT_ERROR_RUNTIME);
                return;
            }
            synchronized (mLock) {
                sendBugreportFinishedBroadcastLocked();
                mMainThreadHandler.post(() -> mInfoDialog.onBugreportFinished(mInfo));
            }
        }

        @Override
        public void onEarlyReportFinished() {}

        /**
         * Reads bugreport id and links it to the bugreport info to track a bugreport that is in
         * process. id is incremented in the dumpstate code.
         * We do not track a bugreport if there is already a bugreport with the same id being
         * tracked.
         */
        @GuardedBy("mLock")
        private void trackInfoWithIdLocked() {
            final int id = SystemProperties.getInt(PROPERTY_LAST_ID, 1);
            if (mBugreportInfos.get(id) == null) {
                mInfo.id = id;
                mBugreportInfos.put(mInfo.id, mInfo);
            }
            return;
        }

        @GuardedBy("mLock")
        private void sendBugreportFinishedBroadcastLocked() {
            final String bugreportFilePath = mInfo.bugreportFile.getAbsolutePath();
            if (mInfo.type == BugreportParams.BUGREPORT_MODE_REMOTE) {
                sendRemoteBugreportFinishedBroadcast(mContext, bugreportFilePath,
                        mInfo.bugreportFile, mInfo.nonce);
            } else {
                cleanupOldFiles(MIN_KEEP_COUNT, MIN_KEEP_AGE, mBugreportsDir);
                final Intent intent = new Intent(INTENT_BUGREPORT_FINISHED);
                intent.putExtra(EXTRA_BUGREPORT, bugreportFilePath);
                intent.putExtra(EXTRA_SCREENSHOT, getScreenshotForIntent(mInfo));
                mContext.sendBroadcast(intent, android.Manifest.permission.DUMP);
                onBugreportFinished(mInfo);
            }
        }
    }

    private static void sendRemoteBugreportFinishedBroadcast(Context context,
            String bugreportFileName, File bugreportFile, long nonce) {
        cleanupOldFiles(REMOTE_BUGREPORT_FILES_AMOUNT, REMOTE_MIN_KEEP_AGE,
                bugreportFile.getParentFile());
        final Intent intent = new Intent(DevicePolicyManager.ACTION_REMOTE_BUGREPORT_DISPATCH);
        final Uri bugreportUri = getUri(context, bugreportFile);
        final String bugreportHash = generateFileHash(bugreportFileName);
        if (bugreportHash == null) {
            Log.e(TAG, "Error generating file hash for remote bugreport");
        }
        intent.setDataAndType(bugreportUri, BUGREPORT_MIMETYPE);
        intent.putExtra(DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_HASH, bugreportHash);
        intent.putExtra(DevicePolicyManager.EXTRA_REMOTE_BUGREPORT_NONCE, nonce);
        intent.putExtra(EXTRA_BUGREPORT, bugreportFileName);
        context.sendBroadcastAsUser(intent, UserHandle.SYSTEM,
                android.Manifest.permission.DUMP);
    }

    /**
     * Checks if screenshot array is non-empty and returns the first screenshot's path. The first
     * screenshot is the default screenshot for the bugreport types that take it.
     */
    private static String getScreenshotForIntent(BugreportInfo info) {
        if (!info.screenshotFiles.isEmpty()) {
            final File screenshotFile = info.screenshotFiles.get(0);
            final String screenshotFilePath = screenshotFile.getAbsolutePath();
            return screenshotFilePath;
        }
        return null;
    }

    private static String generateFileHash(String fileName) {
        String fileHash = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            FileInputStream input = new FileInputStream(new File(fileName));
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) > 0) {
                md.update(buffer, 0, size);
            }
            input.close();
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hashBytes.length; i++) {
                sb.append(String.format("%02x", hashBytes[i]));
            }
            fileHash = sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            Log.e(TAG, "generating file hash for bugreport file failed " + fileName, e);
        }
        return fileHash;
    }

    static void cleanupOldFiles(final int minCount, final long minAge, File bugreportsDir) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    FileUtils.deleteOlderFiles(bugreportsDir, minCount, minAge);
                } catch (RuntimeException e) {
                    Log.e(TAG, "RuntimeException deleting old files", e);
                }
                return null;
            }
        }.execute();
    }

    /**
     * Main thread used to handle all requests but taking screenshots.
     */
    private final class ServiceHandler extends Handler {
        public ServiceHandler(String name) {
            super(newLooper(name));
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_DELAYED_SCREENSHOT) {
                takeScreenshot(msg.arg1, msg.arg2);
                return;
            }

            if (msg.what == MSG_SCREENSHOT_RESPONSE) {
                handleScreenshotResponse(msg);
                return;
            }

            if (msg.what != MSG_SERVICE_COMMAND) {
                // Confidence check.
                Log.e(TAG, "Invalid message type: " + msg.what);
                return;
            }

            // At this point it's handling onStartCommand(), with the intent passed as an Extra.
            if (!(msg.obj instanceof Intent)) {
                // Confidence check.
                Log.wtf(TAG, "handleMessage(): invalid msg.obj type: " + msg.obj);
                return;
            }
            final Parcelable parcel = ((Intent) msg.obj).getParcelableExtra(EXTRA_ORIGINAL_INTENT);
            Log.v(TAG, "handleMessage(): " + dumpIntent((Intent) parcel));
            final Intent intent;
            if (parcel instanceof Intent) {
                // The real intent was passed to BugreportRequestedReceiver,
                // which delegated to the service.
                intent = (Intent) parcel;
            } else {
                intent = (Intent) msg.obj;
            }
            final String action = intent.getAction();
            final int id = intent.getIntExtra(EXTRA_ID, 0);
            final String name = intent.getStringExtra(EXTRA_NAME);

            if (DEBUG)
                Log.v(TAG, "action: " + action + ", name: " + name + ", id: " + id);
            switch (action) {
                case INTENT_BUGREPORT_REQUESTED:
                    startBugreportAPI(intent);
                    break;
                case INTENT_BUGREPORT_INFO_LAUNCH:
                    launchBugreportInfoDialog(id);
                    break;
                case INTENT_BUGREPORT_SCREENSHOT:
                    takeScreenshot(id);
                    break;
                case INTENT_BUGREPORT_SHARE:
                    shareBugreport(id, (BugreportInfo) intent.getParcelableExtra(EXTRA_INFO));
                    break;
                case INTENT_BUGREPORT_CANCEL:
                    cancel(id);
                    break;
                default:
                    Log.w(TAG, "Unsupported intent: " + action);
            }
            return;

        }
    }

    /**
     * Separate thread used only to take screenshots so it doesn't block the main thread.
     */
    private final class ScreenshotHandler extends Handler {
        public ScreenshotHandler(String name) {
            super(newLooper(name));
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what != MSG_SCREENSHOT_REQUEST) {
                Log.e(TAG, "Invalid message type: " + msg.what);
                return;
            }
            handleScreenshotRequest(msg);
        }
    }

    @GuardedBy("mLock")
    private BugreportInfo getInfoLocked(int id) {
        final BugreportInfo bugreportInfo = mBugreportInfos.get(id);
        if (bugreportInfo == null) {
            Log.w(TAG, "Not monitoring bugreports with ID " + id);
            return null;
        }
        return bugreportInfo;
    }

    private String getBugreportBaseName(@BugreportParams.BugreportMode int type) {
        String buildId = SystemProperties.get("ro.build.id", "UNKNOWN_BUILD");
        String deviceName = SystemProperties.get("ro.product.name", "UNKNOWN_DEVICE");
        String typeSuffix = null;
        if (type == BugreportParams.BUGREPORT_MODE_WIFI) {
            typeSuffix = "wifi";
        } else if (type == BugreportParams.BUGREPORT_MODE_TELEPHONY) {
            typeSuffix = "telephony";
        } else {
            return String.format("bugreport-%s-%s", deviceName, buildId);
        }
        return String.format("bugreport-%s-%s-%s", deviceName, buildId, typeSuffix);
    }

    private void startBugreportAPI(Intent intent) {
        String shareTitle = intent.getStringExtra(EXTRA_TITLE);
        String shareDescription = intent.getStringExtra(EXTRA_DESCRIPTION);
        int bugreportType = intent.getIntExtra(EXTRA_BUGREPORT_TYPE,
                BugreportParams.BUGREPORT_MODE_INTERACTIVE);
        long nonce = intent.getLongExtra(EXTRA_BUGREPORT_NONCE, 0);
        String baseName = getBugreportBaseName(bugreportType);
        String name = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date());

        BugreportInfo info = new BugreportInfo(mContext, baseName, name,
                shareTitle, shareDescription, bugreportType, mBugreportsDir, nonce);
        synchronized (mLock) {
            if (info.bugreportFile.exists()) {
                Log.e(TAG, "Failed to start bugreport generation, the requested bugreport file "
                        + info.bugreportFile + " already exists");
                return;
            }
            info.createBugreportFile();
        }
        ParcelFileDescriptor bugreportFd = info.getBugreportFd();
        if (bugreportFd == null) {
            Log.e(TAG, "Failed to start bugreport generation as "
                    + " bugreport parcel file descriptor is null.");
            return;
        }
        info.createScreenshotFile(mBugreportsDir);
        ParcelFileDescriptor screenshotFd = null;
        if (isDefaultScreenshotRequired(bugreportType, /* hasScreenshotButton= */ !mIsTv)) {
            screenshotFd = info.getDefaultScreenshotFd();
            if (screenshotFd == null) {
                Log.e(TAG, "Failed to start bugreport generation as"
                        + " screenshot parcel file descriptor is null. Deleting bugreport file");
                FileUtils.closeQuietly(bugreportFd);
                info.bugreportFile.delete();
                return;
            }
        }

        final Executor executor = ActivityThread.currentActivityThread().getExecutor();

        Log.i(TAG, "bugreport type = " + bugreportType
                + " bugreport file fd: " + bugreportFd
                + " screenshot file fd: " + screenshotFd);

        BugreportCallbackImpl bugreportCallback = new BugreportCallbackImpl(info);
        try {
            synchronized (mLock) {
                mBugreportManager.startBugreport(bugreportFd, screenshotFd,
                        new BugreportParams(bugreportType), executor, bugreportCallback);
                bugreportCallback.trackInfoWithIdLocked();
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "Error in generating bugreports: ", e);
            // The binder call didn't go through successfully, so need to close the fds.
            // If the calls went through API takes ownership.
            FileUtils.closeQuietly(bugreportFd);
            if (screenshotFd != null) {
                FileUtils.closeQuietly(screenshotFd);
            }
        }
    }

    private static boolean isDefaultScreenshotRequired(
            @BugreportParams.BugreportMode int bugreportType,
            boolean hasScreenshotButton) {
        // Modify dumpstate#SetOptionsFromMode as well for default system screenshots.
        // We override dumpstate for interactive bugreports with a screenshot button.
        return (bugreportType == BugreportParams.BUGREPORT_MODE_INTERACTIVE && !hasScreenshotButton)
                || bugreportType == BugreportParams.BUGREPORT_MODE_FULL
                || bugreportType == BugreportParams.BUGREPORT_MODE_WEAR;
    }

    private static ParcelFileDescriptor getFd(File file) {
        try {
            return ParcelFileDescriptor.open(file,
                    ParcelFileDescriptor.MODE_WRITE_ONLY | ParcelFileDescriptor.MODE_APPEND);
        } catch (FileNotFoundException e) {
            Log.i(TAG, "Error in generating bugreports: ", e);
        }
        return null;
    }

    private static void createReadWriteFile(File file) {
        try {
            if (!file.exists()) {
                file.createNewFile();
                file.setReadable(true, true);
                file.setWritable(true, true);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error in creating bugreport file: ", e);
        }
    }

    /**
     * Updates the system notification for a given bugreport.
     */
    private void updateProgress(BugreportInfo info) {
        if (info.progress.intValue() < 0) {
            Log.e(TAG, "Invalid progress values for " + info);
            return;
        }

        if (info.finished.get()) {
            Log.w(TAG, "Not sending progress notification because bugreport has finished already ("
                    + info + ")");
            return;
        }

        final NumberFormat nf = NumberFormat.getPercentInstance();
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        final String percentageText = nf.format((double) info.progress.intValue() / 100);

        String title = mContext.getString(R.string.bugreport_in_progress_title, info.id);

        // TODO: Remove this workaround when notification progress is implemented on Wear.
        if (mIsWatch) {
            nf.setMinimumFractionDigits(0);
            nf.setMaximumFractionDigits(0);
            final String watchPercentageText = nf.format((double) info.progress.intValue() / 100);
            title = title + "\n" + watchPercentageText;
        }

        final String name =
                info.getName() != null ? info.getName()
                        : mContext.getString(R.string.bugreport_unnamed);

        final Notification.Builder builder = newBaseNotification(mContext)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(name)
                .setProgress(100 /* max value of progress percentage */,
                        info.progress.intValue(), false)
                .setOngoing(true);

        // Wear and ATV bugreport doesn't need the bug info dialog, screenshot and cancel action.
        if (!(mIsWatch || mIsTv)) {
            final Action cancelAction = new Action.Builder(null, mContext.getString(
                    com.android.internal.R.string.cancel), newCancelIntent(mContext, info)).build();
            final Intent infoIntent = new Intent(mContext, BugreportProgressService.class);
            infoIntent.setAction(INTENT_BUGREPORT_INFO_LAUNCH);
            infoIntent.putExtra(EXTRA_ID, info.id);
            // Simple notification action button clicks are immutable
            final PendingIntent infoPendingIntent =
                    PendingIntent.getService(mContext, info.id, infoIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final Action infoAction = new Action.Builder(null,
                    mContext.getString(R.string.bugreport_info_action),
                    infoPendingIntent).build();
            final Intent screenshotIntent = new Intent(mContext, BugreportProgressService.class);
            screenshotIntent.setAction(INTENT_BUGREPORT_SCREENSHOT);
            screenshotIntent.putExtra(EXTRA_ID, info.id);
            // Simple notification action button clicks are immutable
            PendingIntent screenshotPendingIntent = mTakingScreenshot ? null : PendingIntent
                    .getService(mContext, info.id, screenshotIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            final Action screenshotAction = new Action.Builder(null,
                    mContext.getString(R.string.bugreport_screenshot_action),
                    screenshotPendingIntent).build();
            builder.setContentIntent(infoPendingIntent)
                .setActions(infoAction, screenshotAction, cancelAction);
        }
        // Show a debug log, every LOG_PROGRESS_STEP percent.
        final int progress = info.progress.intValue();

        if ((progress == 0) || (progress >= 100)
                || ((progress / LOG_PROGRESS_STEP)
                != (info.lastProgress.intValue() / LOG_PROGRESS_STEP))) {
            Log.d(TAG, "Progress #" + info.id + ": " + percentageText);
        }
        info.lastProgress.set(progress);

        sendForegroundabledNotification(info.id, builder.build());
    }

    private void sendForegroundabledNotification(int id, Notification notification) {
        if (mForegroundId >= 0) {
            if (DEBUG) Log.d(TAG, "Already running as foreground service");
            NotificationManager.from(mContext).notify(id, notification);
        } else {
            mForegroundId = id;
            Log.d(TAG, "Start running as foreground service on id " + mForegroundId);
            // Explicitly starting the service so that stopForeground() does not crash
            // Workaround for b/140997620
            startForegroundService(startSelfIntent);
            startForeground(mForegroundId, notification);
        }
    }

    /**
     * Creates a {@link PendingIntent} for a notification action used to cancel a bugreport.
     */
    private static PendingIntent newCancelIntent(Context context, BugreportInfo info) {
        final Intent intent = new Intent(INTENT_BUGREPORT_CANCEL);
        intent.setClass(context, BugreportProgressService.class);
        intent.putExtra(EXTRA_ID, info.id);
        return PendingIntent.getService(context, info.id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * Finalizes the progress on a given bugreport and cancel its notification.
     */
    @GuardedBy("mLock")
    private void stopProgressLocked(int id) {
        if (mBugreportInfos.indexOfKey(id) < 0) {
            Log.w(TAG, "ID not watched: " + id);
        } else {
            Log.d(TAG, "Removing ID " + id);
            mBugreportInfos.remove(id);
        }
        // Must stop foreground service first, otherwise notif.cancel() will fail below.
        stopForegroundWhenDoneLocked(id);
        Log.d(TAG, "stopProgress(" + id + "): cancel notification");
        NotificationManager.from(mContext).cancel(id);
        stopSelfWhenDoneLocked();
    }

    /**
     * Cancels a bugreport upon user's request.
     */
    private void cancel(int id) {
        MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_NOTIFICATION_ACTION_CANCEL);
        Log.v(TAG, "cancel: ID=" + id);
        mInfoDialog.cancel();
        synchronized (mLock) {
            final BugreportInfo info = getInfoLocked(id);
            if (info != null && !info.finished.get()) {
                Log.i(TAG, "Cancelling bugreport service (ID=" + id + ") on user's request");
                mBugreportManager.cancelBugreport();
                info.deleteScreenshots();
                info.deleteBugreportFile();
            }
            stopProgressLocked(id);
        }
    }

    /**
     * Fetches a {@link BugreportInfo} for a given process and launches a dialog where the user can
     * change its values.
     */
    private void launchBugreportInfoDialog(int id) {
        MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_NOTIFICATION_ACTION_DETAILS);
        final BugreportInfo info;
        synchronized (mLock) {
            info = getInfoLocked(id);
        }
        if (info == null) {
            // Most likely am killed Shell before user tapped the notification. Since system might
            // be too busy anwyays, it's better to ignore the notification and switch back to the
            // non-interactive mode (where the bugerport will be shared upon completion).
            Log.w(TAG, "launchBugreportInfoDialog(): canceling notification because id " + id
                    + " was not found");
            // TODO: add test case to make sure notification is canceled.
            NotificationManager.from(mContext).cancel(id);
            return;
        }

        collapseNotificationBar();

        // Dissmiss keyguard first.
        final IWindowManager wm = IWindowManager.Stub
                .asInterface(ServiceManager.getService(Context.WINDOW_SERVICE));
        try {
            wm.dismissKeyguard(null, null);
        } catch (Exception e) {
            // ignore it
        }

        mMainThreadHandler.post(() -> mInfoDialog.initialize(mContext, info));
    }

    /**
     * Starting point for taking a screenshot.
     * <p>
     * It first display a toast message and waits {@link #SCREENSHOT_DELAY_SECONDS} seconds before
     * taking the screenshot.
     */
    private void takeScreenshot(int id) {
        MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_NOTIFICATION_ACTION_SCREENSHOT);
        BugreportInfo info;
        synchronized (mLock) {
            info = getInfoLocked(id);
        }
        if (info == null) {
            // Most likely am killed Shell before user tapped the notification. Since system might
            // be too busy anwyays, it's better to ignore the notification and switch back to the
            // non-interactive mode (where the bugerport will be shared upon completion).
            Log.w(TAG, "takeScreenshot(): canceling notification because id " + id
                    + " was not found");
            // TODO: add test case to make sure notification is canceled.
            NotificationManager.from(mContext).cancel(id);
            return;
        }
        setTakingScreenshot(true);
        collapseNotificationBar();
        final String msg = mContext.getResources()
                .getQuantityString(com.android.internal.R.plurals.bugreport_countdown,
                        mScreenshotDelaySec, mScreenshotDelaySec);
        Log.i(TAG, msg);
        // Show a toast just once, otherwise it might be captured in the screenshot.
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();

        takeScreenshot(id, mScreenshotDelaySec);
    }

    /**
     * Takes a screenshot after {@code delay} seconds.
     */
    private void takeScreenshot(int id, int delay) {
        if (delay > 0) {
            Log.d(TAG, "Taking screenshot for " + id + " in " + delay + " seconds");
            final Message msg = mServiceHandler.obtainMessage();
            msg.what = MSG_DELAYED_SCREENSHOT;
            msg.arg1 = id;
            msg.arg2 = delay - 1;
            mServiceHandler.sendMessageDelayed(msg, DateUtils.SECOND_IN_MILLIS);
            return;
        }
        final BugreportInfo info;
        // It's time to take the screenshot: let the proper thread handle it
        synchronized (mLock) {
            info = getInfoLocked(id);
        }
        if (info == null) {
            return;
        }
        final String screenshotPath =
                new File(mBugreportsDir, info.getPathNextScreenshot()).getAbsolutePath();

        Message.obtain(mScreenshotHandler, MSG_SCREENSHOT_REQUEST, id, UNUSED_ARG2, screenshotPath)
                .sendToTarget();
    }

    /**
     * Sets the internal {@code mTakingScreenshot} state and updates all notifications so their
     * SCREENSHOT button is enabled or disabled accordingly.
     */
    private void setTakingScreenshot(boolean flag) {
        synchronized (mLock) {
            mTakingScreenshot = flag;
            for (int i = 0; i < mBugreportInfos.size(); i++) {
                final BugreportInfo info = getInfoLocked(mBugreportInfos.keyAt(i));
                if (info.finished.get()) {
                    Log.d(TAG, "Not updating progress for " + info.id + " while taking screenshot"
                            + " because share notification was already sent");
                    continue;
                }
                updateProgress(info);
            }
        }
    }

    private void handleScreenshotRequest(Message requestMsg) {
        String screenshotFile = (String) requestMsg.obj;
        boolean taken = takeScreenshot(mContext, screenshotFile);
        setTakingScreenshot(false);

        Message.obtain(mServiceHandler, MSG_SCREENSHOT_RESPONSE, requestMsg.arg1, taken ? 1 : 0,
                screenshotFile).sendToTarget();
    }

    private void handleScreenshotResponse(Message resultMsg) {
        final boolean taken = resultMsg.arg2 != 0;
        final BugreportInfo info;
        synchronized (mLock) {
            info = getInfoLocked(resultMsg.arg1);
        }
        if (info == null) {
            return;
        }
        final File screenshotFile = new File((String) resultMsg.obj);

        final String msg;
        if (taken) {
            info.addScreenshot(screenshotFile);
            if (info.finished.get()) {
                Log.d(TAG, "Screenshot finished after bugreport; updating share notification");
                info.renameScreenshots();
                sendBugreportNotification(info, mTakingScreenshot);
            }
            msg = mContext.getString(R.string.bugreport_screenshot_taken);
        } else {
            msg = mContext.getString(R.string.bugreport_screenshot_failed);
            Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
        }
        Log.d(TAG, msg);
    }

    /**
     * Stop running on foreground once there is no more active bugreports being watched.
     */
    @GuardedBy("mLock")
    private void stopForegroundWhenDoneLocked(int id) {
        if (id != mForegroundId) {
            Log.d(TAG, "stopForegroundWhenDoneLocked(" + id + "): ignoring since foreground id is "
                    + mForegroundId);
            return;
        }

        Log.d(TAG, "detaching foreground from id " + mForegroundId);
        stopForeground(Service.STOP_FOREGROUND_DETACH);
        mForegroundId = -1;

        // Might need to restart foreground using a new notification id.
        final int total = mBugreportInfos.size();
        if (total > 0) {
            for (int i = 0; i < total; i++) {
                final BugreportInfo info = getInfoLocked(mBugreportInfos.keyAt(i));
                if (!info.finished.get()) {
                    updateProgress(info);
                    break;
                }
            }
        }
    }

    /**
     * Finishes the service when it's not monitoring any more processes.
     */
    @GuardedBy("mLock")
    private void stopSelfWhenDoneLocked() {
        if (mBugreportInfos.size() > 0) {
            if (DEBUG) Log.d(TAG, "Staying alive, waiting for IDs " + mBugreportInfos);
            return;
        }
        Log.v(TAG, "No more processes to handle, shutting down");
        stopSelf();
    }

    /**
     * Wraps up bugreport generation and triggers a notification to share the bugreport.
     */
    private void onBugreportFinished(BugreportInfo info) {
        if (!TextUtils.isEmpty(info.shareTitle)) {
            info.setTitle(info.shareTitle);
        }
        Log.d(TAG, "Bugreport finished with title: " + info.getTitle()
                + " and shareDescription: " + info.shareDescription);
        info.finished.set(true);

        synchronized (mLock) {
            // Stop running on foreground, otherwise share notification cannot be dismissed.
            stopForegroundWhenDoneLocked(info.id);
        }

        triggerLocalNotification(mContext, info);
    }

    /**
     * Responsible for triggering a notification that allows the user to start a "share" intent with
     * the bugreport. On watches we have other methods to allow the user to start this intent
     * (usually by triggering it on another connected device); we don't need to display the
     * notification in this case.
     */
    private void triggerLocalNotification(final Context context, final BugreportInfo info) {
        if (!info.bugreportFile.exists() || !info.bugreportFile.canRead()) {
            Log.e(TAG, "Could not read bugreport file " + info.bugreportFile);
            Toast.makeText(context, R.string.bugreport_unreadable_text, Toast.LENGTH_LONG).show();
            synchronized (mLock) {
                stopProgressLocked(info.id);
            }
            return;
        }

        boolean isPlainText = info.bugreportFile.getName().toLowerCase().endsWith(".txt");
        if (!isPlainText) {
            // Already zipped, send it right away.
            sendBugreportNotification(info, mTakingScreenshot);
        } else {
            // Asynchronously zip the file first, then send it.
            sendZippedBugreportNotification(info, mTakingScreenshot);
        }
    }

    private static Intent buildWarningIntent(Context context, Intent sendIntent) {
        final Intent intent = new Intent(context, BugreportWarningActivity.class);
        intent.putExtra(Intent.EXTRA_INTENT, sendIntent);
        return intent;
    }

    /**
     * Build {@link Intent} that can be used to share the given bugreport.
     */
    private static Intent buildSendIntent(Context context, BugreportInfo info) {
        // Rename files (if required) before sharing
        info.renameBugreportFile();
        info.renameScreenshots();
        // Files are kept on private storage, so turn into Uris that we can
        // grant temporary permissions for.
        final Uri bugreportUri;
        try {
            bugreportUri = getUri(context, info.bugreportFile);
        } catch (IllegalArgumentException e) {
            // Should not happen on production, but happens when a Shell is sideloaded and
            // FileProvider cannot find a configured root for it.
            Log.wtf(TAG, "Could not get URI for " + info.bugreportFile, e);
            return null;
        }

        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final String mimeType = "application/vnd.android.bugreport";
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setType(mimeType);

        final String subject = !TextUtils.isEmpty(info.getTitle())
                ? info.getTitle() : bugreportUri.getLastPathSegment();
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);

        // EXTRA_TEXT should be an ArrayList, but some clients are expecting a single String.
        // So, to avoid an exception on Intent.migrateExtraStreamToClipData(), we need to manually
        // create the ClipData object with the attachments URIs.
        final StringBuilder messageBody = new StringBuilder("Build info: ")
            .append(SystemProperties.get("ro.build.description"))
            .append("\nSerial number: ")
            .append(SystemProperties.get("ro.serialno"));
        int descriptionLength = 0;
        if (!TextUtils.isEmpty(info.getDescription())) {
            messageBody.append("\nDescription: ").append(info.getDescription());
            descriptionLength = info.getDescription().length();
        }
        intent.putExtra(Intent.EXTRA_TEXT, messageBody.toString());
        final ClipData clipData = new ClipData(null, new String[] { mimeType },
                new ClipData.Item(null, null, null, bugreportUri));
        Log.d(TAG, "share intent: bureportUri=" + bugreportUri);
        final ArrayList<Uri> attachments = Lists.newArrayList(bugreportUri);
        for (File screenshot : info.screenshotFiles) {
            final Uri screenshotUri = getUri(context, screenshot);
            Log.d(TAG, "share intent: screenshotUri=" + screenshotUri);
            clipData.addItem(new ClipData.Item(null, null, null, screenshotUri));
            attachments.add(screenshotUri);
        }
        intent.setClipData(clipData);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);

        final Pair<UserHandle, Account> sendToAccount = findSendToAccount(context,
                SystemProperties.get("sendbug.preferred.domain"));
        if (sendToAccount != null) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[] { sendToAccount.second.name });

            // TODO Open the chooser activity on work profile by default.
            // If we just use startActivityAsUser(), then the launched app couldn't read
            // attachments.
            // We probably need to change ChooserActivity to take an extra argument for the
            // default profile.
        }

        // Log what was sent to the intent
        Log.d(TAG, "share intent: EXTRA_SUBJECT=" + subject + ", EXTRA_TEXT=" + messageBody.length()
                + " chars, description=" + descriptionLength + " chars");

        return intent;
    }

    /**
     * Shares the bugreport upon user's request by issuing a {@link Intent#ACTION_SEND_MULTIPLE}
     * intent, but issuing a warning dialog the first time.
     */
    private void shareBugreport(int id, BugreportInfo sharedInfo) {
        MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_NOTIFICATION_ACTION_SHARE);
        BugreportInfo info;
        synchronized (mLock) {
            info = getInfoLocked(id);
        }
        if (info == null) {
            // Service was terminated but notification persisted
            info = sharedInfo;
            synchronized (mLock) {
                Log.d(TAG, "shareBugreport(): no info for ID " + id + " on managed processes ("
                        + mBugreportInfos + "), using info from intent instead (" + info + ")");
            }
        } else {
            Log.v(TAG, "shareBugReport(): id " + id + " info = " + info);
        }

        addDetailsToZipFile(info);

        final Intent sendIntent = buildSendIntent(mContext, info);
        if (sendIntent == null) {
            Log.w(TAG, "Stopping progres on ID " + id + " because share intent could not be built");
            synchronized (mLock) {
                stopProgressLocked(id);
            }
            return;
        }

        final Intent notifIntent;
        boolean useChooser = true;

        // Send through warning dialog by default
        if (getWarningState(mContext, STATE_UNKNOWN) != STATE_HIDE) {
            notifIntent = buildWarningIntent(mContext, sendIntent);
            // No need to show a chooser in this case.
            useChooser = false;
        } else {
            notifIntent = sendIntent;
        }
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Send the share intent...
        if (useChooser) {
            sendShareIntent(mContext, notifIntent);
        } else {
            mContext.startActivity(notifIntent);
        }
        synchronized (mLock) {
            // ... and stop watching this process.
            stopProgressLocked(id);
        }
    }

    static void sendShareIntent(Context context, Intent intent) {
        final Intent chooserIntent = Intent.createChooser(intent,
                context.getResources().getText(R.string.bugreport_intent_chooser_title));

        // Since we may be launched behind lockscreen, make sure that ChooserActivity doesn't finish
        // itself in onStop.
        chooserIntent.putExtra(ChooserActivity.EXTRA_PRIVATE_RETAIN_IN_ON_STOP, true);
        // Starting the activity from a service.
        chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(chooserIntent);
    }

    /**
     * Sends a notification indicating the bugreport has finished so use can share it.
     */
    private void sendBugreportNotification(BugreportInfo info, boolean takingScreenshot) {

        // Since adding the details can take a while, do it before notifying user.
        addDetailsToZipFile(info);

        final Intent shareIntent = new Intent(INTENT_BUGREPORT_SHARE);
        shareIntent.setClass(mContext, BugreportProgressService.class);
        shareIntent.setAction(INTENT_BUGREPORT_SHARE);
        shareIntent.putExtra(EXTRA_ID, info.id);
        shareIntent.putExtra(EXTRA_INFO, info);

        String content;
        content = takingScreenshot ?
                mContext.getString(R.string.bugreport_finished_pending_screenshot_text)
                : mContext.getString(R.string.bugreport_finished_text);
        final String title;
        if (TextUtils.isEmpty(info.getTitle())) {
            title = mContext.getString(R.string.bugreport_finished_title, info.id);
        } else {
            title = info.getTitle();
            if (!TextUtils.isEmpty(info.shareDescription)) {
                if(!takingScreenshot) content = info.shareDescription;
            }
        }

        final Notification.Builder builder = newBaseNotification(mContext)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(content)
                .setContentIntent(PendingIntent.getService(mContext, info.id, shareIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .setOnlyAlertOnce(false)
                .setDeleteIntent(newCancelIntent(mContext, info));

        if (!TextUtils.isEmpty(info.getName())) {
            builder.setSubText(info.getName());
        }

        Log.v(TAG, "Sending 'Share' notification for ID " + info.id + ": " + title);
        NotificationManager.from(mContext).notify(info.id, builder.build());
    }

    /**
     * Sends a notification indicating the bugreport is being updated so the user can wait until it
     * finishes - at this point there is nothing to be done other than waiting, hence it has no
     * pending action.
     */
    private void sendBugreportBeingUpdatedNotification(Context context, int id) {
        final String title = context.getString(R.string.bugreport_updating_title);
        final Notification.Builder builder = newBaseNotification(context)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(context.getString(R.string.bugreport_updating_wait));
        Log.v(TAG, "Sending 'Updating zip' notification for ID " + id + ": " + title);
        sendForegroundabledNotification(id, builder.build());
    }

    private static Notification.Builder newBaseNotification(Context context) {
        synchronized (sNotificationBundle) {
            if (sNotificationBundle.isEmpty()) {
                // Rename notifcations from "Shell" to "Android System"
                sNotificationBundle.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME,
                        context.getString(com.android.internal.R.string.android_system_label));
            }
        }
        return new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .addExtras(sNotificationBundle)
                .setSmallIcon(R.drawable.ic_bug_report_black_24dp)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .setOnlyAlertOnce(true)
                .extend(new Notification.TvExtender());
    }

    /**
     * Sends a zipped bugreport notification.
     */
    private void sendZippedBugreportNotification( final BugreportInfo info,
            final boolean takingScreenshot) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Looper.prepare();
                zipBugreport(info);
                sendBugreportNotification(info, takingScreenshot);
                return null;
            }
        }.execute();
    }

    /**
     * Zips a bugreport file, returning the path to the new file (or to the
     * original in case of failure).
     */
    private static void zipBugreport(BugreportInfo info) {
        final String bugreportPath = info.bugreportFile.getAbsolutePath();
        final String zippedPath = bugreportPath.replace(".txt", ".zip");
        Log.v(TAG, "zipping " + bugreportPath + " as " + zippedPath);
        final File bugreportZippedFile = new File(zippedPath);
        try (InputStream is = new FileInputStream(info.bugreportFile);
                ZipOutputStream zos = new ZipOutputStream(
                        new BufferedOutputStream(new FileOutputStream(bugreportZippedFile)))) {
            addEntry(zos, info.bugreportFile.getName(), is);
            // Delete old file
            final boolean deleted = info.bugreportFile.delete();
            if (deleted) {
                Log.v(TAG, "deleted original bugreport (" + bugreportPath + ")");
            } else {
                Log.e(TAG, "could not delete original bugreport (" + bugreportPath + ")");
            }
            info.bugreportFile = bugreportZippedFile;
        } catch (IOException e) {
            Log.e(TAG, "exception zipping file " + zippedPath, e);
        }
    }

    /**
     * Adds the user-provided info into the bugreport zip file.
     * <p>
     * If user provided a title, it will be saved into a {@code title.txt} entry; similarly, the
     * description will be saved on {@code description.txt}.
     */
    private void addDetailsToZipFile(BugreportInfo info) {
        synchronized (mLock) {
            addDetailsToZipFileLocked(info);
        }
    }

    @GuardedBy("mLock")
    private void addDetailsToZipFileLocked(BugreportInfo info) {
        if (info.bugreportFile == null) {
            // One possible reason is a bug in the Parcelization code.
            Log.wtf(TAG, "addDetailsToZipFile(): no bugreportFile on " + info);
            return;
        }
        if (TextUtils.isEmpty(info.getTitle()) && TextUtils.isEmpty(info.getDescription())) {
            Log.d(TAG, "Not touching zip file since neither title nor description are set");
            return;
        }
        if (info.addedDetailsToZip || info.addingDetailsToZip) {
            Log.d(TAG, "Already added details to zip file for " + info);
            return;
        }
        info.addingDetailsToZip = true;

        // It's not possible to add a new entry into an existing file, so we need to create a new
        // zip, copy all entries, then rename it.
        sendBugreportBeingUpdatedNotification(mContext, info.id); // ...and that takes time

        final File dir = info.bugreportFile.getParentFile();
        final File tmpZip = new File(dir, "tmp-" + info.bugreportFile.getName());
        Log.d(TAG, "Writing temporary zip file (" + tmpZip + ") with title and/or description");
        try (ZipFile oldZip = new ZipFile(info.bugreportFile);
                ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmpZip))) {

            // First copy contents from original zip.
            Enumeration<? extends ZipEntry> entries = oldZip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry entry = entries.nextElement();
                final String entryName = entry.getName();
                if (!entry.isDirectory()) {
                    addEntry(zos, entryName, entry.getTime(), oldZip.getInputStream(entry));
                } else {
                    Log.w(TAG, "skipping directory entry: " + entryName);
                }
            }

            // Then add the user-provided info.
            addEntry(zos, "title.txt", info.getTitle());
            addEntry(zos, "description.txt", info.getDescription());
        } catch (IOException e) {
            Log.e(TAG, "exception zipping file " + tmpZip, e);
            Toast.makeText(mContext, R.string.bugreport_add_details_to_zip_failed,
                    Toast.LENGTH_LONG).show();
            return;
        } finally {
            // Make sure it only tries to add details once, even it fails the first time.
            info.addedDetailsToZip = true;
            info.addingDetailsToZip = false;
            stopForegroundWhenDoneLocked(info.id);
        }

        if (!tmpZip.renameTo(info.bugreportFile)) {
            Log.e(TAG, "Could not rename " + tmpZip + " to " + info.bugreportFile);
        }
    }

    private static void addEntry(ZipOutputStream zos, String entry, String text)
            throws IOException {
        if (DEBUG) Log.v(TAG, "adding entry '" + entry + "': " + text);
        if (!TextUtils.isEmpty(text)) {
            addEntry(zos, entry, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static void addEntry(ZipOutputStream zos, String entryName, InputStream is)
            throws IOException {
        addEntry(zos, entryName, System.currentTimeMillis(), is);
    }

    private static void addEntry(ZipOutputStream zos, String entryName, long timestamp,
            InputStream is) throws IOException {
        final ZipEntry entry = new ZipEntry(entryName);
        entry.setTime(timestamp);
        zos.putNextEntry(entry);
        final int totalBytes = Streams.copy(is, zos);
        if (DEBUG) Log.v(TAG, "size of '" + entryName + "' entry: " + totalBytes + " bytes");
        zos.closeEntry();
    }

    /**
     * Find the best matching {@link Account} based on build properties.  If none found, returns
     * the first account that looks like an email address.
     */
    @VisibleForTesting
    static Pair<UserHandle, Account> findSendToAccount(Context context, String preferredDomain) {
        final UserManager um = context.getSystemService(UserManager.class);
        final AccountManager am = context.getSystemService(AccountManager.class);

        if (preferredDomain != null && !preferredDomain.startsWith("@")) {
            preferredDomain = "@" + preferredDomain;
        }

        Pair<UserHandle, Account> first = null;

        for (UserHandle user : um.getUserProfiles()) {
            final Account[] accounts;
            try {
                accounts = am.getAccountsAsUser(user.getIdentifier());
            } catch (RuntimeException e) {
                Log.e(TAG, "Could not get accounts for preferred domain " + preferredDomain
                        + " for user " + user, e);
                continue;
            }
            if (DEBUG) Log.d(TAG, "User: " + user + "  Number of accounts: " + accounts.length);
            for (Account account : accounts) {
                if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                    final Pair<UserHandle, Account> candidate = Pair.create(user, account);

                    if (!TextUtils.isEmpty(preferredDomain)) {
                        // if we have a preferred domain and it matches, return; otherwise keep
                        // looking
                        if (account.name.endsWith(preferredDomain)) {
                            return candidate;
                        }
                        // if we don't have a preferred domain, just return since it looks like
                        // an email address
                    } else {
                        return candidate;
                    }
                    if (first == null) {
                        first = candidate;
                    }
                }
            }
        }
        return first;
    }

    static Uri getUri(Context context, File file) {
        return file != null ? FileProvider.getUriForFile(context, AUTHORITY, file) : null;
    }

    static File getFileExtra(Intent intent, String key) {
        final String path = intent.getStringExtra(key);
        if (path != null) {
            return new File(path);
        } else {
            return null;
        }
    }

    /**
     * Dumps an intent, extracting the relevant extras.
     */
    static String dumpIntent(Intent intent) {
        if (intent == null) {
            return "NO INTENT";
        }
        String action = intent.getAction();
        if (action == null) {
            // Happens when startService is called...
            action = "no action";
        }
        final StringBuilder buffer = new StringBuilder(action).append(" extras: ");
        addExtra(buffer, intent, EXTRA_ID);
        addExtra(buffer, intent, EXTRA_NAME);
        addExtra(buffer, intent, EXTRA_DESCRIPTION);
        addExtra(buffer, intent, EXTRA_BUGREPORT);
        addExtra(buffer, intent, EXTRA_SCREENSHOT);
        addExtra(buffer, intent, EXTRA_INFO);
        addExtra(buffer, intent, EXTRA_TITLE);

        if (intent.hasExtra(EXTRA_ORIGINAL_INTENT)) {
            buffer.append(SHORT_EXTRA_ORIGINAL_INTENT).append(": ");
            final Intent originalIntent = intent.getParcelableExtra(EXTRA_ORIGINAL_INTENT);
            buffer.append(dumpIntent(originalIntent));
        } else {
            buffer.append("no ").append(SHORT_EXTRA_ORIGINAL_INTENT);
        }

        return buffer.toString();
    }

    private static final String SHORT_EXTRA_ORIGINAL_INTENT =
            EXTRA_ORIGINAL_INTENT.substring(EXTRA_ORIGINAL_INTENT.lastIndexOf('.') + 1);

    private static void addExtra(StringBuilder buffer, Intent intent, String name) {
        final String shortName = name.substring(name.lastIndexOf('.') + 1);
        if (intent.hasExtra(name)) {
            buffer.append(shortName).append('=').append(intent.getExtra(name));
        } else {
            buffer.append("no ").append(shortName);
        }
        buffer.append(", ");
    }

    private static boolean setSystemProperty(String key, String value) {
        try {
            if (DEBUG) Log.v(TAG, "Setting system property " + key + " to " + value);
            SystemProperties.set(key, value);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not set property " + key + " to " + value, e);
            return false;
        }
        return true;
    }

    /**
     * Updates the user-provided details of a bugreport.
     */
    private void updateBugreportInfo(int id, String name, String title, String description) {
        final BugreportInfo info;
        synchronized (mLock) {
            info = getInfoLocked(id);
        }
        if (info == null) {
            return;
        }
        if (title != null && !title.equals(info.getTitle())) {
            Log.d(TAG, "updating bugreport title: " + title);
            MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_DETAILS_TITLE_CHANGED);
        }
        info.setTitle(title);
        if (description != null && !description.equals(info.getDescription())) {
            Log.d(TAG, "updating bugreport description: " + description.length() + " chars");
            MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_DETAILS_DESCRIPTION_CHANGED);
        }
        info.setDescription(description);
        if (name != null && !name.equals(info.getName())) {
            Log.d(TAG, "updating bugreport name: " + name);
            MetricsLogger.action(this, MetricsEvent.ACTION_BUGREPORT_DETAILS_NAME_CHANGED);
            info.setName(name);
            updateProgress(info);
        }
    }

    private void collapseNotificationBar() {
        sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
    }

    private static Looper newLooper(String name) {
        final HandlerThread thread = new HandlerThread(name, THREAD_PRIORITY_BACKGROUND);
        thread.start();
        return thread.getLooper();
    }

    /**
     * Takes a screenshot and save it to the given location.
     */
    private static boolean takeScreenshot(Context context, String path) {
        final Bitmap bitmap = Screenshooter.takeScreenshot();
        if (bitmap == null) {
            return false;
        }
        try (final FileOutputStream fos = new FileOutputStream(path)) {
            if (bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)) {
                ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE)).vibrate(150);
                return true;
            } else {
                Log.e(TAG, "Failed to save screenshot on " + path);
            }
        } catch (IOException e ) {
            Log.e(TAG, "Failed to save screenshot on " + path, e);
            return false;
        } finally {
            bitmap.recycle();
        }
        return false;
    }

    static boolean isTv(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /**
     * Checks whether a character is valid on bugreport names.
     */
    @VisibleForTesting
    static boolean isValid(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '-';
    }

    /**
     * A local binder with interface to return an instance of BugreportProgressService for the
     * purpose of testing.
     */
    final class LocalBinder extends Binder {
        @VisibleForTesting BugreportProgressService getService() {
            return BugreportProgressService.this;
        }
    }

    /**
     * Helper class encapsulating the UI elements and logic used to display a dialog where user
     * can change the details of a bugreport.
     */
    private final class BugreportInfoDialog {
        private EditText mInfoName;
        private EditText mInfoTitle;
        private EditText mInfoDescription;
        private AlertDialog mDialog;
        private Button mOkButton;
        private int mId;

        /**
         * Sets its internal state and displays the dialog.
         */
        @MainThread
        void initialize(final Context context, BugreportInfo info) {
            final String dialogTitle =
                    context.getString(R.string.bugreport_info_dialog_title, info.id);
            final Context themedContext = new ContextThemeWrapper(
                    context, com.android.internal.R.style.Theme_DeviceDefault_DayNight);
            // First initializes singleton.
            if (mDialog == null) {
                @SuppressLint("InflateParams")
                // It's ok pass null ViewRoot on AlertDialogs.
                final View view = View.inflate(themedContext, R.layout.dialog_bugreport_info, null);

                mInfoName = (EditText) view.findViewById(R.id.name);
                mInfoTitle = (EditText) view.findViewById(R.id.title);
                mInfoDescription = (EditText) view.findViewById(R.id.description);
                mDialog = new AlertDialog.Builder(themedContext)
                        .setView(view)
                        .setTitle(dialogTitle)
                        .setCancelable(true)
                        .setPositiveButton(context.getString(R.string.save),
                                null)
                        .setNegativeButton(context.getString(com.android.internal.R.string.cancel),
                                new DialogInterface.OnClickListener()
                                {
                                    @Override
                                    public void onClick(DialogInterface dialog, int id)
                                    {
                                        MetricsLogger.action(context,
                                                MetricsEvent.ACTION_BUGREPORT_DETAILS_CANCELED);
                                    }
                                })
                        .create();

                mDialog.getWindow().setAttributes(
                        new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG));

            } else {
                // Re-use view, but reset fields first.
                mDialog.setTitle(dialogTitle);
                mInfoName.setText(null);
                mInfoName.setEnabled(true);
                mInfoTitle.setText(null);
                mInfoDescription.setText(null);
            }

            // Then set fields.
            mId = info.id;
            if (!TextUtils.isEmpty(info.getName())) {
                mInfoName.setText(info.getName());
            }
            if (!TextUtils.isEmpty(info.getTitle())) {
                mInfoTitle.setText(info.getTitle());
            }
            if (!TextUtils.isEmpty(info.getDescription())) {
                mInfoDescription.setText(info.getDescription());
            }

            // And finally display it.
            mDialog.show();

            // TODO: in a traditional AlertDialog, when the positive button is clicked the
            // dialog is always closed, but we need to validate the name first, so we need to
            // get a reference to it, which is only available after it's displayed.
            // It would be cleaner to use a regular dialog instead, but let's keep this
            // workaround for now and change it later, when we add another button to take
            // extra screenshots.
            if (mOkButton == null) {
                mOkButton = mDialog.getButton(DialogInterface.BUTTON_POSITIVE);
                mOkButton.setOnClickListener(new View.OnClickListener() {

                    @Override
                    public void onClick(View view) {
                        MetricsLogger.action(context, MetricsEvent.ACTION_BUGREPORT_DETAILS_SAVED);
                        sanitizeName(info.getName());
                        final String name = mInfoName.getText().toString();
                        final String title = mInfoTitle.getText().toString();
                        final String description = mInfoDescription.getText().toString();

                        updateBugreportInfo(mId, name, title, description);
                        mDialog.dismiss();
                    }
                });
            }
        }

        /**
         * Sanitizes the user-provided value for the {@code name} field, automatically replacing
         * invalid characters if necessary.
         */
        private void sanitizeName(String savedName) {
            String name = mInfoName.getText().toString();
            if (name.equals(savedName)) {
                if (DEBUG) Log.v(TAG, "name didn't change, no need to sanitize: " + name);
                return;
            }
            final StringBuilder safeName = new StringBuilder(name.length());
            boolean changed = false;
            for (int i = 0; i < name.length(); i++) {
                final char c = name.charAt(i);
                if (isValid(c)) {
                    safeName.append(c);
                } else {
                    changed = true;
                    safeName.append('_');
                }
            }
            if (changed) {
                Log.v(TAG, "changed invalid name '" + name + "' to '" + safeName + "'");
                name = safeName.toString();
                mInfoName.setText(name);
            }
        }

        /**
         * Notifies the dialog that the bugreport has finished so it disables the {@code name}
         * field.
         * <p>Once the bugreport is finished dumpstate has already generated the final files, so
         * changing the name would have no effect.
         */
        void onBugreportFinished(BugreportInfo info) {
            if (mId == info.id && mInfoName != null) {
                mInfoName.setEnabled(false);
                mInfoName.setText(null);
                if (!TextUtils.isEmpty(info.getName())) {
                    mInfoName.setText(info.getName());
                }
            }
        }

        void cancel() {
            if (mDialog != null) {
                mDialog.cancel();
            }
        }
    }

    /**
     * Information about a bugreport process while its in progress.
     */
    private static final class BugreportInfo implements Parcelable {
        private final Context context;

        /**
         * Sequential, user-friendly id used to identify the bugreport.
         */
        int id;

        /**
         * Prefix name of the bugreport, this is uneditable.
         * The baseName consists of the string "bugreport" + deviceName + buildID
         * This will end with the string "wifi"/"telephony" for wifi/telephony bugreports.
         * Bugreport zip file name  = "<baseName>-<name>.zip"
         */
        private final String baseName;

        /**
         * Suffix name of the bugreport/screenshot, is set to timestamp initially. User can make
         * modifications to this using interface.
         */
        private String name;

        /**
         * Initial value of the field name. This is required to rename the files later on, as they
         * are created using initial value of name.
         */
        private final String initialName;

        /**
         * User-provided, one-line summary of the bug; when set, will be used as the subject
         * of the {@link Intent#ACTION_SEND_MULTIPLE} intent.
         */
        private String title;

        /**
         * One-line summary of the bug; when set, will be used as the subject of the
         * {@link Intent#ACTION_SEND_MULTIPLE} intent. This is the predefined title which is
         * set initially when the request to take a bugreport is made. This overrides any changes
         * in the title that the user makes after the bugreport starts.
         */
        private final String shareTitle;

        /**
         * User-provided, detailed description of the bugreport; when set, will be added to the body
         * of the {@link Intent#ACTION_SEND_MULTIPLE} intent. This is shown in the app where the
         * bugreport is being shared as an attachment. This is not related/dependant on
         * {@code shareDescription}.
         */
        private String description;

        /**
         * Current value of progress (in percentage) of the bugreport generation as
         * displayed by the UI.
         */
        final AtomicInteger progress = new AtomicInteger(0);

        /**
         * Last value of progress (in percentage) of the bugreport generation for which
         * system notification was updated.
         */
        final AtomicInteger lastProgress = new AtomicInteger(0);

        /**
         * Time of the last progress update.
         */
        final AtomicLong lastUpdate = new AtomicLong(System.currentTimeMillis());

        /**
         * Time of the last progress update when Parcel was created.
         */
        String formattedLastUpdate;

        /**
         * Path of the main bugreport file.
         */
        File bugreportFile;

        /**
         * Path of the screenshot files.
         */
        List<File> screenshotFiles = new ArrayList<>(1);

        /**
         * Whether dumpstate sent an intent informing it has finished.
         */
        final AtomicBoolean finished = new AtomicBoolean(false);

        /**
         * Whether the details entries have been added to the bugreport yet.
         */
        boolean addingDetailsToZip;
        boolean addedDetailsToZip;

        /**
         * Internal counter used to name screenshot files.
         */
        int screenshotCounter;

        /**
         * Descriptive text that will be shown to the user in the notification message. This is the
         * predefined description which is set initially when the request to take a bugreport is
         * made.
         */
        private final String shareDescription;

        /**
         * Type of the bugreport
         */
        final int type;

        /**
         * Nonce of the bugreport
         */
        final long nonce;

        private final Object mLock = new Object();

        /**
         * Constructor for tracked bugreports - typically called upon receiving BUGREPORT_REQUESTED.
         */
        BugreportInfo(Context context, String baseName, String name,
                @Nullable String shareTitle, @Nullable String shareDescription,
                @BugreportParams.BugreportMode int type, File bugreportsDir, long nonce) {
            this.context = context;
            this.name = this.initialName = name;
            this.shareTitle = shareTitle == null ? "" : shareTitle;
            this.shareDescription = shareDescription == null ? "" : shareDescription;
            this.type = type;
            this.nonce = nonce;
            this.baseName = baseName;
            this.bugreportFile = new File(bugreportsDir, getFileName(this, ".zip"));
        }

        void createBugreportFile() {
            createReadWriteFile(bugreportFile);
        }

        void createScreenshotFile(File bugreportsDir) {
            File screenshotFile = new File(bugreportsDir, getScreenshotName("default"));
            addScreenshot(screenshotFile);
            createReadWriteFile(screenshotFile);
        }

        ParcelFileDescriptor getBugreportFd() {
            return getFd(bugreportFile);
        }

        ParcelFileDescriptor getDefaultScreenshotFd() {
            if (screenshotFiles.isEmpty()) {
                return null;
            }
            return getFd(screenshotFiles.get(0));
        }

        void setTitle(String title) {
            synchronized (mLock) {
                this.title = title;
            }
        }

        String getTitle() {
            synchronized (mLock) {
                return title;
            }
        }

        void setName(String name) {
            synchronized (mLock) {
                this.name = name;
            }
        }

        String getName() {
            synchronized (mLock) {
                return name;
            }
        }

        void setDescription(String description) {
            synchronized (mLock) {
                this.description = description;
            }
        }

        String getDescription() {
            synchronized (mLock) {
                return description;
            }
        }

        /**
         * Gets the name for next user triggered screenshot file.
         */
        String getPathNextScreenshot() {
            screenshotCounter ++;
            return getScreenshotName(Integer.toString(screenshotCounter));
        }

        /**
         * Gets the name for screenshot file based on the suffix that is passed.
         */
        String getScreenshotName(String suffix) {
            return "screenshot-" + initialName + "-" + suffix + ".png";
        }

        /**
         * Saves the location of a taken screenshot so it can be sent out at the end.
         */
        void addScreenshot(File screenshot) {
            screenshotFiles.add(screenshot);
        }

        /**
         * Deletes all screenshots taken for a given bugreport.
         */
        private void deleteScreenshots() {
            for (File file : screenshotFiles) {
                Log.i(TAG, "Deleting screenshot file " + file);
                file.delete();
            }
        }

        /**
         * Deletes bugreport file for a given bugreport.
         */
        private void deleteBugreportFile() {
            Log.i(TAG, "Deleting bugreport file " + bugreportFile);
            bugreportFile.delete();
        }

        /**
         * Deletes empty files for a given bugreport.
         */
        private void deleteEmptyFiles() {
            if (bugreportFile.length() == 0) {
                Log.i(TAG, "Deleting empty bugreport file: " + bugreportFile);
                bugreportFile.delete();
            }
            deleteEmptyScreenshots();
        }

        /**
         * Deletes empty screenshot files.
         */
        private void deleteEmptyScreenshots() {
            screenshotFiles.removeIf(file -> {
                final long length = file.length();
                if (length == 0) {
                    Log.i(TAG, "Deleting empty screenshot file: " + file);
                    file.delete();
                }
                return length == 0;
            });
        }

        /**
         * Rename all screenshots files so that they contain the new {@code name} instead of the
         * {@code initialName} if user has changed it.
         */
        void renameScreenshots() {
            deleteEmptyScreenshots();
            if (TextUtils.isEmpty(name) || screenshotFiles.isEmpty()) {
                return;
            }
            final List<File> renamedFiles = new ArrayList<>(screenshotFiles.size());
            for (File oldFile : screenshotFiles) {
                final String oldName = oldFile.getName();
                final String newName = oldName.replaceFirst(initialName, name);
                final File newFile;
                if (!newName.equals(oldName)) {
                    final File renamedFile = new File(oldFile.getParentFile(), newName);
                    Log.d(TAG, "Renaming screenshot file " + oldFile + " to " + renamedFile);
                    newFile = oldFile.renameTo(renamedFile) ? renamedFile : oldFile;
                } else {
                    Log.w(TAG, "Name didn't change: " + oldName);
                    newFile = oldFile;
                }
                if (newFile.length() > 0) {
                    renamedFiles.add(newFile);
                } else if (newFile.delete()) {
                    Log.d(TAG, "screenshot file: " + newFile + " deleted successfully.");
                }
            }
            screenshotFiles = renamedFiles;
        }

        /**
         * Rename bugreport file to include the name given by user via UI
         */
        void renameBugreportFile() {
            File newBugreportFile = new File(bugreportFile.getParentFile(),
                    getFileName(this, ".zip"));
            if (!newBugreportFile.getPath().equals(bugreportFile.getPath())) {
                if (bugreportFile.renameTo(newBugreportFile)) {
                    bugreportFile = newBugreportFile;
                }
            }
        }

        String getFormattedLastUpdate() {
            if (context == null) {
                // Restored from Parcel
                return formattedLastUpdate == null ?
                        Long.toString(lastUpdate.longValue()) : formattedLastUpdate;
            }
            return DateUtils.formatDateTime(context, lastUpdate.longValue(),
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        }

        @Override
        public String toString() {

            final StringBuilder builder = new StringBuilder()
                    .append("\tid: ").append(id)
                    .append(", baseName: ").append(baseName)
                    .append(", name: ").append(name)
                    .append(", initialName: ").append(initialName)
                    .append(", finished: ").append(finished)
                    .append("\n\ttitle: ").append(title)
                    .append("\n\tdescription: ");
            if (description == null) {
                builder.append("null");
            } else {
                if (TextUtils.getTrimmedLength(description) == 0) {
                    builder.append("empty ");
                }
                builder.append("(").append(description.length()).append(" chars)");
            }

            return builder
                .append("\n\tfile: ").append(bugreportFile)
                .append("\n\tscreenshots: ").append(screenshotFiles)
                .append("\n\tprogress: ").append(progress)
                .append("\n\tlast_update: ").append(getFormattedLastUpdate())
                .append("\n\taddingDetailsToZip: ").append(addingDetailsToZip)
                .append(" addedDetailsToZip: ").append(addedDetailsToZip)
                .append("\n\tshareDescription: ").append(shareDescription)
                .append("\n\tshareTitle: ").append(shareTitle)
                .toString();
        }

        // Parcelable contract
        protected BugreportInfo(Parcel in) {
            context = null;
            id = in.readInt();
            baseName = in.readString();
            name = in.readString();
            initialName = in.readString();
            title = in.readString();
            shareTitle = in.readString();
            description = in.readString();
            progress.set(in.readInt());
            lastProgress.set(in.readInt());
            lastUpdate.set(in.readLong());
            formattedLastUpdate = in.readString();
            bugreportFile = readFile(in);

            int screenshotSize = in.readInt();
            for (int i = 1; i <= screenshotSize; i++) {
                  screenshotFiles.add(readFile(in));
            }

            finished.set(in.readInt() == 1);
            addingDetailsToZip = in.readBoolean();
            addedDetailsToZip = in.readBoolean();
            screenshotCounter = in.readInt();
            shareDescription = in.readString();
            type = in.readInt();
            nonce = in.readLong();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeString(baseName);
            dest.writeString(name);
            dest.writeString(initialName);
            dest.writeString(title);
            dest.writeString(shareTitle);
            dest.writeString(description);
            dest.writeInt(progress.intValue());
            dest.writeInt(lastProgress.intValue());
            dest.writeLong(lastUpdate.longValue());
            dest.writeString(getFormattedLastUpdate());
            writeFile(dest, bugreportFile);

            dest.writeInt(screenshotFiles.size());
            for (File screenshotFile : screenshotFiles) {
                writeFile(dest, screenshotFile);
            }

            dest.writeInt(finished.get() ? 1 : 0);
            dest.writeBoolean(addingDetailsToZip);
            dest.writeBoolean(addedDetailsToZip);
            dest.writeInt(screenshotCounter);
            dest.writeString(shareDescription);
            dest.writeInt(type);
            dest.writeLong(nonce);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        private void writeFile(Parcel dest, File file) {
            dest.writeString(file == null ? null : file.getPath());
        }

        private File readFile(Parcel in) {
            final String path = in.readString();
            return path == null ? null : new File(path);
        }

        @SuppressWarnings("unused")
        public static final Parcelable.Creator<BugreportInfo> CREATOR =
                new Parcelable.Creator<BugreportInfo>() {
            @Override
            public BugreportInfo createFromParcel(Parcel source) {
                return new BugreportInfo(source);
            }

            @Override
            public BugreportInfo[] newArray(int size) {
                return new BugreportInfo[size];
            }
        };
    }

    @GuardedBy("mLock")
    private void checkProgressUpdatedLocked(BugreportInfo info, int progress) {
        if (progress > CAPPED_PROGRESS) {
            progress = CAPPED_PROGRESS;
        }
        if (DEBUG) {
            if (progress != info.progress.intValue()) {
                Log.v(TAG, "Updating progress for name " + info.getName() + "(id: " + info.id
                        + ") from " + info.progress.intValue() + " to " + progress);
            }
        }
        info.progress.set(progress);
        info.lastUpdate.set(System.currentTimeMillis());

        updateProgress(info);
    }
}
