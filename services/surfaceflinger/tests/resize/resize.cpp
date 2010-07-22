#include <cutils/memory.h>

#include <utils/Log.h>

#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>

#include <surfaceflinger/Surface.h>
#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

#include <ui/Overlay.h>

using namespace android;

namespace android {
class Test {
public:
    static const sp<ISurface>& getISurface(const sp<Surface>& s) {
        return s->getISurface();
    }
};
};

int main(int argc, char** argv)
{
    // set up the thread-pool
    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    // create a client to surfaceflinger
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    
    // create pushbuffer surface
    sp<Surface> surface = client->createSurface(getpid(), 0, 160, 240, 
            PIXEL_FORMAT_RGB_565);


    client->openTransaction();
    surface->setLayer(100000);
    client->closeTransaction();

    Surface::SurfaceInfo info;
    surface->lock(&info);
    ssize_t bpr = info.s * bytesPerPixel(info.format);
    android_memset16((uint16_t*)info.bits, 0xF800, bpr*info.h);
    surface->unlockAndPost();

    surface->lock(&info);
    android_memset16((uint16_t*)info.bits, 0x07E0, bpr*info.h);
    surface->unlockAndPost();

    client->openTransaction();
    surface->setSize(320, 240);
    client->closeTransaction();

    
    IPCThreadState::self()->joinThreadPool();
    
    return 0;
}
