/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.util;

import java.io.IOException;
import java.io.Reader;
import java.io.StreamTokenizer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A {@code Map} that publishes a set of typed properties, defined by
 * zero or more {@code Reader}s containing textual definitions and assignments.
 */
public class TypedProperties extends HashMap<String, Object> {
    /**
     * Instantiates a {@link java.io.StreamTokenizer} and sets its syntax tables
     * appropriately for the {@code TypedProperties} file format.
     *
     * @param r The {@code Reader} that the {@code StreamTokenizer} will read from
     * @return a newly-created and initialized {@code StreamTokenizer}
     */
    static StreamTokenizer initTokenizer(Reader r) {
        StreamTokenizer st = new StreamTokenizer(r);

        // Treat everything we don't specify as "ordinary".
        st.resetSyntax();

        /* The only non-quoted-string words we'll be reading are:
         * - property names: [._$a-zA-Z0-9]
         * - type names: [a-zS]
         * - number literals: [-0-9.eExXA-Za-z]  ('x' for 0xNNN hex literals. "NaN", "Infinity")
         * - "true" or "false" (case insensitive): [a-zA-Z]
         */
        st.wordChars('0', '9');
        st.wordChars('A', 'Z');
        st.wordChars('a', 'z');
        st.wordChars('_', '_');
        st.wordChars('$', '$');
        st.wordChars('.', '.');
        st.wordChars('-', '-');
        st.wordChars('+', '+');

        // Single-character tokens
        st.ordinaryChar('=');

        // Other special characters
        st.whitespaceChars(' ', ' ');
        st.whitespaceChars('\t', '\t');
        st.whitespaceChars('\n', '\n');
        st.whitespaceChars('\r', '\r');
        st.quoteChar('"');

        // Java-style comments
        st.slashStarComments(true);
        st.slashSlashComments(true);

        return st;
    }


    /**
     * An unchecked exception that is thrown when encountering a syntax
     * or semantic error in the input.
     */
    public static class ParseException extends IllegalArgumentException {
        ParseException(StreamTokenizer state, String expected) {
            super("expected " + expected + ", saw " + state.toString());
        }
    }

    // A sentinel instance used to indicate a null string.
    static final String NULL_STRING = new String("<TypedProperties:NULL_STRING>");

    // Constants used to represent the supported types.
    static final int TYPE_UNSET = 'x';
    static final int TYPE_BOOLEAN = 'Z';
    static final int TYPE_BYTE = 'I' | 1 << 8;
    // TYPE_CHAR: character literal syntax not supported; use short.
    static final int TYPE_SHORT = 'I' | 2 << 8;
    static final int TYPE_INT = 'I' | 4 << 8;
    static final int TYPE_LONG = 'I' | 8 << 8;
    static final int TYPE_FLOAT = 'F' | 4 << 8;
    static final int TYPE_DOUBLE = 'F' | 8 << 8;
    static final int TYPE_STRING = 'L' | 's' << 8;
    static final int TYPE_ERROR = -1;

    /**
     * Converts a string to an internal type constant.
     *
     * @param typeName the type name to convert
     * @return the type constant that corresponds to {@code typeName},
     *         or {@code TYPE_ERROR} if the type is unknown
     */
    static int interpretType(String typeName) {
        if ("unset".equals(typeName)) {
            return TYPE_UNSET;
        } else if ("boolean".equals(typeName)) {
            return TYPE_BOOLEAN;
        } else if ("byte".equals(typeName)) {
            return TYPE_BYTE;
        } else if ("short".equals(typeName)) {
            return TYPE_SHORT;
        } else if ("int".equals(typeName)) {
            return TYPE_INT;
        } else if ("long".equals(typeName)) {
            return TYPE_LONG;
        } else if ("float".equals(typeName)) {
            return TYPE_FLOAT;
        } else if ("double".equals(typeName)) {
            return TYPE_DOUBLE;
        } else if ("String".equals(typeName)) {
            return TYPE_STRING;
        }
        return TYPE_ERROR;
    }

    /**
     * Parses the data in the reader.
     *
     * @param r The {@code Reader} containing input data to parse
     * @param map The {@code Map} to insert parameter values into
     * @throws ParseException if the input data is malformed
     * @throws IOException if there is a problem reading from the {@code Reader}
     */
    static void parse(Reader r, Map<String, Object> map) throws ParseException, IOException {
        final StreamTokenizer st = initTokenizer(r);

        /* A property name must be a valid fully-qualified class + package name.
         * We don't support Unicode, though.
         */
        final String identifierPattern = "[a-zA-Z_$][0-9a-zA-Z_$]*";
        final Pattern propertyNamePattern =
            Pattern.compile("(" + identifierPattern + "\\.)*" + identifierPattern);


        while (true) {
            int token;

            // Read the next token, which is either the type or EOF.
            token = st.nextToken();
            if (token == StreamTokenizer.TT_EOF) {
                break;
            }
            if (token != StreamTokenizer.TT_WORD) {
                throw new ParseException(st, "type name");
            }
            final int type = interpretType(st.sval);
            if (type == TYPE_ERROR) {
                throw new ParseException(st, "valid type name");
            }
            st.sval = null;

            if (type == TYPE_UNSET) {
                // Expect '('.
                token = st.nextToken();
                if (token != '(') {
                    throw new ParseException(st, "'('");
                }
            }

            // Read the property name.
            token = st.nextToken();
            if (token != StreamTokenizer.TT_WORD) {
                throw new ParseException(st, "property name");
            }
            final String propertyName = st.sval;
            if (!propertyNamePattern.matcher(propertyName).matches()) {
                throw new ParseException(st, "valid property name");
            }
            st.sval = null;

            if (type == TYPE_UNSET) {
                // Expect ')'.
                token = st.nextToken();
                if (token != ')') {
                    throw new ParseException(st, "')'");
                }
                map.remove(propertyName);
            } else {
                // Expect '='.
                token = st.nextToken();
                if (token != '=') {
                    throw new ParseException(st, "'='");
                }

                // Read a value of the appropriate type, and insert into the map.
                final Object value = parseValue(st, type);
                final Object oldValue = map.remove(propertyName);
                if (oldValue != null) {
                    // TODO: catch the case where a string is set to null and then
                    //       the same property is defined with a different type.
                    if (value.getClass() != oldValue.getClass()) {
                        throw new ParseException(st,
                            "(property previously declared as a different type)");
                    }
                }
                map.put(propertyName, value);
            }

            // Expect ';'.
            token = st.nextToken();
            if (token != ';') {
                throw new ParseException(st, "';'");
            }
        }
    }

    /**
     * Parses the next token in the StreamTokenizer as the specified type.
     *
     * @param st The token source
     * @param type The type to interpret next token as
     * @return a Boolean, Number subclass, or String representing the value.
     *         Null strings are represented by the String instance NULL_STRING
     * @throws IOException if there is a problem reading from the {@code StreamTokenizer}
     */
    static Object parseValue(StreamTokenizer st, final int type) throws IOException {
        final int token = st.nextToken();

        if (type == TYPE_BOOLEAN) {
            if (token != StreamTokenizer.TT_WORD) {
                throw new ParseException(st, "boolean constant");
            }

            if ("true".equals(st.sval)) {
                return Boolean.TRUE;
            } else if ("false".equals(st.sval)) {
                return Boolean.FALSE;
            }

            throw new ParseException(st, "boolean constant");
        } else if ((type & 0xff) == 'I') {
            if (token != StreamTokenizer.TT_WORD) {
                throw new ParseException(st, "integer constant");
            }

            /* Parse the string.  Long.decode() handles C-style integer constants
             * ("0x" -> hex, "0" -> octal).  It also treats numbers with a prefix of "#" as
             * hex, but our syntax intentionally does not list '#' as a word character.
             */
            long value;
            try {
                value = Long.decode(st.sval);
            } catch (NumberFormatException ex) {
                throw new ParseException(st, "integer constant");
            }

            // Ensure that the type can hold this value, and return.
            int width = (type >> 8) & 0xff;
            switch (width) {
            case 1:
                if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                    throw new ParseException(st, "8-bit integer constant");
                }
                return new Byte((byte)value);
            case 2:
                if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                    throw new ParseException(st, "16-bit integer constant");
                }
                return new Short((short)value);
            case 4:
                if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                    throw new ParseException(st, "32-bit integer constant");
                }
                return new Integer((int)value);
            case 8:
                if (value < Long.MIN_VALUE || value > Long.MAX_VALUE) {
                    throw new ParseException(st, "64-bit integer constant");
                }
                return new Long(value);
            default:
                throw new IllegalStateException(
                    "Internal error; unexpected integer type width " + width);
            }
        } else if ((type & 0xff) == 'F') {
            if (token != StreamTokenizer.TT_WORD) {
                throw new ParseException(st, "float constant");
            }

            // Parse the string.
            /* TODO: Maybe just parse as float or double, losing precision if necessary.
             *       Parsing as double and converting to float can change the value
             *       compared to just parsing as float.
             */
            double value;
            try {
                /* TODO: detect if the string representation loses precision
                 *       when being converted to a double.
                 */
                value = Double.parseDouble(st.sval);
            } catch (NumberFormatException ex) {
                throw new ParseException(st, "float constant");
            }

            // Ensure that the type can hold this value, and return.
            if (((type >> 8) & 0xff) == 4) {
                // This property is a float; make sure the value fits.
                double absValue = Math.abs(value);
                if (absValue != 0.0 && !Double.isInfinite(value) && !Double.isNaN(value)) {
                    if (absValue < Float.MIN_VALUE || absValue > Float.MAX_VALUE) {
                        throw new ParseException(st, "32-bit float constant");
                    }
                }
                return new Float((float)value);
            } else {
                // This property is a double; no need to truncate.
                return new Double(value);
            }
        } else if (type == TYPE_STRING) {
            // Expect a quoted string or the word "null".
            if (token == '"') {
                return st.sval;
            } else if (token == StreamTokenizer.TT_WORD && "null".equals(st.sval)) {
                return NULL_STRING;
            }
            throw new ParseException(st, "double-quoted string or 'null'");
        }

        throw new IllegalStateException("Internal error; unknown type " + type);
    }


    /**
     * Creates an empty TypedProperties instance.
     */
    public TypedProperties() {
        super();
    }

    /**
     * Loads zero or more properties from the specified Reader.
     * Properties that have already been loaded are preserved unless
     * the new Reader overrides or unsets earlier values for the
     * same properties.
     * <p>
     * File syntax:
     * <blockquote>
     *     <tt>
     *     &lt;type&gt; &lt;property-name&gt; = &lt;value&gt; ;
     *     <br />
     *     unset ( &lt;property-name&gt; ) ;
     *     </tt>
     *     <p>
     *     "//" comments everything until the end of the line.
     *     "/&#2a;" comments everything until the next appearance of "&#2a;/".
     *     <p>
     *     Blank lines are ignored.
     *     <p>
     *     The only required whitespace is between the type and
     *     the property name.
     *     <p>
     *     &lt;type&gt; is one of {boolean, byte, short, int, long,
     *     float, double, String}, and is case-sensitive.
     *     <p>
     *     &lt;property-name&gt; is a valid fully-qualified class name
     *     (one or more valid identifiers separated by dot characters).
     *     <p>
     *     &lt;value&gt; depends on the type:
     *     <ul>
     *     <li> boolean: one of {true, false} (case-sensitive)
     *     <li> byte, short, int, long: a valid Java integer constant
     *          (including non-base-10 constants like 0xabc and 074)
     *          whose value does not overflow the type.  NOTE: these are
     *          interpreted as Java integer values, so they are all signed.
     *     <li> float, double: a valid Java floating-point constant.
     *          If the type is float, the value must fit in 32 bits.
     *     <li> String: a double-quoted string value, or the word {@code null}.
     *          NOTE: the contents of the string must be 7-bit clean ASCII;
     *          C-style octal escapes are recognized, but Unicode escapes are not.
     *     </ul>
     *     <p>
     *     Passing a property-name to {@code unset()} will unset the property,
     *     removing its value and type information, as if it had never been
     *     defined.
     * </blockquote>
     *
     * @param r The Reader to load properties from
     * @throws IOException if an error occurs when reading the data
     * @throws IllegalArgumentException if the data is malformed
     */
    public void load(Reader r) throws IOException {
        parse(r, this);
    }

    @Override
    public Object get(Object key) {
        Object value = super.get(key);
        if (value == NULL_STRING) {
            return null;
        }
        return value;
    }

    /*
     * Getters with explicit defaults
     */

    /**
     * An unchecked exception that is thrown if a {@code get<TYPE>()} method
     * is used to retrieve a parameter whose type does not match the method name.
     */
    public static class TypeException extends IllegalArgumentException {
        TypeException(String property, Object value, String requestedType) {
            super(property + " has type " + value.getClass().getName() +
                ", not " + requestedType);
        }
    }

    /**
     * Returns the value of a boolean property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a boolean
     */
    public boolean getBoolean(String property, boolean def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Boolean) {
            return ((Boolean)value).booleanValue();
        }
        throw new TypeException(property, value, "boolean");
    }

    /**
     * Returns the value of a byte property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a byte
     */
    public byte getByte(String property, byte def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Byte) {
            return ((Byte)value).byteValue();
        }
        throw new TypeException(property, value, "byte");
    }

    /**
     * Returns the value of a short property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a short
     */
    public short getShort(String property, short def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Short) {
            return ((Short)value).shortValue();
        }
        throw new TypeException(property, value, "short");
    }

    /**
     * Returns the value of an integer property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not an integer
     */
    public int getInt(String property, int def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Integer) {
            return ((Integer)value).intValue();
        }
        throw new TypeException(property, value, "int");
    }

    /**
     * Returns the value of a long property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a long
     */
    public long getLong(String property, long def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Long) {
            return ((Long)value).longValue();
        }
        throw new TypeException(property, value, "long");
    }

    /**
     * Returns the value of a float property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a float
     */
    public float getFloat(String property, float def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Float) {
            return ((Float)value).floatValue();
        }
        throw new TypeException(property, value, "float");
    }

    /**
     * Returns the value of a double property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a double
     */
    public double getDouble(String property, double def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value instanceof Double) {
            return ((Double)value).doubleValue();
        }
        throw new TypeException(property, value, "double");
    }

    /**
     * Returns the value of a string property, or the default if the property
     * has not been defined.
     *
     * @param property The name of the property to return
     * @param def The default value to return if the property is not set
     * @return the value of the property
     * @throws TypeException if the property is set and is not a string
     */
    public String getString(String property, String def) {
        Object value = super.get(property);
        if (value == null) {
            return def;
        }
        if (value == NULL_STRING) {
            return null;
        } else if (value instanceof String) {
            return (String)value;
        }
        throw new TypeException(property, value, "string");
    }

    /*
     * Getters with implicit defaults
     */

    /**
     * Returns the value of a boolean property, or false
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a boolean
     */
    public boolean getBoolean(String property) {
        return getBoolean(property, false);
    }

    /**
     * Returns the value of a byte property, or 0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a byte
     */
    public byte getByte(String property) {
        return getByte(property, (byte)0);
    }

    /**
     * Returns the value of a short property, or 0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a short
     */
    public short getShort(String property) {
        return getShort(property, (short)0);
    }

    /**
     * Returns the value of an integer property, or 0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not an integer
     */
    public int getInt(String property) {
        return getInt(property, 0);
    }

    /**
     * Returns the value of a long property, or 0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a long
     */
    public long getLong(String property) {
        return getLong(property, 0L);
    }

    /**
     * Returns the value of a float property, or 0.0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a float
     */
    public float getFloat(String property) {
        return getFloat(property, 0.0f);
    }

    /**
     * Returns the value of a double property, or 0.0
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a double
     */
    public double getDouble(String property) {
        return getDouble(property, 0.0);
    }

    /**
     * Returns the value of a String property, or ""
     * if the property has not been defined.
     *
     * @param property The name of the property to return
     * @return the value of the property
     * @throws TypeException if the property is set and is not a string
     */
    public String getString(String property) {
        return getString(property, "");
    }

    // Values returned by getStringInfo()
    public static final int STRING_TYPE_MISMATCH = -2;
    public static final int STRING_NOT_SET = -1;
    public static final int STRING_NULL = 0;
    public static final int STRING_SET = 1;

    /**
     * Provides string type information about a property.
     *
     * @param property the property to check
     * @return STRING_SET if the property is a string and is non-null.
     *         STRING_NULL if the property is a string and is null.
     *         STRING_NOT_SET if the property is not set (no type or value).
     *         STRING_TYPE_MISMATCH if the property is set but is not a string.
     */
    public int getStringInfo(String property) {
        Object value = super.get(property);
        if (value == null) {
            return STRING_NOT_SET;
        }
        if (value == NULL_STRING) {
            return STRING_NULL;
        } else if (value instanceof String) {
            return STRING_SET;
        }
        return STRING_TYPE_MISMATCH;
    }
}
