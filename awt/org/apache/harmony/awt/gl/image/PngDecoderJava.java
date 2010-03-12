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

package org.apache.harmony.awt.gl.image;

// A simple PNG decoder source code in Java.
import java.awt.Graphics;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.zip.CRC32;
import java.util.zip.InflaterInputStream;

//import javax.swing.JFrame;

public class PngDecoderJava {
 
/*
  public static void main(String[] args) throws Exception {
    String name = "logo.png";
    if (args.length > 0)
      name = args[0];
    InputStream in = PngDecoderJava.class.getResourceAsStream(name);
    final BufferedImage image = PngDecoderJava.decode(in);
    in.close();

    JFrame f = new JFrame() {
      public void paint(Graphics g) {
        Insets insets = getInsets();
        g.drawImage(image, insets.left, insets.top, null);
      }
    };
    f.setVisible(true);
    Insets insets = f.getInsets();
    f.setSize(image.getWidth() + insets.left + insets.right, image
        .getHeight()
        + insets.top + insets.bottom);
  }
  */

  public static BufferedImage decode(InputStream in) throws IOException {
    DataInputStream dataIn = new DataInputStream(in);
    readSignature(dataIn);
    PNGData chunks = readChunks(dataIn);

    long widthLong = chunks.getWidth();
    long heightLong = chunks.getHeight();
    if (widthLong > Integer.MAX_VALUE || heightLong > Integer.MAX_VALUE)
      throw new IOException("That image is too wide or tall.");
    int width = (int) widthLong;
    int height = (int) heightLong;

    ColorModel cm = chunks.getColorModel();
    WritableRaster raster = chunks.getRaster();

    BufferedImage image = new BufferedImage(cm, raster, false, null);

    return image;
  }

  protected static void readSignature(DataInputStream in) throws IOException {
    long signature = in.readLong();
    if (signature != 0x89504e470d0a1a0aL)
      throw new IOException("PNG signature not found!");
  }

  protected static PNGData readChunks(DataInputStream in) throws IOException {
    PNGData chunks = new PNGData();

    boolean trucking = true;
    while (trucking) {
      try {
        // Read the length.
        int length = in.readInt();
        if (length < 0)
          throw new IOException("Sorry, that file is too long.");
        // Read the type.
        byte[] typeBytes = new byte[4];
        in.readFully(typeBytes);
        // Read the data.
        byte[] data = new byte[length];
        in.readFully(data);
        // Read the CRC.
        long crc = in.readInt() & 0x00000000ffffffffL; // Make it
        // unsigned.
        if (verifyCRC(typeBytes, data, crc) == false)
          throw new IOException("That file appears to be corrupted.");

        PNGChunk chunk = new PNGChunk(typeBytes, data);
        chunks.add(chunk);
      } catch (EOFException eofe) {
        trucking = false;
      }
    }
    return chunks;
  }

  protected static boolean verifyCRC(byte[] typeBytes, byte[] data, long crc) {
    CRC32 crc32 = new CRC32();
    crc32.update(typeBytes);
    crc32.update(data);
    long calculated = crc32.getValue();
    return (calculated == crc);
  }
}

class PNGData {
  private int mNumberOfChunks;

  private PNGChunk[] mChunks;

  public PNGData() {
    mNumberOfChunks = 0;
    mChunks = new PNGChunk[10];
  }

  public void add(PNGChunk chunk) {
    mChunks[mNumberOfChunks++] = chunk;
    if (mNumberOfChunks >= mChunks.length) {
      PNGChunk[] largerArray = new PNGChunk[mChunks.length + 10];
      System.arraycopy(mChunks, 0, largerArray, 0, mChunks.length);
      mChunks = largerArray;
    }
  }

  public long getWidth() {
    return getChunk("IHDR").getUnsignedInt(0);
  }

  public long getHeight() {    return getChunk("IHDR").getUnsignedInt(4);
  }

  public short getBitsPerPixel() {
    return getChunk("IHDR").getUnsignedByte(8);
  }

  public short getColorType() {
    return getChunk("IHDR").getUnsignedByte(9);
  }

  public short getCompression() {
    return getChunk("IHDR").getUnsignedByte(10);
  }

  public short getFilter() {
    return getChunk("IHDR").getUnsignedByte(11);
  }

  public short getInterlace() {
    return getChunk("IHDR").getUnsignedByte(12);
  }

  public ColorModel getColorModel() {
    short colorType = getColorType();
    int bitsPerPixel = getBitsPerPixel();

    if (colorType == 3) {
      byte[] paletteData = getChunk("PLTE").getData();
      int paletteLength = paletteData.length / 3;
      return new IndexColorModel(bitsPerPixel, paletteLength,
          paletteData, 0, false);
    }
    System.out.println("Unsupported color type: " + colorType);
    return null;
  }

  public WritableRaster getRaster() {
    int width = (int) getWidth();
    int height = (int) getHeight();
    int bitsPerPixel = getBitsPerPixel();
    short colorType = getColorType();

    if (colorType == 3) {
      byte[] imageData = getImageData();
      //Orig: DataBuffer db = new DataBufferByte(imageData, imageData.length);
      int len = Math.max(imageData.length, (width - 1) * (height -1));
      DataBuffer db = new DataBufferByte(imageData, len);
      WritableRaster raster = Raster.createPackedRaster(db, width,
          height, bitsPerPixel, null);
      return raster;
    } else
      System.out.println("Unsupported color type!");
    return null;
  }

  public byte[] getImageData() {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      // Write all the IDAT data into the array.
      for (int i = 0; i < mNumberOfChunks; i++) {
        PNGChunk chunk = mChunks[i];
        if (chunk.getTypeString().equals("IDAT")) {
          out.write(chunk.getData());
        }
      }
      out.flush();
      // Now deflate the data.
      InflaterInputStream in = new InflaterInputStream(
          new ByteArrayInputStream(out.toByteArray()));
      ByteArrayOutputStream inflatedOut = new ByteArrayOutputStream();
      int readLength;
      byte[] block = new byte[8192];
      while ((readLength = in.read(block)) != -1)
        inflatedOut.write(block, 0, readLength);
      inflatedOut.flush();
      byte[] imageData = inflatedOut.toByteArray();
      // Compute the real length.
      int width = (int) getWidth();
      int height = (int) getHeight();
      int bitsPerPixel = getBitsPerPixel();
      int length = width * height * bitsPerPixel / 8;

      byte[] prunedData = new byte[length];

      // We can only deal with non-interlaced images.
      if (getInterlace() == 0) {
        int index = 0;
        for (int i = 0; i < length; i++) {
          if ((i * 8 / bitsPerPixel) % width == 0) {
            index++; // Skip the filter byte.
          }
          prunedData[i] = imageData[index++];
        }
      } else
        System.out.println("Couldn't undo interlacing.");

      return prunedData;
    } catch (IOException ioe) {
    }
    return null;
  }

  public PNGChunk getChunk(String type) {
    for (int i = 0; i < mNumberOfChunks; i++)
      if (mChunks[i].getTypeString().equals(type))
        return mChunks[i];
    return null;
  }
}

class PNGChunk {
  private byte[] mType;

  private byte[] mData;

  public PNGChunk(byte[] type, byte[] data) {
    mType = type;
    mData = data;
  }

  public String getTypeString() {
    try {
      return new String(mType, "UTF8");
    } catch (UnsupportedEncodingException uee) {
      return "";
    }
  }

  public byte[] getData() {
    return mData;
  }

  public long getUnsignedInt(int offset) {
    long value = 0;
    for (int i = 0; i < 4; i++)
      value += (mData[offset + i] & 0xff) << ((3 - i) * 8);
    return value;
  }

  public short getUnsignedByte(int offset) {
    return (short) (mData[offset] & 0x00ff);
  }
}
