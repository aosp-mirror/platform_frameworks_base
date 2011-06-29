/*
 **
 ** Copyright 2008, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "IMediaRecorder"
#include <utils/Log.h>
#include <binder/Parcel.h>
#include <surfaceflinger/Surface.h>
#include <camera/ICamera.h>
#include <media/IMediaRecorderClient.h>
#include <media/IMediaRecorder.h>
#include <gui/ISurfaceTexture.h>
#include <unistd.h>


namespace android {

enum {
    RELEASE = IBinder::FIRST_CALL_TRANSACTION,
    INIT,
    CLOSE,
    QUERY_SURFACE_MEDIASOURCE,
    RESET,
    STOP,
    START,
    PREPARE,
    GET_MAX_AMPLITUDE,
    SET_VIDEO_SOURCE,
    SET_AUDIO_SOURCE,
    SET_OUTPUT_FORMAT,
    SET_VIDEO_ENCODER,
    SET_AUDIO_ENCODER,
    SET_OUTPUT_FILE_PATH,
    SET_OUTPUT_FILE_FD,
    SET_VIDEO_SIZE,
    SET_VIDEO_FRAMERATE,
    SET_PARAMETERS,
    SET_PREVIEW_SURFACE,
    SET_CAMERA,
    SET_LISTENER
};

class BpMediaRecorder: public BpInterface<IMediaRecorder>
{
public:
    BpMediaRecorder(const sp<IBinder>& impl)
    : BpInterface<IMediaRecorder>(impl)
    {
    }

    status_t setCamera(const sp<ICamera>& camera, const sp<ICameraRecordingProxy>& proxy)
    {
        LOGV("setCamera(%p,%p)", camera.get(), proxy.get());
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeStrongBinder(camera->asBinder());
        data.writeStrongBinder(proxy->asBinder());
        remote()->transact(SET_CAMERA, data, &reply);
        return reply.readInt32();
    }

    sp<ISurfaceTexture> querySurfaceMediaSource()
    {
        LOGV("Query SurfaceMediaSource");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(QUERY_SURFACE_MEDIASOURCE, data, &reply);
        int returnedNull = reply.readInt32();
        if (returnedNull) {
            return NULL;
        }
        return interface_cast<ISurfaceTexture>(reply.readStrongBinder());
    }

    status_t setPreviewSurface(const sp<Surface>& surface)
    {
        LOGV("setPreviewSurface(%p)", surface.get());
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        Surface::writeToParcel(surface, &data);
        remote()->transact(SET_PREVIEW_SURFACE, data, &reply);
        return reply.readInt32();
    }

    status_t init()
    {
        LOGV("init");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(INIT, data, &reply);
        return reply.readInt32();
    }

    status_t setVideoSource(int vs)
    {
        LOGV("setVideoSource(%d)", vs);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(vs);
        remote()->transact(SET_VIDEO_SOURCE, data, &reply);
        return reply.readInt32();
    }

    status_t setAudioSource(int as)
    {
        LOGV("setAudioSource(%d)", as);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(as);
        remote()->transact(SET_AUDIO_SOURCE, data, &reply);
        return reply.readInt32();
    }

    status_t setOutputFormat(int of)
    {
        LOGV("setOutputFormat(%d)", of);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(of);
        remote()->transact(SET_OUTPUT_FORMAT, data, &reply);
        return reply.readInt32();
    }

    status_t setVideoEncoder(int ve)
    {
        LOGV("setVideoEncoder(%d)", ve);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(ve);
        remote()->transact(SET_VIDEO_ENCODER, data, &reply);
        return reply.readInt32();
    }

    status_t setAudioEncoder(int ae)
    {
        LOGV("setAudioEncoder(%d)", ae);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(ae);
        remote()->transact(SET_AUDIO_ENCODER, data, &reply);
        return reply.readInt32();
    }

    status_t setOutputFile(const char* path)
    {
        LOGV("setOutputFile(%s)", path);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeCString(path);
        remote()->transact(SET_OUTPUT_FILE_PATH, data, &reply);
        return reply.readInt32();
    }

    status_t setOutputFile(int fd, int64_t offset, int64_t length) {
        LOGV("setOutputFile(%d, %lld, %lld)", fd, offset, length);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeFileDescriptor(fd);
        data.writeInt64(offset);
        data.writeInt64(length);
        remote()->transact(SET_OUTPUT_FILE_FD, data, &reply);
        return reply.readInt32();
    }

    status_t setVideoSize(int width, int height)
    {
        LOGV("setVideoSize(%dx%d)", width, height);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(width);
        data.writeInt32(height);
        remote()->transact(SET_VIDEO_SIZE, data, &reply);
        return reply.readInt32();
    }

    status_t setVideoFrameRate(int frames_per_second)
    {
        LOGV("setVideoFrameRate(%d)", frames_per_second);
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeInt32(frames_per_second);
        remote()->transact(SET_VIDEO_FRAMERATE, data, &reply);
        return reply.readInt32();
    }

    status_t setParameters(const String8& params)
    {
        LOGV("setParameter(%s)", params.string());
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeString8(params);
        remote()->transact(SET_PARAMETERS, data, &reply);
        return reply.readInt32();
    }

    status_t setListener(const sp<IMediaRecorderClient>& listener)
    {
        LOGV("setListener(%p)", listener.get());
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        data.writeStrongBinder(listener->asBinder());
        remote()->transact(SET_LISTENER, data, &reply);
        return reply.readInt32();
    }

    status_t prepare()
    {
        LOGV("prepare");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(PREPARE, data, &reply);
        return reply.readInt32();
    }

    status_t getMaxAmplitude(int* max)
    {
        LOGV("getMaxAmplitude");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(GET_MAX_AMPLITUDE, data, &reply);
        *max = reply.readInt32();
        return reply.readInt32();
    }

    status_t start()
    {
        LOGV("start");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(START, data, &reply);
        return reply.readInt32();
    }

    status_t stop()
    {
        LOGV("stop");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(STOP, data, &reply);
        return reply.readInt32();
    }

    status_t reset()
    {
        LOGV("reset");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(RESET, data, &reply);
        return reply.readInt32();
    }

    status_t close()
    {
        LOGV("close");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(CLOSE, data, &reply);
        return reply.readInt32();
    }

    status_t release()
    {
        LOGV("release");
        Parcel data, reply;
        data.writeInterfaceToken(IMediaRecorder::getInterfaceDescriptor());
        remote()->transact(RELEASE, data, &reply);
        return reply.readInt32();
    }
};

IMPLEMENT_META_INTERFACE(MediaRecorder, "android.media.IMediaRecorder");

// ----------------------------------------------------------------------

status_t BnMediaRecorder::onTransact(
                                     uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    switch(code) {
        case RELEASE: {
            LOGV("RELEASE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(release());
            return NO_ERROR;
        } break;
        case INIT: {
            LOGV("INIT");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(init());
            return NO_ERROR;
        } break;
        case CLOSE: {
            LOGV("CLOSE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(close());
            return NO_ERROR;
        } break;
        case RESET: {
            LOGV("RESET");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(reset());
            return NO_ERROR;
        } break;
        case STOP: {
            LOGV("STOP");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(stop());
            return NO_ERROR;
        } break;
        case START: {
            LOGV("START");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(start());
            return NO_ERROR;
        } break;
        case PREPARE: {
            LOGV("PREPARE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(prepare());
            return NO_ERROR;
        } break;
        case GET_MAX_AMPLITUDE: {
            LOGV("GET_MAX_AMPLITUDE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int max = 0;
            status_t ret = getMaxAmplitude(&max);
            reply->writeInt32(max);
            reply->writeInt32(ret);
            return NO_ERROR;
        } break;
        case SET_VIDEO_SOURCE: {
            LOGV("SET_VIDEO_SOURCE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int vs = data.readInt32();
            reply->writeInt32(setVideoSource(vs));
            return NO_ERROR;
        } break;
        case SET_AUDIO_SOURCE: {
            LOGV("SET_AUDIO_SOURCE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int as = data.readInt32();
            reply->writeInt32(setAudioSource(as));
            return NO_ERROR;
        } break;
        case SET_OUTPUT_FORMAT: {
            LOGV("SET_OUTPUT_FORMAT");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int of = data.readInt32();
            reply->writeInt32(setOutputFormat(of));
            return NO_ERROR;
        } break;
        case SET_VIDEO_ENCODER: {
            LOGV("SET_VIDEO_ENCODER");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int ve = data.readInt32();
            reply->writeInt32(setVideoEncoder(ve));
            return NO_ERROR;
        } break;
        case SET_AUDIO_ENCODER: {
            LOGV("SET_AUDIO_ENCODER");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int ae = data.readInt32();
            reply->writeInt32(setAudioEncoder(ae));
            return NO_ERROR;

        } break;
        case SET_OUTPUT_FILE_PATH: {
            LOGV("SET_OUTPUT_FILE_PATH");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            const char* path = data.readCString();
            reply->writeInt32(setOutputFile(path));
            return NO_ERROR;
        } break;
        case SET_OUTPUT_FILE_FD: {
            LOGV("SET_OUTPUT_FILE_FD");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int fd = dup(data.readFileDescriptor());
            int64_t offset = data.readInt64();
            int64_t length = data.readInt64();
            reply->writeInt32(setOutputFile(fd, offset, length));
            ::close(fd);
            return NO_ERROR;
        } break;
        case SET_VIDEO_SIZE: {
            LOGV("SET_VIDEO_SIZE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int width = data.readInt32();
            int height = data.readInt32();
            reply->writeInt32(setVideoSize(width, height));
            return NO_ERROR;
        } break;
        case SET_VIDEO_FRAMERATE: {
            LOGV("SET_VIDEO_FRAMERATE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            int frames_per_second = data.readInt32();
            reply->writeInt32(setVideoFrameRate(frames_per_second));
            return NO_ERROR;
        } break;
        case SET_PARAMETERS: {
            LOGV("SET_PARAMETER");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            reply->writeInt32(setParameters(data.readString8()));
            return NO_ERROR;
        } break;
        case SET_LISTENER: {
            LOGV("SET_LISTENER");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            sp<IMediaRecorderClient> listener =
                interface_cast<IMediaRecorderClient>(data.readStrongBinder());
            reply->writeInt32(setListener(listener));
            return NO_ERROR;
        } break;
        case SET_PREVIEW_SURFACE: {
            LOGV("SET_PREVIEW_SURFACE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            sp<Surface> surface = Surface::readFromParcel(data);
            reply->writeInt32(setPreviewSurface(surface));
            return NO_ERROR;
        } break;
        case SET_CAMERA: {
            LOGV("SET_CAMERA");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            sp<ICamera> camera = interface_cast<ICamera>(data.readStrongBinder());
            sp<ICameraRecordingProxy> proxy =
                interface_cast<ICameraRecordingProxy>(data.readStrongBinder());
            reply->writeInt32(setCamera(camera, proxy));
            return NO_ERROR;
        } break;
        case QUERY_SURFACE_MEDIASOURCE: {
            LOGV("QUERY_SURFACE_MEDIASOURCE");
            CHECK_INTERFACE(IMediaRecorder, data, reply);
            // call the mediaserver side to create
            // a surfacemediasource
            sp<ISurfaceTexture> surfaceMediaSource = querySurfaceMediaSource();
            // The mediaserver might have failed to create a source
            int returnedNull= (surfaceMediaSource == NULL) ? 1 : 0 ;
            reply->writeInt32(returnedNull);
            if (!returnedNull) {
                reply->writeStrongBinder(surfaceMediaSource->asBinder());
            }
            return NO_ERROR;
        } break;
        default:
            return BBinder::onTransact(code, data, reply, flags);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
