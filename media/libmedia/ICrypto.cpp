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

//#define LOG_NDEBUG 0
#define LOG_TAG "ICrypto"
#include <utils/Log.h>

#include <binder/Parcel.h>
#include <media/ICrypto.h>
#include <media/stagefright/foundation/ADebug.h>

namespace android {

enum {
    INITIALIZE = IBinder::FIRST_CALL_TRANSACTION,
    TERMINATE,
    SET_ENTITLEMENT_KEY,
    SET_ECM,
    DECRYPT_VIDEO,
    DECRYPT_AUDIO,
};

struct BpCrypto : public BpInterface<ICrypto> {
    BpCrypto(const sp<IBinder> &impl)
        : BpInterface<ICrypto>(impl) {
    }

    virtual status_t initialize() {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        remote()->transact(INITIALIZE, data, &reply);

        return reply.readInt32();
    }

    virtual status_t terminate() {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        remote()->transact(TERMINATE, data, &reply);

        return reply.readInt32();
    }

    virtual status_t setEntitlementKey(
            const void *key, size_t keyLength) {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        data.writeInt32(keyLength);
        data.write(key, keyLength);
        remote()->transact(SET_ENTITLEMENT_KEY, data, &reply);

        return reply.readInt32();
    }

    virtual status_t setEntitlementControlMessage(
            const void *msg, size_t msgLength) {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        data.writeInt32(msgLength);
        data.write(msg, msgLength);
        remote()->transact(SET_ECM, data, &reply);

        return reply.readInt32();
    }

    virtual ssize_t decryptVideo(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataOffset) {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        if (iv == NULL) {
            if (ivLength > 0) {
                return -EINVAL;
            }

            data.writeInt32(-1);
        } else {
            data.writeInt32(ivLength);
            data.write(iv, ivLength);
        }

        data.writeInt32(srcDataSize);
        data.write(srcData, srcDataSize);

        data.writeIntPtr((intptr_t)dstData);
        data.writeInt32(dstDataOffset);

        remote()->transact(DECRYPT_VIDEO, data, &reply);

        return reply.readInt32();
    }

    virtual ssize_t decryptAudio(
            const void *iv, size_t ivLength,
            const void *srcData, size_t srcDataSize,
            void *dstData, size_t dstDataSize) {
        Parcel data, reply;
        data.writeInterfaceToken(ICrypto::getInterfaceDescriptor());
        if (iv == NULL) {
            if (ivLength > 0) {
                return -EINVAL;
            }

            data.writeInt32(-1);
        } else {
            data.writeInt32(ivLength);
            data.write(iv, ivLength);
        }

        data.writeInt32(srcDataSize);
        data.write(srcData, srcDataSize);
        data.writeInt32(dstDataSize);

        remote()->transact(DECRYPT_AUDIO, data, &reply);

        ssize_t res = reply.readInt32();

        if (res <= 0) {
            return res;
        }

        reply.read(dstData, res);

        return res;
    }

private:
    DISALLOW_EVIL_CONSTRUCTORS(BpCrypto);
};

IMPLEMENT_META_INTERFACE(Crypto, "android.hardware.ICrypto");

////////////////////////////////////////////////////////////////////////////////

status_t BnCrypto::onTransact(
    uint32_t code, const Parcel &data, Parcel *reply, uint32_t flags) {
    switch (code) {
        case INITIALIZE:
        {
            CHECK_INTERFACE(ICrypto, data, reply);
            reply->writeInt32(initialize());

            return OK;
        }

        case TERMINATE:
        {
            CHECK_INTERFACE(ICrypto, data, reply);
            reply->writeInt32(terminate());

            return OK;
        }

        case SET_ENTITLEMENT_KEY:
        {
            CHECK_INTERFACE(ICrypto, data, reply);

            size_t keyLength = data.readInt32();
            void *key = malloc(keyLength);
            data.read(key, keyLength);

            reply->writeInt32(setEntitlementKey(key, keyLength));

            free(key);
            key = NULL;

            return OK;
        }

        case SET_ECM:
        {
            CHECK_INTERFACE(ICrypto, data, reply);

            size_t msgLength = data.readInt32();
            void *msg = malloc(msgLength);
            data.read(msg, msgLength);

            reply->writeInt32(setEntitlementControlMessage(msg, msgLength));

            free(msg);
            msg = NULL;

            return OK;
        }

        case DECRYPT_VIDEO:
        {
            CHECK_INTERFACE(ICrypto, data, reply);

            void *iv = NULL;

            int32_t ivLength = data.readInt32();
            if (ivLength >= 0) {
                iv = malloc(ivLength);
                data.read(iv, ivLength);
            }

            size_t srcDataSize = data.readInt32();
            void *srcData = malloc(srcDataSize);
            data.read(srcData, srcDataSize);

            void *dstData = (void *)data.readIntPtr();
            size_t dstDataOffset = data.readInt32();

            reply->writeInt32(
                    decryptVideo(
                        iv,
                        ivLength < 0 ? 0 : ivLength,
                        srcData,
                        srcDataSize,
                        dstData,
                        dstDataOffset));

            free(srcData);
            srcData = NULL;

            if (iv != NULL) {
                free(iv);
                iv = NULL;
            }

            return OK;
        }

        case DECRYPT_AUDIO:
        {
            CHECK_INTERFACE(ICrypto, data, reply);

            void *iv = NULL;

            int32_t ivLength = data.readInt32();
            if (ivLength >= 0) {
                iv = malloc(ivLength);
                data.read(iv, ivLength);
            }

            size_t srcDataSize = data.readInt32();
            void *srcData = malloc(srcDataSize);
            data.read(srcData, srcDataSize);

            size_t dstDataSize = data.readInt32();
            void *dstData = malloc(dstDataSize);

            ssize_t res =
                decryptAudio(
                        iv,
                        ivLength < 0 ? 0 : ivLength,
                        srcData,
                        srcDataSize,
                        dstData,
                        dstDataSize);

            reply->writeInt32(res);

            if (res > 0) {
                reply->write(dstData, res);
            }

            free(dstData);
            dstData = NULL;

            free(srcData);
            srcData = NULL;

            if (iv != NULL) {
                free(iv);
                iv = NULL;
            }

            return OK;
        }

        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

}  // namespace android

