package android.nfc;

import android.nfc.ComponentNameAndUser;

/**
 * @hide
 */
oneway interface INfcEventListener {
    void onPreferredServiceChanged(in ComponentNameAndUser ComponentNameAndUser);
    void onObserveModeStateChanged(boolean isEnabled);
}