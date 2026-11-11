# Fturn: тестовая сборка sing-box 1.14.4

Проверено 11 ноября 2026 года на Honor 8X (JSN-L21, Android 10, ARM64).
Основа приложения: 4.3.8. Версия APK: `4.3.8-sb1.14.4-debug`.
Пакет `com.litar.freeturn.sb1144`, название `Fturn SB 1.14.4`: отдельная установка и отдельные данные.

## Изменения

- Официальный исходный архив sing-box 1.14.4 закреплён SHA-256 `9037718f85b67a004517c6a9190b98c68483de643f57c62eb6c889e01beae4bc`.
- Обновлены gomobile bindings, запуск через CommandServer, поток журналов CommandClient, DNS iterator и platform callbacks.
- Остановка сначала вызывает `closeService()`, затем `close()`: без этого остаётся открытый cache.db и повторный запуск завершается timeout.
- Сертификаты Android загружает JNI-провайдер sing-box; ручной callback старого API удалён.
- Патчи WebSocket query и SOCKS UDP перенесены с проверкой исходных файлов и go.sum. Новый upstream UDP timeout сохранён.
- Для Android используется upstream-флаг линковщика `-checklinkname=0`.

## Проверки

- 211 JVM-тестов без ошибок.
- 13 нативных сценариев VLESS: TCP, TLS/uTLS, Vision, REALITY, WebSocket/early data, gRPC, отклонение неверного TLS SNI.
- Регрессионные тесты закрытия входящих и исходящих SOCKS UDP.
- На Honor 8X: фактическая версия libbox 1.14.4; конфигурации VLESS и WireGuard со всеми 8652 встроенными RU CIDR.
- На Honor 8X: два полных цикла запуска, передачи данных через Android TUN/VLESS и остановки в одном процессе.
- После проверки VPN-служба остановлена; временный adb reverse удалён.

Телефонная передача проверялась через локальный сервер на ПК и USB-ретранслятор вместо удалённого FreeTurn-сервера. Это проверка миграции ядра и Android VPN, а не замер скорости удалённого сервера или полный тест утечек DNS. WireGuard на телефоне проверен на уровне конфигурации; удалённый WireGuard handshake в этот сценарий не входил.

## Воспроизведение

Сначала `scripts/build-libbox.ps1`, затем:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug -PvpnFixtureRunner=com.freeturn.app.testing.SingBoxMigrationInstrumentation
./scripts/test-libbox.ps1
./scripts/test-socks-control.ps1
```

Для телефонного сценария скопировать `scripts/tests/vless_relay_test.go` в `.native/sing-box-1.14.4/experimental/libbox/fturn_vless_relay_test.go` (это также делает test-libbox.ps1), установить `FTURN_PHONE_SERVER=1` и запустить в исходниках sing-box:

```powershell
go test -v -tags with_gvisor,with_low_memory,with_clash_api,with_wireguard,with_utls,with_grpc ./experimental/libbox -run '^TestFturnPhoneServer$' -timeout 20m -count=1
```

После сообщения `PHONE_FIXTURE_READY` установить debug APK и androidTest APK, затем:

```powershell
adb reverse tcp:19000 tcp:19000
adb shell appops set com.litar.freeturn.sb1144 ACTIVATE_VPN allow
adb shell am instrument -w com.litar.freeturn.sb1144.test/com.freeturn.app.testing.SingBoxMigrationInstrumentation
adb reverse --remove tcp:19000
```

Сетевой probe запускается от shell UID: собственное исключение VPN-приложения не может скрыть неработающий TUN. Все ключи и UUID в тестах синтетические. Локальный сервер нужно остановить после проверки. Релиз/тег для этой тестовой версии не публикуется.
