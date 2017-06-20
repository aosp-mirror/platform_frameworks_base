/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.autofill;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import com.android.internal.os.HandlerCaller;
import android.annotation.SdkConstant;
import android.app.Activity;
import android.app.Service;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.util.Log;
import android.view.autofill.AutofillManager;

import com.android.internal.os.SomeArgs;

/**
 * Top-level service of the current autofill service for a given user.
 *
 * <p>Apps providing autofill capabilities must extend this service.
 */
public abstract class AutofillService extends Service {
    private static final String TAG = "AutofillService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUTOFILL_SERVICE} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.autofill.AutofillService";

    /**
     * Name under which a AutoFillService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#AutofillService autofill-service}&gt;</code> tag.
     * This is a a sample XML file configuring an AutoFillService:
     * <pre> &lt;autofill-service
     *     android:settingsActivity="foo.bar.SettingsActivity"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.autofill";

    // Handler messages.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_ON_FILL_REQUEST = 3;
    private static final int MSG_ON_SAVE_REQUEST = 4;

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void onConnectedStateChanged(boolean connected) {
            if (connected) {
                mHandlerCaller.obtainMessage(MSG_CONNECT).sendToTarget();
            } else {
                mHandlerCaller.obtainMessage(MSG_DISCONNECT).sendToTarget();
            }
        }

        @Override
        public void onFillRequest(FillRequest request, IFillCallback callback) {
            ICancellationSignal transport = CancellationSignal.createTransport();
            try {
                callback.onCancellable(transport);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mHandlerCaller.obtainMessageOOO(MSG_ON_FILL_REQUEST, request,
                    CancellationSignal.fromTransport(transport), callback)
                    .sendToTarget();
        }

        @Override
        public void onSaveRequest(SaveRequest request, ISaveCallback callback) {
            mHandlerCaller.obtainMessageOO(MSG_ON_SAVE_REQUEST, request,
                    callback).sendToTarget();
        }
    };

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_CONNECT: {
                onConnected();
                break;
            } case MSG_ON_FILL_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final FillRequest request = (FillRequest) args.arg1;
                final CancellationSignal cancellation = (CancellationSignal) args.arg2;
                final IFillCallback callback = (IFillCallback) args.arg3;
                final FillCallback fillCallback = new FillCallback(callback, request.getId());
                args.recycle();
                onFillRequest(request, cancellation, fillCallback);
                break;
            } case MSG_ON_SAVE_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final SaveRequest request = (SaveRequest) args.arg1;
                final ISaveCallback callback = (ISaveCallback) args.arg2;
                final SaveCallback saveCallback = new SaveCallback(callback);
                args.recycle();
                onSaveRequest(request, saveCallback);
                break;
            } case MSG_DISCONNECT: {
                onDisconnected();
                break;
            } default: {
                Log.w(TAG, "MyCallbacks received invalid message type: " + msg);
            }
        }
    };

    private HandlerCaller mHandlerCaller;

    /**
     * {@inheritDoc}
     *
     * <strong>NOTE: </strong>if overridden, it must call {@code super.onCreate()}.
     */
    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(), mHandlerCallback, true);
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent: " + intent);
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
     * Called by the Android system do decide if an {@link Activity} can be autofilled by the
     * service.
     *
     * <p>Service must call one of the {@link FillCallback} methods (like
     * {@link FillCallback#onSuccess(FillResponse)}
     * or {@link FillCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param request the {@link FillRequest request} to handle.
     *        See {@link FillResponse} for examples of multiple-sections requests.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onFillRequest(@NonNull FillRequest request,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback);

    /**
     * Called when user requests service to save the fields of an {@link Activity}.
     *
     * <p>Service must call one of the {@link SaveCallback} methods (like
     * {@link SaveCallback#onSuccess()} or {@link SaveCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * <p><b>NOTE: </b>to retrieve the actual value of the field, the service should call
     * {@link android.app.assist.AssistStructure.ViewNode#getAutofillValue()}; if it calls
     * {@link android.app.assist.AssistStructure.ViewNode#getText()} or other methods, there is no
     * guarantee such method will return the most recent value of the field.
     *
     * @param request the {@link SaveRequest request} to handle.
     *        See {@link FillResponse} for examples of multiple-sections requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onSaveRequest(@NonNull SaveRequest request,
            @NonNull SaveCallback callback);

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutofillService}.
     */
    public void onDisconnected() {
    }

    /** @hide */
    @Deprecated
    public final void disableSelf() {
        getSystemService(AutofillManager.class).disableOwnedAutofillServices();
    }

    /**
     * Returns the {@link FillEventHistory.Event events} since the last {@link FillResponse} was
     * returned.
     *
     * <p>The history is not persisted over reboots.
     *
     * @return The history or {@code null} if there are not events.
     */
    @Nullable public final FillEventHistory getFillEventHistory() {
        AutofillManager afm = getSystemService(AutofillManager.class);

        if (afm == null) {
            return null;
        } else {
            return afm.getFillEventHistory();
        }
    }
}
