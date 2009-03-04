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
 * @author Rustem V. Rafikov
 * @version $Revision: 1.3 $
 */

package javax.imageio;

import java.io.IOException;

/**
 * The IIOException class indicates errors in reading/writing operations.
 * 
 * @since Android 1.0
 */
public class IIOException extends IOException {

    /**
     * The Constant serialVersionUID.
     */
    private static final long serialVersionUID = -3216210718638985251L;

    /**
     * Instantiates a new IIOException.
     * 
     * @param message
     *            the detailed message.
     */
    public IIOException(String message) {
        super(message);
    }

    /**
     * Instantiates a new IIOException.
     * 
     * @param message
     *            the detailed message.
     * @param cause
     *            the cause of this exception.
     */
    public IIOException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }
}
