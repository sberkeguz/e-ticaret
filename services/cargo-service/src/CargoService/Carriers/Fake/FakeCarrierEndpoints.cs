using System.Security.Cryptography;
using System.Text;
using CargoService.Configuration;
using CargoService.Data;
using CargoService.Shipping;

namespace CargoService.Carriers.Fake;

/// <summary>
/// Sahte kargo firmasını testlerden yönetmek için uçlar (/test/fake-carrier/*). YALNIZCA şu üç koşul
/// birlikte sağlanınca eşlenir; aksi hâlde hiç yoktur (404):
///  - <c>Carrier:Provider=Fake</c>, - <c>Carrier:Fake:TestEndpointsEnabled=true</c>,
///  - <c>InternalApiToken</c> dolu.
/// Her istek <c>X-Internal-Token</c> başlığını taşımalıdır; yoksa ya da yanlışsa 401. Karşılaştırma
/// iki değerin SHA-256 özetleri üzerinden sabit zamanlıdır (uzunluk da sızmaz). Token denetimi, gövde
/// okunmadan önce bir ara katmanda yapılır: tokensız istek gövdesi bozuk olsa da 401 alır.
/// </summary>
public static class FakeCarrierEndpoints
{
    public const string Prefix = "/test/fake-carrier";
    public const string TokenHeader = "X-Internal-Token";

    private static readonly Dictionary<string, ShipmentStatus> SettableStatuses = new(StringComparer.Ordinal)
    {
        ["IN_TRANSIT"] = ShipmentStatus.InTransit,
        ["DELIVERED"] = ShipmentStatus.Delivered,
        ["DELIVERY_FAILED"] = ShipmentStatus.DeliveryFailed,
    };

    private const int MaxFaultTimes = 1000;

    public static void MapFakeCarrierEndpoints(this WebApplication app)
    {
        var options = CargoOptions.From(app.Services);
        if (!options.Carrier.IsFake
            || !options.Carrier.Fake.TestEndpointsEnabled
            || string.IsNullOrWhiteSpace(options.InternalApiToken))
        {
            return;
        }

        var expected = SHA256.HashData(Encoding.UTF8.GetBytes(options.InternalApiToken));
        app.UseWhen(
            context => context.Request.Path.StartsWithSegments(Prefix),
            branch => branch.Use(async (context, next) =>
            {
                var given = context.Request.Headers[TokenHeader].ToString();
                var givenHash = SHA256.HashData(Encoding.UTF8.GetBytes(given));
                if (given.Length == 0 || !CryptographicOperations.FixedTimeEquals(givenHash, expected))
                {
                    context.Response.StatusCode = StatusCodes.Status401Unauthorized;
                    return;
                }
                await next(context);
            }));

        var group = app.MapGroup(Prefix);

        // Gönderinin firmadaki durumunu değiştirir: { "status": "IN_TRANSIT" | "DELIVERED" | "DELIVERY_FAILED" }.
        // Değişiklikten sonra o gönderi periyodik turu beklemeden hemen sorgulanır; yanıt (204) sorgu
        // bittikten sonra döner. Sorgu başarısız olursa da 204 döner (firmadaki durum değişti; periyodik
        // tur alır).
        group.MapPost("/shipments/{trackingNo}/status", async (
            string trackingNo, StatusRequest request, FakeCarrierGateway gateway, TrackingPoller poller,
            CancellationToken ct) =>
        {
            if (request.Status is null || !SettableStatuses.TryGetValue(request.Status, out var status))
            {
                return Results.BadRequest(new { error = "status IN_TRANSIT, DELIVERED ya da DELIVERY_FAILED olmalı." });
            }
            if (!await gateway.SetStatusAsync(trackingNo, status, ct))
            {
                return Results.NotFound();
            }
            await poller.PollNowAsync(trackingNo, ct);
            return Results.NoContent();
        });

        // Sonraki gönderi açma çağrılarına hata uygular: { "mode": "TimeoutAfterCreate", "times": 2 }.
        // mode "None" planı temizler.
        group.MapPost("/faults", (FaultRequest request, FakeCarrierFaults faults) =>
        {
            if (!Enum.TryParse<FakeFailMode>(request.Mode, ignoreCase: true, out var mode)
                || !Enum.IsDefined(mode)
                || int.TryParse(request.Mode, out _))
            {
                return Results.BadRequest(new { error = "mode None, TimeoutBeforeCreate, TimeoutAfterCreate ya da Permanent olmalı." });
            }
            if (mode != FakeFailMode.None && request.Times is < 1 or > MaxFaultTimes)
            {
                return Results.BadRequest(new { error = $"times 1 ile {MaxFaultTimes} arasında olmalı." });
            }
            faults.Set(mode, request.Times);
            return Results.NoContent();
        });

        app.Logger.LogWarning("Sahte kargo firması test uçları açık ({Prefix}); üretimde kapatın", Prefix);
    }

    public sealed record StatusRequest(string? Status);

    public sealed record FaultRequest(string? Mode, int Times);
}
