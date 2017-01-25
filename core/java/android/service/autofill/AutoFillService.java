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

import static android.view.View.AUTO_FILL_FLAG_TYPE_FILL;
import static android.view.View.AUTO_FILL_FLAG_TYPE_SAVE;

import android.annotation.SdkConstant;
import android.app.Activity;
import android.app.Service;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.autofill.AutoFillId;
import android.view.autofill.FillResponse;

import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

// TODO(b/33197203): improve javadoc (of both class and methods); in particular, make sure the
// life-cycle (and how state could be maintained on server-side) is well documented.

/**
 * Top-level service of the current auto-fill service for a given user.
 *
 * <p>Apps providing auto-fill capabilities must extend this service.
 */
public abstract class AutoFillService extends Service {

    private static final String TAG = "AutoFillService";
    static final boolean DEBUG = true; // TODO(b/33197203): set to false once stable

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

    // Internal bundle keys.
    /** @hide */ public static final String KEY_CALLBACK = "callback";
    /** @hide */ public static final String KEY_SAVABLE_IDS = "savable_ids";

    // Prefix for public bundle keys.
    private static final String KEY_PREFIX = "android.service.autofill.extra.";

    /**
     * Key of the {@link Bundle} passed to methods such as
     * {@link #onSaveRequest(AssistStructure, Bundle, CancellationSignal, SaveCallback)}
     * containing the extras set by
     * {@link android.view.autofill.FillResponse.Builder#setExtras(Bundle)}.
     */
    public static final String EXTRA_RESPONSE_EXTRAS = KEY_PREFIX + "RESPONSE_EXTRAS";

    /**
     * Key of the {@link Bundle} passed to methods such as
     * {@link #onSaveRequest(AssistStructure, Bundle, CancellationSignal, SaveCallback)}
     * containing the extras set by
     * {@link android.view.autofill.Dataset.Builder#setExtras(Bundle)}.
     */
    public static final String EXTRA_DATASET_EXTRAS = KEY_PREFIX + "DATASET_EXTRAS";

    // Handler messages.
    private static final int MSG_CONNECT = 1;
    private static final int MSG_AUTO_FILL_ACTIVITY = 2;
    private static final int MSG_DISCONNECT = 3;

    private final IAutoFillService mInterface = new IAutoFillService.Stub() {

        @Override
        public void autoFill(AssistStructure structure, IAutoFillServerCallback callback,
                Bundle extras, int flags) {
            mHandlerCaller
                    .obtainMessageIOOO(MSG_AUTO_FILL_ACTIVITY, flags, structure, extras, callback)
                    .sendToTarget();
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

    private final HandlerCaller.Callback mHandlerCallback = new HandlerCaller.Callback() {

        @Override
        public void executeMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONNECT: {
                    onConnected();
                    break;
                } case MSG_AUTO_FILL_ACTIVITY: {
                    final SomeArgs args = (SomeArgs) msg.obj;
                    final int flags = msg.arg1;
                    final AssistStructure structure = (AssistStructure) args.arg1;
                    final Bundle extras = (Bundle) args.arg2;
                    final IAutoFillServerCallback callback = (IAutoFillServerCallback) args.arg3;
                    requestAutoFill(callback, structure, extras, flags);
                    break;
                } case MSG_DISCONNECT: {
                    onDisconnected();
                    break;
                } default: {
                    Log.w(TAG, "MyCallbacks received invalid message type: " + msg);
                }
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
        if (DEBUG) Log.d(TAG, "onConnected()");
    }

    /**
     * Called by the Android system do decide if an {@link Activity} can be auto-filled by the
     * service.
     *
     * <p>Service must call one of the {@link FillCallback} methods (like
     * {@link FillCallback#onSuccess(FillResponse)} or {@link FillCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param structure {@link Activity}'s view structure.
     * @param data bundle containing additional arguments set by the Android system (currently none)
     * or data passed by the service on previous calls to fullfill other sections of this activity
     * (see {@link FillResponse} Javadoc for examples of multiple-sections requests).
     * @param cancellationSignal signal for observing cancel requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onFillRequest(AssistStructure structure,
            Bundle data, CancellationSignal cancellationSignal, FillCallback callback);

    /**
     * Called when user requests service to save the fields of an {@link Activity}.
     *
     * <p>Service must call one of the {@link SaveCallback} methods (like
     * {@link SaveCallback#onSuccess(AutoFillId[])} or {@link SaveCallback#onFailure(CharSequence)})
     * to notify the result of the request.
     *
     * @param structure {@link Activity}'s view structure.
     * @param data bundle containing additional arguments set by the Android system (currently none)
     * or data passed by the service in the {@link FillResponse} that originated this call.
     * @param cancellationSignal signal for observing cancel requests.
     * @param callback object used to notify the result of the request.
     */
    public abstract void onSaveRequest(AssistStructure structure,
            Bundle data, CancellationSignal cancellationSignal, SaveCallback callback);

    private void requestAutoFill(IAutoFillServerCallback callback, AssistStructure structure,
            Bundle data, int flags) {
        switch (flags) {
            case AUTO_FILL_FLAG_TYPE_FILL:
                final FillCallback fillCallback = new FillCallback(callback);
                // TODO(b/33197203): hook up the cancelationSignal
                onFillRequest(structure, data, new CancellationSignal(), fillCallback);
                break;
            case AUTO_FILL_FLAG_TYPE_SAVE:
                final SaveCallback saveCallback = new SaveCallback(callback);
                // TODO(b/33197203): hook up the cancelationSignal
                onSaveRequest(structure, data, new CancellationSignal(), saveCallback);
                break;
            default:
                Log.w(TAG, "invalid flag on requestAutoFill(): " + flags);
        }
    }

    /**
     * Called when the Android system disconnects from the service.
     *
     * <p> At this point this service may no longer be an active {@link AutoFillService}.
     */
    public void onDisconnected() {
        if (DEBUG) Log.d(TAG, "onDisconnected()");
    }
}
