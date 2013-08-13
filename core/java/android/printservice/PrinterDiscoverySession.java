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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.print.IPrinterDiscoverySessionController;
import android.print.IPrinterDiscoverySessionObserver;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * This class encapsulates the interaction between a print service and the
 * system during printer discovery. During printer discovery you are responsible
 * for adding discovered printers, removing already added printers that
 * disappeared, and updating already added printers.
 * <p>
 * The opening of the session is announced by a call to {@link
 * PrinterDiscoverySession#onOpen(List)} at which point you should start printer
 * discovery. The closing of the session is announced by a call to {@link
 * PrinterDiscoverySession#onClose()} at which point you should stop printer
 * discovery. Discovered printers are added by invoking {@link
 * PrinterDiscoverySession#addPrinters(List)}. Added printers that disappeared
 * are removed by invoking {@link PrinterDiscoverySession#removePrinters(List)}.
 * Added printers whose properties or capabilities changed are updated through
 * a call to {@link PrinterDiscoverySession#updatePrinters(List)}.
 * </p>
 * <p>
 * The system will make a call to
 * {@link PrinterDiscoverySession#onRequestPrinterUpdate(PrinterId)} if you
 * need to update a given printer. It is possible that you add a printer without
 * specifying its capabilities. This enables you to avoid querying all
 * discovered printers for their capabilities, rather querying the capabilities
 * of a printer only if necessary. For example, the system will require that you
 * update a printer if it gets selected by the user. If you did not report the
 * printer capabilities when adding it, you must do so after the system requests
 * a printer update. Otherwise, the printer will be ignored.
 * </p>
 * <p>
 * During printer discovery all printers that are known to your print service
 * have to be added. The system does not retain any printers from previous
 * sessions.
 * </p>
 */
public abstract class PrinterDiscoverySession {
    private static final String LOG_TAG = "PrinterDiscoverySession";

    private static int sIdCounter = 0;

    private final Object mLock = new Object();

    private final Handler mHandler;

    private final int mId;

    private IPrinterDiscoverySessionController mController;

    private IPrinterDiscoverySessionObserver mObserver;

    /**
     * Constructor.
     *
     * @param context A context instance.
     */
    public PrinterDiscoverySession(Context context) {
        mId = sIdCounter++;
        mHandler = new SessionHandler(context.getMainLooper());
        mController = new PrinterDiscoverySessionController(this);
    }

    void setObserver(IPrinterDiscoverySessionObserver observer) {
        synchronized (mLock) {
            mObserver = observer;
            try {
                mObserver.setController(mController);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error setting session controller", re);
            }
        }
    }

    int getId() {
        return mId;
    }

    /**
     * Adds discovered printers. Adding an already added printer has no effect.
     * Removed printers can be added again. You can call this method multiple
     * times during printer discovery.
     * <p>
     * <strong>Note: </strong> Calls to this method before the session is opened,
     * i.e. before the {@link #onOpen(List)} call, and after the session is closed,
     * i.e. after the call to {@link #onClose()}, will be ignored.
     * </p>
     *
     * @param printers The printers to add.
     *
     * @see #removePrinters(List)
     * @see #updatePrinters(List)
     */
    public final void addPrinters(List<PrinterInfo> printers) {
        final IPrinterDiscoverySessionObserver observer;
        synchronized (mLock) {
            observer = mObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersAdded(printers);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error adding printers", re);
            }
        } else {
            Log.w(LOG_TAG, "Printer discovery session not open not adding printers.");
        }
    }

    /**
     * Removes added printers. Removing an already removed or never added
     * printer has no effect. Removed printers can be added again. You
     * can call this method multiple times during printer discovery.
     * <p>
     * <strong>Note: </strong> Calls to this method before the session is opened,
     * i.e. before the {@link #onOpen(List)} call, and after the session is closed,
     * i.e. after the call to {@link #onClose()}, will be ignored.
     * </p>
     *
     * @param printerIds The ids of the removed printers.
     *
     * @see #addPrinters(List)
     * @see #updatePrinters(List)
     */
    public final void removePrinters(List<PrinterId> printerIds) {
        final IPrinterDiscoverySessionObserver observer;
        synchronized (mLock) {
            observer = mObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersRemoved(printerIds);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error removing printers", re);
            }
        } else {
            Log.w(LOG_TAG, "Printer discovery session not open not removing printers.");
        }
    }

    /**
     * Updates added printers. Updating a printer that was not added or that
     * was removed has no effect. You can call this method multiple times
     * during printer discovery.
     * <p>
     * <strong>Note: </strong> Calls to this method before the session is opened,
     * i.e. before the {@link #onOpen(List)} call, and after the session is closed,
     * i.e. after the call to {@link #onClose()}, will be ignored.
     * </p>
     *
     * @param printers The printers to update.
     *
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     */
    public final void updatePrinters(List<PrinterInfo> printers) {
        final IPrinterDiscoverySessionObserver observer;
        synchronized (mLock) {
            observer = mObserver;
        }
        if (observer != null) {
            try {
                observer.onPrintersUpdated(printers);
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error updating printers", re);
            }
        } else {
            Log.w(LOG_TAG, "Printer discovery session not open not updating printers.");
        }
    }

    /**
     * Callback notifying you that the session is open and you should start
     * printer discovery. Discovered printers should be added via calling
     * {@link #addPrinters(List)}. Added printers that disappeared should be
     * removed via calling {@link #removePrinters(List)}. Added printers whose
     * properties or capabilities changes should be updated via calling {@link
     * #updatePrinters(List)}. When the session is closed you will receive a
     * call to {@link #onClose()}.
     * <p>
     * During printer discovery all printers that are known to your print
     * service have to be added. The system does not retain any printers from
     * previous sessions.
     * </p>
     * <p>
     * <strong>Note: </strong>You are also given a list of printers whose
     * availability has to be checked first. For example, these printers could
     * be the user's favorite ones, therefore they have to be verified first.
     * </p>
     *
     * @see #onClose()
     * @see #addPrinters(List)
     * @see #removePrinters(List)
     * @see #updatePrinters(List)
     */
    public abstract void onOpen(List<PrinterId> priorityList);

    /**
     * Callback notifying you that the session is closed and you should stop
     * printer discovery. After the session is closed any call to the methods
     * of this instance will be ignored. Once the session is closed
     * it will never be opened again.
     */
    public abstract void onClose();

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

    void close() {
        synchronized (mLock) {
            mController = null;
            mObserver = null;
        }
    }

    private final class SessionHandler extends Handler {
        public static final int MSG_OPEN = 1;
        public static final int MSG_CLOSE = 2;
        public static final int MSG_REQUEST_PRINTER_UPDATE = 3;

        public SessionHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_OPEN: {
                    List<PrinterId> priorityList = (List<PrinterId>) message.obj;
                    onOpen(priorityList);
                } break;

                case MSG_CLOSE: {
                    onClose();
                    close();
                } break;

                case MSG_REQUEST_PRINTER_UPDATE: {
                    PrinterId printerId = (PrinterId) message.obj;
                    onRequestPrinterUpdate(printerId);
                } break;
            }
        }
    }

    private static final class PrinterDiscoverySessionController extends
            IPrinterDiscoverySessionController.Stub {
        private final WeakReference<PrinterDiscoverySession> mWeakSession;

        public PrinterDiscoverySessionController(PrinterDiscoverySession session) {
            mWeakSession = new WeakReference<PrinterDiscoverySession>(session);
        }

        @Override
        public void open(List<PrinterId> priorityList) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(SessionHandler.MSG_OPEN,
                        priorityList).sendToTarget();
            }
        }

        @Override
        public void close() {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.sendEmptyMessage(SessionHandler.MSG_CLOSE);
            }
        }

        @Override
        public void requestPrinterUpdate(PrinterId printerId) {
            PrinterDiscoverySession session = mWeakSession.get();
            if (session != null) {
                session.mHandler.obtainMessage(
                        SessionHandler.MSG_REQUEST_PRINTER_UPDATE,
                        printerId).sendToTarget();
            }
        }
    };
}
