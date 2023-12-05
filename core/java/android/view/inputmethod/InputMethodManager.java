/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;

import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.inputmethod.Flags.FLAG_HOME_SCREEN_HANDWRITING_DELEGATOR;
import static android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE;
import static android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.DISPLAY_ID;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.EDITOR_INFO;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.IME_INSETS_SOURCE_CONSUMER;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.INPUT_CONNECTION;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.INPUT_CONNECTION_CALL;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.INPUT_METHOD_MANAGER;
import static android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto.ClientSideProto.VIEW_ROOT_IMPL;
import static android.view.inputmethod.InputMethodManagerProto.ACTIVE;
import static android.view.inputmethod.InputMethodManagerProto.CUR_ID;
import static android.view.inputmethod.InputMethodManagerProto.FULLSCREEN_MODE;
import static android.view.inputmethod.InputMethodManagerProto.NEXT_SERVED_VIEW;
import static android.view.inputmethod.InputMethodManagerProto.SERVED_CONNECTING;
import static android.view.inputmethod.InputMethodManagerProto.SERVED_VIEW;

import static com.android.internal.inputmethod.StartInputReason.BOUND_TO_IMMS;

import android.Manifest;
import android.annotation.DisplayContext;
import android.annotation.DrawableRes;
import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.app.PropertyInvalidatedCache;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.util.Pair;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;
import android.view.ImeFocusController;
import android.view.ImeInsetsSourceConsumer;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventSender;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.window.ImeOnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.InputMethodPrivilegedOperationsRegistry;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.os.SomeArgs;
import com.android.internal.view.IInputMethodManager;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Central system API to the overall input method framework (IMF) architecture,
 * which arbitrates interaction between applications and the current input method.
 *
 * <p>Topics covered here:
 * <ol>
 * <li><a href="#ArchitectureOverview">Architecture Overview</a>
 * <li><a href="#Applications">Applications</a>
 * <li><a href="#InputMethods">Input Methods</a>
 * <li><a href="#Security">Security</a>
 * </ol>
 *
 * <a name="ArchitectureOverview"></a>
 * <h3>Architecture Overview</h3>
 *
 * <p>There are three primary parties involved in the input method
 * framework (IMF) architecture:</p>
 *
 * <ul>
 * <li> The <strong>input method manager</strong> as expressed by this class
 * is the central point of the system that manages interaction between all
 * other parts.  It is expressed as the client-side API here which exists
 * in each application context and communicates with a global system service
 * that manages the interaction across all processes.
 * <li> An <strong>input method (IME)</strong> implements a particular
 * interaction model allowing the user to generate text.  The system binds
 * to the current input method that is in use, causing it to be created and run,
 * and tells it when to hide and show its UI.  Only one IME is running at a time.
 * <li> Multiple <strong>client applications</strong> arbitrate with the input
 * method manager for input focus and control over the state of the IME.  Only
 * one such client is ever active (working with the IME) at a time.
 * </ul>
 *
 *
 * <a name="Applications"></a>
 * <h3>Applications</h3>
 *
 * <p>In most cases, applications that are using the standard
 * {@link android.widget.TextView} or its subclasses will have little they need
 * to do to work well with soft input methods.  The main things you need to
 * be aware of are:</p>
 *
 * <ul>
 * <li> Properly set the {@link android.R.attr#inputType} in your editable
 * text views, so that the input method will have enough context to help the
 * user in entering text into them.
 * <li> Deal well with losing screen space when the input method is
 * displayed.  Ideally an application should handle its window being resized
 * smaller, but it can rely on the system performing panning of the window
 * if needed.  You should set the {@link android.R.attr#windowSoftInputMode}
 * attribute on your activity or the corresponding values on windows you
 * create to help the system determine whether to pan or resize (it will
 * try to determine this automatically but may get it wrong).
 * <li> You can also control the preferred soft input state (open, closed, etc)
 * for your window using the same {@link android.R.attr#windowSoftInputMode}
 * attribute.
 * </ul>
 *
 * <p>More finer-grained control is available through the APIs here to directly
 * interact with the IMF and its IME -- either showing or hiding the input
 * area, letting the user pick an input method, etc.</p>
 *
 * <p>For the rare people amongst us writing their own text editors, you
 * will need to implement {@link android.view.View#onCreateInputConnection}
 * to return a new instance of your own {@link InputConnection} interface
 * allowing the IME to interact with your editor.</p>
 *
 *
 * <a name="InputMethods"></a>
 * <h3>Input Methods</h3>
 *
 * <p>An input method (IME) is implemented
 * as a {@link android.app.Service}, typically deriving from
 * {@link android.inputmethodservice.InputMethodService}.  It must provide
 * the core {@link InputMethod} interface, though this is normally handled by
 * {@link android.inputmethodservice.InputMethodService} and implementors will
 * only need to deal with the higher-level API there.</p>
 *
 * See the {@link android.inputmethodservice.InputMethodService} class for
 * more information on implementing IMEs.
 *
 *
 * <a name="Security"></a>
 * <h3>Security</h3>
 *
 * <p>There are a lot of security issues associated with input methods,
 * since they essentially have freedom to completely drive the UI and monitor
 * everything the user enters.  The Android input method framework also allows
 * arbitrary third party IMEs, so care must be taken to restrict their
 * selection and interactions.</p>
 *
 * <p>Here are some key points about the security architecture behind the
 * IMF:</p>
 *
 * <ul>
 * <li> <p>Only the system is allowed to directly access an IME's
 * {@link InputMethod} interface, via the
 * {@link android.Manifest.permission#BIND_INPUT_METHOD} permission.  This is
 * enforced in the system by not binding to an input method service that does
 * not require this permission, so the system can guarantee no other untrusted
 * clients are accessing the current input method outside of its control.</p>
 *
 * <li> <p>There may be many client processes of the IMF, but only one may
 * be active at a time.  The inactive clients can not interact with key
 * parts of the IMF through the mechanisms described below.</p>
 *
 * <li> <p>Clients of an input method are only given access to its
 * {@link InputMethodSession} interface.  One instance of this interface is
 * created for each client, and only calls from the session associated with
 * the active client will be processed by the current IME.  This is enforced
 * by {@link android.inputmethodservice.AbstractInputMethodService} for normal
 * IMEs, but must be explicitly handled by an IME that is customizing the
 * raw {@link InputMethodSession} implementation.</p>
 *
 * <li> <p>Only the active client's {@link InputConnection} will accept
 * operations.  The IMF tells each client process whether it is active, and
 * the framework enforces that in inactive processes calls on to the current
 * InputConnection will be ignored.  This ensures that the current IME can
 * only deliver events and text edits to the UI that the user sees as
 * being in focus.</p>
 *
 * <li> <p>An IME can never interact with an {@link InputConnection} while
 * the screen is off.  This is enforced by making all clients inactive while
 * the screen is off, and prevents bad IMEs from driving the UI when the user
 * can not be aware of its behavior.</p>
 *
 * <li> <p>A client application can ask that the system let the user pick a
 * new IME, but can not programmatically switch to one itself.  This avoids
 * malicious applications from switching the user to their own IME, which
 * remains running when the user navigates away to another application.  An
 * IME, on the other hand, <em>is</em> allowed to programmatically switch
 * the system to another IME, since it already has full control of user
 * input.</p>
 *
 * <li> <p>The user must explicitly enable a new IME in settings before
 * they can switch to it, to confirm with the system that they know about it
 * and want to make it available for use.</p>
 * </ul>
 *
 * <p>If your app targets Android 11 (API level 30) or higher, the methods in
 * this class each return a filtered result by the rules of
 * <a href="/training/basics/intents/package-visibility">package visibility</a>,
 * except for the currently connected IME. Apps having a query for the
 * {@link InputMethod#SERVICE_INTERFACE} see all IMEs.</p>
 */
@SystemService(Context.INPUT_METHOD_SERVICE)
@RequiresFeature(PackageManager.FEATURE_INPUT_METHODS)
public final class InputMethodManager {
    private static final boolean DEBUG = false;
    private static final String TAG = "InputMethodManager";

    private static final String PENDING_EVENT_COUNTER = "aq:imm";

    private static final int NOT_A_SUBTYPE_ID = -1;

    /**
     * A constant that represents Voice IME.
     *
     * @see InputMethodSubtype#getMode()
     */
    private static final String SUBTYPE_MODE_VOICE = "voice";

    /**
     * Provide this to {@link IInputMethodManagerGlobalInvoker#startInputOrWindowGainedFocus(int,
     * IInputMethodClient, IBinder, int, int, int, EditorInfo,
     * com.android.internal.inputmethod.IRemoteInputConnection, IRemoteAccessibilityInputConnection,
     * int, int, ImeOnBackInvokedDispatcher)} to receive
     * {@link android.window.OnBackInvokedCallback} registrations from IME.
     */
    private final ImeOnBackInvokedDispatcher mImeDispatcher =
            new ImeOnBackInvokedDispatcher(Handler.getMain()) {
        @Override
        public WindowOnBackInvokedDispatcher getReceivingDispatcher() {
            synchronized (mH) {
                return mCurRootView != null ? mCurRootView.getOnBackInvokedDispatcher() : null;
            }
        }
    };

    /**
     * Ensures that {@link #sInstance} becomes non-{@code null} for application that have directly
     * or indirectly relied on {@link #sInstance} via reflection or something like that.
     *
     * <p>Here are scenarios we know and there could be more scenarios we are not
     * aware of right know.</p>
     *
     * <ul>
     *     <li>Apps that directly access {@link #sInstance} via reflection, which is currently
     *     allowed because of {@link UnsupportedAppUsage} annotation.  Currently
     *     {@link android.view.WindowManagerGlobal#getWindowSession()} is likely to guarantee that
     *     {@link #sInstance} is not {@code null} when such an app is accessing it, but removing
     *     that code from {@link android.view.WindowManagerGlobal#getWindowSession()} can reveal
     *     untested code paths in their apps, which probably happen in an early startup time of that
     *     app.</li>
     *     <li>Apps that directly access {@link #peekInstance()} via reflection, which is currently
     *     allowed because of {@link UnsupportedAppUsage} annotation.  Currently
     *     {@link android.view.WindowManagerGlobal#getWindowSession()} is likely to guarantee that
     *     {@link #peekInstance()} returns non-{@code null} object when such an app is calling
     *     {@link #peekInstance()}, but removing that code from
     *     {@link android.view.WindowManagerGlobal#getWindowSession()} can reveal untested code
     *     paths in their apps, which probably happen in an early startup time of that app. The good
     *     news is that unlike {@link #sInstance}'s case we can at least work around this scenario
     *     by changing the semantics of {@link #peekInstance()}, which is currently defined as
     *     "retrieve the global {@link InputMethodManager} instance, if it exists" to something that
     *     always returns non-{@code null} {@link InputMethodManager}.  However, introducing such an
     *     workaround can also trigger different compatibility issues if {@link #peekInstance()} was
     *     called before {@link android.view.WindowManagerGlobal#getWindowSession()} and it expected
     *     {@link #peekInstance()} to return {@code null} as written in the JavaDoc.</li>
     * </ul>
     *
     * <p>Since this is purely a compatibility hack, this method must be used only from
     * {@link android.view.WindowManagerGlobal#getWindowSession()} and {@link #getInstance()}.</p>
     *
     * <p>TODO(Bug 116157766): Remove this method once we clean up {@link UnsupportedAppUsage}.</p>
     * @hide
     */
    public static void ensureDefaultInstanceForDefaultDisplayIfNecessary() {
        forContextInternal(Display.DEFAULT_DISPLAY, Looper.getMainLooper());
    }

    private static final Object sLock = new Object();

    /**
     * @deprecated This cannot be compatible with multi-display. Please do not use this.
     */
    @Deprecated
    @GuardedBy("sLock")
    @UnsupportedAppUsage
    static InputMethodManager sInstance;

    /**
     * Global map between display to {@link InputMethodManager}.
     *
     * <p>Currently this map works like a so-called leaky singleton.  Once an instance is registered
     * for the associated display ID, that instance will never be garbage collected.</p>
     *
     * <p>TODO(Bug 116699479): Implement instance clean up mechanism.</p>
     */
    @GuardedBy("sLock")
    private static final SparseArray<InputMethodManager> sInstanceMap = new SparseArray<>();

    /**
     * Timeout in milliseconds for delivering a key to an IME.
     */
    private static final long INPUT_METHOD_NOT_RESPONDING_TIMEOUT = 2500;

    /** @hide */
    public static final int DISPATCH_IN_PROGRESS = -1;

    /** @hide */
    public static final int DISPATCH_NOT_HANDLED = 0;

    /** @hide */
    public static final int DISPATCH_HANDLED = 1;

    /** @hide */
    public static final int SHOW_IM_PICKER_MODE_AUTO = 0;
    /** @hide */
    public static final int SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES = 1;
    /** @hide */
    public static final int SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES = 2;

    /**
     * Clear {@link #SHOW_FORCED} flag when the next IME focused application changed.
     *
     * <p>
     * Note that when this flag enabled in server side, {@link #SHOW_FORCED} will no longer
     * affect the next focused application to keep showing IME, in case of unexpected IME visible
     * when the next focused app isn't be the IME requester. </p>
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long CLEAR_SHOW_FORCED_FLAG_WHEN_LEAVING = 214016041L; // This is a bug id.

    /**
     * If {@code true}, avoid calling the
     * {@link com.android.server.inputmethod.InputMethodManagerService InputMethodManagerService}
     * by skipping the call to {@link IInputMethodManager#startInputOrWindowGainedFocus}
     * when we are switching focus between two non-editable views. This saves the cost of a binder
     * call into the system server.
     * <p><b>Note:</b>
     * The default value is {@code true}.
     */
    private static final boolean OPTIMIZE_NONEDITABLE_VIEWS =
            SystemProperties.getBoolean("debug.imm.optimize_noneditable_views", true);

    /** @hide */
    @IntDef(flag = true, prefix = { "HANDWRITING_DELEGATE_FLAG_" }, value = {
            HANDWRITING_DELEGATE_FLAG_HOME_DELEGATOR_ALLOWED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HandwritingDelegateFlags {}

    /**
     * Flag indicating that views from the default home screen ({@link Intent#CATEGORY_HOME}) may
     * act as a handwriting delegator for the delegate editor view. If set, views from the home
     * screen package will be trusted for handwriting delegation, in addition to views in the {@code
     * delegatorPackageName} passed to {@link #acceptStylusHandwritingDelegation(View, String,
     * int)}.
     */
    @FlaggedApi(FLAG_HOME_SCREEN_HANDWRITING_DELEGATOR)
    public static final int HANDWRITING_DELEGATE_FLAG_HOME_DELEGATOR_ALLOWED = 0x0001;

    /**
     * @deprecated Use {@link IInputMethodManagerGlobalInvoker} instead.
     */
    @Deprecated
    @UnsupportedAppUsage
    final IInputMethodManager mService;
    private final Looper mMainLooper;

    // For scheduling work on the main thread.  This also serves as our
    // global lock.
    // Remark on @UnsupportedAppUsage: there were context leaks on old versions
    // of android (b/37043700), so developers used this field to perform manual clean up.
    // Leaks were fixed, hacks were backported to AppCompatActivity,
    // so an access to the field is closed.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    final H mH;

    // Our generic input connection if the current target does not have its own.
    private final RemoteInputConnectionImpl mFallbackInputConnection;

    private final int mDisplayId;

    /**
     * True if this input method client is active, initially false.
     */
    @GuardedBy("mH")
    private boolean mActive = false;

    /**
     * {@code true} if next {@link ImeFocusController#onPostWindowFocus} needs to
     * restart input.
     */
    @GuardedBy("mH")
    private boolean mRestartOnNextWindowFocus = true;

    /**
     * As reported by IME through InputConnection.
     */
    @GuardedBy("mH")
    private boolean mFullscreenMode;

    // -----------------------------------------------------------

    /**
     * This is the view that should currently be served by an input method,
     * regardless of the state of setting that up.
     */
    @Nullable
    @GuardedBy("mH")
    private View mServedView;

    /**
     * This is the next view that will be served by the input method, when
     * we get around to updating things.
     */
    @Nullable
    @GuardedBy("mH")
    private View mNextServedView;

    /**
     * The latest {@link ViewRootImpl} that has, or most recently had, input method focus.
     *
     * <p>This value will be cleared when it becomes inactive and no longer has window focus.
     */
    @Nullable
    @GuardedBy("mH")
    ViewRootImpl mCurRootView;

    /**
     * Whether the {@link #mCurRootView} currently has window focus.
     */
    @GuardedBy("mH")
    boolean mCurRootViewWindowFocused;

    /**
     * This is set when we are in the process of connecting, to determine
     * when we have actually finished.
     */
    @GuardedBy("mH")
    private boolean mServedConnecting;

    /**
     * This is non-null when we have connected the served view; it holds
     * the attributes that were last retrieved from the served view and given
     * to the input connection.
     */
    @GuardedBy("mH")
    private EditorInfo mCurrentEditorInfo;

    @GuardedBy("mH")
    @Nullable
    private ViewFocusParameterInfo mPreviousViewFocusParameters;

    /**
     * The InputConnection that was last retrieved from the served view.
     */
    @GuardedBy("mH")
    private RemoteInputConnectionImpl mServedInputConnection;

    /**
     * The completions that were last provided by the served view.
     */
    @GuardedBy("mH")
    private CompletionInfo[] mCompletions;

    // Cursor position on the screen.
    @GuardedBy("mH")
    @UnsupportedAppUsage
    Rect mTmpCursorRect = new Rect();

    @GuardedBy("mH")
    @UnsupportedAppUsage
    Rect mCursorRect = new Rect();

    /** Cached value for {@link #isStylusHandwritingAvailable} for userId. */
    @GuardedBy("mH")
    private PropertyInvalidatedCache<Integer, Boolean> mStylusHandwritingAvailableCache;

    private static final String CACHE_KEY_STYLUS_HANDWRITING_PROPERTY =
            "cache_key.system_server.stylus_handwriting";

    @GuardedBy("mH")
    private int mCursorSelStart;
    @GuardedBy("mH")
    private int mCursorSelEnd;
    @GuardedBy("mH")
    private int mCursorCandStart;
    @GuardedBy("mH")
    private int mCursorCandEnd;
    @GuardedBy("mH")
    private int mInitialSelStart;
    @GuardedBy("mH")
    private int mInitialSelEnd;

    /**
     * Handler for {@link RemoteInputConnectionImpl#getInputConnection()}.
     */
    @GuardedBy("mH")
    private Handler mServedInputConnectionHandler;

    /**
     * The instance that has previously been sent to the input method.
     */
    @GuardedBy("mH")
    private CursorAnchorInfo mCursorAnchorInfo = null;

    // -----------------------------------------------------------

    /**
     * ID of the method we are bound to.
     *
     * @deprecated New code should use {@code mCurBindState.mImeId}.
     */
    @Deprecated
    @GuardedBy("mH")
    @UnsupportedAppUsage(trackingBug = 236937383,
            maxTargetSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            publicAlternatives = "Apps should not change behavior based on the currently connected"
                    + " IME. If absolutely needed, use {@link InputMethodInfo#getId()} instead.")
    String mCurId;

    /**
     * Kept for {@link UnsupportedAppUsage}.  Not officially maintained.
     *
     * @deprecated New code should use {@code mCurBindState.mImeSession}.
     */
    @Deprecated
    @GuardedBy("mH")
    @Nullable
    @UnsupportedAppUsage(trackingBug = 236937383,
            maxTargetSdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
            publicAlternatives = "Use methods on {@link InputMethodManager} instead.")
    IInputMethodSession mCurMethod;

    /**
     * Encapsulates per-binding state from {@link InputBindResult}.
     */
    @GuardedBy("mH")
    @Nullable
    private BindState mCurBindState;

    /**
     * Encapsulates IPCs to the currently connected AccessibilityServices.
     */
    @Nullable
    @GuardedBy("mH")
    private final SparseArray<IAccessibilityInputMethodSessionInvoker>
            mAccessibilityInputMethodSession = new SparseArray<>();

    @GuardedBy("mH")
    private InputChannel mCurChannel;
    @GuardedBy("mH")
    private ImeInputEventSender mCurSender;

    private static final int REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE = 0x0;

    /**
     * The monitor mode for {@link #updateCursorAnchorInfo(View, CursorAnchorInfo)}.
     * @deprecated This is kept for {@link UnsupportedAppUsage}.  Must not be used.
     */
    @Deprecated
    @GuardedBy("mH")
    private int mRequestUpdateCursorAnchorInfoMonitorMode = REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE;

    /**
     * Applies the IME visibility and listens for other state changes.
     */
    @GuardedBy("mH")
    private ImeInsetsSourceConsumer mImeInsetsConsumer;

    @GuardedBy("mH")
    private final Pool<PendingEvent> mPendingEventPool = new SimplePool<>(20);
    @GuardedBy("mH")
    private final SparseArray<PendingEvent> mPendingEvents = new SparseArray<>(20);

    private final DelegateImpl mDelegate = new DelegateImpl();

    private static boolean sPreventImeStartupUnlessTextEditor;

    // -----------------------------------------------------------

    private static final int MSG_DUMP = 1;
    private static final int MSG_BIND = 2;
    private static final int MSG_UNBIND = 3;
    private static final int MSG_SET_ACTIVE = 4;
    private static final int MSG_SEND_INPUT_EVENT = 5;
    private static final int MSG_TIMEOUT_INPUT_EVENT = 6;
    private static final int MSG_FLUSH_INPUT_EVENT = 7;
    private static final int MSG_REPORT_FULLSCREEN_MODE = 10;
    private static final int MSG_BIND_ACCESSIBILITY_SERVICE = 11;
    private static final int MSG_UNBIND_ACCESSIBILITY_SERVICE = 12;
    private static final int MSG_SET_INTERACTIVE = 13;
    private static final int MSG_ON_SHOW_REQUESTED = 31;

    /**
     * Calling this will invalidate Local stylus handwriting availability Cache which
     * forces the next query in any process to recompute the cache.
     * @hide
     */
    public static void invalidateLocalStylusHandwritingAvailabilityCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_STYLUS_HANDWRITING_PROPERTY);
    }

    private static boolean isAutofillUIShowing(View servedView) {
        AutofillManager afm = servedView.getContext().getSystemService(AutofillManager.class);
        return afm != null && afm.isAutofillUiShowing();
    }

    /**
     * Returns fallback {@link InputMethodManager} if the called one is not likely to be compatible
     * with the given {@code view}.
     *
     * @param view {@link View} to be checked.
     * @return {@code null} when it is unnecessary (or impossible) to use fallback
     *         {@link InputMethodManager} to which IME API calls need to be re-dispatched.
     *          Non-{@code null} {@link InputMethodManager} if this method believes it'd be safer to
     *          re-dispatch IME APIs calls on it.
     */
    @Nullable
    private InputMethodManager getFallbackInputMethodManagerIfNecessary(@Nullable View view) {
        if (view == null) {
            return null;
        }
        // As evidenced in Bug 118341760, view.getViewRootImpl().getDisplayId() is supposed to be
        // more reliable to determine with which display the given view is interacting than
        // view.getContext().getDisplayId() / view.getContext().getSystemService(), which can be
        // easily messed up by app developers (or library authors) by creating inconsistent
        // ContextWrapper objects that re-dispatch those methods to other Context such as
        // ApplicationContext.
        final ViewRootImpl viewRootImpl = view.getViewRootImpl();
        if (viewRootImpl == null) {
            return null;
        }
        final int viewRootDisplayId = viewRootImpl.getDisplayId();
        if (viewRootDisplayId == mDisplayId) {
            // Expected case.  Good to go.
            return null;
        }
        final InputMethodManager fallbackImm =
                viewRootImpl.mContext.getSystemService(InputMethodManager.class);
        if (fallbackImm == null) {
            Log.v(TAG, "b/117267690: Failed to get non-null fallback IMM. view=" + view);
            return null;
        }
        if (fallbackImm.mDisplayId != viewRootDisplayId) {
            Log.v(TAG, "b/117267690: Failed to get fallback IMM with expected displayId="
                    + viewRootDisplayId + " actual IMM#displayId=" + fallbackImm.mDisplayId
                    + " view=" + view);
            return null;
        }
        Log.v(TAG, "b/117267690: Display ID mismatch found."
                + " ViewRootImpl displayId=" + viewRootDisplayId
                + " InputMethodManager displayId=" + mDisplayId
                + ". Use the right InputMethodManager instance to avoid performance overhead.",
                new Throwable());
        return fallbackImm;
    }

    /**
     * An internal API that returns the {@link Context} of the current served view connected to
     * an input method.
     * @hide
     */
    Context getFallbackContextFromServedView() {
        synchronized (mH) {
            if (mCurRootView == null) {
                return null;
            }
            return mServedView != null ? mServedView.getContext() : null;
        }
    }

    private static boolean canStartInput(View servedView) {
        // We can start input ether the servedView has window focus
        // or the activity is showing autofill ui.
        return servedView.hasWindowFocus() || isAutofillUIShowing(servedView);
    }

    /**
     * Reports whether the IME is currently perceptible or not, according to the leash applied by
     * {@link android.view.WindowInsetsController}.
     * @hide
     */
    public void reportPerceptible(@NonNull IBinder windowToken, boolean perceptible) {
        IInputMethodManagerGlobalInvoker.reportPerceptibleAsync(windowToken, perceptible);
    }

    private final class DelegateImpl implements
            ImeFocusController.InputMethodManagerDelegate {

        @Override
        public void onPreWindowGainedFocus(ViewRootImpl viewRootImpl) {
            synchronized (mH) {
                setCurrentRootViewLocked(viewRootImpl);
                mCurRootViewWindowFocused = true;
            }
        }

        @Override
        public void onPostWindowGainedFocus(View viewForWindowFocus,
                @NonNull WindowManager.LayoutParams windowAttribute) {
            boolean forceFocus = false;
            synchronized (mH) {
                // Update mNextServedView when focusedView changed.
                onViewFocusChangedInternal(viewForWindowFocus, true);

                // Starting new input when the next focused view is same as served view but the
                // currently active connection (if any) is not associated with it.
                final boolean nextFocusIsServedView = mServedView == viewForWindowFocus;

                if (nextFocusIsServedView
                        && !hasActiveInputConnectionInternal(viewForWindowFocus)) {
                    forceFocus = true;
                }
            }

            final int softInputMode = windowAttribute.softInputMode;
            final int windowFlags = windowAttribute.flags;

            int startInputFlags = getStartInputFlags(viewForWindowFocus, 0);
            startInputFlags |= StartInputFlags.WINDOW_GAINED_FOCUS;

            ImeTracing.getInstance().triggerClientDump(
                    "InputMethodManager.DelegateImpl#startInputAsyncOnWindowFocusGain",
                    InputMethodManager.this, null /* icProto */);

            boolean checkFocusResult;
            synchronized (mH) {
                if (mCurRootView == null) {
                    return;
                }
                if (mRestartOnNextWindowFocus) {
                    if (DEBUG) Log.v(TAG, "Restarting due to mRestartOnNextWindowFocus as true");
                    mRestartOnNextWindowFocus = false;
                    forceFocus = true;
                }
                checkFocusResult = checkFocusInternalLocked(forceFocus, mCurRootView);
            }

            if (checkFocusResult) {
                // We need to restart input on the current focus view.  This
                // should be done in conjunction with telling the system service
                // about the window gaining focus, to help make the transition
                // smooth.
                if (startInputOnWindowFocusGainInternal(StartInputReason.WINDOW_FOCUS_GAIN,
                        viewForWindowFocus, startInputFlags, softInputMode, windowFlags)) {
                    return;
                }
            }

            synchronized (mH) {
                // For some reason we didn't do a startInput + windowFocusGain, so
                // we'll just do a window focus gain and call it a day.
                if (DEBUG) {
                    Log.v(TAG, "Reporting focus gain, without startInput");
                }

                // ignore the result
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMM.startInputOrWindowGainedFocus");
                IInputMethodManagerGlobalInvoker.startInputOrWindowGainedFocus(
                        StartInputReason.WINDOW_FOCUS_GAIN_REPORT_ONLY, mClient,
                        viewForWindowFocus.getWindowToken(), startInputFlags, softInputMode,
                        windowFlags,
                        null,
                        null, null,
                        mCurRootView.mContext.getApplicationInfo().targetSdkVersion,
                        UserHandle.myUserId(), mImeDispatcher);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }

        @Override
        public void onWindowLostFocus(@NonNull ViewRootImpl viewRootImpl) {
            synchronized (mH) {
                if (mCurRootView == viewRootImpl) {
                    mCurRootViewWindowFocused = false;

                    clearCurRootViewIfNeeded();
                }
            }
        }

        @Override
        public void onViewFocusChanged(@Nullable View view, boolean hasFocus) {
            onViewFocusChangedInternal(view, hasFocus);
        }

        @Override
        public void onScheduledCheckFocus(ViewRootImpl viewRootImpl) {
            synchronized (mH) {
                if (!checkFocusInternalLocked(false, viewRootImpl)) {
                    return;
                }
            }
            startInputOnWindowFocusGainInternal(StartInputReason.SCHEDULED_CHECK_FOCUS,
                    null /* focusedView */, 0 /* startInputFlags */, 0 /* softInputMode */,
                    0 /* windowFlags */);
        }

        @Override
        public void onViewDetachedFromWindow(View view, ViewRootImpl viewRootImpl) {
            synchronized (mH) {
                if (mCurRootView != view.getViewRootImpl()) {
                    return;
                }
                if (mNextServedView == view) {
                    mNextServedView = null;
                }
                if (mServedView == view) {
                    viewRootImpl.dispatchCheckFocus();
                }
            }
        }

        @Override
        public void onWindowDismissed(ViewRootImpl viewRootImpl) {
            synchronized (mH) {
                if (mCurRootView != viewRootImpl) {
                    return;
                }
                if (mServedView != null) {
                    finishInputLocked();
                }
                setCurrentRootViewLocked(null);
            }
        }

        @GuardedBy("mH")
        private void setCurrentRootViewLocked(ViewRootImpl rootView) {
            mImeDispatcher.switchRootView(mCurRootView, rootView);
            mCurRootView = rootView;
        }
    }

    /** @hide */
    public DelegateImpl getDelegate() {
        return mDelegate;
    }

    /**
     * Checks whether the active input connection (if any) is for the given view.
     *
     * <p>Note that {@code view} parameter does not take
     * {@link View#checkInputConnectionProxy(View)} into account. This method returns {@code true}
     * when and only when the specified {@code view} is the actual {@link View} instance that is
     * connected to the IME.</p>
     *
     * @param view {@link View} to be checked.
     * @return {@code true} if {@code view} is currently interacting with IME.
     * @hide
     */
    @TestApi
    public boolean hasActiveInputConnection(@Nullable View view) {
        synchronized (mH) {
            return mCurRootView != null
                    && view != null
                    && mServedView == view
                    && mServedInputConnection != null
                    && mServedInputConnection.isAssociatedWith(view)
                    && isImeSessionAvailableLocked();
        }
    }

    /**
     * Checks whether the active input connection (if any) is for the given view.
     *
     * Note that this method is only intended for restarting input after focus gain
     * (e.g. b/160391516), DO NOT leverage this method to do another check.
     */
    private boolean hasActiveInputConnectionInternal(@Nullable View view) {
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || !isImeSessionAvailableLocked()) {
                return false;
            }

            return mServedInputConnection != null
                    && mServedInputConnection.isAssociatedWith(view);
        }
    }

    private boolean startInputOnWindowFocusGainInternal(@StartInputReason int startInputReason,
            View focusedView, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode, int windowFlags) {
        synchronized (mH) {
            mCurrentEditorInfo = null;
            mCompletions = null;
            mServedConnecting = true;
        }
        return startInputInner(startInputReason,
                focusedView != null ? focusedView.getWindowToken() : null, startInputFlags,
                softInputMode, windowFlags);
    }

    @GuardedBy("mH")
    private View getServedViewLocked() {
        return mCurRootView != null ? mServedView : null;
    }

    @GuardedBy("mH")
    private View getNextServedViewLocked() {
        return mCurRootView != null ? mNextServedView : null;
    }

    /**
     * Returns {@code true} when the given view has been served by Input Method.
     */
    @GuardedBy("mH")
    private boolean hasServedByInputMethodLocked(View view) {
        final View servedView = getServedViewLocked();
        return (servedView == view
                || (servedView != null && servedView.checkInputConnectionProxy(view)));
    }

    class H extends Handler {
        H(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DUMP: {
                    SomeArgs args = (SomeArgs)msg.obj;
                    try {
                        doDump((FileDescriptor)args.arg1,
                                (PrintWriter)args.arg2, (String[])args.arg3);
                    } catch (RuntimeException e) {
                        ((PrintWriter)args.arg2).println("Exception: " + e);
                    }
                    synchronized (args.arg4) {
                        ((CountDownLatch)args.arg4).countDown();
                    }
                    args.recycle();
                    return;
                }
                case MSG_BIND: {
                    final InputBindResult res = (InputBindResult)msg.obj;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_BIND " + res.sequence + "," + res.id);
                    }
                    synchronized (mH) {
                        final int curBindSequence = getBindSequenceLocked();
                        if (curBindSequence < 0 || curBindSequence != res.sequence) {
                            Log.w(TAG, "Ignoring onBind: cur seq=" + curBindSequence
                                    + ", given seq=" + res.sequence);
                            if (res.channel != null && res.channel != mCurChannel) {
                                res.channel.dispose();
                            }
                            return;
                        }

                        mRequestUpdateCursorAnchorInfoMonitorMode =
                                REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE;

                        updateInputChannelLocked(res.channel);
                        mCurMethod = res.method; // for @UnsupportedAppUsage
                        mCurBindState = new BindState(res);
                        mCurId = res.id; // for @UnsupportedAppUsage
                    }
                    startInputInner(StartInputReason.BOUND_TO_IMMS, null, 0, 0, 0);
                    return;
                }
                case MSG_UNBIND: {
                    final int sequence = msg.arg1;
                    @UnbindReason
                    final int reason = msg.arg2;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_UNBIND " + sequence +
                                " reason=" + InputMethodDebug.unbindReasonToString(reason));
                    }
                    final boolean startInput;
                    synchronized (mH) {
                        if (getBindSequenceLocked() != sequence) {
                            return;
                        }
                        clearAllAccessibilityBindingLocked();
                        clearBindingLocked();
                        // If we were actively using the last input method, then
                        // we would like to re-connect to the next input method.
                        final View servedView = getServedViewLocked();
                        if (servedView != null && servedView.isFocused()) {
                            mServedConnecting = true;
                        }
                        startInput = mActive;
                    }
                    if (startInput) {
                        startInputInner(
                                StartInputReason.UNBOUND_FROM_IMMS, null, 0, 0, 0);
                    }
                    return;
                }
                case MSG_BIND_ACCESSIBILITY_SERVICE: {
                    final int id = msg.arg1;
                    final InputBindResult res = (InputBindResult) msg.obj;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_BIND_ACCESSIBILITY " + res.sequence
                                + "," + res.id);
                    }
                    synchronized (mH) {
                        final int curBindSequence = getBindSequenceLocked();
                        if (curBindSequence < 0 || curBindSequence != res.sequence) {
                            Log.w(TAG, "Ignoring onBind: cur seq=" + curBindSequence
                                    + ", given seq=" + res.sequence);
                            if (res.channel != null && res.channel != mCurChannel) {
                                res.channel.dispose();
                            }
                            return;
                        }

                        // Since IMM can start inputting text before a11y sessions are back,
                        // we send a notification so that the a11y service knows the session is
                        // registered and update the a11y service with the current cursor positions.
                        if (res.accessibilitySessions != null) {
                            IAccessibilityInputMethodSessionInvoker invoker =
                                    IAccessibilityInputMethodSessionInvoker.createOrNull(
                                            res.accessibilitySessions.get(id));
                            if (invoker != null) {
                                mAccessibilityInputMethodSession.put(id, invoker);
                                if (mServedInputConnection != null) {
                                    invoker.updateSelection(mInitialSelStart, mInitialSelEnd,
                                            mCursorSelStart, mCursorSelEnd, mCursorCandStart,
                                            mCursorCandEnd);
                                } else {
                                    // If an a11y service binds before input starts, we should still
                                    // send a notification because the a11y service doesn't know it
                                    // binds before or after input starts, it may wonder if it binds
                                    // after input starts, why it doesn't receive a notification of
                                    // the current cursor positions.
                                    invoker.updateSelection(-1, -1, -1, -1, -1, -1);
                                }
                            }
                        }
                    }
                    startInputInner(StartInputReason.BOUND_ACCESSIBILITY_SESSION_TO_IMMS, null,
                            0, 0, 0);
                    return;
                }
                case MSG_UNBIND_ACCESSIBILITY_SERVICE: {
                    final int sequence = msg.arg1;
                    final int id = msg.arg2;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_UNBIND_ACCESSIBILITY_SERVICE "
                                + sequence + " id=" + id);
                    }
                    synchronized (mH) {
                        if (getBindSequenceLocked() != sequence) {
                            if (DEBUG) {
                                Log.i(TAG, "current BindSequence =" + getBindSequenceLocked()
                                        + " sequence =" + sequence + " id=" + id);
                            }
                            return;
                        }
                        clearAccessibilityBindingLocked(id);
                    }
                    return;
                }
                case MSG_SET_ACTIVE: {
                    final boolean active = msg.arg1 != 0;
                    final boolean fullscreen = msg.arg2 != 0;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_SET_ACTIVE " + active + ", was " + mActive);
                    }
                    synchronized (mH) {
                        mActive = active;
                        mFullscreenMode = fullscreen;

                        if (!active) {
                            // Some other client has starting using the IME, so note
                            // that this happened and make sure our own editor's
                            // state is reset.
                            mRestartOnNextWindowFocus = true;
                            // Note that finishComposingText() is allowed to run
                            // even when we are not active.
                            mFallbackInputConnection.finishComposingTextFromImm();

                            if (clearCurRootViewIfNeeded()) {
                                return;
                            }
                        }
                        // Check focus again in case that "onWindowFocus" is called before
                        // handling this message.
                        final View servedView = getServedViewLocked();
                        if (servedView == null || !canStartInput(servedView)) {
                            return;
                        }
                        if (mCurRootView == null) {
                            return;
                        }
                        if (!checkFocusInternalLocked(mRestartOnNextWindowFocus, mCurRootView)) {
                            return;
                        }
                        mCurrentEditorInfo = null;
                        mCompletions = null;
                        mServedConnecting = true;
                    }
                    final int reason = active ? StartInputReason.ACTIVATED_BY_IMMS
                            : StartInputReason.DEACTIVATED_BY_IMMS;
                    startInputInner(reason, null, 0, 0, 0);
                    return;
                }
                case MSG_SET_INTERACTIVE: {
                    final boolean interactive = msg.arg1 != 0;
                    final boolean fullscreen = msg.arg2 != 0;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_SET_INTERACTIVE " + interactive
                                + ", was " + mActive);
                    }
                    synchronized (mH) {
                        mActive = interactive;
                        mFullscreenMode = fullscreen;
                        if (interactive) {
                            // Find the next view focus to start the input connection when the
                            // device was interactive.
                            final View rootView =
                                    mCurRootView != null ? mCurRootView.getView() : null;
                            if (rootView == null) {
                                // No window focused or view was removed, ignore request.
                                return;
                            }
                            final ViewRootImpl currentViewRootImpl = mCurRootView;
                            // Post this on UI thread as required for view focus code.
                            rootView.post(() -> {
                                synchronized (mH) {
                                    if (mCurRootView != currentViewRootImpl) {
                                        // Focused window changed since posting, ignore request.
                                        return;
                                    }
                                }
                                final View curRootView = currentViewRootImpl.getView();
                                if (curRootView == null) {
                                    // View was removed, ignore request.
                                    return;
                                }
                                final View focusedView = curRootView.findFocus();
                                onViewFocusChangedInternal(focusedView, focusedView != null);
                            });
                        } else {
                            // Finish input connection when device becomes non-interactive.
                            finishInputLocked();
                            if (isImeSessionAvailableLocked()) {
                                mCurBindState.mImeSession.finishInput();
                            }
                            forAccessibilitySessionsLocked(
                                    IAccessibilityInputMethodSessionInvoker::finishInput);
                        }
                    }
                    return;
                }
                case MSG_SEND_INPUT_EVENT: {
                    sendInputEventAndReportResultOnMainLooper((PendingEvent)msg.obj);
                    return;
                }
                case MSG_TIMEOUT_INPUT_EVENT: {
                    finishedInputEvent(msg.arg1, false, true);
                    return;
                }
                case MSG_FLUSH_INPUT_EVENT: {
                    finishedInputEvent(msg.arg1, false, false);
                    return;
                }
                case MSG_REPORT_FULLSCREEN_MODE: {
                    final boolean fullscreen = msg.arg1 != 0;
                    RemoteInputConnectionImpl ic = null;
                    synchronized (mH) {
                        if (mFullscreenMode != fullscreen && mServedInputConnection != null) {
                            ic = mServedInputConnection;
                            mFullscreenMode = fullscreen;
                        }
                    }
                    if (ic != null) {
                        ic.dispatchReportFullscreenMode(fullscreen);
                    }
                    return;
                }
                case MSG_ON_SHOW_REQUESTED: {
                    synchronized (mH) {
                        if (mImeInsetsConsumer != null) {
                            mImeInsetsConsumer.onShowRequested();
                        }
                    }
                    return;
                }
            }
        }
    }

    private final IInputMethodClient.Stub mClient = new IInputMethodClient.Stub() {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
            // No need to check for dump permission, since we only give this
            // interface to the system.
            CountDownLatch latch = new CountDownLatch(1);
            SomeArgs sargs = SomeArgs.obtain();
            sargs.arg1 = fd;
            sargs.arg2 = fout;
            sargs.arg3 = args;
            sargs.arg4 = latch;
            mH.sendMessage(mH.obtainMessage(MSG_DUMP, sargs));
            try {
                if (!latch.await(5, TimeUnit.SECONDS)) {
                    fout.println("Timeout waiting for dump");
                }
            } catch (InterruptedException e) {
                fout.println("Interrupted waiting for dump");
            }
        }

        @Override
        public void onBindMethod(InputBindResult res) {
            mH.obtainMessage(MSG_BIND, res).sendToTarget();
        }

        @Override
        public void onBindAccessibilityService(InputBindResult res, int id) {
            mH.obtainMessage(MSG_BIND_ACCESSIBILITY_SERVICE, id, 0, res).sendToTarget();
        }

        @Override
        public void onUnbindMethod(int sequence, @UnbindReason int unbindReason) {
            mH.obtainMessage(MSG_UNBIND, sequence, unbindReason).sendToTarget();
        }

        @Override
        public void onUnbindAccessibilityService(int sequence, int id) {
            mH.obtainMessage(MSG_UNBIND_ACCESSIBILITY_SERVICE, sequence, id).sendToTarget();
        }

        @Override
        public void setActive(boolean active, boolean fullscreen) {
            mH.obtainMessage(MSG_SET_ACTIVE, active ? 1 : 0, fullscreen ? 1 : 0).sendToTarget();
        }

        @Override
        public void setInteractive(boolean interactive, boolean fullscreen) {
            mH.obtainMessage(MSG_SET_INTERACTIVE, interactive ? 1 : 0, fullscreen ? 1 : 0)
                    .sendToTarget();
        }

        @Override
        public void scheduleStartInputIfNecessary(boolean fullscreen) {
            // TODO(b/149859205): See if we can optimize this by having a fused dedicated operation.
            mH.obtainMessage(MSG_SET_ACTIVE, 0 /* active */, fullscreen ? 1 : 0).sendToTarget();
            mH.obtainMessage(MSG_SET_ACTIVE, 1 /* active */, fullscreen ? 1 : 0).sendToTarget();
        }

        @Override
        public void reportFullscreenMode(boolean fullscreen) {
            mH.obtainMessage(MSG_REPORT_FULLSCREEN_MODE, fullscreen ? 1 : 0, 0)
                    .sendToTarget();
        }

        @Override
        public void setImeTraceEnabled(boolean enabled) {
            ImeTracing.getInstance().setEnabled(enabled);
        }

        @Override
        public void throwExceptionFromSystem(String message) {
            throw new RuntimeException(message);
        }
    };

    /**
     * For layoutlib to clean up static objects inside {@link InputMethodManager}.
     */
    static void tearDownEditMode() {
        if (!isInEditMode()) {
            throw new UnsupportedOperationException(
                    "This method must be called only from layoutlib");
        }
        synchronized (sLock) {
            sInstance = null;
        }
    }

    /**
     * For layoutlib to override this method to return {@code true}.
     *
     * @return {@code true} if the process is running for developer tools
     * @see View#isInEditMode()
     */
    private static boolean isInEditMode() {
        return false;
    }

    static boolean isInEditModeInternal() {
        return isInEditMode();
    }

    @NonNull
    private static InputMethodManager createInstance(int displayId, Looper looper) {
        return isInEditMode() ? createStubInstance(displayId, looper)
                : createRealInstance(displayId, looper);
    }

    @NonNull
    private static InputMethodManager createRealInstance(int displayId, Looper looper) {
        final IInputMethodManager service = IInputMethodManagerGlobalInvoker.getService();
        if (service == null) {
            throw new IllegalStateException("IInputMethodManager is not available");
        }
        final InputMethodManager imm = new InputMethodManager(service, displayId, looper);
        // InputMethodManagerService#addClient() relies on Binder.getCalling{Pid, Uid}() to
        // associate PID/UID with each IME client. This means:
        //  A. if this method call will be handled as an IPC, there is no problem.
        //  B. if this method call will be handled as an in-proc method call, we need to
        //     ensure that Binder.getCalling{Pid, Uid}() return Process.my{Pid, Uid}()
        // Either ways we can always call Binder.{clear, restore}CallingIdentity() because
        // 1) doing so has no effect for A and 2) doing so is sufficient for B.
        final long identity = Binder.clearCallingIdentity();
        try {
            IInputMethodManagerGlobalInvoker.addClient(imm.mClient, imm.mFallbackInputConnection,
                    displayId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return imm;
    }

    @NonNull
    private static InputMethodManager createStubInstance(int displayId, Looper looper) {
        // If InputMethodManager is running for layoutlib, stub out IPCs into IMMS.
        final Class<IInputMethodManager> c = IInputMethodManager.class;
        final IInputMethodManager stubInterface =
                (IInputMethodManager) Proxy.newProxyInstance(c.getClassLoader(),
                        new Class[]{c}, (proxy, method, args) -> {
                            final Class<?> returnType = method.getReturnType();
                            if (returnType == boolean.class) {
                                return false;
                            } else if (returnType == int.class) {
                                return 0;
                            } else if (returnType == long.class) {
                                return 0L;
                            } else if (returnType == short.class) {
                                return 0;
                            } else if (returnType == char.class) {
                                return 0;
                            } else if (returnType == byte.class) {
                                return 0;
                            } else if (returnType == float.class) {
                                return 0f;
                            } else if (returnType == double.class) {
                                return 0.0;
                            } else {
                                return null;
                            }
                        });
        return new InputMethodManager(stubInterface, displayId, looper);
    }

    private InputMethodManager(@NonNull IInputMethodManager service, int displayId, Looper looper) {
        mService = service;  // For @UnsupportedAppUsage
        mMainLooper = looper;
        mH = new H(looper);
        mDisplayId = displayId;
        mFallbackInputConnection = new RemoteInputConnectionImpl(looper,
                new BaseInputConnection(this, false), this, null);
    }

    /**
     * Retrieve an instance for the given {@link Context}, creating it if it doesn't already exist.
     *
     * @param context {@link Context} for which IME APIs need to work
     * @return {@link InputMethodManager} instance
     * @hide
     */
    @NonNull
    public static InputMethodManager forContext(@DisplayContext Context context) {
        final int displayId = context.getDisplayId();
        // For better backward compatibility, we always use Looper.getMainLooper() for the default
        // display case.
        final Looper looper = displayId == Display.DEFAULT_DISPLAY
                ? Looper.getMainLooper() : context.getMainLooper();
        // Keep track of whether to expect the IME to be unavailable so as to avoid log spam in
        // sendInputEventOnMainLooperLocked() by not logging a verbose message on every DPAD event
        sPreventImeStartupUnlessTextEditor = context.getResources().getBoolean(
                com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
        return forContextInternal(displayId, looper);
    }

    @NonNull
    private static InputMethodManager forContextInternal(int displayId, Looper looper) {
        final boolean isDefaultDisplay = displayId == Display.DEFAULT_DISPLAY;
        synchronized (sLock) {
            InputMethodManager instance = sInstanceMap.get(displayId);
            if (instance != null) {
                return instance;
            }
            instance = createInstance(displayId, looper);
            // For backward compatibility, store the instance also to sInstance for default display.
            if (sInstance == null && isDefaultDisplay) {
                sInstance = instance;
            }
            sInstanceMap.put(displayId, instance);
            return instance;
        }
    }

    /**
     * Deprecated. Do not use.
     *
     * @return global {@link InputMethodManager} instance
     * @deprecated Use {@link Context#getSystemService(Class)} instead. This method cannot fully
     *             support multi-display scenario.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static InputMethodManager getInstance() {
        Log.w(TAG, "InputMethodManager.getInstance() is deprecated because it cannot be"
                        + " compatible with multi-display."
                        + " Use context.getSystemService(InputMethodManager.class) instead.",
                new Throwable());
        ensureDefaultInstanceForDefaultDisplayIfNecessary();
        return peekInstance();
    }

    /**
     * Deprecated. Do not use.
     *
     * @return {@link #sInstance}
     * @deprecated Use {@link Context#getSystemService(Class)} instead. This method cannot fully
     *             support multi-display scenario.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public static InputMethodManager peekInstance() {
        Log.w(TAG, "InputMethodManager.peekInstance() is deprecated because it cannot be"
                        + " compatible with multi-display."
                        + " Use context.getSystemService(InputMethodManager.class) instead.",
                new Throwable());
        synchronized (sLock) {
            return sInstance;
        }
    }

    /**
     * Returns the list of installed input methods.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @return {@link List} of {@link InputMethodInfo}.
     */
    @NonNull
    public List<InputMethodInfo> getInputMethodList() {
        // We intentionally do not use UserHandle.getCallingUserId() here because for system
        // services InputMethodManagerInternal.getInputMethodListAsUser() should be used
        // instead.
        return IInputMethodManagerGlobalInvoker.getInputMethodList(UserHandle.myUserId(),
                DirectBootAwareness.AUTO);
    }

    /**
     * Returns {@code true} if currently selected IME supports Stylus handwriting & is enabled.
     * If the method returns {@code false}, {@link #startStylusHandwriting(View)} shouldn't be
     * called and Stylus touch should continue as normal touch input.
     *
     * @see #startStylusHandwriting(View)
     */
    public boolean isStylusHandwritingAvailable() {
        return isStylusHandwritingAvailableAsUser(UserHandle.of(UserHandle.myUserId()));
    }

    /**
     * Returns {@code true} if currently selected IME supports Stylus handwriting & is enabled for
     * the given userId.
     *
     * <p>If the method returns {@code false}, {@link #startStylusHandwriting(View)} shouldn't be
     * called and Stylus touch should continue as normal touch input.</p>
     *
     * <p>{@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required when and only when
     * {@code user} is different from the user of the current process.</p>
     *
     * @see #startStylusHandwriting(View)
     * @param user UserHandle to query.
     * @hide
     */
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    @TestApi
    @FlaggedApi(Flags.FLAG_IMM_USERHANDLE_HOSTSIDETESTS)
    @SuppressLint("UserHandle")
    public boolean isStylusHandwritingAvailableAsUser(@NonNull UserHandle user) {
        final Context fallbackContext = ActivityThread.currentApplication();
        if (fallbackContext == null) {
            return false;
        }
        boolean isAvailable;
        synchronized (mH) {
            if (mStylusHandwritingAvailableCache == null) {
                mStylusHandwritingAvailableCache = new PropertyInvalidatedCache<>(
                        4 /* maxEntries */, CACHE_KEY_STYLUS_HANDWRITING_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer userId) {
                        return IInputMethodManagerGlobalInvoker.isStylusHandwritingAvailableAsUser(
                                userId);
                    }
                };
            }
            isAvailable = mStylusHandwritingAvailableCache.query(user.getIdentifier());
        }
        return isAvailable;
    }

    /**
     * Returns the list of installed input methods for the specified user.
     *
     * <p>{@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required when and only when
     * {@code userId} is different from the user id of the current process.</p>
     *
     * @param userId user ID to query
     * @return {@link List} of {@link InputMethodInfo}.
     * @hide
     */
    @TestApi
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
        return IInputMethodManagerGlobalInvoker.getInputMethodList(userId,
                DirectBootAwareness.AUTO);
    }

    /**
     * Returns the list of installed input methods for the specified user.
     *
     * <p>{@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required when and only when
     * {@code userId} is different from the user id of the current process.</p>
     *
     * @param userId user ID to query
     * @param directBootAwareness {@code true} if caller want to query installed input methods list
     * on user locked state.
     * @return {@link List} of {@link InputMethodInfo}.
     * @hide
     */
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        return IInputMethodManagerGlobalInvoker.getInputMethodList(userId, directBootAwareness);
    }

    /**
     * Returns the {@link InputMethodInfo} of the currently selected input method (for the process's
     * user).
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     */
    @Nullable
    public InputMethodInfo getCurrentInputMethodInfo() {
        // We intentionally do not use UserHandle.getCallingUserId() here because for system
        // services InputMethodManagerInternal.getCurrentInputMethodInfoForUser() should be used
        // instead.
        return IInputMethodManagerGlobalInvoker.getCurrentInputMethodInfoAsUser(
                UserHandle.myUserId());
    }

    /**
     * Returns the {@link InputMethodInfo} for currently selected input method for the given user.
     *
     * @param user user to query.
     * @hide
     */
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    @Nullable
    @SystemApi
    @SuppressLint("UserHandle")
    public InputMethodInfo getCurrentInputMethodInfoAsUser(@NonNull UserHandle user) {
        Objects.requireNonNull(user);
        return IInputMethodManagerGlobalInvoker.getCurrentInputMethodInfoAsUser(
                user.getIdentifier());
    }

    /**
     * Returns the list of enabled input methods.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @return {@link List} of {@link InputMethodInfo}.
     */
    @NonNull
    public List<InputMethodInfo> getEnabledInputMethodList() {
        // We intentionally do not use UserHandle.getCallingUserId() here because for system
        // services InputMethodManagerInternal.getEnabledInputMethodListAsUser() should be used
        // instead.
        return IInputMethodManagerGlobalInvoker.getEnabledInputMethodList(UserHandle.myUserId());
    }

    /**
     * Returns the list of enabled input methods for the specified user.
     *
     * <p>{@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required when and only when
     * {@code user} is different from the user of the current process.</p>
     *
     * @param user UserHandle to query
     * @return {@link List} of {@link InputMethodInfo}.
     * @see #getEnabledInputMethodSubtypeListAsUser(String, boolean, UserHandle)
     * @hide
     */
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    @TestApi
    @FlaggedApi(Flags.FLAG_IMM_USERHANDLE_HOSTSIDETESTS)
    @SuppressLint("UserHandle")
    public List<InputMethodInfo> getEnabledInputMethodListAsUser(@NonNull UserHandle user) {
        return IInputMethodManagerGlobalInvoker.getEnabledInputMethodList(user.getIdentifier());
    }

    /**
     * Returns a list of enabled input method subtypes for the specified input method info.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @param imi The {@link InputMethodInfo} whose subtypes list will be returned. If {@code null},
     * returns enabled subtypes for the currently selected {@link InputMethodInfo}.
     * @param allowsImplicitlyEnabledSubtypes A boolean flag to allow to return the implicitly
     * enabled subtypes. If an input method info doesn't have enabled subtypes, the framework
     * will implicitly enable subtypes according to the current system language.
     */
    @NonNull
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(@Nullable InputMethodInfo imi,
            boolean allowsImplicitlyEnabledSubtypes) {
        return IInputMethodManagerGlobalInvoker.getEnabledInputMethodSubtypeList(
                imi == null ? null : imi.getId(),
                allowsImplicitlyEnabledSubtypes,
                UserHandle.myUserId());
    }

    /**
     * Returns a list of enabled input method subtypes for the specified input method info for the
     * specified user.
     *
     * @param imeId IME ID to be queried about.
     * @param allowsImplicitlyEnabledSubtypes {@code true} to include implicitly enabled subtypes.
     * @param user UserHandle to be queried about.
     *               {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required if this is
     *               different from the calling process user ID.
     * @return {@link List} of {@link InputMethodSubtype}.
     * @see #getEnabledInputMethodListAsUser(UserHandle)
     * @hide
     */
    @NonNull
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    @TestApi
    @FlaggedApi(Flags.FLAG_IMM_USERHANDLE_HOSTSIDETESTS)
    @SuppressLint("UserHandle")
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeListAsUser(
            @NonNull String imeId, boolean allowsImplicitlyEnabledSubtypes,
            @NonNull UserHandle user) {
        return IInputMethodManagerGlobalInvoker.getEnabledInputMethodSubtypeList(
                Objects.requireNonNull(imeId), allowsImplicitlyEnabledSubtypes,
                user.getIdentifier());
    }

    /**
     * @deprecated Use {@link InputMethodService#showStatusIcon(int)} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in this
     * class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void showStatusIcon(IBinder imeToken, String packageName, @DrawableRes int iconId) {
        InputMethodPrivilegedOperationsRegistry.get(
                imeToken).updateStatusIconAsync(packageName, iconId);
    }

    /**
     * @deprecated Use {@link InputMethodService#hideStatusIcon()} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in
     * this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void hideStatusIcon(IBinder imeToken) {
        InputMethodPrivilegedOperationsRegistry.get(imeToken).updateStatusIconAsync(null, 0);
    }

    /**
     * This hidden API is deprecated in {@link android.os.Build.VERSION_CODES#Q}. Does nothing.
     *
     * @param spans will be ignored.
     *
     * @deprecated Do not use.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void registerSuggestionSpansForNotification(SuggestionSpan[] spans) {
        Log.w(TAG, "registerSuggestionSpansForNotification() is deprecated.  Does nothing.");
    }

    /**
     * This hidden API is deprecated in {@link android.os.Build.VERSION_CODES#Q}. Does nothing.
     *
     * @deprecated Do not use.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage
    public void notifySuggestionPicked(SuggestionSpan span, String originalString, int index) {
        Log.w(TAG, "notifySuggestionPicked() is deprecated.  Does nothing.");
    }

    /**
     * Allows you to discover whether the attached input method is running
     * in fullscreen mode.  Return true if it is fullscreen, entirely covering
     * your UI, else returns false.
     */
    public boolean isFullscreenMode() {
        synchronized (mH) {
            return mFullscreenMode;
        }
    }

    /**
     * Return {@code true} if the given view is the currently active view for the input method.
     */
    public boolean isActive(View view) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            return fallbackImm.isActive(view);
        }

        checkFocus();
        synchronized (mH) {
            return hasServedByInputMethodLocked(view) && mCurrentEditorInfo != null;
        }
    }

    /**
     * Return {@code true} if any view is currently active for the input method.
     */
    public boolean isActive() {
        checkFocus();
        synchronized (mH) {
            return getServedViewLocked() != null && mCurrentEditorInfo != null;
        }
    }

    /**
     * Returns {@code true} if the given view's {@link ViewRootImpl} is the currently active one
     * for the {@code InputMethodManager}.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isCurrentRootView(@NonNull View attachedView) {
        synchronized (mH) {
            return mCurRootView == attachedView.getViewRootImpl();
        }
    }

    /**
     * Return {@code true} if the currently served view is accepting full text edits.
     * If {@code false}, it has no input connection, so it can only handle raw key events.
     */
    public boolean isAcceptingText() {
        checkFocus();
        synchronized (mH) {
            return mServedInputConnection != null;
        }
    }

    /**
     * Return {@code true} if the input method is suppressing system spell checker.
     */
    public boolean isInputMethodSuppressingSpellChecker() {
        synchronized (mH) {
            return mCurBindState != null
                    && mCurBindState.mIsInputMethodSuppressingSpellChecker;
        }
    }

    /**
     * Reset all of the state associated with being bound to an input method.
     */
    @GuardedBy("mH")
    private void clearBindingLocked() {
        if (DEBUG) Log.v(TAG, "Clearing binding!");
        clearConnectionLocked();
        updateInputChannelLocked(null);
        mCurId = null; // for @UnsupportedAppUsage
        mCurMethod = null; // for @UnsupportedAppUsage
        // We only reset sequence number for input method, but not accessibility.
        mCurBindState = null;
    }

    /**
     * Reset all of the state associated with being bound to an accessibility service.
     */
    @GuardedBy("mH")
    private void clearAccessibilityBindingLocked(int id) {
        if (DEBUG) Log.v(TAG, "Clearing accessibility binding " + id);
        mAccessibilityInputMethodSession.remove(id);
    }

    /**
     * Reset all of the state associated with being bound to all accessibility services.
     */
    @GuardedBy("mH")
    private void clearAllAccessibilityBindingLocked() {
        if (DEBUG) Log.v(TAG, "Clearing all accessibility bindings");
        mAccessibilityInputMethodSession.clear();
    }

    @GuardedBy("mH")
    private void updateInputChannelLocked(InputChannel channel) {
        if (areSameInputChannel(mCurChannel, channel)) {
            return;
        }
        // TODO(b/238720598) : Requirements when design a new protocol for InputChannel
        // channel is a dupe of 'mCurChannel', because they have the same token, and represent
        // the same connection. Ignore the incoming channel and keep using 'mCurChannel' to
        // avoid confusing the InputEventReceiver.
        if (mCurSender != null) {
            flushPendingEventsLocked();
            mCurSender.dispose();
            mCurSender = null;
        }

        if (mCurChannel != null) {
            mCurChannel.dispose();
        }
        mCurChannel = channel;
    }

    private static boolean areSameInputChannel(@Nullable InputChannel lhs,
            @Nullable InputChannel rhs) {
        if (lhs == rhs) {
            return true;
        }
        if (lhs == null || rhs == null) {
            return false;
        }
        return lhs.getToken() == rhs.getToken();
    }

    /**
     * Reset all of the state associated with a served view being connected
     * to an input method
     */
    @GuardedBy("mH")
    private void clearConnectionLocked() {
        mCurrentEditorInfo = null;
        mPreviousViewFocusParameters = null;
        if (mServedInputConnection != null) {
            mServedInputConnection.deactivate();
            mServedInputConnection = null;
            mServedInputConnectionHandler = null;
        }
    }

    /**
     * Disconnect any existing input connection, clearing the served view.
     */
    @UnsupportedAppUsage
    @GuardedBy("mH")
    void finishInputLocked() {
        View clearedView = null;
        mNextServedView = null;
        if (mServedView != null) {
            clearedView = mServedView;
            mServedView = null;
        }
        if (clearedView != null) {
            if (DEBUG) {
                Log.v(TAG, "FINISH INPUT: mServedView="
                        + InputMethodDebug.dumpViewInfo(clearedView));
            }
            mCompletions = null;
            mServedConnecting = false;
            clearConnectionLocked();
        }
        // Clear the back callbacks held by the ime dispatcher to avoid memory leaks.
        mImeDispatcher.clear();
    }

    /**
     * Clears the {@link #mCurRootView} if it's no longer window focused and the connection is
     * no longer active.
     *
     * @return {@code} true iff it was cleared.
     */
    @GuardedBy("mH")
    private boolean clearCurRootViewIfNeeded() {
        if (!mActive && !mCurRootViewWindowFocused) {
            finishInputLocked();
            mDelegate.setCurrentRootViewLocked(null);

            return true;
        }

        return false;
    }

    public void displayCompletions(View view, CompletionInfo[] completions) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.displayCompletions(view, completions);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                return;
            }

            mCompletions = completions;
            if (isImeSessionAvailableLocked()) {
                mCurBindState.mImeSession.displayCompletions(mCompletions);
            }
        }
    }

    public void updateExtractedText(View view, int token, ExtractedText text) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.updateExtractedText(view, token, text);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                return;
            }

            if (isImeSessionAvailableLocked()) {
                mCurBindState.mImeSession.updateExtractedText(token, text);
            }
        }
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "SHOW_" }, value = {
            SHOW_IMPLICIT,
            SHOW_FORCED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShowFlags {}

    /**
     * Flag for {@link #showSoftInput} to indicate that this is an implicit
     * request to show the input window, not as the result of a direct request
     * by the user.  The window may not be shown in this case.
     */
    public static final int SHOW_IMPLICIT = 0x0001;

    /**
     * Flag for {@link #showSoftInput} to indicate that the user has forced
     * the input method open (such as by long-pressing menu) so it should
     * not be closed until they explicitly do so.
     *
     * @deprecated Use {@link #showSoftInput} without this flag instead. Using this flag can lead
     * to the soft input remaining visible even when the calling application is closed. The
     * use of this flag can make the soft input remain visible globally. Starting in
     * {@link Build.VERSION_CODES#TIRAMISU Android T}, this flag only has an effect while the
     * caller is currently focused.
     */
    @Deprecated
    public static final int SHOW_FORCED = 0x0002;

    /**
     * Synonym for {@link #showSoftInput(View, int, ResultReceiver)} without
     * a result receiver: explicitly request that the current input method's
     * soft input area be shown to the user, if needed.
     *
     * @param view The currently focused view, which would like to receive soft keyboard input.
     *             Note that this view is only considered focused here if both it itself has
     *             {@link View#isFocused view focus}, and its containing window has
     *             {@link View#hasWindowFocus window focus}. Otherwise the call fails and
     *             returns {@code false}.
     */
    public boolean showSoftInput(View view, @ShowFlags int flags) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            return fallbackImm.showSoftInput(view, flags);
        }

        return showSoftInput(view, flags, null);
    }

    /**
     * Flag for the {@link ResultReceiver} result code from
     * {@link #showSoftInput(View, int, ResultReceiver)} and
     * {@link #hideSoftInputFromWindow(IBinder, int, ResultReceiver)}: the
     * state of the soft input window was unchanged and remains shown.
     */
    public static final int RESULT_UNCHANGED_SHOWN = 0;

    /**
     * Flag for the {@link ResultReceiver} result code from
     * {@link #showSoftInput(View, int, ResultReceiver)} and
     * {@link #hideSoftInputFromWindow(IBinder, int, ResultReceiver)}: the
     * state of the soft input window was unchanged and remains hidden.
     */
    public static final int RESULT_UNCHANGED_HIDDEN = 1;

    /**
     * Flag for the {@link ResultReceiver} result code from
     * {@link #showSoftInput(View, int, ResultReceiver)} and
     * {@link #hideSoftInputFromWindow(IBinder, int, ResultReceiver)}: the
     * state of the soft input window changed from hidden to shown.
     */
    public static final int RESULT_SHOWN = 2;

    /**
     * Flag for the {@link ResultReceiver} result code from
     * {@link #showSoftInput(View, int, ResultReceiver)} and
     * {@link #hideSoftInputFromWindow(IBinder, int, ResultReceiver)}: the
     * state of the soft input window changed from shown to hidden.
     */
    public static final int RESULT_HIDDEN = 3;

    /**
     * Explicitly request that the current input method's soft input area be
     * shown to the user, if needed.  Call this if the user interacts with
     * your view in such a way that they have expressed they would like to
     * start performing input into it.
     *
     * <p><strong>Caveat:</strong> {@link ResultReceiver} instance passed to
     * this method can be a long-lived object, because it may not be
     * garbage-collected until all the corresponding {@link ResultReceiver}
     * objects transferred to different processes get garbage-collected.
     * Follow the general patterns to avoid memory leaks in Android.
     * Consider to use {@link java.lang.ref.WeakReference} so that application
     * logic objects such as {@link android.app.Activity} and {@link Context}
     * can be garbage collected regardless of the lifetime of
     * {@link ResultReceiver}.
     *
     * @param view The currently focused view, which would like to receive soft keyboard input.
     *             Note that this view is only considered focused here if both it itself has
     *             {@link View#isFocused view focus}, and its containing window has
     *             {@link View#hasWindowFocus window focus}. Otherwise the call fails and
     *             returns {@code false}.
     * @param resultReceiver If non-null, this will be called by the IME when
     * it has processed your request to tell you what it has done.  The result
     * code you receive may be either {@link #RESULT_UNCHANGED_SHOWN},
     * {@link #RESULT_UNCHANGED_HIDDEN}, {@link #RESULT_SHOWN}, or
     * {@link #RESULT_HIDDEN}.
     */
    public boolean showSoftInput(View view, @ShowFlags int flags, ResultReceiver resultReceiver) {
        return showSoftInput(view, null /* statsToken */, flags, resultReceiver,
                SoftInputShowHideReason.SHOW_SOFT_INPUT);
    }

    private boolean showSoftInput(View view, @Nullable ImeTracker.Token statsToken,
            @ShowFlags int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        if (statsToken == null) {
            statsToken = ImeTracker.forLogging().onRequestShow(null /* component */,
                    Process.myUid(), ImeTracker.ORIGIN_CLIENT_SHOW_SOFT_INPUT, reason);
        }
        ImeTracker.forLatency().onRequestShow(statsToken, ImeTracker.ORIGIN_CLIENT_SHOW_SOFT_INPUT,
                reason, ActivityThread::currentApplication);
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#showSoftInput", this,
                null /* icProto */);
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            return fallbackImm.showSoftInput(view, statsToken, flags, resultReceiver, reason);
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                ImeTracker.forLatency().onShowFailed(
                        statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED,
                        ActivityThread::currentApplication);
                Log.w(TAG, "Ignoring showSoftInput() as view=" + view + " is not served.");
                return false;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            // Makes sure to call ImeInsetsSourceConsumer#onShowRequested on the UI thread.
            // TODO(b/229426865): call WindowInsetsController#show instead.
            mH.executeOrSendMessage(Message.obtain(mH, MSG_ON_SHOW_REQUESTED));
            Log.d(TAG, "showSoftInput() view=" + view + " flags=" + flags + " reason="
                    + InputMethodDebug.softInputDisplayReasonToString(reason));
            return IInputMethodManagerGlobalInvoker.showSoftInput(
                    mClient,
                    view.getWindowToken(),
                    statsToken,
                    flags,
                    mCurRootView.getLastClickToolType(),
                    resultReceiver,
                    reason);
        }
    }

    /**
     * This method is still kept for a while until androidx.appcompat.widget.SearchView ver. 26.0
     * is publicly released because previous implementations of that class had relied on this method
     * via reflection.
     *
     * @deprecated This is a hidden API. You should never use this.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768499)
    public void showSoftInputUnchecked(@ShowFlags int flags, ResultReceiver resultReceiver) {
        synchronized (mH) {
            final ImeTracker.Token statsToken = ImeTracker.forLogging().onRequestShow(
                    null /* component */, Process.myUid(), ImeTracker.ORIGIN_CLIENT_SHOW_SOFT_INPUT,
                    SoftInputShowHideReason.SHOW_SOFT_INPUT);

            Log.w(TAG, "showSoftInputUnchecked() is a hidden method, which will be"
                    + " removed soon. If you are using androidx.appcompat.widget.SearchView,"
                    + " please update to version 26.0 or newer version.");
            final View rootView = mCurRootView != null ? mCurRootView.getView() : null;
            if (rootView == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                Log.w(TAG, "No current root view, ignoring showSoftInputUnchecked()");
                return;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            // Makes sure to call ImeInsetsSourceConsumer#onShowRequested on the UI thread.
            // TODO(b/229426865): call WindowInsetsController#show instead.
            mH.executeOrSendMessage(Message.obtain(mH, MSG_ON_SHOW_REQUESTED));
            IInputMethodManagerGlobalInvoker.showSoftInput(
                    mClient,
                    rootView.getWindowToken(),
                    statsToken,
                    flags,
                    mCurRootView.getLastClickToolType(),
                    resultReceiver,
                    SoftInputShowHideReason.SHOW_SOFT_INPUT);
        }
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "HIDE_" }, value = {
            HIDE_IMPLICIT_ONLY,
            HIDE_NOT_ALWAYS,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HideFlags {}

    /**
     * Flag for {@link #hideSoftInputFromWindow} and {@link InputMethodService#requestHideSelf(int)}
     * to indicate that the soft input window should only be hidden if it was not explicitly shown
     * by the user.
     */
    public static final int HIDE_IMPLICIT_ONLY = 0x0001;

    /**
     * Flag for {@link #hideSoftInputFromWindow} and {@link InputMethodService#requestShowSelf(int)}
     * to indicate that the soft input window should normally be hidden, unless it was originally
     * shown with {@link #SHOW_FORCED}.
     */
    public static final int HIDE_NOT_ALWAYS = 0x0002;

    /**
     * Synonym for {@link #hideSoftInputFromWindow(IBinder, int, ResultReceiver)}
     * without a result: request to hide the soft input window from the
     * context of the window that is currently accepting input.
     *
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     */
    public boolean hideSoftInputFromWindow(IBinder windowToken, @HideFlags int flags) {
        return hideSoftInputFromWindow(windowToken, flags, null);
    }

    /**
     * Request to hide the soft input window from the context of the window
     * that is currently accepting input.  This should be called as a result
     * of the user doing some actually than fairly explicitly requests to
     * have the input window hidden.
     *
     * <p><strong>Caveat:</strong> {@link ResultReceiver} instance passed to
     * this method can be a long-lived object, because it may not be
     * garbage-collected until all the corresponding {@link ResultReceiver}
     * objects transferred to different processes get garbage-collected.
     * Follow the general patterns to avoid memory leaks in Android.
     * Consider to use {@link java.lang.ref.WeakReference} so that application
     * logic objects such as {@link android.app.Activity} and {@link Context}
     * can be garbage collected regardless of the lifetime of
     * {@link ResultReceiver}.
     *
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     * @param resultReceiver If non-null, this will be called by the IME when
     * it has processed your request to tell you what it has done.  The result
     * code you receive may be either {@link #RESULT_UNCHANGED_SHOWN},
     * {@link #RESULT_UNCHANGED_HIDDEN}, {@link #RESULT_SHOWN}, or
     * {@link #RESULT_HIDDEN}.
     */
    public boolean hideSoftInputFromWindow(IBinder windowToken, @HideFlags int flags,
            ResultReceiver resultReceiver) {
        return hideSoftInputFromWindow(windowToken, flags, resultReceiver,
                SoftInputShowHideReason.HIDE_SOFT_INPUT);
    }

    private boolean hideSoftInputFromWindow(IBinder windowToken, @HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        final ImeTracker.Token statsToken = ImeTracker.forLogging().onRequestHide(
                null /* component */, Process.myUid(),
                ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT, reason);
        ImeTracker.forLatency().onRequestHide(statsToken, ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                reason, ActivityThread::currentApplication);
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#hideSoftInputFromWindow",
                this, null /* icProto */);
        checkFocus();
        synchronized (mH) {
            final View servedView = getServedViewLocked();
            if (servedView == null || servedView.getWindowToken() != windowToken) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                ImeTracker.forLatency().onHideFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_VIEW_SERVED, ActivityThread::currentApplication);
                return false;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            return IInputMethodManagerGlobalInvoker.hideSoftInput(mClient, windowToken, statsToken,
                    flags, resultReceiver, reason);
        }
    }

    /**
     * Synonym for {@link #hideSoftInputFromWindow(IBinder, int)} but takes a {@link View} as a
     * parameter to be a counterpart of {@link #showSoftInput(View, int)}.
     *
     * @param view {@link View} to be used to conditionally issue hide request when and only when
     *             this {@link View} is serving as an IME target.
     * @hide
     */
    public boolean hideSoftInputFromView(@NonNull View view, @HideFlags int flags) {
        final var reason = SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_VIEW;
        final ImeTracker.Token statsToken = ImeTracker.forLogging().onRequestHide(
                null /* component */, Process.myUid(),
                ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT, reason);
        ImeTracker.forLatency().onRequestHide(statsToken, ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                reason, ActivityThread::currentApplication);
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#hideSoftInputFromView",
                this, null /* icProto */);
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                ImeTracker.forLatency().onShowFailed(
                        statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED,
                        ActivityThread::currentApplication);
                Log.w(TAG, "Ignoring hideSoftInputFromView() as view=" + view + " is not served.");
                return false;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            return IInputMethodManagerGlobalInvoker.hideSoftInput(mClient, view.getWindowToken(),
                    statsToken, flags, null, reason);
        }
    }

    /**
     * Start stylus handwriting session.
     *
     * If supported by the current input method, a stylus handwriting session is started on the
     * given View, capturing all stylus input and converting it to InputConnection commands.
     *
     * If handwriting mode is started successfully by the IME, any currently dispatched stylus
     * pointers will be {@code android.view.MotionEvent#FLAG_CANCELED} cancelled.
     *
     * If Stylus handwriting mode is not supported or cannot be fulfilled for any reason by IME,
     * request will be ignored and Stylus touch will continue as normal touch input. Ideally,
     * {@link #isStylusHandwritingAvailable()} should be called first to determine if stylus
     * handwriting is supported by IME.
     *
     * @param view the View for which stylus handwriting is requested. It and
     * {@link View#hasWindowFocus its window} must be {@link View#hasFocus focused}.
     * @see #isStylusHandwritingAvailable()
     */
    public void startStylusHandwriting(@NonNull View view) {
        startStylusHandwritingInternal(
                view, /* delegatorPackageName= */ null, /* handwritingDelegateFlags= */ 0);
    }

    private boolean startStylusHandwritingInternal(
            @NonNull View view, @Nullable String delegatorPackageName,
            @HandwritingDelegateFlags int handwritingDelegateFlags) {
        Objects.requireNonNull(view);

        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.startStylusHandwritingInternal(
                    view, delegatorPackageName, handwritingDelegateFlags);
        }

        boolean useDelegation = !TextUtils.isEmpty(delegatorPackageName);

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                Log.w(TAG,
                        "Ignoring startStylusHandwriting as view=" + view + " is not served.");
                return false;
            }
            if (view.getViewRootImpl() != mCurRootView) {
                Log.w(TAG,
                        "Ignoring startStylusHandwriting: View's window does not have focus.");
                return false;
            }
            if (useDelegation) {
                return IInputMethodManagerGlobalInvoker.acceptStylusHandwritingDelegation(
                        mClient, UserHandle.myUserId(), view.getContext().getOpPackageName(),
                        delegatorPackageName, handwritingDelegateFlags);
            } else {
                IInputMethodManagerGlobalInvoker.startStylusHandwriting(mClient);
            }
            return false;
        }
    }

    /**
     * Prepares delegation of starting stylus handwriting session to a different editor in same
     * or different window than the view on which initial handwriting stroke was detected.
     *
     * Delegation can be used to start stylus handwriting session before the {@code Editor} view or
     * its {@link InputConnection} is started. Calling this method starts buffering of stylus
     * motion events until {@link #acceptStylusHandwritingDelegation(View)} is called, at which
     * point the handwriting session can be started and the buffered stylus motion events will be
     * delivered to the IME.
     * e.g. Delegation can be used when initial handwriting stroke is
     * on a pseudo {@code Editor} like widget (with no {@link InputConnection}) but actual
     * {@code Editor} is on a different window.
     *
     * <p> Note: If an actual {@code Editor} capable of {@link InputConnection} is being scribbled
     * upon using stylus, use {@link #startStylusHandwriting(View)} instead.</p>
     *
     * @param delegatorView the view that receives initial stylus stroke and delegates it to the
     *  actual editor. Its window must {@link View#hasWindowFocus have focus}.
     * @see #prepareStylusHandwritingDelegation(View, String)
     * @see #acceptStylusHandwritingDelegation(View)
     * @see #startStylusHandwriting(View)
     */
    public void prepareStylusHandwritingDelegation(@NonNull View delegatorView) {
        prepareStylusHandwritingDelegation(
                delegatorView, delegatorView.getContext().getOpPackageName());
    }

    /**
     * Prepares delegation of starting stylus handwriting session to a different editor in same or a
     * different window in a different package than the view on which initial handwriting stroke
     * was detected.
     *
     * Delegation can be used to start stylus handwriting session before the {@code Editor} view or
     * its {@link InputConnection} is started. Calling this method starts buffering of stylus
     * motion events until {@link #acceptStylusHandwritingDelegation(View, String)} is called, at
     * which point the handwriting session can be started and the buffered stylus motion events will
     * be delivered to the IME.
     * e.g. Delegation can be used when initial handwriting stroke is
     * on a pseudo {@code Editor} like widget (with no {@link InputConnection}) but actual
     * {@code Editor} is on a different window in the given package.
     *
     * <p>Note: If delegator and delegate are in same package use
     * {@link #prepareStylusHandwritingDelegation(View)} instead.</p>
     *
     * @param delegatorView  the view that receives initial stylus stroke and delegates it to the
     * actual editor. Its window must {@link View#hasWindowFocus have focus}.
     * @param delegatePackageName package name that contains actual {@code Editor} which should
     *  start stylus handwriting session by calling {@link #acceptStylusHandwritingDelegation}.
     * @see #prepareStylusHandwritingDelegation(View)
     * @see #acceptStylusHandwritingDelegation(View, String)
     */
    public void prepareStylusHandwritingDelegation(
            @NonNull View delegatorView, @NonNull String delegatePackageName) {
        Objects.requireNonNull(delegatorView);
        Objects.requireNonNull(delegatePackageName);

        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm =
                getFallbackInputMethodManagerIfNecessary(delegatorView);
        if (fallbackImm != null) {
            fallbackImm.prepareStylusHandwritingDelegation(delegatorView, delegatePackageName);
        }

        IInputMethodManagerGlobalInvoker.prepareStylusHandwritingDelegation(
                mClient,
                UserHandle.myUserId(),
                delegatePackageName,
                delegatorView.getContext().getOpPackageName());
    }

    /**
     * Accepts and starts a stylus handwriting session on the delegate view, if handwriting
     * initiation delegation was previously requested using
     * {@link #prepareStylusHandwritingDelegation(View)} from the delegator.
     *
     * <p>Note: If delegator and delegate are in different application packages, use
     * {@link #acceptStylusHandwritingDelegation(View, String)} instead.</p>
     *
     * @param delegateView delegate view capable of receiving input via {@link InputConnection}
     *  on which {@link #startStylusHandwriting(View)} will be called.
     * @return {@code true} if view belongs to same application package as used in
     *  {@link #prepareStylusHandwritingDelegation(View)} and handwriting session can start.
     * @see #acceptStylusHandwritingDelegation(View, String)
     * @see #prepareStylusHandwritingDelegation(View)
     */
    public boolean acceptStylusHandwritingDelegation(@NonNull View delegateView) {
        return startStylusHandwritingInternal(
                delegateView, delegateView.getContext().getOpPackageName(),
                delegateView.getHandwritingDelegateFlags());
    }

    /**
     * Accepts and starts a stylus handwriting session on the delegate view, if handwriting
     * initiation delegation was previously requested using
     * {@link #prepareStylusHandwritingDelegation(View, String)} from the delegator and the view
     * belongs to a specified delegate package.
     *
     * <p>Note: If delegator and delegate are in the same application package, use
     * {@link #acceptStylusHandwritingDelegation(View)} instead.</p>
     *
     * @param delegateView delegate view capable of receiving input via {@link InputConnection}
     *  on which {@link #startStylusHandwriting(View)} will be called.
     * @param delegatorPackageName package name of the delegator that handled initial stylus stroke.
     * @return {@code true} if view belongs to allowed delegate package declared in
     *  {@link #prepareStylusHandwritingDelegation(View, String)} and handwriting session can start.
     * @see #prepareStylusHandwritingDelegation(View, String)
     * @see #acceptStylusHandwritingDelegation(View)
     */
    public boolean acceptStylusHandwritingDelegation(
            @NonNull View delegateView, @NonNull String delegatorPackageName) {
        Objects.requireNonNull(delegatorPackageName);
        return startStylusHandwritingInternal(
                delegateView, delegatorPackageName, delegateView.getHandwritingDelegateFlags());
    }

    /**
     * Accepts and starts a stylus handwriting session on the delegate view, if handwriting
     * initiation delegation was previously requested using {@link
     * #prepareStylusHandwritingDelegation(View, String)} from the delegator and the view belongs to
     * a specified delegate package.
     *
     * <p>Note: If delegator and delegate are in the same application package, use {@link
     * #acceptStylusHandwritingDelegation(View)} instead.
     *
     * @param delegateView delegate view capable of receiving input via {@link InputConnection} on
     *     which {@link #startStylusHandwriting(View)} will be called.
     * @param delegatorPackageName package name of the delegator that handled initial stylus stroke.
     * @param flags {@link #HANDWRITING_DELEGATE_FLAG_HOME_DELEGATOR_ALLOWED} or {@code 0}
     * @return {@code true} if view belongs to allowed delegate package declared in {@link
     *     #prepareStylusHandwritingDelegation(View, String)} and handwriting session can start.
     * @see #prepareStylusHandwritingDelegation(View, String)
     * @see #acceptStylusHandwritingDelegation(View)
     */
    @FlaggedApi(FLAG_HOME_SCREEN_HANDWRITING_DELEGATOR)
    public boolean acceptStylusHandwritingDelegation(
            @NonNull View delegateView, @NonNull String delegatorPackageName,
            @HandwritingDelegateFlags int flags) {
        Objects.requireNonNull(delegatorPackageName);

        return startStylusHandwritingInternal(delegateView, delegatorPackageName, flags);
    }

    /**
     * This method toggles the input method window display.
     * If the input window is already displayed, it gets hidden.
     * If not the input window will be displayed.
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     *
     * @deprecated Use {@link #showSoftInput(View, int)} or
     * {@link #hideSoftInputFromWindow(IBinder, int)} explicitly instead.
     * In particular during focus changes, the current visibility of the IME is not
     * well defined. Starting in {@link Build.VERSION_CODES#S Android S}, this only
     * has an effect if the calling app is the current IME focus.
     */
    @Deprecated
    public void toggleSoftInputFromWindow(IBinder windowToken, @ShowFlags int showFlags,
            @HideFlags int hideFlags) {
        ImeTracing.getInstance().triggerClientDump(
                "InputMethodManager#toggleSoftInputFromWindow", InputMethodManager.this,
                null /* icProto */);
        synchronized (mH) {
            final View servedView = getServedViewLocked();
            if (servedView == null || servedView.getWindowToken() != windowToken) {
                return;
            }
            toggleSoftInput(showFlags, hideFlags);
        }
    }

    /**
     * This method toggles the input method window display.
     *
     * If the input window is already displayed, it gets hidden.
     * If not the input window will be displayed.
     *
     * @deprecated Use {@link #showSoftInput(View, int)} or
     * {@link #hideSoftInputFromWindow(IBinder, int)} explicitly instead.
     * In particular during focus changes, the current visibility of the IME is not
     * well defined. Starting in {@link Build.VERSION_CODES#S Android S}, this only
     * has an effect if the calling app is the current IME focus.
     */
    @Deprecated
    public void toggleSoftInput(@ShowFlags int showFlags, @HideFlags int hideFlags) {
        ImeTracing.getInstance().triggerClientDump(
                "InputMethodManager#toggleSoftInput", InputMethodManager.this,
                null /* icProto */);
        synchronized (mH) {
            final View view = getServedViewLocked();
            if (view != null) {
                final WindowInsets rootInsets = view.getRootWindowInsets();
                if (rootInsets != null && rootInsets.isVisible(WindowInsets.Type.ime())) {
                    hideSoftInputFromWindow(view.getWindowToken(), hideFlags, null,
                            SoftInputShowHideReason.HIDE_TOGGLE_SOFT_INPUT);
                } else {
                    showSoftInput(view, null /* statsToken */, showFlags, null /* resultReceiver */,
                            SoftInputShowHideReason.SHOW_TOGGLE_SOFT_INPUT);
                }
            }
        }
    }

    /**
     * If the input method is currently connected to the given view,
     * restart it with its new contents.  You should call this when the text
     * within your view changes outside of the normal input method or key
     * input flow, such as when an application calls TextView.setText().
     *
     * @param view The view whose text has changed.
     */
    public void restartInput(View view) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.restartInput(view);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                return;
            }

            mServedConnecting = true;
        }

        startInputInner(StartInputReason.APP_CALLED_RESTART_INPUT_API, null, 0, 0, 0);
    }

    /**
     * Sends an async signal to the IME to reset the currently served {@link InputConnection}.
     *
     * @param inputConnection the connection to be invalidated.
     * @param textSnapshot {@link TextSnapshot} to be used to update {@link EditorInfo}.
     * @param sessionId the session ID to be sent.
     * @return {@code true} if the operation is done. {@code false} if the caller needs to fall back
     *         to {@link InputMethodManager#restartInput(View)}.
     * @hide
     */
    public boolean doInvalidateInput(@NonNull RemoteInputConnectionImpl inputConnection,
            @NonNull TextSnapshot textSnapshot, int sessionId) {
        synchronized (mH) {
            if (mServedInputConnection != inputConnection || mCurrentEditorInfo == null) {
                // OK to ignore because the calling InputConnection is already abandoned.
                return true;
            }
            if (!isImeSessionAvailableLocked()) {
                // IME is not yet bound to the client.  Need to fall back to the restartInput().
                return false;
            }
            final EditorInfo editorInfo = mCurrentEditorInfo.createCopyInternal();
            editorInfo.initialSelStart = mCursorSelStart = textSnapshot.getSelectionStart();
            editorInfo.initialSelEnd = mCursorSelEnd = textSnapshot.getSelectionEnd();
            mCursorCandStart = textSnapshot.getCompositionStart();
            mCursorCandEnd = textSnapshot.getCompositionEnd();
            editorInfo.initialCapsMode = textSnapshot.getCursorCapsMode();
            editorInfo.setInitialSurroundingTextInternal(textSnapshot.getSurroundingText());
            mCurBindState.mImeSession.invalidateInput(editorInfo, mServedInputConnection,
                    sessionId);
            final IRemoteAccessibilityInputConnection accessibilityInputConnection =
                    mServedInputConnection.asIRemoteAccessibilityInputConnection();
            forAccessibilitySessionsLocked(wrapper -> wrapper.invalidateInput(editorInfo,
                    accessibilityInputConnection, sessionId));
            return true;
        }
    }

    /**
     * Gives a hint to the system that the text associated with {@code view} is updated by something
     * that is not an input method editor (IME), so that the system can cancel any pending text
     * editing requests from the IME until it receives the new editing context such as surrounding
     * text provided by {@link InputConnection#takeSnapshot()}.
     *
     * <p>When {@code view} does not support {@link InputConnection#takeSnapshot()} protocol,
     * calling this method may trigger {@link View#onCreateInputConnection(EditorInfo)}.</p>
     *
     * <p>Unlike {@link #restartInput(View)}, this API does not immediately interact with
     * {@link InputConnection}.  Instead, the application may later receive
     * {@link InputConnection#takeSnapshot()} as needed so that the system can capture new editing
     * context for the IME.  For instance, successive invocations of this API can be coerced into a
     * single (or zero) callback of {@link InputConnection#takeSnapshot()}.</p>
     *
     * @param view The view whose text has changed.
     * @see #restartInput(View)
     */
    public void invalidateInput(@NonNull View view) {
        Objects.requireNonNull(view);

        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.invalidateInput(view);
            return;
        }

        synchronized (mH) {
            if (mServedInputConnection == null || getServedViewLocked() != view) {
                return;
            }
            mServedInputConnection.scheduleInvalidateInput();
        }
    }

    /**
     * Starts an input connection from the served view that gains the window focus.
     * Note that this method should *NOT* be called inside of {@code mH} lock to prevent start input
     * background thread may blocked by other methods which already inside {@code mH} lock.
     *
     * <p>{@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} is required when and only when
     * {@code userId} is different from the user id of the current process.</p>
     */
    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS_FULL, conditional = true)
    private boolean startInputInner(@StartInputReason int startInputReason,
            @Nullable IBinder windowGainingFocus, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode, int windowFlags) {
        final View view;
        synchronized (mH) {
            view = getServedViewLocked();

            // Make sure we have a window token for the served view.
            if (DEBUG) {
                Log.v(TAG, "Starting input: view=" + InputMethodDebug.dumpViewInfo(view) +
                        " reason=" + InputMethodDebug.startInputReasonToString(startInputReason));
            }
            if (view == null) {
                if (DEBUG) Log.v(TAG, "ABORT input: no served view!");
                return false;
            }
        }

        // Now we need to get an input connection from the served view.
        // This is complicated in a couple ways: we can't be holding our lock
        // when calling out to the view, and we need to make sure we call into
        // the view on the same thread that is driving its view hierarchy.
        Handler vh = view.getHandler();
        if (vh == null) {
            // If the view doesn't have a handler, something has changed out
            // from under us, so just close the current input.
            // If we don't close the current input, the current input method can remain on the
            // screen without a connection.
            if (DEBUG) Log.v(TAG, "ABORT input: no handler for view! Close current input.");
            closeCurrentInput();
            return false;
        }
        if (vh.getLooper() != Looper.myLooper()) {
            // The view is running on a different thread than our own, so
            // we need to reschedule our work for over there.
            if (DEBUG) Log.v(TAG, "Starting input: reschedule to view thread");
            vh.post(() -> startInputOnWindowFocusGainInternal(startInputReason, null, 0, 0, 0));
            return false;
        }

        if (windowGainingFocus == null) {
            windowGainingFocus = view.getWindowToken();
            if (windowGainingFocus == null) {
                Log.e(TAG, "ABORT input: ServedView must be attached to a Window");
                return false;
            }
            startInputFlags = getStartInputFlags(view, startInputFlags);
            softInputMode = view.getViewRootImpl().mWindowAttributes.softInputMode;
            windowFlags = view.getViewRootImpl().mWindowAttributes.flags;
        }

        // Okay we are now ready to call into the served view and have it
        // do its stuff.
        // Life is good: let's hook everything up!
        final Pair<InputConnection, EditorInfo> connectionPair = createInputConnection(view);
        final InputConnection ic = connectionPair.first;
        final EditorInfo editorInfo = connectionPair.second;
        final Handler icHandler;
        InputBindResult res = null;
        final boolean hasServedView;
        synchronized (mH) {
            // Now that we are locked again, validate that our state hasn't
            // changed.
            final View servedView = getServedViewLocked();
            if (servedView != view || !mServedConnecting) {
                // Something else happened, so abort.
                if (DEBUG) Log.v(TAG, "Starting input: finished by someone else."
                        + " view=" + InputMethodDebug.dumpViewInfo(view)
                        + " servedView=" + InputMethodDebug.dumpViewInfo(servedView)
                        + " mServedConnecting=" + mServedConnecting);
                if (mServedInputConnection != null && startInputReason == BOUND_TO_IMMS) {
                    // This is not an error. Once IME binds (MSG_BIND), InputConnection is fully
                    // established. So we report this to interested recipients.
                    reportInputConnectionOpened(
                            mServedInputConnection.getInputConnection(), mCurrentEditorInfo,
                            mServedInputConnectionHandler, view);
                }
                return false;
            }

            // If we already have a text box, then this view is already
            // connected so we want to restart it.
            if (mCurrentEditorInfo == null) {
                startInputFlags |= StartInputFlags.INITIAL_CONNECTION;
            }

            editorInfo.setInitialToolType(mCurRootView.getLastClickToolType());

            // Hook 'em up and let 'er rip.
            mCurrentEditorInfo = editorInfo.createCopyInternal();
            // Store the previously served connection so that we can determine whether it is safe
            // to skip the call to startInputOrWindowGainedFocus in the IMMS
            final RemoteInputConnectionImpl previouslyServedConnection = mServedInputConnection;

            mServedConnecting = false;
            if (mServedInputConnection != null) {
                mServedInputConnection.deactivate();
                mServedInputConnection = null;
                mServedInputConnectionHandler = null;
            }
            final RemoteInputConnectionImpl servedInputConnection;
            if (ic != null) {
                mCursorSelStart = editorInfo.initialSelStart;
                mCursorSelEnd = editorInfo.initialSelEnd;
                mInitialSelStart = mCursorSelStart;
                mInitialSelEnd = mCursorSelEnd;
                mCursorCandStart = -1;
                mCursorCandEnd = -1;
                mCursorRect.setEmpty();
                mCursorAnchorInfo = null;
                Handler handler = null;
                try {
                    handler = ic.getHandler();
                } catch (AbstractMethodError ignored) {
                    // TODO(b/199934664): See if we can remove this by providing a default impl.
                }
                icHandler = handler;
                mServedInputConnectionHandler = icHandler;
                servedInputConnection = new RemoteInputConnectionImpl(
                        icHandler != null ? icHandler.getLooper() : vh.getLooper(), ic, this, view);
            } else {
                servedInputConnection = null;
                icHandler = null;
                mServedInputConnectionHandler = null;
            }
            mServedInputConnection = servedInputConnection;

            if (DEBUG) {
                Log.v(TAG, "START INPUT: view=" + InputMethodDebug.dumpViewInfo(view)
                        + " ic=" + ic + " editorInfo=" + editorInfo + " startInputFlags="
                        + InputMethodDebug.startInputFlagsToString(startInputFlags));
            }

            // When we switch between non-editable views, do not call into the IMMS.
            final boolean canSkip = OPTIMIZE_NONEDITABLE_VIEWS
                    && previouslyServedConnection == null
                    && ic == null
                    && isSwitchingBetweenEquivalentNonEditableViews(
                            mPreviousViewFocusParameters, startInputFlags,
                            startInputReason, softInputMode, windowFlags);
            mPreviousViewFocusParameters = new ViewFocusParameterInfo(mCurrentEditorInfo,
                    startInputFlags, startInputReason, softInputMode, windowFlags);
            if (canSkip) {
                if (DEBUG) {
                    Log.d(TAG, "Not calling IMMS due to switching between non-editable views.");
                }
                return false;
            }
            final int targetUserId = editorInfo.targetInputMethodUser != null
                    ? editorInfo.targetInputMethodUser.getIdentifier() : UserHandle.myUserId();
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMM.startInputOrWindowGainedFocus");
            res = IInputMethodManagerGlobalInvoker.startInputOrWindowGainedFocus(
                    startInputReason, mClient, windowGainingFocus, startInputFlags,
                    softInputMode, windowFlags, editorInfo, servedInputConnection,
                    servedInputConnection == null ? null
                            : servedInputConnection.asIRemoteAccessibilityInputConnection(),
                    view.getContext().getApplicationInfo().targetSdkVersion, targetUserId,
                    mImeDispatcher);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (DEBUG) Log.v(TAG, "Starting input: Bind result=" + res);
            if (res == null) {
                Log.wtf(TAG, "startInputOrWindowGainedFocus must not return"
                        + " null. startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason)
                        + " editorInfo=" + editorInfo
                        + " startInputFlags="
                        + InputMethodDebug.startInputFlagsToString(startInputFlags));
                return false;
            }
            if (res.id != null) {
                updateInputChannelLocked(res.channel);
                mCurMethod = res.method; // for @UnsupportedAppUsage
                mCurBindState = new BindState(res);
                mAccessibilityInputMethodSession.clear();
                if (res.accessibilitySessions != null) {
                    for (int i = 0; i < res.accessibilitySessions.size(); i++) {
                        IAccessibilityInputMethodSessionInvoker wrapper =
                                IAccessibilityInputMethodSessionInvoker.createOrNull(
                                        res.accessibilitySessions.valueAt(i));
                        if (wrapper != null) {
                            mAccessibilityInputMethodSession.append(
                                    res.accessibilitySessions.keyAt(i), wrapper);
                        }
                    }
                }
                mCurId = res.id; // for @UnsupportedAppUsage
            } else if (res.channel != null && res.channel != mCurChannel) {
                res.channel.dispose();
            }
            switch (res.result) {
                case InputBindResult.ResultCode.ERROR_NOT_IME_TARGET_WINDOW:
                    mRestartOnNextWindowFocus = true;
                    mServedView = null;
                    break;
            }
            if (mCompletions != null) {
                if (isImeSessionAvailableLocked()) {
                    mCurBindState.mImeSession.displayCompletions(mCompletions);
                }
            }
            hasServedView = mServedView != null;
        }

        // Notify the app that the InputConnection is initialized and ready for use.
        if (ic != null && res != null && res.method != null && hasServedView) {
            if (DEBUG) {
                Log.v(TAG, "Calling View.onInputConnectionOpened: view= " + view
                        + ", ic=" + ic + ", editorInfo=" + editorInfo + ", handler=" + icHandler);
            }
            reportInputConnectionOpened(ic, editorInfo, icHandler, view);
        }

        return true;
    }

    /**
     * @return {@code true} when we are switching focus between two non-editable views
     * so that we can avoid calling {@link IInputMethodManager#startInputOrWindowGainedFocus}.
     */
    @GuardedBy("mH")
    private boolean isSwitchingBetweenEquivalentNonEditableViews(
            @Nullable ViewFocusParameterInfo previousViewFocusParameters,
            @StartInputFlags int startInputFlags,
            @StartInputReason int startInputReason,
            @SoftInputModeFlags int softInputMode,
            int windowFlags) {
        return (startInputFlags & StartInputFlags.WINDOW_GAINED_FOCUS) == 0
                && (startInputFlags & StartInputFlags.IS_TEXT_EDITOR) == 0
                && previousViewFocusParameters != null
                && previousViewFocusParameters.sameAs(mCurrentEditorInfo,
                    startInputFlags, startInputReason, softInputMode, windowFlags);
    }

    private void reportInputConnectionOpened(
            InputConnection ic, EditorInfo editorInfo, Handler icHandler, View view) {
        view.onInputConnectionOpenedInternal(ic, editorInfo, icHandler);
        final ViewRootImpl viewRoot = view.getViewRootImpl();
        if (viewRoot != null) {
            viewRoot.getHandwritingInitiator().onInputConnectionCreated(view);
        }
    }

    /**
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    public void addVirtualStylusIdForTestSession() {
        synchronized (mH) {
            IInputMethodManagerGlobalInvoker.addVirtualStylusIdForTestSession(mClient);
        }
    }

    /**
     * Set a stylus idle-timeout after which handwriting {@code InkWindow} will be removed.
     * <p> This API is for tests only.</p>
     * @param timeout to set in milliseconds. To reset to default, use a value <= zero.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    public void setStylusWindowIdleTimeoutForTest(@DurationMillisLong long timeout) {
        synchronized (mH) {
            IInputMethodManagerGlobalInvoker.setStylusWindowIdleTimeoutForTest(mClient, timeout);
        }
    }

    /**
     * An empty method only to avoid crashes of apps that call this method via reflection and do not
     * handle {@link NoSuchMethodException} in a graceful manner.
     *
     * @deprecated This is an empty method.  No framework method must call this method.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(trackingBug = 37122102, maxTargetSdk = Build.VERSION_CODES.Q,
            publicAlternatives = "{@code androidx.activity.ComponentActivity}")
    public void windowDismissed(IBinder appWindowToken) {
        // Intentionally empty.
        //
        // It seems that some applications call this method via reflection to null clear the
        // following fields that used to exist in InputMethodManager:
        //  * InputMethodManager#mCurRootView
        //  * InputMethodManager#mServedView
        //  * InputMethodManager#mNextServedView
        // so that these objects can be garbage-collected when an Activity gets dismissed.
        //
        // It is indeed true that older versions of InputMethodManager had issues that prevented
        // these fields from being null-cleared when it should have been, but the understanding of
        // the engineering team is that all known issues have already been fixed as of Android 10.
        //
        // For older devices, developers can work around the object leaks by using
        // androidx.activity.ComponentActivity.
        // See https://issuetracker.google.com/u/1/issues/37122102 for details.
        //
        // If you believe InputMethodManager is leaking objects in API 24 or any later version,
        // please file a bug at https://issuetracker.google.com/issues/new?component=192705.
    }

    private int getStartInputFlags(View focusedView, int startInputFlags) {
        startInputFlags |= StartInputFlags.VIEW_HAS_FOCUS;
        if (focusedView.onCheckIsTextEditor()) {
            startInputFlags |= StartInputFlags.IS_TEXT_EDITOR;
        }
        return startInputFlags;
    }

    /**
     * Note that this method should *NOT* be called inside of {@code mH} lock to prevent start input
     * background thread may blocked by other methods which already inside {@code mH} lock.
     * @hide
     */
    @UnsupportedAppUsage
    public void checkFocus() {
        synchronized (mH) {
            if (mCurRootView == null) {
                return;
            }
            if (!checkFocusInternalLocked(false /* forceNewFocus */, mCurRootView)) {
                return;
            }
        }
        startInputOnWindowFocusGainInternal(StartInputReason.CHECK_FOCUS,
                null /* focusedView */,
                0 /* startInputFlags */, 0 /* softInputMode */, 0 /* windowFlags */);
    }

    /**
     * Check the next served view if needs to start input.
     */
    @GuardedBy("mH")
    private boolean checkFocusInternalLocked(boolean forceNewFocus, ViewRootImpl viewRootImpl) {
        if (mCurRootView != viewRootImpl) {
            return false;
        }
        if (mServedView == mNextServedView && !forceNewFocus) {
            return false;
        }
        if (DEBUG) {
            Log.v(TAG, "checkFocus: view=" + mServedView
                    + " next=" + mNextServedView
                    + " force=" + forceNewFocus
                    + " package="
                    + (mServedView != null ? mServedView.getContext().getPackageName()
                    : "<none>"));
        }
        // Close the connection when no next served view coming.
        if (mNextServedView == null) {
            finishInputLocked();
            closeCurrentInput();
            return false;
        }
        mServedView = mNextServedView;
        if (mServedInputConnection != null) {
            mServedInputConnection.finishComposingTextFromImm();
        }
        return true;
    }

    @UiThread
    private void onViewFocusChangedInternal(@Nullable View view, boolean hasFocus) {
        if (view == null || view.isTemporarilyDetached()) {
            return;
        }
        final ViewRootImpl viewRootImpl = view.getViewRootImpl();
        synchronized (mH) {
            if (mCurRootView != viewRootImpl) {
                return;
            }
            if (!view.hasImeFocus() || !view.hasWindowFocus()) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onViewFocusChangedInternal, view="
                        + InputMethodDebug.dumpViewInfo(view));
            }

            // We don't need to track the next served view when the view lost focus here
            // because:
            // 1) The current view focus may be cleared temporary when in touch mode, closing
            //    input at this moment isn't the right way.
            // 2) We only care about the served view change when it focused, since changing
            //    input connection when the focus target changed is reasonable.
            // 3) Setting the next served view as null when no more served view should be
            //    handled in other special events (e.g. view detached from window or the window
            //    dismissed).
            if (hasFocus) {
                mNextServedView = view;
            }
        }
        viewRootImpl.dispatchCheckFocus();
    }

    @UnsupportedAppUsage
    void closeCurrentInput() {
        final ImeTracker.Token statsToken = ImeTracker.forLogging().onRequestHide(
                null /* component */, Process.myUid(), ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION);
        ImeTracker.forLatency().onRequestHide(statsToken, ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION,
                ActivityThread::currentApplication);

        synchronized (mH) {
            final View rootView = mCurRootView != null ? mCurRootView.getView() : null;
            if (rootView == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                ImeTracker.forLatency().onHideFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_VIEW_SERVED, ActivityThread::currentApplication);
                Log.w(TAG, "No current root view, ignoring closeCurrentInput()");
                return;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            IInputMethodManagerGlobalInvoker.hideSoftInput(
                    mClient,
                    rootView.getWindowToken(),
                    statsToken,
                    HIDE_NOT_ALWAYS,
                    null,
                    SoftInputShowHideReason.HIDE_CLOSE_CURRENT_SESSION);
        }
    }

    /**
     * Register for IME state callbacks and applying visibility in
     * {@link android.view.ImeInsetsSourceConsumer}.
     * @hide
     */
    public void registerImeConsumer(@NonNull ImeInsetsSourceConsumer imeInsetsConsumer) {
        if (imeInsetsConsumer == null) {
            throw new IllegalStateException("ImeInsetsSourceConsumer cannot be null.");
        }

        synchronized (mH) {
            mImeInsetsConsumer = imeInsetsConsumer;
        }
    }

    /**
     * Unregister for IME state callbacks and applying visibility in
     * {@link android.view.ImeInsetsSourceConsumer}.
     * @hide
     */
    public void unregisterImeConsumer(@NonNull ImeInsetsSourceConsumer imeInsetsConsumer) {
        if (imeInsetsConsumer == null) {
            throw new IllegalStateException("ImeInsetsSourceConsumer cannot be null.");
        }

        synchronized (mH) {
            if (mImeInsetsConsumer == imeInsetsConsumer) {
                mImeInsetsConsumer = null;
            }
        }
    }

    /**
     * Call showSoftInput with currently focused view.
     *
     * @param windowToken the window from which this request originates. If this doesn't match the
     *                    currently served view, the request is ignored and returns {@code false}.
     * @param statsToken the token tracking the current IME show request or {@code null} otherwise.
     *
     * @return {@code true} if IME can (eventually) be shown, {@code false} otherwise.
     * @hide
     */
    public boolean requestImeShow(IBinder windowToken, @Nullable ImeTracker.Token statsToken) {
        checkFocus();
        synchronized (mH) {
            final View servedView = getServedViewLocked();
            if (servedView == null || servedView.getWindowToken() != windowToken) {
                ImeTracker.forLogging().onFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_REQUEST_IME_SHOW);
                return false;
            }

            ImeTracker.forLogging().onProgress(statsToken,
                    ImeTracker.PHASE_CLIENT_REQUEST_IME_SHOW);

            showSoftInput(servedView, statsToken, 0 /* flags */, null /* resultReceiver */,
                    SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API);
            return true;
        }
    }

    /**
     * Notify IMMS that IME insets are no longer visible.
     *
     * @param windowToken the window from which this request originates. If this doesn't match the
     *                    currently served view, the request is ignored.
     * @param statsToken the token tracking the current IME show request or {@code null} otherwise.
     * @hide
     */
    public void notifyImeHidden(IBinder windowToken, @Nullable ImeTracker.Token statsToken) {
        if (statsToken == null) {
            statsToken = ImeTracker.forLogging().onRequestHide(null /* component */,
                    Process.myUid(), ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API);
        }
        ImeTracker.forLatency().onRequestHide(statsToken, ImeTracker.ORIGIN_CLIENT_HIDE_SOFT_INPUT,
                SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API,
                ActivityThread::currentApplication);
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#notifyImeHidden", this,
                null /* icProto */);
        synchronized (mH) {
            if (!isImeSessionAvailableLocked() || mCurRootView == null
                    || mCurRootView.getWindowToken() != windowToken) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);
                ImeTracker.forLatency().onHideFailed(statsToken,
                        ImeTracker.PHASE_CLIENT_VIEW_SERVED, ActivityThread::currentApplication);
                return;
            }

            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_CLIENT_VIEW_SERVED);

            IInputMethodManagerGlobalInvoker.hideSoftInput(mClient, windowToken, statsToken,
                    0 /* flags */, null /* resultReceiver */,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_INSETS_API);
        }
    }

    /**
     * Notify IME directly to remove surface as it is no longer visible.
     * @param windowToken The client window token that requests the IME to remove its surface.
     * @hide
     */
    public void removeImeSurface(@NonNull IBinder windowToken) {
        synchronized (mH) {
            IInputMethodManagerGlobalInvoker.removeImeSurfaceFromWindowAsync(windowToken);
        }
    }

    /**
     * Report the current selection range.
     *
     * <p><strong>Editor authors</strong>, you need to call this method whenever
     * the cursor moves in your editor. Remember that in addition to doing this, your
     * editor needs to always supply current cursor values in
     * {@link EditorInfo#initialSelStart} and {@link EditorInfo#initialSelEnd} every
     * time {@link android.view.View#onCreateInputConnection(EditorInfo)} is
     * called, which happens whenever the keyboard shows up or the focus changes
     * to a text field, among other cases.</p>
     */
    public void updateSelection(View view, int selStart, int selEnd,
            int candidatesStart, int candidatesEnd) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.updateSelection(view, selStart, selEnd, candidatesStart, candidatesEnd);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || mCurrentEditorInfo == null
                    || !isImeSessionAvailableLocked()) {
                return;
            }

            if (mServedInputConnection != null && mServedInputConnection.hasPendingInvalidation()) {
                return;
            }

            if (mCursorSelStart != selStart || mCursorSelEnd != selEnd
                    || mCursorCandStart != candidatesStart
                    || mCursorCandEnd != candidatesEnd) {
                if (DEBUG) Log.d(TAG, "updateSelection");

                if (DEBUG) {
                    Log.v(TAG, "SELECTION CHANGE: " + mCurBindState.mImeSession);
                }
                mCurBindState.mImeSession.updateSelection(mCursorSelStart, mCursorSelEnd, selStart,
                        selEnd, candidatesStart, candidatesEnd);
                forAccessibilitySessionsLocked(wrapper -> wrapper.updateSelection(mCursorSelStart,
                        mCursorSelEnd, selStart, selEnd, candidatesStart, candidatesEnd));
                mCursorSelStart = selStart;
                mCursorSelEnd = selEnd;
                mCursorCandStart = candidatesStart;
                mCursorCandEnd = candidatesEnd;
            }
        }
    }

    /**
     * Notify the event when the user tapped or clicked the text view.
     *
     * @param view {@link View} which is being clicked.
     * @see InputMethodService#onViewClicked(boolean)
     * @deprecated The semantics of this method can never be defined well for composite {@link View}
     *             that works as a giant "Canvas", which can host its own UI hierarchy and sub focus
     *             state. {@link android.webkit.WebView} is a good example. Application / IME
     *             developers should not rely on this method.
     */
    @Deprecated
    public void viewClicked(View view) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.viewClicked(view);
            return;
        }

        final View servedView;
        final View nextServedView;
        synchronized (mH) {
            servedView = getServedViewLocked();
            nextServedView = getNextServedViewLocked();
        }
        final boolean focusChanged = servedView != nextServedView;
        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || mCurrentEditorInfo == null
                    || !isImeSessionAvailableLocked()) {
                return;
            }
            if (DEBUG) Log.v(TAG, "onViewClicked: " + focusChanged);
            mCurBindState.mImeSession.viewClicked(focusChanged);
        }
    }

    /**
     * Return true if the current input method wants to watch the location
     * of the input editor's cursor in its window.
     *
     * @deprecated Use {@link InputConnection#requestCursorUpdates(int)} instead.
     */
    @Deprecated
    public boolean isWatchingCursor(View view) {
        return false;
    }

    /**
     * Return true if the current input method wants to be notified when cursor/anchor location
     * is changed.
     *
     * @deprecated This method is kept for {@link UnsupportedAppUsage}.  Must not be used.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isCursorAnchorInfoEnabled() {
        synchronized (mH) {
            final boolean isImmediate = (mRequestUpdateCursorAnchorInfoMonitorMode &
                    CURSOR_UPDATE_IMMEDIATE) != 0;
            final boolean isMonitoring = (mRequestUpdateCursorAnchorInfoMonitorMode &
                    CURSOR_UPDATE_MONITOR) != 0;
            return isImmediate || isMonitoring;
        }
    }

    /**
     * Set the requested mode for {@link #updateCursorAnchorInfo(View, CursorAnchorInfo)}.
     *
     * @deprecated This method is kept for {@link UnsupportedAppUsage}.  Must not be used.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setUpdateCursorAnchorInfoMode(int flags) {
        synchronized (mH) {
            mRequestUpdateCursorAnchorInfoMonitorMode = flags;
        }
    }

    /**
     * Report the current cursor location in its window.
     *
     * @deprecated Use {@link #updateCursorAnchorInfo(View, CursorAnchorInfo)} instead.
     */
    @Deprecated
    public void updateCursor(View view, int left, int top, int right, int bottom) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.updateCursor(view, left, top, right, bottom);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || mCurrentEditorInfo == null
                    || !isImeSessionAvailableLocked()) {
                return;
            }

            mTmpCursorRect.set(left, top, right, bottom);
            if (!mCursorRect.equals(mTmpCursorRect)) {
                if (DEBUG) Log.d(TAG, "updateCursor: " + mCurBindState.mImeSession);

                mCurBindState.mImeSession.updateCursor(mTmpCursorRect);
                mCursorRect.set(mTmpCursorRect);
            }
        }
    }

    /**
     * Report positional change of the text insertion point and/or characters in the composition
     * string.
     */
    public void updateCursorAnchorInfo(View view, final CursorAnchorInfo cursorAnchorInfo) {
        if (view == null || cursorAnchorInfo == null) {
            return;
        }
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.updateCursorAnchorInfo(view, cursorAnchorInfo);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || mCurrentEditorInfo == null
                    || !isImeSessionAvailableLocked()) {
                return;
            }
            // If immediate bit is set, we will call updateCursorAnchorInfo() even when the data has
            // not been changed from the previous call.
            final boolean isImmediate = mServedInputConnection != null
                    && mServedInputConnection.resetHasPendingImmediateCursorAnchorInfoUpdate();
            if (!isImmediate && Objects.equals(mCursorAnchorInfo, cursorAnchorInfo)) {
                // TODO: Consider always emitting this message once we have addressed redundant
                // calls of this method from android.widget.Editor.
                if (DEBUG) {
                    Log.w(TAG, "Ignoring redundant updateCursorAnchorInfo: info="
                            + cursorAnchorInfo);
                }
                return;
            }
            if (DEBUG) Log.v(TAG, "updateCursorAnchorInfo: " + cursorAnchorInfo);
            mCurBindState.mImeSession.updateCursorAnchorInfo(cursorAnchorInfo);
            mCursorAnchorInfo = cursorAnchorInfo;
        }
    }

    /**
     * Call {@link InputMethodSession#appPrivateCommand(String, Bundle)
     * InputMethodSession.appPrivateCommand()} on the current Input Method.
     * @param view Optional View that is sending the command, or null if
     * you want to send the command regardless of the view that is attached
     * to the input method.
     * @param action Name of the command to be performed.  This <em>must</em>
     * be a scoped name, i.e. prefixed with a package name you own, so that
     * different developers will not create conflicting commands.
     * @param data Any data to include with the command.
     */
    public void sendAppPrivateCommand(View view, String action, Bundle data) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            fallbackImm.sendAppPrivateCommand(view, action, data);
            return;
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view) || mCurrentEditorInfo == null
                    || !isImeSessionAvailableLocked()) {
                return;
            }
            if (DEBUG) Log.v(TAG, "APP PRIVATE COMMAND " + action + ": " + data);
            mCurBindState.mImeSession.appPrivateCommand(action, data);
        }
    }

    /**
     * Force switch to a new input method component. This can only be called
     * from an application or a service which has a token of the currently active input method.
     *
     * <p>On Android {@link Build.VERSION_CODES#Q} and later devices, the undocumented behavior that
     * token can be {@code null} when the caller has
     * {@link Manifest.permission#WRITE_SECURE_SETTINGS} is deprecated. Instead, update
     * {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD} and
     * {@link android.provider.Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE} directly.</p>
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
     * @throws IllegalArgumentException if the input method is unknown or filtered by the rules of
     * <a href="/training/basics/intents/package-visibility">package visibility</a>.
     * @deprecated Use {@link InputMethodService#switchInputMethod(String)}
     * instead. This method was intended for IME developers who should be accessing APIs through
     * the service. APIs in this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void setInputMethod(IBinder token, String id) {
        if (token == null) {
            // There are still some system components that rely on this undocumented behavior
            // regarding null IME token with WRITE_SECURE_SETTINGS.  Provide a fallback logic as a
            // temporary remedy.
            if (id == null) {
                return;
            }
            if (Process.myUid() == Process.SYSTEM_UID) {
                Log.w(TAG, "System process should not be calling setInputMethod() because almost "
                        + "always it is a bug under multi-user / multi-profile environment. "
                        + "Consider interacting with InputMethodManagerService directly via "
                        + "LocalServices.");
                return;
            }
            final Context fallbackContext = ActivityThread.currentApplication();
            if (fallbackContext == null) {
                return;
            }
            if (fallbackContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            final List<InputMethodInfo> imis = getEnabledInputMethodList();
            final int numImis = imis.size();
            boolean found = false;
            for (int i = 0; i < numImis; ++i) {
                final InputMethodInfo imi = imis.get(i);
                if (id.equals(imi.getId())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                Log.e(TAG, "Ignoring setInputMethod(null, " + id + ") because the specified "
                        + "id not found in enabled IMEs.");
                return;
            }
            Log.w(TAG, "The undocumented behavior that setInputMethod() accepts null token "
                    + "when the caller has WRITE_SECURE_SETTINGS is deprecated. This behavior may "
                    + "be completely removed in a future version.  Update secure settings directly "
                    + "instead.");
            final ContentResolver resolver = fallbackContext.getContentResolver();
            Settings.Secure.putInt(resolver, Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                    NOT_A_SUBTYPE_ID);
            Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, id);
            return;
        }
        InputMethodPrivilegedOperationsRegistry.get(token).setInputMethod(id);
    }

    /**
     * Force switch to a new input method and subtype. This can only be called
     * from an application or a service which has a token of the currently active input method.
     *
     * <p>On Android {@link Build.VERSION_CODES#Q} and later devices, {@code token} cannot be
     * {@code null} even with {@link Manifest.permission#WRITE_SECURE_SETTINGS}. Instead,
     * update {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD} and
     * {@link android.provider.Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE} directly.</p>
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
     * @param subtype The new subtype of the new input method to be switched to.
     * @throws IllegalArgumentException if the input method is unknown or filtered by the rules of
     * <a href="/training/basics/intents/package-visibility">package visibility</a>.
     * @deprecated Use
     * {@link InputMethodService#switchInputMethod(String, InputMethodSubtype)}
     * instead. This method was intended for IME developers who should be accessing APIs through
     * the service. APIs in this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void setInputMethodAndSubtype(@NonNull IBinder token, String id,
            InputMethodSubtype subtype) {
        if (token == null) {
            Log.e(TAG, "setInputMethodAndSubtype() does not accept null token on Android Q "
                    + "and later.");
            return;
        }
        InputMethodPrivilegedOperationsRegistry.get(token).setInputMethodAndSubtype(id, subtype);
    }

    /**
     * Close/hide the input method's soft input area, so the user no longer
     * sees it or can interact with it.  This can only be called
     * from the currently active input method, as validated by the given token.
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @deprecated Use {@link InputMethodService#requestHideSelf(int)} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in this
     * class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void hideSoftInputFromInputMethod(IBinder token, @HideFlags int flags) {
        InputMethodPrivilegedOperationsRegistry.get(token).hideMySoftInput(
                flags, SoftInputShowHideReason.HIDE_SOFT_INPUT_IMM_DEPRECATION);
    }

    /**
     * Show the input method's soft input area, so the user
     * sees the input method window and can interact with it.
     * This can only be called from the currently active input method,
     * as validated by the given token.
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @deprecated Use {@link InputMethodService#requestShowSelf(int)} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in this
     * class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void showSoftInputFromInputMethod(IBinder token, @ShowFlags int flags) {
        InputMethodPrivilegedOperationsRegistry.get(token).showMySoftInput(flags);
    }

    /**
     * Dispatches an input event to the IME.
     *
     * Returns {@link #DISPATCH_HANDLED} if the event was handled.
     * Returns {@link #DISPATCH_NOT_HANDLED} if the event was not handled.
     * Returns {@link #DISPATCH_IN_PROGRESS} if the event is in progress and the
     * callback will be invoked later.
     *
     * @hide
     */
    public int dispatchInputEvent(InputEvent event, Object token,
            FinishedInputEventCallback callback, Handler handler) {
        synchronized (mH) {
            if (isImeSessionAvailableLocked()) {
                if (event instanceof KeyEvent) {
                    KeyEvent keyEvent = (KeyEvent)event;
                    if (keyEvent.getAction() == KeyEvent.ACTION_DOWN
                            && keyEvent.getKeyCode() == KeyEvent.KEYCODE_SYM
                            && keyEvent.getRepeatCount() == 0) {
                        showInputMethodPickerLocked();
                        return DISPATCH_HANDLED;
                    }
                }

                if (DEBUG) {
                    Log.v(TAG, "DISPATCH INPUT EVENT: " + mCurBindState.mImeSession);
                }

                PendingEvent p = obtainPendingEventLocked(
                        event, token, mCurBindState.mImeId, callback, handler);
                if (mMainLooper.isCurrentThread()) {
                    // Already running on the IMM thread so we can send the event immediately.
                    return sendInputEventOnMainLooperLocked(p);
                }

                // Post the event to the IMM thread.
                Message msg = mH.obtainMessage(MSG_SEND_INPUT_EVENT, p);
                msg.setAsynchronous(true);
                mH.sendMessage(msg);
                return DISPATCH_IN_PROGRESS;
            }
        }
        return DISPATCH_NOT_HANDLED;
    }

    /**
     * Provides the default implementation of {@link InputConnection#sendKeyEvent(KeyEvent)}, which
     * is expected to dispatch an keyboard event sent from the IME to an appropriate event target
     * depending on the given {@link View} and the current focus state.
     *
     * <p>CAUTION: This method is provided only for the situation where
     * {@link InputConnection#sendKeyEvent(KeyEvent)} needs to be implemented without relying on
     * {@link BaseInputConnection}. Do not use this API for anything else.</p>
     *
     * @param targetView the default target view. If {@code null} is specified, then this method
     * tries to find a good event target based on the current focus state.
     * @param event the key event to be dispatched.
     */
    public void dispatchKeyEventFromInputMethod(@Nullable View targetView,
            @NonNull KeyEvent event) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(targetView);
        if (fallbackImm != null) {
            fallbackImm.dispatchKeyEventFromInputMethod(targetView, event);
            return;
        }

        synchronized (mH) {
            ViewRootImpl viewRootImpl = targetView != null ? targetView.getViewRootImpl() : null;
            if (viewRootImpl == null) {
                final View servedView = getServedViewLocked();
                if (servedView != null) {
                    viewRootImpl = servedView.getViewRootImpl();
                }
            }
            if (viewRootImpl != null) {
                viewRootImpl.dispatchKeyFromIme(event);
            }
        }
    }

    // Must be called on the main looper
    private void sendInputEventAndReportResultOnMainLooper(PendingEvent p) {
        final boolean handled;
        synchronized (mH) {
            int result = sendInputEventOnMainLooperLocked(p);
            if (result == DISPATCH_IN_PROGRESS) {
                return;
            }

            handled = (result == DISPATCH_HANDLED);
        }

        invokeFinishedInputEventCallback(p, handled);
    }

    // Must be called on the main looper
    @GuardedBy("mH")
    private int sendInputEventOnMainLooperLocked(PendingEvent p) {
        if (mCurChannel != null) {
            if (mCurSender == null) {
                mCurSender = new ImeInputEventSender(mCurChannel, mH.getLooper());
            }

            final InputEvent event = p.mEvent;
            final int seq = event.getSequenceNumber();
            if (mCurSender.sendInputEvent(seq, event)) {
                mPendingEvents.put(seq, p);
                Trace.traceCounter(Trace.TRACE_TAG_INPUT, PENDING_EVENT_COUNTER,
                        mPendingEvents.size());

                Message msg = mH.obtainMessage(MSG_TIMEOUT_INPUT_EVENT, seq, 0, p);
                msg.setAsynchronous(true);
                mH.sendMessageDelayed(msg, INPUT_METHOD_NOT_RESPONDING_TIMEOUT);
                return DISPATCH_IN_PROGRESS;
            }

            if (sPreventImeStartupUnlessTextEditor) {
                Log.d(TAG, "Dropping event because IME is evicted: " + event);
            } else {
                Log.w(TAG, "Unable to send input event to IME: " + getImeIdLocked()
                        + " dropping: " + event);
            }
        }
        return DISPATCH_NOT_HANDLED;
    }

    private void finishedInputEvent(int seq, boolean handled, boolean timeout) {
        final PendingEvent p;
        synchronized (mH) {
            int index = mPendingEvents.indexOfKey(seq);
            if (index < 0) {
                return; // spurious, event already finished or timed out
            }

            p = mPendingEvents.valueAt(index);
            mPendingEvents.removeAt(index);
            Trace.traceCounter(Trace.TRACE_TAG_INPUT, PENDING_EVENT_COUNTER, mPendingEvents.size());

            if (timeout) {
                Log.w(TAG, "Timeout waiting for IME to handle input event after "
                        + INPUT_METHOD_NOT_RESPONDING_TIMEOUT + " ms: " + p.mInputMethodId);
            } else {
                mH.removeMessages(MSG_TIMEOUT_INPUT_EVENT, p);
            }
        }

        invokeFinishedInputEventCallback(p, handled);
    }

    // Assumes the event has already been removed from the queue.
    private void invokeFinishedInputEventCallback(PendingEvent p, boolean handled) {
        p.mHandled = handled;
        if (p.mHandler.getLooper().isCurrentThread()) {
            // Already running on the callback handler thread so we can send the
            // callback immediately.
            p.run();
        } else {
            // Post the event to the callback handler thread.
            // In this case, the callback will be responsible for recycling the event.
            Message msg = Message.obtain(p.mHandler, p);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }

    @GuardedBy("mH")
    private void flushPendingEventsLocked() {
        mH.removeMessages(MSG_FLUSH_INPUT_EVENT);

        final int count = mPendingEvents.size();
        for (int i = 0; i < count; i++) {
            int seq = mPendingEvents.keyAt(i);
            Message msg = mH.obtainMessage(MSG_FLUSH_INPUT_EVENT, seq, 0);
            msg.setAsynchronous(true);
            msg.sendToTarget();
        }
    }

    @GuardedBy("mH")
    private PendingEvent obtainPendingEventLocked(InputEvent event, Object token,
            String inputMethodId, FinishedInputEventCallback callback, Handler handler) {
        PendingEvent p = mPendingEventPool.acquire();
        if (p == null) {
            p = new PendingEvent();
        }
        p.mEvent = event;
        p.mToken = token;
        p.mInputMethodId = inputMethodId;
        p.mCallback = callback;
        p.mHandler = handler;
        return p;
    }

    @GuardedBy("mH")
    private void recyclePendingEventLocked(PendingEvent p) {
        p.recycle();
        mPendingEventPool.release(p);
    }

    /**
     * Show IME picker popup window.
     *
     * <p>Requires the {@link PackageManager#FEATURE_INPUT_METHODS} feature which can be detected
     * using {@link PackageManager#hasSystemFeature(String)}.
     */
    public void showInputMethodPicker() {
        synchronized (mH) {
            showInputMethodPickerLocked();
        }
    }

    /**
     * Shows the input method chooser dialog from system.
     *
     * @param showAuxiliarySubtypes Set true to show auxiliary input methods.
     * @param displayId The ID of the display where the chooser dialog should be shown.
     * @hide
     */
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public void showInputMethodPickerFromSystem(boolean showAuxiliarySubtypes, int displayId) {
        final int mode = showAuxiliarySubtypes
                ? SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES
                : SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES;
        IInputMethodManagerGlobalInvoker.showInputMethodPickerFromSystem(mode, displayId);
    }

    @GuardedBy("mH")
    private void showInputMethodPickerLocked() {
        IInputMethodManagerGlobalInvoker.showInputMethodPickerFromClient(mClient,
                SHOW_IM_PICKER_MODE_AUTO);
    }

    /**
     * A test API for CTS to make sure that {@link #showInputMethodPicker()} works as expected.
     *
     * <p>When customizing the implementation of {@link #showInputMethodPicker()} API, make sure
     * that this test API returns when and only while and only while
     * {@link #showInputMethodPicker()} is showing UI. Otherwise your OS implementation may not
     * pass CTS.</p>
     *
     * @return {@code true} while and only while {@link #showInputMethodPicker()} is showing UI.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isInputMethodPickerShown() {
        return IInputMethodManagerGlobalInvoker.isInputMethodPickerShownForTest();
    }

    /**
     * A test API for CTS to check whether there are any pending IME visibility requests.
     *
     * @return {@code true} iff there are pending IME visibility requests.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.TEST_INPUT_METHOD)
    public boolean hasPendingImeVisibilityRequests() {
        return IInputMethodManagerGlobalInvoker.hasPendingImeVisibilityRequests();
    }

    /**
     * Show the settings for enabling subtypes of the specified input method.
     *
     * @param imiId An input method, whose subtypes settings will be shown. If imiId is null,
     * subtypes of all input methods will be shown.
     */
    public void showInputMethodAndSubtypeEnabler(@Nullable String imiId) {
        Context context = null;
        synchronized (mH) {
            if (mCurRootView != null) {
                context = mCurRootView.mContext;
            }
        }
        if (context == null) {
            final Context appContext = ActivityThread.currentApplication();
            final DisplayManager displayManager = appContext.getSystemService(DisplayManager.class);
            context = appContext.createDisplayContext(displayManager.getDisplay(mDisplayId));
        }

        final Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!TextUtils.isEmpty(imiId)) {
            intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, imiId);
        }
        context.startActivity(intent);
    }

    /**
     * Returns the current input method subtype. This subtype is one of the subtypes in
     * the current input method. This method returns null when the current input method doesn't
     * have any input method subtype.
     */
    @Nullable
    public InputMethodSubtype getCurrentInputMethodSubtype() {
        return IInputMethodManagerGlobalInvoker.getCurrentInputMethodSubtype(UserHandle.myUserId());
    }

    /**
     * Switch to a new input method subtype of the current input method.
     * @param subtype A new input method subtype to switch.
     * @return true if the current subtype was successfully switched. When the specified subtype is
     * null, this method returns false.
     * @deprecated If the calling process is an IME, use
     *             {@link InputMethodService#switchInputMethod(String, InputMethodSubtype)}, which
     *             does not require any permission as long as the caller is the current IME.
     *             If the calling process is some privileged app that already has
     *             {@link Manifest.permission#WRITE_SECURE_SETTINGS} permission, just
     *             directly update {@link Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE}.
     */
    @Deprecated
    @RequiresPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    public boolean setCurrentInputMethodSubtype(InputMethodSubtype subtype) {
        if (Process.myUid() == Process.SYSTEM_UID) {
            Log.w(TAG, "System process should not call setCurrentInputMethodSubtype() because "
                    + "almost always it is a bug under multi-user / multi-profile environment. "
                    + "Consider directly interacting with InputMethodManagerService "
                    + "via LocalServices.");
            return false;
        }
        if (subtype == null) {
            // See the JavaDoc. This is how this method has worked.
            return false;
        }
        final Context fallbackContext = ActivityThread.currentApplication();
        if (fallbackContext == null) {
            return false;
        }
        if (fallbackContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        final ContentResolver contentResolver = fallbackContext.getContentResolver();
        final String imeId = Settings.Secure.getString(contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ComponentName.unflattenFromString(imeId) == null) {
            // Null or invalid IME ID format.
            return false;
        }
        final List<InputMethodSubtype> enabledSubtypes =
                IInputMethodManagerGlobalInvoker.getEnabledInputMethodSubtypeList(imeId, true,
                        UserHandle.myUserId());
        final int numSubtypes = enabledSubtypes.size();
        for (int i = 0; i < numSubtypes; ++i) {
            final InputMethodSubtype enabledSubtype = enabledSubtypes.get(i);
            if (enabledSubtype.equals(subtype)) {
                Settings.Secure.putInt(contentResolver,
                        Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE, enabledSubtype.hashCode());
                return true;
            }
        }
        return false;
    }

    /**
     * Notify that a user took some action with this input method.
     *
     * @deprecated Just kept to avoid possible app compat issue.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(trackingBug = 114740982, maxTargetSdk = Build.VERSION_CODES.P)
    public void notifyUserAction() {
        Log.w(TAG, "notifyUserAction() is a hidden method, which is now just a stub method"
                + " that does nothing.  Leave comments in b.android.com/114740982 if your "
                + " application still depends on the previous behavior of this method.");
    }

    /**
     * Returns a map of all shortcut input method info and their subtypes.
     */
    public Map<InputMethodInfo, List<InputMethodSubtype>> getShortcutInputMethodsAndSubtypes() {
        final List<InputMethodInfo> enabledImes = getEnabledInputMethodList();

        // Ensure we check system IMEs first.
        enabledImes.sort(Comparator.comparingInt(imi -> imi.isSystem() ? 0 : 1));

        final int numEnabledImes = enabledImes.size();
        for (int imiIndex = 0; imiIndex < numEnabledImes; ++imiIndex) {
            final InputMethodInfo imi = enabledImes.get(imiIndex);
            final List<InputMethodSubtype> subtypes = getEnabledInputMethodSubtypeList(
                    imi, true);
            final int subtypeCount = subtypes.size();
            for (int subtypeIndex = 0; subtypeIndex < subtypeCount; ++subtypeIndex) {
                final InputMethodSubtype subtype = imi.getSubtypeAt(subtypeIndex);
                if (SUBTYPE_MODE_VOICE.equals(subtype.getMode())) {
                    return Collections.singletonMap(imi, Collections.singletonList(subtype));
                }
            }
        }
        return Collections.emptyMap();
    }

    /**
     * This is kept due to {@link android.compat.annotation.UnsupportedAppUsage}.
     *
     * <p>TODO(Bug 113914148): Check if we can remove this.  We have accidentally exposed
     * WindowManagerInternal#getInputMethodWindowVisibleHeight to app developers and some of them
     * started relying on it.</p>
     *
     * @return Something that is not well-defined.
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 204906124, maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link android.view.WindowInsets} instead")
    public int getInputMethodWindowVisibleHeight() {
        return IInputMethodManagerGlobalInvoker.getInputMethodWindowVisibleHeight(mClient);
    }

    /**
     * {@code true} means that
     * {@link RemoteInputConnectionImpl#requestCursorUpdatesInternal(int, int, int)} returns
     * {@code false} when the IME client and the IME run in different displays.
     */
    final AtomicBoolean mRequestCursorUpdateDisplayIdCheck = new AtomicBoolean(true);

    /**
     * Controls the display ID mismatch validation in
     * {@link RemoteInputConnectionImpl#requestCursorUpdatesInternal(int, int, int)}.
     *
     * <p>{@link #updateCursorAnchorInfo(View, CursorAnchorInfo)} is not guaranteed to work
     * correctly when the IME client and the IME run in different displays.  This is why
     * {@link RemoteInputConnectionImpl#requestCursorUpdatesInternal(int, int, int)} returns
     * {@code false} by default when the display ID does not match. This method allows special apps
     * to override this behavior when they are sure that it should work.</p>
     *
     * <p>By default the validation is enabled.</p>
     *
     * @param enabled {@code false} to disable the display ID validation.
     * @hide
     */
    public void setRequestCursorUpdateDisplayIdCheck(boolean enabled) {
        mRequestCursorUpdateDisplayIdCheck.set(enabled);
    }

    /**
     * Force switch to the last used input method and subtype. If the last input method didn't have
     * any subtypes, the framework will simply switch to the last input method with no subtype
     * specified.
     * @param imeToken Supplies the identifying token given to an input method when it was started,
     * which allows it to perform this operation on itself.
     * @return true if the current input method and subtype was successfully switched to the last
     * used input method and subtype.
     * @deprecated Use {@link InputMethodService#switchToPreviousInputMethod()} instead. This method
     * was intended for IME developers who should be accessing APIs through the service. APIs in
     * this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public boolean switchToLastInputMethod(IBinder imeToken) {
        return InputMethodPrivilegedOperationsRegistry.get(imeToken).switchToPreviousInputMethod();
    }

    /**
     * Force switch to the next input method and subtype. If there is no IME enabled except
     * current IME and subtype, do nothing.
     * @param imeToken Supplies the identifying token given to an input method when it was started,
     * which allows it to perform this operation on itself.
     * @param onlyCurrentIme if true, the framework will find the next subtype which
     * belongs to the current IME
     * @return true if the current input method and subtype was successfully switched to the next
     * input method and subtype.
     * @deprecated Use {@link InputMethodService#switchToNextInputMethod(boolean)} instead. This
     * method was intended for IME developers who should be accessing APIs through the service.
     * APIs in this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public boolean switchToNextInputMethod(IBinder imeToken, boolean onlyCurrentIme) {
        return InputMethodPrivilegedOperationsRegistry.get(imeToken)
                .switchToNextInputMethod(onlyCurrentIme);
    }

    /**
     * Returns true if the current IME needs to offer the users ways to switch to a next input
     * method (e.g. a globe key.).
     * When an IME sets supportsSwitchingToNextInputMethod and this method returns true,
     * the IME has to offer ways to to invoke {@link #switchToNextInputMethod} accordingly.
     * <p> Note that the system determines the most appropriate next input method
     * and subtype in order to provide the consistent user experience in switching
     * between IMEs and subtypes.
     * @param imeToken Supplies the identifying token given to an input method when it was started,
     * which allows it to perform this operation on itself.
     * @deprecated Use {@link InputMethodService#shouldOfferSwitchingToNextInputMethod()}
     * instead. This method was intended for IME developers who should be accessing APIs through
     * the service. APIs in this class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public boolean shouldOfferSwitchingToNextInputMethod(IBinder imeToken) {
        return InputMethodPrivilegedOperationsRegistry.get(imeToken)
                .shouldOfferSwitchingToNextInputMethod();
    }

    /**
     * Set additional input method subtypes. Only a process which shares the same uid with the IME
     * can add additional input method subtypes to the IME.
     * Please note that a subtype's status is stored in the system.
     * For example, enabled subtypes are remembered by the framework even after they are removed
     * by using this method. If you re-add the same subtypes again,
     * they will just get enabled. If you want to avoid such conflicts, for instance, you may
     * want to create a "different" new subtype even with the same locale and mode,
     * by changing its extra value. The different subtype won't get affected by the stored past
     * status. (You may want to take a look at {@link InputMethodSubtype#hashCode()} to refer
     * to the current implementation.)
     *
     * <p>NOTE: If the same subtype exists in both the manifest XML file and additional subtypes
     * specified by {@code subtypes}, those multiple instances are automatically merged into one
     * instance.</p>
     *
     * <p>CAVEAT: In API Level 23 and prior, the system may do nothing if an empty
     * {@link InputMethodSubtype} is specified in {@code subtypes}, which prevents you from removing
     * the last one entry of additional subtypes. If your IME statically defines one or more
     * subtypes in the manifest XML file, you may be able to work around this limitation by
     * specifying one of those statically defined subtypes in {@code subtypes}.</p>
     *
     * @param imiId Id of InputMethodInfo which additional input method subtypes will be added to.
     * If the imiId is {@code null}, system would do nothing for this operation.
     * @param subtypes subtypes will be added as additional subtypes of the current input method.
     * If the subtypes is {@code null}, system would do nothing for this operation.
     * @deprecated For IMEs that have already implemented features like customizable/downloadable
     *             keyboard layouts/languages, please start migration to other approaches. One idea
     *             would be exposing only one unified {@link InputMethodSubtype} then implement
     *             IME's own language switching mechanism within that unified subtype. The support
     *             of "Additional Subtype" may be completely dropped in a future version of Android.
     */
    @Deprecated
    public void setAdditionalInputMethodSubtypes(@NonNull String imiId,
            @NonNull InputMethodSubtype[] subtypes) {
        IInputMethodManagerGlobalInvoker.setAdditionalInputMethodSubtypes(imiId, subtypes,
                UserHandle.myUserId());
    }

    /**
     * Updates the list of explicitly enabled {@link InputMethodSubtype} for a given IME owned by
     * the calling process.
     *
     * <p>By default each IME has no explicitly enabled {@link InputMethodSubtype}.  In this state
     * the system will decide what {@link InputMethodSubtype} should be enabled by using information
     * available at runtime as per-user language settings.  Users can, however, manually pick up one
     * or more {@link InputMethodSubtype} to be enabled on an Activity shown by
     * {@link #showInputMethodAndSubtypeEnabler(String)}. Such a manual change is stored in
     * {@link Settings.Secure#ENABLED_INPUT_METHODS} so that the change can persist across reboots.
     * {@link Settings.Secure#ENABLED_INPUT_METHODS} stores {@link InputMethodSubtype#hashCode()} as
     * the identifier of {@link InputMethodSubtype} for historical reasons.</p>
     *
     * <p>This API provides a safe and managed way for IME developers to modify what
     * {@link InputMethodSubtype} are referenced in {@link Settings.Secure#ENABLED_INPUT_METHODS}
     * for their own IME.  One use case is when IME developers want to use their own Activity for
     * users to pick up {@link InputMethodSubtype}. Another use case is for IME developers to fix up
     * any stale and/or invalid value stored in {@link Settings.Secure#ENABLED_INPUT_METHODS}
     * without bothering users. Passing an empty {@code subtypeHashCodes} is guaranteed to reset
     * the state to default.</p>
     *
     * <h3>To control the return value of {@link InputMethodSubtype#hashCode()}</h3>
     * <p>{@link android.R.attr#subtypeId} and {@link
     * android.view.inputmethod.InputMethodSubtype.InputMethodSubtypeBuilder#setSubtypeId(int)} are
     * available for IME developers to control the return value of
     * {@link InputMethodSubtype#hashCode()}. Beware that {@code -1} is not a valid value of
     * {@link InputMethodSubtype#hashCode()} for historical reasons.</p>
     *
     * <h3>Note for Direct Boot support</h3>
     * <p>While IME developers can call this API even before
     * {@link android.os.UserManager#isUserUnlocked()} becomes {@code true}, such a change is
     * volatile thus remains effective only until {@link android.os.UserManager#isUserUnlocked()}
     * becomes {@code true} or the device is rebooted. To make the change persistent IME developers
     * need to call this API again after receiving {@link Intent#ACTION_USER_UNLOCKED}.</p>
     *
     * @param imiId IME ID. The specified IME and the calling process need to belong to the same
     *              package.  Otherwise {@link SecurityException} will be thrown.
     * @param subtypeHashCodes An arrays of {@link InputMethodSubtype#hashCode()} to be explicitly
     *                         enabled. Entries that are found in the specified IME will be silently
     *                         ignored. Pass an empty array to reset the state to default.
     * @throws NullPointerException if {@code subtypeHashCodes} is {@code null}.
     * @throws SecurityException if the specified IME and the calling process do not belong to the
     *                           same package.
     */
    public void setExplicitlyEnabledInputMethodSubtypes(@NonNull String imiId,
            @NonNull int[] subtypeHashCodes) {
        IInputMethodManagerGlobalInvoker.setExplicitlyEnabledInputMethodSubtypes(imiId,
                subtypeHashCodes, UserHandle.myUserId());
    }

    /**
     * Returns the last used {@link InputMethodSubtype} in system history.
     *
     * @return the last {@link InputMethodSubtype}, {@code null} if last IME have no subtype.
     */
    @Nullable
    public InputMethodSubtype getLastInputMethodSubtype() {
        return IInputMethodManagerGlobalInvoker.getLastInputMethodSubtype(UserHandle.myUserId());
    }

    /**
     * <p>This is used for CTS test only. Do not use this method outside of CTS package.<p/>
     * @return the ID of this display which this {@link InputMethodManager} resides
     * @hide
     */
    @TestApi
    public int getDisplayId() {
        return mDisplayId;
    }

    private void doDump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (processDump(fd, args)) {
            return;
        }

        final Printer p = new PrintWriterPrinter(fout);
        p.println("Input method client state for " + this + ":");
        p.println("  mFallbackInputConnection=" + mFallbackInputConnection);
        p.println("  mActive=" + mActive
                + " mRestartOnNextWindowFocus=" + mRestartOnNextWindowFocus
                + " mBindSequence=" + getBindSequenceLocked()
                + " mCurImeId=" + getImeIdLocked());
        p.println("  mFullscreenMode=" + mFullscreenMode);
        if (isImeSessionAvailableLocked()) {
            p.println("  mCurMethod=" + mCurBindState.mImeSession);
        } else {
            p.println("  mCurMethod= null");
        }
        for (int i = 0; i < mAccessibilityInputMethodSession.size(); i++) {
            p.println("  mAccessibilityInputMethodSession("
                    + mAccessibilityInputMethodSession.keyAt(i) + ")="
                    + mAccessibilityInputMethodSession.valueAt(i));
        }
        p.println("  mCurRootView=" + mCurRootView);
        p.println("  mServedView=" + getServedViewLocked());
        p.println("  mNextServedView=" + getNextServedViewLocked());
        p.println("  mServedConnecting=" + mServedConnecting);
        if (mCurrentEditorInfo != null) {
            p.println("  mCurrentEditorInfo:");
            mCurrentEditorInfo.dump(p, "    ", false /* dumpExtras */);
        } else {
            p.println("  mCurrentEditorInfo: null");
        }
        p.println("  mServedInputConnection=" + mServedInputConnection);
        p.println("  mServedInputConnectionHandler=" + mServedInputConnectionHandler);
        p.println("  mCompletions=" + Arrays.toString(mCompletions));
        p.println("  mCursorRect=" + mCursorRect);
        p.println("  mCursorSelStart=" + mCursorSelStart
                + " mCursorSelEnd=" + mCursorSelEnd
                + " mCursorCandStart=" + mCursorCandStart
                + " mCursorCandEnd=" + mCursorCandEnd);
    }

    /**
     * Callback that is invoked when an input event that was dispatched to
     * the IME has been finished.
     * @hide
     */
    public interface FinishedInputEventCallback {
        public void onFinishedInputEvent(Object token, boolean handled);
    }

    private final class ImeInputEventSender extends InputEventSender {
        public ImeInputEventSender(InputChannel inputChannel, Looper looper) {
            super(inputChannel, looper);
        }

        @Override
        public void onInputEventFinished(int seq, boolean handled) {
            finishedInputEvent(seq, handled, false);
        }
    }

    private final class PendingEvent implements Runnable {
        public InputEvent mEvent;
        public Object mToken;
        public String mInputMethodId;
        public FinishedInputEventCallback mCallback;
        public Handler mHandler;
        public boolean mHandled;

        public void recycle() {
            mEvent = null;
            mToken = null;
            mInputMethodId = null;
            mCallback = null;
            mHandler = null;
            mHandled = false;
        }

        @Override
        public void run() {
            mCallback.onFinishedInputEvent(mToken, mHandled);

            synchronized (mH) {
                recyclePendingEventLocked(this);
            }
        }
    }

    private static final class BindState {
        /**
         * Encapsulates IPCs to the currently connected InputMethodService.
         */
        @Nullable
        final IInputMethodSessionInvoker mImeSession;

        /**
         * As reported by {@link InputBindResult}. This value is determined by
         * {@link com.android.internal.R.styleable#InputMethod_suppressesSpellChecker}.
         */
        final boolean mIsInputMethodSuppressingSpellChecker;

        /**
         * As reported by {@link InputBindResult}. This value indicates the bound input method ID.
         */
        @Nullable
        final String mImeId;

        /**
         * Sequence number of this binding, as returned by the server.
         */
        final int mBindSequence;

        BindState(@NonNull InputBindResult inputBindResult) {
            mImeSession = IInputMethodSessionInvoker.createOrNull(inputBindResult.method);
            mIsInputMethodSuppressingSpellChecker =
                    inputBindResult.isInputMethodSuppressingSpellChecker;
            mImeId = inputBindResult.id;
            mBindSequence = inputBindResult.sequence;
        }
    }

    @GuardedBy("mH")
    private boolean isImeSessionAvailableLocked() {
        return mCurBindState != null && mCurBindState.mImeSession != null;
    }

    @GuardedBy("mH")
    private String getImeIdLocked() {
        return mCurBindState != null ? mCurBindState.mImeId : null;
    }

    @GuardedBy("mH")
    private int getBindSequenceLocked() {
        return mCurBindState != null ? mCurBindState.mBindSequence : -1;
    }

    /**
     * Checks the args to see if a proto-based ime dump was requested and writes the client side
     * ime dump to the given {@link FileDescriptor}.
     *
     * @return {@code true} if a proto-based ime dump was requested.
     */
    private boolean processDump(final FileDescriptor fd, final String[] args) {
        if (args == null) {
            return false;
        }

        for (String arg : args) {
            if (arg.equals(ImeTracing.PROTO_ARG)) {
                final ProtoOutputStream proto = new ProtoOutputStream(fd);
                dumpDebug(proto, null /* icProto */);
                proto.flush();
                return true;
            }
        }
        return false;
    }

    /**
     * Write the proto dump of various client side components to the provided
     * {@link ProtoOutputStream}.
     *
     * @param proto The proto stream to which the dumps are written.
     * @param icProto {@link InputConnection} call data in proto format.
     * @hide
     */
    public void dumpDebug(ProtoOutputStream proto, @Nullable byte[] icProto) {
        synchronized (mH) {
            if (!isImeSessionAvailableLocked()) {
                return;
            }

            proto.write(DISPLAY_ID, mDisplayId);
            final long token = proto.start(INPUT_METHOD_MANAGER);
            proto.write(CUR_ID, mCurBindState.mImeId);
            proto.write(FULLSCREEN_MODE, mFullscreenMode);
            proto.write(ACTIVE, mActive);
            proto.write(SERVED_CONNECTING, mServedConnecting);
            proto.write(SERVED_VIEW, Objects.toString(mServedView));
            proto.write(NEXT_SERVED_VIEW, Objects.toString(mNextServedView));
            proto.end(token);
            if (mCurRootView != null) {
                mCurRootView.dumpDebug(proto, VIEW_ROOT_IMPL);
            }
            if (mCurrentEditorInfo != null) {
                mCurrentEditorInfo.dumpDebug(proto, EDITOR_INFO);
            }
            if (mImeInsetsConsumer != null) {
                mImeInsetsConsumer.dumpDebug(proto, IME_INSETS_SOURCE_CONSUMER);
            }
            if (mServedInputConnection != null) {
                mServedInputConnection.dumpDebug(proto, INPUT_CONNECTION);
            }
            if (icProto != null) {
                proto.write(INPUT_CONNECTION_CALL, icProto);
            }
        }
    }

    @GuardedBy("mH")
    private void forAccessibilitySessionsLocked(
            Consumer<IAccessibilityInputMethodSessionInvoker> consumer) {
        for (int i = 0; i < mAccessibilityInputMethodSession.size(); i++) {
            consumer.accept(mAccessibilityInputMethodSession.valueAt(i));
        }
    }

    @UiThread
    private static Pair<InputConnection, EditorInfo> createInputConnection(
            @NonNull View servedView) {
        final EditorInfo editorInfo = new EditorInfo();
        // Note: Use Context#getOpPackageName() rather than Context#getPackageName() so that the
        // system can verify the consistency between the uid of this process and package name passed
        // from here. See comment of Context#getOpPackageName() for details.
        editorInfo.packageName = servedView.getContext().getOpPackageName();
        editorInfo.autofillId = servedView.getAutofillId();
        editorInfo.fieldId = servedView.getId();
        final InputConnection ic = servedView.onCreateInputConnection(editorInfo);
        if (DEBUG) Log.v(TAG, "Starting input: editorInfo=" + editorInfo + " ic=" + ic);

        // Clear autofill and field ids if a connection could not be established.
        // This ensures that even disconnected EditorInfos have well-defined attributes,
        // making them consistently and straightforwardly comparable.
        if (ic == null) {
            editorInfo.autofillId = AutofillId.NO_AUTOFILL_ID;
            editorInfo.fieldId = 0;
        }
        return new Pair<>(ic, editorInfo);
    }
}
