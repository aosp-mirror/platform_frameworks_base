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

import static com.android.shell.BugreportPrefs.STATE_SHOW;
import static com.android.shell.BugreportPrefs.getWarningState;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import libcore.io.Streams;

import com.google.android.collect.Lists;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemProperties;
import android.support.v4.content.FileProvider;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.Patterns;
import android.util.SparseArray;
import android.widget.Toast;

/**
 * Service used to keep progress of bug reports processes ({@code dumpstate}).
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
 * <li>Updates the system notification so user can share the bug report.
 * <li>Stops monitoring that {@code dumpstate} process.
 * <li>Stops itself if it doesn't have any process left to monitor.
 * </ol>
 * </ol>
 */
public class BugreportProgressService extends Service {
    private static final String TAG = "Shell";
    private static final boolean DEBUG = false;

    private static final String AUTHORITY = "com.android.shell";

    static final String INTENT_BUGREPORT_STARTED = "android.intent.action.BUGREPORT_STARTED";
    static final String INTENT_BUGREPORT_FINISHED = "android.intent.action.BUGREPORT_FINISHED";
    static final String INTENT_BUGREPORT_CANCEL = "android.intent.action.BUGREPORT_CANCEL";

    static final String EXTRA_BUGREPORT = "android.intent.extra.BUGREPORT";
    static final String EXTRA_SCREENSHOT = "android.intent.extra.SCREENSHOT";
    static final String EXTRA_PID = "android.intent.extra.PID";
    static final String EXTRA_MAX = "android.intent.extra.MAX";
    static final String EXTRA_NAME = "android.intent.extra.NAME";
    static final String EXTRA_ORIGINAL_INTENT = "android.intent.extra.ORIGINAL_INTENT";

    private static final int MSG_SERVICE_COMMAND = 1;
    private static final int MSG_POLL = 2;

    /** Polling frequency, in milliseconds. */
    private static final long POLLING_FREQUENCY = 500;

    /** How long (in ms) a dumpstate process will be monitored if it didn't show progress. */
    private static final long INACTIVITY_TIMEOUT = 3 * DateUtils.MINUTE_IN_MILLIS;

    /** System properties used for monitoring progress. */
    private static final String DUMPSTATE_PREFIX = "dumpstate.";
    private static final String PROGRESS_SUFFIX = ".progress";
    private static final String MAX_SUFFIX = ".max";

    /** System property (and value) used for stop dumpstate. */
    private static final String CTL_STOP = "ctl.stop";
    private static final String BUGREPORT_SERVICE = "bugreportplus";

    /** Managed dumpstate processes (keyed by pid) */
    private final SparseArray<BugreportInfo> mProcesses = new SparseArray<>();

    private Looper mServiceLooper;
    private ServiceHandler mServiceHandler;

    @Override
    public void onCreate() {
        HandlerThread thread = new HandlerThread("BugreportProgressServiceThread",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            // Handle it in a separate thread.
            Message msg = mServiceHandler.obtainMessage();
            msg.what = MSG_SERVICE_COMMAND;
            msg.obj = intent;
            mServiceHandler.sendMessage(msg);
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
        mServiceLooper.quit();
        super.onDestroy();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        writer.printf("Monitored dumpstate processes: \n");
        synchronized (mProcesses) {
            for (int i = 0; i < mProcesses.size(); i++) {
              writer.printf("\t%s\n", mProcesses.valueAt(i));
            }
        }
    }

    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
            poll();
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_POLL) {
                poll();
                return;
            }

            if (msg.what != MSG_SERVICE_COMMAND) {
                // Sanity check.
                Log.e(TAG, "Invalid message type: " + msg.what);
                return;
            }

            // At this point it's handling onStartCommand(), whose intent contains the extras
            // originally received by BugreportReceiver.
            if (!(msg.obj instanceof Intent)) {
                // Sanity check.
                Log.e(TAG, "Internal error: invalid msg.obj: " + msg.obj);
                return;
            }
            final Parcelable parcel = ((Intent) msg.obj).getParcelableExtra(EXTRA_ORIGINAL_INTENT);
            if (!(parcel instanceof Intent)) {
                // Sanity check.
                Log.e(TAG, "Internal error: msg.obj is missing extra " + EXTRA_ORIGINAL_INTENT);
                return;
            }

            final Intent intent = (Intent) parcel;
            final String action = intent.getAction();
            int pid = intent.getIntExtra(EXTRA_PID, 0);
            int max = intent.getIntExtra(EXTRA_MAX, -1);
            String name = intent.getStringExtra(EXTRA_NAME);

            if (DEBUG) Log.v(TAG, "action: " + action + ", name: " + name + ", pid: " + pid
                    + ", max: "+ max);
            switch (action) {
                case INTENT_BUGREPORT_STARTED:
                    if (!startProgress(name, pid, max)) {
                        stopSelfWhenDone();
                        return;
                    }
                    break;
                case INTENT_BUGREPORT_FINISHED:
                    if (pid == -1) {
                        // Shouldn't happen, unless BUGREPORT_FINISHED is received from a legacy,
                        // out-of-sync dumpstate process.
                        Log.w(TAG, "Missing " + EXTRA_PID + " on intent " + intent);
                    }
                    stopProgress(pid, intent);
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
            }
        }
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

        final BugreportInfo info = new BugreportInfo(getApplicationContext(), pid, name, max);
        synchronized (mProcesses) {
            if (mProcesses.indexOfKey(pid) >= 0) {
                Log.w(TAG, "PID " + pid + " already watched");
            } else {
                mProcesses.put(info.pid, info);
            }
        }
        updateProgress(info);
        return true;
    }

    /**
     * Updates the system notification for a given bug report.
     */
    private void updateProgress(BugreportInfo info) {
        if (info.max <= 0 || info.progress < 0) {
            Log.e(TAG, "Invalid progress values for " + info);
            return;
        }

        final Context context = getApplicationContext();
        final NumberFormat nf = NumberFormat.getPercentInstance();
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        final String percentText = nf.format((double) info.progress / info.max);

        final Intent cancelIntent = new Intent(context, BugreportReceiver.class);
        cancelIntent.setAction(INTENT_BUGREPORT_CANCEL);
        cancelIntent.putExtra(EXTRA_PID, info.pid);
        final Action cancelAction = new Action.Builder(null,
                context.getString(com.android.internal.R.string.cancel),
                PendingIntent.getBroadcast(context, info.pid, cancelIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT)).build();

        final String title = context.getString(R.string.bugreport_in_progress_title);
        final String name =
                info.name != null ? info.name : context.getString(R.string.bugreport_unnamed);

        final Notification notification = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(name)
                .setContentInfo(percentText)
                .setProgress(info.max, info.progress, false)
                .setOngoing(true)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color))
                .addAction(cancelAction)
                .build();

        NotificationManager.from(context).notify(TAG, info.pid, notification);
    }

    /**
     * Finalizes the progress on a given process and sends the finished intent.
     */
    private void stopProgress(int pid, Intent intent) {
        synchronized (mProcesses) {
            if (mProcesses.indexOfKey(pid) < 0) {
                Log.w(TAG, "PID not watched: " + pid);
            } else {
                mProcesses.remove(pid);
            }
            stopSelfWhenDone();
        }
        if (DEBUG) Log.v(TAG, "stopProgress(" + pid + "): cancel notification");
        NotificationManager.from(getApplicationContext()).cancel(TAG, pid);
        if (intent != null) {
            // Bug report finished fine: send a new, different notification.
            if (DEBUG) Log.v(TAG, "stopProgress(" + pid + "): finish bug report");
            onBugreportFinished(pid, intent);
        }
    }

    /**
     * Cancels a bugreport upon user's request.
     */
    private void cancel(int pid) {
        Log.i(TAG, "Cancelling PID " + pid + " on user's request");
        SystemProperties.set(CTL_STOP, BUGREPORT_SERVICE);
        stopProgress(pid, null);
    }

    /**
     * Poll {@link SystemProperties} to get the progress on each monitored process.
     *
     * @return whether it should keep polling.
     */
    private boolean pollProgress() {
        synchronized (mProcesses) {
            if (mProcesses.size() == 0) {
                Log.d(TAG, "No process to poll progress.");
            }
            for (int i = 0; i < mProcesses.size(); i++) {
                final int pid = mProcesses.keyAt(i);
                final String progressKey = DUMPSTATE_PREFIX + pid + PROGRESS_SUFFIX;
                final int progress = SystemProperties.getInt(progressKey, 0);
                if (progress == 0) {
                    Log.v(TAG, "System property " + progressKey + " is not set yet");
                    continue;
                }
                final int max = SystemProperties.getInt(DUMPSTATE_PREFIX + pid + MAX_SUFFIX, 0);
                final BugreportInfo info = mProcesses.valueAt(i);
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
                        stopProgress(info.pid, null);
                    }
                }
            }
            return true;
        }
    }

    /**
     * Finishes the service when it's not monitoring any more processes.
     */
    private void stopSelfWhenDone() {
        synchronized (mProcesses) {
            if (mProcesses.size() > 0) {
                if (DEBUG) Log.v(TAG, "Staying alive, waiting for pids " + mProcesses);
                return;
            }
            Log.v(TAG, "No more pids to handle, shutting down");
            stopSelf();
        }
    }

    private void onBugreportFinished(int pid, Intent intent) {
        final Context context = getApplicationContext();
        final Configuration conf = context.getResources().getConfiguration();
        final File bugreportFile = getFileExtra(intent, EXTRA_BUGREPORT);
        final File screenshotFile = getFileExtra(intent, EXTRA_SCREENSHOT);

        if ((conf.uiMode & Configuration.UI_MODE_TYPE_MASK) != Configuration.UI_MODE_TYPE_WATCH) {
            triggerLocalNotification(context, pid, bugreportFile, screenshotFile);
        }
    }

    /**
     * Responsible for triggering a notification that allows the user to start a "share" intent with
     * the bug report. On watches we have other methods to allow the user to start this intent
     * (usually by triggering it on another connected device); we don't need to display the
     * notification in this case.
     */
    private static void triggerLocalNotification(final Context context, final int pid,
            final File bugreportFile, final File screenshotFile) {
        if (!bugreportFile.exists() || !bugreportFile.canRead()) {
            Log.e(TAG, "Could not read bugreport file " + bugreportFile);
            Toast.makeText(context, context.getString(R.string.bugreport_unreadable_text),
                    Toast.LENGTH_LONG).show();
            return;
        }

        boolean isPlainText = bugreportFile.getName().toLowerCase().endsWith(".txt");
        if (!isPlainText) {
            // Already zipped, send it right away.
            sendBugreportNotification(context, pid, bugreportFile, screenshotFile);
        } else {
            // Asynchronously zip the file first, then send it.
            sendZippedBugreportNotification(context, pid, bugreportFile, screenshotFile);
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
    private static Intent buildSendIntent(Context context, Uri bugreportUri, Uri screenshotUri) {
        final Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        final String mimeType = "application/vnd.android.bugreport";
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setType(mimeType);

        intent.putExtra(Intent.EXTRA_SUBJECT, bugreportUri.getLastPathSegment());

        // EXTRA_TEXT should be an ArrayList, but some clients are expecting a single String.
        // So, to avoid an exception on Intent.migrateExtraStreamToClipData(), we need to manually
        // create the ClipData object with the attachments URIs.
        String messageBody = String.format("Build info: %s\nSerial number:%s",
                SystemProperties.get("ro.build.description"), SystemProperties.get("ro.serialno"));
        intent.putExtra(Intent.EXTRA_TEXT, messageBody);
        final ClipData clipData = new ClipData(null, new String[] { mimeType },
                new ClipData.Item(null, null, null, bugreportUri));
        final ArrayList<Uri> attachments = Lists.newArrayList(bugreportUri);
        if (screenshotUri != null) {
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
     * Sends a bugreport notitication.
     */
    private static void sendBugreportNotification(Context context, int pid, File bugreportFile,
            File screenshotFile) {
        // Files are kept on private storage, so turn into Uris that we can
        // grant temporary permissions for.
        final Uri bugreportUri = getUri(context, bugreportFile);
        final Uri screenshotUri = getUri(context, screenshotFile);

        Intent sendIntent = buildSendIntent(context, bugreportUri, screenshotUri);
        Intent notifIntent;

        // Send through warning dialog by default
        if (getWarningState(context, STATE_SHOW) == STATE_SHOW) {
            notifIntent = buildWarningIntent(context, sendIntent);
        } else {
            notifIntent = sendIntent;
        }
        notifIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        final String title = context.getString(R.string.bugreport_finished_title);
        final Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(com.android.internal.R.drawable.stat_sys_adb)
                .setContentTitle(title)
                .setTicker(title)
                .setContentText(context.getString(R.string.bugreport_finished_text))
                .setContentIntent(PendingIntent.getActivity(
                        context, 0, notifIntent, PendingIntent.FLAG_CANCEL_CURRENT))
                .setAutoCancel(true)
                .setLocalOnly(true)
                .setColor(context.getColor(
                        com.android.internal.R.color.system_notification_accent_color));

        NotificationManager.from(context).notify(TAG, pid, builder.build());
    }

    /**
     * Sends a zipped bugreport notification.
     */
    private static void sendZippedBugreportNotification(final Context context,
            final int pid, final File bugreportFile, final File screenshotFile) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                File zippedFile = zipBugreport(bugreportFile);
                sendBugreportNotification(context, pid, zippedFile, screenshotFile);
                return null;
            }
        }.execute();
    }

    /**
     * Zips a bugreport file, returning the path to the new file (or to the
     * original in case of failure).
     */
    private static File zipBugreport(File bugreportFile) {
        String bugreportPath = bugreportFile.getAbsolutePath();
        String zippedPath = bugreportPath.replace(".txt", ".zip");
        Log.v(TAG, "zipping " + bugreportPath + " as " + zippedPath);
        File bugreportZippedFile = new File(zippedPath);
        try (InputStream is = new FileInputStream(bugreportFile);
                ZipOutputStream zos = new ZipOutputStream(
                        new BufferedOutputStream(new FileOutputStream(bugreportZippedFile)))) {
            ZipEntry entry = new ZipEntry(bugreportFile.getName());
            entry.setTime(bugreportFile.lastModified());
            zos.putNextEntry(entry);
            int totalBytes = Streams.copy(is, zos);
            Log.v(TAG, "size of original bugreport: " + totalBytes + " bytes");
            zos.closeEntry();
            // Delete old file;
            boolean deleted = bugreportFile.delete();
            if (deleted) {
                Log.v(TAG, "deleted original bugreport (" + bugreportPath + ")");
            } else {
                Log.e(TAG, "could not delete original bugreport (" + bugreportPath + ")");
            }
            return bugreportZippedFile;
        } catch (IOException e) {
            Log.e(TAG, "exception zipping file " + zippedPath, e);
            return bugreportFile; // Return original.
        }
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

    private static Uri getUri(Context context, File file) {
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
     * Information about a bug report process while its in progress.
     */
    private static final class BugreportInfo {
        private final Context context;

        /**
         * {@code pid} of the {@code dumpstate} process generating the bug report.
         */
        final int pid;

        /**
         * Name of the bug report, will be used to rename the final files.
         * <p>
         * Initial value is the bug report filename reported by {@code dumpstate}, but user can
         * change it later to a more meaningful name.
         */
        String name;

        /**
         * Maximum progress of the bug report generation.
         */
        int max;

        /**
         * Current progress of the bug report generation.
         */
        int progress;

        /**
         * Time of the last progress update.
         */
        long lastUpdate = System.currentTimeMillis();

        BugreportInfo(Context context, int pid, String name, int max) {
            this.context = context;
            this.pid = pid;
            this.name = name;
            this.max = max;
        }

        String getFormattedLastUpdate() {
            return DateUtils.formatDateTime(context, lastUpdate,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME);
        }

        @Override
        public String toString() {
            final float percent = ((float) progress * 100 / max);
            return "Progress for " + name + " (pid=" + pid + "): " + progress + "/" + max
                    + " (" + percent + "%) Last update: " + getFormattedLastUpdate();
        }
    }
}
