/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.printspooler.ui;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.LoaderManager.LoaderCallbacks;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.print.PrinterId;
import android.print.PrinterInfo;
import com.android.internal.os.SomeArgs;

import java.util.ArrayList;
import java.util.List;

public class PrinterRegistry {

    private final int mLoaderId;

    private final Activity mActivity;

    private final List<PrinterInfo> mPrinters = new ArrayList<>();

    private final Runnable mReadyCallback;

    private final Handler mHandler;

    private boolean mReady;

    private OnPrintersChangeListener mOnPrintersChangeListener;

    public interface OnPrintersChangeListener {
        public void onPrintersChanged(List<PrinterInfo> printers);
        public void onPrintersInvalid();
    }

    public PrinterRegistry(Activity activity, Runnable readyCallback, int loaderId,
            int internalLoaderId) {
        mLoaderId = loaderId;
        mActivity = activity;
        mReadyCallback = readyCallback;
        mHandler = new MyHandler(activity.getMainLooper());

        Bundle loaderData = new Bundle(1);
        loaderData.putInt(null, internalLoaderId);

        activity.getLoaderManager().initLoader(loaderId, loaderData, mLoaderCallbacks);
    }

    public void setOnPrintersChangeListener(OnPrintersChangeListener listener) {
        mOnPrintersChangeListener = listener;
    }

    public List<PrinterInfo> getPrinters() {
        return mPrinters;
    }

    public void addHistoricalPrinter(PrinterInfo printer) {
        FusedPrintersProvider provider = getPrinterProvider();
        if (provider != null) {
            getPrinterProvider().addHistoricalPrinter(printer);
        }
    }

    public void forgetFavoritePrinter(PrinterId printerId) {
        FusedPrintersProvider provider = getPrinterProvider();
        if (provider != null) {
            provider.forgetFavoritePrinter(printerId);
        }
    }

    public boolean isFavoritePrinter(PrinterId printerId) {
        FusedPrintersProvider provider = getPrinterProvider();
        if (provider != null) {
            return provider.isFavoritePrinter(printerId);
        }
        return false;
    }

    public void setTrackedPrinter(@Nullable PrinterId printerId) {
        FusedPrintersProvider provider = getPrinterProvider();
        if (provider != null) {
            provider.setTrackedPrinter(printerId);
        }
    }

    public boolean areHistoricalPrintersLoaded() {
        FusedPrintersProvider provider = getPrinterProvider();
        if (provider != null) {
            return getPrinterProvider().areHistoricalPrintersLoaded();
        }
        return false;
    }

    private FusedPrintersProvider getPrinterProvider() {
        Loader<?> loader = mActivity.getLoaderManager().getLoader(mLoaderId);
        return (FusedPrintersProvider) loader;
    }

    private final LoaderCallbacks<List<PrinterInfo>> mLoaderCallbacks =
            new LoaderCallbacks<List<PrinterInfo>>() {
        @Override
        public void onLoaderReset(Loader<List<PrinterInfo>> loader) {
            mPrinters.clear();

            // Post a message as we are in onLoadFinished and certain operations
            // are not allowed in this callback, such as fragment transactions.
            // Clients should not handle this explicitly.
            mHandler.obtainMessage(MyHandler.MSG_PRINTERS_INVALID).sendToTarget();
        }

        // LoaderCallbacks#onLoadFinished
        @Override
        public void onLoadFinished(Loader<List<PrinterInfo>> loader, List<PrinterInfo> printers) {
            mPrinters.clear();
            mPrinters.addAll(printers);

            // Post a message as we are in onLoadFinished and certain operations
            // are not allowed in this callback, such as fragment transactions.
            // Clients should not handle this explicitly.
            mHandler.obtainMessage(MyHandler.MSG_PRINTERS_CHANGED, printers).sendToTarget();

            if (!mReady) {
                mReady = true;
                if (mReadyCallback != null) {
                    mReadyCallback.run();
                }
            }
        }

        // LoaderCallbacks#onCreateLoader
        @Override
        public Loader<List<PrinterInfo>> onCreateLoader(int id, Bundle args) {
            return new FusedPrintersProvider(mActivity, args.getInt(null));
        }
    };

    private final class MyHandler extends Handler {
        public static final int MSG_PRINTERS_CHANGED = 0;
        public static final int MSG_PRINTERS_INVALID = 1;

        public MyHandler(Looper looper) {
            super(looper, null , false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleMessage(Message message) {
            switch (message.what) {
                case MSG_PRINTERS_CHANGED: {
                    List<PrinterInfo> printers = (List<PrinterInfo>) message.obj;

                    if (mOnPrintersChangeListener != null) {
                        mOnPrintersChangeListener.onPrintersChanged(printers);
                    }
                } break;

                case MSG_PRINTERS_INVALID: {
                    if (mOnPrintersChangeListener != null) {
                        mOnPrintersChangeListener.onPrintersInvalid();
                    }
                } break;
            }
        }
    }
}
