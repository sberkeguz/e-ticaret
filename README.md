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

Her servis yalnızca kendi şemasına erişir: backend `backend_app` (public), iyzico-service `iyzico_app` (iyzico).
`POSTGRES_USER` (eticaret_user) süper kullanıcıdır, yalnızca yönetim (pgAdmin) içindir.
Boş veritabanında kullanıcılar otomatik kurulur. Var olan bir veritabanına uygulamak için (tekrar çalıştırılabilir):

```bash
docker compose cp docker/postgres/service-roles.sql postgres:/tmp/service-roles.sql
docker compose exec postgres psql -U eticaret_user -d eticaret -v backend_password=... -v iyzico_password=... -f /tmp/service-roles.sql
```

### Ödeme akışı

- **2D (3D Secure'suz):** Asenkron. Backend kartı IyzicoService'e senkron HTTP ile gönderir (`Prefer: respond-async`); servis denemeyi kartsız kaydedip hemen `202 Accepted` döner ve iyzico'yu arka planda çağırır. Tarayıcı "Ödemeniz işleniyor…" gösterir ve `GET /api/payments/orders/{id}/status` ile sonucu sorar. Sonuç RabbitMQ olayıyla siparişe işlenir; olay gecikirse durum sorgusu servise doğrudan bakar.
- **3D Secure:** Senkron. Bankanın SMS sayfası hemen gösterilir, dönüş ngrok üzerinden backend'e gelir.

### Mutabakat ve fazla ödemeler

- **Mutabakat işi** (iyzico-service, dakikada bir): takılan denemeleri sonuca bağlar. İstek hiç gitmediyse başarısız sayar, cevabı kaybolduysa iyzico'ya sorar (tekrar göndermez), 15 dakikadır bekleyen 3D ekranını kapatır.
- **Bağlantı ısıtma** (iyzico-service, dakikada bir): iyzico sandbox birkaç dakika sessizlikten sonra (4 dakika bile yetiyor) ilk bağlantıyı ~3 sn bekletip düşürüyor. Servis iyzico'ya küçük bir `HEAD /` isteği atar; düşerse hemen tekrar dener, böylece cezayı ödeme yerine kendisi yer. Para ve kayıtla ilgisi yoktur. Aralık `IYZIPAY_KEEP_WARM_MS` ile değişir (0 = kapalı).
- **Otomatik iade** (backend): aynı siparişe ikinci ödeme, iptal/süresi dolmuş siparişe geç ödeme ya da siparişi olmayan ödeme gelirse iade edilir ve admin panelindeki "Ödeme uyarıları" kartına düşer.

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
