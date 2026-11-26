using CargoService.Tests.Infrastructure;
using Npgsql;

namespace CargoService.Tests;

[Collection("Integration")]
public sealed class SchemaTests(ContainersFixture containers)
{
    [Fact]
    public async Task Migration_cargo_app_rolüyle_uygulanır_ve_tablolar_cargo_şemasında_oluşur()
    {
        await StartAppOnceAsync();

        // Uygulama cargo_app ile bağlanıp migration'ı uyguladığı için tabloların sahibi de cargo_app'tir.
        var tables = await QueryAsync(
            "SELECT tablename || '|' || tableowner FROM pg_tables WHERE schemaname = 'cargo' ORDER BY tablename");

        Assert.Equal(
            [
                "__ef_migrations_history|cargo_app",
                "fake_carrier_shipments|cargo_app",
                "outbox_messages|cargo_app",
                "processed_messages|cargo_app",
                "shipments|cargo_app",
            ],
            tables.Order(StringComparer.Ordinal).ToArray());
    }

    [Fact]
    public async Task Tekil_kısıtlar_ve_outbox_kısmi_indeksi_oluşur()
    {
        await StartAppOnceAsync();

        var indexes = await QueryAsync("SELECT indexdef FROM pg_indexes WHERE schemaname = 'cargo'");

        Assert.Contains(indexes, d => d.Contains("UNIQUE INDEX") && d.Contains("cargo.shipments") && d.Contains("(order_id)"));
        Assert.Contains(indexes, d => d.Contains("UNIQUE INDEX") && d.Contains("cargo.shipments") && d.Contains("(idempotency_key)"));
        Assert.Contains(indexes, d => d.Contains("UNIQUE INDEX") && d.Contains("cargo.fake_carrier_shipments") && d.Contains("(tracking_number)"));
        Assert.Contains(indexes, d => d.Contains("cargo.outbox_messages") && d.Contains("WHERE (published_at IS NULL)"));
    }

    [Fact]
    public async Task Cargo_app_public_şemasındaki_tabloları_okuyamaz()
    {
        await using (var admin = new NpgsqlConnection(containers.AdminConnectionString))
        {
            await admin.OpenAsync(TestContext.Current.CancellationToken);
            await using var create = new NpgsqlCommand(
                "CREATE TABLE IF NOT EXISTS public.orders (id bigint PRIMARY KEY)", admin);
            await create.ExecuteNonQueryAsync(TestContext.Current.CancellationToken);
        }

        await using var conn = new NpgsqlConnection(containers.CargoConnectionString);
        await conn.OpenAsync(TestContext.Current.CancellationToken);
        await using var select = new NpgsqlCommand("SELECT count(*) FROM public.orders", conn);

        var ex = await Assert.ThrowsAsync<PostgresException>(
            () => select.ExecuteScalarAsync(TestContext.Current.CancellationToken));
        Assert.Equal(PostgresErrorCodes.InsufficientPrivilege, ex.SqlState);
    }

    /// <summary>Uygulamayı açıp kapatır; açılışta bekleyen migration'lar uygulanır.</summary>
    private async Task StartAppOnceAsync()
    {
        await using var app = TestApp.For(containers);
        using var client = app.CreateClient();
    }

    private async Task<List<string>> QueryAsync(string sql)
    {
        var ct = TestContext.Current.CancellationToken;
        await using var conn = new NpgsqlConnection(containers.CargoConnectionString);
        await conn.OpenAsync(ct);
        await using var cmd = new NpgsqlCommand(sql, conn);
        await using var reader = await cmd.ExecuteReaderAsync(ct);
        var rows = new List<string>();
        while (await reader.ReadAsync(ct))
        {
            rows.Add(reader.GetString(0));
        }
        return rows;
    }
}
