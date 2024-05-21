/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.inputmethod;

import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.os.UserManager.USER_TYPE_SYSTEM_HEADLESS;
import static android.provider.Settings.Secure.STYLUS_HANDWRITING_DEFAULT_VALUE;
import static android.provider.Settings.Secure.STYLUS_HANDWRITING_ENABLED;
import static android.server.inputmethod.InputMethodManagerServiceProto.BACK_DISPOSITION;
import static android.server.inputmethod.InputMethodManagerServiceProto.BOUND_TO_METHOD;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_ATTRIBUTE;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_CLIENT;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_ID;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_METHOD_ID;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_SEQ;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_TOKEN;
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_TOKEN_DISPLAY_ID;
import static android.server.inputmethod.InputMethodManagerServiceProto.HAVE_CONNECTION;
import static android.server.inputmethod.InputMethodManagerServiceProto.IME_WINDOW_VISIBILITY;
import static android.server.inputmethod.InputMethodManagerServiceProto.IN_FULLSCREEN_MODE;
import static android.server.inputmethod.InputMethodManagerServiceProto.IS_INTERACTIVE;
import static android.server.inputmethod.InputMethodManagerServiceProto.LAST_IME_TARGET_WINDOW_NAME;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_IME_WITH_HARD_KEYBOARD;
import static android.server.inputmethod.InputMethodManagerServiceProto.SYSTEM_READY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_HIDE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_OTHER;
import static android.view.inputmethod.ConnectionlessHandwritingCallback.CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED;

import static com.android.server.inputmethod.ImeVisibilityStateComputer.ImeTargetWindowState;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.ImeVisibilityResult;
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_HIDE_IME;
import static com.android.server.inputmethod.InputMethodBindingController.TIME_TO_RECONNECT;
import static com.android.server.inputmethod.InputMethodUtils.isSoftInputModeStateVisibleAllowed;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.DrawableRes;
import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.IntArray;
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.CursorAnchorInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceFileProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodClientsTraceProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodManagerServiceTraceFileProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodManagerServiceTraceProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodServiceTraceFileProto;
import android.view.inputmethod.InputMethodEditorTraceProto.InputMethodServiceTraceProto;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.window.ImeOnBackInvokedDispatcher;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IAccessibilityInputMethodSession;
import com.android.internal.inputmethod.IBooleanListener;
import com.android.internal.inputmethod.IConnectionlessHandwritingCallback;
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.InlineSuggestionsRequestInfo;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal.InputMethodListListener;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * This class provides a system service that manages input methods.
 */
public final class InputMethodManagerService implements IInputMethodManagerImpl.Callback,
        ZeroJankProxy.Callback, Handler.Callback {

    // Virtual device id for test.
    private static final Integer VIRTUAL_STYLUS_ID_FOR_TEST = 999999;
    static final boolean DEBUG = false;
    static final String TAG = "InputMethodManagerService";
    public static final String PROTO_ARG = "--proto";

    @Retention(SOURCE)
    @IntDef({ShellCommandResult.SUCCESS, ShellCommandResult.FAILURE})
    private @interface ShellCommandResult {
        int SUCCESS = 0;
        int FAILURE = -1;
    }

    /**
     * Indicates that the annotated field is not yet ready for concurrent multi-user support.
     *
     * <p>See b/305849394 for details.</p>
     */
    @Retention(SOURCE)
    @Target({ElementType.FIELD})
    private @interface MultiUserUnawareField {
    }

    private static final int MSG_SHOW_IM_SUBTYPE_PICKER = 1;

    private static final int MSG_HIDE_ALL_INPUT_METHODS = 1035;
    private static final int MSG_REMOVE_IME_SURFACE = 1060;
    private static final int MSG_REMOVE_IME_SURFACE_FROM_WINDOW = 1061;
    private static final int MSG_UPDATE_IME_WINDOW_STATUS = 1070;

    private static final int MSG_RESET_HANDWRITING = 1090;
    private static final int MSG_START_HANDWRITING = 1100;
    private static final int MSG_FINISH_HANDWRITING = 1110;
    private static final int MSG_REMOVE_HANDWRITING_WINDOW = 1120;

    private static final int MSG_PREPARE_HANDWRITING_DELEGATION = 1130;

    private static final int MSG_SET_INTERACTIVE = 3030;

    private static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;

    private static final int MSG_SYSTEM_UNLOCK_USER = 5000;
    private static final int MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED = 5010;

    private static final int MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE = 7000;

    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";
    private static final String HANDLER_THREAD_NAME = "android.imms";
    private static final String PACKAGE_MONITOR_THREAD_NAME = "android.imms2";

    /**
     * When set, {@link #startInputUncheckedLocked} will return
     * {@link InputBindResult#NO_EDITOR} instead of starting an IME connection
     * unless {@link StartInputFlags#IS_TEXT_EDITOR} is set. This behavior overrides
     * {@link LayoutParams#SOFT_INPUT_STATE_VISIBLE SOFT_INPUT_STATE_VISIBLE} and
     * {@link LayoutParams#SOFT_INPUT_STATE_ALWAYS_VISIBLE SOFT_INPUT_STATE_ALWAYS_VISIBLE}
     * starting from {@link android.os.Build.VERSION_CODES#P}.
     */
    private final boolean mPreventImeStartupUnlessTextEditor;

    /**
     * These IMEs are known not to behave well when evicted from memory and thus are exempt
     * from the IME startup avoidance behavior that is enabled by
     * {@link #mPreventImeStartupUnlessTextEditor}.
     */
    @NonNull
    private final String[] mNonPreemptibleInputMethods;

    /**
     * See {@link #shouldEnableExperimentalConcurrentMultiUserMode(Context)} about when set to be
     * {@code true}.
     */
    private final boolean mExperimentalConcurrentMultiUserModeEnabled;

    /**
     * Returns {@code true} if experimental concurrent multi-user mode is enabled.
     *
     * <p>Currently not compatible with profiles (e.g. work profile).</p>
     *
     * @param context {@link Context} to be used to query
     *                {@link PackageManager#FEATURE_AUTOMOTIVE}
     * @return {@code true} if experimental concurrent multi-user mode is enabled.
     */
    static boolean shouldEnableExperimentalConcurrentMultiUserMode(@NonNull Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && UserManager.isVisibleBackgroundUsersEnabled()
                && context.getResources().getBoolean(android.R.bool.config_perDisplayFocusEnabled)
                && Flags.concurrentInputMethods();
    }

    final Context mContext;
    final Resources mRes;
    private final Handler mHandler;

    @NonNull
    private final Handler mPackageMonitorHandler;

    @MultiUserUnawareField
    @UserIdInt
    @GuardedBy("ImfLock.class")
    private int mCurrentUserId;

    /** Holds all user related data */
    @GuardedBy("ImfLock.class")
    private UserDataRepository mUserDataRepository;

    @MultiUserUnawareField
    final SettingsObserver mSettingsObserver;
    final WindowManagerInternal mWindowManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;
    final PackageManagerInternal mPackageManagerInternal;
    final InputManagerInternal mInputManagerInternal;
    final ImePlatformCompatUtils mImePlatformCompatUtils;
    final InputMethodDeviceConfigs mInputMethodDeviceConfigs;

    private final UserManagerInternal mUserManagerInternal;
    @MultiUserUnawareField
    private final InputMethodMenuController mMenuController;
    @MultiUserUnawareField
    @NonNull
    private final AutofillSuggestionsController mAutofillController;

    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    @NonNull
    private final ImeVisibilityStateComputer mVisibilityStateComputer;

    @GuardedBy("ImfLock.class")
    @NonNull
    private final DefaultImeVisibilityApplier mVisibilityApplier;

    /**
     * Cache the result of {@code LocalServices.getService(AudioManagerInternal.class)}.
     *
     * <p>This field is used only within {@link #handleMessage(Message)} hence synchronization is
     * not necessary.</p>
     */
    @Nullable
    private AudioManagerInternal mAudioManagerInternal = null;
    @Nullable
    private VirtualDeviceManagerInternal mVdmInternal = null;

    // Mapping from deviceId to the device-specific imeId for that device.
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private final SparseArray<String> mVirtualDeviceMethodMap = new SparseArray<>();

    // TODO: Instantiate mSwitchingController for each user.
    @NonNull
    @MultiUserUnawareField
    private InputMethodSubtypeSwitchingController mSwitchingController;
    // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
    @NonNull
    @MultiUserUnawareField
    private HardwareKeyboardShortcutController mHardwareKeyboardShortcutController;

    /**
     * Tracks how many times {@link #mSettings} was updated.
     */
    @GuardedBy("ImfLock.class")
    private int mMethodMapUpdateCount = 0;

    /**
     * The display id for which the latest startInput was called.
     */
    @GuardedBy("ImfLock.class")
    int getDisplayIdToShowImeLocked() {
        return mDisplayIdToShowIme;
    }

    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private int mDisplayIdToShowIme = INVALID_DISPLAY;

    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private int mDeviceIdToShowIme = DEVICE_ID_DEFAULT;

    @Nullable
    private StatusBarManagerInternal mStatusBarManagerInternal;
    private boolean mShowOngoingImeSwitcherForPhones;
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private final HandwritingModeController mHwController;
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private IntArray mStylusIds;

    @GuardedBy("ImfLock.class")
    @Nullable
    @MultiUserUnawareField
    private OverlayableSystemBooleanResourceWrapper mImeDrawsImeNavBarRes;
    @GuardedBy("ImfLock.class")
    @Nullable
    @MultiUserUnawareField
    Future<?> mImeDrawsImeNavBarResLazyInitFuture;

    private final ImeTracing.ServiceDumper mDumper = new ImeTracing.ServiceDumper() {
        /**
         * {@inheritDoc}
         */
        @Override
        public void dumpToProto(ProtoOutputStream proto, @Nullable byte[] icProto) {
            dumpDebug(proto, InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
        }
    };

    static class SessionState {
        final ClientState mClient;
        final IInputMethodInvoker mMethod;

        IInputMethodSession mSession;
        InputChannel mChannel;

        @Override
        public String toString() {
            return "SessionState{uid=" + mClient.mUid + " pid=" + mClient.mPid
                    + " method=" + Integer.toHexString(
                    IInputMethodInvoker.getBinderIdentityHashCode(mMethod))
                    + " session=" + Integer.toHexString(
                    System.identityHashCode(mSession))
                    + " channel=" + mChannel
                    + "}";
        }

        SessionState(ClientState client, IInputMethodInvoker method,
                IInputMethodSession session, InputChannel channel) {
            mClient = client;
            mMethod = method;
            mSession = session;
            mChannel = channel;
        }
    }

    /**
     * Record session state for an accessibility service.
     */
    static class AccessibilitySessionState {
        final ClientState mClient;
        // Id of the accessibility service.
        final int mId;

        public IAccessibilityInputMethodSession mSession;

        @Override
        public String toString() {
            return "AccessibilitySessionState{uid=" + mClient.mUid + " pid=" + mClient.mPid
                    + " id=" + Integer.toHexString(mId)
                    + " session=" + Integer.toHexString(
                    System.identityHashCode(mSession))
                    + "}";
        }

        AccessibilitySessionState(ClientState client, int id,
                IAccessibilityInputMethodSession session) {
            mClient = client;
            mId = id;
            mSession = session;
        }
    }

    /**
     * Manages the IME clients.
     */
    private final ClientController mClientController;

    /**
     * Holds the current IME binding state info.
     */
    @MultiUserUnawareField
    ImeBindingState mImeBindingState;

    /**
     * Set once the system is ready to run third party code.
     */
    boolean mSystemReady;

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the currently selected input method.
     * This is to be synchronized with the secure settings keyed with
     * {@link Settings.Secure#DEFAULT_INPUT_METHOD}.
     *
     * <p>This can be transiently {@code null} when the system is re-initializing input method
     * settings, e.g., the system locale is just changed.</p>
     *
     * <p>Note that {@link InputMethodBindingController#getCurId()} is used to track which IME
     * is being connected to {@link InputMethodManagerService}.</p>
     *
     * @see InputMethodBindingController#getCurId()
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    String getSelectedMethodIdLocked() {
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        return userData.mBindingController.getSelectedMethodId();
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    InputMethodInfo queryInputMethodForCurrentUserLocked(@NonNull String imeId) {
        return InputMethodSettingsRepository.get(mCurrentUserId).getMethodMap().get(imeId);
    }

    /**
     * The client that is currently bound to an input method.
     */
    @Nullable
    private ClientState mCurClient;

    /**
     * The last window token that we confirmed that IME started talking to.  This is always updated
     * upon reports from the input method.  If the window state is already changed before the report
     * is handled, this field just keeps the last value.
     */
    @MultiUserUnawareField
    IBinder mLastImeTargetWindow;

    /**
     * The {@link IRemoteInputConnection} last provided by the current client.
     */
    @MultiUserUnawareField
    IRemoteInputConnection mCurInputConnection;

    /**
     * The {@link ImeOnBackInvokedDispatcher} last provided by the current client to
     * receive {@link android.window.OnBackInvokedCallback}s forwarded from IME.
     */
    @MultiUserUnawareField
    ImeOnBackInvokedDispatcher mCurImeDispatcher;

    /**
     * The {@link IRemoteAccessibilityInputConnection} last provided by the current client.
     */
    @MultiUserUnawareField
    @Nullable
    IRemoteAccessibilityInputConnection mCurRemoteAccessibilityInputConnection;

    /**
     * The {@link EditorInfo} last provided by the current client.
     */
    @MultiUserUnawareField
    @Nullable
    EditorInfo mCurEditorInfo;

    /**
     * The current subtype of the current input method.
     */
    @MultiUserUnawareField
    private InputMethodSubtype mCurrentSubtype;

    /**
     * Map of window perceptible states indexed by their associated window tokens.
     *
     * The value {@code true} indicates that IME has not been mostly hidden via
     * {@link android.view.InsetsController} for the given window.
     */
    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, Boolean> mFocusedWindowPerceptible = new WeakHashMap<>();

    /**
     * The token tracking the current IME show request that is waiting for a connection to an IME,
     * otherwise {@code null}.
     */
    @Nullable
    @MultiUserUnawareField
    private ImeTracker.Token mCurStatsToken;

    /**
     * {@code true} if the current input method is in fullscreen mode.
     */
    @MultiUserUnawareField
    boolean mInFullscreenMode;

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getCurTokenLocked() {
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        return userData.mBindingController.getCurToken();
    }

    /**
     * The displayId of current active input method.
     */
    @GuardedBy("ImfLock.class")
    int getCurTokenDisplayIdLocked() {
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        return userData.mBindingController.getCurTokenDisplayId();
    }

    /**
     * The display ID of the input method indicates the fallback display which returned by
     * {@link #computeImeDisplayIdForTarget}.
     */
    static final int FALLBACK_DISPLAY_ID = DEFAULT_DISPLAY;

    /**
     * If non-null, this is the input method service we are currently connected
     * to.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IInputMethodInvoker getCurMethodLocked() {
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        return userData.mBindingController.getCurMethod();
    }

    /**
     * Have we called mCurMethod.bindInput()?
     */
    @MultiUserUnawareField
    boolean mBoundToMethod;

    /**
     * Have we called bindInput() for accessibility services?
     */
    @MultiUserUnawareField
    boolean mBoundToAccessibility;

    /**
     * Currently enabled session.
     */
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    SessionState mEnabledSession;
    @MultiUserUnawareField
    SparseArray<AccessibilitySessionState> mEnabledAccessibilitySessions = new SparseArray<>();

    /**
     * True if the device is currently interactive with user.  The value is true initially.
     */
    @MultiUserUnawareField
    boolean mIsInteractive = true;

    @MultiUserUnawareField
    int mBackDisposition = InputMethodService.BACK_DISPOSITION_DEFAULT;

    /**
     * A set of status bits regarding the active IME.
     *
     * <p>This value is a combination of following two bits:</p>
     * <dl>
     * <dt>{@link InputMethodService#IME_ACTIVE}</dt>
     * <dd>
     *   If this bit is ON, connected IME is ready to accept touch/key events.
     * </dd>
     * <dt>{@link InputMethodService#IME_VISIBLE}</dt>
     * <dd>
     *   If this bit is ON, some of IME view, e.g. software input, candidate view, is visible.
     * </dd>
     * <dt>{@link InputMethodService#IME_INVISIBLE}</dt>
     * <dd> If this bit is ON, IME is ready with views from last EditorInfo but is
     *    currently invisible.
     * </dd>
     * </dl>
     * <em>Do not update this value outside of {@link #setImeWindowStatus(IBinder, int, int)} and
     * {@link InputMethodBindingController#unbindCurrentMethod()}.</em>
     */
    @MultiUserUnawareField
    int mImeWindowVis;

    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    private final String mSlotIme;

    /**
     * Registered {@link InputMethodListListener}.
     * This variable can be accessed from both of MainThread and BinderThread.
     */
    private final CopyOnWriteArrayList<InputMethodListListener> mInputMethodListListeners =
            new CopyOnWriteArrayList<>();

    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();

    @GuardedBy("ImfLock.class")
    @NonNull
    private final StartInputHistory mStartInputHistory = new StartInputHistory();

    @GuardedBy("ImfLock.class")
    @NonNull
    private final SoftInputShowHideHistory mSoftInputShowHideHistory =
            new SoftInputShowHideHistory();

    @NonNull
    private final ImeTrackerService mImeTrackerService;

    class SettingsObserver extends ContentObserver {
        int mUserId;
        boolean mRegistered = false;
        @NonNull
        String mLastEnabled = "";

        /**
         * <em>This constructor must be called within the lock.</em>
         */
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @GuardedBy("ImfLock.class")
        public void registerContentObserverLocked(@UserIdInt int userId) {
            if (mRegistered && mUserId == userId) {
                return;
            }
            ContentResolver resolver = mContext.getContentResolver();
            if (mRegistered) {
                mContext.getContentResolver().unregisterContentObserver(this);
                mRegistered = false;
            }
            if (mUserId != userId) {
                mLastEnabled = "";
                mUserId = userId;
            }
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ENABLED_INPUT_METHODS), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE), false, this, userId);
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    STYLUS_HANDWRITING_ENABLED), false, this);
            mRegistered = true;
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            final Uri showImeUri = Settings.Secure.getUriFor(
                    Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);
            final Uri accessibilityRequestingNoImeUri = Settings.Secure.getUriFor(
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);
            final Uri stylusHandwritingEnabledUri = Settings.Secure.getUriFor(
                    STYLUS_HANDWRITING_ENABLED);
            synchronized (ImfLock.class) {
                if (showImeUri.equals(uri)) {
                    mMenuController.updateKeyboardFromSettingsLocked();
                } else if (accessibilityRequestingNoImeUri.equals(uri)) {
                    final int accessibilitySoftKeyboardSetting = Settings.Secure.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0, mUserId);
                    mVisibilityStateComputer.getImePolicy().setA11yRequestNoSoftKeyboard(
                            accessibilitySoftKeyboardSetting);
                    if (mVisibilityStateComputer.getImePolicy().isA11yRequestNoSoftKeyboard()) {
                        hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                                SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE);
                    } else if (isShowRequestedForCurrentWindow()) {
                        showCurrentInputLocked(mImeBindingState.mFocusedWindow,
                                InputMethodManager.SHOW_IMPLICIT,
                                SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE);
                    }
                } else if (stylusHandwritingEnabledUri.equals(uri)) {
                    InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
                    InputMethodManager
                            .invalidateLocalConnectionlessStylusHandwritingAvailabilityCaches();
                } else {
                    boolean enabledChanged = false;
                    String newEnabled = InputMethodSettingsRepository.get(mCurrentUserId)
                            .getEnabledInputMethodsStr();
                    if (!mLastEnabled.equals(newEnabled)) {
                        mLastEnabled = newEnabled;
                        enabledChanged = true;
                    }
                    updateInputMethodsFromSettingsLocked(enabledChanged);
                }
            }
        }

        @Override
        public String toString() {
            return "SettingsObserver{mUserId=" + mUserId + " mRegistered=" + mRegistered
                    + " mLastEnabled=" + mLastEnabled + "}";
        }
    }

    /**
     * {@link BroadcastReceiver} that is intended to listen to broadcasts sent to all the users.
     */
    private final class ImmsBroadcastReceiverForAllUsers extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                final PendingResult pendingResult = getPendingResult();
                if (pendingResult == null) {
                    return;
                }
                // sender userId can be a real user ID or USER_ALL.
                final int senderUserId = pendingResult.getSendingUserId();
                if (senderUserId != UserHandle.USER_ALL) {
                    synchronized (ImfLock.class) {
                        if (senderUserId != mCurrentUserId) {
                            // A background user is trying to hide the dialog. Ignore.
                            return;
                        }
                    }
                }
                mMenuController.hideInputMethodMenu();
            } else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }
        }
    }

    /**
     * Handles {@link Intent#ACTION_LOCALE_CHANGED}.
     *
     * <p>Note: For historical reasons, {@link Intent#ACTION_LOCALE_CHANGED} has been sent to all
     * the users. We should ignore this event if this is about any background user's locale.</p>
     */
    void onActionLocaleChanged(@NonNull LocaleList prevLocales, @NonNull LocaleList newLocales) {
        if (DEBUG) {
            Slog.d(TAG, "onActionLocaleChanged prev=" + prevLocales + " new=" + newLocales);
        }
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }
            for (int userId : mUserManagerInternal.getUserIds()) {
                final InputMethodSettings settings = queryInputMethodServicesInternal(
                        mContext,
                        userId,
                        AdditionalSubtypeMapRepository.get(userId),
                        DirectBootAwareness.AUTO);
                InputMethodSettingsRepository.put(userId, settings);
            }
            postInputMethodSettingUpdatedLocked(true /* resetDefaultEnabledIme */);
            // If the locale is changed, needs to reset the default ime
            resetDefaultImeLocked(mContext);
            updateFromSettingsLocked(true);
        }
    }

    final class MyPackageMonitor extends PackageMonitor {
        /**
         * Remembers package names passed to {@link #onPackageDataCleared(String, int)}.
         *
         * <p>This field must be accessed only from callback methods in {@link PackageMonitor},
         * which should be bound to {@link #getRegisteredHandler()}.</p>
         */
        private ArrayList<String> mDataClearedPackages = new ArrayList<>();

        private MyPackageMonitor() {
            super(true);
        }

        @GuardedBy("ImfLock.class")
        private boolean isChangingPackagesOfCurrentUserLocked() {
            final int userId = getChangingUserId();
            final boolean retval = userId == mCurrentUserId;
            if (DEBUG) {
                if (!retval) {
                    Slog.d(TAG, "--- ignore this call back from a background user: " + userId);
                }
            }
            return retval;
        }

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (ImfLock.class) {
                if (!isChangingPackagesOfCurrentUserLocked()) {
                    return false;
                }
                final InputMethodSettings settings =
                        InputMethodSettingsRepository.get(mCurrentUserId);
                String curInputMethodId = settings.getSelectedInputMethod();
                final List<InputMethodInfo> methodList = settings.getMethodList();
                final int numImes = methodList.size();
                if (curInputMethodId != null) {
                    for (int i = 0; i < numImes; i++) {
                        InputMethodInfo imi = methodList.get(i);
                        if (imi.getId().equals(curInputMethodId)) {
                            for (String pkg : packages) {
                                if (imi.getPackageName().equals(pkg)) {
                                    if (!doit) {
                                        return true;
                                    }
                                    resetSelectedInputMethodAndSubtypeLocked("");
                                    chooseNewDefaultIMELocked();
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        @Override
        public void onBeginPackageChanges() {
            clearPackageChangeState();
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            mDataClearedPackages.add(packageName);
        }

        @Override
        public void onFinishPackageChanges() {
            onFinishPackageChangesInternal();
            clearPackageChangeState();
        }

        private void clearPackageChangeState() {
            // No need to lock them because we access these fields only on getRegisteredHandler().
            mDataClearedPackages.clear();
        }

        private void onFinishPackageChangesInternal() {
            final int userId = getChangingUserId();

            // Instantiating InputMethodInfo requires disk I/O.
            // Do them before acquiring the lock to minimize the chances of ANR (b/340221861).
            final var newMethodMapWithoutAdditionalSubtypes =
                    queryInputMethodServicesInternal(mContext, userId,
                            AdditionalSubtypeMap.EMPTY_MAP, DirectBootAwareness.AUTO)
                            .getMethodMap();

            synchronized (ImfLock.class) {
                final boolean isCurrentUser = (userId == mCurrentUserId);
                final AdditionalSubtypeMap additionalSubtypeMap =
                        AdditionalSubtypeMapRepository.get(userId);
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

                InputMethodInfo curIm = null;
                String curInputMethodId = settings.getSelectedInputMethod();
                final List<InputMethodInfo> methodList = settings.getMethodList();

                final ArrayList<String> imesToClearAdditionalSubtypes = new ArrayList<>();
                final int numImes = methodList.size();
                for (int i = 0; i < numImes; i++) {
                    InputMethodInfo imi = methodList.get(i);
                    final String imiId = imi.getId();
                    if (imiId.equals(curInputMethodId)) {
                        curIm = imi;
                    }
                    if (mDataClearedPackages.contains(imi.getPackageName())) {
                        imesToClearAdditionalSubtypes.add(imiId);
                    }
                    int change = isPackageDisappearing(imi.getPackageName());
                    if (change == PACKAGE_PERMANENT_CHANGE) {
                        Slog.i(TAG, "Input method uninstalled, disabling: " + imi.getComponent());
                        if (isCurrentUser) {
                            setInputMethodEnabledLocked(imi.getId(), false);
                        } else {
                            settings.buildAndPutEnabledInputMethodsStrRemovingId(
                                    new StringBuilder(),
                                    settings.getEnabledInputMethodsAndSubtypeList(),
                                    imi.getId());
                        }
                    } else if (change == PACKAGE_UPDATING) {
                        Slog.i(TAG, "Input method reinstalling, clearing additional subtypes: "
                                + imi.getComponent());
                        imesToClearAdditionalSubtypes.add(imiId);
                    }
                }

                // Clear additional subtypes as a batch operation.
                final AdditionalSubtypeMap newAdditionalSubtypeMap =
                        additionalSubtypeMap.cloneWithRemoveOrSelf(imesToClearAdditionalSubtypes);
                final boolean additionalSubtypeChanged =
                        (newAdditionalSubtypeMap != additionalSubtypeMap);
                if (additionalSubtypeChanged) {
                    AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                            settings.getMethodMap());
                }

                final var newMethodMap = newMethodMapWithoutAdditionalSubtypes
                        .applyAdditionalSubtypes(newAdditionalSubtypeMap);

                if (InputMethodMap.areSame(settings.getMethodMap(), newMethodMap)) {
                    // No update in the actual IME map.
                    return;
                }

                final InputMethodSettings newSettings =
                        InputMethodSettings.create(newMethodMap, userId);
                InputMethodSettingsRepository.put(userId, newSettings);
                if (!isCurrentUser) {
                    return;
                }
                postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */);

                boolean changed = false;

                if (curIm != null) {
                    int change = isPackageDisappearing(curIm.getPackageName());
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        final PackageManager userAwarePackageManager =
                                getPackageManagerForUser(mContext, userId);
                        ServiceInfo si = null;
                        try {
                            si = userAwarePackageManager.getServiceInfo(curIm.getComponent(),
                                    PackageManager.ComponentInfoFlags.of(0));
                        } catch (PackageManager.NameNotFoundException ignored) {
                        }
                        if (si == null) {
                            // Uh oh, current input method is no longer around!
                            // Pick another one...
                            Slog.i(TAG, "Current input method removed: " + curInputMethodId);
                            updateSystemUiLocked(0 /* vis */, mBackDisposition);
                            if (!chooseNewDefaultIMELocked()) {
                                changed = true;
                                curIm = null;
                                Slog.i(TAG, "Unsetting current input method");
                                resetSelectedInputMethodAndSubtypeLocked("");
                            }
                        }
                    }
                }

                if (curIm == null) {
                    // We currently don't have a default input method... is
                    // one now available?
                    changed = chooseNewDefaultIMELocked();
                } else if (!changed && isPackageModified(curIm.getPackageName())) {
                    // Even if the current input method is still available, mCurrentSubtype could
                    // be obsolete when the package is modified in practice.
                    changed = true;
                }

                if (changed) {
                    updateFromSettingsLocked(false);
                }
            }
        }
    }

    private static final class UserSwitchHandlerTask implements Runnable {
        final InputMethodManagerService mService;

        @UserIdInt
        final int mToUserId;

        @Nullable
        IInputMethodClientInvoker mClientToBeReset;

        UserSwitchHandlerTask(InputMethodManagerService service, @UserIdInt int toUserId,
                @Nullable IInputMethodClientInvoker clientToBeReset) {
            mService = service;
            mToUserId = toUserId;
            mClientToBeReset = clientToBeReset;
        }

        @Override
        public void run() {
            synchronized (ImfLock.class) {
                if (mService.mUserSwitchHandlerTask != this) {
                    // This task was already canceled before it is handled here. So do nothing.
                    return;
                }
                mService.switchUserOnHandlerLocked(mService.mUserSwitchHandlerTask.mToUserId,
                        mClientToBeReset);
                mService.mUserSwitchHandlerTask = null;
            }
        }
    }

    /**
     * When non-{@code null}, this represents pending user-switch task, which is to be executed as
     * a handler callback.  This needs to be set and unset only within the lock.
     */
    @Nullable
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private UserSwitchHandlerTask mUserSwitchHandlerTask;

    /**
     * {@link SystemService} used to publish and manage the lifecycle of
     * {@link InputMethodManagerService}.
     */
    public static final class Lifecycle extends SystemService {
        private final InputMethodManagerService mService;


        public Lifecycle(Context context) {
            this(context, new InputMethodManagerService(context,
                            shouldEnableExperimentalConcurrentMultiUserMode(context)));
        }

        public Lifecycle(
                Context context, @NonNull InputMethodManagerService inputMethodManagerService) {
            super(context);
            mService = inputMethodManagerService;
        }

        @Override
        public void onStart() {
            mService.publishLocalService();
            IInputMethodManagerImpl.Callback service;
            if (Flags.useZeroJankProxy()) {
                service = new ZeroJankProxy(mService.mHandler::post, mService);
            } else {
                service = mService;
            }
            publishBinderService(Context.INPUT_METHOD_SERVICE,
                    IInputMethodManagerImpl.create(service), false /*allowIsolated*/,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            // Called on ActivityManager thread.
            synchronized (ImfLock.class) {
                if (mService.mExperimentalConcurrentMultiUserModeEnabled) {
                    // In concurrent multi-user mode, we in general do not rely on the concept of
                    // current user.
                    return;
                }
                mService.scheduleSwitchUserTaskLocked(to.getUserIdentifier(),
                        /* clientToBeReset= */ null);
            }
        }

        @Override
        public void onBootPhase(int phase) {
            // Called on ActivityManager thread.
            // TODO: Dispatch this to a worker thread as needed.
            if (phase == SystemService.PHASE_ACTIVITY_MANAGER_READY) {
                mService.systemRunning();
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            // Called on ActivityManager thread.
            SecureSettingsWrapper.onUserUnlocking(user.getUserIdentifier());
            mService.mHandler.obtainMessage(MSG_SYSTEM_UNLOCK_USER, user.getUserIdentifier(), 0)
                    .sendToTarget();
        }

        @Override
        public void onUserStarting(TargetUser user) {
            // Called on ActivityManager thread.
            final int userId = user.getUserIdentifier();
            SecureSettingsWrapper.onUserStarting(userId);
            synchronized (ImfLock.class) {
                mService.mUserDataRepository.getOrCreate(userId);
                if (mService.mExperimentalConcurrentMultiUserModeEnabled) {
                    if (mService.mCurrentUserId != userId) {
                        mService.experimentalInitializeVisibleBackgroundUserLocked(userId);
                    }
                }
            }
        }

    }

    void onUnlockUser(@UserIdInt int userId) {
        synchronized (ImfLock.class) {
            if (DEBUG) {
                Slog.d(TAG, "onUnlockUser: userId=" + userId + " curUserId=" + mCurrentUserId);
            }
            if (!mSystemReady) {
                return;
            }
            final InputMethodSettings newSettings = queryInputMethodServicesInternal(mContext,
                    userId, AdditionalSubtypeMapRepository.get(userId), DirectBootAwareness.AUTO);
            InputMethodSettingsRepository.put(userId, newSettings);
            if (mCurrentUserId == userId) {
                // We need to rebuild IMEs.
                postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */);
                updateInputMethodsFromSettingsLocked(true /* enabledChanged */);
            } else if (mExperimentalConcurrentMultiUserModeEnabled) {
                experimentalInitializeVisibleBackgroundUserLocked(userId);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void scheduleSwitchUserTaskLocked(@UserIdInt int userId,
            @Nullable IInputMethodClientInvoker clientToBeReset) {
        if (mUserSwitchHandlerTask != null) {
            if (mUserSwitchHandlerTask.mToUserId == userId) {
                mUserSwitchHandlerTask.mClientToBeReset = clientToBeReset;
                return;
            }
            mHandler.removeCallbacks(mUserSwitchHandlerTask);
        }
        // Hide soft input before user switch task since switch task may block main handler a while
        // and delayed the hideCurrentInputLocked().
        hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                SoftInputShowHideReason.HIDE_SWITCH_USER);
        final UserSwitchHandlerTask task = new UserSwitchHandlerTask(this, userId,
                clientToBeReset);
        mUserSwitchHandlerTask = task;
        mHandler.post(task);
    }

    public InputMethodManagerService(Context context,
            boolean experimentalConcurrentMultiUserModeEnabled) {
        this(context, experimentalConcurrentMultiUserModeEnabled, null, null, null);
    }

    @VisibleForTesting
    InputMethodManagerService(
            Context context,
            boolean experimentalConcurrentMultiUserModeEnabled,
            @Nullable ServiceThread serviceThreadForTesting,
            @Nullable ServiceThread packageMonitorThreadForTesting,
            @Nullable IntFunction<InputMethodBindingController> bindingControllerForTesting) {
        synchronized (ImfLock.class) {
            mExperimentalConcurrentMultiUserModeEnabled =
                    experimentalConcurrentMultiUserModeEnabled;
            mContext = context;
            mRes = context.getResources();
            SecureSettingsWrapper.onStart(mContext);

            // TODO(b/196206770): Disallow I/O on this thread. Currently it's needed for loading
            // additional subtypes in switchUserOnHandlerLocked().
            final ServiceThread thread =
                    serviceThreadForTesting != null
                            ? serviceThreadForTesting
                            : new ServiceThread(
                                    HANDLER_THREAD_NAME,
                                    Process.THREAD_PRIORITY_FOREGROUND,
                                    true /* allowIo */);
            thread.start();
            mHandler = Handler.createAsync(thread.getLooper(), this);
            {
                final ServiceThread packageMonitorThread =
                        packageMonitorThreadForTesting != null
                                ? packageMonitorThreadForTesting
                                : new ServiceThread(
                                        PACKAGE_MONITOR_THREAD_NAME,
                                        Process.THREAD_PRIORITY_FOREGROUND,
                                        true /* allowIo */);
                packageMonitorThread.start();
                mPackageMonitorHandler = Handler.createAsync(packageMonitorThread.getLooper());
            }
            SystemLocaleWrapper.onStart(context, this::onActionLocaleChanged, mHandler);
            mImeTrackerService = new ImeTrackerService(serviceThreadForTesting != null
                    ? serviceThreadForTesting.getLooper() : Looper.getMainLooper());
            // Note: SettingsObserver doesn't register observers in its constructor.
            mSettingsObserver = new SettingsObserver(mHandler);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
            mImePlatformCompatUtils = new ImePlatformCompatUtils();
            mInputMethodDeviceConfigs = new InputMethodDeviceConfigs();
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);

            mSlotIme = mContext.getString(com.android.internal.R.string.status_bar_ime);

            mShowOngoingImeSwitcherForPhones = false;

            // InputMethodSettingsRepository should be initialized before buildInputMethodListLocked
            InputMethodSettingsRepository.initialize(mHandler, mContext);
            AdditionalSubtypeMapRepository.initialize(mHandler, mContext);

            final int currentUserId = mActivityManagerInternal.getCurrentUserId();

            // For concurrent multi-user mode, we try to initialize mCurrentUserId with main
            // user rather than the current user when possible.
            mCurrentUserId = mExperimentalConcurrentMultiUserModeEnabled
                    ? MultiUserUtils.getFirstMainUserIdOrDefault(
                            mUserManagerInternal, currentUserId)
                    : currentUserId;

            @SuppressWarnings("GuardedBy") final IntFunction<InputMethodBindingController>
                    bindingControllerFactory = userId -> new InputMethodBindingController(userId,
                    InputMethodManagerService.this);
            mUserDataRepository = new UserDataRepository(mHandler, mUserManagerInternal,
                    bindingControllerForTesting != null ? bindingControllerForTesting
                            : bindingControllerFactory);
            for (int id : mUserManagerInternal.getUserIds()) {
                mUserDataRepository.getOrCreate(id);
            }

            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);

            mSwitchingController =
                    InputMethodSubtypeSwitchingController.createInstanceLocked(context,
                            settings.getMethodMap(), settings.getUserId());
            mHardwareKeyboardShortcutController =
                    new HardwareKeyboardShortcutController(settings.getMethodMap(),
                            settings.getUserId());
            mMenuController = new InputMethodMenuController(this);
            mAutofillController = new AutofillSuggestionsController(this);
            mVisibilityStateComputer = new ImeVisibilityStateComputer(this);
            mVisibilityApplier = new DefaultImeVisibilityApplier(this);

            mClientController = new ClientController(mPackageManagerInternal);
            mClientController.addClientControllerCallback(c -> onClientRemoved(c));
            mImeBindingState = ImeBindingState.newEmptyState();

            mPreventImeStartupUnlessTextEditor = mRes.getBoolean(
                    com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
            mNonPreemptibleInputMethods = mRes.getStringArray(
                    com.android.internal.R.array.config_nonPreemptibleInputMethods);
            IntConsumer toolTypeConsumer =
                    Flags.useHandwritingListenerForTooltype()
                            ? toolType -> onUpdateEditorToolType(toolType) : null;
            Runnable discardDelegationTextRunnable = () -> discardHandwritingDelegationText();
            mHwController = new HandwritingModeController(mContext, thread.getLooper(),
                    new InkWindowInitializer(), toolTypeConsumer, discardDelegationTextRunnable);
            registerDeviceListenerAndCheckStylusSupport();
        }
    }

    @GuardedBy("ImfLock.class")
    @UserIdInt
    int getCurrentImeUserIdLocked() {
        return mCurrentUserId;
    }

    private final class InkWindowInitializer implements Runnable {
        public void run() {
            synchronized (ImfLock.class) {
                IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null) {
                    curMethod.initInkWindow();
                }
            }
        }
    }

    private void onUpdateEditorToolType(int toolType) {
        synchronized (ImfLock.class) {
            IInputMethodInvoker curMethod = getCurMethodLocked();
            if (curMethod != null) {
                curMethod.updateEditorToolType(toolType);
            }
        }
    }

    private void discardHandwritingDelegationText() {
        synchronized (ImfLock.class) {
            IInputMethodInvoker curMethod = getCurMethodLocked();
            if (curMethod != null) {
                curMethod.discardHandwritingDelegationText();
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void resetDefaultImeLocked(Context context) {
        // Do not reset the default (current) IME when it is a 3rd-party IME
        String selectedMethodId = getSelectedMethodIdLocked();
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        if (selectedMethodId != null
                && !settings.getMethodMap().get(selectedMethodId).isSystem()) {
            return;
        }
        final List<InputMethodInfo> suitableImes = InputMethodInfoUtils.getDefaultEnabledImes(
                context, settings.getEnabledInputMethodList());
        if (suitableImes.isEmpty()) {
            Slog.i(TAG, "No default found");
            return;
        }
        final InputMethodInfo defIm = suitableImes.get(0);
        if (DEBUG) {
            Slog.i(TAG, "Default found, using " + defIm.getId());
        }
        setSelectedInputMethodAndSubtypeLocked(defIm, NOT_A_SUBTYPE_ID, false);
    }

    @GuardedBy("ImfLock.class")
    private void maybeInitImeNavbarConfigLocked(@UserIdInt int targetUserId) {
        // Currently, com.android.internal.R.bool.config_imeDrawsImeNavBar is overlaid only for the
        // profile parent user.
        // TODO(b/221443458): See if we can make OverlayManager be aware of profile groups.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(targetUserId);
        if (mImeDrawsImeNavBarRes != null
                && mImeDrawsImeNavBarRes.getUserId() != profileParentUserId) {
            mImeDrawsImeNavBarRes.close();
            mImeDrawsImeNavBarRes = null;
        }
        if (mImeDrawsImeNavBarRes == null) {
            final Context userContext;
            if (mContext.getUserId() == profileParentUserId) {
                userContext = mContext;
            } else {
                userContext = mContext.createContextAsUser(UserHandle.of(profileParentUserId),
                        0 /* flags */);
            }
            mImeDrawsImeNavBarRes = OverlayableSystemBooleanResourceWrapper.create(userContext,
                    com.android.internal.R.bool.config_imeDrawsImeNavBar, mHandler, resource -> {
                        synchronized (ImfLock.class) {
                            if (resource == mImeDrawsImeNavBarRes) {
                                sendOnNavButtonFlagsChangedLocked();
                            }
                        }
                    });
        }
    }

    @NonNull
    private static PackageManager getPackageManagerForUser(@NonNull Context context,
            @UserIdInt int userId) {
        return context.getUserId() == userId
                ? context.getPackageManager()
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */)
                        .getPackageManager();
    }

    @GuardedBy("ImfLock.class")
    private void switchUserOnHandlerLocked(@UserIdInt int newUserId,
            IInputMethodClientInvoker clientToBeReset) {
        if (DEBUG) {
            Slog.d(TAG, "Switching user stage 1/3. newUserId=" + newUserId
                    + " currentUserId=" + mCurrentUserId);
        }

        // Clean up stuff for mCurrentUserId, which soon becomes the previous user.

        // TODO(b/338461930): Check if this is still necessary or not.
        onUnbindCurrentMethodByReset();

        // Note that in b/197848765 we want to see if we can keep the binding alive for better
        // profile switching.
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        final var bindingController = userData.mBindingController;
        bindingController.unbindCurrentMethod();

        unbindCurrentClientLocked(UnbindReason.SWITCH_USER);

        // Hereafter we start initializing things for "newUserId".

        maybeInitImeNavbarConfigLocked(newUserId);

        // ContentObserver should be registered again when the user is changed
        mSettingsObserver.registerContentObserverLocked(newUserId);

        mCurrentUserId = newUserId;
        final String defaultImiId = SecureSettingsWrapper.getString(
                Settings.Secure.DEFAULT_INPUT_METHOD, null, newUserId);

        if (DEBUG) {
            Slog.d(TAG, "Switching user stage 2/3. newUserId=" + newUserId
                    + " defaultImiId=" + defaultImiId);
        }

        // For secondary users, the list of enabled IMEs may not have been updated since the
        // callbacks to PackageMonitor are ignored for the secondary user. Here, defaultImiId may
        // not be empty even if the IME has been uninstalled by the primary user.
        // Even in such cases, IMMS works fine because it will find the most applicable
        // IME for that user.
        final boolean initialUserSwitch = TextUtils.isEmpty(defaultImiId);

        final InputMethodSettings newSettings = InputMethodSettingsRepository.get(newUserId);
        postInputMethodSettingUpdatedLocked(initialUserSwitch /* resetDefaultEnabledIme */);
        if (TextUtils.isEmpty(newSettings.getSelectedInputMethod())) {
            // This is the first time of the user switch and
            // set the current ime to the proper one.
            resetDefaultImeLocked(mContext);
        }
        updateFromSettingsLocked(true);

        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                    getPackageManagerForUser(mContext, newUserId),
                    newSettings.getEnabledInputMethodList());
        }

        if (DEBUG) {
            Slog.d(TAG, "Switching user stage 3/3. newUserId=" + newUserId
                    + " selectedIme=" + newSettings.getSelectedInputMethod());
        }

        if (mIsInteractive && clientToBeReset != null) {
            final ClientState cs = mClientController.getClient(clientToBeReset.asBinder());
            if (cs == null) {
                // The client is already gone.
                return;
            }
            cs.mClient.scheduleStartInputIfNecessary(mInFullscreenMode);
        }
    }

    /**
     * TODO(b/32343335): The entire systemRunning() method needs to be revisited.
     */
    public void systemRunning() {
        synchronized (ImfLock.class) {
            if (DEBUG) {
                Slog.d(TAG, "--- systemReady");
            }
            if (!mSystemReady) {
                mSystemReady = true;
                final int currentUserId = mCurrentUserId;
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
                hideStatusBarIconLocked();
                updateSystemUiLocked(mImeWindowVis, mBackDisposition);
                mShowOngoingImeSwitcherForPhones = mRes.getBoolean(
                        com.android.internal.R.bool.show_ongoing_ime_switcher);
                if (mShowOngoingImeSwitcherForPhones) {
                    mWindowManagerInternal.setOnHardKeyboardStatusChangeListener(available -> {
                        mHandler.obtainMessage(MSG_HARD_KEYBOARD_SWITCH_CHANGED,
                                available ? 1 : 0, 0 /* unused */).sendToTarget();
                    });
                }

                // TODO(b/32343335): The entire systemRunning() method needs to be revisited.
                mImeDrawsImeNavBarResLazyInitFuture = SystemServerInitThreadPool.submit(() -> {
                    // Note that the synchronization block below guarantees that the task
                    // can never be completed before the returned Future<?> object is assigned to
                    // the "mImeDrawsImeNavBarResLazyInitFuture" field.
                    synchronized (ImfLock.class) {
                        mImeDrawsImeNavBarResLazyInitFuture = null;
                        if (currentUserId != mCurrentUserId) {
                            // This means that the current user is already switched to other user
                            // before the background task is executed. In this scenario the relevant
                            // field should already be initialized.
                            return;
                        }
                        maybeInitImeNavbarConfigLocked(currentUserId);
                    }
                }, "Lazily initialize IMMS#mImeDrawsImeNavBarRes");

                mMyPackageMonitor.register(mContext, UserHandle.ALL, mPackageMonitorHandler);
                mSettingsObserver.registerContentObserverLocked(currentUserId);

                final IntentFilter broadcastFilterForAllUsers = new IntentFilter();
                broadcastFilterForAllUsers.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                mContext.registerReceiverAsUser(new ImmsBroadcastReceiverForAllUsers(),
                        UserHandle.ALL, broadcastFilterForAllUsers, null, null,
                        Context.RECEIVER_EXPORTED);

                final String defaultImiId = SecureSettingsWrapper.getString(
                        Settings.Secure.DEFAULT_INPUT_METHOD, null, currentUserId);
                final boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
                final InputMethodSettings newSettings = queryInputMethodServicesInternal(mContext,
                        currentUserId, AdditionalSubtypeMapRepository.get(currentUserId),
                        DirectBootAwareness.AUTO);
                InputMethodSettingsRepository.put(currentUserId, newSettings);
                postInputMethodSettingUpdatedLocked(
                        !imeSelectedOnBoot /* resetDefaultEnabledIme */);
                updateFromSettingsLocked(true);
                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                        getPackageManagerForUser(mContext, currentUserId),
                        newSettings.getEnabledInputMethodList());
            }
        }
    }

    /**
     * Returns true iff the caller is identified to be the current input method with the token.
     *
     * @param token the window token given to the input method when it was started
     * @return true if and only if non-null valid token is specified
     */
    @GuardedBy("ImfLock.class")
    private boolean calledWithValidTokenLocked(@NonNull IBinder token) {
        if (token == null) {
            throw new InvalidParameterException("token must not be null.");
        }
        if (token != getCurTokenLocked()) {
            Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to an invalid token."
                    + " uid:" + Binder.getCallingUid() + " token:" + token);
            return false;
        }
        return true;
    }

    @BinderThread
    @Nullable
    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            return queryDefaultInputMethodForUserIdLocked(userId);
        }
    }

    @BinderThread
    @NonNull
    @Override
    public List<InputMethodInfo> getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            final int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId,
                    mCurrentUserId, null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            final int callingUid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return getInputMethodListLocked(
                        resolvedUserIds[0], directBootAwareness, callingUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public List<InputMethodInfo> getEnabledInputMethodList(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            final int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId,
                    mCurrentUserId, null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            final int callingUid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodListLocked(resolvedUserIds[0], callingUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public boolean isStylusHandwritingAvailableAsUser(
            @UserIdInt int userId, boolean connectionless) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }

        synchronized (ImfLock.class) {
            if (!isStylusHandwritingEnabled(mContext, userId)) {
                return false;
            }

            // Check if selected IME of current user supports handwriting.
            if (userId == mCurrentUserId) {
                final var userData = mUserDataRepository.getOrCreate(userId);
                final var bindingController = userData.mBindingController;
                return bindingController.supportsStylusHandwriting()
                        && (!connectionless
                        || bindingController.supportsConnectionlessStylusHandwriting());
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
            final InputMethodInfo imi = settings.getMethodMap().get(
                    settings.getSelectedInputMethod());
            return imi != null && imi.supportsStylusHandwriting()
                    && (!connectionless || imi.supportsConnectionlessStylusHandwriting());
        }
    }

    private boolean isStylusHandwritingEnabled(
            @NonNull Context context, @UserIdInt int userId) {
        // If user is a profile, use preference of it`s parent profile.
        final int profileParentUserId = mUserManagerInternal.getProfileParentId(userId);
        if (Settings.Secure.getIntForUser(context.getContentResolver(),
                STYLUS_HANDWRITING_ENABLED, STYLUS_HANDWRITING_DEFAULT_VALUE,
                profileParentUserId) == 0) {
            return false;
        }
        return true;
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getInputMethodListLocked(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness, int callingUid) {
        final InputMethodSettings settings;
        if (directBootAwareness == DirectBootAwareness.AUTO) {
            settings = InputMethodSettingsRepository.get(userId);
        } else {
            final AdditionalSubtypeMap additionalSubtypeMap =
                    AdditionalSubtypeMapRepository.get(userId);
            settings = queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap,
                    directBootAwareness);
        }
        // Create a copy.
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>(settings.getMethodList());
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getEnabledInputMethodListLocked(@UserIdInt int userId,
            int callingUid) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final ArrayList<InputMethodInfo> methodList = settings.getEnabledInputMethodList();
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    @GuardedBy("ImfLock.class")
    void performOnCreateInlineSuggestionsRequestLocked() {
        mAutofillController.performOnCreateInlineSuggestionsRequest();
    }

    /**
     * Gets enabled subtypes of the specified {@link InputMethodInfo}.
     *
     * @param imiId                           if null, returns enabled subtypes for the current
     *                                        {@link InputMethodInfo}
     * @param allowsImplicitlyEnabledSubtypes {@code true} to return the implicitly enabled
     *                                        subtypes
     * @param userId                          the user ID to be queried about
     */
    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }

        synchronized (ImfLock.class) {
            final int callingUid = Binder.getCallingUid();
            final long ident = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodSubtypeListLocked(imiId,
                        allowsImplicitlyEnabledSubtypes, userId, callingUid);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(String imiId,
            boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId, int callingUid) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = settings.getMethodMap().get(imiId);
        if (imi == null) {
            return Collections.emptyList();
        }
        if (!canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings)) {
            return Collections.emptyList();
        }
        return settings.getEnabledInputMethodSubtypeList(
                imi, allowsImplicitlyEnabledSubtypes);
    }

    /**
     * Called by each application process as a preparation to start interacting with
     * {@link InputMethodManagerService}.
     *
     * <p>As a general principle, IPCs from the application process that take
     * {@link IInputMethodClient} will be rejected without this step.</p>
     *
     * @param client                {@link android.os.Binder} proxy that is associated with the
     *                              singleton instance of
     *                              {@link android.view.inputmethod.InputMethodManager} that runs
     *                              on the client process
     * @param inputConnection       communication channel for the fallback {@link InputConnection}
     * @param selfReportedDisplayId self-reported display ID to which the client is associated.
     *                              Whether the client is still allowed to access to this display
     *                              or not needs to be evaluated every time the client interacts
     *                              with the display
     */
    @Override
    public void addClient(IInputMethodClient client, IRemoteInputConnection inputConnection,
            int selfReportedDisplayId) {
        // Here there are two scenarios where this method is called:
        // A. IMM is being instantiated in a different process and this is an IPC from that process
        // B. IMM is being instantiated in the same process but Binder.clearCallingIdentity() is
        //    called in the caller side if necessary.
        // In either case the following UID/PID should be the ones where InputMethodManager is
        // actually running.
        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        final IInputMethodClientInvoker clientInvoker =
                IInputMethodClientInvoker.create(client, mHandler);
        synchronized (ImfLock.class) {
            mClientController.addClient(clientInvoker, inputConnection, selfReportedDisplayId,
                    callerUid, callerPid);
        }
    }

    /**
     * Hide the IME if the removed user is the current user.
     */
    // TODO(b/325515685): Move this method to InputMethodBindingController
    @GuardedBy("ImfLock.class")
    private void onClientRemoved(ClientState client) {
        clearClientSessionLocked(client);
        clearClientSessionForAccessibilityLocked(client);
        if (mCurClient == client) {
            hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_REMOVE_CLIENT);
            if (mBoundToMethod) {
                mBoundToMethod = false;
                IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null) {
                    // When we unbind input, we are unbinding the client, so we always
                    // unbind ime and a11y together.
                    curMethod.unbindInput();
                    AccessibilityManagerInternal.get().unbindInput();
                }
            }
            mBoundToAccessibility = false;
            mCurClient = null;
            if (mImeBindingState.mFocusedWindowClient == client) {
                mImeBindingState = ImeBindingState.newEmptyState();
            }
        }
    }

    @Nullable
    @GuardedBy("ImfLock.class")
    @Override
    public ClientState getClientStateLocked(IInputMethodClient client) {
        return mClientController.getClient(client.asBinder());
    }

    @GuardedBy("ImfLock.class")
    void unbindCurrentClientLocked(@UnbindReason int unbindClientReason) {
        if (mCurClient != null) {
            if (DEBUG) {
                Slog.v(TAG, "unbindCurrentInputLocked: client=" + mCurClient.mClient.asBinder());
            }
            if (mBoundToMethod) {
                mBoundToMethod = false;
                IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null) {
                    curMethod.unbindInput();
                }
            }
            mBoundToAccessibility = false;

            // Since we set active false to current client and set mCurClient to null, let's unbind
            // all accessibility too. That means, when input method get disconnected (including
            // switching ime), we also unbind accessibility
            mCurClient.mClient.setActive(false /* active */, false /* fullscreen */);

            // TODO(b/325515685): make binding controller user independent. Before this change, the
            //  following dependencies also need to be user independent: mCurClient, mBoundToMethod,
            //  getCurMethodLocked(), and mMenuController.
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            final var bindingController = userData.mBindingController;
            mCurClient.mClient.onUnbindMethod(bindingController.getSequenceNumber(),
                    unbindClientReason);
            mCurClient.mSessionRequested = false;
            mCurClient.mSessionRequestedForAccessibility = false;
            mCurClient = null;
            ImeTracker.forLogging().onFailed(mCurStatsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
            mCurStatsToken = null;
            mMenuController.hideInputMethodMenuLocked();
        }
    }

    /**
     * Called when {@link #resetCurrentMethodAndClientLocked(int)} invoked for clean-up states
     * before unbinding the current method.
     */
    @GuardedBy("ImfLock.class")
    void onUnbindCurrentMethodByReset() {
        final ImeTargetWindowState winState = mVisibilityStateComputer.getWindowStateOrNull(
                mImeBindingState.mFocusedWindow);
        if (winState != null && !winState.isRequestedImeVisible()
                && !mVisibilityStateComputer.isInputShown()) {
            // Normally, the focus window will apply the IME visibility state to
            // WindowManager when the IME has applied it. But it would be too late when
            // switching IMEs in between different users. (Since the focused IME will
            // first unbind the service to switch to bind the next user of the IME
            // service, that wouldn't make the attached IME token validity check in time)
            // As a result, we have to notify WM to apply IME visibility before clearing the
            // binding states in the first place.
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.UNBIND_CURRENT_METHOD);
            mVisibilityApplier.applyImeVisibility(mImeBindingState.mFocusedWindow, statsToken,
                    STATE_HIDE_IME);
        }
    }

    /**
     * {@code true} when a {@link ClientState} has attached from starting the
     * input connection.
     */
    @GuardedBy("ImfLock.class")
    boolean hasAttachedClient() {
        return mCurClient != null;
    }

    @VisibleForTesting
    void setAttachedClientForTesting(@NonNull ClientState cs) {
        synchronized (ImfLock.class) {
            mCurClient = cs;
        }
    }

    @GuardedBy("ImfLock.class")
    void clearInputShownLocked() {
        mVisibilityStateComputer.setInputShown(false);
    }

    @GuardedBy("ImfLock.class")
    @Override
    public boolean isInputShownLocked() {
        return mVisibilityStateComputer.isInputShown();
    }

    @GuardedBy("ImfLock.class")
    private boolean isShowRequestedForCurrentWindow() {
        final ImeTargetWindowState state = mVisibilityStateComputer.getWindowStateOrNull(
                mImeBindingState.mFocusedWindow);
        return state != null && state.isRequestedImeVisible();
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    InputBindResult attachNewInputLocked(@StartInputReason int startInputReason, boolean initial) {
        if (!mBoundToMethod) {
            getCurMethodLocked().bindInput(mCurClient.mBinding);
            mBoundToMethod = true;
        }

        final boolean restarting = !initial;
        final Binder startInputToken = new Binder();
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        final var bindingController = userData.mBindingController;
        final StartInputInfo info = new StartInputInfo(mCurrentUserId,
                getCurTokenLocked(),
                getCurTokenDisplayIdLocked(), bindingController.getCurId(), startInputReason,
                restarting, UserHandle.getUserId(mCurClient.mUid),
                mCurClient.mSelfReportedDisplayId, mImeBindingState.mFocusedWindow, mCurEditorInfo,
                mImeBindingState.mFocusedWindowSoftInputMode,
                bindingController.getSequenceNumber());
        mImeTargetWindowMap.put(startInputToken, mImeBindingState.mFocusedWindow);
        mStartInputHistory.addEntry(info);

        // Seems that PackageManagerInternal#grantImplicitAccess() doesn't handle cross-user
        // implicit visibility (e.g. IME[user=10] -> App[user=0]) thus we do this only for the
        // same-user scenarios.
        // That said ignoring cross-user scenario will never affect IMEs that do not have
        // INTERACT_ACROSS_USERS(_FULL) permissions, which is actually almost always the case.
        if (mCurrentUserId == UserHandle.getUserId(
                mCurClient.mUid)) {
            mPackageManagerInternal.grantImplicitAccess(mCurrentUserId, null /* intent */,
                    UserHandle.getAppId(bindingController.getCurMethodUid()),
                    mCurClient.mUid, true /* direct */);
        }

        @InputMethodNavButtonFlags final int navButtonFlags = getInputMethodNavButtonFlagsLocked();
        final SessionState session = mCurClient.mCurSession;
        setEnabledSessionLocked(session);
        session.mMethod.startInput(startInputToken, mCurInputConnection, mCurEditorInfo, restarting,
                navButtonFlags, mCurImeDispatcher);
        if (isShowRequestedForCurrentWindow()) {
            if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
            // Re-use current statsToken, if it exists.
            final var statsToken = mCurStatsToken != null ? mCurStatsToken
                    : createStatsTokenForFocusedClient(true /* show */,
                            SoftInputShowHideReason.ATTACH_NEW_INPUT);
            mCurStatsToken = null;
            showCurrentInputLocked(mImeBindingState.mFocusedWindow, statsToken,
                    mVisibilityStateComputer.getShowFlags(), MotionEvent.TOOL_TYPE_UNKNOWN,
                    null /* resultReceiver */, SoftInputShowHideReason.ATTACH_NEW_INPUT);
        }

        final var curId = bindingController.getCurId();
        final InputMethodInfo curInputMethodInfo = InputMethodSettingsRepository.get(mCurrentUserId)
                .getMethodMap().get(curId);
        final boolean suppressesSpellChecker =
                curInputMethodInfo != null && curInputMethodInfo.suppressesSpellChecker();
        final SparseArray<IAccessibilityInputMethodSession> accessibilityInputMethodSessions =
                createAccessibilityInputMethodSessions(mCurClient.mAccessibilitySessions);
        if (bindingController.supportsStylusHandwriting() && hasSupportedStylusLocked()) {
            mHwController.setInkWindowInitializer(new InkWindowInitializer());
        }
        return new InputBindResult(InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                session.mSession, accessibilityInputMethodSessions,
                (session.mChannel != null ? session.mChannel.dup() : null),
                curId, bindingController.getSequenceNumber(), suppressesSpellChecker);
    }

    @GuardedBy("ImfLock.class")
    private void attachNewAccessibilityLocked(@StartInputReason int startInputReason,
            boolean initial) {
        if (!mBoundToAccessibility) {
            AccessibilityManagerInternal.get().bindInput();
            mBoundToAccessibility = true;
        }

        // TODO(b/187453053): grantImplicitAccess to accessibility services access? if so, need to
        //  record accessibility services uid.

        // We don't start input when session for a11y is created. We start input when
        // input method start input, a11y manager service is always on.
        if (startInputReason != StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY) {
            setEnabledSessionForAccessibilityLocked(mCurClient.mAccessibilitySessions);
            AccessibilityManagerInternal.get().startInput(mCurRemoteAccessibilityInputConnection,
                    mCurEditorInfo, !initial /* restarting */);
        }
    }

    private SparseArray<IAccessibilityInputMethodSession> createAccessibilityInputMethodSessions(
            SparseArray<AccessibilitySessionState> accessibilitySessions) {
        final SparseArray<IAccessibilityInputMethodSession> accessibilityInputMethodSessions =
                new SparseArray<>();
        if (accessibilitySessions != null) {
            for (int i = 0; i < accessibilitySessions.size(); i++) {
                accessibilityInputMethodSessions.append(accessibilitySessions.keyAt(i),
                        accessibilitySessions.valueAt(i).mSession);
            }
        }
        return accessibilityInputMethodSessions;
    }

    /**
     * Called by {@link #startInputOrWindowGainedFocusInternalLocked} to bind/unbind/attach the
     * selected InputMethod to the given focused IME client.
     *
     * Note that this should be called after validating if the IME client has IME focus.
     *
     * @see WindowManagerInternal#hasInputMethodClientFocus(IBinder, int, int, int)
     */
    @GuardedBy("ImfLock.class")
    @NonNull
    private InputBindResult startInputUncheckedLocked(@NonNull ClientState cs,
            IRemoteInputConnection inputConnection,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            @NonNull EditorInfo editorInfo, @StartInputFlags int startInputFlags,
            @StartInputReason int startInputReason,
            int unverifiedTargetSdkVersion,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher,
            @NonNull UserDataRepository.UserData userData) {

        // Compute the final shown display ID with validated cs.selfReportedDisplayId for this
        // session & other conditions.
        ImeTargetWindowState winState = mVisibilityStateComputer.getWindowStateOrNull(
                mImeBindingState.mFocusedWindow);
        if (winState == null) {
            return InputBindResult.NOT_IME_TARGET_WINDOW;
        }
        final int csDisplayId = cs.mSelfReportedDisplayId;
        mDisplayIdToShowIme = mVisibilityStateComputer.computeImeDisplayId(winState, csDisplayId);

        // Potentially override the selected input method if the new display belongs to a virtual
        // device with a custom IME.
        String selectedMethodId = getSelectedMethodIdLocked();
        final String deviceMethodId = computeCurrentDeviceMethodIdLocked(selectedMethodId);
        if (deviceMethodId == null) {
            mVisibilityStateComputer.getImePolicy().setImeHiddenByDisplayPolicy(true);
        } else if (!Objects.equals(deviceMethodId, selectedMethodId)) {
            setInputMethodLocked(deviceMethodId, NOT_A_SUBTYPE_ID, mDeviceIdToShowIme);
            selectedMethodId = deviceMethodId;
        }

        if (mVisibilityStateComputer.getImePolicy().isImeHiddenByDisplayPolicy()) {
            hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE);
            return InputBindResult.NO_IME;
        }

        // If no method is currently selected, do nothing.
        if (selectedMethodId == null) {
            return InputBindResult.NO_IME;
        }

        if (mCurClient != cs) {
            prepareClientSwitchLocked(cs);
        }

        final boolean connectionWasActive = mCurInputConnection != null;

        // Bump up the sequence for this client and attach it.
        final var bindingController = userData.mBindingController;
        bindingController.advanceSequenceNumber();

        mCurClient = cs;
        mCurInputConnection = inputConnection;
        mCurRemoteAccessibilityInputConnection = remoteAccessibilityInputConnection;
        mCurImeDispatcher = imeDispatcher;
        // Override the locale hints if the app is running on a virtual device.
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal != null && editorInfo.hintLocales == null) {
            LocaleList hintsFromVirtualDevice = mVdmInternal.getPreferredLocaleListForUid(cs.mUid);
            if (hintsFromVirtualDevice != null) {
                editorInfo.hintLocales = hintsFromVirtualDevice;
            }
        }
        mCurEditorInfo = editorInfo;

        // Notify input manager if the connection state changes.
        final boolean connectionIsActive = mCurInputConnection != null;
        if (connectionIsActive != connectionWasActive) {
            mInputManagerInternal.notifyInputMethodConnectionActive(connectionIsActive);
        }

        // If configured, we want to avoid starting up the IME if it is not supposed to be showing
        if (shouldPreventImeStartupLocked(selectedMethodId, startInputFlags,
                unverifiedTargetSdkVersion)) {
            if (DEBUG) {
                Slog.d(TAG, "Avoiding IME startup and unbinding current input method.");
            }
            invalidateAutofillSessionLocked();
            bindingController.unbindCurrentMethod();
            return InputBindResult.NO_EDITOR;
        }

        // Check if the input method is changing.
        // We expect the caller has already verified that the client is allowed to access this
        // display ID.
        final String curId = bindingController.getCurId();
        if (curId != null && curId.equals(bindingController.getSelectedMethodId())
                && mDisplayIdToShowIme == getCurTokenDisplayIdLocked()) {
            if (cs.mCurSession != null) {
                // Fast case: if we are already connected to the input method,
                // then just return it.
                // This doesn't mean a11y sessions are there. When a11y service is
                // enabled while this client is switched out, this client doesn't have the session.
                // A11yManagerService will only request missing sessions (will not request existing
                // sessions again). Note when an a11y service is disabled, it will clear its
                // session from all clients, so we don't need to worry about disabled a11y services.
                cs.mSessionRequestedForAccessibility = false;
                requestClientSessionForAccessibilityLocked(cs);
                // we can always attach to accessibility because AccessibilityManagerService is
                // always on.
                attachNewAccessibilityLocked(startInputReason,
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0);
                return attachNewInputLocked(startInputReason,
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0);
            }

            InputBindResult bindResult = tryReuseConnectionLocked(userData, cs);
            if (bindResult != null) {
                return bindResult;
            }
        }

        bindingController.unbindCurrentMethod();
        return bindingController.bindCurrentMethod();
    }

    /**
     * Update the current deviceId and return the relevant imeId for this device.
     *
     * <p>1. If the device changes to virtual and its custom IME is not available, then disable
     * IME.</p>
     * <p>2. If the device changes to virtual with valid custom IME, then return the custom IME. If
     * the old device was default, then store the current imeId so it can be restored.</p>
     * <p>3. If the device changes to default, restore the default device IME.</p>
     * <p>4. Otherwise keep the current imeId.</p>
     */
    @GuardedBy("ImfLock.class")
    private String computeCurrentDeviceMethodIdLocked(String currentMethodId) {
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal == null || !android.companion.virtual.flags.Flags.vdmCustomIme()) {
            return currentMethodId;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        final int oldDeviceId = mDeviceIdToShowIme;
        mDeviceIdToShowIme = mVdmInternal.getDeviceIdForDisplayId(mDisplayIdToShowIme);
        if (mDeviceIdToShowIme == DEVICE_ID_DEFAULT) {
            if (oldDeviceId == DEVICE_ID_DEFAULT) {
                return currentMethodId;
            }
            final String defaultDeviceMethodId = settings.getSelectedDefaultDeviceInputMethod();
            if (DEBUG) {
                Slog.v(TAG, "Restoring default device input method: " + defaultDeviceMethodId);
            }
            settings.putSelectedDefaultDeviceInputMethod(null);
            return defaultDeviceMethodId;
        }

        final String deviceMethodId =
                mVirtualDeviceMethodMap.get(mDeviceIdToShowIme, currentMethodId);
        if (Objects.equals(deviceMethodId, currentMethodId)) {
            return currentMethodId;
        } else if (!settings.getMethodMap().containsKey(deviceMethodId)) {
            if (DEBUG) {
                Slog.v(TAG, "Disabling IME on virtual device with id " + mDeviceIdToShowIme
                        + " because its custom input method is not available: " + deviceMethodId);
            }
            return null;
        }

        if (oldDeviceId == DEVICE_ID_DEFAULT) {
            if (DEBUG) {
                Slog.v(TAG, "Storing default device input method " + currentMethodId);
            }
            settings.putSelectedDefaultDeviceInputMethod(currentMethodId);
        }
        if (DEBUG) {
            Slog.v(TAG, "Switching current input method from " + currentMethodId
                    + " to device-specific one " + deviceMethodId + " because the current display "
                    + mDisplayIdToShowIme + " belongs to device with id " + mDeviceIdToShowIme);
        }
        return deviceMethodId;
    }

    @GuardedBy("ImfLock.class")
    void invalidateAutofillSessionLocked() {
        mAutofillController.invalidateAutofillSession();
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldPreventImeStartupLocked(
            @NonNull String selectedMethodId,
            @StartInputFlags int startInputFlags,
            int unverifiedTargetSdkVersion) {
        // Fast-path for the majority of cases
        if (!mPreventImeStartupUnlessTextEditor) {
            return false;
        }
        if (isShowRequestedForCurrentWindow()) {
            return false;
        }
        if (isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags)) {
            return false;
        }
        final InputMethodInfo imi = InputMethodSettingsRepository.get(mCurrentUserId)
                .getMethodMap().get(selectedMethodId);
        if (imi == null) {
            return false;
        }
        if (ArrayUtils.contains(mNonPreemptibleInputMethods, imi.getPackageName())) {
            return false;
        }
        return true;
    }

    @GuardedBy("ImfLock.class")
    private void prepareClientSwitchLocked(ClientState cs) {
        // If the client is changing, we need to switch over to the new
        // one.
        unbindCurrentClientLocked(UnbindReason.SWITCH_CLIENT);
        // If the screen is on, inform the new client it is active
        if (mIsInteractive) {
            cs.mClient.setActive(true /* active */, false /* fullscreen */);
        }
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    private InputBindResult tryReuseConnectionLocked(@NonNull UserDataRepository.UserData userData,
            @NonNull ClientState cs) {
        final var bindingController = userData.mBindingController;
        if (bindingController.hasMainConnection()) {
            if (getCurMethodLocked() != null) {
                // Return to client, and we will get back with it when
                // we have had a session made for it.
                requestClientSessionLocked(cs);
                requestClientSessionForAccessibilityLocked(cs);
                return new InputBindResult(
                        InputBindResult.ResultCode.SUCCESS_WAITING_IME_SESSION,
                        null, null, null,
                        bindingController.getCurId(),
                        bindingController.getSequenceNumber(), false);
            } else {
                final long lastBindTime = bindingController.getLastBindTime();
                long bindingDuration = SystemClock.uptimeMillis() - lastBindTime;
                if (bindingDuration < TIME_TO_RECONNECT) {
                    // In this case we have connected to the service, but
                    // don't yet have its interface.  If it hasn't been too
                    // long since we did the connection, we'll return to
                    // the client and wait to get the service interface so
                    // we can report back.  If it has been too long, we want
                    // to fall through so we can try a disconnect/reconnect
                    // to see if we can get back in touch with the service.
                    return new InputBindResult(
                            InputBindResult.ResultCode.SUCCESS_WAITING_IME_BINDING,
                            null, null, null,
                            bindingController.getCurId(),
                            bindingController.getSequenceNumber(), false);
                } else {
                    EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME,
                            getSelectedMethodIdLocked(), bindingDuration, 0);
                }
            }
        }
        return null;
    }

    @FunctionalInterface
    interface ImeDisplayValidator {
        @DisplayImePolicy
        int getDisplayImePolicy(int displayId);
    }

    /**
     * Find the display where the IME should be shown.
     *
     * @param displayId the ID of the display where the IME client target is
     * @param checker   instance of {@link ImeDisplayValidator} which is used for
     *                  checking display config to adjust the final target display
     * @return the ID of the display where the IME should be shown or
     * {@link android.view.Display#INVALID_DISPLAY} if the display has an ImePolicy of
     * {@link WindowManager#DISPLAY_IME_POLICY_HIDE}
     */
    static int computeImeDisplayIdForTarget(int displayId, @NonNull ImeDisplayValidator checker) {
        if (displayId == DEFAULT_DISPLAY || displayId == INVALID_DISPLAY) {
            return FALLBACK_DISPLAY_ID;
        }

        // Show IME window on fallback display when the display doesn't support system decorations
        // or the display is virtual and isn't owned by system for security concern.
        final int result = checker.getDisplayImePolicy(displayId);
        if (result == DISPLAY_IME_POLICY_LOCAL) {
            return displayId;
        } else if (result == DISPLAY_IME_POLICY_HIDE) {
            return INVALID_DISPLAY;
        } else {
            return FALLBACK_DISPLAY_ID;
        }
    }

    @GuardedBy("ImfLock.class")
    void initializeImeLocked(@NonNull IInputMethodInvoker inputMethod, @NonNull IBinder token) {
        if (DEBUG) {
            Slog.v(TAG, "Sending attach of token: " + token + " for display: "
                    + getCurTokenDisplayIdLocked());
        }
        inputMethod.initializeInternal(token, new InputMethodPrivilegedOperationsImpl(this, token),
                getInputMethodNavButtonFlagsLocked());
    }

    @AnyThread
    void scheduleResetStylusHandwriting() {
        mHandler.obtainMessage(MSG_RESET_HANDWRITING).sendToTarget();
    }

    @AnyThread
    void schedulePrepareStylusHandwritingDelegation(@UserIdInt int userId,
            @NonNull String delegatePackageName, @NonNull String delegatorPackageName) {
        mHandler.obtainMessage(
                MSG_PREPARE_HANDWRITING_DELEGATION, userId, 0 /* unused */,
                new Pair<>(delegatePackageName, delegatorPackageName)).sendToTarget();
    }

    @AnyThread
    void scheduleRemoveStylusHandwritingWindow() {
        mHandler.obtainMessage(MSG_REMOVE_HANDWRITING_WINDOW).sendToTarget();
    }

    @AnyThread
    void scheduleNotifyImeUidToAudioService(int uid) {
        mHandler.removeMessages(MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE);
        mHandler.obtainMessage(MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE, uid, 0 /* unused */)
                .sendToTarget();
    }

    @BinderThread
    void onSessionCreated(IInputMethodInvoker method, IInputMethodSession session,
            InputChannel channel) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onSessionCreated");
        try {
            synchronized (ImfLock.class) {
                if (mUserSwitchHandlerTask != null) {
                    // We have a pending user-switching task so it's better to just ignore this
                    // session.
                    channel.dispose();
                    return;
                }
                IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null && method != null
                        && curMethod.asBinder() == method.asBinder()) {
                    if (mCurClient != null) {
                        clearClientSessionLocked(mCurClient);
                        mCurClient.mCurSession = new SessionState(
                                mCurClient, method, session, channel);
                        InputBindResult res = attachNewInputLocked(
                                StartInputReason.SESSION_CREATED_BY_IME, true);
                        attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_IME, true);
                        if (res.method != null) {
                            mCurClient.mClient.onBindMethod(res);
                        }
                        return;
                    }
                }
            }

            // Session abandoned.  Close its associated input channel.
            channel.dispose();
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @GuardedBy("ImfLock.class")
    void resetSystemUiLocked() {
        // Set IME window status as invisible when unbinding current method.
        mImeWindowVis = 0;
        mBackDisposition = InputMethodService.BACK_DISPOSITION_DEFAULT;
        updateSystemUiLocked(mImeWindowVis, mBackDisposition);
        mAutofillController.onResetSystemUi();
    }

    @GuardedBy("ImfLock.class")
    void resetCurrentMethodAndClientLocked(@UnbindReason int unbindClientReason) {
        final var bindingController =
                mUserDataRepository.getOrCreate(mCurrentUserId).mBindingController;
        bindingController.setSelectedMethodId(null);

        // Callback before clean-up binding states.
        // TODO(b/338461930): Check if this is still necessary or not.
        onUnbindCurrentMethodByReset();
        bindingController.unbindCurrentMethod();
        unbindCurrentClientLocked(unbindClientReason);
    }

    @GuardedBy("ImfLock.class")
    void reRequestCurrentClientSessionLocked() {
        if (mCurClient != null) {
            clearClientSessionLocked(mCurClient);
            clearClientSessionForAccessibilityLocked(mCurClient);
            requestClientSessionLocked(mCurClient);
            requestClientSessionForAccessibilityLocked(mCurClient);
        }
    }

    @GuardedBy("ImfLock.class")
    void requestClientSessionLocked(ClientState cs) {
        if (!cs.mSessionRequested) {
            if (DEBUG) Slog.v(TAG, "Creating new session for client " + cs);
            final InputChannel serverChannel;
            final InputChannel clientChannel;
            {
                final InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
                serverChannel = channels[0];
                clientChannel = channels[1];
            }

            cs.mSessionRequested = true;

            final IInputMethodInvoker curMethod = getCurMethodLocked();
            final IInputMethodSessionCallback.Stub callback =
                    new IInputMethodSessionCallback.Stub() {
                        @Override
                        public void sessionCreated(IInputMethodSession session) {
                            final long ident = Binder.clearCallingIdentity();
                            try {
                                onSessionCreated(curMethod, session, serverChannel);
                            } finally {
                                Binder.restoreCallingIdentity(ident);
                            }
                        }
                    };

            try {
                curMethod.createSession(clientChannel, callback);
            } finally {
                // Dispose the channel because the remote proxy will get its own copy when
                // unparceled.
                if (clientChannel != null) {
                    clientChannel.dispose();
                }
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void requestClientSessionForAccessibilityLocked(ClientState cs) {
        if (!cs.mSessionRequestedForAccessibility) {
            if (DEBUG) Slog.v(TAG, "Creating new accessibility sessions for client " + cs);
            cs.mSessionRequestedForAccessibility = true;
            ArraySet<Integer> ignoreSet = new ArraySet<>();
            for (int i = 0; i < cs.mAccessibilitySessions.size(); i++) {
                ignoreSet.add(cs.mAccessibilitySessions.keyAt(i));
            }
            AccessibilityManagerInternal.get().createImeSession(ignoreSet);
        }
    }

    @GuardedBy("ImfLock.class")
    void clearClientSessionLocked(ClientState cs) {
        finishSessionLocked(cs.mCurSession);
        cs.mCurSession = null;
        cs.mSessionRequested = false;
    }

    @GuardedBy("ImfLock.class")
    void clearClientSessionForAccessibilityLocked(ClientState cs) {
        for (int i = 0; i < cs.mAccessibilitySessions.size(); i++) {
            finishSessionForAccessibilityLocked(cs.mAccessibilitySessions.valueAt(i));
        }
        cs.mAccessibilitySessions.clear();
        cs.mSessionRequestedForAccessibility = false;
    }

    @GuardedBy("ImfLock.class")
    void clearClientSessionForAccessibilityLocked(ClientState cs, int id) {
        AccessibilitySessionState session = cs.mAccessibilitySessions.get(id);
        if (session != null) {
            finishSessionForAccessibilityLocked(session);
            cs.mAccessibilitySessions.remove(id);
        }
    }

    @GuardedBy("ImfLock.class")
    private void finishSessionLocked(SessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.mSession != null) {
                try {
                    sessionState.mSession.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                    updateSystemUiLocked(0 /* vis */, mBackDisposition);
                }
                sessionState.mSession = null;
            }
            if (sessionState.mChannel != null) {
                sessionState.mChannel.dispose();
                sessionState.mChannel = null;
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void finishSessionForAccessibilityLocked(AccessibilitySessionState sessionState) {
        if (sessionState != null) {
            if (sessionState.mSession != null) {
                try {
                    sessionState.mSession.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                }
                sessionState.mSession = null;
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void clearClientSessionsLocked() {
        if (getCurMethodLocked() != null) {
            // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
            @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession = c -> {
                clearClientSessionLocked(c);
                clearClientSessionForAccessibilityLocked(c);
            };
            mClientController.forAllClients(clearClientSession);

            finishSessionLocked(mEnabledSession);
            for (int i = 0; i < mEnabledAccessibilitySessions.size(); i++) {
                finishSessionForAccessibilityLocked(mEnabledAccessibilitySessions.valueAt(i));
            }
            mEnabledSession = null;
            mEnabledAccessibilitySessions.clear();
            scheduleNotifyImeUidToAudioService(Process.INVALID_UID);
        }
        hideStatusBarIconLocked();
        mInFullscreenMode = false;
        mWindowManagerInternal.setDismissImeOnBackKeyPressed(false);
    }

    @BinderThread
    private void updateStatusIcon(@NonNull IBinder token, String packageName,
            @DrawableRes int iconId) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (iconId == 0) {
                    if (DEBUG) Slog.d(TAG, "hide the small icon for the input method");
                    hideStatusBarIconLocked();
                } else if (packageName != null) {
                    if (DEBUG) Slog.d(TAG, "show a small icon for the input method");
                    final PackageManager userAwarePackageManager =
                            getPackageManagerForUser(mContext, mCurrentUserId);
                    ApplicationInfo applicationInfo = null;
                    try {
                        applicationInfo = userAwarePackageManager.getApplicationInfo(packageName,
                                PackageManager.ApplicationInfoFlags.of(0));
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                    final CharSequence contentDescription = applicationInfo != null
                            ? userAwarePackageManager.getApplicationLabel(applicationInfo)
                            : null;
                    if (mStatusBarManagerInternal != null) {
                        mStatusBarManagerInternal.setIcon(mSlotIme, packageName, iconId, 0,
                                contentDescription != null
                                        ? contentDescription.toString() : null);
                        mStatusBarManagerInternal.setIconVisibility(mSlotIme, true);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void hideStatusBarIconLocked() {
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.setIconVisibility(mSlotIme, false);
        }
    }

    @GuardedBy("ImfLock.class")
    @InputMethodNavButtonFlags
    private int getInputMethodNavButtonFlagsLocked() {
        if (mImeDrawsImeNavBarResLazyInitFuture != null) {
            // TODO(b/225366708): Avoid Future.get(), which is internally used here.
            ConcurrentUtils.waitForFutureNoInterrupt(mImeDrawsImeNavBarResLazyInitFuture,
                    "Waiting for the lazy init of mImeDrawsImeNavBarRes");
        }
        // Whether the current display has a navigation bar. When this is false (e.g. emulator),
        // the IME should not draw the IME navigation bar.
        final int tokenDisplayId = getCurTokenDisplayIdLocked();
        final boolean hasNavigationBar = mWindowManagerInternal
                .hasNavigationBar(tokenDisplayId != INVALID_DISPLAY
                        ? tokenDisplayId : DEFAULT_DISPLAY);
        final boolean canImeDrawsImeNavBar =
                mImeDrawsImeNavBarRes != null && mImeDrawsImeNavBarRes.get() && hasNavigationBar;
        final boolean shouldShowImeSwitcherWhenImeIsShown = shouldShowImeSwitcherLocked(
                InputMethodService.IME_ACTIVE | InputMethodService.IME_VISIBLE);
        return (canImeDrawsImeNavBar ? InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR : 0)
                | (shouldShowImeSwitcherWhenImeIsShown
                ? InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN : 0);
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldShowImeSwitcherLocked(int visibility) {
        if (!mShowOngoingImeSwitcherForPhones) return false;
        // When the IME switcher dialog is shown, the IME switcher button should be hidden.
        if (mMenuController.getSwitchingDialogLocked() != null) return false;
        // When we are switching IMEs, the IME switcher button should be hidden.
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        if (!Objects.equals(userData.mBindingController.getCurId(), getSelectedMethodIdLocked())) {
            return false;
        }
        if (mWindowManagerInternal.isKeyguardShowingAndNotOccluded()
                && mWindowManagerInternal.isKeyguardSecure(mCurrentUserId)) {
            return false;
        }
        if ((visibility & InputMethodService.IME_ACTIVE) == 0
                || (visibility & InputMethodService.IME_INVISIBLE) != 0) {
            return false;
        }
        if (mWindowManagerInternal.isHardKeyboardAvailable()) {
            // When physical keyboard is attached, we show the ime switcher (or notification if
            // NavBar is not available) because SHOW_IME_WITH_HARD_KEYBOARD settings currently
            // exists in the IME switcher dialog.  Might be OK to remove this condition once
            // SHOW_IME_WITH_HARD_KEYBOARD settings finds a good place to live.
            return true;
        } else if ((visibility & InputMethodService.IME_VISIBLE) == 0) {
            return false;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        List<InputMethodInfo> imes = settings.getEnabledInputMethodListWithFilter(
                InputMethodInfo::shouldShowInInputMethodPicker);
        final int numImes = imes.size();
        if (numImes > 2) return true;
        if (numImes < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for (int i = 0; i < numImes; ++i) {
            final InputMethodInfo imi = imes.get(i);
            final List<InputMethodSubtype> subtypes =
                    settings.getEnabledInputMethodSubtypeList(imi, true);
            final int subtypeCount = subtypes.size();
            if (subtypeCount == 0) {
                ++nonAuxCount;
            } else {
                for (int j = 0; j < subtypeCount; ++j) {
                    final InputMethodSubtype subtype = subtypes.get(j);
                    if (!subtype.isAuxiliary()) {
                        ++nonAuxCount;
                        nonAuxSubtype = subtype;
                    } else {
                        ++auxCount;
                        auxSubtype = subtype;
                    }
                }
            }
        }
        if (nonAuxCount > 1 || auxCount > 1) {
            return true;
        } else if (nonAuxCount == 1 && auxCount == 1) {
            if (nonAuxSubtype != null && auxSubtype != null
                    && (nonAuxSubtype.getLocale().equals(auxSubtype.getLocale())
                    || auxSubtype.overridesImplicitlyEnabledSubtype()
                    || nonAuxSubtype.overridesImplicitlyEnabledSubtype())
                    && nonAuxSubtype.containsExtraValueKey(TAG_TRY_SUPPRESSING_IME_SWITCHER)) {
                return false;
            }
            return true;
        }
        return false;
    }

    @BinderThread
    @SuppressWarnings("deprecation")
    private void setImeWindowStatus(@NonNull IBinder token, int vis, int backDisposition) {
        final int topFocusedDisplayId = mWindowManagerInternal.getTopFocusedDisplayId();

        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            // Skip update IME status when current token display is not same as focused display.
            // Note that we still need to update IME status when focusing external display
            // that does not support system decoration and fallback to show IME on default
            // display since it is intentional behavior.
            final int tokenDisplayId = getCurTokenDisplayIdLocked();
            if (tokenDisplayId != topFocusedDisplayId && tokenDisplayId != FALLBACK_DISPLAY_ID) {
                return;
            }
            mImeWindowVis = vis;
            mBackDisposition = backDisposition;
            updateSystemUiLocked(vis, backDisposition);
        }

        final boolean dismissImeOnBackKeyPressed;
        switch (backDisposition) {
            case InputMethodService.BACK_DISPOSITION_WILL_DISMISS:
                dismissImeOnBackKeyPressed = true;
                break;
            case InputMethodService.BACK_DISPOSITION_WILL_NOT_DISMISS:
                dismissImeOnBackKeyPressed = false;
                break;
            default:
            case InputMethodService.BACK_DISPOSITION_DEFAULT:
                dismissImeOnBackKeyPressed = ((vis & InputMethodService.IME_VISIBLE) != 0);
                break;
        }
        mWindowManagerInternal.setDismissImeOnBackKeyPressed(dismissImeOnBackKeyPressed);
    }

    @BinderThread
    private void reportStartInput(@NonNull IBinder token, IBinder startInputToken) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final IBinder targetWindow = mImeTargetWindowMap.get(startInputToken);
            if (targetWindow != null) {
                mWindowManagerInternal.updateInputMethodTargetWindow(token, targetWindow);
            }
            mLastImeTargetWindow = targetWindow;
        }
    }

    private void updateImeWindowStatus(boolean disableImeIcon) {
        synchronized (ImfLock.class) {
            if (disableImeIcon) {
                updateSystemUiLocked(0, mBackDisposition);
            } else {
                updateSystemUiLocked();
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void updateSystemUiLocked() {
        updateSystemUiLocked(mImeWindowVis, mBackDisposition);
    }

    // Caution! This method is called in this class. Handle multi-user carefully
    @GuardedBy("ImfLock.class")
    private void updateSystemUiLocked(int vis, int backDisposition) {
        if (getCurTokenLocked() == null) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "IME window vis: " + vis
                    + " active: " + (vis & InputMethodService.IME_ACTIVE)
                    + " inv: " + (vis & InputMethodService.IME_INVISIBLE)
                    + " displayId: " + getCurTokenDisplayIdLocked());
        }
        final IBinder focusedWindowToken = mImeBindingState != null
                ? mImeBindingState.mFocusedWindow : null;
        final Boolean windowPerceptible = focusedWindowToken != null
                ? mFocusedWindowPerceptible.get(focusedWindowToken) : null;

        // TODO: Move this clearing calling identity block to setImeWindowStatus after making sure
        // all updateSystemUi happens on system privilege.
        final long ident = Binder.clearCallingIdentity();
        try {
            if (windowPerceptible != null && !windowPerceptible) {
                if ((vis & InputMethodService.IME_VISIBLE) != 0) {
                    vis &= ~InputMethodService.IME_VISIBLE;
                    vis |= InputMethodService.IME_VISIBLE_IMPERCEPTIBLE;
                }
            } else {
                vis &= ~InputMethodService.IME_VISIBLE_IMPERCEPTIBLE;
            }
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            final var curId = userData.mBindingController.getCurId();
            if (mMenuController.getSwitchingDialogLocked() != null
                    || !Objects.equals(curId, getSelectedMethodIdLocked())) {
                // When the IME switcher dialog is shown, or we are switching IMEs,
                // the back button should be in the default state (as if the IME is not shown).
                backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING;
            }
            final boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis);
            if (mStatusBarManagerInternal != null) {
                mStatusBarManagerInternal.setImeWindowStatus(getCurTokenDisplayIdLocked(),
                        getCurTokenLocked(), vis, backDisposition, needsToShowImeSwitcher);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("ImfLock.class")
    void updateFromSettingsLocked(boolean enabledMayChange) {
        updateInputMethodsFromSettingsLocked(enabledMayChange);
        mMenuController.updateKeyboardFromSettingsLocked();
    }

    /**
     * This is an experimental implementation used when and only when
     * {@link #mExperimentalConcurrentMultiUserModeEnabled}.
     *
     * <p>Never assume what this method is doing is officially supported. For the canonical and
     * desired behaviors always refer to single-user code paths such as
     * {@link #updateInputMethodsFromSettingsLocked(boolean)}.</p>
     *
     * <p>Here are examples of missing features.</p>
     * <ul>
     *     <li>Subtypes are not supported at all!</li>
     *     <li>Profiles are not supported.</li>
     *     <li>
     *         {@link PackageManager#COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED} is not updated.
     *     </li>
     *     <li>{@link #mDeviceIdToShowIme} is ignored.</li>
     *     <li>{@link #mSwitchingController} is ignored.</li>
     *     <li>{@link #mHardwareKeyboardShortcutController} is ignored.</li>
     *     <li>{@link #mPreventImeStartupUnlessTextEditor} is ignored.</li>
     *     <li>and so on.</li>
     * </ul>
     */
    @GuardedBy("ImfLock.class")
    void experimentalInitializeVisibleBackgroundUserLocked(@UserIdInt int userId) {
        if (!mUserManagerInternal.isUserVisible(userId)) {
            return;
        }
        final var settings = InputMethodSettingsRepository.get(userId);
        String id = settings.getSelectedInputMethod();
        if (TextUtils.isEmpty(id)) {
            final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(
                    settings.getEnabledInputMethodList());
            if (imi == null) {
                return;
            }
            id = imi.getId();
            settings.putSelectedInputMethod(id);
        }
        final var userData = mUserDataRepository.getOrCreate(userId);
        final var bindingController = userData.mBindingController;
        bindingController.setSelectedMethodId(id);
    }

    @GuardedBy("ImfLock.class")
    void updateInputMethodsFromSettingsLocked(boolean enabledMayChange) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        if (enabledMayChange) {
            final PackageManager userAwarePackageManager = getPackageManagerForUser(mContext,
                    settings.getUserId());

            List<InputMethodInfo> enabled = settings.getEnabledInputMethodList();
            for (int i = 0; i < enabled.size(); i++) {
                // We allow the user to select "disabled until used" apps, so if they
                // are enabling one of those here we now need to make it enabled.
                InputMethodInfo imm = enabled.get(i);
                ApplicationInfo ai = null;
                try {
                    ai = userAwarePackageManager.getApplicationInfo(imm.getPackageName(),
                            PackageManager.ApplicationInfoFlags.of(
                                    PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS));
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                if (ai != null && ai.enabledSetting
                        == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                    if (DEBUG) {
                        Slog.d(TAG, "Update state(" + imm.getId()
                                + "): DISABLED_UNTIL_USED -> DEFAULT");
                    }
                    userAwarePackageManager.setApplicationEnabledSetting(imm.getPackageName(),
                            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                            PackageManager.DONT_KILL_APP);
                }
            }
        }

        if (mDeviceIdToShowIme == DEVICE_ID_DEFAULT) {
            String ime = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_INPUT_METHOD, null, settings.getUserId());
            String defaultDeviceIme = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, settings.getUserId());
            if (defaultDeviceIme != null && !Objects.equals(ime, defaultDeviceIme)) {
                if (DEBUG) {
                    Slog.v(TAG, "Current input method " + ime + " differs from the stored default"
                            + " device input method for user " + settings.getUserId()
                            + " - restoring " + defaultDeviceIme);
                }
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_INPUT_METHOD, defaultDeviceIme,
                        settings.getUserId());
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, settings.getUserId());
            }
        }

        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String id = settings.getSelectedInputMethod();
        // There is no input method selected, try to choose new applicable input method.
        if (TextUtils.isEmpty(id) && chooseNewDefaultIMELocked()) {
            id = settings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(id)) {
            try {
                setInputMethodLocked(id, settings.getSelectedInputMethodSubtypeId(id));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e);
                resetCurrentMethodAndClientLocked(UnbindReason.SWITCH_IME_FAILED);
            }
        } else {
            // There is no longer an input method set, so stop any current one.
            resetCurrentMethodAndClientLocked(UnbindReason.NO_IME);
        }

        // TODO: Instantiate mSwitchingController for each user.
        if (settings.getUserId() == mSwitchingController.getUserId()) {
            mSwitchingController.resetCircularListLocked(settings.getMethodMap());
        } else {
            mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(
                    mContext, settings.getMethodMap(), settings.getUserId());
        }
        // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
        if (settings.getUserId() == mHardwareKeyboardShortcutController.getUserId()) {
            mHardwareKeyboardShortcutController.reset(settings.getMethodMap());
        } else {
            mHardwareKeyboardShortcutController = new HardwareKeyboardShortcutController(
                    settings.getMethodMap(), settings.getUserId());
        }
        sendOnNavButtonFlagsChangedLocked();
    }

    @GuardedBy("ImfLock.class")
    private void notifyInputMethodSubtypeChangedLocked(@UserIdInt int userId,
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
        final InputMethodSubtype normalizedSubtype =
                subtype != null && subtype.isSuitableForPhysicalKeyboardLayoutMapping()
                        ? subtype : null;
        final InputMethodSubtypeHandle newSubtypeHandle = normalizedSubtype != null
                ? InputMethodSubtypeHandle.of(imi, normalizedSubtype) : null;
        mInputManagerInternal.onInputMethodSubtypeChangedForKeyboardLayoutMapping(
                userId, newSubtypeHandle, normalizedSubtype);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeId) {
        setInputMethodLocked(id, subtypeId, DEVICE_ID_DEFAULT);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeId, int deviceId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        InputMethodInfo info = settings.getMethodMap().get(id);
        if (info == null) {
            throw getExceptionForUnknownImeId(id);
        }

        // See if we need to notify a subtype change within the same IME.
        if (id.equals(getSelectedMethodIdLocked())) {
            final int userId = settings.getUserId();
            final int subtypeCount = info.getSubtypeCount();
            if (subtypeCount <= 0) {
                notifyInputMethodSubtypeChangedLocked(userId, info, null);
                return;
            }
            final InputMethodSubtype oldSubtype = mCurrentSubtype;
            final InputMethodSubtype newSubtype;
            if (subtypeId >= 0 && subtypeId < subtypeCount) {
                newSubtype = info.getSubtypeAt(subtypeId);
            } else {
                // If subtype is null, try to find the most applicable one from
                // getCurrentInputMethodSubtype.
                subtypeId = NOT_A_SUBTYPE_ID;
                newSubtype = getCurrentInputMethodSubtypeLocked();
                if (newSubtype != null) {
                    for (int i = 0; i < subtypeCount; ++i) {
                        if (Objects.equals(newSubtype, info.getSubtypeAt(i))) {
                            subtypeId = i;
                            break;
                        }
                    }
                }
            }
            if (!Objects.equals(newSubtype, oldSubtype)) {
                setSelectedInputMethodAndSubtypeLocked(info, subtypeId, true);
                IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null) {
                    updateSystemUiLocked(mImeWindowVis, mBackDisposition);
                    curMethod.changeInputMethodSubtype(newSubtype);
                }
            }
            return;
        }

        // Changing to a different IME.
        if (mDeviceIdToShowIme != DEVICE_ID_DEFAULT && deviceId == DEVICE_ID_DEFAULT) {
            // This change should only be applicable to the default device but the current input
            // method is a custom one specific to a virtual device. So only update the settings
            // entry used to restore the default device input method once we want to show the IME
            // back on the default device.
            settings.putSelectedDefaultDeviceInputMethod(id);
            return;
        }
        IInputMethodInvoker curMethod = getCurMethodLocked();
        if (curMethod != null) {
            curMethod.removeStylusHandwritingWindow();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            // Set a subtype to this input method.
            // subtypeId the name of a subtype which will be set.
            setSelectedInputMethodAndSubtypeLocked(info, subtypeId, false);
            // mCurMethodId should be updated after setSelectedInputMethodAndSubtypeLocked()
            // because mCurMethodId is stored as a history in
            // setSelectedInputMethodAndSubtypeLocked().
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            userData.mBindingController.setSelectedMethodId(id);

            if (mActivityManagerInternal.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_INPUT_METHOD_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("input_method_id", id);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
            unbindCurrentClientLocked(UnbindReason.SWITCH_IME);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showSoftInput");
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput", mDumper);
        synchronized (ImfLock.class) {
            if (!canInteractWithImeLocked(uid, client, "showSoftInput", statsToken)) {
                ImeTracker.forLogging().onFailed(
                        statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
                return showCurrentInputLocked(windowToken, statsToken, flags, lastClickToolType,
                        resultReceiver, reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @BinderThread
    @Override
    public void startStylusHandwriting(IInputMethodClient client) {
        startStylusHandwriting(client, false /* usesDelegation */);
    }

    @BinderThread
    @Override
    public void startConnectionlessStylusHandwriting(IInputMethodClient client, int userId,
            @Nullable CursorAnchorInfo cursorAnchorInfo, @Nullable String delegatePackageName,
            @Nullable String delegatorPackageName,
            @NonNull IConnectionlessHandwritingCallback callback) {
        synchronized (ImfLock.class) {
            final var userData = mUserDataRepository.getOrCreate(userId);
            if (!userData.mBindingController.supportsConnectionlessStylusHandwriting()) {
                Slog.w(TAG, "Connectionless stylus handwriting mode unsupported by IME.");
                try {
                    callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_UNSUPPORTED", e);
                    e.rethrowAsRuntimeException();
                }
                return;
            }
        }

        IConnectionlessHandwritingCallback immsCallback = callback;
        boolean isForDelegation = delegatePackageName != null && delegatorPackageName != null;
        if (isForDelegation) {
            synchronized (ImfLock.class) {
                if (!mClientController.verifyClientAndPackageMatch(client, delegatorPackageName)) {
                    Slog.w(TAG, "startConnectionlessStylusHandwriting() fail");
                    try {
                        callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_OTHER);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_OTHER", e);
                        e.rethrowAsRuntimeException();
                    }
                    throw new IllegalArgumentException("Delegator doesn't match UID");
                }
            }
            immsCallback = new IConnectionlessHandwritingCallback.Stub() {
                @Override
                public void onResult(CharSequence text) throws RemoteException {
                    synchronized (ImfLock.class) {
                        mHwController.prepareStylusHandwritingDelegation(
                                userId, delegatePackageName, delegatorPackageName,
                                /* connectionless= */ true);
                    }
                    callback.onResult(text);
                }

                @Override
                public void onError(int errorCode) throws RemoteException {
                    callback.onError(errorCode);
                }
            };
        }

        if (!startStylusHandwriting(
                client, false, immsCallback, cursorAnchorInfo, isForDelegation)) {
            try {
                callback.onError(CONNECTIONLESS_HANDWRITING_ERROR_OTHER);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to report CONNECTIONLESS_HANDWRITING_ERROR_OTHER", e);
                e.rethrowAsRuntimeException();
            }
        }
    }

    private void startStylusHandwriting(IInputMethodClient client, boolean acceptingDelegation) {
        startStylusHandwriting(client, acceptingDelegation, null, null, false);
    }

    private boolean startStylusHandwriting(IInputMethodClient client, boolean acceptingDelegation,
            IConnectionlessHandwritingCallback connectionlessCallback,
            CursorAnchorInfo cursorAnchorInfo, boolean isConnectionlessForDelegation) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.startStylusHandwriting");
        try {
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startStylusHandwriting", mDumper);
            int uid = Binder.getCallingUid();
            synchronized (ImfLock.class) {
                if (!acceptingDelegation) {
                    mHwController.clearPendingHandwritingDelegation();
                }
                if (!canInteractWithImeLocked(uid, client, "startStylusHandwriting",
                        null /* statsToken */)) {
                    return false;
                }
                if (!hasSupportedStylusLocked()) {
                    Slog.w(TAG, "No supported Stylus hardware found on device. Ignoring"
                            + " startStylusHandwriting()");
                    return false;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
                    if (!userData.mBindingController.supportsStylusHandwriting()) {
                        Slog.w(TAG,
                                "Stylus HW unsupported by IME. Ignoring startStylusHandwriting()");
                        return false;
                    }

                    final OptionalInt requestId = mHwController.getCurrentRequestId();
                    if (!requestId.isPresent()) {
                        Slog.e(TAG, "Stylus handwriting was not initialized.");
                        return false;
                    }
                    if (!mHwController.isStylusGestureOngoing()) {
                        Slog.e(TAG,
                                "There is no ongoing stylus gesture to start stylus handwriting.");
                        return false;
                    }
                    if (mHwController.hasOngoingStylusHandwritingSession()) {
                        // prevent duplicate calls to startStylusHandwriting().
                        Slog.e(TAG,
                                "Stylus handwriting session is already ongoing."
                                        + " Ignoring startStylusHandwriting().");
                        return false;
                    }
                    if (DEBUG) Slog.v(TAG, "Client requesting Stylus Handwriting to be started");
                    final IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod != null) {
                        curMethod.canStartStylusHandwriting(requestId.getAsInt(),
                                connectionlessCallback, cursorAnchorInfo,
                                isConnectionlessForDelegation);
                        return true;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
        return false;
    }

    @Override
    public void prepareStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName) {
        if (!isStylusHandwritingEnabled(mContext, userId)) {
            Slog.w(TAG, "Can not prepare stylus handwriting delegation. Stylus handwriting"
                    + " pref is disabled for user: " + userId);
            return;
        }
        synchronized (ImfLock.class) {
            if (!mClientController.verifyClientAndPackageMatch(client,
                    delegatorPackageName)) {
                Slog.w(TAG, "prepareStylusHandwritingDelegation() fail");
                throw new IllegalArgumentException("Delegator doesn't match Uid");
            }
        }
        schedulePrepareStylusHandwritingDelegation(
                userId, delegatePackageName, delegatorPackageName);
    }

    @Override
    public void acceptStylusHandwritingDelegationAsync(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags, IBooleanListener callback) {
        boolean result = acceptStylusHandwritingDelegation(
                client, userId, delegatePackageName, delegatorPackageName, flags);
        try {
            callback.onResult(result);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to report result=" + result, e);
            e.rethrowAsRuntimeException();
        }
    }

    @Override
    public boolean acceptStylusHandwritingDelegation(
            @NonNull IInputMethodClient client,
            @UserIdInt int userId,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        if (!isStylusHandwritingEnabled(mContext, userId)) {
            Slog.w(TAG, "Can not accept stylus handwriting delegation. Stylus handwriting"
                    + " pref is disabled for user: " + userId);
            return false;
        }
        if (!verifyDelegator(client, delegatePackageName, delegatorPackageName, flags)) {
            return false;
        }
        synchronized (ImfLock.class) {
            if (mHwController.isDelegationUsingConnectionlessFlow()) {
                final IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod == null) {
                    return false;
                }
                curMethod.commitHandwritingDelegationTextIfAvailable();
                mHwController.clearPendingHandwritingDelegation();
            } else {
                startStylusHandwriting(client, true /* acceptingDelegation */);
            }
        }
        return true;
    }

    private boolean verifyDelegator(
            @NonNull IInputMethodClient client,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        synchronized (ImfLock.class) {
            if (!mClientController.verifyClientAndPackageMatch(client, delegatePackageName)) {
                Slog.w(TAG, "Delegate package does not belong to the same user. Ignoring"
                        + " startStylusHandwriting");
                return false;
            }
            boolean homeDelegatorAllowed =
                    (flags & InputMethodManager.HANDWRITING_DELEGATE_FLAG_HOME_DELEGATOR_ALLOWED)
                            != 0;
            if (!delegatorPackageName.equals(mHwController.getDelegatorPackageName())
                    && !(homeDelegatorAllowed
                            && mHwController.isDelegatorFromDefaultHomePackage())) {
                Slog.w(TAG,
                        "Delegator package does not match. Ignoring startStylusHandwriting");
                return false;
            }
            if (!delegatePackageName.equals(mHwController.getDelegatePackageName())) {
                Slog.w(TAG,
                        "Delegate package does not match. Ignoring startStylusHandwriting");
                return false;
            }
        }
        return true;
    }

    @BinderThread
    @Override
    public void reportPerceptibleAsync(IBinder windowToken, boolean perceptible) {
        Binder.withCleanCallingIdentity(() -> {
            Objects.requireNonNull(windowToken, "windowToken must not be null");
            synchronized (ImfLock.class) {
                Boolean windowPerceptible = mFocusedWindowPerceptible.get(windowToken);
                if (mImeBindingState.mFocusedWindow != windowToken
                        || (windowPerceptible != null && windowPerceptible == perceptible)) {
                    return;
                }
                mFocusedWindowPerceptible.put(windowToken, windowPerceptible);
                updateSystemUiLocked();
            }
        });
    }

    @GuardedBy("ImfLock.class")
    private boolean showCurrentInputLocked(IBinder windowToken,
            @InputMethodManager.ShowFlags int flags, @SoftInputShowHideReason int reason) {
        final var statsToken = createStatsTokenForFocusedClient(true /* show */, reason);
        return showCurrentInputLocked(windowToken, statsToken, flags,
                MotionEvent.TOOL_TYPE_UNKNOWN, null /* resultReceiver */, reason);
    }

    @GuardedBy("ImfLock.class")
    boolean showCurrentInputLocked(IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            @MotionEvent.ToolType int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        if (!mVisibilityStateComputer.onImeShowFlags(statsToken, flags)) {
            return false;
        }

        if (!mSystemReady) {
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);

        mVisibilityStateComputer.requestImeVisibility(windowToken, true);

        // Ensure binding the connection when IME is going to show.
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        userData.mBindingController.setCurrentMethodVisible();
        final IInputMethodInvoker curMethod = getCurMethodLocked();
        ImeTracker.forLogging().onCancelled(mCurStatsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
        final boolean readyToDispatchToIme;
        if (Flags.deferShowSoftInputUntilSessionCreation()) {
            readyToDispatchToIme =
                    curMethod != null && mCurClient != null && mCurClient.mCurSession != null;
        } else {
            readyToDispatchToIme = curMethod != null;
        }
        if (readyToDispatchToIme) {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_HAS_IME);
            mCurStatsToken = null;

            if (lastClickToolType != MotionEvent.TOOL_TYPE_UNKNOWN) {
                curMethod.updateEditorToolType(lastClickToolType);
            }
            mVisibilityApplier.performShowIme(windowToken, statsToken,
                    mVisibilityStateComputer.getShowFlagsForInputMethodServiceOnly(),
                    resultReceiver, reason);
            mVisibilityStateComputer.setInputShown(true);
            return true;
        } else {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
            mCurStatsToken = statsToken;
        }
        return false;
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput", mDumper);
        synchronized (ImfLock.class) {
            if (!canInteractWithImeLocked(uid, client, "hideSoftInput", statsToken)) {
                if (isInputShownLocked()) {
                    ImeTracker.forLogging().onFailed(
                            statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
                } else {
                    ImeTracker.forLogging().onCancelled(statsToken,
                            ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
                }
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideSoftInput");
                if (DEBUG) Slog.v(TAG, "Client requesting input be hidden");
                return InputMethodManagerService.this.hideCurrentInputLocked(windowToken,
                        statsToken, flags, resultReceiver, reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public void hideSoftInputFromServerForTest() {
        synchronized (ImfLock.class) {
            hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT);
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean hideCurrentInputLocked(IBinder windowToken,
            @InputMethodManager.HideFlags int flags, @SoftInputShowHideReason int reason) {
        final var statsToken = createStatsTokenForFocusedClient(false /* show */, reason);
        return hideCurrentInputLocked(windowToken, statsToken, flags, null /* resultReceiver */,
                reason);
    }

    @GuardedBy("ImfLock.class")
    boolean hideCurrentInputLocked(IBinder windowToken, @NonNull ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int flags, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        if (!mVisibilityStateComputer.canHideIme(statsToken, flags)) {
            return false;
        }

        // There is a chance that IMM#hideSoftInput() is called in a transient state where
        // IMMS#InputShown is already updated to be true whereas IMMS#mImeWindowVis is still waiting
        // to be updated with the new value sent from IME process.  Even in such a transient state
        // historically we have accepted an incoming call of IMM#hideSoftInput() from the
        // application process as a valid request, and have even promised such a behavior with CTS
        // since Android Eclair.  That's why we need to accept IMM#hideSoftInput() even when only
        // IMMS#InputShown indicates that the software keyboard is shown.
        // TODO(b/246309664): Clean up IMMS#mImeWindowVis
        IInputMethodInvoker curMethod = getCurMethodLocked();
        final boolean shouldHideSoftInput = curMethod != null
                && (isInputShownLocked() || (mImeWindowVis & InputMethodService.IME_ACTIVE) != 0);

        mVisibilityStateComputer.requestImeVisibility(windowToken, false);
        if (shouldHideSoftInput) {
            // The IME will report its visible state again after the following message finally
            // delivered to the IME process as an IPC.  Hence the inconsistency between
            // IMMS#mInputShown and IMMS#mImeWindowVis should be resolved spontaneously in
            // the final state.
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
            mVisibilityApplier.performHideIme(windowToken, statsToken, resultReceiver, reason);
        } else {
            ImeTracker.forLogging().onCancelled(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
        }
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        userData.mBindingController.setCurrentMethodNotVisible();
        mVisibilityStateComputer.clearImeShowFlags();
        // Cancel existing statsToken for show IME as we got a hide request.
        ImeTracker.forLogging().onCancelled(mCurStatsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
        mCurStatsToken = null;
        return shouldHideSoftInput;
    }

    private boolean isImeClientFocused(IBinder windowToken, ClientState cs) {
        final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                windowToken, cs.mUid, cs.mPid, cs.mSelfReportedDisplayId);
        return imeClientFocus == WindowManagerInternal.ImeClientFocusResult.HAS_IME_FOCUS;
    }

    //TODO(b/293640003): merge with startInputOrWindowGainedFocus once Flags.useZeroJankProxy()
    // is enabled.
    @Override
    public void startInputOrWindowGainedFocusAsync(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq) {
        // implemented by ZeroJankProxy
    }

    @NonNull
    @Override
    public InputBindResult startInputOrWindowGainedFocus(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo editorInfo,
            IRemoteInputConnection inputConnection,
            IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);

            if (editorInfo == null || editorInfo.targetInputMethodUser == null
                    || editorInfo.targetInputMethodUser.getIdentifier() != userId) {
                throw new InvalidParameterException("EditorInfo#targetInputMethodUser must also be "
                        + "specified for cross-user startInputOrWindowGainedFocus()");
            }
        }
        if (windowToken == null) {
            Slog.e(TAG, "windowToken cannot be null.");
            return InputBindResult.NULL;
        }
        // The user represented by userId, must be running.
        if (!mUserManagerInternal.isUserRunning(userId)) {
            // There is a chance that we hit here because of race condition. Let's just
            // return an error code instead of crashing the caller process, which at
            // least has INTERACT_ACROSS_USERS_FULL permission thus is likely to be an
            // important process.
            Slog.w(TAG, "User #" + userId + " is not running.");
            return InputBindResult.INVALID_USER;
        }
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "IMMS.startInputOrWindowGainedFocus");
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startInputOrWindowGainedFocus", mDumper);
            final InputBindResult result;
            synchronized (ImfLock.class) {
                final var userData = mUserDataRepository.getOrCreate(userId);
                final var bindingController = userData.mBindingController;
                // If the system is not yet ready, we shouldn't be running third party code.
                if (!mSystemReady) {
                    return new InputBindResult(
                            InputBindResult.ResultCode.ERROR_SYSTEM_NOT_READY,
                            null /* method */, null /* accessibilitySessions */, null /* channel */,
                            getSelectedMethodIdLocked(),
                            bindingController.getSequenceNumber(),
                            false /* isInputMethodSuppressingSpellChecker */);
                }
                final ClientState cs = mClientController.getClient(client.asBinder());
                if (cs == null) {
                    throw new IllegalArgumentException("Unknown client " + client.asBinder());
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    // Verify if IMMS is in the process of switching user.
                    if (!mExperimentalConcurrentMultiUserModeEnabled
                            && mUserSwitchHandlerTask != null) {
                        // There is already an on-going pending user switch task.
                        final int nextUserId = mUserSwitchHandlerTask.mToUserId;
                        if (userId == nextUserId) {
                            scheduleSwitchUserTaskLocked(userId, cs.mClient);
                            return InputBindResult.USER_SWITCHING;
                        }
                        final int[] profileIdsWithDisabled = mUserManagerInternal.getProfileIds(
                                mCurrentUserId, false /* enabledOnly */);
                        for (int profileId : profileIdsWithDisabled) {
                            if (profileId == userId) {
                                scheduleSwitchUserTaskLocked(userId, cs.mClient);
                                return InputBindResult.USER_SWITCHING;
                            }
                        }
                        return InputBindResult.INVALID_USER;
                    }

                    // Ensure that caller's focused window and display parameters are allowd to
                    // display input method.
                    final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                            windowToken, cs.mUid, cs.mPid, cs.mSelfReportedDisplayId);
                    switch (imeClientFocus) {
                        case WindowManagerInternal.ImeClientFocusResult.DISPLAY_ID_MISMATCH:
                            Slog.e(TAG,
                                    "startInputOrWindowGainedFocusInternal: display ID mismatch.");
                            return InputBindResult.DISPLAY_ID_MISMATCH;
                        case WindowManagerInternal.ImeClientFocusResult.NOT_IME_TARGET_WINDOW:
                            // Check with the window manager to make sure this client actually
                            // has a window with focus.  If not, reject.  This is thread safe
                            // because if the focus changes some time before or after, the
                            // next client receiving focus that has any interest in input will
                            // be calling through here after that change happens.
                            if (DEBUG) {
                                Slog.w(TAG, "Focus gain on non-focused client " + cs.mClient
                                        + " (uid=" + cs.mUid + " pid=" + cs.mPid + ")");
                            }
                            return InputBindResult.NOT_IME_TARGET_WINDOW;
                        case WindowManagerInternal.ImeClientFocusResult.INVALID_DISPLAY_ID:
                            return InputBindResult.INVALID_DISPLAY_ID;
                    }

                    // In case mShowForced flag affects the next client to keep IME visible, when
                    // the current client is leaving due to the next focused client, we clear
                    // mShowForced flag when the next client's targetSdkVersion is T or higher.
                    final boolean shouldClearFlag =
                            mImePlatformCompatUtils.shouldClearShowForcedFlag(cs.mUid);
                    final boolean showForced = mVisibilityStateComputer.mShowForced;
                    if (mImeBindingState.mFocusedWindow != windowToken
                            && showForced && shouldClearFlag) {
                        mVisibilityStateComputer.mShowForced = false;
                    }

                    // Verify if caller is a background user.
                    if (!mExperimentalConcurrentMultiUserModeEnabled && userId != mCurrentUserId) {
                        if (ArrayUtils.contains(
                                mUserManagerInternal.getProfileIds(mCurrentUserId, false),
                                userId)) {
                            // cross-profile access is always allowed here to allow
                            // profile-switching.
                            scheduleSwitchUserTaskLocked(userId, cs.mClient);
                            return InputBindResult.USER_SWITCHING;
                        }
                        Slog.w(TAG, "A background user is requesting window. Hiding IME.");
                        Slog.w(TAG, "If you need to impersonate a foreground user/profile from"
                                + " a background user, use EditorInfo.targetInputMethodUser with"
                                + " INTERACT_ACROSS_USERS_FULL permission.");
                        hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                                SoftInputShowHideReason.HIDE_INVALID_USER);
                        return InputBindResult.INVALID_USER;
                    }

                    if (editorInfo != null && !InputMethodUtils.checkIfPackageBelongsToUid(
                            mPackageManagerInternal, cs.mUid, editorInfo.packageName)) {
                        Slog.e(TAG, "Rejecting this client as it reported an invalid package name."
                                + " uid=" + cs.mUid + " package=" + editorInfo.packageName);
                        return InputBindResult.INVALID_PACKAGE_NAME;
                    }

                    result = startInputOrWindowGainedFocusInternalLocked(startInputReason,
                            client, windowToken, startInputFlags, softInputMode, windowFlags,
                            editorInfo, inputConnection, remoteAccessibilityInputConnection,
                            unverifiedTargetSdkVersion, userData, imeDispatcher, cs);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            if (result == null) {
                // This must never happen, but just in case.
                Slog.wtf(TAG, "InputBindResult is @NonNull. startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason)
                        + " windowFlags=#" + Integer.toHexString(windowFlags)
                        + " editorInfo=" + editorInfo);
                return InputBindResult.NULL;
            }

            return result;
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    private InputBindResult startInputOrWindowGainedFocusInternalLocked(
            @StartInputReason int startInputReason, IInputMethodClient client,
            @NonNull IBinder windowToken, @StartInputFlags int startInputFlags,
            @SoftInputModeFlags int softInputMode, int windowFlags, EditorInfo editorInfo,
            IRemoteInputConnection inputContext,
            @Nullable IRemoteAccessibilityInputConnection remoteAccessibilityInputConnection,
            int unverifiedTargetSdkVersion, @NonNull UserDataRepository.UserData userData,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, @NonNull ClientState cs) {
        if (DEBUG) {
            Slog.v(TAG, "startInputOrWindowGainedFocusInternalLocked: reason="
                    + InputMethodDebug.startInputReasonToString(startInputReason)
                    + " client=" + client.asBinder()
                    + " inputContext=" + inputContext
                    + " editorInfo=" + editorInfo
                    + " startInputFlags="
                    + InputMethodDebug.startInputFlagsToString(startInputFlags)
                    + " softInputMode=" + InputMethodDebug.softInputModeToString(softInputMode)
                    + " windowFlags=#" + Integer.toHexString(windowFlags)
                    + " unverifiedTargetSdkVersion=" + unverifiedTargetSdkVersion
                    + " userData=" + userData
                    + " imeDispatcher=" + imeDispatcher
                    + " cs=" + cs);
        }

        final boolean sameWindowFocused = mImeBindingState.mFocusedWindow == windowToken;
        final boolean isTextEditor = (startInputFlags & StartInputFlags.IS_TEXT_EDITOR) != 0;
        final boolean startInputByWinGainedFocus =
                (startInputFlags & StartInputFlags.WINDOW_GAINED_FOCUS) != 0;
        final int toolType = editorInfo != null
                ? editorInfo.getInitialToolType() : MotionEvent.TOOL_TYPE_UNKNOWN;

        // Init the focused window state (e.g. whether the editor has focused or IME focus has
        // changed from another window).
        final ImeTargetWindowState windowState = new ImeTargetWindowState(
                softInputMode, windowFlags, !sameWindowFocused, isTextEditor,
                startInputByWinGainedFocus, toolType);
        mVisibilityStateComputer.setWindowState(windowToken, windowState);

        if (sameWindowFocused && isTextEditor) {
            if (DEBUG) {
                Slog.w(TAG, "Window already focused, ignoring focus gain of: " + client
                        + " editorInfo=" + editorInfo + ", token = " + windowToken
                        + ", startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason));
            }
            if (editorInfo != null) {
                return startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion, imeDispatcher, userData);
            }
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                    null, null, null, null, -1, false);
        }

        mImeBindingState = new ImeBindingState(userData.mUserId, windowToken, softInputMode, cs,
                editorInfo);
        mFocusedWindowPerceptible.put(windowToken, true);

        // We want to start input before showing the IME, but after closing
        // it.  We want to do this after closing it to help the IME disappear
        // more quickly (not get stuck behind it initializing itself for the
        // new focused input, even if its window wants to hide the IME).
        boolean didStart = false;
        InputBindResult res = null;

        final ImeVisibilityResult imeVisRes = mVisibilityStateComputer.computeState(windowState,
                isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags));
        if (imeVisRes != null) {
            boolean isShow = false;
            switch (imeVisRes.getReason()) {
                case SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY:
                case SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE:
                    isShow = true;

                    if (editorInfo != null) {
                        res = startInputUncheckedLocked(cs, inputContext,
                                remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                                startInputReason, unverifiedTargetSdkVersion,
                                imeDispatcher, userData);
                        didStart = true;
                    }
                    break;
            }
            final var statsToken = createStatsTokenForFocusedClient(isShow, imeVisRes.getReason());
            mVisibilityApplier.applyImeVisibility(mImeBindingState.mFocusedWindow, statsToken,
                    imeVisRes.getState(), imeVisRes.getReason());
            if (imeVisRes.getReason() == SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW) {
                // If focused display changed, we should unbind current method
                // to make app window in previous display relayout after Ime
                // window token removed.
                // Note that we can trust client's display ID as long as it matches
                // to the display ID obtained from the window.
                if (cs.mSelfReportedDisplayId != getCurTokenDisplayIdLocked()) {
                    userData.mBindingController.unbindCurrentMethod();
                }
            }
        }
        if (!didStart) {
            if (editorInfo != null) {
                res = startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion,
                        imeDispatcher, userData);
            } else {
                res = InputBindResult.NULL_EDITOR_INFO;
            }
        }
        return res;
    }

    @GuardedBy("ImfLock.class")
    private boolean canInteractWithImeLocked(int uid, IInputMethodClient client, String methodName,
            @Nullable ImeTracker.Token statsToken) {
        if (mCurClient == null || client == null
                || mCurClient.mClient.asBinder() != client.asBinder()) {
            // We need to check if this is the current client with
            // focus in the window manager, to allow this call to
            // be made before input is started in it.
            final ClientState cs = mClientController.getClient(client.asBinder());
            if (cs == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
                throw new IllegalArgumentException("unknown client " + client.asBinder());
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
            if (!isImeClientFocused(mImeBindingState.mFocusedWindow, cs)) {
                Slog.w(TAG, String.format("Ignoring %s of uid %d : %s", methodName, uid, client));
                return false;
            }
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
        return true;
    }

    @GuardedBy("ImfLock.class")
    private boolean canShowInputMethodPickerLocked(IInputMethodClient client) {
        final int uid = Binder.getCallingUid();
        if (mImeBindingState.mFocusedWindowClient != null && client != null
                && mImeBindingState.mFocusedWindowClient.mClient.asBinder() == client.asBinder()) {
            return true;
        }
        if (mCurrentUserId != UserHandle.getUserId(uid)) {
            return false;
        }
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        final var curIntent = userData.mBindingController.getCurIntent();
        if (curIntent != null && InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, uid, curIntent.getComponent().getPackageName())) {
            return true;
        }
        return false;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode) {
        synchronized (ImfLock.class) {
            if (!canShowInputMethodPickerLocked(client)) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid "
                        + Binder.getCallingUid() + ": " + client);
                return;
            }

            // Always call subtype picker, because subtype picker is a superset of input method
            // picker.
            final int displayId =
                    (mCurClient != null) ? mCurClient.mSelfReportedDisplayId : DEFAULT_DISPLAY;
            mHandler.obtainMessage(MSG_SHOW_IM_SUBTYPE_PICKER, auxiliarySubtypeMode, displayId)
                    .sendToTarget();
        }
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        // Always call subtype picker, because subtype picker is a superset of input method
        // picker.
        mHandler.obtainMessage(MSG_SHOW_IM_SUBTYPE_PICKER, auxiliarySubtypeMode, displayId)
                .sendToTarget();
    }

    /**
     * A test API for CTS to make sure that the input method menu is showing.
     */
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isInputMethodPickerShownForTest() {
        synchronized (ImfLock.class) {
            return mMenuController.isisInputMethodPickerShownForTestLocked();
        }
    }

    @NonNull
    private static IllegalArgumentException getExceptionForUnknownImeId(
            @Nullable String imeId) {
        return new IllegalArgumentException("Unknown id: " + imeId);
    }

    @BinderThread
    private void setInputMethod(@NonNull IBinder token, String id) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            final InputMethodInfo imi = settings.getMethodMap().get(id);
            if (imi == null || !canCallerAccessInputMethod(
                    imi.getPackageName(), callingUid, userId, settings)) {
                throw getExceptionForUnknownImeId(id);
            }
            setInputMethodWithSubtypeIdLocked(token, id, NOT_A_SUBTYPE_ID);
        }
    }

    @BinderThread
    private void setInputMethodAndSubtype(@NonNull IBinder token, String id,
            InputMethodSubtype subtype) {
        final int callingUid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(callingUid);
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            final InputMethodInfo imi = settings.getMethodMap().get(id);
            if (imi == null || !canCallerAccessInputMethod(
                    imi.getPackageName(), callingUid, userId, settings)) {
                throw getExceptionForUnknownImeId(id);
            }
            if (subtype != null) {
                setInputMethodWithSubtypeIdLocked(token, id,
                        SubtypeUtils.getSubtypeIdFromHashCode(imi, subtype.hashCode()));
            } else {
                setInputMethod(token, id);
            }
        }
    }

    @BinderThread
    private boolean switchToPreviousInputMethod(@NonNull IBinder token) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return false;
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            final Pair<String, String> lastIme = settings.getLastInputMethodAndSubtype();
            final InputMethodInfo lastImi;
            if (lastIme != null) {
                lastImi = settings.getMethodMap().get(lastIme.first);
            } else {
                lastImi = null;
            }
            String targetLastImiId = null;
            int subtypeId = NOT_A_SUBTYPE_ID;
            if (lastIme != null && lastImi != null) {
                final boolean imiIdIsSame = lastImi.getId().equals(getSelectedMethodIdLocked());
                final int lastSubtypeHash = Integer.parseInt(lastIme.second);
                final int currentSubtypeHash = mCurrentSubtype == null ? NOT_A_SUBTYPE_ID
                        : mCurrentSubtype.hashCode();
                // If the last IME is the same as the current IME and the last subtype is not
                // defined, there is no need to switch to the last IME.
                if (!imiIdIsSame || lastSubtypeHash != currentSubtypeHash) {
                    targetLastImiId = lastIme.first;
                    subtypeId = SubtypeUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                }
            }

            if (TextUtils.isEmpty(targetLastImiId)
                    && !InputMethodUtils.canAddToLastInputMethod(mCurrentSubtype)) {
                // This is a safety net. If the currentSubtype can't be added to the history
                // and the framework couldn't find the last ime, we will make the last ime be
                // the most applicable enabled keyboard subtype of the system imes.
                final List<InputMethodInfo> enabled = settings.getEnabledInputMethodList();
                if (enabled != null) {
                    final int enabledCount = enabled.size();
                    final String locale;
                    if (mCurrentSubtype != null
                            && !TextUtils.isEmpty(mCurrentSubtype.getLocale())) {
                        locale = mCurrentSubtype.getLocale();
                    } else {
                        locale = SystemLocaleWrapper.get(mCurrentUserId).get(0).toString();
                    }
                    for (int i = 0; i < enabledCount; ++i) {
                        final InputMethodInfo imi = enabled.get(i);
                        if (imi.getSubtypeCount() > 0 && imi.isSystem()) {
                            InputMethodSubtype keyboardSubtype =
                                    SubtypeUtils.findLastResortApplicableSubtype(
                                            SubtypeUtils.getSubtypes(imi),
                                            SubtypeUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                            if (keyboardSubtype != null) {
                                targetLastImiId = imi.getId();
                                subtypeId = SubtypeUtils.getSubtypeIdFromHashCode(imi,
                                        keyboardSubtype.hashCode());
                                if (keyboardSubtype.getLocale().equals(locale)) {
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (!TextUtils.isEmpty(targetLastImiId)) {
                if (DEBUG) {
                    Slog.d(TAG, "Switch to: " + lastImi.getId() + ", " + lastIme.second
                            + ", from: " + getSelectedMethodIdLocked() + ", " + subtypeId);
                }
                setInputMethodWithSubtypeIdLocked(token, targetLastImiId, subtypeId);
                return true;
            } else {
                return false;
            }
        }
    }

    @BinderThread
    private boolean switchToNextInputMethod(@NonNull IBinder token, boolean onlyCurrentIme) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return false;
            }
            return switchToNextInputMethodLocked(token, onlyCurrentIme);
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean switchToNextInputMethodLocked(@Nullable IBinder token, boolean onlyCurrentIme) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        final ImeSubtypeListItem nextSubtype = mSwitchingController.getNextInputMethodLocked(
                onlyCurrentIme, settings.getMethodMap().get(getSelectedMethodIdLocked()),
                mCurrentSubtype);
        if (nextSubtype == null) {
            return false;
        }
        setInputMethodWithSubtypeIdLocked(token, nextSubtype.mImi.getId(),
                nextSubtype.mSubtypeId);
        return true;
    }

    @BinderThread
    private boolean shouldOfferSwitchingToNextInputMethod(@NonNull IBinder token) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return false;
            }
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            final ImeSubtypeListItem nextSubtype = mSwitchingController.getNextInputMethodLocked(
                    false /* onlyCurrentIme */,
                    settings.getMethodMap().get(getSelectedMethodIdLocked()), mCurrentSubtype);
            return nextSubtype != null;
        }
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            return InputMethodSettingsRepository.get(userId).getLastInputMethodSubtype();
        }
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes,
            @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final int callingUid = Binder.getCallingUid();

        // By this IPC call, only a process which shares the same uid with the IME can add
        // additional input method subtypes to the IME.
        if (TextUtils.isEmpty(imiId) || subtypes == null) return;
        final ArrayList<InputMethodSubtype> toBeAdded = new ArrayList<>();
        for (InputMethodSubtype subtype : subtypes) {
            if (!toBeAdded.contains(subtype)) {
                toBeAdded.add(subtype);
            } else {
                Slog.w(TAG, "Duplicated subtype definition found: "
                        + subtype.getLocale() + ", " + subtype.getMode());
            }
        }
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }

            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final boolean isCurrentUser = (mCurrentUserId == userId);
            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
            final var newAdditionalSubtypeMap = settings.getNewAdditionalSubtypeMap(
                    imiId, toBeAdded, additionalSubtypeMap, mPackageManagerInternal, callingUid);
            if (additionalSubtypeMap != newAdditionalSubtypeMap) {
                AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                        settings.getMethodMap());
                final InputMethodSettings newSettings = queryInputMethodServicesInternal(mContext,
                        userId, AdditionalSubtypeMapRepository.get(userId),
                        DirectBootAwareness.AUTO);
                InputMethodSettingsRepository.put(userId, newSettings);
                if (isCurrentUser) {
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                    }
                }
            }
        }
    }

    @Override
    public void setExplicitlyEnabledInputMethodSubtypes(String imeId,
            @NonNull int[] subtypeHashCodes, @UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final int callingUid = Binder.getCallingUid();
        final ComponentName imeComponentName =
                imeId != null ? ComponentName.unflattenFromString(imeId) : null;
        if (imeComponentName == null || !InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, callingUid, imeComponentName.getPackageName())) {
            throw new SecurityException("Calling UID=" + callingUid + " does not belong to imeId="
                    + imeId);
        }
        Objects.requireNonNull(subtypeHashCodes, "subtypeHashCodes must not be null");

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (ImfLock.class) {
                final boolean currentUser = (mCurrentUserId == userId);
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.setEnabledInputMethodSubtypes(imeId, subtypeHashCodes)) {
                    return;
                }
                if (currentUser) {
                    // To avoid unnecessary "updateInputMethodsFromSettingsLocked" from happening.
                    if (mSettingsObserver != null) {
                        mSettingsObserver.mLastEnabled = settings.getEnabledInputMethodsStr();
                    }
                    updateInputMethodsFromSettingsLocked(false /* enabledChanged */);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * This is kept due to {@code @UnsupportedAppUsage} in
     * {@link InputMethodManager#getInputMethodWindowVisibleHeight()} and a dependency in
     * {@link InputMethodService#onCreate()}.
     * @return {@link WindowManagerInternal#getInputMethodWindowVisibleHeight(int)}
     *
     * @deprecated TODO(b/113914148): Check if we can remove this
     */
    @Override
    @Deprecated
    public int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        int callingUid = Binder.getCallingUid();
        return Binder.withCleanCallingIdentity(() -> {
            final int curTokenDisplayId;
            synchronized (ImfLock.class) {
                if (!canInteractWithImeLocked(callingUid, client,
                        "getInputMethodWindowVisibleHeight", null /* statsToken */)) {
                    return 0;
                }
                // This should probably use the caller's display id, but because this is unsupported
                // and maintained only for compatibility, there's no point in fixing it.
                curTokenDisplayId = getCurTokenDisplayIdLocked();
            }
            return mWindowManagerInternal.getInputMethodWindowVisibleHeight(curTokenDisplayId);
        });
    }

    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void removeImeSurface() {
        mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
    }

    @Override
    public void removeImeSurfaceFromWindowAsync(IBinder windowToken) {
        // No permission check, because we'll only execute the request if the calling window is
        // also the current IME client.
        mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE_FROM_WINDOW, windowToken).sendToTarget();
    }

    private void registerDeviceListenerAndCheckStylusSupport() {
        final InputManager im = mContext.getSystemService(InputManager.class);
        final IntArray stylusIds = getStylusInputDeviceIds(im);
        if (stylusIds.size() > 0) {
            synchronized (ImfLock.class) {
                mStylusIds = new IntArray();
                mStylusIds.addAll(stylusIds);
            }
        }
        im.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                InputDevice device = im.getInputDevice(deviceId);
                if (device != null && isStylusDevice(device)) {
                    add(deviceId);
                }
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                remove(deviceId);
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                InputDevice device = im.getInputDevice(deviceId);
                if (device == null) {
                    return;
                }
                if (isStylusDevice(device)) {
                    add(deviceId);
                } else {
                    remove(deviceId);
                }
            }

            private void add(int deviceId) {
                synchronized (ImfLock.class) {
                    addStylusDeviceIdLocked(deviceId);
                }
            }

            private void remove(int deviceId) {
                synchronized (ImfLock.class) {
                    removeStylusDeviceIdLocked(deviceId);
                }
            }
        }, mHandler);
    }

    @GuardedBy("ImfLock.class")
    private void addStylusDeviceIdLocked(int deviceId) {
        if (mStylusIds == null) {
            mStylusIds = new IntArray();
        } else if (mStylusIds.indexOf(deviceId) != -1) {
            return;
        }
        Slog.d(TAG, "New Stylus deviceId" + deviceId + " added.");
        mStylusIds.add(deviceId);
        // a new Stylus is detected. If IME supports handwriting, and we don't have
        // handwriting initialized, lets do it now.
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        final var bindingController = userData.mBindingController;
        if (!mHwController.getCurrentRequestId().isPresent()
                && bindingController.supportsStylusHandwriting()) {
            scheduleResetStylusHandwriting();
        }
    }

    private void removeStylusDeviceIdLocked(int deviceId) {
        if (mStylusIds == null || mStylusIds.size() == 0) {
            return;
        }
        int index;
        if ((index = mStylusIds.indexOf(deviceId)) != -1) {
            mStylusIds.remove(index);
            Slog.d(TAG, "Stylus deviceId: " + deviceId + " removed.");
        }
        if (mStylusIds.size() == 0) {
            // no more supported stylus(es) in system.
            mHwController.reset();
            scheduleRemoveStylusHandwritingWindow();
        }
    }

    private static boolean isStylusDevice(InputDevice inputDevice) {
        return inputDevice.supportsSource(InputDevice.SOURCE_STYLUS)
                || inputDevice.supportsSource(InputDevice.SOURCE_BLUETOOTH_STYLUS);
    }

    @GuardedBy("ImfLock.class")
    private boolean hasSupportedStylusLocked() {
        return mStylusIds != null && mStylusIds.size() != 0;
    }

    /**
     * Helper method that adds a virtual stylus id for next handwriting session test if
     * a stylus deviceId is not already registered on device.
     */
    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        int uid = Binder.getCallingUid();
        synchronized (ImfLock.class) {
            if (!canInteractWithImeLocked(uid, client, "addVirtualStylusIdForTestSession",
                    null /* statsToken */)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG) Slog.v(TAG, "Adding virtual stylus id for session");
                addStylusDeviceIdLocked(VIRTUAL_STYLUS_ID_FOR_TEST);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    /**
     * Helper method to set a stylus idle-timeout after which handwriting {@code InkWindow}
     * will be removed.
     *
     * @param timeout to set in milliseconds. To reset to default, use a value <= zero
     */
    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void setStylusWindowIdleTimeoutForTest(
            IInputMethodClient client, @DurationMillisLong long timeout) {
        int uid = Binder.getCallingUid();
        synchronized (ImfLock.class) {
            if (!canInteractWithImeLocked(uid, client, "setStylusWindowIdleTimeoutForTest",
                    null /* statsToken */)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG) Slog.v(TAG, "Setting stylus window idle timeout");
                getCurMethodLocked().setStylusWindowIdleTimeoutForTest(timeout);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void removeVirtualStylusIdForTestSessionLocked() {
        removeStylusDeviceIdLocked(VIRTUAL_STYLUS_ID_FOR_TEST);
    }

    private static IntArray getStylusInputDeviceIds(InputManager im) {
        IntArray stylusIds = new IntArray();
        for (int id : im.getInputDeviceIds()) {
            InputDevice device = im.getInputDevice(id);
            if (device != null && device.isEnabled() && isStylusDevice(device)) {
                stylusIds.add(id);
            }
        }

        return stylusIds;
    }

    /**
     * Starting point for dumping the IME tracing information in proto format.
     *
     * @param protoDump dump information from the IME client side
     */
    @BinderThread
    @Override
    public void startProtoDump(byte[] protoDump, int source, String where) {
        if (protoDump == null && source != ImeTracing.IME_TRACING_FROM_IMMS) {
            // Dump not triggered from IMMS, but no proto information provided.
            return;
        }
        ImeTracing tracingInstance = ImeTracing.getInstance();
        if (!tracingInstance.isAvailable() || !tracingInstance.isEnabled()) {
            return;
        }

        ProtoOutputStream proto = new ProtoOutputStream();
        switch (source) {
            case ImeTracing.IME_TRACING_FROM_CLIENT:
                final long client_token = proto.start(InputMethodClientsTraceFileProto.ENTRY);
                proto.write(InputMethodClientsTraceProto.ELAPSED_REALTIME_NANOS,
                        SystemClock.elapsedRealtimeNanos());
                proto.write(InputMethodClientsTraceProto.WHERE, where);
                proto.write(InputMethodClientsTraceProto.CLIENT, protoDump);
                proto.end(client_token);
                break;
            case ImeTracing.IME_TRACING_FROM_IMS:
                final long service_token = proto.start(InputMethodServiceTraceFileProto.ENTRY);
                proto.write(InputMethodServiceTraceProto.ELAPSED_REALTIME_NANOS,
                        SystemClock.elapsedRealtimeNanos());
                proto.write(InputMethodServiceTraceProto.WHERE, where);
                proto.write(InputMethodServiceTraceProto.INPUT_METHOD_SERVICE, protoDump);
                proto.end(service_token);
                break;
            case ImeTracing.IME_TRACING_FROM_IMMS:
                final long managerservice_token =
                        proto.start(InputMethodManagerServiceTraceFileProto.ENTRY);
                proto.write(InputMethodManagerServiceTraceProto.ELAPSED_REALTIME_NANOS,
                        SystemClock.elapsedRealtimeNanos());
                proto.write(InputMethodManagerServiceTraceProto.WHERE, where);
                dumpDebug(proto,
                        InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
                proto.end(managerservice_token);
                break;
            default:
                // Dump triggered by a source not recognised.
                return;
        }
        tracingInstance.addToBuffer(proto, source);
    }

    @BinderThread
    @Override
    public boolean isImeTraceEnabled() {
        return ImeTracing.getInstance().isEnabled();
    }

    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() {
        ImeTracing.getInstance().startTrace(null /* printwriter */);
        synchronized (ImfLock.class) {
            mClientController.forAllClients(c -> c.mClient.setImeTraceEnabled(true /* enabled */));
        }
    }

    @BinderThread
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() {
        ImeTracing.getInstance().stopTrace(null /* printwriter */);
        synchronized (ImfLock.class) {
            mClientController.forAllClients(c -> c.mClient.setImeTraceEnabled(false /* enabled */));
        }
    }

    private void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (ImfLock.class) {
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            final var bindingController = userData.mBindingController;
            final long token = proto.start(fieldId);
            proto.write(CUR_METHOD_ID, getSelectedMethodIdLocked());
            proto.write(CUR_SEQ, bindingController.getSequenceNumber());
            proto.write(CUR_CLIENT, Objects.toString(mCurClient));
            mImeBindingState.dumpDebug(proto, mWindowManagerInternal);
            proto.write(LAST_IME_TARGET_WINDOW_NAME,
                    mWindowManagerInternal.getWindowName(mLastImeTargetWindow));
            proto.write(CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE, InputMethodDebug.softInputModeToString(
                    mImeBindingState.mFocusedWindowSoftInputMode));
            if (mCurEditorInfo != null) {
                mCurEditorInfo.dumpDebug(proto, CUR_ATTRIBUTE);
            }
            proto.write(CUR_ID, bindingController.getCurId());
            mVisibilityStateComputer.dumpDebug(proto, fieldId);
            proto.write(IN_FULLSCREEN_MODE, mInFullscreenMode);
            proto.write(CUR_TOKEN, Objects.toString(getCurTokenLocked()));
            proto.write(CUR_TOKEN_DISPLAY_ID, getCurTokenDisplayIdLocked());
            proto.write(SYSTEM_READY, mSystemReady);
            proto.write(HAVE_CONNECTION, bindingController.hasMainConnection());
            proto.write(BOUND_TO_METHOD, mBoundToMethod);
            proto.write(IS_INTERACTIVE, mIsInteractive);
            proto.write(BACK_DISPOSITION, mBackDisposition);
            proto.write(IME_WINDOW_VISIBILITY, mImeWindowVis);
            proto.write(SHOW_IME_WITH_HARD_KEYBOARD, mMenuController.getShowImeWithHardKeyboard());
            proto.end(token);
        }
    }

    @BinderThread
    private void notifyUserAction(@NonNull IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "Got the notification of a user action.");
        }
        synchronized (ImfLock.class) {
            if (getCurTokenLocked() != token) {
                if (DEBUG) {
                    Slog.d(TAG, "Ignoring the user action notification from IMEs that are no longer"
                            + " active.");
                }
                return;
            }
            if (mCurrentUserId != mSwitchingController.getUserId()) {
                return;
            }
            final InputMethodInfo imi = InputMethodSettingsRepository.get(mCurrentUserId)
                    .getMethodMap().get(getSelectedMethodIdLocked());
            if (imi != null) {
                mSwitchingController.onUserActionLocked(imi, mCurrentSubtype);
            }
        }
    }

    @BinderThread
    private void applyImeVisibility(IBinder token, IBinder windowToken, boolean setVisible,
            @NonNull ImeTracker.Token statsToken) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.applyImeVisibility");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    return;
                }
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                final IBinder requestToken = mVisibilityStateComputer.getWindowTokenFrom(
                        windowToken);
                mVisibilityApplier.applyImeVisibility(requestToken, statsToken,
                        setVisible ? ImeVisibilityStateComputer.STATE_SHOW_IME
                                : ImeVisibilityStateComputer.STATE_HIDE_IME);
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @BinderThread
    private void resetStylusHandwriting(int requestId) {
        synchronized (ImfLock.class) {
            final OptionalInt curRequest = mHwController.getCurrentRequestId();
            if (!curRequest.isPresent() || curRequest.getAsInt() != requestId) {
                Slog.w(TAG, "IME requested to finish handwriting with a mismatched requestId: "
                        + requestId);
            }
            removeVirtualStylusIdForTestSessionLocked();
            scheduleResetStylusHandwriting();
        }
    }

    @GuardedBy("ImfLock.class")
    private void setInputMethodWithSubtypeIdLocked(IBinder token, String id, int subtypeId) {
        if (token == null) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Using null token requires permission "
                                + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }
        } else if (getCurTokenLocked() != token) {
            Slog.w(TAG, "Ignoring setInputMethod of uid " + Binder.getCallingUid()
                    + " token: " + token);
            return;
        } else {
            // Called with current IME's token.
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            if (settings.getMethodMap().get(id) != null
                    && settings.getEnabledInputMethodListWithFilter(
                            (info) -> info.getId().equals(id)).isEmpty()) {
                throw new IllegalStateException("Requested IME is not enabled: " + id);
            }
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            setInputMethodLocked(id, subtypeId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Called right after {@link IInputMethod#showSoftInput} or {@link IInputMethod#hideSoftInput}.
     */
    @GuardedBy("ImfLock.class")
    void onShowHideSoftInputRequested(boolean show, IBinder requestImeToken,
            @SoftInputShowHideReason int reason, @Nullable ImeTracker.Token statsToken) {
        final IBinder requestToken = mVisibilityStateComputer.getWindowTokenFrom(requestImeToken);
        final WindowManagerInternal.ImeTargetInfo info =
                mWindowManagerInternal.onToggleImeRequested(
                        show, mImeBindingState.mFocusedWindow, requestToken,
                        getCurTokenDisplayIdLocked());
        mSoftInputShowHideHistory.addEntry(new SoftInputShowHideHistory.Entry(
                mImeBindingState.mFocusedWindowClient, mImeBindingState.mFocusedWindowEditorInfo,
                info.focusedWindowName, mImeBindingState.mFocusedWindowSoftInputMode, reason,
                mInFullscreenMode, info.requestWindowName, info.imeControlTargetName,
                info.imeLayerTargetName, info.imeSurfaceParentName));

        if (statsToken != null) {
            mImeTrackerService.onImmsUpdate(statsToken, info.requestWindowName);
        }
    }

    @BinderThread
    private void hideMySoftInput(@NonNull IBinder token, @NonNull ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int flags, @SoftInputShowHideReason int reason) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideMySoftInput");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    return;
                }
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                final long ident = Binder.clearCallingIdentity();
                try {
                    hideCurrentInputLocked(mLastImeTargetWindow, statsToken, flags,
                            null /* resultReceiver */, reason);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @BinderThread
    private void showMySoftInput(@NonNull IBinder token, @NonNull ImeTracker.Token statsToken,
            @InputMethodManager.ShowFlags int flags, @SoftInputShowHideReason int reason) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showMySoftInput");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    return;
                }
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                final long ident = Binder.clearCallingIdentity();
                try {
                    showCurrentInputLocked(mLastImeTargetWindow, statsToken, flags,
                            MotionEvent.TOOL_TYPE_UNKNOWN, null /* resultReceiver */, reason);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @VisibleForTesting
    ImeVisibilityApplier getVisibilityApplier() {
        synchronized (ImfLock.class) {
            return mVisibilityApplier;
        }
    }

    void onApplyImeVisibilityFromComputer(IBinder windowToken, @NonNull ImeTracker.Token statsToken,
            @NonNull ImeVisibilityResult result) {
        synchronized (ImfLock.class) {
            mVisibilityApplier.applyImeVisibility(windowToken, statsToken, result.getState(),
                    result.getReason());
        }
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionLocked(SessionState session) {
        if (mEnabledSession != session) {
            if (mEnabledSession != null && mEnabledSession.mSession != null) {
                if (DEBUG) Slog.v(TAG, "Disabling: " + mEnabledSession);
                mEnabledSession.mMethod.setSessionEnabled(mEnabledSession.mSession, false);
            }
            mEnabledSession = session;
            if (mEnabledSession != null && mEnabledSession.mSession != null) {
                if (DEBUG) Slog.v(TAG, "Enabling: " + mEnabledSession);
                mEnabledSession.mMethod.setSessionEnabled(mEnabledSession.mSession, true);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionForAccessibilityLocked(
            SparseArray<AccessibilitySessionState> accessibilitySessions) {
        // mEnabledAccessibilitySessions could the same object as accessibilitySessions.
        SparseArray<IAccessibilityInputMethodSession> disabledSessions = new SparseArray<>();
        for (int i = 0; i < mEnabledAccessibilitySessions.size(); i++) {
            if (!accessibilitySessions.contains(mEnabledAccessibilitySessions.keyAt(i))) {
                AccessibilitySessionState sessionState = mEnabledAccessibilitySessions.valueAt(i);
                if (sessionState != null) {
                    disabledSessions.append(mEnabledAccessibilitySessions.keyAt(i),
                            sessionState.mSession);
                }
            }
        }
        if (disabledSessions.size() > 0) {
            AccessibilityManagerInternal.get().setImeSessionEnabled(disabledSessions,
                    false);
        }
        SparseArray<IAccessibilityInputMethodSession> enabledSessions = new SparseArray<>();
        for (int i = 0; i < accessibilitySessions.size(); i++) {
            if (!mEnabledAccessibilitySessions.contains(accessibilitySessions.keyAt(i))) {
                AccessibilitySessionState sessionState = accessibilitySessions.valueAt(i);
                if (sessionState != null) {
                    enabledSessions.append(accessibilitySessions.keyAt(i), sessionState.mSession);
                }
            }
        }
        if (enabledSessions.size() > 0) {
            AccessibilityManagerInternal.get().setImeSessionEnabled(enabledSessions,
                    true);
        }
        mEnabledAccessibilitySessions = accessibilitySessions;
    }

    @SuppressWarnings("unchecked")
    @UiThread
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SHOW_IM_SUBTYPE_PICKER:
                final boolean showAuxSubtypes;
                final int displayId = msg.arg2;
                switch (msg.arg1) {
                    case InputMethodManager.SHOW_IM_PICKER_MODE_AUTO:
                        // This is undocumented so far, but IMM#showInputMethodPicker() has been
                        // implemented so that auxiliary subtypes will be excluded when the soft
                        // keyboard is invisible.
                        synchronized (ImfLock.class) {
                            showAuxSubtypes = isInputShownLocked();
                        }
                        break;
                    case InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES:
                        showAuxSubtypes = true;
                        break;
                    case InputMethodManager.SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES:
                        showAuxSubtypes = false;
                        break;
                    default:
                        Slog.e(TAG, "Unknown subtype picker mode = " + msg.arg1);
                        return false;
                }
                synchronized (ImfLock.class) {
                    final InputMethodSettings settings =
                            InputMethodSettingsRepository.get(mCurrentUserId);
                    final boolean isScreenLocked = mWindowManagerInternal.isKeyguardLocked()
                            && mWindowManagerInternal.isKeyguardSecure(settings.getUserId());
                    final String lastInputMethodId = settings.getSelectedInputMethod();
                    int lastInputMethodSubtypeId =
                            settings.getSelectedInputMethodSubtypeId(lastInputMethodId);

                    final List<ImeSubtypeListItem> imList = InputMethodSubtypeSwitchingController
                            .getSortedInputMethodAndSubtypeList(
                                    showAuxSubtypes, isScreenLocked, true /* forImeMenu */,
                                    mContext, settings.getMethodMap(), settings.getUserId());
                    if (imList.isEmpty()) {
                        Slog.w(TAG, "Show switching menu failed, imList is empty,"
                                + " showAuxSubtypes: " + showAuxSubtypes
                                + " isScreenLocked: " + isScreenLocked
                                + " userId: " + settings.getUserId());
                        return false;
                    }

                    mMenuController.showInputMethodMenuLocked(showAuxSubtypes, displayId,
                            lastInputMethodId, lastInputMethodSubtypeId, imList);
                }
                return true;

            // ---------------------------------------------------------

            case MSG_HIDE_ALL_INPUT_METHODS:
                synchronized (ImfLock.class) {
                    @SoftInputShowHideReason final int reason = (int) msg.obj;
                    hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */, reason);
                }
                return true;
            case MSG_REMOVE_IME_SURFACE: {
                synchronized (ImfLock.class) {
                    try {
                        if (mEnabledSession != null && mEnabledSession.mSession != null
                                && !isShowRequestedForCurrentWindow()) {
                            mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException e) {
                    }
                }
                return true;
            }
            case MSG_REMOVE_IME_SURFACE_FROM_WINDOW: {
                IBinder windowToken = (IBinder) msg.obj;
                synchronized (ImfLock.class) {
                    try {
                        if (windowToken == mImeBindingState.mFocusedWindow
                                && mEnabledSession != null && mEnabledSession.mSession != null) {
                            mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException e) {
                    }
                }
                return true;
            }
            case MSG_UPDATE_IME_WINDOW_STATUS: {
                updateImeWindowStatus(msg.arg1 == 1);
                return true;
            }

            // ---------------------------------------------------------

            case MSG_SET_INTERACTIVE:
                handleSetInteractive(msg.arg1 != 0);
                return true;

            // --------------------------------------------------------------
            case MSG_HARD_KEYBOARD_SWITCH_CHANGED:
                mMenuController.handleHardKeyboardStatusChange(msg.arg1 == 1);
                synchronized (ImfLock.class) {
                    sendOnNavButtonFlagsChangedLocked();
                }
                return true;
            case MSG_SYSTEM_UNLOCK_USER: {
                final int userId = msg.arg1;
                onUnlockUser(userId);
                return true;
            }
            case MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED: {
                final int userId = msg.arg1;
                final List<InputMethodInfo> imes = (List<InputMethodInfo>) msg.obj;
                mInputMethodListListeners.forEach(
                        listener -> listener.onInputMethodListUpdated(imes, userId));
                return true;
            }

            // ---------------------------------------------------------------
            case MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE: {
                if (mAudioManagerInternal == null) {
                    mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
                }
                if (mAudioManagerInternal != null) {
                    mAudioManagerInternal.setInputMethodServiceUid(msg.arg1 /* uid */);
                }
                return true;
            }

            case MSG_RESET_HANDWRITING: {
                synchronized (ImfLock.class) {
                    final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
                    final var bindingController = userData.mBindingController;
                    if (bindingController.supportsStylusHandwriting()
                            && getCurMethodLocked() != null && hasSupportedStylusLocked()) {
                        Slog.d(TAG, "Initializing Handwriting Spy");
                        mHwController.initializeHandwritingSpy(getCurTokenDisplayIdLocked());
                    } else {
                        mHwController.reset();
                    }
                }
                return true;
            }
            case MSG_PREPARE_HANDWRITING_DELEGATION:
                synchronized (ImfLock.class) {
                    int userId = msg.arg1;
                    String delegate = (String) ((Pair) msg.obj).first;
                    String delegator = (String) ((Pair) msg.obj).second;
                    mHwController.prepareStylusHandwritingDelegation(
                            userId, delegate, delegator, /* connectionless= */ false);
                }
                return true;
            case MSG_START_HANDWRITING:
                synchronized (ImfLock.class) {
                    IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod == null || mImeBindingState.mFocusedWindow == null) {
                        return true;
                    }
                    final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
                    final var bindingController = userData.mBindingController;
                    final HandwritingModeController.HandwritingSession session =
                            mHwController.startHandwritingSession(
                                    msg.arg1 /*requestId*/,
                                    msg.arg2 /*pid*/,
                                    bindingController.getCurMethodUid(),
                                    mImeBindingState.mFocusedWindow);
                    if (session == null) {
                        Slog.e(TAG,
                                "Failed to start handwriting session for requestId: " + msg.arg1);
                        return true;
                    }

                    if (!curMethod.startStylusHandwriting(session.getRequestId(),
                            session.getHandwritingChannel(), session.getRecordedEvents())) {
                        // When failed to issue IPCs, re-initialize handwriting state.
                        Slog.w(TAG, "Resetting handwriting mode.");
                        scheduleResetStylusHandwriting();
                    }
                }
                return true;
            case MSG_FINISH_HANDWRITING:
                synchronized (ImfLock.class) {
                    IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod != null && mHwController.getCurrentRequestId().isPresent()) {
                        curMethod.finishStylusHandwriting();
                    }
                }
                return true;
            case MSG_REMOVE_HANDWRITING_WINDOW:
                synchronized (ImfLock.class) {
                    IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod != null) {
                        curMethod.removeStylusHandwritingWindow();
                    }
                }
                return true;
        }
        return false;
    }

    @BinderThread
    private void onStylusHandwritingReady(int requestId, int pid) {
        mHandler.obtainMessage(MSG_START_HANDWRITING, requestId, pid).sendToTarget();
    }

    private void handleSetInteractive(final boolean interactive) {
        synchronized (ImfLock.class) {
            mIsInteractive = interactive;
            updateSystemUiLocked(interactive ? mImeWindowVis : 0, mBackDisposition);

            // Inform the current client of the change in active status
            if (mCurClient == null || mCurClient.mClient == null) {
                return;
            }
            // TODO(b/325515685): user data must be retrieved by a userId parameter
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            final var bindingController = userData.mBindingController;
            if (mImePlatformCompatUtils.shouldUseSetInteractiveProtocol(
                    bindingController.getCurMethodUid())) {
                // Handle IME visibility when interactive changed before finishing the input to
                // ensure we preserve the last state as possible.
                final ImeVisibilityResult imeVisRes = mVisibilityStateComputer.onInteractiveChanged(
                        mImeBindingState.mFocusedWindow, interactive);
                if (imeVisRes != null) {
                    // Pass in a null statsToken as the IME snapshot is not tracked by ImeTracker.
                    mVisibilityApplier.applyImeVisibility(mImeBindingState.mFocusedWindow,
                            null /* statsToken */, imeVisRes.getState(), imeVisRes.getReason());
                }
                // Eligible IME processes use new "setInteractive" protocol.
                mCurClient.mClient.setInteractive(mIsInteractive, mInFullscreenMode);
            } else {
                // Legacy IME processes continue using legacy "setActive" protocol.
                mCurClient.mClient.setActive(mIsInteractive, mInFullscreenMode);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean chooseNewDefaultIMELocked() {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(
                settings.getEnabledInputMethodList());
        if (imi != null) {
            if (DEBUG) {
                Slog.d(TAG, "New default IME was selected: " + imi.getId());
            }
            resetSelectedInputMethodAndSubtypeLocked(imi.getId());
            return true;
        }

        return false;
    }

    @NonNull
    static InputMethodSettings queryInputMethodServicesInternal(Context context,
            @UserIdInt int userId, @NonNull AdditionalSubtypeMap additionalSubtypeMap,
            @DirectBootAwareness int directBootAwareness) {
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);

        final int directBootAwarenessFlags;
        switch (directBootAwareness) {
            case DirectBootAwareness.ANY:
                directBootAwarenessFlags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                        | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
                break;
            case DirectBootAwareness.AUTO:
                directBootAwarenessFlags = PackageManager.MATCH_DIRECT_BOOT_AUTO;
                break;
            default:
                directBootAwarenessFlags = PackageManager.MATCH_DIRECT_BOOT_AUTO;
                Slog.e(TAG, "Unknown directBootAwareness=" + directBootAwareness
                        + ". Falling back to DirectBootAwareness.AUTO");
                break;
        }
        final int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | directBootAwarenessFlags;
        final List<ResolveInfo> services = userAwareContext.getPackageManager().queryIntentServices(
                new Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.ResolveInfoFlags.of(flags));

        // Note: This is a temporary solution for Bug 261723412.  If there is any better solution,
        // we should remove this data dependency.
        final List<String> enabledInputMethodList =
                InputMethodUtils.getEnabledInputMethodIdsForFiltering(context, userId);

        final InputMethodMap methodMap = filterInputMethodServices(
                additionalSubtypeMap, enabledInputMethodList, userAwareContext, services);
        return InputMethodSettings.create(methodMap, userId);
    }

    @NonNull
    static InputMethodMap filterInputMethodServices(
            @NonNull AdditionalSubtypeMap additionalSubtypeMap,
            List<String> enabledInputMethodList, Context userAwareContext,
            List<ResolveInfo> services) {
        final ArrayMap<String, Integer> imiPackageCount = new ArrayMap<>();
        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>(services.size());

        for (int i = 0; i < services.size(); ++i) {
            ResolveInfo ri = services.get(i);
            ServiceInfo si = ri.serviceInfo;
            final String imeId = InputMethodInfo.computeId(ri);
            if (!android.Manifest.permission.BIND_INPUT_METHOD.equals(si.permission)) {
                Slog.w(TAG, "Skipping input method " + imeId
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_INPUT_METHOD);
                continue;
            }

            if (DEBUG) Slog.d(TAG, "Checking " + imeId);

            try {
                final InputMethodInfo imi = new InputMethodInfo(userAwareContext, ri,
                        additionalSubtypeMap.get(imeId));
                if (imi.isVrOnly()) {
                    continue;  // Skip VR-only IME, which isn't supported for now.
                }
                final String packageName = si.packageName;
                // only include IMEs which are from the system, enabled, or below the threshold
                if (si.applicationInfo.isSystemApp() || enabledInputMethodList.contains(imi.getId())
                        || imiPackageCount.getOrDefault(packageName, 0)
                        < InputMethodInfo.MAX_IMES_PER_PACKAGE) {
                    imiPackageCount.put(packageName,
                            1 + imiPackageCount.getOrDefault(packageName, 0));

                    methodMap.put(imi.getId(), imi);
                    if (DEBUG) {
                        Slog.d(TAG, "Found an input method " + imi);
                    }
                } else if (DEBUG) {
                    Slog.d(TAG, "Found an input method, but ignored due threshold: " + imi);
                }
            } catch (Exception e) {
                Slog.wtf(TAG, "Unable to load input method " + imeId, e);
            }
        }
        return InputMethodMap.of(methodMap);
    }

    @GuardedBy("ImfLock.class")
    void postInputMethodSettingUpdatedLocked(boolean resetDefaultEnabledIme) {
        if (DEBUG) {
            Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme
                    + " \n ------ caller=" + Debug.getCallers(10));
        }
        if (!mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }
        mMethodMapUpdateCount++;

        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);

        boolean reenableMinimumNonAuxSystemImes = false;
        // TODO: The following code should find better place to live.
        if (!resetDefaultEnabledIme) {
            boolean enabledImeFound = false;
            boolean enabledNonAuxImeFound = false;
            final List<InputMethodInfo> enabledImes = settings.getEnabledInputMethodList();
            final int numImes = enabledImes.size();
            for (int i = 0; i < numImes; ++i) {
                final InputMethodInfo imi = enabledImes.get(i);
                if (settings.getMethodMap().containsKey(imi.getId())) {
                    enabledImeFound = true;
                    if (!imi.isAuxiliaryIme()) {
                        enabledNonAuxImeFound = true;
                        break;
                    }
                }
            }
            if (!enabledImeFound) {
                if (DEBUG) {
                    Slog.i(TAG, "All the enabled IMEs are gone. Reset default enabled IMEs.");
                }
                resetDefaultEnabledIme = true;
                resetSelectedInputMethodAndSubtypeLocked("");
            } else if (!enabledNonAuxImeFound) {
                if (DEBUG) {
                    Slog.i(TAG, "All the enabled non-Aux IMEs are gone. Do partial reset.");
                }
                reenableMinimumNonAuxSystemImes = true;
            }
        }

        if (resetDefaultEnabledIme || reenableMinimumNonAuxSystemImes) {
            final ArrayList<InputMethodInfo> defaultEnabledIme =
                    InputMethodInfoUtils.getDefaultEnabledImes(mContext, settings.getMethodList(),
                            reenableMinimumNonAuxSystemImes);
            final int numImes = defaultEnabledIme.size();
            for (int i = 0; i < numImes; ++i) {
                final InputMethodInfo imi = defaultEnabledIme.get(i);
                if (DEBUG) {
                    Slog.d(TAG, "--- enable ime = " + imi);
                }
                setInputMethodEnabledLocked(imi.getId(), true);
            }
        }

        final String defaultImiId = settings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(defaultImiId)) {
            if (!settings.getMethodMap().containsKey(defaultImiId)) {
                Slog.w(TAG, "Default IME is uninstalled. Choose new default IME.");
                if (chooseNewDefaultIMELocked()) {
                    updateInputMethodsFromSettingsLocked(true);
                }
            } else {
                // Double check that the default IME is certainly enabled.
                setInputMethodEnabledLocked(defaultImiId, true);
            }
        }

        updateDefaultVoiceImeIfNeededLocked();

        // TODO: Instantiate mSwitchingController for each user.
        if (settings.getUserId() == mSwitchingController.getUserId()) {
            mSwitchingController.resetCircularListLocked(settings.getMethodMap());
        } else {
            mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(
                    mContext, settings.getMethodMap(), mCurrentUserId);
        }
        // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
        if (settings.getUserId() == mHardwareKeyboardShortcutController.getUserId()) {
            mHardwareKeyboardShortcutController.reset(settings.getMethodMap());
        } else {
            mHardwareKeyboardShortcutController = new HardwareKeyboardShortcutController(
                    settings.getMethodMap(), settings.getUserId());
        }

        sendOnNavButtonFlagsChangedLocked();

        // Notify InputMethodListListeners of the new installed InputMethods.
        final List<InputMethodInfo> inputMethodList = settings.getMethodList();
        mHandler.obtainMessage(MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED,
                settings.getUserId(), 0 /* unused */, inputMethodList).sendToTarget();
    }

    @GuardedBy("ImfLock.class")
    void sendOnNavButtonFlagsChangedLocked() {
        final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
        final var bindingController = userData.mBindingController;
        final IInputMethodInvoker curMethod = bindingController.getCurMethod();
        if (curMethod == null) {
            // No need to send the data if the IME is not yet bound.
            return;
        }
        curMethod.onNavButtonFlagsChanged(getInputMethodNavButtonFlagsLocked());
    }

    @GuardedBy("ImfLock.class")
    private void updateDefaultVoiceImeIfNeededLocked() {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        final String systemSpeechRecognizer =
                mContext.getString(com.android.internal.R.string.config_systemSpeechRecognizer);
        final String currentDefaultVoiceImeId = settings.getDefaultVoiceInputMethod();
        final InputMethodInfo newSystemVoiceIme = InputMethodInfoUtils.chooseSystemVoiceIme(
                settings.getMethodMap(), systemSpeechRecognizer, currentDefaultVoiceImeId);
        if (newSystemVoiceIme == null) {
            if (DEBUG) {
                Slog.i(TAG, "Found no valid default Voice IME. If the user is still locked,"
                        + " this may be expected.");
            }
            // Clear DEFAULT_VOICE_INPUT_METHOD when necessary.  Note that InputMethodSettings
            // does not update the actual Secure Settings until the user is unlocked.
            if (!TextUtils.isEmpty(currentDefaultVoiceImeId)) {
                settings.putDefaultVoiceInputMethod("");
                // We don't support disabling the voice ime when a package is removed from the
                // config.
            }
            return;
        }
        if (TextUtils.equals(currentDefaultVoiceImeId, newSystemVoiceIme.getId())) {
            return;
        }
        if (DEBUG) {
            Slog.i(TAG, "Enabling the default Voice IME:" + newSystemVoiceIme);
        }
        setInputMethodEnabledLocked(newSystemVoiceIme.getId(), true);
        settings.putDefaultVoiceInputMethod(newSystemVoiceIme.getId());
    }

    // ----------------------------------------------------------------------

    /**
     * Enable or disable the given IME by updating {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     *
     * @param id      ID of the IME is to be manipulated. It is OK to pass IME ID that is currently
     *                not recognized by the system
     * @param enabled {@code true} if {@code id} needs to be enabled
     * @return {@code true} if the IME was previously enabled
     */
    @GuardedBy("ImfLock.class")
    private boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        if (enabled) {
            final String enabledImeIdsStr = settings.getEnabledInputMethodsStr();
            final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                    enabledImeIdsStr, id);
            if (TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr)) {
                // We are enabling this input method, but it is already enabled.
                // Nothing to do. The previous state was enabled.
                return true;
            }
            settings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
            // Previous state was disabled.
            return false;
        } else {
            final List<Pair<String, ArrayList<String>>> enabledInputMethodsList = settings
                    .getEnabledInputMethodsAndSubtypeList();
            StringBuilder builder = new StringBuilder();
            if (settings.buildAndPutEnabledInputMethodsStrRemovingId(
                    builder, enabledInputMethodsList, id)) {
                if (mDeviceIdToShowIme == DEVICE_ID_DEFAULT) {
                    // Disabled input method is currently selected, switch to another one.
                    final String selId = settings.getSelectedInputMethod();
                    if (id.equals(selId) && !chooseNewDefaultIMELocked()) {
                        Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                        resetSelectedInputMethodAndSubtypeLocked("");
                    }
                } else if (id.equals(settings.getSelectedDefaultDeviceInputMethod())) {
                    // Disabled default device IME while using a virtual device one, choose a
                    // new default one but only update the settings.
                    InputMethodInfo newDefaultIme =
                            InputMethodInfoUtils.getMostApplicableDefaultIME(
                                    settings.getEnabledInputMethodList());
                    settings.putSelectedDefaultDeviceInputMethod(
                            newDefaultIme == null ? null : newDefaultIme.getId());
                }
                // Previous state was enabled.
                return true;
            } else {
                // We are disabling the input method but it is already disabled.
                // Nothing to do.  The previous state was disabled.
                return false;
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeId,
            boolean setSubtypeOnly) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        settings.saveCurrentInputMethodAndSubtypeToHistory(getSelectedMethodIdLocked(),
                mCurrentSubtype);

        // Set Subtype here
        if (imi == null || subtypeId < 0) {
            settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
            mCurrentSubtype = null;
        } else {
            if (subtypeId < imi.getSubtypeCount()) {
                InputMethodSubtype subtype = imi.getSubtypeAt(subtypeId);
                settings.putSelectedSubtype(subtype.hashCode());
                mCurrentSubtype = subtype;
            } else {
                settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
                // If the subtype is not specified, choose the most applicable one
                mCurrentSubtype = getCurrentInputMethodSubtypeLocked();
            }
        }
        notifyInputMethodSubtypeChangedLocked(settings.getUserId(), imi, mCurrentSubtype);

        if (!setSubtypeOnly) {
            // Set InputMethod here
            settings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }
    }

    @GuardedBy("ImfLock.class")
    private void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme) {
        mDeviceIdToShowIme = DEVICE_ID_DEFAULT;
        mDisplayIdToShowIme = INVALID_DISPLAY;

        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        settings.putSelectedDefaultDeviceInputMethod(null);

        InputMethodInfo imi = settings.getMethodMap().get(newDefaultIme);
        int lastSubtypeId = NOT_A_SUBTYPE_ID;
        // newDefaultIme is empty when there is no candidate for the selected IME.
        if (imi != null && !TextUtils.isEmpty(newDefaultIme)) {
            String subtypeHashCode = settings.getLastSubtypeForInputMethod(newDefaultIme);
            if (subtypeHashCode != null) {
                try {
                    lastSubtypeId = SubtypeUtils.getSubtypeIdFromHashCode(imi,
                            Integer.parseInt(subtypeHashCode));
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
                }
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeId, false);
    }

    /**
     * Gets the current subtype of this input method.
     *
     * @param userId User ID to be queried about
     * @return the current {@link InputMethodSubtype} for the specified user
     */
    @Nullable
    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            if (mCurrentUserId == userId) {
                return getCurrentInputMethodSubtypeLocked();
            }

            return InputMethodSettingsRepository.get(userId)
                    .getCurrentInputMethodSubtypeForNonCurrentUsers();
        }
    }

    /**
     * Returns the current {@link InputMethodSubtype} for the current user.
     *
     * <p>CAVEATS: You must also update
     * {@link InputMethodSettings#getCurrentInputMethodSubtypeForNonCurrentUsers()}
     * when you update the algorithm of this method.</p>
     *
     * <p>TODO: Address code duplication between this and
     * {@link InputMethodSettings#getCurrentInputMethodSubtypeForNonCurrentUsers()}.</p>
     */
    @GuardedBy("ImfLock.class")
    InputMethodSubtype getCurrentInputMethodSubtypeLocked() {
        String selectedMethodId = getSelectedMethodIdLocked();
        if (selectedMethodId == null) {
            return null;
        }
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
        final boolean subtypeIsSelected = settings.isSubtypeSelected();
        final InputMethodInfo imi = settings.getMethodMap().get(selectedMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }
        if (!subtypeIsSelected || mCurrentSubtype == null
                || !SubtypeUtils.isValidSubtypeId(imi, mCurrentSubtype.hashCode())) {
            int subtypeId = settings.getSelectedInputMethodSubtypeId(selectedMethodId);
            if (subtypeId == NOT_A_SUBTYPE_ID) {
                // If there are no selected subtypes, the framework will try to find
                // the most applicable subtype from explicitly or implicitly enabled
                // subtypes.
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                        settings.getEnabledInputMethodSubtypeList(imi, true);
                // If there is only one explicitly or implicitly enabled subtype,
                // just returns it.
                if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
                    mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                } else if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                    final String locale = SystemLocaleWrapper.get(settings.getUserId())
                            .get(0).toString();
                    mCurrentSubtype = SubtypeUtils.findLastResortApplicableSubtype(
                            explicitlyOrImplicitlyEnabledSubtypes,
                            SubtypeUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                    if (mCurrentSubtype == null) {
                        mCurrentSubtype = SubtypeUtils.findLastResortApplicableSubtype(
                                explicitlyOrImplicitlyEnabledSubtypes, null, locale, true);
                    }
                }
            } else {
                mCurrentSubtype = SubtypeUtils.getSubtypes(imi).get(subtypeId);
            }
        }
        return mCurrentSubtype;
    }

    /**
     * Returns the default {@link InputMethodInfo} for the specific userId.
     *
     * @param userId user ID to query
     */
    @GuardedBy("ImfLock.class")
    private InputMethodInfo queryDefaultInputMethodForUserIdLocked(@UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        return settings.getMethodMap().get(settings.getSelectedInputMethod());
    }

    @GuardedBy("ImfLock.class")
    private boolean switchToInputMethodLocked(String imeId, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (userId == mCurrentUserId) {
            if (!settings.getMethodMap().containsKey(imeId)
                    || !settings.getEnabledInputMethodList()
                    .contains(settings.getMethodMap().get(imeId))) {
                return false; // IME is not found or not enabled.
            }
            setInputMethodLocked(imeId, NOT_A_SUBTYPE_ID);
            return true;
        }
        if (!settings.getMethodMap().containsKey(imeId)
                || !settings.getEnabledInputMethodList().contains(
                settings.getMethodMap().get(imeId))) {
            return false; // IME is not found or not enabled.
        }
        settings.putSelectedInputMethod(imeId);
        settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
        return true;
    }

    /**
     * Filter the access to the input method by rules of the package visibility. Return {@code true}
     * if the given input method is the currently selected one or visible to the caller.
     *
     * @param targetPkgName the package name of input method to check
     * @param callingUid    the caller that is going to access the input method
     * @param userId        the user ID where the input method resides
     * @param settings      the input method settings under the given user ID
     * @return {@code true} if caller is able to access the input method
     */
    private boolean canCallerAccessInputMethod(@NonNull String targetPkgName, int callingUid,
            @UserIdInt int userId, @NonNull InputMethodSettings settings) {
        final String methodId = settings.getSelectedInputMethod();
        final ComponentName selectedInputMethod = methodId != null
                ? InputMethodUtils.convertIdToComponentName(methodId) : null;
        if (selectedInputMethod != null
                && selectedInputMethod.getPackageName().equals(targetPkgName)) {
            return true;
        }
        final boolean canAccess = !mPackageManagerInternal.filterAppAccess(
                targetPkgName, callingUid, userId);
        if (DEBUG && !canAccess) {
            Slog.d(TAG, "Input method " + targetPkgName
                    + " is not visible to the caller " + callingUid);
        }
        return canAccess;
    }

    @GuardedBy("ImfLock.class")
    private void switchKeyboardLayoutLocked(int direction) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);

        final InputMethodInfo currentImi = settings.getMethodMap().get(getSelectedMethodIdLocked());
        if (currentImi == null) {
            return;
        }
        final InputMethodSubtypeHandle currentSubtypeHandle =
                InputMethodSubtypeHandle.of(currentImi, mCurrentSubtype);
        final InputMethodSubtypeHandle nextSubtypeHandle =
                mHardwareKeyboardShortcutController.onSubtypeSwitch(currentSubtypeHandle,
                        direction > 0);
        if (nextSubtypeHandle == null) {
            return;
        }
        final InputMethodInfo nextImi = settings.getMethodMap().get(nextSubtypeHandle.getImeId());
        if (nextImi == null) {
            return;
        }

        final int subtypeCount = nextImi.getSubtypeCount();
        if (subtypeCount == 0) {
            if (nextSubtypeHandle.equals(InputMethodSubtypeHandle.of(nextImi, null))) {
                setInputMethodLocked(nextImi.getId(), NOT_A_SUBTYPE_ID);
            }
            return;
        }

        for (int i = 0; i < subtypeCount; ++i) {
            if (nextSubtypeHandle.equals(
                    InputMethodSubtypeHandle.of(nextImi, nextImi.getSubtypeAt(i)))) {
                setInputMethodLocked(nextImi.getId(), i);
                return;
            }
        }
    }

    private void publishLocalService() {
        LocalServices.addService(InputMethodManagerInternal.class, new LocalServiceImpl());
    }

    private final class LocalServiceImpl extends InputMethodManagerInternal {

        @Override
        public void setInteractive(boolean interactive) {
            // Do everything in handler so as not to block the caller.
            mHandler.obtainMessage(MSG_SET_INTERACTIVE, interactive ? 1 : 0, 0).sendToTarget();
        }

        @Override
        public void hideAllInputMethods(@SoftInputShowHideReason int reason,
                int originatingDisplayId) {
            mHandler.removeMessages(MSG_HIDE_ALL_INPUT_METHODS);
            mHandler.obtainMessage(MSG_HIDE_ALL_INPUT_METHODS, reason).sendToTarget();
        }

        @Override
        public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return getInputMethodListLocked(userId, DirectBootAwareness.AUTO,
                        Process.SYSTEM_UID);
            }
        }

        @Override
        public List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return getEnabledInputMethodListLocked(userId, Process.SYSTEM_UID);
            }
        }

        @Override
        public void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
                InlineSuggestionsRequestInfo requestInfo, InlineSuggestionsRequestCallback cb) {
            // Get the device global touch exploration state before lock to avoid deadlock.
            final boolean touchExplorationEnabled = AccessibilityManagerInternal.get()
                    .isTouchExplorationEnabled(userId);

            synchronized (ImfLock.class) {
                mAutofillController.onCreateInlineSuggestionsRequest(userId, requestInfo, cb,
                        touchExplorationEnabled);
            }
        }

        @Override
        public boolean switchToInputMethod(String imeId, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return switchToInputMethodLocked(imeId, userId);
            }
        }

        @Override
        public boolean setInputMethodEnabled(String imeId, boolean enabled, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.getMethodMap().containsKey(imeId)) {
                    return false; // IME is not found.
                }
                if (userId == mCurrentUserId) {
                    setInputMethodEnabledLocked(imeId, enabled);
                    return true;
                }
                if (enabled) {
                    final String enabledImeIdsStr = settings.getEnabledInputMethodsStr();
                    final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                            enabledImeIdsStr, imeId);
                    if (!TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr)) {
                        settings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
                    }
                } else {
                    settings.buildAndPutEnabledInputMethodsStrRemovingId(
                            new StringBuilder(),
                            settings.getEnabledInputMethodsAndSubtypeList(), imeId);
                }
                return true;
            }
        }

        @Override
        public void setVirtualDeviceInputMethodForAllUsers(int deviceId, @Nullable String imeId) {
            Preconditions.checkArgument(deviceId != DEVICE_ID_DEFAULT,
                    TextUtils.formatSimple("DeviceId %d is not a virtual device id.", deviceId));
            synchronized (ImfLock.class) {
                if (imeId == null) {
                    mVirtualDeviceMethodMap.remove(deviceId);
                } else if (mVirtualDeviceMethodMap.contains(deviceId)) {
                    throw new IllegalArgumentException("Virtual device " + deviceId
                            + " already has a custom input method component");
                } else {
                    mVirtualDeviceMethodMap.put(deviceId, imeId);
                }
            }
        }

        @Override
        public void registerInputMethodListListener(InputMethodListListener listener) {
            mInputMethodListListeners.addIfAbsent(listener);
        }

        @Override
        public boolean transferTouchFocusToImeWindow(@NonNull IBinder sourceInputToken,
                int displayId, @UserIdInt int userId) {
            //TODO(b/150843766): Check if Input Token is valid.
            final IBinder curHostInputToken;
            synchronized (ImfLock.class) {
                if (displayId != getCurTokenDisplayIdLocked()) {
                    return false;
                }
                curHostInputToken = mAutofillController.getCurHostInputToken();
                if (curHostInputToken == null) {
                    return false;
                }
            }
            return mInputManagerInternal.transferTouchGesture(sourceInputToken, curHostInputToken);
        }

        @Override
        public void reportImeControl(@Nullable IBinder windowToken) {
            synchronized (ImfLock.class) {
                if (mImeBindingState.mFocusedWindow != windowToken) {
                    // A perceptible value was set for the focused window, but it is no longer in
                    // control, so we reset the perceptible for the window passed as argument.
                    // TODO(b/314149476): Investigate whether this logic is still relevant, if not
                    //     then consider removing using concurrent_input_methods feature flag.
                    mFocusedWindowPerceptible.put(windowToken, true);
                }
            }
        }

        @Override
        public void onImeParentChanged(int displayId) {
            synchronized (ImfLock.class) {
                // Hide the IME method menu only when the IME surface parent is changed by the
                // input target changed, in case seeing the dialog dismiss flickering during
                // the next focused window starting the input connection.
                if (mLastImeTargetWindow != mImeBindingState.mFocusedWindow) {
                    mMenuController.hideInputMethodMenuLocked();
                }
            }
        }

        @Override
        public void removeImeSurface(int displayId) {
            mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
        }

        @Override
        public void updateImeWindowStatus(boolean disableImeIcon, int displayId) {
            mHandler.obtainMessage(MSG_UPDATE_IME_WINDOW_STATUS, disableImeIcon ? 1 : 0, 0)
                    .sendToTarget();
        }

        @Override
        public void onSessionForAccessibilityCreated(int accessibilityConnectionId,
                IAccessibilityInputMethodSession session, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
                final var bindingController = userData.mBindingController;
                // TODO(b/305829876): Implement user ID verification
                if (mCurClient != null) {
                    clearClientSessionForAccessibilityLocked(mCurClient, accessibilityConnectionId);
                    mCurClient.mAccessibilitySessions.put(
                            accessibilityConnectionId,
                            new AccessibilitySessionState(mCurClient,
                                    accessibilityConnectionId,
                                    session));

                    attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY,
                            true);

                    final SessionState sessionState = mCurClient.mCurSession;
                    final IInputMethodSession imeSession = sessionState == null
                            ? null : sessionState.mSession;
                    final SparseArray<IAccessibilityInputMethodSession>
                            accessibilityInputMethodSessions =
                            createAccessibilityInputMethodSessions(
                                    mCurClient.mAccessibilitySessions);
                    final InputBindResult res = new InputBindResult(
                            InputBindResult.ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION,
                            imeSession, accessibilityInputMethodSessions, /* channel= */ null,
                            bindingController.getCurId(),
                            bindingController.getSequenceNumber(),
                            /* isInputMethodSuppressingSpellChecker= */ false);
                    mCurClient.mClient.onBindAccessibilityService(res, accessibilityConnectionId);
                }
            }
        }

        @Override
        public void unbindAccessibilityFromCurrentClient(int accessibilityConnectionId,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
                final var bindingController = userData.mBindingController;
                // TODO(b/305829876): Implement user ID verification
                if (mCurClient != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "unbindAccessibilityFromCurrentClientLocked: client="
                                + mCurClient.mClient.asBinder());
                    }
                    // A11yManagerService unbinds the disabled accessibility service. We don't need
                    // to do it here.
                    mCurClient.mClient.onUnbindAccessibilityService(
                            bindingController.getSequenceNumber(),
                            accessibilityConnectionId);
                }
                // We only have sessions when we bound to an input method. Remove this session
                // from all clients.
                if (getCurMethodLocked() != null) {
                    // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
                    @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession =
                            c -> clearClientSessionForAccessibilityLocked(c,
                                    accessibilityConnectionId);
                    mClientController.forAllClients(clearClientSession);

                    AccessibilitySessionState session = mEnabledAccessibilitySessions.get(
                            accessibilityConnectionId);
                    if (session != null) {
                        finishSessionForAccessibilityLocked(session);
                        mEnabledAccessibilitySessions.remove(accessibilityConnectionId);
                    }
                }
            }
        }

        @Override
        public void maybeFinishStylusHandwriting() {
            mHandler.removeMessages(MSG_FINISH_HANDWRITING);
            mHandler.obtainMessage(MSG_FINISH_HANDWRITING).sendToTarget();
        }

        @Override
        public void onSwitchKeyboardLayoutShortcut(int direction, int displayId,
                IBinder targetWindowToken) {
            synchronized (ImfLock.class) {
                switchKeyboardLayoutLocked(direction);
            }
        }
    }

    @BinderThread
    private IInputContentUriToken createInputContentUriToken(@Nullable IBinder token,
            @Nullable Uri contentUri, @Nullable String packageName) {
        if (token == null) {
            throw new NullPointerException("token");
        }
        if (packageName == null) {
            throw new NullPointerException("packageName");
        }
        if (contentUri == null) {
            throw new NullPointerException("contentUri");
        }
        final String contentUriScheme = contentUri.getScheme();
        if (!"content".equals(contentUriScheme)) {
            throw new InvalidParameterException("contentUri must have content scheme");
        }

        synchronized (ImfLock.class) {
            final int uid = Binder.getCallingUid();
            if (getSelectedMethodIdLocked() == null) {
                return null;
            }
            if (getCurTokenLocked() != token) {
                Slog.e(TAG, "Ignoring createInputContentUriToken mCurToken=" + getCurTokenLocked()
                        + " token=" + token);
                return null;
            }
            // We cannot simply distinguish a bad IME that reports an arbitrary package name from
            // an unfortunate IME whose internal state is already obsolete due to the asynchronous
            // nature of our system.  Let's compare it with our internal record.
            final var curPackageName = mCurEditorInfo != null
                    ? mCurEditorInfo.packageName : null;
            if (!TextUtils.equals(curPackageName, packageName)) {
                Slog.e(TAG, "Ignoring createInputContentUriToken mCurEditorInfo.packageName="
                        + curPackageName + " packageName=" + packageName);
                return null;
            }
            // This user ID can never bee spoofed.
            final int imeUserId = UserHandle.getUserId(uid);
            // This user ID can never bee spoofed.
            final int appUserId = UserHandle.getUserId(mCurClient.mUid);
            // This user ID may be invalid if "contentUri" embedded an invalid user ID.
            final int contentUriOwnerUserId = ContentProvider.getUserIdFromUri(contentUri,
                    imeUserId);
            final Uri contentUriWithoutUserId = ContentProvider.getUriWithoutUserId(contentUri);
            // Note: InputContentUriTokenHandler.take() checks whether the IME (specified by "uid")
            // actually has the right to grant a read permission for "contentUriWithoutUserId" that
            // is claimed to belong to "contentUriOwnerUserId".  For example, specifying random
            // content URI and/or contentUriOwnerUserId just results in a SecurityException thrown
            // from InputContentUriTokenHandler.take() and can never be allowed beyond what is
            // actually allowed to "uid", which is guaranteed to be the IME's one.
            return new InputContentUriTokenHandler(contentUriWithoutUserId, uid,
                    packageName, contentUriOwnerUserId, appUserId);
        }
    }

    @BinderThread
    private void reportFullscreenMode(@NonNull IBinder token, boolean fullscreen) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            if (mCurClient != null && mCurClient.mClient != null) {
                mInFullscreenMode = fullscreen;
                mCurClient.mClient.reportFullscreenMode(fullscreen);
            }
        }
    }

    private final PriorityDump.PriorityDumper mPriorityDumper = new PriorityDump.PriorityDumper() {
        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpCritical(FileDescriptor fd, PrintWriter pw, String[] args,
                boolean asProto) {
            if (asProto) {
                dumpAsProtoNoCheck(fd);
            } else {
                dumpAsStringNoCheck(fd, pw, args, true /* isCritical */);
            }
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpHigh(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            dumpNormal(fd, pw, args, asProto);
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dumpNormal(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            if (asProto) {
                dumpAsProtoNoCheck(fd);
            } else {
                dumpAsStringNoCheck(fd, pw, args, false /* isCritical */);
            }
        }

        /**
         * {@inheritDoc}
         */
        @BinderThread
        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args, boolean asProto) {
            dumpNormal(fd, pw, args, asProto);
        }

        @BinderThread
        private void dumpAsProtoNoCheck(FileDescriptor fd) {
            final ProtoOutputStream proto = new ProtoOutputStream(fd);
            // Dump in the format of an ImeTracing trace with a single entry.
            final long magicNumber =
                    ((long) InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_H << 32)
                            | InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER_L;
            final long timeOffsetNs = TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis())
                    - SystemClock.elapsedRealtimeNanos();
            proto.write(InputMethodManagerServiceTraceFileProto.MAGIC_NUMBER,
                    magicNumber);
            proto.write(InputMethodManagerServiceTraceFileProto.REAL_TO_ELAPSED_TIME_OFFSET_NANOS,
                    timeOffsetNs);
            final long token = proto.start(InputMethodManagerServiceTraceFileProto.ENTRY);
            proto.write(InputMethodManagerServiceTraceProto.ELAPSED_REALTIME_NANOS,
                    SystemClock.elapsedRealtimeNanos());
            proto.write(InputMethodManagerServiceTraceProto.WHERE,
                    "InputMethodManagerService.mPriorityDumper#dumpAsProtoNoCheck");
            dumpDebug(proto, InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
            proto.end(token);
            proto.flush();
        }
    };

    @BinderThread
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        PriorityDump.dump(mPriorityDumper, fd, pw, args);
    }

    @BinderThread
    private void dumpAsStringNoCheck(FileDescriptor fd, PrintWriter pw, String[] args,
            boolean isCritical) {
        IInputMethodInvoker method;
        ClientState client;
        ClientState focusedWindowClient;

        final Printer p = new PrintWriterPrinter(pw);

        synchronized (ImfLock.class) {
            final InputMethodSettings settings = InputMethodSettingsRepository.get(mCurrentUserId);
            p.println("Current Input Method Manager state:");
            final List<InputMethodInfo> methodList = settings.getMethodList();
            int numImes = methodList.size();
            p.println("  Input Methods: mMethodMapUpdateCount=" + mMethodMapUpdateCount);
            for (int i = 0; i < numImes; i++) {
                InputMethodInfo info = methodList.get(i);
                p.println("  InputMethod #" + i + ":");
                info.dump(p, "    ");
            }
            // Dump ClientController#mClients
            p.println("  ClientStates:");
            // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
            @SuppressWarnings("GuardedBy") Consumer<ClientState> clientControllerDump = c -> {
                p.println("  " + c + ":");
                p.println("    client=" + c.mClient);
                p.println("    fallbackInputConnection="
                        + c.mFallbackInputConnection);
                p.println("    sessionRequested="
                        + c.mSessionRequested);
                p.println("    sessionRequestedForAccessibility="
                                + c.mSessionRequestedForAccessibility);
                p.println("    curSession=" + c.mCurSession);
                p.println("    selfReportedDisplayId=" + c.mSelfReportedDisplayId);
                p.println("    uid=" + c.mUid);
                p.println("    pid=" + c.mPid);
            };
            mClientController.forAllClients(clientControllerDump);
            final var userData = mUserDataRepository.getOrCreate(mCurrentUserId);
            final var bindingController = userData.mBindingController;
            p.println("  mCurrentUserId=" + mCurrentUserId);
            p.println("  mCurMethodId=" + getSelectedMethodIdLocked());
            client = mCurClient;
            p.println("  mCurClient=" + client + " mCurSeq="
                    + bindingController.getSequenceNumber());
            p.println("  mFocusedWindowPerceptible=" + mFocusedWindowPerceptible);
            mImeBindingState.dump(/* prefix= */ "  ", p);

            p.println("  mCurId=" + bindingController.getCurId()
                    + " mHaveConnection=" + bindingController.hasMainConnection()
                    + " mBoundToMethod=" + mBoundToMethod + " mVisibleBound="
                    + bindingController.isVisibleBound());

            p.println("  mUserDataRepository=");
            // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
            @SuppressWarnings("GuardedBy") Consumer<UserDataRepository.UserData> userDataDump =
                    u -> {
                        p.println("    mUserId=" + u.mUserId);
                        p.println("      hasMainConnection="
                                + u.mBindingController.hasMainConnection());
                        p.println("      isVisibleBound=" + u.mBindingController.isVisibleBound());
                    };
            mUserDataRepository.forAllUserData(userDataDump);

            p.println("  mCurToken=" + getCurTokenLocked());
            p.println("  mCurTokenDisplayId=" + getCurTokenDisplayIdLocked());
            p.println("  mCurHostInputToken=" + mAutofillController.getCurHostInputToken());
            p.println("  mCurIntent=" + bindingController.getCurIntent());
            method = getCurMethodLocked();
            p.println("  mCurMethod=" + getCurMethodLocked());
            p.println("  mEnabledSession=" + mEnabledSession);
            mVisibilityStateComputer.dump(pw, "  ");
            p.println("  mInFullscreenMode=" + mInFullscreenMode);
            p.println("  mSystemReady=" + mSystemReady + " mInteractive=" + mIsInteractive);
            p.println("  mExperimentalConcurrentMultiUserModeEnabled="
                    + mExperimentalConcurrentMultiUserModeEnabled);
            p.println("  ENABLE_HIDE_IME_CAPTION_BAR="
                    + InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR);
            p.println("  mSettingsObserver=" + mSettingsObserver);
            p.println("  mStylusIds=" + (mStylusIds != null
                    ? Arrays.toString(mStylusIds.toArray()) : ""));
            p.println("  mSwitchingController:");
            mSwitchingController.dump(p);

            p.println("  mStartInputHistory:");
            mStartInputHistory.dump(pw, "    ");

            p.println("  mSoftInputShowHideHistory:");
            mSoftInputShowHideHistory.dump(pw, "    ");

            p.println("  mImeTrackerService#History:");
            mImeTrackerService.dump(pw, "    ");
        }

        // Exit here for critical dump, as remaining sections require IPCs to other processes.
        if (isCritical) {
            return;
        }

        p.println(" ");
        if (client != null) {
            pw.flush();
            try {
                TransferPipe.dumpAsync(client.mClient.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                p.println("Failed to dump input method client: " + e);
            }
        } else {
            p.println("No input method client.");
        }

        if (mImeBindingState.mFocusedWindowClient != null
                && client != mImeBindingState.mFocusedWindowClient) {
            p.println(" ");
            p.println("Warning: Current input method client doesn't match the last focused. "
                    + "window.");
            p.println("Dumping input method client in the last focused window just in case.");
            p.println(" ");
            pw.flush();
            try {
                TransferPipe.dumpAsync(
                        mImeBindingState.mFocusedWindowClient.mClient.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                p.println("Failed to dump input method client in focused window: " + e);
            }
        }

        p.println(" ");
        if (method != null) {
            pw.flush();
            try {
                TransferPipe.dumpAsync(method.asBinder(), fd, args);
            } catch (IOException | RemoteException e) {
                p.println("Failed to dump input method service: " + e);
            }
        } else {
            p.println("No input method service.");
        }
    }

    @BinderThread
    @Override
    public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args, @Nullable ShellCallback callback,
            @NonNull ResultReceiver resultReceiver, @NonNull Binder self) {
        final int callingUid = Binder.getCallingUid();
        // Reject any incoming calls from non-shell users, including ones from the system user.
        if (callingUid != Process.ROOT_UID && callingUid != Process.SHELL_UID) {
            // Note that Binder#onTransact() will automatically close "in", "out", and "err" when
            // returned from this method, hence there is no need to close those FDs.
            // "resultReceiver" is the only thing that needs to be taken care of here.
            if (resultReceiver != null) {
                resultReceiver.send(ShellCommandResult.FAILURE, null);
            }
            final String errorMsg = "InputMethodManagerService does not support shell commands from"
                    + " non-shell users. callingUid=" + callingUid
                    + " args=" + Arrays.toString(args);
            if (Process.isCoreUid(callingUid)) {
                // Let's not crash the calling process if the caller is one of core components.
                Slog.e(TAG, errorMsg);
                return;
            }
            throw new SecurityException(errorMsg);
        }
        new ShellCommandImpl(this).exec(
                self, in, out, err, args, callback, resultReceiver);
    }

    private static final class ShellCommandImpl extends ShellCommand {
        @NonNull
        final InputMethodManagerService mService;

        ShellCommandImpl(InputMethodManagerService service) {
            mService = service;
        }

        @BinderThread
        @ShellCommandResult
        @Override
        public int onCommand(@Nullable String cmd) {
            final long identity = Binder.clearCallingIdentity();
            try {
                return onCommandWithSystemIdentity(cmd);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @BinderThread
        @ShellCommandResult
        private int onCommandWithSystemIdentity(@Nullable String cmd) {
            switch (TextUtils.emptyIfNull(cmd)) {
                case "tracing":
                    return mService.handleShellCommandTraceInputMethod(this);
                case "ime": {  // For "adb shell ime <command>".
                    final String imeCommand = TextUtils.emptyIfNull(getNextArg());
                    switch (imeCommand) {
                        case "":
                        case "-h":
                        case "help":
                            return onImeCommandHelp();
                        case "list":
                            return mService.handleShellCommandListInputMethods(this);
                        case "enable":
                            return mService.handleShellCommandEnableDisableInputMethod(this, true);
                        case "disable":
                            return mService.handleShellCommandEnableDisableInputMethod(this, false);
                        case "set":
                            return mService.handleShellCommandSetInputMethod(this);
                        case "reset":
                            return mService.handleShellCommandResetInputMethod(this);
                        case "tracing":  // TODO(b/180765389): Unsupport "adb shell ime tracing"
                            return mService.handleShellCommandTraceInputMethod(this);
                        default:
                            getOutPrintWriter().println("Unknown command: " + imeCommand);
                            return ShellCommandResult.FAILURE;
                    }
                }
                default:
                    return handleDefaultCommands(cmd);
            }
        }

        @BinderThread
        @Override
        public void onHelp() {
            try (PrintWriter pw = getOutPrintWriter()) {
                pw.println("InputMethodManagerService commands:");
                pw.println("  help");
                pw.println("    Prints this help text.");
                pw.println("  dump [options]");
                pw.println("    Synonym of dumpsys.");
                pw.println("  ime <command> [options]");
                pw.println("    Manipulate IMEs.  Run \"ime help\" for details.");
                pw.println("  tracing <command>");
                pw.println("    start: Start tracing.");
                pw.println("    stop : Stop tracing.");
                pw.println("    help : Show help.");
            }
        }

        @BinderThread
        @ShellCommandResult
        private int onImeCommandHelp() {
            try (IndentingPrintWriter pw =
                         new IndentingPrintWriter(getOutPrintWriter(), "  ", 100)) {
                pw.println("ime <command>:");
                pw.increaseIndent();

                pw.println("list [-a] [-s]");
                pw.increaseIndent();
                pw.println("prints all enabled input methods.");
                pw.increaseIndent();
                pw.println("-a: see all input methods");
                pw.println("-s: only a single summary line of each");
                pw.decreaseIndent();
                pw.decreaseIndent();

                pw.println("enable [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("allows the given input method ID to be used.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to enable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();

                pw.println("disable [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("disallows the given input method ID to be used.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to disable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();

                pw.println("set [--user <USER_ID>] <ID>");
                pw.increaseIndent();
                pw.println("switches to the given input method ID.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to enable.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();
                pw.decreaseIndent();

                pw.println("reset [--user <USER_ID>]");
                pw.increaseIndent();
                pw.println("reset currently selected/enabled IMEs to the default ones as if "
                        + "the device is initially booted with the current locale.");
                pw.increaseIndent();
                pw.print("--user <USER_ID>: Specify which user to reset.");
                pw.println(" Assumes the current user if not specified.");
                pw.decreaseIndent();

                pw.decreaseIndent();

                pw.decreaseIndent();
            }
            return ShellCommandResult.SUCCESS;
        }
    }

    // ----------------------------------------------------------------------
    // Shell command handlers:

    /**
     * Handles {@code adb shell ime list}.
     *
     * @param shellCommand {@link ShellCommand} object that is handling this command
     * @return exit code of the command
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandListInputMethods(@NonNull ShellCommand shellCommand) {
        boolean all = false;
        boolean brief = false;
        int userIdToBeResolved = UserHandle.USER_CURRENT;
        while (true) {
            final String nextOption = shellCommand.getNextOption();
            if (nextOption == null) {
                break;
            }
            switch (nextOption) {
                case "-a":
                    all = true;
                    break;
                case "-s":
                    brief = true;
                    break;
                case "-u":
                case "--user":
                    userIdToBeResolved = UserHandle.parseUserArg(shellCommand.getNextArgRequired());
                    break;
            }
        }
        synchronized (ImfLock.class) {
            final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                    mCurrentUserId, shellCommand.getErrPrintWriter());
            try (PrintWriter pr = shellCommand.getOutPrintWriter()) {
                for (int userId : userIds) {
                    final List<InputMethodInfo> methods = all
                            ? getInputMethodListLocked(
                                    userId, DirectBootAwareness.AUTO, Process.SHELL_UID)
                            : getEnabledInputMethodListLocked(userId, Process.SHELL_UID);
                    if (userIds.length > 1) {
                        pr.print("User #");
                        pr.print(userId);
                        pr.println(":");
                    }
                    for (InputMethodInfo info : methods) {
                        if (brief) {
                            pr.println(info.getId());
                        } else {
                            pr.print(info.getId());
                            pr.println(":");
                            info.dump(pr::println, "  ");
                        }
                    }
                }
            }
        }
        return ShellCommandResult.SUCCESS;
    }

    /**
     * Handles {@code adb shell ime enable} and {@code adb shell ime disable}.
     *
     * @param shellCommand {@link ShellCommand} object that is handling this command
     * @param enabled      {@code true} if the command was {@code adb shell ime enable}
     * @return exit code of the command
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandEnableDisableInputMethod(
            @NonNull ShellCommand shellCommand, boolean enabled) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        final String imeId = shellCommand.getNextArgRequired();
        boolean hasFailed = false;
        try (PrintWriter out = shellCommand.getOutPrintWriter();
             PrintWriter error = shellCommand.getErrPrintWriter()) {
            synchronized (ImfLock.class) {
                final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                        mCurrentUserId, shellCommand.getErrPrintWriter());
                for (int userId : userIds) {
                    if (!userHasDebugPriv(userId, shellCommand)) {
                        continue;
                    }
                    hasFailed |= !handleShellCommandEnableDisableInputMethodInternalLocked(
                            userId, imeId, enabled, out, error);
                }
            }
        }
        return hasFailed ? ShellCommandResult.FAILURE : ShellCommandResult.SUCCESS;
    }

    /**
     * A special helper method for commands that only have {@code -u} and {@code --user} options.
     *
     * <p>You cannot use this helper method if the command has other options.</p>
     *
     * <p>CAVEAT: This method must be called only once before any other
     * {@link ShellCommand#getNextArg()} and {@link ShellCommand#getNextArgRequired()} for the
     * main arguments.</p>
     *
     * @param shellCommand {@link ShellCommand} from which options should be obtained
     * @return user ID to be resolved. {@link UserHandle#CURRENT} if not specified
     */
    @BinderThread
    @UserIdInt
    private static int handleOptionsForCommandsThatOnlyHaveUserOption(ShellCommand shellCommand) {
        while (true) {
            final String nextOption = shellCommand.getNextOption();
            if (nextOption == null) {
                break;
            }
            switch (nextOption) {
                case "-u":
                case "--user":
                    return UserHandle.parseUserArg(shellCommand.getNextArgRequired());
            }
        }
        return UserHandle.USER_CURRENT;
    }

    /**
     * Handles core logic of {@code adb shell ime enable} and {@code adb shell ime disable}.
     *
     * @param userId  user ID specified to the command (pseudo user IDs are not supported)
     * @param imeId   IME ID specified to the command
     * @param enabled {@code true} for {@code adb shell ime enable}
     * @param out     {@link PrintWriter} to output standard messages
     * @param error   {@link PrintWriter} to output error messages
     * @return {@code false} if it fails to enable the IME
     */
    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean handleShellCommandEnableDisableInputMethodInternalLocked(
            @UserIdInt int userId, String imeId, boolean enabled, PrintWriter out,
            PrintWriter error) {
        boolean failedToEnableUnknownIme = false;
        boolean previouslyEnabled = false;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (userId == mCurrentUserId) {
            if (enabled && !settings.getMethodMap().containsKey(imeId)) {
                failedToEnableUnknownIme = true;
            } else {
                previouslyEnabled = setInputMethodEnabledLocked(imeId, enabled);
            }
        } else {
            if (enabled) {
                if (!settings.getMethodMap().containsKey(imeId)) {
                    failedToEnableUnknownIme = true;
                } else {
                    final String enabledImeIdsStr = settings.getEnabledInputMethodsStr();
                    final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                            enabledImeIdsStr, imeId);
                    previouslyEnabled = TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr);
                    if (!previouslyEnabled) {
                        settings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
                    }
                }
            } else {
                previouslyEnabled =
                        settings.buildAndPutEnabledInputMethodsStrRemovingId(
                                new StringBuilder(),
                                settings.getEnabledInputMethodsAndSubtypeList(), imeId);
            }
        }
        if (failedToEnableUnknownIme) {
            error.print("Unknown input method ");
            error.print(imeId);
            error.println(" cannot be enabled for user #" + userId);
            // Also print this failure into logcat for better debuggability.
            Slog.e(TAG, "\"ime enable " + imeId + "\" for user #" + userId
                    + " failed due to its unrecognized IME ID.");
            return false;
        }
        out.print("Input method ");
        out.print(imeId);
        out.print(": ");
        out.print((enabled == previouslyEnabled) ? "already " : "now ");
        out.print(enabled ? "enabled" : "disabled");
        out.print(" for user #");
        out.println(userId);
        return true;
    }

    /**
     * Handles {@code adb shell ime set}.
     *
     * @param shellCommand {@link ShellCommand} object that is handling this command
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandSetInputMethod(@NonNull ShellCommand shellCommand) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        final String imeId = shellCommand.getNextArgRequired();
        boolean hasFailed = false;
        try (PrintWriter out = shellCommand.getOutPrintWriter();
             PrintWriter error = shellCommand.getErrPrintWriter()) {
            synchronized (ImfLock.class) {
                final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                        mCurrentUserId, shellCommand.getErrPrintWriter());
                for (int userId : userIds) {
                    if (!userHasDebugPriv(userId, shellCommand)) {
                        continue;
                    }
                    boolean failedToSelectUnknownIme = !switchToInputMethodLocked(imeId,
                            userId);
                    if (failedToSelectUnknownIme) {
                        error.print("Unknown input method ");
                        error.print(imeId);
                        error.print(" cannot be selected for user #");
                        error.println(userId);
                        // Also print this failure into logcat for better debuggability.
                        Slog.e(TAG, "\"ime set " + imeId + "\" for user #" + userId
                                + " failed due to its unrecognized IME ID.");
                    } else {
                        out.print("Input method ");
                        out.print(imeId);
                        out.print(" selected for user #");
                        out.println(userId);
                    }
                    hasFailed |= failedToSelectUnknownIme;
                }
            }
        }
        return hasFailed ? ShellCommandResult.FAILURE : ShellCommandResult.SUCCESS;
    }

    /**
     * Handles {@code adb shell ime reset-ime}.
     *
     * @param shellCommand {@link ShellCommand} object that is handling this command
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandResetInputMethod(@NonNull ShellCommand shellCommand) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        synchronized (ImfLock.class) {
            try (PrintWriter out = shellCommand.getOutPrintWriter()) {
                final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                        mCurrentUserId, shellCommand.getErrPrintWriter());
                for (int userId : userIds) {
                    if (!userHasDebugPriv(userId, shellCommand)) {
                        continue;
                    }
                    // Skip on headless user
                    final var userInfo = mUserManagerInternal.getUserInfo(userId);
                    if (userInfo != null && USER_TYPE_SYSTEM_HEADLESS.equals(userInfo.userType)) {
                        continue;
                    }
                    final String nextIme;
                    final List<InputMethodInfo> nextEnabledImes;
                    final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                    if (userId == mCurrentUserId) {
                        hideCurrentInputLocked(mImeBindingState.mFocusedWindow, 0 /* flags */,
                                SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND);
                        final var userData = mUserDataRepository.getOrCreate(userId);
                        final var bindingController = userData.mBindingController;
                        bindingController.unbindCurrentMethod();

                        // Enable default IMEs, disable others
                        var toDisable = settings.getEnabledInputMethodList();
                        var defaultEnabled = InputMethodInfoUtils.getDefaultEnabledImes(
                                mContext, settings.getMethodList());
                        toDisable.removeAll(defaultEnabled);
                        for (InputMethodInfo info : toDisable) {
                            setInputMethodEnabledLocked(info.getId(), false);
                        }
                        for (InputMethodInfo info : defaultEnabled) {
                            setInputMethodEnabledLocked(info.getId(), true);
                        }
                        // Choose new default IME, reset to none if no IME available.
                        if (!chooseNewDefaultIMELocked()) {
                            resetSelectedInputMethodAndSubtypeLocked(null);
                        }
                        updateInputMethodsFromSettingsLocked(true /* enabledMayChange */);
                        InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                                getPackageManagerForUser(mContext, settings.getUserId()),
                                settings.getEnabledInputMethodList());
                        nextIme = settings.getSelectedInputMethod();
                        nextEnabledImes = settings.getEnabledInputMethodList();
                    } else {
                        nextEnabledImes = InputMethodInfoUtils.getDefaultEnabledImes(mContext,
                                settings.getMethodList());
                        nextIme = InputMethodInfoUtils.getMostApplicableDefaultIME(
                                nextEnabledImes).getId();

                        // Reset enabled IMEs.
                        final String[] nextEnabledImeIds = new String[nextEnabledImes.size()];
                        for (int i = 0; i < nextEnabledImeIds.length; ++i) {
                            nextEnabledImeIds[i] = nextEnabledImes.get(i).getId();
                        }
                        settings.putEnabledInputMethodsStr(InputMethodUtils.concatEnabledImeIds(
                                "", nextEnabledImeIds));

                        // Reset selected IME.
                        settings.putSelectedInputMethod(nextIme);
                        settings.putSelectedDefaultDeviceInputMethod(null);
                        settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
                    }
                    out.println("Reset current and enabled IMEs for user #" + userId);
                    out.println("  Selected: " + nextIme);
                    nextEnabledImes.forEach(ime -> out.println("   Enabled: " + ime.getId()));
                }
            }
        }
        return ShellCommandResult.SUCCESS;
    }

    /**
     * Handles {@code adb shell cmd input_method tracing start/stop/save-for-bugreport}.
     *
     * @param shellCommand {@link ShellCommand} object that is handling this command
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandTraceInputMethod(@NonNull ShellCommand shellCommand) {
        final String cmd = shellCommand.getNextArgRequired();
        try (PrintWriter pw = shellCommand.getOutPrintWriter()) {
            switch (cmd) {
                case "start":
                    ImeTracing.getInstance().startTrace(pw);
                    break;  // proceed to the next step to update the IME client processes.
                case "stop":
                    ImeTracing.getInstance().stopTrace(pw);
                    break;  // proceed to the next step to update the IME client processes.
                case "save-for-bugreport":
                    ImeTracing.getInstance().saveForBugreport(pw);
                    // no need to update the IME client processes.
                    return ShellCommandResult.SUCCESS;
                default:
                    pw.println("Unknown command: " + cmd);
                    pw.println("Input method trace options:");
                    pw.println("  start: Start tracing");
                    pw.println("  stop: Stop tracing");
                    // no need to update the IME client processes.
                    return ShellCommandResult.FAILURE;
            }
        }
        boolean isImeTraceEnabled = ImeTracing.getInstance().isEnabled();
        synchronized (ImfLock.class) {
            // TODO(b/322816970): Replace this with lambda.
            mClientController.forAllClients(new Consumer<ClientState>() {

                @GuardedBy("ImfLock.class")
                @Override
                public void accept(ClientState c) {
                    c.mClient.setImeTraceEnabled(isImeTraceEnabled);
                }
            });
        }
        return ShellCommandResult.SUCCESS;
    }

    /**
     * @param userId the actual user handle obtained by {@link UserHandle#getIdentifier()}
     *               and *not* pseudo ids like {@link UserHandle#USER_ALL etc}
     * @return {@code true} if userId has debugging privileges
     * i.e. {@link UserManager#DISALLOW_DEBUGGING_FEATURES} is {@code false}
     */
    private boolean userHasDebugPriv(@UserIdInt int userId, ShellCommand shellCommand) {
        if (mUserManagerInternal.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES, userId)) {
            shellCommand.getErrPrintWriter().println("User #" + userId
                    + " is restricted with DISALLOW_DEBUGGING_FEATURES.");
            return false;
        }
        return true;
    }

    /** @hide */
    @Override
    public IImeTracker getImeTrackerService() {
        return mImeTrackerService;
    }

    /**
     * Creates an IME request tracking token for the current focused client.
     *
     * @param show   whether this is a show or a hide request
     * @param reason the reason why the IME request was created
     */
    @NonNull
    private ImeTracker.Token createStatsTokenForFocusedClient(boolean show,
            @SoftInputShowHideReason int reason) {
        final int uid = mImeBindingState.mFocusedWindowClient != null
                ? mImeBindingState.mFocusedWindowClient.mUid
                : -1;
        final var packageName = mImeBindingState.mFocusedWindowEditorInfo != null
                ? mImeBindingState.mFocusedWindowEditorInfo.packageName
                : "uid(" + uid + ")";

        return ImeTracker.forLogging().onStart(packageName, uid,
                show ? ImeTracker.TYPE_SHOW : ImeTracker.TYPE_HIDE, ImeTracker.ORIGIN_SERVER,
                reason, false /* fromUser */);
    }

    private static final class InputMethodPrivilegedOperationsImpl
            extends IInputMethodPrivilegedOperations.Stub {
        private final InputMethodManagerService mImms;
        @NonNull
        private final IBinder mToken;

        InputMethodPrivilegedOperationsImpl(InputMethodManagerService imms,
                @NonNull IBinder token) {
            mImms = imms;
            mToken = token;
        }

        @BinderThread
        @Override
        public void setImeWindowStatusAsync(int vis, int backDisposition) {
            mImms.setImeWindowStatus(mToken, vis, backDisposition);
        }

        @BinderThread
        @Override
        public void reportStartInputAsync(IBinder startInputToken) {
            mImms.reportStartInput(mToken, startInputToken);
        }

        @BinderThread
        @Override
        public void setHandwritingSurfaceNotTouchable(boolean notTouchable) {
            mImms.mHwController.setNotTouchable(notTouchable);
        }

        @BinderThread
        @Override
        public void createInputContentUriToken(Uri contentUri, String packageName,
                AndroidFuture future /* T=IBinder */) {
            @SuppressWarnings("unchecked") final AndroidFuture<IBinder> typedFuture = future;
            try {
                typedFuture.complete(mImms.createInputContentUriToken(
                        mToken, contentUri, packageName).asBinder());
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void reportFullscreenModeAsync(boolean fullscreen) {
            mImms.reportFullscreenMode(mToken, fullscreen);
        }

        @BinderThread
        @Override
        public void setInputMethod(String id, AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.setInputMethod(mToken, id);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void setInputMethodAndSubtype(String id, InputMethodSubtype subtype,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.setInputMethodAndSubtype(mToken, id, subtype);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void hideMySoftInput(@NonNull ImeTracker.Token statsToken,
                @InputMethodManager.HideFlags int flags, @SoftInputShowHideReason int reason,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.hideMySoftInput(mToken, statsToken, flags, reason);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void showMySoftInput(@NonNull ImeTracker.Token statsToken,
                @InputMethodManager.ShowFlags int flags, @SoftInputShowHideReason int reason,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.showMySoftInput(mToken, statsToken, flags, reason);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void updateStatusIconAsync(String packageName, @DrawableRes int iconId) {
            mImms.updateStatusIcon(mToken, packageName, iconId);
        }

        @BinderThread
        @Override
        public void switchToPreviousInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                typedFuture.complete(mImms.switchToPreviousInputMethod(mToken));
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void switchToNextInputMethod(boolean onlyCurrentIme,
                AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                typedFuture.complete(mImms.switchToNextInputMethod(mToken, onlyCurrentIme));
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void shouldOfferSwitchingToNextInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                typedFuture.complete(mImms.shouldOfferSwitchingToNextInputMethod(mToken));
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void notifyUserActionAsync() {
            mImms.notifyUserAction(mToken);
        }

        @BinderThread
        @Override
        public void applyImeVisibilityAsync(IBinder windowToken, boolean setVisible,
                @NonNull ImeTracker.Token statsToken) {
            mImms.applyImeVisibility(mToken, windowToken, setVisible, statsToken);
        }

        @BinderThread
        @Override
        public void onStylusHandwritingReady(int requestId, int pid) {
            mImms.onStylusHandwritingReady(requestId, pid);
        }

        @BinderThread
        @Override
        public void resetStylusHandwriting(int requestId) {
            mImms.resetStylusHandwriting(requestId);
        }

        @BinderThread
        @Override
        public void switchKeyboardLayoutAsync(int direction) {
            synchronized (ImfLock.class) {
                if (!mImms.calledWithValidTokenLocked(mToken)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mImms.switchKeyboardLayoutLocked(direction);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }
    }
}
