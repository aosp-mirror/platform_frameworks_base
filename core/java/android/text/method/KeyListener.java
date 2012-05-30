/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.text.method;

import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;

/**
 * Interface for converting text key events into edit operations on an
 * Editable class.  Note that for most cases this interface has been
 * superceded by general soft input methods as defined by
 * {@link android.view.inputmethod.InputMethod}; it should only be used
 * for cases where an application has its own on-screen keypad and also wants
 * to process hard keyboard events to match it.
 * <p></p>
 * Key presses on soft input methods are not required to trigger the methods
 * in this listener, and are in fact discouraged to do so.  The default
 * android keyboard will not trigger these for any key to any application
 * targetting Jelly Bean or later, and will only deliver it for some
 * key presses to applications targetting Ice Cream Sandwich or earlier.
 */
public interface KeyListener {
    /**
     * Return the type of text that this key listener is manipulating,
     * as per {@link android.text.InputType}.  This is used to
     * determine the mode of the soft keyboard that is shown for the editor.
     * 
     * <p>If you return
     * {@link android.text.InputType#TYPE_NULL}
     * then <em>no</em> soft keyboard will provided.  In other words, you
     * must be providing your own key pad for on-screen input and the key
     * listener will be used to handle input from a hard keyboard.
     * 
     * <p>If you
     * return any other value, a soft input method will be created when the
     * user puts focus in the editor, which will provide a keypad and also
     * consume hard key events.  This means that the key listener will generally
     * not be used, instead the soft input method will take care of managing
     * key input as per the content type returned here.
     */
    public int getInputType();
    
    /**
     * If the key listener wants to handle this key, return true,
     * otherwise return false and the caller (i.e. the widget host)
     * will handle the key.
     */
    public boolean onKeyDown(View view, Editable text,
                             int keyCode, KeyEvent event);

    /**
     * If the key listener wants to handle this key release, return true,
     * otherwise return false and the caller (i.e. the widget host)
     * will handle the key.
     */
    public boolean onKeyUp(View view, Editable text,
                           int keyCode, KeyEvent event);
    
    /**
     * If the key listener wants to other kinds of key events, return true,
     * otherwise return false and the caller (i.e. the widget host)
     * will handle the key.
     */
    public boolean onKeyOther(View view, Editable text, KeyEvent event);
    
    /**
     * Remove the given shift states from the edited text.
     */
    public void clearMetaKeyState(View view, Editable content, int states);
}
