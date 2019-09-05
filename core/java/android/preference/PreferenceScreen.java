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

import android.annotation.UnsupportedAppUsage;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/**
 * Represents a top-level {@link Preference} that
 * is the root of a Preference hierarchy. A {@link PreferenceActivity}
 * points to an instance of this class to show the preferences. To instantiate
 * this class, use {@link PreferenceManager#createPreferenceScreen(Context)}.
 * <ul>
 * This class can appear in two places:
 * <li> When a {@link PreferenceActivity} points to this, it is used as the root
 * and is not shown (only the contained preferences are shown).
 * <li> When it appears inside another preference hierarchy, it is shown and
 * serves as the gateway to another screen of preferences (either by showing
 * another screen of preferences as a {@link Dialog} or via a
 * {@link Context#startActivity(android.content.Intent)} from the
 * {@link Preference#getIntent()}). The children of this {@link PreferenceScreen}
 * are NOT shown in the screen that this {@link PreferenceScreen} is shown in.
 * Instead, a separate screen will be shown when this preference is clicked.
 * </ul>
 * <p>Here's an example XML layout of a PreferenceScreen:</p>
 * <pre>
&lt;PreferenceScreen
        xmlns:android="http://schemas.android.com/apk/res/android"
        android:key="first_preferencescreen"&gt;
    &lt;CheckBoxPreference
            android:key="wifi enabled"
            android:title="WiFi" /&gt;
    &lt;PreferenceScreen
            android:key="second_preferencescreen"
            android:title="WiFi settings"&gt;
        &lt;CheckBoxPreference
                android:key="prefer wifi"
                android:title="Prefer WiFi" /&gt;
        ... other preferences here ...
    &lt;/PreferenceScreen&gt;
&lt;/PreferenceScreen&gt; </pre>
 * <p>
 * In this example, the "first_preferencescreen" will be used as the root of the
 * hierarchy and given to a {@link PreferenceActivity}. The first screen will
 * show preferences "WiFi" (which can be used to quickly enable/disable WiFi)
 * and "WiFi settings". The "WiFi settings" is the "second_preferencescreen" and when
 * clicked will show another screen of preferences such as "Prefer WiFi" (and
 * the other preferences that are children of the "second_preferencescreen" tag).
 * 
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about building a settings UI with Preferences,
 * read the <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>
 * guide.</p>
 * </div>
 *
 * @see PreferenceCategory
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public final class PreferenceScreen extends PreferenceGroup implements AdapterView.OnItemClickListener,
        DialogInterface.OnDismissListener {

    @UnsupportedAppUsage
    private ListAdapter mRootAdapter;
    
    private Dialog mDialog;

    @UnsupportedAppUsage
    private ListView mListView;

    private int mLayoutResId = com.android.internal.R.layout.preference_list_fragment;
    private Drawable mDividerDrawable;
    private boolean mDividerSpecified;

    /**
     * Do NOT use this constructor, use {@link PreferenceManager#createPreferenceScreen(Context)}.
     * @hide-
     */
    @UnsupportedAppUsage
    public PreferenceScreen(Context context, AttributeSet attrs) {
        super(context, attrs, com.android.internal.R.attr.preferenceScreenStyle);

        TypedArray a = context.obtainStyledAttributes(null,
                com.android.internal.R.styleable.PreferenceScreen,
                com.android.internal.R.attr.preferenceScreenStyle,
                0);

        mLayoutResId = a.getResourceId(
                com.android.internal.R.styleable.PreferenceScreen_screenLayout,
                mLayoutResId);
        if (a.hasValueOrEmpty(com.android.internal.R.styleable.PreferenceScreen_divider)) {
            mDividerDrawable =
                    a.getDrawable(com.android.internal.R.styleable.PreferenceScreen_divider);
            mDividerSpecified = true;
        }

        a.recycle();
    }

    /**
     * Returns an adapter that can be attached to a {@link PreferenceActivity}
     * or {@link PreferenceFragment} to show the preferences contained in this
     * {@link PreferenceScreen}.
     * <p>
     * This {@link PreferenceScreen} will NOT appear in the returned adapter, instead
     * it appears in the hierarchy above this {@link PreferenceScreen}.
     * <p>
     * This adapter's {@link Adapter#getItem(int)} should always return a
     * subclass of {@link Preference}.
     * 
     * @return An adapter that provides the {@link Preference} contained in this
     *         {@link PreferenceScreen}.
     */
    public ListAdapter getRootAdapter() {
        if (mRootAdapter == null) {
            mRootAdapter = onCreateRootAdapter();
        }
        
        return mRootAdapter;
    }
    
    /**
     * Creates the root adapter.
     * 
     * @return An adapter that contains the preferences contained in this {@link PreferenceScreen}.
     * @see #getRootAdapter()
     */
    protected ListAdapter onCreateRootAdapter() {
        return new PreferenceGroupAdapter(this);
    }

    /**
     * Binds a {@link ListView} to the preferences contained in this {@link PreferenceScreen} via
     * {@link #getRootAdapter()}. It also handles passing list item clicks to the corresponding
     * {@link Preference} contained by this {@link PreferenceScreen}.
     * 
     * @param listView The list view to attach to.
     */
    public void bind(ListView listView) {
        listView.setOnItemClickListener(this);
        listView.setAdapter(getRootAdapter());
        
        onAttachedToActivity();
    }
    
    @Override
    protected void onClick() {
        if (getIntent() != null || getFragment() != null || getPreferenceCount() == 0) {
            return;
        }
        
        showDialog(null);
    }
    
    private void showDialog(Bundle state) {
        Context context = getContext();
        if (mListView != null) {
            mListView.setAdapter(null);
        }

        LayoutInflater inflater = (LayoutInflater)
                context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View childPrefScreen = inflater.inflate(mLayoutResId, null);
        View titleView = childPrefScreen.findViewById(android.R.id.title);
        mListView = (ListView) childPrefScreen.findViewById(android.R.id.list);
        if (mDividerSpecified) {
            mListView.setDivider(mDividerDrawable);
        }

        bind(mListView);

        // Set the title bar if title is available, else no title bar
        final CharSequence title = getTitle();
        Dialog dialog = mDialog = new Dialog(context, context.getThemeResId());
        if (TextUtils.isEmpty(title)) {
            if (titleView != null) {
                titleView.setVisibility(View.GONE);
            }
            dialog.getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        } else {
            if (titleView instanceof TextView) {
                ((TextView) titleView).setText(title);
                titleView.setVisibility(View.VISIBLE);
            } else {
                dialog.setTitle(title);
            }
        }
        dialog.setContentView(childPrefScreen);
        dialog.setOnDismissListener(this);
        if (state != null) {
            dialog.onRestoreInstanceState(state);
        }

        // Add the screen to the list of preferences screens opened as dialogs
        getPreferenceManager().addPreferencesScreen(dialog);
        
        dialog.show();
    }
    
    public void onDismiss(DialogInterface dialog) {
        mDialog = null;
        getPreferenceManager().removePreferencesScreen(dialog);
    }
    
    /**
     * Used to get a handle to the dialog. 
     * This is useful for cases where we want to manipulate the dialog
     * as we would with any other activity or view.
     */
    public Dialog getDialog() {
        return mDialog;
    }

    public void onItemClick(AdapterView parent, View view, int position, long id) {
        // If the list has headers, subtract them from the index.
        if (parent instanceof ListView) {
            position -= ((ListView) parent).getHeaderViewsCount();
        }
        Object item = getRootAdapter().getItem(position);
        if (!(item instanceof Preference)) return;

        final Preference preference = (Preference) item; 
        preference.performClick(this);
    }

    @Override
    protected boolean isOnSameScreenAsChildren() {
        return false;
    }
    
    @Override
    protected Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        final Dialog dialog = mDialog;
        if (dialog == null || !dialog.isShowing()) {
            return superState;
        }
        
        final SavedState myState = new SavedState(superState);
        myState.isDialogShowing = true;
        myState.dialogBundle = dialog.onSaveInstanceState();
        return myState;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }
         
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        if (myState.isDialogShowing) {
            showDialog(myState.dialogBundle);
        }
    }
    
    private static class SavedState extends BaseSavedState {
        boolean isDialogShowing;
        Bundle dialogBundle;
        
        public SavedState(Parcel source) {
            super(source);
            isDialogShowing = source.readInt() == 1;
            dialogBundle = source.readBundle();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(isDialogShowing ? 1 : 0);
            dest.writeBundle(dialogBundle);
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
    
}
