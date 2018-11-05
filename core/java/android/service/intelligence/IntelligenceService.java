/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.intelligence;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.intelligence.ContentCaptureEvent;

import java.util.List;

/**
 * A service used to capture the content of the screen.
 *
 * <p>The data collected by this service can be analyzed and combined with other sources to provide
 * contextual data in other areas of the system such as Autofill.
 *
 * @hide
 */
@SystemApi
public abstract class IntelligenceService extends Service {

    private static final String TAG = "IntelligenceService";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_INTELLIGENCE_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.intelligence.IntelligenceService";

    private Handler mHandler;

    private final IIntelligenceService mInterface = new IIntelligenceService.Stub() {

        @Override
        public void onSessionLifecycle(InteractionContext context, InteractionSessionId sessionId)
                throws RemoteException {
            if (context != null) {
                mHandler.sendMessage(
                        obtainMessage(IntelligenceService::onCreateInteractionSession,
                                IntelligenceService.this, context, sessionId));
            } else {
                mHandler.sendMessage(
                        obtainMessage(IntelligenceService::onDestroyInteractionSession,
                                IntelligenceService.this, sessionId));
            }
        }
        @Override
        public void onContentCaptureEvents(InteractionSessionId sessionId,
                List<ContentCaptureEvent> events) {
            mHandler.sendMessage(
                    obtainMessage(IntelligenceService::onContentCaptureEvent,
                            IntelligenceService.this, sessionId, events));

        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    /** @hide */
    @Override
    public final IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent: " + intent);
        return null;
    }

    /**
     * Creates a new interaction session.
     *
     * @param context interaction context
     * @param sessionId the session's Id
     */
    public void onCreateInteractionSession(@NonNull InteractionContext context,
            @NonNull InteractionSessionId sessionId) {}

    /**
     * Notifies the service of {@link ContentCaptureEvent events} associated with a content capture
     * session.
     *
     * @param sessionId the session's Id
     * @param events the events
     */
     // TODO(b/111276913): rename to onContentCaptureEvents
    public abstract void onContentCaptureEvent(@NonNull InteractionSessionId sessionId,
            @NonNull List<ContentCaptureEvent> events);

    /**
     * Destroys the interaction session.
     *
     * @param sessionId the id of the session to destroy
     */
    public void onDestroyInteractionSession(@NonNull InteractionSessionId sessionId) {}
}
