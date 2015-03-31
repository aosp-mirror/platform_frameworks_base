package android.security;

import android.security.keymaster.KeyCharacteristics;
import android.security.keymaster.KeymasterDefs;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @hide
 */
public abstract class KeymasterUtils {
    private KeymasterUtils() {}

    public static KeymasterException getExceptionForKeymasterError(int keymasterErrorCode) {
        switch (keymasterErrorCode) {
            case KeymasterDefs.KM_ERROR_INVALID_AUTHORIZATION_TIMEOUT:
                // The name of this parameter significantly differs between Keymaster and framework
                // APIs. Use the framework wording to make life easier for developers.
                return new KeymasterException(keymasterErrorCode,
                        "Invalid user authentication validity duration");
            default:
                return new KeymasterException(keymasterErrorCode,
                        KeymasterDefs.getErrorMessage(keymasterErrorCode));
        }
    }

    public static Integer getInt(KeyCharacteristics keyCharacteristics, int tag) {
        if (keyCharacteristics.hwEnforced.containsTag(tag)) {
            return keyCharacteristics.hwEnforced.getInt(tag, -1);
        } else if (keyCharacteristics.swEnforced.containsTag(tag)) {
            return keyCharacteristics.swEnforced.getInt(tag, -1);
        } else {
            return null;
        }
    }

    public static List<Integer> getInts(KeyCharacteristics keyCharacteristics, int tag) {
        List<Integer> result = new ArrayList<Integer>();
        result.addAll(keyCharacteristics.hwEnforced.getInts(tag));
        result.addAll(keyCharacteristics.swEnforced.getInts(tag));
        return result;
    }

    public static Date getDate(KeyCharacteristics keyCharacteristics, int tag) {
        Date result = keyCharacteristics.hwEnforced.getDate(tag, null);
        if (result == null) {
            result = keyCharacteristics.swEnforced.getDate(tag, null);
        }
        return result;
    }

    public static boolean getBoolean(KeyCharacteristics keyCharacteristics, int tag) {
        if (keyCharacteristics.hwEnforced.containsTag(tag)) {
            return keyCharacteristics.hwEnforced.getBoolean(tag, false);
        } else {
            return keyCharacteristics.swEnforced.getBoolean(tag, false);
        }
    }
}
