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

import android.annotation.CallSuper;
import com.android.internal.util.CharSequences;

import android.annotation.DrawableRes;
import android.annotation.LayoutRes;
import android.annotation.StringRes;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.AbsSavedState;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Represents the basic Preference UI building
 * block displayed by a {@link PreferenceActivity} in the form of a
 * {@link ListView}. This class provides the {@link View} to be displayed in
 * the activity and associates with a {@link SharedPreferences} to
 * store/retrieve the preference data.
 * <p>
 * When specifying a preference hierarchy in XML, each element can point to a
 * subclass of {@link Preference}, similar to the view hierarchy and layouts.
 * <p>
 * This class contains a {@code key} that will be used as the key into the
 * {@link SharedPreferences}. It is up to the subclass to decide how to store
 * the value.
 * 
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about building a settings UI with Preferences,
 * read the <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>
 * guide.</p>
 * </div>
 * 
 * @attr ref android.R.styleable#Preference_icon
 * @attr ref android.R.styleable#Preference_key
 * @attr ref android.R.styleable#Preference_title
 * @attr ref android.R.styleable#Preference_summary
 * @attr ref android.R.styleable#Preference_order
 * @attr ref android.R.styleable#Preference_fragment
 * @attr ref android.R.styleable#Preference_layout
 * @attr ref android.R.styleable#Preference_widgetLayout
 * @attr ref android.R.styleable#Preference_enabled
 * @attr ref android.R.styleable#Preference_selectable
 * @attr ref android.R.styleable#Preference_dependency
 * @attr ref android.R.styleable#Preference_persistent
 * @attr ref android.R.styleable#Preference_defaultValue
 * @attr ref android.R.styleable#Preference_shouldDisableView
 */
public class Preference implements Comparable<Preference> {
    /**
     * Specify for {@link #setOrder(int)} if a specific order is not required.
     */
    public static final int DEFAULT_ORDER = Integer.MAX_VALUE;

    private Context mContext;
    private PreferenceManager mPreferenceManager;
    
    /**
     * Set when added to hierarchy since we need a unique ID within that
     * hierarchy.
     */
    private long mId;
    
    private OnPreferenceChangeListener mOnChangeListener;
    private OnPreferenceClickListener mOnClickListener;

    private int mOrder = DEFAULT_ORDER;
    private CharSequence mTitle;
    private int mTitleRes;
    private CharSequence mSummary;
    /**
     * mIconResId is overridden by mIcon, if mIcon is specified.
     */
    private int mIconResId;
    private Drawable mIcon;
    private String mKey;
    private Intent mIntent;
    private String mFragment;
    private Bundle mExtras;
    private boolean mEnabled = true;
    private boolean mSelectable = true;
    private boolean mRequiresKey;
    private boolean mPersistent = true;
    private String mDependencyKey;
    private Object mDefaultValue;
    private boolean mDependencyMet = true;
    private boolean mParentDependencyMet = true;
    
    /**
     * @see #setShouldDisableView(boolean)
     */
    private boolean mShouldDisableView = true;
    
    private int mLayoutResId = com.android.internal.R.layout.preference;
    private int mWidgetLayoutResId;
    private boolean mCanRecycleLayout = true;
    
    private OnPreferenceChangeInternalListener mListener;
    
    private List<Preference> mDependents;
    
    private boolean mBaseMethodCalled;
    
    /**
     * Interface definition for a callback to be invoked when the value of this
     * {@link Preference} has been changed by the user and is
     * about to be set and/or persisted.  This gives the client a chance
     * to prevent setting and/or persisting the value.
     */
    public interface OnPreferenceChangeListener {
        /**
         * Called when a Preference has been changed by the user. This is
         * called before the state of the Preference is about to be updated and
         * before the state is persisted.
         * 
         * @param preference The changed Preference.
         * @param newValue The new value of the Preference.
         * @return True to update the state of the Preference with the new value.
         */
        boolean onPreferenceChange(Preference preference, Object newValue);
    }

    /**
     * Interface definition for a callback to be invoked when a {@link Preference} is
     * clicked.
     */
    public interface OnPreferenceClickListener {
        /**
         * Called when a Preference has been clicked.
         *
         * @param preference The Preference that was clicked.
         * @return True if the click was handled.
         */
        boolean onPreferenceClick(Preference preference);
    }

    /**
     * Interface definition for a callback to be invoked when this
     * {@link Preference} is changed or, if this is a group, there is an
     * addition/removal of {@link Preference}(s). This is used internally.
     */
    interface OnPreferenceChangeInternalListener {
        /**
         * Called when this Preference has changed.
         * 
         * @param preference This preference.
         */
        void onPreferenceChange(Preference preference);
        
        /**
         * Called when this group has added/removed {@link Preference}(s).
         * 
         * @param preference This Preference.
         */
        void onPreferenceHierarchyChange(Preference preference);
    }

    /**
     * Perform inflation from XML and apply a class-specific base style. This
     * constructor of Preference allows subclasses to use their own base style
     * when they are inflating. For example, a {@link CheckBoxPreference}
     * constructor calls this version of the super class constructor and
     * supplies {@code android.R.attr.checkBoxPreferenceStyle} for
     * <var>defStyleAttr</var>. This allows the theme's checkbox preference
     * style to modify all of the base preference attributes as well as the
     * {@link CheckBoxPreference} class's attributes.
     *
     * @param context The Context this is associated with, through which it can
     *            access the current theme, resources,
     *            {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the
     *            preference.
     * @param defStyleAttr An attribute in the current theme that contains a
     *            reference to a style resource that supplies default values for
     *            the view. Can be 0 to not look for defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *            supplies default values for the view, used only if
     *            defStyleAttr is 0 or can not be found in the theme. Can be 0
     *            to not look for defaults.
     * @see #Preference(Context, AttributeSet)
     */
    public Preference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        mContext = context;

        final TypedArray a = context.obtainStyledAttributes(
                attrs, com.android.internal.R.styleable.Preference, defStyleAttr, defStyleRes);
        for (int i = a.getIndexCount() - 1; i >= 0; i--) {
            int attr = a.getIndex(i); 
            switch (attr) {
                case com.android.internal.R.styleable.Preference_icon:
                    mIconResId = a.getResourceId(attr, 0);
                    break;

                case com.android.internal.R.styleable.Preference_key:
                    mKey = a.getString(attr);
                    break;
                    
                case com.android.internal.R.styleable.Preference_title:
                    mTitleRes = a.getResourceId(attr, 0);
                    mTitle = a.getString(attr);
                    break;
                    
                case com.android.internal.R.styleable.Preference_summary:
                    mSummary = a.getString(attr);
                    break;
                    
                case com.android.internal.R.styleable.Preference_order:
                    mOrder = a.getInt(attr, mOrder);
                    break;

                case com.android.internal.R.styleable.Preference_fragment:
                    mFragment = a.getString(attr);
                    break;

                case com.android.internal.R.styleable.Preference_layout:
                    mLayoutResId = a.getResourceId(attr, mLayoutResId);
                    break;

                case com.android.internal.R.styleable.Preference_widgetLayout:
                    mWidgetLayoutResId = a.getResourceId(attr, mWidgetLayoutResId);
                    break;
                    
                case com.android.internal.R.styleable.Preference_enabled:
                    mEnabled = a.getBoolean(attr, true);
                    break;
                    
                case com.android.internal.R.styleable.Preference_selectable:
                    mSelectable = a.getBoolean(attr, true);
                    break;
                    
                case com.android.internal.R.styleable.Preference_persistent:
                    mPersistent = a.getBoolean(attr, mPersistent);
                    break;
                    
                case com.android.internal.R.styleable.Preference_dependency:
                    mDependencyKey = a.getString(attr);
                    break;
                    
                case com.android.internal.R.styleable.Preference_defaultValue:
                    mDefaultValue = onGetDefaultValue(a, attr);
                    break;
                    
                case com.android.internal.R.styleable.Preference_shouldDisableView:
                    mShouldDisableView = a.getBoolean(attr, mShouldDisableView);
                    break;
            }
        }
        a.recycle();

        if (!getClass().getName().startsWith("android.preference")
                && !getClass().getName().startsWith("com.android")) {
            // For non-framework subclasses, assume the worst and don't cache views.
            mCanRecycleLayout = false;
        }
    }

    /**
     * Perform inflation from XML and apply a class-specific base style. This
     * constructor of Preference allows subclasses to use their own base style
     * when they are inflating. For example, a {@link CheckBoxPreference}
     * constructor calls this version of the super class constructor and
     * supplies {@code android.R.attr.checkBoxPreferenceStyle} for
     * <var>defStyleAttr</var>. This allows the theme's checkbox preference
     * style to modify all of the base preference attributes as well as the
     * {@link CheckBoxPreference} class's attributes.
     *
     * @param context The Context this is associated with, through which it can
     *            access the current theme, resources,
     *            {@link SharedPreferences}, etc.
     * @param attrs The attributes of the XML tag that is inflating the
     *            preference.
     * @param defStyleAttr An attribute in the current theme that contains a
     *            reference to a style resource that supplies default values for
     *            the view. Can be 0 to not look for defaults.
     * @see #Preference(Context, AttributeSet)
     */
    public Preference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }
    
    /**
     * Constructor that is called when inflating a Preference from XML. This is
     * called when a Preference is being constructed from an XML file, supplying
     * attributes that were specified in the XML file. This version uses a
     * default style of 0, so the only attribute values applied are those in the
     * Context's Theme and the given AttributeSet.
     * 
     * @param context The Context this is associated with, through which it can
     *            access the current theme, resources, {@link SharedPreferences},
     *            etc.
     * @param attrs The attributes of the XML tag that is inflating the
     *            preference.
     * @see #Preference(Context, AttributeSet, int)
     */
    public Preference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.preferenceStyle);
    }

    /**
     * Constructor to create a Preference.
     * 
     * @param context The Context in which to store Preference values.
     */
    public Preference(Context context) {
        this(context, null);
    }

    /**
     * Called when a Preference is being inflated and the default value
     * attribute needs to be read. Since different Preference types have
     * different value types, the subclass should get and return the default
     * value which will be its value type.
     * <p>
     * For example, if the value type is String, the body of the method would
     * proxy to {@link TypedArray#getString(int)}.
     * 
     * @param a The set of attributes.
     * @param index The index of the default value attribute.
     * @return The default value of this preference type.
     */
    protected Object onGetDefaultValue(TypedArray a, int index) {
        return null;
    }
    
    /**
     * Sets an {@link Intent} to be used for
     * {@link Context#startActivity(Intent)} when this Preference is clicked.
     * 
     * @param intent The intent associated with this Preference.
     */
    public void setIntent(Intent intent) {
        mIntent = intent;
    }
    
    /**
     * Return the {@link Intent} associated with this Preference.
     * 
     * @return The {@link Intent} last set via {@link #setIntent(Intent)} or XML. 
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Sets the class name of a fragment to be shown when this Preference is clicked.
     *
     * @param fragment The class name of the fragment associated with this Preference.
     */
    public void setFragment(String fragment) {
        mFragment = fragment;
    }

    /**
     * Return the fragment class name associated with this Preference.
     *
     * @return The fragment class name last set via {@link #setFragment} or XML.
     */
    public String getFragment() {
        return mFragment;
    }

    /**
     * Return the extras Bundle object associated with this preference, creating
     * a new Bundle if there currently isn't one.  You can use this to get and
     * set individual extra key/value pairs.
     */
    public Bundle getExtras() {
        if (mExtras == null) {
            mExtras = new Bundle();
        }
        return mExtras;
    }

    /**
     * Return the extras Bundle object associated with this preference,
     * returning null if there is not currently one.
     */
    public Bundle peekExtras() {
        return mExtras;
    }

    /**
     * Sets the layout resource that is inflated as the {@link View} to be shown
     * for this Preference. In most cases, the default layout is sufficient for
     * custom Preference objects and only the widget layout needs to be changed.
     * <p>
     * This layout should contain a {@link ViewGroup} with ID
     * {@link android.R.id#widget_frame} to be the parent of the specific widget
     * for this Preference. It should similarly contain
     * {@link android.R.id#title} and {@link android.R.id#summary}.
     * 
     * @param layoutResId The layout resource ID to be inflated and returned as
     *            a {@link View}.
     * @see #setWidgetLayoutResource(int)
     */
    public void setLayoutResource(@LayoutRes int layoutResId) {
        if (layoutResId != mLayoutResId) {
            // Layout changed
            mCanRecycleLayout = false;
        }

        mLayoutResId = layoutResId;
    }
    
    /**
     * Gets the layout resource that will be shown as the {@link View} for this Preference.
     * 
     * @return The layout resource ID.
     */
    @LayoutRes
    public int getLayoutResource() {
        return mLayoutResId;
    }
    
    /**
     * Sets the layout for the controllable widget portion of this Preference. This
     * is inflated into the main layout. For example, a {@link CheckBoxPreference}
     * would specify a custom layout (consisting of just the CheckBox) here,
     * instead of creating its own main layout.
     * 
     * @param widgetLayoutResId The layout resource ID to be inflated into the
     *            main layout.
     * @see #setLayoutResource(int)
     */
    public void setWidgetLayoutResource(@LayoutRes int widgetLayoutResId) {
        if (widgetLayoutResId != mWidgetLayoutResId) {
            // Layout changed
            mCanRecycleLayout = false;
        }
        mWidgetLayoutResId = widgetLayoutResId;
    }

    /**
     * Gets the layout resource for the controllable widget portion of this Preference.
     * 
     * @return The layout resource ID.
     */
    @LayoutRes
    public int getWidgetLayoutResource() {
        return mWidgetLayoutResId;
    }
    
    /**
     * Gets the View that will be shown in the {@link PreferenceActivity}.
     * 
     * @param convertView The old View to reuse, if possible. Note: You should
     *            check that this View is non-null and of an appropriate type
     *            before using. If it is not possible to convert this View to
     *            display the correct data, this method can create a new View.
     * @param parent The parent that this View will eventually be attached to.
     * @return Returns the same Preference object, for chaining multiple calls
     *         into a single statement.
     * @see #onCreateView(ViewGroup)
     * @see #onBindView(View)
     */
    public View getView(View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = onCreateView(parent);
        }
        onBindView(convertView);
        return convertView;
    }
    
    /**
     * Creates the View to be shown for this Preference in the
     * {@link PreferenceActivity}. The default behavior is to inflate the main
     * layout of this Preference (see {@link #setLayoutResource(int)}. If
     * changing this behavior, please specify a {@link ViewGroup} with ID
     * {@link android.R.id#widget_frame}.
     * <p>
     * Make sure to call through to the superclass's implementation.
     * 
     * @param parent The parent that this View will eventually be attached to.
     * @return The View that displays this Preference.
     * @see #onBindView(View)
     */
    @CallSuper
    protected View onCreateView(ViewGroup parent) {
        final LayoutInflater layoutInflater =
            (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        
        final View layout = layoutInflater.inflate(mLayoutResId, parent, false); 
        
        final ViewGroup widgetFrame = (ViewGroup) layout
                .findViewById(com.android.internal.R.id.widget_frame);
        if (widgetFrame != null) {
            if (mWidgetLayoutResId != 0) {
                layoutInflater.inflate(mWidgetLayoutResId, widgetFrame);
            } else {
                widgetFrame.setVisibility(View.GONE);
            }
        }
        return layout;
    }
    
    /**
     * Binds the created View to the data for this Preference.
     * <p>
     * This is a good place to grab references to custom Views in the layout and
     * set properties on them.
     * <p>
     * Make sure to call through to the superclass's implementation.
     * 
     * @param view The View that shows this Preference.
     * @see #onCreateView(ViewGroup)
     */
    @CallSuper
    protected void onBindView(View view) {
        final TextView titleView = (TextView) view.findViewById(com.android.internal.R.id.title);
        if (titleView != null) {
            final CharSequence title = getTitle();
            if (!TextUtils.isEmpty(title)) {
                titleView.setText(title);
                titleView.setVisibility(View.VISIBLE);
            } else {
                titleView.setVisibility(View.GONE);
            }
        }

        final TextView summaryView = (TextView) view.findViewById(
                com.android.internal.R.id.summary);
        if (summaryView != null) {
            final CharSequence summary = getSummary();
            if (!TextUtils.isEmpty(summary)) {
                summaryView.setText(summary);
                summaryView.setVisibility(View.VISIBLE);
            } else {
                summaryView.setVisibility(View.GONE);
            }
        }

        final ImageView imageView = (ImageView) view.findViewById(com.android.internal.R.id.icon);
        if (imageView != null) {
            if (mIconResId != 0 || mIcon != null) {
                if (mIcon == null) {
                    mIcon = getContext().getDrawable(mIconResId);
                }
                if (mIcon != null) {
                    imageView.setImageDrawable(mIcon);
                }
            }
            imageView.setVisibility(mIcon != null ? View.VISIBLE : View.GONE);
        }

        final View imageFrame = view.findViewById(com.android.internal.R.id.icon_frame);
        if (imageFrame != null) {
            imageFrame.setVisibility(mIcon != null ? View.VISIBLE : View.GONE);
        }

        if (mShouldDisableView) {
            setEnabledStateOnViews(view, isEnabled());
        }
    }

    /**
     * Makes sure the view (and any children) get the enabled state changed.
     */
    private void setEnabledStateOnViews(View v, boolean enabled) {
        v.setEnabled(enabled);
        
        if (v instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                setEnabledStateOnViews(vg.getChildAt(i), enabled);
            }
        }
    }
    
    /**
     * Sets the order of this Preference with respect to other
     * Preference objects on the same level. If this is not specified, the
     * default behavior is to sort alphabetically. The
     * {@link PreferenceGroup#setOrderingAsAdded(boolean)} can be used to order
     * Preference objects based on the order they appear in the XML.
     * 
     * @param order The order for this Preference. A lower value will be shown
     *            first. Use {@link #DEFAULT_ORDER} to sort alphabetically or
     *            allow ordering from XML.
     * @see PreferenceGroup#setOrderingAsAdded(boolean)
     * @see #DEFAULT_ORDER
     */
    public void setOrder(int order) {
        if (order != mOrder) {
            mOrder = order;

            // Reorder the list 
            notifyHierarchyChanged();
        }
    }
    
    /**
     * Gets the order of this Preference with respect to other Preference objects
     * on the same level.
     * 
     * @return The order of this Preference.
     * @see #setOrder(int)
     */
    public int getOrder() {
        return mOrder;
    }

    /**
     * Sets the title for this Preference with a CharSequence. 
     * This title will be placed into the ID
     * {@link android.R.id#title} within the View created by
     * {@link #onCreateView(ViewGroup)}.
     * 
     * @param title The title for this Preference.
     */
    public void setTitle(CharSequence title) {
        if (title == null && mTitle != null || title != null && !title.equals(mTitle)) {
            mTitleRes = 0;
            mTitle = title;
            notifyChanged();
        }
    }
    
    /**
     * Sets the title for this Preference with a resource ID. 
     * 
     * @see #setTitle(CharSequence)
     * @param titleResId The title as a resource ID.
     */
    public void setTitle(@StringRes int titleResId) {
        setTitle(mContext.getString(titleResId));
        mTitleRes = titleResId;
    }
    
    /**
     * Returns the title resource ID of this Preference.  If the title did
     * not come from a resource, 0 is returned.
     *
     * @return The title resource.
     * @see #setTitle(int)
     */
    @StringRes
    public int getTitleRes() {
        return mTitleRes;
    }

    /**
     * Returns the title of this Preference.
     * 
     * @return The title.
     * @see #setTitle(CharSequence)
     */
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * Sets the icon for this Preference with a Drawable. 
     * This icon will be placed into the ID
     * {@link android.R.id#icon} within the View created by
     * {@link #onCreateView(ViewGroup)}.
     * 
     * @param icon The optional icon for this Preference.
     */
    public void setIcon(Drawable icon) {
        if ((icon == null && mIcon != null) || (icon != null && mIcon != icon)) {
            mIcon = icon;

            notifyChanged();
        }
    }

    /**
     * Sets the icon for this Preference with a resource ID. 
     * 
     * @see #setIcon(Drawable)
     * @param iconResId The icon as a resource ID.
     */
    public void setIcon(@DrawableRes int iconResId) {
        if (mIconResId != iconResId) {
            mIconResId = iconResId;
            setIcon(mContext.getDrawable(iconResId));
        }
    }

    /**
     * Returns the icon of this Preference.
     * 
     * @return The icon.
     * @see #setIcon(Drawable)
     */
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Returns the summary of this Preference.
     * 
     * @return The summary.
     * @see #setSummary(CharSequence)
     */
    public CharSequence getSummary() {
        return mSummary;
    }

    /**
     * Sets the summary for this Preference with a CharSequence. 
     * 
     * @param summary The summary for the preference.
     */
    public void setSummary(CharSequence summary) {
        if (summary == null && mSummary != null || summary != null && !summary.equals(mSummary)) {
            mSummary = summary;
            notifyChanged();
        }
    }

    /**
     * Sets the summary for this Preference with a resource ID. 
     * 
     * @see #setSummary(CharSequence)
     * @param summaryResId The summary as a resource.
     */
    public void setSummary(@StringRes int summaryResId) {
        setSummary(mContext.getString(summaryResId));
    }
    
    /**
     * Sets whether this Preference is enabled. If disabled, it will
     * not handle clicks.
     * 
     * @param enabled Set true to enable it.
     */
    public void setEnabled(boolean enabled) {
        if (mEnabled != enabled) {
            mEnabled = enabled;

            // Enabled state can change dependent preferences' states, so notify
            notifyDependencyChange(shouldDisableDependents());

            notifyChanged();
        }
    }
    
    /**
     * Checks whether this Preference should be enabled in the list.
     * 
     * @return True if this Preference is enabled, false otherwise.
     */
    public boolean isEnabled() {
        return mEnabled && mDependencyMet && mParentDependencyMet;
    }

    /**
     * Sets whether this Preference is selectable.
     * 
     * @param selectable Set true to make it selectable.
     */
    public void setSelectable(boolean selectable) {
        if (mSelectable != selectable) {
            mSelectable = selectable;
            notifyChanged();
        }
    }
    
    /**
     * Checks whether this Preference should be selectable in the list.
     * 
     * @return True if it is selectable, false otherwise.
     */
    public boolean isSelectable() {
        return mSelectable;
    }

    /**
     * Sets whether this Preference should disable its view when it gets
     * disabled.
     * <p>
     * For example, set this and {@link #setEnabled(boolean)} to false for
     * preferences that are only displaying information and 1) should not be
     * clickable 2) should not have the view set to the disabled state.
     * 
     * @param shouldDisableView Set true if this preference should disable its view
     *            when the preference is disabled.
     */
    public void setShouldDisableView(boolean shouldDisableView) {
        mShouldDisableView = shouldDisableView;
        notifyChanged();
    }
    
    /**
     * Checks whether this Preference should disable its view when it's action is disabled.
     * @see #setShouldDisableView(boolean)
     * @return True if it should disable the view. 
     */
    public boolean getShouldDisableView() {
        return mShouldDisableView;
    }

    /**
     * Returns a unique ID for this Preference.  This ID should be unique across all
     * Preference objects in a hierarchy.
     * 
     * @return A unique ID for this Preference.
     */
    long getId() {
        return mId;
    }
    
    /**
     * Processes a click on the preference. This includes saving the value to
     * the {@link SharedPreferences}. However, the overridden method should
     * call {@link #callChangeListener(Object)} to make sure the client wants to
     * update the preference's state with the new value.
     */
    protected void onClick() {
    }
    
    /**
     * Sets the key for this Preference, which is used as a key to the
     * {@link SharedPreferences}. This should be unique for the package.
     * 
     * @param key The key for the preference.
     */
    public void setKey(String key) {
        mKey = key;
        
        if (mRequiresKey && !hasKey()) {
            requireKey();
        }
    }
    
    /**
     * Gets the key for this Preference, which is also the key used for storing
     * values into SharedPreferences.
     * 
     * @return The key.
     */
    public String getKey() {
        return mKey;
    }
    
    /**
     * Checks whether the key is present, and if it isn't throws an
     * exception. This should be called by subclasses that store preferences in
     * the {@link SharedPreferences}.
     * 
     * @throws IllegalStateException If there is no key assigned.
     */
    void requireKey() {
        if (mKey == null) {
            throw new IllegalStateException("Preference does not have a key assigned.");
        }
        
        mRequiresKey = true;
    }
    
    /**
     * Checks whether this Preference has a valid key.
     * 
     * @return True if the key exists and is not a blank string, false otherwise.
     */
    public boolean hasKey() {
        return !TextUtils.isEmpty(mKey);
    }
    
    /**
     * Checks whether this Preference is persistent. If it is, it stores its value(s) into
     * the persistent {@link SharedPreferences} storage.
     * 
     * @return True if it is persistent.
     */
    public boolean isPersistent() {
        return mPersistent;
    }
    
    /**
     * Checks whether, at the given time this method is called,
     * this Preference should store/restore its value(s) into the
     * {@link SharedPreferences}. This, at minimum, checks whether this
     * Preference is persistent and it currently has a key. Before you
     * save/restore from the {@link SharedPreferences}, check this first.
     * 
     * @return True if it should persist the value.
     */
    protected boolean shouldPersist() {
        return mPreferenceManager != null && isPersistent() && hasKey();
    }
    
    /**
     * Sets whether this Preference is persistent. When persistent,
     * it stores its value(s) into the persistent {@link SharedPreferences}
     * storage.
     * 
     * @param persistent Set true if it should store its value(s) into the {@link SharedPreferences}.
     */
    public void setPersistent(boolean persistent) {
        mPersistent = persistent;
    }
    
    /**
     * Call this method after the user changes the preference, but before the
     * internal state is set. This allows the client to ignore the user value.
     * 
     * @param newValue The new value of this Preference.
     * @return True if the user value should be set as the preference
     *         value (and persisted).
     */
    protected boolean callChangeListener(Object newValue) {
        return mOnChangeListener == null ? true : mOnChangeListener.onPreferenceChange(this, newValue);
    }
    
    /**
     * Sets the callback to be invoked when this Preference is changed by the
     * user (but before the internal state has been updated).
     * 
     * @param onPreferenceChangeListener The callback to be invoked.
     */
    public void setOnPreferenceChangeListener(OnPreferenceChangeListener onPreferenceChangeListener) {
        mOnChangeListener = onPreferenceChangeListener;
    }

    /**
     * Returns the callback to be invoked when this Preference is changed by the
     * user (but before the internal state has been updated).
     * 
     * @return The callback to be invoked.
     */
    public OnPreferenceChangeListener getOnPreferenceChangeListener() {
        return mOnChangeListener;
    }

    /**
     * Sets the callback to be invoked when this Preference is clicked.
     * 
     * @param onPreferenceClickListener The callback to be invoked.
     */
    public void setOnPreferenceClickListener(OnPreferenceClickListener onPreferenceClickListener) {
        mOnClickListener = onPreferenceClickListener;
    }

    /**
     * Returns the callback to be invoked when this Preference is clicked.
     * 
     * @return The callback to be invoked.
     */
    public OnPreferenceClickListener getOnPreferenceClickListener() {
        return mOnClickListener;
    }

    /**
     * Called when a click should be performed.
     * 
     * @param preferenceScreen A {@link PreferenceScreen} whose hierarchy click
     *            listener should be called in the proper order (between other
     *            processing). May be null.
     * @hide
     */
    public void performClick(PreferenceScreen preferenceScreen) {
        
        if (!isEnabled()) {
            return;
        }
        
        onClick();
        
        if (mOnClickListener != null && mOnClickListener.onPreferenceClick(this)) {
            return;
        }
        
        PreferenceManager preferenceManager = getPreferenceManager();
        if (preferenceManager != null) {
            PreferenceManager.OnPreferenceTreeClickListener listener = preferenceManager
                    .getOnPreferenceTreeClickListener();
            if (preferenceScreen != null && listener != null
                    && listener.onPreferenceTreeClick(preferenceScreen, this)) {
                return;
            }
        }
        
        if (mIntent != null) {
            Context context = getContext();
            context.startActivity(mIntent);
        }
    }

    /**
     * Allows a Preference to intercept key events without having focus.
     * For example, SeekBarPreference uses this to intercept +/- to adjust
     * the progress.
     * @return True if the Preference handled the key. Returns false by default.
     * @hide
     */
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }
    
    /**
     * Returns the {@link android.content.Context} of this Preference. 
     * Each Preference in a Preference hierarchy can be
     * from different Context (for example, if multiple activities provide preferences into a single
     * {@link PreferenceActivity}). This Context will be used to save the Preference values.
     * 
     * @return The Context of this Preference.
     */
    public Context getContext() {
        return mContext;
    }
    
    /**
     * Returns the {@link SharedPreferences} where this Preference can read its
     * value(s). Usually, it's easier to use one of the helper read methods:
     * {@link #getPersistedBoolean(boolean)}, {@link #getPersistedFloat(float)},
     * {@link #getPersistedInt(int)}, {@link #getPersistedLong(long)},
     * {@link #getPersistedString(String)}. To save values, see
     * {@link #getEditor()}.
     * <p>
     * In some cases, writes to the {@link #getEditor()} will not be committed
     * right away and hence not show up in the returned
     * {@link SharedPreferences}, this is intended behavior to improve
     * performance.
     * 
     * @return The {@link SharedPreferences} where this Preference reads its
     *         value(s), or null if it isn't attached to a Preference hierarchy.
     * @see #getEditor()
     */
    public SharedPreferences getSharedPreferences() {
        if (mPreferenceManager == null) {
            return null;
        }
        
        return mPreferenceManager.getSharedPreferences();
    }
    
    /**
     * Returns an {@link SharedPreferences.Editor} where this Preference can
     * save its value(s). Usually it's easier to use one of the helper save
     * methods: {@link #persistBoolean(boolean)}, {@link #persistFloat(float)},
     * {@link #persistInt(int)}, {@link #persistLong(long)},
     * {@link #persistString(String)}. To read values, see
     * {@link #getSharedPreferences()}. If {@link #shouldCommit()} returns
     * true, it is this Preference's responsibility to commit.
     * <p>
     * In some cases, writes to this will not be committed right away and hence
     * not show up in the SharedPreferences, this is intended behavior to
     * improve performance.
     * 
     * @return A {@link SharedPreferences.Editor} where this preference saves
     *         its value(s), or null if it isn't attached to a Preference
     *         hierarchy.
     * @see #shouldCommit()
     * @see #getSharedPreferences()
     */
    public SharedPreferences.Editor getEditor() {
        if (mPreferenceManager == null) {
            return null;
        }
        
        return mPreferenceManager.getEditor();
    }
    
    /**
     * Returns whether the {@link Preference} should commit its saved value(s) in
     * {@link #getEditor()}. This may return false in situations where batch
     * committing is being done (by the manager) to improve performance.
     * 
     * @return Whether the Preference should commit its saved value(s).
     * @see #getEditor()
     */
    public boolean shouldCommit() {
        if (mPreferenceManager == null) {
            return false;
        }
        
        return mPreferenceManager.shouldCommit();
    }
    
    /**
     * Compares Preference objects based on order (if set), otherwise alphabetically on the titles.
     * 
     * @param another The Preference to compare to this one.
     * @return 0 if the same; less than 0 if this Preference sorts ahead of <var>another</var>;
     *          greater than 0 if this Preference sorts after <var>another</var>.
     */
    @Override
    public int compareTo(Preference another) {
        if (mOrder != another.mOrder) {
            // Do order comparison
            return mOrder - another.mOrder;
        } else if (mTitle == another.mTitle) {
            // If titles are null or share same object comparison
            return 0;
        } else if (mTitle == null) {
            return 1;
        } else if (another.mTitle == null) {
            return -1;
        } else {
            // Do name comparison
            return CharSequences.compareToIgnoreCase(mTitle, another.mTitle);
        }
    }
    
    /**
     * Sets the internal change listener.
     * 
     * @param listener The listener.
     * @see #notifyChanged()
     */
    final void setOnPreferenceChangeInternalListener(OnPreferenceChangeInternalListener listener) {
        mListener = listener;
    }

    /**
     * Should be called when the data of this {@link Preference} has changed.
     */
    protected void notifyChanged() {
        if (mListener != null) {
            mListener.onPreferenceChange(this);
        }
    }
    
    /**
     * Should be called when a Preference has been
     * added/removed from this group, or the ordering should be
     * re-evaluated.
     */
    protected void notifyHierarchyChanged() {
        if (mListener != null) {
            mListener.onPreferenceHierarchyChange(this);
        }
    }

    /**
     * Gets the {@link PreferenceManager} that manages this Preference object's tree.
     * 
     * @return The {@link PreferenceManager}.
     */
    public PreferenceManager getPreferenceManager() {
        return mPreferenceManager;
    }
    
    /**
     * Called when this Preference has been attached to a Preference hierarchy.
     * Make sure to call the super implementation.
     * 
     * @param preferenceManager The PreferenceManager of the hierarchy.
     */
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        mPreferenceManager = preferenceManager;
        
        mId = preferenceManager.getNextId();
        
        dispatchSetInitialValue();
    }
    
    /**
     * Called when the Preference hierarchy has been attached to the
     * {@link PreferenceActivity}. This can also be called when this
     * Preference has been attached to a group that was already attached
     * to the {@link PreferenceActivity}.
     */
    protected void onAttachedToActivity() {
        // At this point, the hierarchy that this preference is in is connected
        // with all other preferences.
        registerDependency();
    }

    private void registerDependency() {
        
        if (TextUtils.isEmpty(mDependencyKey)) return;
        
        Preference preference = findPreferenceInHierarchy(mDependencyKey);
        if (preference != null) {
            preference.registerDependent(this);
        } else {
            throw new IllegalStateException("Dependency \"" + mDependencyKey
                    + "\" not found for preference \"" + mKey + "\" (title: \"" + mTitle + "\"");
        }
    }

    private void unregisterDependency() {
        if (mDependencyKey != null) {
            final Preference oldDependency = findPreferenceInHierarchy(mDependencyKey);
            if (oldDependency != null) {
                oldDependency.unregisterDependent(this);
            }
        }
    }
    
    /**
     * Finds a Preference in this hierarchy (the whole thing,
     * even above/below your {@link PreferenceScreen} screen break) with the given
     * key.
     * <p>
     * This only functions after we have been attached to a hierarchy.
     * 
     * @param key The key of the Preference to find.
     * @return The Preference that uses the given key.
     */
    protected Preference findPreferenceInHierarchy(String key) {
        if (TextUtils.isEmpty(key) || mPreferenceManager == null) {
            return null;
        }
        
        return mPreferenceManager.findPreference(key);
    }
    
    /**
     * Adds a dependent Preference on this Preference so we can notify it.
     * Usually, the dependent Preference registers itself (it's good for it to
     * know it depends on something), so please use
     * {@link Preference#setDependency(String)} on the dependent Preference.
     * 
     * @param dependent The dependent Preference that will be enabled/disabled
     *            according to the state of this Preference.
     */
    private void registerDependent(Preference dependent) {
        if (mDependents == null) {
            mDependents = new ArrayList<Preference>();
        }
        
        mDependents.add(dependent);
        
        dependent.onDependencyChanged(this, shouldDisableDependents());
    }
    
    /**
     * Removes a dependent Preference on this Preference.
     * 
     * @param dependent The dependent Preference that will be enabled/disabled
     *            according to the state of this Preference.
     * @return Returns the same Preference object, for chaining multiple calls
     *         into a single statement.
     */
    private void unregisterDependent(Preference dependent) {
        if (mDependents != null) {
            mDependents.remove(dependent);
        }
    }
    
    /**
     * Notifies any listening dependents of a change that affects the
     * dependency.
     * 
     * @param disableDependents Whether this Preference should disable
     *            its dependents.
     */
    public void notifyDependencyChange(boolean disableDependents) {
        final List<Preference> dependents = mDependents;
        
        if (dependents == null) {
            return;
        }
        
        final int dependentsCount = dependents.size();
        for (int i = 0; i < dependentsCount; i++) {
            dependents.get(i).onDependencyChanged(this, disableDependents);
        }
    }

    /**
     * Called when the dependency changes.
     * 
     * @param dependency The Preference that this Preference depends on.
     * @param disableDependent Set true to disable this Preference.
     */
    public void onDependencyChanged(Preference dependency, boolean disableDependent) {
        if (mDependencyMet == disableDependent) {
            mDependencyMet = !disableDependent;

            // Enabled state can change dependent preferences' states, so notify
            notifyDependencyChange(shouldDisableDependents());

            notifyChanged();
        }
    }

    /**
     * Called when the implicit parent dependency changes.
     *
     * @param parent The Preference that this Preference depends on.
     * @param disableChild Set true to disable this Preference.
     */
    public void onParentChanged(Preference parent, boolean disableChild) {
        if (mParentDependencyMet == disableChild) {
            mParentDependencyMet = !disableChild;

            // Enabled state can change dependent preferences' states, so notify
            notifyDependencyChange(shouldDisableDependents());

            notifyChanged();
        }
    }

    /**
     * Checks whether this preference's dependents should currently be
     * disabled.
     * 
     * @return True if the dependents should be disabled, otherwise false.
     */
    public boolean shouldDisableDependents() {
        return !isEnabled();
    }
    
    /**
     * Sets the key of a Preference that this Preference will depend on. If that
     * Preference is not set or is off, this Preference will be disabled.
     * 
     * @param dependencyKey The key of the Preference that this depends on.
     */
    public void setDependency(String dependencyKey) {
        // Unregister the old dependency, if we had one
        unregisterDependency();
        
        // Register the new
        mDependencyKey = dependencyKey;
        registerDependency();
    }
    
    /**
     * Returns the key of the dependency on this Preference.
     * 
     * @return The key of the dependency.
     * @see #setDependency(String)
     */
    public String getDependency() {
        return mDependencyKey;
    }
    
    /**
     * Called when this Preference is being removed from the hierarchy. You
     * should remove any references to this Preference that you know about. Make
     * sure to call through to the superclass implementation.
     */
    @CallSuper
    protected void onPrepareForRemoval() {
        unregisterDependency();
    }
    
    /**
     * Sets the default value for this Preference, which will be set either if
     * persistence is off or persistence is on and the preference is not found
     * in the persistent storage.
     * 
     * @param defaultValue The default value.
     */
    public void setDefaultValue(Object defaultValue) {
        mDefaultValue = defaultValue;
    }
    
    private void dispatchSetInitialValue() {
        // By now, we know if we are persistent.
        final boolean shouldPersist = shouldPersist();
        if (!shouldPersist || !getSharedPreferences().contains(mKey)) {
            if (mDefaultValue != null) {
                onSetInitialValue(false, mDefaultValue);
            }
        } else {
            onSetInitialValue(true, null);
        }
    }
    
    /**
     * Implement this to set the initial value of the Preference. 
     * <p>
     * If <var>restorePersistedValue</var> is true, you should restore the 
     * Preference value from the {@link android.content.SharedPreferences}. If 
     * <var>restorePersistedValue</var> is false, you should set the Preference 
     * value to defaultValue that is given (and possibly store to SharedPreferences 
     * if {@link #shouldPersist()} is true).
     * <p>
     * This may not always be called. One example is if it should not persist
     * but there is no default value given.
     * 
     * @param restorePersistedValue True to restore the persisted value;
     *            false to use the given <var>defaultValue</var>.
     * @param defaultValue The default value for this Preference. Only use this
     *            if <var>restorePersistedValue</var> is false.
     */
    protected void onSetInitialValue(boolean restorePersistedValue, Object defaultValue) {
    }

    private void tryCommit(SharedPreferences.Editor editor) {
        if (mPreferenceManager.shouldCommit()) {
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
     * Attempts to persist a String to the {@link android.content.SharedPreferences}.
     * <p>
     * This will check if this Preference is persistent, get an editor from
     * the {@link PreferenceManager}, put in the string, and check if we should commit (and
     * commit if so).
     * 
     * @param value The value to persist.
     * @return True if the Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #getPersistedString(String)
     */
    protected boolean persistString(String value) {
        if (shouldPersist()) {
            // Shouldn't store null
            if (TextUtils.equals(value, getPersistedString(null))) {
                // It's already there, so the same as persisting
                return true;
            }
            
            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putString(mKey, value);
            tryCommit(editor);
            return true;
        }
        return false;
    }
    
    /**
     * Attempts to get a persisted String from the {@link android.content.SharedPreferences}.
     * <p>
     * This will check if this Preference is persistent, get the SharedPreferences
     * from the {@link PreferenceManager}, and get the value.
     * 
     * @param defaultReturnValue The default value to return if either the
     *            Preference is not persistent or the Preference is not in the
     *            shared preferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #persistString(String)
     */
    protected String getPersistedString(String defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getString(mKey, defaultReturnValue);
    }
    
    /**
     * Attempts to persist a set of Strings to the {@link android.content.SharedPreferences}.
     * <p>
     * This will check if this Preference is persistent, get an editor from
     * the {@link PreferenceManager}, put in the strings, and check if we should commit (and
     * commit if so).
     * 
     * @param values The values to persist.
     * @return True if the Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #getPersistedStringSet(Set)
     */
    public boolean persistStringSet(Set<String> values) {
        if (shouldPersist()) {
            // Shouldn't store null
            if (values.equals(getPersistedStringSet(null))) {
                // It's already there, so the same as persisting
                return true;
            }
            
            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putStringSet(mKey, values);
            tryCommit(editor);
            return true;
        }
        return false;
    }

    /**
     * Attempts to get a persisted set of Strings from the
     * {@link android.content.SharedPreferences}.
     * <p>
     * This will check if this Preference is persistent, get the SharedPreferences
     * from the {@link PreferenceManager}, and get the value.
     * 
     * @param defaultReturnValue The default value to return if either the
     *            Preference is not persistent or the Preference is not in the
     *            shared preferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #persistStringSet(Set)
     */
    public Set<String> getPersistedStringSet(Set<String> defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getStringSet(mKey, defaultReturnValue);
    }
    
    /**
     * Attempts to persist an int to the {@link android.content.SharedPreferences}.
     * 
     * @param value The value to persist.
     * @return True if the Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #persistString(String)
     * @see #getPersistedInt(int)
     */
    protected boolean persistInt(int value) {
        if (shouldPersist()) {
            if (value == getPersistedInt(~value)) {
                // It's already there, so the same as persisting
                return true;
            }
            
            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putInt(mKey, value);
            tryCommit(editor);
            return true;
        }
        return false;
    }
    
    /**
     * Attempts to get a persisted int from the {@link android.content.SharedPreferences}.
     * 
     * @param defaultReturnValue The default value to return if either this
     *            Preference is not persistent or this Preference is not in the
     *            SharedPreferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #getPersistedString(String)
     * @see #persistInt(int)
     */
    protected int getPersistedInt(int defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getInt(mKey, defaultReturnValue);
    }
    
    /**
     * Attempts to persist a float to the {@link android.content.SharedPreferences}.
     * 
     * @param value The value to persist.
     * @return True if this Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #persistString(String)
     * @see #getPersistedFloat(float)
     */
    protected boolean persistFloat(float value) {
        if (shouldPersist()) {
            if (value == getPersistedFloat(Float.NaN)) {
                // It's already there, so the same as persisting
                return true;
            }

            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putFloat(mKey, value);
            tryCommit(editor);
            return true;
        }
        return false;
    }
    
    /**
     * Attempts to get a persisted float from the {@link android.content.SharedPreferences}.
     * 
     * @param defaultReturnValue The default value to return if either this
     *            Preference is not persistent or this Preference is not in the
     *            SharedPreferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #getPersistedString(String)
     * @see #persistFloat(float)
     */
    protected float getPersistedFloat(float defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getFloat(mKey, defaultReturnValue);
    }
    
    /**
     * Attempts to persist a long to the {@link android.content.SharedPreferences}.
     * 
     * @param value The value to persist.
     * @return True if this Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #persistString(String)
     * @see #getPersistedLong(long)
     */
    protected boolean persistLong(long value) {
        if (shouldPersist()) {
            if (value == getPersistedLong(~value)) {
                // It's already there, so the same as persisting
                return true;
            }
            
            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putLong(mKey, value);
            tryCommit(editor);
            return true;
        }
        return false;
    }
    
    /**
     * Attempts to get a persisted long from the {@link android.content.SharedPreferences}.
     * 
     * @param defaultReturnValue The default value to return if either this
     *            Preference is not persistent or this Preference is not in the
     *            SharedPreferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #getPersistedString(String)
     * @see #persistLong(long)
     */
    protected long getPersistedLong(long defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getLong(mKey, defaultReturnValue);
    }
    
    /**
     * Attempts to persist a boolean to the {@link android.content.SharedPreferences}.
     * 
     * @param value The value to persist.
     * @return True if this Preference is persistent. (This is not whether the
     *         value was persisted, since we may not necessarily commit if there
     *         will be a batch commit later.)
     * @see #persistString(String)
     * @see #getPersistedBoolean(boolean)
     */
    protected boolean persistBoolean(boolean value) {
        if (shouldPersist()) {
            if (value == getPersistedBoolean(!value)) {
                // It's already there, so the same as persisting
                return true;
            }
            
            SharedPreferences.Editor editor = mPreferenceManager.getEditor();
            editor.putBoolean(mKey, value);
            tryCommit(editor);
            return true;
        }
        return false;
    }
    
    /**
     * Attempts to get a persisted boolean from the {@link android.content.SharedPreferences}.
     * 
     * @param defaultReturnValue The default value to return if either this
     *            Preference is not persistent or this Preference is not in the
     *            SharedPreferences.
     * @return The value from the SharedPreferences or the default return
     *         value.
     * @see #getPersistedString(String)
     * @see #persistBoolean(boolean)
     */
    protected boolean getPersistedBoolean(boolean defaultReturnValue) {
        if (!shouldPersist()) {
            return defaultReturnValue;
        }
        
        return mPreferenceManager.getSharedPreferences().getBoolean(mKey, defaultReturnValue);
    }
    
    boolean canRecycleLayout() {
        return mCanRecycleLayout;
    }
    
    @Override
    public String toString() {
        return getFilterableStringBuilder().toString();
    }
        
    /**
     * Returns the text that will be used to filter this Preference depending on
     * user input.
     * <p>
     * If overridding and calling through to the superclass, make sure to prepend
     * your additions with a space.
     * 
     * @return Text as a {@link StringBuilder} that will be used to filter this
     *         preference. By default, this is the title and summary
     *         (concatenated with a space).
     */
    StringBuilder getFilterableStringBuilder() {
        StringBuilder sb = new StringBuilder();
        CharSequence title = getTitle();
        if (!TextUtils.isEmpty(title)) {
            sb.append(title).append(' ');
        }
        CharSequence summary = getSummary();
        if (!TextUtils.isEmpty(summary)) {
            sb.append(summary).append(' ');
        }
        if (sb.length() > 0) {
            // Drop the last space
            sb.setLength(sb.length() - 1);
        }
        return sb;
    }

    /**
     * Store this Preference hierarchy's frozen state into the given container.
     * 
     * @param container The Bundle in which to save the instance of this Preference.
     * 
     * @see #restoreHierarchyState
     * @see #onSaveInstanceState
     */
    public void saveHierarchyState(Bundle container) {
        dispatchSaveInstanceState(container);
    }

    /**
     * Called by {@link #saveHierarchyState} to store the instance for this Preference and its children.
     * May be overridden to modify how the save happens for children. For example, some
     * Preference objects may want to not store an instance for their children.
     * 
     * @param container The Bundle in which to save the instance of this Preference.
     * 
     * @see #saveHierarchyState
     * @see #onSaveInstanceState
     */
    void dispatchSaveInstanceState(Bundle container) {
        if (hasKey()) {
            mBaseMethodCalled = false;
            Parcelable state = onSaveInstanceState();
            if (!mBaseMethodCalled) {
                throw new IllegalStateException(
                        "Derived class did not call super.onSaveInstanceState()");
            }
            if (state != null) {
                container.putParcelable(mKey, state);
            }
        }
    }

    /**
     * Hook allowing a Preference to generate a representation of its internal
     * state that can later be used to create a new instance with that same
     * state. This state should only contain information that is not persistent
     * or can be reconstructed later.
     * 
     * @return A Parcelable object containing the current dynamic state of
     *         this Preference, or null if there is nothing interesting to save.
     *         The default implementation returns null.
     * @see #onRestoreInstanceState
     * @see #saveHierarchyState
     */
    protected Parcelable onSaveInstanceState() {
        mBaseMethodCalled = true;
        return BaseSavedState.EMPTY_STATE;
    }

    /**
     * Restore this Preference hierarchy's previously saved state from the given container.
     * 
     * @param container The Bundle that holds the previously saved state.
     * 
     * @see #saveHierarchyState
     * @see #onRestoreInstanceState
     */
    public void restoreHierarchyState(Bundle container) {
        dispatchRestoreInstanceState(container);
    }

    /**
     * Called by {@link #restoreHierarchyState} to retrieve the saved state for this
     * Preference and its children. May be overridden to modify how restoring
     * happens to the children of a Preference. For example, some Preference objects may
     * not want to save state for their children.
     * 
     * @param container The Bundle that holds the previously saved state.
     * @see #restoreHierarchyState
     * @see #onRestoreInstanceState
     */
    void dispatchRestoreInstanceState(Bundle container) {
        if (hasKey()) {
            Parcelable state = container.getParcelable(mKey);
            if (state != null) {
                mBaseMethodCalled = false;
                onRestoreInstanceState(state);
                if (!mBaseMethodCalled) {
                    throw new IllegalStateException(
                            "Derived class did not call super.onRestoreInstanceState()");
                }
            }
        }
    }

    /**
     * Hook allowing a Preference to re-apply a representation of its internal
     * state that had previously been generated by {@link #onSaveInstanceState}.
     * This function will never be called with a null state.
     * 
     * @param state The saved state that had previously been returned by
     *            {@link #onSaveInstanceState}.
     * @see #onSaveInstanceState
     * @see #restoreHierarchyState
     */
    protected void onRestoreInstanceState(Parcelable state) {
        mBaseMethodCalled = true;
        if (state != BaseSavedState.EMPTY_STATE && state != null) {
            throw new IllegalArgumentException("Wrong state class -- expecting Preference State");
        }
    }

    /**
     * A base class for managing the instance state of a {@link Preference}.
     */
    public static class BaseSavedState extends AbsSavedState {
        public BaseSavedState(Parcel source) {
            super(source);
        }

        public BaseSavedState(Parcelable superState) {
            super(superState);
        }
        
        public static final Parcelable.Creator<BaseSavedState> CREATOR =
                new Parcelable.Creator<BaseSavedState>() {
            public BaseSavedState createFromParcel(Parcel in) {
                return new BaseSavedState(in);
            }

            public BaseSavedState[] newArray(int size) {
                return new BaseSavedState[size];
            }
        };
    }

}
