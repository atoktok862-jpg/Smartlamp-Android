# Smart Lamp (Android)

Нативное Android-приложение (Kotlin) для управления BLE-лампой/лентой (ELK-BLEDDM / ELK-BLEDOM). Интерфейс работает офлайн в WebView на базе ассетов из проекта `D:\SmartLamp` (`mobile_app`).

## Возможности
- Оффлайн UI в `app/src/main/assets/` (HTML/CSS/JS, палитры, эффекты, таймеры).
- WebView: JavaScript, DOM Storage (настройки/пресеты), доступ к локальным файлам, `usesCleartextTraffic` для локальных HTTP/WebSocket.
- Нативный BLE-мост `AndroidBle` в `MainActivity`: сканирование и подключение к устройству, запись команд, автоподключение к сохранённому устройству и переподключение при потере связи.
- Векторная иконка приложения (светящаяся лампа).
- Сборка debug APK через GitHub Actions.

## BLE
- Имена устройств: `ELK-BLEDDM`, `ELK-BLEDOM`, `ELK-*`
- Service UUID: `0000fff0-0000-1000-8000-00805f9b34fb`
- Write characteristic: `0000fff3-0000-1000-8000-00805f9b34fb`
- Формат команд: `7e ... ef` (вкл/выкл, цвет, яркость, скорость, эффекты)

## Основные файлы
- `app/src/main/java/com/smartlamp/app/MainActivity.kt` — WebView и нативная BLE-логика, JS-мост `AndroidBle`.
- `app/src/main/AndroidManifest.xml` — разрешения BLE/сети, параметры приложения.
- `app/src/main/assets/index.html` — оффлайн интерфейс.
- `app/src/main/res/layout/activity_main.xml` — разметка с WebView.
- `app/src/main/res/drawable/ic_launcher_*` и `mipmap-anydpi-v26/ic_launcher*.xml` — векторная иконка.
- `.github/workflows/build_apk.yml` — CI-сборка APK.

## Сборка (GitHub Actions)
Workflow: `.github/workflows/build_apk.yml`
- `actions/checkout@v4`
- `actions/setup-java@v4` (Temurin 17)
- `gradle/actions/setup-gradle@v4` (`gradle-version: 8.9`)
- `gradle assembleDebug`
- Артефакт: `app/build/outputs/apk/debug/app-debug.apk`

## Примечания
- Проект расположен в `D:\SmartLampAndroid`; исходный `D:\SmartLamp` используется как источник и не изменяется.
- Интеграция `assets/index.html` с нативным мостом `AndroidBle` (полный переход UI на нативное BLE-управление/автоподключение в WebView) требует завершения.
