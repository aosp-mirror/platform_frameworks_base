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
 * @author Igor V. Stolyarov
 * @version $Revision$
 */
/*
 * Created on 10.02.2005
 *
 */
package org.apache.harmony.awt.gl.image;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

public class ByteArrayDecodingImageSource extends DecodingImageSource {

    byte imagedata[];
    int imageoffset;
    int imagelength;

    public ByteArrayDecodingImageSource(byte imagedata[], int imageoffset,
            int imagelength){
        this.imagedata = imagedata;
        this.imageoffset = imageoffset;
        this.imagelength = imagelength;
    }

    public ByteArrayDecodingImageSource(byte imagedata[]){
        this(imagedata, 0, imagedata.length);
    }

    @Override
    protected boolean checkConnection() {
        return true;
    }

    @Override
    protected InputStream getInputStream() {
        // BEGIN android-modified
        // TODO: Why does a ByteArrayInputStream need to be buffered at all?
        return new BufferedInputStream(new ByteArrayInputStream(imagedata,
                        imageoffset, imagelength), 1024);
        // END android-modified
    }

}
