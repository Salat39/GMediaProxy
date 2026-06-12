package com.geely.online.music;

import com.geely.online.music.bean.MusicInfo;

interface IMusicInfoListener {
    void onMusicListResult(in List musicList, String source, String displayId);
    void onMusicCurrentMediaInfoResult(in MusicInfo musicInfo, String source, String displayId);
}
