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
 *
 * @date: Oct 5, 2005
 */

package java.awt.image;

/**
 * The ImagingOpException class provides error notification when the
 * BufferedImageOp or RasterOp filter methods can not perform the desired filter
 * operation.
 * 
 * @since Android 1.0
 */
public class ImagingOpException extends RuntimeException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = 8026288481846276658L;

    /**
     * Instantiates a new ImagingOpException with a detail message.
     * 
     * @param s
     *            the detail message.
     */
    public ImagingOpException(String s) {
        super(s);
    }
}
