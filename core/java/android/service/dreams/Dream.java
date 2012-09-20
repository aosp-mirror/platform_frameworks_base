/**
 * Copyright (C) 2012 The Android Open Source Project
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
package android.service.dreams;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.policy.PolicyManager;

/**
 * Extend this class to implement a custom Dream.
 *
 * <p>Dreams are interactive screensavers launched when a charging device is idle, or docked in a
 * desk dock. Dreams provide another modality for apps to express themselves, tailored for
 * an exhibition/lean-back experience.</p>
 */
public class Dream extends Service implements Window.Callback {
    private final static boolean DEBUG = true;
    private final String TAG = Dream.class.getSimpleName() + "[" + getClass().getSimpleName() + "]";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_WALLPAPER} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.dreams.Dream";

    /** Service meta-data key for declaring an optional configuration activity. */
    public static final String METADATA_NAME_CONFIG_ACTIVITY =
            "android.service.dreams.config_activity";

    /**
     * Broadcast Action: Sent after the system starts dreaming.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * It is only sent to registered receivers.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DREAMING_STARTED = "android.intent.action.DREAMING_STARTED";

    /**
     * Broadcast Action: Sent after the system stops dreaming.
     *
     * <p class="note">This is a protected intent that can only be sent by the system.
     * It is only sent to registered receivers.</p>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DREAMING_STOPPED = "android.intent.action.DREAMING_STOPPED";

    private final Handler mHandler = new Handler();
    private IBinder mWindowToken;
    private Window mWindow;
    private WindowManager mWindowManager;
    private IDreamManager mSandman;
    private boolean mInteractive;
    private boolean mFinished;

    // begin Window.Callback methods
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // TODO: create more flexible version of mInteractive that allows use of KEYCODE_BACK
        if (!mInteractive) {
            if (DEBUG) Slog.v(TAG, "Finishing on keyEvent");
            safelyFinish();
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (DEBUG) Slog.v(TAG, "Finishing on back key");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!mInteractive) { 
            if (DEBUG) Slog.v(TAG, "Finishing on keyShortcutEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // TODO: create more flexible version of mInteractive that allows clicks 
        // but finish()es on any other kind of activity
        if (!mInteractive) { 
            if (DEBUG) Slog.v(TAG, "Finishing on touchEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!mInteractive) {
            if (DEBUG) Slog.v(TAG, "Finishing on trackballEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mInteractive) { 
            if (DEBUG) Slog.v(TAG, "Finishing on genericMotionEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return false;
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return null;
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return false;
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return false;
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return false;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return false;
    }

    @Override
    public void onWindowAttributesChanged(LayoutParams attrs) {

    }

    @Override
    public void onContentChanged() {

    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {

    }

    @Override
    public void onAttachedToWindow() {
    }

    @Override
    public void onDetachedFromWindow() {
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
    }

    @Override
    public boolean onSearchRequested() {
        return false;
    }

    @Override
    public ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) {
        return null;
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
    }
    // end Window.Callback methods

    public WindowManager getWindowManager() {
        return mWindowManager;
    }

    public Window getWindow() {
        return mWindow;
    }

   /**
     * Inflates a layout resource and set it to be the content view for this Dream.
     * Behaves similarly to {@link android.app.Activity#setContentView(int)}.
     *
     * @param layoutResID Resource ID to be inflated.
     * 
     * @see #setContentView(android.view.View)
     * @see #setContentView(android.view.View, android.view.ViewGroup.LayoutParams)
     */
    public void setContentView(int layoutResID) {
        getWindow().setContentView(layoutResID);
    }

    /**
     * Sets a view to be the content view for this Dream.
     * Behaves similarly to {@link android.app.Activity#setContentView(android.view.View)},
     * including using {@link ViewGroup.LayoutParams#MATCH_PARENT} as the layout height and width of the view.
     * 
     * @param view The desired content to display.
     *
     * @see #setContentView(int)
     * @see #setContentView(android.view.View, android.view.ViewGroup.LayoutParams)
     */
    public void setContentView(View view) {
        getWindow().setContentView(view);
    }

    /**
     * Sets a view to be the content view for this Dream.
     * Behaves similarly to 
     * {@link android.app.Activity#setContentView(android.view.View, android.view.ViewGroup.LayoutParams)}.
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     *
     * @see #setContentView(android.view.View)
     * @see #setContentView(int)
     */
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().setContentView(view, params);
    }

    /**
     * Adds a view to the Dream's window, leaving other content views in place.
     * 
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().addContentView(view, params);
    }

    /**
     * Marks this dream as interactive to receive input events.
     *
     * <p>Non-interactive dreams (default) will dismiss on the first input event.</p>
     *
     * <p>Interactive dreams should call {@link #finish()} to dismiss themselves.</p>
     *
     * @param interactive True if this dream will handle input events.
     */
    public void setInteractive(boolean interactive) {
        mInteractive = interactive;
    }

    /**
     * Returns whether or not this dream is interactive.
     */
    public boolean isInteractive() {
        return mInteractive;
    }

    /** Convenience method for setting View.SYSTEM_UI_FLAG_LOW_PROFILE on the content view. */
    protected void lightsOut() {
        // turn the lights down low
        final View v = mWindow.getDecorView();
        if (v != null) {
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE 
                                  | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

    /**
     * Finds a view that was identified by the id attribute from the XML that
     * was processed in {@link #onCreate}.
     *
     * @return The view if found or null otherwise.
     */
    public View findViewById(int id) {
        return getWindow().findViewById(id);
    }

    /**
     * Called when this Dream is constructed. Place your initialization here.
     *
     * Subclasses must call through to the superclass implementation.
     */
    @Override
    public void onCreate() {
        if (DEBUG) Slog.v(TAG, "onCreate() on thread " + Thread.currentThread().getId());
        super.onCreate();
        loadSandman();
    }

    /**
     * Called when this Dream is started.
     */
    public void onStart() {
        // hook for subclasses
        Slog.v(TAG, "called Dream.onStart()");
    }

    private void loadSandman() {
        mSandman = IDreamManager.Stub.asInterface(ServiceManager.getService("dreams"));
    }

    /**
     * Creates a new dream window, attaches the current content view, and shows it.
     * 
     * @param windowToken Binder to attach to the window to allow access to the correct window type.
     * @hide
     */
    private final void attach(IBinder windowToken) {
        if (DEBUG) Slog.v(TAG, "Attached on thread " + Thread.currentThread().getId());

        if (mSandman == null) {
            Slog.w(TAG, "No dream manager found, super.onCreate may not have been called");
            loadSandman();
        }
        mWindowToken = windowToken;
        mWindow = PolicyManager.makeNewWindow(this);
        mWindow.setCallback(this);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(0xFF000000));

        if (DEBUG) Slog.v(TAG, String.format("Attaching window token: %s to window of type %s",
                windowToken, WindowManager.LayoutParams.TYPE_DREAM));

        WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.type = WindowManager.LayoutParams.TYPE_DREAM;
        lp.token = windowToken;
        lp.windowAnimations = com.android.internal.R.style.Animation_Dream;
        lp.flags |= ( WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON 
                    );
        mWindow.setAttributes(lp);

        if (DEBUG) Slog.v(TAG, "Created and attached window: " + mWindow);

        mWindow.setWindowManager(null, windowToken, "dream", true);
        mWindowManager = mWindow.getWindowManager();

        // now make it visible (on the ui thread)
        mHandler.post(new Runnable(){
            @Override
            public void run() {
                if (DEBUG) Slog.v(TAG, "Window added on thread " + Thread.currentThread().getId());

                try {
                    getWindowManager().addView(mWindow.getDecorView(), mWindow.getAttributes());
                } catch (Throwable t) {
                    Slog.w("Crashed adding window view", t);
                    safelyFinish();
                    return;
                }

                // start it up
                try {
                    onStart();
                } catch (Throwable t) {
                    Slog.w("Crashed in onStart()", t);
                    safelyFinish();
                }
            }});
    }

    private void safelyFinish() {
        if (DEBUG) Slog.v(TAG, "safelyFinish()");
        try {
            finish();
        } catch (Throwable t) {
            Slog.w(TAG, "Crashed in safelyFinish()", t);
            finishInternal();
            return;
        }

        if (!mFinished) {
            Slog.w(TAG, "Bad dream, did not call super.finish()");
            finishInternal();
        }
    }

    /**
     * Stops the dream, detaches from the window, and wakes up.
     *
     * Subclasses must call through to the superclass implementation.
     *
     * <p>After this method is called, the service will be stopped.</p>
     */
    public void finish() {
        if (DEBUG) Slog.v(TAG, "finish()");
        finishInternal();
    }

    private void finishInternal() {
        if (DEBUG) Slog.v(TAG, "finishInternal() mFinished = " + mFinished);
        if (mFinished) return;
        try {
            mFinished = true;

            if (mSandman != null) {
                mSandman.awakenSelf(mWindowToken);
            } else {
                Slog.w(TAG, "No dream manager found");
            }
            stopSelf(); // if launched via any other means

        } catch (Throwable t) {
            Slog.w(TAG, "Crashed in finishInternal()", t);
        }
    }

    @Override
    public void onDestroy() {
        if (DEBUG) Slog.v(TAG, "onDestroy()");
        super.onDestroy();

        if (DEBUG) Slog.v(TAG, "Removing window");
        try {
            mWindowManager.removeView(mWindow.getDecorView());
        } catch (Throwable t) {
            Slog.w(TAG, "Crashed removing window view", t);
        }
    }

    @Override
    public final IBinder onBind(Intent intent) {
        if (DEBUG) Slog.v(TAG, "onBind() intent = " + intent);
        return new DreamServiceWrapper();
    }

    private class DreamServiceWrapper extends IDreamService.Stub {
        public void attach(IBinder windowToken) {
            Dream.this.attach(windowToken);
        }
    }

}
