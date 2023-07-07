/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.assist.classification;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.BaseBundle;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.OutcomeReceiver;
import android.os.RemoteException;
import android.util.Log;

/**
 * A service using {@link android.app.assist.AssistStructure} to detect fields on the screen.
 * Service may use classifiers to look at the un-stripped AssistStructure to make informed decision
 * and classify the fields.
 *
 * Currently, it's used to detect the field types for the Autofill Framework to provide relevant
 * autofill suggestions to the user.
 *
 *
 * The methods are invoked on the binder threads.
 *
 * @hide
 */
@SystemApi
public abstract class FieldClassificationService extends Service {

    private static final String TAG = FieldClassificationService.class.getSimpleName();

    static boolean sDebug = Build.IS_USER ? false : true;
    static boolean sVerbose = false;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_FIELD_CLASSIFICATION_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.assist.classification.FieldClassificationService";

    // Used for metrics / debug only
    private ComponentName mServiceComponentName;

    private final class FieldClassificationServiceImpl
            extends IFieldClassificationService.Stub {

        @Override
        public void onConnected(boolean debug, boolean verbose) {
            handleOnConnected(debug, verbose);
        }

        @Override
        public void onDisconnected() {
            handleOnDisconnected();
        }

        @Override
        public void onFieldClassificationRequest(
                FieldClassificationRequest request, IFieldClassificationCallback callback) {
            handleOnClassificationRequest(request, callback);
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        BaseBundle.setShouldDefuse(true);
    }

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            mServiceComponentName = intent.getComponent();
            return new FieldClassificationServiceImpl().asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    /**
     * Called when the Android system connects to service.
     *
     * <p>You should generally do initialization here rather than in {@link #onCreate}.
     */
    public void onConnected() {
    }

    /**
     * Requests the service to handle field classification request.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the detection result is no longer needed and the service should
     *     stop handling this detection request in order to save resources.
     * @param outcomeReceiver object used to notify the result of the request. Service <b>must</b>
     *     call {@link OutcomeReceiver<>#onResult(FieldClassificationResponse)}.
     */
    public abstract void onClassificationRequest(
            @NonNull FieldClassificationRequest request,
            @NonNull CancellationSignal cancellationSignal,
            @NonNull OutcomeReceiver<FieldClassificationResponse, Exception> outcomeReceiver);

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active
     * {@link FieldClassificationService}.
     */
    public void onDisconnected() {
    }

    private void handleOnConnected(boolean debug, boolean verbose) {
        if (sDebug || debug) {
            Log.d(TAG, "handleOnConnected(): debug=" + debug + ", verbose=" + verbose);
        }
        sDebug = debug;
        sVerbose = verbose;
        onConnected();
    }

    private void handleOnDisconnected() {
        onDisconnected();
    }

    private void handleOnClassificationRequest(
            FieldClassificationRequest request, @NonNull IFieldClassificationCallback callback) {

        final ICancellationSignal transport = CancellationSignal.createTransport();
        final CancellationSignal cancellationSignal = CancellationSignal.fromTransport(transport);
        onClassificationRequest(
                request,
                cancellationSignal,
                new OutcomeReceiver<FieldClassificationResponse, Exception>() {
                    @Override
                    public void onResult(FieldClassificationResponse result) {
                        try {
                            callback.onSuccess(result);
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                    @Override
                    public void onError(Exception e) {
                        try {
                            callback.onFailure();
                        } catch (RemoteException ex) {
                            ex.rethrowFromSystemServer();
                        }
                    }
                });
    }
}

