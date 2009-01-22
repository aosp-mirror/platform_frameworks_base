/*
 * Copyright (C) 2007-2008 The Android Open Source Project
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

package android.inputmethodservice;

import static android.view.ViewGroup.LayoutParams.FILL_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;

/**
 * InputMethodService provides a standard implementation of an InputMethod,
 * which final implementations can derive from and customize.  See the
 * base class {@link AbstractInputMethodService} and the {@link InputMethod}
 * interface for more information on the basics of writing input methods.
 * 
 * <p>An input method has significant discretion in how it goes about its
 * work: the {@link android.inputmethodservice.InputMethodService} provides
 * a basic framework for standard UI elements (input view, candidates view,
 * and running in fullscreen mode), but it is up to a particular implementor
 * to decide how to use them.  For example, one input method could implement
 * an input area with a keyboard, another could allow the user to draw text,
 * while a third could have no input area (and thus not be visible to the
 * user) but instead listen to audio and perform text to speech conversion.</p>
 * 
 * <p>In the implementation provided here, all of these elements are placed
 * together in a single window managed by the InputMethodService.  It will
 * execute callbacks as it needs information about them, and provides APIs for
 * programmatic control over them.  They layout of these elements is explicitly
 * defined:</p>
 * 
 * <ul>
 * <li>The soft input view, if available, is placed at the bottom of the
 * screen.
 * <li>The candidates view, if currently shown, is placed above the soft
 * input view.
 * <li>If not running fullscreen, the application is moved or resized to be
 * above these views; if running fullscreen, the window will completely cover
 * the application and its top part will contain the extract text of what is
 * currently being edited by the application.
 * </ul>
 * 
 * 
 * <a name="SoftInputView"></a>
 * <h3>Soft Input View</h3>
 * 
 * <p>Central to most input methods is the soft input view.  This is where most
 * user interaction occurs: pressing on soft keys, drawing characters, or
 * however else your input method wants to generate text.  Most implementations
 * will simply have their own view doing all of this work, and return a new
 * instance of it when {@link #onCreateInputView()} is called.  At that point,
 * as long as the input view is visible, you will see user interaction in
 * that view and can call back on the InputMethodService to interact with the
 * application as appropriate.</p>
 * 
 * <p>There are some situations where you want to decide whether or not your
 * soft input view should be shown to the user.  This is done by implementing
 * the {@link #onEvaluateInputViewShown()} to return true or false based on
 * whether it should be shown in the current environment.  If any of your
 * state has changed that may impact this, call
 * {@link #updateInputViewShown()} to have it re-evaluated.  The default
 * implementation always shows the input view unless there is a hard
 * keyboard available, which is the appropriate behavior for most input
 * methods.</p>
 * 
 * 
 * <a name="CandidatesView"></a>
 * <h3>Candidates View</h3>
 * 
 * <p>Often while the user is generating raw text, an input method wants to
 * provide them with a list of possible interpretations of that text that can
 * be selected for use.  This is accomplished with the candidates view, and
 * like the soft input view you implement {@link #onCreateCandidatesView()}
 * to instantiate your own view implementing your candidates UI.</p>
 * 
 * <p>Management of the candidates view is a little different than the input
 * view, because the candidates view tends to be more transient, being shown
 * only when there are possible candidates for the current text being entered
 * by the user.  To control whether the candidates view is shown, you use
 * {@link #setCandidatesViewShown(boolean)}.  Note that because the candidate
 * view tends to be shown and hidden a lot, it does not impact the application
 * UI in the same way as the soft input view: it will never cause application
 * windows to resize, only cause them to be panned if needed for the user to
 * see the current focus.</p>
 * 
 * 
 * <a name="FullscreenMode"></a>
 * <h3>Fullscreen Mode</h3>
 * 
 * <p>Sometimes your input method UI is too large to integrate with the
 * application UI, so you just want to take over the screen.  This is
 * accomplished by switching to full-screen mode, causing the input method
 * window to fill the entire screen and add its own "extracted text" editor
 * showing the user the text that is being typed.  Unlike the other UI elements,
 * there is a standard implementation for the extract editor that you should
 * not need to change.  The editor is placed at the top of the IME, above the
 * input and candidates views.</p>
 * 
 * <p>Similar to the input view, you control whether the IME is running in
 * fullscreen mode by implementing {@link #onEvaluateFullscreenMode()}
 * to return true or false based on
 * whether it should be fullscreen in the current environment.  If any of your
 * state has changed that may impact this, call
 * {@link #updateFullscreenMode()} to have it re-evaluated.  The default
 * implementation selects fullscreen mode when the screen is in a landscape
 * orientation, which is appropriate behavior for most input methods that have
 * a significant input area.</p>
 * 
 * <p>When in fullscreen mode, you have some special requirements because the
 * user can not see the application UI.  In particular, you should implement
 * {@link #onDisplayCompletions(CompletionInfo[])} to show completions
 * generated by your application, typically in your candidates view like you
 * would normally show candidates.
 * 
 * 
 * <a name="GeneratingText"></a>
 * <h3>Generating Text</h3>
 * 
 * <p>The key part of an IME is of course generating text for the application.
 * This is done through calls to the
 * {@link android.view.inputmethod.InputConnection} interface to the
 * application, which can be retrieved from {@link #getCurrentInputConnection()}.
 * This interface allows you to generate raw key events or, if the target
 * supports it, directly edit in strings of candidates and committed text.</p>
 * 
 * <p>Information about what the target is expected and supports can be found
 * through the {@link android.view.inputmethod.EditorInfo} class, which is
 * retrieved with {@link #getCurrentInputEditorInfo()} method.  The most
 * important part of this is {@link android.view.inputmethod.EditorInfo#inputType
 * EditorInfo.inputType}; in particular, if this is
 * {@link android.view.inputmethod.EditorInfo#TYPE_NULL EditorInfo.TYPE_NULL},
 * then the target does not support complex edits and you need to only deliver
 * raw key events to it.  An input method will also want to look at other
 * values here, to for example detect password mode, auto complete text views,
 * phone number entry, etc.</p>
 * 
 * <p>When the user switches between input targets, you will receive calls to
 * {@link #onFinishInput()} and {@link #onStartInput(EditorInfo, boolean)}.
 * You can use these to reset and initialize your input state for the current
 * target.  For example, you will often want to clear any input state, and
 * update a soft keyboard to be appropriate for the new inputType.</p>
 */
public class InputMethodService extends AbstractInputMethodService {
    static final String TAG = "InputMethodService";
    static final boolean DEBUG = false;
    
    LayoutInflater mInflater;
    View mRootView;
    SoftInputWindow mWindow;
    boolean mWindowCreated;
    boolean mWindowAdded;
    boolean mWindowVisible;
    FrameLayout mExtractFrame;
    FrameLayout mCandidatesFrame;
    FrameLayout mInputFrame;
    
    IBinder mToken;
    
    InputBinding mInputBinding;
    InputConnection mInputConnection;
    boolean mInputStarted;
    EditorInfo mInputEditorInfo;
    
    boolean mShowInputRequested;
    boolean mLastShowInputRequested;
    boolean mShowCandidatesRequested;
    
    boolean mFullscreenApplied;
    boolean mIsFullscreen;
    View mExtractView;
    ExtractEditText mExtractEditText;
    ExtractedText mExtractedText;
    int mExtractedToken;
    
    View mInputView;
    boolean mIsInputViewShown;
    
    int mStatusIcon;

    final Insets mTmpInsets = new Insets();
    final int[] mTmpLocation = new int[2];
    
    final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
            if (isFullscreenMode()) {
                // In fullscreen mode, we just say the window isn't covering
                // any content so we don't impact whatever is behind.
                View decor = getWindow().getWindow().getDecorView();
                info.contentInsets.top = info.visibleInsets.top
                        = decor.getHeight();
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            } else {
                onComputeInsets(mTmpInsets);
                info.contentInsets.top = mTmpInsets.contentTopInsets;
                info.visibleInsets.top = mTmpInsets.visibleTopInsets;
                info.setTouchableInsets(mTmpInsets.touchableInsets);
            }
        }
    };

    /**
     * Concrete implementation of
     * {@link AbstractInputMethodService.AbstractInputMethodImpl} that provides
     * all of the standard behavior for an input method.
     */
    public class InputMethodImpl extends AbstractInputMethodImpl {
        /**
         * Take care of attaching the given window token provided by the system.
         */
        public void attachToken(IBinder token) {
            if (mToken == null) {
                mToken = token;
                mWindow.setToken(token);
            }
        }
        
        /**
         * Handle a new input binding, calling
         * {@link InputMethodService#onBindInput InputMethodService.onBindInput()}
         * when done.
         */
        public void bindInput(InputBinding binding) {
            mInputBinding = binding;
            mInputConnection = binding.getConnection();
            if (DEBUG) Log.v(TAG, "bindInput(): binding=" + binding
                    + " ic=" + mInputConnection);
            onBindInput();
        }

        /**
         * Clear the current input binding.
         */
        public void unbindInput() {
            if (DEBUG) Log.v(TAG, "unbindInput(): binding=" + mInputBinding
                    + " ic=" + mInputConnection);
            onUnbindInput();
            mInputStarted = false;
            mInputBinding = null;
            mInputConnection = null;
        }

        public void startInput(EditorInfo attribute) {
            if (DEBUG) Log.v(TAG, "startInput(): editor=" + attribute);
            doStartInput(attribute, false);
        }

        public void restartInput(EditorInfo attribute) {
            if (DEBUG) Log.v(TAG, "restartInput(): editor=" + attribute);
            doStartInput(attribute, true);
        }

        /**
         * Handle a request by the system to hide the soft input area.
         */
        public void hideSoftInput() {
            if (DEBUG) Log.v(TAG, "hideSoftInput()");
            mShowInputRequested = false;
            hideWindow();
        }

        /**
         * Handle a request by the system to show the soft input area.
         */
        public void showSoftInput(int flags) {
            if (DEBUG) Log.v(TAG, "showSoftInput()");
            onShowRequested(flags);
        }
    }
    
    /**
     * Concrete implementation of
     * {@link AbstractInputMethodService.AbstractInputMethodSessionImpl} that provides
     * all of the standard behavior for an input method session.
     */
    public class InputMethodSessionImpl extends AbstractInputMethodSessionImpl {
        public void finishInput() {
            if (!isEnabled()) {
                return;
            }
            if (DEBUG) Log.v(TAG, "finishInput() in " + this);
            onFinishInput();
            mInputStarted = false;
        }

        /**
         * Call {@link InputMethodService#onDisplayCompletions
         * InputMethodService.onDisplayCompletions()}.
         */
        public void displayCompletions(CompletionInfo[] completions) {
            if (!isEnabled()) {
                return;
            }
            onDisplayCompletions(completions);
        }
        
        /**
         * Call {@link InputMethodService#onUpdateExtractedText
         * InputMethodService.onUpdateExtractedText()}.
         */
        public void updateExtractedText(int token, ExtractedText text) {
            if (!isEnabled()) {
                return;
            }
            onUpdateExtractedText(token, text);
        }
        
        /**
         * Call {@link InputMethodService#onUpdateSelection
         * InputMethodService.onUpdateSelection()}.
         */
        public void updateSelection(int oldSelStart, int oldSelEnd,
                int newSelStart, int newSelEnd,
                int candidatesStart, int candidatesEnd) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onUpdateSelection(oldSelStart, oldSelEnd,
                    newSelStart, newSelEnd, candidatesStart, candidatesEnd);
        }
        
        /**
         * Call {@link InputMethodService#onUpdateCursor
         * InputMethodService.onUpdateCursor()}.
         */
        public void updateCursor(Rect newCursor) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onUpdateCursor(newCursor);
        }
        
        /**
         * Call {@link InputMethodService#onAppPrivateCommand
         * InputMethodService.onAppPrivateCommand()}.
         */
        public void appPrivateCommand(String action, Bundle data) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onAppPrivateCommand(action, data);
        }
    }
    
    /**
     * Information about where interesting parts of the input method UI appear.
     */
    public static final class Insets {
        /**
         * This is the top part of the UI that is the main content.  It is
         * used to determine the basic space needed, to resize/pan the
         * application behind.  It is assumed that this inset does not
         * change very much, since any change will cause a full resize/pan
         * of the application behind.  This value is relative to the top edge
         * of the input method window.
         */
        int contentTopInsets;
        
        /**
         * This is the top part of the UI that is visibly covering the
         * application behind it.  This provides finer-grained control over
         * visibility, allowing you to change it relatively frequently (such
         * as hiding or showing candidates) without disrupting the underlying
         * UI too much.  For example, this will never resize the application
         * UI, will only pan if needed to make the current focus visible, and
         * will not aggressively move the pan position when this changes unless
         * needed to make the focus visible.  This value is relative to the top edge
         * of the input method window.
         */
        int visibleTopInsets;
        
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
         * Option for {@link #touchableInsets}: the area inside of
         * the visible insets can be touched.
         */
        public static final int TOUCHABLE_INSETS_VISIBLE
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;
        
        /**
         * Determine which area of the window is touchable by the user.  May
         * be one of: {@link #TOUCHABLE_INSETS_FRAME},
         * {@link #TOUCHABLE_INSETS_CONTENT}, or {@link #TOUCHABLE_INSETS_VISIBLE}. 
         */
        public int touchableInsets;
    }
    
    @Override public void onCreate() {
        super.onCreate();
        mInflater = (LayoutInflater)getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mWindow = new SoftInputWindow(this);
        initViews();
        mWindow.getWindow().setLayout(FILL_PARENT, WRAP_CONTENT);
    }
    
    void initViews() {
        mWindowVisible = false;
        mWindowCreated = false;
        mShowInputRequested = false;
        mShowCandidatesRequested = false;
        
        mRootView = mInflater.inflate(
                com.android.internal.R.layout.input_method, null);
        mWindow.setContentView(mRootView);
        mRootView.getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsComputer);
        
        mExtractFrame = (FrameLayout)mRootView.findViewById(android.R.id.extractArea);
        mExtractView = null;
        mExtractEditText = null;
        mFullscreenApplied = false;
        
        mCandidatesFrame = (FrameLayout)mRootView.findViewById(android.R.id.candidatesArea);
        mInputFrame = (FrameLayout)mRootView.findViewById(android.R.id.inputArea);
        mInputView = null;
        mIsInputViewShown = false;
        
        mExtractFrame.setVisibility(View.GONE);
        mCandidatesFrame.setVisibility(View.INVISIBLE);
        mInputFrame.setVisibility(View.GONE);
    }
    
    @Override public void onDestroy() {
        super.onDestroy();
        mRootView.getViewTreeObserver().removeOnComputeInternalInsetsListener(
                mInsetsComputer);
        if (mWindowAdded) {
            mWindow.dismiss();
        }
    }
    
    /**
     * Take care of handling configuration changes.  Subclasses of
     * InputMethodService generally don't need to deal directly with
     * this on their own; the standard implementation here takes care of
     * regenerating the input method UI as a result of the configuration
     * change, so you can rely on your {@link #onCreateInputView} and
     * other methods being called as appropriate due to a configuration change.
     */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        boolean visible = mWindowVisible;
        boolean showingInput = mShowInputRequested;
        boolean showingCandidates = mShowCandidatesRequested;
        initViews();
        if (visible) {
            if (showingCandidates) {
                setCandidatesViewShown(true);
            }
            if (showingInput) {
                // If we are showing the full soft keyboard, then go through
                // this path to take care of current decisions about fullscreen
                // etc.
                onShowRequested(InputMethod.SHOW_EXPLICIT);
            } else {
                // Otherwise just put it back for its candidates.
                showWindow(false);
            }
        }
    }

    /**
     * Implement to return our standard {@link InputMethodImpl}.  Subclasses
     * can override to provide their own customized version.
     */
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new InputMethodImpl();
    }
    
    /**
     * Implement to return our standard {@link InputMethodSessionImpl}.  Subclasses
     * can override to provide their own customized version.
     */
    public AbstractInputMethodSessionImpl onCreateInputMethodSessionInterface() {
        return new InputMethodSessionImpl();
    }
    
    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }
    
    public Dialog getWindow() {
        return mWindow;
    }
    
    /**
     * Return the maximum width, in pixels, available the input method.
     * Input methods are positioned at the bottom of the screen and, unless
     * running in fullscreen, will generally want to be as short as possible
     * so should compute their height based on their contents.  However, they
     * can stretch as much as needed horizontally.  The function returns to
     * you the maximum amount of space available horizontally, which you can
     * use if needed for UI placement.
     * 
     * <p>In many cases this is not needed, you can just rely on the normal
     * view layout mechanisms to position your views within the full horizontal
     * space given to the input method.
     * 
     * <p>Note that this value can change dynamically, in particular when the
     * screen orientation changes.
     */
    public int getMaxWidth() {
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        return wm.getDefaultDisplay().getWidth();
    }
    
    /**
     * Return the currently active InputBinding for the input method, or
     * null if there is none.
     */
    public InputBinding getCurrentInputBinding() {
        return mInputBinding;
    }
    
    /**
     * Retrieve the currently active InputConnection that is bound to
     * the input method, or null if there is none.
     */
    public InputConnection getCurrentInputConnection() {
        return mInputConnection;
    }
    
    public boolean getCurrentInputStarted() {
        return mInputStarted;
    }
    
    public EditorInfo getCurrentInputEditorInfo() {
        return mInputEditorInfo;
    }
    
    /**
     * Re-evaluate whether the input method should be running in fullscreen
     * mode, and update its UI if this has changed since the last time it
     * was evaluated.  This will call {@link #onEvaluateFullscreenMode()} to
     * determine whether it should currently run in fullscreen mode.  You
     * can use {@link #isFullscreenMode()} to determine if the input method
     * is currently running in fullscreen mode.
     */
    public void updateFullscreenMode() {
        boolean isFullscreen = mShowInputRequested && onEvaluateFullscreenMode();
        boolean changed = mLastShowInputRequested != mShowInputRequested;
        if (mIsFullscreen != isFullscreen || !mFullscreenApplied) {
            changed = true;
            mIsFullscreen = isFullscreen;
            mFullscreenApplied = true;
            Drawable bg = onCreateBackgroundDrawable();
            if (bg == null) {
                // We need to give the window a real drawable, so that it
                // correctly sets its mode.
                bg = getResources().getDrawable(android.R.color.transparent);
            }
            mWindow.getWindow().setBackgroundDrawable(bg);
            mExtractFrame.setVisibility(isFullscreen ? View.VISIBLE : View.GONE);
            if (isFullscreen) {
                if (mExtractView == null) {
                    View v = onCreateExtractTextView();
                    if (v != null) {
                        setExtractView(v);
                    }
                }
                startExtractingText();
            }
        }
        
        if (changed) {
            onConfigureWindow(mWindow.getWindow(), isFullscreen,
                    !mShowInputRequested);
            mLastShowInputRequested = mShowInputRequested;
        }
    }
    
    /**
     * Update the given window's parameters for the given mode.  This is called
     * when the window is first displayed and each time the fullscreen or
     * candidates only mode changes.
     * 
     * <p>The default implementation makes the layout for the window
     * FILL_PARENT x FILL_PARENT when in fullscreen mode, and
     * FILL_PARENT x WRAP_CONTENT when in non-fullscreen mode.
     * 
     * @param win The input method's window.
     * @param isFullscreen If true, the window is running in fullscreen mode
     * and intended to cover the entire application display.
     * @param isCandidatesOnly If true, the window is only showing the
     * candidates view and none of the rest of its UI.  This is mutually
     * exclusive with fullscreen mode.
     */
    public void onConfigureWindow(Window win, boolean isFullscreen,
            boolean isCandidatesOnly) {
        if (isFullscreen) {
            mWindow.getWindow().setLayout(FILL_PARENT, FILL_PARENT);
        } else {
            mWindow.getWindow().setLayout(FILL_PARENT, WRAP_CONTENT);
        }
    }
    
    /**
     * Return whether the input method is <em>currently</em> running in
     * fullscreen mode.  This is the mode that was last determined and
     * applied by {@link #updateFullscreenMode()}.
     */
    public boolean isFullscreenMode() {
        return mIsFullscreen;
    }
    
    /**
     * Override this to control when the input method should run in
     * fullscreen mode.  The default implementation runs in fullsceen only
     * when the screen is in landscape mode.  If you change what
     * this returns, you will need to call {@link #updateFullscreenMode()}
     * yourself whenever the returned value may have changed to have it
     * re-evaluated and applied.
     */
    public boolean onEvaluateFullscreenMode() {
        Configuration config = getResources().getConfiguration();
        return config.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }
    
    /**
     * Compute the interesting insets into your UI.  The default implementation
     * uses the top of the candidates frame for the visible insets, and the
     * top of the input frame for the content insets.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_VISIBLE}.
     * 
     * <p>Note that this method is not called when in fullscreen mode, since
     * in that case the application is left as-is behind the input method and
     * not impacted by anything in its UI.
     * 
     * @param outInsets Fill in with the current UI insets.
     */
    public void onComputeInsets(Insets outInsets) {
        int[] loc = mTmpLocation;
        if (mInputFrame.getVisibility() == View.VISIBLE) {
            mInputFrame.getLocationInWindow(loc);
        } else {
            loc[1] = 0;
        }
        outInsets.contentTopInsets = loc[1];
        if (mCandidatesFrame.getVisibility() == View.VISIBLE) {
            mCandidatesFrame.getLocationInWindow(loc);
        }
        outInsets.visibleTopInsets = loc[1];
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE;
    }
    
    /**
     * Re-evaluate whether the soft input area should currently be shown, and
     * update its UI if this has changed since the last time it
     * was evaluated.  This will call {@link #onEvaluateInputViewShown()} to
     * determine whether the input view should currently be shown.  You
     * can use {@link #isInputViewShown()} to determine if the input view
     * is currently shown.
     */
    public void updateInputViewShown() {
        boolean isShown = mShowInputRequested && onEvaluateInputViewShown();
        if (mIsInputViewShown != isShown && mWindowVisible) {
            mIsInputViewShown = isShown;
            mInputFrame.setVisibility(isShown ? View.VISIBLE : View.GONE);
            if (mInputView == null) {
                View v = onCreateInputView();
                if (v != null) {
                    setInputView(v);
                }
            }
        }
    }
    
    /**
     * Return whether the soft input view is <em>currently</em> shown to the
     * user.  This is the state that was last determined and
     * applied by {@link #updateInputViewShown()}.
     */
    public boolean isInputViewShown() {
        return mIsInputViewShown;
    }
    
    /**
     * Override this to control when the soft input area should be shown to
     * the user.  The default implementation only shows the input view when
     * there is no hard keyboard or the keyboard is hidden.  If you change what
     * this returns, you will need to call {@link #updateInputViewShown()}
     * yourself whenever the returned value may have changed to have it
     * re-evalauted and applied.
     */
    public boolean onEvaluateInputViewShown() {
        Configuration config = getResources().getConfiguration();
        return config.keyboard == Configuration.KEYBOARD_NOKEYS
                || config.hardKeyboardHidden == Configuration.KEYBOARDHIDDEN_YES;
    }
    
    /**
     * Controls the visibility of the candidates display area.  By default
     * it is hidden.
     */
    public void setCandidatesViewShown(boolean shown) {
        if (mShowCandidatesRequested != shown) {
            mCandidatesFrame.setVisibility(shown ? View.VISIBLE : View.INVISIBLE);
            mShowCandidatesRequested = shown;
        }
        if (!mShowInputRequested && mWindowVisible != shown) {
            // If we are being asked to show the candidates view while the app
            // has not asked for the input view to be shown, then we need
            // to update whether the window is shown.
            if (shown) {
                showWindow(false);
            } else {
                hideWindow();
            }
        }
    }
    
    public void setStatusIcon(int iconResId) {
        mStatusIcon = iconResId;
        if (mInputConnection != null && mWindowVisible) {
            mInputConnection.showStatusIcon(getPackageName(), iconResId);
        }
    }
    
    /**
     * Force switch to a new input method, as identified by <var>id</var>.  This
     * input method will be destroyed, and the requested one started on the
     * current input field.
     * 
     * @param id Unique identifier of the new input method ot start.
     */
    public void switchInputMethod(String id) {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .setInputMethod(mToken, id);
    }
    
    public void setExtractView(View view) {
        mExtractFrame.removeAllViews();
        mExtractFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mExtractView = view;
        if (view != null) {
            mExtractEditText = (ExtractEditText)view.findViewById(
                    com.android.internal.R.id.inputExtractEditText);
            startExtractingText();
        } else {
            mExtractEditText = null;
        }
    }
    
    /**
     * Replaces the current candidates view with a new one.  You only need to
     * call this when dynamically changing the view; normally, you should
     * implement {@link #onCreateCandidatesView()} and create your view when
     * first needed by the input method.
     */
    public void setCandidatesView(View view) {
        mCandidatesFrame.removeAllViews();
        mCandidatesFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }
    
    /**
     * Replaces the current input view with a new one.  You only need to
     * call this when dynamically changing the view; normally, you should
     * implement {@link #onCreateInputView()} and create your view when
     * first needed by the input method.
     */
    public void setInputView(View view) {
        mInputFrame.removeAllViews();
        mInputFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.FILL_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mInputView = view;
    }
    
    /**
     * Called by the framework to create a Drawable for the background of
     * the input method window.  May return null for no background.  The default
     * implementation returns a non-null standard background only when in
     * fullscreen mode.  This is called each time the fullscreen mode changes.
     */
    public Drawable onCreateBackgroundDrawable() {
        if (isFullscreenMode()) {
            return getResources().getDrawable(
                    com.android.internal.R.drawable.input_method_fullscreen_background);
        }
        return null;
    }
    
    /**
     * Called by the framework to create the layout for showing extacted text.
     * Only called when in fullscreen mode.  The returned view hierarchy must
     * have an {@link ExtractEditText} whose ID is 
     * {@link android.R.id#inputExtractEditText}.
     */
    public View onCreateExtractTextView() {
        return mInflater.inflate(
                com.android.internal.R.layout.input_method_extract_view, null);
    }
    
    /**
     * Create and return the view hierarchy used to show candidates.  This will
     * be called once, when the candidates are first displayed.  You can return
     * null to have no candidates view; the default implementation returns null.
     * 
     * <p>To control when the candidates view is displayed, use
     * {@link #setCandidatesViewShown(boolean)}.
     * To change the candidates view after the first one is created by this
     * function, use {@link #setCandidatesView(View)}.
     */
    public View onCreateCandidatesView() {
        return null;
    }
    
    /**
     * Create and return the view hierarchy used for the input area (such as
     * a soft keyboard).  This will be called once, when the input area is
     * first displayed.  You can return null to have no input area; the default
     * implementation returns null.
     * 
     * <p>To control when the input view is displayed, implement
     * {@link #onEvaluateInputViewShown()}.
     * To change the input view after the first one is created by this
     * function, use {@link #setInputView(View)}.
     */
    public View onCreateInputView() {
        return null;
    }
    
    /**
     * Called when the input view is being shown and input has started on
     * a new editor.  This will always be called after {@link #onStartInput},
     * allowing you to do your general setup there and just view-specific
     * setup here.  You are guaranteed that {@link #onCreateInputView()} will
     * have been called some time before this function is called.
     * 
     * @param info Description of the type of text being edited.
     * @param restarting Set to true if we are restarting input on the
     * same text field as before.
     */
    public void onStartInputView(EditorInfo info, boolean restarting) {
    }
    
    /**
     * The system has decided that it may be time to show your input method.
     * This is called due to a corresponding call to your
     * {@link InputMethod#showSoftInput(int) InputMethod.showSoftInput(int)}
     * method.  The default implementation simply calls
     * {@link #showWindow(boolean)}, except if the
     * {@link InputMethod#SHOW_EXPLICIT InputMethod.SHOW_EXPLICIT} flag is
     * not set and the input method is running in fullscreen mode.
     * 
     * @param flags Provides additional information about the show request,
     * as per {@link InputMethod#showSoftInput(int) InputMethod.showSoftInput(int)}.
     */
    public void onShowRequested(int flags) {
        if (!onEvaluateInputViewShown()) {
            return;
        }
        if ((flags&InputMethod.SHOW_EXPLICIT) == 0 && onEvaluateFullscreenMode()) {
            // Don't show if this is not explicit requested by the user and
            // the input method is fullscreen.  That would be too disruptive.
            return;
        }
        showWindow(true);
    }
    
    public void showWindow(boolean showInput) {
        if (DEBUG) Log.v(TAG, "Showing window: showInput=" + showInput
                + " mShowInputRequested=" + mShowInputRequested
                + " mWindowAdded=" + mWindowAdded
                + " mWindowCreated=" + mWindowCreated
                + " mWindowVisible=" + mWindowVisible
                + " mInputStarted=" + mInputStarted);
        boolean doShowInput = false;
        boolean wasVisible = mWindowVisible;
        mWindowVisible = true;
        if (!mShowInputRequested) {
            if (mInputStarted) {
                if (showInput) {
                    doShowInput = true;
                    mShowInputRequested = true;
                }
            }
        } else {
            showInput = true;
        }
        
        if (DEBUG) Log.v(TAG, "showWindow: updating UI");
        updateFullscreenMode();
        updateInputViewShown();
        
        if (!mWindowAdded || !mWindowCreated) {
            mWindowAdded = true;
            mWindowCreated = true;
            View v = onCreateCandidatesView();
            if (DEBUG) Log.v(TAG, "showWindow: candidates=" + v);
            if (v != null) {
                setCandidatesView(v);
            }
        }
        if (doShowInput) {
            if (mInputStarted) {
                if (DEBUG) Log.v(TAG, "showWindow: starting input view");
                onStartInputView(mInputEditorInfo, false);
            }
            startExtractingText();
        }
        
        if (!wasVisible) {
            if (DEBUG) Log.v(TAG, "showWindow: showing!");
            mWindow.show();
            if (mInputConnection != null) {
                mInputConnection.showStatusIcon(getPackageName(), mStatusIcon);
            }
        }
    }
    
    public void hideWindow() {
        if (mWindowVisible) {
            mWindow.hide();
            mWindowVisible = false;
            if (mInputConnection != null) {
                mInputConnection.hideStatusIcon();
            }
        }
    }
    
    /**
     * Called when a new client has bound to the input method.  This
     * may be followed by a series of {@link #onStartInput(EditorInfo, boolean)}
     * and {@link #onFinishInput()} calls as the user navigates through its
     * UI.  Upon this call you know that {@link #getCurrentInputBinding}
     * and {@link #getCurrentInputConnection} return valid objects.
     */
    public void onBindInput() {
    }
    
    /**
     * Called when the previous bound client is no longer associated
     * with the input method.  After returning {@link #getCurrentInputBinding}
     * and {@link #getCurrentInputConnection} will no longer return
     * valid objects.
     */
    public void onUnbindInput() {
    }
    
    /**
     * Called to inform the input method that text input has started in an
     * editor.  You should use this callback to initialize the state of your
     * input to match the state of the editor given to it.
     * 
     * @param attribute The attributes of the editor that input is starting
     * in.
     * @param restarting Set to true if input is restarting in the same
     * editor such as because the application has changed the text in
     * the editor.  Otherwise will be false, indicating this is a new
     * session with the editor.
     */
    public void onStartInput(EditorInfo attribute, boolean restarting) {
    }
    
    void doStartInput(EditorInfo attribute, boolean restarting) {
        if (mInputStarted && !restarting) {
            onFinishInput();
        }
        mInputStarted = true;
        mInputEditorInfo = attribute;
        onStartInput(attribute, restarting);
        if (mWindowVisible) {
            if (mShowInputRequested) {
                onStartInputView(mInputEditorInfo, restarting);
            }
            startExtractingText();
        }
    }
    
    /**
     * Called to inform the input method that text input has finished in
     * the last editor.  At this point there may be a call to
     * {@link #onStartInput(EditorInfo, boolean)} to perform input in a
     * new editor, or the input method may be left idle.  This method is
     * <em>not</em> called when input restarts in the same editor.
     */
    public void onFinishInput() {
    }
    
    /**
     * Called when the application has reported auto-completion candidates that
     * it would like to have the input method displayed.  Typically these are
     * only used when an input method is running in full-screen mode, since
     * otherwise the user can see and interact with the pop-up window of
     * completions shown by the application.
     * 
     * <p>The default implementation here does nothing.
     */
    public void onDisplayCompletions(CompletionInfo[] completions) {
    }
    
    /**
     * Called when the application has reported new extracted text to be shown
     * due to changes in its current text state.  The default implementation
     * here places the new text in the extract edit text, when the input
     * method is running in fullscreen mode.
     */
    public void onUpdateExtractedText(int token, ExtractedText text) {
        if (mExtractedToken != token) {
            return;
        }
        if (mExtractEditText != null && text != null) {
            mExtractedText = text;
            mExtractEditText.setExtractedText(text);
        }
    }
    
    /**
     * Called when the application has reported a new selection region of
     * the text.  This is called whether or not the input method has requested
     * extracted text updates, although if so it will not receive this call
     * if the extracted text has changed as well.
     * 
     * <p>The default implementation takes care of updating the cursor in
     * the extract text, if it is being shown.
     */
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
            int newSelStart, int newSelEnd,
            int candidatesStart, int candidatesEnd) {
        if (mExtractEditText != null && mExtractedText != null) {
            final int off = mExtractedText.startOffset;
            mExtractEditText.setSelection(newSelStart-off, newSelEnd-off);
        }
    }

    /**
     * Called when the application has reported a new location of its text
     * cursor.  This is only called if explicitly requested by the input method.
     * The default implementation does nothing.
     */
    public void onUpdateCursor(Rect newCursor) {
    }

    /**
     * Close this input method's soft input area, removing it from the display.
     * The input method will continue running, but the user can no longer use
     * it to generate input by touching the screen.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link InputMethodManager#HIDE_IMPLICIT_ONLY
     * InputMethodManager.HIDE_IMPLICIT_ONLY} bit set.
     */
    public void dismissSoftInput(int flags) {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                .hideSoftInputFromInputMethod(mToken, flags);
    }
    
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK
                && event.getRepeatCount() == 0) {
            if (mShowInputRequested) {
                // If the soft input area is shown, back closes it and we
                // consume the back key.
                dismissSoftInput(0);
                return true;
            }
            if (mShowCandidatesRequested) {
                // If the candidates are shown, we just want to make sure
                // they are now hidden but otherwise let the app execute
                // the back.
                // XXX this needs better interaction with the soft input
                // implementation.
                //setCandidatesViewShown(false);
            }
        }
        return false;
    }

    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        return false;
    }

    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return false;
    }

    public boolean onTrackballEvent(MotionEvent event) {
        return false;
    }

    public void onAppPrivateCommand(String action, Bundle data) {
    }
    
    void startExtractingText() {
        if (mExtractEditText != null && getCurrentInputStarted()
                && isFullscreenMode()) {
            mExtractedToken++;
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.token = mExtractedToken;
            req.hintMaxLines = 10;
            req.hintMaxChars = 10000;
            mExtractedText = mInputConnection.getExtractedText(req,
                    InputConnection.EXTRACTED_TEXT_MONITOR);
            if (mExtractedText != null) {
                mExtractEditText.setExtractedText(mExtractedText);
            }
            mExtractEditText.setInputType(getCurrentInputEditorInfo().inputType);
            mExtractEditText.setHint(mInputEditorInfo.hintText);
        }
    }
}
