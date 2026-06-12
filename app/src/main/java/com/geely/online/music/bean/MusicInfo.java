package com.geely.online.music.bean;

import android.os.Parcel;
import android.os.Parcelable;

public class MusicInfo implements Parcelable {
    public String albumName;
    public long currentPlayTime;
    public boolean hasProgress = true;
    public String id;
    public boolean isFavor;
    public boolean isFavorAble;
    public String mediaType;
    public String musicAuthor;
    public String musicName;
    public String musicPic;
    public int playMode;
    public long totalPlayTime;
    public String unplayCode;

    public MusicInfo() {
    }

    protected MusicInfo(Parcel in) {
        musicName = in.readString();
        musicPic = in.readString();
        musicAuthor = in.readString();
        totalPlayTime = in.readLong();
        currentPlayTime = in.readLong();
        unplayCode = in.readString();
        id = in.readString();
        isFavorAble = in.readByte() != 0;
        isFavor = in.readByte() != 0;
        hasProgress = in.readByte() != 0;
        albumName = in.readString();
        playMode = in.readInt();
        mediaType = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(musicName);
        dest.writeString(musicPic);
        dest.writeString(musicAuthor);
        dest.writeLong(totalPlayTime);
        dest.writeLong(currentPlayTime);
        dest.writeString(unplayCode);
        dest.writeString(id);
        dest.writeByte((byte) (isFavorAble ? 1 : 0));
        dest.writeByte((byte) (isFavor ? 1 : 0));
        dest.writeByte((byte) (hasProgress ? 1 : 0));
        dest.writeString(albumName);
        dest.writeInt(playMode);
        dest.writeString(mediaType);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<MusicInfo> CREATOR = new Creator<MusicInfo>() {
        @Override
        public MusicInfo createFromParcel(Parcel in) {
            return new MusicInfo(in);
        }

        @Override
        public MusicInfo[] newArray(int size) {
            return new MusicInfo[size];
        }
    };
}
