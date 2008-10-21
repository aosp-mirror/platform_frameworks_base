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
package java.awt.im.spi;

import java.awt.AWTEvent;
import java.awt.Rectangle;
import java.util.Locale;

public interface InputMethod {

    public void activate();

    public void deactivate(boolean isTemporary);

    public void dispatchEvent(AWTEvent event);

    public void dispose();

    public void endComposition();

    public Object getControlObject();

    public Locale getLocale();

    public void hideWindows();

    public boolean isCompositionEnabled();

    public void notifyClientWindowChange(Rectangle bounds);

    public void reconvert();

    public void removeNotify();

    public void setCharacterSubsets(Character.Subset[] subsets);

    public void setCompositionEnabled(boolean enable);

    public void setInputMethodContext(InputMethodContext context);

    public boolean setLocale(Locale locale);
}

