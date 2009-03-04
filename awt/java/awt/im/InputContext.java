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
 * @author Pavel Dolgov
 * @version $Revision$
 */
package java.awt.im;

import java.awt.AWTEvent;
//???AWT: import java.awt.Component;
import java.util.Locale;

import org.apache.harmony.awt.im.InputMethodContext;

/**
 * This class is not supported in Android 1.0. It is merely provided to maintain
 * interface compatibility with desktop Java implementations.
 * 
 * @since Android 1.0
 */
public class InputContext {
    protected InputContext() {
    }

    public static InputContext getInstance() {
        return new InputMethodContext();
    }

    public void dispatchEvent(AWTEvent event) {
    }

    public void dispose() {
    }

    public void endComposition() {
    }

    public Object getInputMethodControlObject() {
        return null;
    }

    public Locale getLocale() {
        return null;
    }

    public boolean isCompositionEnabled() {
        return false;
    }

    public void reconvert() {
    }

    //???AWT
    /*
    public void removeNotify(Component client) {
    }
    */

    public boolean selectInputMethod(Locale locale) {
        return false;
    }

    public void setCharacterSubsets(Character.Subset[] subsets) {
    }
    
    public void setCompositionEnabled(boolean enable) {
    }
}

