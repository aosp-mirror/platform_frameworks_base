/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import com.android.internal.os.HandlerCaller;
import android.annotation.SdkConstant;
import android.app.Activity;
import android.app.Service;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.util.Log;

import com.android.internal.os.SomeArgs;

//TODO(b/33197203): improve javadoc (of both class and methods); in particular, make sure the
//life-cycle (and how state could be maintained on server-side) is well documented.

/**
 * Top-level service of the current auto-fill service for a given user.
 *
 * <p>Apps providing auto-fill capabilities must extend this service.
 */
public abstract class AutoFillService extends Service {
    private static final String TAG = "AutoFillService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_AUTO_FILL} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE = "android.service.autofill.AutoFillService";

    /**
     * Name under which a AutoFillService component publishes information about itself.
     * This meta-data should reference an XML resource containing a
     * <code>&lt;{@link
     * android.R.styleable#AutoFillService autofill-service}&gt;</code> tag.
     * This is a a sample XML file configuring an AutoFillService:
     * <pre> &lt;autofill-service
     *     android:settingsActivity="foo.bar.SettingsActivity"
     *     . . .
     * /&gt;</pre>
     */
    public static final String SERVICE_META_DATA = "android.autofill";

    // Internal extras
    /** @hide */
    public static final String EXTRA_ACTIVITY_TOKEN =
            "android.service.autofill.extra.ACTIVITY_TOKEN";

    // Handler messages.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_DISCONNECT = 2;
    private static final int MSG_ON_FILL_REQUEST = 3;
    private static final int MSG_ON_SAVE_REQUEST = 4;

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {
        @Override
        public void onFillRequest(AssistStructure structure, Bundle extras,
                IFillCallback callback) {
            ICancellationSignal transport = CancellationSignal.createTransport();
            try {
                callback.onCancellable(transport);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
            mHandlerCaller.obtainMessageOOOO(MSG_ON_FILL_REQUEST, structure,
                    CancellationSignal.fromTransport(transport), extras, callback)
                    .sendToTarget();
        }

        @Override
        public void onSaveRequest(AssistStructure structure, Bundle extras,
                ISaveCallback callback) {
            mHandlerCaller.obtainMessageOOO(MSG_ON_SAVE_REQUEST, structure,
                    extras, callback).sendToTarget();
        }

        @Override
        public void onConnected() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_CONNECT));
        }

        @Override
        public void onDisconnected() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_DISCONNECT));
        }
    };

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_CONNECT: {
                onConnected();
                break;
            } case MSG_ON_FILL_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final AssistStructure structure = (AssistStructure) args.arg1;
                final CancellationSignal cancellation = (CancellationSignal) args.arg2;
                final Bundle extras = (Bundle) args.arg3;
                final IFillCallback callback = (IFillCallback) args.arg4;
                final FillCallback fillCallback = new FillCallback(callback);
                args.recycle();
                onFillRequest(structure, extras, cancellation, fillCallback);
                break;
            } case MSG_ON_SAVE_REQUEST: {
                final SomeArgs args = (SomeArgs) msg.obj;
                final AssistStructure structure = (AssistStructure) args.arg1;
                final Bundle extras = (Bundle) args.arg2;
                final ISaveCallback callback = (ISaveCallback) args.arg3;
                final SaveCallback saveCallback = new SaveCallback(callback);
                args.recycle();
                onSaveRequest(structure, extras, saveCallback);
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
        //TODO(b/33197203): is not called anymore, fix it!
    }

    /**
     * Called by the Android system do decide if an {@link Activity} can be auto-filled by the
     * service.
     *
     * <p>Service must call one of the {@link FillCallback} methods (like
     * {@link FillCallback#onSuccess(FillResponse)}
     * or {@link FillCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param structure {@link Activity}'s view structure.
     * @param data bundle containing data passed by the service on previous calls to fill.
     *     This bundle allows your service to keep state between fill and save requests
     *     as well as when filling different sections of the UI as the system will try to
     *     aggressively unbind from the service to conserve resources. See {@link
     *     FillResponse} Javadoc for examples of multiple-sections requests.
     * @param cancellationSignal signal for observing cancellation requests. The system will use
     *     this to notify you that the fill result is no longer needed and you should stop
     *     handling this fill request in order to save resources.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onFillRequest(@NonNull AssistStructure structure, @Nullable Bundle data,
            @NonNull CancellationSignal cancellationSignal, @NonNull FillCallback callback);

    /**
     * Called when user requests service to save the fields of an {@link Activity}.
     *
     * <p>Service must call one of the {@link SaveCallback} methods (like
     * {@link SaveCallback#onSuccess()} or {@link SaveCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param structure {@link Activity}'s view structure.
     * @param data bundle containing data passed by the service on previous calls to fill.
     *     This bundle allows your service to keep state between fill and save requests
     *     as well as when filling different sections of the UI as the system will try to
     *     aggressively unbind from the service to conserve resources. See {@link
     *     FillResponse} Javadoc for examples of multiple-sections requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onSaveRequest(@NonNull AssistStructure structure, @Nullable Bundle data,
            @NonNull SaveCallback callback);

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutoFillService}.
     */
    public void onDisconnected() {
        //TODO(b/33197203): is not called anymore, fix it!
    }
}
