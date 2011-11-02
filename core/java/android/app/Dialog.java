/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app;

import com.android.internal.app.ActionBarImpl;
import com.android.internal.policy.PolicyManager;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnCreateContextMenuListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.lang.ref.WeakReference;

/**
 * Base class for Dialogs.
 * 
 * <p>Note: Activities provide a facility to manage the creation, saving and
 * restoring of dialogs. See {@link Activity#onCreateDialog(int)},
 * {@link Activity#onPrepareDialog(int, Dialog)},
 * {@link Activity#showDialog(int)}, and {@link Activity#dismissDialog(int)}. If
 * these methods are used, {@link #getOwnerActivity()} will return the Activity
 * that managed this dialog.
 * 
 * <p>Often you will want to have a Dialog display on top of the current
 * input method, because there is no reason for it to accept text.  You can
 * do this by setting the {@link WindowManager.LayoutParams#FLAG_ALT_FOCUSABLE_IM
 * WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM} window flag (assuming
 * your Dialog takes input focus, as it the default) with the following code:
 * 
 * <pre>
 * getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
 *         WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);</pre>
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For more information about creating dialogs, read the
 * <a href="{@docRoot}guide/topics/ui/dialogs.html">Dialogs</a> developer guide.</p>
 * </div>
 */
public class Dialog implements DialogInterface, Window.Callback,
        KeyEvent.Callback, OnCreateContextMenuListener {
    private static final String TAG = "Dialog";
    private Activity mOwnerActivity;
    
    final Context mContext;
    final WindowManager mWindowManager;
    Window mWindow;
    View mDecor;
    private ActionBarImpl mActionBar;
    /**
     * This field should be made private, so it is hidden from the SDK.
     * {@hide}
     */
    protected boolean mCancelable = true;

    private String mCancelAndDismissTaken;
    private Message mCancelMessage;
    private Message mDismissMessage;
    private Message mShowMessage;

    private OnKeyListener mOnKeyListener;

    private boolean mCreated = false;
    private boolean mShowing = false;
    private boolean mCanceled = false;

    private final Thread mUiThread;
    private final Handler mHandler = new Handler();

    private static final int DISMISS = 0x43;
    private static final int CANCEL = 0x44;
    private static final int SHOW = 0x45;

    private Handler mListenersHandler;

    private ActionMode mActionMode;

    private final Runnable mDismissAction = new Runnable() {
        public void run() {
            dismissDialog();
        }
    };

    /**
     * Create a Dialog window that uses the default dialog frame style.
     * 
     * @param context The Context the Dialog is to run it.  In particular, it
     *                uses the window manager and theme in this context to
     *                present its UI.
     */
    public Dialog(Context context) {
        this(context, 0, true);
    }

    /**
     * Create a Dialog window that uses a custom dialog style.
     * 
     * @param context The Context in which the Dialog should run. In particular, it
     *                uses the window manager and theme from this context to
     *                present its UI.
     * @param theme A style resource describing the theme to use for the 
     * window. See <a href="{@docRoot}guide/topics/resources/available-resources.html#stylesandthemes">Style 
     * and Theme Resources</a> for more information about defining and using 
     * styles.  This theme is applied on top of the current theme in 
     * <var>context</var>.  If 0, the default dialog theme will be used.
     */
    public Dialog(Context context, int theme) {
        this(context, theme, true);
    }

    Dialog(Context context, int theme, boolean createContextWrapper) {
        if (theme == 0) {
            TypedValue outValue = new TypedValue();
            context.getTheme().resolveAttribute(com.android.internal.R.attr.dialogTheme,
                    outValue, true);
            theme = outValue.resourceId;
        }

        mContext = createContextWrapper ? new ContextThemeWrapper(context, theme) : context;
        mWindowManager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        Window w = PolicyManager.makeNewWindow(mContext);
        mWindow = w;
        w.setCallback(this);
        w.setWindowManager(mWindowManager, null, null);
        w.setGravity(Gravity.CENTER);
        mUiThread = Thread.currentThread();
        mListenersHandler = new ListenersHandler(this);
    }
    
    /**
     * @deprecated
     * @hide
     */
    @Deprecated
    protected Dialog(Context context, boolean cancelable,
            Message cancelCallback) {
        this(context);
        mCancelable = cancelable;
        mCancelMessage = cancelCallback;
    }

    protected Dialog(Context context, boolean cancelable,
            OnCancelListener cancelListener) {
        this(context);
        mCancelable = cancelable;
        setOnCancelListener(cancelListener);
    }

    /**
     * Retrieve the Context this Dialog is running in.
     * 
     * @return Context The Context used by the Dialog.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Retrieve the {@link ActionBar} attached to this dialog, if present.
     *
     * @return The ActionBar attached to the dialog or null if no ActionBar is present.
     */
    public ActionBar getActionBar() {
        return mActionBar;
    }

    /**
     * Sets the Activity that owns this dialog. An example use: This Dialog will
     * use the suggested volume control stream of the Activity.
     * 
     * @param activity The Activity that owns this dialog.
     */
    public final void setOwnerActivity(Activity activity) {
        mOwnerActivity = activity;
        
        getWindow().setVolumeControlStream(mOwnerActivity.getVolumeControlStream());
    }

    /**
     * Returns the Activity that owns this Dialog. For example, if
     * {@link Activity#showDialog(int)} is used to show this Dialog, that
     * Activity will be the owner (by default). Depending on how this dialog was
     * created, this may return null.
     * 
     * @return The Activity that owns this Dialog.
     */
    public final Activity getOwnerActivity() {
        return mOwnerActivity;
    }
    
    /**
     * @return Whether the dialog is currently showing.
     */
    public boolean isShowing() {
        return mShowing;
    }

    /**
     * Start the dialog and display it on screen.  The window is placed in the
     * application layer and opaque.  Note that you should not override this
     * method to do initialization when the dialog is shown, instead implement
     * that in {@link #onStart}.
     */
    public void show() {
        if (mShowing) {
            if (mDecor != null) {
                if (mWindow.hasFeature(Window.FEATURE_ACTION_BAR)) {
                    mWindow.invalidatePanelMenu(Window.FEATURE_ACTION_BAR);
                }
                mDecor.setVisibility(View.VISIBLE);
            }
            return;
        }

        mCanceled = false;
        
        if (!mCreated) {
            dispatchOnCreate(null);
        }

        onStart();
        mDecor = mWindow.getDecorView();

        if (mActionBar == null && mWindow.hasFeature(Window.FEATURE_ACTION_BAR)) {
            mActionBar = new ActionBarImpl(this);
        }

        WindowManager.LayoutParams l = mWindow.getAttributes();
        if ((l.softInputMode
                & WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION) == 0) {
            WindowManager.LayoutParams nl = new WindowManager.LayoutParams();
            nl.copyFrom(l);
            nl.softInputMode |=
                    WindowManager.LayoutParams.SOFT_INPUT_IS_FORWARD_NAVIGATION;
            l = nl;
        }

        try {
            mWindowManager.addView(mDecor, l);
            mShowing = true;
    
            sendShowMessage();
        } finally {
        }
    }
    
    /**
     * Hide the dialog, but do not dismiss it.
     */
    public void hide() {
        if (mDecor != null) {
            mDecor.setVisibility(View.GONE);
        }
    }

    /**
     * Dismiss this dialog, removing it from the screen. This method can be
     * invoked safely from any thread.  Note that you should not override this
     * method to do cleanup when the dialog is dismissed, instead implement
     * that in {@link #onStop}.
     */
    public void dismiss() {
        if (Thread.currentThread() != mUiThread) {
            mHandler.post(mDismissAction);
        } else {
            mHandler.removeCallbacks(mDismissAction);
            mDismissAction.run();
        }
    }

    void dismissDialog() {
        if (mDecor == null || !mShowing) {
            return;
        }

        if (mWindow.isDestroyed()) {
            Log.e(TAG, "Tried to dismissDialog() but the Dialog's window was already destroyed!");
            return;
        }

        try {
            mWindowManager.removeView(mDecor);
        } finally {
            if (mActionMode != null) {
                mActionMode.finish();
            }
            mDecor = null;
            mWindow.closeAllPanels();
            onStop();
            mShowing = false;
            
            sendDismissMessage();
        }
    }

    private void sendDismissMessage() {
        if (mDismissMessage != null) {
            // Obtain a new message so this dialog can be re-used
            Message.obtain(mDismissMessage).sendToTarget();
        }
    }

    private void sendShowMessage() {
        if (mShowMessage != null) {
            // Obtain a new message so this dialog can be re-used
            Message.obtain(mShowMessage).sendToTarget();
        }
    }

    // internal method to make sure mcreated is set properly without requiring
    // users to call through to super in onCreate
    void dispatchOnCreate(Bundle savedInstanceState) {
        if (!mCreated) {
            onCreate(savedInstanceState);
            mCreated = true;
        }
    }

    /**
     * Similar to {@link Activity#onCreate}, you should initialize your dialog
     * in this method, including calling {@link #setContentView}.
     * @param savedInstanceState If this dialog is being reinitalized after a
     *     the hosting activity was previously shut down, holds the result from
     *     the most recent call to {@link #onSaveInstanceState}, or null if this
     *     is the first time.
     */
    protected void onCreate(Bundle savedInstanceState) {
    }

    /**
     * Called when the dialog is starting.
     */
    protected void onStart() {
        if (mActionBar != null) mActionBar.setShowHideAnimationEnabled(true);
    }

    /**
     * Called to tell you that you're stopping.
     */
    protected void onStop() {
        if (mActionBar != null) mActionBar.setShowHideAnimationEnabled(false);
    }

    private static final String DIALOG_SHOWING_TAG = "android:dialogShowing";
    private static final String DIALOG_HIERARCHY_TAG = "android:dialogHierarchy";

    /**
     * Saves the state of the dialog into a bundle.
     *
     * The default implementation saves the state of its view hierarchy, so you'll
     * likely want to call through to super if you override this to save additional
     * state.
     * @return A bundle with the state of the dialog.
     */
    public Bundle onSaveInstanceState() {
        Bundle bundle = new Bundle();
        bundle.putBoolean(DIALOG_SHOWING_TAG, mShowing);
        if (mCreated) {
            bundle.putBundle(DIALOG_HIERARCHY_TAG, mWindow.saveHierarchyState());
        }
        return bundle;
    }

    /**
     * Restore the state of the dialog from a previously saved bundle.
     *
     * The default implementation restores the state of the dialog's view
     * hierarchy that was saved in the default implementation of {@link #onSaveInstanceState()},
     * so be sure to call through to super when overriding unless you want to
     * do all restoring of state yourself.
     * @param savedInstanceState The state of the dialog previously saved by
     *     {@link #onSaveInstanceState()}.
     */
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        final Bundle dialogHierarchyState = savedInstanceState.getBundle(DIALOG_HIERARCHY_TAG);
        if (dialogHierarchyState == null) {
            // dialog has never been shown, or onCreated, nothing to restore.
            return;
        }
        dispatchOnCreate(savedInstanceState);
        mWindow.restoreHierarchyState(dialogHierarchyState);
        if (savedInstanceState.getBoolean(DIALOG_SHOWING_TAG)) {
            show();
        }
    }

    /**
     * Retrieve the current Window for the activity.  This can be used to
     * directly access parts of the Window API that are not available
     * through Activity/Screen.
     * 
     * @return Window The current window, or null if the activity is not
     *         visual.
     */
    public Window getWindow() {
        return mWindow;
    }

    /**
     * Call {@link android.view.Window#getCurrentFocus} on the
     * Window if this Activity to return the currently focused view.
     * 
     * @return View The current View with focus or null.
     * 
     * @see #getWindow
     * @see android.view.Window#getCurrentFocus
     */
    public View getCurrentFocus() {
        return mWindow != null ? mWindow.getCurrentFocus() : null;
    }

    /**
     * Finds a view that was identified by the id attribute from the XML that
     * was processed in {@link #onStart}.
     *
     * @param id the identifier of the view to find
     * @return The view if found or null otherwise.
     */
    public View findViewById(int id) {
        return mWindow.findViewById(id);
    }

    /**
     * Set the screen content from a layout resource.  The resource will be
     * inflated, adding all top-level views to the screen.
     * 
     * @param layoutResID Resource ID to be inflated.
     */
    public void setContentView(int layoutResID) {
        mWindow.setContentView(layoutResID);
    }

    /**
     * Set the screen content to an explicit view.  This view is placed
     * directly into the screen's view hierarchy.  It can itself be a complex
     * view hierarhcy.
     * 
     * @param view The desired content to display.
     */
    public void setContentView(View view) {
        mWindow.setContentView(view);
    }

    /**
     * Set the screen content to an explicit view.  This view is placed
     * directly into the screen's view hierarchy.  It can itself be a complex
     * view hierarhcy.
     * 
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void setContentView(View view, ViewGroup.LayoutParams params) {
        mWindow.setContentView(view, params);
    }

    /**
     * Add an additional content view to the screen.  Added after any existing
     * ones in the screen -- existing views are NOT removed.
     * 
     * @param view The desired content to display.
     * @param params Layout parameters for the view.
     */
    public void addContentView(View view, ViewGroup.LayoutParams params) {
        mWindow.addContentView(view, params);
    }

    /**
     * Set the title text for this dialog's window.
     * 
     * @param title The new text to display in the title.
     */
    public void setTitle(CharSequence title) {
        mWindow.setTitle(title);
        mWindow.getAttributes().setTitle(title);
    }

    /**
     * Set the title text for this dialog's window. The text is retrieved
     * from the resources with the supplied identifier.
     *
     * @param titleId the title's text resource identifier
     */
    public void setTitle(int titleId) {
        setTitle(mContext.getText(titleId));
    }

    /**
     * A key was pressed down.
     * 
     * <p>If the focused view didn't want this event, this method is called.
     *
     * <p>The default implementation consumed the KEYCODE_BACK to later
     * handle it in {@link #onKeyUp}.
     *
     * @see #onKeyUp
     * @see android.view.KeyEvent
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking();
            return true;
        }

        return false;
    }

    /**
     * Default implementation of {@link KeyEvent.Callback#onKeyLongPress(int, KeyEvent)
     * KeyEvent.Callback.onKeyLongPress()}: always returns false (doesn't handle
     * the event).
     */
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * A key was released.
     * 
     * <p>The default implementation handles KEYCODE_BACK to close the
     * dialog.
     *
     * @see #onKeyDown
     * @see KeyEvent
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.isTracking()
                && !event.isCanceled()) {
            onBackPressed();
            return true;
        }
        return false;
    }

    /**
     * Default implementation of {@link KeyEvent.Callback#onKeyMultiple(int, int, KeyEvent)
     * KeyEvent.Callback.onKeyMultiple()}: always returns false (doesn't handle
     * the event).
     */
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return false;
    }
    
    /**
     * Called when the dialog has detected the user's press of the back
     * key.  The default implementation simply cancels the dialog (only if
     * it is cancelable), but you can override this to do whatever you want.
     */
    public void onBackPressed() {
        if (mCancelable) {
            cancel();
        }
    }

    /**
     * Called when an key shortcut event is not handled by any of the views in the Dialog.
     * Override this method to implement global key shortcuts for the Dialog.
     * Key shortcuts can also be implemented by setting the
     * {@link MenuItem#setShortcut(char, char) shortcut} property of menu items.
     *
     * @param keyCode The value in event.getKeyCode().
     * @param event Description of the key event.
     * @return True if the key shortcut was handled.
     */
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return false;
    }

    /**
     * Called when a touch screen event was not handled by any of the views
     * under it. This is most useful to process touch events that happen outside
     * of your window bounds, where there is no view to receive it.
     * 
     * @param event The touch screen event being processed.
     * @return Return true if you have consumed the event, false if you haven't.
     *         The default implementation will cancel the dialog when a touch
     *         happens outside of the window bounds.
     */
    public boolean onTouchEvent(MotionEvent event) {
        if (mCancelable && mShowing && mWindow.shouldCloseOnTouch(mContext, event)) {
            cancel();
            return true;
        }
        
        return false;
    }

    /**
     * Called when the trackball was moved and not handled by any of the
     * views inside of the activity.  So, for example, if the trackball moves
     * while focus is on a button, you will receive a call here because
     * buttons do not normally do anything with trackball events.  The call
     * here happens <em>before</em> trackball movements are converted to
     * DPAD key events, which then get sent back to the view hierarchy, and
     * will be processed at the point for things like focus navigation.
     * 
     * @param event The trackball event being processed.
     * 
     * @return Return true if you have consumed the event, false if you haven't.
     * The default implementation always returns false.
     */
    public boolean onTrackballEvent(MotionEvent event) {
        return false;
    }

    /**
     * Called when a generic motion event was not handled by any of the
     * views inside of the dialog.
     * <p>
     * Generic motion events describe joystick movements, mouse hovers, track pad
     * touches, scroll wheel movements and other input events.  The
     * {@link MotionEvent#getSource() source} of the motion event specifies
     * the class of input that was received.  Implementations of this method
     * must examine the bits in the source before processing the event.
     * The following code example shows how this is done.
     * </p><p>
     * Generic motion events with source class
     * {@link android.view.InputDevice#SOURCE_CLASS_POINTER}
     * are delivered to the view under the pointer.  All other generic motion events are
     * delivered to the focused view.
     * </p><p>
     * See {@link View#onGenericMotionEvent(MotionEvent)} for an example of how to
     * handle this event.
     * </p>
     *
     * @param event The generic motion event being processed.
     *
     * @return Return true if you have consumed the event, false if you haven't.
     * The default implementation always returns false.
     */
    public boolean onGenericMotionEvent(MotionEvent event) {
        return false;
    }

    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        if (mDecor != null) {
            mWindowManager.updateViewLayout(mDecor, params);
        }
    }

    public void onContentChanged() {
    }
    
    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onAttachedToWindow() {
    }
    
    public void onDetachedFromWindow() {
    }
    
    /**
     * Called to process key events.  You can override this to intercept all 
     * key events before they are dispatched to the window.  Be sure to call 
     * this implementation for key events that should be handled normally.
     * 
     * @param event The key event.
     * 
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchKeyEvent(KeyEvent event) {
        if ((mOnKeyListener != null) && (mOnKeyListener.onKey(this, event.getKeyCode(), event))) {
            return true;
        }
        if (mWindow.superDispatchKeyEvent(event)) {
            return true;
        }
        return event.dispatch(this, mDecor != null
                ? mDecor.getKeyDispatcherState() : null, this);
    }

    /**
     * Called to process a key shortcut event.
     * You can override this to intercept all key shortcut events before they are
     * dispatched to the window.  Be sure to call this implementation for key shortcut
     * events that should be handled normally.
     *
     * @param event The key shortcut event.
     * @return True if this event was consumed.
     */
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        if (mWindow.superDispatchKeyShortcutEvent(event)) {
            return true;
        }
        return onKeyShortcut(event.getKeyCode(), event);
    }

    /**
     * Called to process touch screen events.  You can override this to
     * intercept all touch screen events before they are dispatched to the
     * window.  Be sure to call this implementation for touch screen events
     * that should be handled normally.
     * 
     * @param ev The touch screen event.
     * 
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mWindow.superDispatchTouchEvent(ev)) {
            return true;
        }
        return onTouchEvent(ev);
    }
    
    /**
     * Called to process trackball events.  You can override this to
     * intercept all trackball events before they are dispatched to the
     * window.  Be sure to call this implementation for trackball events
     * that should be handled normally.
     * 
     * @param ev The trackball event.
     * 
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        if (mWindow.superDispatchTrackballEvent(ev)) {
            return true;
        }
        return onTrackballEvent(ev);
    }

    /**
     * Called to process generic motion events.  You can override this to
     * intercept all generic motion events before they are dispatched to the
     * window.  Be sure to call this implementation for generic motion events
     * that should be handled normally.
     *
     * @param ev The generic motion event.
     *
     * @return boolean Return true if this event was consumed.
     */
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        if (mWindow.superDispatchGenericMotionEvent(ev)) {
            return true;
        }
        return onGenericMotionEvent(ev);
    }

    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        event.setClassName(getClass().getName());
        event.setPackageName(mContext.getPackageName());

        LayoutParams params = getWindow().getAttributes();
        boolean isFullScreen = (params.width == LayoutParams.MATCH_PARENT) &&
            (params.height == LayoutParams.MATCH_PARENT);
        event.setFullScreen(isFullScreen);

        return false;
    }

    /**
     * @see Activity#onCreatePanelView(int)
     */
    public View onCreatePanelView(int featureId) {
        return null;
    }

    /**
     * @see Activity#onCreatePanelMenu(int, Menu)
     */
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL) {
            return onCreateOptionsMenu(menu);
        }
        
        return false;
    }

    /**
     * @see Activity#onPreparePanel(int, View, Menu)
     */
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        if (featureId == Window.FEATURE_OPTIONS_PANEL && menu != null) {
            boolean goforit = onPrepareOptionsMenu(menu);
            return goforit && menu.hasVisibleItems();
        }
        return true;
    }

    /**
     * @see Activity#onMenuOpened(int, Menu)
     */
    public boolean onMenuOpened(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_ACTION_BAR) {
            mActionBar.dispatchMenuVisibilityChanged(true);
        }
        return true;
    }

    /**
     * @see Activity#onMenuItemSelected(int, MenuItem)
     */
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return false;
    }

    /**
     * @see Activity#onPanelClosed(int, Menu)
     */
    public void onPanelClosed(int featureId, Menu menu) {
        if (featureId == Window.FEATURE_ACTION_BAR) {
            mActionBar.dispatchMenuVisibilityChanged(false);
        }
    }

    /**
     * It is usually safe to proxy this call to the owner activity's
     * {@link Activity#onCreateOptionsMenu(Menu)} if the client desires the same
     * menu for this Dialog.
     * 
     * @see Activity#onCreateOptionsMenu(Menu)
     * @see #getOwnerActivity()
     */
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    /**
     * It is usually safe to proxy this call to the owner activity's
     * {@link Activity#onPrepareOptionsMenu(Menu)} if the client desires the
     * same menu for this Dialog.
     * 
     * @see Activity#onPrepareOptionsMenu(Menu)
     * @see #getOwnerActivity()
     */
    public boolean onPrepareOptionsMenu(Menu menu) {
        return true;
    }

    /**
     * @see Activity#onOptionsItemSelected(MenuItem)
     */
    public boolean onOptionsItemSelected(MenuItem item) {
        return false;
    }

    /**
     * @see Activity#onOptionsMenuClosed(Menu)
     */
    public void onOptionsMenuClosed(Menu menu) {
    }

    /**
     * @see Activity#openOptionsMenu()
     */
    public void openOptionsMenu() {
        mWindow.openPanel(Window.FEATURE_OPTIONS_PANEL, null);
    }
    
    /**
     * @see Activity#closeOptionsMenu()
     */
    public void closeOptionsMenu() {
        mWindow.closePanel(Window.FEATURE_OPTIONS_PANEL);
    }

    /**
     * @see Activity#invalidateOptionsMenu()
     */
    public void invalidateOptionsMenu() {
        mWindow.invalidatePanelMenu(Window.FEATURE_OPTIONS_PANEL);
    }

    /**
     * @see Activity#onCreateContextMenu(ContextMenu, View, ContextMenuInfo)
     */
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
    }

    /**
     * @see Activity#registerForContextMenu(View)
     */
    public void registerForContextMenu(View view) {
        view.setOnCreateContextMenuListener(this);
    }
    
    /**
     * @see Activity#unregisterForContextMenu(View)
     */
    public void unregisterForContextMenu(View view) {
        view.setOnCreateContextMenuListener(null);
    }
    
    /**
     * @see Activity#openContextMenu(View)
     */
    public void openContextMenu(View view) {
        view.showContextMenu();
    }

    /**
     * @see Activity#onContextItemSelected(MenuItem)
     */
    public boolean onContextItemSelected(MenuItem item) {
        return false;
    }
    
    /**
     * @see Activity#onContextMenuClosed(Menu)
     */
    public void onContextMenuClosed(Menu menu) {
    }
    
    /**
     * This hook is called when the user signals the desire to start a search.
     */
    public boolean onSearchRequested() {
        final SearchManager searchManager = (SearchManager) mContext
                .getSystemService(Context.SEARCH_SERVICE);

        // associate search with owner activity
        final ComponentName appName = getAssociatedActivity();
        if (appName != null && searchManager.getSearchableInfo(appName) != null) {
            searchManager.startSearch(null, false, appName, null, false);
            dismiss();
            return true;
        } else {
            return false;
        }
    }

    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        if (mActionBar != null) {
            return mActionBar.startActionMode(callback);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * Note that if you override this method you should always call through
     * to the superclass implementation by calling super.onActionModeStarted(mode).
     */
    public void onActionModeStarted(ActionMode mode) {
        mActionMode = mode;
    }

    /**
     * {@inheritDoc}
     *
     * Note that if you override this method you should always call through
     * to the superclass implementation by calling super.onActionModeFinished(mode).
     */
    public void onActionModeFinished(ActionMode mode) {
        if (mode == mActionMode) {
            mActionMode = null;
        }
    }

    /**
     * @return The activity associated with this dialog, or null if there is no associated activity.
     */
    private ComponentName getAssociatedActivity() {
        Activity activity = mOwnerActivity;
        Context context = getContext();
        while (activity == null && context != null) {
            if (context instanceof Activity) {
                activity = (Activity) context;  // found it!
            } else {
                context = (context instanceof ContextWrapper) ?
                        ((ContextWrapper) context).getBaseContext() : // unwrap one level
                        null;                                         // done
            }
        }
        return activity == null ? null : activity.getComponentName();
    }


    /**
     * Request that key events come to this dialog. Use this if your
     * dialog has no views with focus, but the dialog still wants
     * a chance to process key events.
     * 
     * @param get true if the dialog should receive key events, false otherwise
     * @see android.view.Window#takeKeyEvents
     */
    public void takeKeyEvents(boolean get) {
        mWindow.takeKeyEvents(get);
    }

    /**
     * Enable extended window features.  This is a convenience for calling
     * {@link android.view.Window#requestFeature getWindow().requestFeature()}.
     * 
     * @param featureId The desired feature as defined in
     *                  {@link android.view.Window}.
     * @return Returns true if the requested feature is supported and now
     *         enabled.
     * 
     * @see android.view.Window#requestFeature
     */
    public final boolean requestWindowFeature(int featureId) {
        return getWindow().requestFeature(featureId);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableResource}.
     */
    public final void setFeatureDrawableResource(int featureId, int resId) {
        getWindow().setFeatureDrawableResource(featureId, resId);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableUri}.
     */
    public final void setFeatureDrawableUri(int featureId, Uri uri) {
        getWindow().setFeatureDrawableUri(featureId, uri);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawable(int, Drawable)}.
     */
    public final void setFeatureDrawable(int featureId, Drawable drawable) {
        getWindow().setFeatureDrawable(featureId, drawable);
    }

    /**
     * Convenience for calling
     * {@link android.view.Window#setFeatureDrawableAlpha}.
     */
    public final void setFeatureDrawableAlpha(int featureId, int alpha) {
        getWindow().setFeatureDrawableAlpha(featureId, alpha);
    }

    public LayoutInflater getLayoutInflater() {
        return getWindow().getLayoutInflater();
    }

    /**
     * Sets whether this dialog is cancelable with the
     * {@link KeyEvent#KEYCODE_BACK BACK} key.
     */
    public void setCancelable(boolean flag) {
        mCancelable = flag;
    }

    /**
     * Sets whether this dialog is canceled when touched outside the window's
     * bounds. If setting to true, the dialog is set to be cancelable if not
     * already set.
     * 
     * @param cancel Whether the dialog should be canceled when touched outside
     *            the window.
     */
    public void setCanceledOnTouchOutside(boolean cancel) {
        if (cancel && !mCancelable) {
            mCancelable = true;
        }
        
        mWindow.setCloseOnTouchOutside(cancel);
    }
    
    /**
     * Cancel the dialog.  This is essentially the same as calling {@link #dismiss()}, but it will
     * also call your {@link DialogInterface.OnCancelListener} (if registered).
     */
    public void cancel() {
        if (!mCanceled && mCancelMessage != null) {
            mCanceled = true;
            // Obtain a new message so this dialog can be re-used
            Message.obtain(mCancelMessage).sendToTarget();
        }
        dismiss();
    }

    /**
     * Set a listener to be invoked when the dialog is canceled.
     * <p>
     * This will only be invoked when the dialog is canceled, if the creator
     * needs to know when it is dismissed in general, use
     * {@link #setOnDismissListener}.
     * 
     * @param listener The {@link DialogInterface.OnCancelListener} to use.
     */
    public void setOnCancelListener(final OnCancelListener listener) {
        if (mCancelAndDismissTaken != null) {
            throw new IllegalStateException(
                    "OnCancelListener is already taken by "
                    + mCancelAndDismissTaken + " and can not be replaced.");
        }
        if (listener != null) {
            mCancelMessage = mListenersHandler.obtainMessage(CANCEL, listener);
        } else {
            mCancelMessage = null;
        }
    }

    /**
     * Set a message to be sent when the dialog is canceled.
     * @param msg The msg to send when the dialog is canceled.
     * @see #setOnCancelListener(android.content.DialogInterface.OnCancelListener)
     */
    public void setCancelMessage(final Message msg) {
        mCancelMessage = msg;
    }

    /**
     * Set a listener to be invoked when the dialog is dismissed.
     * @param listener The {@link DialogInterface.OnDismissListener} to use.
     */
    public void setOnDismissListener(final OnDismissListener listener) {
        if (mCancelAndDismissTaken != null) {
            throw new IllegalStateException(
                    "OnDismissListener is already taken by "
                    + mCancelAndDismissTaken + " and can not be replaced.");
        }
        if (listener != null) {
            mDismissMessage = mListenersHandler.obtainMessage(DISMISS, listener);
        } else {
            mDismissMessage = null;
        }
    }

    /**
     * Sets a listener to be invoked when the dialog is shown.
     * @param listener The {@link DialogInterface.OnShowListener} to use.
     */
    public void setOnShowListener(OnShowListener listener) {
        if (listener != null) {
            mShowMessage = mListenersHandler.obtainMessage(SHOW, listener);
        } else {
            mShowMessage = null;
        }
    }

    /**
     * Set a message to be sent when the dialog is dismissed.
     * @param msg The msg to send when the dialog is dismissed.
     */
    public void setDismissMessage(final Message msg) {
        mDismissMessage = msg;
    }

    /** @hide */
    public boolean takeCancelAndDismissListeners(String msg, final OnCancelListener cancel,
            final OnDismissListener dismiss) {
        if (mCancelAndDismissTaken != null) {
            mCancelAndDismissTaken = null;
        } else if (mCancelMessage != null || mDismissMessage != null) {
            return false;
        }
        
        setOnCancelListener(cancel);
        setOnDismissListener(dismiss);
        mCancelAndDismissTaken = msg;
        
        return true;
    }
    
    /**
     * By default, this will use the owner Activity's suggested stream type.
     * 
     * @see Activity#setVolumeControlStream(int)
     * @see #setOwnerActivity(Activity)
     */
    public final void setVolumeControlStream(int streamType) {
        getWindow().setVolumeControlStream(streamType);
    }

    /**
     * @see Activity#getVolumeControlStream()
     */
    public final int getVolumeControlStream() {
        return getWindow().getVolumeControlStream();
    }
    
    /**
     * Sets the callback that will be called if a key is dispatched to the dialog.
     */
    public void setOnKeyListener(final OnKeyListener onKeyListener) {
        mOnKeyListener = onKeyListener;
    }

    private static final class ListenersHandler extends Handler {
        private WeakReference<DialogInterface> mDialog;

        public ListenersHandler(Dialog dialog) {
            mDialog = new WeakReference<DialogInterface>(dialog);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DISMISS:
                    ((OnDismissListener) msg.obj).onDismiss(mDialog.get());
                    break;
                case CANCEL:
                    ((OnCancelListener) msg.obj).onCancel(mDialog.get());
                    break;
                case SHOW:
                    ((OnShowListener) msg.obj).onShow(mDialog.get());
                    break;
            }
        }
    }
}
