# E-ticaret

## Docker ile çalıştırma

Docker Desktop'ı başlattıktan sonra proje kök dizininde:

```powershell
docker compose up --build
```

Uygulama adresleri:

- Arayüz: http://localhost:3000
- Backend API: http://localhost:8080
- IyzicoService: http://localhost:8081 (yalnızca bu bilgisayardan; API belgesi: `/swagger-ui`)
- PostgreSQL: `localhost:5432` (`eticaret` veritabanı)
- RabbitMQ yönetim arayüzü: http://localhost:15672 (kullanıcı `eticaret`; işlenemeyen mesajlar `backend.payment-events.dlq` kuyruğunda)
- Grafana (izleme): http://localhost:3001 → *Dashboards › E-ticaret › Ödeme akışı* (giriş gerekmez, yalnızca bu bilgisayardan)

Veritabanı verileri `postgres_data` adlı Docker volume'unda saklanır. Servisleri durdurmak için `Ctrl+C`, arka planda başlatılan servisleri kaldırmak için `docker compose down` kullanın. Veritabanı verilerini de silmek isterseniz `docker compose down -v` çalıştırın.

Yerel geliştirmede arayüzü `admin-panel` klasöründen `npm run dev` ile başlatabilirsiniz. Vite, `/api` isteklerini `http://localhost:8080` adresindeki backend'e yönlendirir.

Compose varsayılan parolaları yalnızca yerel geliştirme içindir. Paylaşılan/üretim ortamında başlatmadan önce `POSTGRES_PASSWORD` ve `JWT_SECRET` ortam değişkenlerini ayarlayın.

## Servisler

```
Tarayıcı → Backend (8080) → IyzicoService (8081) → iyzico
              └──────────────┴──→ PostgreSQL (public şeması / iyzico şeması)
```

- **Backend**: sipariş, stok, sepet, kullanıcı. iyzico ile doğrudan konuşmaz.
- **IyzicoService** (`services/iyzico-service/`, NestJS + Prisma): iyzico ile konuşan tek yer. Her ödeme denemesini kart verisi olmadan
  `iyzico.payment_attempts` tablosunda bir durum makinesiyle tutar. Backend ona `X-Internal-Token` ile ulaşır.

Çift ödemeye karşı:

- Tarayıcı her ödeme denemesi için bir `Idempotency-Key` üretir; tekrar gönderimde aynı anahtar gider ve kart ikinci kez çekilmez.
- Veritabanı, bir siparişin aynı anda tek aktif denemesi ve tek başarılı ödemesi olmasını kısıtlarla garanti eder.
- iyzico'dan cevap gelmezse istek tekrar gönderilmez; deneme `VERIFYING` olur ve iyzico'ya sorulur.
- 3D Secure dönüşü yalnızca bir kez işlenir.

### Veritabanı kullanıcıları

Her servis yalnızca kendi şemasına erişir: backend `backend_app` (public), iyzico-service `iyzico_app` (iyzico), cargo-service `cargo_app` (cargo).
`POSTGRES_USER` (eticaret_user) süper kullanıcıdır, yalnızca yönetim (pgAdmin) içindir.
Boş veritabanında kullanıcılar otomatik kurulur. Var olan bir veritabanına uygulamak için (tekrar çalıştırılabilir):

```bash
docker compose cp docker/postgres/service-roles.sql postgres:/tmp/service-roles.sql
docker compose exec postgres psql -U eticaret_user -d eticaret -v backend_password=... -v iyzico_password=... -v cargo_password=... -f /tmp/service-roles.sql
```

### Kargo servisi ilk kurulum

cargo-service (`services/cargo-service/`, .NET) için `.env` dosyasında şu değerler **zorunludur**; biri eksikse
`docker compose` hiçbir servisi başlatmaz (varsayılan parola yoktur):

| Değişken | Ne için |
|---|---|
| `CARGO_DB_PASSWORD` | `cargo_app` veritabanı kullanıcısı (yalnız `cargo` şeması) |
| `RABBITMQ_CARGO_PASSWORD` | cargo-service'in kendi RabbitMQ kullanıcısı (`RABBITMQ_CARGO_USER`, varsayılan `cargo`). URL içinde gider: yalnız harf, rakam, `-`, `_` |
| `CARGO_INTERNAL_TOKEN` | cargo-service iç uçlarının (`X-Internal-Token`) sırrı; `INTERNAL_API_TOKEN`'dan farklı olmalı |

RabbitMQ kullanıcısını her `docker compose up`'ta `rabbitmq-init` kurar (tekrar çalıştırılabilir; parola
değişirse günceller). Bu kullanıcı yalnız `cargo.*` kuyruk/exchange'lerine erişir ve `cargo` exchange'ine yalnız
`shipment.*` olayları yayınlayabilir; ödeme kuyruklarını okuyamaz, kargo komutu (`commands.shipment.create`) gönderemez.
Varsayılan exchange'e (`""`, `amq.default`) yazma izni yoktur; bu yüzden başka servislerin kuyruklarına
(ör. `iyzico.charge-requests`) mesaj bırakamaz. Komut kuyruğunun DLQ'su adlı `cargo.dlx`, yeniden denemesi adlı
`cargo.retry` exchange'i üzerinden gider. İzin tablosu `docker/rabbitmq/init-cargo-user.sh`'nin başındadır.

#### Geçiş: `cargo.shipment-requests` kuyruğunun yeni argümanları

Bu sürümde `cargo.shipment-requests` kuyruğunun `x-dead-letter-exchange` argümanı `""` yerine `cargo.dlx` oldu.
Broker'da kuyruk eski argümanlarla duruyorsa backend ve cargo-service onu tanımlarken `PRECONDITION_FAILED`
(406) alır ve komut dinlenmez. RabbitMQ kuyruk argümanını değiştirmeye izin vermez; kuyruk **boşken** silinip
yeni sürümle yeniden tanımlanmalıdır. Kod kuyruğu kendiliğinden silmez (veri kaybı olmasın diye). Bir kez:

```bash
# 1. Yeni komut gelmesin, eski cargo-service kuyruğu boşaltsın: önce backend'i durdur, cargo-service (eski sürüm) çalışırken bekle.
docker compose stop backend
# 2. Kuyrukta bekleyen ya da onaylanmamış mesaj olmadığını doğrula (messages ve messages_unacknowledged 0 olmalı).
docker compose exec rabbitmq rabbitmqctl list_queues name messages messages_unacknowledged arguments
# 3. cargo-service'i durdur ve kuyruğu YALNIZ BOŞSA sil (--if-empty: mesaj varsa RabbitMQ silmeyi reddeder).
docker compose stop cargo-service
docker compose exec rabbitmq rabbitmqctl delete_queue --if-empty cargo.shipment-requests
# 4. Yeni sürümle başlat: rabbitmq-init izinleri günceller, servisler kuyruğu yeni argümanlarla tanımlar.
docker compose up -d --build
```

HTTP API ile 3. adımın karşılığı: `DELETE http://localhost:15672/api/queues/%2F/cargo.shipment-requests?if-empty=true`
(yönetici kullanıcısıyla). **Kuyrukta mesaj varsa silme:** önce eski cargo-service'in kuyruğu boşaltmasını bekle
ya da mesajları yönetim arayüzünden (*Move messages*, shovel eklentisi) geçici bir kuyruğa taşı, kuyruk yeniden
tanımlandıktan sonra geri taşı. `cargo.shipment-requests.dlq` kuyruğu değişmez (argümansız, yalnız yeni bir bağ alır);
silinmez.

Veritabanı daha önce (cargo_app olmadan) oluşturulduysa `cargo_app` rolünü bir kez kurun
(`.env`'deki `CARGO_DB_PASSWORD` ile aynı parola; diğer iki parola da mevcut değerleri olmalı, komut onları yeniden yazar):

```bash
docker compose up -d postgres
docker compose cp docker/postgres/service-roles.sql postgres:/tmp/service-roles.sql
docker compose exec postgres psql -U eticaret_user -d eticaret -v backend_password=... -v iyzico_password=... -v cargo_password=... -f /tmp/service-roles.sql
docker compose up -d --build cargo-service
```

### Ödeme akışı

- **2D (3D Secure'suz):** Asenkron. Backend kartı IyzicoService'e senkron HTTP ile gönderir (`Prefer: respond-async`); servis denemeyi kartsız kaydedip hemen `202 Accepted` döner ve iyzico'yu arka planda çağırır. Tarayıcı "Ödemeniz işleniyor…" gösterir ve `GET /api/payments/orders/{id}/status` ile sonucu sorar. Sonuç RabbitMQ olayıyla siparişe işlenir; olay gecikirse durum sorgusu servise doğrudan bakar.
- **3D Secure:** Senkron. Bankanın SMS sayfası hemen gösterilir, dönüş ngrok üzerinden backend'e gelir.
- **Sonucun hızlı gelmesi:** iyzico cevap verince olay outbox'a yazılır; veritabanı bunu anında haber verir (`LISTEN/NOTIFY`), olay beklemeden RabbitMQ'ya gider (yedek olarak saniyede bir kontrol). "Ödemeniz işleniyor…" ekranı sonucu bir kez sorar (`GET /api/payments/orders/{id}/status?waitMs=10000`); backend cevabı olay işlenene kadar bekletir ve hemen döner. Bekleyen soru sunucuda iş parçacığı ve veritabanı bağlantısı tutmaz (`DeferredResult`, `spring.jpa.open-in-view=false`). Backend birden fazla kopya çalışırsa olay başka kopyada işlenebilir; o zaman bekleme süresi dolup ekran tekrar sorar (yavaşlar, bozulmaz).

### Mutabakat ve fazla ödemeler

- **Mutabakat işi** (iyzico-service, dakikada bir): takılan denemeleri sonuca bağlar. İstek hiç gitmediyse başarısız sayar, cevabı kaybolduysa iyzico'ya sorar (tekrar göndermez), 15 dakikadır bekleyen 3D ekranını kapatır.
- **Bağlantı ısıtma** (iyzico-service, dakikada bir): iyzico sandbox birkaç dakika sessizlikten sonra (4 dakika bile yetiyor) ilk bağlantıyı ~3 sn bekletip düşürüyor. Servis iyzico'ya küçük bir `HEAD /` isteği atar; düşerse hemen tekrar dener, böylece cezayı ödeme yerine kendisi yer. Para ve kayıtla ilgisi yoktur. Aralık `IYZIPAY_KEEP_WARM_MS` ile değişir (0 = kapalı).
- **Fazla ödeme uyarısı** (backend): aynı siparişe ikinci ödeme, iptal/süresi dolmuş siparişe geç ödeme ya da siparişi olmayan ödeme gelirse para **otomatik iade edilmez**; admin panelindeki "Ödeme uyarıları" kartına düşer, admin karar verir.

### Olaylar (RabbitMQ)

IyzicoService ödeme sonuçlandığında (başarılı, başarısız, doğrulanıyor, iade) bir olay yayınlar; kart verisi içermez.

- **Outbox:** Olay, durum değişikliğiyle aynı veritabanı işleminde `iyzico.outbox_messages` tablosuna yazılır. Relay bunları RabbitMQ'ya gönderir; RabbitMQ kapalıysa olaylar bekler, kaybolmaz.
- **Tekrar gelen mesaj:** Backend her mesajın numarasını `processed_messages` tablosuna yazar; aynı numara ikinci kez gelirse atlanır.
- **Önce iş, sonra onay:** Mesaj, veritabanı işlemi bittikten sonra onaylanır.
- **Tekrar deneme:** İşlenemeyen mesaj 5 sn, 30 sn, 2 dk sonra tekrar denenir; yine olmazsa `backend.payment-events.dlq` kuyruğuna taşınır.
- **Sürüm:** Olay alanları silinmez, yalnızca eklenir; eski dinleyici yeni alanı görmezden gelir.

Yeni mikroservisler `services/` altında NestJS ile yazılır; backend Spring Boot olarak kalır.
IyzicoService'in testleri Testcontainers ile gerçek Postgres kullanır (Docker açık olmalı):

```powershell
cd services/iyzico-service; npm install; npm test
```

### İzleme (OpenTelemetry + Grafana)

- Her istek bir **iz (trace)** açar. Backend'in iyzico-service çağrısı, iyzico-service'in veritabanı sorguları ve iyzico API çağrıları, RabbitMQ olayı ve backend'in olayı işlemesi aynı izde görünür (W3C `traceparent` başlığı). Olay geç yayınlansa bile (outbox) ödemenin izine bağlanır.
- **Ölçümler:** istek süreleri (p50/p95), iyzico çağrı süreleri, düşen bağlantılar, hata oranı ve RabbitMQ akışı Prometheus'a gider.
- Hepsi OTLP ile `lgtm` konteynerine gider (Grafana + Tempo + Prometheus). Pano dosyası: `docker/grafana/dashboards/odeme-akisi.json`.
- Adres verilmezse (testler, Docker'sız çalıştırma) hiçbir şey gönderilmez: backend'de `OTEL_ENABLED`, iyzico-service'te `OTEL_EXPORTER_OTLP_ENDPOINT`.
- Zamanlar her yerde UTC tutulur (`timestamptz`, API'de `...Z`); ekranda tarayıcının saat dilimine çevrilir.
