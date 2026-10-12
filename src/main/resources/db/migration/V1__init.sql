CREATE TABLE shop (
    id                       UUID PRIMARY KEY,
    slug                     VARCHAR(32)  NOT NULL UNIQUE,
    name                     VARCHAR(120) NOT NULL,
    active                   BOOLEAN      NOT NULL,
    brand_color              VARCHAR(7)   NOT NULL,
    logo_key                 VARCHAR(512),
    hero_image_key           VARCHAR(512),
    tagline                  VARCHAR(160),
    description              TEXT,
    about                    TEXT,
    support_telegram         VARCHAR(64),
    contact_email            VARCHAR(255),
    contact_phone            VARCHAR(32),
    seller_name              VARCHAR(255),
    seller_inn               VARCHAR(12),
    seller_ogrn              VARCHAR(15),
    seller_address           VARCHAR(512),
    cdek_from_pvz_code       VARCHAR(64),
    cdek_from_city           VARCHAR(255),
    cdek_from_postal_code    VARCHAR(16),
    cdek_from_address        VARCHAR(512),
    tinkoff_terminal_key     VARCHAR(64),
    tinkoff_password_enc     TEXT,
    tinkoff_receipt_enabled  BOOLEAN      NOT NULL,
    tinkoff_taxation         VARCHAR(32),
    tinkoff_tax              VARCHAR(16),
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL
);

CREATE TABLE users (
    id             BIGSERIAL PRIMARY KEY,
    email          VARCHAR(255) NOT NULL UNIQUE,
    name           VARCHAR(255),
    shop_id        UUID UNIQUE REFERENCES shop(id),
    created_at     TIMESTAMPTZ NOT NULL,
    last_login_at  TIMESTAMPTZ
);

CREATE TABLE login_code (
    email       VARCHAR(255) PRIMARY KEY,
    code_hash   VARCHAR(128) NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    sent_at     TIMESTAMPTZ  NOT NULL,
    attempts    INT          NOT NULL
);

CREATE TABLE product (
    id                     UUID PRIMARY KEY,
    shop_id                UUID         NOT NULL REFERENCES shop(id) ON DELETE CASCADE,
    name                   VARCHAR(255) NOT NULL,
    description            TEXT         NOT NULL,
    price_kopecks          BIGINT       NOT NULL,
    crossed_price_kopecks  BIGINT,
    active                 BOOLEAN      NOT NULL,
    preorder               BOOLEAN      NOT NULL,
    order_number           INT          NOT NULL,
    weight_grams           INT,
    length_cm              INT,
    width_cm               INT,
    height_cm              INT,
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_product_shop ON product(shop_id, order_number);

CREATE TABLE product_photo (
    id          UUID PRIMARY KEY,
    product_id  UUID         NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    s3_key      VARCHAR(512) NOT NULL,
    position    INT          NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_product_photo_product ON product_photo(product_id, position);

CREATE TABLE product_variant (
    id             UUID PRIMARY KEY,
    product_id     UUID         NOT NULL REFERENCES product(id) ON DELETE CASCADE,
    label          VARCHAR(255) NOT NULL,
    price_kopecks  BIGINT       NOT NULL,
    order_number   INT          NOT NULL
);
CREATE INDEX idx_product_variant_product ON product_variant(product_id, order_number);

CREATE TABLE orders (
    id                    BIGSERIAL PRIMARY KEY,
    public_id             VARCHAR(8)   NOT NULL UNIQUE,
    shop_id               UUID         NOT NULL REFERENCES shop(id),
    payment_status        VARCHAR(32)  NOT NULL,
    fulfillment_status    VARCHAR(32)  NOT NULL,
    customer_name         VARCHAR(255) NOT NULL,
    customer_phone        VARCHAR(32)  NOT NULL,
    customer_email        VARCHAR(255) NOT NULL,
    pvz_code              VARCHAR(64)  NOT NULL,
    pvz_city              VARCHAR(255),
    pvz_address           VARCHAR(512),
    comment               TEXT,
    marketing_consent     BOOLEAN      NOT NULL,
    items_total_kopecks   BIGINT       NOT NULL,
    tracking_number       VARCHAR(64),
    tinkoff_payment_id    VARCHAR(64),
    payment_url           TEXT,
    payment_checked_at    TIMESTAMPTZ,
    paid_at               TIMESTAMPTZ,
    paid_notified         BOOLEAN      NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_orders_shop_paid ON orders(shop_id, payment_status, paid_at);
CREATE INDEX idx_orders_awaiting ON orders(payment_status, created_at);

CREATE TABLE order_item (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT       NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id          UUID         REFERENCES product(id) ON DELETE SET NULL,
    variant_id          UUID         REFERENCES product_variant(id) ON DELETE SET NULL,
    product_name        VARCHAR(512) NOT NULL,
    unit_price_kopecks  BIGINT       NOT NULL,
    quantity            INT          NOT NULL
);
CREATE INDEX idx_order_item_order ON order_item(order_id);

-- Кэш ПВЗ СДЭК (как V56 в anyforms)
CREATE TABLE cdek_pvz (
    code         VARCHAR(64) PRIMARY KEY,
    name         VARCHAR,
    country_code VARCHAR(8),
    region       VARCHAR,
    city         VARCHAR,
    postal_code  VARCHAR(16),
    address      VARCHAR,
    address_full VARCHAR,
    work_time    VARCHAR,
    longitude    DOUBLE PRECISION,
    latitude     DOUBLE PRECISION,
    synced_at    TIMESTAMP NOT NULL
);
