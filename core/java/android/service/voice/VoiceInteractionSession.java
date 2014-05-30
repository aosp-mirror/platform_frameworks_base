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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

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

    final IVoiceInteractor mInteractor = new IVoiceInteractor.Stub() {
        @Override
        public IVoiceInteractorRequest startConfirmation(String callingPackage,
                IVoiceInteractorCallback callback, String prompt, Bundle extras) {
            Request request = findRequest(callback, true);
            mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageOOOO(MSG_START_CONFIRMATION,
                    new Caller(callingPackage, Binder.getCallingUid()), request,
                    prompt, extras));
            return request.mInterface;
        }

        @Override
        public IVoiceInteractorRequest startCommand(String callingPackage,
                IVoiceInteractorCallback callback, String command, Bundle extras) {
            Request request = findRequest(callback, true);
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

    public static class Request {
        final IVoiceInteractorRequest mInterface = new IVoiceInteractorRequest.Stub() {
            @Override
            public void cancel() throws RemoteException {
                mHandlerCaller.sendMessage(mHandlerCaller.obtainMessageO(MSG_CANCEL, Request.this));
            }
        };
        final IVoiceInteractorCallback mCallback;
        final HandlerCaller mHandlerCaller;
        Request(IVoiceInteractorCallback callback, HandlerCaller handlerCaller) {
            mCallback = callback;
            mHandlerCaller = handlerCaller;
        }

        public void sendConfirmResult(boolean confirmed, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendConfirmResult: req=" + mInterface
                        + " confirmed=" + confirmed + " result=" + result);
                mCallback.deliverConfirmationResult(mInterface, confirmed, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCommandResult(boolean complete, Bundle result) {
            try {
                if (DEBUG) Log.d(TAG, "sendCommandResult: req=" + mInterface
                        + " result=" + result);
                mCallback.deliverCommandResult(mInterface, complete, result);
            } catch (RemoteException e) {
            }
        }

        public void sendCancelResult() {
            try {
                if (DEBUG) Log.d(TAG, "sendCancelResult: req=" + mInterface);
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
    static final int MSG_START_COMMAND = 2;
    static final int MSG_SUPPORTS_COMMANDS = 3;
    static final int MSG_CANCEL = 4;

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
                    onConfirm((Caller)args.arg1, (Request)args.arg2, (String)args.arg3,
                            (Bundle)args.arg4);
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

    Request findRequest(IVoiceInteractorCallback callback, boolean newRequest) {
        synchronized (this) {
            Request req = mActiveRequests.get(callback.asBinder());
            if (req != null) {
                if (newRequest) {
                    throw new IllegalArgumentException("Given request callback " + callback
                            + " is already active");
                }
                return req;
            }
            req = new Request(callback, mHandlerCaller);
            mActiveRequests.put(callback.asBinder(), req);
            return req;
        }
    }

    void doCreate(IVoiceInteractionManagerService service, IBinder token, Bundle args) {
        mSystemService = service;
        mToken = token;
        onCreate(args);
    }

    void doDestroy() {
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

    public void hideWindow() {
        if (mWindowVisible) {
            mWindow.hide();
            mWindowVisible = false;
        }
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

    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }

    public Dialog getWindow() {
        return mWindow;
    }

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

    public void onDestroy() {
    }

    public View onCreateContentView() {
        return null;
    }

    public void setContentView(View view) {
        mContentFrame.removeAllViews();
        mContentFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

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

    public void onBackPressed() {
        finish();
    }

    public void onCloseSystemDialogs() {
        finish();
    }

    /**
     * Compute the interesting insets into your UI.  The default implementation
     * uses the entire window frame as the insets.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_FRAME}.
     *
     * @param outInsets Fill in with the current UI insets.
     */
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

    public void onTaskStarted(Intent intent, int taskId) {
    }

    public void onTaskFinished(Intent intent, int taskId) {
        finish();
    }

    public abstract boolean[] onGetSupportedCommands(Caller caller, String[] commands);
    public abstract void onConfirm(Caller caller, Request request, String prompt, Bundle extras);
    public abstract void onCommand(Caller caller, Request request, String command, Bundle extras);
    public abstract void onCancel(Request request);
}
