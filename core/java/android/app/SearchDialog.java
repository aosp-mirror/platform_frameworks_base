/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.app.SuggestionsAdapter.getColumnString;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.server.search.SearchableInfo;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemSelectedListener;

import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-application-process implementation of Search Bar.  This is still controlled by the 
 * SearchManager, but it runs in the current activity's process to keep things lighter weight.
 * 
 * @hide
 */
public class SearchDialog extends Dialog implements OnItemClickListener, OnItemSelectedListener {

    // Debugging support
    private static final boolean DBG = false;
    private static final String LOG_TAG = "SearchDialog";
    private static final boolean DBG_LOG_TIMING = false;

    private static final String INSTANCE_KEY_COMPONENT = "comp";
    private static final String INSTANCE_KEY_APPDATA = "data";
    private static final String INSTANCE_KEY_GLOBALSEARCH = "glob";
    private static final String INSTANCE_KEY_DISPLAY_QUERY = "dQry";
    private static final String INSTANCE_KEY_DISPLAY_SEL_START = "sel1";
    private static final String INSTANCE_KEY_DISPLAY_SEL_END = "sel2";
    private static final String INSTANCE_KEY_SELECTED_ELEMENT = "slEl";
    private static final int INSTANCE_SELECTED_BUTTON = -2;
    private static final int INSTANCE_SELECTED_QUERY = -1;
    
    private static final int SEARCH_PLATE_LEFT_PADDING_GLOBAL = 12;
    private static final int SEARCH_PLATE_LEFT_PADDING_NON_GLOBAL = 7;
    
    // interaction with runtime
    private IntentFilter mCloseDialogsFilter;
    private IntentFilter mPackageFilter;
    
    // views & widgets
    private TextView mBadgeLabel;
    private ImageView mAppIcon;
    private SearchAutoComplete mSearchAutoComplete;
    private Button mGoButton;
    private ImageButton mVoiceButton;
    private View mSearchPlate;

    // interaction with searchable application
    private SearchableInfo mSearchable;
    private ComponentName mLaunchComponent;
    private Bundle mAppSearchData;
    private boolean mGlobalSearchMode;
    private Context mActivityContext;
    
    // stack of previous searchables, to support the BACK key after
    // SearchManager.INTENT_ACTION_CHANGE_SEARCH_SOURCE.
    // The top of the stack (= previous searchable) is the last element of the list,
    // since adding and removing is efficient at the end of an ArrayList.
    private ArrayList<ComponentName> mPreviousComponents;

    // For voice searching
    private Intent mVoiceWebSearchIntent;
    private Intent mVoiceAppSearchIntent;

    // support for AutoCompleteTextView suggestions display
    private SuggestionsAdapter mSuggestionsAdapter;
    
    // Whether to rewrite queries when selecting suggestions
    // TODO: This is disabled because of problems with persistent selections
    // causing non-user-initiated rewrites.
    private static final boolean REWRITE_QUERIES = false;
    
    // The query entered by the user. This is not changed when selecting a suggestion
    // that modifies the contents of the text field. But if the user then edits
    // the suggestion, the resulting string is saved.
    private String mUserQuery;
    
    // A weak map of drawables we've gotten from other packages, so we don't load them
    // more than once.
    private final WeakHashMap<String, Drawable> mOutsideDrawablesCache =
            new WeakHashMap<String, Drawable>();
    
    /**
     * Constructor - fires it up and makes it look like the search UI.
     * 
     * @param context Application Context we can use for system acess
     */
    public SearchDialog(Context context) {
        super(context, com.android.internal.R.style.Theme_SearchBar);
    }

    /**
     * We create the search dialog just once, and it stays around (hidden)
     * until activated by the user.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window theWindow = getWindow();
        theWindow.setGravity(Gravity.TOP|Gravity.FILL_HORIZONTAL);

        setContentView(com.android.internal.R.layout.search_bar);

        theWindow.setLayout(ViewGroup.LayoutParams.FILL_PARENT,
                // taking up the whole window (even when transparent) is less than ideal,
                // but necessary to show the popup window until the window manager supports
                // having windows anchored by their parent but not clipped by them.
                ViewGroup.LayoutParams.FILL_PARENT);
        WindowManager.LayoutParams lp = theWindow.getAttributes();
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;
        theWindow.setAttributes(lp);

        // get the view elements for local access
        mBadgeLabel = (TextView) findViewById(com.android.internal.R.id.search_badge);
        mSearchAutoComplete = (SearchAutoComplete)
                findViewById(com.android.internal.R.id.search_src_text);
        mAppIcon = (ImageView) findViewById(com.android.internal.R.id.search_app_icon);
        mGoButton = (Button) findViewById(com.android.internal.R.id.search_go_btn);
        mVoiceButton = (ImageButton) findViewById(com.android.internal.R.id.search_voice_btn);
        mSearchPlate = findViewById(com.android.internal.R.id.search_plate);
        
        // attach listeners
        mSearchAutoComplete.addTextChangedListener(mTextWatcher);
        mSearchAutoComplete.setOnKeyListener(mTextKeyListener);
        mSearchAutoComplete.setOnItemClickListener(this);
        mSearchAutoComplete.setOnItemSelectedListener(this);
        mGoButton.setOnClickListener(mGoButtonClickListener);
        mGoButton.setOnKeyListener(mButtonsKeyListener);
        mVoiceButton.setOnClickListener(mVoiceButtonClickListener);
        mVoiceButton.setOnKeyListener(mButtonsKeyListener);

        mSearchAutoComplete.setSearchDialog(this);
        
        // pre-hide all the extraneous elements
        mBadgeLabel.setVisibility(View.GONE);

        // Additional adjustments to make Dialog work for Search

        // Touching outside of the search dialog will dismiss it 
        setCanceledOnTouchOutside(true);
        
        // Set up broadcast filters
        mCloseDialogsFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mPackageFilter = new IntentFilter();
        mPackageFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        mPackageFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mPackageFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        mPackageFilter.addDataScheme("package");
        
        // Save voice intent for later queries/launching
        mVoiceWebSearchIntent = new Intent(RecognizerIntent.ACTION_WEB_SEARCH);
        mVoiceWebSearchIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);
        
        mVoiceAppSearchIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    }

    /**
     * Set up the search dialog
     * 
     * @return true if search dialog launched, false if not
     */
    public boolean show(String initialQuery, boolean selectInitialQuery,
            ComponentName componentName, Bundle appSearchData, boolean globalSearch) {
        if (isShowing()) {
            // race condition - already showing but not handling events yet.
            // in this case, just discard the "show" request
            return true;
        }
        
        // set up the searchable and show the dialog
        if (!show(componentName, appSearchData, globalSearch)) {
            return false;
        }
        
        // finally, load the user's initial text (which may trigger suggestions)
        setUserQuery(initialQuery);
        if (selectInitialQuery) {
            mSearchAutoComplete.selectAll();
        }
        
        return true;
    }
    
    /**
     * Sets up the search dialog and shows it.
     * 
     * @return <code>true</code> if search dialog launched
     */
    private boolean show(ComponentName componentName, Bundle appSearchData, 
            boolean globalSearch) {
        
        if (DBG) { 
            Log.d(LOG_TAG, "show(" + componentName + ", " 
                    + appSearchData + ", " + globalSearch + ")");
        }
        
        mSearchable = SearchManager.getSearchableInfo(componentName, globalSearch);
        if (mSearchable == null) {
            // unfortunately, we can't log here.  it would be logspam every time the user
            // clicks the "search" key on a non-search app
            return false;
        }
        
        mLaunchComponent = componentName;
        mAppSearchData = appSearchData;
        // Using globalSearch here is just an optimization, just calling
        // isDefaultSearchable() should always give the same result.
        mGlobalSearchMode = globalSearch || SearchManager.isDefaultSearchable(mSearchable); 
        mActivityContext = mSearchable.getActivityContext(getContext());
        
        // show the dialog. this will call onStart().
        if (!isShowing()) {
            show();
        }

        updateUI();
        
        return true;
    }
    
    @Override
    protected void onStart() {
        super.onStart();
        
        // receive broadcasts
        getContext().registerReceiver(mBroadcastReceiver, mCloseDialogsFilter);
        getContext().registerReceiver(mBroadcastReceiver, mPackageFilter);
    }

    /**
     * The search dialog is being dismissed, so handle all of the local shutdown operations.
     * 
     * This function is designed to be idempotent so that dismiss() can be safely called at any time
     * (even if already closed) and more likely to really dump any memory.  No leaks!
     */
    @Override
    public void onStop() {
        super.onStop();
        
        // TODO: Removing the listeners means that they never get called, since 
        // Dialog.dismissDialog() calls onStop() before sendDismissMessage().
        setOnCancelListener(null);
        setOnDismissListener(null);
        
        // stop receiving broadcasts (throws exception if none registered)
        try {
            getContext().unregisterReceiver(mBroadcastReceiver);
        } catch (RuntimeException e) {
            // This is OK - it just means we didn't have any registered
        }
        
        closeSuggestionsAdapter();
        
        // dump extra memory we're hanging on to
        mLaunchComponent = null;
        mAppSearchData = null;
        mSearchable = null;
        mActivityContext = null;
        mUserQuery = null;
        mPreviousComponents = null;
    }
    
    /**
     * Closes and gets rid of the suggestions adapter.
     */
    private void closeSuggestionsAdapter() {
        // remove the adapter from the autocomplete first, to avoid any updates
        // when we drop the cursor
        mSearchAutoComplete.setAdapter((SuggestionsAdapter)null);
        // close any leftover cursor
        if (mSuggestionsAdapter != null) {
            mSuggestionsAdapter.changeCursor(null);
        }
        mSuggestionsAdapter = null;
    }
    
    /**
     * Save the minimal set of data necessary to recreate the search
     * 
     * TODO: go through this and make sure that it saves everything that is needed
     * 
     * @return A bundle with the state of the dialog.
     */
    @Override
    public Bundle onSaveInstanceState() {
        Bundle bundle = new Bundle();
        
        // setup info so I can recreate this particular search       
        bundle.putParcelable(INSTANCE_KEY_COMPONENT, mLaunchComponent);
        bundle.putBundle(INSTANCE_KEY_APPDATA, mAppSearchData);
        bundle.putBoolean(INSTANCE_KEY_GLOBALSEARCH, mGlobalSearchMode);
        
        // UI state
        bundle.putString(INSTANCE_KEY_DISPLAY_QUERY, mSearchAutoComplete.getText().toString());
        bundle.putInt(INSTANCE_KEY_DISPLAY_SEL_START, mSearchAutoComplete.getSelectionStart());
        bundle.putInt(INSTANCE_KEY_DISPLAY_SEL_END, mSearchAutoComplete.getSelectionEnd());
        
        int selectedElement = INSTANCE_SELECTED_QUERY;
        if (mGoButton.isFocused()) {
            selectedElement = INSTANCE_SELECTED_BUTTON;
        } else if (mSearchAutoComplete.isPopupShowing()) {
            selectedElement = 0; // TODO mSearchTextField.getListSelection()    // 0..n
        }
        bundle.putInt(INSTANCE_KEY_SELECTED_ELEMENT, selectedElement);
        
        return bundle;
    }

    /**
     * Restore the state of the dialog from a previously saved bundle.
     * 
     * TODO: go through this and make sure that it saves everything that is saved
     *
     * @param savedInstanceState The state of the dialog previously saved by
     *     {@link #onSaveInstanceState()}.
     */
    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        // Get the launch info
        ComponentName launchComponent = savedInstanceState.getParcelable(INSTANCE_KEY_COMPONENT);
        Bundle appSearchData = savedInstanceState.getBundle(INSTANCE_KEY_APPDATA);
        boolean globalSearch = savedInstanceState.getBoolean(INSTANCE_KEY_GLOBALSEARCH);
        
        // get the UI state
        String displayQuery = savedInstanceState.getString(INSTANCE_KEY_DISPLAY_QUERY);
        int querySelStart = savedInstanceState.getInt(INSTANCE_KEY_DISPLAY_SEL_START, -1);
        int querySelEnd = savedInstanceState.getInt(INSTANCE_KEY_DISPLAY_SEL_END, -1);
        int selectedElement = savedInstanceState.getInt(INSTANCE_KEY_SELECTED_ELEMENT);
        
        // show the dialog.  skip any show/hide animation, we want to go fast.
        // send the text that actually generates the suggestions here;  we'll replace the display
        // text as necessary in a moment.
        if (!show(displayQuery, false, launchComponent, appSearchData, globalSearch)) {
            // for some reason, we couldn't re-instantiate
            return;
        }
        
        mSearchAutoComplete.setText(displayQuery);
        
        // clean up the selection state
        switch (selectedElement) {
        case INSTANCE_SELECTED_BUTTON:
            mGoButton.setEnabled(true);
            mGoButton.setFocusable(true);
            mGoButton.requestFocus();
            break;
        case INSTANCE_SELECTED_QUERY:
            if (querySelStart >= 0 && querySelEnd >= 0) {
                mSearchAutoComplete.requestFocus();
                mSearchAutoComplete.setSelection(querySelStart, querySelEnd);
            }
            break;
        default:
            // TODO: defer selecting a list element until suggestion list appears
//            mSearchAutoComplete.setListSelection(selectedElement)
            break;
        }
    }
    
    /**
     * Called after resources have changed, e.g. after screen rotation or locale change.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        if (isShowing()) {
            // Redraw (resources may have changed)
            updateSearchButton();
            updateSearchAppIcon();
            updateSearchBadge();
            updateQueryHint();
        } 
    }
    
    /**
     * Update the UI according to the info in the current value of {@link #mSearchable}.
     */
    private void updateUI() {
        if (mSearchable != null) {
            updateSearchAutoComplete();
            updateSearchButton();
            updateSearchAppIcon();
            updateSearchBadge();
            updateQueryHint();
            updateVoiceButton();
            
            // In order to properly configure the input method (if one is being used), we
            // need to let it know if we'll be providing suggestions.  Although it would be
            // difficult/expensive to know if every last detail has been configured properly, we 
            // can at least see if a suggestions provider has been configured, and use that
            // as our trigger.
            int inputType = mSearchable.getInputType();
            // We only touch this if the input type is set up for text (which it almost certainly
            // should be, in the case of search!)
            if ((inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
                // The existence of a suggestions authority is the proxy for "suggestions 
                // are available here"
                inputType &= ~InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE;
                if (mSearchable.getSuggestAuthority() != null) {
                    inputType |= InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE;
                }
            }
            mSearchAutoComplete.setInputType(inputType);
            mSearchAutoComplete.setImeOptions(mSearchable.getImeOptions());
        }
    }
    
    /**
     * Updates the auto-complete text view.
     */
    private void updateSearchAutoComplete() {
        // close any existing suggestions adapter
        closeSuggestionsAdapter();
        
        mSearchAutoComplete.setDropDownAnimationStyle(0); // no animation
        mSearchAutoComplete.setThreshold(mSearchable.getSuggestThreshold());

        if (mGlobalSearchMode) {
            mSearchAutoComplete.setDropDownAlwaysVisible(true);  // fill space until results come in
            mSearchAutoComplete.setDropDownDismissedOnCompletion(false);
        } else {
            mSearchAutoComplete.setDropDownAlwaysVisible(false);
            mSearchAutoComplete.setDropDownDismissedOnCompletion(true);
        }

        // attach the suggestions adapter, if suggestions are available
        // The existence of a suggestions authority is the proxy for "suggestions available here"
        if (mSearchable.getSuggestAuthority() != null) {
            mSuggestionsAdapter = new SuggestionsAdapter(getContext(), mSearchable, 
                    mOutsideDrawablesCache);
            mSearchAutoComplete.setAdapter(mSuggestionsAdapter);
        }
    }

    /**    
     * Update the text in the search button.  Note: This is deprecated functionality, for 
     * 1.0 compatibility only.
     */  
    private void updateSearchButton() { 
        String textLabel = null;
        Drawable iconLabel = null;
        int textId = mSearchable.getSearchButtonText(); 
        if (textId != 0) {
            textLabel = mActivityContext.getResources().getString(textId);  
        } else {
            iconLabel = getContext().getResources().
                    getDrawable(com.android.internal.R.drawable.ic_btn_search);
        }
        mGoButton.setText(textLabel);
        mGoButton.setCompoundDrawablesWithIntrinsicBounds(iconLabel, null, null, null);
    }
    
    private void updateSearchAppIcon() {
        if (mGlobalSearchMode) {
            mAppIcon.setImageResource(0);
            mAppIcon.setVisibility(View.GONE);
            mSearchPlate.setPadding(SEARCH_PLATE_LEFT_PADDING_GLOBAL,
                    mSearchPlate.getPaddingTop(),
                    mSearchPlate.getPaddingRight(),
                    mSearchPlate.getPaddingBottom());
        } else {
            PackageManager pm = getContext().getPackageManager();
            Drawable icon = null;
            try {
                ActivityInfo info = pm.getActivityInfo(mLaunchComponent, 0);
                icon = pm.getApplicationIcon(info.applicationInfo);
                if (DBG) Log.d(LOG_TAG, "Using app-specific icon");
            } catch (NameNotFoundException e) {
                icon = pm.getDefaultActivityIcon();
                Log.w(LOG_TAG, mLaunchComponent + " not found, using generic app icon");
            }
            mAppIcon.setImageDrawable(icon);
            mAppIcon.setVisibility(View.VISIBLE);
            mSearchPlate.setPadding(SEARCH_PLATE_LEFT_PADDING_NON_GLOBAL,
                    mSearchPlate.getPaddingTop(),
                    mSearchPlate.getPaddingRight(),
                    mSearchPlate.getPaddingBottom());
        }
    }

    /**
     * Setup the search "Badge" if requested by mode flags.
     */
    private void updateSearchBadge() {
        // assume both hidden
        int visibility = View.GONE;
        Drawable icon = null;
        CharSequence text = null;
        
        // optionally show one or the other.
        if (mSearchable.mBadgeIcon) {
            icon = mActivityContext.getResources().getDrawable(mSearchable.getIconId());
            visibility = View.VISIBLE;
            if (DBG) Log.d(LOG_TAG, "Using badge icon: " + mSearchable.getIconId());
        } else if (mSearchable.mBadgeLabel) {
            text = mActivityContext.getResources().getText(mSearchable.getLabelId()).toString();
            visibility = View.VISIBLE;
            if (DBG) Log.d(LOG_TAG, "Using badge label: " + mSearchable.getLabelId());
        }
        
        mBadgeLabel.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        mBadgeLabel.setText(text);
        mBadgeLabel.setVisibility(visibility);
    }

    /**
     * Update the hint in the query text field.
     */
    private void updateQueryHint() {
        if (isShowing()) {
            String hint = null;
            if (mSearchable != null) {
                int hintId = mSearchable.getHintId();
                if (hintId != 0) {
                    hint = mActivityContext.getString(hintId);
                }
            }
            mSearchAutoComplete.setHint(hint);
        }
    }

    /**
     * Update the visibility of the voice button.  There are actually two voice search modes, 
     * either of which will activate the button.
     */
    private void updateVoiceButton() {
        int visibility = View.GONE;
        if (mSearchable.getVoiceSearchEnabled()) {
            Intent testIntent = null;
            if (mSearchable.getVoiceSearchLaunchWebSearch()) {
                testIntent = mVoiceWebSearchIntent;
            } else if (mSearchable.getVoiceSearchLaunchRecognizer()) {
                testIntent = mVoiceAppSearchIntent;
            }      
            if (testIntent != null) {
                ResolveInfo ri = getContext().getPackageManager().
                        resolveActivity(testIntent, PackageManager.MATCH_DEFAULT_ONLY);
                if (ri != null) {
                    visibility = View.VISIBLE;
                }
            }
        }
        mVoiceButton.setVisibility(visibility);
    }
    
    /**
     * Listeners of various types
     */

    /**
     * {@link Dialog#onTouchEvent(MotionEvent)} will cancel the dialog only when the
     * touch is outside the window. But the window includes space for the drop-down,
     * so we also cancel on taps outside the search bar when the drop-down is not showing.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // cancel if the drop-down is not showing and the touch event was outside the search plate
        if (!mSearchAutoComplete.isPopupShowing() && isOutOfBounds(mSearchPlate, event)) {
            if (DBG) Log.d(LOG_TAG, "Pop-up not showing and outside of search plate.");
            cancel();
            return true;
        }
        // Let Dialog handle events outside the window while the pop-up is showing.
        return super.onTouchEvent(event);
    }
    
    private boolean isOutOfBounds(View v, MotionEvent event) {
        final int x = (int) event.getX();
        final int y = (int) event.getY();
        final int slop = ViewConfiguration.get(mContext).getScaledWindowTouchSlop();
        return (x < -slop) || (y < -slop)
                || (x > (v.getWidth()+slop))
                || (y > (v.getHeight()+slop));
    }
    
    /**
     * Dialog's OnKeyListener implements various search-specific functionality
     *
     * @param keyCode This is the keycode of the typed key, and is the same value as
     *        found in the KeyEvent parameter.
     * @param event The complete event record for the typed key
     *
     * @return Return true if the event was handled here, or false if not.
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (DBG) Log.d(LOG_TAG, "onKeyDown(" + keyCode + "," + event + ")");
        
        // handle back key to go back to previous searchable, etc.
        if (handleBackKey(keyCode, event)) {
            return true;
        }
        
        // search or cancel on search key
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            if (!mSearchAutoComplete.isEmpty()) {
                launchQuerySearch();
            } else {
                cancel();
            }
            return true;
        }

        // if it's an action specified by the searchable activity, launch the
        // entered query with the action key
        SearchableInfo.ActionKeyInfo actionKey = mSearchable.findActionKey(keyCode);
        if ((actionKey != null) && (actionKey.mQueryActionMsg != null)) {
            launchQuerySearch(keyCode, actionKey.mQueryActionMsg);
            return true;
        }
        
        return false;
    }
    
    /**
     * Callback to watch the textedit field for empty/non-empty
     */
    private TextWatcher mTextWatcher = new TextWatcher() {

        public void beforeTextChanged(CharSequence s, int start, int before, int after) { }

        public void onTextChanged(CharSequence s, int start,
                int before, int after) {
            if (DBG_LOG_TIMING) {
                dbgLogTiming("onTextChanged()");
            }
            updateWidgetState();
            if (!mSearchAutoComplete.isPerformingCompletion()) {
                // The user changed the query, remember it.
                mUserQuery = s == null ? "" : s.toString();
            }
        }

        public void afterTextChanged(Editable s) { }
    };

    /**
     * Enable/Disable the cancel button based on edit text state (any text?)
     */
    private void updateWidgetState() {
        // enable the button if we have one or more non-space characters
        boolean enabled = !mSearchAutoComplete.isEmpty();
        mGoButton.setEnabled(enabled);
        mGoButton.setFocusable(enabled);
    }

    /**
     * React to typing in the GO search button by refocusing to EditText. 
     * Continue typing the query.
     */
    View.OnKeyListener mButtonsKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            // guard against possible race conditions
            if (mSearchable == null) {
                return false;
            }
            
            if (!event.isSystem() && 
                    (keyCode != KeyEvent.KEYCODE_DPAD_UP) &&
                    (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) &&
                    (keyCode != KeyEvent.KEYCODE_DPAD_LEFT) &&
                    (keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) &&
                    (keyCode != KeyEvent.KEYCODE_DPAD_CENTER)) {
                // restore focus and give key to EditText ...
                if (mSearchAutoComplete.requestFocus()) {
                    return mSearchAutoComplete.dispatchKeyEvent(event);
                }
            }

            return false;
        }
    };

    /**
     * React to a click in the GO button by launching a search.
     */
    View.OnClickListener mGoButtonClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            // guard against possible race conditions
            if (mSearchable == null) {
                return;
            }
            launchQuerySearch();
        }
    };
    
    /**
     * React to a click in the voice search button.
     */
    View.OnClickListener mVoiceButtonClickListener = new View.OnClickListener() {
        public void onClick(View v) {
            // guard against possible race conditions
            if (mSearchable == null) {
                return;
            }
            try {
                if (mSearchable.getVoiceSearchLaunchWebSearch()) {
                    getContext().startActivity(mVoiceWebSearchIntent);
                } else if (mSearchable.getVoiceSearchLaunchRecognizer()) {
                    Intent appSearchIntent = createVoiceAppSearchIntent(mVoiceAppSearchIntent);
                    getContext().startActivity(appSearchIntent);
                }
            } catch (ActivityNotFoundException e) {
                // Should not happen, since we check the availability of
                // voice search before showing the button. But just in case...
                Log.w(LOG_TAG, "Could not find voice search activity");
            }
         }
    };
    
    /**
     * Create and return an Intent that can launch the voice search activity, perform a specific
     * voice transcription, and forward the results to the searchable activity.
     * 
     * @param baseIntent The voice app search intent to start from
     * @return A completely-configured intent ready to send to the voice search activity
     */
    private Intent createVoiceAppSearchIntent(Intent baseIntent) {
        // create the necessary intent to set up a search-and-forward operation
        // in the voice search system.   We have to keep the bundle separate,
        // because it becomes immutable once it enters the PendingIntent
        Intent queryIntent = new Intent(Intent.ACTION_SEARCH);
        queryIntent.setComponent(mSearchable.mSearchActivity);
        PendingIntent pending = PendingIntent.getActivity(
                getContext(), 0, queryIntent, PendingIntent.FLAG_ONE_SHOT);
        
        // Now set up the bundle that will be inserted into the pending intent
        // when it's time to do the search.  We always build it here (even if empty)
        // because the voice search activity will always need to insert "QUERY" into
        // it anyway.
        Bundle queryExtras = new Bundle();
        if (mAppSearchData != null) {
            queryExtras.putBundle(SearchManager.APP_DATA, mAppSearchData);
        }
        
        // Now build the intent to launch the voice search.  Add all necessary
        // extras to launch the voice recognizer, and then all the necessary extras
        // to forward the results to the searchable activity
        Intent voiceIntent = new Intent(baseIntent);
        
        // Add all of the configuration options supplied by the searchable's metadata
        String languageModel = RecognizerIntent.LANGUAGE_MODEL_FREE_FORM;
        String prompt = null;
        String language = null;
        int maxResults = 1;
        Resources resources = mActivityContext.getResources();
        if (mSearchable.getVoiceLanguageModeId() != 0) {
            languageModel = resources.getString(mSearchable.getVoiceLanguageModeId());
        }
        if (mSearchable.getVoicePromptTextId() != 0) {
            prompt = resources.getString(mSearchable.getVoicePromptTextId());
        }
        if (mSearchable.getVoiceLanguageId() != 0) {
            language = resources.getString(mSearchable.getVoiceLanguageId());
        }
        if (mSearchable.getVoiceMaxResults() != 0) {
            maxResults = mSearchable.getVoiceMaxResults();
        }
        voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, languageModel);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, maxResults);
        
        // Add the values that configure forwarding the results
        voiceIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT, pending);
        voiceIntent.putExtra(RecognizerIntent.EXTRA_RESULTS_PENDINGINTENT_BUNDLE, queryExtras);
        
        return voiceIntent;
    }

    /**
     * React to the user typing "enter" or other hardwired keys while typing in the search box.
     * This handles these special keys while the edit box has focus.
     */
    View.OnKeyListener mTextKeyListener = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            // guard against possible race conditions
            if (mSearchable == null) {
                return false;
            }

            if (DBG_LOG_TIMING) dbgLogTiming("doTextKey()");
            if (DBG) { 
                Log.d(LOG_TAG, "mTextListener.onKey(" + keyCode + "," + event 
                        + "), selection: " + mSearchAutoComplete.getListSelection());
            }
            
            // If a suggestion is selected, handle enter, search key, and action keys 
            // as presses on the selected suggestion
            if (mSearchAutoComplete.isPopupShowing() && 
                    mSearchAutoComplete.getListSelection() != ListView.INVALID_POSITION) {
                return onSuggestionsKey(v, keyCode, event);
            }

            // If there is text in the query box, handle enter, and action keys
            // The search key is handled by the dialog's onKeyDown(). 
            if (!mSearchAutoComplete.isEmpty()) {
                if (keyCode == KeyEvent.KEYCODE_ENTER 
                        && event.getAction() == KeyEvent.ACTION_UP) {
                    v.cancelLongPress();
                    launchQuerySearch();                    
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    SearchableInfo.ActionKeyInfo actionKey = mSearchable.findActionKey(keyCode);
                    if ((actionKey != null) && (actionKey.mQueryActionMsg != null)) {
                        launchQuerySearch(keyCode, actionKey.mQueryActionMsg);
                        return true;
                    }
                }
            }
            return false;
        }
    };
        
    /**
     * When the ACTION_CLOSE_SYSTEM_DIALOGS intent is received, we should close ourselves 
     * immediately, in order to allow a higher-priority UI to take over
     * (e.g. phone call received).
     * 
     * When a package is added, removed or changed, our current context
     * may no longer be valid.  This would only happen if a package is installed/removed exactly
     * when the search bar is open.  So for now we're just going to close the search
     * bar.  
     * Anything fancier would require some checks to see if the user's context was still valid.
     * Which would be messier.
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                cancel();
            } else if (Intent.ACTION_PACKAGE_ADDED.equals(action)
                    || Intent.ACTION_PACKAGE_REMOVED.equals(action)
                    || Intent.ACTION_PACKAGE_CHANGED.equals(action)) {
                cancel();
            }
        }
    };

    @Override
    public void cancel() {
        // We made sure the IME was displayed, so also make sure it is closed
        // when we go away.
        InputMethodManager imm = (InputMethodManager)getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    getWindow().getDecorView().getWindowToken(), 0);
        }
        
        super.cancel();
    }
    
    /**
     * React to the user typing while in the suggestions list. First, check for action
     * keys. If not handled, try refocusing regular characters into the EditText. 
     */
    private boolean onSuggestionsKey(View v, int keyCode, KeyEvent event) {
        // guard against possible race conditions (late arrival after dismiss)
        if (mSearchable == null) {
            return false;
        }
        if (mSuggestionsAdapter == null) {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (DBG_LOG_TIMING) {
                dbgLogTiming("onSuggestionsKey()");
            }
            
            // First, check for enter or search (both of which we'll treat as a "click")
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_SEARCH) {
                int position = mSearchAutoComplete.getListSelection();
                return launchSuggestion(position);
            }
            
            // Next, check for left/right moves, which we use to "return" the user to the edit view
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                // give "focus" to text editor, with cursor at the beginning if
                // left key, at end if right key
                // TODO: Reverse left/right for right-to-left languages, e.g. Arabic
                int selPoint = (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) ? 
                        0 : mSearchAutoComplete.length();
                mSearchAutoComplete.setSelection(selPoint);
                mSearchAutoComplete.setListSelection(0);
                mSearchAutoComplete.clearListSelection();
                return true;
            }
            
            // Next, check for an "up and out" move
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP 
                    && 0 == mSearchAutoComplete.getListSelection()) {
                restoreUserQuery();
                // let ACTV complete the move
                return false;
            }
            
            // Next, check for an "action key"
            SearchableInfo.ActionKeyInfo actionKey = mSearchable.findActionKey(keyCode);
            if ((actionKey != null) && 
                    ((actionKey.mSuggestActionMsg != null) || 
                     (actionKey.mSuggestActionMsgColumn != null))) {
                // launch suggestion using action key column
                int position = mSearchAutoComplete.getListSelection();
                if (position != ListView.INVALID_POSITION) {
                    Cursor c = mSuggestionsAdapter.getCursor();
                    if (c.moveToPosition(position)) {
                        final String actionMsg = getActionKeyMessage(c, actionKey);
                        if (actionMsg != null && (actionMsg.length() > 0)) {
                            return launchSuggestion(position, keyCode, actionMsg);
                        }
                    }
                }
            }
        }
        return false;
    }
    
    /**
     * Launch a search for the text in the query text field.
     */
    protected void launchQuerySearch()  {
        launchQuerySearch(KeyEvent.KEYCODE_UNKNOWN, null);
    }

    /**
     * Launch a search for the text in the query text field.
     *
     * @param actionKey The key code of the action key that was pressed,
     *        or {@link KeyEvent#KEYCODE_UNKNOWN} if none.
     * @param actionMsg The message for the action key that was pressed,
     *        or <code>null</code> if none.
     */
    protected void launchQuerySearch(int actionKey, String actionMsg)  {
        String query = mSearchAutoComplete.getText().toString();
        Intent intent = createIntent(Intent.ACTION_SEARCH, null, query, null, 
                actionKey, actionMsg);
        launchIntent(intent);
    }
    
    /**
     * Launches an intent based on a suggestion.
     * 
     * @param position The index of the suggestion to create the intent from.
     * @return true if a successful launch, false if could not (e.g. bad position).
     */
    protected boolean launchSuggestion(int position) {
        return launchSuggestion(position, KeyEvent.KEYCODE_UNKNOWN, null);
    }
    
    /**
     * Launches an intent based on a suggestion.
     * 
     * @param position The index of the suggestion to create the intent from.
     * @param actionKey The key code of the action key that was pressed,
     *        or {@link KeyEvent#KEYCODE_UNKNOWN} if none.
     * @param actionMsg The message for the action key that was pressed,
     *        or <code>null</code> if none.
     * @return true if a successful launch, false if could not (e.g. bad position).
     */
    protected boolean launchSuggestion(int position, int actionKey, String actionMsg) {
        Cursor c = mSuggestionsAdapter.getCursor();
        if ((c != null) && c.moveToPosition(position)) {
            Intent intent = createIntentFromSuggestion(c, actionKey, actionMsg);
            launchIntent(intent);
            return true;
        }
        return false;
    }
    
    /**
     * Launches an intent. Also dismisses the search dialog if not in global search mode.
     */
    private void launchIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        if (handleSpecialIntent(intent)){
            return;
        }
        if (!mGlobalSearchMode) {
            dismiss();
        }
        getContext().startActivity(intent);
    }
    
    /**
     * Handles the special intent actions declared in {@link SearchManager}.
     * 
     * @return <code>true</code> if the intent was handled.
     */
    private boolean handleSpecialIntent(Intent intent) {
        String action = intent.getAction();
        if (SearchManager.INTENT_ACTION_CHANGE_SEARCH_SOURCE.equals(action)) {
            handleChangeSourceIntent(intent);
            return true;
        } else if (SearchManager.INTENT_ACTION_CURSOR_RESPOND.equals(action)) {
            handleCursorRespondIntent(intent);
            return true;
        }
        return false;
    }
    
    /**
     * Handles SearchManager#INTENT_ACTION_CHANGE_SOURCE.
     */
    private void handleChangeSourceIntent(Intent intent) {
        Uri dataUri = intent.getData();
        if (dataUri == null) {
            Log.w(LOG_TAG, "SearchManager.INTENT_ACTION_CHANGE_SOURCE without intent data.");
            return;
        }
        ComponentName componentName = ComponentName.unflattenFromString(dataUri.toString());
        if (componentName == null) {
            Log.w(LOG_TAG, "Invalid ComponentName: " + dataUri);
            return;
        }
        if (DBG) Log.d(LOG_TAG, "Switching to " + componentName);
        
        ComponentName previous = mLaunchComponent;
        if (!show(componentName, mAppSearchData, false)) {
            Log.w(LOG_TAG, "Failed to switch to source " + componentName);
            return;
        }
        pushPreviousComponent(previous);

        String query = intent.getStringExtra(SearchManager.QUERY);
        setUserQuery(query);
    }
    
    /**
     * Handles {@link SearchManager#INTENT_ACTION_CURSOR_RESPOND}.
     */
    private void handleCursorRespondIntent(Intent intent) {
        Cursor c = mSuggestionsAdapter.getCursor();
        if (c != null) {
            c.respond(intent.getExtras());
        }
    }
    
    /**
     * Saves the previous component that was searched, so that we can go
     * back to it.
     */
    private void pushPreviousComponent(ComponentName componentName) {
        if (mPreviousComponents == null) {
            mPreviousComponents = new ArrayList<ComponentName>();
        }
        mPreviousComponents.add(componentName);
    }
    
    /**
     * Pops the previous component off the stack and returns it.
     * 
     * @return The component name, or <code>null</code> if there was
     *         no previous component.
     */
    private ComponentName popPreviousComponent() {
        if (mPreviousComponents == null) {
            return null;
        }
        int size = mPreviousComponents.size();
        if (size == 0) {
            return null;
        }
        return mPreviousComponents.remove(size - 1);
    }
    
    /**
     * Goes back to the previous component that was searched, if any.
     * 
     * @return <code>true</code> if there was a previous component that we could go back to.
     */
    private boolean backToPreviousComponent() {
        ComponentName previous = popPreviousComponent();
        if (previous == null) {
            return false;
        }
        if (!show(previous, mAppSearchData, false)) {
            Log.w(LOG_TAG, "Failed to switch to source " + previous);
            return false;
        }
        
        // must touch text to trigger suggestions
        // TODO: should this be the text as it was when the user left
        // the source that we are now going back to?
        String query = mSearchAutoComplete.getText().toString();
        setUserQuery(query);
        
        return true;
    }
    
    /**
     * When a particular suggestion has been selected, perform the various lookups required
     * to use the suggestion.  This includes checking the cursor for suggestion-specific data,
     * and/or falling back to the XML for defaults;  It also creates REST style Uri data when
     * the suggestion includes a data id.
     * 
     * @param c The suggestions cursor, moved to the row of the user's selection
     * @param actionKey The key code of the action key that was pressed,
     *        or {@link KeyEvent#KEYCODE_UNKNOWN} if none.
     * @param actionMsg The message for the action key that was pressed,
     *        or <code>null</code> if none.
     * @return An intent for the suggestion at the cursor's position.
     */
    private Intent createIntentFromSuggestion(Cursor c, int actionKey, String actionMsg) {
        try {
            // use specific action if supplied, or default action if supplied, or fixed default
            String action = getColumnString(c, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
            if (action == null) {
                action = mSearchable.getSuggestIntentAction();
            }
            if (action == null) {
                action = Intent.ACTION_SEARCH;
            }
            
            // use specific data if supplied, or default data if supplied
            String data = getColumnString(c, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            if (data == null) {
                data = mSearchable.getSuggestIntentData();
            }
            // then, if an ID was provided, append it.
            if (data != null) {
                String id = getColumnString(c, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
                if (id != null) {
                    data = data + "/" + Uri.encode(id);
                }
            }
            Uri dataUri = (data == null) ? null : Uri.parse(data);

            String extraData = getColumnString(c, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
            
            String query = getColumnString(c, SearchManager.SUGGEST_COLUMN_QUERY);

            return createIntent(action, dataUri, query, extraData, actionKey, actionMsg);
        } catch (RuntimeException e ) {
            int rowNum;
            try {                       // be really paranoid now
                rowNum = c.getPosition();
            } catch (RuntimeException e2 ) {
                rowNum = -1;
            }
            Log.w(LOG_TAG, "Search Suggestions cursor at row " + rowNum + 
                            " returned exception" + e.toString());
            return null;
        }
    }
    
    /**
     * Constructs an intent from the given information and the search dialog state.
     * 
     * @param action Intent action.
     * @param data Intent data, or <code>null</code>.
     * @param query Intent query, or <code>null</code>.
     * @param extraData Data for {@link SearchManager#EXTRA_DATA_KEY} or <code>null</code>.
     * @param actionKey The key code of the action key that was pressed,
     *        or {@link KeyEvent#KEYCODE_UNKNOWN} if none.
     * @param actionMsg The message for the action key that was pressed,
     *        or <code>null</code> if none.
     * @return The intent.
     */
    private Intent createIntent(String action, Uri data, String query, String extraData,
            int actionKey, String actionMsg) {
        // Now build the Intent
        Intent intent = new Intent(action);
        if (data != null) {
            intent.setData(data);
        }
        if (query != null) {
            intent.putExtra(SearchManager.QUERY, query);
        }
        if (extraData != null) {
            intent.putExtra(SearchManager.EXTRA_DATA_KEY, extraData);
        }
        if (mAppSearchData != null) {
            intent.putExtra(SearchManager.APP_DATA, mAppSearchData);
        }
        if (actionKey != KeyEvent.KEYCODE_UNKNOWN) {
            intent.putExtra(SearchManager.ACTION_KEY, actionKey);
            intent.putExtra(SearchManager.ACTION_MSG, actionMsg);
        }
        // attempt to enforce security requirement (no 3rd-party intents)
        intent.setComponent(mSearchable.mSearchActivity);
        return intent;
    }
    
    /**
     * For a given suggestion and a given cursor row, get the action message.  If not provided
     * by the specific row/column, also check for a single definition (for the action key).
     * 
     * @param c The cursor providing suggestions
     * @param actionKey The actionkey record being examined
     * 
     * @return Returns a string, or null if no action key message for this suggestion
     */
    private static String getActionKeyMessage(Cursor c, SearchableInfo.ActionKeyInfo actionKey) {
        String result = null;
        // check first in the cursor data, for a suggestion-specific message
        final String column = actionKey.mSuggestActionMsgColumn;
        if (column != null) {
            result = SuggestionsAdapter.getColumnString(c, column);
        }
        // If the cursor didn't give us a message, see if there's a single message defined
        // for the actionkey (for all suggestions)
        if (result == null) {
            result = actionKey.mSuggestActionMsg;
        }
        return result;
    }
        
    /**
     * Local subclass for AutoCompleteTextView.
     */
    public static class SearchAutoComplete extends AutoCompleteTextView {

        private int mThreshold;
        private SearchDialog mSearchDialog;
        
        public SearchAutoComplete(Context context) {
            super(null);
            mThreshold = getThreshold();
        }
        
        public SearchAutoComplete(Context context, AttributeSet attrs) {
            super(context, attrs);
            mThreshold = getThreshold();
        }

        public SearchAutoComplete(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
            mThreshold = getThreshold();
        }

        private void setSearchDialog(SearchDialog searchDialog) {
            mSearchDialog = searchDialog;
        }
        
        @Override
        public void setThreshold(int threshold) {
            super.setThreshold(threshold);
            mThreshold = threshold;
        }

        /**
         * Returns true if the text field is empty, or contains only whitespace.
         */
        private boolean isEmpty() {
            return TextUtils.getTrimmedLength(getText()) == 0;
        }
        
        /**
         * Clears the entered text.
         */
        private void clear() {
            setText("");
        }
        
        /**
         * We override this method to avoid replacing the query box text
         * when a suggestion is clicked.
         */
        @Override
        protected void replaceText(CharSequence text) {
        }
        
        /**
         * We override this method so that we can allow a threshold of zero, which ACTV does not.
         */
        @Override
        public boolean enoughToFilter() {
            return mThreshold <= 0 || super.enoughToFilter();
        }

        /**
         * {@link AutoCompleteTextView#onKeyPreIme(int, KeyEvent)}) dismisses the drop-down on BACK,
         * so we must override this method to modify the BACK behavior.
         */
        @Override
        public boolean onKeyPreIme(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (mSearchDialog.backToPreviousComponent()) {
                    return true;
                }
                return false; // will dismiss soft keyboard if necessary
            }
            return false;
        }
    }
    
    protected boolean handleBackKey(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (backToPreviousComponent()) {
                return true;
            }
            cancel();
            return true;
        }
        return false;
    }
    
    /**
     * Implements OnItemClickListener
     */
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (DBG) Log.d(LOG_TAG, "onItemClick() position " + position);
        launchSuggestion(position);
    }

    /** 
     * Implements OnItemSelectedListener
     */
     public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
         if (DBG) Log.d(LOG_TAG, "onItemSelected() position " + position);
         // A suggestion has been selected, rewrite the query if possible,
         // otherwise the restore the original query.
         if (REWRITE_QUERIES) {
             rewriteQueryFromSuggestion(position);
         }
     }

     /** 
      * Implements OnItemSelectedListener
      */
     public void onNothingSelected(AdapterView<?> parent) {
         if (DBG) Log.d(LOG_TAG, "onNothingSelected()");
     }
     
     /**
      * Query rewriting.
      */

     private void rewriteQueryFromSuggestion(int position) {
         Cursor c = mSuggestionsAdapter.getCursor();
         if (c == null) {
             return;
         }
         if (c.moveToPosition(position)) {
             // Get the new query from the suggestion.
             CharSequence newQuery = mSuggestionsAdapter.convertToString(c);
             if (newQuery != null) {
                 // The suggestion rewrites the query.
                 if (DBG) Log.d(LOG_TAG, "Rewriting query to '" + newQuery + "'");
                 // Update the text field, without getting new suggestions.
                 setQuery(newQuery);
             } else {
                 // The suggestion does not rewrite the query, restore the user's query.
                 if (DBG) Log.d(LOG_TAG, "Suggestion gives no rewrite, restoring user query.");
                 restoreUserQuery();
             }
         } else {
             // We got a bad position, restore the user's query.
             Log.w(LOG_TAG, "Bad suggestion position: " + position);
             restoreUserQuery();
         }
     }
     
     /** 
      * Restores the query entered by the user if needed.
      */
     private void restoreUserQuery() {
         if (DBG) Log.d(LOG_TAG, "Restoring query to '" + mUserQuery + "'");
         setQuery(mUserQuery);
     }
     
     /**
      * Sets the text in the query box, without updating the suggestions.
      */
     private void setQuery(CharSequence query) {
         mSearchAutoComplete.setText(query, false);
         if (query != null) {
             mSearchAutoComplete.setSelection(query.length());
         }
     }
     
     /**
      * Sets the text in the query box, updating the suggestions.
      */
     private void setUserQuery(String query) {
         if (query == null) {
             query = "";
         }
         mUserQuery = query;
         mSearchAutoComplete.setText(query);
         mSearchAutoComplete.setSelection(query.length());
     }

    /**
     * Debugging Support
     */

    /**
     * For debugging only, sample the millisecond clock and log it.
     * Uses AtomicLong so we can use in multiple threads
     */
    private AtomicLong mLastLogTime = new AtomicLong(SystemClock.uptimeMillis());
    private void dbgLogTiming(final String caller) {
        long millis = SystemClock.uptimeMillis();
        long oldTime = mLastLogTime.getAndSet(millis);
        long delta = millis - oldTime;
        final String report = millis + " (+" + delta + ") ticks for Search keystroke in " + caller;
        Log.d(LOG_TAG,report);
    }
}
