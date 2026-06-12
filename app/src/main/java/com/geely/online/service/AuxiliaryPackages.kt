package com.geely.online.service

object AuxiliaryPackages {
    const val MURGLAR = "com.badmanners.murglar2"
    const val YANDEX_MUSIC = "ru.yandex.music"
    const val VKX = "ua.itaysonlab.vkx"
    const val HAVA_YM = "yandex.auto.music"
    const val STRELKA = "air.StrelkaSDFREE"

    const val ANDROID_BLUETOOTH = "com.android.bluetooth"
    const val GEELY_USB_SERVICE = "com.geely.usbservice"
    const val GEELY_RADIO_SERVICE = "com.geely.radio.service"
    const val AUTOKIT_KARAOKE = "com.autokit.karaokeradioplayer"

    val NATIVE_AUDIO_SESSION_PACKAGES: Set<String> = setOf(
        ANDROID_BLUETOOTH,
        GEELY_USB_SERVICE,
        GEELY_RADIO_SERVICE,
        AUTOKIT_KARAOKE,
    )

    val BURST_SYNC_MEDIA_SESSION_PACKAGES: Set<String> = setOf(
        STRELKA
    )
}
