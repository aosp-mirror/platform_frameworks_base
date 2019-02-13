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
package android.content;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * This class contains methods and constants used to start DynamicAndroid
 * installation, and a listener for progress update.
 * @hide
 */
@SystemApi
public class DynamicAndroidClient {
    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_NOT_STARTED,
            STATUS_IN_PROGRESS,
            STATUS_READY,
            STATUS_IN_USE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InstallationStatus {}

    /** @hide */
    @IntDef(prefix = { "CAUSE_" }, value = {
            CAUSE_NOT_SPECIFIED,
            CAUSE_INSTALL_COMPLETED,
            CAUSE_INSTALL_CANCELLED,
            CAUSE_ERROR_IO,
            CAUSE_ERROR_INVALID_URL,
            CAUSE_ERROR_IPC,
            CAUSE_ERROR_EXCEPTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StatusChangedCause {}

    private static final String TAG = "DynAndroidClient";

    private static final long DEFAULT_USERDATA_SIZE = (10L << 30);


    /** Listener for installation status update. */
    public interface OnStatusChangedListener {
        /**
         * This callback is called when installation status is changed, and when the
         * client is {@link #bind} to DynamicAndroid installation service.
         *
         * @param status status code, also defined in {@code DynamicAndroidClient}.
         * @param cause cause code, also defined in {@code DynamicAndroidClient}.
         * @param progress number of bytes installed.
         */
        void onStatusChanged(@InstallationStatus int status, @StatusChangedCause int cause,
                long progress);
    }

    /*
     * Status codes
     */
    /** We are bound to installation service, but failed to get its status */
    public static final int STATUS_UNKNOWN = 0;

    /** Installation is not started yet. */
    public static final int STATUS_NOT_STARTED = 1;

    /** Installation is in progress. */
    public static final int STATUS_IN_PROGRESS = 2;

    /** Installation is finished but the user has not launched it. */
    public static final int STATUS_READY = 3;

    /** Device is running in Dynamic Android. */
    public static final int STATUS_IN_USE = 4;

    /*
     * Causes
     */
    /** Cause is not specified. This means the status is not changed. */
    public static final int CAUSE_NOT_SPECIFIED = 0;

    /** Status changed because installation is completed. */
    public static final int CAUSE_INSTALL_COMPLETED = 1;

    /** Status changed because installation is cancelled. */
    public static final int CAUSE_INSTALL_CANCELLED = 2;

    /** Installation failed due to IOException. */
    public static final int CAUSE_ERROR_IO = 3;

    /** Installation failed because the image URL source is not supported. */
    public static final int CAUSE_ERROR_INVALID_URL = 4;

    /** Installation failed due to IPC error. */
    public static final int CAUSE_ERROR_IPC = 5;

    /** Installation failed due to unhandled exception. */
    public static final int CAUSE_ERROR_EXCEPTION = 6;

    /*
     * IPC Messages
     */
    /**
     * Message to register listener.
     * @hide
     */
    public static final int MSG_REGISTER_LISTENER = 1;

    /**
     * Message to unregister listener.
     * @hide
     */
    public static final int MSG_UNREGISTER_LISTENER = 2;

    /**
     * Message for status update.
     * @hide
     */
    public static final int MSG_POST_STATUS = 3;

    /*
     * Messages keys
     */
    /**
     * Message key, for progress update.
     * @hide
     */
    public static final String KEY_INSTALLED_SIZE = "KEY_INSTALLED_SIZE";

    /*
     * Intent Actions
     */
    /**
     * Intent action: start installation.
     * @hide
     */
    public static final String ACTION_START_INSTALL =
            "android.content.action.START_INSTALL";

    /**
     * Intent action: notify user if we are currently running in Dynamic Android.
     * @hide
     */
    public static final String ACTION_NOTIFY_IF_IN_USE =
            "android.content.action.NOTIFY_IF_IN_USE";

    /*
     * Intent Keys
     */
    /**
     * Intent key: URL to system image.
     * @hide
     */
    public static final String KEY_SYSTEM_URL = "KEY_SYSTEM_URL";

    /**
     * Intent key: Size of system image, in bytes.
     * @hide
     */
    public static final String KEY_SYSTEM_SIZE = "KEY_SYSTEM_SIZE";

    /**
     * Intent key: Number of bytes to reserve for userdata.
     * @hide
     */
    public static final String KEY_USERDATA_SIZE = "KEY_USERDATA_SIZE";


    private static class IncomingHandler extends Handler {
        private final WeakReference<DynamicAndroidClient> mWeakClient;

        IncomingHandler(DynamicAndroidClient service) {
            super(Looper.getMainLooper());
            mWeakClient = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            DynamicAndroidClient service = mWeakClient.get();

            if (service != null) {
                service.handleMessage(msg);
            }
        }
    }

    private class DynAndroidServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Slog.v(TAG, "DynAndroidService connected");

            mService = new Messenger(service);

            try {
                Message msg = Message.obtain(null, MSG_REGISTER_LISTENER);
                msg.replyTo = mMessenger;

                mService.send(msg);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get status from installation service");
                mExecutor.execute(() -> {
                    mListener.onStatusChanged(STATUS_UNKNOWN, CAUSE_ERROR_IPC, 0);
                });
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Slog.v(TAG, "DynAndroidService disconnected");
            mService = null;
        }
    }

    private final Context mContext;
    private final DynAndroidServiceConnection mConnection;
    private final Messenger mMessenger;

    private boolean mBound;
    private Executor mExecutor;
    private OnStatusChangedListener mListener;
    private Messenger mService;

    /**
     * @hide
     */
    @SystemApi
    public DynamicAndroidClient(@NonNull Context context) {
        mContext = context;
        mConnection = new DynAndroidServiceConnection();
        mMessenger = new Messenger(new IncomingHandler(this));
    }

    /**
     * This method register a listener for status change. The listener is called using
     * the executor.
     */
    public void setOnStatusChangedListener(
            @NonNull OnStatusChangedListener listener,
            @NonNull @CallbackExecutor Executor executor) {
        mListener = listener;
        mExecutor = executor;
    }

    /**
     * This method register a listener for status change. The listener is called in main
     * thread.
     */
    public void setOnStatusChangedListener(
            @NonNull OnStatusChangedListener listener) {
        mListener = listener;
        mExecutor = null;
    }

    /**
     * Bind to DynamicAndroidInstallationService.
     */
    public void bind() {
        Intent intent = new Intent();
        intent.setClassName("com.android.dynandroid",
                "com.android.dynandroid.DynamicAndroidInstallationService");

        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        mBound = true;
    }

    /**
     * Unbind from DynamicAndroidInstallationService.
     */
    public void unbind() {
        if (!mBound) {
            return;
        }

        if (mService != null) {
            try {
                Message msg = Message.obtain(null, MSG_UNREGISTER_LISTENER);
                msg.replyTo = mMessenger;
                mService.send(msg);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to unregister from installation service");
            }
        }

        // Detach our existing connection.
        mContext.unbindService(mConnection);

        mBound = false;
    }

    /**
     * Start installing DynamicAndroid from URL with default userdata size.
     *
     * @param systemUrl A network URL or a file URL to system image.
     * @param systemSize size of system image.
     */
    public void start(String systemUrl, long systemSize) {
        start(systemUrl, systemSize, DEFAULT_USERDATA_SIZE);
    }

    /**
     * Start installing DynamicAndroid from URL.
     *
     * @param systemUrl A network URL or a file URL to system image.
     * @param systemSize size of system image.
     * @param userdataSize bytes reserved for userdata.
     */
    public void start(String systemUrl, long systemSize, long userdataSize) {
        Intent intent = new Intent();

        intent.setClassName("com.android.dynandroid",
                "com.android.dynandroid.VerificationActivity");

        intent.setAction(ACTION_START_INSTALL);

        intent.putExtra(KEY_SYSTEM_URL, systemUrl);
        intent.putExtra(KEY_SYSTEM_SIZE, systemSize);
        intent.putExtra(KEY_USERDATA_SIZE, userdataSize);

        mContext.startActivity(intent);
    }

    private void handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_POST_STATUS:
                int status = msg.arg1;
                int cause = msg.arg2;
                // obj is non-null
                long progress = ((Bundle) msg.obj).getLong(KEY_INSTALLED_SIZE);

                if (mExecutor != null) {
                    mExecutor.execute(() -> {
                        mListener.onStatusChanged(status, cause, progress);
                    });
                } else {
                    mListener.onStatusChanged(status, cause, progress);
                }
                break;
            default:
                // do nothing

        }
    }
}
