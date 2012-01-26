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


import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SearchView;
import android.widget.TextView;

/**
 * Search dialog. This is controlled by the 
 * SearchManager and runs in the current foreground process.
 * 
 * @hide
 */
public class SearchDialog extends Dialog {

    // Debugging support
    private static final boolean DBG = false;
    private static final String LOG_TAG = "SearchDialog";

    private static final String INSTANCE_KEY_COMPONENT = "comp";
    private static final String INSTANCE_KEY_APPDATA = "data";
    private static final String INSTANCE_KEY_USER_QUERY = "uQry";

    // The string used for privateImeOptions to identify to the IME that it should not show
    // a microphone button since one already exists in the search dialog.
    private static final String IME_OPTION_NO_MICROPHONE = "nm";

    private static final int SEARCH_PLATE_LEFT_PADDING_NON_GLOBAL = 7;

    // views & widgets
    private TextView mBadgeLabel;
    private ImageView mAppIcon;
    private AutoCompleteTextView mSearchAutoComplete;
    private View mSearchPlate;
    private SearchView mSearchView;
    private Drawable mWorkingSpinner;
    private View mCloseSearch;

    // interaction with searchable application
    private SearchableInfo mSearchable;
    private ComponentName mLaunchComponent;
    private Bundle mAppSearchData;
    private Context mActivityContext;

    // For voice searching
    private final Intent mVoiceWebSearchIntent;
    private final Intent mVoiceAppSearchIntent;

    // The query entered by the user. This is not changed when selecting a suggestion
    // that modifies the contents of the text field. But if the user then edits
    // the suggestion, the resulting string is saved.
    private String mUserQuery;

    // Last known IME options value for the search edit text.
    private int mSearchAutoCompleteImeOptions;

    private BroadcastReceiver mConfChangeListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                onConfigurationChanged();
            }
        }
    };

    static int resolveDialogTheme(Context context) {
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(com.android.internal.R.attr.searchDialogTheme,
                outValue, true);
        return outValue.resourceId;
    }

    /**
     * Constructor - fires it up and makes it look like the search UI.
     * 
     * @param context Application Context we can use for system acess
     */
    public SearchDialog(Context context, SearchManager searchManager) {
        super(context, resolveDialogTheme(context));

        // Save voice intent for later queries/launching
        mVoiceWebSearchIntent = new Intent(RecognizerIntent.ACTION_WEB_SEARCH);
        mVoiceWebSearchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mVoiceWebSearchIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH);

        mVoiceAppSearchIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        mVoiceAppSearchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * Create the search dialog and any resources that are used for the
     * entire lifetime of the dialog.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window theWindow = getWindow();
        WindowManager.LayoutParams lp = theWindow.getAttributes();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        // taking up the whole window (even when transparent) is less than ideal,
        // but necessary to show the popup window until the window manager supports
        // having windows anchored by their parent but not clipped by them.
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.gravity = Gravity.TOP | Gravity.FILL_HORIZONTAL;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        theWindow.setAttributes(lp);

        // Touching outside of the search dialog will dismiss it
        setCanceledOnTouchOutside(true);        
    }

    /**
     * We recreate the dialog view each time it becomes visible so as to limit
     * the scope of any problems with the contained resources.
     */
    private void createContentView() {
        setContentView(com.android.internal.R.layout.search_bar);

        // get the view elements for local access
        SearchBar searchBar = (SearchBar) findViewById(com.android.internal.R.id.search_bar);
        searchBar.setSearchDialog(this);
        mSearchView = (SearchView) findViewById(com.android.internal.R.id.search_view);
        mSearchView.setIconified(false);
        mSearchView.setOnCloseListener(mOnCloseListener);
        mSearchView.setOnQueryTextListener(mOnQueryChangeListener);
        mSearchView.setOnSuggestionListener(mOnSuggestionSelectionListener);
        mSearchView.onActionViewExpanded();

        mCloseSearch = findViewById(com.android.internal.R.id.closeButton);
        mCloseSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        // TODO: Move the badge logic to SearchView or move the badge to search_bar.xml
        mBadgeLabel = (TextView) mSearchView.findViewById(com.android.internal.R.id.search_badge);
        mSearchAutoComplete = (AutoCompleteTextView)
                mSearchView.findViewById(com.android.internal.R.id.search_src_text);
        mAppIcon = (ImageView) findViewById(com.android.internal.R.id.search_app_icon);
        mSearchPlate = mSearchView.findViewById(com.android.internal.R.id.search_plate);
        mWorkingSpinner = getContext().getResources().
                getDrawable(com.android.internal.R.drawable.search_spinner);
        // TODO: Restore the spinner for slow suggestion lookups
        // mSearchAutoComplete.setCompoundDrawablesWithIntrinsicBounds(
        //        null, null, mWorkingSpinner, null);
        setWorking(false);

        // pre-hide all the extraneous elements
        mBadgeLabel.setVisibility(View.GONE);

        // Additional adjustments to make Dialog work for Search
        mSearchAutoCompleteImeOptions = mSearchAutoComplete.getImeOptions();
    }

    /**
     * Set up the search dialog
     * 
     * @return true if search dialog launched, false if not
     */
    public boolean show(String initialQuery, boolean selectInitialQuery,
            ComponentName componentName, Bundle appSearchData) {
        boolean success = doShow(initialQuery, selectInitialQuery, componentName, appSearchData);
        if (success) {
            // Display the drop down as soon as possible instead of waiting for the rest of the
            // pending UI stuff to get done, so that things appear faster to the user.
            mSearchAutoComplete.showDropDownAfterLayout();
        }
        return success;
    }

    /**
     * Does the rest of the work required to show the search dialog. Called by
     * {@link #show(String, boolean, ComponentName, Bundle)} and
     *
     * @return true if search dialog showed, false if not
     */
    private boolean doShow(String initialQuery, boolean selectInitialQuery,
            ComponentName componentName, Bundle appSearchData) {
        // set up the searchable and show the dialog
        if (!show(componentName, appSearchData)) {
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
    private boolean show(ComponentName componentName, Bundle appSearchData) {
        
        if (DBG) { 
            Log.d(LOG_TAG, "show(" + componentName + ", " 
                    + appSearchData + ")");
        }
        
        SearchManager searchManager = (SearchManager)
                mContext.getSystemService(Context.SEARCH_SERVICE);
        // Try to get the searchable info for the provided component.
        mSearchable = searchManager.getSearchableInfo(componentName);

        if (mSearchable == null) {
            return false;
        }

        mLaunchComponent = componentName;
        mAppSearchData = appSearchData;
        mActivityContext = mSearchable.getActivityContext(getContext());

        // show the dialog. this will call onStart().
        if (!isShowing()) {
            // Recreate the search bar view every time the dialog is shown, to get rid
            // of any bad state in the AutoCompleteTextView etc
            createContentView();
            mSearchView.setSearchableInfo(mSearchable);
            mSearchView.setAppSearchData(mAppSearchData);

            show();
        }
        updateUI();

        return true;
    }

    @Override
    public void onStart() {
        super.onStart();

        // Register a listener for configuration change events.
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        getContext().registerReceiver(mConfChangeListener, filter);
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

        getContext().unregisterReceiver(mConfChangeListener);

        // dump extra memory we're hanging on to
        mLaunchComponent = null;
        mAppSearchData = null;
        mSearchable = null;
        mUserQuery = null;
    }

    /**
     * Sets the search dialog to the 'working' state, which shows a working spinner in the
     * right hand size of the text field.
     * 
     * @param working true to show spinner, false to hide spinner
     */
    public void setWorking(boolean working) {
        mWorkingSpinner.setAlpha(working ? 255 : 0);
        mWorkingSpinner.setVisible(working, false);
        mWorkingSpinner.invalidateSelf();
    }

    /**
     * Save the minimal set of data necessary to recreate the search
     * 
     * @return A bundle with the state of the dialog, or {@code null} if the search
     *         dialog is not showing.
     */
    @Override
    public Bundle onSaveInstanceState() {
        if (!isShowing()) return null;

        Bundle bundle = new Bundle();

        // setup info so I can recreate this particular search       
        bundle.putParcelable(INSTANCE_KEY_COMPONENT, mLaunchComponent);
        bundle.putBundle(INSTANCE_KEY_APPDATA, mAppSearchData);
        bundle.putString(INSTANCE_KEY_USER_QUERY, mUserQuery);

        return bundle;
    }

    /**
     * Restore the state of the dialog from a previously saved bundle.
     * 
     * @param savedInstanceState The state of the dialog previously saved by
     *     {@link #onSaveInstanceState()}.
     */
    @Override
    public void onRestoreInstanceState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;

        ComponentName launchComponent = savedInstanceState.getParcelable(INSTANCE_KEY_COMPONENT);
        Bundle appSearchData = savedInstanceState.getBundle(INSTANCE_KEY_APPDATA);
        String userQuery = savedInstanceState.getString(INSTANCE_KEY_USER_QUERY);

        // show the dialog.
        if (!doShow(userQuery, false, launchComponent, appSearchData)) {
            // for some reason, we couldn't re-instantiate
            return;
        }
    }

    /**
     * Called after resources have changed, e.g. after screen rotation or locale change.
     */
    public void onConfigurationChanged() {
        if (mSearchable != null && isShowing()) {
            // Redraw (resources may have changed)
            updateSearchAppIcon();
            updateSearchBadge();
            if (isLandscapeMode(getContext())) {
                mSearchAutoComplete.ensureImeVisible(true);
            }
        }
    }

    static boolean isLandscapeMode(Context context) {
        return context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    /**
     * Update the UI according to the info in the current value of {@link #mSearchable}.
     */
    private void updateUI() {
        if (mSearchable != null) {
            mDecor.setVisibility(View.VISIBLE);
            updateSearchAutoComplete();
            updateSearchAppIcon();
            updateSearchBadge();
            
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
            mSearchAutoCompleteImeOptions = mSearchable.getImeOptions();
            mSearchAutoComplete.setImeOptions(mSearchAutoCompleteImeOptions);
            
            // If the search dialog is going to show a voice search button, then don't let
            // the soft keyboard display a microphone button if it would have otherwise.
            if (mSearchable.getVoiceSearchEnabled()) {
                mSearchAutoComplete.setPrivateImeOptions(IME_OPTION_NO_MICROPHONE);
            } else {
                mSearchAutoComplete.setPrivateImeOptions(null);
            }
        }
    }
    
    /**
     * Updates the auto-complete text view.
     */
    private void updateSearchAutoComplete() {
        // we dismiss the entire dialog instead
        mSearchAutoComplete.setDropDownDismissedOnCompletion(false);
        mSearchAutoComplete.setForceIgnoreOutsideTouch(false);
    }
    
    private void updateSearchAppIcon() {
        PackageManager pm = getContext().getPackageManager();
        Drawable icon;
        try {
            ActivityInfo info = pm.getActivityInfo(mLaunchComponent, 0);
            icon = pm.getApplicationIcon(info.applicationInfo);
            if (DBG)
                Log.d(LOG_TAG, "Using app-specific icon");
        } catch (NameNotFoundException e) {
            icon = pm.getDefaultActivityIcon();
            Log.w(LOG_TAG, mLaunchComponent + " not found, using generic app icon");
        }
        mAppIcon.setImageDrawable(icon);
        mAppIcon.setVisibility(View.VISIBLE);
        mSearchPlate.setPadding(SEARCH_PLATE_LEFT_PADDING_NON_GLOBAL, mSearchPlate.getPaddingTop(), mSearchPlate.getPaddingRight(), mSearchPlate.getPaddingBottom());
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
        if (mSearchable.useBadgeIcon()) {
            icon = mActivityContext.getResources().getDrawable(mSearchable.getIconId());
            visibility = View.VISIBLE;
            if (DBG) Log.d(LOG_TAG, "Using badge icon: " + mSearchable.getIconId());
        } else if (mSearchable.useBadgeLabel()) {
            text = mActivityContext.getResources().getText(mSearchable.getLabelId()).toString();
            visibility = View.VISIBLE;
            if (DBG) Log.d(LOG_TAG, "Using badge label: " + mSearchable.getLabelId());
        }

        mBadgeLabel.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
        mBadgeLabel.setText(text);
        mBadgeLabel.setVisibility(visibility);
    }

    /*
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

    @Override
    public void hide() {
        if (!isShowing()) return;

        // We made sure the IME was displayed, so also make sure it is closed
        // when we go away.
        InputMethodManager imm = (InputMethodManager)getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(
                    getWindow().getDecorView().getWindowToken(), 0);
        }

        super.hide();
    }
    
    /**
     * Launch a search for the text in the query text field.
     */
    public void launchQuerySearch() {
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
    protected void launchQuerySearch(int actionKey, String actionMsg) {
        String query = mSearchAutoComplete.getText().toString();
        String action = Intent.ACTION_SEARCH;
        Intent intent = createIntent(action, null, null, query, actionKey, actionMsg);
        launchIntent(intent);
    }

    /**
     * Launches an intent, including any special intent handling.
     */
    private void launchIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        Log.d(LOG_TAG, "launching " + intent);
        try {
            // If the intent was created from a suggestion, it will always have an explicit
            // component here.
            getContext().startActivity(intent);
            // If the search switches to a different activity,
            // SearchDialogWrapper#performActivityResuming
            // will handle hiding the dialog when the next activity starts, but for
            // real in-app search, we still need to dismiss the dialog.
            dismiss();
        } catch (RuntimeException ex) {
            Log.e(LOG_TAG, "Failed launch activity: " + intent, ex);
        }
    }

    /**
     * Sets the list item selection in the AutoCompleteTextView's ListView.
     */
    public void setListSelection(int index) {
        mSearchAutoComplete.setListSelection(index);
    }

    /**
     * Constructs an intent from the given information and the search dialog state.
     * 
     * @param action Intent action.
     * @param data Intent data, or <code>null</code>.
     * @param extraData Data for {@link SearchManager#EXTRA_DATA_KEY} or <code>null</code>.
     * @param query Intent query, or <code>null</code>.
     * @param actionKey The key code of the action key that was pressed,
     *        or {@link KeyEvent#KEYCODE_UNKNOWN} if none.
     * @param actionMsg The message for the action key that was pressed,
     *        or <code>null</code> if none.
     * @param mode The search mode, one of the acceptable values for
     *             {@link SearchManager#SEARCH_MODE}, or {@code null}.
     * @return The intent.
     */
    private Intent createIntent(String action, Uri data, String extraData, String query,
            int actionKey, String actionMsg) {
        // Now build the Intent
        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // We need CLEAR_TOP to avoid reusing an old task that has other activities
        // on top of the one we want. We don't want to do this in in-app search though,
        // as it can be destructive to the activity stack.
        if (data != null) {
            intent.setData(data);
        }
        intent.putExtra(SearchManager.USER_QUERY, mUserQuery);
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
        intent.setComponent(mSearchable.getSearchActivity());
        return intent;
    }

    /**
     * The root element in the search bar layout. This is a custom view just to override
     * the handling of the back button.
     */
    public static class SearchBar extends LinearLayout {

        private SearchDialog mSearchDialog;

        public SearchBar(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public SearchBar(Context context) {
            super(context);
        }

        public void setSearchDialog(SearchDialog searchDialog) {
            mSearchDialog = searchDialog;
        }

        /**
         * Don't allow action modes in a SearchBar, it looks silly.
         */
        @Override
        public ActionMode startActionModeForChild(View child, ActionMode.Callback callback) {
            return null;
        }
    }

    private boolean isEmpty(AutoCompleteTextView actv) {
        return TextUtils.getTrimmedLength(actv.getText()) == 0;
    }

    @Override
    public void onBackPressed() {
        // If the input method is covering the search dialog completely,
        // e.g. in landscape mode with no hard keyboard, dismiss just the input method
        InputMethodManager imm = (InputMethodManager)getContext()
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && imm.isFullscreenMode() &&
                imm.hideSoftInputFromWindow(getWindow().getDecorView().getWindowToken(), 0)) {
            return;
        }
        // Close search dialog
        cancel();
    }

    private boolean onClosePressed() {
        // Dismiss the dialog if close button is pressed when there's no query text
        if (isEmpty(mSearchAutoComplete)) {
            dismiss();
            return true;
        }

        return false;
    }

    private final SearchView.OnCloseListener mOnCloseListener = new SearchView.OnCloseListener() {

        public boolean onClose() {
            return onClosePressed();
        }
    };

    private final SearchView.OnQueryTextListener mOnQueryChangeListener =
            new SearchView.OnQueryTextListener() {

        public boolean onQueryTextSubmit(String query) {
            dismiss();
            return false;
        }

        public boolean onQueryTextChange(String newText) {
            return false;
        }
    };

    private final SearchView.OnSuggestionListener mOnSuggestionSelectionListener =
            new SearchView.OnSuggestionListener() {

        public boolean onSuggestionSelect(int position) {
            return false;
        }

        public boolean onSuggestionClick(int position) {
            dismiss();
            return false;
        }
    };

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
}
