using Npgsql;
using Testcontainers.PostgreSql;
using Testcontainers.RabbitMq;

namespace CargoService.Tests.Infrastructure;

/// <summary>
/// Postgres ve RabbitMQ kapsayıcılarını test oturumu başına bir kez açar ("Integration" koleksiyonu).
/// Postgres'e depodaki gerçek docker/postgres/service-roles.sql, kapsayıcı içindeki psql ile uygulanır;
/// böylece testler üretimdeki gibi yalnızca cargo_app rolüyle bağlanır.
/// </summary>
public sealed class ContainersFixture : IAsyncLifetime
{
    // Yalnızca geçici test kapsayıcısı içindir; gerçek ortam parolalarıyla ilgisi yoktur.
    private const string CargoPassword = "cargo_test_password";
    private const string Database = "eticaret";
    private const string AdminUser = "eticaret_user";

    private readonly PostgreSqlContainer _postgres = new PostgreSqlBuilder("postgres:17-alpine")
        .WithDatabase(Database)
        .WithUsername(AdminUser)
        .WithPassword("eticaret_test_password")
        .Build();

    private readonly RabbitMqContainer _rabbit = new RabbitMqBuilder("rabbitmq:4-management-alpine").Build();

    /// <summary>Süper kullanıcı bağlantısı: yalnızca test hazırlığı (ör. public'te tablo açmak) için.</summary>
    public string AdminConnectionString => _postgres.GetConnectionString();

    /// <summary>Servisin kullandığı bağlantı: cargo_app rolü.</summary>
    public string CargoConnectionString => new NpgsqlConnectionStringBuilder(AdminConnectionString)
    {
        Username = "cargo_app",
        Password = CargoPassword,
    }.ConnectionString;

    public string RabbitMqUrl => _rabbit.GetConnectionString();

    /// <summary>
    /// RabbitMQ'daki bütün istemci bağlantılarını sunucu tarafında kapatır (ağ kesintisi benzetimi).
    /// Kapsayıcı durdurulmaz; böylece port eşlemesi değişmez ve diğer testler etkilenmez.
    /// </summary>
    public async Task CloseRabbitConnectionsAsync()
    {
        var result = await _rabbit.ExecAsync(["rabbitmqctl", "close_all_connections", "test: baglanti kesintisi"]);
        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException($"RabbitMQ bağlantıları kapatılamadı: {result.Stderr}");
        }
    }

    public async ValueTask InitializeAsync()
    {
        await Task.WhenAll(_postgres.StartAsync(), _rabbit.StartAsync());
        await ApplyServiceRolesAsync();
    }

    public async ValueTask DisposeAsync()
    {
        await _postgres.DisposeAsync();
        await _rabbit.DisposeAsync();
    }

    private async Task ApplyServiceRolesAsync()
    {
        var script = await File.ReadAllBytesAsync(FindServiceRolesSql());
        await _postgres.CopyAsync(script, "/tmp/service-roles.sql");

        var result = await _postgres.ExecAsync(
        [
            "psql", "-v", "ON_ERROR_STOP=1",
            "--username", AdminUser, "--dbname", Database,
            "-v", "backend_password=backend_test_password",
            "-v", "iyzico_password=iyzico_test_password",
            "-v", $"cargo_password={CargoPassword}",
            "-f", "/tmp/service-roles.sql",
        ]);
        if (result.ExitCode != 0)
        {
            throw new InvalidOperationException($"service-roles.sql uygulanamadı: {result.Stderr}");
        }
    }

    /// <summary>Test çıktı klasöründen yukarı çıkarak depodaki betiği bulur.</summary>
    private static string FindServiceRolesSql()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "docker", "postgres", "service-roles.sql");
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        throw new FileNotFoundException("docker/postgres/service-roles.sql bulunamadı.");
    }
}
