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

package android.printservice;

import android.annotation.NonNull;
import android.content.pm.ParceledListSlice;
import android.os.CancellationSignal;
import android.os.RemoteException;
import android.print.PrinterCapabilitiesInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.ArrayMap;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class encapsulates the interaction between a print service and the
 * system during printer discovery. During printer discovery you are responsible
 * for adding discovered printers, removing previously added printers that
 * disappeared, and updating already added printers.
 * <p>
 * During the lifetime of this session you may be asked to start and stop
 * performing printer discovery multiple times. You will receive a call to {@link
 * PrinterDiscoverySession#onStartPrinterDiscovery(List)} to start printer
 * discovery and a call to {@link PrinterDiscoverySession#onStopPrinterDiscovery()}
 * to stop printer discovery. When the system is no longer interested in printers
 * discovered by this session you will receive a call to {@link #onDestroy()} at
 * which point the system will no longer call into the session and all the session
 * methods will do nothing.
 * </p>
 * <p>
 * Discovered printers are added by invoking {@link
 * PrinterDiscoverySession#addPrinters(List)}. Added printers that disappeared are
 * removed by invoking {@link PrinterDiscoverySession#removePrinters(List)}. Added
 * printers whose properties or capabilities changed are updated through a call to
 * {@link PrinterDiscoverySession#addPrinters(List)}. The printers added in this
 * session can be acquired via {@link #getPrinters()} where the returned printers
 * will be an up-to-date snapshot of the printers that you reported during the
 * session. Printers are <strong>not</strong> persisted across sessions.
 * </p>
 * <p>
 * The system will make a call to {@link #onValidatePrinters(List)} if you
 * need to update some printers. It is possible that you add a printer without
 * specifying its capabilities. This enables you to avoid querying all discovered
 * printers for their capabilities, rather querying the capabilities of a printer
 * only if necessary. For example, the system will request that you update a printer
 * if it gets selected by the user. When validating printers you do not need to
 * provide the printers' capabilities but may do so.
 * </p>
 * <p>
 * If the system is interested in being constantly updated for the state of a
 * printer you will receive a call to {@link #onStartPrinterStateTracking(PrinterId)}
 * after which you will have to do a best effort to keep the system updated for
 * changes in the printer state and capabilities. You also <strong>must</strong>
 * update the printer capabilities if you did not provide them when adding it, or
 * the printer will be ignored. When the system is no longer interested in getting
 * updates for a printer you will receive a call to {@link #onStopPrinterStateTracking(
 * PrinterId)}.
 * </p>
 * <p>
 * <strong>Note: </strong> All callbacks in this class are executed on the main
 * application thread. You also have to invoke any method of this class on the main
 * application thread.
 * </p>
 */
public abstract class PrinterDiscoverySession {
    private static final String LOG_TAG = "PrinterDiscoverySession";

    private static int sIdCounter = 0;

    private final int mId;

    private final ArrayMap<PrinterId, PrinterInfo> mPrinters =
            new ArrayMap<PrinterId, PrinterInfo>();

    private final List<PrinterId> mTrackedPrinters =
            new ArrayList<PrinterId>();

    private ArrayMap<PrinterId, PrinterInfo> mLastSentPrinters;

    private IPrintServiceClient mObserver;

    private boolean mIsDestroyed;

    private boolean mIsDiscoveryStarted;

    /**
     * Constructor.
     */
    public PrinterDiscoverySession() {
        mId = sIdCounter++;
    }

    void setObserver(IPrintServiceClient observer) {
        mObserver = observer;
        // If some printers were added in the method that
        // created the session, send them over.
        if (!mPrinters.isEmpty()) {
            try {
                mObserver.onPrintersAdded(new ParceledListSlice<PrinterInfo>(getPrinters()));
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending added printers", re);
            }
        }
    }

    int getId() {
        return mId;
    }

    /**
     * Gets the printers reported in this session. For example, if you add two
     * printers and remove one of them, the returned list will contain only
     * the printer that was added but not removed.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, that is after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @return The printers.
     *
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #isDestroyed()
     */
    public final @NonNull List<PrinterInfo> getPrinters() {
        PrintService.throwIfNotCalledOnMainThread();
        if (mIsDestroyed) {
            return Collections.emptyList();
        }
        return new ArrayList<PrinterInfo>(mPrinters.values());
    }

    /**
     * Adds discovered printers. Adding an already added printer updates it.
     * Removed printers can be added again. You can call this method multiple
     * times during the life of this session. Duplicates will be ignored.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, that is after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @param printers The printers to add.
     *
     * @see #removePrinters(List)
     * @see #getPrinters()
     * @see #isDestroyed()
     */
    public final void addPrinters(@NonNull List<PrinterInfo> printers) {
        PrintService.throwIfNotCalledOnMainThread();

        // If the session is destroyed - nothing do to.
        if (mIsDestroyed) {
            Log.w(LOG_TAG, "Not adding printers - session destroyed.");
            return;
        }

        if (mIsDiscoveryStarted) {
            // If during discovery, add the new printers and send them.
            List<PrinterInfo> addedPrinters = null;
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo addedPrinter = printers.get(i);
                PrinterInfo oldPrinter = mPrinters.put(addedPrinter.getId(), addedPrinter);
                if (oldPrinter == null || !oldPrinter.equals(addedPrinter)) {
                    if (addedPrinters == null) {
                        addedPrinters = new ArrayList<PrinterInfo>();
                    }
                    addedPrinters.add(addedPrinter);
                }
            }

            // Send the added printers, if such.
            if (addedPrinters != null) {
                try {
                    mObserver.onPrintersAdded(new ParceledListSlice<PrinterInfo>(addedPrinters));
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error sending added printers", re);
                }
            }
        } else {
            // Remember the last sent printers if needed.
            if (mLastSentPrinters == null) {
                mLastSentPrinters = new ArrayMap<PrinterId, PrinterInfo>(mPrinters);
            }

            // Update the printers.
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo addedPrinter = printers.get(i);
                if (mPrinters.get(addedPrinter.getId()) == null) {
                    mPrinters.put(addedPrinter.getId(), addedPrinter);
                }
            }
        }
    }

    /**
     * Removes added printers. Removing an already removed or never added
     * printer has no effect. Removed printers can be added again. You can
     * call this method multiple times during the lifetime of this session.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, that is after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @param printerIds The ids of the removed printers.
     *
     * @see #addPrinters(List)
     * @see #getPrinters()
     * @see #isDestroyed()
     */
    public final void removePrinters(@NonNull List<PrinterId> printerIds) {
        PrintService.throwIfNotCalledOnMainThread();

        // If the session is destroyed - nothing do to.
        if (mIsDestroyed) {
            Log.w(LOG_TAG, "Not removing printers - session destroyed.");
            return;
        }

        if (mIsDiscoveryStarted) {
            // If during discovery, remove existing printers and send them.
            List<PrinterId> removedPrinterIds = new ArrayList<PrinterId>();
            final int removedPrinterIdCount = printerIds.size();
            for (int i = 0; i < removedPrinterIdCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                if (mPrinters.remove(removedPrinterId) != null) {
                    removedPrinterIds.add(removedPrinterId);
                }
            }

            // Send the removed printers, if such.
            if (!removedPrinterIds.isEmpty()) {
                try {
                    mObserver.onPrintersRemoved(new ParceledListSlice<PrinterId>(
                            removedPrinterIds));
                } catch (RemoteException re) {
                    Log.e(LOG_TAG, "Error sending removed printers", re);
                }
            }
        } else {
            // Remember the last sent printers if needed.
            if (mLastSentPrinters == null) {
                mLastSentPrinters = new ArrayMap<PrinterId, PrinterInfo>(mPrinters);
            }

            // Update the printers.
            final int removedPrinterIdCount = printerIds.size();
            for (int i = 0; i < removedPrinterIdCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                mPrinters.remove(removedPrinterId);
            }
        }
    }

    private void sendOutOfDiscoveryPeriodPrinterChanges() {
        // Noting changed since the last discovery period - nothing to do.
        if (mLastSentPrinters == null || mLastSentPrinters.isEmpty()) {
            mLastSentPrinters = null;
            return;
        }

        // Determine the added printers.
        List<PrinterInfo> addedPrinters = null;
        for (PrinterInfo printer : mPrinters.values()) {
            PrinterInfo sentPrinter = mLastSentPrinters.get(printer.getId());
            if (sentPrinter == null || !sentPrinter.equals(printer)) {
                if (addedPrinters == null) {
                    addedPrinters = new ArrayList<PrinterInfo>();
                }
                addedPrinters.add(printer);
            }
        }

        // Send the added printers, if such.
        if (addedPrinters != null) {
            try {
                mObserver.onPrintersAdded(new ParceledListSlice<PrinterInfo>(addedPrinters));
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending added printers", re);
            }
        }

        // Determine the removed printers.
        List<PrinterId> removedPrinterIds = null;
        for (PrinterInfo sentPrinter : mLastSentPrinters.values()) {
            if (!mPrinters.containsKey(sentPrinter.getId())) {
                if (removedPrinterIds == null) {
                    removedPrinterIds = new ArrayList<PrinterId>();
                }
                removedPrinterIds.add(sentPrinter.getId());
            }
        }

        // Send the removed printers, if such.
        if (removedPrinterIds != null) {
            try {
                mObserver.onPrintersRemoved(new ParceledListSlice<PrinterId>(removedPrinterIds));
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending removed printers", re);
            }
        }

        mLastSentPrinters = null;
    }

    /**
     * Callback asking you to start printer discovery. Discovered printers should be
     * added via calling {@link #addPrinters(List)}. Added printers that disappeared
     * should be removed via calling {@link #removePrinters(List)}. Added printers
     * whose properties or capabilities changed should be updated via calling {@link
     * #addPrinters(List)}. You will receive a call to {@link #onStopPrinterDiscovery()}
     * when you should stop printer discovery.
     * <p>
     * During the lifetime of this session all printers that are known to your print
     * service have to be added. The system does not retain any printers across sessions.
     * However, if you were asked to start and then stop performing printer discovery
     * in this session, then a subsequent discovering should not re-discover already
     * discovered printers. You can get the printers reported during this session by
     * calling {@link #getPrinters()}.
     * </p>
     * <p>
     * <strong>Note: </strong>You are also given a list of printers whose availability
     * has to be checked first. For example, these printers could be the user's favorite
     * ones, therefore they have to be verified first. You do <strong>not need</strong>
     * to provide the capabilities of the printers, rather verify whether they exist
     * similarly to {@link #onValidatePrinters(List)}.
     * </p>
     *
     * @param priorityList The list of printers to validate first. Never null.
     *
     * @see #onStopPrinterDiscovery()
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #isPrinterDiscoveryStarted()
     */
    public abstract void onStartPrinterDiscovery(@NonNull List<PrinterId> priorityList);

    /**
     * Callback notifying you that you should stop printer discovery.
     *
     * @see #onStartPrinterDiscovery(List)
     * @see #isPrinterDiscoveryStarted()
     */
    public abstract void onStopPrinterDiscovery();

    /**
     * Callback asking you to validate that the given printers are valid, that
     * is they exist. You are responsible for checking whether these printers
     * exist and for the ones that do exist notify the system via calling
     * {@link #addPrinters(List)}.
     * <p>
     * <strong>Note: </strong> You are <strong>not required</strong> to provide
     * the printer capabilities when updating the printers that do exist.
     * <p>
     *
     * @param printerIds The printers to validate.
     *
     * @see android.print.PrinterInfo.Builder#setCapabilities(PrinterCapabilitiesInfo)
     *      PrinterInfo.Builder.setCapabilities(PrinterCapabilitiesInfo)
     */
    public abstract void onValidatePrinters(@NonNull List<PrinterId> printerIds);

    /**
     * Callback asking you to start tracking the state of a printer. Tracking
     * the state means that you should do a best effort to observe the state
     * of this printer and notify the system if that state changes via calling
     * {@link #addPrinters(List)}.
     * <p>
     * <strong>Note: </strong> A printer can be initially added without its
     * capabilities to avoid polling printers that the user will not select.
     * However, after this method is called you are expected to update the
     * printer <strong>including</strong> its capabilities. Otherwise, the
     * printer will be ignored.
     * <p>
     * <p>
     * A scenario when you may be requested to track a printer's state is if
     * the user selects that printer and the system has to present print
     * options UI based on the printer's capabilities. In this case the user
     * should be promptly informed if, for example, the printer becomes
     * unavailable.
     * </p>
     *
     * @param printerId The printer to start tracking.
     *
     * @see #onStopPrinterStateTracking(PrinterId)
     * @see android.print.PrinterInfo.Builder#setCapabilities(PrinterCapabilitiesInfo)
     *      PrinterInfo.Builder.setCapabilities(PrinterCapabilitiesInfo)
     */
    public abstract void onStartPrinterStateTracking(@NonNull PrinterId printerId);

    /**
     * Called by the system to request the custom icon for a printer. Once the icon is available the
     * print services uses {@link CustomPrinterIconCallback#onCustomPrinterIconLoaded} to send the
     * icon to the system.
     *
     * @param printerId The printer to icon belongs to.
     * @param cancellationSignal Signal used to cancel the request.
     * @param callback Callback for returning the icon to the system.
     *
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon(boolean)
     */
    public void onRequestCustomPrinterIcon(@NonNull PrinterId printerId,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull CustomPrinterIconCallback callback) {
    }

    /**
     * Callback asking you to stop tracking the state of a printer. The passed
     * in printer id is the one for which you received a call to {@link
     * #onStartPrinterStateTracking(PrinterId)}.
     *
     * @param printerId The printer to stop tracking.
     *
     * @see #onStartPrinterStateTracking(PrinterId)
     */
    public abstract void onStopPrinterStateTracking(@NonNull PrinterId printerId);

    /**
     * Gets the printers that should be tracked. These are printers that are
     * important to the user and for which you received a call to {@link
     * #onStartPrinterStateTracking(PrinterId)} asking you to observer their
     * state and reporting it to the system via {@link #addPrinters(List)}.
     * You will receive a call to {@link #onStopPrinterStateTracking(PrinterId)}
     * if you should stop tracking a printer.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, that is after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @return The printers.
     *
     * @see #onStartPrinterStateTracking(PrinterId)
     * @see #onStopPrinterStateTracking(PrinterId)
     * @see #isDestroyed()
     */
    public final @NonNull List<PrinterId> getTrackedPrinters() {
        PrintService.throwIfNotCalledOnMainThread();
        if (mIsDestroyed) {
            return Collections.emptyList();
        }
        return new ArrayList<PrinterId>(mTrackedPrinters);
    }

    /**
     * Notifies you that the session is destroyed. After this callback is invoked
     * any calls to the methods of this class will be ignored, {@link #isDestroyed()}
     * will return true and you will also no longer receive callbacks.
     *
     * @see #isDestroyed()
     */
    public abstract void onDestroy();

    /**
     * Gets whether the session is destroyed.
     *
     * @return Whether the session is destroyed.
     *
     * @see #onDestroy()
     */
    public final boolean isDestroyed() {
        PrintService.throwIfNotCalledOnMainThread();
        return mIsDestroyed;
    }

    /**
     * Gets whether printer discovery is started.
     *
     * @return Whether printer discovery is destroyed.
     *
     * @see #onStartPrinterDiscovery(List)
     * @see #onStopPrinterDiscovery()
     */
    public final boolean isPrinterDiscoveryStarted() {
        PrintService.throwIfNotCalledOnMainThread();
        return mIsDiscoveryStarted;
    }

    void startPrinterDiscovery(@NonNull List<PrinterId> priorityList) {
        if (!mIsDestroyed) {
            mIsDiscoveryStarted = true;
            sendOutOfDiscoveryPeriodPrinterChanges();
            if (priorityList == null) {
                priorityList = Collections.emptyList();
            }
            onStartPrinterDiscovery(priorityList);
        }
    }

    void stopPrinterDiscovery() {
        if (!mIsDestroyed) {
            mIsDiscoveryStarted = false;
            onStopPrinterDiscovery();
        }
    }

    void validatePrinters(@NonNull List<PrinterId> printerIds) {
        if (!mIsDestroyed && mObserver != null) {
            onValidatePrinters(printerIds);
        }
    }

    void startPrinterStateTracking(@NonNull PrinterId printerId) {
        if (!mIsDestroyed && mObserver != null
                && !mTrackedPrinters.contains(printerId)) {
            mTrackedPrinters.add(printerId);
            onStartPrinterStateTracking(printerId);
        }
    }

    /**
     * Request the custom icon for a printer.
     *
     * @param printerId The printer to icon belongs to.
     * @see android.print.PrinterInfo.Builder#setHasCustomPrinterIcon()
     */
    void requestCustomPrinterIcon(@NonNull PrinterId printerId) {
        if (!mIsDestroyed && mObserver != null) {
            CustomPrinterIconCallback callback = new CustomPrinterIconCallback(printerId,
                    mObserver);
            onRequestCustomPrinterIcon(printerId, new CancellationSignal(), callback);
        }
    }

    void stopPrinterStateTracking(@NonNull PrinterId printerId) {
        if (!mIsDestroyed && mObserver != null
                && mTrackedPrinters.remove(printerId)) {
            onStopPrinterStateTracking(printerId);
        }
    }

    void destroy() {
        if (!mIsDestroyed) {
            mIsDestroyed = true;
            mIsDiscoveryStarted = false;
            mPrinters.clear();
            mLastSentPrinters = null;
            mObserver = null;
            onDestroy();
        }
    }
}
