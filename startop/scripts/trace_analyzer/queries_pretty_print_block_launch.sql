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

.headers on
.mode quote

SELECT * FROM blocked_iowait_for_app_launches;

/*
Output as CSV example:

'blocked_iowait_duration_ms','process_name','launching_duration_ms','launching_started_timestamp_ms','launching_finished_timestamp_ms'
125.33199995596078224,'com.android.settings',1022.4840000009862706,17149896.822000000626,17150919.305999998003

*/
