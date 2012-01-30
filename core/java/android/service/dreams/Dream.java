/**
 * 
 */
package android.service.dreams;

import com.android.internal.policy.PolicyManager;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.ColorDrawable;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.view.ActionMode;
import android.view.IWindowManager;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;
import android.view.WindowManager;
import android.view.WindowManagerImpl;

/**
 * @hide
 *
 */
public class Dream extends Service implements Window.Callback {
    private final static boolean DEBUG = true;
    private final static String TAG = "Dream";
    
    /**
     * The {@link Intent} that must be declared as handled by the service.
     * To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_WALLPAPER} permission so
     * that other applications can not abuse it.
     */
    @SdkConstant(SdkConstantType.SERVICE_ACTION)
    public static final String SERVICE_INTERFACE =
            "android.service.dreams.Dream";

    private Window mWindow;

    private WindowManager mWindowManager;
    private IDreamManager mSandman;
    
    private boolean mInteractive;
    
    final Handler mHandler = new Handler();
    
    boolean mFinished = false;
    
    // begin Window.Callback methods
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!mInteractive) { 
            finish();
            return true;
        }
        return mWindow.superDispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (!mInteractive) { 
            finish();
            return true;
        }
        return mWindow.superDispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!mInteractive) { 
            finish();
            return true;
        }
        return mWindow.superDispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        if (!mInteractive) { 
            finish();
            return true;
        }
        return mWindow.superDispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!mInteractive) { 
            finish();
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
        mWindow.addFlags(
                WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        lightsOut();
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
     * Called when this Dream is constructed. Place your initialization here.
     * 
     * Subclasses must call through to the superclass implementation.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        if (DEBUG) Slog.v(TAG, "Dream created on thread " + Thread.currentThread().getId());

        mSandman = IDreamManager.Stub.asInterface(ServiceManager.getService("dreams"));
    }
    
    /**
     * Called when this Dream is started. Place your initialization here.
     * 
     * Subclasses must call through to the superclass implementation.
     * 
     * XXX(dsandler) Might want to make this final and have a different method for clients to override 
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return super.onStartCommand(intent, flags, startId);
    }
    
   /**
     * Inflate a layout resource and set it to be the content view for this Dream.
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
     * Set a view to be the content view for this Dream.
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
     * Set a view to be the content view for this Dream.
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
     * Add a view to the Dream's window, leaving other content views in place.
     * 
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        getWindow().addContentView(view, params);
    }
    
    /**
     * @param mInteractive the mInteractive to set
     */
    public void setInteractive(boolean mInteractive) {
        this.mInteractive = mInteractive;
    }

    /**
     * @return the mInteractive
     */
    public boolean isInteractive() {
        return mInteractive;
    }
    
    /** Convenience method for setting View.SYSTEM_UI_FLAG_LOW_PROFILE on the content view. */
    protected void lightsOut() {
        // turn the lights down low
        final View v = mWindow.getDecorView();
        if (v != null) {
            v.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
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
     * Called when this Dream is being removed from the screen and stopped.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        mWindowManager.removeView(mWindow.getDecorView());
    }

    /**
     * Creates a new dream window, attaches the current content view, and shows it.
     * 
     * @param windowToken Binder to attach to the window to allow access to the correct window type.
     * @hide
     */
    final /*package*/ void attach(IBinder windowToken) {
        if (DEBUG) Slog.v(TAG, "Dream attached on thread " + Thread.currentThread().getId());
        
        mWindow = PolicyManager.makeNewWindow(this);
        mWindow.setCallback(this);
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(0xFF000000));

        if (DEBUG) Slog.v(TAG, "attaching window token: " + windowToken 
                + " to window of type " + WindowManager.LayoutParams.TYPE_DREAM);

        WindowManager.LayoutParams lp = mWindow.getAttributes();
        lp.type = WindowManager.LayoutParams.TYPE_DREAM;
        lp.token = windowToken;
        lp.windowAnimations = com.android.internal.R.style.Animation_Dream;
        
        //WindowManagerImpl.getDefault().addView(mWindow.getDecorView(), lp);
        
        if (DEBUG) Slog.v(TAG, "created and attached window: " + mWindow);

        mWindow.setWindowManager(null, windowToken, "dream", true);
        mWindowManager = mWindow.getWindowManager();
        
        // now make it visible
        mHandler.post(new Runnable(){
            @Override
            public void run() {
                if (DEBUG) Slog.v(TAG, "Dream window added on thread " + Thread.currentThread().getId());
                
                getWindowManager().addView(mWindow.getDecorView(), mWindow.getAttributes());
            }});        
    }
    
    /**
     * Stop the dream and wake up.
     * 
     * After this method is called, the service will be stopped.
     */
    public void finish() {
        if (mFinished) return;
        try {
            mSandman.awaken(); // assuming we were started by the DreamManager
            stopSelf(); // if launched via any other means
            mFinished = true;
        } catch (RemoteException ex) {
            // sigh
        }
    }

    class IDreamServiceWrapper extends IDreamService.Stub {
        public IDreamServiceWrapper() {
        }

        public void attach(IBinder windowToken) {
            Dream.this.attach(windowToken);
        }
    }

    /**
     * Implement to return the implementation of the internal accessibility
     * service interface.  Subclasses should not override.
     */
    @Override
    public final IBinder onBind(Intent intent) {
        return new IDreamServiceWrapper();
    }
}
