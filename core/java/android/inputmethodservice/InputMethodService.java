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

package android.inputmethodservice;

import static android.inputmethodservice.InputMethodServiceProto.CANDIDATES_VIEW_STARTED;
import static android.inputmethodservice.InputMethodServiceProto.CANDIDATES_VISIBILITY;
import static android.inputmethodservice.InputMethodServiceProto.CONFIGURATION;
import static android.inputmethodservice.InputMethodServiceProto.DECOR_VIEW_VISIBLE;
import static android.inputmethodservice.InputMethodServiceProto.DECOR_VIEW_WAS_VISIBLE;
import static android.inputmethodservice.InputMethodServiceProto.EXTRACTED_TOKEN;
import static android.inputmethodservice.InputMethodServiceProto.EXTRACT_VIEW_HIDDEN;
import static android.inputmethodservice.InputMethodServiceProto.FULLSCREEN_APPLIED;
import static android.inputmethodservice.InputMethodServiceProto.INPUT_BINDING;
import static android.inputmethodservice.InputMethodServiceProto.INPUT_CONNECTION_CALL;
import static android.inputmethodservice.InputMethodServiceProto.INPUT_EDITOR_INFO;
import static android.inputmethodservice.InputMethodServiceProto.INPUT_STARTED;
import static android.inputmethodservice.InputMethodServiceProto.INPUT_VIEW_STARTED;
import static android.inputmethodservice.InputMethodServiceProto.IN_SHOW_WINDOW;
import static android.inputmethodservice.InputMethodServiceProto.IS_FULLSCREEN;
import static android.inputmethodservice.InputMethodServiceProto.IS_INPUT_VIEW_SHOWN;
import static android.inputmethodservice.InputMethodServiceProto.InsetsProto.CONTENT_TOP_INSETS;
import static android.inputmethodservice.InputMethodServiceProto.InsetsProto.TOUCHABLE_INSETS;
import static android.inputmethodservice.InputMethodServiceProto.InsetsProto.TOUCHABLE_REGION;
import static android.inputmethodservice.InputMethodServiceProto.InsetsProto.VISIBLE_TOP_INSETS;
import static android.inputmethodservice.InputMethodServiceProto.LAST_COMPUTED_INSETS;
import static android.inputmethodservice.InputMethodServiceProto.LAST_SHOW_INPUT_REQUESTED;
import static android.inputmethodservice.InputMethodServiceProto.SETTINGS_OBSERVER;
import static android.inputmethodservice.InputMethodServiceProto.SHOW_INPUT_FLAGS;
import static android.inputmethodservice.InputMethodServiceProto.SHOW_INPUT_REQUESTED;
import static android.inputmethodservice.InputMethodServiceProto.SOFT_INPUT_WINDOW;
import static android.inputmethodservice.InputMethodServiceProto.STATUS_ICON;
import static android.inputmethodservice.InputMethodServiceProto.TOKEN;
import static android.inputmethodservice.InputMethodServiceProto.VIEWS_CREATED;
import static android.inputmethodservice.InputMethodServiceProto.WINDOW_VISIBLE;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_NO_TEXT_RECOGNIZED;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_OTHER;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED;
import static android.view.inputmethod.Flags.FLAG_CONNECTIONLESS_HANDWRITING;
import static android.view.inputmethod.Flags.ctrlShiftShortcut;
import static android.view.inputmethod.Flags.predictiveBackIme;

import android.annotation.AnyThread;
import android.annotation.CallSuper;
import android.annotation.DrawableRes;
import android.annotation.DurationMillisLong;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.annotation.UiContext;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.graphics.Rect;
import android.graphics.Region;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.method.MovementMethod;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import android.view.Gravity;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.MotionEvent.ToolType;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets.Side;
import android.view.WindowInsets.Type;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ConnectionlessHandwritingCallback;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputContentInfo;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodServiceTraceProto;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.CompatOnBackInvokedCallback;
import android.window.ImeOnBackInvokedDispatcher;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowMetricsHelper;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.inputmethod.InputMethodPrivilegedOperations;
import com.android.internal.inputmethod.InputMethodPrivilegedOperationsRegistry;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.util.RingBuffer;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.Executor;

/**
 * InputMethodService provides a standard implementation of an InputMethod,
 * which final implementations can derive from and customize.  See the
 * base class {@link AbstractInputMethodService} and the {@link InputMethod}
 * interface for more information on the basics of writing input methods.
 * 
 * <p>In addition to the normal Service lifecycle methods, this class
 * introduces some new specific callbacks that most subclasses will want
 * to make use of:</p>
 * <ul>
 * <li> {@link #onInitializeInterface()} for user-interface initialization,
 * in particular to deal with configuration changes while the service is
 * running.
 * <li> {@link #onBindInput} to find out about switching to a new client.
 * <li> {@link #onStartInput} to deal with an input session starting with
 * the client.
 * <li> {@link #onCreateInputView()}, {@link #onCreateCandidatesView()},
 * and {@link #onCreateExtractTextView()} for non-demand generation of the UI.
 * <li> {@link #onStartInputView(EditorInfo, boolean)} to deal with input
 * starting within the input area of the IME.
 * </ul>
 * 
 * <p>An input method has significant discretion in how it goes about its
 * work: the {@link android.inputmethodservice.InputMethodService} provides
 * a basic framework for standard UI elements (input view, candidates view,
 * and running in fullscreen mode), but it is up to a particular implementor
 * to decide how to use them.  For example, one input method could implement
 * an input area with a keyboard, another could allow the user to draw text,
 * while a third could have no input area (and thus not be visible to the
 * user) but instead listen to audio and perform text to speech conversion.</p>
 * 
 * <p>In the implementation provided here, all of these elements are placed
 * together in a single window managed by the InputMethodService.  It will
 * execute callbacks as it needs information about them, and provides APIs for
 * programmatic control over them.  They layout of these elements is explicitly
 * defined:</p>
 * 
 * <ul>
 * <li>The soft input view, if available, is placed at the bottom of the
 * screen.
 * <li>The candidates view, if currently shown, is placed above the soft
 * input view.
 * <li>If not running fullscreen, the application is moved or resized to be
 * above these views; if running fullscreen, the window will completely cover
 * the application and its top part will contain the extract text of what is
 * currently being edited by the application.
 * </ul>
 * 
 * 
 * <a name="SoftInputView"></a>
 * <h3>Soft Input View</h3>
 * 
 * <p>Central to most input methods is the soft input view.  This is where most
 * user interaction occurs: pressing on soft keys, drawing characters, or
 * however else your input method wants to generate text.  Most implementations
 * will simply have their own view doing all of this work, and return a new
 * instance of it when {@link #onCreateInputView()} is called.  At that point,
 * as long as the input view is visible, you will see user interaction in
 * that view and can call back on the InputMethodService to interact with the
 * application as appropriate.</p>
 * 
 * <p>There are some situations where you want to decide whether or not your
 * soft input view should be shown to the user.  This is done by implementing
 * the {@link #onEvaluateInputViewShown()} to return true or false based on
 * whether it should be shown in the current environment.  If any of your
 * state has changed that may impact this, call
 * {@link #updateInputViewShown()} to have it re-evaluated.  The default
 * implementation always shows the input view unless there is a hard
 * keyboard available, which is the appropriate behavior for most input
 * methods.</p>
 * 
 * 
 * <a name="CandidatesView"></a>
 * <h3>Candidates View</h3>
 * 
 * <p>Often while the user is generating raw text, an input method wants to
 * provide them with a list of possible interpretations of that text that can
 * be selected for use.  This is accomplished with the candidates view, and
 * like the soft input view you implement {@link #onCreateCandidatesView()}
 * to instantiate your own view implementing your candidates UI.</p>
 * 
 * <p>Management of the candidates view is a little different than the input
 * view, because the candidates view tends to be more transient, being shown
 * only when there are possible candidates for the current text being entered
 * by the user.  To control whether the candidates view is shown, you use
 * {@link #setCandidatesViewShown(boolean)}.  Note that because the candidate
 * view tends to be shown and hidden a lot, it does not impact the application
 * UI in the same way as the soft input view: it will never cause application
 * windows to resize, only cause them to be panned if needed for the user to
 * see the current focus.</p>
 * 
 * 
 * <a name="FullscreenMode"></a>
 * <h3>Fullscreen Mode</h3>
 * 
 * <p>Sometimes your input method UI is too large to integrate with the
 * application UI, so you just want to take over the screen.  This is
 * accomplished by switching to full-screen mode, causing the input method
 * window to fill the entire screen and add its own "extracted text" editor
 * showing the user the text that is being typed.  Unlike the other UI elements,
 * there is a standard implementation for the extract editor that you should
 * not need to change.  The editor is placed at the top of the IME, above the
 * input and candidates views.</p>
 * 
 * <p>Similar to the input view, you control whether the IME is running in
 * fullscreen mode by implementing {@link #onEvaluateFullscreenMode()}
 * to return true or false based on
 * whether it should be fullscreen in the current environment.  If any of your
 * state has changed that may impact this, call
 * {@link #updateFullscreenMode()} to have it re-evaluated.  The default
 * implementation selects fullscreen mode when the screen is in a landscape
 * orientation, which is appropriate behavior for most input methods that have
 * a significant input area.</p>
 * 
 * <p>When in fullscreen mode, you have some special requirements because the
 * user can not see the application UI.  In particular, you should implement
 * {@link #onDisplayCompletions(CompletionInfo[])} to show completions
 * generated by your application, typically in your candidates view like you
 * would normally show candidates.
 * 
 * 
 * <a name="GeneratingText"></a>
 * <h3>Generating Text</h3>
 * 
 * <p>The key part of an IME is of course generating text for the application.
 * This is done through calls to the
 * {@link android.view.inputmethod.InputConnection} interface to the
 * application, which can be retrieved from {@link #getCurrentInputConnection()}.
 * This interface allows you to generate raw key events or, if the target
 * supports it, directly edit in strings of candidates and committed text.</p>
 * 
 * <p>Information about what the target is expected and supports can be found
 * through the {@link android.view.inputmethod.EditorInfo} class, which is
 * retrieved with {@link #getCurrentInputEditorInfo()} method.  The most
 * important part of this is {@link android.view.inputmethod.EditorInfo#inputType
 * EditorInfo.inputType}; in particular, if this is
 * {@link android.view.inputmethod.EditorInfo#TYPE_NULL EditorInfo.TYPE_NULL},
 * then the target does not support complex edits and you need to only deliver
 * raw key events to it.  An input method will also want to look at other
 * values here, to for example detect password mode, auto complete text views,
 * phone number entry, etc.</p>
 * 
 * <p>When the user switches between input targets, you will receive calls to
 * {@link #onFinishInput()} and {@link #onStartInput(EditorInfo, boolean)}.
 * You can use these to reset and initialize your input state for the current
 * target.  For example, you will often want to clear any input state, and
 * update a soft keyboard to be appropriate for the new inputType.</p>
 * 
 * @attr ref android.R.styleable#InputMethodService_imeFullscreenBackground
 * @attr ref android.R.styleable#InputMethodService_imeExtractEnterAnimation
 * @attr ref android.R.styleable#InputMethodService_imeExtractExitAnimation
 */
@UiContext
public class InputMethodService extends AbstractInputMethodService {
    static final String TAG = "InputMethodService";
    static final boolean DEBUG = false;

    /**
     * Key for a boolean value that tells whether {@link InputMethodService} is responsible for
     * rendering the back button and the IME switcher button or not when the gestural navigation is
     * enabled.
     *
     * <p>This sysprop is just ignored when the gestural navigation mode is not enabled.</p>
     *
     * <p>
     * To avoid complexity that is not necessary for production, you always need to reboot the
     * device after modifying this flag as follows:
     * <pre>
     * $ adb root
     * $ adb shell setprop persist.sys.ime.can_render_gestural_nav_buttons true
     * $ adb reboot
     * </pre>
     * </p>
     */
    private static final String PROP_CAN_RENDER_GESTURAL_NAV_BUTTONS =
            "persist.sys.ime.can_render_gestural_nav_buttons";

    /**
     * Number of {@link MotionEvent} to buffer if IME is not ready with Ink view.
     * This number may be configured eventually based on device's touch sampling frequency.
     */
    private static final int MAX_EVENTS_BUFFER = 500;

    /**
     * When IME doesn't receive stylus input for these many milliseconds, Handwriting session
     * will be finished by calling {@link #finishStylusHandwriting()}.
     * @see #onStartStylusHandwriting()
     * @see #onFinishStylusHandwriting()
     */
    private static final long STYLUS_HANDWRITING_IDLE_TIMEOUT_MS = 10000;

    /**
     * Max allowed stylus handwriting session idle-timeout.
     */
    private static final long STYLUS_HANDWRITING_IDLE_TIMEOUT_MAX_MS =
            STYLUS_HANDWRITING_IDLE_TIMEOUT_MS * 3;

    /**
     * Stylus idle-timeout after which stylus {@code InkWindow} will be removed.
     */
    private static final long STYLUS_WINDOW_IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000; // 5 minutes.

    /**
     * A circular buffer of size MAX_EVENTS_BUFFER in case IME is taking too long to add ink view.
     **/
    private RingBuffer<MotionEvent> mPendingEvents;
    private ImeOnBackInvokedDispatcher mImeDispatcher;
    private boolean mBackCallbackRegistered = false;
    private final CompatOnBackInvokedCallback mCompatBackCallback = this::compatHandleBack;
    private Runnable mImeSurfaceRemoverRunnable;
    private Runnable mFinishHwRunnable;
    private long mStylusHwSessionsTimeout = STYLUS_HANDWRITING_IDLE_TIMEOUT_MS;
    private Runnable mStylusWindowIdleTimeoutRunnable;
    private long mStylusWindowIdleTimeoutForTest;

    /**
     * Tracks the ctrl+shift shortcut
     **/
    private boolean mUsingCtrlShiftShortcut = false;

    /**
     * Returns whether {@link InputMethodService} is responsible for rendering the back button and
     * the IME switcher button or not when the gestural navigation is enabled.
     *
     * <p>This method is supposed to be used with an assumption that the same value is returned in
     * other processes. It is developers' responsibility for rebooting the device when the sysprop
     * is modified.</p>
     *
     * @return {@code true} if {@link InputMethodService} is responsible for rendering the back
     * button and the IME switcher button when the gestural navigation is enabled.
     *
     * @hide
     */
    @AnyThread
    public static boolean canImeRenderGesturalNavButtons() {
        return SystemProperties.getBoolean(PROP_CAN_RENDER_GESTURAL_NAV_BUTTONS, true);
    }

    /**
     * Allows the system to optimize the back button affordance based on the presence of software
     * keyboard.
     *
     * <p>For instance, on devices that have navigation bar and software-rendered back button, the
     * system may use a different icon while {@link #isInputViewShown()} returns {@code true}, to
     * indicate that the back button has "dismiss" affordance.</p>
     *
     * <p>Note that {@link KeyEvent#KEYCODE_BACK} events continue to be sent to
     * {@link #onKeyDown(int, KeyEvent)} even when this mode is specified. The default
     * implementation of {@link #onKeyDown(int, KeyEvent)} for {@link KeyEvent#KEYCODE_BACK} does
     * not take this mode into account.</p>
     *
     * <p>For API level {@link android.os.Build.VERSION_CODES#O_MR1} and lower devices, this is the
     * only mode you can safely specify without worrying about the compatibility.</p>
     *
     * @see #setBackDisposition(int)
     */
    public static final int BACK_DISPOSITION_DEFAULT = 0;

    /**
     * Deprecated flag.
     *
     * <p>To avoid compatibility issues, IME developers should not use this flag.</p>
     *
     * @deprecated on {@link android.os.Build.VERSION_CODES#P} and later devices, this flag is
     *             handled as a synonym of {@link #BACK_DISPOSITION_DEFAULT}. On
     *             {@link android.os.Build.VERSION_CODES#O_MR1} and prior devices, expected behavior
     *             of this mode had not been well defined. Most likely the end result would be the
     *             same as {@link #BACK_DISPOSITION_DEFAULT}. Either way it is not recommended to
     *             use this mode
     * @see #setBackDisposition(int)
     */
    @Deprecated
    public static final int BACK_DISPOSITION_WILL_NOT_DISMISS = 1;

    /**
     * Deprecated flag.
     *
     * <p>To avoid compatibility issues, IME developers should not use this flag.</p>
     *
     * @deprecated on {@link android.os.Build.VERSION_CODES#P} and later devices, this flag is
     *             handled as a synonym of {@link #BACK_DISPOSITION_DEFAULT}. On
     *             {@link android.os.Build.VERSION_CODES#O_MR1} and prior devices, expected behavior
     *             of this mode had not been well defined. In AOSP implementation running on devices
     *             that have navigation bar, specifying this flag could change the software back
     *             button to "Dismiss" icon no matter whether the software keyboard is shown or not,
     *             but there would be no easy way to restore the icon state even after IME lost the
     *             connection to the application. To avoid user confusions, do not specify this mode
     *             anyway
     * @see #setBackDisposition(int)
     */
    @Deprecated
    public static final int BACK_DISPOSITION_WILL_DISMISS = 2;

    /**
     * Asks the system to not adjust the back button affordance even when the software keyboard is
     * shown.
     *
     * <p>This mode is useful for UI modes where IME's main soft input window is used for some
     * supplemental UI, such as floating candidate window for languages such as Chinese and
     * Japanese, where users expect the back button is, or at least looks to be, handled by the
     * target application rather than the UI shown by the IME even while {@link #isInputViewShown()}
     * returns {@code true}.</p>
     *
     * <p>Note that {@link KeyEvent#KEYCODE_BACK} events continue to be sent to
     * {@link #onKeyDown(int, KeyEvent)} even when this mode is specified. The default
     * implementation of {@link #onKeyDown(int, KeyEvent)} for {@link KeyEvent#KEYCODE_BACK} does
     * not take this mode into account.</p>
     *
     * @see #setBackDisposition(int)
     */
    public static final int BACK_DISPOSITION_ADJUST_NOTHING = 3;

    /**
     * The disposition mode that indicates the expected affordance for the back button.
     *
     * @hide
     */
    @IntDef(prefix = { "BACK_DISPOSITION_" }, value = {
            BACK_DISPOSITION_DEFAULT,
            BACK_DISPOSITION_WILL_NOT_DISMISS,
            BACK_DISPOSITION_WILL_DISMISS,
            BACK_DISPOSITION_ADJUST_NOTHING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BackDispositionMode {}

    /**
     * The IME is active, and ready to accept touch/key events. It may or may not be visible.
     *
     * @hide
     */
    public static final int IME_ACTIVE = 1 << 0;

    /**
     * The IME is perceptibly visible to the user.
     *
     * @hide
     */
    public static final int IME_VISIBLE = 1 << 1;

    /**
     * The IME is visible, but not yet perceptible to the user (e.g. fading in)
     * by {@link android.view.WindowInsetsController}.
     *
     * @see InputMethodManager#reportPerceptible
     * @hide
     */
    public static final int IME_VISIBLE_IMPERCEPTIBLE = 1 << 2;

    /**
     * The IME window visibility state.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "IME_" }, value = {
            IME_ACTIVE,
            IME_VISIBLE,
            IME_VISIBLE_IMPERCEPTIBLE,
    })
    public @interface ImeWindowVisibility {}

    // Min and max values for back disposition.
    private static final int BACK_DISPOSITION_MIN = BACK_DISPOSITION_DEFAULT;
    private static final int BACK_DISPOSITION_MAX = BACK_DISPOSITION_ADJUST_NOTHING;

    /**
     * Timeout after which hidden IME surface will be removed from memory
     * TODO(b/230762351): reset timeout to 5000ms and invalidate cache when IME insets change.
     */
    private static final long TIMEOUT_SURFACE_REMOVAL_MILLIS = 500;

    InputMethodManager mImm;
    private InputMethodPrivilegedOperations mPrivOps = new InputMethodPrivilegedOperations();

    @NonNull
    private final NavigationBarController mNavigationBarController =
            new NavigationBarController(this);

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    int mTheme = 0;

    /**
     * Finish the {@link InputConnection} when the device becomes
     * {@link android.os.PowerManager#isInteractive non-interactive}.
     *
     * <p>
     * If enabled by the current {@link InputMethodService input method}, the current input
     * connection will be {@link InputMethodService#onFinishInput finished} whenever the devices
     * becomes non-interactive.
     *
     * <p>
     * If not enabled, the current input connection will instead be silently deactivated when the
     * devices becomes non-interactive, and an {@link InputMethodService#onFinishInput
     * onFinishInput()} {@link InputMethodService#onStartInput onStartInput()} pair is dispatched
     * when the device becomes interactive again.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.S)
    public static final long FINISH_INPUT_NO_FALLBACK_CONNECTION = 156215187L; // This is a bug id.

    /**
     * Disallow IMEs to override {@link InputMethodService#onCreateInputMethodSessionInterface()}
     * method.
     *
     * <p>If IMEs targeting on Android U and beyond override the
     * {@link InputMethodService#onCreateInputMethodSessionInterface()}, an {@link LinkageError}
     * would be thrown.</p>
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final long DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE = 148086656L;

    /**
     * Enable the logic to allow hiding the IME caption bar ("fake" IME navigation bar).
     * @hide
     */
    public static final boolean ENABLE_HIDE_IME_CAPTION_BAR = true;

    LayoutInflater mInflater;
    TypedArray mThemeAttrs;
    @UnsupportedAppUsage
    View mRootView;
    SoftInputWindow mWindow;
    boolean mInitialized;
    boolean mViewsCreated;
    // IME views visibility.
    boolean mDecorViewVisible;
    boolean mDecorViewWasVisible;
    boolean mInShowWindow;
    // IME window visibility.
    // Use (mDecorViewVisible && mWindowVisible) to check if IME is visible to the user.
    boolean mWindowVisible;

    ViewGroup mFullscreenArea;
    FrameLayout mExtractFrame;
    FrameLayout mCandidatesFrame;
    FrameLayout mInputFrame;
    
    IBinder mToken;
    
    InputBinding mInputBinding;
    InputConnection mInputConnection;
    boolean mInputStarted;
    boolean mInputViewStarted;
    boolean mCandidatesViewStarted;
    InputConnection mStartedInputConnection;
    EditorInfo mInputEditorInfo;

    @InputMethod.ShowFlags
    int mShowInputFlags;
    boolean mShowInputRequested;
    boolean mLastShowInputRequested;
    int mCandidatesVisibility;
    CompletionInfo[] mCurCompletions;

    boolean mFullscreenApplied;
    boolean mIsFullscreen;
    private boolean mLastWasInFullscreenMode;
    @UnsupportedAppUsage
    View mExtractView;
    boolean mExtractViewHidden;
    @UnsupportedAppUsage
    ExtractEditText mExtractEditText;
    ViewGroup mExtractAccessories;
    View mExtractAction;
    ExtractedText mExtractedText;
    int mExtractedToken;
    
    View mInputView;
    boolean mIsInputViewShown;
    
    int mStatusIcon;

    /** Latest reported value of back disposition mode. */
    @BackDispositionMode
    int mBackDisposition;

    /** Latest reported value of IME window visibility state. */
    @ImeWindowVisibility
    private int mImeWindowVisibility;

    private Object mLock = new Object();
    @GuardedBy("mLock")
    private boolean mNotifyUserActionSent;

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    final Insets mTmpInsets = new Insets();
    final int[] mTmpLocation = new int[2];

    private InlineSuggestionSessionController mInlineSuggestionSessionController;

    private @NonNull OptionalInt mHandwritingRequestId = OptionalInt.empty();
    private InputEventReceiver mHandwritingEventReceiver;
    private Handler mHandler;
    private ImsConfigurationTracker mConfigTracker = new ImsConfigurationTracker();
    private boolean mDestroyed;
    private boolean mOnPreparedStylusHwCalled;

    /** Stylus handwriting Ink window. */
    private InkWindow mInkWindow;

    private IConnectionlessHandwritingCallback mConnectionlessHandwritingCallback;
    private boolean mIsConnectionlessHandwritingForDelegation;
    // Holds the recognized text from a connectionless handwriting session which can later be
    // committed by commitHandwritingDelegationTextIfAvailable().
    private CharSequence mHandwritingDelegationText;

    /**
     * An opaque {@link Binder} token of window requesting {@link InputMethodImpl#showSoftInput}
     * The original app window token is passed from client app window.
     * {@link com.android.server.inputmethod.InputMethodManagerService} creates a unique
     * placeholder token to identify this window.
     * This placeholder token is only valid for a single call to
     * {@link InputMethodImpl#showSoftInput}, after which it is set null until next call.
     */
    private IBinder mCurShowInputToken;

    /**
     * An opaque {@link Binder} token of window requesting {@link InputMethodImpl#hideSoftInput}
     * The original app window token is passed from client app window.
     * {@link com.android.server.inputmethod.InputMethodManagerService} creates a unique
     * placeholder token to identify this window.
     * This placeholder token is only valid for a single call to
     * {@link InputMethodImpl#hideSoftInput}, after which it is set {@code null} until next call.
     */
    private IBinder mCurHideInputToken;

    /**
     * The token tracking the current IME request.
     *
     * <p> This exists as a workaround to changing the signatures of public methods. It will get
     * set to a {@code non-null} value before every call that uses it, stored locally inside the
     * callee, and immediately after reset to {@code null} from the callee.
     */
    @Nullable
    private ImeTracker.Token mCurStatsToken;

    final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer = info -> {
        onComputeInsets(mTmpInsets);
        mNavigationBarController.updateInsets(mTmpInsets);
        if (!mViewsCreated) {
            // The IME views are not ready, keep visible insets untouched.
            mTmpInsets.visibleTopInsets = 0;
        }
        if (isExtractViewShown()) {
            // In true fullscreen mode, we just say the window isn't covering
            // any content so we don't impact whatever is behind.
            View decor = getWindow().getWindow().getDecorView();
            info.contentInsets.top = info.visibleInsets.top = decor.getHeight();
            info.touchableRegion.setEmpty();
            info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
        } else {
            info.contentInsets.top = mTmpInsets.contentTopInsets;
            info.visibleInsets.top = mTmpInsets.visibleTopInsets;
            info.touchableRegion.set(mTmpInsets.touchableRegion);
            info.setTouchableInsets(mTmpInsets.touchableInsets);
        }
        mNavigationBarController.updateTouchableInsets(mTmpInsets, info);

        if (mInputFrame != null) {
            setImeExclusionRect(mTmpInsets.visibleTopInsets);
        }
    };

    final View.OnClickListener mActionClickListener = v -> {
        final EditorInfo ei = getCurrentInputEditorInfo();
        final InputConnection ic = getCurrentInputConnection();
        if (ei != null && ic != null) {
            if (ei.actionId != 0) {
                ic.performEditorAction(ei.actionId);
            } else if ((ei.imeOptions & EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE) {
                ic.performEditorAction(ei.imeOptions & EditorInfo.IME_MASK_ACTION);
            }
        }
    };

    /**
     * Concrete implementation of
     * {@link AbstractInputMethodService.AbstractInputMethodImpl} that provides
     * all of the standard behavior for an input method.
     */
    public class InputMethodImpl extends AbstractInputMethodImpl {

        private boolean mSystemCallingShowSoftInput;
        private boolean mSystemCallingHideSoftInput;
        private boolean mSimultaneousStylusAndTouchEnabled;

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public final void initializeInternal(@NonNull IInputMethod.InitParams params) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.initializeInternal");
            mPrivOps.set(params.privilegedOperations);
            InputMethodPrivilegedOperationsRegistry.put(params.token, mPrivOps);
            mNavigationBarController.onNavButtonFlagsChanged(params.navigationBarFlags);
            attachToken(params.token);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public void onCreateInlineSuggestionsRequest(
                @NonNull InlineSuggestionsRequestInfo requestInfo,
                @NonNull IInlineSuggestionsRequestCallback cb) {
            if (DEBUG) {
                Log.d(TAG, "InputMethodService received onCreateInlineSuggestionsRequest()");
            }
            mInlineSuggestionSessionController.onMakeInlineSuggestionsRequest(requestInfo, cb);
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void attachToken(IBinder token) {
            if (mToken != null) {
                throw new IllegalStateException(
                        "attachToken() must be called at most once. token=" + token);
            }
            attachToWindowToken(token);
            mToken = token;
            mWindow.setToken(token);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Calls {@link InputMethodService#onBindInput()} when done.</p>
         */
        @MainThread
        @Override
        public void bindInput(InputBinding binding) {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.bindInput");
            mInputBinding = binding;
            mInputConnection = binding.getConnection();
            if (DEBUG) Log.v(TAG, "bindInput(): binding=" + binding
                    + " ic=" + mInputConnection);
            reportFullscreenMode();
            initialize();
            onBindInput();
            mConfigTracker.onBindInput(getResources());
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Calls {@link InputMethodService#onUnbindInput()} when done.</p>
         */
        @MainThread
        @Override
        public void unbindInput() {
            if (DEBUG) Log.v(TAG, "unbindInput(): binding=" + mInputBinding
                    + " ic=" + mInputConnection);
            // Unbind input is per process per display.
            onUnbindInput();
            mInputBinding = null;
            mInputConnection = null;

            if (mInkWindow != null) {
                finishStylusHandwriting();
                // free-up InkWindow surface after timeout.
                scheduleStylusWindowIdleTimeout();
            }
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void startInput(InputConnection ic, EditorInfo editorInfo) {
            if (DEBUG) Log.v(TAG, "startInput(): editor=" + editorInfo);
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.startInput");
            doStartInput(ic, editorInfo, false);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void restartInput(InputConnection ic, EditorInfo editorInfo) {
            if (DEBUG) Log.v(TAG, "restartInput(): editor=" + editorInfo);
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.restartInput");
            doStartInput(ic, editorInfo, true);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public final void dispatchStartInput(@Nullable InputConnection inputConnection,
                @NonNull IInputMethod.StartInputParams params) {
            mPrivOps.reportStartInputAsync(params.startInputToken);
            mNavigationBarController.onNavButtonFlagsChanged(params.navigationBarFlags);
            if (params.restarting) {
                restartInput(inputConnection, params.editorInfo);
            } else {
                startInput(inputConnection, params.editorInfo);
            }
            // Update the IME dispatcher last, so that the previously registered back callback
            // (if any) can be unregistered using the old dispatcher if {@link #doFinishInput()}
            // is called from {@link #startInput(InputConnection, EditorInfo)} or
            // {@link #restartInput(InputConnection, EditorInfo)}.
            mImeDispatcher = params.imeDispatcher;
            if (mWindow != null) {
                mWindow.getOnBackInvokedDispatcher().setImeOnBackInvokedDispatcher(
                        params.imeDispatcher);
            }
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public void onNavButtonFlagsChanged(@InputMethodNavButtonFlags int navButtonFlags) {
            mNavigationBarController.onNavButtonFlagsChanged(navButtonFlags);
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public void hideSoftInputWithToken(int flags, ResultReceiver resultReceiver,
                IBinder hideInputToken, @NonNull ImeTracker.Token statsToken) {
            mSystemCallingHideSoftInput = true;
            mCurHideInputToken = hideInputToken;
            mCurStatsToken = statsToken;
            try {
                hideSoftInput(flags, resultReceiver);
            } finally {
                mCurHideInputToken = null;
                mSystemCallingHideSoftInput = false;
            }
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            if (DEBUG) Log.v(TAG, "hideSoftInput()");

            final var statsToken = mCurStatsToken != null ? mCurStatsToken
                    : createStatsToken(false /* show */,
                            SoftInputShowHideReason.HIDE_SOFT_INPUT_LEGACY_DIRECT,
                            ImeTracker.isFromUser(mRootView));
            mCurStatsToken = null;

            // TODO(b/148086656): Disallow IME developers from calling InputMethodImpl methods.
            if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R
                    && !mSystemCallingHideSoftInput) {
                Log.e(TAG, "IME shouldn't call hideSoftInput on itself."
                        + " Use requestHideSelf(int) itself");
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_IME_HIDE_SOFT_INPUT);
                return;
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_HIDE_SOFT_INPUT);

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.hideSoftInput");
            ImeTracing.getInstance().triggerServiceDump(
                    "InputMethodService.InputMethodImpl#hideSoftInput", mDumper,
                    null /* icProto */);
            final boolean wasVisible = isInputViewShown();

            mShowInputFlags = 0;
            mShowInputRequested = false;
            mCurStatsToken = statsToken;
            hideWindow();
            final boolean isVisible = isInputViewShown();
            final boolean visibilityChanged = isVisible != wasVisible;
            if (resultReceiver != null) {
                resultReceiver.send(visibilityChanged
                        ? InputMethodManager.RESULT_HIDDEN
                        : (wasVisible ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                                : InputMethodManager.RESULT_UNCHANGED_HIDDEN), null);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            if (android.view.inputmethod.Flags.refactorInsetsController()) {
                // The hide request first finishes the animation and then proceeds to the server
                // side, finally reaching here, marking this the end state.
                ImeTracker.forLogging().onHidden(statsToken);
            }
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public void showSoftInputWithToken(@InputMethod.ShowFlags int flags,
                ResultReceiver resultReceiver, IBinder showInputToken,
                @NonNull ImeTracker.Token statsToken) {
            mSystemCallingShowSoftInput = true;
            mCurShowInputToken = showInputToken;
            mCurStatsToken = statsToken;
            try {
                showSoftInput(flags, resultReceiver);
            } finally {
                mCurShowInputToken = null;
                mSystemCallingShowSoftInput = false;
            }
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void showSoftInput(@InputMethod.ShowFlags int flags, ResultReceiver resultReceiver) {
            if (DEBUG) Log.v(TAG, "showSoftInput()");

            final var statsToken = mCurStatsToken != null ? mCurStatsToken
                    : createStatsToken(true /* show */,
                            SoftInputShowHideReason.SHOW_SOFT_INPUT_LEGACY_DIRECT,
                            ImeTracker.isFromUser(mRootView));
            mCurStatsToken = null;

            // TODO(b/148086656): Disallow IME developers from calling InputMethodImpl methods.
            if (getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.R
                    && !mSystemCallingShowSoftInput) {
                Log.e(TAG, "IME shouldn't call showSoftInput on itself."
                        + " Use requestShowSelf(int) itself");
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_IME_SHOW_SOFT_INPUT);
                return;
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_SHOW_SOFT_INPUT);

            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.showSoftInput");
            ImeTracing.getInstance().triggerServiceDump(
                    "InputMethodService.InputMethodImpl#showSoftInput", mDumper,
                    null /* icProto */);
            final boolean wasVisible = isInputViewShown();
            if (dispatchOnShowInputRequested(flags, false)) {
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE);
                mCurStatsToken = statsToken;
                showWindow(true /* showInput */);
            } else {
                ImeTracker.forLogging().onFailed(statsToken,
                        ImeTracker.PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE);
            }
            setImeWindowVisibility(computeImeWindowVis());

            final boolean isVisible = isInputViewShown();
            final boolean visibilityChanged = isVisible != wasVisible;
            if (resultReceiver != null) {
                resultReceiver.send(visibilityChanged
                        ? InputMethodManager.RESULT_SHOWN
                        : (wasVisible ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                                : InputMethodManager.RESULT_UNCHANGED_HIDDEN), null);
            }
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void updateEditorToolType(@ToolType int toolType) {
            updateEditorToolTypeInternal(toolType);
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void canStartStylusHandwriting(int requestId,
                @Nullable IConnectionlessHandwritingCallback connectionlessCallback,
                @Nullable CursorAnchorInfo cursorAnchorInfo,
                boolean isConnectionlessForDelegation) {
            if (DEBUG) Log.v(TAG, "canStartStylusHandwriting()");
            if (mHandwritingRequestId.isPresent()) {
                Log.d(TAG, "There is an ongoing Handwriting session. ignoring.");
                return;
            }
            if (!mInputStarted) {
                Log.d(TAG, "Input should have started before starting Stylus handwriting.");
                return;
            }
            maybeCreateAndInitInkWindow();
            if (!mOnPreparedStylusHwCalled) {
                // prepare hasn't been called by Stylus HOVER.
                onPrepareStylusHandwriting();
            }
            // reset flag as it's not relevant after onStartStylusHandwriting().
            mOnPreparedStylusHwCalled = false;
            if (connectionlessCallback != null) {
                if (onStartConnectionlessStylusHandwriting(
                        InputType.TYPE_CLASS_TEXT, cursorAnchorInfo)) {
                    mConnectionlessHandwritingCallback = connectionlessCallback;
                    mIsConnectionlessHandwritingForDelegation = isConnectionlessForDelegation;
                    cancelStylusWindowIdleTimeout();
                    mPrivOps.onStylusHandwritingReady(requestId, Process.myPid());
                } else {
                    Log.i(TAG, "IME is not ready "
                            + "or doesn't currently support connectionless handwriting");
                    try {
                        connectionlessCallback.onError(
                                CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Couldn't send connectionless handwriting error result", e);
                    }
                }
            } else if (onStartStylusHandwriting()) {
                cancelStylusWindowIdleTimeout();
                mPrivOps.onStylusHandwritingReady(requestId, Process.myPid());
            } else {
                Log.i(TAG, "IME is not ready. Can't start Stylus Handwriting");
                // TODO(b/210039666): see if it's valuable to propagate this back to IMM.
            }
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @MainThread
        @Override
        public void startStylusHandwriting(
                int requestId, @NonNull InputChannel channel,
                @NonNull List<MotionEvent> stylusEvents) {
            if (DEBUG) Log.v(TAG, "startStylusHandwriting()");
            Objects.requireNonNull(channel);
            Objects.requireNonNull(stylusEvents);

            if (mHandwritingRequestId.isPresent()) {
                return;
            }

            mHandwritingRequestId = OptionalInt.of(requestId);
            mShowInputRequested = false;

            mInkWindow.show();
            mSimultaneousStylusAndTouchEnabled =
                    com.android.input.flags.Flags.enableMultiDeviceInput();

            // deliver previous @param stylusEvents
            stylusEvents.forEach(this::deliverStylusHandwritingMotionEvent);

            // create receiver for channel
            mHandwritingEventReceiver = new InputEventReceiver(channel, Looper.getMainLooper()) {
                @Override
                public void onInputEvent(InputEvent event) {
                    boolean handled = false;
                    try {
                        if (!(event instanceof MotionEvent motionEvent)) {
                            return;
                        }
                        if (!motionEvent.isStylusPointer()) {
                            // Handwriting surface is touchable, we don't want these touch events
                            // to get to the IME.
                            return;
                        }
                        deliverStylusHandwritingMotionEvent(motionEvent);
                        scheduleHandwritingSessionTimeout();
                        handled = true;
                    } finally {
                        finishInputEvent(event, handled);
                    }
                }
            };
            scheduleHandwritingSessionTimeout();
        }

        private void deliverStylusHandwritingMotionEvent(MotionEvent motionEvent) {
            onStylusHandwritingMotionEvent(motionEvent);
            if (!mSimultaneousStylusAndTouchEnabled) {
                return;
            }
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_HOVER_ENTER:
                    // Consume and ignore all touches while stylus is down to prevent
                    // accidental touches from going to the app while writing.
                    mPrivOps.setHandwritingSurfaceNotTouchable(false);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_HOVER_EXIT:
                    // Go back to only consuming stylus events so that the user
                    // can continue to interact with the app using touch
                    // when the stylus is not down.
                    mPrivOps.setHandwritingSurfaceNotTouchable(true);
                    break;
            }
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void commitHandwritingDelegationTextIfAvailable() {
            InputMethodService.this.commitHandwritingDelegationTextIfAvailable();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void discardHandwritingDelegationText() {
            InputMethodService.this.discardHandwritingDelegationText();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void initInkWindow() {
            maybeCreateAndInitInkWindow();
            onPrepareStylusHandwriting();
            mOnPreparedStylusHwCalled = true;
        }

        /**
         * Create, attach token and layout Ink window if it wasn't already created.
         */
        private void maybeCreateAndInitInkWindow() {
            if (mInkWindow == null) {
                mInkWindow = new InkWindow(mWindow.getContext());
                mInkWindow.setToken(mToken);
            }
            mInkWindow.initOnly();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void finishStylusHandwriting() {
            InputMethodService.this.finishStylusHandwriting();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void removeStylusHandwritingWindow() {
            InputMethodService.this.finishAndRemoveStylusHandwritingWindow();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public void setStylusWindowIdleTimeoutForTest(@DurationMillisLong long timeout) {
            mStylusWindowIdleTimeoutForTest = timeout;
        }

        /**
         * {@inheritDoc}
         */
        @MainThread
        @Override
        public void changeInputMethodSubtype(InputMethodSubtype subtype) {
            dispatchOnCurrentInputMethodSubtypeChanged(subtype);
        }
    }

    /**
     * Called when Autofill is requesting an {@link InlineSuggestionsRequest} from the IME.
     *
     * <p>The Autofill Framework will first request the IME to create and send an
     * {@link InlineSuggestionsRequest} back. Once Autofill Framework receives a valid request and
     * also receives valid inline suggestions, they will be returned via
     * {@link #onInlineSuggestionsResponse(InlineSuggestionsResponse)}.</p>
     *
     * <p>IME Lifecycle - The request will wait to be created after inputStarted</p>
     *
     * <p>If the IME wants to support displaying inline suggestions, they must set
     * supportsInlineSuggestions in its XML and implement this method to return a valid
     * {@link InlineSuggestionsRequest}.</p>
     *
     * @param uiExtras the extras that contain the UI renderer related information
     * @return an {@link InlineSuggestionsRequest} to be sent to Autofill.
     */
    @Nullable
    public InlineSuggestionsRequest onCreateInlineSuggestionsRequest(@NonNull Bundle uiExtras) {
        return null;
    }

    /**
     * Called when Autofill responds back with {@link InlineSuggestionsResponse} containing
     * inline suggestions.
     *
     * <p>Should be implemented by subclasses.</p>
     *
     * @param response {@link InlineSuggestionsResponse} passed back by Autofill.
     * @return Whether the IME will use and render  the inline suggestions.
     */
    public boolean onInlineSuggestionsResponse(@NonNull InlineSuggestionsResponse response) {
        return false;
    }

    /**
     * Returns the {@link IBinder} input token from the host view root.
     */
    @Nullable
    private IBinder getHostInputToken() {
        ViewRootImpl viewRoot = null;
        if (mRootView != null) {
            viewRoot = mRootView.getViewRootImpl();
        }
        return viewRoot == null ? null : viewRoot.getInputToken();
    }

    private void scheduleImeSurfaceRemoval() {
        if (mShowInputRequested || mWindowVisible || mWindow == null
                || mImeSurfaceRemoverRunnable != null) {
            return;
        }
        if (mHandler == null) {
            mHandler = new Handler(getMainLooper());
        }

        if (mLastWasInFullscreenMode) {
            // Caching surface / delaying surface removal can cause mServedView to detach in certain
            // cases in RecyclerView (b/187772544).
            // TODO(b/188818557): Re-enable IME surface caching for fullscreen mode once detaching
            //  view issues is resolved in RecyclerView.
            removeImeSurface();
        } else {
            mImeSurfaceRemoverRunnable = () -> {
                removeImeSurface();
            };
            mHandler.postDelayed(mImeSurfaceRemoverRunnable, TIMEOUT_SURFACE_REMOVAL_MILLIS);
        }
    }

    private void removeImeSurface() {
        cancelImeSurfaceRemoval();
        // hiding a window removes its surface.
        if (mWindow != null) {
            mWindow.hide();
        }
    }

    private void cancelImeSurfaceRemoval() {
        if (mHandler != null && mImeSurfaceRemoverRunnable != null) {
            mHandler.removeCallbacks(mImeSurfaceRemoverRunnable);
        }
        mImeSurfaceRemoverRunnable = null;
    }

    /**
     * Sets the IME window visibility state.
     *
     * @param vis the IME window visibility state to be set.
     */
    private void setImeWindowVisibility(@ImeWindowVisibility int vis) {
        if (vis == mImeWindowVisibility) {
            return;
        }
        mImeWindowVisibility = vis;
        setImeWindowStatus(mImeWindowVisibility, mBackDisposition);
    }

    private void setImeWindowStatus(@ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition) {
        mPrivOps.setImeWindowStatusAsync(vis, backDisposition);
    }

    /** Set region of the keyboard to be avoided from back gesture */
    private void setImeExclusionRect(int visibleTopInsets) {
        View rootView = mInputFrame.getRootView();
        android.graphics.Insets systemGesture =
                rootView.getRootWindowInsets().getInsets(Type.systemGestures());
        ArrayList<Rect> exclusionRects = new ArrayList<>();
        exclusionRects.add(new Rect(0,
                visibleTopInsets,
                systemGesture.left,
                rootView.getHeight()));
        exclusionRects.add(new Rect(rootView.getWidth() - systemGesture.right,
                visibleTopInsets,
                rootView.getWidth(),
                rootView.getHeight()));
        rootView.setSystemGestureExclusionRects(exclusionRects);
    }

    private void updateEditorToolTypeInternal(int toolType) {
        if (Flags.useHandwritingListenerForTooltype()) {
            if (mInputEditorInfo != null) {
                mInputEditorInfo.setInitialToolType(toolType);
            }
        }
        onUpdateEditorToolType(toolType);
    }

    /**
     * Concrete implementation of
     * {@link AbstractInputMethodService.AbstractInputMethodSessionImpl} that provides
     * all of the standard behavior for an input method session.
     */
    public class InputMethodSessionImpl extends AbstractInputMethodSessionImpl {
        public void finishInput() {
            if (!isEnabled()) {
                return;
            }
            if (DEBUG) Log.v(TAG, "finishInput() in " + this);
            doFinishInput();
        }

        /**
         * Call {@link InputMethodService#onDisplayCompletions
         * InputMethodService.onDisplayCompletions()}.
         */
        public void displayCompletions(CompletionInfo[] completions) {
            if (!isEnabled()) {
                return;
            }
            mCurCompletions = completions;
            onDisplayCompletions(completions);
        }
        
        /**
         * Call {@link InputMethodService#onUpdateExtractedText
         * InputMethodService.onUpdateExtractedText()}.
         */
        public void updateExtractedText(int token, ExtractedText text) {
            if (!isEnabled()) {
                return;
            }
            onUpdateExtractedText(token, text);
        }
        
        /**
         * Call {@link InputMethodService#onUpdateSelection
         * InputMethodService.onUpdateSelection()}.
         */
        public void updateSelection(int oldSelStart, int oldSelEnd,
                int newSelStart, int newSelEnd,
                int candidatesStart, int candidatesEnd) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onUpdateSelection(oldSelStart, oldSelEnd,
                    newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        }

        @Override
        public void viewClicked(boolean focusChanged) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onViewClicked(focusChanged);
        }

        /**
         * Call {@link InputMethodService#onUpdateCursor
         * InputMethodService.onUpdateCursor()}.
         */
        public void updateCursor(Rect newCursor) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onUpdateCursor(newCursor);
        }
        
        /**
         * Call {@link InputMethodService#onAppPrivateCommand
         * InputMethodService.onAppPrivateCommand()}.
         */
        public void appPrivateCommand(String action, Bundle data) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onAppPrivateCommand(action, data);
        }
        
        /**
         * Handles a request to toggle the IME visibility.
         *
         * @deprecated Starting in {@link Build.VERSION_CODES#S} the system no longer invokes this
         * method, instead it explicitly shows or hides the IME. An {@code InputMethodService}
         * wishing to toggle its own visibility should instead invoke {@link
         * InputMethodService#requestShowSelf} or {@link InputMethodService#requestHideSelf}
         */
        @Deprecated
        public void toggleSoftInput(@InputMethodManager.ShowFlags int showFlags,
                @InputMethodManager.HideFlags int hideFlags) {
            InputMethodService.this.onToggleSoftInput(showFlags, hideFlags);
        }

        /**
         * Call {@link InputMethodService#onUpdateCursorAnchorInfo
         * InputMethodService.onUpdateCursorAnchorInfo()}.
         */
        public void updateCursorAnchorInfo(CursorAnchorInfo info) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onUpdateCursorAnchorInfo(info);
        }

        /**
         * Notify IME that surface can be now removed.
         * @hide
         */
        public final void removeImeSurface() {
            InputMethodService.this.scheduleImeSurfaceRemoval();
        }

        /**
         * {@inheritDoc}
         * @hide
         */
        @Override
        public final void invalidateInputInternal(@NonNull EditorInfo editorInfo,
                @NonNull IRemoteInputConnection inputConnection, int sessionId) {
            if (mStartedInputConnection instanceof RemoteInputConnection) {
                final RemoteInputConnection ric = (RemoteInputConnection) mStartedInputConnection;
                if (!ric.isSameConnection(inputConnection)) {
                    // This is not an error, and can be safely ignored.
                    if (DEBUG) {
                        Log.d(TAG, "ignoring invalidateInput() due to context mismatch.");
                    }
                    return;
                }
                editorInfo.makeCompatible(getApplicationInfo().targetSdkVersion);
                getInputMethodInternal().restartInput(new RemoteInputConnection(ric, sessionId),
                        editorInfo);
            }
        }
    }

    /**
     * Information about where interesting parts of the input method UI appear.
     */
    public static final class Insets {
        /**
         * This is the top part of the UI that is the main content.  It is
         * used to determine the basic space needed, to resize/pan the
         * application behind.  It is assumed that this inset does not
         * change very much, since any change will cause a full resize/pan
         * of the application behind.  This value is relative to the top edge
         * of the input method window.
         */
        public int contentTopInsets;
        
        /**
         * This is the top part of the UI that is visibly covering the
         * application behind it.  This provides finer-grained control over
         * visibility, allowing you to change it relatively frequently (such
         * as hiding or showing candidates) without disrupting the underlying
         * UI too much.  For example, this will never resize the application
         * UI, will only pan if needed to make the current focus visible, and
         * will not aggressively move the pan position when this changes unless
         * needed to make the focus visible.  This value is relative to the top edge
         * of the input method window.
         */
        public int visibleTopInsets;

        /**
         * This is the region of the UI that is touchable.  It is used when
         * {@link #touchableInsets} is set to {@link #TOUCHABLE_INSETS_REGION}.
         * The region should be specified relative to the origin of the window frame.
         */
        public final Region touchableRegion = new Region();

        /**
         * Option for {@link #touchableInsets}: the entire window frame
         * can be touched.
         */
        public static final int TOUCHABLE_INSETS_FRAME
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME;
        
        /**
         * Option for {@link #touchableInsets}: the area inside of
         * the content insets can be touched.
         */
        public static final int TOUCHABLE_INSETS_CONTENT
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_CONTENT;
        
        /**
         * Option for {@link #touchableInsets}: the area inside of
         * the visible insets can be touched.
         */
        public static final int TOUCHABLE_INSETS_VISIBLE
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;

        /**
         * Option for {@link #touchableInsets}: the region specified by
         * {@link #touchableRegion} can be touched.
         */
        public static final int TOUCHABLE_INSETS_REGION
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;

        /**
         * Determine which area of the window is touchable by the user.  May
         * be one of: {@link #TOUCHABLE_INSETS_FRAME},
         * {@link #TOUCHABLE_INSETS_CONTENT}, {@link #TOUCHABLE_INSETS_VISIBLE},
         * or {@link #TOUCHABLE_INSETS_REGION}.
         */
        public int touchableInsets;

        private void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);
            proto.write(CONTENT_TOP_INSETS, contentTopInsets);
            proto.write(VISIBLE_TOP_INSETS, visibleTopInsets);
            proto.write(TOUCHABLE_INSETS, touchableInsets);
            proto.write(TOUCHABLE_REGION, touchableRegion.toString());
            proto.end(token);
        }
    }

    /**
     * A {@link ContentObserver} to monitor {@link Settings.Secure#SHOW_IME_WITH_HARD_KEYBOARD}.
     *
     * <p>Note that {@link Settings.Secure#SHOW_IME_WITH_HARD_KEYBOARD} is not a public API.
     * Basically this functionality still needs to be considered as implementation details.</p>
     */
    @MainThread
    private static final class SettingsObserver extends ContentObserver {
        @Retention(RetentionPolicy.SOURCE)
        @IntDef({
                ShowImeWithHardKeyboardType.UNKNOWN,
                ShowImeWithHardKeyboardType.FALSE,
                ShowImeWithHardKeyboardType.TRUE,
        })
        private @interface ShowImeWithHardKeyboardType {
            int UNKNOWN = 0;
            int FALSE = 1;
            int TRUE = 2;
        }
        @ShowImeWithHardKeyboardType
        private int mShowImeWithHardKeyboard = ShowImeWithHardKeyboardType.UNKNOWN;

        private final InputMethodService mService;

        private SettingsObserver(InputMethodService service) {
            super(new Handler(service.getMainLooper()));
            mService = service;
        }

        /**
         * A factory method that internally enforces two-phase initialization to make sure that the
         * object reference will not be escaped until the object is properly constructed.
         *
         * <p>NOTE: Currently {@link SettingsObserver} is accessed only from main thread.  Hence
         * this enforcement of two-phase initialization may be unnecessary at the moment.</p>
         *
         * @param service {@link InputMethodService} that needs to receive the callback.
         * @return {@link SettingsObserver} that is already registered to
         * {@link android.content.ContentResolver}. The caller must call
         * {@link SettingsObserver#unregister()}.
         */
        public static SettingsObserver createAndRegister(InputMethodService service) {
            final SettingsObserver observer = new SettingsObserver(service);
            // The observer is properly constructed. Let's start accepting the event.
            service.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD),
                    false, observer);
            return observer;
        }

        void unregister() {
            mService.getContentResolver().unregisterContentObserver(this);
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        private boolean shouldShowImeWithHardKeyboard() {
            // Lazily initialize as needed.
            if (mShowImeWithHardKeyboard == ShowImeWithHardKeyboardType.UNKNOWN) {
                mShowImeWithHardKeyboard = Settings.Secure.getInt(mService.getContentResolver(),
                        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0 ?
                        ShowImeWithHardKeyboardType.TRUE : ShowImeWithHardKeyboardType.FALSE;
            }
            switch (mShowImeWithHardKeyboard) {
                case ShowImeWithHardKeyboardType.TRUE:
                    return true;
                case ShowImeWithHardKeyboardType.FALSE:
                    return false;
                default:
                    Log.e(TAG, "Unexpected mShowImeWithHardKeyboard=" + mShowImeWithHardKeyboard);
                    return false;
            }
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final Uri showImeWithHardKeyboardUri =
                    Settings.Secure.getUriFor(Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);
            if (showImeWithHardKeyboardUri.equals(uri)) {
                mShowImeWithHardKeyboard = Settings.Secure.getInt(mService.getContentResolver(),
                        Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD, 0) != 0 ?
                        ShowImeWithHardKeyboardType.TRUE : ShowImeWithHardKeyboardType.FALSE;
                // In Android M and prior, state change of
                // Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD has triggered
                // #onConfigurationChanged().  For compatibility reasons, we reset the internal
                // state as if configuration was changed.
                mService.resetStateForNewConfiguration();
            }
        }

        @Override
        public String toString() {
            return "SettingsObserver{mShowImeWithHardKeyboard=" + mShowImeWithHardKeyboard  + "}";
        }
    }
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private SettingsObserver mSettingsObserver;

    /**
     * You can call this to customize the theme used by your IME's window.
     * This theme should typically be one that derives from
     * {@link android.R.style#Theme_InputMethod}, which is the default theme
     * you will get.  This must be set before {@link #onCreate}, so you
     * will typically call it in your constructor with the resource ID
     * of your custom theme.
     */
    @Override
    public void setTheme(int theme) {
        if (mWindow != null) {
            throw new IllegalStateException("Must be called before onCreate()");
        }
        mTheme = theme;
    }

    /**
     * You can call this to try to enable accelerated drawing for your IME. This must be set before
     * {@link #onCreate()}, so you will typically call it in your constructor.  It is not always
     * possible to use hardware accelerated drawing in an IME (for example on low-end devices that
     * do not have the resources to support this), so the call {@code true} if it succeeds otherwise
     * {@code false} if you will need to draw in software.  You must be able to handle either case.
     *
     * <p>In API 21 and later, system may automatically enable hardware accelerated drawing for your
     * IME on capable devices even if this method is not explicitly called. Make sure that your IME
     * is able to handle either case.</p>
     *
     * @return {@code true} if accelerated drawing is successfully enabled otherwise {@code false}.
     *         On API 21 and later devices the return value is basically just a hint and your IME
     *         does not need to change the behavior based on the it
     * @deprecated Starting in API 21, hardware acceleration is always enabled on capable devices
     */
    @Deprecated
    public boolean enableHardwareAcceleration() {
        if (mWindow != null) {
            throw new IllegalStateException("Must be called before onCreate()");
        }
        return ActivityManager.isHighEndGfx();
    }

    @Override public void onCreate() {
        if (methodIsOverridden("onCreateInputMethodSessionInterface")
                && CompatChanges.isChangeEnabled(DISALLOW_INPUT_METHOD_INTERFACE_OVERRIDE)) {
            throw new LinkageError("InputMethodService#onCreateInputMethodSessionInterface()"
                    + " can no longer be overridden!");
        }
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.onCreate");
        mTheme = Resources.selectSystemTheme(mTheme,
                getApplicationInfo().targetSdkVersion,
                android.R.style.Theme_InputMethod,
                android.R.style.Theme_Holo_InputMethod,
                android.R.style.Theme_DeviceDefault_InputMethod,
                android.R.style.Theme_DeviceDefault_InputMethod);
        super.setTheme(mTheme);
        super.onCreate();
        mImm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mSettingsObserver = SettingsObserver.createAndRegister(this);
        // cache preference so we don't have to read ContentProvider when IME is requested to be
        // shown the first time (cold start).
        mSettingsObserver.shouldShowImeWithHardKeyboard();

        final boolean hideNavBarForKeyboard = getApplicationContext().getResources().getBoolean(
                com.android.internal.R.bool.config_hideNavBarForKeyboard);

        initConfigurationTracker();

        // TODO(b/111364446) Need to address context lifecycle issue if need to re-create
        // for update resources & configuration correctly when show soft input
        // in non-default display.
        mInflater = (LayoutInflater)getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.initSoftInputWindow");
        mWindow = new SoftInputWindow(this, mTheme, mDispatcherState);
        if (mImeDispatcher != null) {
            mWindow.getOnBackInvokedDispatcher()
                    .setImeOnBackInvokedDispatcher(mImeDispatcher);
        }
        mNavigationBarController.onSoftInputWindowCreated(mWindow);
        {
            final Window window = mWindow.getWindow();
            {
                final WindowManager.LayoutParams lp = window.getAttributes();
                lp.setTitle("InputMethod");
                lp.type = WindowManager.LayoutParams.TYPE_INPUT_METHOD;
                lp.width = WindowManager.LayoutParams.MATCH_PARENT;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.BOTTOM;
                lp.setFitInsetsTypes(statusBars() | navigationBars());
                lp.setFitInsetsSides(Side.all() & ~Side.BOTTOM);
                lp.receiveInsetsIgnoringZOrder = true;
                window.setAttributes(lp);
            }

            // For ColorView in DecorView to work, FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS needs to be set
            // by default (but IME developers can opt this out later if they want a new behavior).
            final int windowFlags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
            final int windowFlagsMask = windowFlags
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND;  // to be unset
            window.setFlags(windowFlags, windowFlagsMask);

            // Automotive devices may request the navigation bar to be hidden when the IME shows up
            // (controlled via config_hideNavBarForKeyboard) in order to maximize the visible
            // screen real estate. When this happens, the IME window should animate from the
            // bottom of the screen to reduce the jank that happens from the lack of synchronization
            // between the bottom system window and the IME window.
            if (hideNavBarForKeyboard) {
                window.setDecorFitsSystemWindows(false);
            }
        }

        initViews();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);

        mInlineSuggestionSessionController = new InlineSuggestionSessionController(
                this::onCreateInlineSuggestionsRequest, this::getHostInputToken,
                this::onInlineSuggestionsResponse);
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private void initConfigurationTracker() {
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        final ComponentName imeComponent = new ComponentName(
                getPackageName(), getClass().getName());
        final String imeId = imeComponent.flattenToShortString();
        final ServiceInfo si;
        try {
            si = getPackageManager().getServiceInfo(imeComponent,
                    PackageManager.ComponentInfoFlags.of(flags));
        } catch (PackageManager.NameNotFoundException e) {
            Log.wtf(TAG, "Unable to find input method " + imeId, e);
            return;
        }
        try (XmlResourceParser parser = si.loadXmlMetaData(getPackageManager(),
                InputMethod.SERVICE_META_DATA);
             TypedArray sa = getResources().obtainAttributes(Xml.asAttributeSet(parser),
                     com.android.internal.R.styleable.InputMethod)) {
            if (parser == null) {
                throw new XmlPullParserException(
                        "No " + InputMethod.SERVICE_META_DATA + " meta-data");
            }
            final int handledConfigChanges = sa.getInt(
                    com.android.internal.R.styleable.InputMethod_configChanges, 0);
            mConfigTracker.onInitialize(handledConfigChanges);
        } catch (Exception e) {
            Log.wtf(TAG, "Unable to load input method " + imeId, e);
        }
    }

    /**
     * This is a hook that subclasses can use to perform initialization of
     * their interface.  It is called for you prior to any of your UI objects
     * being created, both after the service is first created and after a
     * configuration change happens.
     */
    public void onInitializeInterface() {
        // Intentionally empty
    }

    void initialize() {
        if (!mInitialized) {
            mInitialized = true;
            onInitializeInterface();
        }
    }

    void initViews() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.initViews");
        mInitialized = false;
        mViewsCreated = false;
        mShowInputRequested = false;
        mShowInputFlags = 0;

        mThemeAttrs = obtainStyledAttributes(android.R.styleable.InputMethodService);
        mRootView = mInflater.inflate(
                com.android.internal.R.layout.input_method, null);
        mWindow.setContentView(mRootView);
        mRootView.getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsComputer);
        mFullscreenArea = mRootView.findViewById(com.android.internal.R.id.fullscreenArea);
        mExtractViewHidden = false;
        mExtractFrame = mRootView.findViewById(android.R.id.extractArea);
        mExtractView = null;
        mExtractEditText = null;
        mExtractAccessories = null;
        mExtractAction = null;
        mFullscreenApplied = false;

        mCandidatesFrame = mRootView.findViewById(android.R.id.candidatesArea);
        mInputFrame = mRootView.findViewById(android.R.id.inputArea);
        mInputView = null;
        mIsInputViewShown = false;

        mExtractFrame.setVisibility(View.GONE);
        mCandidatesVisibility = getCandidatesHiddenVisibility();
        mCandidatesFrame.setVisibility(mCandidatesVisibility);
        mInputFrame.setVisibility(View.GONE);
        mNavigationBarController.onViewInitialized();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @Override public void onDestroy() {
        mDestroyed = true;
        super.onDestroy();
        mRootView.getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mInsetsComputer);
        doFinishInput();
        mNavigationBarController.onDestroy();
        mWindow.dismissForDestroyIfNecessary();
        if (mSettingsObserver != null) {
            mSettingsObserver.unregister();
            mSettingsObserver = null;
        }
        if (mToken != null) {
            // This is completely optional, but allows us to show more explicit error messages
            // when IME developers are doing something unsupported.
            InputMethodPrivilegedOperationsRegistry.remove(mToken);
        }
        mImeDispatcher = null;
    }

    /**
     * Take care of handling configuration changes.  Subclasses of
     * InputMethodService generally don't need to deal directly with
     * this on their own; the standard implementation here takes care of
     * regenerating the input method UI as a result of the configuration
     * change, so you can rely on your {@link #onCreateInputView} and
     * other methods being called as appropriate due to a configuration change.
     * 
     * <p>When a configuration change does happen,
     * {@link #onInitializeInterface()} is guaranteed to be called the next
     * time prior to any of the other input or UI creation callbacks.  The
     * following will be called immediately depending if appropriate for current 
     * state: {@link #onStartInput} if input is active, and
     * {@link #onCreateInputView} and {@link #onStartInputView} and related
     * appropriate functions if the UI is displayed.
     * <p>Starting with {@link Build.VERSION_CODES#S}, IMEs can opt into handling configuration
     * changes themselves instead of being restarted with
     * {@link android.R.styleable#InputMethod_configChanges}.
     */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mConfigTracker.onConfigurationChanged(newConfig, this::resetStateForNewConfiguration);
    }

    private void resetStateForNewConfiguration() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.resetStateForNewConfiguration");
        boolean visible = mDecorViewVisible;
        int showFlags = mShowInputFlags;
        boolean showingInput = mShowInputRequested;
        CompletionInfo[] completions = mCurCompletions;
        mRootView.getViewTreeObserver().removeOnComputeInternalInsetsListener(mInsetsComputer);
        initViews();
        mInputViewStarted = false;
        mCandidatesViewStarted = false;
        if (mInputStarted) {
            doStartInput(getCurrentInputConnection(),
                    getCurrentInputEditorInfo(), true);
        }
        if (visible) {
            if (showingInput) {
                // If we were last showing the soft keyboard, try to do so again.
                if (dispatchOnShowInputRequested(showFlags, true)) {
                    showWindowWithToken(true /* showInput */,
                            SoftInputShowHideReason.RESET_NEW_CONFIGURATION);
                    if (completions != null) {
                        mCurCompletions = completions;
                        onDisplayCompletions(completions);
                    }
                } else {
                    hideWindowWithToken(SoftInputShowHideReason.RESET_NEW_CONFIGURATION);
                }
            } else if (mCandidatesVisibility == View.VISIBLE) {
                // If the candidates are currently visible, make sure the
                // window is shown for them.
                showWindowWithToken(false /* showInput */,
                        SoftInputShowHideReason.RESET_NEW_CONFIGURATION);
            } else {
                // Otherwise hide the window.
                hideWindowWithToken(SoftInputShowHideReason.RESET_NEW_CONFIGURATION);
            }
            // If user uses hard keyboard, IME button should always be shown.
            boolean showing = onEvaluateInputViewShown();
            setImeWindowVisibility(IME_ACTIVE | (showing ? IME_VISIBLE : 0));
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    /**
     * Implement to return our standard {@link InputMethodImpl}.
     *
     * @deprecated Overriding or calling this method is strongly discouraged. A future version of
     * Android will remove the ability to use this method. Use the callbacks on
     * {@link InputMethodService} as {@link InputMethodService#onBindInput()},
     * {@link InputMethodService#onUnbindInput()}, {@link InputMethodService#onWindowShown()},
     * {@link InputMethodService#onWindowHidden()}, etc.
     */
    @Deprecated
    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new InputMethodImpl();
    }
    
    /**
     * Implement to return our standard {@link InputMethodSessionImpl}.
     *
     * <p>IMEs targeting on Android U and above cannot override this method, or an
     * {@link LinkageError} would be thrown.</p>
     *
     * @deprecated Overriding or calling this method is strongly discouraged.
     * Most methods in {@link InputMethodSessionImpl} have corresponding callbacks.
     * Use {@link InputMethodService#onFinishInput()},
     * {@link InputMethodService#onDisplayCompletions(CompletionInfo[])},
     * {@link InputMethodService#onUpdateExtractedText(int, ExtractedText)},
     * {@link InputMethodService#onUpdateSelection(int, int, int, int, int, int)} instead.
     */
    @Deprecated
    @Override
    public AbstractInputMethodSessionImpl onCreateInputMethodSessionInterface() {
        return new InputMethodSessionImpl();
    }

    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }
    
    public Dialog getWindow() {
        return mWindow;
    }

    /**
     * Sets the disposition mode that indicates the expected affordance for the back button.
     *
     * <p>Keep in mind that specifying this flag does not change the the default behavior of
     * {@link #onKeyDown(int, KeyEvent)}.  It is IME developers' responsibility for making sure that
     * their custom implementation of {@link #onKeyDown(int, KeyEvent)} is consistent with the mode
     * specified to this API.</p>
     *
     * @see #getBackDisposition()
     * @param disposition disposition mode to be set
     */
    public void setBackDisposition(@BackDispositionMode int disposition) {
        if (disposition == mBackDisposition) {
            return;
        }
        if (disposition > BACK_DISPOSITION_MAX || disposition < BACK_DISPOSITION_MIN) {
            Log.e(TAG, "Invalid back disposition value (" + disposition + ") specified.");
            return;
        }
        mBackDisposition = disposition;
        setImeWindowStatus(mImeWindowVisibility, mBackDisposition);
    }

    /**
     * Retrieves the current disposition mode that indicates the expected back button affordance.
     *
     * @see #setBackDisposition(int)
     * @return currently selected disposition mode
     */
    @BackDispositionMode
    public int getBackDisposition() {
        return mBackDisposition;
    }

    /**
     * Return the maximum width, in pixels, available the input method.
     * Input methods are positioned at the bottom of the screen and, unless
     * running in fullscreen, will generally want to be as short as possible
     * so should compute their height based on their contents.  However, they
     * can stretch as much as needed horizontally.  The function returns to
     * you the maximum amount of space available horizontally, which you can
     * use if needed for UI placement.
     * 
     * <p>In many cases this is not needed, you can just rely on the normal
     * view layout mechanisms to position your views within the full horizontal
     * space given to the input method.
     * 
     * <p>Note that this value can change dynamically, in particular when the
     * screen orientation changes.
     */
    public int getMaxWidth() {
        final WindowManager windowManager = getSystemService(WindowManager.class);
        return WindowMetricsHelper.getBoundsExcludingNavigationBarAndCutout(
                windowManager.getCurrentWindowMetrics()).width();
    }
    
    /**
     * Return the currently active InputBinding for the input method, or
     * null if there is none.
     */
    public InputBinding getCurrentInputBinding() {
        return mInputBinding;
    }
    
    /**
     * Retrieve the currently active InputConnection that is bound to
     * the input method, or null if there is none.
     */
    public InputConnection getCurrentInputConnection() {
        InputConnection ic = mStartedInputConnection;
        if (ic != null) {
            return ic;
        }
        return mInputConnection;
    }

    /**
     * Force switch to the last used input method and subtype. If the last input method didn't have
     * any subtypes, the framework will simply switch to the last input method with no subtype
     * specified.
     * @return true if the current input method and subtype was successfully switched to the last
     * used input method and subtype.
     */
    public final boolean switchToPreviousInputMethod() {
        return mPrivOps.switchToPreviousInputMethod();
    }

    /**
     * Force switch to the next input method and subtype. If there is no IME enabled except
     * current IME and subtype, do nothing.
     * @param onlyCurrentIme if true, the framework will find the next subtype which
     * belongs to the current IME
     * @return true if the current input method and subtype was successfully switched to the next
     * input method and subtype.
     */
    public final boolean switchToNextInputMethod(boolean onlyCurrentIme) {
        return mPrivOps.switchToNextInputMethod(onlyCurrentIme);
    }

    /**
     * Returns true if the current IME needs to offer the users ways to switch to a next input
     * method (e.g. a globe key.).
     * When an IME sets supportsSwitchingToNextInputMethod and this method returns true,
     * the IME has to offer ways to to invoke {@link #switchToNextInputMethod} accordingly.
     * <p> Note that the system determines the most appropriate next input method
     * and subtype in order to provide the consistent user experience in switching
     * between IMEs and subtypes.
     */
    public final boolean shouldOfferSwitchingToNextInputMethod() {
        return mPrivOps.shouldOfferSwitchingToNextInputMethod();
    }

    public boolean getCurrentInputStarted() {
        return mInputStarted;
    }
    
    public EditorInfo getCurrentInputEditorInfo() {
        return mInputEditorInfo;
    }

    private void reportFullscreenMode() {
        mPrivOps.reportFullscreenModeAsync(mIsFullscreen);
    }

    /**
     * Re-evaluate whether the input method should be running in fullscreen
     * mode, and update its UI if this has changed since the last time it
     * was evaluated.  This will call {@link #onEvaluateFullscreenMode()} to
     * determine whether it should currently run in fullscreen mode.  You
     * can use {@link #isFullscreenMode()} to determine if the input method
     * is currently running in fullscreen mode.
     */
    public void updateFullscreenMode() {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.updateFullscreenMode");
        boolean isFullscreen = mShowInputRequested && onEvaluateFullscreenMode();
        boolean changed = mLastShowInputRequested != mShowInputRequested;
        if (mIsFullscreen != isFullscreen || !mFullscreenApplied) {
            changed = true;
            mIsFullscreen = isFullscreen;
            reportFullscreenMode();
            mFullscreenApplied = true;
            initialize();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    mFullscreenArea.getLayoutParams();
            if (isFullscreen) {
                mFullscreenArea.setBackgroundDrawable(mThemeAttrs.getDrawable(
                        com.android.internal.R.styleable.InputMethodService_imeFullscreenBackground));
                lp.height = 0;
                lp.weight = 1;
            } else {
                mFullscreenArea.setBackgroundDrawable(null);
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                lp.weight = 0;
            }
            ((ViewGroup)mFullscreenArea.getParent()).updateViewLayout(
                    mFullscreenArea, lp);
            if (isFullscreen) {
                if (mExtractView == null) {
                    View v = onCreateExtractTextView();
                    if (v != null) {
                        setExtractView(v);
                    }
                }
                startExtractingText(false);
            }
            updateExtractFrameVisibility();
        }
        
        if (changed) {
            onConfigureWindow(mWindow.getWindow(), isFullscreen, !mShowInputRequested);
            mLastShowInputRequested = mShowInputRequested;
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }
    
    /**
     * Update the given window's parameters for the given mode.  This is called
     * when the window is first displayed and each time the fullscreen or
     * candidates only mode changes.
     * 
     * <p>The default implementation makes the layout for the window
     * MATCH_PARENT x MATCH_PARENT when in fullscreen mode, and
     * MATCH_PARENT x WRAP_CONTENT when in non-fullscreen mode.
     * 
     * @param win The input method's window.
     * @param isFullscreen If true, the window is running in fullscreen mode
     * and intended to cover the entire application display.
     * @param isCandidatesOnly If true, the window is only showing the
     * candidates view and none of the rest of its UI.  This is mutually
     * exclusive with fullscreen mode.
     */
    public void onConfigureWindow(Window win, boolean isFullscreen,
            boolean isCandidatesOnly) {
        final int currentHeight = mWindow.getWindow().getAttributes().height;
        final int newHeight = isFullscreen ? MATCH_PARENT : WRAP_CONTENT;
        if (mIsInputViewShown && currentHeight != newHeight) {
            if (DEBUG) {
                Log.w(TAG,"Window size has been changed. This may cause jankiness of resizing "
                        + "window: " + currentHeight + " -> " + newHeight);
            }
        }
        mWindow.getWindow().setLayout(MATCH_PARENT, newHeight);
    }
    
    /**
     * Return whether the input method is <em>currently</em> running in
     * fullscreen mode.  This is the mode that was last determined and
     * applied by {@link #updateFullscreenMode()}.
     */
    public boolean isFullscreenMode() {
        return mIsFullscreen;
    }
    
    /**
     * Override this to control when the input method should run in
     * fullscreen mode.  The default implementation runs in fullsceen only
     * when the screen is in landscape mode.  If you change what
     * this returns, you will need to call {@link #updateFullscreenMode()}
     * yourself whenever the returned value may have changed to have it
     * re-evaluated and applied.
     */
    public boolean onEvaluateFullscreenMode() {
        Configuration config = getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return false;
        }
        if (mInputEditorInfo != null
                && ((mInputEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0
                // If app window has portrait orientation, regardless of what display orientation
                // is, IME shouldn't use fullscreen-mode.
                || (mInputEditorInfo.internalImeOptions
                        & EditorInfo.IME_INTERNAL_FLAG_APP_WINDOW_PORTRAIT) != 0)) {
            return false;
        }
        return true;
    }

    /**
     * Controls the visibility of the extracted text area.  This only applies
     * when the input method is in fullscreen mode, and thus showing extracted
     * text.  When false, the extracted text will not be shown, allowing some
     * of the application to be seen behind.  This is normally set for you
     * by {@link #onUpdateExtractingVisibility}.  This controls the visibility
     * of both the extracted text and candidate view; the latter since it is
     * not useful if there is no text to see.
     */
    public void setExtractViewShown(boolean shown) {
        if (mExtractViewHidden == shown) {
            mExtractViewHidden = !shown;
            updateExtractFrameVisibility();
        }
    }
    
    /**
     * Return whether the fullscreen extract view is shown.  This will only
     * return true if {@link #isFullscreenMode()} returns true, and in that
     * case its value depends on the last call to
     * {@link #setExtractViewShown(boolean)}.  This effectively lets you
     * determine if the application window is entirely covered (when this
     * returns true) or if some part of it may be shown (if this returns
     * false, though if {@link #isFullscreenMode()} returns true in that case
     * then it is probably only a sliver of the application).
     */
    public boolean isExtractViewShown() {
        return mIsFullscreen && !mExtractViewHidden;
    }
    
    void updateExtractFrameVisibility() {
        final int vis;
        updateCandidatesVisibility(mCandidatesVisibility == View.VISIBLE);

        if (isFullscreenMode()) {
            vis = mExtractViewHidden ? View.INVISIBLE : View.VISIBLE;
            // "vis" should be applied for the extract frame as well in the fullscreen mode.
            mExtractFrame.setVisibility(vis);
        } else {
            // mFullscreenArea visibility will according the candidate frame visibility once the
            // extract frame is gone.
            vis = mCandidatesVisibility;
            mExtractFrame.setVisibility(View.GONE);
        }

        if (mDecorViewWasVisible && mFullscreenArea.getVisibility() != vis) {
            int animRes = mThemeAttrs.getResourceId(vis == View.VISIBLE
                    ? com.android.internal.R.styleable.InputMethodService_imeExtractEnterAnimation
                    : com.android.internal.R.styleable.InputMethodService_imeExtractExitAnimation,
                    0);
            if (animRes != 0) {
                mFullscreenArea.startAnimation(AnimationUtils.loadAnimation(
                        this, animRes));
            }
        }
        mFullscreenArea.setVisibility(vis);
    }
    
    /**
     * Compute the interesting insets into your UI.  The default implementation
     * uses the top of the candidates frame for the visible insets, and the
     * top of the input frame for the content insets.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_VISIBLE}.
     * 
     * <p>Note that this method is not called when
     * {@link #isExtractViewShown} returns true, since
     * in that case the application is left as-is behind the input method and
     * not impacted by anything in its UI.
     * 
     * @param outInsets Fill in with the current UI insets.
     */
    public void onComputeInsets(Insets outInsets) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.onComputeInsets");
        int[] loc = mTmpLocation;
        if (mInputFrame.getVisibility() == View.VISIBLE) {
            mInputFrame.getLocationInWindow(loc);
        } else {
            View decor = getWindow().getWindow().getDecorView();
            loc[1] = decor.getHeight();
        }
        if (isFullscreenMode()) {
            // In fullscreen mode, we never resize the underlying window.
            View decor = getWindow().getWindow().getDecorView();
            outInsets.contentTopInsets = decor.getHeight();
        } else {
            outInsets.contentTopInsets = loc[1];
        }
        if (mCandidatesFrame.getVisibility() == View.VISIBLE) {
            mCandidatesFrame.getLocationInWindow(loc);
        }
        outInsets.visibleTopInsets = loc[1];
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE;
        outInsets.touchableRegion.setEmpty();
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }
    
    /**
     * Re-evaluate whether the soft input area should currently be shown, and
     * update its UI if this has changed since the last time it
     * was evaluated.  This will call {@link #onEvaluateInputViewShown()} to
     * determine whether the input view should currently be shown.  You
     * can use {@link #isInputViewShown()} to determine if the input view
     * is currently shown.
     */
    public void updateInputViewShown() {
        boolean isShown = mShowInputRequested && onEvaluateInputViewShown();
        if (mIsInputViewShown != isShown && mDecorViewVisible) {
            mIsInputViewShown = isShown;
            mInputFrame.setVisibility(isShown ? View.VISIBLE : View.GONE);
            if (mInputView == null) {
                initialize();
                View v = onCreateInputView();
                if (v != null) {
                    setInputView(v);
                }
            }
        }
    }
    
    /**
     * Returns true if we have been asked to show our input view.
     */
    public boolean isShowInputRequested() {
        return mShowInputRequested;
    }

    /**
     * Return whether the soft input view is <em>currently</em> shown to the
     * user.  This is the state that was last determined and
     * applied by {@link #updateInputViewShown()}.
     */
    public boolean isInputViewShown() {
        return mDecorViewVisible;
    }

    /**
     * Override this to control when the soft input area should be shown to the user.  The default
     * implementation returns {@code false} when there is no hard keyboard or the keyboard is hidden
     * unless the user shows an intention to use software keyboard.  If you change what this
     * returns, you will need to call {@link #updateInputViewShown()} yourself whenever the returned
     * value may have changed to have it re-evaluated and applied.
     *
     * <p>When you override this method, it is recommended to call
     * {@code super.onEvaluateInputViewShown()} and return {@code true} when {@code true} is
     * returned.</p>
     */
    @CallSuper
    public boolean onEvaluateInputViewShown() {
        if (mSettingsObserver == null) {
            Log.w(TAG, "onEvaluateInputViewShown: mSettingsObserver must not be null here.");
            return false;
        }
        if (mSettingsObserver.shouldShowImeWithHardKeyboard()) {
            return true;
        }
        Configuration config = getResources().getConfiguration();
        return config.keyboard == Configuration.KEYBOARD_NOKEYS
                || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES;
    }

    /**
     * Controls the visibility of the candidates display area.  By default
     * it is hidden.
     */
    public void setCandidatesViewShown(boolean shown) {
        updateCandidatesVisibility(shown);
        if (!mShowInputRequested && mDecorViewVisible != shown) {
            // If we are being asked to show the candidates view while the app
            // has not asked for the input view to be shown, then we need
            // to update whether the window is shown.
            if (shown) {
                showWindowWithToken(false /* showInput */,
                        SoftInputShowHideReason.UPDATE_CANDIDATES_VIEW_VISIBILITY);
            } else {
                hideWindowWithToken(
                        SoftInputShowHideReason.UPDATE_CANDIDATES_VIEW_VISIBILITY);
            }
        }
    }

    void updateCandidatesVisibility(boolean shown) {
        int vis = shown ? View.VISIBLE : getCandidatesHiddenVisibility();
        if (mCandidatesVisibility != vis) {
            mCandidatesFrame.setVisibility(vis);
            mCandidatesVisibility = vis;
        }
    }

    /**
     * Returns the visibility mode (either {@link View#INVISIBLE View.INVISIBLE}
     * or {@link View#GONE View.GONE}) of the candidates view when it is not
     * shown.  The default implementation returns GONE when
     * {@link #isExtractViewShown} returns true,
     * otherwise INVISIBLE.  Be careful if you change this to return GONE in
     * other situations -- if showing or hiding the candidates view causes
     * your window to resize, this can cause temporary drawing artifacts as
     * the resize takes place.
     */
    public int getCandidatesHiddenVisibility() {
        return isExtractViewShown() ? View.GONE : View.INVISIBLE;
    }

    public void showStatusIcon(@DrawableRes int iconResId) {
        mStatusIcon = iconResId;
        mPrivOps.updateStatusIconAsync(getPackageName(), iconResId);
    }

    public void hideStatusIcon() {
        mStatusIcon = 0;
        mPrivOps.updateStatusIconAsync(null, 0);
    }

    /**
     * Force switch to a new input method, as identified by <var>id</var>.  This
     * input method will be destroyed, and the requested one started on the
     * current input field.
     * 
     * @param id Unique identifier of the new input method to start.
     * @throws IllegalArgumentException if the input method is unknown or filtered
     * by the rules of <a href="/training/basics/intents/package-visibility">package visibility</a>.
     */
    public void switchInputMethod(String id) {
        mPrivOps.setInputMethod(id);
    }

    /**
     * Force switch to a new input method, as identified by {@code id}.  This
     * input method will be destroyed, and the requested one started on the
     * current input field.
     *
     * @param id Unique identifier of the new input method to start.
     * @param subtype The new subtype of the new input method to be switched to.
     * @throws IllegalArgumentException if the input method is unknown or filtered
     * by the rules of <a href="/training/basics/intents/package-visibility">package visibility</a>.
     */
    public final void switchInputMethod(String id, InputMethodSubtype subtype) {
        mPrivOps.setInputMethodAndSubtype(id, subtype);
    }

    public void setExtractView(View view) {
        mExtractFrame.removeAllViews();
        mExtractFrame.addView(view, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        mExtractView = view;
        if (view != null) {
            mExtractEditText = view.findViewById(
                    com.android.internal.R.id.inputExtractEditText);
            mExtractEditText.setIME(this);
            mExtractAction = view.findViewById(
                    com.android.internal.R.id.inputExtractAction);
            if (mExtractAction != null) {
                mExtractAccessories = view.findViewById(
                        com.android.internal.R.id.inputExtractAccessories);
            }
            startExtractingText(false);
        } else {
            mExtractEditText = null;
            mExtractAccessories = null;
            mExtractAction = null;
        }
    }

    /**
     * Replaces the current candidates view with a new one.  You only need to
     * call this when dynamically changing the view; normally, you should
     * implement {@link #onCreateCandidatesView()} and create your view when
     * first needed by the input method.
     */
    public void setCandidatesView(View view) {
        mCandidatesFrame.removeAllViews();
        mCandidatesFrame.addView(view, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    /**
     * Replaces the current input view with a new one.  You only need to
     * call this when dynamically changing the view; normally, you should
     * implement {@link #onCreateInputView()} and create your view when
     * first needed by the input method.
     */
    public void setInputView(View view) {
        mInputFrame.removeAllViews();
        mInputFrame.addView(view, new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        mInputView = view;
    }

    /**
     * Called by the framework to create the layout for showing extracted text.
     * Only called when in fullscreen mode.  The returned view hierarchy must
     * have an {@link ExtractEditText} whose ID is 
     * {@link android.R.id#inputExtractEditText}, with action ID
     * {@link android.R.id#inputExtractAction} and accessories ID
     * {@link android.R.id#inputExtractAccessories}.
     */
    public View onCreateExtractTextView() {
        return mInflater.inflate(
                com.android.internal.R.layout.input_method_extract_view, null);
    }
    
    /**
     * Create and return the view hierarchy used to show candidates.  This will
     * be called once, when the candidates are first displayed.  You can return
     * null to have no candidates view; the default implementation returns null.
     * 
     * <p>To control when the candidates view is displayed, use
     * {@link #setCandidatesViewShown(boolean)}.
     * To change the candidates view after the first one is created by this
     * function, use {@link #setCandidatesView(View)}.
     */
    public View onCreateCandidatesView() {
        return null;
    }
    
    /**
     * Create and return the view hierarchy used for the input area (such as
     * a soft keyboard).  This will be called once, when the input area is
     * first displayed.  You can return null to have no input area; the default
     * implementation returns null.
     * 
     * <p>To control when the input view is displayed, implement
     * {@link #onEvaluateInputViewShown()}.
     * To change the input view after the first one is created by this
     * function, use {@link #setInputView(View)}.
     */
    public View onCreateInputView() {
        return null;
    }
    
    /**
     * Called when the input view is being shown and input has started on
     * a new editor.  This will always be called after {@link #onStartInput},
     * allowing you to do your general setup there and just view-specific
     * setup here.  You are guaranteed that {@link #onCreateInputView()} will
     * have been called some time before this function is called.
     * 
     * @param editorInfo Description of the type of text being edited.
     * @param restarting Set to true if we are restarting input on the
     * same text field as before.
     */
    public void onStartInputView(EditorInfo editorInfo, boolean restarting) {
        // Intentionally empty
    }
    
    /**
     * Called when the input view is being hidden from the user.  This will
     * be called either prior to hiding the window, or prior to switching to
     * another target for editing.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     * 
     * @param finishingInput If true, {@link #onFinishInput} will be
     * called immediately after.
     */
    public void onFinishInputView(boolean finishingInput) {
        if (!finishingInput) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }
    
    /**
     * Called when only the candidates view has been shown for showing
     * processing as the user enters text through a hard keyboard.
     * This will always be called after {@link #onStartInput},
     * allowing you to do your general setup there and just view-specific
     * setup here.  You are guaranteed that {@link #onCreateCandidatesView()}
     * will have been called some time before this function is called.
     * 
     * <p>Note that this will <em>not</em> be called when the input method
     * is running in full editing mode, and thus receiving
     * {@link #onStartInputView} to initiate that operation.  This is only
     * for the case when candidates are being shown while the input method
     * editor is hidden but wants to show its candidates UI as text is
     * entered through some other mechanism.
     * 
     * @param editorInfo Description of the type of text being edited.
     * @param restarting Set to true if we are restarting input on the
     * same text field as before.
     */
    public void onStartCandidatesView(EditorInfo editorInfo, boolean restarting) {
        // Intentionally empty
    }
    
    /**
     * Called when the candidates view is being hidden from the user.  This will
     * be called either prior to hiding the window, or prior to switching to
     * another target for editing.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     * 
     * @param finishingInput If true, {@link #onFinishInput} will be
     * called immediately after.
     */
    public void onFinishCandidatesView(boolean finishingInput) {
        if (!finishingInput) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }

    /**
     * Called to prepare stylus handwriting.
     * The system calls this before the {@link #onStartStylusHandwriting} request.
     *
     * <p>Note: The system tries to call this as early as possible, when it detects that
     * handwriting stylus input is imminent. However, that a subsequent call to
     * {@link #onStartStylusHandwriting} actually happens is not guaranteed.</p>
     */
    public void onPrepareStylusHandwriting() {
        // Intentionally empty
    }

    /**
     * Called when an app requests stylus handwriting
     * {@link InputMethodManager#startStylusHandwriting(View)}.
     *
     * This will always be preceded by {@link #onStartInput(EditorInfo, boolean)} for the
     * {@link EditorInfo} and {@link InputConnection} for which stylus handwriting is being
     * requested.
     *
     * If the IME supports handwriting for the current input, it should return {@code true},
     * ensure its inking views are attached to the {@link #getStylusHandwritingWindow()}, and handle
     * stylus input received from {@link #onStylusHandwritingMotionEvent(MotionEvent)} on the
     * {@link #getStylusHandwritingWindow()} via {@link #getCurrentInputConnection()}.
     * @return {@code true} if IME can honor the request, {@code false} if IME cannot at this time.
     */
    public boolean onStartStylusHandwriting() {
        // Intentionally empty
        return false;
    }

    /**
     * Called when an app requests to start a connectionless stylus handwriting session using one of
     * {@link InputMethodManager#startConnectionlessStylusHandwriting(View, CursorAnchorInfo,
     * Executor, ConnectionlessHandwritingCallback)}, {@link
     * InputMethodManager#startConnectionlessStylusHandwritingForDelegation(View, CursorAnchorInfo,
     * Executor, ConnectionlessHandwritingCallback)}, or {@link
     * InputMethodManager#startConnectionlessStylusHandwritingForDelegation(View, CursorAnchorInfo,
     * String, Executor, ConnectionlessHandwritingCallback)}.
     *
     * <p>A connectionless stylus handwriting session differs from a regular session in that an
     * input connection is not used to communicate with a text editor. Instead, the recognised text
     * is delivered when the IME finishes the connectionless session using {@link
     * #finishConnectionlessStylusHandwriting(CharSequence)}.
     *
     * <p>If the IME can start the connectionless handwriting session, it should return {@code
     * true}, ensure its inking views are attached to the {@link #getStylusHandwritingWindow()}, and
     * handle stylus input received from {@link #onStylusHandwritingMotionEvent(MotionEvent)} on the
     * {@link #getStylusHandwritingWindow()}.
     */
    @FlaggedApi(FLAG_CONNECTIONLESS_HANDWRITING)
    public boolean onStartConnectionlessStylusHandwriting(
            int inputType, @Nullable CursorAnchorInfo cursorAnchorInfo) {
        // Intentionally empty
        return false;
    }

    /**
     * Called after {@link #onStartStylusHandwriting()} returns {@code true} for every Stylus
     * {@link MotionEvent}.
     * By default, this method forwards all {@link MotionEvent}s to the
     * {@link #getStylusHandwritingWindow()} once its visible, however IME can override it to
     * receive them sooner.
     * @param motionEvent {@link MotionEvent} from stylus.
     */
    public void onStylusHandwritingMotionEvent(@NonNull MotionEvent motionEvent) {
        if (mInkWindow != null && mInkWindow.isInkViewVisible()) {
            mInkWindow.dispatchHandwritingEvent(motionEvent);
        } else {
            if (mPendingEvents == null) {
                mPendingEvents = new RingBuffer(MotionEvent.class, MAX_EVENTS_BUFFER);
            }
            mPendingEvents.append(motionEvent);
            if (mInkWindow != null) {
                mInkWindow.setInkViewVisibilityListener(() -> {
                    if (mPendingEvents != null && !mPendingEvents.isEmpty()) {
                        for (MotionEvent event : mPendingEvents.toArray()) {
                            if (mInkWindow == null) {
                                break;
                            }
                            mInkWindow.dispatchHandwritingEvent(event);
                        }
                        mPendingEvents.clear();
                    }
                });
            }
        }

        // Create a stylus window idle-timeout after which InkWindow is removed.
        if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
            scheduleStylusWindowIdleTimeout();
        }
    }

    /**
     * Called when the current stylus handwriting session was finished (either by the system or
     * via {@link #finishStylusHandwriting()}.
     *
     * When this is called, the ink window has been made invisible, and the IME no longer
     * intercepts handwriting-related {@code MotionEvent}s.
     */
    public void onFinishStylusHandwriting() {
        // Intentionally empty
    }

    /**
     * Returns the stylus handwriting inking window.
     * IMEs supporting stylus input are expected to attach their inking views to this
     * window (e.g. with {@link Window#setContentView(View)} )). Handwriting-related
     * {@link MotionEvent}s are dispatched to the attached view hierarchy.
     *
     * Note: This returns {@code null} if IME doesn't support stylus handwriting
     *   i.e. if {@link InputMethodInfo#supportsStylusHandwriting()} is false.
     *   This method should be called after {@link #onStartStylusHandwriting()}.
     * @see #onStartStylusHandwriting()
     */
    @Nullable
    public final Window getStylusHandwritingWindow() {
        return mInkWindow;
    }

    /**
     * Finish the current stylus handwriting session.
     *
     * <p>This dismisses the {@link #getStylusHandwritingWindow ink window} and stops intercepting
     * stylus {@code MotionEvent}s.
     *
     * <p>Connectionless handwriting sessions should be finished using {@link
     * #finishConnectionlessStylusHandwriting(CharSequence)}.
     *
     * <p>Note for IME developers: Call this method at any time to finish the current handwriting
     * session. Generally, this should be invoked after a short timeout, giving the user enough time
     * to start the next stylus stroke, if any. By default, system will time-out after few seconds.
     * To override default timeout, use {@link #setStylusHandwritingSessionTimeout(Duration)}.
     *
     * <p>Handwriting session will be finished by framework on next {@link #onFinishInput()}.
     */
    public final void finishStylusHandwriting() {
        if (DEBUG) Log.v(TAG, "finishStylusHandwriting()");
        if (mInkWindow == null) {
            return;
        }
        if (!mHandwritingRequestId.isPresent()) {
            return;
        }
        if (mHandler != null && mFinishHwRunnable != null) {
            mHandler.removeCallbacks(mFinishHwRunnable);
        }
        mFinishHwRunnable = null;

        final int requestId = mHandwritingRequestId.getAsInt();
        mHandwritingRequestId = OptionalInt.empty();

        mHandwritingEventReceiver.dispose();
        mHandwritingEventReceiver = null;
        mInkWindow.hide(false /* remove */);

        if (mConnectionlessHandwritingCallback != null) {
            Log.i(TAG, "Connectionless handwriting session did not complete successfully");
            try {
                mConnectionlessHandwritingCallback.onError(CONNECTIONLESS_HANDWRITING_ERROR_OTHER);
            } catch (RemoteException e) {
                Log.e(TAG, "Couldn't send connectionless handwriting error result", e);
            }
            mConnectionlessHandwritingCallback = null;
        }
        mIsConnectionlessHandwritingForDelegation = false;

        mPrivOps.resetStylusHandwriting(requestId);
        mOnPreparedStylusHwCalled = false;
        onFinishStylusHandwriting();
    }

    /**
     * Finishes the current connectionless stylus handwriting session and delivers the result.
     *
     * <p>This dismisses the {@link #getStylusHandwritingWindow ink window} and stops intercepting
     * stylus {@code MotionEvent}s.
     *
     * <p>Note for IME developers: Call this method at any time to finish the current handwriting
     * session. Generally, this should be invoked after a short timeout, giving the user enough time
     * to start the next stylus stroke, if any. By default, system will time-out after few seconds.
     * To override default timeout, use {@link #setStylusHandwritingSessionTimeout(Duration)}.
     */
    @FlaggedApi(FLAG_CONNECTIONLESS_HANDWRITING)
    public final void finishConnectionlessStylusHandwriting(@Nullable CharSequence text) {
        if (DEBUG) Log.v(TAG, "finishConnectionlessStylusHandwriting()");
        if (mConnectionlessHandwritingCallback != null) {
            try {
                if (!TextUtils.isEmpty(text)) {
                    mConnectionlessHandwritingCallback.onResult(text);
                    if (mIsConnectionlessHandwritingForDelegation) {
                        mHandwritingDelegationText = text;
                    }
                } else {
                    mConnectionlessHandwritingCallback.onError(
                            CONNECTIONLESS_HANDWRITING_ERROR_NO_TEXT_RECOGNIZED);
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Couldn't send connectionless handwriting result", e);
            }
            mConnectionlessHandwritingCallback = null;
        }
        finishStylusHandwriting();
    }

    private void commitHandwritingDelegationTextIfAvailable() {
        if (!TextUtils.isEmpty(mHandwritingDelegationText)) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                // Place cursor after inserted text.
                ic.commitText(mHandwritingDelegationText, /* newCursorPosition= */ 1);
            }
        }
        mHandwritingDelegationText = null;
    }

    private void discardHandwritingDelegationText() {
        mHandwritingDelegationText = null;
    }

    /**
     * Remove Stylus handwriting window.
     * Typically, this is called when {@link InkWindow} should no longer be holding a surface in
     * memory.
     */
    private void finishAndRemoveStylusHandwritingWindow() {
        cancelStylusWindowIdleTimeout();
        mOnPreparedStylusHwCalled = false;
        mStylusWindowIdleTimeoutRunnable = null;
        if (mInkWindow != null) {
            if (mHandwritingRequestId.isPresent()) {
                // if handwriting session is still ongoing. This shouldn't happen.
                finishStylusHandwriting();
            }
            mInkWindow.hide(true /* remove */);
            mInkWindow.destroy();
            mInkWindow = null;
        }
    }

    private void cancelStylusWindowIdleTimeout() {
        if (mStylusWindowIdleTimeoutRunnable != null && mHandler != null) {
            mHandler.removeCallbacks(mStylusWindowIdleTimeoutRunnable);
        }
    }

    private void scheduleStylusWindowIdleTimeout() {
        if (mHandler == null) {
            return;
        }
        cancelStylusWindowIdleTimeout();
        long timeout = (mStylusWindowIdleTimeoutForTest > 0)
                ? mStylusWindowIdleTimeoutForTest : STYLUS_WINDOW_IDLE_TIMEOUT_MILLIS;
        mHandler.postDelayed(getStylusWindowIdleTimeoutRunnable(), timeout);
    }

    private Runnable getStylusWindowIdleTimeoutRunnable() {
        if (mStylusWindowIdleTimeoutRunnable == null) {
            mStylusWindowIdleTimeoutRunnable = () -> {
                finishAndRemoveStylusHandwritingWindow();
                mStylusWindowIdleTimeoutRunnable = null;
            };
        }

        return mStylusWindowIdleTimeoutRunnable;
    }

    /**
     * Sets the duration after which an ongoing stylus handwriting session that hasn't received new
     * {@link MotionEvent}s will time out and {@link #finishStylusHandwriting()} will be called.
     *
     * The maximum allowed duration is returned by
     * {@link #getStylusHandwritingIdleTimeoutMax()}, larger values will be clamped.
     *
     * Note: this value is bound to the {@link InputMethodService} instance and resets to the
     * default whenever a new instance is constructed.
     * @param duration timeout to set.
     * @see #onStartStylusHandwriting()
     * @see #onFinishStylusHandwriting()
     * @see #getStylusHandwritingSessionTimeout()
     */
    public final void setStylusHandwritingSessionTimeout(@NonNull Duration duration) {
        long timeoutMs = duration.toMillis();
        if (timeoutMs <= 0) {
            throw new IllegalStateException(
                    "A positive value should be set for Stylus handwriting session timeout.");
        }
        if (timeoutMs > STYLUS_HANDWRITING_IDLE_TIMEOUT_MAX_MS) {
            timeoutMs = STYLUS_HANDWRITING_IDLE_TIMEOUT_MAX_MS;
        }
        mStylusHwSessionsTimeout = timeoutMs;
        scheduleHandwritingSessionTimeout();
    }

    /**
     * Returns the maximum stylus handwriting session idle-timeout for use with
     * {@link #setStylusHandwritingSessionTimeout(Duration)}.
     * @see #onStartStylusHandwriting()
     * @see #onFinishStylusHandwriting()
     * @see #getStylusHandwritingSessionTimeout()
     */
    @NonNull
    public static final Duration getStylusHandwritingIdleTimeoutMax() {
        return Duration.ofMillis(STYLUS_HANDWRITING_IDLE_TIMEOUT_MAX_MS);
    }

    /**
     * Returns the duration after which an ongoing stylus handwriting session that hasn't received
     * new {@link MotionEvent}s will time out and {@link #finishStylusHandwriting()} will be called.
     * The current timeout can be changed using
     * {@link #setStylusHandwritingSessionTimeout(Duration)}.
     * @see #getStylusHandwritingIdleTimeoutMax
     * @see #onStartStylusHandwriting()
     * @see #onFinishStylusHandwriting()
     */
    @NonNull
    public final Duration getStylusHandwritingSessionTimeout() {
        return Duration.ofMillis(mStylusHwSessionsTimeout);
    }

    private Runnable getFinishHandwritingRunnable() {
        if (mFinishHwRunnable != null) {
            return mFinishHwRunnable;
        }
        return mFinishHwRunnable = () -> {
            if (mHandler != null) {
                mHandler.removeCallbacks(mFinishHwRunnable);
            }
            Log.d(TAG, "Stylus handwriting idle timed-out. calling finishStylusHandwriting()");
            mFinishHwRunnable = null;
            finishStylusHandwriting();
        };
    }

    private void scheduleHandwritingSessionTimeout() {
        if (mHandler == null) {
            mHandler = new Handler(getMainLooper());
        }
        if (mFinishHwRunnable != null) {
            mHandler.removeCallbacks(mFinishHwRunnable);
        }
        mHandler.postDelayed(getFinishHandwritingRunnable(), mStylusHwSessionsTimeout);
    }

    /**
     * The system has decided that it may be time to show your input method.
     * This is called due to a corresponding call to your
     * {@link InputMethod#showSoftInput InputMethod.showSoftInput()}
     * method.  The default implementation uses
     * {@link #onEvaluateInputViewShown()}, {@link #onEvaluateFullscreenMode()},
     * and the current configuration to decide whether the input view should
     * be shown at this point.
     *
     * @param configChange This is true if we are re-showing due to a
     * configuration change.
     * @return Returns true to indicate that the window should be shown.
     */
    public boolean onShowInputRequested(@InputMethod.ShowFlags int flags, boolean configChange) {
        if (!onEvaluateInputViewShown()) {
            return false;
        }
        if ((flags & InputMethod.SHOW_EXPLICIT) == 0) {
            if (!configChange && onEvaluateFullscreenMode() && !isInputViewShown()) {
                // Don't show if this is not explicitly requested by the user and
                // the input method is fullscreen unless it is already shown. That
                // would be too disruptive. However, we skip this change for a
                // config change, since if the IME is already shown we do want to
                // go into fullscreen mode at this point.
                return false;
            }
            if (!mSettingsObserver.shouldShowImeWithHardKeyboard() &&
                    getResources().getConfiguration().keyboard != Configuration.KEYBOARD_NOKEYS) {
                // And if the device has a hard keyboard, even if it is
                // currently hidden, don't show the input method implicitly.
                // These kinds of devices don't need it that much.
                return false;
            }
        }
        return true;
    }

    /**
     * A utility method to call {{@link #onShowInputRequested(int, boolean)}} and update internal
     * states depending on its result.  Since {@link #onShowInputRequested(int, boolean)} is
     * exposed to IME authors as an overridable public method without {@code @CallSuper}, we have
     * to have this method to ensure that those internal states are always updated no matter how
     * {@link #onShowInputRequested(int, boolean)} is overridden by the IME author.
     *
     * @param configChange This is true if we are re-showing due to a
     * configuration change.
     * @return Returns true to indicate that the window should be shown.
     * @see #onShowInputRequested(int, boolean)
     */
    private boolean dispatchOnShowInputRequested(@InputMethod.ShowFlags int flags,
            boolean configChange) {
        final boolean result = onShowInputRequested(flags, configChange);
        mInlineSuggestionSessionController.notifyOnShowInputRequested(result);
        if (result) {
            mShowInputFlags = flags;
        } else {
            mShowInputFlags = 0;
        }
        return result;
    }

    /**
     * Utility function that creates an IME request tracking token before
     * calling {@link #showWindow}.
     *
     * @param showInput whether the input window should be shown.
     * @param reason the reason why the IME request was created.
     */
    private void showWindowWithToken(boolean showInput, @SoftInputShowHideReason int reason) {
        mCurStatsToken = createStatsToken(true /* show */, reason,
                ImeTracker.isFromUser(mRootView));
        showWindow(showInput);
    }

    public void showWindow(boolean showInput) {
        if (DEBUG) Log.v(TAG, "Showing window: showInput=" + showInput
                + " mShowInputRequested=" + mShowInputRequested
                + " mViewsCreated=" + mViewsCreated
                + " mDecorViewVisible=" + mDecorViewVisible
                + " mWindowVisible=" + mWindowVisible
                + " mInputStarted=" + mInputStarted
                + " mShowInputFlags=" + mShowInputFlags);

        final var statsToken = mCurStatsToken != null ? mCurStatsToken
                : createStatsToken(true /* show */,
                        SoftInputShowHideReason.SHOW_WINDOW_LEGACY_DIRECT,
                        ImeTracker.isFromUser(mRootView));
        mCurStatsToken = null;

        if (mInShowWindow) {
            Log.w(TAG, "Re-entrance in to showWindow");
            ImeTracker.forLogging().onCancelled(statsToken, ImeTracker.PHASE_IME_SHOW_WINDOW);
            return;
        }

        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_SHOW_WINDOW);

        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            // The ImeInsetsSourceProvider need the statsToken when dispatching the control
            // (whenever the IME has drawn and its window is visible). Therefore, sending the
            // statsToken here first.
            notifyPreImeWindowVisibilityChanged(true /* visible */, statsToken);
        }

        ImeTracing.getInstance().triggerServiceDump("InputMethodService#showWindow", mDumper,
                null /* icProto */);
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMS.showWindow");
        mDecorViewWasVisible = mDecorViewVisible;
        mInShowWindow = true;
        startViews(prepareWindow(showInput));
        setImeWindowVisibility(computeImeWindowVis());

        mNavigationBarController.onWindowShown();
        // compute visibility
        onWindowShown();
        mWindowVisible = true;

        // request draw for the IME surface.
        if (DEBUG) Log.v(TAG, "showWindow: draw decorView!");
        mWindow.show();
        mDecorViewWasVisible = true;
        if (!android.view.inputmethod.Flags.refactorInsetsController()) {
            applyVisibilityInInsetsConsumerIfNecessary(true /* setVisible */, statsToken);
        }
        cancelImeSurfaceRemoval();
        mInShowWindow = false;
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        registerDefaultOnBackInvokedCallback();
    }


    /**
     * Registers an {@link OnBackInvokedCallback} to handle back invocation when ahead-of-time
     *  back dispatching is enabled. We keep the {@link KeyEvent#KEYCODE_BACK} based legacy code
     *  around to handle back on older devices.
     */
    private void registerDefaultOnBackInvokedCallback() {
        if (mBackCallbackRegistered) {
            return;
        }
        if (mWindow != null) {
            if (getApplicationInfo().isOnBackInvokedCallbackEnabled() && predictiveBackIme()) {
                // Register the compat callback as system-callback if IME has opted in for
                // predictive back (and predictiveBackIme feature flag is enabled). This indicates
                // to the receiving process (application process) that a predictive IME dismiss
                // animation may be played instead of invoking the callback.
                mWindow.getOnBackInvokedDispatcher().registerSystemOnBackInvokedCallback(
                        mCompatBackCallback);
            } else {
                mWindow.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCompatBackCallback);
            }
            mBackCallbackRegistered = true;
        }
    }

    private void unregisterDefaultOnBackInvokedCallback() {
        if (!mBackCallbackRegistered) {
            return;
        }
        if (mWindow != null) {
            mWindow.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(mCompatBackCallback);
            mBackCallbackRegistered = false;
        }
    }

    private KeyEvent createBackKeyEvent(int action, boolean isTracking) {
        final long when = SystemClock.uptimeMillis();
        return new KeyEvent(when, when, action,
                KeyEvent.KEYCODE_BACK, 0 /* repeat */, 0 /* metaState */,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /* scancode */,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY
                        | (isTracking ? KeyEvent.FLAG_TRACKING : 0),
                InputDevice.SOURCE_KEYBOARD);
    }

    private boolean prepareWindow(boolean showInput) {
        boolean doShowInput = false;
        mDecorViewVisible = true;
        if (!mShowInputRequested && mInputStarted && showInput) {
            doShowInput = true;
            mShowInputRequested = true;
        }

        if (DEBUG) Log.v(TAG, "showWindow: updating UI");
        initialize();
        updateFullscreenMode();
        updateInputViewShown();

        if (!mViewsCreated) {
            mViewsCreated = true;
            initialize();
            if (DEBUG) Log.v(TAG, "CALL: onCreateCandidatesView");
            View v = onCreateCandidatesView();
            if (DEBUG) Log.v(TAG, "showWindow: candidates=" + v);
            if (v != null) {
                setCandidatesView(v);
            }
        }
        return doShowInput;
    }

    private void startViews(boolean doShowInput) {
        if (mShowInputRequested) {
            if (!mInputViewStarted) {
                if (DEBUG) Log.v(TAG, "CALL: onStartInputView");
                mInputViewStarted = true;
                mInlineSuggestionSessionController.notifyOnStartInputView();
                onStartInputView(mInputEditorInfo, false);
            }
        } else if (!mCandidatesViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onStartCandidatesView");
            mCandidatesViewStarted = true;
            onStartCandidatesView(mInputEditorInfo, false);
        }
        if (doShowInput) startExtractingText(false);
    }

    /**
     * Applies the IME visibility in {@link android.view.ImeInsetsSourceConsumer}.
     *
     * @param setVisible {@code true} to make it visible, false to hide it.
     * @param statsToken the token tracking the current IME request.
     */
    private void applyVisibilityInInsetsConsumerIfNecessary(boolean setVisible,
            @NonNull ImeTracker.Token statsToken) {
        ImeTracing.getInstance().triggerServiceDump(
                "InputMethodService#applyVisibilityInInsetsConsumerIfNecessary", mDumper,
                null /* icProto */);
        mPrivOps.applyImeVisibilityAsync(setVisible
                ? mCurShowInputToken : mCurHideInputToken, setVisible, statsToken);
    }

    /**
     * Notifies the ImeInsetsSourceProvider before the IME visibility changes.
     *
     * @param visible {@code true} if it became visible, {@code false} otherwise.
     * @param statsToken the token tracking the current IME request.
     */
    private void notifyPreImeWindowVisibilityChanged(boolean visible,
            @NonNull ImeTracker.Token statsToken) {
        final var viewRootImpl = getWindow().getWindow().getDecorView().getViewRootImpl();
        if (viewRootImpl != null) {
            viewRootImpl.notifyImeVisibilityChanged(visible, statsToken);
        }
    }

    private void finishViews(boolean finishingInput) {
        if (mInputViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishInputView");
            mInlineSuggestionSessionController.notifyOnFinishInputView();
            onFinishInputView(finishingInput);
        } else if (mCandidatesViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishCandidatesView");
            onFinishCandidatesView(finishingInput);
        }
        mInputViewStarted = false;
        mCandidatesViewStarted = false;
    }

    /**
     * Utility function that creates an IME request tracking token before
     * calling {@link #hideWindow}.
     *
     * @param reason the reason why the IME request was created.
     */
    private void hideWindowWithToken(@SoftInputShowHideReason int reason) {
        // TODO(b/303041796): this should be handled by ImeTracker.isFromUser after fixing it
        //  to work with onClickListeners
        final boolean isFromUser = ImeTracker.isFromUser(mRootView)
                || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY;
        mCurStatsToken = createStatsToken(false /* show */, reason, isFromUser);
        hideWindow();
    }

    public void hideWindow() {
        if (DEBUG) Log.v(TAG, "CALL: hideWindow");

        final var statsToken = mCurStatsToken != null ? mCurStatsToken
                : createStatsToken(false /* show */,
                        SoftInputShowHideReason.HIDE_WINDOW_LEGACY_DIRECT,
                        ImeTracker.isFromUser(mRootView));
        mCurStatsToken = null;

        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_IME_HIDE_WINDOW);
        ImeTracing.getInstance().triggerServiceDump("InputMethodService#hideWindow", mDumper,
                null /* icProto */);
        setImeWindowVisibility(0 /* vis */);
        if (android.view.inputmethod.Flags.refactorInsetsController()) {
            // The ImeInsetsSourceProvider need the statsToken when dispatching the control. We
            // send the token here, so that another request in the provider can be cancelled.
            notifyPreImeWindowVisibilityChanged(false /* visible */, statsToken);
        } else {
            applyVisibilityInInsetsConsumerIfNecessary(false /* setVisible */, statsToken);
        }
        mWindowVisible = false;
        finishViews(false /* finishingInput */);
        if (mDecorViewVisible) {
            // It is responsible for client and server side visibility of IME window.
            if (mInputView != null) {
                mInputView.dispatchWindowVisibilityChanged(View.GONE);
            }
            mDecorViewVisible = false;
            onWindowHidden();
            mDecorViewWasVisible = false;
        }
        mLastWasInFullscreenMode = mIsFullscreen;
        updateFullscreenMode();
        unregisterDefaultOnBackInvokedCallback();
    }

    /**
     * Called immediately before the input method window is shown to the user.
     * You could override this to prepare for the window to be shown
     * (update view structure etc).
     */
    public void onWindowShown() {
        // Intentionally empty
    }
    
    /**
     * Called when the input method window has been hidden from the user,
     * after previously being visible.
     */
    public void onWindowHidden() {
        // Intentionally empty
    }

    /**
     * Called when a new client has bound to the input method.  This
     * may be followed by a series of {@link #onStartInput(EditorInfo, boolean)}
     * and {@link #onFinishInput()} calls as the user navigates through its
     * UI.  Upon this call you know that {@link #getCurrentInputBinding}
     * and {@link #getCurrentInputConnection} return valid objects.
     */
    public void onBindInput() {
        // Intentionally empty
    }
    
    /**
     * Called when the previous bound client is no longer associated
     * with the input method.  After returning {@link #getCurrentInputBinding}
     * and {@link #getCurrentInputConnection} will no longer return
     * valid objects.
     */
    public void onUnbindInput() {
        // Intentionally empty
    }
    
    /**
     * Called to inform the input method that text input has started in an
     * editor.  You should use this callback to initialize the state of your
     * input to match the state of the editor given to it.
     * 
     * @param attribute The attributes of the editor that input is starting
     * in.
     * @param restarting Set to true if input is restarting in the same
     * editor such as because the application has changed the text in
     * the editor.  Otherwise will be false, indicating this is a new
     * session with the editor.
     */
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        // Intentionally empty
    }
    
    void doFinishInput() {
        if (DEBUG) Log.v(TAG, "CALL: doFinishInput");
        ImeTracing.getInstance().triggerServiceDump("InputMethodService#doFinishInput", mDumper,
                null /* icProto */);
        finishViews(true /* finishingInput */);
        if (mInputStarted) {
            mInlineSuggestionSessionController.notifyOnFinishInput();
            if (DEBUG) Log.v(TAG, "CALL: onFinishInput");
            onFinishInput();
        }
        mInputStarted = false;
        mStartedInputConnection = null;
        mCurCompletions = null;
        if (!mOnPreparedStylusHwCalled) {
            // If IME didn't prepare to show InkWindow for current handwriting session.
            finishStylusHandwriting();
        }
        // Back callback is typically unregistered in {@link #hideWindow()}, but it's possible
        // for {@link #doFinishInput()} to be called without {@link #hideWindow()} so we also
        // unregister here.
        unregisterDefaultOnBackInvokedCallback();
    }

    void doStartInput(InputConnection ic, EditorInfo editorInfo, boolean restarting) {
        if (!restarting && mInputStarted) {
            doFinishInput();
        }
        ImeTracing.getInstance().triggerServiceDump("InputMethodService#doStartInput", mDumper,
                null /* icProto */);
        mInputStarted = true;
        mStartedInputConnection = ic;
        mInputEditorInfo = editorInfo;
        initialize();
        mInlineSuggestionSessionController.notifyOnStartInput(
                editorInfo == null ? null : editorInfo.packageName,
                editorInfo == null ? null : editorInfo.autofillId);
        if (DEBUG) Log.v(TAG, "CALL: onStartInput");
        onStartInput(editorInfo, restarting);
        if (mDecorViewVisible) {
            if (mShowInputRequested) {
                if (DEBUG) Log.v(TAG, "CALL: onStartInputView");
                mInputViewStarted = true;
                mInlineSuggestionSessionController.notifyOnStartInputView();
                onStartInputView(mInputEditorInfo, restarting);
                startExtractingText(true);
            } else if (mCandidatesVisibility == View.VISIBLE) {
                if (DEBUG) Log.v(TAG, "CALL: onStartCandidatesView");
                mCandidatesViewStarted = true;
                onStartCandidatesView(mInputEditorInfo, restarting);
            }
        }
    }
    
    /**
     * Called to inform the input method that text input has finished in
     * the last editor.  At this point there may be a call to
     * {@link #onStartInput(EditorInfo, boolean)} to perform input in a
     * new editor, or the input method may be left idle.  This method is
     * <em>not</em> called when input restarts in the same editor.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     */
    public void onFinishInput() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.finishComposingText();
        }
    }
    
    /**
     * Called when the application has reported auto-completion candidates that
     * it would like to have the input method displayed.  Typically these are
     * only used when an input method is running in full-screen mode, since
     * otherwise the user can see and interact with the pop-up window of
     * completions shown by the application.
     * 
     * <p>The default implementation here does nothing.
     */
    public void onDisplayCompletions(CompletionInfo[] completions) {
        // Intentionally empty
    }
    
    /**
     * Called when the application has reported new extracted text to be shown
     * due to changes in its current text state.  The default implementation
     * here places the new text in the extract edit text, when the input
     * method is running in fullscreen mode.
     */
    public void onUpdateExtractedText(int token, ExtractedText text) {
        if (mExtractedToken != token) {
            return;
        }
        if (text != null) {
            if (mExtractEditText != null) {
                mExtractedText = text;
                mExtractEditText.setExtractedText(text);
            }
        }
    }
    
    /**
     * Called when the application has reported a new selection region of
     * the text.  This is called whether or not the input method has requested
     * extracted text updates, although if so it will not receive this call
     * if the extracted text has changed as well.
     * 
     * <p>The default implementation takes care of updating the cursor in
     * the extract text, if it is being shown.
     */
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        final ExtractEditText eet = mExtractEditText;
        if (eet != null && isFullscreenMode() && mExtractedText != null) {
            final int off = mExtractedText.startOffset;
            eet.startInternalChanges();
            newSelStart -= off;
            newSelEnd -= off;
            final int len = eet.getText().length();
            if (newSelStart < 0) newSelStart = 0;
            else if (newSelStart > len) newSelStart = len;
            if (newSelEnd < 0) newSelEnd = 0;
            else if (newSelEnd > len) newSelEnd = len;
            eet.setSelection(newSelStart, newSelEnd);
            eet.finishInternalChanges();
        }
    }

    /**
     * Called when the user tapped or clicked a text view.
     * IMEs can't rely on this method being called because this was not part of the original IME
     * protocol, so applications with custom text editing written before this method appeared will
     * not call to inform the IME of this interaction.
     * @param focusChanged true if the user changed the focused view by this click.
     * @see InputMethodManager#viewClicked(View)
     * @see #onUpdateEditorToolType(int)
     * @deprecated The method may not be called for composite {@link View} that works as a giant
     *             "Canvas", which can host its own UI hierarchy and sub focus state.
     *             {@link android.webkit.WebView} is a good example. Application / IME developers
     *             should not rely on this method. If your goal is just being notified when an
     *             on-going input is interrupted, simply monitor {@link #onFinishInput()}.
     *             If your goal is to know what {@link MotionEvent#getToolType(int)} clicked on
     *             editor, use {@link #onUpdateEditorToolType(int)} instead.
     */
    @Deprecated
    public void onViewClicked(boolean focusChanged) {
        // Intentionally empty
    }

    /**
     * Called when the user tapped or clicked an editor.
     * This can be useful when IME makes a decision of showing Virtual keyboard based on what
     * {@link MotionEvent#getToolType(int)} was used to click the editor.
     * e.g. when toolType is {@link MotionEvent#TOOL_TYPE_STYLUS}, IME may choose to show a
     * companion widget instead of normal virtual keyboard.
     * <p> This method is called after {@link #onStartInput(EditorInfo, boolean)} and before
     * {@link #onStartInputView(EditorInfo, boolean)} when editor was clicked with a known tool
     * type.</p>
     * <p> Default implementation does nothing. </p>
     * @param toolType what {@link MotionEvent#getToolType(int)} was used to click on editor.
     */
    public void onUpdateEditorToolType(@ToolType int toolType) {
        // Intentionally empty
    }

    /**
     * Called when the application has reported a new location of its text
     * cursor.  This is only called if explicitly requested by the input method.
     * The default implementation does nothing.
     * @deprecated Use {@link #onUpdateCursorAnchorInfo(CursorAnchorInfo)} instead.
     */
    @Deprecated
    public void onUpdateCursor(Rect newCursor) {
        // Intentionally empty
    }

    /**
     * Called when the application has reported a new location of its text insertion point and
     * characters in the composition string.  This is only called if explicitly requested by the
     * input method. The default implementation does nothing.
     * @param cursorAnchorInfo The positional information of the text insertion point and the
     * composition string.
     */
    public void onUpdateCursorAnchorInfo(CursorAnchorInfo cursorAnchorInfo) {
        // Intentionally empty
    }

    /**
     * Close this input method's soft input area, removing it from the display.
     *
     * The input method will continue running, but the user can no longer use it to generate input
     * by touching the screen.
     */
    public void requestHideSelf(@InputMethodManager.HideFlags int flags) {
        requestHideSelf(flags, SoftInputShowHideReason.HIDE_SOFT_INPUT_FROM_IME);
    }

    private void requestHideSelf(@InputMethodManager.HideFlags int flags,
            @SoftInputShowHideReason int reason) {
        // TODO(b/303041796): this should be handled by ImeTracker.isFromUser after fixing it
        //  to work with onClickListeners
        final boolean isFromUser = ImeTracker.isFromUser(mRootView)
                || reason == SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY;
        final var statsToken = createStatsToken(false /* show */, reason, isFromUser);
        ImeTracing.getInstance().triggerServiceDump("InputMethodService#requestHideSelf", mDumper,
                null /* icProto */);
        mPrivOps.hideMySoftInput(statsToken, flags, reason);
    }

    /**
     * Show the input method's soft input area, so the user sees the input method window and can
     * interact with it.
     */
    public final void requestShowSelf(@InputMethodManager.ShowFlags int flags) {
        requestShowSelf(flags, SoftInputShowHideReason.SHOW_SOFT_INPUT_FROM_IME);
    }

    private void requestShowSelf(@InputMethodManager.ShowFlags int flags,
            @SoftInputShowHideReason int reason) {
        final var statsToken = createStatsToken(true /* show */, reason,
                ImeTracker.isFromUser(mRootView));
        ImeTracing.getInstance().triggerServiceDump("InputMethodService#requestShowSelf", mDumper,
                null /* icProto */);
        mPrivOps.showMySoftInput(statsToken, flags, reason);
    }

    private boolean handleBack(boolean doIt) {
        if (mShowInputRequested) {
            // If the soft input area is shown, back closes it and we
            // consume the back key.
            if (doIt) {
                requestHideSelf(0 /* flags */, SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY);
            }
            return true;
        } else if (mDecorViewVisible) {
            if (mCandidatesVisibility == View.VISIBLE) {
                // If we are showing candidates even if no input area, then
                // hide them.
                if (doIt) setCandidatesViewShown(false);
            } else {
                // If we have the window visible for some other reason --
                // most likely to show candidates -- then just get rid
                // of it.  This really shouldn't happen, but just in case...
                if (doIt) hideWindowWithToken(SoftInputShowHideReason.HIDE_SOFT_INPUT_BY_BACK_KEY);
            }
            return true;
        }
        return false;
    }

    /**
     * @return {@link ExtractEditText} if it is considered to be visible and active. Otherwise
     * {@code null} is returned.
     */
    private ExtractEditText getExtractEditTextIfVisible() {
        if (!isExtractViewShown() || !isInputViewShown()) {
            return null;
        }
        return mExtractEditText;
    }

    /**
     * Called back when a {@link KeyEvent} is forwarded from the target application.
     *
     * <p>The default implementation intercepts {@link KeyEvent#KEYCODE_BACK} if the IME is
     * currently shown , to possibly hide it when the key goes up (if not canceled or long pressed).
     * In addition, in fullscreen mode only, it will consume DPAD movement events to move the cursor
     * in the extracted text view, not allowing them to perform navigation in the underlying
     * application.</p>
     *
     * <p>The default implementation does not take flags specified to
     * {@link #setBackDisposition(int)} into account, even on API version
     * {@link android.os.Build.VERSION_CODES#P} and later devices.  IME developers are responsible
     * for making sure that their special handling for {@link KeyEvent#KEYCODE_BACK} are consistent
     * with the flag they specified to {@link #setBackDisposition(int)}.</p>
     *
     * @param keyCode The value in {@code event.getKeyCode()}
     * @param event Description of the key event
     *
     * @return {@code true} if the event is consumed by the IME and the application no longer needs
     *         to consume it.  Return {@code false} when the event should be handled as if the IME
     *         had not seen the event at all.
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Flags.useHandwritingListenerForTooltype()) {
            // any KeyEvent keyDown should reset last toolType.
            updateEditorToolTypeInternal(MotionEvent.TOOL_TYPE_UNKNOWN);
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            final ExtractEditText eet = getExtractEditTextIfVisible();
            if (eet != null && eet.handleBackInTextActionModeIfNeeded(event)) {
                return true;
            }
            if (handleBack(false)) {
                event.startTracking();
                return true;
            }
            return false;
        } else if (keyCode == KeyEvent.KEYCODE_SPACE && KeyEvent.metaStateHasModifiers(
                event.getMetaState() & ~KeyEvent.META_SHIFT_MASK, KeyEvent.META_CTRL_ON)) {
            if (mDecorViewVisible && mWindowVisible) {
                int direction = (event.getMetaState() & KeyEvent.META_SHIFT_MASK) != 0 ? -1 : 1;
                mPrivOps.switchKeyboardLayoutAsync(direction);
                event.startTracking();
                return true;
            }
        }

        // Check if this may be a ctrl+shift shortcut
        if (ctrlShiftShortcut()) {
            if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                    || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
                // Potentially Ctrl+Shift shortcut if Ctrl is currently pressed
                mUsingCtrlShiftShortcut = KeyEvent.metaStateHasModifiers(
                    event.getMetaState() & ~KeyEvent.META_SHIFT_MASK, KeyEvent.META_CTRL_ON);
            } else if (keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                    || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
                // Potentially Ctrl+Shift shortcut if Shift is currently pressed
                mUsingCtrlShiftShortcut = KeyEvent.metaStateHasModifiers(
                    event.getMetaState() & ~KeyEvent.META_CTRL_MASK, KeyEvent.META_SHIFT_ON);
            } else {
                mUsingCtrlShiftShortcut = false;
            }
        }

        return doMovementKey(keyCode, event, MOVEMENT_DOWN);
    }

    /**
     * Default implementation of {@link KeyEvent.Callback#onKeyLongPress(int, KeyEvent)
     * KeyEvent.Callback.onKeyLongPress()}: always returns false (doesn't handle
     * the event).
     */
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Override this to intercept special key multiple events before they are
     * processed by the
     * application.  If you return true, the application will not itself
     * process the event.  If you return false, the normal application processing
     * will occur as if the IME had not seen the event at all.
     * 
     * <p>The default implementation always returns false, except when
     * in fullscreen mode, where it will consume DPAD movement
     * events to move the cursor in the extracted text view, not allowing
     * them to perform navigation in the underlying application.
     */
    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        return doMovementKey(keyCode, event, count);
    }

    /**
     * Override this to intercept key up events before they are processed by the
     * application.  If you return true, the application will not itself
     * process the event.  If you return false, the normal application processing
     * will occur as if the IME had not seen the event at all.
     * 
     * <p>The default implementation intercepts {@link KeyEvent#KEYCODE_BACK
     * KeyEvent.KEYCODE_BACK} to hide the current IME UI if it is shown.  In
     * addition, in fullscreen mode only, it will consume DPAD movement
     * events to move the cursor in the extracted text view, not allowing
     * them to perform navigation in the underlying application.
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (ctrlShiftShortcut()) {
            if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                    || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
                    || keyCode == KeyEvent.KEYCODE_CTRL_LEFT
                    || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT) {
                if (mUsingCtrlShiftShortcut
                        && event.hasNoModifiers()) {
                    mUsingCtrlShiftShortcut = false;
                    if (mDecorViewVisible && mWindowVisible) {
                        // Move to the next IME
                        switchToNextInputMethod(false /* onlyCurrentIme */);
                        // TODO(b/332937629): Make the event stream consistent again
                        return true;
                    }
                }
            } else {
                mUsingCtrlShiftShortcut = false;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            final ExtractEditText eet = getExtractEditTextIfVisible();
            if (eet != null && eet.handleBackInTextActionModeIfNeeded(event)) {
                return true;
            }
            if (event.isTracking() && !event.isCanceled()) {
                return handleBack(true);
            }
        } else if (keyCode == KeyEvent.KEYCODE_SPACE) {
            if (event.isTracking() && !event.isCanceled()) {
                return true;
            }
        }

        return doMovementKey(keyCode, event, MOVEMENT_UP);
    }

    /**
     * Override this to intercept trackball motion events before they are
     * processed by the application.
     * If you return true, the application will not itself process the event.
     * If you return false, the normal application processing will occur as if
     * the IME had not seen the event at all.
     */
    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (DEBUG) Log.v(TAG, "onTrackballEvent: " + event);
        return false;
    }

    /**
     * Override this to intercept generic motion events before they are
     * processed by the application.
     * If you return true, the application will not itself process the event.
     * If you return false, the normal application processing will occur as if
     * the IME had not seen the event at all.
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (DEBUG) Log.v(TAG, "onGenericMotionEvent(): event " + event);
        return false;
    }

    /**
     * Not implemented in this class.
     */
    public void onAppPrivateCommand(String action, Bundle data) {
    }

    /**
     * Handle a request by the system to toggle the soft input area.
     */
    private void onToggleSoftInput(@InputMethodManager.ShowFlags int showFlags,
            @InputMethodManager.HideFlags int hideFlags) {
        if (DEBUG) Log.v(TAG, "toggleSoftInput()");
        if (isInputViewShown()) {
            requestHideSelf(hideFlags,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT);
        } else {
            requestShowSelf(showFlags,
                    SoftInputShowHideReason.SHOW_SOFT_INPUT_IME_TOGGLE_SOFT_INPUT);
        }
    }
    
    static final int MOVEMENT_DOWN = -1;
    static final int MOVEMENT_UP = -2;
    
    void reportExtractedMovement(int keyCode, int count) {
        int dx = 0, dy = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dx = -count;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dx = count;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                dy = -count;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dy = count;
                break;
        }
        onExtractedCursorMovement(dx, dy);
    }

    boolean doMovementKey(int keyCode, KeyEvent event, int count) {
        final ExtractEditText eet = getExtractEditTextIfVisible();
        if (eet != null) {
            // If we are in fullscreen mode, the cursor will move around
            // the extract edit text, but should NOT cause focus to move
            // to other fields.
            MovementMethod movement = eet.getMovementMethod();
            Layout layout = eet.getLayout();
            if (movement != null && layout != null) {
                // We want our own movement method to handle the key, so the
                // cursor will properly move in our own word wrapping.
                if (count == MOVEMENT_DOWN) {
                    if (movement.onKeyDown(eet, eet.getText(), keyCode, event)) {
                        reportExtractedMovement(keyCode, 1);
                        return true;
                    }
                } else if (count == MOVEMENT_UP) {
                    if (movement.onKeyUp(eet, eet.getText(), keyCode, event)) {
                        return true;
                    }
                } else {
                    if (movement.onKeyOther(eet, eet.getText(), event)) {
                        reportExtractedMovement(keyCode, count);
                    } else {
                        KeyEvent down = KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN);
                        if (movement.onKeyDown(eet, eet.getText(), keyCode, down)) {
                            KeyEvent up = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
                            movement.onKeyUp(eet, eet.getText(), keyCode, up);
                            while (--count > 0) {
                                movement.onKeyDown(eet, eet.getText(), keyCode, down);
                                movement.onKeyUp(eet, eet.getText(), keyCode, up);
                            }
                            reportExtractedMovement(keyCode, count);
                        }
                    }
                }
            }
            // Regardless of whether the movement method handled the key,
            // we never allow DPAD navigation to the application.
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
            }
        }

        return false;
    }
    
    /**
     * Send the given key event code (as defined by {@link KeyEvent}) to the
     * current input connection is a key down + key up event pair.  The sent
     * events have {@link KeyEvent#FLAG_SOFT_KEYBOARD KeyEvent.FLAG_SOFT_KEYBOARD}
     * set, so that the recipient can identify them as coming from a software
     * input method, and
     * {@link KeyEvent#FLAG_KEEP_TOUCH_MODE KeyEvent.FLAG_KEEP_TOUCH_MODE}, so
     * that they don't impact the current touch mode of the UI.
     *
     * <p>Note that it's discouraged to send such key events in normal operation;
     * this is mainly for use with {@link android.text.InputType#TYPE_NULL} type
     * text fields, or for non-rich input methods. A reasonably capable software
     * input method should use the
     * {@link android.view.inputmethod.InputConnection#commitText} family of methods
     * to send text to an application, rather than sending key events.</p>
     *
     * @param keyEventCode The raw key code to send, as defined by
     * {@link KeyEvent}.
     */
    public void sendDownUpKeyEvents(int keyEventCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long eventTime = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD|KeyEvent.FLAG_KEEP_TOUCH_MODE));
        ic.sendKeyEvent(new KeyEvent(eventTime, SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD|KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }
    
    /**
     * Ask the input target to execute its default action via
     * {@link InputConnection#performEditorAction
     * InputConnection.performEditorAction()}.
     *
     * <p>For compatibility, this method does not execute a custom action even if {@link
     * EditorInfo#actionLabel EditorInfo.actionLabel} is set. The implementor should directly call
     * {@link InputConnection#performEditorAction InputConnection.performEditorAction()} with
     * {@link EditorInfo#actionId EditorInfo.actionId} if they want to execute a custom action.</p>
     *
     * @param fromEnterKey If true, this will be executed as if the user had
     * pressed an enter key on the keyboard, that is it will <em>not</em>
     * be done if the editor has set {@link EditorInfo#IME_FLAG_NO_ENTER_ACTION
     * EditorInfo.IME_FLAG_NO_ENTER_ACTION}.  If false, the action will be
     * sent regardless of how the editor has set that flag.
     * 
     * @return Returns a boolean indicating whether an action has been sent.
     * If false, either the editor did not specify a default action or it
     * does not want an action from the enter key.  If true, the action was
     * sent (or there was no input connection at all).
     */
    public boolean sendDefaultEditorAction(boolean fromEnterKey) {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei != null &&
                (!fromEnterKey || (ei.imeOptions &
                        EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) &&
                (ei.imeOptions & EditorInfo.IME_MASK_ACTION) !=
                    EditorInfo.IME_ACTION_NONE) {
            // If the enter key was pressed, and the editor has a default
            // action associated with pressing enter, then send it that
            // explicit action instead of the key event.
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.performEditorAction(ei.imeOptions&EditorInfo.IME_MASK_ACTION);
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Send the given UTF-16 character to the current input connection.  Most
     * characters will be delivered simply by calling
     * {@link InputConnection#commitText InputConnection.commitText()} with
     * the character; some, however, may be handled different.  In particular,
     * the enter character ('\n') will either be delivered as an action code
     * or a raw key event, as appropriate.  Consider this as a convenience
     * method for IMEs that do not have a full implementation of actions; a
     * fully complying IME will decide of the right action for each event and
     * will likely never call this method except maybe to handle events coming
     * from an actual hardware keyboard.
     * 
     * @param charCode The UTF-16 character code to send.
     */
    public void sendKeyChar(char charCode) {
        switch (charCode) {
            case '\n': // Apps may be listening to an enter key to perform an action
                if (!sendDefaultEditorAction(true)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                }
                break;
            default:
                // Make sure that digits go through any text watcher on the client side.
                if (charCode >= '0' && charCode <= '9') {
                    sendDownUpKeyEvents(charCode - '0' + KeyEvent.KEYCODE_0);
                } else {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(String.valueOf(charCode), 1);
                    }
                }
                break;
        }
    }
    
    /**
     * This is called when the user has moved the cursor in the extracted
     * text view, when running in fullsreen mode.  The default implementation
     * performs the corresponding selection change on the underlying text
     * editor.
     */
    public void onExtractedSelectionChanged(int start, int end) {
        InputConnection conn = getCurrentInputConnection();
        if (conn != null) {
            conn.setSelection(start, end);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void onExtractedDeleteText(int start, int end) {
        InputConnection conn = getCurrentInputConnection();
        if (conn != null) {
            conn.finishComposingText();
            conn.setSelection(start, start);
            conn.deleteSurroundingText(0, end - start);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void onExtractedReplaceText(int start, int end, CharSequence text) {
        InputConnection conn = getCurrentInputConnection();
        if (conn != null) {
            conn.setComposingRegion(start, end);
            conn.commitText(text, 1);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public void onExtractedSetSpan(Object span, int start, int end, int flags) {
        InputConnection conn = getCurrentInputConnection();
        if (conn != null) {
            if (!conn.setSelection(start, end)) return;
            CharSequence text = conn.getSelectedText(InputConnection.GET_TEXT_WITH_STYLES);
            if (text instanceof Spannable) {
                ((Spannable) text).setSpan(span, 0, text.length(), flags);
                conn.setComposingRegion(start, end);
                conn.commitText(text, 1);
            }
        }
    }

    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the candidates view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Re-implement this to provide whatever behavior you want.
     */
    public void onExtractedTextClicked() {
        if (mExtractEditText == null) {
            return;
        }
        if (mExtractEditText.hasVerticalScrollBar()) {
            setCandidatesViewShown(false);
        }
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the candidates view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Re-implement this to provide whatever behavior you want.
     * @param dx The amount of cursor movement in the x dimension.
     * @param dy The amount of cursor movement in the y dimension.
     */
    public void onExtractedCursorMovement(int dx, int dy) {
        if (mExtractEditText == null || dy == 0) {
            return;
        }
        if (mExtractEditText.hasVerticalScrollBar()) {
            setCandidatesViewShown(false);
        }
    }
    
    /**
     * This is called when the user has selected a context menu item from the
     * extracted text view, when running in fullscreen mode.  The default
     * implementation sends this action to the current InputConnection's
     * {@link InputConnection#performContextMenuAction(int)}, for it
     * to be processed in underlying "real" editor.  Re-implement this to
     * provide whatever behavior you want.
     */
    public boolean onExtractTextContextMenuItem(int id) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.performContextMenuAction(id);
        }
        return true;
    }

    /**
     * Return text that can be used as a button label for the given
     * {@link EditorInfo#imeOptions EditorInfo.imeOptions}.  Returns null
     * if there is no action requested.  Note that there is no guarantee that
     * the returned text will be relatively short, so you probably do not
     * want to use it as text on a soft keyboard key label.
     *
     * @param imeOptions The value from {@link EditorInfo#imeOptions EditorInfo.imeOptions}.
     *
     * @return Returns a label to use, or null if there is no action.
     */
    public CharSequence getTextForImeAction(int imeOptions) {
        switch (imeOptions&EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_NONE:
                return null;
            case EditorInfo.IME_ACTION_GO:
                return getText(com.android.internal.R.string.ime_action_go);
            case EditorInfo.IME_ACTION_SEARCH:
                return getText(com.android.internal.R.string.ime_action_search);
            case EditorInfo.IME_ACTION_SEND:
                return getText(com.android.internal.R.string.ime_action_send);
            case EditorInfo.IME_ACTION_NEXT:
                return getText(com.android.internal.R.string.ime_action_next);
            case EditorInfo.IME_ACTION_DONE:
                return getText(com.android.internal.R.string.ime_action_done);
            case EditorInfo.IME_ACTION_PREVIOUS:
                return getText(com.android.internal.R.string.ime_action_previous);
            default:
                return getText(com.android.internal.R.string.ime_action_default);
        }
    }

    /**
     * Return a drawable resource id that can be used as a button icon for the given
     * {@link EditorInfo#imeOptions EditorInfo.imeOptions}.
     *
     * @param imeOptions The value from @link EditorInfo#imeOptions EditorInfo.imeOptions}.
     *
     * @return Returns a drawable resource id to use.
     */
    @DrawableRes
    private int getIconForImeAction(int imeOptions) {
        switch (imeOptions&EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO:
                return com.android.internal.R.drawable.ic_input_extract_action_go;
            case EditorInfo.IME_ACTION_SEARCH:
                return com.android.internal.R.drawable.ic_input_extract_action_search;
            case EditorInfo.IME_ACTION_SEND:
                return com.android.internal.R.drawable.ic_input_extract_action_send;
            case EditorInfo.IME_ACTION_NEXT:
                return com.android.internal.R.drawable.ic_input_extract_action_next;
            case EditorInfo.IME_ACTION_DONE:
                return com.android.internal.R.drawable.ic_input_extract_action_done;
            case EditorInfo.IME_ACTION_PREVIOUS:
                return com.android.internal.R.drawable.ic_input_extract_action_previous;
            default:
                return com.android.internal.R.drawable.ic_input_extract_action_return;
        }
    }

    /**
     * Called when the fullscreen-mode extracting editor info has changed,
     * to determine whether the extracting (extract text and candidates) portion
     * of the UI should be shown.  The standard implementation hides or shows
     * the extract area depending on whether it makes sense for the
     * current editor.  In particular, a {@link InputType#TYPE_NULL}
     * input type or {@link EditorInfo#IME_FLAG_NO_EXTRACT_UI} flag will
     * turn off the extract area since there is no text to be shown.
     */
    public void onUpdateExtractingVisibility(EditorInfo ei) {
        if (ei.inputType == InputType.TYPE_NULL ||
                (ei.imeOptions&EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) {
            // No reason to show extract UI!
            setExtractViewShown(false);
            return;
        }
        
        setExtractViewShown(true);
    }
    
    /**
     * Called when the fullscreen-mode extracting editor info has changed,
     * to update the state of its UI such as the action buttons shown.
     * You do not need to deal with this if you are using the standard
     * full screen extract UI.  If replacing it, you will need to re-implement
     * this to put the appropriate action button in your own UI and handle it,
     * and perform any other changes.
     * 
     * <p>The standard implementation turns on or off its accessory area
     * depending on whether there is an action button, and hides or shows
     * the entire extract area depending on whether it makes sense for the
     * current editor.  In particular, a {@link InputType#TYPE_NULL} or 
     * {@link InputType#TYPE_TEXT_VARIATION_FILTER} input type will turn off the
     * extract area since there is no text to be shown.
     */
    public void onUpdateExtractingViews(EditorInfo ei) {
        if (!isExtractViewShown()) {
            return;
        }
        
        if (mExtractAccessories == null) {
            return;
        }
        final boolean hasAction = ei.actionLabel != null || (
                (ei.imeOptions&EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE &&
                (ei.imeOptions&EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION) == 0 &&
                ei.inputType != InputType.TYPE_NULL);
        if (hasAction) {
            mExtractAccessories.setVisibility(View.VISIBLE);
            if (mExtractAction != null) {
                if (mExtractAction instanceof ImageButton) {
                    ((ImageButton) mExtractAction)
                            .setImageResource(getIconForImeAction(ei.imeOptions));
                    if (ei.actionLabel != null) {
                        mExtractAction.setContentDescription(ei.actionLabel);
                    } else {
                        mExtractAction.setContentDescription(getTextForImeAction(ei.imeOptions));
                    }
                } else {
                    if (ei.actionLabel != null) {
                        ((TextView) mExtractAction).setText(ei.actionLabel);
                    } else {
                        ((TextView) mExtractAction).setText(getTextForImeAction(ei.imeOptions));
                    }
                }
                mExtractAction.setOnClickListener(mActionClickListener);
            }
        } else {
            mExtractAccessories.setVisibility(View.GONE);
            if (mExtractAction != null) {
                mExtractAction.setOnClickListener(null);
            }
        }
    }
    
    /**
     * This is called when, while currently displayed in extract mode, the
     * current input target changes.  The default implementation will
     * auto-hide the IME if the new target is not a full editor, since this
     * can be a confusing experience for the user.
     */
    public void onExtractingInputChanged(EditorInfo ei) {
        if (ei.inputType == InputType.TYPE_NULL) {
            requestHideSelf(InputMethodManager.HIDE_NOT_ALWAYS,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT_EXTRACT_INPUT_CHANGED);
        }
    }

    void startExtractingText(boolean inputChanged) {
        final ExtractEditText eet = mExtractEditText;
        if (eet != null && getCurrentInputStarted()
                && isFullscreenMode()) {
            mExtractedToken++;
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.token = mExtractedToken;
            req.flags = InputConnection.GET_TEXT_WITH_STYLES;
            req.hintMaxLines = 10;
            req.hintMaxChars = 10000;
            InputConnection ic = getCurrentInputConnection();
            mExtractedText = ic == null? null
                    : ic.getExtractedText(req, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
            if (mExtractedText == null || ic == null) {
                Log.e(TAG, "Unexpected null in startExtractingText : mExtractedText = "
                        + mExtractedText + ", input connection = " + ic);
            }
            final EditorInfo ei = getCurrentInputEditorInfo();
            
            try {
                eet.startInternalChanges();
                onUpdateExtractingVisibility(ei);
                onUpdateExtractingViews(ei);
                int inputType = ei.inputType;
                if ((inputType&EditorInfo.TYPE_MASK_CLASS)
                        == EditorInfo.TYPE_CLASS_TEXT) {
                    if ((inputType&EditorInfo.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0) {
                        inputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
                    }
                }
                eet.setInputType(inputType);
                eet.setHint(ei.hintText);
                if (mExtractedText != null) {
                    eet.setEnabled(true);
                    eet.setExtractedText(mExtractedText);
                } else {
                    eet.setEnabled(false);
                    eet.setText("");
                }
            } finally {
                eet.finishInternalChanges();
            }
            
            if (inputChanged) {
                onExtractingInputChanged(ei);
            }
        }
    }

    private void dispatchOnCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        synchronized (mLock) {
            mNotifyUserActionSent = false;
        }
        onCurrentInputMethodSubtypeChanged(newSubtype);
    }

    // TODO: Handle the subtype change event
    /**
     * Called when the subtype was changed.
     * @param newSubtype the subtype which is being changed to.
     */
    protected void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        if (DEBUG) {
            int nameResId = newSubtype.getNameResId();
            String mode = newSubtype.getMode();
            String output = "changeInputMethodSubtype:"
                + (nameResId == 0 ? "<none>" : getString(nameResId)) + ","
                + mode + ","
                + newSubtype.getLocale() + "," + newSubtype.getExtraValue();
            Log.v(TAG, "--- " + output);
        }
    }

    /**
     * Aimed to return the previous input method's {@link Insets#contentTopInsets}, but its actual
     * semantics has never been well defined.
     *
     * <p>Note that the previous document clearly mentioned that this method could return {@code 0}
     * at any time for whatever reason.  Now this method is just always returning {@code 0}.</p>
     *
     * @return on Android {@link android.os.Build.VERSION_CODES#Q} and later devices this method
     *         always returns {@code 0}
     * @deprecated the actual behavior of this method has never been well defined.  You cannot use
     *             this method in a reliable and predictable way
     */
    @Deprecated
    public int getInputMethodWindowRecommendedHeight() {
        Log.w(TAG, "getInputMethodWindowRecommendedHeight() is deprecated and now always returns 0."
                + " Do not use this method.");
        return 0;
    }

    /**
     * Returns whether the IME navigation bar is currently shown, for testing purposes.
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public final boolean isImeNavigationBarShownForTesting() {
        return mNavigationBarController.isShown();
    }

    /**
     * Called when the IME switch button was clicked from the client. Depending on the number of
     * enabled IME subtypes, this will either switch to the next IME/subtype, or show the input
     * method picker dialog.
     *
     * @hide
     */
    final void onImeSwitchButtonClickFromClient() {
        mPrivOps.onImeSwitchButtonClickFromClient(getDisplayId());
    }

    /**
     * Used to inject custom {@link InputMethodServiceInternal}.
     *
     * @return the {@link InputMethodServiceInternal} to be used.
     */
    @NonNull
    @Override
    final InputMethodServiceInternal createInputMethodServiceInternal() {
        return new InputMethodServiceInternal() {
            /**
             * {@inheritDoc}
             */
            @NonNull
            @Override
            public Context getContext() {
                return InputMethodService.this;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void exposeContent(@NonNull InputContentInfo inputContentInfo,
                    @NonNull InputConnection inputConnection) {
                if (inputConnection == null) {
                    return;
                }
                if (getCurrentInputConnection() != inputConnection) {
                    return;
                }
                exposeContentInternal(inputContentInfo, getCurrentInputEditorInfo());
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void notifyUserActionIfNecessary() {
                synchronized (mLock) {
                    if (mNotifyUserActionSent) {
                        return;
                    }
                    mPrivOps.notifyUserActionAsync();
                    mNotifyUserActionSent = true;
                }
            }

            /**
             * Allow the receiver of {@link InputContentInfo} to obtain a temporary read-only access
             * permission to the content.
             *
             * <p>See {@link #exposeContent(InputContentInfo, InputConnection)} for details.</p>
             *
             * @param inputContentInfo Content to be temporarily exposed from the input method to
             *                         the application.  This cannot be {@code null}.
             * @param editorInfo The editor that receives {@link InputContentInfo}.
             */
            private void exposeContentInternal(@NonNull InputContentInfo inputContentInfo,
                    @NonNull EditorInfo editorInfo) {
                final Uri contentUri = inputContentInfo.getContentUri();
                final IInputContentUriToken uriToken =
                        mPrivOps.createInputContentUriToken(contentUri, editorInfo.packageName);
                if (uriToken == null) {
                    Log.e(TAG, "createInputContentAccessToken failed. contentUri="
                            + contentUri.toString() + " packageName=" + editorInfo.packageName);
                    return;
                }
                inputContentInfo.setUriToken(uriToken);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void dump(FileDescriptor fd, PrintWriter fout, String[]args) {
                InputMethodService.this.dump(fd, fout, args);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void triggerServiceDump(String where, @Nullable byte[] icProto) {
                ImeTracing.getInstance().triggerServiceDump(where, mDumper, icProto);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean isServiceDestroyed() {
                return mDestroyed;
            }
        };
    }

    /** Computes the IME window visibility state. */
    @ImeWindowVisibility
    private int computeImeWindowVis() {
        return IME_ACTIVE | (isInputViewShown() ? IME_VISIBLE : 0);
    }

    /**
     * Creates an IME request tracking token.
     *
     * @param show whether this is a show or a hide request.
     * @param reason the reason why the IME request was created.
     * @param isFromUser whether this request was created directly from user interaction.
     */
    @NonNull
    private ImeTracker.Token createStatsToken(boolean show, @SoftInputShowHideReason int reason,
            boolean isFromUser) {
        return ImeTracker.forLogging().onStart(show ? ImeTracker.TYPE_SHOW : ImeTracker.TYPE_HIDE,
                ImeTracker.ORIGIN_IME, reason, isFromUser);
    }

    /**
     * Performs a dump of the InputMethodService's internal state.  Override
     * to add your own information to the dump.
     */
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        final Printer p = new PrintWriterPrinter(fout);
        p.println("Input method service state for " + this + ":");
        p.println("  mViewsCreated=" + mViewsCreated);
        p.println("  mDecorViewVisible=" + mDecorViewVisible
                + " mDecorViewWasVisible=" + mDecorViewWasVisible
                + " mWindowVisible=" + mWindowVisible
                + " mInShowWindow=" + mInShowWindow);
        p.println("  Configuration=" + getResources().getConfiguration());
        p.println("  mToken=" + mToken);
        p.println("  mInputBinding=" + mInputBinding);
        p.println("  mInputConnection=" + mInputConnection);
        p.println("  mStartedInputConnection=" + mStartedInputConnection);
        p.println("  mInputStarted=" + mInputStarted
                + " mInputViewStarted=" + mInputViewStarted
                + " mCandidatesViewStarted=" + mCandidatesViewStarted);

        if (mInputEditorInfo != null) {
            p.println("  mInputEditorInfo:");
            mInputEditorInfo.dump(p, "    ", false /* dumpExtras */);
        } else {
            p.println("  mInputEditorInfo: null");
        }

        p.println("  mShowInputRequested=" + mShowInputRequested
                + " mLastShowInputRequested=" + mLastShowInputRequested
                + " mShowInputFlags=0x" + Integer.toHexString(mShowInputFlags));
        p.println("  mCandidatesVisibility=" + mCandidatesVisibility
                + " mFullscreenApplied=" + mFullscreenApplied
                + " mIsFullscreen=" + mIsFullscreen
                + " mExtractViewHidden=" + mExtractViewHidden);

        if (mExtractedText != null) {
            p.println("  mExtractedText:");
            p.println("    text=" + mExtractedText.text.length() + " chars"
                    + " startOffset=" + mExtractedText.startOffset);
            p.println("    selectionStart=" + mExtractedText.selectionStart
                    + " selectionEnd=" + mExtractedText.selectionEnd
                    + " flags=0x" + Integer.toHexString(mExtractedText.flags));
        } else {
            p.println("  mExtractedText: null");
        }
        p.println("  mExtractedToken=" + mExtractedToken);
        p.println("  mIsInputViewShown=" + mIsInputViewShown
                + " mStatusIcon=" + mStatusIcon);
        p.println("  Last computed insets:");
        p.println("    contentTopInsets=" + mTmpInsets.contentTopInsets
                + " visibleTopInsets=" + mTmpInsets.visibleTopInsets
                + " touchableInsets=" + mTmpInsets.touchableInsets
                + " touchableRegion=" + mTmpInsets.touchableRegion);
        p.println("  mSettingsObserver=" + mSettingsObserver);
        p.println("  mNavigationBarController=" + mNavigationBarController.toDebugString());
    }

    private final ImeTracing.ServiceDumper mDumper = new ImeTracing.ServiceDumper() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void dumpToProto(ProtoOutputStream proto, @Nullable byte[] icProto) {
            final long token = proto.start(InputMethodServiceTraceProto.INPUT_METHOD_SERVICE);
            mWindow.dumpDebug(proto, SOFT_INPUT_WINDOW);
            proto.write(VIEWS_CREATED, mViewsCreated);
            proto.write(DECOR_VIEW_VISIBLE, mDecorViewVisible);
            proto.write(DECOR_VIEW_WAS_VISIBLE, mDecorViewWasVisible);
            proto.write(WINDOW_VISIBLE, mWindowVisible);
            proto.write(IN_SHOW_WINDOW, mInShowWindow);
            proto.write(CONFIGURATION, getResources().getConfiguration().toString());
            proto.write(TOKEN, Objects.toString(mToken));
            proto.write(INPUT_BINDING, Objects.toString(mInputBinding));
            proto.write(INPUT_STARTED, mInputStarted);
            proto.write(INPUT_VIEW_STARTED, mInputViewStarted);
            proto.write(CANDIDATES_VIEW_STARTED, mCandidatesViewStarted);
            if (mInputEditorInfo != null) {
                mInputEditorInfo.dumpDebug(proto, INPUT_EDITOR_INFO);
            }
            proto.write(SHOW_INPUT_REQUESTED, mShowInputRequested);
            proto.write(LAST_SHOW_INPUT_REQUESTED, mLastShowInputRequested);
            proto.write(SHOW_INPUT_FLAGS, mShowInputFlags);
            proto.write(CANDIDATES_VISIBILITY, mCandidatesVisibility);
            proto.write(FULLSCREEN_APPLIED, mFullscreenApplied);
            proto.write(IS_FULLSCREEN, mIsFullscreen);
            proto.write(EXTRACT_VIEW_HIDDEN, mExtractViewHidden);
            proto.write(EXTRACTED_TOKEN, mExtractedToken);
            proto.write(IS_INPUT_VIEW_SHOWN, mIsInputViewShown);
            proto.write(STATUS_ICON, mStatusIcon);
            mTmpInsets.dumpDebug(proto, LAST_COMPUTED_INSETS);
            proto.write(SETTINGS_OBSERVER, Objects.toString(mSettingsObserver));
            if (icProto != null) {
                proto.write(INPUT_CONNECTION_CALL, icProto);
            }
            proto.end(token);
        }
    };

    private void compatHandleBack() {
        if (!mDecorViewVisible) {
            Log.e(TAG, "Back callback invoked on a hidden IME. Removing the callback...");
            unregisterDefaultOnBackInvokedCallback();
            return;
        }
        final KeyEvent downEvent = createBackKeyEvent(
                KeyEvent.ACTION_DOWN, false /* isTracking */);
        onKeyDown(KeyEvent.KEYCODE_BACK, downEvent);
        final boolean hasStartedTracking =
                (downEvent.getFlags() & KeyEvent.FLAG_START_TRACKING) != 0;
        final KeyEvent upEvent = createBackKeyEvent(KeyEvent.ACTION_UP, hasStartedTracking);
        onKeyUp(KeyEvent.KEYCODE_BACK, upEvent);
    }

    private boolean methodIsOverridden(String methodName, Class<?>... parameterTypes) {
        try {
            return getClass().getMethod(methodName, parameterTypes).getDeclaringClass()
                    != InputMethodService.class;
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method must exist.", e);
        }
    }
}
