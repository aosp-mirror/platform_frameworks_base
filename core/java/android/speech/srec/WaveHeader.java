/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.speech.srec;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * This class represents the header of a WAVE format audio file, which usually
 * have a .wav suffix.  The following integer valued fields are contained:
 * <ul>
 * <li> format - usually PCM, ALAW or ULAW.
 * <li> numChannels - 1 for mono, 2 for stereo.
 * <li> sampleRate - usually 8000, 11025, 16000, 22050, or 44100 hz.
 * <li> bitsPerSample - usually 16 for PCM, 8 for ALAW, or 8 for ULAW.
 * <li> numBytes - size of audio data after this header, in bytes.
 * </ul>
 * 
 * Not yet ready to be supported, so
 * @hide
 */
public class WaveHeader {
    
    // follows WAVE format in http://ccrma.stanford.edu/courses/422/projects/WaveFormat

    private static final String TAG = "WaveHeader";
    
    private static final int HEADER_LENGTH = 44;
    
    /** Indicates PCM format. */
    public static final short FORMAT_PCM = 1;
    /** Indicates ALAW format. */
    public static final short FORMAT_ALAW = 6;
    /** Indicates ULAW format. */
    public static final short FORMAT_ULAW = 7;
    
    private short mFormat;
    private short mNumChannels;
    private int mSampleRate;
    private short mBitsPerSample;
    private int mNumBytes;
    
    /**
     * Construct a WaveHeader, with all fields defaulting to zero.
     */
    public WaveHeader() {
    }
    
    /**
     * Construct a WaveHeader, with fields initialized.
     * @param format format of audio data,
     * one of {@link #FORMAT_PCM}, {@link #FORMAT_ULAW}, or {@link #FORMAT_ALAW}. 
     * @param numChannels 1 for mono, 2 for stereo.
     * @param sampleRate typically 8000, 11025, 16000, 22050, or 44100 hz.
     * @param bitsPerSample usually 16 for PCM, 8 for ULAW or 8 for ALAW.
     * @param numBytes size of audio data after this header, in bytes.
     */
    public WaveHeader(short format, short numChannels, int sampleRate, short bitsPerSample, int numBytes) {
        mFormat = format;
        mSampleRate = sampleRate;
        mNumChannels = numChannels;
        mBitsPerSample = bitsPerSample;
        mNumBytes = numBytes;
    }
    
    /**
     * Get the format field.
     * @return format field,
     * one of {@link #FORMAT_PCM}, {@link #FORMAT_ULAW}, or {@link #FORMAT_ALAW}.
     */
    public short getFormat() {
        return mFormat;
    }
    
    /**
     * Set the format field.
     * @param format
     * one of {@link #FORMAT_PCM}, {@link #FORMAT_ULAW}, or {@link #FORMAT_ALAW}.
     * @return reference to this WaveHeader instance.
     */
    public WaveHeader setFormat(short format) {
        mFormat = format;
        return this;
    }
    
    /**
     * Get the number of channels.
     * @return number of channels, 1 for mono, 2 for stereo.
     */
    public short getNumChannels() {
        return mNumChannels;
    }
    
    /**
     * Set the number of channels.
     * @param numChannels 1 for mono, 2 for stereo.
     * @return reference to this WaveHeader instance.
     */
    public WaveHeader setNumChannels(short numChannels) {
        mNumChannels = numChannels;
        return this;
    }
    
    /**
     * Get the sample rate.
     * @return sample rate, typically 8000, 11025, 16000, 22050, or 44100 hz.
     */
    public int getSampleRate() {
        return mSampleRate;
    }
    
    /**
     * Set the sample rate.
     * @param sampleRate sample rate, typically 8000, 11025, 16000, 22050, or 44100 hz.
     * @return reference to this WaveHeader instance.
     */
    public WaveHeader setSampleRate(int sampleRate) {
        mSampleRate = sampleRate;
        return this;
    }
    
    /**
     * Get the number of bits per sample.
     * @return number of bits per sample,
     * usually 16 for PCM, 8 for ULAW or 8 for ALAW.
     */
    public short getBitsPerSample() {
        return mBitsPerSample;
    }
    
    /**
     * Set the number of bits per sample.
     * @param bitsPerSample number of bits per sample,
     * usually 16 for PCM, 8 for ULAW or 8 for ALAW.
     * @return reference to this WaveHeader instance.
     */
    public WaveHeader setBitsPerSample(short bitsPerSample) {
        mBitsPerSample = bitsPerSample;
        return this;
    }
    
    /**
     * Get the size of audio data after this header, in bytes.
     * @return size of audio data after this header, in bytes.
     */
    public int getNumBytes() {
        return mNumBytes;
    }
    
    /**
     * Set the size of audio data after this header, in bytes.
     * @param numBytes size of audio data after this header, in bytes.
     * @return reference to this WaveHeader instance.
     */
    public WaveHeader setNumBytes(int numBytes) {
        mNumBytes = numBytes;
        return this;
    }
    
    /**
     * Read and initialize a WaveHeader.
     * @param in {@link java.io.InputStream} to read from.
     * @return number of bytes consumed.
     * @throws IOException
     */
    public int read(InputStream in) throws IOException {
        /* RIFF header */
        readId(in, "RIFF");
        int numBytes = readInt(in) - 36;
        readId(in, "WAVE");

        /* fmt chunk */
        readId(in, "fmt ");
        if (16 != readInt(in)) throw new IOException("fmt chunk length not 16");
        mFormat = readShort(in);
        mNumChannels = readShort(in);
        mSampleRate = readInt(in);
        int byteRate = readInt(in);
        short blockAlign = readShort(in);
        mBitsPerSample = readShort(in);
        if (byteRate != mNumChannels * mSampleRate * mBitsPerSample / 8) {
            throw new IOException("fmt.ByteRate field inconsistent");
        }
        if (blockAlign != mNumChannels * mBitsPerSample / 8) {
            throw new IOException("fmt.BlockAlign field inconsistent");
        }

        /* data chunk */
        readId(in, "data");
        mNumBytes = readInt(in);
        
        return HEADER_LENGTH;
    }

    private static void readId(InputStream in, String id) throws IOException {
        for (int i = 0; i < id.length(); i++) {
            if (id.charAt(i) != in.read()) throw new IOException( id + " tag not present");
        }
    }

    private static int readInt(InputStream in) throws IOException {
        return in.read() | (in.read() << 8) | (in.read() << 16) | (in.read() << 24);
    }

    private static short readShort(InputStream in) throws IOException {
        return (short)(in.read() | (in.read() << 8));
    }

    /**
     * Write a WAVE file header.
     * @param out {@link java.io.OutputStream} to receive the header.
     * @return number of bytes written.
     * @throws IOException
     */
    public int write(OutputStream out) throws IOException {
        /* RIFF header */
        writeId(out, "RIFF");
        writeInt(out, 36 + mNumBytes);
        writeId(out, "WAVE");

        /* fmt chunk */
        writeId(out, "fmt ");
        writeInt(out, 16);
        writeShort(out, mFormat);
        writeShort(out, mNumChannels);
        writeInt(out, mSampleRate);
        writeInt(out, mNumChannels * mSampleRate * mBitsPerSample / 8);
        writeShort(out, (short)(mNumChannels * mBitsPerSample / 8));
        writeShort(out, mBitsPerSample);

        /* data chunk */
        writeId(out, "data");
        writeInt(out, mNumBytes);
        
        return HEADER_LENGTH;
    }

    private static void writeId(OutputStream out, String id) throws IOException {
        for (int i = 0; i < id.length(); i++) out.write(id.charAt(i));
    }

    private static void writeInt(OutputStream out, int val) throws IOException {
        out.write(val >> 0);
        out.write(val >> 8);
        out.write(val >> 16);
        out.write(val >> 24);
    }

    private static void writeShort(OutputStream out, short val) throws IOException {
        out.write(val >> 0);
        out.write(val >> 8);
    }
    
    @Override
    public String toString() {
        return String.format(
                "WaveHeader format=%d numChannels=%d sampleRate=%d bitsPerSample=%d numBytes=%d",
                mFormat, mNumChannels, mSampleRate, mBitsPerSample, mNumBytes);
    }

}
