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
package android.view.intelligence;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.intelligence.InteractionSessionId;
import android.util.Log;
import android.view.intelligence.ContentCaptureEvent.EventType;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * TODO(b/111276913): add javadocs / implement
 */
@SystemService(Context.INTELLIGENCE_MANAGER_SERVICE)
public final class IntelligenceManager {

    private static final String TAG = "IntelligenceManager";

    // TODO(b/111276913): define a way to dynamically set it (for example, using settings?)
    private static final boolean VERBOSE = false;

    /**
     * Used to indicate that a text change was caused by user input (for example, through IME).
     */
    //TODO(b/111276913): link to notifyTextChanged() method once available
    public static final int FLAG_USER_INPUT = 0x1;


    /**
     * Initial state, when there is no session.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * Service's startSession() was called, but server didn't confirm it was created yet.
     *
     * @hide
     */
    public static final int STATE_WAITING_FOR_SERVER = 1;

    /**
     * Session is active.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 2;

    private final Context mContext;

    @Nullable
    private final IIntelligenceManager mService;

    private final Object mLock = new Object();

    @Nullable
    @GuardedBy("mLock")
    private InteractionSessionId mId;

    @GuardedBy("mLock")
    private int mState = STATE_UNKNOWN;

    @GuardedBy("mLock")
    private IBinder mApplicationToken;

    // TODO(b/111276913): replace by an interface name implemented by Activity, similar to
    // AutofillClient
    @GuardedBy("mLock")
    private ComponentName mComponentName;

    /** @hide */
    public IntelligenceManager(@NonNull Context context, @Nullable IIntelligenceManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        mService = service;
    }

    /** @hide */
    public void onActivityCreated(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (!isContentCaptureEnabled()) return;

        synchronized (mLock) {
            if (mState != STATE_UNKNOWN) {
                Log.w(TAG, "ignoring onActivityStarted(" + token + ") while on state "
                        + getStateAsStringLocked());
                return;
            }
            mState = STATE_WAITING_FOR_SERVER;
            mId = new InteractionSessionId();
            mApplicationToken = token;
            mComponentName = componentName;

            if (VERBOSE) {
                Log.v(TAG, "onActivityStarted(): token=" + token + ", act=" + componentName
                        + ", id=" + mId);
            }
            final int flags = 0; // TODO(b/111276913): get proper flags

            try {
                mService.startSession(mContext.getUserId(), mApplicationToken, componentName,
                        mId, flags, new IResultReceiver.Stub() {
                            @Override
                            public void send(int resultCode, Bundle resultData)
                                    throws RemoteException {
                                synchronized (mLock) {
                                    if (resultCode > 0) {
                                        mState = STATE_ACTIVE;
                                    } else {
                                        // TODO(b/111276913): handle other cases like disabled by
                                        // service
                                        mState = STATE_UNKNOWN;
                                    }
                                    if (VERBOSE) {
                                        Log.v(TAG, "onActivityStarted() result: code=" + resultCode
                                                + ", id=" + mId
                                                + ", state=" + getStateAsStringLocked());
                                    }
                                }
                            }
                        });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Used for intermediate events (i.e, other than created and destroyed).
     *
     * @hide
     */
    public void onActivityLifecycleEvent(@EventType int type) {
        if (!isContentCaptureEnabled()) return;

        //TODO(b/111276913): should buffer event (and call service on handler thread), instead of
        // calling right away
        final ContentCaptureEvent event = new ContentCaptureEvent(type, SystemClock.uptimeMillis(),
                0);
        final List<ContentCaptureEvent> events = Arrays.asList(event);

        synchronized (mLock) {
            //TODO(b/111276913): check session state; for example, how to handle if it's waiting for
            // remote id

            if (VERBOSE) {
                Log.v(TAG, "onActivityLifecycleEvent() for " + mComponentName.flattenToShortString()
                        + ": " + ContentCaptureEvent.getTypeAsString(type));
            }

            try {
                mService.sendEvents(mContext.getUserId(), mId, events);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    public void onActivityDestroyed() {
        if (!isContentCaptureEnabled()) return;

        synchronized (mLock) {
            //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
            // id) and send it to the cache of batched commands

            if (VERBOSE) {
                Log.v(TAG, "onActivityDestroyed(): state=" + getStateAsStringLocked()
                        + ", mId=" + mId);
            }

            try {
                mService.finishSession(mContext.getUserId(), mId);
                mState = STATE_UNKNOWN;
                mId = null;
                mApplicationToken = null;
                mComponentName = null;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Returns the component name of the {@code android.service.intelligence.IntelligenceService}
     * that is enabled for the current user.
     */
    @Nullable
    public ComponentName getIntelligenceServiceComponentName() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Checks whether content capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        //TODO(b/111276913): properly implement by checking if it was explicitly disabled by
        // service, or if service is not set
        // (and probably renamign to isEnabledLocked()
        return mService != null;
    }

    /**
     * Called by apps to disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void disableContentCapture() {
        //TODO(b/111276913): implement
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities with such
     * {@link android.content.ComponentName}.
     *
     * <p>Useful to blacklist a particular activity.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setActivityContentCaptureEnabled(@NonNull ComponentName activity,
            boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities of the app with such
     * {@code packageName}.
     *
     * <p>Useful to blacklist any activity from a particular app.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setPackageContentCaptureEnabled(@NonNull String packageName, boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Gets the activities where content capture was disabled by
     * {@link #setActivityContentCaptureEnabled(ComponentName, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<ComponentName> getContentCaptureDisabledActivities() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Gets the apps where content capture was disabled by
     * {@link #setPackageContentCaptureEnabled(String, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<String> getContentCaptureDisabledPackages() {
        //TODO(b/111276913): implement
        return null;
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("IntelligenceManager");
        final String prefix2 = prefix + "  ";
        synchronized (mLock) {
            pw.print(prefix2); pw.print("mContext: "); pw.println(mContext);
            pw.print(prefix2); pw.print("mService: "); pw.println(mService);
            pw.print(prefix2); pw.print("user: "); pw.println(mContext.getUserId());
            pw.print(prefix2); pw.print("enabled: "); pw.println(isContentCaptureEnabled());
            pw.print(prefix2); pw.print("id: "); pw.println(mId);
            pw.print(prefix2); pw.print("state: "); pw.print(mState); pw.print(" (");
            pw.print(getStateAsStringLocked()); pw.println(")");
            pw.print(prefix2); pw.print("appToken: "); pw.println(mApplicationToken);
            pw.print(prefix2); pw.print("componentName: "); pw.println(mComponentName);
        }
    }

    @GuardedBy("mLock")
    private String getStateAsStringLocked() {
        return getStateAsString(mState);
    }

    @NonNull
    private static String getStateAsString(int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "UNKNOWN";
            case STATE_WAITING_FOR_SERVER:
                return "WAITING_FOR_SERVER";
            case STATE_ACTIVE:
                return "ACTIVE";
            default:
                return "INVALID:" + state;
        }
    }
}
