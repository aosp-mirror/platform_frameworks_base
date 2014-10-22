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

import android.annotation.SystemApi;
import android.app.Dialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
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
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.app.IVoiceInteractorCallback;
import com.android.internal.app.IVoiceInteractorRequest;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.SomeArgs;

import java.lang.ref.WeakReference;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

/**
 * An active interaction session, started by a {@link VoiceInteractionService}.
 */
public abstract class VoiceInteractionSession implements KeyEvent.Callback {
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
    final int[] mTmpLocation = new int[2];

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

    /**
     * @hide
     */
    @SystemApi
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

        public void sendCommandResult(boolean complete, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendCommandResult: req=" + mInterface
                        + " result=" + result);
                finishRequest();
                mCallback.deliverCommandResult(mInterface, complete, result);
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

    /**
     * @hide
     */
    @SystemApi
    public static class Caller {
        final String packageName;
        final int uid;

        Caller(String _packageName, int _uid) {
            packageName = _packageName;
            uid = _uid;
        }
    }

    static final int MSG_START_CONFIRMATION = 1;
    static final int MSG_START_COMPLETE_VOICE = 2;
    static final int MSG_START_ABORT_VOICE = 3;
    static final int MSG_START_COMMAND = 4;
    static final int MSG_SUPPORTS_COMMANDS = 5;
    static final int MSG_CANCEL = 6;

    static final int MSG_TASK_STARTED = 100;
    static final int MSG_TASK_FINISHED = 101;
    static final int MSG_CLOSE_SYSTEM_DIALOGS = 102;
    static final int MSG_DESTROY = 103;

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
                    args = (SomeArgs)msg.obj;
                    if (DEBUG) Log.d(TAG, "onCancel: req=" + ((Request) args.arg1).mInterface);
                    onCancel((Request)args.arg1);
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
            }
        }

        @Override
        public void onBackPressed() {
            VoiceInteractionSession.this.onBackPressed();
        }
    }

    final MyCallbacks mCallbacks = new MyCallbacks();

    /**
     * @hide
     * Information about where interesting parts of the input method UI appear.
     */
    @SystemApi
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

    Request newRequest(IVoiceInteractorCallback callback) {
        synchronized (this) {
            Request req = new Request(callback, this);
            mActiveRequests.put(req.mInterface.asBinder(), req);
            return req;
        }
    }

    Request removeRequest(IBinder reqInterface) {
        synchronized (this) {
            Request req = mActiveRequests.get(reqInterface);
            if (req != null) {
                mActiveRequests.remove(req);
            }
            return req;
        }
    }

    void doCreate(IVoiceInteractionManagerService service, IBinder token, Bundle args) {
        mSystemService = service;
        mToken = token;
        onCreate(args);
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
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mWindow.setContentView(mRootView);
        mRootView.getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsComputer);

        mContentFrame = (FrameLayout)mRootView.findViewById(android.R.id.content);
    }

    /**
     * @hide
     */
    @SystemApi
    public void showWindow() {
        if (DEBUG) Log.v(TAG, "Showing window: mWindowAdded=" + mWindowAdded
                + " mWindowVisible=" + mWindowVisible);

        if (mInShowWindow) {
            Log.w(TAG, "Re-entrance in to showWindow");
            return;
        }

        try {
            mInShowWindow = true;
            if (!mWindowVisible) {
                mWindowVisible = true;
                if (!mWindowAdded) {
                    mWindowAdded = true;
                    View v = onCreateContentView();
                    if (v != null) {
                        setContentView(v);
                    }
                }
                mWindow.show();
            }
        } finally {
            mWindowWasVisible = true;
            mInShowWindow = false;
        }
    }

    /**
     * @hide
     */
    @SystemApi
    public void hideWindow() {
        if (mWindowVisible) {
            mWindow.hide();
            mWindowVisible = false;
        }
    }

    /**
     * @hide
     * You can call this to customize the theme used by your IME's window.
     * This must be set before {@link #onCreate}, so you
     * will typically call it in your constructor with the resource ID
     * of your custom theme.
     */
    @SystemApi
    public void setTheme(int theme) {
        if (mWindow != null) {
            throw new IllegalStateException("Must be called before onCreate()");
        }
        mTheme = theme;
    }

    /**
     * @hide
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
    @SystemApi
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
     * @hide
     * Convenience for inflating views.
     */
    @SystemApi
    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }

    /**
     * @hide
     * Retrieve the window being used to show the session's UI.
     */
    @SystemApi
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

    /**
     * Initiatize a new session.
     *
     * @param args The arguments that were supplied to
     * {@link VoiceInteractionService#startSession VoiceInteractionService.startSession}.
     */
    public void onCreate(Bundle args) {
        mTheme = mTheme != 0 ? mTheme
                : com.android.internal.R.style.Theme_DeviceDefault_VoiceInteractionSession;
        mInflater = (LayoutInflater)mContext.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mWindow = new SoftInputWindow(mContext, "VoiceInteractionSession", mTheme,
                mCallbacks, this, mDispatcherState,
                WindowManager.LayoutParams.TYPE_VOICE_INTERACTION, Gravity.TOP, true);
        mWindow.getWindow().addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        initViews();
        mWindow.getWindow().setLayout(MATCH_PARENT, WRAP_CONTENT);
        mWindow.setToken(mToken);
    }

    /**
     * Last callback to the session as it is being finished.
     */
    public void onDestroy() {
    }

    /**
     * @hide
     * Hook in which to create the session's UI.
     */
    @SystemApi
    public View onCreateContentView() {
        return null;
    }

    public void setContentView(View view) {
        mContentFrame.removeAllViews();
        mContentFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

    }

    /**
     * @hide
     */
    @SystemApi
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * @hide
     */
    @SystemApi
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * @hide
     */
    @SystemApi
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * @hide
     */
    @SystemApi
    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        return false;
    }

    /**
     * @hide
     */
    @SystemApi
    public void onBackPressed() {
        finish();
    }

    /**
     * Sessions automatically watch for requests that all system UI be closed (such as when
     * the user presses HOME), which will appear here.  The default implementation always
     * calls {@link #finish}.
     */
    public void onCloseSystemDialogs() {
        finish();
    }

    /**
     * @hide
     * Compute the interesting insets into your UI.  The default implementation
     * uses the entire window frame as the insets.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_FRAME}.
     *
     * @param outInsets Fill in with the current UI insets.
     */
    @SystemApi
    public void onComputeInsets(Insets outInsets) {
        int[] loc = mTmpLocation;
        View decor = getWindow().getWindow().getDecorView();
        decor.getLocationInWindow(loc);
        outInsets.contentInsets.top = 0;
        outInsets.contentInsets.left = 0;
        outInsets.contentInsets.right = 0;
        outInsets.contentInsets.bottom = 0;
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME;
        outInsets.touchableRegion.setEmpty();
    }

    /**
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
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
        finish();
    }

    /**
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
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
     * @hide
     * @SystemApi
     * Called when the {@link android.app.VoiceInteractor} has asked to cancel a {@link Request}
     * that was previously delivered to {@link #onConfirm} or {@link #onCommand}.
     *
     * @param request The request that is being canceled.
     */
    public abstract void onCancel(Request request);
}
