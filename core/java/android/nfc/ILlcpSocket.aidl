/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.nfc;

/**
 * @hide
 */
interface ILlcpSocket
{
    int close(int nativeHandle);
    int connect(int nativeHandle, int sap);
    int connectByName(int nativeHandle, String sn);
    int getLocalSap(int nativeHandle);
    int getLocalSocketMiu(int nativeHandle);
    int getLocalSocketRw(int nativeHandle);
    int getRemoteSocketMiu(int nativeHandle);
    int getRemoteSocketRw(int nativeHandle);
    int receive(int nativeHandle, out byte[] receiveBuffer);
    int send(int nativeHandle, in byte[] data);
}