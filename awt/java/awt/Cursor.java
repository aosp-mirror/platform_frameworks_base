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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.harmony.awt.internal.nls.Messages;
import org.apache.harmony.awt.wtk.NativeCursor;

/**
 * The Cursor class represents the bitmap of the mouse cursor.
 * 
 * @since Android 1.0
 */
public class Cursor implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 8028237497568985504L;

    /**
     * The Constant DEFAULT_CURSOR indicates the default cursor type.
     */
    public static final int DEFAULT_CURSOR = 0;

    /**
     * The Constant CROSSHAIR_CURSOR cursor type.
     */
    public static final int CROSSHAIR_CURSOR = 1;

    /**
     * The Constant TEXT_CURSOR cursor type.
     */
    public static final int TEXT_CURSOR = 2;

    /**
     * The Constant WAIT_CURSOR cursor type.
     */
    public static final int WAIT_CURSOR = 3;

    /**
     * The Constant SW_RESIZE_CURSOR cursor type.
     */
    public static final int SW_RESIZE_CURSOR = 4;

    /**
     * The Constant SE_RESIZE_CURSOR cursor type.
     */
    public static final int SE_RESIZE_CURSOR = 5;

    /**
     * The Constant NW_RESIZE_CURSOR cursor type.
     */
    public static final int NW_RESIZE_CURSOR = 6;

    /**
     * The Constant NE_RESIZE_CURSOR cursor type.
     */
    public static final int NE_RESIZE_CURSOR = 7;

    /**
     * The Constant N_RESIZE_CURSOR cursor type.
     */
    public static final int N_RESIZE_CURSOR = 8;

    /**
     * The Constant S_RESIZE_CURSOR cursor type.
     */
    public static final int S_RESIZE_CURSOR = 9;

    /**
     * The Constant W_RESIZE_CURSOR cursor type.
     */
    public static final int W_RESIZE_CURSOR = 10;

    /**
     * The Constant E_RESIZE_CURSOR cursor type.
     */
    public static final int E_RESIZE_CURSOR = 11;

    /**
     * The Constant HAND_CURSOR cursor type.
     */
    public static final int HAND_CURSOR = 12;

    /**
     * The Constant MOVE_CURSOR cursor type.
     */
    public static final int MOVE_CURSOR = 13;

    /**
     * A mapping from names to system custom cursors.
     */
    static Map<String, Cursor> systemCustomCursors;

    /**
     * The cursor props.
     */
    static Properties cursorProps;

    /**
     * The Constant predefinedNames.
     */
    static final String[] predefinedNames = {
            "Default", "Crosshair", "Text", "Wait", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Southwest Resize", "Southeast Resize", //$NON-NLS-1$ //$NON-NLS-2$
            "Northwest Resize", "Northeast Resize", //$NON-NLS-1$ //$NON-NLS-2$
            "North Resize", "South Resize", "West Resize", "East Resize", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
            "Hand", "Move" //$NON-NLS-1$ //$NON-NLS-2$

    };

    /**
     * The predefined set of cursors.
     */
    protected static Cursor[] predefined = {
            new Cursor(DEFAULT_CURSOR), null, null, null, null, null, null, null, null, null, null,
            null, null, null
    };

    /**
     * The Constant CUSTOM_CURSOR is associated with all custom cursor types.
     * (Those which are not predefined)
     */
    public static final int CUSTOM_CURSOR = -1;

    /**
     * The name of the cursor.
     */
    protected String name;

    /**
     * The type of the cursor, chosen from the list of cursor type constants.
     */
    private final int type;

    /**
     * The native cursor.
     */
    private transient NativeCursor nativeCursor;

    /**
     * The exact point on the cursor image that indicates which point the cursor
     * is selecting (pointing to). The coordinates are given with respect the
     * origin of the Image (its upper left corner).
     */
    private Point hotSpot;

    /**
     * The image to draw on the screen representing the cursor.
     */
    private Image image;

    /**
     * Instantiates a new cursor with the specified name.
     * 
     * @param name
     *            the name of cursor.
     */
    protected Cursor(String name) {
        this(name, null, new Point());
    }

    /**
     * Instantiates a new cursor of the specified type.
     * 
     * @param type
     *            the type of cursor.
     */
    public Cursor(int type) {
        checkType(type);
        this.type = type;
        if ((type >= 0) && (type < predefinedNames.length)) {
            name = predefinedNames[type] + " Cursor"; //$NON-NLS-1$
        }
    }

    /**
     * Instantiates a new cursor.
     * 
     * @param name
     *            the name.
     * @param img
     *            the img.
     * @param hotSpot
     *            the hot spot.
     */
    Cursor(String name, Image img, Point hotSpot) {
        this.name = name;
        type = CUSTOM_CURSOR;
        this.hotSpot = hotSpot;
        image = img;
    }

    /**
     * Finalize method overrides the finalize method from Object class.
     * 
     * @throws Throwable
     *             if the native cursor is not null and throws a Throwable when
     *             destroyed.
     */
    @Override
    protected void finalize() throws Throwable {
        if (nativeCursor != null) {
            nativeCursor.destroyCursor();
        }
    }

    /**
     * Gets the name of the cursor.
     * 
     * @return the name of the cursor.
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the String representation of the cursor.
     * 
     * @return the String representation of the cursor.
     */
    @Override
    public String toString() {
        return getClass().getName() + "[" + name + "]"; //$NON-NLS-1$ //$NON-NLS-2$
    }

    /**
     * Gets the cursor type.
     * 
     * @return the cursor type.
     */
    public int getType() {
        return type;
    }

    /**
     * Gets the predefined cursor with the specified type.
     * 
     * @param type
     *            the type of cursor.
     * @return the predefined cursor with the specified type.
     */
    public static Cursor getPredefinedCursor(int type) {
        checkType(type);
        Cursor cursor = predefined[type];
        if (cursor == null) {
            cursor = new Cursor(type);
            predefined[type] = cursor;
        }
        return cursor;
    }

    /**
     * Gets the default cursor.
     * 
     * @return the default cursor.
     */
    public static Cursor getDefaultCursor() {
        return getPredefinedCursor(DEFAULT_CURSOR);
    }

    /**
     * Gets the specified system custom cursor.
     * 
     * @param name
     *            the name of the desired system cursor.
     * @return the specific system cursor with the specified name.
     * @throws AWTException
     *             if the desired cursor has malformed data such as an
     *             incorrectly defined hot spot.
     * @throws HeadlessException
     *             if the isHeadless method of the GraphicsEnvironment returns
     *             true.
     */
    public static Cursor getSystemCustomCursor(String name) throws AWTException, HeadlessException {
        Toolkit.checkHeadless();
        return getSystemCustomCursorFromMap(name);
    }

    /**
     * Gets the specified system custom cursor from the map of system custom
     * cursors.
     * 
     * @param name
     *            the name of the desired cursor.
     * @return the desired system custom cursor from the map of system custom
     *         cursors.
     * @throws AWTException
     *             the AWT exception.
     */
    private static Cursor getSystemCustomCursorFromMap(String name) throws AWTException {
        loadCursorProps();
        if (systemCustomCursors == null) {
            systemCustomCursors = new HashMap<String, Cursor>();
        }
        Cursor cursor = systemCustomCursors.get(name);
        if (cursor != null) {
            return cursor;
        }
        // awt.141=failed to parse hotspot property for cursor:
        String exMsg = Messages.getString("awt.141") + name; //$NON-NLS-1$
        String nm = "Cursor." + name; //$NON-NLS-1$
        String nameStr = cursorProps.getProperty(nm + ".Name"); //$NON-NLS-1$
        String hotSpotStr = cursorProps.getProperty(nm + ".HotSpot"); //$NON-NLS-1$
        String fileStr = cursorProps.getProperty(nm + ".File"); //$NON-NLS-1$
        int idx = hotSpotStr.indexOf(',');
        if (idx < 0) {
            throw new AWTException(exMsg);
        }
        int x, y;
        try {
            x = new Integer(hotSpotStr.substring(0, idx)).intValue();
            y = new Integer(hotSpotStr.substring(idx + 1, hotSpotStr.length())).intValue();
        } catch (NumberFormatException nfe) {
            throw new AWTException(exMsg);
        }
        Image img = Toolkit.getDefaultToolkit().createImage(fileStr);
        cursor = new Cursor(nameStr, img, new Point(x, y));
        systemCustomCursors.put(name, cursor);

        return cursor;
    }

    /**
     * Load cursor props.
     * 
     * @throws AWTException
     *             the AWT exception.
     */
    private static void loadCursorProps() throws AWTException {
        if (cursorProps != null) {
            return;
        }
        String sep = File.separator;
        String cursorsDir = "lib" + sep + "images" + sep + "cursors"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String cursorsAbsDir = System.getProperty("java.home") + sep + //$NON-NLS-1$
                cursorsDir;
        String cursorPropsFileName = "cursors.properties"; //$NON-NLS-1$
        String cursorPropsFullFileName = (cursorsAbsDir + sep + cursorPropsFileName);
        cursorProps = new Properties();
        try {
            cursorProps.load(new FileInputStream(new File(cursorPropsFullFileName)));
        } catch (FileNotFoundException e) {
            // awt.142=Exception: class {0} {1} occurred while loading: {2}
            throw new AWTException(Messages.getString("awt.142",//$NON-NLS-1$
                    new Object[] {
                            e.getClass(), e.getMessage(), cursorPropsFullFileName
                    }));
        } catch (IOException e) {
            throw new AWTException(e.getMessage());
        }

    }

    /**
     * Check type.
     * 
     * @param type
     *            the type.
     */
    static void checkType(int type) {
        // can't use predefined array here because it may not have been
        // initialized yet
        if ((type < 0) || (type >= predefinedNames.length)) {
            // awt.143=illegal cursor type
            throw new IllegalArgumentException(Messages.getString("awt.143")); //$NON-NLS-1$
        }
    }

    // "lazily" create native cursors:
    /**
     * Gets the native cursor.
     * 
     * @return the native cursor.
     */
    NativeCursor getNativeCursor() {
        if (nativeCursor != null) {
            return nativeCursor;
        }
        Toolkit toolkit = Toolkit.getDefaultToolkit();
        if (type != CUSTOM_CURSOR) {
            nativeCursor = toolkit.createNativeCursor(type);
        } else {
            nativeCursor = toolkit.createCustomNativeCursor(image, hotSpot, name);
        }
        return nativeCursor;
    }

    /**
     * Sets the native cursor.
     * 
     * @param nativeCursor
     *            the new native cursor.
     */
    void setNativeCursor(NativeCursor nativeCursor) {
        this.nativeCursor = nativeCursor;
    }
}
