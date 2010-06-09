/*
 * Copyright (C) 2006-2008 The Android Open Source Project
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

package com.android.server;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.HandlerCaller;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethod;
import com.android.internal.view.IInputMethodCallback;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;
import com.android.internal.view.IInputMethodSession;
import com.android.internal.view.InputBindResult;

import com.android.server.StatusBarManagerService;

import org.xmlpull.v1.XmlPullParserException;

import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.DialogInterface.OnCancelListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Slog;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * This class provides a system service that manages input methods.
 */
public class InputMethodManagerService extends IInputMethodManager.Stub
        implements ServiceConnection, Handler.Callback {
    static final boolean DEBUG = false;
    static final String TAG = "InputManagerService";

    static final int MSG_SHOW_IM_PICKER = 1;

    static final int MSG_UNBIND_INPUT = 1000;
    static final int MSG_BIND_INPUT = 1010;
    static final int MSG_SHOW_SOFT_INPUT = 1020;
    static final int MSG_HIDE_SOFT_INPUT = 1030;
    static final int MSG_ATTACH_TOKEN = 1040;
    static final int MSG_CREATE_SESSION = 1050;

    static final int MSG_START_INPUT = 2000;
    static final int MSG_RESTART_INPUT = 2010;

    static final int MSG_UNBIND_METHOD = 3000;
    static final int MSG_BIND_METHOD = 3010;

    static final long TIME_TO_RECONNECT = 10*1000;

    final Context mContext;
    final Handler mHandler;
    final SettingsObserver mSettingsObserver;
    final StatusBarManagerService mStatusBar;
    final IWindowManager mIWindowManager;
    final HandlerCaller mCaller;

    final InputBindResult mNoBinding = new InputBindResult(null, null, -1);

    // All known input methods.  mMethodMap also serves as the global
    // lock for this class.
    final ArrayList<InputMethodInfo> mMethodList
            = new ArrayList<InputMethodInfo>();
    final HashMap<String, InputMethodInfo> mMethodMap
            = new HashMap<String, InputMethodInfo>();

    final TextUtils.SimpleStringSplitter mStringColonSplitter
            = new TextUtils.SimpleStringSplitter(':');

    class SessionState {
        final ClientState client;
        final IInputMethod method;
        final IInputMethodSession session;

        @Override
        public String toString() {
            return "SessionState{uid " + client.uid + " pid " + client.pid
                    + " method " + Integer.toHexString(
                            System.identityHashCode(method))
                    + " session " + Integer.toHexString(
                            System.identityHashCode(session))
                    + "}";
        }

        SessionState(ClientState _client, IInputMethod _method,
                IInputMethodSession _session) {
            client = _client;
            method = _method;
            session = _session;
        }
    }

    class ClientState {
        final IInputMethodClient client;
        final IInputContext inputContext;
        final int uid;
        final int pid;
        final InputBinding binding;

        boolean sessionRequested;
        SessionState curSession;

        @Override
        public String toString() {
            return "ClientState{" + Integer.toHexString(
                    System.identityHashCode(this)) + " uid " + uid
                    + " pid " + pid + "}";
        }

        ClientState(IInputMethodClient _client, IInputContext _inputContext,
                int _uid, int _pid) {
            client = _client;
            inputContext = _inputContext;
            uid = _uid;
            pid = _pid;
            binding = new InputBinding(null, inputContext.asBinder(), uid, pid);
        }
    }

    final HashMap<IBinder, ClientState> mClients
            = new HashMap<IBinder, ClientState>();

    /**
     * Set once the system is ready to run third party code.
     */
    boolean mSystemReady;

    /**
     * Id of the currently selected input method.
     */
    String mCurMethodId;

    /**
     * The current binding sequence number, incremented every time there is
     * a new bind performed.
     */
    int mCurSeq;

    /**
     * The client that is currently bound to an input method.
     */
    ClientState mCurClient;

    /**
     * The last window token that gained focus.
     */
    IBinder mCurFocusedWindow;

    /**
     * The input context last provided by the current client.
     */
    IInputContext mCurInputContext;

    /**
     * The attributes last provided by the current client.
     */
    EditorInfo mCurAttribute;

    /**
     * The input method ID of the input method service that we are currently
     * connected to or in the process of connecting to.
     */
    String mCurId;

    /**
     * Set to true if our ServiceConnection is currently actively bound to
     * a service (whether or not we have gotten its IBinder back yet).
     */
    boolean mHaveConnection;

    /**
     * Set if the client has asked for the input method to be shown.
     */
    boolean mShowRequested;

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
    boolean mInputShown;

    /**
     * The Intent used to connect to the current input method.
     */
    Intent mCurIntent;

    /**
     * The token we have made for the currently active input method, to
     * identify it in the future.
     */
    IBinder mCurToken;

    /**
     * If non-null, this is the input method service we are currently connected
     * to.
     */
    IInputMethod mCurMethod;

    /**
     * Time that we last initiated a bind to the input method, to determine
     * if we should try to disconnect and reconnect to it.
     */
    long mLastBindTime;

    /**
     * Have we called mCurMethod.bindInput()?
     */
    boolean mBoundToMethod;

    /**
     * Currently enabled session.  Only touched by service thread, not
     * protected by a lock.
     */
    SessionState mEnabledSession;

    /**
     * True if the screen is on.  The value is true initially.
     */
    boolean mScreenOn = true;

    AlertDialog.Builder mDialogBuilder;
    AlertDialog mSwitchingDialog;
    InputMethodInfo[] mIms;
    CharSequence[] mItems;

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.Secure.getUriFor(
                    Settings.Secure.DEFAULT_INPUT_METHOD), false, this);
        }

        @Override public void onChange(boolean selfChange) {
            synchronized (mMethodMap) {
                updateFromSettingsLocked();
            }
        }
    }

    class ScreenOnOffReceiver extends android.content.BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                mScreenOn = true;
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                mScreenOn = false;
            } else if (intent.getAction().equals(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {
                hideInputMethodMenu();
                return;
            } else {
                Slog.w(TAG, "Unexpected intent " + intent);
            }

            // Inform the current client of the change in active status
            try {
                if (mCurClient != null && mCurClient.client != null) {
                    mCurClient.client.setActive(mScreenOn);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Got RemoteException sending 'screen on/off' notification to pid "
                        + mCurClient.pid + " uid " + mCurClient.uid);
            }
        }
    }

    class MyPackageMonitor extends PackageMonitor {
        
        @Override
        public boolean onHandleForceStop(Intent intent, String[] packages, int uid, boolean doit) {
            synchronized (mMethodMap) {
                String curInputMethodId = Settings.Secure.getString(mContext
                        .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
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
                                    
                                    Settings.Secure.putString(mContext.getContentResolver(),
                                            Settings.Secure.DEFAULT_INPUT_METHOD, "");
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
        public void onSomePackagesChanged() {
            synchronized (mMethodMap) {
                InputMethodInfo curIm = null;
                String curInputMethodId = Settings.Secure.getString(mContext
                        .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
                final int N = mMethodList.size();
                if (curInputMethodId != null) {
                    for (int i=0; i<N; i++) {
                        InputMethodInfo imi = mMethodList.get(i);
                        if (imi.getId().equals(curInputMethodId)) {
                            curIm = imi;
                        }
                        int change = isPackageDisappearing(imi.getPackageName());
                        if (change == PACKAGE_TEMPORARY_CHANGE
                                || change == PACKAGE_PERMANENT_CHANGE) {
                            Slog.i(TAG, "Input method uninstalled, disabling: "
                                    + imi.getComponent());
                            setInputMethodEnabledLocked(imi.getId(), false);
                        }
                    }
                }

                buildInputMethodListLocked(mMethodList, mMethodMap);

                boolean changed = false;

                if (curIm != null) {
                    int change = isPackageDisappearing(curIm.getPackageName()); 
                    if (change == PACKAGE_TEMPORARY_CHANGE
                            || change == PACKAGE_PERMANENT_CHANGE) {
                        ServiceInfo si = null;
                        try {
                            si = mContext.getPackageManager().getServiceInfo(
                                    curIm.getComponent(), 0);
                        } catch (PackageManager.NameNotFoundException ex) {
                        }
                        if (si == null) {
                            // Uh oh, current input method is no longer around!
                            // Pick another one...
                            Slog.i(TAG, "Current input method removed: " + curInputMethodId);
                            if (!chooseNewDefaultIMELocked()) {
                                changed = true;
                                curIm = null;
                                curInputMethodId = "";
                                Slog.i(TAG, "Unsetting current input method");
                                Settings.Secure.putString(mContext.getContentResolver(),
                                        Settings.Secure.DEFAULT_INPUT_METHOD,
                                        curInputMethodId);
                            }
                        }
                    }
                }
                
                if (curIm == null) {
                    // We currently don't have a default input method... is
                    // one now available?
                    changed = chooseNewDefaultIMELocked();
                }

                if (changed) {
                    updateFromSettingsLocked();
                }
            }
        }
    }

    class MethodCallback extends IInputMethodCallback.Stub {
        final IInputMethod mMethod;

        MethodCallback(IInputMethod method) {
            mMethod = method;
        }

        public void finishedEvent(int seq, boolean handled) throws RemoteException {
        }

        public void sessionCreated(IInputMethodSession session) throws RemoteException {
            onSessionCreated(mMethod, session);
        }
    }

    public InputMethodManagerService(Context context, StatusBarManagerService statusBar) {
        mContext = context;
        mHandler = new Handler(this);
        mIWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService(Context.WINDOW_SERVICE));
        mCaller = new HandlerCaller(context, new HandlerCaller.Callback() {
            public void executeMessage(Message msg) {
                handleMessage(msg);
            }
        });

        (new MyPackageMonitor()).register(mContext, true);

        IntentFilter screenOnOffFilt = new IntentFilter();
        screenOnOffFilt.addAction(Intent.ACTION_SCREEN_ON);
        screenOnOffFilt.addAction(Intent.ACTION_SCREEN_OFF);
        screenOnOffFilt.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(new ScreenOnOffReceiver(), screenOnOffFilt);

        buildInputMethodListLocked(mMethodList, mMethodMap);

        final String enabledStr = Settings.Secure.getString(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        Slog.i(TAG, "Enabled input methods: " + enabledStr);
        final String defaultIme = Settings.Secure.getString(mContext
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (enabledStr == null || TextUtils.isEmpty(defaultIme)) {
            Slog.i(TAG, "Enabled input methods or default IME has not been set, enabling all");
            InputMethodInfo defIm = null;
            StringBuilder sb = new StringBuilder(256);
            final int N = mMethodList.size();
            for (int i=0; i<N; i++) {
                InputMethodInfo imi = mMethodList.get(i);
                Slog.i(TAG, "Adding: " + imi.getId());
                if (i > 0) sb.append(':');
                sb.append(imi.getId());
                if (defIm == null && imi.getIsDefaultResourceId() != 0) {
                    try {
                        Resources res = mContext.createPackageContext(
                                imi.getPackageName(), 0).getResources();
                        if (res.getBoolean(imi.getIsDefaultResourceId())) {
                            defIm = imi;
                            Slog.i(TAG, "Selected default: " + imi.getId());
                        }
                    } catch (PackageManager.NameNotFoundException ex) {
                    } catch (Resources.NotFoundException ex) {
                    }
                }
            }
            if (defIm == null && N > 0) {
                defIm = mMethodList.get(0);
                Slog.i(TAG, "No default found, using " + defIm.getId());
            }
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS, sb.toString());
            if (defIm != null) {
                Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD, defIm.getId());
            }
        }

        mStatusBar = statusBar;
        statusBar.setIconVisibility("ime", false);

        mSettingsObserver = new SettingsObserver(mHandler);
        updateFromSettingsLocked();
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
                Slog.e(TAG, "Input Method Manager Crash", e);
            }
            throw e;
        }
    }

    public void systemReady() {
        synchronized (mMethodMap) {
            if (!mSystemReady) {
                mSystemReady = true;
                try {
                    startInputInnerLocked();
                } catch (RuntimeException e) {
                    Slog.w(TAG, "Unexpected exception", e);
                }
            }
        }
    }

    public List<InputMethodInfo> getInputMethodList() {
        synchronized (mMethodMap) {
            return new ArrayList<InputMethodInfo>(mMethodList);
        }
    }

    public List<InputMethodInfo> getEnabledInputMethodList() {
        synchronized (mMethodMap) {
            return getEnabledInputMethodListLocked();
        }
    }

    List<InputMethodInfo> getEnabledInputMethodListLocked() {
        final ArrayList<InputMethodInfo> res = new ArrayList<InputMethodInfo>();

        final String enabledStr = Settings.Secure.getString(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        if (enabledStr != null) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(enabledStr);

            while (splitter.hasNext()) {
                InputMethodInfo info = mMethodMap.get(splitter.next());
                if (info != null) {
                    res.add(info);
                }
            }
        }

        return res;
    }

    public void addClient(IInputMethodClient client,
            IInputContext inputContext, int uid, int pid) {
        synchronized (mMethodMap) {
            mClients.put(client.asBinder(), new ClientState(client,
                    inputContext, uid, pid));
        }
    }

    public void removeClient(IInputMethodClient client) {
        synchronized (mMethodMap) {
            mClients.remove(client.asBinder());
        }
    }

    void executeOrSendMessage(IInterface target, Message msg) {
         if (target.asBinder() instanceof Binder) {
             mCaller.sendMessage(msg);
         } else {
             handleMessage(msg);
             msg.recycle();
         }
    }

    void unbindCurrentClientLocked() {
        if (mCurClient != null) {
            if (DEBUG) Slog.v(TAG, "unbindCurrentInputLocked: client = "
                    + mCurClient.client.asBinder());
            if (mBoundToMethod) {
                mBoundToMethod = false;
                if (mCurMethod != null) {
                    executeOrSendMessage(mCurMethod, mCaller.obtainMessageO(
                            MSG_UNBIND_INPUT, mCurMethod));
                }
            }
            executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                    MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
            mCurClient.sessionRequested = false;

            // Call setActive(false) on the old client
            try {
                mCurClient.client.setActive(false);
            } catch (RemoteException e) {
                Slog.w(TAG, "Got RemoteException sending setActive(false) notification to pid "
                        + mCurClient.pid + " uid " + mCurClient.uid);
            }
            mCurClient = null;

            hideInputMethodMenuLocked();
        }
    }

    private int getImeShowFlags() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethod.SHOW_FORCED
                    | InputMethod.SHOW_EXPLICIT;
        } else if (mShowExplicitlyRequested) {
            flags |= InputMethod.SHOW_EXPLICIT;
        }
        return flags;
    }

    private int getAppShowFlags() {
        int flags = 0;
        if (mShowForced) {
            flags |= InputMethodManager.SHOW_FORCED;
        } else if (!mShowExplicitlyRequested) {
            flags |= InputMethodManager.SHOW_IMPLICIT;
        }
        return flags;
    }

    InputBindResult attachNewInputLocked(boolean initial, boolean needResult) {
        if (!mBoundToMethod) {
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                    MSG_BIND_INPUT, mCurMethod, mCurClient.binding));
            mBoundToMethod = true;
        }
        final SessionState session = mCurClient.curSession;
        if (initial) {
            executeOrSendMessage(session.method, mCaller.obtainMessageOOO(
                    MSG_START_INPUT, session, mCurInputContext, mCurAttribute));
        } else {
            executeOrSendMessage(session.method, mCaller.obtainMessageOOO(
                    MSG_RESTART_INPUT, session, mCurInputContext, mCurAttribute));
        }
        if (mShowRequested) {
            if (DEBUG) Slog.v(TAG, "Attach new input asks to show input");
            showCurrentInputLocked(getAppShowFlags(), null);
        }
        return needResult
                ? new InputBindResult(session.session, mCurId, mCurSeq)
                : null;
    }

    InputBindResult startInputLocked(IInputMethodClient client,
            IInputContext inputContext, EditorInfo attribute,
            boolean initial, boolean needResult) {
        // If no method is currently selected, do nothing.
        if (mCurMethodId == null) {
            return mNoBinding;
        }

        ClientState cs = mClients.get(client.asBinder());
        if (cs == null) {
            throw new IllegalArgumentException("unknown client "
                    + client.asBinder());
        }

        try {
            if (!mIWindowManager.inputMethodClientHasFocus(cs.client)) {
                // Check with the window manager to make sure this client actually
                // has a window with focus.  If not, reject.  This is thread safe
                // because if the focus changes some time before or after, the
                // next client receiving focus that has any interest in input will
                // be calling through here after that change happens.
                Slog.w(TAG, "Starting input on non-focused client " + cs.client
                        + " (uid=" + cs.uid + " pid=" + cs.pid + ")");
                return null;
            }
        } catch (RemoteException e) {
        }

        if (mCurClient != cs) {
            // If the client is changing, we need to switch over to the new
            // one.
            unbindCurrentClientLocked();
            if (DEBUG) Slog.v(TAG, "switching to client: client = "
                    + cs.client.asBinder());

            // If the screen is on, inform the new client it is active
            if (mScreenOn) {
                try {
                    cs.client.setActive(mScreenOn);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Got RemoteException sending setActive notification to pid "
                            + cs.pid + " uid " + cs.uid);
                }
            }
        }

        // Bump up the sequence for this client and attach it.
        mCurSeq++;
        if (mCurSeq <= 0) mCurSeq = 1;
        mCurClient = cs;
        mCurInputContext = inputContext;
        mCurAttribute = attribute;

        // Check if the input method is changing.
        if (mCurId != null && mCurId.equals(mCurMethodId)) {
            if (cs.curSession != null) {
                // Fast case: if we are already connected to the input method,
                // then just return it.
                return attachNewInputLocked(initial, needResult);
            }
            if (mHaveConnection) {
                if (mCurMethod != null) {
                    if (!cs.sessionRequested) {
                        cs.sessionRequested = true;
                        if (DEBUG) Slog.v(TAG, "Creating new session for client " + cs);
                        executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                                MSG_CREATE_SESSION, mCurMethod,
                                new MethodCallback(mCurMethod)));
                    }
                    // Return to client, and we will get back with it when
                    // we have had a session made for it.
                    return new InputBindResult(null, mCurId, mCurSeq);
                } else if (SystemClock.uptimeMillis()
                        < (mLastBindTime+TIME_TO_RECONNECT)) {
                    // In this case we have connected to the service, but
                    // don't yet have its interface.  If it hasn't been too
                    // long since we did the connection, we'll return to
                    // the client and wait to get the service interface so
                    // we can report back.  If it has been too long, we want
                    // to fall through so we can try a disconnect/reconnect
                    // to see if we can get back in touch with the service.
                    return new InputBindResult(null, mCurId, mCurSeq);
                } else {
                    EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME,
                            mCurMethodId, SystemClock.uptimeMillis()-mLastBindTime, 0);
                }
            }
        }

        return startInputInnerLocked();
    }

    InputBindResult startInputInnerLocked() {
        if (mCurMethodId == null) {
            return mNoBinding;
        }

        if (!mSystemReady) {
            // If the system is not yet ready, we shouldn't be running third
            // party code.
            return new InputBindResult(null, mCurMethodId, mCurSeq);
        }

        InputMethodInfo info = mMethodMap.get(mCurMethodId);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + mCurMethodId);
        }

        unbindCurrentMethodLocked(false);

        mCurIntent = new Intent(InputMethod.SERVICE_INTERFACE);
        mCurIntent.setComponent(info.getComponent());
        mCurIntent.putExtra(Intent.EXTRA_CLIENT_LABEL,
                com.android.internal.R.string.input_method_binding_label);
        mCurIntent.putExtra(Intent.EXTRA_CLIENT_INTENT, PendingIntent.getActivity(
                mContext, 0, new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS), 0));
        if (mContext.bindService(mCurIntent, this, Context.BIND_AUTO_CREATE)) {
            mLastBindTime = SystemClock.uptimeMillis();
            mHaveConnection = true;
            mCurId = info.getId();
            mCurToken = new Binder();
            try {
                if (DEBUG) Slog.v(TAG, "Adding window token: " + mCurToken);
                mIWindowManager.addWindowToken(mCurToken,
                        WindowManager.LayoutParams.TYPE_INPUT_METHOD);
            } catch (RemoteException e) {
            }
            return new InputBindResult(null, mCurId, mCurSeq);
        } else {
            mCurIntent = null;
            Slog.w(TAG, "Failure connecting to input method service: "
                    + mCurIntent);
        }
        return null;
    }

    public InputBindResult startInput(IInputMethodClient client,
            IInputContext inputContext, EditorInfo attribute,
            boolean initial, boolean needResult) {
        synchronized (mMethodMap) {
            final long ident = Binder.clearCallingIdentity();
            try {
                return startInputLocked(client, inputContext, attribute,
                        initial, needResult);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void finishInput(IInputMethodClient client) {
    }

    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mMethodMap) {
            if (mCurIntent != null && name.equals(mCurIntent.getComponent())) {
                mCurMethod = IInputMethod.Stub.asInterface(service);
                if (mCurToken == null) {
                    Slog.w(TAG, "Service connected without a token!");
                    unbindCurrentMethodLocked(false);
                    return;
                }
                if (DEBUG) Slog.v(TAG, "Initiating attach with token: " + mCurToken);
                executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                        MSG_ATTACH_TOKEN, mCurMethod, mCurToken));
                if (mCurClient != null) {
                    if (DEBUG) Slog.v(TAG, "Creating first session while with client "
                            + mCurClient);
                    executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                            MSG_CREATE_SESSION, mCurMethod,
                            new MethodCallback(mCurMethod)));
                }
            }
        }
    }

    void onSessionCreated(IInputMethod method, IInputMethodSession session) {
        synchronized (mMethodMap) {
            if (mCurMethod != null && method != null
                    && mCurMethod.asBinder() == method.asBinder()) {
                if (mCurClient != null) {
                    mCurClient.curSession = new SessionState(mCurClient,
                            method, session);
                    mCurClient.sessionRequested = false;
                    InputBindResult res = attachNewInputLocked(true, true);
                    if (res.method != null) {
                        executeOrSendMessage(mCurClient.client, mCaller.obtainMessageOO(
                                MSG_BIND_METHOD, mCurClient.client, res));
                    }
                }
            }
        }
    }

    void unbindCurrentMethodLocked(boolean reportToClient) {
        if (mHaveConnection) {
            mContext.unbindService(this);
            mHaveConnection = false;
        }

        if (mCurToken != null) {
            try {
                if (DEBUG) Slog.v(TAG, "Removing window token: " + mCurToken);
                mIWindowManager.removeWindowToken(mCurToken);
            } catch (RemoteException e) {
            }
            mCurToken = null;
        }

        mCurId = null;
        clearCurMethodLocked();

        if (reportToClient && mCurClient != null) {
            executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                    MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
        }
    }
    
    private void finishSession(SessionState sessionState) {
        if (sessionState != null && sessionState.session != null) {
            try {
                sessionState.session.finishSession();
            } catch (RemoteException e) {
                Slog.w(TAG, "Session failed to close due to remote exception", e);
            }
        }
    }

    void clearCurMethodLocked() {
        if (mCurMethod != null) {
            for (ClientState cs : mClients.values()) {
                cs.sessionRequested = false;
                finishSession(cs.curSession);
                cs.curSession = null;
            }

            finishSession(mEnabledSession);
            mEnabledSession = null;
            mCurMethod = null;
        }
        mStatusBar.setIconVisibility("ime", false);
    }

    public void onServiceDisconnected(ComponentName name) {
        synchronized (mMethodMap) {
            if (DEBUG) Slog.v(TAG, "Service disconnected: " + name
                    + " mCurIntent=" + mCurIntent);
            if (mCurMethod != null && mCurIntent != null
                    && name.equals(mCurIntent.getComponent())) {
                clearCurMethodLocked();
                // We consider this to be a new bind attempt, since the system
                // should now try to restart the service for us.
                mLastBindTime = SystemClock.uptimeMillis();
                mShowRequested = mInputShown;
                mInputShown = false;
                if (mCurClient != null) {
                    executeOrSendMessage(mCurClient.client, mCaller.obtainMessageIO(
                            MSG_UNBIND_METHOD, mCurSeq, mCurClient.client));
                }
            }
        }
    }

    public void updateStatusIcon(IBinder token, String packageName, int iconId) {
        long ident = Binder.clearCallingIdentity();
        try {
            if (token == null || mCurToken != token) {
                Slog.w(TAG, "Ignoring setInputMethod of token: " + token);
                return;
            }

            synchronized (mMethodMap) {
                if (iconId == 0) {
                    if (DEBUG) Slog.d(TAG, "hide the small icon for the input method");
                    mStatusBar.setIconVisibility("ime", false);
                } else if (packageName != null) {
                    if (DEBUG) Slog.d(TAG, "show a small icon for the input method");
                    mStatusBar.setIcon("ime", packageName, iconId, 0);
                    mStatusBar.setIconVisibility("ime", true);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    void updateFromSettingsLocked() {
        // We are assuming that whoever is changing DEFAULT_INPUT_METHOD and
        // ENABLED_INPUT_METHODS is taking care of keeping them correctly in
        // sync, so we will never have a DEFAULT_INPUT_METHOD that is not
        // enabled.
        String id = Settings.Secure.getString(mContext.getContentResolver(),
            Settings.Secure.DEFAULT_INPUT_METHOD);
        if (id != null && id.length() > 0) {
            try {
                setInputMethodLocked(id);
            } catch (IllegalArgumentException e) {
                Slog.w(TAG, "Unknown input method from prefs: " + id, e);
                mCurMethodId = null;
                unbindCurrentMethodLocked(true);
            }
        } else {
            // There is no longer an input method set, so stop any current one.
            mCurMethodId = null;
            unbindCurrentMethodLocked(true);
        }
    }

    void setInputMethodLocked(String id) {
        InputMethodInfo info = mMethodMap.get(id);
        if (info == null) {
            throw new IllegalArgumentException("Unknown id: " + mCurMethodId);
        }

        if (id.equals(mCurMethodId)) {
            return;
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            mCurMethodId = id;
            Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD, id);

            if (ActivityManagerNative.isSystemReady()) {
                Intent intent = new Intent(Intent.ACTION_INPUT_METHOD_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
                intent.putExtra("input_method_id", id);
                mContext.sendBroadcast(intent);
            }
            unbindCurrentClientLocked();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public boolean showSoftInput(IInputMethodClient client, int flags,
            ResultReceiver resultReceiver) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    try {
                        // We need to check if this is the current client with
                        // focus in the window manager, to allow this call to
                        // be made before input is started in it.
                        if (!mIWindowManager.inputMethodClientHasFocus(client)) {
                            Slog.w(TAG, "Ignoring showSoftInput of: " + client);
                            return false;
                        }
                    } catch (RemoteException e) {
                        return false;
                    }
                }

                if (DEBUG) Slog.v(TAG, "Client requesting input be shown");
                return showCurrentInputLocked(flags, resultReceiver);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean showCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        mShowRequested = true;
        if ((flags&InputMethodManager.SHOW_IMPLICIT) == 0) {
            mShowExplicitlyRequested = true;
        }
        if ((flags&InputMethodManager.SHOW_FORCED) != 0) {
            mShowExplicitlyRequested = true;
            mShowForced = true;
        }

        if (!mSystemReady) {
            return false;
        }

        boolean res = false;
        if (mCurMethod != null) {
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageIOO(
                    MSG_SHOW_SOFT_INPUT, getImeShowFlags(), mCurMethod,
                    resultReceiver));
            mInputShown = true;
            res = true;
        } else if (mHaveConnection && SystemClock.uptimeMillis()
                < (mLastBindTime+TIME_TO_RECONNECT)) {
            // The client has asked to have the input method shown, but
            // we have been sitting here too long with a connection to the
            // service and no interface received, so let's disconnect/connect
            // to try to prod things along.
            EventLog.writeEvent(EventLogTags.IMF_FORCE_RECONNECT_IME, mCurMethodId,
                    SystemClock.uptimeMillis()-mLastBindTime,1);
            mContext.unbindService(this);
            mContext.bindService(mCurIntent, this, Context.BIND_AUTO_CREATE);
        }

        return res;
    }

    public boolean hideSoftInput(IInputMethodClient client, int flags,
            ResultReceiver resultReceiver) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    try {
                        // We need to check if this is the current client with
                        // focus in the window manager, to allow this call to
                        // be made before input is started in it.
                        if (!mIWindowManager.inputMethodClientHasFocus(client)) {
                            Slog.w(TAG, "Ignoring hideSoftInput of: " + client);
                            return false;
                        }
                    } catch (RemoteException e) {
                        return false;
                    }
                }

                if (DEBUG) Slog.v(TAG, "Client requesting input be hidden");
                return hideCurrentInputLocked(flags, resultReceiver);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    boolean hideCurrentInputLocked(int flags, ResultReceiver resultReceiver) {
        if ((flags&InputMethodManager.HIDE_IMPLICIT_ONLY) != 0
                && (mShowExplicitlyRequested || mShowForced)) {
            if (DEBUG) Slog.v(TAG,
                    "Not hiding: explicit show not cancelled by non-explicit hide");
            return false;
        }
        if (mShowForced && (flags&InputMethodManager.HIDE_NOT_ALWAYS) != 0) {
            if (DEBUG) Slog.v(TAG,
                    "Not hiding: forced show not cancelled by not-always hide");
            return false;
        }
        boolean res;
        if (mInputShown && mCurMethod != null) {
            executeOrSendMessage(mCurMethod, mCaller.obtainMessageOO(
                    MSG_HIDE_SOFT_INPUT, mCurMethod, resultReceiver));
            res = true;
        } else {
            res = false;
        }
        mInputShown = false;
        mShowRequested = false;
        mShowExplicitlyRequested = false;
        mShowForced = false;
        return res;
    }

    public void windowGainedFocus(IInputMethodClient client, IBinder windowToken,
            boolean viewHasFocus, boolean isTextEditor, int softInputMode,
            boolean first, int windowFlags) {
        long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mMethodMap) {
                if (DEBUG) Slog.v(TAG, "windowGainedFocus: " + client.asBinder()
                        + " viewHasFocus=" + viewHasFocus
                        + " isTextEditor=" + isTextEditor
                        + " softInputMode=#" + Integer.toHexString(softInputMode)
                        + " first=" + first + " flags=#"
                        + Integer.toHexString(windowFlags));

                if (mCurClient == null || client == null
                        || mCurClient.client.asBinder() != client.asBinder()) {
                    try {
                        // We need to check if this is the current client with
                        // focus in the window manager, to allow this call to
                        // be made before input is started in it.
                        if (!mIWindowManager.inputMethodClientHasFocus(client)) {
                            Slog.w(TAG, "Client not active, ignoring focus gain of: " + client);
                            return;
                        }
                    } catch (RemoteException e) {
                    }
                }

                if (mCurFocusedWindow == windowToken) {
                    Slog.w(TAG, "Window already focused, ignoring focus gain of: " + client);
                    return;
                }
                mCurFocusedWindow = windowToken;

                switch (softInputMode&WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE) {
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED:
                        if (!isTextEditor || (softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                                != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                            if (WindowManager.LayoutParams.mayUseInputMethod(windowFlags)) {
                                // There is no focus view, and this window will
                                // be behind any soft input window, so hide the
                                // soft input window if it is shown.
                                if (DEBUG) Slog.v(TAG, "Unspecified window will hide input");
                                hideCurrentInputLocked(InputMethodManager.HIDE_NOT_ALWAYS, null);
                            }
                        } else if (isTextEditor && (softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
                                == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                && (softInputMode &
                                        WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            // There is a focus view, and we are navigating forward
                            // into the window, so show the input window for the user.
                            if (DEBUG) Slog.v(TAG, "Unspecified window will show input");
                            showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED:
                        // Do nothing.
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN:
                        if ((softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            if (DEBUG) Slog.v(TAG, "Window asks to hide input going forward");
                            hideCurrentInputLocked(0, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN:
                        if (DEBUG) Slog.v(TAG, "Window asks to hide input");
                        hideCurrentInputLocked(0, null);
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE:
                        if ((softInputMode &
                                WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) != 0) {
                            if (DEBUG) Slog.v(TAG, "Window asks to show input going forward");
                            showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        }
                        break;
                    case WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE:
                        if (DEBUG) Slog.v(TAG, "Window asks to always show input");
                        showCurrentInputLocked(InputMethodManager.SHOW_IMPLICIT, null);
                        break;
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    public void showInputMethodPickerFromClient(IInputMethodClient client) {
        synchronized (mMethodMap) {
            if (mCurClient == null || client == null
                    || mCurClient.client.asBinder() != client.asBinder()) {
                Slog.w(TAG, "Ignoring showInputMethodDialogFromClient of: " + client);
            }

            mHandler.sendEmptyMessage(MSG_SHOW_IM_PICKER);
        }
    }

    public void setInputMethod(IBinder token, String id) {
        synchronized (mMethodMap) {
            if (token == null) {
                if (mContext.checkCallingOrSelfPermission(
                        android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException(
                            "Using null token requires permission "
                            + android.Manifest.permission.WRITE_SECURE_SETTINGS);
                }
            } else if (mCurToken != token) {
                Slog.w(TAG, "Ignoring setInputMethod of token: " + token);
                return;
            }

            long ident = Binder.clearCallingIdentity();
            try {
                setInputMethodLocked(id);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void hideMySoftInput(IBinder token, int flags) {
        synchronized (mMethodMap) {
            if (token == null || mCurToken != token) {
                Slog.w(TAG, "Ignoring hideInputMethod of token: " + token);
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                hideCurrentInputLocked(flags, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    public void showMySoftInput(IBinder token, int flags) {
        synchronized (mMethodMap) {
            if (token == null || mCurToken != token) {
                Slog.w(TAG, "Ignoring hideInputMethod of token: " + token);
                return;
            }
            long ident = Binder.clearCallingIdentity();
            try {
                showCurrentInputLocked(flags, null);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    void setEnabledSessionInMainThread(SessionState session) {
        if (mEnabledSession != session) {
            if (mEnabledSession != null) {
                try {
                    if (DEBUG) Slog.v(TAG, "Disabling: " + mEnabledSession);
                    mEnabledSession.method.setSessionEnabled(
                            mEnabledSession.session, false);
                } catch (RemoteException e) {
                }
            }
            mEnabledSession = session;
            try {
                if (DEBUG) Slog.v(TAG, "Enabling: " + mEnabledSession);
                session.method.setSessionEnabled(
                        session.session, true);
            } catch (RemoteException e) {
            }
        }
    }

    public boolean handleMessage(Message msg) {
        HandlerCaller.SomeArgs args;
        switch (msg.what) {
            case MSG_SHOW_IM_PICKER:
                showInputMethodMenu();
                return true;

            // ---------------------------------------------------------

            case MSG_UNBIND_INPUT:
                try {
                    ((IInputMethod)msg.obj).unbindInput();
                } catch (RemoteException e) {
                    // There is nothing interesting about the method dying.
                }
                return true;
            case MSG_BIND_INPUT:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    ((IInputMethod)args.arg1).bindInput((InputBinding)args.arg2);
                } catch (RemoteException e) {
                }
                return true;
            case MSG_SHOW_SOFT_INPUT:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    ((IInputMethod)args.arg1).showSoftInput(msg.arg1,
                            (ResultReceiver)args.arg2);
                } catch (RemoteException e) {
                }
                return true;
            case MSG_HIDE_SOFT_INPUT:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    ((IInputMethod)args.arg1).hideSoftInput(0,
                            (ResultReceiver)args.arg2);
                } catch (RemoteException e) {
                }
                return true;
            case MSG_ATTACH_TOKEN:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    if (DEBUG) Slog.v(TAG, "Sending attach of token: " + args.arg2);
                    ((IInputMethod)args.arg1).attachToken((IBinder)args.arg2);
                } catch (RemoteException e) {
                }
                return true;
            case MSG_CREATE_SESSION:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    ((IInputMethod)args.arg1).createSession(
                            (IInputMethodCallback)args.arg2);
                } catch (RemoteException e) {
                }
                return true;
            // ---------------------------------------------------------

            case MSG_START_INPUT:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    SessionState session = (SessionState)args.arg1;
                    setEnabledSessionInMainThread(session);
                    session.method.startInput((IInputContext)args.arg2,
                            (EditorInfo)args.arg3);
                } catch (RemoteException e) {
                }
                return true;
            case MSG_RESTART_INPUT:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    SessionState session = (SessionState)args.arg1;
                    setEnabledSessionInMainThread(session);
                    session.method.restartInput((IInputContext)args.arg2,
                            (EditorInfo)args.arg3);
                } catch (RemoteException e) {
                }
                return true;

            // ---------------------------------------------------------

            case MSG_UNBIND_METHOD:
                try {
                    ((IInputMethodClient)msg.obj).onUnbindMethod(msg.arg1);
                } catch (RemoteException e) {
                    // There is nothing interesting about the last client dying.
                }
                return true;
            case MSG_BIND_METHOD:
                args = (HandlerCaller.SomeArgs)msg.obj;
                try {
                    ((IInputMethodClient)args.arg1).onBindMethod(
                            (InputBindResult)args.arg2);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Client died receiving input method " + args.arg2);
                }
                return true;
        }
        return false;
    }

    private boolean isSystemIme(InputMethodInfo inputMethod) {
        return (inputMethod.getServiceInfo().applicationInfo.flags
                & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private boolean chooseNewDefaultIMELocked() {
        List<InputMethodInfo> enabled = getEnabledInputMethodListLocked();
        if (enabled != null && enabled.size() > 0) {
            // We'd prefer to fall back on a system IME, since that is safer.
            int i=enabled.size();
            while (i > 0) {
                i--;
                if ((enabled.get(i).getServiceInfo().applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    break;
                }
            }
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    enabled.get(i).getId());
            return true;
        }

        return false;
    }

    void buildInputMethodListLocked(ArrayList<InputMethodInfo> list,
            HashMap<String, InputMethodInfo> map) {
        list.clear();
        map.clear();

        PackageManager pm = mContext.getPackageManager();
        final Configuration config = mContext.getResources().getConfiguration();
        final boolean haveHardKeyboard = config.keyboard == Configuration.KEYBOARD_QWERTY;
        String disabledSysImes = Settings.Secure.getString(mContext.getContentResolver(),
                Secure.DISABLED_SYSTEM_INPUT_METHODS);
        if (disabledSysImes == null) disabledSysImes = "";

        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(InputMethod.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA);

        for (int i = 0; i < services.size(); ++i) {
            ResolveInfo ri = services.get(i);
            ServiceInfo si = ri.serviceInfo;
            ComponentName compName = new ComponentName(si.packageName, si.name);
            if (!android.Manifest.permission.BIND_INPUT_METHOD.equals(
                    si.permission)) {
                Slog.w(TAG, "Skipping input method " + compName
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_INPUT_METHOD);
                continue;
            }

            if (DEBUG) Slog.d(TAG, "Checking " + compName);

            try {
                InputMethodInfo p = new InputMethodInfo(mContext, ri);
                list.add(p);
                final String id = p.getId();
                map.put(id, p);

                // System IMEs are enabled by default, unless there's a hard keyboard
                // and the system IME was explicitly disabled
                if (isSystemIme(p) && (!haveHardKeyboard || disabledSysImes.indexOf(id) < 0)) {
                    setInputMethodEnabledLocked(id, true);
                }

                if (DEBUG) {
                    Slog.d(TAG, "Found a third-party input method " + p);
                }

            } catch (XmlPullParserException e) {
                Slog.w(TAG, "Unable to load input method " + compName, e);
            } catch (IOException e) {
                Slog.w(TAG, "Unable to load input method " + compName, e);
            }
        }

        String defaultIme = Settings.Secure.getString(mContext
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (!TextUtils.isEmpty(defaultIme) && !map.containsKey(defaultIme)) {
            if (chooseNewDefaultIMELocked()) {
                updateFromSettingsLocked();
            }
        }
    }

    // ----------------------------------------------------------------------

    void showInputMethodMenu() {
        if (DEBUG) Slog.v(TAG, "Show switching menu");

        final Context context = mContext;

        final PackageManager pm = context.getPackageManager();

        String lastInputMethodId = Settings.Secure.getString(context
                .getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (DEBUG) Slog.v(TAG, "Current IME: " + lastInputMethodId);

        final List<InputMethodInfo> immis = getEnabledInputMethodList();

        if (immis == null) {
            return;
        }
        
        synchronized (mMethodMap) {
            hideInputMethodMenuLocked();

            int N = immis.size();
    
            mItems = new CharSequence[N];
            mIms = new InputMethodInfo[N];
    
            int j = 0;
            for (int i = 0; i < N; ++i) {
                InputMethodInfo property = immis.get(i);
                if (property == null) {
                    continue;
                }
                mItems[j] = property.loadLabel(pm);
                mIms[j] = property;
                j++;
            }
    
            int checkedItem = 0;
            for (int i = 0; i < N; ++i) {
                if (mIms[i].getId().equals(lastInputMethodId)) {
                    checkedItem = i;
                    break;
                }
            }
    
            AlertDialog.OnClickListener adocl = new AlertDialog.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    hideInputMethodMenu();
                }
            };
    
            TypedArray a = context.obtainStyledAttributes(null,
                    com.android.internal.R.styleable.DialogPreference,
                    com.android.internal.R.attr.alertDialogStyle, 0);
            mDialogBuilder = new AlertDialog.Builder(context)
                    .setTitle(com.android.internal.R.string.select_input_method)
                    .setOnCancelListener(new OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            hideInputMethodMenu();
                        }
                    })
                    .setIcon(a.getDrawable(
                            com.android.internal.R.styleable.DialogPreference_dialogTitle));
            a.recycle();
    
            mDialogBuilder.setSingleChoiceItems(mItems, checkedItem,
                    new AlertDialog.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            synchronized (mMethodMap) {
                                if (mIms == null || mIms.length <= which) {
                                    return;
                                }
                                InputMethodInfo im = mIms[which];
                                hideInputMethodMenu();
                                if (im != null) {
                                    setInputMethodLocked(im.getId());
                                }
                            }
                        }
                    });

            mSwitchingDialog = mDialogBuilder.create();
            mSwitchingDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG);
            mSwitchingDialog.show();
        }
    }

    void hideInputMethodMenu() {
        synchronized (mMethodMap) {
            hideInputMethodMenuLocked();
        }
    }

    void hideInputMethodMenuLocked() {
        if (DEBUG) Slog.v(TAG, "Hide switching menu");

        if (mSwitchingDialog != null) {
            mSwitchingDialog.dismiss();
            mSwitchingDialog = null;
        }

        mDialogBuilder = null;
        mItems = null;
        mIms = null;
    }

    // ----------------------------------------------------------------------

    public boolean setInputMethodEnabled(String id, boolean enabled) {
        synchronized (mMethodMap) {
            if (mContext.checkCallingOrSelfPermission(
                    android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "Requires permission "
                        + android.Manifest.permission.WRITE_SECURE_SETTINGS);
            }
            
            long ident = Binder.clearCallingIdentity();
            try {
                return setInputMethodEnabledLocked(id, enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }
    
    boolean setInputMethodEnabledLocked(String id, boolean enabled) {
        // Make sure this is a valid input method.
        InputMethodInfo imm = mMethodMap.get(id);
        if (imm == null) {
            if (imm == null) {
                throw new IllegalArgumentException("Unknown id: " + mCurMethodId);
            }
        }

        StringBuilder builder = new StringBuilder(256);

        boolean removed = false;
        String firstId = null;

        // Look through the currently enabled input methods.
        String enabledStr = Settings.Secure.getString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        if (enabledStr != null) {
            final TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(enabledStr);
            while (splitter.hasNext()) {
                String curId = splitter.next();
                if (curId.equals(id)) {
                    if (enabled) {
                        // We are enabling this input method, but it is
                        // already enabled.  Nothing to do.  The previous
                        // state was enabled.
                        return true;
                    }
                    // We are disabling this input method, and it is
                    // currently enabled.  Skip it to remove from the
                    // new list.
                    removed = true;
                } else if (!enabled) {
                    // We are building a new list of input methods that
                    // doesn't contain the given one.
                    if (firstId == null) firstId = curId;
                    if (builder.length() > 0) builder.append(':');
                    builder.append(curId);
                }
            }
        }

        if (!enabled) {
            if (!removed) {
                // We are disabling the input method but it is already
                // disabled.  Nothing to do.  The previous state was
                // disabled.
                return false;
            }
            // Update the setting with the new list of input methods.
            Settings.Secure.putString(mContext.getContentResolver(),
                    Settings.Secure.ENABLED_INPUT_METHODS, builder.toString());
            // We the disabled input method is currently selected, switch
            // to another one.
            String selId = Settings.Secure.getString(mContext.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            if (id.equals(selId)) {
                Settings.Secure.putString(mContext.getContentResolver(),
                        Settings.Secure.DEFAULT_INPUT_METHOD,
                        firstId != null ? firstId : "");
            }
            // Previous state was enabled.
            return true;
        }

        // Add in the newly enabled input method.
        if (enabledStr == null || enabledStr.length() == 0) {
            enabledStr = id;
        } else {
            enabledStr = enabledStr + ':' + id;
        }

        Settings.Secure.putString(mContext.getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS, enabledStr);

        // Previous state was disabled.
        return false;
    }

    // ----------------------------------------------------------------------

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {

            pw.println("Permission Denial: can't dump InputMethodManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        IInputMethod method;
        ClientState client;

        final Printer p = new PrintWriterPrinter(pw);

        synchronized (mMethodMap) {
            p.println("Current Input Method Manager state:");
            int N = mMethodList.size();
            p.println("  Input Methods:");
            for (int i=0; i<N; i++) {
                InputMethodInfo info = mMethodList.get(i);
                p.println("  InputMethod #" + i + ":");
                info.dump(p, "    ");
            }
            p.println("  Clients:");
            for (ClientState ci : mClients.values()) {
                p.println("  Client " + ci + ":");
                p.println("    client=" + ci.client);
                p.println("    inputContext=" + ci.inputContext);
                p.println("    sessionRequested=" + ci.sessionRequested);
                p.println("    curSession=" + ci.curSession);
            }
            p.println("  mCurMethodId=" + mCurMethodId);
            client = mCurClient;
            p.println("  mCurClient=" + client + " mCurSeq=" + mCurSeq);
            p.println("  mCurFocusedWindow=" + mCurFocusedWindow);
            p.println("  mCurId=" + mCurId + " mHaveConnect=" + mHaveConnection
                    + " mBoundToMethod=" + mBoundToMethod);
            p.println("  mCurToken=" + mCurToken);
            p.println("  mCurIntent=" + mCurIntent);
            method = mCurMethod;
            p.println("  mCurMethod=" + mCurMethod);
            p.println("  mEnabledSession=" + mEnabledSession);
            p.println("  mShowRequested=" + mShowRequested
                    + " mShowExplicitlyRequested=" + mShowExplicitlyRequested
                    + " mShowForced=" + mShowForced
                    + " mInputShown=" + mInputShown);
            p.println("  mSystemReady=" + mSystemReady + " mScreenOn=" + mScreenOn);
        }

        if (client != null) {
            p.println(" ");
            pw.flush();
            try {
                client.client.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                p.println("Input method client dead: " + e);
            }
        }

        if (method != null) {
            p.println(" ");
            pw.flush();
            try {
                method.asBinder().dump(fd, args);
            } catch (RemoteException e) {
                p.println("Input method service dead: " + e);
            }
        }
    }
}
