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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Dumpable;
import android.util.Log;
import android.util.Slog;
import android.view.View;
import android.view.ViewStructure;
import android.view.WindowManager;
import android.view.contentcapture.ContentCaptureSession.FlushReason;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.SyncResultReceiver;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * <p>Provides additional ways for apps to integrate with the content capture subsystem.
 *
 * <p>Content capture provides real-time, continuous capture of application activity, display and
 * events to an intelligence service that is provided by the Android system. The intelligence
 * service then uses that info to mediate and speed user journey through different apps. For
 * example, when the user receives a restaurant address in a chat app and switches to a map app
 * to search for that restaurant, the intelligence service could offer an autofill dialog to
 * let the user automatically select its address.
 *
 * <p>Content capture was designed with two major concerns in mind: privacy and performance.
 *
 * <ul>
 *   <li><b>Privacy:</b> the intelligence service is a trusted component provided that is provided
 *   by the device manufacturer and that cannot be changed by the user (although the user can
 *   globaly disable content capture using the Android Settings app). This service can only use the
 *   data for in-device machine learning, which is enforced both by process isolation and
 *   <a href="https://source.android.com/compatibility/cdd">CDD requirements</a>.
 *   <li><b>Performance:</b> content capture is highly optimized to minimize its impact in the app
 *   jankiness and overall device system health. For example, its only enabled on apps (or even
 *   specific activities from an app) that were explicitly allowlisted by the intelligence service,
 *   and it buffers the events so they are sent in a batch to the service (see
 *   {@link #isContentCaptureEnabled()} for other cases when its disabled).
 * </ul>
 *
 * <p>In fact, before using this manager, the app developer should check if it's available. Example:
 * <pre><code>
 *  ContentCaptureManager mgr = context.getSystemService(ContentCaptureManager.class);
 *  if (mgr != null && mgr.isContentCaptureEnabled()) {
 *    // ...
 *  }
 *  </code></pre>
 *
 * <p>App developers usually don't need to explicitly interact with content capture, except when the
 * app:
 *
 * <ul>
 *   <li>Can define a contextual {@link android.content.LocusId} to identify unique state (such as a
 *   conversation between 2 chat users).
 *   <li>Can have multiple view hierarchies with different contextual meaning (for example, a
 *   browser app with multiple tabs, each representing a different URL).
 *   <li>Contains custom views (that extend View directly and are not provided by the standard
 *   Android SDK.
 *   <li>Contains views that provide their own virtual hierarchy (like a web browser that render the
 *   HTML elements using a Canvas).
 * </ul>
 *
 * <p>The main integration point with content capture is the {@link ContentCaptureSession}. A "main"
 * session is automatically created by the Android System when content capture is enabled for the
 * activity and its used by the standard Android views to notify the content capture service of
 * events such as views being added, views been removed, and text changed by user input. The session
 * could have a {@link ContentCaptureContext} to provide more contextual info about it, such as
 * the locus associated with the view hierarchy (see {@link android.content.LocusId} for more info
 * about locus). By default, the main session doesn't have a {@code ContentCaptureContext}, but you
 * can change it after its created. Example:
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
 * <p>If your activity has custom views (i.e., views that extend {@link View} directly and provide
 * just one logical view, not a virtual tree hiearchy) and it provides content that's relevant for
 * content capture (as of {@link android.os.Build.VERSION_CODES#Q Android Q}, the only relevant
 * content is text), then your view implementation should:
 *
 * <ul>
 *   <li>Set it as important for content capture.
 *   <li>Fill {@link ViewStructure} used for content capture.
 *   <li>Notify the {@link ContentCaptureSession} when the text is changed by user input.
 * </ul>
 *
 * <p>Here's an example of the relevant methods for an {@code EditText}-like view:
 *
 * <pre><code>
 * public class MyEditText extends View {
 *
 * public MyEditText(...) {
 *   if (getImportantForContentCapture() == IMPORTANT_FOR_CONTENT_CAPTURE_AUTO) {
 *     setImportantForContentCapture(IMPORTANT_FOR_CONTENT_CAPTURE_YES);
 *   }
 * }
 *
 * public void onProvideContentCaptureStructure(@NonNull ViewStructure structure, int flags) {
 *   super.onProvideContentCaptureStructure(structure, flags);
 *
 *   structure.setText(getText(), getSelectionStart(), getSelectionEnd());
 *   structure.setHint(getHint());
 *   structure.setInputType(getInputType());
 *   // set other properties like setTextIdEntry(), setTextLines(), setTextStyle(),
 *   // setMinTextEms(), setMaxTextEms(), setMaxTextLength()
 * }
 *
 * private void onTextChanged() {
 *   if (isLaidOut() && isImportantForContentCapture() && isTextEditable()) {
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
 * <p>If your view provides its own virtual hierarchy (for example, if it's a browser that draws
 * the HTML using {@link Canvas} or native libraries in a different render process), then the view
 * is also responsible to notify the session when the virtual elements appear and disappear - see
 * {@link View#onProvideContentCaptureStructure(ViewStructure, int)} for more info.
 */
@SystemService(Context.CONTENT_CAPTURE_MANAGER_SERVICE)
public final class ContentCaptureManager {

    private static final String TAG = ContentCaptureManager.class.getSimpleName();

    /** @hide */
    public static final boolean DEBUG = false;

    /** @hide */
    @TestApi
    public static final String DUMPABLE_NAME = "ContentCaptureManager";

    /** Error happened during the data sharing session. */
    public static final int DATA_SHARE_ERROR_UNKNOWN = 1;

    /** Request has been rejected, because a concurrent data share sessions is in progress. */
    public static final int DATA_SHARE_ERROR_CONCURRENT_REQUEST = 2;

    /** Request has been interrupted because of data share session timeout. */
    public static final int DATA_SHARE_ERROR_TIMEOUT_INTERRUPTED = 3;

    /** @hide */
    @IntDef(flag = false, value = {
            DATA_SHARE_ERROR_UNKNOWN,
            DATA_SHARE_ERROR_CONCURRENT_REQUEST,
            DATA_SHARE_ERROR_TIMEOUT_INTERRUPTED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DataShareError {}

    /** @hide */
    public static final int RESULT_CODE_OK = 0;
    /** @hide */
    public static final int RESULT_CODE_TRUE = 1;
    /** @hide */
    public static final int RESULT_CODE_FALSE = 2;
    /** @hide */
    public static final int RESULT_CODE_SECURITY_EXCEPTION = -1;

    /**
     * ID used to indicate that a session does not exist
     * @hide
     */
    @SystemApi
    public static final int NO_SESSION_ID = 0;

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
    public static final int DEFAULT_MAX_BUFFER_SIZE = 500; // Enough for typical busy screen.
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

    @GuardedBy("mLock")
    private final LocalDataShareAdapterResourceManager mDataShareAdapterResourceManager;

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

    @Nullable // set on-demand by addDumpable()
    private Dumper mDumpable;

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
        mContext = Objects.requireNonNull(context, "context cannot be null");
        mService = Objects.requireNonNull(service, "service cannot be null");
        mOptions = Objects.requireNonNull(options, "options cannot be null");

        ContentCaptureHelper.setLoggingLevel(mOptions.loggingLevel);

        if (sVerbose) Log.v(TAG, "Constructor for " + context.getPackageName());

        // TODO(b/119220549): we might not even need a handler, as the IPCs are oneway. But if we
        // do, then we should optimize it to run the tests after the Choreographer finishes the most
        // important steps of the frame.
        mHandler = Handler.createAsync(Looper.getMainLooper());

        mDataShareAdapterResourceManager = new LocalDataShareAdapterResourceManager();
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
            @NonNull IBinder shareableActivityToken, @NonNull ComponentName activityComponent) {
        if (mOptions.lite) return;
        synchronized (mLock) {
            getMainContentCaptureSession().start(applicationToken, shareableActivityToken,
                    activityComponent, mFlags);
        }
    }

    /** @hide */
    @UiThread
    public void onActivityResumed() {
        if (mOptions.lite) return;
        getMainContentCaptureSession().notifySessionResumed();
    }

    /** @hide */
    @UiThread
    public void onActivityPaused() {
        if (mOptions.lite) return;
        getMainContentCaptureSession().notifySessionPaused();
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
     *
     * @throws RuntimeException if getting the component name is timed out.
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
        } catch (SyncResultReceiver.TimeoutException e) {
            throw new RuntimeException("Fail to get service componentName.");
        }
    }

    /**
     * Gets the (optional) intent used to launch the service-specific settings.
     *
     * <p>This method is static because it's called by Settings, which might not be allowlisted
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
        } catch (SyncResultReceiver.TimeoutException e) {
            Log.e(TAG, "Fail to get service settings componentName: " + e);
            return null;
        }
    }

    /**
     * Checks whether content capture is enabled for this activity.
     *
     * <p>There are many reasons it could be disabled, such as:
     * <ul>
     *   <li>App itself disabled content capture through {@link #setContentCaptureEnabled(boolean)}.
     *   <li>Intelligence service did not allowlist content capture for this activity's package.
     *   <li>Intelligence service did not allowlist content capture for this specific activity.
     *   <li>Intelligence service disabled content capture globally.
     *   <li>User disabled content capture globally through the Android Settings app.
     *   <li>Device manufacturer (OEM) disabled content capture globally.
     *   <li>Transient errors, such as intelligence service package being updated.
     * </ul>
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
     * capture events for websites the content capture service is not interested on.
     *
     * @return list of conditions, or {@code null} if the service didn't set any restriction
     * (in which case content capture events should always be generated). If the list is empty,
     * then it should not generate any event at all.
     */
    @Nullable
    public Set<ContentCaptureCondition> getContentCaptureConditions() {
        // NOTE: we could cache the conditions on ContentCaptureOptions, but then it would be stick
        // to the lifetime of the app. OTOH, by dynamically calling the server every time, we allow
        // the service to fine tune how long-lived apps (like browsers) are allowlisted.
        if (!isContentCaptureEnabled() && !mOptions.lite) return null;

        final SyncResultReceiver resultReceiver = syncRun(
                (r) -> mService.getContentCaptureConditions(mContext.getPackageName(), r));

        try {
            final ArrayList<ContentCaptureCondition> result = resultReceiver
                    .getParcelableListResult();
            return toSet(result);
        } catch (SyncResultReceiver.TimeoutException e) {
            throw new RuntimeException("Fail to get content capture conditions.");
        }
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
    public boolean isContentCaptureFeatureEnabled() {
        final SyncResultReceiver resultReceiver = syncRun(
                (r) -> mService.isContentCaptureFeatureEnabled(r));

        try {
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
        } catch (SyncResultReceiver.TimeoutException e) {
            Log.e(TAG, "Fail to get content capture feature enable status: " + e);
            return false;
        }
    }

    /**
     * Called by the app to request the content capture service to remove content capture data
     * associated with some context.
     *
     * @param request object specifying what user data should be removed.
     */
    public void removeData(@NonNull DataRemovalRequest request) {
        Objects.requireNonNull(request);

        try {
            mService.removeData(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Called by the app to request data sharing via writing to a file.
     *
     * <p>The ContentCaptureService app will receive a read-only file descriptor pointing to the
     * same file and will be able to read data being shared from it.
     *
     * <p>Note: using this API doesn't guarantee the app staying alive and is "best-effort".
     * Starting a foreground service would minimize the chances of the app getting killed during the
     * file sharing session.
     *
     * @param request object specifying details of the data being shared.
     */
    public void shareData(@NonNull DataShareRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull DataShareWriteAdapter dataShareWriteAdapter) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(dataShareWriteAdapter);
        Objects.requireNonNull(executor);

        try {
            mService.shareData(request,
                    new DataShareAdapterDelegate(executor, dataShareWriteAdapter,
                            mDataShareAdapterResourceManager));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (SyncResultReceiver.TimeoutException e) {
            throw new RuntimeException("Fail to get syn run result from SyncResultReceiver.");
        }
        return resultReceiver;
    }

    /** @hide */
    public void addDumpable(Activity activity) {
        if (mDumpable == null) {
            mDumpable = new Dumper();
        }
        activity.addDumpable(mDumpable);
    }

    // NOTE: ContentCaptureManager cannot implement it directly as it would be exposed as public API
    private final class Dumper implements Dumpable {
        @Override
        public void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
            String prefix = "";
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

        @Override
        public String getDumpableName() {
            return DUMPABLE_NAME;
        }
    }

    /**
     * Resets the temporary content capture service implementation to the default component.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_CONTENT_CAPTURE)
    public static void resetTemporaryService(@UserIdInt int userId) {
        final IContentCaptureManager service = getService();
        if (service == null) {
            Log.e(TAG, "IContentCaptureManager is null");
        }
        try {
            service.resetTemporaryService(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily sets the content capture service implementation.
     *
     * @param userId user Id to set the temporary service on.
     * @param serviceName name of the new component
     * @param duration how long the change will be valid (the service will be automatically reset
     * to the default component after this timeout expires).
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_CONTENT_CAPTURE)
    public static void setTemporaryService(
            @UserIdInt int userId, @NonNull String serviceName, int duration) {
        final IContentCaptureManager service = getService();
        if (service == null) {
            Log.e(TAG, "IContentCaptureManager is null");
        }
        try {
            service.setTemporaryService(userId, serviceName, duration);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets whether the default content capture service should be used.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_CONTENT_CAPTURE)
    public static void setDefaultServiceEnabled(@UserIdInt int userId, boolean enabled) {
        final IContentCaptureManager service = getService();
        if (service == null) {
            Log.e(TAG, "IContentCaptureManager is null");
        }
        try {
            service.setDefaultServiceEnabled(userId, enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static IContentCaptureManager getService() {
        return IContentCaptureManager.Stub.asInterface(ServiceManager.getService(
                Service.CONTENT_CAPTURE_MANAGER_SERVICE));
    }

    private interface MyRunnable {
        void run(@NonNull SyncResultReceiver receiver) throws RemoteException;
    }

    private static class DataShareAdapterDelegate extends IDataShareWriteAdapter.Stub {

        private final WeakReference<LocalDataShareAdapterResourceManager> mResourceManagerReference;

        private DataShareAdapterDelegate(Executor executor, DataShareWriteAdapter adapter,
                LocalDataShareAdapterResourceManager resourceManager) {
            Objects.requireNonNull(executor);
            Objects.requireNonNull(adapter);
            Objects.requireNonNull(resourceManager);

            resourceManager.initializeForDelegate(this, adapter, executor);
            mResourceManagerReference = new WeakReference<>(resourceManager);
        }

        @Override
        public void write(ParcelFileDescriptor destination)
                throws RemoteException {
            executeAdapterMethodLocked(adapter -> adapter.onWrite(destination), "onWrite");
        }

        @Override
        public void error(int errorCode) throws RemoteException {
            executeAdapterMethodLocked(adapter -> adapter.onError(errorCode), "onError");
            clearHardReferences();
        }

        @Override
        public void rejected() throws RemoteException {
            executeAdapterMethodLocked(DataShareWriteAdapter::onRejected, "onRejected");
            clearHardReferences();
        }

        @Override
        public void finish() throws RemoteException  {
            clearHardReferences();
        }

        private void executeAdapterMethodLocked(Consumer<DataShareWriteAdapter> adapterFn,
                String methodName) {
            LocalDataShareAdapterResourceManager resourceManager = mResourceManagerReference.get();
            if (resourceManager == null) {
                Slog.w(TAG, "Can't execute " + methodName + "(), resource manager has been GC'ed");
                return;
            }

            DataShareWriteAdapter adapter = resourceManager.getAdapter(this);
            Executor executor = resourceManager.getExecutor(this);

            if (adapter == null || executor == null) {
                Slog.w(TAG, "Can't execute " + methodName + "(), references are null");
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                executor.execute(() -> adapterFn.accept(adapter));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void clearHardReferences() {
            LocalDataShareAdapterResourceManager resourceManager = mResourceManagerReference.get();
            if (resourceManager == null) {
                Slog.w(TAG, "Can't clear references, resource manager has been GC'ed");
                return;
            }

            resourceManager.clearHardReferences(this);
        }
    }

    /**
     * Wrapper class making sure dependencies on the current application stay in the application
     * context.
     */
    private static class LocalDataShareAdapterResourceManager {

        // Keeping hard references to the remote objects in the current process (static context)
        // to prevent them to be gc'ed during the lifetime of the application. This is an
        // artifact of only operating with weak references remotely: there has to be at least 1
        // hard reference in order for this to not be killed.
        private Map<DataShareAdapterDelegate, DataShareWriteAdapter> mWriteAdapterHardReferences =
                new HashMap<>();
        private Map<DataShareAdapterDelegate, Executor> mExecutorHardReferences =
                new HashMap<>();

        void initializeForDelegate(DataShareAdapterDelegate delegate, DataShareWriteAdapter adapter,
                Executor executor) {
            mWriteAdapterHardReferences.put(delegate, adapter);
            mExecutorHardReferences.put(delegate, executor);
        }

        Executor getExecutor(DataShareAdapterDelegate delegate) {
            return mExecutorHardReferences.get(delegate);
        }

        DataShareWriteAdapter getAdapter(DataShareAdapterDelegate delegate) {
            return mWriteAdapterHardReferences.get(delegate);
        }

        void clearHardReferences(DataShareAdapterDelegate delegate) {
            mWriteAdapterHardReferences.remove(delegate);
            mExecutorHardReferences.remove(delegate);
        }
    }
}
