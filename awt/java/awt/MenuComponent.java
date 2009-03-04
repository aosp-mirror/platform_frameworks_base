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

package java.awt;

import java.awt.event.FocusListener;
import java.awt.event.MouseEvent;
import java.awt.peer.MenuComponentPeer;
import java.io.Serializable;
import java.util.Locale; //import javax.accessibility.Accessible;
//import javax.accessibility.AccessibleComponent;
//import javax.accessibility.AccessibleContext;
//import javax.accessibility.AccessibleRole;
//import javax.accessibility.AccessibleSelection;
//import javax.accessibility.AccessibleStateSet;
import org.apache.harmony.awt.gl.MultiRectArea;
import org.apache.harmony.awt.state.MenuItemState;
import org.apache.harmony.awt.state.MenuState;
import org.apache.harmony.luni.util.NotImplementedException;

/**
 * The MenuComponent abstract class is the superclass for menu components. Menu
 * components receive and process AWT events.
 * 
 * @since Android 1.0
 */
public abstract class MenuComponent implements Serializable {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -4536902356223894379L;

    /**
     * The name.
     */
    private String name;

    /**
     * The font.
     */
    private Font font;

    /**
     * The parent.
     */
    MenuContainer parent;

    /**
     * The deprecated event handler.
     */
    boolean deprecatedEventHandler = true;

    /**
     * The selected item index.
     */
    private int selectedItemIndex;

    // ???AWT: private AccessibleContext accessibleContext;

    /**
     * The toolkit.
     */
    final Toolkit toolkit = Toolkit.getDefaultToolkit();

    // ???AWT
    /*
     * protected abstract class AccessibleAWTMenuComponent extends
     * AccessibleContext implements Serializable, AccessibleComponent,
     * AccessibleSelection { private static final long serialVersionUID =
     * -4269533416223798698L; public void addFocusListener(FocusListener
     * listener) { } public boolean contains(Point pt) { return false; } public
     * Accessible getAccessibleAt(Point pt) { return null; } public Color
     * getBackground() { return null; } public Rectangle getBounds() { return
     * null; } public Cursor getCursor() { return null; } public Font getFont()
     * { return MenuComponent.this.getFont(); } public FontMetrics
     * getFontMetrics(Font font) { return null; } public Color getForeground() {
     * return null; } public Point getLocation() { return null; } public Point
     * getLocationOnScreen() { return null; } public Dimension getSize() {
     * return null; } public boolean isEnabled() { return true; // always
     * enabled } public boolean isFocusTraversable() { return true; // always
     * focus traversable } public boolean isShowing() { return true;// always
     * showing } public boolean isVisible() { return true; // always visible }
     * public void removeFocusListener(FocusListener listener) { } public void
     * requestFocus() { } public void setBackground(Color color) { } public void
     * setBounds(Rectangle rect) { } public void setCursor(Cursor cursor) { }
     * public void setEnabled(boolean enabled) { } public void setFont(Font
     * font) { MenuComponent.this.setFont(font); } public void
     * setForeground(Color color) { } public void setLocation(Point pt) { }
     * public void setSize(Dimension pt) { } public void setVisible(boolean
     * visible) { } public void addAccessibleSelection(int index) { } public
     * void clearAccessibleSelection() { } public Accessible
     * getAccessibleSelection(int index) { return null; } public int
     * getAccessibleSelectionCount() { return 0; } public boolean
     * isAccessibleChildSelected(int index) { return false; } public void
     * removeAccessibleSelection(int index) { } public void
     * selectAllAccessibleSelection() { }
     * @Override public Accessible getAccessibleChild(int index) { return null;
     * }
     * @Override public int getAccessibleChildrenCount() { return 0; }
     * @Override public AccessibleComponent getAccessibleComponent() { return
     * this; }
     * @Override public String getAccessibleDescription() { return
     * super.getAccessibleDescription(); }
     * @Override public int getAccessibleIndexInParent() { toolkit.lockAWT();
     * try { Accessible aParent = getAccessibleParent(); int aIndex = -1; if
     * (aParent instanceof MenuComponent) { MenuComponent parent =
     * (MenuComponent) aParent; int count = parent.getItemCount(); for (int i =
     * 0; i < count; i++) { MenuComponent comp = parent.getItem(i); if (comp
     * instanceof Accessible) { aIndex++; if (comp == MenuComponent.this) {
     * return aIndex; } } } } return -1; } finally { toolkit.unlockAWT(); } }
     * @Override public String getAccessibleName() { return
     * super.getAccessibleName(); }
     * @Override public Accessible getAccessibleParent() { toolkit.lockAWT();
     * try { Accessible aParent = super.getAccessibleParent(); if (aParent !=
     * null) { return aParent; } MenuContainer parent = getParent(); if (parent
     * instanceof Accessible) { aParent = (Accessible) parent; } return aParent;
     * } finally { toolkit.unlockAWT(); } }
     * @Override public AccessibleRole getAccessibleRole() { return
     * AccessibleRole.AWT_COMPONENT; }
     * @Override public AccessibleSelection getAccessibleSelection() { return
     * this; }
     * @Override public AccessibleStateSet getAccessibleStateSet() { return new
     * AccessibleStateSet(); }
     * @Override public Locale getLocale() { return Locale.getDefault(); } }
     */

    /**
     * The accessor to MenuComponent internal state, utilized by the visual
     * theme.
     * 
     * @throws HeadlessException
     *             the headless exception.
     */
    // ???AWT
    /*
     * class State implements MenuState { Dimension size; Dimension getSize() {
     * if (size == null) { calculate(); } return size; } public int getWidth() {
     * return getSize().width; } public int getHeight() { return
     * getSize().height; } public Font getFont() { return
     * MenuComponent.this.getFont(); } public int getItemCount() { return
     * MenuComponent.this.getItemCount(); } public int getSelectedItemIndex() {
     * return MenuComponent.this.getSelectedItemIndex(); } public boolean
     * isFontSet() { return MenuComponent.this.isFontSet(); }
     * @SuppressWarnings("deprecation") public FontMetrics getFontMetrics(Font
     * f) { return MenuComponent.this.toolkit.getFontMetrics(f); } public Point
     * getLocation() { return MenuComponent.this.getLocation(); } public
     * MenuItemState getItem(int index) { MenuItem item =
     * MenuComponent.this.getItem(index); return item.itemState; } public void
     * setSize(int w, int h) { this.size = new Dimension(w, h); } void
     * calculate() { size = new Dimension();
     * size.setSize(toolkit.theme.calculateMenuSize(this)); } void reset() { for
     * (int i = 0; i < getItemCount(); i++) { ((MenuItem.State)
     * getItem(i)).reset(); } } }
     */

    /**
     * Pop-up box for menu. It transfers the paint events, keyboard and mouse
     * events to the menu component itself.
     */
    // ???AWT
    /*
     * class MenuPopupBox extends PopupBox { private final Point lastMousePos =
     * new Point();
     * @Override boolean isMenu() { return true; }
     * @Override void paint(Graphics gr) { MenuComponent.this.paint(gr); }
     * @Override void onKeyEvent(int eventId, int vKey, long when, int
     * modifiers) { MenuComponent.this.onKeyEvent(eventId, vKey, when,
     * modifiers); }
     * @Override void onMouseEvent(int eventId, Point where, int mouseButton,
     * long when, int modifiers, int wheelRotation) { // prevent conflict of
     * mouse and keyboard // when sub-menu drops down due to keyboard navigation
     * if (lastMousePos.equals(where) && (eventId == MouseEvent.MOUSE_MOVED ||
     * eventId == MouseEvent.MOUSE_ENTERED)) { return; }
     * lastMousePos.setLocation(where); MenuComponent.this.onMouseEvent(eventId,
     * where, mouseButton, when, modifiers); } }
     */

    /**
     * Instantiates a new MenuComponent object.
     * 
     * @throws HeadlessException
     *             if the graphical interface environment can't support
     *             MenuComponents.
     */
    public MenuComponent() throws HeadlessException {
        toolkit.lockAWT();
        try {
            Toolkit.checkHeadless();
            name = autoName();
            selectedItemIndex = -1;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the name of the MenuComponent object.
     * 
     * @return the name of the MenuComponent object.
     */
    public String getName() {
        toolkit.lockAWT();
        try {
            return name;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns a String representation of the MenuComponent object.
     * 
     * @return a String representation of the MenuComponent object.
     */
    @Override
    public String toString() {
        toolkit.lockAWT();
        try {
            return getClass().getName() + "[" + paramString() + "]"; //$NON-NLS-1$ //$NON-NLS-2$
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Gets the parent menu container.
     * 
     * @return the parent.
     */
    public MenuContainer getParent() {
        toolkit.lockAWT();
        try {
            return parent;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the name of the MenuComponent to the specified string.
     * 
     * @param name
     *            the new name of the MenuComponent object.
     */
    public void setName(String name) {
        toolkit.lockAWT();
        try {
            this.name = name;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Dispatches AWT event.
     * 
     * @param event
     *            the AWTEvent.
     */
    public final void dispatchEvent(AWTEvent event) {
        toolkit.lockAWT();
        try {
            processEvent(event);
            if (deprecatedEventHandler) {
                postDeprecatedEvent(event);
            }
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Post deprecated event.
     * 
     * @param event
     *            the event.
     */
    void postDeprecatedEvent(AWTEvent event) {
        Event evt = event.getEvent();
        if (evt != null) {
            postEvent(evt);
        }
    }

    /**
     * Gets the peer of the MenuComponent; an application must not use this
     * method directly.
     * 
     * @return the MenuComponentPeer object.
     * @throws NotImplementedException
     *             if this method is not implemented by a subclass.
     * @deprecated an application must not use this method directly.
     */
    @Deprecated
    public MenuComponentPeer getPeer() throws org.apache.harmony.luni.util.NotImplementedException {
        toolkit.lockAWT();
        try {
        } finally {
            toolkit.unlockAWT();
        }
        if (true) {
            throw new RuntimeException("Method is not implemented"); //TODO: implement //$NON-NLS-1$
        }
        return null;
    }

    /**
     * Gets the locking object of this MenuComponent.
     * 
     * @return the locking object of this MenuComponent.
     */
    protected final Object getTreeLock() {
        return toolkit.awtTreeLock;
    }

    /**
     * Posts the Event to the MenuComponent.
     * 
     * @param e
     *            the Event.
     * @return true, if the event is posted successfully, false otherwise.
     * @deprecated Replaced dispatchEvent method.
     */
    @SuppressWarnings("deprecation")
    @Deprecated
    public boolean postEvent(Event e) {
        toolkit.lockAWT();
        try {
            if (parent != null) {
                return parent.postEvent(e);
            }
            return false;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Returns the string representation of the MenuComponent state.
     * 
     * @return returns the string representation of the MenuComponent state.
     */
    protected String paramString() {
        toolkit.lockAWT();
        try {
            return getName();
        } finally {
            toolkit.unlockAWT();
        }
    }

    // ???AWT
    /*
     * public AccessibleContext getAccessibleContext() { toolkit.lockAWT(); try
     * { if (accessibleContext == null) { accessibleContext =
     * createAccessibleContext(); } return accessibleContext; } finally {
     * toolkit.unlockAWT(); } }
     */

    /**
     * Gets the font of the MenuComponent object.
     * 
     * @return the Font of the MenuComponent object.
     */
    public Font getFont() {
        toolkit.lockAWT();
        try {
            if (font == null && hasDefaultFont()) {
                return toolkit.getDefaultFont();
            }
            if (font == null && parent != null) {
                return parent.getFont();
            }
            return font;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Checks if is font set.
     * 
     * @return true, if is font set
     */
    boolean isFontSet() {
        return font != null
                || ((parent instanceof MenuComponent) && ((MenuComponent)parent).isFontSet());
    }

    /**
     * Checks for default font.
     * 
     * @return true, if successful.
     */
    boolean hasDefaultFont() {
        return false;
    }

    /**
     * Processes an AWTEevent on this menu component.
     * 
     * @param event
     *            the AWTEvent.
     */
    protected void processEvent(AWTEvent event) {
        toolkit.lockAWT();
        try {
            // do nothing
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Removes the peer of the MenuComponent.
     */
    public void removeNotify() {
        toolkit.lockAWT();
        try {
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the Font for this MenuComponent object.
     * 
     * @param font
     *            the new Font to be used for this MenuComponent.
     */
    public void setFont(Font font) {
        toolkit.lockAWT();
        try {
            this.font = font;
        } finally {
            toolkit.unlockAWT();
        }
    }

    /**
     * Sets the parent.
     * 
     * @param parent
     *            the new parent.
     */
    void setParent(MenuContainer parent) {
        this.parent = parent;
    }

    /**
     * Gets the location.
     * 
     * @return the location.
     */
    Point getLocation() {
        // to be overridden
        return new Point(0, 0);
    }

    /**
     * Gets the width.
     * 
     * @return the width.
     */
    int getWidth() {
        // to be overridden
        return 1;
    }

    /**
     * Gets the height.
     * 
     * @return the height.
     */
    int getHeight() {
        // to be overridden
        return 1;
    }

    /**
     * Recursively find the menu item for a menu shortcut.
     * 
     * @param gr
     *            the gr.
     * @return the menu item; or null if the item is not available for this
     *         shortcut.
     */
    // ???AWT
    /*
     * MenuItem getShortcutMenuItemImpl(MenuShortcut ms) { if (ms == null) {
     * return null; } for (int i = 0; i < getItemCount(); i++) { MenuItem mi =
     * getItem(i); if (mi instanceof Menu) { mi = ((Menu)
     * mi).getShortcutMenuItemImpl(ms); if (mi != null) { return mi; } } else if
     * (ms.equals(mi.getShortcut())) { return mi; } } return null; }
     */

    void paint(Graphics gr) {
        gr.setColor(Color.LIGHT_GRAY);
        gr.fillRect(0, 0, getWidth(), getHeight());
        gr.setColor(Color.BLACK);
    }

    /**
     * Mouse events handler.
     * 
     * @param eventId
     *            one of the MouseEvent.MOUSE_* constants.
     * @param where
     *            mouse location.
     * @param mouseButton
     *            mouse button that was pressed or released.
     * @param when
     *            event time.
     * @param modifiers
     *            input event modifiers.
     */
    void onMouseEvent(int eventId, Point where, int mouseButton, long when, int modifiers) {
        // to be overridden
    }

    /**
     * Keyboard event handler.
     * 
     * @param eventId
     *            one of the KeyEvent.KEY_* constants.
     * @param vKey
     *            the key code.
     * @param when
     *            event time.
     * @param modifiers
     *            input event modifiers.
     */
    void onKeyEvent(int eventId, int vKey, long when, int modifiers) {
        // to be overridden
    }

    /**
     * Post the ActionEvent or ItemEvent, depending on type of the menu item.
     * 
     * @param index
     *            the index.
     * @return the item rect.
     */
    // ???AWT
    /*
     * void fireItemAction(int item, long when, int modifiers) { MenuItem mi =
     * getItem(item); mi.itemSelected(when, modifiers); } MenuItem getItem(int
     * index) { // to be overridden return null; } int getItemCount() { return
     * 0; }
     */

    /**
     * @return The sub-menu of currently selecetd item, or null if such a
     *         sub-menu is not available.
     */
    // ???AWT
    /*
     * Menu getSelectedSubmenu() { if (selectedItemIndex < 0) { return null; }
     * MenuItem item = getItem(selectedItemIndex); return (item instanceof Menu)
     * ? (Menu) item : null; }
     */

    /**
     * Convenience method for selectItem(index, true).
     */
    // ???AWT
    /*
     * void selectItem(int index) { selectItem(index, true); }
     */

    /**
     * Change the selection in the menu.
     * 
     * @param index
     *            new selecetd item's index.
     * @param showSubMenu
     *            if new selected item has a sub-menu, should that sub-menu be
     *            displayed.
     */
    // ???AWT
    /*
     * void selectItem(int index, boolean showSubMenu) { if (selectedItemIndex
     * == index) { return; } if (selectedItemIndex >= 0 &&
     * getItem(selectedItemIndex) instanceof Menu) { ((Menu)
     * getItem(selectedItemIndex)).hide(); } MultiRectArea clip =
     * getUpdateClip(index, selectedItemIndex); selectedItemIndex = index;
     * Graphics gr = getGraphics(clip); if (gr != null) { paint(gr); } if
     * (showSubMenu) { showSubMenu(selectedItemIndex); } }
     */

    /**
     * Change the selected item to the next one in the requested direction
     * moving cyclically, skipping separators
     * 
     * @param forward
     *            the direction to move the selection.
     * @param showSubMenu
     *            if new selected item has a sub-menu, should that sub-menu be
     *            displayed.
     */
    // ???AWT
    /*
     * void selectNextItem(boolean forward, boolean showSubMenu) { int selected
     * = getSelectedItemIndex(); int count = getItemCount(); if (count == 0) {
     * return; } if (selected < 0) { selected = (forward ? count - 1 : 0); } int
     * i = selected; do { i = (forward ? (i + 1) : (i + count - 1)) % count; i
     * %= count; MenuItem item = getItem(i); if (!"-".equals(item.getLabel())) {
     * //$NON-NLS-1$ selectItem(i, showSubMenu); return; } } while (i !=
     * selected); } void showSubMenu(int index) { if ((index < 0) ||
     * !isActive()) { return; } MenuItem item = getItem(index); if (item
     * instanceof Menu) { Menu menu = ((Menu) getItem(index)); if
     * (menu.getItemCount() == 0) { return; } Point location =
     * getSubmenuLocation(index); menu.show(location.x, location.y, false); } }
     */

    /**
     * @return the menu bar which is the root of current menu's hierarchy; or
     *         null if the hierarchy root is not a menu bar.
     */
    // ???AWT
    /*
     * MenuBar getMenuBar() { if (parent instanceof MenuBar) { return (MenuBar)
     * parent; } if (parent instanceof MenuComponent) { return ((MenuComponent)
     * parent).getMenuBar(); } return null; } PopupBox getPopupBox() { return
     * null; }
     */

    Rectangle getItemRect(int index) {
        // to be overridden
        return null;
    }

    /**
     * Determine the clip region when menu selection is changed from index1 to
     * index2.
     * 
     * @param index1
     *            old selected item.
     * @param index2
     *            new selected item.
     * @return the region to repaint.
     */
    final MultiRectArea getUpdateClip(int index1, int index2) {
        MultiRectArea clip = new MultiRectArea();
        if (index1 >= 0) {
            clip.add(getItemRect(index1));
        }
        if (index2 >= 0) {
            clip.add(getItemRect(index2));
        }
        return clip;
    }

    /**
     * Gets the submenu location.
     * 
     * @param index
     *            the index.
     * @return the submenu location.
     */
    Point getSubmenuLocation(int index) {
        // to be overridden
        return new Point(0, 0);
    }

    /**
     * Gets the selected item index.
     * 
     * @return the selected item index.
     */
    int getSelectedItemIndex() {
        return selectedItemIndex;
    }

    /**
     * Hide.
     */
    void hide() {
        selectedItemIndex = -1;
        if (parent instanceof MenuComponent) {
            ((MenuComponent)parent).itemHidden(this);
        }
    }

    /**
     * Item hidden.
     * 
     * @param mc
     *            the mc.
     */
    void itemHidden(MenuComponent mc) {
        // to be overridden
    }

    /**
     * Checks if is visible.
     * 
     * @return true, if is visible.
     */
    boolean isVisible() {
        return true;
    }

    /**
     * Checks if is active.
     * 
     * @return true, if is active.
     */
    boolean isActive() {
        return true;
    }

    /**
     * Hide all menu hierarchy.
     */
    void endMenu() {
        // ???AWT: toolkit.dispatcher.popupDispatcher.deactivateAll();
    }

    /**
     * Handle the mouse click or Enter key event on a menu's item.
     * 
     * @param when
     *            the event time.
     * @param modifiers
     *            input event modifiers.
     */
    void itemSelected(long when, int modifiers) {
        endMenu();
    }

    /**
     * Auto name.
     * 
     * @return the string.
     */
    String autoName() {
        String name = getClass().getName();
        if (name.indexOf("$") != -1) { //$NON-NLS-1$
            return null;
        }
        // ???AWT: int number = toolkit.autoNumber.nextMenuComponent++;
        int number = 0;
        name = name.substring(name.lastIndexOf(".") + 1) + Integer.toString(number); //$NON-NLS-1$
        return name;
    }

    /**
     * Creates the Graphics object for the pop-up box of this menu component.
     * 
     * @param clip
     *            the clip to set on this Graphics.
     * @return the created Graphics object, or null if such object is not
     *         available.
     */
    Graphics getGraphics(MultiRectArea clip) {
        // to be overridden
        return null;
    }

    /**
     * @return accessible context specific for particular menu component.
     */
    // ???AWT
    /*
     * AccessibleContext createAccessibleContext() { return null; }
     */
}
