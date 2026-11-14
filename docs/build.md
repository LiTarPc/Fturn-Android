# Сборка Fturn из исходников

Обычному пользователю достаточно [готового APK](https://github.com/LiTarPc/Fturn-Android/releases). Это руководство — для разработчика.

## Инструменты

- JDK 17.
- Python 3 и Git.
- Go 1.26.11; сборочные скрипты отдельно выбирают закреплённые версии Go для компонентов.
- Android SDK: платформа `android-37.0`, build-tools `36.0.0`, NDK `28.2.13676358`.
- Для JavaScript-регрессий — Node.js с глобальными `Request`, `Response` и `Blob`.

Gradle 9.5.1 загружается wrapper-скриптом. Настройте `JAVA_HOME`, путь Android SDK в `local.properties` и Go/Python в PATH. Windows-скрипт libbox использует стандартный SDK в `%LOCALAPPDATA%/Android/Sdk`. В Linux задайте `ANDROID_HOME` и убедитесь, что NDK установлен.

Для Windows используйте подходящий 64-разрядный Go; случайный старый `windows/386` из PATH не является закреплённым инструментом проекта.

## Закреплённые компоненты

| Компонент | Источник версии | Инструмент |
|---|---|---|
| FTcore 4.2.6 | `freeturn-core.properties` и `third_party/freeturn-core/SOURCE.json` | Go 1.26.11 |
| RAW 1.1.0-captcha.1 | `raw-core.properties` и `third_party/raw-core/SOURCE.json` | Go 1.26.10 |
| sing-box 1.14.4 | `scripts/build-libbox.*` и патчи в `scripts` | Go 1.26.10 |

Оба клиентских ядра собираются из сохранённых исходников при Gradle preBuild. `SOURCE.json` проверяет каждый входной файл и общий snapshot. Версии и SHA-256 бинарников включаются в APK как `freeturn-core.json` и `raw-core.json`.

Для RAW версия `1.1.0-captcha.1` отличает Android-вариант с исправлением ресурсов капчи от опубликованного upstream `1.1.0`. Его исходный commit и локальное изменение номера записаны в manifest.

## Debug-сборка

Сначала соберите libbox с исправлениями SOCKS5 и входом FOU.

Windows / PowerShell:

```powershell
./scripts/build-libbox.ps1
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PrawBuild=false
```

Linux:

```sh
bash scripts/build-libbox.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug -PrawBuild=false
```

AAR создаётся в `app/libs/libbox.aar`. Исходники зависимостей и кэши находятся в `.native` и исключены из Git. APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.

Обычный debug имеет пакет `com.litar.freeturn.routerip` и название «Fturn IP-туннель TEST». С `-PrawBuild=true` используется `com.litar.freeturn.raw.routerip`. Эти пакеты отделены от релизного `com.litar.freeturn`; `rawBuild` меняет идентичность тестового приложения, а не убирает другое ядро из APK.

Параметры `coreFetch=all/none` из старых инструкций больше не управляют поставкой ядер и для текущей сборки не нужны.

## Проверки

После сборки libbox:

```sh
node scripts/tests/captcha_resource_paths.mjs
node third_party/raw-core/internal/provider/vk/internal/captcha/manual/inject_fixture_test.cjs
node third_party/raw-core/internal/provider/vk/internal/captcha/manual/resource_paths_test.mjs
```

Выполните JVM-тесты до нативных VLESS-регрессий: они создают конфиги в `app/build/vless-fixtures`.

```sh
./gradlew :app:testDebugUnitTest
bash scripts/test-libbox.sh
bash scripts/test-socks-control.sh
```

На Windows замените shell-скрипты соответствующими `.ps1`. Нативные VLESS-тесты используют тот же флаг `-checklinkname=0`, что сборка libbox: он нужен используемому runtime hook и не выключает тесты. FOU проверяется в 20 повторах. Linux CI дополнительно запускает проверки гонок и интеграции TURN/RAW.

Instrumentation-сценарии находятся в `app/src/androidTest`. Они запускаются явно через `vpnFixtureRunner`; приватные данные профилей никогда не встраиваются в APK. `FullConnectionInstrumentation` поддерживает `activeProfile=true` для проверки сохранённого профиля и `routerOnly=true` для IP-туннеля без SOCKS5. Это средство разработчика, а не пользовательская настройка.

## Подписанная сборка и релиз

Release имеет пакет `com.litar.freeturn`. Для локальной подписи нужен собственный `keystore.properties` по примеру в корне; ключ проекта в репозитории не хранится. APK с чужой подписью не обновляет установленный официальный Fturn.

Штатная цепочка: PR CI → merge в `main` → тег `vX.Y.Z` → workflow Release. Отдельный workflow Signed APK создаёт подписанный артефакт без публикации релиза.

Сборка проверяет версию, пакет, сертификат, исходные manifests и хеши встроенных ядер. В выпуск входят APK, SHA256SUMS, сведения о происхождении ядер, исходные snapshot-архивы, лицензии и архив помощника OpenWrt.

`app/libs/libbox.aar`, скомпилированные ядра, файлы подписи и `.native` не коммитятся. При обновлении исходников ядра сначала пересмотрите manifest и его контрольные суммы; обход проверки исходников не является способом обновления.
