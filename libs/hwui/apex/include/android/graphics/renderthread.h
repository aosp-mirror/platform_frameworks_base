/*
 * Copyright (C) 2019 The Android Open Source Project
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
#ifndef ANDROID_GRAPHICS_RENDERTHREAD_H
#define ANDROID_GRAPHICS_RENDERTHREAD_H

#include <cutils/compiler.h>
#include <sys/cdefs.h>

__BEGIN_DECLS

/**
 * Dumps a textual representation of the graphics stats for this process.
 * @param fd The file descriptor that the available graphics stats will be appended to.  The
 *           function requires a valid fd, but does not persist or assume ownership of the fd
 *           outside the scope of this function.
 */
ANDROID_API void ARenderThread_dumpGraphicsMemory(int fd);

__END_DECLS

#endif // ANDROID_GRAPHICS_RENDERTHREAD_H
