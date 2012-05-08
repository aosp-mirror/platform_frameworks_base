package android.content.pm;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

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
    private final int mEnd;

    /**
     * Current offset in the stream.
     */
    private int mOffset;

    /**
     * @param in underlying stream to wrap
     * @param offset offset into stream where data starts
     * @param length length of data at offset
     * @throws IOException if an error occured with the underlying stream
     */
    public LimitedLengthInputStream(InputStream in, int offset, int length) throws IOException {
        super(in);

        if (in == null) {
            throw new IOException("in == null");
        }

        if (offset < 0) {
            throw new IOException("offset == " + offset);
        }

        if (length < 0) {
            throw new IOException("length must be non-negative; is " + length);
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

        if (mOffset + byteCount > mEnd) {
            byteCount = mEnd - mOffset;
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
