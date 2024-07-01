/*
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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.service.dreams.Flags.dreamHandlesConfirmKeys;
import static android.service.dreams.Flags.dreamHandlesBeingObscured;

import android.annotation.FlaggedApi;
import android.annotation.IdRes;
import android.annotation.IntDef;
import android.annotation.LayoutRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Service;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.controls.flags.Flags;
import android.service.dreams.utils.DreamAccessibility;
import android.util.Log;
import android.util.MathUtils;
import android.util.Slog;
import android.view.ActionMode;
import android.view.Display;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

/**
 * Extend this class to implement a custom dream (available to the user as a "Daydream").
 *
 * <p>Dreams are interactive screensavers launched when a charging device is idle, or docked in a
 * desk dock. Dreams provide another modality for apps to express themselves, tailored for
 * an exhibition/lean-back experience.</p>
 *
 * <p>The {@code DreamService} lifecycle is as follows:</p>
 * <ol>
 *   <li>{@link #onAttachedToWindow}
 *     <p>Use this for initial setup, such as calling {@link #setContentView setContentView()}.</li>
 *   <li>{@link #onDreamingStarted}
 *     <p>Your dream has started, so you should begin animations or other behaviors here.</li>
 *   <li>{@link #onDreamingStopped}
 *     <p>Use this to stop the things you started in {@link #onDreamingStarted}.</li>
 *   <li>{@link #onDetachedFromWindow}
 *     <p>Use this to dismantle resources (for example, detach from handlers
 *        and listeners).</li>
 * </ol>
 *
 * <p>In addition, onCreate and onDestroy (from the Service interface) will also be called, but
 * initialization and teardown should be done by overriding the hooks above.</p>
 *
 * <p>To be available to the system, your {@code DreamService} should be declared in the
 * manifest as follows:</p>
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
 * <p>If specified with the {@code <meta-data>} element,
 * additional information for the dream is defined using the
 * {@link android.R.styleable#Dream &lt;dream&gt;} element in a separate XML file.
 * Currently, the only additional
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
 *
 * <p>When targeting api level 21 and above, you must declare the service in your manifest file
 * with the {@link android.Manifest.permission#BIND_DREAM_SERVICE} permission. For example:</p>
 * <pre>
 * &lt;service
 *     android:name=".MyDream"
 *     android:exported="true"
 *     android:icon="@drawable/my_icon"
 *     android:label="@string/my_dream_label"
 *     android:permission="android.permission.BIND_DREAM_SERVICE">
 *   &lt;intent-filter>
 *     &lt;action android:name=”android.service.dreams.DreamService” />
 *     &lt;category android:name=”android.intent.category.DEFAULT” />
 *   &lt;/intent-filter>
 * &lt;/service>
 * </pre>
 */
public class DreamService extends Service implements Window.Callback {
    private static final String TAG = DreamService.class.getSimpleName();
    private final String mTag = TAG + "[" + getClass().getSimpleName() + "]";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * The name of the dream manager service.
     *
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

    /**
     * Name of the root tag under which a Dream defines its metadata in an XML file.
     */
    private static final String DREAM_META_DATA_ROOT_TAG = "dream";

    /**
     * The default value for whether to show complications on the overlay.
     *
     * @hide
     */
    public static final boolean DEFAULT_SHOW_COMPLICATIONS = false;

    /**
     * The default value for dream category
     * @hide
     */
    @VisibleForTesting
    public static final int DREAM_CATEGORY_DEFAULT = 0;

    /**
     * Dream category for Low Light Dream
     *
     * @hide
     */
    public static final int DREAM_CATEGORY_LOW_LIGHT = 1 << 0;

    /**
     * Dream category for Home Panel Dream
     *
     * @hide
     */
    public static final int DREAM_CATEGORY_HOME_PANEL = 1 << 1;

    /** @hide */
    @IntDef(flag = true, prefix = {"DREAM_CATEGORY"}, value = {
        DREAM_CATEGORY_DEFAULT,
        DREAM_CATEGORY_LOW_LIGHT,
        DREAM_CATEGORY_HOME_PANEL
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface DreamCategory {}

    /**
     * The name of the extra where the dream overlay component is stored.
     */
    static final String EXTRA_DREAM_OVERLAY_COMPONENT =
            "android.service.dream.DreamService.dream_overlay_component";

    private final IDreamManager mDreamManager;
    private final Handler mHandler;
    private IBinder mDreamToken;
    private Window mWindow;
    private Activity mActivity;
    private boolean mInteractive;
    private boolean mFullscreen;
    private boolean mScreenBright = true;
    private boolean mStarted;
    private boolean mWaking;
    private boolean mFinished;
    private boolean mCanDoze;
    private boolean mDozing;
    private boolean mWindowless;
    private boolean mPreviewMode;
    private int mDozeScreenState = Display.STATE_UNKNOWN;
    private @Display.StateReason int mDozeScreenStateReason = Display.STATE_REASON_UNKNOWN;
    private int mDozeScreenBrightness = PowerManager.BRIGHTNESS_DEFAULT;

    private boolean mDebug = false;

    private ComponentName mDreamComponent;
    private DreamAccessibility mDreamAccessibility;
    private boolean mShouldShowComplications;

    private DreamServiceWrapper mDreamServiceWrapper;
    private Runnable mDispatchAfterOnAttachedToWindow;

    private DreamOverlayConnectionHandler mOverlayConnection;

    private IDreamOverlayCallback mOverlayCallback;

    private Integer mTrackingConfirmKey = null;

    private boolean mRedirectWake;

    private final Injector mInjector;

    /**
     * A helper object to inject dependencies into {@link DreamService}.
     * @hide
     */
    @VisibleForTesting
    public interface Injector {
        /** Initializes the Injector */
        void init(Context context);

        /** Creates and returns the dream overlay connection */
        DreamOverlayConnectionHandler createOverlayConnection(ComponentName overlayComponent,
                Runnable onDisconnected);

        /** Returns the {@link DreamActivity} component */
        ComponentName getDreamActivityComponent();

        /** Returns the dream component */
        ComponentName getDreamComponent();

        /** Returns the dream package name */
        String getDreamPackageName();

        /** Returns the {@link DreamManager} */
        IDreamManager getDreamManager();

        /** Returns the associated service info */
        ServiceInfo getServiceInfo();

        /** Returns the handler to be used for any posted operation */
        Handler getHandler();

        /** Returns the package manager */
        PackageManager getPackageManager();

        /** Returns the resources */
        Resources getResources();
    }

    private static final class DefaultInjector implements Injector {
        private Context mContext;
        private Class<?> mClassName;

        public void init(Context context) {
            mContext = context;
            mClassName = context.getClass();
        }

        @Override
        public DreamOverlayConnectionHandler createOverlayConnection(
                ComponentName overlayComponent,
                Runnable onDisconnected) {
            final Resources resources = mContext.getResources();

            return new DreamOverlayConnectionHandler(
                    /* context= */ mContext,
                    Looper.getMainLooper(),
                    new Intent().setComponent(overlayComponent),
                    onDisconnected);
        }

        @Override
        public ComponentName getDreamActivityComponent() {
            return new ComponentName(mContext, DreamActivity.class);
        }

        @Override
        public ComponentName getDreamComponent() {
            return new ComponentName(mContext, mClassName);
        }

        @Override
        public String getDreamPackageName() {
            return mContext.getApplicationContext().getPackageName();
        }

        @Override
        public IDreamManager getDreamManager() {
            return IDreamManager.Stub.asInterface(ServiceManager.getService(DREAM_SERVICE));
        }

        @Override
        public ServiceInfo getServiceInfo() {
            return fetchServiceInfo(mContext, getDreamComponent());
        }

        @Override
        public Handler getHandler() {
            return new Handler(Looper.getMainLooper());
        }

        @Override
        public PackageManager getPackageManager() {
            return mContext.getPackageManager();
        }

        @Override
        public Resources getResources() {
            return mContext.getResources();
        }

    }

    public DreamService() {
        this(new DefaultInjector());
    }

    /**
     * Constructor for test purposes.
     *
     * @param injector used for providing dependencies
     * @hide
     */
    @VisibleForTesting
    public DreamService(Injector injector) {
        mInjector = injector;
        mInjector.init(this);
        mDreamManager = mInjector.getDreamManager();
        mHandler = mInjector.getHandler();
    }

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
        if (dreamHandlesConfirmKeys()) {
            // In the case of an interactive dream that consumes the event, do not process further.
            if (mInteractive && mWindow.superDispatchKeyEvent(event)) {
                return true;
            }

            // If the key is a confirm key and on up, either unlock (no auth) or show bouncer.
            if (KeyEvent.isConfirmKey(event.getKeyCode())) {
                switch (event.getAction()) {
                    case KeyEvent.ACTION_DOWN -> {
                        if (mTrackingConfirmKey != null) {
                            return true;
                        }

                        mTrackingConfirmKey = event.getKeyCode();
                    }
                    case KeyEvent.ACTION_UP -> {
                        if (mTrackingConfirmKey == null
                                || mTrackingConfirmKey != event.getKeyCode()) {
                            return true;
                        }

                        mTrackingConfirmKey = null;

                        final KeyguardManager keyguardManager =
                                getSystemService(KeyguardManager.class);

                        // Simply wake up in the case the device is not locked.
                        if (!keyguardManager.isKeyguardLocked()) {
                            wakeUp();
                            return true;
                        }

                        keyguardManager.requestDismissKeyguard(getActivity(),
                                new KeyguardManager.KeyguardDismissCallback() {
                                    @Override
                                    public void onDismissError() {
                                        Log.e(TAG, "Could not dismiss keyguard on confirm key");
                                    }
                                });
                    }
                }

                // All key events for matching key codes should be consumed to prevent other actions
                // from triggering.
                return true;
            }
        }

        if (!mInteractive) {
            if (mDebug) Slog.v(mTag, "Waking up on keyEvent");
            wakeUp();
            return true;
        } else if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (mDebug) Slog.v(mTag, "Waking up on back key");
            wakeUp();
            return true;
        }
        return mWindow.superDispatchKeyEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(mTag, "Waking up on keyShortcutEvent");
            wakeUp();
            return true;
        }
        return mWindow.superDispatchKeyShortcutEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // TODO: create more flexible version of mInteractive that allows clicks
        // but finish()es on any other kind of activity
        if (!mInteractive && event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (mDebug) Slog.v(mTag, "Waking up on touchEvent");
            wakeUp();
            return true;
        }
        return mWindow.superDispatchTouchEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(mTag, "Waking up on trackballEvent");
            wakeUp();
            return true;
        }
        return mWindow.superDispatchTrackballEvent(event);
    }

    /** {@inheritDoc} */
    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mInteractive) {
            if (mDebug) Slog.v(mTag, "Waking up on genericMotionEvent");
            wakeUp();
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
    public boolean onSearchRequested(SearchEvent event) {
        return onSearchRequested();
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
    public ActionMode onWindowStartingActionMode(
            android.view.ActionMode.Callback callback, int type) {
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
        return mWindow != null ? mWindow.getWindowManager() : null;
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
     * Retrieves the current {@link android.app.Activity} associated with the dream.
     * This method behaves similarly to calling {@link android.app.Activity#getActivity()}.
     *
     * @return The current activity, or null if the dream is not associated with an activity
     * or not started.
     *
     * @hide
     */
    public Activity getActivity() {
        return mActivity;
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
    public void setContentView(@LayoutRes int layoutResID) {
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
     * <p>
     * <strong>Note:</strong> In most cases -- depending on compiler support --
     * the resulting view is automatically cast to the target class type. If
     * the target class type is unconstrained, an explicit cast may be
     * necessary.
     *
     * @param id the ID to search for
     * @return The view if found or null otherwise.
     * @see View#findViewById(int)
     * @see DreamService#requireViewById(int)
     */
    /* TODO(b/347672184): Re-add @Nullable */
    public <T extends View> T findViewById(@IdRes int id) {
        return getWindow().findViewById(id);
    }

    /**
     * Finds a view that was identified by the id attribute from the XML that was processed in
     * {@link #onCreate}, or throws an IllegalArgumentException if the ID is invalid or there is no
     * matching view in the hierarchy.
     *
     * <p>Note: Requires a window, do not call before {@link #onAttachedToWindow()}</p>
     * <p>
     * <strong>Note:</strong> In most cases -- depending on compiler support --
     * the resulting view is automatically cast to the target class type. If
     * the target class type is unconstrained, an explicit cast may be
     * necessary.
     *
     * @param id the ID to search for
     * @return a view with given ID
     * @see View#requireViewById(int)
     * @see DreamService#findViewById(int)
     */
    @NonNull
    public final <T extends View> T requireViewById(@IdRes int id) {
        T view = findViewById(id);
        if (view == null) {
            throw new IllegalArgumentException(
                    "ID does not reference a View inside this DreamService");
        }
        return view;
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
        updateAccessibilityMessage();
    }

    /**
     * Returns whether this dream is interactive. Defaults to false.
     *
     * @see #setInteractive(boolean)
     */
    public boolean isInteractive() {
        return mInteractive;
    }

    /**
     * Controls {@link android.view.WindowManager.LayoutParams#FLAG_FULLSCREEN}
     * on the dream's window.
     *
     * @param fullscreen If true, the fullscreen flag will be set; else it
     * will be cleared.
     */
    public void setFullscreen(boolean fullscreen) {
        if (mFullscreen != fullscreen) {
            mFullscreen = fullscreen;
            int flag = WindowManager.LayoutParams.FLAG_FULLSCREEN;
            applyWindowFlags(mFullscreen ? flag : 0, flag);
        }
    }

    /**
     * Returns whether this dream is in fullscreen mode. Defaults to false.
     *
     * @see #setFullscreen(boolean)
     */
    public boolean isFullscreen() {
        return mFullscreen;
    }

    /**
     * Marks this dream as keeping the screen bright while dreaming. In preview mode, the screen
     * is always allowed to dim and overrides the value specified here.
     *
     * @param screenBright True to keep the screen bright while dreaming.
     */
    public void setScreenBright(boolean screenBright) {
        if (mScreenBright != screenBright && !mPreviewMode) {
            mScreenBright = screenBright;
            int flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            applyWindowFlags(mScreenBright ? flag : 0, flag);
        }
    }

    /**
     * Returns whether this dream keeps the screen bright while dreaming.
     * Defaults to true, preventing the screen from dimming.
     *
     * @see #setScreenBright(boolean)
     */
    public boolean isScreenBright() {
        return getWindowFlagValue(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, mScreenBright);
    }

    /**
     * Marks this dream as windowless. It should be called in {@link #onCreate} method.
     *
     * @hide
     *
     */
    public void setWindowless(boolean windowless) {
        mWindowless = windowless;
    }

    /**
     * Returns whether this dream is windowless.
     *
     * @hide
     */
    public boolean isWindowless() {
        return mWindowless;
    }

    /**
     * Returns true if this dream is allowed to doze.
     * <p>
     * The value returned by this method is only meaningful when the dream has started.
     * </p>
     *
     * @return True if this dream can doze.
     * @see #startDozing
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean canDoze() {
        return mCanDoze;
    }

    /**
     * Starts dozing, entering a deep dreamy sleep.
     * <p>
     * Dozing enables the system to conserve power while the user is not actively interacting
     * with the device. While dozing, the display will remain on in a low-power state
     * and will continue to show its previous contents but the application processor and
     * other system components will be allowed to suspend when possible.
     * </p><p>
     * While the application processor is suspended, the dream may stop executing code
     * for long periods of time. Prior to being suspended, the dream may schedule periodic
     * wake-ups to render new content by scheduling an alarm with the {@link AlarmManager}.
     * The dream may also keep the CPU awake by acquiring a
     * {@link android.os.PowerManager#PARTIAL_WAKE_LOCK partial wake lock} when necessary.
     * Note that since the purpose of doze mode is to conserve power (especially when
     * running on battery), the dream should not wake the CPU very often or keep it
     * awake for very long.
     * </p><p>
     * It is a good idea to call this method some time after the dream's entry animation
     * has completed and the dream is ready to doze. It is important to completely
     * finish all of the work needed before dozing since the application processor may
     * be suspended at any moment once this method is called unless other wake locks
     * are being held.
     * </p><p>
     * Call {@link #stopDozing} or {@link #finish} to stop dozing.
     * </p>
     *
     * @see #stopDozing
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage
    public void startDozing() {
        if (mCanDoze && !mDozing) {
            mDozing = true;
            updateDoze();
        }
    }

    private void updateDoze() {
        if (mDreamToken == null) {
            Slog.w(mTag, "Updating doze without a dream token.");
            return;
        }

        if (mDozing) {
            try {
                mDreamManager.startDozing(
                        mDreamToken, mDozeScreenState, mDozeScreenStateReason,
                        mDozeScreenBrightness);
            } catch (RemoteException ex) {
                // system server died
            }
        }
    }

    /**
     * Stops dozing, returns to active dreaming.
     * <p>
     * This method reverses the effect of {@link #startDozing}. From this moment onward,
     * the application processor will be kept awake as long as the dream is running
     * or until the dream starts dozing again.
     * </p>
     *
     * @see #startDozing
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void stopDozing() {
        if (mDozing) {
            mDozing = false;
            try {
                mDreamManager.stopDozing(mDreamToken);
            } catch (RemoteException ex) {
                // system server died
            }
        }
    }

    /**
     * Returns true if the dream will allow the system to enter a low-power state while
     * it is running without actually turning off the screen. Defaults to false,
     * keeping the application processor awake while the dream is running.
     *
     * @return True if the dream is dozing.
     *
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public boolean isDozing() {
        return mDozing;
    }

    /**
     * Gets the screen state to use while dozing.
     *
     * @return The screen state to use while dozing, such as {@link Display#STATE_ON},
     * {@link Display#STATE_DOZE}, {@link Display#STATE_DOZE_SUSPEND},
     * {@link Display#STATE_ON_SUSPEND}, {@link Display#STATE_OFF}, or {@link Display#STATE_UNKNOWN}
     * for the default behavior.
     *
     * @see #setDozeScreenState
     * @hide For use by system UI components only.
     */
    public int getDozeScreenState() {
        return mDozeScreenState;
    }

    /**
     * Same as {@link #setDozeScreenState(int, int)}, but with no screen state reason specified.
     *
     * <p>Use {@link #setDozeScreenState(int, int)} whenever possible to allow properly accounting
     * for the screen state reason.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public void setDozeScreenState(int state) {
        setDozeScreenState(state, Display.STATE_REASON_UNKNOWN);
    }

    /**
     * Sets the screen state to use while dozing.
     * <p>
     * The value of this property determines the power state of the primary display
     * once {@link #startDozing} has been called. The default value is
     * {@link Display#STATE_UNKNOWN} which lets the system decide.
     * The dream may set a different state before starting to doze and may
     * perform transitions between states while dozing to conserve power and
     * achieve various effects.
     * </p><p>
     * Some devices will have dedicated hardware ("Sidekick") to animate
     * the display content while the CPU sleeps. If the dream and the hardware support
     * this, {@link Display#STATE_ON_SUSPEND} or {@link Display#STATE_DOZE_SUSPEND}
     * will switch control to the Sidekick.
     * </p><p>
     * If not using Sidekick, it is recommended that the state be set to
     * {@link Display#STATE_DOZE_SUSPEND} once the dream has completely
     * finished drawing and before it releases its wakelock
     * to allow the display hardware to be fully suspended. While suspended,
     * the display will preserve its on-screen contents.
     * </p><p>
     * If the doze suspend state is used, the dream must make sure to set the mode back
     * to {@link Display#STATE_DOZE} or {@link Display#STATE_ON} before drawing again
     * since the display updates may be ignored and not seen by the user otherwise.
     * </p><p>
     * The set of available display power states and their behavior while dozing is
     * hardware dependent and may vary across devices. The dream may therefore
     * need to be modified or configured to correctly support the hardware.
     * </p>
     *
     * @param state The screen state to use while dozing, such as {@link Display#STATE_ON},
     * {@link Display#STATE_DOZE}, {@link Display#STATE_DOZE_SUSPEND},
     * {@link Display#STATE_ON_SUSPEND}, {@link Display#STATE_OFF}, or {@link Display#STATE_UNKNOWN}
     * for the default behavior.
     * @param reason the reason for setting the specified screen state.
     *
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage
    public void setDozeScreenState(int state, @Display.StateReason int reason) {
        if (mDozeScreenState != state) {
            mDozeScreenState = state;
            mDozeScreenStateReason = reason;
            updateDoze();
        }
    }

    /**
     * Gets the screen brightness to use while dozing.
     *
     * @return The screen brightness while dozing as a value between
     * {@link PowerManager#BRIGHTNESS_OFF} (0) and {@link PowerManager#BRIGHTNESS_ON} (255),
     * or {@link PowerManager#BRIGHTNESS_DEFAULT} (-1) to ask the system to apply
     * its default policy based on the screen state.
     *
     * @see #setDozeScreenBrightness
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public int getDozeScreenBrightness() {
        return mDozeScreenBrightness;
    }

    /**
     * Sets the screen brightness to use while dozing.
     * <p>
     * The value of this property determines the power state of the primary display
     * once {@link #startDozing} has been called. The default value is
     * {@link PowerManager#BRIGHTNESS_DEFAULT} which lets the system decide.
     * The dream may set a different brightness before starting to doze and may adjust
     * the brightness while dozing to conserve power and achieve various effects.
     * </p><p>
     * Note that dream may specify any brightness in the full 0-255 range, including
     * values that are less than the minimum value for manual screen brightness
     * adjustments by the user. In particular, the value may be set to 0 which may
     * turn off the backlight entirely while still leaving the screen on although
     * this behavior is device dependent and not guaranteed.
     * </p><p>
     * The available range of display brightness values and their behavior while dozing is
     * hardware dependent and may vary across devices. The dream may therefore
     * need to be modified or configured to correctly support the hardware.
     * </p>
     *
     * @param brightness The screen brightness while dozing as a value between
     * {@link PowerManager#BRIGHTNESS_OFF} (0) and {@link PowerManager#BRIGHTNESS_ON} (255),
     * or {@link PowerManager#BRIGHTNESS_DEFAULT} (-1) to ask the system to apply
     * its default policy based on the screen state.
     *
     * @hide For use by system UI components only.
     */
    @UnsupportedAppUsage
    public void setDozeScreenBrightness(int brightness) {
        if (brightness != PowerManager.BRIGHTNESS_DEFAULT) {
            brightness = clampAbsoluteBrightness(brightness);
        }
        if (mDozeScreenBrightness != brightness) {
            mDozeScreenBrightness = brightness;
            updateDoze();
        }
    }

    /**
     * Called when this Dream is constructed.
     */
    @Override
    public void onCreate() {
        if (mDebug) Slog.v(mTag, "onCreate()");

        mDreamComponent = mInjector.getDreamComponent();
        mShouldShowComplications = fetchShouldShowComplications(mInjector.getPackageManager(),
                mInjector.getServiceInfo());
        mOverlayCallback = new IDreamOverlayCallback.Stub() {
            @Override
            public void onExitRequested() {
                // Simply finish dream when exit is requested.
                mHandler.post(() -> finish());
            }

            @Override
            public void onRedirectWake(boolean redirect) {
                mRedirectWake = redirect;
            }
        };

        super.onCreate();
    }

    /**
     * Called when the dream's window has been created and is visible and animation may now begin.
     */
    public void onDreamingStarted() {
        if (mDebug) Slog.v(mTag, "onDreamingStarted()");
        // hook for subclasses
    }

    /**
     * Called when this Dream is stopped, either by external request or by calling finish(),
     * before the window has been removed.
     */
    public void onDreamingStopped() {
        if (mDebug) Slog.v(mTag, "onDreamingStopped()");
        // hook for subclasses
    }

    /**
     * Called when the dream is being asked to stop itself and wake.
     * <p>
     * The default implementation simply calls {@link #finish} which ends the dream
     * immediately. Subclasses may override this function to perform a smooth exit
     * transition then call {@link #finish} afterwards.
     * </p><p>
     * Note that the dream will only be given a short period of time (currently about
     * five seconds) to wake up. If the dream does not finish itself in a timely manner
     * then the system will forcibly finish it once the time allowance is up.
     * </p>
     */
    public void onWakeUp() {
        if (mOverlayConnection != null) {
            mOverlayConnection.addConsumer(overlay -> {
                try {
                    overlay.wakeUp();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error waking the overlay service", e);
                } finally {
                    finish();
                }
            });
        } else {
            finish();
        }
    }

    /** {@inheritDoc} */
    @Override
    public final IBinder onBind(Intent intent) {
        if (mDebug) Slog.v(mTag, "onBind() intent = " + intent);
        mDreamServiceWrapper = new DreamServiceWrapper();
        final ComponentName overlayComponent = intent.getParcelableExtra(
                EXTRA_DREAM_OVERLAY_COMPONENT, ComponentName.class);

        // Connect to the overlay service if present.
        if (!mWindowless && overlayComponent != null) {
            mOverlayConnection = mInjector.createOverlayConnection(overlayComponent,
                    this::finish);

            if (!mOverlayConnection.bind()) {
                // Binding failed.
                mOverlayConnection = null;
            }
        }

        return mDreamServiceWrapper;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // We must unbind from any overlay connection if we are unbound before finishing.
        if (mOverlayConnection != null) {
            mOverlayConnection.unbind();
            mOverlayConnection = null;
        }

        return super.onUnbind(intent);
    }

    /**
     * Stops the dream and detaches from the window.
     * <p>
     * When the dream ends, the system will be allowed to go to sleep fully unless there
     * is a reason for it to be awake such as recent user activity or wake locks being held.
     * </p>
     */
    public final void finish() {
        // If there is an active overlay connection, signal that the dream is ending before
        // continuing. Note that the overlay cannot rely on the unbound state, since another dream
        // might have bound to it in the meantime.
        if (mOverlayConnection != null) {
            mOverlayConnection.addConsumer(overlay -> {
                try {
                    overlay.endDream();
                    mOverlayConnection.unbind();
                    mOverlayConnection = null;
                } catch (RemoteException e) {
                    Log.e(mTag, "could not inform overlay of dream end:" + e);
                }
            });
        }

        if (mDebug) Slog.v(mTag, "finish(): mFinished=" + mFinished);

        Activity activity = mActivity;
        if (activity != null) {
            if (!activity.isFinishing()) {
                // In case the activity is not finished yet, do it now.
                activity.finishAndRemoveTask();
            }
            return;
        }

        if (mFinished) {
            return;
        }
        mFinished = true;

        if (mDreamToken == null) {
            if (mDebug) Slog.v(mTag, "finish() called when not attached.");
            stopSelf();
            return;
        }

        try {
            // finishSelf will unbind the dream controller from the dream service. This will
            // trigger DreamService.this.onDestroy and DreamService.this will die.
            mDreamManager.finishSelf(mDreamToken, true /*immediate*/);
        } catch (RemoteException ex) {
            // system server died
        }
    }

    /**
     * Wakes the dream up gently.
     * <p>
     * Calls {@link #onWakeUp} to give the dream a chance to perform an exit transition.
     * When the transition is over, the dream should call {@link #finish}.
     * </p>
     */
    public final void wakeUp() {
        wakeUp(false);
    }

    /**
     * Tells the dream to come to the front (which in turn tells the overlay to come to the front).
     */
    private void comeToFront() {
        mOverlayConnection.addConsumer(overlay -> {
            try {
                overlay.comeToFront();
            } catch (RemoteException e) {
                Log.e(mTag, "could not tell overlay to come to front:" + e);
            }
        });
    }

    /**
     * Whether or not wake requests will be redirected.
     *
     * @hide
     */
    public boolean getRedirectWake() {
        return mOverlayConnection != null && mRedirectWake;
    }

    private void wakeUp(boolean fromSystem) {
        if (mDebug) {
            Slog.v(mTag, "wakeUp(): fromSystem=" + fromSystem + ", mWaking=" + mWaking
                    + ", mFinished=" + mFinished);
        }

        if (!fromSystem && getRedirectWake()) {
            mOverlayConnection.addConsumer(overlay -> {
                try {
                    overlay.onWakeRequested();
                } catch (RemoteException e) {
                    Log.e(mTag, "could not inform overlay of dream wakeup:" + e);
                }
            });

            return;
        }

        if (!mWaking && !mFinished) {
            mWaking = true;

            if (mActivity != null) {
                // During wake up the activity should be translucent to allow the application
                // underneath to start drawing. Normally, the WM animation system takes care of
                // this, but here we give the dream application some time to perform a custom exit
                // animation. If it uses a view animation, the WM doesn't know about it and can't
                // make the activity translucent in the normal way. Therefore, here we ensure that
                // the activity is translucent during wake up regardless of what animation is used
                // in onWakeUp().
                mActivity.convertToTranslucent(null, null);
            }

            // As a minor optimization, invoke the callback first in case it simply
            // calls finish() immediately so there wouldn't be much point in telling
            // the system that we are finishing the dream gently.
            onWakeUp();

            // Now tell the system we are waking gently, unless we already told
            // it we were finishing immediately.
            if (!fromSystem && !mFinished) {
                if (mActivity == null) {
                    Slog.w(mTag, "WakeUp was called before the dream was attached.");
                } else {
                    try {
                        mDreamManager.finishSelf(mDreamToken, false /*immediate*/);
                    } catch (RemoteException ex) {
                        // system server died
                    }
                }
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public void onDestroy() {
        if (mDebug) Slog.v(mTag, "onDestroy()");
        // hook for subclasses

        // Just in case destroy came in before detach, let's take care of that now
        detach();
        mOverlayCallback = null;
        super.onDestroy();
    }

    // end public api

    /**
     * Parses and returns metadata of the dream service indicated by the service info. Returns null
     * if metadata cannot be found.
     *
     * Note that {@link ServiceInfo} must be fetched with {@link PackageManager#GET_META_DATA} flag.
     *
     * @hide
     */
    @Nullable
    @TestApi
    public static DreamMetadata getDreamMetadata(@NonNull Context context,
            @Nullable ServiceInfo serviceInfo) {
        return getDreamMetadata(context.getPackageManager(), serviceInfo);
    }

    /**
     * Parses and returns metadata of the dream service indicated by the service info. Returns null
     * if metadata cannot be found.
     *
     * Note that {@link ServiceInfo} must be fetched with {@link PackageManager#GET_META_DATA} flag.
     *
     * @hide
     */
    @Nullable
    public static DreamMetadata getDreamMetadata(@NonNull PackageManager packageManager,
            @Nullable ServiceInfo serviceInfo) {
        if (serviceInfo == null) return null;

        try (TypedArray rawMetadata = packageManager.extractPackageItemInfoAttributes(serviceInfo,
                DreamService.DREAM_META_DATA, DREAM_META_DATA_ROOT_TAG,
                com.android.internal.R.styleable.Dream)) {
            if (rawMetadata == null) return null;
            try {
                return new DreamMetadata(
                        convertToComponentName(
                                rawMetadata.getString(
                                        com.android.internal.R.styleable.Dream_settingsActivity),
                                serviceInfo),
                        rawMetadata.getDrawable(
                                com.android.internal.R.styleable.Dream_previewImage),
                        rawMetadata.getBoolean(R.styleable.Dream_showClockAndComplications,
                                DEFAULT_SHOW_COMPLICATIONS),
                        rawMetadata.getInt(R.styleable.Dream_dreamCategory, DREAM_CATEGORY_DEFAULT)
                );
            } catch (Exception exception) {
                Log.e(TAG, "Failed to create read metadata", exception);
                return null;
            }
        }
    }

    @Nullable
    private static ComponentName convertToComponentName(@Nullable String flattenedString,
            ServiceInfo serviceInfo) {
        if (flattenedString == null) {
            return null;
        }

        if (!flattenedString.contains("/")) {
            return new ComponentName(serviceInfo.packageName, flattenedString);
        }

        // Ensure that the component is from the same package as the dream service. If not,
        // treat the component as invalid and return null instead.
        final ComponentName cn = ComponentName.unflattenFromString(flattenedString);
        if (cn == null) return null;
        if (!cn.getPackageName().equals(serviceInfo.packageName)) {
            Log.w(TAG,
                    "Inconsistent package name in component: " + cn.getPackageName()
                            + ", should be: " + serviceInfo.packageName);
            return null;
        }
        return cn;
    }

    /**
     * Called by DreamController.stopDream() when the Dream is about to be unbound and destroyed.
     *
     * Must run on mHandler.
     */
    private void detach() {
        if (mStarted) {
            if (mDebug) Slog.v(mTag, "detach(): Calling onDreamingStopped()");
            mStarted = false;
            onDreamingStopped();
        }

        if (mActivity != null && !mActivity.isFinishing()) {
            mActivity.finishAndRemoveTask();
        } else {
            finish();
        }

        mDreamToken = null;
        mCanDoze = false;
    }

    /**
     * Called when the Dream is ready to be shown.
     *
     * Must run on mHandler.
     *
     * @param dreamToken Token for this dream service.
     * @param started    A callback that will be invoked once onDreamingStarted has completed.
     */
    private void attach(IBinder dreamToken, boolean canDoze, boolean isPreviewMode,
            IRemoteCallback started) {
        if (mDreamToken != null) {
            Slog.e(mTag, "attach() called when dream with token=" + mDreamToken
                    + " already attached");
            return;
        }
        if (mFinished || mWaking) {
            Slog.w(mTag, "attach() called after dream already finished");
            try {
                mDreamManager.finishSelf(dreamToken, true /*immediate*/);
            } catch (RemoteException ex) {
                // system server died
            }
            return;
        }

        mDreamToken = dreamToken;
        mCanDoze = canDoze;
        mPreviewMode = isPreviewMode;
        if (mPreviewMode) {
            // Allow screen to dim when in preview mode.
            mScreenBright = false;
        }
        // This is not a security check to prevent malicious dreams but a guard rail to stop
        // third-party dreams from being windowless and not working well as a result.
        if (mWindowless && !mCanDoze && !isCallerSystemUi()) {
            throw new IllegalStateException("Only doze or SystemUI dreams can be windowless.");
        }

        mDispatchAfterOnAttachedToWindow = () -> {
            if (mWindow != null || mWindowless) {
                mStarted = true;
                try {
                    onDreamingStarted();
                } finally {
                    try {
                        started.sendResult(null);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
        };

        // We need to defer calling onDreamingStarted until after the activity is created.
        // If the dream is windowless, we can call it immediately. Otherwise, we wait
        // for the DreamActivity to report onActivityCreated via
        // DreamServiceWrapper.onActivityCreated.
        if (!mWindowless) {
            Intent i = new Intent();
            i.setComponent(mInjector.getDreamActivityComponent());
            i.setPackage(mInjector.getDreamPackageName());
            i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            DreamActivity.setCallback(i, new DreamActivityCallbacks(mDreamToken));
            final ServiceInfo serviceInfo = mInjector.getServiceInfo();
            final CharSequence title = fetchDreamLabel(mInjector.getPackageManager(),
                    mInjector.getResources(), serviceInfo, isPreviewMode);

            DreamActivity.setTitle(i, title);

            try {
                mDreamManager.startDreamActivity(i);
            } catch (SecurityException e) {
                Log.w(mTag,
                        "Received SecurityException trying to start DreamActivity. "
                        + "Aborting dream start.");
                detach();
            } catch (RemoteException e) {
                Log.w(mTag, "Could not connect to activity task manager to start dream activity");
                e.rethrowFromSystemServer();
            }
        } else {
            mDispatchAfterOnAttachedToWindow.run();
        }
    }

    private void onWindowCreated(Window w) {
        mWindow = w;
        mWindow.setCallback(this);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);

        WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.flags |= (WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                    | (mFullscreen ? WindowManager.LayoutParams.FLAG_FULLSCREEN : 0)
                    | (mScreenBright ? WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON : 0)
                    );
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        mWindow.setAttributes(lp);
        // Workaround: Currently low-profile and in-window system bar backgrounds don't go
        // along well. Dreams usually don't need such bars anyways, so disable them by default.
        mWindow.clearFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Hide all insets when the dream is showing
        mWindow.getDecorView().getWindowInsetsController().hide(WindowInsets.Type.systemBars());
        mWindow.setDecorFitsSystemWindows(false);
        updateAccessibilityMessage();
        mWindow.getDecorView().addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    private Consumer<IDreamOverlayClient> mDreamStartOverlayConsumer;

                    @Override
                    public void onViewAttachedToWindow(View v) {
                        mDispatchAfterOnAttachedToWindow.run();

                        if (mOverlayConnection != null) {
                            // Request the DreamOverlay be told to dream with dream's window
                            // parameters once the window has been attached.
                            mDreamStartOverlayConsumer = overlay -> {
                                if (mWindow == null) {
                                    Slog.d(TAG, "mWindow is null");
                                    return;
                                }
                                try {
                                    overlay.startDream(mWindow.getAttributes(), mOverlayCallback,
                                            mDreamComponent.flattenToString(),
                                            mShouldShowComplications);
                                } catch (RemoteException e) {
                                    Log.e(mTag, "could not send window attributes:" + e);
                                }
                            };
                            mOverlayConnection.addConsumer(mDreamStartOverlayConsumer);
                        }
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        if (mActivity == null || !mActivity.isChangingConfigurations()) {
                            // Only stop the dream if the view is not detached by relaunching
                            // activity for configuration changes. It is important to also clear
                            // the window reference in order to fully release the DreamActivity.
                            mWindow = null;
                            mActivity = null;
                            finish();
                        }

                        if (mOverlayConnection != null && mDreamStartOverlayConsumer != null) {
                            mOverlayConnection.removeConsumer(mDreamStartOverlayConsumer);
                        }
                    }
                });
    }

    private void updateAccessibilityMessage() {
        if (mWindow == null) return;
        if (mDreamAccessibility == null) {
            final View rootView = mWindow.getDecorView();
            mDreamAccessibility = new DreamAccessibility(this, rootView);
        }
        mDreamAccessibility.updateAccessibilityConfiguration(isInteractive());
    }

    private boolean getWindowFlagValue(int flag, boolean defaultValue) {
        return mWindow == null ? defaultValue : (mWindow.getAttributes().flags & flag) != 0;
    }

    private void applyWindowFlags(int flags, int mask) {
        if (mWindow != null) {
            WindowManager.LayoutParams lp = mWindow.getAttributes();
            lp.flags = applyFlags(lp.flags, flags, mask);
            mWindow.setAttributes(lp);
            mWindow.getWindowManager().updateViewLayout(mWindow.getDecorView(), lp);
        }
    }

    private boolean isCallerSystemUi() {
        return checkCallingOrSelfPermission(android.Manifest.permission.STATUS_BAR_SERVICE)
                == PERMISSION_GRANTED;
    }

    private int applyFlags(int oldFlags, int flags, int mask) {
        return (oldFlags&~mask) | (flags&mask);
    }

    /**
     * Fetches metadata of the dream indicated by the {@link ComponentName}, and returns whether
     * the dream should show complications on the overlay. If not defined, returns
     * {@link DreamService#DEFAULT_SHOW_COMPLICATIONS}.
     */
    private static boolean fetchShouldShowComplications(@NonNull PackageManager packageManager,
            @Nullable ServiceInfo serviceInfo) {
        final DreamMetadata metadata = getDreamMetadata(packageManager, serviceInfo);
        if (metadata != null) {
            return metadata.showComplications;
        }
        return DEFAULT_SHOW_COMPLICATIONS;
    }

    @Nullable
    private static CharSequence fetchDreamLabel(
            PackageManager pm,
            Resources resources,
            @Nullable ServiceInfo serviceInfo,
            boolean isPreviewMode) {
        if (serviceInfo == null) {
            return null;
        }
        final CharSequence dreamLabel = serviceInfo.loadLabel(pm);
        if (!isPreviewMode || dreamLabel == null) {
            return dreamLabel;
        }
        // When in preview mode, return a special label indicating the dream is in preview.
        return resources.getString(R.string.dream_preview_title, dreamLabel);
    }

    @Nullable
    private static ServiceInfo fetchServiceInfo(Context context, ComponentName componentName) {
        final PackageManager pm = context.getPackageManager();

        try {
            return pm.getServiceInfo(componentName,
                    PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        } catch (PackageManager.NameNotFoundException e) {
            if (DEBUG) Log.w(TAG, "cannot find component " + componentName.flattenToShortString());
        }
        return null;
    }

    @Override
    protected void dump(final FileDescriptor fd, PrintWriter pw, final String[] args) {
        DumpUtils.dumpAsync(mHandler, (pw1, prefix) -> dumpOnHandler(fd, pw1, args), pw, "", 1000);
    }

    /** @hide */
    protected void dumpOnHandler(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print(mTag + ": ");
        if (mFinished) {
            pw.println("stopped");
        } else {
            pw.println("running (dreamToken=" + mDreamToken + ")");
        }
        pw.println("  window: " + mWindow);
        pw.print("  flags:");
        if (isInteractive()) pw.print(" interactive");
        if (isFullscreen()) pw.print(" fullscreen");
        if (isScreenBright()) pw.print(" bright");
        if (isWindowless()) pw.print(" windowless");
        if (isDozing()) pw.print(" dozing");
        else if (canDoze()) pw.print(" candoze");
        pw.println();
        if (canDoze()) {
            pw.println("  doze screen state: " + Display.stateToString(mDozeScreenState));
            pw.println("  doze screen brightness: " + mDozeScreenBrightness);
        }
    }

    private static int clampAbsoluteBrightness(int value) {
        return MathUtils.constrain(value, PowerManager.BRIGHTNESS_OFF, PowerManager.BRIGHTNESS_ON);
    }

    /**
     * The DreamServiceWrapper is used as a gateway to the system_server, where DreamController
     * uses it to control the DreamService. It is also used to receive callbacks from the
     * DreamActivity.
     */
    final class DreamServiceWrapper extends IDreamService.Stub {
        @Override
        public void attach(final IBinder dreamToken, final boolean canDoze,
                final boolean isPreviewMode, IRemoteCallback started) {
            mHandler.post(
                    () -> DreamService.this.attach(dreamToken, canDoze, isPreviewMode, started));
        }

        @Override
        public void detach() {
            mHandler.post(DreamService.this::detach);
        }

        @Override
        public void wakeUp() {
            mHandler.post(() -> DreamService.this.wakeUp(true /*fromSystem*/));
        }

        @Override
        public void comeToFront() {
            if (!dreamHandlesBeingObscured()) {
                return;
            }

            mHandler.post(DreamService.this::comeToFront);
        }
    }

    /** @hide */
    @VisibleForTesting
    public final class DreamActivityCallbacks extends Binder {
        private final IBinder mActivityDreamToken;

        DreamActivityCallbacks(IBinder token) {
            mActivityDreamToken = token;
        }

        /** Callback when the {@link DreamActivity} has been created */
        public void onActivityCreated(DreamActivity activity) {
            if (mActivityDreamToken != mDreamToken || mFinished) {
                Slog.d(TAG, "DreamActivity was created after the dream was finished or "
                        + "a new dream started, finishing DreamActivity");
                if (!activity.isFinishing()) {
                    activity.finishAndRemoveTask();
                }
                return;
            }
            if (mActivity != null) {
                Slog.w(TAG, "A DreamActivity has already been started, "
                        + "finishing latest DreamActivity");
                if (!activity.isFinishing()) {
                    activity.finishAndRemoveTask();
                }
                return;
            }

            mActivity = activity;
            onWindowCreated(activity.getWindow());
        }

        /** Callback when the {@link DreamActivity} has been destroyed */
        public void onActivityDestroyed() {
            mActivity = null;
            mWindow = null;
            detach();
        }
    }

    /**
     * Represents metadata defined in {@link android.R.styleable#Dream &lt;dream&gt;}.
     *
     * @hide
     */
    @VisibleForTesting
    @TestApi
    public static final class DreamMetadata {
        @Nullable
        public final ComponentName settingsActivity;

        @Nullable
        public final Drawable previewImage;

        @NonNull
        public final boolean showComplications;

        @NonNull
        @FlaggedApi(Flags.FLAG_HOME_PANEL_DREAM)
        public final int dreamCategory;

        /**
         * @hide
         */
        @VisibleForTesting
        public DreamMetadata(
                ComponentName settingsActivity,
                Drawable previewImage,
                boolean showComplications,
                int dreamCategory) {
            this.settingsActivity = settingsActivity;
            this.previewImage = previewImage;
            this.showComplications = showComplications;
            if (Flags.homePanelDream()) {
                this.dreamCategory = dreamCategory;
            } else {
                this.dreamCategory = DREAM_CATEGORY_DEFAULT;
            }
        }
    }

    /**
     * Sets the dream overlay component to be used by the dream.
     *
     * @hide
     */
    @VisibleForTesting
    public static void setDreamOverlayComponent(Intent intent, ComponentName component) {
        intent.putExtra(DreamService.EXTRA_DREAM_OVERLAY_COMPONENT, component);
    }
}
