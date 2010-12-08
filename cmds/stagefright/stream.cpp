#include <binder/ProcessState.h>

#include <media/IStreamSource.h>
#include <media/mediaplayer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>

#include <binder/IServiceManager.h>
#include <media/IMediaPlayerService.h>
#include <surfaceflinger/ISurfaceComposer.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <fcntl.h>

using namespace android;

struct MyStreamSource : public BnStreamSource {
    // Caller retains ownership of fd.
    MyStreamSource(int fd);

    virtual void setListener(const sp<IStreamListener> &listener);
    virtual void setBuffers(const Vector<sp<IMemory> > &buffers);

    virtual void onBufferAvailable(size_t index);

protected:
    virtual ~MyStreamSource();

private:
    int mFd;

    sp<IStreamListener> mListener;
    Vector<sp<IMemory> > mBuffers;

    DISALLOW_EVIL_CONSTRUCTORS(MyStreamSource);
};

MyStreamSource::MyStreamSource(int fd)
    : mFd(fd) {
    CHECK_GE(fd, 0);
}

MyStreamSource::~MyStreamSource() {
}

void MyStreamSource::setListener(const sp<IStreamListener> &listener) {
    mListener = listener;
}

void MyStreamSource::setBuffers(const Vector<sp<IMemory> > &buffers) {
    mBuffers = buffers;
}

void MyStreamSource::onBufferAvailable(size_t index) {
    CHECK_LT(index, mBuffers.size());
    sp<IMemory> mem = mBuffers.itemAt(index);

    ssize_t n = read(mFd, mem->pointer(), mem->size());
    if (n <= 0) {
        mListener->issueCommand(IStreamListener::EOS, false /* synchronous */);
    } else {
        mListener->queueBuffer(index, n);
    }
}

////////////////////////////////////////////////////////////////////////////////

struct MyClient : public BnMediaPlayerClient {
    MyClient()
        : mEOS(false) {
    }

    virtual void notify(int msg, int ext1, int ext2) {
        Mutex::Autolock autoLock(mLock);

        if (msg == MEDIA_ERROR || msg == MEDIA_PLAYBACK_COMPLETE) {
            mEOS = true;
            mCondition.signal();
        }
    }

    void waitForEOS() {
        Mutex::Autolock autoLock(mLock);
        while (!mEOS) {
            mCondition.wait(mLock);
        }
    }

protected:
    virtual ~MyClient() {
    }

private:
    Mutex mLock;
    Condition mCondition;

    bool mEOS;

    DISALLOW_EVIL_CONSTRUCTORS(MyClient);
};

int main(int argc, char **argv) {
    android::ProcessState::self()->startThreadPool();

    if (argc != 2) {
        fprintf(stderr, "Usage: %s filename\n", argv[0]);
        return 1;
    }

    sp<SurfaceComposerClient> composerClient = new SurfaceComposerClient;
    CHECK_EQ(composerClient->initCheck(), (status_t)OK);

    sp<SurfaceControl> control =
        composerClient->createSurface(
                getpid(),
                String8("A Surface"),
                0,
                1280,
                800,
                PIXEL_FORMAT_RGB_565,
                0);

    CHECK(control != NULL);
    CHECK(control->isValid());

    CHECK_EQ(composerClient->openTransaction(), (status_t)OK);
    CHECK_EQ(control->setLayer(30000), (status_t)OK);
    CHECK_EQ(control->show(), (status_t)OK);
    CHECK_EQ(composerClient->closeTransaction(), (status_t)OK);

    sp<Surface> surface = control->getSurface();
    CHECK(surface != NULL);

    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> binder = sm->getService(String16("media.player"));
    sp<IMediaPlayerService> service = interface_cast<IMediaPlayerService>(binder);

    CHECK(service.get() != NULL);

    int fd = open(argv[1], O_RDONLY);

    if (fd < 0) {
        fprintf(stderr, "Failed to open file '%s'.", argv[1]);
        return 1;
    }

    sp<MyClient> client = new MyClient;

    sp<IMediaPlayer> player =
        service->create(getpid(), client, new MyStreamSource(fd), 0);

    if (player != NULL) {
        player->setVideoSurface(surface);
        player->start();

        client->waitForEOS();

        player->stop();
    } else {
        fprintf(stderr, "failed to instantiate player.\n");
    }

    close(fd);
    fd = -1;

    composerClient->dispose();

    return 0;
}
