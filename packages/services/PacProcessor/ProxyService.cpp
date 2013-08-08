#define LOG_TAG "ProxyService"
#include <utils/Log.h>

#include <errno.h>
#include <utils/threads.h>
#include <binder/IServiceManager.h>
#include <binder/IPCThreadState.h>
#include <sys/stat.h>
#include <proxy_resolver_v8.h>
#include <sstream>

#include "ProxyService.h"

using namespace net;

using namespace android;

class ProxyErrorLogger : public ProxyErrorListener {
protected:
    ~ProxyErrorLogger() {

    }
public:
    void AlertMessage(String16 message) {
        String8 str(message);
        ALOGD("Alert: %s", str.string());
    }
    void ErrorMessage(String16 message) {
        String8 str(message);
        ALOGE("Error: %s", str.string());
    }
};

void ProxyService::instantiate() {
    ALOGV("instantiate");
    defaultServiceManager()->addService(String16("com.android.net.IProxyService"),
            new ProxyService());
}

ProxyService::ProxyService() {
    hasSetScript = false;
}

ProxyService::~ProxyService() {
    stopPacSystem();
}

String16 ProxyService::resolveProxies(String16 host, String16 url) {
    ALOGV("resolve");
    String16 blankRet;
    if (proxyResolver != NULL) {
        if (hasSetScript) {
            String16 ret;
            if (proxyResolver->GetProxyForURL(url, host, &ret) != OK) {
                return blankRet;
            }
            return ret;
        } else {
            ALOGD("Unable to resolve PAC when no script is set!");
        }
    } else {
        ALOGE("Cannot parse while resolver not initialized!");
    }
    return blankRet;
}

void ProxyService::setPacFile(String16& scriptContents) {
    ALOGV("set");
    if (proxyResolver != NULL) {
        if (proxyResolver->SetPacScript(scriptContents) != OK) {
            ALOGD("Unable to initialize PAC - Resolving will not work");
        } else {
            hasSetScript = true;
        }
    } else {
        ALOGE("PAC script set while resolver not initialized!");
    }
}

void ProxyService::startPacSystem() {
    ALOGV("start");
    // Stop in case redundant start call
    stopPacSystem();

    proxyResolver = new ProxyResolverV8(ProxyResolverJSBindings::CreateDefault(),
            new ProxyErrorLogger());
    hasSetScript = false;
}

void ProxyService::stopPacSystem() {
    ALOGV("stop");
    if (proxyResolver != NULL) {
        delete proxyResolver;
        proxyResolver = NULL;
    }
}
