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

import static android.os.Process.THREAD_PRIORITY_BACKGROUND;
import static com.android.shell.BugreportPrefs.STATE_SHOW;
import static com.android.shell.BugreportPrefs.getWarningState;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import libcore.io.Streams;

import com.android.internal.annotations.VisibleForTesting;
import com.google.android.collect.Lists;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.os.Vibrator;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.SparseArray;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

/**
 * Service used to keep progress of bugreport processes ({@code dumpstate}).
 * <p>
 * The workflow is:
 * <ol>
 * <li>When {@code dumpstate} starts, it sends a {@code BUGREPORT_STARTED} with its pid and the
 * estimated total effort.
 * <li>{@link BugreportReceiver} receives the intent and delegates it to this service.
 * <li>Upon start, this service:
 * <ol>
 * <li>Issues a system notification so user can watch the progresss (which is 0% initially).
 * <li>Polls the {@link SystemProperties} for updates on the {@code dumpstate} progress.
 * <li>If the progress changed, it updates the system notification.
 * </ol>
 * <li>As {@code dumpstate} progresses, it updates the system property.
 * <li>When {@code dumpstate} finishes, it sends a {@code BUGREPORT_FINISHED} intent.
 * <li>{@link BugreportReceiver} receives the intent and delegates it to this service, which in
 * turn:
 * <ol>
 * <li>Updates the system notification so user can share the bugreport.
 * <li>Stops monitoring that {@code dumpstate} process.
 * <li>Stops itself if it doesn't have any process left to monitor.
 * </ol>
 * </ol>
 */
public class BugreportProgressService extends Service {
    private static final String TAG = "BugreportProgressService";
    private static final boolean DEBUG = false;

    private static final String AUTHORITY = "com.android.shell";

    // External intents sent by dumpstate.
    static final String INTENT_BUGREPORT_STARTED = "android.intent.action.BUGREPORT_STARTED";
    static final String INTENT_BUGREPORT_FINISHED = "android.intent.action.BUGREPORT_FINISHED";
    static final String INTENT_REMOTE_BUGREPORT_FINISHED =
            "android.intent.action.REMOTE_BUGREPORT_FINISHED";
    static final String INTENT_REMOTE_BUGREPORT_DISPATCH =
            "android.intent.action.REMOTE_BUGREPORT_DISPATCH";

    // Internal intents used on notification actions.
    static final String INTENT_BUGREPORT_CANCEL = "android.intent.action.BUGREPORT_CANCEL";
    static final String INTENT_BUGREPORT_SHARE = "android.intent.action.BUGREPORT_SHARE";
    static final String INTENT_BUGREPORT_INFO_LAUNCH =
            "android.intent.action.BUGREPORT_INFO_LAUNCH";
    static final String INTENT_BUGREPORT_SCREENSHOT =
            "android.intent.action.BUGREPORT_SCREENSHOT";

    static final String EXTRA_BUGREPORT = "android.intent.extra.BUGREPORT";
    static final String EXTRA_SCREENSHOT = "android.intent.extra.SCREENSHOT";
    static final String EXTRA_PID = "android.intent.extra.PID";
    static final String EXTRA_MAX = "android.intent.extra.MAX";
    static final String EXTRA_NAME = "android.intent.extra.NAME";
    static final String EXTRA_TITLE = "android.intent.extra.TITLE";
    static final String EXTRA_DESCRIPTION = "android.intent.extra.DESCRIPTION";
    static final String EXTRA_ORIGINAL_INTENT = "android.intent.extra.ORIGINAL_INTENT";
    static final String EXTRA_INFO = "android.intent.extra.INFO";

    private static final int MSG_SERVICE_COMMAND = 1;
    private static final int MSG_POLL = 2;
    private static final int MSG_DELAYED_SCREENSHOT = 3;
    private static final int MSG_SCREENSHOT_REQUEST = 4;
    private static final int MSG_SCREENSHOT_RESPONSE = 5;

    /**
     * Delay before a screenshot is taken.
     * <p>
     * Should be at least 3 seconds, otherwise its toast might show up in the screenshot.
     */
    static final int SCREENSHOT_DELAY_SECONDS = 3;

    /** Polling frequency, in milliseconds. */
    static final long POLLING_FREQUENCY = 2 * DateUtils.SECOND_IN_MILLIS;

    /** How long (in ms) a dumpstate process will be monitored if it didn't show progress. */
    private static final long INACTIVITY_TIMEOUT = 3 * DateUtils.MINUTE_IN_MILLIS;

    /** System properties used for monitoring progress. */
    private static final String DUMPSTATE_PREFIX = "dumpstate.";
    private static final String PROGRESS_SUFFIX = ".progress";
    private static final String MAX_SUFFIX = ".max";
    private static final String NAME_SUFFIX = ".name";

    /** System property (and value) used to stop dumpstate. */
    // TODO: should call ActiveManager API instead
    private static final String CTL_STOP = "ctl.stop";
    private static final String BUGREPORT_SERVICE = "bugreportplus";

    /**
     * Directory on Shell's data storage where screenshots will be stored.
     * <p>
     * Must be a path supported by its FileProvider.
     */
    private static final String SCREENSHOT_DIR = "bugreports";

    /** Managed dumpstate processes (keyed by pid) */
    private final SparseArray<BugreportInfo> mProcesses = new SparseArray<>();

    private Context mContext;
    private ServiceHandler mMainHandler;
    private ScreenshotHandler mScreenshotHandler;

    private final BugreportInfoDialog mInfoDialog = new BugreportInfoDialog();

    private File mScreenshotsDir;

    /**
     * Flag indicating whether a screenshot is being taken.
     * <p>
     * This is the only state that is shared between the 2 handlers and hence must have synchronized
     * access.
     */
    private boolean mTakingScreenshot;

    @Override
    public void onCreate() {
        mContext = getApplicationContext();
        mMainHandler = new ServiceHandler("BugreportProgressServiceMainThread");
        mScreenshotHandler = new ScreenshotHandler("BugreportProgressServiceScreenshotThread");

        mScreenshotsDir = new File(new ContextWrapper(mContext).getFilesDir(), SCREENSHOT_DIR);
        if (!mScreenshotsDir.exists()) {
            Log.i(TAG, "Creating directory " + mScreenshotsDir + " to store temporary screenshots");
            if (!mScreenshotsDir.mkdir()) {
                Log.w(TAG, "Could not create directory " + mScreenshotsDir);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Handle it in a separate thread.
            final Message msg = mMainHandler.obtainMessage();
            msg.what = MSG_SERVICE_COMMAND;
            msg.obj = intent;
            mMainHandler.sendMessage(msg);
        }

        // If service is killed it cannot be recreated because it would not know which
        // dumpstate PIDs it would have to watch.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        mMainHandler.getLooper().quit();
        mScreenshotHandler.getLooper().quit();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final int size = mProcesses.size();
        if (size == 0) {
            writer.printf("No monitored processes");
            return;
        }
        writer.printf("Monitored dumpstate processes\n");
        writer.printf("-----------------------------\n");
        for (int i = 0; i < size; i++) {
            writer.printf("%s\n", mProcesses.valueAt(i));
        }
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
            if (msg.what == MSG_POLL) {
                poll();
                return;
            }

            if (msg.what == MSG_DELAYED_SCREENSHOT) {
                takeScreenshot(msg.arg1, msg.arg2);
                return;
            }

            if (msg.what == MSG_SCREENSHOT_RESPONSE) {
                handleScreenshotResponse(msg);
                return;
            }

            if (msg.what != MSG_SERVICE_COMMAND) {
                // Sanity check.
                Log.e(TAG, "Invalid message type: " + msg.what);
                return;
            }

            // At this point it's handling onStartCommand(), with the intent passed as an Extra.
            if (!(msg.obj instanceof Intent)) {
                // Sanity check.
                Log.e(TAG, "Internal error: invalid msg.obj: " + msg.obj);
                return;
            }
            final Parcelable parcel = ((Intent) msg.obj).getParcelableExtra(EXTRA_ORIGINAL_INTENT);
            final Intent intent;
            if (parcel instanceof Intent) {
                // The real intent was passed to BugreportReceiver, which delegated to the service.
                intent = (Intent) parcel;
            } else {
                intent = (Intent) msg.obj;
            }
            final String action = intent.getAction();
            final int pid = intent.getIntExtra(EXTRA_PID, 0);
            final int max = intent.getIntExtra(EXTRA_MAX, -1);
            final String name = intent.getStringExtra(EXTRA_NAME);

            if (DEBUG) Log.v(TAG, "action: " + action + ", name: " + name + ", pid: " + pid
                    + ", max: "+ max);
            switch (action) {
                case INTENT_BUGREPORT_STARTED:
                    if (!startProgress(name, pid, max)) {
                        stopSelfWhenDone();
                        return;
                    }
                    poll();
                    break;
                case INTENT_BUGREPORT_FINISHED:
                    if (pid == 0) {
                        // Shouldn't happen, unless BUGREPORT_FINISHED is received from a legacy,
                        // out-of-sync dumpstate process.
                        Log.w(TAG, "Missing " + EXTRA_PID + " on intent " + intent);
                    }
                    onBugreportFinished(pid, intent);
                    break;
                case INTENT_BUGREPORT_INFO_LAUNCH:
                    launchBugreportInfoDialog(pid);
                    break;
                case INTENT_BUGREPORT_SCREENSHOT:
                    takeScreenshot(pid, true);
                    break;
                case INTENT_BUGREPORT_SHARE:
                    shareBugreport(pid, (BugreportInfo) intent.getParcelableExtra(EXTRA_INFO));
                    break;
                case INTENT_BUGREPORT_CANCEL:
                    cancel(pid);
                    break;
                default:
                    Log.w(TAG, "Unsupported intent: " + action);
            }
            return;

        }

        private void poll() {
            if (pollProgress()) {
                // Keep polling...
                sendEmptyMessageDelayed(MSG_POLL, POLLING_FREQUENCY);
            } else {
                Log.i(TAG, "Stopped polling");
            }
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

    private BugreportInfo getInfo(int pid) {
        final BugreportInfo info = mProcesses.get(pid);
        if (info == null) {
            Log.w(TAG, "Not monitoring process with PID " + pid);
        }
        return info;
    }

    /**
     * Creates the {@link BugreportInfo} for a process and issue a system notification to
     * indicate its progress.
     *
     * @return whether it succeeded or not.
     */
    private boolean startProgress(String name, int pid, int max) {
        if (name == null) {
            Log.w(TAG, "Missing " + EXTRA_NAME + " on start intent");
        }
        if (pid == -1) {
            Log.e(TAG, "Missing " + EXTRA_PID + " on start intent");
            return false;
        }
        if (max <= 0) {
            Log.e(TAG, "Invalid value for extra " + EXTRA_MAX + ": " + max);
            return false;
        }

        final BugreportInfo info = new BugreportInfo(mContext, pid, name, max);
        if (mProcesses.indexOfKey(pid) >= 0) {
            Log.w(TAG, "PID " + pid + " already watched");
        } else {
            mProcesses.put(info.pid, info);
        }
        // Take initial screenshot.
        takeScreenshot(pid, false);
        updateProgress(info);
        return true;
    }

    /**
     * Updates the system notification for a given bugreport.
     */
    private void updateProgress(BugreportInfo info) {
        if (info.max <= 0 || info.progress < 0) {
            Log.e(TAG, "Invalid progress values for " + info);
            return;
        }

        final NumberFormat nf = NumberFormat.getPercentInstance();
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        final String percentText = nf.format((double) info.progress / info.max);
        final Action cancelAction = new Action.Builder(null, mContext.getString(
                com.android.internal.R.string.cancel), newCancelIntent(mContext, info)).build();
        final Intent infoIntent = new Intent(mContext, BugreportProgressService.class);
        infoIntent.setAction(INTENT_BUGREPORT_INFO_LAUNCH);
        infoIntent.putExtra(EXTRA_PID, info.pid);
        final Action infoAction = new Action.Builder(null,
                mContext.getString(R.string.bugreport_info_action),
                PendingIntent.getService(mContext, info.pid, infoIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT)).build();
        final Intent screenshotIntent = new Intent(mContext, BugreportProgressService.class);
        screenshotIntent.setAction(INTENT_BUGREPORT_SCREENSHOT);
        screenshotIntent.putExtra(EXTRA_PID, info.pid);
        PendingIntent screenshotPendingIntent = mTakingScreenshot ? null : PendingIntent
                .getService(mContext, info.pid, screenshotIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
        final Action screenshotAction = new Action.Builder(null,
                mContext.getString(R.string.bugreport_screenshot_action),
                screenshotPendingIntent).build();

        final String title = mContext.getString(R.string.bugreport_in_progress_title);

        final String name =
                info.name != null ? info.name : mContext.getString(R.string.bugreport_unnamed);

        final Notification notification = new Notification.Builder(mContext)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(name)
                .setContentInfo(percentText)
                .setProgress(info.max, info.progress, false)
                .setOngoing(true)
                .setLocalOnly(true)
                .setColor(mContext.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .addAction(infoAction)
                .addAction(screenshotAction)
                .addAction(cancelAction)
                .build();

        if (info.finished) {
            Log.w(TAG, "Not sending progress notification because bugreport has finished already ("
                    + info + ")");
            return;
        }
        NotificationManager.from(mContext).notify(TAG, info.pid, notification);
    }

    /**
     * Creates a {@link PendingIntent} for a notification action used to cancel a bugreport.
     */
    private static PendingIntent newCancelIntent(Context context, BugreportInfo info) {
        final Intent intent = new Intent(INTENT_BUGREPORT_CANCEL);
        intent.setClass(context, BugreportProgressService.class);
        intent.putExtra(EXTRA_PID, info.pid);
        return PendingIntent.getService(context, info.pid, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Finalizes the progress on a given bugreport and cancel its notification.
     */
    private void stopProgress(int pid) {
        if (mProcesses.indexOfKey(pid) < 0) {
            Log.w(TAG, "PID not watched: " + pid);
        } else {
            Log.d(TAG, "Removing PID " + pid);
            mProcesses.remove(pid);
        }
        stopSelfWhenDone();
        Log.v(TAG, "stopProgress(" + pid + "): cancel notification");
        NotificationManager.from(mContext).cancel(TAG, pid);
    }

    /**
     * Cancels a bugreport upon user's request.
     */
    private void cancel(int pid) {
        Log.v(TAG, "cancel: pid=" + pid);
        final BugreportInfo info = getInfo(pid);
        if (info != null && !info.finished) {
            Log.i(TAG, "Cancelling bugreport service (pid=" + pid + ") on user's request");
            setSystemProperty(CTL_STOP, BUGREPORT_SERVICE);
            deleteScreenshots(info);
        }
        stopProgress(pid);
    }

    /**
     * Poll {@link SystemProperties} to get the progress on each monitored process.
     *
     * @return whether it should keep polling.
     */
    private boolean pollProgress() {
        final int total = mProcesses.size();
        if (total == 0) {
            Log.d(TAG, "No process to poll progress.");
        }
        int activeProcesses = 0;
        for (int i = 0; i < total; i++) {
            final int pid = mProcesses.keyAt(i);
            final BugreportInfo info = mProcesses.valueAt(i);
            if (info.finished) {
                if (DEBUG) Log.v(TAG, "Skipping finished process " + pid);
                continue;
            }
            activeProcesses++;
            final String progressKey = DUMPSTATE_PREFIX + pid + PROGRESS_SUFFIX;
            final int progress = SystemProperties.getInt(progressKey, 0);
            if (progress == 0) {
                Log.v(TAG, "System property " + progressKey + " is not set yet");
            }
            final int max = SystemProperties.getInt(DUMPSTATE_PREFIX + pid + MAX_SUFFIX, 0);
            final boolean maxChanged = max > 0 && max != info.max;
            final boolean progressChanged = progress > 0 && progress != info.progress;

            if (progressChanged || maxChanged) {
                if (progressChanged) {
                    if (DEBUG) Log.v(TAG, "Updating progress for PID " + pid + " from "
                            + info.progress + " to " + progress);
                    info.progress = progress;
                }
                if (maxChanged) {
                    Log.i(TAG, "Updating max progress for PID " + pid + " from " + info.max
                            + " to " + max);
                    info.max = max;
                }
                info.lastUpdate = System.currentTimeMillis();
                updateProgress(info);
            } else {
                long inactiveTime = System.currentTimeMillis() - info.lastUpdate;
                if (inactiveTime >= INACTIVITY_TIMEOUT) {
                    Log.w(TAG, "No progress update for process " + pid + " since "
                            + info.getFormattedLastUpdate());
                    stopProgress(info.pid);
                }
            }
        }
        if (DEBUG) Log.v(TAG, "pollProgress() total=" + total + ", actives=" + activeProcesses);
        return activeProcesses > 0;
    }

    /**
     * Fetches a {@link BugreportInfo} for a given process and launches a dialog where the user can
     * change its values.
     */
    private void launchBugreportInfoDialog(int pid) {
        // Copy values so it doesn't lock mProcesses while UI is being updated
        final String name, title, description;
        final BugreportInfo info = getInfo(pid);
        if (info == null) {
            return;
        }
        name = info.name;
        title = info.title;
        description = info.description;

        collapseNotificationBar();
        mInfoDialog.initialize(mContext, pid, name, title, description);
    }

    /**
     * Starting point for taking a screenshot.
     * <p>
     * If {@code delayed} is set, it first display a toast message and waits
     * {@link #SCREENSHOT_DELAY_SECONDS} seconds before taking it, otherwise it takes the screenshot
     * right away.
     * <p>
     * Typical usage is delaying when taken from the notification action, and taking it right away
     * upon receiving a {@link #INTENT_BUGREPORT_STARTED}.
     */
    private void takeScreenshot(int pid, boolean delayed) {
        setTakingScreenshot(true);
        if (delayed) {
            collapseNotificationBar();
            final String msg = mContext.getResources()
                    .getQuantityString(com.android.internal.R.plurals.bugreport_countdown,
                            SCREENSHOT_DELAY_SECONDS, SCREENSHOT_DELAY_SECONDS);
            Log.i(TAG, msg);
            // Show a toast just once, otherwise it might be captured in the screenshot.
            Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();

            takeScreenshot(pid, SCREENSHOT_DELAY_SECONDS);
        } else {
            takeScreenshot(pid, 0);
        }
    }

    /**
     * Takes a screenshot after {@code delay} seconds.
     */
    private void takeScreenshot(int pid, int delay) {
        if (delay > 0) {
            Log.d(TAG, "Taking screenshot for " + pid + " in " + delay + " seconds");
            final Message msg = mMainHandler.obtainMessage();
            msg.what = MSG_DELAYED_SCREENSHOT;
            msg.arg1 = pid;
            msg.arg2 = delay - 1;
            mMainHandler.sendMessageDelayed(msg, DateUtils.SECOND_IN_MILLIS);
            return;
        }

        // It's time to take the screenshot: let the proper thread handle it
        final BugreportInfo info = getInfo(pid);
        if (info == null) {
            return;
        }
        final String screenshotPath =
                new File(mScreenshotsDir, info.getPathNextScreenshot()).getAbsolutePath();

        final Message requestMsg = new Message();
        requestMsg.what = MSG_SCREENSHOT_REQUEST;
        requestMsg.arg1 = pid;
        requestMsg.obj = screenshotPath;
        mScreenshotHandler.sendMessage(requestMsg);
    }

    /**
     * Sets the internal {@code mTakingScreenshot} state and updates all notifications so their
     * SCREENSHOT button is enabled or disabled accordingly.
     */
    private void setTakingScreenshot(boolean flag) {
        synchronized (BugreportProgressService.this) {
            mTakingScreenshot = flag;
            for (int i = 0; i < mProcesses.size(); i++) {
                final BugreportInfo info = mProcesses.valueAt(i);
                if (info.finished) {
                    Log.d(TAG, "Not updating progress because share notification was already sent");
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

        final Message resultMsg = new Message();
        resultMsg.what = MSG_SCREENSHOT_RESPONSE;
        resultMsg.arg1 = requestMsg.arg1;
        resultMsg.arg2 = taken ? 1 : 0;
        resultMsg.obj = screenshotFile;
        mMainHandler.sendMessage(resultMsg);
    }

    private void handleScreenshotResponse(Message resultMsg) {
        final boolean taken = resultMsg.arg2 != 0;
        final BugreportInfo info = getInfo(resultMsg.arg1);
        if (info == null) {
            return;
        }
        final File screenshotFile = new File((String) resultMsg.obj);

        final int msgId;
        if (taken) {
            info.addScreenshot(screenshotFile);
            if (info.finished) {
                Log.d(TAG, "Screenshot finished after bugreport; updating share notification");
                info.renameScreenshots(mScreenshotsDir);
                sendBugreportNotification(mContext, info);
            }
            msgId = R.string.bugreport_screenshot_taken;
        } else {
            // TODO: try again using Framework APIs instead of relying on screencap.
            msgId = R.string.bugreport_screenshot_failed;
        }
        final String msg = mContext.getString(msgId);
        Log.d(TAG, msg);
        Toast.makeText(mContext, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * Deletes all screenshots taken for a given bugreport.
     */
    private void deleteScreenshots(BugreportInfo info) {
        for (File file : info.screenshotFiles) {
            Log.i(TAG, "Deleting screenshot file " + file);
            file.delete();
        }
    }

    /**
     * Finishes the service when it's not monitoring any more processes.
     */
    private void stopSelfWhenDone() {
        if (mProcesses.size() > 0) {
            if (DEBUG) Log.v(TAG, "Staying alive, waiting for pids " + mProcesses);
            return;
        }
        Log.v(TAG, "No more pids to handle, shutting down");
        stopSelf();
    }

    /**
     * Handles the BUGREPORT_FINISHED intent sent by {@code dumpstate}.
     */
    private void onBugreportFinished(int pid, Intent intent) {
        mInfoDialog.onBugreportFinished(pid);
        BugreportInfo info = getInfo(pid);
        if (info == null) {
            // Happens when BUGREPORT_FINISHED was received without a BUGREPORT_STARTED first.
            Log.v(TAG, "Creating info for untracked pid " + pid);
            info = new BugreportInfo(mContext, pid);
            mProcesses.put(pid, info);
        }
        info.renameScreenshots(mScreenshotsDir);
        info.bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final File screenshot = getFileExtra(intent, EXTRA_SCREENSHOT);
        if (screenshot != null) {
            info.addScreenshot(screenshot);
        }
        info.finished = true;

        final Configuration conf = mContext.getResources().getConfiguration();
        if ((conf.uiMode & Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_WATCH) {
            triggerLocalNotification(mContext, info);
        }
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
            stopProgress(info.pid);
            return;
        }

        boolean isPlainText = info.bugreportFile.getName().toLowerCase().endsWith(".txt");
        if (!isPlainText) {
            // Already zipped, send it right away.
            sendBugreportNotification(context, info);
        } else {
            // Asynchronously zip the file first, then send it.
            sendZippedBugreportNotification(context, info);
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
        // Files are kept on private storage, so turn into Uris that we can
        // grant temporary permissions for.
        final Uri bugreportUri = getUri(context, info.bugreportFile);

        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final String mimeType = "application/vnd.android.bugreport";
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setType(mimeType);

        final String subject = info.title != null ? info.title : bugreportUri.getLastPathSegment();
        intent.putExtra(Intent.EXTRA_SUBJECT, subject);

        // EXTRA_TEXT should be an ArrayList, but some clients are expecting a single String.
        // So, to avoid an exception on Intent.migrateExtraStreamToClipData(), we need to manually
        // create the ClipData object with the attachments URIs.
        final StringBuilder messageBody = new StringBuilder("Build info: ")
            .append(SystemProperties.get("ro.build.description"))
            .append("\nSerial number: ")
            .append(SystemProperties.get("ro.serialno"));
        if (!TextUtils.isEmpty(info.description)) {
            messageBody.append("\nDescription: ").append(info.description);
        }
        intent.putExtra(Intent.EXTRA_TEXT, messageBody.toString());
        final ClipData clipData = new ClipData(null, new String[] { mimeType },
                new ClipData.Item(null, null, null, bugreportUri));
        final ArrayList<Uri> attachments = Lists.newArrayList(bugreportUri);
        for (File screenshot : info.screenshotFiles) {
            final Uri screenshotUri = getUri(context, screenshot);
            clipData.addItem(new ClipData.Item(null, null, null, screenshotUri));
            attachments.add(screenshotUri);
        }
        intent.setClipData(clipData);
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, attachments);

        final Account sendToAccount = findSendToAccount(context);
        if (sendToAccount != null) {
            intent.putExtra(Intent.EXTRA_EMAIL, new String[] { sendToAccount.name });
        }

        return intent;
    }

    /**
     * Shares the bugreport upon user's request by issuing a {@link Intent#ACTION_SEND_MULTIPLE}
     * intent, but issuing a warning dialog the first time.
     */
    private void shareBugreport(int pid, BugreportInfo sharedInfo) {
        BugreportInfo info = getInfo(pid);
        if (info == null) {
            // Service was terminated but notification persisted
            info = sharedInfo;
            Log.d(TAG, "shareBugreport(): no info for PID " + pid + " on managed processes ("
                    + mProcesses + "), using info from intent instead (" + info + ")");
        }

        addDetailsToZipFile(info);

        final Intent sendIntent = buildSendIntent(mContext, info);
        final Intent notifIntent;

        // Send through warning dialog by default
        if (getWarningState(mContext, STATE_SHOW) == STATE_SHOW) {
            notifIntent = buildWarningIntent(mContext, sendIntent);
        } else {
            notifIntent = sendIntent;
        }
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // Send the share intent...
        mContext.startActivity(notifIntent);

        // ... and stop watching this process.
        stopProgress(pid);
    }

    /**
     * Sends a notitication indicating the bugreport has finished so use can share it.
     */
    private static void sendBugreportNotification(Context context, BugreportInfo info) {
        final Intent shareIntent = new Intent(INTENT_BUGREPORT_SHARE);
        shareIntent.setClass(context, BugreportProgressService.class);
        shareIntent.setAction(INTENT_BUGREPORT_SHARE);
        shareIntent.putExtra(EXTRA_PID, info.pid);
        shareIntent.putExtra(EXTRA_INFO, info);

        final String title = context.getString(R.string.bugreport_finished_title);
        final Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(context.getString(R.string.bugreport_finished_text))
                .setContentIntent(PendingIntent.getService(context, info.pid, shareIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(newCancelIntent(context, info))
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        if (!TextUtils.isEmpty(info.name)) {
            builder.setContentInfo(info.name);
        }

        NotificationManager.from(context).notify(TAG, info.pid, builder.build());
    }

    /**
     * Sends a zipped bugreport notification.
     */
    private static void sendZippedBugreportNotification(final Context context,
            final BugreportInfo info) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                zipBugreport(info);
                sendBugreportNotification(context, info);
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
        if (info.bugreportFile == null) {
            // One possible reason is a bug in the Parcelization code.
            Log.e(TAG, "INTERNAL ERROR: no bugreportFile on " + info);
            return;
        }
        // It's not possible to add a new entry into an existing file, so we need to create a new
        // zip, copy all entries, then rename it.
        final File dir = info.bugreportFile.getParentFile();
        final File tmpZip = new File(dir, "tmp-" + info.bugreportFile.getName());
        Log.d(TAG, "Writing temporary zip file (" + tmpZip + ")");
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
            addEntry(zos, "title.txt", info.title);
            addEntry(zos, "description.txt", info.description);
        } catch (IOException e) {
            Log.e(TAG, "exception zipping file " + tmpZip, e);
            return;
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
     * Find the best matching {@link Account} based on build properties.
     */
    private static Account findSendToAccount(Context context) {
        final AccountManager am = (AccountManager) context.getSystemService(
                Context.ACCOUNT_SERVICE);

        String preferredDomain = SystemProperties.get("sendbug.preferred.domain");
        if (!preferredDomain.startsWith("@")) {
            preferredDomain = "@" + preferredDomain;
        }

        final Account[] accounts = am.getAccounts();
        Account foundAccount = null;
        for (Account account : accounts) {
            if (Patterns.EMAIL_ADDRESS.matcher(account.name).matches()) {
                if (!preferredDomain.isEmpty()) {
                    // if we have a preferred domain and it matches, return; otherwise keep
                    // looking
                    if (account.name.endsWith(preferredDomain)) {
                        return account;
                    } else {
                        foundAccount = account;
                    }
                    // if we don't have a preferred domain, just return since it looks like
                    // an email address
                } else {
                    return account;
                }
            }
        }
        return foundAccount;
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

    private static boolean setSystemProperty(String key, String value) {
        try {
            if (DEBUG) Log.v(TAG, "Setting system property" + key + " to " + value);
            SystemProperties.set(key, value);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Could not set property " + key + " to " + value, e);
            return false;
        }
        return true;
    }

    /**
     * Updates the system property used by {@code dumpstate} to rename the final bugreport files.
     */
    private boolean setBugreportNameProperty(int pid, String name) {
        Log.d(TAG, "Updating bugreport name to " + name);
        final String key = DUMPSTATE_PREFIX + pid + NAME_SUFFIX;
        return setSystemProperty(key, name);
    }

    /**
     * Updates the user-provided details of a bugreport.
     */
    private void updateBugreportInfo(int pid, String name, String title, String description) {
        final BugreportInfo info = getInfo(pid);
        if (info == null) {
            return;
        }
        info.title = title;
        info.description = description;
        if (name != null && !info.name.equals(name)) {
            info.name = name;
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
    private static boolean takeScreenshot(Context context, String screenshotFile) {
        ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE))
                .vibrate(150);
        final ProcessBuilder screencap = new ProcessBuilder()
                .command("/system/bin/screencap", "-p", screenshotFile);
        Log.d(TAG, "Taking screenshot using " + screencap.command());
        try {
            final int exitValue = screencap.start().waitFor();
            if (exitValue == 0) {
                return true;
            }
            Log.e(TAG, "screencap (" + screencap.command() + ") failed: " + exitValue);
        } catch (IOException e) {
            Log.e(TAG, "screencap (" + screencap.command() + ") failed", e);
        } catch (InterruptedException e) {
            Log.w(TAG, "Thread interrupted while screencap still running");
            Thread.currentThread().interrupt();
        }
        return false;
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
     * Helper class encapsulating the UI elements and logic used to display a dialog where user
     * can change the details of a bugreport.
     */
    private final class BugreportInfoDialog {
        private EditText mInfoName;
        private EditText mInfoTitle;
        private EditText mInfoDescription;
        private AlertDialog mDialog;
        private Button mOkButton;
        private int mPid;

        /**
         * Last "committed" value of the bugreport name.
         * <p>
         * Once initially set, it's only updated when user clicks the OK button.
         */
        private String mSavedName;

        /**
         * Last value of the bugreport name as entered by the user.
         * <p>
         * Every time it's changed the equivalent system property is changed as well, but if the
         * user clicks CANCEL, the old value (stored on {@code mSavedName} is restored.
         * <p>
         * This logic handles the corner-case scenario where {@code dumpstate} finishes after the
         * user changed the name but didn't clicked OK yet (for example, because the user is typing
         * the description). The only drawback is that if the user changes the name while
         * {@code dumpstate} is running but clicks CANCEL after it finishes, then the final name
         * will be the one that has been canceled. But when {@code dumpstate} finishes the {code
         * name} UI is disabled and the old name restored anyways, so the user will be "alerted" of
         * such drawback.
         */
        private String mTempName;

        /**
         * Sets its internal state and displays the dialog.
         */
        private void initialize(Context context, int pid, String name, String title,
                String description) {
            // First initializes singleton.
            if (mDialog == null) {
                @SuppressLint("InflateParams")
                // It's ok pass null ViewRoot on AlertDialogs.
                final View view = View.inflate(context, R.layout.dialog_bugreport_info, null);

                mInfoName = (EditText) view.findViewById(R.id.name);
                mInfoTitle = (EditText) view.findViewById(R.id.title);
                mInfoDescription = (EditText) view.findViewById(R.id.description);

                mInfoName.setOnFocusChangeListener(new OnFocusChangeListener() {

                    @Override
                    public void onFocusChange(View v, boolean hasFocus) {
                        if (hasFocus) {
                            return;
                        }
                        sanitizeName();
                    }
                });

                mDialog = new AlertDialog.Builder(context)
                        .setView(view)
                        .setTitle(context.getString(R.string.bugreport_info_dialog_title))
                        .setCancelable(false)
                        .setPositiveButton(context.getString(com.android.internal.R.string.ok),
                                null)
                        .setNegativeButton(context.getString(com.android.internal.R.string.cancel),
                                new DialogInterface.OnClickListener()
                                {
                                    @Override
                                    public void onClick(DialogInterface dialog, int id)
                                    {
                                        if (!mTempName.equals(mSavedName)) {
                                            // Must restore dumpstate's name since it was changed
                                            // before user clicked OK.
                                            setBugreportNameProperty(mPid, mSavedName);
                                        }
                                    }
                                })
                        .create();

                mDialog.getWindow().setAttributes(
                        new WindowManager.LayoutParams(
                                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG));

            }

            // Then set fields.
            mSavedName = mTempName = name;
            mPid = pid;
            if (!TextUtils.isEmpty(name)) {
                mInfoName.setText(name);
            }
            if (!TextUtils.isEmpty(title)) {
                mInfoTitle.setText(title);
            }
            if (!TextUtils.isEmpty(description)) {
                mInfoDescription.setText(description);
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
                        sanitizeName();
                        final String name = mInfoName.getText().toString();
                        final String title = mInfoTitle.getText().toString();
                        final String description = mInfoDescription.getText().toString();

                        updateBugreportInfo(mPid, name, title, description);
                        mDialog.dismiss();
                    }
                });
            }
        }

        /**
         * Sanitizes the user-provided value for the {@code name} field, automatically replacing
         * invalid characters if necessary.
         */
        private void sanitizeName() {
            String name = mInfoName.getText().toString();
            if (name.equals(mTempName)) {
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
            mTempName = name;

            // Must update system property for the cases where dumpstate finishes
            // while the user is still entering other fields (like title or
            // description)
            setBugreportNameProperty(mPid, name);
        }

       /**
         * Notifies the dialog that the bugreport has finished so it disables the {@code name}
         * field.
         * <p>Once the bugreport is finished dumpstate has already generated the final files, so
         * changing the name would have no effect.
         */
        private void onBugreportFinished(int pid) {
            if (mInfoName != null) {
                mInfoName.setEnabled(false);
                mInfoName.setText(mSavedName);
            }
        }

    }

    /**
     * Information about a bugreport process while its in progress.
     */
    private static final class BugreportInfo implements Parcelable {
        private final Context context;

        /**
         * {@code pid} of the {@code dumpstate} process generating the bugreport.
         */
        final int pid;

        /**
         * Name of the bugreport, will be used to rename the final files.
         * <p>
         * Initial value is the bugreport filename reported by {@code dumpstate}, but user can
         * change it later to a more meaningful name.
         */
        String name;

        /**
         * User-provided, one-line summary of the bug; when set, will be used as the subject
         * of the {@link Intent#ACTION_SEND_MULTIPLE} intent.
         */
        String title;

        /**
         * User-provided, detailed description of the bugreport; when set, will be added to the body
         * of the {@link Intent#ACTION_SEND_MULTIPLE} intent.
         */
        String description;

        /**
         * Maximum progress of the bugreport generation.
         */
        int max;

        /**
         * Current progress of the bugreport generation.
         */
        int progress;

        /**
         * Time of the last progress update.
         */
        long lastUpdate = System.currentTimeMillis();

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
        boolean finished;

        /**
         * Internal counter used to name screenshot files.
         */
        int screenshotCounter;

        /**
         * Constructor for tracked bugreports - typically called upon receiving BUGREPORT_STARTED.
         */
        BugreportInfo(Context context, int pid, String name, int max) {
            this.context = context;
            this.pid = pid;
            this.name = name;
            this.max = max;
        }

        /**
         * Constructor for untracked bugreports - typically called upon receiving BUGREPORT_FINISHED
         * without a previous call to BUGREPORT_STARTED.
         */
        BugreportInfo(Context context, int pid) {
            this(context, pid, null, 0);
            this.finished = true;
        }

        /**
         * Gets the name for next screenshot file.
         */
        String getPathNextScreenshot() {
            screenshotCounter ++;
            return "screenshot-" + pid + "-" + screenshotCounter + ".png";
        }

        /**
         * Saves the location of a taken screenshot so it can be sent out at the end.
         */
        void addScreenshot(File screenshot) {
            screenshotFiles.add(screenshot);
        }

        /**
         * Rename all screenshots files so that they contain the user-generated name instead of pid.
         */
        void renameScreenshots(File screenshotDir) {
            if (TextUtils.isEmpty(name)) {
                return;
            }
            final List<File> renamedFiles = new ArrayList<>(screenshotFiles.size());
            for (File oldFile : screenshotFiles) {
                final String oldName = oldFile.getName();
                final String newName = oldName.replace(Integer.toString(pid), name);
                final File newFile;
                if (!newName.equals(oldName)) {
                    final File renamedFile = new File(screenshotDir, newName);
                    newFile = oldFile.renameTo(renamedFile) ? renamedFile : oldFile;
                } else {
                    Log.w(TAG, "Name didn't change: " + oldName); // Shouldn't happen.
                    newFile = oldFile;
                }
                renamedFiles.add(newFile);
            }
            screenshotFiles = renamedFiles;
        }

        String getFormattedLastUpdate() {
            if (context == null) {
                // Restored from Parcel
                return formattedLastUpdate == null ?
                        Long.toString(lastUpdate) : formattedLastUpdate;
            }
            return DateUtils.formatDateTime(context, lastUpdate,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        }

        @Override
        public String toString() {
            final float percent = ((float) progress * 100 / max);
            return "pid: " + pid + ", name: " + name + ", finished: " + finished
                    + "\n\ttitle: " + title + "\n\tdescription: " + description
                    + "\n\tfile: " + bugreportFile + "\n\tscreenshots: " + screenshotFiles
                    + "\n\tprogress: " + progress + "/" + max + "(" + percent + ")"
                    + "\n\tlast_update: " + getFormattedLastUpdate();
        }

        // Parcelable contract
        protected BugreportInfo(Parcel in) {
            context = null;
            pid = in.readInt();
            name = in.readString();
            title = in.readString();
            description = in.readString();
            max = in.readInt();
            progress = in.readInt();
            lastUpdate = in.readLong();
            formattedLastUpdate = in.readString();
            bugreportFile = readFile(in);

            int screenshotSize = in.readInt();
            for (int i = 1; i <= screenshotSize; i++) {
                  screenshotFiles.add(readFile(in));
            }

            finished = in.readInt() == 1;
            screenshotCounter = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(pid);
            dest.writeString(name);
            dest.writeString(title);
            dest.writeString(description);
            dest.writeInt(max);
            dest.writeInt(progress);
            dest.writeLong(lastUpdate);
            dest.writeString(getFormattedLastUpdate());
            writeFile(dest, bugreportFile);

            dest.writeInt(screenshotFiles.size());
            for (File screenshotFile : screenshotFiles) {
                writeFile(dest, screenshotFile);
            }

            dest.writeInt(finished ? 1 : 0);
            dest.writeInt(screenshotCounter);
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

        public static final Parcelable.Creator<BugreportInfo> CREATOR =
                new Parcelable.Creator<BugreportInfo>() {
            public BugreportInfo createFromParcel(Parcel source) {
                return new BugreportInfo(source);
            }

            public BugreportInfo[] newArray(int size) {
                return new BugreportInfo[size];
            }
        };

    }
}
