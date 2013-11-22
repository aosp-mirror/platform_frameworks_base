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

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

/**
 * Base class that provides the content of a document to be printed.
 *
 * <h3>Lifecycle</h3>
 * <p>
 * <ul>
 * <li>
 * Initially, you will receive a call to {@link #onStart()}. This callback
 * can be used to allocate resources.
 * </li>
 * <li>
 * Next, you will get one or more calls to {@link #onLayout(PrintAttributes,
 * PrintAttributes, CancellationSignal, LayoutResultCallback, Bundle)} to
 * inform you that the print attributes (page size, density, etc) changed
 * giving you an opportunity to layout the content to match the new constraints.
 * </li>
 * <li>
 * After every call to {@link #onLayout(PrintAttributes, PrintAttributes,
 * CancellationSignal, LayoutResultCallback, Bundle)}, you may get a call to
 * {@link #onWrite(PageRange[], ParcelFileDescriptor, CancellationSignal, WriteResultCallback)}
 * asking you to write a PDF file with the content for specific pages.
 * </li>
 * <li>
 * Finally, you will receive a call to {@link #onFinish()}. You can use this
 * callback to release resources allocated in {@link #onStart()}.
 * </li>
 * </ul>
 * </p>
 * <h3>Implementation</h3>
 * <p>
 * The APIs defined in this class are designed to enable doing part or all
 * of the work on an arbitrary thread. For example, if the printed content
 * does not depend on the UI state, i.e. on what is shown on the screen, then
 * you can offload the entire work on a dedicated thread, thus making your
 * application interactive while the print work is being performed.
 * </p>
 * <p>
 * You can also do work on different threads, for example if you print UI
 * content, you can handle {@link #onStart()} and {@link #onLayout(PrintAttributes,
 * PrintAttributes, CancellationSignal, LayoutResultCallback, Bundle)} on
 * the UI thread (assuming onStart initializes resources needed for layout).
 * This will ensure that the UI does not change while you are laying out the
 * printed content. Then you can handle {@link #onWrite(PageRange[], ParcelFileDescriptor,
 * CancellationSignal, WriteResultCallback)} and {@link #onFinish()} on another
 * thread. This will ensure that the UI is frozen for the minimal amount of
 * time. Also this assumes that you will generate the printed content in
 * {@link #onLayout(PrintAttributes, PrintAttributes, CancellationSignal,
 * LayoutResultCallback, Bundle)} which is not mandatory. If you use multiple
 * threads, you are responsible for proper synchronization.
 * </p>
 */
public abstract class PrintDocumentAdapter {

    /**
     * Extra: mapped to a boolean value that is <code>true</code> if
     * the current layout is for a print preview, <code>false</code> otherwise.
     */
    public static final String EXTRA_PRINT_PREVIEW = "EXTRA_PRINT_PREVIEW";

    /**
     * Called when printing starts. You can use this callback to allocate
     * resources. This method is invoked on the main thread.
     */
    public void onStart() {
        /* do nothing - stub */
    }

    /**
     * Called when the print attributes (page size, density, etc) changed
     * giving you a chance to layout the content such that it matches the
     * new constraints. This method is invoked on the main thread.
     * <p>
     * After you are done laying out, you <strong>must</strong> invoke: {@link
     * LayoutResultCallback#onLayoutFinished(PrintDocumentInfo, boolean)} with
     * the last argument <code>true</code> or <code>false</code> depending on
     * whether the layout changed the content or not, respectively; and {@link
     * LayoutResultCallback#onLayoutFailed(CharSequence)}, if an error occurred.
     * Note that you must call one of the methods of the given callback.
     * </p>
     * <p>
     * <strong>Note:</strong> If the content is large and a layout will be
     * performed, it is a good practice to schedule the work on a dedicated
     * thread and register an observer in the provided {@link
     * CancellationSignal} upon invocation of which you should stop the
     * layout. The cancellation callback will not be made on the main
     * thread.
     * </p>
     *
     * @param oldAttributes The old print attributes.
     * @param newAttributes The new print attributes.
     * @param cancellationSignal Signal for observing cancel layout requests.
     * @param callback Callback to inform the system for the layout result.
     * @param extras Additional information about how to layout the content.
     *
     * @see LayoutResultCallback
     * @see CancellationSignal
     * @see #EXTRA_PRINT_PREVIEW
     */
    public abstract void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
            CancellationSignal cancellationSignal, LayoutResultCallback callback,
            Bundle extras);

    /**
     * Called when specific pages of the content should be written in the
     * form of a PDF file to the given file descriptor. This method is invoked
     * on the main thread.
     *<p>
     * After you are done writing, you should close the file descriptor and
     * invoke {@link WriteResultCallback #onWriteFinished(PageRange[]), if writing
     * completed successfully; or {@link WriteResultCallback#onWriteFailed(
     * CharSequence)}, if an error occurred. Note that you must call one of
     * the methods of the given callback.
     * </p>
     * <p>
     * <strong>Note:</strong> If the printed content is large, it is a good
     * practice to schedule writing it on a dedicated thread and register an
     * observer in the provided {@link CancellationSignal} upon invocation of
     * which you should stop writing. The cancellation callback will not be
     * made on the main thread.
     * </p>
     *
     * @param pages The pages whose content to print - non-overlapping in ascending order.
     * @param destination The destination file descriptor to which to write.
     * @param cancellationSignal Signal for observing cancel writing requests.
     * @param callback Callback to inform the system for the write result.
     *
     * @see WriteResultCallback
     * @see CancellationSignal
     */
    public abstract void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
            CancellationSignal cancellationSignal, WriteResultCallback callback);

    /**
     * Called when printing finishes. You can use this callback to release
     * resources acquired in {@link #onStart()}. This method is invoked on
     * the main thread.
     */
    public void onFinish() {
        /* do nothing - stub */
    }

    /**
     * Base class for implementing a callback for the result of {@link
     * PrintDocumentAdapter#onWrite(PageRange[], ParcelFileDescriptor, CancellationSignal,
     * WriteResultCallback)}.
     */
    public static abstract class WriteResultCallback {

        /**
         * @hide
         */
        public WriteResultCallback() {
            /* do nothing - hide constructor */
        }

        /**
         * Notifies that all the data was written.
         *
         * @param pages The pages that were written. Cannot be null or empty.
         */
        public void onWriteFinished(PageRange[] pages) {
            /* do nothing - stub */
        }

        /**
         * Notifies that an error occurred while writing the data.
         *
         * @param error Error message. May be null if error is unknown.
         */
        public void onWriteFailed(CharSequence error) {
            /* do nothing - stub */
        }

        /**
         * Notifies that write was cancelled as a result of a cancellation request.
         */
        public void onWriteCancelled() {
            /* do nothing - stub */
        }
    }

    /**
     * Base class for implementing a callback for the result of {@link
     * PrintDocumentAdapter#onLayout(PrintAttributes, PrintAttributes,
     * CancellationSignal, LayoutResultCallback, Bundle)}.
     */
    public static abstract class LayoutResultCallback {

        /**
         * @hide
         */
        public LayoutResultCallback() {
            /* do nothing - hide constructor */
        }

        /**
         * Notifies that the layout finished and whether the content changed.
         *
         * @param info An info object describing the document. Cannot be null.
         * @param changed Whether the layout changed.
         *
         * @see PrintDocumentInfo
         */
        public void onLayoutFinished(PrintDocumentInfo info, boolean changed) {
            /* do nothing - stub */
        }

        /**
         * Notifies that an error occurred while laying out the document.
         *
         * @param error Error message. May be null if error is unknown.
         */
        public void onLayoutFailed(CharSequence error) {
            /* do nothing - stub */
        }

        /**
         * Notifies that layout was cancelled as a result of a cancellation request.
         */
        public void onLayoutCancelled() {
            /* do nothing - stub */
        }
    }
}
