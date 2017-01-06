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

#include "context_hub.h"

#define LOG_NDEBUG 0
#define LOG_TAG "ContextHubService"

#include <inttypes.h>
#include <jni.h>
#include <mutex>
#include <string.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

// TOOD: On master, alphabetize these and move <mutex> into this
//     grouping.
#include <chrono>
#include <unordered_map>
#include <queue>

#include <android-base/macros.h>
#include <cutils/log.h>

#include "JNIHelp.h"
#include "core_jni_helpers.h"

static constexpr jint OS_APP_ID = -1;
static constexpr jint INVALID_APP_ID = -2;
static constexpr uint64_t ALL_APPS = UINT64_C(0xFFFFFFFFFFFFFFFF);

static constexpr jint MIN_APP_ID = 1;
static constexpr jint MAX_APP_ID = 128;

static constexpr size_t MSG_HEADER_SIZE = 4;
static constexpr size_t HEADER_FIELD_MSG_TYPE = 0;
static constexpr size_t HEADER_FIELD_MSG_VERSION = 1;
static constexpr size_t HEADER_FIELD_HUB_HANDLE = 2;
static constexpr size_t HEADER_FIELD_APP_INSTANCE = 3;

static constexpr size_t HEADER_FIELD_LOAD_APP_ID_LO = MSG_HEADER_SIZE;
static constexpr size_t HEADER_FIELD_LOAD_APP_ID_HI = MSG_HEADER_SIZE + 1;
static constexpr size_t MSG_HEADER_SIZE_LOAD_APP = MSG_HEADER_SIZE + 2;

// Monotonically increasing clock we use to determine if we can cancel
// a transaction.
using std::chrono::steady_clock;

namespace android {

namespace {

/*
 * Finds the length of a statically-sized array using template trickery that
 * also prevents it from being applied to the wrong type.
 */
template <typename T, size_t N>
constexpr size_t array_length(T (&)[N]) { return N; }

struct jniInfo_s {
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

struct context_hub_info_s {
    uint32_t *cookies;
    int numHubs;
    const struct context_hub_t *hubs;
    struct context_hub_module_t *contextHubModule;
};

struct app_instance_info_s {
    uint64_t truncName;          // Possibly truncated name for logging
    uint32_t hubHandle;          // Id of the hub this app is on
    jint instanceId;             // system wide unique instance id - assigned
    struct hub_app_info appInfo; // returned from the HAL
};


// If a transaction takes longer than this, we'll allow it to be
// canceled by a new transaction.  Note we do _not_ automatically
// cancel a transaction after this much time.  We can have a
// legal transaction which takes longer than this amount of time,
// as long as no other new transactions are attempted after this
// time has expired.
// TODO(b/31105001): Establish a clean timing approach for all
// of our HAL interactions.
constexpr auto kMinTransactionCancelTime = std::chrono::seconds(29);

/*
 * TODO(ashutoshj): From original code review:
 *
 * So, I feel like we could possible do a better job of organizing this code,
 * and being more C++-y.  Consider something like this:
 * class TxnManager {
 *  public:
 *   TxnManager();
 *   ~TxnManager();
 *   int add(hub_message_e identifier, void *data);
 *   int close();
 *   bool isPending() const;
 *   int fetchData(hub_message_e *identifier, void **data) const;
 *
 *  private:
 *   bool mPending;
 *   mutable std::mutex mLock;
 *   hub_message_e mIdentifier;
 *   void *mData;
 * };
 *
 * And then, for example, we'd have things like:
 * TxnManager::TxnManager() : mPending(false), mLock(), mIdentifier(), mData(nullptr) {}
 * int TxnManager::add(hub_message_e identifier, void *data) {
 *    std::lock_guard<std::mutex> lock(mLock);
 *    mPending = true;
 *    mData = txnData;
 *    mIdentifier = txnIdentifier;
 *    return 0;
 *  }
 * And then calling code would look like:
 *    if (!db.txnManager.add(CONTEXT_HUB_LOAD_APP, txnInfo)) {
 *
 * This would make it clearer the nothing is manipulating any state within TxnManager
 * unsafely and outside of these couple of calls.
 */
struct txnManager_s {
    bool txnPending;              // Is a transaction pending
    std::mutex m;                 // mutex for manager
    hub_messages_e txnIdentifier; // What are we doing
    void *txnData;                // Details
    steady_clock::time_point firstTimeTxnCanBeCanceled;
};

struct contextHubServiceDb_s {
    int initialized;
    context_hub_info_s hubInfo;
    jniInfo_s jniInfo;
    std::queue<jint> freeIds;
    std::unordered_map<jint, app_instance_info_s> appInstances;
    txnManager_s txnManager;
};

}  // unnamed namespace

static contextHubServiceDb_s db;

static bool initTxnManager() {
    txnManager_s *mgr = &db.txnManager;

    mgr->txnData = nullptr;
    mgr->txnPending = false;
    return true;
}

static int addTxn(hub_messages_e txnIdentifier, void *txnData) {
    txnManager_s *mgr = &db.txnManager;

    std::lock_guard<std::mutex>lock(mgr->m);

    mgr->txnPending = true;
    mgr->firstTimeTxnCanBeCanceled = steady_clock::now() +
        kMinTransactionCancelTime;
    mgr->txnData = txnData;
    mgr->txnIdentifier = txnIdentifier;

    return 0;
}

// Only call this if you hold the db.txnManager.m lock.
static void closeTxnUnlocked() {
    txnManager_s *mgr = &db.txnManager;
    mgr->txnPending = false;
    free(mgr->txnData);
    mgr->txnData = nullptr;
}

static int closeTxn() {
    std::lock_guard<std::mutex>lock(db.txnManager.m);
    closeTxnUnlocked();
    return 0;
}

// If a transaction has been pending for longer than
// kMinTransactionCancelTime, this call will "cancel" that
// transaction and return that there are none pending.
static bool isTxnPending() {
    txnManager_s *mgr = &db.txnManager;
    std::lock_guard<std::mutex>lock(mgr->m);
    if (mgr->txnPending) {
        if (steady_clock::now() >= mgr->firstTimeTxnCanBeCanceled) {
            ALOGW("Transaction canceled");
            closeTxnUnlocked();
        }
    }
    return mgr->txnPending;
}

static int fetchTxnData(hub_messages_e *id, void **data) {
    txnManager_s *mgr = &db.txnManager;

    if (!id || !data) {
        ALOGW("Null params id %p, data %p", id, data);
        return -1;
    }

    std::lock_guard<std::mutex>lock(mgr->m);
    if (!mgr->txnPending) {
        ALOGW("No Transactions pending");
        return -1;
    }

    // else
    *id = mgr->txnIdentifier;
    *data = mgr->txnData;
    return 0;
}

int context_hub_callback(uint32_t hubId, const struct hub_message_t *msg,
                         void *cookie);

const context_hub_t *get_hub_info(int hubHandle) {
    if (hubHandle >= 0 && hubHandle < db.hubInfo.numHubs) {
        return &db.hubInfo.hubs[hubHandle];
    }
    return nullptr;
}

static int send_msg_to_hub(const hub_message_t *msg, int hubHandle) {
    const context_hub_t *info = get_hub_info(hubHandle);

    if (info) {
        return db.hubInfo.contextHubModule->send_message(info->hub_id, msg);
    } else {
        ALOGD("%s: Hub information is null for hubHandle %d", __FUNCTION__, hubHandle);
        return -1;
    }
}

static int set_os_app_as_destination(hub_message_t *msg, int hubHandle) {
    const context_hub_t *info = get_hub_info(hubHandle);

    if (info) {
        msg->app_name = info->os_app_name;
        return 0;
    } else {
        ALOGD("%s: Hub information is null for hubHandle %d", __FUNCTION__, hubHandle);
        return -1;
    }
}

static int get_hub_id_for_hub_handle(int hubHandle) {
    if (hubHandle < 0 || hubHandle >= db.hubInfo.numHubs) {
      return -1;
    } else {
      return db.hubInfo.hubs[hubHandle].hub_id;
    }
}

static int get_hub_handle_for_app_instance(jint id) {
    if (!db.appInstances.count(id)) {
        ALOGD("%s: Cannot find app for app instance %" PRId32,
              __FUNCTION__, id);
        return -1;
    }

    return db.appInstances[id].hubHandle;
}

static int get_hub_id_for_app_instance(jint id) {
    int hubHandle = get_hub_handle_for_app_instance(id);

    if (hubHandle < 0) {
        return -1;
    }

    return db.hubInfo.hubs[hubHandle].hub_id;
}

static jint get_app_instance_for_app_id(uint64_t app_id) {
    auto end = db.appInstances.end();
    for (auto current = db.appInstances.begin(); current != end; ++current) {
        if (current->second.appInfo.app_name.id == app_id) {
            return current->first;
        }
    }
    ALOGD("Cannot find app for app instance %" PRIu64 ".", app_id);
    return -1;
}

static int set_dest_app(hub_message_t *msg, jint id) {
    if (!db.appInstances.count(id)) {
        ALOGD("%s: Cannot find app for app instance %" PRId32,
              __FUNCTION__, id);
        return -1;
    }

    msg->app_name = db.appInstances[id].appInfo.app_name;
    return 0;
}

static void query_hub_for_apps(uint32_t hubHandle) {
    hub_message_t msg;
    query_apps_request_t queryMsg;

    // TODO(b/30835598): When we're able to tell which request our
    //     response matches, then we should allow this to be more
    //     targetted, instead of always being every app in the
    //     system.
    queryMsg.app_name.id = ALL_APPS;

    msg.message_type = CONTEXT_HUB_QUERY_APPS;
    msg.message_len  = sizeof(queryMsg);
    msg.message = &queryMsg;

    ALOGD("Sending query for apps to hub %" PRIu32, hubHandle);
    set_os_app_as_destination(&msg, hubHandle);
    if (send_msg_to_hub(&msg, hubHandle) != 0) {
        ALOGW("Could not query hub %" PRIu32 " for apps", hubHandle);
    }
}

static void sendQueryForApps() {
    for (int i = 0; i < db.hubInfo.numHubs; i++ ) {
        query_hub_for_apps(i);
    }
}

static int return_id(jint id) {
    // Note : This method is not thread safe.
    // id returned is guaranteed to be in use
    if (id >= 0) {
        db.freeIds.push(id);
        return 0;
    }

    return -1;
}

static jint generate_id() {
    // Note : This method is not thread safe.
    jint retVal = -1;

    if (!db.freeIds.empty()) {
        retVal = db.freeIds.front();
        db.freeIds.pop();
    }

    return retVal;
}


static jint add_app_instance(const hub_app_info *appInfo, uint32_t hubHandle,
        jint appInstanceHandle, JNIEnv *env) {
    // Not checking if the apps are indeed distinct
    app_instance_info_s entry;
    assert(appInfo);

    const char *action =
        (db.appInstances.count(appInstanceHandle) == 0) ? "Added" : "Updated";

    entry.appInfo = *appInfo;

    entry.instanceId = appInstanceHandle;
    entry.truncName = appInfo->app_name.id;
    entry.hubHandle = hubHandle;

    db.appInstances[appInstanceHandle] = entry;

    // Finally - let the service know of this app instance, to populate
    // the Java cache.
    env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceAddAppInstance,
                       hubHandle, entry.instanceId, entry.truncName,
                       entry.appInfo.version);

    ALOGI("%s App 0x%" PRIx64 " on hub Handle %" PRId32
          " as appInstance %" PRId32, action, entry.truncName,
          entry.hubHandle, appInstanceHandle);

    return appInstanceHandle;
}

int delete_app_instance(jint id, JNIEnv *env) {
    bool fullyDeleted = true;

    if (db.appInstances.count(id)) {
        db.appInstances.erase(id);
    } else {
        ALOGW("Cannot delete App id (%" PRId32 ") from the JNI C++ cache", id);
        fullyDeleted = false;
    }
    return_id(id);

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

static int startLoadAppTxn(uint64_t appId, int hubHandle) {
    app_instance_info_s *txnInfo = (app_instance_info_s *)malloc(sizeof(app_instance_info_s));
    jint instanceId = generate_id();

    if (!txnInfo || instanceId < 0) {
        return_id(instanceId);
        free(txnInfo);
        return -1;
    }

    txnInfo->truncName = appId;
    txnInfo->hubHandle = hubHandle;
    txnInfo->instanceId = instanceId;

    txnInfo->appInfo.app_name.id = appId;
    txnInfo->appInfo.num_mem_ranges = 0;
    txnInfo->appInfo.version = -1; // Awaited

    if (addTxn(CONTEXT_HUB_LOAD_APP, txnInfo) != 0) {
        return_id(instanceId);
        free(txnInfo);
        return -1;
    }

    return 0;
}

static int startUnloadAppTxn(jint appInstanceHandle) {
    jint *txnData = (jint *) malloc(sizeof(jint));
    if (!txnData) {
        ALOGW("Cannot allocate memory to start unload transaction");
        return -1;
    }

    *txnData = appInstanceHandle;

    if (addTxn(CONTEXT_HUB_UNLOAD_APP, txnData) != 0) {
        free(txnData);
        ALOGW("Cannot start transaction to unload app");
        return -1;
    }

    return 0;
}

static void initContextHubService() {
    int err = 0;
    db.hubInfo.hubs = nullptr;
    db.hubInfo.numHubs = 0;

    err = hw_get_module(CONTEXT_HUB_MODULE_ID,
                        (hw_module_t const**)(&db.hubInfo.contextHubModule));

    if (err) {
      ALOGE("** Could not load %s module : err %s", CONTEXT_HUB_MODULE_ID,
            strerror(-err));
    }

    // Prep for storing app info
    for (jint i = MIN_APP_ID; i <= MAX_APP_ID; i++) {
        db.freeIds.push(i);
    }

    initTxnManager();
    if (db.hubInfo.contextHubModule) {
        int retNumHubs = db.hubInfo.contextHubModule->get_hubs(db.hubInfo.contextHubModule,
                                                                 &db.hubInfo.hubs);
        ALOGD("ContextHubModule returned %d hubs ", retNumHubs);
        db.hubInfo.numHubs = retNumHubs;

        if (db.hubInfo.numHubs > 0) {
            db.hubInfo.numHubs = retNumHubs;
            db.hubInfo.cookies = (uint32_t *)malloc(sizeof(uint32_t) * db.hubInfo.numHubs);

            if (!db.hubInfo.cookies) {
                ALOGW("Ran out of memory allocating cookies, bailing");
                return;
            }

            for (int i = 0; i < db.hubInfo.numHubs; i++) {
                db.hubInfo.cookies[i] = db.hubInfo.hubs[i].hub_id;
                ALOGI("Subscribing to hubHandle %d with OS App name %" PRIu64, i, db.hubInfo.hubs[i].os_app_name.id);
                if (db.hubInfo.contextHubModule->subscribe_messages(db.hubInfo.hubs[i].hub_id,
                                                                    context_hub_callback,
                                                                    &db.hubInfo.cookies[i]) == 0) {
                }
            }
        }

        sendQueryForApps();
    } else {
        ALOGW("No Context Hub Module present");
    }
}

static int onMessageReceipt(uint32_t *header, size_t headerLen, char *msg, size_t msgLen) {
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

    env->SetByteArrayRegion(jmsg, 0, msgLen, (jbyte *)msg);
    env->SetIntArrayRegion(jheader, 0, headerLen, (jint *)header);

    int ret = (env->CallIntMethod(db.jniInfo.jContextHubService,
                          db.jniInfo.contextHubServiceMsgReceiptCallback,
                          jheader, jmsg) != 0);
    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(jheader);

    return ret;
}

int handle_query_apps_response(const uint8_t *msg, int msgLen,
                               uint32_t hubHandle) {
    JNIEnv *env;
    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return -1;
    }

    int numApps = msgLen / sizeof(hub_app_info);
    const hub_app_info *unalignedInfoAddr = (const hub_app_info*)msg;

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
        app_instance_info_s cache_entry = current->second;
        // We perform our iteration here because if we call
        // delete_app_instance() below, it will erase() this entry.
        current++;
        bool entryIsStale = true;
        for (int i = 0; i < numApps; i++) {
            // We use memcmp since this could be unaligned.
            if (memcmp(&unalignedInfoAddr[i].app_name.id,
                       &cache_entry.appInfo.app_name.id,
                       sizeof(cache_entry.appInfo.app_name.id)) == 0) {
                // We found a match; this entry is current.
                entryIsStale = false;
                break;
            }
        }
        if (entryIsStale) {
            delete_app_instance(cache_entry.instanceId, env);
        }
    }

    // (2).  Update our caches with the latest.
    for (int i = 0; i < numApps; i++) {
        hub_app_info query_info;
        memcpy(&query_info, &unalignedInfoAddr[i], sizeof(query_info));
        // We will only have one instance of the app
        // TODO : Change this logic once we support multiple instances of the same app
        jint appInstance = get_app_instance_for_app_id(query_info.app_name.id);
        if (appInstance == -1) {
            // This is a previously unknown app, let's allocate an "id" for it.
            appInstance = generate_id();
        }
        add_app_instance(&query_info, hubHandle, appInstance, env);
    }

    return 0;
}

// TODO(b/30807327): Do not use raw bytes for additional data.  Use the
//     JNI interfaces for the appropriate types.
static void passOnOsResponse(uint32_t hubHandle, uint32_t msgType,
                             status_response_t *rsp, int8_t *additionalData,
                             size_t additionalDataLen) {
    JNIEnv *env;

    if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        ALOGW("Cannot latch to JNI env, dropping OS response %" PRIu32, msgType);
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
    bool neededToTruncate;
    if (rsp->result < INT8_MIN) {
        neededToTruncate = true;
        truncatedResult = INT8_MIN;
    } else if (rsp->result > INT8_MAX) {
        neededToTruncate = true;
        truncatedResult = INT8_MAX;
    } else {
        neededToTruncate = false;
        // Since this value fits within an int8_t, this is a safe cast which
        // won't change the value or sign.
        truncatedResult = static_cast<int8_t>(rsp->result);
    }
    if (neededToTruncate) {
        ALOGW("Response from Context Hub truncated.  Value was %" PRId32
              ", but giving Java layer %" PRId8,
              rsp->result, (int)truncatedResult);
    }

    msg[0] = truncatedResult;

    if (additionalData) {
        memcpy(&msg[1], additionalData, additionalDataLen);
    }

    jbyteArray jmsg = env->NewByteArray(msgLen);
    jintArray jheader = env->NewIntArray(arraysize(header));

    env->SetByteArrayRegion(jmsg, 0, msgLen, (jbyte *)msg);
    env->SetIntArrayRegion(jheader, 0, arraysize(header), (jint *)header);

    ALOGI("Passing msg type %" PRIu32 " from app %" PRIu32 " from hub %" PRIu32,
          header[HEADER_FIELD_MSG_TYPE], header[HEADER_FIELD_APP_INSTANCE],
          header[HEADER_FIELD_HUB_HANDLE]);

    env->CallIntMethod(db.jniInfo.jContextHubService,
                       db.jniInfo.contextHubServiceMsgReceiptCallback,
                       jheader, jmsg);
    env->DeleteLocalRef(jmsg);
    env->DeleteLocalRef(jheader);

    delete[] msg;
}

void closeUnloadTxn(bool success) {
    void *txnData = nullptr;
    hub_messages_e txnId;

    if (success && fetchTxnData(&txnId, &txnData) == 0 &&
        txnId == CONTEXT_HUB_UNLOAD_APP) {
        JNIEnv *env;
        if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            ALOGW("Could not attach to JVM !");
            env = nullptr;
        }
        jint handle = *reinterpret_cast<jint *>(txnData);
        delete_app_instance(handle, env);
    } else {
        ALOGW("Could not unload the app successfully ! success %d, txnData %p", success, txnData);
    }

    closeTxn();
}

static bool closeLoadTxn(bool success, jint *appInstanceHandle) {
    void *txnData;
    hub_messages_e txnId;

    if (success && fetchTxnData(&txnId, &txnData) == 0 &&
        txnId == CONTEXT_HUB_LOAD_APP) {
        app_instance_info_s *info = (app_instance_info_s *)txnData;
        *appInstanceHandle = info->instanceId;

        JNIEnv *env;
        if ((db.jniInfo.vm)->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            add_app_instance(&info->appInfo, info->hubHandle, info->instanceId, env);
        } else {
            ALOGW("Could not attach to JVM !");
            success = false;
        }
        // While we just called add_app_instance above, our info->appInfo was
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

    closeTxn();
    return success;
}

static bool isValidOsStatus(const uint8_t *msg, size_t msgLen,
                            status_response_t *rsp) {
    // Workaround a bug in some HALs
    if (msgLen == 1) {
        rsp->result = msg[0];
        return true;
    }

    if (!msg || msgLen != sizeof(*rsp)) {
        ALOGW("Received invalid response %p of size %zu", msg, msgLen);
        return false;
    }

    memcpy(rsp, msg, sizeof(*rsp));

    // No sanity checks on return values
    return true;
}

static int handle_os_message(uint32_t msgType, uint32_t hubHandle,
                             const uint8_t *msg, int msgLen) {
    int retVal = -1;

    ALOGD("Rcd OS message from hubHandle %" PRIu32 " type %" PRIu32 " length %d",
          hubHandle, msgType, msgLen);

    struct status_response_t rsp;

    switch(msgType) {

      case CONTEXT_HUB_APPS_ENABLE:
      case CONTEXT_HUB_APPS_DISABLE:
      case CONTEXT_HUB_LOAD_APP:
      case CONTEXT_HUB_UNLOAD_APP:
          if (isValidOsStatus(msg, msgLen, &rsp)) {
              if (msgType == CONTEXT_HUB_LOAD_APP) {
                  jint appInstanceHandle = INVALID_APP_ID;
                  bool appRunningOnHub = (rsp.result == 0);
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
                          rsp.result = -1;
                      }
                  }
                  passOnOsResponse(hubHandle, msgType, &rsp, (int8_t *)(&appInstanceHandle),
                                   sizeof(appInstanceHandle));
              } else if (msgType == CONTEXT_HUB_UNLOAD_APP) {
                  closeUnloadTxn(rsp.result == 0);
                  passOnOsResponse(hubHandle, msgType, &rsp, nullptr, 0);
              } else {
                  passOnOsResponse(hubHandle, msgType, &rsp, nullptr, 0);
              }
              retVal = 0;
          }
          break;

      case CONTEXT_HUB_QUERY_APPS:
          rsp.result = 0;
          retVal = handle_query_apps_response(msg, msgLen, hubHandle);
          passOnOsResponse(hubHandle, msgType, &rsp, nullptr, 0);
          break;

      case CONTEXT_HUB_QUERY_MEMORY:
          // Deferring this use
          retVal = 0;
          break;

      case CONTEXT_HUB_OS_REBOOT:
          if (isValidOsStatus(msg, msgLen, &rsp)) {
              rsp.result = 0;
              ALOGW("Context Hub handle %d restarted", hubHandle);
              closeTxn();
              passOnOsResponse(hubHandle, msgType, &rsp, nullptr, 0);
              query_hub_for_apps(hubHandle);
              retVal = 0;
          }
          break;

      default:
          retVal = -1;
          break;
    }

    return retVal;
}

static bool sanity_check_cookie(void *cookie, uint32_t hub_id) {
    int *ptr = (int *)cookie;

    if (!ptr || *ptr >= db.hubInfo.numHubs) {
        return false;
    }

    if (db.hubInfo.hubs[*ptr].hub_id != hub_id) {
        return false;
    } else {
        return true;
    }
}


int context_hub_callback(uint32_t hubId,
                         const struct hub_message_t *msg,
                         void *cookie) {
    if (!msg) {
        ALOGW("NULL message");
        return -1;
    }
    if (!sanity_check_cookie(cookie, hubId)) {
        ALOGW("Incorrect cookie %" PRId32 " for cookie %p! Bailing",
              hubId, cookie);

        return -1;
    }


    uint32_t messageType = msg->message_type;
    uint32_t hubHandle = *(uint32_t*) cookie;

    if (messageType < CONTEXT_HUB_TYPE_PRIVATE_MSG_BASE) {
        handle_os_message(messageType, hubHandle, (uint8_t*) msg->message, msg->message_len);
    } else {
        jint appHandle = get_app_instance_for_app_id(msg->app_name.id);
        if (appHandle < 0) {
            ALOGE("Filtering out message due to invalid App Instance.");
        } else {
            uint32_t msgHeader[MSG_HEADER_SIZE] = {};
            msgHeader[HEADER_FIELD_MSG_TYPE] = messageType;
            msgHeader[HEADER_FIELD_HUB_HANDLE] = hubHandle;
            msgHeader[HEADER_FIELD_APP_INSTANCE] = appHandle;
            onMessageReceipt(msgHeader, MSG_HEADER_SIZE, (char*) msg->message, msg->message_len);
        }
    }

    return 0;
}

static int init_jni(JNIEnv *env, jobject instance) {

    if (env->GetJavaVM(&db.jniInfo.vm) != JNI_OK) {
        return -1;
    }

    db.jniInfo.jContextHubService = env->NewGlobalRef(instance);

    db.jniInfo.contextHubInfoClass =
            env->FindClass("android/hardware/location/ContextHubInfo");

    db.jniInfo.contextHubServiceClass =
            env->FindClass("android/hardware/location/ContextHubService");

    db.jniInfo.memoryRegionsClass =
            env->FindClass("android/hardware/location/MemoryRegion");

    db.jniInfo.contextHubInfoCtor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "<init>", "()V");
    db.jniInfo.contextHubInfoSetId =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setId", "(I)V");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName",
                                "(Ljava/lang/String;)V");

    db.jniInfo.contextHubInfoSetVendor =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setVendor", "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetToolchain =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setToolchain", "(Ljava/lang/String;)V");
    db.jniInfo.contextHubInfoSetPlatformVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPlatformVersion", "(I)V");
    db.jniInfo.contextHubInfoSetStaticSwVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setStaticSwVersion", "(I)V");
    db.jniInfo.contextHubInfoSetToolchainVersion =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setToolchainVersion", "(I)V");
    db.jniInfo.contextHubInfoSetPeakMips =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPeakMips", "(F)V");
    db.jniInfo.contextHubInfoSetStoppedPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setStoppedPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetSleepPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setSleepPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetPeakPowerDrawMw =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setPeakPowerDrawMw", "(F)V");
    db.jniInfo.contextHubInfoSetSupportedSensors =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setSupportedSensors", "([I)V");
    db.jniInfo.contextHubInfoSetMemoryRegions =
            env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setMemoryRegions", "([Landroid/hardware/location/MemoryRegion;)V");
    db.jniInfo.contextHubInfoSetMaxPacketLenBytes =
             env->GetMethodID(db.jniInfo.contextHubInfoClass,
                                "setMaxPacketLenBytes", "(I)V");


    db.jniInfo.contextHubServiceMsgReceiptCallback =
            env->GetMethodID(db.jniInfo.contextHubServiceClass, "onMessageReceipt",
                               "([I[B)I");
    db.jniInfo.contextHubInfoSetName =
            env->GetMethodID(db.jniInfo.contextHubInfoClass, "setName",
            "(Ljava/lang/String;)V");

    db.jniInfo.contextHubServiceAddAppInstance =
                 env->GetMethodID(db.jniInfo.contextHubServiceClass,
                                    "addAppInstance", "(IIJI)I");

    db.jniInfo.contextHubServiceDeleteAppInstance =
                 env->GetMethodID(db.jniInfo.contextHubServiceClass,
                                    "deleteAppInstance", "(I)I");

    return 0;
}

static jobject constructJContextHubInfo(JNIEnv *env, const struct context_hub_t *hub) {
    jstring jstrBuf;
    jintArray jintBuf;
    jobjectArray jmemBuf;

    jobject jHub = env->NewObject(db.jniInfo.contextHubInfoClass,
                                  db.jniInfo.contextHubInfoCtor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetId, hub->hub_id);

    jstrBuf = env->NewStringUTF(hub->name);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetName, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub->vendor);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetVendor, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    jstrBuf = env->NewStringUTF(hub->toolchain);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchain, jstrBuf);
    env->DeleteLocalRef(jstrBuf);

    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPlatformVersion, hub->platform_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetToolchainVersion, hub->toolchain_version);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakMips, hub->peak_mips);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetStoppedPowerDrawMw,
                        hub->stopped_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetSleepPowerDrawMw,
                        hub->sleep_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetPeakPowerDrawMw,
                        hub->peak_power_draw_mw);
    env->CallVoidMethod(jHub, db.jniInfo.contextHubInfoSetMaxPacketLenBytes,
                        hub->max_supported_msg_len);


    jintBuf = env->NewIntArray(hub->num_connected_sensors);
    int *connectedSensors = new int[hub->num_connected_sensors];

    if (!connectedSensors) {
      ALOGW("Cannot allocate memory! Unexpected");
      assert(false);
    } else {
      for (unsigned int i = 0; i < hub->num_connected_sensors; i++) {
        connectedSensors[i] = hub->connected_sensors[i].sensor_id;
      }
    }

    env->SetIntArrayRegion(jintBuf, 0, hub->num_connected_sensors,
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

static jobjectArray nativeInitialize(JNIEnv *env, jobject instance)
{
    jobject hub;
    jobjectArray retArray;

    if (init_jni(env, instance) < 0) {
        return nullptr;
    }

    initContextHubService();

    if (db.hubInfo.numHubs > 1) {
      ALOGW("Clamping the number of hubs to 1");
      db.hubInfo.numHubs = 1;
    }

    retArray = env->NewObjectArray(db.hubInfo.numHubs, db.jniInfo.contextHubInfoClass, nullptr);

    for(int i = 0; i < db.hubInfo.numHubs; i++) {
        hub = constructJContextHubInfo(env, &db.hubInfo.hubs[i]);
        env->SetObjectArrayElement(retArray, i, hub);
    }

    return retArray;
}

static jint nativeSendMessage(JNIEnv *env, jobject instance, jintArray header_,
                              jbyteArray data_) {
    jint retVal = -1; // Default to failure

    jint *header = env->GetIntArrayElements(header_, 0);
    unsigned int numHeaderElements = env->GetArrayLength(header_);
    jbyte *data = env->GetByteArrayElements(data_, 0);
    int dataBufferLength = env->GetArrayLength(data_);

    if (numHeaderElements < MSG_HEADER_SIZE) {
        ALOGW("Malformed header len");
        return -1;
    }

    uint32_t appInstanceHandle = header[HEADER_FIELD_APP_INSTANCE];
    uint32_t msgType = header[HEADER_FIELD_MSG_TYPE];
    int hubHandle = -1;
    int hubId;
    uint64_t appId;

    if (msgType == CONTEXT_HUB_UNLOAD_APP) {
        hubHandle = get_hub_handle_for_app_instance(appInstanceHandle);
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

    if (hubHandle < 0) {
        ALOGD("Invalid hub Handle %d", hubHandle);
        return -1;
    }

    if (msgType == CONTEXT_HUB_LOAD_APP ||
        msgType == CONTEXT_HUB_UNLOAD_APP) {

        if (isTxnPending()) {
            ALOGW("Cannot load or unload app while a transaction is pending !");
            return -1;
        }

        if (msgType == CONTEXT_HUB_LOAD_APP) {
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

    bool setAddressSuccess = false;
    hub_message_t msg;

    msg.message_type = msgType;

    if (msgType == CONTEXT_HUB_UNLOAD_APP) {
        msg.message_len = sizeof(db.appInstances[appInstanceHandle].appInfo.app_name);
        msg.message = &db.appInstances[appInstanceHandle].appInfo.app_name;
        setAddressSuccess = (set_os_app_as_destination(&msg, hubHandle) == 0);
        hubId = get_hub_id_for_hub_handle(hubHandle);
    } else {
        msg.message_len = dataBufferLength;
        msg.message = data;

        if (header[HEADER_FIELD_APP_INSTANCE] == OS_APP_ID) {
            setAddressSuccess = (set_os_app_as_destination(&msg, hubHandle) == 0);
            hubId = get_hub_id_for_hub_handle(hubHandle);
        } else {
            setAddressSuccess = (set_dest_app(&msg, header[HEADER_FIELD_APP_INSTANCE]) == 0);
            hubId = get_hub_id_for_app_instance(header[HEADER_FIELD_APP_INSTANCE]);
        }
    }

    if (setAddressSuccess && hubId >= 0) {
        ALOGD("Asking HAL to remove app");
        retVal = db.hubInfo.contextHubModule->send_message(hubId, &msg);
    } else {
      ALOGD("Could not find app instance %" PRId32 " on hubHandle %" PRId32
            ", setAddress %d",
            header[HEADER_FIELD_APP_INSTANCE],
            header[HEADER_FIELD_HUB_HANDLE],
            (int)setAddressSuccess);
    }

    if (retVal != 0) {
        ALOGD("Send Message failure - %d", retVal);
        if (msgType == CONTEXT_HUB_LOAD_APP) {
            jint ignored;
            closeLoadTxn(false, &ignored);
        } else if (msgType == CONTEXT_HUB_UNLOAD_APP) {
            closeUnloadTxn(false);
        }
    }

    env->ReleaseIntArrayElements(header_, header, 0);
    env->ReleaseByteArrayElements(data_, data, 0);

    return retVal;
}

//--------------------------------------------------------------------------------------------------
//
static const JNINativeMethod gContextHubServiceMethods[] = {
    {"nativeInitialize",
             "()[Landroid/hardware/location/ContextHubInfo;",
             (void*)nativeInitialize },
    {"nativeSendMessage",
            "([I[B)I",
            (void*)nativeSendMessage }
};

}//namespace android

using namespace android;

int register_android_hardware_location_ContextHubService(JNIEnv *env)
{
    RegisterMethodsOrDie(env, "android/hardware/location/ContextHubService",
            gContextHubServiceMethods, NELEM(gContextHubServiceMethods));

    return 0;
}
