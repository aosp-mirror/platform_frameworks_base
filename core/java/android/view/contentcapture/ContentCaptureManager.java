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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;
import static android.view.contentcapture.ContentCaptureHelper.toSet;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.WindowManager;
import android.view.contentcapture.ContentCaptureSession.FlushReason;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Set;

/**
 * Content capture is mechanism used to let apps notify the Android system of events associated with
 * views.
 *
 * <p>Before using this manager, you should check if it's available. Example:
 * <pre><code>
 *  ContentCaptureManager mgr = context.getSystemService(ContentCaptureManager.class);
 *  if (mgr != null && mgr.isContentCaptureEnabled()) {
 *    // ...
 *  }
 *  </code></pre>
 *
 * <p>To support content capture, you must notifiy the Android system of the following events:
 *
 * <ul>
 *   <li>When a visible view is laid out, call
 *   {@link ContentCaptureSession#notifyViewAppeared(ViewStructure)}.
 *   <li>When a view becomes invisible or is removed from the view hierarchy, call
 *   {@link ContentCaptureSession#notifyViewDisappeared(android.view.autofill.AutofillId)}.
 *   <li>When the view represents text and the text value changed, call {@link
 *   ContentCaptureSession#notifyViewTextChanged(android.view.autofill.AutofillId, CharSequence)}.
 * </ul>
 *
 * <p>You can get a blank content capture structure using
 * {@link ContentCaptureSession#newViewStructure(View)}, then populate its relevant fields.
 * Here's an example of the relevant methods for an {@code EditText}-like view:
 *
 * <pre><code>
 * public class MyEditText extends View {
 *
 * private void populateContentCaptureStructure(@NonNull ViewStructure structure) {
 *   structure.setText(getText(), getSelectionStart(), getSelectionEnd());
 *   structure.setHint(getHint());
 *   structure.setInputType(getInputType());
 *   // set other properties like setTextIdEntry(), setTextLines(), setTextStyle(),
 *   // setMinTextEms(), setMaxTextEms(), setMaxTextLength()
 * }
 *
 * private void onTextChanged() {
 *   if (isLaidOut() && isTextEditable()) {
 *     ContentCaptureManager mgr = mContext.getSystemService(ContentCaptureManager.class);
 *     if (cm != null && cm.isContentCaptureEnabled()) {
 *        ContentCaptureSession session = getContentCaptureSession();
 *        if (session != null) {
 *          session.notifyViewTextChanged(getAutofillId(), getText());
 *        }
 *   }
 * }
 * </code></pre>
 *
 * <p>The main integration point with content capture is the {@link ContentCaptureSession}. A "main"
 * session is automatically created by the Android system when content capture is enabled for the
 * activity. The session could have a {@link ContentCaptureContext} to provide more contextual info
 * about it, such as the locus associated with the view hierarchy
 * (see {@link android.content.LocusId} for more info about locus). By default, the main session
 * doesn't have a {@code ContentCaptureContext}, but you can change it after its created. Example:
 *
 * <pre><code>
 * protected void onCreate(Bundle savedInstanceState) {
 *   // Initialize view structure
 *   ContentCaptureSession session = rootView.getContentCaptureSession();
 *   if (session != null) {
 *     session.setContentCaptureContext(ContentCaptureContext.forLocusId("chat_UserA_UserB"));
 *   }
 * }
 * </code></pre>
 *
 * <p>If your activity contains view hierarchies with a different contextual meaning, you should
 * created child sessions for each view hierarchy root. For example, if your activity is a browser,
 * you could use the main session for the main URL being rendered, then child sessions for each
 * {@code IFRAME}:
 *
 * <pre><code>
 * ContentCaptureSession mMainSession;
 *
 * protected void onCreate(Bundle savedInstanceState) {
 *    // Initialize view structure...
 *    mMainSession = rootView.getContentCaptureSession();
 *    if (mMainSession != null) {
 *      mMainSession.setContentCaptureContext(
 *          ContentCaptureContext.forLocusId("https://example.com"));
 *    }
 * }
 *
 * private void loadIFrame(View iframeRootView, String url) {
 *   if (mMainSession != null) {
 *      ContentCaptureSession iFrameSession = mMainSession.newChild(
 *          ContentCaptureContext.forLocusId(url));
 *      }
 *      iframeRootView.setContentCaptureSession(iFrameSession);
 *   }
 *   // Load iframe...
 * }
 * </code></pre>
 *
 */
@SystemService(Context.CONTENT_CAPTURE_MANAGER_SERVICE)
public final class ContentCaptureManager {

    private static final String TAG = ContentCaptureManager.class.getSimpleName();

    /** @hide */
    public static final int RESULT_CODE_OK = 0;
    /** @hide */
    public static final int RESULT_CODE_TRUE = 1;
    /** @hide */
    public static final int RESULT_CODE_FALSE = 2;
    /** @hide */
    public static final int RESULT_CODE_SECURITY_EXCEPTION = -1;

    /**
     * Timeout for calls to system_server.
     */
    private static final int SYNC_CALLS_TIMEOUT_MS = 5000;

    /**
     * DeviceConfig property used by {@code com.android.server.SystemServer} on start to decide
     * whether the content capture service should be created or not
     *
     * <p>By default it should *NOT* be set (or set to {@code "default"}, so the decision is based
     * on whether the OEM provides an implementation for the service), but it can be overridden to:
     *
     * <ul>
     *   <li>Provide a "kill switch" so OEMs can disable it remotely in case of emergency (when
     *   it's set to {@code "false"}).
     *   <li>Enable the CTS tests to be run on AOSP builds (when it's set to {@code "true"}).
     * </ul>
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_SERVICE_EXPLICITLY_ENABLED =
            "service_explicitly_enabled";

    /**
     * Maximum number of events that are buffered before sent to the app.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_MAX_BUFFER_SIZE = "max_buffer_size";

    /**
     * Frequency (in ms) of buffer flushes when no events are received.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_IDLE_FLUSH_FREQUENCY = "idle_flush_frequency";

    /**
     * Frequency (in ms) of buffer flushes when no events are received and the last one was a
     * text change event.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_TEXT_CHANGE_FLUSH_FREQUENCY =
            "text_change_flush_frequency";

    /**
     * Size of events that are logging on {@code dump}.
     *
     * <p>Set it to {@code 0} or less to disable history.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_LOG_HISTORY_SIZE = "log_history_size";

    /**
     * Sets the logging level for {@code logcat} statements.
     *
     * <p>Valid values are: {@link #LOGGING_LEVEL_OFF}, {@value #LOGGING_LEVEL_DEBUG}, and
     * {@link #LOGGING_LEVEL_VERBOSE}.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL = "logging_level";

    /**
     * Sets how long (in ms) the service is bound while idle.
     *
     * <p>Use {@code 0} to keep it permanently bound.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_PROPERTY_IDLE_UNBIND_TIMEOUT = "idle_unbind_timeout";

    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_OFF = 0;

    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_DEBUG = 1;

    /** @hide */
    @TestApi
    public static final int LOGGING_LEVEL_VERBOSE = 2;

    /** @hide */
    @IntDef(flag = false, value = {
            LOGGING_LEVEL_OFF,
            LOGGING_LEVEL_DEBUG,
            LOGGING_LEVEL_VERBOSE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LoggingLevel {}


    /** @hide */
    public static final int DEFAULT_MAX_BUFFER_SIZE = 100;
    /** @hide */
    public static final int DEFAULT_IDLE_FLUSHING_FREQUENCY_MS = 5_000;
    /** @hide */
    public static final int DEFAULT_TEXT_CHANGE_FLUSHING_FREQUENCY_MS = 1_000;
    /** @hide */
    public static final int DEFAULT_LOG_HISTORY_SIZE = 10;

    private final Object mLock = new Object();

    @NonNull
    private final Context mContext;

    @NonNull
    private final IContentCaptureManager mService;

    @NonNull
    final ContentCaptureOptions mOptions;

    // Flags used for starting session.
    @GuardedBy("mLock")
    private int mFlags;

    // TODO(b/119220549): use UI Thread directly (as calls are one-way) or a shared thread / handler
    // held at the Application level
    @NonNull
    private final Handler mHandler;

    @GuardedBy("mLock")
    private MainContentCaptureSession mMainSession;

    /** @hide */
    public interface ContentCaptureClient {
        /**
         * Gets the component name of the client.
         */
        @NonNull
        ComponentName contentCaptureClientGetComponentName();
    }

    /** @hide */
    public ContentCaptureManager(@NonNull Context context,
            @NonNull IContentCaptureManager service, @NonNull ContentCaptureOptions options) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        mService = Preconditions.checkNotNull(service, "service cannot be null");
        mOptions = Preconditions.checkNotNull(options, "options cannot be null");

        ContentCaptureHelper.setLoggingLevel(mOptions.loggingLevel);

        if (sVerbose) Log.v(TAG, "Constructor for " + context.getPackageName());

        // TODO(b/119220549): we might not even need a handler, as the IPCs are oneway. But if we
        // do, then we should optimize it to run the tests after the Choreographer finishes the most
        // important steps of the frame.
        mHandler = Handler.createAsync(Looper.getMainLooper());
    }

    /**
     * Gets the main session associated with the context.
     *
     * <p>By default there's just one (associated with the activity lifecycle), but apps could
     * explicitly add more using
     * {@link ContentCaptureSession#createContentCaptureSession(ContentCaptureContext)}.
     *
     * @hide
     */
    @NonNull
    @UiThread
    public MainContentCaptureSession getMainContentCaptureSession() {
        synchronized (mLock) {
            if (mMainSession == null) {
                mMainSession = new MainContentCaptureSession(mContext, this, mHandler, mService);
                if (sVerbose) Log.v(TAG, "getMainContentCaptureSession(): created " + mMainSession);
            }
            return mMainSession;
        }
    }

    /** @hide */
    @UiThread
    public void onActivityCreated(@NonNull IBinder applicationToken,
            @NonNull ComponentName activityComponent) {
        if (mOptions.lite) return;
        synchronized (mLock) {
            getMainContentCaptureSession().start(applicationToken, activityComponent, mFlags);
        }
    }

    /** @hide */
    @UiThread
    public void onActivityResumed() {
        if (mOptions.lite) return;
        getMainContentCaptureSession().notifySessionLifecycle(/* started= */ true);
    }

    /** @hide */
    @UiThread
    public void onActivityPaused() {
        if (mOptions.lite) return;
        getMainContentCaptureSession().notifySessionLifecycle(/* started= */ false);
    }

    /** @hide */
    @UiThread
    public void onActivityDestroyed() {
        if (mOptions.lite) return;
        getMainContentCaptureSession().destroy();
    }

    /**
     * Flushes the content of all sessions.
     *
     * <p>Typically called by {@code Activity} when it's paused / resumed.
     *
     * @hide
     */
    @UiThread
    public void flush(@FlushReason int reason) {
        if (mOptions.lite) return;
        getMainContentCaptureSession().flush(reason);
    }

    /**
     * Returns the component name of the system service that is consuming the captured events for
     * the current user.
     */
    @Nullable
    public ComponentName getServiceComponentName() {
        if (!isContentCaptureEnabled() && !mOptions.lite) return null;

        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getServiceComponentName(resultReceiver);
            return resultReceiver.getParcelableResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the (optional) intent used to launch the service-specific settings.
     *
     * <p>This method is static because it's called by Settings, which might not be whitelisted
     * for content capture (in which case the ContentCaptureManager on its context would be null).
     *
     * @hide
     */
    // TODO: use "lite" options as it's done by activities from the content capture service
    @Nullable
    public static ComponentName getServiceSettingsComponentName() {
        final IBinder binder = ServiceManager
                .checkService(Context.CONTENT_CAPTURE_MANAGER_SERVICE);
        if (binder == null) return null;

        final IContentCaptureManager service = IContentCaptureManager.Stub.asInterface(binder);
        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            service.getServiceSettingsActivity(resultReceiver);
            final int resultCode = resultReceiver.getIntResult();
            if (resultCode == RESULT_CODE_SECURITY_EXCEPTION) {
                throw new SecurityException(resultReceiver.getStringResult());
            }
            return resultReceiver.getParcelableResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether content capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        if (mOptions.lite) return false;

        final MainContentCaptureSession mainSession;
        synchronized (mLock) {
            mainSession = mMainSession;
        }
        // The main session is only set when the activity starts, so we need to return true until
        // then.
        if (mainSession != null && mainSession.isDisabled()) return false;

        return true;
    }

    /**
     * Gets the list of conditions for when content capture should be allowed.
     *
     * <p>This method is typically used by web browsers so they don't generate unnecessary content
     * capture events for some websites.
     *
     * @return list of conditions, or {@code null} if there isn't any restriction
     * (in which case content capture events should always be generated). If the list is empty,
     * then it should not generate any event at all.
     */
    @Nullable
    public Set<ContentCaptureCondition> getContentCaptureConditions() {
        // NOTE: we could cache the conditions on ContentCaptureOptions, but then it would be stick
        // to the lifetime of the app. OTOH, by dynamically calling the server every time, we allow
        // the service to fine tune how long-lived apps (like browsers) are whitelisted.
        if (!isContentCaptureEnabled() && !mOptions.lite) return null;

        final SyncResultReceiver resultReceiver = syncRun(
                (r) -> mService.getContentCaptureConditions(mContext.getPackageName(), r));

        final ArrayList<ContentCaptureCondition> result = resultReceiver
                .getParcelableListResult();
        return toSet(result);
    }

    /**
     * Called by apps to explicitly enable or disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void setContentCaptureEnabled(boolean enabled) {
        if (sDebug) {
            Log.d(TAG, "setContentCaptureEnabled(): setting to " + enabled + " for " + mContext);
        }

        MainContentCaptureSession mainSession;
        synchronized (mLock) {
            if (enabled) {
                mFlags &= ~ContentCaptureContext.FLAG_DISABLED_BY_APP;
            } else {
                mFlags |= ContentCaptureContext.FLAG_DISABLED_BY_APP;
            }
            mainSession = mMainSession;
        }
        if (mainSession != null) {
            mainSession.setDisabled(!enabled);
        }
    }

    /**
     * Called by apps to update flag secure when window attributes change.
     *
     * @hide
     */
    public void updateWindowAttributes(@NonNull WindowManager.LayoutParams params) {
        if (sDebug) {
            Log.d(TAG, "updateWindowAttributes(): window flags=" + params.flags);
        }
        final boolean flagSecureEnabled =
                (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;

        MainContentCaptureSession mainSession;
        synchronized (mLock) {
            if (flagSecureEnabled) {
                mFlags |= ContentCaptureContext.FLAG_DISABLED_BY_FLAG_SECURE;
            } else {
                mFlags &= ~ContentCaptureContext.FLAG_DISABLED_BY_FLAG_SECURE;
            }
            mainSession = mMainSession;
        }
        if (mainSession != null) {
            mainSession.setDisabled(flagSecureEnabled);
        }
    }

    /**
     * Gets whether content capture is enabled for the given user.
     *
     * <p>This method is typically used by the content capture service settings page, so it can
     * provide a toggle to enable / disable it.
     *
     * @throws SecurityException if caller is not the app that owns the content capture service
     * associated with the user.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public boolean isContentCaptureFeatureEnabled() {
        final SyncResultReceiver resultReceiver = syncRun(
                (r) -> mService.isContentCaptureFeatureEnabled(r));
        final int resultCode = resultReceiver.getIntResult();
        switch (resultCode) {
            case RESULT_CODE_TRUE:
                return true;
            case RESULT_CODE_FALSE:
                return false;
            default:
                Log.wtf(TAG, "received invalid result: " + resultCode);
                return false;
        }
    }

    /**
     * Called by the app to remove content capture data associated with some context.
     *
     * @param request object specifying what data should be removed.
     */
    public void removeData(@NonNull DataRemovalRequest request) {
        Preconditions.checkNotNull(request);

        try {
            mService.removeData(request);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Runs a sync method in the service, properly handling exceptions.
     *
     * @throws SecurityException if caller is not allowed to execute the method.
     */
    @NonNull
    private SyncResultReceiver syncRun(@NonNull MyRunnable r) {
        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            r.run(resultReceiver);
            final int resultCode = resultReceiver.getIntResult();
            if (resultCode == RESULT_CODE_SECURITY_EXCEPTION) {
                throw new SecurityException(resultReceiver.getStringResult());
            }
            return resultReceiver;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("ContentCaptureManager");
        final String prefix2 = prefix + "  ";
        synchronized (mLock) {
            pw.print(prefix2); pw.print("isContentCaptureEnabled(): ");
            pw.println(isContentCaptureEnabled());
            pw.print(prefix2); pw.print("Debug: "); pw.print(sDebug);
            pw.print(" Verbose: "); pw.println(sVerbose);
            pw.print(prefix2); pw.print("Context: "); pw.println(mContext);
            pw.print(prefix2); pw.print("User: "); pw.println(mContext.getUserId());
            pw.print(prefix2); pw.print("Service: "); pw.println(mService);
            pw.print(prefix2); pw.print("Flags: "); pw.println(mFlags);
            pw.print(prefix2); pw.print("Options: "); mOptions.dumpShort(pw); pw.println();
            if (mMainSession != null) {
                final String prefix3 = prefix2 + "  ";
                pw.print(prefix2); pw.println("Main session:");
                mMainSession.dump(prefix3, pw);
            } else {
                pw.print(prefix2); pw.println("No sessions");
            }
        }
    }

    private interface MyRunnable {
        void run(@NonNull SyncResultReceiver receiver) throws RemoteException;
    }
}
