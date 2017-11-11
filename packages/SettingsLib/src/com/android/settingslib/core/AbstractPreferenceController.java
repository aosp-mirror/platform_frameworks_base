package com.android.settingslib.core;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.os.BuildCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceScreen;

/**
 * A controller that manages event for preference.
 */
public abstract class AbstractPreferenceController {

    private static final String TAG = "AbstractPrefController";

    protected final Context mContext;
    private final DevicePolicyManager mDevicePolicyManager;

    public AbstractPreferenceController(Context context) {
        mContext = context;
        mDevicePolicyManager =
                (DevicePolicyManager) mContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    /**
     * Displays preference in this controller.
     */
    public void displayPreference(PreferenceScreen screen) {
        final String prefKey = getPreferenceKey();
        if (TextUtils.isEmpty(prefKey)) {
            Log.w(TAG, "Skipping displayPreference because key is empty:" + getClass().getName());
            return;
        }
        if (isAvailable()) {
            setVisible(screen, prefKey, true /* visible */);
            if (this instanceof Preference.OnPreferenceChangeListener) {
                final Preference preference = screen.findPreference(prefKey);
                if (preference != null) {
                    preference.setOnPreferenceChangeListener(
                        (Preference.OnPreferenceChangeListener) this);
                }
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

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    protected void replaceEnterpriseStringTitle(PreferenceScreen screen,
            String preferenceKey, String overrideKey, int resource) {
        if (!BuildCompat.isAtLeastT() || mDevicePolicyManager == null) {
            return;
        }

        Preference preference = screen.findPreference(preferenceKey);
        if (preference == null) {
            Log.d(TAG, "Could not find enterprise preference " + preferenceKey);
            return;
        }

        preference.setTitle(
                mDevicePolicyManager.getResources().getString(overrideKey,
                        () -> mContext.getString(resource)));
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    protected void replaceEnterpriseStringSummary(
            PreferenceScreen screen, String preferenceKey, String overrideKey, int resource) {
        if (!BuildCompat.isAtLeastT() || mDevicePolicyManager == null) {
            return;
        }

        Preference preference = screen.findPreference(preferenceKey);
        if (preference == null) {
            Log.d(TAG, "Could not find enterprise preference " + preferenceKey);
            return;
        }

        preference.setSummary(
                mDevicePolicyManager.getResources().getString(overrideKey,
                        () -> mContext.getString(resource)));
    }
}
