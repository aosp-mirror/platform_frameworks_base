package android.security;

import android.annotation.IntDef;
import android.security.keymaster.KeymasterDefs;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Characteristics of {@code AndroidKeyStore} keys.
 *
 * @hide
 */
public abstract class KeyStoreKeyCharacteristics {
    private KeyStoreKeyCharacteristics() {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({Origin.GENERATED_INSIDE_TEE, Origin.GENERATED_OUTSIDE_OF_TEE, Origin.IMPORTED})
    public @interface OriginEnum {}

    /**
     * Origin of the key.
     */
    public static abstract class Origin {
        private Origin() {}

        /** Key was generated inside a TEE. */
        public static final int GENERATED_INSIDE_TEE = 1;

        /** Key was generated outside of a TEE. */
        public static final int GENERATED_OUTSIDE_OF_TEE = 2;

        /** Key was imported. */
        public static final int IMPORTED = 0;

        /**
         * @hide
         */
        public static @OriginEnum int fromKeymaster(int origin) {
            switch (origin) {
                case KeymasterDefs.KM_ORIGIN_HARDWARE:
                    return GENERATED_INSIDE_TEE;
                case KeymasterDefs.KM_ORIGIN_SOFTWARE:
                    return GENERATED_OUTSIDE_OF_TEE;
                case KeymasterDefs.KM_ORIGIN_IMPORTED:
                    return IMPORTED;
                default:
                    throw new IllegalArgumentException("Unknown origin: " + origin);
            }
        }
    }
}
