package android.os;

/**
 * @hide
 */
parcelable StatsDimensionsValueParcel {
    /**
     * Field equals:
     *      - atomTag for top level StatsDimensionsValueParcel
     *      - position in dimension for all other levels
     */
    int field;
    int valueType;

    String stringValue;
    int intValue;
    long longValue;
    boolean boolValue;
    float floatValue;
    StatsDimensionsValueParcel[] tupleValue;
}
