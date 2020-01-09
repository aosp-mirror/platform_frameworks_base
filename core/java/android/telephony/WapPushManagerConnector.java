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

package android.telephony;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.telephony.IWapPushManager;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * APIs for platform to connect to the WAP push manager service.
 *
 * <p>To start connection, {@link #bindToWapPushManagerService} should be called.
 *
 * <p>Upon completion {@link #unbindWapPushManagerService} should be called to unbind the service.
 *
 * @hide
 */
@SystemApi
public final class WapPushManagerConnector {
    private final Context mContext;

    private volatile WapPushManagerConnection mConnection;
    private volatile IWapPushManager mWapPushManager;
    private String mWapPushManagerPackage;

    /**
     * The {@link android.content.Intent} that must be declared as handled by the
     * WAP push manager service.
     * @hide
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "com.android.internal.telephony.IWapPushManager";

    /** @hide */
    @IntDef(flag = true, prefix = {"RESULT_"}, value = {
            RESULT_MESSAGE_HANDLED,
            RESULT_APP_QUERY_FAILED,
            RESULT_SIGNATURE_NO_MATCH,
            RESULT_INVALID_RECEIVER_NAME,
            RESULT_EXCEPTION_CAUGHT,
            RESULT_FURTHER_PROCESSING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProcessMessageResult{}

    /** {@link #processMessage} return value: Message is handled. */
    public static final int RESULT_MESSAGE_HANDLED = 0x1;
    /** {@link #processMessage} return value: Application ID or content type was not found. */
    public static final int RESULT_APP_QUERY_FAILED = 0x2;
    /** {@link #processMessage} return value: Receiver application signature check failed. */
    public static final int RESULT_SIGNATURE_NO_MATCH = 0x4;
    /** {@link #processMessage} return value: Receiver application was not found. */
    public static final int RESULT_INVALID_RECEIVER_NAME = 0x8;
    /** {@link #processMessage} return value: Unknown exception. */
    public static final int RESULT_EXCEPTION_CAUGHT = 0x10;
    /** {@link #processMessage} return value: further processing needed. */
    public static final int RESULT_FURTHER_PROCESSING = 0x8000;

    /** The application package name of the WAP push manager service. */
    private static final String SERVICE_PACKAGE = "com.android.smspush";

    public WapPushManagerConnector(@NonNull Context context) {
        mContext = context;
    }

    /**
     * Binds to the WAP push manager service. This method should be called exactly once.
     *
     * @return {@code true} upon successfully binding to a service, {@code false} otherwise
     */
    public boolean bindToWapPushManagerService() {
        Preconditions.checkState(mConnection == null);

        Intent intent = new Intent(SERVICE_INTERFACE);
        ComponentName component = intent.resolveSystemService(mContext.getPackageManager(), 0);
        intent.setComponent(component);
        mConnection = new WapPushManagerConnection();
        if (component != null
                && mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)) {
            mWapPushManagerPackage = component.getPackageName();
            return true;
        }
        return false;
    }

    /**
     * Returns the package name of WAP push manager service application connected to,
     * or {@code null} if not connected.
     */
    @Nullable
    public String getConnectedWapPushManagerServicePackage() {
        return mWapPushManagerPackage;
    }

    /**
     * Processes WAP push message and triggers the {@code intent}.
     *
     * @see RESULT_MESSAGE_HANDLED
     * @see RESULT_APP_QUERY_FAILED
     * @see RESULT_SIGNATURE_NO_MATCH
     * @see RESULT_INVALID_RECEIVER_NAME
     * @see RESULT_EXCEPTION_CAUGHT
     * @see RESULT_FURTHER_PROCESSING
     */
    @ProcessMessageResult
    public int processMessage(
            @NonNull String applicationId, @NonNull String contentType, @NonNull Intent intent) {
        try {
            return mWapPushManager.processMessage(applicationId, contentType, intent);
        } catch (NullPointerException | RemoteException e) {
            return RESULT_EXCEPTION_CAUGHT;
        }
    }

    /**
     * Unbinds the WAP push manager service. This method should be called exactly once.
     */
    public void unbindWapPushManagerService() {
        Preconditions.checkNotNull(mConnection);

        mContext.unbindService(mConnection);
        mConnection = null;
    }

    private class WapPushManagerConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Because we have bound to an explicit
            // service that is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            mWapPushManager = IWapPushManager.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mWapPushManager = null;
        }

        @Override
        public void onNullBinding(ComponentName name) {
            onServiceDisconnected(name);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }
    }
}
