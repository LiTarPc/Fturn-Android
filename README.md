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

## Формат ссылок для импорта

Описание относится к **Fturn v4.0.0**. Рекомендуемый формат:

```text
freeturn://BASE64URL_JSON
```

Порядок подготовки: создать JSON-профиль, сериализовать его в UTF-8, закодировать **весь JSON** в Base64URL, убрать завершающие `=` и добавить префикс `freeturn://`. Base64URL использует `-` и `_` вместо `+` и `/`. Полученная ссылка должна занимать одну строку, без внутренних пробелов и переносов.

Импорт также принимает обычный Base64 (`+`, `/`, с `=` или без него) и строку Base64 без префикса при вставке или сканировании QR. Для открытия ссылки через Android используйте `freeturn://`. Не кодируйте результат повторно, не добавляйте кавычки вокруг всей ссылки и не применяйте URL-кодирование к её Base64-части. Максимальная длина входной строки — 262 144 символа.

### Поля JSON-профиля

| Поле | Назначение |
|---|---|
| `v` | Версия формата: `1`. Если поле отсутствует, используется `1`. |
| `provider` | Обязательное непустое поле; поддерживаемый провайдер приложения — `"vk"`. |
| `peer` | Обязательный адрес **сервера FreeTurn** в виде `IPv4:порт` или `домен:порт`. Это не адрес VLESS-сервера. |
| `name` | Необязательное название профиля. |
| `links` | Ссылка на звонок VK. Если её нет в JSON, нужно заполнить её в окне импорта перед сохранением. Для подключения нужна действующая ссылка. |
| `sb` | Обычная строка `vless://…` для VLESS. Отдельно кодировать её в Base64 не нужно. |
| `wg` | Текст конфигурации WireGuard (`[Interface]`, `[Peer]` и их параметры). |
| `transport` | Транспорт FreeTurn: `"tcp"` или `"udp"`; по умолчанию TCP. Параметр `type` внутри VLESS — отдельная настройка. |
| `mode` | `"tcp"` включает TCP-перенаправление FreeTurn. Для `sb` приложение включает его автоматически, для `wg` использует UDP-перенаправление. |
| `obf`, `key` | Если на сервере включена обфускация: профиль `rtpopus`, `rtpopus2` или `rtpopus3` и соответствующий ключ из 64 шестнадцатеричных символов. |
| `cid` | При необходимости — ID клиента из 32 строчных шестнадцатеричных символов. |
| `listen` | Необязательный локальный адрес FreeTurn; для VPN — `127.0.0.1:порт`. По умолчанию `127.0.0.1:9000`. |
| `n`, `spc` | Необязательные числовые настройки потоков и streams-per-credential FreeTurn. |

В одном профиле указывайте **только один VPN**: `sb` **или** `wg`. Два непустых поля одновременно отклоняются. Обфускация, ключ, ID клиента и параметры транспорта должны соответствовать настройкам вашего сервера.

### Пример VLESS-профиля

Сохраните следующий JSON как `profile.json` и закодируйте его целиком:

```json
{
  "v": 1,
  "name": "VLESS TCP",
  "provider": "vk",
  "peer": "192.0.2.1:56411",
  "transport": "tcp",
  "mode": "tcp",
  "links": "https://vk.ru/call/join/EXAMPLE",
  "sb": "vless://11111111-2222-4333-8444-555555555555@127.0.0.1:8443?encryption=none&security=none&type=tcp#VLESS_TCP"
}
```

Это пример структуры для импорта: адрес `192.0.2.1`, ссылка звонка и UUID — тестовые, их нужно заменить вашими данными. Если сервер использует обфускацию, добавьте его `obf` и `key`.

В v4.0.0 поле `sb` поддерживает **только VLESS TCP без TLS**:

- UUID должен быть в формате `xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`, адрес непустым, порт — от 1 до 65535.
- Допустимые параметры запроса: `encryption=none`, `security=none`, `type=tcp`. Их можно опустить: эти значения используются по умолчанию. Повторять один параметр нельзя.
- Дополнительные параметры, включая `sni`, `fp`, `pbk`, `sid`, `flow`, `host`, `path` и `serviceName`, пока отклоняются. TLS, REALITY, WebSocket и gRPC пока не поддерживаются.
- После `#` можно указать название; специальные символы названия кодируйте как часть URI. Например, пробел — `%20`.
- Обычная самостоятельная ссылка `vless://…` сейчас не является форматом общего импорта: её нужно поместить в поле `sb` профиля FreeTurn.

При запуске адрес и порт из `sb` заменяются на локальный адрес FreeTurn (`listen`). Удалённая сторона FreeTurn должна передавать соединение к соответствующему VLESS-сервису. Замена в ссылке `security=reality` или `tls` на `none` не перенастраивает сервер и не делает его совместимым с этой версией.

### Пример WireGuard-профиля

```json
{
  "v": 1,
  "name": "WireGuard",
  "provider": "vk",
  "peer": "192.0.2.1:56411",
  "links": "https://vk.ru/call/join/EXAMPLE",
  "wg": "[Interface]\nPrivateKey = AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=\nAddress = 10.13.13.2/32\nDNS = 1.1.1.1\n\n[Peer]\nPublicKey = ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A=\nAllowedIPs = 0.0.0.0/0\nEndpoint = 127.0.0.1:9000\nPersistentKeepalive = 25"
}
```

Адрес сервера, ссылка звонка и оба WG-ключа в примере — тестовые. Используйте ваш действующий конфиг. Значение `wg` — строка: в JSON переносы строк записываются как `\n`, а после разбора JSON становятся настоящими переносами. Удобнее читать `.conf` как текст и передавать его в JSON-сериализатор; вручную экранировать переносы перед `json.dumps` не нужно.

### Как получить готовую ссылку

Для приведённых примеров сохраните выбранный JSON в `profile.json`, затем выполните Python 3:

```python
import base64
import json
from pathlib import Path

profile = json.loads(Path("profile.json").read_text(encoding="utf-8-sig"))
payload = json.dumps(profile, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
encoded = base64.urlsafe_b64encode(payload).decode("ascii").rstrip("=")
print("freeturn://" + encoded)
```

Если формируете WireGuard-профиль программно, задавайте поле так: `profile["wg"] = Path("client.conf").read_text(encoding="utf-8-sig")`, затем сериализуйте весь профиль тем же способом.

Вставьте полученную строку целиком в приложение или поместите **всю ссылку** в QR-код. Base64 — способ кодирования, он не шифрует UUID, WG-ключи или ключ обфускации; публикуйте только примеры с тестовыми данными.

---

## 🤝 Благодарности

- **[@samosvalishe](https://github.com/samosvalishe)** — оригинальные проекты [free-turn-proxy](https://github.com/samosvalishe/free-turn-proxy) и [turn-proxy-android](https://github.com/samosvalishe/turn-proxy-android)
- **[@Moroka8](https://github.com/Moroka8)** — форк ядра [vk-turn-proxy](https://github.com/Moroka8/vk-turn-proxy)
- **[@alexmac6574](https://github.com/alexmac6574)** — форк ядра [vk-turn-proxy](https://github.com/alexmac6574/vk-turn-proxy)
- **[@cacggghp](https://github.com/cacggghp)** — оригинальное [vk-turn-proxy](https://github.com/cacggghp/vk-turn-proxy)
- **[@MYSOREZ](https://github.com/MYSOREZ)** — оригинальный Android-клиент [vk-turn-proxy-android](https://github.com/MYSOREZ/vk-turn-proxy-android)
