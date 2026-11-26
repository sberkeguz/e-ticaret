using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;

namespace CargoService.Data;

/// <summary>
/// Kargo servisinin veritabanı. Tüm tablolar <c>cargo</c> şemasındadır, sütun adları snake_case'tir;
/// migration geçmişi <c>cargo.__ef_migrations_history</c> tablosunda tutulur.
/// Hassas veri loglaması (EnableSensitiveDataLogging) bilerek hiçbir yerde açılmaz: alıcı bilgisi
/// SQL parametresi olarak loglara düşmemelidir.
/// </summary>
public sealed class CargoDbContext(DbContextOptions<CargoDbContext> options) : DbContext(options)
{
    public const string Schema = "cargo";
    public const string MigrationsHistoryTable = "__ef_migrations_history";
    public const string FakeCarrierTrackingSequence = "fake_carrier_tracking_seq";

    public DbSet<Shipment> Shipments => Set<Shipment>();

    public DbSet<OutboxMessage> OutboxMessages => Set<OutboxMessage>();

    public DbSet<ProcessedMessage> ProcessedMessages => Set<ProcessedMessage>();

    public DbSet<FakeCarrierShipment> FakeCarrierShipments => Set<FakeCarrierShipment>();

    /// <summary>
    /// Uygulamanın ve tasarım zamanı aracının (dotnet ef) ortak sağlayıcı ayarı.
    /// EF Core'un istisnayı olduğu gibi logladığı hata olayları kapatılır (<see cref="DataBearingErrorEvents"/>):
    /// istisnanın ToString'i PostgresException.Where/Detail alanlarında satır verisini (alıcı adı, telefon,
    /// adres) taşıyabilir. İstisna yine fırlatılır; çağıran yalnızca tipini ve SqlState'ini loglar.
    /// </summary>
    public static void Configure(DbContextOptionsBuilder options, string connectionString) =>
        options
            .UseNpgsql(connectionString, npgsql => npgsql.MigrationsHistoryTable(MigrationsHistoryTable, Schema))
            .UseSnakeCaseNamingConvention()
            .ConfigureWarnings(warnings => warnings.Ignore(DataBearingErrorEvents));

    /// <summary>İstisnayla birlikte loglanan ve bu yüzden satır verisi sızdırabilen EF Core hata olayları.</summary>
    public static readonly EventId[] DataBearingErrorEvents =
    [
        CoreEventId.SaveChangesFailed,        // Microsoft.EntityFrameworkCore.Update
        CoreEventId.QueryIterationFailed,     // Microsoft.EntityFrameworkCore.Query
        RelationalEventId.CommandError,       // Microsoft.EntityFrameworkCore.Database.Command
        RelationalEventId.TransactionError,   // Microsoft.EntityFrameworkCore.Database.Transaction
    ];

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.HasDefaultSchema(Schema);

        modelBuilder.Entity<Shipment>(shipment =>
        {
            var statuses = string.Join(", ", ShipmentStatusConverter.AllDbValues.Select(s => $"'{s}'"));
            shipment.ToTable("shipments", t => t.HasCheckConstraint("ck_shipments_status", $"status IN ({statuses})"));
            shipment.HasKey(s => s.Id);
            shipment.Property(s => s.Id).ValueGeneratedNever();
            shipment.HasIndex(s => s.OrderId).IsUnique();
            shipment.HasIndex(s => s.IdempotencyKey).IsUnique();
            shipment.Property(s => s.IdempotencyKey).HasMaxLength(100);
            shipment.Property(s => s.Status).HasConversion<ShipmentStatusConverter>().HasMaxLength(30);
            shipment.Property(s => s.CarrierCode).HasMaxLength(50);
            shipment.Property(s => s.CarrierName).HasMaxLength(100);
            shipment.Property(s => s.TrackingNumber).HasMaxLength(100);
            shipment.Property(s => s.FailureReason).HasMaxLength(500);
            shipment.Property(s => s.LastError).HasMaxLength(500);
            shipment.Property(s => s.Recipient).HasColumnType("jsonb");
            shipment.Property(s => s.Parcel).HasColumnType("jsonb");
            // Npgsql: uint + IsRowVersion → PostgreSQL'in xmin sistem sütunu (tabloya yeni sütun eklenmez).
            shipment.Property(s => s.Version).IsRowVersion();
        });

        modelBuilder.Entity<OutboxMessage>(outbox =>
        {
            outbox.ToTable("outbox_messages");
            outbox.HasKey(o => o.Id);
            outbox.Property(o => o.Id).ValueGeneratedNever();
            outbox.Property(o => o.Exchange).HasMaxLength(100);
            outbox.Property(o => o.RoutingKey).HasMaxLength(100);
            outbox.Property(o => o.Type).HasMaxLength(100);
            outbox.Property(o => o.Payload).HasColumnType("jsonb");
            outbox.Property(o => o.CreatedAt).HasPrecision(3);
            outbox.Property(o => o.PublishedAt).HasPrecision(3);
            outbox.Property(o => o.Attempts).HasDefaultValue(0);
            outbox.Property(o => o.LastError).HasMaxLength(500);
            // Yayıncı yalnızca gönderilmemiş satırlara bakar; bu kısmi indeks onları hızlı bulur.
            outbox.HasIndex(o => o.CreatedAt)
                .HasDatabaseName("ix_outbox_unpublished")
                .HasFilter("published_at IS NULL");
        });

        modelBuilder.Entity<ProcessedMessage>(processed =>
        {
            processed.ToTable("processed_messages");
            processed.HasKey(p => p.MessageId);
            processed.Property(p => p.MessageId).HasMaxLength(100);
        });

        modelBuilder.Entity<FakeCarrierShipment>(fake =>
        {
            fake.ToTable("fake_carrier_shipments");
            fake.HasKey(f => f.IdempotencyKey);
            fake.Property(f => f.IdempotencyKey).HasMaxLength(100);
            fake.Property(f => f.TrackingNumber).HasMaxLength(100);
            fake.HasIndex(f => f.TrackingNumber).IsUnique();
            fake.Property(f => f.Status).HasMaxLength(30);
        });

        // Sahte firmanın takip numarası sırası ("TK" + 10 hane); eşzamanlı isteklerde de tekildir.
        modelBuilder.HasSequence<long>(FakeCarrierTrackingSequence);
    }
}
