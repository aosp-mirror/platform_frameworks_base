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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import android.content.Context;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.AttributeSet;

/**
 * A container for multiple
 * {@link Preference} objects. It is a base class for  Preference objects that are
 * parents, such as {@link PreferenceCategory} and {@link PreferenceScreen}.
 * 
 * <div class="special reference">
 * <h3>Developer Guides</h3>
 * <p>For information about building a settings UI with Preferences,
 * read the <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>
 * guide.</p>
 * </div>
 * 
 * @attr ref android.R.styleable#PreferenceGroup_orderingFromXml
 */
public abstract class PreferenceGroup extends Preference implements GenericInflater.Parent<Preference> {
    /**
     * The container for child {@link Preference}s. This is sorted based on the
     * ordering, please use {@link #addPreference(Preference)} instead of adding
     * to this directly.
     */
    private List<Preference> mPreferenceList;

    private boolean mOrderingAsAdded = true;

    private int mCurrentPreferenceOrder = 0;

    private boolean mAttachedToActivity = false;
    
    public PreferenceGroup(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mPreferenceList = new ArrayList<Preference>();

        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.PreferenceGroup, defStyle, 0);
        mOrderingAsAdded = a.getBoolean(com.android.internal.R.styleable.PreferenceGroup_orderingFromXml,
                mOrderingAsAdded);
        a.recycle();
    }

    public PreferenceGroup(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * Whether to order the {@link Preference} children of this group as they
     * are added. If this is false, the ordering will follow each Preference
     * order and default to alphabetic for those without an order.
     * <p>
     * If this is called after preferences are added, they will not be
     * re-ordered in the order they were added, hence call this method early on.
     * 
     * @param orderingAsAdded Whether to order according to the order added.
     * @see Preference#setOrder(int)
     */
    public void setOrderingAsAdded(boolean orderingAsAdded) {
        mOrderingAsAdded = orderingAsAdded;
    }

    /**
     * Whether this group is ordering preferences in the order they are added.
     * 
     * @return Whether this group orders based on the order the children are added.
     * @see #setOrderingAsAdded(boolean)
     */
    public boolean isOrderingAsAdded() {
        return mOrderingAsAdded;
    }

    /**
     * Called by the inflater to add an item to this group.
     */
    public void addItemFromInflater(Preference preference) {
        addPreference(preference);
    }

    /**
     * Returns the number of children {@link Preference}s.
     * @return The number of preference children in this group.
     */
    public int getPreferenceCount() {
        return mPreferenceList.size();
    }

    /**
     * Returns the {@link Preference} at a particular index.
     * 
     * @param index The index of the {@link Preference} to retrieve.
     * @return The {@link Preference}.
     */
    public Preference getPreference(int index) {
        return mPreferenceList.get(index);
    }

    /**
     * Adds a {@link Preference} at the correct position based on the
     * preference's order.
     * 
     * @param preference The preference to add.
     * @return Whether the preference is now in this group.
     */
    public boolean addPreference(Preference preference) {
        if (mPreferenceList.contains(preference)) {
            // Exists
            return true;
        }
        
        if (preference.getOrder() == Preference.DEFAULT_ORDER) {
            if (mOrderingAsAdded) {
                preference.setOrder(mCurrentPreferenceOrder++);
            }

            if (preference instanceof PreferenceGroup) {
                // TODO: fix (method is called tail recursively when inflating,
                // so we won't end up properly passing this flag down to children
                ((PreferenceGroup)preference).setOrderingAsAdded(mOrderingAsAdded);
            }
        }

        int insertionIndex = Collections.binarySearch(mPreferenceList, preference);
        if (insertionIndex < 0) {
            insertionIndex = insertionIndex * -1 - 1;
        }

        if (!onPrepareAddPreference(preference)) {
            return false;
        }

        synchronized(this) {
            mPreferenceList.add(insertionIndex, preference);
        }

        preference.onAttachedToHierarchy(getPreferenceManager());
        
        if (mAttachedToActivity) {
            preference.onAttachedToActivity();
        }
        
        notifyHierarchyChanged();

        return true;
    }

    /**
     * Removes a {@link Preference} from this group.
     * 
     * @param preference The preference to remove.
     * @return Whether the preference was found and removed.
     */
    public boolean removePreference(Preference preference) {
        final boolean returnValue = removePreferenceInt(preference);
        notifyHierarchyChanged();
        return returnValue;
    }

    private boolean removePreferenceInt(Preference preference) {
        synchronized(this) {
            preference.onPrepareForRemoval();
            return mPreferenceList.remove(preference);
        }
    }
    
    /**
     * Removes all {@link Preference Preferences} from this group.
     */
    public void removeAll() {
        synchronized(this) {
            List<Preference> preferenceList = mPreferenceList;
            for (int i = preferenceList.size() - 1; i >= 0; i--) {
                removePreferenceInt(preferenceList.get(0));
            }
        }
        notifyHierarchyChanged();
    }
    
    /**
     * Prepares a {@link Preference} to be added to the group.
     * 
     * @param preference The preference to add.
     * @return Whether to allow adding the preference (true), or not (false).
     */
    protected boolean onPrepareAddPreference(Preference preference) {
        if (!super.isEnabled()) {
            preference.setEnabled(false);
        }
        
        return true;
    }

    /**
     * Finds a {@link Preference} based on its key. If two {@link Preference}
     * share the same key (not recommended), the first to appear will be
     * returned (to retrieve the other preference with the same key, call this
     * method on the first preference). If this preference has the key, it will
     * not be returned.
     * <p>
     * This will recursively search for the preference into children that are
     * also {@link PreferenceGroup PreferenceGroups}.
     * 
     * @param key The key of the preference to retrieve.
     * @return The {@link Preference} with the key, or null.
     */
    public Preference findPreference(CharSequence key) {
        if (TextUtils.equals(getKey(), key)) {
            return this;
        }
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            final Preference preference = getPreference(i);
            final String curKey = preference.getKey();

            if (curKey != null && curKey.equals(key)) {
                return preference;
            }
            
            if (preference instanceof PreferenceGroup) {
                final Preference returnedPreference = ((PreferenceGroup)preference)
                        .findPreference(key);
                if (returnedPreference != null) {
                    return returnedPreference;
                }
            }
        }

        return null;
    }

    /**
     * Whether this preference group should be shown on the same screen as its
     * contained preferences.
     * 
     * @return True if the contained preferences should be shown on the same
     *         screen as this preference.
     */
    protected boolean isOnSameScreenAsChildren() {
        return true;
    }
    
    @Override
    protected void onAttachedToActivity() {
        super.onAttachedToActivity();

        // Mark as attached so if a preference is later added to this group, we
        // can tell it we are already attached
        mAttachedToActivity = true;
        
        // Dispatch to all contained preferences
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            getPreference(i).onAttachedToActivity();
        }
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        
        // We won't be attached to the activity anymore
        mAttachedToActivity = false;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        
        // Dispatch to all contained preferences
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            getPreference(i).setEnabled(enabled);
        }
    }
    
    void sortPreferences() {
        synchronized (this) {
            Collections.sort(mPreferenceList);
        }
    }

    @Override
    protected void dispatchSaveInstanceState(Bundle container) {
        super.dispatchSaveInstanceState(container);

        // Dispatch to all contained preferences
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            getPreference(i).dispatchSaveInstanceState(container);
        }
    }
    
    @Override
    protected void dispatchRestoreInstanceState(Bundle container) {
        super.dispatchRestoreInstanceState(container);

        // Dispatch to all contained preferences
        final int preferenceCount = getPreferenceCount();
        for (int i = 0; i < preferenceCount; i++) {
            getPreference(i).dispatchRestoreInstanceState(container);
        }
    }

}
