#ifndef PROXY_SERVICE_H
#define PROXY_SERVICE_H

#include <binder/IInterface.h>
#include "IProxyService.h"
#include "proxy_resolver_v8.h"

namespace android {

class ProxyService : public BnProxyService {
public:
    static void instantiate();

private:
    ProxyService();
    virtual ~ProxyService();

public:
    String16 resolveProxies(String16 host, String16 url);

    void setPacFile(String16& scriptContents);

    void startPacSystem();
    void stopPacSystem();

private:
    net::ProxyResolverV8* proxyResolver;
    bool hasSetScript;
};

}

#endif //PROXY_SERVICE_H
