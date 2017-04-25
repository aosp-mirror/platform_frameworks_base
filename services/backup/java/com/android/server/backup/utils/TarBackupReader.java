package com.android.server.backup.utils;

import android.app.backup.BackupAgent;
import android.app.backup.FullBackup;
import android.util.Slog;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.RefactoredBackupManagerService;

import java.io.IOException;
import java.io.InputStream;

/**
 * Utility methods to read backup tar file.
 */
public class TarBackupReader {
    private static final int TAR_HEADER_OFFSET_TYPE_CHAR = 156;
    private static final int TAR_HEADER_LENGTH_PATH = 100;
    private static final int TAR_HEADER_OFFSET_PATH = 0;
    private static final int TAR_HEADER_LENGTH_PATH_PREFIX = 155;
    private static final int TAR_HEADER_OFFSET_PATH_PREFIX = 345;
    private static final int TAR_HEADER_LENGTH_MODE = 8;
    private static final int TAR_HEADER_OFFSET_MODE = 100;
    private static final int TAR_HEADER_LENGTH_MODTIME = 12;
    private static final int TAR_HEADER_OFFSET_MODTIME = 136;
    private static final int TAR_HEADER_LENGTH_FILESIZE = 12;
    private static final int TAR_HEADER_OFFSET_FILESIZE = 124;
    private static final int TAR_HEADER_LONG_RADIX = 8;

    private final InputStream mInputStream;
    private final BytesReadListener mBytesReadListener;

    /**
     * Listener for bytes reading.
     */
    public interface BytesReadListener {
        void onBytesRead(long bytesRead);
    }

    public TarBackupReader(InputStream inputStream, BytesReadListener bytesReadListener) {
        mInputStream = inputStream;
        mBytesReadListener = bytesReadListener;
    }

    /**
     * Tries to read exactly the given number of bytes into a buffer at the stated offset.
     *
     * @param in - input stream to read bytes from..
     * @param buffer - where to write bytes to.
     * @param offset - offset in buffer to write bytes to.
     * @param size - number of bytes to read.
     * @return number of bytes actually read.
     * @throws IOException in case of an error.
     */
    public static int readExactly(InputStream in, byte[] buffer, int offset, int size)
            throws IOException {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (RefactoredBackupManagerService.MORE_DEBUG) {
            Slog.i(RefactoredBackupManagerService.TAG, "  ... readExactly(" + size + ") called");
        }
        int soFar = 0;
        while (soFar < size) {
            int nRead = in.read(buffer, offset + soFar, size - soFar);
            if (nRead <= 0) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "- wanted exactly " + size + " but got only " + soFar);
                }
                break;
            }
            soFar += nRead;
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.v(RefactoredBackupManagerService.TAG,
                        "   + got " + nRead + "; now wanting " + (size - soFar));
            }
        }
        return soFar;
    }

    /**
     * Builds a line from a byte buffer starting at 'offset'.
     *
     * @param buffer - where to read a line from.
     * @param offset - offset in buffer to read a line from.
     * @param outStr - an output parameter, the result will be put in outStr.
     * @return the index of the next unconsumed data in the buffer.
     * @throws IOException in case of an error.
     */
    public static int extractLine(byte[] buffer, int offset, String[] outStr) throws IOException {
        final int end = buffer.length;
        if (offset >= end) {
            throw new IOException("Incomplete data");
        }

        int pos;
        for (pos = offset; pos < end; pos++) {
            byte c = buffer[pos];
            // at LF we declare end of line, and return the next char as the
            // starting point for the next time through
            if (c == '\n') {
                break;
            }
        }
        outStr[0] = new String(buffer, offset, pos - offset);
        pos++;  // may be pointing an extra byte past the end but that's okay
        return pos;
    }

    /**
     * Consumes a tar file header block [sequence] and accumulates the relevant metadata.
     */
    public FileMetadata readTarHeaders() throws IOException {
        byte[] block = new byte[512];
        FileMetadata info = null;

        boolean gotHeader = readTarHeader(block);
        if (gotHeader) {
            try {
                // okay, presume we're okay, and extract the various metadata
                info = new FileMetadata();
                info.size = extractRadix(block,
                        TAR_HEADER_OFFSET_FILESIZE,
                        TAR_HEADER_LENGTH_FILESIZE,
                        TAR_HEADER_LONG_RADIX);
                info.mtime = extractRadix(block,
                        TAR_HEADER_OFFSET_MODTIME,
                        TAR_HEADER_LENGTH_MODTIME,
                        TAR_HEADER_LONG_RADIX);
                info.mode = extractRadix(block,
                        TAR_HEADER_OFFSET_MODE,
                        TAR_HEADER_LENGTH_MODE,
                        TAR_HEADER_LONG_RADIX);

                info.path = extractString(block,
                        TAR_HEADER_OFFSET_PATH_PREFIX,
                        TAR_HEADER_LENGTH_PATH_PREFIX);
                String path = extractString(block,
                        TAR_HEADER_OFFSET_PATH,
                        TAR_HEADER_LENGTH_PATH);
                if (path.length() > 0) {
                    if (info.path.length() > 0) {
                        info.path += '/';
                    }
                    info.path += path;
                }

                // tar link indicator field: 1 byte at offset 156 in the header.
                int typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                if (typeChar == 'x') {
                    // pax extended header, so we need to read that
                    gotHeader = readPaxExtendedHeader(info);
                    if (gotHeader) {
                        // and after a pax extended header comes another real header -- read
                        // that to find the real file type
                        gotHeader = readTarHeader(block);
                    }
                    if (!gotHeader) {
                        throw new IOException("Bad or missing pax header");
                    }

                    typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                }

                switch (typeChar) {
                    case '0':
                        info.type = BackupAgent.TYPE_FILE;
                        break;
                    case '5': {
                        info.type = BackupAgent.TYPE_DIRECTORY;
                        if (info.size != 0) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Directory entry with nonzero size in header");
                            info.size = 0;
                        }
                        break;
                    }
                    case 0: {
                        // presume EOF
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Saw type=0 in tar header block, info=" + info);
                        }
                        return null;
                    }
                    default: {
                        Slog.e(RefactoredBackupManagerService.TAG,
                                "Unknown tar entity type: " + typeChar);
                        throw new IOException("Unknown entity type " + typeChar);
                    }
                }

                // Parse out the path
                //
                // first: apps/shared/unrecognized
                if (FullBackup.SHARED_PREFIX.regionMatches(0,
                        info.path, 0, FullBackup.SHARED_PREFIX.length())) {
                    // File in shared storage.  !!! TODO: implement this.
                    info.path = info.path.substring(FullBackup.SHARED_PREFIX.length());
                    info.packageName = RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
                    info.domain = FullBackup.SHARED_STORAGE_TOKEN;
                    if (RefactoredBackupManagerService.DEBUG) {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "File in shared storage: " + info.path);
                    }
                } else if (FullBackup.APPS_PREFIX.regionMatches(0,
                        info.path, 0, FullBackup.APPS_PREFIX.length())) {
                    // App content!  Parse out the package name and domain

                    // strip the apps/ prefix
                    info.path = info.path.substring(FullBackup.APPS_PREFIX.length());

                    // extract the package name
                    int slash = info.path.indexOf('/');
                    if (slash < 0) {
                        throw new IOException("Illegal semantic path in " + info.path);
                    }
                    info.packageName = info.path.substring(0, slash);
                    info.path = info.path.substring(slash + 1);

                    // if it's a manifest or metadata payload we're done, otherwise parse
                    // out the domain into which the file will be restored
                    if (!info.path.equals(RefactoredBackupManagerService.BACKUP_MANIFEST_FILENAME)
                            && !info.path.equals(
                            RefactoredBackupManagerService.BACKUP_METADATA_FILENAME)) {
                        slash = info.path.indexOf('/');
                        if (slash < 0) {
                            throw new IOException("Illegal semantic path in non-manifest "
                                    + info.path);
                        }
                        info.domain = info.path.substring(0, slash);
                        info.path = info.path.substring(slash + 1);
                    }
                }
            } catch (IOException e) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.e(RefactoredBackupManagerService.TAG,
                            "Parse error in header: " + e.getMessage());
                    if (RefactoredBackupManagerService.MORE_DEBUG) {
                        hexLog(block);
                    }
                }
                throw e;
            }
        }
        return info;
    }

    private boolean readTarHeader(byte[] block) throws IOException {
        final int got = readExactly(mInputStream, block, 0, 512);
        if (got == 0) {
            return false;     // Clean EOF
        }
        if (got < 512) {
            throw new IOException("Unable to read full block header");
        }
        mBytesReadListener.onBytesRead(512);
        return true;
    }

    // overwrites 'info' fields based on the pax extended header
    private boolean readPaxExtendedHeader(FileMetadata info)
            throws IOException {
        // We should never see a pax extended header larger than this
        if (info.size > 32 * 1024) {
            Slog.w(RefactoredBackupManagerService.TAG,
                    "Suspiciously large pax header size " + info.size
                            + " - aborting");
            throw new IOException("Sanity failure: pax header size " + info.size);
        }

        // read whole blocks, not just the content size
        int numBlocks = (int) ((info.size + 511) >> 9);
        byte[] data = new byte[numBlocks * 512];
        if (readExactly(mInputStream, data, 0, data.length) < data.length) {
            throw new IOException("Unable to read full pax header");
        }
        mBytesReadListener.onBytesRead(data.length);

        final int contentSize = (int) info.size;
        int offset = 0;
        do {
            // extract the line at 'offset'
            int eol = offset + 1;
            while (eol < contentSize && data[eol] != ' ') {
                eol++;
            }
            if (eol >= contentSize) {
                // error: we just hit EOD looking for the end of the size field
                throw new IOException("Invalid pax data");
            }
            // eol points to the space between the count and the key
            int linelen = (int) extractRadix(data, offset, eol - offset, 10);
            int key = eol + 1;  // start of key=value
            eol = offset + linelen - 1; // trailing LF
            int value;
            for (value = key + 1; data[value] != '=' && value <= eol; value++) {
                ;
            }
            if (value > eol) {
                throw new IOException("Invalid pax declaration");
            }

            // pax requires that key/value strings be in UTF-8
            String keyStr = new String(data, key, value - key, "UTF-8");
            // -1 to strip the trailing LF
            String valStr = new String(data, value + 1, eol - value - 1, "UTF-8");

            if ("path".equals(keyStr)) {
                info.path = valStr;
            } else if ("size".equals(keyStr)) {
                info.size = Long.parseLong(valStr);
            } else {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.i(RefactoredBackupManagerService.TAG, "Unhandled pax key: " + key);
                }
            }

            offset += linelen;
        } while (offset < contentSize);

        return true;
    }

    // Given an actual file content size, consume the post-content padding mandated
    // by the tar format.
    public void skipTarPadding(long size) throws IOException {
        long partial = (size + 512) % 512;
        if (partial > 0) {
            final int needed = 512 - (int) partial;
            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.i(RefactoredBackupManagerService.TAG,
                        "Skipping tar padding: " + needed + " bytes");
            }
            byte[] buffer = new byte[needed];
            if (readExactly(mInputStream, buffer, 0, needed) == needed) {
                mBytesReadListener.onBytesRead(needed);
            } else {
                throw new IOException("Unexpected EOF in padding");
            }
        }
    }

    private static long extractRadix(byte[] data, int offset, int maxChars, int radix)
            throws IOException {
        long value = 0;
        final int end = offset + maxChars;
        for (int i = offset; i < end; i++) {
            final byte b = data[i];
            // Numeric fields in tar can terminate with either NUL or SPC
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > ('0' + radix - 1)) {
                throw new IOException("Invalid number in header: '" + (char) b
                        + "' for radix " + radix);
            }
            value = radix * value + (b - '0');
        }
        return value;
    }

    private static String extractString(byte[] data, int offset, int maxChars) throws IOException {
        final int end = offset + maxChars;
        int eos = offset;
        // tar string fields terminate early with a NUL
        while (eos < end && data[eos] != 0) {
            eos++;
        }
        return new String(data, offset, eos - offset, "US-ASCII");
    }

    private static void hexLog(byte[] block) {
        int offset = 0;
        int todo = block.length;
        StringBuilder buf = new StringBuilder(64);
        while (todo > 0) {
            buf.append(String.format("%04x   ", offset));
            int numThisLine = (todo > 16) ? 16 : todo;
            for (int i = 0; i < numThisLine; i++) {
                buf.append(String.format("%02x ", block[offset + i]));
            }
            Slog.i("hexdump", buf.toString());
            buf.setLength(0);
            todo -= numThisLine;
            offset += numThisLine;
        }
    }

}
