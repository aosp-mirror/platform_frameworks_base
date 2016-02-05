package com.android.hotspot2;

/**
 * Match score for EAP credentials:
 * None means that there is a distinct mismatch, i.e. realm, method or parameter is defined
 * and mismatches that of the credential.
 * Indeterminate means that there is no ANQP information to match against.
 * Note: The numeric values given to the constants are used for preference comparison and
 * must be maintained accordingly.
 */
public abstract class AuthMatch {
    public static final int None = -1;
    public static final int Indeterminate = 0;
    public static final int Realm = 0x04;
    public static final int Method = 0x02;
    public static final int Param = 0x01;
    public static final int MethodParam = Method | Param;
    public static final int Exact = Realm | Method | Param;

    public static String toString(int match) {
        if (match < 0) {
            return "None";
        } else if (match == 0) {
            return "Indeterminate";
        }

        StringBuilder sb = new StringBuilder();
        if ((match & Realm) != 0) {
            sb.append("Realm");
        }
        if ((match & Method) != 0) {
            sb.append("Method");
        }
        if ((match & Param) != 0) {
            sb.append("Param");
        }
        return sb.toString();
    }
}
