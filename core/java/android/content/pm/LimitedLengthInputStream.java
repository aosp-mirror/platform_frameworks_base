package android.content.pm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * A class that limits the amount of data that is read from an InputStream. When
 * the specified length is reached, the stream returns an EOF even if the
 * underlying stream still has more data.
 *
 * @hide
 */
public class LimitedLengthInputStream extends FilterInputStream {
    /**
     * The end of the stream where we don't want to allow more data to be read.
     */
    private final long mEnd;

    /**
     * Current offset in the stream.
     */
    private long mOffset;

    /**
     * @param in underlying stream to wrap
     * @param offset offset into stream where data starts
     * @param length length of data at offset
     * @throws IOException if an error occurred with the underlying stream
     */
    public LimitedLengthInputStream(InputStream in, long offset, long length) throws IOException {
        super(in);

        if (in == null) {
            throw new IOException("in == null");
        }

        if (offset < 0) {
            throw new IOException("offset < 0");
        }

        if (length < 0) {
            throw new IOException("length < 0");
        }

        if (length > Long.MAX_VALUE - offset) {
            throw new IOException("offset + length > Long.MAX_VALUE");
        }

        mEnd = offset + length;

        skip(offset);
        mOffset = offset;
    }

    @Override
    public synchronized int read() throws IOException {
        if (mOffset >= mEnd) {
            return -1;
        }

        mOffset++;
        return super.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int byteCount) throws IOException {
        if (mOffset >= mEnd) {
            return -1;
        }

        final int arrayLength = buffer.length;
        Arrays.checkOffsetAndCount(arrayLength, offset, byteCount);

        if (mOffset > Long.MAX_VALUE - byteCount) {
            throw new IOException("offset out of bounds: " + mOffset + " + " + byteCount);
        }

        if (mOffset + byteCount > mEnd) {
            byteCount = (int) (mEnd - mOffset);
        }

        final int numRead = super.read(buffer, offset, byteCount);
        mOffset += numRead;

        return numRead;
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        return read(buffer, 0, buffer.length);
    }
}
