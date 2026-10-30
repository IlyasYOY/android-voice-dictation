# Подготовка облачного Android-окружения

Это инструкция для **будущего** окружения репозитория `IlyasYOY/android-voice-dictation`.
Наличие этих файлов не означает, что сохранённое Codex environment уже создано.
Репозиторий остаётся приватным. Скрипты не меняют доступ к нему и не используют
пользовательский компьютер.

## Требования

- Linux x86_64; Bash, `curl`, `unzip`, `sha256sum`, стандартные GNU-утилиты.
- Полный JDK **17 или 21** (`java` и `javac`); байткод приложения рассчитан на Java 17.
- Доступный для записи SDK-каталог и несколько гигабайт свободного места для SDK,
  Gradle, зависимостей и результатов сборки.
- Доступ к приватному репозиторию через штатное GitHub-подключение окружения.
  Не вставляйте GitHub-токены в файлы проекта.

Зафиксированный базовый набор: AGP 8.11.1, Gradle 8.13, Kotlin 2.2.21,
Android API 36, Build Tools 35.0.0. Установщик использует официальный Linux-архив
command-line tools **15859902**, проверяя SHA-256
`4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`.
Он не заменяет существующий `cmdline-tools/latest`: собственная версия хранится
в `.scaffold-tools/15859902` внутри SDK. `platform-tools` устанавливается из
стабильного канала SDK; этот пакет Google распространяет как обновляемую версию,
поэтому его номер не фиксируется нашим установщиком. Сборка использует
зафиксированные platform API и Build Tools.

## Когда создание Codex environment снова будет доступно

1. Создайте окружение именно для приватного `IlyasYOY/android-voice-dictation`,
   выбрав его основную ветку `main`. Не переиспользуйте окружение другого проекта.
2. Выберите Linux-образ с JDK 17/21 и утилитами выше. Скрипт не устанавливает Java
   и не вызывает `sudo`.
3. При необходимости задайте `ANDROID_HOME` как абсолютный путь к доступному для
   записи каталогу. По умолчанию это `$HOME/.local/share/android-sdk`.
   Не задавайте отличающийся `ANDROID_SDK_ROOT`: конфликт будет явной ошибкой.
4. Прочитайте [лицензионные условия Android SDK](https://developer.android.com/studio/terms).
   **Только если вы принимаете их**, используйте эту команду настройки из корня репозитория:

   ```bash
   ANDROID_ACCEPT_LICENSES=yes bash scripts/setup-android.sh
   bash scripts/check.sh
   ```

   Без `ANDROID_ACCEPT_LICENSES=yes` установщик завершится **до загрузки и изменения SDK**.
   Переменная является явным согласием загрузить указанный архив command-line tools
   и принять лицензии, необходимые **только для установки трёх явно запрошенных
   пакетов**: `platform-tools`, `platforms;android-36`, `build-tools;35.0.0`.
   Скрипт не вызывает `sdkmanager --licenses`, поскольку эта команда предлагает
   также лицензии посторонних SDK-пакетов. Не устанавливайте переменную согласия
   незаметно в общих настройках.
   Если SDK уже установлен и лицензии приняты, установщик не обязателен: можно
   сразу указать `ANDROID_HOME` и запустить проверку.
5. Сохраните `ANDROID_HOME` в переменных будущего окружения, если использовали
   нестандартный путь. Экспорт переменных внутри setup-процесса не гарантирует,
   что они попадут в последующую рабочую сессию. Скрипты проверки сами подключают
   `android-env.sh`; перед ручными командами в новой Bash-сессии выполните:

   ```bash
   source scripts/android-env.sh
   ./gradlew --no-daemon :app:assembleDebug
   ```

Секреты, OpenAI API key, выбор биллинга или авторизации для каркаса **не нужны**.
Установщик повторно использует свой уже установленный инструментарий и ставит
недостающие SDK-пакеты. Он не удаляет SDK и не исправляет неизвестные каталоги
перезаписью: при незавершённой установке выдаёт путь для проверки человеком.

## Сеть и кеш

Первый запуск требует сети. Для данной конфигурации могут понадобиться:

- `dl.google.com`, `dl-ssl.google.com`, `maven.google.com` — SDK и Google Maven;
- `services.gradle.org`, `downloads.gradle.org`, `github.com`,
  `release-assets.githubusercontent.com` — Gradle и перенаправления его загрузки;
- `plugins.gradle.org`, `plugins-artifacts.gradle.org` — Gradle Plugin Portal;
- `repo.maven.apache.org` — Maven Central.

Используйте разрешения сети своего окружения; перенаправления могут потребовать
дополнительного официального хоста. Не обходите запрет сети и не заменяйте
репозитории неизвестными зеркалами. При ошибке сохраните точный URL/код ошибки
и проверьте разрешения. В среде с прокси нужны корректные настройки JVM/Gradle,
а не только `curl`.

Если после setup доступ в сеть ограничивается, сначала выполните **полный**
`bash scripts/check.sh` в стадии с сетью и сохраните SDK + Gradle-кеш.
Один `./gradlew --version` не загружает зависимости приложения. Затем
`bash scripts/check.sh --offline` проверит достаточность кеша; новый dependency
или обновление версии снова потребует сети. Кеш не является частью Git.

Если домашний каталог недоступен для записи, отдельно задайте доступные каталоги
`GRADLE_USER_HOME` и `ANDROID_USER_HOME` (например, в рабочей области окружения).
Они хранят кеш/служебное состояние и не должны попадать в Git. `ANDROID_HOME`
указывает на SDK и не заменяет эти две переменные. Не отключайте TLS-проверки для
исправления proxy/certificate ошибок: настройте штатный прокси и доверенное
хранилище сертификатов окружения.

## Что проверяют скрипты

```bash
bash scripts/doctor.sh   # только диагностика JDK, SDK и Gradle Wrapper
bash scripts/check.sh    # debug APK, Android Lint, JVM unit tests, сборка test APK
```

Результаты:

- `app/build/outputs/apk/debug/app-debug.apk`;
- `app/build/reports/lint-results-debug.html`;
- `app/build/reports/tests/testDebugUnitTest/index.html`.

`assembleDebugAndroidTest` **только компилирует** instrumentation-тесты.
Успешная сборка не доказывает, что UI-тесты выполнены или экран просмотрен.

## Эмулятор — отдельно и не обязателен для каркаса

Сборка, lint и JVM-тесты работают без эмулятора. Для UI-тестов позже потребуются
совместимый образ Android, emulator, AVD и рабочая аппаратная виртуализация
(обычно доступ к `/dev/kvm` на Linux), либо авторизованное физическое устройство.
Наш setup не устанавливает большие образы эмулятора и не подключает устройства.

Только в среде с уже подготовленным и запущенным тестовым устройством:

```bash
source scripts/android-env.sh
adb devices
./gradlew --no-daemon :app:connectedDebugAndroidTest
```

Если устройства нет, эти тесты **не запускались**; не отмечайте их пройденными.
Скриншот приложения нужно получать с действительно запущенного Android-приложения,
а не выдавать макет или Compose Preview за проверку на устройстве.

## Официальные справочники

- [SDK command-line tools и контрольные суммы](https://developer.android.com/studio#command-line-tools-only)
- [sdkmanager](https://developer.android.com/tools/sdkmanager)
- [Переменные Android SDK](https://developer.android.com/tools/variables)
- [Совместимость AGP 8.11](https://developer.android.com/build/releases/past-releases/agp-8-11-0-release-notes)
- [Совместимость Kotlin Gradle plugin](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Совместимость Gradle с Java](https://docs.gradle.org/8.13/userguide/compatibility.html)
- [Аппаратное ускорение эмулятора](https://developer.android.com/studio/run/emulator-acceleration)
