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

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.method.MovementMethod;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Printer;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputBinding;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethod;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputMethodSubtype;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * InputMethodService provides a standard implementation of an InputMethod,
 * which final implementations can derive from and customize.  See the
 * base class {@link AbstractInputMethodService} and the {@link InputMethod}
 * interface for more information on the basics of writing input methods.
 * 
 * <p>In addition to the normal Service lifecycle methods, this class
 * introduces some new specific callbacks that most subclasses will want
 * to make use of:</p>
 * <ul>
 * <li> {@link #onInitializeInterface()} for user-interface initialization,
 * in particular to deal with configuration changes while the service is
 * running.
 * <li> {@link #onBindInput} to find out about switching to a new client.
 * <li> {@link #onStartInput} to deal with an input session starting with
 * the client.
 * <li> {@link #onCreateInputView()}, {@link #onCreateCandidatesView()},
 * and {@link #onCreateExtractTextView()} for non-demand generation of the UI.
 * <li> {@link #onStartInputView(EditorInfo, boolean)} to deal with input
 * starting within the input area of the IME.
 * </ul>
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
 * 
 * @attr ref android.R.styleable#InputMethodService_imeFullscreenBackground
 * @attr ref android.R.styleable#InputMethodService_imeExtractEnterAnimation
 * @attr ref android.R.styleable#InputMethodService_imeExtractExitAnimation
 */
public class InputMethodService extends AbstractInputMethodService {
    static final String TAG = "InputMethodService";
    static final boolean DEBUG = false;

    /**
     * The back button will close the input window.
     */
    public static final int BACK_DISPOSITION_DEFAULT = 0;  // based on window

    /**
     * This input method will not consume the back key.
     */
    public static final int BACK_DISPOSITION_WILL_NOT_DISMISS = 1; // back

    /**
     * This input method will consume the back key.
     */
    public static final int BACK_DISPOSITION_WILL_DISMISS = 2; // down

    /**
     * @hide
     * The IME is active.  It may or may not be visible.
     */
    public static final int IME_ACTIVE = 0x1;

    /**
     * @hide
     * The IME is visible.
     */
    public static final int IME_VISIBLE = 0x2;

    InputMethodManager mImm;
    
    int mTheme = 0;
    
    LayoutInflater mInflater;
    TypedArray mThemeAttrs;
    View mRootView;
    SoftInputWindow mWindow;
    boolean mInitialized;
    boolean mWindowCreated;
    boolean mWindowAdded;
    boolean mWindowVisible;
    boolean mWindowWasVisible;
    boolean mInShowWindow;
    ViewGroup mFullscreenArea;
    FrameLayout mExtractFrame;
    FrameLayout mCandidatesFrame;
    FrameLayout mInputFrame;
    
    IBinder mToken;
    
    InputBinding mInputBinding;
    InputConnection mInputConnection;
    boolean mInputStarted;
    boolean mInputViewStarted;
    boolean mCandidatesViewStarted;
    InputConnection mStartedInputConnection;
    EditorInfo mInputEditorInfo;
    
    int mShowInputFlags;
    boolean mShowInputRequested;
    boolean mLastShowInputRequested;
    int mCandidatesVisibility;
    CompletionInfo[] mCurCompletions;
    
    boolean mShowInputForced;
    
    boolean mFullscreenApplied;
    boolean mIsFullscreen;
    View mExtractView;
    boolean mExtractViewHidden;
    ExtractEditText mExtractEditText;
    ViewGroup mExtractAccessories;
    Button mExtractAction;
    ExtractedText mExtractedText;
    int mExtractedToken;
    
    View mInputView;
    boolean mIsInputViewShown;
    
    int mStatusIcon;
    int mBackDisposition;

    final Insets mTmpInsets = new Insets();
    final int[] mTmpLocation = new int[2];

    final ViewTreeObserver.OnComputeInternalInsetsListener mInsetsComputer =
            new ViewTreeObserver.OnComputeInternalInsetsListener() {
        public void onComputeInternalInsets(ViewTreeObserver.InternalInsetsInfo info) {
            if (isExtractViewShown()) {
                // In true fullscreen mode, we just say the window isn't covering
                // any content so we don't impact whatever is behind.
                View decor = getWindow().getWindow().getDecorView();
                info.contentInsets.top = info.visibleInsets.top
                        = decor.getHeight();
                info.touchableRegion.setEmpty();
                info.setTouchableInsets(ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_FRAME);
            } else {
                onComputeInsets(mTmpInsets);
                info.contentInsets.top = mTmpInsets.contentTopInsets;
                info.visibleInsets.top = mTmpInsets.visibleTopInsets;
                info.touchableRegion.set(mTmpInsets.touchableRegion);
                info.setTouchableInsets(mTmpInsets.touchableInsets);
            }
        }
    };

    final View.OnClickListener mActionClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            final EditorInfo ei = getCurrentInputEditorInfo();
            final InputConnection ic = getCurrentInputConnection();
            if (ei != null && ic != null) {
                if (ei.actionId != 0) {
                    ic.performEditorAction(ei.actionId);
                } else if ((ei.imeOptions&EditorInfo.IME_MASK_ACTION)
                        != EditorInfo.IME_ACTION_NONE) {
                    ic.performEditorAction(ei.imeOptions&EditorInfo.IME_MASK_ACTION);
                }
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
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.reportFullscreenMode(mIsFullscreen);
            initialize();
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

        public void startInput(InputConnection ic, EditorInfo attribute) {
            if (DEBUG) Log.v(TAG, "startInput(): editor=" + attribute);
            doStartInput(ic, attribute, false);
        }

        public void restartInput(InputConnection ic, EditorInfo attribute) {
            if (DEBUG) Log.v(TAG, "restartInput(): editor=" + attribute);
            doStartInput(ic, attribute, true);
        }

        /**
         * Handle a request by the system to hide the soft input area.
         */
        public void hideSoftInput(int flags, ResultReceiver resultReceiver) {
            if (DEBUG) Log.v(TAG, "hideSoftInput()");
            boolean wasVis = isInputViewShown();
            mShowInputFlags = 0;
            mShowInputRequested = false;
            mShowInputForced = false;
            hideWindow();
            if (resultReceiver != null) {
                resultReceiver.send(wasVis != isInputViewShown()
                        ? InputMethodManager.RESULT_HIDDEN
                        : (wasVis ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                                : InputMethodManager.RESULT_UNCHANGED_HIDDEN), null);
            }
        }

        /**
         * Handle a request by the system to show the soft input area.
         */
        public void showSoftInput(int flags, ResultReceiver resultReceiver) {
            if (DEBUG) Log.v(TAG, "showSoftInput()");
            boolean wasVis = isInputViewShown();
            mShowInputFlags = 0;
            if (onShowInputRequested(flags, false)) {
                showWindow(true);
            }
            // If user uses hard keyboard, IME button should always be shown.
            boolean showing = onEvaluateInputViewShown();
            mImm.setImeWindowStatus(mToken, IME_ACTIVE | (showing ? IME_VISIBLE : 0),
                    mBackDisposition);
            if (resultReceiver != null) {
                resultReceiver.send(wasVis != isInputViewShown()
                        ? InputMethodManager.RESULT_SHOWN
                        : (wasVis ? InputMethodManager.RESULT_UNCHANGED_SHOWN
                                : InputMethodManager.RESULT_UNCHANGED_HIDDEN), null);
            }
        }

        public void changeInputMethodSubtype(InputMethodSubtype subtype) {
            onCurrentInputMethodSubtypeChanged(subtype);
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
            doFinishInput();
        }

        /**
         * Call {@link InputMethodService#onDisplayCompletions
         * InputMethodService.onDisplayCompletions()}.
         */
        public void displayCompletions(CompletionInfo[] completions) {
            if (!isEnabled()) {
                return;
            }
            mCurCompletions = completions;
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

        @Override
        public void viewClicked(boolean focusChanged) {
            if (!isEnabled()) {
                return;
            }
            InputMethodService.this.onViewClicked(focusChanged);
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
        
        /**
         * 
         */
        public void toggleSoftInput(int showFlags, int hideFlags) {
            InputMethodService.this.onToggleSoftInput(showFlags, hideFlags);
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
        public int contentTopInsets;
        
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
        public int visibleTopInsets;

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
         * Option for {@link #touchableInsets}: the area inside of
         * the visible insets can be touched.
         */
        public static final int TOUCHABLE_INSETS_VISIBLE
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_VISIBLE;

        /**
         * Option for {@link #touchableInsets}: the region specified by
         * {@link #touchableRegion} can be touched.
         */
        public static final int TOUCHABLE_INSETS_REGION
                = ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION;

        /**
         * Determine which area of the window is touchable by the user.  May
         * be one of: {@link #TOUCHABLE_INSETS_FRAME},
         * {@link #TOUCHABLE_INSETS_CONTENT}, {@link #TOUCHABLE_INSETS_VISIBLE},
         * or {@link #TOUCHABLE_INSETS_REGION}.
         */
        public int touchableInsets;
    }

    /**
     * You can call this to customize the theme used by your IME's window.
     * This theme should typically be one that derives from
     * {@link android.R.style#Theme_InputMethod}, which is the default theme
     * you will get.  This must be set before {@link #onCreate}, so you
     * will typically call it in your constructor with the resource ID
     * of your custom theme.
     */
    @Override
    public void setTheme(int theme) {
        if (mWindow != null) {
            throw new IllegalStateException("Must be called before onCreate()");
        }
        mTheme = theme;
    }

    @Override public void onCreate() {
        mTheme = Resources.selectSystemTheme(mTheme,
                getApplicationInfo().targetSdkVersion,
                android.R.style.Theme_InputMethod,
                android.R.style.Theme_Holo_InputMethod,
                android.R.style.Theme_DeviceDefault_InputMethod);
        super.setTheme(mTheme);
        super.onCreate();
        mImm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mInflater = (LayoutInflater)getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        mWindow = new SoftInputWindow(this, mTheme, mDispatcherState);
        initViews();
        mWindow.getWindow().setLayout(MATCH_PARENT, WRAP_CONTENT);
    }

    /**
     * This is a hook that subclasses can use to perform initialization of
     * their interface.  It is called for you prior to any of your UI objects
     * being created, both after the service is first created and after a
     * configuration change happens.
     */
    public void onInitializeInterface() {
    }

    void initialize() {
        if (!mInitialized) {
            mInitialized = true;
            onInitializeInterface();
        }
    }

    void initViews() {
        mInitialized = false;
        mWindowCreated = false;
        mShowInputRequested = false;
        mShowInputForced = false;
        
        mThemeAttrs = obtainStyledAttributes(android.R.styleable.InputMethodService);
        mRootView = mInflater.inflate(
                com.android.internal.R.layout.input_method, null);
        mWindow.setContentView(mRootView);
        mRootView.getViewTreeObserver().addOnComputeInternalInsetsListener(mInsetsComputer);
        if (Settings.System.getInt(getContentResolver(),
                Settings.System.FANCY_IME_ANIMATIONS, 0) != 0) {
            mWindow.getWindow().setWindowAnimations(
                    com.android.internal.R.style.Animation_InputMethodFancy);
        }
        mFullscreenArea = (ViewGroup)mRootView.findViewById(com.android.internal.R.id.fullscreenArea);
        mExtractViewHidden = false;
        mExtractFrame = (FrameLayout)mRootView.findViewById(android.R.id.extractArea);
        mExtractView = null;
        mExtractEditText = null;
        mExtractAccessories = null;
        mExtractAction = null;
        mFullscreenApplied = false;
        
        mCandidatesFrame = (FrameLayout)mRootView.findViewById(android.R.id.candidatesArea);
        mInputFrame = (FrameLayout)mRootView.findViewById(android.R.id.inputArea);
        mInputView = null;
        mIsInputViewShown = false;
        
        mExtractFrame.setVisibility(View.GONE);
        mCandidatesVisibility = getCandidatesHiddenVisibility();
        mCandidatesFrame.setVisibility(mCandidatesVisibility);
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
     * 
     * <p>When a configuration change does happen,
     * {@link #onInitializeInterface()} is guaranteed to be called the next
     * time prior to any of the other input or UI creation callbacks.  The
     * following will be called immediately depending if appropriate for current 
     * state: {@link #onStartInput} if input is active, and
     * {@link #onCreateInputView} and {@link #onStartInputView} and related
     * appropriate functions if the UI is displayed.
     */
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        boolean visible = mWindowVisible;
        int showFlags = mShowInputFlags;
        boolean showingInput = mShowInputRequested;
        CompletionInfo[] completions = mCurCompletions;
        initViews();
        mInputViewStarted = false;
        mCandidatesViewStarted = false;
        if (mInputStarted) {
            doStartInput(getCurrentInputConnection(),
                    getCurrentInputEditorInfo(), true);
        }
        if (visible) {
            if (showingInput) {
                // If we were last showing the soft keyboard, try to do so again.
                if (onShowInputRequested(showFlags, true)) {
                    showWindow(true);
                    if (completions != null) {
                        mCurCompletions = completions;
                        onDisplayCompletions(completions);
                    }
                } else {
                    hideWindow();
                }
            } else if (mCandidatesVisibility == View.VISIBLE) {
                // If the candidates are currently visible, make sure the
                // window is shown for them.
                showWindow(false);
            } else {
                // Otherwise hide the window.
                hideWindow();
            }
            // If user uses hard keyboard, IME button should always be shown.
            boolean showing = onEvaluateInputViewShown();
            mImm.setImeWindowStatus(mToken, IME_ACTIVE | (showing ? IME_VISIBLE : 0),
                    mBackDisposition);
        }
    }

    /**
     * Implement to return our standard {@link InputMethodImpl}.  Subclasses
     * can override to provide their own customized version.
     */
    @Override
    public AbstractInputMethodImpl onCreateInputMethodInterface() {
        return new InputMethodImpl();
    }
    
    /**
     * Implement to return our standard {@link InputMethodSessionImpl}.  Subclasses
     * can override to provide their own customized version.
     */
    @Override
    public AbstractInputMethodSessionImpl onCreateInputMethodSessionInterface() {
        return new InputMethodSessionImpl();
    }
    
    public LayoutInflater getLayoutInflater() {
        return mInflater;
    }
    
    public Dialog getWindow() {
        return mWindow;
    }
    
    public void setBackDisposition(int disposition) {
        mBackDisposition = disposition;
    }

    public int getBackDisposition() {
        return mBackDisposition;
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
        InputConnection ic = mStartedInputConnection;
        if (ic != null) {
            return ic;
        }
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
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.reportFullscreenMode(isFullscreen);
            mFullscreenApplied = true;
            initialize();
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                    mFullscreenArea.getLayoutParams();
            if (isFullscreen) {
                mFullscreenArea.setBackgroundDrawable(mThemeAttrs.getDrawable(
                        com.android.internal.R.styleable.InputMethodService_imeFullscreenBackground));
                lp.height = 0;
                lp.weight = 1;
            } else {
                mFullscreenArea.setBackgroundDrawable(null);
                lp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
                lp.weight = 0;
            }
            ((ViewGroup)mFullscreenArea.getParent()).updateViewLayout(
                    mFullscreenArea, lp);
            if (isFullscreen) {
                if (mExtractView == null) {
                    View v = onCreateExtractTextView();
                    if (v != null) {
                        setExtractView(v);
                    }
                }
                startExtractingText(false);
            }
            updateExtractFrameVisibility();
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
     * MATCH_PARENT x MATCH_PARENT when in fullscreen mode, and
     * MATCH_PARENT x WRAP_CONTENT when in non-fullscreen mode.
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
            mWindow.getWindow().setLayout(MATCH_PARENT, MATCH_PARENT);
        } else {
            mWindow.getWindow().setLayout(MATCH_PARENT, WRAP_CONTENT);
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
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            return false;
        }
        if (mInputEditorInfo != null
                && (mInputEditorInfo.imeOptions & EditorInfo.IME_FLAG_NO_FULLSCREEN) != 0) {
            return false;
        }
        return true;
    }
    
    /**
     * Controls the visibility of the extracted text area.  This only applies
     * when the input method is in fullscreen mode, and thus showing extracted
     * text.  When false, the extracted text will not be shown, allowing some
     * of the application to be seen behind.  This is normally set for you
     * by {@link #onUpdateExtractingVisibility}.  This controls the visibility
     * of both the extracted text and candidate view; the latter since it is
     * not useful if there is no text to see.
     */
    public void setExtractViewShown(boolean shown) {
        if (mExtractViewHidden == shown) {
            mExtractViewHidden = !shown;
            updateExtractFrameVisibility();
        }
    }
    
    /**
     * Return whether the fullscreen extract view is shown.  This will only
     * return true if {@link #isFullscreenMode()} returns true, and in that
     * case its value depends on the last call to
     * {@link #setExtractViewShown(boolean)}.  This effectively lets you
     * determine if the application window is entirely covered (when this
     * returns true) or if some part of it may be shown (if this returns
     * false, though if {@link #isFullscreenMode()} returns true in that case
     * then it is probably only a sliver of the application).
     */
    public boolean isExtractViewShown() {
        return mIsFullscreen && !mExtractViewHidden;
    }
    
    void updateExtractFrameVisibility() {
        int vis;
        if (isFullscreenMode()) {
            vis = mExtractViewHidden ? View.INVISIBLE : View.VISIBLE;
            mExtractFrame.setVisibility(View.VISIBLE);
        } else {
            vis = View.VISIBLE;
            mExtractFrame.setVisibility(View.GONE);
        }
        updateCandidatesVisibility(mCandidatesVisibility == View.VISIBLE);
        if (mWindowWasVisible && mFullscreenArea.getVisibility() != vis) {
            int animRes = mThemeAttrs.getResourceId(vis == View.VISIBLE
                    ? com.android.internal.R.styleable.InputMethodService_imeExtractEnterAnimation
                    : com.android.internal.R.styleable.InputMethodService_imeExtractExitAnimation,
                    0);
            if (animRes != 0) {
                mFullscreenArea.startAnimation(AnimationUtils.loadAnimation(
                        this, animRes));
            }
        }
        mFullscreenArea.setVisibility(vis);
    }
    
    /**
     * Compute the interesting insets into your UI.  The default implementation
     * uses the top of the candidates frame for the visible insets, and the
     * top of the input frame for the content insets.  The default touchable
     * insets are {@link Insets#TOUCHABLE_INSETS_VISIBLE}.
     * 
     * <p>Note that this method is not called when
     * {@link #isExtractViewShown} returns true, since
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
            View decor = getWindow().getWindow().getDecorView();
            loc[1] = decor.getHeight();
        }
        if (isFullscreenMode()) {
            // In fullscreen mode, we never resize the underlying window.
            View decor = getWindow().getWindow().getDecorView();
            outInsets.contentTopInsets = decor.getHeight();
        } else {
            outInsets.contentTopInsets = loc[1];
        }
        if (mCandidatesFrame.getVisibility() == View.VISIBLE) {
            mCandidatesFrame.getLocationInWindow(loc);
        }
        outInsets.visibleTopInsets = loc[1];
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE;
        outInsets.touchableRegion.setEmpty();
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
                initialize();
                View v = onCreateInputView();
                if (v != null) {
                    setInputView(v);
                }
            }
        }
    }
    
    /**
     * Returns true if we have been asked to show our input view.
     */
    public boolean isShowInputRequested() {
        return mShowInputRequested;
    }
    
    /**
     * Return whether the soft input view is <em>currently</em> shown to the
     * user.  This is the state that was last determined and
     * applied by {@link #updateInputViewShown()}.
     */
    public boolean isInputViewShown() {
        return mIsInputViewShown && mWindowVisible;
    }
    
    /**
     * Override this to control when the soft input area should be shown to
     * the user.  The default implementation only shows the input view when
     * there is no hard keyboard or the keyboard is hidden.  If you change what
     * this returns, you will need to call {@link #updateInputViewShown()}
     * yourself whenever the returned value may have changed to have it
     * re-evaluated and applied.
     */
    public boolean onEvaluateInputViewShown() {
        Configuration config = getResources().getConfiguration();
        return config.keyboard == Configuration.KEYBOARD_NOKEYS
                || config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES;
    }
    
    /**
     * Controls the visibility of the candidates display area.  By default
     * it is hidden.
     */
    public void setCandidatesViewShown(boolean shown) {
        updateCandidatesVisibility(shown);
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
    
    void updateCandidatesVisibility(boolean shown) {
        int vis = shown ? View.VISIBLE : getCandidatesHiddenVisibility();
        if (mCandidatesVisibility != vis) {
            mCandidatesFrame.setVisibility(vis);
            mCandidatesVisibility = vis;
        }
    }
    
    /**
     * Returns the visibility mode (either {@link View#INVISIBLE View.INVISIBLE}
     * or {@link View#GONE View.GONE}) of the candidates view when it is not
     * shown.  The default implementation returns GONE when
     * {@link #isExtractViewShown} returns true,
     * otherwise VISIBLE.  Be careful if you change this to return GONE in
     * other situations -- if showing or hiding the candidates view causes
     * your window to resize, this can cause temporary drawing artifacts as
     * the resize takes place.
     */
    public int getCandidatesHiddenVisibility() {
        return isExtractViewShown() ? View.GONE : View.INVISIBLE;
    }
    
    public void showStatusIcon(int iconResId) {
        mStatusIcon = iconResId;
        mImm.showStatusIcon(mToken, getPackageName(), iconResId);
    }
    
    public void hideStatusIcon() {
        mStatusIcon = 0;
        mImm.hideStatusIcon(mToken);
    }
    
    /**
     * Force switch to a new input method, as identified by <var>id</var>.  This
     * input method will be destroyed, and the requested one started on the
     * current input field.
     * 
     * @param id Unique identifier of the new input method ot start.
     */
    public void switchInputMethod(String id) {
        mImm.setInputMethod(mToken, id);
    }
    
    public void setExtractView(View view) {
        mExtractFrame.removeAllViews();
        mExtractFrame.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        mExtractView = view;
        if (view != null) {
            mExtractEditText = (ExtractEditText)view.findViewById(
                    com.android.internal.R.id.inputExtractEditText);
            mExtractEditText.setIME(this);
            mExtractAction = (Button)view.findViewById(
                    com.android.internal.R.id.inputExtractAction);
            if (mExtractAction != null) {
                mExtractAccessories = (ViewGroup)view.findViewById(
                        com.android.internal.R.id.inputExtractAccessories);
            }
            startExtractingText(false);
        } else {
            mExtractEditText = null;
            mExtractAccessories = null;
            mExtractAction = null;
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
                ViewGroup.LayoutParams.MATCH_PARENT,
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        mInputView = view;
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
     * Called when the input view is being hidden from the user.  This will
     * be called either prior to hiding the window, or prior to switching to
     * another target for editing.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     * 
     * @param finishingInput If true, {@link #onFinishInput} will be
     * called immediately after.
     */
    public void onFinishInputView(boolean finishingInput) {
        if (!finishingInput) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }
    
    /**
     * Called when only the candidates view has been shown for showing
     * processing as the user enters text through a hard keyboard.
     * This will always be called after {@link #onStartInput},
     * allowing you to do your general setup there and just view-specific
     * setup here.  You are guaranteed that {@link #onCreateCandidatesView()}
     * will have been called some time before this function is called.
     * 
     * <p>Note that this will <em>not</em> be called when the input method
     * is running in full editing mode, and thus receiving
     * {@link #onStartInputView} to initiate that operation.  This is only
     * for the case when candidates are being shown while the input method
     * editor is hidden but wants to show its candidates UI as text is
     * entered through some other mechanism.
     * 
     * @param info Description of the type of text being edited.
     * @param restarting Set to true if we are restarting input on the
     * same text field as before.
     */
    public void onStartCandidatesView(EditorInfo info, boolean restarting) {
    }
    
    /**
     * Called when the candidates view is being hidden from the user.  This will
     * be called either prior to hiding the window, or prior to switching to
     * another target for editing.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     * 
     * @param finishingInput If true, {@link #onFinishInput} will be
     * called immediately after.
     */
    public void onFinishCandidatesView(boolean finishingInput) {
        if (!finishingInput) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.finishComposingText();
            }
        }
    }
    
    /**
     * The system has decided that it may be time to show your input method.
     * This is called due to a corresponding call to your
     * {@link InputMethod#showSoftInput InputMethod.showSoftInput()}
     * method.  The default implementation uses
     * {@link #onEvaluateInputViewShown()}, {@link #onEvaluateFullscreenMode()},
     * and the current configuration to decide whether the input view should
     * be shown at this point.
     * 
     * @param flags Provides additional information about the show request,
     * as per {@link InputMethod#showSoftInput InputMethod.showSoftInput()}.
     * @param configChange This is true if we are re-showing due to a
     * configuration change.
     * @return Returns true to indicate that the window should be shown.
     */
    public boolean onShowInputRequested(int flags, boolean configChange) {
        if (!onEvaluateInputViewShown()) {
            return false;
        }
        if ((flags&InputMethod.SHOW_EXPLICIT) == 0) {
            if (!configChange && onEvaluateFullscreenMode()) {
                // Don't show if this is not explicitly requested by the user and
                // the input method is fullscreen.  That would be too disruptive.
                // However, we skip this change for a config change, since if
                // the IME is already shown we do want to go into fullscreen
                // mode at this point.
                return false;
            }
            Configuration config = getResources().getConfiguration();
            if (config.keyboard != Configuration.KEYBOARD_NOKEYS) {
                // And if the device has a hard keyboard, even if it is
                // currently hidden, don't show the input method implicitly.
                // These kinds of devices don't need it that much.
                return false;
            }
        }
        if ((flags&InputMethod.SHOW_FORCED) != 0) {
            mShowInputForced = true;
        }
        return true;
    }
    
    public void showWindow(boolean showInput) {
        if (DEBUG) Log.v(TAG, "Showing window: showInput=" + showInput
                + " mShowInputRequested=" + mShowInputRequested
                + " mWindowAdded=" + mWindowAdded
                + " mWindowCreated=" + mWindowCreated
                + " mWindowVisible=" + mWindowVisible
                + " mInputStarted=" + mInputStarted);
        
        if (mInShowWindow) {
            Log.w(TAG, "Re-entrance in to showWindow");
            return;
        }
        
        try {
            mWindowWasVisible = mWindowVisible;
            mInShowWindow = true;
            showWindowInner(showInput);
        } finally {
            mWindowWasVisible = true;
            mInShowWindow = false;
        }
    }

    void showWindowInner(boolean showInput) {
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
        initialize();
        updateFullscreenMode();
        updateInputViewShown();
        
        if (!mWindowAdded || !mWindowCreated) {
            mWindowAdded = true;
            mWindowCreated = true;
            initialize();
            if (DEBUG) Log.v(TAG, "CALL: onCreateCandidatesView");
            View v = onCreateCandidatesView();
            if (DEBUG) Log.v(TAG, "showWindow: candidates=" + v);
            if (v != null) {
                setCandidatesView(v);
            }
        }
        if (mShowInputRequested) {
            if (!mInputViewStarted) {
                if (DEBUG) Log.v(TAG, "CALL: onStartInputView");
                mInputViewStarted = true;
                onStartInputView(mInputEditorInfo, false);
            }
        } else if (!mCandidatesViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onStartCandidatesView");
            mCandidatesViewStarted = true;
            onStartCandidatesView(mInputEditorInfo, false);
        }
        
        if (doShowInput) {
            startExtractingText(false);
        }

        if (!wasVisible) {
            if (DEBUG) Log.v(TAG, "showWindow: showing!");
            mImm.setImeWindowStatus(mToken, IME_ACTIVE, mBackDisposition);
            onWindowShown();
            mWindow.show();
        }
    }

    public void hideWindow() {
        if (mInputViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishInputView");
            onFinishInputView(false);
        } else if (mCandidatesViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishCandidatesView");
            onFinishCandidatesView(false);
        }
        mInputViewStarted = false;
        mCandidatesViewStarted = false;
        mImm.setImeWindowStatus(mToken, 0, mBackDisposition);
        if (mWindowVisible) {
            mWindow.hide();
            mWindowVisible = false;
            onWindowHidden();
            mWindowWasVisible = false;
        }
    }

    /**
     * Called when the input method window has been shown to the user, after
     * previously not being visible.  This is done after all of the UI setup
     * for the window has occurred (creating its views etc).
     */
    public void onWindowShown() {
    }
    
    /**
     * Called when the input method window has been hidden from the user,
     * after previously being visible.
     */
    public void onWindowHidden() {
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
    
    void doFinishInput() {
        if (mInputViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishInputView");
            onFinishInputView(true);
        } else if (mCandidatesViewStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishCandidatesView");
            onFinishCandidatesView(true);
        }
        mInputViewStarted = false;
        mCandidatesViewStarted = false;
        if (mInputStarted) {
            if (DEBUG) Log.v(TAG, "CALL: onFinishInput");
            onFinishInput();
        }
        mInputStarted = false;
        mStartedInputConnection = null;
        mCurCompletions = null;
    }

    void doStartInput(InputConnection ic, EditorInfo attribute, boolean restarting) {
        if (!restarting) {
            doFinishInput();
        }
        mInputStarted = true;
        mStartedInputConnection = ic;
        mInputEditorInfo = attribute;
        initialize();
        if (DEBUG) Log.v(TAG, "CALL: onStartInput");
        onStartInput(attribute, restarting);
        if (mWindowVisible) {
            if (mShowInputRequested) {
                if (DEBUG) Log.v(TAG, "CALL: onStartInputView");
                mInputViewStarted = true;
                onStartInputView(mInputEditorInfo, restarting);
                startExtractingText(true);
            } else if (mCandidatesVisibility == View.VISIBLE) {
                if (DEBUG) Log.v(TAG, "CALL: onStartCandidatesView");
                mCandidatesViewStarted = true;
                onStartCandidatesView(mInputEditorInfo, restarting);
            }
        }
    }
    
    /**
     * Called to inform the input method that text input has finished in
     * the last editor.  At this point there may be a call to
     * {@link #onStartInput(EditorInfo, boolean)} to perform input in a
     * new editor, or the input method may be left idle.  This method is
     * <em>not</em> called when input restarts in the same editor.
     * 
     * <p>The default
     * implementation uses the InputConnection to clear any active composing
     * text; you can override this (not calling the base class implementation)
     * to perform whatever behavior you would like.
     */
    public void onFinishInput() {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.finishComposingText();
        }
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
        if (text != null) {
            if (mExtractEditText != null) {
                mExtractedText = text;
                mExtractEditText.setExtractedText(text);
            }
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
        final ExtractEditText eet = mExtractEditText;
        if (eet != null && isFullscreenMode() && mExtractedText != null) {
            final int off = mExtractedText.startOffset;
            eet.startInternalChanges();
            newSelStart -= off;
            newSelEnd -= off;
            final int len = eet.getText().length();
            if (newSelStart < 0) newSelStart = 0;
            else if (newSelStart > len) newSelStart = len;
            if (newSelEnd < 0) newSelEnd = 0;
            else if (newSelEnd > len) newSelEnd = len;
            eet.setSelection(newSelStart, newSelEnd);
            eet.finishInternalChanges();
        }
    }

    /**
     * Called when the user tapped or clicked a text view.
     * IMEs can't rely on this method being called because this was not part of the original IME
     * protocol, so applications with custom text editing written before this method appeared will
     * not call to inform the IME of this interaction.
     * @param focusChanged true if the user changed the focused view by this click.
     */
    public void onViewClicked(boolean focusChanged) {
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
    public void requestHideSelf(int flags) {
        mImm.hideSoftInputFromInputMethod(mToken, flags);
    }
    
    /**
     * Show the input method. This is a call back to the
     * IMF to handle showing the input method.
     * Close this input method's soft input area, removing it from the display.
     * The input method will continue running, but the user can no longer use
     * it to generate input by touching the screen.
     * @param flags Provides additional operating flags.  Currently may be
     * 0 or have the {@link InputMethodManager#SHOW_FORCED
     * InputMethodManager.} bit set.
     */
    private void requestShowSelf(int flags) {
        mImm.showSoftInputFromInputMethod(mToken, flags);
    }
    
    private boolean handleBack(boolean doIt) {
        if (mShowInputRequested) {
            // If the soft input area is shown, back closes it and we
            // consume the back key.
            if (doIt) requestHideSelf(0);
            return true;
        } else if (mWindowVisible) {
            if (mCandidatesVisibility == View.VISIBLE) {
                // If we are showing candidates even if no input area, then
                // hide them.
                if (doIt) setCandidatesViewShown(false);
            } else {
                // If we have the window visible for some other reason --
                // most likely to show candidates -- then just get rid
                // of it.  This really shouldn't happen, but just in case...
                if (doIt) hideWindow();
            }
            return true;
        }
        return false;
    }
    
    /**
     * Override this to intercept key down events before they are processed by the
     * application.  If you return true, the application will not itself
     * process the event.  If you return true, the normal application processing
     * will occur as if the IME had not seen the event at all.
     * 
     * <p>The default implementation intercepts {@link KeyEvent#KEYCODE_BACK
     * KeyEvent.KEYCODE_BACK} if the IME is currently shown, to
     * possibly hide it when the key goes up (if not canceled or long pressed).  In
     * addition, in fullscreen mode only, it will consume DPAD movement
     * events to move the cursor in the extracted text view, not allowing
     * them to perform navigation in the underlying application.
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (handleBack(false)) {
                event.startTracking();
                return true;
            }
            return false;
        }
        return doMovementKey(keyCode, event, MOVEMENT_DOWN);
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
     * Override this to intercept special key multiple events before they are
     * processed by the
     * application.  If you return true, the application will not itself
     * process the event.  If you return true, the normal application processing
     * will occur as if the IME had not seen the event at all.
     * 
     * <p>The default implementation always returns false, except when
     * in fullscreen mode, where it will consume DPAD movement
     * events to move the cursor in the extracted text view, not allowing
     * them to perform navigation in the underlying application.
     */
    public boolean onKeyMultiple(int keyCode, int count, KeyEvent event) {
        return doMovementKey(keyCode, event, count);
    }

    /**
     * Override this to intercept key up events before they are processed by the
     * application.  If you return true, the application will not itself
     * process the event.  If you return true, the normal application processing
     * will occur as if the IME had not seen the event at all.
     * 
     * <p>The default implementation intercepts {@link KeyEvent#KEYCODE_BACK
     * KeyEvent.KEYCODE_BACK} to hide the current IME UI if it is shown.  In
     * addition, in fullscreen mode only, it will consume DPAD movement
     * events to move the cursor in the extracted text view, not allowing
     * them to perform navigation in the underlying application.
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.isTracking()
                && !event.isCanceled()) {
            return handleBack(true);
        }
        
        return doMovementKey(keyCode, event, MOVEMENT_UP);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        return false;
    }

    public void onAppPrivateCommand(String action, Bundle data) {
    }
    
    /**
     * Handle a request by the system to toggle the soft input area.
     */
    private void onToggleSoftInput(int showFlags, int hideFlags) {
        if (DEBUG) Log.v(TAG, "toggleSoftInput()");
        if (isInputViewShown()) {
            requestHideSelf(hideFlags);
        } else {
            requestShowSelf(showFlags);
        }
    }
    
    static final int MOVEMENT_DOWN = -1;
    static final int MOVEMENT_UP = -2;
    
    void reportExtractedMovement(int keyCode, int count) {
        int dx = 0, dy = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dx = -count;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dx = count;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                dy = -count;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dy = count;
                break;
        }
        onExtractedCursorMovement(dx, dy);
    }
    
    boolean doMovementKey(int keyCode, KeyEvent event, int count) {
        final ExtractEditText eet = mExtractEditText;
        if (isExtractViewShown() && isInputViewShown() && eet != null) {
            // If we are in fullscreen mode, the cursor will move around
            // the extract edit text, but should NOT cause focus to move
            // to other fields.
            MovementMethod movement = eet.getMovementMethod();
            Layout layout = eet.getLayout();
            if (movement != null && layout != null) {
                // We want our own movement method to handle the key, so the
                // cursor will properly move in our own word wrapping.
                if (count == MOVEMENT_DOWN) {
                    if (movement.onKeyDown(eet,
                            (Spannable)eet.getText(), keyCode, event)) {
                        reportExtractedMovement(keyCode, 1);
                        return true;
                    }
                } else if (count == MOVEMENT_UP) {
                    if (movement.onKeyUp(eet,
                            (Spannable)eet.getText(), keyCode, event)) {
                        return true;
                    }
                } else {
                    if (movement.onKeyOther(eet, (Spannable)eet.getText(), event)) {
                        reportExtractedMovement(keyCode, count);
                    } else {
                        KeyEvent down = KeyEvent.changeAction(event, KeyEvent.ACTION_DOWN);
                        if (movement.onKeyDown(eet,
                                (Spannable)eet.getText(), keyCode, down)) {
                            KeyEvent up = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
                            movement.onKeyUp(eet,
                                    (Spannable)eet.getText(), keyCode, up);
                            while (--count > 0) {
                                movement.onKeyDown(eet,
                                        (Spannable)eet.getText(), keyCode, down);
                                movement.onKeyUp(eet,
                                        (Spannable)eet.getText(), keyCode, up);
                            }
                            reportExtractedMovement(keyCode, count);
                        }
                    }
                }
            }
            // Regardless of whether the movement method handled the key,
            // we never allow DPAD navigation to the application.
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
            }
        }
        
        return false;
    }
    
    /**
     * Send the given key event code (as defined by {@link KeyEvent}) to the
     * current input connection is a key down + key up event pair.  The sent
     * events have {@link KeyEvent#FLAG_SOFT_KEYBOARD KeyEvent.FLAG_SOFT_KEYBOARD}
     * set, so that the recipient can identify them as coming from a software
     * input method, and
     * {@link KeyEvent#FLAG_KEEP_TOUCH_MODE KeyEvent.FLAG_KEEP_TOUCH_MODE}, so
     * that they don't impact the current touch mode of the UI.
     *
     * @param keyEventCode The raw key code to send, as defined by
     * {@link KeyEvent}.
     */
    public void sendDownUpKeyEvents(int keyEventCode) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        long eventTime = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(eventTime, eventTime,
                KeyEvent.ACTION_DOWN, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD|KeyEvent.FLAG_KEEP_TOUCH_MODE));
        ic.sendKeyEvent(new KeyEvent(SystemClock.uptimeMillis(), eventTime,
                KeyEvent.ACTION_UP, keyEventCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_SOFT_KEYBOARD|KeyEvent.FLAG_KEEP_TOUCH_MODE));
    }
    
    /**
     * Ask the input target to execute its default action via
     * {@link InputConnection#performEditorAction
     * InputConnection.performEditorAction()}.
     * 
     * @param fromEnterKey If true, this will be executed as if the user had
     * pressed an enter key on the keyboard, that is it will <em>not</em>
     * be done if the editor has set {@link EditorInfo#IME_FLAG_NO_ENTER_ACTION
     * EditorInfo.IME_FLAG_NO_ENTER_ACTION}.  If false, the action will be
     * sent regardless of how the editor has set that flag.
     * 
     * @return Returns a boolean indicating whether an action has been sent.
     * If false, either the editor did not specify a default action or it
     * does not want an action from the enter key.  If true, the action was
     * sent (or there was no input connection at all).
     */
    public boolean sendDefaultEditorAction(boolean fromEnterKey) {
        EditorInfo ei = getCurrentInputEditorInfo();
        if (ei != null &&
                (!fromEnterKey || (ei.imeOptions &
                        EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) &&
                (ei.imeOptions & EditorInfo.IME_MASK_ACTION) !=
                    EditorInfo.IME_ACTION_NONE) {
            // If the enter key was pressed, and the editor has a default
            // action associated with pressing enter, then send it that
            // explicit action instead of the key event.
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.performEditorAction(ei.imeOptions&EditorInfo.IME_MASK_ACTION);
            }
            return true;
        }
        
        return false;
    }
    
    /**
     * Send the given UTF-16 character to the current input connection.  Most
     * characters will be delivered simply by calling
     * {@link InputConnection#commitText InputConnection.commitText()} with
     * the character; some, however, may be handled different.  In particular,
     * the enter character ('\n') will either be delivered as an action code
     * or a raw key event, as appropriate.
     * 
     * @param charCode The UTF-16 character code to send.
     */
    public void sendKeyChar(char charCode) {
        switch (charCode) {
            case '\n': // Apps may be listening to an enter key to perform an action
                if (!sendDefaultEditorAction(true)) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
                }
                break;
            default:
                // Make sure that digits go through any text watcher on the client side.
                if (charCode >= '0' && charCode <= '9') {
                    sendDownUpKeyEvents(charCode - '0' + KeyEvent.KEYCODE_0);
                } else {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) {
                        ic.commitText(String.valueOf((char) charCode), 1);
                    }
                }
                break;
        }
    }
    
    /**
     * This is called when the user has moved the cursor in the extracted
     * text view, when running in fullsreen mode.  The default implementation
     * performs the corresponding selection change on the underlying text
     * editor.
     */
    public void onExtractedSelectionChanged(int start, int end) {
        InputConnection conn = getCurrentInputConnection();
        if (conn != null) {
            conn.setSelection(start, end);
        }
    }
    
    /**
     * This is called when the user has clicked on the extracted text view,
     * when running in fullscreen mode.  The default implementation hides
     * the candidates view when this happens, but only if the extracted text
     * editor has a vertical scroll bar because its text doesn't fit.
     * Re-implement this to provide whatever behavior you want.
     */
    public void onExtractedTextClicked() {
        if (mExtractEditText == null) {
            return;
        }
        if (mExtractEditText.hasVerticalScrollBar()) {
            setCandidatesViewShown(false);
        }
    }
    
    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode.  The default
     * implementation hides the candidates view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit.
     * Re-implement this to provide whatever behavior you want.
     * @param dx The amount of cursor movement in the x dimension.
     * @param dy The amount of cursor movement in the y dimension.
     */
    public void onExtractedCursorMovement(int dx, int dy) {
        if (mExtractEditText == null || dy == 0) {
            return;
        }
        if (mExtractEditText.hasVerticalScrollBar()) {
            setCandidatesViewShown(false);
        }
    }
    
    /**
     * This is called when the user has selected a context menu item from the
     * extracted text view, when running in fullscreen mode.  The default
     * implementation sends this action to the current InputConnection's
     * {@link InputConnection#performContextMenuAction(int)}, for it
     * to be processed in underlying "real" editor.  Re-implement this to
     * provide whatever behavior you want.
     */
    public boolean onExtractTextContextMenuItem(int id) {
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            ic.performContextMenuAction(id);
        }
        return true;
    }
    
    /**
     * Return text that can be used as a button label for the given
     * {@link EditorInfo#imeOptions EditorInfo.imeOptions}.  Returns null
     * if there is no action requested.  Note that there is no guarantee that
     * the returned text will be relatively short, so you probably do not
     * want to use it as text on a soft keyboard key label.
     * 
     * @param imeOptions The value from @link EditorInfo#imeOptions EditorInfo.imeOptions}.
     * 
     * @return Returns a label to use, or null if there is no action.
     */
    public CharSequence getTextForImeAction(int imeOptions) {
        switch (imeOptions&EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_NONE:
                return null;
            case EditorInfo.IME_ACTION_GO:
                return getText(com.android.internal.R.string.ime_action_go);
            case EditorInfo.IME_ACTION_SEARCH:
                return getText(com.android.internal.R.string.ime_action_search);
            case EditorInfo.IME_ACTION_SEND:
                return getText(com.android.internal.R.string.ime_action_send);
            case EditorInfo.IME_ACTION_NEXT:
                return getText(com.android.internal.R.string.ime_action_next);
            case EditorInfo.IME_ACTION_DONE:
                return getText(com.android.internal.R.string.ime_action_done);
            case EditorInfo.IME_ACTION_PREVIOUS:
                return getText(com.android.internal.R.string.ime_action_previous);
            default:
                return getText(com.android.internal.R.string.ime_action_default);
        }
    }
    
    /**
     * Called when the fullscreen-mode extracting editor info has changed,
     * to determine whether the extracting (extract text and candidates) portion
     * of the UI should be shown.  The standard implementation hides or shows
     * the extract area depending on whether it makes sense for the
     * current editor.  In particular, a {@link InputType#TYPE_NULL}
     * input type or {@link EditorInfo#IME_FLAG_NO_EXTRACT_UI} flag will
     * turn off the extract area since there is no text to be shown.
     */
    public void onUpdateExtractingVisibility(EditorInfo ei) {
        if (ei.inputType == InputType.TYPE_NULL ||
                (ei.imeOptions&EditorInfo.IME_FLAG_NO_EXTRACT_UI) != 0) {
            // No reason to show extract UI!
            setExtractViewShown(false);
            return;
        }
        
        setExtractViewShown(true);
    }
    
    /**
     * Called when the fullscreen-mode extracting editor info has changed,
     * to update the state of its UI such as the action buttons shown.
     * You do not need to deal with this if you are using the standard
     * full screen extract UI.  If replacing it, you will need to re-implement
     * this to put the appropriate action button in your own UI and handle it,
     * and perform any other changes.
     * 
     * <p>The standard implementation turns on or off its accessory area
     * depending on whether there is an action button, and hides or shows
     * the entire extract area depending on whether it makes sense for the
     * current editor.  In particular, a {@link InputType#TYPE_NULL} or 
     * {@link InputType#TYPE_TEXT_VARIATION_FILTER} input type will turn off the
     * extract area since there is no text to be shown.
     */
    public void onUpdateExtractingViews(EditorInfo ei) {
        if (!isExtractViewShown()) {
            return;
        }
        
        if (mExtractAccessories == null) {
            return;
        }
        final boolean hasAction = ei.actionLabel != null || (
                (ei.imeOptions&EditorInfo.IME_MASK_ACTION) != EditorInfo.IME_ACTION_NONE &&
                (ei.imeOptions&EditorInfo.IME_FLAG_NO_ACCESSORY_ACTION) == 0 &&
                ei.inputType != InputType.TYPE_NULL);
        if (hasAction) {
            mExtractAccessories.setVisibility(View.VISIBLE);
            if (mExtractAction != null) {
                if (ei.actionLabel != null) {
                    mExtractAction.setText(ei.actionLabel);
                } else {
                    mExtractAction.setText(getTextForImeAction(ei.imeOptions));
                }
                mExtractAction.setOnClickListener(mActionClickListener);
            }
        } else {
            mExtractAccessories.setVisibility(View.GONE);
            if (mExtractAction != null) {
                mExtractAction.setOnClickListener(null);
            }
        }
    }
    
    /**
     * This is called when, while currently displayed in extract mode, the
     * current input target changes.  The default implementation will
     * auto-hide the IME if the new target is not a full editor, since this
     * can be an confusing experience for the user.
     */
    public void onExtractingInputChanged(EditorInfo ei) {
        if (ei.inputType == InputType.TYPE_NULL) {
            requestHideSelf(InputMethodManager.HIDE_NOT_ALWAYS);
        }
    }
    
    void startExtractingText(boolean inputChanged) {
        final ExtractEditText eet = mExtractEditText;
        if (eet != null && getCurrentInputStarted()
                && isFullscreenMode()) {
            mExtractedToken++;
            ExtractedTextRequest req = new ExtractedTextRequest();
            req.token = mExtractedToken;
            req.flags = InputConnection.GET_TEXT_WITH_STYLES;
            req.hintMaxLines = 10;
            req.hintMaxChars = 10000;
            InputConnection ic = getCurrentInputConnection();
            mExtractedText = ic == null? null
                    : ic.getExtractedText(req, InputConnection.GET_EXTRACTED_TEXT_MONITOR);
            if (mExtractedText == null || ic == null) {
                Log.e(TAG, "Unexpected null in startExtractingText : mExtractedText = "
                        + mExtractedText + ", input connection = " + ic);
            }
            final EditorInfo ei = getCurrentInputEditorInfo();
            
            try {
                eet.startInternalChanges();
                onUpdateExtractingVisibility(ei);
                onUpdateExtractingViews(ei);
                int inputType = ei.inputType;
                if ((inputType&EditorInfo.TYPE_MASK_CLASS)
                        == EditorInfo.TYPE_CLASS_TEXT) {
                    if ((inputType&EditorInfo.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0) {
                        inputType |= EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE;
                    }
                }
                eet.setInputType(inputType);
                eet.setHint(ei.hintText);
                if (mExtractedText != null) {
                    eet.setEnabled(true);
                    eet.setExtractedText(mExtractedText);
                } else {
                    eet.setEnabled(false);
                    eet.setText("");
                }
            } finally {
                eet.finishInternalChanges();
            }
            
            if (inputChanged) {
                onExtractingInputChanged(ei);
            }
        }
    }

    // TODO: Handle the subtype change event
    /**
     * Called when the subtype was changed.
     * @param newSubtype the subtype which is being changed to.
     */
    protected void onCurrentInputMethodSubtypeChanged(InputMethodSubtype newSubtype) {
        if (DEBUG) {
            int nameResId = newSubtype.getNameResId();
            String mode = newSubtype.getMode();
            String output = "changeInputMethodSubtype:"
                + (nameResId == 0 ? "<none>" : getString(nameResId)) + ","
                + mode + ","
                + newSubtype.getLocale() + "," + newSubtype.getExtraValue();
            Log.v(TAG, "--- " + output);
        }
    }

    /**
     * Performs a dump of the InputMethodService's internal state.  Override
     * to add your own information to the dump.
     */
    @Override protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        final Printer p = new PrintWriterPrinter(fout);
        p.println("Input method service state for " + this + ":");
        p.println("  mWindowCreated=" + mWindowCreated
                + " mWindowAdded=" + mWindowAdded);
        p.println("  mWindowVisible=" + mWindowVisible
                + " mWindowWasVisible=" + mWindowWasVisible
                + " mInShowWindow=" + mInShowWindow);
        p.println("  Configuration=" + getResources().getConfiguration());
        p.println("  mToken=" + mToken);
        p.println("  mInputBinding=" + mInputBinding);
        p.println("  mInputConnection=" + mInputConnection);
        p.println("  mStartedInputConnection=" + mStartedInputConnection);
        p.println("  mInputStarted=" + mInputStarted
                + " mInputViewStarted=" + mInputViewStarted
                + " mCandidatesViewStarted=" + mCandidatesViewStarted);
        
        if (mInputEditorInfo != null) {
            p.println("  mInputEditorInfo:");
            mInputEditorInfo.dump(p, "    ");
        } else {
            p.println("  mInputEditorInfo: null");
        }
        
        p.println("  mShowInputRequested=" + mShowInputRequested
                + " mLastShowInputRequested=" + mLastShowInputRequested
                + " mShowInputForced=" + mShowInputForced
                + " mShowInputFlags=0x" + Integer.toHexString(mShowInputFlags));
        p.println("  mCandidatesVisibility=" + mCandidatesVisibility
                + " mFullscreenApplied=" + mFullscreenApplied
                + " mIsFullscreen=" + mIsFullscreen
                + " mExtractViewHidden=" + mExtractViewHidden);
        
        if (mExtractedText != null) {
            p.println("  mExtractedText:");
            p.println("    text=" + mExtractedText.text.length() + " chars"
                    + " startOffset=" + mExtractedText.startOffset);
            p.println("    selectionStart=" + mExtractedText.selectionStart
                    + " selectionEnd=" + mExtractedText.selectionEnd
                    + " flags=0x" + Integer.toHexString(mExtractedText.flags));
        } else {
            p.println("  mExtractedText: null");
        }
        p.println("  mExtractedToken=" + mExtractedToken);
        p.println("  mIsInputViewShown=" + mIsInputViewShown
                + " mStatusIcon=" + mStatusIcon);
        p.println("Last computed insets:");
        p.println("  contentTopInsets=" + mTmpInsets.contentTopInsets
                + " visibleTopInsets=" + mTmpInsets.visibleTopInsets
                + " touchableInsets=" + mTmpInsets.touchableInsets
                + " touchableRegion=" + mTmpInsets.touchableRegion);
    }
}
