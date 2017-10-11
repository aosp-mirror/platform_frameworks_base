/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.speech.tts;

import android.annotation.NonNull;
import android.media.AudioFormat;
import android.speech.tts.TextToSpeechService.UtteranceProgressDispatcher;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Speech synthesis request that writes the audio to a WAV file.
 */
class FileSynthesisCallback extends AbstractSynthesisCallback {

    private static final String TAG = "FileSynthesisRequest";
    private static final boolean DBG = false;

    private static final int MAX_AUDIO_BUFFER_SIZE = 8192;

    private static final int WAV_HEADER_LENGTH = 44;
    private static final short WAV_FORMAT_PCM = 0x0001;

    private final Object mStateLock = new Object();

    private int mSampleRateInHz;
    private int mAudioFormat;
    private int mChannelCount;

    private FileChannel mFileChannel;

    private final UtteranceProgressDispatcher mDispatcher;

    private boolean mStarted = false;
    private boolean mDone = false;

    /** Status code of synthesis */
    protected int mStatusCode;

    FileSynthesisCallback(@NonNull FileChannel fileChannel,
            @NonNull UtteranceProgressDispatcher dispatcher, boolean clientIsUsingV2) {
        super(clientIsUsingV2);
        mFileChannel = fileChannel;
        mDispatcher = dispatcher;
        mStatusCode = TextToSpeech.SUCCESS;
    }

    @Override
    void stop() {
        synchronized (mStateLock) {
            if (mDone) {
                return;
            }
            if (mStatusCode == TextToSpeech.STOPPED) {
                return;
            }

            mStatusCode = TextToSpeech.STOPPED;
            cleanUp();
            mDispatcher.dispatchOnStop();
        }
    }

    /**
     * Must be called while holding the monitor on {@link #mStateLock}.
     */
    private void cleanUp() {
        closeFile();
    }

    /**
     * Must be called while holding the monitor on {@link #mStateLock}.
     */
    private void closeFile() {
        // File will be closed by the SpeechItem in the speech service.
        mFileChannel = null;
    }

    @Override
    public int getMaxBufferSize() {
        return MAX_AUDIO_BUFFER_SIZE;
    }

    @Override
    public int start(int sampleRateInHz, int audioFormat, int channelCount) {
        if (DBG) {
            Log.d(TAG, "FileSynthesisRequest.start(" + sampleRateInHz + "," + audioFormat
                    + "," + channelCount + ")");
        }
        if (audioFormat != AudioFormat.ENCODING_PCM_8BIT &&
            audioFormat != AudioFormat.ENCODING_PCM_16BIT &&
            audioFormat != AudioFormat.ENCODING_PCM_FLOAT) {
            Log.e(TAG, "Audio format encoding " + audioFormat + " not supported. Please use one " +
                       "of AudioFormat.ENCODING_PCM_8BIT, AudioFormat.ENCODING_PCM_16BIT or " +
                       "AudioFormat.ENCODING_PCM_FLOAT");
        }
        mDispatcher.dispatchOnBeginSynthesis(sampleRateInHz, audioFormat, channelCount);

        FileChannel fileChannel = null;
        synchronized (mStateLock) {
            if (mStatusCode == TextToSpeech.STOPPED) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return errorCodeOnStop();
            }
            if (mStatusCode != TextToSpeech.SUCCESS) {
                if (DBG) Log.d(TAG, "Error was raised");
                return TextToSpeech.ERROR;
            }
            if (mStarted) {
                Log.e(TAG, "Start called twice");
                return TextToSpeech.ERROR;
            }
            mStarted = true;
            mSampleRateInHz = sampleRateInHz;
            mAudioFormat = audioFormat;
            mChannelCount = channelCount;

            mDispatcher.dispatchOnStart();
            fileChannel = mFileChannel;
        }

        try {
            fileChannel.write(ByteBuffer.allocate(WAV_HEADER_LENGTH));
                return TextToSpeech.SUCCESS;
        } catch (IOException ex) {
            Log.e(TAG, "Failed to write wav header to output file descriptor", ex);
            synchronized (mStateLock) {
                cleanUp();
                mStatusCode = TextToSpeech.ERROR_OUTPUT;
            }
            return TextToSpeech.ERROR;
        }
    }

    @Override
    public int audioAvailable(byte[] buffer, int offset, int length) {
        if (DBG) {
            Log.d(TAG, "FileSynthesisRequest.audioAvailable(" + buffer + "," + offset
                    + "," + length + ")");
        }
        FileChannel fileChannel = null;
        synchronized (mStateLock) {
            if (mStatusCode == TextToSpeech.STOPPED) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return errorCodeOnStop();
            }
            if (mStatusCode != TextToSpeech.SUCCESS) {
                if (DBG) Log.d(TAG, "Error was raised");
                return TextToSpeech.ERROR;
            }
            if (mFileChannel == null) {
                Log.e(TAG, "File not open");
                mStatusCode = TextToSpeech.ERROR_OUTPUT;
                return TextToSpeech.ERROR;
            }
            if (!mStarted) {
                Log.e(TAG, "Start method was not called");
                return TextToSpeech.ERROR;
            }
            fileChannel = mFileChannel;
        }

        final byte[] bufferCopy = new byte[length];
        System.arraycopy(buffer, offset, bufferCopy, 0, length);
        mDispatcher.dispatchOnAudioAvailable(bufferCopy);

        try {
            fileChannel.write(ByteBuffer.wrap(buffer,  offset,  length));
            return TextToSpeech.SUCCESS;
        } catch (IOException ex) {
            Log.e(TAG, "Failed to write to output file descriptor", ex);
            synchronized (mStateLock) {
                cleanUp();
                mStatusCode = TextToSpeech.ERROR_OUTPUT;
            }
            return TextToSpeech.ERROR;
        }
    }

    @Override
    public int done() {
        if (DBG) Log.d(TAG, "FileSynthesisRequest.done()");
        FileChannel fileChannel = null;

        int sampleRateInHz = 0;
        int audioFormat = 0;
        int channelCount = 0;

        synchronized (mStateLock) {
            if (mDone) {
                Log.w(TAG, "Duplicate call to done()");
                // This is not an error that would prevent synthesis. Hence no
                // setStatusCode is set.
                return TextToSpeech.ERROR;
            }
            if (mStatusCode == TextToSpeech.STOPPED) {
                if (DBG) Log.d(TAG, "Request has been aborted.");
                return errorCodeOnStop();
            }
            if (mStatusCode != TextToSpeech.SUCCESS && mStatusCode != TextToSpeech.STOPPED) {
                mDispatcher.dispatchOnError(mStatusCode);
                return TextToSpeech.ERROR;
            }
            if (mFileChannel == null) {
                Log.e(TAG, "File not open");
                return TextToSpeech.ERROR;
            }
            mDone = true;
            fileChannel = mFileChannel;
            sampleRateInHz = mSampleRateInHz;
            audioFormat = mAudioFormat;
            channelCount = mChannelCount;
        }

        try {
            // Write WAV header at start of file
            fileChannel.position(0);
            int dataLength = (int) (fileChannel.size() - WAV_HEADER_LENGTH);
            fileChannel.write(
                    makeWavHeader(sampleRateInHz, audioFormat, channelCount, dataLength));

            synchronized (mStateLock) {
                closeFile();
                mDispatcher.dispatchOnSuccess();
                return TextToSpeech.SUCCESS;
            }
        } catch (IOException ex) {
            Log.e(TAG, "Failed to write to output file descriptor", ex);
            synchronized (mStateLock) {
                cleanUp();
            }
            return TextToSpeech.ERROR;
        }
    }

    @Override
    public void error() {
        error(TextToSpeech.ERROR_SYNTHESIS);
    }

    @Override
    public void error(int errorCode) {
        if (DBG) Log.d(TAG, "FileSynthesisRequest.error()");
        synchronized (mStateLock) {
            if (mDone) {
                return;
            }
            cleanUp();
            mStatusCode = errorCode;
        }
    }

    @Override
    public boolean hasStarted() {
        synchronized (mStateLock) {
            return mStarted;
        }
    }

    @Override
    public boolean hasFinished() {
        synchronized (mStateLock) {
            return mDone;
        }
    }

    private ByteBuffer makeWavHeader(int sampleRateInHz, int audioFormat, int channelCount,
            int dataLength) {
        int sampleSizeInBytes = AudioFormat.getBytesPerSample(audioFormat);
        int byteRate = sampleRateInHz * sampleSizeInBytes * channelCount;
        short blockAlign = (short) (sampleSizeInBytes * channelCount);
        short bitsPerSample = (short) (sampleSizeInBytes * 8);

        byte[] headerBuf = new byte[WAV_HEADER_LENGTH];
        ByteBuffer header = ByteBuffer.wrap(headerBuf);
        header.order(ByteOrder.LITTLE_ENDIAN);

        header.put(new byte[]{ 'R', 'I', 'F', 'F' });
        header.putInt(dataLength + WAV_HEADER_LENGTH - 8);  // RIFF chunk size
        header.put(new byte[]{ 'W', 'A', 'V', 'E' });
        header.put(new byte[]{ 'f', 'm', 't', ' ' });
        header.putInt(16);  // size of fmt chunk
        header.putShort(WAV_FORMAT_PCM);
        header.putShort((short) channelCount);
        header.putInt(sampleRateInHz);
        header.putInt(byteRate);
        header.putShort(blockAlign);
        header.putShort(bitsPerSample);
        header.put(new byte[]{ 'd', 'a', 't', 'a' });
        header.putInt(dataLength);
        header.flip();

        return header;
    }

    @Override
    public void rangeStart(int markerInFrames, int start, int end) {
        mDispatcher.dispatchOnRangeStart(markerInFrames, start, end);
    }
}
