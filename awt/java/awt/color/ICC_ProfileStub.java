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
 * @author Oleg V. Khaschansky
 * @version $Revision$
 */

package java.awt.color;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectStreamException;
import java.io.OutputStream;

import org.apache.harmony.awt.internal.nls.Messages;

final class ICC_ProfileStub extends ICC_Profile {
    private static final long serialVersionUID = 501389760875253507L;

    transient int colorspace;

    public ICC_ProfileStub(int csSpecifier) {
        switch (csSpecifier) {
            case ColorSpace.CS_sRGB:
            case ColorSpace.CS_CIEXYZ:
            case ColorSpace.CS_LINEAR_RGB:
            case ColorSpace.CS_PYCC:
            case ColorSpace.CS_GRAY:
                break;
            default:
                // awt.15D=Invalid colorspace
                throw new IllegalArgumentException(Messages.getString("awt.15D")); //$NON-NLS-1$
        }
        colorspace = csSpecifier;
    }

    @Override
    public void write(String fileName) throws IOException {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    /**
     * Serializable implementation
     *
     * @throws ObjectStreamException
     */
    private Object writeReplace() throws ObjectStreamException {
        return loadProfile();
    }

    @Override
    public void write(OutputStream s) throws IOException {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public void setData(int tagSignature, byte[] tagData) {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public byte[] getData(int tagSignature) {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public byte[] getData() {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    protected void finalize() {
    }

    @Override
    public int getProfileClass() {
        return CLASS_COLORSPACECONVERSION;
    }

    @Override
    public int getPCSType() {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public int getNumComponents() {
        switch (colorspace) {
            case ColorSpace.CS_sRGB:
            case ColorSpace.CS_CIEXYZ:
            case ColorSpace.CS_LINEAR_RGB:
            case ColorSpace.CS_PYCC:
                return 3;
            case ColorSpace.CS_GRAY:
                return 1;
            default:
                throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
        }
    }

    @Override
    public int getMinorVersion() {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public int getMajorVersion() {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    @Override
    public int getColorSpaceType() {
        switch (colorspace) {
            case ColorSpace.CS_sRGB:
            case ColorSpace.CS_LINEAR_RGB:
                return ColorSpace.TYPE_RGB;
            case ColorSpace.CS_CIEXYZ:
                return ColorSpace.TYPE_XYZ;
            case ColorSpace.CS_PYCC:
                return ColorSpace.TYPE_3CLR;
            case ColorSpace.CS_GRAY:
                return ColorSpace.TYPE_GRAY;
            default:
                throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
        }
    }

    public static ICC_Profile getInstance(String fileName) throws IOException {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    public static ICC_Profile getInstance(InputStream s) throws IOException {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    public static ICC_Profile getInstance(byte[] data) {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    public static ICC_Profile getInstance(int cspace) {
        throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
    }

    public ICC_Profile loadProfile() {
        switch (colorspace) {
            case ColorSpace.CS_sRGB:
                return ICC_Profile.getInstance(ColorSpace.CS_sRGB);
            case ColorSpace.CS_GRAY:
                return ICC_Profile.getInstance(ColorSpace.CS_GRAY);
            case ColorSpace.CS_CIEXYZ:
                return ICC_Profile.getInstance(ColorSpace.CS_CIEXYZ);
            case ColorSpace.CS_LINEAR_RGB:
                return ICC_Profile.getInstance(ColorSpace.CS_LINEAR_RGB);
            case ColorSpace.CS_PYCC:
                return ICC_Profile.getInstance(ColorSpace.CS_PYCC);
            default:
                throw new UnsupportedOperationException("Stub cannot perform this operation"); //$NON-NLS-1$
        }
    }
}