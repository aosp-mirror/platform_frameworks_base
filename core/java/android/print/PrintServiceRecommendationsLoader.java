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
import android.printservice.recommendation.RecommendationInfo;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Loader for the list of print service recommendations.
 *
 * @hide
 */
public class PrintServiceRecommendationsLoader extends Loader<List<RecommendationInfo>> {
    /** The print manager to be used by this object */
    private final @NonNull PrintManager mPrintManager;

    /** Handler to sequentialize the delivery of the results to the main thread */
    private final @NonNull Handler mHandler;

    /** Listens for updates to the data from the platform */
    private PrintManager.PrintServiceRecommendationsChangeListener mListener;

    /**
     * Create a new PrintServicesLoader.
     *
     * @param printManager The print manager supplying the data
     * @param context      Context of the using object
     */
    public PrintServiceRecommendationsLoader(@NonNull PrintManager printManager,
            @NonNull Context context) {
        super(Preconditions.checkNotNull(context));
        mHandler = new MyHandler();
        mPrintManager = Preconditions.checkNotNull(printManager);
    }

    @Override
    protected void onForceLoad() {
        queueNewResult();
    }

    /**
     * Read the print service recommendations and queue it to be delivered on the main thread.
     */
    private void queueNewResult() {
        Message m = mHandler.obtainMessage(0);
        m.obj = mPrintManager.getPrintServiceRecommendations();
        mHandler.sendMessage(m);
    }

    @Override
    protected void onStartLoading() {
        mListener = new PrintManager.PrintServiceRecommendationsChangeListener() {
            @Override
            public void onPrintServiceRecommendationsChanged() {
                queueNewResult();
            }
        };

        mPrintManager.addPrintServiceRecommendationsChangeListener(mListener);

        // Immediately deliver a result
        deliverResult(mPrintManager.getPrintServiceRecommendations());
    }

    @Override
    protected void onStopLoading() {
        if (mListener != null) {
            mPrintManager.removePrintServiceRecommendationsChangeListener(mListener);
            mListener = null;
        }

        mHandler.removeMessages(0);
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
            if (isStarted()) {
                deliverResult((List<RecommendationInfo>) msg.obj);
            }
        }
    }
}
