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
      if (isAvailable()) {
          if (this instanceof Preference.OnPreferenceChangeListener) {
              final Preference preference = screen.findPreference(getPreferenceKey());
              preference.setOnPreferenceChangeListener(
                      (Preference.OnPreferenceChangeListener) this);
          }
      } else {
          removePreference(screen, getPreferenceKey());
      }
  }

  /**
   * Updates the current status of preference (summary, switch state, etc)
   */
  public void updateState(Preference preference) {

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
   * Removes preference from screen.
   */
  protected final void removePreference(PreferenceScreen screen, String key) {
      findAndRemovePreference(screen, key);
  }

  // finds the preference recursively and removes it from its parent
  private boolean findAndRemovePreference(PreferenceGroup prefGroup, String key) {
      final int preferenceCount = prefGroup.getPreferenceCount();
      for (int i = 0; i < preferenceCount; i++) {
          final Preference preference = prefGroup.getPreference(i);
          final String curKey = preference.getKey();

          if (curKey != null && curKey.equals(key)) {
              return prefGroup.removePreference(preference);
          }

          if (preference instanceof PreferenceGroup) {
              if (findAndRemovePreference((PreferenceGroup) preference, key)) {
                  return true;
              }
          }
      }
      return false;
  }

}
