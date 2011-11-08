/*
 * Copyright (C) 2009 The Android Open Source Project
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

#ifndef ANDROID_FRAMEWORKS_BASE_MEDIA_LIBMEDIAPLAYERSERVICE_TESTPLAYERSTUB_H__
#define ANDROID_FRAMEWORKS_BASE_MEDIA_LIBMEDIAPLAYERSERVICE_TESTPLAYERSTUB_H__

#include <media/MediaPlayerInterface.h>
#include <utils/Errors.h>

namespace android {
class MediaPlayerBase;  // in media/MediaPlayerInterface.h

// Wrapper around a test media player that gets dynamically loaded.
//
// The URL passed to setDataSource has this format:
//
//   test:<name of the .so>?url=<url for the real setDataSource impl.>
//
// e.g:
//   test:invoke_test_media_player.so?url=http://youtube.com/
//   test:invoke_test_media_player.so?url=speedtest
//
// TestPlayerStub::setDataSource loads the library in the test url. 2
// entry points with C linkage are expected. One to create the test
// player and one to destroy it.
//
// extern "C" android::MediaPlayerBase* newPlayer();
// extern "C" android::status_t deletePlayer(android::MediaPlayerBase *p);
//
// Once the test player has been loaded, its setDataSource
// implementation is called with the value of the 'url' parameter.
//
// typical usage in a java test:
// ============================
//
//  MediaPlayer p = new MediaPlayer();
//  p.setDataSource("test:invoke_mock_media_player.so?url=http://youtube.com");
//  p.prepare();
//  ...
//  p.release();

class TestPlayerStub : public MediaPlayerInterface {
  public:
    typedef MediaPlayerBase* (*NEW_PLAYER)();
    typedef status_t (*DELETE_PLAYER)(MediaPlayerBase *);

    TestPlayerStub();
    virtual ~TestPlayerStub();

    // Called right after the constructor. Check if the current build
    // allows test players.
    virtual status_t initCheck();

    // @param url Should be a test url. See class comment.
    virtual status_t setDataSource(
            const char* url, const KeyedVector<String8, String8> *headers);

    // Test player for a file descriptor source is not supported.
    virtual status_t setDataSource(int, int64_t, int64_t)  {
        return INVALID_OPERATION;
    }


    // All the methods below wrap the mPlayer instance.
    virtual status_t setVideoSurfaceTexture(
            const android::sp<android::ISurfaceTexture>& st)  {
        return mPlayer->setVideoSurfaceTexture(st);
    }
    virtual status_t prepare() {return mPlayer->prepare();}
    virtual status_t prepareAsync()  {return mPlayer->prepareAsync();}
    virtual status_t start()  {return mPlayer->start();}
    virtual status_t stop()  {return mPlayer->stop();}
    virtual status_t pause()  {return mPlayer->pause();}
    virtual bool isPlaying() {return mPlayer->isPlaying();}
    virtual status_t seekTo(int msec) {return mPlayer->seekTo(msec);}
    virtual status_t getCurrentPosition(int *p)  {
        return mPlayer->getCurrentPosition(p);
    }
    virtual status_t getDuration(int *d)  {return mPlayer->getDuration(d);}
    virtual status_t reset() {return mPlayer->reset();}
    virtual status_t setLooping(int b)  {return mPlayer->setLooping(b);}
    virtual player_type playerType() {return mPlayer->playerType();}
    virtual status_t invoke(const android::Parcel& in, android::Parcel *out) {
        return mPlayer->invoke(in, out);
    }
    virtual status_t setParameter(int key, const Parcel &request) {
        return mPlayer->setParameter(key, request);
    }
    virtual status_t getParameter(int key, Parcel *reply) {
        return mPlayer->getParameter(key, reply);
    }


    // @return true if the current build is 'eng' or 'test' and the
    //              url's scheme is 'test:'
    static bool canBeUsed(const char *url);

  private:
    // Release the player, dlclose the library.
    status_t resetInternal();
    status_t parseUrl();

    char *mUrl;                // test:foo.so?url=http://bar
    char *mFilename;           // foo.so
    char *mContentUrl;         // http://bar
    void *mHandle;             // returned by dlopen
    NEW_PLAYER    mNewPlayer;
    DELETE_PLAYER mDeletePlayer;
    MediaPlayerBase *mPlayer;  // wrapped player
};

}  // namespace android

#endif
