# Дополнения Docker Compose

Файлы в этом каталоге дополняют корневой `docker-compose.yml`. Секреты передаются из локального `.env`, который исключён из Git. Запуск только с основным файлом применяет его параметры памяти. Для сохранения параметров из дополнения укажи его при пересоздании контейнеров.

## runtime-memory-preservation.yml

Профиль памяти для шести Java-сервисов и Kafka. Для Java он задаёт G1, `MaxRAMPercentage=50`, `MALLOC_ARENA_MAX=2` и ограничение контейнера 384m. Для Kafka задаёт `-Xmx512m -Xms256m`.

Запускай команды из корня репозитория по одному сервису. Перед переходом к следующему убедись, что текущий контейнер работает и прошёл проверку готовности.

```bash
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build kafka
# Перед следующим шагом проверь, что `docker ps` показывает op-kafka в состоянии healthy.
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build auth-service
# Перед следующим шагом проверь состояние op-auth и работу JWT.
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build inventory-service
# После проверки op-inventory повтори для order-service, payment-service,
# notification-service и api-gateway по одному сервису с проверкой готовности каждого.
```

- `-p order-processing` и поле `name:` основного файла указывают на один проект Compose. При запуске из другой копии проекта команды затронут те же контейнеры, сеть и тома.
- Шесть Java-сервисов относятся к профилю `app`. Команды выше явно включают этот профиль.
- В основном файле используются `-XX:MaxRAMPercentage=65` и `-Xms128m` для Kafka. Дополнение переопределяет их, чтобы пересоздание не меняло настройки работающего стенда.

## auth-jwt-preserve-memory.yml

Дополнение для auth-service. Оно сохраняет параметры JVM и аллокатора при включении постоянных ключей подписи JWT. Блок auth-service совпадает с блоком из `runtime-memory-preservation.yml`. Их можно использовать вместе или по отдельности.

## kafka-memory-trial.yml

Файл завершённого эксперимента с Kafka. Значение `-Xms` временно снизили с 256m до 128m. Максимальный размер остался 512m. Через девять минут восстановили прежние настройки.

RSS при эксперименте составлял 470.7 MiB через 85 секунд и 496.5 MiB через 386 секунд, а перед экспериментом — 347.6–381.7 MiB при времени работы около трёх часов. Из-за разного времени работы после перезапуска по этим измерениям нельзя судить об использовании памяти при длительной нагрузке. Перезапусков и OOM в ходе эксперимента и отката не было. Все шесть групп потребителей оставались в состоянии `Stable`, с назначенными партициями и нулевым отставанием.

После отката использовались основной файл и `runtime-memory-preservation.yml`, снова с `-Xmx512m -Xms256m`. Команда отката:

```bash
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing up -d --no-deps --no-build kafka
```
