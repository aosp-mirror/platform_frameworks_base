/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package androidx.media.filterfw;


/**
 * A FrameType instance specifies the data format of a Frame.
 *
 * FrameTypes are used mainly by Filters to specify the data type they intend to consume or produce.
 * When filters are connected, their FrameType information is analyzed and checked for
 * compatibility. This allows Filter writers to assume a certain data input type. It also helps
 * filter-graph designers determine which filters can be hooked up to one another.
 *
 * A FrameType generally consists of an element type and number of dimensions. The currently
 * supported element types are:
 *
 * <ul>
 * <li>int8, int16, int32, in64</li>
 * <li>float32, float64</li>
 * <li>rgba8888</li>
 * <li>object</li>
 * <li>don't-care</li>
 * </ul>
 *
 * If the object element type is used, class information may be appended to the FrameType to
 * indicate what class of objects are expected. When constructing an object based FrameType, you
 * have the option of either specifying a type that represents a single object of that class, or
 * an array of objects (see the {@link #single()} and {@link #array()} constructors). A single
 * object has a dimensionality of 0, while an array has a dimensionality of 1.
 *
 * When constructing a non-object type, you have the option of creating a 1D or 2D buffer, or
 * a 2D image (see the {@link #buffer1D(int)}, {@link #buffer2D(int)}, and
 * {@link #image2D(int, int)} constructors). To optimize access, provide access hints when making
 * an image type.
 *
 * Finally, it is possible to create a wild-card type with the {@link #any()} constructor. This
 * type matches any other type. Note, that this is a more general type than a {@code single(Object)}
 * type that matches only object-base types (of any Object subclass). You may also specify the
 * leave the element of any type unspecified by using the {@code ELEMENT_DONTCARE} constant.
 *
 * When a graph is connected the types between outputs and inputs are merged to a queue-type. All
 * Frames in this queue will be of that type. In order for a merge to succeed the following
 * conditions must hold:
 *
 * <ul>
 * <li>The element types must be identical.</li>
 * <li>The dimensions must match (except for singles and arrays, see below).</li>
 * <li>For object-based types: The classes must be compatible.</li>
 * <li>If one of the types is a wild-card, both types are always compatible.</li>
 * </ul>
 *
 * Class compatibility is determined in an optimistic fashion, i.e. one class must be the subclass
 * of the other. It does not matter which of the types is the subclass of the other. For instance,
 * if one Filter outputs a type of class {@code Object}, and the consumer expects a Filter of type
 * {@code Bitmap}, the connection is considered compatible. (Of course if at runtime a non-Bitmap
 * object is produced, this will cause a runtime exception to be thrown).
 *
 * For convenience, single and array object-based types are compatible with one another. This
 * in turn means that Frames with a single object can be accessed as an array with a single entry,
 * and array based Frames can be accessed as a single object of the array class. For this reason
 * you should prefer consuming objects as array types (if it makes sense for that specific port),
 * as this will allow your Filter to handle multiple objects in one Frame while not giving up the
 * possibility to deal with singles.
 * TODO: This needs to be reworked. An array(int) should not be interchangeable with a single(int),
 * but rather with a single(int[]). Use ArraySelectFilter for the former!
 *
 * After the types are merged, the queue-type must be a fully specified type. This means that the
 * type must have its element and dimensions specified. This ensures that filters that need to
 * query their input or output types receive meaningful information.
 */
public final class FrameType {

    public final static int ELEMENT_DONTCARE = 0;
    public final static int ELEMENT_OBJECT = 1;

    public final static int ELEMENT_INT8 = 100;
    public final static int ELEMENT_INT16 = 101;
    public final static int ELEMENT_INT32 = 102;
    public final static int ELEMENT_INT64 = 103;

    public final static int ELEMENT_FLOAT32 = 200;
    public final static int ELEMENT_FLOAT64 = 201;

    public final static int ELEMENT_RGBA8888 = 301;

    public final static int READ_CPU = 0x01;
    public final static int READ_GPU = 0x02;
    public final static int READ_ALLOCATION = 0x04;
    public final static int WRITE_CPU = 0x08;
    public final static int WRITE_GPU = 0x10;
    public final static int WRITE_ALLOCATION = 0x20;

    private final static int ACCESS_UNKNOWN = 0x00;

    private final int mElementId;
    private final int mDimensions;
    private final int mAccessHints;
    private final Class<?> mClass;

    private static SimpleCache<String, FrameType> mTypeCache =
            new SimpleCache<String, FrameType>(64);

    /**
     * Constructs a wild-card FrameType that matches any other FrameType.
     * @return The wild-card FrameType instance.
     */
    public static FrameType any() {
        return FrameType.fetchType(ELEMENT_DONTCARE, -1, ACCESS_UNKNOWN);
    }

    /**
     * Constructs an object-based single FrameType that matches object-based FrameTypes of any
     * class.
     * @return A single object-based FrameType instance.
     */
    public static FrameType single() {
        return FrameType.fetchType(null, 0);
    }

    /**
     * Constructs an object-based single FrameType of the specified class.
     * @param clazz The class of the FrameType.
     * @return A single object-base FrameType instance of the specified class.
     */
    public static FrameType single(Class<?> clazz) {
        return FrameType.fetchType(clazz, 0);
    }

    /**
     * Constructs an object-based array FrameType that matches object-based FrameTypes of any class.
     * @return An array object-based FrameType instance.
     */
    public static FrameType array() {
        return FrameType.fetchType(null, 1);
    }

    /**
     * Constructs an object-based array FrameType with elements of the specified class.
     * @param clazz The class of the array elements (not the array type).
     * @return An array object-based FrameType instance of the specified class.
     */
    public static FrameType array(Class<?> clazz) {
        return FrameType.fetchType(clazz, 1);
    }

    /**
     * Constructs a one-dimensional buffer type of the specified element.
     * @param elementType One of the {@code ELEMENT} constants.
     * @return A 1D buffer FrameType instance.
     */
    public static FrameType buffer1D(int elementType) {
        return FrameType.fetchType(elementType, 1, ACCESS_UNKNOWN);
    }

    /**
     * Constructs a two-dimensional buffer type of the specified element.
     * @param elementType One of the {@code ELEMENT} constants.
     * @return A 2D buffer FrameType instance.
     */
    public static FrameType buffer2D(int elementType) {
        return FrameType.fetchType(elementType, 2, ACCESS_UNKNOWN);
    }

    /**
     * Constructs a two-dimensional image type of the specified element.
     * @param elementType One of the {@code ELEMENT} constants.
     * @param accessHint A bit-mask of access flags (see {@code READ} and {@code WRITE} constants).
     * @return A 2D image FrameType instance.
     */
    public static FrameType image2D(int elementType, int accessHint) {
        return FrameType.fetchType(elementType, 2, accessHint);
    }

    /**
     * Converts the current array type to a single type.
     * The type must be an object-based type. If the type is already a single type, this does
     * nothing.
     * @return type as a single type.
     */
    public FrameType asSingle() {
        if (mElementId != ELEMENT_OBJECT) {
            throw new RuntimeException("Calling asSingle() on non-object type!");
        }
        return FrameType.fetchType(mClass, 0);
    }

    /**
     * Converts the current single type to an array type.
     * The type must be an object-based type. If the type is already an array type, this does
     * nothing.
     * @return type as an array type.
     */
    public FrameType asArray() {
        if (mElementId != ELEMENT_OBJECT) {
            throw new RuntimeException("Calling asArray() on non-object type!");
        }
        return FrameType.fetchType(mClass, 1);
    }

    /**
     * Returns the FrameType's class specifier, or null if no class was set or the receiver is not
     * an object-based type.
     * @return The FrameType's class specifier or null.
     */
    public Class<?> getContentClass() {
        return mClass;
    }

    /**
     * Returns the FrameType's element id.
     * @return The element id constant.
     */
    public int getElementId() {
        return mElementId;
    }

    /**
     * Returns the number of bytes of the FrameType's element, or 0 if no such size can be
     * determined.
     * @return The number of bytes of the FrameType's element.
     */
    public int getElementSize() {
        switch (mElementId) {
            case ELEMENT_INT8:
                return 1;
            case ELEMENT_INT16:
                return 2;
            case ELEMENT_INT32:
            case ELEMENT_FLOAT32:
            case ELEMENT_RGBA8888:
                return 4;
            case ELEMENT_INT64:
            case ELEMENT_FLOAT64:
                return 4;
            default:
                return 0;
        }
    }

    /**
     * Returns the access hints bit-mask of the FrameType.
     * @return The access hints bit-mask of the FrameType.
     */
    public int getAccessHints() {
        return mAccessHints;
    }

    /**
     * Returns the number of dimensions of the FrameType or -1 if no dimensions were set.
     * @return The number of dimensions of the FrameType.
     */
    public int getNumberOfDimensions() {
        return mDimensions;
    }

    /**
     * Returns true, if the FrameType is fully specified.
     *
     * A FrameType is fully specified if its element and dimensions are specified.
     *
     * @return true, if the FrameType is fully specified.
     */
    public boolean isSpecified() {
        return mElementId != ELEMENT_DONTCARE && mDimensions >= 0;
    }

    @Override
    public boolean equals(Object object) {
        if (object instanceof FrameType) {
            FrameType type = (FrameType) object;
            return mElementId == type.mElementId && mDimensions == type.mDimensions
                    && mAccessHints == type.mAccessHints && mClass == type.mClass;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return mElementId ^ mDimensions ^ mAccessHints ^ mClass.hashCode();
    }

    @Override
    public String toString() {
        String result = elementToString(mElementId, mClass) + "[" + mDimensions + "]";
        if ((mAccessHints & READ_CPU) != 0) {
            result += "(rcpu)";
        }
        if ((mAccessHints & READ_GPU) != 0) {
            result += "(rgpu)";
        }
        if ((mAccessHints & READ_ALLOCATION) != 0) {
            result += "(ralloc)";
        }
        if ((mAccessHints & WRITE_CPU) != 0) {
            result += "(wcpu)";
        }
        if ((mAccessHints & WRITE_GPU) != 0) {
            result += "(wgpu)";
        }
        if ((mAccessHints & WRITE_ALLOCATION) != 0) {
            result += "(walloc)";
        }
        return result;
    }

    String keyString() {
        return keyValueForType(mElementId, mDimensions, mAccessHints, mClass);
    }

    static FrameType tryMerge(FrameType writer, FrameType reader) {
        if (writer.mElementId == ELEMENT_DONTCARE) {
            return reader;
        } else if (reader.mElementId == ELEMENT_DONTCARE) {
            return writer;
        } else if (writer.mElementId == ELEMENT_OBJECT && reader.mElementId == ELEMENT_OBJECT) {
            return tryMergeObjectTypes(writer, reader);
        } else if (writer.mDimensions > 0 && writer.mElementId == reader.mElementId) {
            return tryMergeBuffers(writer, reader);
        } else {
            return null;
        }
    }

    static FrameType tryMergeObjectTypes(FrameType writer, FrameType reader) {
        int dimensions = Math.max(writer.mDimensions, reader.mDimensions);
        Class<?> mergedClass = mergeClasses(writer.mClass, reader.mClass);
        boolean success = mergedClass != null || writer.mClass == null;
        return success ? FrameType.fetchType(mergedClass, dimensions) : null;
    }

    static FrameType tryMergeBuffers(FrameType writer, FrameType reader) {
        if (writer.mDimensions == reader.mDimensions) {
            int accessHints = writer.mAccessHints | reader.mAccessHints;
            return FrameType.fetchType(writer.mElementId, writer.mDimensions, accessHints);
        }
        return null;
    }

    static FrameType merge(FrameType writer, FrameType reader) {
        FrameType result = tryMerge(writer, reader);
        if (result == null) {
            throw new RuntimeException(
                    "Incompatible types in connection: " + writer + " vs. " + reader + "!");
        }
        return result;
    }

    private static String keyValueForType(int elemId, int dims, int hints, Class<?> clazz) {
        return elemId + ":" + dims + ":" + hints + ":" + (clazz != null ? clazz.getName() : "0");
    }

    private static String elementToString(int elemId, Class<?> clazz) {
        switch (elemId) {
            case ELEMENT_INT8:
                return "int8";
            case ELEMENT_INT16:
                return "int16";
            case ELEMENT_INT32:
                return "int32";
            case ELEMENT_INT64:
                return "int64";
            case ELEMENT_FLOAT32:
                return "float32";
            case ELEMENT_FLOAT64:
                return "float64";
            case ELEMENT_RGBA8888:
                return "rgba8888";
            case ELEMENT_OBJECT:
                return "<" + (clazz == null ? "*" : clazz.getSimpleName()) + ">";
            case ELEMENT_DONTCARE:
                return "*";
            default:
                return "?";
        }
    }

    private static Class<?> mergeClasses(Class<?> classA, Class<?> classB) {
        // Return the most specialized class.
        if (classA == null) {
            return classB;
        } else if (classB == null) {
            return classA;
        } else if (classA.isAssignableFrom(classB)) {
            return classB;
        } else if (classB.isAssignableFrom(classA)) {
            return classA;
        } else {
            return null;
        }
    }

    private static FrameType fetchType(int elementId, int dimensions, int accessHints) {
        return fetchType(elementId, dimensions, accessHints, null);
    }

    private static FrameType fetchType(Class<?> clazz, int dimensions) {
        return fetchType(ELEMENT_OBJECT, dimensions, ACCESS_UNKNOWN, clazz);
    }

    private static FrameType fetchType(
            int elementId, int dimensions, int accessHints, Class<?> clazz) {
        String typeKey = FrameType.keyValueForType(elementId, dimensions, accessHints, clazz);
        FrameType type = mTypeCache.get(typeKey);
        if (type == null) {
            type = new FrameType(elementId, dimensions, accessHints, clazz);
            mTypeCache.put(typeKey, type);
        }
        return type;
    }

    private FrameType(int elementId, int dimensions, int accessHints, Class<?> clazz) {
        mElementId = elementId;
        mDimensions = dimensions;
        mClass = clazz;
        mAccessHints = accessHints;
    }

}
