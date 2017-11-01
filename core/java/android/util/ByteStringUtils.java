/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util;

/**
 * A utility class for common byte array to hex string operations and vise versa.
 *
 * @hide
 */
public final class ByteStringUtils {
  private final static char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

  private ByteStringUtils() {
    /* hide constructor */
  }

  /**
   * Returns the hex encoded string representation of bytes.
   * @param bytes Byte array to encode.
   * @return Hex encoded string representation of bytes.
   */
  public static String toHexString(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length % 2 != 0) {
      return null;
    }

    final int byteLength = bytes.length;
    final int charCount = 2 * byteLength;
    final char[] chars = new char[charCount];

    for (int i = 0; i < byteLength; i++) {
      final int byteHex = bytes[i] & 0xFF;
      chars[i * 2] = HEX_ARRAY[byteHex >>> 4];
      chars[i * 2 + 1] = HEX_ARRAY[byteHex & 0x0F];
    }
    return new String(chars);
  }

  /**
   * Returns the decoded byte array representation of str.
   * @param str Hex encoded string to decode.
   * @return Decoded byte array representation of str.
   */
  public static byte[] fromHexToByteArray(String str) {
    if (str == null || str.length() == 0 || str.length() % 2 != 0) {
      return null;
    }

    final char[] chars = str.toCharArray();
    final int charLength = chars.length;
    final byte[] bytes = new byte[charLength / 2];

    for (int i = 0; i < bytes.length; i++) {
      bytes[i] =
          (byte)(((getIndex(chars[i * 2]) << 4) & 0xF0) | (getIndex(chars[i * 2 + 1]) & 0x0F));
    }
    return bytes;
  }

  private static int getIndex(char c) {
    for (int i = 0; i < HEX_ARRAY.length; i++) {
      if (HEX_ARRAY[i] == c) {
        return i;
      }
    }
    return -1;
  }
}
