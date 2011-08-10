/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef SUPPORT_H_

#define SUPPORT_H_

#include <assert.h>

#include "net/base/net_log.h"
#include "net/url_request/url_request.h"
#include "net/url_request/url_request_context.h"
#include "net/base/android_network_library.h"
#include "net/base/io_buffer.h"

#include <utils/KeyedVector.h>
#include <utils/String8.h>

namespace android {

struct SfNetLog : public net::NetLog {
    SfNetLog();

    virtual void AddEntry(
            EventType type,
            const base::TimeTicks &time,
            const Source &source,
            EventPhase phase,
            EventParameters *params);

    virtual uint32 NextID();
    virtual LogLevel GetLogLevel() const;

private:
    uint32 mNextID;

    DISALLOW_EVIL_CONSTRUCTORS(SfNetLog);
};

struct SfRequestContext : public net::URLRequestContext {
    SfRequestContext();

    virtual const std::string &GetUserAgent(const GURL &url) const;

private:
    std::string mUserAgent;

    DISALLOW_EVIL_CONSTRUCTORS(SfRequestContext);
};

// This is required for https support, we don't really verify certificates,
// we accept anything...
struct SfNetworkLibrary : public net::AndroidNetworkLibrary {
    SfNetworkLibrary();

    virtual VerifyResult VerifyX509CertChain(
            const std::vector<std::string>& cert_chain,
            const std::string& hostname,
            const std::string& auth_type);

private:
    DISALLOW_EVIL_CONSTRUCTORS(SfNetworkLibrary);
};

struct ChromiumHTTPDataSource;

struct SfDelegate : public net::URLRequest::Delegate {
    SfDelegate();
    virtual ~SfDelegate();

    void initiateConnection(
            const char *uri,
            const KeyedVector<String8, String8> *headers,
            off64_t offset);

    void initiateDisconnect();
    void initiateRead(void *data, size_t size);

    void setOwner(ChromiumHTTPDataSource *mOwner);

    // Gets the UID of the calling process
    bool getUID(uid_t *uid) const;

    void setUID(uid_t uid);

    virtual void OnReceivedRedirect(
            net::URLRequest *request, const GURL &new_url, bool *defer_redirect);

    virtual void OnAuthRequired(
            net::URLRequest *request, net::AuthChallengeInfo *auth_info);

    virtual void OnCertificateRequested(
            net::URLRequest *request, net::SSLCertRequestInfo *cert_request_info);

    virtual void OnSSLCertificateError(
            net::URLRequest *request, int cert_error, net::X509Certificate *cert);

    virtual void OnGetCookies(net::URLRequest *request, bool blocked_by_policy);

    virtual void OnSetCookie(
            net::URLRequest *request,
            const std::string &cookie_line,
            const net::CookieOptions &options,
            bool blocked_by_policy);

    virtual void OnResponseStarted(net::URLRequest *request);

    virtual void OnReadCompleted(net::URLRequest *request, int bytes_read);

private:
    typedef Delegate inherited;

    ChromiumHTTPDataSource *mOwner;

    net::URLRequest *mURLRequest;
    scoped_refptr<net::IOBufferWithSize> mReadBuffer;

    size_t mNumBytesRead;
    size_t mNumBytesTotal;
    void *mDataDestination;

    bool mRangeRequested;
    bool mAtEOS;

    void readMore(net::URLRequest *request);

    static void OnInitiateConnectionWrapper(
            SfDelegate *me,
            GURL url,
            const KeyedVector<String8, String8> *headers,
            off64_t offset);

    static void OnInitiateDisconnectWrapper(SfDelegate *me);

    static void OnInitiateReadWrapper(
            SfDelegate *me, void *data, size_t size);

    void onInitiateConnection(
            const GURL &url,
            const KeyedVector<String8, String8> *headers,
            off64_t offset);

    void onInitiateDisconnect();
    void onInitiateRead(void *data, size_t size);

    DISALLOW_EVIL_CONSTRUCTORS(SfDelegate);
};

}  // namespace android

#endif  // SUPPORT_H_
