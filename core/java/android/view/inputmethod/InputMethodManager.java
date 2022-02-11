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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.util.imetracing.ImeTracing.PROTO_ARG;
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
import static android.view.inputmethod.InputMethodManagerProto.SERVED_CONNECTING;

import static com.android.internal.inputmethod.StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION;
import static com.android.internal.inputmethod.StartInputReason.WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION;

import android.annotation.DisplayContext;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.inputmethodservice.InputMethodService;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;
import android.os.Trace;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.util.Pools.Pool;
import android.util.Pools.SimplePool;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.SparseArray;
import android.util.imetracing.ImeTracing;
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
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.autofill.AutofillManager;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.InputMethodPrivilegedOperationsRegistry;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.os.SomeArgs;
import com.android.internal.view.IInputConnectionWrapper;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 */
@SystemService(Context.INPUT_METHOD_SERVICE)
@RequiresFeature(PackageManager.FEATURE_INPUT_METHODS)
public final class InputMethodManager {
    static final boolean DEBUG = false;
    static final String TAG = "InputMethodManager";

    static final String PENDING_EVENT_COUNTER = "aq:imm";

    private static final int NOT_A_SUBTYPE_ID = -1;

    /**
     * A constant that represents Voice IME.
     *
     * @see InputMethodSubtype#getMode()
     */
    private static final String SUBTYPE_MODE_VOICE = "voice";

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
    static final long INPUT_METHOD_NOT_RESPONDING_TIMEOUT = 2500;

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

    @UnsupportedAppUsage
    final IInputMethodManager mService;
    final Looper mMainLooper;

    // For scheduling work on the main thread.  This also serves as our
    // global lock.
    // Remark on @UnsupportedAppUsage: there were context leaks on old versions
    // of android (b/37043700), so developers used this field to perform manual clean up.
    // Leaks were fixed, hacks were backported to AppCompatActivity,
    // so an access to the field is closed.
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    final H mH;

    // Our generic input connection if the current target does not have its own.
    final IInputContext mIInputContext;

    private final int mDisplayId;

    /**
     * True if this input method client is active, initially false.
     */
    boolean mActive = false;

    /**
     * {@code true} if next {@link ImeFocusController#onPostWindowFocus} needs to
     * restart input.
     */
    private boolean mRestartOnNextWindowFocus = true;

    /**
     * As reported by IME through InputConnection.
     */
    boolean mFullscreenMode;

    // -----------------------------------------------------------

    /**
     * This is the root view of the overall window that currently has input
     * method focus.
     */
    @GuardedBy("mH")
    ViewRootImpl mCurRootView;
    /**
     * This is set when we are in the process of connecting, to determine
     * when we have actually finished.
     */
    boolean mServedConnecting;
    /**
     * This is non-null when we have connected the served view; it holds
     * the attributes that were last retrieved from the served view and given
     * to the input connection.
     */
    EditorInfo mCurrentTextBoxAttribute;
    /**
     * The InputConnection that was last retrieved from the served view.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    IInputConnectionWrapper mServedInputConnectionWrapper;
    /**
     * The completions that were last provided by the served view.
     */
    CompletionInfo[] mCompletions;

    // Cursor position on the screen.
    @UnsupportedAppUsage
    Rect mTmpCursorRect = new Rect();
    @UnsupportedAppUsage
    Rect mCursorRect = new Rect();
    int mCursorSelStart;
    int mCursorSelEnd;
    int mCursorCandStart;
    int mCursorCandEnd;

    /**
     * The instance that has previously been sent to the input method.
     */
    private CursorAnchorInfo mCursorAnchorInfo = null;

    /**
     * As reported by {@link InputBindResult}. This value is determined by
     * {@link com.android.internal.R.styleable#InputMethod_suppressesSpellChecking}.
     */
    @GuardedBy("mH")
    private boolean mIsInputMethodSuppressingSpellChecker = false;

    // -----------------------------------------------------------

    /**
     * Sequence number of this binding, as returned by the server.
     */
    int mBindSequence = -1;
    /**
     * ID of the method we are bound to.
     */
    @UnsupportedAppUsage
    String mCurId;

    /**
     * Kept for {@link UnsupportedAppUsage}.  Not officially maintained.
     *
     * @deprecated New code should use {@link #mCurrentInputMethodSession}.
     */
    @Deprecated
    @GuardedBy("mH")
    @Nullable
    @UnsupportedAppUsage
    IInputMethodSession mCurMethod;

    /**
     * Encapsulates IPCs to the currently connected InputMethodService.
     */
    @Nullable
    @GuardedBy("mH")
    private InputMethodSessionWrapper mCurrentInputMethodSession = null;

    InputChannel mCurChannel;
    ImeInputEventSender mCurSender;

    private static final int REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE = 0x0;

    /**
     * The monitor mode for {@link #updateCursorAnchorInfo(View, CursorAnchorInfo)}.
     */
    private int mRequestUpdateCursorAnchorInfoMonitorMode = REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE;

    /**
     * Applies the IME visibility and listens for other state changes.
     */
    private ImeInsetsSourceConsumer mImeInsetsConsumer;

    final Pool<PendingEvent> mPendingEventPool = new SimplePool<>(20);
    final SparseArray<PendingEvent> mPendingEvents = new SparseArray<>(20);

    final DelegateImpl mDelegate = new DelegateImpl();

    // -----------------------------------------------------------

    static final int MSG_DUMP = 1;
    static final int MSG_BIND = 2;
    static final int MSG_UNBIND = 3;
    static final int MSG_SET_ACTIVE = 4;
    static final int MSG_SEND_INPUT_EVENT = 5;
    static final int MSG_TIMEOUT_INPUT_EVENT = 6;
    static final int MSG_FLUSH_INPUT_EVENT = 7;
    static final int MSG_REPORT_FULLSCREEN_MODE = 10;

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
    public void reportPerceptible(IBinder windowToken, boolean perceptible) {
        try {
            mService.reportPerceptibleAsync(windowToken, perceptible);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final class DelegateImpl implements
            ImeFocusController.InputMethodManagerDelegate {
        /**
         * Used by {@link ImeFocusController} to start input connection.
         */
        @Override
        public boolean startInput(@StartInputReason int startInputReason, View focusedView,
                @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
                int windowFlags) {
            final View servedView;
            ImeTracing.getInstance().triggerClientDump(
                    "InputMethodManager.DelegateImpl#startInput", InputMethodManager.this,
                    null /* icProto */);
            synchronized (mH) {
                mCurrentTextBoxAttribute = null;
                mCompletions = null;
                mServedConnecting = true;
                servedView = getServedViewLocked();
            }
            return startInputInner(startInputReason,
                    focusedView != null ? focusedView.getWindowToken() : null, startInputFlags,
                    softInputMode, windowFlags);
        }

        /**
         * Used by {@link ImeFocusController} to finish input connection.
         */
        @Override
        public void finishInput() {
            ImeTracing.getInstance().triggerClientDump(
                    "InputMethodManager.DelegateImpl#finishInput", InputMethodManager.this,
                    null /* icProto */);
            synchronized (mH) {
                finishInputLocked();
            }
        }

        /**
         * Used by {@link ImeFocusController} to finish input connection and callback
         * {@link InputMethodService#onFinishInput()}.
         *
         * This method is especially for when ImeFocusController received device screen-off event to
         * ensure the entire finish input connection and the connection lifecycle callback to
         * IME can be done for security concern.
         */
        @Override
        public void finishInputAndReportToIme() {
            synchronized (mH) {
                finishInputLocked();
                if (mCurrentInputMethodSession != null) {
                    mCurrentInputMethodSession.finishInput();
                }
            }
        }

        /**
         * Used by {@link ImeFocusController} to hide current input method editor.
         */
        @Override
        public void closeCurrentIme() {
            closeCurrentInput();
        }

        /**
         * For {@link ImeFocusController} to start input asynchronously when focus gain.
         */
        @Override
        public void startInputAsyncOnWindowFocusGain(View focusedView,
                @SoftInputModeFlags int softInputMode, int windowFlags, boolean forceNewFocus) {
            int startInputFlags = getStartInputFlags(focusedView, 0);
            startInputFlags |= StartInputFlags.WINDOW_GAINED_FOCUS;

            ImeTracing.getInstance().triggerClientDump(
                    "InputMethodManager.DelegateImpl#startInputAsyncOnWindowFocusGain",
                    InputMethodManager.this, null /* icProto */);

            final ImeFocusController controller = getFocusController();
            if (controller == null) {
                return;
            }
            if (controller.checkFocus(forceNewFocus, false)) {
                // We need to restart input on the current focus view.  This
                // should be done in conjunction with telling the system service
                // about the window gaining focus, to help make the transition
                // smooth.
                if (startInput(StartInputReason.WINDOW_FOCUS_GAIN,
                        focusedView, startInputFlags, softInputMode, windowFlags)) {
                    return;
                }
            }

            synchronized (mH) {
                // For some reason we didn't do a startInput + windowFocusGain, so
                // we'll just do a window focus gain and call it a day.
                try {
                    View servedView = controller.getServedView();
                    boolean nextFocusHasConnection = servedView != null && servedView == focusedView
                            && hasActiveConnection(focusedView);
                    if (DEBUG) {
                        Log.v(TAG, "Reporting focus gain, without startInput"
                                + ", nextFocusIsServedView=" + nextFocusHasConnection);
                    }

                    final int startInputReason = nextFocusHasConnection
                            ? WINDOW_FOCUS_GAIN_REPORT_WITH_CONNECTION
                            : WINDOW_FOCUS_GAIN_REPORT_WITHOUT_CONNECTION;
                    // ignore the result
                    mService.startInputOrWindowGainedFocus(
                            startInputReason, mClient,
                            focusedView.getWindowToken(), startInputFlags, softInputMode,
                            windowFlags,
                            null,
                            null,
                            0 /* missingMethodFlags */,
                            mCurRootView.mContext.getApplicationInfo().targetSdkVersion);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }

        /**
         * Used by {@link ImeFocusController} to finish current composing text.
         */
        @Override
        public void finishComposingText() {
            if (mServedInputConnectionWrapper != null) {
                mServedInputConnectionWrapper.finishComposingText();
            }
        }

        /**
         * Used for {@link ImeFocusController} to set the current focused root view.
         */
        @Override
        public void setCurrentRootView(ViewRootImpl rootView) {
            synchronized (mH) {
                mCurRootView = rootView;
            }
        }

        /**
         * Used for {@link ImeFocusController} to return if the root view from the
         * controller is this {@link InputMethodManager} currently focused.
         * TODO: Address event-order problem when get current root view in multi-threads.
         */
        @Override
        public boolean isCurrentRootView(ViewRootImpl rootView) {
            synchronized (mH) {
                return mCurRootView == rootView;
            }
        }

        /**
         * For {@link ImeFocusController#checkFocus} if needed to force check new focus.
         */
        @Override
        public boolean isRestartOnNextWindowFocus(boolean reset) {
            final boolean result = mRestartOnNextWindowFocus;
            if (reset) {
                mRestartOnNextWindowFocus = false;
            }
            return result;
        }

        /**
         * Checks whether the active input connection (if any) is for the given view.
         *
         * TODO(b/182259171): Clean-up hasActiveConnection to simplify the logic.
         *
         * Note that this method is only intended for restarting input after focus gain
         * (e.g. b/160391516), DO NOT leverage this method to do another check.
         */
        @Override
        public boolean hasActiveConnection(View view) {
            synchronized (mH) {
                if (!hasServedByInputMethodLocked(view)
                        || mCurrentInputMethodSession == null) {
                    return false;
                }

                return mServedInputConnectionWrapper != null
                        && mServedInputConnectionWrapper.isActive()
                        && mServedInputConnectionWrapper.getServedView() == view;
            }
        }
    }

    /** @hide */
    public DelegateImpl getDelegate() {
        return mDelegate;
    }

    /**
     * Checks whether the active input connection (if any) is for the given view.
     *
     * @hide
     * @see ImeFocusController#getImmDelegate()#hasActiveInputConnection(View)
     */
    @TestApi
    public boolean hasActiveInputConnection(@Nullable View view) {
        return mDelegate.hasActiveConnection(view);
    }

    private View getServedViewLocked() {
        return mCurRootView != null ? mCurRootView.getImeFocusController().getServedView() : null;
    }

    private View getNextServedViewLocked() {
        return mCurRootView != null ? mCurRootView.getImeFocusController().getNextServedView()
                : null;
    }

    private void setServedViewLocked(View view) {
        if (mCurRootView != null) {
            mCurRootView.getImeFocusController().setServedView(view);
        }
    }

    private void setNextServedViewLocked(View view) {
        if (mCurRootView != null) {
            mCurRootView.getImeFocusController().setNextServedView(view);
        }
    }

    private ImeFocusController getFocusController() {
        synchronized (mH) {
            if (mCurRootView != null) {
                return mCurRootView.getImeFocusController();
            }
            return null;
        }
    }

    /**
     * Returns {@code true} when the given view has been served by Input Method.
     */
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
                        if (mBindSequence < 0 || mBindSequence != res.sequence) {
                            Log.w(TAG, "Ignoring onBind: cur seq=" + mBindSequence
                                    + ", given seq=" + res.sequence);
                            if (res.channel != null && res.channel != mCurChannel) {
                                res.channel.dispose();
                            }
                            return;
                        }

                        mRequestUpdateCursorAnchorInfoMonitorMode =
                                REQUEST_UPDATE_CURSOR_ANCHOR_INFO_NONE;

                        setInputChannelLocked(res.channel);
                        mCurMethod = res.method; // for @UnsupportedAppUsage
                        mCurrentInputMethodSession =
                                InputMethodSessionWrapper.createOrNull(res.method);
                        mCurId = res.id;
                        mBindSequence = res.sequence;
                        mIsInputMethodSuppressingSpellChecker =
                                res.isInputMethodSuppressingSpellChecker;
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
                        if (mBindSequence != sequence) {
                            return;
                        }
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
                case MSG_SET_ACTIVE: {
                    final boolean active = msg.arg1 != 0;
                    final boolean fullscreen = msg.arg2 != 0;
                    final boolean reportToImeController = msg.obj != null && (boolean) msg.obj;
                    if (DEBUG) {
                        Log.i(TAG, "handleMessage: MSG_SET_ACTIVE " + active + ", was " + mActive);
                    }
                    synchronized (mH) {
                        mActive = active;
                        mFullscreenMode = fullscreen;

                        // Report active state to ImeFocusController to handle IME input
                        // connection lifecycle callback when it allowed.
                        final ImeFocusController controller = getFocusController();
                        final View rootView = mCurRootView != null ? mCurRootView.getView() : null;
                        if (controller != null && rootView != null && reportToImeController) {
                            rootView.post(() -> controller.onInteractiveChanged(active));
                            return;
                        }

                        if (!active) {
                            // Some other client has starting using the IME, so note
                            // that this happened and make sure our own editor's
                            // state is reset.
                            mRestartOnNextWindowFocus = true;
                            try {
                                // Note that finishComposingText() is allowed to run
                                // even when we are not active.
                                mIInputContext.finishComposingText();
                            } catch (RemoteException e) {
                            }
                        }
                        // Check focus again in case that "onWindowFocus" is called before
                        // handling this message.
                        final View servedView = getServedViewLocked();
                        if (servedView != null && canStartInput(servedView)) {
                            if (mCurRootView != null && mCurRootView.getImeFocusController()
                                    .checkFocus(mRestartOnNextWindowFocus, false)) {
                                final int reason = active ? StartInputReason.ACTIVATED_BY_IMMS
                                        : StartInputReason.DEACTIVATED_BY_IMMS;
                                mDelegate.startInput(reason, null, 0, 0, 0);
                            }
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
                    InputConnection ic = null;
                    synchronized (mH) {
                        mFullscreenMode = fullscreen;
                        if (mServedInputConnectionWrapper != null) {
                            ic = mServedInputConnectionWrapper.getInputConnection();
                        }
                    }
                    if (ic != null) {
                        ic.reportFullscreenMode(fullscreen);
                    }
                    return;
                }
            }
        }
    }

    final IInputMethodClient.Stub mClient = new IInputMethodClient.Stub() {
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
        public void onUnbindMethod(int sequence, @UnbindReason int unbindReason) {
            mH.obtainMessage(MSG_UNBIND, sequence, unbindReason).sendToTarget();
        }

        @Override
        public void setActive(boolean active, boolean fullscreen, boolean reportToImeController) {
            mH.obtainMessage(MSG_SET_ACTIVE, active ? 1 : 0, fullscreen ? 1 : 0,
                    reportToImeController).sendToTarget();
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

    final InputConnection mDummyInputConnection = new BaseInputConnection(this, false);

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

    @NonNull
    private static InputMethodManager createInstance(int displayId, Looper looper) {
        return isInEditMode() ? createStubInstance(displayId, looper)
                : createRealInstance(displayId, looper);
    }

    @NonNull
    private static InputMethodManager createRealInstance(int displayId, Looper looper) {
        final IInputMethodManager service;
        try {
            service = IInputMethodManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.INPUT_METHOD_SERVICE));
        } catch (ServiceNotFoundException e) {
            throw new IllegalStateException(e);
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
            service.addClient(imm.mClient, imm.mIInputContext, displayId);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
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

    private InputMethodManager(IInputMethodManager service, int displayId, Looper looper) {
        mService = service;
        mMainLooper = looper;
        mH = new H(looper);
        mDisplayId = displayId;
        mIInputContext = new IInputConnectionWrapper(looper, mDummyInputConnection, this, null);
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

    /** @hide */
    @UnsupportedAppUsage
    public IInputMethodClient getClient() {
        return mClient;
    }

    /** @hide */
    @UnsupportedAppUsage
    public IInputContext getInputContext() {
        return mIInputContext;
    }

    /**
     * Returns the list of installed input methods.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @return {@link List} of {@link InputMethodInfo}.
     */
    public List<InputMethodInfo> getInputMethodList() {
        try {
            // We intentionally do not use UserHandle.getCallingUserId() here because for system
            // services InputMethodManagerInternal.getInputMethodListAsUser() should be used
            // instead.
            return mService.getInputMethodList(UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of installed input methods for the specified user.
     *
     * @param userId user ID to query
     * @return {@link List} of {@link InputMethodInfo}.
     * @hide
     */
    @TestApi
    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    @NonNull
    public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
        try {
            return mService.getInputMethodList(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of enabled input methods.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @return {@link List} of {@link InputMethodInfo}.
     */
    public List<InputMethodInfo> getEnabledInputMethodList() {
        try {
            // We intentionally do not use UserHandle.getCallingUserId() here because for system
            // services InputMethodManagerInternal.getEnabledInputMethodListAsUser() should be used
            // instead.
            return mService.getEnabledInputMethodList(UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the list of enabled input methods for the specified user.
     *
     * @param userId user ID to query
     * @return {@link List} of {@link InputMethodInfo}.
     * @hide
     */
    @RequiresPermission(INTERACT_ACROSS_USERS_FULL)
    public List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId) {
        try {
            return mService.getEnabledInputMethodList(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of enabled input method subtypes for the specified input method info.
     *
     * <p>On multi user environment, this API returns a result for the calling process user.</p>
     *
     * @param imi An input method info whose subtypes list will be returned.
     * @param allowsImplicitlySelectedSubtypes A boolean flag to allow to return the implicitly
     * selected subtypes. If an input method info doesn't have enabled subtypes, the framework
     * will implicitly enable subtypes according to the current system language.
     */
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo imi,
            boolean allowsImplicitlySelectedSubtypes) {
        try {
            return mService.getEnabledInputMethodSubtypeList(
                    imi == null ? null : imi.getId(),
                    allowsImplicitlySelectedSubtypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Return true if the given view is the currently active view for the
     * input method.
     */
    public boolean isActive(View view) {
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            return fallbackImm.isActive(view);
        }

        checkFocus();
        synchronized (mH) {
            return hasServedByInputMethodLocked(view) && mCurrentTextBoxAttribute != null;
        }
    }

    /**
     * Return true if any view is currently active in the input method.
     */
    public boolean isActive() {
        checkFocus();
        synchronized (mH) {
            return getServedViewLocked() != null && mCurrentTextBoxAttribute != null;
        }
    }

    /**
     * Return {@code true} if the currently served view is accepting full text edits.
     * If {@code false}, it has no input connection, so it can only handle raw key events.
     */
    public boolean isAcceptingText() {
        checkFocus();
        synchronized (mH) {
            return mServedInputConnectionWrapper != null
                    && mServedInputConnectionWrapper.getInputConnection() != null;
        }
    }

    /**
     * Return {@code true} if the input method is suppressing system spell checker.
     */
    public boolean isInputMethodSuppressingSpellChecker() {
        synchronized (mH) {
            return mIsInputMethodSuppressingSpellChecker;
        }
    }

    /**
     * Reset all of the state associated with being bound to an input method.
     */
    void clearBindingLocked() {
        if (DEBUG) Log.v(TAG, "Clearing binding!");
        clearConnectionLocked();
        setInputChannelLocked(null);
        mBindSequence = -1;
        mCurId = null;
        mCurMethod = null; // for @UnsupportedAppUsage
        mCurrentInputMethodSession = null;
    }

    void setInputChannelLocked(InputChannel channel) {
        if (mCurChannel == channel) {
            return;
        }
        if (mCurChannel != null && channel != null
                && mCurChannel.getToken() == channel.getToken()) {
            // channel is a dupe of 'mCurChannel', because they have the same token, and represent
            // the same connection. Ignore the incoming channel and keep using 'mCurChannel' to
            // avoid confusing the InputEventReceiver.
            return;
        }
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

    /**
     * Reset all of the state associated with a served view being connected
     * to an input method
     */
    void clearConnectionLocked() {
        mCurrentTextBoxAttribute = null;
        if (mServedInputConnectionWrapper != null) {
            mServedInputConnectionWrapper.deactivate();
            mServedInputConnectionWrapper = null;
        }
    }

    /**
     * Disconnect any existing input connection, clearing the served view.
     */
    @UnsupportedAppUsage
    void finishInputLocked() {
        mIsInputMethodSuppressingSpellChecker = false;
        setNextServedViewLocked(null);
        if (getServedViewLocked() != null) {
            if (DEBUG) {
                Log.v(TAG, "FINISH INPUT: mServedView="
                        + dumpViewInfo(getServedViewLocked()));
            }
            setServedViewLocked(null);
            mCompletions = null;
            mServedConnecting = false;
            clearConnectionLocked();
        }
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
            if (mCurrentInputMethodSession != null) {
                mCurrentInputMethodSession.displayCompletions(mCompletions);
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

            if (mCurrentInputMethodSession != null) {
                mCurrentInputMethodSession.updateExtractedText(token, text);
            }
        }
    }

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
     */
    public static final int SHOW_FORCED = 0x0002;

    /**
     * Synonym for {@link #showSoftInput(View, int, ResultReceiver)} without
     * a result receiver: explicitly request that the current input method's
     * soft input area be shown to the user, if needed.
     *
     * @param view The currently focused view, which would like to receive
     * soft keyboard input.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #SHOW_IMPLICIT} bit set.
     */
    public boolean showSoftInput(View view, int flags) {
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
     * @param view The currently focused view, which would like to receive
     * soft keyboard input.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #SHOW_IMPLICIT} bit set.
     * @param resultReceiver If non-null, this will be called by the IME when
     * it has processed your request to tell you what it has done.  The result
     * code you receive may be either {@link #RESULT_UNCHANGED_SHOWN},
     * {@link #RESULT_UNCHANGED_HIDDEN}, {@link #RESULT_SHOWN}, or
     * {@link #RESULT_HIDDEN}.
     */
    public boolean showSoftInput(View view, int flags, ResultReceiver resultReceiver) {
        return showSoftInput(view, flags, resultReceiver, SoftInputShowHideReason.SHOW_SOFT_INPUT);
    }

    private boolean showSoftInput(View view, int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#showSoftInput", this,
                null /* icProto */);
        // Re-dispatch if there is a context mismatch.
        final InputMethodManager fallbackImm = getFallbackInputMethodManagerIfNecessary(view);
        if (fallbackImm != null) {
            return fallbackImm.showSoftInput(view, flags, resultReceiver);
        }

        checkFocus();
        synchronized (mH) {
            if (!hasServedByInputMethodLocked(view)) {
                Log.w(TAG, "Ignoring showSoftInput() as view=" + view + " is not served.");
                return false;
            }

            try {
                Log.d(TAG, "showSoftInput() view=" + view + " flags=" + flags + " reason="
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
                return mService.showSoftInput(
                        mClient,
                        view.getWindowToken(),
                        flags,
                        resultReceiver,
                        reason);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This method is still kept for a while until android.support.v7.widget.SearchView ver. 26.0
     * is publicly released because previous implementations of that class had relied on this method
     * via reflection.
     *
     * @deprecated This is a hidden API. You should never use this.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768499)
    public void showSoftInputUnchecked(int flags, ResultReceiver resultReceiver) {
        synchronized (mH) {
            try {
                Log.w(TAG, "showSoftInputUnchecked() is a hidden method, which will be"
                        + " removed soon. If you are using android.support.v7.widget.SearchView,"
                        + " please update to version 26.0 or newer version.");
                if (mCurRootView == null || mCurRootView.getView() == null) {
                    Log.w(TAG, "No current root view, ignoring showSoftInputUnchecked()");
                    return;
                }
                mService.showSoftInput(
                        mClient,
                        mCurRootView.getView().getWindowToken(),
                        flags,
                        resultReceiver,
                        SoftInputShowHideReason.SHOW_SOFT_INPUT);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

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
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY} bit set.
     */
    public boolean hideSoftInputFromWindow(IBinder windowToken, int flags) {
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
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY} bit set.
     * @param resultReceiver If non-null, this will be called by the IME when
     * it has processed your request to tell you what it has done.  The result
     * code you receive may be either {@link #RESULT_UNCHANGED_SHOWN},
     * {@link #RESULT_UNCHANGED_HIDDEN}, {@link #RESULT_SHOWN}, or
     * {@link #RESULT_HIDDEN}.
     */
    public boolean hideSoftInputFromWindow(IBinder windowToken, int flags,
            ResultReceiver resultReceiver) {
        return hideSoftInputFromWindow(windowToken, flags, resultReceiver,
                SoftInputShowHideReason.HIDE_SOFT_INPUT);
    }

    private boolean hideSoftInputFromWindow(IBinder windowToken, int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#hideSoftInputFromWindow",
                this, null /* icProto */);
        checkFocus();
        synchronized (mH) {
            final View servedView = getServedViewLocked();
            if (servedView == null || servedView.getWindowToken() != windowToken) {
                return false;
            }

            try {
                return mService.hideSoftInput(mClient, windowToken, flags, resultReceiver, reason);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * This method toggles the input method window display.
     * If the input window is already displayed, it gets hidden.
     * If not the input window will be displayed.
     * @param windowToken The token of the window that is making the request,
     * as returned by {@link View#getWindowToken() View.getWindowToken()}.
     * @param showFlags Provides additional operating flags.  May be
     * 0 or have the {@link #SHOW_IMPLICIT},
     * {@link #SHOW_FORCED} bit set.
     * @param hideFlags Provides additional operating flags.  May be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY},
     * {@link #HIDE_NOT_ALWAYS} bit set.
     *
     * @deprecated Use {@link #showSoftInput(View, int)} or
     * {@link #hideSoftInputFromWindow(IBinder, int)} explicitly instead.
     * In particular during focus changes, the current visibility of the IME is not
     * well defined. Starting in {@link Build.VERSION_CODES#S Android S}, this only
     * has an effect if the calling app is the current IME focus.
     */
    @Deprecated
    public void toggleSoftInputFromWindow(IBinder windowToken, int showFlags, int hideFlags) {
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
     * @param showFlags Provides additional operating flags.  May be
     * 0 or have the {@link #SHOW_IMPLICIT},
     * {@link #SHOW_FORCED} bit set.
     * @param hideFlags Provides additional operating flags.  May be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY},
     * {@link #HIDE_NOT_ALWAYS} bit set.
     *
     * @deprecated Use {@link #showSoftInput(View, int)} or
     * {@link #hideSoftInputFromWindow(IBinder, int)} explicitly instead.
     * In particular during focus changes, the current visibility of the IME is not
     * well defined. Starting in {@link Build.VERSION_CODES#S Android S}, this only
     * has an effect if the calling app is the current IME focus.
     */
    @Deprecated
    public void toggleSoftInput(int showFlags, int hideFlags) {
        ImeTracing.getInstance().triggerClientDump(
                "InputMethodManager#toggleSoftInput", InputMethodManager.this,
                null /* icProto */);
        synchronized (mH) {
            final View view = getServedViewLocked();
            if (mImeInsetsConsumer != null && view != null) {
                if (mImeInsetsConsumer.isRequestedVisible()) {
                    hideSoftInputFromWindow(view.getWindowToken(), hideFlags, null,
                            SoftInputShowHideReason.HIDE_TOGGLE_SOFT_INPUT);
                } else {
                    showSoftInput(view, showFlags, null,
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
     * Called when {@link DelegateImpl#startInput}, {@link #restartInput(View)},
     * {@link #MSG_BIND} or {@link #MSG_UNBIND}.
     * Note that this method should *NOT* be called inside of {@code mH} lock to prevent start input
     * background thread may blocked by other methods which already inside {@code mH} lock.
     */
    boolean startInputInner(@StartInputReason int startInputReason,
            @Nullable IBinder windowGainingFocus, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode, int windowFlags) {
        final View view;
        synchronized (mH) {
            view = getServedViewLocked();

            // Make sure we have a window token for the served view.
            if (DEBUG) {
                Log.v(TAG, "Starting input: view=" + dumpViewInfo(view) +
                        " reason=" + InputMethodDebug.startInputReasonToString(startInputReason));
            }
            if (view == null) {
                if (DEBUG) Log.v(TAG, "ABORT input: no served view!");
                return false;
            }
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
            vh.post(() -> mDelegate.startInput(startInputReason, null, 0, 0, 0));
            return false;
        }

        // Okay we are now ready to call into the served view and have it
        // do its stuff.
        // Life is good: let's hook everything up!
        EditorInfo tba = new EditorInfo();
        // Note: Use Context#getOpPackageName() rather than Context#getPackageName() so that the
        // system can verify the consistency between the uid of this process and package name passed
        // from here. See comment of Context#getOpPackageName() for details.
        tba.packageName = view.getContext().getOpPackageName();
        tba.autofillId = view.getAutofillId();
        tba.fieldId = view.getId();
        InputConnection ic = view.onCreateInputConnection(tba);
        if (DEBUG) Log.v(TAG, "Starting input: tba=" + tba + " ic=" + ic);

        final Handler icHandler;
        InputBindResult res = null;
        synchronized (mH) {
            // Now that we are locked again, validate that our state hasn't
            // changed.
            final View servedView = getServedViewLocked();
            if (servedView != view || !mServedConnecting) {
                // Something else happened, so abort.
                if (DEBUG) Log.v(TAG,
                        "Starting input: finished by someone else. view=" + dumpViewInfo(view)
                        + " servedView=" + dumpViewInfo(servedView)
                        + " mServedConnecting=" + mServedConnecting);
                return false;
            }

            // If we already have a text box, then this view is already
            // connected so we want to restart it.
            if (mCurrentTextBoxAttribute == null) {
                startInputFlags |= StartInputFlags.INITIAL_CONNECTION;
            }

            // Hook 'em up and let 'er rip.
            mCurrentTextBoxAttribute = tba;

            mServedConnecting = false;
            if (mServedInputConnectionWrapper != null) {
                mServedInputConnectionWrapper.deactivate();
                mServedInputConnectionWrapper = null;
            }
            IInputConnectionWrapper servedContext;
            final int missingMethodFlags;
            if (ic != null) {
                mCursorSelStart = tba.initialSelStart;
                mCursorSelEnd = tba.initialSelEnd;
                mCursorCandStart = -1;
                mCursorCandEnd = -1;
                mCursorRect.setEmpty();
                mCursorAnchorInfo = null;
                missingMethodFlags = InputConnectionInspector.getMissingMethodFlags(ic);
                if ((missingMethodFlags & InputConnectionInspector.MissingMethodFlags.GET_HANDLER)
                        != 0) {
                    // InputConnection#getHandler() is not implemented.
                    icHandler = null;
                } else {
                    icHandler = ic.getHandler();
                }
                servedContext = new IInputConnectionWrapper(
                        icHandler != null ? icHandler.getLooper() : vh.getLooper(), ic, this, view);
            } else {
                servedContext = null;
                missingMethodFlags = 0;
                icHandler = null;
            }
            mServedInputConnectionWrapper = servedContext;

            if (DEBUG) {
                Log.v(TAG, "START INPUT: view=" + dumpViewInfo(view) + " ic="
                        + ic + " tba=" + tba + " startInputFlags="
                        + InputMethodDebug.startInputFlagsToString(startInputFlags));
            }
            try {
                res = mService.startInputOrWindowGainedFocus(
                        startInputReason, mClient, windowGainingFocus, startInputFlags,
                        softInputMode, windowFlags, tba, servedContext, missingMethodFlags,
                        view.getContext().getApplicationInfo().targetSdkVersion);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (DEBUG) Log.v(TAG, "Starting input: Bind result=" + res);
            if (res == null) {
                Log.wtf(TAG, "startInputOrWindowGainedFocus must not return"
                        + " null. startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason)
                        + " editorInfo=" + tba
                        + " startInputFlags="
                        + InputMethodDebug.startInputFlagsToString(startInputFlags));
                return false;
            }
            mIsInputMethodSuppressingSpellChecker = res.isInputMethodSuppressingSpellChecker;
            if (res.id != null) {
                setInputChannelLocked(res.channel);
                mBindSequence = res.sequence;
                mCurMethod = res.method; // for @UnsupportedAppUsage
                mCurrentInputMethodSession = InputMethodSessionWrapper.createOrNull(res.method);
                mCurId = res.id;
            } else if (res.channel != null && res.channel != mCurChannel) {
                res.channel.dispose();
            }
            switch (res.result) {
                case InputBindResult.ResultCode.ERROR_NOT_IME_TARGET_WINDOW:
                    mRestartOnNextWindowFocus = true;
                    break;
            }
            if (mCurrentInputMethodSession != null && mCompletions != null) {
                mCurrentInputMethodSession.displayCompletions(mCompletions);
            }
        }

        // Notify the app that the InputConnection is initialized and ready for use.
        if (ic != null && res != null && res.method != null) {
            if (DEBUG) {
                Log.v(TAG, "Calling View.onInputConnectionOpened: view= " + view
                        + ", ic=" + ic + ", tba=" + tba + ", handler=" + icHandler);
            }
            view.onInputConnectionOpenedInternal(ic, tba, icHandler);
        }

        return true;
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
     * Check the next served view from {@link ImeFocusController} if needs to start input.
     * Note that this method should *NOT* be called inside of {@code mH} lock to prevent start input
     * background thread may blocked by other methods which already inside {@code mH} lock.
     * @hide
     */
    @UnsupportedAppUsage
    public void checkFocus() {
        final ImeFocusController controller = getFocusController();
        if (controller != null) {
            controller.checkFocus(false /* forceNewFocus */, true /* startInput */);
        }
    }

    @UnsupportedAppUsage
    void closeCurrentInput() {
        synchronized (mH) {
            if (mCurRootView == null || mCurRootView.getView() == null) {
                Log.w(TAG, "No current root view, ignoring closeCurrentInput()");
                return;
            }
            try {
                mService.hideSoftInput(
                        mClient,
                        mCurRootView.getView().getWindowToken(),
                        HIDE_NOT_ALWAYS,
                        null,
                        SoftInputShowHideReason.HIDE_SOFT_INPUT);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
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
     *
     * @return {@code true} if IME can (eventually) be shown, {@code false} otherwise.
     * @hide
     */
    public boolean requestImeShow(IBinder windowToken) {
        checkFocus();
        synchronized (mH) {
            final View servedView = getServedViewLocked();
            if (servedView == null || servedView.getWindowToken() != windowToken) {
                return false;
            }
            showSoftInput(servedView, 0 /* flags */, null /* resultReceiver */,
                    SoftInputShowHideReason.SHOW_SOFT_INPUT_BY_INSETS_API);
            return true;
        }
    }

    /**
     * Notify IME directly that it is no longer visible.
     *
     * @param windowToken the window from which this request originates. If this doesn't match the
     *                    currently served view, the request is ignored.
     * @hide
     */
    public void notifyImeHidden(IBinder windowToken) {
        ImeTracing.getInstance().triggerClientDump("InputMethodManager#notifyImeHidden", this,
                null /* icProto */);
        synchronized (mH) {
            if (mCurrentInputMethodSession != null && mCurRootView != null
                    && mCurRootView.getWindowToken() == windowToken) {
                mCurrentInputMethodSession.notifyImeHidden();
            }
        }
    }

    /**
     * Notify IME directly to remove surface as it is no longer visible.
     * @param windowToken The client window token that requests the IME to remove its surface.
     * @hide
     */
    public void removeImeSurface(IBinder windowToken) {
        synchronized (mH) {
            try {
                mService.removeImeSurfaceFromWindowAsync(windowToken);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
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
            if (!hasServedByInputMethodLocked(view) || mCurrentTextBoxAttribute == null
                    || mCurrentInputMethodSession == null) {
                return;
            }

            if (mCursorSelStart != selStart || mCursorSelEnd != selEnd
                    || mCursorCandStart != candidatesStart
                    || mCursorCandEnd != candidatesEnd) {
                if (DEBUG) Log.d(TAG, "updateSelection");

                if (DEBUG) {
                    Log.v(TAG, "SELECTION CHANGE: " + mCurrentInputMethodSession);
                }
                final int oldSelStart = mCursorSelStart;
                final int oldSelEnd = mCursorSelEnd;
                // Update internal values before sending updateSelection to the IME, because
                // if it changes the text within its onUpdateSelection handler in a way that
                // does not move the cursor we don't want to call it again with the same values.
                mCursorSelStart = selStart;
                mCursorSelEnd = selEnd;
                mCursorCandStart = candidatesStart;
                mCursorCandEnd = candidatesEnd;
                mCurrentInputMethodSession.updateSelection(
                        oldSelStart, oldSelEnd, selStart, selEnd, candidatesStart, candidatesEnd);
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
            if (!hasServedByInputMethodLocked(view) || mCurrentTextBoxAttribute == null
                    || mCurrentInputMethodSession == null) {
                return;
            }
            if (DEBUG) Log.v(TAG, "onViewClicked: " + focusChanged);
            mCurrentInputMethodSession.viewClicked(focusChanged);
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
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean isCursorAnchorInfoEnabled() {
        synchronized (mH) {
            final boolean isImmediate = (mRequestUpdateCursorAnchorInfoMonitorMode &
                    InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0;
            final boolean isMonitoring = (mRequestUpdateCursorAnchorInfoMonitorMode &
                    InputConnection.CURSOR_UPDATE_MONITOR) != 0;
            return isImmediate || isMonitoring;
        }
    }

    /**
     * Set the requested mode for {@link #updateCursorAnchorInfo(View, CursorAnchorInfo)}.
     *
     * @hide
     */
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
            if (!hasServedByInputMethodLocked(view) || mCurrentTextBoxAttribute == null
                    || mCurrentInputMethodSession == null) {
                return;
            }

            mTmpCursorRect.set(left, top, right, bottom);
            if (!mCursorRect.equals(mTmpCursorRect)) {
                if (DEBUG) Log.d(TAG, "updateCursor: " + mCurrentInputMethodSession);

                mCurrentInputMethodSession.updateCursor(mTmpCursorRect);
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
            if (!hasServedByInputMethodLocked(view) || mCurrentTextBoxAttribute == null
                    || mCurrentInputMethodSession == null) {
                return;
            }
            // If immediate bit is set, we will call updateCursorAnchorInfo() even when the data has
            // not been changed from the previous call.
            final boolean isImmediate = (mRequestUpdateCursorAnchorInfoMonitorMode &
                    InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0;
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
            mCurrentInputMethodSession.updateCursorAnchorInfo(cursorAnchorInfo);
            mCursorAnchorInfo = cursorAnchorInfo;
            // Clear immediate bit (if any).
            mRequestUpdateCursorAnchorInfoMonitorMode &= ~InputConnection.CURSOR_UPDATE_IMMEDIATE;
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
            if (!hasServedByInputMethodLocked(view) || mCurrentTextBoxAttribute == null
                    || mCurrentInputMethodSession == null) {
                return;
            }
            if (DEBUG) Log.v(TAG, "APP PRIVATE COMMAND " + action + ": " + data);
            mCurrentInputMethodSession.appPrivateCommand(action, data);
        }
    }

    /**
     * Force switch to a new input method component. This can only be called
     * from an application or a service which has a token of the currently active input method.
     *
     * <p>On Android {@link Build.VERSION_CODES#Q} and later devices, the undocumented behavior that
     * token can be {@code null} when the caller has
     * {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} is deprecated. Instead, update
     * {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD} and
     * {@link android.provider.Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE} directly.</p>
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
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
            if (fallbackContext.checkSelfPermission(WRITE_SECURE_SETTINGS)
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
     * {@code null} even with {@link android.Manifest.permission#WRITE_SECURE_SETTINGS}. Instead,
     * update {@link android.provider.Settings.Secure#DEFAULT_INPUT_METHOD} and
     * {@link android.provider.Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE} directly.</p>
     *
     * @param token Supplies the identifying token given to an input method
     * when it was started, which allows it to perform this operation on
     * itself.
     * @param id The unique identifier for the new input method to be switched to.
     * @param subtype The new subtype of the new input method to be switched to.
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
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #HIDE_IMPLICIT_ONLY},
     * {@link #HIDE_NOT_ALWAYS} bit set.
     * @deprecated Use {@link InputMethodService#requestHideSelf(int)} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in this
     * class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void hideSoftInputFromInputMethod(IBinder token, int flags) {
        InputMethodPrivilegedOperationsRegistry.get(token).hideMySoftInput(flags);
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
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link #SHOW_IMPLICIT} or
     * {@link #SHOW_FORCED} bit set.
     * @deprecated Use {@link InputMethodService#requestShowSelf(int)} instead. This method was
     * intended for IME developers who should be accessing APIs through the service. APIs in this
     * class are intended for app developers interacting with the IME.
     */
    @Deprecated
    public void showSoftInputFromInputMethod(IBinder token, int flags) {
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
            if (mCurrentInputMethodSession != null) {
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
                    Log.v(TAG, "DISPATCH INPUT EVENT: " + mCurrentInputMethodSession);
                }

                PendingEvent p = obtainPendingEventLocked(
                        event, token, mCurId, callback, handler);
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
    void sendInputEventAndReportResultOnMainLooper(PendingEvent p) {
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
    int sendInputEventOnMainLooperLocked(PendingEvent p) {
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

            Log.w(TAG, "Unable to send input event to IME: " + mCurId + " dropping: " + event);
        }
        return DISPATCH_NOT_HANDLED;
    }

    void finishedInputEvent(int seq, boolean handled, boolean timeout) {
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
    void invokeFinishedInputEventCallback(PendingEvent p, boolean handled) {
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
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void showInputMethodPickerFromSystem(boolean showAuxiliarySubtypes, int displayId) {
        final int mode = showAuxiliarySubtypes
                ? SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES
                : SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES;
        try {
            mService.showInputMethodPickerFromSystem(mClient, mode, displayId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void showInputMethodPickerLocked() {
        try {
            mService.showInputMethodPickerFromClient(mClient, SHOW_IM_PICKER_MODE_AUTO);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
    public boolean isInputMethodPickerShown() {
        try {
            return mService.isInputMethodPickerShownForTest();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Show the settings for enabling subtypes of the specified input method.
     *
     * @param imiId An input method, whose subtypes settings will be shown. If imiId is null,
     * subtypes of all input methods will be shown.
     */
    public void showInputMethodAndSubtypeEnabler(String imiId) {
        try {
            mService.showInputMethodAndSubtypeEnablerFromClient(mClient, imiId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current input method subtype. This subtype is one of the subtypes in
     * the current input method. This method returns null when the current input method doesn't
     * have any input method subtype.
     */
    public InputMethodSubtype getCurrentInputMethodSubtype() {
        try {
            return mService.getCurrentInputMethodSubtype();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     *             {@link android.Manifest.permission#WRITE_SECURE_SETTINGS} permission, just
     *             directly update {@link Settings.Secure#SELECTED_INPUT_METHOD_SUBTYPE}.
     */
    @Deprecated
    @RequiresPermission(WRITE_SECURE_SETTINGS)
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
        if (fallbackContext.checkSelfPermission(WRITE_SECURE_SETTINGS)
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
        final List<InputMethodSubtype> enabledSubtypes;
        try {
            enabledSubtypes = mService.getEnabledInputMethodSubtypeList(imeId, true);
        } catch (RemoteException e) {
            return false;
        }
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
    @UnsupportedAppUsage
    public int getInputMethodWindowVisibleHeight() {
        try {
            return mService.getInputMethodWindowVisibleHeight();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * @param subtypes subtypes will be added as additional subtypes of the current input method.
     * @deprecated For IMEs that have already implemented features like customizable/downloadable
     *             keyboard layouts/languages, please start migration to other approaches. One idea
     *             would be exposing only one unified {@link InputMethodSubtype} then implement
     *             IME's own language switching mechanism within that unified subtype. The support
     *             of "Additional Subtype" may be completely dropped in a future version of Android.
     */
    @Deprecated
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
        try {
            mService.setAdditionalInputMethodSubtypes(imiId, subtypes);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public InputMethodSubtype getLastInputMethodSubtype() {
        try {
            return mService.getLastInputMethodSubtype();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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

    void doDump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (processDump(fd, args)) {
            return;
        }

        final Printer p = new PrintWriterPrinter(fout);
        p.println("Input method client state for " + this + ":");

        p.println("  mService=" + mService);
        p.println("  mMainLooper=" + mMainLooper);
        p.println("  mIInputContext=" + mIInputContext);
        p.println("  mActive=" + mActive
                + " mRestartOnNextWindowFocus=" + mRestartOnNextWindowFocus
                + " mBindSequence=" + mBindSequence
                + " mCurId=" + mCurId);
        p.println("  mFullscreenMode=" + mFullscreenMode);
        if (mCurrentInputMethodSession != null) {
            p.println("  mCurMethod=" + mCurrentInputMethodSession);
        } else {
            p.println("  mCurMethod= null");
        }
        p.println("  mCurRootView=" + mCurRootView);
        p.println("  mServedView=" + getServedViewLocked());
        p.println("  mNextServedView=" + getNextServedViewLocked());
        p.println("  mServedConnecting=" + mServedConnecting);
        if (mCurrentTextBoxAttribute != null) {
            p.println("  mCurrentTextBoxAttribute:");
            mCurrentTextBoxAttribute.dump(p, "    ");
        } else {
            p.println("  mCurrentTextBoxAttribute: null");
        }
        p.println("  mServedInputConnectionWrapper=" + mServedInputConnectionWrapper);
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

    private static String dumpViewInfo(@Nullable final View view) {
        if (view == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append(view);
        sb.append(",focus=" + view.hasFocus());
        sb.append(",windowFocus=" + view.hasWindowFocus());
        sb.append(",autofillUiShowing=" + isAutofillUIShowing(view));
        sb.append(",window=" + view.getWindowToken());
        sb.append(",displayId=" + view.getContext().getDisplayId());
        sb.append(",temporaryDetach=" + view.isTemporarilyDetached());
        sb.append(",hasImeFocus=" + view.hasImeFocus());

        return sb.toString();
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
            if (arg.equals(PROTO_ARG)) {
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
    @GuardedBy("mH")
    public void dumpDebug(ProtoOutputStream proto, ProtoOutputStream icProto) {
        if (mCurrentInputMethodSession == null) {
            return;
        }

        proto.write(DISPLAY_ID, mDisplayId);
        final long token = proto.start(INPUT_METHOD_MANAGER);
        synchronized (mH) {
            proto.write(CUR_ID, mCurId);
            proto.write(FULLSCREEN_MODE, mFullscreenMode);
            proto.write(ACTIVE, mActive);
            proto.write(SERVED_CONNECTING, mServedConnecting);
            proto.end(token);
            if (mCurRootView != null) {
                mCurRootView.dumpDebug(proto, VIEW_ROOT_IMPL);
            }
            if (mCurrentTextBoxAttribute != null) {
                mCurrentTextBoxAttribute.dumpDebug(proto, EDITOR_INFO);
            }
            if (mImeInsetsConsumer != null) {
                mImeInsetsConsumer.dumpDebug(proto, IME_INSETS_SOURCE_CONSUMER);
            }
            if (mServedInputConnectionWrapper != null) {
                mServedInputConnectionWrapper.dumpDebug(proto, INPUT_CONNECTION);
            }
            if (icProto != null) {
                proto.write(INPUT_CONNECTION_CALL, icProto.getBytes());
            }
        }
    }
}
