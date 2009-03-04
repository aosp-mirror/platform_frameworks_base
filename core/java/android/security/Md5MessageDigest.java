/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.security;

/**
 * Provides the MD5 hash encryption.
 */
public class Md5MessageDigest extends MessageDigest
{
    // ptr to native context
    private int mNativeMd5Context;
    
    public Md5MessageDigest()
    {
        init();
    }
    
    public byte[] digest(byte[] input)
    {
        update(input);
        return digest();
    }

    private native void init();
    public native void update(byte[] input);  
    public native byte[] digest();
    native public void reset();
}
