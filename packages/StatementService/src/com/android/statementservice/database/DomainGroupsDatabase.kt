/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.statementservice.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [DomainGroups::class], version = 1)
@TypeConverters(Converters::class)
abstract class DomainGroupsDatabase : RoomDatabase() {
    companion object {
        private const val DATABASE_NAME = "domain-groups"
        @Volatile
        private var instance: DomainGroupsDatabase? = null

        fun getInstance(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context,
                DomainGroupsDatabase::class.java, DATABASE_NAME
            ).build().also { instance = it }
        }
    }
    abstract fun domainGroupsDao(): DomainGroupsDao
}