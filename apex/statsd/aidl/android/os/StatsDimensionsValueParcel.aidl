package android.os;

/**
 * @hide
 */
parcelable StatsDimensionsValueParcel {
    int atomTag;
    int valueType;

    String stringValue;
    int intValue;
    long longValue;
    boolean boolValue;
    float floatValue;
    StatsDimensionsValueParcel[] arrayValue;
}
