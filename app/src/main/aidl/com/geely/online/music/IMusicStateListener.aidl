package com.geely.online.music;

import com.geely.online.music.bean.MusicInfo;

interface IMusicStateListener {
    void onMediaDataChanged();
    void onProgressChanged(long position, long duration, String source, String displayId);
    void onPlayStateChanged(int state, String source, String displayId);
    void onPlayListChanged(String source, String displayId);
    void onFavorStateChanged(boolean isFavor, String source, String id, String displayId);
    void onLrcLoad(String lrc, long duration, String source, String displayId);
    void onPlayModeChange(int playMode, String source, String displayId);
    void onAudioFocusChange(int focusChange, String source, String displayId);
    void onContentListResult(String result);
    void onMediaChange(in MusicInfo musicInfo, String source, String displayId);
    void onSourceChange(String source, String displayId);
    void onSearchResult(in List result, String source);
    void onAppChanged(boolean isInApp, String source);
    void onAppDied(String source);
}
