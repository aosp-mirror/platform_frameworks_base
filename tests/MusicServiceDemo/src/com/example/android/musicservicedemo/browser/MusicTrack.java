/*
 * Copyright (C) 2014 Google Inc. All Rights Reserved.
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

package com.example.android.musicservicedemo.browser;

/**
 * A class to model music track metadata.
 */
public class MusicTrack {

    private static final String TAG = "MusicTrack";

    private String mTitle;
    private String mAlbum;
    private String mArtist;
    private String mGenre;
    private String mSource;
    private String mImage;
    private int mTrackNumber;
    private int mTotalTrackCount;
    private int mDuration;

    /**
     * Constructor creating a MusicTrack instance.
     *
     * @param title
     * @param album
     * @param artist
     * @param genre
     * @param source
     * @param image
     * @param trackNumber
     * @param totalTrackCount
     * @param duration
     */
    public MusicTrack(String title, String album, String artist, String genre, String source,
            String image, int trackNumber, int totalTrackCount, int duration) {
        this.mTitle = title;
        this.mAlbum = album;
        this.mArtist = artist;
        this.mGenre = genre;
        this.mSource = source;
        this.mImage = image;
        this.mTrackNumber = trackNumber;
        this.mTotalTrackCount = totalTrackCount;
        this.mDuration = duration;
    }

    public String getTitle() {
        return mTitle;
    }

    public void setTitle(String mTitle) {
        this.mTitle = mTitle;
    }

    public String getAlbum() {
        return mAlbum;
    }

    public void setAlbum(String mAlbum) {
        this.mAlbum = mAlbum;
    }

    public String getArtist() {
        return mArtist;
    }

    public void setArtist(String mArtist) {
        this.mArtist = mArtist;
    }

    public String getGenre() {
        return mGenre;
    }

    public void setGenre(String mGenre) {
        this.mGenre = mGenre;
    }

    public String getSource() {
        return mSource;
    }

    public void setSource(String mSource) {
        this.mSource = mSource;
    }

    public String getImage() {
        return mImage;
    }

    public void setImage(String mImage) {
        this.mImage = mImage;
    }

    public int getTrackNumber() {
        return mTrackNumber;
    }

    public void setTrackNumber(int mTrackNumber) {
        this.mTrackNumber = mTrackNumber;
    }

    public int getTotalTrackCount() {
        return mTotalTrackCount;
    }

    public void setTotalTrackCount(int mTotalTrackCount) {
        this.mTotalTrackCount = mTotalTrackCount;
    }

    public int getDuration() {
        return mDuration;
    }

    public void setDuration(int mDuration) {
        this.mDuration = mDuration;
    }

    public String toString() {
        return mTitle;
    }

}
