# Suno Downloader for Android

Скачивание публичных треков Suno на Android 10+ (API 29+).

## Возможности
- Скачивание по ссылке `https://suno.com/s/...` или `https://suno.com/song/...`
- Конвертация в MP3 256 kbps (если установлен FFmpeg)
- Вшитая обложка (ID3v2.3)
- Тёмная тема, минималистичный интерфейс
- Файлы сохраняются в `Music/Suno/`

## Требования для сборки

- **Android Studio** (Hedgehog или новее)
- **JDK 17** (встроен в Android Studio)
- **Android SDK 34**

## Сборка APK

### Вариант 1: Через Android Studio (проще)

1. Откройте папку `android/` в Android Studio
2. Дождитесь синхронизации Gradle
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. Готовый APK: `app/build/outputs/apk/debug/app-debug.apk`

### Вариант 2: Через командную строку

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Для Windows:
```bat
cd android
gradlew.bat assembleDebug
```

### Установка на телефон

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Или просто скопируйте APK на телефон и откройте его.

## FFmpeg (опционально)

Без FFmpeg программа сохраняет оригинальный M4A (Opus ~128 kbps).  
С FFmpeg — конвертирует в MP3 256 kbps с обложкой.

Чтобы добавить FFmpeg:

1. Скачайте [ffmpeg-android](https://github.com/kali-linux/ffmpeg-binaries) или соберите сами
2. Поместите `ffmpeg` (без .exe) в корень приложения:
   ```
   adb push ffmpeg /sdcard/Download/
   ```
3. В приложении авторизуйтесь через Termux:
   ```bash
   cp /sdcard/Download/ffmpeg /data/local/bin/
   chmod +x /data/local/bin/ffmpeg
   ```

Альтернатива: положите `ffmpeg` в папку приложения через файловый менеджер.

## Как работает

1. Приложение открывает `usesuno.com/tools/downloader/` во встроенном WebView
2. Ждёт прохождения Cloudflare challenge (~5-20 сек)
3. Вызывает `window.SunoAudioDecoder.getAudio(songId)` — встроенный декодер страницы
4. Получает дешифрованный M4A Blob
5. Параллельно получает название трека через `/api/proxy`
6. Скачивает обложку с `cdn2.suno.ai`
7. Конвертирует в MP3 через FFmpeg (если доступен)
8. Сохраняет в `Music/Suno/`

## Структура проекта

```
android/
├── build.gradle.kts          # Корневой Gradle
├── settings.gradle.kts       # Настройки проекта
└── app/
    ├── build.gradle.kts      # Зависимости модуля
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/sunodownloader/
        │   └── MainActivity.kt    # Вся логика
        └── res/
            ├── layout/activity_main.xml
            ├── drawable/
            │   ├── card_bg.xml
            ├── input_bg.xml
            ├── btn_accent_bg.xml
            └── btn_outline_bg.xml
            └── values/themes.xml
```
