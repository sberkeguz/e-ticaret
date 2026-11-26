using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using System.Net.Sockets;
using CargoService.Carriers;
using CargoService.Data;
using CargoService.Messaging.Contracts;
using CargoService.Tests.Infrastructure;
using Microsoft.Extensions.DependencyInjection;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class FakeCarrierTests(ContainersFixture containers)
{
    private const string Token = "test-internal-token-7f3a";

    private static readonly Recipient Recipient = new()
    {
        FirstName = "Ali", LastName = "Veli", Phone = "05550000000", City = "Ankara",
        District = "Çankaya", Neighborhood = "Kızılay", Address = "Test cd. 1",
    };

    private static readonly Parcel Parcel = new() { ItemCount = 1, DeclaredValue = 10m };

    private static string NewKey() => $"order-{Random.Shared.NextInt64(1_000_000, long.MaxValue / 2)}";

    private TestApp App(Dictionary<string, string?>? settings = null) => TestApp.For(containers, settings);

    private static Dictionary<string, string?> EndpointsOn() => new()
    {
        ["Carrier:Provider"] = "Fake",
        ["Carrier:Fake:TestEndpointsEnabled"] = "true",
        ["InternalApiToken"] = Token,
    };

    private static ICarrierGateway Gateway(TestApp app) => app.Services.GetRequiredService<ICarrierGateway>();

    private static CancellationToken Ct => TestContext.Current.CancellationToken;

    [Fact]
    public async Task Aynı_anahtar_aynı_takip_numarasını_farklı_anahtar_farklısını_alır()
    {
        await using var app = App();
        var gateway = Gateway(app);
        var key = NewKey();

        var first = Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        var again = Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        var other = Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(NewKey(), Recipient, Parcel, Ct));

        Assert.Matches("^TK[0-9]{10}$", first.TrackingNumber);
        Assert.Equal(first.TrackingNumber, again.TrackingNumber);
        Assert.NotEqual(first.TrackingNumber, other.TrackingNumber);
        Assert.Equal(first.TrackingNumber,
            Assert.IsType<CarrierLookupResult.Found>(await gateway.FindByReferenceAsync(key, Ct)).TrackingNumber);
        Assert.Equal(ShipmentStatus.Created,
            Assert.IsType<CarrierStatusResult.Status>(await gateway.GetStatusAsync(first.TrackingNumber, Ct)).Value);
        Assert.Equal("FAKE", gateway.CarrierCode);
        Assert.Equal("Test Kargo", gateway.CarrierName);
    }

    [Fact]
    public async Task Bilinmeyen_anahtar_ve_takip_numarası_bulunamaz()
    {
        await using var app = App();
        var gateway = Gateway(app);

        Assert.IsType<CarrierLookupResult.NotFound>(await gateway.FindByReferenceAsync(NewKey(), Ct));
        Assert.IsType<CarrierStatusResult.NotFound>(await gateway.GetStatusAsync("TK9999999999", Ct));
    }

    [Fact]
    public async Task TimeoutBeforeCreate_belirsiz_döner_ve_kayıt_açmaz_sonra_normale_döner()
    {
        await using var app = App(new() { ["Carrier:Fake:FailMode"] = "TimeoutBeforeCreate", ["Carrier:Fake:FailTimes"] = "1" });
        var gateway = Gateway(app);
        var key = NewKey();

        Assert.IsType<CarrierCreateResult.Uncertain>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        Assert.IsType<CarrierLookupResult.NotFound>(await gateway.FindByReferenceAsync(key, Ct));
        Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
    }

    [Fact]
    public async Task TimeoutAfterCreate_belirsiz_döner_ama_kayıt_firmada_vardır()
    {
        await using var app = App(new() { ["Carrier:Fake:FailMode"] = "TimeoutAfterCreate", ["Carrier:Fake:FailTimes"] = "1" });
        var gateway = Gateway(app);
        var key = NewKey();

        Assert.IsType<CarrierCreateResult.Uncertain>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        var found = Assert.IsType<CarrierLookupResult.Found>(await gateway.FindByReferenceAsync(key, Ct));
        var retry = Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        Assert.Equal(found.TrackingNumber, retry.TrackingNumber);
    }

    [Fact]
    public async Task Permanent_verilen_sayıda_kalıcı_ret_döner_kayıt_açmaz()
    {
        await using var app = App(new() { ["Carrier:Fake:FailMode"] = "Permanent", ["Carrier:Fake:FailTimes"] = "2" });
        var gateway = Gateway(app);
        var key = NewKey();

        var failure = Assert.IsType<CarrierCreateResult.PermanentFailure>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        Assert.False(string.IsNullOrWhiteSpace(failure.Reason));
        Assert.IsType<CarrierCreateResult.PermanentFailure>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        Assert.IsType<CarrierLookupResult.NotFound>(await gateway.FindByReferenceAsync(key, Ct));
        Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
    }

    [Fact]
    public async Task DelayMs_firma_gecikmesini_taklit_eder()
    {
        await using var app = App(new() { ["Carrier:Fake:DelayMs"] = "300" });
        var gateway = Gateway(app);

        var watch = Stopwatch.StartNew();
        await gateway.CreateAsync(NewKey(), Recipient, Parcel, Ct);

        Assert.True(watch.ElapsedMilliseconds >= 290, $"{watch.ElapsedMilliseconds} ms");
    }

    [Fact]
    public async Task Provider_Fake_değilse_servis_açılmaz_firma_bağlantısı_çözülemez()
    {
        await using var app = App(new() { ["Carrier:Provider"] = "Baska" });

        var ex = Record.Exception(() => Gateway(app));
        Assert.NotNull(ex);
        Assert.Contains("Carrier:Provider", ex.ToString());
    }

    [Fact]
    public async Task Test_ucu_doğru_tokenla_durumu_değiştirir()
    {
        await using var app = App(EndpointsOn());
        var gateway = Gateway(app);
        var created = Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(NewKey(), Recipient, Parcel, Ct));
        using var client = Client(app, Token);

        var response = await client.PostAsJsonAsync(
            $"/test/fake-carrier/shipments/{created.TrackingNumber}/status", new { status = "IN_TRANSIT" }, Ct);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Equal(ShipmentStatus.InTransit,
            Assert.IsType<CarrierStatusResult.Status>(await gateway.GetStatusAsync(created.TrackingNumber, Ct)).Value);

        response = await client.PostAsJsonAsync(
            $"/test/fake-carrier/shipments/{created.TrackingNumber}/status", new { status = "DELIVERED" }, Ct);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        Assert.Equal(ShipmentStatus.Delivered,
            Assert.IsType<CarrierStatusResult.Status>(await gateway.GetStatusAsync(created.TrackingNumber, Ct)).Value);

        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(
            $"/test/fake-carrier/shipments/{created.TrackingNumber}/status", new { status = "LOST" }, Ct)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.PostAsJsonAsync(
            "/test/fake-carrier/shipments/TK9999999999/status", new { status = "DELIVERED" }, Ct)).StatusCode);
    }

    [Fact]
    public async Task Hata_ucu_sonraki_gönderi_açma_çağrılarını_etkiler()
    {
        await using var app = App(EndpointsOn());
        var gateway = Gateway(app);
        using var client = Client(app, Token);

        var response = await client.PostAsJsonAsync("/test/fake-carrier/faults", new { mode = "Permanent", times = 1 }, Ct);
        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        var key = NewKey();
        Assert.IsType<CarrierCreateResult.PermanentFailure>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));
        Assert.IsType<CarrierCreateResult.Created>(await gateway.CreateAsync(key, Recipient, Parcel, Ct));

        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(
            "/test/fake-carrier/faults", new { mode = "Kaos", times = 1 }, Ct)).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(
            "/test/fake-carrier/faults", new { mode = "Permanent", times = 0 }, Ct)).StatusCode);
    }

    public static TheoryData<string, string?, string?> ClosedEndpointSettings => new()
    {
        // Sağlayıcı Fake değilse servis hiç açılmaz (CarrierProviderConfigTests, QaConfigTests).
        { "Fake", "false", Token }, // bayrak kapalı
        { "Fake", null, Token }, // bayrak varsayılan (kapalı)
        { "Fake", "true", "" }, // token ayarı boş
        { "Fake", "true", null }, // token ayarı yok
    };

    [Theory]
    [MemberData(nameof(ClosedEndpointSettings))]
    public async Task Test_uçları_koşullar_sağlanmazsa_hiç_eşlenmez_404(string provider, string? enabled, string? token)
    {
        var settings = new Dictionary<string, string?> { ["Carrier:Provider"] = provider, ["InternalApiToken"] = token };
        if (enabled is not null)
        {
            settings["Carrier:Fake:TestEndpointsEnabled"] = enabled; // null: anahtar hiç verilmez (varsayılan)
        }
        await using var app = App(settings);
        using var client = Client(app, Token);

        Assert.Equal(HttpStatusCode.NotFound, (await client.PostAsJsonAsync(
            "/test/fake-carrier/shipments/TK0000000001/status", new { status = "DELIVERED" }, Ct)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.PostAsJsonAsync(
            "/test/fake-carrier/faults", new { mode = "Permanent", times = 1 }, Ct)).StatusCode);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("yanlis-token")]
    [InlineData(Token + "x")]
    public async Task Token_yoksa_ya_da_yanlışsa_401(string? token)
    {
        await using var app = App(EndpointsOn());
        using var client = Client(app, token);

        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync(
            "/test/fake-carrier/shipments/TK0000000001/status", new { status = "DELIVERED" }, Ct)).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync(
            "/test/fake-carrier/faults", new { mode = "Permanent", times = 1 }, Ct)).StatusCode);
    }

    private static HttpClient Client(TestApp app, string? token)
    {
        var client = app.CreateClient();
        if (token is not null)
        {
            client.DefaultRequestHeaders.TryAddWithoutValidation("X-Internal-Token", token);
        }
        return client;
    }
}

public sealed class CarrierErrorsTests
{
    [Theory]
    [InlineData(HttpStatusCode.InternalServerError, true)]
    [InlineData(HttpStatusCode.BadGateway, true)]
    [InlineData(HttpStatusCode.ServiceUnavailable, true)]
    [InlineData(HttpStatusCode.RequestTimeout, true)]
    [InlineData(HttpStatusCode.TooManyRequests, true)]
    [InlineData(HttpStatusCode.BadRequest, false)]
    [InlineData(HttpStatusCode.UnprocessableEntity, false)]
    public void HTTP_durumu_sınıflandırılır(HttpStatusCode status, bool uncertain) =>
        Assert.Equal(uncertain, CarrierErrors.IsUncertain(status));

    [Fact]
    public void Zaman_aşımı_ve_ağ_hatası_belirsizdir_çağıran_iptali_değildir()
    {
        Assert.True(CarrierErrors.IsUncertain(new TaskCanceledException(), CancellationToken.None));
        Assert.True(CarrierErrors.IsUncertain(new TimeoutException(), CancellationToken.None));
        Assert.True(CarrierErrors.IsUncertain(new HttpRequestException("ağ"), CancellationToken.None));
        Assert.True(CarrierErrors.IsUncertain(new SocketException(), CancellationToken.None));
        Assert.True(CarrierErrors.IsUncertain(
            new HttpRequestException("5xx", null, HttpStatusCode.BadGateway), CancellationToken.None));
        Assert.False(CarrierErrors.IsUncertain(
            new HttpRequestException("4xx", null, HttpStatusCode.BadRequest), CancellationToken.None));
        Assert.False(CarrierErrors.IsUncertain(new InvalidOperationException(), CancellationToken.None));

        using var cts = new CancellationTokenSource();
        cts.Cancel();
        Assert.False(CarrierErrors.IsUncertain(new OperationCanceledException(cts.Token), cts.Token));
    }
}
