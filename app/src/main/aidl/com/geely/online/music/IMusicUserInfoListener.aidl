package com.geely.online.music;

import com.tencent.wecarflow.control.data.LoginUserInfo;

interface IMusicUserInfoListener {
    void onUserInfoResult(in LoginUserInfo userInfo);
}
