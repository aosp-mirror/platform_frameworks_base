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
 *
 * <p>Dreams should be declared in the manifest as follows:</p>
 * <pre>
 * {@code
 * <service
 *     android:name=".MyDream"
 *     android:exported="true"
 *     android:icon="@drawable/my_icon"
 *     android:label="@string/my_dream_label" >
 *
 *     <intent-filter>
 *         <action android:name="android.intent.action.MAIN" />
 *         <category android:name="android.intent.category.DREAM" />
 *     </intent-filter>
 *
 *     <!-- Point to additional information for this dream (optional) -->
 *     <meta-data
 *         android:name="android.service.dream"
 *         android:resource="@xml/my_dream" />
 * </service>
 * }
 * </pre>
 */
public class DreamService extends Service implements Window.Callback {
    private final static boolean DEBUG = true;
    private final String TAG = DreamService.class.getSimpleName() + "[" + getClass().getSimpleName() + "]";

    /**
     * The name of the dream manager service.
     * @hide
     */
    public static final String DREAM_SERVICE = "dreams";

    /**
     * The {@link Intent} that must be declared as handled by the service.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.dreams.DreamService";

    /**
     * Name under which a Dream publishes information about itself.
     * This meta-data must reference an XML resource containing
     * a <code>&lt;{@link android.R.styleable#Dream dream}&gt;</code>
     * tag.
     */
    public static final String DREAM_META_DATA = "android.service.dream";

    private final Handler mHandler = new Handler();
    private IBinder mWindowToken;
    private Window mWindow;
    private WindowManager mWindowManager;
    private IDreamManager mSandman;
    private boolean mInteractive = false;
    private boolean mLowProfile = true;
    private boolean mFullscreen = false;
    private boolean mScreenBright = false;
    private boolean mFinished;

    // begin Window.Callback methods
    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!mInteractive) { 
            if (DEBUG) Slog.v(TAG, "Finishing on keyShortcutEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchKeyShortcutEvent(event);
    }

    /** {@inheritDoc} */
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

    /** {@inheritDoc} */
    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!mInteractive) {
            if (DEBUG) Slog.v(TAG, "Finishing on trackballEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchTrackballEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mInteractive) { 
            if (DEBUG) Slog.v(TAG, "Finishing on genericMotionEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchGenericMotionEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public View onCreatePanelView(int featureId) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public void onWindowAttributesChanged(LayoutParams attrs) {
    }

    /** {@inheritDoc} */
    @Override
    public void onContentChanged() {
    }

    /** {@inheritDoc} */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
    }

    /** {@inheritDoc} */
    @Override
    public void onAttachedToWindow() {
    }

    /** {@inheritDoc} */
    @Override
    public void onDetachedFromWindow() {
    }

    /** {@inheritDoc} */
    @Override
    public void onPanelClosed(int featureId, Menu menu) {
    }

    /** {@inheritDoc} */
    @Override
    public boolean onSearchRequested() {
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public ActionMode onWindowStartingActionMode(android.view.ActionMode.Callback callback) {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void onActionModeStarted(ActionMode mode) {
    }

    /** {@inheritDoc} */
    @Override
    public void onActionModeFinished(ActionMode mode) {
    }
    // end Window.Callback methods

    // begin public api
    /**
     * Retrieves the current {@link android.view.WindowManager} for the dream.
     * Behaves similarly to {@link android.app.Activity#getWindowManager()}.
     *
     * @return The current window manager, or null if the dream is not started.
     */
    public WindowManager getWindowManager() {
        return mWindowManager;
    }

    /**
     * Retrieves the current {@link android.view.Window} for the dream.
     * Behaves similarly to {@link android.app.Activity#getWindow()}.
     *
     * @return The current window, or null if the dream is not started.
     */
    public Window getWindow() {
        return mWindow;
    }

   /**
     * Inflates a layout resource and set it to be the content view for this Dream.
     * Behaves similarly to {@link android.app.Activity#setContentView(int)}.
     *
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
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
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
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
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
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
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
     *
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().addContentView(view, params);
    }

    /**
     * Finds a view that was identified by the id attribute from the XML that
     * was processed in {@link #onCreate}.
     *
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
     *
     * @return The view if found or null otherwise.
     */
    public View findViewById(int id) {
        return getWindow().findViewById(id);
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
     * Returns whether or not this dream is interactive.  Defaults to false.
     *
     * @see #setInteractive(boolean)
     */
    public boolean isInteractive() {
        return mInteractive;
    }

    /**
     * Sets View.SYSTEM_UI_FLAG_LOW_PROFILE on the content view.
     *
     * @param lowProfile True to set View.SYSTEM_UI_FLAG_LOW_PROFILE
     */
    public void setLowProfile(boolean lowProfile) {
        mLowProfile = lowProfile;
        int flag = View.SYSTEM_UI_FLAG_LOW_PROFILE;
        applySystemUiVisibilityFlags(mLowProfile ? flag : 0, flag);
    }

    /**
     * Returns whether or not this dream is in low profile mode. Defaults to true.
     *
     * @see #setLowProfile(boolean)
     */
    public boolean isLowProfile() {
        return getSystemUiVisibilityFlagValue(View.SYSTEM_UI_FLAG_LOW_PROFILE, mLowProfile);
    }

    /**
     * Sets View.SYSTEM_UI_FLAG_FULLSCREEN on the content view.
     *
     * @param fullscreen True to set View.SYSTEM_UI_FLAG_FULLSCREEN
     */
    public void setFullscreen(boolean fullscreen) {
        mFullscreen = fullscreen;
        int flag = View.SYSTEM_UI_FLAG_FULLSCREEN;
        applySystemUiVisibilityFlags(mFullscreen ? flag : 0, flag);
    }

    /**
     * Returns whether or not this dream is in fullscreen mode. Defaults to false.
     *
     * @see #setFullscreen(boolean)
     */
    public boolean isFullscreen() {
        return getSystemUiVisibilityFlagValue(View.SYSTEM_UI_FLAG_FULLSCREEN, mFullscreen);
    }

    /**
     * Marks this dream as keeping the screen bright while dreaming.
     *
     * @param screenBright True to keep the screen bright while dreaming.
     */
    public void setScreenBright(boolean screenBright) {
        mScreenBright = screenBright;
        int flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        applyWindowFlags(mScreenBright ? flag : 0, flag);
    }

    /**
     * Returns whether or not this dream keeps the screen bright while dreaming. Defaults to false,
     * allowing the screen to dim if necessary.
     *
     * @see #setScreenBright(boolean)
     */
    public boolean isScreenBright() {
        return getWindowFlagValue(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, mScreenBright);
    }

    /**
     * Called when this Dream is constructed. Place your initialization here.
     *
     * <p>Subclasses must call through to the superclass implementation.</p>
     */
    @Override
    public void onCreate() {
        if (DEBUG) Slog.v(TAG, "onCreate() on thread " + Thread.currentThread().getId());
        super.onCreate();
        loadSandman();
    }

    /**
     * Called when this Dream is started.  The window is created and visible at this point.
     */
    public void onStart() {
        if (DEBUG) Slog.v(TAG, "onStart()");
        // hook for subclasses
    }

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        if (DEBUG) Slog.v(TAG, "onBind() intent = " + intent);
        return new DreamServiceWrapper();
    }

    /**
     * Stops the dream, detaches from the window, and wakes up.
     *
     * <p>Subclasses must call through to the superclass implementation.</p>
     *
     * <p>After this method is called, the service will be stopped.</p>
     */
    public void finish() {
        if (DEBUG) Slog.v(TAG, "finish()");
        finishInternal();
    }

    /** {@inheritDoc} */
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
    // end public api

    private void loadSandman() {
        mSandman = IDreamManager.Stub.asInterface(ServiceManager.getService(DREAM_SERVICE));
    }

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
                    | (mScreenBright ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0)
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
                    applySystemUiVisibilityFlags(
                            (mLowProfile ? View.SYSTEM_UI_FLAG_LOW_PROFILE : 0)
                          | (mFullscreen ? View.SYSTEM_UI_FLAG_FULLSCREEN : 0),
                            View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN);
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

    private void finishInternal() {
        if (DEBUG) Slog.v(TAG, "finishInternal() mFinished = " + mFinished);
        if (mFinished) return;
        try {
            mFinished = true;

            if (mSandman != null) {
                mSandman.finishSelf(mWindowToken);
            } else {
                Slog.w(TAG, "No dream manager found");
            }
            stopSelf(); // if launched via any other means

        } catch (Throwable t) {
            Slog.w(TAG, "Crashed in finishInternal()", t);
        }
    }

    private boolean getWindowFlagValue(int flag, boolean defaultValue) {
        return mWindow == null ? defaultValue : (mWindow.getAttributes().flags & flag) != 0;
    }

    private void applyWindowFlags(int flags, int mask) {
        if (mWindow != null) {
            WindowManager.LayoutParams lp = mWindow.getAttributes();
            lp.flags = applyFlags(lp.flags, flags, mask);
            mWindow.setAttributes(lp);
            mWindowManager.updateViewLayout(mWindow.getDecorView(), lp);
        }
    }

    private boolean getSystemUiVisibilityFlagValue(int flag, boolean defaultValue) {
        View v = mWindow == null ? null : mWindow.getDecorView();
        return v == null ? defaultValue : (v.getSystemUiVisibility() & flag) != 0;
    }

    private void applySystemUiVisibilityFlags(int flags, int mask) {
        View v = mWindow == null ? null : mWindow.getDecorView();
        if (v != null) {
            v.setSystemUiVisibility(applyFlags(v.getSystemUiVisibility(), flags, mask));
        }
    }

    private int applyFlags(int oldFlags, int flags, int mask) {
        return (oldFlags&~mask) | (flags&mask);
    }

    private class DreamServiceWrapper extends IDreamService.Stub {
        public void attach(IBinder windowToken) {
            DreamService.this.attach(windowToken);
        }
    }

}
