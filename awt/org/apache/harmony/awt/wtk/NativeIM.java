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
package org.apache.harmony.awt.wtk;

import java.awt.AWTEvent;
import java.awt.AWTException;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.im.spi.InputMethod;
import java.awt.im.spi.InputMethodContext;
import java.awt.im.spi.InputMethodDescriptor;
import java.lang.Character.Subset;
import java.util.Locale;

/**
 * A cross-platform interface for native input
 * method sub-system functionality.
 */
public abstract class NativeIM implements InputMethod, InputMethodDescriptor {
    protected InputMethodContext imc;

    public void activate() {

    }

    public void deactivate(boolean isTemporary) {

    }

    public void dispatchEvent(AWTEvent event) {

    }

    public void dispose() {

    }

    public void endComposition() {

    }

    public Object getControlObject() {
        return null;
    }

    public Locale getLocale() {
        return null;
    }

    public void hideWindows() {

    }

    public boolean isCompositionEnabled() {
        return false;
    }

    public void notifyClientWindowChange(Rectangle bounds) {

    }

    public void reconvert() {

    }

    public void removeNotify() {

    }

    public void setCharacterSubsets(Subset[] subsets) {

    }
    
    public void setCompositionEnabled(boolean enable) {

    }

    public void setInputMethodContext(InputMethodContext context) {
        imc = context;
    }

    public boolean setLocale(Locale locale) {
        return false;
    }

    public Locale[] getAvailableLocales() throws AWTException {
    	return new Locale[]{Locale.getDefault(), Locale.ENGLISH};
        //return new Locale[]{Locale.getDefault(), Locale.US};
    }

    public InputMethod createInputMethod() throws Exception {        
        return this;
    }

    public String getInputMethodDisplayName(Locale inputLocale,
                                            Locale displayLanguage) {
        return "System input methods"; //$NON-NLS-1$
    }

    public Image getInputMethodIcon(Locale inputLocale) {
        return null;
    }

    public boolean hasDynamicLocaleList() {
        return false;
    }
    
    public abstract void disableIME();
    
//    public abstract void disableIME(long id);

}
