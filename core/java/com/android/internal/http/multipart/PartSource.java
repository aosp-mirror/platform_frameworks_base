/*
 * $Header: /home/jerenkrantz/tmp/commons/commons-convert/cvs/home/cvs/jakarta-commons//httpclient/src/java/org/apache/commons/httpclient/methods/multipart/PartSource.java,v 1.6 2004/04/18 23:51:37 jsdever Exp $
 * $Revision: 480424 $
 * $Date: 2006-11-29 06:56:49 +0100 (Wed, 29 Nov 2006) $
 *
 * ====================================================================
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package com.android.internal.http.multipart;

import java.io.IOException;
import java.io.InputStream;

/**
 * An interface for providing access to data when posting MultiPart messages.
 * 
 * @see FilePart
 * 
 * @author <a href="mailto:becke@u.washington.edu">Michael Becke</a>
 *   
 * @since 2.0 
 */
public interface PartSource {

    /**
     * Gets the number of bytes contained in this source.
     * 
     * @return a value >= 0
     */
    long getLength();
    
    /**
     * Gets the name of the file this source represents.
     * 
     * @return the fileName used for posting a MultiPart file part
     */
    String getFileName();
    
    /**
     * Gets a new InputStream for reading this source.  This method can be 
     * called more than once and should therefore return a new stream every
     * time.
     * 
     * @return a new InputStream
     * 
     * @throws IOException if an error occurs when creating the InputStream
     */
    InputStream createInputStream() throws IOException;

}
