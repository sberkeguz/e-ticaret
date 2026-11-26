using System.Text.Json;
using CargoService.Data;
using CargoService.Messaging.Contracts;
using CargoService.Shipping;
using Microsoft.EntityFrameworkCore;
using static CargoService.Tests.Infrastructure.Shipments;

namespace CargoService.Tests;

/// <summary>
/// QA: backend (Java) ile iki yönlü sözleşme kontrolü, dosya üzerinden ({repo}/target/cargo-contract).
///  - backend-commands/*.json: backend'in KABUL ETTİĞİ ve outbox'a yazdığı gerçek komut JSON'u
///    (CargoCrossContractQaTest yazar). Hepsi gerçek Validate()'ten geçmeli; geçmeyen komut DLQ'ya gider ve
///    backend'de gönderi REQUESTED'da takılır.
///  - cargo-events/*.json: gerçek ShipmentEvents.Append çıktısı; backend testi okur.
/// Docker gerektirmez.
/// </summary>
public sealed class BackendContractFilesTests
{
    private static string? ContractDir()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            if (File.Exists(Path.Combine(dir.FullName, "pom.xml")))
            {
                return Path.Combine(dir.FullName, "target", "cargo-contract");
            }
        }
        return null;
    }

    [Fact]
    public void Backendin_kabul_ettiği_her_komut_cargo_doğrulamasından_geçer()
    {
        var root = ContractDir();
        var dir = root is null ? null : Path.Combine(root, "backend-commands");
        if (dir is null || !Directory.Exists(dir) || Directory.GetFiles(dir, "*.json").Length == 0)
        {
            Assert.Skip("Önce backend testini (CargoCrossContractQaTest) çalıştırın: " + dir);
        }
        var problems = new List<string>();
        foreach (var file in Directory.GetFiles(dir, "*.json").Order())
        {
            var command = JsonSerializer.Deserialize<ShipmentRequested>(File.ReadAllBytes(file), JsonSerializerOptions.Web);
            Assert.NotNull(command);
            var errors = command.Validate();
            if (errors.Count > 0)
            {
                problems.Add($"{Path.GetFileName(file)}: {string.Join(",", errors)}");
            }
        }
        Assert.Empty(problems);
    }

    [Theory]
    [InlineData(" ")]
    [InlineData(" ")]
    [InlineData(" ")]
    public void Yalnız_Unicode_boşluk_olan_alıcı_alanı_geçersizdir(string space)
    {
        var command = Command(77) with { Recipient = Recipient() with { District = space } };
        Assert.Equal(["recipient.district"], command.Validate());
    }

    [Fact]
    public void Gerçek_olay_çıktısı_backend_için_dosyaya_yazılır()
    {
        var root = ContractDir();
        if (root is null)
        {
            Assert.Skip("Depo kökü (pom.xml) bulunamadı");
        }
        var dir = Directory.CreateDirectory(Path.Combine(root, "cargo-events")).FullName;
        foreach (var old in Directory.GetFiles(dir, "*.json"))
        {
            File.Delete(old);
        }
        using var db = new CargoDbContext(new DbContextOptionsBuilder<CargoDbContext>()
            .UseNpgsql("Host=127.0.0.1;Port=1;Database=none").Options);
        var cases = new (string Name, ShipmentStatus Status, bool Tracking, string? Failure)[]
        {
            ("1-created", ShipmentStatus.Created, true, null),
            ("2-in-transit", ShipmentStatus.InTransit, true, null),
            ("3-delivery-failed", ShipmentStatus.DeliveryFailed, true, null),
            ("4-delivered", ShipmentStatus.Delivered, true, null),
            ("5-failed", ShipmentStatus.Failed, false, "Adres firmada doğrulanamadı"),
            ("6-rejected", ShipmentStatus.Rejected, false, "Ödeme onaylanmadı"),
        };
        foreach (var c in cases)
        {
            var shipment = new Shipment
            {
                Id = Guid.CreateVersion7(),
                OrderId = 9_000_000_001,
                IdempotencyKey = "order-9000000001",
                RequestNo = 2,
                Status = c.Status,
                CarrierCode = c.Tracking ? "FAKE" : null,
                CarrierName = c.Tracking ? "Sahte Kargo" : null,
                TrackingNumber = c.Tracking ? "FK0000000123" : null,
                FailureReason = c.Failure,
                EventSequence = 6,
            };
            var row = ShipmentEvents.Append(db, shipment, DateTimeOffset.UtcNow, null);
            Assert.NotNull(row);
            File.WriteAllText(Path.Combine(dir, c.Name + ".json"), row.Payload);
        }
        Assert.Equal(6, Directory.GetFiles(dir, "*.json").Length);
    }
}
