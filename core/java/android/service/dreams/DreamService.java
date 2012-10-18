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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
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
import android.view.WindowManagerGlobal;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.policy.PolicyManager;

/**
 * Extend this class to implement a custom Dream (displayed to the user as a "Sleep Mode").
 *
 * <p>Dreams are interactive screensavers launched when a charging device is idle, or docked in a
 * desk dock. Dreams provide another modality for apps to express themselves, tailored for
 * an exhibition/lean-back experience.</p>
 *
 * <p>The Dream lifecycle is as follows:</p>
 * <ol>
 *   <li>{@link #onAttachedToWindow}
 *     <p>Use this for initial setup, such as calling {@link #setContentView setContentView()}.</li>
 *   <li>{@link #onDreamingStarted}
 *     <p>Your dream has started, so you should begin animations or other behaviors here.</li>
 *   <li>{@link #onDreamingStopped}
 *     <p>Use this to stop the things you started in {@link #onDreamingStarted}.</li>
 *   <li>{@link #onDetachedFromWindow}
 *     <p>Use this to dismantle resources your dream set up. For example, detach from handlers
 *        and listeners.</li>
 * </ol>
 *
 * <p>In addition, onCreate and onDestroy (from the Service interface) will also be called, but
 * initialization and teardown should be done by overriding the hooks above.</p>
 *
 * <p>To be available to the system, Dreams should be declared in the manifest as follows:</p>
 * <pre>
 * &lt;service
 *     android:name=".MyDream"
 *     android:exported="true"
 *     android:icon="@drawable/my_icon"
 *     android:label="@string/my_dream_label" >
 *
 *     &lt;intent-filter>
 *         &lt;action android:name="android.service.dreams.DreamService" />
 *         &lt;category android:name="android.intent.category.DEFAULT" />
 *     &lt;/intent-filter>
 *
 *     &lt;!-- Point to additional information for this dream (optional) -->
 *     &lt;meta-data
 *         android:name="android.service.dream"
 *         android:resource="@xml/my_dream" />
 * &lt;/service>
 * </pre>
 *
 * <p>If specified with the {@code &lt;meta-data&gt;} element,
 * additional information for the dream is defined using the
 * {@link android.R.styleable#Dream &lt;dream&gt;} element in a separate XML file.
 * Currently, the only addtional
 * information you can provide is for a settings activity that allows the user to configure
 * the dream behavior. For example:</p>
 * <p class="code-caption">res/xml/my_dream.xml</p>
 * <pre>
 * &lt;dream xmlns:android="http://schemas.android.com/apk/res/android"
 *     android:settingsActivity="com.example.app/.MyDreamSettingsActivity" />
 * </pre>
 * <p>This makes a Settings button available alongside your dream's listing in the
 * system settings, which when pressed opens the specified activity.</p>
 *
 *
 * <p>To specify your dream layout, call {@link #setContentView}, typically during the
 * {@link #onAttachedToWindow} callback. For example:</p>
 * <pre>
 * public class MyDream extends DreamService {
 *
 *     &#64;Override
 *     public void onAttachedToWindow() {
 *         super.onAttachedToWindow();
 *
 *         // Exit dream upon user touch
 *         setInteractive(false);
 *         // Hide system UI
 *         setFullscreen(true);
 *         // Set the dream layout
 *         setContentView(R.layout.dream);
 *     }
 * }
 * </pre>
 */
public class DreamService extends Service implements Window.Callback {
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
    private boolean mScreenBright = true;
    private boolean mFinished;

    private boolean mDebug = false;

    /**
     * @hide
     */
    public void setDebug(boolean dbg) {
        mDebug = dbg;
    }

    // begin Window.Callback methods
    /** {@inheritDoc} */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // TODO: create more flexible version of mInteractive that allows use of KEYCODE_BACK
        if (!mInteractive) {
            if (mDebug) Slog.v(TAG, "Finishing on keyEvent");
            safelyFinish();
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (mDebug) Slog.v(TAG, "Finishing on back key");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchKeyEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(TAG, "Finishing on keyShortcutEvent");
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
            if (mDebug) Slog.v(TAG, "Finishing on touchEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchTouchEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(TAG, "Finishing on trackballEvent");
            safelyFinish();
            return true;
        }
        return mWindow.superDispatchTrackballEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(TAG, "Finishing on genericMotionEvent");
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
     * Behaves similarly to {@link android.app.Activity#setContentView(android.view.View)} in an activity,
     * including using {@link ViewGroup.LayoutParams#MATCH_PARENT} as the layout height and width of the view.
     *
     * <p>Note: This requires a window, so you should usually call it during
     * {@link #onAttachedToWindow()} and never earlier (you <strong>cannot</strong> call it
     * during {@link #onCreate}).</p>
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
     * {@link android.app.Activity#setContentView(android.view.View, android.view.ViewGroup.LayoutParams)}
     * in an activity.
     *
     * <p>Note: This requires a window, so you should usually call it during
     * {@link #onAttachedToWindow()} and never earlier (you <strong>cannot</strong> call it
     * during {@link #onCreate}).</p>
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
     * @hide There is no reason to have this -- dreams can set this flag
     * on their own content view, and from there can actually do the
     * correct interactions with it (seeing when it is cleared etc).
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
     * @hide
     */
    public boolean isLowProfile() {
        return getSystemUiVisibilityFlagValue(View.SYSTEM_UI_FLAG_LOW_PROFILE, mLowProfile);
    }

    /**
     * Controls {@link android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN}
     * on the dream's window.
     *
     * @param fullscreen If true, the fullscreen flag will be set; else it
     * will be cleared.
     */
    public void setFullscreen(boolean fullscreen) {
        mFullscreen = fullscreen;
        int flag = WindowManager.LayoutParams.FLAG_FULLSCREEN;
        applyWindowFlags(mFullscreen ? flag : 0, flag);
    }

    /**
     * Returns whether or not this dream is in fullscreen mode. Defaults to false.
     *
     * @see #setFullscreen(boolean)
     */
    public boolean isFullscreen() {
        return mFullscreen;
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
     * Called when this Dream is constructed.
     */
    @Override
    public void onCreate() {
        if (mDebug) Slog.v(TAG, "onCreate() on thread " + Thread.currentThread().getId());
        super.onCreate();
    }

    /**
     * Called when the dream's window has been created and is visible and animation may now begin.
     */
    public void onDreamingStarted() {
        if (mDebug) Slog.v(TAG, "onDreamingStarted()");
        // hook for subclasses
    }

    /**
     * Called when this Dream is stopped, either by external request or by calling finish(),
     * before the window has been removed.
     */
    public void onDreamingStopped() {
        if (mDebug) Slog.v(TAG, "onDreamingStopped()");
        // hook for subclasses
    }

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        if (mDebug) Slog.v(TAG, "onBind() intent = " + intent);
        return new DreamServiceWrapper();
    }

    /**
     * Stops the dream, detaches from the window, and wakes up.
     */
    public final void finish() {
        if (mDebug) Slog.v(TAG, "finish()");
        finishInternal();
    }

    /** {@inheritDoc} */
    @Override
    public void onDestroy() {
        if (mDebug) Slog.v(TAG, "onDestroy()");
        // hook for subclasses

        // Just in case destroy came in before detach, let's take care of that now
        detach();

        super.onDestroy();
    }

    // end public api

    private void loadSandman() {
        mSandman = IDreamManager.Stub.asInterface(ServiceManager.getService(DREAM_SERVICE));
    }

    /**
     * Called by DreamController.stopDream() when the Dream is about to be unbound and destroyed.
     *
     * Must run on mHandler.
     */
    private final void detach() {
        if (mWindow == null) {
            // already detached!
            return;
        }

        try {
            onDreamingStopped();
        } catch (Throwable t) {
            Slog.w(TAG, "Crashed in onDreamingStopped()", t);
            // we were going to stop anyway
        }

        if (mDebug) Slog.v(TAG, "detach(): Removing window from window manager");
        try {
            // force our window to be removed synchronously
            mWindowManager.removeViewImmediate(mWindow.getDecorView());
            // the following will print a log message if it finds any other leaked windows
            WindowManagerGlobal.getInstance().closeAll(mWindowToken,
                    this.getClass().getName(), "Dream");
        } catch (Throwable t) {
            Slog.w(TAG, "Crashed removing window view", t);
        }

        mWindow = null;
        mWindowToken = null;
    }

    /**
     * Called when the Dream is ready to be shown.
     *
     * Must run on mHandler.
     *
     * @param windowToken A window token that will allow a window to be created in the correct layer.
     */
    private final void attach(IBinder windowToken) {
        if (mWindowToken != null) {
            Slog.e(TAG, "attach() called when already attached with token=" + mWindowToken);
            return;
        }

        if (mDebug) Slog.v(TAG, "Attached on thread " + Thread.currentThread().getId());

        if (mSandman == null) {
            loadSandman();
        }
        mWindowToken = windowToken;
        mWindow = PolicyManager.makeNewWindow(this);
        mWindow.setCallback(this);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(0xFF000000));
        mWindow.setFormat(PixelFormat.OPAQUE);

        if (mDebug) Slog.v(TAG, String.format("Attaching window token: %s to window of type %s",
                windowToken, WindowManager.LayoutParams.TYPE_DREAM));

        WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.type = WindowManager.LayoutParams.TYPE_DREAM;
        lp.token = windowToken;
        lp.windowAnimations = com.android.internal.R.style.Animation_Dream;
        lp.flags |= ( WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | (mFullscreen ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0)
                    | (mScreenBright ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0)
                    );
        mWindow.setAttributes(lp);

        if (mDebug) Slog.v(TAG, "Created and attached window: " + mWindow);

        mWindow.setWindowManager(null, windowToken, "dream", true);
        mWindowManager = mWindow.getWindowManager();

        if (mDebug) Slog.v(TAG, "Window added on thread " + Thread.currentThread().getId());
        try {
            applySystemUiVisibilityFlags(
                    (mLowProfile ? View.SYSTEM_UI_FLAG_LOW_PROFILE : 0),
                    View.SYSTEM_UI_FLAG_LOW_PROFILE);
            getWindowManager().addView(mWindow.getDecorView(), mWindow.getAttributes());
        } catch (Throwable t) {
            Slog.w(TAG, "Crashed adding window view", t);
            safelyFinish();
            return;
        }

        // start it up
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    onDreamingStarted();
                } catch (Throwable t) {
                    Slog.w(TAG, "Crashed in onDreamingStarted()", t);
                    safelyFinish();
                }
            }
        });
    }

    private void safelyFinish() {
        if (mDebug) Slog.v(TAG, "safelyFinish()");
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
        if (mDebug) Slog.v(TAG, "finishInternal() mFinished = " + mFinished);
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

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        super.dump(fd, pw, args);

        pw.print(TAG + ": ");
        if (mWindowToken == null) {
            pw.println("stopped");
        } else {
            pw.println("running (token=" + mWindowToken + ")");
        }
        pw.println("  window: " + mWindow);
        pw.print("  flags:");
        if (isInteractive()) pw.print(" interactive");
        if (isLowProfile()) pw.print(" lowprofile");
        if (isFullscreen()) pw.print(" fullscreen");
        if (isScreenBright()) pw.print(" bright");
        pw.println();
    }

    private class DreamServiceWrapper extends IDreamService.Stub {
        public void attach(final IBinder windowToken) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    DreamService.this.attach(windowToken);
                }
            });
        }
        public void detach() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    DreamService.this.detach();
                }
            });
        }
    }

}
