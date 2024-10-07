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
import static android.server.inputmethod.InputMethodManagerServiceProto.BACK_DISPOSITION;
import static android.server.inputmethod.InputMethodManagerServiceProto.BOUND_TO_METHOD;
import static android.server.inputmethod.InputMethodManagerServiceProto.CONCURRENT_MULTI_USER_MODE_ENABLED;
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
import static com.android.server.inputmethod.ImeVisibilityStateComputer.STATE_SHOW_IME;
import static com.android.server.inputmethod.InputMethodBindingController.TIME_TO_RECONNECT;
import static com.android.server.inputmethod.InputMethodSettings.INVALID_SUBTYPE_HASHCODE;
import static com.android.server.inputmethod.InputMethodSubtypeSwitchingController.MODE_AUTO;
import static com.android.server.inputmethod.InputMethodUtils.NOT_A_SUBTYPE_INDEX;
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
import android.annotation.WorkerThread;
import android.app.ActivityManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.hardware.input.InputManager;
import android.inputmethodservice.InputMethodService;
import android.inputmethodservice.InputMethodService.BackDispositionMode;
import android.inputmethodservice.InputMethodService.ImeWindowVisibility;
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
import com.android.internal.inputmethod.InputMethodInfoSafeList;
import com.android.internal.inputmethod.InputMethodNavButtonFlags;
import com.android.internal.inputmethod.InputMethodSubtypeHandle;
import com.android.internal.inputmethod.SoftInputShowHideReason;
import com.android.internal.inputmethod.StartInputFlags;
import com.android.internal.inputmethod.StartInputReason;
import com.android.internal.inputmethod.UnbindReason;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.AccessibilityManagerInternal;
import com.android.server.EventLogTags;
import com.android.server.LocalServices;
import com.android.server.ServiceThread;
import com.android.server.SystemService;
import com.android.server.companion.virtual.VirtualDeviceManagerInternal;
import com.android.server.input.InputManagerInternal;
import com.android.server.inputmethod.InputMethodManagerInternal.InputMethodListListener;
import com.android.server.inputmethod.InputMethodMenuControllerNew.MenuItem;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

    /**
     * Timeout in milliseconds in {@link #systemRunning()} to make sure that users are initialized
     * in {@link Lifecycle#initializeUsersAsync(int[])}.
     */
    @DurationMillisLong
    private static final long SYSTEM_READY_USER_INIT_TIMEOUT = 3000;

    @Retention(SOURCE)
    @IntDef({ShellCommandResult.SUCCESS, ShellCommandResult.FAILURE})
    private @interface ShellCommandResult {
        int SUCCESS = 0;
        int FAILURE = -1;
    }

    /**
     * Indicates that the annotated field is shared by all the users.
     *
     * <p>See b/305849394 for details.</p>
     */
    @Retention(SOURCE)
    @Target({ElementType.FIELD})
    private @interface SharedByAllUsersField {
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

    private static final int MSG_HIDE_INPUT_METHOD = 1035;
    private static final int MSG_REMOVE_IME_SURFACE = 1060;
    private static final int MSG_REMOVE_IME_SURFACE_FROM_WINDOW = 1061;

    private static final int MSG_RESET_HANDWRITING = 1090;
    private static final int MSG_START_HANDWRITING = 1100;
    private static final int MSG_FINISH_HANDWRITING = 1110;
    private static final int MSG_REMOVE_HANDWRITING_WINDOW = 1120;

    private static final int MSG_PREPARE_HANDWRITING_DELEGATION = 1130;

    private static final int MSG_SET_INTERACTIVE = 3030;

    private static final int MSG_HARD_KEYBOARD_SWITCH_CHANGED = 4000;

    private static final int MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED = 5010;

    private static final int MSG_NOTIFY_IME_UID_TO_AUDIO_SERVICE = 7000;

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
    @SharedByAllUsersField
    private final boolean mPreventImeStartupUnlessTextEditor;

    /**
     * These IMEs are known not to behave well when evicted from memory and thus are exempt
     * from the IME startup avoidance behavior that is enabled by
     * {@link #mPreventImeStartupUnlessTextEditor}.
     */
    @SharedByAllUsersField
    @NonNull
    private final String[] mNonPreemptibleInputMethods;

    /**
     * See {@link #shouldEnableConcurrentMultiUserMode(Context)} about when set to be {@code true}.
     */
    @SharedByAllUsersField
    private final boolean mConcurrentMultiUserModeEnabled;

    /**
     * Returns {@code true} if the concurrent multi-user mode is enabled.
     *
     * <p>Currently not compatible with profiles (e.g. work profile).</p>
     *
     * @param context {@link Context} to be used to query
     *                {@link PackageManager#FEATURE_AUTOMOTIVE}
     * @return {@code true} if the concurrent multi-user mode is enabled.
     */
    static boolean shouldEnableConcurrentMultiUserMode(@NonNull Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                && UserManager.isVisibleBackgroundUsersEnabled()
                && context.getResources().getBoolean(android.R.bool.config_perDisplayFocusEnabled)
                && Flags.concurrentInputMethods();
    }

    /**
     * Figures out the target IME user ID for a given {@link Binder} IPC.
     *
     * @param callingProcessUserId the user ID of the calling process
     * @return the user ID to be used for this {@link Binder} call
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    @BinderThread
    private int resolveImeUserIdLocked(@UserIdInt int callingProcessUserId) {
        return mConcurrentMultiUserModeEnabled ? callingProcessUserId : mCurrentImeUserId;
    }

    /**
     * Figures out the targetIMuser for a given {@link Binder} IPC. In case
     * {@code callingProcessUserId} is SYSTEM user, then it will return the owner of the display
     * associated with the {@code client} passed as parameter.
     *
     * @param callingProcessUserId the user ID of the calling process
     * @param client               the input method client used to retrieve the user id in case
     *                             {@code callingProcessUserId} is assigned to SYSTEM user
     * @return the user ID to be used for this {@link Binder} call
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    @BinderThread
    private int resolveImeUserIdLocked(@UserIdInt int callingProcessUserId,
            @NonNull IInputMethodClient client) {
        if (mConcurrentMultiUserModeEnabled) {
            if (callingProcessUserId == UserHandle.USER_SYSTEM) {
                final var clientState = mClientController.getClient(client.asBinder());
                return mUserManagerInternal.getUserAssignedToDisplay(
                        clientState.mSelfReportedDisplayId);
            }
            return callingProcessUserId;
        }
        return mCurrentImeUserId;
    }

   /**
     * Figures out the target IME user ID associated with the given {@code displayId}.
     *
     * @param displayId the display ID to be queried about
     * @return User ID to be used for this {@code displayId}.
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    private int resolveImeUserIdFromDisplayIdLocked(int displayId) {
        return mConcurrentMultiUserModeEnabled
                ? mUserManagerInternal.getUserAssignedToDisplay(displayId) : mCurrentImeUserId;
    }

    /**
     * Figures out the target IME user ID associated with the given {@code windowToken}.
     *
     * @param windowToken the Window token to be queried about
     * @return User ID to be used for this {@code displayId}.
     */
    @GuardedBy("ImfLock.class")
    @UserIdInt
    private int resolveImeUserIdFromWindowLocked(@NonNull IBinder windowToken) {
        if (mConcurrentMultiUserModeEnabled) {
            final int displayId = mWindowManagerInternal.getDisplayIdForWindow(windowToken);
            return mUserManagerInternal.getUserAssignedToDisplay(displayId);
        }
        return mCurrentImeUserId;
    }

    final Context mContext;
    final Resources mRes;
    private final Handler mHandler;

    private final InputMethodManagerInternal mInputMethodManagerInternal;
    @NonNull
    private final Handler mIoHandler;

    /**
     * The user ID whose IME should be used if {@link #mConcurrentMultiUserModeEnabled} is
     * {@code false}, otherwise remains to be the initial value, which is obtained by
     * {@link ActivityManagerInternal#getCurrentUserId()} while the device is booting up.
     *
     * <p>Never get confused with {@link ActivityManagerInternal#getCurrentUserId()}, which is
     * in general useless when designing and implementing interactions between apps and IMEs.</p>
     *
     * <p>You can also not assume that the IME client process belongs to {@link #mCurrentImeUserId}.
     * A most important outlier is System UI process, which always runs under
     * {@link UserHandle#USER_SYSTEM} in all the known configurations including Headless System User
     * Mode (HSUM).</p>
     */
    @MultiUserUnawareField
    @UserIdInt
    @GuardedBy("ImfLock.class")
    private int mCurrentImeUserId;

    /** Holds all user related data */
    @SharedByAllUsersField
    private final UserDataRepository mUserDataRepository;

    final WindowManagerInternal mWindowManagerInternal;
    private final ActivityManagerInternal mActivityManagerInternal;
    final PackageManagerInternal mPackageManagerInternal;
    final InputManagerInternal mInputManagerInternal;
    final ImePlatformCompatUtils mImePlatformCompatUtils;
    @SharedByAllUsersField
    final InputMethodDeviceConfigs mInputMethodDeviceConfigs;

    private final UserManagerInternal mUserManagerInternal;
    @MultiUserUnawareField
    private final InputMethodMenuController mMenuController;
    private final InputMethodMenuControllerNew mMenuControllerNew;

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
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
    @SharedByAllUsersField
    private final SparseArray<String> mVirtualDeviceMethodMap = new SparseArray<>();

    @Nullable
    private StatusBarManagerInternal mStatusBarManagerInternal;
    @SharedByAllUsersField
    private boolean mShowOngoingImeSwitcherForPhones;
    @GuardedBy("ImfLock.class")
    @MultiUserUnawareField
    private final HandwritingModeController mHwController;
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private IntArray mStylusIds;

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

        @UserIdInt
        final int mUserId;

        @Override
        public String toString() {
            return "SessionState{uid=" + mClient.mUid + " pid=" + mClient.mPid
                    + " method=" + Integer.toHexString(
                    IInputMethodInvoker.getBinderIdentityHashCode(mMethod))
                    + " session=" + Integer.toHexString(
                    System.identityHashCode(mSession))
                    + " channel=" + mChannel
                    + " userId=" + mUserId
                    + "}";
        }

        SessionState(ClientState client, IInputMethodInvoker method,
                IInputMethodSession session, InputChannel channel, @UserIdInt int userId) {
            mClient = client;
            mMethod = method;
            mSession = session;
            mChannel = channel;
            mUserId = userId;
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
    @SharedByAllUsersField
    private final ClientController mClientController;

    /**
     * Set once the system is ready to run third party code.
     */
    @SharedByAllUsersField
    boolean mSystemReady;

    @AnyThread
    @NonNull
    UserData getUserData(@UserIdInt int userId) {
        return mUserDataRepository.getOrCreate(userId);
    }

    @AnyThread
    @NonNull
    InputMethodBindingController getInputMethodBindingController(@UserIdInt int userId) {
        return getUserData(userId).mBindingController;
    }

    /**
     * Map of window perceptible states indexed by their associated window tokens.
     *
     * The value {@code true} indicates that IME has not been mostly hidden via
     * {@link android.view.InsetsController} for the given window.
     */
    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private final WeakHashMap<IBinder, Boolean> mFocusedWindowPerceptible = new WeakHashMap<>();

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
        return getInputMethodBindingController(mCurrentImeUserId).getCurMethod();
    }

    /**
     * True if the device is currently interactive with user.  The value is true initially.
     */
    @MultiUserUnawareField
    boolean mIsInteractive = true;

    @SharedByAllUsersField
    private final MyPackageMonitor mMyPackageMonitor = new MyPackageMonitor();

    @SharedByAllUsersField
    private final String mSlotIme;

    /**
     * Registered {@link InputMethodListListener}.
     * This variable can be accessed from both of MainThread and BinderThread.
     */
    @SharedByAllUsersField
    private final CopyOnWriteArrayList<InputMethodListListener> mInputMethodListListeners =
            new CopyOnWriteArrayList<>();

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    private final WeakHashMap<IBinder, IBinder> mImeTargetWindowMap = new WeakHashMap<>();

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @NonNull
    private final StartInputHistory mStartInputHistory = new StartInputHistory();

    @GuardedBy("ImfLock.class")
    @SharedByAllUsersField
    @NonNull
    private final SoftInputShowHideHistory mSoftInputShowHideHistory =
            new SoftInputShowHideHistory();

    @SharedByAllUsersField
    @NonNull
    private final ImeTrackerService mImeTrackerService;

    @GuardedBy("ImfLock.class")
    private void onSecureSettingsChangedLocked(@NonNull String key, @UserIdInt int userId) {
        switch (key) {
            case Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD: {
                if (!Flags.imeSwitcherRevamp()) {
                    if (userId == mCurrentImeUserId) {
                        mMenuController.updateKeyboardFromSettingsLocked(userId);
                    }
                }
                break;
            }
            case Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE: {
                final int accessibilitySoftKeyboardSetting = Settings.Secure.getIntForUser(
                        mContext.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE, 0, userId);
                final var userData = getUserData(userId);
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                visibilityStateComputer.getImePolicy().setA11yRequestNoSoftKeyboard(
                        accessibilitySoftKeyboardSetting);
                if (visibilityStateComputer.getImePolicy().isA11yRequestNoSoftKeyboard()) {
                    hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                            0 /* flags */, SoftInputShowHideReason.HIDE_SETTINGS_ON_CHANGE, userId);
                } else if (isShowRequestedForCurrentWindow(userId)) {
                    showCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                            InputMethodManager.SHOW_IMPLICIT,
                            SoftInputShowHideReason.SHOW_SETTINGS_ON_CHANGE, userId);
                }
                break;
            }
            case Settings.Secure.STYLUS_HANDWRITING_ENABLED: {
                InputMethodManager.invalidateLocalStylusHandwritingAvailabilityCaches();
                InputMethodManager
                        .invalidateLocalConnectionlessStylusHandwritingAvailabilityCaches();
                break;
            }
            case Settings.Secure.DEFAULT_INPUT_METHOD:
            case Settings.Secure.ENABLED_INPUT_METHODS:
            case Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE: {
                boolean enabledChanged = false;
                String newEnabled = InputMethodSettingsRepository.get(userId)
                        .getEnabledInputMethodsStr();
                final var userData = getUserData(userId);
                if (!userData.mLastEnabledInputMethodsStr.equals(newEnabled)) {
                    userData.mLastEnabledInputMethodsStr = newEnabled;
                    enabledChanged = true;
                }
                updateInputMethodsFromSettingsLocked(enabledChanged, userId);
                break;
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
                synchronized (ImfLock.class) {
                    if (senderUserId != UserHandle.USER_ALL && senderUserId != mCurrentImeUserId) {
                        // A background user is trying to hide the dialog. Ignore.
                        return;
                    }
                    final int userId = mCurrentImeUserId;
                    if (Flags.imeSwitcherRevamp()) {
                        final var bindingController = getInputMethodBindingController(userId);
                        mMenuControllerNew.hide(bindingController.getCurTokenDisplayId(), userId);
                    } else {
                        mMenuController.hideInputMethodMenuLocked(userId);
                    }
                }
            } else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }
        }
    }

    /**
     * Handles {@link Intent#ACTION_LOCALE_CHANGED}.
     *
     * <p>Note: For historical reasons, {@link Intent#ACTION_LOCALE_CHANGED} has been sent to all
     * the users.</p>
     */
    @WorkerThread
    void onActionLocaleChanged(@NonNull LocaleList prevLocales, @NonNull LocaleList newLocales) {
        if (DEBUG) {
            Slog.d(TAG, "onActionLocaleChanged prev=" + prevLocales + " new=" + newLocales);
        }
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }
            for (int userId : mUserManagerInternal.getUserIds()) {
                // Does InputMethodInfo really have data dependency on system locale?
                // TODO(b/356679261): Check if we really need to update RawInputMethodInfo here.
                {
                    final var userData = getUserData(userId);
                    final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
                    final var rawMethodMap = queryRawInputMethodServiceMap(mContext, userId);
                    userData.mRawInputMethodMap.set(rawMethodMap);
                    final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                            DirectBootAwareness.AUTO,
                            userData.mIsUnlockingOrUnlocked.get());
                    final var settings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, settings);
                }
                postInputMethodSettingUpdatedLocked(true /* resetDefaultEnabledIme */, userId);
                // If the locale is changed, needs to reset the default ime
                resetDefaultImeLocked(mContext, userId);
                updateFromSettingsLocked(true, userId);
            }
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

        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (ImfLock.class) {
                final int userId = getChangingUserId();
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
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
                                    resetSelectedInputMethodAndSubtypeLocked("", userId);
                                    chooseNewDefaultIMELocked(userId);
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
            final var userData = getUserData(userId);

            userData.mRawInputMethodMap.set(queryRawInputMethodServiceMap(mContext, userId));

            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

            InputMethodInfo curIm = null;
            String curInputMethodId = settings.getSelectedInputMethod();
            final List<InputMethodInfo> methodList = settings.getMethodList();

            final ArrayList<String> imesToClearAdditionalSubtypes = new ArrayList<>();
            final ArrayList<String> imesToBeDisabled = new ArrayList<>();
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
                    imesToBeDisabled.add(imi.getId());
                } else if (change == PACKAGE_UPDATING) {
                    Slog.i(TAG, "Input method reinstalling, clearing additional subtypes: "
                            + imi.getComponent());
                    imesToClearAdditionalSubtypes.add(imiId);
                }
            }

            // Clear additional subtypes as a batch operation.
            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final AdditionalSubtypeMap newAdditionalSubtypeMap =
                    additionalSubtypeMap.cloneWithRemoveOrSelf(imesToClearAdditionalSubtypes);
            final boolean additionalSubtypeChanged =
                    (newAdditionalSubtypeMap != additionalSubtypeMap);
            if (additionalSubtypeChanged) {
                AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                        settings.getMethodMap());
            }

            final var newMethodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                    newAdditionalSubtypeMap,
                    DirectBootAwareness.AUTO,
                    userData.mIsUnlockingOrUnlocked.get());

            final boolean noUpdate = InputMethodMap.areSame(settings.getMethodMap(), newMethodMap);
            if (noUpdate && imesToBeDisabled.isEmpty()) {
                return;
            }

            // Here we start remaining tasks that need to be done with the lock (b/340221861).
            synchronized (ImfLock.class) {
                final int numImesToBeDisabled = imesToBeDisabled.size();
                for (int i = 0; i < numImesToBeDisabled; ++i) {
                    setInputMethodEnabledLocked(imesToBeDisabled.get(i), false /* enabled */,
                            userId);
                }
                if (noUpdate) {
                    return;
                }
                InputMethodSettingsRepository.put(userId,
                        InputMethodSettings.create(newMethodMap, userId));
                postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */, userId);

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
                            final var bindingController = getInputMethodBindingController(userId);
                            updateSystemUiLocked(0 /* vis */,
                                    bindingController.getBackDisposition(), userId);
                            if (!chooseNewDefaultIMELocked(userId)) {
                                changed = true;
                                curIm = null;
                                Slog.i(TAG, "Unsetting current input method");
                                resetSelectedInputMethodAndSubtypeLocked("", userId);
                            }
                        }
                    }
                }

                if (curIm == null) {
                    // We currently don't have a default input method... is
                    // one now available?
                    changed = chooseNewDefaultIMELocked(userId);
                } else if (!changed && isPackageModified(curIm.getPackageName())) {
                    // Even if the current input method is still available, current subtype could
                    // be obsolete when the package is modified in practice.
                    changed = true;
                }

                if (changed) {
                    updateFromSettingsLocked(false, userId);
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
    public static final class Lifecycle extends SystemService
            implements UserManagerInternal.UserLifecycleListener {
        private final InputMethodManagerService mService;

        public Lifecycle(Context context) {
            this(context, createServiceForProduction(context));

            // For production code, hook up user lifecycle
            mService.mUserManagerInternal.addUserLifecycleListener(this);

            // Hook up resource change first before initializeUsersAsync() starts reading the
            // seemingly initial data so that we can eliminate the race condition.
            InputMethodDrawsNavBarResourceMonitor.registerCallback(context, mService.mIoHandler,
                    mService::onUpdateResourceOverlay);

            // Also schedule user init tasks onto an I/O thread.
            initializeUsersAsync(mService.mUserManagerInternal.getUserIds());
        }

        @VisibleForTesting
        Lifecycle(Context context, @NonNull InputMethodManagerService inputMethodManagerService) {
            super(context);
            mService = inputMethodManagerService;
        }

        /**
         * Does initialization then instantiate {@link InputMethodManagerService} for production
         * configurations.
         *
         * <p>We have this abstraction just because several unit tests directly initialize
         * {@link InputMethodManagerService} with some mocked/emulated dependencies.</p>
         *
         * @param context {@link Context} to be used to set up
         * @return {@link InputMethodManagerService} object to be used
         */
        @NonNull
        private static InputMethodManagerService createServiceForProduction(
                @NonNull Context context) {
            final ServiceThread thread = new ServiceThread(HANDLER_THREAD_NAME,
                    Process.THREAD_PRIORITY_FOREGROUND, false /* allowIo */);
            thread.start();

            final ServiceThread ioThread = new ServiceThread(PACKAGE_MONITOR_THREAD_NAME,
                    Process.THREAD_PRIORITY_FOREGROUND, true /* allowIo */);
            ioThread.start();

            SecureSettingsWrapper.setContentResolver(context.getContentResolver());

            return new InputMethodManagerService(context,
                    shouldEnableConcurrentMultiUserMode(context), thread.getLooper(),
                    Handler.createAsync(ioThread.getLooper()),
                    null /* bindingControllerForTesting */);
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
            if (Flags.refactorInsetsController()) {
                mService.registerImeRequestedChangedListener();
            }
        }

        @Override
        public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
            // Called on ActivityManager thread.
            synchronized (ImfLock.class) {
                if (mService.mConcurrentMultiUserModeEnabled) {
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
        public void onUserCreated(UserInfo user, @Nullable Object token) {
            // Called directly from UserManagerService. Do not block the calling thread.
            final int userId = user.id;
            AdditionalSubtypeMapRepository.onUserCreated(userId);
            initializeUsersAsync(new int[]{ userId });
        }

        @Override
        public void onUserRemoved(UserInfo user) {
            // Called directly from UserManagerService. Do not block the calling thread.
            final int userId = user.id;
            SecureSettingsWrapper.onUserRemoved(userId);
            AdditionalSubtypeMapRepository.remove(userId);
            InputMethodSettingsRepository.remove(userId);
            mService.mUserDataRepository.remove(userId);
        }

        @Override
        public void onUserUnlocking(@NonNull TargetUser user) {
            // Called on ActivityManager thread. Do not block the calling thread.
            final int userId = user.getUserIdentifier();
            final var userData = mService.getUserData(userId);
            final boolean userUnlocked = true;
            userData.mIsUnlockingOrUnlocked.set(userUnlocked);
            SecureSettingsWrapper.onUserUnlocking(userId);
            final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                    AdditionalSubtypeMapRepository.get(userId), DirectBootAwareness.AUTO,
                    userUnlocked);
            final var newSettings = InputMethodSettings.create(methodMap, userId);
            InputMethodSettingsRepository.put(userId, newSettings);
            mService.mIoHandler.post(() -> {
                synchronized (ImfLock.class) {
                    if (!mService.mSystemReady) {
                        return;
                    }
                    // We need to rebuild IMEs.
                    mService.postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */,
                            userId);
                    mService.updateInputMethodsFromSettingsLocked(true /* enabledChanged */,
                            userId);
                }
            });
        }

        @Override
        public void onUserStarting(TargetUser user) {
            // Called on ActivityManager thread.
            final int userId = user.getUserIdentifier();
            SecureSettingsWrapper.onUserStarting(userId);
            mService.mIoHandler.post(() -> {
                synchronized (ImfLock.class) {
                    if (mService.mSystemReady) {
                        mService.onUserReadyLocked(userId);
                    }
                }
            });
        }

        @AnyThread
        private void initializeUsersAsync(@UserIdInt int[] userIds) {
            Slog.d(TAG, "Schedule initialization for users=" + Arrays.toString(userIds));
            mService.mIoHandler.post(() -> {
                final var service = mService;
                final var context = service.mContext;
                final var userManagerInternal = service.mUserManagerInternal;

                for (int userId : userIds) {
                    Slog.d(TAG, "Start initialization for user=" + userId);
                    final var userData = mService.getUserData(userId);

                    AdditionalSubtypeMapRepository.initializeIfNecessary(userId);
                    final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
                    final var rawMethodMap = queryRawInputMethodServiceMap(context, userId);
                    userData.mRawInputMethodMap.set(rawMethodMap);

                    final boolean unlocked = userManagerInternal.isUserUnlockingOrUnlocked(userId);
                    userData.mIsUnlockingOrUnlocked.set(unlocked);
                    final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                            DirectBootAwareness.AUTO, unlocked);

                    final var settings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, settings);

                    final int profileParentId = userManagerInternal.getProfileParentId(userId);
                    final boolean value =
                            InputMethodDrawsNavBarResourceMonitor.evaluate(context,
                                    profileParentId);
                    userData.mImeDrawsNavBar.set(value);

                    userData.mBackgroundLoadLatch.countDown();
                    Slog.d(TAG, "Complete initialization for user=" + userId);
                }
            });
        }

        @Override
        public void onUserStopped(@NonNull TargetUser user) {
            final int userId = user.getUserIdentifier();
            // Called on ActivityManager thread.

            // Following operations should be trivial and fast enough, so do not dispatch them to
            // the IO thread.
            SecureSettingsWrapper.onUserStopped(userId);
            final var userData = mService.getUserData(userId);
            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final var rawMethodMap = userData.mRawInputMethodMap.get();
            final boolean userUnlocked = false;  // Stopping a user also locks their storage.
            userData.mIsUnlockingOrUnlocked.set(userUnlocked);
            final var methodMap = rawMethodMap.toInputMethodMap(additionalSubtypeMap,
                    DirectBootAwareness.AUTO, userUnlocked);
            InputMethodSettingsRepository.put(userId,
                    InputMethodSettings.create(methodMap, userId));
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
            mIoHandler.removeCallbacks(mUserSwitchHandlerTask);
        }
        // Hide soft input before user switch task since switch task may block main handler a while
        // and delayed the hideCurrentInputLocked().
        final var userData = getUserData(userId);
        hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow, 0 /* flags */,
                SoftInputShowHideReason.HIDE_SWITCH_USER, userId);
        final UserSwitchHandlerTask task = new UserSwitchHandlerTask(this, userId,
                clientToBeReset);
        mUserSwitchHandlerTask = task;
        mIoHandler.post(task);
    }

    @VisibleForTesting
    InputMethodManagerService(
            Context context,
            boolean concurrentMultiUserModeEnabled,
            @NonNull Looper uiLooper,
            @NonNull Handler ioHandler,
            @Nullable IntFunction<InputMethodBindingController> bindingControllerForTesting) {
        synchronized (ImfLock.class) {
            mConcurrentMultiUserModeEnabled = concurrentMultiUserModeEnabled;
            mContext = context;
            mRes = context.getResources();

            mHandler = Handler.createAsync(uiLooper, this);
            mIoHandler = ioHandler;
            SystemLocaleWrapper.onStart(context, this::onActionLocaleChanged, mIoHandler);
            mImeTrackerService = new ImeTrackerService(mHandler);
            mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);
            mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
            mInputManagerInternal = LocalServices.getService(InputManagerInternal.class);
            mImePlatformCompatUtils = new ImePlatformCompatUtils();
            mInputMethodDeviceConfigs = new InputMethodDeviceConfigs();
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);

            mSlotIme = mContext.getString(com.android.internal.R.string.status_bar_ime);

            mShowOngoingImeSwitcherForPhones = false;

            mCurrentImeUserId = mActivityManagerInternal.getCurrentUserId();
            final IntFunction<InputMethodBindingController>
                    bindingControllerFactory = userId -> new InputMethodBindingController(userId,
                    InputMethodManagerService.this);
            final IntFunction<ImeVisibilityStateComputer> visibilityStateComputerFactory =
                    userId -> new ImeVisibilityStateComputer(InputMethodManagerService.this,
                            userId);
            mUserDataRepository = new UserDataRepository(
                    bindingControllerForTesting != null ? bindingControllerForTesting
                            : bindingControllerFactory, visibilityStateComputerFactory);

            mMenuController = new InputMethodMenuController(this);
            mMenuControllerNew = Flags.imeSwitcherRevamp()
                    ? new InputMethodMenuControllerNew() : null;
            mVisibilityApplier = new DefaultImeVisibilityApplier(this);

            mClientController = new ClientController(mPackageManagerInternal);
            mClientController.addClientControllerCallback(c -> onClientRemoved(c));

            mPreventImeStartupUnlessTextEditor = mRes.getBoolean(
                    com.android.internal.R.bool.config_preventImeStartupUnlessTextEditor);
            mNonPreemptibleInputMethods = mRes.getStringArray(
                    com.android.internal.R.array.config_nonPreemptibleInputMethods);
            Runnable discardDelegationTextRunnable = () -> discardHandwritingDelegationText();
            mHwController = new HandwritingModeController(mContext, uiLooper,
                    new InkWindowInitializer(), discardDelegationTextRunnable);
            registerDeviceListenerAndCheckStylusSupport();
            mInputMethodManagerInternal = new LocalServiceImpl();
        }
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
    private void onUpdateEditorToolTypeLocked(@MotionEvent.ToolType int toolType,
            @UserIdInt int userId) {
        final var curMethod = getInputMethodBindingController(userId).getCurMethod();
        if (curMethod != null) {
            curMethod.updateEditorToolType(toolType);
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
    private void resetDefaultImeLocked(Context context, @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        // Do not reset the default (current) IME when it is a 3rd-party IME
        String selectedMethodId = bindingController.getSelectedMethodId();
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
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
        setSelectedInputMethodAndSubtypeLocked(defIm, NOT_A_SUBTYPE_INDEX, false, userId);
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
        final int prevUserId = mCurrentImeUserId;
        if (DEBUG) {
            Slog.d(TAG, "Switching user stage 1/3. newUserId=" + newUserId
                    + " prevUserId=" + prevUserId);
        }

        // Clean up stuff for mCurrentUserId, which soon becomes the previous user.

        // TODO(b/338461930): Check if this is still necessary or not.
        onUnbindCurrentMethodByReset(prevUserId);

        // Note that in b/197848765 we want to see if we can keep the binding alive for better
        // profile switching.
        final var bindingController = getInputMethodBindingController(prevUserId);
        bindingController.unbindCurrentMethod();

        unbindCurrentClientLocked(UnbindReason.SWITCH_USER, prevUserId);

        // Hereafter we start initializing things for "newUserId".

        final var newUserData = getUserData(newUserId);

        // TODO(b/342027196): Double check if we need to always reset upon user switching.
        newUserData.mLastEnabledInputMethodsStr = "";

        mCurrentImeUserId = newUserId;
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
        postInputMethodSettingUpdatedLocked(initialUserSwitch /* resetDefaultEnabledIme */,
                newUserId);
        if (TextUtils.isEmpty(newSettings.getSelectedInputMethod())) {
            // This is the first time of the user switch and
            // set the current ime to the proper one.
            resetDefaultImeLocked(mContext, newUserId);
        }
        updateFromSettingsLocked(true, newUserId);

        // Special workaround for b/356879517.
        // KeyboardLayoutManager still expects onInputMethodSubtypeChangedForKeyboardLayoutMapping
        // to be called back upon IME user switching, while we are actively deprecating the concept
        // of "current IME user" at b/350386877.
        // TODO(b/356879517): Come up with a way to avoid this special handling.
        if (newUserData.mSubtypeForKeyboardLayoutMapping != null) {
            final var subtypeHandleAndSubtype = newUserData.mSubtypeForKeyboardLayoutMapping;
            mInputManagerInternal.onInputMethodSubtypeChangedForKeyboardLayoutMapping(
                    newUserId, subtypeHandleAndSubtype.first, subtypeHandleAndSubtype.second);
        }

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
            cs.mClient.scheduleStartInputIfNecessary(newUserData.mInFullscreenMode);
        }
    }

    private void waitForUserInitialization() {
        final int[] userIds = mUserManagerInternal.getUserIds();
        final long deadlineNanos = SystemClock.elapsedRealtimeNanos()
                + TimeUnit.MILLISECONDS.toNanos(SYSTEM_READY_USER_INIT_TIMEOUT);
        boolean interrupted = false;
        try {
            for (int userId : userIds) {
                final var latch = getUserData(userId).mBackgroundLoadLatch;
                boolean awaitResult;
                while (true) {
                    try {
                        final long remainingNanos =
                                Math.max(deadlineNanos - SystemClock.elapsedRealtimeNanos(), 0);
                        awaitResult = latch.await(remainingNanos, TimeUnit.NANOSECONDS);
                        break;
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (!awaitResult) {
                    Slog.w(TAG, "Timed out for user#" + userId + " to be initialized");
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * TODO(b/32343335): The entire systemRunning() method needs to be revisited.
     */
    public void systemRunning() {
        waitForUserInitialization();

        synchronized (ImfLock.class) {
            if (DEBUG) {
                Slog.d(TAG, "--- systemReady");
            }
            if (!mSystemReady) {
                mSystemReady = true;
                final int currentImeUserId = mCurrentImeUserId;
                mStatusBarManagerInternal =
                        LocalServices.getService(StatusBarManagerInternal.class);
                hideStatusBarIconLocked(currentImeUserId);
                final var bindingController = getInputMethodBindingController(currentImeUserId);
                updateSystemUiLocked(bindingController.getImeWindowVis(),
                        bindingController.getBackDisposition(), currentImeUserId);
                mShowOngoingImeSwitcherForPhones = mRes.getBoolean(
                        com.android.internal.R.bool.show_ongoing_ime_switcher);
                if (mShowOngoingImeSwitcherForPhones) {
                    mWindowManagerInternal.setOnHardKeyboardStatusChangeListener(available -> {
                        mHandler.obtainMessage(MSG_HARD_KEYBOARD_SWITCH_CHANGED,
                                available ? 1 : 0, 0 /* unused */).sendToTarget();
                    });
                }

                mMyPackageMonitor.register(mContext, UserHandle.ALL, mIoHandler);
                SecureSettingsChangeCallback.register(mHandler, mContext.getContentResolver(),
                        new String[] {
                                Settings.Secure.ACCESSIBILITY_SOFT_KEYBOARD_MODE,
                                Settings.Secure.DEFAULT_INPUT_METHOD,
                                Settings.Secure.ENABLED_INPUT_METHODS,
                                Settings.Secure.SELECTED_INPUT_METHOD_SUBTYPE,
                                Settings.Secure.SHOW_IME_WITH_HARD_KEYBOARD,
                                Settings.Secure.STYLUS_HANDWRITING_ENABLED,
                        }, (key, flags, userId) -> {
                            synchronized (ImfLock.class) {
                                onSecureSettingsChangedLocked(key, userId);
                            }
                        });

                final IntentFilter broadcastFilterForAllUsers = new IntentFilter();
                broadcastFilterForAllUsers.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                mContext.registerReceiverAsUser(new ImmsBroadcastReceiverForAllUsers(),
                        UserHandle.ALL, broadcastFilterForAllUsers, null, null,
                        Context.RECEIVER_EXPORTED);

                AdditionalSubtypeMapRepository.startWriterThread();

                for (int userId : mUserManagerInternal.getUserIds()) {
                    onUserReadyLocked(userId);
                }
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void onUserReadyLocked(@UserIdInt int userId) {
        if (!mUserManagerInternal.isUserRunning(userId)) {
            return;
        }

        final String defaultImiId = SecureSettingsWrapper.getString(
                Settings.Secure.DEFAULT_INPUT_METHOD, null, userId);
        final boolean imeSelectedOnBoot = !TextUtils.isEmpty(defaultImiId);
        final var settings = InputMethodSettingsRepository.get(userId);
        postInputMethodSettingUpdatedLocked(!imeSelectedOnBoot /* resetDefaultEnabledIme */,
                userId);
        updateFromSettingsLocked(true, userId);
        InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                getPackageManagerForUser(mContext, userId), settings.getEnabledInputMethodList());
    }

    void registerImeRequestedChangedListener() {
        mWindowManagerInternal.setOnImeRequestedChangedListener(
                (windowToken, imeVisible, statsToken) -> {
                    if (Flags.refactorInsetsController()) {
                        if (imeVisible) {
                            showCurrentInputInternal(windowToken, statsToken);
                        } else {
                            hideCurrentInputInternal(windowToken, statsToken);
                        }
                    }
                });
    }

    @BinderThread
    @Nullable
    @Override
    public InputMethodInfo getCurrentInputMethodInfoAsUser(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        return settings.getMethodMap().get(settings.getSelectedInputMethod());
    }

    @BinderThread
    @NonNull
    @Override
    public InputMethodInfoSafeList getInputMethodList(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return InputMethodInfoSafeList.empty();
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return InputMethodInfoSafeList.create(getInputMethodListInternal(
                    userId, directBootAwareness, callingUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @BinderThread
    @NonNull
    @Override
    public InputMethodInfoSafeList getEnabledInputMethodList(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return InputMethodInfoSafeList.empty();
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return InputMethodInfoSafeList.create(
                    getEnabledInputMethodListInternal(userId, callingUid));
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @BinderThread
    @NonNull
    @Override
    public List<InputMethodInfo> getInputMethodListLegacy(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return Collections.emptyList();
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return getInputMethodListInternal(userId, directBootAwareness, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @BinderThread
    @NonNull
    @Override
    public List<InputMethodInfo> getEnabledInputMethodListLegacy(@UserIdInt int userId) {
        if (UserHandle.getCallingUserId() != userId) {
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL, null);
        }
        if (!mUserManagerInternal.exists(userId)) {
            return Collections.emptyList();
        }
        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return getEnabledInputMethodListInternal(userId, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
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
            if (userId == mCurrentImeUserId) {
                final var bindingController = getInputMethodBindingController(userId);
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
                Settings.Secure.STYLUS_HANDWRITING_ENABLED,
                Settings.Secure.STYLUS_HANDWRITING_DEFAULT_VALUE, profileParentUserId) == 0) {
            return false;
        }
        return true;
    }

    private List<InputMethodInfo> getInputMethodListInternal(@UserIdInt int userId,
            @DirectBootAwareness int directBootAwareness, int callingUid) {
        final var userData = getUserData(userId);
        final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                AdditionalSubtypeMapRepository.get(userId), directBootAwareness,
                userData.mIsUnlockingOrUnlocked.get());
        final var settings = InputMethodSettings.create(methodMap, userId);
        // Create a copy.
        final ArrayList<InputMethodInfo> methodList = new ArrayList<>(settings.getMethodList());
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
    }

    private List<InputMethodInfo> getEnabledInputMethodListInternal(@UserIdInt int userId,
            int callingUid) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final ArrayList<InputMethodInfo> methodList = settings.getEnabledInputMethodList();
        // filter caller's access to input methods
        methodList.removeIf(imi ->
                !canCallerAccessInputMethod(imi.getPackageName(), callingUid, userId, settings));
        return methodList;
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

        final int callingUid = Binder.getCallingUid();
        final long ident = Binder.clearCallingIdentity();
        try {
            return getEnabledInputMethodSubtypeListInternal(imiId,
                    allowsImplicitlyEnabledSubtypes, userId, callingUid);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    private List<InputMethodSubtype> getEnabledInputMethodSubtypeListInternal(String imiId,
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

    @GuardedBy("ImfLock.class")
    private void onClientRemoved(ClientState client) {
        clearClientSessionLocked(client);
        clearClientSessionForAccessibilityLocked(client);
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<UserData> clientRemovedForUser =
                userData -> onClientRemovedInternalLocked(client, userData);
        mUserDataRepository.forAllUserData(clientRemovedForUser);
    }

    /**
     * Hide the IME if the removed user is the current user.
     */
    // TODO(b/325515685): Move this method to InputMethodBindingController
    @GuardedBy("ImfLock.class")
    private void onClientRemovedInternalLocked(ClientState client, @NonNull UserData userData) {
        final int userId = userData.mUserId;
        if (userData.mCurClient == client) {
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_REMOVE_CLIENT, userId);
            if (userData.mBoundToMethod) {
                userData.mBoundToMethod = false;
                final var userBindingController = userData.mBindingController;
                IInputMethodInvoker curMethod = userBindingController.getCurMethod();
                if (curMethod != null) {
                    // When we unbind input, we are unbinding the client, so we always
                    // unbind ime and a11y together.
                    curMethod.unbindInput();
                    AccessibilityManagerInternal.get().unbindInput();
                }
            }
            userData.mBoundToAccessibility = false;
            userData.mCurClient = null;
            if (userData.mImeBindingState.mFocusedWindowClient == client) {
                userData.mImeBindingState = ImeBindingState.newEmptyState();
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
    void unbindCurrentClientLocked(@UnbindReason int unbindClientReason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient != null) {
            if (DEBUG) {
                Slog.v(TAG, "unbindCurrentInputLocked: client="
                        + userData.mCurClient.mClient.asBinder());
            }
            final var bindingController = userData.mBindingController;
            if (userData.mBoundToMethod) {
                userData.mBoundToMethod = false;
                IInputMethodInvoker curMethod = bindingController.getCurMethod();
                if (curMethod != null) {
                    curMethod.unbindInput();
                }
            }
            userData.mBoundToAccessibility = false;

            // Since we set active false to current client and set mCurClient to null, let's unbind
            // all accessibility too. That means, when input method get disconnected (including
            // switching ime), we also unbind accessibility
            userData.mCurClient.mClient.setActive(false /* active */, false /* fullscreen */);

            userData.mCurClient.mClient.onUnbindMethod(bindingController.getSequenceNumber(),
                    unbindClientReason);
            userData.mCurClient.mSessionRequested = false;
            userData.mCurClient.mSessionRequestedForAccessibility = false;
            userData.mCurClient = null;
            ImeTracker.forLogging().onFailed(userData.mCurStatsToken,
                    ImeTracker.PHASE_SERVER_WAIT_IME);
            userData.mCurStatsToken = null;
            // TODO: Make mMenuController multi-user aware
            if (Flags.imeSwitcherRevamp()) {
                mMenuControllerNew.hide(bindingController.getCurTokenDisplayId(), userId);
            } else {
                mMenuController.hideInputMethodMenuLocked(userId);
            }
        }
    }

    /**
     * TODO(b/338404383) Remove
     * Called when {@link #resetCurrentMethodAndClientLocked(int, int)} invoked for clean-up states
     * before unbinding the current method.
     */
    @GuardedBy("ImfLock.class")
    void onUnbindCurrentMethodByReset(@UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        final ImeTargetWindowState winState = visibilityStateComputer.getWindowStateOrNull(
                userData.mImeBindingState.mFocusedWindow);
        if (winState != null && !winState.isRequestedImeVisible()
                && !visibilityStateComputer.isInputShown()) {
            // Normally, the focus window will apply the IME visibility state to
            // WindowManager when the IME has applied it. But it would be too late when
            // switching IMEs in between different users. (Since the focused IME will
            // first unbind the service to switch to bind the next user of the IME
            // service, that wouldn't make the attached IME token validity check in time)
            // As a result, we have to notify WM to apply IME visibility before clearing the
            // binding states in the first place.
            final var statsToken = createStatsTokenForFocusedClient(false /* show */,
                    SoftInputShowHideReason.UNBIND_CURRENT_METHOD, userId);
            mVisibilityApplier.applyImeVisibility(userData.mImeBindingState.mFocusedWindow,
                    statsToken, STATE_HIDE_IME, SoftInputShowHideReason.NOT_SET /* ignore reason */,
                    userId);
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean isShowRequestedForCurrentWindow(@UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        final ImeTargetWindowState state = visibilityStateComputer.getWindowStateOrNull(
                userData.mImeBindingState.mFocusedWindow);
        return state != null && state.isRequestedImeVisible();
    }

    @GuardedBy("ImfLock.class")
    @NonNull
    InputBindResult attachNewInputLocked(@StartInputReason int startInputReason, boolean initial,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        if (!userData.mBoundToMethod) {
            bindingController.getCurMethod().bindInput(userData.mCurClient.mBinding);
            userData.mBoundToMethod = true;
        }

        final boolean restarting = !initial;
        final Binder startInputToken = new Binder();
        final StartInputInfo info = new StartInputInfo(userId,
                bindingController.getCurToken(), bindingController.getCurTokenDisplayId(),
                bindingController.getCurId(), startInputReason,
                restarting, UserHandle.getUserId(userData.mCurClient.mUid),
                userData.mCurClient.mSelfReportedDisplayId,
                userData.mImeBindingState.mFocusedWindow, userData.mCurEditorInfo,
                userData.mImeBindingState.mFocusedWindowSoftInputMode,
                bindingController.getSequenceNumber());
        mImeTargetWindowMap.put(startInputToken, userData.mImeBindingState.mFocusedWindow);
        mStartInputHistory.addEntry(info);

        // Seems that PackageManagerInternal#grantImplicitAccess() doesn't handle cross-user
        // implicit visibility (e.g. IME[user=10] -> App[user=0]) thus we do this only for the
        // same-user scenarios.
        // That said ignoring cross-user scenario will never affect IMEs that do not have
        // INTERACT_ACROSS_USERS(_FULL) permissions, which is actually almost always the case.
        if (userId == UserHandle.getUserId(userData.mCurClient.mUid)) {
            mPackageManagerInternal.grantImplicitAccess(userId, null /* intent */,
                    UserHandle.getAppId(bindingController.getCurMethodUid()),
                    userData.mCurClient.mUid, true /* direct */);
        }

        @InputMethodNavButtonFlags final int navButtonFlags =
                getInputMethodNavButtonFlagsLocked(userData);
        final SessionState session = userData.mCurClient.mCurSession;
        setEnabledSessionLocked(session, userData);
        session.mMethod.startInput(startInputToken, userData.mCurInputConnection,
                userData.mCurEditorInfo, restarting, navButtonFlags, userData.mCurImeDispatcher);
        if (Flags.refactorInsetsController()) {
            if (isShowRequestedForCurrentWindow(userId) && userData.mImeBindingState != null
                    && userData.mImeBindingState.mFocusedWindow != null) {
                // Re-use current statsToken, if it exists.
                final var statsToken = userData.mCurStatsToken != null ? userData.mCurStatsToken
                        : createStatsTokenForFocusedClient(true /* show */,
                                SoftInputShowHideReason.ATTACH_NEW_INPUT, userId);
                userData.mCurStatsToken = null;
                showCurrentInputInternal(userData.mImeBindingState.mFocusedWindow, statsToken);
            }
        } else {
            if (isShowRequestedForCurrentWindow(userId)) {
                if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
                // Re-use current statsToken, if it exists.
                final var statsToken = userData.mCurStatsToken != null ? userData.mCurStatsToken
                    : createStatsTokenForFocusedClient(true /* show */,
                            SoftInputShowHideReason.ATTACH_NEW_INPUT, userId);
                userData.mCurStatsToken = null;
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                showCurrentInputLocked(userData.mImeBindingState.mFocusedWindow, statsToken,
                        visibilityStateComputer.getShowFlags(), MotionEvent.TOOL_TYPE_UNKNOWN,
                        null /* resultReceiver */, SoftInputShowHideReason.ATTACH_NEW_INPUT,
                        userId);
            }
        }

        final var curId = bindingController.getCurId();
        final InputMethodInfo curInputMethodInfo = InputMethodSettingsRepository.get(userId)
                .getMethodMap().get(curId);
        final boolean suppressesSpellChecker =
                curInputMethodInfo != null && curInputMethodInfo.suppressesSpellChecker();
        final SparseArray<IAccessibilityInputMethodSession> accessibilityInputMethodSessions =
                createAccessibilityInputMethodSessions(
                        userData.mCurClient.mAccessibilitySessions);
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
            boolean initial, @UserIdInt int userId) {
        final var userData = getUserData(userId);

        if (!userData.mBoundToAccessibility) {
            AccessibilityManagerInternal.get().bindInput();
            userData.mBoundToAccessibility = true;
        }

        // TODO(b/187453053): grantImplicitAccess to accessibility services access? if so, need to
        //  record accessibility services uid.

        // We don't start input when session for a11y is created. We start input when
        // input method start input, a11y manager service is always on.
        if (startInputReason != StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY) {
            setEnabledSessionForAccessibilityLocked(userData.mCurClient.mAccessibilitySessions,
                    userData);
            AccessibilityManagerInternal.get().startInput(
                    userData.mCurRemoteAccessibilityInputConnection,
                    userData.mCurEditorInfo, !initial /* restarting */);
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
            @NonNull InputMethodBindingController bindingController) {

        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;

        // Compute the final shown display ID with validated cs.selfReportedDisplayId for this
        // session & other conditions.
        ImeTargetWindowState winState = visibilityStateComputer.getWindowStateOrNull(
                userData.mImeBindingState.mFocusedWindow);
        if (winState == null) {
            return InputBindResult.NOT_IME_TARGET_WINDOW;
        }
        final int csDisplayId = cs.mSelfReportedDisplayId;
        bindingController.setDisplayIdToShowIme(
                visibilityStateComputer.computeImeDisplayId(winState, csDisplayId));

        // Potentially override the selected input method if the new display belongs to a virtual
        // device with a custom IME.
        String selectedMethodId = bindingController.getSelectedMethodId();
        final String deviceMethodId = computeCurrentDeviceMethodIdLocked(
                bindingController.getUserId(), selectedMethodId);
        if (deviceMethodId == null) {
            visibilityStateComputer.getImePolicy().setImeHiddenByDisplayPolicy(true);
        } else if (!Objects.equals(deviceMethodId, selectedMethodId)) {
            setInputMethodLocked(deviceMethodId, NOT_A_SUBTYPE_INDEX,
                    bindingController.getDeviceIdToShowIme(), userId);
            selectedMethodId = deviceMethodId;
        }

        if (visibilityStateComputer.getImePolicy().isImeHiddenByDisplayPolicy()) {
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_DISPLAY_IME_POLICY_HIDE, userId);
            return InputBindResult.NO_IME;
        }

        // If no method is currently selected, do nothing.
        if (selectedMethodId == null) {
            return InputBindResult.NO_IME;
        }

        if (userData.mCurClient != cs) {
            prepareClientSwitchLocked(cs, userId);
        }

        final boolean connectionWasActive = userData.mCurInputConnection != null;

        // Bump up the sequence for this client and attach it.
        bindingController.advanceSequenceNumber();

        userData.mCurClient = cs;
        userData.mCurInputConnection = inputConnection;
        userData.mCurRemoteAccessibilityInputConnection = remoteAccessibilityInputConnection;
        userData.mCurImeDispatcher = imeDispatcher;
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
        userData.mCurEditorInfo = editorInfo;

        // Notify input manager if the connection state changes.
        final boolean connectionIsActive = userData.mCurInputConnection != null;
        if (connectionIsActive != connectionWasActive) {
            mInputManagerInternal.notifyInputMethodConnectionActive(connectionIsActive);
        }

        // If configured, we want to avoid starting up the IME if it is not supposed to be showing
        if (shouldPreventImeStartupLocked(selectedMethodId, startInputFlags,
                unverifiedTargetSdkVersion, userId)) {
            if (DEBUG) {
                Slog.d(TAG, "Avoiding IME startup and unbinding current input method.");
            }
            bindingController.invalidateAutofillSession();
            bindingController.unbindCurrentMethod();
            return InputBindResult.NO_EDITOR;
        }

        // Check if the input method is changing.
        // We expect the caller has already verified that the client is allowed to access this
        // display ID.
        final String curId = bindingController.getCurId();
        final int displayIdToShowIme = bindingController.getDisplayIdToShowIme();
        if (curId != null && curId.equals(bindingController.getSelectedMethodId())
                && displayIdToShowIme == bindingController.getCurTokenDisplayId()) {
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
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0, userId);
                return attachNewInputLocked(startInputReason,
                        (startInputFlags & StartInputFlags.INITIAL_CONNECTION) != 0, userId);
            }

            InputBindResult bindResult = tryReuseConnectionLocked(bindingController, cs, userId);
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
    private String computeCurrentDeviceMethodIdLocked(@UserIdInt int userId,
            String currentMethodId) {
        if (mVdmInternal == null) {
            mVdmInternal = LocalServices.getService(VirtualDeviceManagerInternal.class);
        }
        if (mVdmInternal == null || !android.companion.virtual.flags.Flags.vdmCustomIme()) {
            return currentMethodId;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final var bindingController = getInputMethodBindingController(userId);
        final int oldDeviceId = bindingController.getDeviceIdToShowIme();
        final int displayIdToShowIme = bindingController.getDisplayIdToShowIme();
        final int newDeviceId = mVdmInternal.getDeviceIdForDisplayId(displayIdToShowIme);
        bindingController.setDeviceIdToShowIme(newDeviceId);
        if (newDeviceId == DEVICE_ID_DEFAULT) {
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

        final String deviceMethodId = mVirtualDeviceMethodMap.get(newDeviceId, currentMethodId);
        if (Objects.equals(deviceMethodId, currentMethodId)) {
            return currentMethodId;
        } else if (!settings.getMethodMap().containsKey(deviceMethodId)) {
            if (DEBUG) {
                Slog.v(TAG, "Disabling IME on virtual device with id " + newDeviceId
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
                    + displayIdToShowIme + " belongs to device with id " + newDeviceId);
        }
        return deviceMethodId;
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldPreventImeStartupLocked(
            @NonNull String selectedMethodId,
            @StartInputFlags int startInputFlags,
            int unverifiedTargetSdkVersion,
            @UserIdInt int userId) {
        // Fast-path for the majority of cases
        if (!mPreventImeStartupUnlessTextEditor) {
            return false;
        }
        if (isShowRequestedForCurrentWindow(userId)) {
            return false;
        }
        if (isSoftInputModeStateVisibleAllowed(unverifiedTargetSdkVersion, startInputFlags)) {
            return false;
        }
        final InputMethodInfo imi = InputMethodSettingsRepository.get(userId)
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
    private void prepareClientSwitchLocked(ClientState cs, @UserIdInt int userId) {
        // If the client is changing, we need to switch over to the new
        // one.
        unbindCurrentClientLocked(UnbindReason.SWITCH_CLIENT, userId);
        // If the screen is on, inform the new client it is active
        if (mIsInteractive) {
            cs.mClient.setActive(true /* active */, false /* fullscreen */);
        }
    }

    @GuardedBy("ImfLock.class")
    @Nullable
    private InputBindResult tryReuseConnectionLocked(
            @NonNull InputMethodBindingController bindingController, @NonNull ClientState cs,
            @UserIdInt int userId) {
        if (bindingController.hasMainConnection()) {
            if (bindingController.getCurMethod() != null) {
                if (!Flags.useZeroJankProxy()) {
                    // Return to client, and we will get back with it when
                    // we have had a session made for it.
                    requestClientSessionLocked(cs, userId);
                    requestClientSessionForAccessibilityLocked(cs);
                }
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
                            bindingController.getSelectedMethodId(), bindingDuration, 0);
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
    void initializeImeLocked(@NonNull IInputMethodInvoker inputMethod, @NonNull IBinder token,
            @NonNull InputMethodBindingController bindingController) {
        if (DEBUG) {
            Slog.v(TAG, "Sending attach of token: " + token + " for display: "
                    + bindingController.getCurTokenDisplayId());
        }
        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        inputMethod.initializeInternal(token,
                new InputMethodPrivilegedOperationsImpl(this, token, userData),
                getInputMethodNavButtonFlagsLocked(userData));
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
            InputChannel channel, @UserIdInt int userId) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.onSessionCreated");
        try {
            synchronized (ImfLock.class) {
                if (mUserSwitchHandlerTask != null) {
                    // We have a pending user-switching task so it's better to just ignore this
                    // session.
                    channel.dispose();
                    return;
                }
                final var userData = getUserData(userId);
                final var bindingController = userData.mBindingController;
                IInputMethodInvoker curMethod = bindingController.getCurMethod();
                if (curMethod != null && method != null
                        && curMethod.asBinder() == method.asBinder()) {
                    if (userData.mCurClient != null) {
                        clearClientSessionLocked(userData.mCurClient);
                        userData.mCurClient.mCurSession = new SessionState(
                                userData.mCurClient, method, session, channel, userId);
                        InputBindResult res = attachNewInputLocked(
                                StartInputReason.SESSION_CREATED_BY_IME, true, userId);
                        attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_IME, true,
                                userId);
                        if (res.method != null) {
                            userData.mCurClient.mClient.onBindMethod(res);
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
    void resetSystemUiLocked(InputMethodBindingController bindingController) {
        // Set IME window status as invisible when unbinding current method.
        final int imeWindowVis = 0;
        final int backDisposition = InputMethodService.BACK_DISPOSITION_DEFAULT;
        bindingController.setImeWindowVis(imeWindowVis);
        bindingController.setBackDisposition(backDisposition);
        updateSystemUiLocked(imeWindowVis, backDisposition, bindingController.getUserId());
    }

    @GuardedBy("ImfLock.class")
    void resetCurrentMethodAndClientLocked(@UnbindReason int unbindClientReason,
            @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        bindingController.setSelectedMethodId(null);

        // Callback before clean-up binding states.
        // TODO(b/338461930): Check if this is still necessary or not.
        onUnbindCurrentMethodByReset(userId);
        bindingController.unbindCurrentMethod();
        unbindCurrentClientLocked(unbindClientReason, userId);
    }

    @GuardedBy("ImfLock.class")
    void reRequestCurrentClientSessionLocked(@UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient != null) {
            clearClientSessionLocked(userData.mCurClient);
            clearClientSessionForAccessibilityLocked(userData.mCurClient);
            requestClientSessionLocked(userData.mCurClient, userId);
            requestClientSessionForAccessibilityLocked(userData.mCurClient);
        }
    }

    @GuardedBy("ImfLock.class")
    void requestClientSessionLocked(ClientState cs, @UserIdInt int userId) {
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

            final var bindingController = getInputMethodBindingController(userId);
            final IInputMethodInvoker curMethod = bindingController.getCurMethod();
            final IInputMethodSessionCallback.Stub callback =
                    new IInputMethodSessionCallback.Stub() {
                        @Override
                        public void sessionCreated(IInputMethodSession session) {
                            final long ident = Binder.clearCallingIdentity();
                            try {
                                onSessionCreated(curMethod, session, serverChannel, userId);
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
                    final int userId = sessionState.mUserId;
                    final var bindingController = getInputMethodBindingController(userId);
                    updateSystemUiLocked(0 /* vis */, bindingController.getBackDisposition(),
                            userId);
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
    void clearClientSessionsLocked(@NonNull InputMethodBindingController bindingController) {
        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        if (bindingController.getCurMethod() != null) {
            // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
            @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession = c -> {
                // TODO(b/305849394): Figure out what we should do for single user IME mode.
                final boolean shouldClearClientSession =
                        !mConcurrentMultiUserModeEnabled
                                || UserHandle.getUserId(c.mUid) == userId;
                if (shouldClearClientSession) {
                    clearClientSessionLocked(c);
                    clearClientSessionForAccessibilityLocked(c);
                }
            };
            mClientController.forAllClients(clearClientSession);

            finishSessionLocked(userData.mEnabledSession);
            for (int i = 0; i < userData.mEnabledAccessibilitySessions.size(); i++) {
                finishSessionForAccessibilityLocked(
                        userData.mEnabledAccessibilitySessions.valueAt(i));
            }
            userData.mEnabledSession = null;
            userData.mEnabledAccessibilitySessions.clear();
            scheduleNotifyImeUidToAudioService(Process.INVALID_UID);
        }
        hideStatusBarIconLocked(userId);
        getUserData(userId).mInFullscreenMode = false;
        mWindowManagerInternal.setDismissImeOnBackKeyPressed(false);
        scheduleResetStylusHandwriting();
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void updateStatusIconLocked(String packageName, @DrawableRes int iconId,
            @NonNull UserData userData) {
        final int userId = userData.mUserId;
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        if (iconId == 0) {
            if (DEBUG) Slog.d(TAG, "hide the small icon for the input method");
            hideStatusBarIconLocked(userId);
        } else if (packageName != null) {
            if (DEBUG) Slog.d(TAG, "show a small icon for the input method");
            final PackageManager userAwarePackageManager =
                    getPackageManagerForUser(mContext, userId);
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
    }

    @GuardedBy("ImfLock.class")
    private void hideStatusBarIconLocked(@UserIdInt int userId) {
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        if (mStatusBarManagerInternal != null) {
            mStatusBarManagerInternal.setIconVisibility(mSlotIme, false);
        }
    }

    @GuardedBy("ImfLock.class")
    @InputMethodNavButtonFlags
    private int getInputMethodNavButtonFlagsLocked(@NonNull UserData userData) {
        final int userId = userData.mUserId;
        final var bindingController = userData.mBindingController;
        // Whether the current display has a navigation bar. When this is false (e.g. emulator),
        // the IME should not draw the IME navigation bar.
        final int tokenDisplayId = bindingController.getCurTokenDisplayId();
        final boolean hasNavigationBar = mWindowManagerInternal
                .hasNavigationBar(tokenDisplayId != INVALID_DISPLAY
                        ? tokenDisplayId : DEFAULT_DISPLAY);
        final boolean canImeDrawsImeNavBar = userData.mImeDrawsNavBar.get() && hasNavigationBar;
        final boolean shouldShowImeSwitcherWhenImeIsShown = shouldShowImeSwitcherLocked(
                InputMethodService.IME_ACTIVE | InputMethodService.IME_VISIBLE, userId);
        return (canImeDrawsImeNavBar ? InputMethodNavButtonFlags.IME_DRAWS_IME_NAV_BAR : 0)
                | (shouldShowImeSwitcherWhenImeIsShown
                ? InputMethodNavButtonFlags.SHOW_IME_SWITCHER_WHEN_IME_IS_SHOWN : 0);
    }

    @GuardedBy("ImfLock.class")
    private boolean shouldShowImeSwitcherLocked(@ImeWindowVisibility int visibility,
            @UserIdInt int userId) {
        if (!mShowOngoingImeSwitcherForPhones) return false;
        // When the IME switcher dialog is shown, the IME switcher button should be hidden.
        // TODO(b/305849394): Make mMenuController multi-user aware.
        final boolean switcherMenuShowing = Flags.imeSwitcherRevamp()
                ? mMenuControllerNew.isShowing()
                : mMenuController.getSwitchingDialogLocked() != null;
        if (switcherMenuShowing) {
            return false;
        }
        // When we are switching IMEs, the IME switcher button should be hidden.
        final var bindingController = getInputMethodBindingController(userId);
        if (!Objects.equals(bindingController.getCurId(),
                bindingController.getSelectedMethodId())) {
            return false;
        }
        if (mWindowManagerInternal.isKeyguardShowingAndNotOccluded()
                && mWindowManagerInternal.isKeyguardSecure(userId)) {
            return false;
        }
        if ((visibility & InputMethodService.IME_ACTIVE) == 0) {
            return false;
        }
        if (mWindowManagerInternal.isHardKeyboardAvailable() && !Flags.imeSwitcherRevamp()) {
            // When physical keyboard is attached, we show the ime switcher (or notification if
            // NavBar is not available) because SHOW_IME_WITH_HARD_KEYBOARD settings currently
            // exists in the IME switcher dialog.  Might be OK to remove this condition once
            // SHOW_IME_WITH_HARD_KEYBOARD settings finds a good place to live.
            return true;
        } else if ((visibility & InputMethodService.IME_VISIBLE) == 0) {
            return false;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (Flags.imeSwitcherRevamp()) {
            // The IME switcher button should be shown when the current IME specified a
            // language settings activity.
            final var curImi = settings.getMethodMap().get(settings.getSelectedInputMethod());
            if (curImi != null && curImi.createImeLanguageSettingsActivityIntent() != null) {
                return true;
            }
        }

        return hasMultipleSubtypesForSwitcher(false /* nonAuxOnly */, settings);
    }

    /**
     * Checks whether there at least two subtypes that should be shown for the IME switcher menu,
     * across all enabled IMEs for the given user.
     *
     * @param nonAuxOnly whether to check only for non auxiliary subtypes.
     * @param settings   the input method settings under the given user ID.
     */
    private static boolean hasMultipleSubtypesForSwitcher(boolean nonAuxOnly,
            @NonNull InputMethodSettings settings) {
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
        if (Flags.imeSwitcherRevamp() && nonAuxOnly) {
            return nonAuxCount > 1;
        } else if (nonAuxCount > 1 || auxCount > 1) {
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
    @GuardedBy("ImfLock.class")
    @SuppressWarnings("deprecation")
    private void setImeWindowStatusLocked(@ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, @NonNull UserData userData) {
        final int topFocusedDisplayId = mWindowManagerInternal.getTopFocusedDisplayId();

        final int userId = userData.mUserId;
        final var bindingController = userData.mBindingController;
        // Skip update IME status when current token display is not same as focused display.
        // Note that we still need to update IME status when focusing external display
        // that does not support system decoration and fallback to show IME on default
        // display since it is intentional behavior.
        final int tokenDisplayId = bindingController.getCurTokenDisplayId();
        if (tokenDisplayId != topFocusedDisplayId && tokenDisplayId != FALLBACK_DISPLAY_ID) {
            return;
        }
        bindingController.setImeWindowVis(vis);
        bindingController.setBackDisposition(backDisposition);
        updateSystemUiLocked(vis, backDisposition, userId);

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
    @GuardedBy("ImfLock.class")
    private void reportStartInputLocked(IBinder startInputToken, @NonNull UserData userData) {
        final IBinder targetWindow = mImeTargetWindowMap.get(startInputToken);
        if (targetWindow != null) {
            mWindowManagerInternal.updateInputMethodTargetWindow(targetWindow);
        }
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        visibilityStateComputer.setLastImeTargetWindow(targetWindow);
    }

    @GuardedBy("ImfLock.class")
    private void updateImeWindowStatusLocked(boolean disableImeIcon, int displayId) {
        final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
        if (disableImeIcon) {
            final var bindingController = getInputMethodBindingController(userId);
            updateSystemUiLocked(0 /* vis */, bindingController.getBackDisposition(), userId);
        } else {
            updateSystemUiLocked(userId);
        }
    }

    // Caution! This method is called in this class. Handle multi-user carefully
    @GuardedBy("ImfLock.class")
    void updateSystemUiLocked(@UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        updateSystemUiLocked(bindingController.getImeWindowVis(),
                bindingController.getBackDisposition(), userId);
    }

    @GuardedBy("ImfLock.class")
    private void updateSystemUiLocked(@ImeWindowVisibility int vis,
            @BackDispositionMode int backDisposition, @UserIdInt int userId) {
        // To minimize app compat risk, ignore background users' request for single-user mode.
        // TODO(b/357178609): generalize the logic and remove this special rule.
        if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
            return;
        }
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        final var curToken = bindingController.getCurToken();
        if (curToken == null) {
            return;
        }
        final int curTokenDisplayId = bindingController.getCurTokenDisplayId();
        if (DEBUG) {
            Slog.d(TAG, "IME window vis: " + vis
                    + " active: " + (vis & InputMethodService.IME_ACTIVE)
                    + " visible: " + (vis & InputMethodService.IME_VISIBLE)
                    + " displayId: " + curTokenDisplayId);
        }
        final IBinder focusedWindowToken = userData.mImeBindingState != null
                ? userData.mImeBindingState.mFocusedWindow : null;
        final Boolean windowPerceptible = focusedWindowToken != null
                ? mFocusedWindowPerceptible.get(focusedWindowToken) : null;

        // TODO: Move this clearing calling identity block to setImeWindowStatusLocked after making
        //  sure all updateSystemUi happens on system privilege.
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
            final var curId = bindingController.getCurId();
            // TODO(b/305849394): Make mMenuController multi-user aware.
            final boolean switcherMenuShowing = Flags.imeSwitcherRevamp()
                    ? mMenuControllerNew.isShowing()
                    : mMenuController.getSwitchingDialogLocked() != null;
            if (switcherMenuShowing
                    || !Objects.equals(curId, bindingController.getSelectedMethodId())) {
                // When the IME switcher dialog is shown, or we are switching IMEs,
                // the back button should be in the default state (as if the IME is not shown).
                backDisposition = InputMethodService.BACK_DISPOSITION_ADJUST_NOTHING;
            }
            final boolean needsToShowImeSwitcher = shouldShowImeSwitcherLocked(vis, userId);
            if (mStatusBarManagerInternal != null) {
                mStatusBarManagerInternal.setImeWindowStatus(curTokenDisplayId, vis,
                        backDisposition, needsToShowImeSwitcher);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("ImfLock.class")
    void updateFromSettingsLocked(boolean enabledMayChange, @UserIdInt int userId) {
        updateInputMethodsFromSettingsLocked(enabledMayChange, userId);
        if (!Flags.imeSwitcherRevamp()) {
            mMenuController.updateKeyboardFromSettingsLocked(userId);
        }
    }

    @GuardedBy("ImfLock.class")
    void updateInputMethodsFromSettingsLocked(boolean enabledMayChange, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (enabledMayChange) {
            final PackageManager userAwarePackageManager = getPackageManagerForUser(mContext,
                    userId);

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

        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        if (bindingController.getDeviceIdToShowIme() == DEVICE_ID_DEFAULT) {
            String ime = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_INPUT_METHOD, null, userId);
            String defaultDeviceIme = SecureSettingsWrapper.getString(
                    Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, userId);
            if (defaultDeviceIme != null && !Objects.equals(ime, defaultDeviceIme)) {
                if (DEBUG) {
                    Slog.v(TAG, "Current input method " + ime + " differs from the stored default"
                            + " device input method for user " + userId
                            + " - restoring " + defaultDeviceIme);
                }
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_INPUT_METHOD, defaultDeviceIme, userId);
                SecureSettingsWrapper.putString(
                        Settings.Secure.DEFAULT_DEVICE_INPUT_METHOD, null, userId);
            }
        }

        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String id = settings.getSelectedInputMethod();
        // There is no input method selected, try to choose new applicable input method.
        if (TextUtils.isEmpty(id) && chooseNewDefaultIMELocked(userId)) {
            id = settings.getSelectedInputMethod();
        }
        if (!TextUtils.isEmpty(id)) {
            try {
                setInputMethodLocked(id, settings.getSelectedInputMethodSubtypeIndex(id), userId);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e);
                resetCurrentMethodAndClientLocked(UnbindReason.SWITCH_IME_FAILED, userId);
            }
        } else {
            // There is no longer an input method set, so stop any current one.
            resetCurrentMethodAndClientLocked(UnbindReason.NO_IME, userId);
        }

        userData.mSwitchingController.resetCircularListLocked(mContext, settings);
        userData.mHardwareKeyboardShortcutController.update(settings);
        sendOnNavButtonFlagsChangedLocked(userData);
    }

    @GuardedBy("ImfLock.class")
    private void notifyInputMethodSubtypeChangedLocked(@UserIdInt int userId,
            @NonNull InputMethodInfo imi, @Nullable InputMethodSubtype subtype) {
        final InputMethodSubtype normalizedSubtype =
                subtype != null && subtype.isSuitableForPhysicalKeyboardLayoutMapping()
                        ? subtype : null;
        final InputMethodSubtypeHandle newSubtypeHandle = normalizedSubtype != null
                ? InputMethodSubtypeHandle.of(imi, normalizedSubtype) : null;

        final var userData = getUserData(userId);

        // A workaround for b/356879517. KeyboardLayoutManager has relied on an implementation
        // detail that IMMS triggers this callback only for the current IME user.
        // TODO(b/357663774): Figure out how to better handle this scenario.
        userData.mSubtypeForKeyboardLayoutMapping =
                Pair.create(newSubtypeHandle, normalizedSubtype);
        if (userId != mCurrentImeUserId) {
            return;
        }
        mInputManagerInternal.onInputMethodSubtypeChangedForKeyboardLayoutMapping(
                userId, newSubtypeHandle, normalizedSubtype);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeIndex, @UserIdInt int userId) {
        setInputMethodLocked(id, subtypeIndex, DEVICE_ID_DEFAULT, userId);
    }

    @GuardedBy("ImfLock.class")
    void setInputMethodLocked(String id, int subtypeIndex, int deviceId, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        InputMethodInfo info = settings.getMethodMap().get(id);
        if (info == null) {
            throw getExceptionForUnknownImeId(id);
        }

        final var bindingController = getInputMethodBindingController(userId);
        // See if we need to notify a subtype change within the same IME.
        if (id.equals(bindingController.getSelectedMethodId())) {
            final int subtypeCount = info.getSubtypeCount();
            if (subtypeCount <= 0) {
                notifyInputMethodSubtypeChangedLocked(userId, info, null);
                return;
            }
            final InputMethodSubtype oldSubtype = bindingController.getCurrentSubtype();
            final InputMethodSubtype newSubtype;
            if (subtypeIndex >= 0 && subtypeIndex < subtypeCount) {
                newSubtype = info.getSubtypeAt(subtypeIndex);
            } else {
                // If subtype is null, try to find the most applicable one from
                // getCurrentInputMethodSubtype.
                subtypeIndex = NOT_A_SUBTYPE_INDEX;
                // TODO(b/347083680): The method below has questionable behaviors.
                newSubtype = bindingController.getCurrentInputMethodSubtype();
                if (newSubtype != null) {
                    for (int i = 0; i < subtypeCount; ++i) {
                        if (Objects.equals(newSubtype, info.getSubtypeAt(i))) {
                            subtypeIndex = i;
                            break;
                        }
                    }
                }
            }
            if (!Objects.equals(newSubtype, oldSubtype)) {
                setSelectedInputMethodAndSubtypeLocked(info, subtypeIndex, true, userId);
                IInputMethodInvoker curMethod = bindingController.getCurMethod();
                if (curMethod != null) {
                    updateSystemUiLocked(bindingController.getImeWindowVis(),
                            bindingController.getBackDisposition(), userId);
                    curMethod.changeInputMethodSubtype(newSubtype);
                }
            }
            return;
        }

        // Changing to a different IME.
        if (bindingController.getDeviceIdToShowIme() != DEVICE_ID_DEFAULT
                && deviceId == DEVICE_ID_DEFAULT) {
            // This change should only be applicable to the default device but the current input
            // method is a custom one specific to a virtual device. So only update the settings
            // entry used to restore the default device input method once we want to show the IME
            // back on the default device.
            settings.putSelectedDefaultDeviceInputMethod(id);
            return;
        }
        IInputMethodInvoker curMethod = bindingController.getCurMethod();
        if (curMethod != null) {
            curMethod.removeStylusHandwritingWindow();
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            setSelectedInputMethodAndSubtypeLocked(info, subtypeIndex, false, userId);
            // mCurMethodId should be updated after setSelectedInputMethodAndSubtypeLocked()
            // because mCurMethodId is stored as a history in
            // setSelectedInputMethodAndSubtypeLocked().
            bindingController.setSelectedMethodId(id);

            if (mActivityManagerInternal.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_INPUT_METHOD_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("input_method_id", id);
                mContext.sendBroadcastAsUser(intent, UserHandle.CURRENT);
            }
            bindingController.unbindCurrentMethod();
            unbindCurrentClientLocked(UnbindReason.SWITCH_IME, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @GuardedBy("ImfLock.class")
    private void sendResultReceiverFailureLocked(@Nullable ResultReceiver resultReceiver,
            @UserIdInt int userId) {
        if (resultReceiver == null) {
            return;
        }
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        final boolean isInputShown = visibilityStateComputer.isInputShown();
        resultReceiver.send(isInputShown
                ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                : InputMethodManager.RESULT_UNCHANGED_HIDDEN, null);
    }

    @Override
    public boolean showSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, boolean async) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showSoftInput");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int uid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(uid);
            final int userId = resolveImeUserIdLocked(callingUserId, client);
            final boolean result = showSoftInputLocked(client, windowToken, statsToken, flags,
                    lastClickToolType, resultReceiver, reason, uid, userId);
            // When ZeroJankProxy is enabled, the app has already received "true" as the return
            // value, and expect "resultReceiver" to be notified later. See b/327751155.
            if (!result && Flags.useZeroJankProxy()) {
                sendResultReceiverFailureLocked(resultReceiver, userId);
            }
            return result;  // ignored when ZeroJankProxy is enabled.
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean showSoftInputLocked(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            int lastClickToolType, ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, int uid, @UserIdInt int userId) {
        if (!canInteractWithImeLocked(uid, client, "showSoftInput", statsToken,
                userId)) {
            ImeTracker.forLogging().onFailed(
                    statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            return false;
        }
        final var userData = getUserData(userId);
        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
            if (Flags.refactorInsetsController()) {
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                boolean wasVisible = visibilityStateComputer.isInputShown();
                if (setImeVisibilityOnFocusedWindowClient(false, userData, statsToken)) {
                    if (resultReceiver != null) {
                        resultReceiver.send(
                                wasVisible ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                                        : InputMethodManager.RESULT_SHOWN, null);
                    }
                    return true;
                }
                return false;
            } else {
                return showCurrentInputLocked(windowToken, statsToken, flags, lastClickToolType,
                        resultReceiver, reason, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    // TODO(b/353463205) check callers to see if we can make statsToken @NonNull
    boolean showCurrentInputInternal(IBinder windowToken, @Nullable ImeTracker.Token statsToken) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showCurrentInputInternal");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#showSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromWindowLocked(windowToken);
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
                return showCurrentInputLocked(windowToken, statsToken, 0 /* flags */,
                        0 /* lastClickTooType */, null /* resultReceiver */,
                        SoftInputShowHideReason.SHOW_SOFT_INPUT, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
                Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
            }
        }
    }

    // TODO(b/353463205) check callers to see if we can make statsToken @NonNull
    boolean hideCurrentInputInternal(IBinder windowToken, @Nullable ImeTracker.Token statsToken) {
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideCurrentInputInternal");
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromWindowLocked(windowToken);
            final long ident = Binder.clearCallingIdentity();
            try {
                if (DEBUG) Slog.v(TAG, "Client requesting input be hidden");
                return hideCurrentInputLocked(windowToken, statsToken, 0 /* flags */,
                        null /* resultReceiver */, SoftInputShowHideReason.HIDE_SOFT_INPUT,
                        userId);
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
            final var bindingController = getInputMethodBindingController(userId);
            if (!bindingController.supportsConnectionlessStylusHandwriting()) {
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
            final int uid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(uid);
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdLocked(callingUserId);
                if (!acceptingDelegation) {
                    mHwController.clearPendingHandwritingDelegation();
                }
                if (!canInteractWithImeLocked(uid, client, "startStylusHandwriting",
                        null /* statsToken */, userId)) {
                    return false;
                }
                if (!hasSupportedStylusLocked()) {
                    Slog.w(TAG, "No supported Stylus hardware found on device. Ignoring"
                            + " startStylusHandwriting()");
                    return false;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    final var bindingController = getInputMethodBindingController(userId);
                    if (!bindingController.supportsStylusHandwriting()) {
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
                    final IInputMethodInvoker curMethod = bindingController.getCurMethod();
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
            final var bindingController = getInputMethodBindingController(userId);
            if (mHwController.isDelegationUsingConnectionlessFlow()) {
                final IInputMethodInvoker curMethod = bindingController.getCurMethod();
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
                final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                final var userData = getUserData(userId);
                if (userData.mImeBindingState.mFocusedWindow != windowToken
                        || (windowPerceptible != null && windowPerceptible == perceptible)) {
                    return;
                }
                mFocusedWindowPerceptible.put(windowToken, windowPerceptible);
                updateSystemUiLocked(userId);
            }
        });
    }

    @GuardedBy("ImfLock.class")
    private boolean showCurrentInputLocked(IBinder windowToken,
            @InputMethodManager.ShowFlags int flags, @SoftInputShowHideReason int reason,
            @UserIdInt int userId) {
        final var statsToken = createStatsTokenForFocusedClient(true /* show */, reason, userId);
        return showCurrentInputLocked(windowToken, statsToken, flags,
                MotionEvent.TOOL_TYPE_UNKNOWN, null /* resultReceiver */, reason, userId);
    }

    @GuardedBy("ImfLock.class")
    boolean showCurrentInputLocked(IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.ShowFlags int flags,
            @MotionEvent.ToolType int lastClickToolType, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        if (!visibilityStateComputer.onImeShowFlags(statsToken, flags)) {
            return false;
        }

        if (!mSystemReady) {
            ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);
            return false;
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SYSTEM_READY);

        visibilityStateComputer.requestImeVisibility(windowToken, true);

        // Ensure binding the connection when IME is going to show.
        final var bindingController = userData.mBindingController;
        bindingController.setCurrentMethodVisible();
        final IInputMethodInvoker curMethod = bindingController.getCurMethod();
        ImeTracker.forLogging().onCancelled(userData.mCurStatsToken,
                ImeTracker.PHASE_SERVER_WAIT_IME);
        final boolean readyToDispatchToIme;
        if (Flags.deferShowSoftInputUntilSessionCreation()) {
            readyToDispatchToIme =
                    curMethod != null && userData.mCurClient != null
                            && userData.mCurClient.mCurSession != null;
        } else {
            readyToDispatchToIme = curMethod != null;
        }
        if (readyToDispatchToIme) {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_HAS_IME);
            userData.mCurStatsToken = null;

            if (Flags.useHandwritingListenerForTooltype()) {
                maybeReportToolType(userId);
            } else if (lastClickToolType != MotionEvent.TOOL_TYPE_UNKNOWN) {
                onUpdateEditorToolTypeLocked(lastClickToolType, userId);
            }
            mVisibilityApplier.performShowIme(windowToken, statsToken,
                    visibilityStateComputer.getShowFlagsForInputMethodServiceOnly(),
                    resultReceiver, reason, userId);
            visibilityStateComputer.setInputShown(true);
            return true;
        } else {
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_WAIT_IME);
            userData.mCurStatsToken = statsToken;
        }
        return false;
    }

    @GuardedBy("ImfLock.class")
    private void maybeReportToolType(@UserIdInt int userId) {
        // TODO(b/356638981): This needs to be compatible with visible background users.
        int lastDeviceId = mInputManagerInternal.getLastUsedInputDeviceId();
        final InputManager im = mContext.getSystemService(InputManager.class);
        if (im == null) {
            return;
        }
        InputDevice device = im.getInputDevice(lastDeviceId);
        if (device == null) {
            return;
        }
        int toolType;
        if (isStylusDevice(device)) {
            toolType = MotionEvent.TOOL_TYPE_STYLUS;
        } else if (isFingerDevice(device)) {
            toolType = MotionEvent.TOOL_TYPE_FINGER;
        } else {
            // other toolTypes are irrelevant and reported as unknown.
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN;
        }
        onUpdateEditorToolTypeLocked(toolType, userId);
    }

    @Override
    public boolean hideSoftInput(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason, boolean async) {
        ImeTracing.getInstance().triggerManagerServiceDump(
                "InputMethodManagerService#hideSoftInput", mDumper);
        synchronized (ImfLock.class) {
            final int uid = Binder.getCallingUid();
            final int callingUserId = UserHandle.getUserId(uid);
            final int userId = resolveImeUserIdLocked(callingUserId, client);
            final boolean result = hideSoftInputLocked(client, windowToken, statsToken, flags,
                    resultReceiver, reason, uid, userId);
            // When ZeroJankProxy is enabled, the app has already received "true" as the return
            // value, and expect "resultReceiver" to be notified later. See b/327751155.
            if (!result && Flags.useZeroJankProxy()) {
                sendResultReceiverFailureLocked(resultReceiver, userId);
            }
            return result;  // ignored when ZeroJankProxy is enabled.
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean hideSoftInputLocked(IInputMethodClient client, IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @InputMethodManager.HideFlags int flags,
            ResultReceiver resultReceiver, @SoftInputShowHideReason int reason,
            int uid, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        if (!canInteractWithImeLocked(uid, client, "hideSoftInput", statsToken, userId)) {
            if (visibilityStateComputer.isInputShown()) {
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
            if (Flags.refactorInsetsController()) {
                boolean wasVisible = visibilityStateComputer.isInputShown();
                // TODO add windowToken to interface
                if (setImeVisibilityOnFocusedWindowClient(false, userData, statsToken)) {
                    if (resultReceiver != null) {
                        resultReceiver.send(wasVisible ? InputMethodManager.RESULT_HIDDEN
                                : InputMethodManager.RESULT_UNCHANGED_HIDDEN, null);
                    }
                    return true;
                }
                return false;
            } else {
                return InputMethodManagerService.this.hideCurrentInputLocked(
                        windowToken, statsToken, flags, resultReceiver, reason, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @Override
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public void hideSoftInputFromServerForTest() {
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            final var userData = getUserData(userId);
            hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow, 0 /* flags */,
                    SoftInputShowHideReason.HIDE_SOFT_INPUT, userId);
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean hideCurrentInputLocked(IBinder windowToken,
            @InputMethodManager.HideFlags int flags, @SoftInputShowHideReason int reason,
            @UserIdInt int userId) {
        final var statsToken = createStatsTokenForFocusedClient(false /* show */, reason, userId);
        return hideCurrentInputLocked(windowToken, statsToken, flags, null /* resultReceiver */,
                reason, userId);
    }

    @GuardedBy("ImfLock.class")
    boolean hideCurrentInputLocked(IBinder windowToken, @NonNull ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int flags, @Nullable ResultReceiver resultReceiver,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var bindingController = userData.mBindingController;
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        if (!visibilityStateComputer.canHideIme(statsToken, flags)) {
            return false;
        }

        // There is a chance that IMM#hideSoftInput() is called in a transient state where
        // IMMS#InputShown is already updated to be true whereas the user's ImeWindowVis is still
        // waiting to be updated with the new value sent from IME process.  Even in such a transient
        // state historically we have accepted an incoming call of IMM#hideSoftInput() from the
        // application process as a valid request, and have even promised such a behavior with CTS
        // since Android Eclair.  That's why we need to accept IMM#hideSoftInput() even when only
        // IMMS#InputShown indicates that the software keyboard is shown.
        // TODO(b/246309664): Clean up IMMS#mImeWindowVis
        IInputMethodInvoker curMethod = bindingController.getCurMethod();
        final boolean shouldHideSoftInput = curMethod != null
                && (visibilityStateComputer.isInputShown()
                || (bindingController.getImeWindowVis() & InputMethodService.IME_ACTIVE) != 0);

        visibilityStateComputer.requestImeVisibility(windowToken, false);
        if (shouldHideSoftInput) {
            // The IME will report its visible state again after the following message finally
            // delivered to the IME process as an IPC.  Hence the inconsistency between
            // IMMS#mInputShown and the user's ImeWindowVis should be resolved spontaneously in
            // the final state.
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
            mVisibilityApplier.performHideIme(windowToken, statsToken, resultReceiver, reason,
                    userId);
        } else {
            ImeTracker.forLogging().onCancelled(statsToken, ImeTracker.PHASE_SERVER_SHOULD_HIDE);
        }
        bindingController.setCurrentMethodNotVisible();
        visibilityStateComputer.clearImeShowFlags();
        // Cancel existing statsToken for show IME as we got a hide request.
        ImeTracker.forLogging().onCancelled(userData.mCurStatsToken,
                ImeTracker.PHASE_SERVER_WAIT_IME);
        userData.mCurStatsToken = null;
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
            @NonNull ImeOnBackInvokedDispatcher imeDispatcher, int startInputSeq,
            boolean useAsyncShowHideMethod) {
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
        final var userData = getUserData(userId);
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER,
                    "IMMS.startInputOrWindowGainedFocus");
            ImeTracing.getInstance().triggerManagerServiceDump(
                    "InputMethodManagerService#startInputOrWindowGainedFocus", mDumper);
            final InputBindResult result;
            synchronized (ImfLock.class) {
                final var bindingController = userData.mBindingController;
                // If the system is not yet ready, we shouldn't be running third party code.
                if (!mSystemReady) {
                    return new InputBindResult(
                            InputBindResult.ResultCode.ERROR_SYSTEM_NOT_READY,
                            null /* method */, null /* accessibilitySessions */, null /* channel */,
                            bindingController.getSelectedMethodId(),
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
                    if (!mConcurrentMultiUserModeEnabled && mUserSwitchHandlerTask != null) {
                        // There is already an on-going pending user switch task.
                        final int nextUserId = mUserSwitchHandlerTask.mToUserId;
                        if (userId == nextUserId) {
                            scheduleSwitchUserTaskLocked(userId, cs.mClient);
                            return InputBindResult.USER_SWITCHING;
                        }
                        final int[] profileIdsWithDisabled = mUserManagerInternal.getProfileIds(
                                mCurrentImeUserId, false /* enabledOnly */);
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
                    final var visibilityStateComputer = userData.mVisibilityStateComputer;
                    final boolean showForced = visibilityStateComputer.mShowForced;
                    if (userData.mImeBindingState.mFocusedWindow != windowToken
                            && showForced && shouldClearFlag) {
                        visibilityStateComputer.mShowForced = false;
                    }

                    // Verify if caller is a background user.
                    if (!mConcurrentMultiUserModeEnabled && userId != mCurrentImeUserId) {
                        if (ArrayUtils.contains(
                                mUserManagerInternal.getProfileIds(mCurrentImeUserId, false),
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
                        hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                                0 /* flags */, SoftInputShowHideReason.HIDE_INVALID_USER, userId);
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
                            unverifiedTargetSdkVersion, bindingController, imeDispatcher, cs);
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
            int unverifiedTargetSdkVersion, @NonNull InputMethodBindingController bindingController,
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
                    + " bindingController=" + bindingController
                    + " imeDispatcher=" + imeDispatcher
                    + " cs=" + cs);
        }

        final int userId = bindingController.getUserId();
        final var userData = getUserData(userId);
        final boolean sameWindowFocused = userData.mImeBindingState.mFocusedWindow == windowToken;
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
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        visibilityStateComputer.setWindowState(windowToken, windowState);

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
                        startInputReason, unverifiedTargetSdkVersion, imeDispatcher,
                        bindingController);
            }
            return new InputBindResult(
                    InputBindResult.ResultCode.SUCCESS_REPORT_WINDOW_FOCUS_ONLY,
                    null, null, null, null, -1, false);
        }

        userData.mImeBindingState = new ImeBindingState(bindingController.getUserId(), windowToken,
                softInputMode, cs, editorInfo);
        mFocusedWindowPerceptible.put(windowToken, true);

        // We want to start input before showing the IME, but after closing
        // it.  We want to do this after closing it to help the IME disappear
        // more quickly (not get stuck behind it initializing itself for the
        // new focused input, even if its window wants to hide the IME).
        boolean didStart = false;
        InputBindResult res = null;

        final ImeVisibilityResult imeVisRes = visibilityStateComputer.computeState(windowState,
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
                                imeDispatcher, bindingController);
                        didStart = true;
                    }
                    break;
            }
            final var statsToken = createStatsTokenForFocusedClient(isShow, imeVisRes.getReason(),
                    userId);
            mVisibilityApplier.applyImeVisibility(userData.mImeBindingState.mFocusedWindow,
                    statsToken, imeVisRes.getState(), imeVisRes.getReason(), userId);
            if (imeVisRes.getReason() == SoftInputShowHideReason.HIDE_UNSPECIFIED_WINDOW) {
                // If focused display changed, we should unbind current method
                // to make app window in previous display relayout after Ime
                // window token removed.
                // Note that we can trust client's display ID as long as it matches
                // to the display ID obtained from the window.
                if (cs.mSelfReportedDisplayId != bindingController.getCurTokenDisplayId()) {
                    bindingController.unbindCurrentMethod();
                }
            }
        }
        if (!didStart) {
            if (editorInfo != null) {
                res = startInputUncheckedLocked(cs, inputContext,
                        remoteAccessibilityInputConnection, editorInfo, startInputFlags,
                        startInputReason, unverifiedTargetSdkVersion,
                        imeDispatcher, bindingController);
            } else {
                res = InputBindResult.NULL_EDITOR_INFO;
            }
        }
        return res;
    }

    @GuardedBy("ImfLock.class")
    private boolean canInteractWithImeLocked(int uid, IInputMethodClient client, String methodName,
            @Nullable ImeTracker.Token statsToken, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        if (userData.mCurClient == null || client == null
                || userData.mCurClient.mClient.asBinder() != client.asBinder()) {
            // We need to check if this is the current client with
            // focus in the window manager, to allow this call to
            // be made before input is started in it.
            final ClientState cs = mClientController.getClient(client.asBinder());
            if (cs == null) {
                ImeTracker.forLogging().onFailed(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
                throw new IllegalArgumentException("unknown client " + client.asBinder());
            }
            ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_KNOWN);
            if (!isImeClientFocused(userData.mImeBindingState.mFocusedWindow, cs)) {
                Slog.w(TAG, String.format("Ignoring %s of uid %d : %s", methodName, uid, client));
                return false;
            }
        }
        ImeTracker.forLogging().onProgress(statsToken, ImeTracker.PHASE_SERVER_CLIENT_FOCUSED);
        return true;
    }

    @GuardedBy("ImfLock.class")
    private boolean canShowInputMethodPickerLocked(IInputMethodClient client,
            @UserIdInt int userId) {
        final int uid = Binder.getCallingUid();
        final var userData = getUserData(userId);
        if (userData.mImeBindingState.mFocusedWindowClient != null && client != null
                && userData.mImeBindingState.mFocusedWindowClient.mClient.asBinder()
                == client.asBinder()) {
            return true;
        }
        if (userId != UserHandle.getUserId(uid)) {
            return false;
        }
        final var curIntent = getInputMethodBindingController(userId).getCurIntent();
        if (curIntent != null && InputMethodUtils.checkIfPackageBelongsToUid(
                mPackageManagerInternal, uid, curIntent.getComponent().getPackageName())) {
            return true;
        }
        return false;
    }

    @Override
    public void showInputMethodPickerFromClient(IInputMethodClient client,
            int auxiliarySubtypeMode) {
        if (mConcurrentMultiUserModeEnabled) {
            Slog.w(TAG, "showInputMethodPickerFromClient is not enabled on automotive");
            return;
        }
        final int callingUserId = UserHandle.getCallingUserId();
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canShowInputMethodPickerLocked(client, userId)) {
                Slog.w(TAG, "Ignoring showInputMethodPickerFromClient of uid "
                        + Binder.getCallingUid() + ": " + client);
                return;
            }
            final var userData = getUserData(userId);
            // Always call subtype picker, because subtype picker is a superset of input method
            // picker.
            final int displayId = (userData.mCurClient != null)
                    ? userData.mCurClient.mSelfReportedDisplayId : DEFAULT_DISPLAY;
            mHandler.post(() -> {
                synchronized (ImfLock.class) {
                    showInputMethodPickerLocked(auxiliarySubtypeMode, displayId, userId);
                }
            });
        }
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void showInputMethodPickerFromSystem(int auxiliarySubtypeMode, int displayId) {
        // Always call subtype picker, because subtype picker is a superset of input method
        // picker.
        mHandler.post(() -> {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                showInputMethodPickerLocked(auxiliarySubtypeMode, displayId, userId);
            }
        });
    }

    /**
     * A test API for CTS to make sure that the input method menu is showing.
     */
    @IInputMethodManagerImpl.PermissionVerified(Manifest.permission.TEST_INPUT_METHOD)
    public boolean isInputMethodPickerShownForTest() {
        synchronized (ImfLock.class) {
            return Flags.imeSwitcherRevamp()
                    ? mMenuControllerNew.isShowing()
                    : mMenuController.isisInputMethodPickerShownForTestLocked();
        }
    }

    /**
     * Gets the list of Input Method Switcher Menu items and the index of the selected item.
     *
     * @param items                the list of input method and subtype items.
     * @param selectedImeId        the ID of the selected input method.
     * @param selectedSubtypeIndex the index of the selected subtype in the input method's array of
     *                             subtypes, or {@link InputMethodUtils#NOT_A_SUBTYPE_INDEX} if no
     *                             subtype is selected.
     * @param userId               the ID of the user for which to get the menu items.
     * @return the list of menu items, and the index of the selected item,
     * or {@code -1} if no item is selected.
     */
    @GuardedBy("ImfLock.class")
    @NonNull
    private Pair<List<MenuItem>, Integer> getInputMethodPickerItems(
            @NonNull List<ImeSubtypeListItem> items, @Nullable String selectedImeId,
            int selectedSubtypeIndex, @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        final var settings = InputMethodSettingsRepository.get(userId);

        if (selectedSubtypeIndex == NOT_A_SUBTYPE_INDEX) {
            // TODO(b/351124299): Check if this fallback logic is still necessary.
            final var curSubtype = bindingController.getCurrentInputMethodSubtype();
            if (curSubtype != null) {
                final var curMethodId = bindingController.getSelectedMethodId();
                final var curImi = settings.getMethodMap().get(curMethodId);
                selectedSubtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(
                        curImi, curSubtype.hashCode());
            }
        }

        // No item is selected by default. When we have a list of explicitly enabled
        // subtypes, the implicit subtype is no longer listed. If the implicit one
        // is still selected, no items will be shown as selected.
        int selectedIndex = -1;
        String prevImeId = null;
        final var menuItems = new ArrayList<MenuItem>();
        for (int i = 0; i < items.size(); i++) {
            final var item = items.get(i);
            final var imeId = item.mImi.getId();
            if (imeId.equals(selectedImeId)) {
                final int subtypeIndex = item.mSubtypeIndex;
                // Check if this is the selected IME-subtype pair.
                if ((subtypeIndex == 0 && selectedSubtypeIndex == NOT_A_SUBTYPE_INDEX)
                        || subtypeIndex == NOT_A_SUBTYPE_INDEX
                        || subtypeIndex == selectedSubtypeIndex) {
                    selectedIndex = i;
                }
            }
            final boolean hasHeader = !imeId.equals(prevImeId);
            final boolean hasDivider = hasHeader && prevImeId != null;
            prevImeId = imeId;
            menuItems.add(new MenuItem(item.mImeName, item.mSubtypeName, item.mImi,
                    item.mSubtypeIndex, hasHeader, hasDivider));
        }

        return new Pair<>(menuItems, selectedIndex);
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.WRITE_SECURE_SETTINGS})
    @Override
    public void onImeSwitchButtonClickFromSystem(int displayId) {
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
            final var userData = getUserData(userId);

            onImeSwitchButtonClickLocked(displayId, userData);
        }
    }

    /**
     * Handles a click on the IME switch button. Depending on the number of enabled IME subtypes,
     * this will either switch to the next IME/subtype, or show the input method picker dialog.
     *
     * @param displayId The ID of the display where the input method picker dialog should be shown.
     * @param userData  The data of the user for which to switch IMEs or show the picker dialog.
     */
    @BinderThread
    @GuardedBy("ImfLock.class")
    private void onImeSwitchButtonClickLocked(int displayId, @NonNull UserData userData) {
        final int userId = userData.mUserId;
        final var settings = InputMethodSettingsRepository.get(userId);
        if (hasMultipleSubtypesForSwitcher(true /* nonAuxOnly */, settings)) {
            switchToNextInputMethodLocked(false /* onlyCurrentIme */, userData);
        } else {
            showInputMethodPickerFromSystem(
                    InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES, displayId);
        }
    }

    @NonNull
    private static IllegalArgumentException getExceptionForUnknownImeId(
            @Nullable String imeId) {
        return new IllegalArgumentException("Unknown id: " + imeId);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void setInputMethodAndSubtypeLocked(String id, @Nullable InputMethodSubtype subtype,
            @NonNull UserData userData) {
        final int callingUid = Binder.getCallingUid();
        final int userId = userData.mUserId;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = settings.getMethodMap().get(id);
        if (imi == null || !canCallerAccessInputMethod(
                imi.getPackageName(), callingUid, userId, settings)) {
            throw getExceptionForUnknownImeId(id);
        }
        final int subtypeIndex = subtype != null
                ? SubtypeUtils.getSubtypeIndexFromHashCode(imi, subtype.hashCode())
                : NOT_A_SUBTYPE_INDEX;
        setInputMethodWithSubtypeIndexLocked(id, subtypeIndex, userId);
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean switchToPreviousInputMethodLocked(@NonNull UserData userData) {
        final int userId = userData.mUserId;
        final var bindingController = userData.mBindingController;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final Pair<String, String> lastIme = settings.getLastInputMethodAndSubtype();
        final InputMethodInfo lastImi;
        if (lastIme != null) {
            lastImi = settings.getMethodMap().get(lastIme.first);
        } else {
            lastImi = null;
        }
        final var currentSubtype = bindingController.getCurrentSubtype();
        String targetLastImiId = null;
        int subtypeIndex = NOT_A_SUBTYPE_INDEX;
        if (lastIme != null && lastImi != null) {
            final boolean imiIdIsSame = lastImi.getId().equals(
                    bindingController.getSelectedMethodId());
            final int lastSubtypeHash = Integer.parseInt(lastIme.second);
            final int currentSubtypeHash = currentSubtype == null ? NOT_A_SUBTYPE_INDEX
                    : currentSubtype.hashCode();
            // If the last IME is the same as the current IME and the last subtype is not
            // defined, there is no need to switch to the last IME.
            if (!imiIdIsSame || lastSubtypeHash != currentSubtypeHash) {
                targetLastImiId = lastIme.first;
                subtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(lastImi, lastSubtypeHash);
            }
        }

        if (TextUtils.isEmpty(targetLastImiId)
                && !InputMethodUtils.canAddToLastInputMethod(currentSubtype)) {
            // This is a safety net. If the currentSubtype can't be added to the history
            // and the framework couldn't find the last ime, we will make the last ime be
            // the most applicable enabled keyboard subtype of the system imes.
            final List<InputMethodInfo> enabled = settings.getEnabledInputMethodList();
            final int enabledCount = enabled.size();
            final String locale;
            if (currentSubtype != null
                    && !TextUtils.isEmpty(currentSubtype.getLocale())) {
                locale = currentSubtype.getLocale();
            } else {
                locale = SystemLocaleWrapper.get(userId).get(0).toString();
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
                        subtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(imi,
                                keyboardSubtype.hashCode());
                        if (keyboardSubtype.getLocale().equals(locale)) {
                            break;
                        }
                    }
                }
            }
        }

        if (!TextUtils.isEmpty(targetLastImiId)) {
            if (DEBUG) {
                Slog.d(TAG, "Switch to: " + lastImi.getId() + ", " + lastIme.second
                        + ", from: " + bindingController.getSelectedMethodId() + ", "
                        + subtypeIndex);
            }
            setInputMethodWithSubtypeIndexLocked(targetLastImiId, subtypeIndex, userId);
            return true;
        } else {
            return false;
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean switchToNextInputMethodLocked(boolean onlyCurrentIme,
            @NonNull UserData userData) {
        final var bindingController = userData.mBindingController;
        final var currentImi = bindingController.getSelectedMethod();
        if (currentImi == null) {
            return false;
        }
        final ImeSubtypeListItem nextSubtype = userData.mSwitchingController
                .getNextInputMethodLocked(onlyCurrentIme, currentImi,
                        bindingController.getCurrentSubtype(),
                        MODE_AUTO, true /* forward */);
        if (nextSubtype == null) {
            return false;
        }
        setInputMethodWithSubtypeIndexLocked(nextSubtype.mImi.getId(), nextSubtype.mSubtypeIndex,
                userData.mUserId);
        return true;
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private boolean shouldOfferSwitchingToNextInputMethodLocked(@NonNull UserData userData) {
        final var bindingController = userData.mBindingController;
        final var currentImi = bindingController.getSelectedMethod();
        if (currentImi == null) {
            return false;
        }
        final ImeSubtypeListItem nextSubtype = userData.mSwitchingController
                .getNextInputMethodLocked(false /* onlyCurrentIme */, currentImi,
                        bindingController.getCurrentSubtype(),
                        MODE_AUTO, true /* forward */);
        return nextSubtype != null;
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
        final var userData = getUserData(userId);
        synchronized (ImfLock.class) {
            if (!mSystemReady) {
                return;
            }

            final var additionalSubtypeMap = AdditionalSubtypeMapRepository.get(userId);
            final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
            final var newAdditionalSubtypeMap = settings.getNewAdditionalSubtypeMap(
                    imiId, toBeAdded, additionalSubtypeMap, mPackageManagerInternal, callingUid);
            if (additionalSubtypeMap != newAdditionalSubtypeMap) {
                AdditionalSubtypeMapRepository.putAndSave(userId, newAdditionalSubtypeMap,
                        settings.getMethodMap());
                final long ident = Binder.clearCallingIdentity();
                try {
                    final var methodMap = userData.mRawInputMethodMap.get().toInputMethodMap(
                            AdditionalSubtypeMapRepository.get(userId), DirectBootAwareness.AUTO,
                            userData.mIsUnlockingOrUnlocked.get());
                    final var newSettings = InputMethodSettings.create(methodMap, userId);
                    InputMethodSettingsRepository.put(userId, newSettings);
                    postInputMethodSettingUpdatedLocked(false /* resetDefaultEnabledIme */, userId);
                } finally {
                    Binder.restoreCallingIdentity(ident);
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
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.setEnabledInputMethodSubtypes(imeId, subtypeHashCodes)) {
                    return;
                }
                // To avoid unnecessary "updateInputMethodsFromSettingsLocked" from happening.
                final var userData = getUserData(userId);
                userData.mLastEnabledInputMethodsStr = settings.getEnabledInputMethodsStr();
                updateInputMethodsFromSettingsLocked(false /* enabledChanged */, userId);
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
        final int callingUid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getCallingUserId();
        return Binder.withCleanCallingIdentity(() -> {
            final int curTokenDisplayId;
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdLocked(callingUserId);
                if (!canInteractWithImeLocked(callingUid, client,
                        "getInputMethodWindowVisibleHeight", null /* statsToken */, userId)) {
                    return 0;
                }
                final var bindingController = getInputMethodBindingController(userId);
                // This should probably use the caller's display id, but because this is unsupported
                // and maintained only for compatibility, there's no point in fixing it.
                curTokenDisplayId = bindingController.getCurTokenDisplayId();
            }
            return mWindowManagerInternal.getInputMethodWindowVisibleHeight(curTokenDisplayId);
        });
    }

    @IInputMethodManagerImpl.PermissionVerified(allOf = {
            Manifest.permission.INTERACT_ACROSS_USERS_FULL,
            Manifest.permission.INTERNAL_SYSTEM_WINDOW})
    @Override
    public void removeImeSurface(int displayId) {
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
        final var bindingController = getInputMethodBindingController(mCurrentImeUserId);
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

    private static boolean isFingerDevice(InputDevice inputDevice) {
        return inputDevice.supportsSource(InputDevice.SOURCE_TOUCHSCREEN);
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
        final int uid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(uid);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canInteractWithImeLocked(uid, client, "addVirtualStylusIdForTestSession",
                    null /* statsToken */, userId)) {
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
        final int uid = Binder.getCallingUid();
        final int callingUserId = UserHandle.getUserId(uid);
        synchronized (ImfLock.class) {
            final int userId = resolveImeUserIdLocked(callingUserId);
            if (!canInteractWithImeLocked(uid, client, "setStylusWindowIdleTimeoutForTest",
                    null /* statsToken */, userId)) {
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

    // TODO(b/356239178): Make dump proto multi-user aware.
    private void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (ImfLock.class) {
            final int userId = mCurrentImeUserId;
            final var userData = getUserData(userId);
            final var bindingController = userData.mBindingController;
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            final long token = proto.start(fieldId);
            proto.write(CUR_METHOD_ID, bindingController.getSelectedMethodId());
            proto.write(CUR_SEQ, bindingController.getSequenceNumber());
            proto.write(CUR_CLIENT, Objects.toString(userData.mCurClient));
            userData.mImeBindingState.dumpDebug(proto, mWindowManagerInternal);
            proto.write(LAST_IME_TARGET_WINDOW_NAME, mWindowManagerInternal.getWindowName(
                    visibilityStateComputer.getLastImeTargetWindow()));
            proto.write(CUR_FOCUSED_WINDOW_SOFT_INPUT_MODE, InputMethodDebug.softInputModeToString(
                    userData.mImeBindingState.mFocusedWindowSoftInputMode));
            if (userData.mCurEditorInfo != null) {
                userData.mCurEditorInfo.dumpDebug(proto, CUR_ATTRIBUTE);
            }
            proto.write(CUR_ID, bindingController.getCurId());
            visibilityStateComputer.dumpDebug(proto, fieldId);
            proto.write(IN_FULLSCREEN_MODE, userData.mInFullscreenMode);
            proto.write(CUR_TOKEN, Objects.toString(bindingController.getCurToken()));
            proto.write(CUR_TOKEN_DISPLAY_ID, bindingController.getCurTokenDisplayId());
            proto.write(SYSTEM_READY, mSystemReady);
            proto.write(HAVE_CONNECTION, bindingController.hasMainConnection());
            proto.write(BOUND_TO_METHOD, userData.mBoundToMethod);
            proto.write(IS_INTERACTIVE, mIsInteractive);
            proto.write(BACK_DISPOSITION, bindingController.getBackDisposition());
            proto.write(IME_WINDOW_VISIBILITY, bindingController.getImeWindowVis());
            if (!Flags.imeSwitcherRevamp()) {
                proto.write(SHOW_IME_WITH_HARD_KEYBOARD,
                        mMenuController.getShowImeWithHardKeyboard());
            }
            proto.write(CONCURRENT_MULTI_USER_MODE_ENABLED, mConcurrentMultiUserModeEnabled);
            proto.end(token);
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void notifyUserActionLocked(@NonNull UserData userData) {
        if (DEBUG) {
            Slog.d(TAG, "Got the notification of a user action.");
        }
        final var bindingController = userData.mBindingController;
        final InputMethodInfo imi = bindingController.getSelectedMethod();
        if (imi != null) {
            userData.mSwitchingController.onUserActionLocked(imi,
                    bindingController.getCurrentSubtype());
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void applyImeVisibilityLocked(IBinder windowToken, boolean setVisible,
            @NonNull ImeTracker.Token statsToken, @NonNull UserData userData) {
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.applyImeVisibilityLocked");
            final int userId = userData.mUserId;
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            final IBinder requestToken = visibilityStateComputer.getWindowTokenFrom(
                    windowToken, userId);
            mVisibilityApplier.applyImeVisibility(requestToken, statsToken,
                    setVisible ? STATE_SHOW_IME : STATE_HIDE_IME,
                    SoftInputShowHideReason.NOT_SET /* ignore reason */, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void resetStylusHandwritingLocked(int requestId) {
        final OptionalInt curRequest = mHwController.getCurrentRequestId();
        if (!curRequest.isPresent() || curRequest.getAsInt() != requestId) {
            Slog.w(TAG, "IME requested to finish handwriting with a mismatched requestId: "
                    + requestId);
        }
        removeVirtualStylusIdForTestSessionLocked();
        scheduleResetStylusHandwriting();
    }

    @GuardedBy("ImfLock.class")
    private void setInputMethodWithSubtypeIndexLocked(String id, int subtypeIndex,
            @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (settings.getMethodMap().get(id) != null
                && settings.getEnabledInputMethodListWithFilter(
                        (info) -> info.getId().equals(id)).isEmpty()) {
            throw new IllegalStateException("Requested IME is not enabled: " + id);
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            setInputMethodLocked(id, subtypeIndex, userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Called right after {@link IInputMethod#showSoftInput} or {@link IInputMethod#hideSoftInput}.
     */
    @GuardedBy("ImfLock.class")
    void onShowHideSoftInputRequested(boolean show, IBinder requestImeToken,
            @SoftInputShowHideReason int reason, @Nullable ImeTracker.Token statsToken,
            @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final var visibilityStateComputer = userData.mVisibilityStateComputer;
        final IBinder requestToken = visibilityStateComputer.getWindowTokenFrom(requestImeToken,
                userId);
        final var bindingController = userData.mBindingController;
        final WindowManagerInternal.ImeTargetInfo info =
                mWindowManagerInternal.onToggleImeRequested(
                        show, userData.mImeBindingState.mFocusedWindow, requestToken,
                        bindingController.getCurTokenDisplayId());
        mSoftInputShowHideHistory.addEntry(new SoftInputShowHideHistory.Entry(
                userData.mImeBindingState.mFocusedWindowClient,
                userData.mImeBindingState.mFocusedWindowEditorInfo,
                info.focusedWindowName, userData.mImeBindingState.mFocusedWindowSoftInputMode,
                reason, userData.mInFullscreenMode, info.requestWindowName,
                info.imeControlTargetName, info.imeLayerTargetName, info.imeSurfaceParentName,
                userId));

        if (statsToken != null) {
            mImeTrackerService.onImmsUpdate(statsToken, info.requestWindowName);
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void hideMySoftInputLocked(@NonNull ImeTracker.Token statsToken,
            @InputMethodManager.HideFlags int flags, @SoftInputShowHideReason int reason,
            @NonNull UserData userData) {
        final int userId = userData.mUserId;
        if (Flags.refactorInsetsController()) {
            userData.mCurClient.mClient.setImeVisibility(false, statsToken);
            // TODO we will loose the flags here
            setImeVisibilityOnFocusedWindowClient(false, userData, statsToken);
        } else {
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            hideCurrentInputLocked(visibilityStateComputer.getLastImeTargetWindow(),
                    statsToken, flags, null /* resultReceiver */, reason, userId);
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void showMySoftInputLocked(@NonNull ImeTracker.Token statsToken,
            @InputMethodManager.ShowFlags int flags, @SoftInputShowHideReason int reason,
            @NonNull UserData userData) {
        final int userId = userData.mUserId;
        if (Flags.refactorInsetsController()) {
            userData.mCurClient.mClient.setImeVisibility(true, statsToken);
            setImeVisibilityOnFocusedWindowClient(true, userData, statsToken);
        } else {
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            showCurrentInputLocked(visibilityStateComputer.getLastImeTargetWindow(),
                    statsToken, flags, MotionEvent.TOOL_TYPE_UNKNOWN,
                    null /* resultReceiver */, reason, userId);
        }
    }

    @GuardedBy("ImfLock.class")
    @VisibleForTesting
    DefaultImeVisibilityApplier getVisibilityApplierLocked() {
        return mVisibilityApplier;
    }

    @GuardedBy("ImfLock.class")
    void onApplyImeVisibilityFromComputerLocked(IBinder windowToken,
            @NonNull ImeTracker.Token statsToken, @NonNull ImeVisibilityResult result,
            @UserIdInt int userId) {
        mVisibilityApplier.applyImeVisibility(windowToken, statsToken, result.getState(),
                result.getReason(), userId);
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionLocked(SessionState session, @NonNull UserData userData) {
        if (userData.mEnabledSession != session) {
            if (userData.mEnabledSession != null && userData.mEnabledSession.mSession != null) {
                if (DEBUG) Slog.v(TAG, "Disabling: " + userData.mEnabledSession);
                userData.mEnabledSession.mMethod.setSessionEnabled(
                        userData.mEnabledSession.mSession, false);
            }
            userData.mEnabledSession = session;
            if (userData.mEnabledSession != null && userData.mEnabledSession.mSession != null) {
                if (DEBUG) Slog.v(TAG, "Enabling: " + userData.mEnabledSession);
                userData.mEnabledSession.mMethod.setSessionEnabled(
                        userData.mEnabledSession.mSession, true);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    void setEnabledSessionForAccessibilityLocked(
            SparseArray<AccessibilitySessionState> accessibilitySessions,
            @NonNull UserData userData) {
        // mEnabledAccessibilitySessions could the same object as accessibilitySessions.
        SparseArray<IAccessibilityInputMethodSession> disabledSessions = new SparseArray<>();
        for (int i = 0; i < userData.mEnabledAccessibilitySessions.size(); i++) {
            if (!accessibilitySessions.contains(userData.mEnabledAccessibilitySessions.keyAt(i))) {
                AccessibilitySessionState sessionState =
                        userData.mEnabledAccessibilitySessions.valueAt(i);
                if (sessionState != null) {
                    disabledSessions.append(userData.mEnabledAccessibilitySessions.keyAt(i),
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
            if (!userData.mEnabledAccessibilitySessions.contains(accessibilitySessions.keyAt(i))) {
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
        userData.mEnabledAccessibilitySessions = accessibilitySessions;
    }

    @GuardedBy("ImfLock.class")
    private void showInputMethodPickerLocked(int auxiliarySubtypeMode, int displayId,
            @UserIdInt int userId) {
        final boolean showAuxSubtypes;
        switch (auxiliarySubtypeMode) {
            // This is undocumented so far, but IMM#showInputMethodPicker() has been
            // implemented so that auxiliary subtypes will be excluded when the soft
            // keyboard is invisible.
            case InputMethodManager.SHOW_IM_PICKER_MODE_AUTO -> {
                final var userData = getUserData(userId);
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                showAuxSubtypes = visibilityStateComputer.isInputShown();
            }
            case InputMethodManager.SHOW_IM_PICKER_MODE_INCLUDE_AUXILIARY_SUBTYPES ->
                    showAuxSubtypes = true;
            case InputMethodManager.SHOW_IM_PICKER_MODE_EXCLUDE_AUXILIARY_SUBTYPES ->
                    showAuxSubtypes = false;
            default -> {
                Slog.e(TAG, "Unknown subtype picker mode=" + auxiliarySubtypeMode);
                return;
            }
        }
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final boolean isScreenLocked = mWindowManagerInternal.isKeyguardLocked()
                && mWindowManagerInternal.isKeyguardSecure(userId);
        final String lastInputMethodId = settings.getSelectedInputMethod();
        final int lastInputMethodSubtypeIndex =
                settings.getSelectedInputMethodSubtypeIndex(lastInputMethodId);

        final List<ImeSubtypeListItem> imList = InputMethodSubtypeSwitchingController
                .getSortedInputMethodAndSubtypeList(
                        showAuxSubtypes, isScreenLocked, true /* forImeMenu */,
                        mContext, settings);
        if (imList.isEmpty()) {
            Slog.w(TAG, "Show switching menu failed, imList is empty,"
                    + " showAuxSubtypes: " + showAuxSubtypes
                    + " isScreenLocked: " + isScreenLocked
                    + " userId: " + userId);
            return;
        }

        if (Flags.imeSwitcherRevamp()) {
            if (DEBUG) {
                Slog.v(TAG, "Show IME switcher menu,"
                        + " showAuxSubtypes=" + showAuxSubtypes
                        + " displayId=" + displayId
                        + " preferredInputMethodId=" + lastInputMethodId
                        + " preferredInputMethodSubtypeIndex=" + lastInputMethodSubtypeIndex);
            }

            final var itemsAndIndex = getInputMethodPickerItems(imList,
                    lastInputMethodId, lastInputMethodSubtypeIndex, userId);
            final var menuItems = itemsAndIndex.first;
            final int selectedIndex = itemsAndIndex.second;

            if (selectedIndex == -1) {
                Slog.w(TAG, "Switching menu shown with no item selected"
                        + ", IME id: " + lastInputMethodId
                        + ", subtype index: " + lastInputMethodSubtypeIndex);
            }

            mMenuControllerNew.show(menuItems, selectedIndex, displayId, userId);
        } else {
            mMenuController.showInputMethodMenuLocked(showAuxSubtypes, displayId,
                    lastInputMethodId, lastInputMethodSubtypeIndex, imList, userId);
        }
    }

    @SuppressWarnings("unchecked")
    @UiThread
    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_HIDE_INPUT_METHOD: {
                @SoftInputShowHideReason final int reason = msg.arg1;
                final int originatingDisplayId = msg.arg2;
                synchronized (ImfLock.class) {
                    final int userId = resolveImeUserIdFromDisplayIdLocked(originatingDisplayId);
                    final var userData = getUserData(userId);
                    if (Flags.refactorInsetsController()) {
                        setImeVisibilityOnFocusedWindowClient(false, userData,
                                null /* TODO(b329229469) check statsToken */);
                    } else {

                        hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                                0 /* flags */, reason, userId);
                    }
                }
                return true;
            }
            case MSG_REMOVE_IME_SURFACE: {
                synchronized (ImfLock.class) {
                    // TODO(b/305849394): Needs to figure out what to do where for background users.
                    final int userId = mCurrentImeUserId;
                    final var userData = getUserData(userId);
                    try {
                        if (userData.mEnabledSession != null
                                && userData.mEnabledSession.mSession != null
                                && !isShowRequestedForCurrentWindow(userId)) {
                            userData.mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException e) {
                    }
                }
                return true;
            }
            case MSG_REMOVE_IME_SURFACE_FROM_WINDOW: {
                IBinder windowToken = (IBinder) msg.obj;
                synchronized (ImfLock.class) {
                    final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                    final var userData = getUserData(userId);
                    try {
                        if (windowToken == userData.mImeBindingState.mFocusedWindow
                                && userData.mEnabledSession != null
                                && userData.mEnabledSession.mSession != null) {
                            userData.mEnabledSession.mSession.removeImeSurface();
                        }
                    } catch (RemoteException e) {
                    }
                }
                return true;
            }

            // ---------------------------------------------------------

            case MSG_SET_INTERACTIVE:
                handleSetInteractive(msg.arg1 != 0);
                return true;

            // --------------------------------------------------------------
            case MSG_HARD_KEYBOARD_SWITCH_CHANGED:
                if (!Flags.imeSwitcherRevamp()) {
                    mMenuController.handleHardKeyboardStatusChange(msg.arg1 == 1);
                }
                synchronized (ImfLock.class) {
                    sendOnNavButtonFlagsChangedToAllImesLocked();
                }
                return true;
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
                    final var bindingController =
                            getInputMethodBindingController(mCurrentImeUserId);
                    if (bindingController.supportsStylusHandwriting()
                            && bindingController.getCurMethod() != null
                            && hasSupportedStylusLocked()) {
                        Slog.d(TAG, "Initializing Handwriting Spy");
                        mHwController.initializeHandwritingSpy(
                                bindingController.getCurTokenDisplayId());
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
                final var handwritingRequest = (HandwritingRequest) msg.obj;
                synchronized (ImfLock.class) {
                    final var userData = handwritingRequest.userData;
                    final var bindingController = userData.mBindingController;
                    IInputMethodInvoker curMethod = bindingController.getCurMethod();
                    if (curMethod == null || userData.mImeBindingState.mFocusedWindow == null) {
                        return true;
                    }
                    final HandwritingModeController.HandwritingSession session =
                            mHwController.startHandwritingSession(
                                    handwritingRequest.requestId,
                                    handwritingRequest.pid,
                                    bindingController.getCurMethodUid(),
                                    userData.mImeBindingState.mFocusedWindow);
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

    private record HandwritingRequest(int requestId, int pid, @NonNull UserData userData) { }

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void onStylusHandwritingReadyLocked(int requestId, int pid,
            @NonNull UserData userData) {
        mHandler.obtainMessage(MSG_START_HANDWRITING,
                new HandwritingRequest(requestId, pid, userData)).sendToTarget();
    }

    private void handleSetInteractive(final boolean interactive) {
        synchronized (ImfLock.class) {
            // TODO(b/305849394): Support multiple IMEs.
            final int userId = mCurrentImeUserId;
            final var userData = getUserData(userId);
            final var bindingController = userData.mBindingController;
            mIsInteractive = interactive;
            updateSystemUiLocked(
                    interactive ? bindingController.getImeWindowVis() : 0,
                    bindingController.getBackDisposition(), userId);
            // Inform the current client of the change in active status
            if (userData.mCurClient == null || userData.mCurClient.mClient == null) {
                return;
            }
            if (mImePlatformCompatUtils.shouldUseSetInteractiveProtocol(
                    bindingController.getCurMethodUid())) {
                // Handle IME visibility when interactive changed before finishing the input to
                // ensure we preserve the last state as possible.
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                final ImeVisibilityResult imeVisRes = visibilityStateComputer.onInteractiveChanged(
                        userData.mImeBindingState.mFocusedWindow, interactive);
                if (imeVisRes != null) {
                    // Pass in a null statsToken as the IME snapshot is not tracked by ImeTracker.
                    mVisibilityApplier.applyImeVisibility(userData.mImeBindingState.mFocusedWindow,
                            null /* statsToken */, imeVisRes.getState(), imeVisRes.getReason(),
                            userId);
                }
                // Eligible IME processes use new "setInteractive" protocol.
                userData.mCurClient.mClient.setInteractive(mIsInteractive,
                        userData.mInFullscreenMode);
            } else {
                // Legacy IME processes continue using legacy "setActive" protocol.
                userData.mCurClient.mClient.setActive(mIsInteractive, userData.mInFullscreenMode);
            }
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean chooseNewDefaultIMELocked(@UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final InputMethodInfo imi = InputMethodInfoUtils.getMostApplicableDefaultIME(
                settings.getEnabledInputMethodList());
        if (imi != null) {
            if (DEBUG) {
                Slog.d(TAG, "New default IME was selected: " + imi.getId());
            }
            resetSelectedInputMethodAndSubtypeLocked(imi.getId(), userId);
            return true;
        }

        return false;
    }

    @NonNull
    static RawInputMethodMap queryRawInputMethodServiceMap(Context context, @UserIdInt int userId) {
        final Context userAwareContext = context.getUserId() == userId
                ? context
                : context.createContextAsUser(UserHandle.of(userId), 0 /* flags */);

        final int flags = PackageManager.GET_META_DATA
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE;

        // Beware that package visibility filtering will be enforced based on the effective calling
        // identity (Binder.getCallingUid()), but our use case always expect Binder.getCallingUid()
        // to return Process.SYSTEM_UID here. The actual filtering is implemented separately with
        // canCallerAccessInputMethod().
        // TODO(b/343108534): Use PackageManagerInternal#queryIntentServices() to pass SYSTEM_UID.
        final List<ResolveInfo> services = userAwareContext.getPackageManager().queryIntentServices(
                new Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.ResolveInfoFlags.of(flags));

        // Note: This is a temporary solution for Bug 261723412.
        // TODO(b/339761278): Remove this workaround after switching to InputMethodInfoSafeList.
        final List<String> enabledInputMethodList =
                InputMethodUtils.getEnabledInputMethodIdsForFiltering(context, userId);

        return filterInputMethodServices(enabledInputMethodList, userAwareContext, services);
    }

    @NonNull
    static RawInputMethodMap filterInputMethodServices(
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
                        Collections.emptyList());
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
        return RawInputMethodMap.of(methodMap);
    }

    @GuardedBy("ImfLock.class")
    void postInputMethodSettingUpdatedLocked(boolean resetDefaultEnabledIme,
            @UserIdInt int userId) {
        if (DEBUG) {
            Slog.d(TAG, "--- re-buildInputMethodList reset = " + resetDefaultEnabledIme
                    + " \n ------ caller=" + Debug.getCallers(10));
        }
        if (!mSystemReady) {
            Slog.e(TAG, "buildInputMethodListLocked is not allowed until system is ready");
            return;
        }

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

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
                resetSelectedInputMethodAndSubtypeLocked("", userId);
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
                setInputMethodEnabledLocked(imi.getId(), true, userId);
            }
        }

        final String defaultImiId = settings.getSelectedInputMethod();
        if (!TextUtils.isEmpty(defaultImiId)) {
            if (!settings.getMethodMap().containsKey(defaultImiId)) {
                Slog.w(TAG, "Default IME is uninstalled. Choose new default IME.");
                if (chooseNewDefaultIMELocked(userId)) {
                    updateInputMethodsFromSettingsLocked(true, userId);
                }
            } else {
                // Double check that the default IME is certainly enabled.
                setInputMethodEnabledLocked(defaultImiId, true, userId);
            }
        }

        updateDefaultVoiceImeIfNeededLocked(userId);

        final var userData = getUserData(userId);
        userData.mSwitchingController.resetCircularListLocked(mContext, settings);
        userData.mHardwareKeyboardShortcutController.update(settings);

        sendOnNavButtonFlagsChangedLocked(userData);

        // Notify InputMethodListListeners of the new installed InputMethods.
        final List<InputMethodInfo> inputMethodList = settings.getMethodList();
        mHandler.obtainMessage(MSG_DISPATCH_ON_INPUT_METHOD_LIST_UPDATED,
                userId, 0 /* unused */, inputMethodList).sendToTarget();
    }

    @GuardedBy("ImfLock.class")
    void sendOnNavButtonFlagsChangedToAllImesLocked() {
        for (int userId : mUserManagerInternal.getUserIds()) {
            sendOnNavButtonFlagsChangedLocked(getUserData(userId));
        }
    }

    @GuardedBy("ImfLock.class")
    void sendOnNavButtonFlagsChangedLocked(@NonNull UserData userData) {
        final var bindingController = userData.mBindingController;
        final IInputMethodInvoker curMethod = bindingController.getCurMethod();
        if (curMethod == null) {
            // No need to send the data if the IME is not yet bound.
            return;
        }
        curMethod.onNavButtonFlagsChanged(getInputMethodNavButtonFlagsLocked(userData));
    }

    @WorkerThread
    private void onUpdateResourceOverlay(@UserIdInt int userId) {
        final int profileParentId = mUserManagerInternal.getProfileParentId(userId);
        final boolean value =
                InputMethodDrawsNavBarResourceMonitor.evaluate(mContext, profileParentId);
        final var profileUserIds = mUserManagerInternal.getProfileIds(profileParentId, false);
        final ArrayList<UserData> updatedUsers = new ArrayList<>();
        for (int profileUserId : profileUserIds) {
            final var userData = getUserData(profileUserId);
            userData.mImeDrawsNavBar.set(value);
            updatedUsers.add(userData);
        }
        synchronized (ImfLock.class) {
            updatedUsers.forEach(this::sendOnNavButtonFlagsChangedLocked);
        }
    }

    @GuardedBy("ImfLock.class")
    private void updateDefaultVoiceImeIfNeededLocked(@UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
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
            Slog.i(TAG, "Enabling the default Voice IME:" + newSystemVoiceIme
                    + " userId:" + userId);
        }
        setInputMethodEnabledLocked(newSystemVoiceIme.getId(), true, userId);
        settings.putDefaultVoiceInputMethod(newSystemVoiceIme.getId());
    }

    // ----------------------------------------------------------------------

    /**
     * Enable or disable the given IME by updating {@link Settings.Secure#ENABLED_INPUT_METHODS}.
     *
     * @param id      ID of the IME is to be manipulated. It is OK to pass IME ID that is currently
     *                not recognized by the system
     * @param enabled {@code true} if {@code id} needs to be enabled
     * @param userId  the user ID to be updated
     * @return {@code true} if the IME was previously enabled
     */
    @GuardedBy("ImfLock.class")
    private boolean setInputMethodEnabledLocked(String id, boolean enabled, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
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
                final var bindingController = getInputMethodBindingController(userId);
                if (bindingController.getDeviceIdToShowIme() == DEVICE_ID_DEFAULT) {
                    // Disabled input method is currently selected, switch to another one.
                    final String selId = settings.getSelectedInputMethod();
                    if (id.equals(selId) && !chooseNewDefaultIMELocked(userId)) {
                        Slog.i(TAG, "Can't find new IME, unsetting the current input method.");
                        resetSelectedInputMethodAndSubtypeLocked("", userId);
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
    private void setSelectedInputMethodAndSubtypeLocked(InputMethodInfo imi, int subtypeIndex,
            boolean setSubtypeOnly, @UserIdInt int userId) {
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        final var bindingController = getInputMethodBindingController(userId);
        settings.saveCurrentInputMethodAndSubtypeToHistory(bindingController.getSelectedMethodId(),
                bindingController.getCurrentSubtype());

        // Set Subtype here
        final int newSubtypeHashcode;
        final InputMethodSubtype newSubtype;
        if (imi == null || subtypeIndex < 0) {
            newSubtypeHashcode = INVALID_SUBTYPE_HASHCODE;
            newSubtype = null;
        } else {
            if (subtypeIndex < imi.getSubtypeCount()) {
                InputMethodSubtype subtype = imi.getSubtypeAt(subtypeIndex);
                newSubtypeHashcode = subtype.hashCode();
                newSubtype = subtype;
            } else {
                // TODO(b/347093491): Probably this should be determined from the new subtype.
                newSubtypeHashcode = INVALID_SUBTYPE_HASHCODE;
                // If the subtype is not specified, choose the most applicable one
                // TODO(b/347083680): The method below has questionable behaviors.
                newSubtype = bindingController.getCurrentInputMethodSubtype();
            }
        }
        settings.putSelectedSubtype(newSubtypeHashcode);
        bindingController.setCurrentSubtype(newSubtype);
        notifyInputMethodSubtypeChangedLocked(settings.getUserId(), imi, newSubtype);

        if (!setSubtypeOnly) {
            // Set InputMethod here
            settings.putSelectedInputMethod(imi != null ? imi.getId() : "");
        }

        if (Flags.imeSwitcherRevamp()) {
            getUserData(userId).mSwitchingController.onInputMethodSubtypeChanged();
        }
    }

    @GuardedBy("ImfLock.class")
    private void resetSelectedInputMethodAndSubtypeLocked(String newDefaultIme,
            @UserIdInt int userId) {
        final var bindingController = getInputMethodBindingController(userId);
        bindingController.setDisplayIdToShowIme(INVALID_DISPLAY);
        bindingController.setDeviceIdToShowIme(DEVICE_ID_DEFAULT);

        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        settings.putSelectedDefaultDeviceInputMethod(null);

        InputMethodInfo imi = settings.getMethodMap().get(newDefaultIme);
        int lastSubtypeIndex = NOT_A_SUBTYPE_INDEX;
        // newDefaultIme is empty when there is no candidate for the selected IME.
        if (imi != null && !TextUtils.isEmpty(newDefaultIme)) {
            String subtypeHashCode = settings.getLastSubtypeForInputMethod(newDefaultIme);
            if (subtypeHashCode != null) {
                try {
                    lastSubtypeIndex = SubtypeUtils.getSubtypeIndexFromHashCode(imi,
                            Integer.parseInt(subtypeHashCode));
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "HashCode for subtype looks broken: " + subtypeHashCode, e);
                }
            }
        }
        setSelectedInputMethodAndSubtypeLocked(imi, lastSubtypeIndex, false, userId);
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
            final var bindingController = getInputMethodBindingController(userId);
            // TODO(b/347083680): The method below has questionable behaviors.
            return bindingController.getCurrentInputMethodSubtype();
        }
    }

    @GuardedBy("ImfLock.class")
    private boolean switchToInputMethodLocked(@NonNull String imeId, int subtypeIndex,
            @UserIdInt int userId) {
        final var settings = InputMethodSettingsRepository.get(userId);
        final var enabledImes = settings.getEnabledInputMethodList();
        if (!CollectionUtils.any(enabledImes, imi -> imi.getId().equals(imeId))) {
            return false; // IME is not found or not enabled.
        }
        setInputMethodLocked(imeId, subtypeIndex, userId);
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
    private void switchKeyboardLayoutLocked(int direction, @NonNull UserData userData) {
        final int userId = userData.mUserId;
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);

        final var bindingController = userData.mBindingController;
        final InputMethodInfo currentImi = settings.getMethodMap().get(
                bindingController.getSelectedMethodId());
        if (currentImi == null) {
            return;
        }
        final var currentSubtype = bindingController.getCurrentSubtype();
        final InputMethodSubtypeHandle nextSubtypeHandle;
        if (Flags.imeSwitcherRevamp()) {
            final var nextItem = userData.mSwitchingController
                    .getNextInputMethodForHardware(
                            false /* onlyCurrentIme */, currentImi, currentSubtype, MODE_AUTO,
                            direction > 0 /* forward */);
            if (nextItem == null) {
                Slog.i(TAG, "Hardware keyboard switching shortcut,"
                        + " next input method and subtype not found");
                return;
            }

            final var nextSubtype = nextItem.mSubtypeIndex > NOT_A_SUBTYPE_INDEX
                    ? nextItem.mImi.getSubtypeAt(nextItem.mSubtypeIndex) : null;
            nextSubtypeHandle = InputMethodSubtypeHandle.of(nextItem.mImi, nextSubtype);
        } else {
            final InputMethodSubtypeHandle currentSubtypeHandle =
                    InputMethodSubtypeHandle.of(currentImi, currentSubtype);
            nextSubtypeHandle = userData.mHardwareKeyboardShortcutController.onSubtypeSwitch(
                        currentSubtypeHandle, direction > 0);
        }
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
                setInputMethodLocked(nextImi.getId(), NOT_A_SUBTYPE_INDEX, userId);
            }
            return;
        }

        for (int i = 0; i < subtypeCount; ++i) {
            if (nextSubtypeHandle.equals(
                    InputMethodSubtypeHandle.of(nextImi, nextImi.getSubtypeAt(i)))) {
                setInputMethodLocked(nextImi.getId(), i, userId);
                return;
            }
        }
    }

    private void publishLocalService() {
        LocalServices.addService(InputMethodManagerInternal.class, mInputMethodManagerInternal);
    }

    // TODO(b/352228316): Remove it once IMMIProxy is removed.
    InputMethodManagerInternal getLocalService(){
        return mInputMethodManagerInternal;
    }

    private final class LocalServiceImpl extends InputMethodManagerInternal {

        @ImfLockFree
        @Override
        public void setInteractive(boolean interactive) {
            // Do everything in handler so as not to block the caller.
            mHandler.obtainMessage(MSG_SET_INTERACTIVE, interactive ? 1 : 0, 0).sendToTarget();
        }

        @ImfLockFree
        @Override
        public void hideInputMethod(@SoftInputShowHideReason int reason,
                int originatingDisplayId) {
            mHandler.removeMessages(MSG_HIDE_INPUT_METHOD);
            mHandler.obtainMessage(MSG_HIDE_INPUT_METHOD, reason, originatingDisplayId)
                    .sendToTarget();
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodInfo> getInputMethodListAsUser(@UserIdInt int userId) {
            return getInputMethodListInternal(userId, DirectBootAwareness.AUTO, Process.SYSTEM_UID);
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodInfo> getEnabledInputMethodListAsUser(@UserIdInt int userId) {
            return getEnabledInputMethodListInternal(userId, Process.SYSTEM_UID);
        }

        @ImfLockFree
        @NonNull
        @Override
        public List<InputMethodSubtype> getEnabledInputMethodSubtypeListAsUser(
                String imiId, boolean allowsImplicitlyEnabledSubtypes, @UserIdInt int userId) {
            return getEnabledInputMethodSubtypeListInternal(imiId, allowsImplicitlyEnabledSubtypes,
                    userId, Process.SYSTEM_UID);
        }

        @Override
        public void onCreateInlineSuggestionsRequest(@UserIdInt int userId,
                InlineSuggestionsRequestInfo requestInfo, InlineSuggestionsRequestCallback cb) {
            // Get the device global touch exploration state before lock to avoid deadlock.
            final boolean touchExplorationEnabled = AccessibilityManagerInternal.get()
                    .isTouchExplorationEnabled(userId);

            synchronized (ImfLock.class) {
                getInputMethodBindingController(userId).onCreateInlineSuggestionsRequest(
                        requestInfo, cb, touchExplorationEnabled);
            }
        }

        @Override
        public boolean switchToInputMethod(@NonNull String imeId, int subtypeIndex,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                return switchToInputMethodLocked(imeId, subtypeIndex, userId);
            }
        }

        @Override
        public boolean setInputMethodEnabled(String imeId, boolean enabled, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
                if (!settings.getMethodMap().containsKey(imeId)) {
                    return false; // IME is not found.
                }
                setInputMethodEnabledLocked(imeId, enabled, userId);
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

        @ImfLockFree
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
                final var bindingController = getInputMethodBindingController(userId);
                if (displayId != bindingController.getCurTokenDisplayId()) {
                    return false;
                }
                curHostInputToken = bindingController.getCurHostInputToken();
                if (curHostInputToken == null) {
                    return false;
                }
            }
            return mInputManagerInternal.transferTouchGesture(sourceInputToken, curHostInputToken);
        }

        @Override
        public void reportImeControl(@Nullable IBinder windowToken) {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromWindowLocked(windowToken);
                final var userData = getUserData(userId);
                if (userData.mImeBindingState.mFocusedWindow != windowToken) {
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
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                final var userData = getUserData(userId);
                // Hide the IME method menu only when the IME surface parent is changed by the
                // input target changed, in case seeing the dialog dismiss flickering during
                // the next focused window starting the input connection.
                final var visibilityStateComputer = userData.mVisibilityStateComputer;
                if (visibilityStateComputer.getLastImeTargetWindow()
                        != userData.mImeBindingState.mFocusedWindow) {
                    if (Flags.imeSwitcherRevamp()) {
                        final var bindingController = getInputMethodBindingController(userId);
                        mMenuControllerNew.hide(bindingController.getCurTokenDisplayId(), userId);
                    } else {
                        mMenuController.hideInputMethodMenuLocked(userId);
                    }
                }
            }
        }

        @ImfLockFree
        @Override
        public void removeImeSurface(int displayId) {
            mHandler.obtainMessage(MSG_REMOVE_IME_SURFACE).sendToTarget();
        }

        @Override
        public void setHasVisibleImeLayeringOverlay(boolean hasVisibleOverlay, int displayId) {
            synchronized (ImfLock.class) {
                final var userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                getUserData(userId).mVisibilityStateComputer.setHasVisibleImeLayeringOverlay(
                        hasVisibleOverlay);
            }
        }

        @Override
        public void onImeInputTargetVisibilityChanged(@NonNull IBinder imeInputTarget,
                boolean visibleAndNotRemoved, int displayId) {
            synchronized (ImfLock.class) {
                final var userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                getUserData(userId).mVisibilityStateComputer.onImeInputTargetVisibilityChanged(
                        imeInputTarget, visibleAndNotRemoved);
            }
        }

        @ImfLockFree
        @Override
        public void updateImeWindowStatus(boolean disableImeIcon, int displayId) {
            mHandler.post(() -> {
                synchronized (ImfLock.class) {
                    updateImeWindowStatusLocked(disableImeIcon, displayId);
                }
            });
        }

        @Override
        public void updateShouldShowImeSwitcher(int displayId, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                updateSystemUiLocked(userId);
                final var userData = getUserData(userId);
                sendOnNavButtonFlagsChangedLocked(userData);
            }
        }

        @Override
        public void onSessionForAccessibilityCreated(int accessibilityConnectionId,
                IAccessibilityInputMethodSession session, @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = getUserData(userId);
                final var bindingController = userData.mBindingController;
                // TODO(b/305829876): Implement user ID verification
                if (userData.mCurClient != null) {
                    clearClientSessionForAccessibilityLocked(userData.mCurClient,
                            accessibilityConnectionId);
                    userData.mCurClient.mAccessibilitySessions.put(
                            accessibilityConnectionId,
                            new AccessibilitySessionState(userData.mCurClient,
                                    accessibilityConnectionId,
                                    session));

                    attachNewAccessibilityLocked(StartInputReason.SESSION_CREATED_BY_ACCESSIBILITY,
                            true, userId);

                    final SessionState sessionState = userData.mCurClient.mCurSession;
                    final IInputMethodSession imeSession = sessionState == null
                            ? null : sessionState.mSession;
                    final SparseArray<IAccessibilityInputMethodSession>
                            accessibilityInputMethodSessions =
                            createAccessibilityInputMethodSessions(
                                    userData.mCurClient.mAccessibilitySessions);
                    final InputBindResult res = new InputBindResult(
                            InputBindResult.ResultCode.SUCCESS_WITH_ACCESSIBILITY_SESSION,
                            imeSession, accessibilityInputMethodSessions, /* channel= */ null,
                            bindingController.getCurId(),
                            bindingController.getSequenceNumber(),
                            /* isInputMethodSuppressingSpellChecker= */ false);
                    userData.mCurClient.mClient.onBindAccessibilityService(res,
                            accessibilityConnectionId);
                }
            }
        }

        @Override
        public void unbindAccessibilityFromCurrentClient(int accessibilityConnectionId,
                @UserIdInt int userId) {
            synchronized (ImfLock.class) {
                final var userData = getUserData(userId);
                final var bindingController = userData.mBindingController;
                // TODO(b/305829876): Implement user ID verification
                if (userData.mCurClient != null) {
                    if (DEBUG) {
                        Slog.v(TAG, "unbindAccessibilityFromCurrentClientLocked: client="
                                + userData.mCurClient.mClient.asBinder());
                    }
                    // A11yManagerService unbinds the disabled accessibility service. We don't need
                    // to do it here.
                    userData.mCurClient.mClient.onUnbindAccessibilityService(
                            bindingController.getSequenceNumber(),
                            accessibilityConnectionId);
                }
                // We only have sessions when we bound to an input method. Remove this session
                // from all clients.
                if (bindingController.getCurMethod() != null) {
                    // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
                    @SuppressWarnings("GuardedBy") Consumer<ClientState> clearClientSession =
                            c -> clearClientSessionForAccessibilityLocked(c,
                                    accessibilityConnectionId);
                    mClientController.forAllClients(clearClientSession);

                    AccessibilitySessionState session = userData.mEnabledAccessibilitySessions.get(
                            accessibilityConnectionId);
                    if (session != null) {
                        finishSessionForAccessibilityLocked(session);
                        userData.mEnabledAccessibilitySessions.remove(accessibilityConnectionId);
                    }
                }
            }
        }

        @ImfLockFree
        @Override
        public void maybeFinishStylusHandwriting() {
            mHandler.removeMessages(MSG_FINISH_HANDWRITING);
            mHandler.obtainMessage(MSG_FINISH_HANDWRITING).sendToTarget();
        }

        @Override
        public void onSwitchKeyboardLayoutShortcut(int direction, int displayId,
                IBinder targetWindowToken) {
            synchronized (ImfLock.class) {
                final int userId = resolveImeUserIdFromDisplayIdLocked(displayId);
                switchKeyboardLayoutLocked(direction, getUserData(userId));
            }
        }
    }

    @BinderThread
    @GuardedBy("ImfLock.class")
    @Nullable
    private IInputContentUriToken createInputContentUriTokenLocked(@NonNull Uri contentUri,
            @NonNull String packageName, @NonNull UserData userData) {
        Objects.requireNonNull(packageName, "packageName must not be null");
        Objects.requireNonNull(contentUri, "contentUri must not be null");
        final String contentUriScheme = contentUri.getScheme();
        if (!"content".equals(contentUriScheme)) {
            throw new InvalidParameterException("contentUri must have content scheme");
        }

        final int uid = Binder.getCallingUid();
        final var bindingController = userData.mBindingController;
        if (bindingController.getSelectedMethodId() == null) {
            return null;
        }
        // We cannot simply distinguish a bad IME that reports an arbitrary package name from
        // an unfortunate IME whose internal state is already obsolete due to the asynchronous
        // nature of our system.  Let's compare it with our internal record.
        final var curPackageName = userData.mCurEditorInfo != null
                ? userData.mCurEditorInfo.packageName : null;
        if (!TextUtils.equals(curPackageName, packageName)) {
            Slog.e(TAG, "Ignoring createInputContentUriTokenLocked mCurEditorInfo.packageName="
                    + curPackageName + " packageName=" + packageName);
            return null;
        }
        // This user ID can never be spoofed.
        final int appUserId = UserHandle.getUserId(userData.mCurClient.mUid);
        // This user ID may be invalid if "contentUri" embedded an invalid user ID.
        final int contentUriOwnerUserId = ContentProvider.getUserIdFromUri(contentUri,
                userData.mUserId);
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

    @BinderThread
    @GuardedBy("ImfLock.class")
    private void reportFullscreenModeLocked(boolean fullscreen, @NonNull UserData userData) {
        if (userData.mCurClient != null && userData.mCurClient.mClient != null) {
            userData.mInFullscreenMode = fullscreen;
            userData.mCurClient.mClient.reportFullscreenMode(fullscreen);
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
        final int argUserId = parseUserIdFromDumpArgs(args);
        final Printer p = new PrintWriterPrinter(pw);
        p.println("Current Input Method Manager state:");
        p.println("  mSystemReady=" + mSystemReady);
        p.println("  mInteractive=" + mIsInteractive);
        p.println("  mConcurrentMultiUserModeEnabled=" + mConcurrentMultiUserModeEnabled);
        p.println("  ENABLE_HIDE_IME_CAPTION_BAR="
                + InputMethodService.ENABLE_HIDE_IME_CAPTION_BAR);
        synchronized (ImfLock.class) {
            p.println("  mStylusIds=" + (mStylusIds != null
                    ? Arrays.toString(mStylusIds.toArray()) : ""));
        }
        if (Flags.imeSwitcherRevamp()) {
            p.println("  mMenuControllerNew:");
            mMenuControllerNew.dump(p, "  ");
        } else {
            p.println("  mMenuController:");
            mMenuController.dump(p, "  ");
        }
        if (mConcurrentMultiUserModeEnabled && argUserId == UserHandle.USER_NULL) {
            mUserDataRepository.forAllUserData(
                    u -> dumpAsStringNoCheckForUser(u, fd, pw, args, isCritical));
        } else {
            final int userId = argUserId != UserHandle.USER_NULL ? argUserId : mCurrentImeUserId;
            final var userData = getUserData(userId);
            dumpAsStringNoCheckForUser(userData, fd, pw, args, isCritical);
        }

        // TODO(b/365868861): Make StartInputHistory and ImeTracker multi-user aware.
        synchronized (ImfLock.class) {
            p.println("  mStartInputHistory:");
            mStartInputHistory.dump(pw, "    ");

            p.println("  mSoftInputShowHideHistory:");
            mSoftInputShowHideHistory.dump(pw, "    ");
        }

        p.println("  mImeTrackerService#History:");
        mImeTrackerService.dump(pw, "    ");

        dumpUserRepository(p);
        dumpClientStates(p);
    }

    @UserIdInt
    private static int parseUserIdFromDumpArgs(String[] args) {
        final int userIdx = Arrays.binarySearch(args, "--user");
        if (userIdx == -1 || userIdx == args.length - 1) {
            return UserHandle.USER_NULL;
        }
        return Integer.parseInt(args[userIdx + 1]);
    }

    // TODO(b/356239178): Update dump format output to better group per-user info.
    @BinderThread
    private void dumpAsStringNoCheckForUser(UserData userData, FileDescriptor fd, PrintWriter pw,
            String[] args, boolean isCritical) {
        final Printer p = new PrintWriterPrinter(pw);
        IInputMethodInvoker method;
        ClientState client;
        p.println("  UserId=" + userData.mUserId);
        synchronized (ImfLock.class) {
            final InputMethodSettings settings = InputMethodSettingsRepository.get(
                    userData.mUserId);
            final List<InputMethodInfo> methodList = settings.getMethodList();
            int numImes = methodList.size();
            p.println("    Input Methods:");
            for (int i = 0; i < numImes; i++) {
                InputMethodInfo info = methodList.get(i);
                p.println("      InputMethod #" + i + ":");
                info.dump(p, "        ");
            }
            final var bindingController = userData.mBindingController;
            p.println("        mCurMethodId=" + bindingController.getSelectedMethodId());
            client = userData.mCurClient;
            p.println("        mCurClient=" + client + " mCurSeq="
                    + bindingController.getSequenceNumber());
            p.println("        mFocusedWindowPerceptible=" + mFocusedWindowPerceptible);
            userData.mImeBindingState.dump(/* prefix= */ "  ", p);
            p.println("        mCurId=" + bindingController.getCurId());
            p.println("        mHaveConnection=" + bindingController.hasMainConnection());
            p.println("        mBoundToMethod=" + userData.mBoundToMethod);
            p.println("        mVisibleBound=" + bindingController.isVisibleBound());
            p.println("        mCurToken=" + bindingController.getCurToken());
            p.println("        mCurTokenDisplayId=" + bindingController.getCurTokenDisplayId());
            p.println("        mCurHostInputToken=" + bindingController.getCurHostInputToken());
            p.println("        mCurIntent=" + bindingController.getCurIntent());
            method = bindingController.getCurMethod();
            p.println("        mCurMethod=" + method);
            p.println("        mEnabledSession=" + userData.mEnabledSession);
            final var visibilityStateComputer = userData.mVisibilityStateComputer;
            visibilityStateComputer.dump(pw, "  ");
            p.println("        mInFullscreenMode=" + userData.mInFullscreenMode);
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
        synchronized (ImfLock.class) {
            if (userData.mImeBindingState.mFocusedWindowClient != null
                    && client != userData.mImeBindingState.mFocusedWindowClient) {
                p.println(" ");
                p.println("Warning: Current input method client doesn't match the last focused. "
                        + "window.");
                p.println("Dumping input method client in the last focused window just in case.");
                p.println(" ");
                pw.flush();
                try {
                    TransferPipe.dumpAsync(
                            userData.mImeBindingState.mFocusedWindowClient.mClient.asBinder(), fd,
                            args);
                } catch (IOException | RemoteException e) {
                    p.println("Failed to dump input method client in focused window: " + e);
                }
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

    private void dumpClientStates(Printer p) {
        p.println(" ClientStates:");
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<ClientState> clientControllerDump = c -> {
            p.println("   " + c + ":");
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
        synchronized (ImfLock.class) {
            mClientController.forAllClients(clientControllerDump);
        }
    }

    private void dumpUserRepository(Printer p) {
        p.println("  mUserDataRepository=");
        // TODO(b/324907325): Remove the suppress warnings once b/324907325 is fixed.
        @SuppressWarnings("GuardedBy") Consumer<UserData> userDataDump =
                u -> {
                    p.println("    mUserId=" + u.mUserId);
                    p.println("      unlocked=" + u.mIsUnlockingOrUnlocked.get());
                    p.println("      hasMainConnection="
                            + u.mBindingController.hasMainConnection());
                    p.println("      isVisibleBound=" + u.mBindingController.isVisibleBound());
                    p.println("      boundToMethod=" + u.mBoundToMethod);
                    p.println("      curClient=" + u.mCurClient);
                    if (u.mCurEditorInfo != null) {
                        p.println("      curEditorInfo:");
                        u.mCurEditorInfo.dump(p, "        ", false /* dumpExtras */);
                    } else {
                        p.println("      curEditorInfo: null");
                    }
                    p.println("      imeBindingState:");
                    u.mImeBindingState.dump("        ", p);
                    p.println("      enabledSession=" + u.mEnabledSession);
                    p.println("      inFullscreenMode=" + u.mInFullscreenMode);
                    p.println("      imeDrawsNavBar=" + u.mImeDrawsNavBar.get());
                    p.println("      switchingController:");
                    u.mSwitchingController.dump(p, "        ");
                    p.println("      mLastEnabledInputMethodsStr="
                            + u.mLastEnabledInputMethodsStr);
                };
        synchronized (ImfLock.class) {
            mUserDataRepository.forAllUserData(userDataDump);
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
        final int[] userIds;
        synchronized (ImfLock.class) {
            userIds = InputMethodUtils.resolveUserId(userIdToBeResolved, mCurrentImeUserId,
                    shellCommand.getErrPrintWriter());
        }
        try (PrintWriter pr = shellCommand.getOutPrintWriter()) {
            for (int userId : userIds) {
                final List<InputMethodInfo> methods = all
                        ? getInputMethodListInternal(
                                userId, DirectBootAwareness.AUTO, Process.SHELL_UID)
                        : getEnabledInputMethodListInternal(userId, Process.SHELL_UID);
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
                        mCurrentImeUserId, shellCommand.getErrPrintWriter());
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
        final InputMethodSettings settings = InputMethodSettingsRepository.get(userId);
        if (enabled && !settings.getMethodMap().containsKey(imeId)) {
            error.print("Unknown input method ");
            error.print(imeId);
            error.println(" cannot be enabled for user #" + userId);
            // Also print this failure into logcat for better debuggability.
            Slog.e(TAG, "\"ime enable " + imeId + "\" for user #" + userId
                    + " failed due to its unrecognized IME ID.");
            return false;
        }

        final boolean previouslyEnabled = setInputMethodEnabledLocked(imeId, enabled, userId);
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
                        mCurrentImeUserId, shellCommand.getErrPrintWriter());
                for (int userId : userIds) {
                    if (!userHasDebugPriv(userId, shellCommand)) {
                        continue;
                    }
                    boolean failedToSelectUnknownIme = !switchToInputMethodLocked(imeId,
                            NOT_A_SUBTYPE_INDEX, userId);
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

                        // Workaround for b/354782333.
                        final InputMethodSettings settings =
                                InputMethodSettingsRepository.get(userId);
                        final var bindingController = getInputMethodBindingController(userId);
                        final int deviceId = bindingController.getDeviceIdToShowIme();
                        final String settingsValue;
                        if (deviceId == DEVICE_ID_DEFAULT) {
                            settingsValue = settings.getSelectedInputMethod();
                        } else {
                            settingsValue = settings.getSelectedDefaultDeviceInputMethod();
                        }
                        if (!TextUtils.equals(settingsValue, imeId)) {
                            Slog.w(TAG, "DEFAULT_INPUT_METHOD=" + settingsValue
                                    + " is not updated. Fixing it up to " + imeId
                                    + " See b/354782333.");
                            if (deviceId == DEVICE_ID_DEFAULT) {
                                settings.putSelectedInputMethod(imeId);
                            } else {
                                settings.putSelectedDefaultDeviceInputMethod(imeId);
                            }
                        }
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
                        mCurrentImeUserId, shellCommand.getErrPrintWriter());
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
                    final var userData = getUserData(userId);
                    if (Flags.refactorInsetsController()) {
                        setImeVisibilityOnFocusedWindowClient(false, userData,
                                null /* TODO(b329229469) initialize statsToken here? */);
                    } else {
                        hideCurrentInputLocked(userData.mImeBindingState.mFocusedWindow,
                                0 /* flags */,
                                SoftInputShowHideReason.HIDE_RESET_SHELL_COMMAND, userId);
                    }
                    final var bindingController = userData.mBindingController;
                    bindingController.unbindCurrentMethod();

                    // Enable default IMEs, disable others
                    var toDisable = settings.getEnabledInputMethodList();
                    var defaultEnabled = InputMethodInfoUtils.getDefaultEnabledImes(
                            mContext, settings.getMethodList());
                    toDisable.removeAll(defaultEnabled);
                    for (InputMethodInfo info : toDisable) {
                        setInputMethodEnabledLocked(info.getId(), false, userId);
                    }
                    for (InputMethodInfo info : defaultEnabled) {
                        setInputMethodEnabledLocked(info.getId(), true, userId);
                    }
                    // Choose new default IME, reset to none if no IME available.
                    if (!chooseNewDefaultIMELocked(userId)) {
                        resetSelectedInputMethodAndSubtypeLocked(null, userId);
                    }
                    updateInputMethodsFromSettingsLocked(true /* enabledMayChange */, userId);
                    InputMethodUtils.setNonSelectedSystemImesDisabledUntilUsed(
                            getPackageManagerForUser(mContext, settings.getUserId()),
                            settings.getEnabledInputMethodList());
                    nextIme = settings.getSelectedInputMethod();
                    nextEnabledImes = settings.getEnabledInputMethodList();
                    out.println("Reset current and enabled IMEs for user #" + userId);
                    out.println("  Selected: " + nextIme);
                    nextEnabledImes.forEach(ime -> out.println("   Enabled: " + ime.getId()));
                }
            }
        }
        return ShellCommandResult.SUCCESS;
    }

    @GuardedBy("ImfLock.class")
    boolean setImeVisibilityOnFocusedWindowClient(boolean visible, UserData userData,
            @NonNull ImeTracker.Token statsToken) {
        if (Flags.refactorInsetsController()) {
            if (userData.mImeBindingState != null
                    && userData.mImeBindingState.mFocusedWindowClient != null
                    && userData.mImeBindingState.mFocusedWindowClient.mClient != null) {
                userData.mImeBindingState.mFocusedWindowClient.mClient.setImeVisibility(visible,
                        statsToken);
                return true;
            }
            ImeTracker.forLogging().onFailed(statsToken,
                    ImeTracker.PHASE_SERVER_SET_VISIBILITY_ON_FOCUSED_WINDOW);
        }
        return false;
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
    @GuardedBy("ImfLock.class")
    private ImeTracker.Token createStatsTokenForFocusedClient(boolean show,
            @SoftInputShowHideReason int reason, @UserIdInt int userId) {
        final var userData = getUserData(userId);
        final int uid = userData.mImeBindingState.mFocusedWindowClient != null
                ? userData.mImeBindingState.mFocusedWindowClient.mUid
                : -1;
        final var packageName = userData.mImeBindingState.mFocusedWindowEditorInfo != null
                ? userData.mImeBindingState.mFocusedWindowEditorInfo.packageName
                : "uid(" + uid + ")";

        return ImeTracker.forLogging().onStart(packageName, uid,
                show ? ImeTracker.TYPE_SHOW : ImeTracker.TYPE_HIDE, ImeTracker.ORIGIN_SERVER,
                reason, false /* fromUser */);
    }

    private static final class InputMethodPrivilegedOperationsImpl
            extends IInputMethodPrivilegedOperations.Stub {
        @NonNull
        private final InputMethodManagerService mImms;
        @NonNull
        private final IBinder mToken;
        @NonNull
        private final UserData mUserData;

        InputMethodPrivilegedOperationsImpl(@NonNull InputMethodManagerService imms,
                @NonNull IBinder token, @NonNull UserData userData) {
            mImms = imms;
            mToken = token;
            mUserData = userData;
        }

        @BinderThread
        @Override
        public void setImeWindowStatusAsync(@ImeWindowVisibility int vis,
                @BackDispositionMode int backDisposition) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.setImeWindowStatusLocked(vis, backDisposition, mUserData);
            }
        }

        @BinderThread
        @Override
        public void reportStartInputAsync(IBinder startInputToken) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.reportStartInputLocked(startInputToken, mUserData);
            }
        }

        @BinderThread
        @Override
        public void setHandwritingSurfaceNotTouchable(boolean notTouchable) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.mHwController.setNotTouchable(notTouchable);
            }
        }

        @BinderThread
        @Override
        public void createInputContentUriToken(Uri contentUri, String packageName,
                AndroidFuture future /* T=IBinder */) {
            @SuppressWarnings("unchecked") final AndroidFuture<IBinder> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    typedFuture.complete(mImms.createInputContentUriTokenLocked(
                            contentUri, packageName, mUserData).asBinder());
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void reportFullscreenModeAsync(boolean fullscreen) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.reportFullscreenModeLocked(fullscreen, mUserData);
            }
        }

        @BinderThread
        @Override
        public void setInputMethod(String id, AndroidFuture future /* T=Void */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Void> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    mImms.setInputMethodAndSubtypeLocked(id, null /* subtype */, mUserData);
                    typedFuture.complete(null);
                }
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
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(null);
                        return;
                    }
                    mImms.setInputMethodAndSubtypeLocked(id, subtype, mUserData);
                    typedFuture.complete(null);
                }
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
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        ImeTracker.forLogging().onFailed(statsToken,
                                ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                        typedFuture.complete(null);
                        return;
                    }
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.hideMySoftInput");
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mImms.hideMySoftInputLocked(statsToken, flags, reason, mUserData);
                        typedFuture.complete(null);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }
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
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        ImeTracker.forLogging().onFailed(statsToken,
                                ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                        typedFuture.complete(null);
                        return;
                    }
                    ImeTracker.forLogging().onProgress(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "IMMS.showMySoftInput");
                    final long ident = Binder.clearCallingIdentity();
                    try {
                        mImms.showMySoftInputLocked(statsToken, flags, reason, mUserData);
                        typedFuture.complete(null);
                    } finally {
                        Binder.restoreCallingIdentity(ident);
                        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
                    }
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void updateStatusIconAsync(String packageName, @DrawableRes int iconId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mImms.updateStatusIconLocked(packageName, iconId, mUserData);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        @BinderThread
        @Override
        public void switchToPreviousInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.switchToPreviousInputMethodLocked(mUserData));
                }
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
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.switchToNextInputMethodLocked(onlyCurrentIme,
                            mUserData));
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void shouldOfferSwitchingToNextInputMethod(AndroidFuture future /* T=Boolean */) {
            @SuppressWarnings("unchecked") final AndroidFuture<Boolean> typedFuture = future;
            try {
                synchronized (ImfLock.class) {
                    if (!calledWithValidTokenLocked(mToken, mUserData)) {
                        typedFuture.complete(false);
                        return;
                    }
                    typedFuture.complete(mImms.shouldOfferSwitchingToNextInputMethodLocked(
                            mUserData));
                }
            } catch (Throwable e) {
                typedFuture.completeExceptionally(e);
            }
        }

        @BinderThread
        @Override
        public void onImeSwitchButtonClickFromClient(int displayId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.onImeSwitchButtonClickLocked(displayId, mUserData);
            }
        }

        @BinderThread
        @Override
        public void notifyUserActionAsync() {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.notifyUserActionLocked(mUserData);
            }
        }

        @BinderThread
        @Override
        public void applyImeVisibilityAsync(IBinder windowToken, boolean setVisible,
                @NonNull ImeTracker.Token statsToken) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    ImeTracker.forLogging().onFailed(statsToken,
                            ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                    return;
                }
                ImeTracker.forLogging().onProgress(statsToken,
                        ImeTracker.PHASE_SERVER_CURRENT_ACTIVE_IME);
                mImms.applyImeVisibilityLocked(windowToken, setVisible, statsToken, mUserData);
            }
        }

        @BinderThread
        @Override
        public void onStylusHandwritingReady(int requestId, int pid) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.onStylusHandwritingReadyLocked(requestId, pid, mUserData);
            }
        }

        @BinderThread
        @Override
        public void resetStylusHandwriting(int requestId) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                mImms.resetStylusHandwritingLocked(requestId);
            }
        }

        @BinderThread
        @Override
        public void switchKeyboardLayoutAsync(int direction) {
            synchronized (ImfLock.class) {
                if (!calledWithValidTokenLocked(mToken, mUserData)) {
                    return;
                }
                final long ident = Binder.clearCallingIdentity();
                try {
                    mImms.switchKeyboardLayoutLocked(direction, mUserData);
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
        }

        /**
         * Returns true iff the caller is identified to be the current input method with the token.
         *
         * @param token the window token given to the input method when it was started
         * @param userData {@link UserData} of the calling IME process
         * @return true if and only if non-null valid token is specified
         */
        @GuardedBy("ImfLock.class")
        private static boolean calledWithValidTokenLocked(@NonNull IBinder token,
                @NonNull UserData userData) {
            Objects.requireNonNull(token, "token must not be null");
            final var bindingController = userData.mBindingController;
            if (token != bindingController.getCurToken()) {
                Slog.e(TAG, "Ignoring " + Debug.getCaller() + " due to an invalid token."
                        + " uid:" + Binder.getCallingUid() + " token:" + token);
                return false;
            }
            return true;
        }
    }
}
