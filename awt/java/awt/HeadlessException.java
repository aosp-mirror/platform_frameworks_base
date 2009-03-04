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
 * @author Alexey A. Petrenko
 * @version $Revision$
 */

package java.awt;

/**
 * The HeadlessException class provides notifications and error messages when
 * code that is dependent on a keyboard, display, or mouse is called in an
 * environment that does not support a keyboard, display, or mouse.
 * 
 * @since Android 1.0
 */
public class HeadlessException extends UnsupportedOperationException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 167183644944358563L;

    /**
     * Instantiates a new headless exception.
     */
    public HeadlessException() {
        super();
    }

    /**
     * Instantiates a new headless exception with the specified message.
     * 
     * @param msg
     *            the String which represents error message.
     */
    public HeadlessException(String msg) {
        super(msg);
    }
}
