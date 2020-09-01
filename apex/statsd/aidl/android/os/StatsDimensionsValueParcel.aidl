package android.os;

/**
 * @hide
 */
parcelable StatsDimensionsValueParcel {
    // Field equals atomTag for top level StatsDimensionsValueParcels or
    // positions in depth (1-indexed) for lower level parcels.
    int field;

    // Indicator for which type of value is stored. Should be set to one
    // of the constants in StatsDimensionsValue.java.
    int valueType;

    String stringValue;
    int intValue;
    long longValue;
    boolean boolValue;
    float floatValue;
    StatsDimensionsValueParcel[] tupleValue;
}
