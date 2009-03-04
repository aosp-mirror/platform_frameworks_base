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
 * @author Michael Danilov
 * @version $Revision$
 */

package java.awt;

/**
 * The IllegalComponentStateException class is used to provide notification that
 * AWT component is not in an appropriate state for the requested operation.
 * 
 * @since Android 1.0
 */
public class IllegalComponentStateException extends IllegalStateException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -1889339587208144238L;

    /**
     * Instantiates a new IllegalComponentStateException with the specified
     * message.
     * 
     * @param s
     *            the String message which describes the exception.
     */
    public IllegalComponentStateException(String s) {
        super(s);
    }

    /**
     * Instantiates a new IllegalComponentStateException without detailed
     * message.
     */
    public IllegalComponentStateException() {
    }

}
