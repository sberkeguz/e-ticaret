using DotNet.Testcontainers.Builders;
using DotNet.Testcontainers.Networks;
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

    // RabbitMQ'nun cargo kullanıcısı: depodaki docker/rabbitmq/init-cargo-user.sh ile kurulur.
    public const string RabbitCargoUser = "cargo";
    private const string RabbitCargoPassword = "cargo_rabbit_test_password";
    private const string RabbitAdminUser = "rabbitmq";
    private const string RabbitAdminPassword = "rabbitmq";
    private const string RabbitAlias = "rabbitmq";

    // Betik compose'daki gibi ayrı bir curl kapsayıcısında, RabbitMQ ile aynı (geçici) ağda çalışır.
    private readonly INetwork _network = new NetworkBuilder().Build();

    private readonly RabbitMqContainer _rabbit;

    public ContainersFixture()
    {
        _rabbit = new RabbitMqBuilder("rabbitmq:4-management-alpine")
            .WithUsername(RabbitAdminUser)
            .WithPassword(RabbitAdminPassword)
            .WithNetwork(_network)
            .WithNetworkAliases(RabbitAlias)
            .Build();
    }

    /// <summary>Süper kullanıcı bağlantısı: yalnızca test hazırlığı (ör. public'te tablo açmak) için.</summary>
    public string AdminConnectionString => _postgres.GetConnectionString();

    /// <summary>Servisin kullandığı bağlantı: cargo_app rolü.</summary>
    public string CargoConnectionString => new NpgsqlConnectionStringBuilder(AdminConnectionString)
    {
        Username = "cargo_app",
        Password = CargoPassword,
    }.ConnectionString;

    /// <summary>Yönetici bağlantısı: yalnızca test hazırlığı ve doğrulama için.</summary>
    public string RabbitMqUrl => _rabbit.GetConnectionString();

    /// <summary>Servisin kullandığı bağlantı: init-cargo-user.sh'nin dar izinli cargo kullanıcısı.</summary>
    public string CargoRabbitMqUrl => new UriBuilder(RabbitMqUrl)
    {
        UserName = RabbitCargoUser,
        Password = RabbitCargoPassword,
    }.Uri.ToString();

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
        await _network.CreateAsync();
        await Task.WhenAll(_postgres.StartAsync(), _rabbit.StartAsync());
        await Task.WhenAll(ApplyServiceRolesAsync(), ApplyRabbitCargoUserAsync());
    }

    public async ValueTask DisposeAsync()
    {
        await _postgres.DisposeAsync();
        await _rabbit.DisposeAsync();
        await _network.DisposeAsync();
    }

    /// <summary>
    /// Depodaki gerçek docker/rabbitmq/init-cargo-user.sh'yi compose'daki curl imajıyla çalıştırır; böylece
    /// testler üretimdeki izinlerle (ör. varsayılan exchange'e yazma yok) cargo kullanıcısıyla bağlanır.
    /// </summary>
    private async Task ApplyRabbitCargoUserAsync()
    {
        var script = await File.ReadAllBytesAsync(FindRepoFile("docker", "rabbitmq", "init-cargo-user.sh"));
        await using var init = new ContainerBuilder("curlimages/curl:8.17.0")
            .WithNetwork(_network)
            .WithResourceMapping(script, FilePath.Of("/init/init-cargo-user.sh"))
            .WithEntrypoint("/bin/sh", "/init/init-cargo-user.sh")
            .WithEnvironment("RABBITMQ_API", $"http://{RabbitAlias}:15672/api")
            .WithEnvironment("RABBITMQ_ADMIN_USER", RabbitAdminUser)
            .WithEnvironment("RABBITMQ_ADMIN_PASSWORD", RabbitAdminPassword)
            .WithEnvironment("CARGO_USER", RabbitCargoUser)
            .WithEnvironment("CARGO_PASSWORD", RabbitCargoPassword)
            .Build();
        await init.StartAsync();
        var exitCode = await init.GetExitCodeAsync();
        if (exitCode != 0)
        {
            var (_, stderr) = await init.GetLogsAsync();
            throw new InvalidOperationException($"init-cargo-user.sh başarısız ({exitCode}): {stderr}");
        }
    }

    private async Task ApplyServiceRolesAsync()
    {
        var script = await File.ReadAllBytesAsync(FindRepoFile("docker", "postgres", "service-roles.sql"));
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
    private static string FindRepoFile(params string[] relative)
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine([dir.FullName, .. relative]);
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        throw new FileNotFoundException(string.Join('/', relative) + " bulunamadı.");
    }
}
