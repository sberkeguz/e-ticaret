# cargo-service

Kargo servisi (.NET 10, ASP.NET Core Minimal API, EF Core 10 + Npgsql, RabbitMQ.Client 7). Şu anki
kapsam: şema, sağlık uçları, outbox yayıncısı (K2), "kargoya ver" komut tüketicisi ve kayıt (K3),
kargo firması arayüzü + sahte firma ve test uçları (K4), gönderiyi firmada açan, takılı kayıtları
uzlaştıran, durumu sorgulayan ve eski veriyi temizleyen arka plan işleri (K5).

## Derleme ve test

Gerekenler: .NET SDK 10.0.401+ (`global.json`), testler için çalışan Docker (Testcontainers).

```sh
cd services/cargo-service
dotnet restore            # CI: dotnet restore --locked-mode
dotnet build -c Release
dotnet test -c Release    # Postgres 17 + RabbitMQ 4 kapsayıcıları oturum başına bir kez açılır
docker build -t cargo-service .
```

- Paket sürümleri `Directory.Packages.props` içinde merkezi tutulur; `packages.lock.json` dosyaları
  commit edilir. Sürüm değiştirince `dotnet restore` kilit dosyalarını günceller.
- Testler Microsoft.Testing.Platform ile çalışır (`global.json` → `test.runner`). Filtre xunit v3
  seçenekleriyle `--` sonrasına yazılır: `dotnet test -c Release -- --filter-class "CargoService.Tests.OutboxRelayTests"`
  (ayrıca `--filter-method "*ad*"`).
- Yeni migration: `dotnet tool restore && dotnet ef migrations add <Ad> --project src/CargoService --output-dir Data/Migrations`
  (veritabanı bağlantısı gerekmez).

## Yapılandırma

| Anahtar (ortam değişkeni) | Açıklama |
|---|---|
| `ConnectionStrings__Cargo` | **Zorunlu.** `cargo_app` rolüyle Npgsql bağlantı dizesi. Yoksa servis açılmaz. |
| `Database__MigrateOnStartup` | Varsayılan `true`: açılışta bekleyen migration'lar uygulanır. |
| `RabbitMq__Url` | amqp(s):// adresi. Boşsa olaylar outbox'ta bekler, komutlar dinlenmez; servis yine açılır. Loglanmaz. |
| `Workers__Enabled` | Varsayılan `true`: bütün arka plan işleri (yayıncı, tüketici, gönderi işleri, temizlik) çalışır (testlerde `false`). |
| `Workers__OutboxInterval` | Varsayılan `00:00:01`: yayıncının yedek tur aralığı (commit sonrası sinyal yanında). |
| `Workers__SendInterval` | Varsayılan `00:00:30`: gönderi açma işinin yedek tur aralığı (yeni REQUESTED gönderi commit edilince iş zaten hemen uyanır). |
| `Workers__ReconcileInterval` | Varsayılan `00:01:00`: uzlaştırıcının tur aralığı. |
| `Workers__SendingStuckAfter` | Varsayılan `00:02:00`: bu süreden uzun SENDING kalan kayıt firmada aranır. |
| `Workers__RetryBaseDelay` | Varsayılan `00:00:30`: belirsiz sonuçtan sonraki bekleme; her denemede iki katı, en fazla 1 saat. |
| `Workers__PollInterval` | Varsayılan `00:05:00`: firmadan takip durumu sorgulama aralığı. |
| `Workers__CleanupInterval` | Varsayılan `1.00:00:00`: temizlik işinin aralığı. |
| `Workers__UndeliveredRecipientRetention` | Varsayılan `90.00:00:00` (sıfırdan büyük olmalı): FAILED ve DELIVERY_FAILED gönderide son değişiklikten bu kadar sonra alıcı bilgisi silinir; DELIVERY_FAILED gönderi bu süreden sonra firmadan sorgulanmaz. |
| `Carrier__Provider` | **Zorunlu, varsayılanı yok.** Şu an yalnızca `Fake` (sahte firma) var. Boşsa ya da birebir `Fake` değilse (`fake`, ` Fake`, `Fakee` dahil) servis açılışta doğrulama hatasıyla durur. |
| `Carrier__Fake__DelayMs` | Sahte firmanın her çağrıdan önceki beklemesi (ms). |
| `Carrier__Fake__FailMode`, `Carrier__Fake__FailTimes` | Açılıştaki hata planı: `None`, `TimeoutBeforeCreate`, `TimeoutAfterCreate`, `Permanent`; kaç gönderi açma çağrısına uygulanacağı. |
| `Carrier__Fake__TestEndpointsEnabled` | Varsayılan `false`. `true` ve `InternalApiToken` doluysa (ve Provider=Fake) test uçları açılır. |
| `InternalApiToken` | İç uçların sırrı (`X-Internal-Token`). Boşsa test uçları hiç eşlenmez. Loglanmaz. |

Port: `8082` (`ASPNETCORE_HTTP_PORTS`).

## Sağlık uçları

- `GET /health/live` — süreç ayaktaysa her zaman 200.
- `GET /health/ready` — veritabanına bağlanılabiliyorsa 200, bağlanılamıyorsa 503. RabbitMQ'nun
  durumu ready'yi etkilemez.

## Mesajlaşma (sözleşme v1)

Tanımlar `src/CargoService/Messaging/CargoTopology.cs` içindedir; backend aynı adları aynı ayarlarla tanımlar.

- Exchange `cargo` (topic, durable, `alternate-exchange=cargo.unrouted`); `cargo.unrouted` fanout +
  aynı adlı durable kuyruk (yönlendirilemeyen mesaj kaybolmaz).
- Komut `commands.shipment.create` → kuyruk `cargo.shipment-requests` (durable,
  `x-dead-letter-exchange=""`, `x-dead-letter-routing-key=cargo.shipment-requests.dlq`); DLQ
  `cargo.shipment-requests.dlq`. Bozuk/eksik alanlı ya da yasak karakterli (U+0000, kontrol karakteri, eşsiz
  vekil, U+FFFD) komut DLQ'ya gider. İşleme hatasında: geçici hata (bağlantı, zaman aşımı, 40001, 40P01)
  → 5 sn sonra kuyruğa geri konur, deneme sınırı yok; kalıcı veri hatası (SqlState sınıf 22/23) → DLQ;
  tanınmayan hata → 5 sn sonra kopyası `x-cargo-attempts` başlığı artırılarak yeniden yayınlanır,
  5. denemede DLQ. Aynı messageId ikinci kez işlenmez.
  DLQ'daki bir komut sorun giderildikten sonra elle `cargo.shipment-requests`'e geri taşınırken
  **`x-cargo-attempts` başlığı silinmelidir**; silinmezse sayaç sınırda kalır (başlık en fazla 5 sayılır)
  ve komut ilk tanınmayan hatada yeniden DLQ'ya düşer.
- Olaylar: `shipment.created`, `shipment.in_transit`, `shipment.delivered`, `shipment.delivery_failed`,
  `shipment.failed` (ret de `shipment.failed`, `data.status=REJECTED`). AMQP messageId = outbox satır id,
  persistent, başlıklar `x-event-version: 1` ve `traceparent`. Olaylar kişisel veri içermez; `sequence`
  gönderi başına artar.
- Yayın outbox üzerinden yapılır: satır yalnızca publisher confirm gelince `published_at` alır; RabbitMQ
  kapalıyken satırlar bekler (`attempts`/`last_error` güncellenir), bağlantı gelince gider. En az bir kez
  teslim: dinleyiciler messageId ile tekrarı ayıklar.

## Gönderi işleri

Kargo firması çağrıları hiçbir zaman açık bir veritabanı işleminin içinde yapılmaz; her çağrı 30 sn ile
sınırlıdır. Firmanın sonucu belirsizse (zaman aşımı, ağ hatası) gönderi FAILED yapılmaz: SENDING kalır,
firmada tekrar anahtarıyla (`idempotency_key`) aranır. Böylece firmada sipariş başına tek gönderi açılır.

- **Gönderi açma** (`ShipmentWorker`): REQUESTED satırları tek bir UPDATE ile `FOR UPDATE SKIP LOCKED`
  sahiplenir (SENDING, `attempts+1`), firmada açar, sonucu satır kilitliyken ve yalnızca arada başka bir iş
  sahiplenmediyse uygular: CREATED + takip no + `shipment.created`; kesin ret → FAILED + `shipment.failed`;
  belirsiz → SENDING, `last_error`, artan geri çekilmeyle `next_attempt_at`.
- **Uzlaştırma** (`ShipmentReconciler`): `SendingStuckAfter`'dan eski ve vadesi gelmiş SENDING kayıtları
  sahiplenir, firmada arar: bulunursa CREATED; yoksa aynı anahtarla açar; belirsizse geri çekilir.
- **Takip sorgusu** (`TrackingPoller`): CREATED/IN_TRANSIT/DELIVERY_FAILED gönderileri sorgular. İzinli
  geçişler: CREATED → IN_TRANSIT/DELIVERED/DELIVERY_FAILED; IN_TRANSIT → DELIVERED/DELIVERY_FAILED;
  DELIVERY_FAILED → IN_TRANSIT/DELIVERED. DELIVERED son durumdur; izin verilmeyen geçiş yok sayılır ve
  uyarı loglanır. Her geçiş aynı işlemde olay yazar (`sequence+1`).
- **Temizlik** (`Cleanup`): 14 günden eski `processed_messages`, yayını 7 günden eski outbox satırları
  silinir (yayınlanmamış olay silinmez); teslimden 30 gün sonra, FAILED/DELIVERY_FAILED gönderide son değişiklikten
  90 gün sonra (`Workers__UndeliveredRecipientRetention`) `shipments.recipient` NULL yapılır.

Birden fazla servis kopyası aynı anda çalışabilir (satır kilitleri ve koşullu güncellemeler çift işlemeyi
önler); temizlik tekrarlanması zararsız ifadelerden oluşur.

### Metrikler

`System.Diagnostics.Metrics` ile, `CargoService` adlı Meter'da:

- `cargo_shipments_uncertain` (gözlemlenebilir gösterge, birim `{shipment}`): 30 dakikadan uzun süredir
  sonucu belirsiz (SENDING) gönderi sayısı; uzlaştırıcının her turunda güncellenir, sıfırdan büyükse uyarı
  loglanır. Dışarı aktarım (OpenTelemetry/OTLP) henüz kurulu değil.

## Sahte kargo firması test uçları

Yalnızca `Carrier:Provider=Fake`, `Carrier:Fake:TestEndpointsEnabled=true` ve `InternalApiToken` dolu
iken vardır (aksi hâlde 404). `X-Internal-Token` yoksa/yanlışsa 401.

- `POST /test/fake-carrier/shipments/{trackingNo}/status` — `{ "status": "IN_TRANSIT" | "DELIVERED" | "DELIVERY_FAILED" }` → 204 (takip no yoksa 404).
  Ardından o gönderi periyodik turu beklemeden hemen sorgulanır (`Workers__Enabled`'dan bağımsız); 204 sorgu bitince döner.
- `POST /test/fake-carrier/faults` — `{ "mode": "TimeoutAfterCreate", "times": 2 }` → 204; `mode: "None"` planı temizler.

## Şema ve veritabanı rolü

Servis yalnızca `cargo` şemasını kullanır ve `cargo_app` rolüyle bağlanır; `cargo_app` `public`
şemasındaki (backend) tablolara erişemez. Tablolar: `shipments`, `outbox_messages`,
`processed_messages`, `fake_carrier_shipments`; migration geçmişi `cargo.__ef_migrations_history`.

Rol ve şema `docker/postgres/service-roles.sql` ile kurulur. Bu betik boş bir Postgres volume'unda
`docker/postgres/init/10-service-roles.sh` tarafından otomatik çalışır (`CARGO_DB_PASSWORD` ortam
değişkeniyle; boşsa `cargo_app`'in parolası olmaz ve parolayla bağlanamaz).

**Var olan bir volume'da** (init betikleri yeniden çalışmaz) betiği elle çalıştırın; tekrar tekrar
çalıştırılabilir, üç parolanın da verilmesi gerekir:

```sh
docker compose cp docker/postgres/service-roles.sql postgres:/tmp/service-roles.sql
docker compose exec postgres psql -v ON_ERROR_STOP=1 -U eticaret_user -d eticaret \
  -v backend_password="$BACKEND_DB_PASSWORD" \
  -v iyzico_password="$IYZICO_DB_PASSWORD" \
  -v cargo_password="$CARGO_DB_PASSWORD" \
  -f /tmp/service-roles.sql
```

Alıcı bilgileri (`shipments.recipient`) kişisel veridir: loglara ve olaylara yazılmaz;
`EnableSensitiveDataLogging` açılmaz; EF Core'un istisnayı satır verisiyle loglayan hata olayları
(SaveChangesFailed, CommandError, QueryIterationFailed, TransactionError) kapalıdır, servis yalnızca istisna
tipini ve SqlState'i loglar; teslimden 30 gün, teslim edilemeyen/açılamayan gönderide 90 gün sonra silinir. Koli bilgisi (`shipments.parcel`)
firmada gönderi açmak için saklanır; firmaya giden gövdeyle birlikte loglanmaz.
