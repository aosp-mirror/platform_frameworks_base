/*
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

package com.android.server.inputmethod;

import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_CRITICAL;
import static android.os.IServiceManager.DUMP_FLAG_PRIORITY_NORMAL;
import static android.os.IServiceManager.DUMP_FLAG_PROTO;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.server.inputmethod.InputMethodManagerServiceProto.ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD;
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
import static android.server.inputmethod.InputMethodManagerServiceProto.INPUT_SHOWN;
import static android.server.inputmethod.InputMethodManagerServiceProto.IN_FULLSCREEN_MODE;
import static android.server.inputmethod.InputMethodManagerServiceProto.IS_INTERACTIVE;
import static android.server.inputmethod.InputMethodManagerServiceProto.LAST_IME_TARGET_WINDOW_NAME;
import static android.server.inputmethod.InputMethodManagerServiceProto.LAST_SWITCH_USER_ID;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_EXPLICITLY_REQUESTED;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_FORCED;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_IME_WITH_HARD_KEYBOARD;
import static android.server.inputmethod.InputMethodManagerServiceProto.SHOW_REQUESTED;
import static android.server.inputmethod.InputMethodManagerServiceProto.SYSTEM_READY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.DISPLAY_IME_POLICY_HIDE;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;

import static com.android.server.inputmethod.InputMethodBindingController.TIME_TO_RECONNECT;
import static com.android.server.inputmethod.InputMethodUtils.isSoftInputModeStateVisibleAllowed;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.annotation.AnyThread;
import android.annotation.BinderThread;
import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UiThread;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.hardware.display.DisplayManagerInternal;
import android.hardware.input.InputManagerInternal;
import android.inputmethodservice.InputMethodService;
import android.media.AudioManagerInternal;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Message;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
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
import android.util.Pair;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.InputChannel;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.DisplayImePolicy;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManager.LayoutParams.SoftInputModeFlags;
import android.view.accessibility.AccessibilityManager;
import android.view.autofill.AutofillId;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InlineSuggestionsRequest;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.infra.AndroidFuture;
import com.android.internal.inputmethod.DirectBootAwareness;
import com.android.internal.inputmethod.IInputContentUriToken;
import com.android.internal.inputmethod.IInputMethodPrivilegedOperations;
import com.android.internal.inputmethod.ImeTracing;
import com.android.internal.inputmethod.InputBindResult;
import com.android.internal.inputmethod.InputMethodDebug;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.notification.SystemNotificationChannels;
import com.android.internal.os.SomeArgs;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.ConcurrentUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.view.IInlineSuggestionsRequestCallback;
import com.android.internal.view.IInlineSuggestionsResponseCallback;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.IInputSessionCallback;
import com.android.internal.view.InlineSuggestionsRequestInfo;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.inputmethod.InputMethodManagerInternal.InputMethodListListener;
import com.android.server.inputmethod.InputMethodSubtypeSwitchingController.ImeSubtypeListItem;
import com.android.server.inputmethod.InputMethodUtils.InputMethodSettings;
import com.android.server.pm.UserManagerInternal;
import com.android.server.statusbar.StatusBarManagerService;
import com.android.server.utils.PriorityDump;
import com.android.server.wm.WindowManagerInternal;

import com.google.android.collect.Sets;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.security.InvalidParameterException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class provides a system service that manages input methods.
 */
public final class InputMethodManagerService extends IInputMethodManager.Stub
        implements Handler.Callback {
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

    private static final int MSG_HIDE_CURRENT_INPUT_METHOD = 1035;
    private static final int MSG_REMOVE_IME_SURFACE = 1060;
    private static final int MSG_REMOVE_IME_SURFACE_FROM_WINDOW = 1061;
    private static final int MSG_UPDATE_IME_WINDOW_STATUS = 1070;

    private static final int MSG_RESET_HANDWRITING = 1090;
    private static final int MSG_START_HANDWRITING = 1100;
    private static final int MSG_FINISH_HANDWRITING = 1110;

    private static final int MSG_UNBIND_CLIENT = 3000;
    private static final int MSG_UNBIND_ACCESSIBILITY_SERVICE = 3001;
    private static final int MSG_BIND_CLIENT = 3010;
    private static final int MSG_BIND_ACCESSIBILITY_SERVICE = 3011;
    private static final int MSG_SET_ACTIVE = 3020;
    private static final int MSG_SET_INTERACTIVE = 3030;
    private static final int MSG_REPORT_FULLSCREEN_MODE = 3045;

    private static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;

    private static final int MSG_SYSTEM_UNLOCK_USER = 5000;
    private static final int MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED = 5010;

    private static final int MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE = 7000;

    private static final int NOT_A_SUBTYPE_ID = InputMethodUtils.NOT_A_SUBTYPE_ID;
    private static final String TAG_TRY_SUPPRESSING_IME_SWITCHER = "TrySuppressingImeSwitcher";
    private static final String HANDLER_THREAD_NAME = "android.imms";

    /**
     * A protected broadcast intent action for internal use for {@link PendingIntent} in
     * the notification.
     */
    private static final String ACTION_SHOW_INPUT_METHOD_PICKER =
            "com.android.server.inputmethod.InputMethodManagerService.SHOW_INPUT_METHOD_PICKER";

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
    private final Set<String> mNonPreemptibleInputMethods;

    @UserIdInt
    private int mLastSwitchUserId;

    final Context mContext;
    final Resources mRes;
    private final Handler mHandler;
    final InputMethodSettings mSettings;
    final SettingsObserver mSettingsObserver;
    final IWindowManager mIWindowManager;
    private final SparseBooleanArray mLoggedDeniedGetInputMethodWindowVisibleHeightForUid =
            new SparseBooleanArray(0);
    final WindowManagerInternal mWindowManagerInternal;
    final PackageManagerInternal mPackageManagerInternal;
    final InputManagerInternal mInputManagerInternal;
    final ImePlatformCompatUtils mImePlatformCompatUtils;
    private final DisplayManagerInternal mDisplayManagerInternal;
    final boolean mHasFeature;
    private final ArrayMap<String, List<InputMethodSubtype>> mAdditionalSubtypeMap =
            new ArrayMap<>();
    private final AppOpsManager mAppOpsManager;
    private final UserManager mUserManager;
    private final UserManagerInternal mUserManagerInternal;
    private final InputMethodMenuController mMenuController;
    private final InputMethodBindingController mBindingController;

    // TODO(b/219056452): Use AccessibilityManagerInternal instead.
    private final AccessibilityManager mAccessibilityManager;

    /**
     * Cache the result of {@code LocalServices.getService(AudioManagerInternal.class)}.
     *
     * <p>This field is used only within {@link #handleMessage(Message)} hence synchronization is
     * not necessary.</p>
     */
    @Nullable
    private AudioManagerInternal mAudioManagerInternal = null;

    // All known input methods.
    final ArrayList<InputMethodInfo> mMethodList = new ArrayList<>();
    final ArrayMap<String, InputMethodInfo> mMethodMap = new ArrayMap<>();
    final InputMethodSubtypeSwitchingController mSwitchingController;

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

    // Ongoing notification
    private NotificationManager mNotificationManager;
    KeyguardManager mKeyguardManager;
    private @Nullable StatusBarManagerService mStatusBar;
    private final Notification.Builder mImeSwitcherNotification;
    private final PendingIntent mImeSwitchPendingIntent;
    private boolean mShowOngoingImeSwitcherForPhones;
    private boolean mNotificationShown;
    @GuardedBy("ImfLock.class")
    private final HandwritingModeController mHwController;

    @GuardedBy("ImfLock.class")
    @Nullable
    private OverlayableSystemBooleanResourceWrapper mImeDrawsImeNavBarRes;
    @GuardedBy("ImfLock.class")
    @Nullable
    Future<?> mImeDrawsImeNavBarResLazyInitFuture;

    static class SessionState {
        final ClientState client;
        final IInputMethodInvoker method;

        IInputMethodSession session;
        InputChannel channel;

        @Override
        public String toString() {
            return "SessionState{uid " + client.uid + " pid " + client.pid
                    + " method " + Integer.toHexString(
                            IInputMethodInvoker.getBinderIdentityHashCode(method))
                    + " session " + Integer.toHexString(
                            System.identityHashCode(session))
                    + " channel " + channel
                    + "}";
        }

        SessionState(ClientState _client, IInputMethodInvoker _method,
                IInputMethodSession _session, InputChannel _channel) {
            client = _client;
            method = _method;
            session = _session;
            channel = _channel;
        }
    }

    /**
     * Record session state for an accessibility service.
     */
    private static class AccessibilitySessionState {
        final ClientState mClient;
        // Id of the accessibility service.
        final int mId;

        public IInputMethodSession mSession;

        @Override
        public String toString() {
            return "AccessibilitySessionState{uid " + mClient.uid + " pid " + mClient.pid
                    + " id " + Integer.toHexString(mId)
                    + " session " + Integer.toHexString(
                    System.identityHashCode(mSession))
                    + "}";
        }

        AccessibilitySessionState(ClientState client, int id,
                IInputMethodSession session) {
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
        final IInputMethodClient client;
        final IInputContext inputContext;
        final int uid;
        final int pid;
        final int selfReportedDisplayId;
        final InputBinding binding;
        final ClientDeathRecipient clientDeathRecipient;

        boolean sessionRequested;
        boolean mSessionRequestedForAccessibility;
        SessionState curSession;
        SparseArray<AccessibilitySessionState> mAccessibilitySessions = new SparseArray<>();

        @Override
        public String toString() {
            return "ClientState{" + Integer.toHexString(
                    System.identityHashCode(this)) + " uid=" + uid
                    + " pid=" + pid + " displayId=" + selfReportedDisplayId + "}";
        }

        ClientState(IInputMethodClient _client, IInputContext _inputContext,
                int _uid, int _pid, int _selfReportedDisplayId,
                ClientDeathRecipient _clientDeathRecipient) {
            client = _client;
            inputContext = _inputContext;
            uid = _uid;
            pid = _pid;
            selfReportedDisplayId = _selfReportedDisplayId;
            binding = new InputBinding(null, inputContext.asBinder(), uid, pid);
            clientDeathRecipient = _clientDeathRecipient;
        }
    }

    @GuardedBy("ImfLock.class")
    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    private static final class VirtualDisplayInfo {
        /**
         * {@link ClientState} where {@link android.hardware.display.VirtualDisplay} is running.
         */
        private final ClientState mParentClient;
        /**
         * {@link Matrix} to convert screen coordinates in the embedded virtual display to
         * screen coordinates where {@link #mParentClient} exists.
         */
        private final Matrix mMatrix;

        VirtualDisplayInfo(ClientState parentClient, Matrix matrix) {
            mParentClient = parentClient;
            mMatrix = matrix;
        }
    }

    /**
     * A mapping table from virtual display IDs created for
     * {@link android.hardware.display.VirtualDisplay} to its parent IME client where the embedded
     * virtual display is running.
     *
     * <p>Note: this can be used only for virtual display IDs created by
     * {@link android.hardware.display.VirtualDisplay}.</p>
     */
    @GuardedBy("ImfLock.class")
    private final SparseArray<VirtualDisplayInfo> mVirtualDisplayIdToParentMap =
            new SparseArray<>();

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

    /**
     * {@code true} if the Ime policy has been set to {@link WindowManager#DISPLAY_IME_POLICY_HIDE}.
     *
     * This prevents the IME from showing when it otherwise may have shown.
     */
    boolean mImeHiddenByDisplayPolicy;

    /**
     * The client that is currently bound to an input method.
     */
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
     * The client by which {@link #mCurFocusedWindow} was reported.
     */
    ClientState mCurFocusedWindowClient;

    /**
     * The input context last provided by the current client.
     */
    IInputContext mCurInputContext;

    /**
     * The attributes last provided by the current client.
     */
    EditorInfo mCurAttribute;

    /**
     * A special {@link Matrix} to convert virtual screen coordinates to the IME target display
     * coordinates.
     *
     * <p>Used only while the IME client is running in a virtual display. {@code null}
     * otherwise.</p>
     */
    @Nullable
    private Matrix mCurVirtualDisplayToScreenMatrix = null;

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
        return mBindingController.hasConnection();
    }

    /**
     * Set if the client has asked for the input method to be shown.
     */
    private boolean mShowRequested;

    /**
     * Set if we were explicitly told to show the input method.
     */
    boolean mShowExplicitlyRequested;

    /**
     * Set if we were forced to be shown.
     */
    boolean mShowForced;

    /**
     * Set if we last told the input method to show itself.
     */
    private boolean mInputShown;

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
    private IBinder getCurTokenLocked() {
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
    private static final int FALLBACK_DISPLAY_ID = DEFAULT_DISPLAY;

    final ImeDisplayValidator mImeDisplayValidator;

    /**
     * If non-null, this is the input method service we are currently connected
     * to.
     */
    @GuardedBy("ImfLock.class")
    @Nullable
    private IInputMethodInvoker getCurMethodLocked() {
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
     * dt>{@link InputMethodService#IME_INVISIBLE}</dt>
     * <dd> If this bit is ON, IME is ready with views from last EditorInfo but is
     *    currently invisible.
     * </dd>
     * </dl>
     * <em>Do not update this value outside of {@link #setImeWindowStatus(IBinder, int, int)} and
     * {@link InputMethodBindingController#unbindCurrentMethod()}.</em>
     */
    int mImeWindowVis;

    private LocaleList mLastSystemLocales;
    private boolean mAccessibilityRequestingNoSoftKeyboard;
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();
    private final IPackageManager mIPackageManager;
    private final String mSlotIme;

    /**
     * Registered {@link InputMethodListListener}.
     * This variable can be accessed from both of MainThread and BinderThread.
     */
    private final CopyOnWriteArrayList<InputMethodListListener> mInputMethodListListeners =
            new CopyOnWriteArrayList<>();

    /**
     * Internal state snapshot when
     * {@link com.android.internal.view.IInputMethod#startInput(IBinder, IInputContext, EditorInfo,
     * boolean)} is about to be called.
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

    private static final class SoftInputShowHideHistory {
        private final Entry[] mEntries = new Entry[16];
        private int mNextIndex = 0;
        private static final AtomicInteger sSequenceNumber = new AtomicInteger(0);

        private static final class Entry {
            final int mSequenceNumber = sSequenceNumber.getAndIncrement();
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
            @NonNull
            final EditorInfo mEditorInfo;
            @NonNull
            final String mRequestWindowName;
            @Nullable
            final String mImeControlTargetName;
            @Nullable
            final String mImeTargetNameFromWm;

            Entry(ClientState client, EditorInfo editorInfo, String focusedWindowName,
                    @SoftInputModeFlags int softInputMode, @SoftInputShowHideReason int reason,
                    boolean inFullscreenMode, String requestWindowName,
                    @Nullable String imeControlTargetName, @Nullable String imeTargetName) {
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
            }
        }

        void addEntry(@NonNull Entry entry) {
            final int index = mNextIndex;
            mEntries[index] = entry;
            mNextIndex = (mNextIndex + 1) % mEntries.length;
        }

        void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            final SimpleDateFormat dataFormat =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

            for (int i = 0; i < mEntries.length; ++i) {
                final Entry entry = mEntries[(i + mNextIndex) % mEntries.length];
                if (entry == null) {
                    continue;
                }
                pw.print(prefix);
                pw.println("SoftInputShowHideHistory #" + entry.mSequenceNumber + ":");

                pw.print(prefix);
                pw.println(" time=" + dataFormat.format(new Date(entry.mWallTime))
                        + " (timestamp=" + entry.mTimestamp + ")");

                pw.print(prefix);
                pw.print(" reason=" + InputMethodDebug.softInputDisplayReasonToString(
                        entry.mReason));
                pw.println(" inFullscreenMode=" + entry.mInFullscreenMode);

                pw.print(prefix);
                pw.println(" requestClient=" + entry.mClientState);

                pw.print(prefix);
                pw.println(" focusedWindowName=" + entry.mFocusedWindowName);

                pw.print(prefix);
                pw.println(" requestWindowName=" + entry.mRequestWindowName);

                pw.print(prefix);
                pw.println(" imeControlTargetName=" + entry.mImeControlTargetName);

                pw.print(prefix);
                pw.println(" imeTargetNameFromWm=" + entry.mImeTargetNameFromWm);

                pw.print(prefix);
                pw.print(" editorInfo: ");
                pw.print(" inputType=" + entry.mEditorInfo.inputType);
                pw.print(" privateImeOptions=" + entry.mEditorInfo.privateImeOptions);
                pw.println(" fieldId (viewId)=" + entry.mEditorInfo.fieldId);

                pw.print(prefix);
                pw.println(" focusedWindowSoftInputMode=" + InputMethodDebug.softInputModeToString(
                        entry.mFocusedWindowSoftInputMode));
            }
        }
    }

    /**
     * Map of generated token to windowToken that is requesting
     * {@link InputMethodManager#showSoftInput(View, int)}.
     * This map tracks origin of showSoftInput requests.
     */
    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, IBinder> mShowRequestWindowMap = new WeakHashMap<>();

    /**
     * Map of generated token to windowToken that is requesting
     * {@link InputMethodManager#hideSoftInputFromWindow(IBinder, int)}.
     * This map tracks origin of hideSoftInput requests.
     */
    @GuardedBy("ImfLock.class")
    private final WeakHashMap<IBinder, IBinder> mHideRequestWindowMap = new WeakHashMap<>();

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
        private final static int ENTRY_SIZE_FOR_HIGH_RAM_DEVICE = 32;

        /**
         * Entry size for non low-RAM devices.
         *
         * <p>TODO: Consider to follow what other system services have been doing to manage
         * constants (e.g. {@link android.provider.Settings.Global#ACTIVITY_MANAGER_CONSTANTS}).</p>
         */
        private final static int ENTRY_SIZE_FOR_LOW_RAM_DEVICE = 5;

        private static int getEntrySize() {
            if (ActivityManager.isLowRamDeviceStatic()) {
                return ENTRY_SIZE_FOR_LOW_RAM_DEVICE;
            } else {
                return ENTRY_SIZE_FOR_HIGH_RAM_DEVICE;
            }
        }

        /**
         * Backing store for the ring bugger.
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
         * @param info {@lin StartInputInfo} to be added.
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
            final SimpleDateFormat dataFormat =
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

            for (int i = 0; i < mEntries.length; ++i) {
                final Entry entry = mEntries[(i + mNextIndex) % mEntries.length];
                if (entry == null) {
                    continue;
                }
                pw.print(prefix);
                pw.println("StartInput #" + entry.mSequenceNumber + ":");

                pw.print(prefix);
                pw.println(" time=" + dataFormat.format(new Date(entry.mWallTime))
                        + " (timestamp=" + entry.mTimestamp + ")"
                        + " reason="
                        + InputMethodDebug.startInputReasonToString(entry.mStartInputReason)
                        + " restarting=" + entry.mRestarting);

                pw.print(prefix);
                pw.print(" imeToken=" + entry.mImeTokenString + " [" + entry.mImeId + "]");
                pw.print(" imeUserId=" + entry.mImeUserId);
                pw.println(" imeDisplayId=" + entry.mImeDisplayId);

                pw.print(prefix);
                pw.println(" targetWin=" + entry.mTargetWindowString
                        + " [" + entry.mEditorInfo.packageName + "]"
                        + " targetUserId=" + entry.mTargetUserId
                        + " targetDisplayId=" + entry.mTargetDisplayId
                        + " clientBindSeq=" + entry.mClientBindSequenceNumber);

                pw.print(prefix);
                pw.println(" softInputMode=" + InputMethodDebug.softInputModeToString(
                                entry.mTargetWindowSoftInputMode));

                pw.print(prefix);
                pw.println(" inputType=0x" + Integer.toHexString(entry.mEditorInfo.inputType)
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
            mRegistered = true;
        }

        @Override public void onChange(boolean selfChange, Uri uri) {
            final Uri showImeUri = Settings.Secure.getUriFor(
                    Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD);
            final Uri accessibilityRequestingNoImeUri = Settings.Secure.getUriFor(
                    Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE);
            synchronized (ImfLock.class) {
                if (showImeUri.equals(uri)) {
                    mMenuController.updateKeyboardFromSettingsLocked();
                } else if (accessibilityRequestingNoImeUri.equals(uri)) {
                    final int accessibilitySoftKeyboardSetting = Settings.Secure.getIntForUser(
                            mContext.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0, mUserId);
                    mAccessibilityRequestingNoSoftKeyboard =
                            (accessibilitySoftKeyboardSetting & AccessibilityService.SHOW_MODE_MASK)
                                    == AccessibilityService.SHOW_MODE_HIDDEN;
                    if (mAccessibilityRequestingNoSoftKeyboard) {
                        final boolean showRequested = mShowRequested;
                        hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                                SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE);
                        mShowRequested = showRequested;
                    } else if (mShowRequested) {
                        showCurrentInputLocked(mCurFocusedWindow,
                                InputMethodManager.SHOW_IMPLICIT, null,
                                SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE);
                    }
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
     * {@link BroadcastReceiver} that is intended to listen to broadcasts sent to the system user
     * only.
     */
    private final class ImmsBroadcastReceiverForSystemUser extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (Intent.ACTION_USER_ADDED.equals(action)
                    || Intent.ACTION_USER_REMOVED.equals(action)) {
                updateCurrentProfileIds();
                return;
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                onActionLocaleChanged();
            } else if (ACTION_SHOW_INPUT_METHOD_PICKER.equals(action)) {
                // ACTION_SHOW_INPUT_METHOD_PICKER action is a protected-broadcast and it is
                // guaranteed to be send only from the system, so that there is no need for extra
                // security check such as
                // {@link #canShowInputMethodPickerLocked(IInputMethodClient)}.
                mHandler.obtainMessage(
                        MSG_SHOW_IM_SUBTYPE_PICKER,
                        // TODO(b/120076400): Design and implement IME switcher for heterogeneous
                        // navbar configuration.
                        InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES,
                        DEFAULT_DISPLAY).sendToTarget();
            } else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }
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
     *
     * <p>Caution: This method must not be called when system is not ready.</p>
     */
    void onActionLocaleChanged() {
        synchronized (ImfLock.class) {
            final LocaleList possibleNewLocale = mRes.getConfiguration().getLocales();
            if (possibleNewLocale != null && possibleNewLocale.equals(mLastSystemLocales)) {
                return;
            }
            buildInputMethodListLocked(true);
            // If the locale is changed, needs to reset the default ime
            resetDefaultImeLocked(mContext);
            updateFromSettingsLocked(true);
            mLastSystemLocales = possibleNewLocale;
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
        final private ArraySet<String> mKnownImePackageNames = new ArraySet<>();

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
        final void addKnownImePackageNameLocked(@NonNull String packageName) {
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
                final int N = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i=0; i<N; i++) {
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
            final int N = mChangedPackages.size();
            for (int i = 0; i < N; ++i) {
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
                final int N = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i=0; i<N; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
                        final String imiId = imi.getId();
                        if (imiId.equals(curInputMethodId)) {
                            curIm = imi;
                        }

                        int change = isPackageDisappearing(imi.getPackageName());
                        if (isPackageModified(imi.getPackageName())) {
                            mAdditionalSubtypeMap.remove(imi.getId());
                            AdditionalSubtypeUtils.save(mAdditionalSubtypeMap, mMethodMap,
                                    mSettings.getCurrentUserId());
                        }
                        if (change == PACKAGE_TEMPORARY_CHANGE
                                || change == PACKAGE_PERMANENT_CHANGE) {
                            Slog.i(TAG, "Input method uninstalled, disabling: "
                                    + imi.getComponent());
                            setInputMethodEnabledLocked(imi.getId(), false);
                        }
                    }
                }

                buildInputMethodListLocked(false /* resetDefaultEnabledIme */);

                boolean changed = false;

                if (curIm != null) {
                    int change = isPackageDisappearing(curIm.getPackageName());
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        ServiceInfo si = null;
                        try {
                            si = mIPackageManager.getServiceInfo(
                                    curIm.getComponent(), 0, mSettings.getCurrentUserId());
                        } catch (RemoteException ex) {
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
        IInputMethodClient mClientToBeReset;

        UserSwitchHandlerTask(InputMethodManagerService service, @UserIdInt int toUserId,
                @Nullable IInputMethodClient clientToBeReset) {
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

    public static final class Lifecycle extends SystemService {
        private final InputMethodManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new InputMethodManagerService(context);
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
                StatusBarManagerService statusBarService = (StatusBarManagerService) ServiceManager
                        .getService(Context.STATUS_BAR_SERVICE);
                mService.systemRunning(statusBarService);
            }
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            // Called on ActivityManager thread.
            mService.mHandler.obtainMessage(MSG_SYSTEM_UNLOCK_USER, user.getUserIdentifier(), 0)
                    .sendToTarget();
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
            mSettings.switchCurrentUser(currentUserId, !mSystemReady);
            if (mSystemReady) {
                // We need to rebuild IMEs.
                buildInputMethodListLocked(false /* resetDefaultEnabledIme */);
                updateInputMethodsFromSettingsLocked(true /* enabledChanged */);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void scheduleSwitchUserTaskLocked(@UserIdInt int userId,
            @Nullable IInputMethodClient clientToBeReset) {
        if (mUserSwitchHandlerTask != null) {
            if (mUserSwitchHandlerTask.mToUserId == userId) {
                mUserSwitchHandlerTask.mClientToBeReset = clientToBeReset;
                return;
            }
            mHandler.removeCallbacks(mUserSwitchHandlerTask);
        }
        // Hide soft input before user switch task since switch task may block main handler a while
        // and delayed the hideCurrentInputLocked().
        hideCurrentInputLocked(
                mCurFocusedWindow, 0, null, SoftInputShowHideReason.HIDE_SWITCH_USER);
        final UserSwitchHandlerTask task = new UserSwitchHandlerTask(this, userId,
                clientToBeReset);
        mUserSwitchHandlerTask = task;
        mHandler.post(task);
    }

    public InputMethodManagerService(Context context) {
        mIPackageManager = AppGlobals.getPackageManager();
        mContext = context;
        mRes = context.getResources();
        // TODO(b/196206770): Disallow I/O on this thread. Currently it's needed for loading
        // additional subtypes in switchUserOnHandlerLocked().
        final ServiceThread thread = new ServiceThread(
                HANDLER_THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
        thread.start();
        mHandler = Handler.createAsync(thread.getLooper(), this);
        // Note: SettingsObserver doesn't register observers in its constructor.
        mSettingsObserver = new SettingsObserver(mHandler);
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
        mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
        mImePlatformCompatUtils = new ImePlatformCompatUtils();
        mImeDisplayValidator = mWindowManagerInternal::getDisplayImePolicy;
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        mAppOpsManager = mContext.getSystemService(AppOpsManager.class);
        mUserManager = mContext.getSystemService(UserManager.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        mAccessibilityManager = AccessibilityManager.getInstance(context);
        mHasFeature = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_INPUT_METHODS);

        mSlotIme = mContext.getString(com.android.internal.R.string.status_bar_ime);

        Bundle extras = new Bundle();
        extras.putBoolean(Notification.EXTRA_ALLOW_DURING_SETUP, true);
        @ColorInt final int accentColor = mContext.getColor(
                com.android.internal.R.color.system_notification_accent_color);
        mImeSwitcherNotification =
                new Notification.Builder(mContext, SystemNotificationChannels.VIRTUAL_KEYBOARD)
                        .setSmallIcon(com.android.internal.R.drawable.ic_notification_ime_default)
                        .setWhen(0)
                        .setOngoing(true)
                        .addExtras(extras)
                        .setCategory(Notification.CATEGORY_SYSTEM)
                        .setColor(accentColor);

        Intent intent = new Intent(ACTION_SHOW_INPUT_METHOD_PICKER)
                .setPackage(mContext.getPackageName());
        mImeSwitchPendingIntent = PendingIntent.getBroadcast(mContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE);

        mShowOngoingImeSwitcherForPhones = false;

        mNotificationShown = false;
        int userId = 0;
        try {
            userId = ActivityManager.getService().getCurrentUser().id;
        } catch (RemoteException e) {
            Slog.w(TAG, "Couldn't get current user ID; guessing it's 0", e);
        }

        mLastSwitchUserId = userId;

        // mSettings should be created before buildInputMethodListLocked
        mSettings = new InputMethodSettings(
                mRes, context.getContentResolver(), mMethodMap, userId, !mSystemReady);

        updateCurrentProfileIds();
        AdditionalSubtypeUtils.load(mAdditionalSubtypeMap, userId);
        mSwitchingController = InputMethodSubtypeSwitchingController.createInstanceLocked(
                mSettings, context);
        mMenuController = new InputMethodMenuController(this);
        mBindingController = new InputMethodBindingController(this);
        mPreventImeStartupUnlessTextEditor = mRes.getBoolean(
                com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
        mNonPreemptibleInputMethods = Sets.newHashSet(mRes.getStringArray(
                com.android.internal.R.array.config_nonPreemptibleInputMethods));
        mHwController = new HandwritingModeController(thread.getLooper(),
                new InkWindowInitializer());
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

    @GuardedBy("ImfLock.class")
    private void resetDefaultImeLocked(Context context) {
        // Do not reset the default (current) IME when it is a 3rd-party IME
        String selectedMethodId = getSelectedMethodIdLocked();
        if (selectedMethodId != null && !mMethodMap.get(selectedMethodId).isSystem()) {
            return;
        }
        final List<InputMethodInfo> suitableImes = InputMethodUtils.getDefaultEnabledImes(
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
            IInputMethodClient clientToBeReset) {
        if (DEBUG) Slog.d(TAG, "Switching user stage 1/3. newUserId=" + newUserId
                + " currentUserId=" + mSettings.getCurrentUserId());

        maybeInitImeNavbarConfigLocked(newUserId);

        // ContentObserver should be registered again when the user is changed
        mSettingsObserver.registerContentObserverLocked(newUserId);

        // If the system is not ready or the device is not yed unlocked by the user, then we use
        // copy-on-write settings.
        final boolean useCopyOnWriteSettings =
                !mSystemReady || !mUserManagerInternal.isUserUnlockingOrUnlocked(newUserId);
        mSettings.switchCurrentUser(newUserId, useCopyOnWriteSettings);
        updateCurrentProfileIds();
        // Additional subtypes should be reset when the user is changed
        AdditionalSubtypeUtils.load(mAdditionalSubtypeMap, newUserId);
        final String defaultImiId = mSettings.getSelectedInputMethod();

        if (DEBUG) Slog.d(TAG, "Switching user stage 2/3. newUserId=" + newUserId
                + " defaultImiId=" + defaultImiId);

        // For secondary users, the list of enabled IMEs may not have been updated since the
        // callbacks to PackageMonitor are ignored for the secondary user. Here, defaultImiId may
        // not be empty even if the IME has been uninstalled by the primary user.
        // Even in such cases, IMMS works fine because it will find the most applicable
        // IME for that user.
        final boolean initialUserSwitch = TextUtils.isEmpty(defaultImiId);
        mLastSystemLocales = mRes.getConfiguration().getLocales();

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

        if (DEBUG) Slog.d(TAG, "Switching user stage 3/3. newUserId=" + newUserId
                + " selectedIme=" + mSettings.getSelectedInputMethod());

        mLastSwitchUserId = newUserId;

        if (mIsInteractive && clientToBeReset != null) {
            final ClientState cs = mClients.get(clientToBeReset.asBinder());
            if (cs == null) {
                // The client is already gone.
                return;
            }
            try {
                cs.client.scheduleStartInputIfNecessary(mInFullscreenMode);
            } catch (RemoteException e) {
            }
        }
    }

    void updateCurrentProfileIds() {
        mSettings.setCurrentProfileIds(
                mUserManager.getProfileIdsWithDisabled(mSettings.getCurrentUserId()));
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The input method manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.wtf(TAG, "Input Method Manager Crash", e);
            }
            throw e;
        }
    }

    public void systemRunning(StatusBarManagerService statusBar) {
        synchronized (ImfLock.class) {
            if (DEBUG) {
                Slog.d(TAG, "--- systemReady");
            }
            if (!mSystemReady) {
                mSystemReady = true;
                mLastSystemLocales = mRes.getConfiguration().getLocales();
                final int currentUserId = mSettings.getCurrentUserId();
                mSettings.switchCurrentUser(currentUserId,
                        !mUserManagerInternal.isUserUnlockingOrUnlocked(currentUserId));
                mKeyguardManager = mContext.getSystemService(KeyguardManager.class);
                mNotificationManager = mContext.getSystemService(NotificationManager.class);
                mStatusBar = statusBar;
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

                final IntentFilter broadcastFilterForSystemUser = new IntentFilter();
                broadcastFilterForSystemUser.addAction(Intent.ACTION_USER_ADDED);
                broadcastFilterForSystemUser.addAction(Intent.ACTION_USER_REMOVED);
                broadcastFilterForSystemUser.addAction(Intent.ACTION_LOCALE_CHANGED);
                broadcastFilterForSystemUser.addAction(ACTION_SHOW_INPUT_METHOD_PICKER);
                mContext.registerReceiver(new ImmsBroadcastReceiverForSystemUser(),
                        broadcastFilterForSystemUser);

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

    // ---------------------------------------------------------------------------------------
    // Check whether or not this is a valid IPC. Assumes an IPC is valid when either
    // 1) it comes from the system process
    // 2) the calling process' user id is identical to the current user id IMMS thinks.
    @GuardedBy("ImfLock.class")
    private boolean calledFromValidUserLocked() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);
        if (DEBUG) {
            Slog.d(TAG, "--- calledFromForegroundUserOrSystemProcess ? "
                    + "calling uid = " + uid + " system uid = " + Process.SYSTEM_UID
                    + " calling userId = " + userId + ", foreground user id = "
                    + mSettings.getCurrentUserId() + ", calling pid = " + Binder.getCallingPid()
                    + InputMethodUtils.getApiCallStack());
        }
        if (uid == Process.SYSTEM_UID) {
            return true;
        }
        if (userId == mSettings.getCurrentUserId()) {
            return true;
        }

        // Caveat: A process which has INTERACT_ACROSS_USERS_FULL gets results for the
        // foreground user, not for the user of that process. Accordingly InputMethodManagerService
        // must not manage background users' states in any functions.
        // Note that privacy-sensitive IPCs, such as setInputMethod, are still securely guarded
        // by a token.
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                        == PackageManager.PERMISSION_GRANTED) {
            if (DEBUG) {
                Slog.d(TAG, "--- Access granted because the calling process has "
                        + "the INTERACT_ACROSS_USERS_FULL permission");
            }
            return true;
        }
        // TODO(b/34886274): The semantics of this verification is actually not well-defined.
        Slog.w(TAG, "--- IPC called from background users. Ignore. callers="
                + Debug.getCallers(10));
        return false;
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

    private List<InputMethodInfo> getInputMethodListInternal(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            final int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId,
                    mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return getInputMethodListLocked(resolvedUserIds[0], directBootAwareness);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @Override
    public List<InputMethodInfo> getInputMethodList(@UserIdInt int userId) {
        return getInputMethodListInternal(userId, DirectBootAwareness.AUTO);
    }

    @Override
    public List<InputMethodInfo> getAwareLockedInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        return getInputMethodListInternal(userId, directBootAwareness);
    }

    @Override
    public List<InputMethodInfo> getEnabledInputMethodList(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        synchronized (ImfLock.class) {
            final int[] resolvedUserIds = InputMethodUtils.resolveUserId(userId,
                    mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodListLocked(resolvedUserIds[0]);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getInputMethodListLocked(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        final ArrayList<InputMethodInfo> methodList;
        if (userId == mSettings.getCurrentUserId()
                && directBootAwareness == DirectBootAwareness.AUTO) {
            // Create a copy.
            methodList = new ArrayList<>(mMethodList);
        } else {
            final ArrayMap<String, InputMethodInfo> methodMap = new ArrayMap<>();
            methodList = new ArrayList<>();
            final ArrayMap<String, List<InputMethodSubtype>> additionalSubtypeMap =
                    new ArrayMap<>();
            AdditionalSubtypeUtils.load(additionalSubtypeMap, userId);
            queryInputMethodServicesInternal(mContext, userId, additionalSubtypeMap, methodMap,
                    methodList, directBootAwareness);
        }
        return methodList;
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodInfo> getEnabledInputMethodListLocked(@UserIdInt int userId) {
        if (userId == mSettings.getCurrentUserId()) {
            return mSettings.getEnabledInputMethodListLocked();
        }
        final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
        final InputMethodSettings settings = new InputMethodSettings(mContext.getResources(),
                mContext.getContentResolver(), methodMap, userId, true);
        return settings.getEnabledInputMethodListLocked();
    }

    @GuardedBy("ImfLock.class")
    private void onCreateInlineSuggestionsRequestLocked(@UserIdInt int userId,
            InlineSuggestionsRequestInfo requestInfo, IInlineSuggestionsRequestCallback callback,
            boolean touchExplorationEnabled) {
        final InputMethodInfo imi = mMethodMap.get(getSelectedMethodIdLocked());
        try {
            IInputMethodInvoker curMethod = getCurMethodLocked();
            if (userId == mSettings.getCurrentUserId() && curMethod != null
                    && imi != null && isInlineSuggestionsEnabled(imi, touchExplorationEnabled)) {
                final IInlineSuggestionsRequestCallback callbackImpl =
                        new InlineSuggestionsRequestCallbackDecorator(callback,
                                imi.getPackageName(), mCurTokenDisplayId, getCurTokenLocked(),
                                this);
                curMethod.onCreateInlineSuggestionsRequest(requestInfo, callbackImpl);
            } else {
                callback.onInlineSuggestionsUnsupported();
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException calling onCreateInlineSuggestionsRequest(): " + e);
        }
    }

    private static boolean isInlineSuggestionsEnabled(InputMethodInfo imi,
            boolean touchExplorationEnabled) {
        return imi.isInlineSuggestionsEnabled()
                && (!touchExplorationEnabled
                    || imi.supportsInlineSuggestionsWithTouchExploration());
    }

    /**
     * The decorator which validates the host package name in the
     * {@link InlineSuggestionsRequest} argument to make sure it matches the IME package name.
     */
    private static final class InlineSuggestionsRequestCallbackDecorator
            extends IInlineSuggestionsRequestCallback.Stub {
        @NonNull
        private final IInlineSuggestionsRequestCallback mCallback;
        @NonNull
        private final String mImePackageName;

        private final int mImeDisplayId;

        @NonNull
        private final IBinder mImeToken;
        @NonNull
        private final InputMethodManagerService mImms;

        InlineSuggestionsRequestCallbackDecorator(
                @NonNull IInlineSuggestionsRequestCallback callback, @NonNull String imePackageName,
                int displayId, @NonNull IBinder imeToken, @NonNull InputMethodManagerService imms) {
            mCallback = callback;
            mImePackageName = imePackageName;
            mImeDisplayId = displayId;
            mImeToken = imeToken;
            mImms = imms;
        }

        @Override
        public void onInlineSuggestionsUnsupported() throws RemoteException {
            mCallback.onInlineSuggestionsUnsupported();
        }

        @Override
        public void onInlineSuggestionsRequest(InlineSuggestionsRequest request,
                IInlineSuggestionsResponseCallback callback)
                throws RemoteException {
            if (!mImePackageName.equals(request.getHostPackageName())) {
                throw new SecurityException(
                        "Host package name in the provide request=[" + request.getHostPackageName()
                                + "] doesn't match the IME package name=[" + mImePackageName
                                + "].");
            }
            request.setHostDisplayId(mImeDisplayId);
            mImms.setCurHostInputToken(mImeToken, request.getHostInputToken());
            mCallback.onInlineSuggestionsRequest(request, callback);
        }

        @Override
        public void onInputMethodStartInput(AutofillId imeFieldId) throws RemoteException {
            mCallback.onInputMethodStartInput(imeFieldId);
        }

        @Override
        public void onInputMethodShowInputRequested(boolean requestResult) throws RemoteException {
            mCallback.onInputMethodShowInputRequested(requestResult);
        }

        @Override
        public void onInputMethodStartInputView() throws RemoteException {
            mCallback.onInputMethodStartInputView();
        }

        @Override
        public void onInputMethodFinishInputView() throws RemoteException {
            mCallback.onInputMethodFinishInputView();
        }

        @Override
        public void onInputMethodFinishInput() throws RemoteException {
            mCallback.onInputMethodFinishInput();
        }

        @Override
        public void onInlineSuggestionsSessionInvalidated() throws RemoteException {
            mCallback.onInlineSuggestionsSessionInvalidated();
        }
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
     * @param allowsImplicitlySelectedSubtypes {@code true} to return the implicitly selected
     *                                         subtypes.
     */
    @Override
    public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(String imiId,
            boolean allowsImplicitlySelectedSubtypes) {
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int[] resolvedUserIds = InputMethodUtils.resolveUserId(callingUserId,
                    mSettings.getCurrentUserId(), null);
            if (resolvedUserIds.length != 1) {
                return Collections.emptyList();
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                return getEnabledInputMethodSubtypeListLocked(imiId,
                        allowsImplicitlySelectedSubtypes, resolvedUserIds[0]);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private List<InputMethodSubtype> getEnabledInputMethodSubtypeListLocked(String imiId,
            boolean allowsImplicitlySelectedSubtypes, @UserIdInt int userId) {
        if (userId == mSettings.getCurrentUserId()) {
            final InputMethodInfo imi;
            String selectedMethodId = getSelectedMethodIdLocked();
            if (imiId == null && selectedMethodId != null) {
                imi = mMethodMap.get(selectedMethodId);
            } else {
                imi = mMethodMap.get(imiId);
            }
            if (imi == null) {
                return Collections.emptyList();
            }
            return mSettings.getEnabledInputMethodSubtypeListLocked(
                    mContext, imi, allowsImplicitlySelectedSubtypes);
        }
        final ArrayMap<String, InputMethodInfo> methodMap = queryMethodMapForUser(userId);
        final InputMethodInfo imi = methodMap.get(imiId);
        if (imi == null) {
            return Collections.emptyList();
        }
        final InputMethodSettings settings = new InputMethodSettings(mContext.getResources(),
                mContext.getContentResolver(), methodMap, userId, true);
        return settings.getEnabledInputMethodSubtypeListLocked(
                mContext, imi, allowsImplicitlySelectedSubtypes);
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
     * @param inputContext communication channel for the fallback {@link InputConnection}
     * @param selfReportedDisplayId self-reported display ID to which the client is associated.
     *                              Whether the client is still allowed to access to this display
     *                              or not needs to be evaluated every time the client interacts
     *                              with the display
     */
    @Override
    public void addClient(IInputMethodClient client, IInputContext inputContext,
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
                if (state.uid == callerUid && state.pid == callerPid
                        && state.selfReportedDisplayId == selfReportedDisplayId) {
                    throw new SecurityException("uid=" + callerUid + "/pid=" + callerPid
                            + "/displayId=" + selfReportedDisplayId + " is already registered.");
                }
            }
            final ClientDeathRecipient deathRecipient = new ClientDeathRecipient(this, client);
            try {
                client.asBinder().linkToDeath(deathRecipient, 0);
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
            mClients.put(client.asBinder(), new ClientState(client, inputContext, callerUid,
                    callerPid, selfReportedDisplayId, deathRecipient));
        }
    }

    void removeClient(IInputMethodClient client) {
        synchronized (ImfLock.class) {
            ClientState cs = mClients.remove(client.asBinder());
            if (cs != null) {
                client.asBinder().unlinkToDeath(cs.clientDeathRecipient, 0);
                clearClientSessionLocked(cs);
                clearClientSessionForAccessibilityLocked(cs);

                final int numItems = mVirtualDisplayIdToParentMap.size();
                for (int i = numItems - 1; i >= 0; --i) {
                    final VirtualDisplayInfo info = mVirtualDisplayIdToParentMap.valueAt(i);
                    if (info.mParentClient == cs) {
                        mVirtualDisplayIdToParentMap.removeAt(i);
                    }
                }

                if (mCurClient == cs) {
                    hideCurrentInputLocked(
                            mCurFocusedWindow, 0, null, SoftInputShowHideReason.HIDE_REMOVE_CLIENT);
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
                    mCurVirtualDisplayToScreenMatrix = null;
                }
                if (mCurFocusedWindowClient == cs) {
                    mCurFocusedWindowClient = null;
                }
            }
        }
    }

    @NonNull
    private Message obtainMessageOO(int what, Object arg1, Object arg2) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        return mHandler.obtainMessage(what, 0, 0, args);
    }

    @NonNull
    private Message obtainMessageOOO(int what, Object arg1, Object arg2, Object arg3) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.arg2 = arg2;
        args.arg3 = arg3;
        return mHandler.obtainMessage(what, 0, 0, args);
    }

    @NonNull
    private Message obtainMessageIIOO(int what, int arg1, int arg2,
            Object arg3, Object arg4) {
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg3;
        args.arg2 = arg4;
        return mHandler.obtainMessage(what, arg1, arg2, args);
    }

    @NonNull
    private Message obtainMessageIIIO(int what, int argi1, int argi2, int argi3, Object arg1) {
        final SomeArgs args = SomeArgs.obtain();
        args.arg1 = arg1;
        args.argi1 = argi1;
        args.argi2 = argi2;
        args.argi3 = argi3;
        return mHandler.obtainMessage(what, 0, 0, args);
    }

    private void executeOrSendMessage(IInputMethodClient target, Message msg) {
         if (target.asBinder() instanceof Binder) {
             // This is supposed to be emulating the one-way semantics when the IME client is
             // system_server itself, which has not been explicitly prohibited so far while we have
             // never ever officially supported such a use case...
             // We probably should create a simple wrapper of IInputMethodClient as the first step
             // to get rid of executeOrSendMessage() then should prohibit system_server to be the
             // IME client for long term.
             msg.sendToTarget();
         } else {
             handleMessage(msg);
             msg.recycle();
         }
    }

    @GuardedBy("ImfLock.class")
    void unbindCurrentClientLocked(@UnbindReason int unbindClientReason) {
        if (mCurClient != null) {
            if (DEBUG) Slog.v(TAG, "unbindCurrentInputLocked: client="
                    + mCurClient.client.asBinder());
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
            scheduleSetActiveToClient(mCurClient, false /* active */, false /* fullscreen */,
                    false /* reportToImeController */);
            executeOrSendMessage(mCurClient.client, mHandler.obtainMessage(
                    MSG_UNBIND_CLIENT, getSequenceNumberLocked(), unbindClientReason,
                    mCurClient.client));
            mCurClient.sessionRequested = false;
            mCurClient.mSessionRequestedForAccessibility = false;
            mCurClient = null;
            mCurVirtualDisplayToScreenMatrix = null;

            mMenuController.hideInputMethodMenuLocked();
        }
    }

    @GuardedBy("ImfLock.class")
    void clearInputShowRequestLocked() {
        mShowRequested = mInputShown;
        mInputShown = false;
    }

    @GuardedBy("ImfLock.class")
    private int getImeShowFlagsLocked() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethod.SHOW_FORCED
                    | InputMethod.SHOW_EXPLICIT;
        } else if (mShowExplicitlyRequested) {
            flags |= InputMethod.SHOW_EXPLICIT;
        }
        return flags;
    }

    @GuardedBy("ImfLock.class")
    private int getAppShowFlagsLocked() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethodManager.SHOW_FORCED;
        } else if (!mShowExplicitlyRequested) {
            flags |= InputMethodManager.SHOW_IMPLICIT;
        }
        return flags;
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    InputBindResult attachNewInputLocked(@StartInputReason int startInputReason, boolean initial) {
        if (!mBoundToMethod) {
            getCurMethodLocked().bindInput(mCurClient.binding);
            mBoundToMethod = true;
        }

        final boolean restarting = !initial;
        final Binder startInputToken = new Binder();
        final StartInputInfo info = new StartInputInfo(mSettings.getCurrentUserId(),
                getCurTokenLocked(),
                mCurTokenDisplayId, getCurIdLocked(), startInputReason, restarting,
                UserHandle.getUserId(mCurClient.uid), mCurClient.selfReportedDisplayId,
                mCurFocusedWindow, mCurAttribute, mCurFocusedWindowSoftInputMode,
                getSequenceNumberLocked());
        mImeTargetWindowMap.put(startInputToken, mCurFocusedWindow);
        mStartInputHistory.addEntry(info);

        // Seems that PackageManagerInternal#grantImplicitAccess() doesn't handle cross-user
        // implicit visibility (e.g. IME[user=10] -> App[user=0]) thus we do this only for the
        // same-user scenarios.
        // That said ignoring cross-user scenario will never affect IMEs that do not have
        // INTERACT_ACROSS_USERS(_FULL) permissions, which is actually almost always the case.
        if (mSettings.getCurrentUserId() == UserHandle.getUserId(mCurClient.uid)) {
            mPackageManagerInternal.grantImplicitAccess(mSettings.getCurrentUserId(),
                    null /* intent */, UserHandle.getAppId(getCurMethodUidLocked()), mCurClient.uid,
                    true /* direct */);
        }

        @InputMethodNavButtonFlags
        final int navButtonFlags = getInputMethodNavButtonFlagsLocked();
        final SessionState session = mCurClient.curSession;
        setEnabledSessionLocked(session);
        session.method.startInput(startInputToken, mCurInputContext, mCurAttribute, restarting,
                navButtonFlags);
        if (mShowRequested) {
            if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
            showCurrentInputLocked(mCurFocusedWindow, getAppShowFlagsLocked(), null,
                    SoftInputShowHideReason.ATTACH_NEW_INPUT);
        }

        String curId = getCurIdLocked();
        final InputMethodInfo curInputMethodInfo = mMethodMap.get(curId);
        final boolean suppressesSpellChecker =
                curInputMethodInfo != null && curInputMethodInfo.suppressesSpellChecker();
        final SparseArray<IInputMethodSession> accessibilityInputMethodSessions =
                createAccessibilityInputMethodSessions(mCurClient.mAccessibilitySessions);
        return new InputBindResult(InputBindResult.ResultCode.SUCCESS_WITH_IME_SESSION,
                session.session, accessibilityInputMethodSessions,
                (session.channel != null ? session.channel.dup() : null),
                curId, getSequenceNumberLocked(), mCurVirtualDisplayToScreenMatrix,
                suppressesSpellChecker);
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    private Matrix getVirtualDisplayToScreenMatrixLocked(int clientDisplayId, int imeDisplayId) {
        if (clientDisplayId == imeDisplayId) {
            return null;
        }
        int displayId = clientDisplayId;
        Matrix matrix = null;
        while (true) {
            final VirtualDisplayInfo info = mVirtualDisplayIdToParentMap.get(displayId);
            if (info == null) {
                return null;
            }
            if (matrix == null) {
                matrix = new Matrix(info.mMatrix);
            } else {
                matrix.postConcat(info.mMatrix);
            }
            if (info.mParentClient.selfReportedDisplayId == imeDisplayId) {
                return matrix;
            }
            displayId = info.mParentClient.selfReportedDisplayId;
        }
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    InputBindResult attachNewAccessibilityLocked(@StartInputReason int startInputReason,
            boolean initial, int id) {
        if (!mBoundToAccessibility) {
            AccessibilityManagerInternal.get().bindInput(mCurClient.binding);
            mBoundToAccessibility = true;
        }

        // TODO(b/187453053): grantImplicitAccess to accessibility services access? if so, need to
        //  record accessibility services uid.

        final AccessibilitySessionState accessibilitySession =
                mCurClient.mAccessibilitySessions.get(id);
        // We don't start input when session for a11y is created. We start input when
        // input method start input, a11y manager service is always on.
        if (startInputReason != StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY) {
            final Binder startInputToken = new Binder();
            setEnabledSessionForAccessibilityLocked(mCurClient.mAccessibilitySessions);
            AccessibilityManagerInternal.get().startInput(startInputToken, mCurInputContext,
                    mCurAttribute, !initial /* restarting */);
        }

        if (accessibilitySession != null) {
            final SessionState session = mCurClient.curSession;
            IInputMethodSession imeSession = session == null ? null : session.session;
            final SparseArray<IInputMethodSession> accessibilityInputMethodSessions =
                    createAccessibilityInputMethodSessions(mCurClient.mAccessibilitySessions);
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION,
                    imeSession, accessibilityInputMethodSessions, null,
                    getCurIdLocked(), getSequenceNumberLocked(), mCurVirtualDisplayToScreenMatrix,
                    false);
        }
        return null;
    }

    private SparseArray<IInputMethodSession> createAccessibilityInputMethodSessions(
            SparseArray<AccessibilitySessionState> accessibilitySessions) {
        final SparseArray<IInputMethodSession> accessibilityInputMethodSessions =
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
            IInputContext inputContext, @NonNull EditorInfo attribute,
            @StartInputFlags int startInputFlags, @StartInputReason int startInputReason,
            int unverifiedTargetSdkVersion) {
        // If no method is currently selected, do nothing.
        final String selectedMethodId = getSelectedMethodIdLocked();
        if (selectedMethodId == null) {
            return InputBindResult.NO_IME;
        }

        if (!mSystemReady) {
            // If the system is not yet ready, we shouldn't be running third
            // party code.
            return new InputBindResult(
                    InputBindResult.ResultCode.ERROR_SYSTEM_NOT_READY,
                    null, null, null, selectedMethodId, getSequenceNumberLocked(), null, false);
        }

        if (!InputMethodUtils.checkIfPackageBelongsToUid(mAppOpsManager, cs.uid,
                attribute.packageName)) {
            Slog.e(TAG, "Rejecting this client as it reported an invalid package name."
                    + " uid=" + cs.uid + " package=" + attribute.packageName);
            return InputBindResult.INVALID_PACKAGE_NAME;
        }

        // Compute the final shown display ID with validated cs.selfReportedDisplayId for this
        // session & other conditions.
        mDisplayIdToShowIme = computeImeDisplayIdForTarget(cs.selfReportedDisplayId,
                mImeDisplayValidator);

        if (mDisplayIdToShowIme == INVALID_DISPLAY) {
            mImeHiddenByDisplayPolicy = true;
            hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                    SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE);
            return InputBindResult.NO_IME;
        }
        mImeHiddenByDisplayPolicy = false;

        if (mCurClient != cs) {
            prepareClientSwitchLocked(cs);
        }

        // Bump up the sequence for this client and attach it.
        advanceSequenceNumberLocked();
        mCurClient = cs;
        mCurInputContext = inputContext;
        mCurVirtualDisplayToScreenMatrix =
                getVirtualDisplayToScreenMatrixLocked(cs.selfReportedDisplayId,
                        mDisplayIdToShowIme);
        mCurAttribute = attribute;

        // If configured, we want to avoid starting up the IME if it is not supposed to be showing
        if (shouldPreventImeStartupLocked(selectedMethodId, startInputFlags,
                unverifiedTargetSdkVersion)) {
            if (DEBUG) {
                Slog.d(TAG, "Avoiding IME startup and unbinding current input method.");
            }
            mBindingController.unbindCurrentMethod();
            return InputBindResult.NO_EDITOR;
        }

        // Check if the input method is changing.
        // We expect the caller has already verified that the client is allowed to access this
        // display ID.
        if (isSelectedMethodBoundLocked()) {
            if (cs.curSession != null) {
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
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0, -1);
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

    @GuardedBy("ImfLock.class")
    private boolean shouldPreventImeStartupLocked(
            @NonNull String selectedMethodId,
            @StartInputFlags int startInputFlags,
            int unverifiedTargetSdkVersion) {
        // Fast-path for the majority of cases
        if (!mPreventImeStartupUnlessTextEditor) {
            return false;
        }

        final boolean imeVisibleAllowed =
                isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags);

        return !(imeVisibleAllowed
                || mShowRequested
                || isNonPreemptibleImeLocked(selectedMethodId));
    }

    /** Return {@code true} if the given IME is non-preemptible like the tv remote service. */
    @GuardedBy("ImfLock.class")
    private boolean isNonPreemptibleImeLocked(@NonNull  String selectedMethodId) {
        final InputMethodInfo imi = mMethodMap.get(selectedMethodId);
        if (imi != null) {
            return mNonPreemptibleInputMethods.contains(imi.getPackageName());
        }
        return false;
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
            scheduleSetActiveToClient(cs, true /* active */, false /* fullscreen */,
                    false /* reportToImeController */);
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
                        null, null, null, getCurIdLocked(), getSequenceNumberLocked(), null, false);
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
                            null, null, null, getCurIdLocked(), getSequenceNumberLocked(), null,
                            false);
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
    void initializeImeLocked(@NonNull IInputMethodInvoker inputMethod, @NonNull IBinder token,
            @android.content.pm.ActivityInfo.Config int configChanges, boolean supportStylusHw) {
        if (DEBUG) {
            Slog.v(TAG, "Sending attach of token: " + token + " for display: "
                    + mCurTokenDisplayId);
        }
        inputMethod.initializeInternal(token, new InputMethodPrivilegedOperationsImpl(this, token),
                configChanges, supportStylusHw, getInputMethodNavButtonFlagsLocked());
    }

    @AnyThread
    void scheduleResetStylusHandwriting() {
        mHandler.obtainMessage(MSG_RESET_HANDWRITING).sendToTarget();
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
                        mCurClient.curSession = new SessionState(mCurClient,
                                method, session, channel);
                        InputBindResult res = attachNewInputLocked(
                                StartInputReason.SESSION_CREATED_BY_IME, true);
                        attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_IME,
                                true, -1);
                        if (res.method != null) {
                            executeOrSendMessage(mCurClient.client, obtainMessageOO(
                                    MSG_BIND_CLIENT, mCurClient.client, res));
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
        if (!cs.sessionRequested) {
            if (DEBUG) Slog.v(TAG, "Creating new session for client " + cs);
            final InputChannel serverChannel;
            final InputChannel clientChannel;
            {
                final InputChannel[] channels = InputChannel.openInputChannelPair(cs.toString());
                serverChannel = channels[0];
                clientChannel = channels[1];
            }

            cs.sessionRequested = true;

            final IInputMethodInvoker curMethod = getCurMethodLocked();
            final IInputSessionCallback.Stub callback = new IInputSessionCallback.Stub() {
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
        finishSessionLocked(cs.curSession);
        cs.curSession = null;
        cs.sessionRequested = false;
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
            if (sessionState.session != null) {
                try {
                    sessionState.session.finishSession();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Session failed to close due to remote exception", e);
                    updateSystemUiLocked(0 /* vis */, mBackDisposition);
                }
                sessionState.session = null;
            }
            if (sessionState.channel != null) {
                sessionState.channel.dispose();
                sessionState.channel = null;
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
                    CharSequence contentDescription = null;
                    try {
                        // Use PackageManager to load label
                        final PackageManager packageManager = mContext.getPackageManager();
                        contentDescription = packageManager.getApplicationLabel(
                                mIPackageManager.getApplicationInfo(packageName, 0,
                                        mSettings.getCurrentUserId()));
                    } catch (RemoteException e) {
                        /* ignore */
                    }
                    if (mStatusBar != null) {
                        mStatusBar.setIcon(mSlotIme, packageName, iconId, 0,
                                contentDescription  != null
                                        ? contentDescription.toString() : null);
                        mStatusBar.setIconVisibility(mSlotIme, true);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private void hideStatusBarIconLocked() {
        if (mStatusBar != null) {
            mStatusBar.setIconVisibility(mSlotIme, false);
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
        final boolean canImeDrawsImeNavBar =
                mImeDrawsImeNavBarRes != null && mImeDrawsImeNavBarRes.get();
        final boolean shouldShowImeSwitcherWhenImeIsShown = shouldShowImeSwitcherLocked(
                InputMethodService.IME_ACTIVE | InputMethodService.IME_VISIBLE);
        return (canImeDrawsImeNavBar ? InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR : 0)
                | (shouldShowImeSwitcherWhenImeIsShown
                        ? InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN : 0);
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldShowImeSwitcherLocked(int visibility) {
        if (!mShowOngoingImeSwitcherForPhones) return false;
        if (mMenuController.getSwitchingDialogLocked() != null) return false;
        if (mWindowManagerInternal.isKeyguardShowingAndNotOccluded()
                && mKeyguardManager != null && mKeyguardManager.isKeyguardSecure()) return false;
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

        List<InputMethodInfo> imis = mSettings.getEnabledInputMethodListWithFilterLocked(
                InputMethodInfo::shouldShowInInputMethodPicker);
        final int N = imis.size();
        if (N > 2) return true;
        if (N < 1) return false;
        int nonAuxCount = 0;
        int auxCount = 0;
        InputMethodSubtype nonAuxSubtype = null;
        InputMethodSubtype auxSubtype = null;
        for(int i = 0; i < N; ++i) {
            final InputMethodInfo imi = imis.get(i);
            final List<InputMethodSubtype> subtypes =
                    mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true);
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
        // all updateSystemUi happens on system previlege.
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
            // mImeWindowVis should be updated before calling shouldShowImeSwitcherLocked().
            final boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis);
            if (mStatusBar != null) {
                mStatusBar.setImeWindowStatus(mCurTokenDisplayId, getCurTokenLocked(), vis,
                        backDisposition, needsToShowImeSwitcher);
            }
            final InputMethodInfo imi = mMethodMap.get(getSelectedMethodIdLocked());
            if (imi != null && needsToShowImeSwitcher) {
                // Used to load label
                final CharSequence title = mRes.getText(
                        com.android.internal.R.string.select_input_method);
                final CharSequence summary = InputMethodUtils.getImeAndSubtypeDisplayName(
                        mContext, imi, mCurrentSubtype);
                mImeSwitcherNotification.setContentTitle(title)
                        .setContentText(summary)
                        .setContentIntent(mImeSwitchPendingIntent);
                try {
                    // TODO(b/120076400): Figure out what is the best behavior
                    if ((mNotificationManager != null)
                            && !mIWindowManager.hasNavigationBar(DEFAULT_DISPLAY)) {
                        if (DEBUG) {
                            Slog.d(TAG, "--- show notification: label =  " + summary);
                        }
                        mNotificationManager.notifyAsUser(null,
                                SystemMessage.NOTE_SELECT_INPUT_METHOD,
                                mImeSwitcherNotification.build(), UserHandle.ALL);
                        mNotificationShown = true;
                    }
                } catch (RemoteException e) {
                }
            } else {
                if (mNotificationShown && mNotificationManager != null) {
                    if (DEBUG) {
                        Slog.d(TAG, "--- hide notification");
                    }
                    mNotificationManager.cancelAsUser(null,
                            SystemMessage.NOTE_SELECT_INPUT_METHOD, UserHandle.ALL);
                    mNotificationShown = false;
                }
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
            List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
            for (int i=0; i<enabled.size(); i++) {
                // We allow the user to select "disabled until used" apps, so if they
                // are enabling one of those here we now need to make it enabled.
                InputMethodInfo imm = enabled.get(i);
                try {
                    ApplicationInfo ai = mIPackageManager.getApplicationInfo(imm.getPackageName(),
                            PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS,
                            mSettings.getCurrentUserId());
                    if (ai != null && ai.enabledSetting
                            == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                        if (DEBUG) {
                            Slog.d(TAG, "Update state(" + imm.getId()
                                    + "): DISABLED_UNTIL_USED -> DEFAULT");
                        }
                        mIPackageManager.setApplicationEnabledSetting(imm.getPackageName(),
                                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                                PackageManager.DONT_KILL_APP, mSettings.getCurrentUserId(),
                                mContext.getBasePackageName());
                    }
                } catch (RemoteException e) {
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
        // Here is not the perfect place to reset the switching controller. Ideally
        // mSwitchingController and mSettings should be able to share the same state.
        // TODO: Make sure that mSwitchingController and mSettings are sharing the
        // the same enabled IMEs list.
        mSwitchingController.resetCircularListLocked(mContext);

        sendOnNavButtonFlagsChangedLocked();
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeId) {
        InputMethodInfo info = mMethodMap.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + id);
        }

        // See if we need to notify a subtype change within the same IME.
        if (id.equals(getSelectedMethodIdLocked())) {
            final int subtypeCount = info.getSubtypeCount();
            if (subtypeCount <= 0) {
                return;
            }
            final InputMethodSubtype oldSubtype = mCurrentSubtype;
            final InputMethodSubtype newSubtype;
            if (subtypeId >= 0 && subtypeId < subtypeCount) {
                newSubtype = info.getSubtypeAt(subtypeId);
            } else {
                // If subtype is null, try to find the most applicable one from
                // getCurrentInputMethodSubtype.
                newSubtype = getCurrentInputMethodSubtypeLocked();
            }
            if (newSubtype == null || oldSubtype == null) {
                Slog.w(TAG, "Illegal subtype state: old subtype = " + oldSubtype
                        + ", new subtype = " + newSubtype);
                return;
            }
            if (newSubtype != oldSubtype) {
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
        final long ident = Binder.clearCallingIdentity();
        try {
            // Set a subtype to this input method.
            // subtypeId the name of a subtype which will be set.
            setSelectedInputMethodAndSubtypeLocked(info, subtypeId, false);
            // mCurMethodId should be updated after setSelectedInputMethodAndSubtypeLocked()
            // because mCurMethodId is stored as a history in
            // setSelectedInputMethodAndSubtypeLocked().
            setSelectedMethodIdLocked(id);

            if (LocalServices.getService(ActivityManagerInternal.class).isSystemReady()) {
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
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken, int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showSoftInput");
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput");
        synchronized (ImfLock.class) {
            if (!calledFromValidUserLocked()) {
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (!canInteractWithImeLocked(uid, client, "showSoftInput")) {
                    return false;
                }
                if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
                return showCurrentInputLocked(windowToken, flags, resultReceiver, reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @BinderThread
    @Override
    public void startStylusHandwriting(IInputMethodClient client) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.startStylusHandwriting");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#startStylusHandwriting");
        int uid = Binder.getCallingUid();
        synchronized (ImfLock.class) {
            if (!calledFromValidUserLocked()) {
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (!canInteractWithImeLocked(uid, client, "startStylusHandwriting")) {
                    return;
                }
                if (!mBindingController.supportsStylusHandwriting()) {
                    Slog.w(TAG, "Stylus HW unsupported by IME. Ignoring startStylusHandwriting()");
                    return;
                }
                final OptionalInt requestId = mHwController.getCurrentRequestId();
                if (!requestId.isPresent()) {
                    Slog.e(TAG, "Stylus handwriting was not initialized.");
                    return;
                }
                if (!mHwController.isStylusGestureOngoing()) {
                    Slog.e(TAG, "There is no ongoing stylus gesture to start stylus handwriting.");
                    return;
                }
                if (DEBUG) Slog.v(TAG, "Client requesting Stylus Handwriting to be started");
                final IInputMethodInvoker curMethod = getCurMethodLocked();
                if (curMethod != null) {
                    curMethod.canStartStylusHandwriting(requestId.getAsInt());
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @BinderThread
    @Override
    public void reportPerceptibleAsync(IBinder windowToken, boolean perceptible) {
        Objects.requireNonNull(windowToken, "windowToken must not be null");
        int uid = Binder.getCallingUid();
        synchronized (ImfLock.class) {
            if (!calledFromValidUserLocked()) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                if (mCurFocusedWindow == windowToken
                        && mCurPerceptible != perceptible) {
                    mCurPerceptible = perceptible;
                    updateSystemUiLocked(mImeWindowVis, mBackDisposition);
                }
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    boolean showCurrentInputLocked(IBinder windowToken, int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        mShowRequested = true;
        if (mAccessibilityRequestingNoSoftKeyboard || mImeHiddenByDisplayPolicy) {
            return false;
        }

        if ((flags&InputMethodManager.SHOW_FORCED) != 0) {
            mShowExplicitlyRequested = true;
            mShowForced = true;
        } else if ((flags&InputMethodManager.SHOW_IMPLICIT) == 0) {
            mShowExplicitlyRequested = true;
        }

        if (!mSystemReady) {
            return false;
        }

        mBindingController.setCurrentMethodVisible();
        final IInputMethodInvoker curMethod = getCurMethodLocked();
        if (curMethod != null) {
            // create a placeholder token for IMS so that IMS cannot inject windows into client app.
            Binder showInputToken = new Binder();
            mShowRequestWindowMap.put(showInputToken, windowToken);
            final int showFlags = getImeShowFlagsLocked();
            if (DEBUG) {
                Slog.v(TAG, "Calling " + curMethod + ".showSoftInput(" + showInputToken
                        + ", " + showFlags + ", " + resultReceiver + ") for reason: "
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
            }
            // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
            if (curMethod.showSoftInput(showInputToken, showFlags, resultReceiver)) {
                onShowHideSoftInputRequested(true /* show */, windowToken, reason);
            }
            mInputShown = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken, int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason) {
        int uid = Binder.getCallingUid();
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput");
        synchronized (ImfLock.class) {
            if (!InputMethodManagerService.this.calledFromValidUserLocked()) {
                return false;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideSoftInput");
                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    // We need to check if this is the current client with
                    // focus in the window manager, to allow this call to
                    // be made before input is started in it.
                    final ClientState cs = mClients.get(client.asBinder());
                    if (cs == null) {
                        throw new IllegalArgumentException("unknown client " + client.asBinder());
                    }
                    if (!isImeClientFocused(windowToken, cs)) {
                        if (DEBUG) {
                            Slog.w(TAG, "Ignoring hideSoftInput of uid " + uid + ": " + client);
                        }
                        return false;
                    }
                }

                if (DEBUG) Slog.v(TAG, "Client requesting input be hidden");
                return InputMethodManagerService.this.hideCurrentInputLocked(windowToken,
                        flags, resultReceiver, reason);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    boolean hideCurrentInputLocked(IBinder windowToken, int flags, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason) {
        if ((flags&InputMethodManager.HIDE_IMPLICIT_ONLY) != 0
                && (mShowExplicitlyRequested || mShowForced)) {
            if (DEBUG) Slog.v(TAG, "Not hiding: explicit show not cancelled by non-explicit hide");
            return false;
        }
        if (mShowForced && (flags&InputMethodManager.HIDE_NOT_ALWAYS) != 0) {
            if (DEBUG) Slog.v(TAG, "Not hiding: forced show not cancelled by not-always hide");
            return false;
        }

        // There is a chance that IMM#hideSoftInput() is called in a transient state where
        // IMMS#InputShown is already updated to be true whereas IMMS#mImeWindowVis is still waiting
        // to be updated with the new value sent from IME process.  Even in such a transient state
        // historically we have accepted an incoming call of IMM#hideSoftInput() from the
        // application process as a valid request, and have even promised such a behavior with CTS
        // since Android Eclair.  That's why we need to accept IMM#hideSoftInput() even when only
        // IMMS#InputShown indicates that the software keyboard is shown.
        // TODO: Clean up, IMMS#mInputShown, IMMS#mImeWindowVis and mShowRequested.
        IInputMethodInvoker curMethod = getCurMethodLocked();
        final boolean shouldHideSoftInput = (curMethod != null) && (mInputShown
                || (mImeWindowVis & InputMethodService.IME_ACTIVE) != 0);
        boolean res;
        if (shouldHideSoftInput) {
            final Binder hideInputToken = new Binder();
            mHideRequestWindowMap.put(hideInputToken, windowToken);
            // The IME will report its visible state again after the following message finally
            // delivered to the IME process as an IPC.  Hence the inconsistency between
            // IMMS#mInputShown and IMMS#mImeWindowVis should be resolved spontaneously in
            // the final state.
            if (DEBUG) {
                Slog.v(TAG, "Calling " + curMethod + ".hideSoftInput(0, " + hideInputToken
                        + ", " + resultReceiver + ") for reason: "
                        + InputMethodDebug.softInputDisplayReasonToString(reason));
            }
            // TODO(b/192412909): Check if we can always call onShowHideSoftInputRequested() or not.
            if (curMethod.hideSoftInput(hideInputToken, 0 /* flags */, resultReceiver)) {
                onShowHideSoftInputRequested(false /* show */, windowToken, reason);
            }
            res = true;
        } else {
            res = false;
        }
        mBindingController.setCurrentMethodNotVisible();
        mInputShown = false;
        mShowRequested = false;
        mShowExplicitlyRequested = false;
        mShowForced = false;
        return res;
    }

    private boolean isImeClientFocused(IBinder windowToken, ClientState cs) {
        final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                windowToken, cs.uid, cs.pid, cs.selfReportedDisplayId);
        return imeClientFocus == WindowManagerInternal.ImeClientFocusResult.HAS_IME_FOCUS;
    }

    @NonNull
    @Override
    public InputBindResult startInputOrWindowGainedFocus(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo attribute, IInputContext inputContext,
            int unverifiedTargetSdkVersion) {
        return startInputOrWindowGainedFocusInternal(startInputReason, client, windowToken,
                startInputFlags, softInputMode, windowFlags, attribute, inputContext,
                unverifiedTargetSdkVersion);
    }

    @NonNull
    private InputBindResult startInputOrWindowGainedFocusInternal(
            @StartInputReason int startInputReason, IInputMethodClient client, IBinder windowToken,
            @StartInputFlags int startInputFlags, @SoftInputModeFlags int softInputMode,
            int windowFlags, @Nullable EditorInfo attribute, @Nullable IInputContext inputContext,
            int unverifiedTargetSdkVersion) {
        if (windowToken == null) {
            Slog.e(TAG, "windowToken cannot be null.");
            return InputBindResult.NULL;
        }
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "IMMS.startInputOrWindowGainedFocus");
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startInputOrWindowGainedFocus");
            final int callingUserId = UserHandle.getCallingUserId();
            final int userId;
            if (attribute != null && attribute.targetInputMethodUser != null
                    && attribute.targetInputMethodUser.getIdentifier() != callingUserId) {
                mContext.enforceCallingPermission(
                        Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "Using EditorInfo.targetInputMethodUser requires"
                                + " INTERACT_ACROSS_USERS_FULL.");
                userId = attribute.targetInputMethodUser.getIdentifier();
                if (!mUserManagerInternal.isUserRunning(userId)) {
                    // There is a chance that we hit here because of race condition. Let's just
                    // return an error code instead of crashing the caller process, which at
                    // least has INTERACT_ACROSS_USERS_FULL permission thus is likely to be an
                    // important process.
                    Slog.e(TAG, "User #" + userId + " is not running.");
                    return InputBindResult.INVALID_USER;
                }
            } else {
                userId = callingUserId;
            }
            final InputBindResult result;
            synchronized (ImfLock.class) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    result = startInputOrWindowGainedFocusInternalLocked(startInputReason,
                            client, windowToken, startInputFlags, softInputMode, windowFlags,
                            attribute, inputContext, unverifiedTargetSdkVersion, userId);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            if (result == null) {
                // This must never happen, but just in case.
                Slog.wtf(TAG, "InputBindResult is @NonNull. startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason)
                        + " windowFlags=#" + Integer.toHexString(windowFlags)
                        + " editorInfo=" + attribute);
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
            @SoftInputModeFlags int softInputMode, int windowFlags, EditorInfo attribute,
            IInputContext inputContext, int unverifiedTargetSdkVersion, @UserIdInt int userId) {
        if (DEBUG) {
            Slog.v(TAG, "startInputOrWindowGainedFocusInternalLocked: reason="
                    + InputMethodDebug.startInputReasonToString(startInputReason)
                    + " client=" + client.asBinder()
                    + " inputContext=" + inputContext
                    + " attribute=" + attribute
                    + " startInputFlags="
                    + InputMethodDebug.startInputFlagsToString(startInputFlags)
                    + " softInputMode=" + InputMethodDebug.softInputModeToString(softInputMode)
                    + " windowFlags=#" + Integer.toHexString(windowFlags)
                    + " unverifiedTargetSdkVersion=" + unverifiedTargetSdkVersion);
        }

        final ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client " + client.asBinder());
        }

        final int imeClientFocus = mWindowManagerInternal.hasInputMethodClientFocus(
                windowToken, cs.uid, cs.pid, cs.selfReportedDisplayId);
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
                    Slog.w(TAG, "Focus gain on non-focused client " + cs.client
                            + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                }
                return InputBindResult.NOT_IME_TARGET_WINDOW;
            case WindowManagerInternal.ImeClientFocusResult.INVALID_DISPLAY_ID:
                return InputBindResult.INVALID_DISPLAY_ID;
        }

        if (mUserSwitchHandlerTask != null) {
            // There is already an on-going pending user switch task.
            final int nextUserId = mUserSwitchHandlerTask.mToUserId;
            if (userId == nextUserId) {
                scheduleSwitchUserTaskLocked(userId, cs.client);
                return InputBindResult.USER_SWITCHING;
            }
            for (int profileId : mUserManager.getProfileIdsWithDisabled(nextUserId)) {
                if (profileId == userId) {
                    scheduleSwitchUserTaskLocked(userId, cs.client);
                    return InputBindResult.USER_SWITCHING;
                }
            }
            return InputBindResult.INVALID_USER;
        }

        final boolean shouldClearFlag = mImePlatformCompatUtils.shouldClearShowForcedFlag(cs.uid);
        // In case mShowForced flag affects the next client to keep IME visible, when the current
        // client is leaving due to the next focused client, we clear mShowForced flag when the
        // next client's targetSdkVersion is T or higher.
        if (mCurFocusedWindow != windowToken && mShowForced && shouldClearFlag) {
            mShowForced = false;
        }

        // cross-profile access is always allowed here to allow profile-switching.
        if (!mSettings.isCurrentProfile(userId)) {
            Slog.w(TAG, "A background user is requesting window. Hiding IME.");
            Slog.w(TAG, "If you need to impersonate a foreground user/profile from"
                    + " a background user, use EditorInfo.targetInputMethodUser with"
                    + " INTERACT_ACROSS_USERS_FULL permission.");
            hideCurrentInputLocked(
                    mCurFocusedWindow, 0, null, SoftInputShowHideReason.HIDE_INVALID_USER);
            return InputBindResult.INVALID_USER;
        }

        if (userId != mSettings.getCurrentUserId()) {
            scheduleSwitchUserTaskLocked(userId, cs.client);
            return InputBindResult.USER_SWITCHING;
        }

        final boolean sameWindowFocused = mCurFocusedWindow == windowToken;
        final boolean isTextEditor = (startInputFlags & StartInputFlags.IS_TEXT_EDITOR) != 0;
        final boolean startInputByWinGainedFocus =
                (startInputFlags & StartInputFlags.WINDOW_GAINED_FOCUS) != 0;

        if (sameWindowFocused && isTextEditor) {
            if (DEBUG) {
                Slog.w(TAG, "Window already focused, ignoring focus gain of: " + client
                        + " attribute=" + attribute + ", token = " + windowToken
                        + ", startInputReason="
                        + InputMethodDebug.startInputReasonToString(startInputReason));
            }
            if (attribute != null) {
                return startInputUncheckedLocked(cs, inputContext, attribute, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion);
            }
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                    null, null, null, null, -1, null, false);
        }

        mCurFocusedWindow = windowToken;
        mCurFocusedWindowSoftInputMode = softInputMode;
        mCurFocusedWindowClient = cs;
        mCurPerceptible = true;

        // Should we auto-show the IME even if the caller has not
        // specified what should be done with it?
        // We only do this automatically if the window can resize
        // to accommodate the IME (so what the user sees will give
        // them good context without input information being obscured
        // by the IME) or if running on a large screen where there
        // is more room for the target window + IME.
        final boolean doAutoShow =
                (softInputMode & LayoutParams.SOFT_INPUT_MASK_ADJUST)
                        == LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                || mRes.getConfiguration().isLayoutSizeAtLeast(
                        Configuration.SCREENLAYOUT_SIZE_LARGE);

        // We want to start input before showing the IME, but after closing
        // it.  We want to do this after closing it to help the IME disappear
        // more quickly (not get stuck behind it initializing itself for the
        // new focused input, even if its window wants to hide the IME).
        boolean didStart = false;

        InputBindResult res = null;
        // We shows the IME when the system allows the IME focused target window to restore the
        // IME visibility (e.g. switching to the app task when last time the IME is visible).
        // Note that we don't restore IME visibility for some cases (e.g. when the soft input
        // state is ALWAYS_HIDDEN or STATE_HIDDEN with forward navigation).
        // Because the app might leverage these flags to hide soft-keyboard with showing their own
        // UI for input.
        if (isTextEditor && attribute != null
                && shouldRestoreImeVisibility(windowToken, softInputMode)) {
            res = startInputUncheckedLocked(cs, inputContext, attribute, startInputFlags,
                    startInputReason, unverifiedTargetSdkVersion);
            showCurrentInputLocked(windowToken, InputMethodManager.SHOW_IMPLICIT, null,
                    SoftInputShowHideReason.SHOW_RESTORE_IME_VISIBILITY);
            return res;
        }

        switch (softInputMode & LayoutParams.SOFT_INPUT_MASK_STATE) {
            case LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                if (!sameWindowFocused && (!isTextEditor || !doAutoShow)) {
                    if (LayoutParams.mayUseInputMethod(windowFlags)) {
                        // There is no focus view, and this window will
                        // be behind any soft input window, so hide the
                        // soft input window if it is shown.
                        if (DEBUG) Slog.v(TAG, "Unspecified window will hide input");
                        hideCurrentInputLocked(
                                mCurFocusedWindow, InputMethodManager.HIDE_NOT_ALWAYS, null,
                                SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW);

                        // If focused display changed, we should unbind current method
                        // to make app window in previous display relayout after Ime
                        // window token removed.
                        // Note that we can trust client's display ID as long as it matches
                        // to the display ID obtained from the window.
                        if (cs.selfReportedDisplayId != mCurTokenDisplayId) {
                            mBindingController.unbindCurrentMethod();
                        }
                    }
                } else if (isTextEditor && doAutoShow
                        && (softInputMode & LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    // There is a focus view, and we are navigating forward
                    // into the window, so show the input window for the user.
                    // We only do this automatically if the window can resize
                    // to accommodate the IME (so what the user sees will give
                    // them good context without input information being obscured
                    // by the IME) or if running on a large screen where there
                    // is more room for the target window + IME.
                    if (DEBUG) Slog.v(TAG, "Unspecified window will show input");
                    if (attribute != null) {
                        res = startInputUncheckedLocked(cs, inputContext, attribute,
                                startInputFlags, startInputReason, unverifiedTargetSdkVersion);
                        didStart = true;
                    }
                    showCurrentInputLocked(windowToken, InputMethodManager.SHOW_IMPLICIT, null,
                            SoftInputShowHideReason.SHOW_AUTO_EDITOR_FORWARD_NAV);
                }
                break;
            case LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                // Do nothing.
                break;
            case LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if ((softInputMode & LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    if (DEBUG) Slog.v(TAG, "Window asks to hide input going forward");
                    hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                            SoftInputShowHideReason.HIDE_STATE_HIDDEN_FORWARD_NAV);
                }
                break;
            case LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                if (!sameWindowFocused) {
                    if (DEBUG) Slog.v(TAG, "Window asks to hide input");
                    hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                            SoftInputShowHideReason.HIDE_ALWAYS_HIDDEN_STATE);
                }
                break;
            case LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                if ((softInputMode & LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    if (DEBUG) Slog.v(TAG, "Window asks to show input going forward");
                    if (isSoftInputModeStateVisibleAllowed(
                            unverifiedTargetSdkVersion, startInputFlags)) {
                        if (attribute != null) {
                            res = startInputUncheckedLocked(cs, inputContext, attribute,
                                    startInputFlags, startInputReason, unverifiedTargetSdkVersion);
                            didStart = true;
                        }
                        showCurrentInputLocked(windowToken, InputMethodManager.SHOW_IMPLICIT, null,
                                SoftInputShowHideReason.SHOW_STATE_VISIBLE_FORWARD_NAV);
                    } else {
                        Slog.e(TAG, "SOFT_INPUT_STATE_VISIBLE is ignored because"
                                + " there is no focused view that also returns true from"
                                + " View#onCheckIsTextEditor()");
                    }
                }
                break;
            case LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                if (DEBUG) Slog.v(TAG, "Window asks to always show input");
                if (isSoftInputModeStateVisibleAllowed(
                        unverifiedTargetSdkVersion, startInputFlags)) {
                    if (!sameWindowFocused) {
                        if (attribute != null) {
                            res = startInputUncheckedLocked(cs, inputContext, attribute,
                                    startInputFlags, startInputReason, unverifiedTargetSdkVersion);
                            didStart = true;
                        }
                        showCurrentInputLocked(windowToken, InputMethodManager.SHOW_IMPLICIT, null,
                                SoftInputShowHideReason.SHOW_STATE_ALWAYS_VISIBLE);
                    }
                } else {
                    Slog.e(TAG, "SOFT_INPUT_STATE_ALWAYS_VISIBLE is ignored because"
                            + " there is no focused view that also returns true from"
                            + " View#onCheckIsTextEditor()");
                }
                break;
        }

        if (!didStart) {
            if (attribute != null) {
                if (sameWindowFocused) {
                    // On previous platforms, when Dialogs re-gained focus, the Activity behind
                    // would briefly gain focus first, and dismiss the IME.
                    // On R that behavior has been fixed, but unfortunately apps have come
                    // to rely on this behavior to hide the IME when the editor no longer has focus
                    // To maintain compatibility, we are now hiding the IME when we don't have
                    // an editor upon refocusing a window.
                    if (startInputByWinGainedFocus) {
                        hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                                SoftInputShowHideReason.HIDE_SAME_WINDOW_FOCUSED_WITHOUT_EDITOR);
                    }
                }
                res = startInputUncheckedLocked(cs, inputContext, attribute, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion);
            } else {
                res = InputBindResult.NULL_EDITOR_INFO;
            }
        }
        return res;
    }

    @GuardedBy("ImfLock.class")
    private boolean canInteractWithImeLocked(
            int uid, IInputMethodClient client, String methodName) {
        if (mCurClient == null || client == null
                || mCurClient.client.asBinder() != client.asBinder()) {
            // We need to check if this is the current client with
            // focus in the window manager, to allow this call to
            // be made before input is started in it.
            final ClientState cs = mClients.get(client.asBinder());
            if (cs == null) {
                throw new IllegalArgumentException("unknown client " + client.asBinder());
            }
            if (!isImeClientFocused(mCurFocusedWindow, cs)) {
                Slog.w(TAG, String.format("Ignoring %s of uid %d : %s", methodName, uid, client));
                return false;
            }
        }
        return true;
    }

    private boolean shouldRestoreImeVisibility(IBinder windowToken,
            @SoftInputModeFlags int softInputMode) {
        switch (softInputMode & LayoutParams.SOFT_INPUT_MASK_STATE) {
            case LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                return false;
            case LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                if ((softInputMode & LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                    return false;
                }
        }
        return mWindowManagerInternal.shouldRestoreImeVisibility(windowToken);
    }

    @GuardedBy("ImfLock.class")
    private boolean canShowInputMethodPickerLocked(IInputMethodClient client) {
        // TODO(yukawa): multi-display support.
        final int uid = Binder.getCallingUid();
        if (mCurFocusedWindowClient != null && client != null
                && mCurFocusedWindowClient.client.asBinder() == client.asBinder()) {
            return true;
        } else if (getCurIntentLocked() != null && InputMethodUtils.checkIfPackageBelongsToUid(
                mAppOpsManager,
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
            if (!calledFromValidUserLocked()) {
                return;
            }
            if (!canShowInputMethodPickerLocked(client)) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid "
                        + Binder.getCallingUid() + ": " + client);
                return;
            }

            // Always call subtype picker, because subtype picker is a superset of input method
            // picker.
            final int displayId =
                    (mCurClient != null) ? mCurClient.selfReportedDisplayId : DEFAULT_DISPLAY;
            mHandler.obtainMessage(MSG_SHOW_IM_SUBTYPE_PICKER, auxiliarySubtypeMode, displayId)
                    .sendToTarget();
        }
    }

    @Override
    public void showInputMethodPickerFromSystem(IInputMethodClient client, int auxiliarySubtypeMode,
            int displayId) {
        if (mContext.checkCallingPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "showInputMethodPickerFromSystem requires WRITE_SECURE_SETTINGS "
                            + "permission");
        }
        // Always call subtype picker, because subtype picker is a superset of input method
        // picker.
        mHandler.obtainMessage(MSG_SHOW_IM_SUBTYPE_PICKER, auxiliarySubtypeMode, displayId)
                .sendToTarget();
    }

    /**
     * A test API for CTS to make sure that the input method menu is showing.
     */
    public boolean isInputMethodPickerShownForTest() {
        synchronized (ImfLock.class) {
            return mMenuController.isisInputMethodPickerShownForTestLocked();
        }
    }

    @BinderThread
    private void setInputMethod(@NonNull IBinder token, String id) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            setInputMethodWithSubtypeIdLocked(token, id, NOT_A_SUBTYPE_ID);
        }
    }

    @BinderThread
    private void setInputMethodAndSubtype(@NonNull IBinder token, String id,
            InputMethodSubtype subtype) {
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            if (subtype != null) {
                setInputMethodWithSubtypeIdLocked(token, id,
                        InputMethodUtils.getSubtypeIdFromHashCode(mMethodMap.get(id),
                                subtype.hashCode()));
            } else {
                setInputMethod(token, id);
            }
        }
    }

    @Override
    public void showInputMethodAndSubtypeEnablerFromClient(
            IInputMethodClient client, String inputMethodId) {
        synchronized (ImfLock.class) {
            // TODO(yukawa): Should we verify the display ID?
            if (!calledFromValidUserLocked()) {
                return;
            }
            showInputMethodAndSubtypeEnabler(inputMethodId);
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
                    subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                }
            }

            if (TextUtils.isEmpty(targetLastImiId)
                    && !InputMethodUtils.canAddToLastInputMethod(mCurrentSubtype)) {
                // This is a safety net. If the currentSubtype can't be added to the history
                // and the framework couldn't find the last ime, we will make the last ime be
                // the most applicable enabled keyboard subtype of the system imes.
                final List<InputMethodInfo> enabled = mSettings.getEnabledInputMethodListLocked();
                if (enabled != null) {
                    final int N = enabled.size();
                    final String locale = mCurrentSubtype == null
                            ? mRes.getConfiguration().locale.toString()
                            : mCurrentSubtype.getLocale();
                    for (int i = 0; i < N; ++i) {
                        final InputMethodInfo imi = enabled.get(i);
                        if (imi.getSubtypeCount() > 0 && imi.isSystem()) {
                            InputMethodSubtype keyboardSubtype =
                                    InputMethodUtils.findLastResortApplicableSubtypeLocked(mRes,
                                            InputMethodUtils.getSubtypes(imi),
                                            InputMethodUtils.SUBTYPE_MODE_KEYBOARD, locale, true);
                            if (keyboardSubtype != null) {
                                targetLastImiId = imi.getId();
                                subtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                                        imi, keyboardSubtype.hashCode());
                                if(keyboardSubtype.getLocale().equals(locale)) {
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
            final ImeSubtypeListItem nextSubtype = mSwitchingController.getNextInputMethodLocked(
                    onlyCurrentIme, mMethodMap.get(getSelectedMethodIdLocked()), mCurrentSubtype);
            if (nextSubtype == null) {
                return false;
            }
            setInputMethodWithSubtypeIdLocked(token, nextSubtype.mImi.getId(),
                    nextSubtype.mSubtypeId);
            return true;
        }
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
            if (nextSubtype == null) {
                return false;
            }
            return true;
        }
    }

    @Override
    public InputMethodSubtype getLastInputMethodSubtype() {
        synchronized (ImfLock.class) {
            if (!calledFromValidUserLocked()) {
                return null;
            }
            final Pair<String, String> lastIme = mSettings.getLastInputMethodAndSubtypeLocked();
            // TODO: Handle the case of the last IME with no subtypes
            if (lastIme == null || TextUtils.isEmpty(lastIme.first)
                    || TextUtils.isEmpty(lastIme.second)) return null;
            final InputMethodInfo lastImi = mMethodMap.get(lastIme.first);
            if (lastImi == null) return null;
            try {
                final int lastSubtypeHash = Integer.parseInt(lastIme.second);
                final int lastSubtypeId =
                        InputMethodUtils.getSubtypeIdFromHashCode(lastImi, lastSubtypeHash);
                if (lastSubtypeId < 0 || lastSubtypeId >= lastImi.getSubtypeCount()) {
                    return null;
                }
                return lastImi.getSubtypeAt(lastSubtypeId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    @Override
    public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
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
            if (!calledFromValidUserLocked()) {
                return;
            }
            if (!mSystemReady) {
                return;
            }
            final InputMethodInfo imi = mMethodMap.get(imiId);
            if (imi == null) return;
            final String[] packageInfos;
            try {
                packageInfos = mIPackageManager.getPackagesForUid(Binder.getCallingUid());
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to get package infos");
                return;
            }
            if (packageInfos != null) {
                final int packageNum = packageInfos.length;
                for (int i = 0; i < packageNum; ++i) {
                    if (packageInfos[i].equals(imi.getPackageName())) {
                        if (subtypes.length > 0) {
                            mAdditionalSubtypeMap.put(imi.getId(), toBeAdded);
                        } else {
                            mAdditionalSubtypeMap.remove(imi.getId());
                        }
                        AdditionalSubtypeUtils.save(mAdditionalSubtypeMap, mMethodMap,
                                mSettings.getCurrentUserId());
                        final long ident = Binder.clearCallingIdentity();
                        try {
                            buildInputMethodListLocked(false /* resetDefaultEnabledIme */);
                        } finally {
                            Binder.restoreCallingIdentity(ident);
                        }
                        return;
                    }
                }
            }
        }
    }

    /**
     * This is kept due to {@code @UnsupportedAppUsage} in
     * {@link InputMethodManager#getInputMethodWindowVisibleHeight()} and a dependency in
     * {@link InputMethodService#onCreate()}.
     *
     * <p>TODO(Bug 113914148): Check if we can remove this.</p>
     * @return {@link WindowManagerInternal#getInputMethodWindowVisibleHeight(int)}
     */
    @Override
    @Deprecated
    public int getInputMethodWindowVisibleHeight(@NonNull IInputMethodClient client) {
        int callingUid = Binder.getCallingUid();
        return Binder.withCleanCallingIdentity(() -> {
            final int curTokenDisplayId;
            synchronized (ImfLock.class) {
                if (!canInteractWithImeLocked(callingUid, client,
                        "getInputMethodWindowVisibleHeight")) {
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

    @Override
    public void removeImeSurface() {
        mContext.enforceCallingPermission(Manifest.permission.INTERNAL_SYSTEM_WINDOW, null);
        mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
    }

    @Override
    public void reportVirtualDisplayGeometryAsync(IInputMethodClient parentClient,
            int childDisplayId, float[] matrixValues) {
        try {
            final DisplayInfo displayInfo = mDisplayManagerInternal.getDisplayInfo(childDisplayId);
            if (displayInfo == null) {
                throw new IllegalArgumentException(
                        "Cannot find display for non-existent displayId: " + childDisplayId);
            }
            final int callingUid = Binder.getCallingUid();
            if (callingUid != displayInfo.ownerUid) {
                throw new SecurityException("The caller doesn't own the display.");
            }

            synchronized (ImfLock.class) {
                final ClientState cs = mClients.get(parentClient.asBinder());
                if (cs == null) {
                    return;
                }

                // null matrixValues means that the entry needs to be removed.
                if (matrixValues == null) {
                    final VirtualDisplayInfo info =
                            mVirtualDisplayIdToParentMap.get(childDisplayId);
                    if (info == null) {
                        return;
                    }
                    if (info.mParentClient != cs) {
                        throw new SecurityException("Only the owner client can clear"
                                + " VirtualDisplayGeometry for display #" + childDisplayId);
                    }
                    mVirtualDisplayIdToParentMap.remove(childDisplayId);
                    return;
                }

                VirtualDisplayInfo info = mVirtualDisplayIdToParentMap.get(childDisplayId);
                if (info != null && info.mParentClient != cs) {
                    throw new InvalidParameterException("Display #" + childDisplayId
                            + " is already registered by " + info.mParentClient);
                }
                if (info == null) {
                    if (!mWindowManagerInternal.isUidAllowedOnDisplay(childDisplayId, cs.uid)) {
                        throw new SecurityException(cs + " cannot access to display #"
                                + childDisplayId);
                    }
                    info = new VirtualDisplayInfo(cs, new Matrix());
                    mVirtualDisplayIdToParentMap.put(childDisplayId, info);
                }
                info.mMatrix.setValues(matrixValues);

                if (mCurClient == null || mCurClient.curSession == null) {
                    return;
                }

                Matrix matrix = null;
                int displayId = mCurClient.selfReportedDisplayId;
                boolean needToNotify = false;
                while (true) {
                    needToNotify |= (displayId == childDisplayId);
                    final VirtualDisplayInfo next = mVirtualDisplayIdToParentMap.get(displayId);
                    if (next == null) {
                        break;
                    }
                    if (matrix == null) {
                        matrix = new Matrix(next.mMatrix);
                    } else {
                        matrix.postConcat(next.mMatrix);
                    }
                    if (next.mParentClient.selfReportedDisplayId == mCurTokenDisplayId) {
                        if (needToNotify) {
                            final float[] values = new float[9];
                            matrix.getValues(values);
                            try {
                                mCurClient.client.updateVirtualDisplayToScreenMatrix(
                                        getSequenceNumberLocked(), values);
                            } catch (RemoteException e) {
                                Slog.e(TAG,
                                        "Exception calling updateVirtualDisplayToScreenMatrix()",
                                        e);

                            }
                        }
                        break;
                    }
                    displayId = info.mParentClient.selfReportedDisplayId;
                }
            }
        } catch (Throwable t) {
            if (parentClient != null) {
                try {
                    parentClient.throwExceptionFromSystem(t.toString());
                } catch (RemoteException e) {
                    Slog.e(TAG, "Exception calling throwExceptionFromSystem()", e);
                }
            }
        }
    }

    @Override
    public void removeImeSurfaceFromWindowAsync(IBinder windowToken) {
        // No permission check, because we'll only execute the request if the calling window is
        // also the current IME client.
        mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE_FROM_WINDOW, windowToken).sendToTarget();
    }

    /**
     * Starting point for dumping the IME tracing information in proto format.
     *
     * @param clientProtoDump dump information from the IME client side
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
    @Override
    public void startImeTrace() {
        ImeTracing.getInstance().startTrace(null /* printwriter */);
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                try {
                    state.client.setImeTraceEnabled(true /* enabled */);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error while trying to enable ime trace on client window", e);
                }
            }
        }
    }

    @BinderThread
    @Override
    public void stopImeTrace() {
        ImeTracing.getInstance().stopTrace(null /* printwriter */);
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                try {
                    state.client.setImeTraceEnabled(false /* enabled */);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error while trying to disable ime trace on client window", e);
                }
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
            if (mCurAttribute != null) {
                mCurAttribute.dumpDebug(proto, CUR_ATTRIBUTE);
            }
            proto.write(CUR_ID, getCurIdLocked());
            proto.write(SHOW_REQUESTED, mShowRequested);
            proto.write(SHOW_EXPLICITLY_REQUESTED, mShowExplicitlyRequested);
            proto.write(SHOW_FORCED, mShowForced);
            proto.write(INPUT_SHOWN, mInputShown);
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
            proto.write(ACCESSIBILITY_REQUESTING_NO_SOFT_KEYBOARD,
                    mAccessibilityRequestingNoSoftKeyboard);
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
            final InputMethodInfo imi = mMethodMap.get(getSelectedMethodIdLocked());
            if (imi != null) {
                mSwitchingController.onUserActionLocked(imi, mCurrentSubtype);
            }
        }
    }

    @BinderThread
    private void applyImeVisibility(IBinder token, IBinder windowToken, boolean setVisible) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.applyImeVisibility");
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            if (!setVisible) {
                if (mCurClient != null) {
                    // IMMS only knows of focused window, not the actual IME target.
                    // e.g. it isn't aware of any window that has both
                    // NOT_FOCUSABLE, ALT_FOCUSABLE_IM flags set and can the IME target.
                    // Send it to window manager to hide IME from IME target window.
                    // TODO(b/139861270): send to mCurClient.client once IMMS is aware of
                    // actual IME target.
                    mWindowManagerInternal.hideIme(
                            mHideRequestWindowMap.get(windowToken),
                            mCurClient.selfReportedDisplayId);
                }
            } else {
                // Send to window manager to show IME after IME layout finishes.
                mWindowManagerInternal.showImePostLayout(mShowRequestWindowMap.get(windowToken));
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @BinderThread
    private void resetStylusHandwriting(int requestId) {
        synchronized (ImfLock.class) {
            final OptionalInt curRequest = mHwController.getCurrentRequestId();
            if (!curRequest.isPresent() || curRequest.getAsInt() != requestId) {
                Slog.w(TAG, "IME requested to finish handwriting with a mismatched requestId: "
                        + requestId);
            }
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
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            setInputMethodLocked(id, subtypeId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /** Called right after {@link com.android.internal.view.IInputMethod#showSoftInput}. */
    @GuardedBy("ImfLock.class")
    private void onShowHideSoftInputRequested(boolean show, IBinder requestToken,
            @SoftInputShowHideReason int reason) {
        final WindowManagerInternal.ImeTargetInfo info =
                mWindowManagerInternal.onToggleImeRequested(
                        show, mCurFocusedWindow, requestToken, mCurTokenDisplayId);
        mSoftInputShowHideHistory.addEntry(new SoftInputShowHideHistory.Entry(
                mCurFocusedWindowClient, mCurAttribute, info.focusedWindowName,
                mCurFocusedWindowSoftInputMode, reason, mInFullscreenMode,
                info.requestWindowName, info.imeControlTargetName, info.imeLayerTargetName));
    }

    @BinderThread
    private void hideMySoftInput(@NonNull IBinder token, int flags) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideMySoftInput");
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                hideCurrentInputLocked(
                        mLastImeTargetWindow, flags, null,
                        SoftInputShowHideReason.HIDE_MY_SOFT_INPUT);

            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @BinderThread
    private void showMySoftInput(@NonNull IBinder token, int flags) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showMySoftInput");
        synchronized (ImfLock.class) {
            if (!calledWithValidTokenLocked(token)) {
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                showCurrentInputLocked(mLastImeTargetWindow, flags, null,
                        SoftInputShowHideReason.SHOW_MY_SOFT_INPUT);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionLocked(SessionState session) {
        if (mEnabledSession != session) {
            if (mEnabledSession != null && mEnabledSession.session != null) {
                if (DEBUG) Slog.v(TAG, "Disabling: " + mEnabledSession);
                mEnabledSession.method.setSessionEnabled(mEnabledSession.session, false);
            }
            mEnabledSession = session;
            if (mEnabledSession != null && mEnabledSession.session != null) {
                if (DEBUG) Slog.v(TAG, "Enabling: " + mEnabledSession);
                mEnabledSession.method.setSessionEnabled(mEnabledSession.session, true);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionForAccessibilityLocked(
            SparseArray<AccessibilitySessionState> accessibilitySessions) {
        // mEnabledAccessibilitySessions could the same object as accessibilitySessions.
        SparseArray<IInputMethodSession> disabledSessions = new SparseArray<>();
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
        SparseArray<IInputMethodSession> enabledSessions = new SparseArray<>();
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
        SomeArgs args;
        switch (msg.what) {
            case MSG_SHOW_IM_SUBTYPE_PICKER:
                final boolean showAuxSubtypes;
                final int displayId = msg.arg2;
                switch (msg.arg1) {
                    case InputMethodManager.SHOW_IM_PICKER_MODE_AUTO:
                        // This is undocumented so far, but IMM#showInputMethodPicker() has been
                        // implemented so that auxiliary subtypes will be excluded when the soft
                        // keyboard is invisible.
                        showAuxSubtypes = mInputShown;
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
                mMenuController.showInputMethodMenu(showAuxSubtypes, displayId);
                return true;

            // ---------------------------------------------------------

            case MSG_HIDE_CURRENT_INPUT_METHOD:
                synchronized (ImfLock.class) {
                    final @SoftInputShowHideReason int reason = (int) msg.obj;
                    hideCurrentInputLocked(mCurFocusedWindow, 0, null, reason);

                }
                return true;
            case MSG_REMOVE_IME_SURFACE: {
                synchronized (ImfLock.class) {
                    try {
                        if (mEnabledSession != null && mEnabledSession.session != null
                                && !mShowRequested) {
                            mEnabledSession.session.removeImeSurface();
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
                                && mEnabledSession != null && mEnabledSession.session != null) {
                            mEnabledSession.session.removeImeSurface();
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

            case MSG_UNBIND_CLIENT: {
                try {
                    // This unbinds all accessibility services too.
                    ((IInputMethodClient) msg.obj).onUnbindMethod(msg.arg1, msg.arg2);
                } catch (RemoteException e) {
                    // There is nothing interesting about the last client dying.
                    if (!(e instanceof DeadObjectException)) {
                        Slog.w(TAG, "RemoteException when unbinding input method service or"
                                + "accessibility services");
                    }
                }
                return true;
            }
            case MSG_UNBIND_ACCESSIBILITY_SERVICE: {
                args = (SomeArgs) msg.obj;
                IInputMethodClient client = (IInputMethodClient) args.arg1;
                int id = (int) args.arg2;
                try {
                    client.onUnbindAccessibilityService(msg.arg1, id);
                } catch (RemoteException e) {
                    // There is nothing interesting about the last client dying.
                    if (!(e instanceof DeadObjectException)) {
                        Slog.w(TAG, "RemoteException when unbinding accessibility services");
                    }
                }
                args.recycle();
                return true;
            }
            case MSG_BIND_CLIENT: {
                args = (SomeArgs)msg.obj;
                IInputMethodClient client = (IInputMethodClient)args.arg1;
                InputBindResult res = (InputBindResult)args.arg2;
                try {
                    client.onBindMethod(res);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Client died receiving input method " + args.arg2);
                } finally {
                    // Dispose the channel if the input method is not local to this process
                    // because the remote proxy will get its own copy when unparceled.
                    if (res.channel != null && Binder.isProxy(client)) {
                        res.channel.dispose();
                    }
                }
                args.recycle();
                return true;
            }
            case MSG_BIND_ACCESSIBILITY_SERVICE: {
                args = (SomeArgs) msg.obj;
                IInputMethodClient client = (IInputMethodClient) args.arg1;
                InputBindResult res = (InputBindResult) args.arg2;
                int id = (int) args.arg3;
                try {
                    client.onBindAccessibilityService(res, id);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Client died receiving input method " + args.arg2);
                } finally {
                    // Dispose the channel if the accessibility service is not local to this process
                    // because the remote proxy will get its own copy when unparceled.
                    if (res.channel != null && Binder.isProxy(client)) {
                        res.channel.dispose();
                    }
                }
                args.recycle();
                return true;
            }
            case MSG_SET_ACTIVE: {
                args = (SomeArgs) msg.obj;
                final ClientState clientState = (ClientState) args.arg1;
                try {
                    clientState.client.setActive(args.argi1 != 0 /* active */,
                            args.argi2 != 0 /* fullScreen */,
                            args.argi3 != 0 /* reportToImeController */);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Got RemoteException sending setActive(false) notification to pid "
                            + clientState.pid + " uid " + clientState.uid);
                }
                args.recycle();
                return true;
            }
            case MSG_SET_INTERACTIVE:
                handleSetInteractive(msg.arg1 != 0);
                return true;
            case MSG_REPORT_FULLSCREEN_MODE: {
                final boolean fullscreen = msg.arg1 != 0;
                final ClientState clientState = (ClientState)msg.obj;
                try {
                    clientState.client.reportFullscreenMode(fullscreen);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Got RemoteException sending "
                            + "reportFullscreen(" + fullscreen + ") notification to pid="
                            + clientState.pid + " uid=" + clientState.uid);
                }
                return true;
            }

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
                            && getCurMethodLocked() != null && mCurFocusedWindow != null) {
                        mHwController.initializeHandwritingSpy(
                                mCurTokenDisplayId, mCurFocusedWindow);
                    } else {
                        mHwController.reset();
                    }
                }
                return true;
            }
            case MSG_START_HANDWRITING:
                synchronized (ImfLock.class) {
                    IInputMethodInvoker curMethod = getCurMethodLocked();
                    if (curMethod == null) {
                        return true;
                    }
                    final HandwritingModeController.HandwritingSession session =
                            mHwController.startHandwritingSession(
                                    msg.arg1 /*requestId*/,
                                    msg.arg2 /*pid*/,
                                    mBindingController.getCurMethodUid());
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
            if (mCurClient != null && mCurClient.client != null) {
                scheduleSetActiveToClient(mCurClient, mIsInteractive, mInFullscreenMode,
                        mImePlatformCompatUtils.shouldFinishInputWithReportToIme(
                                getCurMethodUidLocked()));
            }
        }
    }

    private void scheduleSetActiveToClient(ClientState state, boolean active, boolean fullscreen,
            boolean reportToImeController) {
        executeOrSendMessage(state.client, obtainMessageIIIO(MSG_SET_ACTIVE,
                active ? 1 : 0, fullscreen ? 1 : 0, reportToImeController ? 1 : 0, state));
    }

    @GuardedBy("ImfLock.class")
    private boolean chooseNewDefaultIMELocked() {
        final InputMethodInfo imi = InputMethodUtils.getMostApplicableDefaultIME(
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
        final List<ResolveInfo> services = context.getPackageManager().queryIntentServicesAsUser(
                new Intent(InputMethod.SERVICE_INTERFACE), flags, userId);

        methodList.ensureCapacity(services.size());
        methodMap.ensureCapacity(services.size());

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
                final InputMethodInfo imi = new InputMethodInfo(context, ri,
                        additionalSubtypeMap.get(imeId));
                if (imi.isVrOnly()) {
                    continue;  // Skip VR-only IME, which isn't supported for now.
                }
                methodList.add(imi);
                methodMap.put(imi.getId(), imi);
                if (DEBUG) {
                    Slog.d(TAG, "Found an input method " + imi);
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
            final int N = allInputMethodServices.size();
            for (int i = 0; i < N; ++i) {
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
            final int N = enabledImes.size();
            for (int i = 0; i < N; ++i) {
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
                    InputMethodUtils.getDefaultEnabledImes(mContext, mMethodList,
                            reenableMinimumNonAuxSystemImes);
            final int N = defaultEnabledIme.size();
            for (int i = 0; i < N; ++i) {
                final InputMethodInfo imi =  defaultEnabledIme.get(i);
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

        // Here is not the perfect place to reset the switching controller. Ideally
        // mSwitchingController and mSettings should be able to share the same state.
        // TODO: Make sure that mSwitchingController and mSettings are sharing the
        // the same enabled IMEs list.
        mSwitchingController.resetCircularListLocked(mContext);

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
        final InputMethodInfo newSystemVoiceIme = InputMethodUtils.chooseSystemVoiceIme(
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

    private void showInputMethodAndSubtypeEnabler(String inputMethodId) {
        Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SUBTYPE_SETTINGS);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (!TextUtils.isEmpty(inputMethodId)) {
            intent.putExtra(Settings.EXTRA_INPUT_METHOD_ID, inputMethodId);
        }
        final int userId;
        synchronized (ImfLock.class) {
            userId = mSettings.getCurrentUserId();
        }
        mContext.startActivityAsUser(intent, null, UserHandle.of(userId));
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
        List<Pair<String, ArrayList<String>>> enabledInputMethodsList = mSettings
                .getEnabledInputMethodsAndSubtypeListLocked();

        if (enabled) {
            for (Pair<String, ArrayList<String>> pair: enabledInputMethodsList) {
                if (pair.first.equals(id)) {
                    // We are enabling this input method, but it is already enabled.
                    // Nothing to do. The previous state was enabled.
                    return true;
                }
            }
            mSettings.appendAndPutEnabledInputMethodLocked(id, false);
            // Previous state was disabled.
            return false;
        } else {
            StringBuilder builder = new StringBuilder();
            if (mSettings.buildAndPutEnabledInputMethodsStrRemovingIdLocked(
                    builder, enabledInputMethodsList, id)) {
                // Disabled input method is currently selected, switch to another one.
                final String selId = mSettings.getSelectedInputMethod();
                if (id.equals(selId) && !chooseNewDefaultIMELocked()) {
                    Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                    resetSelectedInputMethodAndSubtypeLocked("");
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
                    lastSubtypeId = InputMethodUtils.getSubtypeIdFromHashCode(
                            imi, Integer.parseInt(subtypeHashCode));
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
                }
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeId, false);
    }

    /**
     * Gets the current subtype of this input method.
     */
    @Override
    public InputMethodSubtype getCurrentInputMethodSubtype() {
        synchronized (ImfLock.class) {
            // TODO: Make this work even for non-current users?
            if (!calledFromValidUserLocked()) {
                return null;
            }
            return getCurrentInputMethodSubtypeLocked();
        }
    }

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
                || !InputMethodUtils.isValidSubtypeId(imi, mCurrentSubtype.hashCode())) {
            int subtypeId = mSettings.getSelectedInputMethodSubtypeId(selectedMethodId);
            if (subtypeId == NOT_A_SUBTYPE_ID) {
                // If there are no selected subtypes, the framework will try to find
                // the most applicable subtype from explicitly or implicitly enabled
                // subtypes.
                List<InputMethodSubtype> explicitlyOrImplicitlyEnabledSubtypes =
                        mSettings.getEnabledInputMethodSubtypeListLocked(mContext, imi, true);
                // If there is only one explicitly or implicitly enabled subtype,
                // just returns it.
                if (explicitlyOrImplicitlyEnabledSubtypes.size() == 1) {
                    mCurrentSubtype = explicitlyOrImplicitlyEnabledSubtypes.get(0);
                } else if (explicitlyOrImplicitlyEnabledSubtypes.size() > 1) {
                    mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                            mRes, explicitlyOrImplicitlyEnabledSubtypes,
                            InputMethodUtils.SUBTYPE_MODE_KEYBOARD, null, true);
                    if (mCurrentSubtype == null) {
                        mCurrentSubtype = InputMethodUtils.findLastResortApplicableSubtypeLocked(
                                mRes, explicitlyOrImplicitlyEnabledSubtypes, null, null,
                                true);
                    }
                }
            } else {
                mCurrentSubtype = InputMethodUtils.getSubtypes(imi).get(subtypeId);
            }
        }
        return mCurrentSubtype;
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
        final InputMethodSettings settings = new InputMethodSettings(
                mContext.getResources(), mContext.getContentResolver(), methodMap,
                userId, false);
        if (!methodMap.containsKey(imeId)
                || !settings.getEnabledInputMethodListLocked()
                        .contains(methodMap.get(imeId))) {
            return false; // IME is not found or not enabled.
        }
        settings.putSelectedInputMethod(imeId);
        settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
        return true;
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
        public void hideCurrentInputMethod(@SoftInputShowHideReason int reason) {
            mHandler.removeMessages(MSG_HIDE_CURRENT_INPUT_METHOD);
            mHandler.obtainMessage(MSG_HIDE_CURRENT_INPUT_METHOD, reason).sendToTarget();
        }

        @Override
        public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return getInputMethodListLocked(userId, DirectBootAwareness.AUTO);
            }
        }

        @Override
        public List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return getEnabledInputMethodListLocked(userId);
            }
        }

        @Override
        public void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
                InlineSuggestionsRequestInfo requestInfo, IInlineSuggestionsRequestCallback cb) {
            // Get the device global touch exploration state before lock to avoid deadlock.
            boolean touchExplorationEnabled = mAccessibilityManager.isTouchExplorationEnabled();

            synchronized (ImfLock.class) {
                onCreateInlineSuggestionsRequestLocked(userId, requestInfo, cb,
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
                final InputMethodSettings settings = new InputMethodSettings(
                        mContext.getResources(), mContext.getContentResolver(), methodMap,
                        userId, false);
                if (!methodMap.containsKey(imeId)) {
                    return false; // IME is not found.
                }
                if (enabled) {
                    if (!settings.getEnabledInputMethodListLocked().contains(
                            methodMap.get(imeId))) {
                        settings.appendAndPutEnabledInputMethodLocked(imeId, false);
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
        public void reportImeControl(@Nullable IBinder windowToken, boolean imeParentChanged) {
            synchronized (ImfLock.class) {
                if (mCurFocusedWindow != windowToken) {
                    // mCurPerceptible was set by the focused window, but it is no longer in
                    // control, so we reset mCurPerceptible.
                    mCurPerceptible = true;
                }
                if (imeParentChanged) {
                    // Hide the IME method menu earlier when the IME surface parent will change in
                    // case seeing the dialog dismiss flickering during the next focused window
                    // starting the input connection.
                    mMenuController.hideInputMethodMenu();
                }
            }
        }

        @Override
        public void removeImeSurface() {
            mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
        }

        @Override
        public void updateImeWindowStatus(boolean disableImeIcon) {
            mHandler.obtainMessage(MSG_UPDATE_IME_WINDOW_STATUS, disableImeIcon ? 1 : 0, 0)
                    .sendToTarget();
        }

        @Override
        public void onSessionForAccessibilityCreated(int accessibilityConnectionId,
                IInputMethodSession session) {
            synchronized (ImfLock.class) {
                if (mCurClient != null) {
                    clearClientSessionForAccessibilityLocked(mCurClient, accessibilityConnectionId);
                    mCurClient.mAccessibilitySessions.put(accessibilityConnectionId,
                            new AccessibilitySessionState(mCurClient, accessibilityConnectionId,
                                    session));
                    InputBindResult res = attachNewAccessibilityLocked(
                            StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY, true,
                            accessibilityConnectionId);
                    executeOrSendMessage(mCurClient.client, obtainMessageOOO(
                            MSG_BIND_ACCESSIBILITY_SERVICE, mCurClient.client, res,
                            accessibilityConnectionId));
                }
            }
        }

        @Override
        public void unbindAccessibilityFromCurrentClient(int accessibilityConnectionId) {
            synchronized (ImfLock.class) {
                if (mCurClient != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "unbindAccessibilityFromCurrentClientLocked: client="
                                + mCurClient.client.asBinder());
                    }
                    // A11yManagerService unbinds the disabled accessibility service. We don't need
                    // to do it here.
                    @UnbindReason int unbindClientReason =
                            UnbindReason.ACCESSIBILITY_SERVICE_DISABLED;
                    executeOrSendMessage(mCurClient.client, obtainMessageIIOO(
                            MSG_UNBIND_ACCESSIBILITY_SERVICE, getSequenceNumberLocked(),
                            unbindClientReason, mCurClient.client, accessibilityConnectionId));
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
            if (!TextUtils.equals(mCurAttribute.packageName, packageName)) {
                Slog.e(TAG, "Ignoring createInputContentUriToken mCurAttribute.packageName="
                    + mCurAttribute.packageName + " packageName=" + packageName);
                return null;
            }
            // This user ID can never bee spoofed.
            final int imeUserId = UserHandle.getUserId(uid);
            // This user ID can never bee spoofed.
            final int appUserId = UserHandle.getUserId(mCurClient.uid);
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
            if (mCurClient != null && mCurClient.client != null) {
                mInFullscreenMode = fullscreen;
                executeOrSendMessage(mCurClient.client, mHandler.obtainMessage(
                        MSG_REPORT_FULLSCREEN_MODE, fullscreen ? 1 : 0, 0 /* unused */,
                        mCurClient));
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
            int N = mMethodList.size();
            p.println("  Input Methods: mMethodMapUpdateCount=" + mMethodMapUpdateCount);
            for (int i=0; i<N; i++) {
                InputMethodInfo info = mMethodList.get(i);
                p.println("  InputMethod #" + i + ":");
                info.dump(p, "    ");
            }
            p.println("  Clients:");
            final int numClients = mClients.size();
            for (int i = 0; i < numClients; ++i) {
                final ClientState ci = mClients.valueAt(i);
                p.println("  Client " + ci + ":");
                p.println("    client=" + ci.client);
                p.println("    inputContext=" + ci.inputContext);
                p.println("    sessionRequested=" + ci.sessionRequested);
                p.println("    sessionRequestedForAccessibility="
                        + ci.mSessionRequestedForAccessibility);
                p.println("    curSession=" + ci.curSession);
            }
            p.println("  mCurMethodId=" + getSelectedMethodIdLocked());
            client = mCurClient;
            p.println("  mCurClient=" + client + " mCurSeq=" + getSequenceNumberLocked());
            p.println("  mCurPerceptible=" + mCurPerceptible);
            p.println("  mCurFocusedWindow=" + mCurFocusedWindow
                    + " softInputMode=" +
                    InputMethodDebug.softInputModeToString(mCurFocusedWindowSoftInputMode)
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
            p.println("  mShowRequested=" + mShowRequested
                    + " mShowExplicitlyRequested=" + mShowExplicitlyRequested
                    + " mShowForced=" + mShowForced
                    + " mInputShown=" + mInputShown);
            p.println("  mInFullscreenMode=" + mInFullscreenMode);
            p.println("  mSystemReady=" + mSystemReady + " mInteractive=" + mIsInteractive);
            p.println("  mSettingsObserver=" + mSettingsObserver);
            p.println("  mImeHiddenByDisplayPolicy=" + mImeHiddenByDisplayPolicy);
            p.println("  mSwitchingController:");
            mSwitchingController.dump(p);
            p.println("  mSettings:");
            mSettings.dumpLocked(p, "    ");

            p.println("  mStartInputHistory:");
            mStartInputHistory.dump(pw, "   ");

            p.println("  mSoftInputShowHideHistory:");
            mSoftInputShowHideHistory.dump(pw, "   ");
        }

        // Exit here for critical dump, as remaining sections require IPCs to other processes.
        if (isCritical) {
            return;
        }

        p.println(" ");
        if (client != null) {
            pw.flush();
            try {
                TransferPipe.dumpAsync(client.client.asBinder(), fd, args);
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
                TransferPipe.dumpAsync(focusedWindowClient.client.asBinder(), fd, args);
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

        @RequiresPermission(allOf = {
                Manifest.permission.DUMP,
                Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                Manifest.permission.WRITE_SECURE_SETTINGS,
        })
        @BinderThread
        @ShellCommandResult
        @Override
        public int onCommand(@Nullable String cmd) {
            // For shell command, require all the permissions here in favor of code simplicity.
            Arrays.asList(
                    Manifest.permission.DUMP,
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    Manifest.permission.WRITE_SECURE_SETTINGS
            ).forEach(permission -> mService.mContext.enforceCallingPermission(permission, null));

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
            final PrintWriter pr = shellCommand.getOutPrintWriter();
            final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                    mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
            for (int userId : userIds) {
                final List<InputMethodInfo> methods = all
                        ? getInputMethodListLocked(userId, DirectBootAwareness.AUTO)
                        : getEnabledInputMethodListLocked(userId);
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
        return ShellCommandResult.SUCCESS;
    }

    /**
     * Handles {@code adb shell ime enable} and {@code adb shell ime disable}.
     * @param shellCommand {@link ShellCommand} object that is handling this command.
     * @param enabled {@code true} if the command was {@code adb shell ime enable}.
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandEnableDisableInputMethod(
            @NonNull ShellCommand shellCommand, boolean enabled) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        final String imeId = shellCommand.getNextArgRequired();
        final PrintWriter out = shellCommand.getOutPrintWriter();
        final PrintWriter error = shellCommand.getErrPrintWriter();
        boolean hasFailed = false;
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
            final InputMethodSettings settings = new InputMethodSettings(mContext.getResources(),
                    mContext.getContentResolver(), methodMap, userId, false);
            if (enabled) {
                if (!methodMap.containsKey(imeId)) {
                    failedToEnableUnknownIme = true;
                } else {
                    for (InputMethodInfo imi : settings.getEnabledInputMethodListLocked()) {
                        if (TextUtils.equals(imi.getId(), imeId)) {
                            previouslyEnabled = true;
                            break;
                        }
                    }
                    if (!previouslyEnabled) {
                        settings.appendAndPutEnabledInputMethodLocked(imeId, false);
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
     * @param shellCommand {@link ShellCommand} object that is handling this command.
     * @return Exit code of the command.
     */
    @BinderThread
    @ShellCommandResult
    private int handleShellCommandSetInputMethod(@NonNull ShellCommand shellCommand) {
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        final String imeId = shellCommand.getNextArgRequired();
        final PrintWriter out = shellCommand.getOutPrintWriter();
        final PrintWriter error = shellCommand.getErrPrintWriter();
        boolean hasFailed = false;
        synchronized (ImfLock.class) {
            final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                    mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
            for (int userId : userIds) {
                if (!userHasDebugPriv(userId, shellCommand)) {
                    continue;
                }
                boolean failedToSelectUnknownIme = !switchToInputMethodLocked(imeId, userId);
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
        final PrintWriter out = shellCommand.getOutPrintWriter();
        final int userIdToBeResolved = handleOptionsForCommandsThatOnlyHaveUserOption(shellCommand);
        synchronized (ImfLock.class) {
            final int[] userIds = InputMethodUtils.resolveUserId(userIdToBeResolved,
                    mSettings.getCurrentUserId(), shellCommand.getErrPrintWriter());
            for (int userId : userIds) {
                if (!userHasDebugPriv(userId, shellCommand)) {
                    continue;
                }
                final String nextIme;
                final List<InputMethodInfo> nextEnabledImes;
                if (userId == mSettings.getCurrentUserId()) {
                    hideCurrentInputLocked(mCurFocusedWindow, 0, null,
                            SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND);
                    mBindingController.unbindCurrentMethod();
                    // Reset the current IME
                    resetSelectedInputMethodAndSubtypeLocked(null);
                    // Also reset the settings of the current IME
                    mSettings.putSelectedInputMethod(null);
                    // Disable all enabled IMEs.
                    for (InputMethodInfo inputMethodInfo :
                            mSettings.getEnabledInputMethodListLocked()) {
                        setInputMethodEnabledLocked(inputMethodInfo.getId(), false);
                    }
                    // Re-enable with default enabled IMEs.
                    for (InputMethodInfo imi :
                            InputMethodUtils.getDefaultEnabledImes(mContext, mMethodList)) {
                        setInputMethodEnabledLocked(imi.getId(), true);
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
                            mContext.getResources(), mContext.getContentResolver(), methodMap,
                            userId, false);

                    nextEnabledImes = InputMethodUtils.getDefaultEnabledImes(mContext, methodList);
                    nextIme = InputMethodUtils.getMostApplicableDefaultIME(nextEnabledImes).getId();

                    // Reset enabled IMEs.
                    settings.putEnabledInputMethodsStr("");
                    nextEnabledImes.forEach(imi -> settings.appendAndPutEnabledInputMethodLocked(
                            imi.getId(), false));

                    // Reset selected IME.
                    settings.putSelectedInputMethod(nextIme);
                    settings.putSelectedSubtype(NOT_A_SUBTYPE_ID);
                }
                out.println("Reset current and enabled IMEs for user #" + userId);
                out.println("  Selected: " + nextIme);
                nextEnabledImes.forEach(ime -> out.println("   Enabled: " + ime.getId()));
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
        final PrintWriter pw = shellCommand.getOutPrintWriter();
        switch (cmd) {
            case "start":
                ImeTracing.getInstance().startTrace(pw);
                break;  // proceed to the next step to update the IME client processes.
            case "stop":
                ImeTracing.getInstance().stopTrace(pw);
                break;  // proceed to the next step to update the IME client processes.
            case "save-for-bugreport":
                ImeTracing.getInstance().saveForBugreport(pw);
                return ShellCommandResult.SUCCESS;  // no need to update the IME client processes.
            default:
                pw.println("Unknown command: " + cmd);
                pw.println("Input method trace options:");
                pw.println("  start: Start tracing");
                pw.println("  stop: Stop tracing");
                return ShellCommandResult.FAILURE;  // no need to update the IME client processes.
        }
        boolean isImeTraceEnabled = ImeTracing.getInstance().isEnabled();
        ArrayMap<IBinder, ClientState> clients;
        synchronized (ImfLock.class) {
            clients = new ArrayMap<>(mClients);
        }
        for (ClientState state : clients.values()) {
            if (state != null) {
                try {
                    state.client.setImeTraceEnabled(isImeTraceEnabled);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error while trying to enable/disable ime trace on client window",
                            e);
                }
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
    private boolean userHasDebugPriv(int userId, final ShellCommand shellCommand) {
        if (mUserManager.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES, UserHandle.of(userId))) {
            shellCommand.getErrPrintWriter().println("User #" + userId
                    + " is restricted with DISALLOW_DEBUGGING_FEATURES.");
            return false;
        }
        return true;
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
        public void hideMySoftInput(int flags, AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked")
            final AndroidFuture<Void> typedFuture = future;
            try {
                mImms.hideMySoftInput(mToken, flags);
                typedFuture.complete(null);
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void showMySoftInput(int flags, AndroidFuture future /* T=Void */) {
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
        public void applyImeVisibilityAsync(IBinder windowToken, boolean setVisible) {
            mImms.applyImeVisibility(mToken, windowToken, setVisible);
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
    }
}
