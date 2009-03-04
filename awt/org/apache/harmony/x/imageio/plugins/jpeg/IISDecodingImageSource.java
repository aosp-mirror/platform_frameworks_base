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
 * @author Rustem Rafikov
 * @version $Revision: 1.2 $
 */
package org.apache.harmony.x.imageio.plugins.jpeg;

import javax.imageio.stream.ImageInputStream;

import org.apache.harmony.awt.gl.image.DecodingImageSource;

import java.io.InputStream;
import java.io.IOException;

/**
 * This allows usage of the java2d jpegdecoder with ImageInputStream in
 * the JPEGImageReader. Temporary, only to make JPEGImageReader#read(..)
 * working.
 *
 */
public class IISDecodingImageSource extends DecodingImageSource {

    private final InputStream is;

    public IISDecodingImageSource(ImageInputStream iis) {
        is = new IISToInputStreamWrapper(iis);
    }

    @Override
    protected boolean checkConnection() {
        return true;
    }

    @Override
    protected InputStream getInputStream() {
        return is;
    }

    static class IISToInputStreamWrapper extends InputStream {

        private ImageInputStream input;

        public IISToInputStreamWrapper(ImageInputStream input) {
            this.input=input;
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public int read(byte[] b) throws IOException {
            return input.read(b);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return input.read(b, off, len);
        }

        @Override
        public long skip(long n) throws IOException {
            return input.skipBytes(n);
        }

        @Override
        public boolean markSupported() {
        	return true;  // This is orig
        	
            // ???AWT: FIXME
        	// This is an error in Harmony. Not all input streams
        	// have mark support and it is not ok to just return true. 
        	// There should be an input.markSupported(). However, if 
        	// this call returns false, nothing works anymore.
        	
        	// The backside is that BitmapFactory uses a call to markSupport()
        	// to find out if it needs to warp the stream in a
        	// BufferedInputStream to get mark support, and this fails!
        	
        	// Currently, the hack is in BitmapFactory, where we always
        	// wrap the stream in a BufferedInputStream.
        }

        @Override
        public void mark(int readlimit) {
            input.mark();
        }

        @Override
        public void reset() throws IOException {
            input.reset();
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }
}
