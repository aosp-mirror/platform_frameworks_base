/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.LONG;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteComposeOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedCompanionOperation;

import java.util.List;

/**
 * Describe some basic information for a RemoteCompose document
 * <p>
 * It encodes the version of the document (following semantic versioning) as well
 * as the dimensions of the document in pixels.
 */
public class Header implements RemoteComposeOperation {
    public static final int MAJOR_VERSION = 0;
    public static final int MINOR_VERSION = 1;
    public static final int PATCH_VERSION = 0;

    int mMajorVersion;
    int mMinorVersion;
    int mPatchVersion;

    int mWidth;
    int mHeight;

    float mDensity;
    long mCapabilities;

    public static final Companion COMPANION = new Companion();

    /**
     * It encodes the version of the document (following semantic versioning) as well
     * as the dimensions of the document in pixels.
     *
     * @param majorVersion the major version of the RemoteCompose document API
     * @param minorVersion the minor version of the RemoteCompose document API
     * @param patchVersion the patch version of the RemoteCompose document API
     * @param width        the width of the RemoteCompose document
     * @param height       the height of the RemoteCompose document
     * @param density      the density at which the document was originally created
     * @param capabilities bitmask field storing needed capabilities (unused for now)
     */
    public Header(int majorVersion, int minorVersion, int patchVersion,
                  int width, int height, float density, long capabilities) {
        this.mMajorVersion = majorVersion;
        this.mMinorVersion = minorVersion;
        this.mPatchVersion = patchVersion;
        this.mWidth = width;
        this.mHeight = height;
        this.mDensity = density;
        this.mCapabilities = capabilities;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mWidth, mHeight, mDensity, mCapabilities);
    }

    @Override
    public String toString() {
        return "HEADER v" + mMajorVersion + "."
                + mMinorVersion + "." + mPatchVersion + ", "
                + mWidth + " x " + mHeight + " [" + mCapabilities + "]";
    }

    @Override
    public void apply(RemoteContext context) {
        context.header(mMajorVersion, mMinorVersion, mPatchVersion, mWidth, mHeight, mCapabilities);
    }

    @Override
    public String deepToString(String indent) {
        return toString();
    }

    public static class Companion implements DocumentedCompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "Header";
        }

        @Override
        public int id() {
            return Operations.HEADER;
        }

        public void apply(WireBuffer buffer, int width, int height,
                          float density, long capabilities) {
            buffer.start(Operations.HEADER);
            buffer.writeInt(MAJOR_VERSION); // major version number of the protocol
            buffer.writeInt(MINOR_VERSION); // minor version number of the protocol
            buffer.writeInt(PATCH_VERSION); // patch version number of the protocol
            buffer.writeInt(width);
            buffer.writeInt(height);
            // buffer.writeFloat(density);
            buffer.writeLong(capabilities);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int majorVersion = buffer.readInt();
            int minorVersion = buffer.readInt();
            int patchVersion = buffer.readInt();
            int width = buffer.readInt();
            int height = buffer.readInt();
            // float density = buffer.readFloat();
            float density = 1f;
            long capabilities = buffer.readLong();
            Header header = new Header(majorVersion, minorVersion, patchVersion,
                    width, height, density, capabilities);
            operations.add(header);
        }

        @Override
        public void documentation(DocumentationBuilder doc) {
            doc.operation("Protocol Operations", id(), name())
                    .description("Document metadata, containing the version,"
                          + " original size & density, capabilities mask")
                    .field(INT, "MAJOR_VERSION", "Major version")
                    .field(INT, "MINOR_VERSION", "Minor version")
                    .field(INT, "PATCH_VERSION", "Patch version")
                    .field(INT, "WIDTH", "Major version")
                    .field(INT, "HEIGHT", "Major version")
                    // .field(FLOAT, "DENSITY", "Major version")
                    .field(LONG, "CAPABILITIES", "Major version");
        }
    }
}
