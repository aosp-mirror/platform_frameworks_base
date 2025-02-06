/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.printspooler.model;

import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

import static com.android.internal.print.DumpUtils.writePrintJobInfo;
import static com.android.internal.util.dump.DumpUtils.writeComponentName;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.RemoteException;
import android.print.IPrintSpooler;
import android.print.IPrintSpoolerCallbacks;
import android.print.IPrintSpoolerClient;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintAttributes.Margins;
import android.print.PrintAttributes.MediaSize;
import android.print.PrintAttributes.Resolution;
import android.print.PrintDocumentInfo;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.service.print.PrintSpoolerInternalStateProto;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Slog;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.printspooler.R;
import com.android.printspooler.flags.Flags;
import com.android.printspooler.util.ApprovedPrintServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Service for exposing some of the {@link PrintSpooler} functionality to
 * another process.
 */
public final class PrintSpoolerService extends Service {

    private static final String LOG_TAG = "PrintSpoolerService";

    private static final boolean DEBUG_PRINT_JOB_LIFECYCLE = false;

    private static final boolean DEBUG_PERSISTENCE = false;

    private static final boolean PERSISTENCE_MANAGER_ENABLED = true;

    private static final String PRINT_JOB_STATE_HISTO = "print_job_state";

    private static final long CHECK_ALL_PRINTJOBS_HANDLED_DELAY = 5000;

    private static final String PRINT_JOB_FILE_PREFIX = "print_job_";

    private static final String PRINT_FILE_EXTENSION = "pdf";

    private static final Object sLock = new Object();

    private final Object mLock = new Object();

    private final List<PrintJobInfo> mPrintJobs = new ArrayList<>();

    private static PrintSpoolerService sInstance;

    private IPrintSpoolerClient mClient;

    private PersistenceManager mPersistanceManager;

    private NotificationController mNotificationController;

    /** Cache for custom printer icons loaded from the print service */
    private CustomPrinterIconCache mCustomIconCache;

    /** If the system should be kept awake to process print jobs */
    private PowerManager.WakeLock mKeepAwake;

    public static PrintSpoolerService peekInstance() {
        synchronized (sLock) {
            return sInstance;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mPersistanceManager = new PersistenceManager();
        mNotificationController = new NotificationController(PrintSpoolerService.this);
        mCustomIconCache = new CustomPrinterIconCache(getCacheDir());
        mKeepAwake = getSystemService(PowerManager.class).newWakeLock(PARTIAL_WAKE_LOCK,
                "Active Print Job");

        synchronized (mLock) {
            mPersistanceManager.readStateLocked();
            handleReadPrintJobsLocked();
        }

        synchronized (sLock) {
            sInstance = this;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new PrintSpooler();
    }

    private void dumpLocked(@NonNull DualDumpOutputStream dumpStream) {
        int numPrintJobs = mPrintJobs.size();
        for (int i = 0; i < numPrintJobs; i++) {
            writePrintJobInfo(this, dumpStream, "print_jobs",
                    PrintSpoolerInternalStateProto.PRINT_JOBS, mPrintJobs.get(i));
        }

        File[] files = getFilesDir().listFiles();
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (file.isFile() && file.getName().startsWith(PRINT_JOB_FILE_PREFIX)) {
                    dumpStream.write("print_job_files",
                            PrintSpoolerInternalStateProto.PRINT_JOB_FILES, file.getName());
                }
            }
        }

        Set<String> approvedPrintServices = (new ApprovedPrintServices(this)).getApprovedServices();
        if (approvedPrintServices != null) {
            for (String approvedService : approvedPrintServices) {
                ComponentName componentName = ComponentName.unflattenFromString(approvedService);
                if (componentName != null) {
                    writeComponentName(dumpStream, "approved_services",
                            PrintSpoolerInternalStateProto.APPROVED_SERVICES, componentName);
                }
            }
        }

        dumpStream.flush();
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        fd = Preconditions.checkNotNull(fd);

        int opti = 0;
        boolean dumpAsProto = false;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("--proto".equals(opt)) {
                dumpAsProto = true;
            }
        }

        final long identity = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                if (dumpAsProto) {
                    dumpLocked(new DualDumpOutputStream(new ProtoOutputStream(fd)));
                } else {
                    try (FileOutputStream out = new FileOutputStream(fd)) {
                        try (PrintWriter w = new PrintWriter(out)) {
                            dumpLocked(new DualDumpOutputStream(new IndentingPrintWriter(w, "  ")));
                        }
                    } catch (IOException ignored) {
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void sendOnPrintJobQueued(PrintJobInfo printJob) {
        Message message = PooledLambda.obtainMessage(
                PrintSpoolerService::onPrintJobQueued, this, printJob);
        Handler.getMain().executeOrSendMessage(message);
    }

    private void sendOnAllPrintJobsForServiceHandled(ComponentName service) {
        Message message = PooledLambda.obtainMessage(
                PrintSpoolerService::onAllPrintJobsForServiceHandled, this, service);
        Handler.getMain().executeOrSendMessage(message);
    }

    private void sendOnAllPrintJobsHandled() {
        Message message = PooledLambda.obtainMessage(
                PrintSpoolerService::onAllPrintJobsHandled, this);
        Handler.getMain().executeOrSendMessage(message);
    }


    private void onPrintJobStateChanged(PrintJobInfo printJob) {
        if (mClient != null) {
            try {
                mClient.onPrintJobStateChanged(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error notify for print job state change.", re);
            }
        }
    }

    private void onAllPrintJobsHandled() {
        if (mClient != null) {
            try {
                mClient.onAllPrintJobsHandled();
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error notify for all print job handled.", re);
            }
        }
    }

    private void onAllPrintJobsForServiceHandled(ComponentName service) {
        if (mClient != null) {
            try {
                mClient.onAllPrintJobsForServiceHandled(service);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error notify for all print jobs per service"
                        + " handled.", re);
            }
        }
    }

    private void onPrintJobQueued(PrintJobInfo printJob) {
        if (mClient != null) {
            try {
                mClient.onPrintJobQueued(printJob);
            } catch (RemoteException re) {
                Slog.e(LOG_TAG, "Error notify for a queued print job.", re);
            }
        }
    }

    private void setClient(IPrintSpoolerClient client) {
        synchronized (mLock) {
            mClient = client;
            if (mClient != null) {
                Message msg = PooledLambda.obtainMessage(
                        PrintSpoolerService::checkAllPrintJobsHandled, this);
                Handler.getMain().sendMessageDelayed(msg,
                        CHECK_ALL_PRINTJOBS_HANDLED_DELAY);
            }
        }
    }

    public List<PrintJobInfo> getPrintJobInfos(ComponentName componentName,
            int state, int appId) {
        List<PrintJobInfo> foundPrintJobs = null;
        synchronized (mLock) {
            final int printJobCount = mPrintJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = mPrintJobs.get(i);
                PrinterId printerId = printJob.getPrinterId();
                final boolean sameComponent = (componentName == null
                        || (printerId != null
                        && componentName.equals(printerId.getServiceName())));
                final boolean sameAppId = appId == PrintManager.APP_ID_ANY
                        || printJob.getAppId() == appId;
                final boolean sameState = (state == printJob.getState())
                        || (state == PrintJobInfo.STATE_ANY)
                        || (state == PrintJobInfo.STATE_ANY_VISIBLE_TO_CLIENTS
                            && isStateVisibleToUser(printJob.getState()))
                        || (state == PrintJobInfo.STATE_ANY_ACTIVE
                            && isActiveState(printJob.getState()))
                        || (state == PrintJobInfo.STATE_ANY_SCHEDULED
                            && isScheduledState(printJob.getState()));
                if (sameComponent && sameAppId && sameState) {
                    if (foundPrintJobs == null) {
                        foundPrintJobs = new ArrayList<>();
                    }
                    foundPrintJobs.add(printJob);
                }
            }
        }
        return foundPrintJobs;
    }

    private boolean isStateVisibleToUser(int state) {
        return (isActiveState(state) && (state == PrintJobInfo.STATE_FAILED
                || state == PrintJobInfo.STATE_COMPLETED || state == PrintJobInfo.STATE_CANCELED
                || state == PrintJobInfo.STATE_BLOCKED));
    }

    public PrintJobInfo getPrintJobInfo(PrintJobId printJobId, int appId) {
        synchronized (mLock) {
            final int printJobCount = mPrintJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = mPrintJobs.get(i);
                if (printJob.getId().equals(printJobId)
                        && (appId == PrintManager.APP_ID_ANY
                        || appId == printJob.getAppId())) {
                    return printJob;
                }
            }
            return null;
        }
    }

    public void createPrintJob(PrintJobInfo printJob) {
        synchronized (mLock) {
            addPrintJobLocked(printJob);
            setPrintJobState(printJob.getId(), PrintJobInfo.STATE_CREATED, null);

            Message message = PooledLambda.obtainMessage(
                    PrintSpoolerService::onPrintJobStateChanged, this, printJob);
            Handler.getMain().executeOrSendMessage(message);
        }
    }

    private void handleReadPrintJobsLocked() {
        // Make a map with the files for a print job since we may have
        // to delete some. One example of getting orphan files if the
        // spooler crashes while constructing a print job. We do not
        // persist partially populated print jobs under construction to
        // avoid special handling for various attributes missing.
        ArrayMap<PrintJobId, File> fileForJobMap = null;
        File[] files = getFilesDir().listFiles();
        if (files != null) {
            final int fileCount = files.length;
            for (int i = 0; i < fileCount; i++) {
                File file = files[i];
                if (file.isFile() && file.getName().startsWith(PRINT_JOB_FILE_PREFIX)) {
                    if (fileForJobMap == null) {
                        fileForJobMap = new ArrayMap<PrintJobId, File>();
                    }
                    String printJobIdString = file.getName().substring(
                            PRINT_JOB_FILE_PREFIX.length(),
                            file.getName().indexOf('.'));
                    PrintJobId printJobId = PrintJobId.unflattenFromString(
                            printJobIdString);
                    fileForJobMap.put(printJobId, file);
                }
            }
        }

        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);

            // We want to have only the orphan files at the end.
            if (fileForJobMap != null) {
                fileForJobMap.remove(printJob.getId());
            }

            switch (printJob.getState()) {
                case PrintJobInfo.STATE_QUEUED:
                case PrintJobInfo.STATE_STARTED:
                case PrintJobInfo.STATE_BLOCKED: {
                    // We have a print job that was queued or started or blocked in
                    // the past but the device battery died or a crash occurred. In
                    // this case we assume the print job failed and let the user
                    // decide whether to restart the job or just cancel it.
                    setPrintJobState(printJob.getId(), PrintJobInfo.STATE_FAILED,
                            getString(R.string.no_connection_to_printer));
                } break;
            }
        }

        if (!mPrintJobs.isEmpty()) {
            // Update the notification.
            mNotificationController.onUpdateNotifications(mPrintJobs);
        }

        // Delete the orphan files.
        if (fileForJobMap != null) {
            final int orphanFileCount = fileForJobMap.size();
            for (int i = 0; i < orphanFileCount; i++) {
                File file = fileForJobMap.valueAt(i);
                file.delete();
            }
        }
    }

    public void checkAllPrintJobsHandled() {
        synchronized (mLock) {
            if (!hasActivePrintJobsLocked()) {
                notifyOnAllPrintJobsHandled();
            }
        }
    }

    public void writePrintJobData(final ParcelFileDescriptor fd, final PrintJobId printJobId) {
        final PrintJobInfo printJob;
        synchronized (mLock) {
            printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
        }
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                FileInputStream in = null;
                FileOutputStream out = null;
                try {
                    if (printJob != null) {
                        File file = generateFileForPrintJob(PrintSpoolerService.this, printJobId);
                        in = new FileInputStream(file);
                        out = new FileOutputStream(fd.getFileDescriptor());
                    }
                    final byte[] buffer = new byte[8192];
                    while (true) {
                        final int readByteCount = in.read(buffer);
                        if (readByteCount < 0) {
                            return null;
                        }
                        out.write(buffer, 0, readByteCount);
                    }
                } catch (FileNotFoundException fnfe) {
                    Log.e(LOG_TAG, "Error writing print job data!", fnfe);
                } catch (IOException ioe) {
                    Log.e(LOG_TAG, "Error writing print job data!", ioe);
                } finally {
                    IoUtils.closeQuietly(in);
                    IoUtils.closeQuietly(out);
                    IoUtils.closeQuietly(fd);
                }
                Log.i(LOG_TAG, "[END WRITE]");
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);
    }

    public static File generateFileForPrintJob(Context context, PrintJobId printJobId) {
        return new File(context.getFilesDir(), PRINT_JOB_FILE_PREFIX
                + printJobId.flattenToString() + "." + PRINT_FILE_EXTENSION);
    }

    private void addPrintJobLocked(PrintJobInfo printJob) {
        mPrintJobs.add(printJob);

        if (printJob.shouldStayAwake()) {
            keepAwakeLocked();
        }

        if (Flags.logPrintJobs() || DEBUG_PRINT_JOB_LIFECYCLE) {
            Slog.i(LOG_TAG, "[ADD] " + printJob);
        }
    }

    private void removeObsoletePrintJobs() {
        synchronized (mLock) {
            boolean persistState = false;
            final int printJobCount = mPrintJobs.size();
            for (int i = printJobCount - 1; i >= 0; i--) {
                PrintJobInfo printJob = mPrintJobs.get(i);
                if (isObsoleteState(printJob.getState())) {
                    mPrintJobs.remove(i);
                    if (Flags.logPrintJobs() || DEBUG_PRINT_JOB_LIFECYCLE) {
                        Slog.i(LOG_TAG, "[REMOVE] " + printJob.getId().flattenToString());
                    }
                    removePrintJobFileLocked(printJob.getId());
                    persistState = true;
                }
            }

            checkIfStillKeepAwakeLocked();

            if (persistState) {
                mPersistanceManager.writeStateLocked();
            }
        }
    }

    private void removePrintJobFileLocked(PrintJobId printJobId) {
        File file = generateFileForPrintJob(PrintSpoolerService.this, printJobId);
        if (file.exists()) {
            file.delete();
            if (DEBUG_PRINT_JOB_LIFECYCLE) {
                Slog.i(LOG_TAG, "[REMOVE FILE FOR] " + printJobId);
            }
        }
    }

    /**
     * Notify all interested parties that a print job has been updated.
     *
     * @param printJob The updated print job.
     */
    private void notifyPrintJobUpdated(PrintJobInfo printJob) {
        Message message = PooledLambda.obtainMessage(
                PrintSpoolerService::onPrintJobStateChanged, this, printJob);
        Handler.getMain().executeOrSendMessage(message);

        mNotificationController.onUpdateNotifications(mPrintJobs);
    }

    public boolean setPrintJobState(PrintJobId printJobId, int state, String error) {
        boolean success = false;

        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                final int oldState = printJob.getState();
                if (oldState == state) {
                    return false;
                }

                success = true;

                printJob.setState(state);
                printJob.setStatus(error);
                printJob.setCancelling(false);

                if (printJob.shouldStayAwake()) {
                    keepAwakeLocked();
                } else {
                    checkIfStillKeepAwakeLocked();
                }

                if (Flags.logPrintJobs() || DEBUG_PRINT_JOB_LIFECYCLE) {
                    Slog.i(LOG_TAG, "[STATE CHANGED] " + printJob);
                }

                MetricsLogger.histogram(this, PRINT_JOB_STATE_HISTO, state);
                switch (state) {
                    case PrintJobInfo.STATE_COMPLETED:
                    case PrintJobInfo.STATE_CANCELED:
                        mPrintJobs.remove(printJob);
                        removePrintJobFileLocked(printJob.getId());
                        // $fall-through$

                    case PrintJobInfo.STATE_FAILED: {
                        PrinterId printerId = printJob.getPrinterId();
                        if (printerId != null) {
                            ComponentName service = printerId.getServiceName();
                            if (!hasActivePrintJobsForServiceLocked(service)) {
                                sendOnAllPrintJobsForServiceHandled(service);
                            }
                        }
                    } break;

                    case PrintJobInfo.STATE_QUEUED: {
                        sendOnPrintJobQueued(new PrintJobInfo(printJob));
                    }  break;
                }

                if (shouldPersistPrintJob(printJob)) {
                    mPersistanceManager.writeStateLocked();
                }

                if (!hasActivePrintJobsLocked()) {
                    notifyOnAllPrintJobsHandled();
                }

                notifyPrintJobUpdated(printJob);
            }
        }

        return success;
    }

    /**
     * Set the progress for a print job.
     *
     * @param printJobId ID of the print job to update
     * @param progress the new progress
     */
    public void setProgress(@NonNull PrintJobId printJobId,
            @FloatRange(from=0.0, to=1.0) float progress) {
        synchronized (mLock) {
            getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY).setProgress(progress);

            mNotificationController.onUpdateNotifications(mPrintJobs);
        }
    }

    /**
     * Set the status for a print job.
     *
     * @param printJobId ID of the print job to update
     * @param status the new status
     */
    public void setStatus(@NonNull PrintJobId printJobId, @Nullable CharSequence status) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);

            if (printJob != null) {
                printJob.setStatus(status);
                notifyPrintJobUpdated(printJob);
            }
        }
    }

    /**
     * Set the status for a print job.
     *
     * @param printJobId ID of the print job to update
     * @param status the new status as a string resource
     * @param appPackageName app package the resource belongs to
     */
    public void setStatus(@NonNull PrintJobId printJobId, @StringRes int status,
            @Nullable CharSequence appPackageName) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);

            if (printJob != null) {
                printJob.setStatus(status, appPackageName);
                notifyPrintJobUpdated(printJob);
            }
        }
    }

    public boolean hasActivePrintJobsLocked() {
        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);
            if (isActiveState(printJob.getState())) {
                return true;
            }
        }
        return false;
    }

    public boolean hasActivePrintJobsForServiceLocked(ComponentName service) {
        final int printJobCount = mPrintJobs.size();
        for (int i = 0; i < printJobCount; i++) {
            PrintJobInfo printJob = mPrintJobs.get(i);
            if (isActiveState(printJob.getState()) && printJob.getPrinterId() != null
                    && printJob.getPrinterId().getServiceName().equals(service)) {
                return true;
            }
        }
        return false;
    }

    private boolean isObsoleteState(int printJobState) {
        return (isTerminalState(printJobState)
                || printJobState == PrintJobInfo.STATE_QUEUED);
    }

    private boolean isScheduledState(int printJobState) {
        return printJobState == PrintJobInfo.STATE_QUEUED
                || printJobState == PrintJobInfo.STATE_STARTED
                || printJobState == PrintJobInfo.STATE_BLOCKED;
    }

    private boolean isActiveState(int printJobState) {
        return printJobState == PrintJobInfo.STATE_CREATED
                || printJobState == PrintJobInfo.STATE_QUEUED
                || printJobState == PrintJobInfo.STATE_STARTED
                || printJobState == PrintJobInfo.STATE_BLOCKED;
    }

    private boolean isTerminalState(int printJobState) {
        return printJobState == PrintJobInfo.STATE_COMPLETED
                || printJobState == PrintJobInfo.STATE_CANCELED;
    }

    public boolean setPrintJobTag(PrintJobId printJobId, String tag) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                String printJobTag = printJob.getTag();
                if (printJobTag == null) {
                    if (tag == null) {
                        return false;
                    }
                } else if (printJobTag.equals(tag)) {
                    return false;
                }
                printJob.setTag(tag);
                if (shouldPersistPrintJob(printJob)) {
                    mPersistanceManager.writeStateLocked();
                }
                return true;
            }
        }
        return false;
    }

    public void setPrintJobCancelling(PrintJobId printJobId, boolean cancelling) {
        synchronized (mLock) {
            PrintJobInfo printJob = getPrintJobInfo(printJobId, PrintManager.APP_ID_ANY);
            if (printJob != null) {
                printJob.setCancelling(cancelling);
                if (shouldPersistPrintJob(printJob)) {
                    mPersistanceManager.writeStateLocked();
                }
                mNotificationController.onUpdateNotifications(mPrintJobs);

                if (printJob.shouldStayAwake()) {
                    keepAwakeLocked();
                } else {
                    checkIfStillKeepAwakeLocked();
                }

                Message message = PooledLambda.obtainMessage(
                        PrintSpoolerService::onPrintJobStateChanged, this, printJob);
                Handler.getMain().executeOrSendMessage(message);
            }
        }
    }

    public void updatePrintJobUserConfigurableOptionsNoPersistence(PrintJobInfo printJob) {
        synchronized (mLock) {
            final int printJobCount = mPrintJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo cachedPrintJob = mPrintJobs.get(i);
                if (cachedPrintJob.getId().equals(printJob.getId())) {
                    cachedPrintJob.setPrinterId(printJob.getPrinterId());
                    cachedPrintJob.setPrinterName(printJob.getPrinterName());
                    cachedPrintJob.setCopies(printJob.getCopies());
                    cachedPrintJob.setDocumentInfo(printJob.getDocumentInfo());
                    cachedPrintJob.setPages(printJob.getPages());
                    cachedPrintJob.setAttributes(printJob.getAttributes());
                    cachedPrintJob.setAdvancedOptions(printJob.getAdvancedOptions());
                    return;
                }
            }
            throw new IllegalArgumentException("No print job with id:" + printJob.getId());
        }
    }

    private boolean shouldPersistPrintJob(PrintJobInfo printJob) {
        return printJob.getState() >= PrintJobInfo.STATE_QUEUED;
    }

    private void notifyOnAllPrintJobsHandled() {
        // This has to run on the tread that is persisting the current state
        // since this call may result in the system unbinding from the spooler
        // and as a result the spooler process may get killed before the write
        // completes.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                sendOnAllPrintJobsHandled();
                return null;
            }
        }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
    }

    /**
     * Handle that a custom icon for a printer was loaded.
     *
     * @param printerId the id of the printer the icon belongs to
     * @param icon the icon that was loaded
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon
     */
    public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon) {
        mCustomIconCache.onCustomPrinterIconLoaded(printerId, icon);
    }

    /**
     * Get the custom icon for a printer. If the icon is not cached, the icon is
     * requested asynchronously. Once it is available the printer is updated.
     *
     * @param printerId the id of the printer the icon should be loaded for
     * @return the custom icon to be used for the printer or null if the icon is
     *         not yet available
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon
     */
    public Icon getCustomPrinterIcon(PrinterId printerId) {
        return mCustomIconCache.getIcon(printerId);
    }

    /**
     * Clear the custom printer icon cache.
     */
    public void clearCustomPrinterIconCache() {
        mCustomIconCache.clear();
    }

    private final class PersistenceManager {
        private static final String PERSIST_FILE_NAME = "print_spooler_state.xml";

        private static final String TAG_SPOOLER = "spooler";
        private static final String TAG_JOB = "job";

        private static final String TAG_PRINTER_ID = "printerId";
        private static final String TAG_PAGE_RANGE = "pageRange";
        private static final String TAG_ATTRIBUTES = "attributes";
        private static final String TAG_DOCUMENT_INFO = "documentInfo";

        private static final String ATTR_ID = "id";
        private static final String ATTR_LABEL = "label";
        private static final String ATTR_LABEL_RES_ID = "labelResId";
        private static final String ATTR_PACKAGE_NAME = "packageName";
        private static final String ATTR_STATE = "state";
        private static final String ATTR_APP_ID = "appId";
        private static final String ATTR_TAG = "tag";
        private static final String ATTR_CREATION_TIME = "creationTime";
        private static final String ATTR_COPIES = "copies";
        private static final String ATTR_PRINTER_NAME = "printerName";
        private static final String ATTR_STATE_REASON = "stateReason";
        private static final String ATTR_STATUS = "status";
        private static final String ATTR_PROGRESS = "progress";
        private static final String ATTR_CANCELLING = "cancelling";

        private static final String TAG_ADVANCED_OPTIONS = "advancedOptions";
        private static final String TAG_ADVANCED_OPTION = "advancedOption";
        private static final String ATTR_KEY = "key";
        private static final String ATTR_TYPE = "type";
        private static final String ATTR_VALUE = "value";
        private static final String TYPE_STRING = "string";
        private static final String TYPE_INT = "int";

        private static final String TAG_MEDIA_SIZE = "mediaSize";
        private static final String TAG_RESOLUTION = "resolution";
        private static final String TAG_MARGINS = "margins";

        private static final String ATTR_COLOR_MODE = "colorMode";
        private static final String ATTR_DUPLEX_MODE = "duplexMode";

        private static final String ATTR_LOCAL_ID = "localId";
        private static final String ATTR_SERVICE_NAME = "serviceName";

        private static final String ATTR_WIDTH_MILS = "widthMils";
        private static final String ATTR_HEIGHT_MILS = "heightMils";

        private static final String ATTR_HORIZONTAL_DPI = "horizontalDip";
        private static final String ATTR_VERTICAL_DPI = "verticalDpi";

        private static final String ATTR_LEFT_MILS = "leftMils";
        private static final String ATTR_TOP_MILS = "topMils";
        private static final String ATTR_RIGHT_MILS = "rightMils";
        private static final String ATTR_BOTTOM_MILS = "bottomMils";

        private static final String ATTR_START = "start";
        private static final String ATTR_END = "end";

        private static final String ATTR_NAME = "name";
        private static final String ATTR_PAGE_COUNT = "pageCount";
        private static final String ATTR_CONTENT_TYPE = "contentType";
        private static final String ATTR_DATA_SIZE = "dataSize";

        private final AtomicFile mStatePersistFile;

        private boolean mWriteStateScheduled;

        private PersistenceManager() {
            mStatePersistFile = new AtomicFile(new File(getFilesDir(),
                    PERSIST_FILE_NAME), "print-spooler");
        }

        public void writeStateLocked() {
            if (!PERSISTENCE_MANAGER_ENABLED) {
                return;
            }
            if (mWriteStateScheduled) {
                return;
            }
            mWriteStateScheduled = true;
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    synchronized (mLock) {
                        mWriteStateScheduled = false;
                        doWriteStateLocked();
                    }
                    return null;
                }
            }.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, (Void[]) null);
        }

        private void doWriteStateLocked() {
            if (DEBUG_PERSISTENCE) {
                Log.i(LOG_TAG, "[PERSIST START]");
            }
            FileOutputStream out = null;
            try {
                out = mStatePersistFile.startWrite();

                XmlSerializer serializer = new FastXmlSerializer();
                serializer.setOutput(out, StandardCharsets.UTF_8.name());
                serializer.startDocument(null, true);
                serializer.startTag(null, TAG_SPOOLER);

                List<PrintJobInfo> printJobs = mPrintJobs;

                final int printJobCount = printJobs.size();
                for (int j = 0; j < printJobCount; j++) {
                    PrintJobInfo printJob = printJobs.get(j);

                    if (!shouldPersistPrintJob(printJob)) {
                        continue;
                    }

                    serializer.startTag(null, TAG_JOB);

                    serializer.attribute(null, ATTR_ID, printJob.getId().flattenToString());
                    serializer.attribute(null, ATTR_LABEL, printJob.getLabel().toString());
                    serializer.attribute(null, ATTR_STATE, String.valueOf(printJob.getState()));
                    serializer.attribute(null, ATTR_APP_ID, String.valueOf(printJob.getAppId()));
                    String tag = printJob.getTag();
                    if (tag != null) {
                        serializer.attribute(null, ATTR_TAG, tag);
                    }
                    serializer.attribute(null, ATTR_CREATION_TIME, String.valueOf(
                            printJob.getCreationTime()));
                    serializer.attribute(null, ATTR_COPIES, String.valueOf(printJob.getCopies()));
                    String printerName = printJob.getPrinterName();
                    if (!TextUtils.isEmpty(printerName)) {
                        serializer.attribute(null, ATTR_PRINTER_NAME, printerName);
                    }
                    serializer.attribute(null, ATTR_CANCELLING, String.valueOf(
                            printJob.isCancelling()));

                    float progress = printJob.getProgress();
                    if (!Float.isNaN(progress)) {
                        serializer.attribute(null, ATTR_PROGRESS, String.valueOf(progress));
                    }

                    CharSequence status = printJob.getStatus(getPackageManager());
                    if (!TextUtils.isEmpty(status)) {
                        serializer.attribute(null, ATTR_STATUS, status.toString());
                    }

                    PrinterId printerId = printJob.getPrinterId();
                    if (printerId != null) {
                        serializer.startTag(null, TAG_PRINTER_ID);
                        serializer.attribute(null, ATTR_LOCAL_ID, printerId.getLocalId());
                        serializer.attribute(null, ATTR_SERVICE_NAME, printerId.getServiceName()
                                .flattenToString());
                        serializer.endTag(null, TAG_PRINTER_ID);
                    }

                    PageRange[] pages = printJob.getPages();
                    if (pages != null) {
                        for (int i = 0; i < pages.length; i++) {
                            serializer.startTag(null, TAG_PAGE_RANGE);
                            serializer.attribute(null, ATTR_START, String.valueOf(
                                    pages[i].getStart()));
                            serializer.attribute(null, ATTR_END, String.valueOf(
                                    pages[i].getEnd()));
                            serializer.endTag(null, TAG_PAGE_RANGE);
                        }
                    }

                    PrintAttributes attributes = printJob.getAttributes();
                    if (attributes != null) {
                        serializer.startTag(null, TAG_ATTRIBUTES);

                        final int colorMode = attributes.getColorMode();
                        serializer.attribute(null, ATTR_COLOR_MODE,
                                String.valueOf(colorMode));

                        final int duplexMode = attributes.getDuplexMode();
                        serializer.attribute(null, ATTR_DUPLEX_MODE,
                                String.valueOf(duplexMode));

                        MediaSize mediaSize = attributes.getMediaSize();
                        if (mediaSize != null) {
                            serializer.startTag(null, TAG_MEDIA_SIZE);
                            serializer.attribute(null, ATTR_ID, mediaSize.getId());
                            serializer.attribute(null, ATTR_WIDTH_MILS, String.valueOf(
                                    mediaSize.getWidthMils()));
                            serializer.attribute(null, ATTR_HEIGHT_MILS, String.valueOf(
                                    mediaSize.getHeightMils()));
                            // We prefer to store only the package name and
                            // resource id and fallback to the label.
                            if (!TextUtils.isEmpty(mediaSize.mPackageName)
                                    && mediaSize.mLabelResId > 0) {
                                serializer.attribute(null, ATTR_PACKAGE_NAME,
                                        mediaSize.mPackageName);
                                serializer.attribute(null, ATTR_LABEL_RES_ID,
                                        String.valueOf(mediaSize.mLabelResId));
                            } else {
                                serializer.attribute(null, ATTR_LABEL,
                                        mediaSize.getLabel(getPackageManager()));
                            }
                            serializer.endTag(null, TAG_MEDIA_SIZE);
                        }

                        Resolution resolution = attributes.getResolution();
                        if (resolution != null) {
                            serializer.startTag(null, TAG_RESOLUTION);
                            serializer.attribute(null, ATTR_ID, resolution.getId());
                            serializer.attribute(null, ATTR_HORIZONTAL_DPI, String.valueOf(
                                    resolution.getHorizontalDpi()));
                            serializer.attribute(null, ATTR_VERTICAL_DPI, String.valueOf(
                                    resolution.getVerticalDpi()));
                            serializer.attribute(null, ATTR_LABEL,
                                    resolution.getLabel());
                            serializer.endTag(null, TAG_RESOLUTION);
                        }

                        Margins margins = attributes.getMinMargins();
                        if (margins != null) {
                            serializer.startTag(null, TAG_MARGINS);
                            serializer.attribute(null, ATTR_LEFT_MILS, String.valueOf(
                                    margins.getLeftMils()));
                            serializer.attribute(null, ATTR_TOP_MILS, String.valueOf(
                                    margins.getTopMils()));
                            serializer.attribute(null, ATTR_RIGHT_MILS, String.valueOf(
                                    margins.getRightMils()));
                            serializer.attribute(null, ATTR_BOTTOM_MILS, String.valueOf(
                                    margins.getBottomMils()));
                            serializer.endTag(null, TAG_MARGINS);
                        }

                        serializer.endTag(null, TAG_ATTRIBUTES);
                    }

                    PrintDocumentInfo documentInfo = printJob.getDocumentInfo();
                    if (documentInfo != null) {
                        serializer.startTag(null, TAG_DOCUMENT_INFO);
                        serializer.attribute(null, ATTR_NAME, documentInfo.getName());
                        serializer.attribute(null, ATTR_CONTENT_TYPE, String.valueOf(
                                documentInfo.getContentType()));
                        serializer.attribute(null, ATTR_PAGE_COUNT, String.valueOf(
                                documentInfo.getPageCount()));
                        serializer.attribute(null, ATTR_DATA_SIZE, String.valueOf(
                                documentInfo.getDataSize()));
                        serializer.endTag(null, TAG_DOCUMENT_INFO);
                    }

                    Bundle advancedOptions = printJob.getAdvancedOptions();
                    if (advancedOptions != null) {
                        serializer.startTag(null, TAG_ADVANCED_OPTIONS);
                        for (String key : advancedOptions.keySet()) {
                            Object value = advancedOptions.get(key);
                            if (value instanceof String) {
                                String stringValue = (String) value;
                                serializer.startTag(null, TAG_ADVANCED_OPTION);
                                serializer.attribute(null, ATTR_KEY, key);
                                serializer.attribute(null, ATTR_TYPE, TYPE_STRING);
                                serializer.attribute(null, ATTR_VALUE, stringValue);
                                serializer.endTag(null, TAG_ADVANCED_OPTION);
                            } else if (value instanceof Integer) {
                                String intValue = Integer.toString((Integer) value);
                                serializer.startTag(null, TAG_ADVANCED_OPTION);
                                serializer.attribute(null, ATTR_KEY, key);
                                serializer.attribute(null, ATTR_TYPE, TYPE_INT);
                                serializer.attribute(null, ATTR_VALUE, intValue);
                                serializer.endTag(null, TAG_ADVANCED_OPTION);
                            }
                        }
                        serializer.endTag(null, TAG_ADVANCED_OPTIONS);
                    }

                    serializer.endTag(null, TAG_JOB);

                    if (DEBUG_PERSISTENCE) {
                        Log.i(LOG_TAG, "[PERSISTED] " + printJob);
                    }
                }

                serializer.endTag(null, TAG_SPOOLER);
                serializer.endDocument();
                mStatePersistFile.finishWrite(out);
                if (DEBUG_PERSISTENCE) {
                    Log.i(LOG_TAG, "[PERSIST END]");
                }
            } catch (IOException e) {
                Slog.w(LOG_TAG, "Failed to write state, restoring backup.", e);
                mStatePersistFile.failWrite(out);
            } finally {
                IoUtils.closeQuietly(out);
            }
        }

        public void readStateLocked() {
            if (!PERSISTENCE_MANAGER_ENABLED) {
                return;
            }
            FileInputStream in = null;
            try {
                in = mStatePersistFile.openRead();
            } catch (FileNotFoundException e) {
                if (DEBUG_PERSISTENCE) {
                    Log.d(LOG_TAG, "No existing print spooler state.");
                }
                return;
            }
            try {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(in, StandardCharsets.UTF_8.name());
                parseStateLocked(parser);
            } catch (IllegalStateException ise) {
                Slog.w(LOG_TAG, "Failed parsing ", ise);
            } catch (NullPointerException npe) {
                Slog.w(LOG_TAG, "Failed parsing ", npe);
            } catch (NumberFormatException nfe) {
                Slog.w(LOG_TAG, "Failed parsing ", nfe);
            } catch (XmlPullParserException xppe) {
                Slog.w(LOG_TAG, "Failed parsing ", xppe);
            } catch (IOException ioe) {
                Slog.w(LOG_TAG, "Failed parsing ", ioe);
            } catch (IndexOutOfBoundsException iobe) {
                Slog.w(LOG_TAG, "Failed parsing ", iobe);
            } finally {
                IoUtils.closeQuietly(in);
            }
        }

        private void parseStateLocked(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            parser.next();
            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.START_TAG, TAG_SPOOLER);
            parser.next();

            while (parsePrintJobLocked(parser)) {
                parser.next();
            }

            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.END_TAG, TAG_SPOOLER);
        }

        private boolean parsePrintJobLocked(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            skipEmptyTextTags(parser);
            if (!accept(parser, XmlPullParser.START_TAG, TAG_JOB)) {
                return false;
            }

            PrintJobInfo printJob = new PrintJobInfo();

            PrintJobId printJobId = PrintJobId.unflattenFromString(
                    parser.getAttributeValue(null, ATTR_ID));
            printJob.setId(printJobId);
            String label = parser.getAttributeValue(null, ATTR_LABEL);
            printJob.setLabel(label);
            final int state = Integer.parseInt(parser.getAttributeValue(null, ATTR_STATE));
            printJob.setState(state);
            final int appId = Integer.parseInt(parser.getAttributeValue(null, ATTR_APP_ID));
            printJob.setAppId(appId);
            String tag = parser.getAttributeValue(null, ATTR_TAG);
            printJob.setTag(tag);
            String creationTime = parser.getAttributeValue(null, ATTR_CREATION_TIME);
            printJob.setCreationTime(Long.parseLong(creationTime));
            String copies = parser.getAttributeValue(null, ATTR_COPIES);
            printJob.setCopies(Integer.parseInt(copies));
            String printerName = parser.getAttributeValue(null, ATTR_PRINTER_NAME);
            printJob.setPrinterName(printerName);

            String progressString = parser.getAttributeValue(null, ATTR_PROGRESS);
            if (progressString != null) {
                float progress = Float.parseFloat(progressString);

                if (progress != -1) {
                    printJob.setProgress(progress);
                }
            }

            CharSequence status = parser.getAttributeValue(null, ATTR_STATUS);
            printJob.setStatus(status);

            // stateReason is deprecated, but might be used by old print jobs
            String stateReason = parser.getAttributeValue(null, ATTR_STATE_REASON);
            if (stateReason != null) {
                printJob.setStatus(stateReason);
            }

            String cancelling = parser.getAttributeValue(null, ATTR_CANCELLING);
            printJob.setCancelling(!TextUtils.isEmpty(cancelling)
                    ? Boolean.parseBoolean(cancelling) : false);

            parser.next();

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_PRINTER_ID)) {
                String localId = parser.getAttributeValue(null, ATTR_LOCAL_ID);
                ComponentName service = ComponentName.unflattenFromString(parser.getAttributeValue(
                        null, ATTR_SERVICE_NAME));
                printJob.setPrinterId(new PrinterId(service, localId));
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PRINTER_ID);
                parser.next();
            }

            skipEmptyTextTags(parser);
            List<PageRange> pageRanges = null;
            while (accept(parser, XmlPullParser.START_TAG, TAG_PAGE_RANGE)) {
                final int start = Integer.parseInt(parser.getAttributeValue(null, ATTR_START));
                final int end = Integer.parseInt(parser.getAttributeValue(null, ATTR_END));
                PageRange pageRange = new PageRange(start, end);
                if (pageRanges == null) {
                    pageRanges = new ArrayList<PageRange>();
                }
                pageRanges.add(pageRange);
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_PAGE_RANGE);
                parser.next();
                skipEmptyTextTags(parser);
            }
            if (pageRanges != null) {
                PageRange[] pageRangesArray = new PageRange[pageRanges.size()];
                pageRanges.toArray(pageRangesArray);
                printJob.setPages(pageRangesArray);
            }

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_ATTRIBUTES)) {

                PrintAttributes.Builder builder = new PrintAttributes.Builder();

                String colorMode = parser.getAttributeValue(null, ATTR_COLOR_MODE);
                builder.setColorMode(Integer.parseInt(colorMode));

                String duplexMode = parser.getAttributeValue(null, ATTR_DUPLEX_MODE);
                // Duplex mode was added later, so null check is needed.
                if (duplexMode != null) {
                    builder.setDuplexMode(Integer.parseInt(duplexMode));
                }

                parser.next();

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_MEDIA_SIZE)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    final int widthMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_WIDTH_MILS));
                    final int heightMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_HEIGHT_MILS));
                    String packageName = parser.getAttributeValue(null, ATTR_PACKAGE_NAME);
                    String labelResIdString = parser.getAttributeValue(null, ATTR_LABEL_RES_ID);
                    final int labelResId = (labelResIdString != null)
                            ? Integer.parseInt(labelResIdString) : 0;
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    MediaSize mediaSize = new MediaSize(id, label, packageName,
                                widthMils, heightMils, labelResId);
                    builder.setMediaSize(mediaSize);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_MEDIA_SIZE);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_RESOLUTION)) {
                    String id = parser.getAttributeValue(null, ATTR_ID);
                    label = parser.getAttributeValue(null, ATTR_LABEL);
                    final int horizontalDpi = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_HORIZONTAL_DPI));
                    final int verticalDpi = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_VERTICAL_DPI));
                    Resolution resolution = new Resolution(id, label, horizontalDpi, verticalDpi);
                    builder.setResolution(resolution);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_RESOLUTION);
                    parser.next();
                }

                skipEmptyTextTags(parser);
                if (accept(parser, XmlPullParser.START_TAG, TAG_MARGINS)) {
                    final int leftMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_LEFT_MILS));
                    final int topMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_TOP_MILS));
                    final int rightMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_RIGHT_MILS));
                    final int bottomMils = Integer.parseInt(parser.getAttributeValue(null,
                            ATTR_BOTTOM_MILS));
                    Margins margins = new Margins(leftMils, topMils, rightMils, bottomMils);
                    builder.setMinMargins(margins);
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_MARGINS);
                    parser.next();
                }

                printJob.setAttributes(builder.build());

                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_ATTRIBUTES);
                parser.next();
            }

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_DOCUMENT_INFO)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                final int pageCount = Integer.parseInt(parser.getAttributeValue(null,
                        ATTR_PAGE_COUNT));
                final int contentType = Integer.parseInt(parser.getAttributeValue(null,
                        ATTR_CONTENT_TYPE));
                final int dataSize = Integer.parseInt(parser.getAttributeValue(null,
                        ATTR_DATA_SIZE));
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(name)
                        .setPageCount(pageCount)
                        .setContentType(contentType).build();
                printJob.setDocumentInfo(info);
                info.setDataSize(dataSize);
                parser.next();
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_DOCUMENT_INFO);
                parser.next();
            }

            skipEmptyTextTags(parser);
            if (accept(parser, XmlPullParser.START_TAG, TAG_ADVANCED_OPTIONS)) {
                parser.next();
                skipEmptyTextTags(parser);
                Bundle advancedOptions = new Bundle();
                while (accept(parser, XmlPullParser.START_TAG, TAG_ADVANCED_OPTION)) {
                    String key = parser.getAttributeValue(null, ATTR_KEY);
                    String value = parser.getAttributeValue(null, ATTR_VALUE);
                    String type = parser.getAttributeValue(null, ATTR_TYPE);
                    if (TYPE_STRING.equals(type)) {
                        advancedOptions.putString(key, value);
                    } else if (TYPE_INT.equals(type)) {
                        advancedOptions.putInt(key, Integer.parseInt(value));
                    }
                    parser.next();
                    skipEmptyTextTags(parser);
                    expect(parser, XmlPullParser.END_TAG, TAG_ADVANCED_OPTION);
                    parser.next();
                    skipEmptyTextTags(parser);
                }
                printJob.setAdvancedOptions(advancedOptions);
                skipEmptyTextTags(parser);
                expect(parser, XmlPullParser.END_TAG, TAG_ADVANCED_OPTIONS);
                parser.next();
            }

            mPrintJobs.add(printJob);

            if (printJob.shouldStayAwake()) {
                keepAwakeLocked();
            }

            if (DEBUG_PERSISTENCE) {
                Log.i(LOG_TAG, "[RESTORED] " + printJob);
            }

            skipEmptyTextTags(parser);
            expect(parser, XmlPullParser.END_TAG, TAG_JOB);

            return true;
        }

        private void expect(XmlPullParser parser, int type, String tag)
                throws XmlPullParserException {
            if (!accept(parser, type, tag)) {
                throw new XmlPullParserException("Exepected event: " + type
                        + " and tag: " + tag + " but got event: " + parser.getEventType()
                        + " and tag:" + parser.getName());
            }
        }

        private void skipEmptyTextTags(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            while (accept(parser, XmlPullParser.TEXT, null)
                    && "\n".equals(parser.getText())) {
                parser.next();
            }
        }

        private boolean accept(XmlPullParser parser, int type, String tag)
                throws XmlPullParserException {
            if (parser.getEventType() != type) {
                return false;
            }
            if (tag != null) {
                if (!tag.equals(parser.getName())) {
                    return false;
                }
            } else if (parser.getName() != null) {
                return false;
            }
            return true;
        }
    }

    /**
     * Keep the system awake as a print job needs to be processed.
     */
    private void keepAwakeLocked() {
        if (!mKeepAwake.isHeld()) {
            mKeepAwake.acquire();
        }
    }

    /**
     * Check if we still need to keep the system awake.
     *
     * @see #keepAwakeLocked
     */
    private void checkIfStillKeepAwakeLocked() {
        if (mKeepAwake.isHeld()) {
            int numPrintJobs = mPrintJobs.size();
            for (int i = 0; i < numPrintJobs; i++) {
                if (mPrintJobs.get(i).shouldStayAwake()) {
                    return;
                }
            }

            mKeepAwake.release();
        }
    }

    public final class PrintSpooler extends IPrintSpooler.Stub {
        @Override
        public void getPrintJobInfos(IPrintSpoolerCallbacks callback,
                ComponentName componentName, int state, int appId, int sequence)
                throws RemoteException {
            List<PrintJobInfo> printJobs = null;
            try {
                printJobs = PrintSpoolerService.this.getPrintJobInfos(
                        componentName, state, appId);
            } finally {
                callback.onGetPrintJobInfosResult(printJobs, sequence);
            }
        }

        @Override
        public void getPrintJobInfo(PrintJobId printJobId, IPrintSpoolerCallbacks callback,
                int appId, int sequence) throws RemoteException {
            PrintJobInfo printJob = null;
            try {
                printJob = PrintSpoolerService.this.getPrintJobInfo(printJobId, appId);
            } finally {
                callback.onGetPrintJobInfoResult(printJob, sequence);
            }
        }

        @Override
        public void createPrintJob(PrintJobInfo printJob) {
            PrintSpoolerService.this.createPrintJob(printJob);
        }

        @Override
        public void setPrintJobState(PrintJobId printJobId, int state, String error,
                IPrintSpoolerCallbacks callback, int sequece) throws RemoteException {
            boolean success = false;
            try {
                success = PrintSpoolerService.this.setPrintJobState(
                        printJobId, state, error);
            } finally {
                callback.onSetPrintJobStateResult(success, sequece);
            }
        }

        @Override
        public void setPrintJobTag(PrintJobId printJobId, String tag,
                IPrintSpoolerCallbacks callback, int sequece) throws RemoteException {
            boolean success = false;
            try {
                success = PrintSpoolerService.this.setPrintJobTag(printJobId, tag);
            } finally {
                callback.onSetPrintJobTagResult(success, sequece);
            }
        }

        @Override
        public void writePrintJobData(ParcelFileDescriptor fd, PrintJobId printJobId) {
            PrintSpoolerService.this.writePrintJobData(fd, printJobId);
        }

        @Override
        public void setClient(IPrintSpoolerClient client) {
            Message message = PooledLambda.obtainMessage(
                    PrintSpoolerService::setClient, PrintSpoolerService.this, client);
            Handler.getMain().executeOrSendMessage(message);
        }

        @Override
        public void removeObsoletePrintJobs() {
            PrintSpoolerService.this.removeObsoletePrintJobs();
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
            PrintSpoolerService.this.dump(fd, writer, args);
        }

        @Override
        public void setPrintJobCancelling(PrintJobId printJobId, boolean cancelling) {
            PrintSpoolerService.this.setPrintJobCancelling(printJobId, cancelling);
        }

        @Override
        public void pruneApprovedPrintServices(List<ComponentName> servicesToKeep) {
            (new ApprovedPrintServices(PrintSpoolerService.this))
                    .pruneApprovedServices(servicesToKeep);
        }

        @Override
        public void setProgress(@NonNull PrintJobId printJobId,
                @FloatRange(from=0.0, to=1.0) float progress) throws RemoteException {
            PrintSpoolerService.this.setProgress(printJobId, progress);
        }

        @Override
        public void setStatus(@NonNull PrintJobId printJobId,
                @Nullable CharSequence status) throws RemoteException {
            PrintSpoolerService.this.setStatus(printJobId, status);
        }

        @Override
        public void setStatusRes(@NonNull PrintJobId printJobId, @StringRes int status,
                @NonNull CharSequence appPackageName) throws RemoteException {
            PrintSpoolerService.this.setStatus(printJobId, status, appPackageName);
        }


        public PrintSpoolerService getService() {
            return PrintSpoolerService.this;
        }

        @Override
        public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon,
                IPrintSpoolerCallbacks callbacks, int sequence)
                throws RemoteException {
            try {
                PrintSpoolerService.this.onCustomPrinterIconLoaded(printerId, icon);
            } finally {
                callbacks.onCustomPrinterIconCached(sequence);
            }
        }

        @Override
        public void getCustomPrinterIcon(PrinterId printerId, IPrintSpoolerCallbacks callbacks,
                int sequence) throws RemoteException {
            Icon icon = null;
            try {
                icon = PrintSpoolerService.this.getCustomPrinterIcon(printerId);
            } finally {
                callbacks.onGetCustomPrinterIconResult(icon, sequence);
            }
        }

        @Override
        public void clearCustomPrinterIconCache(IPrintSpoolerCallbacks callbacks,
                int sequence) throws RemoteException {
            try {
                PrintSpoolerService.this.clearCustomPrinterIconCache();
            } finally {
                callbacks.customPrinterIconCacheCleared(sequence);
            }
        }

    }
}
