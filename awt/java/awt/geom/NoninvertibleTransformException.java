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
 * @author Denis M. Kishenko
 * @version $Revision$
 */

package java.awt.geom;

/**
 * The Class NoninvertibleTransformException is the exception that is thrown
 * when an action requires inverting an {@link AffineTransform} that is not
 * invertible (has determinant 0).
 * 
 * @since Android 1.0
 */
public class NoninvertibleTransformException extends java.lang.Exception {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 6137225240503990466L;

    /**
     * Instantiates a new non-invertible transform exception.
     * 
     * @param s
     *            the error message.
     */
    public NoninvertibleTransformException(String s) {
        super(s);
    }

}
