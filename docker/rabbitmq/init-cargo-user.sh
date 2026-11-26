#!/bin/sh
# cargo-service'in RabbitMQ kullanıcısını ve dar izinlerini kurar (her "up"ta çalışır, tekrar çalıştırılabilir).
# Yönetim HTTP API'si kullanılır; ana kullanıcıya (RABBITMQ_USER) dokunulmaz.
#
# İzinler (RabbitMQ erişim kontrolü: https://www.rabbitmq.com/docs/access-control):
#   configure  cargo, cargo.unrouted, cargo.dlx, cargo.retry, cargo.shipment-requests, cargo.shipment-requests.dlq
#              (exchange.declare / queue.declare yalnız bu adlar)
#   write      cargo                       → olay yayını (shipment.*; konu izniyle daraltılır)
#              cargo.unrouted              → "cargo" exchange'inin alternate-exchange'i (declare) + unrouted kuyruğuna bağ
#              cargo.dlx                   → komut kuyruğunun DLX'i (DLX'li queue.declare: write DLX'te)
#              cargo.retry                 → tanınmayan hatada komutun sayaçlı kopyasının yeniden yayını
#              cargo.shipment-requests     → komut kuyruğuna bağlar (queue.bind: write kuyrukta)
#              cargo.shipment-requests.dlq → DLQ'nun cargo.dlx'e bağı
#   read       cargo, cargo.unrouted, cargo.dlx, cargo.retry → bağ kaynağı (queue.bind: read exchange'de),
#                                            AE'li exchange.declare (read: declare edilen exchange'de)
#              cargo.shipment-requests     → komut tüketimi + DLX'li queue.declare (read: kuyrukta)
#   Varsayılan exchange (amq.default) YOK: direct exchange'de konu izni çalışmadığından "" üzerindeki yazma izni
#   her kuyruğa (ör. iyzico.charge-requests) mesaj bırakmaya izin verirdi.
#   topic (cargo exchange'i)  write ^shipment\.  → cargo kullanıcısı "commands.shipment.create" YAYINLAYAMAZ
#                             read  ^commands\.shipment\.create$ → yalnız komut bağını kurabilir
set -eu

: "${RABBITMQ_ADMIN_USER:?RABBITMQ_ADMIN_USER gerekli}"
: "${RABBITMQ_ADMIN_PASSWORD:?RABBITMQ_ADMIN_PASSWORD gerekli}"
: "${CARGO_USER:?CARGO_USER gerekli}"
: "${CARGO_PASSWORD:?CARGO_PASSWORD gerekli}"
API="${RABBITMQ_API:-http://rabbitmq:15672/api}"
VHOST="%2F"

# JSON / curl yapılandırma dizgesi içinde ters bölü ve çift tırnak kaçırılır.
esc() { printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'; }

# Kimlik bilgisi komut satırında (ps) görünmesin diye curl'e stdin'den yapılandırma olarak verilir.
call() { # $1 yöntem, $2 yol, $3 gövde (JSON, boş olabilir)
  printf '%s' "$3" > /tmp/body.json
  code=$(printf 'user = "%s:%s"\n' "$(esc "$RABBITMQ_ADMIN_USER")" "$(esc "$RABBITMQ_ADMIN_PASSWORD")" \
    | curl -sS -K - -o /tmp/resp -w '%{http_code}' -X "$1" -H 'content-type: application/json' \
        --data-binary @/tmp/body.json "$API$2") || code=000
  case "$code" in
    2??) return 0 ;;
    *) echo "rabbitmq-init: $1 $2 -> HTTP $code $(cat /tmp/resp 2>/dev/null || true)" >&2; return 1 ;;
  esac
}

# Sağlık kontrolü (ping) yönetim eklentisinden önce yeşile dönebilir; API hazır olana kadar beklenir.
i=0
until call GET /whoami ""; do
  i=$((i + 1))
  [ "$i" -ge 60 ] && { echo "rabbitmq-init: yönetim API'sine ulaşılamadı" >&2; exit 1; }
  sleep 2
done

USER_PATH=$(printf '%s' "$CARGO_USER" | sed 's/%/%25/g; s/ /%20/g')

# Etiket yok: yönetim arayüzüne giremez, yalnız AMQP.
call PUT "/users/$USER_PATH" "{\"password\":\"$(esc "$CARGO_PASSWORD")\",\"tags\":\"\"}"

call PUT "/permissions/$VHOST/$USER_PATH" \
  '{"configure":"^cargo(\\.unrouted|\\.dlx|\\.retry|\\.shipment-requests(\\.dlq)?)?$","write":"^cargo(\\.unrouted|\\.dlx|\\.retry|\\.shipment-requests(\\.dlq)?)?$","read":"^cargo(\\.unrouted|\\.dlx|\\.retry|\\.shipment-requests)?$"}'

# Konu izni yalnız var olan exchange'e yazılabilir. Exchange'ler kodla (CargoTopology.cs / CargoTopology.java)
# BİREBİR aynı ayarlarla tanımlanır; ayar farklıysa RabbitMQ 406 döner ve kurulum durur (sözleşme bozulmuş demektir).
call PUT "/exchanges/$VHOST/cargo.unrouted" \
  '{"type":"fanout","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
call PUT "/exchanges/$VHOST/cargo" \
  '{"type":"topic","durable":true,"auto_delete":false,"internal":false,"arguments":{"alternate-exchange":"cargo.unrouted"}}'
call PUT "/exchanges/$VHOST/cargo.dlx" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'
call PUT "/exchanges/$VHOST/cargo.retry" \
  '{"type":"direct","durable":true,"auto_delete":false,"internal":false,"arguments":{}}'

call PUT "/topic-permissions/$VHOST/$USER_PATH" \
  '{"exchange":"cargo","write":"^shipment\\.","read":"^commands\\.shipment\\.create$"}'

echo "rabbitmq-init: '$CARGO_USER' kullanıcısı ve izinleri hazır"
