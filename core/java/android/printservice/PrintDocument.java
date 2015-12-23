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
import android.annotation.Nullable;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.print.PrintDocumentInfo;
import android.print.PrintJobId;
import android.util.Log;

import java.io.IOException;

/**
 * This class represents a printed document from the perspective of a print
 * service. It exposes APIs to query the document and obtain its data.
 * <p>
 * <strong>Note: </strong> All methods of this class must be executed on the
 * main application thread.
 * </p>
 */
public final class PrintDocument {

    private static final String LOG_TAG = "PrintDocument";

    private final PrintJobId mPrintJobId;

    private final IPrintServiceClient mPrintServiceClient;

    private final PrintDocumentInfo mInfo;

    PrintDocument(PrintJobId printJobId, IPrintServiceClient printServiceClient,
            PrintDocumentInfo info) {
        mPrintJobId = printJobId;
        mPrintServiceClient = printServiceClient;
        mInfo = info;
    }

    /**
     * Gets the {@link PrintDocumentInfo} that describes this document.
     *
     * @return The document info.
     */
    public @NonNull PrintDocumentInfo getInfo() {
        PrintService.throwIfNotCalledOnMainThread();
        return mInfo;
    }

    /**
     * Gets the data associated with this document.
     * <p>
     * <strong>Note: </strong> It is a responsibility of the client to open a
     * stream to the returned file descriptor, fully read the data, and close
     * the file descriptor.
     * </p>
     *
     * @return A file descriptor for reading the data.
     */
    public @Nullable ParcelFileDescriptor getData() {
        PrintService.throwIfNotCalledOnMainThread();
        ParcelFileDescriptor source = null;
        ParcelFileDescriptor sink = null;
        try {
            ParcelFileDescriptor[] fds = ParcelFileDescriptor.createPipe();
            source = fds[0];
            sink = fds[1];
            mPrintServiceClient.writePrintJobData(sink, mPrintJobId);
            return source;
        } catch (IOException ioe) {
            Log.e(LOG_TAG, "Error calling getting print job data!", ioe);
        } catch (RemoteException re) {
            Log.e(LOG_TAG, "Error calling getting print job data!", re);
        } finally {
            if (sink != null) {
                try {
                    sink.close();
                } catch (IOException ioe) {
                    /* ignore */
                }
            }
        }
        return null;
    }
}
