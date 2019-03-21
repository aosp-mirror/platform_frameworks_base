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
package android.os.image;

import android.annotation.BytesLong;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.util.Slog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * <p>This class contains methods and constants used to start a {@code DynamicSystem} installation,
 * and a listener for status updates.</p>
 *
 * <p>{@code DynamicSystem} allows users to run certified system images in a non destructive manner
 * without needing to prior OEM unlock. It creates a temporary system partition to install the new
 * system image, and a temporary data partition for the newly installed system to run with.</p>
 *
 * After the installation is completed, the device will be running in the new system on next the
 * reboot. Then, when the user reboots the device again, it will leave {@code DynamicSystem} and go
 * back to the original system. While running in {@code DynamicSystem}, persitent storage for
 * factory reset protection (FRP) remains unchanged. Since the user is running the new system with
 * a temporarily created data partition, their original user data are kept unchanged.</p>
 *
 * <p>With {@link #setOnStatusChangedListener}, API users can register an
 * {@link #OnStatusChangedListener} to get status updates and their causes when the installation is
 * started, stopped, or cancelled. It also sends progress updates during the installation. With
 * {@link #start}, API users can start an installation with the {@link Uri} to a unsparsed and
 * gzipped system image. The {@link Uri} can be a web URL or a content Uri to a local path.</p>
 *
 * @hide
 */
@SystemApi
public class DynamicSystemClient {
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

    private static final String TAG = "DynSystemClient";

    private static final long DEFAULT_USERDATA_SIZE = (10L << 30);


    /** Listener for installation status updates. */
    public interface OnStatusChangedListener {
        /**
         * This callback is called when installation status is changed, and when the
         * client is {@link #bind} to {@code DynamicSystem} installation service.
         *
         * @param status status code, also defined in {@code DynamicSystemClient}.
         * @param cause cause code, also defined in {@code DynamicSystemClient}.
         * @param progress number of bytes installed.
         * @param detail additional detail about the error if available, otherwise null.
         */
        void onStatusChanged(@InstallationStatus int status, @StatusChangedCause int cause,
                @BytesLong long progress, @Nullable Throwable detail);
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

    /** Device is running in {@code DynamicSystem}. */
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

    /** Installation failed due to {@code IOException}. */
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
     * Message for status updates.
     * @hide
     */
    public static final int MSG_POST_STATUS = 3;

    /*
     * Messages keys
     */
    /**
     * Message key, for progress updates.
     * @hide
     */
    public static final String KEY_INSTALLED_SIZE = "KEY_INSTALLED_SIZE";

    /**
     * Message key, used when the service is sending exception detail to the client.
     * @hide
     */
    public static final String KEY_EXCEPTION_DETAIL = "KEY_EXCEPTION_DETAIL";

    /*
     * Intent Actions
     */
    /**
     * Intent action: start installation.
     * @hide
     */
    public static final String ACTION_START_INSTALL =
            "android.os.image.action.START_INSTALL";

    /**
     * Intent action: notify user if we are currently running in {@code DynamicSystem}.
     * @hide
     */
    public static final String ACTION_NOTIFY_IF_IN_USE =
            "android.os.image.action.NOTIFY_IF_IN_USE";

    /*
     * Intent Keys
     */
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
        private final WeakReference<DynamicSystemClient> mWeakClient;

        IncomingHandler(DynamicSystemClient service) {
            super(Looper.getMainLooper());
            mWeakClient = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            DynamicSystemClient service = mWeakClient.get();

            if (service != null) {
                service.handleMessage(msg);
            }
        }
    }

    private class DynSystemServiceConnection implements ServiceConnection {
        public void onServiceConnected(ComponentName className, IBinder service) {
            Slog.v(TAG, "DynSystemService connected");

            mService = new Messenger(service);

            try {
                Message msg = Message.obtain(null, MSG_REGISTER_LISTENER);
                msg.replyTo = mMessenger;

                mService.send(msg);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get status from installation service");
                mExecutor.execute(() -> {
                    mListener.onStatusChanged(STATUS_UNKNOWN, CAUSE_ERROR_IPC, 0, e);
                });
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Slog.v(TAG, "DynSystemService disconnected");
            mService = null;
        }
    }

    private final Context mContext;
    private final DynSystemServiceConnection mConnection;
    private final Messenger mMessenger;

    private boolean mBound;
    private Executor mExecutor;
    private OnStatusChangedListener mListener;
    private Messenger mService;

    /**
     * Create a new {@code DynamicSystem} client.
     *
     * @param context a {@link Context} will be used to bind the installation service.
     *
     * @hide
     */
    @SystemApi
    public DynamicSystemClient(@NonNull Context context) {
        mContext = context;
        mConnection = new DynSystemServiceConnection();
        mMessenger = new Messenger(new IncomingHandler(this));
    }

    /**
     * This method register a listener for status change. The listener is called using
     * the executor.
     */
    public void setOnStatusChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnStatusChangedListener listener) {
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
     * Bind to {@code DynamicSystem} installation service. Binding to the installation service
     * allows it to send status updates to {@link #OnStatusChangedListener}. It is recommanded
     * to bind before calling {@link #start} and get status updates.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public void bind() {
        Intent intent = new Intent();
        intent.setClassName("com.android.dynsystem",
                "com.android.dynsystem.DynamicSystemInstallationService");

        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        mBound = true;
    }

    /**
     * Unbind from {@code DynamicSystem} installation service. Unbinding from the installation
     * service stops it from sending following status updates.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
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
     * Start installing {@code DynamicSystem} from URL with default userdata size.
     *
     * Calling this function will first start an Activity to confirm device credential, using
     * {@link KeyguardManager}. If it's confirmed, the installation service will be started.
     *
     * This function doesn't require prior calling {@link #bind}.
     *
     * @param systemUrl A network URL or a file URL to system image.
     * @param systemSize size of system image.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public void start(@NonNull Uri systemUrl, @BytesLong long systemSize) {
        start(systemUrl, systemSize, DEFAULT_USERDATA_SIZE);
    }

    /**
     * Start installing {@code DynamicSystem} from URL.
     *
     * Calling this function will first start an Activity to confirm device credential, using
     * {@link KeyguardManager}. If it's confirmed, the installation service will be started.
     *
     * This function doesn't require prior calling {@link #bind}.
     *
     * @param systemUrl A network URL or a file URL to system image.
     * @param systemSize size of system image.
     * @param userdataSize bytes reserved for userdata.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_DYNAMIC_SYSTEM)
    public void start(@NonNull Uri systemUrl, @BytesLong long systemSize,
            @BytesLong long userdataSize) {
        Intent intent = new Intent();

        intent.setClassName("com.android.dynsystem",
                "com.android.dynsystem.VerificationActivity");

        intent.setData(systemUrl);
        intent.setAction(ACTION_START_INSTALL);

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
                Bundle bundle = (Bundle) msg.obj;
                long progress = bundle.getLong(KEY_INSTALLED_SIZE);
                ParcelableException t = (ParcelableException) bundle.getSerializable(
                        KEY_EXCEPTION_DETAIL);

                Throwable detail = t == null ? null : t.getCause();

                if (mExecutor != null) {
                    mExecutor.execute(() -> {
                        mListener.onStatusChanged(status, cause, progress, detail);
                    });
                } else {
                    mListener.onStatusChanged(status, cause, progress, detail);
                }
                break;
            default:
                // do nothing

        }
    }
}
