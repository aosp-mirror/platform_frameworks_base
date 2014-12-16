package com.android.example.bindingdemo.vo;

import android.graphics.Color;

import com.android.databinding.library.BaseObservable;

import java.util.Objects;

public class User extends BaseObservable {
    private String name;
    private String lastName;
    private int photoResource = 0;
    private int favoriteColor = Color.RED;
    private int group;
    public static final int TOOLKITTY = 1;
    public static final int ROBOT = 2;

    public User(String name, String lastName, int photoResource, int group) {
        this.name = name;
        this.lastName = lastName;
        this.photoResource = photoResource;
        this.group = group;
    }

    public void setGroup(int group) {
        if (this.group == group) {
            return;
        }
        this.group = group;
        fireChange("group");
    }

    public int getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (Objects.equals(name, this.name)) {
            return;
        }
        this.name = name;
        fireChange("name");
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        if (Objects.equals(lastName, this.lastName)) {
            return;
        }
        this.lastName = lastName;
        fireChange("lastName");
    }

    public int getPhotoResource() {
        return photoResource;
    }

    public void setPhotoResource(int photoResource) {
        if (this.photoResource == photoResource) {
            return;
        }
        this.photoResource = photoResource;
        fireChange("photoResource");
    }

    public int getFavoriteColor() {
        return favoriteColor;
    }
}
