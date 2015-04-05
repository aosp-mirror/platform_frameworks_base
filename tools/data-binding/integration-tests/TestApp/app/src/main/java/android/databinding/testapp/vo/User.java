package android.databinding.testapp.vo;

import android.databinding.Bindable;

public class User {
    @Bindable
    private User friend;
    @Bindable
    private String name;
    @Bindable
    private String fullName;

    public User getFriend() {
        return friend;
    }

    public void setFriend(User friend) {
        this.friend = friend;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }
}
