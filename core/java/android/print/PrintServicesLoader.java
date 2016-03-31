/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.print;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Loader;
import android.os.Handler;
import android.os.Message;
import android.printservice.PrintServiceInfo;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Loader for the list of print services. Can be parametrized to select a subset.
 *
 * @hide
 */
public class PrintServicesLoader extends Loader<List<PrintServiceInfo>> {
    /** What type of services to load. */
    private final int mSelectionFlags;

    /** The print manager to be used by this object */
    private final @NonNull PrintManager mPrintManager;

    /** Handler to sequentialize the delivery of the results to the main thread */
    private Handler mHandler;

    /** Listens for updates to the data from the platform */
    private PrintManager.PrintServicesChangeListener mListener;

    /**
     * Create a new PrintServicesLoader.
     *
     * @param printManager   The print manager supplying the data
     * @param context        Context of the using object
     * @param selectionFlags What type of services to load.
     */
    public PrintServicesLoader(@NonNull PrintManager printManager, @NonNull Context context,
            int selectionFlags) {
        super(Preconditions.checkNotNull(context));
        mPrintManager = Preconditions.checkNotNull(printManager);
        mSelectionFlags = Preconditions.checkFlagsArgument(selectionFlags,
                PrintManager.ALL_SERVICES);
    }

    @Override
    protected void onForceLoad() {
        queueNewResult();
    }

    /**
     * Read the print services and queue it to be delivered on the main thread.
     */
    private void queueNewResult() {
        Message m = mHandler.obtainMessage(0);
        m.obj = mPrintManager.getPrintServices(mSelectionFlags);
        mHandler.sendMessage(m);
    }

    @Override
    protected void onStartLoading() {
        mHandler = new MyHandler();
        mListener = new PrintManager.PrintServicesChangeListener() {
            @Override public void onPrintServicesChanged() {
                queueNewResult();
            }
        };

        mPrintManager.addPrintServicesChangeListener(mListener);

        // Immediately deliver a result
        deliverResult(mPrintManager.getPrintServices(mSelectionFlags));
    }

    @Override
    protected void onStopLoading() {
        if (mListener != null) {
            mPrintManager.removePrintServicesChangeListener(mListener);
            mListener = null;
        }

        if (mHandler != null) {
            mHandler.removeMessages(0);
            mHandler = null;
        }
    }

    @Override
    protected void onReset() {
        onStopLoading();
    }

    /**
     * Handler to sequentialize all the updates to the main thread.
     */
    private class MyHandler extends Handler {
        /**
         * Create a new handler on the main thread.
         */
        public MyHandler() {
            super(getContext().getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);

            if (isStarted()) {
                deliverResult((List<PrintServiceInfo>) msg.obj);
            }
        }
    }
}
