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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.XmlResourceParser;
import android.os.Bundle;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Used to help create {@link Preference} hierarchies
 * from activities or XML.
 * <p>
 * In most cases, clients should use
 * {@link PreferenceActivity#addPreferencesFromIntent} or
 * {@link PreferenceActivity#addPreferencesFromResource(int)}.
 * 
 * @see PreferenceActivity
 */
public class PreferenceManager {
    
    private static final String TAG = "PreferenceManager";

    /**
     * The Activity meta-data key for its XML preference hierarchy.
     */
    public static final String METADATA_KEY_PREFERENCES = "android.preference";
    
    public static final String KEY_HAS_SET_DEFAULT_VALUES = "_has_set_default_values";
    
    /**
     * @see #getActivity()
     */
    private Activity mActivity;

    /**
     * Fragment that owns this instance.
     */
    private PreferenceFragment mFragment;

    /**
     * The context to use. This should always be set.
     * 
     * @see #mActivity
     */
    private Context mContext;
    
    /**
     * The counter for unique IDs.
     */
    private long mNextId = 0;

    /**
     * The counter for unique request codes.
     */
    private int mNextRequestCode;

    /**
     * Cached shared preferences.
     */
    private SharedPreferences mSharedPreferences;
    
    /**
     * If in no-commit mode, the shared editor to give out (which will be
     * committed when exiting no-commit mode).
     */
    private SharedPreferences.Editor mEditor;
    
    /**
     * Blocks commits from happening on the shared editor. This is used when
     * inflating the hierarchy. Do not set this directly, use {@link #setNoCommit(boolean)}
     */
    private boolean mNoCommit;
    
    /**
     * The SharedPreferences name that will be used for all {@link Preference}s
     * managed by this instance.
     */
    private String mSharedPreferencesName;
    
    /**
     * The SharedPreferences mode that will be used for all {@link Preference}s
     * managed by this instance.
     */
    private int mSharedPreferencesMode;
    
    /**
     * The {@link PreferenceScreen} at the root of the preference hierarchy.
     */
    private PreferenceScreen mPreferenceScreen;

    /**
     * List of activity result listeners.
     */
    private List<OnActivityResultListener> mActivityResultListeners;

    /**
     * List of activity stop listeners.
     */
    private List<OnActivityStopListener> mActivityStopListeners;

    /**
     * List of activity destroy listeners.
     */
    private List<OnActivityDestroyListener> mActivityDestroyListeners;

    /**
     * List of dialogs that should be dismissed when we receive onNewIntent in
     * our PreferenceActivity.
     */
    private List<DialogInterface> mPreferencesScreens;
    
    private OnPreferenceTreeClickListener mOnPreferenceTreeClickListener;
    
    /**
     * @hide
     */
    public PreferenceManager(Activity activity, int firstRequestCode) {
        mActivity = activity;
        mNextRequestCode = firstRequestCode;
        
        init(activity);
    }

    /**
     * This constructor should ONLY be used when getting default values from
     * an XML preference hierarchy.
     * <p>
     * The {@link PreferenceManager#PreferenceManager(Activity)}
     * should be used ANY time a preference will be displayed, since some preference
     * types need an Activity for managed queries.
     */
    private PreferenceManager(Context context) {
        init(context);
    }

    private void init(Context context) {
        mContext = context;
        
        setSharedPreferencesName(getDefaultSharedPreferencesName(context));
    }

    /**
     * Sets the owning preference fragment
     */
    void setFragment(PreferenceFragment fragment) {
        mFragment = fragment;
    }

    /**
     * Returns the owning preference fragment, if any.
     */
    PreferenceFragment getFragment() {
        return mFragment;
    }

    /**
     * Returns a list of {@link Activity} (indirectly) that match a given
     * {@link Intent}.
     * 
     * @param queryIntent The Intent to match.
     * @return The list of {@link ResolveInfo} that point to the matched
     *         activities.
     */
    private List<ResolveInfo> queryIntentActivities(Intent queryIntent) {
        return mContext.getPackageManager().queryIntentActivities(queryIntent,
                PackageManager.GET_META_DATA);
    }
    
    /**
     * Inflates a preference hierarchy from the preference hierarchies of
     * {@link Activity Activities} that match the given {@link Intent}. An
     * {@link Activity} defines its preference hierarchy with meta-data using
     * the {@link #METADATA_KEY_PREFERENCES} key.
     * <p>
     * If a preference hierarchy is given, the new preference hierarchies will
     * be merged in.
     * 
     * @param queryIntent The intent to match activities.
     * @param rootPreferences Optional existing hierarchy to merge the new
     *            hierarchies into.
     * @return The root hierarchy (if one was not provided, the new hierarchy's
     *         root).
     */
    PreferenceScreen inflateFromIntent(Intent queryIntent, PreferenceScreen rootPreferences) {
        final List<ResolveInfo> activities = queryIntentActivities(queryIntent);
        final HashSet<String> inflatedRes = new HashSet<String>();

        for (int i = activities.size() - 1; i >= 0; i--) {
            final ActivityInfo activityInfo = activities.get(i).activityInfo;
            final Bundle metaData = activityInfo.metaData;

            if ((metaData == null) || !metaData.containsKey(METADATA_KEY_PREFERENCES)) {
                continue;
            }

            // Need to concat the package with res ID since the same res ID
            // can be re-used across contexts
            final String uniqueResId = activityInfo.packageName + ":"
                    + activityInfo.metaData.getInt(METADATA_KEY_PREFERENCES);
            
            if (!inflatedRes.contains(uniqueResId)) {
                inflatedRes.add(uniqueResId);

                final Context context;
                try {
                    context = mContext.createPackageContext(activityInfo.packageName, 0);
                } catch (NameNotFoundException e) {
                    Log.w(TAG, "Could not create context for " + activityInfo.packageName + ": "
                        + Log.getStackTraceString(e));
                    continue;
                }
                
                final PreferenceInflater inflater = new PreferenceInflater(context, this);
                final XmlResourceParser parser = activityInfo.loadXmlMetaData(context
                        .getPackageManager(), METADATA_KEY_PREFERENCES);
                rootPreferences = (PreferenceScreen) inflater
                        .inflate(parser, rootPreferences, true);
                parser.close();
            }
        }

        rootPreferences.onAttachedToHierarchy(this);
        
        return rootPreferences;
    }

    /**
     * Inflates a preference hierarchy from XML. If a preference hierarchy is
     * given, the new preference hierarchies will be merged in.
     * 
     * @param context The context of the resource.
     * @param resId The resource ID of the XML to inflate.
     * @param rootPreferences Optional existing hierarchy to merge the new
     *            hierarchies into.
     * @return The root hierarchy (if one was not provided, the new hierarchy's
     *         root).
     * @hide
     */
    public PreferenceScreen inflateFromResource(Context context, int resId,
            PreferenceScreen rootPreferences) {
        // Block commits
        setNoCommit(true);

        final PreferenceInflater inflater = new PreferenceInflater(context, this);
        rootPreferences = (PreferenceScreen) inflater.inflate(resId, rootPreferences, true);
        rootPreferences.onAttachedToHierarchy(this);

        // Unblock commits
        setNoCommit(false);

        return rootPreferences;
    }
    
    public PreferenceScreen createPreferenceScreen(Context context) {
        final PreferenceScreen preferenceScreen = new PreferenceScreen(context, null);
        preferenceScreen.onAttachedToHierarchy(this);
        return preferenceScreen;
    }
    
    /**
     * Called by a preference to get a unique ID in its hierarchy.
     * 
     * @return A unique ID.
     */
    long getNextId() {
        synchronized (this) {
            return mNextId++;
        }
    }
    
    /**
     * Returns the current name of the SharedPreferences file that preferences managed by
     * this will use.
     * 
     * @return The name that can be passed to {@link Context#getSharedPreferences(String, int)}.
     * @see Context#getSharedPreferences(String, int)
     */
    public String getSharedPreferencesName() {
        return mSharedPreferencesName;
    }

    /**
     * Sets the name of the SharedPreferences file that preferences managed by this
     * will use.
     * 
     * @param sharedPreferencesName The name of the SharedPreferences file.
     * @see Context#getSharedPreferences(String, int)
     */
    public void setSharedPreferencesName(String sharedPreferencesName) {
        mSharedPreferencesName = sharedPreferencesName;
        mSharedPreferences = null;
    }

    /**
     * Returns the current mode of the SharedPreferences file that preferences managed by
     * this will use.
     * 
     * @return The mode that can be passed to {@link Context#getSharedPreferences(String, int)}.
     * @see Context#getSharedPreferences(String, int)
     */
    public int getSharedPreferencesMode() {
        return mSharedPreferencesMode;
    }

    /**
     * Sets the mode of the SharedPreferences file that preferences managed by this
     * will use.
     * 
     * @param sharedPreferencesMode The mode of the SharedPreferences file.
     * @see Context#getSharedPreferences(String, int)
     */
    public void setSharedPreferencesMode(int sharedPreferencesMode) {
        mSharedPreferencesMode = sharedPreferencesMode;
        mSharedPreferences = null;
    }

    /**
     * Gets a SharedPreferences instance that preferences managed by this will
     * use.
     * 
     * @return A SharedPreferences instance pointing to the file that contains
     *         the values of preferences that are managed by this.
     */
    public SharedPreferences getSharedPreferences() {
        if (mSharedPreferences == null) {
            mSharedPreferences = mContext.getSharedPreferences(mSharedPreferencesName,
                    mSharedPreferencesMode);
        }
        
        return mSharedPreferences;
    }
    
    /**
     * Gets a SharedPreferences instance that points to the default file that is
     * used by the preference framework in the given context.
     * 
     * @param context The context of the preferences whose values are wanted.
     * @return A SharedPreferences instance that can be used to retrieve and
     *         listen to values of the preferences.
     */
    public static SharedPreferences getDefaultSharedPreferences(Context context) {
        return context.getSharedPreferences(getDefaultSharedPreferencesName(context),
                getDefaultSharedPreferencesMode());
    }

    private static String getDefaultSharedPreferencesName(Context context) {
        return context.getPackageName() + "_preferences";
    }

    private static int getDefaultSharedPreferencesMode() {
        return Context.MODE_PRIVATE;
    }

    /**
     * Returns the root of the preference hierarchy managed by this class.
     *  
     * @return The {@link PreferenceScreen} object that is at the root of the hierarchy.
     */
    PreferenceScreen getPreferenceScreen() {
        return mPreferenceScreen;
    }
    
    /**
     * Sets the root of the preference hierarchy.
     * 
     * @param preferenceScreen The root {@link PreferenceScreen} of the preference hierarchy.
     * @return Whether the {@link PreferenceScreen} given is different than the previous. 
     */
    boolean setPreferences(PreferenceScreen preferenceScreen) {
        if (preferenceScreen != mPreferenceScreen) {
            mPreferenceScreen = preferenceScreen;
            return true;
        }
        
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
        if (mPreferenceScreen == null) {
            return null;
        }
        
        return mPreferenceScreen.findPreference(key);
    }
    
    /**
     * Sets the default values from an XML preference file by reading the values defined
     * by each {@link Preference} item's {@code android:defaultValue} attribute. This should
     * be called by the application's main activity.
     * <p>
     * 
     * @param context The context of the shared preferences.
     * @param resId The resource ID of the preference XML file.
     * @param readAgain Whether to re-read the default values.
     * If false, this method sets the default values only if this
     * method has never been called in the past (or if the
     * {@link #KEY_HAS_SET_DEFAULT_VALUES} in the default value shared
     * preferences file is false). To attempt to set the default values again
     * bypassing this check, set {@code readAgain} to true.
     *            <p class="note">
     *            Note: this will NOT reset preferences back to their default
     *            values. For that functionality, use
     *            {@link PreferenceManager#getDefaultSharedPreferences(Context)}
     *            and clear it followed by a call to this method with this
     *            parameter set to true.
     */
    public static void setDefaultValues(Context context, int resId, boolean readAgain) {
        
        // Use the default shared preferences name and mode
        setDefaultValues(context, getDefaultSharedPreferencesName(context),
                getDefaultSharedPreferencesMode(), resId, readAgain);
    }
    
    /**
     * Similar to {@link #setDefaultValues(Context, int, boolean)} but allows
     * the client to provide the filename and mode of the shared preferences
     * file.
     *
     * @param context The context of the shared preferences.
     * @param sharedPreferencesName A custom name for the shared preferences file.
     * @param sharedPreferencesMode The file creation mode for the shared preferences file, such
     * as {@link android.content.Context#MODE_PRIVATE} or {@link
     * android.content.Context#MODE_PRIVATE}
     * @param resId The resource ID of the preference XML file.
     * @param readAgain Whether to re-read the default values.
     * If false, this method will set the default values only if this
     * method has never been called in the past (or if the
     * {@link #KEY_HAS_SET_DEFAULT_VALUES} in the default value shared
     * preferences file is false). To attempt to set the default values again
     * bypassing this check, set {@code readAgain} to true.
     *            <p class="note">
     *            Note: this will NOT reset preferences back to their default
     *            values. For that functionality, use
     *            {@link PreferenceManager#getDefaultSharedPreferences(Context)}
     *            and clear it followed by a call to this method with this
     *            parameter set to true.
     * 
     * @see #setDefaultValues(Context, int, boolean)
     * @see #setSharedPreferencesName(String)
     * @see #setSharedPreferencesMode(int)
     */
    public static void setDefaultValues(Context context, String sharedPreferencesName,
            int sharedPreferencesMode, int resId, boolean readAgain) {
        final SharedPreferences defaultValueSp = context.getSharedPreferences(
                KEY_HAS_SET_DEFAULT_VALUES, Context.MODE_PRIVATE);
        
        if (readAgain || !defaultValueSp.getBoolean(KEY_HAS_SET_DEFAULT_VALUES, false)) {
            final PreferenceManager pm = new PreferenceManager(context);
            pm.setSharedPreferencesName(sharedPreferencesName);
            pm.setSharedPreferencesMode(sharedPreferencesMode);
            pm.inflateFromResource(context, resId, null);

            SharedPreferences.Editor editor =
                    defaultValueSp.edit().putBoolean(KEY_HAS_SET_DEFAULT_VALUES, true);
            try {
                editor.apply();
            } catch (AbstractMethodError unused) {
                // The app injected its own pre-Gingerbread
                // SharedPreferences.Editor implementation without
                // an apply method.
                editor.commit();
            }
        }
    }
    
    /**
     * Returns an editor to use when modifying the shared preferences.
     * <p>
     * Do NOT commit unless {@link #shouldCommit()} returns true.
     * 
     * @return An editor to use to write to shared preferences.
     * @see #shouldCommit()
     */
    SharedPreferences.Editor getEditor() {
        
        if (mNoCommit) {
            if (mEditor == null) {
                mEditor = getSharedPreferences().edit();
            }
            
            return mEditor;
        } else {
            return getSharedPreferences().edit();
        }
    }
    
    /**
     * Whether it is the client's responsibility to commit on the
     * {@link #getEditor()}. This will return false in cases where the writes
     * should be batched, for example when inflating preferences from XML.
     * 
     * @return Whether the client should commit.
     */
    boolean shouldCommit() {
        return !mNoCommit;
    }

    private void setNoCommit(boolean noCommit) {
        if (!noCommit && mEditor != null) {
            try {
                mEditor.apply();
            } catch (AbstractMethodError unused) {
                // The app injected its own pre-Gingerbread
                // SharedPreferences.Editor implementation without
                // an apply method.
                mEditor.commit();
            }
        }
        mNoCommit = noCommit;
    }

    /**
     * Returns the activity that shows the preferences. This is useful for doing
     * managed queries, but in most cases the use of {@link #getContext()} is
     * preferred.
     * <p>
     * This will return null if this class was instantiated with a Context
     * instead of Activity. For example, when setting the default values.
     * 
     * @return The activity that shows the preferences.
     * @see #mContext
     */
    Activity getActivity() {
        return mActivity;
    }
    
    /**
     * Returns the context. This is preferred over {@link #getActivity()} when
     * possible.
     * 
     * @return The context.
     */
    Context getContext() {
        return mContext;
    }

    /**
     * Registers a listener.
     * 
     * @see OnActivityResultListener
     */
    void registerOnActivityResultListener(OnActivityResultListener listener) {
        synchronized (this) {
            if (mActivityResultListeners == null) {
                mActivityResultListeners = new ArrayList<OnActivityResultListener>();
            }
            
            if (!mActivityResultListeners.contains(listener)) {
                mActivityResultListeners.add(listener);
            }
        }
    }

    /**
     * Unregisters a listener.
     * 
     * @see OnActivityResultListener
     */
    void unregisterOnActivityResultListener(OnActivityResultListener listener) {
        synchronized (this) {
            if (mActivityResultListeners != null) {
                mActivityResultListeners.remove(listener);
            }
        }
    }

    /**
     * Called by the {@link PreferenceManager} to dispatch a subactivity result.
     */
    void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        List<OnActivityResultListener> list;
        
        synchronized (this) {
            if (mActivityResultListeners == null) return;
            list = new ArrayList<OnActivityResultListener>(mActivityResultListeners);
        }

        final int N = list.size();
        for (int i = 0; i < N; i++) {
            if (list.get(i).onActivityResult(requestCode, resultCode, data)) {
                break;
            }
        }
    }

    /**
     * Registers a listener.
     * 
     * @see OnActivityStopListener
     * @hide
     */
    public void registerOnActivityStopListener(OnActivityStopListener listener) {
        synchronized (this) {
            if (mActivityStopListeners == null) {
                mActivityStopListeners = new ArrayList<OnActivityStopListener>();
            }
            
            if (!mActivityStopListeners.contains(listener)) {
                mActivityStopListeners.add(listener);
            }
        }
    }
    
    /**
     * Unregisters a listener.
     * 
     * @see OnActivityStopListener
     * @hide
     */
    public void unregisterOnActivityStopListener(OnActivityStopListener listener) {
        synchronized (this) {
            if (mActivityStopListeners != null) {
                mActivityStopListeners.remove(listener);
            }
        }
    }
    
    /**
     * Called by the {@link PreferenceManager} to dispatch the activity stop
     * event.
     */
    void dispatchActivityStop() {
        List<OnActivityStopListener> list;
        
        synchronized (this) {
            if (mActivityStopListeners == null) return;
            list = new ArrayList<OnActivityStopListener>(mActivityStopListeners);
        }

        final int N = list.size();
        for (int i = 0; i < N; i++) {
            list.get(i).onActivityStop();
        }
    }

    /**
     * Registers a listener.
     * 
     * @see OnActivityDestroyListener
     */
    void registerOnActivityDestroyListener(OnActivityDestroyListener listener) {
        synchronized (this) {
            if (mActivityDestroyListeners == null) {
                mActivityDestroyListeners = new ArrayList<OnActivityDestroyListener>();
            }

            if (!mActivityDestroyListeners.contains(listener)) {
                mActivityDestroyListeners.add(listener);
            }
        }
    }
    
    /**
     * Unregisters a listener.
     * 
     * @see OnActivityDestroyListener
     */
    void unregisterOnActivityDestroyListener(OnActivityDestroyListener listener) {
        synchronized (this) {
            if (mActivityDestroyListeners != null) {
                mActivityDestroyListeners.remove(listener);
            }
        }
    }
    
    /**
     * Called by the {@link PreferenceManager} to dispatch the activity destroy
     * event.
     */
    void dispatchActivityDestroy() {
        List<OnActivityDestroyListener> list = null;
        
        synchronized (this) {
            if (mActivityDestroyListeners != null) {
                list = new ArrayList<OnActivityDestroyListener>(mActivityDestroyListeners);
            }
        }

        if (list != null) {
            final int N = list.size();
            for (int i = 0; i < N; i++) {
                list.get(i).onActivityDestroy();
            }
        }

        // Dismiss any PreferenceScreens still showing
        dismissAllScreens();
    }
    
    /**
     * Returns a request code that is unique for the activity. Each subsequent
     * call to this method should return another unique request code.
     * 
     * @return A unique request code that will never be used by anyone other
     *         than the caller of this method.
     */
    int getNextRequestCode() {
        synchronized (this) {
            return mNextRequestCode++;
        }
    }
    
    void addPreferencesScreen(DialogInterface screen) {
        synchronized (this) {
            
            if (mPreferencesScreens == null) {
                mPreferencesScreens = new ArrayList<DialogInterface>();
            }
            
            mPreferencesScreens.add(screen);
        }
    }
    
    void removePreferencesScreen(DialogInterface screen) {
        synchronized (this) {
            
            if (mPreferencesScreens == null) {
                return;
            }
            
            mPreferencesScreens.remove(screen);
        }
    }
    
    /**
     * Called by {@link PreferenceActivity} to dispatch the new Intent event.
     * 
     * @param intent The new Intent.
     */
    void dispatchNewIntent(Intent intent) {
        dismissAllScreens();
    }

    private void dismissAllScreens() {
        // Remove any of the previously shown preferences screens
        ArrayList<DialogInterface> screensToDismiss;

        synchronized (this) {
            
            if (mPreferencesScreens == null) {
                return;
            }
            
            screensToDismiss = new ArrayList<DialogInterface>(mPreferencesScreens);
            mPreferencesScreens.clear();
        }
        
        for (int i = screensToDismiss.size() - 1; i >= 0; i--) {
            screensToDismiss.get(i).dismiss();
        }
    }
    
    /**
     * Sets the callback to be invoked when a {@link Preference} in the
     * hierarchy rooted at this {@link PreferenceManager} is clicked.
     * 
     * @param listener The callback to be invoked.
     */
    void setOnPreferenceTreeClickListener(OnPreferenceTreeClickListener listener) {
        mOnPreferenceTreeClickListener = listener;
    }

    OnPreferenceTreeClickListener getOnPreferenceTreeClickListener() {
        return mOnPreferenceTreeClickListener;
    }
    
    /**
     * Interface definition for a callback to be invoked when a
     * {@link Preference} in the hierarchy rooted at this {@link PreferenceScreen} is
     * clicked.
     *
     * @hide
     */
    public interface OnPreferenceTreeClickListener {
        /**
         * Called when a preference in the tree rooted at this
         * {@link PreferenceScreen} has been clicked.
         * 
         * @param preferenceScreen The {@link PreferenceScreen} that the
         *        preference is located in.
         * @param preference The preference that was clicked.
         * @return Whether the click was handled.
         */
        boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference);
    }

    /**
     * Interface definition for a class that will be called when the container's activity
     * receives an activity result.
     */
    public interface OnActivityResultListener {
        
        /**
         * See Activity's onActivityResult.
         * 
         * @return Whether the request code was handled (in which case
         *         subsequent listeners will not be called.
         */
        boolean onActivityResult(int requestCode, int resultCode, Intent data);
    }
    
    /**
     * Interface definition for a class that will be called when the container's activity
     * is stopped.
     */
    public interface OnActivityStopListener {
        
        /**
         * See Activity's onStop.
         */
        void onActivityStop();
    }

    /**
     * Interface definition for a class that will be called when the container's activity
     * is destroyed.
     */
    public interface OnActivityDestroyListener {
        
        /**
         * See Activity's onDestroy.
         */
        void onActivityDestroy();
    }

}
