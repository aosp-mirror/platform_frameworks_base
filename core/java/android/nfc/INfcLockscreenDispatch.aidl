package android.nfc;

import android.nfc.Tag;

/**
 * @hide
 */
interface INfcLockscreenDispatch {

    boolean onTagDetected(in Tag tag);

}
