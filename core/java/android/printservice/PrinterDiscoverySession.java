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
 * for adding discovered printers, removing already added printers that
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
 * {@link PrinterDiscoverySession#updatePrinters(List)}. The printers added in this
 * session can be acquired via {@link #getPrinters()} where the returned printers
 * will be an up-to-date snapshot of the printers that you reported during the
 * session. Printers are <strong>not</strong> persisted across sessions.
 * </p>
 * <p>
 * The system will make a call to
 * {@link PrinterDiscoverySession#onRequestPrinterUpdate(PrinterId)} if you
 * need to update a given printer. It is possible that you add a printer without
 * specifying its capabilities. This enables you to avoid querying all discovered
 * printers for their capabilities, rather querying the capabilities of a printer
 * only if necessary. For example, the system will request that you update a printer
 * if it gets selected by the user. If you did not report the printer capabilities
 * when adding it, you must do so after the system requests a printer update.
 * Otherwise, the printer will be ignored.
 * </p>
 * <p>
 * <strong>Note: </strong> All callbacks in this class are executed on the main
 * application thread. You also have to invoke any method of this class on the main
 * application thread.
 * </p>
 */
public abstract class PrinterDiscoverySession {
    private static final String LOG_TAG = "PrinterDiscoverySession";

    private static final int MAX_ITEMS_PER_CALLBACK = 100;

    private static int sIdCounter = 0;

    private final int mId;

    private final ArrayMap<PrinterId, PrinterInfo> mPrinters =
            new ArrayMap<PrinterId, PrinterInfo>();

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
            sendAddedPrinters(mObserver, getPrinters());
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
     * destroyed, i.e. after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @return The printers.
     *
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #updatePrinters(List)
     * @see #isDestroyed()
     */
    public final List<PrinterInfo> getPrinters() {
        PrintService.throwIfNotCalledOnMainThread();
        if (mIsDestroyed) {
            return Collections.emptyList();
        }
        return new ArrayList<PrinterInfo>(mPrinters.values());
    }

    /**
     * Adds discovered printers. Adding an already added printer has no effect.
     * Removed printers can be added again. You can call this method multiple
     * times during the life of this session. Duplicates will be ignored.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, i.e. after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @param printers The printers to add.
     *
     * @see #removePrinters(List)
     * @see #updatePrinters(List)
     * @see #getPrinters()
     * @see #isDestroyed()
     */
    public final void addPrinters(List<PrinterInfo> printers) {
        PrintService.throwIfNotCalledOnMainThread();

        // If the session is destroyed - nothing do to.
        if (mIsDestroyed) {
            Log.w(LOG_TAG, "Not adding printers - session destroyed.");
            return;
        }

        if (mIsDiscoveryStarted) {
            // If during discovery, add the new printers and send them.
            List<PrinterInfo> addedPrinters = new ArrayList<PrinterInfo>();
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo addedPrinter = printers.get(i);
                if (mPrinters.get(addedPrinter.getId()) == null) {
                    mPrinters.put(addedPrinter.getId(), addedPrinter);
                    addedPrinters.add(addedPrinter);
                }
            }

            // Send the added printers, if such.
            if (!addedPrinters.isEmpty()) {
                sendAddedPrinters(mObserver, addedPrinters);
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

    private static void sendAddedPrinters(IPrintServiceClient observer,
        List<PrinterInfo> printers) {
        try {
            final int printerCount = printers.size();
            if (printerCount <= MAX_ITEMS_PER_CALLBACK) {
                observer.onPrintersAdded(printers);
            } else {
                // Send the added printers in chunks avoiding the binder transaction limit.
                final int transactionCount = (printerCount / MAX_ITEMS_PER_CALLBACK) + 1;
                for (int i = 0; i < transactionCount; i++) {
                    final int start = i * MAX_ITEMS_PER_CALLBACK;
                    final int end = Math.min(start + MAX_ITEMS_PER_CALLBACK, printerCount);
                    List<PrinterInfo> subPrinters = printers.subList(start, end);
                    observer.onPrintersAdded(subPrinters);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error sending added printers", re);
        }
    }

    /**
     * Removes added printers. Removing an already removed or never added
     * printer has no effect. Removed printers can be added again. You can
     * call this method multiple times during the lifetime of this session.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, i.e. after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @param printerIds The ids of the removed printers.
     *
     * @see #addPrinters(List)
     * @see #updatePrinters(List)
     * @see #getPrinters()
     * @see #isDestroyed()
     */
    public final void removePrinters(List<PrinterId> printerIds) {
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
                sendRemovedPrinters(mObserver, removedPrinterIds);
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

    private static void sendRemovedPrinters(IPrintServiceClient observer,
            List<PrinterId> printerIds) {
        try {
            final int printerIdCount = printerIds.size();
            if (printerIdCount <= MAX_ITEMS_PER_CALLBACK) {
                observer.onPrintersRemoved(printerIds);
            } else {
                final int transactionCount = (printerIdCount / MAX_ITEMS_PER_CALLBACK) + 1;
                for (int i = 0; i < transactionCount; i++) {
                    final int start = i * MAX_ITEMS_PER_CALLBACK;
                    final int end = Math.min(start + MAX_ITEMS_PER_CALLBACK, printerIdCount);
                    List<PrinterId> subPrinterIds = printerIds.subList(start, end);
                    observer.onPrintersRemoved(subPrinterIds);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error sending removed printers", re);
        }
    }

    /**
     * Updates added printers. Updating a printer that was not added or that
     * was removed has no effect. You can call this method multiple times
     * during the lifetime of this session.
     * <p>
     * <strong>Note: </strong> Calls to this method after the session is
     * destroyed, i.e. after the {@link #onDestroy()} callback, will be ignored.
     * </p>
     *
     * @param printers The printers to update.
     *
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #getPrinters()
     * @see #isDestroyed()
     */
    public final void updatePrinters(List<PrinterInfo> printers) {
        PrintService.throwIfNotCalledOnMainThread();

        // If the session is destroyed - nothing do to.
        if (mIsDestroyed) {
            Log.w(LOG_TAG, "Not updating printers - session destroyed.");
            return;
        }

        if (mIsDiscoveryStarted) {
            // If during discovery, update existing printers and send them.
            List<PrinterInfo> updatedPrinters = new ArrayList<PrinterInfo>();
            final int updatedPrinterCount = printers.size();
            for (int i = 0; i < updatedPrinterCount; i++) {
                PrinterInfo updatedPrinter = printers.get(i);
                PrinterInfo oldPrinter = mPrinters.get(updatedPrinter.getId());
                if (oldPrinter != null && !oldPrinter.equals(updatedPrinter)) {
                    mPrinters.put(updatedPrinter.getId(), updatedPrinter);
                    updatedPrinters.add(updatedPrinter);
                }
            }

            // Send the updated printers, if such.
            if (!updatedPrinters.isEmpty()) {
                sendUpdatedPrinters(mObserver, updatedPrinters);
            }
        } else {
            // Remember the last sent printers if needed.
            if (mLastSentPrinters == null) {
                mLastSentPrinters = new ArrayMap<PrinterId, PrinterInfo>(mPrinters);
            }

            // Update the printers.
            final int updatedPrinterCount = printers.size();
            for (int i = 0; i < updatedPrinterCount; i++) {
                PrinterInfo updatedPrinter = printers.get(i);
                PrinterInfo oldPrinter = mPrinters.get(updatedPrinter.getId());
                if (oldPrinter != null && !oldPrinter.equals(updatedPrinter)) {
                    mPrinters.put(updatedPrinter.getId(), updatedPrinter);
                }
            }
        }
    }

    private static void sendUpdatedPrinters(IPrintServiceClient observer,
            List<PrinterInfo> printers) {
        try {
            final int printerCount = printers.size();
            if (printerCount <= MAX_ITEMS_PER_CALLBACK) {
                observer.onPrintersUpdated(printers);
            } else {
                final int transactionCount = (printerCount / MAX_ITEMS_PER_CALLBACK) + 1;
                for (int i = 0; i < transactionCount; i++) {
                    final int start = i * MAX_ITEMS_PER_CALLBACK;
                    final int end = Math.min(start + MAX_ITEMS_PER_CALLBACK, printerCount);
                    List<PrinterInfo> subPrinters = printers.subList(start, end);
                    observer.onPrintersUpdated(subPrinters);
                }
            }
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error sending updated printers", re);
        }
    }

    private void sendOutOfDiscoveryPeriodPrinterChanges() {
        // Noting changed since the last discovery period - nothing to do.
        if (mLastSentPrinters == null || mLastSentPrinters.isEmpty()) {
            mLastSentPrinters = null;
            return;
        }

        List<PrinterInfo> addedPrinters = null;
        List<PrinterInfo> updatedPrinters = null;
        List<PrinterId> removedPrinterIds = null;

        // Determine the added and updated printers.
        for (PrinterInfo printer : mPrinters.values()) {
            PrinterInfo sentPrinter = mLastSentPrinters.get(printer.getId());
            if (sentPrinter != null) {
                if (!sentPrinter.equals(printer)) {
                    if (updatedPrinters == null) {
                        updatedPrinters = new ArrayList<PrinterInfo>();
                    }
                    updatedPrinters.add(printer);
                }
            } else {
                if (addedPrinters == null) {
                    addedPrinters = new ArrayList<PrinterInfo>();
                }
                addedPrinters.add(printer);
            }
        }

        // Send the added printers, if such.
        if (addedPrinters != null) {
            sendAddedPrinters(mObserver, addedPrinters);
        }

        // Send the updated printers, if such.
        if (updatedPrinters != null) {
            sendUpdatedPrinters(mObserver, updatedPrinters);
        }

        // Determine the removed printers.
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
            sendRemovedPrinters(mObserver, removedPrinterIds);
        }

        mLastSentPrinters = null;
    }

    /**
     * Callback asking you to start printer discovery. Discovered printers should be
     * added via calling {@link #addPrinters(List)}. Added printers that disappeared
     * should be removed via calling {@link #removePrinters(List)}. Added printers
     * whose properties or capabilities changed should be updated via calling {@link
     * #updatePrinters(List)}. You will receive a call to call to {@link
     * #onStopPrinterDiscovery()} when you should stop printer discovery.
     * <p>
     * During the lifetime of this session all printers that are known to your print
     * service have to be added. The system does not retain any printers across sessions.
     * However, if you were asked to start and then stop performing printer discovery
     * in this session, then a subsequent discovering should not re-discover already
     * discovered printers.
     * </p>
     * <p>
     * <strong>Note: </strong>You are also given a list of printers whose availability
     * has to be checked first. For example, these printers could be the user's favorite
     * ones, therefore they have to be verified first.
     * </p>
     *
     * @param priorityList The list of printers to validate first. Never null.
     *
     * @see #onStopPrinterDiscovery()
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #updatePrinters(List)
     * @see #isPrinterDiscoveryStarted()
     */
    public abstract void onStartPrinterDiscovery(List<PrinterId> priorityList);

    /**
     * Callback notifying you that you should stop printer discovery.
     *
     * @see #onStartPrinterDiscovery(List)
     * @see #isPrinterDiscoveryStarted()
     */
    public abstract void onStopPrinterDiscovery();

    /**
     * Requests that you update a printer. You are responsible for updating
     * the printer by also reporting its capabilities via calling {@link
     * #updatePrinters(List)}.
     * <p>
     * <strong>Note: </strong> A printer can be initially added without its
     * capabilities to avoid polling printers that the user will not select.
     * However, after this method is called you are expected to update the
     * printer <strong>including</strong> its capabilities. Otherwise, the
     * printer will be ignored.
     * <p>
     * A scenario when you may be requested to update a printer is if the user
     * selects it and the system has to present print options UI based on the
     * printer's capabilities.
     * </p>
     *
     * @param printerId The printer id.
     *
     * @see #updatePrinters(List)
     * @see PrinterInfo.Builder#setCapabilities(PrinterCapabilitiesInfo)
     *      PrinterInfo.Builder.setCapabilities(PrinterCapabilitiesInfo)
     */
    public abstract void onRequestPrinterUpdate(PrinterId printerId);

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

    void startPrinterDiscovery(List<PrinterId> priorityList) {
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

    void requestPrinterUpdate(PrinterId printerId) {
        if (!mIsDestroyed) {
            onRequestPrinterUpdate(printerId);
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
