package com.android.internal.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;

public class FastPrintWriter extends PrintWriter {
    private static final int BUFFER_LEN = 8192;

    private final char[] mText = new char[BUFFER_LEN];
    private int mPos;

    final private OutputStream mOutputStream;
    private CharsetEncoder mCharset;
    final private ByteBuffer mBytes = ByteBuffer.allocate(BUFFER_LEN);

    /**
     * Constructs a new {@code PrintWriter} with {@code out} as its target
     * stream. By default, the new print writer does not automatically flush its
     * contents to the target stream when a newline is encountered.
     *
     * @param out
     *            the target output stream.
     * @throws NullPointerException
     *             if {@code out} is {@code null}.
     */
    public FastPrintWriter(OutputStream out) {
        super(out);
        mOutputStream = out;
        initDefaultEncoder();
    }

    /**
     * Constructs a new {@code PrintWriter} with {@code out} as its target
     * stream. The parameter {@code autoFlush} determines if the print writer
     * automatically flushes its contents to the target stream when a newline is
     * encountered.
     *
     * @param out
     *            the target output stream.
     * @param autoFlush
     *            indicates whether contents are flushed upon encountering a
     *            newline sequence.
     * @throws NullPointerException
     *             if {@code out} is {@code null}.
     */
    public FastPrintWriter(OutputStream out, boolean autoFlush) {
        super(out, autoFlush);
        mOutputStream = out;
        initDefaultEncoder();
    }

    /**
     * Constructs a new {@code PrintWriter} with {@code wr} as its target
     * writer. By default, the new print writer does not automatically flush its
     * contents to the target writer when a newline is encountered.
     *
     * @param wr
     *            the target writer.
     * @throws NullPointerException
     *             if {@code wr} is {@code null}.
     */
    public FastPrintWriter(Writer wr) {
        super(wr);
        mOutputStream = null;
        initDefaultEncoder();
    }

    /**
     * Constructs a new {@code PrintWriter} with {@code out} as its target
     * writer. The parameter {@code autoFlush} determines if the print writer
     * automatically flushes its contents to the target writer when a newline is
     * encountered.
     *
     * @param wr
     *            the target writer.
     * @param autoFlush
     *            indicates whether to flush contents upon encountering a
     *            newline sequence.
     * @throws NullPointerException
     *             if {@code out} is {@code null}.
     */
    public FastPrintWriter(Writer wr, boolean autoFlush) {
        super(wr, autoFlush);
        mOutputStream = null;
        initDefaultEncoder();
    }

    /**
     * Constructs a new {@code PrintWriter} with {@code file} as its target. The
     * VM's default character set is used for character encoding.
     * The print writer does not automatically flush its contents to the target
     * file when a newline is encountered. The output to the file is buffered.
     *
     * @param file
     *            the target file. If the file already exists, its contents are
     *            removed, otherwise a new file is created.
     * @throws java.io.FileNotFoundException
     *             if an error occurs while opening or creating the target file.
     */
    public FastPrintWriter(File file) throws FileNotFoundException {
        super(file);
        mOutputStream = null;
        initDefaultEncoder();
    }

    /**
     * Constructs a new {@code PrintWriter} with {@code file} as its target. The
     * character set named {@code csn} is used for character encoding.
     * The print writer does not automatically flush its contents to the target
     * file when a newline is encountered. The output to the file is buffered.
     *
     * @param file
     *            the target file. If the file already exists, its contents are
     *            removed, otherwise a new file is created.
     * @param csn
     *            the name of the character set used for character encoding.
     * @throws FileNotFoundException
     *             if an error occurs while opening or creating the target file.
     * @throws NullPointerException
     *             if {@code csn} is {@code null}.
     * @throws java.io.UnsupportedEncodingException
     *             if the encoding specified by {@code csn} is not supported.
     */
    public FastPrintWriter(File file, String csn) throws FileNotFoundException,
            UnsupportedEncodingException {
        super(file, csn);
        mOutputStream = null;
        initEncoder(csn);
    }

    /**
     * Constructs a new {@code PrintWriter} with the file identified by {@code
     * fileName} as its target. The VM's default character set is
     * used for character encoding. The print writer does not automatically
     * flush its contents to the target file when a newline is encountered. The
     * output to the file is buffered.
     *
     * @param fileName
     *            the target file's name. If the file already exists, its
     *            contents are removed, otherwise a new file is created.
     * @throws FileNotFoundException
     *             if an error occurs while opening or creating the target file.
     */
    public FastPrintWriter(String fileName) throws FileNotFoundException {
        super(fileName);
        mOutputStream = null;
        initDefaultEncoder();
    }

     /**
     * Constructs a new {@code PrintWriter} with the file identified by {@code
     * fileName} as its target. The character set named {@code csn} is used for
     * character encoding. The print writer does not automatically flush its
     * contents to the target file when a newline is encountered. The output to
     * the file is buffered.
     *
     * @param fileName
     *            the target file's name. If the file already exists, its
     *            contents are removed, otherwise a new file is created.
     * @param csn
     *            the name of the character set used for character encoding.
     * @throws FileNotFoundException
     *             if an error occurs while opening or creating the target file.
     * @throws NullPointerException
     *             if {@code csn} is {@code null}.
     * @throws UnsupportedEncodingException
     *             if the encoding specified by {@code csn} is not supported.
     */
    public FastPrintWriter(String fileName, String csn)
            throws FileNotFoundException, UnsupportedEncodingException {
        super(fileName, csn);
        mOutputStream = null;
        initEncoder(csn);
    }

    private final void initEncoder(String csn) throws UnsupportedEncodingException {
        try {
            mCharset = Charset.forName(csn).newEncoder();
        } catch (Exception e) {
            throw new UnsupportedEncodingException(csn);
        }
        mCharset.onMalformedInput(CodingErrorAction.REPLACE);
        mCharset.onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private final void initDefaultEncoder() {
        mCharset = Charset.defaultCharset().newEncoder();
        mCharset.onMalformedInput(CodingErrorAction.REPLACE);
        mCharset.onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    private void appendInner(char c) throws IOException {
        int pos = mPos;
        if (pos >= (BUFFER_LEN-1)) {
            flush();
            pos = mPos;
        }
        mText[pos] = c;
        mPos = pos+1;
    }

    private void appendInner(String str, int i, final int length) throws IOException {
        if (length > BUFFER_LEN) {
            final int end = i + length;
            while (i < end) {
                int next = i + BUFFER_LEN;
                appendInner(str, i, next<end ? BUFFER_LEN : (end-i));
                i = next;
            }
            return;
        }
        int pos = mPos;
        if ((pos+length) > BUFFER_LEN) {
            flush();
            pos = mPos;
        }
        str.getChars(i, i + length, mText, pos);
        mPos = pos + length;
    }

    private void appendInner(char[] buf, int i, final int length) throws IOException {
        if (length > BUFFER_LEN) {
            final int end = i + length;
            while (i < end) {
                int next = i + BUFFER_LEN;
                appendInner(buf, i, next < end ? BUFFER_LEN : (end - i));
                i = next;
            }
            return;
        }
        int pos = mPos;
        if ((pos+length) > BUFFER_LEN) {
            flush();
            pos = mPos;
        }
        System.arraycopy(buf, i, mText, pos, length);
        mPos = pos + length;
    }

    private void flushBytesInner() throws IOException {
        int position;
        if ((position = mBytes.position()) > 0) {
            mBytes.flip();
            mOutputStream.write(mBytes.array(), 0, position);
            mBytes.clear();
        }
    }

    private void flushInner() throws IOException {
        //Log.i("PackageManager", "flush mPos=" + mPos);
        if (mPos > 0) {
            if (mOutputStream != null) {
                CharBuffer charBuffer = CharBuffer.wrap(mText, 0, mPos);
                CoderResult result = mCharset.encode(charBuffer, mBytes, true);
                while (true) {
                    if (result.isError()) {
                        throw new IOException(result.toString());
                    } else if (result.isOverflow()) {
                        flushBytesInner();
                        result = mCharset.encode(charBuffer, mBytes, true);
                        continue;
                    }
                    break;
                }
                flushBytesInner();
                mOutputStream.flush();
            } else {
                out.write(mText, 0, mPos);
                out.flush();
            }
            mPos = 0;
        }
    }

    /**
     * Ensures that all pending data is sent out to the target. It also
     * flushes the target. If an I/O error occurs, this writer's error
     * state is set to {@code true}.
     */
    @Override
    public void flush() {
        try {
            flushInner();
        } catch (IOException e) {
        }
        super.flush();
    }

    /**
     * Prints the string representation of the specified character array
     * to the target.
     *
     * @param charArray
     *            the character array to print to the target.
     * @see #print(String)
     */
    public void print(char[] charArray) {
        try {
            appendInner(charArray, 0, charArray.length);
        } catch (IOException e) {
        }
    }

    /**
     * Prints the string representation of the specified character to the
     * target.
     *
     * @param ch
     *            the character to print to the target.
     * @see #print(String)
     */
    public void print(char ch) {
        try {
            appendInner(ch);
        } catch (IOException e) {
        }
    }

    /**
     * Prints a string to the target. The string is converted to an array of
     * bytes using the encoding chosen during the construction of this writer.
     * The bytes are then written to the target with {@code write(int)}.
     * <p>
     * If an I/O error occurs, this writer's error flag is set to {@code true}.
     *
     * @param str
     *            the string to print to the target.
     * @see #write(int)
     */
    public void print(String str) {
        if (str == null) {
            str = String.valueOf((Object) null);
        }
        try {
            appendInner(str, 0, str.length());
        } catch (IOException e) {
        }
    }

    /**
     * Prints the string representation of the character array {@code chars} followed by a newline.
     * Flushes this writer if the autoFlush flag is set to {@code true}.
     */
    public void println(char[] chars) {
        print(chars);
        println();
    }

    /**
     * Prints the string representation of the char {@code c} followed by a newline.
     * Flushes this writer if the autoFlush flag is set to {@code true}.
     */
    public void println(char c) {
        print(c);
        println();
    }

    /**
     * Writes {@code count} characters from {@code buffer} starting at {@code
     * offset} to the target.
     * <p>
     * This writer's error flag is set to {@code true} if this writer is closed
     * or an I/O error occurs.
     *
     * @param buf
     *            the buffer to write to the target.
     * @param offset
     *            the index of the first character in {@code buffer} to write.
     * @param count
     *            the number of characters in {@code buffer} to write.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code count < 0}, or if {@code
     *             offset + count} is greater than the length of {@code buf}.
     */
    @Override
    public void write(char[] buf, int offset, int count) {
        try {
            appendInner(buf, offset, count);
        } catch (IOException e) {
        }
    }

    /**
     * Writes one character to the target. Only the two least significant bytes
     * of the integer {@code oneChar} are written.
     * <p>
     * This writer's error flag is set to {@code true} if this writer is closed
     * or an I/O error occurs.
     *
     * @param oneChar
     *            the character to write to the target.
     */
    @Override
    public void write(int oneChar) {
        try {
            appendInner((char) oneChar);
        } catch (IOException e) {
        }
    }

    /**
     * Writes the characters from the specified string to the target.
     *
     * @param str
     *            the non-null string containing the characters to write.
     */
    @Override
    public void write(String str) {
        try {
            appendInner(str, 0, str.length());
        } catch (IOException e) {
        }
    }

    /**
     * Writes {@code count} characters from {@code str} starting at {@code
     * offset} to the target.
     *
     * @param str
     *            the non-null string containing the characters to write.
     * @param offset
     *            the index of the first character in {@code str} to write.
     * @param count
     *            the number of characters from {@code str} to write.
     * @throws IndexOutOfBoundsException
     *             if {@code offset < 0} or {@code count < 0}, or if {@code
     *             offset + count} is greater than the length of {@code str}.
     */
    @Override
    public void write(String str, int offset, int count) {
        try {
            appendInner(str, offset, count);
        } catch (IOException e) {
        }
    }

    /**
     * Appends a subsequence of the character sequence {@code csq} to the
     * target. This method works the same way as {@code
     * PrintWriter.print(csq.subsequence(start, end).toString())}. If {@code
     * csq} is {@code null}, then the specified subsequence of the string "null"
     * will be written to the target.
     *
     * @param csq
     *            the character sequence appended to the target.
     * @param start
     *            the index of the first char in the character sequence appended
     *            to the target.
     * @param end
     *            the index of the character following the last character of the
     *            subsequence appended to the target.
     * @return this writer.
     * @throws StringIndexOutOfBoundsException
     *             if {@code start > end}, {@code start < 0}, {@code end < 0} or
     *             either {@code start} or {@code end} are greater or equal than
     *             the length of {@code csq}.
     */
    @Override
    public PrintWriter append(CharSequence csq, int start, int end) {
        if (csq == null) {
            csq = "null";
        }
        String output = csq.subSequence(start, end).toString();
        write(output, 0, output.length());
        return this;
    }
}
