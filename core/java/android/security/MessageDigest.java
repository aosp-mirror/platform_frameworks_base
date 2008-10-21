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

import java.security.NoSuchAlgorithmException;

public abstract class MessageDigest 
{    
    public static MessageDigest getInstance(String algorithm) 
        throws NoSuchAlgorithmException
    {
        if (algorithm == null) {
            return null;
        }
        
        if (algorithm.equals("SHA-1")) {
            return new Sha1MessageDigest();
        }
        else if (algorithm.equals("MD5")) {
            return new Md5MessageDigest();
        }
        
        throw new NoSuchAlgorithmException();
    }
    
    public abstract void update(byte[] input);    
    public abstract byte[] digest();
    public abstract byte[] digest(byte[] input);
}
