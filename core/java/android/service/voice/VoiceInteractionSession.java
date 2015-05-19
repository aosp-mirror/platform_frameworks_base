/**
 * Copyright (C) 2014 The Android Open Source Project
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

package android.service.voice;

import android.app.AssistStructure;
import android.app.Dialog;
import android.app.Instrumentation;
import android.app.VoiceInteractor;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.Region;
import android.inputmethodservice.SoftInputWindow;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.android.internal.app.IVoiceInteractionManagerService;
import com.android.internal.app.IVoiceInteractionSessionShowCallback;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.lang.ref.WeakReference;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;

/**
 * An active voice interaction session, providing a facility for the implementation
 * to interact with the user in the voice interaction layer.  The user interface is
 * initially shown by default, and can be created be overriding {@link #onCreateContentView()}
 * in which the UI can be built.
 *
 * <p>A voice interaction session can be self-contained, ultimately calling {@link #finish}
 * when done.  It can also initiate voice interactions with applications by calling
 * {@link #startVoiceActivity}</p>.
 */
public abstract class VoiceInteractionSession implements KeyEvent.Callback,
        ComponentCallbacks2 {
    static final String TAG = "VoiceInteractionSession";
    static final boolean DEBUG = true;

    final Context mContext;
    final HandlerCaller mHandlerCaller;

    final KeyEvent.DispatcherState mDispatcherState = new KeyEvent.DispatcherState();

    IVoiceInteractionManagerService mSystemService;
    IBinder mToken;

    int mTheme = 0;
    LayoutInflater mInflater;
    TypedArray mThemeAttrs;
    View mRootView;
    FrameLayout mContentFrame;
    SoftInputWindow mWindow;

    boolean mInitialized;
    boolean mWindowAdded;
    boolean mWindowVisible;
    boolean mWindowWasVisible;
    boolean mInShowWindow;

    final ArrayMap<IBinder, Request> mActiveRequests = new ArrayMap<IBinder, Request>();

    final Insets mTmpInsets = new Insets();

    final WeakReference<VoiceInteractionSession> mWeakRef
            = new WeakReference<VoiceInteractionSession>(this);

    final IVoiceInteractor mInteractor = new IVoiceInteractor.Stub() {
        @Override
        public IVoiceInteractorRequest startConfirmation(String callingPackage,
                IVoiceInteractorCallback callback, CharSequence prompt, Bundle extras) {
            Request request = newRequest(callback);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_CONFIRMATION,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    prompt, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startPickOption(String callingPackage,
                IVoiceInteractorCallback callback, CharSequence prompt,
                VoiceInteractor.PickOptionRequest.Option[] options, Bundle extras) {
            Request request = newRequest(callback);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOOO(MSG_START_PICK_OPTION,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    prompt, options, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startCompleteVoice(String callingPackage,
                IVoiceInteractorCallback callback, CharSequence message, Bundle extras) {
            Request request = newRequest(callback);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_COMPLETE_VOICE,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    message, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startAbortVoice(String callingPackage,
                IVoiceInteractorCallback callback, CharSequence message, Bundle extras) {
            Request request = newRequest(callback);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_ABORT_VOICE,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    message, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startCommand(String callingPackage,
                IVoiceInteractorCallback callback, String command, Bundle extras) {
            Request request = newRequest(callback);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_COMMAND,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    command, extras));
            return request.mInterface;
        }

        @Override
        public boolean[] supportsCommands(String callingPackage, String[] commands) {
            Message msg = mHandlerCaller.obtainMessageIOO(MSG_SUPPORTS_COMMANDS,
                    0, new Caller(callingPackage, Binder.getCallingUid()), commands);
            SomeArgs args = mHandlerCaller.sendMessageAndWait(msg);
            if (args != null) {
                boolean[] res = (boolean[])args.arg1;
                args.recycle();
                return res;
            }
            return new boolean[commands.length];
        }
    };

    final IVoiceInteractionSession mSession = new IVoiceInteractionSession.Stub() {
        @Override
        public void show(Bundle sessionArgs, int flags,
                IVoiceInteractionSessionShowCallback showCallback) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIOO(MSG_SHOW,
                    flags, sessionArgs, showCallback));
        }

        @Override
        public void hide() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_HIDE));
        }

        @Override
        public void handleAssist(Bundle assistBundle) {
            // We want to pre-warm the AssistStructure before handing it off to the main
            // thread.  There is a strong argument to be made that it should be handed
            // through as a separate param rather than part of the assistBundle.
            if (assistBundle != null) {
                Bundle assistContext = assistBundle.getBundle(Intent.EXTRA_ASSIST_CONTEXT);
                if (assistContext != null) {
                    AssistStructure as = AssistStructure.getAssistStructure(assistContext);
                    if (as != null) {
                        as.ensureData();
                    }
                }
            }
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(MSG_HANDLE_ASSIST,
                    assistBundle));
        }

        @Override
        public void handleScreenshot(Bitmap screenshot) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(MSG_HANDLE_SCREENSHOT,
                    screenshot));
        }

        @Override
        public void taskStarted(Intent intent, int taskId) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIO(MSG_TASK_STARTED,
                    taskId, intent));
        }

        @Override
        public void taskFinished(Intent intent, int taskId) {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageIO(MSG_TASK_FINISHED,
                    taskId, intent));
        }

        @Override
        public void closeSystemDialogs() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_CLOSE_SYSTEM_DIALOGS));
        }

        @Override
        public void destroy() {
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessage(MSG_DESTROY));
        }
    };

    public static class Request {
        final IVoiceInteractorRequest mInterface = new IVoiceInteractorRequest.Stub() {
            @Override
            public void cancel() throws RemoteException {
                VoiceInteractionSession session = mSession.get();
                if (session != null) {
                    session.mHandlerCaller.sendMessage(
                            session.mHandlerCaller.obtainMessageO(MSG_CANCEL, Request.this));
                }
            }
        };
        final IVoiceInteractorCallback mCallback;
        final WeakReference<VoiceInteractionSession> mSession;

        Request(IVoiceInteractorCallback callback, VoiceInteractionSession session) {
            mCallback = callback;
            mSession = session.mWeakRef;
        }

        void finishRequest() {
            VoiceInteractionSession session = mSession.get();
            if (session == null) {
                throw new IllegalStateException("VoiceInteractionSession has been destroyed");
            }
            Request req = session.removeRequest(mInterface.asBinder());
            if (req == null) {
                throw new IllegalStateException("Request not active: " + this);
            } else if (req != this) {
                throw new IllegalStateException("Current active request " + req
                        + " not same as calling request " + this);
            }
        }

        public void sendConfirmResult(boolean confirmed, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendConfirmResult: req=" + mInterface
                        + " confirmed=" + confirmed + " result=" + result);
                finishRequest();
                mCallback.deliverConfirmationResult(mInterface, confirmed, result);
            } catch (RemoteException e) {
            }
        }

        public void sendPickOptionResult(boolean finished,
                VoiceInteractor.PickOptionRequest.Option[] selections, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendPickOptionResult: req=" + mInterface
                        + " finished=" + finished + " selections=" + selections
                        + " result=" + result);
                if (finished) {
                    finishRequest();
                }
                mCallback.deliverPickOptionResult(mInterface, finished, selections, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCompleteVoiceResult(Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendCompleteVoiceResult: req=" + mInterface
                        + " result=" + result);
                finishRequest();
                mCallback.deliverCompleteVoiceResult(mInterface, result);
            } catch (RemoteException e) {
            }
        }

        public void sendAbortVoiceResult(Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendConfirmResult: req=" + mInterface
                        + " result=" + result);
                finishRequest();
                mCallback.deliverAbortVoiceResult(mInterface, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCommandResult(boolean finished, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendCommandResult: req=" + mInterface
                        + " result=" + result);
                if (finished) {
                    finishRequest();
                }
                mCallback.deliverCommandResult(mInterface, finished, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCancelResult() {
            try {
                if (DEBUG) Log.d(TAG, "sendCancelResult: req=" + mInterface);
                finishRequest();
                mCallback.deliverCancel(mInterface);
            } catch (RemoteException e) {
            }
        }
    }

    public static class Caller {
        final String packageName;
        final int uid;

        Caller(String _packageName, int _uid) {
            packageName = _packageName;
            uid = _uid;
        }
    }

    static final int MSG_START_CONFIRMATION = 1;
    static final int MSG_START_PICK_OPTION = 2;
    static final int MSG_START_COMPLETE_VOICE = 3;
    static final int MSG_START_ABORT_VOICE = 4;
    static final int MSG_START_COMMAND = 5;
    static final int MSG_SUPPORTS_COMMANDS = 6;
    static final int MSG_CANCEL = 7;

    static final int MSG_TASK_STARTED = 100;
    static final int MSG_TASK_FINISHED = 101;
    static final int MSG_CLOSE_SYSTEM_DIALOGS = 102;
    static final int MSG_DESTROY = 103;
    static final int MSG_HANDLE_ASSIST = 104;
    static final int MSG_HANDLE_SCREENSHOT = 105;
    static final int MSG_SHOW = 106;
    static final int MSG_HIDE = 107;

    class MyCallbacks implements HandlerCaller.Callback, SoftInputWindow.Callback {
        @Override
        public void executeMessage(Message msg) {
            SomeArgs args;
            switch (msg.what) {
                case MSG_START_CONFIRMATION:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onConfirm: req=" + ((Request) args.arg2).mInterface
                            + " prompt=" + args.arg3 + " extras=" + args.arg4);
                    onConfirm((Caller)args.arg1, (Request)args.arg2, (CharSequence)args.arg3,
                            (Bundle)args.arg4);
                    break;
                case MSG_START_PICK_OPTION:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onPickOption: req=" + ((Request) args.arg2).mInterface
                            + " prompt=" + args.arg3 + " options=" + args.arg4
                            + " extras=" + args.arg5);
                    onPickOption((Caller)args.arg1, (Request)args.arg2, (CharSequence)args.arg3,
                            (VoiceInteractor.PickOptionRequest.Option[])args.arg4,
                            (Bundle)args.arg5);
                    break;
                case MSG_START_COMPLETE_VOICE:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onCompleteVoice: req=" + ((Request) args.arg2).mInterface
                            + " message=" + args.arg3 + " extras=" + args.arg4);
                    onCompleteVoice((Caller) args.arg1, (Request) args.arg2,
                            (CharSequence) args.arg3, (Bundle) args.arg4);
                    break;
                case MSG_START_ABORT_VOICE:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onAbortVoice: req=" + ((Request) args.arg2).mInterface
                            + " message=" + args.arg3 + " extras=" + args.arg4);
                    onAbortVoice((Caller) args.arg1, (Request) args.arg2, (CharSequence) args.arg3,
                            (Bundle) args.arg4);
                    break;
                case MSG_START_COMMAND:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onCommand: req=" + ((Request) args.arg2).mInterface
                            + " command=" + args.arg3 + " extras=" + args.arg4);
                    onCommand((Caller) args.arg1, (Request) args.arg2, (String) args.arg3,
                            (Bundle) args.arg4);
                    break;
                case MSG_SUPPORTS_COMMANDS:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onGetSupportedCommands: cmds=" + args.arg2);
                    args.arg1 = onGetSupportedCommands((Caller) args.arg1, (String[]) args.arg2);
                    break;
                case MSG_CANCEL:
                    if (DEBUG) Log.d(TAG, "onCancel: req=" + ((Request)msg.obj));
                    onCancel((Request)msg.obj);
                    break;
                case MSG_TASK_STARTED:
                    if (DEBUG) Log.d(TAG, "onTaskStarted: intent=" + msg.obj
                            + " taskId=" + msg.arg1);
                    onTaskStarted((Intent) msg.obj, msg.arg1);
                    break;
                case MSG_TASK_FINISHED:
                    if (DEBUG) Log.d(TAG, "onTaskFinished: intent=" + msg.obj
                            + " taskId=" + msg.arg1);
                    onTaskFinished((Intent) msg.obj, msg.arg1);
                    break;
                case MSG_CLOSE_SYSTEM_DIALOGS:
                    if (DEBUG) Log.d(TAG, "onCloseSystemDialogs");
                    onCloseSystemDialogs();
                    break;
                case MSG_DESTROY:
                    if (DEBUG) Log.d(TAG, "doDestroy");
                    doDestroy();
                    break;
                case MSG_HANDLE_ASSIST:
                    if (DEBUG) Log.d(TAG, "onHandleAssist: " + msg.obj);
                    onHandleAssist((Bundle) msg.obj);
                    break;
                case MSG_HANDLE_SCREENSHOT:
                    if (DEBUG) Log.d(TAG, "onHandleScreenshot: " + msg.obj);
                    onHandleScreenshot((Bitmap) msg.obj);
                    break;
                case MSG_SHOW:
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "doShow: args=" + args.arg1
                            + " flags=" + msg.arg1
                            + " showCallback=" + args.arg2);
                    doShow((Bundle) args.arg1, msg.arg1,
                            (IVoiceInteractionSessionShowCallback) args.arg2);
                    break;
                case MSG_HIDE:
                    if (DEBUG) Log.d(TAG, "doHide");
                    doHide();
                    break;
            }
        }

        @Override
        public void onBackPressed() {
            VoiceInteractionSession.this.onBackPressed();
        }
    }

    final MyCallbacks mCallbacks = new MyCallbacks();

    /**
     * Information about where interesting parts of the input method UI appear.
     */
    public static final class Insets {
        /**
         * This is the part of the UI that is the main content.  It is
         * used to determine the basic space needed, to resize/pan the
         * application behind.  It is assumed that this inset does not
         * change very much, since any change will cause a full resize/pan
         * of the application behind.  This value is relative to the top edge
         * of the input method window.
         */
        public final Rect contentInsets = new Rect();

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
         * Option for {@link #touchableInsets}: the region specified by
         * {@link #touchableRegion} can be touched.
         */
        public static final int TOUCHABLE_INSETS_REGION
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;

        /**
         * Determine which area of the window is touchable by the user.  May
         * be one of: {@link #TOUCHABLE_INSETS_FRAME},
         * {@link #TOUCHABLE_INSETS_CONTENT}, or {@link #TOUCHABLE_INSETS_REGION}.
         */
        public int touchableInsets;
    }

    final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
            onComputeInsets(mTmpInsets);
            info.contentInsets.set(mTmpInsets.contentInsets);
            info.visibleInsets.set(mTmpInsets.contentInsets);
            info.touchableRegion.set(mTmpInsets.touchableRegion);
            info.setTouchableInsets(mTmpInsets.touchableInsets);
        }
    };

    public VoiceInteractionSession(Context context) {
        this(context, new Handler());
    }

    public VoiceInteractionSession(Context context, Handler handler) {
        mContext = context;
        mHandlerCaller = new HandlerCaller(context, handler.getLooper(),
                mCallbacks, true);
    }

    public Context getContext() {
        return mContext;
    }

    Request newRequest(IVoiceInteractorCallback callback) {
        synchronized (this) {
            Request req = new Request(callback, this);
            mActiveRequests.put(req.mInterface.asBinder(), req);
            return req;
        }
    }

    Request removeRequest(IBinder reqInterface) {
        synchronized (this) {
            return mActiveRequests.remove(reqInterface);
        }
    }

    void doCreate(IVoiceInteractionManagerService service, IBinder token, Bundle args,
            int startFlags) {
        mSystemService = service;
        mToken = token;
        onCreate(args, startFlags);
    }

    void doShow(Bundle args, int flags, final IVoiceInteractionSessionShowCallback showCallback) {
        if (DEBUG) Log.v(TAG, "Showing window: mWindowAdded=" + mWindowAdded
                + " mWindowVisible=" + mWindowVisible);

        if (mInShowWindow) {
            Log.w(TAG, "Re-entrance in to showWindow");
            return;
        }

        try {
            mInShowWindow = true;
            if (!mWindowVisible) {
                if (!mWindowAdded) {
                    mWindowAdded = true;
                    View v = onCreateContentView();
                    if (v != null) {
                        setContentView(v);
                    }
                }
            }
            onShow(args, flags);
            if (!mWindowVisible) {
                mWindowVisible = true;
                mWindow.show();
            }
            if (showCallback != null) {
                mRootView.invalidate();
                mRootView.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                mRootView.getViewTreeObserver().removeOnPreDrawListener(this);
                                try {
                                    showCallback.onShown();
                                } catch (RemoteException e) {
                                    Log.w(TAG, "Error calling onShown", e);
                                }
                                return true;
                            }
                        });
            }
        } finally {
            mWindowWasVisible = true;
            mInShowWindow = false;
        }
    }

    void doHide() {
        if (mWindowVisible) {
            mWindow.hide();
            mWindowVisible = false;
            onHide();
        }
    }

    void doDestroy() {
        onDestroy();
        if (mInitialized) {
            mRootView.getViewTreeObserver().removeOnComputeInternalInsetsListener(
                    mInsetsComputer);
            if (mWindowAdded) {
                mWindow.dismiss();
                mWindowAdded = false;
            }
            mInitialized = false;
        }
    }

    void initViews() {
        mInitialized = true;

        mThemeAttrs = mContext.obtainStyledAttributes(android.R.styleable.VoiceInteractionSession);
        mRootView = mInflater.inflate(
                com.android.internal.R.layout.voice_interaction_session, null);
        mRootView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        mWindow.setContentView(mRootView);
        mRootView.getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsComputer);

        mContentFrame = (FrameLayout)mRootView.findViewById(android.R.id.content);
    }

    public void show() {
        try {
            mSystemService.showSessionFromSession(mToken, null, 0);
        } catch (RemoteException e) {
        }
    }

    public void hide() {
        try {
            mSystemService.hideSessionFromSession(mToken);
        } catch (RemoteException e) {
        }
    }

    /** TODO: remove */
    public void showWindow() {
    }

    /** TODO: remove */
    public void hideWindow() {
    }

    /**
     * You can call this to customize the theme used by your IME's window.
     * This must be set before {@link #onCreate}, so you
     * will typically call it in your constructor with the resource ID
     * of your custom theme.
     */
    public void setTheme(int theme) {
        if (mWindow != null) {
            throw new IllegalStateException("Must be called before onCreate()");
        }
        mTheme = theme;
    }

    /**
     * Ask that a new activity be started for voice interaction.  This will create a
     * new dedicated task in the activity manager for this voice interaction session;
     * this means that {@link Intent#FLAG_ACTIVITY_NEW_TASK Intent.FLAG_ACTIVITY_NEW_TASK}
     * will be set for you to make it a new task.
     *
     * <p>The newly started activity will be displayed to the user in a special way, as
     * a layer under the voice interaction UI.</p>
     *
     * <p>As the voice activity runs, it can retrieve a {@link android.app.VoiceInteractor}
     * through which it can perform voice interactions through your session.  These requests
     * for voice interactions will appear as callbacks on {@link #onGetSupportedCommands},
     * {@link #onConfirm}, {@link #onCommand}, and {@link #onCancel}.
     *
     * <p>You will receive a call to {@link #onTaskStarted} when the task starts up
     * and {@link #onTaskFinished} when the last activity has finished.
     *
     * @param intent The Intent to start this voice interaction.  The given Intent will
     * always have {@link Intent#CATEGORY_VOICE Intent.CATEGORY_VOICE} added to it, since
     * this is part of a voice interaction.
     */
    public void startVoiceActivity(Intent intent) {
        if (mToken == null) {
            throw new IllegalStateException("Can't call before onCreate()");
        }
        try {
            intent.migrateExtraStreamToClipData();
            intent.prepareToLeaveProcess();
            int res = mSystemService.startVoiceActivity(mToken, intent,
                    intent.resolveType(mContext.getContentResolver()));
            Instrumentation.checkStartActivityResult(res, intent);
        } catch (RemoteException e) {
        }
    }

    /**
     * Set whether this session will keep the device awake while it is running a voice
     * activity.  By default, the system holds a wake lock for it while in this state,
     * so that it can work even if the screen is off.  Setting this to false removes that
     * wake lock, allowing the CPU to go to sleep.  This is typically used if the
     * session decides it has been waiting too long for a response from the user and
     * doesn't want to let this continue to drain the battery.
     *
     * <p>Passing false here will release the wake lock, and you can call later with
     * true to re-acquire it.  It will also be automatically re-acquired for you each
     * time you start a new voice activity task -- that is when you call
     * {@link #startVoiceActivity}.</p>
     */
    public void setKeepAwake(boolean keepAwake) {
        try {
            mSystemService.setKeepAwake(mToken, keepAwake);
        } catch (RemoteException e) {
        }
    }

    /**
     * Convenience for inflating views.
     */
    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }

    /**
     * Retrieve the window being used to show the session's UI.
     */
    public Dialog getWindow() {
        return mWindow;
    }

    /**
     * Finish the session.
     */
    public void finish() {
        if (mToken == null) {
            throw new IllegalStateException("Can't call before onCreate()");
        }
        hideWindow();
        try {
            mSystemService.finish(mToken);
        } catch (RemoteException e) {
        }
    }

    /** @hide */
    public void onCreate(Bundle args) {
        mTheme = mTheme != 0 ? mTheme
                : com.android.internal.R.style.Theme_DeviceDefault_VoiceInteractionSession;
        mInflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mWindow = new SoftInputWindow(mContext, "VoiceInteractionSession", mTheme,
                mCallbacks, this, mDispatcherState,
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION, Gravity.BOTTOM, true);
        mWindow.getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        initViews();
        mWindow.getWindow().setLayout(MATCH_PARENT, MATCH_PARENT);
        mWindow.setToken(mToken);
    }

    /**
     * Initiatize a new session.  The given args and showFlags are the initial values
     * passed to {@link VoiceInteractionService#showSession VoiceInteractionService.showSession},
     * if possible.  Normally you should handle these in {@link #onShow}.
     */
    public void onCreate(Bundle args, int showFlags) {
        onCreate(args);
    }

    /**
     * Called when the session UI is going to be shown.  This is called after
     * {@link #onCreateContentView} (if the session's content UI needed to be created) and
     * immediately prior to the window being shown.  This may be called while the window
     * is already shown, if a show request has come in while it is shown, to allow you to
     * update the UI to match the new show arguments.
     *
     * @param args The arguments that were supplied to
     * {@link VoiceInteractionService#showSession VoiceInteractionService.showSession}.
     * @param showFlags The show flags originally provided to
     * {@link VoiceInteractionService#showSession VoiceInteractionService.showSession}.
     */
    public void onShow(Bundle args, int showFlags) {
    }

    /**
     * Called immediately after stopping to show the session UI.
     */
    public void onHide() {
    }

    /**
     * Last callback to the session as it is being finished.
     */
    public void onDestroy() {
    }

    /**
     * Hook in which to create the session's UI.
     */
    public View onCreateContentView() {
        return null;
    }

    public void setContentView(View view) {
        mContentFrame.removeAllViews();
        mContentFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

    }

    public void onHandleAssist(Bundle assistBundle) {
    }

    /** @hide */
    public void onHandleScreenshot(Bitmap screenshot) {
    }

    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        return false;
    }

    /**
     * Called when the user presses the back button while focus is in the session UI.  Note
     * that this will only happen if the session UI has requested input focus in its window;
     * otherwise, the back key will go to whatever window has focus and do whatever behavior
     * it normally has there.
     */
    public void onBackPressed() {
        hide();
    }

    /**
     * Sessions automatically watch for requests that all system UI be closed (such as when
     * the user presses HOME), which will appear here.  The default implementation always
     * calls {@link #finish}.
     */
    public void onCloseSystemDialogs() {
        hide();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    public void onLowMemory() {
    }

    @Override
    public void onTrimMemory(int level) {
    }

    /**
     * Compute the interesting insets into your UI.  The default implementation
     * sets {@link Insets#contentInsets outInsets.contentInsets.top} to the height
     * of the window, meaning it should not adjust content underneath.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_FRAME}, meaning it consumes all touch
     * events within its window frame.
     *
     * @param outInsets Fill in with the current UI insets.
     */
    public void onComputeInsets(Insets outInsets) {
        outInsets.contentInsets.left = 0;
        outInsets.contentInsets.bottom = 0;
        outInsets.contentInsets.right = 0;
        View decor = getWindow().getWindow().getDecorView();
        outInsets.contentInsets.top = decor.getHeight();
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME;
        outInsets.touchableRegion.setEmpty();
    }

    /**
     * Called when a task initiated by {@link #startVoiceActivity(android.content.Intent)}
     * has actually started.
     *
     * @param intent The original {@link Intent} supplied to
     * {@link #startVoiceActivity(android.content.Intent)}.
     * @param taskId Unique ID of the now running task.
     */
    public void onTaskStarted(Intent intent, int taskId) {
    }

    /**
     * Called when the last activity of a task initiated by
     * {@link #startVoiceActivity(android.content.Intent)} has finished.  The default
     * implementation calls {@link #finish()} on the assumption that this represents
     * the completion of a voice action.  You can override the implementation if you would
     * like a different behavior.
     *
     * @param intent The original {@link Intent} supplied to
     * {@link #startVoiceActivity(android.content.Intent)}.
     * @param taskId Unique ID of the finished task.
     */
    public void onTaskFinished(Intent intent, int taskId) {
        hide();
    }

    /**
     * Request to query for what extended commands the session supports.
     *
     * @param caller Who is making the request.
     * @param commands An array of commands that are being queried.
     * @return Return an array of booleans indicating which of each entry in the
     * command array is supported.  A true entry in the array indicates the command
     * is supported; false indicates it is not.  The default implementation returns
     * an array of all false entries.
     */
    public boolean[] onGetSupportedCommands(Caller caller, String[] commands) {
        return new boolean[commands.length];
    }

    /**
     * Request to confirm with the user before proceeding with an unrecoverable operation,
     * corresponding to a {@link android.app.VoiceInteractor.ConfirmationRequest
     * VoiceInteractor.ConfirmationRequest}.
     *
     * @param caller Who is making the request.
     * @param request The active request.
     * @param prompt The prompt informing the user of what will happen, as per
     * {@link android.app.VoiceInteractor.ConfirmationRequest VoiceInteractor.ConfirmationRequest}.
     * @param extras Any additional information, as per
     * {@link android.app.VoiceInteractor.ConfirmationRequest VoiceInteractor.ConfirmationRequest}.
     */
    public abstract void onConfirm(Caller caller, Request request, CharSequence prompt,
            Bundle extras);

    /**
     * Request for the user to pick one of N options, corresponding to a
     * {@link android.app.VoiceInteractor.PickOptionRequest VoiceInteractor.PickOptionRequest}.
     *
     * @param caller Who is making the request.
     * @param request The active request.
     * @param prompt The prompt informing the user of what they are picking, as per
     * {@link android.app.VoiceInteractor.PickOptionRequest VoiceInteractor.PickOptionRequest}.
     * @param options The set of options the user is picking from, as per
     * {@link android.app.VoiceInteractor.PickOptionRequest VoiceInteractor.PickOptionRequest}.
     * @param extras Any additional information, as per
     * {@link android.app.VoiceInteractor.PickOptionRequest VoiceInteractor.PickOptionRequest}.
     */
    public abstract void onPickOption(Caller caller, Request request, CharSequence prompt,
            VoiceInteractor.PickOptionRequest.Option[] options, Bundle extras);

    /**
     * Request to complete the voice interaction session because the voice activity successfully
     * completed its interaction using voice.  Corresponds to
     * {@link android.app.VoiceInteractor.CompleteVoiceRequest
     * VoiceInteractor.CompleteVoiceRequest}.  The default implementation just sends an empty
     * confirmation back to allow the activity to exit.
     *
     * @param caller Who is making the request.
     * @param request The active request.
     * @param message The message informing the user of the problem, as per
     * {@link android.app.VoiceInteractor.CompleteVoiceRequest
     * VoiceInteractor.CompleteVoiceRequest}.
     * @param extras Any additional information, as per
     * {@link android.app.VoiceInteractor.CompleteVoiceRequest
     * VoiceInteractor.CompleteVoiceRequest}.
     */
    public void onCompleteVoice(Caller caller, Request request, CharSequence message,
           Bundle extras) {
        request.sendCompleteVoiceResult(null);
    }

    /**
     * Request to abort the voice interaction session because the voice activity can not
     * complete its interaction using voice.  Corresponds to
     * {@link android.app.VoiceInteractor.AbortVoiceRequest
     * VoiceInteractor.AbortVoiceRequest}.  The default implementation just sends an empty
     * confirmation back to allow the activity to exit.
     *
     * @param caller Who is making the request.
     * @param request The active request.
     * @param message The message informing the user of the problem, as per
     * {@link android.app.VoiceInteractor.AbortVoiceRequest VoiceInteractor.AbortVoiceRequest}.
     * @param extras Any additional information, as per
     * {@link android.app.VoiceInteractor.AbortVoiceRequest VoiceInteractor.AbortVoiceRequest}.
     */
    public void onAbortVoice(Caller caller, Request request, CharSequence message, Bundle extras) {
        request.sendAbortVoiceResult(null);
    }

    /**
     * Process an arbitrary extended command from the caller,
     * corresponding to a {@link android.app.VoiceInteractor.CommandRequest
     * VoiceInteractor.CommandRequest}.
     *
     * @param caller Who is making the request.
     * @param request The active request.
     * @param command The command that is being executed, as per
     * {@link android.app.VoiceInteractor.CommandRequest VoiceInteractor.CommandRequest}.
     * @param extras Any additional information, as per
     * {@link android.app.VoiceInteractor.CommandRequest VoiceInteractor.CommandRequest}.
     */
    public abstract void onCommand(Caller caller, Request request, String command, Bundle extras);

    /**
     * Called when the {@link android.app.VoiceInteractor} has asked to cancel a {@link Request}
     * that was previously delivered to {@link #onConfirm} or {@link #onCommand}.
     *
     * @param request The request that is being canceled.
     */
    public abstract void onCancel(Request request);
}
