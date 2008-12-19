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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */
package java.awt.color;

/**
 * The CMMException is thrown as soon as a native CMM error occurs.
 * 
 * @since Android 1.0
 */
public class CMMException extends java.lang.RuntimeException {
    
    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 5775558044142994965L;

    /**
     * Instantiates a new CMM exception with detail message.
     * 
     * @param s
     *            the detail message of the exception.
     */
    public CMMException (String s) {
        super (s);
    }
}
