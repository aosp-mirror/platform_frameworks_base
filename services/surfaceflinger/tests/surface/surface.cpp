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

int main(int argc, char** argv)
{
    // set up the thread-pool
    sp<ProcessState> proc(ProcessState::self());
    ProcessState::self()->startThreadPool();

    // create a client to surfaceflinger
    sp<SurfaceComposerClient> client = new SurfaceComposerClient();
    
    // create pushbuffer surface
    sp<SurfaceControl> surfaceControl = client->createSurface(
            getpid(), 0, 160, 240, PIXEL_FORMAT_RGB_565);
    client->openTransaction();
    surfaceControl->setLayer(100000);
    client->closeTransaction();

    // pretend it went cross-process
    Parcel parcel;
    SurfaceControl::writeSurfaceToParcel(surfaceControl, &parcel);
    parcel.setDataPosition(0);
    sp<Surface> surface = Surface::readFromParcel(parcel);
    ANativeWindow* window = surface.get();

    printf("window=%p\n", window);

    int err = native_window_set_buffer_count(window, 8);
    android_native_buffer_t* buffer;

    for (int i=0 ; i<8 ; i++) {
        window->dequeueBuffer(window, &buffer);
        printf("buffer %d: %p\n", i, buffer);
    }

    printf("test complete. CTRL+C to finish.\n");

    IPCThreadState::self()->joinThreadPool();
    return 0;
}
