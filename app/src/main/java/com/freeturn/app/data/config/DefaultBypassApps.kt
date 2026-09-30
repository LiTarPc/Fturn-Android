package com.freeturn.app.data.config

import com.freeturn.app.data.toPackageSet

val DEFAULT_BYPASS_APPS: Set<String> = linkedSetOf(
    // Маркетплейсы и доставка
    "ru.wildberries.buyer",
    "ru.ozon.app.android",
    "ru.sbermegamarket.app",
    "ru.samokat.app",
    "ru.yandex.market",
    "ru.yandex.eda",
    "ru.yandex.taxi",
    // Карты, сервисы Яндекса
    "ru.dublgis.dgismobile",
    "com.yandex.browser",
    "ru.yandex.yandexmaps",
    "ru.yandex.music",
    "ru.kinopoisk",
    // VK / соцсети / медиа
    "ru.oneme.app",
    "ru.vk.store",
    "ru.ok.android",
    "com.vkontakte.android",
    "ru.vk.video",
    "ru.rutube.app",
    "com.zen.android",
    // Банки и платежи
    "ru.sberbankmobile",
    "com.tinkoff.itinkoff",
    "ru.tbank.mobile",
    "ru.alfabank.mobile.android",
    "ru.vtb24.mobileandroid",
    "ru.nspk.mirpay",
    // Госуслуги и гос-сервисы
    "ru.gosuslugi.app",
    "ru.rzd.pass",
    "ru.mos.parking",
    // Телеком
    "ru.megafon.mlk",
    "ru.beeline.services",
    "ru.tele2.mytele2",
    // Классифайды и работа
    "com.avito.android",
    "ru.hh.android",
    "ru.cian.main",
    "ru.mail.mailapp",
    // Прочее / антицензура / вендорные оболочки
    "com.notcvnt.rknhardering",
    "com.miui.securitycenter",
    "com.miui.securityadd",
    "com.huawei.systemmanager",
    "com.samsung.android.securitymanager"
)

/** Match package segments, not substrings such as truecaller or crunchyroll. */
fun isRussianPackage(packageName: String): Boolean =
    packageName.split('.').any { it.equals("ru", ignoreCase = true) }

/** One selection policy for the checkbox list and the Android VPN builder. */
fun splitTunnelSelection(
    mode: String,
    apps: String,
    installedPackages: Iterable<String> = emptyList(),
    useDefaults: Boolean = apps.isBlank()
): Set<String> {
    val selected = apps.toPackageSet()
    return if (selected.isEmpty() && useDefaults && mode == SplitTunnelMode.EXCLUDE) {
        DEFAULT_BYPASS_APPS + installedPackages.filter(::isRussianPackage)
    } else selected
}
