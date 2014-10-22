package android.nfc;

import android.nfc.Tag;

/**
 * @hide
 */
interface INfcUnlockHandler {

    boolean onUnlockAttempted(in Tag tag);

}
