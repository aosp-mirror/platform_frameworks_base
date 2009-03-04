/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Dmitry A. Durnev
 * @version $Revision$
 */

package java.awt;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

import org.apache.harmony.awt.internal.nls.Messages;

/**
 * The AWTKeyStroke holds all of the information for the complete act of 
 * typing a character. This includes the events that are generated when 
 * the key is pressed, released, or typed (pressed and released generating
 * a Unicode character result) which are associated with the event
 * objects KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED, or KeyEvent.KEY_TYPED.
 * It also holds information about which modifiers (such as control or 
 * shift) were used in conjunction with the keystroke. The following masks 
 * are available to identify the modifiers:
 * <ul>
 * <li>java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK</li>
 * <li>java.awt.event.InputEvent.ALT_DOWN_MASK</li>
 * <li>java.awt.event.InputEvent.CTRL_DOWN_MASK</li>
 * <li>java.awt.event.InputEvent.META_DOWN_MASK</li>
 * <li>java.awt.event.InputEvent.SHIFT_DOWN_MASK</li>
 * <li>java.awt.event.InputEvent.ALT_GRAPH_MASK</li>
 * <li>java.awt.event.InputEvent.ALT_MASK</li>
 * <li>java.awt.event.InputEvent.CTRL_MASK</li>
 * <li>java.awt.event.InputEvent.META_MASK</li>  
 * <li>java.awt.event.InputEvent.SHIFT_MASK</li>
 * </ul>  
 * <br>
 *  The AWTKeyStroke is unique, and applications should not create their own 
 *  instances of AWTKeyStroke. All applications should use getAWTKeyStroke 
 *  methods for obtaining instances of AWTKeyStroke.
 *  
 *  @since Android 1.0
 */
public class AWTKeyStroke implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -6430539691155161871L;

    /**
     * The Constant cache.
     */
    private static final Map<AWTKeyStroke, AWTKeyStroke> cache = new HashMap<AWTKeyStroke, AWTKeyStroke>(); // Map

    // <
    // AWTKeyStroke
    // ,
    // ?
    // extends
    // AWTKeyStroke
    // >

    /**
     * The Constant keyEventTypesMap.
     */
    private static final Map<Integer, String> keyEventTypesMap = new HashMap<Integer, String>(); // Map

    // <
    // int
    // ,
    // String
    // >

    private static Constructor<?> subConstructor;

    static {
        keyEventTypesMap.put(new Integer(KeyEvent.KEY_PRESSED), "pressed"); //$NON-NLS-1$
        keyEventTypesMap.put(new Integer(KeyEvent.KEY_RELEASED), "released"); //$NON-NLS-1$
        keyEventTypesMap.put(new Integer(KeyEvent.KEY_TYPED), "typed"); //$NON-NLS-1$
    }

    /**
     * The key char.
     */
    private char keyChar;

    /**
     * The key code.
     */
    private int keyCode;

    /**
     * The modifiers.
     */
    private int modifiers;

    /**
     * The on key release.
     */
    private boolean onKeyRelease;

    /**
     * Instantiates a new AWTKeyStroke. getAWTKeyStroke method should be used by
     * applications code.
     * 
     * @param keyChar
     *            the key char.
     * @param keyCode
     *            the key code.
     * @param modifiers
     *            the modifiers.
     * @param onKeyRelease
     *            true if AWTKeyStroke is for a key release, false otherwise.
     */
    protected AWTKeyStroke(char keyChar, int keyCode, int modifiers, boolean onKeyRelease) {
        setAWTKeyStroke(keyChar, keyCode, modifiers, onKeyRelease);
    }

    /**
     * Sets the AWT key stroke.
     * 
     * @param keyChar
     *            the key char.
     * @param keyCode
     *            the key code.
     * @param modifiers
     *            the modifiers.
     * @param onKeyRelease
     *            the on key release.
     */
    private void setAWTKeyStroke(char keyChar, int keyCode, int modifiers, boolean onKeyRelease) {
        this.keyChar = keyChar;
        this.keyCode = keyCode;
        this.modifiers = modifiers;
        this.onKeyRelease = onKeyRelease;
    }

    /**
     * Instantiates a new AWTKeyStroke with default parameters:
     * KeyEvent.CHAR_UNDEFINED key char, KeyEvent.VK_UNDEFINED key code, without
     * modifiers and false key realized value.
     */
    protected AWTKeyStroke() {
        this(KeyEvent.CHAR_UNDEFINED, KeyEvent.VK_UNDEFINED, 0, false);
    }

    /**
     * Returns the unique number value for AWTKeyStroke object.
     * 
     * @return the integer unique value of the AWTKeyStroke object.
     */
    @Override
    public int hashCode() {
        return modifiers + (keyCode != KeyEvent.VK_UNDEFINED ? keyCode : keyChar)
                + (onKeyRelease ? -1 : 0);
    }

    /**
     * Gets the set of modifiers for the AWTKeyStroke object.
     * 
     * @return the integer value which contains modifiers.
     */
    public final int getModifiers() {
        return modifiers;
    }

    /**
     * Compares this AWTKeyStroke object to the specified object.
     * 
     * @param anObject
     *            the specified AWTKeyStroke object to compare with this
     *            instance.
     * @return true if objects are identical, false otherwise.
     */
    @Override
    public final boolean equals(Object anObject) {
        if (anObject instanceof AWTKeyStroke) {
            AWTKeyStroke key = (AWTKeyStroke)anObject;
            return ((key.keyCode == keyCode) && (key.keyChar == keyChar)
                    && (key.modifiers == modifiers) && (key.onKeyRelease == onKeyRelease));
        }
        return false;
    }

    /**
     * Returns the string representation of the AWTKeyStroke. This string should
     * contain key stroke properties.
     * 
     * @return the string representation of the AWTKeyStroke.
     */
    @Override
    public String toString() {
        int type = getKeyEventType();
        return InputEvent.getModifiersExText(getModifiers()) + " " + //$NON-NLS-1$
                keyEventTypesMap.get(new Integer(type)) + " " + //$NON-NLS-1$
                (type == KeyEvent.KEY_TYPED ? new String(new char[] {
                    keyChar
                }) : KeyEvent.getKeyText(keyCode));
    }

    /**
     * Gets the key code for the AWTKeyStroke object.
     * 
     * @return the key code for the AWTKeyStroke object.
     */
    public final int getKeyCode() {
        return keyCode;
    }

    /**
     * Gets the key character for the AWTKeyStroke object.
     * 
     * @return the key character for the AWTKeyStroke object.
     */
    public final char getKeyChar() {
        return keyChar;
    }

    /**
     * Gets the AWT key stroke.
     * 
     * @param keyChar
     *            the key char.
     * @param keyCode
     *            the key code.
     * @param modifiers
     *            the modifiers.
     * @param onKeyRelease
     *            the on key release.
     * @return the AWT key stroke.
     */
    private static AWTKeyStroke getAWTKeyStroke(char keyChar, int keyCode, int modifiers,
            boolean onKeyRelease) {
        AWTKeyStroke key = newInstance(keyChar, keyCode, modifiers, onKeyRelease);

        AWTKeyStroke value = cache.get(key);
        if (value == null) {
            value = key;
            cache.put(key, value);
        }
        return value;
    }

    /**
     * New instance.
     * 
     * @param keyChar
     *            the key char.
     * @param keyCode
     *            the key code.
     * @param modifiers
     *            the modifiers.
     * @param onKeyRelease
     *            the on key release.
     * @return the AWT key stroke.
     */
    private static AWTKeyStroke newInstance(char keyChar, int keyCode, int modifiers,
            boolean onKeyRelease) {
        AWTKeyStroke key;
        // ???AWT
        // if (subConstructor == null) {
        key = new AWTKeyStroke();
        // ???AWT
        // } else {
        // try {
        // key = (AWTKeyStroke) subConstructor.newInstance();
        // } catch (Exception e) {
        // throw new RuntimeException(e);
        // }
        // }
        int allModifiers = getAllModifiers(modifiers);
        key.setAWTKeyStroke(keyChar, keyCode, allModifiers, onKeyRelease);
        return key;
    }

    /**
     * Adds the mask.
     * 
     * @param mod
     *            the mod.
     * @param mask
     *            the mask.
     * @return the int.
     */
    private static int addMask(int mod, int mask) {
        return ((mod & mask) != 0) ? (mod | mask) : mod;
    }

    /**
     * Return all (old & new) modifiers corresponding to.
     * 
     * @param mod
     *            old or new modifiers.
     * @return old and new modifiers together.
     */
    static int getAllModifiers(int mod) {
        int allMod = mod;
        int shift = (InputEvent.SHIFT_MASK | InputEvent.SHIFT_DOWN_MASK);
        int ctrl = (InputEvent.CTRL_MASK | InputEvent.CTRL_DOWN_MASK);
        int meta = (InputEvent.META_MASK | InputEvent.META_DOWN_MASK);
        int alt = (InputEvent.ALT_MASK | InputEvent.ALT_DOWN_MASK);
        int altGr = (InputEvent.ALT_GRAPH_MASK | InputEvent.ALT_GRAPH_DOWN_MASK);
        // button modifiers are not converted between old & new

        allMod = addMask(allMod, shift);
        allMod = addMask(allMod, ctrl);
        allMod = addMask(allMod, meta);
        allMod = addMask(allMod, alt);
        allMod = addMask(allMod, altGr);

        return allMod;
    }

    /**
     * Returns an instance of AWTKeyStroke for parsed string. The string must
     * have the following syntax:
     *<p>
     * &lt;modifiers&gt;* (&lt;typedID&gt; | &lt;pressedReleasedID&gt;)
     *<p>
     * modifiers := shift | control | ctrl | meta | alt | altGraph <br>
     * typedID := typed <typedKey> <br>
     * typedKey := string of length 1 giving the Unicode character. <br>
     * pressedReleasedID := (pressed | released) <key> <br>
     * key := KeyEvent key code name, i.e. the name following "VK_".
     * <p>
     * 
     * @param s
     *            the String which contains key stroke parameters.
     * @return the AWTKeyStroke for string.
     * @throws IllegalArgumentException
     *             if string has incorrect format or null.
     */
    public static AWTKeyStroke getAWTKeyStroke(String s) {
        if (s == null) {
            // awt.65=null argument
            throw new IllegalArgumentException(Messages.getString("awt.65")); //$NON-NLS-1$
        }

        StringTokenizer tokenizer = new StringTokenizer(s);

        Boolean release = null;
        int modifiers = 0;
        int keyCode = KeyEvent.VK_UNDEFINED;
        char keyChar = KeyEvent.CHAR_UNDEFINED;
        boolean typed = false;
        long modifier = 0;
        String token = null;
        do {
            token = getNextToken(tokenizer);
            modifier = parseModifier(token);
            modifiers |= modifier;
        } while (modifier > 0);

        typed = parseTypedID(token);

        if (typed) {
            token = getNextToken(tokenizer);
            keyChar = parseTypedKey(token);

        }
        if (keyChar == KeyEvent.CHAR_UNDEFINED) {
            release = parsePressedReleasedID(token);
            if (release != null) {
                token = getNextToken(tokenizer);
            }
            keyCode = parseKey(token);
        }
        if (tokenizer.hasMoreTokens()) {
            // awt.66=Invalid format
            throw new IllegalArgumentException(Messages.getString("awt.66")); //$NON-NLS-1$
        }

        return getAWTKeyStroke(keyChar, keyCode, modifiers, release == Boolean.TRUE);
    }

    /**
     * Gets the next token.
     * 
     * @param tokenizer
     *            the tokenizer.
     * @return the next token.
     */
    private static String getNextToken(StringTokenizer tokenizer) {
        try {
            return tokenizer.nextToken();
        } catch (NoSuchElementException exception) {
            // awt.66=Invalid format
            throw new IllegalArgumentException(Messages.getString("awt.66")); //$NON-NLS-1$
        }
    }

    /**
     * Gets the key code.
     * 
     * @param s
     *            the s.
     * @return the key code.
     */
    static int getKeyCode(String s) {
        try {
            Field vk = KeyEvent.class.getField("VK_" + s); //$NON-NLS-1$
            return vk.getInt(null);
        } catch (Exception e) {
            if (s.length() != 1) {
                // awt.66=Invalid format
                throw new IllegalArgumentException(Messages.getString("awt.66")); //$NON-NLS-1$
            }
            return KeyEvent.VK_UNDEFINED;
        }
    }

    /**
     * Gets an instance of the AWTKeyStroke for specified character.
     * 
     * @param keyChar
     *            the keyboard character value.
     * @return a AWTKeyStroke for specified character.
     */
    public static AWTKeyStroke getAWTKeyStroke(char keyChar) {
        return getAWTKeyStroke(keyChar, KeyEvent.VK_UNDEFINED, 0, false);
    }

    /**
     * Returns an instance of AWTKeyStroke for a given key code, set of
     * modifiers, and specified key released flag value. The key codes are
     * defined in java.awt.event.KeyEvent class. The set of modifiers is given
     * as a bitwise combination of masks taken from the following list:
     * <ul>
     * <li>java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.META_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_GRAPH_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_MASK</li> <li>
     * java.awt.event.InputEvent.META_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_MASK</li>
     * </ul>
     * <br>
     * 
     * @param keyCode
     *            the specified key code of keyboard.
     * @param modifiers
     *            the bit set of modifiers.
     * @param onKeyRelease
     *            the value which represents whether this AWTKeyStroke shall
     *            represents a key release.
     * @return the AWTKeyStroke.
     */
    public static AWTKeyStroke getAWTKeyStroke(int keyCode, int modifiers, boolean onKeyRelease) {
        return getAWTKeyStroke(KeyEvent.CHAR_UNDEFINED, keyCode, modifiers, onKeyRelease);
    }

    /**
     * Returns AWTKeyStroke for a specified character and set of modifiers. The
     * set of modifiers is given as a bitwise combination of masks taken from
     * the following list:
     * <ul>
     * <li>java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.META_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_GRAPH_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_MASK</li> <li>
     * java.awt.event.InputEvent.META_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_MASK</li>
     * </ul>
     * 
     * @param keyChar
     *            the Character object which represents keyboard character
     *            value.
     * @param modifiers
     *            the bit set of modifiers.
     * @return the AWTKeyStroke object.
     * @throws IllegalArgumentException
     *             if keyChar value is null.
     */
    public static AWTKeyStroke getAWTKeyStroke(Character keyChar, int modifiers) {
        if (keyChar == null) {
            // awt.01='{0}' parameter is null
            throw new IllegalArgumentException(Messages.getString("awt.01", "keyChar")); //$NON-NLS-1$ //$NON-NLS-2$
        }
        return getAWTKeyStroke(keyChar.charValue(), KeyEvent.VK_UNDEFINED, modifiers, false);
    }

    /**
     * Returns an instance of AWTKeyStroke for a specified key code and set of
     * modifiers. The key codes are defined in java.awt.event.KeyEvent class.
     * The set of modifiers is given as a bitwise combination of masks taken
     * from the following list:
     * <ul>
     * <li>java.awt.event.InputEvent.ALT_GRAPH_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.META_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_DOWN_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_GRAPH_MASK</li> <li>
     * java.awt.event.InputEvent.ALT_MASK</li> <li>
     * java.awt.event.InputEvent.CTRL_MASK</li> <li>
     * java.awt.event.InputEvent.META_MASK</li> <li>
     * java.awt.event.InputEvent.SHIFT_MASK</li>
     * </ul>
     * 
     * @param keyCode
     *            the specified key code of keyboard.
     * @param modifiers
     *            the bit set of modifiers.
     * @return the AWTKeyStroke.
     */
    public static AWTKeyStroke getAWTKeyStroke(int keyCode, int modifiers) {
        return getAWTKeyStroke(keyCode, modifiers, false);
    }

    /**
     * Gets the AWTKeyStroke for a key event. This method obtains the key char
     * and key code from the specified key event.
     * 
     * @param anEvent
     *            the key event which identifies the desired AWTKeyStroke.
     * @return the AWTKeyStroke for the key event.
     */
    public static AWTKeyStroke getAWTKeyStrokeForEvent(KeyEvent anEvent) {
        int id = anEvent.getID();
        char undef = KeyEvent.CHAR_UNDEFINED;
        char keyChar = (id == KeyEvent.KEY_TYPED ? anEvent.getKeyChar() : undef);
        int keyCode = (keyChar == undef ? anEvent.getKeyCode() : KeyEvent.VK_UNDEFINED);
        return getAWTKeyStroke(keyChar, keyCode, anEvent.getModifiersEx(),
                id == KeyEvent.KEY_RELEASED);
    }

    /**
     * Gets the key event type for the AWTKeyStroke object.
     * 
     * @return the key event type: KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED, or
     *         KeyEvent.KEY_RELEASED.
     */
    public final int getKeyEventType() {
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            return KeyEvent.KEY_TYPED;
        }
        return (onKeyRelease ? KeyEvent.KEY_RELEASED : KeyEvent.KEY_PRESSED);
    }

    /**
     * Returns true if the key event is associated with the AWTKeyStroke is
     * KEY_RELEASED, false otherwise.
     * 
     * @return true, if if the key event associated with the AWTKeyStroke is
     *         KEY_RELEASED, false otherwise.
     */
    public final boolean isOnKeyRelease() {
        return onKeyRelease;
    }

    /**
     * Read resolve.
     * 
     * @return the object.
     * @throws ObjectStreamException
     *             the object stream exception.
     */
    protected Object readResolve() throws ObjectStreamException {
        return getAWTKeyStroke(this.keyChar, this.keyCode, this.modifiers, this.onKeyRelease);
    }

    /**
     * Register subclass.
     * 
     * @param subclass
     *            the subclass.
     */
    protected static void registerSubclass(Class<?> subclass) {
        // ???AWT
        /*
         * if (subclass == null) { // awt.01='{0}' parameter is null throw new
         * IllegalArgumentException(Messages.getString("awt.01", "subclass"));
         * //$NON-NLS-1$ //$NON-NLS-2$ } if (!
         * AWTKeyStroke.class.isAssignableFrom(subclass)) { // awt.67=subclass
         * is not derived from AWTKeyStroke throw new
         * ClassCastException(Messages.getString("awt.67")); //$NON-NLS-1$ } try
         * { subConstructor = subclass.getDeclaredConstructor();
         * subConstructor.setAccessible(true); } catch (SecurityException e) {
         * throw new RuntimeException(e); } catch (NoSuchMethodException e) { //
         * awt.68=subclass could not be instantiated throw new
         * IllegalArgumentException(Messages.getString("awt.68")); //$NON-NLS-1$
         * } cache.clear(); //flush the cache
         */
    }

    /**
     * Parses the modifier.
     * 
     * @param strMod
     *            the str mod.
     * @return the long.
     */
    private static long parseModifier(String strMod) {
        long modifiers = 0l;
        if (strMod.equals("shift")) { //$NON-NLS-1$
            modifiers |= InputEvent.SHIFT_DOWN_MASK;
        } else if (strMod.equals("control") || strMod.equals("ctrl")) { //$NON-NLS-1$ //$NON-NLS-2$
            modifiers |= InputEvent.CTRL_DOWN_MASK;
        } else if (strMod.equals("meta")) { //$NON-NLS-1$
            modifiers |= InputEvent.META_DOWN_MASK;
        } else if (strMod.equals("alt")) { //$NON-NLS-1$
            modifiers |= InputEvent.ALT_DOWN_MASK;
        } else if (strMod.equals("altGraph")) { //$NON-NLS-1$
            modifiers |= InputEvent.ALT_GRAPH_DOWN_MASK;
        } else if (strMod.equals("button1")) { //$NON-NLS-1$
            modifiers |= InputEvent.BUTTON1_DOWN_MASK;
        } else if (strMod.equals("button2")) { //$NON-NLS-1$
            modifiers |= InputEvent.BUTTON2_DOWN_MASK;
        } else if (strMod.equals("button3")) { //$NON-NLS-1$
            modifiers |= InputEvent.BUTTON3_DOWN_MASK;
        }
        return modifiers;
    }

    /**
     * Parses the typed id.
     * 
     * @param strTyped
     *            the str typed.
     * @return true, if successful.
     */
    private static boolean parseTypedID(String strTyped) {
        if (strTyped.equals("typed")) { //$NON-NLS-1$
            return true;
        }

        return false;
    }

    /**
     * Parses the typed key.
     * 
     * @param strChar
     *            the str char.
     * @return the char.
     */
    private static char parseTypedKey(String strChar) {
        char keyChar = KeyEvent.CHAR_UNDEFINED;

        if (strChar.length() != 1) {
            // awt.66=Invalid format
            throw new IllegalArgumentException(Messages.getString("awt.66")); //$NON-NLS-1$
        }
        keyChar = strChar.charAt(0);
        return keyChar;
    }

    /**
     * Parses the pressed released id.
     * 
     * @param str
     *            the str.
     * @return the boolean.
     */
    private static Boolean parsePressedReleasedID(String str) {

        if (str.equals("pressed")) { //$NON-NLS-1$
            return Boolean.FALSE;
        } else if (str.equals("released")) { //$NON-NLS-1$
            return Boolean.TRUE;
        }
        return null;
    }

    /**
     * Parses the key.
     * 
     * @param strCode
     *            the str code.
     * @return the int.
     */
    private static int parseKey(String strCode) {
        int keyCode = KeyEvent.VK_UNDEFINED;

        keyCode = getKeyCode(strCode);

        if (keyCode == KeyEvent.VK_UNDEFINED) {
            // awt.66=Invalid format
            throw new IllegalArgumentException(Messages.getString("awt.66")); //$NON-NLS-1$
        }
        return keyCode;
    }
}
