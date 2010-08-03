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

package android.preference;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.app.Fragment;
import android.app.ListActivity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * This is the base class for an activity to show a hierarchy of preferences
 * to the user.  Prior to {@link android.os.Build.VERSION_CODES#HONEYCOMB}
 * this class only allowed the display of a single set of preference; this
 * functionality should now be found in the new {@link PreferenceFragment}
 * class.  If you are using PreferenceActivity in its old mode, the documentation
 * there applies to the deprecated APIs here.
 *
 * <p>This activity shows one or more headers of preferences, each of with
 * is associated with a {@link PreferenceFragment} to display the preferences
 * of that header.  The actual layout and display of these associations can
 * however vary; currently there are two major approaches it may take:
 *
 * <ul>
 * <li>On a small screen it may display only the headers as a single list
 * when first launched.  Selecting one of the header items will re-launch
 * the activity with it only showing the PreferenceFragment of that header.
 * <li>On a large screen in may display both the headers and current
 * PreferenceFragment together as panes.  Selecting a header item switches
 * to showing the correct PreferenceFragment for that item.
 * </ul>
 *
 * <p>Subclasses of PreferenceActivity should implement
 * {@link #onBuildHeaders} to populate the header list with the desired
 * items.  Doing this implicitly switches the class into its new "headers
 * + fragments" mode rather than the old style of just showing a single
 * preferences list.
 *
 * <a name="SampleCode"></a>
 * <h3>Sample Code</h3>
 *
 * <p>The following sample code shows a simple preference activity that
 * has two different sets of preferences.  The implementation, consisting
 * of the activity itself as well as its two preference fragments is:</p>
 *
 * {@sample development/samples/ApiDemos/src/com/example/android/apis/preference/PreferenceWithHeaders.java
 *      activity}
 *
 * <p>The preference_headers resource describes the headers to be displayed
 * and the fragments associated with them.  It is:
 *
 * {@sample development/samples/ApiDemos/res/xml/preference_headers.xml headers}
 *
 * See {@link PreferenceFragment} for information on implementing the
 * fragments themselves.
 */
public abstract class PreferenceActivity extends ListActivity implements
        PreferenceManager.OnPreferenceTreeClickListener {
    private static final String TAG = "PreferenceActivity";

    private static final String PREFERENCES_TAG = "android:preferences";

    private static final String EXTRA_PREFS_SHOW_FRAGMENT = ":android:show_fragment";

    private static final String EXTRA_PREFS_NO_HEADERS = ":android:no_headers";

    // extras that allow any preference activity to be launched as part of a wizard

    // show Back and Next buttons? takes boolean parameter
    // Back will then return RESULT_CANCELED and Next RESULT_OK
    private static final String EXTRA_PREFS_SHOW_BUTTON_BAR = "extra_prefs_show_button_bar";

    // specify custom text for the Back or Next buttons, or cause a button to not appear
    // at all by setting it to null
    private static final String EXTRA_PREFS_SET_NEXT_TEXT = "extra_prefs_set_next_text";
    private static final String EXTRA_PREFS_SET_BACK_TEXT = "extra_prefs_set_back_text";

    // --- State for new mode when showing a list of headers + prefs fragment

    private final ArrayList<Header> mHeaders = new ArrayList<Header>();

    private HeaderAdapter mAdapter;

    private View mPrefsContainer;

    private boolean mSinglePane;

    // --- State for old mode when showing a single preference list

    private PreferenceManager mPreferenceManager;

    private Bundle mSavedInstanceState;

    // --- Common state

    private Button mNextButton;

    /**
     * The starting request code given out to preference framework.
     */
    private static final int FIRST_REQUEST_CODE = 100;

    private static final int MSG_BIND_PREFERENCES = 0;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {

                case MSG_BIND_PREFERENCES:
                    bindPreferences();
                    break;
            }
        }
    };

    private class HeaderViewHolder {
        ImageView icon;
        TextView title;
        TextView summary;
    }

    private class HeaderAdapter extends ArrayAdapter<Header> {
        private LayoutInflater mInflater;

        public HeaderAdapter(Context context, List<Header> objects) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HeaderViewHolder holder;
            View view;

            if (convertView == null) {
                view = mInflater.inflate(com.android.internal.R.layout.preference_list_item,
                        parent, false);
                holder = new HeaderViewHolder();
                holder.icon = (ImageView)view.findViewById(
                        com.android.internal.R.id.icon);
                holder.title = (TextView)view.findViewById(
                        com.android.internal.R.id.title);
                holder.summary = (TextView)view.findViewById(
                        com.android.internal.R.id.summary);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (HeaderViewHolder)view.getTag();
            }

            Header header = getItem(position);
            if (header.icon != null) holder.icon.setImageDrawable(header.icon);
            else if (header.iconRes != 0) holder.icon.setImageResource(header.iconRes);
            if (header.title != null) holder.title.setText(header.title);
            if (header.summary != null) holder.summary.setText(header.summary);

            return view;
        }
    }

    /**
     * Description of a single Header item that the user can select.
     */
    public static class Header {
        /**
         * Title of the header that is shown to the user.
         */
        CharSequence title;

        /**
         * Optional summary describing what this header controls.
         */
        CharSequence summary;

        /**
         * Optional icon resource to show for this header.
         */
        int iconRes;

        /**
         * Optional icon drawable to show for this header.  (If this is non-null,
         * the iconRes will be ignored.)
         */
        Drawable icon;

        /**
         * Full class name of the fragment to display when this header is
         * selected.
         */
        String fragment;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(com.android.internal.R.layout.preference_list_content);

        mPrefsContainer = findViewById(com.android.internal.R.id.prefs);
        boolean hidingHeaders = onIsHidingHeaders();
        mSinglePane = hidingHeaders || !onIsMultiPane();
        String initialFragment = getIntent().getStringExtra(EXTRA_PREFS_SHOW_FRAGMENT);

        if (initialFragment != null && mSinglePane) {
            // If we are just showing a fragment, we want to run in
            // new fragment mode, but don't need to compute and show
            // the headers.
            getListView().setVisibility(View.GONE);
            mPrefsContainer.setVisibility(View.VISIBLE);
            switchToHeader(initialFragment);

        } else {
            // We need to try to build the headers.
            onBuildHeaders(mHeaders);

            // If there are headers, then at this point we need to show
            // them and, depending on the screen, we may also show in-line
            // the currently selected preference fragment.
            if (mHeaders.size() > 0) {
                mAdapter = new HeaderAdapter(this, mHeaders);
                setListAdapter(mAdapter);
                if (!mSinglePane) {
                    mPrefsContainer.setVisibility(View.VISIBLE);
                    switchToHeader(initialFragment != null
                            ? initialFragment : onGetInitialFragment());
                }

            // If there are no headers, we are in the old "just show a screen
            // of preferences" mode.
            } else {
                mPreferenceManager = new PreferenceManager(this, FIRST_REQUEST_CODE);
                mPreferenceManager.setOnPreferenceTreeClickListener(this);
            }
        }

        getListView().setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);

        // see if we should show Back/Next buttons
        Intent intent = getIntent();
        if (intent.getBooleanExtra(EXTRA_PREFS_SHOW_BUTTON_BAR, false)) {

            findViewById(com.android.internal.R.id.button_bar).setVisibility(View.VISIBLE);

            Button backButton = (Button)findViewById(com.android.internal.R.id.back_button);
            backButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    setResult(RESULT_CANCELED);
                    finish();
                }
            });
            mNextButton = (Button)findViewById(com.android.internal.R.id.next_button);
            mNextButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    setResult(RESULT_OK);
                    finish();
                }
            });

            // set our various button parameters
            if (intent.hasExtra(EXTRA_PREFS_SET_NEXT_TEXT)) {
                String buttonText = intent.getStringExtra(EXTRA_PREFS_SET_NEXT_TEXT);
                if (TextUtils.isEmpty(buttonText)) {
                    mNextButton.setVisibility(View.GONE);
                }
                else {
                    mNextButton.setText(buttonText);
                }
            }
            if (intent.hasExtra(EXTRA_PREFS_SET_BACK_TEXT)) {
                String buttonText = intent.getStringExtra(EXTRA_PREFS_SET_BACK_TEXT);
                if (TextUtils.isEmpty(buttonText)) {
                    backButton.setVisibility(View.GONE);
                }
                else {
                    backButton.setText(buttonText);
                }
            }
        }
    }

    /**
     * Called to determine if the activity should run in multi-pane mode.
     * The default implementation returns true if the screen is large
     * enough.
     */
    public boolean onIsMultiPane() {
        Configuration config = getResources().getConfiguration();
        if ((config.screenLayout&Configuration.SCREENLAYOUT_SIZE_MASK)
                == Configuration.SCREENLAYOUT_SIZE_XLARGE
                && config.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return true;
        }
        return false;
    }

    /**
     * Called to determine whether the header list should be hidden.  The
     * default implementation hides the list if the activity is being re-launched
     * when not in multi-pane mode.
     */
    public boolean onIsHidingHeaders() {
        return getIntent().getBooleanExtra(EXTRA_PREFS_NO_HEADERS, false);
    }

    /**
     * Called to determine the initial fragment to be shown.  The default
     * implementation simply returns the fragment of the first header.
     */
    public String onGetInitialFragment() {
        return mHeaders.get(0).fragment;
    }

    /**
     * Called when the activity needs its list of headers build.  By
     * implementing this and adding at least one item to the list, you
     * will cause the activity to run in its modern fragment mode.  Note
     * that this function may not always be called; for example, if the
     * activity has been asked to display a particular fragment without
     * the header list, there is no need to build the headers.
     *
     * <p>Typical implementations will use {@link #loadHeadersFromResource}
     * to fill in the list from a resource.
     *
     * @param target The list in which to place the headers.
     */
    public void onBuildHeaders(List<Header> target) {
    }

    /**
     * Parse the given XML file as a header description, adding each
     * parsed Header into the target list.
     *
     * @param resid The XML resource to load and parse.
     * @param target The list in which the parsed headers should be placed.
     */
    public void loadHeadersFromResource(int resid, List<Header> target) {
        XmlResourceParser parser = null;
        try {
            parser = getResources().getXml(resid);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
            }

            String nodeName = parser.getName();
            if (!"PreferenceHeaders".equals(nodeName)) {
                throw new RuntimeException(
                        "XML document must start with <PreferenceHeaders> tag; found"
                        + nodeName + " at " + parser.getPositionDescription());
            }

            int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                   && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                nodeName = parser.getName();
                if ("Header".equals(nodeName)) {
                    Header header = new Header();

                    TypedArray sa = getResources().obtainAttributes(attrs,
                            com.android.internal.R.styleable.PreferenceHeader);
                    header.title = sa.getText(
                            com.android.internal.R.styleable.PreferenceHeader_title);
                    header.summary = sa.getText(
                            com.android.internal.R.styleable.PreferenceHeader_summary);
                    header.iconRes = sa.getResourceId(
                            com.android.internal.R.styleable.PreferenceHeader_icon, 0);
                    header.fragment = sa.getString(
                            com.android.internal.R.styleable.PreferenceHeader_fragment);
                    sa.recycle();

                    target.add(header);

                    XmlUtils.skipCurrentTag(parser);
                } else {
                    XmlUtils.skipCurrentTag(parser);
                }
            }

        } catch (XmlPullParserException e) {
            throw new RuntimeException("Error parsing headers", e);
        } catch (IOException e) {
            throw new RuntimeException("Error parsing headers", e);
        } finally {
            if (parser != null) parser.close();
        }

    }

    @Override
    protected void onStop() {
        super.onStop();

        if (mPreferenceManager != null) {
            mPreferenceManager.dispatchActivityStop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mPreferenceManager != null) {
            mPreferenceManager.dispatchActivityDestroy();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mPreferenceManager != null) {
            final PreferenceScreen preferenceScreen = getPreferenceScreen();
            if (preferenceScreen != null) {
                Bundle container = new Bundle();
                preferenceScreen.saveHierarchyState(container);
                outState.putBundle(PREFERENCES_TAG, container);
            }
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        if (mPreferenceManager != null) {
            Bundle container = state.getBundle(PREFERENCES_TAG);
            if (container != null) {
                final PreferenceScreen preferenceScreen = getPreferenceScreen();
                if (preferenceScreen != null) {
                    preferenceScreen.restoreHierarchyState(container);
                    mSavedInstanceState = state;
                    return;
                }
            }
        }

        // Only call this if we didn't save the instance state for later.
        // If we did save it, it will be restored when we bind the adapter.
        super.onRestoreInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (mPreferenceManager != null) {
            mPreferenceManager.dispatchActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();

        if (mPreferenceManager != null) {
            postBindPreferences();
        }
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        super.onListItemClick(l, v, position, id);

        if (mAdapter != null) {
            onHeaderClick(mHeaders.get(position), position);
        }
    }

    /**
     * Called when the user selects an item in the header list.  The default
     * implementation will call either {@link #startWithFragment(String)}
     * or {@link #switchToHeader(String)} as appropriate.
     *
     * @param header The header that was selected.
     * @param position The header's position in the list.
     */
    public void onHeaderClick(Header header, int position) {
        if (mSinglePane) {
            startWithFragment(header.fragment);
        } else {
            switchToHeader(header.fragment);
        }
    }

    /**
     * Start a new instance of this activity, showing only the given
     * preference fragment.  When launched in this mode, the header list
     * will be hidden and the given preference fragment will be instantiated
     * and fill the entire activity.
     *
     * @param fragmentName The name of the fragment to display.
     */
    public void startWithFragment(String fragmentName) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(this, getClass());
        intent.putExtra(EXTRA_PREFS_SHOW_FRAGMENT, fragmentName);
        intent.putExtra(EXTRA_PREFS_NO_HEADERS, true);
        startActivity(intent);
    }

    /**
     * When in two-pane mode, switch the fragment pane to show the given
     * preference fragment.
     *
     * @param fragmentName The name of the fragment to display.
     */
    public void switchToHeader(String fragmentName) {
        Fragment f;
        try {
            f = Fragment.instantiate(this, fragmentName);
        } catch (Exception e) {
            Log.w(TAG, "Failure instantiating fragment " + fragmentName, e);
            return;
        }
        openFragmentTransaction().replace(com.android.internal.R.id.prefs, f).commit();
    }

    /**
     * Posts a message to bind the preferences to the list view.
     * <p>
     * Binding late is preferred as any custom preference types created in
     * {@link #onCreate(Bundle)} are able to have their views recycled.
     */
    private void postBindPreferences() {
        if (mHandler.hasMessages(MSG_BIND_PREFERENCES)) return;
        mHandler.obtainMessage(MSG_BIND_PREFERENCES).sendToTarget();
    }

    private void bindPreferences() {
        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            preferenceScreen.bind(getListView());
            if (mSavedInstanceState != null) {
                super.onRestoreInstanceState(mSavedInstanceState);
                mSavedInstanceState = null;
            }
        }
    }

    /**
     * Returns the {@link PreferenceManager} used by this activity.
     * @return The {@link PreferenceManager}.
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public PreferenceManager getPreferenceManager() {
        return mPreferenceManager;
    }

    private void requirePreferenceManager() {
        if (mPreferenceManager == null) {
            if (mAdapter == null) {
                throw new RuntimeException("This should be called after super.onCreate.");
            }
            throw new RuntimeException(
                    "Modern two-pane PreferenceActivity requires use of a PreferenceFragment");
        }
    }

    /**
     * Sets the root of the preference hierarchy that this activity is showing.
     *
     * @param preferenceScreen The root {@link PreferenceScreen} of the preference hierarchy.
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
        requirePreferenceManager();

        if (mPreferenceManager.setPreferences(preferenceScreen) && preferenceScreen != null) {
            postBindPreferences();
            CharSequence title = getPreferenceScreen().getTitle();
            // Set the title of the activity
            if (title != null) {
                setTitle(title);
            }
        }
    }

    /**
     * Gets the root of the preference hierarchy that this activity is showing.
     *
     * @return The {@link PreferenceScreen} that is the root of the preference
     *         hierarchy.
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public PreferenceScreen getPreferenceScreen() {
        if (mPreferenceManager != null) {
            return mPreferenceManager.getPreferenceScreen();
        }
        return null;
    }

    /**
     * Adds preferences from activities that match the given {@link Intent}.
     *
     * @param intent The {@link Intent} to query activities.
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public void addPreferencesFromIntent(Intent intent) {
        requirePreferenceManager();

        setPreferenceScreen(mPreferenceManager.inflateFromIntent(intent, getPreferenceScreen()));
    }

    /**
     * Inflates the given XML resource and adds the preference hierarchy to the current
     * preference hierarchy.
     *
     * @param preferencesResId The XML resource ID to inflate.
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public void addPreferencesFromResource(int preferencesResId) {
        requirePreferenceManager();

        setPreferenceScreen(mPreferenceManager.inflateFromResource(this, preferencesResId,
                getPreferenceScreen()));
    }

    /**
     * {@inheritDoc}
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        return false;
    }

    /**
     * Finds a {@link Preference} based on its key.
     *
     * @param key The key of the preference to retrieve.
     * @return The {@link Preference} with the key, or null.
     * @see PreferenceGroup#findPreference(CharSequence)
     *
     * @deprecated This function is not relevant for a modern fragment-based
     * PreferenceActivity.
     */
    @Deprecated
    public Preference findPreference(CharSequence key) {

        if (mPreferenceManager == null) {
            return null;
        }

        return mPreferenceManager.findPreference(key);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if (mPreferenceManager != null) {
            mPreferenceManager.dispatchNewIntent(intent);
        }
    }

    // give subclasses access to the Next button
    /** @hide */
    protected boolean hasNextButton() {
        return mNextButton != null;
    }
    /** @hide */
    protected Button getNextButton() {
        return mNextButton;
    }
}
