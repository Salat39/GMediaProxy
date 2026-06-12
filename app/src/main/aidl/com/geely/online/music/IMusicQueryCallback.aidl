package com.geely.online.music;

interface IMusicQueryCallback {
    void onSuccess(int code, String result);
    void onError(int code);
}
