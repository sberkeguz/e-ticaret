# E-ticaret

## Docker ile çalıştırma

Docker Desktop'ı başlattıktan sonra proje kök dizininde:

```powershell
docker compose up --build
```

Uygulama adresleri:

- Arayüz: http://localhost:3000
- Backend API: http://localhost:8080
- PostgreSQL: `localhost:5432` (`eticaret` veritabanı)

Veritabanı verileri `postgres_data` adlı Docker volume'unda saklanır. Servisleri durdurmak için `Ctrl+C`, arka planda başlatılan servisleri kaldırmak için `docker compose down` kullanın. Veritabanı verilerini de silmek isterseniz `docker compose down -v` çalıştırın.

Yerel geliştirmede arayüzü `admin-panel` klasöründen `npm run dev` ile başlatabilirsiniz. Vite, `/api` isteklerini `http://localhost:8080` adresindeki backend'e yönlendirir.

Compose varsayılan parolaları yalnızca yerel geliştirme içindir. Paylaşılan/üretim ortamında başlatmadan önce `POSTGRES_PASSWORD` ve `JWT_SECRET` ortam değişkenlerini ayarlayın.
