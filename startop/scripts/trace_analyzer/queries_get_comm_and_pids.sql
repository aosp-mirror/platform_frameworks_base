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

DROP VIEW IF EXISTS sched_switch_next_comm_pids;

CREATE VIEW IF NOT EXISTS sched_switch_next_comm_pids AS

-- TODO: switch to using sched_switches table.

WITH
    sched_switchs AS (
        SELECT * FROM raw_ftrace_entries WHERE function = 'sched_switch' AND function_args LIKE '% next_pid=%' AND function_args NOT LIKE '% next_comm=main %'
    ),
    comm_and_pids_raws AS (
        SELECT id,
               SUBSTR(function_args, instr(function_args, "next_comm="), instr(function_args, "next_pid=") - instr(function_args, "next_comm=")) AS next_comm_raw,
               SUBSTR(function_args, instr(function_args, "next_pid="), instr(function_args, "next_prio=") - instr(function_args, "next_pid=")) AS next_pid_raw
        FROM sched_switchs
    ),
    comm_and_pids AS (
        SELECT id,
               id AS raw_ftrace_entry_id,
               TRIM(SUBSTR(next_pid_raw, 10)) AS next_pid, -- len("next_pid=") is 10
               TRIM(SUBSTR(next_comm_raw, 11)) AS next_comm -- len("next_comm=") is 11
        FROM comm_and_pids_raws
    )
SELECT * from comm_and_pids;

SELECT * from sched_switch_next_comm_pids;
