/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.service.timezone;

import android.annotation.DurationMillisLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * A service to generate time zone callbacks to the platform. Developers must extend this class.
 *
 * <p>Provider implementations are started via a call to {@link #onStartUpdates(long)} and stopped
 * via a call to {@link #onStopUpdates()}.
 *
 * <p>Once started, providers are expected to detect the time zone if possible, and report the
 * result via {@link #reportSuggestion(TimeZoneProviderSuggestion)} or {@link
 * #reportUncertain()}. Providers may also report that they have permanently failed
 * by calling {@link #reportPermanentFailure(Throwable)}. See the javadocs for each
 * method for details.
 *
 * <p>After starting, providers are expected to issue their first callback within the timeout
 * duration specified in {@link #onStartUpdates(long)}, or they will be implicitly considered to be
 * uncertain.
 *
 * <p>Once stopped or failed, providers are required to stop generating callbacks.
 *
 * <p>Provider types:
 *
 * <p>Android supports up to two <em>location-derived</em> time zone providers. These are called the
 * "primary" and "secondary" location time zone providers. When a location-derived time zone is
 * required, the primary location time zone provider is started first and used until it becomes
 * uncertain or fails, at which point the secondary provider will be started. The secondary will be
 * started and stopped as needed.
 *
 * <p>Provider discovery:
 *
 * <p>Each provider is optional and can be disabled. When enabled, a provider's package name must
 * be explicitly configured in the system server, see {@code
 * config_primaryLocationTimeZoneProviderPackageName} and {@code
 * config_secondaryLocationTimeZoneProviderPackageName} for details.
 *
 * <p>You must declare the service in the AndroidManifest of the app hosting the provider with the
 * {@link android.Manifest.permission#BIND_TIME_ZONE_PROVIDER_SERVICE} permission,
 * and include an intent filter with the necessary action indicating that it is the primary
 * provider ({@link #PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE}) or the secondary
 * provider ({@link #SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE}).
 *
 * <p>Besides declaring the android:permission attribute mentioned above, the application supplying
 * a location provider must be granted the {@link
 * android.Manifest.permission#INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE} permission to be
 * accepted by the system server.
 *
 * <p>{@link TimeZoneProviderService}s may be deployed into processes that run once-per-user
 * or once-per-device (i.e. they service multiple users). See serviceIsMultiuser metadata below for
 * configuration details.
 *
 * <p>The service may specify metadata on its capabilities:
 *
 * <ul>
 *     <li>
 *         "serviceIsMultiuser": A boolean property, indicating if the service wishes to take
 *         responsibility for handling changes to the current user on the device. If true, the
 *         service will always be bound from the system user. If false, the service will always be
 *         bound from the current user. If the current user changes, the old binding will be
 *         released, and a new binding established under the new user. Assumed to be false if not
 *         specified.
 *     </li>
 * </ul>
 *
 * <p>For example:
 * <pre>
 *   &lt;uses-permission
 *       android:name="android.permission.INSTALL_LOCATION_TIME_ZONE_PROVIDER_SERVICE"/&gt;
 *
 * ...
 *
 *     &lt;service android:name=".ExampleTimeZoneProviderService"
 *             android:exported="true"
 *             android:permission="android.permission.BIND_TIME_ZONE_PROVIDER_SERVICE"&gt;
 *         &lt;intent-filter&gt;
 *             &lt;action
 *             android:name="android.service.timezone.SecondaryLocationTimeZoneProviderService"
 *             /&gt;
 *         &lt;/intent-filter&gt;
 *         &lt;meta-data android:name="serviceIsMultiuser" android:value="true" /&gt;
 *     &lt;/service&gt;
 * </pre>
 *
 * <p>Threading:
 *
 * <p>Outgoing calls to {@code report} methods can be made on any thread and will be delivered
 * asynchronously to the system server. Incoming calls to {@link TimeZoneProviderService}-defined
 * service methods like {@link #onStartUpdates(long)} and {@link #onStopUpdates()} are also
 * asynchronous with respect to the system server caller and will be delivered to this service using
 * a single thread. {@link Service} lifecycle method calls like {@link #onCreate()} and {@link
 * #onDestroy()} can occur on a different thread from those made to {@link
 * TimeZoneProviderService}-defined service methods, so implementations must be defensive and not
 * assume an ordering between them, e.g. a call to {@link #onStopUpdates()} can occur after {@link
 * #onDestroy()} and should be handled safely. {@link #mLock} is used to ensure that synchronous
 * calls like {@link #dump(FileDescriptor, PrintWriter, String[])} are safe with respect to
 * asynchronous behavior.
 *
 * @hide
 */
@SystemApi
public abstract class TimeZoneProviderService extends Service {

    private static final String TAG = "TimeZoneProviderService";

    /**
     * The test command result key indicating whether a command succeeded. Value type: boolean
     * @hide
     */
    public static final String TEST_COMMAND_RESULT_SUCCESS_KEY = "SUCCESS";

    /**
     * The test command result key for the error message present when {@link
     * #TEST_COMMAND_RESULT_SUCCESS_KEY} is false. Value type: string
     * @hide
     */
    public static final String TEST_COMMAND_RESULT_ERROR_KEY = "ERROR";

    /**
     * The Intent action that the primary location-derived time zone provider service must respond
     * to. Add it to the intent filter of the service in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE =
            "android.service.timezone.PrimaryLocationTimeZoneProviderService";

    /**
     * The Intent action that the secondary location-based time zone provider service must respond
     * to. Add it to the intent filter of the service in its manifest.
     */
    @SdkConstant(SdkConstant.SdkConstantType.SERVICE_ACTION)
    public static final String SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE =
            "android.service.timezone.SecondaryLocationTimeZoneProviderService";

    private final TimeZoneProviderServiceWrapper mWrapper = new TimeZoneProviderServiceWrapper();

    /** The object used for operations that occur between the main / handler thread. */
    private final Object mLock = new Object();

    /** The handler used for most operations. */
    private final Handler mHandler = BackgroundThread.getHandler();

    /** Set by {@link #mHandler} thread. */
    @GuardedBy("mLock")
    @Nullable
    private ITimeZoneProviderManager mManager;

    /** Set by {@link #mHandler} thread. */
    @GuardedBy("mLock")
    private long mEventFilteringAgeThresholdMillis;

    /**
     * The type of the last suggestion sent to the system server. Used to de-dupe suggestions client
     * side and avoid calling into the system server unnecessarily. {@code null} means no previous
     * event has been sent this cycle; this field is cleared when the service is started.
     */
    @GuardedBy("mLock")
    @Nullable
    private TimeZoneProviderEvent mLastEventSent;

    @Override
    @NonNull
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    /**
     * Indicates a successful time zone detection. See {@link TimeZoneProviderSuggestion} for
     * details.
     */
    public final void reportSuggestion(@NonNull TimeZoneProviderSuggestion suggestion) {
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> {
            synchronized (mLock) {
                ITimeZoneProviderManager manager = mManager;
                if (manager != null) {
                    try {
                        TimeZoneProviderEvent thisEvent =
                                TimeZoneProviderEvent.createSuggestionEvent(
                                        SystemClock.elapsedRealtime(), suggestion);
                        if (shouldSendEvent(thisEvent)) {
                            manager.onTimeZoneProviderEvent(thisEvent);
                            mLastEventSent = thisEvent;
                        }
                    } catch (RemoteException | RuntimeException e) {
                        Log.w(TAG, e);
                    }
                }
            }
        });
    }

    /**
     * Indicates the time zone is not known because of an expected runtime state or error, e.g. when
     * the provider is unable to detect location, or there was a problem when resolving the location
     * to a time zone.
     */
    public final void reportUncertain() {
        mHandler.post(() -> {
            synchronized (mLock) {
                ITimeZoneProviderManager manager = mManager;
                if (manager != null) {
                    try {
                        TimeZoneProviderEvent thisEvent =
                                TimeZoneProviderEvent.createUncertainEvent(
                                        SystemClock.elapsedRealtime());
                        if (shouldSendEvent(thisEvent)) {
                            manager.onTimeZoneProviderEvent(thisEvent);
                            mLastEventSent = thisEvent;
                        }
                    } catch (RemoteException | RuntimeException e) {
                        Log.w(TAG, e);
                    }
                }
            }
        });
    }

    /**
     * Indicates there was a permanent failure. This is not generally expected, and probably means a
     * required backend service has been turned down, or the client is unreasonably old.
     */
    public final void reportPermanentFailure(@NonNull Throwable cause) {
        Objects.requireNonNull(cause);

        mHandler.post(() -> {
            synchronized (mLock) {
                ITimeZoneProviderManager manager = mManager;
                if (manager != null) {
                    try {
                        String causeString = cause.getMessage();
                        TimeZoneProviderEvent thisEvent =
                                TimeZoneProviderEvent.createPermanentFailureEvent(
                                        SystemClock.elapsedRealtime(), causeString);
                        if (shouldSendEvent(thisEvent)) {
                            manager.onTimeZoneProviderEvent(thisEvent);
                            mLastEventSent = thisEvent;
                        }
                    } catch (RemoteException | RuntimeException e) {
                        Log.w(TAG, e);
                    }
                }
            }
        });
    }

    @GuardedBy("mLock")
    private boolean shouldSendEvent(TimeZoneProviderEvent newEvent) {
        // Always send an event if it indicates a state or suggestion change.
        if (!newEvent.isEquivalentTo(mLastEventSent)) {
            return true;
        }

        // Guard against implementations that generate a lot of uninteresting events in a short
        // space of time and would cause the time_zone_detector to evaluate time zone suggestions
        // too frequently.
        //
        // If the new event and last event sent are equivalent, the client will still send an update
        // if their creation times are sufficiently different. This enables the time_zone_detector
        // to better understand how recently the location time zone provider was certain /
        // uncertain, which can be useful when working out ordering of events, e.g. to work out
        // whether a suggestion was generated before or after a device left airplane mode.
        long timeSinceLastEventMillis =
                newEvent.getCreationElapsedMillis() - mLastEventSent.getCreationElapsedMillis();
        return timeSinceLastEventMillis > mEventFilteringAgeThresholdMillis;
    }

    private void onStartUpdatesInternal(@NonNull ITimeZoneProviderManager manager,
            @DurationMillisLong long initializationTimeoutMillis,
            @DurationMillisLong long eventFilteringAgeThresholdMillis) {
        synchronized (mLock) {
            mManager = manager;
            mEventFilteringAgeThresholdMillis =  eventFilteringAgeThresholdMillis;
            mLastEventSent = null;
            onStartUpdates(initializationTimeoutMillis);
        }
    }

    /**
     * Informs the provider that it should start detecting and reporting the detected time zone
     * state via the various {@code report} methods. Implementations of {@link
     * #onStartUpdates(long)} should return immediately, and will typically be used to start
     * worker threads or begin asynchronous location listening.
     *
     * <p>Between {@link #onStartUpdates(long)} and {@link #onStopUpdates()} calls, the Android
     * system server holds the latest report from the provider in memory. After an initial report,
     * provider implementations are only required to send a report via {@link
     * #reportSuggestion(TimeZoneProviderSuggestion)} or via {@link #reportUncertain()} when it
     * differs from the previous report.
     *
     * <p>{@link #reportPermanentFailure(Throwable)} can also be called by provider implementations
     * in rare cases, after which the provider should consider itself stopped and not make any
     * further reports. {@link #onStopUpdates()} will not be called in this case.
     *
     * <p>The {@code initializationTimeoutMillis} parameter indicates how long the provider has been
     * granted to call one of the {@code report} methods for the first time. If the provider does
     * not call one of the {@code report} methods in this time, it may be judged uncertain and the
     * Android system server may move on to use other providers or detection methods. Providers
     * should therefore make best efforts during this time to generate a report, which could involve
     * increased power usage. Providers should preferably report an explicit {@link
     * #reportUncertain()} if the time zone(s) cannot be detected within the initialization timeout.
     *
     * @see #onStopUpdates() for the signal from the system server to stop sending reports
     */
    public abstract void onStartUpdates(@DurationMillisLong long initializationTimeoutMillis);

    private void onStopUpdatesInternal() {
        synchronized (mLock) {
            onStopUpdates();
            mManager = null;
        }
    }

    /**
     * Stops the provider sending further updates. This will be called after {@link
     * #onStartUpdates(long)}.
     */
    public abstract void onStopUpdates();

    /** @hide */
    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        synchronized (mLock) {
            writer.append("mLastEventSent=" + mLastEventSent);
        }
    }

    private class TimeZoneProviderServiceWrapper extends ITimeZoneProvider.Stub {

        public void startUpdates(@NonNull ITimeZoneProviderManager manager,
                @DurationMillisLong long initializationTimeoutMillis,
                @DurationMillisLong long eventFilteringAgeThresholdMillis) {
            Objects.requireNonNull(manager);
            mHandler.post(() -> onStartUpdatesInternal(
                    manager, initializationTimeoutMillis, eventFilteringAgeThresholdMillis));
        }

        public void stopUpdates() {
            mHandler.post(TimeZoneProviderService.this::onStopUpdatesInternal);
        }
    }
}
