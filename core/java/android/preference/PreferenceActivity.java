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

import android.app.Activity;
import android.app.ListActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;

/**
 * Shows a hierarchy of {@link Preference} objects as
 * lists, possibly spanning multiple screens. These preferences will
 * automatically save to {@link SharedPreferences} as the user interacts with
 * them. To retrieve an instance of {@link SharedPreferences} that the
 * preference hierarchy in this activity will use, call
 * {@link PreferenceManager#getDefaultSharedPreferences(android.content.Context)}
 * with a context in the same package as this activity.
 * <p>
 * Furthermore, the preferences shown will follow the visual style of system
 * preferences. It is easy to create a hierarchy of preferences (that can be
 * shown on multiple screens) via XML. For these reasons, it is recommended to
 * use this activity (as a superclass) to deal with preferences in applications.
 * <p>
 * A {@link PreferenceScreen} object should be at the top of the preference
 * hierarchy. Furthermore, subsequent {@link PreferenceScreen} in the hierarchy
 * denote a screen break--that is the preferences contained within subsequent
 * {@link PreferenceScreen} should be shown on another screen. The preference
 * framework handles showing these other screens from the preference hierarchy.
 * <p>
 * The preference hierarchy can be formed in multiple ways:
 * <li> From an XML file specifying the hierarchy
 * <li> From different {@link Activity Activities} that each specify its own
 * preferences in an XML file via {@link Activity} meta-data
 * <li> From an object hierarchy rooted with {@link PreferenceScreen}
 * <p>
 * To inflate from XML, use the {@link #addPreferencesFromResource(int)}. The
 * root element should be a {@link PreferenceScreen}. Subsequent elements can point
 * to actual {@link Preference} subclasses. As mentioned above, subsequent
 * {@link PreferenceScreen} in the hierarchy will result in the screen break.
 * <p>
 * To specify an {@link Intent} to query {@link Activity Activities} that each
 * have preferences, use {@link #addPreferencesFromIntent}. Each
 * {@link Activity} can specify meta-data in the manifest (via the key
 * {@link PreferenceManager#METADATA_KEY_PREFERENCES}) that points to an XML
 * resource. These XML resources will be inflated into a single preference
 * hierarchy and shown by this activity.
 * <p>
 * To specify an object hierarchy rooted with {@link PreferenceScreen}, use
 * {@link #setPreferenceScreen(PreferenceScreen)}.
 * <p>
 * As a convenience, this activity implements a click listener for any
 * preference in the current hierarchy, see
 * {@link #onPreferenceTreeClick(PreferenceScreen, Preference)}.
 * 
 * @see Preference
 * @see PreferenceScreen
 */
public abstract class PreferenceActivity extends ListActivity implements
        PreferenceManager.OnPreferenceTreeClickListener {
    
    private static final String PREFERENCES_TAG = "android:preferences";
    
    private PreferenceManager mPreferenceManager;
    
    private Bundle mSavedInstanceState;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(com.android.internal.R.layout.preference_list_content);
        
        mPreferenceManager = onCreatePreferenceManager();
        getListView().setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        
        mPreferenceManager.dispatchActivityStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        mPreferenceManager.dispatchActivityDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        final PreferenceScreen preferenceScreen = getPreferenceScreen();
        if (preferenceScreen != null) {
            Bundle container = new Bundle();
            preferenceScreen.saveHierarchyState(container);
            outState.putBundle(PREFERENCES_TAG, container);
        }
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        Bundle container = state.getBundle(PREFERENCES_TAG);
        if (container != null) {
            final PreferenceScreen preferenceScreen = getPreferenceScreen();
            if (preferenceScreen != null) {
                preferenceScreen.restoreHierarchyState(container);
                mSavedInstanceState = state;
                return;
            }
        }

        // Only call this if we didn't save the instance state for later.
        // If we did save it, it will be restored when we bind the adapter.
        super.onRestoreInstanceState(state);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        mPreferenceManager.dispatchActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        postBindPreferences();
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
     * Creates the {@link PreferenceManager}.
     * 
     * @return The {@link PreferenceManager} used by this activity.
     */
    private PreferenceManager onCreatePreferenceManager() {
        PreferenceManager preferenceManager = new PreferenceManager(this, FIRST_REQUEST_CODE);
        preferenceManager.setOnPreferenceTreeClickListener(this);
        return preferenceManager;
    }
    
    /**
     * Returns the {@link PreferenceManager} used by this activity.
     * @return The {@link PreferenceManager}.
     */
    public PreferenceManager getPreferenceManager() {
        return mPreferenceManager;
    }
    
    private void requirePreferenceManager() {
        if (mPreferenceManager == null) {
            throw new RuntimeException("This should be called after super.onCreate.");
        }
    }

    /**
     * Sets the root of the preference hierarchy that this activity is showing.
     * 
     * @param preferenceScreen The root {@link PreferenceScreen} of the preference hierarchy.
     */
    public void setPreferenceScreen(PreferenceScreen preferenceScreen) {
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
     */
    public PreferenceScreen getPreferenceScreen() {
        return mPreferenceManager.getPreferenceScreen();
    }
    
    /**
     * Adds preferences from activities that match the given {@link Intent}.
     * 
     * @param intent The {@link Intent} to query activities.
     */
    public void addPreferencesFromIntent(Intent intent) {
        requirePreferenceManager();
        
        setPreferenceScreen(mPreferenceManager.inflateFromIntent(intent, getPreferenceScreen()));
    }
    
    /**
     * Inflates the given XML resource and adds the preference hierarchy to the current
     * preference hierarchy.
     * 
     * @param preferencesResId The XML resource ID to inflate.
     */
    public void addPreferencesFromResource(int preferencesResId) {
        requirePreferenceManager();
        
        setPreferenceScreen(mPreferenceManager.inflateFromResource(this, preferencesResId,
                getPreferenceScreen()));
    }

    /**
     * {@inheritDoc}
     */
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        return false;
    }
    
    /**
     * Finds a {@link Preference} based on its key.
     * 
     * @param key The key of the preference to retrieve.
     * @return The {@link Preference} with the key, or null.
     * @see PreferenceGroup#findPreference(CharSequence)
     */
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
    
}
