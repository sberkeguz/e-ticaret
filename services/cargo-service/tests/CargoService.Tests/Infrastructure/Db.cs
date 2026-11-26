using Npgsql;

namespace CargoService.Tests.Infrastructure;

/// <summary>Testlerde cargo_app rolüyle doğrudan SQL çalıştırmak için küçük yardımcılar.</summary>
public static class Db
{
    public static async Task<int> ExecuteAsync(ContainersFixture containers, string sql, params object?[] args)
    {
        await using var conn = new NpgsqlConnection(containers.CargoConnectionString);
        await conn.OpenAsync();
        await using var cmd = Command(conn, sql, args);
        return await cmd.ExecuteNonQueryAsync();
    }

    public static async Task<T?> ScalarAsync<T>(ContainersFixture containers, string sql, params object?[] args)
    {
        await using var conn = new NpgsqlConnection(containers.CargoConnectionString);
        await conn.OpenAsync();
        await using var cmd = Command(conn, sql, args);
        var value = await cmd.ExecuteScalarAsync();
        return value is null or DBNull ? default : (T)value;
    }

    /// <summary>Konumsal parametreler: SQL içinde $1, $2, ...</summary>
    private static NpgsqlCommand Command(NpgsqlConnection conn, string sql, object?[] args)
    {
        var cmd = new NpgsqlCommand(sql, conn);
        foreach (var arg in args)
        {
            cmd.Parameters.Add(new NpgsqlParameter { Value = arg ?? DBNull.Value });
        }
        return cmd;
    }

    /// <summary>Yayınlanmamış bir outbox satırı ekler ve id'sini döner.</summary>
    public static async Task<Guid> InsertOutboxAsync(
        ContainersFixture containers, string routingKey, string? traceId = null, string exchange = "cargo")
    {
        var id = Guid.CreateVersion7();
        var payload = $$"""{"messageId":"{{id}}","type":"TestEvent","version":1,"traceId":"{{traceId ?? ""}}","marker":"GIZLI-PAYLOAD-{{id}}"}""";
        await ExecuteAsync(containers,
            "INSERT INTO cargo.outbox_messages (id, exchange, routing_key, type, payload, created_at, attempts) " +
            "VALUES ($1, $2, $3, 'TestEvent', $4::jsonb, now(), 0)",
            id, exchange, routingKey, payload);
        return id;
    }
}
