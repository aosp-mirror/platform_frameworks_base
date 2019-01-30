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

package android.service.attention;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;


/**
 * Abstract base class for Attention service.
 *
 * <p> An attention service provides attention estimation related features to the system.
 * The system's default AttentionService implementation is configured in
 * {@code config_AttentionComponent}. If this config has no value, a stub is returned.
 *
 * See: {@link AttentionManagerService}.
 *
 * <pre>
 * {@literal
 * <service android:name=".YourAttentionService"
 *          android:permission="android.permission.BIND_ATTENTION_SERVICE">
 * </service>}
 * </pre>
 *
 * @hide
 */
@SystemApi
public abstract class AttentionService extends Service {
    /**
     * The {@link Intent} that must be declared as handled by the service. To be supported, the
     * service must also require the {@link android.Manifest.permission#BIND_ATTENTION_SERVICE}
     * permission so that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.attention.AttentionService";

    /** Attention is absent. */
    public static final int ATTENTION_SUCCESS_ABSENT = 0;

    /** Attention is present. */
    public static final int ATTENTION_SUCCESS_PRESENT = 1;

    /** Preempted by other client. */
    public static final int ATTENTION_FAILURE_PREEMPTED = 2;

    /** Request timed out. */
    public static final int ATTENTION_FAILURE_TIMED_OUT = 3;

    /** Unknown reasons for failing to determine the attention. */
    public static final int ATTENTION_FAILURE_UNKNOWN = 4;

    /**
     * Result codes for when attention check was successful.
     *
     * @hide
     */
    @IntDef(prefix = {"ATTENTION_SUCCESS_"}, value = {ATTENTION_SUCCESS_ABSENT,
            ATTENTION_SUCCESS_PRESENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttentionSuccessCodes {
    }

    /**
     * Result codes explaining why attention check was not successful.
     *
     * @hide
     */
    @IntDef(prefix = {"ATTENTION_FAILURE_"}, value = {ATTENTION_FAILURE_PREEMPTED,
            ATTENTION_FAILURE_TIMED_OUT, ATTENTION_FAILURE_UNKNOWN})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AttentionFailureCodes {
    }

    private final IAttentionService.Stub mBinder = new IAttentionService.Stub() {

        /** {@inheritDoc} */
        @Override
        public void checkAttention(int requestCode, IAttentionCallback callback) {
            Preconditions.checkNotNull(callback);
            AttentionService.this.onCheckAttention(requestCode, new AttentionCallback(callback));
        }

        /** {@inheritDoc} */
        @Override
        public void cancelAttentionCheck(int requestCode) {
            AttentionService.this.onCancelAttentionCheck(requestCode);
        }
    };

    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    /**
     * Checks the user attention and calls into the provided callback.
     *
     * @param requestCode an identifier that could be used to cancel the request
     * @param callback    the callback to return the result to
     */
    public abstract void onCheckAttention(int requestCode, @NonNull AttentionCallback callback);

    /** Cancels the attention check for a given request code. */
    public abstract void onCancelAttentionCheck(int requestCode);


    /** Callbacks for AttentionService results. */
    public static final class AttentionCallback {
        private final IAttentionCallback mCallback;

        private AttentionCallback(IAttentionCallback callback) {
            mCallback = callback;
        }

        /** Returns the result. */
        public void onSuccess(int requestCode, @AttentionSuccessCodes int result, long timestamp) {
            try {
                mCallback.onSuccess(requestCode, result, timestamp);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }

        /** Signals a failure. */
        public void onFailure(int requestCode, @AttentionFailureCodes int error) {
            try {
                mCallback.onFailure(requestCode, error);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }
}
