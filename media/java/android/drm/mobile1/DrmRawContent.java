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

package android.drm.mobile1;

import java.io.*;

/**
 * This class provides interfaces to access the DRM raw content.
 */
public class DrmRawContent {
    /**
     * The "application/vnd.oma.drm.message" mime type.
     */
    public static final String DRM_MIMETYPE_MESSAGE_STRING = "application/vnd.oma.drm.message";

    /**
     * The "application/vnd.oma.drm.content" mime type.
     */
    public static final String DRM_MIMETYPE_CONTENT_STRING = "application/vnd.oma.drm.content";

    /**
     * The DRM delivery type: Forward-Lock
     */
    public static final int DRM_FORWARD_LOCK = 1;

    /**
     * The DRM delivery type: Combined Delivery
     */
    public static final int DRM_COMBINED_DELIVERY = 2;

    /**
     * The DRM delivery type: Separate Delivery
     */
    public static final int DRM_SEPARATE_DELIVERY = 3;

    /**
     * The DRM delivery type: Separate Delivery in DRM message
     */
    public static final int DRM_SEPARATE_DELIVERY_DM = 4;

    /**
     * The DRM media content length is unknown currently
     */
    public static final int DRM_UNKNOWN_DATA_LEN = -1;


    /**
     * The id of "application/vnd.oma.drm.message" mime type.
     */
    private static final int DRM_MIMETYPE_MESSAGE = 1;

    /**
     * The id of "application/vnd.oma.drm.content" mime type.
     */
    private static final int DRM_MIMETYPE_CONTENT = 2;

    /**
     * Successful operation.
     */
    private static final int JNI_DRM_SUCCESS = 0;

    /**
     * General failure.
     */
    private static final int JNI_DRM_FAILURE = -1;

    /**
     * Indicates the end of the DRM content is reached.
     */
    private static final int JNI_DRM_EOF = -2;

    /**
     * The media content length is unknown from native method
     */
    private static final int JNI_DRM_UNKNOWN_DATA_LEN = -3;

    /**
     * The member to save the original InputStream data.
     */
    private BufferedInputStream inData;

    /**
     * The member to save the original InputStream data length.
     */
    private int inDataLen;

    /**
     * The unique id to this DRM content. It will be initialized
     * in constructor by native method. And it will not be changed
     * after initialization.
     */
    private int id;

    /**
     * The rights issuer address of this DRM object.
     */
    private String rightsIssuer;

    /**
     * The media content type of this DRM object.
     */
    private String mediaType;

    /**
     * The delivery method type of this DRM object.
     */
    private int rawType;


    /**
     * Construct a DrmRawContent object.
     *
     * @param inRawdata     object of DRM raw data stream.
     * @param len           the length of raw data can be read.
     * @param mimeTypeStr   the mime type of the DRM content.
     */
    public DrmRawContent(InputStream inRawdata, int len, String mimeTypeStr) throws DrmException, IOException {
        int mimeType;

        id = -1;
        inData = new BufferedInputStream(inRawdata, 1024);
        inDataLen = len;

        if (DRM_MIMETYPE_MESSAGE_STRING.equals(mimeTypeStr))
            mimeType = DRM_MIMETYPE_MESSAGE;
        else if (DRM_MIMETYPE_CONTENT_STRING.equals(mimeTypeStr))
            mimeType = DRM_MIMETYPE_CONTENT;
        else
            throw new IllegalArgumentException("mimeType must be DRM_MIMETYPE_MESSAGE or DRM_MIMETYPE_CONTENT");

        if (len <= 0)
            throw new IllegalArgumentException("len must be > 0");

        /* call native method to initialize this DRM content */
        id = nativeConstructDrmContent(inData, inDataLen, mimeType);

        if (JNI_DRM_FAILURE == id)
            throw new DrmException("nativeConstructDrmContent() returned JNI_DRM_FAILURE");

        /* init the rights issuer field. */
        rightsIssuer = nativeGetRightsAddress();

        /* init the raw content type. */
        rawType = nativeGetDeliveryMethod();
        if (JNI_DRM_FAILURE == rawType)
            throw new DrmException("nativeGetDeliveryMethod() returned JNI_DRM_FAILURE");

        /* init the media content type. */
        mediaType = nativeGetContentType();
        if (null == mediaType)
            throw new DrmException("nativeGetContentType() returned null");
    }

    /**
     * Get rights address from raw Seperate Delivery content.
     *
     * @return the string of the rights issuer address,
     *         or null if no rights issuer.
     */
    public String getRightsAddress() {
        return rightsIssuer;
    }

    /**
     * Get the type of the raw DRM content.
     *
     * @return one of the following delivery type of this DRM content:
     *              #DRM_FORWARD_LOCK
     *              #DRM_COMBINED_DELIVERY
     *              #DRM_SEPARATE_DELIVERY
     *              #DRM_SEPARATE_DELIVERY_DM
     */
    public int getRawType() {
        return rawType;
    }

    /**
     * Get one InputStream object to read decrypted content.
     *
     * @param rights        the rights object contain decrypted key.
     *
     * @return the InputStream object of decrypted media content.
     */
    public InputStream getContentInputStream(DrmRights rights) {
        if (null == rights)
            throw new NullPointerException();

        return new DrmInputStream(rights);
    }

    /**
     * Get the type of the decrypted media content.
     *
     * @return the decrypted media content type of this DRM content.
     */
    public String getContentType() {
        return mediaType;
    }

    /**
     * Get the length of the decrypted media content.
     *
     * @param rights        the rights object contain decrypted key.
     *
     * @return the length of the decrypted media content.
     *         #DRM_UNKNOWN_DATA_LEN if the length is unknown currently.
     */
    public int getContentLength(DrmRights rights) throws DrmException {
        /**
         * Because currently the media object associate with rights object
         * has been handled in native logic, so here it is not need to deal
         * the rights. But for the apps, it is mandatory for user to get
         * the rights object before get the media content length.
         */
        if (null == rights)
            throw new NullPointerException();

        int mediaLen = nativeGetContentLength();

        if (JNI_DRM_FAILURE == mediaLen)
            throw new DrmException("nativeGetContentLength() returned JNI_DRM_FAILURE");

        if (JNI_DRM_UNKNOWN_DATA_LEN == mediaLen)
            return DRM_UNKNOWN_DATA_LEN;

        return mediaLen;
    }

    /**
     * This class provide a InputStream to the DRM media content.
     */
    class DrmInputStream extends InputStream
    {
        /**
         * The flag to indicate whether this stream is closed or not.
         */
        private boolean isClosed;

        /**
         * The offset of this DRM content to be reset.
         */
        private int offset;

        /**
         * A byte of data to be readed.
         */
        private byte[] b;

        /**
         * Construct a DrmInputStream instance.
         */
        public DrmInputStream(DrmRights rights) {
            /**
             * Because currently the media object associate with rights object
             * has been handled in native logic, so here it is not need to deal
             * the rights. But for the apps, it is mandatory for user to get
             * the rights object before get the media content data.
             */

            isClosed = false;
            offset = 0;
            b = new byte[1];
        }

        /* Non-javadoc
         * @see java.io.InputStream#available()
         */
        public int available() throws IOException {
            /* call native method to get this DRM decrypted media content length */
            int len = nativeGetContentLength();

            if (JNI_DRM_FAILURE == len)
                throw new IOException();

            /* if the length is unknown, just return 0 for available value */
            if (JNI_DRM_UNKNOWN_DATA_LEN == len)
                return 0;

            int availableLen = len - offset;
            if (availableLen < 0)
                throw new IOException();

            return availableLen;
        }

        /* Non-javadoc
         * @see java.io.InputStream#read()
         */
        public int read() throws IOException {
            int res;

            res = read(b, 0, 1);

            if (-1 == res)
                return -1;

            return b[0] & 0xff;
        }

        /* Non-javadoc
         * @see java.io.InputStream#read(byte)
         */
        public int read(byte[] b) throws IOException {
            return read(b, 0, b.length);
        }

        /* Non-javadoc
         * @see java.io.InputStream#read(byte, int, int)
         */
        public int read(byte[] b, int off, int len) throws IOException {
            if (null == b)
                throw new NullPointerException();
            if (off < 0 || len < 0 || off + len > b.length)
                throw new IndexOutOfBoundsException();
            if (true == isClosed)
                throw new IOException();

            if (0 == len)
                return 0;

            len = nativeReadContent(b, off, len, offset);

            if (JNI_DRM_FAILURE == len)
                throw new IOException();
            else if (JNI_DRM_EOF == len)
                return -1;

            offset += len;

            return len;
        }

        /* Non-javadoc
         * @see java.io.InputStream#markSupported()
         */
        public boolean markSupported() {
            return false;
        }

        /* Non-javadoc
         * @see java.io.InputStream#mark(int)
         */
        public void mark(int readlimit) {
        }

        /* Non-javadoc
         * @see java.io.InputStream#reset()
         */
        public void reset() throws IOException {
            throw new IOException();
        }

        /* Non-javadoc
         * @see java.io.InputStream#skip()
         */
        public long skip(long n) throws IOException {
            return 0;
        }

        /* Non-javadoc
         * @see java.io.InputStream#close()
         */
        public void close() {
            isClosed = true;
        }
    }

    /**
     * native method: construct a DRM content according the mime type.
     *
     * @param data      input DRM content data to be parsed.
     * @param len       the length of the data.
     * @param mimeType  the mime type of this DRM content. the value of this field includes:
     *                      #DRM_MIMETYPE_MESSAGE
     *                      #DRM_MIMETYPE_CONTENT
     *
     * @return #the id of the DRM content if succeed.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeConstructDrmContent(InputStream data, int len, int mimeType);

    /**
     * native method: get this DRM content rights issuer.
     *
     * @return the address of rights issuer if in case of separate delivery.
     *         null if not separete delivery, or otherwise.
     */
    private native String nativeGetRightsAddress();

    /**
     * native method: get this DRM content delivery type.
     *
     * @return the delivery method, the value may be one of the following:
     *              #DRM_FORWARD_LOCK
     *              #DRM_COMBINED_DELIVERY
     *              #DRM_SEPARATE_DELIVERY
     *              #DRM_SEPARATE_DELIVERY_DM
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeGetDeliveryMethod();

    /**
     * native method: get a piece of media content data.
     *
     * @param buf       the buffer to save DRM media content data.
     * @param bufOff    the offset of the buffer to start to save data.
     * @param len       the number of byte to read.
     * @param mediaOff  the offset of the media content data to start to read.
     *
     * @return the length of the media content data has been read.
     *         #JNI_DRM_EOF if reach to end of the media content.
     *         #JNI_DRM_FAILURE if fail.
     */
    private native int nativeReadContent(byte[] buf, int bufOff, int len, int mediaOff);

    /**
     * native method: get this DRM content type.
     *
     * @return the decrypted media content type.
     *         null if fail.
     */
    private native String nativeGetContentType();

    /**
     * native method: get this DRM decrypted media content length.
     *
     * @return the length of decrypted media content.
     *         #JNI_DRM_FAILURE if fail.
     *         #JNI_DRM_UNKNOWN_DATA_LEN if the length is unknown currently.
     */
    private native int nativeGetContentLength();

    /**
     * The finalizer of the DRMRawContent. Do some cleanup.
     */
    protected native void finalize();


    /**
     * Load the shared library to link the native methods.
     */
    static {
        try {
            System.loadLibrary("drm1_jni");
        }
        catch (UnsatisfiedLinkError ule) {
            System.err.println("WARNING: Could not load libdrm1_jni.so");
        }
    }
}
