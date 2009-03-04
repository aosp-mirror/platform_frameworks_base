/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.content;


import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Interface to insert data to ContentResolver
 * @hide
 */
public interface ContentInsertHandler extends ContentHandler {
    /**
     * insert data from InputStream to ContentResolver
     * @param contentResolver
     * @param in InputStream
     * @throws IOException
     * @throws SAXException
     */
    public void insert(ContentResolver contentResolver, InputStream in) 
        throws IOException, SAXException;
    
    /**
     * insert data from String to ContentResolver
     * @param contentResolver
     * @param in input string
     * @throws SAXException
     */
    public void insert(ContentResolver contentResolver, String in) 
        throws SAXException;
    
}
