package com.android.settingslib.core;

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceGroup;
import android.support.v7.preference.PreferenceScreen;

/**
 * A controller that manages event for preference.
 */
public abstract class AbstractPreferenceController {

    protected final Context mContext;

    public AbstractPreferenceController(Context context) {
        mContext = context;
    }

    /**
     * Displays preference in this controller.
     */
    public void displayPreference(PreferenceScreen screen) {
        final String prefKey = getPreferenceKey();
        if (isAvailable()) {
            setVisible(screen, prefKey, true /* visible */);
            if (this instanceof Preference.OnPreferenceChangeListener) {
                final Preference preference = screen.findPreference(prefKey);
                preference.setOnPreferenceChangeListener(
                        (Preference.OnPreferenceChangeListener) this);
            }
        } else {
            setVisible(screen, prefKey, false /* visible */);
        }
    }

    /**
     * Updates the current status of preference (summary, switch state, etc)
     */
    public void updateState(Preference preference) {
        refreshSummary(preference);
    }

    /**
     * Refresh preference summary with getSummary()
     */
    protected void refreshSummary(Preference preference) {
        if (preference == null) {
            return;
        }
        final CharSequence summary = getSummary();
        if (summary == null) {
            // Default getSummary returns null. If subclass didn't override this, there is nothing
            // we need to do.
            return;
        }
        preference.setSummary(summary);
    }

    /**
     * Returns true if preference is available (should be displayed)
     */
    public abstract boolean isAvailable();

    /**
     * Handles preference tree click
     *
     * @param preference the preference being clicked
     * @return true if click is handled
     */
    public boolean handlePreferenceTreeClick(Preference preference) {
        return false;
    }

    /**
     * Returns the key for this preference.
     */
    public abstract String getPreferenceKey();

    /**
     * Show/hide a preference.
     */
    protected final void setVisible(PreferenceGroup group, String key, boolean isVisible) {
        final Preference pref = group.findPreference(key);
        if (pref != null) {
            pref.setVisible(isVisible);
        }
    }


    /**
     * @return a {@link CharSequence} for the summary of the preference.
     */
    public CharSequence getSummary() {
        return null;
    }
}
