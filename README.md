# marketplace-back

Бэкенд **marketplace** — сервиса витрин в духе Ecwid. Мерчант сам регистрируется (почта → код из письма),
создаёт магазин (название, адрес `/shop/{slug}`, фирменный цвет), ведёт каталог, настраивает оформление витрины,
подключает **свой** терминал Т-Банка или магазин ЮKassa (деньги идут мерчанту напрямую) и **свой** аккаунт СДЭК (доставка считается
по тарифам его договора), указывает ПВЗ СДЭК, откуда отправляет.
Покупатель на витрине кладёт товары в корзину, выбирает ПВЗ СДЭК и платит через терминал мерчанта;
мерчант видит заказы и аналитику продаж. Фронтенд — репозиторий `marketplace-front`.

Стек: Java 21, Spring Boot 3.2 (web, security, data-jpa, validation, actuator, webflux `WebClient`),
PostgreSQL + Flyway, JWT (jjwt), AWS SDK v2 для S3 (Yandex Object Storage), springdoc-openapi, Guava, Lombok.

## Сборка

Нужен **JDK 21**. С более новым JDK (например, 25 из Homebrew) ломается Lombok — указывайте `JAVA_HOME` явно:

```bash
JAVA_HOME=/path/to/jdk-21 mvn -q compile       # компиляция
JAVA_HOME=/path/to/jdk-21 mvn -q test          # юнит-тесты (БД не нужна)
JAVA_HOME=/path/to/jdk-21 mvn -q package -DskipTests   # target/marketplace-back-1.0-SNAPSHOT.jar
```

Docker-образ: `docker build -t marketplace-back .` (сборка внутри `maven:3.9.9-eclipse-temurin-21`, порт 8090).

## Запуск локально

1. PostgreSQL 14+ и пустая база (схему создаст Flyway: миграции `src/main/resources/db/migration/V1__init.sql`, `V2__login_code_fail_window.sql`,
   затем Hibernate проверяет сущности против схемы — `ddl-auto=validate`).
   ```bash
   createdb marketplace
   ```
2. Скопируйте `.env.example` в `.env` и заполните. Значений по умолчанию в конфиге нет: если переменная
   не задана, приложение не стартует.
3. Запуск:
   ```bash
   set -a; source .env; set +a
   JAVA_HOME=/path/to/jdk-21 mvn spring-boot:run
   ```
4. Проверка: `GET http://localhost:8090/api/actuator/health` → `{"status":"UP"}`,
   Swagger — `http://localhost:8090/swagger-ui.html`.

Для прямой загрузки картинок из браузера бакету нужен CORS: при `S3_CORS_AUTO=true` он прописывается
при старте из `ALLOWED_ORIGINS`, иначе — вручную (methods GET, PUT, HEAD; headers *).

## Деплой

Бэкенд — отдельный контейнер без публичного адреса: снаружи до него доходит только гейтвей
(`marketplace-gateway`, репозиторий `marketplace-getaway`), который отдаёт ему `/api/*` любого хоста —
домена платформы и своих доменов магазинов. Railway: сборка и healthcheck — в `railway.toml`,
приватный адрес `back.railway.internal:8090`. Свой сервер: `docker run --network marketplace --name marketplace-back`,
см. README гейтвея.

За гейтвеем фронт и API на одном origin, поэтому `ALLOWED_ORIGINS` нужен только для CORS бакета S3
(загрузка картинок из кабинета) — это домен платформы. Возврат покупателя после оплаты разрешён на хосты
`PAYMENT_ALLOWED_RETURN_HOSTS` и на свой домен магазина заказа (с `www.`).

## Переменные окружения

| Переменная | Ключ в `application.properties` | Что это |
|---|---|---|
| `ALLOWED_ORIGINS` | `allowed.origins` | Origins фронта через запятую: CORS ответов API и CORS бакета S3 |
| `FRONT_URL` | `front.url` | Origin фронта без слэша на конце — ссылки в письмах |
| `POSTGRES_URL` | `spring.datasource.url` | JDBC URL базы, например `jdbc:postgresql://localhost:5432/marketplace` |
| `POSTGRES_USER` | `spring.datasource.username` | Пользователь БД |
| `POSTGRES_PASSWORD` | `spring.datasource.password` | Пароль БД |
| `S3_BUCKET_NAME` | `s3.bucket.name` | Приватный бакет для фото товаров, логотипов и обложек |
| `S3_REGION` | `s3.region` | Регион S3 (`ru-central1` для Yandex) |
| `S3_ENDPOINT` | `s3.endpoint` | Endpoint S3 (`https://storage.yandexcloud.net`) |
| `S3_ACCESS_KEY_ID` | `s3.access-key-id` | Ключ доступа S3 |
| `S3_SECRET_ACCESS_KEY` | `s3.secret-access-key` | Секрет S3 |
| `S3_CORS_AUTO` | `s3.cors.auto` | `true` — прописать CORS бакета при старте, `false` — не трогать |
| `JWT_SECRET` | `jwt.secret` | Ключ подписи JWT (HS256), не короче 32 символов — иначе падение на старте |
| `JWT_EXPIRATION_SECONDS` | `jwt.expiration.seconds` | Срок жизни токена в секундах |
| `ADMIN_SUPER_EMAILS` | `admin.super.emails` | Почты супер-админов платформы через запятую (минимум одна) |
| `AUTH_LOGIN_CODE_SECRET` | `auth.login-code.secret` | Ключ HMAC-SHA256 для хранения кодов входа (в БД только хэш) |
| `EMAIL_NOTISEND_API_KEY` | `email.notisend.api.key` | API-ключ NotiSend |
| `EMAIL_NOTISEND_EMAIL_ADDRESS` | `email.notisend.email.address` | Адрес отправителя писем |
| `EMAIL_NOTISEND_FROM_NAME` | `email.notisend.from.name` | Имя отправителя писем |
| `SDEK_TARIFF_CODE` | `sdek.tariff.code` | Код тарифа СДЭК для расчёта доставки |
| `PAYMENT_TINKOFF_API_URL` | `payment.tinkoff.api.url` | API Т-Банка, `https://securepay.tinkoff.ru/v2` |
| `PAYMENT_TINKOFF_NOTIFICATION_URL` | `payment.tinkoff.notification-url` | Публичный URL вебхука `…/api/public/payment/tinkoff-webhook`, уходит в Init |
| `PAYMENT_SECRETS_KEY` | `payment.secrets.key` | AES-256 ключ шифрования паролей терминалов в БД: base64 ровно от 32 байт (`openssl rand -base64 32`). Смена ключа = потеря сохранённых паролей |
| `PAYMENT_ALLOWED_RETURN_HOSTS` | `payment.allowed-return-hosts` | Хосты, куда можно вернуть покупателя после оплаты, через запятую |
| `PAYMENT_PENDING_CHECK_INTERVAL_MS` | `payment.pending-check.interval-ms` | Период поллера статусов неоплаченных заказов, мс |

Константы предметной области заданы литералами в `application.properties`: срок кода входа 10 мин,
повторная отправка не чаще раза в 60 с, 5 попыток, 20 запросов кода в час с одного IP; порт 8090.

## Доступ к API

- `/api/public/**` — без токена (витрина, вход по коду, вебхуки Т-Банка и ЮKassa).
- `/api/merchant/**`, `/api/auth/me` — любой вошедший пользователь (`ROLE_MERCHANT`).
- `/api/admin/**` — только супер-админ (почта из `ADMIN_SUPER_EMAILS`, проверяется на каждом запросе).
- Всё остальное закрыто. Без токена — `401 {"error":"invalid auth token"}`, без прав — `403`.

Ошибки API всегда в формате `{"error": "текст"}`. Деньги — в копейках (`…Kopecks`), даты — ISO-строки UTC.
Списки страницами — `PageDTO { items, total, page, size }` (page с 0). Полный контракт — Swagger (`/swagger-ui.html`).

## Обзор API

| Метод | Путь | Доступ | Что делает |
|---|---|---|---|
| POST | `/api/public/auth/request-code` | все | `{ email }` → `{}`: код входа на почту (регистрация = первый вход). Повтор раньше 60 с — без письма; 20 запросов в час с IP → 429; почта заблокирована после 20 неверных кодов за сутки → 429; письмо не ушло → 502 |
| POST | `/api/public/auth/verify-code` | все | `{ email, code }` → `{ token }`; неверный/устаревший код → 400, после 5 ошибок код сгорает; 20 неверных кодов почты за сутки (по всем кодам) → 429 до конца суточного окна; 30 проверок в час с IP → 429 |
| GET | `/api/auth/me` | мерчант | `{ email, name, superAdmin, shop: { id, slug, name, active } \| null }` |
| GET | `/api/public/shops/slug-available?slug=` | все | `{ available, reason }` — проверка адреса витрины в онбординге |
| POST | `/api/merchant/shop` | мерчант | Онбординг `{ name, slug, brandColor }` → `MerchantShopDTO`; магазин уже есть / адрес занят → 409 |
| GET | `/api/merchant/shop` | мерчант | Свой магазин (`MerchantShopDTO`, с `acceptsOrders` и `missingForOrders`); без магазина → 409 |
| PUT | `/api/merchant/shop/design` | мерчант | Название, цвет, заголовок, описание, «О магазине» |
| POST | `/api/merchant/shop/images/{logo\|hero}/presign` | мерчант | `{ filename, contentType, size? }` → `{ uploadUrl, key }` — прямая загрузка картинки в S3. `size` (байты, до 10 МБ) входит в подпись PUT как Content-Length; 300 подписей в час на магазин → 429 |
| PUT | `/api/merchant/shop/images/{logo\|hero}` | мерчант | `{ key }` — привязать загруженную картинку (ключ только с префиксом своего магазина; файл должен быть в бакете и не больше 10 МБ), старая удаляется |
| DELETE | `/api/merchant/shop/images/{logo\|hero}` | мерчант | Убрать логотип / фото витрины |
| PUT | `/api/merchant/shop/settings` | мерчант | Контакты, реквизиты продавца, ПВЗ СДЭК отправки (`cdekFromPvzCode` ищется в справочнике ПВЗ) |
| PUT | `/api/merchant/shop/cdek` | мерчант | Свой аккаунт СДЭК `{ clientId, clientSecret }` (Account / Secure password из ЛК СДЭК): новые ключи проверяются запросом токена — не приняты → 400, СДЭК не ответил → 502; пустой `clientSecret` — оставить сохранённый, пустой `clientId` — отключить; секрет хранится шифртекстом и наружу не отдаётся |
| PUT | `/api/merchant/shop/payment` | мерчант | Терминал Т-Банка `{ terminalKey, password, receiptEnabled, taxation, tax }`; `tax` ∈ none, vat0, vat5, vat7, vat10, vat22 (vat20 из API Т-Банка убран с 2026 года); пароль хранится шифртекстом и наружу не отдаётся |
| PUT | `/api/merchant/shop/payment/yookassa` | мерчант | Магазин ЮKassa `{ shopId, secretKey, receiptEnabled, taxation, tax }`; новые ключи проверяются в ЮKassa (`GET /v3/me`): не приняты — 400, не ответила — 502; ключ хранится шифртекстом. Только что подключённый провайдер становится активным |
| PUT | `/api/merchant/shop/payment/provider` | мерчант | `{ provider: TINKOFF \| YOOKASSA }` — через кого витрина принимает оплату; провайдер должен быть подключён |
| GET | `/api/merchant/products` | мерчант | Все товары магазина по порядку |
| GET / PUT / DELETE | `/api/merchant/products/{id}` | мерчант | Товар; чужой → 404; DELETE → 204, фото удаляются из S3 |
| POST | `/api/merchant/products` | мерчант | Создать товар (в конец списка), варианты — полная замена набора |
| PUT | `/api/merchant/products/order` | мерчант | `{ productIds }` — новый порядок (полный набор товаров магазина) |
| POST | `/api/merchant/products/{id}/photos/presign` | мерчант | `{ filename, contentType, size? }` → `{ uploadUrl, key }` (как у картинок витрины: до 10 МБ, общий лимит подписей магазина) |
| POST | `/api/merchant/products/{id}/photos` | мерчант | `{ key }` — фото в конец (до 20); файл должен быть в бакете и не больше 10 МБ |
| DELETE | `/api/merchant/products/{id}/photos/{photoId}` | мерчант | Удалить фото (и из S3) |
| PUT | `/api/merchant/products/{id}/photos/order` | мерчант | `{ photoIds }` — порядок фото, первое — главное |
| GET | `/api/merchant/orders?payment=&fulfillment=&page=&size=` | мерчант | `PageDTO<MerchantOrderDTO>`; по умолчанию `payment=PAID`, `fulfillment=ALL`, `size=20` (≤100), новые сверху |
| PUT | `/api/merchant/orders/{publicId}/fulfillment` | мерчант | `{ fulfillmentStatus, trackingNumber }` — только оплаченные заказы своего магазина |
| GET | `/api/merchant/analytics/sales?from=&to=` | мерчант | Отчёт продаж за дни `[from..to]` по Москве: заказы, штуки, сумма, разбивка по товарам |
| GET | `/api/admin/shops` | супер-админ | Все магазины: владелец, товары, оплаченные заказы и выручка |
| PUT | `/api/admin/shops/{id}/active` | супер-админ | `{ active }` — выключенный магазин: витрина 404, оформление заказа 409 |
| PUT | `/api/merchant/shop/domain` | мерчант | `{ domain }` — свой домен витрины (схема, путь, порт и `www.` отбрасываются, кириллица → punycode); пусто — отвязать; некорректный или домен платформы (хост `FRONT_URL` и его поддомены) → 400, у другого магазина → 409 |
| GET | `/api/public/domains/resolve?domain=` | все | `{ slug, domain }` включённого магазина с этим доменом (`www.`, порт и регистр не важны), иначе 404. Его зовут фронт на чужом домене и гейтвей (Caddy `on_demand_tls ask`) перед выпуском сертификата |
| GET | `/api/public/shops` | все | Витрины: активные магазины хотя бы с одним активным товаром |
| GET | `/api/public/shops/{slug}` | все | Магазин для витрины (`PublicShopDTO`); нет или выключен → 404 |
| GET | `/api/public/shops/{slug}/products` | все | Активные товары витрины |
| GET | `/api/public/shops/{slug}/products/{id}` | все | Карточка товара; выключенный или чужой → 404 |
| GET | `/api/public/cdek/pvz?query=` | все | Поиск ПВЗ СДЭК (от 3 символов) по кэшу справочника |
| POST | `/api/public/shops/{slug}/delivery-cost` | все | `{ pvzCode, items }` → `{ cost, periodMin, periodMax, tariffCode }` от ПВЗ отправки магазина по его ключам СДЭК; нет ПВЗ отправки или СДЭК не подключён → 409, ошибка СДЭК → 502; 120 расчётов в час с IP → 429 |
| POST | `/api/public/shops/{slug}/checkout` | все | Корзина + покупатель + ПВЗ → заказ `AWAITING_PAYMENT` и платёж Init терминалом магазина → `{ orderPublicId, paymentUrl }`; доставка в сумму не входит; при включённом чеке почта не длиннее 64 символов (лимит Receipt.Email); 30 заказов в час с IP → 429 |
| GET | `/api/public/orders/{publicId}/status` | все | Статус оплаты для страницы успеха; пока заказ ждёт оплаты — сверка с провайдером заказа (GetState Т-Банка / платёж ЮKassa) не чаще раза в 5 с и только одна на пачку одновременных запросов; 600 запросов в час с IP → 429 |
| POST | `/api/public/payment/tinkoff-webhook` | все (подпись Token) | Нотификация Т-Банка: терминал и Token магазина заказа, сумма → `OK` / `400 fail` |
| POST | `/api/public/payment/yookassa-webhook` | все | HTTP-уведомление ЮKassa (адрес мерчант указывает в своём кабинете ЮKassa): телу не верим — платёж перезапрашивается из API ключами магазина, сверяются order_id и сумма → `200`; ЮKassa не ответила → `503` (повторит) |
| GET | `/api/actuator/health` | все | Health-check (`{"status":"UP"}`) |

Фоновые задачи: поллер неоплаченных заказов (каждые `PAYMENT_PENDING_CHECK_INTERVAL_MS`: GetState по свежим заказам,
заказы без оплаты дольше суток → `FAILED`), обновление справочника ПВЗ СДЭК (ежедневно в 04:30 МСК, ключами
любого магазина с подключённым СДЭК — справочник у всех аккаунтов один; на свежей платформе его заполняет первое
подключение СДЭК мерчантом) и уборка
осиротевших загрузок S3 (раз в 6 часов: объекты под `shops/` старше суток, на которые не ссылаются ни фото товаров,
ни логотип/фото витрины). Письма об оплаченном заказе (мерчанту и покупателю) уходят один раз, после коммита статуса
`PAID`, в отдельном пуле — вебхук Т-Банка и страница успеха их не ждут; сбой NotiSend только логируется
(почта в логах — только маской `a***@domain`).

Лимиты запросов считаются по IP клиента: последний элемент `X-Forwarded-For` (гейтвей `marketplace-gateway`
перезаписывает его одним адресом клиента; всё левее клиент может подделать), без заголовка — адрес подключения. Лимиты живут в памяти
процесса и сбрасываются рестартом.
