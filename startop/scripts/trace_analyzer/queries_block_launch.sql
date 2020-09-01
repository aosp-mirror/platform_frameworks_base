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

DROP VIEW IF EXISTS blocked_iowait_for_app_launches;

CREATE VIEW blocked_iowait_for_app_launches AS
WITH
    block_launch_join AS (
        SELECT *
        FROM blocking_durations AS bd,
             launch_durations_named AS ld
        WHERE bd.block_timestamp >= ld.started_timestamp
              AND bd.unblock_timestamp <= ld.finished_timestamp
    ),
    blocked_ui_threads AS (
        SELECT *
        FROM start_process_ui_threads AS sp,
             block_launch_join AS blj
        WHERE sp.atm_ui_thread_tid == unblock_pid
              AND sp.process_name = blj.proc_name
    ),
    summed_raw AS (
        SELECT SUM(unblock_timestamp-block_timestamp)*1000 AS sum_block_duration_ms,
               *
        FROM blocked_ui_threads
        GROUP BY unblock_pid
    ),
    summed_neat AS (
        SELECT sum_block_duration_ms AS blocked_iowait_duration_ms,
               process_name,
               (finished_timestamp - started_timestamp) * 1000 AS launching_duration_ms,
               started_timestamp * 1000 AS launching_started_timestamp_ms,
               finished_timestamp * 1000 AS launching_finished_timestamp_ms
                -- filter out the rest because its just selecting 1 arbitrary row (due to the SUM aggregate).,
        FROM summed_raw
    )
SELECT * FROM summed_neat;

SELECT * FROM blocked_iowait_for_app_launches;
