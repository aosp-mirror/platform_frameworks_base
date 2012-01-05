/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef VIDEO_EDITOR_LOGGING_H
#define VIDEO_EDITOR_LOGGING_H

//#define VIDEOEDIT_LOGGING_ENABLED

#define VIDEOEDIT_LOG_INDENTATION                       (3)

#define VIDEOEDIT_LOG_ERROR                             __android_log_print
#define VIDEOEDIT_LOG_EXCEPTION                         __android_log_print

#ifdef VIDEOEDIT_LOGGING_ENABLED

#define VIDEOEDIT_LOG_ALLOCATION                        __android_log_print
#define VIDEOEDIT_LOG_API                               __android_log_print
#define VIDEOEDIT_LOG_FUNCTION                          __android_log_print
#define VIDEOEDIT_LOG_RESULT(x,y, ...)                     ALOGI(y, __VA_ARGS__ )
#define VIDEOEDIT_LOG_SETTING                           __android_log_print
#define VIDEOEDIT_LOG_EDIT_SETTINGS(m_settings)         videoEditClasses_logEditSettings\
                                               (m_settings, VIDEOEDIT_LOG_INDENTATION)
#define VIDEOEDIT_PROP_LOG_PROPERTIES(m_properties)          videoEditPropClass_logProperties\
                                                  (m_properties, VIDEOEDIT_LOG_INDENTATION)
#define VIDEOEDIT_PROP_LOG_RESULT                            __android_log_print

#else

#define VIDEOEDIT_LOG_ALLOCATION                        (void)
#define VIDEOEDIT_LOG_API                               (void)
#define VIDEOEDIT_LOG_FUNCTION                          (void)
#define VIDEOEDIT_LOG_RESULT                            (void)
#define VIDEOEDIT_LOG_SETTING                           (void)
#define VIDEOEDIT_LOG_EDIT_SETTINGS(m_settings)         (void)m_settings
#define VIDEOEDIT_PROP_LOG_PROPERTIES(m_properties)          (void)m_properties
#define VIDEOEDIT_PROP_LOG_RESULT                            (void)

#endif

#endif // VIDEO_EDITOR_LOGGING_H

