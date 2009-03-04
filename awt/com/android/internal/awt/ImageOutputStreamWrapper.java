/*
 * Copyright 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *     http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

package com.android.internal.awt;

import java.io.IOException;
import java.io.OutputStream;

import javax.imageio.stream.ImageOutputStream;

public class ImageOutputStreamWrapper extends OutputStream {
	
	protected ImageOutputStream mIos;
	
	private byte[] mBuff;
	
	public ImageOutputStreamWrapper(ImageOutputStream ios) {
		if (null == ios) {
			throw new IllegalArgumentException("ImageOutputStream must not be null");
		}
		this.mIos = ios;
		this.mBuff = new byte[1];
	}

	public ImageOutputStream getImageOutputStream() {
		return mIos;
	}
	
	@Override
	public void write(int oneByte) throws IOException {
		mBuff[0] = (byte)oneByte;
		mIos.write(mBuff, 0, 1);
	}

	public void write(byte[] b) throws IOException {
		mIos.write(b, 0, b.length);
	}
	
	public void write(byte[] b, int off, int len) throws IOException {
		mIos.write(b, off, len);
	}
	
	public void flush() throws IOException {
		mIos.flush();
	}
	
    public void close() throws IOException {
    	if (mIos == null) {
    		throw new IOException("Stream already closed");
    	}
        mIos = null;
    }
}
