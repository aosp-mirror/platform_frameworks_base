/*
 * Copyright (C) 2006 The Android Open Source Project
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

#include <stdint.h>
#include <sys/types.h>

#include <utils/Parcel.h>

#include <utils/IDataConnection.h>

namespace android {

// ---------------------------------------------------------------------------

enum
{
    CONNECT_TRANSACTION = IBinder::FIRST_CALL_TRANSACTION,
    DISCONNECT_TRANSACTION = IBinder::FIRST_CALL_TRANSACTION + 1
};

class BpDataConnection : public BpInterface<IDataConnection>
{
public:
    BpDataConnection::BpDataConnection(const sp<IBinder>& impl)
        : BpInterface<IDataConnection>(impl)
    {
    }

	virtual void connect()
	{
		Parcel data, reply;
        data.writeInterfaceToken(IDataConnection::descriptor());
		remote()->transact(CONNECT_TRANSACTION, data, &reply);
	}
	
	virtual void disconnect()
	{
		Parcel data, reply;
		remote()->transact(DISCONNECT_TRANSACTION, data, &reply);
	}
};

IMPLEMENT_META_INTERFACE(DataConnection, "android.utils.IDataConnection");

#define CHECK_INTERFACE(interface, data, reply) \
        do { if (!data.enforceInterface(interface::getInterfaceDescriptor())) { \
            LOGW("Call incorrectly routed to " #interface); \
            return PERMISSION_DENIED; \
        } } while (0)

status_t BnDataConnection::onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code)
    {
		case CONNECT_TRANSACTION:
		{                   
            CHECK_INTERFACE(IDataConnection, data, reply);
			connect();
			return NO_ERROR;
		}    
		
		case DISCONNECT_TRANSACTION:
		{                   
            CHECK_INTERFACE(IDataConnection, data, reply);
			disconnect();
			return NO_ERROR;
		}
       
		default:
			return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
