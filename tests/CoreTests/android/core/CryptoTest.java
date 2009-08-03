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

package android.core;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.apache.harmony.xnet.provider.jsse.OpenSSLMessageDigest;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.ExtendedDigest;
import org.bouncycastle.crypto.digests.MD4Digest;
import org.bouncycastle.crypto.digests.MD5Digest;
import org.bouncycastle.crypto.digests.SHA1Digest;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Implements unit tests for our JNI wrapper around OpenSSL. We use the
 * existing Bouncy Castle implementation as our test oracle.
 */
public class CryptoTest extends TestCase {

    /**
     * Processes the two given message digests for the same data and checks
     * the results. Requirement is that the results must be equal, the digest
     * implementations must have the same properties, and the new implementation
     * must be faster than the old one.
     * 
     * @param oldDigest The old digest implementation, provided by Bouncy Castle 
     * @param newDigest The new digest implementation, provided by OpenSSL
     */
    public void doTestMessageDigest(Digest oldDigest, Digest newDigest) {
        final int ITERATIONS = 10;
        
        byte[] data = new byte[1024];
        
        byte[] oldHash = new byte[oldDigest.getDigestSize()];
        byte[] newHash = new byte[newDigest.getDigestSize()];
        
        Assert.assertEquals("Hash names must be equal", oldDigest.getAlgorithmName(), newDigest.getAlgorithmName());
        Assert.assertEquals("Hash sizes must be equal", oldHash.length, newHash.length);
        Assert.assertEquals("Hash block sizes must be equal", ((ExtendedDigest)oldDigest).getByteLength(), ((ExtendedDigest)newDigest).getByteLength());
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte)i;
        }

        long oldTime = 0;
        long newTime = 0;
        
        for (int j = 0; j < ITERATIONS; j++) {
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < 4; i++) {
                oldDigest.update(data, 0, data.length);
            }
            int oldLength = oldDigest.doFinal(oldHash, 0);
            long t1 = System.currentTimeMillis();
    
            oldTime = oldTime + (t1 - t0);
            
            long t2 = System.currentTimeMillis();
            for (int i = 0; i < 4; i++) {
                newDigest.update(data, 0, data.length);
            }
            int newLength = newDigest.doFinal(newHash, 0);
            long t3 = System.currentTimeMillis();

            newTime = newTime + (t3 - t2);
            
            Assert.assertEquals("Hash sizes must be equal", oldLength, newLength);
    
            for (int i = 0; i < oldLength; i++) {
                Assert.assertEquals("Hashes[" + i + "] must be equal", oldHash[i], newHash[i]);
            }
        }

        android.util.Log.d("CryptoTest", "Time for " + ITERATIONS + " x old hash processing: " + oldTime + " ms");
        android.util.Log.d("CryptoTest", "Time for " + ITERATIONS + " x new hash processing: " + newTime + " ms");
        
        // Assert.assertTrue("New hash should be faster", newTime < oldTime);
    }
    
    /**
     * Tests the MD4 implementation.
     */
    @MediumTest
    public void testMD4() {
        Digest oldDigest = new MD4Digest();
        Digest newDigest = OpenSSLMessageDigest.getInstance("MD4");
        doTestMessageDigest(oldDigest, newDigest);
    }
    
    /**
     * Tests the MD5 implementation.
     */
    @MediumTest
    public void testMD5() {
        Digest oldDigest = new MD5Digest();
        Digest newDigest = OpenSSLMessageDigest.getInstance("MD5");
        doTestMessageDigest(oldDigest, newDigest);
    }
    
    /**
     * Tests the SHA-1 implementation.
     */
    @MediumTest
    public void testSHA1() {
        Digest oldDigest = new SHA1Digest();
        Digest newDigest = OpenSSLMessageDigest.getInstance("SHA-1");
        doTestMessageDigest(oldDigest, newDigest);
    }
    
}
