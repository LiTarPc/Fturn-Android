<div align="center">

# Fturn

[![Core](https://img.shields.io/badge/Core-fturn--core-blue?logo=github&logoColor=white)](https://github.com/LiTarPc/fturn-core)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)
![Material 3](https://img.shields.io/badge/Material-3-757575?logo=materialdesign&logoColor=white)
![License](https://img.shields.io/badge/license-GPL--3.0-blue)

</div>

> **Disclaimer.** Проект предназначен **исключительно для образовательных и исследовательских целей.**

---

##  Возможности

- **Современный UI**: минималистичный дизайн, контрастная палитра и мониторинг скорости трафика (скачивание / выгрузка) в реальном времени.
- **VPN через sing-box 1.12.0**: WireGuard и VLESS TCP через транспорт FreeTurn, импорт ссылок с полями `wg` или `sb`.
- **Обход RU**: встроенный список IPdeny IPv4 включён по умолчанию, доступно обновление и импорт `.srs`, `.zone` и `.txt`.
- **Раздельное туннелирование (Split Tunneling)**: российские приложения из встроенного списка и пакеты с отдельным сегментом `ru` исключены из VPN по умолчанию. Галочки можно менять вручную.
- **IPv4**: DNS и пользовательский VPN-трафик используют IPv4; IPv6 отклоняется внутри VPN.
- **Одно уведомление**: общий статус подключения и статистика туннеля.
- **Интеграция с ядром [fturn-core](https://github.com/LiTarPc/fturn-core)**: быстрая работа с протоколами и автоматическая сборка.

---

##  Требования

- **Android 7.0+** (API 24+)
- **Архитектура процессора:** `arm64-v8a`
- **Ядро:** отдельный `client-android-arm64` версии `v4.1.3`, загружаемый при сборке
- **Сервер (VPS)** с установленным ядром `fturn-core`

---

## 🤝 Благодарности

- **[@samosvalishe](https://github.com/samosvalishe)** — оригинальные проекты [free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy) и [turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android)
- **[@Moroka8](https://github.com/Moroka8)** — форк ядра [vk-turn-proxy](https://github.com/Moroka8/vk-turn-proxy)
- **[@alexmac6574](https://github.com/alexmac6574)** — форк ядра [vk-turn-proxy](https://github.com/alexmac6574/vk-turn-proxy)
- **[@cacggghp](https://github.com/cacggghp)** — оригинальное [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy)
- **[@MYSOREZ](https://github.com/MYSOREZ)** — оригинальный Android-клиент [vk-turn-proxy-android](https://github.com/MYSOREZ/vk-turn-proxy-android)
