<!-- Copyright (C) 2020 The Android Open Source Project

     Licensed under the Apache License, Version 2.0 (the "License");
     you may not use this file except in compliance with the License.
     You may obtain a copy of the License at

          http://www.apache.org/licenses/LICENSE-2.0

     Unless required by applicable law or agreed to in writing, software
     distributed under the License is distributed on an "AS IS" BASIS,
     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     See the License for the specific language governing permissions and
     limitations under the License.
-->

# Oom Adjuster Designs

## Purpose of Oom Adjuster

The Android OS runs with limited hardware resources, i.e. CPU/RAM/Power. To strive for the better performance, Oom Ajuster is introduced to tweak the following 3 major factors:

 * Process State
   * Wildly used by the System Server, i.e., determine if it's foreground or not, change the GC behavior, etc.
   * Defined in `ActivityManager#PROCESS_STATE_*`
 * Oom Adj score
   * Used by the lmkd to determine which process should be expunged on memory pressure.
   * Defined in `ProcessList#*_ADJ`
 * Scheduler Group
   * Used to tweak the process group, thread priorities.
   * Top process is scheduled to be running on a dedicated big core, while foreground processes take the other big cores; background processes stay with LITTLE cores instead.

## Process Capabilities

Besides the above 3 major factors, Android R introduced the Process Capabilities `ActivityManager#PROCESS_CAPABILITY_*`.  It's a new attribute to process record, mainly designed for supporting the "while-in-use" permission model - in additional to the traditional Android permissions, wheather or not a process has access to a given API, will be guarded by its current process state as well. The OomAdjuster will compute the process capabilities during updating the oom adj. Meanwhile, the flag `ActivityManager#BIND_INCLUDE_CAPABILITIES` enables to possiblity to "transfer" the capability from a client process to the service process it binds to.

## Rationale of Oom Adjuster

System server keeps a list of recent used app processes. Given the 4 types of entities that an Android processes could have: Activity, Service, Content Provider and Broadcast Receiver, the System Server has to adjust the above 3 factors to give the users the best performance according to the states of the entities. A typical case would be that: foreground app A binds into a background service B in order to serve the user, in the case of memory pressure, the background service B should be avoided from being expunged since it would result user-perceptible interruption of service. The Oom Adjuster is to tweak the aforementioned 3 factors for those app processes.

The timing of updating the Oom Adj score is vital: assume a camera process in background gets launched into foreground, launching camera typically incurs high memory pressure, which could incur low memory kills - if the camera process isn't moved out of the background adj group, it could get killed by lmkd. Therefore the updates have to be called pretty frequently: in case there is an activity start, service binding, etc.

The update procedure basically consists of 3 parts:
  * Find out the process record to be updated
    * There are two categories of updateOomAdjLocked: one with the target process record to be updated, while the other one is to update all process record.
    * Besides that, while computing the Oom Aj score, the clients of service connections or content providers of the present process record, which forms a process dependency graph actually, will be evaluated as well.
    * Starting from Android R, when updating for a specific process record, an optimization is made that, only the reachable process records starting from this process record in the process dependency graph, will be re-evaluated.
    * The `cached` Oom Adj scores are grouped in `bucket`, which is used in the isolated processes: they could be correlated - assume one isolated Chrome process is at Oom Adj score 920 and another one is 980; the later one could get expunged much earlier than the former one, which doesn't make sense; grouping them would be a big relief for this case.
  * Compute Oom Adj score
    * This procedure returns true if there is a score change, false if there is no.
    * The curAdj field in the process record is used as an intermediate value during the computation.
    * Initialize the Process State to `PROCESS_STATE_CACHED_EMPTY`, which is the lowest importance.
    * Calculate the scores based on various factors:
      * If it's not allowed to be lower than `ProcessList#FOREGROUND_APP_ADJ`, meaning it's propbably a persistent process, there is no too much to do here.
      * Exame if the process is the top app, running remote animation, running instrumentation, receiving broadcast, executing services, running on top but sleeping (screen off), update the intermediate values.
      * Ask Window Manager (yes, ActivityTaskManager is with WindowManager now) to tell each activity's visibility information.
      * Check if the process has recent tasks, check if it's hosting a foreground service, overlay UI, toast etc. Note for the foreground service, if it was in foreground status, allow it to stay in higher rank in memory for a while: Assuming a camera captureing case, where the camera app is still processing the picture while being switched out of foreground - keep it stay in higher rank in memory would ensure the pictures are persisted correctly.
      * Check if the process is the heavy weight process, whose launching/exiting would be slow and it's better to keep it in the memory. Note there should be only one heavy weight process across the system.
      * For sure the Home process shouldn't be expunged frequently as well.
      * The next two factors are either it was the previous process with visible UI to the user, or it's a backup agent.
      * And then it goes to the massive searches against the service connections and the content providers, each of the clients will be evaluated, and the Oom Adj score could get updated according to its clients' scores. However there are a bunch of service binding flags which could impact the result:
        * Below table captures the results with given various service binding states:
        | Conditon #1                     | Condition #2                                               | Condition #3                                 | Condition #4                                      | Result                   |
        |---------------------------------|------------------------------------------------------------|----------------------------------------------|---------------------------------------------------|--------------------------|
        | `BIND_WAIVE_PRIORITY` not set   | `BIND_ALLOW_OOM_MANAGEMENT` set                            | Shown UI && Not Home                         |                                                   | Use the app's own Adj    |
        |                                 |                                                            | Inactive for a while                         |                                                   | Use the app's own Adj    |
        |                                 | Client has a higher importance                             | Shown UI && Not Home && client is invisible  |                                                   | Use the app's own Adj    |
        |                                 |                                                            | `BIND_ABOVE_CLIENT` and `BIND_IMPORTANT` set | Client is not persistent                          | Try client's Adj         |
        |                                 |                                                            |                                              | Client is persistent                              | Try persistent Adj       |
        |                                 |                                                            | `BIND_NOT_PERCEPTIBLE` set                   | client < perceptible && app > low perceptible     | Try low perceptible Adj  |
        |                                 |                                                            | `BIND_NOT_VISIBLE` set                       | client < perceptible && app > perceptible         | Try perceptible Adj      |
        |                                 |                                                            | Client >= perceptible                        |                                                   | Try client's Adj         |
        |                                 |                                                            | Adj > visible                                |                                                   | Max of client/Own Adj    |
        |                                 |                                                            |                                              |                                                   | Use the app's own Adj    |
        |                                 | `BIND_NOT_FOREGROUND`+`BIND_IMPORTANT_BACKGROUND` not set  | Client's sched group > app's                 | `BIND_IMPORTANT` is set                           | Use client's sched group |
        |                                 |                                                            |                                              |                                                   | Use default sched group  |
        |                                 |                                                            | Client's process state < top                 | `BIND_FOREGROUND_SERVICE` is set                  | ProcState = bound fg     |
        |                                 |                                                            |                                              | `BIND_FOREGROUND_SERVICE_WHILE_AWAKE` + screen ON | ProcState = bound fg     |
        |                                 |                                                            |                                              |                                                   | ProcState = important fg |
        |                                 |                                                            | Client's process state = top                 |                                                   | ProcState = bound top    |
        |                                 | `BIND_IMPORTANT_BACKGROUND` not set                        | Client's process state < transient bg        |                                                   | ProcState = transient bg |
        |                                 | `BIND_NOT_FOREGROUND` or `BIND_IMPORTANT_BACKGROUND` set   | Client's process state < important bg        |                                                   | ProcState = important bg |
        | `BIND_ADJUST_WITH_ACTIVITY` set | Adj > fg && App visible                                    |                                              |                                                   | Adj = foreground         |
        |                                 |                                                            | `BIND_NOT_FOREGROUND` not set                | `BIND_IMPORTANT` is set                           | Sched = top app bound    |
        |                                 |                                                            |                                              | `BIND_IMPORTANT` is NOT set                       | Sched = default          |
        * Below table captures the results with given various content provider binding states:
        | Conditon #1                     | Condition #2                                               | Condition #3                                 | Result                   |
        |---------------------------------|------------------------------------------------------------|----------------------------------------------|--------------------------|
        | Client's process state >= cached|                                                            |                                              | Client ProcState = empty |
        | Adj > Client Adj                | Not shown UI or is Home, or Client's Adj <= perceptible    | Client's Adj <= foreground Adj               | Try foreground Adj       |
        |                                 |                                                            | Client's Adj > foreground Adj                | Try client's Adj         |
        | Client's process state <= fg svc| Client's process state is top                              |                                              | ProcState = bound top    |
        |                                 | Client's process state is NOT top                          |                                              | ProcState = bound fg svc |
        | Has external dependencies       | Adj > fg app                                               |                                              | adj = fg app             |
        |                                 | Process state > important foreground                       |                                              | ProcState = important fg |
        | Still within retain time        | Adj > previous app Adj                                     |                                              | adj = previuos app adj   |
        |                                 | Process state > last activity                              |                                              | ProcState = last activity|
        * Some additional tweaks after the above ones:
        | Conditon #1                     | Condition #2                                               | Condition #3                                 | Result                             |
        |---------------------------------|------------------------------------------------------------|----------------------------------------------|------------------------------------|
        | Process state >= cached empty   | Has client activities                                      |                                              | ProcState = cached activity client |
        |                                 | treat like activity (IME)                                  |                                              | ProcState = cached activity        |
        | Adj is service adj              | computing all process records                              | Num of new service A > 1/3 of services       | Push it to service B               |
        |                                 |                                                            | Low on RAM and app process's PSS is large    | Push it to service B               |
  * Apply the scores, which consists of: write into kernel sysfs entries to update the Oom Adj scores; call kernel API to set the thread priorities, and then tell the world the new process state

## Cycles, Cycles, Cycles

Another interesting aspect of the Oom Adjuster is the cycles of the dependencies. A simple example would be like below illustration, process A is hosting a service which is bound by process B; meanwhile the process B is hosting a service which is bound by process A.
<pre>
  +-------------+           +-------------+
  |  Process A  | <-------- |  Process B  |
  | (service 1) | --------> | (service 2) |
  +-------------+           +-------------+
</pre>

There could be very complicated cases, which could involve multiple cycles, and in the dependency graph, each of the process record node could have different importance.
<pre>
  +-------------+           +-------------+           +-------------+           +-------------+           +-------------+
  |  Process D  | --------> |  Process A  | <-------- |  Process B  | <-------- |  Process C  | <-------- |  Process A  |
  |             |           | (service 1) |           | (service 2) |           | (service 3) |           | (service 1) |
  +-------------+           +-------------+           +-------------+           +-------------+           +-------------+
</pre>

The Oom Adjuster maintains a global sequence ID `mAdjSeq` to track the current Oom Adjuster calling. And each of the process record has a field to track in which sequence the process record is evaluated. If during the Oom Adj computation, a process record with sequence ID as same as the current global sequence ID, this would mean that a cycle is detected; in this case:
  * Decrement the sequence ID of each process if there is a cycle.
  * Re-evaluate each of the process record within the cycle until nothing was promoted.
  * Iterate the processes from least important to most important ones.
  * A maximum retries of 10 is enforced, while in practice, the maximum retries could reach only 2 to 3.

