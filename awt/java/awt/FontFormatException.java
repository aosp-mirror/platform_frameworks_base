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
 * @author Ilya S. Okomin
 * @version $Revision$
 */

package java.awt;

/**
 * The FontFormatException class is used to provide notification and information
 * that font can't be created.
 * 
 * @since Android 1.0
 */
public class FontFormatException extends Exception {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -4481290147811361272L;

    /**
     * Instantiates a new font format exception with detailed message.
     * 
     * @param reason
     *            the detailed message.
     */
    public FontFormatException(String reason) {
        super(reason);
    }

}
