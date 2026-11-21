# Suno Downloader — Android (64-bit)

Нативное Android-приложение для скачивания публичных треков Suno в M4A/MP3.

## Требования для сборки

- **Android Studio** (Hedgehog или новее) + SDK 34
- **JDK 17** (встроен в Android Studio)
- **NDK** не требуется (криптография через `javax.crypto`, FFmpeg не используется)

## Сборка APK (64-bit)

### Способ 1: Через Android Studio

1. Откройте папку `android-project` в Android Studio
2. Дождитесь синхронизации Gradle
3. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. Готовые APK:
   - `app/build/outputs/apk/release/app-arm64-v8a-release.apk` (для телефонов)
   - `app/build/outputs/apk/release/app-x86_64-release.apk` (для эмуляторов)

### Способ 2: Через командную строку

```bash
cd android-project
./gradlew assembleRelease        # Linux/Mac
gradlew.bat assembleRelease       # Windows
```

APK появятся в `app/build/outputs/apk/release/`.

### Подпись release-APK

Для установки на устройство нужно подписать APK:

```bash
# Создать keystore (один раз)
keytool -genkeypair -v -keystore suno.jks -alias suno -keyalg RSA -keysize 2048 -validity 10000

# Подписать
apksigner sign --ks suno.jks app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

Или настроить в `app/build.gradle`:
```groovy
signingConfigs {
    release {
        storeFile file("../suno.jks")
        storePassword "..."
        keyAlias "suno"
        keyPassword "..."
    }
}
buildTypes {
    release {
        signingConfig signingConfigs.release
    }
}
```

## Архитектура

| Файл | Назначение |
|---|---|
| `MainActivity.kt` | Вся логика: UI, HTTP, криптография, сохранение |
| `activity_main.xml` | Layout (тёмная тема, карточки) |
| `drawable/*.xml` | Фоны кнопок и карточек |

## Алгоритм (идентичен десктопной v4)

1. **Resolve** — короткая ссылка `/s/...` → HEAD-запрос → UUID из Location
2. **Title** — `GET studio-api-prod.suno.com/api/oembed?url=...`
3. **Rights** — `POST studio-api-prod.suno.com/api/mango/rights` → `{key, iv, glt}`
4. **Download** — `GET d2lwuy8qc234o3.cloudfront.net/1/clip/<uuid>.m4a`
5. **Decrypt**:
   - `userKey = SHA-256(glt)`
   - `contentKey = AES-GCM-decrypt(userKey, nonce, ct||tag, AAD=uuid)`
   - `contentIv = AES-GCM-decrypt(userKey, nonce, ct||tag, AAD=uuid)`
   - `audio = AES-CTR-decrypt(contentKey, contentIv, encrypted)` — ручная реализация через ECB
6. **Save** — M4A (оригинал) через SAF или `Downloads/Suno`

## Почему нет MP3?

На Android нет встроенного MP3-энкодера. Для конвертации нужен FFmpeg:
- Вариант А: сохранить M4A (качество оригинала Suno, ~256 kbps AAC)
- Вариант Б: добавить `ffmpeg-arm64` в assets и вызывать через `ProcessBuilder`

По умолчанию приложение сохраняет **M4A** (оригинальный формат).

## Разрешения

| Permission | Зачем |
|---|---|
| `INTERNET` | Скачивание с API Suno и CDN |
| `ACCESS_NETWORK_STATE` | Проверка подключения |
| `WRITE_EXTERNAL_STORAGE` (≤API 29) | Запись в Downloads на старых Android |

На Android 10+ запись идёт через **SAF** (Storage Access Framework) — пользователь сам выбирает папку, отдельное разрешение не нужно.
