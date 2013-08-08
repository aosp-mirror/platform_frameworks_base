#ifndef IPROXY_SERVICE_H
#define IPROXY_SERVICE_H

#include <binder/IInterface.h>
#include <binder/IBinder.h>

namespace android {
class IProxyService : public IInterface {
public:
    /**
     * Keep up-to-date with
     * frameworks/base/packages/services/Proxy/com/android/net/IProxyService.aidl
     */
    enum {
        RESOLVE_PROXIES = IBinder::FIRST_CALL_TRANSACTION,
        SET_PAC,
        START_PAC,
        STOP_PAC,
    };
public:
    DECLARE_META_INTERFACE(ProxyService);

public:

    virtual String16 resolveProxies(String16 host, String16 url) = 0;

    virtual void setPacFile(String16& scriptContents) = 0;

    virtual void startPacSystem() = 0;
    virtual void stopPacSystem() = 0;
private:
};

class BpProxyService : public BpInterface<IProxyService> {
public:
    BpProxyService(const sp<IBinder>& impl) : BpInterface<IProxyService>(impl) {}

    virtual String16 resolveProxies(String16 host, String16 url);

    virtual void setPacFile(String16& scriptContents);

    virtual void startPacSystem();
    virtual void stopPacSystem();
};

class BnProxyService : public BnInterface<IProxyService> {
public:
    virtual status_t onTransact(
            uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags = 0);

private:
    int getCallingUid();

    bool notSystemUid();
};
}


#endif //IPROXY_SERVICE_H
