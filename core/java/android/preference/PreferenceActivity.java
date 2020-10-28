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

import android.animation.LayoutTransition;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.XmlRes;
import android.app.Fragment;
import android.app.FragmentBreadCrumbs;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.app.ListActivity;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.util.Xml;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

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
 * <p>This activity shows one or more headers of preferences, each of which
 * is associated with a {@link PreferenceFragment} to display the preferences
 * of that header.  The actual layout and display of these associations can
 * however vary; currently there are two major approaches it may take:
 *
 * <ul>
 * <li>On a small screen it may display only the headers as a single list when first launched.
 * Selecting one of the header items will only show the PreferenceFragment of that header (on
 * Android N and lower a new Activity is launched).
 * <li>On a large screen it may display both the headers and current PreferenceFragment together as
 * panes. Selecting a header item switches to showing the correct PreferenceFragment for that item.
 * </ul>
 *
 * <p>Subclasses of PreferenceActivity should implement
 * {@link #onBuildHeaders} to populate the header list with the desired
 * items.  Doing this implicitly switches the class into its new "headers
 * + fragments" mode rather than the old style of just showing a single
 * preferences list.
 *
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about using {@code PreferenceActivity},
 * read the <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>
 * guide.</p>
 * </div>
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public abstract class PreferenceActivity extends ListActivity implements
        PreferenceManager.OnPreferenceTreeClickListener,
        PreferenceFragment.OnPreferenceStartFragmentCallback {

    private static final String TAG = "PreferenceActivity";

    // Constants for state save/restore
    private static final String HEADERS_TAG = ":android:headers";
    private static final String CUR_HEADER_TAG = ":android:cur_header";
    private static final String PREFERENCES_TAG = ":android:preferences";

    /**
     * When starting this activity, the invoking Intent can contain this extra
     * string to specify which fragment should be initially displayed.
     * <p/>Starting from Key Lime Pie, when this argument is passed in, the PreferenceActivity
     * will call isValidFragment() to confirm that the fragment class name is valid for this
     * activity.
     */
    public static final String EXTRA_SHOW_FRAGMENT = ":android:show_fragment";

    /**
     * When starting this activity and using {@link #EXTRA_SHOW_FRAGMENT},
     * this extra can also be specified to supply a Bundle of arguments to pass
     * to that fragment when it is instantiated during the initial creation
     * of PreferenceActivity.
     */
    public static final String EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":android:show_fragment_args";

    /**
     * When starting this activity and using {@link #EXTRA_SHOW_FRAGMENT},
     * this extra can also be specify to supply the title to be shown for
     * that fragment.
     */
    public static final String EXTRA_SHOW_FRAGMENT_TITLE = ":android:show_fragment_title";

    /**
     * When starting this activity and using {@link #EXTRA_SHOW_FRAGMENT},
     * this extra can also be specify to supply the short title to be shown for
     * that fragment.
     */
    public static final String EXTRA_SHOW_FRAGMENT_SHORT_TITLE
            = ":android:show_fragment_short_title";

    /**
     * When starting this activity, the invoking Intent can contain this extra
     * boolean that the header list should not be displayed.  This is most often
     * used in conjunction with {@link #EXTRA_SHOW_FRAGMENT} to launch
     * the activity to display a specific fragment that the user has navigated
     * to.
     */
    public static final String EXTRA_NO_HEADERS = ":android:no_headers";

    private static final String BACK_STACK_PREFS = ":android:prefs";

    // extras that allow any preference activity to be launched as part of a wizard

    // show Back and Next buttons? takes boolean parameter
    // Back will then return RESULT_CANCELED and Next RESULT_OK
    private static final String EXTRA_PREFS_SHOW_BUTTON_BAR = "extra_prefs_show_button_bar";

    // add a Skip button?
    private static final String EXTRA_PREFS_SHOW_SKIP = "extra_prefs_show_skip";

    // specify custom text for the Back or Next buttons, or cause a button to not appear
    // at all by setting it to null
    private static final String EXTRA_PREFS_SET_NEXT_TEXT = "extra_prefs_set_next_text";
    private static final String EXTRA_PREFS_SET_BACK_TEXT = "extra_prefs_set_back_text";

    // --- State for new mode when showing a list of headers + prefs fragment

    private final ArrayList<Header> mHeaders = new ArrayList<Header>();

    private FrameLayout mListFooter;

    @UnsupportedAppUsage
    private ViewGroup mPrefsContainer;

    // Backup of the original activity title. This is used when navigating back to the headers list
    // in onBackPress to restore the title.
    private CharSequence mActivityTitle;

    // Null if in legacy mode.
    private ViewGroup mHeadersContainer;

    private FragmentBreadCrumbs mFragmentBreadCrumbs;

    private boolean mSinglePane;

    private Header mCurHeader;

    // --- State for old mode when showing a single preference list

    @UnsupportedAppUsage
    private PreferenceManager mPreferenceManager;

    private Bundle mSavedInstanceState;

    // --- Common state

    private Button mNextButton;

    private int mPreferenceHeaderItemResId = 0;
    private boolean mPreferenceHeaderRemoveEmptyIcon = false;

    /**
     * The starting request code given out to preference framework.
     */
    private static final int FIRST_REQUEST_CODE = 100;

    private static final int MSG_BIND_PREFERENCES = 1;
    private static final int MSG_BUILD_HEADERS = 2;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_BIND_PREFERENCES: {
                    bindPreferences();
                } break;
                case MSG_BUILD_HEADERS: {
                    ArrayList<Header> oldHeaders = new ArrayList<Header>(mHeaders);
                    mHeaders.clear();
                    onBuildHeaders(mHeaders);
                    if (mAdapter instanceof BaseAdapter) {
                        ((BaseAdapter) mAdapter).notifyDataSetChanged();
                    }
                    Header header = onGetNewHeader();
                    if (header != null && header.fragment != null) {
                        Header mappedHeader = findBestMatchingHeader(header, oldHeaders);
                        if (mappedHeader == null || mCurHeader != mappedHeader) {
                            switchToHeader(header);
                        }
                    } else if (mCurHeader != null) {
                        Header mappedHeader = findBestMatchingHeader(mCurHeader, mHeaders);
                        if (mappedHeader != null) {
                            setSelectedHeader(mappedHeader);
                        }
                    }
                } break;
            }
        }
    };

    private static class HeaderAdapter extends ArrayAdapter<Header> {
        private static class HeaderViewHolder {
            ImageView icon;
            TextView title;
            TextView summary;
        }

        private LayoutInflater mInflater;
        private int mLayoutResId;
        private boolean mRemoveIconIfEmpty;

        public HeaderAdapter(Context context, List<Header> objects, int layoutResId,
                boolean removeIconBehavior) {
            super(context, 0, objects);
            mInflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mLayoutResId = layoutResId;
            mRemoveIconIfEmpty = removeIconBehavior;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            HeaderViewHolder holder;
            View view;

            if (convertView == null) {
                view = mInflater.inflate(mLayoutResId, parent, false);
                holder = new HeaderViewHolder();
                holder.icon = (ImageView) view.findViewById(com.android.internal.R.id.icon);
                holder.title = (TextView) view.findViewById(com.android.internal.R.id.title);
                holder.summary = (TextView) view.findViewById(com.android.internal.R.id.summary);
                view.setTag(holder);
            } else {
                view = convertView;
                holder = (HeaderViewHolder) view.getTag();
            }

            // All view fields must be updated every time, because the view may be recycled
            Header header = getItem(position);
            if (mRemoveIconIfEmpty) {
                if (header.iconRes == 0) {
                    holder.icon.setVisibility(View.GONE);
                } else {
                    holder.icon.setVisibility(View.VISIBLE);
                    holder.icon.setImageResource(header.iconRes);
                }
            } else {
                holder.icon.setImageResource(header.iconRes);
            }
            holder.title.setText(header.getTitle(getContext().getResources()));
            CharSequence summary = header.getSummary(getContext().getResources());
            if (!TextUtils.isEmpty(summary)) {
                holder.summary.setVisibility(View.VISIBLE);
                holder.summary.setText(summary);
            } else {
                holder.summary.setVisibility(View.GONE);
            }

            return view;
        }
    }

    /**
     * Default value for {@link Header#id Header.id} indicating that no
     * identifier value is set.  All other values (including those below -1)
     * are valid.
     */
    public static final long HEADER_ID_UNDEFINED = -1;

    /**
     * Description of a single Header item that the user can select.
     *
     * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
     *      Preference Library</a> for consistent behavior across all devices.
     *      For more information on using the AndroidX Preference Library see
     *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
     */
    @Deprecated
    public static final class Header implements Parcelable {
        /**
         * Identifier for this header, to correlate with a new list when
         * it is updated.  The default value is
         * {@link PreferenceActivity#HEADER_ID_UNDEFINED}, meaning no id.
         * @attr ref android.R.styleable#PreferenceHeader_id
         */
        public long id = HEADER_ID_UNDEFINED;

        /**
         * Resource ID of title of the header that is shown to the user.
         * @attr ref android.R.styleable#PreferenceHeader_title
         */
        @StringRes
        public int titleRes;

        /**
         * Title of the header that is shown to the user.
         * @attr ref android.R.styleable#PreferenceHeader_title
         */
        public CharSequence title;

        /**
         * Resource ID of optional summary describing what this header controls.
         * @attr ref android.R.styleable#PreferenceHeader_summary
         */
        @StringRes
        public int summaryRes;

        /**
         * Optional summary describing what this header controls.
         * @attr ref android.R.styleable#PreferenceHeader_summary
         */
        public CharSequence summary;

        /**
         * Resource ID of optional text to show as the title in the bread crumb.
         * @attr ref android.R.styleable#PreferenceHeader_breadCrumbTitle
         */
        @StringRes
        public int breadCrumbTitleRes;

        /**
         * Optional text to show as the title in the bread crumb.
         * @attr ref android.R.styleable#PreferenceHeader_breadCrumbTitle
         */
        public CharSequence breadCrumbTitle;

        /**
         * Resource ID of optional text to show as the short title in the bread crumb.
         * @attr ref android.R.styleable#PreferenceHeader_breadCrumbShortTitle
         */
        @StringRes
        public int breadCrumbShortTitleRes;

        /**
         * Optional text to show as the short title in the bread crumb.
         * @attr ref android.R.styleable#PreferenceHeader_breadCrumbShortTitle
         */
        public CharSequence breadCrumbShortTitle;

        /**
         * Optional icon resource to show for this header.
         * @attr ref android.R.styleable#PreferenceHeader_icon
         */
        public int iconRes;

        /**
         * Full class name of the fragment to display when this header is
         * selected.
         * @attr ref android.R.styleable#PreferenceHeader_fragment
         */
        public String fragment;

        /**
         * Optional arguments to supply to the fragment when it is
         * instantiated.
         */
        public Bundle fragmentArguments;

        /**
         * Intent to launch when the preference is selected.
         */
        public Intent intent;

        /**
         * Optional additional data for use by subclasses of PreferenceActivity.
         */
        public Bundle extras;

        public Header() {
            // Empty
        }

        /**
         * Return the currently set title.  If {@link #titleRes} is set,
         * this resource is loaded from <var>res</var> and returned.  Otherwise
         * {@link #title} is returned.
         */
        public CharSequence getTitle(Resources res) {
            if (titleRes != 0) {
                return res.getText(titleRes);
            }
            return title;
        }

        /**
         * Return the currently set summary.  If {@link #summaryRes} is set,
         * this resource is loaded from <var>res</var> and returned.  Otherwise
         * {@link #summary} is returned.
         */
        public CharSequence getSummary(Resources res) {
            if (summaryRes != 0) {
                return res.getText(summaryRes);
            }
            return summary;
        }

        /**
         * Return the currently set bread crumb title.  If {@link #breadCrumbTitleRes} is set,
         * this resource is loaded from <var>res</var> and returned.  Otherwise
         * {@link #breadCrumbTitle} is returned.
         */
        public CharSequence getBreadCrumbTitle(Resources res) {
            if (breadCrumbTitleRes != 0) {
                return res.getText(breadCrumbTitleRes);
            }
            return breadCrumbTitle;
        }

        /**
         * Return the currently set bread crumb short title.  If
         * {@link #breadCrumbShortTitleRes} is set,
         * this resource is loaded from <var>res</var> and returned.  Otherwise
         * {@link #breadCrumbShortTitle} is returned.
         */
        public CharSequence getBreadCrumbShortTitle(Resources res) {
            if (breadCrumbShortTitleRes != 0) {
                return res.getText(breadCrumbShortTitleRes);
            }
            return breadCrumbShortTitle;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(id);
            dest.writeInt(titleRes);
            TextUtils.writeToParcel(title, dest, flags);
            dest.writeInt(summaryRes);
            TextUtils.writeToParcel(summary, dest, flags);
            dest.writeInt(breadCrumbTitleRes);
            TextUtils.writeToParcel(breadCrumbTitle, dest, flags);
            dest.writeInt(breadCrumbShortTitleRes);
            TextUtils.writeToParcel(breadCrumbShortTitle, dest, flags);
            dest.writeInt(iconRes);
            dest.writeString(fragment);
            dest.writeBundle(fragmentArguments);
            if (intent != null) {
                dest.writeInt(1);
                intent.writeToParcel(dest, flags);
            } else {
                dest.writeInt(0);
            }
            dest.writeBundle(extras);
        }

        public void readFromParcel(Parcel in) {
            id = in.readLong();
            titleRes = in.readInt();
            title = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            summaryRes = in.readInt();
            summary = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            breadCrumbTitleRes = in.readInt();
            breadCrumbTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            breadCrumbShortTitleRes = in.readInt();
            breadCrumbShortTitle = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
            iconRes = in.readInt();
            fragment = in.readString();
            fragmentArguments = in.readBundle();
            if (in.readInt() != 0) {
                intent = Intent.CREATOR.createFromParcel(in);
            }
            extras = in.readBundle();
        }

        Header(Parcel in) {
            readFromParcel(in);
        }

        public static final @android.annotation.NonNull Creator<Header> CREATOR = new Creator<Header>() {
            public Header createFromParcel(Parcel source) {
                return new Header(source);
            }
            public Header[] newArray(int size) {
                return new Header[size];
            }
        };
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            // Override home navigation button to call onBackPressed (b/35152749).
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Theming for the PreferenceActivity layout and for the Preference Header(s) layout
        TypedArray sa = obtainStyledAttributes(null,
                com.android.internal.R.styleable.PreferenceActivity,
                com.android.internal.R.attr.preferenceActivityStyle,
                0);

        final int layoutResId = sa.getResourceId(
                com.android.internal.R.styleable.PreferenceActivity_layout,
                com.android.internal.R.layout.preference_list_content);

        mPreferenceHeaderItemResId = sa.getResourceId(
                com.android.internal.R.styleable.PreferenceActivity_headerLayout,
                com.android.internal.R.layout.preference_header_item);
        mPreferenceHeaderRemoveEmptyIcon = sa.getBoolean(
                com.android.internal.R.styleable.PreferenceActivity_headerRemoveIconIfEmpty,
                false);

        sa.recycle();

        setContentView(layoutResId);

        mListFooter = (FrameLayout)findViewById(com.android.internal.R.id.list_footer);
        mPrefsContainer = (ViewGroup) findViewById(com.android.internal.R.id.prefs_frame);
        mHeadersContainer = (ViewGroup) findViewById(com.android.internal.R.id.headers);
        boolean hidingHeaders = onIsHidingHeaders();
        mSinglePane = hidingHeaders || !onIsMultiPane();
        String initialFragment = getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT);
        Bundle initialArguments = getIntent().getBundleExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS);
        int initialTitle = getIntent().getIntExtra(EXTRA_SHOW_FRAGMENT_TITLE, 0);
        int initialShortTitle = getIntent().getIntExtra(EXTRA_SHOW_FRAGMENT_SHORT_TITLE, 0);
        mActivityTitle = getTitle();

        if (savedInstanceState != null) {
            // We are restarting from a previous saved state; used that to
            // initialize, instead of starting fresh.
            ArrayList<Header> headers = savedInstanceState.getParcelableArrayList(HEADERS_TAG);
            if (headers != null) {
                mHeaders.addAll(headers);
                int curHeader = savedInstanceState.getInt(CUR_HEADER_TAG,
                        (int) HEADER_ID_UNDEFINED);
                if (curHeader >= 0 && curHeader < mHeaders.size()) {
                    setSelectedHeader(mHeaders.get(curHeader));
                } else if (!mSinglePane && initialFragment == null) {
                    switchToHeader(onGetInitialHeader());
                }
            } else {
                // This will for instance hide breadcrumbs for single pane.
                showBreadCrumbs(getTitle(), null);
            }
        } else {
            if (!onIsHidingHeaders()) {
                onBuildHeaders(mHeaders);
            }

            if (initialFragment != null) {
                switchToHeader(initialFragment, initialArguments);
            } else if (!mSinglePane && mHeaders.size() > 0) {
                switchToHeader(onGetInitialHeader());
            }
        }

        if (mHeaders.size() > 0) {
            setListAdapter(new HeaderAdapter(this, mHeaders, mPreferenceHeaderItemResId,
                    mPreferenceHeaderRemoveEmptyIcon));
            if (!mSinglePane) {
                getListView().setChoiceMode(AbsListView.CHOICE_MODE_SINGLE);
            }
        }

        if (mSinglePane && initialFragment != null && initialTitle != 0) {
            CharSequence initialTitleStr = getText(initialTitle);
            CharSequence initialShortTitleStr = initialShortTitle != 0
                    ? getText(initialShortTitle) : null;
            showBreadCrumbs(initialTitleStr, initialShortTitleStr);
        }

        if (mHeaders.size() == 0 && initialFragment == null) {
            // If there are no headers, we are in the old "just show a screen
            // of preferences" mode.
            setContentView(com.android.internal.R.layout.preference_list_content_single);
            mListFooter = (FrameLayout) findViewById(com.android.internal.R.id.list_footer);
            mPrefsContainer = (ViewGroup) findViewById(com.android.internal.R.id.prefs);
            mPreferenceManager = new PreferenceManager(this, FIRST_REQUEST_CODE);
            mPreferenceManager.setOnPreferenceTreeClickListener(this);
            mHeadersContainer = null;
        } else if (mSinglePane) {
            // Single-pane so one of the header or prefs containers must be hidden.
            if (initialFragment != null || mCurHeader != null) {
                mHeadersContainer.setVisibility(View.GONE);
            } else {
                mPrefsContainer.setVisibility(View.GONE);
            }

            // This animates our manual transitions between headers and prefs panel in single-pane.
            // It also comes last so we don't animate any initial layout changes done above.
            ViewGroup container = (ViewGroup) findViewById(
                    com.android.internal.R.id.prefs_container);
            container.setLayoutTransition(new LayoutTransition());
        } else {
            // Multi-pane
            if (mHeaders.size() > 0 && mCurHeader != null) {
                setSelectedHeader(mCurHeader);
            }
        }

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
            Button skipButton = (Button)findViewById(com.android.internal.R.id.skip_button);
            skipButton.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    setResult(RESULT_OK);
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
            if (intent.getBooleanExtra(EXTRA_PREFS_SHOW_SKIP, false)) {
                skipButton.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mCurHeader != null && mSinglePane && getFragmentManager().getBackStackEntryCount() == 0
                && getIntent().getStringExtra(EXTRA_SHOW_FRAGMENT) == null) {
            mCurHeader = null;

            mPrefsContainer.setVisibility(View.GONE);
            mHeadersContainer.setVisibility(View.VISIBLE);
            if (mActivityTitle != null) {
                showBreadCrumbs(mActivityTitle, null);
            }
            getListView().clearChoices();
        } else {
            super.onBackPressed();
        }
    }

    /**
     * Returns true if this activity is currently showing the header list.
     */
    public boolean hasHeaders() {
        return mHeadersContainer != null && mHeadersContainer.getVisibility() == View.VISIBLE;
    }

    /**
     * Returns the Header list
     * @hide
     */
    @UnsupportedAppUsage
    public List<Header> getHeaders() {
        return mHeaders;
    }

    /**
     * Returns true if this activity is showing multiple panes -- the headers
     * and a preference fragment.
     */
    public boolean isMultiPane() {
        return !mSinglePane;
    }

    /**
     * Called to determine if the activity should run in multi-pane mode.
     * The default implementation returns true if the screen is large
     * enough.
     */
    public boolean onIsMultiPane() {
        boolean preferMultiPane = getResources().getBoolean(
                com.android.internal.R.bool.preferences_prefer_dual_pane);
        return preferMultiPane;
    }

    /**
     * Called to determine whether the header list should be hidden.
     * The default implementation returns the
     * value given in {@link #EXTRA_NO_HEADERS} or false if it is not supplied.
     * This is set to false, for example, when the activity is being re-launched
     * to show a particular preference activity.
     */
    public boolean onIsHidingHeaders() {
        return getIntent().getBooleanExtra(EXTRA_NO_HEADERS, false);
    }

    /**
     * Called to determine the initial header to be shown.  The default
     * implementation simply returns the fragment of the first header.  Note
     * that the returned Header object does not actually need to exist in
     * your header list -- whatever its fragment is will simply be used to
     * show for the initial UI.
     */
    public Header onGetInitialHeader() {
        for (int i=0; i<mHeaders.size(); i++) {
            Header h = mHeaders.get(i);
            if (h.fragment != null) {
                return h;
            }
        }
        throw new IllegalStateException("Must have at least one header with a fragment");
    }

    /**
     * Called after the header list has been updated ({@link #onBuildHeaders}
     * has been called and returned due to {@link #invalidateHeaders()}) to
     * specify the header that should now be selected.  The default implementation
     * returns null to keep whatever header is currently selected.
     */
    public Header onGetNewHeader() {
        return null;
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
        // Should be overloaded by subclasses
    }

    /**
     * Call when you need to change the headers being displayed.  Will result
     * in onBuildHeaders() later being called to retrieve the new list.
     */
    public void invalidateHeaders() {
        if (!mHandler.hasMessages(MSG_BUILD_HEADERS)) {
            mHandler.sendEmptyMessage(MSG_BUILD_HEADERS);
        }
    }

    /**
     * Parse the given XML file as a header description, adding each
     * parsed Header into the target list.
     *
     * @param resid The XML resource to load and parse.
     * @param target The list in which the parsed headers should be placed.
     */
    public void loadHeadersFromResource(@XmlRes int resid, List<Header> target) {
        XmlResourceParser parser = null;
        try {
            parser = getResources().getXml(resid);
            AttributeSet attrs = Xml.asAttributeSet(parser);

            int type;
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && type != XmlPullParser.START_TAG) {
                // Parse next until start tag is found
            }

            String nodeName = parser.getName();
            if (!"preference-headers".equals(nodeName)) {
                throw new RuntimeException(
                        "XML document must start with <preference-headers> tag; found"
                                + nodeName + " at " + parser.getPositionDescription());
            }

            Bundle curBundle = null;

            final int outerDepth = parser.getDepth();
            while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                nodeName = parser.getName();
                if ("header".equals(nodeName)) {
                    Header header = new Header();

                    TypedArray sa = obtainStyledAttributes(
                            attrs, com.android.internal.R.styleable.PreferenceHeader);
                    header.id = sa.getResourceId(
                            com.android.internal.R.styleable.PreferenceHeader_id,
                            (int)HEADER_ID_UNDEFINED);
                    TypedValue tv = sa.peekValue(
                            com.android.internal.R.styleable.PreferenceHeader_title);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) {
                        if (tv.resourceId != 0) {
                            header.titleRes = tv.resourceId;
                        } else {
                            header.title = tv.string;
                        }
                    }
                    tv = sa.peekValue(
                            com.android.internal.R.styleable.PreferenceHeader_summary);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) {
                        if (tv.resourceId != 0) {
                            header.summaryRes = tv.resourceId;
                        } else {
                            header.summary = tv.string;
                        }
                    }
                    tv = sa.peekValue(
                            com.android.internal.R.styleable.PreferenceHeader_breadCrumbTitle);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) {
                        if (tv.resourceId != 0) {
                            header.breadCrumbTitleRes = tv.resourceId;
                        } else {
                            header.breadCrumbTitle = tv.string;
                        }
                    }
                    tv = sa.peekValue(
                            com.android.internal.R.styleable.PreferenceHeader_breadCrumbShortTitle);
                    if (tv != null && tv.type == TypedValue.TYPE_STRING) {
                        if (tv.resourceId != 0) {
                            header.breadCrumbShortTitleRes = tv.resourceId;
                        } else {
                            header.breadCrumbShortTitle = tv.string;
                        }
                    }
                    header.iconRes = sa.getResourceId(
                            com.android.internal.R.styleable.PreferenceHeader_icon, 0);
                    header.fragment = sa.getString(
                            com.android.internal.R.styleable.PreferenceHeader_fragment);
                    sa.recycle();

                    if (curBundle == null) {
                        curBundle = new Bundle();
                    }

                    final int innerDepth = parser.getDepth();
                    while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String innerNodeName = parser.getName();
                        if (innerNodeName.equals("extra")) {
                            getResources().parseBundleExtra("extra", attrs, curBundle);
                            XmlUtils.skipCurrentTag(parser);

                        } else if (innerNodeName.equals("intent")) {
                            header.intent = Intent.parseIntent(getResources(), parser, attrs);

                        } else {
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }

                    if (curBundle.size() > 0) {
                        header.fragmentArguments = curBundle;
                        curBundle = null;
                    }

                    target.add(header);
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

    /**
     * Subclasses should override this method and verify that the given fragment is a valid type
     * to be attached to this activity. The default implementation returns <code>true</code> for
     * apps built for <code>android:targetSdkVersion</code> older than
     * {@link android.os.Build.VERSION_CODES#KITKAT}. For later versions, it will throw an exception.
     * @param fragmentName the class name of the Fragment about to be attached to this activity.
     * @return true if the fragment class name is valid for this Activity and false otherwise.
     */
    protected boolean isValidFragment(String fragmentName) {
        if (getApplicationInfo().targetSdkVersion  >= android.os.Build.VERSION_CODES.KITKAT) {
            throw new RuntimeException(
                    "Subclasses of PreferenceActivity must override isValidFragment(String)"
                            + " to verify that the Fragment class is valid! "
                            + this.getClass().getName()
                            + " has not checked if fragment " + fragmentName + " is valid.");
        } else {
            return true;
        }
    }

    /**
     * Set a footer that should be shown at the bottom of the header list.
     */
    public void setListFooter(View view) {
        mListFooter.removeAllViews();
        mListFooter.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
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
        mHandler.removeMessages(MSG_BIND_PREFERENCES);
        mHandler.removeMessages(MSG_BUILD_HEADERS);
        super.onDestroy();

        if (mPreferenceManager != null) {
            mPreferenceManager.dispatchActivityDestroy();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (mHeaders.size() > 0) {
            outState.putParcelableArrayList(HEADERS_TAG, mHeaders);
            if (mCurHeader != null) {
                int index = mHeaders.indexOf(mCurHeader);
                if (index >= 0) {
                    outState.putInt(CUR_HEADER_TAG, index);
                }
            }
        }

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

        if (!mSinglePane) {
            // Multi-pane.
            if (mCurHeader != null) {
                setSelectedHeader(mCurHeader);
            }
        }
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
        if (!isResumed()) {
            return;
        }
        super.onListItemClick(l, v, position, id);

        if (mAdapter != null) {
            Object item = mAdapter.getItem(position);
            if (item instanceof Header) onHeaderClick((Header) item, position);
        }
    }

    /**
     * Called when the user selects an item in the header list.  The default
     * implementation will call either
     * {@link #startWithFragment(String, Bundle, Fragment, int, int, int)}
     * or {@link #switchToHeader(Header)} as appropriate.
     *
     * @param header The header that was selected.
     * @param position The header's position in the list.
     */
    public void onHeaderClick(Header header, int position) {
        if (header.fragment != null) {
            switchToHeader(header);
        } else if (header.intent != null) {
            startActivity(header.intent);
        }
    }

    /**
     * Called by {@link #startWithFragment(String, Bundle, Fragment, int, int, int)} when
     * in single-pane mode, to build an Intent to launch a new activity showing
     * the selected fragment.  The default implementation constructs an Intent
     * that re-launches the current activity with the appropriate arguments to
     * display the fragment.
     *
     * @param fragmentName The name of the fragment to display.
     * @param args Optional arguments to supply to the fragment.
     * @param titleRes Optional resource ID of title to show for this item.
     * @param shortTitleRes Optional resource ID of short title to show for this item.
     * @return Returns an Intent that can be launched to display the given
     * fragment.
     */
    public Intent onBuildStartFragmentIntent(String fragmentName, Bundle args,
            @StringRes int titleRes, int shortTitleRes) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(this, getClass());
        intent.putExtra(EXTRA_SHOW_FRAGMENT, fragmentName);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, args);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_TITLE, titleRes);
        intent.putExtra(EXTRA_SHOW_FRAGMENT_SHORT_TITLE, shortTitleRes);
        intent.putExtra(EXTRA_NO_HEADERS, true);
        return intent;
    }

    /**
     * Like {@link #startWithFragment(String, Bundle, Fragment, int, int, int)}
     * but uses a 0 titleRes.
     */
    public void startWithFragment(String fragmentName, Bundle args,
            Fragment resultTo, int resultRequestCode) {
        startWithFragment(fragmentName, args, resultTo, resultRequestCode, 0, 0);
    }

    /**
     * Start a new instance of this activity, showing only the given
     * preference fragment.  When launched in this mode, the header list
     * will be hidden and the given preference fragment will be instantiated
     * and fill the entire activity.
     *
     * @param fragmentName The name of the fragment to display.
     * @param args Optional arguments to supply to the fragment.
     * @param resultTo Option fragment that should receive the result of
     * the activity launch.
     * @param resultRequestCode If resultTo is non-null, this is the request
     * code in which to report the result.
     * @param titleRes Resource ID of string to display for the title of
     * this set of preferences.
     * @param shortTitleRes Resource ID of string to display for the short title of
     * this set of preferences.
     */
    public void startWithFragment(String fragmentName, Bundle args,
            Fragment resultTo, int resultRequestCode, @StringRes int titleRes,
            @StringRes int shortTitleRes) {
        Intent intent = onBuildStartFragmentIntent(fragmentName, args, titleRes, shortTitleRes);
        if (resultTo == null) {
            startActivity(intent);
        } else {
            resultTo.startActivityForResult(intent, resultRequestCode);
        }
    }

    /**
     * Change the base title of the bread crumbs for the current preferences.
     * This will normally be called for you.  See
     * {@link android.app.FragmentBreadCrumbs} for more information.
     */
    public void showBreadCrumbs(CharSequence title, CharSequence shortTitle) {
        if (mFragmentBreadCrumbs == null) {
            View crumbs = findViewById(android.R.id.title);
            // For screens with a different kind of title, don't create breadcrumbs.
            try {
                mFragmentBreadCrumbs = (FragmentBreadCrumbs)crumbs;
            } catch (ClassCastException e) {
                setTitle(title);
                return;
            }
            if (mFragmentBreadCrumbs == null) {
                if (title != null) {
                    setTitle(title);
                }
                return;
            }
            if (mSinglePane) {
                mFragmentBreadCrumbs.setVisibility(View.GONE);
                // Hide the breadcrumb section completely for single-pane
                View bcSection = findViewById(com.android.internal.R.id.breadcrumb_section);
                if (bcSection != null) bcSection.setVisibility(View.GONE);
                setTitle(title);
            }
            mFragmentBreadCrumbs.setMaxVisible(2);
            mFragmentBreadCrumbs.setActivity(this);
        }
        if (mFragmentBreadCrumbs.getVisibility() != View.VISIBLE) {
            setTitle(title);
        } else {
            mFragmentBreadCrumbs.setTitle(title, shortTitle);
            mFragmentBreadCrumbs.setParentTitle(null, null, null);
        }
    }

    /**
     * Should be called after onCreate to ensure that the breadcrumbs, if any, were created.
     * This prepends a title to the fragment breadcrumbs and attaches a listener to any clicks
     * on the parent entry.
     * @param title the title for the breadcrumb
     * @param shortTitle the short title for the breadcrumb
     */
    public void setParentTitle(CharSequence title, CharSequence shortTitle,
            OnClickListener listener) {
        if (mFragmentBreadCrumbs != null) {
            mFragmentBreadCrumbs.setParentTitle(title, shortTitle, listener);
        }
    }

    void setSelectedHeader(Header header) {
        mCurHeader = header;
        int index = mHeaders.indexOf(header);
        if (index >= 0) {
            getListView().setItemChecked(index, true);
        } else {
            getListView().clearChoices();
        }
        showBreadCrumbs(header);
    }

    void showBreadCrumbs(Header header) {
        if (header != null) {
            CharSequence title = header.getBreadCrumbTitle(getResources());
            if (title == null) title = header.getTitle(getResources());
            if (title == null) title = getTitle();
            showBreadCrumbs(title, header.getBreadCrumbShortTitle(getResources()));
        } else {
            showBreadCrumbs(getTitle(), null);
        }
    }

    private void switchToHeaderInner(String fragmentName, Bundle args) {
        getFragmentManager().popBackStack(BACK_STACK_PREFS,
                FragmentManager.POP_BACK_STACK_INCLUSIVE);
        if (!isValidFragment(fragmentName)) {
            throw new IllegalArgumentException("Invalid fragment for this activity: "
                    + fragmentName);
        }

        Fragment f = Fragment.instantiate(this, fragmentName, args);
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.setTransition(mSinglePane
                ? FragmentTransaction.TRANSIT_NONE
                : FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        transaction.replace(com.android.internal.R.id.prefs, f);
        transaction.commitAllowingStateLoss();

        if (mSinglePane && mPrefsContainer.getVisibility() == View.GONE) {
            // We are transitioning from headers to preferences panel in single-pane so we need
            // to hide headers and show the prefs container.
            mPrefsContainer.setVisibility(View.VISIBLE);
            mHeadersContainer.setVisibility(View.GONE);
        }
    }

    /**
     * When in two-pane mode, switch the fragment pane to show the given
     * preference fragment.
     *
     * @param fragmentName The name of the fragment to display.
     * @param args Optional arguments to supply to the fragment.
     */
    public void switchToHeader(String fragmentName, Bundle args) {
        Header selectedHeader = null;
        for (int i = 0; i < mHeaders.size(); i++) {
            if (fragmentName.equals(mHeaders.get(i).fragment)) {
                selectedHeader = mHeaders.get(i);
                break;
            }
        }
        setSelectedHeader(selectedHeader);
        switchToHeaderInner(fragmentName, args);
    }

    /**
     * When in two-pane mode, switch to the fragment pane to show the given
     * preference fragment.
     *
     * @param header The new header to display.
     */
    public void switchToHeader(Header header) {
        if (mCurHeader == header) {
            // This is the header we are currently displaying.  Just make sure
            // to pop the stack up to its root state.
            getFragmentManager().popBackStack(BACK_STACK_PREFS,
                    FragmentManager.POP_BACK_STACK_INCLUSIVE);
        } else {
            if (header.fragment == null) {
                throw new IllegalStateException("can't switch to header that has no fragment");
            }
            switchToHeaderInner(header.fragment, header.fragmentArguments);
            setSelectedHeader(header);
        }
    }

    Header findBestMatchingHeader(Header cur, ArrayList<Header> from) {
        ArrayList<Header> matches = new ArrayList<Header>();
        for (int j=0; j<from.size(); j++) {
            Header oh = from.get(j);
            if (cur == oh || (cur.id != HEADER_ID_UNDEFINED && cur.id == oh.id)) {
                // Must be this one.
                matches.clear();
                matches.add(oh);
                break;
            }
            if (cur.fragment != null) {
                if (cur.fragment.equals(oh.fragment)) {
                    matches.add(oh);
                }
            } else if (cur.intent != null) {
                if (cur.intent.equals(oh.intent)) {
                    matches.add(oh);
                }
            } else if (cur.title != null) {
                if (cur.title.equals(oh.title)) {
                    matches.add(oh);
                }
            }
        }
        final int NM = matches.size();
        if (NM == 1) {
            return matches.get(0);
        } else if (NM > 1) {
            for (int j=0; j<NM; j++) {
                Header oh = matches.get(j);
                if (cur.fragmentArguments != null &&
                        cur.fragmentArguments.equals(oh.fragmentArguments)) {
                    return oh;
                }
                if (cur.extras != null && cur.extras.equals(oh.extras)) {
                    return oh;
                }
                if (cur.title != null && cur.title.equals(oh.title)) {
                    return oh;
                }
            }
        }
        return null;
    }

    /**
     * Start a new fragment.
     *
     * @param fragment The fragment to start
     * @param push If true, the current fragment will be pushed onto the back stack.  If false,
     * the current fragment will be replaced.
     */
    public void startPreferenceFragment(Fragment fragment, boolean push) {
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.replace(com.android.internal.R.id.prefs, fragment);
        if (push) {
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
            transaction.addToBackStack(BACK_STACK_PREFS);
        } else {
            transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE);
        }
        transaction.commitAllowingStateLoss();
    }

    /**
     * Start a new fragment containing a preference panel.  If the preferences
     * are being displayed in multi-pane mode, the given fragment class will
     * be instantiated and placed in the appropriate pane.  If running in
     * single-pane mode, a new activity will be launched in which to show the
     * fragment.
     *
     * @param fragmentClass Full name of the class implementing the fragment.
     * @param args Any desired arguments to supply to the fragment.
     * @param titleRes Optional resource identifier of the title of this
     * fragment.
     * @param titleText Optional text of the title of this fragment.
     * @param resultTo Optional fragment that result data should be sent to.
     * If non-null, resultTo.onActivityResult() will be called when this
     * preference panel is done.  The launched panel must use
     * {@link #finishPreferencePanel(Fragment, int, Intent)} when done.
     * @param resultRequestCode If resultTo is non-null, this is the caller's
     * request code to be received with the result.
     */
    public void startPreferencePanel(String fragmentClass, Bundle args, @StringRes int titleRes,
            CharSequence titleText, Fragment resultTo, int resultRequestCode) {
        Fragment f = Fragment.instantiate(this, fragmentClass, args);
        if (resultTo != null) {
            f.setTargetFragment(resultTo, resultRequestCode);
        }
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.replace(com.android.internal.R.id.prefs, f);
        if (titleRes != 0) {
            transaction.setBreadCrumbTitle(titleRes);
        } else if (titleText != null) {
            transaction.setBreadCrumbTitle(titleText);
        }
        transaction.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
        transaction.addToBackStack(BACK_STACK_PREFS);
        transaction.commitAllowingStateLoss();
    }

    /**
     * Called by a preference panel fragment to finish itself.
     *
     * @param caller The fragment that is asking to be finished.
     * @param resultCode Optional result code to send back to the original
     * launching fragment.
     * @param resultData Optional result data to send back to the original
     * launching fragment.
     */
    public void finishPreferencePanel(Fragment caller, int resultCode, Intent resultData) {
        // TODO: be smarter about popping the stack.
        onBackPressed();
        if (caller != null) {
            if (caller.getTargetFragment() != null) {
                caller.getTargetFragment().onActivityResult(caller.getTargetRequestCode(),
                        resultCode, resultData);
            }
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragment caller, Preference pref) {
        startPreferencePanel(pref.getFragment(), pref.getExtras(), pref.getTitleRes(),
                pref.getTitle(), null, 0);
        return true;
    }

    /**
     * Posts a message to bind the preferences to the list view.
     * <p>
     * Binding late is preferred as any custom preference types created in
     * {@link #onCreate(Bundle)} are able to have their views recycled.
     */
    @UnsupportedAppUsage
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

    @UnsupportedAppUsage
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
