package com.tencent.wecarflow.control.data;

import android.os.Parcel;
import android.os.Parcelable;

public class LoginUserInfo implements Parcelable {
    public String uid;
    public boolean isLogin;
    public boolean isExpired;
    public String nickName;
    public String avatarUrl;

    public LoginUserInfo() {
    }

    protected LoginUserInfo(Parcel in) {
        isLogin = in.readByte() != 0;
        isExpired = in.readByte() != 0;
        nickName = in.readString();
        avatarUrl = in.readString();
    }

    public boolean isLogin() {
        return isLogin;
    }

    public void setLogin(boolean login) {
        isLogin = login;
    }

    public boolean isExpired() {
        return isExpired;
    }

    public void setExpired(boolean expired) {
        isExpired = expired;
    }

    public String getNickName() {
        return nickName;
    }

    public void setNickName(String nickName) {
        this.nickName = nickName;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (isLogin ? 1 : 0));
        dest.writeByte((byte) (isExpired ? 1 : 0));
        dest.writeString(nickName);
        dest.writeString(avatarUrl);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<LoginUserInfo> CREATOR = new Creator<LoginUserInfo>() {
        @Override
        public LoginUserInfo createFromParcel(Parcel in) {
            return new LoginUserInfo(in);
        }

        @Override
        public LoginUserInfo[] newArray(int size) {
            return new LoginUserInfo[size];
        }
    };
}
