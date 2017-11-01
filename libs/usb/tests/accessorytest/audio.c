/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <signal.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>
#include <string.h>

#include <tinyalsa/asoundlib.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/ioctl.h>

#include "accessory.h"

#define BUFFER_COUNT 2
#define BUFFER_SIZE 16384

#define BUFFER_EMPTY 0
#define BUFFER_BUSY 1
#define BUFFER_FULL 2

static char* buffers[BUFFER_COUNT];
static int buffer_states[BUFFER_COUNT];
static int empty_index = 0;
static int full_index = -1;

static pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t empty_cond = PTHREAD_COND_INITIALIZER;
static pthread_cond_t full_cond = PTHREAD_COND_INITIALIZER;

static unsigned int input_card;
static unsigned int input_device;

static int get_empty()
{
    int index, other;

    pthread_mutex_lock(&mutex);

    while (empty_index == -1)
        pthread_cond_wait(&empty_cond, &mutex);

    index = empty_index;
    other = (index == 0 ? 1 : 0);
    buffer_states[index] = BUFFER_BUSY;
    if (buffer_states[other] == BUFFER_EMPTY)
        empty_index = other;
    else
        empty_index = -1;

    pthread_mutex_unlock(&mutex);
    return index;
}

static void put_empty(int index)
{
    pthread_mutex_lock(&mutex);

    buffer_states[index] = BUFFER_EMPTY;
    if (empty_index == -1) {
        empty_index = index;
        pthread_cond_signal(&empty_cond);
    }

    pthread_mutex_unlock(&mutex);
}

static int get_full()
{
    int index, other;

    pthread_mutex_lock(&mutex);

    while (full_index == -1)
        pthread_cond_wait(&full_cond, &mutex);

    index = full_index;
    other = (index == 0 ? 1 : 0);
    buffer_states[index] = BUFFER_BUSY;
    if (buffer_states[other] == BUFFER_FULL)
        full_index = other;
    else
        full_index = -1;

    pthread_mutex_unlock(&mutex);
    return index;
}

static void put_full(int index)
{
    pthread_mutex_lock(&mutex);

    buffer_states[index] = BUFFER_FULL;
    if (full_index == -1) {
        full_index = index;
        pthread_cond_signal(&full_cond);
    }

    pthread_mutex_unlock(&mutex);
}

static void* capture_thread(void* arg)
{
    struct pcm_config config;
    struct pcm *pcm = NULL;

    fprintf(stderr, "capture_thread start\n");

    memset(&config, 0, sizeof(config));

    config.channels = 2;
    config.rate = 44100;
    config.period_size = 1024;
    config.period_count = 4;
    config.format = PCM_FORMAT_S16_LE;

    while (1) {
        while (!pcm) {
            pcm = pcm_open(input_card, input_device, PCM_IN, &config);
            if (pcm && !pcm_is_ready(pcm)) {
                pcm_close(pcm);
                pcm = NULL;
            }
            if (!pcm)
                sleep(1);
        }

        while (pcm) {
            int index = get_empty();
            if (pcm_read(pcm, buffers[index], BUFFER_SIZE)) {
                put_empty(index);
                pcm_close(pcm);
                pcm = NULL;
            } else {
                put_full(index);
            }
        }
    }

    fprintf(stderr, "capture_thread done\n");
    return NULL;
}

static void* play_thread(void* arg)
{
    struct pcm *pcm = arg;
    int index, err;

    fprintf(stderr, "play_thread start\n");

    while (1) {
        index = get_full();

        err = pcm_write(pcm, buffers[index], BUFFER_SIZE);
        if (err)
            fprintf(stderr, "pcm_write err: %d\n", err);

        put_empty(index);
    }

    fprintf(stderr, "play_thread done\n");
    pcm_close(pcm);

    return NULL;
}

int init_audio(unsigned int ic, unsigned int id, unsigned int oc, unsigned int od)
{
    pthread_t tid;
    struct pcm_config config;
    struct pcm *pcm;
    int i;

    input_card = ic;
    input_device = id;

    for (i = 0; i < BUFFER_COUNT; i++) {
        buffers[i] = malloc(BUFFER_SIZE);
        buffer_states[i] = BUFFER_EMPTY;
    }

    memset(&config, 0, sizeof(config));
    config.channels = 2;
    config.rate = 44100;
    config.period_size = 1024;
    config.period_count = 4;
    config.format = PCM_FORMAT_S16_LE;

    pcm = pcm_open(oc, od, PCM_OUT, &config);
    if (!pcm || !pcm_is_ready(pcm)) {
        fprintf(stderr, "Unable to open PCM device %d/%d for output (%s)\n",
               oc, od, pcm_get_error(pcm));
        return -1;
    }

    pthread_create(&tid, NULL, capture_thread, NULL);
    pthread_create(&tid, NULL, play_thread, pcm);
    return 0;
}
