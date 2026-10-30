# Серверный комплект

Комплект для одного Linux-сервера находится в `deploy/server`. Требуются Docker Engine с Compose v2, openssl для генерации секретов, DNS-имя с A/AAAA на сервер и входящие TCP 80/443. Caddy получает и обновляет сертификат; наружу публикуются только его порты. Backend, PostgreSQL и обработчик резервов работают во внутренних сетях. Сервер и доступ к внешнему хранилищу в репозитории не заданы.

## Образ приложения

На машине сборки с JDK 21, Node.js 22.12+ и Docker выполнить проверки и собрать образ из проверенного JAR:

```powershell
.\gradlew.bat --no-daemon --max-workers=2 build
cd web
npm run test:e2e
cd ..
docker build -f deploy/server/app.Dockerfile -t payment-core:release-2026-10-30 build/libs
```

На Linux используется `./gradlew`. Серверный Dockerfile получает только JAR из `build/libs`; секреты и исходники не входят в контекст. Корневой Dockerfile дополнительно поддерживает сборку из исходников. Для выпуска используйте уникальный тег или digest, сохраняйте прежний образ. Передайте образ серверу через свой registry либо `docker save`/`docker load`. Инфраструктурные образы из `.env.example` также закрепите на проверенных digest.

## Настройка и запуск

Пример каталога checkout на сервере: `/opt/payment-core`; секреты хранятся отдельно. Команды ниже выполняются на сервере:

```sh
cd /opt/payment-core/deploy/server
cp .env.example .env
sh init-secrets.sh /opt/payment-core-secrets
```

В `.env` задайте `PAYMENT_IMAGE`, `CADDY_SITE`, `ACME_EMAIL`, `PAYMENT_SECRETS_DIR`. Паролей в `.env` нет. `init-secrets.sh` не перезаписывает существующие файлы. Каталог секретов имеет режим 0700; отдельные файлы 0444 нужны для чтения через bind mount непривилегированными контейнерами. Защитите родительский каталог и отдельно сохраните секреты в защищённом хранилище. Compose secrets — файловые монтирования, а не шифрование файлов на диске.

Роли PostgreSQL: `postgres` обслуживает кластер, `payment_migrator` владеет схемой, `payment_app` получает DML без CREATE/TRUNCATE/изменения триггеров, `payment_backup` читает таблицы. Backend получает только пароль `payment_app`; отдельный процесс миграций получает только пароль владельца схемы и завершается до запуска backend. Смена файла пароля не меняет пароль уже созданной роли: ротация требует отдельного ALTER ROLE и согласованного перезапуска потребителей.

```sh
docker compose config --quiet
docker compose up -d --wait --wait-timeout 180
docker compose ps -a
docker compose run --rm --no-deps maintenance --bootstrap-admin
curl --fail https://payments.example.com/api/v1/status
```

Bootstrap запрашивает пароль в терминале и не запускает HTTP. Для защищённой автоматизации доступен `--credentials-stdin`; пароль не передаётся аргументом. Встроенных пользователей нет. Откройте HTTPS-адрес панели, создайте пользователя, проверьте учебное пополнение, перевод, резерв и административную сверку.

Caddy имеет фиксированный адрес `172.30.80.2`, backend — `172.30.80.3` во внутренней сети `172.30.80.0/24`; backend доверяет forwarding-заголовкам только от адреса Caddy. Caddy заменяет X-Forwarded-For адресом своего соединения и удаляет Forwarded. Клиент не может задать адрес для лимита входа. При конфликте подсетей задайте `PAYMENT_PROXY_SUBNET`, `PAYMENT_PROXY_IP` и `PAYMENT_BACKEND_PROXY_IP` согласованно; Compose передаст точный `/32` Caddy в backend. Перед Caddy в этой конфигурации не предполагается дополнительный CDN или reverse proxy.

`/actuator`, Swagger и OpenAPI снаружи получают 404. Readiness backend проверяется внутри Docker; публичный `/api/v1/status` не раскрывает БД. Secure/HttpOnly/SameSite-cookie, CSRF и CSP сохраняются. Backend и Caddy работают без root с read-only rootfs и ограничениями ресурсов. PostgreSQL работает от UID 70; отдельный короткий `volume-init` меняет владельцев корней именованных томов. Логи контейнеров ограничены тремя файлами по 10 MB. Caddy access log не включён, тела запросов, пароли и cookie не журналируются.

## Внешние резервные копии

Выделите отдельный restic-репозиторий для этого окружения на другом сервере/внешнем объектном хранилище. Отредактируйте `/opt/payment-core-secrets/restic_config` защищённым редактором. Это доверенный shell-файл, импортируемый только инструментом копирования. Пример для S3-совместимого хранилища:

```sh
RESTIC_REPOSITORY=s3:https://s3.example.com/payment-core-backups
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
```

Кавычки обязательны для значений со специальными shell-символами. Пароль шифрования лежит в отдельном `restic_password`. Его потеря делает копии недоступными; сохраните пароль и доступ к хранилищу отдельно от сервера. Не используйте рабочие секреты в примерах/командной строке. Репозиторий создаётся один раз явной командой; неудачный доступ никогда не инициирует новое хранилище автоматически.

```sh
docker compose --profile ops build backup
docker compose --profile ops run --rm --no-deps backup init
docker compose --profile ops run --rm --no-deps backup backup
docker compose --profile ops run --rm --no-deps backup snapshots
docker compose --profile ops run --rm --no-deps backup check
```

`pg_dump` PostgreSQL 18 создаёт согласованный custom archive всей БД: журнал, ключи команд, пользователей, резервы, аудит и сессии. Restic читает вывод через `--stdin-from-command`, поэтому ненулевой код pg_dump не создаёт снимок. Дамп сразу шифруется и отправляется во внешний репозиторий. После копирования проверяется целостность структуры; удерживаются 14 ежедневных, 8 еженедельных и 12 ежемесячных снимков. Не направляйте разные окружения в один репозиторий с общей политикой удаления. Команда `check` читает и проверяет все данные репозитория.

Установите ежедневный timer (пути в service рассчитаны на `/opt/payment-core`):

```sh
sudo install -m 0644 payment-backup.service payment-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now payment-backup.timer
systemctl list-timers payment-backup.timer
journalctl -u payment-backup.service
```

Запуск — ежедневно около 03:00 UTC; пропущенный запуск выполняется после включения сервера. `flock` исключает наложение timer-запусков. Проверяйте ненулевые exit codes и отсутствие свежего снимка средствами мониторинга сервера. Полное чтение `backup check` и тренировочное восстановление выполняйте регулярно и после изменения копирования. Таймер и доступ к реальному внешнему репозиторию необходимо включить и проверить на предоставленном сервере.

## Проверка восстановления

Сначала выберите snapshot ID в `backup snapshots`. Следующие команды поднимают отдельную PostgreSQL без опубликованных портов:

```sh
docker compose --profile restore up -d --wait restore-postgres
docker compose --profile restore run --rm --no-deps restore SNAPSHOT_ID
docker compose --profile restore run --rm --no-deps verify-restore
```

Восстановление разрешено только в `restore-postgres/payment_restore`, только в пустую базу. Повтор в непустую базу останавливается. Дамп временно находится в tmpfs на 512 MB, затем восстанавливается одной транзакцией; для большой БД увеличьте tmpfs и memory limit инструмента. `verify-restore` запускает приложение с `--reconcile-only`, сравнивает остатки, журнал и активные резервы и возвращает ненулевой код при расхождении. Дополнительно проверьте пользователей, число операций и ключевые остатки. Обычная проверка копии рабочую БД не меняет.

Для следующей тренировки используйте новый Compose project name/новый том `restore-data`. Сохраняйте последний проверенный том до окончания восстановления. Не используйте `down --volumes` для рабочего проекта: эта команда удаляет все его тома.

## Переключение на восстановленную базу

При аварийном восстановлении зафиксируйте время выбранного снимка: операции после него отсутствуют. Сначала завершите проверку копии, остановите запись и сохраните исходный том. Затем:

```sh
docker compose --profile restore stop caddy backend postgres restore-postgres
# В .env установите PAYMENT_DB_NAME=payment_restore.
docker compose -f compose.yaml -f promote-restored.yaml up -d --wait --wait-timeout 180 postgres migrate backend
docker compose -f compose.yaml -f promote-restored.yaml run --rm --no-deps maintenance --reconcile-only
docker compose -f compose.yaml -f promote-restored.yaml exec -T postgres psql -U payment_app -d payment_restore -v ON_ERROR_STOP=1 -c 'DELETE FROM spring_session'
docker compose -f compose.yaml -f promote-restored.yaml up -d --no-deps caddy
```

Overlay подключает `restore-data` к основному сервису `postgres`; исходный `postgres-data` сохраняется. Два процесса PostgreSQL не должны одновременно открывать один PGDATA. После переключения постоянно используйте `-f compose.yaml -f promote-restored.yaml`; обновите ExecStart backup service теми же двумя `-f`, выполните `systemctl daemon-reload`. Удаление сессий перед открытием доступа требует повторного входа пользователей, сохранённые ключи денежных команд при этом остаются в БД. Проверьте панель, сверку и новое внешнее копирование. Caddy поднимайте последним после внутренних проверок.

## Обновление и совместимый откат

Перед обновлением сохраните текущие image tag/digest и версию Flyway, выполните свежую внешнюю копию и тренировку её восстановления. Новый образ должен пройти Gradle, Playwright и серверный drill ниже. Изменения схемы выпускайте по принципу expand/contract: добавления совместимы со старым кодом, удаление/переименование — отдельный выпуск после отказа от старого кода.

```sh
docker compose --profile ops run --rm --no-deps backup backup
docker compose stop caddy backend
# В .env сохраните новый уникальный PAYMENT_IMAGE.
docker compose run --rm --no-deps migrate --migrate-only
docker compose up -d --no-deps --wait backend
docker compose run --rm --no-deps maintenance --reconcile-only
docker compose up -d --no-deps caddy
curl --fail https://payments.example.com/api/v1/status
```

Для проекта после восстановления добавляйте overlay к каждой команде. При ошибке миграции backend остаётся остановленным; проверяйте лог и состояние Flyway, не запускайте автоматический `repair`. Старые миграции не редактируются.

Откат бинарного образа допустим только при подтверждённой совместимости старого кода с текущей схемой: верните прежний `PAYMENT_IMAGE` в `.env`, остановите caddy/backend, поднимите только backend с `--no-deps --wait`, выполните `--reconcile-only`, затем поднимите Caddy. Runtime не запускает Flyway и не пытается понизить схему. Если схема несовместима, требуется исправляющий выпуск; восстановление старой копии — отдельное решение с потерей последующих операций, а не автоматический откат.

## Автоматическая проверка комплекта

После Gradle build из корня checkout:

```sh
node scripts/test-server.mjs --build --image payment-core:server-check
```

Сценарий создаёт уникальный `pc-smoke-*`, случайные тестовые секреты, loopback-порты, локальный CA Caddy и отдельный зашифрованный restic-репозиторий. Настройки развёртывания из окружения shell не влияют на тестовые пути и подключения. Проверяет HTTPS, cookie/CSRF, закрытые endpoints и порты, роли БД, защиту forwarded-заголовков, перезапуск с сохранением сессии и ключа, копирование, сбой pg_dump, восстановление и сверку, отказ перезаписи базы, переключение восстановленного тома, завершение старых сессий, повтор команды после нового входа и обнаружение искусственного повреждения. В конце удаляет только контейнеры и тома своего тестового проекта. Логи и `result.json` остаются в `build/pc-smoke-*/`; `--keep` сохраняет проект для диагностики.

`--image` без `--build` проверяет уже собранный образ. Для локальной диагностики при недоступном registry можно добавить `--runtime-image` с заранее проверенным локальным Java 21 Alpine runtime; он передаётся как build arg JAVA_RUNTIME_IMAGE. Для выпуска используйте чистый официальный runtime с закреплённым digest.

Локальный restic-репозиторий drill проверяет шифрование и восстановление. Он не подтверждает доступность вашего внешнего S3/SFTP, работу systemd на сервере, публичный DNS или выдачу публичного сертификата — эти проверки выполняются при фактическом развёртывании.

Основания конфигурации: [Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/), [forwarding-заголовки Caddy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy#headers), [настройки trusted proxy Spring Boot](https://docs.spring.io/spring-boot/appendix/application-properties/index.html), [restic и exit code команды дампа](https://restic.readthedocs.io/en/stable/040_backup.html#reading-data-from-a-command).
