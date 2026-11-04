# Постоянная подпись APK для обновления

Нужный сертификат SHA-256:
`66088e33b7734f65bc26a0268c63f47875babe65c1a81558bc6f806e23b85a68`.
Package: `io.github.ilyasyoy.voicedictation`. Кандидат: 0.15.0 (31).
Новый ключ не заменяет существующий: Android разрешает обновление с сохранением
данных только с совместимой подписью и без понижения versionCode.
Сертификат установленного на телефоне приложения отдельно здесь не считывался;
этот fingerprint — согласованная идентичность ранее переданного APK.

Схема основана на документированном поведении
[`workflow_run`](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run)
и [Environment branch policies](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments#deployment-branches-and-tags).
Для приватных репозиториев такие branch rules требуют соответствующего тарифа
(GitHub Pro/Team/Enterprise). Получение списка environments само по себе ещё не
доказывает возможность сохранить ограничивающую policy; это нужно проверить
после разрешения на однократное включение, до загрузки ключа.

## Как работает CI

1. `Android checks` собирает приложение, lint и тесты как раньше, без постоянного
   ключа. Временный APK — `app-debug-build-only`; это вход для подписания,
   не артефакт для обновления телефона.
2. Успешный запуск автоматически вызывает `Sign installable APK` через
   `workflow_run`. Этот listener должен находиться в default branch. GitHub
   использует workflow default branch, а checkout закреплён на его `github.sha`.
   Код PR, Gradle, зависимости и build cache на signing runner не запускаются.
3. Подписываются только push в default branch и PR из этого же репозитория в
   default branch. Идентификаторы обеих сторон PR, SHA, статус и путь исходного
   workflow перепроверяются через GitHub API. Fork, другой workflow, неуспешная
   сборка или неизвестный event отклоняются.
4. Из одного артефакта читается только точный ZIP entry `app-debug.apk`, с лимитом
   размера и проверкой digest. Произвольные пути, скрипты и symlink отклоняются.
   APK рассматривается как данные, не исполняется. Build Tools 35.0.0 проверяют
   пакет, положительный versionCode, versionName и alignment. Версия отражается
   в имени и receipt; версия на телефоне здесь не считывается. Ключ сверяется с ожидаемым сертификатом
   до подписи; затем проверяются v2 и ровно один ожидаемый signer.
5. Артефакт **`app-update`** содержит APK, `SHA256SUMS` и `build-info.json`.
   В Summary signing run находятся ссылка скачивания, версия, source head SHA,
   исходный CI run и отпечатки. Для `pull_request` исходный CI проверяет test merge;
   `source_head_sha` обозначает head PR, а не SHA checkout test merge.
   Signing workflow SHA записывается отдельно. ZIP доступен 7 дней и требует
   входа с доступом к приватному репозиторию.

Нет автоматической подстановки другого ключа. При отсутствующем секрете,
неподходящем сертификате или ошибке проверки подписанный артефакт не публикуется.
Ключ во временном файле удаляется при завершении подписания; он не попадает в
checkout, build cache, артефакт, checksum или журнал. Логи инструментов с ключом
не пересылаются в Actions log, пароли передаются через environment, не аргументы.

## Однократное включение — только после отдельного согласия владельца

До согласия **не передавать ключ в GitHub, не записывать secrets и не генерировать
замену**. Требуется сохранить именно существующий ключ с указанным сертификатом.

После согласия и проверки предлагаемых изменений:

1. Создать GitHub Environment **`apk-signing`**. Настроить deployment branch policy:
   **Selected branches and tags**, branch **`main`** (текущая default branch),
   без tag rules и без PR refs. Проверить сохранённую политику через API перед
   добавлением ключа. При смене default branch пересмотреть правило.
   **Не хранить ключ в repository-level secrets**: изменённый workflow того же PR
   мог бы запросить их. Environment с одной лишь меткой без branch policy также
   не является защитой. Если тариф/настройки приватного репозитория не позволяют
   применить это ограничение, остановиться до хранения ключа.
2. В этот Environment добавить ровно три secrets:
   `APK_SIGNING_KEYSTORE_BASE64`, `APK_SIGNING_STORE_PASSWORD`,
   `APK_SIGNING_KEY_PASSWORD`. Первый — base64 существующего keystore, alias
   `androiddebugkey`; это encoding, не способ защиты. Передавать через штатный
   Secrets API/CLI в памяти/stdin, не через чат, shell args, Git или stdout.
   Конкретные значения, резервная копия и создание новых credentials сюда
   не включены. Сохранение в Actions — постоянное разрешение default-branch CI
   использовать существующий ключ, поэтому требует отдельного подтверждения.
3. После отдельного разрешения на merge разместить listener и проверенный signer
   в default branch. До этого `workflow_run` listener из PR не активируется.
4. Дождаться новой успешной сборки PR или default branch. Проверить signing run,
   скачать `app-update`, проверить APK fingerprint и checksum. Только после этого
   выдавать пользователю ссылку как на готовое обновление.

Необходим доступ GitHub-подключения к Environment settings и Secrets write.
Если Actions API запрещает настройку, сообщить точный отказ; не извлекать токен
из других инструментов и не использовать пользовательский компьютер.

## Проверка изменений без постоянной передачи ключа

`python3 -m unittest discover -s scripts/tests -p 'test_*.py'` проверяет отказ для
fork/чужого workflow/ошибочной сборки, ZIP traversal, symlink, посторонних файлов,
неподходящего пакета/версии/сертификата и отсутствие fallback при missing secrets.
Они также включены в `scripts/check.sh`. Нужен Python 3 из стандартного runner.

Локальная интеграционная проверка может подписать существующий CI APK этим же
существующим локальным ключом через `--keystore`, без его base64-копирования,
создания ключей или записи в Actions. Она не заменяет успешный GitHub signing run
после однократного включения. Проверка обновления/сохранности данных на телефоне
остаётся отдельной; удаление установленного приложения не требуется и не должно
быть способом обойти несовпадающую подпись.
