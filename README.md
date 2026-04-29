# Nookify Backend

AI-сервис для автоматизации 3D-дизайна интерьеров. Система принимает текстовые запросы, обрабатывает их через **Google Gemini** и выдает готовый план расстановки мебели с координатами и ссылками на модели в **MinIO**.

---

## 🛠 Технологический стек

* **Core:** Java 21, Spring Boot 3.4+
* **AI Integration:** Google Gemini API (через Spring AI & OkHttp)
* **Database:** PostgreSQL + Liquibase (миграции)
* **Storage:** MinIO (S3-compatible storage для .glb моделей)
* **Network:** OkHttp3 с поддержкой авторизованного прокси

---

## ⚙️ Локальная настройка (Setup)

Чтобы проект завелся, нужно подготовить конфиги, так как реальные ключи скрыты.

### 1. Конфигурация (application.properties)
1. Перейди в директорию `src/main/resources/`.
2. Найди файл `application.properties.origin`.
3. Сделай его копию и назови её просто `application.properties`.
4. Заполни свои данные в полях:
    * `spring.datasource.*` — доступ к твоей базе Postgres.
    * `minio.*` — доступы к локальному или облачному MinIO.
    * `gemini.api.key` — твой ключ от Google AI Studio.
    * `proxy.*` — данные твоего прокси (хост, порт, логин, пароль).

### 2. Запуск инфраструктуры
В корне проекта лежит `docker-compose.yml`. Чтобы поднять базу и MinIO, выполни:
```bash
docker-compose up -d
```
Чтобы положить, выполни:
```bash
docker-compose up -d
```
БЕЗ ФЛАГОВ, пожалуйста^^
### 3. Сборка и запуск
```bash
./gradlew bootRun
```
---

## 🏗️ Структура проекта
* **com.nookify.backend.service.AiPlannerService** — "Мозги" системы. Формирует промпты, ходит в Gemini через прокси и парсит "стерильный" JSON из ответов нейронки.
* **com.nookify.backend.config.MinioConfig** — Настройка подключения к хранилищу ассетов.
* **src/main/resources/db/changelog** — История изменений базы данных (Liquibase).

---

## ⚠️ Примечания
1. **Прокси:** Весь сетевой трафик до Google API идет через кастомный `OkHttpClient`. Если прокси не нужен, закомментируй настройки в `application.properties`.
2. **Формат моделей:** Система ожидает, что в MinIO лежат файлы в формате `.glb`. Имена файлов в бакете `furniture` должны строго соответствовать ID мебели (например, `Bed_01.glb`).
3. **Безопасность:** Никогда не пушьте `application.properties` в репозиторий. Все новые ключи добавляйте сначала в `application.properties.origin`.