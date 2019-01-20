/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder.DeathRecipient;

import java.io.FileDescriptor;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Class that provides a privileged API to capture and consume bugreports.
 *
 * @hide
 */
// TODO: Expose API when the implementation is more complete.
// @SystemApi
@SystemService(Context.BUGREPORT_SERVICE)
public class BugreportManager {
    private final Context mContext;
    private final IDumpstate mBinder;

    /** @hide */
    public BugreportManager(@NonNull Context context, IDumpstate binder) {
        mContext = context;
        mBinder = binder;
    }

    /**
     * An interface describing the listener for bugreport progress and status.
     */
    public interface BugreportListener {
        /**
         * Called when there is a progress update.
         * @param progress the progress in [0.0, 100.0]
         */
        void onProgress(float progress);

        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = { "BUGREPORT_ERROR_" }, value = {
                BUGREPORT_ERROR_INVALID_INPUT,
                BUGREPORT_ERROR_RUNTIME
        })

        /** Possible error codes taking a bugreport can encounter */
        @interface BugreportErrorCode {}

        /** The input options were invalid */
        int BUGREPORT_ERROR_INVALID_INPUT = IDumpstateListener.BUGREPORT_ERROR_INVALID_INPUT;

        /** A runtime error occured */
        int BUGREPORT_ERROR_RUNTIME = IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR;

        /** User denied consent to share the bugreport */
        int BUGREPORT_ERROR_USER_DENIED_CONSENT =
                IDumpstateListener.BUGREPORT_ERROR_USER_DENIED_CONSENT;

        /**
         * Called when taking bugreport resulted in an error.
         *
         * @param errorCode the error that occurred. Possible values are
         *     {@code BUGREPORT_ERROR_INVALID_INPUT}, {@code BUGREPORT_ERROR_RUNTIME}.
         */
        void onError(@BugreportErrorCode int errorCode);

        /**
         * Called when taking bugreport finishes successfully
         *
         * @param durationMs time capturing bugreport took in milliseconds
         * @param title title for the bugreport; helpful in reminding the user why they took it
         * @param description detailed description for the bugreport
         */
        void onFinished(long durationMs, @NonNull String title,
                @NonNull String description);
    }

    /**
     * Starts a bugreport asynchronously.
     *
     * @param bugreportFd file to write the bugreport. This should be opened in write-only,
     *     append mode.
     * @param screenshotFd file to write the screenshot, if necessary. This should be opened
     *     in write-only, append mode.
     * @param params options that specify what kind of a bugreport should be taken
     * @param listener callback for progress and status updates
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(@NonNull FileDescriptor bugreportFd,
            @Nullable FileDescriptor screenshotFd,
            @NonNull BugreportParams params, @Nullable BugreportListener listener) {
        // TODO(b/111441001): Enforce android.Manifest.permission.DUMP if necessary.
        DumpstateListener dsListener = new DumpstateListener(listener);

        try {
            // Note: mBinder can get callingUid from the binder transaction.
            mBinder.startBugreport(-1 /* callingUid */,
                    mContext.getOpPackageName(), bugreportFd, screenshotFd,
                    params.getMode(), dsListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class DumpstateListener extends IDumpstateListener.Stub
            implements DeathRecipient {
        private final BugreportListener mListener;

        DumpstateListener(@Nullable BugreportListener listener) {
            mListener = listener;
        }

        @Override
        public void binderDied() {
            // TODO(b/111441001): implement
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            mListener.onProgress(progress);
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            mListener.onError(errorCode);
        }

        @Override
        public void onFinished(long durationMs, String title, String description)
                throws RemoteException {
            mListener.onFinished(durationMs, title, description);
        }

        // Old methods; should go away
        @Override
        public void onProgressUpdated(int progress) throws RemoteException {
            // TODO(b/111441001): remove from interface
        }

        @Override
        public void onMaxProgressUpdated(int maxProgress) throws RemoteException {
            // TODO(b/111441001): remove from interface
        }

        @Override
        public void onSectionComplete(String title, int status, int size, int durationMs)
                throws RemoteException {
            // TODO(b/111441001): remove from interface
        }
    }
}
