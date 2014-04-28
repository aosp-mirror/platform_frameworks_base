/*
 * Copyright (C) 2012 The Android Open Source Project
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
package android.os;

import java.net.InetAddress;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.util.Locale;
import static android.system.OsConstants.*;

class CommonTimeUtils {
    /**
     * Successful operation.
     */
    public static final int SUCCESS = 0;
    /**
     * Unspecified error.
     */
    public static final int ERROR = -1;
    /**
     * Operation failed due to bad parameter value.
     */
    public static final int ERROR_BAD_VALUE = -4;
    /**
     * Operation failed due to dead remote object.
     */
    public static final int ERROR_DEAD_OBJECT = -7;

    public CommonTimeUtils(IBinder remote, String interfaceDesc) {
        mRemote = remote;
        mInterfaceDesc = interfaceDesc;
    }

    public int transactGetInt(int method_code, int error_ret_val)
    throws RemoteException {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int ret_val;

        try {
            int res;
            data.writeInterfaceToken(mInterfaceDesc);
            mRemote.transact(method_code, data, reply, 0);

            res = reply.readInt();
            ret_val = (0 == res) ? reply.readInt() : error_ret_val;
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        return ret_val;
    }

    public int transactSetInt(int method_code, int val) {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        try {
            data.writeInterfaceToken(mInterfaceDesc);
            data.writeInt(val);
            mRemote.transact(method_code, data, reply, 0);

            return reply.readInt();
        }
        catch (RemoteException e) {
            return ERROR_DEAD_OBJECT;
        }
        finally {
            reply.recycle();
            data.recycle();
        }
    }

    public long transactGetLong(int method_code, long error_ret_val)
    throws RemoteException {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        long ret_val;

        try {
            int res;
            data.writeInterfaceToken(mInterfaceDesc);
            mRemote.transact(method_code, data, reply, 0);

            res = reply.readInt();
            ret_val = (0 == res) ? reply.readLong() : error_ret_val;
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        return ret_val;
    }

    public int transactSetLong(int method_code, long val) {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        try {
            data.writeInterfaceToken(mInterfaceDesc);
            data.writeLong(val);
            mRemote.transact(method_code, data, reply, 0);

            return reply.readInt();
        }
        catch (RemoteException e) {
            return ERROR_DEAD_OBJECT;
        }
        finally {
            reply.recycle();
            data.recycle();
        }
    }

    public String transactGetString(int method_code, String error_ret_val)
    throws RemoteException {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        String ret_val;

        try {
            int res;
            data.writeInterfaceToken(mInterfaceDesc);
            mRemote.transact(method_code, data, reply, 0);

            res = reply.readInt();
            ret_val = (0 == res) ? reply.readString() : error_ret_val;
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        return ret_val;
    }

    public int transactSetString(int method_code, String val) {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();

        try {
            data.writeInterfaceToken(mInterfaceDesc);
            data.writeString(val);
            mRemote.transact(method_code, data, reply, 0);

            return reply.readInt();
        }
        catch (RemoteException e) {
            return ERROR_DEAD_OBJECT;
        }
        finally {
            reply.recycle();
            data.recycle();
        }
    }

    public InetSocketAddress transactGetSockaddr(int method_code)
    throws RemoteException {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        InetSocketAddress ret_val = null;

        try {
            int res;
            data.writeInterfaceToken(mInterfaceDesc);
            mRemote.transact(method_code, data, reply, 0);

            res = reply.readInt();
            if (0 == res) {
                int type;
                int port = 0;
                String addrStr = null;

                type = reply.readInt();

                if (AF_INET == type) {
                    int addr = reply.readInt();
                    port = reply.readInt();
                    addrStr = String.format(Locale.US, "%d.%d.%d.%d",
                                                       (addr >> 24) & 0xFF,
                                                       (addr >> 16) & 0xFF,
                                                       (addr >>  8) & 0xFF,
                                                        addr        & 0xFF);
                } else if (AF_INET6 == type) {
                    int addr1 = reply.readInt();
                    int addr2 = reply.readInt();
                    int addr3 = reply.readInt();
                    int addr4 = reply.readInt();

                    port = reply.readInt();

                    int flowinfo = reply.readInt();
                    int scope_id = reply.readInt();

                    addrStr = String.format(Locale.US, "[%04X:%04X:%04X:%04X:%04X:%04X:%04X:%04X]",
                                                       (addr1 >> 16) & 0xFFFF, addr1 & 0xFFFF,
                                                       (addr2 >> 16) & 0xFFFF, addr2 & 0xFFFF,
                                                       (addr3 >> 16) & 0xFFFF, addr3 & 0xFFFF,
                                                       (addr4 >> 16) & 0xFFFF, addr4 & 0xFFFF);
                }

                if (null != addrStr) {
                    ret_val = new InetSocketAddress(addrStr, port);
                }
            }
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        return ret_val;
    }

    public int transactSetSockaddr(int method_code, InetSocketAddress addr) {
        android.os.Parcel data  = android.os.Parcel.obtain();
        android.os.Parcel reply = android.os.Parcel.obtain();
        int ret_val = ERROR;

        try {
            data.writeInterfaceToken(mInterfaceDesc);

            if (null == addr) {
                data.writeInt(0);
            } else {
                data.writeInt(1);
                final InetAddress a = addr.getAddress();
                final byte[]      b = a.getAddress();
                final int         p = addr.getPort();

                if (a instanceof Inet4Address) {
                    int v4addr = (((int)b[0] & 0xFF) << 24) |
                                 (((int)b[1] & 0xFF) << 16) |
                                 (((int)b[2] & 0xFF) << 8) |
                                  ((int)b[3] & 0xFF);

                    data.writeInt(AF_INET);
                    data.writeInt(v4addr);
                    data.writeInt(p);
                } else
                if (a instanceof Inet6Address) {
                    int i;
                    Inet6Address v6 = (Inet6Address)a;
                    data.writeInt(AF_INET6);
                    for (i = 0; i < 4; ++i) {
                        int aword = (((int)b[(i*4) + 0] & 0xFF) << 24) |
                                    (((int)b[(i*4) + 1] & 0xFF) << 16) |
                                    (((int)b[(i*4) + 2] & 0xFF) << 8) |
                                     ((int)b[(i*4) + 3] & 0xFF);
                        data.writeInt(aword);
                    }
                    data.writeInt(p);
                    data.writeInt(0);   // flow info
                    data.writeInt(v6.getScopeId());
                } else {
                    return ERROR_BAD_VALUE;
                }
            }

            mRemote.transact(method_code, data, reply, 0);
            ret_val = reply.readInt();
        }
        catch (RemoteException e) {
            ret_val = ERROR_DEAD_OBJECT;
        }
        finally {
            reply.recycle();
            data.recycle();
        }

        return ret_val;
    }

    private IBinder mRemote;
    private String mInterfaceDesc;
};
