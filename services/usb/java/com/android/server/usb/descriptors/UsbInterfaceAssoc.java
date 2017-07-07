/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.usb.descriptors;

// import com.android.server.usb.descriptors.report.ReportCanvas;

/**
 * @hide
 * A USB Interface Association Descriptor.
 * found this one here: http://www.usb.org/developers/docs/whitepapers/iadclasscode_r10.pdf
 * also: https://msdn.microsoft.com/en-us/library/windows/hardware/ff540054(v=vs.85).aspx
 */
public final class UsbInterfaceAssoc extends UsbDescriptor {
    private static final String TAG = "UsbInterfaceAssoc";

    private byte mFirstInterface;
    private byte mInterfaceCount;
    private byte mFunctionClass;
    private byte mFunctionSubClass;
    private byte mFunctionProtocol;
    private byte mFunction;

    public UsbInterfaceAssoc(int length, byte type) {
        super(length, type);
    }

    public byte getFirstInterface() {
        return mFirstInterface;
    }

    public byte getInterfaceCount() {
        return mInterfaceCount;
    }

    public byte getFunctionClass() {
        return mFunctionClass;
    }

    public byte getFunctionSubClass() {
        return mFunctionSubClass;
    }

    public byte getFunctionProtocol() {
        return mFunctionProtocol;
    }

    public byte getFunction() {
        return mFunction;
    }

    @Override
    public int parseRawDescriptors(ByteStream stream) {
        mFirstInterface = stream.getByte();
        mInterfaceCount = stream.getByte();
        mFunctionClass = stream.getByte();
        mFunctionSubClass = stream.getByte();
        mFunctionProtocol = stream.getByte();
        mFunction = stream.getByte();

        return mLength;
    }

    // TODO - Report fields
//    @Override
//    public void report(ReportCanvas canvas) {
//        super.report(canvas);
//
//    }
}
