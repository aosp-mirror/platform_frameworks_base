#define LOG_TAG "ProxyTesting"

#include <stdint.h>
#include <sys/types.h>
#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <utils/Errors.h>
#include "IProxyService.h"

#include <utils/Log.h>

#include <private/android_filesystem_config.h>

using namespace android;

String16 BpProxyService::resolveProxies(String16 host, String16 url) {
    String16 ret;
    return ret;
}

void BpProxyService::setPacFile(String16& scriptContents) {

}

void BpProxyService::startPacSystem() {

}
void BpProxyService::stopPacSystem() {

}

IMPLEMENT_META_INTERFACE(ProxyService, "com.android.net.IProxyService");

status_t BnProxyService::onTransact(
            uint32_t code, const Parcel& data,
            Parcel* reply, uint32_t flags) {
    int returnInt = 0;
    switch (code) {
    case RESOLVE_PROXIES:
    {
        CHECK_INTERFACE(IProxyService, data, reply);
        String16 host = data.readString16();
        String16 url = data.readString16();
        String16 response = resolveProxies(host, url);
        reply->writeNoException();
        reply->writeString16(response);
        return NO_ERROR;
    } break;
    case SET_PAC:
    {
        CHECK_INTERFACE(IProxyService, data, reply);
        if (notSystemUid()) {
            returnInt = 1;
        } else {
            String16 pacFile = data.readString16();
            setPacFile(pacFile);
        }
        reply->writeNoException();
        reply->writeInt32(returnInt);
        return NO_ERROR;
    } break;
    case START_PAC:
    {
        CHECK_INTERFACE(IProxyService, data, reply);
        if (notSystemUid()) {
            returnInt = 1;
        } else {
            startPacSystem();
        }
        reply->writeNoException();
        reply->writeInt32(returnInt);
        return NO_ERROR;
    } break;
    case STOP_PAC:
    {
        CHECK_INTERFACE(IProxyService, data, reply);
        if (notSystemUid()) {
            returnInt = 1;
        } else {
            stopPacSystem();
        }
        reply->writeNoException();
        reply->writeInt32(returnInt);
        return NO_ERROR;
    } break;
    default:
        return BBinder::onTransact(code, data, reply, flags);
    }
}

int BnProxyService::getCallingUid() {
    return IPCThreadState::self()->getCallingUid();
}

bool BnProxyService::notSystemUid() {
    return getCallingUid() != AID_SYSTEM;
}
