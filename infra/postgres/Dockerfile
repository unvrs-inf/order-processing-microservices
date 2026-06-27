# Образ postgres:16 со скриптами создания баз сервисов.
# Скрипты встроены в образ, чтобы запуск не зависел от разрешений Docker Desktop
# на подключение локального каталога.
FROM postgres:16

COPY init/ /docker-entrypoint-initdb.d/

# Скрипты выполняет пользователь postgres. Файлы доступны ему на чтение
# при любом umask на машине сборки.
RUN chmod 0644 /docker-entrypoint-initdb.d/*
