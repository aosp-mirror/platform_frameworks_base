#include <binder/IPCThreadState.h>
#include <binder/ProcessState.h>
#include <binder/IServiceManager.h>
#include <utils/Log.h>

#include <ui/Overlay.h>

#include <surfaceflinger/Surface.h>
#include <surfaceflinger/ISurface.h>
#include <surfaceflinger/SurfaceComposerClient.h>

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
    sp<Surface> surface = client->createSurface(getpid(), 0, 320, 240, 
            PIXEL_FORMAT_UNKNOWN, ISurfaceComposer::ePushBuffers);

    // get to the isurface
    sp<ISurface> isurface = Test::getISurface(surface);
    printf("isurface = %p\n", isurface.get());
    
    // now request an overlay
    sp<OverlayRef> ref = isurface->createOverlay(320, 240, PIXEL_FORMAT_RGB_565);
    sp<Overlay> overlay = new Overlay(ref);
    

    /*
     * here we can use the overlay API 
     */
    
    overlay_buffer_t buffer; 
    overlay->dequeueBuffer(&buffer);
    printf("buffer = %p\n", buffer);
    
    void* address = overlay->getBufferAddress(buffer);
    printf("address = %p\n", address);

    overlay->queueBuffer(buffer);

    return 0;
}
