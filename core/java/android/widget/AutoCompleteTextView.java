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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.Selection;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.R;


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
 *         ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
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
 * @attr ref android.R.styleable#AutoCompleteTextView_completionHint
 * @attr ref android.R.styleable#AutoCompleteTextView_completionThreshold
 * @attr ref android.R.styleable#AutoCompleteTextView_completionHintView
 * @attr ref android.R.styleable#AutoCompleteTextView_dropDownSelector
 */
public class AutoCompleteTextView extends EditText implements Filter.FilterListener {
    private static final int HINT_VIEW_ID = 0x17;

    private CharSequence mHintText;
    private int mHintResource;

    private ListAdapter mAdapter;
    private Filter mFilter;
    private int mThreshold;

    private PopupWindow mPopup;
    private DropDownListView mDropDownList;

    private Drawable mDropDownListHighlight;

    private AdapterView.OnItemClickListener mItemClickListener;
    private AdapterView.OnItemSelectedListener mItemSelectedListener;

    private final DropDownItemClickListener mDropDownItemClickListener =
            new DropDownItemClickListener();

    private boolean mTextChanged;

    public AutoCompleteTextView(Context context) {
        this(context, null);
    }

    public AutoCompleteTextView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.autoCompleteTextViewStyle);
    }

    public AutoCompleteTextView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPopup = new PopupWindow(context, attrs, com.android.internal.R.attr.autoCompleteTextViewStyle);

        TypedArray a =
            context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.AutoCompleteTextView, defStyle, 0);

        mThreshold = a.getInt(
                R.styleable.AutoCompleteTextView_completionThreshold, 2);

        mHintText = a.getText(R.styleable.AutoCompleteTextView_completionHint);

        mDropDownListHighlight = a.getDrawable(
                R.styleable.AutoCompleteTextView_dropDownSelector);

        mHintResource = a.getResourceId(R.styleable.AutoCompleteTextView_completionHintView,
                R.layout.simple_dropdown_hint);

        a.recycle();

        setFocusable(true);
    }

    /**
     * Sets this to be single line; a separate method so
     * MultiAutoCompleteTextView can skip this.
     */
    /* package */ void finishInit() {
        setSingleLine();
    }

    /**
     * <p>Sets the optional hint text that is displayed at the bottom of the
     * the matching list.  This can be used as a cue to the user on how to
     * best use the list, or to provide extra information.</p>
     *
     * @param hint the text to be displayed to the user
     *
     * @attr ref android.R.styleable#AutoCompleteTextView_completionHint
     */
    public void setCompletionHint(CharSequence hint) {
        mHintText = hint;
    }

    /**
     * <p>Returns the number of characters the user must type before the drop
     * down list is shown.</p>
     *
     * @return the minimum number of characters to type to show the drop down
     *
     * @see #setThreshold(int)
     */
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
     */
    public AdapterView.OnItemClickListener getItemClickListener() {
        return mItemClickListener;
    }

    /**
     * <p>Returns the listener that is notified whenever the user selects an
     * item in the drop down list.</p>
     *
     * @return the item selected listener
     */
    public AdapterView.OnItemSelectedListener getItemSelectedListener() {
        return mItemSelectedListener;
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
     * @param adapter the adapter holding the auto completion data
     *
     * @see #getAdapter()
     * @see android.widget.Filterable
     * @see android.widget.ListAdapter
     */
    public <T extends ListAdapter & Filterable> void setAdapter(T adapter) {
        mAdapter = adapter;
        if (mAdapter != null) {
            //noinspection unchecked
            mFilter = ((Filterable) mAdapter).getFilter();
        } else {
            mFilter = null;
        }

        if (mDropDownList != null) {
            mDropDownList.setAdapter(mAdapter);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isPopupShowing()) {
            boolean consumed = mDropDownList.onKeyUp(keyCode, event);
            if (consumed) {
                switch (keyCode) {
                    // if the list accepts the key events and the key event
                    // was a click, the text view gets the selected item
                    // from the drop down as its content
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                        performCompletion();
                        return true;
                }
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // when the drop down is shown, we drive it directly
        if (isPopupShowing()) {
            // special case for the back key, we do not even try to send it
            // to the drop down list but instead, consume it immediately
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                dismissDropDown();
                return true;

            // the key events are forwarded to the list in the drop down view
            // note that ListView handles space but we don't want that to happen
            } else if (keyCode != KeyEvent.KEYCODE_SPACE) {
                boolean consumed = mDropDownList.onKeyDown(keyCode, event);

                if (consumed) {
                    switch (keyCode) {
                        // avoid passing the focus from the text view to the
                        // next component
                        case KeyEvent.KEYCODE_ENTER:
                        case KeyEvent.KEYCODE_DPAD_CENTER:
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                        case KeyEvent.KEYCODE_DPAD_UP:
                            return true;
                    }
                } else{
                    int index = mDropDownList.getSelectedItemPosition();
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_DPAD_UP:
                            if (index == 0) {
                                return true;
                            }
                            break;
                        // when the selection is at the bottom, we block the
                        // event to avoid going to the next focusable widget
                        case KeyEvent.KEYCODE_DPAD_DOWN:
                            Adapter adapter = mDropDownList.getAdapter();
                            if (index == adapter.getCount() - 1) {
                                return true;
                            }
                            break;
                    }
                }
            }
        } else {
            switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                performValidation();
            }
        }

        // when text is changed, inserted or deleted, we attempt to show
        // the drop down
        boolean openBefore = isPopupShowing();
        mTextChanged = false;

        boolean handled = super.onKeyDown(keyCode, event);

        // if the list was open before the keystroke, but closed afterwards,
        // then something in the keystroke processing (an input filter perhaps)
        // called performCompletion() and we shouldn't do any more processing.
        if (openBefore && !isPopupShowing()) {
            return handled;
        }

        if (mTextChanged) { // would have been set in onTextChanged()
            // the drop down is shown only when a minimum number of characters
            // was typed in the text view
            if (enoughToFilter()) {
                if (mFilter != null) {
                    performFiltering(getText(), keyCode);
                }
            } else {
                // drop down is automatically dismissed when enough characters
                // are deleted from the text view
                dismissDropDown();
                if (mFilter != null) {
                    mFilter.filter(null);
                }
            }
            return true;
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
        return getText().length() >= mThreshold;
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before,
                                 int after) {
        super.onTextChanged(text, start, before, after);
        mTextChanged = true;
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
     * <p>Starts filtering the content of the drop down list. The filtering
     * pattern is the content of the edit box. Subclasses should override this
     * method to filter with a different pattern, for instance a substring of
     * <code>text</code>.</p>
     *
     * @param text the filtering pattern
     * @param keyCode the last character inserted in the edit box
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

    private void performCompletion(View selectedView, int position, long id) {
        if (isPopupShowing()) {
            Object selectedItem;
            if (position == -1) {
                selectedItem = mDropDownList.getSelectedItem();
            } else {
                selectedItem = mAdapter.getItem(position);
            }
            replaceText(convertSelectionToString(selectedItem));

            if (mItemClickListener != null) {
                final DropDownListView list = mDropDownList;

                if (selectedView == null || position == -1) {
                    selectedView = list.getSelectedView();
                    position = list.getSelectedItemPosition();
                    id = list.getSelectedItemId();
                }
                mItemClickListener.onItemClick(list, selectedView, position, id);
            }
        }

        dismissDropDown();
    }

    /**
     * <p>Performs the text completion by replacing the current text by the
     * selected item. Subclasses should override this method to avoid replacing
     * the whole content of the edit box.</p>
     *
     * @param text the selected suggestion in the drop down list
     */
    protected void replaceText(CharSequence text) {
        setText(text);
        // make sure we keep the caret at the end of the text view
        Editable spannable = getText();
        Selection.setSelection(spannable, spannable.length());
    }

    public void onFilterComplete(int count) {
        /*
         * This checks enoughToFilter() again because filtering requests    
         * are asynchronous, so the result may come back after enough text
         * has since been deleted to make it no longer appropriate
         * to filter.
         */

        if (count > 0 && enoughToFilter()) {
            if (hasFocus() && hasWindowFocus()) {
                showDropDown();
            }
        } else {
            dismissDropDown();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        performValidation();
        if (!hasWindowFocus) {
            dismissDropDown();
        }
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
        performValidation();
        if (!focused) {
            dismissDropDown();
        }
    }

    /**
     * <p>Closes the drop down if present on screen.</p>
     */
    public void dismissDropDown() {
        mPopup.dismiss();
        if (mDropDownList != null) {
            // start next time with no selection
            mDropDownList.hideSelector();
        }
    }

    @Override
    protected boolean setFrame(int l, int t, int r, int b) {
        boolean result = super.setFrame(l, t, r, b);

        mPopup.update(this, getMeasuredWidth(), -1);

        return result;
    }

    /**
     * <p>Displays the drop down on screen.</p>
     */
    public void showDropDown() {
        int height = buildDropDown();
        if (mPopup.isShowing()) {
            mPopup.update(this, getMeasuredWidth() - mPaddingLeft - mPaddingRight, height);
        } else {
            mPopup.setHeight(height);
            mPopup.setWidth(getMeasuredWidth() - mPaddingLeft - mPaddingRight);
            mPopup.showAsDropDown(this);
        }
    }

    /**
     * <p>Builds the popup window's content and returns the height the popup
     * should have. Returns -1 when the content already exists.</p>
     *
     * @return the content's height or -1 if content already exists
     */
    private int buildDropDown() {
        ViewGroup dropDownView;
        int otherHeights = 0;

        if (mDropDownList == null) {
            Context context = getContext();

            mDropDownList = new DropDownListView(context);
            mDropDownList.setSelector(mDropDownListHighlight);
            mDropDownList.setAdapter(mAdapter);
            mDropDownList.setVerticalFadingEdgeEnabled(true);
            mDropDownList.setOnItemClickListener(mDropDownItemClickListener);

            if (mItemSelectedListener != null) {
                mDropDownList.setOnItemSelectedListener(mItemSelectedListener);
            }

            dropDownView = mDropDownList;

            View hintView = getHintView(context);
            if (hintView != null) {
                // if an hint has been specified, we accomodate more space for it and
                // add a text view in the drop down menu, at the bottom of the list
                LinearLayout hintContainer = new LinearLayout(context);
                hintContainer.setOrientation(LinearLayout.VERTICAL);

                LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.FILL_PARENT, 0, 1.0f
                );
                hintContainer.addView(dropDownView, hintParams);
                hintContainer.addView(hintView);

                // measure the hint's height to find how much more vertical space
                // we need to add to the drop down's height
                int widthSpec = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.AT_MOST);
                int heightSpec = MeasureSpec.UNSPECIFIED;
                hintView.measure(widthSpec, heightSpec);

                hintParams = (LinearLayout.LayoutParams) hintView.getLayoutParams();
                otherHeights = hintView.getMeasuredHeight() + hintParams.topMargin
                        + hintParams.bottomMargin;

                dropDownView = hintContainer;
            }

            mPopup.setContentView(dropDownView);
        } else {
            dropDownView = (ViewGroup) mPopup.getContentView();
            final View view = dropDownView.findViewById(HINT_VIEW_ID);
            if (view != null) {
                LinearLayout.LayoutParams hintParams =
                        (LinearLayout.LayoutParams) view.getLayoutParams();
                otherHeights = view.getMeasuredHeight() + hintParams.topMargin
                        + hintParams.bottomMargin;
            }
        }

        // Max height available on the screen for a popup anchored to us
        final int maxHeight = mPopup.getMaxAvailableHeight(this);
        otherHeights += dropDownView.getPaddingTop() + dropDownView.getPaddingBottom();

        return mDropDownList.measureHeightOfChildren(MeasureSpec.UNSPECIFIED,
                0, ListView.NO_POSITION, maxHeight - otherHeights, 2) + otherHeights;
    }

    private View getHintView(Context context) {
        if (mHintText != null && mHintText.length() > 0) {
            final TextView hintView = (TextView) LayoutInflater.from(context).inflate(
                    mHintResource, null).findViewById(com.android.internal.R.id.text1);
            hintView.setText(mHintText);
            hintView.setId(HINT_VIEW_ID);
            return hintView;
        } else {
            return null;
        }
    }

    private class DropDownItemClickListener implements AdapterView.OnItemClickListener {
        public void onItemClick(AdapterView parent, View v, int position, long id) {
            performCompletion(v, position, id);
        }
    }

    /**
     * <p>Wrapper class for a ListView. This wrapper hijacks the focus to
     * make sure the list uses the appropriate drawables and states when
     * displayed on screen within a drop down. The focus is never actually
     * passed to the drop down; the list only looks focused.</p>
     */
    private static class DropDownListView extends ListView {
        /**
         * <p>Creates a new list view wrapper.</p>
         *
         * @param context this view's context
         */
        public DropDownListView(Context context) {
            super(context, null, com.android.internal.R.attr.dropDownListViewStyle);
        }

        /**
         * <p>Avoids jarring scrolling effect by ensuring that list elements
         * made of a text view fit on a single line.</p>
         *
         * @param position the item index in the list to get a view for
         * @return the view for the specified item
         */
        @Override
        protected View obtainView(int position) {
            View view = super.obtainView(position);

            if (view instanceof TextView) {
                ((TextView) view).setHorizontallyScrolling(true);
            }

            return view;
        }

        /**
         * <p>Returns the top padding of the currently selected view.</p>
         *
         * @return the height of the top padding for the selection
         */
        public int getSelectionPaddingTop() {
            return mSelectionTopPadding;
        }

        /**
         * <p>Returns the bottom padding of the currently selected view.</p>
         *
         * @return the height of the bottom padding for the selection
         */
        public int getSelectionPaddingBottom() {
            return mSelectionBottomPadding;
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always
         */
        @Override
        public boolean hasWindowFocus() {
            return true;
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always
         */
        @Override
        public boolean isFocused() {
            return true;
        }

        /**
         * <p>Returns the focus state in the drop down.</p>
         *
         * @return true always
         */
        @Override
        public boolean hasFocus() {
            return true;
        }
    }

    /**
     * This interface is used to make sure that the text entered in this TextView complies to
     * a certain format.  Since there is no foolproof way to prevent the user from leaving
     * this View with an incorrect value in it, all we can do is try to fix it ourselves
     * when this happens.
     */
    static public interface Validator {
        /**
         * @return true if the text currently in the text editor is valid.
         */
        boolean isValid(CharSequence text);

        /**
         * @param invalidText a string that doesn't pass validation:
         * isValid(invalidText) returns false
         * @return a string based on invalidText such as invoking isValid() on it returns true.
         */
        CharSequence fixText(CharSequence invalidText);
    }

    private Validator mValidator = null;
    
    public void setValidator(Validator validator) {
        mValidator = validator;
    }

    /**
     * Returns the Validator set with {@link #setValidator},
     * or <code>null</code> if it was not set.
     */
    public Validator getValidator() {
        return mValidator;
    }
    
    /**
     * If a validator was set on this view and the current string is not valid,
     * ask the validator to fix it. 
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
}
