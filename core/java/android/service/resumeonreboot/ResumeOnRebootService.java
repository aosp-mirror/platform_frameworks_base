/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.resumeonreboot;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.RemoteException;

import com.android.internal.os.BackgroundThread;

import java.io.IOException;

/**
 * Base class for service that provides wrapping/unwrapping of the opaque blob needed for
 * ResumeOnReboot operation. The package needs to provide a wrap/unwrap implementation for handling
 * the opaque blob, that's secure even when on device keystore and clock is compromised. This can
 * be achieved by using tamper-resistant hardware such as a secure element with a secure clock, or
 * using a remote server to store and retrieve data and manage timing.
 *
 * <p>To extend this class, you must declare the service in your manifest file with the
 * {@link android.Manifest.permission#BIND_RESUME_ON_REBOOT_SERVICE} permission,
 * include an intent filter with the {@link #SERVICE_INTERFACE} action and mark the service as
 * direct-boot aware. In addition, the package that contains the service must be granted
 * {@link android.Manifest.permission#BIND_RESUME_ON_REBOOT_SERVICE}.
 * For example:</p>
 * <pre>
 *     &lt;service android:name=".FooResumeOnRebootService"
 *             android:exported="true"
 *             android:priority="100"
 *             android:directBootAware="true"
 *             android:permission="android.permission.BIND_RESUME_ON_REBOOT_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action android:name="android.service.resumeonreboot.ResumeOnRebootService" /&gt;
 *         &lt;/intent-filter&gt;
 *     &lt;/service&gt;
 * </pre>
 *
 * @hide
 * @see
 * <a href="https://source.android.com/devices/tech/ota/resume-on-reboot">https://source.android.com/devices/tech/ota/resume-on-reboot</a>
 */
@SystemApi
public abstract class ResumeOnRebootService extends Service {

    /**
     * The intent that the service must respond to. Add it to the intent filter of the service.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.resumeonreboot.ResumeOnRebootService";
    /** @hide */
    public static final String UNWRAPPED_BLOB_KEY = "unrwapped_blob_key";
    /** @hide */
    public static final String WRAPPED_BLOB_KEY = "wrapped_blob_key";
    /** @hide */
    public static final String EXCEPTION_KEY = "exception_key";

    private final Handler mHandler = BackgroundThread.getHandler();

    /**
     * Implementation for wrapping the opaque blob used for resume-on-reboot prior to
     * reboot. The service should not assume any structure of the blob to be wrapped. The
     * implementation should wrap the opaque blob in a reasonable time or throw {@link IOException}
     * if it's unable to complete the action due to retry-able errors (e.g network errors)
     * and {@link IllegalArgumentException} if {@code wrapBlob} fails due to fatal errors
     * (e.g corrupted blob).
     *
     * @param blob             The opaque blob with size on the order of 100 bytes.
     * @param lifeTimeInMillis The life time of the blob. This must be strictly enforced by the
     *                         implementation and any attempt to unWrap the wrapped blob returned by
     *                         this function after expiration should
     *                         fail.
     * @return Wrapped blob to be persisted across reboot with size on the order of 100 bytes.
     * @throws IOException if the implementation is unable to wrap the blob successfully due to
     * retry-able errors.
     */
    @NonNull
    public abstract byte[] onWrap(@NonNull byte[] blob, @DurationMillisLong long lifeTimeInMillis)
            throws IOException;

    /**
     * Implementation for unwrapping the wrapped blob used for resume-on-reboot after reboot. This
     * operation would happen after reboot during direct boot mode (i.e before device is unlocked
     * for the first time). The implementation should unwrap the wrapped blob in a reasonable time
     * and returns the result or throw {@link IOException} if it's unable to complete the action
     * due to retry-able errors (e.g network error) and {@link IllegalArgumentException}
     * if {@code unwrapBlob} fails due to fatal errors (e.g stale or corrupted blob).
     *
     * @param wrappedBlob The wrapped blob with size on the order of 100 bytes.
     * @return Unwrapped blob used for resume-on-reboot with the size on the order of 100 bytes.
     * @throws IOException if the implementation is unable to unwrap the wrapped blob successfully
     * due to retry-able errors.
     */
    @NonNull
    public abstract byte[] onUnwrap(@NonNull byte[] wrappedBlob) throws IOException;

    private final android.service.resumeonreboot.IResumeOnRebootService mInterface =
            new android.service.resumeonreboot.IResumeOnRebootService.Stub() {

                @Override
                public void wrapSecret(byte[] unwrappedBlob,
                        @DurationMillisLong long lifeTimeInMillis,
                        RemoteCallback resultCallback) throws RemoteException {
                    mHandler.post(() -> {
                        try {
                            byte[] wrappedBlob = onWrap(unwrappedBlob,
                                    lifeTimeInMillis);
                            Bundle bundle = new Bundle();
                            bundle.putByteArray(WRAPPED_BLOB_KEY, wrappedBlob);
                            resultCallback.sendResult(bundle);
                        } catch (Throwable e) {
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(EXCEPTION_KEY, new ParcelableException(e));
                            resultCallback.sendResult(bundle);
                        }
                    });
                }

                @Override
                public void unwrap(byte[] wrappedBlob, RemoteCallback resultCallback)
                        throws RemoteException {
                    mHandler.post(() -> {
                        try {
                            byte[] unwrappedBlob = onUnwrap(wrappedBlob);
                            Bundle bundle = new Bundle();
                            bundle.putByteArray(UNWRAPPED_BLOB_KEY, unwrappedBlob);
                            resultCallback.sendResult(bundle);
                        } catch (Throwable e) {
                            Bundle bundle = new Bundle();
                            bundle.putParcelable(EXCEPTION_KEY, new ParcelableException(e));
                            resultCallback.sendResult(bundle);
                        }
                    });
                }
            };

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return mInterface.asBinder();
    }
}
