/*
**
** Copyright 2012, The Android Open Source Project
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

#ifndef ANDROID_MEDIAPLAYERFACTORY_H
#define ANDROID_MEDIAPLAYERFACTORY_H

#include <media/MediaPlayerInterface.h>
#include <media/stagefright/foundation/ABase.h>

namespace android {

class MediaPlayerFactory {
  public:
    class IFactory {
      public:
        virtual ~IFactory() { }

        virtual float scoreFactory(const sp<IMediaPlayer>& client,
                                   const char* url,
                                   float curScore) { return 0.0; }

        virtual float scoreFactory(const sp<IMediaPlayer>& client,
                                   int fd,
                                   int64_t offset,
                                   int64_t length,
                                   float curScore) { return 0.0; }

        virtual sp<MediaPlayerBase> createPlayer() = 0;
    };

    static status_t registerFactory(IFactory* factory,
                                    player_type type);
    static void unregisterFactory(player_type type);
    static player_type getPlayerType(const sp<IMediaPlayer>& client,
                                     const char* url);
    static player_type getPlayerType(const sp<IMediaPlayer>& client,
                                     int fd,
                                     int64_t offset,
                                     int64_t length);
    static sp<MediaPlayerBase> createPlayer(player_type playerType,
                                            void* cookie,
                                            notify_callback_f notifyFunc);

    static void registerBuiltinFactories();

  private:
    typedef KeyedVector<player_type, IFactory*> tFactoryMap;

    MediaPlayerFactory() { }

    static status_t registerFactory_l(IFactory* factory,
                                      player_type type);

    static Mutex       sLock;
    static tFactoryMap sFactoryMap;
    static bool        sInitComplete;

    DISALLOW_EVIL_CONSTRUCTORS(MediaPlayerFactory);
};

}  // namespace android
#endif  // ANDROID_MEDIAPLAYERFACTORY_H
