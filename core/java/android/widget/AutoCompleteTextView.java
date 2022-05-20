/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget;

import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.database.DataSetObserver;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.Selection;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.WindowManager;
import android.view.inputmethod.CompletionInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.inspector.InspectableProperty;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.internal.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * <p>An editable text view that shows completion suggestions automatically
 * while the user is typing. The list of suggestions is displayed in a drop
 * down menu from which the user can choose an item to replace the content
 * of the edit box with.</p>
 *
 * <p>The drop down can be dismissed at any time by pressing the back key or,
 * if no item is selected in the drop down, by pressing the enter/dpad center
 * key.</p>
 *
 * <p>The list of suggestions is obtained from a data adapter and appears
 * only after a given number of characters defined by
 * {@link #getThreshold() the threshold}.</p>
 *
 * <p>The following code snippet shows how to create a text view which suggests
 * various countries names while the user is typing:</p>
 *
 * <pre class="prettyprint">
 * public class CountriesActivity extends Activity {
 *     protected void onCreate(Bundle icicle) {
 *         super.onCreate(icicle);
 *         setContentView(R.layout.countries);
 *
 *         ArrayAdapter&lt;String&gt; adapter = new ArrayAdapter&lt;String&gt;(this,
 *                 android.R.layout.simple_dropdown_item_1line, COUNTRIES);
 *         AutoCompleteTextView textView = (AutoCompleteTextView)
 *                 findViewById(R.id.countries_list);
 *         textView.setAdapter(adapter);
 *     }
 *
 *     private static final String[] COUNTRIES = new String[] {
 *         "Belgium", "France", "Italy", "Germany", "Spain"
 *     };
 * }
 * </pre>
 *
 * <p>See the <a href="{@docRoot}guide/topics/ui/controls/text.html">Text Fields</a>
 * guide.</p>
 *
 * @attr ref android.R.styleable#AutoCompleteTextView_completionHint
 * @attr ref android.R.styleable#AutoCompleteTextView_completionThreshold
 * @attr ref android.R.styleable#AutoCompleteTextView_completionHintView
 * @attr ref android.R.styleable#AutoCompleteTextView_dropDownSelector
 * @attr ref android.R.styleable#AutoCompleteTextView_dropDownAnchor
 * @attr ref android.R.styleable#AutoCompleteTextView_dropDownWidth
 * @attr ref android.R.styleable#AutoCompleteTextView_dropDownHeight
 * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
 * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
 */
public class AutoCompleteTextView extends EditText implements Filter.FilterListener {
    static final boolean DEBUG = false;
    static final String TAG = "AutoCompleteTextView";

    static final int EXPAND_MAX = 3;

    /** Context used to inflate the popup window or dialog. */
    private final Context mPopupContext;

    @UnsupportedAppUsage
    private final ListPopupWindow mPopup;
    @UnsupportedAppUsage
    private final PassThroughClickListener mPassThroughClickListener;

    private CharSequence mHintText;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private TextView mHintView;
    private int mHintResource;

    private ListAdapter mAdapter;
    private Filter mFilter;
    private int mThreshold;

    private int mDropDownAnchorId;

    private AdapterView.OnItemClickListener mItemClickListener;
    private AdapterView.OnItemSelectedListener mItemSelectedListener;

    private boolean mDropDownDismissedOnCompletion = true;

    private int mLastKeyCode = KeyEvent.KEYCODE_UNKNOWN;
    private MyWatcher mAutoCompleteTextWatcher;

    private Validator mValidator = null;

    // Set to true when text is set directly and no filtering shall be performed
    private boolean mBlockCompletion;

    // When set, an update in the underlying adapter will update the result list popup.
    // Set to false when the list is hidden to prevent asynchronous updates to popup the list again.
    private boolean mPopupCanBeUpdated = true;

    @UnsupportedAppUsage
    private PopupDataSetObserver mObserver;

    private boolean mBackCallbackRegistered;
    /** Handles back invocation */
    private final OnBackInvokedCallback mBackCallback = () -> {
        if (isPopupShowing()) {
            dismissDropDown();
        }
    };

    /**
     * Constructs a new auto-complete text view with the given context's theme.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public AutoCompleteTextView(Context context) {
        this(context, null);
    }

    /**
     * Constructs a new auto-complete text view with the given context's theme
     * and the supplied attribute set.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public AutoCompleteTextView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.autoCompleteTextViewStyle);
    }

    /**
     * Constructs a new auto-complete text view with the given context's theme,
     * the supplied attribute set, and default style attribute.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     */
    public AutoCompleteTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    /**
     * Constructs a new auto-complete text view with the given context's theme,
     * the supplied attribute set, and default styles.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *                    supplies default values for the view, used only if
     *                    defStyleAttr is 0 or can not be found in the theme.
     *                    Can be 0 to not look for defaults.
     */
    public AutoCompleteTextView(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        this(context, attrs, defStyleAttr, defStyleRes, null);
    }

    /**
     * Constructs a new auto-complete text view with the given context, the
     * supplied attribute set, default styles, and the theme against which the
     * completion popup should be inflated.
     *
     * @param context The context against which the view is inflated, which
     *                provides access to the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *                    supplies default values for the view, used only if
     *                    defStyleAttr is 0 or can not be found in the theme.
     *                    Can be 0 to not look for defaults.
     * @param popupTheme The theme against which the completion popup window
     *                   should be inflated. May be {@code null} to use the
     *                   view theme. If set, this will override any value
     *                   specified by
     *                   {@link android.R.styleable#AutoCompleteTextView_popupTheme}.
     */
    public AutoCompleteTextView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes, Theme popupTheme) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.AutoCompleteTextView, defStyleAttr, defStyleRes);
        saveAttributeDataForStyleable(context,  R.styleable.AutoCompleteTextView,
                attrs, a, defStyleAttr, defStyleRes);

        if (popupTheme != null) {
            mPopupContext = new ContextThemeWrapper(context, popupTheme);
        } else {
            final int popupThemeResId = a.getResourceId(
                    R.styleable.AutoCompleteTextView_popupTheme, 0);
            if (popupThemeResId != 0) {
                mPopupContext = new ContextThemeWrapper(context, popupThemeResId);
            } else {
                mPopupContext = context;
            }
        }

        // Load attributes used within the popup against the popup context.
        final TypedArray pa;
        if (mPopupContext != context) {
            pa = mPopupContext.obtainStyledAttributes(
                    attrs, R.styleable.AutoCompleteTextView, defStyleAttr, defStyleRes);
            saveAttributeDataForStyleable(context, R.styleable.AutoCompleteTextView,
                    attrs, a, defStyleAttr, defStyleRes);
        } else {
            pa = a;
        }

        final Drawable popupListSelector = pa.getDrawable(
                R.styleable.AutoCompleteTextView_dropDownSelector);
        final int popupWidth = pa.getLayoutDimension(
                R.styleable.AutoCompleteTextView_dropDownWidth, LayoutParams.WRAP_CONTENT);
        final int popupHeight = pa.getLayoutDimension(
                R.styleable.AutoCompleteTextView_dropDownHeight, LayoutParams.WRAP_CONTENT);
        final int popupHintLayoutResId = pa.getResourceId(
                R.styleable.AutoCompleteTextView_completionHintView, R.layout.simple_dropdown_hint);
        final CharSequence popupHintText = pa.getText(
                R.styleable.AutoCompleteTextView_completionHint);

        if (pa != a) {
            pa.recycle();
        }

        mPopup = new ListPopupWindow(mPopupContext, attrs, defStyleAttr, defStyleRes);
        mPopup.setOnDismissListener(() -> {
            unregisterOnBackInvokedCallback();
        });
        mPopup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        mPopup.setPromptPosition(ListPopupWindow.POSITION_PROMPT_BELOW);
        mPopup.setListSelector(popupListSelector);
        mPopup.setOnItemClickListener(new DropDownItemClickListener());

        // For dropdown width, the developer can specify a specific width, or
        // MATCH_PARENT (for full screen width), or WRAP_CONTENT (to match the
        // width of the anchored view).
        mPopup.setWidth(popupWidth);
        mPopup.setHeight(popupHeight);

        // Completion hint must be set after specifying hint layout.
        mHintResource = popupHintLayoutResId;
        setCompletionHint(popupHintText);

        // Get the anchor's id now, but the view won't be ready, so wait to
        // actually get the view and store it in mDropDownAnchorView lazily in
        // getDropDownAnchorView later. Defaults to NO_ID, in which case the
        // getDropDownAnchorView method will simply return this TextView, as a
        // default anchoring point.
        mDropDownAnchorId = a.getResourceId(
                R.styleable.AutoCompleteTextView_dropDownAnchor, View.NO_ID);

        mThreshold = a.getInt(R.styleable.AutoCompleteTextView_completionThreshold, 2);

        a.recycle();

        // Always turn on the auto complete input type flag, since it
        // makes no sense to use this widget without it.
        int inputType = getInputType();
        if ((inputType & EditorInfo.TYPE_MASK_CLASS) == EditorInfo.TYPE_CLASS_TEXT) {
            inputType |= EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE;
            setRawInputType(inputType);
        }

        setFocusable(true);

        mAutoCompleteTextWatcher = new MyWatcher();
        addTextChangedListener(mAutoCompleteTextWatcher);

        mPassThroughClickListener = new PassThroughClickListener();
        super.setOnClickListener(mPassThroughClickListener);
    }

    @Override
    public void setOnClickListener(OnClickListener listener) {
        mPassThroughClickListener.mWrapped = listener;
    }

    /**
     * Private hook into the on click event, dispatched from {@link PassThroughClickListener}
     */
    private void onClickImpl() {
        // If the dropdown is showing, bring the keyboard to the front
        // when the user touches the text field.
        if (isPopupShowing()) {
            ensureImeVisible(true);
        }
    }

    /**
     * <p>Sets the optional hint text that is displayed at the bottom of the
     * the matching list.  This can be used as a cue to the user on how to
     * best use the list, or to provide extra information.</p>
     *
     * @param hint the text to be displayed to the user
     *
     * @see #getCompletionHint()
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_completionHint
     */
    public void setCompletionHint(CharSequence hint) {
        mHintText = hint;
        if (hint != null) {
            if (mHintView == null) {
                final TextView hintView = (TextView) LayoutInflater.from(mPopupContext).inflate(
                        mHintResource, null).findViewById(R.id.text1);
                hintView.setText(mHintText);
                mHintView = hintView;
                mPopup.setPromptView(hintView);
            } else {
                mHintView.setText(hint);
            }
        } else {
            mPopup.setPromptView(null);
            mHintView = null;
        }
    }

    /**
     * Gets the optional hint text displayed at the bottom of the the matching list.
     *
     * @return The hint text, if any
     *
     * @see #setCompletionHint(CharSequence)
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_completionHint
     */
    @InspectableProperty
    public CharSequence getCompletionHint() {
        return mHintText;
    }

    /**
     * Returns the current width for the auto-complete drop down list.
     *
     * This can be a fixed width, or {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to fill the screen, or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
     * to fit the width of its anchor view.
     *
     * @return the width for the drop down list
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownWidth
     */
    @InspectableProperty
    public int getDropDownWidth() {
        return mPopup.getWidth();
    }

    /**
     * Sets the current width for the auto-complete drop down list.
     *
     * This can be a fixed width, or {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to fill the screen, or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
     * to fit the width of its anchor view.
     *
     * @param width the width to use
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownWidth
     */
    public void setDropDownWidth(int width) {
        mPopup.setWidth(width);
    }

    /**
     * <p>Returns the current height for the auto-complete drop down list.
     *
     * This can be a fixed width, or {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to fill the screen, or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
     * to fit the width of its anchor view.
     *
     * @return the height for the drop down list
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownHeight
     */
    @InspectableProperty
    public int getDropDownHeight() {
        return mPopup.getHeight();
    }

    /**
     * Sets the current height for the auto-complete drop down list.
     *
     * This can be a fixed width, or {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
     * to fill the screen, or {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
     * to fit the width of its anchor view.
     *
     * @param height the height to use
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownHeight
     */
    public void setDropDownHeight(int height) {
        mPopup.setHeight(height);
    }

    /**
     * <p>Returns the id for the view that the auto-complete drop down list is anchored to.</p>
     *
     * @return the view's id, or {@link View#NO_ID} if none specified
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownAnchor
     */
    public int getDropDownAnchor() {
        return mDropDownAnchorId;
    }

    /**
     * <p>Sets the view to which the auto-complete drop down list should anchor. The view
     * corresponding to this id will not be loaded until the next time it is needed to avoid
     * loading a view which is not yet instantiated.</p>
     *
     * @param id the id to anchor the drop down list view to
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_dropDownAnchor
     */
    public void setDropDownAnchor(int id) {
        mDropDownAnchorId = id;
        mPopup.setAnchorView(null);
    }

    /**
     * <p>Gets the background of the auto-complete drop-down list.</p>
     *
     * @return the background drawable
     *
     * @attr ref android.R.styleable#PopupWindow_popupBackground
     */
    @InspectableProperty(name = "popupBackground")
    public Drawable getDropDownBackground() {
        return mPopup.getBackground();
    }

    /**
     * <p>Sets the background of the auto-complete drop-down list.</p>
     *
     * @param d the drawable to set as the background
     *
     * @attr ref android.R.styleable#PopupWindow_popupBackground
     */
    public void setDropDownBackgroundDrawable(Drawable d) {
        mPopup.setBackgroundDrawable(d);
    }

    /**
     * <p>Sets the background of the auto-complete drop-down list.</p>
     *
     * @param id the id of the drawable to set as the background
     *
     * @attr ref android.R.styleable#PopupWindow_popupBackground
     */
    public void setDropDownBackgroundResource(@DrawableRes int id) {
        mPopup.setBackgroundDrawable(getContext().getDrawable(id));
    }

    /**
     * <p>Sets the vertical offset used for the auto-complete drop-down list.</p>
     *
     * @param offset the vertical offset
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
     */
    public void setDropDownVerticalOffset(int offset) {
        mPopup.setVerticalOffset(offset);
    }

    /**
     * <p>Gets the vertical offset used for the auto-complete drop-down list.</p>
     *
     * @return the vertical offset
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownVerticalOffset
     */
    @InspectableProperty
    public int getDropDownVerticalOffset() {
        return mPopup.getVerticalOffset();
    }

    /**
     * <p>Sets the horizontal offset used for the auto-complete drop-down list.</p>
     *
     * @param offset the horizontal offset
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
     */
    public void setDropDownHorizontalOffset(int offset) {
        mPopup.setHorizontalOffset(offset);
    }

    /**
     * <p>Gets the horizontal offset used for the auto-complete drop-down list.</p>
     *
     * @return the horizontal offset
     *
     * @attr ref android.R.styleable#ListPopupWindow_dropDownHorizontalOffset
     */
    @InspectableProperty
    public int getDropDownHorizontalOffset() {
        return mPopup.getHorizontalOffset();
    }

     /**
     * <p>Sets the animation style of the auto-complete drop-down list.</p>
     *
     * <p>If the drop-down is showing, calling this method will take effect only
     * the next time the drop-down is shown.</p>
     *
     * @param animationStyle animation style to use when the drop-down appears
     *      and disappears.  Set to -1 for the default animation, 0 for no
     *      animation, or a resource identifier for an explicit animation.
     *
     * @hide Pending API council approval
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void setDropDownAnimationStyle(int animationStyle) {
        mPopup.setAnimationStyle(animationStyle);
    }

    /**
     * <p>Returns the animation style that is used when the drop-down list appears and disappears
     * </p>
     *
     * @return the animation style that is used when the drop-down list appears and disappears
     *
     * @hide Pending API council approval
     */
    public int getDropDownAnimationStyle() {
        return mPopup.getAnimationStyle();
    }

    /**
     * @return Whether the drop-down is visible as long as there is {@link #enoughToFilter()}
     *
     * @hide Pending API council approval
     */
    public boolean isDropDownAlwaysVisible() {
        return mPopup.isDropDownAlwaysVisible();
    }

    /**
     * Sets whether the drop-down should remain visible as long as there is there is
     * {@link #enoughToFilter()}.  This is useful if an unknown number of results are expected
     * to show up in the adapter sometime in the future.
     *
     * The drop-down will occupy the entire screen below {@link #getDropDownAnchor} regardless
     * of the size or content of the list.  {@link #getDropDownBackground()} will fill any space
     * that is not used by the list.
     *
     * @param dropDownAlwaysVisible Whether to keep the drop-down visible.
     *
     * @hide Pending API council approval
     */
    @UnsupportedAppUsage
    public void setDropDownAlwaysVisible(boolean dropDownAlwaysVisible) {
        mPopup.setDropDownAlwaysVisible(dropDownAlwaysVisible);
    }

    /**
     * Checks whether the drop-down is dismissed when a suggestion is clicked.
     *
     * @hide Pending API council approval
     */
    public boolean isDropDownDismissedOnCompletion() {
        return mDropDownDismissedOnCompletion;
    }

    /**
     * Sets whether the drop-down is dismissed when a suggestion is clicked. This is
     * true by default.
     *
     * @param dropDownDismissedOnCompletion Whether to dismiss the drop-down.
     *
     * @hide Pending API council approval
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void setDropDownDismissedOnCompletion(boolean dropDownDismissedOnCompletion) {
        mDropDownDismissedOnCompletion = dropDownDismissedOnCompletion;
    }

    /**
     * <p>Returns the number of characters the user must type before the drop
     * down list is shown.</p>
     *
     * @return the minimum number of characters to type to show the drop down
     *
     * @see #setThreshold(int)
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_completionThreshold
     */
    @InspectableProperty(name = "completionThreshold")
    public int getThreshold() {
        return mThreshold;
    }

    /**
     * <p>Specifies the minimum number of characters the user has to type in the
     * edit box before the drop down list is shown.</p>
     *
     * <p>When <code>threshold</code> is less than or equals 0, a threshold of
     * 1 is applied.</p>
     *
     * @param threshold the number of characters to type before the drop down
     *                  is shown
     *
     * @see #getThreshold()
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_completionThreshold
     */
    public void setThreshold(int threshold) {
        if (threshold <= 0) {
            threshold = 1;
        }

        mThreshold = threshold;
    }

    /**
     * <p>Sets the listener that will be notified when the user clicks an item
     * in the drop down list.</p>
     *
     * @param l the item click listener
     */
    public void setOnItemClickListener(AdapterView.OnItemClickListener l) {
        mItemClickListener = l;
    }

    /**
     * <p>Sets the listener that will be notified when the user selects an item
     * in the drop down list.</p>
     *
     * @param l the item selected listener
     */
    public void setOnItemSelectedListener(AdapterView.OnItemSelectedListener l) {
        mItemSelectedListener = l;
    }

    /**
     * <p>Returns the listener that is notified whenever the user clicks an item
     * in the drop down list.</p>
     *
     * @return the item click listener
     *
     * @deprecated Use {@link #getOnItemClickListener()} intead
     */
    @Deprecated
    public AdapterView.OnItemClickListener getItemClickListener() {
        return mItemClickListener;
    }

    /**
     * <p>Returns the listener that is notified whenever the user selects an
     * item in the drop down list.</p>
     *
     * @return the item selected listener
     *
     * @deprecated Use {@link #getOnItemSelectedListener()} intead
     */
    @Deprecated
    public AdapterView.OnItemSelectedListener getItemSelectedListener() {
        return mItemSelectedListener;
    }

    /**
     * <p>Returns the listener that is notified whenever the user clicks an item
     * in the drop down list.</p>
     *
     * @return the item click listener
     */
    public AdapterView.OnItemClickListener getOnItemClickListener() {
        return mItemClickListener;
    }

    /**
     * <p>Returns the listener that is notified whenever the user selects an
     * item in the drop down list.</p>
     *
     * @return the item selected listener
     */
    public AdapterView.OnItemSelectedListener getOnItemSelectedListener() {
        return mItemSelectedListener;
    }

    /**
     * Set a listener that will be invoked whenever the AutoCompleteTextView's
     * list of completions is dismissed.
     * @param dismissListener Listener to invoke when completions are dismissed
     */
    public void setOnDismissListener(final OnDismissListener dismissListener) {
        PopupWindow.OnDismissListener wrappedListener = null;
        if (dismissListener != null) {
            wrappedListener = new PopupWindow.OnDismissListener() {
                @Override public void onDismiss() {
                    dismissListener.onDismiss();
                    unregisterOnBackInvokedCallback();
                }
            };
        }
        mPopup.setOnDismissListener(wrappedListener);
    }

    /**
     * <p>Returns a filterable list adapter used for auto completion.</p>
     *
     * @return a data adapter used for auto completion
     */
    public ListAdapter getAdapter() {
        return mAdapter;
    }

    /**
     * <p>Changes the list of data used for auto completion. The provided list
     * must be a filterable list adapter.</p>
     *
     * <p>The caller is still responsible for managing any resources used by the adapter.
     * Notably, when the AutoCompleteTextView is closed or released, the adapter is not notified.
     * A common case is the use of {@link android.widget.CursorAdapter}, which
     * contains a {@link android.database.Cursor} that must be closed.  This can be done
     * automatically (see
     * {@link android.app.Activity#startManagingCursor(android.database.Cursor)
     * startManagingCursor()}),
     * or by manually closing the cursor when the AutoCompleteTextView is dismissed.</p>
     *
     * @param adapter the adapter holding the auto completion data
     *
     * @see #getAdapter()
     * @see android.widget.Filterable
     * @see android.widget.ListAdapter
     */
    public <T extends ListAdapter & Filterable> void setAdapter(T adapter) {
        if (mObserver == null) {
            mObserver = new PopupDataSetObserver(this);
        } else if (mAdapter != null) {
            mAdapter.unregisterDataSetObserver(mObserver);
        }
        mAdapter = adapter;
        if (mAdapter != null) {
            //noinspection unchecked
            mFilter = ((Filterable) mAdapter).getFilter();
            adapter.registerDataSetObserver(mObserver);
        } else {
            mFilter = null;
        }

        mPopup.setAdapter(mAdapter);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
                && isPopupShowing() && !mPopup.isDropDownAlwaysVisible()) {
            // special case for the back key, we do not even try to send it
            // to the drop down list but instead, consume it immediately
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                KeyEvent.DispatcherState state = getKeyDispatcherState();
                if (state != null) {
                    state.startTracking(event, this);
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                KeyEvent.DispatcherState state = getKeyDispatcherState();
                if (state != null) {
                    state.handleUpEvent(event);
                }
                if (event.isTracking() && !event.isCanceled()) {
                    dismissDropDown();
                    return true;
                }
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean consumed = mPopup.onKeyUp(keyCode, event);
        if (consumed) {
            switch (keyCode) {
            // if the list accepts the key events and the key event
            // was a click, the text view gets the selected item
            // from the drop down as its content
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_TAB:
                if (event.hasNoModifiers()) {
                    performCompletion();
                }
                return true;
            }
        }

        if (isPopupShowing() && keyCode == KeyEvent.KEYCODE_TAB && event.hasNoModifiers()) {
            performCompletion();
            return true;
        }

        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (mPopup.onKeyDown(keyCode, event)) {
            return true;
        }

        if (!isPopupShowing()) {
            switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.hasNoModifiers()) {
                    performValidation();
                }
            }
        }

        if (isPopupShowing() && keyCode == KeyEvent.KEYCODE_TAB && event.hasNoModifiers()) {
            return true;
        }

        mLastKeyCode = keyCode;
        boolean handled = super.onKeyDown(keyCode, event);
        mLastKeyCode = KeyEvent.KEYCODE_UNKNOWN;

        if (handled && isPopupShowing()) {
            clearListSelection();
        }

        return handled;
    }

    /**
     * Returns <code>true</code> if the amount of text in the field meets
     * or exceeds the {@link #getThreshold} requirement.  You can override
     * this to impose a different standard for when filtering will be
     * triggered.
     */
    public boolean enoughToFilter() {
        if (DEBUG) Log.v(TAG, "Enough to filter: len=" + getText().length()
                + " threshold=" + mThreshold);
        return getText().length() >= mThreshold;
    }



    /** This is used to watch for edits to the text view. */
    private class MyWatcher implements TextWatcher {
        private boolean mOpenBefore;

        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            if (mBlockCompletion) return;

            // when text is changed, inserted or deleted, we attempt to show
            // the drop down
            mOpenBefore = isPopupShowing();
            if (DEBUG) Log.v(TAG, "before text changed: open=" + mOpenBefore);
        }

        public void afterTextChanged(Editable s) {
            if (mBlockCompletion) return;

            // if the list was open before the keystroke, but closed afterwards,
            // then something in the keystroke processing (an input filter perhaps)
            // called performCompletion() and we shouldn't do any more processing.
            if (DEBUG) {
                Log.v(TAG, "after text changed: openBefore=" + mOpenBefore
                        + " open=" + isPopupShowing());
            }

            if (mOpenBefore && !isPopupShowing()) return;

            refreshAutoCompleteResults();
        }

        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }

    /**
     * This function is deprecated. Please use {@link #refreshAutoCompleteResults} instead.
     * Note: Remove {@link #mAutoCompleteTextWatcher} after removing this function.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    void doBeforeTextChanged() {
        mAutoCompleteTextWatcher.beforeTextChanged(null, 0, 0, 0);
    }

    /**
     * This function is deprecated. Please use {@link #refreshAutoCompleteResults} instead.
     * Note: Remove {@link #mAutoCompleteTextWatcher} after removing this function.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    void doAfterTextChanged() {
        mAutoCompleteTextWatcher.afterTextChanged(null);
    }

    /**
     * Refreshes the auto complete results. You usually shouldn't have to manually refresh the
     * AutoCompleteResults as this is done automatically whenever the text changes. However if the
     * results are not available and have to be fetched, you can call this function after fetching
     * the results.
     */
    public final void refreshAutoCompleteResults() {
        // the drop down is shown only when a minimum number of characters
        // was typed in the text view
        if (enoughToFilter()) {
            if (mFilter != null) {
                mPopupCanBeUpdated = true;
                performFiltering(getText(), mLastKeyCode);
            }
        } else {
            // drop down is automatically dismissed when enough characters
            // are deleted from the text view
            if (!mPopup.isDropDownAlwaysVisible()) {
                dismissDropDown();
            }
            if (mFilter != null) {
                mFilter.filter(null);
            }
        }
    }

    /**
     * <p>Indicates whether the popup menu is showing.</p>
     *
     * @return true if the popup menu is showing, false otherwise
     */
    public boolean isPopupShowing() {
        return mPopup.isShowing();
    }

    /**
     * <p>Converts the selected item from the drop down list into a sequence
     * of character that can be used in the edit box.</p>
     *
     * @param selectedItem the item selected by the user for completion
     *
     * @return a sequence of characters representing the selected suggestion
     */
    protected CharSequence convertSelectionToString(Object selectedItem) {
        return mFilter.convertResultToString(selectedItem);
    }

    /**
     * <p>Clear the list selection.  This may only be temporary, as user input will often bring
     * it back.
     */
    public void clearListSelection() {
        mPopup.clearListSelection();
    }

    /**
     * Set the position of the dropdown view selection.
     *
     * @param position The position to move the selector to.
     */
    public void setListSelection(int position) {
        mPopup.setSelection(position);
    }

    /**
     * Get the position of the dropdown view selection, if there is one.  Returns
     * {@link ListView#INVALID_POSITION ListView.INVALID_POSITION} if there is no dropdown or if
     * there is no selection.
     *
     * @return the position of the current selection, if there is one, or
     * {@link ListView#INVALID_POSITION ListView.INVALID_POSITION} if not.
     *
     * @see ListView#getSelectedItemPosition()
     */
    public int getListSelection() {
        return mPopup.getSelectedItemPosition();
    }

    /**
     * <p>Starts filtering the content of the drop down list. The filtering
     * pattern is the content of the edit box. Subclasses should override this
     * method to filter with a different pattern, for instance a substring of
     * <code>text</code>.</p>
     *
     * @param text the filtering pattern
     * @param keyCode the last character inserted in the edit box; beware that
     * this will be null when text is being added through a soft input method.
     */
    @SuppressWarnings({ "UnusedDeclaration" })
    protected void performFiltering(CharSequence text, int keyCode) {
        mFilter.filter(text, this);
    }

    /**
     * <p>Performs the text completion by converting the selected item from
     * the drop down list into a string, replacing the text box's content with
     * this string and finally dismissing the drop down menu.</p>
     */
    public void performCompletion() {
        performCompletion(null, -1, -1);
    }

    @Override
    public void onCommitCompletion(CompletionInfo completion) {
        if (isPopupShowing()) {
            mPopup.performItemClick(completion.getPosition());
        }
    }

    private void performCompletion(View selectedView, int position, long id) {
        if (isPopupShowing()) {
            Object selectedItem;
            if (position < 0) {
                selectedItem = mPopup.getSelectedItem();
            } else {
                selectedItem = mAdapter.getItem(position);
            }
            if (selectedItem == null) {
                Log.w(TAG, "performCompletion: no selected item");
                return;
            }

            mBlockCompletion = true;
            replaceText(convertSelectionToString(selectedItem));
            mBlockCompletion = false;

            if (mItemClickListener != null) {
                final ListPopupWindow list = mPopup;

                if (selectedView == null || position < 0) {
                    selectedView = list.getSelectedView();
                    position = list.getSelectedItemPosition();
                    id = list.getSelectedItemId();
                }
                mItemClickListener.onItemClick(list.getListView(), selectedView, position, id);
            }
        }

        if (mDropDownDismissedOnCompletion && !mPopup.isDropDownAlwaysVisible()) {
            dismissDropDown();
        }
    }

    /**
     * Identifies whether the view is currently performing a text completion, so subclasses
     * can decide whether to respond to text changed events.
     */
    public boolean isPerformingCompletion() {
        return mBlockCompletion;
    }

    /**
     * Like {@link #setText(CharSequence)}, except that it can disable filtering.
     *
     * @param filter If <code>false</code>, no filtering will be performed
     *        as a result of this call.
     */
    public void setText(CharSequence text, boolean filter) {
        if (filter) {
            setText(text);
        } else {
            mBlockCompletion = true;
            setText(text);
            mBlockCompletion = false;
        }
    }

    /**
     * <p>Performs the text completion by replacing the current text by the
     * selected item. Subclasses should override this method to avoid replacing
     * the whole content of the edit box.</p>
     *
     * @param text the selected suggestion in the drop down list
     */
    protected void replaceText(CharSequence text) {
        clearComposingText();

        setText(text);
        // make sure we keep the caret at the end of the text view
        Editable spannable = getText();
        Selection.setSelection(spannable, spannable.length());
    }

    /** {@inheritDoc} */
    public void onFilterComplete(int count) {
        updateDropDownForFilter(count);
    }

    private void updateDropDownForFilter(int count) {
        // Not attached to window, don't update drop-down
        if (getWindowVisibility() == View.GONE) return;

        /*
         * This checks enoughToFilter() again because filtering requests
         * are asynchronous, so the result may come back after enough text
         * has since been deleted to make it no longer appropriate
         * to filter.
         */

        final boolean dropDownAlwaysVisible = mPopup.isDropDownAlwaysVisible();
        final boolean enoughToFilter = enoughToFilter();
        if ((count > 0 || dropDownAlwaysVisible) && enoughToFilter) {
            if (hasFocus() && hasWindowFocus() && mPopupCanBeUpdated) {
                showDropDown();
            }
        } else if (!dropDownAlwaysVisible && isPopupShowing()) {
            dismissDropDown();
            // When the filter text is changed, the first update from the adapter may show an empty
            // count (when the query is being performed on the network). Future updates when some
            // content has been retrieved should still be able to update the list.
            mPopupCanBeUpdated = true;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus && !mPopup.isDropDownAlwaysVisible()) {
            dismissDropDown();
        }
    }

    @Override
    protected void onDisplayHint(int hint) {
        super.onDisplayHint(hint);
        switch (hint) {
            case INVISIBLE:
                if (!mPopup.isDropDownAlwaysVisible()) {
                    dismissDropDown();
                }
                break;
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);

        if (isTemporarilyDetached()) {
            // If we are temporarily in the detach state, then do nothing.
            return;
        }

        // Perform validation if the view is losing focus.
        if (!focused) {
            performValidation();
        }
        if (!focused && !mPopup.isDropDownAlwaysVisible()) {
            dismissDropDown();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        dismissDropDown();
        super.onDetachedFromWindow();
    }

    /**
     * <p>Closes the drop down if present on screen.</p>
     */
    public void dismissDropDown() {
        InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.displayCompletions(this, null);
        }
        mPopup.dismiss();
        mPopupCanBeUpdated = false;
    }

    @Override
    protected boolean setFrame(final int l, int t, final int r, int b) {
        boolean result = super.setFrame(l, t, r, b);

        if (isPopupShowing()) {
            showDropDown();
        }

        return result;
    }

    /**
     * Issues a runnable to show the dropdown as soon as possible.
     *
     * @hide internal used only by SearchDialog
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void showDropDownAfterLayout() {
        mPopup.postShow();
    }

    /**
     * Ensures that the drop down is not obscuring the IME.
     * @param visible whether the ime should be in front. If false, the ime is pushed to
     * the background.
     *
     * This method is deprecated. Please use the following methods instead.
     * Use {@link #setInputMethodMode} to ensure that the drop down is not obscuring the IME.
     * Use {@link #showDropDown()} to show the drop down immediately
     * A combination of {@link #isDropDownAlwaysVisible()} and {@link #enoughToFilter()} to decide
     * whether to manually trigger {@link #showDropDown()} or not.
     *
     * @hide internal used only here and SearchDialog
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 123768913)
    public void ensureImeVisible(boolean visible) {
        mPopup.setInputMethodMode(visible
                ? ListPopupWindow.INPUT_METHOD_NEEDED : ListPopupWindow.INPUT_METHOD_NOT_NEEDED);
        if (mPopup.isDropDownAlwaysVisible() || (mFilter != null && enoughToFilter())) {
            showDropDown();
        }
    }

    /**
     * This method is deprecated. Please use {@link #getInputMethodMode()} instead.
     *
     * @hide This API is not being used and can be removed.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public boolean isInputMethodNotNeeded() {
        return mPopup.getInputMethodMode() == ListPopupWindow.INPUT_METHOD_NOT_NEEDED;
    }

    /**
     * The valid input method modes for the {@link AutoCompleteTextView}:
     *
     * {@hide}
     */
    @IntDef({ListPopupWindow.INPUT_METHOD_FROM_FOCUSABLE,
            ListPopupWindow.INPUT_METHOD_NEEDED,
            ListPopupWindow.INPUT_METHOD_NOT_NEEDED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface InputMethodMode {}

    /**
     * Returns the input method mode used by the auto complete dropdown.
     */
    public @InputMethodMode int getInputMethodMode() {
        return mPopup.getInputMethodMode();
    }

    /**
     * Use this method to specify when the IME should be displayed. This function can be used to
     * prevent the dropdown from obscuring the IME.
     *
     * @param mode speficies the input method mode. use one of the following values:
     *
     * {@link ListPopupWindow#INPUT_METHOD_FROM_FOCUSABLE} IME Displayed if the auto-complete box is
     * focusable.
     * {@link ListPopupWindow#INPUT_METHOD_NEEDED} Always display the IME.
     * {@link ListPopupWindow#INPUT_METHOD_NOT_NEEDED}. The auto-complete suggestions are always
     * displayed, even if the suggestions cover/hide the input method.
     */
    public void setInputMethodMode(@InputMethodMode int mode) {
        mPopup.setInputMethodMode(mode);
    }

    /**
     * <p>Displays the drop down on screen.</p>
     */
    public void showDropDown() {
        buildImeCompletions();

        if (mPopup.getAnchorView() == null) {
            if (mDropDownAnchorId != View.NO_ID) {
                mPopup.setAnchorView(getRootView().findViewById(mDropDownAnchorId));
            } else {
                mPopup.setAnchorView(this);
            }
        }
        if (!isPopupShowing()) {
            // Make sure the list does not obscure the IME when shown for the first time.
            mPopup.setInputMethodMode(ListPopupWindow.INPUT_METHOD_NEEDED);
            mPopup.setListItemExpandMax(EXPAND_MAX);
        }
        mPopup.show();
        if (!mPopup.isDropDownAlwaysVisible()) {
            registerOnBackInvokedCallback();
        }
        mPopup.getListView().setOverScrollMode(View.OVER_SCROLL_ALWAYS);
    }

    /**
     * Forces outside touches to be ignored. Normally if {@link #isDropDownAlwaysVisible()} is
     * false, we allow outside touch to dismiss the dropdown. If this is set to true, then we
     * ignore outside touch even when the drop down is not set to always visible.
     *
     * @hide used only by SearchDialog
     */
    @UnsupportedAppUsage
    public void setForceIgnoreOutsideTouch(boolean forceIgnoreOutsideTouch) {
        mPopup.setForceIgnoreOutsideTouch(forceIgnoreOutsideTouch);
    }

    private void buildImeCompletions() {
        final ListAdapter adapter = mAdapter;
        if (adapter != null) {
            InputMethodManager imm = getContext().getSystemService(InputMethodManager.class);
            if (imm != null) {
                final int count = Math.min(adapter.getCount(), 20);
                CompletionInfo[] completions = new CompletionInfo[count];
                int realCount = 0;

                for (int i = 0; i < count; i++) {
                    if (adapter.isEnabled(i)) {
                        Object item = adapter.getItem(i);
                        long id = adapter.getItemId(i);
                        completions[realCount] = new CompletionInfo(id, realCount,
                                convertSelectionToString(item));
                        realCount++;
                    }
                }

                if (realCount != count) {
                    CompletionInfo[] tmp = new CompletionInfo[realCount];
                    System.arraycopy(completions, 0, tmp, 0, realCount);
                    completions = tmp;
                }

                imm.displayCompletions(this, completions);
            }
        }
    }

    /**
     * Sets the validator used to perform text validation.
     *
     * @param validator The validator used to validate the text entered in this widget.
     *
     * @see #getValidator()
     * @see #performValidation()
     */
    public void setValidator(Validator validator) {
        mValidator = validator;
    }

    /**
     * Returns the Validator set with {@link #setValidator},
     * or <code>null</code> if it was not set.
     *
     * @see #setValidator(android.widget.AutoCompleteTextView.Validator)
     * @see #performValidation()
     */
    public Validator getValidator() {
        return mValidator;
    }

    /**
     * If a validator was set on this view and the current string is not valid,
     * ask the validator to fix it.
     *
     * @see #getValidator()
     * @see #setValidator(android.widget.AutoCompleteTextView.Validator)
     */
    public void performValidation() {
        if (mValidator == null) return;

        CharSequence text = getText();

        if (!TextUtils.isEmpty(text) && !mValidator.isValid(text)) {
            setText(mValidator.fixText(text));
        }
    }

    /**
     * Returns the Filter obtained from {@link Filterable#getFilter},
     * or <code>null</code> if {@link #setAdapter} was not called with
     * a Filterable.
     */
    protected Filter getFilter() {
        return mFilter;
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return AutoCompleteTextView.class.getName();
    }

    private void unregisterOnBackInvokedCallback() {
        if (!mBackCallbackRegistered) {
            return;
        }
        OnBackInvokedDispatcher dispatcher = findOnBackInvokedDispatcher();
        if (dispatcher == null) {
            return;
        }
        if (WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(mPopupContext)) {
            dispatcher.unregisterOnBackInvokedCallback(mBackCallback);
        }
        mBackCallbackRegistered = false;
    }

    private void registerOnBackInvokedCallback() {
        if (mBackCallbackRegistered) {
            return;
        }
        OnBackInvokedDispatcher dispatcher =  findOnBackInvokedDispatcher();
        if (dispatcher == null) {
            return;
        }
        if (WindowOnBackInvokedDispatcher.isOnBackInvokedCallbackEnabled(mPopupContext)) {
            dispatcher.registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_OVERLAY, mBackCallback);
        }
        mBackCallbackRegistered = true;
    }

    private class DropDownItemClickListener implements AdapterView.OnItemClickListener {
        public void onItemClick(AdapterView parent, View v, int position, long id) {
            performCompletion(v, position, id);
        }
    }

    /**
     * This interface is used to make sure that the text entered in this TextView complies to
     * a certain format.  Since there is no foolproof way to prevent the user from leaving
     * this View with an incorrect value in it, all we can do is try to fix it ourselves
     * when this happens.
     */
    public interface Validator {
        /**
         * Validates the specified text.
         *
         * @return true If the text currently in the text editor is valid.
         *
         * @see #fixText(CharSequence)
         */
        boolean isValid(CharSequence text);

        /**
         * Corrects the specified text to make it valid.
         *
         * @param invalidText A string that doesn't pass validation: isValid(invalidText)
         *        returns false
         *
         * @return A string based on invalidText such as invoking isValid() on it returns true.
         *
         * @see #isValid(CharSequence)
         */
        CharSequence fixText(CharSequence invalidText);
    }

    /**
     * Listener to respond to the AutoCompleteTextView's completion list being dismissed.
     * @see AutoCompleteTextView#setOnDismissListener(OnDismissListener)
     */
    public interface OnDismissListener {
        /**
         * This method will be invoked whenever the AutoCompleteTextView's list
         * of completion options has been dismissed and is no longer available
         * for user interaction.
         */
        void onDismiss();
    }

    /**
     * Allows us a private hook into the on click event without preventing users from setting
     * their own click listener.
     */
    private class PassThroughClickListener implements OnClickListener {

        private View.OnClickListener mWrapped;

        /** {@inheritDoc} */
        public void onClick(View v) {
            onClickImpl();

            if (mWrapped != null) mWrapped.onClick(v);
        }
    }

    /**
     * Static inner listener that keeps a WeakReference to the actual AutoCompleteTextView.
     * <p>
     * This way, if adapter has a longer life span than the View, we won't leak the View, instead
     * we will just leak a small Observer with 1 field.
     */
    private static class PopupDataSetObserver extends DataSetObserver {
        private final WeakReference<AutoCompleteTextView> mViewReference;

        private PopupDataSetObserver(AutoCompleteTextView view) {
            mViewReference = new WeakReference<AutoCompleteTextView>(view);
        }

        @Override
        public void onChanged() {
            final AutoCompleteTextView textView = mViewReference.get();
            if (textView != null && textView.mAdapter != null) {
                // If the popup is not showing already, showing it will cause
                // the list of data set observers attached to the adapter to
                // change. We can't do it from here, because we are in the middle
                // of iterating through the list of observers.
                textView.post(updateRunnable);
            }
        }

        private final Runnable updateRunnable = new Runnable() {
            @Override
            public void run() {
                final AutoCompleteTextView textView = mViewReference.get();
                if (textView == null) {
                    return;
                }
                final ListAdapter adapter = textView.mAdapter;
                if (adapter == null) {
                    return;
                }
                textView.updateDropDownForFilter(adapter.getCount());
            }
        };
    }
}
