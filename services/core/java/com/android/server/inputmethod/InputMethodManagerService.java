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
import static android.server.inputmethod.InputMethodManagerServiceProto.CUR_FOCUSED_WINDOW_NAME;
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
import static android.server.inputmethod.InputMethodManagerServiceProto.LAST_SWITCH_USER_ID;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_IME_WITH_HARD_KEYBOARD;
import static android.server.inputmethod.InputMethodManagerServiceProto.SYSTEM_READY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_HIDE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;

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
import android.annotation.EnforcePermission;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
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
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.InputChannel;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.Flags;
import android.view.inputmethod.ImeTracker;
import android.view.inputmethod.InputBinding;
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
import com.android.internal.inputmethod.IImeTracker;
import com.android.internal.inputmethod.IInlineSuggestionsRequestCallback;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethod;
import com.android.internal.inputmethod.IInputMethodClient;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.IInputMethodSession;
import com.android.internal.inputmethod.IInputMethodSessionCallback;
import com.android.internal.inputmethod.IRemoteAccessibilityInputConnection;
import com.android.internal.inputmethod.IRemoteInputConnection;
import com.android.internal.inputmethod.ImeTracing;
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
import com.android.internal.view.IInputMethodManager;
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
import com.android.server.inputmethod.InputMethodUtils.InputMethodSettings;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerInternal;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.WindowManagerInternal;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.security.InvalidParameterException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * This class provides a system service that manages input methods.
 */
public final class InputMethodManagerService extends IInputMethodManager.Stub
        implements Handler.Callback {
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

    @UserIdInt
    private int mLastSwitchUserId;

    final Context mContext;
    final Resources mRes;
    private final Handler mHandler;
    private final InputMethodSettings mSettings;
    final SettingsObserver mSettingsObserver;
    private final SparseBooleanArray mLoggedDeniedGetInputMethodWindowVisibleHeightForUid =
            new SparseBooleanArray(0);
    final WindowManagerInternal mWindowManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;
    final PackageManagerInternal mPackageManagerInternal;
    final InputManagerInternal mInputManagerInternal;
    final ImePlatformCompatUtils mImePlatformCompatUtils;
    final InputMethodDeviceConfigs mInputMethodDeviceConfigs;
    private final ArrayMap<String, List<InputMethodSubtype>> mAdditionalSubtypeMap =
            new ArrayMap<>();
    private final UserManagerInternal mUserManagerInternal;
    private final InputMethodMenuController mMenuController;
    @NonNull private final InputMethodBindingController mBindingController;
    @NonNull private final AutofillSuggestionsController mAutofillController;

    @GuardedBy("ImfLock.class")
    @NonNull private final ImeVisibilityStateComputer mVisibilityStateComputer;

    @GuardedBy("ImfLock.class")
    @NonNull private final DefaultImeVisibilityApplier mVisibilityApplier;

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

    // All known input methods.
    final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    private final ArrayMap<String, InputMethodInfo> mMethodMap = new ArrayMap<>();

    // Mapping from deviceId to the device-specific imeId for that device.
    @GuardedBy("ImfLock.class")
    private final SparseArray<String> mVirtualDeviceMethodMap = new SparseArray<>();

    // TODO: Instantiate mSwitchingController for each user.
    @NonNull
    private InputMethodSubtypeSwitchingController mSwitchingController;
    // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
    @NonNull
    private HardwareKeyboardShortcutController mHardwareKeyboardShortcutController;

    /**
     * Tracks how many times {@link #mMethodMap} was updated.
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
    private int mDisplayIdToShowIme = INVALID_DISPLAY;

    @GuardedBy("ImfLock.class")
    private int mDeviceIdToShowIme = DEVICE_ID_DEFAULT;

    @Nullable private StatusBarManagerInternal mStatusBarManagerInternal;
    private boolean mShowOngoingImeSwitcherForPhones;
    @GuardedBy("ImfLock.class")
    private final HandwritingModeController mHwController;
    @GuardedBy("ImfLock.class")
    private IntArray mStylusIds;

    @GuardedBy("ImfLock.class")
    @Nullable
    private OverlayableSystemBooleanResourceWrapper mImeDrawsImeNavBarRes;
    @GuardedBy("ImfLock.class")
    @Nullable
    Future<?> mImeDrawsImeNavBarResLazyInitFuture;

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
    private static class AccessibilitySessionState {
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

    private static final class ClientDeathRecipient implements IBinder.DeathRecipient {
        private final InputMethodManagerService mImms;
        private final IInputMethodClient mClient;

        ClientDeathRecipient(InputMethodManagerService imms, IInputMethodClient client) {
            mImms = imms;
            mClient = client;
        }

        @Override
        public void binderDied() {
            mImms.removeClient(mClient);
        }
    }

    static final class ClientState {
        final IInputMethodClientInvoker mClient;
        final IRemoteInputConnection mFallbackInputConnection;
        final int mUid;
        final int mPid;
        final int mSelfReportedDisplayId;
        final InputBinding mBinding;
        final ClientDeathRecipient mClientDeathRecipient;

        boolean mSessionRequested;
        boolean mSessionRequestedForAccessibility;
        SessionState mCurSession;
        SparseArray<AccessibilitySessionState> mAccessibilitySessions = new SparseArray<>();

        @Override
        public String toString() {
            return "ClientState{" + Integer.toHexString(
                    System.identityHashCode(this)) + " mUid=" + mUid
                    + " mPid=" + mPid + " mSelfReportedDisplayId=" + mSelfReportedDisplayId + "}";
        }

        ClientState(IInputMethodClientInvoker client,
                IRemoteInputConnection fallbackInputConnection,
                int uid, int pid, int selfReportedDisplayId,
                ClientDeathRecipient clientDeathRecipient) {
            mClient = client;
            mFallbackInputConnection = fallbackInputConnection;
            mUid = uid;
            mPid = pid;
            mSelfReportedDisplayId = selfReportedDisplayId;
            mBinding = new InputBinding(null, mFallbackInputConnection.asBinder(), mUid, mPid);
            mClientDeathRecipient = clientDeathRecipient;
        }
    }

    @GuardedBy("ImfLock.class")
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

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
        return mBindingController.getSelectedMethodId();
    }

    @GuardedBy("ImfLock.class")
    private void setSelectedMethodIdLocked(@Nullable String selectedMethodId) {
        mBindingController.setSelectedMethodId(selectedMethodId);
    }

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    @GuardedBy("ImfLock.class")
    private int getSequenceNumberLocked() {
        return mBindingController.getSequenceNumber();
    }

    /**
     * Increase the current binding sequence number by one.
     * Reset to 1 on overflow.
     */
    @GuardedBy("ImfLock.class")
    private void advanceSequenceNumberLocked() {
        mBindingController.advanceSequenceNumber();
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    InputMethodInfo queryInputMethodForCurrentUserLocked(@NonNull String imeId) {
        return mMethodMap.get(imeId);
    }

    /**
     * The client that is currently bound to an input method.
     */
    @Nullable
    private ClientState mCurClient;

    /**
     * The last window token that we confirmed to be focused.  This is always updated upon reports
     * from the input method client.  If the window state is already changed before the report is
     * handled, this field just keeps the last value.
     */
    IBinder mCurFocusedWindow;

    /**
     * The last window token that we confirmed that IME started talking to.  This is always updated
     * upon reports from the input method.  If the window state is already changed before the report
     * is handled, this field just keeps the last value.
     */
    IBinder mLastImeTargetWindow;

    /**
     * {@link LayoutParams#softInputMode} of {@link #mCurFocusedWindow}.
     *
     * @see #mCurFocusedWindow
     */
    @SoftInputModeFlags
    int mCurFocusedWindowSoftInputMode;

    /**
     * The client by which {@link #mCurFocusedWindow} was reported. This gets updated whenever an
     * IME-focusable window gained focus (without necessarily starting an input connection),
     * while {@link #mCurClient} only gets updated when we actually start an input connection.
     *
     * @see #mCurFocusedWindow
     */
    @Nullable
    ClientState mCurFocusedWindowClient;

    /**
     * The editor info by which {@link #mCurFocusedWindow} was reported. This differs from
     * {@link #mCurEditorInfo} the same way {@link #mCurFocusedWindowClient} differs
     * from {@link #mCurClient}.
     *
     * @see #mCurFocusedWindow
     */
    @Nullable
    EditorInfo mCurFocusedWindowEditorInfo;

    /**
     * The {@link IRemoteInputConnection} last provided by the current client.
     */
    IRemoteInputConnection mCurInputConnection;

    /**
     * The {@link ImeOnBackInvokedDispatcher} last provided by the current client to
     * receive {@link android.window.OnBackInvokedCallback}s forwarded from IME.
     */
    ImeOnBackInvokedDispatcher mCurImeDispatcher;

    /**
     * The {@link IRemoteAccessibilityInputConnection} last provided by the current client.
     */
    @Nullable IRemoteAccessibilityInputConnection mCurRemoteAccessibilityInputConnection;

    /**
     * The {@link EditorInfo} last provided by the current client.
     */
    @Nullable
    EditorInfo mCurEditorInfo;

    /**
     * Id obtained with {@link InputMethodInfo#getId()} for the input method that we are currently
     * connected to or in the process of connecting to.
     *
     * <p>This can be {@code null} when no input method is connected.</p>
     *
     * @see #getSelectedMethodIdLocked()
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private String getCurIdLocked() {
        return mBindingController.getCurId();
    }

    /**
     * The current subtype of the current input method.
     */
    private InputMethodSubtype mCurrentSubtype;

    /**
     * {@code true} if the IME has not been mostly hidden via {@link android.view.InsetsController}
     */
    private boolean mCurPerceptible;

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    @GuardedBy("ImfLock.class")
    private boolean hasConnectionLocked() {
        return mBindingController.hasMainConnection();
    }

    /** The token tracking the current IME request or {@code null} otherwise. */
    @Nullable
    private ImeTracker.Token mCurStatsToken;

    /**
     * {@code true} if the current input method is in fullscreen mode.
     */
    boolean mInFullscreenMode;

    /**
     * The Intent used to connect to the current input method.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private Intent getCurIntentLocked() {
        return mBindingController.getCurIntent();
    }

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    IBinder getCurTokenLocked() {
        return mBindingController.getCurToken();
    }

    /**
     * The displayId of current active input method.
     */
    @GuardedBy("ImfLock.class")
    int getCurTokenDisplayIdLocked() {
        return mCurTokenDisplayId;
    }

    @GuardedBy("ImfLock.class")
    void setCurTokenDisplayIdLocked(int curTokenDisplayId) {
        mCurTokenDisplayId = curTokenDisplayId;
    }

    @GuardedBy("ImfLock.class")
    private int mCurTokenDisplayId = INVALID_DISPLAY;

    /**
     * The host input token of the current active input method.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IBinder mCurHostInputToken;

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
        return mBindingController.getCurMethod();
    }

    /**
     * If not {@link Process#INVALID_UID}, then the UID of {@link #getCurIntentLocked()}.
     */
    @GuardedBy("ImfLock.class")
    private int getCurMethodUidLocked() {
        return mBindingController.getCurMethodUid();
    }

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    @GuardedBy("ImfLock.class")
    private long getLastBindTimeLocked() {
        return mBindingController.getLastBindTime();
    }

    /**
     * Have we called mCurMethod.bindInput()?
     */
    boolean mBoundToMethod;

    /**
     * Have we called bindInput() for accessibility services?
     */
    boolean mBoundToAccessibility;

    /**
     * Currently enabled session.
     */
    @GuardedBy("ImfLock.class")
    SessionState mEnabledSession;
    SparseArray<AccessibilitySessionState> mEnabledAccessibilitySessions = new SparseArray<>();

    /**
     * True if the device is currently interactive with user.  The value is true initially.
     */
    boolean mIsInteractive = true;

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
    int mImeWindowVis;

    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    private final String mSlotIme;

    /**
     * Registered {@link InputMethodListListener}.
     * This variable can be accessed from both of MainThread and BinderThread.
     */
    private final CopyOnWriteArrayList<InputMethodListListener> mInputMethodListListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Internal state snapshot when
     * {@link IInputMethod#startInput(IInputMethod.StartInputParams)} is about to be called.
     *
     * <p>Calling that IPC endpoint basically means that
     * {@link InputMethodService#doStartInput(InputConnection, EditorInfo, boolean)} will be called
     * back in the current IME process shortly, which will also affect what the current IME starts
     * receiving from {@link InputMethodService#getCurrentInputConnection()}. In other words, this
     * snapshot will be taken every time when {@link InputMethodManagerService} is initiating a new
     * logical input session between the client application and the current IME.</p>
     *
     * <p>Be careful to not keep strong references to this object forever, which can prevent
     * {@link StartInputInfo#mImeToken} and {@link StartInputInfo#mTargetWindow} from being GC-ed.
     * </p>
     */
    private static class StartInputInfo {
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

        final int mSequenceNumber;
        final long mTimestamp;
        final long mWallTime;
        @UserIdInt
        final int mImeUserId;
        @NonNull
        final IBinder mImeToken;
        final int mImeDisplayId;
        @NonNull
        final String mImeId;
        @StartInputReason
        final int mStartInputReason;
        final boolean mRestarting;
        @UserIdInt
        final int mTargetUserId;
        final int mTargetDisplayId;
        @Nullable
        final IBinder mTargetWindow;
        @NonNull
        final EditorInfo mEditorInfo;
        @SoftInputModeFlags
        final int mTargetWindowSoftInputMode;
        final int mClientBindSequenceNumber;

        StartInputInfo(@UserIdInt int imeUserId, @NonNull IBinder imeToken, int imeDisplayId,
                @NonNull String imeId, @StartInputReason int startInputReason, boolean restarting,
                @UserIdInt int targetUserId, int targetDisplayId, @Nullable IBinder targetWindow,
                @NonNull EditorInfo editorInfo, @SoftInputModeFlags int targetWindowSoftInputMode,
                int clientBindSequenceNumber) {
            mSequenceNumber = sSequenceNumber.getAndIncrement();
            mTimestamp = SystemClock.uptimeMillis();
            mWallTime = System.currentTimeMillis();
            mImeUserId = imeUserId;
            mImeToken = imeToken;
            mImeDisplayId = imeDisplayId;
            mImeId = imeId;
            mStartInputReason = startInputReason;
            mRestarting = restarting;
            mTargetUserId = targetUserId;
            mTargetDisplayId = targetDisplayId;
            mTargetWindow = targetWindow;
            mEditorInfo = editorInfo;
            mTargetWindowSoftInputMode = targetWindowSoftInputMode;
            mClientBindSequenceNumber = clientBindSequenceNumber;
        }
    }

    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();

    @VisibleForTesting
    static final class SoftInputShowHideHistory {
        private final Entry[] mEntries = new Entry[16];
        private int mNextIndex = 0;
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

        static final class Entry {
            final int mSequenceNumber = sSequenceNumber.getAndIncrement();
            @Nullable
            final ClientState mClientState;
            @SoftInputModeFlags
            final int mFocusedWindowSoftInputMode;
            @SoftInputShowHideReason
            final int mReason;
            // The timing of handling showCurrentInputLocked() or hideCurrentInputLocked().
            final long mTimestamp;
            final long mWallTime;
            final boolean mInFullscreenMode;
            @NonNull
            final String mFocusedWindowName;
            @Nullable
            final EditorInfo mEditorInfo;
            @NonNull
            final String mRequestWindowName;
            @Nullable
            final String mImeControlTargetName;
            @Nullable
            final String mImeTargetNameFromWm;
            @Nullable
            final String mImeSurfaceParentName;

            Entry(ClientState client, EditorInfo editorInfo, String focusedWindowName,
                    @SoftInputModeFlags int softInputMode, @SoftInputShowHideReason int reason,
                    boolean inFullscreenMode, String requestWindowName,
                    @Nullable String imeControlTargetName, @Nullable String imeTargetName,
                    @Nullable String imeSurfaceParentName) {
                mClientState = client;
                mEditorInfo = editorInfo;
                mFocusedWindowName = focusedWindowName;
                mFocusedWindowSoftInputMode = softInputMode;
                mReason = reason;
                mTimestamp = SystemClock.uptimeMillis();
                mWallTime = System.currentTimeMillis();
                mInFullscreenMode = inFullscreenMode;
                mRequestWindowName = requestWindowName;
                mImeControlTargetName = imeControlTargetName;
                mImeTargetNameFromWm = imeTargetName;
                mImeSurfaceParentName = imeSurfaceParentName;
            }
        }

        void addEntry(@NonNull Entry entry) {
            final int index = mNextIndex;
            mEntries[index] = entry;
            mNextIndex = (mNextIndex + 1) % mEntries.length;
        }

        void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            final DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                            .withZone(ZoneId.systemDefault());

            for (int i = 0; i < mEntries.length; ++i) {
                final Entry entry = mEntries[(i + mNextIndex) % mEntries.length];
                if (entry == null) {
                    continue;
                }
                pw.print(prefix);
                pw.println("SoftInputShowHide #" + entry.mSequenceNumber + ":");

                pw.print(prefix);
                pw.println("  time=" + formatter.format(Instant.ofEpochMilli(entry.mWallTime))
                        + " (timestamp=" + entry.mTimestamp + ")");

                pw.print(prefix);
                pw.print("  reason=" + InputMethodDebug.softInputDisplayReasonToString(
                        entry.mReason));
                pw.println(" inFullscreenMode=" + entry.mInFullscreenMode);

                pw.print(prefix);
                pw.println("  requestClient=" + entry.mClientState);

                pw.print(prefix);
                pw.println("  focusedWindowName=" + entry.mFocusedWindowName);

                pw.print(prefix);
                pw.println("  requestWindowName=" + entry.mRequestWindowName);

                pw.print(prefix);
                pw.println("  imeControlTargetName=" + entry.mImeControlTargetName);

                pw.print(prefix);
                pw.println("  imeTargetNameFromWm=" + entry.mImeTargetNameFromWm);

                pw.print(prefix);
                pw.println("  imeSurfaceParentName=" + entry.mImeSurfaceParentName);

                pw.print(prefix);
                pw.print("  editorInfo:");
                if (entry.mEditorInfo != null) {
                    pw.print(" inputType=" + entry.mEditorInfo.inputType);
                    pw.print(" privateImeOptions=" + entry.mEditorInfo.privateImeOptions);
                    pw.println(" fieldId (viewId)=" + entry.mEditorInfo.fieldId);
                } else {
                    pw.println(" null");
                }

                pw.print(prefix);
                pw.println("  focusedWindowSoftInputMode=" + InputMethodDebug.softInputModeToString(
                        entry.mFocusedWindowSoftInputMode));
            }
        }
    }

    /**
     * A ring buffer to store the history of {@link StartInputInfo}.
     */
    private static final class StartInputHistory {
        /**
         * Entry size for non low-RAM devices.
         *
         * <p>TODO: Consider to follow what other system services have been doing to manage
         * constants (e.g. {@link android.provider.Settings.Global#ACTIVITY_MANAGER_CONSTANTS}).</p>
         */
        private static final int ENTRY_SIZE_FOR_HIGH_RAM_DEVICE = 32;

        /**
         * Entry size for low-RAM devices.
         *
         * <p>TODO: Consider to follow what other system services have been doing to manage
         * constants (e.g. {@link android.provider.Settings.Global#ACTIVITY_MANAGER_CONSTANTS}).</p>
         */
        private static final int ENTRY_SIZE_FOR_LOW_RAM_DEVICE = 5;

        private static int getEntrySize() {
            if (ActivityManager.isLowRamDeviceStatic()) {
                return ENTRY_SIZE_FOR_LOW_RAM_DEVICE;
            } else {
                return ENTRY_SIZE_FOR_HIGH_RAM_DEVICE;
            }
        }

        /**
         * Backing store for the ring buffer.
         */
        private final Entry[] mEntries = new Entry[getEntrySize()];

        /**
         * An index of {@link #mEntries}, to which next {@link #addEntry(StartInputInfo)} should
         * write.
         */
        private int mNextIndex = 0;

        /**
         * Recyclable entry to store the information in {@link StartInputInfo}.
         */
        private static final class Entry {
            int mSequenceNumber;
            long mTimestamp;
            long mWallTime;
            @UserIdInt
            int mImeUserId;
            @NonNull
            String mImeTokenString;
            int mImeDisplayId;
            @NonNull
            String mImeId;
            @StartInputReason
            int mStartInputReason;
            boolean mRestarting;
            @UserIdInt
            int mTargetUserId;
            int mTargetDisplayId;
            @NonNull
            String mTargetWindowString;
            @NonNull
            EditorInfo mEditorInfo;
            @SoftInputModeFlags
            int mTargetWindowSoftInputMode;
            int mClientBindSequenceNumber;

            Entry(@NonNull StartInputInfo original) {
                set(original);
            }

            void set(@NonNull StartInputInfo original) {
                mSequenceNumber = original.mSequenceNumber;
                mTimestamp = original.mTimestamp;
                mWallTime = original.mWallTime;
                mImeUserId = original.mImeUserId;
                // Intentionally convert to String so as not to keep a strong reference to a Binder
                // object.
                mImeTokenString = String.valueOf(original.mImeToken);
                mImeDisplayId = original.mImeDisplayId;
                mImeId = original.mImeId;
                mStartInputReason = original.mStartInputReason;
                mRestarting = original.mRestarting;
                mTargetUserId = original.mTargetUserId;
                mTargetDisplayId = original.mTargetDisplayId;
                // Intentionally convert to String so as not to keep a strong reference to a Binder
                // object.
                mTargetWindowString = String.valueOf(original.mTargetWindow);
                mEditorInfo = original.mEditorInfo;
                mTargetWindowSoftInputMode = original.mTargetWindowSoftInputMode;
                mClientBindSequenceNumber = original.mClientBindSequenceNumber;
            }
        }

        /**
         * Add a new entry and discard the oldest entry as needed.
         * @param info {@link StartInputInfo} to be added.
         */
        void addEntry(@NonNull StartInputInfo info) {
            final int index = mNextIndex;
            if (mEntries[index] == null) {
                mEntries[index] = new Entry(info);
            } else {
                mEntries[index].set(info);
            }
            mNextIndex = (mNextIndex + 1) % mEntries.length;
        }

        void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            final DateTimeFormatter formatter =
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                            .withZone(ZoneId.systemDefault());

            for (int i = 0; i < mEntries.length; ++i) {
                final Entry entry = mEntries[(i + mNextIndex) % mEntries.length];
                if (entry == null) {
                    continue;
                }
                pw.print(prefix);
                pw.println("StartInput #" + entry.mSequenceNumber + ":");

                pw.print(prefix);
                pw.println("  time=" + formatter.format(Instant.ofEpochMilli(entry.mWallTime))
                        + " (timestamp=" + entry.mTimestamp + ")"
                        + " reason="
                        + InputMethodDebug.startInputReasonToString(entry.mStartInputReason)
                        + " restarting=" + entry.mRestarting);

                pw.print(prefix);
                pw.print("  imeToken=" + entry.mImeTokenString + " [" + entry.mImeId + "]");
                pw.print(" imeUserId=" + entry.mImeUserId);
                pw.println(" imeDisplayId=" + entry.mImeDisplayId);

                pw.print(prefix);
                pw.println("  targetWin=" + entry.mTargetWindowString
                        + " [" + entry.mEditorInfo.packageName + "]"
                        + " targetUserId=" + entry.mTargetUserId
                        + " targetDisplayId=" + entry.mTargetDisplayId
                        + " clientBindSeq=" + entry.mClientBindSequenceNumber);

                pw.print(prefix);
                pw.println("  softInputMode=" + InputMethodDebug.softInputModeToString(
                        entry.mTargetWindowSoftInputMode));

                pw.print(prefix);
                pw.println("  inputType=0x" + Integer.toHexString(entry.mEditorInfo.inputType)
                        + " imeOptions=0x" + Integer.toHexString(entry.mEditorInfo.imeOptions)
                        + " fieldId=0x" + Integer.toHexString(entry.mEditorInfo.fieldId)
                        + " fieldName=" + entry.mEditorInfo.fieldName
                        + " actionId=" + entry.mEditorInfo.actionId
                        + " actionLabel=" + entry.mEditorInfo.actionLabel);
            }
        }
    }

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

        @Override public void onChange(boolean selfChange, Uri uri) {
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
                        hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */,
                                0 /* flags */, null /* resultReceiver */,
                                SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE);
                    } else if (isShowRequestedForCurrentWindow()) {
                        showCurrentInputImplicitLocked(mCurFocusedWindow,
                                SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE);
                    }
                } else if (stylusHandwritingEnabledUri.equals(uri)) {
                    InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
                } else {
                    boolean enabledChanged = false;
                    String newEnabled = mSettings.getEnabledInputMethodsStr();
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
                    if (senderUserId != mSettings.getCurrentUserId()) {
                        // A background user is trying to hide the dialog. Ignore.
                        return;
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
            buildInputMethodListLocked(true);
            // If the locale is changed, needs to reset the default ime
            resetDefaultImeLocked(mContext);
            updateFromSettingsLocked(true);
        }
    }

    final class MyPackageMonitor extends PackageMonitor {
        /**
         * Package names that are known to contain {@link InputMethodService}.
         *
         * <p>No need to include packages because of direct-boot unaware IMEs since we always rescan
         * all the packages when the user is unlocked, and direct-boot awareness will not be changed
         * dynamically unless the entire package is updated, which also always triggers package
         * rescanning.</p>
         */
        @GuardedBy("ImfLock.class")
        private final ArraySet<String> mKnownImePackageNames = new ArraySet<>();

        /**
         * Packages that are appeared, disappeared, or modified for whatever reason.
         *
         * <p>Note: For now we intentionally use {@link ArrayList} instead of {@link ArraySet}
         * because 1) the number of elements is almost always 1 or so, and 2) we do not care
         * duplicate elements for our use case.</p>
         *
         * <p>This object must be accessed only from callback methods in {@link PackageMonitor},
         * which should be bound to {@link #getRegisteredHandler()}.</p>
         */
        private final ArrayList<String> mChangedPackages = new ArrayList<>();

        /**
         * {@code true} if one or more packages that contain {@link InputMethodService} appeared.
         *
         * <p>This field must be accessed only from callback methods in {@link PackageMonitor},
         * which should be bound to {@link #getRegisteredHandler()}.</p>
         */
        private boolean mImePackageAppeared = false;

        @GuardedBy("ImfLock.class")
        void clearKnownImePackageNamesLocked() {
            mKnownImePackageNames.clear();
        }

        @GuardedBy("ImfLock.class")
        void addKnownImePackageNameLocked(@NonNull String packageName) {
            mKnownImePackageNames.add(packageName);
        }

        @GuardedBy("ImfLock.class")
        private boolean isChangingPackagesOfCurrentUserLocked() {
            final int userId = getChangingUserId();
            final boolean retval = userId == mSettings.getCurrentUserId();
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
                String curInputMethodId = mSettings.getSelectedInputMethod();
                final int numImes = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i = 0; i < numImes; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
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
        public void onPackageAppeared(String packageName, int reason) {
            if (!mImePackageAppeared) {
                final PackageManager pm = mContext.getPackageManager();
                final List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                        new Intent(InputMethod.SERVICE_INTERFACE).setPackage(packageName),
                        PackageManager.MATCH_DISABLED_COMPONENTS, getChangingUserId());
                // No need to lock this because we access it only on getRegisteredHandler().
                if (!services.isEmpty()) {
                    mImePackageAppeared = true;
                }
            }
            // No need to lock this because we access it only on getRegisteredHandler().
            mChangedPackages.add(packageName);
        }

        @Override
        public void onPackageDisappeared(String packageName, int reason) {
            // No need to lock this because we access it only on getRegisteredHandler().
            mChangedPackages.add(packageName);
        }

        @Override
        public void onPackageModified(String packageName) {
            // No need to lock this because we access it only on getRegisteredHandler().
            mChangedPackages.add(packageName);
        }

        @Override
        public void onPackagesSuspended(String[] packages) {
            // No need to lock this because we access it only on getRegisteredHandler().
            for (String packageName : packages) {
                mChangedPackages.add(packageName);
            }
        }

        @Override
        public void onPackagesUnsuspended(String[] packages) {
            // No need to lock this because we access it only on getRegisteredHandler().
            for (String packageName : packages) {
                mChangedPackages.add(packageName);
            }
        }

        @Override
        public void onPackageDataCleared(String packageName, int uid) {
            boolean changed = false;
            for (InputMethodInfo imi : mMethodList) {
                if (imi.getPackageName().equals(packageName)) {
                    mAdditionalSubtypeMap.remove(imi.getId());
                    changed = true;
                }
            }
            if (changed) {
                AdditionalSubtypeUtils.save(
                        mAdditionalSubtypeMap, mMethodMap, mSettings.getCurrentUserId());
                mChangedPackages.add(packageName);
            }
        }

        @Override
        public void onFinishPackageChanges() {
            onFinishPackageChangesInternal();
            clearPackageChangeState();
        }

        @Override
        public void onUidRemoved(int uid) {
            synchronized (ImfLock.class) {
                mLoggedDeniedGetInputMethodWindowVisibleHeightForUid.delete(uid);
            }
        }

        private void clearPackageChangeState() {
            // No need to lock them because we access these fields only on getRegisteredHandler().
            mChangedPackages.clear();
            mImePackageAppeared = false;
        }

        @GuardedBy("ImfLock.class")
        private boolean shouldRebuildInputMethodListLocked() {
            // This method is guaranteed to be called only by getRegisteredHandler().

            // If there is any new package that contains at least one IME, then rebuilt the list
            // of IMEs.
            if (mImePackageAppeared) {
                return true;
            }

            // Otherwise, check if mKnownImePackageNames and mChangedPackages have any intersection.
            // TODO: Consider to create a utility method to do the following test. List.retainAll()
            // is an option, but it may still do some extra operations that we do not need here.
            final int numPackages = mChangedPackages.size();
            for (int i = 0; i < numPackages; ++i) {
                final String packageName = mChangedPackages.get(i);
                if (mKnownImePackageNames.contains(packageName)) {
                    return true;
                }
            }
            return false;
        }

        private void onFinishPackageChangesInternal() {
            synchronized (ImfLock.class) {
                if (!isChangingPackagesOfCurrentUserLocked()) {
                    return;
                }
                if (!shouldRebuildInputMethodListLocked()) {
                    return;
                }

                InputMethodInfo curIm = null;
                String curInputMethodId = mSettings.getSelectedInputMethod();
                final int numImes = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i = 0; i < numImes; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
                        final String imiId = imi.getId();
                        if (imiId.equals(curInputMethodId)) {
                            curIm = imi;
                        }

                        int change = isPackageDisappearing(imi.getPackageName());
                        if (change == PACKAGE_TEMPORARY_CHANGE
                                || change == PACKAGE_PERMANENT_CHANGE) {
                            Slog.i(TAG, "Input method uninstalled, disabling: "
                                    + imi.getComponent());
                            setInputMethodEnabledLocked(imi.getId(), false);
                        } else if (change == PACKAGE_UPDATING) {
                            Slog.i(TAG,
                                    "Input method reinstalling, clearing additional subtypes: "
                                            + imi.getComponent());
                            mAdditionalSubtypeMap.remove(imi.getId());
                            AdditionalSubtypeUtils.save(mAdditionalSubtypeMap,
                                    mMethodMap,
                                    mSettings.getCurrentUserId());
                        }
                    }
                }

                buildInputMethodListLocked(false /* resetDefaultEnabledIme */);

                boolean changed = false;

                if (curIm != null) {
                    int change = isPackageDisappearing(curIm.getPackageName());
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        final PackageManager userAwarePackageManager =
                                getPackageManagerForUser(mContext, mSettings.getCurrentUserId());
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
    private UserSwitchHandlerTask mUserSwitchHandlerTask;

    /**
     * {@link SystemService} used to publish and manage the lifecycle of
     * {@link InputMethodManagerService}.
     */
    public static final class Lifecycle extends SystemService {
        private final InputMethodManagerService mService;

        public Lifecycle(Context context) {
            this(context, new InputMethodManagerService(context));
        }

        public Lifecycle(
                Context context, @NonNull InputMethodManagerService inputMethodManagerService) {
            super(context);
            mService = inputMethodManagerService;
        }

        @Override
        public void onStart() {
            mService.publishLocalService();
            publishBinderService(Context.INPUT_METHOD_SERVICE, mService, false /*allowIsolated*/,
                    DUMP_FLAG_PRIORITY_CRITICAL | DUMP_FLAG_PRIORITY_NORMAL | DUMP_FLAG_PROTO);
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            // Called on ActivityManager thread.
            synchronized (ImfLock.class) {
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
            SecureSettingsWrapper.onUserStarting(user.getUserIdentifier());
        }
    }

    void onUnlockUser(@UserIdInt int userId) {
        synchronized (ImfLock.class) {
            final int currentUserId = mSettings.getCurrentUserId();
            if (DEBUG) {
                Slog.d(TAG, "onUnlockUser: userId=" + userId + " curUserId=" + currentUserId);
            }
            if (userId != currentUserId) {
                return;
            }
            mSettings.switchCurrentUser(currentUserId);
            if (mSystemReady) {
                // We need to rebuild IMEs.
                buildInputMethodListLocked(false /* resetDefaultEnabledIme */);
                updateInputMethodsFromSettingsLocked(true /* enabledChanged */);
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
        hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */, 0 /* flags */,
                null /* resultReceiver */, SoftInputShowHideReason.HIDE_SWITCH_USER);
        final UserSwitchHandlerTask task = new UserSwitchHandlerTask(this, userId,
                clientToBeReset);
        mUserSwitchHandlerTask = task;
        mHandler.post(task);
    }

    public InputMethodManagerService(Context context) {
        this(context, null, null);
    }

    @VisibleForTesting
    InputMethodManagerService(
            Context context,
            @Nullable ServiceThread serviceThreadForTesting,
            @Nullable InputMethodBindingController bindingControllerForTesting) {
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

        final int userId = mActivityManagerInternal.getCurrentUserId();

        mLastSwitchUserId = userId;

        // mSettings should be created before buildInputMethodListLocked
        mSettings = new InputMethodSettings(mMethodMap, userId);

        AdditionalSubtypeUtils.load(mAdditionalSubtypeMap, userId);
        mSwitchingController =
                InputMethodSubtypeSwitchingController.createInstanceLocked(context, mMethodMap,
                        userId);
        mHardwareKeyboardShortcutController =
                new HardwareKeyboardShortcutController(mMethodMap, userId);
        mMenuController = new InputMethodMenuController(this);
        mBindingController =
                bindingControllerForTesting != null
                        ? bindingControllerForTesting
                        : new InputMethodBindingController(this);
        mAutofillController = new AutofillSuggestionsController(this);

        mVisibilityStateComputer = new ImeVisibilityStateComputer(this);
        mVisibilityApplier = new DefaultImeVisibilityApplier(this);

        mPreventImeStartupUnlessTextEditor = mRes.getBoolean(
                com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
        mNonPreemptibleInputMethods = mRes.getStringArray(
                com.android.internal.R.array.config_nonPreemptibleInputMethods);
        IntConsumer toolTypeConsumer =
                Flags.useHandwritingListenerForTooltype()
                        ? toolType -> onUpdateEditorToolType(toolType) : null;
        mHwController = new HandwritingModeController(mContext, thread.getLooper(),
                new InkWindowInitializer(), toolTypeConsumer);
        registerDeviceListenerAndCheckStylusSupport();
    }

    @GuardedBy("ImfLock.class")
    @UserIdInt
    int getCurrentImeUserIdLocked() {
        return mSettings.getCurrentUserId();
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

    @GuardedBy("ImfLock.class")
    private void resetDefaultImeLocked(Context context) {
        // Do not reset the default (current) IME when it is a 3rd-party IME
        String selectedMethodId = getSelectedMethodIdLocked();
        if (selectedMethodId != null && !mMethodMap.get(selectedMethodId).isSystem()) {
            return;
        }
        final List<InputMethodInfo> suitableImes = InputMethodInfoUtils.getDefaultEnabledImes(
                context, mSettings.getEnabledInputMethodListLocked());
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
                    + " currentUserId=" + mSettings.getCurrentUserId());
        }

        maybeInitImeNavbarConfigLocked(newUserId);

        // ContentObserver should be registered again when the user is changed
        mSettingsObserver.registerContentObserverLocked(newUserId);

        // If the system is not ready or the device is not yed unlocked by the user, then we use
        // copy-on-write settings.
        final boolean useCopyOnWriteSettings =
                !mSystemReady || !mUserManagerInternal.isUserUnlockingOrUnlocked(newUserId);
        mSettings.switchCurrentUser(newUserId);
        // Additional subtypes should be reset when the user is changed
        AdditionalSubtypeUtils.load(mAdditionalSubtypeMap, newUserId);
        final String defaultImiId = mSettings.getSelectedInputMethod();

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

        // The mSystemReady flag is set during boot phase,
        // and user switch would not happen at that time.
        resetCurrentMethodAndClientLocked(UnbindReason.SWITCH_USER);
        buildInputMethodListLocked(initialUserSwitch);
        if (TextUtils.isEmpty(mSettings.getSelectedInputMethod())) {
            // This is the first time of the user switch and
            // set the current ime to the proper one.
            resetDefaultImeLocked(mContext);
        }
        updateFromSettingsLocked(true);

        if (initialUserSwitch) {
            InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                    getPackageManagerForUser(mContext, newUserId),
                    mSettings.getEnabledInputMethodListLocked());
        }

        if (DEBUG) {
            Slog.d(TAG, "Switching user stage 3/3. newUserId=" + newUserId
                    + " selectedIme=" + mSettings.getSelectedInputMethod());
        }

        mLastSwitchUserId = newUserId;

        if (mIsInteractive && clientToBeReset != null) {
            final ClientState cs = mClients.get(clientToBeReset.asBinder());
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
                final int currentUserId = mSettings.getCurrentUserId();
                mSettings.switchCurrentUser(currentUserId);
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
                        if (currentUserId != mSettings.getCurrentUserId()) {
                            // This means that the current user is already switched to other user
                            // before the background task is executed. In this scenario the relevant
                            // field should already be initialized.
                            return;
                        }
                        maybeInitImeNavbarConfigLocked(currentUserId);
                    }
                }, "Lazily initialize IMMS#mImeDrawsImeNavBarRes");

                mMyPackageMonitor.register(mContext, null, UserHandle.ALL, true);
                mSettingsObserver.registerContentObserverLocked(currentUserId);

                final IntentFilter broadcastFilterForAllUsers = new IntentFilter();
                broadcastFilterForAllUsers.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                mContext.registerReceiverAsUser(new ImmsBroadcastReceiverForAllUsers(),
                        UserHandle.ALL, broadcastFilterForAllUsers, null, null,
                        Context.RECEIVER_EXPORTED);

                final String defaultImiId = mSettings.getSelectedInputMethod();
                final boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
                buildInputMethodListLocked(!imeSelectedOnBoot /* resetDefaultEnabledIme */);
                updateFromSettingsLocked(true);
                InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                        getPackageManagerForUser(mContext, currentUserId),
                        mSettings.getEnabledInputMethodListLocked());
            }
        }
    }

    /**
     * Returns true iff the caller is identified to be the current input method with the token.
     * @param token The window token given to the input method when it was started.
     * @return true if and only if non-null valid token is specified.
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
                    mSettings.getCurrentUserId(), null);
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
                    mSettings.getCurrentUserId(), null);
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
    public boolean isStylusHandwritingAvailableAsUser(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }

        synchronized (ImfLock.class) {
            if (!isStylusHandwritingEnabled(mContext, userId)) {
                return false;
            }

            // Check if selected IME of current user supports handwriting.
            if (userId == mSettings.getCurrentUserId()) {
                return mBindingController.supportsStylusHandwriting();
            }
            //TODO(b/197848765): This can be optimized by caching multi-user methodMaps/methodList.
            //TODO(b/210039666): use cache.
            final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
            final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
            final InputMethodInfo imi = methodMap.get(settings.getSelectedInputMethod());
            return imi != null && imi.supportsStylusHandwriting();
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
        final ArrayList<InputMethodInfo> methodList;
        final InputMethodSettings settings;
        if (userId == mSettings.getCurrentUserId()
                && directBootAwareness == DirectBootAwareness.AUTO) {
            // Create a copy.
            methodList = new ArrayList<>(mMethodList);
            settings = mSettings;
        } else {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodList = new ArrayList<>();
            final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap =
                    new ArrayMap<>();
            AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
            queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap, methodMap,
                    methodList, directBootAwareness);
            settings = new InputMethodSettings(methodMap, userId);
        }
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getEnabledInputMethodListLocked(@UserIdInt int userId,
            int callingUid) {
        final ArrayList<InputMethodInfo> methodList;
        final InputMethodSettings settings;
        if (userId == mSettings.getCurrentUserId()) {
            methodList = mSettings.getEnabledInputMethodListLocked();
            settings = mSettings;
        } else {
            final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
            settings = new InputMethodSettings(methodMap, userId);
            methodList = settings.getEnabledInputMethodListLocked();
        }
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
     * Sets current host input token.
     *
     * @param callerImeToken the token has been made for the current active input method
     * @param hostInputToken the host input token of the current active input method
     */
    void setCurHostInputToken(@NonNull IBinder callerImeToken, @Nullable IBinder hostInputToken) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(callerImeToken)) {
                return;
            }
            mCurHostInputToken = hostInputToken;
        }
    }

    /**
     * Gets enabled subtypes of the specified {@link InputMethodInfo}.
     *
     * @param imiId if null, returns enabled subtypes for the current {@link InputMethodInfo}.
     * @param allowsImplicitlyEnabledSubtypes {@code true} to return the implicitly enabled
     *                                         subtypes.
     * @param userId the user ID to be queried about.
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
        if (userId == mSettings.getCurrentUserId()) {
            final InputMethodInfo imi;
            String selectedMethodId = getSelectedMethodIdLocked();
            if (imiId == null && selectedMethodId != null) {
                imi = mMethodMap.get(selectedMethodId);
            } else {
                imi = mMethodMap.get(imiId);
            }
            if (imi == null || !canCallerAccessInputMethod(
                    imi.getPackageName(), callingUid, userId, mSettings)) {
                return Collections.emptyList();
            }
            return mSettings.getEnabledInputMethodSubtypeListLocked(
                    imi, allowsImplicitlyEnabledSubtypes);
        }
        final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
        final InputMethodInfo imi = methodMap.get(imiId);
        if (imi == null) {
            return Collections.emptyList();
        }
        final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
        if (!canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings)) {
            return Collections.emptyList();
        }
        return settings.getEnabledInputMethodSubtypeListLocked(
                imi, allowsImplicitlyEnabledSubtypes);
    }

    /**
     * Called by each application process as a preparation to start interacting with
     * {@link InputMethodManagerService}.
     *
     * <p>As a general principle, IPCs from the application process that take
     * {@link IInputMethodClient} will be rejected without this step.</p>
     *
     * @param client {@link android.os.Binder} proxy that is associated with the singleton instance
     *               of {@link android.view.inputmethod.InputMethodManager} that runs on the client
     *               process
     * @param inputConnection communication channel for the fallback {@link InputConnection}
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
        synchronized (ImfLock.class) {
            // TODO: Optimize this linear search.
            final int numClients = mClients.size();
            for (int i = 0; i < numClients; ++i) {
                final ClientState state = mClients.valueAt(i);
                if (state.mUid == callerUid && state.mPid == callerPid
                        && state.mSelfReportedDisplayId == selfReportedDisplayId) {
                    throw new SecurityException("uid=" + callerUid + "/pid=" + callerPid
                            + "/displayId=" + selfReportedDisplayId + " is already registered.");
                }
            }
            final ClientDeathRecipient deathRecipient = new ClientDeathRecipient(this, client);
            try {
                client.asBinder().linkToDeath(deathRecipient, 0 /* flags */);
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            }
            // We cannot fully avoid race conditions where the client UID already lost the access to
            // the given self-reported display ID, even if the client is not maliciously reporting
            // a fake display ID. Unconditionally returning SecurityException just because the
            // client doesn't pass display ID verification can cause many test failures hence not an
            // option right now.  At the same time
            //    context.getSystemService(InputMethodManager.class)
            // is expected to return a valid non-null instance at any time if we do not choose to
            // have the client crash.  Thus we do not verify the display ID at all here.  Instead we
            // later check the display ID every time the client needs to interact with the specified
            // display.
            final IInputMethodClientInvoker clientInvoker =
                    IInputMethodClientInvoker.create(client, mHandler);
            mClients.put(client.asBinder(), new ClientState(clientInvoker, inputConnection,
                    callerUid, callerPid, selfReportedDisplayId, deathRecipient));
        }
    }

    void removeClient(IInputMethodClient client) {
        synchronized (ImfLock.class) {
            ClientState cs = mClients.remove(client.asBinder());
            if (cs != null) {
                client.asBinder().unlinkToDeath(cs.mClientDeathRecipient, 0 /* flags */);
                clearClientSessionLocked(cs);
                clearClientSessionForAccessibilityLocked(cs);

                if (mCurClient == cs) {
                    hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */, 0 /* flags */,
                            null /* resultReceiver */, SoftInputShowHideReason.HIDE_REMOVE_CLIENT);
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
                }
                if (mCurFocusedWindowClient == cs) {
                    mCurFocusedWindowClient = null;
                    mCurFocusedWindowEditorInfo = null;
                }
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void unbindCurrentClientLocked(@UnbindReason int unbindClientReason) {
        if (mCurClient != null) {
            if (DEBUG) {
                Slog.v(TAG, "unbindCurrentInputLocked: client="
                        + mCurClient.mClient.asBinder());
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
            mCurClient.mClient.onUnbindMethod(getSequenceNumberLocked(), unbindClientReason);
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
                mCurFocusedWindow);
        if (winState != null && !winState.isRequestedImeVisible()
                && !mVisibilityStateComputer.isInputShown()) {
            // Normally, the focus window will apply the IME visibility state to
            // WindowManager when the IME has applied it. But it would be too late when
            // switching IMEs in between different users. (Since the focused IME will
            // first unbind the service to switch to bind the next user of the IME
            // service, that wouldn't make the attached IME token validity check in time)
            // As a result, we have to notify WM to apply IME visibility before clearing the
            // binding states in the first place.
            mVisibilityApplier.applyImeVisibility(mCurFocusedWindow, mCurStatsToken,
                    STATE_HIDE_IME);
        }
    }

    /** {@code true} when a {@link ClientState} has attached from starting the input connection. */
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
    private boolean isInputShown() {
        return mVisibilityStateComputer.isInputShown();
    }

    @GuardedBy("ImfLock.class")
    private boolean isShowRequestedForCurrentWindow() {
        final ImeTargetWindowState state = mVisibilityStateComputer.getWindowStateOrNull(
                mCurFocusedWindow);
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
        final StartInputInfo info = new StartInputInfo(mSettings.getCurrentUserId(),
                getCurTokenLocked(),
                mCurTokenDisplayId, getCurIdLocked(), startInputReason, restarting,
                UserHandle.getUserId(mCurClient.mUid), mCurClient.mSelfReportedDisplayId,
                mCurFocusedWindow, mCurEditorInfo, mCurFocusedWindowSoftInputMode,
                getSequenceNumberLocked());
        mImeTargetWindowMap.put(startInputToken, mCurFocusedWindow);
        mStartInputHistory.addEntry(info);

        // Seems that PackageManagerInternal#grantImplicitAccess() doesn't handle cross-user
        // implicit visibility (e.g. IME[user=10] -> App[user=0]) thus we do this only for the
        // same-user scenarios.
        // That said ignoring cross-user scenario will never affect IMEs that do not have
        // INTERACT_ACROSS_USERS(_FULL) permissions, which is actually almost always the case.
        if (mSettings.getCurrentUserId() == UserHandle.getUserId(mCurClient.mUid)) {
            mPackageManagerInternal.grantImplicitAccess(mSettings.getCurrentUserId(),
                    null /* intent */, UserHandle.getAppId(getCurMethodUidLocked()),
                    mCurClient.mUid, true /* direct */);
        }

        @InputMethodNavButtonFlags
        final int navButtonFlags = getInputMethodNavButtonFlagsLocked();
        final SessionState session = mCurClient.mCurSession;
        setEnabledSessionLocked(session);
        session.mMethod.startInput(startInputToken, mCurInputConnection, mCurEditorInfo, restarting,
                navButtonFlags, mCurImeDispatcher);
        if (isShowRequestedForCurrentWindow()) {
            if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
            // Re-use current statsToken, if it exists.
            final ImeTracker.Token statsToken = mCurStatsToken;
            mCurStatsToken = null;
            showCurrentInputLocked(mCurFocusedWindow, statsToken,
                    mVisibilityStateComputer.getShowFlags(),
                    null /* resultReceiver */, SoftInputShowHideReason.ATTACH_NEW_INPUT);
        }

        String curId = getCurIdLocked();
        final InputMethodInfo curInputMethodInfo = mMethodMap.get(curId);
        final boolean suppressesSpellChecker =
                curInputMethodInfo != null && curInputMethodInfo.suppressesSpellChecker();
        final SparseArray<IAccessibilityInputMethodSession> accessibilityInputMethodSessions =
                createAccessibilityInputMethodSessions(mCurClient.mAccessibilitySessions);
        if (mBindingController.supportsStylusHandwriting() && hasSupportedStylusLocked()) {
            mHwController.setInkWindowInitializer(new InkWindowInitializer());
        }
        return new InputBindResult(InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                session.mSession, accessibilityInputMethodSessions,
                (session.mChannel != null ? session.mChannel.dup() : null),
                curId, getSequenceNumberLocked(), suppressesSpellChecker);
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
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
        String selectedMethodId = getSelectedMethodIdLocked();

        if (!mSystemReady) {
            // If the system is not yet ready, we shouldn't be running third
            // party code.
            return new InputBindResult(
                    InputBindResult.ResultCode.ERROR_SYSTEM_NOT_READY,
                    null, null, null, selectedMethodId, getSequenceNumberLocked(), false);
        }

        if (!InputMethodUtils.checkIfPackageBelongsToUid(mPackageManagerInternal, cs.mUid,
                editorInfo.packageName)) {
            Slog.e(TAG, "Rejecting this client as it reported an invalid package name."
                    + " uid=" + cs.mUid + " package=" + editorInfo.packageName);
            return InputBindResult.INVALID_PACKAGE_NAME;
        }

        // Compute the final shown display ID with validated cs.selfReportedDisplayId for this
        // session & other conditions.
        ImeTargetWindowState winState = mVisibilityStateComputer.getWindowStateOrNull(
                mCurFocusedWindow);
        if (winState == null) {
            return InputBindResult.NOT_IME_TARGET_WINDOW;
        }
        final int csDisplayId = cs.mSelfReportedDisplayId;
        final int oldDisplayIdToShowIme = mDisplayIdToShowIme;
        mDisplayIdToShowIme = mVisibilityStateComputer.computeImeDisplayId(winState, csDisplayId);

        // Potentially override the selected input method if the new display belongs to a virtual
        // device with a custom IME.
        if (oldDisplayIdToShowIme != mDisplayIdToShowIme) {
            final String deviceMethodId = computeCurrentDeviceMethodIdLocked(selectedMethodId);
            if (deviceMethodId == null) {
                mVisibilityStateComputer.getImePolicy().setImeHiddenByDisplayPolicy(true);
            } else if (!Objects.equals(deviceMethodId, selectedMethodId)) {
                setInputMethodLocked(deviceMethodId, NOT_A_SUBTYPE_ID, mDeviceIdToShowIme);
                selectedMethodId = deviceMethodId;
            }
        }

        if (mVisibilityStateComputer.getImePolicy().isImeHiddenByDisplayPolicy()) {
            hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */, 0 /* flags */,
                    null /* resultReceiver */,
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

        // Bump up the sequence for this client and attach it.
        advanceSequenceNumberLocked();
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

        // If configured, we want to avoid starting up the IME if it is not supposed to be showing
        if (shouldPreventImeStartupLocked(selectedMethodId, startInputFlags,
                unverifiedTargetSdkVersion)) {
            if (DEBUG) {
                Slog.d(TAG, "Avoiding IME startup and unbinding current input method.");
            }
            invalidateAutofillSessionLocked();
            mBindingController.unbindCurrentMethod();
            return InputBindResult.NO_EDITOR;
        }

        // Check if the input method is changing.
        // We expect the caller has already verified that the client is allowed to access this
        // display ID.
        if (isSelectedMethodBoundLocked()) {
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

            InputBindResult bindResult = tryReuseConnectionLocked(cs);
            if (bindResult != null) {
                return bindResult;
            }
        }

        mBindingController.unbindCurrentMethod();

        return mBindingController.bindCurrentMethod();
    }

    /**
     * Update the current deviceId and return the relevant imeId for this device.
     *   1. If the device changes to virtual and its custom IME is not available, then disable IME.
     *   2. If the device changes to virtual with valid custom IME, then return the custom IME. If
     *      the old device was default, then store the current imeId so it can be restored.
     *   3. If the device changes to default, restore the default device IME.
     *   4. Otherwise keep the current imeId.
     */
    @GuardedBy("ImfLock.class")
    private String computeCurrentDeviceMethodIdLocked(String currentMethodId) {
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal == null || !android.companion.virtual.flags.Flags.vdmCustomIme()) {
            return currentMethodId;
        }

        final int oldDeviceId = mDeviceIdToShowIme;
        mDeviceIdToShowIme = mVdmInternal.getDeviceIdForDisplayId(mDisplayIdToShowIme);
        if (mDeviceIdToShowIme == oldDeviceId) {
            return currentMethodId;
        }
        if (mDeviceIdToShowIme == DEVICE_ID_DEFAULT) {
            final String defaultDeviceMethodId = mSettings.getSelectedDefaultDeviceInputMethod();
            if (DEBUG) {
                Slog.v(TAG, "Restoring default device input method: " + defaultDeviceMethodId);
            }
            return defaultDeviceMethodId;
        }

        final String deviceMethodId =
                mVirtualDeviceMethodMap.get(mDeviceIdToShowIme, currentMethodId);
        if (Objects.equals(deviceMethodId, currentMethodId)) {
            return currentMethodId;
        } else if (!mMethodMap.containsKey(deviceMethodId)) {
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
            mSettings.putSelectedDefaultDeviceInputMethod(currentMethodId);
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
        final InputMethodInfo imi = mMethodMap.get(selectedMethodId);
        if (imi == null) {
            return false;
        }
        if (ArrayUtils.contains(mNonPreemptibleInputMethods, imi.getPackageName())) {
            return false;
        }
        return true;
    }

    @GuardedBy("ImfLock.class")
    private boolean isSelectedMethodBoundLocked() {
        String curId = getCurIdLocked();
        return curId != null && curId.equals(getSelectedMethodIdLocked())
                && mDisplayIdToShowIme == mCurTokenDisplayId;
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
    private InputBindResult tryReuseConnectionLocked(@NonNull ClientState cs) {
        if (hasConnectionLocked()) {
            if (getCurMethodLocked() != null) {
                // Return to client, and we will get back with it when
                // we have had a session made for it.
                requestClientSessionLocked(cs);
                requestClientSessionForAccessibilityLocked(cs);
                return new InputBindResult(
                        InputBindResult.ResultCode.SUCCESS_WAITING_IME_SESSION,
                        null, null, null, getCurIdLocked(), getSequenceNumberLocked(), false);
            } else {
                long bindingDuration = SystemClock.uptimeMillis() - getLastBindTimeLocked();
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
                            null, null, null, getCurIdLocked(), getSequenceNumberLocked(), false);
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
        @DisplayImePolicy int getDisplayImePolicy(int displayId);
    }

    /**
     * Find the display where the IME should be shown.
     *
     * @param displayId the ID of the display where the IME client target is.
     * @param checker instance of {@link ImeDisplayValidator} which is used for
     *                checking display config to adjust the final target display.
     * @return The ID of the display where the IME should be shown or
     *         {@link android.view.Display#INVALID_DISPLAY} if the display has an ImePolicy of
     *         {@link WindowManager#DISPLAY_IME_POLICY_HIDE}.
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
                    + mCurTokenDisplayId);
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
                        mCurClient.mCurSession = new SessionState(mCurClient,
                                method, session, channel);
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
        mCurTokenDisplayId = INVALID_DISPLAY;
        mCurHostInputToken = null;
    }

    @GuardedBy("ImfLock.class")
    void resetCurrentMethodAndClientLocked(@UnbindReason int unbindClientReason) {
        setSelectedMethodIdLocked(null);
        // Callback before clean-up binding states.
        onUnbindCurrentMethodByReset();
        mBindingController.unbindCurrentMethod();
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
            final int numClients = mClients.size();
            for (int i = 0; i < numClients; ++i) {
                clearClientSessionLocked(mClients.valueAt(i));
                clearClientSessionForAccessibilityLocked(mClients.valueAt(i));
            }

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
                            getPackageManagerForUser(mContext, mSettings.getCurrentUserId());
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
                                contentDescription  != null
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
        final boolean hasNavigationBar = mWindowManagerInternal
                .hasNavigationBar(mCurTokenDisplayId != INVALID_DISPLAY
                        ? mCurTokenDisplayId : DEFAULT_DISPLAY);
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
        if (!Objects.equals(getCurIdLocked(), getSelectedMethodIdLocked())) {
            return false;
        }
        if (mWindowManagerInternal.isKeyguardShowingAndNotOccluded()
                && mWindowManagerInternal.isKeyguardSecure(mSettings.getCurrentUserId())) {
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

        List<InputMethodInfo> imes = mSettings.getEnabledInputMethodListWithFilterLocked(
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
                    mSettings.getEnabledInputMethodSubtypeListLocked(imi, true);
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
            if (mCurTokenDisplayId != topFocusedDisplayId
                    && mCurTokenDisplayId != FALLBACK_DISPLAY_ID) {
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
                    + " displayId: " + mCurTokenDisplayId);
        }

        // TODO: Move this clearing calling identity block to setImeWindowStatus after making sure
        // all updateSystemUi happens on system privilege.
        final long ident = Binder.clearCallingIdentity();
        try {
            if (!mCurPerceptible) {
                if ((vis & InputMethodService.IME_VISIBLE) != 0) {
                    vis &= ~InputMethodService.IME_VISIBLE;
                    vis |= InputMethodService.IME_VISIBLE_IMPERCEPTIBLE;
                }
            } else {
                vis &= ~InputMethodService.IME_VISIBLE_IMPERCEPTIBLE;
            }
            if (mMenuController.getSwitchingDialogLocked() != null
                    || !Objects.equals(getCurIdLocked(), getSelectedMethodIdLocked())) {
                // When the IME switcher dialog is shown, or we are switching IMEs,
                // the back button should be in the default state (as if the IME is not shown).
                backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING;
            }
            final boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis);
            if (mStatusBarManagerInternal != null) {
                mStatusBarManagerInternal.setImeWindowStatus(mCurTokenDisplayId,
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

    @GuardedBy("ImfLock.class")
    void updateInputMethodsFromSettingsLocked(boolean enabledMayChange) {
        if (enabledMayChange) {
            final PackageManager userAwarePackageManager = getPackageManagerForUser(mContext,
                    mSettings.getCurrentUserId());

            List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
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
        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String id = mSettings.getSelectedInputMethod();
        // There is no input method selected, try to choose new applicable input method.
        if (TextUtils.isEmpty(id) && chooseNewDefaultIMELocked()) {
            id = mSettings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(id)) {
            try {
                setInputMethodLocked(id, mSettings.getSelectedInputMethodSubtypeId(id));
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e);
                resetCurrentMethodAndClientLocked(UnbindReason.SWITCH_IME_FAILED);
            }
        } else {
            // There is no longer an input method set, so stop any current one.
            resetCurrentMethodAndClientLocked(UnbindReason.NO_IME);
        }

        // TODO: Instantiate mSwitchingController for each user.
        if (mSettings.getCurrentUserId() == mSwitchingController.getUserId()) {
            mSwitchingController.resetCircularListLocked(mMethodMap);
        } else {
            mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(
                    mContext, mMethodMap, mSettings.getCurrentUserId());
        }
        // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
        if (mSettings.getCurrentUserId() == mHardwareKeyboardShortcutController.getUserId()) {
            mHardwareKeyboardShortcutController.reset(mMethodMap);
        } else {
            mHardwareKeyboardShortcutController = new HardwareKeyboardShortcutController(
                    mMethodMap, mSettings.getCurrentUserId());
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
        InputMethodInfo info = mMethodMap.get(id);
        if (info == null) {
            throw getExceptionForUnknownImeId(id);
        }

        // See if we need to notify a subtype change within the same IME.
        if (id.equals(getSelectedMethodIdLocked())) {
            final int userId = mSettings.getCurrentUserId();
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
            mSettings.putSelectedDefaultDeviceInputMethod(id);
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
            setSelectedMethodIdLocked(id);

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
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickTooType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showSoftInput");
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput");
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
                return showCurrentInputLocked(windowToken, statsToken, flags, lastClickTooType,
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

    private void startStylusHandwriting(IInputMethodClient client, boolean usesDelegation) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.startStylusHandwriting");
        try {
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startStylusHandwriting");
            int uid = Binder.getCallingUid();
            synchronized (ImfLock.class) {
                if (!usesDelegation) {
                    mHwController.clearPendingHandwritingDelegation();
                }
                if (!canInteractWithImeLocked(uid, client, "startStylusHandwriting",
                        null /* statsToken */)) {
                    return;
                }
                if (!hasSupportedStylusLocked()) {
                    Slog.w(TAG, "No supported Stylus hardware found on device. Ignoring"
                            + " startStylusHandwriting()");
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    if (!mBindingController.supportsStylusHandwriting()) {
                        Slog.w(TAG,
                                "Stylus HW unsupported by IME. Ignoring startStylusHandwriting()");
                        return;
                    }

                    final OptionalInt requestId = mHwController.getCurrentRequestId();
                    if (!requestId.isPresent()) {
                        Slog.e(TAG, "Stylus handwriting was not initialized.");
                        return;
                    }
                    if (!mHwController.isStylusGestureOngoing()) {
                        Slog.e(TAG,
                                "There is no ongoing stylus gesture to start stylus handwriting.");
                        return;
                    }
                    if (mHwController.hasOngoingStylusHandwritingSession()) {
                        // prevent duplicate calls to startStylusHandwriting().
                        Slog.e(TAG,
                                "Stylus handwriting session is already ongoing."
                                        + " Ignoring startStylusHandwriting().");
                        return;
                    }
                    if (DEBUG) Slog.v(TAG, "Client requesting Stylus Handwriting to be started");
                    final IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod != null) {
                        curMethod.canStartStylusHandwriting(requestId.getAsInt());
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
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
        if (!verifyClientAndPackageMatch(client, delegatorPackageName)) {
            Slog.w(TAG, "prepareStylusHandwritingDelegation() fail");
            throw new IllegalArgumentException("Delegator doesn't match Uid");
        }
        schedulePrepareStylusHandwritingDelegation(
                userId, delegatePackageName, delegatorPackageName);
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

        startStylusHandwriting(client, true /* usesDelegation */);
        return true;
    }

    private boolean verifyClientAndPackageMatch(
            @NonNull IInputMethodClient client, @NonNull String packageName) {
        ClientState cs;
        synchronized (ImfLock.class) {
            cs = mClients.get(client.asBinder());
        }
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        }
        return InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, cs.mUid, packageName);
    }

    private boolean verifyDelegator(
            @NonNull IInputMethodClient client,
            @NonNull String delegatePackageName,
            @NonNull String delegatorPackageName,
            @InputMethodManager.HandwritingDelegateFlags int flags) {
        if (!verifyClientAndPackageMatch(client, delegatePackageName)) {
            Slog.w(TAG, "Delegate package does not belong to the same user. Ignoring"
                    + " startStylusHandwriting");
            return false;
        }
        synchronized (ImfLock.class) {
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
                if (mCurFocusedWindow != windowToken || mCurPerceptible == perceptible) {
                    return;
                }
                mCurPerceptible = perceptible;
                updateSystemUiLocked();
            }
        });
    }

    @GuardedBy("ImfLock.class")
    boolean showCurrentInputLocked(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @InputMethodManager.ShowFlags int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        return showCurrentInputLocked(windowToken, statsToken, flags,
                MotionEvent.TOOL_TYPE_UNKNOWN, resultReceiver, reason);
    }

    @GuardedBy("ImfLock.class")
    private boolean showCurrentInputLocked(IBinder windowToken,
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        // Create statsToken is none exists.
        if (statsToken == null) {
            statsToken = createStatsTokenForFocusedClient(true /* show */,
                    ImeTracker.ORIGIN_SERVER_START_INPUT, reason);
        }

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
        mBindingController.setCurrentMethodVisible();
        final IInputMethodInvoker curMethod = getCurMethodLocked();
        ImeTracker.forLogging().onCancelled(mCurStatsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
        if (curMethod != null) {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_HAS_IME);
            mCurStatsToken = null;

            if (!Flags.useHandwritingListenerForTooltype()
                    && lastClickToolType != MotionEvent.TOOL_TYPE_UNKNOWN) {
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
            @Nullable ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput");
        synchronized (ImfLock.class) {
            if (!canInteractWithImeLocked(uid, client, "hideSoftInput", statsToken)) {
                if (isInputShown()) {
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

    @GuardedBy("ImfLock.class")
    boolean hideCurrentInputLocked(IBinder windowToken, @Nullable ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        // Create statsToken is none exists.
        if (statsToken == null) {
            statsToken = createStatsTokenForFocusedClient(false /* show */,
                    ImeTracker.ORIGIN_SERVER_HIDE_INPUT, reason);
        }

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
                && (isInputShown() || (mImeWindowVis & InputMethodService.IME_ACTIVE) != 0);

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
        mBindingController.setCurrentMethodNotVisible();
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
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "IMMS.startInputOrWindowGainedFocus");
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startInputOrWindowGainedFocus");
            final InputBindResult result;
            synchronized (ImfLock.class) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    result = startInputOrWindowGainedFocusInternalLocked(startInputReason,
                            client, windowToken, startInputFlags, softInputMode, windowFlags,
                            editorInfo, inputConnection, remoteAccessibilityInputConnection,
                            unverifiedTargetSdkVersion, userId, imeDispatcher);
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
            int unverifiedTargetSdkVersion, @UserIdInt int userId,
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher) {
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
                    + " userId=" + userId
                    + " imeDispatcher=" + imeDispatcher);
        }

        if (!mUserManagerInternal.isUserRunning(userId)) {
            // There is a chance that we hit here because of race condition. Let's just
            // return an error code instead of crashing the caller process, which at
            // least has INTERACT_ACROSS_USERS_FULL permission thus is likely to be an
            // important process.
            Slog.w(TAG, "User #" + userId + " is not running.");
            return InputBindResult.INVALID_USER;
        }

        final ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        }

        final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                windowToken, cs.mUid, cs.mPid, cs.mSelfReportedDisplayId);
        switch (imeClientFocus) {
            case WindowManagerInternal.ImeClientFocusResult.DISPLAY_ID_MISMATCH:
                Slog.e(TAG, "startInputOrWindowGainedFocusInternal: display ID mismatch.");
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

        if (mUserSwitchHandlerTask != null) {
            // There is already an on-going pending user switch task.
            final int nextUserId = mUserSwitchHandlerTask.mToUserId;
            if (userId == nextUserId) {
                scheduleSwitchUserTaskLocked(userId, cs.mClient);
                return InputBindResult.USER_SWITCHING;
            }
            final int[] profileIdsWithDisabled = mUserManagerInternal.getProfileIds(
                    mSettings.getCurrentUserId(), false /* enabledOnly */);
            for (int profileId : profileIdsWithDisabled) {
                if (profileId == userId) {
                    scheduleSwitchUserTaskLocked(userId, cs.mClient);
                    return InputBindResult.USER_SWITCHING;
                }
            }
            return InputBindResult.INVALID_USER;
        }

        final boolean shouldClearFlag = mImePlatformCompatUtils.shouldClearShowForcedFlag(cs.mUid);
        // In case mShowForced flag affects the next client to keep IME visible, when the current
        // client is leaving due to the next focused client, we clear mShowForced flag when the
        // next client's targetSdkVersion is T or higher.
        final boolean showForced = mVisibilityStateComputer.mShowForced;
        if (mCurFocusedWindow != windowToken && showForced && shouldClearFlag) {
            mVisibilityStateComputer.mShowForced = false;
        }

        final int currentUserId = mSettings.getCurrentUserId();
        if (userId != currentUserId) {
            if (ArrayUtils.contains(
                    mUserManagerInternal.getProfileIds(currentUserId, false), userId)) {
                // cross-profile access is always allowed here to allow profile-switching.
                scheduleSwitchUserTaskLocked(userId, cs.mClient);
                return InputBindResult.USER_SWITCHING;
            }
            Slog.w(TAG, "A background user is requesting window. Hiding IME.");
            Slog.w(TAG, "If you need to impersonate a foreground user/profile from"
                    + " a background user, use EditorInfo.targetInputMethodUser with"
                    + " INTERACT_ACROSS_USERS_FULL permission.");
            hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */, 0 /* flags */,
                    null /* resultReceiver */, SoftInputShowHideReason.HIDE_INVALID_USER);
            return InputBindResult.INVALID_USER;
        }

        final boolean sameWindowFocused = mCurFocusedWindow == windowToken;
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
                        startInputReason, unverifiedTargetSdkVersion, imeDispatcher);
            }
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                    null, null, null, null, -1, false);
        }

        mCurFocusedWindow = windowToken;
        mCurFocusedWindowSoftInputMode = softInputMode;
        mCurFocusedWindowClient = cs;
        mCurFocusedWindowEditorInfo = editorInfo;
        mCurPerceptible = true;

        // We want to start input before showing the IME, but after closing
        // it.  We want to do this after closing it to help the IME disappear
        // more quickly (not get stuck behind it initializing itself for the
        // new focused input, even if its window wants to hide the IME).
        boolean didStart = false;
        InputBindResult res = null;

        final ImeVisibilityResult imeVisRes = mVisibilityStateComputer.computeState(windowState,
                isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags));
        if (imeVisRes != null) {
            switch (imeVisRes.getReason()) {
                case SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY:
                case SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV:
                case SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE:
                    if (editorInfo != null) {
                        res = startInputUncheckedLocked(cs, inputContext,
                                remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                                startInputReason, unverifiedTargetSdkVersion,
                                imeDispatcher);
                        didStart = true;
                    }
                    break;
            }

            mVisibilityApplier.applyImeVisibility(mCurFocusedWindow, null /* statsToken */,
                    imeVisRes.getState(), imeVisRes.getReason());

            if (imeVisRes.getReason() == SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW) {
                // If focused display changed, we should unbind current method
                // to make app window in previous display relayout after Ime
                // window token removed.
                // Note that we can trust client's display ID as long as it matches
                // to the display ID obtained from the window.
                if (cs.mSelfReportedDisplayId != mCurTokenDisplayId) {
                    mBindingController.unbindCurrentMethod();
                }
            }
        }
        if (!didStart) {
            if (editorInfo != null) {
                res = startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion,
                        imeDispatcher);
            } else {
                res = InputBindResult.NULL_EDITOR_INFO;
            }
        }
        return res;
    }

    @GuardedBy("ImfLock.class")
    private void showCurrentInputImplicitLocked(@NonNull IBinder windowToken,
            @SoftInputShowHideReason int reason) {
        showCurrentInputLocked(windowToken, null /* statsToken */, InputMethodManager.SHOW_IMPLICIT,
                null /* resultReceiver */, reason);
    }

    @GuardedBy("ImfLock.class")
    private boolean canInteractWithImeLocked(int uid, IInputMethodClient client, String methodName,
            @Nullable ImeTracker.Token statsToken) {
        if (mCurClient == null || client == null
                || mCurClient.mClient.asBinder() != client.asBinder()) {
            // We need to check if this is the current client with
            // focus in the window manager, to allow this call to
            // be made before input is started in it.
            final ClientState cs = mClients.get(client.asBinder());
            if (cs == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
                throw new IllegalArgumentException("unknown client " + client.asBinder());
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
            if (!isImeClientFocused(mCurFocusedWindow, cs)) {
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
        if (mCurFocusedWindowClient != null && client != null
                && mCurFocusedWindowClient.mClient.asBinder() == client.asBinder()) {
            return true;
        }
        if (mSettings.getCurrentUserId() != UserHandle.getUserId(uid)) {
            return false;
        }
        if (getCurIntentLocked() != null && InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal,
                uid,
                getCurIntentLocked().getComponent().getPackageName())) {
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

    @EnforcePermission(Manifest.permission.WRITE_SECURE_SETTINGS)
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        // Always call subtype picker, because subtype picker is a superset of input method
        // picker.
        super.showInputMethodPickerFromSystem_enforcePermission();

        mHandler.obtainMessage(MSG_SHOW_IM_SUBTYPE_PICKER, auxiliarySubtypeMode, displayId)
                .sendToTarget();
    }

    /**
     * A test API for CTS to make sure that the input method menu is showing.
     */
    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isInputMethodPickerShownForTest() {
        super.isInputMethodPickerShownForTest_enforcePermission();

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
            final InputMethodInfo imi = mMethodMap.get(id);
            if (imi == null || !canCallerAccessInputMethod(
                    imi.getPackageName(), callingUid, userId, mSettings)) {
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
            final InputMethodInfo imi = mMethodMap.get(id);
            if (imi == null || !canCallerAccessInputMethod(
                    imi.getPackageName(), callingUid, userId, mSettings)) {
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
            final Pair<String, String> lastIme = mSettings.getLastInputMethodAndSubtypeLocked();
            final InputMethodInfo lastImi;
            if (lastIme != null) {
                lastImi = mMethodMap.get(lastIme.first);
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
                final List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
                if (enabled != null) {
                    final int enabledCount = enabled.size();
                    final String locale;
                    if (mCurrentSubtype != null
                            && !TextUtils.isEmpty(mCurrentSubtype.getLocale())) {
                        locale = mCurrentSubtype.getLocale();
                    } else {
                        locale = SystemLocaleWrapper.get(mSettings.getCurrentUserId()).get(0)
                                .toString();
                    }
                    for (int i = 0; i < enabledCount; ++i) {
                        final InputMethodInfo imi = enabled.get(i);
                        if (imi.getSubtypeCount() > 0 && imi.isSystem()) {
                            InputMethodSubtype keyboardSubtype =
                                    SubtypeUtils.findLastResortApplicableSubtypeLocked(
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
        final ImeSubtypeListItem nextSubtype = mSwitchingController.getNextInputMethodLocked(
                onlyCurrentIme, mMethodMap.get(getSelectedMethodIdLocked()), mCurrentSubtype);
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
            final ImeSubtypeListItem nextSubtype = mSwitchingController.getNextInputMethodLocked(
                    false /* onlyCurrentIme */, mMethodMap.get(getSelectedMethodIdLocked()),
                    mCurrentSubtype);
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
            if (mSettings.getCurrentUserId() == userId) {
                return mSettings.getLastInputMethodSubtypeLocked();
            }

            final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
            final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
            return settings.getLastInputMethodSubtypeLocked();
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

            if (mSettings.getCurrentUserId() == userId) {
                if (!mSettings.setAdditionalInputMethodSubtypes(imiId, toBeAdded,
                        mAdditionalSubtypeMap, mPackageManagerInternal, callingUid)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    buildInputMethodListLocked(false /* resetDefaultEnabledIme */);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
                return;
            }

            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            final ArrayList<InputMethodInfo> methodList = new ArrayList<>();
            final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap =
                    new ArrayMap<>();
            AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
            queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap, methodMap,
                    methodList, DirectBootAwareness.AUTO);
            final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
            settings.setAdditionalInputMethodSubtypes(imiId, toBeAdded, additionalSubtypeMap,
                    mPackageManagerInternal, callingUid);
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
                final boolean currentUser = (mSettings.getCurrentUserId() == userId);
                final InputMethodSettings settings = currentUser
                        ? mSettings
                        : new InputMethodSettings(queryMethodMapForUser(userId), userId);
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
     *
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
                    if (!mLoggedDeniedGetInputMethodWindowVisibleHeightForUid.get(callingUid)) {
                        EventLog.writeEvent(0x534e4554, "204906124", callingUid, "");
                        mLoggedDeniedGetInputMethodWindowVisibleHeightForUid.put(callingUid, true);
                    }
                    return 0;
                }
                // This should probably use the caller's display id, but because this is unsupported
                // and maintained only for compatibility, there's no point in fixing it.
                curTokenDisplayId = mCurTokenDisplayId;
            }
            return mWindowManagerInternal.getInputMethodWindowVisibleHeight(curTokenDisplayId);
        });
    }

    @EnforcePermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW)
    @Override
    public void removeImeSurface() {
        super.removeImeSurface_enforcePermission();

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
        if (!mHwController.getCurrentRequestId().isPresent()
                && mBindingController.supportsStylusHandwriting()) {
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
    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void addVirtualStylusIdForTestSession(IInputMethodClient client) {
        super.addVirtualStylusIdForTestSession_enforcePermission();

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
     * @param timeout to set in milliseconds. To reset to default, use a value <= zero.
     */
    @BinderThread
    @EnforcePermission(Manifest.permission.TEST_INPUT_METHOD)
    @Override
    public void setStylusWindowIdleTimeoutForTest(
            IInputMethodClient client, @DurationMillisLong long timeout) {
        super.setStylusWindowIdleTimeoutForTest_enforcePermission();

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
            if (!im.isInputDeviceEnabled(id)) {
                continue;
            }
            InputDevice device = im.getInputDevice(id);
            if (device != null && isStylusDevice(device)) {
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
    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void startImeTrace() {
        super.startImeTrace_enforcePermission();

        ImeTracing.getInstance().startTrace(null /* printwriter */);
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                state.mClient.setImeTraceEnabled(true /* enabled */);
            }
        }
    }

    @BinderThread
    @EnforcePermission(Manifest.permission.CONTROL_UI_TRACING)
    @Override
    public void stopImeTrace() {
        super.stopImeTrace_enforcePermission();

        ImeTracing.getInstance().stopTrace(null /* printwriter */);
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                state.mClient.setImeTraceEnabled(false /* enabled */);
            }
        }
    }

    private void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (ImfLock.class) {
            final long token = proto.start(fieldId);
            proto.write(CUR_METHOD_ID, getSelectedMethodIdLocked());
            proto.write(CUR_SEQ, getSequenceNumberLocked());
            proto.write(CUR_CLIENT, Objects.toString(mCurClient));
            proto.write(CUR_FOCUSED_WINDOW_NAME,
                    mWindowManagerInternal.getWindowName(mCurFocusedWindow));
            proto.write(LAST_IME_TARGET_WINDOW_NAME,
                    mWindowManagerInternal.getWindowName(mLastImeTargetWindow));
            proto.write(CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE,
                    InputMethodDebug.softInputModeToString(mCurFocusedWindowSoftInputMode));
            if (mCurEditorInfo != null) {
                mCurEditorInfo.dumpDebug(proto, CUR_ATTRIBUTE);
            }
            proto.write(CUR_ID, getCurIdLocked());
            mVisibilityStateComputer.dumpDebug(proto, fieldId);
            proto.write(IN_FULLSCREEN_MODE, mInFullscreenMode);
            proto.write(CUR_TOKEN, Objects.toString(getCurTokenLocked()));
            proto.write(CUR_TOKEN_DISPLAY_ID, mCurTokenDisplayId);
            proto.write(SYSTEM_READY, mSystemReady);
            proto.write(LAST_SWITCH_USER_ID, mLastSwitchUserId);
            proto.write(HAVE_CONNECTION, hasConnectionLocked());
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
            if (mSettings.getCurrentUserId() != mSwitchingController.getUserId()) {
                return;
            }
            final InputMethodInfo imi = mMethodMap.get(getSelectedMethodIdLocked());
            if (imi != null) {
                mSwitchingController.onUserActionLocked(imi, mCurrentSubtype);
            }
        }
    }

    @BinderThread
    private void applyImeVisibility(IBinder token, IBinder windowToken, boolean setVisible,
            @Nullable ImeTracker.Token statsToken) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.applyImeVisibility");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_APPLY_IME_VISIBILITY);
                    return;
                }
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
            if (mMethodMap.get(id) != null
                    && mSettings.getEnabledInputMethodListWithFilterLocked(
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
                        show, mCurFocusedWindow, requestToken, mCurTokenDisplayId);
        mSoftInputShowHideHistory.addEntry(new SoftInputShowHideHistory.Entry(
                mCurFocusedWindowClient, mCurFocusedWindowEditorInfo, info.focusedWindowName,
                mCurFocusedWindowSoftInputMode, reason, mInFullscreenMode,
                info.requestWindowName, info.imeControlTargetName, info.imeLayerTargetName,
                info.imeSurfaceParentName));

        if (statsToken != null) {
            mImeTrackerService.onImmsUpdate(statsToken, info.requestWindowName);
        }
    }

    @BinderThread
    private void hideMySoftInput(@NonNull IBinder token, @InputMethodManager.HideFlags int flags,
            @SoftInputShowHideReason int reason) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideMySoftInput");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    hideCurrentInputLocked(mLastImeTargetWindow, null /* statsToken */, flags,
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
    private void showMySoftInput(@NonNull IBinder token, @InputMethodManager.ShowFlags int flags) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showMySoftInput");
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(token)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    showCurrentInputLocked(mLastImeTargetWindow, null /* statsToken */, flags,
                            null /* resultReceiver */,
                            SoftInputShowHideReason.SHOW_SOFT_INPUT_FROM_IME);
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

    void onApplyImeVisibilityFromComputer(IBinder windowToken,
            @NonNull ImeVisibilityResult result) {
        synchronized (ImfLock.class) {
            mVisibilityApplier.applyImeVisibility(windowToken, null, result.getState(),
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
                AccessibilitySessionState sessionState  = mEnabledAccessibilitySessions.valueAt(i);
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
                            showAuxSubtypes = isInputShown();
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
                    final boolean isScreenLocked = mWindowManagerInternal.isKeyguardLocked()
                            && mWindowManagerInternal.isKeyguardSecure(
                                    mSettings.getCurrentUserId());
                    final String lastInputMethodId = mSettings.getSelectedInputMethod();
                    int lastInputMethodSubtypeId =
                            mSettings.getSelectedInputMethodSubtypeId(lastInputMethodId);

                    final List<ImeSubtypeListItem> imList = InputMethodSubtypeSwitchingController
                            .getSortedInputMethodAndSubtypeList(
                                    showAuxSubtypes, isScreenLocked, false, mContext,
                                    mMethodMap, mSettings.getCurrentUserId());
                    mMenuController.showInputMethodMenuLocked(showAuxSubtypes, displayId,
                            lastInputMethodId, lastInputMethodSubtypeId, imList);
                }
                return true;

            // ---------------------------------------------------------

            case MSG_HIDE_ALL_INPUT_METHODS:
                synchronized (ImfLock.class) {
                    final @SoftInputShowHideReason int reason = (int) msg.obj;
                    hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */, 0 /* flags */,
                            null /* resultReceiver */, reason);

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
                        if (windowToken == mCurFocusedWindow
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
                    if (mBindingController.supportsStylusHandwriting()
                            && getCurMethodLocked() != null && hasSupportedStylusLocked()) {
                        Slog.d(TAG, "Initializing Handwriting Spy");
                        mHwController.initializeHandwritingSpy(mCurTokenDisplayId);
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
                    mHwController.prepareStylusHandwritingDelegation(userId, delegate, delegator);
                }
                return true;
            case MSG_START_HANDWRITING:
                synchronized (ImfLock.class) {
                    IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod == null || mCurFocusedWindow == null) {
                        return true;
                    }
                    final HandwritingModeController.HandwritingSession session =
                            mHwController.startHandwritingSession(
                                    msg.arg1 /*requestId*/,
                                    msg.arg2 /*pid*/,
                                    mBindingController.getCurMethodUid(),
                                    mCurFocusedWindow);
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
            if (mImePlatformCompatUtils.shouldUseSetInteractiveProtocol(getCurMethodUidLocked())) {
                // Handle IME visibility when interactive changed before finishing the input to
                // ensure we preserve the last state as possible.
                final ImeVisibilityResult imeVisRes = mVisibilityStateComputer.onInteractiveChanged(
                        mCurFocusedWindow, interactive);
                if (imeVisRes != null) {
                    mVisibilityApplier.applyImeVisibility(mCurFocusedWindow, null,
                            imeVisRes.getState(), imeVisRes.getReason());
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
        final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(
                mSettings.getEnabledInputMethodListLocked());
        if (imi != null) {
            if (DEBUG) {
                Slog.d(TAG, "New default IME was selected: " + imi.getId());
            }
            resetSelectedInputMethodAndSubtypeLocked(imi.getId());
            return true;
        }

        return false;
    }

    static void queryInputMethodServicesInternal(Context context,
            @UserIdInt int userId, ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap,
            ArrayMap<String, InputMethodInfo> methodMap, ArrayList<InputMethodInfo> methodList,
            @DirectBootAwareness int directBootAwareness) {
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);

        methodList.clear();
        methodMap.clear();

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

        methodList.ensureCapacity(services.size());
        methodMap.ensureCapacity(services.size());

        // Note: This is a temporary solution for Bug 261723412.  If there is any better solution,
        // we should remove this data dependency.
        final List<String> enabledInputMethodList =
                InputMethodUtils.getEnabledInputMethodIdsForFiltering(context, userId);

        filterInputMethodServices(additionalSubtypeMap, methodMap, methodList,
                enabledInputMethodList, userAwareContext, services);
    }

    static void filterInputMethodServices(
            ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap,
            ArrayMap<String, InputMethodInfo> methodMap, ArrayList<InputMethodInfo> methodList,
            List<String> enabledInputMethodList, Context userAwareContext,
            List<ResolveInfo> services) {
        final ArrayMap<String, Integer> imiPackageCount = new ArrayMap<>();

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

                    methodList.add(imi);
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
    }

    @GuardedBy("ImfLock.class")
    void buildInputMethodListLocked(boolean resetDefaultEnabledIme) {
        if (DEBUG) {
            Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme
                    + " \n ------ caller=" + Debug.getCallers(10));
        }
        if (!mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }
        mMethodMapUpdateCount++;
        mMyPackageMonitor.clearKnownImePackageNamesLocked();

        queryInputMethodServicesInternal(mContext, mSettings.getCurrentUserId(),
                mAdditionalSubtypeMap, mMethodMap, mMethodList, DirectBootAwareness.AUTO);

        // Construct the set of possible IME packages for onPackageChanged() to avoid false
        // negatives when the package state remains to be the same but only the component state is
        // changed.
        {
            // Here we intentionally use PackageManager.MATCH_DISABLED_COMPONENTS since the purpose
            // of this query is to avoid false negatives.  PackageManager.MATCH_ALL could be more
            // conservative, but it seems we cannot use it for now (Issue 35176630).
            final List<ResolveInfo> allInputMethodServices =
                    mContext.getPackageManager().queryIntentServicesAsUser(
                            new Intent(InputMethod.SERVICE_INTERFACE),
                            PackageManager.MATCH_DISABLED_COMPONENTS, mSettings.getCurrentUserId());
            final int numImes = allInputMethodServices.size();
            for (int i = 0; i < numImes; ++i) {
                final ServiceInfo si = allInputMethodServices.get(i).serviceInfo;
                if (android.Manifest.permission.BIND_INPUT_METHOD.equals(si.permission)) {
                    mMyPackageMonitor.addKnownImePackageNameLocked(si.packageName);
                }
            }
        }

        boolean reenableMinimumNonAuxSystemImes = false;
        // TODO: The following code should find better place to live.
        if (!resetDefaultEnabledIme) {
            boolean enabledImeFound = false;
            boolean enabledNonAuxImeFound = false;
            final List<InputMethodInfo> enabledImes = mSettings.getEnabledInputMethodListLocked();
            final int numImes = enabledImes.size();
            for (int i = 0; i < numImes; ++i) {
                final InputMethodInfo imi = enabledImes.get(i);
                if (mMethodList.contains(imi)) {
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
                    InputMethodInfoUtils.getDefaultEnabledImes(mContext, mMethodList,
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

        final String defaultImiId = mSettings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(defaultImiId)) {
            if (!mMethodMap.containsKey(defaultImiId)) {
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
        if (mSettings.getCurrentUserId() == mSwitchingController.getUserId()) {
            mSwitchingController.resetCircularListLocked(mMethodMap);
        } else {
            mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(
                    mContext, mMethodMap, mSettings.getCurrentUserId());
        }
        // TODO: Instantiate mHardwareKeyboardShortcutController for each user.
        if (mSettings.getCurrentUserId() == mHardwareKeyboardShortcutController.getUserId()) {
            mHardwareKeyboardShortcutController.reset(mMethodMap);
        } else {
            mHardwareKeyboardShortcutController = new HardwareKeyboardShortcutController(
                    mMethodMap, mSettings.getCurrentUserId());
        }

        sendOnNavButtonFlagsChangedLocked();

        // Notify InputMethodListListeners of the new installed InputMethods.
        final List<InputMethodInfo> inputMethodList = new ArrayList<>(mMethodList);
        mHandler.obtainMessage(MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED,
                mSettings.getCurrentUserId(), 0 /* unused */, inputMethodList).sendToTarget();
    }

    @GuardedBy("ImfLock.class")
    void sendOnNavButtonFlagsChangedLocked() {
        final IInputMethodInvoker curMethod = mBindingController.getCurMethod();
        if (curMethod == null) {
            // No need to send the data if the IME is not yet bound.
            return;
        }
        curMethod.onNavButtonFlagsChanged(getInputMethodNavButtonFlagsLocked());
    }

    @GuardedBy("ImfLock.class")
    private void updateDefaultVoiceImeIfNeededLocked() {
        final String systemSpeechRecognizer =
                mContext.getString(com.android.internal.R.string.config_systemSpeechRecognizer);
        final String currentDefaultVoiceImeId = mSettings.getDefaultVoiceInputMethod();
        final InputMethodInfo newSystemVoiceIme = InputMethodInfoUtils.chooseSystemVoiceIme(
                mMethodMap, systemSpeechRecognizer, currentDefaultVoiceImeId);
        if (newSystemVoiceIme == null) {
            if (DEBUG) {
                Slog.i(TAG, "Found no valid default Voice IME. If the user is still locked,"
                        + " this may be expected.");
            }
            // Clear DEFAULT_VOICE_INPUT_METHOD when necessary.  Note that InputMethodSettings
            // does not update the actual Secure Settings until the user is unlocked.
            if (!TextUtils.isEmpty(currentDefaultVoiceImeId)) {
                mSettings.putDefaultVoiceInputMethod("");
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
        mSettings.putDefaultVoiceInputMethod(newSystemVoiceIme.getId());
    }

    // ----------------------------------------------------------------------

    /**
     * Enable or disable the given IME by updating {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     *
     * @param id ID of the IME is to be manipulated. It is OK to pass IME ID that is currently not
     *           recognized by the system.
     * @param enabled {@code true} if {@code id} needs to be enabled.
     * @return {@code true} if the IME was previously enabled. {@code false} otherwise.
     */
    @GuardedBy("ImfLock.class")
    private boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        if (enabled) {
            final String enabledImeIdsStr = mSettings.getEnabledInputMethodsStr();
            final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                    enabledImeIdsStr, id);
            if (TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr)) {
                // We are enabling this input method, but it is already enabled.
                // Nothing to do. The previous state was enabled.
                return true;
            }
            mSettings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
            // Previous state was disabled.
            return false;
        } else {
            final List<Pair<String, ArrayList<String>>> enabledInputMethodsList = mSettings
                    .getEnabledInputMethodsAndSubtypeListLocked();
            StringBuilder builder = new StringBuilder();
            if (mSettings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                    builder, enabledInputMethodsList, id)) {
                if (mDeviceIdToShowIme == DEVICE_ID_DEFAULT) {
                    // Disabled input method is currently selected, switch to another one.
                    final String selId = mSettings.getSelectedInputMethod();
                    if (id.equals(selId) && !chooseNewDefaultIMELocked()) {
                        Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                        resetSelectedInputMethodAndSubtypeLocked("");
                    }
                } else if (id.equals(mSettings.getSelectedDefaultDeviceInputMethod())) {
                    // Disabled default device IME while using a virtual device one, choose a
                    // new default one but only update the settings.
                    InputMethodInfo newDefaultIme =
                            InputMethodInfoUtils.getMostApplicableDefaultIME(
                                        mSettings.getEnabledInputMethodListLocked());
                    mSettings.putSelectedDefaultDeviceInputMethod(
                            newDefaultIme == null ? "" : newDefaultIme.getId());
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
        mSettings.saveCurrentInputMethodAndSubtypeToHistory(getSelectedMethodIdLocked(),
                mCurrentSubtype);

        // Set Subtype here
        if (imi == null || subtypeId < 0) {
            mSettings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
            mCurrentSubtype = null;
        } else {
            if (subtypeId < imi.getSubtypeCount()) {
                InputMethodSubtype subtype = imi.getSubtypeAt(subtypeId);
                mSettings.putSelectedSubtype(subtype.hashCode());
                mCurrentSubtype = subtype;
            } else {
                mSettings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
                // If the subtype is not specified, choose the most applicable one
                mCurrentSubtype = getCurrentInputMethodSubtypeLocked();
            }
        }
        notifyInputMethodSubtypeChangedLocked(mSettings.getCurrentUserId(), imi, mCurrentSubtype);

        if (!setSubtypeOnly) {
            // Set InputMethod here
            mSettings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }
    }

    @GuardedBy("ImfLock.class")
    private void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme) {
        InputMethodInfo imi = mMethodMap.get(newDefaultIme);
        int lastSubtypeId = NOT_A_SUBTYPE_ID;
        // newDefaultIme is empty when there is no candidate for the selected IME.
        if (imi != null && !TextUtils.isEmpty(newDefaultIme)) {
            String subtypeHashCode = mSettings.getLastSubtypeForInputMethodLocked(newDefaultIme);
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
     * @param userId User ID to be queried about.
     * @return The current {@link InputMethodSubtype} for the specified user.
     */
    @Nullable
    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            if (mSettings.getCurrentUserId() == userId) {
                return getCurrentInputMethodSubtypeLocked();
            }

            final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
            final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
            return settings.getCurrentInputMethodSubtypeForNonCurrentUsers();
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
        final boolean subtypeIsSelected = mSettings.isSubtypeSelected();
        final InputMethodInfo imi = mMethodMap.get(selectedMethodId);
        if (imi == null || imi.getSubtypeCount() == 0) {
            return null;
        }
        if (!subtypeIsSelected || mCurrentSubtype == null
                || !SubtypeUtils.isValidSubtypeId(imi, mCurrentSubtype.hashCode())) {
            int subtypeId = mSettings.getSelectedInputMethodSubtypeId(selectedMethodId);
            if (subtypeId == NOT_A_SUBTYPE_ID) {
                // If there are no selected subtypes, the framework will try to find
                // the most applicable subtype from explicitly or implicitly enabled
                // subtypes.
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                        mSettings.getEnabledInputMethodSubtypeListLocked(imi, true);
                // If there is only one explicitly or implicitly enabled subtype,
                // just returns it.
                if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
                    mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                } else if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                    final String locale = SystemLocaleWrapper.get(mSettings.getCurrentUserId())
                            .get(0).toString();
                    mCurrentSubtype = SubtypeUtils.findLastResortApplicableSubtypeLocked(
                            explicitlyOrImplicitlyEnabledSubtypes,
                            SubtypeUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                    if (mCurrentSubtype == null) {
                        mCurrentSubtype = SubtypeUtils.findLastResortApplicableSubtypeLocked(
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
     * @param userId user ID to query.
     */
    @GuardedBy("ImfLock.class")
    private InputMethodInfo queryDefaultInputMethodForUserIdLocked(@UserIdInt int userId) {
        if (userId == mSettings.getCurrentUserId()) {
            return mMethodMap.get(mSettings.getSelectedInputMethod());
        }

        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap = new ArrayMap<>();
        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
        queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap, methodMap,
                methodList, DirectBootAwareness.AUTO);
        InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
        return methodMap.get(settings.getSelectedInputMethod());
    }

    private ArrayMap<String, InputMethodInfo> queryMethodMapForUser(@UserIdInt int userId) {
        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>();
        final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap =
                new ArrayMap<>();
        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
        queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap,
                methodMap, methodList, DirectBootAwareness.AUTO);
        return methodMap;
    }

    @GuardedBy("ImfLock.class")
    private boolean switchToInputMethodLocked(String imeId, @UserIdInt int userId) {
        if (userId == mSettings.getCurrentUserId()) {
            if (!mMethodMap.containsKey(imeId)
                    || !mSettings.getEnabledInputMethodListLocked()
                    .contains(mMethodMap.get(imeId))) {
                return false; // IME is not found or not enabled.
            }
            setInputMethodLocked(imeId, NOT_A_SUBTYPE_ID);
            return true;
        }
        final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
        final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
        if (!methodMap.containsKey(imeId)
                || !settings.getEnabledInputMethodListLocked().contains(methodMap.get(imeId))) {
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
     * @param targetPkgName The package name of input method to check.
     * @param callingUid The caller that is going to access the input method.
     * @param userId The user ID where the input method resides.
     * @param settings The input method settings under the given user ID.
     * @return {@code true} if caller is able to access the input method.
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
        final InputMethodInfo currentImi = mMethodMap.get(getSelectedMethodIdLocked());
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
        final InputMethodInfo nextImi = mMethodMap.get(nextSubtypeHandle.getImeId());
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
                InlineSuggestionsRequestInfo requestInfo, IInlineSuggestionsRequestCallback cb) {
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
                if (userId == mSettings.getCurrentUserId()) {
                    if (!mMethodMap.containsKey(imeId)) {
                        return false; // IME is not found.
                    }
                    setInputMethodEnabledLocked(imeId, enabled);
                    return true;
                }
                final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
                final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
                if (!methodMap.containsKey(imeId)) {
                    return false; // IME is not found.
                }
                if (enabled) {
                    final String enabledImeIdsStr = settings.getEnabledInputMethodsStr();
                    final String newEnabledImeIdsStr = InputMethodUtils.concatEnabledImeIds(
                            enabledImeIdsStr, imeId);
                    if (!TextUtils.equals(enabledImeIdsStr, newEnabledImeIdsStr)) {
                        settings.putEnabledInputMethodsStr(newEnabledImeIdsStr);
                    }
                } else {
                    settings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                            new StringBuilder(),
                            settings.getEnabledInputMethodsAndSubtypeListLocked(), imeId);
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
                int displayId) {
            //TODO(b/150843766): Check if Input Token is valid.
            final IBinder curHostInputToken;
            synchronized (ImfLock.class) {
                if (displayId != mCurTokenDisplayId || mCurHostInputToken == null) {
                    return false;
                }
                curHostInputToken = mCurHostInputToken;
            }
            return mInputManagerInternal.transferTouchFocus(sourceInputToken, curHostInputToken);
        }

        @Override
        public void reportImeControl(@Nullable IBinder windowToken) {
            synchronized (ImfLock.class) {
                if (mCurFocusedWindow != windowToken) {
                    // mCurPerceptible was set by the focused window, but it is no longer in
                    // control, so we reset mCurPerceptible.
                    mCurPerceptible = true;
                }
            }
        }

        @Override
        public void onImeParentChanged(int displayId) {
            synchronized (ImfLock.class) {
                // Hide the IME method menu only when the IME surface parent is changed by the
                // input target changed, in case seeing the dialog dismiss flickering during
                // the next focused window starting the input connection.
                if (mLastImeTargetWindow != mCurFocusedWindow) {
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
                // TODO(b/305829876): Implement user ID verification
                if (mCurClient != null) {
                    clearClientSessionForAccessibilityLocked(mCurClient, accessibilityConnectionId);
                    mCurClient.mAccessibilitySessions.put(accessibilityConnectionId,
                            new AccessibilitySessionState(mCurClient, accessibilityConnectionId,
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
                            imeSession, accessibilityInputMethodSessions, null, getCurIdLocked(),
                            getSequenceNumberLocked(), false);
                    mCurClient.mClient.onBindAccessibilityService(res, accessibilityConnectionId);
                }
            }
        }

        @Override
        public void unbindAccessibilityFromCurrentClient(int accessibilityConnectionId,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                // TODO(b/305829876): Implement user ID verification
                if (mCurClient != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "unbindAccessibilityFromCurrentClientLocked: client="
                                + mCurClient.mClient.asBinder());
                    }
                    // A11yManagerService unbinds the disabled accessibility service. We don't need
                    // to do it here.
                    mCurClient.mClient.onUnbindAccessibilityService(getSequenceNumberLocked(),
                            accessibilityConnectionId);
                }
                // We only have sessions when we bound to an input method. Remove this session
                // from all clients.
                if (getCurMethodLocked() != null) {
                    final int numClients = mClients.size();
                    for (int i = 0; i < numClients; ++i) {
                        clearClientSessionForAccessibilityLocked(mClients.valueAt(i),
                                accessibilityConnectionId);
                    }
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

        /**
         * Returns true if any InputConnection is currently active.
         */
        @Override
        public boolean isAnyInputConnectionActive() {
            return mCurInputConnection != null;
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
            dumpDebug(proto, InputMethodManagerServiceTraceProto.INPUT_METHOD_MANAGER_SERVICE);
            proto.flush();
        }
    };

    @BinderThread
    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
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
            p.println("Current Input Method Manager state:");
            int numImes = mMethodList.size();
            p.println("  Input Methods: mMethodMapUpdateCount=" + mMethodMapUpdateCount);
            for (int i = 0; i < numImes; i++) {
                InputMethodInfo info = mMethodList.get(i);
                p.println("  InputMethod #" + i + ":");
                info.dump(p, "    ");
            }
            p.println("  ClientStates:");
            final int numClients = mClients.size();
            for (int i = 0; i < numClients; ++i) {
                final ClientState ci = mClients.valueAt(i);
                p.println("  " + ci + ":");
                p.println("    client=" + ci.mClient);
                p.println("    fallbackInputConnection=" + ci.mFallbackInputConnection);
                p.println("    sessionRequested=" + ci.mSessionRequested);
                p.println("    sessionRequestedForAccessibility="
                        + ci.mSessionRequestedForAccessibility);
                p.println("    curSession=" + ci.mCurSession);
            }
            p.println("  mCurMethodId=" + getSelectedMethodIdLocked());
            client = mCurClient;
            p.println("  mCurClient=" + client + " mCurSeq=" + getSequenceNumberLocked());
            p.println("  mCurPerceptible=" + mCurPerceptible);
            p.println("  mCurFocusedWindow=" + mCurFocusedWindow
                    + " softInputMode="
                    + InputMethodDebug.softInputModeToString(mCurFocusedWindowSoftInputMode)
                    + " client=" + mCurFocusedWindowClient);
            focusedWindowClient = mCurFocusedWindowClient;
            p.println("  mCurId=" + getCurIdLocked() + " mHaveConnection=" + hasConnectionLocked()
                    + " mBoundToMethod=" + mBoundToMethod + " mVisibleBound="
                    + mBindingController.isVisibleBound());
            p.println("  mCurToken=" + getCurTokenLocked());
            p.println("  mCurTokenDisplayId=" + mCurTokenDisplayId);
            p.println("  mCurHostInputToken=" + mCurHostInputToken);
            p.println("  mCurIntent=" + getCurIntentLocked());
            method = getCurMethodLocked();
            p.println("  mCurMethod=" + getCurMethodLocked());
            p.println("  mEnabledSession=" + mEnabledSession);
            mVisibilityStateComputer.dump(pw, "  ");
            p.println("  mInFullscreenMode=" + mInFullscreenMode);
            p.println("  mSystemReady=" + mSystemReady + " mInteractive=" + mIsInteractive);
            p.println("  ENABLE_HIDE_IME_CAPTION_BAR="
                    + InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR);
            p.println("  mSettingsObserver=" + mSettingsObserver);
            p.println("  mStylusIds=" + (mStylusIds != null
                    ? Arrays.toString(mStylusIds.toArray()) : ""));
            p.println("  mSwitchingController:");
            mSwitchingController.dump(p);
            p.println("  mSettings:");
            mSettings.dumpLocked(p, "    ");

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

        if (focusedWindowClient != null && client != focusedWindowClient) {
            p.println(" ");
            p.println("Warning: Current input method client doesn't match the last focused. "
                    + "window.");
            p.println("Dumping input method client in the last focused window just in case.");
            p.println(" ");
            pw.flush();
            try {
                TransferPipe.dumpAsync(focusedWindowClient.mClient.asBinder(), fd, args);
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
            @NonNull ResultReceiver resultReceiver) throws RemoteException {
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
                this, in, out, err, args, callback, resultReceiver);
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
                case "get-last-switch-user-id":
                    return mService.getLastSwitchUserId(this);
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

    @BinderThread
    @ShellCommandResult
    private int getLastSwitchUserId(@NonNull ShellCommand shellCommand) {
        synchronized (ImfLock.class) {
            shellCommand.getOutPrintWriter().println(mLastSwitchUserId);
            return ShellCommandResult.SUCCESS;
        }
    }

    /**
     * Handles {@code adb shell ime list}.
     * @param shellCommand {@link ShellCommand} object that is handling this command.
     * @return Exit code of the command.
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
                    mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
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
     * @param shellCommand {@link ShellCommand} object that is handling this command.
     * @param enabled      {@code true} if the command was {@code adb shell ime enable}.
     * @return Exit code of the command.
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
                        mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
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
     * @param shellCommand {@link ShellCommand} from which options should be obtained.
     * @return User ID to be resolved. {@link UserHandle#CURRENT} if not specified.
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
     * @param userId user ID specified to the command.  Pseudo user IDs are not supported.
     * @param imeId IME ID specified to the command.
     * @param enabled {@code true} for {@code adb shell ime enable}. {@code false} otherwise.
     * @param out {@link PrintWriter} to output standard messages.
     * @param error {@link PrintWriter} to output error messages.
     * @return {@code false} if it fails to enable the IME.  {@code false} otherwise.
     */
    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean handleShellCommandEnableDisableInputMethodInternalLocked(
            @UserIdInt int userId, String imeId, boolean enabled, PrintWriter out,
            PrintWriter error) {
        boolean failedToEnableUnknownIme = false;
        boolean previouslyEnabled = false;
        if (userId == mSettings.getCurrentUserId()) {
            if (enabled && !mMethodMap.containsKey(imeId)) {
                failedToEnableUnknownIme = true;
            } else {
                previouslyEnabled = setInputMethodEnabledLocked(imeId, enabled);
            }
        } else {
            final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
            final InputMethodSettings settings = new InputMethodSettings(methodMap, userId);
            if (enabled) {
                if (!methodMap.containsKey(imeId)) {
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
                        settings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                                new StringBuilder(),
                                settings.getEnabledInputMethodsAndSubtypeListLocked(), imeId);
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
     * @param shellCommand {@link ShellCommand} object that is handling this command.
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
                        mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
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
     * @param shellCommand {@link ShellCommand} object that is handling this command.
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandResetInputMethod(@NonNull ShellCommand shellCommand) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        synchronized (ImfLock.class) {
            try (PrintWriter out = shellCommand.getOutPrintWriter()) {
                final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                        mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
                for (int userId : userIds) {
                    if (!userHasDebugPriv(userId, shellCommand)) {
                        continue;
                    }
                    // Skip on headless user
                    if (USER_TYPE_SYSTEM_HEADLESS.equals(
                            mUserManagerInternal.getUserInfo(userId).userType)) {
                        continue;
                    }
                    final String nextIme;
                    final List<InputMethodInfo> nextEnabledImes;
                    if (userId == mSettings.getCurrentUserId()) {
                        hideCurrentInputLocked(mCurFocusedWindow, null /* statsToken */,
                                0 /* flags */, null /* resultReceiver */,
                                SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND);
                        mBindingController.unbindCurrentMethod();

                        // Enable default IMEs, disable others
                        var toDisable = mSettings.getEnabledInputMethodListLocked();
                        var defaultEnabled = InputMethodInfoUtils.getDefaultEnabledImes(
                                mContext, mMethodList);
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
                                getPackageManagerForUser(mContext, mSettings.getCurrentUserId()),
                                mSettings.getEnabledInputMethodListLocked());
                        nextIme = mSettings.getSelectedInputMethod();
                        nextEnabledImes = mSettings.getEnabledInputMethodListLocked();
                    } else {
                        final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
                        final ArrayList<InputMethodInfo> methodList = new ArrayList<>();
                        final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap =
                                new ArrayMap<>();
                        AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
                        queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap,
                                methodMap, methodList, DirectBootAwareness.AUTO);
                        final InputMethodSettings settings = new InputMethodSettings(
                                methodMap, userId);

                        nextEnabledImes = InputMethodInfoUtils.getDefaultEnabledImes(mContext,
                                methodList);
                        nextIme = InputMethodInfoUtils.getMostApplicableDefaultIME(
                                nextEnabledImes).getId();

                        // Reset enabled IMEs.
                        final String[] nextEnabledImeIds = new String[nextEnabledImes.size()];
                        for (int i = 0; i < nextEnabledImeIds.length; ++i) {
                            nextEnabledImeIds[i] = nextEnabledImes.get(i).getId();
                        }
                        settings.putEnabledInputMethodsStr(InputMethodUtils.concatEnabledImeIds(
                                settings.getEnabledInputMethodsStr(), nextEnabledImeIds));

                        // Reset selected IME.
                        settings.putSelectedInputMethod(nextIme);
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
     * @param shellCommand {@link ShellCommand} object that is handling this command.
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
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                state.mClient.setImeTraceEnabled(isImeTraceEnabled);
            }
        }
        return ShellCommandResult.SUCCESS;
    }

    /**
     * @param userId the actual user handle obtained by {@link UserHandle#getIdentifier()}
     * and *not* pseudo ids like {@link UserHandle#USER_ALL etc}.
     * @return {@code true} if userId has debugging privileges.
     * i.e. {@link UserManager#DISALLOW_DEBUGGING_FEATURES} is {@code false}.
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
     * @param show whether this is a show or a hide request.
     * @param origin the origin of the IME request.
     * @param reason the reason why the IME request was created.
     */
    @NonNull
    private ImeTracker.Token createStatsTokenForFocusedClient(boolean show,
            @ImeTracker.Origin int origin, @SoftInputShowHideReason int reason) {
        final int uid = mCurFocusedWindowClient != null
                ? mCurFocusedWindowClient.mUid
                : -1;
        final var packageName = mCurFocusedWindowEditorInfo != null
                ? mCurFocusedWindowEditorInfo.packageName
                : "uid(" + uid + ")";

        if (show) {
            return ImeTracker.forLogging().onRequestShow(packageName, uid, origin, reason);
        } else {
            return ImeTracker.forLogging().onRequestHide(packageName, uid, origin, reason);
        }
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
        public void createInputContentUriToken(Uri contentUri, String packageName,
                AndroidFuture future /* T=IBinder */) {
            @SuppressWarnings("unchecked")
            final AndroidFuture<IBinder> typedFuture = future;
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
            @SuppressWarnings("unchecked")
            final AndroidFuture<Void> typedFuture = future;
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
            @SuppressWarnings("unchecked")
            final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.setInputMethodAndSubtype(mToken, id, subtype);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void hideMySoftInput(@InputMethodManager.HideFlags int flags,
                @SoftInputShowHideReason int reason, AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked")
            final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.hideMySoftInput(mToken, flags, reason);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void showMySoftInput(@InputMethodManager.ShowFlags int flags,
                AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked")
            final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.showMySoftInput(mToken, flags);
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
            @SuppressWarnings("unchecked")
            final AndroidFuture<Boolean> typedFuture = future;
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
            @SuppressWarnings("unchecked")
            final AndroidFuture<Boolean> typedFuture = future;
            try {
                typedFuture.complete(mImms.switchToNextInputMethod(mToken, onlyCurrentIme));
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void shouldOfferSwitchingToNextInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked")
            final AndroidFuture<Boolean> typedFuture = future;
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
                @Nullable ImeTracker.Token statsToken) {
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
