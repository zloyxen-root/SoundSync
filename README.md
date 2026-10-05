# SoundSync (Android)

[![Build Android APK](https://github.com/zloyxen-root/SoundSync/actions/workflows/build.yml/badge.svg)](https://github.com/zloyxen-root/SoundSync/actions/workflows/build.yml)

Автономное Android-приложение для синхронизации лайков из SoundCloud в локальную музыкальную библиотеку телефона (MP3 в высоком качестве с ID3v2.3 тегами и обложками).

---

## 🚀 Возможности

- **Material You (Material 3):** Современный дизайн с поддержкой динамических цветов на Android 12+ и стильной тёмной/светлой темой на Android 9–11.
- **Diffing Engine (Умная синхронизация):**
  - Добавляет новые лайкнутые треки в папку `Music/SoundSync/`.
  - Удаляет локальные файлы, если трек был убран из лайков или удалён автором (с возможностью отключения в настройках).
  - Сверяет ID и дату модификации, исключая дубликаты и повторные загрузки.
- **Полноценные ID3-теги:** Встраивание названия (TIT2), исполнителя (TPE1), альбома (TALB) и полноразмерной обложки (APIC).
- **Интеграция с Android MediaStore:** Все скачанные треки мгновенно отображаются в любых системных и сторонних аудиоплеерах (Poweramp, AIMP, Retro Music и т.д.).
- **Фоновая работа:**
  - Foreground Service с отображением прогресса и названия текущего трека в шторке уведомлений.
  - WorkManager для автоматической синхронизации в фоне (например, по Wi-Fi).
- **Никаких CLI и терминалов:** Удобный графический интерфейс с поиском, статистикой занятого места и быстрыми настройками.

---

## 📥 Скачать готовый APK

Готовый `.apk` собирается автоматически через GitHub Actions:
- **[Скачать SoundSync.apk (Latest Release)](https://github.com/zloyxen-root/SoundSync/releases/tag/latest-build)**

---

## 🛠 Технический стек

- **Язык:** Kotlin 1.9.22
- **UI:** Jetpack Compose + Material 3
- **База данных:** Android Room (SQLite)
- **Сеть:** OkHttp 4.12
- **Изображения:** Coil Compose
- **Фоновые задачи:** Android WorkManager + Foreground Services
- **Тегирование:** Встроенный чистый парсер/писатель ID3v2.3

---

## 📱 Сборка и запуск

1. Откройте проект в **Android Studio** (Iguana / Jellyfish / Koala).
2. Подключите телефон (Android 9.0+) по USB в режиме отладки или запустите эмулятор.
3. Нажмите кнопку **Run (Shift + F10)**.

Или соберите APK в терминале:
```bash
./gradlew assembleDebug
```
Готовый файл APK будет находиться по пути: `app/build/outputs/apk/debug/app-debug.apk`.
