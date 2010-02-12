#define LOG_TAG "CameraServiceTest"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <surfaceflinger/ISurface.h>
#include <camera/Camera.h>
#include <camera/CameraParameters.h>
#include <ui/GraphicBuffer.h>
#include <camera/ICamera.h>
#include <camera/ICameraClient.h>
#include <camera/ICameraService.h>
#include <ui/Overlay.h>
#include <binder/IPCThreadState.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>
#include <utils/KeyedVector.h>
#include <utils/Log.h>
#include <utils/Vector.h>
#include <utils/threads.h>

using namespace android;

//
//  Assertion and Logging utilities
//
#define INFO(...) \
    do { \
        printf(__VA_ARGS__); \
        printf("\n"); \
        LOGD(__VA_ARGS__); \
    } while(0)

void assert_fail(const char *file, int line, const char *func, const char *expr) {
    INFO("assertion failed at file %s, line %d, function %s:",
            file, line, func);
    INFO("%s", expr);
    exit(1);
}

void assert_eq_fail(const char *file, int line, const char *func,
        const char *expr, int actual) {
    INFO("assertion failed at file %s, line %d, function %s:",
            file, line, func);
    INFO("(expected) %s != (actual) %d", expr, actual);
    exit(1);
}

#define ASSERT(e) \
    do { \
        if (!(e)) \
            assert_fail(__FILE__, __LINE__, __func__, #e); \
    } while(0)

#define ASSERT_EQ(expected, actual) \
    do { \
        int _x = (actual); \
        if (_x != (expected)) \
            assert_eq_fail(__FILE__, __LINE__, __func__, #expected, _x); \
    } while(0)

//
//  Holder service for pass objects between processes.
//
class IHolder : public IInterface {
protected:
    enum {
        HOLDER_PUT = IBinder::FIRST_CALL_TRANSACTION,
        HOLDER_GET,
        HOLDER_CLEAR
    };
public:
    DECLARE_META_INTERFACE(Holder);

    virtual void put(sp<IBinder> obj) = 0;
    virtual sp<IBinder> get() = 0;
    virtual void clear() = 0;
};

class BnHolder : public BnInterface<IHolder> {
    virtual status_t onTransact(uint32_t code,
                                const Parcel& data,
                                Parcel* reply,
                                uint32_t flags = 0);
};

class BpHolder : public BpInterface<IHolder> {
public:
    BpHolder(const sp<IBinder>& impl)
        : BpInterface<IHolder>(impl) {
    }

    virtual void put(sp<IBinder> obj) {
        Parcel data, reply;
        data.writeStrongBinder(obj);
        remote()->transact(HOLDER_PUT, data, &reply, IBinder::FLAG_ONEWAY);
    }

    virtual sp<IBinder> get() {
        Parcel data, reply;
        remote()->transact(HOLDER_GET, data, &reply);
        return reply.readStrongBinder();
    }

    virtual void clear() {
        Parcel data, reply;
        remote()->transact(HOLDER_CLEAR, data, &reply);
    }
};

IMPLEMENT_META_INTERFACE(Holder, "CameraServiceTest.Holder");

status_t BnHolder::onTransact(
    uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) {
    switch(code) {
        case HOLDER_PUT: {
            put(data.readStrongBinder());
            return NO_ERROR;
        } break;
        case HOLDER_GET: {
            reply->writeStrongBinder(get());
            return NO_ERROR;
        } break;
        case HOLDER_CLEAR: {
            clear();
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

class HolderService : public BnHolder {
    virtual void put(sp<IBinder> obj) {
        mObj = obj;
    }
    virtual sp<IBinder> get() {
        return mObj;
    }
    virtual void clear() {
        mObj.clear();
    }
private:
    sp<IBinder> mObj;
};

//
//  A mock CameraClient
//
class MCameraClient : public BnCameraClient {
public:
    virtual void notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2);
    virtual void dataCallback(int32_t msgType, const sp<IMemory>& data);
    virtual void dataCallbackTimestamp(nsecs_t timestamp,
            int32_t msgType, const sp<IMemory>& data) {}

    // new functions
    void clearStat();
    enum OP { EQ, GE, LE, GT, LT };
    void assertNotify(int32_t msgType, OP op, int count);
    void assertData(int32_t msgType, OP op, int count);
    void waitNotify(int32_t msgType, OP op, int count);
    void waitData(int32_t msgType, OP op, int count);
    void assertDataSize(int32_t msgType, OP op, int dataSize);

    void setReleaser(ICamera *releaser) {
        mReleaser = releaser;
    }
private:
    Mutex mLock;
    Condition mCond;
    DefaultKeyedVector<int32_t, int> mNotifyCount;
    DefaultKeyedVector<int32_t, int> mDataCount;
    DefaultKeyedVector<int32_t, int> mDataSize;
    bool test(OP op, int v1, int v2);

    ICamera *mReleaser;
};

void MCameraClient::clearStat() {
    Mutex::Autolock _l(mLock);
    mNotifyCount.clear();
    mDataCount.clear();
    mDataSize.clear();
}

bool MCameraClient::test(OP op, int v1, int v2) {
    switch (op) {
        case EQ: return v1 == v2;
        case GT: return v1 > v2;
        case LT: return v1 < v2;
        case GE: return v1 >= v2;
        case LE: return v1 <= v2;
        default: ASSERT(0); break;
    }
    return false;
}

void MCameraClient::assertNotify(int32_t msgType, OP op, int count) {
    Mutex::Autolock _l(mLock);
    int v = mNotifyCount.valueFor(msgType);
    ASSERT(test(op, v, count));
}

void MCameraClient::assertData(int32_t msgType, OP op, int count) {
    Mutex::Autolock _l(mLock);
    int v = mDataCount.valueFor(msgType);
    ASSERT(test(op, v, count));
}

void MCameraClient::assertDataSize(int32_t msgType, OP op, int dataSize) {
    Mutex::Autolock _l(mLock);
    int v = mDataSize.valueFor(msgType);
    ASSERT(test(op, v, dataSize));
}

void MCameraClient::notifyCallback(int32_t msgType, int32_t ext1, int32_t ext2) {
    INFO(__func__);
    Mutex::Autolock _l(mLock);
    ssize_t i = mNotifyCount.indexOfKey(msgType);
    if (i < 0) {
        mNotifyCount.add(msgType, 1);
    } else {
        ++mNotifyCount.editValueAt(i);
    }
    mCond.signal();
}

void MCameraClient::dataCallback(int32_t msgType, const sp<IMemory>& data) {
    INFO(__func__);
    int dataSize = data->size();
    INFO("data type = %d, size = %d", msgType, dataSize);
    Mutex::Autolock _l(mLock);
    ssize_t i = mDataCount.indexOfKey(msgType);
    if (i < 0) {
        mDataCount.add(msgType, 1);
        mDataSize.add(msgType, dataSize);
    } else {
        ++mDataCount.editValueAt(i);
        mDataSize.editValueAt(i) = dataSize;
    }
    mCond.signal();

    if (msgType == CAMERA_MSG_VIDEO_FRAME) {
        ASSERT(mReleaser != NULL);
        mReleaser->releaseRecordingFrame(data);
    }
}

void MCameraClient::waitNotify(int32_t msgType, OP op, int count) {
    INFO("waitNotify: %d, %d, %d", msgType, op, count);
    Mutex::Autolock _l(mLock);
    while (true) {
        int v = mNotifyCount.valueFor(msgType);
        if (test(op, v, count)) {
            break;
        }
        mCond.wait(mLock);
    }
}

void MCameraClient::waitData(int32_t msgType, OP op, int count) {
    INFO("waitData: %d, %d, %d", msgType, op, count);
    Mutex::Autolock _l(mLock);
    while (true) {
        int v = mDataCount.valueFor(msgType);
        if (test(op, v, count)) {
            break;
        }
        mCond.wait(mLock);
    }
}

//
//  A mock Surface
//
class MSurface : public BnSurface {
public:
    virtual status_t registerBuffers(const BufferHeap& buffers);
    virtual void postBuffer(ssize_t offset);
    virtual void unregisterBuffers();
    virtual sp<OverlayRef> createOverlay(
            uint32_t w, uint32_t h, int32_t format, int32_t orientation);
    virtual sp<GraphicBuffer> requestBuffer(int bufferIdx, int usage);

    // new functions
    void clearStat();
    void waitUntil(int c0, int c1, int c2);

private:
    // check callback count
    Condition mCond;
    Mutex mLock;
    int registerBuffersCount;
    int postBufferCount;
    int unregisterBuffersCount;
};

status_t MSurface::registerBuffers(const BufferHeap& buffers) {
    INFO(__func__);
    Mutex::Autolock _l(mLock);
    ++registerBuffersCount;
    mCond.signal();
    return NO_ERROR;
}

void MSurface::postBuffer(ssize_t offset) {
    // INFO(__func__);
    Mutex::Autolock _l(mLock);
    ++postBufferCount;
    mCond.signal();
}

void MSurface::unregisterBuffers() {
    INFO(__func__);
    Mutex::Autolock _l(mLock);
    ++unregisterBuffersCount;
    mCond.signal();
}

sp<GraphicBuffer> MSurface::requestBuffer(int bufferIdx, int usage) {
    INFO(__func__);
    return NULL;
}

void MSurface::clearStat() {
    Mutex::Autolock _l(mLock);
    registerBuffersCount = 0;
    postBufferCount = 0;
    unregisterBuffersCount = 0;
}

void MSurface::waitUntil(int c0, int c1, int c2) {
    INFO("waitUntil: %d %d %d", c0, c1, c2);
    Mutex::Autolock _l(mLock);
    while (true) {
        if (registerBuffersCount >= c0 &&
            postBufferCount >= c1 &&
            unregisterBuffersCount >= c2) {
            break;
        }
        mCond.wait(mLock);
    }
}

sp<OverlayRef> MSurface::createOverlay(uint32_t w, uint32_t h, int32_t format,
        int32_t orientation) {
    // We don't expect this to be called in current hardware.
    ASSERT(0);
    sp<OverlayRef> dummy;
    return dummy;
}

//
//  Utilities to use the Holder service
//
sp<IHolder> getHolder() {
    sp<IServiceManager> sm = defaultServiceManager();
    ASSERT(sm != 0);
    sp<IBinder> binder = sm->getService(String16("CameraServiceTest.Holder"));
    ASSERT(binder != 0);
    sp<IHolder> holder = interface_cast<IHolder>(binder);
    ASSERT(holder != 0);
    return holder;
}

void putTempObject(sp<IBinder> obj) {
    INFO(__func__);
    getHolder()->put(obj);
}

sp<IBinder> getTempObject() {
    INFO(__func__);
    return getHolder()->get();
}

void clearTempObject() {
    INFO(__func__);
    getHolder()->clear();
}

//
//  Get a Camera Service
//
sp<ICameraService> getCameraService() {
    sp<IServiceManager> sm = defaultServiceManager();
    ASSERT(sm != 0);
    sp<IBinder> binder = sm->getService(String16("media.camera"));
    ASSERT(binder != 0);
    sp<ICameraService> cs = interface_cast<ICameraService>(binder);
    ASSERT(cs != 0);
    return cs;
}

//
// Various Connect Tests
//
void testConnect() {
    INFO(__func__);
    sp<ICameraService> cs = getCameraService();
    sp<MCameraClient> cc = new MCameraClient();
    sp<ICamera> c = cs->connect(cc);
    ASSERT(c != 0);
    c->disconnect();
}

void testAllowConnectOnceOnly() {
    INFO(__func__);
    sp<ICameraService> cs = getCameraService();
    // Connect the first client.
    sp<MCameraClient> cc = new MCameraClient();
    sp<ICamera> c = cs->connect(cc);
    ASSERT(c != 0);
    // Same client -- ok.
    ASSERT(cs->connect(cc) != 0);
    // Different client -- not ok.
    sp<MCameraClient> cc2 = new MCameraClient();
    ASSERT(cs->connect(cc2) == 0);
    c->disconnect();
}

void testReconnectFailed() {
    INFO(__func__);
    sp<ICamera> c = interface_cast<ICamera>(getTempObject());
    sp<MCameraClient> cc2 = new MCameraClient();
    ASSERT(c->connect(cc2) != NO_ERROR);
}

void testReconnectSuccess() {
    INFO(__func__);
    sp<ICamera> c = interface_cast<ICamera>(getTempObject());
    sp<MCameraClient> cc = new MCameraClient();
    ASSERT(c->connect(cc) == NO_ERROR);
}

void testLockFailed() {
    INFO(__func__);
    sp<ICamera> c = interface_cast<ICamera>(getTempObject());
    ASSERT(c->lock() != NO_ERROR);
}

void testLockUnlockSuccess() {
    INFO(__func__);
    sp<ICamera> c = interface_cast<ICamera>(getTempObject());
    ASSERT(c->lock() == NO_ERROR);
    ASSERT(c->unlock() == NO_ERROR);
}

void testLockSuccess() {
    INFO(__func__);
    sp<ICamera> c = interface_cast<ICamera>(getTempObject());
    ASSERT(c->lock() == NO_ERROR);
}

//
// Run the connect tests in another process.
//
const char *gExecutable;

struct FunctionTableEntry {
    const char *name;
    void (*func)();
};

FunctionTableEntry function_table[] = {
#define ENTRY(x) {#x, &x}
    ENTRY(testReconnectFailed),
    ENTRY(testReconnectSuccess),
    ENTRY(testLockUnlockSuccess),
    ENTRY(testLockFailed),
    ENTRY(testLockSuccess),
#undef ENTRY
};

void runFunction(const char *tag) {
    INFO("runFunction: %s", tag);
    int entries = sizeof(function_table) / sizeof(function_table[0]);
    for (int i = 0; i < entries; i++) {
        if (strcmp(function_table[i].name, tag) == 0) {
            (*function_table[i].func)();
            return;
        }
    }
    ASSERT(0);
}

void runInAnotherProcess(const char *tag) {
    pid_t pid = fork();
    if (pid == 0) {
        execlp(gExecutable, gExecutable, tag, NULL);
        ASSERT(0);
    } else {
        int status;
        ASSERT_EQ(pid, wait(&status));
        ASSERT_EQ(0, status);
    }
}

void testReconnect() {
    INFO(__func__);
    sp<ICameraService> cs = getCameraService();
    sp<MCameraClient> cc = new MCameraClient();
    sp<ICamera> c = cs->connect(cc);
    ASSERT(c != 0);
    // Reconnect to the same client -- ok.
    ASSERT(c->connect(cc) == NO_ERROR);
    // Reconnect to a different client (but the same pid) -- ok.
    sp<MCameraClient> cc2 = new MCameraClient();
    ASSERT(c->connect(cc2) == NO_ERROR);
    c->disconnect();
    cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
}

void testLockUnlock() {
    sp<ICameraService> cs = getCameraService();
    sp<MCameraClient> cc = new MCameraClient();
    sp<ICamera> c = cs->connect(cc);
    ASSERT(c != 0);
    // We can lock as many times as we want.
    ASSERT(c->lock() == NO_ERROR);
    ASSERT(c->lock() == NO_ERROR);
    // Lock from a different process -- not ok.
    putTempObject(c->asBinder());
    runInAnotherProcess("testLockFailed");
    // Unlock then lock from a different process -- ok.
    ASSERT(c->unlock() == NO_ERROR);
    runInAnotherProcess("testLockUnlockSuccess");
    // Unlock then lock from a different process -- ok.
    runInAnotherProcess("testLockSuccess");
    c->disconnect();
    clearTempObject();
}

void testReconnectFromAnotherProcess() {
    INFO(__func__);

    sp<ICameraService> cs = getCameraService();
    sp<MCameraClient> cc = new MCameraClient();
    sp<ICamera> c = cs->connect(cc);
    ASSERT(c != 0);
    // Reconnect from a different process -- not ok.
    putTempObject(c->asBinder());
    runInAnotherProcess("testReconnectFailed");
    // Unlock then reconnect from a different process -- ok.
    ASSERT(c->unlock() == NO_ERROR);
    runInAnotherProcess("testReconnectSuccess");
    c->disconnect();
    clearTempObject();
}

// We need to flush the command buffer after the reference
// to ICamera is gone. The sleep is for the server to run
// the destructor for it.
static void flushCommands() {
    IPCThreadState::self()->flushCommands();
    usleep(200000);  // 200ms
}

// Run a test case
#define RUN(class_name) do { \
    { \
        INFO(#class_name); \
        class_name instance; \
        instance.run(); \
    } \
    flushCommands(); \
} while(0)

// Base test case after the the camera is connected.
class AfterConnect {
protected:
    sp<ICameraService> cs;
    sp<MCameraClient> cc;
    sp<ICamera> c;

    AfterConnect() {
        cs = getCameraService();
        cc = new MCameraClient();
        c = cs->connect(cc);
        ASSERT(c != 0);
    }

    ~AfterConnect() {
        c.clear();
        cc.clear();
        cs.clear();
    }
};

class TestSetPreviewDisplay : public AfterConnect {
public:
    void run() {
        sp<MSurface> surface = new MSurface();
        ASSERT(c->setPreviewDisplay(surface) == NO_ERROR);
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestStartPreview : public AfterConnect {
public:
    void run() {
        sp<MSurface> surface = new MSurface();
        ASSERT(c->setPreviewDisplay(surface) == NO_ERROR);

        ASSERT(c->startPreview() == NO_ERROR);
        ASSERT(c->previewEnabled() == true);

        surface->waitUntil(1, 10, 0); // needs 1 registerBuffers and 10 postBuffer
        surface->clearStat();

        c->disconnect();
        // TODO: CameraService crashes for this. Fix it.
#if 0
        sp<MSurface> another_surface = new MSurface();
        c->setPreviewDisplay(another_surface);  // just to make sure unregisterBuffers
                                                // is called.
        surface->waitUntil(0, 0, 1);  // needs unregisterBuffers
#endif
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestStartPreviewWithoutDisplay : AfterConnect {
public:
    void run() {
        ASSERT(c->startPreview() == NO_ERROR);
        ASSERT(c->previewEnabled() == true);
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

// Base test case after the the camera is connected and the preview is started.
class AfterStartPreview : public AfterConnect {
protected:
    sp<MSurface> surface;

    AfterStartPreview() {
        surface = new MSurface();
        ASSERT(c->setPreviewDisplay(surface) == NO_ERROR);
        ASSERT(c->startPreview() == NO_ERROR);
    }

    ~AfterStartPreview() {
        surface.clear();
    }
};

class TestAutoFocus : public AfterStartPreview {
public:
    void run() {
        cc->assertNotify(CAMERA_MSG_FOCUS, MCameraClient::EQ, 0);
        c->autoFocus();
        cc->waitNotify(CAMERA_MSG_FOCUS, MCameraClient::EQ, 1);
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestStopPreview : public AfterStartPreview {
public:
    void run() {
        ASSERT(c->previewEnabled() == true);
        c->stopPreview();
        ASSERT(c->previewEnabled() == false);
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestTakePicture: public AfterStartPreview {
public:
    void run() {
        ASSERT(c->takePicture() == NO_ERROR);
        cc->waitNotify(CAMERA_MSG_SHUTTER, MCameraClient::EQ, 1);
        cc->waitData(CAMERA_MSG_RAW_IMAGE, MCameraClient::EQ, 1);
        cc->waitData(CAMERA_MSG_COMPRESSED_IMAGE, MCameraClient::EQ, 1);
        c->stopPreview();
#if 1  // TODO: It crashes if we don't have this. Fix it.
        usleep(100000);
#endif
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestTakeMultiplePictures: public AfterStartPreview {
public:
    void run() {
        for (int i = 0; i < 10; i++) {
            cc->clearStat();
            ASSERT(c->takePicture() == NO_ERROR);
            cc->waitNotify(CAMERA_MSG_SHUTTER, MCameraClient::EQ, 1);
            cc->waitData(CAMERA_MSG_RAW_IMAGE, MCameraClient::EQ, 1);
            cc->waitData(CAMERA_MSG_COMPRESSED_IMAGE, MCameraClient::EQ, 1);
            usleep(100000);  // 100ms
        }
        c->disconnect();
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
    }
};

class TestGetParameters: public AfterStartPreview {
public:
    void run() {
        String8 param_str = c->getParameters();
        INFO(param_str);
    }
};

class TestPictureSize : public AfterStartPreview {
public:
    void checkOnePicture(int w, int h) {
        const float rate = 0.5;  // byte per pixel limit
        int pixels = w * h;

        CameraParameters param(c->getParameters());
        param.setPictureSize(w, h);
        c->setParameters(param.flatten());

        cc->clearStat();
        ASSERT(c->takePicture() == NO_ERROR);
        cc->waitData(CAMERA_MSG_RAW_IMAGE, MCameraClient::EQ, 1);
        cc->assertDataSize(CAMERA_MSG_RAW_IMAGE, MCameraClient::EQ, pixels*3/2);
        cc->waitData(CAMERA_MSG_COMPRESSED_IMAGE, MCameraClient::EQ, 1);
        cc->assertDataSize(CAMERA_MSG_COMPRESSED_IMAGE, MCameraClient::LT,
                int(pixels * rate));
        cc->assertDataSize(CAMERA_MSG_COMPRESSED_IMAGE, MCameraClient::GT, 0);
        cc->assertNotify(CAMERA_MSG_ERROR, MCameraClient::EQ, 0);
        usleep(100000);  // 100ms
    }

    void run() {
        checkOnePicture(2048, 1536);
        checkOnePicture(1600, 1200);
        checkOnePicture(1024, 768);
    }
};

class TestPreviewCallbackFlag : public AfterConnect {
public:
    void run() {
        sp<MSurface> surface = new MSurface();
        ASSERT(c->setPreviewDisplay(surface) == NO_ERROR);

        // Try all flag combinations.
        for (int v = 0; v < 8; v++) {
            cc->clearStat();
            c->setPreviewCallbackFlag(v);
            ASSERT(c->previewEnabled() == false);
            ASSERT(c->startPreview() == NO_ERROR);
            ASSERT(c->previewEnabled() == true);
            sleep(2);
            c->stopPreview();
            if ((v & FRAME_CALLBACK_FLAG_ENABLE_MASK) == 0) {
                cc->assertData(CAMERA_MSG_PREVIEW_FRAME, MCameraClient::EQ, 0);
            } else {
                if ((v & FRAME_CALLBACK_FLAG_ONE_SHOT_MASK) == 0) {
                    cc->assertData(CAMERA_MSG_PREVIEW_FRAME, MCameraClient::GE, 10);
                } else {
                    cc->assertData(CAMERA_MSG_PREVIEW_FRAME, MCameraClient::EQ, 1);
                }
            }
        }
    }
};

class TestRecording : public AfterConnect {
public:
    void run() {
        ASSERT(c->recordingEnabled() == false);
        sp<MSurface> surface = new MSurface();
        ASSERT(c->setPreviewDisplay(surface) == NO_ERROR);
        c->setPreviewCallbackFlag(FRAME_CALLBACK_FLAG_ENABLE_MASK);
        cc->setReleaser(c.get());
        c->startRecording();
        ASSERT(c->recordingEnabled() == true);
        sleep(2);
        c->stopRecording();
        cc->setReleaser(NULL);
        cc->assertData(CAMERA_MSG_VIDEO_FRAME, MCameraClient::GE, 10);
    }
};

class TestPreviewSize : public AfterStartPreview {
public:
    void checkOnePicture(int w, int h) {
        int size = w*h*3/2;  // should read from parameters

        c->stopPreview();

        CameraParameters param(c->getParameters());
        param.setPreviewSize(w, h);
        c->setPreviewCallbackFlag(FRAME_CALLBACK_FLAG_ENABLE_MASK);
        c->setParameters(param.flatten());

        c->startPreview();

        cc->clearStat();
        cc->waitData(CAMERA_MSG_PREVIEW_FRAME, MCameraClient::GE, 1);
        cc->assertDataSize(CAMERA_MSG_PREVIEW_FRAME, MCameraClient::EQ, size);
    }

    void run() {
        checkOnePicture(480, 320);
        checkOnePicture(352, 288);
        checkOnePicture(176, 144);
    }
};

void runHolderService() {
    defaultServiceManager()->addService(
            String16("CameraServiceTest.Holder"), new HolderService());
    ProcessState::self()->startThreadPool();
}

int main(int argc, char **argv)
{
    if (argc != 1) {
        runFunction(argv[1]);
        return 0;
    }
    INFO("CameraServiceTest start");
    gExecutable = argv[0];
    runHolderService();

    testConnect();                              flushCommands();
    testAllowConnectOnceOnly();                 flushCommands();
    testReconnect();                            flushCommands();
    testLockUnlock();                           flushCommands();
    testReconnectFromAnotherProcess();          flushCommands();

    RUN(TestSetPreviewDisplay);
    RUN(TestStartPreview);
    RUN(TestStartPreviewWithoutDisplay);
    RUN(TestAutoFocus);
    RUN(TestStopPreview);
    RUN(TestTakePicture);
    RUN(TestTakeMultiplePictures);
    RUN(TestGetParameters);
    RUN(TestPictureSize);
    RUN(TestPreviewCallbackFlag);
    RUN(TestRecording);
    RUN(TestPreviewSize);
}
