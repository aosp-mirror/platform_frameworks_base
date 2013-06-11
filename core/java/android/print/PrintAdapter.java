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

package android.print;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.List;

import android.os.CancellationSignal;

/**
 * Base class that provides data to be printed.
 *
 * <h3>Lifecycle</h3>
 * <p>
 * <ul>
 * <li>
 * You will receive a call on {@link #onStart()} when printing starts.
 * This callback can be used to allocate resources.
 * </li>
 * <li>
 * Next you will get one or more calls to the pair
 *  {@link #onPrintAttributesChanged(PrintAttributes)} and {@link #onPrint(List,
 * FileDescriptor, CancellationSignal, PrintProgressCallback)}. The first callback
 * informs you that the print attributes (page size, density, etc) changed giving
 * you an opportunity to re-layout the content. The second method asks you to write
 * a PDF file with the content for specific pages.
 * </li>
 * <li>
 * Finally, you will receive a call on {@link #onFinish()} right after printing.
 * You can use this callback to release resources.
 * </li>
 * <li>
 * You can receive calls to {@link #getInfo()} at any point which should return
 * a {@link PrintAdapterInfo} describing your {@link PrintAdapter}.
 * </li>
 * </ul>
 * </p>
 * <p>
 */
public abstract class PrintAdapter {

    /**
     * Called when printing started. You can use this callback to
     * allocate resources.
     * <p>
     * <strong>Note:</strong> Invoked on the main thread.
     * </p>
     */
    public void onStart() {
        /* do nothing - stub */
    }

    /**
     * Called when the print job attributes (page size, density, etc)
     * changed giving you a chance to re-layout the content such that
     * it matches the new constraints.
     * <p>
     * <strong>Note:</strong> Invoked on the main thread.
     * </p>
     *
     * @param attributes The print job attributes.
     * @return Whether the content changed based on the provided attributes.
     */
    public boolean onPrintAttributesChanged(PrintAttributes attributes) {
        return false;
    }

    /**
     * Called when specific pages of the content have to be printed in the from of
     * a PDF file to the given file descriptor. You should <strong>not</strong>
     * close the file descriptor instead you have to invoke {@link PrintProgressCallback
     * #onWriteFinished()} or {@link PrintProgressCallback#onPrintFailed(CharSequence)}.
     * <p>
     * <strong>Note:</strong> If the printed content is large, it is a  good
     * practice to schedule writing it on a dedicated thread and register a
     * callback in the provided {@link CancellationSignal} upon which to stop
     * writing data. The cancellation callback will not be made on the main
     * thread.
     * </p>
     * <p>
     * <strong>Note:</strong> Invoked on the main thread.
     * </p>
     * <p>
     *
     * @param pages The pages whose content to write.
     * @param destination The destination file descriptor to which to start writing.
     * @param cancellationSignal Signal for observing cancel write requests.
     * @param progressListener Callback to inform the system with the write progress.
     *
     * @see CancellationSignal
     */
    public abstract void onPrint(List<PageRange> pages, FileDescriptor destination,
            CancellationSignal cancellationSignal, PrintProgressCallback progressListener);

    /**
     * Called when printing finished. You can use this callback to release
     * resources.
     * <p>
     * <strong>Note:</strong> Invoked on the main thread.
     * </p>
     */
    public void onFinish() {
        /* do nothing - stub */
    }

    /**
     * Gets a {@link PrinterInfo} object that contains metadata about the
     * printed content.
     * <p>
     * <strong>Note:</strong> Invoked on the main thread.
     * </p>
     *
     * @return The info object for this {@link PrintAdapter}.
     *
     * @see PrintAdapterInfo
     */
    public abstract PrintAdapterInfo getInfo();

    /**
     * Base class for implementing a listener for the printing progress
     * of a {@link PrintAdapter}.
     */
    public static abstract class PrintProgressCallback {

        PrintProgressCallback() {
            /* do nothing - hide constructor */
        }

        /**
         * Notifies that all the data was printed.
         *
         * @param pages The pages that were printed.
         */
        public void onPrintFinished(List<PageRange> pages) {
            /* do nothing - stub */
        }

        /**
         * Notifies that an error occurred while printing the data.
         *
         * @param error Error message. May be null if error is unknown.
         */
        public void onPrintFailed(CharSequence error) {
            /* do nothing - stub */
        }
    }
}
