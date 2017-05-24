/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#undef LOG_NDEBUG
#undef LOG_TAG
#define LOG_NDEBUG 0
#define LOG_TAG "ContextHubService"

#include <inttypes.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/endian.h>

#include <chrono>
#include <mutex>
#include <queue>
#include <unordered_map>
#include <utility>

#include <android-base/macros.h>
#include <android/hardware/contexthub/1.0/IContexthub.h>
#include <cutils/log.h>

#include "core_jni_helpers.h"
#include "JNIHelp.h"

using android::hardware::contexthub::V1_0::AsyncEventType;
using android::hardware::contexthub::V1_0::ContextHub;
using android::hardware::contexthub::V1_0::ContextHubMsg;
using android::hardware::contexthub::V1_0::HubAppInfo;
using android::hardware::contexthub::V1_0::IContexthub;
using android::hardware::contexthub::V1_0::IContexthubCallback;
using android::hardware::contexthub::V1_0::NanoAppBinary;
using android::hardware::contexthub::V1_0::Result;
using android::hardware::contexthub::V1_0::TransactionResult;

using android::hardware::Return;

using std::chrono::steady_clock;

// If a transaction takes longer than this, we'll allow it to be
// canceled by a new transaction.  Note we do _not_ automatically
// cancel a transaction after this much time.  We can have a
// legal transaction which takes longer than this amount of time,
// as long as no other new transactions are attempted after this
// time has expired.
constexpr auto kMinTransactionCancelTime = std::chrono::seconds(29);

namespace android {

constexpr uint32_t kNanoAppBinaryHeaderVersion = 1;

// Important: this header is explicitly defined as little endian byte order, and
// therefore may not match host endianness
struct NanoAppBinaryHeader {
    uint32_t headerVersion;        // 0x1 for this version
    uint32_t magic;                // "NANO" (see NANOAPP_MAGIC in context_hub.h)
    uint64_t appId;                // App Id, contains vendor id
    uint32_t appVersion;           // Version of the app
    uint32_t flags;                // Signed, encrypted
    uint64_t hwHubType;            // Which hub type is this compiled for
    uint8_t targetChreApiMajorVersion; // Which CHRE API version this is compiled for
    uint8_t targetChreApiMinorVersion;
    uint8_t reserved[6];
} __attribute__((packed));

enum HubMessageType {
    CONTEXT_HUB_APPS_ENABLE  = 1, // Enables loaded nano-app(s)
    CONTEXT_HUB_APPS_DISABLE = 2, // Disables loaded nano-app(s)
    CONTEXT_HUB_LOAD_APP     = 3, // Load a supplied app
    CONTEXT_HUB_UNLOAD_APP   = 4, // Unload a specified app
    CONTEXT_HUB_QUERY_APPS   = 5, // Query for app(s) info on hub
    CONTEXT_HUB_QUERY_MEMORY = 6, // Query for memory info
    CONTEXT_HUB_OS_REBOOT    = 7, // Request to reboot context HUB OS
};

constexpr jint OS_APP_ID = -1;
constexpr jint INVALID_APP_ID = -2;

constexpr jint MIN_APP_ID = 1;
constexpr jint MAX_APP_ID = 128;

constexpr size_t MSG_HEADER_SIZE = 4;
constexpr size_t HEADER_FIELD_MSG_TYPE = 0;
constexpr size_t HEADER_FIELD_MSG_VERSION = 1;
constexpr size_t HEADER_FIELD_HUB_HANDLE = 2;
constexpr size_t HEADER_FIELD_APP_INSTANCE = 3;

constexpr size_t HEADER_FIELD_LOAD_APP_ID_LO = MSG_HEADER_SIZE;
constexpr size_t HEADER_FIELD_LOAD_APP_ID_HI = MSG_HEADER_SIZE + 1;
constexpr size_t MSG_HEADER_SIZE_LOAD_APP = MSG_HEADER_SIZE + 2;

jint getAppInstanceForAppId(uint64_t app_id);
int onMessageReceipt(const uint32_t *header,
                     size_t headerLen,
                     const char *msg,
                     size_t msgLen);
void onHubReset(uint32_t hubId);
void queryHubForApps(uint32_t hubId);
void passOnOsResponse(uint32_t hubHandle,
                      uint32_t msgType,
                      TransactionResult result,
                      const int8_t *additionalData,
                      size_t additionalDataLen);

bool closeLoadTxn(bool success, jint *appInstanceHandle);
void closeUnloadTxn(bool success);
int handleQueryAppsResponse(const std::vector<HubAppInfo> apps,
                               uint32_t hubHandle);

struct JniInfo {
    JavaVM *vm;
    jclass contextHubInfoClass;
    jclass contextHubServiceClass;
    jclass memoryRegionsClass;

    jobject jContextHubService;

    jmethodID msgReceiptCallBack;

    jmethodID contextHubInfoCtor;
    jmethodID contextHubInfoSetId;
    jmethodID contextHubInfoSetName;
    jmethodID contextHubInfoSetVendor;
    jmethodID contextHubInfoSetToolchain;
    jmethodID contextHubInfoSetPlatformVersion;
    jmethodID contextHubInfoSetStaticSwVersion;
    jmethodID contextHubInfoSetToolchainVersion;
    jmethodID contextHubInfoSetPeakMips;
    jmethodID contextHubInfoSetStoppedPowerDrawMw;
    jmethodID contextHubInfoSetSleepPowerDrawMw;
    jmethodID contextHubInfoSetPeakPowerDrawMw;
    jmethodID contextHubInfoSetSupportedSensors;
    jmethodID contextHubInfoSetMemoryRegions;
    jmethodID contextHubInfoSetMaxPacketLenBytes;

    jmethodID contextHubServiceMsgReceiptCallback;
    jmethodID contextHubServiceAddAppInstance;
    jmethodID contextHubServiceDeleteAppInstance;
};



class TxnManager {
public:
    TxnManager() {
        mData = nullptr;
        mIsPending = false;
    }

    ~TxnManager() {
        closeTxn();
    }

    int addTxn(HubMessageType txnIdentifier, void *txnData) {
        std::lock_guard<std::mutex>lock(mLock);
        if (mIsPending) {
            ALOGW("Transaction already found pending when trying to add a new one.");
            return -1;
        }
        mIsPending = true;
        mFirstTimeTxnCanBeCanceled = steady_clock::now() + kMinTransactionCancelTime;
        mData = txnData;
        mIdentifier = txnIdentifier;

        return 0;
    }

    int closeTxn() {
        std::lock_guard<std::mutex>lock(mLock);
        closeTxnUnlocked();
        return 0;
    }

    bool isTxnPending() {
        std::lock_guard<std::mutex>lock(mLock);
        return mIsPending;
    }

    void closeAnyStaleTxns() {
        std::lock_guard<std::mutex>lock(mLock);
        if (mIsPending && steady_clock::now() >= mFirstTimeTxnCanBeCanceled) {
            ALOGW("Stale transaction canceled");
            closeTxnUnlocked();
        }
    }

    int fetchTxnData(HubMessageType *id, void **data) {
        if (id == nullptr || data == nullptr) {
            ALOGW("Null Params isNull{id, data} {%d, %d}",
                  id == nullptr ? 1 : 0,
                  data == nullptr ? 1 : 0);
            return -1;
        }

        std::lock_guard<std::mutex>lock(mLock);
        if (!mIsPending) {
            ALOGW("No Transactions pending");
            return -1;
        }

        *id = mIdentifier;
        *data = mData;
        return 0;
    }

 private:
    bool mIsPending;            // Is a transaction pending
    std::mutex mLock;           // mutex for manager
    HubMessageType mIdentifier; // What are we doing
    void *mData;                // Details
    steady_clock::time_point mFirstTimeTxnCanBeCanceled;

    // Only call this if you hold the lock.
    void closeTxnUnlocked() {
        mIsPending = false;
        free(mData);
        mData = nullptr;
    }
};


struct ContextHubServiceCallback : IContexthubCallback {
    uint32_t mContextHubId;

    ContextHubServiceCallback(uint32_t hubId) {
        mContextHubId = hubId;
    }

    virtual Return<void> handleClientMsg(const ContextHubMsg &msg) {
        jint appHandle = getAppInstanceForAppId(msg.appName);
        if (appHandle < 0) {
            ALOGE("Filtering out message due to invalid App Instance.");
        } else {
            uint32_t msgHeader[MSG_HEADER_SIZE] = {};
            msgHeader[HEADER_FIELD_MSG_TYPE] = msg.msgType;
            msgHeader[HEADER_FIELD_HUB_HANDLE] = mContextHubId;
            msgHeader[HEADER_FIELD_APP_INSTANCE] = appHandle;
            onMessageReceipt(msgHeader,
                             MSG_HEADER_SIZE,
                             reinterpret_cast<const char *>(msg.msg.data()),
                             msg.msg.size());
        }

        return android::hardware::Void();
    }

    virtual Return<void> handleHubEvent(AsyncEventType evt) {
        if (evt == AsyncEventType::RESTARTED) {
            ALOGW("Context Hub handle %d restarted", mContextHubId);
            onHubReset(mContextHubId);
        } else {
            ALOGW("Cannot handle event %u from hub %d", evt, mContextHubId);
        }

        return android::hardware::Void();
    }

    virtual Return<void> handleTxnResult(uint32_t txnId,
                                         TransactionResult result) {
        ALOGI("Handle transaction result , hubId %" PRIu32 ", txnId %" PRIu32 ", result %" PRIu32,
              mContextHubId,
              txnId,
              result);

        switch(txnId) {
            case CONTEXT_HUB_APPS_ENABLE:
            case CONTEXT_HUB_APPS_DISABLE:
                passOnOsResponse(mContextHubId, txnId, result, nullptr, 0);
                break;

            case CONTEXT_HUB_UNLOAD_APP:
                closeUnloadTxn(result == TransactionResult::SUCCESS);
                passOnOsResponse(mContextHubId, txnId, result, nullptr, 0);
                break;

            case CONTEXT_HUB_LOAD_APP:
                {
                    jint appInstanceHandle = INVALID_APP_ID;
                    bool appRunningOnHub = (result == TransactionResult::SUCCESS);
                    if (!(closeLoadTxn(appRunningOnHub, &appInstanceHandle))) {
                        if (appRunningOnHub) {
                            // Now we're in an odd situation.  Our nanoapp
                            // is up and running on the Context Hub.  However,
                            // something went wrong in our Service code so that
                            // we're not able to properly track this nanoapp
                            // in our Service code.  If we tell the Java layer
                            // things are good, it's a lie because the handle
                            // we give them will fail when used with the Service.
                            // If we tell the Java layer this failed, it's kind
                            // of a lie as well, since this nanoapp is running.
                            //
                            // We leave a more robust fix for later, and for
                            // now just tell the user things have failed.
                            //
                            // TODO(b/30835981): Make this situation better.
                            result = TransactionResult::FAILURE;
                        }
                    }

                    passOnOsResponse(mContextHubId,
                                     txnId,
                                     result,
                                     reinterpret_cast<int8_t *>(&appInstanceHandle),
                                     sizeof(appInstanceHandle));
                    break;
                }

            default:
                ALOGI("unrecognized transction id %" PRIu32, txnId);
                break;
        }
        return android::hardware::Void();
    }

    virtual Return<void> handleAppsInfo(
            const android::hardware::hidl_vec<HubAppInfo>& apps) {
        TransactionResult result = TransactionResult::SUCCESS;
        handleQueryAppsResponse(apps,mContextHubId);
        passOnOsResponse(mContextHubId, CONTEXT_HUB_QUERY_APPS, result, nullptr, 0);
        return android::hardware::Void();
    }

    virtual Return<void> handleAppAbort(uint64_t appId, uint32_t abortCode) {
        ALOGI("Handle app aport called from %" PRIx64 " with abort code %" PRIu32,
            appId,
            abortCode);

        // TODO: Plumb this to the clients interested in this app
        return android::hardware::Void();
    }

    void setContextHubId(uint32_t id) {
        mContextHubId = id;
    }

    uint32_t getContextHubId() {
        return(mContextHubId);
    }
};

struct AppInstanceInfo {
    HubAppInfo appInfo;          // returned from the HAL
    uint64_t truncName;          // Possibly truncated name for logging
    uint32_t hubHandle;          // Id of the hub this app is on
    jint instanceId;             // system wide unique instance id - assigned
};

struct ContextHubInfo {
    int numHubs;
    Vector<ContextHub> hubs;
    sp<IContexthub> contextHub;
};

struct ContextHubServiceDb {
    int initialized;
    ContextHubInfo hubInfo;
    JniInfo jniInfo;
    std::queue<jint> freeIds;
    std::unordered_map<jint, AppInstanceInfo> appInstances;
    TxnManager txnManager;
    std::vector<ContextHubServiceCallback *> regCallBacks;
};

ContextHubServiceDb db;

bool getHubIdForHubHandle(int hubHandle, uint32_t *hubId) {
    if (hubHandle < 0 || hubHandle >= db.hubInfo.numHubs || hubId == nullptr) {
        return false;
    } else {
        *hubId = db.hubInfo.hubs[hubHandle].hubId;
        return true;
    }
}

int getHubHandleForAppInstance(jint id) {
    if (!db.appInstances.count(id)) {
        ALOGD("%s: Cannot find app for app instance %" PRId32,
              __FUNCTION__,
              id);
        return -1;
    }

    return db.appInstances[id].hubHandle;
}

jint getAppInstanceForAppId(uint64_t app_id) {
    auto end = db.appInstances.end();
    for (auto current = db.appInstances.begin(); current != end; ++current) {
        if (current->second.appInfo.appId == app_id) {
            return current->first;
        }
    }
    ALOGD("Cannot find app for app id %" PRIu64 ".", app_id);
    return -1;
}

uint64_t getAppIdForAppInstance(jint id) {
    if (!db.appInstances.count(id)) {
        return INVALID_APP_ID;
    }
    return db.appInstances[id].appInfo.appId;
}

void queryHubForApps(uint32_t hubId) {
    Result r = db.hubInfo.contextHub->queryApps(hubId);
    ALOGD("Sent query for apps to hub %" PRIu32 " with result %" PRIu32, hubId, r);
}

void sendQueryForApps() {
    for (int i = 0; i < db.hubInfo.numHubs; i++ ) {
        queryHubForApps(db.hubInfo.hubs[i].hubId);
    }
}

int returnId(jint id) {
    // Note : This method is not thread safe.
    // id returned is guaranteed to be in use
    if (id >= 0) {
        db.freeIds.push(id);
        return 0;
    }

    return -1;
}

jint generateId() {
    // Note : This method is not thread safe.
    jint retVal = -1;

    if (!db.freeIds.empty()) {
        retVal = db.freeIds.front();
        db.freeIds.pop();
    }

    return retVal;
}

jint addAppInstance(const HubAppInfo *appInfo, uint32_t hubHandle,
        jint appInstanceHandle, JNIEnv *env) {
    // Not checking if the apps are indeed distinct
    AppInstanceInfo entry;
    assert(appInfo);


    entry.appInfo = *appInfo;

    entry.instanceId = appInstanceHandle;
    entry.truncName = appInfo->appId;
    entry.hubHandle = hubHandle;
    db.appInstances[appInstanceHandle] = entry;
    // Finally - let the service know of this app instance, to populate
    // the Java cache.
    env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceAddAppInstance,
                       hubHandle, entry.instanceId,
                       entry.truncName,
                       entry.appInfo.version);

    const char *action = (db.appInstances.count(appInstanceHandle) == 0) ? "Added" : "Updated";
    ALOGI("%s App 0x%" PRIx64 " on hub Handle %" PRId32
          " as appInstance %" PRId32, action, entry.truncName,
          entry.hubHandle, appInstanceHandle);

    return appInstanceHandle;
}

int deleteAppInstance(jint id, JNIEnv *env) {
    bool fullyDeleted = true;

    if (db.appInstances.count(id)) {
        db.appInstances.erase(id);
    } else {
        ALOGW("Cannot delete App id (%" PRId32 ") from the JNI C++ cache", id);
        fullyDeleted = false;
    }
    returnId(id);

    if ((env == nullptr) ||
        (env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceDeleteAppInstance,
                       id) != 0)) {
        ALOGW("Cannot delete App id (%" PRId32 ") from Java cache", id);
        fullyDeleted = false;
    }

    if (fullyDeleted) {
        ALOGI("Deleted App id : %" PRId32, id);
        return 0;
    }
    return -1;
}

int startLoadAppTxn(uint64_t appId, int hubHandle) {
    AppInstanceInfo *txnInfo = new AppInstanceInfo();
    jint instanceId = generateId();

    if (!txnInfo || instanceId < 0) {
        returnId(instanceId);
        delete txnInfo;
        return -1;
    }

    txnInfo->truncName = appId;
    txnInfo->hubHandle = hubHandle;
    txnInfo->instanceId = instanceId;

    txnInfo->appInfo.appId = appId;
    txnInfo->appInfo.version = -1; // Awaited

    if (db.txnManager.addTxn(CONTEXT_HUB_LOAD_APP, txnInfo) != 0) {
        returnId(instanceId);
        delete txnInfo;
        return -1;
    }

    return 0;
}

int startUnloadAppTxn(jint appInstanceHandle) {
    jint *txnData = new(jint);
    if (!txnData) {
        ALOGW("Cannot allocate memory to start unload transaction");
        return -1;
    }

    *txnData = appInstanceHandle;

    if (db.txnManager.addTxn(CONTEXT_HUB_UNLOAD_APP, txnData) != 0) {
        delete txnData;
        ALOGW("Cannot start transaction to unload app");
        return -1;
    }

    return 0;
}

void getHubsCb(const ::android::hardware::hidl_vec<ContextHub>& hubs)  {
    for (size_t i = 0; i < hubs.size(); i++) {
        db.hubInfo.hubs.push_back(hubs[i]);
    }
}

void initContextHubService() {
    db.hubInfo.numHubs = 0;

    db.hubInfo.contextHub = IContexthub::getService();

    if (db.hubInfo.contextHub == nullptr) {
        ALOGE("Could not load context hub hal");
    } else {
        ALOGI("Loaded context hub hal, isRemote %s", db.hubInfo.contextHub->isRemote() ? "TRUE" : "FALSE");
    }

    // Prep for storing app info
    for (jint i = MIN_APP_ID; i <= MAX_APP_ID; i++) {
        db.freeIds.push(i);
    }

    if (db.hubInfo.contextHub != nullptr) {
        std::function<void(const ::android::hardware::hidl_vec<ContextHub>& hubs)> f = getHubsCb;
        if(!db.hubInfo.contextHub->getHubs(f).isOk()) {
            ALOGW("GetHubs Failed! transport error.");
            return;
        };

        int retNumHubs = db.hubInfo.hubs.size();
        ALOGD("ContextHubModule returned %d hubs ", retNumHubs);
        db.hubInfo.numHubs = retNumHubs;

        for (int i = 0; i < db.hubInfo.numHubs; i++) {
            ALOGI("Subscribing to hubHandle %d", i);

            ContextHubServiceCallback *callBackPtr =
                new ContextHubServiceCallback(db.hubInfo.hubs[i].hubId);
            db.hubInfo.contextHub->registerCallback(db.hubInfo.hubs[i].hubId,
                                                    callBackPtr);
            db.regCallBacks.push_back(callBackPtr);
        }

        sendQueryForApps();

    } else {
        ALOGW("No Context Hub Module present");
    }
}

void onHubReset(uint32_t hubId) {
    TransactionResult result = TransactionResult::SUCCESS;
    db.txnManager.closeTxn();
    // TODO : Expose this through an api
    passOnOsResponse(hubId, CONTEXT_HUB_OS_REBOOT, result, nullptr, 0);
    queryHubForApps(hubId);
}

int onMessageReceipt(const uint32_t *header,
                     size_t headerLen,
                     const char *msg,
                     size_t msgLen) {
    JNIEnv *env;

    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
      return -1;
    }

    jbyteArray jmsg = env->NewByteArray(msgLen);
    if (jmsg == nullptr) {
        ALOGW("Can't allocate %zu byte array", msgLen);
        return -1;
    }
    jintArray jheader = env->NewIntArray(headerLen);
    if (jheader == nullptr) {
        env->DeleteLocalRef(jmsg);
        ALOGW("Can't allocate %zu int array", headerLen);
        return -1;
    }

    env->SetByteArrayRegion(jmsg, 0, msgLen, reinterpret_cast<const jbyte *>(msg));
    env->SetIntArrayRegion(jheader, 0, headerLen, reinterpret_cast<const jint *>(header));

    int ret = (env->CallIntMethod(db.jniInfo.jContextHubService,
                                  db.jniInfo.contextHubServiceMsgReceiptCallback,
                                  jheader,
                                  jmsg) != 0);
    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(jheader);

    return ret;
}

int handleQueryAppsResponse(const std::vector<HubAppInfo> apps,
                               uint32_t hubHandle) {
    JNIEnv *env;
    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return -1;
    }

    int numApps = apps.size();

    // We use this information to sync our JNI and Java caches of nanoapp info.
    // We want to accomplish two things here:
    // 1) Remove entries from our caches which are stale, and pertained to
    //    apps no longer running on Context Hub.
    // 2) Populate our caches with the latest information of all these apps.

    // We make a couple of assumptions here:
    // A) The JNI and Java caches are in sync with each other (this isn't
    //    necessarily true; any failure of a single call into Java land to
    //    update its cache will leave that cache in a bad state.  For NYC,
    //    we're willing to tolerate this for now).
    // B) The total number of apps is relatively small, so horribly inefficent
    //    algorithms aren't too painful.
    // C) We're going to call this relatively infrequently, so its inefficency
    //    isn't a big impact.


    // (1).  Looking for stale cache entries.  Yes, this is O(N^2).  See
    // assumption (B).  Per assumption (A), it is sufficient to iterate
    // over just the JNI cache.
    auto end = db.appInstances.end();
    for (auto current = db.appInstances.begin(); current != end; ) {
        AppInstanceInfo cacheEntry = current->second;
        // We perform our iteration here because if we call
        // delete_app_instance() below, it will erase() this entry.
        current++;
        bool entryIsStale = true;
        for (int i = 0; i < numApps; i++) {
            if (apps[i].appId == cacheEntry.appInfo.appId) {
                // We found a match; this entry is current.
                entryIsStale = false;
                break;
            }
        }

        if (entryIsStale) {
            deleteAppInstance(cacheEntry.instanceId, env);
        }
    }

    // (2).  Update our caches with the latest.
    for (int i = 0; i < numApps; i++) {
        // We will only have one instance of the app
        // TODO : Change this logic once we support multiple instances of the same app
        jint appInstance = getAppInstanceForAppId(apps[i].appId);
        if (appInstance == -1) {
            // This is a previously unknown app, let's allocate an "id" for it.
            appInstance = generateId();
        }
        addAppInstance(&apps[i], hubHandle, appInstance, env);
    }
    return 0;
}

// TODO(b/30807327): Do not use raw bytes for additional data.  Use the
//     JNI interfaces for the appropriate types.
void passOnOsResponse(uint32_t hubHandle,
                      uint32_t msgType,
                      TransactionResult result,
                      const int8_t *additionalData,
                      size_t additionalDataLen) {
    JNIEnv *env;

    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        ALOGW("Cannot latch to JNI env, dropping OS response %" PRIu32,
              msgType);
        return;
    }

    uint32_t header[MSG_HEADER_SIZE];
    memset(header, 0, sizeof(header));

    if (!additionalData) {
        additionalDataLen = 0; // clamp
    }
    int msgLen = 1 + additionalDataLen;

    int8_t *msg = new int8_t[msgLen];

    if (!msg) {
        ALOGW("Unexpected : Ran out of memory, cannot send response");
        return;
    }

    header[HEADER_FIELD_MSG_TYPE] = msgType;
    header[HEADER_FIELD_MSG_VERSION] = 0;
    header[HEADER_FIELD_HUB_HANDLE] = hubHandle;
    header[HEADER_FIELD_APP_INSTANCE] = OS_APP_ID;

    // Due to API constraints, at the moment we can't change the fact that
    // we're changing our 4-byte response to a 1-byte value.  But we can prevent
    // the possible change in sign (and thus meaning) that would happen from
    // a naive cast.  Further, we can log when we're losing part of the value.
    // TODO(b/30918279): Don't truncate this result.
    int8_t truncatedResult;
    truncatedResult = static_cast<int8_t>(result);
    msg[0] = truncatedResult;

    if (additionalData) {
        memcpy(&msg[1], additionalData, additionalDataLen);
    }

    jbyteArray jmsg = env->NewByteArray(msgLen);
    jintArray jheader = env->NewIntArray(arraysize(header));

    env->SetByteArrayRegion(jmsg, 0, msgLen, reinterpret_cast<jbyte *>(msg));
    env->SetIntArrayRegion(jheader, 0, arraysize(header), reinterpret_cast<jint *>(header));

    ALOGI("Passing msg type %" PRIu32 " from app %" PRIu32 " from hub %" PRIu32,
          header[HEADER_FIELD_MSG_TYPE],
          header[HEADER_FIELD_APP_INSTANCE],
          header[HEADER_FIELD_HUB_HANDLE]);

    env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceMsgReceiptCallback,
                       jheader,
                       jmsg);

    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(jheader);

    delete[] msg;
}

void closeUnloadTxn(bool success) {
    void *txnData = nullptr;
    HubMessageType txnId;

    if (success && db.txnManager.fetchTxnData(&txnId, &txnData) == 0 &&
        txnId == CONTEXT_HUB_UNLOAD_APP) {
        JNIEnv *env;
        if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGW("Could not attach to JVM !");
            env = nullptr;
        }
        jint handle = *reinterpret_cast<jint *>(txnData);
        deleteAppInstance(handle, env);
    } else {
        ALOGW("Could not unload the app successfully ! success %d, txnData %p",
              success,
              txnData);
    }

    db.txnManager.closeTxn();
}

bool closeLoadTxn(bool success, jint *appInstanceHandle) {
    void *txnData;
    HubMessageType txnId;

    if (success && db.txnManager.fetchTxnData(&txnId, &txnData) == 0 &&
        txnId == CONTEXT_HUB_LOAD_APP) {
        AppInstanceInfo *info = static_cast<AppInstanceInfo *>(txnData);
        *appInstanceHandle = info->instanceId;

        JNIEnv *env;
        if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            addAppInstance(&info->appInfo, info->hubHandle, info->instanceId, env);
        } else {
            ALOGW("Could not attach to JVM !");
            success = false;
        }
        // While we just called addAppInstance above, our info->appInfo was
        // incomplete (for example, the 'version' is hardcoded to -1).  So we
        // trigger an additional query to the CHRE, so we'll be able to get
        // all the app "info", and have our JNI and Java caches with the
        // full information.
        sendQueryForApps();
    } else {
        ALOGW("Could not load the app successfully ! Unexpected failure");
        *appInstanceHandle = INVALID_APP_ID;
        success = false;
    }

    db.txnManager.closeTxn();
    return success;
}

int initJni(JNIEnv *env, jobject instance) {
    if (env->GetJavaVM(&db.jniInfo.vm) != JNI_OK) {
        return -1;
    }

    db.jniInfo.jContextHubService = env->NewGlobalRef(instance);

    db.jniInfo.contextHubInfoClass =
            env->FindClass("android/hardware/location/ContextHubInfo");
    db.jniInfo.contextHubServiceClass =
            env->FindClass("com/android/server/location/ContextHubService");

    db.jniInfo.memoryRegionsClass =
            env->FindClass("android/hardware/location/MemoryRegion");

    db.jniInfo.contextHubInfoCtor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "<init>", "()V");
    db.jniInfo.contextHubInfoSetId =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setId", "(I)V");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName", "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetVendor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setVendor",
                             "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetToolchain =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setToolchain",
                             "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetPlatformVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setPlatformVersion",
                             "(I)V");
    db.jniInfo.contextHubInfoSetStaticSwVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setStaticSwVersion",
                             "(I)V");
    db.jniInfo.contextHubInfoSetToolchainVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setToolchainVersion",
                             "(I)V");
    db.jniInfo.contextHubInfoSetPeakMips =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setPeakMips",
                             "(F)V");
    db.jniInfo.contextHubInfoSetStoppedPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setStoppedPowerDrawMw",
                             "(F)V");
    db.jniInfo.contextHubInfoSetSleepPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setSleepPowerDrawMw",
                             "(F)V");
    db.jniInfo.contextHubInfoSetPeakPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setPeakPowerDrawMw",
                             "(F)V");
    db.jniInfo.contextHubInfoSetSupportedSensors =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setSupportedSensors",
                             "([I)V");
    db.jniInfo.contextHubInfoSetMemoryRegions =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setMemoryRegions",
                             "([Landroid/hardware/location/MemoryRegion;)V");
    db.jniInfo.contextHubInfoSetMaxPacketLenBytes =
             env->GetMethodID(db.jniInfo.contextHubInfoClass,
                              "setMaxPacketLenBytes",
                              "(I)V");
    db.jniInfo.contextHubServiceMsgReceiptCallback =
            env->GetMethodID(db.jniInfo.contextHubServiceClass,
                             "onMessageReceipt",
                             "([I[B)I");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                             "setName",
                             "(Ljava/lang/String;)V");
    db.jniInfo.contextHubServiceAddAppInstance =
                 env->GetMethodID(db.jniInfo.contextHubServiceClass,
                                  "addAppInstance",
                                  "(IIJI)I");
    db.jniInfo.contextHubServiceDeleteAppInstance =
                 env->GetMethodID(db.jniInfo.contextHubServiceClass,
                                  "deleteAppInstance",
                                  "(I)I");

    return 0;
}

jobject constructJContextHubInfo(JNIEnv *env, const ContextHub &hub) {
    jstring jstrBuf;
    jintArray jintBuf;
    jobjectArray jmemBuf;

    jobject jHub = env->NewObject(db.jniInfo.contextHubInfoClass,
                                  db.jniInfo.contextHubInfoCtor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetId, hub.hubId);

    jstrBuf = env->NewStringUTF(hub.name.c_str());
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetName, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub.vendor.c_str());
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetVendor, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub.toolchain.c_str());
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchain, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPlatformVersion, hub.platformVersion);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchainVersion, hub.toolchainVersion);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakMips, hub.peakMips);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetStoppedPowerDrawMw,
                        hub.stoppedPowerDrawMw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSleepPowerDrawMw,
                        hub.sleepPowerDrawMw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakPowerDrawMw,
                        hub.peakPowerDrawMw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMaxPacketLenBytes,
                        hub.maxSupportedMsgLen);


    jintBuf = env->NewIntArray(hub.connectedSensors.size());
    int *connectedSensors = new int[hub.connectedSensors.size()];

    if (!connectedSensors) {
      ALOGW("Cannot allocate memory! Unexpected");
      assert(false);
    } else {
      for (unsigned int i = 0; i < hub.connectedSensors.size(); i++) {
        // TODO :: Populate connected sensors.
        //connectedSensors[i] = hub.connectedSensors[i].sensorType;
        connectedSensors[i] = 0;
      }
    }

    env->SetIntArrayRegion(jintBuf, 0, hub.connectedSensors.size(),
                           connectedSensors);

    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSupportedSensors, jintBuf);
    env->DeleteLocalRef(jintBuf);

    // We are not getting the memory regions from the CH Hal - change this when it is available
    jmemBuf = env->NewObjectArray(0, db.jniInfo.memoryRegionsClass, nullptr);
    // Note the zero size above. We do not need to set any elements
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMemoryRegions, jmemBuf);
    env->DeleteLocalRef(jmemBuf);


    delete[] connectedSensors;
    return jHub;
}

jobjectArray nativeInitialize(JNIEnv *env, jobject instance) {
    jobject hub;
    jobjectArray retArray;

    if (initJni(env, instance) < 0) {
        return nullptr;
    }

    initContextHubService();

    if (db.hubInfo.numHubs > 1) {
        ALOGW("Clamping the number of hubs to 1");
        db.hubInfo.numHubs = 1;
    }

    retArray = env->NewObjectArray(db.hubInfo.numHubs, db.jniInfo.contextHubInfoClass, nullptr);

    for(int i = 0; i < db.hubInfo.numHubs; i++) {
        hub = constructJContextHubInfo(env, db.hubInfo.hubs[i]);
        env->SetObjectArrayElement(retArray, i, hub);
    }

    return retArray;
}

Result sendLoadNanoAppRequest(uint32_t hubId,
                              jbyte *data,
                              size_t dataBufferLength) {
    auto header = reinterpret_cast<const NanoAppBinaryHeader *>(data);
    Result result;

    if (dataBufferLength < sizeof(NanoAppBinaryHeader)) {
        ALOGE("Got short NanoApp, length %zu", dataBufferLength);
        result = Result::BAD_PARAMS;
    } else if (header->headerVersion != htole32(kNanoAppBinaryHeaderVersion)) {
        ALOGE("Got unexpected NanoApp header version %" PRIu32,
              letoh32(header->headerVersion));
        result = Result::BAD_PARAMS;
    } else {
        NanoAppBinary nanoapp;

        // Data from the common nanoapp header goes into explicit fields
        nanoapp.appId      = letoh64(header->appId);
        nanoapp.appVersion = letoh32(header->appVersion);
        nanoapp.flags      = letoh32(header->flags);
        nanoapp.targetChreApiMajorVersion = header->targetChreApiMajorVersion;
        nanoapp.targetChreApiMinorVersion = header->targetChreApiMinorVersion;

        // Everything past the header goes in customBinary
        auto dataBytes = reinterpret_cast<const uint8_t *>(data);
        std::vector<uint8_t> customBinary(
            dataBytes + sizeof(NanoAppBinaryHeader),
            dataBytes + dataBufferLength);
        nanoapp.customBinary = std::move(customBinary);

        ALOGW("Calling Load NanoApp on hub %d", hubId);
        result = db.hubInfo.contextHub->loadNanoApp(hubId,
                                                    nanoapp,
                                                    CONTEXT_HUB_LOAD_APP);
    }

    return result;
}

jint nativeSendMessage(JNIEnv *env,
                       jobject instance,
                       jintArray header_,
                       jbyteArray data_) {
    // With the new binderized HAL definition, this function can be made much simpler.
    // All the magic can be removed. This is not however needed for the default implementation
    // TODO :: Change the JNI interface to conform to the new HAL interface and clean up this
    // function
    jint retVal = -1; // Default to failure

    jint *header = env->GetIntArrayElements(header_, 0);
    size_t numHeaderElements = env->GetArrayLength(header_);
    jbyte *data = env->GetByteArrayElements(data_, 0);
    size_t dataBufferLength = env->GetArrayLength(data_);

    if (numHeaderElements < MSG_HEADER_SIZE) {
        ALOGW("Malformed header len");
        return -1;
    }

    jint appInstanceHandle = header[HEADER_FIELD_APP_INSTANCE];
    uint32_t msgType = header[HEADER_FIELD_MSG_TYPE];
    int hubHandle = -1;
    uint64_t appId;

    if (msgType == CONTEXT_HUB_UNLOAD_APP) {
        hubHandle = getHubHandleForAppInstance(appInstanceHandle);
    } else if (msgType == CONTEXT_HUB_LOAD_APP) {
        if (numHeaderElements < MSG_HEADER_SIZE_LOAD_APP) {
            return -1;
        }
        uint64_t appIdLo = header[HEADER_FIELD_LOAD_APP_ID_LO];
        uint64_t appIdHi = header[HEADER_FIELD_LOAD_APP_ID_HI];
        appId = appIdHi << 32 | appIdLo;

        hubHandle = header[HEADER_FIELD_HUB_HANDLE];
    } else {
        hubHandle = header[HEADER_FIELD_HUB_HANDLE];
    }

    uint32_t hubId = -1;
    if (!getHubIdForHubHandle(hubHandle, &hubId)) {
        ALOGD("Invalid hub Handle %d", hubHandle);
        return -1;
    }

    if (msgType == CONTEXT_HUB_LOAD_APP ||
        msgType == CONTEXT_HUB_UNLOAD_APP) {

        db.txnManager.closeAnyStaleTxns();

        if (db.txnManager.isTxnPending()) {
            // TODO : There is a race conditio
            ALOGW("Cannot load or unload app while a transaction is pending !");
            return -1;
        } else if (msgType == CONTEXT_HUB_LOAD_APP) {
            if (startLoadAppTxn(appId, hubHandle) != 0) {
                ALOGW("Cannot Start Load Transaction");
                return -1;
            }
        } else if (msgType == CONTEXT_HUB_UNLOAD_APP) {
            if (startUnloadAppTxn(appInstanceHandle) != 0) {
                ALOGW("Cannot Start UnLoad Transaction");
                return -1;
            }
        }
    }

    Result result;

    if (msgType == CONTEXT_HUB_UNLOAD_APP) {
        ALOGW("Calling UnLoad NanoApp for app %" PRIx64 " on hub %" PRIu32,
              db.appInstances[appInstanceHandle].appInfo.appId,
              hubId);
        result = db.hubInfo.contextHub->unloadNanoApp(
                hubId, db.appInstances[appInstanceHandle].appInfo.appId, CONTEXT_HUB_UNLOAD_APP);
    } else {
        if (appInstanceHandle == OS_APP_ID) {
            if (msgType == CONTEXT_HUB_LOAD_APP) {
                result = sendLoadNanoAppRequest(hubId, data, dataBufferLength);
            } else if (msgType == CONTEXT_HUB_QUERY_APPS) {
                result = db.hubInfo.contextHub->queryApps(hubId);
            } else {
                ALOGD("Dropping OS addresses message of type - %" PRIu32, msgType);
                result = Result::BAD_PARAMS;
            }
        } else {
            appId = getAppIdForAppInstance(appInstanceHandle);
            if (appId == static_cast<uint64_t>(INVALID_APP_ID)) {
                ALOGD("Cannot find application instance %d", appInstanceHandle);
                result = Result::BAD_PARAMS;
            } else if (hubHandle != getHubHandleForAppInstance(appInstanceHandle)) {
                ALOGE("Given hubHandle (%d) doesn't match expected for app instance (%d)",
                      hubHandle,
                      getHubHandleForAppInstance(appInstanceHandle));
                result = Result::BAD_PARAMS;
            } else {
                ContextHubMsg msg;
                msg.appName = appId;
                msg.msgType = msgType;
                msg.msg.setToExternal((unsigned char *)data, dataBufferLength);

                ALOGW("Sending msg of type %" PRIu32 " len %zu to app %" PRIx64 " on hub %" PRIu32,
                       msgType,
                       dataBufferLength,
                       appId,
                       hubId);
                result = db.hubInfo.contextHub->sendMessageToHub(hubId, msg);
            }
        }
    }

    if (result != Result::OK) {
        ALOGD("Send Message failure - %d", retVal);
        if (msgType == CONTEXT_HUB_LOAD_APP) {
            jint ignored;
            closeLoadTxn(false, &ignored);
        } else if (msgType == CONTEXT_HUB_UNLOAD_APP) {
            closeUnloadTxn(false);
        }
    } else {
        retVal = 0;
    }

    env->ReleaseIntArrayElements(header_, header, 0);
    env->ReleaseByteArrayElements(data_, data, 0);

    return retVal;
}

//--------------------------------------------------------------------------------------------------
//
const JNINativeMethod gContextHubServiceMethods[] = {
    {"nativeInitialize",
            "()[Landroid/hardware/location/ContextHubInfo;",
            reinterpret_cast<void*>(nativeInitialize)},
    {"nativeSendMessage",
            "([I[B)I",
            reinterpret_cast<void*>(nativeSendMessage)}
};

int register_android_server_location_ContextHubService(JNIEnv *env)
{
    RegisterMethodsOrDie(env, "com/android/server/location/ContextHubService",
            gContextHubServiceMethods, NELEM(gContextHubServiceMethods));

    return 0;
}

}//namespace android
