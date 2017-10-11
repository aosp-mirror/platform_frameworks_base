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

package com.android.server.print;

import static android.content.pm.PackageManager.GET_META_DATA;
import static android.content.pm.PackageManager.GET_SERVICES;
import static android.content.pm.PackageManager.MATCH_DEBUG_TRIAGED_MISSING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ParceledListSlice;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IInterface;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.IPrintDocumentAdapter;
import android.print.IPrintJobStateChangeListener;
import android.print.IPrintServicesChangeListener;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintAttributes;
import android.print.PrintJobId;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.printservice.recommendation.IRecommendationsChangeListener;
import android.printservice.recommendation.RecommendationInfo;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.BackgroundThread;
import com.android.internal.os.SomeArgs;
import com.android.server.print.RemotePrintService.PrintServiceCallbacks;
import com.android.server.print.RemotePrintServiceRecommendationService
        .RemotePrintServiceRecommendationServiceCallbacks;
import com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the print state for a user.
 */
final class UserState implements PrintSpoolerCallbacks, PrintServiceCallbacks,
        RemotePrintServiceRecommendationServiceCallbacks {

    private static final String LOG_TAG = "UserState";

    private static final boolean DEBUG = false;

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private static final int SERVICE_RESTART_DELAY_MILLIS = 500;

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Intent mQueryIntent =
            new Intent(android.printservice.PrintService.SERVICE_INTERFACE);

    private final ArrayMap<ComponentName, RemotePrintService> mActiveServices =
            new ArrayMap<ComponentName, RemotePrintService>();

    private final List<PrintServiceInfo> mInstalledServices =
            new ArrayList<PrintServiceInfo>();

    private final Set<ComponentName> mDisabledServices =
            new ArraySet<ComponentName>();

    private final PrintJobForAppCache mPrintJobForAppCache =
            new PrintJobForAppCache();

    private final Object mLock;

    private final Context mContext;

    private final int mUserId;

    private final RemotePrintSpooler mSpooler;

    private final Handler mHandler;

    private PrinterDiscoverySessionMediator mPrinterDiscoverySession;

    private List<PrintJobStateChangeListenerRecord> mPrintJobStateChangeListenerRecords;

    private List<ListenerRecord<IPrintServicesChangeListener>> mPrintServicesChangeListenerRecords;

    private List<ListenerRecord<IRecommendationsChangeListener>>
            mPrintServiceRecommendationsChangeListenerRecords;

    private boolean mDestroyed;

    /** Currently known list of print service recommendations */
    private List<RecommendationInfo> mPrintServiceRecommendations;

    /**
     * Connection to the service updating the {@link #mPrintServiceRecommendations print service
     * recommendations}.
     */
    private RemotePrintServiceRecommendationService mPrintServiceRecommendationsService;

    public UserState(Context context, int userId, Object lock, boolean lowPriority) {
        mContext = context;
        mUserId = userId;
        mLock = lock;
        mSpooler = new RemotePrintSpooler(context, userId, lowPriority, this);
        mHandler = new UserStateHandler(context.getMainLooper());

        synchronized (mLock) {
            readInstalledPrintServicesLocked();
            upgradePersistentStateIfNeeded();
            readDisabledPrintServicesLocked();
        }

        // Some print services might have gotten installed before the User State came up
        prunePrintServices();

        synchronized (mLock) {
            onConfigurationChangedLocked();
        }
    }

    public void increasePriority() {
        mSpooler.increasePriority();
    }

    @Override
    public void onPrintJobQueued(PrintJobInfo printJob) {
        final RemotePrintService service;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            ComponentName printServiceName = printJob.getPrinterId().getServiceName();
            service = mActiveServices.get(printServiceName);
        }
        if (service != null) {
            service.onPrintJobQueued(printJob);
        } else {
            // The service for the job is no longer enabled, so just
            // fail the job with the appropriate message.
            mSpooler.setPrintJobState(printJob.getId(), PrintJobInfo.STATE_FAILED,
                    mContext.getString(R.string.reason_service_unavailable));
        }
    }

    @Override
    public void onAllPrintJobsForServiceHandled(ComponentName printService) {
        final RemotePrintService service;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            service = mActiveServices.get(printService);
        }
        if (service != null) {
            service.onAllPrintJobsHandled();
        }
    }

    public void removeObsoletePrintJobs() {
        mSpooler.removeObsoletePrintJobs();
    }

    @SuppressWarnings("deprecation")
    public Bundle print(@NonNull String printJobName, @NonNull IPrintDocumentAdapter adapter,
            @Nullable PrintAttributes attributes, @NonNull String packageName, int appId) {
        // Create print job place holder.
        final PrintJobInfo printJob = new PrintJobInfo();
        printJob.setId(new PrintJobId());
        printJob.setAppId(appId);
        printJob.setLabel(printJobName);
        printJob.setAttributes(attributes);
        printJob.setState(PrintJobInfo.STATE_CREATED);
        printJob.setCopies(1);
        printJob.setCreationTime(System.currentTimeMillis());

        // Track this job so we can forget it when the creator dies.
        if (!mPrintJobForAppCache.onPrintJobCreated(adapter.asBinder(), appId,
                printJob)) {
            // Not adding a print job means the client is dead - done.
            return null;
        }

        // Spin the spooler to add the job and show the config UI.
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                mSpooler.createPrintJob(printJob);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[]) null);

        final long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(PrintManager.ACTION_PRINT_DIALOG);
            intent.setData(Uri.fromParts("printjob", printJob.getId().flattenToString(), null));
            intent.putExtra(PrintManager.EXTRA_PRINT_DOCUMENT_ADAPTER, adapter.asBinder());
            intent.putExtra(PrintManager.EXTRA_PRINT_JOB, printJob);
            intent.putExtra(DocumentsContract.EXTRA_PACKAGE_NAME, packageName);

            IntentSender intentSender = PendingIntent.getActivityAsUser(
                    mContext, 0, intent, PendingIntent.FLAG_ONE_SHOT
                    | PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                    null, new UserHandle(mUserId)) .getIntentSender();

            Bundle result = new Bundle();
            result.putParcelable(PrintManager.EXTRA_PRINT_JOB, printJob);
            result.putParcelable(PrintManager.EXTRA_PRINT_DIALOG_INTENT, intentSender);

            return result;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    public List<PrintJobInfo> getPrintJobInfos(int appId) {
        List<PrintJobInfo> cachedPrintJobs = mPrintJobForAppCache.getPrintJobs(appId);
        // Note that the print spooler is not storing print jobs that
        // are in a terminal state as it is non-trivial to properly update
        // the spooler state for when to forget print jobs in terminal state.
        // Therefore, we fuse the cached print jobs for running apps (some
        // jobs are in a terminal state) with the ones that the print
        // spooler knows about (some jobs are being processed).
        ArrayMap<PrintJobId, PrintJobInfo> result =
                new ArrayMap<PrintJobId, PrintJobInfo>();

        // Add the cached print jobs for running apps.
        final int cachedPrintJobCount = cachedPrintJobs.size();
        for (int i = 0; i < cachedPrintJobCount; i++) {
            PrintJobInfo cachedPrintJob = cachedPrintJobs.get(i);
            result.put(cachedPrintJob.getId(), cachedPrintJob);
            // Strip out the tag and the advanced print options.
            // They are visible only to print services.
            cachedPrintJob.setTag(null);
            cachedPrintJob.setAdvancedOptions(null);
        }

        // Add everything else the spooler knows about.
        List<PrintJobInfo> printJobs = mSpooler.getPrintJobInfos(null,
                PrintJobInfo.STATE_ANY, appId);
        if (printJobs != null) {
            final int printJobCount = printJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = printJobs.get(i);
                result.put(printJob.getId(), printJob);
                // Strip out the tag and the advanced print options.
                // They are visible only to print services.
                printJob.setTag(null);
                printJob.setAdvancedOptions(null);
            }
        }

        return new ArrayList<PrintJobInfo>(result.values());
    }

    public PrintJobInfo getPrintJobInfo(@NonNull PrintJobId printJobId, int appId) {
        PrintJobInfo printJob = mPrintJobForAppCache.getPrintJob(printJobId, appId);
        if (printJob == null) {
            printJob = mSpooler.getPrintJobInfo(printJobId, appId);
        }
        if (printJob != null) {
            // Strip out the tag and the advanced print options.
            // They are visible only to print services.
            printJob.setTag(null);
            printJob.setAdvancedOptions(null);
        }
        return printJob;
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
    public @Nullable Icon getCustomPrinterIcon(@NonNull PrinterId printerId) {
        Icon icon = mSpooler.getCustomPrinterIcon(printerId);

        if (icon == null) {
            RemotePrintService service = mActiveServices.get(printerId.getServiceName());
            if (service != null) {
                service.requestCustomPrinterIcon(printerId);
            }
        }

        return icon;
    }

    public void cancelPrintJob(@NonNull PrintJobId printJobId, int appId) {
        PrintJobInfo printJobInfo = mSpooler.getPrintJobInfo(printJobId, appId);
        if (printJobInfo == null) {
            return;
        }

        // Take a note that we are trying to cancel the job.
        mSpooler.setPrintJobCancelling(printJobId, true);

        if (printJobInfo.getState() != PrintJobInfo.STATE_FAILED) {
            PrinterId printerId = printJobInfo.getPrinterId();

            if (printerId != null) {
                ComponentName printServiceName = printerId.getServiceName();
                RemotePrintService printService = null;
                synchronized (mLock) {
                    printService = mActiveServices.get(printServiceName);
                }
                if (printService == null) {
                    return;
                }
                printService.onRequestCancelPrintJob(printJobInfo);
            }
        } else {
            // If the print job is failed we do not need cooperation
            // from the print service.
            mSpooler.setPrintJobState(printJobId, PrintJobInfo.STATE_CANCELED, null);
        }
    }

    public void restartPrintJob(@NonNull PrintJobId printJobId, int appId) {
        PrintJobInfo printJobInfo = getPrintJobInfo(printJobId, appId);
        if (printJobInfo == null || printJobInfo.getState() != PrintJobInfo.STATE_FAILED) {
            return;
        }
        mSpooler.setPrintJobState(printJobId, PrintJobInfo.STATE_QUEUED, null);
    }

    public @Nullable List<PrintServiceInfo> getPrintServices(int selectionFlags) {
        synchronized (mLock) {
            List<PrintServiceInfo> selectedServices = null;
            final int installedServiceCount = mInstalledServices.size();
            for (int i = 0; i < installedServiceCount; i++) {
                PrintServiceInfo installedService = mInstalledServices.get(i);

                ComponentName componentName = new ComponentName(
                        installedService.getResolveInfo().serviceInfo.packageName,
                        installedService.getResolveInfo().serviceInfo.name);

                // Update isEnabled under the same lock the final returned list is created
                installedService.setIsEnabled(mActiveServices.containsKey(componentName));

                if (installedService.isEnabled()) {
                    if ((selectionFlags & PrintManager.ENABLED_SERVICES) == 0) {
                        continue;
                    }
                } else {
                    if ((selectionFlags & PrintManager.DISABLED_SERVICES) == 0) {
                        continue;
                    }
                }

                if (selectedServices == null) {
                    selectedServices = new ArrayList<>();
                }
                selectedServices.add(installedService);
            }
            return selectedServices;
        }
    }

    public void setPrintServiceEnabled(@NonNull ComponentName serviceName, boolean isEnabled) {
        synchronized (mLock) {
            boolean isChanged = false;
            if (isEnabled) {
                isChanged = mDisabledServices.remove(serviceName);
            } else {
                // Make sure to only disable services that are currently installed
                final int numServices = mInstalledServices.size();
                for (int i = 0; i < numServices; i++) {
                    PrintServiceInfo service = mInstalledServices.get(i);

                    if (service.getComponentName().equals(serviceName)) {
                        mDisabledServices.add(serviceName);
                        isChanged = true;
                        break;
                    }
                }
            }

            if (isChanged) {
                writeDisabledPrintServicesLocked(mDisabledServices);

                MetricsLogger.action(mContext, MetricsEvent.ACTION_PRINT_SERVICE_TOGGLE,
                        isEnabled ? 0 : 1);

                onConfigurationChangedLocked();
            }
        }
    }

    /**
     * @return The currently known print service recommendations
     */
    public @Nullable List<RecommendationInfo> getPrintServiceRecommendations() {
        return mPrintServiceRecommendations;
    }

    public void createPrinterDiscoverySession(@NonNull IPrinterDiscoveryObserver observer) {
        mSpooler.clearCustomPrinterIconCache();

        synchronized (mLock) {
            throwIfDestroyedLocked();

            if (mPrinterDiscoverySession == null) {
                // If we do not have a session, tell all service to create one.
                mPrinterDiscoverySession = new PrinterDiscoverySessionMediator(mContext) {
                    @Override
                    public void onDestroyed() {
                        mPrinterDiscoverySession = null;
                    }
                };
                // Add the observer to the brand new session.
                mPrinterDiscoverySession.addObserverLocked(observer);
            } else {
                // If services have created session, just add the observer.
                mPrinterDiscoverySession.addObserverLocked(observer);
            }
        }
    }

    public void destroyPrinterDiscoverySession(@NonNull IPrinterDiscoveryObserver observer) {
        synchronized (mLock) {
            // Already destroyed - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Remove this observer.
            mPrinterDiscoverySession.removeObserverLocked(observer);
        }
    }

    public void startPrinterDiscovery(@NonNull IPrinterDiscoveryObserver observer,
            @Nullable List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();

            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Kick of discovery.
            mPrinterDiscoverySession.startPrinterDiscoveryLocked(observer,
                    printerIds);
        }
    }

    public void stopPrinterDiscovery(@NonNull IPrinterDiscoveryObserver observer) {
        synchronized (mLock) {
            throwIfDestroyedLocked();

            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Kick of discovery.
            mPrinterDiscoverySession.stopPrinterDiscoveryLocked(observer);
        }
    }

    public void validatePrinters(@NonNull List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request an updated.
            mPrinterDiscoverySession.validatePrintersLocked(printerIds);
        }
    }

    public void startPrinterStateTracking(@NonNull PrinterId printerId) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request start tracking the printer.
            mPrinterDiscoverySession.startPrinterStateTrackingLocked(printerId);
        }
    }

    public void stopPrinterStateTracking(PrinterId printerId) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request stop tracking the printer.
            mPrinterDiscoverySession.stopPrinterStateTrackingLocked(printerId);
        }
    }

    public void addPrintJobStateChangeListener(@NonNull IPrintJobStateChangeListener listener,
            int appId) throws RemoteException {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintJobStateChangeListenerRecords == null) {
                mPrintJobStateChangeListenerRecords =
                        new ArrayList<PrintJobStateChangeListenerRecord>();
            }
            mPrintJobStateChangeListenerRecords.add(
                    new PrintJobStateChangeListenerRecord(listener, appId) {
                @Override
                public void onBinderDied() {
                    synchronized (mLock) {
                        if (mPrintJobStateChangeListenerRecords != null) {
                            mPrintJobStateChangeListenerRecords.remove(this);
                        }
                    }
                }
            });
        }
    }

    public void removePrintJobStateChangeListener(@NonNull IPrintJobStateChangeListener listener) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintJobStateChangeListenerRecords == null) {
                return;
            }
            final int recordCount = mPrintJobStateChangeListenerRecords.size();
            for (int i = 0; i < recordCount; i++) {
                PrintJobStateChangeListenerRecord record =
                        mPrintJobStateChangeListenerRecords.get(i);
                if (record.listener.asBinder().equals(listener.asBinder())) {
                    mPrintJobStateChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (mPrintJobStateChangeListenerRecords.isEmpty()) {
                mPrintJobStateChangeListenerRecords = null;
            }
        }
    }

    public void addPrintServicesChangeListener(@NonNull IPrintServicesChangeListener listener)
            throws RemoteException {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintServicesChangeListenerRecords == null) {
                mPrintServicesChangeListenerRecords = new ArrayList<>();
            }
            mPrintServicesChangeListenerRecords.add(
                    new ListenerRecord<IPrintServicesChangeListener>(listener) {
                        @Override
                        public void onBinderDied() {
                            synchronized (mLock) {
                                if (mPrintServicesChangeListenerRecords != null) {
                                    mPrintServicesChangeListenerRecords.remove(this);
                                }
                            }
                        }
                    });
        }
    }

    public void removePrintServicesChangeListener(@NonNull IPrintServicesChangeListener listener) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintServicesChangeListenerRecords == null) {
                return;
            }
            final int recordCount = mPrintServicesChangeListenerRecords.size();
            for (int i = 0; i < recordCount; i++) {
                ListenerRecord<IPrintServicesChangeListener> record =
                        mPrintServicesChangeListenerRecords.get(i);
                if (record.listener.asBinder().equals(listener.asBinder())) {
                    mPrintServicesChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (mPrintServicesChangeListenerRecords.isEmpty()) {
                mPrintServicesChangeListenerRecords = null;
            }
        }
    }

    public void addPrintServiceRecommendationsChangeListener(
            @NonNull IRecommendationsChangeListener listener) throws RemoteException {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintServiceRecommendationsChangeListenerRecords == null) {
                mPrintServiceRecommendationsChangeListenerRecords = new ArrayList<>();

                mPrintServiceRecommendationsService =
                        new RemotePrintServiceRecommendationService(mContext,
                                UserHandle.getUserHandleForUid(mUserId), this);
            }
            mPrintServiceRecommendationsChangeListenerRecords.add(
                    new ListenerRecord<IRecommendationsChangeListener>(listener) {
                        @Override
                        public void onBinderDied() {
                            synchronized (mLock) {
                                if (mPrintServiceRecommendationsChangeListenerRecords != null) {
                                    mPrintServiceRecommendationsChangeListenerRecords.remove(this);
                                }
                            }
                        }
                    });
        }
    }

    public void removePrintServiceRecommendationsChangeListener(
            @NonNull IRecommendationsChangeListener listener) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mPrintServiceRecommendationsChangeListenerRecords == null) {
                return;
            }
            final int recordCount = mPrintServiceRecommendationsChangeListenerRecords.size();
            for (int i = 0; i < recordCount; i++) {
                ListenerRecord<IRecommendationsChangeListener> record =
                        mPrintServiceRecommendationsChangeListenerRecords.get(i);
                if (record.listener.asBinder().equals(listener.asBinder())) {
                    mPrintServiceRecommendationsChangeListenerRecords.remove(i);
                    break;
                }
            }
            if (mPrintServiceRecommendationsChangeListenerRecords.isEmpty()) {
                mPrintServiceRecommendationsChangeListenerRecords = null;

                mPrintServiceRecommendations = null;

                mPrintServiceRecommendationsService.close();
                mPrintServiceRecommendationsService = null;
            }
        }
    }

    @Override
    public void onPrintJobStateChanged(PrintJobInfo printJob) {
        mPrintJobForAppCache.onPrintJobStateChanged(printJob);
        mHandler.obtainMessage(UserStateHandler.MSG_DISPATCH_PRINT_JOB_STATE_CHANGED,
                printJob.getAppId(), 0, printJob.getId()).sendToTarget();
    }

    public void onPrintServicesChanged() {
        mHandler.obtainMessage(UserStateHandler.MSG_DISPATCH_PRINT_SERVICES_CHANGED).sendToTarget();
    }

    @Override
    public void onPrintServiceRecommendationsUpdated(List<RecommendationInfo> recommendations) {
        mHandler.obtainMessage(UserStateHandler.MSG_DISPATCH_PRINT_SERVICES_RECOMMENDATIONS_UPDATED,
                0, 0, recommendations).sendToTarget();
    }

    @Override
    public void onPrintersAdded(List<PrinterInfo> printers) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            mPrinterDiscoverySession.onPrintersAddedLocked(printers);
        }
    }

    @Override
    public void onPrintersRemoved(List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            mPrinterDiscoverySession.onPrintersRemovedLocked(printerIds);
        }
    }

    @Override
    public void onCustomPrinterIconLoaded(PrinterId printerId, Icon icon) {
        mSpooler.onCustomPrinterIconLoaded(printerId, icon);

        synchronized (mLock) {
            throwIfDestroyedLocked();

            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            mPrinterDiscoverySession.onCustomPrinterIconLoadedLocked(printerId);
        }
    }

    @Override
    public void onServiceDied(RemotePrintService service) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // Fail all print jobs.
            failActivePrintJobsForService(service.getComponentName());
            service.onAllPrintJobsHandled();

            mActiveServices.remove(service.getComponentName());

            // The service might need to be restarted if it died because of an update
            mHandler.sendMessageDelayed(
                    mHandler.obtainMessage(UserStateHandler.MSG_CHECK_CONFIG_CHANGED),
                    SERVICE_RESTART_DELAY_MILLIS);

            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            mPrinterDiscoverySession.onServiceDiedLocked(service);
        }
    }

    public void updateIfNeededLocked() {
        throwIfDestroyedLocked();
        readConfigurationLocked();
        onConfigurationChangedLocked();
    }

    public void destroyLocked() {
        throwIfDestroyedLocked();
        mSpooler.destroy();
        for (RemotePrintService service : mActiveServices.values()) {
            service.destroy();
        }
        mActiveServices.clear();
        mInstalledServices.clear();
        mDisabledServices.clear();
        if (mPrinterDiscoverySession != null) {
            mPrinterDiscoverySession.destroyLocked();
            mPrinterDiscoverySession = null;
        }
        mDestroyed = true;
    }

    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw, @NonNull String prefix) {
        pw.append(prefix).append("user state ").append(String.valueOf(mUserId)).append(":");
        pw.println();

        String tab = "  ";

        pw.append(prefix).append(tab).append("installed services:").println();
        final int installedServiceCount = mInstalledServices.size();
        for (int i = 0; i < installedServiceCount; i++) {
            PrintServiceInfo installedService = mInstalledServices.get(i);
            String installedServicePrefix = prefix + tab + tab;
            pw.append(installedServicePrefix).append("service:").println();
            ResolveInfo resolveInfo = installedService.getResolveInfo();
            ComponentName componentName = new ComponentName(
                    resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            pw.append(installedServicePrefix).append(tab).append("componentName=")
                    .append(componentName.flattenToString()).println();
            pw.append(installedServicePrefix).append(tab).append("settingsActivity=")
                    .append(installedService.getSettingsActivityName()).println();
            pw.append(installedServicePrefix).append(tab).append("addPrintersActivity=")
                    .append(installedService.getAddPrintersActivityName()).println();
            pw.append(installedServicePrefix).append(tab).append("avancedOptionsActivity=")
                   .append(installedService.getAdvancedOptionsActivityName()).println();
        }

        pw.append(prefix).append(tab).append("disabled services:").println();
        for (ComponentName disabledService : mDisabledServices) {
            String disabledServicePrefix = prefix + tab + tab;
            pw.append(disabledServicePrefix).append("service:").println();
            pw.append(disabledServicePrefix).append(tab).append("componentName=")
                    .append(disabledService.flattenToString());
            pw.println();
        }

        pw.append(prefix).append(tab).append("active services:").println();
        final int activeServiceCount = mActiveServices.size();
        for (int i = 0; i < activeServiceCount; i++) {
            RemotePrintService activeService = mActiveServices.valueAt(i);
            activeService.dump(pw, prefix + tab + tab);
            pw.println();
        }

        pw.append(prefix).append(tab).append("cached print jobs:").println();
        mPrintJobForAppCache.dump(pw, prefix + tab + tab);

        pw.append(prefix).append(tab).append("discovery mediator:").println();
        if (mPrinterDiscoverySession != null) {
            mPrinterDiscoverySession.dump(pw, prefix + tab + tab);
        }

        pw.append(prefix).append(tab).append("print spooler:").println();
        mSpooler.dump(fd, pw, prefix + tab + tab);
        pw.println();
    }

    private void readConfigurationLocked() {
        readInstalledPrintServicesLocked();
        readDisabledPrintServicesLocked();
    }

    private void readInstalledPrintServicesLocked() {
        Set<PrintServiceInfo> tempPrintServices = new HashSet<PrintServiceInfo>();

        List<ResolveInfo> installedServices = mContext.getPackageManager()
                .queryIntentServicesAsUser(mQueryIntent,
                        GET_SERVICES | GET_META_DATA | MATCH_DEBUG_TRIAGED_MISSING, mUserId);

        final int installedCount = installedServices.size();
        for (int i = 0, count = installedCount; i < count; i++) {
            ResolveInfo installedService = installedServices.get(i);
            if (!android.Manifest.permission.BIND_PRINT_SERVICE.equals(
                    installedService.serviceInfo.permission)) {
                ComponentName serviceName = new ComponentName(
                        installedService.serviceInfo.packageName,
                        installedService.serviceInfo.name);
                Slog.w(LOG_TAG, "Skipping print service "
                        + serviceName.flattenToShortString()
                        + " since it does not require permission "
                        + android.Manifest.permission.BIND_PRINT_SERVICE);
                continue;
            }
            tempPrintServices.add(PrintServiceInfo.create(mContext, installedService));
        }

        mInstalledServices.clear();
        mInstalledServices.addAll(tempPrintServices);
    }

    /**
     * Update persistent state from a previous version of Android.
     */
    private void upgradePersistentStateIfNeeded() {
        String enabledSettingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ENABLED_PRINT_SERVICES, mUserId);

        // Pre N we store the enabled services, in N and later we store the disabled services.
        // Hence if enabledSettingValue is still set, we need to upgrade.
        if (enabledSettingValue != null) {
            Set<ComponentName> enabledServiceNameSet = new HashSet<ComponentName>();
            readPrintServicesFromSettingLocked(Settings.Secure.ENABLED_PRINT_SERVICES,
                    enabledServiceNameSet);

            ArraySet<ComponentName> disabledServices = new ArraySet<>();
            final int numInstalledServices = mInstalledServices.size();
            for (int i = 0; i < numInstalledServices; i++) {
                ComponentName serviceName = mInstalledServices.get(i).getComponentName();
                if (!enabledServiceNameSet.contains(serviceName)) {
                    disabledServices.add(serviceName);
                }
            }

            writeDisabledPrintServicesLocked(disabledServices);

            // We won't needed ENABLED_PRINT_SERVICES anymore, set to null to prevent upgrade to run
            // again.
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_PRINT_SERVICES, null, mUserId);
        }
    }

    /**
     * Read the set of disabled print services from the secure settings.
     *
     * @return true if the state changed.
     */
    private void readDisabledPrintServicesLocked() {
        Set<ComponentName> tempDisabledServiceNameSet = new HashSet<ComponentName>();
        readPrintServicesFromSettingLocked(Settings.Secure.DISABLED_PRINT_SERVICES,
                tempDisabledServiceNameSet);
        if (!tempDisabledServiceNameSet.equals(mDisabledServices)) {
            mDisabledServices.clear();
            mDisabledServices.addAll(tempDisabledServiceNameSet);
        }
    }

    private void readPrintServicesFromSettingLocked(String setting,
            Set<ComponentName> outServiceNames) {
        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                setting, mUserId);
        if (!TextUtils.isEmpty(settingValue)) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String string = splitter.next();
                if (TextUtils.isEmpty(string)) {
                    continue;
                }
                ComponentName componentName = ComponentName.unflattenFromString(string);
                if (componentName != null) {
                    outServiceNames.add(componentName);
                }
            }
        }
    }

    /**
     * Persist the disabled print services to the secure settings.
     */
    private void writeDisabledPrintServicesLocked(Set<ComponentName> disabledServices) {
        StringBuilder builder = new StringBuilder();
        for (ComponentName componentName : disabledServices) {
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(componentName.flattenToShortString());
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.DISABLED_PRINT_SERVICES, builder.toString(), mUserId);
    }

    /**
     * Get the {@link ComponentName names} of the installed print services
     *
     * @return The names of the installed print services
     */
    private ArrayList<ComponentName> getInstalledComponents() {
        ArrayList<ComponentName> installedComponents = new ArrayList<ComponentName>();

        final int installedCount = mInstalledServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo resolveInfo = mInstalledServices.get(i).getResolveInfo();
            ComponentName serviceName = new ComponentName(resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);

            installedComponents.add(serviceName);
        }

        return installedComponents;
    }

    /**
     * Prune persistent state if a print service was uninstalled
     */
    public void prunePrintServices() {
        ArrayList<ComponentName> installedComponents;

        synchronized (mLock) {
            installedComponents = getInstalledComponents();

            // Remove unnecessary entries from persistent state "disabled services"
            boolean disabledServicesUninstalled = mDisabledServices.retainAll(installedComponents);
            if (disabledServicesUninstalled) {
                writeDisabledPrintServicesLocked(mDisabledServices);
            }
        }

        // Remove unnecessary entries from persistent state "approved services"
        mSpooler.pruneApprovedPrintServices(installedComponents);

    }

    private void onConfigurationChangedLocked() {
        ArrayList<ComponentName> installedComponents = getInstalledComponents();

        final int installedCount = installedComponents.size();
        for (int i = 0; i < installedCount; i++) {
            ComponentName serviceName = installedComponents.get(i);

            if (!mDisabledServices.contains(serviceName)) {
                if (!mActiveServices.containsKey(serviceName)) {
                    RemotePrintService service = new RemotePrintService(
                            mContext, serviceName, mUserId, mSpooler, this);
                    addServiceLocked(service);
                }
            } else {
                RemotePrintService service = mActiveServices.remove(serviceName);
                if (service != null) {
                    removeServiceLocked(service);
                }
            }
        }

        Iterator<Map.Entry<ComponentName, RemotePrintService>> iterator =
                mActiveServices.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ComponentName, RemotePrintService> entry = iterator.next();
            ComponentName serviceName = entry.getKey();
            RemotePrintService service = entry.getValue();
            if (!installedComponents.contains(serviceName)) {
                removeServiceLocked(service);
                iterator.remove();
            }
        }

        onPrintServicesChanged();
    }

    private void addServiceLocked(RemotePrintService service) {
        mActiveServices.put(service.getComponentName(), service);
        if (mPrinterDiscoverySession != null) {
            mPrinterDiscoverySession.onServiceAddedLocked(service);
        }
    }

    private void removeServiceLocked(RemotePrintService service) {
        // Fail all print jobs.
        failActivePrintJobsForService(service.getComponentName());
        // If discovery is in progress, tear down the service.
        if (mPrinterDiscoverySession != null) {
            mPrinterDiscoverySession.onServiceRemovedLocked(service);
        } else {
            // Otherwise, just destroy it.
            service.destroy();
        }
    }

    private void failActivePrintJobsForService(final ComponentName serviceName) {
        // Makes sure all active print jobs are failed since the service
        // just died. Do this off the main thread since we do to allow
        // calls into the spooler on the main thread.
        if (Looper.getMainLooper().isCurrentThread()) {
            BackgroundThread.getHandler().post(new Runnable() {
                @Override
                public void run() {
                    failScheduledPrintJobsForServiceInternal(serviceName);
                }
            });
        } else {
            failScheduledPrintJobsForServiceInternal(serviceName);
        }
    }

    private void failScheduledPrintJobsForServiceInternal(ComponentName serviceName) {
        List<PrintJobInfo> printJobs = mSpooler.getPrintJobInfos(serviceName,
                PrintJobInfo.STATE_ANY_SCHEDULED, PrintManager.APP_ID_ANY);
        if (printJobs == null) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            final int printJobCount = printJobs.size();
            for (int i = 0; i < printJobCount; i++) {
                PrintJobInfo printJob = printJobs.get(i);
                mSpooler.setPrintJobState(printJob.getId(), PrintJobInfo.STATE_FAILED,
                        mContext.getString(R.string.reason_service_unavailable));
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void throwIfDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed instance.");
        }
    }

    private void handleDispatchPrintJobStateChanged(PrintJobId printJobId, int appId) {
        final List<PrintJobStateChangeListenerRecord> records;
        synchronized (mLock) {
            if (mPrintJobStateChangeListenerRecords == null) {
                return;
            }
            records = new ArrayList<PrintJobStateChangeListenerRecord>(
                    mPrintJobStateChangeListenerRecords);
        }
        final int recordCount = records.size();
        for (int i = 0; i < recordCount; i++) {
            PrintJobStateChangeListenerRecord record = records.get(i);
            if (record.appId == PrintManager.APP_ID_ANY
                    || record.appId == appId)
            try {
                record.listener.onPrintJobStateChanged(printJobId);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error notifying for print job state change", re);
            }
        }
    }

    private void handleDispatchPrintServicesChanged() {
        final List<ListenerRecord<IPrintServicesChangeListener>> records;
        synchronized (mLock) {
            if (mPrintServicesChangeListenerRecords == null) {
                return;
            }
            records = new ArrayList<>(mPrintServicesChangeListenerRecords);
        }
        final int recordCount = records.size();
        for (int i = 0; i < recordCount; i++) {
            ListenerRecord<IPrintServicesChangeListener> record = records.get(i);

            try {
                record.listener.onPrintServicesChanged();;
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error notifying for print services change", re);
            }
        }
    }

    private void handleDispatchPrintServiceRecommendationsUpdated(
            @Nullable List<RecommendationInfo> recommendations) {
        final List<ListenerRecord<IRecommendationsChangeListener>> records;
        synchronized (mLock) {
            if (mPrintServiceRecommendationsChangeListenerRecords == null) {
                return;
            }
            records = new ArrayList<>(mPrintServiceRecommendationsChangeListenerRecords);

            mPrintServiceRecommendations = recommendations;
        }
        final int recordCount = records.size();
        for (int i = 0; i < recordCount; i++) {
            ListenerRecord<IRecommendationsChangeListener> record = records.get(i);

            try {
                record.listener.onRecommendationsChanged();
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error notifying for print service recommendations change", re);
            }
        }
    }

    private final class UserStateHandler extends Handler {
        public static final int MSG_DISPATCH_PRINT_JOB_STATE_CHANGED = 1;
        public static final int MSG_DISPATCH_PRINT_SERVICES_CHANGED = 2;
        public static final int MSG_DISPATCH_PRINT_SERVICES_RECOMMENDATIONS_UPDATED = 3;
        public static final int MSG_CHECK_CONFIG_CHANGED = 4;

        public UserStateHandler(Looper looper) {
            super(looper, null, false);
        }

        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_DISPATCH_PRINT_JOB_STATE_CHANGED:
                    PrintJobId printJobId = (PrintJobId) message.obj;
                    final int appId = message.arg1;
                    handleDispatchPrintJobStateChanged(printJobId, appId);
                    break;
                case MSG_DISPATCH_PRINT_SERVICES_CHANGED:
                    handleDispatchPrintServicesChanged();
                    break;
                case MSG_DISPATCH_PRINT_SERVICES_RECOMMENDATIONS_UPDATED:
                    handleDispatchPrintServiceRecommendationsUpdated(
                            (List<RecommendationInfo>) message.obj);
                    break;
                case MSG_CHECK_CONFIG_CHANGED:
                    synchronized (mLock) {
                        onConfigurationChangedLocked();
                    }
                default:
                    // not reached
            }
        }
    }

    private abstract class PrintJobStateChangeListenerRecord implements DeathRecipient {
        @NonNull final IPrintJobStateChangeListener listener;
        final int appId;

        public PrintJobStateChangeListenerRecord(@NonNull IPrintJobStateChangeListener listener,
                int appId) throws RemoteException {
            this.listener = listener;
            this.appId = appId;
            listener.asBinder().linkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            listener.asBinder().unlinkToDeath(this, 0);
            onBinderDied();
        }

        public abstract void onBinderDied();
    }

    private abstract class ListenerRecord<T extends IInterface> implements DeathRecipient {
        @NonNull final T listener;

        public ListenerRecord(@NonNull T listener) throws RemoteException {
            this.listener = listener;
            listener.asBinder().linkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            listener.asBinder().unlinkToDeath(this, 0);
            onBinderDied();
        }

        public abstract void onBinderDied();
    }

    private class PrinterDiscoverySessionMediator {
        private final ArrayMap<PrinterId, PrinterInfo> mPrinters =
                new ArrayMap<PrinterId, PrinterInfo>();

        private final RemoteCallbackList<IPrinterDiscoveryObserver> mDiscoveryObservers =
                new RemoteCallbackList<IPrinterDiscoveryObserver>() {
            @Override
            public void onCallbackDied(IPrinterDiscoveryObserver observer) {
                synchronized (mLock) {
                    stopPrinterDiscoveryLocked(observer);
                    removeObserverLocked(observer);
                }
            }
        };

        private final List<IBinder> mStartedPrinterDiscoveryTokens = new ArrayList<IBinder>();

        private final List<PrinterId> mStateTrackedPrinters = new ArrayList<PrinterId>();

        private final Handler mSessionHandler;

        private boolean mIsDestroyed;

        public PrinterDiscoverySessionMediator(Context context) {
            mSessionHandler = new SessionHandler(context.getMainLooper());
            // Kick off the session creation.
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION, services)
                    .sendToTarget();
        }

        public void addObserverLocked(@NonNull IPrinterDiscoveryObserver observer) {
            // Add the observer.
            mDiscoveryObservers.register(observer);

            // Bring the added observer up to speed with the printers.
            if (!mPrinters.isEmpty()) {
                List<PrinterInfo> printers = new ArrayList<PrinterInfo>(mPrinters.values());
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = observer;
                args.arg2 = printers;
                mSessionHandler.obtainMessage(SessionHandler.MSG_PRINTERS_ADDED,
                        args).sendToTarget();
            }
        }

        public void removeObserverLocked(@NonNull IPrinterDiscoveryObserver observer) {
            // Remove the observer.
            mDiscoveryObservers.unregister(observer);
            // No one else observing - then kill it.
            if (mDiscoveryObservers.getRegisteredCallbackCount() == 0) {
                destroyLocked();
            }
        }

        public final void startPrinterDiscoveryLocked(@NonNull IPrinterDiscoveryObserver observer,
                @Nullable List<PrinterId> priorityList) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not starting dicovery - session destroyed");
                return;
            }

            final boolean discoveryStarted = !mStartedPrinterDiscoveryTokens.isEmpty();

            // Remember we got a start request to match with an end.
            mStartedPrinterDiscoveryTokens.add(observer.asBinder());

            // If printer discovery is ongoing and the start request has a list
            // of printer to be checked, then we just request validating them.
            if (discoveryStarted && priorityList != null && !priorityList.isEmpty()) {
                validatePrinters(priorityList);
                return;
            }

            // The service are already performing discovery - nothing to do.
            if (mStartedPrinterDiscoveryTokens.size() > 1) {
                return;
            }

            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = services;
            args.arg2 = priorityList;
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_START_PRINTER_DISCOVERY, args)
                    .sendToTarget();
        }

        public final void stopPrinterDiscoveryLocked(@NonNull IPrinterDiscoveryObserver observer) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not stopping dicovery - session destroyed");
                return;
            }
            // This one did not make an active discovery request - nothing to do.
            if (!mStartedPrinterDiscoveryTokens.remove(observer.asBinder())) {
                return;
            }
            // There are other interested observers - do not stop discovery.
            if (!mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_STOP_PRINTER_DISCOVERY, services)
                    .sendToTarget();
        }

        public void validatePrintersLocked(@NonNull List<PrinterId> printerIds) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not validating pritners - session destroyed");
                return;
            }

            List<PrinterId> remainingList = new ArrayList<PrinterId>(printerIds);
            while (!remainingList.isEmpty()) {
                Iterator<PrinterId> iterator = remainingList.iterator();
                // Gather the printers per service and request a validation.
                List<PrinterId> updateList = new ArrayList<PrinterId>();
                ComponentName serviceName = null;
                while (iterator.hasNext()) {
                    PrinterId printerId = iterator.next();
                    if (printerId != null) {
                        if (updateList.isEmpty()) {
                            updateList.add(printerId);
                            serviceName = printerId.getServiceName();
                            iterator.remove();
                        } else if (printerId.getServiceName().equals(serviceName)) {
                            updateList.add(printerId);
                            iterator.remove();
                        }
                    }
                }
                // Schedule a notification of the service.
                RemotePrintService service = mActiveServices.get(serviceName);
                if (service != null) {
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = service;
                    args.arg2 = updateList;
                    mSessionHandler.obtainMessage(SessionHandler
                            .MSG_VALIDATE_PRINTERS, args)
                            .sendToTarget();
                }
            }
        }

        public final void startPrinterStateTrackingLocked(@NonNull PrinterId printerId) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not starting printer state tracking - session destroyed");
                return;
            }
            // If printer discovery is not started - nothing to do.
            if (mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            final boolean containedPrinterId = mStateTrackedPrinters.contains(printerId);
            // Keep track of the number of requests to track this one.
            mStateTrackedPrinters.add(printerId);
            // If we were tracking this printer - nothing to do.
            if (containedPrinterId) {
                return;
            }
            // No service - nothing to do.
            RemotePrintService service = mActiveServices.get(printerId.getServiceName());
            if (service == null) {
                return;
            }
            // Ask the service to start tracking.
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = service;
            args.arg2 = printerId;
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_START_PRINTER_STATE_TRACKING, args)
                    .sendToTarget();
        }

        public final void stopPrinterStateTrackingLocked(PrinterId printerId) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not stopping printer state tracking - session destroyed");
                return;
            }
            // If printer discovery is not started - nothing to do.
            if (mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            // If we did not track this printer - nothing to do.
            if (!mStateTrackedPrinters.remove(printerId)) {
                return;
            }
            // No service - nothing to do.
            RemotePrintService service = mActiveServices.get(printerId.getServiceName());
            if (service == null) {
                return;
            }
            // Ask the service to start tracking.
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = service;
            args.arg2 = printerId;
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_STOP_PRINTER_STATE_TRACKING, args)
                    .sendToTarget();
        }

        public void onDestroyed() {
            /* do nothing */
        }

        public void destroyLocked() {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not destroying - session destroyed");
                return;
            }
            mIsDestroyed = true;
            // Make sure printer tracking is stopped.
            final int printerCount = mStateTrackedPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterId printerId = mStateTrackedPrinters.get(i);
                stopPrinterStateTracking(printerId);
            }
            // Make sure discovery is stopped.
            final int observerCount = mStartedPrinterDiscoveryTokens.size();
            for (int i = 0; i < observerCount; i++) {
                IBinder token = mStartedPrinterDiscoveryTokens.get(i);
                stopPrinterDiscoveryLocked(IPrinterDiscoveryObserver.Stub.asInterface(token));
            }
            // Tell the services we are done.
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mSessionHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION, services)
                    .sendToTarget();
        }

        public void onPrintersAddedLocked(List<PrinterInfo> printers) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onPrintersAddedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not adding printers - session destroyed");
                return;
            }
            List<PrinterInfo> addedPrinters = null;
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo printer = printers.get(i);
                PrinterInfo oldPrinter = mPrinters.put(printer.getId(), printer);
                if (oldPrinter == null || !oldPrinter.equals(printer)) {
                    if (addedPrinters == null) {
                        addedPrinters = new ArrayList<PrinterInfo>();
                    }
                    addedPrinters.add(printer);
                }
            }
            if (addedPrinters != null) {
                mSessionHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_ADDED,
                        addedPrinters).sendToTarget();
            }
        }

        public void onPrintersRemovedLocked(List<PrinterId> printerIds) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onPrintersRemovedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not removing printers - session destroyed");
                return;
            }
            List<PrinterId> removedPrinterIds = null;
            final int removedPrinterCount = printerIds.size();
            for (int i = 0; i < removedPrinterCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                if (mPrinters.remove(removedPrinterId) != null) {
                    if (removedPrinterIds == null) {
                        removedPrinterIds = new ArrayList<PrinterId>();
                    }
                    removedPrinterIds.add(removedPrinterId);
                }
            }
            if (removedPrinterIds != null) {
                mSessionHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_REMOVED,
                        removedPrinterIds).sendToTarget();
            }
        }

        public void onServiceRemovedLocked(RemotePrintService service) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating removed service - session destroyed");
                return;
            }
            // Remove the reported and tracked printers for that service.
            ComponentName serviceName = service.getComponentName();
            removePrintersForServiceLocked(serviceName);
            service.destroy();
        }

        /**
         * Handle that a custom icon for a printer was loaded.
         *
         * This increments the icon generation and adds the printer again which triggers an update
         * in all users of the currently known printers.
         *
         * @param printerId the id of the printer the icon belongs to
         * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon
         */
        public void onCustomPrinterIconLoadedLocked(PrinterId printerId) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onCustomPrinterIconLoadedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating printer - session destroyed");
                return;
            }

            PrinterInfo printer = mPrinters.get(printerId);
            if (printer != null) {
                PrinterInfo newPrinter = (new PrinterInfo.Builder(printer))
                        .incCustomPrinterIconGen().build();
                mPrinters.put(printerId, newPrinter);

                ArrayList<PrinterInfo> addedPrinters = new ArrayList<>(1);
                addedPrinters.add(newPrinter);
                mSessionHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_ADDED,
                        addedPrinters).sendToTarget();
            }
        }

        public void onServiceDiedLocked(RemotePrintService service) {
            removeServiceLocked(service);
        }

        public void onServiceAddedLocked(RemotePrintService service) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating added service - session destroyed");
                return;
            }
            // Tell the service to create a session.
            mSessionHandler.obtainMessage(
                    SessionHandler.MSG_CREATE_PRINTER_DISCOVERY_SESSION,
                    service).sendToTarget();
            // Start printer discovery if necessary.
            if (!mStartedPrinterDiscoveryTokens.isEmpty()) {
                mSessionHandler.obtainMessage(
                        SessionHandler.MSG_START_PRINTER_DISCOVERY,
                        service).sendToTarget();
            }
            // Start tracking printers if necessary
            final int trackedPrinterCount = mStateTrackedPrinters.size();
            for (int i = 0; i < trackedPrinterCount; i++) {
                PrinterId printerId = mStateTrackedPrinters.get(i);
                if (printerId.getServiceName().equals(service.getComponentName())) {
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = service;
                    args.arg2 = printerId;
                    mSessionHandler.obtainMessage(SessionHandler
                            .MSG_START_PRINTER_STATE_TRACKING, args)
                            .sendToTarget();
                }
            }
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.append(prefix).append("destroyed=")
                    .append(String.valueOf(mDestroyed)).println();

            pw.append(prefix).append("printDiscoveryInProgress=")
                    .append(String.valueOf(!mStartedPrinterDiscoveryTokens.isEmpty())).println();

            String tab = "  ";

            pw.append(prefix).append(tab).append("printer discovery observers:").println();
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                pw.append(prefix).append(prefix).append(observer.toString());
                pw.println();
            }
            mDiscoveryObservers.finishBroadcast();

            pw.append(prefix).append(tab).append("start discovery requests:").println();
            final int tokenCount = this.mStartedPrinterDiscoveryTokens.size();
            for (int i = 0; i < tokenCount; i++) {
                IBinder token = mStartedPrinterDiscoveryTokens.get(i);
                pw.append(prefix).append(tab).append(tab).append(token.toString()).println();
            }

            pw.append(prefix).append(tab).append("tracked printer requests:").println();
            final int trackedPrinters = mStateTrackedPrinters.size();
            for (int i = 0; i < trackedPrinters; i++) {
                PrinterId printer = mStateTrackedPrinters.get(i);
                pw.append(prefix).append(tab).append(tab).append(printer.toString()).println();
            }

            pw.append(prefix).append(tab).append("printers:").println();
            final int pritnerCount = mPrinters.size();
            for (int i = 0; i < pritnerCount; i++) {
                PrinterInfo printer = mPrinters.valueAt(i);
                pw.append(prefix).append(tab).append(tab).append(
                        printer.toString()).println();
            }
        }

        private void removePrintersForServiceLocked(ComponentName serviceName) {
            // No printers - nothing to do.
            if (mPrinters.isEmpty()) {
                return;
            }
            // Remove the printers for that service.
            List<PrinterId> removedPrinterIds = null;
            final int printerCount = mPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterId printerId = mPrinters.keyAt(i);
                if (printerId.getServiceName().equals(serviceName)) {
                    if (removedPrinterIds == null) {
                        removedPrinterIds = new ArrayList<PrinterId>();
                    }
                    removedPrinterIds.add(printerId);
                }
            }
            if (removedPrinterIds != null) {
                final int removedPrinterCount = removedPrinterIds.size();
                for (int i = 0; i < removedPrinterCount; i++) {
                    mPrinters.remove(removedPrinterIds.get(i));
                }
                mSessionHandler.obtainMessage(
                        SessionHandler.MSG_DISPATCH_PRINTERS_REMOVED,
                        removedPrinterIds).sendToTarget();
            }
        }

        private void handleDispatchPrintersAdded(List<PrinterInfo> addedPrinters) {
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                handlePrintersAdded(observer, addedPrinters);
            }
            mDiscoveryObservers.finishBroadcast();
        }

        private void handleDispatchPrintersRemoved(List<PrinterId> removedPrinterIds) {
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                handlePrintersRemoved(observer, removedPrinterIds);
            }
            mDiscoveryObservers.finishBroadcast();
        }

        private void handleDispatchCreatePrinterDiscoverySession(
                List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.createPrinterDiscoverySession();
            }
        }

        private void handleDispatchDestroyPrinterDiscoverySession(
                List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.destroyPrinterDiscoverySession();
            }
            onDestroyed();
        }

        private void handleDispatchStartPrinterDiscovery(
                List<RemotePrintService> services, List<PrinterId> printerIds) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.startPrinterDiscovery(printerIds);
            }
        }

        private void handleDispatchStopPrinterDiscovery(List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.stopPrinterDiscovery();
            }
        }

        private void handleValidatePrinters(RemotePrintService service,
                List<PrinterId> printerIds) {
            service.validatePrinters(printerIds);
        }

        private void handleStartPrinterStateTracking(@NonNull RemotePrintService service,
                @NonNull PrinterId printerId) {
            service.startPrinterStateTracking(printerId);
        }

        private void handleStopPrinterStateTracking(RemotePrintService service,
                PrinterId printerId) {
            service.stopPrinterStateTracking(printerId);
        }

        private void handlePrintersAdded(IPrinterDiscoveryObserver observer,
            List<PrinterInfo> printers) {
            try {
                observer.onPrintersAdded(new ParceledListSlice<PrinterInfo>(printers));
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending added printers", re);
            }
        }

        private void handlePrintersRemoved(IPrinterDiscoveryObserver observer,
            List<PrinterId> printerIds) {
            try {
                observer.onPrintersRemoved(new ParceledListSlice<PrinterId>(printerIds));
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending removed printers", re);
            }
        }

        private final class SessionHandler extends Handler {
            public static final int MSG_PRINTERS_ADDED = 1;
            public static final int MSG_PRINTERS_REMOVED = 2;
            public static final int MSG_DISPATCH_PRINTERS_ADDED = 3;
            public static final int MSG_DISPATCH_PRINTERS_REMOVED = 4;

            public static final int MSG_CREATE_PRINTER_DISCOVERY_SESSION = 5;
            public static final int MSG_DESTROY_PRINTER_DISCOVERY_SESSION = 6;
            public static final int MSG_START_PRINTER_DISCOVERY = 7;
            public static final int MSG_STOP_PRINTER_DISCOVERY = 8;
            public static final int MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION = 9;
            public static final int MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION = 10;
            public static final int MSG_DISPATCH_START_PRINTER_DISCOVERY = 11;
            public static final int MSG_DISPATCH_STOP_PRINTER_DISCOVERY = 12;
            public static final int MSG_VALIDATE_PRINTERS = 13;
            public static final int MSG_START_PRINTER_STATE_TRACKING = 14;
            public static final int MSG_STOP_PRINTER_STATE_TRACKING = 15;
            public static final int MSG_DESTROY_SERVICE = 16;

            SessionHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_PRINTERS_ADDED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        IPrinterDiscoveryObserver observer = (IPrinterDiscoveryObserver) args.arg1;
                        List<PrinterInfo> addedPrinters = (List<PrinterInfo>) args.arg2;
                        args.recycle();
                        handlePrintersAdded(observer, addedPrinters);
                    } break;

                    case MSG_PRINTERS_REMOVED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        IPrinterDiscoveryObserver observer = (IPrinterDiscoveryObserver) args.arg1;
                        List<PrinterId> removedPrinterIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handlePrintersRemoved(observer, removedPrinterIds);
                    }

                    case MSG_DISPATCH_PRINTERS_ADDED: {
                        List<PrinterInfo> addedPrinters = (List<PrinterInfo>) message.obj;
                        handleDispatchPrintersAdded(addedPrinters);
                    } break;

                    case MSG_DISPATCH_PRINTERS_REMOVED: {
                        List<PrinterId> removedPrinterIds = (List<PrinterId>) message.obj;
                        handleDispatchPrintersRemoved(removedPrinterIds);
                    } break;

                    case MSG_CREATE_PRINTER_DISCOVERY_SESSION: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.createPrinterDiscoverySession();
                    } break;

                    case MSG_DESTROY_PRINTER_DISCOVERY_SESSION: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.destroyPrinterDiscoverySession();
                    } break;

                    case MSG_START_PRINTER_DISCOVERY: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.startPrinterDiscovery(null);
                    } break;

                    case MSG_STOP_PRINTER_DISCOVERY: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.stopPrinterDiscovery();
                    } break;

                    case MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchCreatePrinterDiscoverySession(services);
                    } break;

                    case MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchDestroyPrinterDiscoverySession(services);
                    } break;

                    case MSG_DISPATCH_START_PRINTER_DISCOVERY: {
                        SomeArgs args = (SomeArgs) message.obj;
                        List<RemotePrintService> services = (List<RemotePrintService>) args.arg1;
                        List<PrinterId> printerIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handleDispatchStartPrinterDiscovery(services, printerIds);
                    } break;

                    case MSG_DISPATCH_STOP_PRINTER_DISCOVERY: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchStopPrinterDiscovery(services);
                    } break;

                    case MSG_VALIDATE_PRINTERS: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        List<PrinterId> printerIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handleValidatePrinters(service, printerIds);
                    } break;

                    case MSG_START_PRINTER_STATE_TRACKING: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        PrinterId printerId = (PrinterId) args.arg2;
                        args.recycle();
                        handleStartPrinterStateTracking(service, printerId);
                    } break;

                    case MSG_STOP_PRINTER_STATE_TRACKING: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        PrinterId printerId = (PrinterId) args.arg2;
                        args.recycle();
                        handleStopPrinterStateTracking(service, printerId);
                    } break;

                    case MSG_DESTROY_SERVICE: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.destroy();
                    } break;
                }
            }
        }
    }

    private final class PrintJobForAppCache {
        private final SparseArray<List<PrintJobInfo>> mPrintJobsForRunningApp =
                new SparseArray<List<PrintJobInfo>>();

        public boolean onPrintJobCreated(final IBinder creator, final int appId,
                PrintJobInfo printJob) {
            try {
                creator.linkToDeath(new DeathRecipient() {
                    @Override
                    public void binderDied() {
                        creator.unlinkToDeath(this, 0);
                        synchronized (mLock) {
                            mPrintJobsForRunningApp.remove(appId);
                        }
                    }
                }, 0);
            } catch (RemoteException re) {
                /* The process is already dead - we just failed. */
                return false;
            }
            synchronized (mLock) {
                List<PrintJobInfo> printJobsForApp = mPrintJobsForRunningApp.get(appId);
                if (printJobsForApp == null) {
                    printJobsForApp = new ArrayList<PrintJobInfo>();
                    mPrintJobsForRunningApp.put(appId, printJobsForApp);
                }
                printJobsForApp.add(printJob);
            }
            return true;
        }

        public void onPrintJobStateChanged(PrintJobInfo printJob) {
            synchronized (mLock) {
                List<PrintJobInfo> printJobsForApp = mPrintJobsForRunningApp.get(
                        printJob.getAppId());
                if (printJobsForApp == null) {
                    return;
                }
                final int printJobCount = printJobsForApp.size();
                for (int i = 0; i < printJobCount; i++) {
                    PrintJobInfo oldPrintJob = printJobsForApp.get(i);
                    if (oldPrintJob.getId().equals(printJob.getId())) {
                        printJobsForApp.set(i, printJob);
                    }
                }
            }
        }

        public PrintJobInfo getPrintJob(PrintJobId printJobId, int appId) {
            synchronized (mLock) {
                List<PrintJobInfo> printJobsForApp = mPrintJobsForRunningApp.get(appId);
                if (printJobsForApp == null) {
                    return null;
                }
                final int printJobCount = printJobsForApp.size();
                for (int i = 0; i < printJobCount; i++) {
                    PrintJobInfo printJob = printJobsForApp.get(i);
                    if (printJob.getId().equals(printJobId)) {
                        return printJob;
                    }
                }
            }
            return null;
        }

        public List<PrintJobInfo> getPrintJobs(int appId) {
            synchronized (mLock) {
                List<PrintJobInfo> printJobs = null;
                if (appId == PrintManager.APP_ID_ANY) {
                    final int bucketCount = mPrintJobsForRunningApp.size();
                    for (int i = 0; i < bucketCount; i++) {
                        List<PrintJobInfo> bucket = mPrintJobsForRunningApp.valueAt(i);
                        if (printJobs == null) {
                            printJobs = new ArrayList<PrintJobInfo>();
                        }
                        printJobs.addAll(bucket);
                    }
                } else {
                    List<PrintJobInfo> bucket = mPrintJobsForRunningApp.get(appId);
                    if (bucket != null) {
                        if (printJobs == null) {
                            printJobs = new ArrayList<PrintJobInfo>();
                        }
                        printJobs.addAll(bucket);
                    }
                }
                if (printJobs != null) {
                    return printJobs;
                }
                return Collections.emptyList();
            }
        }

        public void dump(PrintWriter pw, String prefix) {
            synchronized (mLock) {
                String tab = "  ";
                final int bucketCount = mPrintJobsForRunningApp.size();
                for (int i = 0; i < bucketCount; i++) {
                    final int appId = mPrintJobsForRunningApp.keyAt(i);
                    pw.append(prefix).append("appId=" + appId).append(':').println();
                    List<PrintJobInfo> bucket = mPrintJobsForRunningApp.valueAt(i);
                    final int printJobCount = bucket.size();
                    for (int j = 0; j < printJobCount; j++) {
                        PrintJobInfo printJob = bucket.get(j);
                        pw.append(prefix).append(tab).append(printJob.toString()).println();
                    }
                }
            }
        }
    }
}
