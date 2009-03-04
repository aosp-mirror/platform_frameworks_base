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
 * @author Sergey I. Salishev
 * @version $Revision: 1.2 $
 */

package javax.imageio.stream;

// 
// @author Sergey I. Salishev
// @version $Revision: 1.2 $
//

/**
 * The IIOByteBuffer class represents a byte array with offset and length that
 * is used by ImageInputStream for obtaining a sequence of bytes.
 * 
 * @since Android 1.0
 */
public class IIOByteBuffer {

    /**
     * The data.
     */
    private byte[] data;

    /**
     * The offset.
     */
    private int offset;

    /**
     * The length.
     */
    private int length;

    /**
     * Instantiates a new IIOByteBuffer.
     * 
     * @param data
     *            the byte array.
     * @param offset
     *            the offset in the array.
     * @param length
     *            the length of array.
     */
    public IIOByteBuffer(byte[] data, int offset, int length) {
        this.data = data;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Gets the byte array of this IIOByteBuffer.
     * 
     * @return the byte array.
     */
    public byte[] getData() {
        return data;
    }

    /**
     * Gets the length in the array which will be used.
     * 
     * @return the length of the data.
     */
    public int getLength() {
        return length;
    }

    /**
     * Gets the offset of this IIOByteBuffer.
     * 
     * @return the offset of this IIOByteBuffer.
     */
    public int getOffset() {
        return offset;
    }

    /**
     * Sets the new data array to this IIOByteBuffer object.
     * 
     * @param data
     *            the new data array.
     */
    public void setData(byte[] data) {
        this.data = data;
    }

    /**
     * Sets the length of data which will be used.
     * 
     * @param length
     *            the new length.
     */
    public void setLength(int length) {
        this.length = length;
    }

    /**
     * Sets the offset in the data array of this IIOByteBuffer.
     * 
     * @param offset
     *            the new offset.
     */
    public void setOffset(int offset) {
        this.offset = offset;
    }
}
