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

import android.annotation.CallbackExecutor;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import com.android.internal.R;
import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;

/**
 * Class that provides a privileged API to capture and consume bugreports.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.BUGREPORT_SERVICE)
public final class BugreportManager {

    private static final String TAG = "BugreportManager";

    private final Context mContext;
    private final IDumpstate mBinder;

    /** @hide */
    public BugreportManager(@NonNull Context context, IDumpstate binder) {
        mContext = context;
        mBinder = binder;
    }

    /**
     * An interface describing the callback for bugreport progress and status.
     */
    public abstract static class BugreportCallback {
        /** @hide */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = { "BUGREPORT_ERROR_" }, value = {
                BUGREPORT_ERROR_INVALID_INPUT,
                BUGREPORT_ERROR_RUNTIME,
                BUGREPORT_ERROR_USER_DENIED_CONSENT,
                BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT,
                BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS
        })

        /** Possible error codes taking a bugreport can encounter */
        public @interface BugreportErrorCode {}

        /** The input options were invalid */
        public static final int BUGREPORT_ERROR_INVALID_INPUT =
                IDumpstateListener.BUGREPORT_ERROR_INVALID_INPUT;

        /** A runtime error occured */
        public static final int BUGREPORT_ERROR_RUNTIME =
                IDumpstateListener.BUGREPORT_ERROR_RUNTIME_ERROR;

        /** User denied consent to share the bugreport */
        public static final int BUGREPORT_ERROR_USER_DENIED_CONSENT =
                IDumpstateListener.BUGREPORT_ERROR_USER_DENIED_CONSENT;

        /** The request to get user consent timed out. */
        public static final int BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT =
                IDumpstateListener.BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT;

        /** There is currently a bugreport running. The caller should try again later. */
        public static final int BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS =
                IDumpstateListener.BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS;

        /**
         * Called when there is a progress update.
         * @param progress the progress in [0.0, 100.0]
         */
        public void onProgress(@FloatRange(from = 0f, to = 100f) float progress) {}

        /**
         * Called when taking bugreport resulted in an error.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_DENIED_CONSENT} is passed, then the user did not
         * consent to sharing the bugreport with the calling app.
         *
         * <p>If {@code BUGREPORT_ERROR_USER_CONSENT_TIMED_OUT} is passed, then the consent timed
         * out, but the bugreport could be available in the internal directory of dumpstate for
         * manual retrieval.
         *
         * <p> If {@code BUGREPORT_ERROR_ANOTHER_REPORT_IN_PROGRESS} is passed, then the
         * caller should try later, as only one bugreport can be in progress at a time.
         */
        public void onError(@BugreportErrorCode int errorCode) {}

        /**
         * Called when taking bugreport finishes successfully.
         */
        public void onFinished() {}
    }

    /**
     * Starts a bugreport.
     *
     * <p>This starts a bugreport in the background. However the call itself can take several
     * seconds to return in the worst case. {@code callback} will receive progress and status
     * updates.
     *
     * <p>The bugreport artifacts will be copied over to the given file descriptors only if the
     * user consents to sharing with the calling app.
     *
     * <p>{@link BugreportManager} takes ownership of {@code bugreportFd} and {@code screenshotFd}.
     *
     * @param bugreportFd file to write the bugreport. This should be opened in write-only,
     *     append mode.
     * @param screenshotFd file to write the screenshot, if necessary. This should be opened
     *     in write-only, append mode.
     * @param params options that specify what kind of a bugreport should be taken
     * @param callback callback for progress and status updates
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void startBugreport(@NonNull ParcelFileDescriptor bugreportFd,
            @Nullable ParcelFileDescriptor screenshotFd,
            @NonNull BugreportParams params,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BugreportCallback callback) {
        try {
            Preconditions.checkNotNull(bugreportFd);
            Preconditions.checkNotNull(params);
            Preconditions.checkNotNull(executor);
            Preconditions.checkNotNull(callback);

            boolean isScreenshotRequested = screenshotFd != null;
            if (screenshotFd == null) {
                // Binder needs a valid File Descriptor to be passed
                screenshotFd = ParcelFileDescriptor.open(new File("/dev/null"),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            }
            DumpstateListener dsListener = new DumpstateListener(executor, callback,
                    isScreenshotRequested);
            // Note: mBinder can get callingUid from the binder transaction.
            mBinder.startBugreport(-1 /* callingUid */,
                    mContext.getOpPackageName(),
                    bugreportFd.getFileDescriptor(),
                    screenshotFd.getFileDescriptor(),
                    params.getMode(), dsListener, isScreenshotRequested);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (FileNotFoundException e) {
            Log.wtf(TAG, "Not able to find /dev/null file: ", e);
        } finally {
            // We can close the file descriptors here because binder would have duped them.
            IoUtils.closeQuietly(bugreportFd);
            if (screenshotFd != null) {
                IoUtils.closeQuietly(screenshotFd);
            }
        }
    }

    /*
     * Cancels a currently running bugreport.
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void cancelBugreport() {
        try {
            mBinder.cancelBugreport();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests a bugreport.
     *
     * <p>This requests the platform/system to take a bugreport and makes the final bugreport
     * available to the user. The user may choose to share it with another app, but the bugreport
     * is never given back directly to the app that requested it.
     *
     * @param params           {@link BugreportParams} that specify what kind of a bugreport should
     *                         be taken, please note that not all kinds of bugreport allow for a
     *                         progress notification
     * @param shareTitle       title on the final share notification
     * @param shareDescription description on the final share notification
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void requestBugreport(@NonNull BugreportParams params, @Nullable CharSequence shareTitle,
            @Nullable CharSequence shareDescription) {
        try {
            String title = shareTitle == null ? null : shareTitle.toString();
            String description = shareDescription == null ? null : shareDescription.toString();
            ActivityManager.getService().requestBugReportWithDescription(title, description,
                    params.getMode());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class DumpstateListener extends IDumpstateListener.Stub {
        private final Executor mExecutor;
        private final BugreportCallback mCallback;
        private final boolean mIsScreenshotRequested;

        DumpstateListener(Executor executor, BugreportCallback callback,
                boolean isScreenshotRequested) {
            mExecutor = executor;
            mCallback = callback;
            mIsScreenshotRequested = isScreenshotRequested;
        }

        @Override
        public void onProgress(int progress) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onProgress(progress);
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onError(int errorCode) throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onError(errorCode);
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onFinished() throws RemoteException {
            final long identity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> {
                    mCallback.onFinished();
                });
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void onScreenshotTaken(boolean success) throws RemoteException {
            if (!mIsScreenshotRequested) {
                return;
            }

            Handler mainThreadHandler = new Handler(Looper.getMainLooper());
            mainThreadHandler.post(
                    () -> {
                        int message = success ? R.string.bugreport_screenshot_success_toast
                                : R.string.bugreport_screenshot_failure_toast;
                        Toast.makeText(mContext, message, Toast.LENGTH_LONG).show();
                    });
        }
    }
}
